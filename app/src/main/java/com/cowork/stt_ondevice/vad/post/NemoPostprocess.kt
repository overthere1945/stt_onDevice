// 파일명: NemoPostprocess.kt
// 목적: NVIDIA NeMo 3.0.0 frame VAD 후처리(nemo/collections/asr/parts/utils/vad_utils.py)의 Kotlin 포팅
// 기능: generate_vad_segment_table_per_tensor(L719–736) = binarization(L529–594) → filtering(L622–689) → torch.sort(dim 0) 를 재현한다.
//       scale "absolute"(cal_vad_onset_offset L507–526: onset/offset 그대로), filter_speech_first 는 Boolean → 1.0/0.0(prepare_gen_segment_table).
//       NeMo 는 구간을 torch.tensor(float32)로 저장하고 확률·길이 비교도 float32 로 하므로(파이썬 실수는 텐서 dtype 으로 맞춰짐,
//       torch 2.8 에서 eager·TorchScript 모두 확인) 구간 값은 FloatArray[start, end] 로 다루고 시각 계산만 double 로 한다.
//       세부 규칙은 tools/export/README_한국어.md §7.3. 결과 구간은 float32 값을 그대로 double 로 바꾼 값이다.
package com.cowork.stt_ondevice.vad.post // 후처리 패키지

import com.cowork.stt_ondevice.vad.NemoParams // NeMo 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: NeMo frame VAD 후처리 단일 진입점
 * 기능: 프레임 확률(hop 초 간격)을 이진화·필터링해 음성 구간을 만든다.
 */
object NemoPostprocess {

    /**
     * 목적: 확률 → 음성 구간(generate_vad_segment_table_per_tensor L719–736)
     * 기능: binarize → filter → (비어 있으면 빈 목록) → 시작 열·끝 열을 **각각 독립적으로** 오름차순 정렬(torch.sort(·, 0)) 한다.
     * 입력: probs(프레임 확률, float32), hopSec(프레임 간격 = frame_length_in_sec, MarbleNet 0.02), p(NeMo 파라미터)
     * 출력: 없음
     * 리턴: 음성 구간 목록(float32 값을 double 로)
     */
    fun apply(probs: FloatArray, hopSec: Double, p: NemoParams): List<SpeechSegment> {
        val segs = filter(binarize(probs, hopSec, p), p) // L726–727
        if (segs.isEmpty()) return emptyList() // L729–730
        val starts = segs.map { it[0] }.sorted() // L732 torch.sort(dim 0): 시작 열 정렬
        val ends = segs.map { it[1] }.sorted() // 끝 열 정렬(열마다 독립)
        return starts.indices.map { SpeechSegment(starts[it].toDouble(), ends[it].toDouble()) } // float32 → double
    }

    /**
     * 목적: binarization(L529–594) 포팅
     * 기능: 비음성에서 p > onset(float32 비교)이면 start = i·hop, 음성으로. 음성에서 p < offset 이면
     *       i·hop + pad_offset > max(0, start − pad_onset) 일 때 [max(0, start − pad_onset), i·hop + pad_offset] 추가(float32 저장),
     *       start = i·hop, 비음성으로. 끝까지 음성이면 [max(0, start − pad_onset), (T−1)·hop + pad_offset] 를 조건 없이 추가하고
     *       merge_overlap_segment 로 병합한다.
     * 입력: probs(프레임 확률), hopSec(프레임 간격), p(파라미터)
     * 출력: 없음
     * 리턴: [start, end](float32) 구간 목록
     */
    fun binarize(probs: FloatArray, hopSec: Double, p: NemoParams): List<FloatArray> {
        var speech = false // L559
        var start = 0.0 // L560
        val segs = ArrayList<FloatArray>() // L563 speech_segments
        for (i in probs.indices) { // L565
            if (speech) { // L567
                if (probs[i] < p.offset) { // L569 float32 비교
                    val s = maxOf(0.0, start - p.padOnsetSec) // max(0, start − pad_onset)
                    val e = i * hopSec + p.padOffsetSec // i·frame + pad_offset (double)
                    if (e > s) segs += floatArrayOf(s.toFloat(), e.toFloat()) // L570–574 torch.tensor → float32
                    start = i * hopSec // L576
                    speech = false // L577
                }
            } else if (probs[i] > p.onset) { // L582 float32 비교
                start = i * hopSec // L583
                speech = true // L584
            }
        }
        if (speech) { // L587 끝까지 음성
            val last = probs.size - 1 // 루프 변수 i 의 마지막 값
            segs += floatArrayOf(maxOf(0.0, start - p.padOnsetSec).toFloat(), (last * hopSec + p.padOffsetSec).toFloat()) // L588–589
        }
        return mergeOverlap(segs) // L592
    }

