// 파일명: ui/run/RunSetupViewModelTest.kt
// 목적: 실행 설정 화면 ViewModel(RunSetupViewModel)의 조합 수 계산·모델 선택 순서·runId 형식·미설치 모델/VAD 모델 미설치 프리셋 선택 거부·maxChunk 검증을 검증
// 기능: 임시 폴더 ModelStore 에 합성 모델을 직접 설치(파일+표식)하고, 고정 입력 목록을 돌려주는 스캐너로 ViewModel 을 만든 뒤
//       선택 동작과 comboCount/buildConfig 결과를 확인한다. 작업 디스패처는 즉시 실행 테스트 디스패처로 바꿔 refresh 가 바로 끝나게 한다.
package com.cowork.stt_ondevice.ui.run // 실행 화면 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.model.ModelFile // 모델 파일 정보
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소
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
        scanner = { kr + en }, // 고정 입력
        catalog = listOf(koOnly, both, third, missing), // 합성 카탈로그
        store = store, // 저장소
        presets = VadPresets.all, // 실제 프리셋 26종
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
}
