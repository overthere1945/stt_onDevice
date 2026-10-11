// 파일명: VoskResult.kt
// 목적: Vosk Recognizer 가 돌려주는 최종 결과 JSON 문자열에서 인식 텍스트만 꺼내기
// 기능: VoskResult.text(json) — {"text" : "..."} 의 text 값을 앞뒤 공백 정리해 돌려준다. text 가 비었거나 없거나 null 이면 "".
//       JSON 이 아니거나 객체가 아니면 예외(SerializationException·IllegalArgumentException)를 그대로 던진다(조용히 빈 문자열로 바꾸지 않음).
// change(add)-hyungchul-2026-10-10-21:41
package com.cowork.stt_ondevice.engine.vosk // Vosk 엔진 패키지

import kotlinx.serialization.json.Json // kotlinx 직렬화 JSON
import kotlinx.serialization.json.JsonNull // JSON null 값
import kotlinx.serialization.json.JsonPrimitive // JSON 기본값(문자열 등)
import kotlinx.serialization.json.contentOrNull // 기본값 → 문자열 또는 null
import kotlinx.serialization.json.jsonObject // 요소 → 객체

// change(add)-hyungchul-2026-10-10-21:41
/**
 * 목적: Vosk 결과 JSON 파서
 * 기능: 위 파일 머리 설명 참조. 상태가 없는 순수 함수라 JVM 단위 테스트에서 네이티브 없이 검증한다.
 */
object VoskResult {

    private val parser = Json { isLenient = true; ignoreUnknownKeys = true } // 느슨한 파서(Vosk 서식·추가 필드 허용)

    /**
     * 목적: 최종 결과에서 텍스트 추출
     * 기능: JSON 을 객체로 파싱해 "text" 키의 문자열을 꺼내 앞뒤 공백을 정리한다. 키가 없거나 null 이면 "".
     * 입력: json(Recognizer.getFinalResult() 문자열)
     * 출력: JSON 이 아니면 SerializationException, 객체가 아니면 IllegalArgumentException
     * 리턴: 인식 텍스트(없으면 "")
     */
    fun text(json: String): String {
        val obj = parser.parseToJsonElement(json).jsonObject // 객체로 파싱(객체가 아니면 IllegalArgumentException)
        val v = obj["text"] // text 키 값(없으면 null)
        if (v == null || v is JsonNull) return "" // 키 없음/null → 빈 문자열
        return (v as? JsonPrimitive)?.contentOrNull?.trim() ?: "" // 문자열이 아니면 빈 문자열
    }
}
