// 파일명: RunServiceInstrumentedTest.kt (기기 계측 테스트)
// 목적: 실제 기기에서 RunService(포그라운드 서비스)가 App 의 실제 객체 그래프(ModelStore·ManagedEngine·EngineFactory·VadEngineFactory·BatchRunner)로
//       배치를 끝까지 실행하고, 알림의 "취소" 버튼으로 멈추는지 검증
// 기능: - serviceRunsTinyWhisperToFinished: 생성한 3 s 톤 WAV 1개 × whispercpp-tiny × NONE VAD 를 서비스로 실행 → Finished(ERROR 0, 처리 1건),
//         output_stt/<runId>/en/whispercpp_tiny_NONE/_svc_test/01.txt 존재, 모델 사용 횟수 0 으로 복귀.
//       - notificationCancelStopsRun: 60 s 톤 WAV 2개로 실행 → 첫 진행 이벤트 후 (1) 두 번째 start 가 거부되고 Running.rejectedStart 가 채워짐,
//         (2) 진행 알림에 "취소" 버튼이 있고 그 PendingIntent 를 보내면 Cancelled 로 끝남, (3) 끝난 뒤 진행(ongoing) 알림이 사라지고 사용 횟수 0.
//         POST_NOTIFICATIONS 는 UiAutomation 으로 부여한다(실제 화면에서는 UI 가 요청).
//       테스트가 만든 입력(input_sound/en/_svc_test)과 실행 폴더(output_stt/<runId>)는 끝나면 지운다(결과 폴더에 유령 실행이 남지 않게).
//       모델 준비: models/whispercpp-tiny 가 READY 가 아니면 files/import/whispercpp-tiny/ggml-tiny.bin 을 ModelInstaller.importFrom 으로 설치
//       (SHA-256 검증 포함). 둘 다 없으면 건너뜀(assume). 넣는 법(installDebug·installDebugAndroidTest 뒤):
//         P=/sdcard/Android/data/com.cowork.stt_ondevice/files
//         adb shell mkdir -p $P/import/whispercpp-tiny
//         adb push tools/models_cache/whispercpp-tiny/dl0/ggml-tiny.bin $P/import/whispercpp-tiny/ggml-tiny.bin
//         adb shell chmod -R a+rwX $P/import
//         adb shell am instrument -w -e class com.cowork.stt_ondevice.runner.RunServiceInstrumentedTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner
// change(add)-hyungchul-2026-10-11-02:40
package com.cowork.stt_ondevice.runner // 배치 실행 패키지(계측 테스트)

import android.Manifest // 권한 이름
import android.app.Notification // 알림 플래그
import android.app.NotificationManager // 활성 알림 조회
import android.content.Context // 컨텍스트
import android.util.Log // 결과 로그(logcat)
import androidx.test.ext.junit.runners.AndroidJUnit4 // JUnit4 계측 러너
import androidx.test.platform.app.InstrumentationRegistry // 대상 앱 Context·UiAutomation
import com.cowork.stt_ondevice.App // 전역 객체 그래프
import com.cowork.stt_ondevice.core.audio.WavWriter // 테스트 WAV 쓰기
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.model.InstallState // 설치 상태
import com.cowork.stt_ondevice.model.ModelCatalog // 모델 카탈로그
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import com.cowork.stt_ondevice.vad.VadPresets // VAD 프리셋
import kotlinx.coroutines.delay // 대기
import kotlinx.coroutines.flow.first // 조건 만족 첫 값 대기
import kotlinx.coroutines.runBlocking // 테스트에서 코루틴 실행
import kotlinx.coroutines.withTimeout // 대기 제한 시간
import org.junit.After // 정리
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 전제 미충족 시 건너뜀
import org.junit.Before // 준비
import org.junit.Test // 테스트 표시
import org.junit.runner.RunWith // 러너 지정
import java.io.File // 파일
import java.time.LocalDateTime // 현재 시각
import java.time.format.DateTimeFormatter // 시각 서식
import kotlin.math.PI // 원주율
import kotlin.math.sin // 사인

// change(add)-hyungchul-2026-10-11-02:40
/**
 * 목적: RunService 기기 동작 검증
 * 기능: 파일 머리말 참조. 두 테스트는 같은 프로세스의 RunService.events 를 쓰므로 순서대로(JUnit 기본) 실행된다.
 */
