// 파일명: WavReaderTest.kt
// 목적: WavReader / WavWriter 의 파싱·기록 동작 검증
// 기능: 왕복, 추가 청크 건너뛰기, EXTENSIBLE 헤더, 잘린 data 보정, 잘못된 형식 예외를 확인한다.
package com.cowork.stt_ondevice.core.audio // 오디오 테스트 패키지

import com.cowork.stt_ondevice.core.audio.WavTestBytes.chunk // 청크 헬퍼
import com.cowork.stt_ondevice.core.audio.WavTestBytes.pcm16 // PCM16 바이트 헬퍼
import com.cowork.stt_ondevice.core.audio.WavTestBytes.wavBytes // WAV 조립 헬퍼
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Test // JUnit4 테스트 표시
import java.io.File // 임시 파일

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: WAV 입출력 회귀 방지
 * 기능: 합성 WAV 바이트로 파서의 각 분기를 검증한다.
 */
class WavReaderTest {

    /**
     * 목적: Writer→Reader 왕복 일치 확인
     * 기능: 샘플 4개를 파일로 쓰고 다시 읽어 동일한지 비교한다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun pcm16Mono16kRoundTrip() {
        val pcm = shortArrayOf(0, 16384, -16384, 32767) // 기록할 샘플
        val f = File.createTempFile("wavrt", ".wav") // 임시 파일
        try {
            WavWriter.write(f, pcm, 16000) // WAV 로 기록
            val w = WavReader.read(f) // 다시 읽기
            assertArrayEquals(pcm, w.pcm) // 샘플 동일
            assertEquals(16000, w.sampleRate) // 샘플레이트 동일
            assertEquals(1, w.channels) // 모노
            assertEquals(16, w.bitsPerSample) // 16-bit
        } finally {
            f.delete() // 임시 파일 정리
        }
    }

    /**
     * 목적: LIST 등 임의 청크 건너뛰기 확인
     * 기능: fmt 앞과 data 앞(홀수 길이 포함)에 LIST 청크를 넣고 정상 파싱되는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun listChunkSkipped() {
        val pcm = shortArrayOf(1, 2, 3) // 샘플
        val bytes = wavBytes(
            16000, 1, 16, pcm16(pcm),
            chunksBeforeFmt = listOf(chunk("LIST", ByteArray(5))), // 홀수 길이(패딩 포함) 청크
            chunksBetween = listOf(chunk("LIST", ByteArray(8))), // fmt 와 data 사이 청크
        )
        val w = WavReader.read(bytes) // 파싱
        assertArrayEquals(pcm, w.pcm) // 샘플 일치
    }

    /**
     * 목적: WAVE_FORMAT_EXTENSIBLE 헤더 지원 확인
     * 기능: 0xFFFE + PCM 서브포맷 GUID 헤더를 정상 파싱하는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun extensibleHeaderParsed() {
        val pcm = shortArrayOf(5, -5) // 샘플
        val w = WavReader.read(wavBytes(48000, 2, 16, pcm16(pcm), extensible = true)) // 확장 헤더 WAV
        assertEquals(48000, w.sampleRate) // 샘플레이트
        assertEquals(2, w.channels) // 채널
        assertArrayEquals(pcm, w.pcm) // 샘플
    }

    /**
     * 목적: data 길이 필드가 실제보다 클 때 보정 확인
     * 기능: 선언 크기를 크게 적어도 실제 있는 샘플 수만큼만 읽는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun truncatedDataChunkClamped() {
        val pcm = shortArrayOf(10, 20, 30) // 실제 샘플 3개
        val w = WavReader.read(wavBytes(16000, 1, 16, pcm16(pcm), dataDeclaredSize = 1000)) // 선언만 1000바이트
        assertArrayEquals(pcm, w.pcm) // 실제 길이만큼
    }

    /**
     * 목적: RIFF 가 아닌 입력 거부 확인
     * 기능: 임의 바이트에서 WavFormatException 이 나는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun notRiffThrows() {
        assertThrows(WavFormatException::class.java) { WavReader.read(ByteArray(64) { 7 }) } // RIFF 아님
    }

    /**
     * 목적: IEEE float(포맷 3) 거부 확인
     * 기능: 지원하지 않는 포맷 코드에서 WavFormatException 이 나는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun floatFormatThrows() {
        val bytes = wavBytes(16000, 1, 32, ByteArray(8), formatTag = 3) // float WAV
        assertThrows(WavFormatException::class.java) { WavReader.read(bytes) } // 미지원
    }
}
