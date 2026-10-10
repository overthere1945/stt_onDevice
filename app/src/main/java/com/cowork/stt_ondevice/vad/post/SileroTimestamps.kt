// 파일명: SileroTimestamps.kt
// 목적: Silero 계열(SILERO_* 8 프리셋, window 512)과 TEN_SHERPA(window 256)가 공유하는 "프레임 확률 → 음성 구간" 후처리
// 기능: silero-vad v6.2.3 `src/silero_vad/utils_vad.py` 의 `get_speech_timestamps_from_probs`(421~588 행,
//       `get_speech_timestamps` 가 402 행에서 그대로 호출하는 후처리)를 줄 단위로 포팅한다.
//       주석의 "L숫자" 는 위 원본 파일(pip silero-vad==6.2.3 설치본, 태그 v6.2.3 = 커밋 5cd7945)의 행 번호다.
//       - neg_threshold = max(threshold − 0.15, 0.01) (L475–476)
//       - temp_end(무음 후보 시작), prev_end/next_start(최대 길이 절단 위치), possible_ends(절단 후보 무음 목록)
//       - min_silence_at_max_speech = 98 ms, use_max_poss_sil_at_max_speech = true (라이브러리 기본값, L295–296)
//       - 마지막 구간 끝 = audio_length_samples(=totalSamples), 패딩·인접 구간 사이 무음 반분 규칙(L557–573)
//       return_seconds=False, step=1 경로만 쓴다(초 변환은 샘플/sampleRate 로 직접 함 — 원본의 반올림 초 변환 L575–579 는 쓰지 않음).
package com.cowork.stt_ondevice.vad.post // VAD 후처리 패키지

import com.cowork.stt_ondevice.vad.SileroParams // Silero 후처리 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간

// change(add)-hyungchul-2026-10-10-13:04
/**
 * 목적: silero-vad get_speech_timestamps 후처리의 Kotlin 포팅(골든과 샘플 단위 일치가 목표)
 * 기능: 창(window)마다 하나씩 나온 음성 확률 배열을 받아 원본과 같은 규칙으로 구간을 만든다.
 *
 * window 256(TEN_SHERPA) 동등성 설명:
 *   원본 함수는 창 크기를 sampling_rate 로만 정한다(L461: 16000 → 512, 그 외 → 256). Task 7 은 16 kHz·hop 256 인 TEN 확률을
 *   원본에 넣기 위해 sampling_rate=8000(→ window 256) 으로 부르고 ms·초 값을 모두 2배로 넘겼다(tools/golden/README_한국어.md §6.6).
 *   이때 원본 안의 모든 샘플 수는 8000·(2·ms)/1000 = 16000·ms/1000, 8000·(2·maxSec) − 256 − 2·pad = 16000·maxSec − 256 − 2·pad,
 *   min_silence_at_max_speech: 8000·(2·98)/1000 = 1568 = 16000·98/1000, cur_sample = 256·i, audio_length_samples = N(16 kHz) 으로
 *   "16 kHz, window 256" 을 그대로 계산한 값과 같다(모두 정수라 부동소수 오차도 없음).
 *   이 포팅은 창 크기를 windowSamples 로 직접 받고 sampleRate=16000 을 쓰므로 같은 결과를 낸다.
 */
object SileroTimestamps {

    /** 원본 L295/L432 기본값: 최대 길이 절단 후보로 인정할 최소 무음(ms). */
    private const val MIN_SILENCE_AT_MAX_SPEECH_MS = 98 // min_silence_at_max_speech 기본값

    /**
     * 목적: 구간 1개를 원본 dict({'start', 'end'})처럼 들고 다니는 가변 객체
     * 기능: 패딩 단계(L561–573)에서 이웃 구간의 start/end 를 고치므로 가변 필드로 둔다. 단위는 샘플.
     */
    private class Speech(var start: Long, var end: Long) // 시작·끝 샘플

