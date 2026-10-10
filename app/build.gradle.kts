// 파일명: app/build.gradle.kts
// 목적: 앱 모듈의 빌드 설정 (SDK 버전, ABI, Compose, 의존성)
// 기능: Compose 기반 앱 빌드(minSdk 31, arm64-v8a 전용, Java 17), 16KB 페이지 정렬을 위한 비압축 JNI 패키징,
//       change(add)-hyungchul-2026-10-10-15:26 CMake 네이티브 빌드(NDK 30.0.16248370, CMake 4.1.2, c++_static)

plugins {
    alias(libs.plugins.android.application)
    // change(add)-hyungchul-2026-10-10-11:56: Compose 컴파일러 플러그인 적용 (AGP 9 내장 Kotlin 사용 — kotlin-android 플러그인은 적용하지 않음)
    alias(libs.plugins.kotlin.compose)
    // change(add)-hyungchul-2026-10-10-11:56: kotlinx-serialization 플러그인 적용 (이후 Task 의 JSON 직렬화용)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cowork.stt_ondevice"
    compileSdk {
        version = release(37)
    }
    // change(add)-hyungchul-2026-10-10-15:26: 네이티브 빌드(libstt_native.so)에 쓸 NDK 버전 고정
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.cowork.stt_ondevice"
        // change(add)-hyungchul-2026-10-10-11:56: minSdk 30 -> 31
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // change(add)-hyungchul-2026-10-10-11:56: arm64-v8a 전용 (S25 Ultra 대상)
        ndk {
            abiFilters += "arm64-v8a"
        }

        // change(add)-hyungchul-2026-10-10-15:26: CMake 인자 — C++ STL 정적 링크(libc++_shared.so 를 APK 에 넣지 않음)
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        // change(add)-hyungchul-2026-10-10-11:56: Java 11 -> 17
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // change(add)-hyungchul-2026-10-10-11:56: Compose 활성화
    buildFeatures {
        compose = true
    }
    // change(add)-hyungchul-2026-10-10-11:56: .so 를 압축하지 않고 APK 에 정렬 저장 (16KB 페이지 정렬 요건, pickFirst 는 사용하지 않음)
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    // change(add)-hyungchul-2026-10-10-15:26: 앱 네이티브 라이브러리(libfvad·TEN JNI, 이후 whisper.cpp) CMake 빌드, CMake 4.1.2 고정
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    // change(add)-hyungchul-2026-10-10-11:56: Compose BOM 과 UI 라이브러리
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // change(add)-hyungchul-2026-10-10-11:56: Activity / Lifecycle / Navigation 의 Compose 연동
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    // change(add)-hyungchul-2026-10-10-11:56: 코루틴 / 직렬화
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // change(add)-hyungchul-2026-10-10-13:56: ORT 기반 VAD(Silero·pyannote·FSMN)용 ONNX Runtime 안드로이드 AAR (arm64-v8a libonnxruntime.so 포함)
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    // change(add)-hyungchul-2026-10-10-11:56: JVM 단위 테스트용 코루틴 테스트 / ONNX Runtime
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.onnxruntime.jvm)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// change(add)-hyungchul-2026-10-10-13:56
// JVM 단위 테스트 런타임 클래스패스(debugUnitTestRuntimeClasspath / releaseUnitTestRuntimeClasspath)에서는
// onnxruntime-android(AAR, 안드로이드용 .so 만 포함)를 빼고 testImplementation 의 JVM onnxruntime(같은 1.30.0, 같은 ai.onnxruntime API)을 쓴다.
// 앱 컴파일·APK 패키징 클래스패스에는 영향 없음(이름이 "UnitTestRuntimeClasspath" 로 끝나는 설정에만 적용).
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android") // 안드로이드 AAR 제외
}
