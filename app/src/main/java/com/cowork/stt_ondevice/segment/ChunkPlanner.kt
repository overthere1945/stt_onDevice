// 파일명: ChunkPlanner.kt
// 목적: VAD 음성 구간을 STT 엔진의 최대 입력 길이에 맞는 오디오 chunk 로 변환
// 기능: 구간 clamp → 긴 구간 분할(프레임 점수 최저점 / 강제) → MERGE(joinGap 으로 묶기) 또는 INDIVIDUAL(1:1) chunk 생성
package com.cowork.stt_ondevice.segment // 청크 분할 패키지

import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: 엔진별 최대 입력 길이에 맞춰 오디오를 chunk 로 나눔
 * 기능: 알고리즘(spec §8)
 *  1. 각 구간을 오디오 범위로 clamp 하고 길이 0 이하는 버린다.
 *  2. maxChunkSec > 0 이고 구간이 maxChunkSec 보다 길면 분할한다.
 *     - 프레임 i 의 시각은 i * frameHopSec(프레임 시작). 탐색구간은 닫힌 구간 [start+1.0, start+maxChunkSec].
 *     - 탐색구간 안에서 frameScores 최저 프레임의 시각에서 자른다(MIN_SCORE). 동점이면 가장 늦은 프레임(조각이 가장 길어짐).
 *     - 탐색구간에 프레임이 없거나 점수가 없으면 start+maxChunkSec 에서 자른다(FORCED).
 *     - split 은 "잘린 조각"에 기록하고, 더 안 자른 마지막 나머지 조각과 짧아서 안 자른 구간은 NONE 이다.
 *  3. INDIVIDUAL: 조각 하나 = chunk 하나.
 *  4. MERGE: 누적 길이 + (비어있지 않으면 joinGap) + 조각 길이 ≤ maxChunkSec 이면 같은 chunk 에 0 으로 채운 joinGap 뒤에 붙이고, 아니면 새 chunk. maxChunkSec == 0 이면 전부 하나.
 *  5. spans[i].chunkOffsetSec = 그 조각이 chunk 안에서 시작하는 위치.
 */
object ChunkPlanner {

    private const val MIN_SPLIT_OFFSET_SEC = 1.0 // 분할 탐색 시작 최소 오프셋(초)
    private const val EPS = 1e-9 // 부동소수 비교 허용오차

    /** 분할이 끝난 한 조각(원본 기준 시각과 분할 방식). */
    private data class Piece(val startSec: Double, val endSec: Double, val split: SplitMethod)

    /**
     * 목적: VAD 구간을 chunk 목록으로 변환
     * 기능: clamp → 분할 → 모드에 따라 묶기. 샘플 인덱스는 Math.round(sec * sampleRate) 로 계산한다.
     * 입력: audio(16 kHz mono 등 float 샘플), sampleRate(Hz), vad(구간+선택적 프레임 점수), maxChunkSec(0 이면 무제한), mode(MERGE/INDIVIDUAL), joinGapSec(MERGE 시 조각 사이 무음 길이)
     * 출력: 없음
     * 리턴: 시간순 AudioChunk 목록(구간이 없으면 빈 리스트)
     */
    fun plan(
        audio: FloatArray,
        sampleRate: Int,
        vad: VadOutput,
        maxChunkSec: Double,
        mode: ChunkMode,
        joinGapSec: Double = 0.2,
    ): List<AudioChunk> {
        require(sampleRate > 0) { "sampleRate 는 양수여야 한다: $sampleRate" } // 잘못된 샘플레이트 거부
        require(maxChunkSec >= 0.0) { "maxChunkSec 는 0 이상이어야 한다: $maxChunkSec" } // 음수 길이 거부
        val durationSec = audio.size.toDouble() / sampleRate // 오디오 총 길이(초)
        val pieces = vad.segments.flatMap { seg -> // 구간마다
            val start = maxOf(seg.startSec, 0.0) // 앞쪽 범위 밖 clamp
            val end = minOf(seg.endSec, durationSec) // 뒤쪽 범위 밖 clamp
            if (end - start <= 0.0) emptyList() else splitSegment(start, end, vad, maxChunkSec) // 빈 구간 제거 후 분할
        }
        return if (mode == ChunkMode.INDIVIDUAL) { // 조각 하나 = chunk 하나
            pieces.mapIndexed { i, p -> buildChunk(i, audio, sampleRate, listOf(p), joinGapSec) } // 조각마다 chunk
        } else { // 묶기
            mergePieces(pieces, sampleRate, maxChunkSec, joinGapSec).mapIndexed { i, group -> // 묶음마다
                buildChunk(i, audio, sampleRate, group, joinGapSec) // chunk 생성
            }
        }
    }

