// 파일명: FsmnStateMachine.kt
// 목적: FSMN 계열(FSMN_DEFAULT, FSMN_ASR30) 의 "프레임 sil prob·dB → 음성 구간" 후처리(상태기계)
// 기능: funasr_onnx 0.4.3 `funasr_onnx/utils/e2e_vad.py` 의 E2EVadModel(오프라인, online=False) 을 줄 단위로 포팅하고,
//       `funasr_onnx/vad_bin.py` 의 `Fsmn_vad.__call__`(L95–148) 이 6000 프레임 블록마다 같은 인스턴스를
//       is_final=False … True 로 이어 부르는 방식(블록 분할 규칙 포함)을 그대로 재현한다.
//       주석의 "e2e L숫자"·"vad_bin L숫자" 는 tools/golden/.venv(pip funasr_onnx==0.4.3 설치본)의 행 번호다.
//       프레임 간격 10 ms(frame_in_ms), 결과 ms = 프레임 × frame_in_ms, 초 = ms / 1000.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 패키지

import com.cowork.stt_ondevice.vad.FsmnParams // FSMN 상태기계 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import kotlin.math.abs // 절댓값(math.fabs)
import kotlin.math.exp // 지수(math.exp)
import kotlin.math.ln // 자연로그(math.log)

// change(add)-hyungchul-2026-10-10-13:25
/**
 * 목적: FunASR FSMN-VAD 후처리의 Kotlin 포팅(골든과 ms 단위 정확 일치가 목표)
 * 기능: 전체 프레임의 sil prob(`logits[0][t][0]`)·frame dB 를 받아, funasr_onnx 와 같은 블록 분할로 상태기계를 돌려 구간을 만든다.
 *
 * 블록 분할(시그니처를 바꾸지 않고 apply 안에서 유도 — vad_bin L110–117 과 같은 규칙, blockPlan 참고):
 *   T = 전체 프레임 수, S = min(T, 6000), 블록 시작 o = range(0, T, S). 변수 step(처음 S)에 대해 o + step ≥ T − 1 이면
 *   step = T − o, is_final = True, 아니면 is_final = False. 블록 [o, o + step) 을 같은 인스턴스로 처리한다.
 *   T % 6000 == 1(T > 6000)이면 원본처럼 마지막 프레임 1 개 블록이 리셋된 상태기계로 한 번 더 처리된다(그 결과 시각은 0 기준).
 *   블록 파형으로 다시 계산한 dB 는 전역 dB 와 같은 값이 이어지므로(이웃 블록 파형이 240 샘플 겹침, tools/golden/README_한국어.md §6.3)
 *   전역 frameDb 의 블록 구간을 그대로 쓴다.
 *
 * 수치 기준(numpy 1.26 승격 규칙 확인값):
 *   sil prob 은 float32 이지만 원본 `sum([np.float32])` 가 float64 가 되므로 ln·exp·비교는 모두 double 이다.
 *   Float 파라미터는 최단 10진 표기("0.6")를 double 로 읽어 Python 의 config 값과 같게 만든다.
 *   ln/exp 는 JVM Math 를 쓴다(원본 glibc 와 마지막 ulp 가 다를 수 있으나 판정 경계에 정확히 걸리지 않는 한 결과 동일).
 *
 * 원본과 다른 점(결과 구간에 영향 없음):
 *   - 샘플 버퍼 복사·경고 출력·frame_probs·online 전용 next_seg·max_time_out·sil_frame 은 구간에 쓰이지 않아 생략.
 *   - PopDataBufTillFrame(e2e L326–334)의 `data_buf_size ≥ 160` 조건은 항상 참(아래 popDataBufTillFrame 설명)이라 생략.
 *   - 원본이 예외로 멈추는 입력(sil prob 이 정확히 0 또는 1 → math.log(0) ValueError, 프레임 0 개 → range step 0 ValueError)은
 *     예외 대신 ln(0) = −∞ 로 계속 계산하거나(0 → 무음 판정) 빈 결과를 낸다.
 */
object FsmnStateMachine {

    /** vad_bin L111: 한 번에 ONNX·상태기계에 넣는 최대 프레임 수. */
    private const val MAX_BLOCK_FRAMES = 6000 // 블록 최대 프레임 수

    /** e2e VADXOptions L60 기본값(config 에 없음): SNR 잡음 평균에 쓰는 프레임 수. */
    private const val NOISE_FRAME_NUM_USED_FOR_SNR = 100 // 잡음 평균 프레임 수

    /** e2e VADXOptions L42 기본값(config 에 없음): 샘플레이트. */
    private const val SAMPLE_RATE = 16000 // 샘플레이트(Hz)

