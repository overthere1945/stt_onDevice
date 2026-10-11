// 파일명: ResultCsvWriter.kt
// 목적: PC 평가기 호환 result.csv(및 요약 CSV) 작성
// 기능: 파일이 없으면 BOM+헤더를 쓰고, 행을 헤더 열 순서대로 RFC4180 따옴표 규칙으로 이어쓴다.
package com.cowork.stt_ondevice.output // 출력 패키지

import java.io.File // 파일
import java.io.FileOutputStream // 이어쓰기용 출력 스트림

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: CSV 작성기
 * 기능: 모든 행을 정확히 header.size 열로 쓴다. 없는 키/null 은 빈 칸(0·추정치로 채우지 않음). 헤더에 없는 키는 오타 방지를 위해 예외.
 * 입력: file(CSV 경로), header(열 이름 목록, 기본 STT_CSV_HEADER; 요약 CSV 는 STT_CSV_HEADER + "vad")
 */
class ResultCsvWriter(val file: File, val header: List<String> = STT_CSV_HEADER) {

    private val lock = Any() // 동시 append 보호용 잠금

    /**
     * 목적: CSV 필드 이스케이프
     * 기능: 쉼표·큰따옴표·줄바꿈이 있으면 큰따옴표로 감싸고 내부 큰따옴표는 두 개로 만든다(Python csv QUOTE_MINIMAL 과 동일).
     * 입력: v(필드 값, null 이면 빈 칸)
     * 출력: 없음
     * 리턴: 이스케이프된 필드 문자열
     */
    private fun escape(v: String?): String {
        if (v == null) return "" // null → 빈 칸
        val needQuote = v.any { it == ',' || it == '"' || it == '\n' || it == '\r' } // 따옴표 필요 여부
        return if (needQuote) "\"" + v.replace("\"", "\"\"") + "\"" else v // 필요 시 감싸기
    }

    /**
     * 목적: 한 행 이어쓰기
     * 기능: 파일이 없거나 비어 있으면 BOM(EF BB BF)+헤더를 먼저 쓰고, header 순서대로 값을 뽑아 한 줄(CRLF 종료)을 추가한다.
     * 입력: row(열 이름 → 값; 없는 키/null 은 빈 칸). 헤더에 없는 키가 있으면 IllegalArgumentException.
     * 출력: 파일에 행 추가
     * 리턴: 없음
     */
    fun append(row: Map<String, String?>) {
        val unknown = row.keys.filter { it !in header } // 헤더에 없는 키
        require(unknown.isEmpty()) { "CSV 헤더에 없는 열: $unknown" } // 조용히 버리지 않고 거부
        synchronized(lock) { // 한 번에 하나만 쓰기
            file.parentFile?.mkdirs() // 부모 폴더 생성
            val sb = StringBuilder() // 쓸 내용
            if (!file.exists() || file.length() == 0L) { // 새 파일이면
                sb.append('\uFEFF') // BOM (UTF-8 로 EF BB BF) // change(add)-hyungchul-2026-10-11-04:46 BOM 문자를 \uFEFF 이스케이프로 표기(lint ByteOrderMark)
                sb.append(header.joinToString(",") { escape(it) }).append("\r\n") // 헤더 줄
            }
            sb.append(header.joinToString(",") { escape(row[it]) }).append("\r\n") // 데이터 줄(항상 header.size 열)
            FileOutputStream(file, true).use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) } // 이어쓰기
        }
    }
}
