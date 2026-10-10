// 파일명: RunService.kt
// 목적: 몇 시간씩 걸리는 배치 실행(BatchRunner)을 화면이 꺼져도 계속 돌리는 포그라운드 서비스 (spec §10)
// 기능: - 유형: foregroundServiceType="specialUse"(PROPERTY_SPECIAL_USE_FGS_SUBTYPE). dataSync 는 Android 15+ 에서 24시간당 6시간 제한이 있어 쓰지 않는다.
//       - 시작: RunService.start(context, config) — 한 번에 하나만 실행. 실행 중에 또 시작하면 거부(로그 + Running.rejectedStart, false 리턴).
//         RunConfig 는 ModelSpec·VadPreset·InputItem 객체를 담고 있어 Intent 로 직렬화하지 않고, companion 의 대기 슬롯(프로세스 메모리)에 두고
//         Intent 에는 토큰과 runId 만 넣는다. 프로세스가 죽으면 대기 설정·실행 상태가 사라진다(START_NOT_STICKY — 시스템이 다시 시작하지 않음).
//         이어하기는 화면이 같은 runId·resume=true 로 다시 start 하면 BatchRunner 가 manifest·inFlight 로 정리한다.
//       - 실행: 서비스 소유 CoroutineScope(SupervisorJob + Dispatchers.Default)에서 BatchRunner.run(config) 를 수집한다.
//         이벤트마다 events(StateFlow<RunUiState>) 와 진행 알림(모델/VAD 프리셋/파일, done/total, "취소" 버튼)을 갱신한다.
//       - 취소: RunService.cancel(context) 또는 알림 "취소" → ACTION_CANCEL → 실행 Job 취소(BatchRunner/ItemProcessor 가 engine.cancel 로 전파).
//       - 끝: 정상/실패/취소 모두 메인 스레드에서 handle=null → 포그라운드 해제 → 결과 알림 1건 → stopSelfResult(마지막 startId) → 최종 상태 게시
//         (change(add)-hyungchul-2026-10-11-03:00 순서 변경: 게시는 RunCoordinator 가 같은 메인 스레드 블록 끝에서 함).
//         최종 상태를 메인 스레드에서 handle=null 이후에 게시하므로, 화면이 Finished 를 본 직후 새 실행을 시작해도 이전 Job 과 겹치지 않는다.
//       - 깨우기 잠금: PARTIAL_WAKE_LOCK 을 실행 중에만 쥔다(참조 계수 없음). 안전 제한 시간 6시간을 두되 항목마다(Progress 이벤트) 다시
//         acquire 해 제한 시간을 새로 시작하므로 실행 전체 길이에는 제한이 없고, 한 항목(모델 로드 포함)이 6시간 넘게 멈추면 잠금이 자동으로 풀린다.
//         실행이 끝나면 finally 에서 해제, onDestroy 에서도 한 번 더 해제.
//       - 알림 권한(POST_NOTIFICATIONS)은 화면이 요청한다. 거부돼도 startForeground 는 그대로 호출하며 실행은 계속된다(이때 알림은 서랍에 보이지 않고
//         Android 13+ 작업 관리자(FGS Task Manager)에만 나타날 수 있다). 진행·결과 알림 갱신은 권한이 있을 때만 한다.
//       - (change(add)-hyungchul-2026-10-11-03:00 리뷰 수정 1) 상태기계(시작 수락/거부·대기 슬롯·실행 Job 수명·최종 상태)를 RunCoordinator 로 옮겨 JVM 에서 검증한다.
//         실행 Job 은 CoroutineStart.ATOMIC 으로 시작해 본문 시작 전에 취소돼도(시작 직후 취소·onDestroy) 정리(잠금 해제·포그라운드 해제·stopSelf·최종 상태)가 반드시 실행된다.
//         최종 상태 분류: 사용자 취소(cancelByUser)만 Cancelled, 그 밖의 취소(서비스 파괴)·예외는 Failed. 깨우기 잠금 획득도 실행 본문(try) 안에서 한다.
// change(add)-hyungchul-2026-10-11-02:40
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import android.Manifest // 권한 이름
import android.app.Notification // 알림
import android.app.NotificationChannel // 알림 채널
import android.app.NotificationManager // 알림 관리자
import android.app.PendingIntent // 알림 동작 인텐트
import android.app.Service // 서비스 기본 클래스
import android.content.Context // 컨텍스트
import android.content.Intent // 인텐트
import android.content.pm.PackageManager // 권한 결과 상수
import android.content.pm.ServiceInfo // 포그라운드 서비스 유형 상수
import android.os.Build // SDK 버전
import android.os.IBinder // 바인더(사용 안 함)
import android.os.PowerManager // 깨우기 잠금
import android.util.Log // 로그
import androidx.core.app.NotificationCompat // 알림 빌더
import androidx.core.content.ContextCompat // 포그라운드 서비스 시작·권한 확인
import com.cowork.stt_ondevice.App // 전역 객체 그래프
import com.cowork.stt_ondevice.MainActivity // 알림 눌렀을 때 열 화면
import kotlinx.coroutines.CoroutineScope // 서비스 소유 코루틴 범위
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.SupervisorJob // 자식 실패가 범위를 깨지 않게
import kotlinx.coroutines.cancel // 범위 취소
import kotlinx.coroutines.flow.Flow // 이벤트 흐름 // change(add)-hyungchul-2026-10-11-03:00
import kotlinx.coroutines.flow.StateFlow // 읽기 전용 상태

