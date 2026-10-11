// 파일명: ui/run/RunSetupUserPresetTest.kt
// 목적: 실행 설정 화면 ViewModel 의 USER VAD 프리셋 만들기·선택·삭제 흐름 검증
// 기능: 임시 폴더의 user_presets.json 저장소와 합본 레지스트리(PresetRegistry)를 ViewModel 에 공급 함수로 연결하고,
//       복사해서 만들기(기본 값 채움·저장·자동 선택·조합 수 갱신), 필드 오류로 저장 차단, 이름 오류, 삭제 경고(미완료 실행 포함)·확인·취소,
//       손상 파일 오류 표시, 미완료 실행 조회(unfinishedRunsUsing)를 확인한다. 작업 디스패처는 즉시 실행 테스트 디스패처로 바꾼다.
// change(add)-hyungchul-2026-10-11-20:15
package com.cowork.stt_ondevice.ui.run // 실행 화면 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.core.storage.InputScanResult // 입력 스캔 결과
import com.cowork.stt_ondevice.model.ModelFile // 모델 파일 정보
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소
import com.cowork.stt_ondevice.model.VadModelConfig // VAD 모델 설정
import com.cowork.stt_ondevice.model.sha256Hex // SHA-256 도우미
import com.cowork.stt_ondevice.model.testSpec // 테스트용 ModelSpec
import com.cowork.stt_ondevice.output.DeviceInfoJson // manifest 기기 정보
import com.cowork.stt_ondevice.output.ModelEntry // manifest 모델 항목
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.PresetEntry // manifest 프리셋 항목
import com.cowork.stt_ondevice.output.RunManifest // 실행 manifest
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.vad.PresetParamCodec // 프리셋 값 변환
import com.cowork.stt_ondevice.vad.PresetRegistry // 기본+USER 합본
import com.cowork.stt_ondevice.vad.SileroParams // Silero 파라미터
import com.cowork.stt_ondevice.vad.UserPresetStore // USER 프리셋 저장소
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadPresets // 기본 프리셋
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
import org.junit.Assert.assertTrue // 참
import org.junit.Before // 각 테스트 전
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-20:15
/**
 * 목적: USER 프리셋 화면 상태 회귀 방지
 * 기능: brief 필수 3종(createFromSileroAndSelect, fieldErrorBlocksSave, deleteWarnsWhenUnfinishedRunUsesIt)과 보조 검증을 한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RunSetupUserPresetTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val dispatcher = UnconfinedTestDispatcher() // 즉시 실행 디스패처
    private lateinit var modelStore: ModelStore // 모델 저장소
    private lateinit var userStore: UserPresetStore // USER 프리셋 저장소
    private lateinit var registry: PresetRegistry // 합본 조회
    private lateinit var presetFile: File // 저장 파일

    private val bytes = "model".toByteArray() // 합성 모델 파일 내용
    private val files = listOf(ModelFile("m.bin", bytes.size.toLong(), sha256Hex(bytes), "model")) // 합성 모델 파일
    private val both = testSpec("both", emptyList(), files).copy(languages = setOf("ko", "en"), maxChunkSec = 30.0) // 한·영 모델
    private val sileroModel: ModelSpec = testSpec("silero-vad", emptyList(), files).copy(kind = ModelKind.VAD, languages = emptySet(), engineConfig = VadModelConfig(VadFamily.SILERO)) // Silero VAD 모델
    private val kr = listOf(item("kr", "customer", "01"), item("kr", "customer", "02")) // 한국어 2개
    private val en = listOf(item("en", "Meeting", "01"), item("en", "Meeting", "02"), item("en", "Meeting", "03")) // 영어 3개

    /** 목적: 입력 항목 만들기 / 기능: 실제 파일 없이 경로만 가진 항목 / 입력: lang, rel, stem / 리턴: InputItem */
    private fun item(lang: String, rel: String, stem: String) = InputItem(lang, rel, stem, File("/input/$lang/$rel/$stem.wav")) // 항목

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher) // viewModelScope 용 Main 교체
        modelStore = ModelStore(AppDirs(tmp.newFolder("root"))) // 임시 모델 저장소
        for (spec in listOf(both, sileroModel)) { // 두 모델을 설치 상태로
            val dir = modelStore.modelDir(spec) // 모델 폴더
            File(dir, spec.files[0].path).apply { parentFile!!.mkdirs(); writeBytes(bytes) } // 파일
            modelStore.writeMarker(spec, dir) // 표식
        }
        presetFile = File(tmp.newFolder("files"), "user_presets.json") // 저장 파일(아직 없음)
        userStore = UserPresetStore(presetFile, now = { "2026-10-11T20:15:00+09:00" }) // 고정 시각
        registry = PresetRegistry(userStore) // 합본
    }

    @After
    fun tearDown() = Dispatchers.resetMain() // Main 복구

    /** 목적: ViewModel 만들기 / 기능: 합본 레지스트리·USER 저장소·미완료 실행 조회를 주입한다 / 입력: unfinished(미완료 runId 조회 함수) / 리턴: RunSetupViewModel */
    private fun newVm(unfinished: (String) -> List<String> = { emptyList() }): RunSetupViewModel = RunSetupViewModel(
        scanner = { InputScanResult(kr + en, emptyList()) }, // 고정 입력
        catalog = listOf(both), // 모델 1개
        store = modelStore, // 모델 저장소
        presets = registry::all, // 합본(refresh 마다 다시 읽음)
        vadModelOf = { family -> if (family == VadFamily.SILERO) sileroModel else null }, // Silero 만 모델 있음(설치됨)
        io = dispatcher, // 즉시 실행
        userPresets = userStore, // USER 저장소
        unfinishedRunsUsing = unfinished, // 미완료 실행 조회
    )

    /** 목적: SILERO 기준 값 map / 기능: 기준 프리셋 SILERO 의 값 / 입력: 없음 / 리턴: Map */
    private fun sileroValues() = PresetParamCodec.toMap(VadPresets.byId("SILERO").params) // 기준 값

    /**
     * 목적: SILERO 를 복사해 만들고 자동 선택 (brief 9-1)
     * 기능: 폼 필드 기본값이 기준 값과 같고, threshold 0.35·이름 "조용한방" 저장 후 목록에 USER_조용한방(USER 표시)이 나타나 선택되며 조합 수가 갱신된다.
     *       실행 설정(buildConfig)에도 그 USER 프리셋(threshold 0.35)이 들어간다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun createFromSileroAndSelect() {
        val vm = newVm() // ViewModel
        (kr + en).forEach { vm.toggleItem(it) } // 입력 전부 선택
        vm.toggleModel("both") // 모델 선택
        assertEquals(0, vm.state.value.comboCount) // 프리셋 없어서 0
        vm.startUserPreset("SILERO") // 복사 시작
        val draft = vm.state.value.userDraft!! // 폼
        assertEquals("SILERO", draft.baseId) // 기준
        assertEquals(sileroValues(), draft.fields.associate { it.key to it.text }) // 기준 값으로 채움
        assertEquals(listOf("threshold", "minSpeechMs", "minSilenceMs", "speechPadMs", "maxSpeechSec"), draft.fields.map { it.key }) // 필드 순서
        assertTrue(draft.fields.all { it.hint.isNotEmpty() && it.error == null }) // 안내 있음·오류 없음
        assertEquals("실수, 0보다 큰 값 또는 inf(무제한)", draft.fields.first { it.key == "maxSpeechSec" }.hint) // 실제 규칙 안내
        vm.setUserField("threshold", "0.35") // 값 변경
        vm.setUserName("조용한방") // 이름
        vm.saveUserPreset() // 저장
        val s = vm.state.value
        assertNull(s.userDraft) // 폼 닫힘
        val row = s.presets.first { it.id == "USER_조용한방" } // 목록에 나타남
        assertTrue(row.user) // USER 표시
        assertEquals(VadFamily.SILERO, row.family) // 계열 상속
        assertTrue(row.params.contains("threshold=0.35")) // 새 값
        assertTrue("USER_조용한방" in s.selectedPresets) // 자동 선택
        assertEquals(5, s.comboCount) // 1 모델 × 1 프리셋 × 파일 5개
        assertEquals(listOf("USER_조용한방"), userStore.list().map { it.id }) // 파일에도 저장
        val cfg = vm.buildConfig(java.time.LocalDateTime.of(2026, 10, 11, 20, 15, 0)) // 실행 설정
        assertEquals(listOf("USER_조용한방"), cfg.presets.map { it.id }) // 설정에 USER 프리셋
        assertEquals(0.35f, (cfg.presets[0].params as SileroParams).threshold) // 바뀐 값
    }

    /**
     * 목적: 필드 오류는 저장을 막는다 (brief 9-1)
     * 기능: threshold "2" 는 입력 즉시 필드 오류가 보이고, 저장해도 파일에 쓰지 않으며 폼이 남는다. 고치면 저장된다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun fieldErrorBlocksSave() {
        val vm = newVm() // ViewModel
        vm.startUserPreset("SILERO") // 시작
        vm.setUserField("threshold", "2") // 범위 밖
        vm.setUserName("방1") // 이름
        assertEquals("threshold: 0~1 사이여야 합니다", vm.state.value.userDraft!!.fields.first { it.key == "threshold" }.error) // 즉시 오류
        vm.saveUserPreset() // 저장 시도
        val d = vm.state.value.userDraft!! // 폼은 남음
        assertEquals("threshold: 0~1 사이여야 합니다", d.fields.first { it.key == "threshold" }.error) // 필드 오류 유지
        assertNotNull(d.saveError) // 안내
        assertFalse(presetFile.exists()) // 파일 안 만듦
        assertTrue(vm.state.value.presets.none { it.user }) // 목록에 없음
        vm.setUserField("threshold", "0.4") // 고침
        assertNull(vm.state.value.userDraft!!.fields.first { it.key == "threshold" }.error) // 오류 해제
        vm.saveUserPreset() // 저장
        assertNull(vm.state.value.userDraft) // 성공
        assertTrue(vm.state.value.presets.any { it.id == "USER_방1" }) // 목록에 나타남
    }

    /**
     * 목적: 이름 오류·중복·기준 오류
     * 기능: 규칙 위반 이름과 중복 이름은 이름 칸 오류로 보이고 폼이 남는다. NONE 은 폼이 열리지 않고 안내가 뜬다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun nameErrorsAndNoneBase() {
        val vm = newVm() // ViewModel
        vm.startUserPreset("SILERO") // 시작
        vm.setUserName("ㅋ") // 자모 단독 이름
        vm.saveUserPreset() // 저장 시도
        assertNotNull(vm.state.value.userDraft!!.nameError) // 이름 오류
        vm.setUserName("silero") // 이름 고침(USER_silero)
        vm.saveUserPreset() // 저장
        assertNull(vm.state.value.userDraft) // 성공
        vm.startUserPreset("SILERO") // 다시 시작
        vm.setUserName("Silero") // 대소문자만 다른 중복(USER_silero 와)
        vm.saveUserPreset() // 저장 시도
        assertTrue(vm.state.value.userDraft!!.nameError!!.contains("이미 있는 이름")) // 중복 사유
        vm.cancelUserDraft() // 닫기
        assertNull(vm.state.value.userDraft) // 닫힘
        vm.startUserPreset("NONE") // NONE 기준
        assertNull(vm.state.value.userDraft) // 폼 안 열림
        assertTrue(vm.state.value.message!!.contains("NONE")) // 안내
        vm.consumeMessage() // 소비
        vm.startUserPreset("USER_silero") // USER 는 기준이 될 수 없음
        assertNull(vm.state.value.userDraft) // 폼 안 열림
        assertTrue(vm.state.value.message!!.contains("기준 프리셋을 찾을 수 없습니다")) // 안내
    }

    /**
     * 목적: 미완료 실행이 쓰는 프리셋 삭제 경고 (brief 9-1)
     * 기능: unfinishedRunsUsing 이 runId 를 주면 requestDelete 는 지우지 않고 경고(runId 포함)를 두며, confirmDelete 후 목록·파일에서 사라지고 선택에서도 빠진다.
     *       경고를 취소하면 그대로 남는다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun deleteWarnsWhenUnfinishedRunUsesIt() {
        userStore.create("SILERO", "방", sileroValues()) // 미리 하나 저장
        val vm = newVm { listOf("20261011_120000") } // 미완료 실행이 쓴다고 응답
        vm.togglePreset("USER_방") // 선택
        assertTrue("USER_방" in vm.state.value.selectedPresets) // 선택됨
        vm.requestDeleteUserPreset("USER_방") // 삭제 요청
        val w = vm.state.value.deleteWarning!! // 경고
        assertEquals("미완료 실행(20261011_120000)이 이 프리셋을 씁니다. 지우면 그 실행은 이어할 수 없습니다. 지울까요?", w) // 정해진 문구
        assertTrue(vm.state.value.presets.any { it.id == "USER_방" }) // 아직 있음
        vm.cancelDeleteUserPreset() // 취소
        assertNull(vm.state.value.deleteWarning) // 경고 닫힘
        assertEquals(1, userStore.list().size) // 그대로
        vm.confirmDeleteUserPreset("USER_방") // 요청 없이 확인 → 무시
        assertEquals(1, userStore.list().size) // 지워지지 않음
        vm.requestDeleteUserPreset("USER_방") // 다시 요청
        vm.confirmDeleteUserPreset("USER_방") // 확인
        assertNull(vm.state.value.deleteWarning) // 경고 닫힘
        assertTrue(vm.state.value.presets.none { it.id == "USER_방" }) // 목록에서 사라짐
        assertTrue("USER_방" !in vm.state.value.selectedPresets) // 선택에서도 빠짐
        assertTrue(userStore.list().isEmpty()) // 파일에서도 삭제
    }

    /**
     * 목적: 쓰는 실행이 없으면 바로 삭제, 기본 프리셋은 지울 수 없음
     * 기능: 미완료 실행이 없으면 경고 없이 지운다. USER_ 가 아닌 id 는 요청해도 아무 일도 없다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun deleteImmediatelyWhenNoUnfinishedRun() {
        userStore.create("SILERO", "방", sileroValues()) // 하나 저장
        val vm = newVm() // 미완료 없음
        vm.requestDeleteUserPreset("USER_방") // 요청
        assertNull(vm.state.value.deleteWarning) // 경고 없음
        assertTrue(vm.state.value.presets.none { it.user }) // 바로 삭제
        vm.requestDeleteUserPreset("SILERO") // 기본 프리셋
        assertTrue(vm.state.value.presets.any { it.id == "SILERO" }) // 그대로
    }

    /**
     * 목적: 확인 실패는 삭제하지 않음
     * 기능: 미완료 실행 조회가 예외를 내면 지우지 않고 사유를 메시지로 보인다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun deleteIsSkippedWhenCheckFails() {
        userStore.create("SILERO", "방", sileroValues()) // 하나 저장
        val vm = newVm { throw IllegalStateException("읽기 실패") } // 조회 실패
        vm.requestDeleteUserPreset("USER_방") // 요청
        assertTrue(vm.state.value.message!!.contains("삭제하지 않았습니다")) // 사유
        assertEquals(1, userStore.list().size) // 그대로
    }

    /**
     * 목적: 손상된 저장 파일은 화면에 오류로 보임
     * 기능: user_presets.json 이 깨져 있으면 USER 목록은 비고 state.userPresetError 에 한국어 사유가 있으며 파일은 그대로다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun corruptFileIsShownNotHidden() {
        presetFile.writeText("{ 깨진") // 손상 파일
        val vm = newVm() // ViewModel
        assertTrue(vm.state.value.userPresetError!!.contains("user_presets.json")) // 사유 표시
        assertTrue(vm.state.value.presets.none { it.user }) // USER 없음
        assertEquals("{ 깨진", presetFile.readText()) // 덮어쓰지 않음
        presetFile.delete() // 파일 제거
        vm.refresh() // 다시 읽기
        assertNull(vm.state.value.userPresetError) // 정상이면 오류 해제
    }

    /**
     * 목적: 미완료 실행 조회(앱 구현)
     * 기능: finishedAt 이 null 이고 presets 에 그 id 가 있는 run 폴더 이름만 이름순으로 돌려주고, 완료 run·다른 프리셋·손상 manifest·manifest 없는 폴더·일반 파일은 건너뛴다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun unfinishedRunsUsingReadsManifests() {
        val out = tmp.newFolder("output_stt") // 출력 루트
        val user = VadPresets.byId("WEBRTC_ANDROIDVAD").let { it.copy(id = "USER_x", source = "USER(기준 ${it.id})") } // USER 프리셋
        fun write(runId: String, finishedAt: String?, vararg ids: String) { // manifest 쓰기
            RunManifest(
                runId = runId, appVersion = "1.0 (1)", startedAt = "2026-10-11T12:00:00",
                device = DeviceInfoJson("samsung", "SM-S908N", null, "16", 36, "arm64-v8a", 11000L, "1.0 (1)"),
                models = listOf(ModelEntry.from(both, 30.0)),
                presets = ids.map { id -> PresetEntry.from(if (id == "USER_x") user else VadPresets.byId(id)) },
                chunkMode = ChunkMode.MERGE.name, maxChunkOverride = emptyMap(), threads = 4, resume = false,
                inputRoot = "/input", unsupported = emptyList(), finishedAt = finishedAt, inputs = emptyList(),
            ).write(OutputLayout.manifest(File(out, runId))) // 저장
        }
        write("20261011_130000", null, "NONE", "USER_x") // 미완료 + 사용
        write("20261011_110000", null, "USER_x") // 미완료 + 사용(더 이른 이름)
        write("20261011_120000", "2026-10-11T12:30:00", "USER_x") // 완료 → 제외
        write("20261011_140000", null, "NONE") // 다른 프리셋 → 제외
        File(File(out, "20261011_150000"), "run_manifest.json").apply { parentFile!!.mkdirs(); writeText("{ 깨진") } // 손상 → 제외
        File(out, "20261011_160000").mkdirs() // manifest 없는 폴더 → 제외
        File(out, "stray.txt").writeText("x") // 일반 파일 → 제외
        assertEquals(listOf("20261011_110000", "20261011_130000"), RunSetupViewModel.unfinishedRunsUsing(out, "USER_x")) // 이름순
        assertTrue(RunSetupViewModel.unfinishedRunsUsing(File(out, "없는폴더"), "USER_x").isEmpty()) // 출력 루트 없음 → 빈 목록
    }
}