    /**
     * 목적: 블록 1개의 범위
     * 기능: funasr_onnx 가 E2EVadModel 을 한 번 부를 때의 시작 프레임·프레임 수·is_final 을 담는다.
     */
    internal data class Block(
        val offset: Int, // 블록 시작 전역 프레임(t_offset)
        val frames: Int, // 블록 프레임 수(step)
        val isFinal: Boolean, // is_final 인자
    )

    /**
     * 목적: funasr_onnx 블록 분할 규칙 재현
     * 기능: vad_bin L110–117 의 루프를 그대로 실행한다. range 의 step 은 루프 시작 때 한 번만 min(step, T − 0) = S 로 정해진다.
     * 입력: numFrames(전체 프레임 수 T)
     * 출력: 없음
     * 리턴: 호출 순서대로의 Block 목록(T ≤ 0 이면 빈 목록 — 원본은 range step 0 으로 ValueError)
     */
    internal fun blockPlan(numFrames: Int): List<Block> {
        if (numFrames <= 0) return emptyList() // 프레임 없음
        val featsLen = numFrames // feats_len
        var step = minOf(featsLen, MAX_BLOCK_FRAMES) // L111: step = int(min(feats_len.max(), 6000))
        val rangeStep = minOf(step, featsLen - 0) // L112: min(step, feats_len - t_offset), t_offset = 0 일 때 한 번 평가
        val out = mutableListOf<Block>() // 블록 목록
        var tOffset = 0 // L112: range(0, feats_len, rangeStep) 의 현재 값
        while (tOffset < featsLen) { // range 순회
            val isFinal: Boolean // 이번 블록의 is_final
            if (tOffset + step >= featsLen - 1) { // L113: 남은 프레임이 step 이하(+1)면 마지막
                step = featsLen - tOffset // L114: 남은 전부
                isFinal = true // L115
            } else {
                isFinal = false // L117
            }
            out += Block(tOffset, step, isFinal) // L118–123: feats[t_offset : t_offset + step]
            tOffset += rangeStep // 다음 range 값
        }
        return out // 결과
    }

    /**
     * 목적: 프레임 sil prob·dB → 음성 구간(초)
     * 기능: E2EVadModel 1 개(vad_bin L101)를 만들고 blockPlan 의 블록마다
     *       `vad_scorer(scores, 블록 파형, is_final, max_end_sil, online=False)`(L130–136)를 부른 결과를 이어 붙인다(L139–141).
     *       max_end_sil 은 생성자 인자가 없으므로 config 의 max_end_silence_time(L79–81)이다.
     * 입력: silProb(전체 프레임 sil prob = logits[0][t][0], 길이 T), frameDb(전체 프레임 dB, 길이 T,
     *       `10·log10(Σ x[160t+i]² + 1e-6)`, x 는 −1~1 파형), p(FsmnParams)
     * 출력: 없음
     * 리턴: 원본 반환 순서의 SpeechSegment 목록(초 = ms / 1000)
     */
    fun apply(silProb: FloatArray, frameDb: FloatArray, p: FsmnParams): List<SpeechSegment> {
        require(silProb.size == frameDb.size) { "sil prob 수(${silProb.size}) ≠ dB 수(${frameDb.size})" } // 길이 불일치는 호출 오류
        val vad = E2EVad(p) // vad_bin L101: 파형마다 새 E2EVadModel
        val maxEndSil = p.maxEndSilenceTime // vad_bin L79–81: max_end_sil = config max_end_silence_time
        val msSegments = mutableListOf<LongArray>() // [start_ms, end_ms] 누적
        for (b in blockPlan(silProb.size)) { // 블록마다(같은 인스턴스)
            msSegments += vad.call(silProb, frameDb, b.offset, b.frames, b.isFinal, maxEndSil) // L130–141
        }
        return msSegments.map { SpeechSegment(it[0] / 1000.0, it[1] / 1000.0) } // ms → 초(골든 expected 와 같은 ms/1000)
    }

    /** e2e L12–15: 상태기계 상태. */
    private enum class VadState { START_POINT_NOT_DETECTED, IN_SPEECH_SEGMENT, END_POINT_DETECTED }

    /** e2e L18–21: 프레임 상태. */
    private enum class FrameState { INVALID, SPEECH, SIL }

    /** e2e L25–31: 창 판정 결과(프레임별 전이). */
    private enum class AudioChangeState { SPEECH2SPEECH, SPEECH2SIL, SIL2SIL, SIL2SPEECH, NO_BEGIN, INVALID }

    /**
     * 목적: Python `int(a / b)`(실수 나눗셈 후 0 방향 절삭) 재현
     * 기능: double 나눗셈 뒤 toInt(0 방향 절삭)로 원본과 같은 정수를 만든다.
     * 입력: a, b(정수)
     * 출력: 없음
     * 리턴: 절삭된 몫
     */
    private fun pyIntDiv(a: Int, b: Int): Int = (a.toDouble() / b).toInt() // int(a / b)

