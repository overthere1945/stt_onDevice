// 파일명: RunnerHelpersTest.kt
// 목적: 배치 실행 보조 객체(CsvRowBuilder·RunErrors·ItemOutputs.replaceRows·CsvFiles)의 단위 동작 검증
// 기능: 유니코드 공백(NBSP·U+3000) 정리와 텍스트 통계, LinkageError 격리 매핑, 결과 쓰기 재시도 시 찢긴 줄·기존 행 교체를 확인한다.
// change(add)-hyungchul-2026-10-11-02:14
package com.cowork.stt_ondevice.runner // 배치 실행 테스트 패키지

import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.device.ResourceSampler // 자원 샘플러(생성자 주입용)
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 판정
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.Runtime // 런타임
import com.cowork.stt_ondevice.model.StreamingKind // 입력 방식
import com.cowork.stt_ondevice.model.VoskConfig // 엔진 설정
import com.cowork.stt_ondevice.output.ChunkJson // chunk 결과
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.SpanJson // 원본 구간
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 목록
import org.junit.Assert.assertEquals // 같음 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Rule // 규칙
import org.junit.Test // 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-02:14
/**
 * 목적: 배치 실행 보조 객체 회귀 방지
 * 기능: 위 파일 머리말의 항목을 확인한다.
 */
class RunnerHelpersTest {

    @get:Rule // 테스트마다 새 임시 폴더
    val tmp = TemporaryFolder()

    private val device = DeviceInfo("samsung", "SM-S938N", "SM8750", "16", 36, "arm64-v8a", 11800L, "1.0 (1)") // 고정 기기

    /** 목적: 가짜 모델. 기능: algo "fake", model "m1". 입력: 없음. 출력: 없음. 리턴: ModelSpec */
    private fun spec(): ModelSpec = ModelSpec(
        id = "m1", kind = ModelKind.ASR, algo = "fake", model = "m1", displayName = "m1", languages = setOf("ko"), // 식별·언어
        runtime = Runtime.VOSK, runtimeVersion = "0.3", precision = "fp32", maxChunkSec = 30.0, // 실행 정보
        streaming = StreamingKind.FILE_ONLY, downloads = emptyList(), files = emptyList(), originalRepo = "", // 설치 정보
        license = "test", licenseUrl = "", commercial = CommercialStatus.ALLOWED, compatAlias = null, engineConfig = VoskConfig, // 라이선스·설정
    )

    private val combo by lazy { Combo(spec(), VadPresets.byId("SILERO"), 30.0, ChunkMode.MERGE) } // 고정 조합
    private val processor = ItemProcessor({ 0L }, { "2026-10-11T02:14:00" }, { null }, { ResourceSampler(readPssKb = { null }, readCpuMs = { 0L }) }) // detail 생성용

    /** 목적: 입력 항목. 기능: 임시 폴더의 가짜 wav 경로(내용 1바이트). 입력: stem. 출력: 파일 생성. 리턴: InputItem */
    private fun item(stem: String): InputItem {
        val f = File(tmp.root, "in/kr/customer/$stem.wav") // 경로
        f.parentFile!!.mkdirs() // 폴더
        f.writeBytes(byteArrayOf(0)) // 내용
        return InputItem("kr", "customer", stem, f) // 항목
    }