    /**
     * 목적: 한 구간을 최대길이 이하 조각들로 분할
     * 기능: 남은 길이가 maxChunkSec 를 넘는 동안 탐색구간 최저점(또는 강제 지점)에서 잘라낸다.
     * 입력: start/end(clamp 된 구간, 초), vad(프레임 점수), maxChunkSec(0 이면 분할 안 함)
     * 출력: 없음
     * 리턴: 조각 목록(자른 조각은 MIN_SCORE/FORCED, 나머지는 NONE)
     */
    private fun splitSegment(start: Double, end: Double, vad: VadOutput, maxChunkSec: Double): List<Piece> {
        val out = ArrayList<Piece>() // 결과 조각
        var cur = start // 현재 시작 위치
        if (maxChunkSec > 0.0) { // 길이 제한이 있을 때만 분할
            while (end - cur > maxChunkSec + EPS) { // 남은 길이가 최대길이를 넘으면
                val cut = findMinScoreCut(cur, cur + maxChunkSec, vad) // 점수 최저 시각 탐색
                if (cut != null) { // 프레임이 있으면
                    out.add(Piece(cur, cut, SplitMethod.MIN_SCORE)) // 최저점에서 자름
                    cur = cut // 다음 시작
                } else { // 프레임이 없으면
                    out.add(Piece(cur, cur + maxChunkSec, SplitMethod.FORCED)) // 최대길이에서 강제 분할
                    cur += maxChunkSec // 다음 시작
                }
            }
        }
        out.add(Piece(cur, end, SplitMethod.NONE)) // 자르지 않은 나머지(또는 짧은 구간 전체)
        return out // 분할 완료
    }

    /**
     * 목적: 탐색구간 안에서 점수가 가장 낮은 프레임 시각 찾기
     * 기능: 시각 t = i * frameHopSec 가 [searchStart+1.0, searchEnd] 에 드는 프레임 중 최저 점수를 고른다. 동점이면 가장 늦은 프레임.
     * 입력: searchStart(조각 시작, 초), searchEnd(조각 시작+maxChunkSec, 초), vad(점수·hop)
     * 출력: 없음
     * 리턴: 자를 시각(초), 점수가 없거나 탐색구간에 프레임이 없으면 null
     */
    private fun findMinScoreCut(searchStart: Double, searchEnd: Double, vad: VadOutput): Double? {
        val scores = vad.frameScores ?: return null // 점수 없음
        val hop = vad.frameHopSec ?: return null // hop 없음
        if (hop <= 0.0 || scores.isEmpty()) return null // 사용할 수 없는 점수
        val lo = searchStart + MIN_SPLIT_OFFSET_SEC // 탐색 하한(시각)
        val first = maxOf(0, Math.ceil(lo / hop - EPS).toInt()) // 하한 이상 첫 프레임 인덱스
        val last = minOf(scores.size - 1, Math.floor(searchEnd / hop + EPS).toInt()) // 상한 이하 마지막 프레임 인덱스
        if (first > last) return null // 탐색구간에 프레임 없음
        var best = last // 최저 점수 인덱스(동점 시 늦은 쪽 우선이라 끝에서 시작)
        for (i in last - 1 downTo first) { // 뒤에서 앞으로 훑으며
            if (scores[i] < scores[best]) best = i // 엄격히 더 낮을 때만 교체(동점은 늦은 프레임 유지)
        }
        return minOf(best * hop, searchEnd) // 프레임 시각, 부동소수 오차로 상한을 넘지 않게
    }

