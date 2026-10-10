// 파일명: PyannoteBinarize.kt
// 목적: pyannote 계열(PYA3_DEFAULT, PYA2_DIHARD3, PYA2_AMI, PYA2_VOXCONVERSE) 의 "프레임 speech 점수 → 음성 구간" 후처리
// 기능: pyannote.audio 3.4.0 `pyannote/audio/utils/signal.py` 의 `Binarize.__call__`(L254–317) 를 pad_onset = pad_offset = 0,
//       단일 클래스(speech) 로 줄 단위 포팅한다. 구간 병합은 pyannote.core 5.0.0 의
//       `Annotation.support`(annotation.py L1311–1364) → `Timeline.support`(timeline.py L844–879) → `Timeline.support_iter`(L799–842),
//       구간 비교·합집합·간격은 `Segment.__bool__`(segment.py L156–169), `duration`(L177–180), `__or__`(L277–305), `__xor__`(L307–332) 를 따른다.
//       주석의 "signal.py L숫자" 등은 tools/golden/.venv(pip pyannote.audio==3.4.0, pyannote.core==5.0.0 설치본)의 행 번호다.
//       - 히스테리시스: 활성 중 score < offset 이면 종료, 비활성 중 score > onset 이면 시작(둘 다 엄격 비교)
//       - min_duration_off > 0 이면 간격 < min_duration_off(또는 1 µs 이하) 인 이웃 구간 병합 → 그 다음 길이 < min_duration_on 구간 삭제
//       - 길이 1 µs 이하 구간은 Annotation 에 추가되지 않음(annotation.py L873–875)
package com.cowork.stt_ondevice.vad.post // VAD 후처리 패키지

import com.cowork.stt_ondevice.vad.PyannoteParams // pyannote 후처리 파라미터
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간

// change(add)-hyungchul-2026-10-10-13:25
/**
 * 목적: pyannote Binarize 후처리의 Kotlin 포팅(골든과 정확히 일치가 목표)
 * 기능: 프레임별 speech 점수와 프레임 중앙 시각을 받아 원본과 같은 규칙으로 구간(초)을 만든다.
 *
 * 수치 기준:
 *   - 원본 점수는 numpy float32 스칼라, onset/offset 은 Python float 이다. numpy 1.26 은 스칼라끼리 비교할 때 float64 로 승격하므로
 *     (tools/golden/.venv 에서 `np.result_type(np.float32 스칼라, 0.767)` = float64 확인) 점수를 double 로 바꿔 double 임계값과 비교한다.
 *     임계값은 Float(예: 0.767f) 의 최단 10진 표기("0.767")를 double 로 읽어 Python 의 0.767 과 같은 값을 만든다.
 *   - 프레임 시각은 호출자가 준 값을 그대로 쓴다(골든 JSON frameTimesSec 또는 같은 식 `0.5·(s + (s + 0.0619375))`, s = i·0.016875).
 */
object PyannoteBinarize {

    /** pyannote.core segment.py L151(set_precision(None) 기본 경로): SEGMENT_PRECISION = 1e-6(1 µs). 이 길이 이하 구간은 "비어 있음". */
    private const val SEGMENT_PRECISION = 1e-6 // 구간 비어 있음 판정 기준(초)

    /**
     * 목적: pyannote.core Segment 의 최소 표현
     * 기능: 시작·끝(초)과 원본의 비어 있음(__bool__)·길이(duration) 규칙을 제공한다.
     */
    private data class Seg(val start: Double, val end: Double) { // 시작·끝(초)
        /** segment.py L169: `(end - start) > SEGMENT_PRECISION` 이면 비어 있지 않음. */
        val nonEmpty: Boolean get() = (end - start) > SEGMENT_PRECISION // 비어 있지 않은지

        /** segment.py L177–180: 비어 있지 않으면 end - start, 비어 있으면 0. */
        val duration: Double get() = if (nonEmpty) end - start else 0.0 // 길이(초)
    }

