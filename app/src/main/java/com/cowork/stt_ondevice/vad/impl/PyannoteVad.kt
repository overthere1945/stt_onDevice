// 파일명: PyannoteVad.kt
// 목적: pyannote segmentation-3.0(sherpa-onnx 변환본 model.onnx, fp32) ONNX 추론 + Binarize 후처리 VAD 구현
// 기능: Task 7 `gen_pyannote.py` 의 reference_scores(= pyannote.audio 3.4.0 VoiceActivityDetection 의 Inference 경로와 |차| ≤ 1e-6 일치)
//       를 그대로 재현한다(tools/golden/README_한국어.md §6.2 1~10 단계):
//       10 s 창(메타 window_size)·step 1 s(= 창의 0.1, pyannote Inference 기본값), 마지막 불완전 창 0 패딩,
//       창별 출력 y(1, F, 7)(powerset log-softmax) → 프레임 speech = argmax ≠ 0(무음 클래스 0 이 아님 = 화자 중 최대),
//       hamming(F) 가중 겹침 평균(float32 누적), 패딩 창이 있으면 오디오 길이까지 loose crop, 프레임 중앙 시각 = 0.5·(i·h + (i·h + d)).
//       구간은 PyannoteBinarize.apply(점수, 시각, 프리셋) 로 만든다.
//       앱도 골든과 같은 fp32 model.onnx 를 써야 점수 정합(≤ 1e-3)이 성립한다(int8 은 미검증).
//       인스턴스는 단일 스레드 전용이다.
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import ai.onnxruntime.OnnxTensor // ORT 텐서
import ai.onnxruntime.OrtSession // ORT 세션
import com.cowork.stt_ondevice.vad.OrtSessions // 세션 생성 도우미
import com.cowork.stt_ondevice.vad.PyannoteParams // pyannote 후처리 파라미터
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.post.PyannoteBinarize // Binarize 후처리 포팅
import java.io.File // 모델 파일
import java.nio.FloatBuffer // 텐서 입력 버퍼
import kotlin.math.PI // 원주율
import kotlin.math.cos // 코사인
import kotlin.math.floor // 내림

// change(add)-hyungchul-2026-10-10-13:56
/**
 * 목적: pyannote segmentation-3.0 ONNX 기반 VAD
 * 기능: 생성 시 ONNX 메타데이터(window_size, receptive_field_size, receptive_field_shift, num_speakers, powerset_max_classes,
 *       num_classes, sample_rate)를 읽어 검증하고, speechScores 로 프레임 speech 점수·시각을, detect 로 구간을 만든다.
 * 입력: modelFile(sherpa-onnx-pyannote-segmentation-3-0/model.onnx), threads(ORT intra-op 스레드 수, 기본 1)
 * 출력: 메타데이터가 없거나 값이 맞지 않으면 IllegalArgumentException
 * 리턴: 해당 없음
 */
class PyannoteVad(modelFile: File, threads: Int = 1) : VadEngine {

    override val family: VadFamily = VadFamily.PYANNOTE // pyannote 계열

    private val session: OrtSession = OrtSessions.open(modelFile, threads) // ORT 세션(close 에서 닫음)

    private val windowSamples: Int // 창 샘플 수 W(메타 window_size, 160000)
    private val stepSamples: Int // 창 이동 샘플 수 S(= W/10, 16000)
    private val frameStepSec: Double // 프레임 간격 h(초) = receptive_field_shift / sample_rate
    private val frameDurationSec: Double // 프레임 길이 d(초) = receptive_field_size / sample_rate
    private val numClasses: Int // powerset 클래스 수(7)
    private val inputName: String // 입력 이름(x)

    init {
        try { // 검증 실패 시 세션을 닫고 예외 전달
            val meta = session.metadata.customMetadata // sherpa 변환본 메타데이터
            fun metaInt(key: String): Int = meta[key]?.toIntOrNull() // 정수 메타 읽기
                ?: throw IllegalArgumentException("pyannote ONNX 메타데이터 '$key' 없음/정수 아님: ${modelFile.absolutePath}") // 없으면 실패
            val sampleRate = metaInt("sample_rate") // 샘플레이트
            require(sampleRate == SAMPLE_RATE) { "pyannote 모델 sample_rate $sampleRate ≠ $SAMPLE_RATE" } // 16 kHz 만 지원
            windowSamples = metaInt("window_size") // 창 샘플 수
            require(windowSamples > 0 && windowSamples % 10 == 0) { "window_size $windowSamples 가 10 의 배수가 아님(step = 창의 0.1)" } // step 정수 조건
            stepSamples = windowSamples / 10 // pyannote Inference 기본 step = 0.1 × duration
            frameStepSec = metaInt("receptive_field_shift").toDouble() / sampleRate // h = 270/16000
            frameDurationSec = metaInt("receptive_field_size").toDouble() / sampleRate // d = 991/16000
            val numSpeakers = metaInt("num_speakers") // 화자 수(3)
            val maxSet = metaInt("powerset_max_classes") // 동시 최대 화자 수(2)
            numClasses = metaInt("num_classes") // 클래스 수(7)
            require(numClasses == powersetSize(numSpeakers, maxSet)) { "num_classes $numClasses ≠ powerset 크기 C($numSpeakers, 0..$maxSet)" } // 클래스 0 = 무음 전제 확인
            require(session.inputNames.size == 1) { "pyannote 입력이 1 개가 아님: ${session.inputNames}" } // 입력 x 하나
            inputName = session.inputNames.first() // 입력 이름
        } catch (e: Exception) {
            session.close() // 세션 정리
            throw e // 원래 예외 전달
        }
    }