    /**
     * 목적: e2e WindowDetector(L130–204) 포팅
     * 기능: 최근 window_size_ms 동안의 음성 프레임 수(win_sum)로 무음↔음성 전이를 판정한다.
     */
    private class WindowDetector(windowSizeMs: Int, silToSpeechTime: Int, speechToSilTime: Int, frameSizeMs: Int) {
        val winSizeFrame = pyIntDiv(windowSizeMs, frameSizeMs) // L143: 창 프레임 수
        private var winSum = 0 // L144: 창 안 음성 프레임 수
        private var winState = IntArray(winSizeFrame) // L145: 창 원형 버퍼
        private var curWinPos = 0 // L147: 원형 버퍼 위치
        private var preFrameState = FrameState.SIL // L148: 직전 확정 상태
        private val silToSpeechFrmcntThres = pyIntDiv(silToSpeechTime, frameSizeMs) // L150: 무음→음성 프레임 수 임계
        private val speechToSilFrmcntThres = pyIntDiv(speechToSilTime, frameSizeMs) // L151: 음성→무음 프레임 수 임계

        /** 목적: 창 초기화(L157–165). 입력/출력/리턴: 없음. */
        fun reset() {
            curWinPos = 0 // L158
            winSum = 0 // L159
            winState = IntArray(winSizeFrame) // L160
            preFrameState = FrameState.SIL // L161
        }

        /**
         * 목적: 프레임 1 개 반영 후 전이 판정(L170–201)
         * 기능: 창에 현재 프레임 상태(1/0)를 넣고 win_sum 으로 Sil2Speech / Speech2Sil / 유지 를 돌려준다.
         * 입력: frameState(프레임 상태)
         * 출력: 창 상태 갱신
         * 리턴: AudioChangeState
         */
        fun detectOneFrame(frameState: FrameState): AudioChangeState {
            val cur = when (frameState) { // L171–177
                FrameState.SPEECH -> 1 // 음성
                FrameState.SIL -> 0 // 무음
                FrameState.INVALID -> return AudioChangeState.INVALID // L177: 무효
            }
            winSum -= winState[curWinPos] // L178: 빠지는 값 제거
            winSum += cur // L179: 새 값 추가
            winState[curWinPos] = cur // L180: 버퍼 기록
            curWinPos = (curWinPos + 1) % winSizeFrame // L181: 위치 이동
            if (preFrameState == FrameState.SIL && winSum >= silToSpeechFrmcntThres) { // L183–186
                preFrameState = FrameState.SPEECH // L187
                return AudioChangeState.SIL2SPEECH // L188
            }
            if (preFrameState == FrameState.SPEECH && winSum <= speechToSilFrmcntThres) { // L190–193
                preFrameState = FrameState.SIL // L194
                return AudioChangeState.SPEECH2SIL // L195
            }
            if (preFrameState == FrameState.SIL) return AudioChangeState.SIL2SIL // L197–198
            if (preFrameState == FrameState.SPEECH) return AudioChangeState.SPEECH2SPEECH // L199–200
            return AudioChangeState.INVALID // L201
        }
    }

    /**
     * 목적: e2e E2EVadSpeechBufWithDoa(L103–118) 중 구간 결과에 쓰이는 필드
     * 기능: 출력 구간 1 개의 시작·끝 ms 와 시작점/끝점 포함 여부를 담는다(샘플 버퍼·doa 생략).
     */
    private class SpeechBuf(var startMs: Long, var endMs: Long) { // 시작·끝(ms)
        var containSegStartPoint = false // L108: 구간 시작점 포함
        var containSegEndPoint = false // L109: 구간 끝점 포함
    }

    /**
     * 목적: e2e E2EVadModel(L207–704) 포팅(오프라인, batch 1, sil_pdf_ids = [0])
     * 기능: 블록 호출(call)마다 프레임 상태를 판정하고 창 전이에 따라 구간 시작·끝을 확정한다.
     */
    private class E2EVad(p: FsmnParams) {
        // ---- vad_opts(VADXOptions L39–100, config model_conf 값) ----
        private val detectMode = p.detectMode // detect_mode(0 단일 발화, 1 다중 발화)
        private val maxStartSilenceTime = p.maxStartSilenceTime // max_start_silence_time(ms)
        private val maxEndSilenceTime = p.maxEndSilenceTime // max_end_silence_time(ms)
        private val speechToSilTimeThres = p.speechToSilTimeThres // speech_to_sil_time_thres(ms)
        private val speech2NoiseRatio = p.speech2NoiseRatio.toString().toDouble() // speech_2_noise_ratio
        private val doExtend = p.doExtend // do_extend(0 이 아니면 참)
        private val lookbackTimeStartPoint = p.lookbackTimeStartPoint // lookback_time_start_point(ms)
        private val lookaheadTimeEndPoint = p.lookaheadTimeEndPoint // lookahead_time_end_point(ms)
        private val maxSingleSegmentTime = p.maxSingleSegmentTime // max_single_segment_time(ms)
        private val snrThres = p.snrThres.toString().toDouble() // snr_thres
        private val decibelThres = p.decibelThres.toString().toDouble() // decibel_thres
        private val fePriorThres = p.fePriorThres.toString().toDouble() // fe_prior_thres
        private val frameInMs = p.frameInMs // frame_in_ms
        private val speechNoiseThres = p.speechNoiseThres.toString().toDouble() // L247: self.speech_noise_thres

