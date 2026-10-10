// 파일명: BuildSanityTest.kt
// 목적: JVM 단위 테스트 인프라(JUnit, Gradle 테스트 태스크)가 동작하는지 확인하는 최소 테스트
// 기능: 2 + 2 == 4 를 검증한다. (Task 2 에서 삭제 예정)
package com.cowork.stt_ondevice // 앱 루트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-11:56
/**
 * 목적: 테스트 인프라 확인용
 * 기능: 가장 단순한 단언 하나만 수행한다.
 */
class BuildSanityTest {

    /**
     * 목적: 테스트 실행 환경 점검
     * 기능: 2 + 2 의 결과가 4 인지 검증한다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // JUnit 이 실행할 테스트 메서드
    fun sanity() {
        assertEquals(4, 2 + 2) // 기대값 4 와 실제 계산 결과 비교
    }
}
