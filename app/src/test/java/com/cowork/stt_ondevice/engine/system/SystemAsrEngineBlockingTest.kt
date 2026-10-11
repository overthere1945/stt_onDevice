// 파일명: SystemAsrEngineBlockingTest.kt
// 목적: 공급 스레드가 파이프 write 에서 막혀 있어도 SystemAsrEngine 의 엔진 오류·감시 시간 초과(멈춤)·Job 취소가
//       짧은 실제 시간 안에 끝나는지 실제 스레드(Dispatchers.IO, runBlocking + withTimeout)로 검증 (2차 작업 3, 리뷰 라운드 1)
// 기능: 첫 write 에서 latch 로 막히는 스트림 두 종류로 각 경우를 확인한다 —
//       (1) close 하면 write 가 IOException 으로 풀리는 정상 파이프, (2) close 해도 write 가 풀리지 않는 최악의 스트림.
//       테스트 끝에는 막힌 스레드를 모두 풀어 다른 테스트에 남기지 않는다.
// change(add)-hyungchul-2026-10-11-18:51
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 엔진 카탈로그
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.Dispatchers // 실제 디스패처
import kotlinx.coroutines.delay // 실제 시간 대기
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.runBlocking // 실제 스레드 테스트 실행
import kotlinx.coroutines.withTimeout // 실제 시간 제한
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Test // JUnit4 테스트 표시
import java.io.IOException // 입출력 오류
import java.io.OutputStream // 출력 스트림
import java.util.Collections // 동기화 목록
import java.util.concurrent.CountDownLatch // 막힘·풀림 신호
import java.util.concurrent.TimeUnit // 시간 단위
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-18:51
/**
 * 목적: 첫 write 에서 막히는 출력 스트림
 * 기능: write 는 풀림 신호가 올 때까지 막혔다가 IOException 을 던진다(엔진이 파이프를 읽지 않는 상황 재현).
 *       releaseOnClose=true 면 close 가 막힌 write 를 풀고(정상 파이프), false 면 close 해도 풀리지 않는다(최악의 경우).
 * 입력: releaseOnClose(close 가 write 를 푸는지)
 */
class BlockingOutputStream(private val releaseOnClose: Boolean) : OutputStream() {
    private val release = CountDownLatch(1) // 풀림 신호
    val writeEntered = CountDownLatch(1) // write 진입 신호
    @Volatile var closed = false // 닫힘 여부

    /**
     * 목적: 1 바이트 쓰기
     * 기능: 블록 쓰기와 같이 막힌다.
     * 입력: b(바이트 값)
     * 출력: 풀리면 IOException
     * 리턴: 없음
     */
    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1) // 블록 쓰기로 위임

    /**
     * 목적: 블록 쓰기(막힘)
     * 기능: 진입을 알리고 풀림 신호까지 막혔다가 IOException 을 던진다.
     * 입력: b(버퍼), off(시작 위치), len(길이)
     * 출력: 풀리면 IOException
     * 리턴: 없음
     */
    override fun write(b: ByteArray, off: Int, len: Int) {
        writeEntered.countDown() // 진입 알림
        release.await() // 풀릴 때까지 막힘(인터럽트·코루틴 취소로는 풀지 않음)
        throw IOException("파이프 닫힘") // 닫힌 파이프 쓰기
    }

    /**
     * 목적: 닫기
     * 기능: 닫힘을 기록하고, releaseOnClose 면 막힌 write 를 푼다. 즉시 돌아온다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        closed = true // 닫힘 기록
        if (releaseOnClose) release.countDown() // 정상 파이프는 write 를 풂
    }

    /**
     * 목적: 테스트 정리
     * 기능: 최악의 스트림에 막힌 스레드를 테스트 끝에 풀어 준다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun releaseForCleanup() = release.countDown() // 강제 풀림
}

// change(add)-hyungchul-2026-10-11-18:51
/**
 * 목적: 막힌 write 상황의 실제 스레드 테스트
 * 기능: 엔진은 기본 io(Dispatchers.IO)·실제 공급기·실제 시계로 만들고, 각 경우를 두 종류 스트림으로 확인한다.
 */
