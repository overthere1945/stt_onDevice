// 파일명: ten_vad_jni.cpp
// 목적: TEN VAD 공식 Android 빌드(libten_vad.so, 헤더 ten_vad.h)를 Kotlin(TenVadNative)에서 부르는 JNI 구현
// 기능: create  — ten_vad_create(hop, threshold) 후 TEN 핸들과 hop 을 묶은 구조체 주소를 jlong 으로 반환
//       processAll — floor(N/hop) 개 프레임을 순서대로 ten_vad_process 해 프레임별 확률 float[] 반환(잔여 샘플 버림)
//       destroy — ten_vad_destroy 후 구조체 해제
// change(add)-hyungchul-2026-10-10-15:26
// change(add)-hyungchul-2026-10-10-20:35: throwJava 를 공용 헤더 jni_util.h 로 이동

#include <jni.h>     // JNI 타입·함수
#include <cstdint>   // int16_t, intptr_t
#include <new>       // std::nothrow
#include <vector>    // 버퍼
#include "jni_util.h" // change(add)-hyungchul-2026-10-10-20:35 공용 JNI 헬퍼(throwJava)

#include "ten_vad.h" // TEN VAD C API (third_party/ten-vad/include, tools/fetch_native_deps.sh 로 받음)

namespace {

/**
 * 목적: TEN 인스턴스와 hop 크기 보관
 * 기능: TEN 핸들은 불투명 포인터라 hop 을 알 수 없으므로 함께 저장해 processAll 이 프레임을 나눌 때 쓴다.
 */
struct TenVadHolder {
    ten_vad_handle_t handle = nullptr; // TEN 인스턴스 핸들
    size_t hop = 0;                    // 프레임 이동 샘플 수
};

// change(add)-hyungchul-2026-10-10-20:35: 중복이던 throwJava 정의를 jni_util.h(stt::throwJava)로 옮기고 이 파일에서는 같은 이름으로 가져다 씀
using stt::throwJava; // 공용 예외 헬퍼

} // namespace

/**
 * 목적: TEN VAD 인스턴스 생성(TenVadNative.create)
 * 기능: 인자 검사 → holder 할당 → ten_vad_create. 실패하면 holder 를 지우고 예외.
 * 입력: env, thiz, hop(>0 샘플), threshold(0..1)
 * 출력: 잘못된 인자 → IllegalArgumentException, 생성 실패 → IllegalStateException/OutOfMemoryError
 * 리턴: holder 주소(jlong), 예외 시 0
 */
extern "C" JNIEXPORT jlong JNICALL
Java_com_cowork_stt_1ondevice_vad_impl_TenVadNative_create(JNIEnv *env, jobject /* thiz */, jint hop, jfloat threshold) {
    if (hop <= 0 || !(threshold >= 0.0f && threshold <= 1.0f)) { // hop 양수, 임계값 [0,1] (NaN 거부)
        throwJava(env, "java/lang/IllegalArgumentException", "hop > 0, 0 <= threshold <= 1 이어야 함"); // 인자 오류
        return 0; // 예외 반환
    }
    auto *holder = new (std::nothrow) TenVadHolder(); // 보관 구조체 할당
    if (holder == nullptr) { // 메모리 부족
        throwJava(env, "java/lang/OutOfMemoryError", "TenVadHolder 할당 실패"); // 할당 실패
        return 0; // 예외 반환
    }
    holder->hop = static_cast<size_t>(hop); // hop 저장
    if (ten_vad_create(&holder->handle, holder->hop, threshold) != 0 || holder->handle == nullptr) { // TEN 인스턴스 생성
        delete holder; // 구조체 정리
        throwJava(env, "java/lang/IllegalStateException", "ten_vad_create 실패"); // 생성 실패
        return 0; // 예외 반환
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(holder)); // 주소를 jlong 으로
}

/**
 * 목적: PCM16 전체의 hop 단위 확률 계산(TenVadNative.processAll)
 * 기능: floor(N/hop) 프레임을 순서대로 ten_vad_process 해 확률만 모은다(flag 는 버림, Kotlin 이 확률로 후처리).
 * 입력: env, thiz, handle(create 결과), pcm(short[], 16 kHz mono)
 * 출력: 잘못된 handle/null pcm → IllegalArgumentException, 처리 실패 → IllegalStateException
 * 리턴: 길이 floor(N/hop) 의 float[] (예외 시 nullptr)
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_cowork_stt_1ondevice_vad_impl_TenVadNative_processAll(JNIEnv *env, jobject /* thiz */, jlong handle, jshortArray pcm) {
    auto *holder = reinterpret_cast<TenVadHolder *>(static_cast<intptr_t>(handle)); // jlong → 구조체 주소
    if (holder == nullptr || holder->handle == nullptr || pcm == nullptr) { // 잘못된 인자
        throwJava(env, "java/lang/IllegalArgumentException", "handle 또는 pcm 이 잘못됨"); // 인자 오류
        return nullptr; // 예외 반환
    }
    const jsize n = env->GetArrayLength(pcm); // 전체 샘플 수 N
    const size_t hop = holder->hop; // 프레임 이동 샘플 수
    const jsize numFrames = static_cast<jsize>(static_cast<size_t>(n) / hop); // 완전한 프레임 수 floor(N/hop)
    std::vector<int16_t> samples(static_cast<size_t>(n)); // 샘플 복사 버퍼
    if (n > 0) env->GetShortArrayRegion(pcm, 0, n, reinterpret_cast<jshort *>(samples.data())); // Java short[] → 버퍼
    std::vector<jfloat> probs(static_cast<size_t>(numFrames)); // 프레임별 확률
    for (jsize i = 0; i < numFrames; ++i) { // 프레임 순서대로(상태 유지)
        float prob = 0.0f; // 확률 출력
        int flag = 0; // flag 출력(사용 안 함)
        if (ten_vad_process(holder->handle, samples.data() + static_cast<size_t>(i) * hop, hop, &prob, &flag) != 0) { // 1 프레임 처리
            throwJava(env, "java/lang/IllegalStateException", "ten_vad_process 실패"); // 처리 실패
            return nullptr; // 예외 반환
        }
        probs[static_cast<size_t>(i)] = prob; // 확률 저장
    }
    jfloatArray out = env->NewFloatArray(numFrames); // 결과 배열 생성
    if (out == nullptr) return nullptr; // 할당 실패(OutOfMemoryError 설정됨)
    if (numFrames > 0) env->SetFloatArrayRegion(out, 0, numFrames, probs.data()); // 결과 복사
    return out; // 반환
}

/**
 * 목적: TEN VAD 인스턴스 해제(TenVadNative.destroy)
 * 기능: ten_vad_destroy 후 holder 를 지운다. handle 0 은 무시한다.
 * 입력: env, thiz, handle(create 결과)
 * 출력: 없음
 * 리턴: 없음
 */
extern "C" JNIEXPORT void JNICALL
Java_com_cowork_stt_1ondevice_vad_impl_TenVadNative_destroy(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    auto *holder = reinterpret_cast<TenVadHolder *>(static_cast<intptr_t>(handle)); // jlong → 구조체 주소
    if (holder == nullptr) return; // 0 이면 할 일 없음
    if (holder->handle != nullptr) ten_vad_destroy(&holder->handle); // TEN 인스턴스 해제(핸들은 NULL 로 바뀜)
    delete holder; // 구조체 해제
}
