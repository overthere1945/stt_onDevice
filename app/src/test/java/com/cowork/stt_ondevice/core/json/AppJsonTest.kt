// 파일명: AppJsonTest.kt
// 목적: AppJson 설정(prettyPrint, encodeDefaults, explicitNulls) 검증
// 기능: 기본값과 null 이 JSON 출력에 명시되는지 확인한다.
package com.cowork.stt_ondevice.core.json // JSON 테스트 패키지

import kotlinx.serialization.Serializable // 직렬화 대상 표시
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-12:08
/** 목적: AppJson 검증용 샘플 모델. 기능: 기본값 필드와 null 필드를 가진다. */
@Serializable
data class AppJsonSample(val name: String = "기본", val value: Double? = null)

/**
 * 목적: AppJson 설정 회귀 방지
 * 기능: 직렬화 결과 문자열에 기본값·null 이 포함되고 들여쓰기가 있는지 본다.
 */
class AppJsonTest {

    /**
     * 목적: 기본값/null 명시 출력 확인
     * 기능: AppJsonSample() 을 직렬화해 "name", "value": null 이 있는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun encodesDefaultsAndExplicitNullsPretty() {
        val s = AppJson.encodeToString(AppJsonSample.serializer(), AppJsonSample()) // 직렬화
        assertTrue(s.contains("\"name\": \"기본\"")) // 기본값 포함
        assertTrue(s.contains("\"value\": null")) // null 명시
        assertTrue(s.contains("\n")) // 들여쓰기(여러 줄)
    }
}