    /**
     * 목적: 조각들을 최대길이 이내 묶음으로 그룹화
     * 기능: 누적 길이 + joinGap + 조각 길이 ≤ maxChunkSec 이면 현재 묶음에 넣고, 아니면 새 묶음. maxChunkSec == 0 이면 전부 한 묶음.
     * 입력: pieces(분할된 조각), sampleRate, maxChunkSec, joinGapSec
     * 출력: 없음
     * 리턴: 묶음 목록(빈 입력이면 빈 리스트)
     */
    private fun mergePieces(pieces: List<Piece>, sampleRate: Int, maxChunkSec: Double, joinGapSec: Double): List<List<Piece>> {
        val groups = ArrayList<MutableList<Piece>>() // 결과 묶음
        var usedSamples = 0L // 현재 묶음이 차지한 샘플 수
        val gapSamples = toSamples(joinGapSec, sampleRate).toLong() // joinGap 샘플 수
        for (p in pieces) { // 조각 순서대로
            val pieceSamples = (toSamples(p.endSec, sampleRate) - toSamples(p.startSec, sampleRate)).toLong() // 조각 샘플 수
            val current = groups.lastOrNull() // 현재 묶음
            val needed = usedSamples + gapSamples + pieceSamples // 붙였을 때 총 샘플 수
            if (current != null && (maxChunkSec == 0.0 || needed.toDouble() / sampleRate <= maxChunkSec + EPS)) { // 들어가면
                current.add(p) // 현재 묶음에 추가
                usedSamples = needed // 누적 갱신
            } else { // 안 들어가면
                groups.add(mutableListOf(p)) // 새 묶음
                usedSamples = pieceSamples // 누적 초기화
            }
        }
        return groups // 그룹화 완료
    }

    /**
     * 목적: 조각 묶음을 하나의 AudioChunk 로 만들기
     * 기능: 조각 샘플을 복사하고 사이에 0 으로 채운 joinGap 을 넣으며 span 의 chunk 내 위치를 기록한다.
     * 입력: index(chunk 순번), audio, sampleRate, group(조각들), joinGapSec
     * 출력: 없음
     * 리턴: AudioChunk(split 은 FORCED > MIN_SCORE > NONE 대표값)
     */
    private fun buildChunk(index: Int, audio: FloatArray, sampleRate: Int, group: List<Piece>, joinGapSec: Double): AudioChunk {
        val gapSamples = toSamples(joinGapSec, sampleRate) // joinGap 샘플 수
        val ranges = group.map { toSamples(it.startSec, sampleRate) to toSamples(it.endSec, sampleRate) } // 조각별 [시작,끝) 샘플
        val total = ranges.sumOf { it.second - it.first } + gapSamples * (group.size - 1) // chunk 총 샘플 수
        val samples = FloatArray(total) // 0 으로 초기화된 결과 배열(joinGap 은 그대로 0)
        val spans = ArrayList<SourceSpan>() // 원본 매핑
        var pos = 0 // chunk 안 현재 쓰기 위치(샘플)
        for ((i, p) in group.withIndex()) { // 조각마다
            val (from, to) = ranges[i] // 원본 샘플 범위
            System.arraycopy(audio, from, samples, pos, to - from) // 원본 샘플 복사
            spans.add(SourceSpan(p.startSec, p.endSec, pos.toDouble() / sampleRate)) // 원본 시각과 chunk 내 위치 기록
            pos += (to - from) + gapSamples // 다음 조각 위치(joinGap 건너뜀)
        }
        val split = when { // 대표 분할 방식
            group.any { it.split == SplitMethod.FORCED } -> SplitMethod.FORCED // 강제 분할 포함
            group.any { it.split == SplitMethod.MIN_SCORE } -> SplitMethod.MIN_SCORE // 점수 분할 포함
            else -> SplitMethod.NONE // 자르지 않음
        }
        return AudioChunk(index, samples, spans, split) // chunk 완성
    }

    /**
     * 목적: 초를 샘플 인덱스로 변환
     * 기능: Math.round(sec * sampleRate).
     * 입력: sec(초), sampleRate(Hz)
     * 출력: 없음
     * 리턴: 샘플 수(Int)
     */
    private fun toSamples(sec: Double, sampleRate: Int): Int = Math.round(sec * sampleRate).toInt()
}
