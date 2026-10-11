// 파일명: ChunkPlannerTest.kt
// 목적: ChunkPlanner 의 구간 clamp·분할·병합 동작 검증
// 기능: MERGE/INDIVIDUAL, MIN_SCORE/FORCED 분할, 무제한 길이, 빈 입력, 범위 밖 clamp 를 확인한다.
package com.cowork.stt_ondevice.segment // 청크 분할 테스트 패키지

import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: ChunkPlanner 회귀 방지
 * 기능: 샘플값이 자기 시각(i/16000/1000)을 인코딩한 100 초 오디오로, 어떤 원본 샘플이 어디에 들어갔는지까지 확인한다.
 */
class ChunkPlannerTest {

    private val sr = 16000 // 샘플레이트(Hz)
    private val eps = 1e-6 // 시간 비교 허용오차(초)
    private val audio = FloatArray(100 * sr) { it / 16000f / 1000f } // 샘플값 = 자기 시각(초)/1000

    /**
     * 목적: 구간 목록만 가진 VAD 출력 생성
     * 기능: 점수 없는 VadOutput 을 만든다.
     * 입력: ranges(시작·끝 초 쌍)
     * 출력: 없음
     * 리턴: VadOutput(frameHopSec/frameScores 는 null)
     */
    private fun vadOf(vararg ranges: Pair<Double, Double>): VadOutput =
        VadOutput(ranges.map { SpeechSegment(it.first, it.second) }, null, null) // 점수 없음

