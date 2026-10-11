// 파일명: FireRedPostprocess.kt
// 목적: FireRedVAD(github FireRedTeam/FireRedVAD commit c30ec49) 프레임 확률 후처리의 Kotlin 포팅
// 기능: OFFLINE = fireredvad/core/vad_postprocessor.py `VadPostprocessor.process`(L38–55) + `decision_to_segment`(L57–79)
//       (공식 fireredvad/vad.py `FireRedVad.detect` L94–95 경로),
//       STREAM = fireredvad/core/stream_vad_postprocessor.py `StreamVadPostprocessor.process_one_frame`(L57–163)
//       + fireredvad/stream_vad.py `FireRedStreamVad.results_to_timestamps`(L163–187)(`detect_full` L107–140 경로).
//       프레임 hop 10 ms(core/constants.py FRAME_SHIFT_S 0.010, FRAME_LENGTH_S 0.025, FRAME_PER_SECONDS 100).
//       입력 확률은 float32 값을 double 로 바꿔 쓴다(원본 probs.tolist()). 임계값은 프리셋 Float 를 Python 리터럴과 같은
//       double(예 0.4f → "0.4" → 0.4)로 바꿔 비교한다. 세부 규칙은 tools/export/README_한국어.md §6.3·§6.4.
package com.cowork.stt_ondevice.vad.post // 후처리 패키지

import com.cowork.stt_ondevice.vad.FireRedMode // 후처리 모드
import com.cowork.stt_ondevice.vad.FireRedParams // FireRed 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import java.math.BigDecimal // 정확한 10진 반올림
import java.math.RoundingMode // HALF_EVEN

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: FireRedVAD 후처리 단일 진입점
 * 기능: 모드에 따라 offline(결정 배열 → 초 구간) 또는 stream(프레임별 상태기계 → 이벤트 → 초 구간) 후처리를 한다.
 */
object FireRedPostprocess {

    private const val FRAME_SHIFT_S = 0.010 // 프레임 이동(초), constants.py
    private const val FRAME_LENGTH_S = 0.025 // 프레임 길이(초), constants.py
    private const val FRAME_PER_SECONDS = 100 // int(1000 / 10), constants.py

    /**
     * 목적: 확률 → 음성 구간
     * 기능: OFFLINE 이면 offlineDecisions 결과를 decision_to_segment(d, wav_dur)로, STREAM 이면 streamFrames 결과를 프레임/100 초로 바꾼다.
     *       해당 모드에서 쓰이지 않는 파라미터(OFFLINE 의 padStartFrames, STREAM 의 mergeSilenceFrames·extendFrames)가 0 이 아니면
     *       조용히 무시하지 않고 예외를 던진다.
     * 입력: probs(프레임 확률, hop 10 ms), p(FireRed 파라미터),
     *       durSec(OFFLINE 의 wav_dur = N/16000; 끝까지 이어진 구간 끝을 min(T·0.01 + 0.025, dur) 로 자름. null 이면 자르지 않음. STREAM 은 미사용)
     * 출력: 쓰이지 않는 파라미터가 0 이 아니면 IllegalArgumentException
     * 리턴: 음성 구간 목록(초)
     */
    fun apply(probs: FloatArray, p: FireRedParams, durSec: Double? = null): List<SpeechSegment> = when (p.mode) {
        FireRedMode.OFFLINE -> decisionToSegment(offlineDecisions(probs, p), durSec) // vad.py L94–95
        FireRedMode.STREAM -> streamFrames(probs, p).map { // stream_vad.py L183–186: 초 = 프레임 / FRAME_PER_SECONDS
            SpeechSegment(it[0].toDouble() / FRAME_PER_SECONDS, it[1].toDouble() / FRAME_PER_SECONDS) // 나눗셈(곱셈 아님)
        }
    }

    // ---------------------------------------------------------------- OFFLINE

