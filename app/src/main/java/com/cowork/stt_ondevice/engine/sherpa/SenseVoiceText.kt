// 파일명: SenseVoiceText.kt
// 목적: SenseVoice 결과의 원문/정리본 만들기 (spec §9.1 — detail JSON 에는 태그 원문 보존, txt 에는 태그 제거 텍스트)
// 기능: clean — "<|...|>" 형태의 특수 태그(언어·감정·이벤트·ITN)를 지우고 공백을 하나로 정리한다.
//       rawOf — sherpa-onnx OfflineRecognizerResult 의 lang/emotion/event(이미 "<|ko|>" 같은 태그 문자열)와 text 를
//               SenseVoice 원 출력 순서대로 이어 붙여 원문 문자열을 만든다(빈 필드는 생략).
//       참고: sherpa v1.13.8 은 첫 4개 토큰(언어·감정·이벤트·ITN)을 text 에서 빼고 앞 3개만 lang/emotion/event 로 돌려주므로
//             4번째 ITN 태그(<|withitn|>/<|woitn|>)는 결과에 없다(설정 useInverseTextNormalization=true 로 고정).
// change(add)-hyungchul-2026-10-10-20:57
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 패키지

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: SenseVoice 텍스트 도우미
 * 기능: 위 파일 머리 설명 참조. 상태가 없는 순수 함수만 있다.
 */
object SenseVoiceText {

    private val TAG = Regex("""<\|[^|<>]*\|>""") // "<|" + ('|','<','>' 가 아닌 문자들) + "|>" 형태의 태그
    private val SPACES = Regex("""\s+""") // 연속 공백(탭·줄바꿈 포함)

    /**
     * 목적: 태그 제거 + 공백 정리
     * 기능: 모든 "<|...|>" 태그를 공백으로 바꾼 뒤 연속 공백을 하나로 줄이고 양끝 공백을 없앤다.
     *       태그 형태가 아닌 '<'·'|'·'>' 기호는 그대로 둔다.
     * 입력: raw(SenseVoice 원문 또는 sherpa 결과 text)
     * 출력: 없음
     * 리턴: 정리된 텍스트(태그만 있으면 빈 문자열)
     */
    fun clean(raw: String): String =
        raw.replace(TAG, " ") // 태그 → 공백(앞뒤 단어가 붙지 않게)
            .replace(SPACES, " ") // 연속 공백 → 하나
            .trim() // 양끝 공백 제거

    /**
     * 목적: sherpa 결과 필드를 원문 문자열로 직렬화
     * 기능: lang, emotion, event, text 를 이 순서로 이어 붙인다(빈 문자열은 건너뜀). 결과 예: "<|ko|><|NEUTRAL|><|Speech|>안녕하세요".
     * 입력: lang(언어 태그), emotion(감정 태그), event(이벤트 태그), text(태그 뺀 본문)
     * 출력: 없음
     * 리턴: 원문 문자열
     */
    fun rawOf(lang: String, emotion: String, event: String, text: String): String =
        listOf(lang, emotion, event, text) // 원 출력 순서
            .filter { it.isNotEmpty() } // 빈 필드 생략
            .joinToString("") // 구분자 없이 연결(SenseVoice 원 출력 형식)
}
