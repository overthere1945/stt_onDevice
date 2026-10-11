// 파일명: whisper_jni.cpp
// 목적: whisper.cpp v1.9.5(서브모듈 third_party/whisper.cpp, libstt_native.so 에 정적 링크)를 Kotlin(WhisperNative)에서 부르는 JNI 구현
// 기능: init      — whisper_init_from_file_with_params(use_gpu=false) 로 모델 로드, 다국어 모델만 허용, whisper_context 와 원자 중단 플래그를 묶은 구조체 주소 반환
//       transcribe — whisper_full(BEAM_SEARCH, beam_size, 언어 고정, translate=false, no_context=true, print_* false, token_timestamps=false,
//                    single_segment=false, n_threads, abort_callback=중단 플래그) 후 모든 segment 텍스트를 trim 해 공백 하나로 이어 붙여 반환
//       abort     — 중단 플래그 켜기(다른 스레드에서 호출 가능, 인코딩 도중이면 그 인코딩이 끝난 뒤 멈춤)
//       free      — whisper_free 후 구조체 해제
//       version   — whisper_version()
//       whisper.cpp/ggml 로그는 logcat(태그 "whisper.cpp")으로 보낸다. 모든 오류는 Java 예외로 바꾸고 C++ 예외가 JNI 밖으로 나가지 않게 막는다.
// change(add)-hyungchul-2026-10-10-20:35

#include <jni.h>              // JNI 타입·함수
#include <android/log.h>      // logcat 출력
#include <atomic>             // 원자 중단 플래그
#include <cstdint>            // intptr_t
#include <exception>          // std::exception
#include <mutex>              // std::call_once
#include <new>                // std::nothrow, std::bad_alloc
#include <string>             // 문자열 결합
#include <vector>             // 샘플 버퍼
#include "jni_util.h"         // 공용 JNI 헬퍼(throwJava, newStringUtf8)

#include "whisper.h"          // whisper.cpp C API (third_party/whisper.cpp/include)

namespace {

using stt::throwJava;     // 공용 예외 헬퍼
using stt::newStringUtf8; // 표준 UTF-8 → Java String

constexpr const char *kLogTag = "whisper.cpp"; // whisper/ggml 로그 태그
constexpr const char *kAbortedClass = "com/cowork/stt_ondevice/engine/whisper/WhisperAbortedException"; // 중단 예외 클래스

/**
 * 목적: whisper 컨텍스트와 중단 플래그 보관
 * 기능: abort_callback 의 user_data 로 이 구조체를 넘겨 abort() 가 켠 플래그를 ggml 계산 중에 확인하게 한다.
 */
struct WhisperHolder {
    whisper_context *ctx = nullptr;   // whisper 컨텍스트
    std::atomic<bool> abort{false};   // 중단 요청 플래그
};

std::once_flag gLogOnce; // 로그 콜백 1회 등록용

/**
 * 목적: whisper.cpp/ggml 로그를 logcat 으로 보냄
 * 기능: ggml 로그 수준을 안드로이드 우선순위로 바꿔 __android_log_write 한다.
 * 입력: level(ggml 로그 수준), text(로그 문자열), user_data(사용 안 함)
 * 출력: logcat
 * 리턴: 없음
 */
void logToLogcat(ggml_log_level level, const char *text, void * /* user_data */) {
    int prio = ANDROID_LOG_INFO; // 기본 INFO(CONT 포함)
    if (level == GGML_LOG_LEVEL_DEBUG) prio = ANDROID_LOG_DEBUG; // 디버그
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN; // 경고
    else if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR; // 오류
    __android_log_write(prio, kLogTag, text != nullptr ? text : ""); // 출력
}

/**
 * 목적: ggml 계산 중단 여부 확인(abort_callback)
 * 기능: whisper.cpp v1.9.5 는 인코더 그래프(conv·encoder·cross) 계산 뒤와 디코더 호출마다 이 함수를 부르며(인코딩 도중에는 안 부름),
 *       true 를 돌려주면 whisper_full 이 0 이 아닌 값(-6 인코딩 / -7·-8 디코딩 등)으로 끝난다.
 * 입력: data(WhisperHolder 주소)
 * 출력: 없음
 * 리턴: 중단 요청이 있으면 true
 */
bool abortCallback(void *data) {
    return static_cast<WhisperHolder *>(data)->abort.load(std::memory_order_relaxed); // 플래그 읽기
}

/**
 * 목적: 앞뒤 공백 제거
 * 기능: 공백·탭·개행을 양 끝에서 지운다(whisper segment 텍스트는 보통 앞에 공백이 붙음).
 * 입력: s(segment 텍스트)
 * 출력: 없음
 * 리턴: trim 된 문자열
 */
std::string trim(const std::string &s) {
    const char *ws = " \t\r\n"; // 지울 문자
    const auto b = s.find_first_not_of(ws); // 첫 비공백 위치
    if (b == std::string::npos) return {}; // 전부 공백
    const auto e = s.find_last_not_of(ws); // 마지막 비공백 위치
    return s.substr(b, e - b + 1); // 잘라 반환
}

/**
 * 목적: jlong → WhisperHolder 주소 변환
 * 기능: 단순 캐스트(0 이면 nullptr).
 * 입력: handle(init 결과)
 * 출력: 없음
 * 리턴: 구조체 주소
 */
WhisperHolder *toHolder(jlong handle) {
    return reinterpret_cast<WhisperHolder *>(static_cast<intptr_t>(handle)); // 캐스트
}

} // namespace

