// 파일명: RunCoordinator.kt
// 목적: RunService 의 실행 상태기계(시작 수락/거부·대기 설정 슬롯·실행 Job 수명·최종 상태 게시)를 Android 의존 없이 분리해 JVM 에서 검증한다
//       (Task 27 리뷰 수정 1)
// 기능: - RunCoordinator: events(StateFlow<RunUiState>), requestStart(수락 시 토큰, 거부 시 null + rejectedStart), startFailed, takePending(토큰 일치 1회),
//         markRejected, rejectMessage(거부 문구 도우미), launch(실행 Job 시작 → RunHandle).
//       - launch 는 CoroutineStart.ATOMIC 으로 시작해, 디스패처가 본문을 시작하기 전에 취소되어도 본문이 반드시 시작되고 finally(정리·최종 상태 게시)가 실행된다
//         (기본 시작 모드면 본문 시작 전 취소 시 본문·finally 가 모두 건너뛰어져 상태가 Running 에 고착되고 서비스가 멈추지 않는다).
//       - 최종 상태: 정상 종료 Finished, 사용자 취소(RunHandle.cancelByUser) Cancelled, 그 밖의 취소(서비스 범위 취소 등)와 예외는 Failed.
//       - RunHost: 서비스가 구현하는 콜백(실행 흐름 생성·깨우기 잠금·알림·로그·메인 스레드 종료 처리).
//       - change(add)-hyungchul-2026-10-11-03:31 진행 화면용으로 Running.startedAtMs(시작 수락 시각)와 errorCount(ERROR 완료 항목 수)를 채운다.
// change(add)-hyungchul-2026-10-11-03:00
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import kotlinx.coroutines.CoroutineScope // 실행 범위
import kotlinx.coroutines.CoroutineStart // 시작 모드
import kotlinx.coroutines.Job // 실행 Job
import kotlinx.coroutines.NonCancellable // 취소 후에도 정리 실행
import kotlinx.coroutines.ensureActive // 취소 확인
import kotlinx.coroutines.flow.Flow // 이벤트 흐름
import kotlinx.coroutines.flow.MutableStateFlow // 상태 보관
import kotlinx.coroutines.flow.StateFlow // 읽기 전용 상태
import kotlinx.coroutines.flow.asStateFlow // 읽기 전용 변환
import kotlinx.coroutines.flow.update // 원자적 상태 갱신
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withContext // 문맥 전환
import java.util.UUID // 시작 토큰
import java.util.concurrent.atomic.AtomicBoolean // 사용자 취소 표시
import kotlin.coroutines.CoroutineContext // 종료 처리 문맥
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-03:00
/**
 * 목적: 실행 Job 이 서비스에 요구하는 콜백
 * 기능: 호출 스레드 — runFlow·onRunStarting·onProgress·onRunFailed·onRunFinishing 은 실행 스레드(Default), onRunEnded 는 launch 의 endContext(서비스는 Main).
 */
internal interface RunHost {
    /** 목적: 실행 흐름 만들기(서비스: App.newBatchRunner().run(config)). 입력: config. 출력: 조립 실패 시 예외. 리턴: RunEvent 흐름. */
    fun runFlow(config: RunConfig): Flow<RunEvent>

    /** 목적: 실행 시작 준비(서비스: 깨우기 잠금 획득). 입력·리턴: 없음. 출력: 실패 시 예외(→ Failed). */
    fun onRunStarting()

    /** 목적: 진행 이벤트 처리(서비스: 잠금 연장·진행 알림). 입력: p(진행률). 리턴: 없음. */
    fun onProgress(p: RunEvent.Progress)

    /** 목적: 실패 기록(서비스: 로그). 입력: t(실행을 멈춘 예외). 리턴: 없음. */
    fun onRunFailed(t: Throwable)

    /** 목적: 실행 스레드 쪽 정리(서비스: 깨우기 잠금 해제). 입력·리턴: 없음. 항상 호출된다. */
    fun onRunFinishing()

    /** 목적: 종료 처리(서비스: job 비우기·포그라운드 해제·결과 알림·stopSelfResult). 입력: result(최종 상태). 리턴: 없음. 항상 호출된다. */
    fun onRunEnded(result: RunUiState)
}

// change(add)-hyungchul-2026-10-11-03:00
/**
 * 목적: 시작 대기 중인 실행 설정
 * 기능: requestStart 가 넣고 takePending 이 같은 토큰으로 꺼낸다(프로세스 메모리에만 있음).
 * 입력: token(Intent 로 전달한 일회용 토큰), config(실행 설정)
 */
private class PendingStart(val token: String, val config: RunConfig)

// change(add)-hyungchul-2026-10-11-03:00
/**
 * 목적: 실행 중인 Job 핸들
 * 기능: cancelByUser 는 사용자 취소 표시를 먼저 세운 뒤 Job 을 취소한다(최종 상태를 Cancelled 로 분류하기 위해).
 * 입력: runId(실행 ID), job(실행 Job), userCancel(사용자 취소 표시 — launch 와 공유)
 */
