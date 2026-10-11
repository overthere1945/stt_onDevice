// 파일명: DetailJson.kt
// 목적: 파일별 상세 JSON(details/<lang>/<combo>/<relDir>/<stem>.json) 스키마 정의 (스펙 §10~§11)
// 기능: 파일 1건의 상태·오류·VAD 정보·시간·자원·환경·chunk 별 텍스트/매핑을 담는다.
//       측정하지 못한 값은 0 이나 추정치가 아니라 null (AppJson 의 explicitNulls 로 JSON 에 null 이 명시된다).
//       도메인 타입(EnvSnapshot/AudioChunk/SourceSpan)의 JSON 미러와 from() 변환기를 함께 둔다.
//       change(add)-hyungchul-2026-10-11-18:37 SessionJson(시스템 엔진 세션 기록 미러) 추가, ChunkJson 마지막 필드 session(기본 null) 추가(2차 작업 3).
package com.cowork.stt_ondevice.output // 출력 패키지

import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.engine.SessionInfo // 시스템 엔진 세션 기록 // change(add)-hyungchul-2026-10-11-18:37
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

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 시스템 엔진 세션 기록(JSON)
 * 기능: SessionInfo 와 같은 필드를 담는다(그 밖의 엔진 chunk 에는 없음).
 * 입력: number(누적 세션 순번), fedBytes(공급 바이트), maxBlockMs(블록 쓰기 최대 ms), retried(재시도 세션 여부)
 */
@Serializable
data class SessionJson(
    val number: Int, // 누적 세션 순번
    val fedBytes: Long, // 공급 바이트
    val maxBlockMs: Long, // 블록 쓰기 최대(ms)
    val retried: Boolean, // 재시도 세션 여부
) {
    companion object {
        /**
         * 목적: SessionInfo → SessionJson 변환
         * 기능: 필드를 1:1 로 복사한다.
         * 입력: s(세션 기록)
         * 출력: 없음
         * 리턴: SessionJson
         */
        fun from(s: SessionInfo): SessionJson = SessionJson(s.number, s.fedBytes, s.maxBlockMs, s.retried) // 1:1 복사
    }
}

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: chunk 1개의 결과(JSON)
 * 기능: 입력 매핑(spans)·분할 방식·인식 텍스트(후처리 전 rawText 포함)·엔진 추론 시간을 담는다.
 * 입력: index(순번), spans(원본 매핑), split(SplitMethod 이름), text(최종 텍스트), rawText(엔진 원문), inferSec(추론 시간, 미측정 null),
 *       session(시스템 엔진 세션 기록, 그 밖의 엔진 null) // change(add)-hyungchul-2026-10-11-18:37
 */
@Serializable
data class ChunkJson(
    val index: Int, // chunk 순번
    val spans: List<SpanJson>, // 원본 매핑
    val split: String, // 분할 방식 이름
    val text: String, // 최종 텍스트
    val rawText: String, // 엔진 원문
    val inferSec: Double?, // 추론 시간(초), 미측정 null
    val session: SessionJson? = null, // 시스템 엔진 세션 기록(그 밖의 엔진 null, 이전 파일 호환) // change(add)-hyungchul-2026-10-11-18:37
) {
    companion object {
        /**
         * 목적: AudioChunk + 인식 결과 → ChunkJson 변환
         * 기능: chunk 의 index/spans/split 에 텍스트와 시간을 붙인다.
         * 입력: chunk(입력 chunk), text(최종 텍스트), rawText(엔진 원문), inferSec(추론 시간, 미측정 null),
         *       session(시스템 엔진 세션 기록, 없으면 null) // change(add)-hyungchul-2026-10-11-18:37
         * 출력: 없음
         * 리턴: ChunkJson
         */
        fun from(chunk: AudioChunk, text: String, rawText: String, inferSec: Double?, session: SessionJson? = null): ChunkJson = // change(add)-hyungchul-2026-10-11-18:37 session 인자
            ChunkJson(chunk.index, chunk.spans.map(SpanJson::from), chunk.split.name, text, rawText, inferSec, session) // 변환
    }
}

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 엔진 실행 메타(JSON) — 모델 prepare 가 보고한 실제 값
 * 기능: CSV 의 engine_version/compute_type/workers 를 detail JSON 만으로 다시 만들 수 있게 한다(재개 시 CSV 행 복구).
 * 입력: runtimeVersion(런타임 버전), backend(실행 장치), precision(가중치 정밀도), threads(실제 스레드 수)
 */
@Serializable
data class EngineMetaJson(
    val runtimeVersion: String, // 런타임 버전
    val backend: String, // 실행 장치
    val precision: String, // 가중치 정밀도
    val threads: Int, // 스레드 수
)

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
    val audioSec: Double?, // 음원 길이(초), WAV 를 읽기 전에 실패했으면 null // change(add)-hyungchul-2026-10-11-01:47: nullable 로 변경
    val preprocessSec: Double?, // WAV 로드·변환 시간
    val vadSec: Double?, // VAD 계산 시간(초). 캐시 재사용(vadReused=true)이면 이번에 걸린 시간이 아니라 최초 계산 때 저장된 시간이므로 vadReused 와 함께 해석 // change(add)-hyungchul-2026-10-11-02:14: 주석을 실제 값과 일치
    val loadSec: Double?, // 모델 로드 시간
    val transcribeSec: Double?, // 엔진 순수 chunk 추론 시간 합
    val elapsedSec: Double?, // 파일 전체 처리 시간
    val rtf: Double?, // transcribeSec / audioSec
    val cpuTimeSec: Double?, // 프로세스 CPU 시간
    val pssPeakMb: Double?, // PSS 최대(MB)
    val envStart: EnvSnapshotJson?, // 파일 시작 시점 환경
    val envEnd: EnvSnapshotJson?, // 파일 끝 시점 환경
    val chunks: List<ChunkJson>, // chunk 별 결과
    // change(add)-hyungchul-2026-10-11-01:47: CSV 행을 detail JSON 만으로 다시 만들기 위한 원본 음원·엔진 정보(기본값 null 은 이전 파일 호환용)
    val audioBytes: Long? = null, // 원본 파일 크기(바이트)
    val sampleRate: Int? = null, // 원본 샘플레이트(Hz), 읽기 전 실패면 null
    val channels: Int? = null, // 원본 채널 수, 읽기 전 실패면 null
    val detectedLang: String? = null, // 엔진이 감지한 언어(첫 non-null chunk 값), 없으면 null
    val engine: EngineMetaJson? = null, // 엔진 실행 메타, 모델 로드 전/실패면 null
)
