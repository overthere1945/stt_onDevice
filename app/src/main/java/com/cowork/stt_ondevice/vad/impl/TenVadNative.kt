// 파일명: TenVadNative.kt
// 목적: TEN VAD(TEN-framework/ten-vad 공식 Android 빌드 libten_vad.so) JNI 진입점 선언
// 기능: libstt_native.so(app/src/main/cpp/ten_vad_jni.cpp)를 올리고 ten_vad_create / ten_vad_process / ten_vad_destroy 를 노출한다.
//       handle 은 네이티브 구조체(TEN 핸들 + hop 크기) 주소이며 destroy 로 반드시 해제해야 한다.
// change(add)-hyungchul-2026-10-10-15:26
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

// change(add)-hyungchul-2026-10-10-15:26
/**
 * 목적: TEN VAD 네이티브 호출 래퍼
 * 기능: 처음 접근할 때 libstt_native.so 를 로드한다(로드 실패는 UnsatisfiedLinkError 로 그대로 드러남).
 *       한 handle 은 상태(이전 프레임)를 가지므로 한 스레드에서 순서대로만 쓴다.
 */
object TenVadNative {

    init {
        System.loadLibrary("stt_native") // libstt_native.so 로드(의존 libten_vad.so 함께 로드)
    }

    /**
     * 목적: TEN VAD 인스턴스 생성
     * 기능: ten_vad_create(&h, hop, threshold) 를 호출하고 hop 과 함께 보관한 네이티브 구조체 주소를 돌려준다.
     * 입력: hop(프레임 이동 샘플 수, 예: 256), threshold(flag 판정 임계값 0..1)
     * 출력: 생성 실패 시 IllegalStateException, 잘못된 인자는 IllegalArgumentException
     * 리턴: handle(0 이 아닌 주소)
     */
    external fun create(hop: Int, threshold: Float): Long

    /**
     * 목적: PCM16 전체의 hop 단위 음성 확률 계산
     * 기능: pcm 을 앞에서부터 floor(pcm.size / hop) 개 프레임으로 나눠 ten_vad_process 를 순서대로 부른다(잔여 샘플 버림).
     * 입력: handle(create 결과), pcm(16 kHz mono PCM16)
     * 출력: 처리 오류 시 IllegalStateException
     * 리턴: 프레임별 음성 확률(0..1, 높을수록 음성)
     */
    external fun processAll(handle: Long, pcm: ShortArray): FloatArray

    /**
     * 목적: TEN VAD 인스턴스 해제
     * 기능: ten_vad_destroy 후 네이티브 구조체를 지운다. handle 0 은 무시한다.
     * 입력: handle(create 결과)
     * 출력: 없음
     * 리턴: 없음
     */
    external fun destroy(handle: Long)
}