    /**
     * 목적: filtering(L622–689) 포팅
     * 기능: filter_speech_first 이면 짧은 발화 제거(min_on > 0) 후 짧은 무음 메우기(min_off > 0: 간격 구간 중 길이 < min_off 인 것을
     *       구간으로 추가하고 병합), 아니면 순서를 바꿔 적용한다. 빈 입력은 그대로 돌려준다.
     * 입력: segs(binarize 결과), p(파라미터)
     * 출력: 없음
     * 리턴: [start, end](float32) 구간 목록
     */
    fun filter(segs: List<FloatArray>, p: NemoParams): List<FloatArray> {
        if (segs.isEmpty()) return segs // L649–650
        var out = segs // speech_segments
        if (p.filterSpeechFirst) { // L656
            if (p.minDurationOnSec > 0.0) out = filterShort(out, p.minDurationOnSec) // L658–659
            if (p.minDurationOffSec > 0.0) out = fillShortGaps(out, p.minDurationOffSec) // L661–672
        } else { // L673
            if (p.minDurationOffSec > 0.0) out = fillShortGaps(out, p.minDurationOffSec) // L674–685
            if (p.minDurationOnSec > 0.0) out = filterShort(out, p.minDurationOnSec) // L686–687
        }
        return out // 결과
    }

    /**
     * 목적: 짧은 무음 메우기(filtering 의 get_gap_segments → remove_segments → cat → merge_overlap_segment 묶음)
     * 기능: 정렬된 이웃 사이 간격 [end_k, start_{k+1}] 중 filter_short_segments(간격, min_off) 로 남는(긴) 간격과 값이 같은 행을 모두 빼고
     *       (remove_segments, L597–608), 남은 짧은 간격을 구간 뒤에 붙여 병합한다.
     * 입력: segs(구간), minOff(초)
     * 출력: 없음
     * 리턴: 병합된 구간
     */
    private fun fillShortGaps(segs: List<FloatArray>, minOff: Double): List<FloatArray> {
        val gaps = gapSegments(segs) // L663 non_speech_segments
        val longGaps = filterShort(gaps, minOff) // filter_short_segments(non_speech, min_off)
        var short = gaps // remove_segments 의 original_segments
        for (y in longGaps) short = short.filterNot { it[0] == y[0] && it[1] == y[1] } // L606 eq(y).all(dim=1) 인 행 제거
        return mergeOverlap(segs + short) // L669–672 cat → merge
    }

    /**
     * 목적: filter_short_segments(L488–496) 포팅
     * 기능: end − start(float32 뺄셈) ≥ threshold(float32 로 맞춤) 인 행만 남긴다.
     * 입력: segs(구간), threshold(초)
     * 출력: 없음
     * 리턴: 남은 구간
     */
    private fun filterShort(segs: List<FloatArray>, threshold: Double): List<FloatArray> {
        val th = threshold.toFloat() // 텐서 dtype(float32)으로 맞춘 임계
        return segs.filter { it[1] - it[0] >= th } // float32 비교
    }

    /**
     * 목적: get_gap_segments(L611–619) 포팅
     * 기능: 시작 기준 정렬 후 (end_k, start_{k+1}) 쌍을 만든다.
     * 입력: segs(구간)
     * 출력: 없음
     * 리턴: 간격 구간 목록(길이 n−1, n ≤ 1 이면 빈 목록)
     */
    private fun gapSegments(segs: List<FloatArray>): List<FloatArray> {
        val sorted = segs.sortedBy { it[0] } // 시작 기준 정렬(안정 정렬)
        return (0 until maxOf(0, sorted.size - 1)).map { floatArrayOf(sorted[it][1], sorted[it + 1][0]) } // column_stack(ends[:-1], starts[1:])
    }

    /**
     * 목적: merge_overlap_segment(L464–484) 포팅
     * 기능: 0·1 개면 그대로. 시작 기준 정렬 후 경계 b_k = (end_k ≥ start_{k+1})(float32)를 구해,
     *       앞 경계가 없는 행의 시작(head)과 뒤 경계가 없는 행의 끝(tail)을 짝지운다(누적 최댓값이 아니라 원본 식 그대로).
     *       torch sort 의 같은 시작값 순서는 안정 정렬로 둔다(torch 2.8 CPU 에서 안정 정렬 결과 확인).
     * 입력: segs(구간)
     * 출력: 없음
     * 리턴: 병합된 구간
     */
    private fun mergeOverlap(segs: List<FloatArray>): List<FloatArray> {
        if (segs.size <= 1) return segs // L470–475 [0], [0,2], [1,2] 형상은 그대로
        val s = segs.sortedBy { it[0] } // L477 시작 기준 정렬
        val boundary = BooleanArray(s.size - 1) { s[it][1] >= s[it + 1][0] } // L478 merge_boundary
        val heads = s.indices.filter { it == 0 || !boundary[it - 1] }.map { s[it][0] } // L479–480 head(앞에 False 패딩)
        val tails = s.indices.filter { it == s.size - 1 || !boundary[it] }.map { s[it][1] } // L481–482 tail(뒤에 False 패딩)
        return heads.indices.map { floatArrayOf(heads[it], tails[it]) } // L483 stack
    }
}
