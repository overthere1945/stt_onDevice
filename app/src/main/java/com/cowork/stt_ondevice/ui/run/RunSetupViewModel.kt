// 파일명: ui/run/RunSetupViewModel.kt
// 목적: 실행 설정 화면의 상태(입력 언어·파일 선택, 모델 선택 순서, VAD 프리셋 선택, chunk 모드·maxChunk·스레드)와 실행 설정(RunConfig) 조립을 담당하는 ViewModel
// 기능: - 입력 목록은 스캐너(InputScanner.scan(input_sound))로 읽어 언어 → 상대 폴더 → 파일 트리로 보여 준다(폴더 단위 전체 선택 지원).
//       - 모델은 설치 상태가 READY 인 ASR 모델만 고를 수 있다(그 밖은 사유 "미설치"/"손상"/"다운로드 중" 등을 보이고 선택 불가). 선택 순서 = 실행 순서.
//       - VAD 프리셋은 계열이 모델을 쓰지 않거나(NONE/WEBRTC/TEN) 그 계열의 카탈로그 VAD 모델이 READY 일 때만 고를 수 있다(아니면 "VAD 모델 미설치").
//       - 예상 조합 수 = Σ(선택 모델 × 선택 프리셋 × 그 모델이 지원하는 언어의 선택 파일) 를 상태에 실시간으로 반영한다.
//       - buildConfig(now) 는 runId = now 의 yyyyMMdd_HHmmss 로 RunConfig 를 만든다(선택이 부족하거나 값 오류가 있으면 한국어 IllegalStateException).
//       - 다른 모델·프리셋으로 자동 대체하지 않는다(조용한 fallback 금지).
//       change(add)-hyungchul-2026-10-11-03:59 - preselect(path): 마이크 화면 "실행 설정으로" 에서 넘어온 녹음 파일을 다시 스캔한 뒤 선택하고 그 언어를 켠다.
//       - runImport(block): SAF 음원 가져오기 작업(복사 함수는 화면이 SafBridge 로 만든다)을 1개만 돌리고(취소 가능), 결과를 한국어 메시지로 보인 뒤 목록을 다시 읽는다.
//       change(add)-hyungchul-2026-10-11-19:10 (2차 작업 5) 시스템 엔진 모델의 maxChunk 덮어쓰기는 RunConfig 와 같은 범위(0 초 초과 60 초 이하)·같은 문구로 검증한다.
//       change(add)-hyungchul-2026-10-11-12:51 (T3 이월) 스캐너는 InputScanResult(항목 + 입력 오류)를 돌려주고, 입력 오류(같은 폴더에 대소문자만 다른 같은 이름 wav 등)는 state.inputErrors 로 화면에 보인다.
// change(add)-hyungchul-2026-10-11-03:31
package com.cowork.stt_ondevice.ui.run // 실행 화면 패키지

import androidx.lifecycle.ViewModel // 뷰모델 기본 클래스
import androidx.lifecycle.ViewModelProvider // 뷰모델 팩토리 인터페이스
import androidx.lifecycle.viewModelScope // 뷰모델 코루틴 범위
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.core.storage.InputScanResult // 입력 스캔 결과(항목 + 오류) // change(add)-hyungchul-2026-10-11-12:51
import com.cowork.stt_ondevice.core.storage.InputScanner // 입력 스캐너
import com.cowork.stt_ondevice.core.storage.SafCopyResult // change(add)-hyungchul-2026-10-11-03:59 SAF 복사 결과
import com.cowork.stt_ondevice.model.InstallState // 설치 상태
import com.cowork.stt_ondevice.model.ModelCatalog // 모델 카탈로그
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소
import com.cowork.stt_ondevice.output.PresetEntry // 프리셋 파라미터 문자열 도우미
import com.cowork.stt_ondevice.runner.RunConfig // 실행 설정
import com.cowork.stt_ondevice.runner.RunUiState // 실행 상태(시작 실패 사유 문구용)
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import com.cowork.stt_ondevice.ui.models.ModelsViewModel // 언어 라벨 도우미
import com.cowork.stt_ondevice.ui.models.koreanLabel // 설치 상태 한국어 라벨
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.VadPresets // VAD 프리셋 레지스트리
import kotlinx.coroutines.CancellationException // change(add)-hyungchul-2026-10-11-03:59 취소 예외
import kotlinx.coroutines.CoroutineDispatcher // 디스패처 타입
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.Job // change(add)-hyungchul-2026-10-11-03:59 가져오기 작업 핸들
import kotlinx.coroutines.flow.MutableStateFlow // 변경 가능한 상태 흐름
import kotlinx.coroutines.flow.StateFlow // 읽기 전용 상태 흐름
import kotlinx.coroutines.flow.asStateFlow // 읽기 전용 변환
import kotlinx.coroutines.flow.update // 원자적 갱신
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withContext // change(add)-hyungchul-2026-10-11-03:59 컨텍스트 전환
import java.io.File // change(add)-hyungchul-2026-10-11-03:59 파일
import java.util.concurrent.atomic.AtomicReference // change(add)-hyungchul-2026-10-11-03:59 미리 선택할 경로(스레드 안전)
import java.time.LocalDateTime // 현재 시각
import java.time.format.DateTimeFormatter // 시각 서식

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 모델 선택 목록의 한 행
 * 기능: 화면에 그릴 값과 선택 가능 여부를 담는다.
 * 입력: id(모델 id), name(표시 이름), group(algo — 묶음 머리글), languages(한국어 언어 라벨), langCodes(엔진 언어 코드),
 *       defaultMaxChunkSec(모델 기본 최대 chunk 길이, 0 = 제한 없음), selectable(READY 여부), disabledReason(선택 불가 사유, 가능하면 null)
 */