    /**
     * 목적: MERGE 가 최대길이까지 묶고 넘으면 새 chunk 를 여는지 확인
     * 기능: [0-10],[12-20],[25-40] max 30 → chunk0(2개 span, 18.2 초), chunk1([25-40]).
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun mergePacksUpToMax() {
        val chunks = ChunkPlanner.plan(audio, sr, vadOf(0.0 to 10.0, 12.0 to 20.0, 25.0 to 40.0), 30.0, ChunkMode.MERGE) // 병합 실행
        assertEquals(2, chunks.size) // chunk 2개
        val c0 = chunks[0] // 첫 chunk
        assertEquals(0, c0.index) // 순번 0
        assertEquals(2, c0.spans.size) // span 2개
        assertEquals((10.2 * sr + 8.0 * sr).toInt(), c0.samples.size) // (10+0.2+8)*16000 샘플
        assertEquals(0.0, c0.spans[0].chunkOffsetSec, eps) // 첫 조각은 chunk 맨 앞
        assertEquals(10.2, c0.spans[1].chunkOffsetSec, eps) // 둘째 조각은 10 초 + joinGap 뒤
        assertEquals(12.0, c0.spans[1].startSec, eps) // 둘째 조각 원본 시작
        assertEquals(0.0, c0.startSec, eps) // chunk 원본 시작
        assertEquals(20.0, c0.endSec, eps) // chunk 원본 끝
        assertEquals(0f, c0.samples[10 * sr], 0f) // joinGap 첫 샘플은 0
        assertEquals(0f, c0.samples[10 * sr + 3199], 0f) // joinGap 마지막 샘플도 0
        assertEquals(audio[12 * sr], c0.samples[(10.2 * sr).toInt()], 0f) // 둘째 조각 첫 샘플 = 원본 12 초
        assertEquals(SplitMethod.NONE, c0.split) // 자르지 않음
        val c1 = chunks[1] // 둘째 chunk
        assertEquals(1, c1.index) // 순번 1
        assertEquals(1, c1.spans.size) // span 1개
        assertEquals(15 * sr, c1.samples.size) // 15 초 분량
        assertEquals(25.0, c1.startSec, eps) // 원본 25 초 시작
        assertEquals(40.0, c1.endSec, eps) // 원본 40 초 끝
        assertEquals(audio[25 * sr], c1.samples[0], 0f) // 첫 샘플 = 원본 25 초
    }

    /**
     * 목적: INDIVIDUAL 이 구간마다 chunk 하나를 만드는지 확인
     * 기능: 같은 입력에서 chunk 3개, 각각 span 1개.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun individualOnePerSegment() {
        val chunks = ChunkPlanner.plan(audio, sr, vadOf(0.0 to 10.0, 12.0 to 20.0, 25.0 to 40.0), 30.0, ChunkMode.INDIVIDUAL) // 개별 실행
        assertEquals(3, chunks.size) // chunk 3개
        assertTrue(chunks.all { it.spans.size == 1 }) // 모두 span 1개
        assertEquals(listOf(0, 1, 2), chunks.map { it.index }) // 순번 연속
        assertEquals(8 * sr, chunks[1].samples.size) // 둘째는 8 초
    }

    /**
     * 목적: 긴 구간이 프레임 점수 최저점에서 잘리는지 확인
     * 기능: [0-70] max 30, hop 0.1, 25.0 초만 0.0 → [0-25](MIN_SCORE), [25-55](동점 → 가장 늦은 55.0, MIN_SCORE), [55-70](자르지 않은 나머지 NONE).
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun longSegmentSplitAtMinScore() {
        val scores = FloatArray(1000) { 0.9f } // 100 초 분량(hop 0.1) 점수, 기본 0.9
        scores[250] = 0.0f // 25.0 초 프레임만 최저
        val vad = VadOutput(listOf(SpeechSegment(0.0, 70.0)), 0.1, scores) // 점수 있는 VAD 출력
        val chunks = ChunkPlanner.plan(audio, sr, vad, 30.0, ChunkMode.MERGE) // 병합 모드로 실행
        assertEquals(3, chunks.size) // 조각 3개가 합쳐지지 않고 chunk 3개
        assertEquals(0.0, chunks[0].startSec, eps) // 첫 조각 시작
        assertEquals(25.0, chunks[0].endSec, eps) // 첫 분할은 점수 최저점 25.0
        assertEquals(SplitMethod.MIN_SCORE, chunks[0].split) // 첫 분할은 MIN_SCORE
        assertEquals(25.0, chunks[1].startSec, eps) // 둘째 조각 시작
        assertEquals(55.0, chunks[1].endSec, eps) // 탐색구간 [26,55] 은 전부 동점 → 가장 늦은 55.0
        assertEquals(SplitMethod.MIN_SCORE, chunks[1].split) // 둘째 분할도 MIN_SCORE
        assertEquals(55.0, chunks[2].startSec, eps) // 나머지 조각 시작
        assertEquals(70.0, chunks[2].endSec, eps) // 나머지 조각 끝
        assertEquals(SplitMethod.NONE, chunks[2].split) // 자르지 않은 나머지는 NONE
        assertEquals(25 * sr, chunks[0].samples.size) // 첫 chunk 샘플 수
        assertEquals(audio[25 * sr], chunks[1].samples[0], 0f) // 둘째 chunk 첫 샘플 = 원본 25 초
        assertEquals(audio[55 * sr], chunks[2].samples[0], 0f) // 셋째 chunk 첫 샘플 = 원본 55 초
    }

    /**
     * 목적: 점수가 없으면 최대길이에서 강제 분할하는지 확인
     * 기능: scores null → [0-30],[30-60],[60-70], 자른 조각은 FORCED, 마지막 나머지는 NONE.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun longSegmentForcedWithoutScores() {
        val chunks = ChunkPlanner.plan(audio, sr, vadOf(0.0 to 70.0), 30.0, ChunkMode.INDIVIDUAL) // 점수 없이 실행
        assertEquals(3, chunks.size) // chunk 3개
        assertEquals(listOf(0.0, 30.0, 60.0), chunks.map { it.startSec }) // 시작 시각
        assertEquals(listOf(30.0, 60.0, 70.0), chunks.map { it.endSec }) // 끝 시각
        assertEquals(listOf(SplitMethod.FORCED, SplitMethod.FORCED, SplitMethod.NONE), chunks.map { it.split }) // 분할 방식
    }

    /**
     * 목적: 탐색구간에 프레임이 없으면 FORCED 로 자르는지 확인
     * 기능: 점수 배열이 0~10 초뿐이라 [40-80] 의 탐색구간 [41,70] 에 프레임이 없다 → 70 에서 FORCED, 나머지 [70-80] 은 NONE.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun windowOutsideScoresFallsBackToForced() {
        val vad = VadOutput(listOf(SpeechSegment(40.0, 80.0)), 0.1, FloatArray(100) { 0.5f }) // 점수는 0~10 초뿐
        val chunks = ChunkPlanner.plan(audio, sr, vad, 30.0, ChunkMode.INDIVIDUAL) // 실행
        assertEquals(2, chunks.size) // chunk 2개
        assertEquals(70.0, chunks[0].endSec, eps) // start+max 에서 강제 분할
        assertEquals(SplitMethod.FORCED, chunks[0].split) // 프레임 없음 → FORCED
    }

    /**
     * 목적: maxChunkSec 0 이면 제한 없이 한 chunk 로 합치는지 확인
     * 기능: 세 구간이 chunk 1개(span 3개)로 나온다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun unlimitedMaxMergesAll() {
        val chunks = ChunkPlanner.plan(audio, sr, vadOf(0.0 to 10.0, 12.0 to 20.0, 25.0 to 40.0), 0.0, ChunkMode.MERGE) // 무제한 병합
        assertEquals(1, chunks.size) // chunk 1개
        assertEquals(3, chunks[0].spans.size) // span 3개
        assertEquals(40.0, chunks[0].endSec, eps) // 끝은 마지막 구간 끝
    }

    /**
     * 목적: 구간이 없으면 chunk 도 없는지 확인
     * 기능: 빈 segments → 빈 리스트.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun noSegmentsGivesNoChunks() {
        val chunks = ChunkPlanner.plan(audio, sr, vadOf(), 30.0, ChunkMode.MERGE) // 빈 입력
        assertTrue(chunks.isEmpty()) // 결과 없음
    }

    /**
     * 목적: 오디오 길이를 넘는 구간이 잘려 들어오는지 확인
     * 기능: [95-120] 은 [95-100] 로 clamp, 샘플 5 초분.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun clampsOutOfRange() {
        val chunks = ChunkPlanner.plan(audio, sr, vadOf(95.0 to 120.0), 30.0, ChunkMode.MERGE) // 범위 밖 구간
        assertEquals(1, chunks.size) // chunk 1개
        assertEquals(95.0, chunks[0].startSec, eps) // 시작 유지
        assertEquals(100.0, chunks[0].endSec, eps) // 끝은 오디오 길이로 clamp
        assertEquals(5 * sr, chunks[0].samples.size) // 5 초분 샘플
    }

    /**
     * 목적: 길이 0 이하·완전히 범위 밖 구간이 버려지는지 확인
     * 기능: [5-5], [8-3], [110-120] 는 제거되고 [1-2] 만 남는다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun dropsEmptyAndInvertedSegments() {
        val chunks = ChunkPlanner.plan(audio, sr, vadOf(5.0 to 5.0, 8.0 to 3.0, 110.0 to 120.0, 1.0 to 2.0), 30.0, ChunkMode.INDIVIDUAL) // 무효 구간 포함
        assertEquals(1, chunks.size) // 유효 구간 1개만
        assertEquals(1.0, chunks[0].startSec, eps) // [1-2]
    }
}
