# 파일명: app/src/main/cpp/CMakeLists.txt
# 목적: 앱 네이티브 라이브러리 libstt_native.so 빌드 설정(이후 Task 20 에서 whisper.cpp 를 같은 대상에 추가)
# 기능: - libfvad(서브모듈 third_party/libfvad) C 소스를 직접 나열해 정적 라이브러리 fvad 로 빌드·정적 링크
#         (서브모듈의 src/CMakeLists.txt 는 cmake_minimum_required 3.5 라 CMake 4 가 폐기 경고를 내므로 같은 소스 목록을 여기서 씀)
#       - TEN VAD 공식 prebuilt libten_vad.so 를 IMPORTED 공유 라이브러리로 링크(AGP 가 IMPORTED 의존 .so 를 APK 에 패키징)
#       - 16 KB 페이지 정렬 링크 옵션(-Wl,-z,max-page-size=16384), STL 은 gradle 인자 ANDROID_STL=c++_static
#       - change(add)-hyungchul-2026-10-10-20:35: whisper.cpp v1.9.5(서브모듈 third_party/whisper.cpp)를 add_subdirectory 로 정적 빌드(BUILD_SHARED_LIBS OFF)해
#         stt_native 에 링크, ggml CPU 백엔드는 -march=armv8.2-a+fp16+dotprod, OpenMP 는 ggml 기본(ON, NDK 기본대로 libomp.so 동적 링크 — AGP 가 APK 에 함께 포함),
#         debug 변형에서도 whisper/ggml 은 -O3 -DNDEBUG 로 컴파일(-O0 이면 기기 전사가 수십 배 느려 측정 불가), whisper_jni.cpp 추가
# change(add)-hyungchul-2026-10-10-15:26

cmake_minimum_required(VERSION 3.22.1) # 최소 CMake 버전(실제 사용 4.1.2, app/build.gradle.kts 에서 고정)
project(stt_native LANGUAGES C CXX) # 프로젝트: C(libfvad)·C++(JNI)

set(CMAKE_CXX_STANDARD 17) # C++17
set(CMAKE_CXX_STANDARD_REQUIRED ON) # 표준 필수

# ---- libfvad (dpirch/libfvad, BSD-3-Clause, 서브모듈 커밋 고정) ----
set(LIBFVAD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libfvad) # 서브모듈 경로
if(NOT EXISTS ${LIBFVAD_DIR}/src/CMakeLists.txt) # 서브모듈 미초기화
    message(FATAL_ERROR "libfvad 서브모듈이 없음: tools/fetch_native_deps.sh 또는 git submodule update --init 실행") # 명시적 실패
endif()
add_library(fvad STATIC # libfvad src/CMakeLists.txt 의 SOURCES 중 .c 파일(같은 목록)
    ${LIBFVAD_DIR}/src/fvad.c # 공개 API(fvad_new/process/...)
    ${LIBFVAD_DIR}/src/signal_processing/division_operations.c # 정수 나눗셈
    ${LIBFVAD_DIR}/src/signal_processing/energy.c # 에너지 계산
    ${LIBFVAD_DIR}/src/signal_processing/get_scaling_square.c # 스케일링
    ${LIBFVAD_DIR}/src/signal_processing/resample_48khz.c # 48 kHz 리샘플(16 kHz 에선 미사용, 원본 목록 유지)
    ${LIBFVAD_DIR}/src/signal_processing/resample_by_2_internal.c # 1/2 리샘플 내부
    ${LIBFVAD_DIR}/src/signal_processing/resample_fractional.c # 분수 리샘플
    ${LIBFVAD_DIR}/src/signal_processing/spl_inl.c # 인라인 신호처리 함수
    ${LIBFVAD_DIR}/src/vad/vad_core.c # VAD 핵심
    ${LIBFVAD_DIR}/src/vad/vad_filterbank.c # 필터뱅크
    ${LIBFVAD_DIR}/src/vad/vad_gmm.c # GMM 확률
    ${LIBFVAD_DIR}/src/vad/vad_sp.c # 신호처리 보조
)
target_include_directories(fvad PUBLIC ${LIBFVAD_DIR}/include) # 공개 헤더 fvad.h
set_property(TARGET fvad PROPERTY POSITION_INDEPENDENT_CODE ON) # 공유 라이브러리에 넣으므로 PIC(원본 설정과 같음)