// change(add)-hyungchul-2026-10-11-02:40
/**
 * 목적: 배치 실행 포그라운드 서비스
 * 기능: 파일 머리말 참조. 바인딩은 지원하지 않는다(onBind = null). onStartCommand 와 종료 처리(host.onRunEnded)는 메인 스레드에서만 handle 을 만진다.
 */
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) // 서비스 소유 범위(실행은 Default 스레드)
    private var handle: RunHandle? = null // 현재 실행 핸들(메인 스레드에서만 읽고 씀) // change(add)-hyungchul-2026-10-11-03:00 job → handle(사용자 취소 구분)
    private var lastStartId = 0 // 마지막으로 받은 startId(stopSelfResult 용, 메인 스레드)
    @Volatile private var wakeLock: PowerManager.WakeLock? = null // 실행 중 CPU 유지 잠금

    /**
     * 목적: 서비스 생성
     * 기능: 알림 채널(중요도 LOW — 소리·진동 없음)을 만든다(이미 있으면 시스템이 무시).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun onCreate() {
        super.onCreate() // 부모 초기화
        val channel = NotificationChannel(CHANNEL_ID, "배치 실행", NotificationManager.IMPORTANCE_LOW) // 진행 알림 채널
        channel.description = "STT 배치 실행 진행률과 취소 버튼" // 채널 설명
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel) // 채널 등록
    }

    /**
     * 목적: 바인딩 거부
     * 기능: 이 서비스는 시작형만 지원한다.
     * 입력: intent(바인딩 요청)
     * 출력: 없음
     * 리턴: null
     */
    override fun onBind(intent: Intent?): IBinder? = null // 바인딩 없음

    /**
     * 목적: 시작·취소 명령 처리(메인 스레드)
     * 기능: ACTION_START 는 handleStart, ACTION_CANCEL 은 handleCancel, 그 밖은 로그만 남기고 실행 중이 아니면 종료한다.
     * 입력: intent(명령), flags(재전달 플래그), startId(이번 시작 번호)
     * 출력: 없음
     * 리턴: START_NOT_STICKY(프로세스가 죽어도 시스템이 다시 시작하지 않음 — 대기 설정이 메모리에만 있기 때문)
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId // 마지막 시작 번호 기록
        when (intent?.action) { // 명령별
            ACTION_START -> handleStart(intent, startId) // 실행 시작
            ACTION_CANCEL -> handleCancel(startId) // 실행 취소
            else -> { // 알 수 없는 명령
                Log.w(TAG, "알 수 없는 명령 무시: ${intent?.action}") // 로그
                if (handle == null) stopSelfResult(startId) // 실행 중이 아니면 종료
            }
        }
        return START_NOT_STICKY // 재시작 안 함
    }

    /**
     * 목적: 실행 시작(메인 스레드)
     * 기능: startForegroundService 로 왔으므로 먼저 startForeground 를 호출한다(실패해도 상태를 Failed 로 남기고 종료).
     *       토큰에 맞는 대기 설정을 꺼내 Job 을 띄운다. 설정이 없거나(프로세스 재시작 등) 이미 실행 중이면 Job 을 만들지 않는다.
     * 입력: intent(토큰·runId 포함), startId(이번 시작 번호)
     * 출력: events 갱신, 알림
     * 리턴: 없음
     */
    private fun handleStart(intent: Intent, startId: Int) {
        val runId = intent.getStringExtra(EXTRA_RUN_ID) ?: "-" // 실행 ID(설정이 사라졌을 때 표시용)
        val config = coordinator.takePending(intent.getStringExtra(EXTRA_TOKEN)) // 대기 설정 꺼내기(토큰 일치 시)
        try {
            enterForeground(buildProgressNotification(events.value)) // 포그라운드 진입(시작 후 몇 초 안에 필수)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 실패: $runId", e) // 로그
            if (handle == null) { // 실행 중이 아니면
                coordinator.publishTerminal(RunUiState.Failed(config?.runId ?: runId, "포그라운드 서비스 시작 실패: ${RunCoordinator.describe(e)}")) // 상태 기록(설정이 없어도 Running 에 머물지 않게)
                stopSelfResult(startId) // 종료
            }
            return // 진행하지 않음
        }
        if (handle != null) { // 이미 실행 중(정상 경로에서는 start() 가 먼저 거부함)
            Log.w(TAG, "이미 실행 중이라 시작 요청 무시: $runId") // 로그
            if (config != null) coordinator.markRejected(config.runId) // 거부 사유 표시(문구는 RunCoordinator.rejectMessage)
            return // 기존 실행 유지
        }
        if (config == null) { // 대기 설정이 없음(토큰 불일치·프로세스 재시작)
            Log.e(TAG, "대기 중인 실행 설정이 없음: $runId") // 로그
            coordinator.publishTerminal(RunUiState.Failed(runId, "실행 설정을 찾을 수 없음(앱 프로세스가 다시 시작됐을 수 있음) — 같은 runId 로 이어하기를 다시 시작하세요")) // 상태 기록
            leaveForeground() // 포그라운드 해제
            stopSelfResult(startId) // 종료
            return // 끝
        }
        Log.i(TAG, "실행 시작: ${config.runId} resume=${config.resume}") // 로그
        handle = coordinator.launch(scope, config, host, Dispatchers.Main) // 실행 Job 시작(ATOMIC — 시작 전 취소에도 정리 보장) // change(add)-hyungchul-2026-10-11-03:00
    }

    /**
     * 목적: 실행 취소(메인 스레드)
     * 기능: 실행 Job 이 있으면 취소한다(정리·상태 게시는 Job 의 finally 가 함). 없으면 로그만 남기고, 이 명령으로 서비스가 막 만들어진 경우 종료한다.
     * 입력: startId(이번 시작 번호)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun handleCancel(startId: Int) {
        val h = handle // 현재 실행 핸들
        if (h == null) { // 실행 중 아님
            Log.i(TAG, "실행 중이 아니라 취소 요청 무시") // 로그
            stopSelfResult(startId) // 더 받은 명령이 없으면 종료
            return // 끝
        }
        Log.i(TAG, "사용자 취소 요청") // 로그
        h.cancelByUser() // 사용자 취소 표시 + Job 취소 → BatchRunner 가 engine.cancel·manifest 정리, 최종 상태 Cancelled // change(add)-hyungchul-2026-10-11-03:00
    }

    // change(add)-hyungchul-2026-10-11-03:00
    /**
     * 목적: 실행 Job 콜백(RunCoordinator 가 호출)
     * 기능: 실행 흐름 = App.newBatchRunner().run(config). 시작 준비 = 깨우기 잠금 획득(실패하면 Failed, 정리는 보장). 진행 = 잠금 연장(실패는 로그만)·진행 알림.
     *       실패 = 로그. 정리(실행 스레드) = 잠금 해제. 종료(메인 스레드) = handle=null → 포그라운드 해제 → 결과 알림 → stopSelfResult(마지막 startId)
     *       (최종 상태 게시는 이 콜백 직후 RunCoordinator 가 같은 메인 스레드 블록에서 하므로, 화면이 최종 상태를 본 뒤의 새 시작은 이전 실행과 겹치지 않는다).
     *       RunHost 가 internal 이라 공개 클래스인 서비스가 직접 구현하지 않고 내부 객체로 둔다.
     */
    private val host = object : RunHost {
        /** 목적: 실행 흐름 만들기. 입력: config(실행 설정). 출력: 조립 실패 시 예외. 리턴: BatchRunner 이벤트 흐름. */
        override fun runFlow(config: RunConfig): Flow<RunEvent> = App.from(this@RunService).newBatchRunner().run(config) // 실제 의존성으로 조립·실행

        /** 목적: 시작 준비(깨우기 잠금 획득). 입력: 없음. 출력: 실패 시 예외. 리턴: 없음. */
        override fun onRunStarting() = acquireWakeLock() // CPU 유지 시작(예외는 RunCoordinator 가 Failed 로)

        /** 목적: 진행 이벤트 처리(잠금 연장·진행 알림). 입력: p(진행률). 출력: 알림. 리턴: 없음. */
        override fun onProgress(p: RunEvent.Progress) {
            try {
                acquireWakeLock() // 안전 제한 시간 다시 시작(항목마다)
            } catch (e: Exception) {
                Log.w(TAG, "깨우기 잠금 연장 실패", e) // 연장 실패로 실행을 멈추지 않음
            }
            postNotification(NOTIFICATION_ID, buildProgressNotification(events.value)) // 진행 알림 갱신(상태는 이미 반영됨)
        }

        /** 목적: 실패 로그. 입력: t(실행을 멈춘 예외). 출력: 로그. 리턴: 없음. */
        override fun onRunFailed(t: Throwable) {
            Log.e(TAG, "실행 실패", t) // 로그
        }

        /** 목적: 실행 스레드 쪽 정리(잠금 해제). 입력·출력·리턴: 없음. */
        override fun onRunFinishing() = releaseWakeLock() // CPU 유지 해제

        /** 목적: 종료 처리(메인 스레드). 입력: result(최종 상태). 출력: 포그라운드 해제·결과 알림·서비스 종료 요청. 리턴: 없음. */
        override fun onRunEnded(result: RunUiState) {
            handle = null // 실행 없음
            leaveForeground() // 포그라운드 해제
            postNotification(RESULT_NOTIFICATION_ID, buildResultNotification(result)) // 결과 알림
            Log.i(TAG, "실행 종료: $result") // 로그
            stopSelfResult(lastStartId) // 더 받은 명령이 없으면 종료
        }
    }

    /**
     * 목적: 서비스 파괴
     * 기능: 범위를 취소해 남은 실행을 멈추고(사용자 취소가 아니므로 Failed 게시 — change(add)-hyungchul-2026-10-11-03:00) 깨우기 잠금을 해제한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun onDestroy() {
        scope.cancel() // 남은 실행 취소
        releaseWakeLock() // 잠금 해제(이미 풀렸으면 무시)
        super.onDestroy() // 부모 정리
    }

    /**
     * 목적: 포그라운드 진입
     * 기능: API 34+ 는 유형 FOREGROUND_SERVICE_TYPE_SPECIAL_USE 를 명시하고, 그 아래(31~33)는 Manifest 유형을 쓰는 2인자 호출을 한다.
     * 입력: notification(포그라운드 알림)
     * 출력: 허용되지 않으면 예외(ForegroundServiceStartNotAllowedException·SecurityException 등)
     * 리턴: 없음
     */
    private fun enterForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34+
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) // specialUse 명시
        } else {
            startForeground(NOTIFICATION_ID, notification) // Manifest 유형 사용
        }
    }

    /**
     * 목적: 포그라운드 해제
     * 기능: 진행 알림을 지우며 포그라운드 상태를 끝낸다(실패는 로그만).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    private fun leaveForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE) // 진행 알림 제거
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground 실패", e) // 로그만
        }
    }

    /**
     * 목적: 깨우기 잠금 획득·연장
     * 기능: 처음이면 PARTIAL_WAKE_LOCK(참조 계수 없음)을 만들고, acquire(제한 시간)으로 쥔다. 이미 쥐고 있으면 제한 시간이 새로 시작된다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    private fun acquireWakeLock() {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java) // 기존 잠금 또는 새로 만들기
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG) // CPU 만 유지
            .also { it.setReferenceCounted(false); wakeLock = it } // 참조 계수 없음(acquire 여러 번 → release 한 번)
        wl.acquire(WAKE_LOCK_TIMEOUT_MS) // 획득(제한 시간 새로 시작)
    }

    /**
     * 목적: 깨우기 잠금 해제
     * 기능: 쥐고 있으면 해제한다(제한 시간으로 이미 풀렸으면 아무것도 안 함).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() } // 쥔 경우만 해제
    }

    /**
     * 목적: 알림 게시(권한 확인)
     * 기능: Android 13+ 에서 POST_NOTIFICATIONS 가 없으면 게시하지 않는다(포그라운드 알림 자체는 startForeground 가 처리). 실패는 로그만.
     * 입력: id(알림 ID), notification(알림)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun postNotification(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && // API 33+ 는 런타임 권한
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED // 권한 없음
        ) return // 게시 안 함
        try {
            getSystemService(NotificationManager::class.java).notify(id, notification) // 게시
        } catch (e: SecurityException) {
            Log.w(TAG, "알림 게시 실패", e) // 로그만
        }
    }

    /**
     * 목적: 진행 알림 만들기
     * 기능: 진행 중 알림(지울 수 없음, 소리 없음, 즉시 표시)에 제목 "배치 실행 중 done/total", 본문 "모델 / VAD 프리셋 / 파일",
     *       진행 막대, 앱 열기, "취소" 버튼을 넣는다. 진행률이 아직 없으면 "준비 중"과 무한 진행 막대.
     * 입력: state(현재 화면 상태)
     * 출력: 없음
     * 리턴: Notification
     */
    private fun buildProgressNotification(state: RunUiState): Notification {
        val running = state as? RunUiState.Running // 실행 중 상태
        val p = running?.progress // 최근 진행률
        val b = NotificationCompat.Builder(this, CHANNEL_ID) // 빌더
            .setSmallIcon(android.R.drawable.stat_notify_sync) // 작은 아이콘(시스템 동기화 아이콘)
            .setOngoing(true) // 지울 수 없음
            .setOnlyAlertOnce(true) // 갱신 때 다시 울리지 않음
            .setSilent(true) // 소리 없음
            .setCategory(NotificationCompat.CATEGORY_PROGRESS) // 진행 범주
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE) // 10초 지연 없이 표시
            .setContentIntent(openAppIntent()) // 누르면 앱 열기
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "취소", cancelIntent()) // 취소 버튼
        if (p == null) { // 첫 진행 이벤트 전
            b.setContentTitle("배치 실행 준비 중") // 제목
                .setContentText(running?.runId ?: "") // 실행 ID
                .setProgress(0, 0, true) // 무한 진행 막대
        } else { // 진행 중
            b.setContentTitle("배치 실행 중 ${p.done}/${p.total}") // 제목(끝난 수/전체)
                .setContentText(p.current) // 모델 / 프리셋 / 파일
                .setStyle(NotificationCompat.BigTextStyle().bigText("${p.current}\nrun ${running.runId}")) // 펼침 본문
                .setProgress(p.total, p.done, false) // 진행 막대
        }
        return b.build() // 알림
    }

    /**
     * 목적: 결과 알림 만들기
     * 기능: 정상 종료는 상태별 건수, 실패는 원인, 취소는 이어하기 안내를 담은 지울 수 있는 알림(누르면 앱 열기, 누르면 사라짐).
     * 입력: result(최종 상태)
     * 출력: 없음
     * 리턴: Notification
     */
    private fun buildResultNotification(result: RunUiState): Notification {
        val (title, text) = when (result) { // 상태별 문구
            is RunUiState.Finished -> "배치 실행 완료" to
                "OK ${result.counts.ok} · NO_MATCH ${result.counts.noMatch} · ERROR ${result.counts.error} · 미지원 ${result.counts.unsupported} (run ${result.runId})" // 집계
            is RunUiState.Failed -> "배치 실행 실패" to "${result.message} (run ${result.runId})" // 원인
            is RunUiState.Cancelled -> "배치 실행 취소됨" to "run ${result.runId} — 같은 runId 로 이어하기 가능" // 안내
            else -> "배치 실행 종료" to result.toString() // 그 밖(발생하지 않음)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID) // 빌더
            .setSmallIcon(android.R.drawable.stat_notify_sync) // 작은 아이콘
            .setContentTitle(title) // 제목
            .setContentText(text) // 본문
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)) // 펼침 본문
            .setSilent(true) // 소리 없음
            .setAutoCancel(true) // 누르면 사라짐
            .setContentIntent(openAppIntent()) // 앱 열기
            .build() // 알림
    }

    /**
     * 목적: 앱 열기 PendingIntent
     * 기능: MainActivity 를 (이미 위에 있으면 재사용) 연다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: PendingIntent(불변)
     */
    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, // 요청 코드 0
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), // 앱 화면
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // 불변
    )

    /**
     * 목적: 알림 "취소" PendingIntent
     * 기능: 이 서비스에 ACTION_CANCEL 을 보낸다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: PendingIntent(불변)
     */
    private fun cancelIntent(): PendingIntent = PendingIntent.getService(
        this, 1, // 요청 코드 1
        Intent(this, RunService::class.java).setAction(ACTION_CANCEL), // 취소 명령
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, // 불변
    )

    companion object {
        private const val TAG = "RunService" // 로그 태그
        const val ACTION_START = "com.cowork.stt_ondevice.action.RUN_START" // 시작 명령
        const val ACTION_CANCEL = "com.cowork.stt_ondevice.action.RUN_CANCEL" // 취소 명령
        private const val EXTRA_TOKEN = "token" // 대기 설정 토큰
        private const val EXTRA_RUN_ID = "run_id" // 실행 ID
        private const val CHANNEL_ID = "batch_run" // 알림 채널 ID
        private const val NOTIFICATION_ID = 2701 // 진행(포그라운드) 알림 ID
        private const val RESULT_NOTIFICATION_ID = 2702 // 결과 알림 ID
        private const val WAKE_LOCK_TAG = "STT_OnDevice:BatchRun" // 깨우기 잠금 이름("앱:용도" 형식)
        private const val WAKE_LOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000 // 안전 제한 시간 6시간(항목마다 연장)

        private val coordinator = RunCoordinator() // 실행 상태기계(대기 슬롯·상태·Job 수명) // change(add)-hyungchul-2026-10-11-03:00

        /** 화면이 구독하는 실행 상태(프로세스 안에서만 유지). */
        val events: StateFlow<RunUiState> = coordinator.events // 읽기 전용 // change(add)-hyungchul-2026-10-11-03:00

        /**
         * 목적: 배치 실행 시작 요청
         * 기능: 실행 중(Running)이면 거부한다(로그 + Running.rejectedStart, false). 아니면 상태를 즉시 Running(runId) 로 바꾸고
         *       설정을 대기 슬롯에 둔 뒤 토큰·runId 만 담은 Intent 로 startForegroundService 한다.
         *       서비스 시작이 거부되면(앱이 백그라운드라 FGS 시작 불가 등) 상태를 Failed 로 바꾸고 false.
         * 입력: context(컨텍스트), config(실행 설정 — 객체 그대로 프로세스 메모리에 보관)
         * 출력: events 갱신
         * 리턴: 시작 요청을 보냈으면 true, 거부·실패면 false
         */
        fun start(context: Context, config: RunConfig): Boolean {
            val token = coordinator.requestStart(config) // 수락 시 토큰(대기 슬롯 보관·Running), 거부 시 null(rejectedStart) // change(add)-hyungchul-2026-10-11-03:00
            if (token == null) { // 거부됨
                Log.w(TAG, "이미 실행 중이라 시작 요청 거부: ${config.runId}") // 로그
                return false // 거부
            }
            val intent = Intent(context, RunService::class.java) // 서비스 인텐트
                .setAction(ACTION_START) // 시작 명령
                .putExtra(EXTRA_TOKEN, token) // 토큰
                .putExtra(EXTRA_RUN_ID, config.runId) // 실행 ID
            return try {
                ContextCompat.startForegroundService(context, intent) // 포그라운드 서비스 시작
                true // 요청 보냄
            } catch (e: Exception) {
                Log.e(TAG, "서비스 시작 실패: ${config.runId}", e) // 로그
                coordinator.startFailed(token, config.runId, "서비스 시작 실패: ${RunCoordinator.describe(e)}") // 대기 슬롯 비우기 + Failed // change(add)-hyungchul-2026-10-11-03:00
                false // 실패
            }
        }

        /**
         * 목적: 실행 취소 요청
         * 기능: 실행 중이 아니면 로그만 남긴다. 실행 중이면 서비스에 ACTION_CANCEL 을 보낸다(시작 명령 뒤에 처리되므로 시작 직후 취소도 안전).
         * 입력: context(컨텍스트)
         * 출력: 없음
         * 리턴: 없음
         */
        fun cancel(context: Context) {
            if (events.value !is RunUiState.Running) { // 실행 중 아님
                Log.i(TAG, "실행 중이 아니라 취소 요청 무시") // 로그
                return // 끝
            }
            try {
                context.startService(Intent(context, RunService::class.java).setAction(ACTION_CANCEL)) // 취소 명령 전달
            } catch (e: Exception) {
                Log.e(TAG, "취소 명령 전달 실패", e) // 로그(포그라운드 서비스 실행 중이면 백그라운드에서도 허용됨)
            }
        }
    }
}