    /**
     * 목적: OFFLINE 최종 결정 배열 계산(VadPostprocessor.process, vad_postprocessor.py L38–55)
     * 기능: 평활 → 임계 → 상태기계 → 평활 창 시작 보정 → 짧은 무음 병합 → 확장 → 긴 구간 분할 순서로 프레임별 0/1 을 만든다.
     * 입력: probs(프레임 확률), p(OFFLINE 파라미터)
     * 출력: OFFLINE 이 아니거나 padStartFrames ≠ 0 이면 IllegalArgumentException
     * 리턴: 길이 T 의 0/1 결정(빈 입력이면 빈 배열, 원본 `if not raw_probs: return []`)
     */
    fun offlineDecisions(probs: FloatArray, p: FireRedParams): IntArray {
        require(p.mode == FireRedMode.OFFLINE) { "offlineDecisions 에 ${p.mode} 파라미터" } // 모드 확인
        require(p.padStartFrames == 0) { "OFFLINE 은 padStartFrames 를 쓰지 않음(0 이어야 함): ${p.padStartFrames}" } // 조용히 무시 금지
        if (probs.isEmpty()) return IntArray(0) // L39–40
        val raw = DoubleArray(probs.size) { probs[it].toDouble() } // probs.tolist() (float32 → double)
        val w = maxOf(1, p.smoothWindow) // L30 smooth_window_size = max(1, ·)
        val threshold = p.threshold.toString().toDouble() // Python double 0.4 와 같은 값
        val smoothed = smoothProb(raw, w) // L42
        val binary = IntArray(raw.size) { if (smoothed[it] >= threshold) 1 else 0 } // L44 _apply_threshold
        val decisions = stateMachine(binary, p.minSpeechFrames, p.minSilenceFrames) // L47
        val fixed = fixSmoothWindowStart(decisions, w) // L49
        val merged = mergeShortSilence(fixed, p.mergeSilenceFrames) // L50
        val extended = extendSpeech(merged, p.extendFrames) // L51
        return splitLongSpeech(extended, raw, p.maxSpeechFrames) // L52 (분할 뒤 병합 없음)
    }

    /**
     * 목적: _smooth_prob(L97–107) 포팅
     * 기능: w ≤ 1 이면 그대로. 아니면 i ≥ w−1 에서 Σ_{k=0}^{w−1} p[i−w+1+k]·(1/w)(np.convolve 'full' 앞 T 개),
     *       i < w−1 에서 mean(p[0..i]) 를 double 로 계산한다. 합산 순서가 numpy 와 달라 ulp 단위 차이가 날 수 있다
     *       (README §6.3: 골든 임계 여유 ≥ 1e-2 수준이라 판정 영향 없음).
     * 입력: p(double 확률), w(창 크기 ≥ 1)
     * 출력: 없음
     * 리턴: 평활 확률(새 배열)
     */
    private fun smoothProb(p: DoubleArray, w: Int): DoubleArray {
        if (w <= 1) return p.copyOf() // L98–99
        val k = 1.0 / w // kernel 값 np.ones(w)/w
        val out = DoubleArray(p.size) // 결과
        for (i in p.indices) { // 프레임마다
            if (i < w - 1) { // L105–106 앞쪽 누적 평균
                var s = 0.0 // 합
                for (j in 0..i) s += p[j] // p[0..i] 합
                out[i] = s / (i + 1) // 평균
            } else { // L103 합성곱
                var s = 0.0 // 합
                for (j in i - w + 1..i) s += p[j] * k // 창 안 가중합
                out[i] = s // 평활 값
            }
        }
        return out // 결과
    }

