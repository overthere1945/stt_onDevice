// 파일명: FireRedNemoPostTest.kt (테스트 소스)
// 목적: FireRedPostprocess(FireRedVAD offline/stream 후처리)·NemoPostprocess(NeMo vad_utils 후처리) 포팅이 원본 Python 과 정확히 같은지 검증
// 기능: (1) 골든 FIRERED_FIRERED_OFFLINE_*·FIRERED_FIRERED_STREAM_*(클립 4 + 손작성 max_speech 1 씩, 총 10 개)의 frameScores 를 넣어
//           프레임 구간(expectedFrames)과 초 구간(expected)이 **정확히**(double 동일) 같은지 확인한다.
//       (2) 골든 MARBLENET_MARBLENET_NEMO_*(4 개)의 binarized·filtered·expected 가 float32 값으로 정확히 같은지 확인한다.
//       (3) 프리셋 값으로는 타지 않는 분기(offline merge/extend·홀수 max_speech 분할·상태기계 생략, stream max_speech 연속 분할,
//           NeMo filter_speech_first false·전부 제거)를 원본 Python 실행값(함수 주석에 출처)과 비교한다.
//       골든은 음원 없이 점수만 쓰므로 건너뛰는 테스트가 없다.
package com.cowork.stt_ondevice.vad.post // 후처리 테스트 패키지

import com.cowork.stt_ondevice.vad.FireRedMode // FireRed 모드
import com.cowork.stt_ondevice.vad.FireRedParams // FireRed 파라미터
import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.NemoParams // NeMo 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import kotlinx.serialization.json.JsonArray // JSON 배열
import kotlinx.serialization.json.jsonArray // 요소 → 배열
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: FireRed·NeMo 후처리 골든 정합 테스트
 * 기능: 골든 전수 비교와 손으로 만든 분기 입력 비교를 한다. 실제 최대 차이를 출력해 보고서에 옮긴다.
 */
class FireRedNemoPostTest {

    // ---------------------------------------------------------------- FireRed OFFLINE

    /**
     * 목적: FIRERED_OFFLINE 후처리가 공식 fireredvad VadPostprocessor + decision_to_segment(wav_dur) 와 정확히 같은지 검증
     * 기능: FIRERED_FIRERED_OFFLINE_* 골든 5 개(실제 클립 4 + 손작성 max_speech 1)를 모두 읽어
     *       최종 결정의 연속 1 구간 [시작, 끝) 이 expectedFrames 와, apply(durSec = N/16000) 초 구간이 expected 와 double 로 같은지 본다.
     *       골든 params 가 프리셋 값과 같은지도 확인한다.
     * 입력: 없음
     * 출력: 골든별 구간 수·최대 차이 출력
     * 리턴: 없음
     */
    @Test
    fun fireRedOfflineMatchesAllGoldens() {
        val preset = VadPresets.byId("FIRERED_OFFLINE") // 비교 프리셋
        val p = preset.params as FireRedParams // FireRed 파라미터
        val goldens = GoldenLoader.allForPreset("FIRERED", "FIRERED_OFFLINE") // 골든 전부
        assertEquals("FIRERED_OFFLINE 골든 수", GoldenLoader.countFiles("FIRERED_FIRERED_OFFLINE_"), goldens.size) // 빠짐 없음
        assertEquals("FIRERED_OFFLINE 골든 수(클립 4 + 손작성 1)", 5, goldens.size) // 기대 개수
        assertTrue("손작성 max_speech 골든 포함", goldens.any { it.clip == "synthetic_firered_long" }) // 분할 분기 골든 포함
        for (g in goldens) { // 골든마다
            checkOfflineParams(g, p) // 골든 생성 파라미터 = 프리셋
            val decisions = FireRedPostprocess.offlineDecisions(g.frameScores, p) // 최종 결정(0/1)
            assertEquals("${g.name} 결정 길이 = 프레임 수", g.frameScores.size, decisions.size) // 길이 보존
            val frames = runsOf(decisions) // 연속 1 구간
            assertFramesEqual(g.name, g.expectedFrames!!, frames) // 프레임 구간 정확 일치
            val segs = FireRedPostprocess.apply(g.frameScores, p, g.totalSamples!! / 16000.0) // 초 구간(dur = N/16000)
            assertEquals("${g.name} 초 구간(정확 일치)", g.expected, segs) // double 정확 일치
            println("FIRERED_OFFLINE ${g.name}: 구간 ${segs.size} 개, 최대 경계 차이 = ${maxDiff(g.expected, segs)} s") // 보고용
        }
    }

