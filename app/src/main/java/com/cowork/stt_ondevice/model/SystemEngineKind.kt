// 파일명: SystemEngineKind.kt
// 목적: 모델 파일이 없는 시스템 엔진(ML Kit GenAI Basic, Android 온디바이스 SpeechRecognizer)의 종류와 고정 속성 정의
// 기능: 결과 폴더 algo·모델 이름 접두·실제 인식을 맡는 서비스 패키지/표시 이름·결과 기록용 backend 문자열을 종류마다 한 곳에 모은다.
//       change(add)-hyungchul-2026-10-11-18:21 신규(2차 작업 2).
package com.cowork.stt_ondevice.model // 모델 메타 패키지

// change(add)-hyungchul-2026-10-11-18:21
/**
 * 목적: 시스템 엔진 종류
 * 기능: 카탈로그 항목(SystemCatalog)·세션 구현·사전 점검이 같은 상수를 쓰도록 종류별 고정 값을 담는다.
 * 입력: algo(결과 폴더 algo), modelPrefix(model 이름 접두), servicePackage(인식을 실제로 수행하는 서비스 패키지),
 *       serviceLabel(그 서비스의 화면 표시 이름), backend(결과 기록용 backend 문자열)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
enum class SystemEngineKind(
    val algo: String, // 결과 폴더 algo
    val modelPrefix: String, // 결과 폴더 model 접두("<접두>-x<배속>")
    val servicePackage: String, // 인식을 수행하는 서비스 패키지
    val serviceLabel: String, // 서비스 화면 표시 이름
    val backend: String, // 결과 기록용 backend
) {
    MLKIT_BASIC("mlkit", "basic", "com.google.android.tts", "Speech Services by Google", "system(soda)"), // ML Kit GenAI Basic
    PLATFORM_ONDEVICE("android-ondevice", "asi", "com.google.android.as", "Android System Intelligence", "system(asi)"), // Android 온디바이스 SpeechRecognizer
}
