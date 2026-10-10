// 파일명: SimpleHangoverTest.kt
// 목적: SimpleHangover(hangover_ref.py 포팅)가 TEN_DEFAULT 골든 및 손작성 기준 케이스와 정확히 일치하는지 검증
// 기능: TEN_TEN_DEFAULT_* 골든 4개(threshold 0.5, speechMs = silenceMs = 0)를 다시 계산해 초 구간·프레임 인덱스를 정확히 비교하고,
//       계획서 손작성 케이스(hangoverBridgesShortSilence)와 상태기계 경계(짧은 발화 무시, 진행 중 무음에서 끝, 프레임 수 계산)를 확인한다.
//       WEBRTC_ANDROIDVAD 골든(HANGOVER 20 ms, 50/300)은 WebRtcPostTest 가 SimpleHangover 로 검증한다.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 테스트 패키지

import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.HangoverParams // 임계값 + 행오버 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-13:43
/**
 * 목적: SimpleHangover 포팅의 정합성(골든·기준 정의 일치) 회귀 방지
 * 기능: TEN_DEFAULT 골든 전부와 hangover_ref.py 자체 검사(손작성) 케이스를 검증한다.
 */
class SimpleHangoverTest {

    /**
     * 목적: TEN_DEFAULT 4 클립 골든 일치 검증
     * 기능: "TEN_TEN_DEFAULT_" 골든을 모두 읽어(TEN_SHERPA 와 섞이지 않음) 개수를 고정하고, 프레임 점수에 프리셋 파라미터(0.5, 0, 0)로
     *       SimpleHangover 를 적용한 결과가 초 구간(최대 편차 0.0)과 expectedFrames 에 정확히 같은지 본다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun matchesGoldenTenDefault() {
        val goldens = GoldenLoader.allForPreset("TEN", "TEN_DEFAULT") // TEN_DEFAULT 구간 골든 전부
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("TEN_TEN_DEFAULT_"), goldens.size) // 빠진 파일 없음
        assertEquals("TEN_DEFAULT 골든은 4 클립이어야 함", 4, goldens.size) // 발견 개수 고정(R14)
        assertEquals("TEN_DEFAULT 클립 4종이 모두 있어야 함", 4, goldens.map { it.clip }.toSet().size) // 클립 누락 없음
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
     * 기능: 프리셋 파라미터가 JSON params 와 같은지 확인한 뒤 SimpleHangover 결과를 초(비트 단위)·프레임 인덱스로 비교한다.
     * 입력: g(골든)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun checkOne(g: Golden) {
        val p = VadPresets.byId(g.presetId).params as HangoverParams // 프리셋 파라미터(앱이 실제로 쓸 값)
        val jp = g.params!! // JSON params 원문
        assertEquals("threshold", jp["threshold"].toString().toDouble(), p.threshold.toString().toDouble(), 0.0) // 임계값
        assertEquals("speechMs", jp["speechMs"].toString().toInt(), p.speechMs) // 시작 확정 길이
        assertEquals("silenceMs", jp["silenceMs"].toString().toInt(), p.silenceMs) // 종료 확정 길이
        val n = g.totalSamples!! // 전체 샘플 수
        assertEquals("프레임 수 = floor(N/256)", n / 256, g.frameScores.size) // TEN 잔여 샘플 버림
        val actual = SimpleHangover.apply(g.frameScores, g.hopSec!!, p.threshold, p.speechMs, p.silenceMs) // 포팅 실행
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

    /**
     * 목적: 계획서 손작성 케이스(짧은 무음 메움)
     * 기능: hop 0.01, 음성 50 프레임·무음 20 프레임·음성 50 프레임, silenceMs 300(필요 30 프레임) → 구간 1개 [0.0, 1.2].
     *       speechMs = silenceMs = 0 이면 RAW 와 같이 구간 2개로 나뉜다(hangover_ref.py 자체 검사와 동일).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun hangoverBridgesShortSilence() {
        val scores = FloatArray(120) { if (it in 50 until 70) 0.0f else 1.0f } // 음성 50 · 무음 20 · 음성 50
        val bridged = SimpleHangover.apply(scores, 0.01, 0.5f, 0, 300) // 300 ms 이상 무음이어야 종료
        assertEquals("구간 1개", 1, bridged.size) // 무음이 메워짐
        assertEquals("시작", 0.0, bridged[0].startSec, 1e-12) // 0.0 s
        assertEquals("끝", 1.2, bridged[0].endSec, 1e-9) // 120 × 0.01 = 1.2 s
        val raw = SimpleHangover.apply(scores, 0.01, 0.5f, 0, 0) // 0/0 = RAW
        assertEquals(listOf(SpeechSegment(0.0, 50 * 0.01), SpeechSegment(70 * 0.01, 120 * 0.01)), raw) // 구간 2개
    }

    /**
     * 목적: 상태기계 경계 규칙 검증
     * 기능: (1) 필요 프레임보다 짧은 발화는 무시 (2) 구간 시작은 연속의 첫 프레임 (3) 종료는 무음 연속의 첫 프레임
     *       (4) 끝에서 진행 중인 무음(필요 길이 미달)은 그 첫 프레임에서 종료 (5) 무음 없이 끝나면 프레임 수 n 에서 종료.
     *       hop 0.02 · speechMs 50(3 프레임) · silenceMs 300(15 프레임) 기준.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun hangoverBoundaryRules() {
        val hop = 0.02 // 20 ms 프레임
        fun s(pattern: String) = FloatArray(pattern.length) { if (pattern[it] == 'T') 1.0f else 0.0f } // 'T' = 1.0, 'F' = 0.0
        assertEquals("2 프레임 발화(3 필요)는 무시", emptyList<SpeechSegment>(), SimpleHangover.apply(s("FFTTFFFF"), hop, 0.5f, 50, 300)) // 구간 없음
        assertEquals("3 프레임 발화는 첫 프레임에서 시작, 끝은 진행 중 무음의 첫 프레임", // 끝까지 무음 5 프레임(15 미달)
            listOf(SpeechSegment(2 * hop, 5 * hop)), SimpleHangover.apply(s("FFTTTFFFFF"), hop, 0.5f, 50, 300)) // [2, 5)
        assertEquals("무음 없이 끝나면 n 에서 종료", listOf(SpeechSegment(1 * hop, 6 * hop)), SimpleHangover.apply(s("FTTTTT"), hop, 0.5f, 50, 300)) // [1, 6)
        val long = "TTT" + "F".repeat(15) + "TTT" // 무음 15 프레임 = 필요 길이 → 두 구간으로 분리
        assertEquals("무음이 필요 길이에 도달하면 무음 첫 프레임에서 종료", listOf(SpeechSegment(0.0, 3 * hop), SpeechSegment(18 * hop, 21 * hop)),
            SimpleHangover.apply(s(long), hop, 0.5f, 50, 300)) // [0,3) [18,21)
    }

    /**
     * 목적: 필요 프레임 수 계산 검증 (hangover_ref.py need_frames)
     * 기능: 300/20=15, 50/20=3, 30/16=2(올림), 0→1(최소 1), 300/16=19(18.75 올림), 소수 9자리 반올림으로 부동소수 오차 제거를 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun needFramesFollowsReference() {
        assertEquals(15, SimpleHangover.needFrames(300, 0.02)) // 300 / 20
        assertEquals(3, SimpleHangover.needFrames(50, 0.02)) // 50 / 20 = 2.5 → 3
        assertEquals(2, SimpleHangover.needFrames(30, 0.016)) // 30 / 16 = 1.875 → 2
        assertEquals(1, SimpleHangover.needFrames(0, 0.016)) // 0 → 최소 1
        assertEquals(19, SimpleHangover.needFrames(300, 0.016)) // 18.75 → 19
        assertEquals(30, SimpleHangover.needFrames(300, 0.01)) // 300 / 10 = 30(0.01 × 1000 이 정확히 10.0 이 아닐 수 있어도 30)
        assertEquals(10, SimpleHangover.needFrames(300, 0.03)) // 300 / 30 = 10
    }
}
