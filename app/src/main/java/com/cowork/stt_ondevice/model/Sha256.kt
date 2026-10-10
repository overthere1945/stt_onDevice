// 파일명: Sha256.kt
// 목적: 파일의 SHA-256 해시 계산
// 기능: 파일을 64KB 씩 읽어 소문자 16진 64자 문자열로 돌려준다(큰 모델도 메모리에 통째로 올리지 않는다).
package com.cowork.stt_ondevice.model // 모델 패키지

import java.io.File // 파일
import java.security.MessageDigest // 해시 계산기

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: SHA-256 계산 도구
 * 기능: 스트리밍 방식으로 파일 해시를 계산한다.
 */
object Sha256 {
    private const val BUFFER_SIZE = 64 * 1024 // 읽기 버퍼 크기(64KB)

    /**
     * 목적: 파일 SHA-256 계산
     * 기능: 파일 전체를 순서대로 읽어 해시를 갱신한다.
     * 입력: file(대상 파일)
     * 출력: 파일을 읽을 수 없으면 IOException
     * 리턴: 소문자 16진 64자
     */
    fun of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256") // 해시 계산기
        val buf = ByteArray(BUFFER_SIZE) // 읽기 버퍼
        file.inputStream().use { input -> // 열고 자동 닫기
            while (true) { // 끝까지 반복
                val n = input.read(buf) // 읽은 바이트 수
                if (n < 0) break // 끝
                md.update(buf, 0, n) // 해시 갱신
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } // 소문자 16진 문자열
    }
}