class SystemAsrEngineBlockingTest {

    /** 목적: 실제 시간 제한 / 기능: transcribe 가 이 시간 안에 끝나야 함(ms) */
    private val limitMs = 3_000L // 3 초

    /**
     * 목적: 테스트 환경 묶음
     * 기능: 엔진, 생성된 백엔드, 세션마다 만든 막히는 스트림을 담는다.
     * 입력: engine(시험 대상), backends(생성 순서 백엔드), streams(생성 순서 스트림)
     */
    private class Rig(val engine: SystemAsrEngine, val backends: List<FakeBackend>, val streams: List<BlockingOutputStream>) {
        /**
         * 목적: 정리
         * 기능: 막힌 스레드를 모두 풀고 엔진을 닫는다.
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        fun cleanup() {
            synchronized(streams) { streams.forEach { it.releaseForCleanup() } } // 막힌 스레드 풀기
            engine.close() // 엔진 해제
        }
    }

    /**
     * 목적: 실제 스레드 엔진 생성
     * 기능: 세션마다 BlockingOutputStream 을 공급 쪽으로 쓰는 FakeBackend 를 만드는 factory 로 엔진을 만든다.
     * 입력: specId(카탈로그 id), releaseOnClose(스트림 종류), scripts(세션 순서대로의 각본)
     * 출력: 없음
     * 리턴: Rig
     */
    private fun rig(specId: String, releaseOnClose: Boolean, vararg scripts: SessionScript): Rig {
        val queue = ArrayDeque(scripts.toList()) // 공유 각본 큐
        val backends = Collections.synchronizedList(ArrayList<FakeBackend>()) // 생성된 백엔드
        val streams = Collections.synchronizedList(ArrayList<BlockingOutputStream>()) // 생성된 스트림
        val engine = SystemAsrEngine( // 시험 대상(io·공급기·시계는 기본값 = 실제)
            spec = SystemCatalog.all.first { it.id == specId }, // 시스템 spec
            backendFactory = { // 백엔드 생성
                FakeBackend(queue, System::currentTimeMillis) { BlockingOutputStream(releaseOnClose).also { streams += it } }.also { backends += it } // 막히는 스트림 사용
            },
            versionInfo = { "fake-1.0" }, // 버전 문자열
            closeTimeoutMs = 500, // 백엔드 닫기 제한
        )
        return Rig(engine, backends, streams) // 묶음
    }

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
     * 목적: 막힌 write 중 엔진 오류 검증
     * 기능: 10 초 조각 1× 공급이 첫 write 에서 막힌 채 300 ms 에 Failed(LANGUAGE_UNAVAILABLE) → 3 초 안에 그 코드로 끝나고 audio 가 닫힌다.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError, 제한 시간 초과 시 TimeoutCancellationException
     * 리턴: 없음
     */
    @Test
    fun blockedWriteDoesNotDelayEngineError() {
        for (releaseOnClose in listOf(true, false)) { // 정상 파이프·최악의 스트림
            val r = rig("mlkit-basic-x1", releaseOnClose, SessionScript(SessionOutcome.Failed("LANGUAGE_UNAVAILABLE", "en-US 미설치"), atMs = 300)) // 300 ms 오류
            try {
                runBlocking {
                    r.engine.prepare(1) // 준비
                    withTimeout(limitMs) { // 실제 시간 제한
                        try {
                            r.engine.transcribe(chunk(10.0), "en") // 전사
                            fail("EngineException 이 나야 함") // 여기 오면 실패
                        } catch (e: EngineException) {
                            assertEquals("LANGUAGE_UNAVAILABLE", e.code) // 엔진 코드 그대로
                        }
                    }
                }
                val s = r.streams[0] // 유일 스트림
                assertTrue("write 가 막혔어야 함(releaseOnClose=$releaseOnClose)", s.writeEntered.await(0, TimeUnit.MILLISECONDS)) // 막힘 재현 확인
                assertTrue(s.closed) // audio 닫힘
            } finally {
                r.cleanup() // 막힌 스레드 정리
            }
        }
    }

