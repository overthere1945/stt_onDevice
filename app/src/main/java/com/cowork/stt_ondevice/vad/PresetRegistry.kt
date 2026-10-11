// 파일명: PresetRegistry.kt
// 목적: 기본 프리셋 + USER 프리셋 합본 조회
// 기능: VadPresets.all 뒤에 저장소의 USER 프리셋을 붙여 전체 목록과 id 조회를 제공한다. 없는 id 는 null(대체 없음).
package com.cowork.stt_ondevice.vad // VAD 패키지

// change(add)-hyungchul-2026-10-11-20:08
/**
 * 목적: 화면·실행이 쓰는 프리셋 단일 조회 창구
 * 기능: 기본 26종(VadPresets.all)과 USER 프리셋을 합쳐 돌려준다.
 * 입력: store(USER 프리셋 저장소)
 */
class PresetRegistry(private val store: UserPresetStore) {

    /**
     * 목적: 전체 프리셋 목록
     * 기능: 기본 프리셋 뒤에 저장된 USER 프리셋을 붙인다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: VadPreset 목록
     */
    fun all(): List<VadPreset> = VadPresets.all + store.list() // 기본 + USER

    /**
     * 목적: id 로 프리셋 조회
     * 기능: 일치하는 프리셋을 찾고 없으면 null 을 돌려준다(다른 프리셋으로 대체하지 않음).
     * 입력: id
     * 출력: 없음
     * 리턴: VadPreset 또는 null
     */
    fun byId(id: String): VadPreset? = all().firstOrNull { it.id == id } // 없으면 null
}
