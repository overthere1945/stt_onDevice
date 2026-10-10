// 파일명: RunUiState.kt
// 목적: 배치 실행 서비스(RunService)가 화면에 알리는 실행 상태 타입 정의
// 기능: Idle(실행 없음) / Running(진행 중, 최근 진행률·완료 항목·거부 알림) / Finished(정상 종료 집계) / Failed(비정상 종료) / Cancelled(사용자 취소).
//       RunState.kt 의 RunState 는 BatchRunner 내부의 manifest·로그 관리용(internal)이라 이름이 겹치지 않게 별도 파일·이름으로 둔다.
//       값은 프로세스 안에서만 유지된다(프로세스가 죽으면 Idle 로 다시 시작 — 이어하기는 같은 runId·resume=true 로 새로 시작).
//       change(add)-hyungchul-2026-10-11-03:31 Running 에 오류 수(errorCount)와 시작 시각(startedAtMs)을 더해 진행 화면이 표시한다.
// change(add)-hyungchul-2026-10-11-02:40
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

// change(add)-hyungchul-2026-10-11-02:40
/**
 * 목적: 화면용 실행 상태의 공통 상위 타입
 * 기능: RunService.events(StateFlow) 로 내보낸다. 실행이 끝난 뒤의 상태(Finished/Failed/Cancelled)는 다음 실행을 시작할 때까지 유지된다.
 */
sealed interface RunUiState {

    /** 목적: 이 프로세스에서 아직 실행한 적이 없음. */
    data object Idle : RunUiState

    /**
     * 목적: 실행 중
     * 기능: 시작을 받아들인 순간부터 끝날 때까지의 상태. progress·lastItem 은 BatchRunner 이벤트가 올 때마다 바뀐다.
     * 입력: runId(실행 ID), progress(최근 진행률 — 첫 이벤트 전이면 null), lastItem(최근 완료 항목 — 없으면 null),
     *       rejectedStart(실행 중에 들어온 다른 시작 요청을 거부한 사유 — 없으면 null, 화면 오류 표시용)
     *       change(add)-hyungchul-2026-10-11-03:31 errorCount(지금까지 status=ERROR 로 끝난 항목 수 — 진행 화면 표시용),
     *       startedAtMs(시작을 받아들인 벽시계 시각 ms — 진행 화면 경과 시간용, 알 수 없으면 null)
     */
    data class Running(
        val runId: String, // 실행 ID
        val progress: RunEvent.Progress? = null, // 최근 진행률
        val lastItem: RunEvent.ItemDone? = null, // 최근 완료 항목
        val rejectedStart: String? = null, // 거부한 시작 요청 사유
        val errorCount: Int = 0, // change(add)-hyungchul-2026-10-11-03:31 지금까지의 ERROR 항목 수
        val startedAtMs: Long? = null, // change(add)-hyungchul-2026-10-11-03:31 시작 시각(ms)
    ) : RunUiState

    /**
     * 목적: 정상 종료
     * 입력: runId(실행 ID), counts(상태별 건수 — BatchRunner 의 Finished 이벤트 그대로)
     */
    data class Finished(val runId: String, val counts: RunEvent.Finished) : RunUiState

    /**
     * 목적: 비정상 종료(설정 거부·결과 쓰기 실패·서비스 시작 실패 등)
     * 입력: runId(실행 ID), message(사람이 읽는 원인 — "예외클래스: 메시지")
     */
    data class Failed(val runId: String, val message: String) : RunUiState

    /**
     * 목적: 사용자 취소(알림의 "취소" 또는 RunService.cancel)
     * 입력: runId(실행 ID — 같은 runId 와 resume=true 로 이어서 실행할 수 있음)
     */
    data class Cancelled(val runId: String) : RunUiState
}
