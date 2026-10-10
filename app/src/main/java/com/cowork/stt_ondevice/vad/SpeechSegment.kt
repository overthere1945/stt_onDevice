// 파일명: SpeechSegment.kt
// 목적: VAD 가 내놓는 음성 구간과 VAD 전체 출력 모델 정의
// 기능: SpeechSegment(원본 기준 시작/끝 초), VadOutput(구간 목록 + 선택적 프레임별 비음성 점수)
package com.cowork.stt_ondevice.vad // VAD 패키지

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: 음성으로 판정된 한 구간
 * 기능: 원본 오디오 기준 시작/끝 시각(초)을 보관한다.
 */
data class SpeechSegment(
    val startSec: Double, // 구간 시작(초, 원본 기준)
    val endSec: Double, // 구간 끝(초, 원본 기준)
)

// change(add)-hyungchul-2026-10-10-12:11
/**
 * 목적: VAD 한 번 실행의 결과
 * 기능: 음성 구간 목록과, 가능한 경우 프레임별 점수(낮을수록 분할하기 좋은 지점)를 담는다.
 *       frameScores 가 있으면 frameHopSec 도 있어야 하며 프레임 i 의 시각은 i * frameHopSec(프레임 시작)이다.
 *       점수가 없는 VAD 는 frameHopSec / frameScores 를 null 로 둔다(0 으로 채우지 않는다).
 */
data class VadOutput(
    val segments: List<SpeechSegment>, // 음성 구간 목록
    val frameHopSec: Double?, // 점수 프레임 간격(초), 점수가 없으면 null
    val frameScores: FloatArray?, // 프레임별 점수, 없으면 null
)
