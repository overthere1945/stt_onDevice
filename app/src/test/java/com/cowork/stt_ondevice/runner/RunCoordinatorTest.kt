// 파일명: RunCoordinatorTest.kt
// 목적: RunService 에서 떼어 낸 실행 상태기계(RunCoordinator)의 JVM 검증 (Task 27 리뷰 수정 1)
// 기능: - 시작 수락/거부(거부 사유 문구·기존 실행 유지), 토큰 불일치·1회성 꺼내기, 서비스 시작 실패 → Failed·대기 슬롯 비움
//       - 실행 Job: 정상 종료 → Finished(진행 상태 반영), 본문 시작 전 사용자 취소 → Cancelled + 정리 콜백(onRunFinishing·onRunEnded) 보장,
//         실행 중 사용자 취소 → Cancelled, 사용자 취소가 아닌 취소(범위 취소) → Failed, 흐름 예외·시작 준비(onRunStarting) 예외 → Failed + 정리 보장
//       change(add)-hyungchul-2026-10-11-03:31 진행 화면용 Running.errorCount(ERROR 완료 수)·startedAtMs(시작 시각) 반영
//       Android 의존 없음(가짜 RunHost, 가상 시간 TestScope).
// change(add)-hyungchul-2026-10-11-03:00
package com.cowork.stt_ondevice.runner // 배치 실행 테스트 패키지

import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import kotlinx.coroutines.CoroutineScope // 실행 범위
import kotlinx.coroutines.SupervisorJob // 범위 Job
import kotlinx.coroutines.awaitCancellation // 취소될 때까지 대기
import kotlinx.coroutines.cancel // 범위 취소
import kotlinx.coroutines.flow.Flow // 이벤트 흐름
import kotlinx.coroutines.flow.flow // 흐름 빌더
import kotlinx.coroutines.test.StandardTestDispatcher // 수동 진행 디스패처
import kotlinx.coroutines.test.TestScope // 가상 시간 범위
import kotlinx.coroutines.test.advanceUntilIdle // 남은 작업 모두 실행
import kotlinx.coroutines.test.runCurrent // 지금 실행 가능한 작업 실행
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-03:00
/**
 * 목적: 호출 기록용 가짜 RunHost
 * 기능: flow 를 그대로 실행 흐름으로 돌려주고, 각 콜백 호출을 기록한다. startError 가 있으면 onRunStarting 에서 던진다.
 * 입력: flowFactory(설정 → 이벤트 흐름), startError(onRunStarting 에서 던질 예외 또는 null)
 */
private class FakeHost(
    private val flowFactory: (RunConfig) -> Flow<RunEvent>, // 실행 흐름 생성
    private val startError: Exception? = null, // 시작 준비 예외
) : RunHost {
    val calls = mutableListOf<String>() // 호출 기록
    var ended: RunUiState? = null // onRunEnded 로 받은 최종 상태
    var failure: Throwable? = null // onRunFailed 로 받은 예외

    /** 목적: 실행 흐름(기록). 입력: config. 리턴: flowFactory 결과. */
    override fun runFlow(config: RunConfig): Flow<RunEvent> { calls += "runFlow"; return flowFactory(config) } // 기록 후 생성

    /** 목적: 시작 준비(기록, 설정 시 예외). 입력·리턴: 없음. */
    override fun onRunStarting() { calls += "starting"; startError?.let { throw it } } // 기록·예외

    /** 목적: 진행 알림(기록). 입력: p(진행률). 리턴: 없음. */
    override fun onProgress(p: RunEvent.Progress) { calls += "progress:${p.done}" } // 기록

    /** 목적: 실패 기록. 입력: t(예외). 리턴: 없음. */
    override fun onRunFailed(t: Throwable) { calls += "failed"; failure = t } // 기록

    /** 목적: 정리(기록). 입력·리턴: 없음. */
    override fun onRunFinishing() { calls += "finishing" } // 기록

    /** 목적: 종료 처리(기록). 입력: result(최종 상태). 리턴: 없음. */
    override fun onRunEnded(result: RunUiState) { calls += "ended"; ended = result } // 기록
}

// change(add)-hyungchul-2026-10-11-03:00
/**
 * 목적: RunCoordinator 회귀 방지
 * 기능: 파일 머리말의 항목을 확인한다.
 */
class RunCoordinatorTest {