data class ModelOption(
    val id: String, // 모델 id
    val name: String, // 표시 이름
    val group: String, // algo 묶음
    val languages: String, // 언어 라벨
    val langCodes: Set<String>, // 엔진 언어 코드
    val defaultMaxChunkSec: Double, // 기본 최대 chunk(초)
    val selectable: Boolean, // 선택 가능 여부
    val disabledReason: String?, // 선택 불가 사유
)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: VAD 프리셋 선택 목록의 한 행
 * 기능: 계열·파라미터 문자열(PresetEntry.describeParams 와 같은 형식)·출처·주의 문구와 선택 가능 여부를 담는다.
 * 입력: id(프리셋 id), family(계열), params(파라미터 문자열), source(값 출처), note(주의 문구), selectable(선택 가능 여부), disabledReason(사유 또는 null)
 */
data class PresetOption(
    val id: String, // 프리셋 id
    val family: VadFamily, // 계열
    val params: String, // 파라미터 문자열
    val source: String, // 출처
    val note: String, // 주의 문구
    val selectable: Boolean, // 선택 가능 여부
    val disabledReason: String?, // 선택 불가 사유
)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 입력 트리의 폴더 1개
 * 기능: 언어 폴더(kr/en) 아래 상대 폴더 하나와 그 안의 wav 항목(스캔 순서)을 묶는다.
 * 입력: lang(언어 폴더명), relDir(상대 폴더, 최상위면 빈 문자열), items(항목 목록)
 */
data class InputFolder(val lang: String, val relDir: String, val items: List<InputItem>)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 모델별 maxChunk 입력 칸 상태
 * 기능: 사용자가 친 글자 그대로와 오류 문구(정상이면 null)를 담는다.
 * 입력: text(입력 글자), error(오류 문구 또는 null)
 */
data class MaxChunkInput(val text: String, val error: String?)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 실행 설정 화면 상태
 * 기능: 화면이 그릴 모든 값. 선택은 id/항목 값으로 보관하고 실행 순서는 selectedModels 의 순서다.
 * 입력: loaded(첫 읽기 완료 여부), folders(입력 트리), langs(켜진 언어 폴더), selectedItems(선택한 항목), models(모델 행), selectedModels(선택 순서),
 *       presets(프리셋 행), selectedPresets(선택한 프리셋 id), chunkMode(묶기 방식), maxChunk(모델 id → 입력 칸), maxChunkOverride(모델 id → 검증된 값),
 *       threads(스레드 1~8), comboCount(예상 조합 수)
 *       change(add)-hyungchul-2026-10-11-03:59 importing(SAF 음원 가져오기 중), message(일회성 안내 — 화면이 보인 뒤 consumeMessage)
 *       change(add)-hyungchul-2026-10-11-12:51 inputErrors(입력 스캔 오류 문구 — 실행 목록에서 뺀 파일과 사유)
 */
