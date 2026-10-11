// 파일명: InputScannerTest.kt
// 목적: InputScanner 의 입력 음원 스캔 동작 검증
// 기능: 알려진 언어 폴더의 wav 수집·정렬, 언어 매핑, 없는 폴더 처리를 확인한다.
//       change(add)-hyungchul-2026-10-11-12:51 (T3 이월) '.' 으로 시작하는 파일 제외, 같은 폴더의 대소문자만 다른 같은 stem wav 를 오류로 알리고 목록에서 빼는지 확인한다.
package com.cowork.stt_ondevice.core.storage // 저장소 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증 // change(add)-hyungchul-2026-10-11-12:51
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

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 숨김 파일 제외 확인
     * 기능: `._01.wav`(macOS 메타), `.trashed-1700000000-02.wav`(휴지통) 은 빼고 01.wav 만 남는지, 오류는 없는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun dotFilesAreSkipped() {
        val root = tmp.newFolder("input_sound") // 입력 루트
        touch(root, "kr/customer/01.wav") // 정상 파일
        touch(root, "kr/customer/._01.wav") // macOS 메타 파일
        touch(root, "kr/customer/.trashed-1700000000-02.wav") // 휴지통 파일
        val r = InputScanner.scanWithErrors(root) // 스캔
        assertEquals(listOf("01"), r.items.map { it.stem }) // 정상 파일만
        assertTrue(r.errors.isEmpty()) // 오류 없음
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 대소문자만 다른 같은 이름 wav 를 오류로 처리하는지 확인
     * 기능: 같은 폴더의 01.wav·01.WAV(그리고 A.wav·a.wav)는 어느 것도 고르지 않고 목록에서 빼고 위치·파일 이름이 든 오류 문구를 남긴다.
     *       다른 폴더의 01.wav 와 같은 폴더의 02.wav 는 그대로 남는다. scan() 은 같은 항목 목록을 돌려준다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun caseInsensitiveStemClashIsReported() {
        val root = tmp.newFolder("input_sound") // 입력 루트
        touch(root, "kr/customer/01.wav") // 충돌 1
        touch(root, "kr/customer/01.WAV") // 충돌 2(확장자 대소문자만 다름)
        touch(root, "kr/customer/02.wav") // 정상
        touch(root, "kr/other/01.wav") // 다른 폴더(충돌 아님)
        touch(root, "en/A.wav") // 충돌 3(최상위)
        touch(root, "en/a.wav") // 충돌 4(이름 대소문자만 다름)
        val r = InputScanner.scanWithErrors(root) // 스캔
        assertEquals(listOf("kr/customer/02", "kr/other/01"), r.items.map { "${it.lang}/${it.relDir}/${it.stem}" }) // 충돌 파일은 모두 빠짐
        assertEquals(2, r.errors.size) // 위치마다 1건
        assertTrue(r.errors[0], r.errors[0].startsWith("en: A.wav, a.wav")) // 최상위 위치·이름
        assertTrue(r.errors[1], r.errors[1].startsWith("kr/customer: 01.WAV, 01.wav")) // 폴더 위치·이름
        assertEquals(r.items, InputScanner.scan(root)) // scan() 도 같은 항목
    }
}
