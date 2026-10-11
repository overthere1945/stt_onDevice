// 파일명: CsvFiles.kt
// 목적: 재개(resume) 시 결과 CSV 를 점검하는 읽기 도우미
// 기능: - truncatePartialLastLine: 크래시로 반쯤 쓰인 마지막 줄(CRLF 로 끝나지 않는 꼬리)을 잘라낸다.
//       - readRecords: ResultCsvWriter 가 쓴 CSV(UTF-8 BOM, RFC4180 따옴표, CRLF)를 헤더→값 맵 목록으로 읽는다.
//         change(add)-hyungchul-2026-10-11-03:59 completeOnly=true 면 줄바꿈으로 끝나지 않은 마지막 레코드(쓰는 중인 행)를 뺀다(결과 화면이 실행 중 run 을 셀 때).
//       - containsRow: 지정한 열 값이 모두 같은 행이 있는지 본다(행 중복 추가 방지).
//       - removeRows: 지정한 열 값이 모두 같은 행을 지우고 원자적으로 다시 쓴다(재시도·크래시 정리 시 중복/상충 행 방지).
//       - change(add)-hyungchul-2026-10-11-12:51 removeRowsWhere: 조건에 맞는 행을 한 번 읽고 한 번 써서 지운다(여러 항목의 행을 한꺼번에 지울 때 — CRASH_OR_KILLED 다시 실행).
//       change(add)-hyungchul-2026-10-11-18:14 onFullRead: 파일 전체를 읽을 때마다 불리는 테스트 전용 관찰 훅(운영 코드는 null) — 읽기 횟수를 시간 대신 세어 O(n²) 회귀를 막는다.
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.output.AtomicFile // 원자적 쓰기
import java.io.File // 파일
import java.io.RandomAccessFile // 파일 길이 자르기

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 결과 CSV 점검 도우미
 * 기능: 위 함수들을 제공한다. 행 추가는 ResultCsvWriter 가 담당한다.
 */
internal object CsvFiles {

    // change(add)-hyungchul-2026-10-11-18:14
    /**
     * 목적: 파일 전체 읽기 관찰 훅(테스트 전용)
     * 기능: readRecords·removeRowsWhere·truncatePartialLastLine 이 CSV 파일 전체를 읽을 때마다 호출된다. 운영 코드는 항상 null 로 둔다.
     *       항목 수와 상관없이 읽기 횟수가 일정한지를 시간 대신 횟수로 검증하는 데 쓴다.
     */
    @Volatile
    internal var onFullRead: ((File) -> Unit)? = null // 읽기 관찰 훅(기본 null)

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
        onFullRead?.invoke(file) // 전체 읽기 관찰(테스트 훅) // change(add)-hyungchul-2026-10-11-18:14
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

    // change(add)-hyungchul-2026-10-11-02:14: 레코드 분리와 필드 파싱을 나눠 removeRows 와 공유
    /**
     * 목적: 원문 레코드 분리
     * 기능: BOM 을 뗀 텍스트를 따옴표 밖의 CRLF(또는 LF) 기준으로 레코드 원문 목록으로 나눈다(따옴표 안 줄바꿈은 레코드 안에 남긴다). 빈 꼬리는 버린다.
     *       change(add)-hyungchul-2026-10-11-03:59 dropUnterminated 가 참이면 따옴표 밖 줄바꿈으로 끝나지 않은 마지막 레코드(따옴표가 열린 채 끝난 것 포함)를 버린다.
     * 입력: text(CSV 전체 텍스트, BOM 제거됨), dropUnterminated(change(add)-hyungchul-2026-10-11-03:59 끝나지 않은 꼬리 버리기, 기본 false)
     * 출력: 없음
     * 리턴: 레코드 원문 목록(줄바꿈 문자 제외)
     */
    private fun splitRawRecords(text: String, dropUnterminated: Boolean = false): List<String> {
        val out = ArrayList<String>() // 결과
        var inQuotes = false // 따옴표 안인지
        var start = 0 // 현재 레코드 시작
        var i = 0 // 위치
        while (i < text.length) { // 문자마다
            val c = text[i] // 현재 문자
            if (c == '"') inQuotes = !inQuotes // 따옴표 상태 전환("" 는 두 번 바뀌어 상태 유지)
            else if (c == '\n' && !inQuotes) { // 따옴표 밖 줄바꿈 = 레코드 끝
                val endIdx = if (i > start && text[i - 1] == '\r') i - 1 else i // CR 제외
                out.add(text.substring(start, endIdx)) // 레코드 원문
                start = i + 1 // 다음 레코드 시작
            }
            i++ // 다음 문자
        }
        if (start < text.length && !dropUnterminated) out.add(text.substring(start).removeSuffix("\r")) // 줄바꿈 없는 마지막 레코드(change(add)-hyungchul-2026-10-11-03:59 버리기 요청이면 제외)
        return out // 레코드 목록
    }

