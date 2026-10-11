// 파일명: ResultCsvWriterTest.kt
// 목적: STT_CSV_HEADER 와 ResultCsvWriter 의 PC 평가기 호환 CSV 출력 검증
// 기능: 헤더 열 수·순서, BOM·헤더 1회, RFC4180 따옴표, 빈 칸 처리, 사용자 정의 헤더(vad 열)를 확인한다.
package com.cowork.stt_ondevice.output // 출력 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Rule // JUnit 규칙 표시
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: CSV 출력 회귀 방지
 * 기능: 임시 폴더에 CSV 를 쓰고 바이트·텍스트를 검증한다.
 */
class ResultCsvWriterTest {

    @get:Rule // 테스트마다 새 임시 폴더 생성·삭제
    val tmp = TemporaryFolder()

    /**
     * 목적: PC 도구 헤더 정의 일치 확인
     * 기능: 열 수(PC stt_common.py 94-180행의 실제 열 60개), 첫/마지막 열, status<text 순서를 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun headerMatchesPcDefinition() {
        assertEquals(60, STT_CSV_HEADER.size) // PC 원본의 실제 열 수(brief 의 59 는 오기)
        assertEquals("run_id", STT_CSV_HEADER.first()) // 첫 열
        assertEquals("text", STT_CSV_HEADER.last()) // 마지막 열
        assertTrue(STT_CSV_HEADER.indexOf("status") in 0 until STT_CSV_HEADER.indexOf("text")) // status 가 text 앞
        assertEquals(STT_CSV_HEADER.size, STT_CSV_HEADER.toSet().size) // 중복 열 없음
    }

    /**
     * 목적: BOM·헤더 1회·따옴표 규칙 확인
     * 기능: 두 번 append 후 앞 3바이트 BOM, 헤더 한 번, 쉼표·따옴표·줄바꿈 이스케이프를 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun csvBomHeaderAndEscaping() {
        val f = File(tmp.root, "sub/result.csv") // 부모 폴더 없는 경로
        val w = ResultCsvWriter(f) // 기본 헤더 사용
        w.append(mapOf("run_id" to "r1", "text" to "a,\"b\"\nc")) // 첫 행
        w.append(mapOf("run_id" to "r2", "text" to "평범")) // 둘째 행
        val bytes = f.readBytes() // 전체 바이트
        assertEquals(listOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), bytes.take(3)) // BOM
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF") // BOM 제거한 본문 // change(add)-hyungchul-2026-10-11-04:46 BOM 문자를 \uFEFF 이스케이프로 표기(lint ByteOrderMark)
        assertEquals(1, text.split(STT_CSV_HEADER.joinToString(",")).size - 1) // 헤더 1회만
        assertTrue(text.contains("\"a,\"\"b\"\"\nc\"")) // 이스케이프된 text 필드
        assertTrue(text.contains("\r\nr2,")) // 둘째 행은 CRLF 뒤에 시작
    }

    /**
     * 목적: 모든 행이 헤더 열 수와 같은지 확인
     * 기능: 없는 키/null 이 빈 칸이 되어 열 수가 유지되는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun missingAndNullValuesBecomeEmptyCells() {
        val f = File(tmp.root, "result.csv") // 대상 파일
        ResultCsvWriter(f).append(mapOf("run_id" to "r1", "rtf" to null)) // null 포함 행
        val lines = String(f.readBytes(), Charsets.UTF_8).removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() } // 헤더+행 // change(add)-hyungchul-2026-10-11-04:46 BOM 문자를 \uFEFF 이스케이프로 표기(lint ByteOrderMark)
        assertEquals(2, lines.size) // 헤더 + 1행
        assertEquals(STT_CSV_HEADER.size - 1, lines[1].count { it == ',' }) // 쉼표 수 = 열 수 - 1
        assertTrue(lines[1].startsWith("r1,,")) // 첫 칸 값, 둘째 칸 빈 칸
    }

    /**
     * 목적: 기존 파일에 이어쓰기 확인
     * 기능: 새 writer 인스턴스가 이미 있는 파일에 헤더를 다시 쓰지 않는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun reopeningExistingFileDoesNotRewriteHeader() {
        val f = File(tmp.root, "result.csv") // 대상 파일
        ResultCsvWriter(f).append(mapOf("run_id" to "r1")) // 첫 인스턴스
        ResultCsvWriter(f).append(mapOf("run_id" to "r2")) // 둘째 인스턴스
        val text = String(f.readBytes(), Charsets.UTF_8) // 전체 텍스트
        assertEquals(1, Regex("run_id,started_at").findAll(text).count()) // 헤더 1회
    }

    /**
     * 목적: 사용자 정의 헤더(요약 CSV 용 vad 열) 지원 확인
     * 기능: 헤더 + "vad" 로 만든 writer 가 vad 키를 마지막 열에 쓰는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun customHeaderPutsVadInLastColumn() {
        val f = File(tmp.root, "all_result.csv") // 요약 CSV
        val header = STT_CSV_HEADER + "vad" // 열 하나 추가
        ResultCsvWriter(f, header).append(mapOf("run_id" to "r1", "vad" to "SILERO_SAFE")) // vad 키 행
        val lines = String(f.readBytes(), Charsets.UTF_8).removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() } // 헤더+행 // change(add)-hyungchul-2026-10-11-04:46 BOM 문자를 \uFEFF 이스케이프로 표기(lint ByteOrderMark)
        assertTrue(lines[0].endsWith(",text,vad")) // 헤더 마지막이 vad
        assertEquals(header.size - 1, lines[1].count { it == ',' }) // 행 열 수 = 헤더 열 수
        assertTrue(lines[1].endsWith(",SILERO_SAFE")) // vad 값이 마지막 열
    }

    /**
     * 목적: 헤더에 없는 키 거부 확인
     * 기능: 오타 키가 조용히 버려지지 않고 예외가 나는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun unknownKeyIsRejected() {
        val w = ResultCsvWriter(File(tmp.root, "result.csv")) // 기본 헤더
        try {
            w.append(mapOf("run_id" to "r1", "no_such_column" to "x")) // 알 수 없는 키
            fail("IllegalArgumentException 이 기대됨") // 예외가 안 났으면 실패
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no_such_column")) // 메시지에 키 이름 포함
        }
    }
}
