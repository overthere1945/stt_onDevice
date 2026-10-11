// 파일명: TranscriptWriterTest.kt
// 목적: TranscriptWriter 의 PC 평가기 호환 txt 서식과 파일 쓰기 검증
// 기능: format 결과 문자열, 줄바꿈 치환, UTF-8(BOM 없음) 쓰기·부모 폴더 생성, ERROR 파일 내용을 확인한다.
package com.cowork.stt_ondevice.output // 출력 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Rule // JUnit 규칙 표시
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: 전사 txt 서식 회귀 방지
 * 기능: 문자열 서식과 실제 파일 바이트를 비교한다.
 */
class TranscriptWriterTest {

    @get:Rule // 테스트마다 새 임시 폴더 생성·삭제
    val tmp = TemporaryFolder()

    /**
     * 목적: PC 도구 헤더 서식과의 일치 확인
     * 기능: 엔진/VAD/조각 수 머리줄, 전체 텍스트, 구간별 목록이 정확한 문자열인지 비교한다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun formatMatchesPcHeaderStyle() {
        val actual = TranscriptWriter.format(
            "whispercpp small", "SILERO_SAFE", 2, "안녕 하세요",
            listOf(TimedText(0.42, 28.1, "안녕"), TimedText(29.05, 55.8, "하세요")),
        ) // 서식 생성
        val expected = "[전체 텍스트] (엔진: whispercpp small / VAD: SILERO_SAFE / 조각 2개)\n" +
            "안녕 하세요\n" +
            "\n" +
            "[구간별] start(sec), end(sec), text\n" +
            "0.42, 28.10, 안녕\n" +
            "29.05, 55.80, 하세요\n" // 기대 문자열
        assertEquals(expected, actual) // 비교
    }

    /**
     * 목적: 텍스트 내부 줄바꿈 치환 확인
     * 기능: 전체 텍스트·구간 텍스트의 \n, \r\n 이 공백으로 바뀌는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun newlinesInTextBecomeSpaces() {
        val actual = TranscriptWriter.format("e", "v", 1, "가\n나\r\n다", listOf(TimedText(1.0, 2.0, "라\n마"))) // 줄바꿈 포함 입력
        assertEquals("[전체 텍스트] (엔진: e / VAD: v / 조각 1개)\n가 나 다\n\n[구간별] start(sec), end(sec), text\n1.00, 2.00, 라 마\n", actual) // 공백 치환 확인
    }

    /**
     * 목적: 파일 쓰기 규칙 확인
     * 기능: 없는 부모 폴더가 만들어지고, BOM 없는 UTF-8 바이트로 저장되는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun writeCreatesParentsAndUsesUtf8WithoutBom() {
        val f = File(tmp.root, "a/b/01.txt") // 부모 폴더가 없는 경로
        TranscriptWriter.write(f, "한글") // 쓰기
        assertEquals("한글".toByteArray(Charsets.UTF_8).toList(), f.readBytes().toList()) // BOM 없이 UTF-8 바이트 그대로
    }

    /**
     * 목적: 오류 파일 내용 확인
     * 기능: stage/code/message 가 줄 단위로 기록되고 부모 폴더가 만들어지는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun writeErrorRecordsStageCodeMessage() {
        val f = File(tmp.root, "x/01.ERROR.txt") // 오류 파일 경로
        TranscriptWriter.writeError(f, "decode", "E_MODEL", "모델 로드 실패") // 오류 기록
        val text = f.readText(Charsets.UTF_8) // 읽기
        assertEquals("stage: decode\ncode: E_MODEL\nmessage: 모델 로드 실패\n", text) // 내용 비교
        assertTrue(f.readBytes().size >= 3 && f.readBytes()[0] != 0xEF.toByte()) // BOM 없음
    }
}