    /**
     * 목적: 막힌 write 중 응답 없음(멈춤) 검증
     * 기능: 감시 여유를 200 ms 로 줄이고(0.5 초 4× → 감시 325 ms) 두 세션 모두 응답 없음·write 막힘 → 3 초 안에 EngineStalledException,
     *       백엔드 2개(재생성 1회), 두 세션 모두 abort.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError, 제한 시간 초과 시 TimeoutCancellationException
     * 리턴: 없음
     */
    @Test
    fun blockedWriteDoesNotDelayStall() {
        for (releaseOnClose in listOf(true, false)) { // 정상 파이프·최악의 스트림
            val r = rig("mlkit-basic-x4", releaseOnClose, SessionScript.NO_RESPONSE, SessionScript.NO_RESPONSE) // 두 번 응답 없음
            r.engine.watchdogMarginMs = 200 // 실제 시간 테스트용 짧은 여유
            try {
                runBlocking {
                    r.engine.prepare(1) // 준비
                    withTimeout(limitMs) { // 실제 시간 제한
                        try {
                            r.engine.transcribe(chunk(0.5), "ko") // 전사
                            fail("EngineStalledException 이 나야 함") // 여기 오면 실패
                        } catch (e: EngineStalledException) {
                            assertTrue(e.message!!.contains("세션 2")) // 두 번째 세션에서 멈춤
                        }
                    }
                }
                assertEquals(2, r.backends.size) // 재생성 1회
                assertTrue(r.backends.flatMap { it.sessions }.all { it.abortCount >= 1 }) // 두 세션 모두 중단
                assertTrue(r.streams.all { it.closed }) // 두 세션 audio 닫힘
            } finally {
                r.cleanup() // 막힌 스레드 정리
            }
        }
    }

    /**
     * 목적: 막힌 write 중 Job 취소 검증
     * 기능: 30 초 조각 1× 공급이 첫 write 에서 막힌 상태로 Job 을 취소 → 1 초 안에 CancellationException 으로 끝나고 세션 abort·audio 닫힘.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError, 제한 시간 초과 시 TimeoutCancellationException
     * 리턴: 없음
     */
    @Test
    fun blockedWriteDoesNotDelayCancel() {
        for (releaseOnClose in listOf(true, false)) { // 정상 파이프·최악의 스트림
            val r = rig("mlkit-basic-x1", releaseOnClose, SessionScript.NO_RESPONSE) // 응답 없음
            try {
                runBlocking {
                    r.engine.prepare(1) // 준비
                    var caught: Throwable? = null // 잡힌 예외
                    val job = launch(Dispatchers.Default) { // 전사 코루틴
                        try {
                            r.engine.transcribe(chunk(30.0), "ko") // 30 초 전사
                        } catch (e: Throwable) {
                            caught = e // 기록
                            throw e // 그대로 전파
                        }
                    }
                    withTimeout(limitMs) { while (r.streams.isEmpty()) delay(10) } // 세션이 열릴 때까지
                    assertTrue(r.streams[0].writeEntered.await(limitMs, TimeUnit.MILLISECONDS)) // write 가 막힐 때까지
                    job.cancel() // 취소
                    withTimeout(1_000) { job.join() } // 1 초 안에 끝나야 함
                    assertTrue("예외 $caught", caught is CancellationException) // 취소 전파
                }
                assertTrue(r.backends[0].sessions[0].abortCount >= 1) // 세션 중단
                assertTrue(r.streams[0].closed) // audio 닫힘
            } finally {
                r.cleanup() // 막힌 스레드 정리
            }
        }
    }
}
