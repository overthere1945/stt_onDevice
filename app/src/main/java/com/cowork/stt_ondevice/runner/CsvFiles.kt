// 파일명: CsvFiles.kt
// 목적: 재개(resume) 시 결과 CSV 를 점검하는 읽기 도우미
// 기능: - truncatePartialLastLine: 크래시로 반쯤 쓰인 마지막 줄(CRLF 로 끝나지 않는 꼬리)을 잘라낸다.
//       - readRecords: ResultCsvWriter 가 쓴 CSV(UTF-8 BOM, RFC4180 따옴표, CRLF)를 헤더→값 맵 목록으로 읽는다.
//       - containsRow: 지정한 열 값이 모두 같은 행이 있는지 본다(행 중복 추가 방지).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import java.io.File // 파일
import java.io.RandomAccessFile // 파일 길이 자르기

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 결과 CSV 점검 도우미
 * 기능: 위 세 함수를 제공한다. 쓰기는 ResultCsvWriter 가 담당한다.
 */
internal object CsvFiles {

    /**
     * 목적: 반쯤 쓴 마지막 줄 제거
     * 기능: 파일이 CRLF 로 끝나지 않으면 마지막 CRLF 바로 뒤까지만 남긴다. CRLF 가 하나도 없으면(헤더조차 미완성) 파일을 지운다
     *       (다음 append 때 ResultCsvWriter 가 BOM+헤더를 다시 쓴다). 파일이 없거나 비었으면 아무 것도 하지 않는다.
     * 입력: file(CSV 파일)
     * 출력: 파일 길이 변경 또는 삭제
     * 리턴: 잘라냈으면 true
     */
    fun truncatePartialLastLine(file: File): Boolean {
        if (!file.isFile || file.length() == 0L) return false // 점검 대상 없음
        val bytes = file.readBytes() // 전체 바이트
        val n = bytes.size // 길이
        if (n >= 2 && bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()) return false // 정상 종료
        var keep = -1 // 남길 길이
        for (i in n - 2 downTo 0) { // 뒤에서부터 CRLF 탐색
            if (bytes[i] == '\r'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) { keep = i + 2; break } // 마지막 CRLF 뒤
        }
        if (keep < 0) return file.delete() // 헤더도 미완성 → 파일 제거
        RandomAccessFile(file, "rw").use { it.setLength(keep.toLong()) } // 찢긴 꼬리 제거
        return true // 잘라냄
    }

    /**
     * 목적: CSV 레코드 읽기
     * 기능: BOM 을 떼고 RFC4180 규칙(따옴표 안의 쉼표·줄바꿈·"" 이스케이프)으로 레코드를 나눈 뒤 첫 레코드를 헤더로 쓴다.
     * 입력: file(CSV 파일)
     * 출력: 없음
     * 리턴: 행(헤더 → 값) 목록. 파일이 없으면 빈 목록
     */
    fun readRecords(file: File): List<Map<String, String>> {
        if (!file.isFile) return emptyList() // 파일 없음
        val text = file.readText(Charsets.UTF_8).removePrefix("﻿") // BOM 제거
        val records = ArrayList<List<String>>() // 레코드 목록
        var fields = ArrayList<String>() // 현재 레코드의 필드
        val cur = StringBuilder() // 현재 필드
        var inQuotes = false // 따옴표 안인지
        var i = 0 // 위치
        while (i < text.length) { // 문자마다
            val c = text[i] // 현재 문자
            if (inQuotes) { // 따옴표 안
                if (c == '"' && i + 1 < text.length && text[i + 1] == '"') { cur.append('"'); i++ } // "" → "
                else if (c == '"') inQuotes = false // 따옴표 닫힘
                else cur.append(c) // 일반 문자
            } else when (c) { // 따옴표 밖
                '"' -> inQuotes = true // 따옴표 열림
                ',' -> { fields.add(cur.toString()); cur.setLength(0) } // 필드 끝
                '\r' -> {} // CR 은 무시(CRLF 의 일부)
                '\n' -> { fields.add(cur.toString()); cur.setLength(0); records.add(fields); fields = ArrayList() } // 레코드 끝
                else -> cur.append(c) // 일반 문자
            }
            i++ // 다음 문자
        }
        if (cur.isNotEmpty() || fields.isNotEmpty()) { fields.add(cur.toString()); records.add(fields) } // 마지막 줄(줄바꿈 없음)
        if (records.isEmpty()) return emptyList() // 내용 없음
        val header = records.first() // 헤더
        return records.drop(1).map { r -> header.indices.associate { header[it] to r.getOrElse(it) { "" } } } // 헤더 → 값 맵
    }

    /**
     * 목적: 일치하는 행 존재 여부
     * 기능: match 의 모든 열 값이 같은 행이 하나라도 있으면 true.
     * 입력: file(CSV 파일), match(열 이름 → 기대 값)
     * 출력: 없음
     * 리턴: 존재하면 true
     */
    fun containsRow(file: File, match: Map<String, String>): Boolean =
        readRecords(file).any { row -> match.all { (k, v) -> row[k] == v } } // 모든 열 일치
}
