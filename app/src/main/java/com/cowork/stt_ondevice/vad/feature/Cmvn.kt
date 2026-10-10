// 파일명: Cmvn.kt
// 목적: FunASR 의 CMVN(am.mvn) 적용을 Kotlin 으로 포팅
// 기능: Kaldi nnet 텍스트 형식 am.mvn 의 <AddShift>(=mean), <Rescale>(=var) 다음 줄 <LearnRateCoef> 값을 읽고,
//       funasr_onnx WavFrontend.apply_cmvn 과 같이 (x + shift) × scale 을 double 로 계산해 Float 로 낸다.
//       FunASR 순서는 fbank → LFR → CMVN 이므로 FSMN am.mvn 은 400 차원(LFR 결과)에 적용한다.
package com.cowork.stt_ondevice.vad.feature // 특징 추출 패키지

import java.io.File // am.mvn 파일

// change(add)-hyungchul-2026-10-10-13:49
/**
 * 목적: CMVN 적용기
 * 기능: 차원별 shift·scale 로 특징을 정규화한다.
 * 입력: shift(<AddShift> 값), scale(<Rescale> 값) — 길이가 같아야 함
 * 출력: 없음
 * 리턴: 해당 없음
 */
class Cmvn(val shift: FloatArray, val scale: FloatArray) {

    init {
        require(shift.size == scale.size) { "CMVN shift(${shift.size}) 와 scale(${scale.size}) 길이가 다름" } // 길이 일치 확인
    }

    /**
     * 목적: CMVN 적용
     * 기능: 프레임마다 원소별 (x + shift) × scale 을 double 로 계산해 Float 로 저장한다(FunASR 는 float64 계산 후 float32 변환).
     *       FunASR 처럼 입력 차원 D 만큼 앞쪽 shift·scale 을 쓰며, D 가 CMVN 차원보다 크면 예외.
     * 입력: x(T × D 행렬)
     * 출력: D 가 CMVN 차원보다 크면 IllegalArgumentException
     * 리턴: T × D 정규화 행렬(새 배열)
     */
    fun apply(x: Array<FloatArray>): Array<FloatArray> = Array(x.size) { t -> // 프레임마다
        val row = x[t] // 입력 프레임
        require(row.size <= shift.size) { "특징 차원 ${row.size} 가 CMVN 차원 ${shift.size} 보다 큼" } // 차원 확인
        FloatArray(row.size) { d -> ((row[d].toDouble() + shift[d].toDouble()) * scale[d].toDouble()).toFloat() } // (x + mean) × var
    }

    companion object {

        /**
         * 목적: Kaldi nnet am.mvn 텍스트 파싱(FunASR load_cmvn 포팅)
         * 기능: 줄 첫 토큰이 <AddShift> 이면 다음 줄, <Rescale> 이면 다음 줄이 <LearnRateCoef> 로 시작할 때
         *       토큰 [3 .. 끝-1](= "<LearnRateCoef> 0 [" 다음부터 "]" 앞까지)을 값으로 읽는다.
         *       둘 중 하나라도 없거나 비어 있으면 예외(조용히 넘어가지 않음).
         * 입력: file(am.mvn 파일)
         * 출력: 형식 오류 시 IllegalArgumentException
         * 리턴: Cmvn
         */
        fun loadAmMvn(file: File): Cmvn {
            val lines = file.readLines(Charsets.UTF_8) // 전체 줄
            var shift: FloatArray? = null // <AddShift> 값
            var scale: FloatArray? = null // <Rescale> 값
            for (i in lines.indices) { // 줄마다
                val head = lines[i].trim().split(WS).firstOrNull() ?: continue // 첫 토큰
                if (head != "<AddShift>" && head != "<Rescale>") continue // 관심 태그가 아니면 건너뜀
                val next = lines.getOrNull(i + 1)?.trim()?.split(WS) ?: continue // 다음 줄 토큰
                if (next.firstOrNull() != "<LearnRateCoef>") continue // FunASR 와 같이 <LearnRateCoef> 줄만 인정
                require(next.size >= 4) { "am.mvn $head 다음 줄 형식 오류(토큰 ${next.size}개): $file" } // "<LearnRateCoef> 0 [ ... ]" 최소 형태
                val values = next.subList(3, next.size - 1).map { it.toFloat() }.toFloatArray() // "[" 다음 ~ "]" 앞
                if (head == "<AddShift>") shift = values else scale = values // 태그별 저장
            }
            require(shift != null && shift.isNotEmpty()) { "am.mvn 에 <AddShift> 값이 없음: $file" } // mean 필수
            require(scale != null && scale.isNotEmpty()) { "am.mvn 에 <Rescale> 값이 없음: $file" } // var 필수
            return Cmvn(shift, scale) // 길이 검사는 생성자에서
        }

        private val WS = Regex("\\s+") // 공백 구분자
    }
}
