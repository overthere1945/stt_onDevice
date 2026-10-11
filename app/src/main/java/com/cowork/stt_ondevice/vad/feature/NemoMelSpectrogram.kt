// 파일명: NemoMelSpectrogram.kt
// 목적: MarbleNet(Frame_VAD_Multilingual_MarbleNet_v2.0) ONNX 입력용 NeMo log-mel 특징 계산의 Kotlin 포팅
// 기능: NeMo 3.0.0 nemo/collections/asr/parts/preprocessing/features.py `FilterbankFeatures.forward`(L415–494)를 추론 모드로 재현한다
//       (tools/export/README_한국어.md §7.2). 설정은 tools/export/export_marblenet.py 가 만든 marblenet_preprocessor.json 의
//       `effective`(실효값)·`windowValues`(torch.hann_window(400, periodic=False) float32 값)·`melFilterbank`(librosa slaney 80×257 float32)를 읽는다.
//       순서: 프리엠퍼시스 y[0]=x[0], y[i]=x[i]−0.97·x[i−1](L431–434, torch 와 같은 float32 연산) → center=True 앞뒤 n_fft/2 0 패딩 STFT
//       (창 400 을 512 가운데 56 샘플 뒤에 둠, torch.stft L378–388) → |X|²(L443–453) → mel 필터뱅크 곱(L463) → ln(x + 2^-24)(L465–467)
//       → seqLen = floor((N + 2·(n_fft/2) − n_fft)/hop)(get_seq_len L405–409) 이상 프레임 0 마스크 → pad_to 배수로 0 채움(L481–494).
//       정규화(normalize "None" 문자열 → normalize_batch else 분기 = 없음), dither(학습 때만), frame_splicing 1 은 적용할 것이 없다.
//       NeMo 는 STFT 이후를 float32 로 계산하지만 여기서는 FFT·|X|²·mel 곱·log 를 double 로 하고(float32 FFT 오차가 log 영역에서 1e-4 를
//       넘을 수 있음) 결과만 Float 로 낸다. 작업 버퍼를 재사용하므로 인스턴스는 스레드 안전하지 않다.
package com.cowork.stt_ondevice.vad.feature // 특징 추출 패키지

import kotlinx.serialization.json.Json // JSON 파서
import kotlinx.serialization.json.JsonArray // JSON 배열
import kotlinx.serialization.json.JsonElement // JSON 요소
import kotlinx.serialization.json.jsonArray // 요소 → 배열
import kotlinx.serialization.json.jsonObject // 요소 → 객체
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값
import java.io.File // 설정 파일
import kotlin.math.ln // 자연로그

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: NeMo FilterbankFeatures(추론) 계산기
 * 기능: 생성 시 설정 JSON 을 읽어 지원 범위인지 확인하고, compute 로 파형 전체의 log-mel 행렬(프레임 × mel)을 만든다.
 * 입력: config(marblenet_preprocessor.json 파일)
 * 출력: 파일이 없거나 지원하지 않는 설정이면 IllegalArgumentException
 * 리턴: 해당 없음
 */
class NemoMelSpectrogram(config: File) {

    val sampleRate: Int // 샘플레이트(16000)
    val hopLength: Int // 프레임 이동 샘플(160)
    val numMel: Int // mel 수(80)
    private val winLength: Int // 창 길이(400)
    private val nFft: Int // FFT 크기(512)
    private val preemph: Float // 프리엠퍼시스 계수(float32, torch 연산과 같게)
    private val logGuard: Double // log_zero_guard_value(2^-24)
    private val padTo: Int // pad_to(2), 0 이면 패딩 없음
    private val window: DoubleArray // 창 계수(float32 값)
    private val melFb: Array<DoubleArray> // mel 필터뱅크 numMel × (nFft/2+1)
    private val fft: KaldiFbank.RealPowerFft // 파워 스펙트럼 계산기(KaldiFbank 와 같은 radix-2, double)