    private val item = InputItem("en", "", "01", File("01.wav")) // 입력 항목(파일은 읽지 않음)

    /**
     * 목적: 테스트용 실행 설정
     * 기능: 모델·프리셋 없이 runId 만 다른 설정을 만든다(RunCoordinator 는 내용을 보지 않음).
     * 입력: runId(실행 ID)
     * 출력: 없음
     * 리턴: RunConfig
     */
    private fun config(runId: String) = RunConfig(runId, listOf(item), emptyList(), emptyList(), ChunkMode.MERGE, emptyMap()) // 설정

    /**
     * 목적: 시작 수락과 실행 중 거부
     * 기능: 첫 요청은 토큰을 받고 Running(runId), 두 번째 요청은 null 이고 상태는 기존 runId 유지 + rejectedStart 문구.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun startAcceptedThenRejectedWhileRunning() {
        val c = RunCoordinator { 1234L } // 상태기계(고정 시계) // change(add)-hyungchul-2026-10-11-03:31 시작 시각 고정
        assertEquals(RunUiState.Idle, c.events.value) // 처음은 Idle
        assertNotNull(c.requestStart(config("20261011_030000"))) // 수락
        assertEquals(RunUiState.Running("20261011_030000", startedAtMs = 1234L), c.events.value) // 즉시 Running(시작 시각 기록) // change(add)-hyungchul-2026-10-11-03:31
        assertNull(c.requestStart(config("20261011_030001"))) // 거부
        val s = c.events.value as RunUiState.Running // 상태
        assertEquals("20261011_030000", s.runId) // 기존 실행 유지
        assertEquals(RunCoordinator.rejectMessage("20261011_030000", "20261011_030001"), s.rejectedStart) // 거부 사유
        c.markRejected("20261011_030002") // 서비스 쪽 거부 표시도 같은 도우미 문구
        assertEquals(RunCoordinator.rejectMessage("20261011_030000", "20261011_030002"), (c.events.value as RunUiState.Running).rejectedStart) // 같은 형식
    }

    /**
     * 목적: 토큰 불일치·1회성 꺼내기
     * 기능: 다른 토큰·null 이면 null(슬롯 유지), 맞는 토큰이면 설정을 돌려주고 두 번째는 null.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun takePendingRequiresMatchingTokenOnce() {
        val c = RunCoordinator() // 상태기계
        val cfg = config("20261011_030000") // 설정
        val token = c.requestStart(cfg)!! // 토큰
        assertNull(c.takePending("다른-토큰")) // 불일치
        assertNull(c.takePending(null)) // 토큰 없음
        assertEquals(cfg, c.takePending(token)) // 일치 → 설정
        assertNull(c.takePending(token)) // 1회성
    }

    /**
     * 목적: 서비스 시작 실패 처리
     * 기능: startFailed 는 Failed(runId, 메시지)로 바꾸고 대기 슬롯을 비워 다음 시작을 허용한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun startFailedPublishesFailedAndClearsPending() {
        val c = RunCoordinator() // 상태기계
        val token = c.requestStart(config("20261011_030000"))!! // 수락
        c.startFailed(token, "20261011_030000", "서비스 시작 실패: X") // 실패 처리
        assertEquals(RunUiState.Failed("20261011_030000", "서비스 시작 실패: X"), c.events.value) // Failed
        assertNull(c.takePending(token)) // 슬롯 비워짐
        assertNotNull(c.requestStart(config("20261011_030001"))) // 다시 시작 가능
    }

    /**
     * 목적: 정상 종료
     * 기능: Progress·ItemDone·Finished 를 내는 흐름 → 진행 상태 반영, onProgress 호출, 최종 Finished, 정리 순서 starting→runFlow→…→finishing→ended.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun normalRunEndsFinished() {
        val scope = TestScope() // 가상 시간 범위
        val c = RunCoordinator() // 상태기계
        val cfg = config("20261011_030000") // 설정
        c.requestStart(cfg) // 수락
        val fin = RunEvent.Finished(1, 0, 0, 0) // 종료 집계
        var seenProgress: RunUiState? = null // 진행 중 상태 관찰값
        val host = FakeHost({ flow {
            emit(RunEvent.Progress(0, 1, "m / p / f")) // 진행
            seenProgress = c.events.value // 진행 반영 확인
            emit(RunEvent.ItemDone("combo", item, "OK")) // 완료
            emit(fin) // 종료
        } })
        val handle = c.launch(scope, cfg, host, StandardTestDispatcher(scope.testScheduler)) // 실행
        scope.advanceUntilIdle() // 끝까지
        assertEquals(RunEvent.Progress(0, 1, "m / p / f"), (seenProgress as RunUiState.Running).progress) // 진행률 반영
        assertEquals(RunUiState.Finished("20261011_030000", fin), c.events.value) // Finished 게시
        assertEquals(RunUiState.Finished("20261011_030000", fin), host.ended) // 종료 콜백
        assertEquals(listOf("starting", "runFlow", "progress:0", "finishing", "ended"), host.calls) // 순서
        assertTrue(handle.job.isCompleted) // Job 끝남
    }

    /**
     * 목적: 본문 시작 전 사용자 취소(리뷰 지적 재현)
     * 기능: 디스패처가 본문을 시작하기 전에 cancelByUser 해도 본문이 시작되어(ATOMIC) 흐름은 만들지 않고
     *       Cancelled 게시 + onRunFinishing·onRunEnded 가 실행된다(서비스가 job=null·포그라운드 해제·stopSelf 할 수 있음).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun userCancelBeforeBodyStartsStillEndsCancelled() {
        val scope = TestScope() // 가상 시간 범위(launch 는 advance 전까지 실행되지 않음)
        val c = RunCoordinator() // 상태기계
        val cfg = config("20261011_030000") // 설정
        c.requestStart(cfg) // 수락
        val host = FakeHost({ flow { emit(RunEvent.Finished(0, 0, 0, 0)) } }) // 실행되면 안 되는 흐름
        val handle = c.launch(scope, cfg, host, StandardTestDispatcher(scope.testScheduler)) // 실행 예약
        handle.cancelByUser() // 본문 시작 전 취소
        scope.advanceUntilIdle() // 진행
        assertEquals(RunUiState.Cancelled("20261011_030000"), c.events.value) // Running 고착 없음
        assertEquals(RunUiState.Cancelled("20261011_030000"), host.ended) // 종료 콜백 실행
        assertEquals(listOf("finishing", "ended"), host.calls) // 흐름·시작 준비 없이 정리만
        assertNotNull(c.requestStart(config("20261011_030001"))) // 다음 시작 가능
    }

    /**
     * 목적: 실행 중 사용자 취소
     * 기능: 흐름이 멈춰 있는 동안 cancelByUser → Cancelled, 정리 콜백 실행.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun userCancelWhileRunningEndsCancelled() {
        val scope = TestScope() // 가상 시간 범위
        val c = RunCoordinator() // 상태기계
        val cfg = config("20261011_030000") // 설정
        c.requestStart(cfg) // 수락
        val host = FakeHost({ flow { emit(RunEvent.Progress(0, 1, "x")); awaitCancellation() } }) // 진행 후 대기
        val handle = c.launch(scope, cfg, host, StandardTestDispatcher(scope.testScheduler)) // 실행
        scope.runCurrent() // 진행 이벤트까지
        assertTrue(c.events.value is RunUiState.Running) // 실행 중
        handle.cancelByUser() // 사용자 취소
        scope.advanceUntilIdle() // 정리까지
        assertEquals(RunUiState.Cancelled("20261011_030000"), c.events.value) // Cancelled
        assertEquals(listOf("starting", "runFlow", "progress:0", "finishing", "ended"), host.calls) // 정리 실행
    }

    /**
     * 목적: 사용자 취소가 아닌 취소 구분
     * 기능: 실행 범위가 취소되면(서비스 파괴 등) Cancelled 가 아니라 Failed 로 끝나고 정리 콜백은 실행된다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun scopeCancelIsFailedNotUserCancel() {
        val scheduler = TestScope().testScheduler // 가상 시계
        val dispatcher = StandardTestDispatcher(scheduler) // 수동 디스패처
        val runScope = CoroutineScope(SupervisorJob() + dispatcher) // 서비스 범위 대용
        val c = RunCoordinator() // 상태기계
        val cfg = config("20261011_030000") // 설정
        c.requestStart(cfg) // 수락
        val host = FakeHost({ flow { emit(RunEvent.Progress(0, 1, "x")); awaitCancellation() } }) // 진행 후 대기
        c.launch(runScope, cfg, host, dispatcher) // 실행
        scheduler.runCurrent() // 진행 이벤트까지
        runScope.cancel() // 범위 취소(사용자 취소 아님)
        scheduler.advanceUntilIdle() // 정리까지
        val s = c.events.value // 최종 상태
        assertTrue("Failed 가 아님: $s", s is RunUiState.Failed) // 실패로 분류
        assertEquals("ended", host.calls.last()) // 정리 실행
    }

    /**
     * 목적: 흐름 예외 처리
     * 기능: 흐름이 IllegalStateException 을 던지면 onRunFailed 로 전달, Failed("IllegalStateException: 메시지").
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun flowExceptionEndsFailed() {
        val scope = TestScope() // 가상 시간 범위
        val c = RunCoordinator() // 상태기계
        val cfg = config("20261011_030000") // 설정
        c.requestStart(cfg) // 수락
        val host = FakeHost({ flow<RunEvent> { throw IllegalStateException("manifest 있음") } }) // 실패 흐름
        c.launch(scope, cfg, host, StandardTestDispatcher(scope.testScheduler)) // 실행
        scope.advanceUntilIdle() // 끝까지
        assertEquals(RunUiState.Failed("20261011_030000", "IllegalStateException: manifest 있음"), c.events.value) // Failed
        assertTrue(host.failure is IllegalStateException) // 실패 콜백
        assertEquals(listOf("starting", "runFlow", "failed", "finishing", "ended"), host.calls) // 순서
    }

    /**
     * 목적: 시작 준비 예외(깨우기 잠금 실패 등) 처리
     * 기능: onRunStarting 이 던져도 크래시 없이 Failed 로 끝나고 onRunFinishing·onRunEnded 가 실행된다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun startingExceptionEndsFailedWithCleanup() {
        val scope = TestScope() // 가상 시간 범위
        val c = RunCoordinator() // 상태기계
        val cfg = config("20261011_030000") // 설정
        c.requestStart(cfg) // 수락
        val host = FakeHost({ flow { emit(RunEvent.Finished(0, 0, 0, 0)) } }, startError = SecurityException("WAKE_LOCK 없음")) // 시작 준비 실패
        c.launch(scope, cfg, host, StandardTestDispatcher(scope.testScheduler)) // 실행
        scope.advanceUntilIdle() // 끝까지
        assertEquals(RunUiState.Failed("20261011_030000", "SecurityException: WAKE_LOCK 없음"), c.events.value) // Failed
        assertFalse("runFlow" in host.calls) // 흐름을 만들지 않음
        assertEquals(listOf("finishing", "ended"), host.calls.takeLast(2)) // 정리 실행
    }

    // change(add)-hyungchul-2026-10-11-03:31
    /**
     * 목적: 진행 화면용 오류 수 집계
     * 기능: ItemDone 중 status=ERROR 만 Running.errorCount 를 1씩 올리고(OK·NO_MATCH 는 그대로), 시작 시각(startedAtMs)은 유지된다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun itemDoneCountsErrors() {
        val scope = TestScope() // 가상 시간 범위
        val c = RunCoordinator { 5000L } // 고정 시계
        val cfg = config("20261011_030000") // 설정
        c.requestStart(cfg) // 수락
        val seen = mutableListOf<Int>() // 항목 완료 직후 오류 수
        val host = FakeHost({ flow {
            emit(RunEvent.ItemDone("combo", item, "ERROR")) // 오류 1
            seen += (c.events.value as RunUiState.Running).errorCount // 관찰
            emit(RunEvent.ItemDone("combo", item, "OK")) // 정상
            seen += (c.events.value as RunUiState.Running).errorCount // 관찰
            emit(RunEvent.ItemDone("combo", item, "ERROR")) // 오류 2
            seen += (c.events.value as RunUiState.Running).errorCount // 관찰
            assertEquals(5000L, (c.events.value as RunUiState.Running).startedAtMs) // 시작 시각 유지
            emit(RunEvent.Finished(1, 0, 2, 0)) // 종료
        } })
        c.launch(scope, cfg, host, StandardTestDispatcher(scope.testScheduler)) // 실행
        scope.advanceUntilIdle() // 끝까지
        assertEquals(listOf(1, 1, 2), seen) // ERROR 만 셈
    }
}
