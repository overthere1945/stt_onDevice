// 파일명: AudioClip.kt
// 목적: VAD/STT 엔진에 공급하는 16 kHz mono float 오디오와 원본 메타데이터 보관
// 기능: 샘플(-1..1), 원본 포맷 정보, 변환 여부, 길이(초) 제공
package com.cowork.stt_ondevice.core.audio // 오디오 입출력 패키지

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: 변환이 끝난 오디오 한 건을 표현
 * 기능: 16 kHz mono float 샘플과 원본 샘플레이트/채널/비트, 변환 여부를 가진다.
 * 입력: samples(16k mono, -1..1), sourceSampleRate, sourceChannels, sourceBits, converted
 * 출력: 없음
 * 리턴: 해당 없음
 */
data class AudioClip(
    val samples: FloatArray, // 16 kHz mono 샘플(-1..1)
    val sourceSampleRate: Int, // 원본 샘플레이트
    val sourceChannels: Int, // 원본 채널 수
    val sourceBits: Int, // 원본 비트 수
    val converted: Boolean, // 다운믹스/리샘플이 일어났는지
) {
    /** 샘플레이트: 항상 16000 Hz */
    val sampleRate: Int get() = PcmConverter.TARGET_RATE

    /** 길이(초): 샘플 수 / 샘플레이트 */
    val durationSec: Double get() = samples.size.toDouble() / sampleRate
}
