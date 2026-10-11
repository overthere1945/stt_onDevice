// 파일명: SileroVad.kt
// 목적: Silero VAD v6(v6.2.3 silero_vad.onnx) ONNX 추론 + get_speech_timestamps 후처리 VAD 구현
// 기능: silero-vad 6.2.3 `OnnxWrapper.__call__`(utils_vad.py L109–144)과 `get_speech_timestamps` 의 창 루프를 재현한다.
//       창 수 = ceil(N/512), 마지막 창은 0 패딩. 창마다 입력 `input`(1, 64+512) = [직전 입력의 마지막 64 샘플(context) | 현재 512 샘플]
//       (첫 창 context 는 0), `state`(2,1,128) float32(처음 0, 매 창 출력 `stateN` 으로 갱신), `sr` int64 스칼라 16000.
//       출력 `output`(1,1) = 음성 확률. 확률 배열을 SileroTimestamps.apply 에 넣어 구간을 만든다(tools/golden/README_한국어.md §6.1).
//       인스턴스는 단일 스레드 전용이다(ORT 세션은 스레드 안전하지만 detect 마다 state 를 새로 만들고, 동시 호출은 고려하지 않음).
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import ai.onnxruntime.OnnxTensor // ORT 텐서
import ai.onnxruntime.OrtSession // ORT 세션
import com.cowork.stt_ondevice.vad.OrtSessions // 세션 생성 도우미
import com.cowork.stt_ondevice.vad.SileroParams // Silero 후처리 파라미터
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.post.SileroTimestamps // get_speech_timestamps 후처리 포팅
import java.io.File // 모델 파일
import java.nio.FloatBuffer // 텐서 입력 버퍼
import java.nio.LongBuffer // sr 스칼라 버퍼

// change(add)-hyungchul-2026-10-10-13:56
/**
 * 목적: Silero v6 ONNX 기반 VAD
 * 기능: probabilities 로 창별 음성 확률을 만들고 detect 에서 SileroTimestamps 로 구간을 만든다.
 * 입력: modelFile(silero_vad.onnx v6), threads(ORT intra-op 스레드 수, 기본 1)
 * 출력: 모델 입력 이름(input/state/sr)이 다르면 IllegalArgumentException
 * 리턴: 해당 없음
 */
class SileroVad(modelFile: File, threads: Int = 1) : VadEngine {

    override val family: VadFamily = VadFamily.SILERO // Silero 계열

    private val session: OrtSession = OrtSessions.open(modelFile, threads) // ORT 세션(close 에서 닫음)

    init {
        val names = session.inputNames // 모델 입력 이름
        if (!names.containsAll(listOf(IN_INPUT, IN_STATE, IN_SR))) { // v6 입력 형식이 아니면
            session.close() // 연 세션 정리
            throw IllegalArgumentException("Silero v6 입력(input, state, sr)이 아님: $names (${modelFile.absolutePath})") // 다른 모델로 대체하지 않음
        }
    }

