// 파일명: PlatformErrorsTest.kt
// 목적: 플랫폼 SpeechRecognizer onError 값 → 앱 분류 표(mapRecognizerError·classifySupportError)를 전체 단언 (후속 수정 11)
// 기능: 세션 onError 매핑(서비스 끊김 11 은 SERVICE_DISCONNECTED 로 구분, 원래 코드는 메시지에 보존)과
//       지원 확인 onError 분류(12·13 영구 미지원, 9 영구 권한 없음, 그 밖 일시 확인 실패)를 정수 값 그대로 표로 확인한다.
// change(add)-hyungchul-2026-10-12-02:54
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-12-02:54
/**
 * 목적: 플랫폼 오류 분류 순수 함수 테스트
 * 기능: Android 클래스 없이 정수 값(SpeechRecognizer.ERROR_* 의 실제 값)으로 표 전체를 확인한다.
 */
class PlatformErrorsTest {

    /**
     * 목적: 세션 onError 매핑 표 검증
     * 기능: 알려진 모든 코드(1~14)가 기대 결과로 바뀐다 — 12·13 LANGUAGE_UNAVAILABLE, 6·7 Completed(그때까지 조각), 9 PERMISSION_MISSING,
     *       11 SERVICE_DISCONNECTED(메시지에 onError=11 보존), 그 밖 ENGINE_ERROR("onError=<n>").
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun mapRecognizerErrorTable() {
        val seg = listOf("조각") // 그때까지 모은 조각
        for (n in listOf(12, 13)) { // 언어 문제
            val o = mapRecognizerError(n, "ko-KR", seg) as SessionOutcome.Failed // 실패 결과
            assertEquals("LANGUAGE_UNAVAILABLE", o.code) // 코드
            assertTrue(o.message, o.message.contains("onError=$n") && o.message.contains("ko-KR")) // 원래 코드·태그 보존
        }
        for (n in listOf(6, 7)) assertEquals(SessionOutcome.Completed(seg), mapRecognizerError(n, "ko-KR", seg)) // 음성 없음 = 오류 아님
        val perm = mapRecognizerError(9, "en-US", seg) as SessionOutcome.Failed // 권한 부족
        assertEquals("PERMISSION_MISSING", perm.code) // 코드
        assertTrue(perm.message, perm.message.contains("onError=9")) // 원래 코드 보존
        val disc = mapRecognizerError(11, "en-US", seg) as SessionOutcome.Failed // 서비스 연결 끊김
        assertEquals("SERVICE_DISCONNECTED", disc.code) // 새 코드(ENGINE_ERROR 와 구분)
        assertTrue(disc.message, disc.message.contains("onError=11") && disc.message.contains("en-US")) // 원래 코드·태그 보존
        for (n in listOf(1, 2, 3, 4, 5, 8, 10, 14, 99)) { // 그 밖
            assertEquals(SessionOutcome.Failed("ENGINE_ERROR", "onError=$n"), mapRecognizerError(n, "ko-KR", seg)) // 기존 형식 유지
        }
    }

    /**
     * 목적: 지원 확인 onError 분류 표 검증
     * 기능: 12·13 → Unavailable(영구 미지원 사유 상수), 9 → Unavailable("마이크 권한 없음"), 그 밖(11·14 등) → CheckFailed("상태 확인 실패(onError=<n>)").
     * 입력: 없음
     * 출력: 단언 실패 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun classifySupportErrorTable() {
        for (n in listOf(12, 13)) assertEquals(LanguageStatus.Unavailable(StatusReasons.PLATFORM_UNSUPPORTED), classifySupportError(n)) // 영구 미지원
        assertEquals(LanguageStatus.Unavailable("마이크 권한 없음"), classifySupportError(9)) // 영구 권한 없음
        for (n in listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 14, 99)) { // 일시
            assertEquals(LanguageStatus.CheckFailed("상태 확인 실패(onError=$n)"), classifySupportError(n)) // 지금까지와 같은 문구
        }
    }
}
