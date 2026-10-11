// 파일명: ui/run/RunSetupViewModelTest.kt
// 목적: 실행 설정 화면 ViewModel(RunSetupViewModel)의 조합 수 계산·모델 선택 순서·runId 형식·미설치 모델/VAD 모델 미설치 프리셋 선택 거부·maxChunk 검증을 검증
// 기능: 임시 폴더 ModelStore 에 합성 모델을 직접 설치(파일+표식)하고, 고정 입력 목록을 돌려주는 스캐너로 ViewModel 을 만든 뒤
//       선택 동작과 comboCount/buildConfig 결과를 확인한다. 작업 디스패처는 즉시 실행 테스트 디스패처로 바꿔 refresh 가 바로 끝나게 한다.
//       change(add)-hyungchul-2026-10-11-19:10 (2차 작업 5) 시스템 엔진 모델의 maxChunk 입력이 RunConfig 와 같은 범위(0 초 초과 60 초 이하)·문구로 검증되는지 확인한다.
//       change(add)-hyungchul-2026-10-11-03:59 마이크 녹음 파일 미리 선택(preselect)과 SAF 음원 가져오기 작업(runImport)의 메시지·목록 갱신도 확인한다.
//       change(add)-hyungchul-2026-10-11-20:15 (2차 작업 9) ViewModel 의 presets 인자가 공급 함수가 되어 호출부를 { VadPresets.all } 로 고쳤다(동작 변화 없음).
package com.cowork.stt_ondevice.ui.run // 실행 화면 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.core.storage.InputScanResult // 입력 스캔 결과 // change(add)-hyungchul-2026-10-11-12:51
import com.cowork.stt_ondevice.core.storage.SafCopyResult // change(add)-hyungchul-2026-10-11-03:59 SAF 복사 결과
import com.cowork.stt_ondevice.model.ModelFile // 모델 파일 정보
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 엔진 카탈로그 // change(add)-hyungchul-2026-10-11-19:10
import com.cowork.stt_ondevice.model.VadModelConfig // VAD 모델 설정
import com.cowork.stt_ondevice.model.sha256Hex // SHA-256 도우미
import com.cowork.stt_ondevice.model.testSpec // 테스트용 ModelSpec
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadPresets // VAD 프리셋 레지스트리
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.ExperimentalCoroutinesApi // 테스트 API 표시
import kotlinx.coroutines.test.UnconfinedTestDispatcher // 즉시 실행 테스트 디스패처
import kotlinx.coroutines.test.resetMain // Main 복구
import kotlinx.coroutines.test.setMain // Main 교체
import org.junit.After // 각 테스트 후
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // 거짓
import org.junit.Assert.assertNotNull // null 아님
import org.junit.Assert.assertNull // null
import org.junit.Assert.assertThrows // 예외 확인
import org.junit.Assert.assertTrue // 참
import org.junit.Before // 각 테스트 전
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.time.LocalDateTime // 현재 시각 값

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: RunSetupViewModel 회귀 방지
 * 기능: brief 필수 4종(comboCountRespectsLanguage, modelSelectionOrderPreserved, runIdFormat, uninstalledModelNotSelectable)과
 *       vadPresetDisabledWithoutModel, maxChunkValidation 을 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunSetupViewModelTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val dispatcher = UnconfinedTestDispatcher() // 즉시 실행 디스패처
    private lateinit var store: ModelStore // 모델 저장소

    private val bytes = "model".toByteArray() // 합성 모델 파일 내용(모든 합성 모델 공통)
    private val files = listOf(ModelFile("m.bin", bytes.size.toLong(), sha256Hex(bytes), "model")) // 합성 모델 파일 목록
    private val koOnly = testSpec("ko-only", emptyList(), files).copy(languages = setOf("ko"), maxChunkSec = 30.0) // 한국어 전용 모델
    private val both = testSpec("both", emptyList(), files).copy(languages = setOf("ko", "en"), maxChunkSec = 30.0) // 한·영 공용 모델
    private val third = testSpec("third", emptyList(), files).copy(languages = setOf("ko", "en"), maxChunkSec = 30.0) // 순서 확인용 세 번째 모델
    private val missing = testSpec("missing", emptyList(), files) // 설치하지 않을 모델
    private val sileroModel: ModelSpec = testSpec("silero-vad", emptyList(), files).copy(kind = ModelKind.VAD, languages = emptySet(), engineConfig = VadModelConfig(VadFamily.SILERO)) // Silero VAD 모델(미설치로 둠)

    // change(add)-hyungchul-2026-10-11-19:10
    /**
     * 목적: 시스템 엔진 maxChunk 입력 검증 (2차 작업 5)
     * 기능: 시스템 엔진 모델에 0·61 은 RunConfig 와 같은 문구의 오류(덮어쓰기에 안 들어감), 60 은 허용, 일반 모델 0 은 기존대로 허용.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun systemMaxChunkRangeIsValidatedLikeRunConfig() {
        val sys = SystemCatalog.all.first { it.id == "mlkit-basic-x4" } // 시스템 엔진(파일 없음 → 항상 READY)
        val vm = RunSetupViewModel(
            scanner = { InputScanResult(kr + en, emptyList()) }, catalog = listOf(both, sys), store = store, presets = { VadPresets.all },
            vadModelOf = { null }, io = dispatcher, // 고정 입력·합성 카탈로그
        )
        selectAllItems(vm) // 입력
        vm.togglePreset("NONE") // 프리셋
        vm.toggleModel("mlkit-basic-x4") // 시스템 모델
        vm.toggleModel("both") // 일반 모델
        val now = LocalDateTime.of(2026, 10, 11, 19, 10, 0) // 고정 시각
        for (bad in listOf("0", "61")) { // 범위 밖
            vm.setMaxChunkText("mlkit-basic-x4", bad) // 입력
            assertEquals("시스템 엔진 최대 조각 길이는 0 초 초과 60 초 이하여야 합니다: mlkit-basic-x4=${bad.toDouble()}", vm.state.value.maxChunk.getValue("mlkit-basic-x4").error) // RunConfig 와 같은 문구
            assertNotNull(vm.validationError()) // 실행 불가
        }
        assertFalse(vm.setMaxChunk("mlkit-basic-x4", 61.0)) // 숫자 API 도 거부
        vm.setMaxChunkText("mlkit-basic-x4", "60") // 상한
        assertNull(vm.state.value.maxChunk.getValue("mlkit-basic-x4").error) // 허용
        assertEquals(mapOf("mlkit-basic-x4" to 60.0), vm.buildConfig(now).maxChunkOverride) // RunConfig 생성도 통과
        assertTrue(vm.setMaxChunk("both", 0.0)) // 일반 모델 0(무제한)은 기존대로 허용
    }

    /** 목적: 입력 항목 만들기 / 기능: 파일은 실제로 만들지 않는다(스캐너 결과 흉내) / 입력: lang, rel, stem / 리턴: InputItem */
    private fun item(lang: String, rel: String, stem: String) = InputItem(lang, rel, stem, File("/input/$lang/$rel/$stem.wav")) // 항목

    private val kr = listOf(item("kr", "customer", "01"), item("kr", "customer", "02")) // 한국어 2개
    private val en = listOf(item("en", "Customer_Service", "01"), item("en", "Customer_Service", "02"), item("en", "Meeting", "01")) // 영어 3개

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher) // viewModelScope 용 Main 교체
        store = ModelStore(AppDirs(tmp.newFolder("root"))) // 임시 저장소
        for (spec in listOf(koOnly, both, third)) install(spec) // 세 모델 설치
    }

    @After
    fun tearDown() = Dispatchers.resetMain() // Main 복구

    /** 목적: spec 을 READY 로 만들기 / 기능: 파일과 표식을 직접 기록한다 / 입력: spec / 리턴: 없음 */
    private fun install(spec: ModelSpec) {
        val dir = store.modelDir(spec) // 모델 폴더
        File(dir, spec.files[0].path).apply { parentFile!!.mkdirs(); writeBytes(bytes) } // 파일 기록
        store.writeMarker(spec, dir) // 표식 기록
    }

    /** 목적: ViewModel 만들기 / 기능: 고정 입력 목록·합성 카탈로그·실제 프리셋 목록·합성 VAD 모델 조회로 주입한다 / 입력: 없음 / 리턴: RunSetupViewModel */
    private fun newVm(): RunSetupViewModel = RunSetupViewModel(
        scanner = { InputScanResult(kr + en, emptyList()) }, // 고정 입력(오류 없음) // change(add)-hyungchul-2026-10-11-12:51 스캔 결과 타입
        catalog = listOf(koOnly, both, third, missing), // 합성 카탈로그
        store = store, // 저장소
        presets = { VadPresets.all }, // 실제 프리셋 26종 // change(add)-hyungchul-2026-10-11-20:15 공급 함수
        vadModelOf = { family -> if (family == VadFamily.SILERO) sileroModel else null }, // Silero 만 모델 있음(미설치)
        io = dispatcher, // 즉시 실행
    )

    /** 목적: 모든 입력 선택 / 기능: 항목마다 toggleItem / 입력: vm / 리턴: 없음 */
    private fun selectAllItems(vm: RunSetupViewModel) = (kr + en).forEach { vm.toggleItem(it) } // 전부 선택

    /**
     * 목적: 조합 수가 모델의 지원 언어를 따르는지(brief 예시)
     * 기능: ko 전용 1 + 공용 1, 프리셋 2(NONE·WEBRTC_ANDROIDVAD), kr 2·en 3 → 1×2×2 + 1×2×5 = 14. 언어 en 을 끄면 1×2×2 + 1×2×2 = 8.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun comboCountRespectsLanguage() {
        val vm = newVm() // ViewModel
        selectAllItems(vm) // 입력 5개
        vm.toggleModel("ko-only") // ko 전용
        vm.toggleModel("both") // 공용
        vm.togglePreset("NONE") // 프리셋 1
        vm.togglePreset("WEBRTC_ANDROIDVAD") // 프리셋 2
        assertEquals(14, vm.comboCount()) // 1×2×2 + 1×2×5
        assertEquals(14, vm.state.value.comboCount) // 상태에도 반영
        vm.toggleLang("en") // 영어 끄기
        assertEquals(8, vm.comboCount()) // 영어 파일 제외
        val cfg = vm.buildConfig(LocalDateTime.of(2026, 10, 9, 13, 15, 0)) // 설정
        assertEquals(kr, cfg.items) // 한국어 항목만
    }

    /**
     * 목적: 모델 선택 순서 유지
     * 기능: third → koOnly → both 순으로 고르면 설정의 models 도 그 순서. 가운데를 해제했다 다시 고르면 맨 뒤로 간다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun modelSelectionOrderPreserved() {
        val vm = newVm() // ViewModel
        selectAllItems(vm) // 입력
        vm.togglePreset("NONE") // 프리셋
        vm.toggleModel("third") // 1번
        vm.toggleModel("ko-only") // 2번
        vm.toggleModel("both") // 3번
        assertEquals(listOf("third", "ko-only", "both"), vm.state.value.selectedModels) // 선택 순서
        assertEquals(listOf("third", "ko-only", "both"), vm.buildConfig(LocalDateTime.of(2026, 10, 9, 13, 15, 0)).models.map { it.id }) // 실행 순서
        vm.toggleModel("ko-only") // 해제
        vm.toggleModel("ko-only") // 다시 선택 → 맨 뒤
        assertEquals(listOf("third", "both", "ko-only"), vm.state.value.selectedModels) // 순서 갱신
    }

    /**
     * 목적: runId 형식
     * 기능: 2026-10-09 13:15:00 → "20261009_131500", 스레드·chunk 모드도 설정에 들어간다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun runIdFormat() {
        val vm = newVm() // ViewModel
        selectAllItems(vm) // 입력
        vm.toggleModel("both") // 모델
        vm.togglePreset("NONE") // 프리셋
        vm.setThreads(6) // 스레드
        vm.setChunkMode(ChunkMode.INDIVIDUAL) // 묶기 방식
        val cfg = vm.buildConfig(LocalDateTime.of(2026, 10, 9, 13, 15, 0)) // 설정
        assertEquals("20261009_131500", cfg.runId) // 형식
        assertEquals(6, cfg.threads) // 스레드
        assertEquals(ChunkMode.INDIVIDUAL, cfg.chunkMode) // 묶기 방식
        assertFalse(cfg.resume) // 새 실행
        vm.setThreads(99) // 범위 밖
        assertEquals(8, vm.state.value.threads) // 1~8 로 제한
        vm.setThreads(0) // 범위 밖
        assertEquals(1, vm.state.value.threads) // 하한
    }

    /**
     * 목적: 미설치 모델 선택 불가
     * 기능: missing 은 선택해도 selectedModels 에 들어가지 않고 사유 "미설치" 가 보인다. 모델 없이 buildConfig 하면 한국어 오류.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun uninstalledModelNotSelectable() {
        val vm = newVm() // ViewModel
        vm.toggleModel("missing") // 미설치 모델 선택 시도
        assertTrue(vm.state.value.selectedModels.isEmpty()) // 선택 안 됨
        val opt = vm.state.value.models.first { it.id == "missing" } // 행
        assertFalse(opt.selectable) // 선택 불가
        assertEquals("미설치", opt.disabledReason) // 사유
        assertNull(vm.state.value.models.first { it.id == "both" }.disabledReason) // 설치된 모델은 사유 없음
        selectAllItems(vm) // 입력
        vm.togglePreset("NONE") // 프리셋
        val e = assertThrows(IllegalStateException::class.java) { vm.buildConfig(LocalDateTime.of(2026, 10, 9, 13, 15, 0)) } // 모델 없음
        assertTrue(e.message!!.contains("모델")) // 한국어 사유
    }

    /**
     * 목적: VAD 모델이 없는 계열의 프리셋 선택 불가
     * 기능: SILERO(모델 미설치)는 선택 불가·사유 "VAD 모델 미설치", NONE/WEBRTC/TEN 은 모델 없이 선택 가능, 카탈로그에 모델이 없는 PYANNOTE 도 선택 불가.
     *       Silero 모델을 설치하고 refresh 하면 선택 가능해진다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun vadPresetDisabledWithoutModel() {
        val vm = newVm() // ViewModel
        vm.togglePreset("SILERO") // 선택 시도
        assertTrue(vm.state.value.selectedPresets.isEmpty()) // 선택 안 됨
        val silero = vm.state.value.presets.first { it.id == "SILERO" } // 행
        assertFalse(silero.selectable) // 불가
        assertEquals("VAD 모델 미설치", silero.disabledReason) // 사유
        assertFalse(vm.state.value.presets.first { it.id == "PYA3_DEFAULT" }.selectable) // 카탈로그 모델 없음도 불가
        for (id in listOf("NONE", "WEBRTC_ANDROIDVAD", "TEN_DEFAULT")) { // 모델 불필요 계열
            assertTrue(id, vm.state.value.presets.first { it.id == id }.selectable) // 가능
        }
        assertTrue(silero.params.contains("threshold=0.5")) // 파라미터 문자열 표시
        install(sileroModel) // Silero 모델 설치
        vm.refresh() // 상태 다시 읽기
        vm.togglePreset("SILERO") // 다시 선택
        assertEquals(setOf("SILERO"), vm.state.value.selectedPresets) // 이제 선택됨
    }

    /**
     * 목적: 모델별 maxChunk 덮어쓰기 검증
     * 기능: 음수·NaN·무한대·숫자 아님은 거부(오류 표시, 설정에 없음, buildConfig 거부), 30 은 반영, 빈 칸은 해제.
     *       선택하지 않은 모델의 값은 설정에 넣지 않는다(RunConfig 의 키 검증과 충돌하지 않게).
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun maxChunkValidation() {
        val vm = newVm() // ViewModel
        selectAllItems(vm) // 입력
        vm.togglePreset("NONE") // 프리셋
        vm.toggleModel("both") // 모델
        val now = LocalDateTime.of(2026, 10, 9, 13, 15, 0) // 고정 시각
        assertFalse(vm.setMaxChunk("both", -1.0)) // 음수 거부
        assertFalse(vm.setMaxChunk("both", Double.NaN)) // NaN 거부
        assertFalse(vm.setMaxChunk("both", Double.POSITIVE_INFINITY)) // 무한대 거부
        assertTrue(vm.buildConfig(now).maxChunkOverride.isEmpty()) // 거부 값은 설정에 없음
        assertTrue(vm.setMaxChunk("both", 20.0)) // 정상 값
        assertEquals(mapOf("both" to 20.0), vm.buildConfig(now).maxChunkOverride) // 반영
        vm.setMaxChunkText("both", "abc") // 숫자 아님
        assertNotNull(vm.state.value.maxChunk.getValue("both").error) // 오류 표시
        assertNotNull(vm.validationError()) // 실행 불가 사유
        assertThrows(IllegalStateException::class.java) { vm.buildConfig(now) } // 실행 거부
        vm.setMaxChunkText("both", "-3") // 음수 텍스트
        assertNotNull(vm.state.value.maxChunk.getValue("both").error) // 오류
        vm.setMaxChunkText("both", "12.5") // 정상 텍스트
        assertNull(vm.state.value.maxChunk.getValue("both").error) // 오류 없음
        assertEquals(mapOf("both" to 12.5), vm.buildConfig(now).maxChunkOverride) // 반영
        vm.setMaxChunkText("both", "") // 빈 칸 = 기본값
        assertTrue(vm.buildConfig(now).maxChunkOverride.isEmpty()) // 해제
        assertTrue(vm.setMaxChunk("third", 10.0)) // 선택 안 한 모델에 값
        assertTrue(vm.buildConfig(now).maxChunkOverride.isEmpty()) // 선택 모델만 설정에 들어감
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: 마이크 녹음 파일 미리 선택과 SAF 가져오기 후 목록 갱신
     * 기능: kr 언어를 끈 상태에서 새로 생긴 kr/mic/20261011_120000.wav 를 preselect 하면 다시 스캔해 그 항목이 선택되고 kr 이 켜진다(없는 경로는 무시).
     *       runImport 는 복사 결과(복사 2·건너뜀 1)를 한국어 메시지로 보이고, 끝나면 importing=false 이며 입력 목록을 다시 읽는다. 실패하면 "음원 가져오기 실패" 사유.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun preselectAndImportRefresh() {
        var items = kr + en // 스캐너가 돌려줄 목록(바뀔 수 있음)
        var scans = 0 // 스캔 횟수
        val vm = RunSetupViewModel({ scans++; InputScanResult(items, emptyList()) }, listOf(koOnly, both), store, { VadPresets.all }, { null }, dispatcher) // ViewModel // change(add)-hyungchul-2026-10-11-12:51 스캔 결과 타입
        vm.toggleLang("kr") // kr 끄기
        val mic = item("kr", "mic", "20261011_120000") // 새 녹음 파일
        items = items + mic // 목록에 생김
        vm.preselect(File("/nope/x.wav")) // 없는 경로
        assertFalse(vm.state.value.selectedItems.any { it.file.path == "/nope/x.wav" }) // 무시
        vm.preselect(mic.file) // 녹음 파일 미리 선택
        assertTrue(mic in vm.state.value.selectedItems) // 선택됨
        assertTrue("kr" in vm.state.value.langs) // kr 켜짐
        assertTrue(vm.state.value.folders.any { it.lang == "kr" && it.relDir == "mic" }) // 트리에 나타남
        val before = scans // 가져오기 전 스캔 수
        vm.runImport { SafCopyResult(copied = 2, skipped = 1, bytes = 10, target = "/x/input_sound") } // 가져오기 성공
        val msg = vm.state.value.message!! // 안내
        assertTrue(msg, msg.contains("2개") && msg.contains("건너뜀 1개")) // 결과 문구
        assertFalse(vm.state.value.importing) // 끝남
        assertTrue(scans > before) // 다시 스캔
        vm.consumeMessage() // 소비
        assertNull(vm.state.value.message) // 비워짐
        vm.runImport { throw java.io.IOException("읽기 거부") } // 실패
        assertTrue(vm.state.value.message!!.contains("음원 가져오기 실패: 읽기 거부")) // 사유
        assertFalse(vm.state.value.importing) // 끝남
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 입력 스캔 오류 표시(T3 이월)
     * 기능: 스캐너가 오류 문구를 돌려주면 state.inputErrors 에 그대로 들어가고, 다시 읽어 오류가 없어지면 비워진다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun inputErrorsShown() {
        var errors = listOf("kr/customer: 01.WAV, 01.wav — 겹침") // 스캐너 오류
        val vm = RunSetupViewModel({ InputScanResult(kr, errors) }, listOf(koOnly), store, { VadPresets.all }, { null }, dispatcher) // ViewModel
        assertEquals(errors, vm.state.value.inputErrors) // 오류 표시
        errors = emptyList() // 사용자가 파일을 정리함
        vm.refresh() // 다시 읽기
        assertTrue(vm.state.value.inputErrors.isEmpty()) // 비워짐
    }
}
