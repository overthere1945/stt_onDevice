// 파일명: WebRtcPostTest.kt
// 목적: WebRtcPost(RAW / RING_BUFFER)와 WEBRTC_ANDROIDVAD(HANGOVER) 경로가 골든과 정확히 일치하는지 검증
// 기능: WEBRTC_* 골든 전부(3 프리셋 × 4 클립 = 12)를 JSON 의 frameScores(0/1 → Boolean, ≥ 0.5)와 프리셋 파라미터로 다시 계산해
//       초 값(비트 단위)·프레임 인덱스(expectedFrames)가 같은지 비교한다. 잔여 프레임 규칙(RAW·HANGOVER = floor(N/L), RING_BUFFER = floor((N-1)/L))도 확인한다.
//       골든이 닿지 않는 경계(파일 끝에서 열린 구간, 0.9 비율 부동소수 함정, 링버퍼 첫 프레임 시작)는 손작성 케이스로 검증한다.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 테스트 패키지

import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.WebRtcParams // WebRTC 파라미터
import com.cowork.stt_ondevice.vad.WebRtcStyle // WebRTC 후처리 방식
import kotlinx.serialization.json.int // JSON → Int
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값 변환
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-13:43
/**
 * 목적: WebRTC 후처리 포팅의 정합성(골든 일치) 회귀 방지
 * 기능: 골든 파일을 접두어 WEBRTC_ 로 모두 찾아(클립 이름 하드코딩 없음) 하나도 빠짐없이 비교한다.
 */
class WebRtcPostTest {

