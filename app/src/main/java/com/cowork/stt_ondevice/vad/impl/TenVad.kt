// 파일명: TenVad.kt
// 목적: TEN VAD(공식 libten_vad.so, hop 256 확률) + 프리셋별 후처리 VAD 구현 (TEN_DEFAULT / TEN_SHERPA)
// 기능: 16 kHz float 오디오를 PCM16 으로 바꾸고 floor(N/256) 개 완전한 프레임만 TEN 에 넘겨 프레임별 음성 확률을 받는다
//       (잔여 샘플 버림, detect 마다 새 인스턴스로 처음부터 순서대로 처리 — 골든 gen_ten.py 와 같음, README §6.6).
//         - TEN_DEFAULT(HangoverParams): SimpleHangover(확률, hop 256/16000 = 0.016 s, threshold, speechMs, silenceMs) — 프리셋 값 0/0 이면
//           라이브러리 flag(확률 ≥ threshold) 연속 병합과 같다.
//         - TEN_SHERPA(SileroParams): SileroTimestamps.apply(확률, windowSamples = 256, 16000, totalSamples = N, params).
// change(add)-hyungchul-2026-10-10-15:26
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // float → PCM16 변환
import com.cowork.stt_ondevice.vad.HangoverParams // TEN_DEFAULT 파라미터
import com.cowork.stt_ondevice.vad.SileroParams // TEN_SHERPA 파라미터
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.post.SileroTimestamps // TEN_SHERPA 후처리
import com.cowork.stt_ondevice.vad.post.SimpleHangover // TEN_DEFAULT 후처리

// change(add)-hyungchul-2026-10-10-15:26
/**
 * 목적: TEN VAD 기반 VAD
 * 기능: 확률 함수(frameProbs)로 hop 256 확률을 받아 프리셋 params 타입대로 구간을 만든다.
 *       기본 생성자는 TenVadNative(JNI)를 쓰고, 내부 생성자는 JVM 단위 테스트가 확률 함수를 주입할 때 쓴다.
 * 입력: frameProbs((threshold, pcm) → 프레임별 확률; pcm 길이는 항상 프레임 수 × 256)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class TenVad internal constructor(
    private val frameProbs: (threshold: Float, pcm: ShortArray) -> FloatArray, // 프레임 확률 함수
) : VadEngine {

    /**
     * 목적: 실제 앱용 생성자
     * 기능: 확률 계산을 TEN 네이티브(create → processAll → destroy)로 한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 해당 없음
     */
    constructor() : this(::nativeProbs) // 네이티브 확률 연결

    override val family: VadFamily = VadFamily.TEN // TEN 계열

    /**
     * 목적: 음성 구간 검출
     * 기능: 계열·파라미터 확인 → PCM16 변환 → floor(N/256)·256 샘플을 확률 함수에 넘김 → 개수 확인 → params 타입별 후처리.
     * 입력: audio(16 kHz mono float), preset(TEN 계열 프리셋, params = HangoverParams 또는 SileroParams)
     * 출력: 다른 계열·params 타입이면 IllegalArgumentException, 확률 개수가 프레임 수와 다르면 IllegalStateException
     * 리턴: VadOutput(구간, frameHopSec 0.016, frameScores = 프레임별 확률)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "TenVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val params = preset.params // 프리셋 파라미터
        val threshold = when (params) { // 네이티브 flag 임계값(확률에는 영향 없음)
            is HangoverParams -> params.threshold // TEN_DEFAULT
            is SileroParams -> params.threshold // TEN_SHERPA
            else -> throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 TEN 용 타입이 아님: ${params::class.simpleName}") // 타입 오류
        }
        val pcm = PcmConverter.floatToPcm16(audio) // float → PCM16 (int16/32768 원본이면 정확히 복원)
        val numFrames = pcm.size / HOP // floor(N/256) 완전한 프레임
        val probs = frameProbs(threshold, pcm.copyOf(numFrames * HOP)) // 완전한 프레임만 넘김(잔여 샘플 제외)
        check(probs.size == numFrames) { "TEN 확률 개수 ${probs.size} ≠ 프레임 수 $numFrames" } // 개수 이상은 조용히 넘기지 않음
        val segments = when (params) { // 후처리
            is HangoverParams -> SimpleHangover.apply(probs, HOP_SEC, params.threshold, params.speechMs, params.silenceMs) // TEN_DEFAULT
            is SileroParams -> SileroTimestamps.apply(probs, HOP, SAMPLE_RATE, audio.size, params) // TEN_SHERPA(window 256, N = 원본 길이)
            else -> error("도달 불가") // 위에서 이미 걸러짐
        }
        return VadOutput(segments, HOP_SEC, probs) // 결과
    }

    /**
     * 목적: 자원 해제
     * 기능: detect 마다 네이티브 인스턴스를 만들고 해제하므로 할 일이 없다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() = Unit // 보관 자원 없음

    private companion object {
        const val SAMPLE_RATE = 16000 // 입력 샘플레이트(Hz)
        const val HOP = 256 // TEN hop(샘플), 공식 권장값
        const val HOP_SEC = HOP.toDouble() / SAMPLE_RATE // 프레임 간격 0.016 s (골든 hop / SAMPLE_RATE 와 같은 연산)

        /**
         * 목적: 네이티브 TEN 으로 프레임 확률 계산
         * 기능: 새 인스턴스 생성(hop 256, threshold) → 전체 처리 → 반드시 해제(try/finally).
         * 입력: threshold(flag 임계값), pcm(프레임 수 × 256 샘플)
         * 출력: 네이티브 오류는 예외로 전달
         * 리턴: 프레임별 확률
         */
        fun nativeProbs(threshold: Float, pcm: ShortArray): FloatArray {
            val handle = TenVadNative.create(HOP, threshold) // 새 인스턴스(상태 초기)
            try { // 처리 중 예외가 나도 해제
                return TenVadNative.processAll(handle, pcm) // hop 단위 확률
            } finally {
                TenVadNative.destroy(handle) // 인스턴스 해제
            }
        }
    }
}
