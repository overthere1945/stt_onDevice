// 파일명: PcmFrames.kt
// 목적: 시스템 엔진 공급용 PCM 변환 — float 샘플을 헤더 없는 PCM16 리틀엔디언 바이트로 바꾼다 (2차 작업 3)
// 기능: [-1, 1] 밖 값은 자르고 32767 을 곱해 16 비트 정수로 만든 뒤 리틀엔디언 2 바이트씩 쓴다. 공급 블록 크기 상수(100 ms = 3,200 B)를 둔다.
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: PCM16 변환 도구
 * 기능: toPcm16Le() 와 공급 블록 상수를 제공한다.
 */
object PcmFrames {
    /** 목적: 공급 블록 크기 / 기능: 16 kHz mono PCM16 의 100 ms = 1,600 샘플 × 2 바이트 */
    const val BLOCK_BYTES: Int = 3_200 // 100 ms 블록

    /** 목적: 공급 블록 길이 / 기능: 1× 에서 블록 사이 간격(ms) */
    const val BLOCK_MS: Long = 100 // 블록 길이(ms)

    /** 목적: 16 비트 최대값 / 기능: float 1.0 에 대응하는 정수 */
    private const val SCALE: Float = 32_767f // 배율

    /**
     * 목적: float → PCM16 LE 변환
     * 기능: 샘플마다 [-1, 1] 로 자르고 × 32767 후 소수점을 버려 리틀엔디언 2 바이트로 쓴다(NaN 은 0).
     * 입력: samples(16 kHz mono float 샘플)
     * 출력: 없음
     * 리턴: 길이 samples.size × 2 의 바이트 배열
     */
    fun toPcm16Le(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2) // 결과 버퍼
        for (i in samples.indices) { // 샘플마다
            val v = (samples[i].coerceIn(-1f, 1f) * SCALE).toInt() // 자르기 × 32767(NaN → 0)
            out[2 * i] = (v and 0xFF).toByte() // 하위 바이트
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte() // 상위 바이트
        }
        return out // 결과
    }
}
