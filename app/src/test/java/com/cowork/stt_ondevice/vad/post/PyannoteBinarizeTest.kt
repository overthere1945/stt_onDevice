// 파일명: PyannoteBinarizeTest.kt
// 목적: PyannoteBinarize(pyannote.audio 3.4.0 Binarize 포팅)가 골든과 일치하는지 검증
// 기능: PYANNOTE_* 골든 전부(4 프리셋 × 4 클립 = 16)를 JSON 의 frameScores·frameTimesSec 와 프리셋 파라미터로 다시 계산해
//       expected(초)와 1e-6 s 이내로 비교하고, 골든으로는 실행되지 않는 분기를 원본을 직접 실행해 얻은 손작성 기대값으로 검증한다.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 테스트 패키지

import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.PyannoteParams // pyannote 후처리 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.assertSegmentsClose // 구간 근사 비교
import kotlinx.serialization.json.JsonNull // JSON null
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값 변환
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시
import kotlin.math.abs // 절댓값

// change(add)-hyungchul-2026-10-10-13:25
/**
 * 목적: pyannote Binarize 포팅의 정합성(골든 일치) 회귀 방지
 * 기능: 골든 파일을 접두어 PYANNOTE_ 로 모두 찾아(클립 이름 하드코딩 없음) 하나도 빠짐없이 비교한다.
 */
class PyannoteBinarizeTest {

    private val tolSec = 1e-6 // 허용 오차(초): 정확 일치가 기대값이며 부동소수 잡음만 허용(계획서 최대 허용 1 프레임 = 0.016875 s 보다 엄격)
    private val hop = 0.016875 // 프레임 간격(초) = 270/16000
    private val dur = 0.0619375 // 프레임 길이(초) = 991/16000

    /**
     * 목적: pyannote 4 프리셋 × 4 클립 골든 일치 검증
     * 기능: PYANNOTE_* 골든을 모두 읽어 개수를 파일 수·16 과 대조하고, 각 골든을 apply 로 다시 계산해 비교한다.
     *       전체 골든에서의 최대 경계 차이도 1e-6 s 이하인지 확인한다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun matchesGoldenPyannote() {
        val goldens = GoldenLoader.allFor("PYANNOTE") // PYANNOTE_* 구간 골든 전부
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("PYANNOTE_"), goldens.size) // 빠진 파일 없음
        assertEquals("pyannote 골든은 4 프리셋 × 4 클립 = 16 개여야 함", 16, goldens.size) // 발견 개수 고정(R14)
        assertEquals("pyannote 프리셋 4종이 모두 있어야 함", 4, goldens.map { it.presetId }.toSet().size) // 프리셋 누락 없음
        assertEquals("클립 4종이 모두 있어야 함", 4, goldens.map { it.clip }.toSet().size) // 클립 누락 없음
        val failures = mutableListOf<String>() // 실패 메시지 모음
        var maxDev = 0.0 // 전체 골든 최대 경계 차이(초)
        for (g in goldens) { // 골든마다
            try { // 한 골든 실패가 나머지 비교를 막지 않도록
                maxDev = maxOf(maxDev, checkOne(g)) // 개별 비교 후 최대 차이 갱신
            } catch (e: AssertionError) { // 불일치
                failures += "${g.name}: ${e.message}" // 기록
            }
        }
        assertTrue("${failures.size}/${goldens.size} 개 불일치\n" + failures.joinToString("\n"), failures.isEmpty()) // 전부 통과해야 함
        assertTrue("최대 경계 차이 $maxDev s > $tolSec s", maxDev <= tolSec) // 최대 차이 확인
    }

    /**
     * 목적: 골든 1건 비교
     * 기능: (1) 프리셋 파라미터가 JSON params 와 같은지 확인 (2) frameTimesSec 존재·길이 확인
     *       (3) apply(점수, 프레임 시각(JSON 값 그대로), 프리셋 파라미터) 실행 (4) expected 와 1e-6 s 이내 비교.
     * 입력: g(골든)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 이 골든에서의 최대 경계 차이(초)
     */
    private fun checkOne(g: Golden): Double {
        val p = VadPresets.byId(g.presetId).params as PyannoteParams // 프리셋 파라미터(앱이 실제로 쓸 값)
        assertParamsMatchJson(g, p) // 골든 생성에 쓴 값과 같은지
        val times = g.frameTimesSec // 프레임 중앙 시각(JSON)
        assertNotNull("frameTimesSec 없음", times) // pyannote 골든은 반드시 있음
        assertEquals("frameTimesSec 길이 ≠ frameScores 길이", g.frameScores.size, times!!.size) // 길이 일치
        val actual = PyannoteBinarize.apply(g.frameScores, times, p) // 포팅 실행
        assertSegmentsClose(g.expected, actual, tolSec) // 개수·경계 비교
        return g.expected.indices.maxOfOrNull { i -> // 구간별 최대 차이
            maxOf(abs(g.expected[i].startSec - actual[i].startSec), abs(g.expected[i].endSec - actual[i].endSec)) // 시작·끝 중 큰 차이
        } ?: 0.0 // 구간이 없으면 0
    }

