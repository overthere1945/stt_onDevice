// 파일명: PcmConverterTest.kt
// 목적: PcmConverter 의 16 kHz mono 변환 동작 검증
// 기능: 다운믹스·리샘플, 무변환 경로, 8-bit 변환, float→PCM16, 실제 음원 길이를 확인한다.
package com.cowork.stt_ondevice.core.audio // 오디오 테스트 패키지

import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 조건부 테스트 실행
import org.junit.Test // JUnit4 테스트 표시
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: 오디오 변환 회귀 방지
 * 기능: WavData 를 직접 만들어 변환 결과를 검증한다.
 */
class PcmConverterTest {

    /**
     * 목적: 44.1 kHz 스테레오 → 16 kHz mono 변환 확인
     * 기능: 1초 분량 입력이 정확히 16000 샘플로 나오고 원본 정보가 보존되는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun stereo44kResampledTo16kMono() {
        val frames = 44100 // 1초 분량 프레임
        val pcm = ShortArray(frames * 2) { 1000 } // 좌우 동일한 상수 신호
        val clip = PcmConverter.toMono16k(WavData(44100, 2, 16, pcm)) // 변환
        assertEquals(16000, clip.samples.size) // 1초 = 16000 샘플
        assertTrue(clip.converted) // 변환됨
        assertEquals(2, clip.sourceChannels) // 원본 채널 보존
        assertEquals(44100, clip.sourceSampleRate) // 원본 샘플레이트 보존
        assertEquals(1000 / 32768f, clip.samples[8000], 1e-6f) // 상수 신호는 보간 후에도 유지
        assertEquals(1.0, clip.durationSec, 1e-9) // 길이 1초
    }

    /**
     * 목적: 이미 16 kHz mono 면 무변환 확인
     * 기능: converted=false 이고 값이 pcm/32768 인지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun mono16kNotConverted() {
        val pcm = shortArrayOf(0, 16384, -16384, 32767) // 입력 샘플
        val clip = PcmConverter.toMono16k(WavData(16000, 1, 16, pcm)) // 변환 시도
        assertFalse(clip.converted) // 무변환
        assertEquals(4, clip.samples.size) // 개수 유지
        for (i in pcm.indices) assertEquals(pcm[i] / 32768f, clip.samples[i], 0f) // 값 = pcm/32768
    }

    /**
     * 목적: 8-bit unsigned 처리 확인
     * 기능: 128 은 0f, 0 은 -1f 로 변환되는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun eightBitConverted() {
        // 8-bit 는 WavData.pcm 에 unsigned 값(0..255)을 그대로 담는 규약
        val clip = PcmConverter.toMono16k(WavData(16000, 1, 8, shortArrayOf(128, 0, 255))) // 8-bit 입력
        assertEquals(0f, clip.samples[0], 0f) // 128 -> 0
        assertEquals(-1f, clip.samples[1], 0f) // 0 -> -1
        assertEquals(127f / 128f, clip.samples[2], 0f) // 255 -> 127/128
        assertEquals(8, clip.sourceBits) // 원본 비트 보존
    }

    /**
     * 목적: float → PCM16 변환과 클리핑 확인
     * 기능: 범위를 넘는 값이 Short 범위로 잘리는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun floatToPcm16ClipsAndScales() {
        val out = PcmConverter.floatToPcm16(floatArrayOf(0f, 0.5f, -0.5f, 2f, -2f)) // 변환
        assertArrayEquals(shortArrayOf(0, 16384, -16384, 32767, -32768), out) // 기대 PCM
    }

    /**
     * 목적: 실제 데이터셋 음원 sanity 확인
     * 기능: 파일이 있을 때만 읽어 길이가 84.0~84.2 초인지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun realDatasetFileIfPresent() {
        val f = File("/home/hyungchul/workspace/stt/input_sound/kr/customer/01.wav") // 실제 음원 경로
        assumeTrue(f.exists()) // 없으면 건너뜀
        val clip = PcmConverter.toMono16k(WavReader.read(f)) // 읽고 변환
        assertTrue("길이=${clip.durationSec}", clip.durationSec in 84.0..84.2) // 84.1 초 근방
        assertFalse(clip.converted) // 이미 16k mono
    }
}
