// 파일명: Json.kt
// 목적: 앱 전역 JSON 직렬화 설정 제공
// 기능: details/manifest/vad_segments 등 산출 JSON 을 일관된 서식(들여쓰기, 기본값·null 명시)으로 만든다.
package com.cowork.stt_ondevice.core.json // JSON 패키지

import kotlinx.serialization.json.Json // kotlinx 직렬화 JSON

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: 공용 Json 인스턴스
 * 기능: prettyPrint(사람이 읽기 쉬움), encodeDefaults(기본값도 출력), explicitNulls(측정 불가 값은 null 로 명시)
 */
val AppJson: Json = Json {
    prettyPrint = true // 들여쓰기 출력
    encodeDefaults = true // 기본값 필드도 출력
    explicitNulls = true // null 값을 생략하지 않고 null 로 출력
}
