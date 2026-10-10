// 파일명: build.gradle.kts (루트)
// 목적: 모든 하위 모듈에 공통으로 쓰이는 플러그인을 클래스패스에 선언
// 기능: Android 앱 / Compose 컴파일러 / 직렬화 플러그인을 버전만 등록(apply false)

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // change(add)-hyungchul-2026-10-10-11:56: Compose 컴파일러 플러그인 등록 (하위 모듈에서 적용)
    alias(libs.plugins.kotlin.compose) apply false
    // change(add)-hyungchul-2026-10-10-11:56: kotlinx-serialization 플러그인 등록 (하위 모듈에서 적용)
    alias(libs.plugins.kotlin.serialization) apply false
}
