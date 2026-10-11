// 파일명: ModelCatalog.kt
// 목적: 모델 카탈로그 조회 진입점
// 기능: 생성된 ModelCatalogData 의 전체 목록을 노출하고 id 조회, ASR 목록, VAD 계열별 모델 조회를 제공한다.
//       change(add)-hyungchul-2026-10-11-18:21 전체 목록 = 생성 데이터 + 시스템 엔진 항목(SystemCatalog, 2차 작업 2).
package com.cowork.stt_ondevice.model // 모델 메타 패키지

import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 모델 카탈로그 조회
 * 기능: 전체/ASR/VAD 조회를 제공한다. 없는 id 는 예외(다른 모델로 대체하지 않음).
 */
object ModelCatalog {
    /** 목적: 전체 모델 목록 / 기능: 생성 데이터(ASR → VAD 순서) 뒤에 시스템 엔진 항목을 붙인다 */
    val all: List<ModelSpec> = ModelCatalogData.all + SystemCatalog.all // 전체 목록 // change(add)-hyungchul-2026-10-11-18:21 시스템 항목 합침

    /** 목적: ASR 모델 목록 / 기능: kind == ASR 인 항목만 원래 순서로 */
    val asr: List<ModelSpec> = all.filter { it.kind == ModelKind.ASR } // ASR 만

    private val byIdMap: Map<String, ModelSpec> = all.associateBy { it.id } // id → 항목

    /**
     * 목적: id 로 모델 조회
     * 기능: 정확히 같은 id 의 항목을 돌려준다.
     * 입력: id(모델 id)
     * 출력: 없는 id 면 IllegalArgumentException
     * 리턴: ModelSpec
     */
    fun byId(id: String): ModelSpec = byIdMap[id] ?: throw IllegalArgumentException("카탈로그에 없는 모델 id: $id") // 조회 또는 예외

    /**
     * 목적: VAD 계열의 모델 조회
     * 기능: engineConfig 가 VadModelConfig(family) 인 항목을 찾는다. 모델이 필요 없는 계열(NONE/WEBRTC/TEN)은 null.
     * 입력: family(VAD 계열)
     * 출력: 없음
     * 리턴: ModelSpec 또는 null
     */
    fun vadModel(family: VadFamily): ModelSpec? =
        all.firstOrNull { (it.engineConfig as? VadModelConfig)?.family == family } // 계열 일치 첫 항목
}
