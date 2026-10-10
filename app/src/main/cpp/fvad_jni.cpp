// 파일명: fvad_jni.cpp
// 목적: libfvad(WebRTC VAD) 를 Kotlin(FvadNative)에서 부르는 JNI 구현
// 기능: Java_com_cowork_stt_1ondevice_vad_impl_FvadNative_processAll —
//       fvad_new → fvad_set_mode → fvad_set_sample_rate(16000) → floor(N/L) 프레임 fvad_process → fvad_free 를 한 번에 수행하고
//       프레임별 voiced 여부를 boolean[] 로 돌려준다(L = 16·frameMs, 잔여 샘플 버림).
// change(add)-hyungchul-2026-10-10-15:26
// change(add)-hyungchul-2026-10-10-20:35: throwJava 를 공용 헤더 jni_util.h 로 이동

#include <jni.h>    // JNI 타입·함수
#include <cstdint>  // int16_t
#include <memory>   // unique_ptr(자동 해제)
#include <vector>   // 버퍼
#include "jni_util.h" // change(add)-hyungchul-2026-10-10-20:35 공용 JNI 헬퍼(throwJava)

extern "C" {
#include "fvad.h"   // libfvad C API (third_party/libfvad/include)
}

namespace {

constexpr int kSampleRate = 16000; // 입력 샘플레이트(Hz), 앱 오디오는 항상 16 kHz

// change(add)-hyungchul-2026-10-10-20:35: 중복이던 throwJava 정의를 jni_util.h(stt::throwJava)로 옮기고 이 파일에서는 같은 이름으로 가져다 씀
using stt::throwJava; // 공용 예외 헬퍼

/**
 * 목적: Fvad 인스턴스 자동 해제용 삭제자
 * 기능: unique_ptr 가 범위를 벗어날 때 fvad_free 를 호출한다.
 */
struct FvadDeleter {
    void operator()(Fvad *p) const { fvad_free(p); } // 인스턴스 해제
};

} // namespace

/**
 * 목적: PCM16 전체의 프레임별 voiced 판정(FvadNative.processAll)
 * 기능: 인자 검사 → 새 Fvad 인스턴스 설정 → 프레임 순서대로 fvad_process → boolean[] 생성.
 * 입력: env, thiz(FvadNative 싱글턴), mode(0..3), frameMs(10/20/30), pcm(short[], 16 kHz mono)
 * 출력: 잘못된 인자 → IllegalArgumentException, 생성·처리 실패 → IllegalStateException/OutOfMemoryError
 * 리턴: 길이 floor(N/L) 의 boolean[] (예외 시 nullptr)
 */
extern "C" JNIEXPORT jbooleanArray JNICALL
Java_com_cowork_stt_1ondevice_vad_impl_FvadNative_processAll(JNIEnv *env, jobject /* thiz */, jint mode, jint frameMs, jshortArray pcm) {
    if (pcm == nullptr) { // null 입력
        throwJava(env, "java/lang/IllegalArgumentException", "pcm 이 null"); // 인자 오류
        return nullptr; // 예외 반환
    }
    if (frameMs != 10 && frameMs != 20 && frameMs != 30) { // libfvad 는 10/20/30 ms 프레임만 지원
        throwJava(env, "java/lang/IllegalArgumentException", "frameMs 는 10/20/30 만 가능"); // 인자 오류
        return nullptr; // 예외 반환
    }
    std::unique_ptr<Fvad, FvadDeleter> vad(fvad_new()); // 새 인스턴스(상태 초기), 범위 끝에서 자동 해제
    if (!vad) { // 메모리 부족
        throwJava(env, "java/lang/OutOfMemoryError", "fvad_new 실패"); // 할당 실패
        return nullptr; // 예외 반환
    }
    if (fvad_set_mode(vad.get(), mode) != 0) { // 모드 설정(0..3 이 아니면 -1)
        throwJava(env, "java/lang/IllegalArgumentException", "fvad_set_mode 실패: mode 는 0..3"); // 인자 오류
        return nullptr; // 예외 반환
    }
    if (fvad_set_sample_rate(vad.get(), kSampleRate) != 0) { // 16 kHz 설정
        throwJava(env, "java/lang/IllegalStateException", "fvad_set_sample_rate(16000) 실패"); // 설정 실패
        return nullptr; // 예외 반환
    }
    const jsize n = env->GetArrayLength(pcm); // 전체 샘플 수 N
    const jsize frameLen = (kSampleRate / 1000) * frameMs; // 프레임당 샘플 수 L
    const jsize numFrames = n / frameLen; // 완전한 프레임 수 floor(N/L)
    std::vector<int16_t> samples(static_cast<size_t>(n)); // 샘플 복사 버퍼
    if (n > 0) env->GetShortArrayRegion(pcm, 0, n, reinterpret_cast<jshort *>(samples.data())); // Java short[] → 버퍼
    std::vector<jboolean> flags(static_cast<size_t>(numFrames)); // 프레임별 결과
    for (jsize i = 0; i < numFrames; ++i) { // 프레임 순서대로(상태 유지)
        const int r = fvad_process(vad.get(), samples.data() + static_cast<size_t>(i) * frameLen, static_cast<size_t>(frameLen)); // 1 프레임 판정
        if (r < 0) { // -1: 잘못된 프레임 길이 등
            throwJava(env, "java/lang/IllegalStateException", "fvad_process 실패"); // 처리 실패
            return nullptr; // 예외 반환
        }
        flags[static_cast<size_t>(i)] = r == 1 ? JNI_TRUE : JNI_FALSE; // 1 = voiced
    }
    jbooleanArray out = env->NewBooleanArray(numFrames); // 결과 배열 생성
    if (out == nullptr) return nullptr; // 할당 실패(OutOfMemoryError 가 이미 설정됨)
    if (numFrames > 0) env->SetBooleanArrayRegion(out, 0, numFrames, flags.data()); // 결과 복사
    return out; // 반환(vad 는 unique_ptr 가 해제)
}