    /**
     * 목적: 프레임 speech 점수와 프레임 중앙 시각 계산(테스트에서 Python 점수와 직접 비교하기 위해 공개)
     * 기능: README §6.2 2~10 단계 — 창 분할(마지막 0 패딩) → 창별 argmax≠0 → hamming 가중 평균(float32 누적) → loose crop → 시각.
     * 입력: audio(16 kHz mono float, −1~1)
     * 출력: 없음
     * 리턴: Pair(speech 점수 FloatArray, 프레임 중앙 시각 DoubleArray(초)) — 길이 같음
     */
    fun speechScores(audio: FloatArray): Pair<FloatArray, DoubleArray> {
        val n = audio.size // 샘플 수 N
        val fullChunks = if (n >= windowSamples) (n - windowSamples) / stepSamples + 1 else 0 // 완전한 창 수 C0
        val hasLast = n < windowSamples || (n - windowSamples) % stepSamples > 0 // 불완전한 마지막 창 존재 여부
        val totalChunks = fullChunks + if (hasLast) 1 else 0 // 전체 창 수 C
        val windowSec = windowSamples.toDouble() / SAMPLE_RATE // 창 길이(초, 10.0)
        val stepSec = stepSamples.toDouble() / SAMPLE_RATE // 창 이동(초, 1.0)
        val numFrames = Math.rint(((totalChunks - 1) * stepSec + windowSec) / frameStepSec).toInt() + 1 // T = rint((10 + (C−1)·1)/h) + 1
        val acc = FloatArray(numFrames) // 가중 합(float32)
        val cnt = FloatArray(numFrames) // 가중치 합(float32)
        val chunk = FloatArray(windowSamples) // 창 입력 버퍼
        var hamming: DoubleArray? = null // 창당 프레임 수 F 를 안 뒤 만듦
        for (c in 0 until totalChunks) { // 창마다
            val start = c * stepSamples // 창 시작 샘플
            val len = minOf(windowSamples, n - start) // 실제 샘플 수(마지막 창은 짧음)
            System.arraycopy(audio, start, chunk, 0, len) // 창 샘플 복사
            chunk.fill(0f, len, windowSamples) // 0 패딩
            val speech = chunkSpeech(chunk) // 창 프레임별 speech 0/1
            val w = hamming ?: hammingWindow(speech.size).also { hamming = it } // np.hamming(F)
            check(w.size == speech.size) { "창마다 프레임 수가 다름: ${speech.size} ≠ ${w.size}" } // 고정 창이라 F 일정
            val s0 = Math.rint((c * stepSec) / frameStepSec).toInt() // 창 시작 프레임 = rint(c·1 s / h)
            for (k in speech.indices) { // 창 프레임마다
                val t = s0 + k // 전체 프레임 인덱스
                if (t >= numFrames) break // numpy 슬라이스처럼 범위 밖은 버림
                acc[t] = (acc[t].toDouble() + speech[k] * w[k]).toFloat() // float32 += float32·float64 (numpy 승격 후 float32 저장)
                cnt[t] = (cnt[t].toDouble() + w[k]).toFloat() // float32 += float64
            }
        }
        val keep = if (hasLast) minOf(floor((n.toDouble() / SAMPLE_RATE) / frameStepSec).toInt() + 1, numFrames) else numFrames // loose crop 프레임 수
        val scores = FloatArray(keep) { t -> acc[t] / maxOf(cnt[t], 1e-12f) } // 가중 평균(덮지 않는 프레임은 0/1e-12 = 0)
        val times = DoubleArray(keep) { i -> // 프레임 중앙 시각
            val s = 0.0 + i * frameStepSec // SlidingWindow 시작 = start(0) + i·step
            0.5 * (s + (s + frameDurationSec)) // Segment(start, start + duration).middle
        }
        return scores to times // 점수·시각
    }

