// 파일명: OutputLayoutTest.kt
// 목적: OutputLayout 의 PC 평가기 호환 경로 규칙 검증
// 기능: 조합명, txt/ERROR/result.csv/details/vad_segments/manifest/log/summary 경로와 최상위 폴더(relDir="") 처리를 확인한다.
package com.cowork.stt_ondevice.output // 출력 테스트 패키지

import com.cowork.stt_ondevice.core.storage.InputItem // 입력 음원 모델
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Test // JUnit4 테스트 표시
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: 출력 경로 규칙 회귀 방지
 * 기능: 가상의 실행 폴더 기준으로 각 경로가 기대 문자열과 같은지 비교한다.
 */
class OutputLayoutTest {

    private val run = File("run") // 가상의 실행 폴더(실제 생성 안 함)
    private val combo = "whispercpp_small_SILERO_SAFE" // 조합명 예시

    /**
     * 목적: 조합명과 주요 경로 규칙 확인
     * 기능: comboName, transcript, errorFile, resultCsv 가 PC 평가기 규칙과 같은지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun comboAndPaths() {
        val item = InputItem("kr", "customer/test", "01", File("in/kr/customer/test/01.wav")) // 하위 폴더 항목
        assertEquals(combo, OutputLayout.comboName("whispercpp", "small", "SILERO_SAFE")) // 조합명
        assertEquals(File("run/kr/whispercpp_small_SILERO_SAFE/customer/test/01.txt"), OutputLayout.transcript(run, item, combo)) // 전사 txt
        assertEquals(File("run/kr/whispercpp_small_SILERO_SAFE/customer/test/01.ERROR.txt"), OutputLayout.errorFile(run, item, combo)) // 오류 txt
        assertEquals(File("run/kr/whispercpp_small_SILERO_SAFE/result.csv"), OutputLayout.resultCsv(run, "kr", combo)) // 조합별 CSV
    }

    /**
     * 목적: 부가 산출물 경로 규칙 확인
     * 기능: details, vad_segments, manifest, log, summary 경로를 비교한다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun auxiliaryPaths() {
        val item = InputItem("en", "vLog", "10", File("in/en/vLog/10.wav")) // 영어 항목
        assertEquals(File("run/details/en/$combo/vLog/10.json"), OutputLayout.detail(run, item, combo)) // 상세 JSON
        assertEquals(File("run/vad_segments/SILERO_SAFE/en/vLog/10.json"), OutputLayout.vadSegments(run, "SILERO_SAFE", item)) // VAD 구간 JSON
        assertEquals(File("run/run_manifest.json"), OutputLayout.manifest(run)) // 실행 매니페스트 // change(add)-hyungchul-2026-10-11-01:47: run_manifest.json(R32)
        assertEquals(File("run/_logs/run_20261010_120800.log"), OutputLayout.log(run, "20261010_120800")) // 실행 로그
        assertEquals(File("run/_summary/all_result_20261010_120800.csv"), OutputLayout.summaryCsv(run, "20261010_120800")) // 통합 요약 CSV
    }

    /**
     * 목적: 최상위 파일(relDir 빈 문자열)의 경로 확인
     * 기능: 빈 relDir 가 추가 경로 조각을 만들지 않는지 본다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test // 테스트 메서드
    fun emptyRelDirAddsNoSegment() {
        val item = InputItem("kr", "", "05", File("in/kr/05.wav")) // 최상위 항목
        assertEquals(File("run/kr/$combo/05.txt"), OutputLayout.transcript(run, item, combo)) // txt 가 조합 폴더 바로 아래
        assertEquals(File("run/details/kr/$combo/05.json"), OutputLayout.detail(run, item, combo)) // details 도 동일
        assertEquals(File("run/vad_segments/SILERO_SAFE/kr/05.json"), OutputLayout.vadSegments(run, "SILERO_SAFE", item)) // vad_segments 도 동일
    }
}
