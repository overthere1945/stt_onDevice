// 파일명: SenseVoiceTextTest.kt
// 목적: SenseVoice 결과 텍스트 정리(SenseVoiceText) 검증 (spec §9.1 — txt 에는 태그 제거 텍스트, detail 에는 원문 보존)
// 기능: <|...|> 태그 제거·공백 정리(clean)와, sherpa 결과 필드(lang/emotion/event/text)를 원문 문자열로 합치는 rawOf 를 확인한다.
// change(add)-hyungchul-2026-10-10-20:57
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: SenseVoice 텍스트 정리 규칙 회귀 방지
 * 기능: 태그·공백 처리와 원문 직렬화를 확인한다(네이티브 불필요).
 */
class SenseVoiceTextTest {

    /**
     * 목적: brief 의 대표 예 검증
     * 기능: 언어·감정·이벤트·ITN 태그 4개가 붙은 원문에서 태그를 모두 지우면 본문만 남는다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun senseVoiceTagsStripped() {
        assertEquals("안녕하세요", SenseVoiceText.clean("<|ko|><|NEUTRAL|><|Speech|><|woitn|>안녕하세요")) // 태그 4개 제거
    }

    /**
     * 목적: 공백 정리 검증
     * 기능: 태그 앞뒤·중간의 연속 공백(탭·줄바꿈 포함)을 공백 하나로 줄이고 양끝 공백을 없앤다.
     *       문장 중간에 끼어 있는 태그도 지운다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun spacesNormalized() {
        assertEquals("hello world", SenseVoiceText.clean("  <|en|><|HAPPY|> hello \t  world \n ")) // 앞 태그·연속 공백
        assertEquals("a b", SenseVoiceText.clean("a<|Laughter|> b")) // 중간 태그
        assertEquals("", SenseVoiceText.clean("<|ko|><|NEUTRAL|><|Speech|><|withitn|>")) // 태그만 있으면 빈 문자열
        assertEquals("", SenseVoiceText.clean("")) // 빈 입력
    }

    /**
     * 목적: 태그가 아닌 꺾쇠 문자열 보존 검증
     * 기능: "<|" 로 시작해 "|>" 로 끝나는 형태만 태그로 보고, 그 밖의 '<'·'|'·'>' 는 그대로 둔다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun nonTagBracketsKept() {
        assertEquals("1 < 2 | 3 > 2", SenseVoiceText.clean("1 < 2 | 3 > 2")) // 일반 기호 보존
    }

    /**
     * 목적: 원문 직렬화 검증
     * 기능: sherpa OfflineRecognizerResult 의 lang/emotion/event(이미 "<|..|>" 형태)와 text 를 그 순서로 이어 붙인다.
     *       빈 필드는 건너뛴다(태그가 3개 미만이면 sherpa 가 빈 문자열을 준다). clean(rawOf(...)) 은 clean(text) 와 같다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun rawKeepsAllFields() {
        val raw = SenseVoiceText.rawOf("<|ko|>", "<|NEUTRAL|>", "<|Speech|>", "안녕하세요 반갑습니다") // 직렬화
        assertEquals("<|ko|><|NEUTRAL|><|Speech|>안녕하세요 반갑습니다", raw) // 순서·내용
        assertEquals("안녕하세요 반갑습니다", SenseVoiceText.clean(raw)) // 정리하면 본문
        assertEquals("text", SenseVoiceText.rawOf("", "", "", "text")) // 빈 필드 생략
    }
}
