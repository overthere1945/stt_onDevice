// 파일명: FireRedVad.kt
// 목적: FireRedVAD(FireRedTeam/FireRedVAD c30ec49, HF 7990aac) ONNX 추론 + 후처리 VAD 구현
// 기능: 공식 fireredvad/vad.py `FireRedVad.detect`(L70–102, OFFLINE)·fireredvad/stream_vad.py `FireRedStreamVad.detect_full`(L107–140, STREAM)
//       경로를 재현한다(tools/export/README_한국어.md §6). 전처리 fireredvad/core/audio_feat.py `AudioFeat.extract`(L22–37):
//       KaldiFbank(povey 창, 입력 = int16 값 = float × 32768, 80 mel, 25/10 ms, snip_edges) → FireRedCmvn(cmvn.ark) → float32.
//       모델: OFFLINE = fireredvad_vad.onnx, STREAM = fireredvad_stream_vad.onnx(캐시 입력 없음 = 0 캐시, 전체 특징 1 회 추론 —
//       detect_full 과 같은 경로이며 골든 scores_FIRERED_STREAM 도 이 방식으로 만들었다). 입력 `feat` [1, T, 80], 출력 `probs` [1, T, 1].
//       특징이 chunk_max_frame(30000 프레임 = 300 s)을 넘으면 원본처럼 30000 프레임씩 잘라 캐시 없이 따로 추론해 이어 붙인다.
//       후처리는 FireRedPostprocess(OFFLINE 은 wav_dur = N/16000 전달). frameScores = 음성 확률(높을수록 음성, hop 10 ms).
//       세션은 모드별로 처음 쓸 때 연다(없는 모델 파일이면 그때 예외, 다른 모델로 대체하지 않음).
//       인스턴스는 단일 스레드 전용이다(KaldiFbank 작업 버퍼를 재사용, 스레드 안전하지 않음).
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import ai.onnxruntime.OnnxTensor // ORT 텐서
import ai.onnxruntime.OrtSession // ORT 세션
import com.cowork.stt_ondevice.vad.FireRedMode // 후처리 모드
import com.cowork.stt_ondevice.vad.FireRedParams // FireRed 파라미터
import com.cowork.stt_ondevice.vad.OrtSessions // 세션 생성 도우미
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.feature.FireRedCmvn // FireRed CMVN
import com.cowork.stt_ondevice.vad.feature.KaldiFbank // Kaldi fbank
import com.cowork.stt_ondevice.vad.feature.KaldiWindowType // 창 함수 종류
import com.cowork.stt_ondevice.vad.post.FireRedPostprocess // 후처리 포팅
import java.io.File // 모델 폴더
import java.nio.FloatBuffer // 텐서 입력 버퍼
import java.util.EnumMap // 모드별 세션

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: FireRedVAD ONNX 기반 VAD
 * 기능: features 로 모델 입력 특징을, probabilities 로 프레임 확률을, detect 로 구간을 만든다.
 * 입력: modelDir(fireredvad_vad.onnx, fireredvad_stream_vad.onnx, cmvn.ark 가 있는 폴더), threads(ORT intra-op 스레드 수, 기본 1)
 * 출력: cmvn.ark 가 없거나 형식이 다르면 IllegalArgumentException
 * 리턴: 해당 없음
 */
class FireRedVad(private val modelDir: File, private val threads: Int = 1) : VadEngine {

    override val family: VadFamily = VadFamily.FIRERED // FireRed 계열

    private val fbank = KaldiFbank(NUM_MEL, 25, 10, SAMPLE_RATE, KaldiWindowType.POVEY) // KaldifeatFbank(80, 25, 10, dither 0) — knf 기본 povey 창
    private val cmvn = FireRedCmvn.load(File(modelDir, "cmvn.ark")) // CMVN(audio_feat.py L15)
    private val sessions = EnumMap<FireRedMode, OrtSession>(FireRedMode::class.java) // 모드별 세션(처음 쓸 때 연다)

    init {
        require(cmvn.means.size == NUM_MEL) { "FireRed cmvn.ark 차원 ${cmvn.means.size} ≠ $NUM_MEL: $modelDir" } // 특징 차원 일치
    }

    /**
     * 목적: 모델 입력 특징 계산(AudioFeat.extract L22–37)
     * 기능: fbank(povey) → CMVN → float32. 프레임 수 T = 1 + (N − 400)/160(N < 400 이면 0).
     * 입력: audio(16 kHz mono float, −1~1)
     * 출력: 없음
     * 리턴: T × 80 특징
     */
    fun features(audio: FloatArray): Array<FloatArray> = cmvn.apply(fbank.compute(audio)) // fbank → CMVN

    /**
     * 목적: 프레임 음성 확률 계산(테스트에서 Python 확률과 직접 비교하기 위해 공개)
     * 기능: 특징을 모드의 ONNX 에 넣는다. T ≤ 30000 이면 1 회, 넘으면 30000 프레임씩 캐시 없이 따로 추론해 이어 붙인다(vad.py L77–88,
     *       stream_vad.py L113–124). T = 0 이면 추론 없이 빈 배열.
     * 입력: audio(16 kHz mono float), mode(OFFLINE → fireredvad_vad.onnx, STREAM → fireredvad_stream_vad.onnx)
     * 출력: 모델 파일이 없으면 IllegalArgumentException
     * 리턴: 길이 T 의 확률
     */
    fun probabilities(audio: FloatArray, mode: FireRedMode): FloatArray {
        val feats = features(audio) // 모델 입력
        val probs = FloatArray(feats.size) // 결과
        var offset = 0 // 청크 시작 프레임
        while (offset < feats.size) { // 청크마다(feats.split(chunk_max_frame))
            val frames = minOf(CHUNK_MAX_FRAME, feats.size - offset) // 이 청크 프레임 수
            runChunk(sessionFor(mode), feats, offset, frames, probs) // 추론 → probs[offset ..]
            offset += frames // 다음 청크
        }
        return probs // 확률
    }