    /**
     * 목적: 프리셋에서는 0 이라 골든이 덮지 않는 offline merge_silence·extend_speech 분기와 dur 절단 검증
     * 기능: 원본 VadPostprocessor(smooth 1, thr 0.5, min_speech 2, max 1000, min_silence 2, merge 3, extend 1)에
     *       [0]*5+[1]*6+[0]*2+[1]*5+[0]*6+[1]*4+[0]*3 을 넣은 결과(tools/export/.venv, fireredvad c30ec49 실행값):
     *       결정 0001111111111111111110111111111, decision_to_segment(dur 0.31) = [(0.03, 0.21), (0.22, 0.31)].
     * 입력: 없음
     * 출력: 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun fireRedOfflineMergeExtendBranch() {
        val p = FireRedParams(FireRedMode.OFFLINE, 0.5f, 1, 2, 1000, 2, 3, 1, 0) // 손 입력 파라미터
        val probs = floats(List(5) { 0f } + List(6) { 1f } + List(2) { 0f } + List(5) { 1f } + List(6) { 0f } + List(4) { 1f } + List(3) { 0f }) // 31 프레임
        assertEquals("0001111111111111111110111111111", FireRedPostprocess.offlineDecisions(probs, p).joinToString("")) // 원본 결정
        assertEquals(listOf(SpeechSegment(0.03, 0.21), SpeechSegment(0.22, 0.31)), FireRedPostprocess.apply(probs, p, 0.31)) // 원본 구간(끝 dur 절단)
    }

    /**
     * 목적: 홀수 max_speech(9 → 창 시작 int(start + 4.5))의 긴 구간 분할 분기 검증
     * 기능: 원본 VadPostprocessor(1, 0.5, 1, 9, 1, 0, 0)에 [0,0, 0.75,0.9,0.8,0.7,0.6,0.55,0.65,0.85,0.95,0.6,0.7,0.75,0.8,0.9,0.95,0.99,
     *       0.6,0.7,0.8,0.9,0.75,0.8, 0,0,0](float32 값) 을 넣은 결과(fireredvad c30ec49 실행값):
     *       결정 011111101111011111011111100, decision_to_segment(None) = [(0.01,0.07),(0.08,0.12),(0.13,0.18),(0.19,0.25)].
     * 입력: 없음
     * 출력: 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun fireRedOfflineOddMaxSpeechSplit() {
        val p = FireRedParams(FireRedMode.OFFLINE, 0.5f, 1, 1, 9, 1, 0, 0, 0) // 손 입력 파라미터
        val probs = floats(listOf(0f, 0f, 0.75f, 0.9f, 0.8f, 0.7f, 0.6f, 0.55f, 0.65f, 0.85f, 0.95f, 0.6f, 0.7f, 0.75f, 0.8f, 0.9f, 0.95f, 0.99f, 0.6f, 0.7f, 0.8f, 0.9f, 0.75f, 0.8f, 0f, 0f, 0f)) // 27 프레임
        assertEquals("011111101111011111011111100", FireRedPostprocess.offlineDecisions(probs, p).joinToString("")) // 원본 결정
        val expected = listOf(SpeechSegment(0.01, 0.07), SpeechSegment(0.08, 0.12), SpeechSegment(0.13, 0.18), SpeechSegment(0.19, 0.25)) // 원본 구간
        assertEquals(expected, FireRedPostprocess.apply(probs, p)) // dur 없음
    }

    /**
     * 목적: min_speech·min_silence 가 모두 0 이면 상태기계를 건너뛰는 분기와 창 3 평활 검증
     * 기능: 원본 VadPostprocessor(3, 0.5, 0, 1000, 0, 0, 0)에 [0.1,0.9,0.2,0.8,0.9,0.1,0.0,0.6,0.7,0.2,0.9,0.9,0.9,0.1,0.0] 을 넣은 결과
     *       (fireredvad c30ec49 실행값): 결정 111111111111110, decision_to_segment(dur 0.2) = [(0.0, 0.14)].
     * 입력: 없음
     * 출력: 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun fireRedOfflineSkipsStateMachine() {
        val p = FireRedParams(FireRedMode.OFFLINE, 0.5f, 3, 0, 1000, 0, 0, 0, 0) // 손 입력 파라미터
        val probs = floats(listOf(0.1f, 0.9f, 0.2f, 0.8f, 0.9f, 0.1f, 0f, 0.6f, 0.7f, 0.2f, 0.9f, 0.9f, 0.9f, 0.1f, 0f)) // 15 프레임
        assertEquals("111111111111110", FireRedPostprocess.offlineDecisions(probs, p).joinToString("")) // 원본 결정
        assertEquals(listOf(SpeechSegment(0.0, 0.14)), FireRedPostprocess.apply(probs, p, 0.2)) // 원본 구간
        assertEquals(emptyList<SpeechSegment>(), FireRedPostprocess.apply(FloatArray(0), p, 0.0)) // 빈 입력 → 빈 결과(원본 process([]) = [])
    }

    // ---------------------------------------------------------------- FireRed STREAM

    /**
     * 목적: FIRERED_STREAM 후처리가 공식 StreamVadPostprocessor.process_one_frame + FireRedStreamVad.results_to_timestamps 와 같은지 검증
     * 기능: FIRERED_FIRERED_STREAM_* 골든 5 개(실제 클립 4 + 손작성 max_speech 1)를 모두 읽어
     *       0-based (시작, 끝) 프레임 쌍이 expectedFrames 와, 초 구간(프레임/100)이 expected 와 정확히 같은지 본다.
     * 입력: 없음
     * 출력: 골든별 구간 수·최대 차이 출력
     * 리턴: 없음
     */
    @Test
    fun fireRedStreamMatchesAllGoldens() {
        val preset = VadPresets.byId("FIRERED_STREAM") // 비교 프리셋
        val p = preset.params as FireRedParams // FireRed 파라미터
        val goldens = GoldenLoader.allForPreset("FIRERED", "FIRERED_STREAM") // 골든 전부
        assertEquals("FIRERED_STREAM 골든 수", GoldenLoader.countFiles("FIRERED_FIRERED_STREAM_"), goldens.size) // 빠짐 없음
        assertEquals("FIRERED_STREAM 골든 수(클립 4 + 손작성 1)", 5, goldens.size) // 기대 개수
        assertTrue("손작성 max_speech 골든 포함", goldens.any { it.clip == "synthetic_firered_long" }) // 분할 분기 골든 포함
        assertEquals("FIRERED 전체 골든 수 = OFFLINE + STREAM", GoldenLoader.countFiles("FIRERED_"), 10) // 계열 전체 10 개
        for (g in goldens) { // 골든마다
            checkStreamParams(g, p) // 골든 생성 파라미터 = 프리셋
            val frames = FireRedPostprocess.streamFrames(g.frameScores, p) // 프레임 쌍
            assertFramesEqual(g.name, g.expectedFrames!!, frames) // 정확 일치
            val segs = FireRedPostprocess.apply(g.frameScores, p, g.totalSamples!! / 16000.0) // 초 구간(stream 은 dur 미사용)
            assertEquals("${g.name} 초 구간(정확 일치)", g.expected, segs) // double 정확 일치
            println("FIRERED_STREAM ${g.name}: 구간 ${segs.size} 개, 최대 경계 차이 = ${maxDiff(g.expected, segs)} s") // 보고용
        }
    }

