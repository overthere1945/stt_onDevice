// 파일명: InputScannerTest.kt
// 목적: InputScanner 의 입력 음원 스캔 동작 검증
// 기능: 알려진 언어 폴더의 wav 수집·정렬, 언어 매핑, 없는 폴더 처리를 확인한다.
package com.cowork.stt_ondevice.core.storage // 저장소 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Rule // JUnit 규칙 표시
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:06
/**
 * 목적: 입력 스캔 회귀 방지
 * 기능: 임시 폴더에 샘플 트리를 만들고 스캔 결과를 검증한다.
 */
class InputScannerTest {

    @get:Rule // 테스트마다 새 임시 폴더 생성·삭제
    val tmp = TemporaryFolder()

    /**
     * 목적: 테스트용 빈 파일 생성
     * 기능: 루트 기준 상대 경로에 부모 폴더를 만들고 빈 파일을 만든다.
     * 입력: root(기준 폴더), rel(상대 경로)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun touch(root: File, rel: String) {
        val f = File(root, rel) // 대상 파일
        f.parentFile!!.mkdirs() // 부모 폴더 생성
        f.writeText("") // 빈 파일 생성
    }

    /**
     * 목적: 알려진 언어 폴더 아래 wav 만 수집되는지 확인
     * 기능: 5개 파일 중 wav 3개가 lang→relDir→stem 순으로 나오는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun scanFindsWavsUnderKnownLangs() {
        val root = tmp.newFolder("input_sound") // 입력 루트
        touch(root, "kr/customer/01.wav") // 한국어 정상 파일
        touch(root, "kr/customer/test/01.wav") // 하위 폴더 파일
        touch(root, "en/vLog/10.WAV") // 대문자 확장자
        touch(root, "en/vLog/note.txt") // 비 wav
        touch(root, "jp/x/01.wav") // 알 수 없는 언어
        val items = InputScanner.scan(root) // 스캔
        assertEquals(3, items.size) // wav 3개만
        assertEquals(listOf("en", "kr", "kr"), items.map { it.lang }) // 언어 순서
        assertEquals(listOf("vLog", "customer", "customer/test"), items.map { it.relDir }) // 상대 폴더 순서
        assertEquals(listOf("10", "01", "01"), items.map { it.stem }) // 파일 stem
        assertEquals(File(root, "en/vLog/10.WAV"), items[0].file) // 원본 파일 경로 보존
    }

    /**
     * 목적: 언어 폴더명 → 엔진 언어 코드 매핑 확인
     * 기능: kr→ko, en→en 인지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun engineLangMapping() {
        val root = tmp.newFolder("input_sound") // 입력 루트
        touch(root, "kr/a/01.wav") // 한국어 파일
        touch(root, "en/a/01.wav") // 영어 파일
        val byLang = InputScanner.scan(root).associateBy { it.lang } // 언어별 맵
        assertEquals("ko", byLang.getValue("kr").engineLang) // kr→ko
        assertEquals("en", byLang.getValue("en").engineLang) // en→en
    }

    /**
     * 목적: 입력 루트가 없을 때 빈 목록 확인
     * 기능: 존재하지 않는 폴더를 스캔해도 예외 없이 빈 리스트인지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun missingRootGivesEmpty() {
        val missing = File(tmp.root, "nope") // 없는 폴더
        assertEquals(emptyList<InputItem>(), InputScanner.scan(missing)) // 빈 리스트
    }
}