    /**
     * 목적: 프리셋 값 = 골든 생성 값 확인
     * 기능: JSON params(onset, offset, minDurationOn, minDurationOff)와 프리셋 PyannoteParams 를 비교한다.
     *       onset/offset 은 Float 의 10진 표기(0.767f → "0.767")를 double 로 읽어 Python 값과 같은 기준으로 비교한다.
     * 입력: g(골든), p(프리셋 파라미터)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun assertParamsMatchJson(g: Golden, p: PyannoteParams) {
        val j = g.params ?: throw AssertionError("params 없음") // pyannote 골든은 모두 params 가 있음
        fun d(key: String): Double? = j[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble() // 숫자 키 → double
        assertEquals("onset", d("onset"), p.onset.toString().toDouble()) // 시작 임계값
        assertEquals("offset", d("offset"), p.offset.toString().toDouble()) // 종료 임계값
        assertEquals("minDurationOn", d("minDurationOn"), p.minDurationOnSec) // 최소 발화
        assertEquals("minDurationOff", d("minDurationOff"), p.minDurationOffSec) // 최소 무음
    }

    /**
     * 목적: 손작성 입력용 프레임 시각 생성
     * 기능: pyannote SlidingWindow(start 0, step 0.016875, duration 0.0619375)의 i 번째 창 중앙
     *       `0.5 * (start + (start + duration))`, start = 0 + i·step 을 같은 연산 순서로 계산한다.
     * 입력: n(프레임 수)
     * 출력: 없음
     * 리턴: 프레임 중앙 시각 배열(초)
     */
    private fun times(n: Int): DoubleArray = DoubleArray(n) { i ->
        val start = 0.0 + i * hop // 창 시작(SlidingWindow.__getitem__)
        0.5 * (start + (start + dur)) // Segment.middle
    }

    /**
     * 목적: 손작성 케이스 실행 도우미
     * 기능: 점수 배열과 파라미터로 apply 를 돌린다.
     * 입력: scores(점수), onset, offset, minOn(초), minOff(초)
     * 출력: 없음
     * 리턴: 구간 목록
     */
    private fun run(scores: FloatArray, onset: Float, offset: Float, minOn: Double, minOff: Double): List<SpeechSegment> =
        PyannoteBinarize.apply(scores, times(scores.size), PyannoteParams(onset, offset, minOn, minOff)) // 포팅 실행

    /**
     * 목적: 프레임 인덱스 쌍 → 기대 구간(초)
     * 기능: times() 의 같은 시각으로 [시작 프레임 시각, 끝 프레임 시각] 구간을 만든다.
     * 입력: n(프레임 수), idx(시작·끝 프레임 인덱스를 번갈아 나열)
     * 출력: 없음
     * 리턴: 구간 목록
     */
    private fun seg(n: Int, vararg idx: Int): List<SpeechSegment> {
        val t = times(n) // 프레임 시각
        return idx.toList().chunked(2).map { SpeechSegment(t[it[0]], t[it[1]]) } // 인덱스 쌍 → 구간
    }