@RunWith(AndroidJUnit4::class)
class RunServiceInstrumentedTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext // 대상 앱 컨텍스트
    private val app: App = App.from(ctx) // 앱 전역 객체(서비스와 같은 인스턴스)
    private val spec: ModelSpec = ModelCatalog.byId("whispercpp-tiny") // 가장 빠른 실제 모델
    private val preset = VadPresets.byId("NONE") // VAD 없음(모델 불필요)
    private val inputDir: File by lazy { File(app.dirs.inputSound, "en/$REL_DIR") } // 테스트 입력 폴더
    private val createdRuns = mutableListOf<String>() // 이 테스트가 만든 runId(정리 대상)

    /**
     * 목적: 모델 준비
     * 기능: whispercpp-tiny 가 READY 가 아니면 files/import/whispercpp-tiny 폴더에서 import 한다. 원본도 없으면 건너뜀.
     * 입력: 없음
     * 출력: 모델 설치
     * 리턴: 없음
     */
    @Before
    fun prepareModel() = runBlocking {
        if (app.modelStore.state(spec) != InstallState.READY) { // 미설치
            val src = File(app.dirs.root, "import/${spec.id}") // adb 로 넣은 원본 폴더
            assumeTrue("모델 원본 없음: $src (파일 머리말의 adb push 참조)", File(src, "ggml-tiny.bin").isFile) // 없으면 건너뜀
            app.modelInstaller.importFrom(spec, src) // 복사 + SHA-256 검증 + READY 표식
        }
        assertEquals(InstallState.READY, app.modelStore.state(spec)) // 준비 완료
    }

    /**
     * 목적: 테스트 산출물 정리
     * 기능: 테스트 입력 폴더와 이 테스트가 만든 실행 폴더를 지운다.
     * 입력: 없음
     * 출력: 파일 삭제
     * 리턴: 없음
     */
    @After
    fun cleanup() {
        File(app.dirs.inputSound, "en/$REL_DIR").deleteRecursively() // 입력 정리
        for (id in createdRuns) app.dirs.runDir(id).deleteRecursively() // 실행 폴더 정리
    }

    /**
     * 목적: 서비스로 정상 종료까지 실행
     * 기능: 3 s 톤 1개를 whispercpp-tiny × NONE 으로 실행해 Finished·txt·사용 횟수를 확인한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun serviceRunsTinyWhisperToFinished() = runBlocking {
        val item = writeTone("01", 3.0) // 3 s 입력
        val config = newConfig(listOf(item)) // 실행 설정
        assertTrue(RunService.start(ctx, config)) // 시작 요청 수락
        val end = withTimeout(180_000) { RunService.events.first { isTerminal(it, config.runId) } } // 종료 대기(최대 3분)
        Log.i(TAG, "종료 상태: $end") // 기록
        assertTrue("Finished 가 아님: $end", end is RunUiState.Finished) // 정상 종료
        val counts = (end as RunUiState.Finished).counts // 집계
        assertEquals(0, counts.error) // 오류 없음
        assertEquals(1, counts.ok + counts.noMatch) // 1건 처리(톤이라 OK/NO_MATCH 어느 쪽이든 허용)
        val combo = OutputLayout.comboName(spec.algo, spec.model, preset.id) // whispercpp_tiny_NONE
        val txt = OutputLayout.transcript(app.dirs.runDir(config.runId), item, combo) // 결과 txt 경로
        assertTrue("txt 없음: $txt", txt.isFile) // 결과 존재
        Log.i(TAG, "txt=${txt.absolutePath} 내용=\"${txt.readText()}\"") // 내용 기록
        assertEquals(0, app.modelStore.useCountOf(spec)) // close 시 release 됨
    }

    /**
     * 목적: 중복 시작 거부와 알림 "취소" 동작 확인
     * 기능: 60 s 톤 2개로 실행 → 진행 이벤트 후 두 번째 start 거부 확인 → 진행 알림의 "취소" PendingIntent 전송 → Cancelled,
     *       진행 알림 제거, 사용 횟수 0.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun notificationCancelStopsRun() = runBlocking {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS) // 알림 권한 부여(화면 요청 대신)
        val items = listOf(writeTone("01", 60.0), writeTone("02", 60.0)) // 60 s 입력 2개
        val config = newConfig(items) // 실행 설정
        assertTrue(RunService.start(ctx, config)) // 시작 요청 수락
        val running = withTimeout(120_000) { // 첫 진행 이벤트 대기(모델 로드 포함)
            RunService.events.first { it is RunUiState.Running && it.runId == config.runId && it.progress != null } as RunUiState.Running
        }
        Log.i(TAG, "진행: ${running.progress}") // 기록
        val other = newConfig(items) // 다른 runId 설정
        assertFalse(RunService.start(ctx, other)) // 실행 중 → 거부
        val rejected = RunService.events.value // 거부 후 상태
        assertTrue("Running 이 아님: $rejected", rejected is RunUiState.Running && rejected.runId == config.runId) // 기존 실행 유지
        assertNotNull((rejected as RunUiState.Running).rejectedStart) // 거부 사유 표시
        assertFalse(app.dirs.runDir(other.runId).exists()) // 거부된 실행은 폴더를 만들지 않음
        val nm = ctx.getSystemService(NotificationManager::class.java) // 알림 관리자
        val sbn = nm.activeNotifications.firstOrNull { n -> n.notification.actions?.any { it.title == "취소" } == true } // "취소" 버튼 달린 진행 알림
        assertNotNull("진행 알림이 없음", sbn) // 존재
        Log.i(TAG, "알림 제목=${sbn!!.notification.extras.getCharSequence(Notification.EXTRA_TITLE)} 본문=${sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)}") // 기록
        sbn.notification.actions.first { it.title == "취소" }.actionIntent.send() // 알림 "취소" 누르기와 같은 동작
        val end = withTimeout(60_000) { RunService.events.first { isTerminal(it, config.runId) } } // 종료 대기
        Log.i(TAG, "종료 상태: $end") // 기록
        assertTrue("Cancelled 가 아님: $end", end is RunUiState.Cancelled) // 취소로 끝남
        assertEquals(0, app.modelStore.useCountOf(spec)) // 엔진 close → release
        var ongoing = nm.activeNotifications.filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 } // 남은 진행 알림
        val deadline = System.currentTimeMillis() + 5_000 // 알림 제거는 시스템 쪽에서 비동기라 최대 5 s 기다림
        while (ongoing.isNotEmpty() && System.currentTimeMillis() < deadline) { // 남아 있는 동안
            delay(100) // 잠시 대기
            ongoing = nm.activeNotifications.filter { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 } // 다시 조회
        }
        assertTrue("진행 알림이 남아 있음: $ongoing", ongoing.isEmpty()) // 포그라운드 해제됨
    }

    /**
     * 목적: 테스트 WAV 만들기
     * 기능: 16 kHz mono PCM16 440 Hz 톤(진폭 0.3)을 input_sound/en/_svc_test/<stem>.wav 로 쓴다.
     * 입력: stem(파일 이름), seconds(길이, 초)
     * 출력: 파일 생성
     * 리턴: InputItem(lang "en", relDir "_svc_test")
     */
    private fun writeTone(stem: String, seconds: Double): InputItem {
        val n = (seconds * SR).toInt() // 샘플 수
        val pcm = ShortArray(n) { i -> (0.3 * Short.MAX_VALUE * sin(2 * PI * 440.0 * i / SR)).toInt().toShort() } // 440 Hz 사인
        val file = File(inputDir, "$stem.wav") // 경로
        file.parentFile!!.mkdirs() // 폴더
        WavWriter.write(file, pcm, SR) // 저장
        return InputItem("en", REL_DIR, stem, file) // 입력 항목
    }

    /**
     * 목적: 새 실행 설정 만들기
     * 기능: 아직 폴더가 없는 runId(현재 시각부터 1초씩 증가)를 골라 whispercpp-tiny × NONE 설정을 만들고 정리 목록에 넣는다.
     * 입력: items(입력 항목)
     * 출력: 없음
     * 리턴: RunConfig(threads 4, MERGE)
     */
    private fun newConfig(items: List<InputItem>): RunConfig {
        var t = LocalDateTime.now() // 시작 시각
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss") // runId 형식
        while (app.dirs.runDir(t.format(fmt)).exists() || t.format(fmt) in createdRuns) t = t.plusSeconds(1) // 겹치지 않게
        val runId = t.format(fmt) // 실행 ID
        createdRuns += runId // 정리 대상
        return RunConfig(runId, items, listOf(spec), listOf(preset), ChunkMode.MERGE, emptyMap(), threads = 4) // 설정
    }

    /**
     * 목적: 종료 상태 판정
     * 기능: 해당 runId 의 Finished/Failed/Cancelled 이면 true.
     * 입력: s(상태), runId(실행 ID)
     * 출력: 없음
     * 리턴: 종료 여부
     */
    private fun isTerminal(s: RunUiState, runId: String): Boolean = when (s) { // 상태별
        is RunUiState.Finished -> s.runId == runId // 정상 종료
        is RunUiState.Failed -> s.runId == runId // 실패
        is RunUiState.Cancelled -> s.runId == runId // 취소
        else -> false // 진행 중·대기
    }

    private companion object {
        const val TAG = "RunServiceTest" // 로그 태그
        const val REL_DIR = "_svc_test" // 테스트 입력 상대 폴더
        const val SR = 16_000 // 샘플레이트
    }
}