# ---- TEN VAD (공식 prebuilt, tools/fetch_native_deps.sh 가 SHA-256 검사 후 배치) ----
set(TEN_VAD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ten-vad) # TEN 배치 폴더(gitignore)
set(TEN_VAD_SO ${TEN_VAD_DIR}/lib/${ANDROID_ABI}/libten_vad.so) # ABI 별 .so 경로
if(NOT EXISTS ${TEN_VAD_SO} OR NOT EXISTS ${TEN_VAD_DIR}/include/ten_vad.h) # 의존성 미준비
    message(FATAL_ERROR "TEN VAD 파일 없음(${TEN_VAD_SO}): tools/fetch_native_deps.sh 실행") # 명시적 실패
endif()
add_library(ten_vad SHARED IMPORTED) # IMPORTED 공유 라이브러리 대상
set_target_properties(ten_vad PROPERTIES
    IMPORTED_LOCATION ${TEN_VAD_SO} # 실제 .so 위치
    INTERFACE_INCLUDE_DIRECTORIES ${TEN_VAD_DIR}/include) # 헤더 경로 공개

# ---- whisper.cpp (ggml-org/whisper.cpp, MIT, 서브모듈 태그 v1.9.5 커밋 고정) ---- change(add)-hyungchul-2026-10-10-20:35
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp) # 서브모듈 경로
if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt) # 서브모듈 미초기화
    message(FATAL_ERROR "whisper.cpp 서브모듈이 없음: git submodule update --init app/src/main/cpp/third_party/whisper.cpp 실행") # 명시적 실패
endif()
# 아래 값은 CACHE FORCE 로 넣는다: whisper.cpp 최상위가 cmake_minimum_required(VERSION 3.5) 라 그 안에서는 정책 CMP0077 이 OLD 이고,
# OLD 에서는 option() 이 같은 이름의 일반 변수를 무시하므로 캐시 변수여야 적용된다.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "whisper/ggml 정적 라이브러리" FORCE) # libwhisper.a·libggml*.a → stt_native 에 정적 포함
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "릴리스 태그 빌드" FORCE) # whisper_version() = "1.9.5"(ON 이면 "1.9.5-dev")
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE) # 테스트 빌드 안 함
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE) # 예제 빌드 안 함
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE) # 서버 예제 빌드 안 함
set(GGML_NATIVE OFF CACHE BOOL "교차 컴파일: 호스트 CPU 감지 안 함" FORCE) # -mcpu=native 금지(빌드 PC 기준이 됨)
set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "ggml CPU 백엔드 -march" FORCE) # fp16 산술·int8 dot product(SM8450 /proc/cpuinfo: fphp asimdhp asimddp 확인, SM8750 도 지원)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE) # ccache 탐색 안 함(재현성)
# GGML_OPENMP 는 지정하지 않음 → ggml 기본값 ON(NDK 의 OpenMP, -fopenmp=libomp → libstt_native.so 가 libomp.so 를 NEEDED, AGP 가 APK lib/arm64-v8a/ 에 포함)
# whisper.cpp 최상위의 cmake_minimum_required(VERSION 3.5) 는 CMake 4.1.2 에서 폐기 "경고"만 내고 오류는 아니므로 CMAKE_POLICY_VERSION_MINIMUM 은 넘기지 않음
add_subdirectory(${WHISPER_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp EXCLUDE_FROM_ALL) # 링크되는 대상(whisper, ggml*)만 빌드
foreach(_t whisper ggml ggml-base ggml-cpu) # whisper.cpp 가 만드는 정적 라이브러리 대상
    if(TARGET ${_t}) # 대상이 있으면
        target_compile_options(${_t} PRIVATE -O3 -DNDEBUG) # debug 변형(NDK Debug 는 -O 미지정 = clang 기본 -O0)에서도 최적화, assert 끔
    endif()
endforeach()

# ---- 앱 JNI 라이브러리 ----
add_library(stt_native SHARED # Kotlin 은 System.loadLibrary("stt_native")
    fvad_jni.cpp # FvadNative JNI
    ten_vad_jni.cpp # TenVadNative JNI
    whisper_jni.cpp) # change(add)-hyungchul-2026-10-10-20:35 WhisperNative JNI
target_link_libraries(stt_native PRIVATE fvad ten_vad) # libfvad 정적 + TEN 공유 링크
target_link_libraries(stt_native PRIVATE whisper log) # change(add)-hyungchul-2026-10-10-20:35 whisper.cpp·ggml 정적 + logcat(liblog)
target_link_options(stt_native PRIVATE "-Wl,-z,max-page-size=16384") # LOAD 세그먼트 16 KB 정렬