internal class RunHandle(val runId: String, val job: Job, private val userCancel: AtomicBoolean) {
    /**
     * 목적: 사용자 취소
     * 기능: 표시를 세우고 Job 을 취소한다(본문 시작 전이어도 ATOMIC 시작이라 정리가 실행됨).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun cancelByUser() {
        userCancel.set(true) // 사용자 취소 표시
        job.cancel(CancellationException("사용자 취소")) // Job 취소
    }
}

// change(add)-hyungchul-2026-10-11-03:00
/**
 * 목적: 실행 상태기계
 * 기능: 파일 머리말 참조. 프로세스 안에서 하나만 쓴다(RunService companion 이 보유).
 * 입력: change(add)-hyungchul-2026-10-11-03:31 clock(시작 수락 시 Running.startedAtMs 에 넣을 벽시계 ms, 기본 System.currentTimeMillis)
 */
internal class RunCoordinator(private val clock: () -> Long = System::currentTimeMillis) { // change(add)-hyungchul-2026-10-11-03:31 시작 시각용 벽시계(테스트에서 교체)
    private val lock = Any() // 대기 슬롯 보호
    private var pending: PendingStart? = null // 시작 대기 중인 설정(lock 으로 보호)
    private val state = MutableStateFlow<RunUiState>(RunUiState.Idle) // 화면 상태(쓰기용)

    /** 화면이 구독하는 실행 상태. */
    val events: StateFlow<RunUiState> = state.asStateFlow() // 읽기 전용

    /**
     * 목적: 시작 요청 수락/거부
     * 기능: 원자적으로 확인해 이미 Running 이면 rejectedStart 를 채우고 null. 아니면 즉시 Running(runId) 로 바꾸고 설정을 대기 슬롯에 넣는다.
     * 입력: config(실행 설정)
     * 출력: events 갱신
     * 리턴: 수락 시 토큰, 거부 시 null
     */
    fun requestStart(config: RunConfig): String? {
        var accepted = false // 수락 여부(update 재시도 시 마지막 값이 유효)
        state.update { cur -> // 원자적 확인·갱신
            if (cur is RunUiState.Running) { // 이미 실행 중
                accepted = false // 거부
                cur.copy(rejectedStart = rejectMessage(cur.runId, config.runId)) // 거부 사유
            } else { // 실행 없음
                accepted = true // 수락
                RunUiState.Running(config.runId, startedAtMs = clock()) // 즉시 실행 중(중복 시작 차단) // change(add)-hyungchul-2026-10-11-03:31 시작 시각 기록
            }
        }
        if (!accepted) return null // 거부
        val token = UUID.randomUUID().toString() // 일회용 토큰
        synchronized(lock) { pending = PendingStart(token, config) } // 대기 슬롯
        return token // 토큰
    }

    /**
     * 목적: 서비스 시작 실패 처리
     * 기능: 그 토큰의 대기 설정을 지우고 Failed(runId, message) 를 게시한다.
     * 입력: token(requestStart 토큰), runId(실행 ID), message(원인)
     * 출력: events 갱신
     * 리턴: 없음
     */
    fun startFailed(token: String, runId: String, message: String) {
        synchronized(lock) { if (pending?.token == token) pending = null } // 대기 슬롯 비우기
        publishTerminal(RunUiState.Failed(runId, message)) // 실패 게시
    }

    /**
     * 목적: 대기 설정 꺼내기
     * 기능: 토큰이 같을 때만 꺼내고 슬롯을 비운다(1회).
     * 입력: token(Intent 의 토큰, 없으면 null)
     * 출력: 없음
     * 리턴: RunConfig 또는 null
     */
    fun takePending(token: String?): RunConfig? = synchronized(lock) {
        val p = pending // 현재 슬롯
        if (p != null && token != null && p.token == token) { // 토큰 일치
            pending = null // 비우기
            p.config // 설정
        } else null // 불일치·없음
    }

    /**
     * 목적: 거부 사유 표시(서비스 쪽 방어 경로)
     * 기능: 실행 중 상태에 rejectedStart 를 채운다.
     * 입력: runId(거부한 실행 ID)
     * 출력: events 갱신
     * 리턴: 없음
     */
    fun markRejected(runId: String) {
        state.update { cur -> if (cur is RunUiState.Running) cur.copy(rejectedStart = rejectMessage(cur.runId, runId)) else cur } // 실행 중일 때만
    }

    /**
     * 목적: 최종 상태 게시
     * 기능: Finished/Failed/Cancelled 를 그대로 설정한다.
     * 입력: result(최종 상태)
     * 출력: events 갱신
     * 리턴: 없음
     */
    fun publishTerminal(result: RunUiState) {
        state.value = result // 설정
    }

