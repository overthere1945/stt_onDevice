// 파일명: VadPresets.kt
// 목적: VAD 프리셋 26종 레지스트리
// 기능: spec §7.3 의 모든 초기값을 보관하고 id 조회·계열별 상업 이용 판정을 제공한다.
package com.cowork.stt_ondevice.vad // VAD 패키지

import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 상태

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: 모든 VAD 구현이 파라미터를 읽는 단일 출처
 * 기능: Silero 8 + pyannote 4 + FSMN 2 + WebRTC 3 + TEN 2 + FireRed 2 + MarbleNet 1 + Yamnet 3 + NONE 1 = 26 종.
 */
object VadPresets {

    // 설명: SHERPA·WS 원본은 스트리밍 iterator 이지만 본 앱은 동일 파라미터를 get_speech_timestamps 로 적용한다.
    private const val SILERO_ITERATOR_NOTE = "원본은 스트리밍 iterator 로직이다. 본 앱은 동일 파라미터를 get_speech_timestamps 알고리즘으로 적용한다." // 공통 주의
    private const val PYA2_NOTE = "pyannote/segmentation(2.x) 모델 카드 값이다. 본 앱의 segmentation-3.0 출력에 적용하는 참고값이다." // pyannote 2.x 주의

    // FSMN 기본값(funasr/fsmn-vad config.yaml)
    private val fsmnDefault = FsmnParams( // FSMN_DEFAULT 파라미터
        maxEndSilenceTime = 800, maxStartSilenceTime = 3000, windowSizeMs = 200, // 무음/창
        silToSpeechTimeThres = 150, speechToSilTimeThres = 150, speech2NoiseRatio = 1.0f, // 전이 임계
        doExtend = 1, lookbackTimeStartPoint = 200, lookaheadTimeEndPoint = 100, // 확장
        maxSingleSegmentTime = 60000, snrThres = -100f, decibelThres = -100f, // 최대 구간/SNR/dB
        speechNoiseThres = 0.6f, fePriorThres = 1e-4f, speechNoiseThreshLow = -0.1f, speechNoiseThreshHigh = 0.3f, // 잡음 임계
        frameInMs = 10, frameLengthMs = 25, detectMode = 1, snrMode = 0, // 프레임/모드
    )

