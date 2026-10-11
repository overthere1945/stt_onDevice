// 파일명: PcmConverter.kt
// 목적: 임의 포맷 WavData 를 VAD/STT 가 쓰는 16 kHz mono float 로 변환
// 기능: 채널 평균 다운믹스, 선형보간 리샘플, 8-bit unsigned 정규화, float→PCM16 변환
package com.cowork.stt_ondevice.core.audio // 오디오 입출력 패키지

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: 오디오 포맷 변환
 * 기능: WavData → AudioClip(16 kHz mono), float → PCM16.
 */
object PcmConverter {

    const val TARGET_RATE = 16000 // 목표 샘플레이트(Hz)

    /**
     * 목적: 16 kHz mono float 로 변환
     * 기능: 정규화(-1..1) → 채널 평균 다운믹스 → 선형보간 리샘플. 이미 16k mono 면 변환 없이 정규화만 한다.
     * 입력: wav(원본 WavData)
     * 출력: 없음
     * 리턴: AudioClip(변환 여부 converted 포함)
     */
    fun toMono16k(wav: WavData): AudioClip {
        val ch = wav.channels // 채널 수
        val frames = wav.pcm.size / ch // 프레임 수
        val mono = FloatArray(frames) // 다운믹스 결과
        for (f in 0 until frames) { // 프레임마다
            var sum = 0f // 채널 합
            for (c in 0 until ch) sum += normalize(wav.pcm[f * ch + c], wav.bitsPerSample) // 정규화 후 누적
            mono[f] = sum / ch // 채널 평균
        }
        val needsConvert = ch != 1 || wav.sampleRate != TARGET_RATE // 변환이 필요한지
        val out = if (wav.sampleRate == TARGET_RATE) mono else resample(mono, wav.sampleRate) // 리샘플
        return AudioClip(out, wav.sampleRate, ch, wav.bitsPerSample, needsConvert) // 결과 포장
    }

    /**
     * 목적: float 샘플을 16-bit PCM 으로 변환
     * 기능: x*32768 후 반올림하고 Short 범위로 자른다.
     * 입력: x(-1..1 float 샘플)
     * 출력: 없음
     * 리턴: PCM16 ShortArray
     */
    fun floatToPcm16(x: FloatArray): ShortArray {
        val out = ShortArray(x.size) // 결과 배열
        for (i in x.indices) { // 샘플마다
            val v = Math.round(x[i] * 32768f) // 스케일 후 반올림
            out[i] = v.coerceIn(-32768, 32767).toShort() // 범위 제한
        }
        return out // 변환 완료
    }

    /**
     * 목적: 샘플 하나를 -1..1 float 로 정규화
     * 기능: 8-bit 는 (b-128)/128, 16-bit 는 /32768.
     * 입력: v(원본 값; 8-bit 는 unsigned), bits(8 또는 16)
     * 출력: 없음
     * 리턴: 정규화된 float
     */
    private fun normalize(v: Short, bits: Int): Float =
        if (bits == 8) (v - 128) / 128f else v / 32768f // 비트 수별 정규화

    /**
     * 목적: 선형보간 리샘플링
     * 기능: 입력 샘플레이트에서 16 kHz 로 위치를 환산해 인접 두 샘플을 보간한다.
     * 입력: src(mono float), srcRate(원본 Hz)
     * 출력: 없음
     * 리턴: 16 kHz float 배열
     */
    private fun resample(src: FloatArray, srcRate: Int): FloatArray {
        if (src.isEmpty()) return FloatArray(0) // 빈 입력
        val outLen = (src.size.toLong() * TARGET_RATE / srcRate).toInt() // 출력 샘플 수(내림)
        val out = FloatArray(outLen) // 결과 배열
        val step = srcRate.toDouble() / TARGET_RATE // 출력 1샘플당 입력 이동량
        for (i in 0 until outLen) { // 출력 샘플마다
            val pos = i * step // 입력상 위치
            val i0 = pos.toInt() // 앞 샘플 인덱스
            val i1 = minOf(i0 + 1, src.size - 1) // 뒤 샘플 인덱스(끝 보호)
            val frac = (pos - i0).toFloat() // 보간 비율
            out[i] = src[i0] + (src[i1] - src[i0]) * frac // 선형보간
        }
        return out // 리샘플 완료
    }
}