    /**
     * 목적: 프레임 점수 → 음성 구간(초)
     * 기능: Binarize(onset, offset, min_duration_on, min_duration_off, pad 0)(scores) 와 같은 구간을 시작순으로 돌려준다.
     * 입력: scores(프레임별 speech 점수), frameTimesSec(프레임 i 의 중앙 시각(초), scores 와 같은 길이),
     *       p(PyannoteParams: onset, offset, minDurationOnSec, minDurationOffSec)
     * 출력: 없음
     * 리턴: 시작순 SpeechSegment 목록(경계 = 프레임 중앙 시각)
     */
    fun apply(scores: FloatArray, frameTimesSec: DoubleArray, p: PyannoteParams): List<SpeechSegment> {
        require(scores.size == frameTimesSec.size) { "점수 수(${scores.size}) ≠ 프레임 시각 수(${frameTimesSec.size})" } // 길이 불일치는 호출 오류
        // 원본은 프레임 0 개면 timestamps[0] 에서 IndexError → 포팅은 구간 없음으로 처리
        if (scores.isEmpty()) return emptyList() // 빈 입력
        // signal.py L245: self.onset = onset (Float 최단 10진 표기 → Python 과 같은 double)
        val onset = p.onset.toString().toDouble() // 시작 임계값
        // signal.py L246: self.offset = offset or onset (0.0 은 Python 에서 거짓이므로 onset 을 씀)
        val offset = p.offset.toString().toDouble().let { if (it == 0.0) onset else it } // 종료 임계값
        val timestamps = frameTimesSec // signal.py L270: timestamps = [frames[i].middle ...] → 호출자가 준 값 그대로

        val active = mutableListOf<Seg>() // signal.py L273: active = Annotation() (단일 라벨이라 구간 목록으로 충분)
        // signal.py L280–281: 초기 상태
        var start = timestamps[0] // 현재(또는 다음) 구간 시작 시각
        var isActive = scores[0].toDouble() > onset // 첫 프레임이 onset 초과면 활성
        // signal.py L283: for t, y in zip(timestamps[1:], k_scores[1:])
        for (i in 1 until scores.size) { // 둘째 프레임부터
            val t = timestamps[i] // 현재 프레임 시각
            val y = scores[i].toDouble() // 현재 점수(float32 → double, numpy 승격과 같음)
            if (isActive) { // signal.py L286: 활성 중
                if (y < offset) { // L288: 활성 → 비활성 전환
                    addTrack(active, Seg(start - 0.0, t + 0.0)) // L289–290: Segment(start - pad_onset, t + pad_offset), pad 0
                    start = t // L291
                    isActive = false // L292
                }
            } else { // L295: 비활성 중
                if (y > onset) { // L297: 비활성 → 활성 전환
                    start = t // L298
                    isActive = true // L299
                }
            }
        }
        // signal.py L302–304: 끝까지 활성이면 마지막 구간 추가. t 는 루프의 마지막 시각(= 마지막 프레임 시각).
        // 원본은 프레임이 1 개면 t 가 정의되지 않아 UnboundLocalError → 포팅은 [t0, t0](길이 0, 추가 안 됨)으로 처리
        if (isActive) { // 끝까지 활성
            addTrack(active, Seg(start - 0.0, timestamps[scores.size - 1] + 0.0)) // [start, 마지막 프레임 시각]
        }

        // signal.py L308–309: pad 가 0 이므로 min_duration_off > 0 일 때만 support(collar=min_duration_off)
        var result: List<Seg> = active // 현재 구간 목록
        if (p.minDurationOffSec > 0.0) { // 짧은 무음 메움
            // annotation.py L1355: label_timeline(label) → Timeline(정렬·중복 제거·빈 구간 제외)
            val timeline = toTimeline(result) // 라벨 타임라인
            // annotation.py L1358: timeline = timeline.support(collar)
            val merged = toTimeline(supportIter(timeline, p.minDurationOffSec)) // collar 병합
            // annotation.py L1361: for segment in timeline.support(): (collar 0 으로 한 번 더, 원본 그대로)
            result = toTimeline(supportIter(merged, 0.0)) // 최종 지지 구간
        }

        // signal.py L312–315: min_duration_on > 0 이면 길이 < min_duration_on 구간 삭제(병합 뒤에 삭제)
        if (p.minDurationOnSec > 0.0) { // 짧은 발화 제거
            result = result.filter { !(it.duration < p.minDurationOnSec) } // L314: segment.duration < min_duration_on 이면 삭제
        }
        return result.map { SpeechSegment(it.start, it.end) } // 초 단위 구간으로 변환(itertracks 순서 = 시작순)
    }

