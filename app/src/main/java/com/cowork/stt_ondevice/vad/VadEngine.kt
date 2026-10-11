// 파일명: VadEngine.kt
// 목적: VAD 구현체 공통 인터페이스
// 기능: 16 kHz mono 오디오와 프리셋을 받아 음성 구간(+선택적 프레임 점수)을 돌려준다.
package com.cowork.stt_ondevice.vad // VAD 패키지

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: VAD 구현 추상화
 * 기능: 계열마다 구현 1개. 네이티브/세션 자원을 쓰므로 AutoCloseable 이다.
 */
interface VadEngine : AutoCloseable {
    val family: VadFamily // 이 구현이 담당하는 계열

    /**
     * 목적: 음성 구간 검출
     * 기능: preset.params 로 후처리까지 적용해 구간을 만든다.
     * 입력: audio(항상 16 kHz mono float), preset(이 엔진 계열의 프리셋)
     * 출력: 없음
     * 리턴: VadOutput(구간, 가능하면 프레임 점수)
     */
    fun detect(audio: FloatArray, preset: VadPreset): VadOutput
}
