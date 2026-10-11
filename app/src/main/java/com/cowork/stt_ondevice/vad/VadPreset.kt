// 파일명: VadPreset.kt
// 목적: VAD 프리셋 1건의 정의
// 기능: id·계열·파라미터·출처·주의 문구를 묶는다.
package com.cowork.stt_ondevice.vad // VAD 패키지

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: 사용자가 비교하는 VAD 설정 단위
 * 기능: 같은 프리셋이면 모든 엔진이 동일 구간을 쓰도록 id 로 식별한다.
 */
data class VadPreset(
    val id: String, // 프리셋 id(spec §7.3 표기 그대로)
    val family: VadFamily, // 소속 VAD 계열
    val params: VadParams, // 후처리 파라미터
    val source: String, // 값의 출처
    val note: String = "", // 사용상 주의 문구(없으면 빈 문자열)
)
