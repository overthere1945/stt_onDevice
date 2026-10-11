// 파일명: NoneVad.kt
// 목적: VAD 없음(기준선) 구현
// 기능: 전체 길이를 하나의 발화로 반환한다. 이후 ChunkPlanner 가 고정 길이로 자른다.
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: VAD 효과 비교의 기준선
 * 기능: 입력 전체를 1개 구간으로 돌려주고 프레임 점수는 만들지 않는다(null).
 */
class NoneVad : VadEngine {
    override val family: VadFamily = VadFamily.NONE // NONE 계열

    /**
     * 목적: 전체 파일을 1개 발화로 반환
     * 기능: 길이(샘플 수/16000)를 초로 환산해 [0, 길이] 구간 하나를 만든다. 빈 입력은 구간 없음.
     * 입력: audio(16 kHz mono), preset(사용 안 함)
     * 출력: 없음
     * 리턴: VadOutput(구간 0~1개, 점수 null)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        val durationSec = audio.size / SAMPLE_RATE.toDouble() // 전체 길이(초)
        val segments = if (audio.isEmpty()) emptyList() else listOf(SpeechSegment(0.0, durationSec)) // 빈 입력이면 구간 없음
        return VadOutput(segments, null, null) // 점수 없음
    }

    /**
     * 목적: 자원 해제
     * 기능: 잡을 자원이 없어 아무 것도 하지 않는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() = Unit // 해제할 자원 없음

    private companion object {
        const val SAMPLE_RATE = 16000 // 입력 샘플레이트(Hz)
    }
}
