// 파일명: RunEvent.kt
// 목적: 배치 실행이 화면(진행률·로그)으로 내보내는 이벤트 타입 정의
// 기능: 진행률, 항목 완료, 실행 종료 요약 이벤트를 sealed interface 로 표현한다.
//       change(add)-hyungchul-2026-10-11-19:03 일시정지(Paused) 이벤트 추가 — 시스템 엔진이 멈추면 배치를 오류로 끝내지 않고 멈춘다(2차 작업 4).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.storage.InputItem // 입력 음원 항목

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 배치 실행 이벤트의 공통 상위 타입
 * 기능: BatchRunner 가 Flow 로 내보내고 화면이 구독한다.
 */
sealed interface RunEvent {
    /** 목적: 진행률. 입력: done(끝난 수), total(전체 수), current(현재 작업 설명). */
    data class Progress(val done: Int, val total: Int, val current: String) : RunEvent

    /** 목적: 항목 1건 완료. 입력: combo(조합명), item(입력 항목), status(OK/NO_MATCH/ERROR/UNSUPPORTED 등). */
    data class ItemDone(val combo: String, val item: InputItem, val status: String) : RunEvent

    /** 목적: 실행 종료 요약. 입력: 상태별 건수(ok/noMatch/error/unsupported). */
    data class Finished(val ok: Int, val noMatch: Int, val error: Int, val unsupported: Int) : RunEvent

    // change(add)-hyungchul-2026-10-11-19:03
    /**
     * 목적: 배치 일시정지(시스템 엔진 멈춤). 이 이벤트가 마지막이며 뒤에 Finished 는 오지 않는다.
     * 입력: reason(멈춤 사유 한국어 설명), servicePackage(멈춘 서비스의 패키지 — 설정 열기용), serviceLabel(그 서비스의 화면 표시 이름)
     */
    data class Paused(val reason: String, val servicePackage: String, val serviceLabel: String) : RunEvent
}