    /**
     * 목적: Annotation.__setitem__ 의 빈 구간 무시 규칙 적용
     * 기능: annotation.py L873–875 "do not add empty track" — 비어 있는 구간은 추가하지 않는다.
     * 입력: active(구간 목록), seg(추가할 구간)
     * 출력: active 에 seg 추가(비어 있지 않을 때만)
     * 리턴: 없음
     */
    private fun addTrack(active: MutableList<Seg>, seg: Seg) {
        if (!seg.nonEmpty) return // L874: if not segment: return
        active += seg // 트랙 추가
    }

    /**
     * 목적: pyannote.core Timeline 생성 규칙
     * 기능: timeline.py L150–155: 비어 있지 않은 구간만 set 으로 모은 뒤 SortedList(시작, 끝 순) 로 정렬한다.
     * 입력: segs(구간 목록)
     * 출력: 없음
     * 리턴: 중복 제거·정렬된 구간 목록
     */
    private fun toTimeline(segs: List<Seg>): List<Seg> =
        segs.filter { it.nonEmpty }.distinct().sortedWith(compareBy<Seg>({ it.start }, { it.end })) // L150 set + L155 SortedList(dataclass order)

    /**
     * 목적: Timeline.support_iter(collar) 포팅
     * 기능: timeline.py L799–842: 정렬된 구간을 차례로 보며, 현재 병합 구간과의 간격이 비어 있거나(겹침·1 µs 이하)
     *       간격 길이 < collar 이면 합치고, 아니면 현재 병합 구간을 내보내고 새로 시작한다.
     * 입력: timeline(정렬·중복 제거된 구간), collar(초)
     * 출력: 없음
     * 리턴: 병합된 구간 목록
     */
    private fun supportIter(timeline: List<Seg>, collar: Double): List<Seg> {
        if (timeline.isEmpty()) return emptyList() // L808–809: 빈 타임라인 → 빈 결과
        val out = mutableListOf<Seg>() // 내보낸 구간
        var newSegment = timeline[0] // L821: 첫 구간으로 시작
        for (segment in timeline) { // L823: 첫 구간 포함 전체 순회(원본 그대로)
            val possibleGap = xor(segment, newSegment) // L827: segment ^ new_segment
            if (!possibleGap.nonEmpty || possibleGap.duration < collar) { // L828: 간격 없음 또는 간격 < collar
                newSegment = or(newSegment, segment) // L830: new_segment |= segment
            } else { // L834: 간격 ≥ collar
                out += newSegment // L835: yield new_segment
                newSegment = segment // L839: 다음 구간으로 새로 시작
            }
        }
        out += newSegment // L841–842: 마지막 병합 구간
        return out // 결과
    }

    /**
     * 목적: Segment.__xor__(간격) 포팅
     * 기능: segment.py L307–332: 두 구간 모두 비어 있지 않아야 하며, 간격 = Segment(min(끝), max(시작)).
     * 입력: a, b(구간)
     * 출력: 없음
     * 리턴: 간격 구간(겹치면 끝 < 시작이라 비어 있음)
     */
    private fun xor(a: Seg, b: Seg): Seg {
        check(a.nonEmpty && b.nonEmpty) { "빈 구간의 간격은 정의되지 않음" } // L325–328: 원본도 ValueError
        return Seg(minOf(a.end, b.end), maxOf(a.start, b.start)) // L330–332
    }

    /**
     * 목적: Segment.__or__(합집합) 포팅
     * 기능: segment.py L277–305: 한쪽이 비어 있으면 다른 쪽, 아니면 Segment(min(시작), max(끝)).
     * 입력: a, b(구간)
     * 출력: 없음
     * 리턴: 두 구간을 덮는 구간
     */
    private fun or(a: Seg, b: Seg): Seg {
        if (!a.nonEmpty) return b // L296–297: self 가 비면 other
        if (!b.nonEmpty) return a // L299–300: other 가 비면 self
        return Seg(minOf(a.start, b.start), maxOf(a.end, b.end)) // L303–305
    }
}
