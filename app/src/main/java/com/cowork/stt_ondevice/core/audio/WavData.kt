// 파일명: WavData.kt
// 목적: WAV 파일에서 읽어낸 원본 PCM 과 헤더 정보를 담는 데이터 클래스, 그리고 WAV 형식 오류 예외 정의
// 기능: 샘플레이트/채널/비트 수/인터리브 PCM 보관, 형식 오류를 IOException 으로 구분해 전달
package com.cowork.stt_ondevice.core.audio // 오디오 입출력 패키지

import java.io.IOException // 입출력 예외 기반 클래스

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: WAV 파싱 결과 보관
 * 기능: 헤더 정보와 인터리브된 PCM 샘플을 함께 가진다.
 *       16-bit 는 부호 있는 값 그대로, 8-bit 는 unsigned 값(0..255)을 그대로 담는다.
 * 입력: sampleRate(Hz), channels(채널 수), bitsPerSample(8 또는 16), pcm(인터리브 샘플)
 * 출력: 없음
 * 리턴: 해당 없음
 */
data class WavData(
    val sampleRate: Int, // 샘플레이트(Hz)
    val channels: Int, // 채널 수
    val bitsPerSample: Int, // 샘플당 비트 수
    val pcm: ShortArray, // 채널 인터리브 샘플(8-bit 는 unsigned 값)
)

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: 지원하지 않거나 손상된 WAV 입력을 알린다.
 * 기능: IOException 하위 클래스로 일반 입출력 오류와 구분한다.
 * 입력: message(원인 설명)
 * 출력: 없음
 * 리턴: 해당 없음
 */
class WavFormatException(message: String) : IOException(message)