    /**
     * 목적: _smooth_preds_with_state_machine(L116–167) 포팅
     * 기능: SILENCE/POSSIBLE_SPEECH/SPEECH/POSSIBLE_SILENCE 상태로 min_speech·min_silence 를 적용한다.
     *       POSSIBLE_SPEECH 에서 t − start ≥ minSpeech 이면 SPEECH 로 바꾸고 [start, t) 를 1 로 채운다. 각 t 의 결정 = 전이 후 상태가
     *       SPEECH/POSSIBLE_SILENCE 인지. minSpeech·minSilence 가 모두 ≤ 0 이면 입력을 그대로 돌려준다.
     * 입력: binary(0/1), minSpeech, minSilence(프레임)
     * 출력: 없음
     * 리턴: 결정 배열
     */
    private fun stateMachine(binary: IntArray, minSpeech: Int, minSilence: Int): IntArray {
        if (minSpeech <= 0 && minSilence <= 0) return binary // L120–121
        val d = IntArray(binary.size) // L122
        var state = SILENCE // L123
        var speechStart = -1 // L124
        var silenceStart = -1 // L125
        for (t in binary.indices) { // L126
            val isSpeech = binary[t] == 1 // 현재 프레임 음성 여부
            when (state) { // L128–156 상태 전이
                SILENCE -> if (isSpeech) { state = POSSIBLE_SPEECH; speechStart = t } // L128–131
                POSSIBLE_SPEECH -> if (isSpeech) { // L133–138
                    if (t - speechStart >= minSpeech) { state = SPEECH; d.fill(1, speechStart, t) } // decisions[start:t] = 1
                } else { state = SILENCE; speechStart = -1 } // L139–141
                SPEECH -> if (!isSpeech) { state = POSSIBLE_SILENCE; silenceStart = t } // L143–146
                POSSIBLE_SILENCE -> if (!isSpeech) { // L148–153
                    if (t - silenceStart >= minSilence) { state = SILENCE; speechStart = -1 } // 무음 확정
                } else { state = SPEECH; silenceStart = -1 } // L154–156
            }
            d[t] = if (state == SPEECH || state == POSSIBLE_SILENCE) 1 else 0 // L159–166
        }
        return d // 결과
    }

    /**
     * 목적: _fix_smooth_window_start(L169–175) 포팅
     * 기능: 원래 결정에서 d[t−1]=0, d[t]=1 (t>0) 인 시작마다 새 배열의 [max(0, t−w), t) 를 1 로 채운다(시작을 w 프레임 앞당김).
     * 입력: d(결정), w(평활 창)
     * 출력: 없음
     * 리턴: 새 결정 배열
     */
    private fun fixSmoothWindowStart(d: IntArray, w: Int): IntArray {
        val out = d.copyOf() // new_decisions
        for (t in 1 until d.size) if (d[t - 1] == 0 && d[t] == 1) out.fill(1, maxOf(0, t - w), t) // 원래 배열 기준 판정
        return out // 결과
    }

    /**
     * 목적: _merge_short_silence_segments(L177–190) 포팅
     * 기능: merge ≤ 0 이면 그대로. 1→0 에서 무음 시작을 기록하고, 0→1 에서 무음 길이 < merge 이면 그 무음을 1 로 채운다.
     * 입력: d(결정), merge(프레임)
     * 출력: 없음
     * 리턴: 결정 배열
     */
    private fun mergeShortSilence(d: IntArray, merge: Int): IntArray {
        if (merge <= 0) return d // L178–179
        val out = d.copyOf() // L180
        var silenceStart = -1 // None
        for (t in 1 until d.size) { // t > 0 조건
            if (d[t - 1] == 1 && d[t] == 0 && silenceStart < 0) silenceStart = t // L183–184
            else if (d[t - 1] == 0 && d[t] == 1 && silenceStart >= 0) { // L185
                if (t - silenceStart < merge) out.fill(1, silenceStart, t) // L186–188
                silenceStart = -1 // L189
            }
        }
        return out // 결과
    }

    /**
     * 목적: _extend_speech_segments(L206–215) 포팅
     * 기능: e ≤ 0 이면 그대로. np.convolve(d, ones(2e+1), 'same') > 0 → 각 1 프레임의 앞뒤 e 프레임까지 1 로(배열 범위 안).
     * 입력: d(결정), e(확장 프레임)
     * 출력: 없음
     * 리턴: 결정 배열
     */
    private fun extendSpeech(d: IntArray, e: Int): IntArray {
        if (e <= 0) return d // L210–211
        val out = IntArray(d.size) // 결과
        for (t in d.indices) if (d[t] == 1) out.fill(1, maxOf(0, t - e), minOf(d.size, t + e + 1)) // 중심 대칭 창 > 0
        return out // 결과
    }

