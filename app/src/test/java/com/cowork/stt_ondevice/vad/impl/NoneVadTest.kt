// 파일명: NoneVadTest.kt
// 목적: NoneVad 동작 검증
// 기능: 전체 길이 1개 구간·점수 null·빈 입력 처리를 확인한다.
package com.cowork.stt_ondevice.vad.impl // VAD 구현 테스트 패키지

import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: NoneVad 회귀 방지
 * 기능: 파일 전체를 하나의 발화로 돌려주는지 확인한다.
 */
class NoneVadTest {

    /**
     * 목적: 전체 파일 1구간 반환 검증
     * 기능: 5 초 오디오가 [0.0-5.0] 한 구간, 점수 null 로 나오는지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun noneVadReturnsWholeFile() {
        val vad = NoneVad() // NONE VAD
        assertEquals(VadFamily.NONE, vad.family) // 계열
        val out = vad.detect(FloatArray(16000 * 5), VadPresets.byId("NONE")) // 5 초 실행
        assertEquals(listOf(SpeechSegment(0.0, 5.0)), out.segments) // 전체 1구간
        assertNull(out.frameScores) // 점수 없음
        assertNull(out.frameHopSec) // hop 없음
    }

    /**
     * 목적: 빈 입력 처리 검증
     * 기능: 길이 0 오디오는 구간 없이 반환되는지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun emptyAudioGivesNoSegments() {
        val out = NoneVad().detect(FloatArray(0), VadPresets.byId("NONE")) // 빈 입력
        assertTrue(out.segments.isEmpty()) // 구간 없음
    }
}
