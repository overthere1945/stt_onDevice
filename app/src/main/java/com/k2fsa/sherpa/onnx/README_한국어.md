# com.k2fsa.sherpa.onnx — sherpa-onnx Kotlin API 원문 복사본

<!-- change(add)-hyungchul-2026-10-10-20:57 (Task 21) -->

이 폴더의 `.kt` 파일 8개는 sherpa-onnx 공식 저장소의 Kotlin API 를 **한 글자도 고치지 않고** 그대로 복사한 것이다.
그래서 프로젝트 주석 규칙(한국어 파일·함수·라인 주석, change 태그)의 예외이며, 출처·버전·라이선스는 이 문서에 적는다.
고칠 일이 생기면 원문을 고치지 말고 sherpa-onnx 버전을 올려 다시 복사한다(네이티브 JNI 와 함께 바꿔야 한다).

## 1. 출처와 라이선스

| 항목 | 값 |
|---|---|
| 저장소 | https://github.com/k2-fsa/sherpa-onnx |
| 버전 | 태그 `v1.13.8` (커밋 `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`) |
| 원본 폴더 | `sherpa-onnx/kotlin-api/` |
| 라이선스 | Apache License 2.0 — https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/LICENSE (저작권: k2-fsa/sherpa-onnx 기여자, LICENSE 본문에 개별 저작권자 표기 없음) |
| 복사일 | 2026-10-10 |

Apache-2.0 조건: 라이선스 사본·저작권 고지 유지, 수정 시 수정 사실 표시(이 복사본은 수정하지 않음).

## 2. 파일 목록 (원본 URL · SHA-256)

복사한 뒤 내려받은 원본과 `cmp` 로 바이트 단위 동일함을 확인했다.

| 파일 | 원본 URL | 바이트 | SHA-256 |
|---|---|---|---|
| `OfflineRecognizer.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/OfflineRecognizer.kt | 56380 | `85054fa9722913bce96c94c5c7eaaa7fd99606b79fbfbeac6cfb60040eae2164` |
| `OnlineRecognizer.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/OnlineRecognizer.kt | 30509 | `3204ff816361d99d94dda1e8c26681f6d4a5ad749a68d8fee93b4e8bb2b21d17` |
| `OfflineStream.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/OfflineStream.kt | 1080 | `5c90b7c060cdd0a2222cc935b54f69465a4a7544de29240bafe4d3a8d86dbb6e` |
| `OnlineStream.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/OnlineStream.kt | 1178 | `6b9f8de317e6a0bb51f0f29d940617b781cb31b190e1c725dd438231836c06ab` |
| `FeatureConfig.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/FeatureConfig.kt | 301 | `829c7adc4db1324f21b9a9cbf5bf10eacf759714fe50956f48317c1ffef044f6` |
| `HomophoneReplacerConfig.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/HomophoneReplacerConfig.kt | 170 | `fe926b6413f4d87e67c41feb21b8363923a67cbd3a5eb687b76390a5f1f7db60` |
| `QnnConfig.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/QnnConfig.kt | 156 | `86c963f27677f690eb0d6639735052eba5e60d9f81ccd4c1df0aa831fd680d6f` |
| `VersionInfo.kt` | https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/VersionInfo.kt | 627 | `8a9ada883d8cb802b4e70cc6770af20f4b7d85935a16b0b71974d22d55d0c90a` |

고른 기준: `OfflineRecognizer`·`OnlineRecognizer` 와 그 설정·스트림·결과 클래스가 컴파일되는 데 필요한 최소 집합
(`FeatureConfig`·`HomophoneReplacerConfig`·`QnnConfig` 는 인식기 설정이 참조, `VersionInfo` 는 `PrepareInfo.runtimeVersion` 용).
TTS·VAD·화자 분리·키워드 검출 등 나머지 `kotlin-api/*.kt` 는 쓰지 않아 복사하지 않았다.

## 3. 네이티브 JNI 와의 짝

- JNI 라이브러리 `libsherpa-onnx-jni.so` 는 같은 v1.13.8 릴리스의 `sherpa-onnx-static-link-onnxruntime-1.13.8.aar`
  (SHA-256 `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`, `tools/fetch_native_deps.sh` 가 `app/libs/` 에 받음)에서
  **arm64-v8a `.so` 하나만** 꺼내 쓴다(`app/build.gradle.kts` 의 `ExtractSherpaJniTask`, 추출본 SHA-256 `b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8`).
- AAR 안의 `classes.jar` 는 쓰지 않는다. 이 폴더 소스와 같은 패키지·같은 클래스를 담고 있어, AAR 을 `implementation(files(...))` 로 통째로 넣으면
  debug APK 에는 같은 클래스가 dex 두 곳에 들어가고(classes7.dex·classes12.dex 에서 `OfflineRecognizer` 2회 정의 확인),
  release 빌드는 R8 이 `Type com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig is defined multiple times` 로 실패한다(2026-10-10 실제 빌드로 확인).
- 짝 확인: 이 폴더 소스를 컴파일한 클래스 48개와 AAR `classes.jar` 의 같은 이름 클래스를 `javap -p -s`(모든 필드·메서드·`native` 시그니처)로
  비교해 **48개 모두 같음**을 확인했다. 차이는 앱 모듈에 적용된 Compose 컴파일러 플러그인이 넣는 `$stable` 정적 필드뿐이다(JNI 와 무관).
  즉 JNI 함수 이름·시그니처(`Java_com_k2fsa_sherpa_onnx_...`)가 AAR 의 `.so` 와 맞는다.