data class RunSetupState(
    val loaded: Boolean = false, // 첫 읽기 완료
    val folders: List<InputFolder> = emptyList(), // 입력 트리
    val langs: Set<String> = InputScanner.LANG_FOLDERS.keys, // 켜진 언어(기본: kr, en 모두)
    val selectedItems: Set<InputItem> = emptySet(), // 선택한 항목
    val models: List<ModelOption> = emptyList(), // 모델 행
    val selectedModels: List<String> = emptyList(), // 선택 순서
    val presets: List<PresetOption> = emptyList(), // 프리셋 행
    val selectedPresets: Set<String> = emptySet(), // 선택한 프리셋
    val chunkMode: ChunkMode = ChunkMode.MERGE, // 묶기 방식(spec §8 기본 MERGE)
    val maxChunk: Map<String, MaxChunkInput> = emptyMap(), // 입력 칸 상태
    val maxChunkOverride: Map<String, Double> = emptyMap(), // 검증된 덮어쓰기 값
    val threads: Int = RunSetupViewModel.DEFAULT_THREADS, // 스레드
    val comboCount: Int = 0, // 예상 조합 수
    val importing: Boolean = false, // change(add)-hyungchul-2026-10-11-03:59 가져오기 중
    val message: String? = null, // change(add)-hyungchul-2026-10-11-03:59 안내 문구
    val inputErrors: List<String> = emptyList(), // 입력 스캔 오류 // change(add)-hyungchul-2026-10-11-12:51
)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 실행 설정 화면 ViewModel
 * 기능: 위 파일 머리말 참조. store 는 반드시 App 의 같은 인스턴스를 넘긴다(설치 진행 상태가 인스턴스 단위).
 * 입력: scanner(입력 목록을 돌려주는 함수 — 앱에서는 InputScanner.scanWithErrors(dirs.inputSound); change(add)-hyungchul-2026-10-11-12:51 항목 + 입력 오류), catalog(선택 대상 ASR 모델 목록), store(설치 상태 판정),
 *       presets(VAD 프리셋 목록, 기본 VadPresets.all), vadModelOf(계열 → 카탈로그 VAD 모델, 기본 ModelCatalog::vadModel),
 *       io(파일 확인용 디스패처 — 테스트에서 교체)
 */
