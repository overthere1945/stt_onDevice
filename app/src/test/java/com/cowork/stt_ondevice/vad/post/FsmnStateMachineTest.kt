// 파일명: FsmnStateMachineTest.kt
// 목적: FsmnStateMachine(funasr_onnx 0.4.3 E2EVadModel + Fsmn_vad 블록 루프 포팅)이 골든과 ms 단위로 정확히 일치하는지 검증
// 기능: FSMN_* 골든 전부(2 프리셋 × 4 클립 = 8)를 JSON 의 frameScores(sil prob)·frameDb 와 프리셋 파라미터로 다시 계산해
//       expectedMs 와 정확히 비교하고, 블록 분할 규칙을 골든 blocks 기록과 대조한다. 골든으로는 실행되지 않는 분기
//       (T % 6000 == 1 재처리, decibel_thres 이중 처리, 단일 발화 모드, 3 블록·블록 경계 걸침)는 원본을 직접 실행한 손작성 기대값으로 검증한다.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 테스트 패키지

import com.cowork.stt_ondevice.vad.FsmnParams // FSMN 상태기계 파라미터
import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import kotlinx.serialization.json.JsonNull // JSON null
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값 변환
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertNotEquals // 값 불일치 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-13:25
/**
 * 목적: FSMN 상태기계 포팅의 정합성(골든 일치) 회귀 방지
 * 기능: 골든 파일을 접두어 FSMN_ 으로 모두 찾아(클립 이름 하드코딩 없음) 하나도 빠짐없이 비교한다.
 */
class FsmnStateMachineTest {