        // L217–222: windows_detector(window_size_ms, sil_to_speech, speech_to_sil, frame_in_ms)
        private val windowsDetector = WindowDetector(p.windowSizeMs, p.silToSpeechTimeThres, p.speechToSilTimeThres, p.frameInMs)

        init {
            require(frameInMs > 0 && windowsDetector.winSizeFrame > 0) { "frame_in_ms·window_size_ms 가 잘못됨" } // 원본은 0 나눗셈 예외
        }

        // ---- 상태 변수(L225–255) ----
        private var dataBufStartFrame = 0 // L226: 샘플 버퍼의 첫 프레임
        private var frmCnt = 0 // L227: 지금까지 받은 프레임 수
        private var latestConfirmedSpeechFrame = 0 // L228: 마지막으로 음성 확정한 프레임
        private var lastestConfirmedSilenceFrame = -1 // L229: 마지막으로 무음 확정한 프레임(원본 철자 유지)
        private var continousSilenceFrameCount = 0 // L230: 연속 무음 프레임 수
        private var vadStateMachine = VadState.START_POINT_NOT_DETECTED // L231: 상태
        private var confirmedStartFrame = -1 // L232: 확정 시작 프레임
        private var confirmedEndFrame = -1 // L233: 확정 끝 프레임
        private var numberEndTimeDetected = 0 // L234: 끝점 검출 횟수
        private var noiseAverageDecibel = -100.0 // L237: 잡음 평균 dB
        private val outputDataBuf = ArrayList<SpeechBuf>() // L241: 출력 구간 버퍼
        private var outputDataBufOffset = 0 // L242: 이미 반환한 구간 수
        private var maxEndSilFrameCntThresh = maxEndSilenceTime - speechToSilTimeThres // L244–246: 끝 무음 임계(ms)
        private var idxPreChunk = 0 // L249: 이전 블록들의 프레임 수 합
        private val decibel = ArrayList<Double>() // L251: 누적 프레임 dB
        private var nnEvalBlockSize = 0 // L57/L322: 현재 블록 프레임 수
        private var scores: FloatArray = FloatArray(0) // L324: self.scores 의 원본 배열(전역 sil prob)
        private var scoresBase = 0 // 현재 블록 scores[0][0] 의 전역 인덱스

        /**
         * 목적: E2EVadModel.__call__(L501–552) 포팅(online=False)
         * 기능: dB·점수 누적 → is_final 이면 DetectLastFrames, 아니면 DetectCommonFrames → 반환 대상 구간 수집 → is_final 이면 전체 리셋.
         * 입력: sil(전역 sil prob), db(전역 dB), offset(블록 시작 전역 프레임), frames(블록 프레임 수), isFinal, maxEndSil(ms)
         * 출력: 상태 갱신
         * 리턴: 이번 호출이 반환한 [start_ms, end_ms] 목록
         */
        fun call(sil: FloatArray, db: FloatArray, offset: Int, frames: Int, isFinal: Boolean, maxEndSil: Int): List<LongArray> {
            maxEndSilFrameCntThresh = maxEndSil - speechToSilTimeThres // L509
            // L510–511: ComputeDecibel(L301–318) — 블록 파형의 프레임 dB = 전역 dB 의 [offset, offset + frames) (클래스 설명 참고)
            for (t in offset until offset + frames) decibel += db[t].toDouble() // 누적 dB 에 블록 dB 추가
            // L512: ComputeScores(L320–324)
            nnEvalBlockSize = frames // L322: nn_eval_block_size = scores.shape[1]
            frmCnt += frames // L323: 전체 프레임 수 누적
            scores = sil // L324: self.scores = scores (블록 시작 = scoresBase)
            scoresBase = offset // 블록 첫 프레임의 전역 인덱스
            if (!isFinal) detectCommonFrames() else detectLastFrames() // L513–516
            val segmentBatch = mutableListOf<LongArray>() // L519
            if (outputDataBuf.isNotEmpty()) { // L520
                var i = outputDataBufOffset // L521: range(output_data_buf_offset, len) — 시작값은 루프 전에 한 번 평가
                val end = outputDataBuf.size // range 끝(루프 전 평가)
                while (i < end) { // 구간마다
                    val buf = outputDataBuf[i] // 현재 구간
                    if (!isFinal && (!buf.containSegStartPoint || !buf.containSegEndPoint)) { i++; continue } // L536–540: 미완 구간은 다음 호출로
                    segmentBatch += longArrayOf(buf.startMs, buf.endMs) // L541–545
                    outputDataBufOffset += 1 // L543
                    i++ // 다음 구간
                }
            }
            if (isFinal) allResetDetection() // L549–551
            return segmentBatch // L547–548·L552(배치 1)
        }