    /**
     * 목적: stream 의 max_speech 강제 분할이 연달아 일어나는 분기·pad_start 하한(max(창, pad)) 검증
     * 기능: 원본 StreamVadPostprocessor(smooth 3, thr 0.5, pad_start 2 → 3, min_speech 2, max_speech 6, min_silence 3)에 30 프레임을 넣은 결과
     *       (fireredvad c30ec49 실행값): 이벤트 (4 시작 1), (8 끝), (9 시작 9), (16 끝), (20 시작 17), (24 끝), (25 시작 25), (28 끝),
     *       results_to_timestamps = [(0.0,0.07),(0.08,0.15),(0.16,0.23),(0.24,0.27)].
     * 입력: 없음
     * 출력: 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun fireRedStreamRepeatedMaxSpeechSplit() {
        val p = FireRedParams(FireRedMode.STREAM, 0.5f, 3, 2, 6, 3, 0, 0, 2) // 손 입력 파라미터
        val probs = floats(listOf(0.1f, 0.9f, 0.9f, 0.8f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.1f, 0f, 0f, 0f, 0f, 0.9f, 0.9f, 0f, 0.9f, 0.9f, 0.9f, 0.9f, 0f, 0f, 0f, 0f, 0f, 0.9f)) // 30 프레임
        val frames = FireRedPostprocess.streamFrames(probs, p) // 프레임 쌍
        assertFramesEqual("stream 손 입력", listOf(intArrayOf(0, 7), intArrayOf(8, 15), intArrayOf(16, 23), intArrayOf(24, 27)), frames) // 원본 프레임
        val expected = listOf(SpeechSegment(0.0, 0.07), SpeechSegment(0.08, 0.15), SpeechSegment(0.16, 0.23), SpeechSegment(0.24, 0.27)) // 원본 초
        assertEquals(expected, FireRedPostprocess.apply(probs, p)) // 정확 일치
    }

    /**
     * 목적: 모드에 쓰이지 않는 파라미터가 0 이 아니면 조용히 무시하지 않고 거부하는지 검증
     * 기능: OFFLINE 에 padStartFrames ≠ 0, STREAM 에 mergeSilenceFrames·extendFrames ≠ 0 이면 IllegalArgumentException.
     * 입력: 없음
     * 출력: 예외가 없으면 AssertionError
     * 리턴: 없음
     */
    @Test
    fun fireRedRejectsUnusedParams() {
        val probs = FloatArray(10) // 10 프레임 0
        assertThrows(IllegalArgumentException::class.java) { FireRedPostprocess.apply(probs, FireRedParams(FireRedMode.OFFLINE, 0.4f, 5, 20, 2000, 20, 0, 0, 5)) } // offline pad
        assertThrows(IllegalArgumentException::class.java) { FireRedPostprocess.apply(probs, FireRedParams(FireRedMode.STREAM, 0.5f, 5, 8, 2000, 20, 3, 0, 5)) } // stream merge
        assertThrows(IllegalArgumentException::class.java) { FireRedPostprocess.apply(probs, FireRedParams(FireRedMode.STREAM, 0.5f, 5, 8, 2000, 20, 0, 2, 5)) } // stream extend
    }

