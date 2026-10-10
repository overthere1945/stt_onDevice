// 파일명: MarbleNetVad.kt
// 목적: NVIDIA Frame_VAD_Multilingual_MarbleNet_v2.0 ONNX 추론 + NeMo 후처리 VAD 구현
// 기능: 모델 카드 "Inference with PyTorch" 경로(fp32)를 ONNX 로 재현한다(tools/export/README_한국어.md §7):
//       NemoMelSpectrogram(marblenet_preprocessor.json) log-mel [1, 80, F] + processed_signal_length = seqLen(floor(N/160), F 아님)
//       → frame_vad_multilingual_marblenet_v2.0.onnx 출력 `output` logits [1, F/2, 2] → 음성 확률 = softmax(logits)[..., 1]
//       (NeMo vad_utils.generate_vad_frame_pred 와 같음) → NemoPostprocess(hop 0.02 s, MARBLENET_NEMO 프리셋).
//       R18: 마지막 출력 프레임은 패딩 영역(입력 0)만 보지만 NeMo 처럼 그대로 후처리한다(파일 끝 짧은 구간·끝이 오디오 길이를 넘을 수 있음,
//       오디오 범위 자르기는 앱 ChunkPlanner 가 한다). frameScores = 음성 확률(높을수록 음성).
//       인스턴스는 단일 스레드 전용이다(NemoMelSpectrogram 작업 버퍼 재사용).
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import ai.onnxruntime.OnnxTensor // ORT 텐서
import ai.onnxruntime.OrtSession // ORT 세션
import com.cowork.stt_ondevice.vad.NemoParams // NeMo 파라미터
import com.cowork.stt_ondevice.vad.OrtSessions // 세션 생성 도우미
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.feature.NemoMelSpectrogram // NeMo log-mel
import com.cowork.stt_ondevice.vad.post.NemoPostprocess // NeMo 후처리 포팅
import java.io.File // 모델 폴더
import java.nio.FloatBuffer // 특징 버퍼
import java.nio.LongBuffer // 길이 버퍼
import kotlin.math.exp // 지수

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: MarbleNet ONNX 기반 VAD
 * 기능: probabilities 로 20 ms 프레임 음성 확률을, detect 로 구간을 만든다.
 * 입력: modelDir(frame_vad_multilingual_marblenet_v2.0.onnx, marblenet_preprocessor.json 이 있는 폴더), threads(ORT intra-op 스레드 수, 기본 1)
 * 출력: 파일이 없거나 입출력 이름이 다르면 IllegalArgumentException
 * 리턴: 해당 없음
 */
class MarbleNetVad(modelDir: File, threads: Int = 1) : VadEngine {

    override val family: VadFamily = VadFamily.MARBLENET // MarbleNet 계열

    private val mel = NemoMelSpectrogram(File(modelDir, PREPROCESSOR_JSON)) // 전처리(설정 확인 포함)
    private val session: OrtSession = OrtSessions.open(File(modelDir, MODEL_FILE), threads) // ORT 세션(close 에서 닫음)

    init {
        if (!session.inputNames.containsAll(listOf(IN_SIGNAL, IN_LENGTH)) || OUT_LOGITS !in session.outputNames) { // 모델 카드 export 형식 확인
            session.close() // 정리
            throw IllegalArgumentException("MarbleNet ONNX 입출력(processed_signal, processed_signal_length → output)이 아님: ${session.inputNames} → ${session.outputNames}") // 대체 금지
        }
    }

    /**
     * 목적: 20 ms 프레임 음성 확률 계산(테스트에서 Python 확률과 직접 비교하기 위해 공개)
     * 기능: log-mel(F × 80)을 [1, 80, F] 로 바꿔 seqLen 과 함께 추론하고, logits 마다 softmax 의 1 번(음성) 확률을 double 로 계산해 Float 로 낸다.
     * 입력: audio(16 kHz mono float, −1~1)
     * 출력: 출력 형상이 [1, F/2, 2] 가 아니면 IllegalStateException
     * 리턴: 길이 F/2 의 확률
     */
    fun probabilities(audio: FloatArray): FloatArray {
        val feats = mel.compute(audio) // F × 80
        val frames = feats.size // F(pad_to 2 → 짝수)
        val numMel = mel.numMel // 80
        val buf = FloatArray(numMel * frames) // [1, 80, F] 평탄화(mel 우선)
        for (t in 0 until frames) for (m in 0 until numMel) buf[m * frames + t] = feats[t][m] // 전치
        val env = OrtSessions.environment // 공용 ORT 환경
        OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, numMel.toLong(), frames.toLong())).use { sig -> // processed_signal
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(mel.seqLen(audio.size).toLong())), longArrayOf(1)).use { len -> // processed_signal_length = seqLen
                session.run(mapOf(IN_SIGNAL to sig, IN_LENGTH to len)).use { res -> // 추론
                    val out = res.get(OUT_LOGITS).orElseThrow { IllegalStateException("MarbleNet 출력 '$OUT_LOGITS' 없음") } as OnnxTensor // logits
                    val shape = out.info.shape // 형상
                    check(shape.size == 3 && shape[0] == 1L && shape[1] == (frames / 2).toLong() && shape[2] == 2L) { "MarbleNet output 형상 ${shape.contentToString()} ≠ (1, ${frames / 2}, 2)" } // 형상 확인
                    val lb = out.floatBuffer // logits 값
                    return FloatArray(frames / 2) { i -> // 출력 프레임마다
                        val l0 = lb.get(2 * i).toDouble() // 비음성 logit
                        val l1 = lb.get(2 * i + 1).toDouble() // 음성 logit
                        val mx = maxOf(l0, l1) // 수치 안정화
                        val e0 = exp(l0 - mx) // exp
                        val e1 = exp(l1 - mx) // exp
                        (e1 / (e0 + e1)).toFloat() // softmax[..., 1]
                    }
                }
            }
        }
    }

    /**
     * 목적: 음성 구간 검출
     * 기능: 프리셋 계열·파라미터 타입을 확인하고 확률 → NemoPostprocess.apply(hop 0.02) 로 구간을 만든다.
     * 입력: audio(16 kHz mono float), preset(MARBLENET 계열 프리셋, params = NemoParams)
     * 출력: 다른 계열이거나 params 타입이 다르면 IllegalArgumentException
     * 리턴: VadOutput(구간, frameHopSec 0.02, frameScores = 음성 확률)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "MarbleNetVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val p = preset.params as? NemoParams // 파라미터 캐스팅
            ?: throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 NemoParams 가 아님: ${preset.params::class.simpleName}") // 타입 오류
        val probs = probabilities(audio) // 프레임 확률
        val segments = NemoPostprocess.apply(probs, HOP_SEC, p) // NeMo 후처리
        return VadOutput(segments, HOP_SEC, probs) // hop 20 ms
    }

    /**
     * 목적: 자원 해제
     * 기능: ORT 세션을 닫는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() = session.close() // 세션 해제

    private companion object {
        const val HOP_SEC = 0.02 // 출력 프레임 간격(shift_length_in_sec)
        const val MODEL_FILE = "frame_vad_multilingual_marblenet_v2.0.onnx" // 모델 파일
        const val PREPROCESSOR_JSON = "marblenet_preprocessor.json" // 전처리 설정
        const val IN_SIGNAL = "processed_signal" // 입력: log-mel
        const val IN_LENGTH = "processed_signal_length" // 입력: seqLen
        const val OUT_LOGITS = "output" // 출력: logits
    }
}