    /**
     * 목적: FSMN 2 프리셋 × 4 클립 골든 일치 검증
     * 기능: FSMN_* 골든을 모두 읽어 개수를 파일 수·8 과 대조하고, 각 골든을 apply 로 다시 계산해 ms 정수 구간이 정확히 같은지 본다.
     *       다중 블록 골든(blocks 2개 이상)이 있고, 그 클립에서 두 프리셋 결과가 달라야(max_single_segment_time 발동) 한다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun matchesGoldenFsmn() {
        val goldens = GoldenLoader.allFor("FSMN") // FSMN_* 구간 골든 전부
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("FSMN_"), goldens.size) // 빠진 파일 없음
        assertEquals("FSMN 골든은 2 프리셋 × 4 클립 = 8 개여야 함", 8, goldens.size) // 발견 개수 고정(R14)
        assertEquals("FSMN 프리셋 2종이 모두 있어야 함", 2, goldens.map { it.presetId }.toSet().size) // 프리셋 누락 없음
        val multi = goldens.filter { (it.blocks?.size ?: 0) >= 2 } // 다중 블록 골든
        assertTrue("다중 블록 골든이 없음", multi.isNotEmpty()) // 다중 블록 경로가 실제로 검증되는지
        multi.groupBy { it.clip }.values.forEach { byClip -> // 다중 블록 클립마다
            assertNotEquals("다중 블록 클립에서 두 프리셋 결과가 같음(최대 구간 분기 미검증)", byClip[0].expected, byClip[1].expected) // 결과가 달라야 함
        }
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
     * 기능: (1) 프리셋 파라미터가 JSON params 와 같은지 (2) blockPlan 이 JSON blocks(프레임 수·is_final·파형 샘플 수)와 같은지
     *       (3) apply(sil prob, frame dB, 프리셋) 결과의 ms 정수 구간이 expectedMs 와 같은지 (4) 초 구간이 expected 와 정확히 같은지 본다.
     * 입력: g(골든)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun checkOne(g: Golden) {
        val p = VadPresets.byId(g.presetId).params as FsmnParams // 프리셋 파라미터(앱이 실제로 쓸 값)
        assertParamsMatchJson(g, p) // 골든 생성에 쓴 값과 같은지
        val db = g.frameDb // 프레임 dB
        assertNotNull("frameDb 없음", db) // FSMN 골든은 반드시 있음
        assertEquals("frameDb 길이 ≠ frameScores 길이", g.frameScores.size, db!!.size) // 길이 일치
        assertBlocksMatch(g) // 블록 분할 규칙 확인
        val actual = FsmnStateMachine.apply(g.frameScores, db, p) // 포팅 실행
        val expMs = g.expectedMs ?: throw AssertionError("expectedMs 없음") // ms 정수 기대값
        val expText = expMs.joinToString { "[${it[0]}, ${it[1]}]" } // 메시지용 기대 ms
        val actMs = actual.map { longArrayOf(Math.round(it.startSec * 1000), Math.round(it.endSec * 1000)) } // 초 → ms 복원(ms/1000 이므로 정확)
        val actText = actMs.joinToString { "[${it[0]}, ${it[1]}]" } // 메시지용 실제 ms
        assertEquals("ms 구간 불일치\n기대=$expText\n실제=$actText", expText, actText) // ms 정확 일치
        assertEquals("초 구간 불일치(ms/1000)", g.expected, actual) // 초 값도 비트 단위 일치(둘 다 ms/1000.0)
    }

    /**
     * 목적: 블록 분할 규칙(funasr_onnx vad_bin.py L110–123)이 골든 생성 때의 실제 호출과 같은지 확인
     * 기능: blockPlan(T) 의 (프레임 수, is_final) 를 JSON blocks 와 비교하고, 파형 샘플 수는
     *       `min(N, (o + step − 1)·160 + 400) − o·160`(L119–123) 으로 계산해 비교한다.
     * 입력: g(골든)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun assertBlocksMatch(g: Golden) {
        val blocks = g.blocks ?: throw AssertionError("blocks 없음") // 골든 블록 기록
        val n = g.totalSamples ?: throw AssertionError("totalSamples 없음") // 전체 샘플 수
        val plan = FsmnStateMachine.blockPlan(g.frameScores.size) // 포팅의 블록 분할
        assertEquals("블록 수", blocks.size, plan.size) // 블록 수 일치
        for (i in blocks.indices) { // 블록마다
            val b = plan[i] // 포팅 블록
            val wave = minOf(n.toLong(), (b.offset + b.frames - 1).toLong() * 160 + 400) - b.offset.toLong() * 160 // 블록 파형 샘플 수
            assertEquals("블록[$i] 프레임 수", blocks[i].frames, b.frames) // 프레임 수
            assertEquals("블록[$i] is_final", blocks[i].isFinal, b.isFinal) // is_final
            assertEquals("블록[$i] 파형 샘플 수", blocks[i].waveSamples.toLong(), wave) // 파형 샘플 수
        }
    }

    /**
     * 목적: 프리셋 값 = 골든 생성 값 확인
     * 기능: JSON params(funasr config.yaml model_conf 키 20개)와 프리셋 FsmnParams 를 비교한다.
     *       Float 값은 10진 표기(0.6f → "0.6")를 double 로 읽어 Python 값과 같은 기준으로 비교한다.
     * 입력: g(골든), p(프리셋 파라미터)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun assertParamsMatchJson(g: Golden, p: FsmnParams) {
        val j = g.params ?: throw AssertionError("params 없음") // FSMN 골든은 모두 params 가 있음
        fun d(key: String): Double = j[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toDouble() // 숫자 키 → double
            ?: throw AssertionError("params.$key 없음") // 키가 없으면 실패
        fun f(v: Float): Double = v.toString().toDouble() // Float → Python 과 같은 double
        assertEquals("max_end_silence_time", d("max_end_silence_time"), p.maxEndSilenceTime.toDouble(), 0.0) // 끝 무음
        assertEquals("max_start_silence_time", d("max_start_silence_time"), p.maxStartSilenceTime.toDouble(), 0.0) // 시작 무음
        assertEquals("window_size_ms", d("window_size_ms"), p.windowSizeMs.toDouble(), 0.0) // 창
        assertEquals("sil_to_speech_time_thres", d("sil_to_speech_time_thres"), p.silToSpeechTimeThres.toDouble(), 0.0) // 무음→음성
        assertEquals("speech_to_sil_time_thres", d("speech_to_sil_time_thres"), p.speechToSilTimeThres.toDouble(), 0.0) // 음성→무음
        assertEquals("speech_2_noise_ratio", d("speech_2_noise_ratio"), f(p.speech2NoiseRatio), 0.0) // 잡음비
        assertEquals("do_extend", d("do_extend"), p.doExtend.toDouble(), 0.0) // 확장
        assertEquals("lookback_time_start_point", d("lookback_time_start_point"), p.lookbackTimeStartPoint.toDouble(), 0.0) // lookback
        assertEquals("lookahead_time_end_point", d("lookahead_time_end_point"), p.lookaheadTimeEndPoint.toDouble(), 0.0) // lookahead
        assertEquals("max_single_segment_time", d("max_single_segment_time"), p.maxSingleSegmentTime.toDouble(), 0.0) // 최대 구간
        assertEquals("snr_thres", d("snr_thres"), f(p.snrThres), 0.0) // SNR 임계
        assertEquals("decibel_thres", d("decibel_thres"), f(p.decibelThres), 0.0) // dB 임계
        assertEquals("speech_noise_thres", d("speech_noise_thres"), f(p.speechNoiseThres), 0.0) // 음성/잡음 임계
        assertEquals("fe_prior_thres", d("fe_prior_thres"), f(p.fePriorThres), 0.0) // 사전 임계
        assertEquals("speech_noise_thresh_low", d("speech_noise_thresh_low"), f(p.speechNoiseThreshLow), 0.0) // 하한
        assertEquals("speech_noise_thresh_high", d("speech_noise_thresh_high"), f(p.speechNoiseThreshHigh), 0.0) // 상한
        assertEquals("frame_in_ms", d("frame_in_ms"), p.frameInMs.toDouble(), 0.0) // 프레임 간격
        assertEquals("frame_length_ms", d("frame_length_ms"), p.frameLengthMs.toDouble(), 0.0) // 프레임 길이
        assertEquals("detect_mode", d("detect_mode"), p.detectMode.toDouble(), 0.0) // 검출 모드
        assertEquals("snr_mode", d("snr_mode"), p.snrMode.toDouble(), 0.0) // SNR 모드
    }

    /**
     * 목적: 블록 분할 규칙 단독 검증(골든에 없는 길이 포함)
     * 기능: 기대값은 vad_bin.py L110–117 루프를 numpy feats_len 그대로 tools/golden/.venv 에서 실행한 결과
     *       (offset, 프레임 수, is_final)이다. T % 6000 == 1(T > 6000)이면 마지막 프레임 1개 블록이 한 번 더 붙는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun blockPlanMatchesFunasrLoop() {
        fun plan(t: Int): String = FsmnStateMachine.blockPlan(t).joinToString { "(${it.offset}, ${it.frames}, ${it.isFinal})" } // 문자열화
        assertEquals("(0, 1, true)", plan(1)) // 1 프레임
        assertEquals("(0, 1998, true)", plan(1998)) // 20 s 클립
        assertEquals("(0, 6000, true)", plan(6000)) // 정확히 6000
        assertEquals("(0, 6001, true), (6000, 1, true)", plan(6001)) // 6000+1: 재처리 블록
        assertEquals("(0, 6000, false), (6000, 2, true)", plan(6002)) // 6000+2
        assertEquals("(0, 6000, false), (6000, 998, true)", plan(6998)) // 70 s 클립
        assertEquals("(0, 6000, false), (6000, 6000, true)", plan(12000)) // 12000
        assertEquals("(0, 6000, false), (6000, 6001, true), (12000, 1, true)", plan(12001)) // 12000+1: 재처리 블록
        assertEquals("(0, 6000, false), (6000, 6000, false), (12000, 2, true)", plan(12002)) // 3 블록
        assertEquals("", plan(0)) // 프레임 없음(원본은 range step 0 예외 — 포팅은 블록 없음)
    }

    /**
     * 목적: 손작성 입력 생성 도우미
     * 기능: sil prob 은 기본 0.95f, speech 구간 [a, b) 는 0.05f. frame dB 는 기본 0f, quiet 구간 [a, b) 는 −60f
     *       (원본 실행 때 파형: 0.05 진폭(≈0 dB), 무음 구간은 0 → 정확히 −60 dB. 손작성 케이스의 snr_thres −100 에서는
     *       dB 의 정확한 값이 아니라 decibel_thres 기준 대소만 결과에 영향을 준다).
     * 입력: t(프레임 수), speech(음성 구간 목록), quiet(무음 dB 구간 목록), p(파라미터)
     * 출력: 없음
     * 리턴: apply 결과의 ms 정수 구간 문자열
     */
    private fun runHand(t: Int, speech: List<IntRange>, quiet: List<IntRange>, p: FsmnParams): String {
        val sil = FloatArray(t) { 0.95f } // 기본 무음 확률
        speech.forEach { r -> r.forEach { sil[it] = 0.05f } } // 음성 프레임
        val db = FloatArray(t) { 0f } // 기본 dB
        quiet.forEach { r -> r.forEach { db[it] = -60f } } // 완전 무음 프레임
        return FsmnStateMachine.apply(sil, db, p) // 포팅 실행
            .joinToString { "[${Math.round(it.startSec * 1000)}, ${Math.round(it.endSec * 1000)}]" } // ms 문자열
    }

