// 파일명: PlatformErrors.kt
// 목적: Android 플랫폼 SpeechRecognizer 의 onError 정수 값을 앱 분류로 바꾸는 순수 함수 모음 (후속 수정 11)
// 기능: mapRecognizerError — 세션 onError → SessionOutcome (PlatformBackend 가 사용). ERROR_SERVER_DISCONNECTED(11)는 ENGINE_ERROR 와 구분되는
//       SERVICE_DISCONNECTED 로 돌려주고 원래 코드는 메시지에 남긴다(멈춤 뒤 재시도에서 일시정지 판단에 쓰임).
//       classifySupportError — checkRecognitionSupport 의 onError → LanguageStatus (AndroidSystemEngineStatus 가 사용).
//       12·13 은 영구 미지원, 9 는 영구 권한 없음, 그 밖(11·14 등)은 일시적 확인 실패.
//       Android 객체를 만들지 않고 상수(컴파일 시 정수로 치환됨)만 쓰므로 PC 단위 테스트로 표 전체를 확인할 수 있다.
// change(add)-hyungchul-2026-10-12-02:54
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import android.speech.SpeechRecognizer // 오류 상수(ERROR_SERVER_DISCONNECTED=11 은 API 31 에 있어 minSdk 31 에서 그대로 사용)

// change(add)-hyungchul-2026-10-12-02:54
/**
 * 목적: 플랫폼 세션 onError → 세션 결과
 * 기능: LANGUAGE_UNAVAILABLE(13)/LANGUAGE_NOT_SUPPORTED(12) → Failed(LANGUAGE_UNAVAILABLE), NO_MATCH(7)/SPEECH_TIMEOUT(6) → Completed(그때까지 조각),
 *       INSUFFICIENT_PERMISSIONS(9) → Failed(PERMISSION_MISSING), SERVER_DISCONNECTED(11) → Failed(SERVICE_DISCONNECTED, 메시지에 onError=11 보존),
 *       그 밖 → Failed(ENGINE_ERROR, "onError=<n>").
 * 입력: error(오류 값), tag(언어 태그), segments(그때까지 모은 조각)
 * 출력: 없음
 * 리턴: SessionOutcome
 */
internal fun mapRecognizerError(error: Int, tag: String, segments: List<String>): SessionOutcome = when (error) {
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> // 언어 팩 없음·미지원
        SessionOutcome.Failed("LANGUAGE_UNAVAILABLE", "언어 사용 불가($tag, onError=$error)") // 언어 문제
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> // 음성 없음
        SessionOutcome.Completed(segments) // 오류 아님(보통 빈 목록)
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> // 권한 부족
        SessionOutcome.Failed("PERMISSION_MISSING", "권한 부족($tag, onError=$error) — 마이크(RECORD_AUDIO) 권한 확인 필요") // 권한 문제
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> // 인식 서비스 연결 끊김(값 11) — 서비스가 망가진 신호
        SessionOutcome.Failed("SERVICE_DISCONNECTED", "인식 서비스 연결 끊김($tag, onError=$error)") // 서비스 문제(멈춤 뒤 재시도면 일시정지 대상)
    else -> SessionOutcome.Failed("ENGINE_ERROR", "onError=$error") // 그 밖
}

// change(add)-hyungchul-2026-10-12-02:54
/**
 * 목적: 지원 확인(checkRecognitionSupport) onError → 언어 상태
 * 기능: ERROR_LANGUAGE_NOT_SUPPORTED(12)·ERROR_LANGUAGE_UNAVAILABLE(13) → Unavailable(PLATFORM_UNSUPPORTED — 영구 미지원, availability 는 Unsupported),
 *       ERROR_INSUFFICIENT_PERMISSIONS(9) → Unavailable("마이크 권한 없음", 영구), 그 밖(11·14 등) → CheckFailed("상태 확인 실패(onError=<n>)", 일시).
 * 입력: error(오류 값)
 * 출력: 없음
 * 리턴: LanguageStatus
 */
internal fun classifySupportError(error: Int): LanguageStatus = when (error) {
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> // 언어 미지원·사용 불가
        LanguageStatus.Unavailable(StatusReasons.PLATFORM_UNSUPPORTED) // 영구 미지원
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> LanguageStatus.Unavailable("마이크 권한 없음") // 영구(권한 허용 전까지)
    else -> LanguageStatus.CheckFailed("상태 확인 실패(onError=$error)") // 일시적 확인 실패(서비스 문제 등)
}