        /** 목적: AllResetDetection(L257–288). 기능: 모든 상태를 생성 직후 값으로 되돌린다. 입력/출력/리턴: 없음. */
        private fun allResetDetection() {
            dataBufStartFrame = 0 // L259
            frmCnt = 0 // L260
            numberEndTimeDetected = 0 // L267
            noiseAverageDecibel = -100.0 // L270
            outputDataBuf.clear() // L274
            outputDataBufOffset = 0 // L275
            maxEndSilFrameCntThresh = maxEndSilenceTime - speechToSilTimeThres // L277–279
            idxPreChunk = 0 // L282
            decibel.clear() // L284
            scores = FloatArray(0) // L281: self.scores = None
            scoresBase = 0 // 블록 기준 초기화
            resetDetection() // L288(L261–265 의 나머지 값도 여기서 같은 값으로 초기화)
        }

        /** 목적: ResetDetection(L290–299). 기능: 구간 단위 상태와 창을 초기화한다. 입력/출력/리턴: 없음. */
        private fun resetDetection() {
            continousSilenceFrameCount = 0 // L291
            latestConfirmedSpeechFrame = 0 // L292
            lastestConfirmedSilenceFrame = -1 // L293
            confirmedStartFrame = -1 // L294
            confirmedEndFrame = -1 // L295
            vadStateMachine = VadState.START_POINT_NOT_DETECTED // L296
            windowsDetector.reset() // L297
        }

        /**
         * 목적: PopDataBufTillFrame(L326–334)
         * 기능: data_buf_start_frame 을 frame_idx 까지 올린다. 원본 조건 `data_buf_size ≥ 160` 은 항상 참이다:
         *       data_buf_all_size 는 블록 파형 길이의 합(블록마다 프레임 수·160 + 240 이상)이라 ≥ frm_cnt·160 + 240 이고,
         *       data_buf_size 를 다시 계산하는 순간 data_buf_start_frame ≤ frame_idx ≤ frm_cnt − 1 이므로 data_buf_size ≥ 400
         *       (첫 호출 초기값도 블록 파형 길이 ≥ 400). 따라서 루프는 data_buf_start_frame = max(현재, frame_idx) 와 같다.
         * 입력: frameIdx(목표 프레임)
         * 출력: dataBufStartFrame 갱신
         * 리턴: 없음
         */
        private fun popDataBufTillFrame(frameIdx: Int) {
            if (dataBufStartFrame < frameIdx) dataBufStartFrame = frameIdx // L327–331: while start < idx: start += 1
        }

        /**
         * 목적: PopDataToOutputBuf(L336–398) 중 구간 결과에 영향을 주는 부분
         * 기능: 시작점이면 새 출력 구간을 만들고, 현재 구간의 end_ms 와 시작/끝점 표시를 갱신한다(샘플 복사·경고 출력 생략).
         * 입력: startFrm(프레임), frmCnt(프레임 수), firstFrmIsStartPoint, lastFrmIsEndPoint, endPointIsSentEnd(샘플 수에만 쓰여 미사용)
         * 출력: outputDataBuf·dataBufStartFrame 갱신
         * 리턴: 없음
         */
        @Suppress("UNUSED_PARAMETER")
        private fun popDataToOutputBuf(startFrm: Int, frmCnt: Int, firstFrmIsStartPoint: Boolean, lastFrmIsEndPoint: Boolean, endPointIsSentEnd: Boolean) {
            popDataBufTillFrame(startFrm) // L344
            if (outputDataBuf.isEmpty() || firstFrmIsStartPoint) { // L362: 첫 구간이거나 시작점이면 새 구간
                val startMs = startFrm.toLong() * frameInMs // L365: start_ms = start_frm · frame_in_ms
                outputDataBuf += SpeechBuf(startMs, startMs) // L363–366: end_ms = start_ms
            }
            val curSeg = outputDataBuf[outputDataBuf.size - 1] // L368: 마지막 구간
            dataBufStartFrame += frmCnt // L393
            curSeg.endMs = (startFrm + frmCnt).toLong() * frameInMs // L394
            if (firstFrmIsStartPoint) curSeg.containSegStartPoint = true // L395–396
            if (lastFrmIsEndPoint) curSeg.containSegEndPoint = true // L397–398
        }