    /**
     * 목적: 프레임 확률 → 음성 구간(초)
     * 기능: get_speech_timestamps_from_probs(L421–588)를 return_seconds=False, step=1 로 실행한 결과를 샘플/sampleRate 초로 돌려준다.
     * 입력: probs(창별 음성 확률, 창 i 의 시작 샘플 = windowSamples·i), windowSamples(창 크기: Silero 512, TEN 256),
     *       sampleRate(샘플레이트, 16000), totalSamples(원본 전체 샘플 수 N = audio_length_samples — probs.size·window 로 추정하지 말 것),
     *       p(SileroParams: threshold, minSpeechMs, minSilenceMs, speechPadMs, maxSpeechSec(무제한은 +∞)),
     *       minSilenceAtMaxSpeechMs(원본 min_silence_at_max_speech, 기본 98), useMaxPossSilAtMaxSpeech(원본 use_max_poss_sil_at_max_speech, 기본 true)
     * 출력: 없음
     * 리턴: 시작순 SpeechSegment 목록(startSec = 시작 샘플/sampleRate, endSec = 끝 샘플/sampleRate)
     */
    fun apply(
        probs: FloatArray,
        windowSamples: Int,
        sampleRate: Int,
        totalSamples: Int,
        p: SileroParams,
        minSilenceAtMaxSpeechMs: Int = MIN_SILENCE_AT_MAX_SPEECH_MS,
        useMaxPossSilAtMaxSpeech: Boolean = true,
    ): List<SpeechSegment> {
        // L461: window_size_samples = 512 if sampling_rate == 16000 else 256 → 호출자가 직접 지정(TEN 은 16 kHz 에서 256, 클래스 주석 참고)
        val windowSize = windowSamples.toLong() // 창 크기(샘플)
        // L462–463: audio_length_samples 가 None 이면 len(probs)·window 로 추정 → 여기서는 항상 호출자가 준 실제 N 사용
        val audioLengthSamples = totalSamples.toLong() // 원본 전체 샘플 수 N
        // 임계값: Float(예: 0.3f) 를 그대로 double 로 바꾸면 0.30000001192… 가 되어 Python 의 0.3 과 달라진다.
        // 프리셋 값은 10진 표기로 정의했으므로 Float 의 최단 10진 표기("0.3")를 double 로 읽어 Python 과 같은 값을 만든다.
        val threshold = p.threshold.toString().toDouble() // Python threshold 와 같은 double

        // L465: min_speech_samples = sampling_rate * min_speech_duration_ms / 1000 (float)
        val minSpeechSamples = (sampleRate.toLong() * p.minSpeechMs).toDouble() / 1000.0 // 최소 발화 길이(샘플, 실수)
        // L466: speech_pad_samples = sampling_rate * speech_pad_ms / 1000 (float)
        val speechPadSamples = (sampleRate.toLong() * p.speechPadMs).toDouble() / 1000.0 // 패딩(샘플, 실수)
        // L467: max_speech_samples = sampling_rate * max_speech_duration_s - window_size_samples - 2 * speech_pad_samples (무제한이면 +∞)
        val maxSpeechSamples = sampleRate.toDouble() * p.maxSpeechSec - windowSize - 2 * speechPadSamples // 최대 발화 길이(샘플)
        // L468: min_silence_samples = sampling_rate * min_silence_duration_ms / 1000 (float)
        val minSilenceSamples = (sampleRate.toLong() * p.minSilenceMs).toDouble() / 1000.0 // 구간을 끊는 최소 무음(샘플)
        // L469: min_silence_samples_at_max_speech = sampling_rate * min_silence_at_max_speech / 1000 (float)
        val minSilenceSamplesAtMaxSpeech = (sampleRate.toLong() * minSilenceAtMaxSpeechMs).toDouble() / 1000.0 // 절단 후보 최소 무음(샘플)

        var triggered = false // L471: 현재 음성 구간 안인지
        val speeches = mutableListOf<Speech>() // L472: 확정 구간 목록
        // L473: current_speech = {} → 빈 dict 는 curStart == null 로 표현. 'end' 는 append 직전에만 정해지므로 지역값으로 처리
        var curStart: Long? = null // 진행 중 구간의 'start'(없으면 빈 dict)

        // L475–476: neg_threshold 미지정 시 max(threshold - 0.15, 0.01) (SileroParams 에 별도 값이 없으므로 항상 이 기본식)
        val negThreshold = maxOf(threshold - 0.15, 0.01) // 음성 중 무음 판정 임계값
        var tempEnd = 0L // L477: 무음 후보 시작 샘플(0 = 없음, Python 의 falsy 0 과 같은 의미)
        var prevEnd = 0L // L478: 최대 길이 절단 시 쓸 직전 유효 무음 끝
        var nextStart = 0L // L478: 절단 후 다음 구간 시작 후보
        val possibleEnds = mutableListOf<LongArray>() // L479: (temp_end, sil_dur) 절단 후보 목록

        for (i in probs.indices) { // L481: for i, speech_prob in enumerate(speech_probs)
            val speechProb = probs[i].toDouble() // float32 확률 → double(Python 의 .item() 결과와 같은 값)
            val curSample = windowSize * i // L482: cur_sample = window_size_samples * i

            // L484–491: temp_end 이후 음성이 돌아오면, 충분히 긴 무음이면 후보로 기록하고 temp_end 해제
            if (speechProb >= threshold && tempEnd != 0L) { // L485
                val silDur = curSample - tempEnd // L486: 무음 길이
                if (silDur > minSilenceSamplesAtMaxSpeech) { // L487: 98 ms 초과 무음만
                    possibleEnds.add(longArrayOf(tempEnd, silDur)) // L488: 후보 추가
                }
                tempEnd = 0L // L489
                if (nextStart < prevEnd) { // L490
                    nextStart = curSample // L491
                }
            }

            // L493–497: 음성 시작
            if (speechProb >= threshold && !triggered) { // L494
                triggered = true // L495
                curStart = curSample // L496: current_speech['start'] = cur_sample
                continue // L497
            }

            // L499–534: 최대 발화 길이 도달 → 절단 위치 결정
            if (triggered && (curSample - curStart!!).toDouble() > maxSpeechSamples) { // L500
                if (useMaxPossSilAtMaxSpeech && possibleEnds.isNotEmpty()) { // L501
                    val best = possibleEnds.maxByOrNull { it[1] }!! // L502: 가장 긴 무음(동률이면 Python max 처럼 첫 번째)
                    prevEnd = best[0] // L502: prev_end
                    val dur = best[1] // L502: dur
                    speeches.add(Speech(curStart, prevEnd)) // L503–504: current_speech['end'] = prev_end; append
                    curStart = null // L505: current_speech = {}
                    nextStart = prevEnd + dur // L506

                    if (nextStart < prevEnd + curSample) { // L508: (원본 조건식 그대로)
                        curStart = nextStart // L509: 무음 뒤 음성이 이미 시작됨 → 다음 구간 시작
                    } else {
                        triggered = false // L511
                    }
                    prevEnd = 0L; nextStart = 0L; tempEnd = 0L // L512
                    possibleEnds.clear() // L513
                } else {
                    // L515–534: 기존 방식(use_max_poss_sil_at_max_speech=False) 또는 후보 없음
                    if (prevEnd != 0L) { // L516: 직전 유효 무음이 있으면 거기서 절단
                        speeches.add(Speech(curStart, prevEnd)) // L517–518
                        curStart = null // L519
                        if (nextStart < prevEnd) { // L520
                            triggered = false // L521
                        } else {
                            curStart = nextStart // L523
                        }
                        prevEnd = 0L; nextStart = 0L; tempEnd = 0L // L524
                        possibleEnds.clear() // L525
                    } else {
                        // L527: prev_end 없음 → 현재 샘플에서 강제 절단
                        speeches.add(Speech(curStart, curSample)) // L528–529
                        curStart = null // L530
                        prevEnd = 0L; nextStart = 0L; tempEnd = 0L // L531
                        triggered = false // L532
                        possibleEnds.clear() // L533
                        continue // L534
                    }
                }
            }

            // L536–555: 음성 중 무음 감지
            if (speechProb < negThreshold && triggered) { // L537
                if (tempEnd == 0L) { // L538: if not temp_end
                    tempEnd = curSample // L539
                }
                val silDurNow = curSample - tempEnd // L540

                if (!useMaxPossSilAtMaxSpeech && silDurNow > minSilenceSamplesAtMaxSpeech) { // L542
                    prevEnd = tempEnd // L543
                }

                if (silDurNow < minSilenceSamples) { // L545
                    continue // L546: 아직 짧은 무음 → 구간 유지
                } else {
                    val end = tempEnd // L548: current_speech['end'] = temp_end
                    if ((end - curStart!!).toDouble() > minSpeechSamples) { // L549: 최소 발화 길이 초과만
                        speeches.add(Speech(curStart, end)) // L550
                    }
                    curStart = null // L551
                    prevEnd = 0L; nextStart = 0L; tempEnd = 0L // L552
                    triggered = false // L553
                    possibleEnds.clear() // L554
                    continue // L555
                }
            }
        }

        // L557–559: 입력 끝에서 진행 중 구간이 있고 충분히 길면 끝 = audio_length_samples
        val lastStart = curStart // 진행 중 구간 시작
        if (lastStart != null && (audioLengthSamples - lastStart).toDouble() > minSpeechSamples) { // L557
            speeches.add(Speech(lastStart, audioLengthSamples)) // L558–559
        }

        // L561–573: 패딩 적용. 이웃 사이 무음이 2·pad 보다 짧으면 무음을 반으로 나눠 양쪽에 붙인다
        for (i in speeches.indices) { // L561
            val speech = speeches[i] // 현재 구간
            if (i == 0) { // L562
                speech.start = maxOf(0.0, speech.start - speechPadSamples).toLong() // L563: int(max(0, start - pad)) (0 이상이라 절사 = 내림)
            }
            if (i != speeches.size - 1) { // L564
                val next = speeches[i + 1] // 다음 구간
                val silenceDuration = next.start - speech.end // L565: 정수 무음 길이
                if (silenceDuration < 2 * speechPadSamples) { // L566
                    val half = Math.floorDiv(silenceDuration, 2L) // Python 정수 // 2 (음수여도 내림)
                    speech.end += half // L567
                    next.start = maxOf(0L, next.start - half) // L568
                } else {
                    speech.end = minOf(audioLengthSamples.toDouble(), speech.end + speechPadSamples).toLong() // L570: int(min(N, end + pad))
                    next.start = maxOf(0.0, next.start - speechPadSamples).toLong() // L571: int(max(0, start - pad))
                }
            } else {
                speech.end = minOf(audioLengthSamples.toDouble(), speech.end + speechPadSamples).toLong() // L573: 마지막 구간 끝 패딩(N 으로 clamp)
            }
        }

        // L575–583: return_seconds=False, step=1 → 샘플 그대로. 앱 공통 형식(초)으로만 바꾼다
        val sr = sampleRate.toDouble() // 샘플레이트(실수)
        return speeches.map { SpeechSegment(it.start / sr, it.end / sr) } // 초 = 샘플 / sampleRate
    }
}