    /**
     * 목적: 골든으로는 실행되지 않는 분기 검증
     * 기능: 기대값은 tools/golden/.venv 의 funasr_onnx 0.4.3 E2EVadModel 을 vad_bin.py L110–141 블록 루프 그대로
     *       같은 sil prob(float32)·파형으로 실행한 결과(ms)다.
     *       (1) T = 6001: 마지막 프레임이 리셋된 상태기계로 다시 처리됨(구간 없음)
     *       (1b) 같은 경로에서 sil_to_speech 10 ms 면 재처리 블록이 [0, 10] 을 덧붙임(원본 그대로의 특이 결과)
     *       (2) decibel_thres −50: dB 미만 프레임은 GetFrameState 안에서 DetectOneFrame 이 한 번 더 불림
     *       (3) detect_mode 0(단일 발화): 첫 구간 뒤 검출 중단
     *       (4) 3 블록(13000 프레임), 블록 경계(6000·12000)를 걸친 구간, max_single_segment_time 10 s 강제 분할
     *       (5) 전부 무음 → 구간 없음
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun matchesUpstreamOnHandCases() {
        val base = VadPresets.byId("FSMN_DEFAULT").params as FsmnParams // 기본 파라미터
        assertEquals("[740, 9160], [19740, 60010]", runHand(6001, listOf(100 until 900, 2000 until 5990), emptyList(), base)) // (1)
        assertEquals(
            "[600, 9300], [59500, 60010], [0, 10]", // 원본 결과(마지막 [0, 10] 은 재처리 블록의 상대 시각)
            runHand(6001, listOf(100 until 900, 5990 until 6001), emptyList(), base.copy(silToSpeechTimeThres = 10)), // (1b)
        )
        assertEquals("[740, 6810], [7320, 20160]", runHand(3000, listOf(100 until 2000), listOf(700 until 758), base.copy(decibelThres = -50f))) // (2)
        assertEquals("[740, 6160]", runHand(3000, listOf(100 until 600, 1200 until 2000), emptyList(), base.copy(detectMode = 0))) // (3)
        assertEquals(
            "[240, 3160], [57740, 63160], [69740, 79750], [79750, 89760], [89760, 95160], [119640, 125160]", // 원본 결과
            runHand(13000, listOf(50 until 300, 5800 until 6300, 7000 until 9500, 11990 until 12500), emptyList(), base.copy(maxSingleSegmentTime = 10000)), // (4)
        )
        assertEquals("", runHand(500, emptyList(), emptyList(), base)) // (5)
        assertEquals(emptyList<SpeechSegment>(), FsmnStateMachine.apply(FloatArray(0), FloatArray(0), base)) // 빈 입력
    }
}