    /**
     * 목적: _split_long_speech_segments(L217–230) + _find_split_points(L232–247) 포팅
     * 기능: decision_to_segment(d)(wav_dur 없음, 3 자리 반올림 초)로 구간을 만든 뒤 start_frame = int(start_s/0.01),
     *       end_frame = int(end_s/0.01)(부동소수 절사 그대로). end − start > max 이면 원 확률 probs[start:end](Python 슬라이스 →
     *       끝을 T 로 자름)에서 분할점을 찾아 그 프레임 1 개만 0 으로 만든다.
     * 입력: d(결정), probs(원 확률 double), maxSpeech(프레임)
     * 출력: 없음
     * 리턴: 새 결정 배열
     */
    private fun splitLongSpeech(d: IntArray, probs: DoubleArray, maxSpeech: Int): IntArray {
        val out = d.copyOf() // L218
        for (seg in decisionToSegment(d, null)) { // L219–220
            val startFrame = (seg.startSec / FRAME_SHIFT_S).toInt() // L221 int() 절사
            val endFrame = (seg.endSec / FRAME_SHIFT_S).toInt() // L222 int() 절사
            if (endFrame - startFrame > maxSpeech) { // L224
                val segProbs = probs.copyOfRange(minOf(startFrame, probs.size), minOf(endFrame, probs.size)) // L225 슬라이스(끝 T 로 클램프)
                for (sp in findSplitPoints(segProbs, maxSpeech)) out[startFrame + sp] = 0 // L226–229
            }
        }
        return out // 결과
    }

    /**
     * 목적: _find_split_points(L232–247) 포팅
     * 기능: start = 0 부터, 남은 길이 ≤ max 이면 중단, 아니면 창 [int(start + max/2), int(start + max)) 의 첫 최솟값(np.argmin)을
     *       분할점으로 추가하고 start = 분할점 + 1 을 반복한다.
     * 입력: probs(구간 확률), maxSpeech(프레임)
     * 출력: 없음
     * 리턴: 구간 내 분할점 인덱스 목록
     */
    private fun findSplitPoints(probs: DoubleArray, maxSpeech: Int): List<Int> {
        val points = ArrayList<Int>() // 결과
        val length = probs.size // 길이
        var start = 0 // L235
        while (start < length) { // L236
            if (length - start <= maxSpeech) break // L237–238
            val windowStart = (start + maxSpeech / 2.0).toInt() // L239 int(start + max/2) (Python 실수 나눗셈)
            val windowEnd = minOf((start + maxSpeech.toDouble()).toInt(), length) // L240 + 슬라이스 끝 클램프
            var minIndex = windowStart // np.argmin: 첫 최솟값
            for (i in windowStart + 1 until windowEnd) if (probs[i] < probs[minIndex]) minIndex = i // 엄격 비교(첫 위치 유지)
            points += minIndex // L244
            start = minIndex + 1 // L246
        }
        return points // 결과
    }

    /**
     * 목적: decision_to_segment(L57–79) 포팅
     * 기능: 연속 1 구간 [s, e) → (s·0.01, e·0.01). 끝까지 이어진 구간의 끝 = T·0.01 + 0.025(dur 가 있으면 min(·, dur)).
     *       모든 값은 Python round(x, 3)(double 의 정확한 10진값 half-even 반올림)과 같은 BigDecimal HALF_EVEN 으로 반올림한다.
     * 입력: d(결정), durSec(wav_dur 또는 null)
     * 출력: 없음
     * 리턴: 구간 목록(초)
     */
    private fun decisionToSegment(d: IntArray, durSec: Double?): List<SpeechSegment> {
        val out = ArrayList<SpeechSegment>() // 결과
        var speechStart = -1 // None
        for (t in d.indices) { // L60
            if (d[t] == 1 && speechStart < 0) speechStart = t // L61–62
            else if (d[t] == 0 && speechStart >= 0) { // L63
                out += SpeechSegment(round3(speechStart * FRAME_SHIFT_S), round3(t * FRAME_SHIFT_S)) // L66–67 + L78
                speechStart = -1 // L68
            }
        }
        if (speechStart >= 0) { // L69 끝까지 이어진 구간
            var end = d.size * FRAME_SHIFT_S + FRAME_LENGTH_S // L73
            if (durSec != null) end = minOf(end, durSec) // L74–75
            out += SpeechSegment(round3(speechStart * FRAME_SHIFT_S), round3(end)) // L76–78
        }
        return out // 결과
    }

    /** 목적: Python round(x, 3) 재현. 기능: double 의 정확한 10진값을 HALF_EVEN 으로 3 자리 반올림 후 가장 가까운 double. 입력: x. 리턴: 반올림 값. */
    private fun round3(x: Double): Double = BigDecimal(x).setScale(3, RoundingMode.HALF_EVEN).toDouble() // 정확 반올림

