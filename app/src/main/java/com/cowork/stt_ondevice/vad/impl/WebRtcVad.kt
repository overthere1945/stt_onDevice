// 파일명: WebRtcVad.kt
// 목적: WebRTC VAD(libfvad 네이티브 판정) + 프리셋 방식별 후처리 VAD 구현 (WEBRTC_LIB_DEFAULT / WEBRTC_PYEXAMPLE / WEBRTC_ANDROIDVAD)
// 기능: 16 kHz float 오디오를 PCM16 으로 바꾸고(int16/32768 로 만든 float 는 원래 int16 으로 정확히 복원됨),
//       방식별 프레임 수 규칙(tools/golden/README_한국어.md §6.4)대로 앞부분만 잘라 libfvad 에 넘긴다.
//         - RAW, HANGOVER: floor(N / L) 프레임(L = 16·frameMs), 잔여 샘플 버림
//         - RING_BUFFER: floor((N − 1) / L) 프레임(py-webrtcvad example.py frame_generator 의 엄격한 < 조건)
//       판정 결과를 RAW → WebRtcPost.raw, RING_BUFFER → WebRtcPost.ringBuffer, HANGOVER → SimpleHangover(threshold 0.5) 로 구간화한다.
//       detect 마다 새 libfvad 인스턴스를 쓰므로(호출 간 상태 없음) close 할 자원이 없다.
// change(add)-hyungchul-2026-10-10-15:26
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // float → PCM16 변환
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.WebRtcParams // WebRTC 파라미터
import com.cowork.stt_ondevice.vad.WebRtcStyle // WebRTC 후처리 방식
import com.cowork.stt_ondevice.vad.post.SimpleHangover // HANGOVER 후처리
import com.cowork.stt_ondevice.vad.post.WebRtcPost // RAW / RING_BUFFER 후처리

// change(add)-hyungchul-2026-10-10-15:26
/**
 * 목적: WebRTC(libfvad) 기반 VAD
 * 기능: 프레임 판정 함수(frameFlags)로 voiced 여부를 받고 프리셋 방식대로 구간을 만든다.
 *       기본 생성자는 FvadNative(JNI)를 쓰고, 내부 생성자는 JVM 단위 테스트가 판정 함수를 주입할 때 쓴다.
 * 입력: frameFlags((mode, frameMs, pcm) → 프레임별 voiced; pcm 길이는 항상 프레임 수 × L)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class WebRtcVad internal constructor(
    private val frameFlags: (mode: Int, frameMs: Int, pcm: ShortArray) -> BooleanArray, // 프레임 판정 함수
) : VadEngine {

    /**
     * 목적: 실제 앱용 생성자
     * 기능: 프레임 판정을 libfvad 네이티브(FvadNative.processAll)로 한다. FvadNative 는 detect 에서 처음 쓸 때 로드된다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 해당 없음
     */
    constructor() : this({ mode, frameMs, pcm -> FvadNative.processAll(mode, frameMs, pcm) }) // 네이티브 판정 연결

    override val family: VadFamily = VadFamily.WEBRTC // WebRTC 계열

    /**
     * 목적: 음성 구간 검출
     * 기능: 계열·파라미터 확인 → PCM16 변환 → 방식별 프레임 수만큼 앞부분을 판정 함수에 넘김 → 결과 개수 확인 → 방식별 후처리.
     * 입력: audio(16 kHz mono float), preset(WEBRTC 계열 프리셋, params = WebRtcParams)
     * 출력: 다른 계열·params 타입·frameMs 이면 IllegalArgumentException, 판정 개수가 프레임 수와 다르면 IllegalStateException
     * 리턴: VadOutput(구간, frameHopSec = frameMs/1000, frameScores = 프레임별 1.0(voiced)/0.0)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "WebRtcVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val p = preset.params as? WebRtcParams // 파라미터 캐스팅
            ?: throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 WebRtcParams 가 아님: ${preset.params::class.simpleName}") // 타입 오류
        require(p.frameMs == 10 || p.frameMs == 20 || p.frameMs == 30) { "libfvad 프레임 길이는 10/20/30 ms 만 가능: ${p.frameMs}" } // libfvad 제약
        val frameLen = SAMPLES_PER_MS * p.frameMs // 프레임당 샘플 수 L
        val pcm = PcmConverter.floatToPcm16(audio) // float → PCM16 (int16/32768 원본이면 정확히 복원)
        val numFrames = if (p.style == WebRtcStyle.RING_BUFFER) { // py-webrtcvad 예제 방식
            if (pcm.isEmpty()) 0 else (pcm.size - 1) / frameLen // floor((N−1)/L): offset + n < len(audio) 조건
        } else { // RAW, HANGOVER
            pcm.size / frameLen // floor(N/L): 완전한 프레임만
        }
        val flags = frameFlags(p.mode, p.frameMs, pcm.copyOf(numFrames * frameLen)) // 판정할 프레임만 넘김(잔여 샘플 제외)
        check(flags.size == numFrames) { "WebRTC 판정 개수 ${flags.size} ≠ 프레임 수 $numFrames" } // 개수 이상은 조용히 넘기지 않음
        val hopSec = p.frameMs / 1000.0 // 프레임 간격(초): 골든 hopSec 와 같은 연산
        val scores = FloatArray(numFrames) { if (flags[it]) 1.0f else 0.0f } // 프레임 점수 1/0 (골든 frameScores 와 같은 표현)
        val segments = when (p.style) { // 방식별 후처리
            WebRtcStyle.RAW -> WebRtcPost.raw(flags, p.frameMs) // 연속 voiced 병합
            WebRtcStyle.RING_BUFFER -> WebRtcPost.ringBuffer(flags, p.frameMs, p.paddingMs, p.ratio) // example.py vad_collector
            WebRtcStyle.HANGOVER -> SimpleHangover.apply(scores, hopSec, HANGOVER_THRESHOLD, p.speechMs, p.silenceMs) // android-vad hangover
        }
        return VadOutput(segments, hopSec, scores) // 결과
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
        const val SAMPLES_PER_MS = 16 // 16 kHz 에서 1 ms 당 샘플 수
        const val HANGOVER_THRESHOLD = 0.5f // HANGOVER 의 voiced 판정 임계값(1/0 점수, 골든 hangover_ref 와 같은 0.5)
    }
}
