// 파일명: LanguageStatus.kt
// 목적: 시스템 엔진(ML Kit Basic·플랫폼 온디바이스 인식기)의 언어별 사용 상태와 다운로드 진행 이벤트 값 정의 (2차 작업 7)
// 기능: LanguageStatus — 사용 가능/다운로드 필요/다운로드 중/사용 불가(사유). DownloadEvent — 진행/완료/실패.
//       StatusReasons — 화면·실행 전 확인이 같은 문구를 쓰도록 사용 불가 사유 상수를 한 곳에 둔다.
// change(add)-hyungchul-2026-10-11-19:52
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 시스템 엔진 언어 1개의 사용 상태
 * 기능: Available(바로 사용), Downloadable(언어 팩을 받아야 함), Downloading(받는 중·설치 대기), Unavailable(사용 불가 — 한국어 사유).
 * 입력: Unavailable.reason(사유)
 */
sealed interface LanguageStatus {
    /** 목적: 사용 가능 / 기능: 언어 팩이 이미 설치되어 바로 인식할 수 있음 */
    data object Available : LanguageStatus

    /** 목적: 다운로드 필요 / 기능: 이 기기에서 지원하지만 언어 팩이 아직 없음 */
    data object Downloadable : LanguageStatus

    /** 목적: 다운로드 중 / 기능: 언어 팩을 받는 중이거나 설치 대기 중 */
    data object Downloading : LanguageStatus

    /** 목적: 사용 불가 / 기능: 이 기기·설정에서 쓸 수 없음 / 입력: reason(한국어 사유) */
    data class Unavailable(val reason: String) : LanguageStatus
}

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 언어 팩 다운로드 진행 이벤트
 * 기능: Progress(받은 양/전체 — 모르면 total=null, 플랫폼은 퍼센트라 bytes=퍼센트·total=100), Done(요청 처리 끝 — 실제 상태는 다시 조회), Failed(한국어 원인).
 */
sealed interface DownloadEvent {
    /** 목적: 진행 / 입력: bytes(받은 양 또는 퍼센트), total(전체 또는 null) */
    data class Progress(val bytes: Long, val total: Long?) : DownloadEvent

    /** 목적: 완료 / 기능: 다운로드 요청 처리가 끝남(성공 여부는 상태를 다시 조회해 확인) */
    data object Done : DownloadEvent

    /** 목적: 실패 / 입력: message(원인 문구) */
    data class Failed(val message: String) : DownloadEvent
}

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 사용 불가 사유 상수
 * 기능: 상태 조회기가 Unavailable 에 담는 문구를 상수로 두어, 실행 전 확인(SystemEngines.unavailableReason)이 같은 상수로 작업 6 의 사유 문구로 바꿀 수 있게 한다.
 */
object StatusReasons {
    /** 목적: 상태 확인 시간 초과 / 기능: withStatusTimeout 이 제한 시간(기본 15 초) 안에 응답을 못 받았을 때 */
    const val TIMEOUT = "상태 확인 시간 초과"

    /** 목적: ML Kit 미지원 / 기능: checkStatus() 가 UNAVAILABLE 일 때 */
    const val MLKIT_UNSUPPORTED = "이 기기에서 지원하지 않음"

    /** 목적: 플랫폼 API 부족 / 기능: API 33(Android 13) 미만 */
    const val API_TOO_OLD = "Android 13 이상 필요"

    /** 목적: 플랫폼 미지원 언어 / 기능: 설치·대기·지원 목록 어디에도 없음 */
    const val PLATFORM_UNSUPPORTED = "기기 인식기가 이 언어를 지원하지 않음"
}
