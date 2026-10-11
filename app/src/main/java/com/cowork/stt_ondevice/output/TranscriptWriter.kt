// 파일명: TranscriptWriter.kt
// 목적: PC 평가기가 읽는 전사 txt 의 서식 생성과 파일 쓰기
// 기능: "[전체 텍스트] ... [구간별] ..." 형식 문자열 생성, UTF-8(BOM 없음) 원자적 저장, 오류 파일(ERROR.txt) 저장.
package com.cowork.stt_ondevice.output // 출력 패키지

import java.io.File // 파일
import java.util.Locale // 숫자 서식 로케일

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: 시간 구간이 붙은 전사 조각
 * 기능: 시작/끝 시각(초)과 텍스트를 보관한다.
 */
data class TimedText(
    val startSec: Double, // 시작 시각(초)
    val endSec: Double, // 끝 시각(초)
    val text: String, // 구간 텍스트
)

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: 전사 txt 작성기
 * 기능: PC 도구 서식과 같은 문자열을 만들고 파일로 저장한다.
 */
object TranscriptWriter {

    private val NEWLINES = Regex("\r\n|\r|\n") // 줄바꿈 패턴(CRLF 우선)

    /**
     * 목적: 텍스트 내 줄바꿈 제거
     * 기능: 줄바꿈 문자를 공백 한 칸으로 바꾼다.
     * 입력: s(원본 텍스트)
     * 출력: 없음
     * 리턴: 한 줄 텍스트
     */
    private fun oneLine(s: String): String = s.replace(NEWLINES, " ") // 줄바꿈 → 공백

    /**
     * 목적: 전사 txt 본문 생성
     * 기능: 머리줄(엔진/VAD/조각 수), 전체 텍스트, 구간별 "start, end, text"(초 소수 2자리) 줄을 이어 붙인다. 줄바꿈은 \n, 마지막 줄 뒤에도 \n.
     * 입력: engineLabel(예: "whispercpp small"), vadId(VAD 프리셋 ID), chunkCount(조각 수), fullText(전체 텍스트), segments(구간 목록)
     * 출력: 없음
     * 리턴: txt 본문 문자열
     */
    fun format(engineLabel: String, vadId: String, chunkCount: Int, fullText: String, segments: List<TimedText>): String {
        val sb = StringBuilder() // 결과 버퍼
        sb.append("[전체 텍스트] (엔진: ").append(engineLabel).append(" / VAD: ").append(vadId).append(" / 조각 ").append(chunkCount).append("개)\n") // 머리줄
        sb.append(oneLine(fullText)).append("\n\n") // 전체 텍스트 + 빈 줄
        sb.append("[구간별] start(sec), end(sec), text\n") // 구간 머리줄
        for (seg in segments) { // 구간마다
            sb.append(String.format(Locale.ROOT, "%.2f, %.2f, ", seg.startSec, seg.endSec)) // 시각(로케일 무관 소수점 '.')
            sb.append(oneLine(seg.text)).append("\n") // 텍스트 + 줄바꿈
        }
        return sb.toString() // 완성 문자열
    }

    /**
     * 목적: 텍스트 파일 저장
     * 기능: 부모 폴더를 만들고 UTF-8(BOM 없음)로 원자적으로 덮어쓴다(임시 파일 → rename). 크래시해도 반쯤 쓴 txt 가 남지 않는다(R10).
     * 입력: file(저장 경로), content(내용)
     * 출력: 파일 생성, 실패 시 IOException
     * 리턴: 없음
     */
    // change(add)-hyungchul-2026-10-11-01:47: 원자적 쓰기로 변경(R10 — 재개 시 부분 txt 를 완료로 오인하지 않게)
    fun write(file: File, content: String) {
        AtomicFile.write(file, content.toByteArray(Charsets.UTF_8)) // BOM 없이 UTF-8 로 원자적 저장(부모 폴더도 생성)
    }

    /**
     * 목적: 오류 파일 저장
     * 기능: stage/code/message 를 한 줄씩 적은 <stem>.ERROR.txt 를 원자적으로 만든다(줄바꿈은 공백으로 치환해 줄 구조 유지).
     * 입력: file(저장 경로), stage(실패 단계), code(오류 코드), message(오류 메시지)
     * 출력: 파일 생성
     * 리턴: 없음
     */
    fun writeError(file: File, stage: String, code: String, message: String) {
        write(file, "stage: ${oneLine(stage)}\ncode: ${oneLine(code)}\nmessage: ${oneLine(message)}\n") // 3줄 기록
    }
}
