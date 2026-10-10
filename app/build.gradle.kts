// 파일명: app/build.gradle.kts
// 목적: 앱 모듈의 빌드 설정 (SDK 버전, ABI, Compose, 의존성)
// 기능: Compose 기반 앱 빌드(minSdk 31, arm64-v8a 전용, Java 17), 16KB 페이지 정렬을 위한 비압축 JNI 패키징,
//       change(add)-hyungchul-2026-10-10-15:26 CMake 네이티브 빌드(NDK 30.0.16248370, CMake 4.1.2, c++_static)
//       change(add)-hyungchul-2026-10-10-15:43 LiteRT(Yamnet VAD) 의존성
//       change(add)-hyungchul-2026-10-10-20:18 commons-compress(tar.bz2·zip 해제) 의존성
//       change(add)-hyungchul-2026-10-11-03:09 Compose material-icons-core(하단 탭 아이콘) 의존성 — Task 28
//       change(add)-hyungchul-2026-10-10-21:41 Vosk(vosk-android 0.3.75 + JNA 5.18.1 AAR) 의존성 — Task 22
//       change(add)-hyungchul-2026-10-10-20:57 sherpa-onnx v1.13.8 AAR 에서 arm64 JNI(libsherpa-onnx-jni.so)만 꺼내 APK 에 넣는 작업(ExtractSherpaJniTask)
//         — Kotlin API 는 소스(app/src/main/java/com/k2fsa/sherpa/onnx/)로 복사해 쓰므로 AAR 의 classes.jar 는 쓰지 않는다
//           (둘 다 넣으면 같은 클래스가 두 번 정의되어 R8 이 "Type ... is defined multiple times" 로 실패, 보고서 참조)

import java.security.MessageDigest // change(add)-hyungchul-2026-10-10-20:57 AAR SHA-256 검사
import javax.inject.Inject // change(add)-hyungchul-2026-10-10-20:57 Gradle 서비스 주입

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
    // change(add)-hyungchul-2026-10-11-03:09: 하단 탭 아이콘(Icons.Default.*, 기본 모음)
    implementation(libs.androidx.compose.material.icons.core)
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
    // change(add)-hyungchul-2026-10-10-15:43: Yamnet VAD(yamnet.tflite) 실행용 LiteRT 런타임 (JVM 단위 테스트에서는 추론 함수를 주입하므로 네이티브를 쓰지 않음)
    implementation(libs.litert)
    // change(add)-hyungchul-2026-10-10-20:18: 모델 압축(tar.bz2·zip) 안전 해제용 commons-compress (테스트의 압축 fixture 생성에도 사용)
    implementation(libs.commons.compress)
    // change(add)-hyungchul-2026-10-10-21:41: Vosk 엔진 — vosk-android(org.vosk.*, libvosk.so arm64) + JNA 안드로이드 AAR(libjnidispatch.so). JNA 는 AAR 형태 하나만 쓴다(jar 형태가 따라오면 중복 클래스)
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }
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

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: sherpa-onnx AAR 에서 arm64-v8a JNI 라이브러리만 꺼내기
 * 기능: AAR 파일의 SHA-256 이 고정값과 같은지 확인한 뒤(다르면 빌드 실패 — 다른 버전으로 조용히 대체하지 않음),
 *       jni/arm64-v8a/libsherpa-onnx-jni.so 하나만 outputDir/arm64-v8a/ 에 풀어 놓는다(그 밖 ABI·classes.jar 는 무시).
 *       이 AAR 은 onnxruntime 을 정적 링크했으므로 arm64 용 libonnxruntime.so 를 따로 갖고 있지 않다(APK 의 libonnxruntime.so 는 onnxruntime-android 것 1개).
 * 입력: aarFile(AAR 경로), expectedSha256(기대 해시), outputDir(jniLibs 형식 출력 폴더)
 * 출력: AAR 없음(change(add)-hyungchul-2026-10-10-21:32: tools/fetch_native_deps.sh 안내)·해시 불일치·.so 없음 → GradleException
 * 리턴: 없음
 */