class RunSetupViewModel(
    private val scanner: () -> InputScanResult, // 입력 스캐너(항목 + 오류) // change(add)-hyungchul-2026-10-11-12:51 타입 변경
    private val catalog: List<ModelSpec>, // ASR 모델 목록
    private val store: ModelStore, // 설치 상태
    private val presets: List<VadPreset> = VadPresets.all, // 프리셋 목록
    private val vadModelOf: (VadFamily) -> ModelSpec? = ModelCatalog::vadModel, // 계열 → VAD 모델
    private val io: CoroutineDispatcher = Dispatchers.IO, // 작업 디스패처
) : ViewModel() {

    private val specs: Map<String, ModelSpec> = catalog.associateBy { it.id } // id → 모델
    private val presetById: Map<String, VadPreset> = presets.associateBy { it.id } // id → 프리셋
    private val _state = MutableStateFlow(RunSetupState()) // 내부 상태
    private val pendingSelect = AtomicReference<String?>(null) // change(add)-hyungchul-2026-10-11-03:59 다음 스캔에서 선택할 파일 절대 경로
    private var importJob: Job? = null // change(add)-hyungchul-2026-10-11-03:59 진행 중인 가져오기(메인 스레드에서만 읽고 씀)

    /** 목적: 화면 상태 / 기능: 선택이 바뀔 때마다 comboCount 까지 다시 계산된 값 */
    val state: StateFlow<RunSetupState> = _state.asStateFlow() // 읽기 전용 노출

    init {
        refresh() // 첫 읽기
    }

    /**
     * 목적: 입력 목록·설치 상태 다시 읽기
     * 기능: io 에서 입력을 다시 스캔하고 모델·프리셋 행을 새로 만든다. 사라진 항목과 이제 선택할 수 없는 모델·프리셋은 선택에서 뺀다
     *       (다른 것으로 바꾸지 않음). 모델 탭에서 설치한 뒤 이 화면에 돌아오면 화면이 호출한다.
     * 입력: 없음
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun refresh() {
        viewModelScope.launch(io) { // 파일 확인은 백그라운드에서
            val scan = scanner() // 입력 스캔 // change(add)-hyungchul-2026-10-11-12:51 항목 + 오류
            val items = scan.items // 실행 대상 항목 // change(add)-hyungchul-2026-10-11-12:51
            val folders = items.groupBy { it.lang to it.relDir }.map { (k, v) -> InputFolder(k.first, k.second, v) } // 언어·폴더별 묶기(스캔 정렬 유지)
            val modelRows = catalog.map { buildModelOption(it) } // 모델 행
            val presetRows = presets.map { buildPresetOption(it) } // 프리셋 행
            val selectableModels = modelRows.filter { it.selectable }.map { it.id }.toSet() // 선택 가능 모델
            val selectablePresets = presetRows.filter { it.selectable }.map { it.id }.toSet() // 선택 가능 프리셋
            val itemSet = items.toSet() // 존재 항목
            val wanted = pendingSelect.get() // change(add)-hyungchul-2026-10-11-03:59 미리 선택할 경로
            val pick = wanted?.let { w -> items.firstOrNull { it.file.absolutePath == w } } // change(add)-hyungchul-2026-10-11-03:59 그 항목(아직 없으면 null)
            if (pick != null) pendingSelect.compareAndSet(wanted, null) // change(add)-hyungchul-2026-10-11-03:59 찾았으면 소비
            _state.update { s ->
                recount(
                    s.copy(
                        loaded = true, // 읽기 완료
                        folders = folders, // 트리
                        langs = if (pick != null) s.langs + pick.lang else s.langs, // change(add)-hyungchul-2026-10-11-03:59 미리 선택 항목의 언어 켜기
                        selectedItems = s.selectedItems.filter { it in itemSet }.toSet() + listOfNotNull(pick), // 사라진 항목 제외(change(add)-hyungchul-2026-10-11-03:59 미리 선택 항목 추가)
                        models = modelRows, // 모델 행
                        selectedModels = s.selectedModels.filter { it in selectableModels }, // 선택 불가가 된 모델 제외(순서 유지)
                        presets = presetRows, // 프리셋 행
                        selectedPresets = s.selectedPresets.filter { it in selectablePresets }.toSet(), // 선택 불가가 된 프리셋 제외
                        inputErrors = scan.errors, // 입력 오류 // change(add)-hyungchul-2026-10-11-12:51
                    ),
                )
            }
        }
    }

    /**
     * 목적: 모델 행 만들기
     * 기능: READY 면 선택 가능, 아니면 설치 상태의 한국어 라벨(미설치/손상/다운로드 중/검증 중)을 사유로 둔다.
     * 입력: spec(모델)
     * 출력: 없음
     * 리턴: ModelOption
     */
    private fun buildModelOption(spec: ModelSpec): ModelOption {
        val st = store.state(spec) // 설치 상태
        return ModelOption(
            id = spec.id, // id
            name = spec.displayName, // 이름
            group = spec.algo, // algo 묶음
            languages = ModelsViewModel.languageLabel(spec.languages), // 언어 라벨
            langCodes = spec.languages, // 언어 코드
            defaultMaxChunkSec = spec.maxChunkSec, // 기본 최대 chunk
            selectable = st == InstallState.READY, // READY 만 선택 가능
            disabledReason = if (st == InstallState.READY) null else st.koreanLabel(), // 사유
        )
    }

    /**
     * 목적: 프리셋 행 만들기
     * 기능: 계열이 모델을 쓰지 않으면 선택 가능. 쓰면 카탈로그 VAD 모델이 READY 일 때만 가능하고, 아니면 "VAD 모델 미설치"
     *       (카탈로그에 그 계열 모델이 없으면 "VAD 모델 없음(카탈로그)").
     * 입력: preset(프리셋)
     * 출력: 없음
     * 리턴: PresetOption
     */
    private fun buildPresetOption(preset: VadPreset): PresetOption {
        val reason: String? = if (preset.family in MODEL_FREE_FAMILIES) null else { // 모델 불필요 계열은 사유 없음
            val spec = vadModelOf(preset.family) // 계열 모델
            when {
                spec == null -> "VAD 모델 없음(카탈로그)" // 카탈로그에 없음
                store.state(spec) != InstallState.READY -> "VAD 모델 미설치" // 미설치·손상 등
                else -> null // 사용 가능
            }
        }
        return PresetOption(
            id = preset.id, // id
            family = preset.family, // 계열
            params = PresetEntry.describeParams(preset.params), // 파라미터 문자열(manifest 와 같은 형식)
            source = preset.source, // 출처
            note = preset.note, // 주의 문구
            selectable = reason == null, // 선택 가능 여부
            disabledReason = reason, // 사유
        )
    }

    /**
     * 목적: 입력 언어 켜기/끄기
     * 기능: 언어 폴더(kr/en)를 켜거나 끈다. 꺼진 언어의 항목은 선택이 남아 있어도 조합 수·설정에서 빠진다.
     * 입력: lang(언어 폴더명)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun toggleLang(lang: String) {
        _state.update { s -> recount(s.copy(langs = if (lang in s.langs) s.langs - lang else s.langs + lang)) } // 토글 후 재계산
    }

    /**
     * 목적: 입력 파일 1개 선택/해제
     * 기능: 선택 집합에 넣거나 뺀다.
     * 입력: item(입력 항목)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun toggleItem(item: InputItem) {
        _state.update { s -> recount(s.copy(selectedItems = if (item in s.selectedItems) s.selectedItems - item else s.selectedItems + item)) } // 토글 후 재계산
    }

    /**
     * 목적: 폴더 단위 전체 선택/해제
     * 기능: 그 폴더 항목이 모두 선택돼 있으면 모두 해제, 아니면 모두 선택한다.
     * 입력: lang(언어 폴더명), relDir(상대 폴더)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun toggleFolder(lang: String, relDir: String) {
        _state.update { s ->
            val items = s.folders.firstOrNull { it.lang == lang && it.relDir == relDir }?.items ?: return@update s // 폴더 항목(없으면 그대로)
            val allSelected = items.all { it in s.selectedItems } // 모두 선택됨?
            recount(s.copy(selectedItems = if (allSelected) s.selectedItems - items.toSet() else s.selectedItems + items)) // 전체 해제/선택
        }
    }

    /**
     * 목적: 모델 선택/해제(선택 순서 유지)
     * 기능: 선택돼 있으면 빼고, 아니면 선택 가능한(READY) 모델일 때만 맨 뒤에 붙인다. 선택 불가 모델은 무시한다.
     * 입력: id(모델 id)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun toggleModel(id: String) {
        _state.update { s ->
            when {
                id in s.selectedModels -> recount(s.copy(selectedModels = s.selectedModels - id)) // 해제
                s.models.firstOrNull { it.id == id }?.selectable == true -> recount(s.copy(selectedModels = s.selectedModels + id)) // 맨 뒤에 추가
                else -> s // 선택 불가(미설치 등)
            }
        }
    }

    /**
     * 목적: VAD 프리셋 선택/해제
     * 기능: 선택돼 있으면 빼고, 아니면 선택 가능한 프리셋일 때만 넣는다.
     * 입력: id(프리셋 id)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun togglePreset(id: String) {
        _state.update { s ->
            when {
                id in s.selectedPresets -> recount(s.copy(selectedPresets = s.selectedPresets - id)) // 해제
                s.presets.firstOrNull { it.id == id }?.selectable == true -> recount(s.copy(selectedPresets = s.selectedPresets + id)) // 선택
                else -> s // 선택 불가
            }
        }
    }

    /**
     * 목적: chunk 묶기 방식 선택
     * 기능: MERGE/INDIVIDUAL 중 하나로 바꾼다.
     * 입력: m(묶기 방식)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun setChunkMode(m: ChunkMode) {
        _state.update { it.copy(chunkMode = m) } // 설정
    }

    /**
     * 목적: 모델별 maxChunk 덮어쓰기 값 설정(숫자)
     * 기능: null 이면 덮어쓰기 해제(모델 기본값 사용). 유한하고 0 이상이면 반영(0 = 제한 없음). 그 밖(NaN·무한대·음수)은 상태를 바꾸지 않고 false
     *       (화면 입력 칸의 잘못된 글자는 setMaxChunkText 가 오류로 표시한다).
     * 입력: id(모델 id), sec(초 또는 null)
     * 출력: state 갱신
     * 리턴: 반영했으면 true, 거부했으면 false
     */
    fun setMaxChunk(id: String, sec: Double?): Boolean {
        if (sec != null && maxChunkError(id, sec) != null) return false // 잘못된 값은 거부(상태 그대로) // change(add)-hyungchul-2026-10-11-19:10 모델 id 전달(시스템 엔진 범위)
        return applyMaxChunk(id, sec?.toString() ?: "", sec, null) // 반영
    }

    /**
     * 목적: 모델별 maxChunk 입력 칸 글자 반영(화면 입력용)
     * 기능: 빈 칸이면 해제, 숫자가 아니면 "숫자를 입력하세요" 오류, 숫자면 setMaxChunk 와 같은 검증.
     * 입력: id(모델 id), text(입력 글자)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun setMaxChunkText(id: String, text: String) {
        val trimmed = text.trim() // 앞뒤 공백 제거
        if (trimmed.isEmpty()) { applyMaxChunk(id, text, null, null); return } // 빈 칸 = 기본값
        val v = trimmed.toDoubleOrNull() // 숫자 해석
        applyMaxChunk(id, text, v, if (v == null) "숫자를 입력하세요" else maxChunkError(id, v)) // 검증 후 반영 // change(add)-hyungchul-2026-10-11-19:10 모델 id 전달
    }

    /**
     * 목적: maxChunk 값 검증 문구
     * 기능: 유한하고 0 이상이면 null, 아니면 한국어 오류 문구.
     *       change(add)-hyungchul-2026-10-11-19:10 시스템 엔진 모델이면 RunConfig 와 같은 범위(0 초 초과 60 초 이하)·같은 문구를 먼저 적용한다(화면 검증과 RunConfig 생성 검증이 어긋나지 않게).
     * 입력: id(모델 id), sec(초)
     * 출력: 없음
     * 리턴: 오류 문구 또는 null
     */
    private fun maxChunkError(id: String, sec: Double): String? =
        specs[id]?.let { RunConfig.systemMaxChunkError(it, id, sec) } // 시스템 엔진 범위 문구(해당 없으면 null) // change(add)-hyungchul-2026-10-11-19:10
            ?: if (sec.isFinite() && sec >= 0.0) null else "0 이상의 유한한 값이어야 합니다" // 검증

    /**
     * 목적: maxChunk 입력 상태·검증 값 함께 갱신
     * 기능: 오류가 없고 값이 있으면 덮어쓰기에 넣고, 값이 없거나 오류면 덮어쓰기에서 뺀다. 입력 칸 상태는 항상 기록한다.
     * 입력: id(모델 id), text(입력 글자), value(해석한 값 또는 null), error(오류 문구 또는 null)
     * 출력: state 갱신
     * 리턴: 오류가 없으면 true
     */
    private fun applyMaxChunk(id: String, text: String, value: Double?, error: String?): Boolean {
        _state.update { s ->
            val overrides = if (error == null && value != null) s.maxChunkOverride + (id to value) else s.maxChunkOverride - id // 검증된 값만 보관
            s.copy(maxChunk = s.maxChunk + (id to MaxChunkInput(text, error)), maxChunkOverride = overrides) // 입력 칸 + 값
        }
        return error == null // 결과
    }

    /**
     * 목적: 스레드 수 설정
     * 기능: 1~8 범위로 맞춰 반영한다.
     * 입력: n(스레드 수)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun setThreads(n: Int) {
        _state.update { it.copy(threads = n.coerceIn(MIN_THREADS, MAX_THREADS)) } // 범위 제한
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: 파일 미리 선택(마이크 녹음 → 실행 설정)
     * 기능: 경로를 기억하고 다시 스캔한다. 스캔 결과에 그 파일이 있으면 선택에 넣고 그 언어를 켠다(없으면 다음 스캔까지 기다림 — 다른 파일로 바꾸지 않음).
     * 입력: file(선택할 wav)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun preselect(file: File) {
        pendingSelect.set(file.absolutePath) // 기억
        refresh() // 다시 스캔
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: SAF 음원 가져오기 실행
     * 기능: 이미 가져오는 중이면 거부 메시지. 아니면 importing 을 켜고 io 에서 block 을 실행한 뒤
     *       "음원 N개를 가져왔습니다(건너뜀 M개 — 같은 이름 파일이 이미 있음 등)" 또는 "음원 가져오기 실패: 사유"/취소 안내를 보이고 목록을 다시 읽는다.
     * 입력: block(복사 함수 — 화면이 SafBridge.importTree 로 만든다)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun runImport(block: suspend () -> SafCopyResult) {
        if (_state.value.importing) { // 이미 진행 중
            _state.update { it.copy(message = "이미 음원을 가져오는 중입니다") } // 거부
            return // 중단
        }
        _state.update { it.copy(importing = true) } // 진행 표시
        importJob = viewModelScope.launch {
            try {
                val r = withContext(io) { block() } // 복사
                val skipped = if (r.skipped > 0) " (건너뜀 ${r.skipped}개 — 같은 이름 파일이 이미 있거나 쓸 수 없는 이름)" else "" // 건너뜀 안내
                _state.update { it.copy(message = "음원 ${r.copied}개를 가져왔습니다$skipped") } // 성공
            } catch (e: CancellationException) {
                _state.update { it.copy(message = "음원 가져오기를 취소했습니다(이미 복사된 파일은 남아 있습니다)") } // 취소
                throw e // 취소는 삼키지 않음
            } catch (e: Exception) {
                _state.update { it.copy(message = "음원 가져오기 실패: ${e.message ?: e::class.java.simpleName}") } // 실패
            } finally {
                _state.update { it.copy(importing = false) } // 진행 표시 해제
                importJob = null // 핸들 정리
                refresh() // 목록 다시 읽기(취소된 코루틴이 아닌 새 코루틴에서)
            }
        }
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: 가져오기 취소 / 기능: 진행 중인 가져오기를 취소한다(파일 사이·64KB 마다 멈춤) / 입력·출력: 없음 / 리턴: 없음
     */
    fun cancelImport() {
        importJob?.cancel() // 취소
    }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: 안내 문구 소비 / 기능: 화면이 스낵바로 보인 뒤 부른다 / 입력·출력: 없음 / 리턴: 없음
     */
    fun consumeMessage() {
        _state.update { it.copy(message = null) } // 비우기
    }

    /**
     * 목적: 예상 조합 수
     * 기능: 현재 상태로 Σ(선택 모델 × 선택 프리셋 × 그 모델 언어의 선택 파일)을 계산한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 조합(항목 실행) 수
     */
    fun comboCount(): Int = countCombos(_state.value) // 현재 상태로 계산

    /**
     * 목적: 실행할 수 없는 사유
     * 기능: 모델·프리셋·파일이 하나도 없거나, maxChunk 입력 오류가 있거나(선택 모델), 조합 수가 0 이면 한국어 사유를 돌려준다.
     * 입력: s(판정할 상태, 기본은 현재 상태 — change(add)-hyungchul-2026-10-11-03:31 화면은 자신이 그리는 상태 값을 넘겨 화면과 판정이 어긋나지 않게 함)
     * 출력: 없음
     * 리턴: 사유 또는 null(실행 가능)
     */
    fun validationError(s: RunSetupState = _state.value): String? = validationErrorOf(s) // 주어진 상태로 판정

    /**
     * 목적: 실행 설정 만들기
     * 기능: 사유가 있으면 IllegalStateException(한국어). 아니면 runId = now 의 yyyyMMdd_HHmmss, 항목 = 켜진 언어의 선택 파일(스캔 순서),
     *       모델 = 선택 순서, 프리셋 = 목록 순서, maxChunkOverride = 선택 모델의 검증된 값만, threads·chunkMode 그대로, resume=false.
     * 입력: now(현재 시각)
     * 출력: 실행 불가면 IllegalStateException
     * 리턴: RunConfig
     */
    fun buildConfig(now: LocalDateTime): RunConfig {
        val s = _state.value // 현재 상태
        validationErrorOf(s)?.let { throw IllegalStateException(it) } // 실행 불가
        val models = s.selectedModels.map { specs.getValue(it) } // 선택 순서대로 모델
        return RunConfig(
            runId = now.format(RUN_ID_FORMAT), // 실행 ID
            items = effectiveItems(s), // 입력 항목
            models = models, // 모델
            presets = presets.filter { it.id in s.selectedPresets }, // 프리셋(목록 순서)
            chunkMode = s.chunkMode, // 묶기 방식
            maxChunkOverride = s.maxChunkOverride.filterKeys { it in s.selectedModels }, // 선택 모델 값만
            threads = s.threads, // 스레드
            resume = false, // 새 실행
        )
    }

    /**
     * 목적: 상태의 조합 수 다시 계산
     * 기능: comboCount 필드만 새 값으로 바꾼 복사본을 돌려준다.
     * 입력: s(상태)
     * 출력: 없음
     * 리턴: 갱신된 상태
     */
    private fun recount(s: RunSetupState): RunSetupState = s.copy(comboCount = countCombos(s)) // 조합 수 갱신

    /**
     * 목적: 조합 수 계산
     * 기능: 선택 모델마다 (선택 프리셋 수 × 그 모델 언어를 지원하는 선택 파일 수)를 더한다.
     * 입력: s(상태)
     * 출력: 없음
     * 리턴: 조합 수
     */
    private fun countCombos(s: RunSetupState): Int {
        val items = effectiveItems(s) // 실행 대상 파일
        val presetCount = s.selectedPresets.size // 프리셋 수
        return s.selectedModels.sumOf { id -> // 모델마다
            val langs = specs[id]?.languages ?: emptySet() // 모델 언어
            presetCount * items.count { it.engineLang in langs } // 프리셋 × 지원 파일
        }
    }

    /**
     * 목적: 실행 대상 파일 목록
     * 기능: 트리(스캔) 순서로, 켜진 언어의 선택 파일만 고른다.
     * 입력: s(상태)
     * 출력: 없음
     * 리턴: 항목 목록
     */
    private fun effectiveItems(s: RunSetupState): List<InputItem> =
        s.folders.flatMap { it.items }.filter { it.lang in s.langs && it in s.selectedItems } // 순서 유지 필터

    /**
     * 목적: 실행 불가 사유 판정
     * 기능: validationError 설명 참조.
     * 입력: s(상태)
     * 출력: 없음
     * 리턴: 사유 또는 null
     */
    private fun validationErrorOf(s: RunSetupState): String? = when {
        s.selectedModels.isEmpty() -> "모델을 1개 이상 선택하세요" // 모델 없음
        s.selectedPresets.isEmpty() -> "VAD 프리셋을 1개 이상 선택하세요" // 프리셋 없음
        effectiveItems(s).isEmpty() -> "입력 파일을 1개 이상 선택하세요" // 파일 없음
        s.selectedModels.any { s.maxChunk[it]?.error != null } -> "최대 chunk 길이 입력에 오류가 있습니다" // 입력 오류
        countCombos(s) == 0 -> "선택한 모델이 지원하는 언어의 파일이 없습니다(예상 조합 0건)" // 조합 없음
        else -> null // 실행 가능
    }

    companion object {
        const val DEFAULT_THREADS = 4 // 기본 스레드(spec §10)
        const val MIN_THREADS = 1 // 스레드 하한
        const val MAX_THREADS = 8 // 스레드 상한
        private val RUN_ID_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss") // runId 서식
        private val MODEL_FREE_FAMILIES = setOf(VadFamily.NONE, VadFamily.WEBRTC, VadFamily.TEN) // 모델이 필요 없는 계열(VadEngineFactory 와 같음)

        /**
         * 목적: 시작 실패 사유 문구
         * 기능: RunService.start 가 false 를 돌려준 직후의 상태에서 사람이 읽을 사유를 만든다
         *       (Running 의 rejectedStart, Failed 의 message, 그 밖은 일반 문구).
         * 입력: state(RunService.events 의 현재 값)
         * 출력: 없음
         * 리턴: 한국어 문구
         */
        fun startFailureReason(state: RunUiState): String = when (state) {
            is RunUiState.Running -> state.rejectedStart ?: "이미 실행 중(${state.runId})이라 시작할 수 없습니다" // 실행 중 거부
            is RunUiState.Failed -> state.message // 서비스 시작 실패
            else -> "실행을 시작하지 못했습니다" // 그 밖
        }

        /**
         * 목적: 앱 전역 객체로 ViewModel 팩토리 만들기
         * 기능: 입력 스캐너 = InputScanner.scanWithErrors(dirs.inputSound)(change(add)-hyungchul-2026-10-11-12:51 오류 포함), 카탈로그 = ModelCatalog.asr, store = App 싱글턴.
         * 입력: app(App 인스턴스)
         * 출력: 없음
         * 리턴: ViewModelProvider.Factory
         */
        fun factory(app: App): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // 요청 클래스는 RunSetupViewModel 뿐
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(RunSetupViewModel::class.java)) { "지원하지 않는 ViewModel: ${modelClass.name}" } // 다른 타입 거부
                return RunSetupViewModel({ InputScanner.scanWithErrors(app.dirs.inputSound) }, ModelCatalog.asr, app.modelStore) as T // 싱글턴 주입 // change(add)-hyungchul-2026-10-11-12:51 오류 포함 스캔
            }
        }
    }
}
