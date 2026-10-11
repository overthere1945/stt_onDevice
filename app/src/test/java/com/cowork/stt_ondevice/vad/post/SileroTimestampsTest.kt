// 파일명: SileroTimestampsTest.kt
// 목적: SileroTimestamps(silero-vad 6.2.3 get_speech_timestamps_from_probs 포팅)가 골든과 샘플 단위로 일치하는지 검증
// 기능: SILERO 계열 골든 전부(8 프리셋 × 4 클립 + 손작성 max_speech 3종)와 TEN_SHERPA 골든 전부(4 클립 + 손작성 3종)를
//       프리셋 파라미터로 다시 계산해 expectedSamples(1 샘플 허용)·expected(초, 1 샘플 허용)와 비교한다.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 테스트 패키지

import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.SileroParams // Silero 후처리 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.assertSegmentsClose // 구간 근사 비교
import kotlinx.serialization.json.JsonNull // JSON null
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값 변환
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시
import kotlin.math.abs // 절댓값

// change(add)-hyungchul-2026-10-10-13:04
/**
 * 목적: Silero식 후처리 포팅의 정합성(골든 일치) 회귀 방지
 * 기능: 골든 파일을 이름 접두어로 모두 찾아(클립 이름 하드코딩 없음) 하나도 빠짐없이 비교한다.
 */
class SileroTimestampsTest {

    private val sampleRate = 16000 // 모든 골든의 샘플레이트(Hz)
    private val tolSamples = 1 // 허용 오차(샘플)
    private val tolSec = tolSamples.toDouble() / sampleRate + 1e-9 // 초 단위 허용 오차(1 샘플 + 부동소수 여유)

    /**
     * 목적: Silero 8 프리셋 전체 골든 일치 검증
     * 기능: family SILERO 골든을 모두 읽어 개수를 파일 수와 대조하고(≥ 8×4+3), 각 골든을 window 512 로 계산해 비교한다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun matchesGoldenForAllSileroPresets() {
        val goldens = GoldenLoader.allFor("SILERO") // SILERO_* 구간 골든 전부
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("SILERO_"), goldens.size) // 빠진 파일 없음
        assertTrue("Silero 골든이 8×4+3 개 미만: ${goldens.size}", goldens.size >= 8 * 4 + 3) // 최소 개수
        assertEquals("Silero 프리셋 8종이 모두 있어야 함", 8, goldens.map { it.presetId }.toSet().size) // 프리셋 누락 없음
        assertTrue("손작성 max_speech 골든 3종 필요", goldens.count { it.clip.startsWith("synthetic_maxspeech_") } >= 3) // 분할 분기 골든 포함
        checkAll(goldens, expectedWindow = 512) // 모두 비교
    }

    /**
     * 목적: TEN_SHERPA 골든 일치 검증(TEN 확률 + hop 256 에 같은 후처리)
     * 기능: family TEN 골든 중 presetId TEN_SHERPA 만 골라 개수를 파일 수와 대조하고(≥ 4+3), window 256 으로 계산해 비교한다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun tenSherpaGolden() {
        val goldens = GoldenLoader.allFor("TEN").filter { it.presetId == "TEN_SHERPA" } // TEN_SHERPA 골든만
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("TEN_TEN_SHERPA_"), goldens.size) // 빠진 파일 없음
        assertTrue("TEN_SHERPA 골든이 4+3 개 미만: ${goldens.size}", goldens.size >= 4 + 3) // 최소 개수
        checkAll(goldens, expectedWindow = 256) // 모두 비교
    }

    /**
     * 목적: 골든 목록 일괄 비교
     * 기능: 각 골든에 대해 프리셋 파라미터 확인 → apply 실행 → 샘플·초 비교를 하고, 실패를 모아 한 번에 보고한다.
     * 입력: goldens(비교할 골든), expectedWindow(이 계열 창 크기 샘플)
     * 출력: 없음(실패가 하나라도 있으면 AssertionError)
     * 리턴: 없음
     */
    private fun checkAll(goldens: List<Golden>, expectedWindow: Int) {
        val failures = mutableListOf<String>() // 실패 메시지 모음
        for (g in goldens) { // 골든마다
            try { // 한 골든 실패가 나머지 비교를 막지 않도록
                checkOne(g, expectedWindow) // 개별 비교
            } catch (e: AssertionError) { // 불일치
                failures += "${g.name}: ${e.message}" // 기록
            }
        }
        assertTrue("${failures.size}/${goldens.size} 개 불일치\n" + failures.joinToString("\n"), failures.isEmpty()) // 전부 통과해야 함
    }

