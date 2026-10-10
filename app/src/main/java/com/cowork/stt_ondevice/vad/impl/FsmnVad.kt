// 파일명: FsmnVad.kt
// 목적: FunASR FSMN-VAD(iic/speech_fsmn_vad_zh-cn-16k-common-onnx, model_quant.onnx) ONNX 추론 + E2EVadModel 상태기계 VAD 구현
// 기능: funasr_onnx 0.4.3 `Fsmn_vad.__call__`(vad_bin.py L95–148)을 재현한다(tools/golden/README_한국어.md §6.3, §6.7):
//       fbank(KaldiFbank) → LFR(lfr_m, lfr_n) → CMVN(am.mvn) 을 파형 전체에 한 번 계산 → FsmnStateMachine.blockPlan 의 블록마다
//       ONNX 입력 [speech (1, 블록 프레임 수, 400), in_cache0..3 (1, proj_dim, lorder−1, 1)] 을 넣고(첫 블록 cache 0, 이후 직전 블록 out_cache),
//       출력 logits[0][t][0] 을 sil prob 으로 쓴다. T % 6000 == 1 재처리 블록도 원본처럼 cache 를 이어 받아 다시 추론하고,
//       그 점수를 FsmnStateMachine.apply 의 blockSilProb 로 넘긴다.
//       프레임 dB = 10·log10(Σ x[160t+i]² + 1e-6) (x = −1~1 float 파형, 400 샘플 창, 160 이동; 원본 ComputeDecibel 과 같게
//       float32 제곱·numpy pairwise 합(float32) 후 double 로 +1e-6·log10).
//       VadOutput.frameScores 는 "낮을수록 무음" 의미(VadOutput 규약)를 맞추려고 1 − sil prob(음성 점수)으로 낸다.
//       인스턴스는 단일 스레드 전용이다(KaldiFbank 작업 버퍼를 인스턴스가 재사용, 스레드 안전하지 않음).
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import ai.onnxruntime.OnnxTensor // ORT 텐서
import ai.onnxruntime.OrtSession // ORT 세션
import ai.onnxruntime.TensorInfo // 텐서 형상 정보
import com.cowork.stt_ondevice.vad.FsmnParams // FSMN 상태기계 파라미터
import com.cowork.stt_ondevice.vad.OrtSessions // 세션 생성 도우미
import com.cowork.stt_ondevice.vad.VadEngine // VAD 인터페이스
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.feature.Cmvn // CMVN(am.mvn)
import com.cowork.stt_ondevice.vad.feature.KaldiFbank // Kaldi fbank
import com.cowork.stt_ondevice.vad.feature.Lfr // LFR 프레임 연결
import com.cowork.stt_ondevice.vad.post.FsmnStateMachine // E2EVadModel 상태기계 포팅
import java.io.File // 모델 폴더
import java.nio.FloatBuffer // 텐서 입력 버퍼
import kotlin.math.log10 // 상용로그

// change(add)-hyungchul-2026-10-10-13:56
/**
 * 목적: FSMN-VAD 모델 점수 묶음
 * 기능: 전체 프레임 sil prob(블록 첫 추론 값), 프레임 dB, blockPlan 순서의 블록별 sil prob(재처리 블록 포함)을 담는다.
 */
class FsmnScores(
    val silProb: FloatArray, // 전체 프레임 sil prob(길이 T, 각 프레임을 처음 추론한 블록의 값)
    val frameDb: FloatArray, // 전체 프레임 dB(길이 T)
    val blockSilProb: List<FloatArray>, // FsmnStateMachine.blockPlan(T) 순서의 블록별 sil prob(블록 길이)
)

// change(add)-hyungchul-2026-10-10-13:56
/**
 * 목적: FSMN-VAD ONNX 기반 VAD
 * 기능: 생성 시 config.yaml(frontend_conf·encoder_conf·model_conf.sil_pdf_ids)을 읽어 지원 설정인지 확인하고,
 *       scores 로 sil prob·dB 를, detect 로 구간을 만든다.
 * 입력: modelDir(model_quant.onnx, am.mvn, config.yaml 이 있는 폴더), threads(ORT intra-op 스레드 수, 기본 1)
 * 출력: 파일이 없거나 설정이 지원 범위 밖이면 IllegalArgumentException
 * 리턴: 해당 없음
 */
