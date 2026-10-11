// 파일명: VoskResultTest.kt
// 목적: Vosk 최종 결과 JSON 에서 텍스트를 뽑는 VoskResult.text 의 동작 검증(네이티브 없이 JVM 에서 실행)
// 기능: 공백이 낀 Vosk 출력 서식 파싱, 빈 text·text 없음·null → "", 앞뒤 공백 정리, 알 수 없는 필드 무시, 깨진 JSON 은 예외 확인.
// change(add)-hyungchul-2026-10-10-21:41
package com.cowork.stt_ondevice.engine.vosk // Vosk 엔진 테스트 패키지

import kotlinx.serialization.SerializationException // JSON 파싱 오류
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-21:41
/**
 * 목적: VoskResult 회귀 방지
 * 기능: Vosk 가 돌려주는 JSON 문자열 모양별로 text 추출 결과를 확인한다.
 */
class VoskResultTest {

    /**
     * 목적: Vosk 실제 출력 서식 파싱 검증
     * 기능: `{"text" : "안녕 하세요"}`(콜론 앞뒤 공백, Vosk 기본 서식)에서 text 값을 그대로 꺼낸다. 빈 text 는 "".
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun parsesFinalResultText() {
        assertEquals("안녕 하세요", VoskResult.text("""{"text" : "안녕 하세요"}""")) // Vosk 서식
        assertEquals("hello world", VoskResult.text("{\n  \"text\" : \"hello world\"\n}")) // 줄바꿈 서식
        assertEquals("", VoskResult.text("""{"text" : ""}""")) // 빈 text
    }

    /**
     * 목적: 비정상이지만 유효한 JSON 처리 검증
     * 기능: text 키 없음(partial 결과 등), text 가 null, 알 수 없는 필드 → 에러 없이 "" 또는 text 값. 앞뒤 공백은 정리한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun absentOrNullTextIsEmpty() {
        assertEquals("", VoskResult.text("{}")) // text 키 없음
        assertEquals("", VoskResult.text("""{"partial" : "abc"}""")) // 다른 키만 있음
        assertEquals("", VoskResult.text("""{"text" : null}""")) // null
        assertEquals("a b", VoskResult.text("""{"text" : "  a b  ", "result" : [{"word" : "a"}]}""")) // 공백 정리 + 알 수 없는 필드 무시
    }

    /**
     * 목적: 깨진 입력 거부 검증
     * 기능: JSON 이 아닌 문자열·객체가 아닌 JSON 은 조용히 ""로 바꾸지 않고 예외를 던진다(엔진이 NATIVE_ERROR 로 보고).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun malformedJsonThrows() {
        assertThrows(SerializationException::class.java) { VoskResult.text("not json") } // JSON 아님
        assertThrows(IllegalArgumentException::class.java) { VoskResult.text("[1,2]") } // 객체 아님
    }
}
