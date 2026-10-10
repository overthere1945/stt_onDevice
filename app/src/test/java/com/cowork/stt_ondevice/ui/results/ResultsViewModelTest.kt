// 파일명: ui/results/ResultsViewModelTest.kt
// 목적: 결과 화면 ViewModel(ResultsViewModel)과 그 도우미의 run 목록·조합별 집계·파일 목록·미리보기·manifest 손상 표시·이어하기 설정 복원을 검증
// 기능: 임시 output_stt 트리에 manifest(정상·미완료·손상)와 _results/<lang>/<combo>/result.csv(따옴표 안 쉼표·줄바꿈·큰따옴표 포함)를
//       실제 ResultCsvWriter 로 쓰고, 목록 순서(최신 먼저)·집계·드릴다운·미리보기·이어하기 RunConfig 복원과 복원 실패 사유를 확인한다.
package com.cowork.stt_ondevice.ui.results // 결과 화면 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
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
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 목록
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.ExperimentalCoroutinesApi // 테스트 API 표시
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
    ) {
        RunManifest(
            runId = runId, appVersion = "1.0 (1)", startedAt = "2026-10-09T13:15:00", // 식별
            device = DeviceInfoJson("samsung", "SM-S908N", null, "16", 36, "arm64-v8a", 11000L, "1.0 (1)"), // 기기
            models = models.map { ModelEntry.from(it, override[it.id] ?: it.maxChunkSec) }, // 모델
            presets = presetIds.map { PresetEntry.from(VadPresets.byId(it)) }, // 프리셋
            chunkMode = ChunkMode.INDIVIDUAL.name, maxChunkOverride = override, threads = 3, resume = false, // 옵션
            inputRoot = dirs.inputSound.absolutePath, unsupported = unsupported, // 입력 루트·미지원
            finishedAt = finishedAt, inputs = inputs?.map { InputEntry.from(it) }, // 종료·입력
        ).write(OutputLayout.manifest(dirs.runDir(runId))) // 저장
    }

    /** 목적: 결과 CSV 행 추가 / 기능: 실제 ResultCsvWriter 로 rel_dir·file·status·text 를 쓴다 / 입력: runId, lang, combo, rel, file, status, text / 리턴: 없음 */
    private fun csvRow(runId: String, lang: String, combo: String, rel: String, file: String, status: String, text: String) =
        ResultCsvWriter(OutputLayout.resultCsv(dirs.runDir(runId), lang, combo)).append(mapOf("run_id" to runId, "rel_dir" to rel, "file" to file, "status" to status, "text" to text)) // 행 추가

    /** 목적: ViewModel 만들기 / 기능: 임시 output 루트·합성 카탈로그·실제 프리셋·기록용 시작 함수 / 입력: start(시작 함수) / 리턴: ResultsViewModel */
    private fun newVm(start: (RunConfig) -> String? = { null }): ResultsViewModel =
        ResultsViewModel(dirs.output, store, listOf(m1, m2), VadPresets.all, start, dispatcher) // 주입

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
}