    // ---------------------------------------------------------------- NeMo (MarbleNet)

    /**
     * 목적: MARBLENET_NEMO 후처리가 NeMo 3.0.0 vad_utils(binarization → filtering → torch.sort) 와 float32 로 정확히 같은지 검증
     * 기능: MARBLENET_MARBLENET_NEMO_* 골든 4 개를 모두 읽어 binarize·filter 중간 결과와 최종 apply 결과(float32 값)를 비교한다.
     *       hop 은 골든 params.frame_length_in_sec(0.02), 파라미터 값이 프리셋과 같은지도 확인한다.
     * 입력: 없음
     * 출력: 골든별 구간 수 출력
     * 리턴: 없음
     */
    @Test
    fun nemoMatchesAllGoldens() {
        val p = VadPresets.byId("MARBLENET_NEMO").params as NemoParams // 프리셋 파라미터
        val goldens = GoldenLoader.allFor("MARBLENET") // 골든 전부
        assertEquals("MARBLENET 골든 수", GoldenLoader.countFiles("MARBLENET_"), goldens.size) // 빠짐 없음
        assertEquals("MARBLENET 골든 수(클립 4)", 4, goldens.size) // 기대 개수
        for (g in goldens) { // 골든마다
            assertEquals("${g.name} presetId", "MARBLENET_NEMO", g.presetId) // 프리셋
            val raw = GoldenLoader.loadRaw(g.name) // 원문(binarized·filtered)
            val hop = g.params!!["frame_length_in_sec"]!!.jsonPrimitive.content.toDouble() // hop 0.02
            assertEquals("${g.name} hop", 0.02, hop, 0.0) // 골든 hop
            checkNemoParams(g, p) // 골든 생성 파라미터 = 프리셋
            val bin = NemoPostprocess.binarize(g.frameScores, hop, p) // 이진화 + 병합
            assertFloatSegs("${g.name} binarized", raw["binarized"]!!.jsonArray, bin) // 중간 결과 1
            val filt = NemoPostprocess.filter(bin, p) // 필터링
            assertFloatSegs("${g.name} filtered", raw["filtered"]!!.jsonArray, filt) // 중간 결과 2
            val segs = NemoPostprocess.apply(g.frameScores, hop, p) // 최종
            assertEquals("${g.name} 구간 수", g.expected.size, segs.size) // 개수
            for (i in segs.indices) { // 구간마다 float32 값 정확 일치
                assertEquals("${g.name}[$i] start", g.expected[i].startSec.toFloat(), segs[i].startSec.toFloat(), 0f) // 시작
                assertEquals("${g.name}[$i] end", g.expected[i].endSec.toFloat(), segs[i].endSec.toFloat(), 0f) // 끝
                assertEquals("${g.name}[$i] start 는 float32 값", segs[i].startSec, segs[i].startSec.toFloat().toDouble(), 0.0) // NeMo float32 텐서 값 그대로
            }
            println("MARBLENET_NEMO ${g.name}: 구간 ${segs.size} 개 $segs (float32 정확 일치)") // 보고용
        }
    }

