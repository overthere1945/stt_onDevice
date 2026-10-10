# app/src/main/cpp/third_party — 네이티브 서드파티 의존성

change(add)-hyungchul-2026-10-10-15:26

이 폴더의 외부 코드·바이너리는 원문 그대로 쓰며 고치지 않는다. 빌드 설정은 `../CMakeLists.txt`, 내려받기는
`tools/fetch_native_deps.sh` 가 담당한다.

## 1. libfvad (`libfvad/`, git 서브모듈)

| 항목 | 값 |
|---|---|
| 출처 | https://github.com/dpirch/libfvad |
| 고정 커밋 | `532ab666c20d3cfda38bca63abbb0f152706c369` (2024-02-07, "Fix C11 compiler support check in configure.ac", libfvad 1.0.1 계열) |
| 라이선스 | BSD-3-Clause (WebRTC 원 코드 포함, `libfvad/LICENSE`·`libfvad/PATENTS` 원문) |
| 받는 방법 | `git submodule update --init app/src/main/cpp/third_party/libfvad` (fetch 스크립트가 함께 실행) |
| 빌드 | `../CMakeLists.txt` 가 `libfvad/src/CMakeLists.txt` 와 같은 C 소스 목록을 직접 나열해 정적 라이브러리 `fvad` 로 빌드 → `libstt_native.so` 에 정적 링크 |

서브모듈의 `src/CMakeLists.txt` 를 `add_subdirectory` 로 쓰지 않는 이유: `cmake_minimum_required(VERSION 3.5)` 라
CMake 4.1.2 가 폐기 경고를 낸다. 소스 목록은 같다(헤더 제외 .c 12개).

## 2. TEN VAD (`ten-vad/`, gitignore — 저장소에 없음)

| 파일 | 원본 경로(커밋 고정) | SHA-256 |
|---|---|---|
| `ten-vad/lib/arm64-v8a/libten_vad.so` | `lib/Android/arm64-v8a/libten_vad.so` | `6bc63b352ff5d0752b7c90ca7eb0b23189d5e9cbea9979f82fac962d2ac3c5a5` |
| `ten-vad/include/ten_vad.h` | `include/ten_vad.h` | `21409e22b80a104d1eeeb11527812baeaf96f9a59819e2e2fb3b3d462c609f7f` |
| `ten-vad/LICENSE` | `LICENSE` | `03d862b922867b8c3406a0c17791e256ed3d76f197d88c1996c4aebfb79f688e` |

- 출처: https://github.com/TEN-framework/ten-vad , 커밋 `d68ab71c8d5fbd8f98e97ae7c9986af76b5d72b1`
  (골든 도구 `tools/golden/gen_ten.py` 가 쓴 Python 패키지 1.0.6.9 와 같은 커밋).
- 라이선스: Apache-2.0 + 추가 조건(Agora 경쟁 서비스 배포 금지 등, `ten-vad/LICENSE` 원문). 앱 프리셋에서 상업 사용 "조건부"로 표시.
- 공식 prebuilt 이므로 직접 빌드하지 않는다. 받은 .so 의 LOAD 정렬은 이미 0x4000(16 KB) 이다(확인함).
- 빌드: `../CMakeLists.txt` 가 `IMPORTED` 공유 라이브러리로 링크하고, AGP 9.4.1 이 IMPORTED 의존 .so 를 APK `lib/arm64-v8a/` 에 자동 포함한다
  (`jniLibs` 폴더·`pickFirst` 불필요, `assembleDebug` 결과로 확인).

## 3. whisper.cpp (`whisper.cpp/`, git 서브모듈) — change(add)-hyungchul-2026-10-10-20:35

| 항목 | 값 |
|---|---|
| 출처 | https://github.com/ggml-org/whisper.cpp |
| 고정 | 태그 `v1.9.5`, 커밋 `d1be6fde11ac6e0407606b4e42fe72d34add8037` (2026-10-06, "whisper : bump version to 1.9.5 (#4102)") |
| 라이선스 | MIT (`whisper.cpp/LICENSE` 원문, ggml 포함) |
| 받는 방법 | `git submodule update --init app/src/main/cpp/third_party/whisper.cpp` |
| 빌드 | `../CMakeLists.txt` 가 `add_subdirectory(... EXCLUDE_FROM_ALL)` 로 정적 라이브러리(whisper, ggml, ggml-base, ggml-cpu)를 만들어 `libstt_native.so` 에 링크 |

빌드 옵션(`../CMakeLists.txt` 에서 CACHE FORCE 로 지정):
- `BUILD_SHARED_LIBS=OFF`, `WHISPER_BUILD_IS_DEV=OFF`(→ `whisper_version()` = `1.9.5`), 테스트·예제·서버 OFF, `GGML_CCACHE=OFF`.
- `GGML_NATIVE=OFF`, `GGML_CPU_ARM_ARCH=armv8.2-a+fp16+dotprod` → ggml CPU 백엔드에 `-march=armv8.2-a+fp16+dotprod`.
  Galaxy S22 Ultra(SM8450) `/proc/cpuinfo` 의 `fphp asimdhp asimddp` 로 fp16·dotprod 지원을 확인했다(S25 Ultra SM8750 도 지원하는 상위 아키텍처).
- `GGML_OPENMP` 는 기본값(ON). NDK 기본대로 `libomp.so` 를 동적 링크하며 AGP 가 APK `lib/arm64-v8a/libomp.so` 로 함께 넣는다(16 KB 정렬 확인).
- debug 변형에서도 whisper/ggml 대상만 `-O3 -DNDEBUG` 로 컴파일한다(NDK Debug 는 최적화 없음 → 기기 전사가 지나치게 느림). 앱 JNI 코드는 debug 설정 유지.
- whisper.cpp 최상위 `cmake_minimum_required(VERSION 3.5)` 는 CMake 4.1.2 에서 폐기 경고만 내므로 `CMAKE_POLICY_VERSION_MINIMUM` 은 쓰지 않는다.

알려진 동작: v1.9.5 의 `abort_callback` 은 인코더 그래프 전체(conv·encoder·cross) 계산 후와 디코더 호출마다만 확인된다.
인코딩 도중 취소하면 그 인코딩이 끝날 때까지 기다린다(소스를 고치지 않으므로 그대로 둠).

## 4. 다시 받기

```bash
tools/fetch_native_deps.sh   # 서브모듈(libfvad·whisper.cpp) 초기화 + TEN 3개 파일 + sherpa-onnx AAR(app/libs/) SHA-256 검사
```

해시가 다르면 아무것도 덮어쓰지 않고 실패한다(다른 버전으로 조용히 대체하지 않음).
