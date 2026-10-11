// 파일명: ui/results/ResultsViewModelTest.kt
// 목적: 결과 화면 ViewModel(ResultsViewModel)과 그 도우미의 run 목록·조합별 집계·파일 목록·미리보기·manifest 손상 표시·이어하기 설정 복원을 검증
// 기능: 임시 output_stt 트리에 manifest(정상·미완료·손상)와 _results/<lang>/<combo>/result.csv(따옴표 안 쉼표·줄바꿈·큰따옴표 포함)를
//       실제 ResultCsvWriter 로 쓰고, 목록 순서(최신 먼저)·집계·드릴다운·미리보기·이어하기 RunConfig 복원과 복원 실패 사유를 확인한다.
//       change(add)-hyungchul-2026-10-11-03:59 R42: refresh 중 result.csv 읽기 실패의 한국어 메시지(크래시 없음), 실행 중 run 의 "실행 중" 판정·이어하기 거부,
//       실행 중 run 의 CRLF 로 끝나지 않은 마지막 CSV 레코드 제외 집계, 내보내기 작업의 완료·실패 메시지를 확인한다.
//       change(add)-hyungchul-2026-10-11-12:51 (최종 리뷰 I-1) CRASH_OR_KILLED 행 집계와 완료 run 의 "CRASH_OR_KILLED 항목 다시 실행"(설정 복원·시작·미완료 거부)을 확인한다.
//       change(add)-hyungchul-2026-10-11-20:15 (2차 작업 9) USER 프리셋 이어하기: 지워진 USER 프리셋이면 "삭제된 USER 프리셋입니다" 사유로 거부하고 시작하지 않으며, 레지스트리에 있으면 그 프리셋으로 시작한다.
package com.cowork.stt_ondevice.ui.results // 결과 화면 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.core.storage.SafCopyResult // change(add)-hyungchul-2026-10-11-03:59 SAF 복사 결과
import com.cowork.stt_ondevice.model.ModelFile // 모델 파일 정보
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소
import com.cowork.stt_ondevice.model.sha256Hex // SHA-256 도우미
import com.cowork.stt_ondevice.model.testSpec // 테스트용 ModelSpec
import com.cowork.stt_ondevice.output.DeviceInfoJson // manifest 기기 정보
import com.cowork.stt_ondevice.output.InputEntry // manifest 입력 항목
import com.cowork.stt_ondevice.output.ModelEntry // manifest 모델 항목
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.PresetEntry // manifest 프리셋 항목
import com.cowork.stt_ondevice.output.ResultCsvWriter // 결과 CSV 쓰기
import com.cowork.stt_ondevice.output.RunManifest // 실행 manifest
import com.cowork.stt_ondevice.runner.RunConfig // 실행 설정
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.vad.VadPreset // change(add)-hyungchul-2026-10-11-20:15 USER 프리셋 흉내 타입
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 목록
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.ExperimentalCoroutinesApi // 테스트 API 표시
import kotlinx.coroutines.flow.MutableStateFlow // change(add)-hyungchul-2026-10-11-03:59 실행 중 runId 흉내
import kotlinx.coroutines.test.UnconfinedTestDispatcher // 즉시 실행 테스트 디스패처
import kotlinx.coroutines.test.resetMain // Main 복구
import kotlinx.coroutines.test.setMain // Main 교체
import org.junit.After // 각 테스트 후
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // 거짓
import org.junit.Assert.assertNull // null
import org.junit.Assert.assertTrue // 참
import org.junit.Before // 각 테스트 전
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: ResultsViewModel 회귀 방지
 * 기능: 파일 머리말의 항목을 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResultsViewModelTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val dispatcher = UnconfinedTestDispatcher() // 즉시 실행 디스패처
    private lateinit var dirs: AppDirs // 앱 폴더
    private lateinit var store: ModelStore // 모델 저장소

    private val bytes = "model".toByteArray() // 합성 모델 파일 내용
    private val files = listOf(ModelFile("m.bin", bytes.size.toLong(), sha256Hex(bytes), "model")) // 합성 모델 파일
    private val m1 = testSpec("m1", emptyList(), files).copy(languages = setOf("ko", "en"), maxChunkSec = 30.0) // 설치된 모델
    private val m2 = testSpec("m2", emptyList(), files).copy(languages = setOf("ko", "en"), maxChunkSec = 30.0) // 미설치 모델
    private val tricky = "안녕, \"세계\"\n둘째 줄" // 쉼표·큰따옴표·줄바꿈이 섞인 전사 텍스트

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher) // viewModelScope 용 Main 교체
        dirs = AppDirs(tmp.newFolder("root")) // 루트
        store = ModelStore(dirs) // 저장소
        val dir = store.modelDir(m1) // m1 폴더
        File(dir, "m.bin").apply { parentFile!!.mkdirs(); writeBytes(bytes) } // 파일
        store.writeMarker(m1, dir) // m1 READY
    }

    @After
    fun tearDown() = Dispatchers.resetMain() // Main 복구

    /** 목적: 입력 wav 만들기 / 기능: input_sound/<lang>/<rel>/<stem>.wav 빈 파일(내용은 읽지 않음) / 입력: lang, rel, stem / 리턴: InputItem */
    private fun input(lang: String, rel: String, stem: String): InputItem {
        val f = File(File(File(dirs.inputSound, lang), rel), "$stem.wav").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(44)) } // 파일 생성
        return InputItem(lang, rel, stem, f) // 항목
    }

    /**
     * 목적: manifest 쓰기
     * 기능: 주어진 값으로 run_manifest.json 을 만든다.
     * 입력: runId, finishedAt(종료 시각 또는 null), models, presetIds, inputs(기록할 입력 또는 null), override, unsupported
     * 출력: 파일 생성
     * 리턴: 없음
     */
    private fun writeManifest(
        runId: String, finishedAt: String?, models: List<ModelSpec> = listOf(m1), presetIds: List<String> = listOf("NONE", "WEBRTC_ANDROIDVAD"),
        inputs: List<InputItem>? = emptyList(), override: Map<String, Double> = emptyMap(), unsupported: List<String> = emptyList(),
        presetList: List<VadPreset>? = null, // 프리셋 객체로 직접 지정(USER 프리셋용, 없으면 presetIds) // change(add)-hyungchul-2026-10-11-20:15
    ) {
        RunManifest(
            runId = runId, appVersion = "1.0 (1)", startedAt = "2026-10-09T13:15:00", // 식별
            device = DeviceInfoJson("samsung", "SM-S908N", null, "16", 36, "arm64-v8a", 11000L, "1.0 (1)"), // 기기
            models = models.map { ModelEntry.from(it, override[it.id] ?: it.maxChunkSec) }, // 모델
            presets = (presetList ?: presetIds.map { VadPresets.byId(it) }).map { PresetEntry.from(it) }, // 프리셋 // change(add)-hyungchul-2026-10-11-20:15
            chunkMode = ChunkMode.INDIVIDUAL.name, maxChunkOverride = override, threads = 3, resume = false, // 옵션
            inputRoot = dirs.inputSound.absolutePath, unsupported = unsupported, // 입력 루트·미지원
            finishedAt = finishedAt, inputs = inputs?.map { InputEntry.from(it) }, // 종료·입력
        ).write(OutputLayout.manifest(dirs.runDir(runId))) // 저장
    }

    /** 목적: 결과 CSV 행 추가 / 기능: 실제 ResultCsvWriter 로 rel_dir·file·status·text 를 쓴다 / 입력: runId, lang, combo, rel, file, status, text / 리턴: 없음 */
    private fun csvRow(runId: String, lang: String, combo: String, rel: String, file: String, status: String, text: String) =
        ResultCsvWriter(OutputLayout.resultCsv(dirs.runDir(runId), lang, combo)).append(mapOf("run_id" to runId, "rel_dir" to rel, "file" to file, "status" to status, "text" to text)) // 행 추가

    /** 목적: ViewModel 만들기 / 기능: 임시 output 루트·합성 카탈로그·실제 프리셋·기록용 시작 함수 / 입력: running(change(add)-hyungchul-2026-10-11-03:59 실행 중 runId 흐름), presets(change(add)-hyungchul-2026-10-11-20:15 프리셋 공급 함수), start(시작 함수 — 뒤따르는 람다) / 리턴: ResultsViewModel */
    private fun newVm(running: MutableStateFlow<String?> = MutableStateFlow(null), presets: () -> List<VadPreset> = { VadPresets.all }, start: (RunConfig) -> String? = { null }): ResultsViewModel =
        ResultsViewModel(dirs.output, store, listOf(m1, m2), presets, start, dispatcher, runningRunId = running) // 주입 — change(add)-hyungchul-2026-10-11-03:59 실행 중 runId

    /** 목적: 공통 트리 만들기 / 기능: 완료 run A, 미완료 run B, 손상 run C 와 A 의 결과 CSV·txt·ERROR.txt / 입력: 없음 / 리턴: 없음 */
    private fun buildTree() {
        val a = "20261009_131500" // 완료 run
        writeManifest(a, "2026-10-09T13:20:00", unsupported = listOf("test_m1_NONE|en")) // A manifest
        val combo = "test_m1_NONE" // 조합명
        csvRow(a, "kr", combo, "customer", "01.wav", "OK", tricky) // OK(까다로운 텍스트)
        csvRow(a, "kr", combo, "customer", "02.wav", "NO_MATCH", "") // NO_MATCH
        csvRow(a, "kr", combo, "", "03.wav", "ERROR", "") // ERROR(최상위 폴더)
        csvRow(a, "kr", "test_m1_WEBRTC_ANDROIDVAD", "customer", "01.wav", "OK", "x") // 다른 조합
        val runDir = dirs.runDir(a) // 실행 폴더
        File(runDir, "kr/$combo/customer/01.txt").apply { parentFile!!.mkdirs(); writeText("[전체 텍스트] (엔진: test m1 / VAD: NONE / 조각 1개)\n본문\n") } // 전사 txt
        File(runDir, "kr/$combo/03.ERROR.txt").apply { parentFile!!.mkdirs(); writeText("stage=TRANSCRIBE\ncode=X\nmessage=실패") } // 오류 txt
        writeManifest("20261010_090000", null) // B: 미완료
        File(dirs.runDir("20261008_000000"), "run_manifest.json").apply { parentFile!!.mkdirs(); writeText("{ 깨진") } // C: 손상
    }

    /**
     * 목적: run 목록·집계·손상 표시
     * 기능: 최신 먼저(B, A, C), A 는 완료·조합 2개·OK/NO_MATCH/ERROR 1/1/1(쉼표·줄바꿈 텍스트가 행을 깨지 않음)·미지원 목록,
     *       B 는 미완료·이어하기 가능, C 는 manifest 손상·이어하기 불가.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun listsRunsNewestFirstWithComboCounts() {
        buildTree() // 트리
        val runs = newVm().state.value.runs // 목록
        assertEquals(listOf("20261010_090000", "20261009_131500", "20261008_000000"), runs.map { it.runId }) // 최신 먼저
        val a = runs[1] // A
        assertEquals(ManifestStatus.OK, a.manifestStatus) // 정상
        assertTrue(a.finished) // 완료
        assertFalse(a.resumable) // 완료 run 은 이어하기 없음
        assertEquals("2026-10-09T13:20:00", a.finishedAt) // 종료 시각
        assertEquals(listOf("test_m1_NONE|en"), a.unsupported) // 미지원
        val none = a.combos.first { it.combo == "test_m1_NONE" } // 조합 1
        assertEquals(listOf(1, 1, 1, 0), listOf(none.ok, none.noMatch, none.error, none.other)) // 집계
        assertEquals("kr", none.lang) // 언어
        assertEquals(1, a.combos.first { it.combo == "test_m1_WEBRTC_ANDROIDVAD" }.ok) // 조합 2
        assertFalse(runs[0].finished) // B 미완료
        assertTrue(runs[0].resumable) // B 이어하기 가능
        assertEquals(ManifestStatus.CORRUPT, runs[2].manifestStatus) // C 손상
        assertEquals("manifest 손상", runs[2].manifestStatus.label) // 한국어 라벨
        assertFalse(runs[2].resumable) // 이어하기 불가
    }

    /**
     * 목적: 드릴다운(조합 → 파일 → 미리보기)
     * 기능: openRun → openCombo 로 파일 3개(rel_dir/file/status) 가 CSV 순서로 보이고, OK 파일은 txt, ERROR 파일은 ERROR.txt 내용, NO_MATCH 의 txt 가 없으면 "파일 없음" 문구.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun drillDownShowsFilesAndPreview() {
        buildTree() // 트리
        val vm = newVm() // ViewModel
        vm.openRun("20261009_131500") // run 열기
        assertEquals("20261009_131500", vm.state.value.openRunId) // 열린 run
        val combo = vm.state.value.runs.first { it.runId == "20261009_131500" }.combos.first { it.combo == "test_m1_NONE" } // 조합
        vm.openCombo(combo) // 조합 열기
        val files = vm.state.value.files // 파일 목록
        assertEquals(listOf("01.wav" to "OK", "02.wav" to "NO_MATCH", "03.wav" to "ERROR"), files.map { it.file to it.status }) // CSV 순서
        assertEquals("customer", files[0].relDir) // 상대 폴더
        vm.openPreview(files[0]) // OK 미리보기
        assertTrue(vm.state.value.preview!!.text.contains("본문")) // txt 내용
        vm.openPreview(files[2]) // ERROR 미리보기
        assertTrue(vm.state.value.preview!!.text.contains("message=실패")) // ERROR.txt 내용
        vm.openPreview(files[1]) // txt 없는 NO_MATCH
        assertTrue(vm.state.value.preview!!.text.contains("파일 없음")) // 안내
        vm.closePreview() // 닫기
        assertNull(vm.state.value.preview) // 닫힘
        vm.closeCombo() // 조합 닫기
        assertNull(vm.state.value.openCombo) // 닫힘
        assertTrue(vm.state.value.files.isEmpty()) // 파일 목록 비움
    }

    /**
     * 목적: 따옴표 안 쉼표·줄바꿈 텍스트의 CSV 행 해석
     * 기능: readComboFiles 가 rel_dir/file/status 를 정확히 읽고(텍스트 줄바꿈이 행을 나누지 않음) 행 수가 3 이다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun csvWithQuotedCommasAndNewlines() {
        buildTree() // 트리
        val rows = ResultsViewModel.readComboFiles(dirs.runDir("20261009_131500"), "kr", "test_m1_NONE") // 파일 목록
        assertEquals(3, rows.size) // 3행
        assertEquals("OK", rows[0].status) // 첫 행 상태
        assertEquals("", rows[2].relDir) // 최상위 폴더
    }

    /**
     * 목적: 이어하기 설정 복원
     * 기능: 미완료 manifest(모델 m1, 프리셋 NONE·WEBRTC_ANDROIDVAD, INDIVIDUAL, threads 3, override 12.5, 입력 2개) → 같은 runId·resume=true 의 RunConfig.
     *       VM.resume 은 그 설정으로 시작 함수를 부르고, 시작 실패 사유는 메시지로 알린다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun resumeConfigRebuild() {
        val items = listOf(input("kr", "customer", "01"), input("en", "Customer_Service", "01")) // 입력 2개
        writeManifest("20261010_090000", null, inputs = items, override = mapOf("m1" to 12.5)) // 미완료 manifest
        val started = mutableListOf<RunConfig>() // 시작 기록
        val vm = newVm { started += it; null } // 시작 성공 흉내
        val plan = vm.buildResumeConfig("20261010_090000") // 복원
        assertTrue(plan.toString(), plan is ResumePlan.Ready) // 성공
        val cfg = (plan as ResumePlan.Ready).config // 설정
        assertEquals("20261010_090000", cfg.runId) // 같은 runId
        assertTrue(cfg.resume) // 이어하기
        assertEquals(listOf("m1"), cfg.models.map { it.id }) // 모델
        assertEquals(listOf("NONE", "WEBRTC_ANDROIDVAD"), cfg.presets.map { it.id }) // 프리셋 순서
        assertEquals(ChunkMode.INDIVIDUAL, cfg.chunkMode) // 묶기 방식
        assertEquals(3, cfg.threads) // 스레드
        assertEquals(mapOf("m1" to 12.5), cfg.maxChunkOverride) // 덮어쓰기
        assertEquals(items, cfg.items) // 입력 항목(파일 경로 포함)
        vm.resume("20261010_090000") // 이어하기 실행
        assertEquals(listOf(cfg), started) // 같은 설정으로 시작
        assertEquals("20261010_090000", vm.state.value.resumeStarted) // 진행 탭 이동 신호
        vm.consumeResumeStarted() // 신호 소비
        assertNull(vm.state.value.resumeStarted) // 비워짐
        val failing = newVm { "이미 실행 중" } // 시작 실패 흉내
        failing.resume("20261010_090000") // 시도
        assertTrue(failing.state.value.message!!.contains("이미 실행 중")) // 사유 표시
        assertNull(failing.state.value.resumeStarted) // 이동 신호 없음
        failing.consumeMessage() // 메시지 소비
        assertNull(failing.state.value.message) // 비워짐
    }

    /**
     * 목적: 이어하기 복원 실패 사유
     * 기능: 완료 run, 손상 manifest, 입력 미기록, 입력 파일 없음, 카탈로그에 없는 모델, 미설치 모델, 알 수 없는 프리셋은 Error(한국어)이고 시작 함수를 부르지 않는다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun resumeConfigErrors() {
        buildTree() // 완료 A·손상 C
        val started = mutableListOf<RunConfig>() // 시작 기록
        val vm = newVm { started += it; null } // ViewModel
        val item = input("kr", "customer", "01") // 입력
        fun err(runId: String): String = (vm.buildResumeConfig(runId) as ResumePlan.Error).message // 실패 사유
        assertTrue(err("20261009_131500").contains("완료")) // 완료 run
        assertTrue(err("20261008_000000").contains("manifest")) // 손상
        writeManifest("20261011_000001", null, inputs = null) // 입력 미기록
        assertTrue(err("20261011_000001").contains("입력")) // 사유
        writeManifest("20261011_000002", null, inputs = listOf(item)) // 정상 입력
        item.file.delete() // 그 파일 삭제
        assertTrue(err("20261011_000002").contains("입력 파일이 없습니다")) // 사유
        val again = input("kr", "customer", "01") // 다시 생성
        writeManifest("20261011_000003", null, models = listOf(m2), inputs = listOf(again)) // 미설치 모델
        assertTrue(err("20261011_000003").contains("미설치")) // 사유
        writeManifest("20261011_000004", null, models = listOf(testSpec("ghost", emptyList(), files)), inputs = listOf(again)) // 카탈로그 밖 모델
        assertTrue(err("20261011_000004").contains("ghost")) // 사유
        val bad = File(dirs.runDir("20261011_000005"), "run_manifest.json") // 알 수 없는 프리셋 manifest
        writeManifest("20261011_000005", null, inputs = listOf(again)) // 먼저 정상 작성
        bad.writeText(bad.readText().replace("\"WEBRTC_ANDROIDVAD\"", "\"NO_SUCH_PRESET\"")) // 프리셋 id 바꾸기
        assertTrue(err("20261011_000005").contains("NO_SUCH_PRESET")) // 사유
        vm.resume("20261011_000003") // 실패하는 이어하기
        assertTrue(started.isEmpty()) // 시작하지 않음
        assertTrue(vm.state.value.message!!.contains("미설치")) // 사유를 메시지로 표시
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: R42(1) — refresh 중 result.csv 읽기 실패
     * 기능: 조합을 연 뒤 그 result.csv 를 읽을 수 없게 만들고 refresh 하면 크래시 없이 "result.csv 를 읽지 못했습니다" 한국어 메시지가 나오고 파일 목록은 빈다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun refreshCsvReadFailureShowsMessage() {
        buildTree() // 트리
        val vm = newVm() // ViewModel
        vm.openRun("20261009_131500") // run 열기
        val combo = vm.state.value.runs.first { it.runId == "20261009_131500" }.combos.first { it.combo == "test_m1_NONE" } // 조합
        vm.openCombo(combo) // 조합 열기
        assertEquals(3, vm.state.value.files.size) // 처음엔 읽힘
        val csv = OutputLayout.resultCsv(dirs.runDir("20261009_131500"), "kr", "test_m1_NONE") // 그 CSV
        assertTrue(csv.setReadable(false)) // 읽기 권한 제거(읽기 시 IOException)
        try {
            vm.refresh() // 다시 읽기
            val msg = vm.state.value.message // 안내
            assertTrue(msg.toString(), msg != null && msg.contains("result.csv 를 읽지 못했습니다")) // 한국어 메시지
            assertTrue(vm.state.value.files.isEmpty()) // 파일 목록 비움
            assertFalse(vm.state.value.loading) // 읽기 끝남
        } finally {
            csv.setReadable(true) // 권한 복구(임시 폴더 정리용)
        }
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: R42(2) — 실행 중인 run
     * 기능: RunService 가 미완료 run B 를 실행 중이면 state.runningRunId = B, isRunning(B) 참, resumable(B) 거짓(이어하기 숨김), resume(B) 는 시작 함수를 부르지 않고 사유를 보인다.
     *       실행이 끝나 흐름이 null 이 되면 다시 이어하기 가능으로 돌아온다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun runningRunHidesResume() {
        val items = listOf(input("kr", "customer", "01")) // 입력
        writeManifest("20261010_090000", null, inputs = items) // 미완료 run B
        val running = MutableStateFlow<String?>("20261010_090000") // B 실행 중
        val started = mutableListOf<RunConfig>() // 시작 기록
        val vm = newVm(running) { started += it; null } // ViewModel
        val s = vm.state.value // 상태
        val b = s.runs.single() // run B
        assertEquals("20261010_090000", s.runningRunId) // 실행 중 runId
        assertTrue(s.isRunning(b.runId)) // 실행 중
        assertTrue(b.resumable) // manifest 상으로는 미완료
        assertFalse(s.resumable(b)) // 화면에서는 이어하기 숨김
        vm.resume("20261010_090000") // 이어하기 시도
        assertTrue(started.isEmpty()) // 시작하지 않음
        assertTrue(vm.state.value.message.toString(), vm.state.value.message!!.contains("실행 중")) // 사유
        running.value = null // 실행 끝
        assertNull(vm.state.value.runningRunId) // 반영
        assertTrue(vm.state.value.resumable(vm.state.value.runs.single())) // 다시 이어하기 가능
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: R42(4) — 실행 중 run 의 반쯤 쓴 마지막 CSV 레코드
     * 기능: result.csv 끝에 CRLF 로 끝나지 않은 레코드를 붙이면, 그 run 이 실행 중일 때는 집계·파일 목록에서 빼고(OK 1·NO_MATCH 1·ERROR 1·기타 0, 파일 3),
     *       실행 중이 아니면 기존처럼 레코드로 센다(기타 1, 파일 4).
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun runningRunIgnoresUnterminatedCsvTail() {
        buildTree() // 트리
        val runId = "20261009_131500" // 대상 run
        OutputLayout.resultCsv(dirs.runDir(runId), "kr", "test_m1_NONE").appendText("$runId,kr,whisper", Charsets.UTF_8) // CRLF 없는 꼬리(쓰는 중 흉내)
        val running = MutableStateFlow<String?>(runId) // 실행 중
        val vm = newVm(running = running) // ViewModel
        val c = vm.state.value.runs.first { it.runId == runId }.combos.first { it.combo == "test_m1_NONE" } // 조합
        assertEquals(listOf(1, 1, 1, 0), listOf(c.ok, c.noMatch, c.error, c.other)) // 꼬리 제외
        vm.openCombo(c) // 파일 목록
        assertEquals(3, vm.state.value.files.size) // 꼬리 제외
        running.value = null // 실행 끝(꼬리는 그대로 남음)
        val c2 = vm.state.value.runs.first { it.runId == runId }.combos.first { it.combo == "test_m1_NONE" } // 다시 읽은 조합
        assertEquals(1, c2.other) // 실행 중이 아니면 레코드로 셈
        assertEquals(4, vm.state.value.files.size) // 파일 목록도 4
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: 내보내기 작업 상태와 메시지
     * 기능: export(runId, copy) 는 run 폴더를 copy 에 넘기고, 끝나면 "내보내기 완료" 와 대상 폴더 이름·파일 수를 보이며 exportingRunId 를 비운다.
     *       copy 가 IOException 을 던지면 "내보내기 실패" 사유를 보인다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun exportReportsResult() {
        buildTree() // 트리
        val vm = newVm() // ViewModel
        var got: File? = null // copy 에 넘어온 폴더
        vm.export("20261009_131500") { dir -> got = dir; SafCopyResult(copied = 5, skipped = 0, bytes = 100, target = "20261009_131500_1") } // 내보내기
        assertEquals(dirs.runDir("20261009_131500"), got) // run 폴더
        val msg = vm.state.value.message!! // 안내
        assertTrue(msg, msg.contains("내보내기 완료") && msg.contains("20261009_131500_1") && msg.contains("5")) // 결과
        assertNull(vm.state.value.exportingRunId) // 끝남
        vm.consumeMessage() // 소비
        vm.export("20261009_131500") { throw java.io.IOException("공간 부족") } // 실패
        assertTrue(vm.state.value.message!!.contains("내보내기 실패: 공간 부족")) // 사유
        assertTrue(vm.state.value.message!!.contains("PC 평가에 쓰지 마세요")) // change(add)-hyungchul-2026-10-11-04:39 부분 복사 경고
        assertNull(vm.state.value.exportingRunId) // 끝남
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: CRASH_OR_KILLED 항목 다시 실행(I-1)
     * 기능: 완료 run 의 result.csv 에 CRASH_OR_KILLED 행 1개·일반 ERROR 행 1개·OK 행 1개가 있으면 crashed=1, 다시 실행 버튼 표시(이어하기는 숨김).
     *       rerunCrashed 는 같은 설정의 RunConfig(resume=true, rerunCrashed=true)로 시작하고 진행 탭 이동 신호를 낸다. 미완료 run 은 다시 실행 복원이 거부되고,
     *       크래시 행이 없는 완료 run 은 버튼이 없다. 실행 중이면 숨긴다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun rerunCrashedForFinishedRun() {
        val items = listOf(input("kr", "customer", "01"), input("kr", "customer", "02"), input("kr", "customer", "03")) // 입력 3개
        val runId = "20261011_125100" // 완료 run
        writeManifest(runId, "2026-10-11T13:00:00", inputs = items) // 완료 manifest
        val w = ResultCsvWriter(OutputLayout.resultCsv(dirs.runDir(runId), "kr", "test_m1_NONE")) // 조합 CSV
        w.append(mapOf("rel_dir" to "customer", "file" to "01.wav", "status" to "OK", "note" to "vad=NONE")) // 정상
        w.append(mapOf("rel_dir" to "customer", "file" to "02.wav", "status" to "ERROR", "note" to "vad=NONE;stage=TRANSCRIBE;code=CRASH_OR_KILLED")) // 크래시
        w.append(mapOf("rel_dir" to "customer", "file" to "03.wav", "status" to "ERROR", "note" to "vad=NONE;stage=MODEL_LOAD;code=LOAD_FAILED")) // 일반 오류
        val started = mutableListOf<RunConfig>() // 시작 기록
        val running = MutableStateFlow<String?>(null) // 실행 중 runId
        val vm = newVm(running) { started += it; null } // ViewModel
        val run = vm.state.value.runs.single() // run
        assertEquals(1, run.combos.single().crashed) // 크래시 행 1개
        assertEquals(2, run.combos.single().error) // ERROR 는 2개
        assertEquals(1, run.crashed) // run 합계
        assertTrue(run.rerunnable) // 다시 실행 가능
        assertTrue(vm.state.value.rerunnable(run)) // 화면에도 표시
        assertFalse(vm.state.value.resumable(run)) // 이어하기는 숨김(완료)
        val plan = vm.buildResumeConfig(runId, rerunCrashed = true) // 다시 실행 설정 복원
        assertTrue(plan.toString(), plan is ResumePlan.Ready) // 성공
        val cfg = (plan as ResumePlan.Ready).config // 설정
        assertTrue(cfg.resume && cfg.rerunCrashed) // 이어하기 + 다시 실행
        assertEquals(items, cfg.items) // 같은 입력
        vm.rerunCrashed(runId) // 다시 실행
        assertEquals(listOf(cfg), started) // 같은 설정으로 시작
        assertEquals(runId, vm.state.value.resumeStarted) // 진행 탭 이동 신호
        assertTrue(vm.state.value.message!!.contains("다시 실행을 시작했습니다")) // 안내
        running.value = runId // 실행 중이 되면
        assertFalse(vm.state.value.rerunnable(vm.state.value.runs.single())) // 버튼 숨김
        running.value = null // 실행 끝
        writeManifest("20261011_125101", null, inputs = items) // 미완료 run
        val err = vm.buildResumeConfig("20261011_125101", rerunCrashed = true) // 미완료 run 다시 실행 복원
        assertTrue(err.toString(), err is ResumePlan.Error && err.message.contains("끝나지 않은")) // 거부
        writeManifest("20261011_125102", "2026-10-11T13:10:00", inputs = items) // 크래시 없는 완료 run
        csvRow("20261011_125102", "kr", "test_m1_NONE", "customer", "01.wav", "OK", "x") // 정상 행만
        vm.refresh() // 다시 읽기
        assertFalse(vm.state.value.runs.first { it.runId == "20261011_125102" }.rerunnable) // 버튼 없음
    }
    // change(add)-hyungchul-2026-10-11-20:15
    /** 목적: WEBRTC_ANDROIDVAD 를 복사한 USER 프리셋 흉내(모델 불필요 계열이라 VAD 모델 설치 없이 이어하기 검사 가능) / 입력: 없음 / 리턴: VadPreset */
    private fun userPreset(): VadPreset {
        val base = VadPresets.byId("WEBRTC_ANDROIDVAD") // 기준
        return VadPreset("USER_x", base.family, base.params, "USER(기준 ${base.id})", "") // 같은 파라미터의 USER 프리셋
    }

    // change(add)-hyungchul-2026-10-11-20:15
    /**
     * 목적: 이어하기 중 지워진 USER 프리셋 (brief 작업 9 / 검토 중점 5)
     * 기능: manifest 가 USER_x 를 쓰는데 레지스트리(공급 함수)에 없으면 기본 프리셋으로 대체하지 않고 정해진 문구로 거부하며 시작 함수를 부르지 않는다.
     *       기본 프리셋 id 를 못 찾는 경우는 기존 문구("알 수 없는 VAD 프리셋입니다")를 그대로 쓴다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun resumeWithDeletedUserPresetIsRejected() {
        val item = input("kr", "customer", "01") // 입력
        writeManifest("20261011_120000", null, inputs = listOf(item), presetList = listOf(userPreset())) // USER_x 를 쓰는 미완료 run
        val started = mutableListOf<RunConfig>() // 시작 기록
        val vm = newVm(start = { started += it; null }) // 레지스트리(기본 26종)에 USER_x 없음
        val plan = vm.buildResumeConfig("20261011_120000") // 복원
        assertEquals("삭제된 USER 프리셋입니다: USER_x — 이 실행은 이어할 수 없습니다", (plan as ResumePlan.Error).message) // 정해진 문구
        vm.resume("20261011_120000") // 이어하기 실행
        assertTrue(started.isEmpty()) // 시작하지 않음
        assertEquals("이어하기 불가: 삭제된 USER 프리셋입니다: USER_x — 이 실행은 이어할 수 없습니다", vm.state.value.message) // 사유를 메시지로 표시
        writeManifest("20261011_120001", null, inputs = listOf(item), presetIds = listOf("NONE", "WEBRTC_ANDROIDVAD")) // 기본 프리셋만
        val raw = File(dirs.runDir("20261011_120001"), "run_manifest.json") // manifest 파일
        raw.writeText(raw.readText().replace("\"WEBRTC_ANDROIDVAD\"", "\"NO_SUCH_PRESET\"")) // 기본 프리셋 id 를 없는 값으로
        assertTrue((vm.buildResumeConfig("20261011_120001") as ResumePlan.Error).message.startsWith("알 수 없는 VAD 프리셋입니다: NO_SUCH_PRESET")) // 기존 문구 유지
    }

    // change(add)-hyungchul-2026-10-11-20:15
    /**
     * 목적: 레지스트리에 있는 USER 프리셋으로 이어하기
     * 기능: 공급 함수가 USER_x 를 포함하면 복원된 설정의 프리셋에 그 USER 프리셋이 들어가고 시작 함수가 그 설정으로 불린다.
     *       공급 함수는 이어하기 시점마다 다시 불린다(처음엔 없다가 나중에 생긴 프리셋도 찾는다).
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun resumeWithUserPresetStarts() {
        val item = input("kr", "customer", "01") // 입력
        val user = userPreset() // USER 프리셋
        writeManifest("20261011_120000", null, inputs = listOf(item), presetList = listOf(VadPresets.byId("NONE"), user)) // NONE + USER_x
        var registry: List<VadPreset> = VadPresets.all // 지금 시점 목록(처음엔 USER 없음)
        val started = mutableListOf<RunConfig>() // 시작 기록
        val vm = newVm(presets = { registry }, start = { started += it; null }) // 공급 함수로 주입
        assertTrue(vm.buildResumeConfig("20261011_120000") is ResumePlan.Error) // 아직 없음 → 거부
        registry = VadPresets.all + user // 나중에 생김
        vm.resume("20261011_120000") // 이어하기
        assertEquals(1, started.size) // 시작함
        assertEquals(listOf("NONE", "USER_x"), started[0].presets.map { it.id }) // 설정 프리셋에 USER 프리셋
        assertEquals(user, started[0].presets[1]) // 같은 객체 값(파라미터 동일)
        assertTrue(started[0].resume) // 이어하기
        assertEquals("20261011_120000", vm.state.value.resumeStarted) // 진행 탭 이동 신호
    }
}
