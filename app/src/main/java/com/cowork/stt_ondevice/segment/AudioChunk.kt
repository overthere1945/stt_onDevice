// 파일명: AudioChunk.kt
// 목적: STT 엔진에 한 번에 넣는 오디오 조각(chunk)과 그 출처 정보 모델 정의
// 기능: ChunkMode(묶기 방식), SplitMethod(분할 방식), SourceSpan(원본 구간 매핑), AudioChunk(샘플+매핑)
package com.cowork.stt_ondevice.segment // 청크 분할 패키지

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: 구간을 chunk 로 묶는 방식
 * 기능: MERGE 는 엔진 최대길이까지 여러 조각을 한 chunk 에 합치고, INDIVIDUAL 은 조각마다 chunk 하나를 만든다.
 */
enum class ChunkMode { MERGE, INDIVIDUAL }

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: 긴 구간을 자른 방법 표시
 * 기능: NONE 은 자르지 않음, MIN_SCORE 는 프레임 점수 최저점에서 자름, FORCED 는 점수 없이 최대길이에서 강제로 자름.
 */
enum class SplitMethod { NONE, MIN_SCORE, FORCED }

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: chunk 안의 한 조각이 원본 어디에서 왔는지 기록
 * 기능: 원본 기준 시작/끝(초)과 그 조각이 chunk 안에서 시작하는 위치(초)를 보관한다.
 */
data class SourceSpan(
    val startSec: Double, // 원본 기준 조각 시작(초)
    val endSec: Double, // 원본 기준 조각 끝(초)
    val chunkOffsetSec: Double, // chunk 안에서 조각이 시작하는 위치(초)
)

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: STT 엔진 입력 단위
 * 기능: 샘플과 원본 매핑(spans)을 보관한다.
 *       split 은 chunk 에 들어간 조각 중 "자른 조각"의 분할 방식이며, 여러 조각이 섞이면 FORCED > MIN_SCORE > NONE 순으로 대표값을 정한다.
 *       자르지 않은 조각(긴 구간의 마지막 나머지 포함)은 NONE 이다.
 */
data class AudioChunk(
    val index: Int, // chunk 순번(0부터)
    val samples: FloatArray, // 엔진에 넣을 샘플(조각 사이는 0 으로 채운 joinGap)
    val spans: List<SourceSpan>, // 포함된 조각들의 원본 매핑(시간순, 1개 이상)
    val split: SplitMethod, // 분할 방식 대표값
) {
    /** 원본 기준 첫 span 의 시작(초). */
    val startSec: Double get() = spans.first().startSec

    /** 원본 기준 마지막 span 의 끝(초). */
    val endSec: Double get() = spans.last().endSec
}