    /** 목적: 유니코드 공백 정리 확인. 기능: NBSP·U+3000·탭이 공백 1개로 정리되고 chars/words/text 가 Python str.split 기준과 같은지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun unicodeWhitespaceMatchesPythonSplit() {
        val it1 = item("01") // 항목
        val chunk = ChunkJson(0, listOf(SpanJson(0.0, 1.0, 0.0)), "NONE", "　안녕 세계\t끝　", "raw", 0.1) // 유니코드 공백 포함
        val detail = processor.failed(combo, it1, ErrorJson("X", "Y", "z"), null).copy(status = "OK", error = null, chunks = listOf(chunk)) // OK detail
        val row = CsvRowBuilder.build("20261011_021400", combo, it1, detail, device, 4) // 행
        assertEquals("안녕 세계 끝", row["text"]) // 공백 정리
        assertEquals("7", row["chars"]) // 공백 포함 7자(Python len 과 같음)
        assertEquals("3", row["words"]) // 3어절
        assertEquals("안녕 세계 끝", CsvRowBuilder.oneLine("안녕  세계 끝")) // 연속·em space 도 정리
        assertEquals("a b c d e", CsvRowBuilder.oneLine("a\u000Bb\u001Cc\u0085d\u202Fe")) // VT·정보 구분자·NEL·좁은 NBSP 도 Python split 처럼 공백 // change(add)-hyungchul-2026-10-11-02:40
    }

    /** 목적: 오류 매핑 확인. 기능: LinkageError 는 격리(코드 구분), 취소·그 밖의 Error 는 null(전파). 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun runErrorsIsolateLinkageErrors() {
        assertEquals("LINKAGE_ERROR", RunErrors.toError(NoClassDefFoundError("x"), "TRANSCRIBE", "D")!!.code) // 클래스 누락
        assertEquals("TRANSCRIBE", RunErrors.toError(NoClassDefFoundError("x"), "TRANSCRIBE", "D")!!.stage) // 현재 단계
        assertEquals("NATIVE_LIB_MISSING", RunErrors.toError(UnsatisfiedLinkError("so"), "MODEL_LOAD", "D")!!.code) // .so 누락
        assertEquals("OOM", RunErrors.toError(OutOfMemoryError(), "TRANSCRIBE", "D")!!.code) // 메모리 부족
        assertEquals("ABORTED", RunErrors.toError(EngineException("TRANSCRIBE", "ABORTED", "m"), "VAD", "D")!!.code) // 엔진 분류 그대로
        assertNull(RunErrors.toError(CancellationException("c"), "TRANSCRIBE", "D")) // 취소는 전파
        assertNull(RunErrors.toError(StackOverflowError(), "TRANSCRIBE", "D")) // 그 밖의 Error 는 전파
    }

    /** 목적: 결과 쓰기 재시도 시 행 교체 확인. 기능: 찢긴 마지막 줄과 이 항목의 기존 OK 행이 있을 때 replaceRows=true 로 ERROR 를 쓰면 그 항목 행이 정확히 1개(ERROR), 다른 항목 행은 유지. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun replaceRowsRemovesTornLineAndOldRow() {
        val runDir = File(tmp.root, "run") // 실행 폴더
        val outputs = ItemOutputs(runDir, "20261011_021400", device, 4) // 결과 쓰기
        val a = item("01") // 다른 항목
        val b = item("02") // 대상 항목
        val ok = { i: InputItem -> processor.failed(combo, i, ErrorJson("X", "Y", "z"), null).copy(status = "NO_MATCH", error = null) } // 정상(NO_MATCH) detail
        outputs.write(combo, a, ok(a)) // 01 정상
        outputs.write(combo, b, ok(b)) // 02 정상(나중에 오류로 교체)
        val csv = OutputLayout.resultCsv(runDir, "kr", combo.name) // 조합 CSV
        csv.appendText("20261011_021400,찢긴", Charsets.UTF_8) // 찢긴 줄
        outputs.summaryFile().appendText("20261011_021400,찢긴", Charsets.UTF_8) // 요약에도 찢긴 줄
        outputs.write(combo, b, processor.failed(combo, b, ErrorJson("OUTPUT", "WRITE_FAILED", "디스크"), null), replaceRows = true) // 오류로 교체
        for (f in listOf(csv, outputs.summaryFile())) { // 두 CSV 마다
            val rows = CsvFiles.readRecords(f) // 행
            assertEquals(listOf("01.wav" to "NO_MATCH", "02.wav" to "ERROR"), rows.map { it["file"] to it["status"] }) // 행 교체·찢긴 줄 제거
            assertEquals('\uFEFF', f.readText(Charsets.UTF_8).first()) // BOM 유지 // change(add)-hyungchul-2026-10-11-04:46 BOM 문자를 \uFEFF 이스케이프로 표기(lint ByteOrderMark)
        }
        assertNotNull(OutputLayout.errorFile(runDir, b, combo.name).takeIf { it.isFile }) // ERROR.txt
        assertNull(OutputLayout.transcript(runDir, b, combo.name).takeIf { it.exists() }) // txt 제거
    }
}
