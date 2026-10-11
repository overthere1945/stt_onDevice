// 파일명: AtomicFile.kt
// 목적: 결과 파일(txt/ERROR.txt/detail JSON)을 원자적으로 저장하는 공용 도우미
// 기능: 같은 폴더의 임시 파일에 쓴 뒤 ATOMIC_MOVE 로 교체한다. 쓰는 도중 크래시해도 최종 경로에는 "완성된 파일"만 존재한다(재개 판정의 전제, R10).
package com.cowork.stt_ondevice.output // 출력 패키지

import java.io.File // 파일
import java.io.FileOutputStream // fsync 가능한 출력 스트림
import java.nio.file.Files // 파일 이동
import java.nio.file.StandardCopyOption // 이동 옵션

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 원자적 파일 쓰기
 * 기능: write() 하나만 제공한다.
 */
object AtomicFile {

    /**
     * 목적: 파일 원자적 저장
     * 기능: 부모 폴더를 만들고 "<이름>.tmp" 에 쓰고 fsync(디스크 반영) 한 뒤 ATOMIC_MOVE(+REPLACE_EXISTING)로 교체한다. 실패하면 임시 파일을 지우고 예외를 그대로 던진다.
     * 입력: file(최종 경로), bytes(내용)
     * 출력: file 생성/교체, 쓰기·이동 실패 시 IOException
     * 리턴: 없음
     */
    fun write(file: File, bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile!! // 부모 폴더
        dir.mkdirs() // 폴더 보장
        val tmp = File(dir, file.name + ".tmp") // 같은 폴더의 임시 파일(원자적 이동 조건)
        try {
            FileOutputStream(tmp).use { out -> // 임시 파일 열기 // change(add)-hyungchul-2026-10-11-02:14: 이동 전 fsync
                out.write(bytes) // 내용 기록
                out.fd.sync() // 디스크에 반영(정전·강제 종료 시 빈 파일이 남지 않게)
            }
            Files.move(tmp.toPath(), file.absoluteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) // 원자적 교체
        } finally {
            tmp.delete() // 실패 시 잔재 제거(성공 시엔 이미 없음)
        }
    }
}