    /**
     * 목적: filter_speech_first true/false 두 분기와 전부 제거·무음 입력 분기 검증
     * 기능: 원본 NeMo 3.0.0 vad_utils(tools/export/.venv-nemo 실행값)와 비교한다. 입력 E = [0.1,0.6,0.7,0.2,0.1,0.6,0.1,0.1,0.1,0.1,0.8,0.9,0.9,0.9,0.2,0.4,
     *       0.9,0.9,0.1,0.1,0.1,0.1,0.1,0.1,0.7], onset 0.5, offset 0.3, pad 0.04/0.02, min_on 0.1, min_off 0.08, hop 0.02:
     *       binarization = [[0,0.14],[0.16,0.38],[0.44,0.5]], false → [[0,0.5]], true → [[0,0.38]].
     *       무음 [0.1]*10 → 빈 결과. H = [0.9,0.9,0.1,0.9]+[0.1]*6, pad 0, min_on 0.05, min_off 0 → binarization [[0,0.04],[0.06,0.08]], filtering 후 빈 결과.
     * 입력: 없음
     * 출력: 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun nemoBranchesMatchNemo() {
        val e = floats(listOf(0.1f, 0.6f, 0.7f, 0.2f, 0.1f, 0.6f, 0.1f, 0.1f, 0.1f, 0.1f, 0.8f, 0.9f, 0.9f, 0.9f, 0.2f, 0.4f, 0.9f, 0.9f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f, 0.7f)) // 입력 E
        val base = NemoParams(0.5f, 0.3f, 0.04, 0.02, 0.1, 0.08, false) // filter_speech_first false
        val bin = NemoPostprocess.binarize(e, 0.02, base) // 이진화
        assertEquals(listOf(listOf(0f, 0.14f), listOf(0.16f, 0.38f), listOf(0.44f, 0.5f)), bin.map { it.toList() }) // 원본 이진화(float32)
        assertEquals(listOf(SpeechSegment(0.0, 0.5f.toDouble())), NemoPostprocess.apply(e, 0.02, base)) // false 분기
        assertEquals(listOf(SpeechSegment(0.0, 0.38f.toDouble())), NemoPostprocess.apply(e, 0.02, base.copy(filterSpeechFirst = true))) // true 분기
        assertEquals(emptyList<SpeechSegment>(), NemoPostprocess.apply(FloatArray(10) { 0.1f }, 0.02, base.copy(filterSpeechFirst = true))) // 무음
        val h = floats(listOf(0.9f, 0.9f, 0.1f, 0.9f) + List(6) { 0.1f }) // 입력 H
        val hp = NemoParams(0.5f, 0.3f, 0.0, 0.0, 0.05, 0.0, true) // 패딩 없음, min_on 0.05
        assertEquals(listOf(listOf(0f, 0.04f), listOf(0.06f, 0.08f)), NemoPostprocess.binarize(h, 0.02, hp).map { it.toList() }) // 원본 이진화
        assertEquals(emptyList<SpeechSegment>(), NemoPostprocess.apply(h, 0.02, hp)) // 전부 제거
    }

    // ---------------------------------------------------------------- 도우미

    /** 목적: Float 목록 → FloatArray. 입력: xs. 리턴: FloatArray. */
    private fun floats(xs: List<Float>): FloatArray = xs.toFloatArray() // 변환