/**
 * 목적: 모델 로드(WhisperNative.init)
 * 기능: 로그 콜백 1회 등록 → use_gpu=false 로 whisper_init_from_file_with_params(flash_attn 은 라이브러리 기본값 유지) →
 *       다국어 모델인지 확인(.en 금지, spec §9.1) → 구조체 할당.
 * 입력: env, thiz, modelPath(ggml-*.bin 경로)
 * 출력: null 경로 → IllegalArgumentException, 로드 실패·다국어 아님 → IllegalStateException, 메모리 부족 → OutOfMemoryError
 * 리턴: 구조체 주소(jlong), 예외 시 0
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_cowork_stt_1ondevice_engine_whisper_WhisperNative_init(JNIEnv *env, jobject /* thiz */, jstring modelPath) {
    if (modelPath == nullptr) { // null 경로
        throwJava(env, "java/lang/IllegalArgumentException", "modelPath 가 null"); // 인자 오류
        return 0; // 예외 반환
    }
    std::call_once(gLogOnce, [] { whisper_log_set(logToLogcat, nullptr); }); // 로그를 logcat 으로(1회)
    const char *path = env->GetStringUTFChars(modelPath, nullptr); // Java 문자열 → C 문자열
    if (path == nullptr) return 0; // 할당 실패(OutOfMemoryError 설정됨)
    const std::string pathCopy(path); // 메시지용 복사
    env->ReleaseStringUTFChars(modelPath, path); // C 문자열 해제
    whisper_context *ctx = nullptr; // 로드 결과
    try { // C++ 예외가 JNI 밖으로 나가지 않게
        whisper_context_params cparams = whisper_context_default_params(); // 기본 컨텍스트 설정
        cparams.use_gpu = false; // CPU 전용(GPU 백엔드 미빌드)
        ctx = whisper_init_from_file_with_params(pathCopy.c_str(), cparams); // 모델 로드
    } catch (const std::bad_alloc &) { // 메모리 부족
        throwJava(env, "java/lang/OutOfMemoryError", "whisper 모델 로드 중 메모리 부족"); // OOM
        return 0; // 예외 반환
    } catch (const std::exception &e) { // 그 밖의 C++ 예외
        throwJava(env, "java/lang/IllegalStateException", (std::string("whisper 모델 로드 예외: ") + e.what()).c_str()); // 실패
        return 0; // 예외 반환
    }
    if (ctx == nullptr) { // 로드 실패(파일 손상·형식 불일치 등, 상세는 logcat)
        throwJava(env, "java/lang/IllegalStateException", ("whisper_init_from_file_with_params 실패: " + pathCopy).c_str()); // 실패
        return 0; // 예외 반환
    }
    if (!whisper_is_multilingual(ctx)) { // 영어 전용(.en) 모델
        whisper_free(ctx); // 정리
        throwJava(env, "java/lang/IllegalStateException", ("다국어 모델이 아님(.en 모델 금지): " + pathCopy).c_str()); // 거부
        return 0; // 예외 반환
    }
    auto *holder = new (std::nothrow) WhisperHolder(); // 구조체 할당
    if (holder == nullptr) { // 메모리 부족
        whisper_free(ctx); // 정리
        throwJava(env, "java/lang/OutOfMemoryError", "WhisperHolder 할당 실패"); // OOM
        return 0; // 예외 반환
    }
    holder->ctx = ctx; // 컨텍스트 보관
    return static_cast<jlong>(reinterpret_cast<intptr_t>(holder)); // 주소를 jlong 으로
}

