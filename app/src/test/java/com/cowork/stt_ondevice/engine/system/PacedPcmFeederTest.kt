// 파일명: PacedPcmFeederTest.kt
// 목적: 실시간 배속 공급기(PacedPcmFeeder)와 PCM16 변환(PcmFrames)의 동작 검증 (2차 작업 3)
// 기능: - pacesAtSpeed: 1 초 분량을 1× 는 900 ms 이상, 4× 는 225 ms 이상 300 ms 미만(가상 시간)에 모두 공급
//       - tinyChunkIsFed: 0.025 초(800 B) 조각도 블록 1개로 공급
//       - writeFailureStopsWithoutThrow: 쓰기 IOException 은 던지지 않고 completed=false 로 반환
//       - toPcm16Clamps: [-1,1] 자르기 × 32767, 리틀엔디언
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 테스트 패키지

import kotlinx.coroutines.ExperimentalCoroutinesApi // 실험적 테스트 API 표시
import kotlinx.coroutines.delay // 가상 시간 대기
import kotlinx.coroutines.test.TestScope // 코루틴 테스트 범위
import kotlinx.coroutines.test.currentTime // 가상 현재 시각(ms)
import kotlinx.coroutines.test.runTest // 코루틴 테스트 실행기
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시
import java.io.ByteArrayOutputStream // 메모리 출력 스트림
import java.io.IOException // 입출력 오류
import java.io.OutputStream // 출력 스트림

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: PacedPcmFeeder·PcmFrames 단위 테스트
 * 기능: runTest 가상 시간으로 공급 속도·블록 수·쓰기 실패 처리·PCM 변환을 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class) // currentTime·advanceTimeBy·StandardTestDispatcher 사용
class PacedPcmFeederTest {

    /**
     * 목적: 가상 시간 공급기 생성
     * 기능: delay 와 가상 시계(testScheduler.currentTime, ns 환산)를 넣은 공급기를 만든다.
     * 입력: TestScope(수신 객체)
     * 출력: 없음
     * 리턴: PacedPcmFeeder
     */
    private fun TestScope.virtualFeeder(): PacedPcmFeeder =
        PacedPcmFeeder(delayMs = { delay(it) }, nanoTime = { testScheduler.currentTime * 1_000_000L }) // 가상 시간 주입

    /**
     * 목적: 배속별 공급 시간 검증
     * 기능: 1 초(32,000 B)를 1× 로 공급하면 마지막 블록이 900 ms 에, 4× 면 225 ms 에 쓰인다.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun pacesAtSpeed() = runTest {
        val feeder = virtualFeeder() // 가상 시간 공급기
        val pcm = ByteArray(32_000) // 1 초 분량 PCM16
        val out1 = ByteArrayOutputStream() // 1× 출력
        val t1 = currentTime // 1× 시작 시각
        val s1 = feeder.feed(out1, pcm, 1) // 1× 공급
        val e1 = currentTime - t1 // 1× 경과(ms)
        assertTrue("1× 경과 $e1 ms", e1 >= 900) // 실시간 속도
        assertEquals(32_000L, s1.writtenBytes) // 전부 씀
        assertEquals(32_000, out1.size()) // 실제 출력 크기
        assertTrue(s1.completed) // 완료
        val out4 = ByteArrayOutputStream() // 4× 출력
        val t4 = currentTime // 4× 시작 시각
        val s4 = feeder.feed(out4, pcm, 4) // 4× 공급
        val e4 = currentTime - t4 // 4× 경과(ms)
        assertTrue("4× 경과 $e4 ms", e4 >= 225 && e4 < 300) // 4 배속
        assertEquals(32_000L, s4.writtenBytes) // 전부 씀
        assertTrue(s4.completed) // 완료
    }

    /**
     * 목적: 아주 짧은 조각 공급 검증
     * 기능: 800 B(0.025 초)는 블록 1개(쓰기 1회)로 전부 공급된다.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun tinyChunkIsFed() = runTest {
        var writes = 0 // 쓰기 호출 수
        val sink = ByteArrayOutputStream() // 실제 저장소
        val out = object : OutputStream() { // 쓰기 횟수 기록 스트림
            override fun write(b: Int) { writes++; sink.write(b) } // 1 바이트 쓰기
            override fun write(b: ByteArray, off: Int, len: Int) { writes++; sink.write(b, off, len) } // 블록 쓰기
        }
        val s = virtualFeeder().feed(out, ByteArray(800), 1) // 0.025 초 공급
        assertEquals(1, writes) // 블록 1개
        assertEquals(800L, s.writtenBytes) // 전부 씀
        assertEquals(800, sink.size()) // 실제 출력 크기
        assertTrue(s.completed) // 완료
    }

    /**
     * 목적: 쓰기 실패 처리 검증
     * 기능: 두 번째 블록에서 IOException 이 나면 예외 없이 completed=false, 첫 블록 바이트만 기록된다.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError(예외가 새어 나오면 테스트 실패)
     * 리턴: 없음
     */
    @Test
    fun writeFailureStopsWithoutThrow() = runTest {
        var blocks = 0 // 받은 블록 수
        val out = object : OutputStream() { // 두 번째 블록에서 실패하는 스트림
            override fun write(b: Int) = throw IOException("1 바이트 쓰기 없음") // 사용 안 함
            override fun write(b: ByteArray, off: Int, len: Int) { // 블록 쓰기
                blocks++ // 블록 수 증가
                if (blocks == 2) throw IOException("파이프 끊김") // 두 번째 블록 실패
            }
        }
        val s = virtualFeeder().feed(out, ByteArray(32_000), 4) // 공급(예외 없어야 함)
        assertFalse(s.completed) // 미완료
        assertEquals(3_200L, s.writtenBytes) // 첫 블록만 씀
        assertEquals(2, blocks) // 실패 후 더 쓰지 않음
    }

    /**
     * 목적: PCM16 변환 검증
     * 기능: 범위 밖 값은 ±1 로 자르고 32767 을 곱해 리틀엔디언 2 바이트로 만든다.
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun toPcm16Clamps() {
        val bytes = PcmFrames.toPcm16Le(floatArrayOf(1.5f, -1.5f, 0.5f)) // 변환
        val expected = byteArrayOf(0xFF.toByte(), 0x7F, 0x01, 0x80.toByte(), 0xFF.toByte(), 0x3F) // 32767, -32767, 16383
        assertArrayEquals(expected, bytes) // 바이트 일치
    }
}
