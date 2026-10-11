// 파일명: SystemAsrEngineTest.kt
// 목적: 시스템 엔진 세션 핵심(SystemAsrEngine)의 동작을 가짜 백엔드(FakeBackend)와 가상 시간으로 검증 (2차 작업 3)
// 기능: 결과 이어 붙이기·음성 없음·엔진 오류 시 파이프 닫기와 코드 전달·공급 전 완료(PIPE_NOT_CONSUMED)·
//       응답 없음 1회 재생성 재시도·두 번 응답 없음(EngineStalledException)·공급 중 취소·배속별 감시 시간을 확인한다.
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 엔진 카탈로그
import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.ExperimentalCoroutinesApi // 실험적 테스트 API 표시
import kotlinx.coroutines.delay // 가상 시간 대기
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.test.StandardTestDispatcher // 가상 시간 디스패처
import kotlinx.coroutines.test.TestScope // 코루틴 테스트 범위
import kotlinx.coroutines.test.currentTime // 가상 현재 시각(ms)
import kotlinx.coroutines.test.advanceTimeBy // 가상 시간 진행
import kotlinx.coroutines.test.runTest // 코루틴 테스트 실행기
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Test // JUnit4 테스트 표시
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: SystemAsrEngine 단위 테스트
 * 기능: 엔진의 io 디스패처·공급기·시계를 모두 가상 시간으로 주입해 공급·감시·재시도 시간을 결정적으로 잰다.
 */
@OptIn(ExperimentalCoroutinesApi::class) // currentTime·advanceTimeBy·StandardTestDispatcher 사용
class SystemAsrEngineTest {

    /**
     * 목적: 테스트 환경 묶음
     * 기능: 만든 엔진과 생성된 백엔드 목록(크기 = backendFactory 호출 수)을 담는다.
     * 입력: engine(시험 대상), backends(생성 순서대로의 백엔드)
     */
    private class Harness(val engine: SystemAsrEngine, val backends: List<FakeBackend>) {
        /** 목적: 모든 세션(생성 순서) / 기능: 백엔드별 세션을 이어 붙인다 */
        val sessions: List<FakeSession> get() = backends.flatMap { it.sessions } // 전체 세션
    }

    /**
     * 목적: 카탈로그 spec 조회
     * 기능: id 로 SystemCatalog 항목을 찾는다.
     * 입력: id(예 "mlkit-basic-x4")
     * 출력: 없으면 NoSuchElementException
     * 리턴: ModelSpec
     */
    private fun spec(id: String): ModelSpec = SystemCatalog.all.first { it.id == id } // 카탈로그 항목

    /**
     * 목적: 테스트용 chunk 생성
     * 기능: sec 초 분량(16 kHz)의 작은 진폭 샘플로 chunk 를 만든다.
     * 입력: sec(길이, 초)
     * 출력: 없음
     * 리턴: AudioChunk
     */
    private fun chunk(sec: Double): AudioChunk =
        AudioChunk(0, FloatArray((sec * 16_000).toInt()) { 0.1f }, listOf(SourceSpan(0.0, sec, 0.0)), SplitMethod.NONE) // 단일 구간

    /**
     * 목적: 가상 시간 엔진 생성
     * 기능: 각본 큐를 공유하는 FakeBackend 를 만드는 factory, 테스트 디스패처, 가상 시계 공급기·시계로 엔진을 만든다.
     * 입력: TestScope(수신 객체), specId(카탈로그 id), scripts(세션 순서대로의 각본)
     * 출력: 없음
     * 리턴: Harness
     */
    private fun TestScope.harness(specId: String, vararg scripts: SessionScript): Harness {
        val queue = ArrayDeque(scripts.toList()) // 공유 각본 큐
        val backends = ArrayList<FakeBackend>() // 생성된 백엔드
        val nowMs = { testScheduler.currentTime } // 가상 시계(ms)
        val nowNs = { testScheduler.currentTime * 1_000_000L } // 가상 시계(ns)
        val engine = SystemAsrEngine( // 시험 대상
            spec = spec(specId), // 시스템 spec
            backendFactory = { FakeBackend(queue, nowMs).also { backends += it } }, // 생성 기록
            versionInfo = { "fake-1.0" }, // 버전 문자열
            io = StandardTestDispatcher(testScheduler), // 공급 코루틴도 가상 시간
            feeder = PacedPcmFeeder(delayMs = { delay(it) }, nanoTime = nowNs), // 가상 시간 공급기
            clock = nowNs, // 가상 시계
        )
        return Harness(engine, backends) // 묶음
    }

