// 파일명: YamnetVad.kt
// 목적: Yamnet(AudioSet 521 클래스 분류기)의 "Speech" 클래스 점수를 VAD 점수로 쓰는 VAD 구현 (YAMNET_NORMAL / AGGRESSIVE / VERY_AGGRESSIVE)
// 기능: gkonovalov/android-vad v2.0.10 yamnet 모듈(MIT)의 FRAME_SIZE_243 동작을 재현해 프레임 점수를 만들고, 구간은 프로젝트 공통 SimpleHangover 로 만든다.
//
//   [android-vad 원본 읽기 — 태그 2.0.10 = 커밋 1b006d0aaf8fa269986a8c95a196055550c9b877]
//   - https://github.com/gkonovalov/android-vad/blob/1b006d0aaf8fa269986a8c95a196055550c9b877/yamnet/src/main/java/com/konovalov/vad/yamnet/VadYamnet.kt
//     · TensorAudio.create(16 kHz, 15600) 링버퍼 1개 + AudioClassifier(yamnet.tflite, maxResults 1).
//     · classifyAudio(audioData) 마다 tensor.load(audioData) 로 프레임(243 샘플) 을 링버퍼에 넣고 classify(tensor) — 즉 매 프레임
//       "가장 최근 15600 샘플"(처음에는 앞이 0) 창 하나를 분류한다. → hop = 프레임 크기 243 샘플(15.1875 ms), 창 = 0.975 s.
//     · getResult: 최고점 1개(maxResults 1) 클래스 중 score > threshold 인 것의 label. threshold: NORMAL 0.3, AGGRESSIVE 0.5, VERY_AGGRESSIVE 0.85.
//     · isContinuousVoice(label="Speech"): speech/silence 프레임 카운터(getFramesCount = durationMs / (frameSize / (sampleRate/1000)), 정수 나눗셈:
//       30 / (243/16 = 15) = 2) 를 써서 카운트가 2 를 "넘을 때" 상태를 바꾼다.
//   - https://github.com/gkonovalov/android-vad/blob/1b006d0aaf8fa269986a8c95a196055550c9b877/yamnet/src/main/java/com/konovalov/vad/yamnet/config/FrameSize.kt
//     · FRAME_SIZE_243(243) / 487 / 731 / 975 — 한 번에 load 하는 샘플 수(= hop). 243 은 README 권장값.
//   - https://github.com/gkonovalov/android-vad/blob/1b006d0aaf8fa269986a8c95a196055550c9b877/README.md (Yamnet 권장: 16 kHz, 243, NORMAL, silence 30 ms, speech 30 ms)
//   - TensorAudio(tflite-support v0.4.4 = 커밋 133228616ca14e1b64c03f6a0ea8610f41a60783, android-vad 의존성 tensorflow-lite-task-audio:0.4.4):
//     https://github.com/tensorflow/tflite-support/blob/v0.4.4/tensorflow_lite_support/java/src/java/org/tensorflow/lite/support/audio/TensorAudio.java
//     · load(short[]) 는 short * 1.f / Short.MAX_VALUE(32767) 로 float 변환, FloatRingBuffer 는 0 으로 시작하고 오래된 것→최근 순으로 꺼낸다.
//   - Speech index: 모델 내장 라벨(yamnet_label_list.txt) 과 tensorflow/models yamnet_class_map.csv 모두 index 0 = "Speech" (JVM 테스트 speechClassIndexIsZero).
//
//   [본 앱의 재현 방식]
//   - 프레임 수 = floor(N/243)(완전한 프레임만, 잔여 샘플 버림 — TEN 과 같은 규칙). 프레임 i 의 창 = 원본 샘플 [(i+1)·243 − 15600, (i+1)·243),
//     음수 위치는 0. 값 = int16 / 32767 (앱 float 는 int16/32768 이므로 PcmConverter.floatToPcm16 으로 int16 을 정확히 복원한 뒤 나눔).
//   - 프레임 점수 = 출력 521 개 중 index 0(Speech) 점수. 프레임 시각 = i × 243/16000 s(그 프레임 243 샘플의 시작, 판정은 그 프레임까지의 0.975 s 를 봄).
//   - 구간 = SimpleHangover(점수, hop 243/16000, threshold, speechMs, silenceMs) — 프로젝트 공통 정의(tools/golden/README_한국어.md §6.5).
//   - android-vad 와의 차이(프리셋 note 에도 적음): (1) android-vad 는 Speech 가 521 클래스 중 최고점이고 점수 > threshold 여야 음성,
//     본 앱은 Speech 점수 ≥ threshold 만 본다. (2) 시작/종료 확정 카운트 규칙이 다르다(android-vad: 정수 나눗셈 2 프레임을 "초과", 음성 카운트는
//     무음이 확정될 때까지 유지 / 본 앱: ceil(30/15.1875) = 2 연속 프레임). (3) android-vad 의 float 입력 경로(×32767 절삭)가 아니라 16-bit PCM 경로를 재현.
// change(add)-hyungchul-2026-10-10-15:43
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // float → PCM16 변환
import com.cowork.stt_ondevice.vad.HangoverParams // YAMNET_* 파라미터
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.post.SimpleHangover // 공통 hangover 후처리
import java.io.File // 모델 파일