    init {
        require(config.isFile) { "MarbleNet 전처리 설정 파일 없음: ${config.absolutePath}" } // 조용한 fallback 금지
        val root = Json.parseToJsonElement(config.readText(Charsets.UTF_8)).jsonObject // 설정 JSON
        val eff = root["effective"]?.jsonObject ?: throw IllegalArgumentException("설정에 effective 없음: $config") // 실효값
        fun str(k: String): String = eff[k]?.jsonPrimitive?.content ?: throw IllegalArgumentException("effective.$k 없음: $config") // 문자열 키
        fun int(k: String): Int = str(k).toIntOrNull() ?: throw IllegalArgumentException("effective.$k 정수 아님: ${str(k)}") // 정수 키
        fun dbl(k: String): Double = str(k).toDoubleOrNull() ?: throw IllegalArgumentException("effective.$k 실수 아님: ${str(k)}") // 실수 키
        fun expect(k: String, v: String) = require(str(k) == v) { "지원하지 않는 전처리 설정 effective.$k = ${str(k)} (지원: $v)" } // 고정값 확인
        sampleRate = int("sample_rate") // 샘플레이트
        require(sampleRate == 16000) { "MarbleNet sample_rate $sampleRate 미지원(16000 만)" } // 입력은 항상 16 kHz
        winLength = int("win_length") // 창 길이
        hopLength = int("hop_length") // 이동
        nFft = int("n_fft") // FFT 크기
        numMel = int("nfilt") // mel 수
        require(nFft > 0 && nFft and (nFft - 1) == 0 && winLength in 1..nFft && hopLength > 0) { "STFT 설정 미지원: win $winLength, hop $hopLength, n_fft $nFft" } // radix-2 FFT 전제
        preemph = dbl("preemph").toFloat() // torch 는 float32 텐서 × 파이썬 실수 → float32 연산
        expect("log", "true") // log 적용
        expect("log_zero_guard_type", "add") // log(x + guard)
        logGuard = dbl("log_zero_guard_value") // 2^-24
        require(dbl("mag_power") == 2.0) { "mag_power ${dbl("mag_power")} 미지원(2.0 만)" } // 파워 스펙트럼
        expect("normalize_applied", "false") // 정규화 없음
        expect("dither_applied_in_eval", "false") // 추론 dither 없음
        require(int("frame_splicing") == 1) { "frame_splicing ${int("frame_splicing")} 미지원(1 만)" } // 프레임 연결 없음
        padTo = int("pad_to") // pad_to("max" 등 문자열은 위 int() 에서 거부)
        require(padTo >= 0) { "pad_to $padTo 미지원" } // 음수 거부
        require(dbl("pad_value") == 0.0) { "pad_value ${dbl("pad_value")} 미지원(0 만)" } // 패딩 값
        expect("exact_pad", "false") // center 패딩 경로
        expect("stft_center", "true") // center=True
        expect("stft_pad_mode", "constant") // 0 패딩
        window = doubles(root["windowValues"], "windowValues") // 창 계수
        require(window.size == winLength) { "windowValues 길이 ${window.size} ≠ win_length $winLength" } // 길이 확인
        val fbRows = root["melFilterbank"]?.jsonArray ?: throw IllegalArgumentException("설정에 melFilterbank 없음: $config") // 필터뱅크
        require(fbRows.size == numMel) { "melFilterbank 행 수 ${fbRows.size} ≠ nfilt $numMel" } // 행 수
        melFb = Array(numMel) { m -> doubles(fbRows[m], "melFilterbank[$m]") } // 행마다 double
        require(melFb.all { it.size == nFft / 2 + 1 }) { "melFilterbank 열 수 ≠ n_fft/2+1 (${nFft / 2 + 1})" } // 열 수
        fft = KaldiFbank.RealPowerFft(nFft) // FFT
    }

