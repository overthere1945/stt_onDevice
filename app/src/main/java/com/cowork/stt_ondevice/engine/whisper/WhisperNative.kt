// 파일명: WhisperNative.kt
// 목적: whisper.cpp v1.9.5(서브모듈 app/src/main/cpp/third_party/whisper.cpp) JNI 진입점 선언
// 기능: libstt_native.so(app/src/main/cpp/whisper_jni.cpp)를 올리고 모델 로드(init)·전사(transcribe)·중단(abort)·해제(free)·버전(version)을 노출한다.
//       ctx 는 네이티브 구조체(whisper_context + 원자 중단 플래그) 주소이며 free 로 반드시 해제해야 한다.
//       중단으로 끝난 전사는 WhisperAbortedException 으로 구분해 던진다(엔진이 EngineException(TRANSCRIBE, ABORTED) 로 바꾼다).
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine.whisper // whisper 엔진 패키지

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: whisper_full 이 abort_callback 으로 중단됐음을 알리는 예외
 * 기능: whisper_jni.cpp 가 whisper_full 의 0 이 아닌 반환값을 받았고 그때 중단 플래그가 켜져 있으면 이 예외를 던진다.
 * 입력: message(설명)
 */
class WhisperAbortedException(message: String) : RuntimeException(message)

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: whisper.cpp 네이티브 호출 래퍼
 * 기능: 처음 접근할 때 libstt_native.so 를 로드한다(로드 실패는 UnsatisfiedLinkError 로 그대로 드러남).
 *       한 ctx 의 transcribe 는 동시에 하나만 돌려야 하고(whisper_context 내부 상태 공유), abort 는 다른 스레드에서 불러도 된다.
 *       free 는 진행 중인 transcribe 가 끝난 뒤에만 불러야 한다(WhisperCppEngine 이 잠금으로 보장).
 */
object WhisperNative {

    init {
        System.loadLibrary("stt_native") // libstt_native.so 로드(whisper.cpp·ggml 정적 포함)
    }

    /**
     * 목적: 모델 로드
     * 기능: whisper_init_from_file_with_params(use_gpu=false) 로 ggml 모델을 올린다. 다국어 모델이 아니면(.en) 해제 후 거부한다.
     * 입력: modelPath(ggml-*.bin 절대 경로)
     * 출력: 로드 실패·다국어 아님 → IllegalStateException, 메모리 부족 → OutOfMemoryError, null 경로 → IllegalArgumentException
     * 리턴: ctx(0 이 아닌 네이티브 구조체 주소)
     */
    external fun init(modelPath: String): Long

    /**
     * 목적: 오디오 전사
     * 기능: whisper_full(BEAM_SEARCH, beam_size=beamSize, language 고정, translate=false, no_context=true, print_* false,
     *       token_timestamps=false, single_segment=false, n_threads=threads, abort_callback=원자 플래그)을 실행하고
     *       모든 segment 텍스트를 trim 해 공백 하나로 이어 붙인다. 시작할 때 중단 플래그를 끈다.
     * 입력: ctx(init 결과), samples(16 kHz mono float), lang(whisper 언어 코드 "ko"/"en"), threads(1 이상), beamSize(1 이상)
     * 출력: 중단 → WhisperAbortedException, whisper_full 실패 → IllegalStateException, 잘못된 인자 → IllegalArgumentException
     * 리턴: 전사 텍스트(빈 입력이면 "")
     */
    external fun transcribe(ctx: Long, samples: FloatArray, lang: String, threads: Int, beamSize: Int): String

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: ctx 의 원자 중단 플래그를 켠다. ctx 0 은 무시한다.
     *       whisper.cpp v1.9.5 는 abort_callback 을 인코더 그래프(conv·encoder·cross) 계산이 끝난 뒤와 디코더 호출마다 확인하므로
     *       인코딩 도중 요청하면 그 인코딩(30 s 창 1 회)이 끝난 뒤에 멈춘다(S22 Ultra base 실측 약 0.9 s).
     * 입력: ctx(init 결과)
     * 출력: 없음
     * 리턴: 없음
     */
    external fun abort(ctx: Long)

    /**
     * 목적: 모델 해제
     * 기능: whisper_free 후 네이티브 구조체를 지운다. ctx 0 은 무시한다.
     * 입력: ctx(init 결과)
     * 출력: 없음
     * 리턴: 없음
     */
    external fun free(ctx: Long)

    /**
     * 목적: whisper.cpp 버전 조회
     * 기능: whisper_version() 문자열을 돌려준다(릴리스 태그 빌드라 "1.9.5").
     * 입력: 없음
     * 출력: 없음
     * 리턴: 버전 문자열
     */
    external fun version(): String
}
