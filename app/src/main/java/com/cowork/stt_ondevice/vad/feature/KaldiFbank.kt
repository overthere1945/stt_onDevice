// 파일명: KaldiFbank.kt
// 목적: FSMN-VAD(이후 FireRedVAD) ONNX 입력용 Kaldi 호환 log mel filterbank(fbank) 계산
// 기능: kaldi-native-fbank 1.22.3 OnlineFbank 를 FunASR WavFrontend 설정으로 실행한 것과 같은 결과를 낸다.
//       입력 ×32768 → snip_edges 프레임 분할(25 ms / 10 ms) → DC 제거 → 프리엠퍼시스 0.97 → 해밍 창
//       → 512 점 FFT(0 패딩) → 파워 스펙트럼 → kaldi mel 필터뱅크(20 Hz..나이퀴스트, 80 개) → log(max(값, float eps)).
//       dither 0, use_energy false(에너지 열 없음), htk_mode false, is_librosa false.
//       change(add)-hyungchul-2026-10-10-15:04 창 함수 선택(windowType) 추가: 기본 HAMMING(FSMN, 기존 동작 그대로),
//       POVEY 는 FireRedVAD(fireredvad/core/audio_feat.py KaldifeatFbank = knf 기본 window_type "povey") 용이다.
//       kaldi-native-fbank 는 신호 처리를 float 로 하지만 여기서는 FFT·누적을 double 로 하고(오차가 더 작음),
//       창·프리엠퍼시스 계수·mel 가중치는 kaldi 와 같은 float 값을 쓴 뒤 결과를 Float 로 낸다.
package com.cowork.stt_ondevice.vad.feature // 특징 추출 패키지

import kotlin.math.PI // 원주율
import kotlin.math.cos // 코사인
import kotlin.math.ln // 자연로그
import kotlin.math.max // 최댓값
import kotlin.math.sin // 사인

// change(add)-hyungchul-2026-10-10-13:49
/**
 * 목적: Kaldi 호환 fbank 계산기
 * 기능: 생성 시 창 함수·FFT 표·mel 필터뱅크를 한 번 만들고, compute 로 파형 전체의 fbank 행렬을 만든다.
 * 입력: numMel(mel 필터 수), frameLengthMs(창 길이 ms), frameShiftMs(이동 ms), sampleRate(Hz),
 *       windowType(창 함수, 기본 HAMMING — change(add)-hyungchul-2026-10-10-15:04)
 * 출력: 없음
 * 리턴: 해당 없음
 */
