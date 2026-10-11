// 파일명: RunnerHelpersTest.kt
// 목적: 배치 실행 보조 객체(CsvRowBuilder·RunErrors·ItemOutputs.replaceRows·CsvFiles)의 단위 동작 검증
// 기능: 유니코드 공백(NBSP·U+3000) 정리와 텍스트 통계, LinkageError 격리 매핑, 결과 쓰기 재시도 시 찢긴 줄·기존 행 교체를 확인한다.
//       change(add)-hyungchul-2026-10-11-12:51 (최종 리뷰) device 열이 엔진 보고 backend 를 따르는지(I-6), 많은 항목을 크래시 ERROR 로 기록하는 정리가 빠르고 행 수가 맞는지(I-3),
//       removeRowsWhere 가 여러 행을 한 번에 지우는지 확인한다.
//       change(add)-hyungchul-2026-10-11-18:14 (2차 작업 1) 다시 실행 정리 순서(행 먼저 → 파일, removeRows/removeResults 멱등)와 시간 단언 대신 요약 CSV 읽기 횟수 단언(CsvFiles.onFullRead)을 확인한다.
//       change(add)-hyungchul-2026-10-11-19:10 (2차 작업 5) 시스템 엔진 CSV 행(rtf·compute_type·workers 빈 값, device=backend, engine_version, note 끝 ";feed=x<배속>")과
//       시스템 엔진 maxChunk 덮어쓰기 범위 (0, 60] 를 확인한다.
//       change(add)-hyungchul-2026-10-11-21:50 (최종 리뷰 수정) 시스템 엔진 CSV attempts = 조각 수 + 재시도한 조각 수(계산 불가면 빈 값), 1차 엔진은 "1" 인지 확인한다.
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
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 엔진 카탈로그 // change(add)-hyungchul-2026-10-11-19:10
import com.cowork.stt_ondevice.model.VoskConfig // 엔진 설정
import com.cowork.stt_ondevice.output.ChunkJson // chunk 결과
import com.cowork.stt_ondevice.output.DetailJson // 상세 JSON // change(add)-hyungchul-2026-10-11-21:50
import com.cowork.stt_ondevice.output.EngineMetaJson // 엔진 메타 // change(add)-hyungchul-2026-10-11-12:51
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.SessionJson // 시스템 엔진 세션 기록 // change(add)-hyungchul-2026-10-11-21:50
import com.cowork.stt_ondevice.output.ResultCsvWriter // CSV 작성기(큰 요약 흉내) // change(add)-hyungchul-2026-10-11-12:51
import com.cowork.stt_ondevice.output.STT_CSV_HEADER // PC CSV 헤더 // change(add)-hyungchul-2026-10-11-12:51
import com.cowork.stt_ondevice.output.SpanJson // 원본 구간
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 목록
import org.junit.Assert.assertEquals // 같음 검증
import org.junit.Assert.assertFalse // 거짓 검증 // change(add)-hyungchul-2026-10-11-18:14
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증 // change(add)-hyungchul-2026-10-11-12:51
import org.junit.Assert.fail // 실패 처리 // change(add)-hyungchul-2026-10-11-19:10
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

    // change(add)-hyungchul-2026-10-11-12:51
    /** 목적: device 열 확인(I-6). 기능: 엔진 메타 backend "CPU" → "cpu"(이전 출력과 같음), "GPU" → "gpu", 메타 없음(로드 전 실패) → "cpu". 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun deviceColumnFollowsBackend() {
        val it1 = item("01") // 항목
        val base = processor.failed(combo, it1, ErrorJson("MODEL_LOAD", "LOAD_FAILED", "x"), null) // 메타 없는 오류 detail
        assertEquals("cpu", CsvRowBuilder.build("20261011_125100", combo, it1, base, device, 4)["device"]) // 메타 없음 → 설정 장치
        val cpu = base.copy(engine = EngineMetaJson("1.0", "CPU", "int8", 4)) // CPU 보고
        assertEquals("cpu", CsvRowBuilder.build("20261011_125100", combo, it1, cpu, device, 4)["device"]) // 소문자(이전과 같음)
        val gpu = base.copy(engine = EngineMetaJson("1.0", "GPU", "fp16", 4)) // GPU 보고(가정)
        assertEquals("gpu", CsvRowBuilder.build("20261011_125100", combo, it1, gpu, device, 4)["device"]) // 보고값 따름
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 많은 항목 크래시 정리 속도·행 수 확인(I-3)
     * 기능: 요약 CSV 에 다른 모델 행 1개 + 큰 run 을 흉내낸 다른 행 12000개 + 찢긴 꼬리가 있는 상태에서 모델 로드 크래시 표시를 두 번째로 만나
     *       N=400 항목을 ERROR(CRASH_OR_KILLED)로 기록하면 조합·요약 CSV 에 항목마다 정확히 1행씩 생기고(기존 행 유지, 찢긴 줄 제거)
     *       요약 CSV 전체 읽기가 2번 이하다(항목마다 읽던 이전 방식은 400번 이상). change(add)-hyungchul-2026-10-11-18:14 시간(8초) 단언을 읽기 횟수 단언으로 교체.
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun markManyCrashedItemsIsFastAndConsistent() {
        val runDir = File(tmp.root, "run") // 실행 폴더
        val outputs = ItemOutputs(runDir, "20261011_125100", device, 4) // 결과 쓰기
        val other = Combo(spec().copy(id = "m0", model = "m0"), VadPresets.byId("SILERO"), 30.0, ChunkMode.MERGE) // 다른 모델 조합
        val first = item("0000") // 다른 모델의 기존 항목
        outputs.write(other, first, processor.failed(other, first, ErrorJson("X", "Y", "z"), null).copy(status = "NO_MATCH", error = null)) // 기존 행 1개
        val bulk = ResultCsvWriter(outputs.summaryFile(), STT_CSV_HEADER + "vad") // 요약 CSV 직접 쓰기(큰 run 흉내)
        repeat(12000) { i -> bulk.append(mapOf("algo" to "other", "model" to "x", "vad" to "SILERO", "file" to "b$i.wav", "status" to "OK", "text" to "다른 조합 행 $i")) } // 다른 행 12000개
        outputs.summaryFile().appendText("20261011_125100,찢긴", Charsets.UTF_8) // 요약 찢긴 꼬리
        val n = 400 // 항목 수
        val pairs = (1..n).map { combo to item("%04d".format(it)) } // 대상 항목
        var summaryReads = 0 // 요약 CSV 전체 읽기 횟수 // change(add)-hyungchul-2026-10-11-18:14
        CsvFiles.onFullRead = { f -> if (f == outputs.summaryFile()) summaryReads++ } // 읽을 때마다 센다(테스트 전용 훅) // change(add)-hyungchul-2026-10-11-18:14
        val r = try {
            ResumeReconciler(runDir, outputs, processor).reconcile("m1|MODEL_LOAD", pairs, priorAttempts = 1) // 두 번째 만남 → ERROR 기록
        } finally {
            CsvFiles.onFullRead = null // 훅 해제(다른 테스트에 영향 없게) // change(add)-hyungchul-2026-10-11-18:14
        }
        assertEquals(n, r.written.size) // 모두 기록
        assertEquals("m1", r.crashedModelId) // 크래시 모델
        val comboRows = CsvFiles.readRecords(OutputLayout.resultCsv(runDir, "kr", combo.name)) // 조합 CSV
        assertEquals(n, comboRows.size) // 항목마다 1행
        assertTrue(comboRows.all { it["status"] == "ERROR" && it["note"]!!.endsWith(";code=CRASH_OR_KILLED") }) // 크래시 오류 행
        val summaryRows = CsvFiles.readRecords(outputs.summaryFile()) // 요약 CSV
        assertEquals(n + 12001, summaryRows.size) // 기존 12001행 + 항목 n행(찢긴 줄 없음)
        assertEquals("NO_MATCH", summaryRows[0]["status"]) // 기존 행 유지
        assertEquals((1..n).map { "%04d.wav".format(it) }, summaryRows.drop(12001).map { it["file"] }) // 순서·중복 없음
        assertTrue("요약 CSV 를 $summaryReads 번 읽음(항목마다 읽으면 $n 이상)", summaryReads <= 2) // 항목 수와 상관없이 상수 번 읽음(시간 대신 구조로 검증) // change(add)-hyungchul-2026-10-11-18:14
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /** 목적: 여러 행 한 번에 지우기 확인. 기능: removeRowsWhere 가 조건에 맞는 행만 지우고 지운 수를 돌려주며, 맞는 행이 없으면 파일을 건드리지 않는다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun removeRowsWhereDropsMatchingRows() {
        val runDir = File(tmp.root, "run") // 실행 폴더
        val outputs = ItemOutputs(runDir, "20261011_125100", device, 4) // 결과 쓰기
        val items = (1..4).map { item("0$it") } // 항목 4개
        for (i in items) outputs.write(combo, i, processor.failed(combo, i, ErrorJson("X", "Y", "z"), null).copy(status = "NO_MATCH", error = null)) // 행 4개
        val csv = OutputLayout.resultCsv(runDir, "kr", combo.name) // 조합 CSV
        assertEquals(2, CsvFiles.removeRowsWhere(csv) { it["file"] in setOf("02.wav", "04.wav") }) // 2행 제거
        assertEquals(listOf("01.wav", "03.wav"), CsvFiles.readRecords(csv).map { it["file"] }) // 남은 행
        val before = csv.lastModified() // 수정 시각
        assertEquals(0, CsvFiles.removeRowsWhere(csv) { false }) // 지울 것 없음
        assertEquals(before, csv.lastModified()) // 파일 그대로
    }

    // change(add)-hyungchul-2026-10-11-18:14
    /**
     * 목적: 다시 실행 정리 순서(행 먼저 → 파일) 확인
     * 기능: 크래시 항목 2개를 만든 뒤 removeRows 만 부르면(행 정리 직후 프로세스가 죽은 상태) 조합·요약 CSV 에 해당 행이 0개이고
     *       ERROR.txt 는 남아 isCrashed 가 참이다. 이어서 removeResults 를 다시 부르면 예외 없이 파일까지 지워진다(멱등).
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun removeRowsLeavesFilesForRedo() {
        val runDir = File(tmp.root, "run") // 실행 폴더
        val outputs = ItemOutputs(runDir, "20261011_125100", device, 4) // 결과 쓰기
        val items = listOf(item("01"), item("02")) // 크래시 항목 2개
        val crash = ErrorJson("TRANSCRIBE", ItemOutputs.CRASH_CODE, "중단") // 크래시 오류
        for (i in items) outputs.write(combo, i, processor.failed(combo, i, crash, null)) // ERROR.txt + detail + CSV 행
        val targets = items.map { combo to it } // 정리 대상
        assertTrue(targets.all { (c, it) -> outputs.isCrashed(c, it) }) // 처음엔 둘 다 크래시 기록
        outputs.removeRows(targets) // 행만 지움(여기서 죽은 상태 흉내)
        val comboCsv = OutputLayout.resultCsv(runDir, "kr", combo.name) // 조합 CSV
        assertEquals(0, CsvFiles.readRecords(comboCsv).size) // 조합 CSV 행 0개
        assertEquals(0, CsvFiles.readRecords(outputs.summaryFile()).size) // 요약 CSV 행 0개
        for ((c, it) in targets) { // 항목마다
            assertTrue(OutputLayout.errorFile(runDir, it, c.name).isFile) // ERROR.txt 는 남음
            assertTrue(outputs.isCrashed(c, it)) // 아직 크래시 기록으로 보임(= 다시 정리 대상)
            assertTrue(OutputLayout.detail(runDir, it, c.name).isFile) // detail 도 남음
        }
        outputs.removeResults(targets) // 다시 정리(행은 이미 없음) — 예외 없이 끝나야 함
        for ((c, it) in targets) { // 항목마다
            assertFalse(OutputLayout.errorFile(runDir, it, c.name).exists()) // ERROR.txt 지워짐
            assertFalse(OutputLayout.detail(runDir, it, c.name).exists()) // detail 지워짐
            assertFalse(outputs.isDone(c, it)) // 미완료
        }
        assertEquals(0, CsvFiles.readRecords(comboCsv).size) // 행은 여전히 0개
        outputs.removeResults(targets) // 세 번째도 멱등
    }

    // change(add)-hyungchul-2026-10-11-19:10
    /**
     * 목적: 시스템 엔진 CSV 행 매핑 확인 (2차 작업 5)
     * 기능: 시스템 spec(공급 4배속) detail → rtf·compute_type·workers 빈 값, device = backend 그대로, engine_version = 엔진 보고 버전,
     *       note 가 ";feed=x4" 로 끝나고, transcribe_sec 는 8.000. 일반 모델의 note 에는 feed 표기가 없다.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test
    fun systemEngineCsvRow() {
        val sys = SystemCatalog.all.first { it.id == "mlkit-basic-x4" } // 시스템 엔진 x4
        val sysCombo = Combo(sys, VadPresets.byId("SILERO"), 30.0, ChunkMode.MERGE) // 시스템 조합
        val it1 = item("01") // 항목
        val version = "mlkit-genai-sr 1.0.0-alpha1 / com.google.android.tts 1.2.3" // 엔진 보고 버전
        val detail = processor.failed(sysCombo, it1, ErrorJson("X", "Y", "z"), null).copy( // OK detail 구성
            status = "OK", error = null, audioSec = 32.0, transcribeSec = 8.0, rtf = null,
            chunks = listOf(ChunkJson(0, listOf(SpanJson(0.0, 1.0, 0.0)), "NONE", "안녕", "raw", 0.1)),
            engine = EngineMetaJson(version, "system(soda)", "", 0),
        )
        val row = CsvRowBuilder.build("20261011_191000", sysCombo, it1, detail, device, 4) // 행
        assertEquals("", row["rtf"].orEmpty()) // rtf 빈 값
        assertEquals("system(soda)", row["device"]) // backend 그대로
        assertEquals("", row["compute_type"].orEmpty()) // 정밀도 빈 값
        assertEquals("", row["workers"].orEmpty()) // 스레드 빈 값(설정 스레드 4 로 채우지 않음)
        assertEquals(version, row["engine_version"]) // 엔진 보고 버전
        assertTrue(row.getValue("note")!!.endsWith(";feed=x4")) // feed 표기
        assertEquals("8.000", row["transcribe_sec"]) // 전사 시간
        val plain = CsvRowBuilder.build("20261011_191000", combo, it1, detail.copy(engine = null), device, 4) // 일반 모델 행
        assertFalse(plain.getValue("note")!!.contains("feed=")) // 일반 모델은 feed 없음
        assertEquals("4", plain["workers"]) // 일반 모델은 기존 규칙(설정 스레드 수)
    }

    // change(add)-hyungchul-2026-10-11-19:10
    /**
     * 목적: 시스템 엔진 maxChunk 덮어쓰기 범위 확인 (2차 작업 5)
     * 기능: 시스템 spec 의 덮어쓰기 0.0·61.0 은 IllegalArgumentException(문구 포함), 60.0 은 허용. 일반 spec 의 0.0 은 기존대로 허용.
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test
    fun systemOverrideRange() {
        val sys = SystemCatalog.all.first { it.id == "mlkit-basic-x4" } // 시스템 엔진
        fun cfg(spec: ModelSpec, v: Double) = RunConfig("20261011_191000", emptyList(), listOf(spec), emptyList(), ChunkMode.MERGE, mapOf(spec.id to v)) // 덮어쓰기 1개 설정
        for (bad in listOf(0.0, 61.0)) { // 범위 밖 값
            try {
                cfg(sys, bad) // 생성 시 거부되어야 함
                fail("$bad 는 거부되어야 합니다")
            } catch (e: IllegalArgumentException) {
                assertEquals("시스템 엔진 최대 조각 길이는 0 초 초과 60 초 이하여야 합니다: mlkit-basic-x4=$bad", e.message) // 문구 그대로
            }
        }
        assertEquals(60.0, cfg(sys, 60.0).maxChunkFor(sys), 0.0) // 상한 60 허용
        assertEquals(0.0, cfg(spec(), 0.0).maxChunkFor(spec()), 0.0) // 일반 모델 0(무제한)은 기존대로 허용
    }

    // change(add)-hyungchul-2026-10-11-21:50
    /**
     * 목적: 시스템 엔진 CSV attempts 열 (최종 리뷰 수정)
     * 기능: 시스템 엔진 OK 행은 조각 3개 중 1개가 재시도면 "4"(조각 수 + 재시도 조각 수), 세션 기록이 없는 조각이 있으면 빈 값, ERROR 행은 빈 값
     *       (실패한 조각의 시도 수를 알 수 없음), 조각이 없는 NO_MATCH 는 "0"(세션을 열지 않음). 1차 엔진은 그대로 "1".
     * 입력: 없음
     * 출력: 실패 시 AssertionError
     * 리턴: Unit
     */
    @Test
    fun systemEngineAttemptsCountsRetriedSessions() {
        val sys = SystemCatalog.all.first { it.id == "mlkit-basic-x1" } // 시스템 엔진 x1
        val sysCombo = Combo(sys, VadPresets.byId("SILERO"), 30.0, ChunkMode.MERGE) // 시스템 조합
        val it1 = item("01") // 항목
        /** 목적: 세션 기록이 있는 조각 / 입력: i(순번), retried(재시도 여부 또는 null=기록 없음) / 리턴: ChunkJson */
        fun chunk(i: Int, retried: Boolean?): ChunkJson = ChunkJson(i, listOf(SpanJson(i.toDouble(), i + 1.0, i.toDouble())), "NONE", "말", "말", 0.1, retried?.let { SessionJson(i + 1, 32_000L, 5L, it) }) // 조각
        val ok = processor.failed(sysCombo, it1, ErrorJson("X", "Y", "z"), null).copy(status = "OK", error = null, chunks = listOf(chunk(0, false), chunk(1, true), chunk(2, false))) // OK detail(2번째 조각 재시도)
        /** 목적: attempts 열 값 / 입력: c(조합), d(detail) / 리턴: 값(null 은 빈 값) */
        fun attempts(c: Combo, d: DetailJson): String = CsvRowBuilder.build("20261011_215000", c, it1, d, device, 4)["attempts"].orEmpty() // 열 값
        assertEquals("4", attempts(sysCombo, ok)) // 3 조각 + 재시도 1
        assertEquals("", attempts(sysCombo, ok.copy(chunks = listOf(chunk(0, false), chunk(1, null))))) // 세션 기록 없음 → 계산 불가
        assertEquals("", attempts(sysCombo, ok.copy(status = "ERROR", error = ErrorJson("TRANSCRIBE", "X", "y")))) // 오류 행 → 계산 불가
        assertEquals("0", attempts(sysCombo, ok.copy(status = "NO_MATCH", chunks = emptyList()))) // 조각 없음 → 세션 0
        assertEquals("1", attempts(combo, ok)) // 1차 엔진은 그대로 1
    }
}