    /**
     * 목적: 창 1 개 추론 → 프레임별 speech(0/1)
     * 기능: 입력 x (1,1,W) 로 ONNX 를 실행해 y (1,F,numClasses) 를 받고 프레임마다 argmax(동률이면 첫 인덱스) ≠ 0 이면 1 로 둔다.
     * 입력: chunk(W 샘플 창)
     * 출력: 출력 형상이 다르면 IllegalStateException
     * 리턴: 길이 F 의 0/1 배열(Float)
     */
    private fun chunkSpeech(chunk: FloatArray): FloatArray {
        val env = OrtSessions.environment // 공용 ORT 환경
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, 1, windowSamples.toLong())).use { x -> // x (1,1,W)
            session.run(mapOf(inputName to x)).use { res -> // 추론
                val y = res.get(0) as OnnxTensor // 첫 출력 y
                val shape = y.info.shape // (1, F, C)
                check(shape.size == 3 && shape[0] == 1L && shape[2] == numClasses.toLong()) { "pyannote 출력 형상 이상: ${shape.contentToString()}" } // 형상 확인
                val frames = shape[1].toInt() // 창당 프레임 수 F(589)
                val buf = y.floatBuffer // 출력 값
                return FloatArray(frames) { k -> // 프레임마다
                    var best = 0 // argmax 인덱스(동률이면 첫 인덱스 유지)
                    var bestVal = buf.get(k * numClasses) // 클래스 0 값
                    for (j in 1 until numClasses) { // 나머지 클래스
                        val v = buf.get(k * numClasses + j) // 클래스 j 값
                        if (v > bestVal) { best = j; bestVal = v } // 엄격히 클 때만 갱신(torch/np argmax 규칙)
                    }
                    if (best != 0) 1f else 0f // 무음(빈 집합) 외 = speech
                }
            }
        }
    }

    /**
     * 목적: 음성 구간 검출
     * 기능: 프리셋 계열·파라미터 타입을 확인하고 speech 점수·시각 → PyannoteBinarize.apply 로 구간을 만든다.
     * 입력: audio(16 kHz mono float), preset(PYANNOTE 계열 프리셋, params = PyannoteParams)
     * 출력: 다른 계열이거나 params 타입이 다르면 IllegalArgumentException
     * 리턴: VadOutput(구간, frameHopSec = h(0.016875 s), frameScores = speech 점수)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "PyannoteVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val p = preset.params as? PyannoteParams // 파라미터 캐스팅
            ?: throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 PyannoteParams 가 아님: ${preset.params::class.simpleName}") // 타입 오류
        val (scores, times) = speechScores(audio) // 점수·시각
        val segments = PyannoteBinarize.apply(scores, times, p) // Binarize 후처리
        return VadOutput(segments, frameStepSec, scores) // 프레임 i 시작 = i·h
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
        const val SAMPLE_RATE = 16000 // 입력 샘플레이트(Hz)

        /**
         * 목적: numpy 1.26 np.hamming(m) 재현
         * 기능: numpy 원본 식 그대로 n = 1−m+2k(k = 0..m−1), w[k] = 0.54 + 0.46·cos(π·n/(m−1)) (m = 1 이면 [1.0]) 을 double 로 계산한다.
         *       (수학적으로 0.54 − 0.46·cos(2πk/(m−1)) 와 같지만 부동소수 연산 순서를 원본과 맞춤)
         * 입력: m(길이)
         * 출력: 없음
         * 리턴: 길이 m 배열
         */
        fun hammingWindow(m: Int): DoubleArray =
            if (m == 1) doubleArrayOf(1.0) else DoubleArray(m) { k -> 0.54 + 0.46 * cos(PI * (1 - m + 2 * k) / (m - 1)) } // np.hamming 식

        /**
         * 목적: powerset 클래스 수 계산
         * 기능: Σ_{k=0}^{maxSet} C(numSpeakers, k) (pyannote Powerset, 클래스 0 = 빈 집합 = 무음).
         * 입력: numSpeakers(화자 수), maxSet(동시 최대 화자 수)
         * 출력: 없음
         * 리턴: 클래스 수
         */
        fun powersetSize(numSpeakers: Int, maxSet: Int): Int {
            var total = 0 // 합계
            var comb = 1L // C(n, 0)
            for (k in 0..maxSet) { // k 화자 조합마다
                total += comb.toInt() // C(n, k) 더함
                comb = comb * (numSpeakers - k) / (k + 1) // C(n, k+1)
            }
            return total // 결과
        }
    }
}