abstract class ExtractSherpaJniTask : DefaultTask() {
    // change(add)-hyungchul-2026-10-10-21:32 @InputFile → @InputFiles: 파일이 없을 때 Gradle 입력 검증 오류 대신 작업이 안내 메시지로 실패하게 함(입력 추적은 그대로)
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aarFile: RegularFileProperty // 입력 AAR(tools/fetch_native_deps.sh 가 받아 둠)

    @get:Input
    abstract val expectedSha256: Property<String> // AAR 기대 SHA-256

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty // 출력 폴더(<outputDir>/arm64-v8a/libsherpa-onnx-jni.so)

    @get:Inject
    abstract val archives: ArchiveOperations // zipTree 제공 서비스

    @get:Inject
    abstract val fileOps: FileSystemOperations // sync 제공 서비스

    /**
     * 목적: 해시 검사 후 JNI .so 추출
     * 기능: AAR 을 1 MiB 단위로 읽어 SHA-256 을 계산·비교하고, 출력 폴더를 jni/arm64-v8a/libsherpa-onnx-jni.so 만 든 상태로 동기화한다.
     * 입력: 없음(속성 사용)
     * 출력: 실패 시 GradleException
     * 리턴: 없음
     */
    @TaskAction
    fun extract() {
        val aar = aarFile.get().asFile // AAR 파일
        if (!aar.isFile) { // change(add)-hyungchul-2026-10-10-21:32 AAR 없음
            throw GradleException("sherpa-onnx AAR 없음: ${aar.path} — 저장소 루트에서 tools/fetch_native_deps.sh 를 먼저 실행해 내려받으세요(SHA-256 검사 포함)") // change(add)-hyungchul-2026-10-10-21:32 안내 후 실패
        }
        val md = MessageDigest.getInstance("SHA-256") // 해시기
        aar.inputStream().use { input -> // 파일 열기
            val buf = ByteArray(1 shl 20) // 1 MiB 버퍼
            while (true) { // 끝까지
                val n = input.read(buf) // 읽기
                if (n < 0) break // 파일 끝
                md.update(buf, 0, n) // 해시 갱신
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) } // 실제 해시(16진)
        if (got != expectedSha256.get()) { // 불일치
            throw GradleException("sherpa-onnx AAR SHA-256 불일치: ${aar.path} 기대 ${expectedSha256.get()} 실제 $got (tools/fetch_native_deps.sh 로 다시 받기)") // 빌드 실패
        }
        fileOps.sync { // 출력 폴더를 아래 내용으로 맞춤(이전 결과 정리)
            from(archives.zipTree(aar)) { // AAR(zip) 내용
                include("jni/arm64-v8a/libsherpa-onnx-jni.so") // arm64 JNI 하나만
                eachFile { relativePath = RelativePath(true, "arm64-v8a", name) } // jni/arm64-v8a/x.so → arm64-v8a/x.so
            }
            into(outputDir) // 출력 폴더
            includeEmptyDirs = false // 빈 jni/ 폴더 만들지 않음
        }
        val so = outputDir.get().file("arm64-v8a/libsherpa-onnx-jni.so").asFile // 추출 결과
        if (!so.isFile) throw GradleException("AAR 에 jni/arm64-v8a/libsherpa-onnx-jni.so 가 없음: ${aar.path}") // 누락 시 실패
    }
}

// change(add)-hyungchul-2026-10-10-20:57: sherpa-onnx v1.13.8 AAR(SHA-256 은 tools/fetch_native_deps.sh 의 SHERPA_SHA 와 같은 값)에서 JNI 만 추출
val extractSherpaJni = tasks.register<ExtractSherpaJniTask>("extractSherpaJni") {
    aarFile.set(layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")) // 입력 AAR
    expectedSha256.set("b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471") // AAR SHA-256(change(add)-hyungchul-2026-10-10-21:32 tools/fetch_native_deps.sh 의 SHERPA_SHA 와 같은 값 — 바꿀 때 둘 다)
    outputDir.set(layout.buildDirectory.dir("generated/sherpaJni")) // 출력(빌드 폴더, git 밖)
}

// change(add)-hyungchul-2026-10-10-20:57: 모든 변형(debug/release)의 jniLibs 소스에 추출 폴더를 생성 소스로 등록(작업 의존성 자동 연결)
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(extractSherpaJni, ExtractSherpaJniTask::outputDir) // 추출 결과를 jniLibs 로
    }
}