/**
 * 목적: 오디오 전사(WhisperNative.transcribe)
 * 기능: 인자 검사 → 중단 플래그 끄기 → whisper_full 설정 채우기 → 실행 → 실패면 중단/일반 실패 구분 → segment 텍스트 trim·공백 결합.
 * 입력: env, thiz, handle(init 결과), samples(16 kHz mono float), lang("ko"/"en" 등 whisper 언어 코드), threads(≥1), beamSize(≥1)
 * 출력: 잘못된 인자 → IllegalArgumentException, 중단 → WhisperAbortedException, 실패 → IllegalStateException, 메모리 부족 → OutOfMemoryError
 * 리턴: 전사 텍스트(빈 입력이면 ""), 예외 시 nullptr
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_cowork_stt_1ondevice_engine_whisper_WhisperNative_transcribe(JNIEnv *env, jobject /* thiz */, jlong handle, jfloatArray samples,
                                                                      jstring lang, jint threads, jint beamSize) {
    WhisperHolder *holder = toHolder(handle); // 구조체 주소
    if (holder == nullptr || holder->ctx == nullptr || samples == nullptr || lang == nullptr) { // 잘못된 인자
        throwJava(env, "java/lang/IllegalArgumentException", "handle/samples/lang 이 잘못됨"); // 인자 오류
        return nullptr; // 예외 반환
    }
    if (threads < 1 || beamSize < 1) { // 스레드·빔 크기 검사
        throwJava(env, "java/lang/IllegalArgumentException", "threads 와 beamSize 는 1 이상이어야 함"); // 인자 오류
        return nullptr; // 예외 반환
    }
    const char *langChars = env->GetStringUTFChars(lang, nullptr); // 언어 코드
    if (langChars == nullptr) return nullptr; // 할당 실패(예외 설정됨)
    const std::string language(langChars); // 복사(whisper_full 동안 유지)
    env->ReleaseStringUTFChars(lang, langChars); // 해제
    if (language.empty() || language == "auto" || whisper_lang_id(language.c_str()) < 0) { // 자동 감지·모르는 언어 금지(언어 고정)
        throwJava(env, "java/lang/IllegalArgumentException", ("지원하지 않는 whisper 언어 코드: '" + language + "'").c_str()); // 인자 오류
        return nullptr; // 예외 반환
    }
    const jsize n = env->GetArrayLength(samples); // 샘플 수
    if (n == 0) return newStringUtf8(env, std::string()); // 빈 입력은 빈 텍스트
    std::vector<float> pcm(static_cast<size_t>(n)); // 샘플 복사 버퍼
    env->GetFloatArrayRegion(samples, 0, n, pcm.data()); // Java float[] → 버퍼

    holder->abort.store(false, std::memory_order_relaxed); // 이전 중단 요청 지우기(새 chunk 시작)

    std::string joined; // 결합 결과
    try { // C++ 예외가 JNI 밖으로 나가지 않게
        whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH); // 빔 서치 기본값(온도 fallback 등 기본 유지)
        p.beam_search.beam_size = beamSize; // 빔 크기(PC faster-whisper 기본 5 와 동일)
        p.n_threads = threads; // 스레드 수
        p.language = language.c_str(); // 언어 고정(ko/en)
        p.detect_language = false; // 언어 감지 안 함
        p.translate = false; // task=transcribe
        p.no_context = true; // 이전 chunk 텍스트를 프롬프트로 쓰지 않음
        p.no_timestamps = false; // 타임스탬프 토큰 디코딩은 라이브러리 기본(segment 분할용)
        p.token_timestamps = false; // 토큰 단위 타임스탬프 불필요
        p.single_segment = false; // 여러 segment 허용
        p.print_special = false; // 출력 끔
        p.print_progress = false; // 진행률 출력 끔
        p.print_realtime = false; // 실시간 출력 끔
        p.print_timestamps = false; // 타임스탬프 출력 끔
        p.abort_callback = abortCallback; // 중단 확인 콜백
        p.abort_callback_user_data = holder; // 콜백 데이터 = 구조체
        const int ret = whisper_full(holder->ctx, p, pcm.data(), static_cast<int>(n)); // 전사 실행
        if (ret != 0) { // 실패 또는 중단
            if (holder->abort.load(std::memory_order_relaxed)) { // 중단 요청으로 끝남
                throwJava(env, kAbortedClass, "whisper_full 중단됨(abort_callback)"); // 중단 예외
            } else { // 일반 실패
                throwJava(env, "java/lang/IllegalStateException", ("whisper_full 실패: 반환값 " + std::to_string(ret)).c_str()); // 실패 예외
            }
            return nullptr; // 예외 반환
        }
        const int nSeg = whisper_full_n_segments(holder->ctx); // segment 수
        for (int i = 0; i < nSeg; ++i) { // segment 마다
            const char *t = whisper_full_get_segment_text(holder->ctx, i); // segment 텍스트
            const std::string s = trim(t != nullptr ? t : ""); // 앞뒤 공백 제거
            if (s.empty()) continue; // 빈 segment 건너뜀
            if (!joined.empty()) joined.push_back(' '); // 공백 하나로 구분
            joined += s; // 이어 붙임
        }
    } catch (const std::bad_alloc &) { // 메모리 부족
        throwJava(env, "java/lang/OutOfMemoryError", "whisper 전사 중 메모리 부족"); // OOM
        return nullptr; // 예외 반환
    } catch (const std::exception &e) { // 그 밖의 C++ 예외
        throwJava(env, "java/lang/IllegalStateException", (std::string("whisper 전사 예외: ") + e.what()).c_str()); // 실패
        return nullptr; // 예외 반환
    }
    return newStringUtf8(env, joined); // 표준 UTF-8 → Java String
}