        /** 목적: OnSilenceDetected(L400–403). 입력: validFrame. 출력: 무음 확정 프레임·버퍼 시작 갱신. 리턴: 없음. */
        private fun onSilenceDetected(validFrame: Int) {
            lastestConfirmedSilenceFrame = validFrame // L401
            if (vadStateMachine == VadState.START_POINT_NOT_DETECTED) popDataBufTillFrame(validFrame) // L402–403
        }

        /** 목적: OnVoiceDetected(L407–409). 입력: validFrame. 출력: 음성 확정 프레임·출력 구간 끝 갱신. 리턴: 없음. */
        private fun onVoiceDetected(validFrame: Int) {
            latestConfirmedSpeechFrame = validFrame // L408
            popDataToOutputBuf(validFrame, 1, false, false, false) // L409
        }

        /** 목적: OnVoiceStart(L411–423). 입력: startFrame, fakeResult. 출력: 확정 시작 프레임·새 출력 구간. 리턴: 없음. */
        private fun onVoiceStart(startFrame: Int, fakeResult: Boolean) {
            if (confirmedStartFrame == -1) confirmedStartFrame = startFrame // L414–417(-1 이 아니면 원본은 경고만 출력)
            if (!fakeResult && vadStateMachine == VadState.START_POINT_NOT_DETECTED) { // L419–422
                popDataToOutputBuf(confirmedStartFrame, 1, true, false, false) // L423: 시작점
            }
        }

        /** 목적: OnVoiceEnd(L425–437). 입력: endFrame, fakeResult, isLastFrame. 출력: 끝점 확정·출력 구간 종료. 리턴: 없음. */
        private fun onVoiceEnd(endFrame: Int, fakeResult: Boolean, isLastFrame: Boolean) {
            for (t in latestConfirmedSpeechFrame + 1 until endFrame) onVoiceDetected(t) // L426–427: 사이 프레임 음성 확정
            if (confirmedEndFrame == -1) confirmedEndFrame = endFrame // L430–433(-1 이 아니면 원본은 경고만 출력)
            if (!fakeResult) { // L434
                popDataToOutputBuf(confirmedEndFrame, 1, false, true, isLastFrame) // L436: 끝점
            }
            numberEndTimeDetected += 1 // L437
        }

        /** 목적: MaybeOnVoiceEndIfLastFrame(L439–442). 입력: isFinalFrame, curFrmIdx. 출력: 마지막 프레임이면 구간 종료. 리턴: 없음. */
        private fun maybeOnVoiceEndIfLastFrame(isFinalFrame: Boolean, curFrmIdx: Int) {
            if (isFinalFrame) { // L440
                onVoiceEnd(curFrmIdx, false, true) // L441
                vadStateMachine = VadState.END_POINT_DETECTED // L442
            }
        }

        /** 목적: LatencyFrmNumAtStartPoint(L447–451). 리턴: 창 프레임 수 + (do_extend 면 lookback 프레임 수). */
        private fun latencyFrmNumAtStartPoint(): Int {
            var vadLatency = windowsDetector.winSizeFrame // L448
            if (doExtend != 0) vadLatency += pyIntDiv(lookbackTimeStartPoint, frameInMs) // L449–450
            return vadLatency // L451
        }

        /**
         * 목적: GetFrameState(L453–499)
         * 기능: dB 가 decibel_thres 미만이면 무음(이때 원본처럼 DetectOneFrame 을 한 번 더 부름, L458–461).
         *       아니면 noise = ln(sil)·ratio, speech = ln(1 − sil), exp(speech) ≥ exp(noise) + speech_noise_thres 이고
         *       snr·dB 임계를 넘으면 음성, 그 외 무음. 확률 조건에서 무음이면 잡음 평균 dB 를 갱신한다.
         * 입력: t(전역 프레임)
         * 출력: 잡음 평균 갱신(필요 시 창 상태 갱신)
         * 리턴: FrameState
         */
        private fun getFrameState(t: Int): FrameState {
            val curDecibel = decibel[t] // L455
            val curSnr = curDecibel - noiseAverageDecibel // L456
            if (curDecibel < decibelThres) { // L458
                detectOneFrame(FrameState.SIL, t, false) // L460: 원본 그대로 이 프레임을 한 번 더 창에 넣음
                return FrameState.SIL // L461
            }
            // L466–475: sil_pdf_ids = [0] → sum_score = scores[0][t − idx_pre_chunk][0] (float32 → float64)
            val silScore = scores[scoresBase + (t - idxPreChunk)].toDouble() // 무음 확률
            val noiseProb = ln(silScore) * speech2NoiseRatio // L473
            val speechProb = ln(1.0 - silScore) // L474–476: total_score(1.0) − sum_score
            val frameState: FrameState // 결과
            if (exp(speechProb) >= exp(noiseProb) + speechNoiseThres) { // L484
                frameState = if (curSnr >= snrThres && curDecibel >= decibelThres) FrameState.SPEECH else FrameState.SIL // L485–488
            } else {
                frameState = FrameState.SIL // L490
                noiseAverageDecibel = if (noiseAverageDecibel < -99.9) { // L491
                    curDecibel // L492
                } else {
                    (curDecibel + noiseAverageDecibel * (NOISE_FRAME_NUM_USED_FOR_SNR - 1)) / NOISE_FRAME_NUM_USED_FOR_SNR // L494–497
                }
            }
            return frameState // L499
        }