    /**
     * 목적: 실행 Job 시작
     * 기능: scope 에서 시작 모드 START_MODE 로 본문을 띄운다. 본문: 취소 확인 → host.onRunStarting → host.runFlow(config) 수집
     *       (Progress: Running.progress 갱신 + host.onProgress, ItemDone: lastItem 갱신, Finished: 최종 상태 보관).
     *       취소는 사용자 취소 표시가 있으면 Cancelled, 없으면 Failed. 그 밖의 예외는 host.onRunFailed 후 Failed. Finished 없이 끝나면 Failed.
     *       finally: host.onRunFinishing(예외는 무시) → withContext(NonCancellable + endContext) { host.onRunEnded(최종) 후 최종 상태 게시 }.
     * 입력: scope(실행 범위), config(실행 설정), host(콜백), endContext(종료 처리 문맥 — 서비스는 Dispatchers.Main)
     * 출력: events 갱신, 콜백 호출
     * 리턴: RunHandle
     */
    fun launch(scope: CoroutineScope, config: RunConfig, host: RunHost, endContext: CoroutineContext): RunHandle {
        val userCancel = AtomicBoolean(false) // 사용자 취소 표시
        val runId = config.runId // 실행 ID
        val job = scope.launch(start = START_MODE) { // 실행 본문
            var terminal: RunUiState = RunUiState.Failed(runId, "종료 이벤트 없이 실행이 끝남") // 최종 상태(기본: 비정상)
            try {
                coroutineContext.ensureActive() // 시작 전에 취소됐으면 아무것도 하지 않고 정리로
                host.onRunStarting() // 시작 준비(잠금)
                host.runFlow(config).collect { ev -> // 이벤트 수집
                    when (ev) { // 이벤트별
                        is RunEvent.Progress -> { // 항목 시작 직전
                            updateRunning(runId) { it.copy(progress = ev) } // 진행률 반영
                            host.onProgress(ev) // 잠금 연장·알림
                        }
                        is RunEvent.ItemDone -> updateRunning(runId) { it.copy(lastItem = ev, errorCount = it.errorCount + if (ev.status == "ERROR") 1 else 0) } // 최근 완료 항목 // change(add)-hyungchul-2026-10-11-03:31 ERROR 수 누적
                        is RunEvent.Finished -> terminal = RunUiState.Finished(runId, ev) // 정상 종료 집계
                    }
                }
            } catch (e: CancellationException) {
                terminal = if (userCancel.get()) RunUiState.Cancelled(runId) // 사용자 취소
                else RunUiState.Failed(runId, "실행이 사용자 취소가 아닌 이유로 중단됨(서비스 종료 등): ${describe(e)}") // 그 밖의 취소
                throw e // 취소 전파
            } catch (t: Throwable) {
                host.onRunFailed(t) // 로그
                terminal = RunUiState.Failed(runId, describe(t)) // 비정상 종료
            } finally {
                try { host.onRunFinishing() } catch (_: Exception) { } // 잠금 해제(실패해도 종료 처리는 계속)
                val result = terminal // 최종 상태 고정
                withContext(NonCancellable + endContext) { // 취소 중이어도 종료 처리 문맥에서 실행
                    try {
                        host.onRunEnded(result) // job 비우기·포그라운드 해제·알림·stopSelf
                    } finally {
                        publishTerminal(result) // 화면에 최종 상태(콜백이 실패해도 Running 고착 방지)
                    }
                }
            }
        }
        return RunHandle(runId, job, userCancel) // 핸들
    }

    /**
     * 목적: 실행 중 상태 갱신
     * 기능: 현재 상태가 같은 runId 의 Running 일 때만 change 를 적용한다.
     * 입력: runId(실행 ID), change(Running → Running)
     * 출력: events 갱신
     * 리턴: 없음
     */
    private fun updateRunning(runId: String, change: (RunUiState.Running) -> RunUiState.Running) {
        state.update { cur -> if (cur is RunUiState.Running && cur.runId == runId) change(cur) else cur } // 같은 실행만
    }

    companion object {
        private val START_MODE = CoroutineStart.ATOMIC // 실행 Job 시작 모드: 시작 전 취소돼도 본문이 시작되어 finally(정리·게시)가 반드시 실행됨

        /**
         * 목적: 거부 사유 문구(한 곳에서 만듦)
         * 기능: "이미 실행 중(<현재>)이라 <요청> 시작 요청을 거부함".
         * 입력: currentRunId(실행 중인 ID), requestedRunId(거부한 ID)
         * 출력: 없음
         * 리턴: 문구
         */
        fun rejectMessage(currentRunId: String, requestedRunId: String): String =
            "이미 실행 중($currentRunId)이라 $requestedRunId 시작 요청을 거부함" // 문구

        /**
         * 목적: 예외 설명 문자열
         * 기능: "클래스이름: 메시지"(메시지가 없으면 클래스 이름).
         * 입력: t(예외)
         * 출력: 없음
         * 리턴: 문자열
         */
        fun describe(t: Throwable): String = t.message?.let { "${t.javaClass.simpleName}: $it" } ?: t.javaClass.simpleName // 설명
    }
}
