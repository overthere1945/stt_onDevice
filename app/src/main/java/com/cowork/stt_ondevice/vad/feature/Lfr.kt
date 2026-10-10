// 파일명: Lfr.kt
// 목적: FunASR 의 LFR(Low Frame Rate) 프레임 연결을 Kotlin 으로 포팅
// 기능: funasr_onnx WavFrontend.apply_lfr 와 같은 규칙으로 m 개 프레임을 이어 붙이고 n 프레임씩 건너뛴다.
//       앞쪽에 첫 프레임을 (m-1)/2 개 복제해 붙이고, 끝에서 m 개가 모자라면 마지막 프레임을 복제해 채운다.
//       FSMN 은 m=5, n=1(80 차원 → 400 차원, 프레임 수 그대로). FunASR 순서는 fbank → LFR → CMVN 이다.
package com.cowork.stt_ondevice.vad.feature // 특징 추출 패키지

// change(add)-hyungchul-2026-10-10-13:49
/**
 * 목적: LFR 적용기
 * 기능: 프레임 행렬을 LFR 행렬로 바꾼다(상태 없음).
 */
object Lfr {

    /**
     * 목적: LFR 적용(FunASR apply_lfr 포팅)
     * 기능: 출력 프레임 수 = ceil(T/n). 패딩 열 P = [첫 프레임 × (m-1)/2] + feats.
     *       i 번째 출력 = P[i·n .. i·n+m-1] 연결, 남은 프레임이 m 개보다 적으면 남은 것 + 마지막 프레임(P 의 끝) 복제.
     * 입력: feats(T × D 행렬), m(연결 프레임 수), n(건너뛰는 프레임 수)
     * 출력: 없음
     * 리턴: ceil(T/n) × (m·D) 행렬. 입력이 비어 있으면 빈 배열.
     */
    fun apply(feats: Array<FloatArray>, m: Int = 5, n: Int = 1): Array<FloatArray> {
        require(m >= 1 && n >= 1) { "LFR m, n 은 1 이상이어야 함: m=$m n=$n" } // 잘못된 인자 거부
        if (feats.isEmpty()) return emptyArray() // 프레임이 없으면 출력도 없음
        val dim = feats[0].size // 입력 차원
        val left = (m - 1) / 2 // 앞쪽 복제 패딩 수
        val total = feats.size + left // 패딩 후 길이
        val outFrames = (feats.size + n - 1) / n // ceil(T/n)
        return Array(outFrames) { i -> // 출력 프레임마다
            val row = FloatArray(m * dim) // 연결 결과
            for (j in 0 until m) { // 연결할 m 개 위치마다
                val p = minOf(i * n + j, total - 1) // 패딩 열 인덱스(끝을 넘으면 마지막 프레임 복제)
                val src = feats[maxOf(p - left, 0)] // 패딩 열 → 원래 프레임(앞쪽 패딩은 첫 프레임)
                System.arraycopy(src, 0, row, j * dim, dim) // j 번째 칸에 복사
            }
            row // 완성된 출력 프레임
        }
    }
}
