// 파일명: VadParams.kt
// 목적: VAD 계열별 후처리 파라미터 타입 정의
// 기능: 프리셋이 들고 다니는 파라미터를 sealed interface 와 계열별 data class 로 표현한다.
package com.cowork.stt_ondevice.vad // VAD 패키지

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: 모든 VAD 파라미터의 공통 상위 타입
 * 기능: VadPreset.params 의 타입. 각 VAD 구현은 자기 계열 구현체로 캐스팅해 사용한다.
 */
sealed interface VadParams

/**
 * 목적: Silero 계열(get_speech_timestamps 후처리) 파라미터
 * 기능: 임계값·최소 발화/무음·패딩·최대 발화 길이를 담는다. TEN_SHERPA 도 같은 후처리를 쓴다.
 */
data class SileroParams(
    val threshold: Float, // 음성 판정 확률 임계값(neg_threshold = threshold - 0.15)
    val minSpeechMs: Int, // 최소 발화 길이(ms)
    val minSilenceMs: Int, // 발화를 끊는 최소 무음 길이(ms)
    val speechPadMs: Int, // 구간 앞뒤 패딩(ms)
    val maxSpeechSec: Double = Double.POSITIVE_INFINITY, // 최대 발화 길이(초), 무제한은 무한대
) : VadParams

/**
 * 목적: pyannote Binarize 후처리 파라미터
 * 기능: onset/offset 히스테리시스와 최소 발화/무음 길이를 담는다.
 */
data class PyannoteParams(
    val onset: Float, // 발화 시작 임계값
    val offset: Float, // 발화 종료 임계값
    val minDurationOnSec: Double, // 이보다 짧은 발화 제거(초)
    val minDurationOffSec: Double, // 이보다 짧은 무음 메움(초)
) : VadParams

/**
 * 목적: FunASR FSMN-VAD(E2EVadModel) 상태기계 파라미터
 * 기능: funasr/fsmn-vad config.yaml 의 값을 그대로 담는다(이름만 camelCase).
 */
data class FsmnParams(
    val maxEndSilenceTime: Int, // max_end_silence_time(ms)
    val maxStartSilenceTime: Int, // max_start_silence_time(ms)
    val windowSizeMs: Int, // window_size_ms
    val silToSpeechTimeThres: Int, // sil_to_speech_time_thres(ms)
    val speechToSilTimeThres: Int, // speech_to_sil_time_thres(ms)
    val speech2NoiseRatio: Float, // speech_2_noise_ratio
    val doExtend: Int, // do_extend
    val lookbackTimeStartPoint: Int, // lookback_time_start_point(ms)
    val lookaheadTimeEndPoint: Int, // lookahead_time_end_point(ms)
    val maxSingleSegmentTime: Int, // max_single_segment_time(ms)
    val snrThres: Float, // snr_thres
    val decibelThres: Float, // decibel_thres
    val speechNoiseThres: Float, // speech_noise_thres
    val fePriorThres: Float, // fe_prior_thres
    val speechNoiseThreshLow: Float, // speech_noise_thresh_low
    val speechNoiseThreshHigh: Float, // speech_noise_thresh_high
    val frameInMs: Int, // frame_in_ms
    val frameLengthMs: Int, // frame_length_ms
    val detectMode: Int, // detect_mode
    val snrMode: Int, // snr_mode
) : VadParams

/** 목적: WebRTC 후처리 방식 구분. 기능: RAW(병합만) / RING_BUFFER(py-webrtcvad 예제) / HANGOVER(android-vad). */
enum class WebRtcStyle { RAW, RING_BUFFER, HANGOVER }

/**
 * 목적: WebRTC(libfvad) VAD 파라미터
 * 기능: aggressiveness 모드, 프레임 길이, 후처리 방식과 방식별 값을 담는다.
 */
data class WebRtcParams(
    val mode: Int, // libfvad aggressiveness 0..3
    val frameMs: Int, // 프레임 길이(10/20/30 ms)
    val style: WebRtcStyle, // 후처리 방식
    val paddingMs: Int = 300, // RING_BUFFER: 패딩 길이(ms)
    val ratio: Float = 0.9f, // RING_BUFFER: voiced/unvoiced 비율
    val speechMs: Int = 0, // HANGOVER: 발화 시작 확정 길이(ms)
    val silenceMs: Int = 0, // HANGOVER: 발화 종료 확정 길이(ms)
) : VadParams

/**
 * 목적: 임계값 + 행오버(SimpleHangover) 파라미터
 * 기능: TEN_DEFAULT(후처리 없음은 0/0)와 YAMNET_* 가 공유한다.
 */
data class HangoverParams(
    val threshold: Float, // 음성 판정 확률 임계값
    val speechMs: Int, // 발화 시작 확정 길이(ms)
    val silenceMs: Int, // 발화 종료 확정 길이(ms)
) : VadParams

/** 목적: FireRedVAD 후처리 모드 구분. 기능: OFFLINE(vad.py) / STREAM(stream_vad.py). */
enum class FireRedMode { OFFLINE, STREAM }

/**
 * 목적: FireRedVAD 후처리 파라미터
 * 기능: 프레임 단위(10 ms) 길이 값들과 평활 창을 담는다.
 */
data class FireRedParams(
    val mode: FireRedMode, // 후처리 모드
    val threshold: Float, // 음성 판정 임계값
    val smoothWindow: Int, // 평활 창(프레임), 0 이면 평활 없음
    val minSpeechFrames: Int, // 최소 발화(프레임)
    val maxSpeechFrames: Int, // 최대 발화(프레임)
    val minSilenceFrames: Int, // 최소 무음(프레임)
    val mergeSilenceFrames: Int, // 이보다 짧은 무음 병합(프레임)
    val extendFrames: Int, // 구간 확장(프레임)
    val padStartFrames: Int, // 시작 패딩(프레임)
) : VadParams

/**
 * 목적: NeMo(MarbleNet) 후처리 파라미터
 * 기능: frame_vad_infer_postprocess.yaml 의 값을 담는다.
 */
data class NemoParams(
    val onset: Float, // 발화 시작 임계값
    val offset: Float, // 발화 종료 임계값
    val padOnsetSec: Double, // 시작 패딩(초)
    val padOffsetSec: Double, // 종료 패딩(초)
    val minDurationOnSec: Double, // 최소 발화(초)
    val minDurationOffSec: Double, // 최소 무음(초)
    val filterSpeechFirst: Boolean, // 발화 필터를 무음 병합보다 먼저 적용할지
) : VadParams

/** 목적: VAD 없음(NONE) 파라미터. 기능: 값이 없는 싱글톤. */
data object NoneParams : VadParams
