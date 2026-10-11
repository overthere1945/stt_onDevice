// 파일명: VadFamily.kt
// 목적: VAD 계열(알고리즘 종류) 정의
// 기능: 프리셋이 속한 VAD 계열을 열거한다. 계열마다 VadEngine 구현 1개가 대응한다.
package com.cowork.stt_ondevice.vad // VAD 패키지

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: VAD 계열 구분
 * 기능: NONE(기준선) 포함 9 계열을 나열한다.
 */
enum class VadFamily {
    NONE, // VAD 없음(고정 길이 분할 기준선)
    SILERO, // Silero VAD
    PYANNOTE, // pyannote segmentation-3.0
    FSMN, // FunASR FSMN-VAD
    WEBRTC, // libfvad(WebRTC VAD)
    TEN, // TEN VAD
    FIRERED, // FireRedVAD
    MARBLENET, // NVIDIA MarbleNet
    YAMNET, // Yamnet(speech 클래스)
}
