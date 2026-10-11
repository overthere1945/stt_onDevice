// 파일명: RunConfig.kt
// 목적: 배치 실행 1회의 설정을 담는 값 객체
// 기능: run_id·입력 항목·모델(선택 순서)·VAD 프리셋·chunk 모드·maxChunkSec 덮어쓰기·스레드·이어하기 여부를 보관하고, 모델별 실효 maxChunkSec 를 계산한다.
//       change(add)-hyungchul-2026-10-11-19:10 시스템 엔진(실시간 배속 공급)의 maxChunkOverride 는 0 초 초과 60 초 이하만 허용한다(2차 작업 5, 0 = 무제한은 금지 — 61 초 넘는 세션 앞부분 잘림 실측).
//       change(add)-hyungchul-2026-10-11-12:51 rerunCrashed: 같은 run(같은 설정)을 이어서 CRASH_OR_KILLED 항목만 지우고 다시 실행하는 모드(완료된 run 도 허용, 이어하기 전용).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.storage.InputItem // 입력 음원 항목
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.isSystem // 시스템 엔진 여부 // change(add)-hyungchul-2026-10-11-19:10
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 배치 실행 설정
 * 기능: 실행 순서는 모델(선택 순) → VAD 프리셋 → 파일이다. 생성 시 threads 와 runId 형식, maxChunkOverride(유한·0 이상·선택 모델 키)를 검증한다.
 *       change(add)-hyungchul-2026-10-11-19:10 시스템 엔진의 maxChunkOverride 는 0 < 값 ≤ 60 이어야 한다(아니면 "시스템 엔진 최대 조각 길이는 0 초 초과 60 초 이하여야 합니다: <id>=<값>").
 * 입력: runId(yyyyMMdd_HHmmss), items(입력 항목), models(선택 순서), presets(VAD 프리셋), chunkMode(묶기 방식),
 *       maxChunkOverride(모델 id → 덮어쓸 maxChunkSec), threads(기본 4, 1 이상), resume(이어하기 여부),
 *       rerunCrashed(change(add)-hyungchul-2026-10-11-12:51 CRASH_OR_KILLED 항목 다시 실행 — resume=true 일 때만, 기본 false)
 * 출력: 형식이 틀리면 IllegalArgumentException
 * 리턴: 해당 없음
 */
data class RunConfig(
    val runId: String, // 실행 ID
    val items: List<InputItem>, // 입력 항목
    val models: List<ModelSpec>, // 선택한 모델(실행 순서)
    val presets: List<VadPreset>, // 선택한 VAD 프리셋
    val chunkMode: ChunkMode, // chunk 묶기 방식
    val maxChunkOverride: Map<String, Double>, // 모델 id → 덮어쓸 최대 chunk 길이(초)
    val threads: Int = 4, // 추론 스레드 수
    val resume: Boolean = false, // 이어하기 실행 여부
    val rerunCrashed: Boolean = false, // CRASH_OR_KILLED 항목 다시 실행 여부 // change(add)-hyungchul-2026-10-11-12:51
) {
    init {
        require(threads >= 1) { "threads 는 1 이상이어야 합니다: $threads" } // 스레드 하한
        require(RUN_ID_REGEX.matches(runId)) { "runId 는 yyyyMMdd_HHmmss 형식이어야 합니다: $runId" } // 형식 검사
        require(!rerunCrashed || resume) { "rerunCrashed 는 같은 run 을 이어서 실행할 때(resume=true)만 쓸 수 있습니다" } // 다시 실행은 이어하기 전용 // change(add)-hyungchul-2026-10-11-12:51
        // change(add)-hyungchul-2026-10-11-01:47: maxChunkOverride 값·키 검증(Task 24 리뷰 지적)
        for ((id, sec) in maxChunkOverride) { // 덮어쓰기 항목마다
            require(sec.isFinite() && sec >= 0.0) { "maxChunkOverride 값은 유한한 0 이상이어야 합니다: $id=$sec" } // NaN·무한대·음수 거부
            require(models.any { it.id == id }) { "maxChunkOverride 의 키가 선택한 모델 id 가 아닙니다: $id" } // 미선택 모델 키 거부
            models.firstOrNull { it.id == id }?.let { m -> systemMaxChunkError(m, id, sec)?.let { msg -> throw IllegalArgumentException(msg) } } // 시스템 엔진 범위 검사 // change(add)-hyungchul-2026-10-11-19:10
        }
    }

    /**
     * 목적: 모델의 실효 최대 chunk 길이 계산
     * 기능: 덮어쓰기 값이 있으면 그 값, 없으면 모델 기본값(spec.maxChunkSec)을 쓴다.
     * 입력: spec(모델)
     * 출력: 없음
     * 리턴: 최대 chunk 길이(초), 0 = 제한 없음
     */
    fun maxChunkFor(spec: ModelSpec): Double = maxChunkOverride[spec.id] ?: spec.maxChunkSec // 덮어쓰기 우선

    companion object { // change(add)-hyungchul-2026-10-11-19:10 private 에서 공개로 바꿈(화면 입력 검증이 같은 범위·문구를 쓰도록 systemMaxChunkError 공개)
        private val RUN_ID_REGEX = Regex("""\d{8}_\d{6}""") // yyyyMMdd_HHmmss 모양
        const val SYSTEM_MAX_CHUNK_LIMIT_SEC = 60.0 // 시스템 엔진 maxChunk 덮어쓰기 상한(초) // change(add)-hyungchul-2026-10-11-19:10

        // change(add)-hyungchul-2026-10-11-19:10
        /**
         * 목적: 시스템 엔진 maxChunk 덮어쓰기 범위 검사 문구
         * 기능: 시스템 엔진이면 0 초 초과 60 초 이하(NaN 포함 그 밖은 위반)인지 본다. 시스템 엔진이 아니면 검사하지 않는다(기존 규칙은 init 에서 따로 검사).
         * 입력: spec(모델), id(모델 id — 문구에 표시), sec(덮어쓸 값)
         * 출력: 없음
         * 리턴: 위반이면 한국어 오류 문구, 아니면 null
         */
        fun systemMaxChunkError(spec: ModelSpec, id: String, sec: Double): String? =
            if (spec.isSystem && !(sec > 0.0 && sec <= SYSTEM_MAX_CHUNK_LIMIT_SEC)) "시스템 엔진 최대 조각 길이는 0 초 초과 60 초 이하여야 합니다: $id=$sec" else null // 범위 밖이면 문구
    }
}