    /**
     * 목적: 창별 음성 확률 계산(테스트에서 Python 확률과 직접 비교하기 위해 공개)
     * 기능: 호출마다 state·context 를 0 으로 초기화하고(OnnxWrapper.reset_states 와 같음) ceil(N/512) 개 창을 순서대로 추론한다.
     * 입력: audio(16 kHz mono float, −1~1)
     * 출력: 없음
     * 리턴: 창별 확률(길이 ceil(N/512), 빈 입력이면 빈 배열)
     */
    fun probabilities(audio: FloatArray): FloatArray {
        val env = OrtSessions.environment // 공용 ORT 환경
        val numWindows = (audio.size + WINDOW - 1) / WINDOW // 창 수 = ceil(N/512)
        val probs = FloatArray(numWindows) // 결과
        val input = FloatArray(CONTEXT + WINDOW) // [context 64 | 창 512] (처음엔 모두 0 → 첫 창 context 0)
        val state = FloatArray(STATE_SIZE) // RNN state (2,1,128), 처음 0
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), LongArray(0)).use { srTensor -> // sr int64 스칼라(모든 창 공용)
            for (w in 0 until numWindows) { // 창마다
                System.arraycopy(input, WINDOW, input, 0, CONTEXT) // context ← 직전 입력 576 샘플의 마지막 64 샘플
                val start = w * WINDOW // 창 시작 샘플
                val n = minOf(WINDOW, audio.size - start) // 실제 샘플 수(마지막 창은 짧을 수 있음)
                System.arraycopy(audio, start, input, CONTEXT, n) // 현재 창 샘플 복사
                input.fill(0f, CONTEXT + n, CONTEXT + WINDOW) // 마지막 창 0 패딩(get_speech_timestamps 의 pad)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), INPUT_SHAPE).use { inT -> // input (1, 576)
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(state), STATE_SHAPE).use { stT -> // state (2,1,128)
                        session.run(mapOf(IN_INPUT to inT, IN_STATE to stT, IN_SR to srTensor)).use { res -> // 추론
                            val out = res.get(OUT_PROB).orElseThrow { IllegalStateException("Silero 출력 '$OUT_PROB' 없음") } as OnnxTensor // 확률 텐서
                            probs[w] = out.floatBuffer.get(0) // output (1,1) → 확률
                            val newState = res.get(OUT_STATE).orElseThrow { IllegalStateException("Silero 출력 '$OUT_STATE' 없음") } as OnnxTensor // 새 state
                            val buf = newState.floatBuffer // state 값 버퍼
                            check(buf.remaining() == STATE_SIZE) { "stateN 크기 ${buf.remaining()} ≠ $STATE_SIZE" } // 형상 확인
                            buf.get(state) // 다음 창 state 로 복사
                        }
                    }
                }
            }
        }
        return probs // 창별 확률
    }

    /**
     * 목적: 음성 구간 검출
     * 기능: 프리셋 계열·파라미터 타입을 확인하고 확률 → SileroTimestamps.apply(window 512, 16 kHz, totalSamples = audio.size) 로 구간을 만든다.
     * 입력: audio(16 kHz mono float), preset(SILERO 계열 프리셋, params = SileroParams)
     * 출력: 다른 계열이거나 params 타입이 다르면 IllegalArgumentException
     * 리턴: VadOutput(구간, frameHopSec 0.032, frameScores = 창별 확률)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "SileroVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val p = preset.params as? SileroParams // 파라미터 캐스팅
            ?: throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 SileroParams 가 아님: ${preset.params::class.simpleName}") // 타입 오류
        val probs = probabilities(audio) // 창별 확률
        val segments = SileroTimestamps.apply(probs, WINDOW, SAMPLE_RATE, audio.size, p) // 후처리(원본 audio_length_samples = N)
        return VadOutput(segments, WINDOW.toDouble() / SAMPLE_RATE, probs) // hop 512/16000 = 0.032 s
    }

    /**
     * 목적: 자원 해제
     * 기능: ORT 세션을 닫는다(환경은 프로세스 공용이라 닫지 않음).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() = session.close() // 세션 해제

    private companion object {
        const val SAMPLE_RATE = 16000 // 입력 샘플레이트(Hz)
        const val WINDOW = 512 // 창(hop) 샘플 수(16 kHz)
        const val CONTEXT = 64 // context 샘플 수(16 kHz)
        const val STATE_SIZE = 2 * 1 * 128 // state 원소 수
        const val IN_INPUT = "input" // 입력 이름: 파형
        const val IN_STATE = "state" // 입력 이름: RNN state
        const val IN_SR = "sr" // 입력 이름: 샘플레이트
        const val OUT_PROB = "output" // 출력 이름: 확률
        const val OUT_STATE = "stateN" // 출력 이름: 새 state
        val INPUT_SHAPE = longArrayOf(1, (CONTEXT + WINDOW).toLong()) // input 형상 (1, 576)
        val STATE_SHAPE = longArrayOf(2, 1, 128) // state 형상
    }
}