class FsmnVad(modelDir: File, threads: Int = 1) : VadEngine {

    override val family: VadFamily = VadFamily.FSMN // FSMN 계열

    private val sampleRate: Int // frontend_conf.fs(16000)
    private val frameLengthMs: Int // frontend_conf.frame_length(25)
    private val frameShiftMs: Int // frontend_conf.frame_shift(10)
    private val lfrM: Int // frontend_conf.lfr_m(5)
    private val lfrN: Int // frontend_conf.lfr_n(1)
    private val cacheShape: LongArray // in_cache 형상 (1, proj_dim, lorder − 1, 1)
    private val numLayers: Int // encoder_conf.fsmn_layers(4) = cache 개수
    private val fbank: KaldiFbank // fbank 계산기(스레드 안전하지 않음 → 인스턴스 단일 스레드)
    private val cmvn: Cmvn // am.mvn
    private val session: OrtSession // ORT 세션(close 에서 닫음)
    private val inputNames: List<String> // 입력 이름(모델 순서: speech, in_cache0..)
    private val featDim: Int // ONNX 입력 특징 차원(lfr_m × n_mels = 400)

    init {
        val conf = readSimpleYaml(File(modelDir, "config.yaml")) // config.yaml(필요한 단순 키만)
        val fe = conf["frontend_conf"] ?: throw IllegalArgumentException("config.yaml 에 frontend_conf 없음: $modelDir") // 전처리 설정
        val enc = conf["encoder_conf"] ?: throw IllegalArgumentException("config.yaml 에 encoder_conf 없음: $modelDir") // 인코더 설정
        val mc = conf["model_conf"] ?: throw IllegalArgumentException("config.yaml 에 model_conf 없음: $modelDir") // 후처리 설정
        fun int(m: Map<String, String>, k: String): Int = m[k]?.toIntOrNull() ?: throw IllegalArgumentException("config.yaml 키 '$k' 없음/정수 아님") // 정수 키
        sampleRate = int(fe, "fs") // 샘플레이트
        require(sampleRate == 16000) { "FSMN fs $sampleRate 미지원(16000 만)" } // 입력은 항상 16 kHz
        require(fe["window"] == "hamming") { "FSMN frontend window '${fe["window"]}' 미지원(KaldiFbank 는 hamming)" } // 창 함수
        require(fe["dither"]?.toDoubleOrNull() == 0.0) { "FSMN frontend dither '${fe["dither"]}' 미지원(0 만)" } // dither 0 만(결정적)
        val nMels = int(fe, "n_mels") // mel 수(80)
        frameLengthMs = int(fe, "frame_length") // 창 길이(ms)
        frameShiftMs = int(fe, "frame_shift") // 이동(ms)
        lfrM = int(fe, "lfr_m") // LFR m
        lfrN = int(fe, "lfr_n") // LFR n
        require(lfrN == 1) { "FSMN lfr_n $lfrN 미지원(1 만: sil prob 프레임 = fbank 프레임 = dB 프레임)" } // 프레임 수 일치 전제
        require(mc["sil_pdf_ids"]?.replace(" ", "") == "[0]") { "FSMN sil_pdf_ids '${mc["sil_pdf_ids"]}' 미지원([0] 만: FsmnStateMachine 이 logits[..][0] 사용)" } // 상태기계 전제
        numLayers = int(enc, "fsmn_layers") // cache 개수
        cacheShape = longArrayOf(1, int(enc, "proj_dim").toLong(), (int(enc, "lorder") - 1).toLong(), 1) // vad_bin prepare_cache 와 같은 형상
        fbank = KaldiFbank(nMels, frameLengthMs, frameShiftMs, sampleRate) // fbank 계산기
        cmvn = Cmvn.loadAmMvn(File(modelDir, "am.mvn")) // CMVN
        featDim = lfrM * nMels // 특징 차원
        session = OrtSessions.open(File(modelDir, "model_quant.onnx"), threads) // 양자화 모델(quantize=True 와 같은 파일)
        try { // 모델 입력 확인(실패 시 세션 정리)
            inputNames = session.inputInfo.keys.toList() // 모델 입력 순서(funasr_onnx 는 이 순서에 [speech, cache...] 를 zip)
            require(inputNames.size == 1 + numLayers) { "FSMN 입력 수 ${inputNames.size} ≠ 1 + fsmn_layers($numLayers): $inputNames" } // 입력 개수
            val speechShape = (session.inputInfo.getValue(inputNames[0]).info as TensorInfo).shape // speech 형상(1, T, 400)
            require(speechShape.size == 3 && speechShape[2] == featDim.toLong()) { "FSMN speech 입력 형상 ${speechShape.contentToString()} 이 (1, T, $featDim) 아님" } // 차원 일치
            for (i in 1..numLayers) { // cache 입력마다
                val s = (session.inputInfo.getValue(inputNames[i]).info as TensorInfo).shape // 모델이 선언한 cache 형상
                require(s.contentEquals(cacheShape)) { "FSMN ${inputNames[i]} 형상 ${s.contentToString()} ≠ config 형상 ${cacheShape.contentToString()}" } // 형상 일치
            }
            require(session.outputInfo.size == 1 + numLayers) { "FSMN 출력 수 ${session.outputInfo.size} ≠ 1 + fsmn_layers" } // logits + out_cache
        } catch (e: Exception) {
            session.close() // 세션 정리
            throw e // 원래 예외 전달
        }
    }