    /**
     * 목적: WEBRTC 3 프리셋 × 4 클립 골든 일치 검증
     * 기능: 발견 개수(파일 수·12·프리셋 3종 × 4 클립)를 고정하고, 각 골든을 프리셋 방식(RAW/RING_BUFFER/HANGOVER)대로 다시 계산해
     *       초 구간이 정확히 같은지(최대 편차 0.0), 프레임 인덱스가 expectedFrames 와 같은지 본다. 이 중 하나라도 어긋나면 목록으로 실패한다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun matchesGoldenWebRtc() {
        val goldens = GoldenLoader.allFor("WEBRTC") // WEBRTC_* 구간 골든 전부
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("WEBRTC_"), goldens.size) // 빠진 파일 없음
        assertEquals("WEBRTC 골든은 3 프리셋 × 4 클립 = 12 개여야 함", 12, goldens.size) // 발견 개수 고정(R14)
        assertEquals("WEBRTC 프리셋 3종이 모두 있어야 함", 3, goldens.map { it.presetId }.toSet().size) // 프리셋 누락 없음
        assertEquals("WEBRTC 클립 4종이 모두 있어야 함", 4, goldens.map { it.clip }.toSet().size) // 클립 누락 없음
        val failures = mutableListOf<String>() // 실패 메시지 모음
        for (g in goldens) { // 골든마다
            try { // 한 골든 실패가 나머지 비교를 막지 않도록
                checkOne(g) // 개별 비교
            } catch (e: AssertionError) { // 불일치
                failures += "${g.name}: ${e.message}" // 기록
            }
        }
        assertTrue("${failures.size}/${goldens.size} 개 불일치\n" + failures.joinToString("\n"), failures.isEmpty()) // 전부 통과해야 함
    }

    /**
     * 목적: 골든 1건 비교
     * 기능: (1) 프리셋 파라미터가 JSON params 와 같은지 (2) 점수가 0/1 이고 프레임 수가 잔여 프레임 규칙과 맞는지
     *       (3) 방식별 후처리 결과의 초 구간이 expected 와 정확히 같은지 (4) 프레임 인덱스(초 ÷ hop 반올림)가 expectedFrames 와 같은지 본다.
     * 입력: g(골든)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun checkOne(g: Golden) {
        val p = VadPresets.byId(g.presetId).params as WebRtcParams // 프리셋 파라미터(앱이 실제로 쓸 값)
        val jp = g.params!! // JSON params 원문
        assertEquals("mode", jp["mode"]!!.jsonPrimitive.int, p.mode) // 모드
        assertEquals("frameMs", jp["frameMs"]!!.jsonPrimitive.int, p.frameMs) // 프레임 길이
        assertEquals("style", jp["style"]!!.jsonPrimitive.content, p.style.name) // 후처리 방식
        assertEquals("speechMs", jp["speechMs"]!!.jsonPrimitive.int, p.speechMs) // 시작 확정 길이
        assertEquals("silenceMs", jp["silenceMs"]!!.jsonPrimitive.int, p.silenceMs) // 종료 확정 길이
        if (p.style == WebRtcStyle.RING_BUFFER) { // 링버퍼 전용 값
            assertEquals("paddingMs", jp["paddingMs"]!!.jsonPrimitive.int, p.paddingMs) // 패딩
            assertEquals("ratio", jp["ratio"]!!.jsonPrimitive.content.toDouble(), p.ratio.toString().toDouble(), 0.0) // 비율(최단 표기 비교)
        }
        assertEquals("hopSec", p.frameMs / 1000.0, g.hopSec!!, 0.0) // 골든 hop = frameMs / 1000.0
        assertTrue("frameScores 가 0/1 이 아님", g.frameScores.all { it == 0.0f || it == 1.0f }) // WebRTC 점수는 0/1
        val n = g.totalSamples!! // 전체 샘플 수
        val l = 16 * p.frameMs // 프레임당 샘플 수(16 kHz)
        val expectedFrameCount = if (p.style == WebRtcStyle.RING_BUFFER) (n - 1) / l else n / l // 잔여 프레임 규칙(README §5.1)
        assertEquals("프레임 수(잔여 규칙)", expectedFrameCount, g.frameScores.size) // RING_BUFFER 는 엄격한 <, 나머지는 floor
        val flags = BooleanArray(g.frameScores.size) { g.frameScores[it] >= 0.5f } // 0/1 점수 → Boolean
        val actual = when (p.style) { // 방식별 후처리
            WebRtcStyle.RAW -> WebRtcPost.raw(flags, p.frameMs) // 연속 voiced 병합
            WebRtcStyle.RING_BUFFER -> WebRtcPost.ringBuffer(flags, p.frameMs, p.paddingMs, p.ratio) // example.py vad_collector
            WebRtcStyle.HANGOVER -> SimpleHangover.apply(g.frameScores, g.hopSec, 0.5f, p.speechMs, p.silenceMs) // SimpleHangover(th 0.5)
        }
        assertEquals("구간 개수 불일치\n기대=${g.expected}\n실제=$actual", g.expected.size, actual.size) // 개수
        var maxDev = 0.0 // 초 단위 최대 편차
        for (i in actual.indices) { // 구간마다
            maxDev = maxOf(maxDev, Math.abs(actual[i].startSec - g.expected[i].startSec), Math.abs(actual[i].endSec - g.expected[i].endSec)) // 편차 누적
        }
        assertEquals("초 구간 최대 편차(0 이어야 함)\n기대=${g.expected}\n실제=$actual", 0.0, maxDev, 0.0) // 비트 단위 일치
        val ef = g.expectedFrames!! // 프레임 인덱스 기대값
        assertEquals("expectedFrames 개수", ef.size, actual.size) // 개수
        for (i in actual.indices) { // 구간마다
            assertEquals("구간[$i] 시작 프레임", ef[i][0].toLong(), Math.round(actual[i].startSec / g.hopSec)) // 시작 프레임
            assertEquals("구간[$i] 끝 프레임", ef[i][1].toLong(), Math.round(actual[i].endSec / g.hopSec)) // 끝 프레임(배타)
        }
    }

    /** 목적: 테스트용 Boolean 배열 조립. 입력: 'T'(voiced)/'F'(unvoiced) 로 된 문자열. 리턴: BooleanArray. */
    private fun flagsOf(pattern: String): BooleanArray = BooleanArray(pattern.length) { pattern[it] == 'T' } // 문자 → Boolean

