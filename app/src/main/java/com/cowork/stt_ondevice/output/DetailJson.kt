// 파일명: DetailJson.kt
// 목적: 파일별 상세 JSON(details/<lang>/<combo>/<relDir>/<stem>.json) 스키마 정의 (스펙 §10~§11)
// 기능: 파일 1건의 상태·오류·VAD 정보·시간·자원·환경·chunk 별 텍스트/매핑을 담는다.
//       측정하지 못한 값은 0 이나 추정치가 아니라 null (AppJson 의 explicitNulls 로 JSON 에 null 이 명시된다).
//       도메인 타입(EnvSnapshot/AudioChunk/SourceSpan)의 JSON 미러와 from() 변환기를 함께 둔다.
package com.cowork.stt_ondevice.output // 출력 패키지

import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.segment.AudioChunk // 오디오 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간
import kotlinx.serialization.Serializable // 직렬화 대상 표시

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 환경 스냅샷의 JSON 미러
 * 기능: 파일 시작/끝 시점의 발열·배터리 값을 담는다(읽지 못한 값은 null).
 * 입력: EnvSnapshot 과 동일한 필드
 */
@Serializable
data class EnvSnapshotJson(
    val thermalStatus: Int?, // 열 상태 코드
    val thermalHeadroom: Float?, // 열 여유
    val batteryPct: Int?, // 배터리 %
    val charging: Boolean?, // 충전 여부
) {
    companion object {
        /**
         * 목적: EnvSnapshot → EnvSnapshotJson 변환
         * 기능: 필드를 1:1 로 복사한다.
         * 입력: e(환경 스냅샷)
         * 출력: 없음
         * 리턴: EnvSnapshotJson
         */
        fun from(e: EnvSnapshot): EnvSnapshotJson = EnvSnapshotJson(e.thermalStatus, e.thermalHeadroom, e.batteryPct, e.charging) // 1:1 복사
    }
}

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 오류 정보(JSON)
 * 기능: ERROR.txt / CSV 와 같은 stage·code·message 를 담는다.
 * 입력: stage(실패 단계), code(오류 코드), message(메시지)
 */
@Serializable
data class ErrorJson(
    val stage: String, // 실패 단계
    val code: String, // 오류 코드
    val message: String, // 메시지
)

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: chunk 안 조각의 원본 매핑(JSON)
 * 기능: SourceSpan 과 같은 필드를 담는다.
 * 입력: startSec/endSec(원본 기준 구간, 초), chunkOffsetSec(chunk 안 시작 위치, 초)
 */
@Serializable
data class SpanJson(
    val startSec: Double, // 원본 기준 시작(초)
    val endSec: Double, // 원본 기준 끝(초)
    val chunkOffsetSec: Double, // chunk 안 시작 위치(초)
) {
    companion object {
        /**
         * 목적: SourceSpan → SpanJson 변환
         * 기능: 필드를 1:1 로 복사한다.
         * 입력: s(원본 구간)
         * 출력: 없음
         * 리턴: SpanJson
         */
        fun from(s: SourceSpan): SpanJson = SpanJson(s.startSec, s.endSec, s.chunkOffsetSec) // 1:1 복사
    }
}

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: chunk 1개의 결과(JSON)
 * 기능: 입력 매핑(spans)·분할 방식·인식 텍스트(후처리 전 rawText 포함)·엔진 추론 시간을 담는다.
 * 입력: index(순번), spans(원본 매핑), split(SplitMethod 이름), text(최종 텍스트), rawText(엔진 원문), inferSec(추론 시간, 미측정 null)
 */
@Serializable
data class ChunkJson(
    val index: Int, // chunk 순번
    val spans: List<SpanJson>, // 원본 매핑
    val split: String, // 분할 방식 이름
    val text: String, // 최종 텍스트
    val rawText: String, // 엔진 원문
    val inferSec: Double?, // 추론 시간(초), 미측정 null
) {
    companion object {
        /**
         * 목적: AudioChunk + 인식 결과 → ChunkJson 변환
         * 기능: chunk 의 index/spans/split 에 텍스트와 시간을 붙인다.
         * 입력: chunk(입력 chunk), text(최종 텍스트), rawText(엔진 원문), inferSec(추론 시간, 미측정 null)
         * 출력: 없음
         * 리턴: ChunkJson
         */
        fun from(chunk: AudioChunk, text: String, rawText: String, inferSec: Double?): ChunkJson =
            ChunkJson(chunk.index, chunk.spans.map(SpanJson::from), chunk.split.name, text, rawText, inferSec) // 변환
    }
}

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 파일별 상세 JSON 스키마
 * 기능: 파일 1건(조합 × 파일)의 전 과정을 기록한다. CSV 의 시간/자원 열과 같은 값을 담는다.
 * 입력: 각 필드(아래 주석). 측정 안 한 값은 null.
 */
@Serializable
data class DetailJson(
    val schemaVersion: Int = 1, // 스키마 버전
    val combo: String, // 조합명 "<algo>_<model>_<VAD>"
    val lang: String, // 입력 언어 폴더명
    val relDir: String, // 상대 폴더("" 가능)
    val stem: String, // 확장자 없는 파일명
    val file: String, // 원본 파일명(확장자 포함)
    val status: String, // 처리 상태(OK/NO_MATCH/ERROR/UNSUPPORTED 등)
    val error: ErrorJson?, // 오류 정보, 오류 없으면 null
    val vadPresetId: String, // 사용한 VAD 프리셋 id
    val vadReused: Boolean?, // VAD 결과를 캐시에서 재사용했는지, 알 수 없으면 null
    val vadFrameScoresAvailable: Boolean, // ChunkPlanner 가 프레임 점수를 받았는지(false 면 긴 구간은 FORCED 분할)
    val startedAt: String?, // 파일 처리 시작 시각(ISO8601), 미기록 null
    val audioSec: Double, // 음원 길이(초)
    val preprocessSec: Double?, // WAV 로드·변환 시간
    val vadSec: Double?, // VAD 시간(캐시 재사용이면 최초 계산 시간이 아니라 이번에 쓴 시간)
    val loadSec: Double?, // 모델 로드 시간
    val transcribeSec: Double?, // 엔진 순수 chunk 추론 시간 합
    val elapsedSec: Double?, // 파일 전체 처리 시간
    val rtf: Double?, // transcribeSec / audioSec
    val cpuTimeSec: Double?, // 프로세스 CPU 시간
    val pssPeakMb: Double?, // PSS 최대(MB)
    val envStart: EnvSnapshotJson?, // 파일 시작 시점 환경
    val envEnd: EnvSnapshotJson?, // 파일 끝 시점 환경
    val chunks: List<ChunkJson>, // chunk 별 결과
)
