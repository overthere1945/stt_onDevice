// 파일명: WavReader.kt
// 목적: WAV(RIFF/WAVE) 파일·바이트를 WavData 로 파싱
// 기능: PCM(1) 및 EXTENSIBLE(0xFFFE)+PCM 서브포맷, 8/16-bit 지원, 임의 청크 건너뛰기, 잘린 data 보정
package com.cowork.stt_ondevice.core.audio // 오디오 입출력 패키지

import java.io.File // 입력 파일
import java.nio.ByteBuffer // 바이트 버퍼
import java.nio.ByteOrder // 바이트 순서

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: WAV 읽기
 * 기능: 청크를 순회하며 fmt 와 data 를 찾아 WavData 로 만든다.
 */
object WavReader {

    private const val FORMAT_PCM = 1 // 일반 PCM 포맷 코드
    private const val FORMAT_EXTENSIBLE = 0xFFFE // 확장 포맷 코드

    /**
     * 목적: 파일에서 WAV 읽기
     * 기능: 파일 전체를 바이트로 읽어 바이트 버전에 위임한다.
     * 입력: file(WAV 파일)
     * 출력: 형식 오류 시 WavFormatException
     * 리턴: 파싱된 WavData
     */
    fun read(file: File): WavData = read(file.readBytes()) // 바이트로 읽어 파싱

    /**
     * 목적: 바이트 배열에서 WAV 파싱
     * 기능: RIFF/WAVE 확인 후 청크를 순회, fmt 검증, data 를 실제 길이로 잘라 PCM 으로 변환한다.
     * 입력: bytes(WAV 파일 전체 바이트)
     * 출력: RIFF 아님/미지원 포맷/fmt·data 누락 시 WavFormatException
     * 리턴: 파싱된 WavData
     */
    fun read(bytes: ByteArray): WavData {
        if (bytes.size < 12) throw WavFormatException("WAV 헤더가 너무 짧음: ${bytes.size}바이트") // 최소 헤더 길이
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN) // 리틀엔디언 뷰
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") throw WavFormatException("RIFF/WAVE 형식이 아님") // 식별자 확인
        var pos = 12 // 첫 청크 위치
        var rate = 0 // 샘플레이트
        var channels = 0 // 채널 수
        var bits = 0 // 비트 수
        var haveFmt = false // fmt 를 읽었는지
        while (pos + 8 <= bytes.size) { // 청크 헤더(8바이트)가 남아 있는 동안
            val id = tag(bytes, pos) // 청크 ID
            val declared = buf.getInt(pos + 4).toLong() and 0xFFFFFFFFL // 선언된 크기(부호 없는 값)
            val bodyStart = pos + 8 // 본문 시작
            val avail = (bytes.size - bodyStart).toLong() // 실제 남은 바이트
            if (id == "fmt ") { // 포맷 청크
                if (declared < 16 || avail < 16) throw WavFormatException("fmt 청크가 너무 짧음") // 최소 16바이트
                val tagCode = buf.getShort(bodyStart).toInt() and 0xFFFF // 포맷 코드
                channels = buf.getShort(bodyStart + 2).toInt() and 0xFFFF // 채널 수
                rate = buf.getInt(bodyStart + 4) // 샘플레이트
                bits = buf.getShort(bodyStart + 14).toInt() and 0xFFFF // 비트 수
                val effective = if (tagCode == FORMAT_EXTENSIBLE) { // 확장 헤더면 서브포맷 확인
                    if (declared < 40 || avail < 40) throw WavFormatException("EXTENSIBLE fmt 청크가 너무 짧음") // 40바이트 필요
                    buf.getShort(bodyStart + 24).toInt() and 0xFFFF // GUID 첫 2바이트 = 실제 포맷 코드
                } else tagCode // 일반 헤더는 그대로
                if (effective != FORMAT_PCM) throw WavFormatException("PCM 이 아닌 포맷 코드: $effective") // float 등 거부
                if (bits != 8 && bits != 16) throw WavFormatException("지원하지 않는 비트 수: $bits") // 8/16-bit 만
                if (channels < 1) throw WavFormatException("채널 수가 올바르지 않음: $channels") // 채널 1 이상
                if (rate < 1) throw WavFormatException("샘플레이트가 올바르지 않음: $rate") // 샘플레이트 양수
                haveFmt = true // fmt 확보
            } else if (id == "data") { // 데이터 청크
                if (!haveFmt) throw WavFormatException("fmt 청크보다 data 청크가 먼저 나옴") // 순서 오류
                val bytesPerSample = bits / 8 // 샘플당 바이트
                val frameBytes = bytesPerSample * channels // 프레임당 바이트
                val len = minOf(declared, avail).toInt() // 선언이 더 길면 실제 길이로 자름
                val usable = len - len % frameBytes // 온전한 프레임 경계로 내림
                val count = usable / bytesPerSample // 샘플 수
                val pcm = ShortArray(count) // 결과 PCM
                if (bits == 16) { // 16-bit: 부호 있는 리틀엔디언
                    for (i in 0 until count) pcm[i] = buf.getShort(bodyStart + i * 2) // 샘플 읽기
                } else { // 8-bit: unsigned 값 그대로
                    for (i in 0 until count) pcm[i] = (bytes[bodyStart + i].toInt() and 0xFF).toShort() // 0..255
                }
                return WavData(rate, channels, bits, pcm) // data 를 찾으면 종료
            }
            val next = bodyStart.toLong() + declared + (declared and 1L) // 다음 청크 위치(짝수 패딩 포함)
            if (next > bytes.size) break // 범위를 벗어나면 중단
            pos = next.toInt() // 다음 청크로 이동
        }
        throw WavFormatException(if (haveFmt) "data 청크가 없음" else "fmt 청크가 없음") // 필수 청크 누락
    }

    /**
     * 목적: 4바이트 청크 ID 문자열 추출
     * 기능: 지정 위치부터 4바이트를 ASCII 로 해석한다.
     * 입력: bytes(원본), off(시작 위치)
     * 출력: 없음
     * 리턴: 4글자 문자열
     */
    private fun tag(bytes: ByteArray, off: Int): String = String(bytes, off, 4, Charsets.US_ASCII) // ASCII 변환
}