    /**
     * 목적: 0/1 결정 배열의 연속 1 구간 추출
     * 기능: [시작, 끝(배타)) 쌍 목록을 만든다(골든 OFFLINE expectedFrames 형식).
     * 입력: d(결정 배열)
     * 출력: 없음
     * 리턴: 구간 목록
     */
    private fun runsOf(d: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>() // 결과
        var s = -1 // 현재 구간 시작(-1 = 없음)
        for (t in d.indices) { // 프레임마다
            if (d[t] == 1 && s < 0) s = t // 구간 시작
            if (d[t] == 0 && s >= 0) { out += intArrayOf(s, t); s = -1 } // 구간 끝
        }
        if (s >= 0) out += intArrayOf(s, d.size) // 끝까지 이어진 구간
        return out // 결과
    }

    /** 목적: 프레임 쌍 목록 정확 비교. 입력: label, expected, actual. 출력: 불일치 시 AssertionError. 리턴: 없음. */
    private fun assertFramesEqual(label: String, expected: List<IntArray>, actual: List<IntArray>) {
        assertEquals("$label 프레임 구간", expected.map { it.toList() }, actual.map { it.toList() }) // 리스트로 바꿔 비교
    }

    /** 목적: JSON [[start, end], …](float32 최단 표기) 와 FloatArray 쌍 목록의 float32 정확 비교. 입력: label, expected, actual. 리턴: 없음. */
    private fun assertFloatSegs(label: String, expected: JsonArray, actual: List<FloatArray>) {
        val exp = expected.map { r -> r.jsonArray.map { it.jsonPrimitive.content.toFloat() } } // 원문 → float32
        assertEquals(label, exp, actual.map { it.toList() }) // 정확 일치
    }

    /** 목적: 최대 경계 차이(보고용). 입력: 기대·실제 구간. 리턴: 최대 |차|(개수 다르면 +∞). */
    private fun maxDiff(e: List<SpeechSegment>, a: List<SpeechSegment>): Double =
        if (e.size != a.size) Double.POSITIVE_INFINITY else e.indices.maxOfOrNull { maxOf(Math.abs(e[it].startSec - a[it].startSec), Math.abs(e[it].endSec - a[it].endSec)) } ?: 0.0 // 최대