    /**
     * 목적: 레코드 1개의 필드 파싱
     * 기능: RFC4180 규칙(따옴표 안 쉼표·줄바꿈, "" → ")으로 필드를 나눈다.
     * 입력: raw(레코드 원문)
     * 출력: 없음
     * 리턴: 필드 목록
     */
    private fun parseFields(raw: String): List<String> {
        val fields = ArrayList<String>() // 필드 목록
        val cur = StringBuilder() // 현재 필드
        var inQuotes = false // 따옴표 안인지
        var i = 0 // 위치
        while (i < raw.length) { // 문자마다
            val c = raw[i] // 현재 문자
            if (inQuotes) { // 따옴표 안
                if (c == '"' && i + 1 < raw.length && raw[i + 1] == '"') { cur.append('"'); i++ } // "" → "
                else if (c == '"') inQuotes = false // 따옴표 닫힘
                else cur.append(c) // 일반 문자
            } else when (c) { // 따옴표 밖
                '"' -> inQuotes = true // 따옴표 열림
                ',' -> { fields.add(cur.toString()); cur.setLength(0) } // 필드 끝
                else -> cur.append(c) // 일반 문자
            }
            i++ // 다음 문자
        }
        fields.add(cur.toString()) // 마지막 필드
        return fields // 결과
    }

    /**
     * 목적: CSV 레코드 읽기
     * 기능: BOM 을 떼고 레코드로 나눈 뒤 첫 레코드를 헤더로 써서 헤더→값 맵을 만든다.
     * 입력: file(CSV 파일), completeOnly(change(add)-hyungchul-2026-10-11-03:59 참이면 줄바꿈으로 끝나지 않은 마지막 레코드 제외 — 실행 중 run 집계용, 기본 false)
     * 출력: 읽기 실패 시 IOException(change(add)-hyungchul-2026-10-11-03:59 정정: 원래도 readText 의 IOException 이 그대로 전달됨)
     * 리턴: 행(헤더 → 값) 목록. 파일이 없으면 빈 목록
     */
    fun readRecords(file: File, completeOnly: Boolean = false): List<Map<String, String>> {
        if (!file.isFile) return emptyList() // 파일 없음
        onFullRead?.invoke(file) // 전체 읽기 관찰(테스트 훅) // change(add)-hyungchul-2026-10-11-18:14
        val raws = splitRawRecords(file.readText(Charsets.UTF_8).removePrefix("\uFEFF"), completeOnly) // 레코드 원문(change(add)-hyungchul-2026-10-11-03:59 끝나지 않은 꼬리 처리)
        if (raws.isEmpty()) return emptyList() // 내용 없음
        val header = parseFields(raws.first()) // 헤더
        return raws.drop(1).map { raw -> // 데이터 레코드마다
            val r = parseFields(raw) // 필드
            header.indices.associate { header[it] to r.getOrElse(it) { "" } } // 헤더 → 값
        }
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /**
     * 목적: 일치하는 행 제거
     * 기능: match 의 모든 열 값이 같은 데이터 행을 빼고 BOM+나머지 레코드(CRLF)로 원자적으로 다시 쓴다. 지울 행이 없으면 파일을 건드리지 않는다.
     *       결과 쓰기 재시도·크래시 정리에서 같은 항목 행이 두 번(또는 다른 상태로) 남지 않게 할 때 쓴다.
     * 입력: file(CSV 파일), match(열 이름 → 기대 값)
     * 출력: 파일 재작성, 실패 시 IOException
     * 리턴: 지운 행 수
     */
    fun removeRows(file: File, match: Map<String, String>): Int =
        removeRowsWhere(file) { row -> match.all { (k, v) -> row[k] == v } } // 모든 열 일치 조건으로 제거 // change(add)-hyungchul-2026-10-11-12:51 공통 구현으로 위임

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 조건에 맞는 행 한꺼번에 제거
     * 기능: 파일을 한 번 읽어 drop(행 맵)이 참인 데이터 행을 빼고 BOM+나머지 레코드(CRLF)로 한 번 원자적으로 다시 쓴다. 지울 행이 없으면 파일을 건드리지 않는다.
     *       여러 항목의 행을 지울 때 항목마다 removeRows 를 부르면 파일 전체를 항목 수만큼 다시 읽게 되므로 이것을 쓴다.
     * 입력: file(CSV 파일), drop(헤더 → 값 맵을 받아 지울지 판단)
     * 출력: 파일 재작성, 실패 시 IOException
     * 리턴: 지운 행 수
     */
    fun removeRowsWhere(file: File, drop: (Map<String, String>) -> Boolean): Int {
        if (!file.isFile) return 0 // 파일 없음
        onFullRead?.invoke(file) // 전체 읽기 관찰(테스트 훅) // change(add)-hyungchul-2026-10-11-18:14
        val raws = splitRawRecords(file.readText(Charsets.UTF_8).removePrefix("\uFEFF")) // 레코드 원문
        if (raws.size < 2) return 0 // 데이터 행 없음
        val header = parseFields(raws.first()) // 헤더
        val keep = ArrayList<String>() // 남길 레코드
        keep.add(raws.first()) // 헤더는 유지
        var removed = 0 // 지운 수
        for (raw in raws.drop(1)) { // 데이터 레코드마다
            val f = parseFields(raw) // 필드
            val row = header.indices.associate { header[it] to f.getOrElse(it) { "" } } // 맵
            if (drop(row)) removed++ else keep.add(raw) // 조건에 맞으면 제거
        }
        if (removed == 0) return 0 // 변경 없음
        AtomicFile.write(file, ("\uFEFF" + keep.joinToString("") { it + "\r\n" }).toByteArray(Charsets.UTF_8)) // BOM + CRLF 레코드로 원자적 재작성
        return removed // 지운 수
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