    /**
     * 목적: 골든으로는 실행되지 않는 분기 검증
     * 기능: 기대값은 tools/golden/.venv 의 pyannote.audio 3.4.0 Binarize 를 같은 입력(float32 점수,
     *       SlidingWindow(0, 0.0619375, 0.016875))으로 직접 실행한 결과(프레임 인덱스)다.
     *       (1) 마지막 프레임에서만 활성화 → 길이 0 구간이라 Annotation 이 버림(결과 없음)
     *       (2) 끝까지 활성 → [시작, 마지막 프레임 시각]
     *       (3) 점수 = onset/offset 은 상태를 바꾸지 않음(엄격 비교 `>`/`<`)
     *       (4) offset 0 → `offset or onset` 으로 onset 사용
     *       (5) onset/offset 히스테리시스(PYA2_DIHARD3 값)
     *       (6) min_duration_off 간격 병합(간격 < collar 만)
     *       (7) 병합 후 min_duration_on 삭제 순서(병합 전이면 지워질 짧은 구간이 병합되어 살아남음 / 0.06 이면 삭제)
     *       (8) 1 프레임 입력(원본은 UnboundLocalError, 포팅은 길이 0 구간으로 보고 결과 없음)·빈 입력
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun matchesUpstreamOnHandCases() {
        fun f(vararg v: Float): FloatArray = v // 가독용 FloatArray 생성
        assertEquals(emptyList<SpeechSegment>(), run(f(0.1f, 0.1f, 0.1f, 0.9f), 0.5f, 0.5f, 0.0, 0.0)) // (1) 원본 결과 []
        assertEquals(seg(4, 1, 3), run(f(0.1f, 0.9f, 0.9f, 0.9f), 0.5f, 0.5f, 0.0, 0.0)) // (2) 원본 (1, 3)
        assertEquals(seg(6, 2, 4), run(f(0.5f, 0.5f, 0.9f, 0.5f, 0.4f, 0.1f), 0.5f, 0.5f, 0.0, 0.0)) // (3) 원본 (2, 4)
        assertEquals(seg(4, 0, 1, 2, 3), run(f(0.9f, 0.3f, 0.6f, 0.2f), 0.5f, 0.0f, 0.0, 0.0)) // (4) 원본 (0,1)(2,3)
        assertEquals(seg(8, 1, 4), run(f(0.1f, 0.8f, 0.6f, 0.45f, 0.3f, 0.7f, 0.6f, 0.1f), 0.767f, 0.377f, 0.0, 0.0)) // (5) 원본 (1, 4)
        val merge = f(0.9f, 0.9f, 0.1f, 0.1f, 0.9f, 0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.9f, 0.9f, 0.1f) // 간격 2 프레임·4 프레임
        assertEquals(seg(13, 0, 6, 10, 12), run(merge, 0.5f, 0.5f, 0.0, 0.04)) // (6) 원본 (0,6)(10,12)
        val order = f(0.9f, 0.1f, 0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.1f) // 짧은 구간 2개 + 긴 구간
        assertEquals(seg(13, 0, 3, 7, 12), run(order, 0.5f, 0.5f, 0.05, 0.02)) // (7) 원본 (0,3)(7,12)
        assertEquals(seg(13, 7, 12), run(order, 0.5f, 0.5f, 0.06, 0.02)) // (7) 원본 (7,12)
        assertEquals(emptyList<SpeechSegment>(), run(f(0.9f), 0.5f, 0.5f, 0.0, 0.0)) // (8) 1 프레임 활성
        assertEquals(emptyList<SpeechSegment>(), run(f(0.1f), 0.5f, 0.5f, 0.0, 0.0)) // (8) 1 프레임 비활성(원본 [])
        assertEquals(emptyList<SpeechSegment>(), run(FloatArray(0), 0.5f, 0.5f, 0.0, 0.0)) // (8) 빈 입력
    }
}
