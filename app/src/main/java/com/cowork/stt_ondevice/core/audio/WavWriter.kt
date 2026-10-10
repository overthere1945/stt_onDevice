// 파일명: WavWriter.kt
// 목적: 16-bit mono PCM 을 표준 44바이트 헤더 WAV 파일로 기록
// 기능: RIFF/WAVE 헤더를 만들고 샘플을 리틀엔디언으로 쓴다.
package com.cowork.stt_ondevice.core.audio // 오디오 입출력 패키지

import java.io.File // 대상 파일
import java.nio.ByteBuffer // 바이트 버퍼
import java.nio.ByteOrder // 바이트 순서

// change(add)-hyungchul-2026-10-10-12:02
/**
 * 목적: WAV 파일 쓰기
 * 기능: 16-bit mono PCM WAV 를 만든다.
 */
object WavWriter {

    /**
     * 목적: PCM 을 WAV 파일로 저장
     * 기능: 44바이트 헤더 + 리틀엔디언 샘플을 한 번에 기록한다.
     * 입력: file(저장 경로), pcm(16-bit mono 샘플), sampleRate(Hz)
     * 출력: file 에 WAV 내용이 기록됨
     * 리턴: Unit
     */
    fun write(file: File, pcm: ShortArray, sampleRate: Int) {
        val dataLen = pcm.size * 2 // data 청크 바이트 수
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN) // 전체 출력 버퍼
        buf.put("RIFF".toByteArray(Charsets.US_ASCII)) // RIFF 식별자
        buf.putInt(36 + dataLen) // RIFF 이후 전체 크기
        buf.put("WAVE".toByteArray(Charsets.US_ASCII)) // WAVE 식별자
        buf.put("fmt ".toByteArray(Charsets.US_ASCII)) // fmt 청크 ID
        buf.putInt(16) // fmt 본문 크기
        buf.putShort(1) // 포맷 코드: PCM
        buf.putShort(1) // 채널 수: mono
        buf.putInt(sampleRate) // 샘플레이트
        buf.putInt(sampleRate * 2) // 바이트레이트(mono 16-bit)
        buf.putShort(2) // 블록 정렬
        buf.putShort(16) // 비트 수
        buf.put("data".toByteArray(Charsets.US_ASCII)) // data 청크 ID
        buf.putInt(dataLen) // data 크기
        for (s in pcm) buf.putShort(s) // 샘플 기록
        file.writeBytes(buf.array()) // 파일로 저장
    }
}
