// 파일명: FvadNative.kt
// 목적: libfvad(WebRTC VAD C 포크, dpirch/libfvad) JNI 진입점 선언
// 기능: libstt_native.so(app/src/main/cpp/fvad_jni.cpp)를 올리고, 16 kHz PCM16 전체를 프레임 단위로 판정하는 네이티브 함수를 노출한다.
//       한 번 호출마다 fvad_new → fvad_set_mode → fvad_set_sample_rate(16000) → 프레임별 fvad_process → fvad_free 를 한다
//       (WebRTC VAD 는 잡음 적응 상태가 있으므로 호출마다 새 인스턴스로 처음 프레임부터 순서대로 처리, 골든 gen_webrtc.py 와 같음).
// change(add)-hyungchul-2026-10-10-15:26
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

// change(add)-hyungchul-2026-10-10-15:26
/**
 * 목적: libfvad 네이티브 호출 래퍼
 * 기능: 처음 접근할 때 libstt_native.so 를 로드한다(로드 실패는 UnsatisfiedLinkError 로 그대로 드러남, 대체 구현 없음).
 */
object FvadNative {

    init {
        System.loadLibrary("stt_native") // libstt_native.so 로드(libten_vad.so 는 동적 링커가 함께 올림)
    }

    /**
     * 목적: PCM16 전체의 프레임별 voiced 판정
     * 기능: pcm 을 frameMs 길이(16·frameMs 샘플) 프레임으로 앞에서부터 floor(pcm.size / L) 개 판정한다. 잔여 샘플은 버린다.
     * 입력: mode(aggressiveness 0..3), frameMs(10/20/30), pcm(16 kHz mono PCM16)
     * 출력: 잘못된 mode/frameMs 는 IllegalArgumentException, libfvad 오류는 IllegalStateException(네이티브에서 throw)
     * 리턴: 프레임별 voiced 여부(길이 floor(pcm.size / (16·frameMs)))
     */
    external fun processAll(mode: Int, frameMs: Int, pcm: ShortArray): BooleanArray
}