    /** 목적: OFFLINE 골든 params 가 프리셋 값과 같은지 확인. 입력: g(골든), p(프리셋 파라미터). 리턴: 없음. */
    private fun checkOfflineParams(g: Golden, p: FireRedParams) {
        val o = g.params!! // 골든 params
        fun i(k: String) = o[k]!!.jsonPrimitive.content.toInt() // 정수 키
        assertEquals("${g.name} smooth", p.smoothWindow, i("smooth_window_size")) // 평활 창
        assertEquals("${g.name} threshold", p.threshold.toString(), o["speech_threshold"]!!.jsonPrimitive.content) // 임계(문자열 비교 0.4)
        assertEquals("${g.name} min_speech", p.minSpeechFrames, i("min_speech_frame")) // 최소 발화
        assertEquals("${g.name} max_speech", p.maxSpeechFrames, i("max_speech_frame")) // 최대 발화
        assertEquals("${g.name} min_silence", p.minSilenceFrames, i("min_silence_frame")) // 최소 무음
        assertEquals("${g.name} merge", p.mergeSilenceFrames, i("merge_silence_frame")) // 병합
        assertEquals("${g.name} extend", p.extendFrames, i("extend_speech_frame")) // 확장
    }

    /** 목적: STREAM 골든 params 가 프리셋 값과 같은지 확인. 입력: g(골든), p(프리셋 파라미터). 리턴: 없음. */
    private fun checkStreamParams(g: Golden, p: FireRedParams) {
        val o = g.params!! // 골든 params
        fun i(k: String) = o[k]!!.jsonPrimitive.content.toInt() // 정수 키
        assertEquals("${g.name} smooth", p.smoothWindow, i("smooth_window_size")) // 평활 창(R17: 5)
        assertEquals("${g.name} threshold", p.threshold.toString(), o["speech_threshold"]!!.jsonPrimitive.content) // 임계 0.5
        assertEquals("${g.name} pad_start", p.padStartFrames, i("pad_start_frame")) // 시작 패딩
        assertEquals("${g.name} min_speech", p.minSpeechFrames, i("min_speech_frame")) // 최소 발화
        assertEquals("${g.name} max_speech", p.maxSpeechFrames, i("max_speech_frame")) // 최대 발화
        assertEquals("${g.name} min_silence", p.minSilenceFrames, i("min_silence_frame")) // 최소 무음
    }

    /** 목적: NeMo 골든 params 가 프리셋 값과 같은지 확인. 입력: g(골든), p(프리셋 파라미터). 리턴: 없음. */
    private fun checkNemoParams(g: Golden, p: NemoParams) {
        val o = g.params!! // 골든 params
        fun d(k: String) = o[k]!!.jsonPrimitive.content.toDouble() // 실수 키
        assertEquals("${g.name} onset", p.onset.toString(), o["onset"]!!.jsonPrimitive.content) // 0.3
        assertEquals("${g.name} offset", p.offset.toString(), o["offset"]!!.jsonPrimitive.content) // 0.3
        assertEquals("${g.name} pad_onset", p.padOnsetSec, d("pad_onset"), 0.0) // 0.2
        assertEquals("${g.name} pad_offset", p.padOffsetSec, d("pad_offset"), 0.0) // 0.2
        assertEquals("${g.name} min_on", p.minDurationOnSec, d("min_duration_on"), 0.0) // 0.2
        assertEquals("${g.name} min_off", p.minDurationOffSec, d("min_duration_off"), 0.0) // 0.2
        assertEquals("${g.name} filter_speech_first", p.filterSpeechFirst, o["filter_speech_first"]!!.jsonPrimitive.content.toBooleanStrict()) // true
    }
}
