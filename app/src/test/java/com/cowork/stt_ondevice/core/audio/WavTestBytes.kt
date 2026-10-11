// 파일명: WavTestBytes.kt
// 목적: 오디오 테스트에서 공통으로 쓰는 WAV 바이트 조립 헬퍼
// 기능: 샘플/청크/헤더 종류를 조절하며 테스트용 WAV 바이트 배열을 만든다.
package com.cowork.stt_ondevice.core.audio // 오디오 테스트 패키지

import java.nio.ByteBuffer // 바이트 버퍼
import java.nio.ByteOrder // 바이트 순서 지정

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: 테스트용 WAV 바이트 생성
 * 기능: RIFF/WAVE 헤더, 선택적 추가 청크, data 청크를 직접 조립한다.
 */
object WavTestBytes {

    /**
     * 목적: 리틀엔디언 청크(ID + 크기 + 본문) 하나를 만든다.
     * 기능: 본문 길이가 홀수면 패딩 1바이트를 덧붙인다.
     * 입력: id(4글자), body(본문), declaredSize(헤더에 적을 크기, null 이면 실제 길이)
     * 출력: 없음
     * 리턴: 청크 바이트 배열
     */
    fun chunk(id: String, body: ByteArray, declaredSize: Int? = null): ByteArray {
        val pad = body.size % 2 // 홀수 길이 패딩 필요 여부
        val buf = ByteBuffer.allocate(8 + body.size + pad).order(ByteOrder.LITTLE_ENDIAN) // 청크 버퍼
        buf.put(id.toByteArray(Charsets.US_ASCII)) // 청크 ID 기록
        buf.putInt(declaredSize ?: body.size) // 청크 크기 기록
        buf.put(body) // 본문 기록
        return buf.array() // 패딩은 0 으로 남는다
    }

    /**
     * 목적: PCM 샘플(Short)을 16-bit 리틀엔디언 바이트로 변환
     * 기능: 각 Short 를 2바이트로 풀어 쓴다.
     * 입력: samples(인터리브 샘플)
     * 출력: 없음
     * 리턴: 바이트 배열
     */
    fun pcm16(samples: ShortArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN) // 출력 버퍼
        for (s in samples) buf.putShort(s) // 샘플을 하나씩 기록
        return buf.array() // 완성된 바이트
    }

    /**
     * 목적: 완전한 WAV 파일 바이트 조립
     * 기능: fmt/data 청크와 앞뒤 추가 청크를 이어 붙이고 RIFF 헤더를 씌운다.
     * 입력: rate, ch, bits, data(원시 PCM 바이트), chunksBeforeFmt/chunksBetween/chunksAfterData(추가 청크),
     *       extensible(true 면 0xFFFE + PCM 서브포맷), formatTag(일반 헤더 포맷 코드), dataDeclaredSize(data 크기 필드 덮어쓰기)
     * 출력: 없음
     * 리턴: WAV 바이트 배열
     */
    fun wavBytes(
        rate: Int,
        ch: Int,
        bits: Int,
        data: ByteArray,
        chunksBeforeFmt: List<ByteArray> = emptyList(),
        chunksBetween: List<ByteArray> = emptyList(),
        chunksAfterData: List<ByteArray> = emptyList(),
        extensible: Boolean = false,
        formatTag: Int = 1,
        dataDeclaredSize: Int? = null,
    ): ByteArray {
        val blockAlign = ch * bits / 8 // 프레임당 바이트
        val fmtLen = if (extensible) 40 else 16 // fmt 본문 길이
        val fmt = ByteBuffer.allocate(fmtLen).order(ByteOrder.LITTLE_ENDIAN) // fmt 본문 버퍼
        fmt.putShort((if (extensible) 0xFFFE else formatTag).toShort()) // 포맷 코드
        fmt.putShort(ch.toShort()) // 채널 수
        fmt.putInt(rate) // 샘플레이트
        fmt.putInt(rate * blockAlign) // 바이트레이트
        fmt.putShort(blockAlign.toShort()) // 블록 정렬
        fmt.putShort(bits.toShort()) // 비트 수
        if (extensible) { // 확장 헤더 필드
            fmt.putShort(22) // cbSize
            fmt.putShort(bits.toShort()) // validBitsPerSample
            fmt.putInt(0x3) // 채널 마스크
            fmt.putShort(1) // 서브포맷 GUID 앞 2바이트: PCM(1)
            fmt.put(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71)) // GUID 나머지 14바이트
        }
        val body = ArrayList<ByteArray>() // WAVE 본문 청크 모음
        body.addAll(chunksBeforeFmt) // fmt 앞 청크
        body.add(chunk("fmt ", fmt.array())) // fmt 청크
        body.addAll(chunksBetween) // fmt 와 data 사이 청크
        body.add(chunk("data", data, dataDeclaredSize)) // data 청크
        body.addAll(chunksAfterData) // data 뒤 청크
        val total = 4 + body.sumOf { it.size } // "WAVE" + 청크들
        val out = ByteBuffer.allocate(8 + total).order(ByteOrder.LITTLE_ENDIAN) // 전체 버퍼
        out.put("RIFF".toByteArray(Charsets.US_ASCII)) // RIFF 식별자
        out.putInt(total) // RIFF 크기
        out.put("WAVE".toByteArray(Charsets.US_ASCII)) // WAVE 식별자
        for (c in body) out.put(c) // 청크들을 순서대로 기록
        return out.array() // 조립 완료
    }
}
