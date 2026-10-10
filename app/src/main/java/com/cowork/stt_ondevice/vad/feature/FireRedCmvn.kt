// 파일명: FireRedCmvn.kt
// 목적: FireRedVAD 의 CMVN(cmvn.ark, Kaldi 바이너리 double 통계 행렬) 읽기와 적용을 Kotlin 으로 포팅
// 기능: fireredvad/core/audio_feat.py `CMVN.read_kaldi_cmvn`(L52–70)·`CMVN.__call__`(L46–50)을 재현한다(tools/export/README_한국어.md §6.2).
//       파일 형식(kaldiio.load_mat 이 읽는 Kaldi 바이너리, 키 없음): 바이트 `\0B` → 토큰 "DM "(double 행렬) →
//       0x04 + int32 LE 행 수 → 0x04 + int32 LE 열 수 → 행×열 개 little-endian double.
//       통계 2 × (D+1): 0행 = 차원별 합 D 개 + 마지막 열 프레임 수(count), 1행 = 제곱합 D 개 + 0.
//       mean = sum/count, var = sumsq/count − mean²(하한 1e-20), istd = 1/sqrt(var) 를 모두 double 로 계산하고,
//       적용 (x − mean) × istd 도 double 로 계산한 뒤 Float 로 낸다(원본: float32 fbank − float64 mean → float64, .float()).
//       FSMN 의 Cmvn(am.mvn 텍스트, (x + shift) × scale)과 형식·식이 달라 별도 파일로 둔다.
package com.cowork.stt_ondevice.vad.feature // 특징 추출 패키지

import java.io.File // cmvn.ark 파일
import java.nio.ByteBuffer // 바이너리 해석
import java.nio.ByteOrder // little-endian
import kotlin.math.sqrt // 제곱근

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: FireRedVAD CMVN 적용기
 * 기능: 차원별 평균·역표준편차(double)로 특징을 정규화한다.
 * 입력: means(차원별 평균), inverseStd(차원별 1/표준편차) — 길이가 같아야 함
 * 출력: 길이가 다르면 IllegalArgumentException
 * 리턴: 해당 없음
 */
class FireRedCmvn(val means: DoubleArray, val inverseStd: DoubleArray) {

    init {
        require(means.size == inverseStd.size) { "CMVN mean(${means.size}) 와 istd(${inverseStd.size}) 길이가 다름" } // 길이 일치
    }

    /**
     * 목적: CMVN 적용(원본 CMVN.__call__)
     * 기능: 원본처럼 특징 차원이 CMVN 차원과 정확히 같아야 하며(assert x.shape[-1] == dim), 원소별 (x − mean) × istd 를 double 로
     *       계산해 Float 로 저장한다.
     * 입력: x(T × D 행렬, D = CMVN 차원)
     * 출력: 차원이 다르면 IllegalArgumentException
     * 리턴: T × D 정규화 행렬(새 배열)
     */
    fun apply(x: Array<FloatArray>): Array<FloatArray> = Array(x.size) { t -> // 프레임마다
        val row = x[t] // 입력 프레임
        require(row.size == means.size) { "특징 차원 ${row.size} ≠ CMVN 차원 ${means.size}" } // 원본 assert 와 같음
        FloatArray(row.size) { d -> ((row[d].toDouble() - means[d]) * inverseStd[d]).toFloat() } // (x − mean) × istd → float32
    }

    companion object {
        private const val VARIANCE_FLOOR = 1e-20 // 원본 floor
        private const val SIZE_PREFIX: Byte = 4 // Kaldi 바이너리 int32 앞의 크기 바이트

        /**
         * 목적: Kaldi 바이너리 double 행렬 cmvn.ark 읽기(kaldiio.load_mat + read_kaldi_cmvn 포팅)
         * 기능: 헤더 `\0B`, 토큰 "DM ", 크기 접두 0x04 + int32 LE 행·열을 확인하고 double 값을 읽어 mean·istd 를 계산한다.
         *       행 수 2, count ≥ 1, 파일 길이 정확 일치를 확인하며, 다른 형식(텍스트·float 행렬 "FM " 등)은 지원하지 않고 예외를 던진다.
         * 입력: file(cmvn.ark)
         * 출력: 파일 없음·형식 오류 시 IllegalArgumentException
         * 리턴: FireRedCmvn
         */
        fun load(file: File): FireRedCmvn {
            require(file.isFile) { "FireRed CMVN 파일 없음: ${file.absolutePath}" } // 조용한 fallback 금지
            val bytes = file.readBytes() // 전체 바이트
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN) // little-endian 해석기
            require(bytes.size >= 15) { "CMVN 파일이 너무 짧음(${bytes.size} 바이트): $file" } // 헤더 최소 길이(2 + 3 + 5 + 5)
            require(buf.get() == 0.toByte() && buf.get() == 'B'.code.toByte()) { "Kaldi 바이너리 헤더(\\0B) 아님: $file" } // 바이너리 표시
            val token = String(ByteArray(3).also { buf.get(it) }, Charsets.US_ASCII) // 행렬 형식 토큰
            require(token == "DM ") { "지원하지 않는 Kaldi 행렬 형식 '$token'(double 행렬 'DM ' 만): $file" } // double 행렬만
            val rows = readInt(buf, file) // 행 수
            val cols = readInt(buf, file) // 열 수
            require(rows == 2 && cols >= 2) { "CMVN 통계 형상 ${rows}×$cols 이 2×(D+1) 아님: $file" } // 원본 assert stats.shape[0] == 2
            require(bytes.size == buf.position() + rows * cols * 8) { "CMVN 파일 길이 ${bytes.size} ≠ 헤더 ${buf.position()} + ${rows}×${cols}×8: $file" } // 꼬리 바이트 없음
            val stats = Array(rows) { DoubleArray(cols) { buf.double } } // 행렬 값(행 우선)
            val dim = cols - 1 // 특징 차원
            val count = stats[0][dim] // 프레임 수
            require(count >= 1.0) { "CMVN count $count < 1: $file" } // 원본 assert count >= 1
            val means = DoubleArray(dim) { d -> stats[0][d] / count } // mean = sum / count
            val istd = DoubleArray(dim) { d -> // 차원마다
                var variance = stats[1][d] / count - means[d] * means[d] // var = sumsq/count − mean²
                if (variance < VARIANCE_FLOOR) variance = VARIANCE_FLOOR // 하한
                1.0 / sqrt(variance) // 역표준편차(math.sqrt 와 같은 IEEE 제곱근)
            }
            return FireRedCmvn(means, istd) // 결과
        }

        /**
         * 목적: Kaldi 바이너리 int32(크기 접두 0x04 + 4 바이트 LE) 읽기
         * 기능: 접두 바이트가 4 인지 확인하고 int32 를 읽는다.
         * 입력: buf(현재 위치), file(오류 메시지용)
         * 출력: 접두가 다르면 IllegalArgumentException
         * 리턴: 정수 값
         */
        private fun readInt(buf: ByteBuffer, file: File): Int {
            val size = buf.get() // 크기 접두
            require(size == SIZE_PREFIX) { "Kaldi int32 크기 접두 $size ≠ 4: $file" } // 형식 확인
            return buf.int // int32 LE
        }
    }
}