    /**
     * 목적: 골든 1건 비교
     * 기능: (1) JSON 의 windowSamples·totalSamples 확인 (2) 프리셋 파라미터가 JSON params 와 같은지 확인
     *       (3) apply(점수, 창, 16000, totalSamples(JSON 값), 프리셋 파라미터) 실행 (4) expectedSamples 와 1 샘플 허용 비교
     *       (5) expected(초)와 assertSegmentsClose(1 샘플) 비교.
     * 입력: g(골든), expectedWindow(창 크기 샘플)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun checkOne(g: Golden, expectedWindow: Int) {
        assertEquals("windowSamples", expectedWindow, g.windowSamples) // 창 크기 기록 확인
        val total = g.totalSamples ?: throw AssertionError("totalSamples 없음") // N 은 반드시 JSON 값(프레임 수 × 창으로 추정 금지)
        val p = VadPresets.byId(g.presetId).params as SileroParams // 프리셋 파라미터(앱이 실제로 쓸 값)
        assertParamsMatchJson(g, p) // 골든 생성에 쓴 값과 같은지
        val actual = SileroTimestamps.apply(g.frameScores, expectedWindow, sampleRate, total, p) // 포팅 실행
        val expSamples = g.expectedSamples ?: throw AssertionError("expectedSamples 없음") // 정수 샘플 기대값
        val actSamples = actual.map { s -> // 초 → 샘플 복원(apply 가 샘플/16000 으로 만들었으므로 반올림으로 정확히 복원)
            intArrayOf(Math.round(s.startSec * sampleRate).toInt(), Math.round(s.endSec * sampleRate).toInt()) // [start, end]
        }
        val expText = expSamples.joinToString { "[${it[0]}, ${it[1]}]" } // 메시지용 기대 샘플
        val actText = actSamples.joinToString { "[${it[0]}, ${it[1]}]" } // 메시지용 실제 샘플
        assertEquals("구간 개수\n기대=$expText\n실제=$actText", expSamples.size, actSamples.size) // 개수 동일
        for (i in expSamples.indices) { // 구간별
            val ok = abs(expSamples[i][0] - actSamples[i][0]) <= tolSamples && // 시작 1 샘플 이내
                abs(expSamples[i][1] - actSamples[i][1]) <= tolSamples // 끝 1 샘플 이내
            assertTrue("구간[$i] 샘플 불일치\n기대=$expText\n실제=$actText", ok) // 경계 일치
        }
        assertSegmentsClose(g.expected, actual, tolSec) // 초 단위 기대값과도 일치
        actual.forEach { assertTrue("구간 끝이 N 초과: $it", it.endSec <= total.toDouble() / sampleRate) } // 끝은 N 으로 clamp
    }

    /**
     * 목적: 프리셋 값 = 골든 생성 값 확인
     * 기능: JSON params(threshold, minSpeechMs, minSilenceMs, padMs, maxSpeechSec(null=무제한))와 프리셋 SileroParams 를 비교한다.
     *       threshold 는 Float 의 10진 표기(0.3f → "0.3")를 double 로 읽어 비교한다(Python 이 쓴 값과 같은 기준).
     * 입력: g(골든), p(프리셋 파라미터)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun assertParamsMatchJson(g: Golden, p: SileroParams) {
        val j = g.params ?: return // params 키가 없으면 비교 생략(현재 골든은 모두 있음)
        fun d(key: String): Double? = j[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble() // 숫자 키 → double
        assertEquals("threshold", d("threshold"), p.threshold.toString().toDouble()) // 임계값
        assertEquals("minSpeechMs", d("minSpeechMs"), p.minSpeechMs.toDouble()) // 최소 발화
        assertEquals("minSilenceMs", d("minSilenceMs"), p.minSilenceMs.toDouble()) // 최소 무음
        assertEquals("padMs", d("padMs"), p.speechPadMs.toDouble()) // 패딩
        assertEquals("maxSpeechSec", d("maxSpeechSec") ?: Double.POSITIVE_INFINITY, p.maxSpeechSec, 0.0) // 최대 발화(null = 무한대)
    }

    /**
     * 목적: 손작성 경계 케이스(골든 외) — 빈 입력·전부 음성
     * 기능: 확률이 없으면 구간 0개, 전부 0.9 면 [0, N] 1개(마지막 구간 끝 = totalSamples, 패딩 clamp)인지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun edgeCases() {
        val p = SileroParams(0.5f, 250, 100, 30) // SILERO_DEFAULT 값
        assertEquals(emptyList<SpeechSegment>(), SileroTimestamps.apply(FloatArray(0), 512, 16000, 0, p)) // 빈 입력 → 0개
        val all = SileroTimestamps.apply(FloatArray(10) { 0.9f }, 512, 16000, 5000, p) // 10 창 전부 음성, N = 5000(< 10×512)
        assertEquals(listOf(SpeechSegment(0.0, 5000.0 / 16000)), all) // 시작 0(패딩 하한 0), 끝 = N(clamp)
    }

    /**
     * 목적: 골든(실제 프리셋)으로는 실행되지 않는 분기 검증
     * 기능: (1) 이웃 무음 < 2·pad 일 때 무음 반분(L566–568) — 어떤 프리셋도 minSilence < 2·pad 가 아니라 골든에서는 안 나옴
     *       (2) use_max_poss_sil_at_max_speech=false 기존 절단 경로(L515–534).
     *       기대값은 silero-vad 6.2.3 get_speech_timestamps_from_probs 를 tools/golden/.venv 에서 같은 입력으로 직접 실행한 결과(샘플)다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun matchesUpstreamOnHandCases() {
        fun probs(vararg runs: Pair<Float, Int>): FloatArray = runs.flatMap { (v, n) -> List(n) { v } }.toFloatArray() // (값, 반복 수) → 확률 배열
        fun sec(vararg s: Int): List<SpeechSegment> = s.toList().chunked(2).map { SpeechSegment(it[0] / 16000.0, it[1] / 16000.0) } // 샘플 쌍 → 초 구간
        // (1) 반분: th 0.5, minSpeech 0, minSilence 50 ms, pad 100 ms, 무제한 → 원본 결과 [0, 7424] [7424, 14912]
        val p1 = probs(0.02f to 3, 0.9f to 10, 0.1f to 3, 0.9f to 10, 0.02f to 5) // 31 창
        assertEquals(sec(0, 7424, 7424, 14912), SileroTimestamps.apply(p1, 512, 16000, 31 * 512 + 100, SileroParams(0.5f, 0, 50, 100))) // 반분 일치
        // (2) max 1 s, minSilence 500 ms, pad 0, N = 60·512+7 → 원본 결과(두 설정 모두) [1024, 11264] [13824, 29696]
        val p2 = probs(0.02f to 2, 0.9f to 20, 0.1f to 5, 0.9f to 30, 0.02f to 3) // 60 창
        val params2 = SileroParams(0.5f, 0, 500, 0, 1.0) // 최대 발화 1 초
        val exp2 = sec(1024, 11264, 13824, 29696) // 원본 결과
        assertEquals(exp2, SileroTimestamps.apply(p2, 512, 16000, 60 * 512 + 7, params2)) // 기본(가장 긴 무음 절단)
        assertEquals(exp2, SileroTimestamps.apply(p2, 512, 16000, 60 * 512 + 7, params2, useMaxPossSilAtMaxSpeech = false)) // 기존 경로(prev_end 절단 + 강제 절단)
    }
}