    // ---------------------------------------------------------------- STREAM

    /**
     * 목적: STREAM 프레임 구간 계산(StreamVadPostprocessor + results_to_timestamps)
     * 기능: 프레임마다 process_one_frame(L57–75)을 문자 그대로 실행해 시작/끝 이벤트를 모으고,
     *       results_to_timestamps(stream_vad.py L163–182)로 0-based (시작, 끝) 프레임 쌍을 만든다.
     *       시작 = max(0, speech_start_frame − 1), 끝 = max(0, speech_end_frame − 1), 끝 이벤트 없이 끝나면 끝 = T − 1.
     *       한 프레임이 시작·끝 이벤트를 모두 가지면 원본처럼 시작만 처리한다(if/elif).
     * 입력: probs(프레임 확률), p(STREAM 파라미터)
     * 출력: STREAM 이 아니거나 merge/extend ≠ 0 이면 IllegalArgumentException
     * 리턴: (시작, 끝) 프레임 쌍 목록(0-based, 끝 포함)
     */
    fun streamFrames(probs: FloatArray, p: FireRedParams): List<IntArray> {
        require(p.mode == FireRedMode.STREAM) { "streamFrames 에 ${p.mode} 파라미터" } // 모드 확인
        require(p.mergeSilenceFrames == 0 && p.extendFrames == 0) { // 조용히 무시 금지
            "STREAM 은 mergeSilenceFrames·extendFrames 를 쓰지 않음(0 이어야 함): ${p.mergeSilenceFrames}, ${p.extendFrames}" // 메시지
        }
        val events = streamEvents(probs, p) // 이벤트 프레임 결과
        val out = ArrayList<IntArray>() // frame_timestamps
        var start = -1 // L168
        var end = -1 // L168
        for (r in events) { // L169 (frame_idx 오름차순)
            if (r.isStart) { // L170
                start = maxOf(0, r.startFrame - 1) // L172
                end = -1 // L173
            } else if (r.isEnd) { // L174
                check(end == -1) { "FireRed stream: 끝 이벤트 순서 이상(frame ${r.frameIdx})" } // L175 assert
                end = maxOf(0, r.endFrame - 1) // L176
                out += intArrayOf(start, end) // L177
                start = -1; end = -1 // L178
            }
        }
        if (start != -1) out += intArrayOf(start, probs.size - 1) // L179–182 results[-1].frame_idx − 1 = T − 1
        return out // 결과
    }