    /**
     * 목적: sil prob·프레임 dB 계산(테스트에서 Python 점수와 직접 비교하기 위해 공개)
     * 기능: 특징을 전체 파형에 한 번 계산하고 blockPlan 의 블록마다 ONNX 를 cache 를 이어 가며 호출한다(vad_bin L110–129).
     * 입력: audio(16 kHz mono float, −1~1)
     * 출력: 없음
     * 리턴: FsmnScores(전체 sil prob·dB(길이 T = 1 + (N − 400)/160, N < 400 이면 0), 블록별 sil prob)
     */
    fun scores(audio: FloatArray): FsmnScores {
        val feats = cmvn.apply(Lfr.apply(fbank.compute(audio), lfrM, lfrN)) // fbank → LFR → CMVN (FunASR 순서)
        val numFrames = feats.size // T
        val db = frameDecibels(audio, numFrames) // 프레임 dB
        val plan = FsmnStateMachine.blockPlan(numFrames) // 블록 분할(상태기계와 같은 규칙)
        val env = OrtSessions.environment // 공용 ORT 환경
        val cacheSize = cacheShape.fold(1L) { a, b -> a * b }.toInt() // cache 원소 수
        var caches = List(numLayers) { FloatArray(cacheSize) } // 첫 블록 cache = 0 (prepare_cache)
        val blocks = ArrayList<FloatArray>(plan.size) // 블록별 sil prob
        val silProb = FloatArray(numFrames) // 전체 sil prob
        var filled = 0 // silProb 에 채운 프레임 수(재처리 블록은 이미 채운 프레임이라 건너뜀)
        for (b in plan) { // 블록마다
            val buf = FloatArray(b.frames * featDim) // speech (1, frames, 400) 평탄화
            for (i in 0 until b.frames) System.arraycopy(feats[b.offset + i], 0, buf, i * featDim, featDim) // feats[o : o + step]
            val inputs = LinkedHashMap<String, OnnxTensor>() // 입력 텐서(모두 닫아야 함)
            try {
                inputs[inputNames[0]] = OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, b.frames.toLong(), featDim.toLong())) // speech
                for (l in 0 until numLayers) inputs[inputNames[1 + l]] = OnnxTensor.createTensor(env, FloatBuffer.wrap(caches[l]), cacheShape) // in_cache(직전 out_cache)
                session.run(inputs).use { res -> // 추론
                    val logits = res.get(0) as OnnxTensor // logits (1, frames, 248) softmax 확률
                    val shape = logits.info.shape // 형상
                    check(shape.size == 3 && shape[1] == b.frames.toLong()) { "FSMN logits 형상 이상: ${shape.contentToString()}" } // 형상 확인
                    val classes = shape[2].toInt() // 클래스 수(248)
                    val lb = logits.floatBuffer // 값
                    val sil = FloatArray(b.frames) { t -> lb.get(t * classes) } // sil prob = logits[0][t][0] (sil_pdf_ids [0])
                    blocks += sil // 블록 점수 기록
                    for (t in 0 until b.frames) { // 전체 배열 채우기(처음 추론한 값만)
                        val g = b.offset + t // 전역 프레임
                        if (g >= filled) silProb[g] = sil[t] // 아직 안 채운 프레임만
                    }
                    filled = maxOf(filled, b.offset + b.frames) // 채운 범위 갱신
                    caches = List(numLayers) { l -> // out_cache → 다음 블록 in_cache (vad_bin L128: in_cache = out_caches)
                        val c = res.get(1 + l) as OnnxTensor // out_cache l
                        check(c.info.shape.contentEquals(cacheShape)) { "FSMN out_cache$l 형상 ${c.info.shape.contentToString()} ≠ ${cacheShape.contentToString()}" } // 형상 확인
                        FloatArray(cacheSize).also { c.floatBuffer.get(it) } // 값 복사(Result 닫히기 전에)
                    }
                }
            } finally {
                inputs.values.forEach { it.close() } // 입력 텐서 해제
            }
        }
        return FsmnScores(silProb, db, blocks) // 결과
    }

    /**
     * 목적: 음성 구간 검출
     * 기능: 프리셋 계열·파라미터 타입과 프레임 설정(frame_length_ms·frame_in_ms 가 config 전처리와 같음)을 확인하고
     *       scores → FsmnStateMachine.apply(전체 sil prob, dB, 프리셋, 블록별 sil prob) 로 구간을 만든다.
     * 입력: audio(16 kHz mono float), preset(FSMN 계열 프리셋, params = FsmnParams)
     * 출력: 다른 계열·params 타입·프레임 설정 불일치면 IllegalArgumentException
     * 리턴: VadOutput(구간, frameHopSec 0.010, frameScores = 1 − sil prob)
     */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        require(preset.family == family) { "FsmnVad 에 ${preset.family} 계열 프리셋(${preset.id})을 넣을 수 없음" } // 계열 확인
        val p = preset.params as? FsmnParams // 파라미터 캐스팅
            ?: throw IllegalArgumentException("프리셋 ${preset.id} 의 params 가 FsmnParams 가 아님: ${preset.params::class.simpleName}") // 타입 오류
        require(p.frameInMs == frameShiftMs && p.frameLengthMs == frameLengthMs) { // dB·sil prob 프레임 = 상태기계 프레임 전제
            "프리셋 ${preset.id} 의 frame_in_ms/frame_length_ms(${p.frameInMs}/${p.frameLengthMs}) ≠ 모델 전처리(${frameShiftMs}/${frameLengthMs})" // 불일치
        }
        val s = scores(audio) // 모델 점수
        val segments = FsmnStateMachine.apply(s.silProb, s.frameDb, p, s.blockSilProb) // 상태기계(재처리 블록은 재추론 점수)
        val speech = FloatArray(s.silProb.size) { (1.0 - s.silProb[it].toDouble()).toFloat() } // 음성 점수 = 1 − sil(낮을수록 무음)
        return VadOutput(segments, frameShiftMs / 1000.0, speech) // hop 10 ms
    }

    /**
     * 목적: 자원 해제
     * 기능: ORT 세션을 닫는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() = session.close() // 세션 해제

    /**
     * 목적: 프레임 dB 계산(funasr_onnx E2EVadModel.ComputeDecibel 재현)
     * 기능: t = 0..T−1 마다 x[shift·t, shift·t + len) 의 float32 제곱을 numpy pairwise 합(float32)으로 더한 뒤
     *       double 로 10·log10(합 + 1e-6) 을 계산한다(numpy 1.26: float32 스칼라 + Python float → float64). 완전 무음은 −60.
     * 입력: audio(−1~1 float 파형), numFrames(T)
     * 출력: 없음
     * 리턴: 길이 T 의 dB(Float)
     */
    private fun frameDecibels(audio: FloatArray, numFrames: Int): FloatArray {
        val len = sampleRate * frameLengthMs / 1000 // 창 샘플 수(400)
        val shift = sampleRate * frameShiftMs / 1000 // 이동 샘플 수(160)
        val sq = FloatArray(len) // 제곱 작업 버퍼
        return FloatArray(numFrames) { t -> // 프레임마다
            val start = t * shift // 창 시작
            for (i in 0 until len) { val v = audio[start + i]; sq[i] = v * v } // np.square(float32)
            (10.0 * log10(pairwiseSum(sq, 0, len).toDouble() + 0.000001)).toFloat() // 10·log10(합 + 1e-6)
        }
    }

    private companion object {
        /** numpy pairwise 합의 블록 크기(PW_BLOCKSIZE). */
        const val PW_BLOCKSIZE = 128 // numpy 상수

        /**
         * 목적: numpy 1.26 float32 sum 재현(FLOAT_pairwise_sum, loops_utils.h.src)
         * 기능: n < 8 이면 순차 합, n ≤ 128 이면 8 개 누산기 펼침 합 후 ((r0+r1)+(r2+r3))+((r4+r5)+(r6+r7)) + 나머지,
         *       그 외에는 n2 = n/2 − (n/2 % 8) 로 나눠 재귀. 누적은 모두 float32. (reduce 초기값 0 을 더해도 값이 같음)
         * 입력: a(배열), off(시작), n(개수)
         * 출력: 없음
         * 리턴: float32 합
         */
        fun pairwiseSum(a: FloatArray, off: Int, n: Int): Float {
            if (n < 8) { // 짧으면 순차 합
                var res = 0f // 누적
                for (i in 0 until n) res += a[off + i] // 차례로 더함
                return res // 결과
            }
            if (n <= PW_BLOCKSIZE) { // 펼침 합
                val r = FloatArray(8) { a[off + it] } // 누산기 8 개 초기화
                var i = 8 // 다음 위치
                while (i < n - (n % 8)) { // 8 개씩
                    for (j in 0 until 8) r[j] += a[off + i + j] // 누산기별 누적
                    i += 8 // 다음 묶음
                }
                var res = ((r[0] + r[1]) + (r[2] + r[3])) + ((r[4] + r[5]) + (r[6] + r[7])) // 누산기 합치기(원본 순서)
                while (i < n) { res += a[off + i]; i++ } // 8 의 배수가 아닌 나머지
                return res // 결과
            }
            var n2 = n / 2 // 반으로
            n2 -= n2 % 8 // 8 의 배수로 맞춤
            return pairwiseSum(a, off, n2) + pairwiseSum(a, off + n2, n - n2) // 재귀 합
        }

        /**
         * 목적: config.yaml 의 단순 2 단계 키 읽기(YAML 라이브러리 없이 필요한 값만)
         * 기능: 들여쓰기 없는 "섹션:" 줄 아래 들여쓴 "키: 값" 줄을 섹션별 맵으로 모은다. '#' 뒤 주석은 버린다.
         *       목록·여러 줄 값은 원문 문자열 그대로 둔다(예: "[0]"). 파일이 없으면 예외.
         * 입력: file(config.yaml)
         * 출력: 파일 없으면 IllegalArgumentException
         * 리턴: 섹션 이름 → (키 → 값 문자열)
         */
        fun readSimpleYaml(file: File): Map<String, Map<String, String>> {
            require(file.isFile) { "FSMN config.yaml 없음: ${file.absolutePath}" } // 조용한 fallback 금지
            val out = LinkedHashMap<String, MutableMap<String, String>>() // 결과
            var section: MutableMap<String, String>? = null // 현재 섹션
            for (raw in file.readLines(Charsets.UTF_8)) { // 줄마다
                val line = raw.substringBefore('#').trimEnd() // 주석 제거
                if (line.isBlank()) continue // 빈 줄
                val indented = line[0] == ' ' || line[0] == '\t' // 들여쓰기 여부
                val key = line.substringBefore(':').trim() // 키
                val value = line.substringAfter(':', "").trim() // 값
                if (!indented) { // 최상위 키
                    section = if (value.isEmpty()) LinkedHashMap<String, String>().also { out[key] = it } else null // 값 없으면 섹션 시작
                } else if (section != null && line.contains(':')) { // 섹션 안 키
                    section[key] = value // 저장
                }
            }
            return out // 결과
        }
    }
}