// change(add)-hyungchul-2026-10-10-15:43
/**
 * 목적: Yamnet Speech 점수 기반 VAD
 * 기능: 추론 함수(classify: 15600 샘플 창 → 521 점수)로 243 hop 마다 Speech 점수를 얻고 SimpleHangover 로 구간을 만든다.
 *       기본 생성자는 LiteRT(YamnetLiteRt)를 쓰고, 내부 생성자는 JVM 단위 테스트가 추론 함수를 주입할 때 쓴다.
 * 입력: classify(창 → 521 점수; 넘겨받은 창 배열은 다음 호출 때 재사용되므로 보관하지 말 것), release(close 때 부를 해제 함수)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class YamnetVad internal constructor(
    private val classify: (FloatArray) -> FloatArray, // 창 → 521 클래스 점수
    private val release: () -> Unit, // 자원 해제 함수
) : VadEngine {

    /**
     * 목적: 실제 앱용 생성자
     * 기능: yamnet.tflite 를 LiteRT 로 올린다(파일이 없거나 텐서 모양이 다르면 예외 — 다른 모델로 대체하지 않음).
     * 입력: modelFile(yamnet.tflite 경로)
     * 출력: 모델 로드 실패 시 예외
     * 리턴: 해당 없음
     */
    constructor(modelFile: File) : this(YamnetLiteRt(modelFile)) // LiteRT 세션 연결

    /**
     * 목적: LiteRT 세션을 추론·해제 함수로 연결(공개 생성자 전용 보조 생성자)
     * 기능: 세션의 classify / close 를 주입 형태로 넘긴다.
     * 입력: model(열린 YamnetLiteRt)
     * 출력: 없음
     * 리턴: 해당 없음
     */
    private constructor(model: YamnetLiteRt) : this(model::classify, model::close) // 세션 함수 연결

    override val family: VadFamily = VadFamily.YAMNET // Yamnet 계열

    /**
     * 목적: 음성 구간 검출
     * 기능: 계열·params 타입 확인 → 프레임별 Speech 점수(speechScores) → SimpleHangover.
     * 입력: audio(16 kHz mono float), preset(YAMNET 계열 프리셋, params = HangoverParams)
     * 출력: 다른 계열·params 타입이면 IllegalArgumentException, 추론 결과 크기가 521 이 아니면 IllegalStateException
     * 리턴: VadOutput(구간, frameHopSec 243/16000, frameScores = 프레임별 Speech 점수)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "YamnetVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val params = preset.params as? HangoverParams // YAMNET 파라미터
            ?: throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 HangoverParams 가 아님: ${preset.params::class.simpleName}") // 타입 오류
        val scores = speechScores(audio) // 프레임별 Speech 점수
        val segments = SimpleHangover.apply(scores, HOP_SEC, params.threshold, params.speechMs, params.silenceMs) // 공통 hangover
        return VadOutput(segments, HOP_SEC, scores) // 결과
    }

    /**
     * 목적: android-vad FRAME_SIZE_243 방식의 프레임별 Speech 점수 계산
     * 기능: PCM16 복원 → int16/32767 float → 앞에 0 을 (15600 − 243) 개 붙인 배열에서 프레임 i 마다 [i·243, i·243 + 15600) 를 창으로 잘라
     *       classify 에 넣고 index 0 점수를 모은다(= 원본 [(i+1)·243 − 15600, (i+1)·243) 창, 링버퍼 초기 0 재현).
     * 입력: audio(16 kHz mono float)
     * 출력: 추론 결과 크기 ≠ 521 이면 IllegalStateException
     * 리턴: floor(N/243) 개 Speech 점수
     */
    private fun speechScores(audio: FloatArray): FloatArray {
        val pcm = PcmConverter.floatToPcm16(audio) // float → int16 (int16/32768 원본이면 정확히 복원)
        val numFrames = pcm.size / HOP_SAMPLES // 완전한 프레임 수 floor(N/243)
        val padded = FloatArray(PAD_SAMPLES + numFrames * HOP_SAMPLES) // 앞 0 채움 + 사용할 샘플(나머지 버림)
        for (k in 0 until numFrames * HOP_SAMPLES) padded[PAD_SAMPLES + k] = pcm[k].toFloat() / PCM_SCALE // TensorAudio: short / Short.MAX_VALUE
        val window = FloatArray(WINDOW_SAMPLES) // 창 버퍼(재사용)
        return FloatArray(numFrames) { i -> // 프레임마다
            padded.copyInto(window, 0, i * HOP_SAMPLES, i * HOP_SAMPLES + WINDOW_SAMPLES) // 프레임 i 창
            val out = classify(window) // 521 클래스 점수
            check(out.size == NUM_CLASSES) { "Yamnet 출력 크기 ${out.size} ≠ $NUM_CLASSES" } // 크기 이상은 조용히 넘기지 않음
            out[SPEECH_CLASS_INDEX] // Speech 점수
        }
    }

    /**
     * 목적: 자원 해제
     * 기능: 주입된 해제 함수(LiteRT Interpreter close)를 부른다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        release() // 세션 해제
    }

    companion object {
        internal const val SPEECH_CLASS_INDEX = 0 // "Speech" 클래스 index (yamnet_class_map.csv / 모델 내장 라벨 확인)
        internal const val NUM_CLASSES = 521 // AudioSet 클래스 수(모델 출력 [1, 521])
        internal const val WINDOW_SAMPLES = 15600 // 모델 입력 창(0.975 s @ 16 kHz, android-vad TensorAudio 크기)
        internal const val HOP_SAMPLES = 243 // 프레임 hop(샘플) = android-vad FRAME_SIZE_243
        private const val PAD_SAMPLES = WINDOW_SAMPLES - HOP_SAMPLES // 첫 창 앞 0 채움 길이(15357)
        private const val SAMPLE_RATE = 16000 // 입력 샘플레이트(Hz)
        private const val PCM_SCALE = 32767f // TensorAudio 의 int16 → float 나눗수(Short.MAX_VALUE)
        internal const val HOP_SEC = HOP_SAMPLES.toDouble() / SAMPLE_RATE // 프레임 간격 0.0151875 s
    }
}