    /**
     * 목적: StreamVadPostprocessor.process_one_frame(L57–75)·smooth_prob(L77–86)·state_transition(L91–163) 문자 그대로 포팅
     * 기능: 프레임 번호 1 부터, deque 이동 평균(누적합에 더하고 창 밖 값을 빼는 double 연산)으로 평활하고 ≥ 임계면 음성,
     *       원본 분기 순서·카운터 증가 위치 그대로 상태를 바꾼다. 시작·끝 이벤트가 있는 프레임만 돌려준다.
     *       smooth_window = max(1, w), pad_start = max(smooth_window, pad).
     * 입력: probs(프레임 확률), p(STREAM 파라미터)
     * 출력: 없음
     * 리턴: 이벤트 프레임 결과 목록(frame_idx 오름차순)
     */
    private fun streamEvents(probs: FloatArray, p: FireRedParams): List<StreamEvent> {
        val smoothWindow = maxOf(1, p.smoothWindow) // L36
        val padStart = maxOf(smoothWindow, p.padStartFrames) // L38
        val threshold = p.threshold.toString().toDouble() // Python double 임계
        val window = ArrayDeque<Double>() // smooth_window deque
        var windowSum = 0.0 // smooth_window_sum
        var state = SILENCE // L50
        var speechCnt = 0 // L51
        var silenceCnt = 0 // L52
        var hitMaxSpeech = false // L53
        var lastStart = -1 // last_speech_start_frame
        var lastEnd = -1 // last_speech_end_frame
        val out = ArrayList<StreamEvent>() // 이벤트
        for (idx in probs.indices) { // 프레임마다
            val frameCnt = idx + 1 // L60 frame_cnt += 1 (1-based)
            val raw = probs[idx].toDouble() // float32 → double
            val smoothed = if (smoothWindow <= 1) raw else { // L78–79
                window.addLast(raw); windowSum += raw // L80–81
                if (window.size > smoothWindow) windowSum -= window.removeFirst() // L82–84
                windowSum / window.size // L85
            }
            val isSpeech = smoothed >= threshold // L88–89
            var isStart = false // result.is_speech_start
            var isEnd = false // result.is_speech_end
            var startFrame = -1 // result.speech_start_frame
            var endFrame = -1 // result.speech_end_frame
            if (hitMaxSpeech) { // L92–96
                isStart = true; startFrame = frameCnt // 바로 새 시작
                lastStart = startFrame // L95
                hitMaxSpeech = false // L96
            }
            when (state) { // L98–161
                SILENCE -> if (isSpeech) { state = POSSIBLE_SPEECH; speechCnt += 1 } // L99–101
                else { silenceCnt += 1; speechCnt = 0 } // L102–104
                POSSIBLE_SPEECH -> if (isSpeech) { // L106–107
                    speechCnt += 1 // L108
                    if (speechCnt >= p.minSpeechFrames) { // L109
                        state = SPEECH // L110
                        isStart = true // L111
                        startFrame = maxOf(1, frameCnt - speechCnt + 1 - padStart, lastEnd + 1) // L112–114
                        lastStart = startFrame // L115
                        silenceCnt = 0 // L116
                    }
                } else { state = SILENCE; silenceCnt = 1; speechCnt = 0 } // L117–120
                SPEECH -> { // L122
                    speechCnt += 1 // L123
                    if (isSpeech) { // L124
                        silenceCnt = 0 // L125
                        if (speechCnt >= p.maxSpeechFrames) { // L126
                            hitMaxSpeech = true; speechCnt = 0 // L127–128
                            isEnd = true; endFrame = frameCnt; startFrame = lastStart // L129–131
                            lastStart = -1; lastEnd = endFrame // L132–133
                        }
                    } else { state = POSSIBLE_SILENCE; silenceCnt += 1 } // L134–136
                }
                POSSIBLE_SILENCE -> { // L138
                    speechCnt += 1 // L139
                    if (isSpeech) { // L140
                        state = SPEECH; silenceCnt = 0 // L141–142
                        if (speechCnt >= p.maxSpeechFrames) { // L143
                            hitMaxSpeech = true; speechCnt = 0 // L144–145
                            isEnd = true; endFrame = frameCnt; startFrame = lastStart // L146–148
                            lastStart = -1; lastEnd = endFrame // L149–150
                        }
                    } else { // L152
                        silenceCnt += 1 // L153
                        if (silenceCnt >= p.minSilenceFrames) { // L154
                            state = SILENCE // L155
                            isEnd = true; endFrame = frameCnt; startFrame = lastStart // L156–158
                            lastEnd = endFrame; lastStart = -1 // L159–160
                            speechCnt = 0 // L161
                        }
                    }
                }
            }
            if (isStart || isEnd) out += StreamEvent(frameCnt, isStart, isEnd, startFrame, endFrame) // 이벤트 프레임만 보관
        }
        return out // 결과
    }

    /**
     * 목적: StreamVadFrameResult(stream_vad_postprocessor.py L8–17) 중 구간 변환에 필요한 필드
     * 기능: 1-based 프레임 번호, 시작/끝 이벤트 여부, 시작/끝 프레임(1-based, 없으면 −1)을 담는다.
     */
    private class StreamEvent(
        val frameIdx: Int, // frame_idx(1-based)
        val isStart: Boolean, // is_speech_start
        val isEnd: Boolean, // is_speech_end
        val startFrame: Int, // speech_start_frame
        val endFrame: Int, // speech_end_frame
    )

    // VadState(두 파일 공통 enum 값)
    private const val SILENCE = 0 // VadState.SILENCE
    private const val POSSIBLE_SPEECH = 1 // VadState.POSSIBLE_SPEECH
    private const val SPEECH = 2 // VadState.SPEECH
    private const val POSSIBLE_SILENCE = 3 // VadState.POSSIBLE_SILENCE
}