class KaldiFbank(
    val numMel: Int = 80, // mel 필터 수
    val frameLengthMs: Int = 25, // 창 길이(ms)
    val frameShiftMs: Int = 10, // 프레임 이동(ms)
    val sampleRate: Int = 16000, // 샘플레이트(Hz)
    val windowType: KaldiWindowType = KaldiWindowType.HAMMING, // change(add)-hyungchul-2026-10-10-15:04 창 함수(기본 해밍 = FSMN)
) {

    init {
        require(numMel > 0 && sampleRate * frameLengthMs / 1000 > 0 && sampleRate * frameShiftMs / 1000 > 0) { "fbank 설정이 올바르지 않음" } // 잘못된 설정 거부(표 생성 전)
    }

    private val frameLength = sampleRate * frameLengthMs / 1000 // 창 샘플 수(400)
    private val frameShift = sampleRate * frameShiftMs / 1000 // 이동 샘플 수(160)
    private val fftSize = nextPowerOfTwo(frameLength) // round_to_power_of_two: FFT 크기(512)
    // change(add)-hyungchul-2026-10-10-15:04 창 함수 선택(기존: hammingWindow 고정)
    private val window = when (windowType) { // 창 계수
        KaldiWindowType.HAMMING -> hammingWindow(frameLength) // 해밍(FSMN)
        KaldiWindowType.POVEY -> poveyWindow(frameLength) // povey(FireRedVAD)
    }
    private val fft = RealPowerFft(fftSize) // 파워 스펙트럼 계산기
    private val melBins = kaldiMelBins() // mel 필터(시작 FFT 빈, 가중치)

    /**
     * 목적: 파형 전체의 fbank 계산
     * 기능: 입력(-1..1)을 ×32768 해 int16 스케일로 만들고 snip_edges 규칙(T = 1 + (N - 창)/이동, N < 창 이면 0)으로
     *       프레임을 나눠 프레임마다 log mel 에너지를 계산한다.
     * 입력: samples(16 kHz mono float 파형, -1..1)
     * 출력: 없음
     * 리턴: T × numMel 행렬(행 = 프레임)
     */
    fun compute(samples: FloatArray): Array<FloatArray> {
        val numFrames = if (samples.size < frameLength) 0 else 1 + (samples.size - frameLength) / frameShift // snip_edges 프레임 수
        val frame = DoubleArray(fftSize) // 프레임 작업 버퍼(0 패딩 포함)
        val power = DoubleArray(fftSize / 2 + 1) // 파워 스펙트럼(0..나이퀴스트)
        return Array(numFrames) { t -> // 프레임마다
            val start = t * frameShift // 프레임 시작 샘플
            for (i in 0 until frameLength) frame[i] = (samples[start + i] * 32768f).toDouble() // int16 스케일(정확한 정수값)
            for (i in frameLength until fftSize) frame[i] = 0.0 // 나머지 0 패딩
            processWindow(frame) // DC 제거·프리엠퍼시스·창
            fft.powerSpectrum(frame, power) // 파워 스펙트럼
            melLog(power) // mel 에너지 → log
        }
    }

    /**
     * 목적: kaldi ProcessWindow(dither 0) 재현
     * 기능: 창 길이 구간에서 평균(DC)을 빼고, 뒤에서부터 x[i] -= 0.97·x[i-1], x[0] -= 0.97·x[0] 프리엠퍼시스 후 창을 곱한다.
     * 입력: frame(앞 frameLength 개가 신호인 작업 버퍼)
     * 출력: frame 을 제자리에서 바꿈
     * 리턴: 없음
     */
    private fun processWindow(frame: DoubleArray) {
        var sum = 0.0 // 합
        for (i in 0 until frameLength) sum += frame[i] // 신호 합
        val mean = sum / frameLength // DC(평균)
        for (i in 0 until frameLength) frame[i] -= mean // remove_dc_offset
        for (i in frameLength - 1 downTo 1) frame[i] -= PREEMPH * frame[i - 1] // 프리엠퍼시스(뒤에서부터)
        frame[0] -= PREEMPH * frame[0] // 첫 샘플은 자기 자신 기준(kaldi 규칙)
        for (i in 0 until frameLength) frame[i] *= window[i] // 창 적용(해밍 또는 povey) change(add)-hyungchul-2026-10-10-15:04 주석 수정
    }

    /**
     * 목적: mel 필터뱅크 적용 후 log
     * 기능: 필터마다 가중합을 구하고 max(값, float epsilon) 의 자연로그를 Float 로 돌려준다(kaldi use_log_fbank).
     * 입력: power(파워 스펙트럼)
     * 출력: 없음
     * 리턴: numMel 길이 FloatArray
     */
    private fun melLog(power: DoubleArray): FloatArray = FloatArray(numMel) { m -> // mel 필터마다
        val bin = melBins[m] // 이 필터의 시작 빈·가중치
        var energy = 0.0 // 가중합
        for (k in bin.weights.indices) energy += bin.weights[k] * power[bin.offset + k] // 가중 누적
        ln(max(energy, FLOAT_EPS)).toFloat() // log(max(eps)) → Float
    }

    /**
     * 목적: kaldi MelBanks(htk_mode false, vtln 없음) 가중치 생성
     * 기능: low 20 Hz, high = 나이퀴스트(high_freq 0) 사이를 mel 척도로 numMel+1 등분해 삼각 필터를 만든다.
     *       FFT 빈 i(0..fftSize/2-1, 나이퀴스트 빈 제외)의 주파수 = (sampleRate/fftSize)·i, mel = 1127·ln(1 + f/700).
     *       left < mel < right 인 빈만 가중치를 가진다. kaldi-native-fbank 와 같게 float 연산으로 계산한다.
     * 입력: 없음(클래스 설정)
     * 출력: 없음
     * 리턴: 필터별 MelBin 목록
     */
    private fun kaldiMelBins(): List<MelBin> {
        val numFftBins = fftSize / 2 // 사용하는 FFT 빈 수(256)
        val fftBinWidth = sampleRate.toFloat() / fftSize // 빈 간격 Hz(31.25)
        val melLow = melScale(LOW_FREQ) // 하한 mel
        val melHigh = melScale(0.5f * sampleRate) // 상한 mel(나이퀴스트)
        val melDelta = (melHigh - melLow) / (numMel + 1) // mel 간격
        return List(numMel) { b -> // 필터마다
            val left = melLow + b * melDelta // 왼쪽 끝 mel
            val center = melLow + (b + 1) * melDelta // 중심 mel
            val right = melLow + (b + 2) * melDelta // 오른쪽 끝 mel
            var first = -1 // 첫 비영 빈
            var last = -1 // 마지막 비영 빈
            val weights = FloatArray(numFftBins) // 전체 빈 가중치(임시)
            for (i in 0 until numFftBins) { // FFT 빈마다
                val mel = melScale(fftBinWidth * i) // 빈 주파수의 mel
                if (mel > left && mel < right) { // 삼각형 안
                    weights[i] = if (mel <= center) (mel - left) / (center - left) else (right - mel) / (right - center) // 상승/하강 변
                    if (first == -1) first = i // 첫 빈 기록
                    last = i // 마지막 빈 갱신
                }
            }
            require(first >= 0) { "빈 mel 필터 $b (FFT 해상도 부족)" } // kaldi 도 빈 필터는 오류
            MelBin(first, DoubleArray(last - first + 1) { weights[first + it].toDouble() }) // 비영 구간만 보관
        }
    }

    /**
     * 목적: mel 필터 1개(희소 표현)
     * 기능: 시작 FFT 빈 번호와 그 뒤 연속 가중치를 담는다.
     */
    private class MelBin(val offset: Int, val weights: DoubleArray)

    /**
     * 목적: 실수 입력 FFT 의 파워 스펙트럼(작은 radix-2 구현)
     * 기능: 비트 반전 + 반복 버터플라이로 복소 FFT 를 계산하고 |X[k]|² (k = 0..n/2)을 낸다.
     * 입력: n(2 의 거듭제곱 FFT 크기)
     * 출력: 없음
     * 리턴: 해당 없음
     * change(add)-hyungchul-2026-10-10-15:04 private → internal: NemoMelSpectrogram(MarbleNet)이 같은 FFT 를 재사용한다.
     *       작업 버퍼(re, im)를 인스턴스가 재사용하므로 스레드 안전하지 않다(인스턴스를 스레드 간에 공유하지 말 것).
     */
    internal class RealPowerFft(private val n: Int) {
        private val re = DoubleArray(n) // 실수부 작업 버퍼
        private val im = DoubleArray(n) // 허수부 작업 버퍼
        private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) } // 회전 인자 cos
        private val sinTable = DoubleArray(n / 2) { -sin(2.0 * PI * it / n) } // 회전 인자 -sin(정방향 FFT)
        private val bitRev = IntArray(n).also { r -> // 비트 반전 인덱스
            val bits = Integer.numberOfTrailingZeros(n) // log2(n)
            for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits) // 하위 bits 비트 반전
        }

        /**
         * 목적: 파워 스펙트럼 계산
         * 기능: x 를 비트 반전 순서로 복사해 반복 radix-2 FFT 후 re²+im² 를 0..n/2 에 쓴다.
         * 입력: x(길이 n 실수 신호), out(길이 n/2+1 결과 버퍼)
         * 출력: out 을 채움
         * 리턴: 없음
         */
        fun powerSpectrum(x: DoubleArray, out: DoubleArray) {
            for (i in 0 until n) { re[bitRev[i]] = x[i]; im[bitRev[i]] = 0.0 } // 비트 반전 배치(허수 0)
            var size = 2 // 버터플라이 크기
            while (size <= n) { // 단계마다
                val half = size / 2 // 절반 크기
                val step = n / size // 회전 인자 표 간격
                var start = 0 // 블록 시작
                while (start < n) { // 블록마다
                    for (j in 0 until half) { // 블록 안 쌍마다
                        val wr = cosTable[j * step] // 회전 인자 실수부
                        val wi = sinTable[j * step] // 회전 인자 허수부
                        val a = start + j // 위쪽 인덱스
                        val b = a + half // 아래쪽 인덱스
                        val tr = re[b] * wr - im[b] * wi // 아래쪽 × 회전 인자(실수)
                        val ti = re[b] * wi + im[b] * wr // 아래쪽 × 회전 인자(허수)
                        re[b] = re[a] - tr; im[b] = im[a] - ti // 차
                        re[a] += tr; im[a] += ti // 합
                    }
                    start += size // 다음 블록
                }
                size *= 2 // 다음 단계
            }
            for (k in 0..n / 2) out[k] = re[k] * re[k] + im[k] * im[k] // |X[k]|²
        }
    }

    companion object {
        private val PREEMPH = 0.97f.toDouble() // kaldi preemph_coeff(float 0.97 값 그대로)
        private const val LOW_FREQ = 20f // mel 하한 Hz
        private val FLOAT_EPS = Math.ulp(1.0f).toDouble() // numeric_limits<float>::epsilon (2^-23)

        /**
         * 목적: kaldi MelScale(htk 식) — float 연산
         * 기능: 1127 · ln(1 + f/700) 을 kaldi-native-fbank 와 같이 float 로 계산한다.
         * 입력: freq(Hz)
         * 출력: 없음
         * 리턴: mel 값(Float)
         */
        private fun melScale(freq: Float): Float = 1127.0f * ln(1.0f + freq / 700.0f) // float 연산

        /**
         * 목적: kaldi 해밍 창
         * 기능: w[i] = 0.54 − 0.46·cos(2π·i/(N−1)) 를 double 로 계산해 kaldi 처럼 float 로 저장한 값을 쓴다.
         * 입력: n(창 길이)
         * 출력: 없음
         * 리턴: 창 계수 DoubleArray
         */
        private fun hammingWindow(n: Int): DoubleArray {
            val a = 2.0 * PI / (n - 1) // 각도 간격
            return DoubleArray(n) { (0.54 - 0.46 * cos(a * it)).toFloat().toDouble() } // float 저장값과 같게
        }

        // change(add)-hyungchul-2026-10-10-15:04
        /**
         * 목적: kaldi povey 창(kaldi-native-fbank feature-window.cc window_type "povey")
         * 기능: w[i] = (0.5 − 0.5·cos(2π·i/(N−1)))^0.85 를 double 로 계산해 kaldi 처럼 float 로 저장한 값을 쓴다.
         * 입력: n(창 길이)
         * 출력: 없음
         * 리턴: 창 계수 DoubleArray
         */
        private fun poveyWindow(n: Int): DoubleArray {
            val a = 2.0 * PI / (n - 1) // 각도 간격
            return DoubleArray(n) { Math.pow(0.5 - 0.5 * cos(a * it), 0.85).toFloat().toDouble() } // float 저장값과 같게
        }

        /**
         * 목적: 2 의 거듭제곱 올림(kaldi RoundUpToNearestPowerOfTwo)
         * 기능: n 이상인 가장 작은 2 의 거듭제곱을 구한다.
         * 입력: n(양수)
         * 출력: 없음
         * 리턴: 2 의 거듭제곱
         */
        private fun nextPowerOfTwo(n: Int): Int {
            var p = 1 // 후보
            while (p < n) p *= 2 // n 이상이 될 때까지 2 배
            return p // 결과
        }
    }
}

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: KaldiFbank 창 함수 종류
 * 기능: kaldi window_type 중 이 앱이 쓰는 두 가지만 둔다(다른 값은 지원하지 않음).
 */
enum class KaldiWindowType {
    HAMMING, // 0.54 − 0.46·cos(2πi/(N−1)) — FunASR FSMN-VAD
    POVEY, // (0.5 − 0.5·cos(2πi/(N−1)))^0.85 — FireRedVAD(knf 기본값)
}