    /** 전체 프리셋(표시 순서 = spec 표 순서). */
    val all: List<VadPreset> = listOf(
        // ---- Silero (get_speech_timestamps 포팅) ----
        VadPreset("SILERO", VadFamily.SILERO, SileroParams(0.5f, 250, 400, 150), "프로젝트 기준선(Balanced)"),
        VadPreset("SILERO_SAFE", VadFamily.SILERO, SileroParams(0.3f, 150, 600, 300), "프로젝트 ASR Safe"),
        VadPreset(
            "SILERO_FW", VadFamily.SILERO, SileroParams(0.5f, 0, 2000, 400), "faster-whisper VadOptions",
            "WhisperModel 기본값이다. Batched pipeline 은 160 ms/30 s 로 다르다.",
        ),
        VadPreset("SILERO_DEFAULT", VadFamily.SILERO, SileroParams(0.5f, 250, 100, 30), "silero get_speech_timestamps 기본값"),
        VadPreset(
            "SILERO_SHERPA", VadFamily.SILERO, SileroParams(0.5f, 250, 500, 0, 20.0), "sherpa-onnx C++ SileroVadModelConfig",
            "sherpa-onnx Kotlin API 기본값은 250 ms/5 s 로 다르다. $SILERO_ITERATOR_NOTE",
        ),
        VadPreset("SILERO_QWEN3", VadFamily.SILERO, SileroParams(0.5f, 1500, 500, 30), "Qwen3-ASR-Toolkit audio_tools.py"),
        VadPreset(
            "SILERO_WS", VadFamily.SILERO, SileroParams(0.5f, 0, 500, 100), "whisper_streaming FixedVADIterator",
            SILERO_ITERATOR_NOTE,
        ),
        VadPreset("SILERO_USER", VadFamily.SILERO, SileroParams(0.4f, 250, 300, 100), "사용자 정의"),
        // ---- pyannote (Binarize) ----
        VadPreset("PYA3_DEFAULT", VadFamily.PYANNOTE, PyannoteParams(0.5f, 0.5f, 0.0, 0.0), "segmentation-3.0 VAD pipeline 기본값"),
        VadPreset("PYA2_DIHARD3", VadFamily.PYANNOTE, PyannoteParams(0.767f, 0.377f, 0.136, 0.067), "pyannote/segmentation 모델 카드(DIHARD3)", PYA2_NOTE),
        VadPreset("PYA2_AMI", VadFamily.PYANNOTE, PyannoteParams(0.684f, 0.577f, 0.181, 0.037), "pyannote/segmentation 모델 카드(AMI)", PYA2_NOTE),
        VadPreset("PYA2_VOXCONVERSE", VadFamily.PYANNOTE, PyannoteParams(0.767f, 0.713f, 0.182, 0.501), "pyannote/segmentation 모델 카드(VoxConverse)", PYA2_NOTE),
        // ---- FSMN (FsmnStateMachine) ----
        VadPreset("FSMN_DEFAULT", VadFamily.FSMN, fsmnDefault, "funasr/fsmn-vad config.yaml"),
        VadPreset("FSMN_ASR30", VadFamily.FSMN, fsmnDefault.copy(maxSingleSegmentTime = 30000), "FSMN_DEFAULT + max_single_segment_time 30000 (SenseVoice README 사용 예)"),
        // ---- WebRTC ----
        VadPreset("WEBRTC_LIB_DEFAULT", VadFamily.WEBRTC, WebRtcParams(0, 30, WebRtcStyle.RAW), "libfvad 기본 mode", "후처리 없음. 연속 voiced 프레임을 병합한다."),
        VadPreset(
            "WEBRTC_PYEXAMPLE", VadFamily.WEBRTC,
            WebRtcParams(3, 30, WebRtcStyle.RING_BUFFER, paddingMs = 300, ratio = 0.9f), "py-webrtcvad example.py",
            "padding 300 ms ring buffer, 90% voiced 시작/90% unvoiced 종료. mode 는 예제 CLI 인자이며 원본에 기본값이 없어 프리셋 값 3 을 썼다.",
        ),
        VadPreset("WEBRTC_ANDROIDVAD", VadFamily.WEBRTC, WebRtcParams(3, 20, WebRtcStyle.HANGOVER, speechMs = 50, silenceMs = 300), "gkonovalov/android-vad README", "mode 3(VERY_AGGRESSIVE)"),
        // ---- TEN ----
        VadPreset("TEN_DEFAULT", VadFamily.TEN, HangoverParams(0.5f, 0, 0), "ten-vad README", "hop 256, 후처리 없음. 연속 speech flag 를 병합한다."),
        VadPreset("TEN_SHERPA", VadFamily.TEN, SileroParams(0.5f, 250, 500, 0, 20.0), "sherpa-onnx ten-vad-model-config.h", "hop 256, 확률 입력에 SileroTimestamps 후처리(pad 0)."),
        // ---- FireRedVAD ----
        VadPreset(
            "FIRERED_OFFLINE", VadFamily.FIRERED,
            FireRedParams(FireRedMode.OFFLINE, 0.4f, 5, 20, 2000, 20, 0, 0, 0), "fireredvad vad.py",
        ),
        VadPreset(
            "FIRERED_STREAM", VadFamily.FIRERED,
            FireRedParams(FireRedMode.STREAM, 0.5f, 0, 8, 2000, 20, 0, 0, 5), "fireredvad stream_vad.py",
        ),
        // ---- MarbleNet ----
        VadPreset("MARBLENET_NEMO", VadFamily.MARBLENET, NemoParams(0.3f, 0.3f, 0.2, 0.2, 0.2, 0.2, true), "NeMo frame_vad_infer_postprocess.yaml"),
        // ---- Yamnet ----
        VadPreset("YAMNET_NORMAL", VadFamily.YAMNET, HangoverParams(0.3f, 30, 30), "android-vad Yamnet 매핑(NORMAL)"),
        VadPreset("YAMNET_AGGRESSIVE", VadFamily.YAMNET, HangoverParams(0.5f, 30, 30), "android-vad Yamnet 매핑(AGGRESSIVE)"),
        VadPreset("YAMNET_VERY_AGGRESSIVE", VadFamily.YAMNET, HangoverParams(0.85f, 30, 30), "android-vad Yamnet 매핑(VERY_AGGRESSIVE)"),
        // ---- NONE ----
        VadPreset("NONE", VadFamily.NONE, NoneParams, "VAD 없음 기준선", "엔진 maxChunkSec 고정 길이로 분할한다."),
    )

    /**
     * 목적: id 로 프리셋 조회
     * 기능: 일치하는 프리셋을 찾고, 없으면 다른 프리셋으로 대체하지 않고 예외를 던진다.
     * 입력: id(프리셋 id)
     * 출력: 없음
     * 리턴: 해당 VadPreset
     */
    fun byId(id: String): VadPreset =
        all.firstOrNull { it.id == id } ?: throw NoSuchElementException("알 수 없는 VAD 프리셋 id: $id") // 조용한 fallback 금지

    /**
     * 목적: 계열의 상업 이용 가능 여부 판정
     * 기능: spec §7.2 라이선스 검토 결과를 반환한다(FSMN·TEN 은 조건부, 나머지 허용).
     * 입력: family(VAD 계열)
     * 출력: 없음
     * 리턴: CommercialStatus
     */
    fun commercialOf(family: VadFamily): CommercialStatus = when (family) {
        VadFamily.FSMN, VadFamily.TEN -> CommercialStatus.CONDITIONAL // 모델 라이선스 조건 / Agora 경쟁 서비스 금지
        VadFamily.SILERO, VadFamily.PYANNOTE, VadFamily.WEBRTC, VadFamily.FIRERED,
        VadFamily.MARBLENET, VadFamily.YAMNET, VadFamily.NONE -> CommercialStatus.ALLOWED // 허용
    }
}