        /** 목적: DetectCommonFrames(L554–562). 기능: 블록 프레임을 is_final_frame=False 로 처리. 입력/출력/리턴: 없음(상태 갱신). */
        private fun detectCommonFrames() {
            if (vadStateMachine == VadState.END_POINT_DETECTED) return // L555–556(idx_pre_chunk 도 갱신 안 함, 원본 그대로)
            for (i in nnEvalBlockSize - 1 downTo 0) { // L557
                val frameState = getFrameState(frmCnt - 1 - i) // L559
                detectOneFrame(frameState, frmCnt - 1 - i, false) // L560
            }
            idxPreChunk += nnEvalBlockSize // L561: scores.shape[1]
        }

        /** 목적: DetectLastFrames(L564–575). 기능: 마지막 프레임만 is_final_frame=True 로 처리. 입력/출력/리턴: 없음(상태 갱신). */
        private fun detectLastFrames() {
            if (vadStateMachine == VadState.END_POINT_DETECTED) return // L565–566
            for (i in nnEvalBlockSize - 1 downTo 0) { // L567
                val frameState = getFrameState(frmCnt - 1 - i) // L569
                if (i != 0) detectOneFrame(frameState, frmCnt - 1 - i, false) // L570–571
                else detectOneFrame(frameState, frmCnt - 1, true) // L572–573: 마지막 프레임
            }
        }