    /**
     * 목적: 유효 프레임 수(seqLen) 계산(get_seq_len L405–409, center 경로)
     * 기능: floor((N + 2·(n_fft/2) − n_fft) / hop). ONNX 입력 processed_signal_length 로도 쓴다.
     * 입력: numSamples(N)
     * 출력: 없음
     * 리턴: seqLen
     */
    fun seqLen(numSamples: Int): Int = Math.floorDiv(numSamples + nFft / 2 * 2 - nFft, hopLength) // floor_divide

    /**
     * 목적: 파형 전체의 log-mel 계산
     * 기능: 프리엠퍼시스(float32) → 프레임 t(0 ≤ t < seqLen)마다 패딩 신호 [t·hop, t·hop + n_fft) 에 가운데 배치 창을 곱해 |X|² →
     *       mel 곱 → ln(+guard). t ≥ seqLen 프레임(STFT 마지막 프레임 포함)과 pad_to 패딩 프레임은 0 이다.
     *       프레임 수 F = (1 + floor(N/hop)) 를 pad_to 배수로 올린 값.
     * 입력: samples(16 kHz mono float, −1~1 = int16/32768)
     * 출력: 없음
     * 리턴: F × numMel 행렬(expected[t][m] = processed_signal[0][m][t])
     */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val n = samples.size // N
        val y = FloatArray(n) // 프리엠퍼시스 결과(float32)
        if (n > 0) y[0] = samples[0] // 첫 샘플 유지(L433 x[:, 0])
        for (i in 1 until n) y[i] = samples[i] - preemph * samples[i - 1] // float32 곱·뺄셈(torch 와 같은 반올림)
        val stftFrames = 1 + Math.floorDiv(n + nFft / 2 * 2 - nFft, hopLength) // center=True 프레임 수(n_fft 짝수 → 1 + N/hop)
        val total = if (padTo > 0 && stftFrames % padTo != 0) stftFrames + padTo - stftFrames % padTo else stftFrames // pad_to 배수
        val valid = minOf(seqLen(n), stftFrames) // 마스크 안 되는 프레임 수
        val winOffset = (nFft - winLength) / 2 // 창 앞 0 개수(torch.stft 가운데 배치)
        val frame = DoubleArray(nFft) // 프레임 작업 버퍼
        val power = DoubleArray(nFft / 2 + 1) // 파워 스펙트럼
        return Array(total) { t -> // 프레임마다
            if (t >= valid) return@Array FloatArray(numMel) // 마스크·패딩 프레임 = pad_value 0
            frame.fill(0.0) // 창 밖 0
            val base = t * hopLength - nFft / 2 + winOffset // 창 첫 샘플의 원 신호 인덱스(앞 n_fft/2 패딩 보정)
            for (k in 0 until winLength) { // 창 안 샘플마다
                val idx = base + k // 원 신호 인덱스
                if (idx in 0 until n) frame[winOffset + k] = y[idx] * window[k] // 패딩 영역(범위 밖)은 0
            }
            fft.powerSpectrum(frame, power) // |X[k]|², k = 0..n_fft/2
            FloatArray(numMel) { m -> // mel 마다
                val fb = melFb[m] // 필터 가중치
                var e = 0.0 // 가중합
                for (k in fb.indices) e += fb[k] * power[k] // matmul(fb, power)
                ln(e + logGuard).toFloat() // log(x + guard) → Float
            }
        }
    }

    private companion object {
        /** 목적: JSON 숫자 배열 → DoubleArray. 입력: e(요소), name(오류 메시지용). 출력: 배열이 아니면 IllegalArgumentException. 리턴: DoubleArray. */
        fun doubles(e: JsonElement?, name: String): DoubleArray {
            val a = (e as? JsonArray) ?: throw IllegalArgumentException("설정 $name 이 배열이 아님") // 배열 확인
            return DoubleArray(a.size) { a[it].jsonPrimitive.content.toDouble() } // 원문 → double
        }
    }
}