    /**
     * 목적: 결과 이어 붙이기 검증
     * 기능: 조각 ["안녕 ", "하세요"] → text 는 trim 후 공백 연결, rawText 는 줄바꿈 연결, 세션 번호 1, fedBytes = 조각 바이트.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun completedJoinsSegments() = runTest {
        val h = harness("mlkit-basic-x4", SessionScript(SessionOutcome.Completed(listOf("안녕 ", "하세요")))) // 공급 끝 직후 완료
        val info = h.engine.prepare(1) // 준비
        assertEquals("fake-1.0", info.runtimeVersion) // 버전 문자열
        assertEquals("system(soda)", info.backend) // 종류의 backend
        val r = h.engine.transcribe(chunk(1.0), "ko") // 1 초 전사
        assertEquals("안녕 하세요", r.text) // 정리 텍스트
        assertEquals("안녕 \n하세요", r.rawText) // 원문
        val s = r.session // 세션 기록
        assertNotNull(s) // 기록 있음
        assertEquals(1, s!!.number) // 첫 세션
        assertEquals(32_000L, s.fedBytes) // 1 초 PCM16
        assertFalse(s.retried) // 재시도 없음
        assertEquals(listOf("ko-KR"), h.backends[0].openedLangs) // 언어 태그 변환
        assertEquals(1, h.sessions[0].finishCount) // 공급 끝 알림 1회
    }

    /**
     * 목적: 음성 없음 처리 검증
     * 기능: Completed(빈 목록)는 오류가 아니라 빈 텍스트다.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun noSpeechIsEmptyText() = runTest {
        val h = harness("mlkit-basic-x4", SessionScript(SessionOutcome.Completed(emptyList()))) // 음성 없음
        h.engine.prepare(1) // 준비
        val r = h.engine.transcribe(chunk(0.5), "ko") // 전사
        assertEquals("", r.text) // 빈 텍스트
        assertEquals("", r.rawText) // 빈 원문
    }

    /**
     * 목적: 공급 중 엔진 오류 처리 검증
     * 기능: 10 초 조각 1× 공급 중 1 초 시점 Failed(LANGUAGE_UNAVAILABLE) → 코드 그대로 EngineException,
     *       공급기는 즉시 끝나고(가상 경과 < 2 초) audio 가 닫히며, 이후 더 쓰지 않는다.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun engineErrorWhileFeedingClosesPipeAndMapsCode() = runTest {
        val h = harness("mlkit-basic-x1", SessionScript(SessionOutcome.Failed("LANGUAGE_UNAVAILABLE", "en-US 미설치"), atMs = 1_000)) // 1 초 시점 오류
        h.engine.prepare(1) // 준비
        val t0 = currentTime // 시작 시각
        try {
            h.engine.transcribe(chunk(10.0), "en") // 10 초 전사
            fail("EngineException 이 나야 함") // 여기 오면 실패
        } catch (e: EngineException) {
            assertEquals("TRANSCRIBE", e.stage) // 단계
            assertEquals("LANGUAGE_UNAVAILABLE", e.code) // 엔진 코드 그대로
        }
        assertTrue("경과 ${currentTime - t0} ms", currentTime - t0 < 2_000) // 공급 끝까지 기다리지 않음
        val session = h.sessions[0] // 유일 세션
        assertTrue(session.recorder.closed) // audio 닫힘
        val written = session.recorder.bytes // 지금까지 쓴 바이트
        assertTrue(written < 320_000L) // 다 쓰지 않음
        advanceTimeBy(15_000) // 시간을 더 보내도
        assertEquals(written, session.recorder.bytes) // 공급기는 이미 끝남
        assertEquals(listOf("en-US"), h.backends[0].openedLangs) // 언어 태그 변환
    }

    /**
     * 목적: 공급 끝 전 완료 검증
     * 기능: 4 초 조각 1× 공급 절반(2 초)에 Completed 가 오면 파이프를 읽지 않은 것으로 보고 PIPE_NOT_CONSUMED.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun completedBeforeFeedEndIsPipeNotConsumed() = runTest {
        val h = harness("mlkit-basic-x1", SessionScript(SessionOutcome.Completed(listOf("x")), atMs = 2_000)) // 절반에서 완료
        h.engine.prepare(1) // 준비
        try {
            h.engine.transcribe(chunk(4.0), "ko") // 4 초 전사
            fail("EngineException 이 나야 함") // 여기 오면 실패
        } catch (e: EngineException) {
            assertEquals("PIPE_NOT_CONSUMED", e.code) // 다른 입력 사용 의심
        }
        assertTrue(h.sessions[0].recorder.closed) // audio 닫힘
    }

    /**
     * 목적: 응답 없음 1회 재시도 검증
     * 기능: 첫 세션 응답 없음 → abort·첫 백엔드 close·새 백엔드로 같은 조각 재시도 → 정상 결과, retried=true, 세션 번호 2.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun stallRetriesOnceWithNewBackend() = runTest {
        val h = harness("mlkit-basic-x4", SessionScript.NO_RESPONSE, SessionScript(SessionOutcome.Completed(listOf("다시")))) // 응답 없음 → 정상
        h.engine.prepare(1) // 준비(백엔드 1)
        val r = h.engine.transcribe(chunk(1.0), "ko") // 전사
        assertEquals("다시", r.text) // 재시도 결과
        val s = r.session!! // 세션 기록
        assertTrue(s.retried) // 재시도 표시
        assertEquals(2, s.number) // 누적 세션 번호
        assertEquals(32_000L, s.fedBytes) // 재시도 세션 공급량
        assertEquals(2, h.backends.size) // prepare 1 + 재생성 1
        assertEquals(1, h.backends[0].closeCount) // 첫 백엔드 닫힘
        assertEquals(0, h.backends[1].closeCount) // 새 백엔드는 사용 중
        assertTrue(h.sessions[0].abortCount >= 1) // 첫 세션 중단
        assertTrue(h.sessions[0].recorder.closed) // 첫 세션 audio 닫힘
    }

    /**
     * 목적: 두 번 응답 없음 검증
     * 기능: 두 세션 모두 응답 없음 → EngineStalledException(종류·서비스 이름 포함), 경과 = 2 × (조각÷배속 + 15 초) ± 1 초.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun stallTwiceThrowsStalled() = runTest {
        val h = harness("mlkit-basic-x1", SessionScript.NO_RESPONSE, SessionScript.NO_RESPONSE) // 두 번 응답 없음
        h.engine.prepare(1) // 준비
        val t0 = currentTime // 시작 시각
        try {
            h.engine.transcribe(chunk(2.0), "ko") // 2 초 전사
            fail("EngineStalledException 이 나야 함") // 여기 오면 실패
        } catch (e: EngineStalledException) {
            assertEquals(SystemEngineKind.MLKIT_BASIC, e.kind) // 종류
            assertTrue(e.message!!.contains("Speech Services by Google")) // 서비스 이름
            assertTrue(e.message!!.contains("세션 2")) // 세션 번호
        }
        val elapsed = currentTime - t0 // 경과(ms)
        val expected = 2 * (2_000L + 15_000L) // 기대 경과
        assertTrue("경과 $elapsed ms", elapsed in (expected - 1_000)..(expected + 1_000)) // ±1 초
        assertEquals(2, h.backends.size) // 재생성 1회만
        assertTrue(h.sessions.all { it.abortCount >= 1 }) // 두 세션 모두 중단
    }

    /**
     * 목적: 공급 중 취소 검증
     * 기능: 30 초 조각 1× 공급 중 2 초 시점 Job 취소 → 가상 3 초 안에 CancellationException, 세션 abort 호출.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun cancelDuringPacedFeedAbortsWithinOneSecond() = runTest {
        val h = harness("mlkit-basic-x1", SessionScript.NO_RESPONSE) // 응답 없음
        h.engine.prepare(1) // 준비
        val t0 = currentTime // 시작 시각
        var caught: Throwable? = null // 잡힌 예외
        val job = launch { // 전사 코루틴
            try {
                h.engine.transcribe(chunk(30.0), "ko") // 30 초 전사
            } catch (e: Throwable) {
                caught = e // 기록
                throw e // 그대로 전파
            }
        }
        advanceTimeBy(2_000) // 2 초 공급
        job.cancel() // 취소
        job.join() // 끝날 때까지(가상 시간 진행)
        assertTrue("경과 ${currentTime - t0} ms", currentTime - t0 < 3_000) // 3 초 안
        assertTrue("예외 $caught", caught is CancellationException) // 취소 전파
        val session = h.sessions[0] // 유일 세션
        assertTrue(session.abortCount >= 1) // 세션 중단
        assertTrue(session.recorder.closed) // audio 닫힘
        assertTrue(session.recorder.bytes < 30 * 32_000L) // 다 쓰지 않음
    }

    /**
     * 목적: 배속별 감시 시간 검증
     * 기능: 20 초 조각 4× → 감시 = 5,000 + 15,000 ms. 첫 세션 abort 시각으로 확인한다(재시도는 정상 완료).
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun watchdogScalesWithSpeed() = runTest {
        val h = harness("mlkit-basic-x4", SessionScript.NO_RESPONSE, SessionScript(SessionOutcome.Completed(listOf("ok")))) // 응답 없음 → 정상
        h.engine.prepare(1) // 준비
        val t0 = currentTime // 시작 시각
        val r = h.engine.transcribe(chunk(20.0), "ko") // 20 초 전사
        assertEquals("ok", r.text) // 재시도 결과
        assertEquals(20_000L, h.sessions[0].firstAbortAtMs!! - t0) // 감시 시간
    }
}