    /**
     * 목적: 음성 구간 검출
     * 기능: 프리셋 계열·파라미터 타입을 확인하고 모드에 맞는 모델 확률 → FireRedPostprocess.apply(OFFLINE 은 dur = N/16000) 로 구간을 만든다.
     * 입력: audio(16 kHz mono float), preset(FIRERED 계열 프리셋, params = FireRedParams)
     * 출력: 다른 계열이거나 params 타입이 다르면 IllegalArgumentException
     * 리턴: VadOutput(구간, frameHopSec 0.010, frameScores = 음성 확률)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "FireRedVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val p = preset.params as? FireRedParams // 파라미터 캐스팅
            ?: throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 FireRedParams 가 아님: ${preset.params::class.simpleName}") // 타입 오류
        val probs = probabilities(audio, p.mode) // 모드별 모델 확률
        val segments = FireRedPostprocess.apply(probs, p, audio.size.toDouble() / SAMPLE_RATE) // dur = wav_np.shape[0] / sample_rate
        return VadOutput(segments, HOP_SEC, probs) // hop 10 ms
    }

    /**
     * 목적: 자원 해제
     * 기능: 열린 세션을 모두 닫는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        sessions.values.forEach { it.close() } // 세션 해제
        sessions.clear() // 목록 비움
    }

    /**
     * 목적: 모드별 세션 얻기
     * 기능: 처음 요청 시 모델 파일로 세션을 열고 입력 `feat`·출력 `probs` 이름을 확인한다.
     * 입력: mode(후처리 모드)
     * 출력: 파일이 없거나 입출력 이름이 다르면 IllegalArgumentException
     * 리턴: OrtSession
     */
    private fun sessionFor(mode: FireRedMode): OrtSession = sessions.getOrPut(mode) {
        val file = File(modelDir, if (mode == FireRedMode.OFFLINE) OFFLINE_MODEL else STREAM_MODEL) // 모드별 모델 파일
        val s = OrtSessions.open(file, threads) // 세션 열기(없으면 예외)
        if (IN_FEAT !in s.inputNames || OUT_PROBS !in s.outputNames) { // 입출력 이름 확인
            s.close() // 정리
            throw IllegalArgumentException("FireRed ONNX 입출력(feat → probs)이 아님: ${s.inputNames} → ${s.outputNames} ($file)") // 다른 모델로 대체하지 않음
        }
        s // 세션
    }

    /**
     * 목적: 특징 청크 1 개 추론
     * 기능: feats[offset, offset + frames) 를 [1, frames, 80] 텐서로 넣고(다른 입력 없음 = 스트리밍 모델 0 캐시) 출력 probs [1, frames, 1] 을 복사한다.
     * 입력: session, feats(전체 특징), offset(시작 프레임), frames(프레임 수), out(결과 배열, offset 위치에 씀)
     * 출력: 출력 형상이 다르면 IllegalStateException
     * 리턴: 없음
     */
    private fun runChunk(session: OrtSession, feats: Array<FloatArray>, offset: Int, frames: Int, out: FloatArray) {
        val buf = FloatArray(frames * NUM_MEL) // 평탄화 입력
        for (i in 0 until frames) System.arraycopy(feats[offset + i], 0, buf, i * NUM_MEL, NUM_MEL) // 행 복사
        OnnxTensor.createTensor(OrtSessions.environment, FloatBuffer.wrap(buf), longArrayOf(1, frames.toLong(), NUM_MEL.toLong())).use { feat -> // feat (1, T, 80)
            session.run(mapOf(IN_FEAT to feat), setOf(OUT_PROBS)).use { res -> // probs 만 요청(스트리밍 모델의 cache_out 은 받지 않음)
                val t = res.get(OUT_PROBS).orElseThrow { IllegalStateException("FireRed 출력 '$OUT_PROBS' 없음") } as OnnxTensor // 확률 텐서
                val shape = t.info.shape // 형상
                check(shape.contentEquals(longArrayOf(1, frames.toLong(), 1))) { "FireRed probs 형상 ${shape.contentToString()} ≠ (1, $frames, 1)" } // 형상 확인
                t.floatBuffer.get(out, offset, frames) // 결과 복사
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16000 // 입력 샘플레이트
        const val NUM_MEL = 80 // 특징 차원
        const val HOP_SEC = 0.010 // 프레임 간격(초)
        const val CHUNK_MAX_FRAME = 30000 // FireRedVadConfig/FireRedStreamVadConfig chunk_max_frame
        const val OFFLINE_MODEL = "fireredvad_vad.onnx" // 오프라인 모델
        const val STREAM_MODEL = "fireredvad_stream_vad.onnx" // 스트리밍 모델(전체 1 회 추론)
        const val IN_FEAT = "feat" // 입력 이름
        const val OUT_PROBS = "probs" // 출력 이름
    }
}