/**
 * 목적: 진행 중 전사 중단 요청(WhisperNative.abort)
 * 기능: 중단 플래그를 켠다. handle 0 은 무시한다.
 * 입력: env, thiz, handle(init 결과)
 * 출력: 없음
 * 리턴: 없음
 */
extern "C" JNIEXPORT void JNICALL
Java_com_cowork_stt_1ondevice_engine_whisper_WhisperNative_abort(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    WhisperHolder *holder = toHolder(handle); // 구조체 주소
    if (holder == nullptr) return; // 0 이면 할 일 없음
    holder->abort.store(true, std::memory_order_relaxed); // 중단 요청
}

/**
 * 목적: 모델 해제(WhisperNative.free)
 * 기능: whisper_free 후 구조체를 지운다. handle 0 은 무시한다.
 * 입력: env, thiz, handle(init 결과)
 * 출력: 없음
 * 리턴: 없음
 */
extern "C" JNIEXPORT void JNICALL
Java_com_cowork_stt_1ondevice_engine_whisper_WhisperNative_free(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    WhisperHolder *holder = toHolder(handle); // 구조체 주소
    if (holder == nullptr) return; // 0 이면 할 일 없음
    if (holder->ctx != nullptr) whisper_free(holder->ctx); // 컨텍스트 해제
    delete holder; // 구조체 해제
}

/**
 * 목적: whisper.cpp 버전 조회(WhisperNative.version)
 * 기능: whisper_version() 을 Java 문자열로 돌려준다.
 * 입력: env, thiz
 * 출력: 없음
 * 리턴: 버전 문자열
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_cowork_stt_1ondevice_engine_whisper_WhisperNative_version(JNIEnv *env, jobject /* thiz */) {
    return env->NewStringUTF(whisper_version()); // ASCII 버전 문자열
}