        /**
         * 목적: E2EVadModel.DetectOneFrame(L577–704)
         * 기능: 창 전이(Sil2Speech / Speech2Sil / Speech2Speech / Sil2Sil)에 따라 구간 시작·연장·종료를 처리하고,
         *       다중 발화 모드에서 끝점이 나오면 ResetDetection 한다.
         * 입력: curFrmState(프레임 상태), curFrmIdx(전역 프레임), isFinalFrame(전체 마지막 프레임 여부)
         * 출력: 상태·출력 구간 갱신
         * 리턴: 없음
         */
        private fun detectOneFrame(curFrmState: FrameState, curFrmIdx: Int, isFinalFrame: Boolean) {
            var tmpCurFrmState = FrameState.INVALID // L580
            if (curFrmState == FrameState.SPEECH) { // L581
                tmpCurFrmState = if (abs(1.0) > fePriorThres) FrameState.SPEECH else FrameState.SIL // L582–585
            } else if (curFrmState == FrameState.SIL) { // L586
                tmpCurFrmState = FrameState.SIL // L587
            }
            val stateChange = windowsDetector.detectOneFrame(tmpCurFrmState) // L588
            val frmShiftInMs = frameInMs // L589
            val maxSingleFrames = maxSingleSegmentTime.toDouble() / frmShiftInMs // max_single_segment_time / frm_shift_in_ms (실수 나눗셈)
            when (stateChange) {
                AudioChangeState.SIL2SPEECH -> { // L590
                    continousSilenceFrameCount = 0 // L592
                    if (vadStateMachine == VadState.START_POINT_NOT_DETECTED) { // L595
                        val startFrame = maxOf(dataBufStartFrame, curFrmIdx - latencyFrmNumAtStartPoint()) // L596–598: lookback 시작
                        onVoiceStart(startFrame, false) // L599
                        vadStateMachine = VadState.IN_SPEECH_SEGMENT // L600
                        for (t in startFrame + 1..curFrmIdx) onVoiceDetected(t) // L601–602
                    } else if (vadStateMachine == VadState.IN_SPEECH_SEGMENT) { // L603
                        for (t in latestConfirmedSpeechFrame + 1 until curFrmIdx) onVoiceDetected(t) // L604–605
                        if ((curFrmIdx - confirmedStartFrame + 1).toDouble() > maxSingleFrames) { // L606–609: 최대 길이 초과
                            onVoiceEnd(curFrmIdx, false, false) // L610
                            vadStateMachine = VadState.END_POINT_DETECTED // L611
                        } else if (!isFinalFrame) { // L612
                            onVoiceDetected(curFrmIdx) // L613
                        } else {
                            maybeOnVoiceEndIfLastFrame(isFinalFrame, curFrmIdx) // L615
                        }
                    }
                }
                AudioChangeState.SPEECH2SIL -> { // L618
                    continousSilenceFrameCount = 0 // L619
                    if (vadStateMachine == VadState.IN_SPEECH_SEGMENT) { // L622
                        if ((curFrmIdx - confirmedStartFrame + 1).toDouble() > maxSingleFrames) { // L623–626
                            onVoiceEnd(curFrmIdx, false, false) // L627
                            vadStateMachine = VadState.END_POINT_DETECTED // L628
                        } else if (!isFinalFrame) { // L629
                            onVoiceDetected(curFrmIdx) // L630
                        } else {
                            maybeOnVoiceEndIfLastFrame(isFinalFrame, curFrmIdx) // L632
                        }
                    }
                }
                AudioChangeState.SPEECH2SPEECH -> { // L635
                    continousSilenceFrameCount = 0 // L636
                    if (vadStateMachine == VadState.IN_SPEECH_SEGMENT) { // L637
                        if ((curFrmIdx - confirmedStartFrame + 1).toDouble() > maxSingleFrames) { // L638–641
                            onVoiceEnd(curFrmIdx, false, false) // L643(L642 max_time_out 은 구간에 안 쓰여 생략)
                            vadStateMachine = VadState.END_POINT_DETECTED // L644
                        } else if (!isFinalFrame) { // L645
                            onVoiceDetected(curFrmIdx) // L646
                        } else {
                            maybeOnVoiceEndIfLastFrame(isFinalFrame, curFrmIdx) // L648
                        }
                    }
                }
                AudioChangeState.SIL2SIL -> { // L651
                    continousSilenceFrameCount += 1 // L652
                    if (vadStateMachine == VadState.START_POINT_NOT_DETECTED) { // L653
                        val singleTimeout = detectMode == 0 && // L656: kVadSingleUtteranceDetectMode
                            continousSilenceFrameCount * frmShiftInMs > maxStartSilenceTime // L657–660: 시작 무음 초과
                        if (singleTimeout || (isFinalFrame && numberEndTimeDetected == 0)) { // L655–661: 길이 0 결정
                            for (t in lastestConfirmedSilenceFrame + 1 until curFrmIdx) onSilenceDetected(t) // L662–663
                            onVoiceStart(0, true) // L664: 가짜 시작
                            onVoiceEnd(0, true, false) // L665: 가짜 끝
                            vadStateMachine = VadState.END_POINT_DETECTED // L666
                        } else {
                            if (curFrmIdx >= latencyFrmNumAtStartPoint()) { // L668
                                onSilenceDetected(curFrmIdx - latencyFrmNumAtStartPoint()) // L669
                            }
                        }
                    } else if (vadStateMachine == VadState.IN_SPEECH_SEGMENT) { // L670
                        if (continousSilenceFrameCount * frmShiftInMs >= maxEndSilFrameCntThresh) { // L671–674: 끝 무음 충족
                            var lookbackFrame = pyIntDiv(maxEndSilFrameCntThresh, frmShiftInMs) // L675
                            if (doExtend != 0) { // L676
                                lookbackFrame -= pyIntDiv(lookaheadTimeEndPoint, frmShiftInMs) // L677–679
                                lookbackFrame -= 1 // L680
                                lookbackFrame = maxOf(0, lookbackFrame) // L681
                            }
                            onVoiceEnd(curFrmIdx - lookbackFrame, false, false) // L682
                            vadStateMachine = VadState.END_POINT_DETECTED // L683
                        } else if ((curFrmIdx - confirmedStartFrame + 1).toDouble() > maxSingleFrames) { // L684–687
                            onVoiceEnd(curFrmIdx, false, false) // L688
                            vadStateMachine = VadState.END_POINT_DETECTED // L689
                        } else if (doExtend != 0 && !isFinalFrame) { // L690
                            if (continousSilenceFrameCount <= pyIntDiv(lookaheadTimeEndPoint, frmShiftInMs)) { // L691–693
                                onVoiceDetected(curFrmIdx) // L694: lookahead 안 무음은 구간에 포함
                            }
                        } else {
                            maybeOnVoiceEndIfLastFrame(isFinalFrame, curFrmIdx) // L696
                        }
                    }
                }
                AudioChangeState.NO_BEGIN, AudioChangeState.INVALID -> Unit // 원본에도 처리 분기 없음
            }
            // L700–704: 다중 발화 모드에서 끝점이 나오면 다음 구간을 위해 리셋
            if (vadStateMachine == VadState.END_POINT_DETECTED && detectMode == 1) {
                resetDetection() // L704
            }
        }
    }
}
