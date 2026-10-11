// 파일명: LangAvailability.kt
// 목적: 실행 전 언어 사용 가능 확인(BatchRunner.availability)의 결과 종류 정의 (2차 최종 리뷰 수정 Important 1)
// 기능: Available(사용 가능), Unsupported(영구 — 언어 미설치·미지원·Android 13 미만 등, manifest.unsupported 에 기록),
//       Transient(일시 — 상태 확인 시간 초과·상태 확인 실패, 기록하지 않고 이번 실행에서만 건너뛰고 실행을 일시정지로 끝냄)를 구분한다.
//       사유 문자열을 비교하지 않고 이 종류로 판별하게 하려는 값이다.
// change(add)-hyungchul-2026-10-11-21:50
package com.cowork.stt_ondevice.engine // 엔진 공통 패키지

// change(add)-hyungchul-2026-10-11-21:50
/**
 * 목적: (모델, 언어) 하나의 실행 전 사용 가능 확인 결과
 * 기능: 파일 머리 설명 참조. BatchRunner 가 종류에 따라 실행·기록·일시정지를 정한다.
 */
sealed interface LangAvailability {
    /** 목적: 사용 가능 / 기능: 그 (모델, 언어) 항목을 실행한다 */
    data object Available : LangAvailability

    /**
     * 목적: 영구 사용 불가
     * 기능: 언어 미설치·미지원·API 부족·권한 없음 등. 그 조합은 실행·폴더 생성 없이 manifest.unsupported·unavailableReasons 에 기록된다.
     * 입력: reason(한국어 사유)
     */
    data class Unsupported(val reason: String) : LangAvailability

    /**
     * 목적: 일시적 확인 실패
     * 기능: 상태 확인 시간 초과·상태 확인 중 예외 등 서비스가 잠시 응답하지 않는 경우. 기록하지 않고 이번 실행에서만 건너뛰며,
     *       실행은 끝(finishedAt)이 아니라 일시정지(pausedReason·Paused 이벤트)로 끝나 이어하기에서 다시 확인한다.
     * 입력: reason(한국어 사유), servicePackage(응답하지 않은 서비스 패키지 — 설정 열기용), serviceLabel(그 서비스의 화면 표시 이름)
     */
    data class Transient(val reason: String, val servicePackage: String, val serviceLabel: String) : LangAvailability
}