    /**
     * 목적: RAW 경계 검증
     * 기능: 연속 병합, 끝까지 이어진 구간(마지막 프레임 끝에서 닫힘), 전부 unvoiced/빈 입력(구간 없음)을 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun rawMergesContiguousVoicedFrames() {
        val hop = 30 / 1000.0 // 30 ms 프레임
        assertEquals( // 중간 구간 2개 + 끝까지 이어진 구간
            listOf(SpeechSegment(1 * hop, 3 * hop), SpeechSegment(5 * hop, 8 * hop)), // [1,3) [5,8)
            WebRtcPost.raw(flagsOf("FTTFFTTT"), 30), // 마지막 구간은 flags.size(8) 경계에서 닫힘
        )
        assertEquals("전부 unvoiced", emptyList<SpeechSegment>(), WebRtcPost.raw(flagsOf("FFFF"), 30)) // 구간 없음
        assertEquals("빈 입력", emptyList<SpeechSegment>(), WebRtcPost.raw(BooleanArray(0), 30)) // 구간 없음
    }

    /**
     * 목적: ring buffer 시작·끝 시각 규칙 검증
     * 기능: 3 프레임 unvoiced 뒤 10 프레임 voiced 면 트리거 시점(13번째, 인덱스 12)에 구간 시작 = 링버퍼 첫 프레임(인덱스 3),
     *       이어서 10 프레임 unvoiced 면 마지막 unvoiced(인덱스 22) 프레임 끝(23 × hop)에서 종료함을 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun ringBufferStartsAtFirstBufferedFrameAndEndsAtDetriggerFrameEnd() {
        val hop = 30 / 1000.0 // 30 ms 프레임
        val flags = flagsOf("FFF" + "T".repeat(10) + "F".repeat(10) + "FFFF") // 트리거 후 10 프레임 unvoiced 에서 종료, 뒤 4 프레임은 무관
        assertEquals(listOf(SpeechSegment(3 * hop, 23 * hop)), WebRtcPost.ringBuffer(flags, 30, 300, 0.9f)) // [3, 23)
    }

    /**
     * 목적: 파일 끝에서 열린 구간 닫기 검증
     * 기능: 트리거된 채 입력이 끝나면 끝 = (마지막 프레임 번호 + 1) × hop = flags.size × hop 임을 확인한다(잔여 샘플 시간은 더하지 않음).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun ringBufferClosesOpenSegmentAtLastFrameEnd() {
        val hop = 30 / 1000.0 // 30 ms 프레임
        val flags = flagsOf("FFF" + "T".repeat(12)) // 인덱스 12 에서 트리거, 이후 2 프레임 더 voiced, 15 프레임 입력
        assertEquals(listOf(SpeechSegment(3 * hop, 15 * hop)), WebRtcPost.ringBuffer(flags, 30, 300, 0.9f)) // 끝 = 15 × hop
    }

    /**
     * 목적: 0.9 비율 부동소수 함정 검증
     * 기능: 링버퍼 10 개 중 voiced 9 개는 "9 > 0.9 × 10" 이 거짓이라 트리거되지 않아야 한다.
     *       ratio 를 Float 0.9f 그대로 double 로 바꾸면 0.8999999762 × 10 < 9 가 되어 잘못 트리거되므로 이를 막는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun ringBufferDoesNotTriggerOnNineOfTenVoiced() {
        val flags = flagsOf("F" + "T".repeat(9) + "F".repeat(20)) // 어떤 10 프레임 창에도 voiced 는 최대 9 개
        assertEquals(emptyList<SpeechSegment>(), WebRtcPost.ringBuffer(flags, 30, 300, 0.9f)) // 구간 없음
    }
}
