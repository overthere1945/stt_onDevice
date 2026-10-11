// 파일명: RunEvent.kt
// 목적: 배치 실행이 화면(진행률·로그)으로 내보내는 이벤트 타입 정의
// 기능: 진행률, 항목 완료, 실행 종료 요약 이벤트를 sealed interface 로 표현한다.
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
}
