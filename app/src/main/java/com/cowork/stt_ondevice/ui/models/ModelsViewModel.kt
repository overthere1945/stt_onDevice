// 파일명: ui/models/ModelsViewModel.kt
// 목적: 모델 관리 화면의 상태(ModelRow 목록)와 동작(다운로드·취소·가져오기·검증·삭제)을 담당하는 ViewModel
// 기능: 카탈로그 각 모델의 설치 상태를 ModelStore 에서 읽어 rows 로 노출하고, 오래 걸리는 작업은 viewModelScope + IO 디스패처에서 모델 id 당 1개만 실행한다.
//       작업이 끝나면(성공·실패·취소) 상태를 다시 읽고, 실패는 한국어 메시지(messages)로 알린다. 가져오기는 adb push 폴더(files/import/<모델id>/)를 쓴다.
//       SAF(Uri) 가져오기는 Task 30 에서 이 클래스의 importFrom(id, source: File) 앞단에 Uri → 임시 파일/폴더 복사를 붙여 추가한다(확장 지점).
package com.cowork.stt_ondevice.ui.models // 모델 화면 패키지

import androidx.lifecycle.ViewModel // 뷰모델 기본 클래스
import androidx.lifecycle.ViewModelProvider // 뷰모델 팩토리 인터페이스
import androidx.lifecycle.viewModelScope // 뷰모델 코루틴 범위
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 상태
import com.cowork.stt_ondevice.model.InstallState // 설치 상태
import com.cowork.stt_ondevice.model.ModelCatalog // 카탈로그
import com.cowork.stt_ondevice.model.ModelInstaller // 설치기
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 저장소
import kotlinx.coroutines.CancellationException // 취소 예외
import kotlinx.coroutines.CoroutineDispatcher // 디스패처 타입
import kotlinx.coroutines.CoroutineStart // 코루틴 시작 방식
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.Job // 작업 핸들
import kotlinx.coroutines.channels.Channel // 일회성 메시지 채널
import kotlinx.coroutines.flow.Flow // 흐름
import kotlinx.coroutines.flow.MutableStateFlow // 변경 가능한 상태 흐름
import kotlinx.coroutines.flow.StateFlow // 읽기 전용 상태 흐름
import kotlinx.coroutines.flow.asStateFlow // 읽기 전용 변환
import kotlinx.coroutines.flow.receiveAsFlow // 채널 → 흐름
import kotlinx.coroutines.flow.update // 원자적 갱신
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.io.FileNotFoundException // 파일 없음
import java.util.concurrent.ConcurrentHashMap // 스레드 안전 맵

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 모델 목록 화면의 한 행
 * 기능: 화면에 그릴 값을 모은다(진행률은 받는 중일 때만 0~1, 아니면 null).
 * 입력: id(모델 id), group(그룹 이름: ASR 은 algo, VAD 는 "VAD 모델"), name(표시 이름), sizeMb(파일 크기 합 MB, 올림), languages(한국어 라벨),
 *       license(라이선스 이름), commercial(상업 이용 상태), state(설치 상태), progress(다운로드 진행률 또는 null), hasDownload(다운로드 경로 보유 여부)
 */
data class ModelRow(
    val id: String, // 모델 id
    val group: String, // 그룹 이름
    val name: String, // 표시 이름
    val sizeMb: Long, // 크기(MB)
    val languages: String, // 언어 라벨
    val license: String, // 라이선스
    val commercial: CommercialStatus, // 상업 이용 상태
    val state: InstallState, // 설치 상태
    val progress: Float?, // 진행률(0~1) 또는 null
    val hasDownload: Boolean, // 다운로드 가능 여부(false 면 import 전용)
)

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 가져오기 안내 대화상자 상태
 * 기능: 사용자가 파일을 넣어야 할 정확한 폴더 경로를 담는다.
 * 입력: modelId(대상 모델 id), modelName(표시 이름), folderPath(adb push 대상 폴더 절대 경로)
 */
data class ImportPrompt(val modelId: String, val modelName: String, val folderPath: String)

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 검증 결과 대화상자 상태
 * 기능: 모델 이름과 문제 목록을 담는다(문제가 없으면 목록이 비어 있음).
 * 입력: modelName(표시 이름), problems(문제 설명 목록)
 */
data class VerifyReport(val modelName: String, val problems: List<String>)

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 상업 이용 상태의 한국어 라벨
 * 기능: ALLOWED=상업 가능, CONDITIONAL=조건부, NONCOMMERCIAL=비상업, UNVERIFIED=미확인
 * 입력: 없음(수신 객체)
 * 출력: 없음
 * 리턴: 한국어 문자열
 */
fun CommercialStatus.koreanLabel(): String = when (this) {
    CommercialStatus.ALLOWED -> "상업 가능" // 허용
    CommercialStatus.CONDITIONAL -> "조건부" // 조건부
    CommercialStatus.NONCOMMERCIAL -> "비상업" // 비상업
    CommercialStatus.UNVERIFIED -> "미확인" // 미확인
}

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 설치 상태의 한국어 라벨
 * 기능: NOT_INSTALLED=미설치, DOWNLOADING=다운로드 중, VERIFYING=검증 중, READY=설치됨, CORRUPT=손상
 * 입력: 없음(수신 객체)
 * 출력: 없음
 * 리턴: 한국어 문자열
 */
fun InstallState.koreanLabel(): String = when (this) {
    InstallState.NOT_INSTALLED -> "미설치" // 미설치
    InstallState.DOWNLOADING -> "다운로드 중" // 받는 중
    InstallState.VERIFYING -> "검증 중" // 검증 중
    InstallState.READY -> "설치됨" // 사용 가능
    InstallState.CORRUPT -> "손상" // 손상
}

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 모델 관리 화면 ViewModel
 * 기능: 위 파일 머리말 참조. 앱 전역 store/installer 와 같은 인스턴스를 받아야 사용 횟수·동시 설치 보호가 유지된다.
 * 입력: store(모델 저장소), installer(설치기), catalog(표시할 모델 목록, 기본은 전체 카탈로그), importRoot(adb push 가져오기 루트: files/import),
 *       io(오래 걸리는 작업용 디스패처 — 테스트에서 교체)
 */
class ModelsViewModel(
    private val store: ModelStore, // 모델 저장소
    private val installer: ModelInstaller, // 설치기
    private val catalog: List<ModelSpec> = ModelCatalog.all, // 표시 목록
    private val importRoot: File, // 가져오기 루트
    private val io: CoroutineDispatcher = Dispatchers.IO, // 작업 디스패처
) : ViewModel() {

    private val specs: Map<String, ModelSpec> = catalog.associateBy { it.id } // id → spec
    private val _rows = MutableStateFlow<List<ModelRow>>(emptyList()) // 행 목록(내부)
    private val progress = ConcurrentHashMap<String, Float>() // 받는 중인 모델의 진행률
    private val jobs = ConcurrentHashMap<String, Job>() // 모델 id → 진행 중인 작업(모델당 1개)
    private val busy = ConcurrentHashMap<String, InstallState>() // change(add)-hyungchul-2026-10-11-03:24 작업 시작 직후부터 행에 보일 상태(DOWNLOADING/VERIFYING) — 설치기가 상태를 올리기 전 구간 보완
    private val messageChannel = Channel<String>(Channel.BUFFERED) // 일회성 메시지(스낵바)
    private val _importPrompt = MutableStateFlow<ImportPrompt?>(null) // 가져오기 안내
    private val _verifyReport = MutableStateFlow<VerifyReport?>(null) // 검증 결과

    /** 목적: 화면에 그릴 행 목록 / 기능: 카탈로그 순서(ASR → VAD), 상태가 바뀔 때마다 갱신 */
    val rows: StateFlow<List<ModelRow>> = _rows.asStateFlow() // 읽기 전용 노출

    /** 목적: 일회성 사용자 메시지 / 기능: 한국어 오류·완료 메시지를 순서대로 흘린다(스낵바 표시용) */
    val messages: Flow<String> = messageChannel.receiveAsFlow() // 채널 → 흐름

    /** 목적: 가져오기 안내 상태 / 기능: null 이면 대화상자 없음 */
    val importPrompt: StateFlow<ImportPrompt?> = _importPrompt.asStateFlow() // 읽기 전용 노출

    /** 목적: 검증 결과 상태 / 기능: null 이면 대화상자 없음 */
    val verifyReport: StateFlow<VerifyReport?> = _verifyReport.asStateFlow() // 읽기 전용 노출

    init {
        refresh() // 첫 상태 읽기
    }

    /**
     * 목적: 상태 다시 읽기
     * 기능: 작업 디스패처에서 모든 행을 새로 만들어 rows 에 넣는다(파일 확인이 있으므로 메인 스레드에서 하지 않음).
     * 입력: 없음
     * 출력: rows 갱신
     * 리턴: 없음
     */
    fun refresh() {
        viewModelScope.launch(io) { // 백그라운드에서
            _rows.update { buildRows() } // change(add)-hyungchul-2026-10-11-03:24 모든 갱신을 update(CAS) 한 경로로 통일 — 동시 갱신 시 최신 값으로 다시 계산
        }
    }

    // change(add)-hyungchul-2026-10-11-03:24
    /**
     * 목적: 모델 1개의 행만 다시 만들기
     * 기능: 그 모델의 상태·진행률을 새로 읽어 해당 행만 교체한다(진행 콜백·작업 시작 시 사용, update 로 다른 갱신과 직렬화).
     * 입력: spec(대상 모델)
     * 출력: rows 갱신(아직 첫 목록이 없으면 그대로)
     * 리턴: 없음
     */
    private fun updateRow(spec: ModelSpec) {
        _rows.update { list -> list.map { if (it.id == spec.id) buildRow(spec) else it } } // 해당 행만 교체
    }

    /**
     * 목적: 행 목록 구성
     * 기능: 카탈로그 각 모델의 상태·진행률로 ModelRow 를 만든다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 행 목록
     */
    private fun buildRows(): List<ModelRow> = catalog.map { spec -> buildRow(spec) } // 전체 행

    // change(add)-hyungchul-2026-10-11-03:24
    /**
     * 목적: 모델 1개의 행 구성
     * 기능: 상태는 설치기가 진행 상태(DOWNLOADING/VERIFYING)를 올렸으면 그것, 아니면 이 ViewModel 이 작업을 시작해 둔 상태(busy), 아니면 저장소 판정.
     *       진행률은 받는 중일 때만 채운다.
     * 입력: spec(대상 모델)
     * 출력: 없음
     * 리턴: ModelRow
     */
    private fun buildRow(spec: ModelSpec): ModelRow {
        val real = store.state(spec) // 저장소 판정
        val state = if (real == InstallState.DOWNLOADING || real == InstallState.VERIFYING) real else busy[spec.id] ?: real // 표시 상태
        return ModelRow(
            id = spec.id, // 모델 id
            group = if (spec.kind == ModelKind.VAD) VAD_GROUP else spec.algo, // 그룹
            name = spec.displayName, // 표시 이름
            sizeMb = megabytesCeil(spec.files.sumOf { it.bytes }), // 크기(MB, 올림)
            languages = languageLabel(spec.languages), // 언어 라벨
            license = spec.license, // 라이선스
            commercial = spec.commercial, // 상업 이용 상태
            state = state, // 설치 상태
            progress = if (state == InstallState.DOWNLOADING) progress[spec.id] else null, // 받는 중일 때만 진행률
            hasDownload = spec.downloads.isNotEmpty(), // 다운로드 가능 여부
        )
    }

    /**
     * 목적: 모델 내려받기 시작
     * 기능: installer.install 을 모델 id 당 1개 작업으로 실행한다. 진행률은 0.5% 이상 변할 때만 행에 반영한다.
     * 입력: id(모델 id)
     * 출력: 실패는 "다운로드 실패: …" 메시지, 취소는 취소 메시지
     * 리턴: 없음
     */
    fun download(id: String) = runJob(id, "다운로드", "다운로드와 설치를 마쳤습니다", InstallState.DOWNLOADING) { spec ->
        var lastShown = -1f // 마지막으로 화면에 반영한 진행률
        installer.install(spec) { done, total -> // 진행 콜백(IO 스레드)
            val fraction = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f // 진행률
            progress[spec.id] = fraction // 기록
            if (fraction - lastShown >= PROGRESS_STEP || fraction >= 1f) { // 충분히 변했거나 끝
                lastShown = fraction // 갱신
                updateRow(spec) // change(add)-hyungchul-2026-10-11-03:24 상태·진행률을 다시 읽어 해당 행만 반영(설치기가 올린 DOWNLOADING/VERIFYING 변화도 따라감)
            }
        }
    }

    /**
     * 목적: 진행 중인 작업 취소
     * 기능: 그 모델의 작업(다운로드)을 취소한다. .part 는 이어받기를 위해 남는다. 진행 중인 작업이 없으면 아무 일도 없다.
     * 입력: id(모델 id)
     * 출력: 취소 메시지(작업 쪽에서 발행)
     * 리턴: 없음
     */
    fun cancel(id: String) {
        jobs[id]?.cancel() // 작업 취소
    }

    /**
     * 목적: 가져오기 안내 열기
     * 기능: import/<id> 폴더를 만들어 두고(adb push 가 바로 되도록) 그 경로를 안내 상태로 올린다.
     * 입력: id(모델 id)
     * 출력: importPrompt 갱신, 폴더를 만들지 못하면 오류 메시지
     * 리턴: 없음
     */
    fun requestImport(id: String) {
        val spec = specs[id] ?: return post("가져오기 실패: 카탈로그에 없는 모델입니다($id)") // 모르는 모델
        val folder = importFolderOf(spec) // 대상 폴더
        if (!folder.isDirectory && !folder.mkdirs()) { // 폴더 생성 실패
            return post("가져오기 실패: 폴더를 만들 수 없습니다(${folder.path})") // 알림
        }
        _importPrompt.value = ImportPrompt(spec.id, spec.displayName, folder.path) // 안내 표시
    }

    /**
     * 목적: 가져오기 안내 닫기
     * 기능: importPrompt 를 null 로 둔다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun dismissImportPrompt() {
        _importPrompt.value = null // 닫기
    }

    /**
     * 목적: 안내한 폴더에서 가져오기 실행
     * 기능: 안내를 닫고 import/<id> 안의 내용(resolveImportSource 규칙)으로 importFrom 을 실행한다. 원본 파일은 지우지 않는다.
     * 입력: id(모델 id)
     * 출력: 실패는 "가져오기 실패: …" 메시지
     * 리턴: 없음
     */
    fun confirmImport(id: String) {
        dismissImportPrompt() // 대화상자 닫기
        runJob(id, "가져오기", "가져오기와 검증을 마쳤습니다", InstallState.VERIFYING) { spec ->
            installer.importFrom(spec, resolveImportSource(spec)) // 원본 해석 후 가져오기
        }
    }

    /**
     * 목적: 임의 원본(폴더 또는 압축 파일)에서 가져오기 — SAF 확장 지점
     * 기능: installer.importFrom 을 모델당 1개 작업으로 실행한다. Task 30 의 SAF 선택은 Uri 를 임시 파일/폴더로 복사한 뒤 이 함수를 부르면 된다.
     * 입력: id(모델 id), source(폴더 또는 .tar.bz2/.zip 파일)
     * 출력: 실패는 "가져오기 실패: …" 메시지
     * 리턴: 없음
     */
    fun importFrom(id: String, source: File) = runJob(id, "가져오기", "가져오기와 검증을 마쳤습니다", InstallState.VERIFYING) { spec ->
        installer.importFrom(spec, source) // 설치기에 위임
    }

    /**
     * 목적: import/<id> 안에서 설치기에 넘길 원본 결정
     * 기능: 폴더가 없거나 비면 한국어 오류. 항목이 압축 파일(.tar.bz2/.tbz2/.tbz/.zip) 1개면 그 파일, 폴더 1개뿐이고 선언 파일이 바로 그 안에 있으면 그 폴더(adb push 로 폴더째 넣은 경우), 그 밖이면 import/<id> 자체.
     * 입력: spec(대상 모델)
     * 출력: 폴더 없음/비어 있음이면 FileNotFoundException(경로 포함 한국어 메시지)
     * 리턴: 원본 File
     */
    fun resolveImportSource(spec: ModelSpec): File {
        val folder = importFolderOf(spec) // import/<id>
        if (!folder.isDirectory) throw FileNotFoundException("가져올 폴더가 없습니다. 파일을 이 폴더에 넣어 주세요: ${folder.path}") // 폴더 없음
        val entries = folder.listFiles()?.sortedBy { it.name } ?: emptyList() // 항목 목록
        if (entries.isEmpty()) throw FileNotFoundException("가져올 폴더가 비어 있습니다. 파일을 이 폴더에 넣어 주세요: ${folder.path}") // 비어 있음
        val only = entries.singleOrNull() // 항목이 하나뿐이면
        if (only != null && only.isFile && isArchiveName(only.name)) return only // 압축 파일 1개
        val probe = spec.files.firstOrNull()?.path // 첫 선언 파일의 상대 경로
        if (only != null && only.isDirectory && probe != null && !File(folder, probe).exists() && File(only, probe).exists()) return only // 폴더째 넣은 경우
        return folder // 그 밖에는 폴더 자체
    }

    /**
     * 목적: 설치기가 푸는 압축 파일 이름인지 확인
     * 기능: .tar.bz2/.tbz2/.tbz/.zip(대소문자 무시) 여부. ModelInstaller 의 지원 형식과 같다.
     * 입력: name(파일 이름)
     * 출력: 없음
     * 리턴: 압축 파일이면 true
     */
    private fun isArchiveName(name: String): Boolean {
        val n = name.lowercase() // 소문자 이름
        return n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") || n.endsWith(".zip") // 지원 확장자
    }

    /**
     * 목적: 설치본 검증
     * 기능: installer.verify(전수 SHA-256)를 작업으로 실행하고 결과를 verifyReport 로 올린다(문제 없으면 빈 목록).
     * 입력: id(모델 id)
     * 출력: verifyReport 갱신, 실패는 "검증 실패: …" 메시지
     * 리턴: 없음
     */
    fun verify(id: String) = runJob(id, "검증", null, InstallState.VERIFYING) { spec ->
        val problems = installer.verify(spec) // 전수 검사
        _verifyReport.value = VerifyReport(spec.displayName, problems) // 결과 표시
    }

    /**
     * 목적: 검증 결과 닫기
     * 기능: verifyReport 를 null 로 둔다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun dismissVerifyReport() {
        _verifyReport.value = null // 닫기
    }

    /**
     * 목적: 모델 삭제
     * 기능: 설치·가져오기·검증이 진행 중이거나 실행 중(사용 중)인 모델은 거부하고 한국어 오류를 알린다. 아니면 models/<id> 를 지운다.
     * 입력: id(모델 id)
     * 출력: 거부·실패는 "삭제 실패: …" 메시지
     * 리턴: 없음
     */
    fun delete(id: String) = runJob(id, "삭제", "삭제했습니다", null) { spec ->
        val state = store.state(spec) // 현재 상태
        check(state != InstallState.DOWNLOADING && state != InstallState.VERIFYING) { "설치 또는 검증이 진행 중인 모델은 삭제할 수 없습니다" } // 진행 중 거부
        check(store.useCountOf(spec) == 0) { "사용 중인 모델은 삭제할 수 없습니다(실행 중인 작업이 끝난 뒤 다시 시도하세요)" } // 사용 중 거부
        if (!store.delete(spec)) { // change(add)-hyungchul-2026-10-11-03:24 false 의 사유를 가려서 알림
            check(store.useCountOf(spec) == 0) { "사용 중인 모델은 삭제할 수 없습니다(그 사이 실행이 시작됨)" } // 확인 직후 사용이 시작된 경우
            throw IllegalStateException("폴더를 지우지 못했습니다(${store.modelDir(spec).path})") // 그 밖의 삭제 실패
        }
    }

    /**
     * 목적: 모델별 단일 작업 실행 틀
     * 기능: 같은 모델의 작업이 이미 돌면 거부 메시지. 아니면 viewModelScope 에서 io 로 block 을 실행하고,
     *       성공 시 successText(있으면)를 알리고, 취소는 취소 메시지 후 재전파, 그 밖의 Exception 은 "<label> 실패: 메시지"로 알린다.
     *       끝나면(성공·실패·취소) 진행률을 지우고 상태를 다시 읽는다.
     * 입력: id(모델 id), label(작업 이름), successText(성공 메시지 또는 null), busyState(작업 시작 직후 행에 보일 상태 또는 null), block(작업 본문)
     * 출력: 메시지 발행
     * 리턴: 없음
     */
    private fun runJob(id: String, label: String, successText: String?, busyState: InstallState?, block: suspend (ModelSpec) -> Unit) {
        val spec = specs[id] ?: return post("$label 실패: 카탈로그에 없는 모델입니다($id)") // 모르는 모델
        val running = jobs[id] // 이 모델의 기존 작업
        if (running != null && !running.isCompleted) { // change(add)-hyungchul-2026-10-11-03:24 isActive 가 아니라 isCompleted 로 판정 — 취소 중(정리 전)에도 새 작업을 막아 설치기의 중복 진행 오류를 피함
            return post(if (running.isCancelled) "${spec.displayName}: 이전 작업을 정리하는 중입니다. 잠시 뒤 다시 눌러 주세요" else "${spec.displayName}: 이미 다른 작업이 진행 중입니다") // 사유별 안내
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { // 시작 전에 등록하려고 지연 시작
            try {
                withContext(io) {
                    if (busyState != null) busy[id] = busyState // change(add)-hyungchul-2026-10-11-03:24 시작 즉시 행에 반영할 상태
                    updateRow(spec) // change(add)-hyungchul-2026-10-11-03:24 행 갱신(취소 버튼·진행 바가 바로 나타남)
                    block(spec) // 작업 실행
                }
                if (successText != null) post("${spec.displayName}: $successText") // 성공 알림
            } catch (e: CancellationException) {
                post("${spec.displayName}: $label 작업을 취소했습니다") // 취소 알림
                throw e // 취소는 삼키지 않음
            } catch (e: Exception) {
                post("${spec.displayName}: $label 실패 — ${e.message ?: e::class.java.simpleName}") // 실패 알림(예외의 한국어 메시지)
            } finally {
                progress.remove(id) // 진행률 제거
                busy.remove(id) // 작업 중 표시 제거
                refresh() // 상태 다시 읽기(취소된 코루틴이 아닌 새 코루틴에서)
            }
        }
        jobs[id] = job // 등록
        job.invokeOnCompletion { jobs.remove(id, job) } // 끝나면 제거
        job.start() // 시작
    }

    /**
     * 목적: import 폴더 위치
     * 기능: importRoot/<모델id>
     * 입력: spec
     * 출력: 없음
     * 리턴: 폴더(생성하지 않음)
     */
    private fun importFolderOf(spec: ModelSpec): File = File(importRoot, spec.id) // import/<id>

    /**
     * 목적: 메시지 발행
     * 기능: 버퍼 채널에 넣는다(가득 차는 일은 없음 — 버퍼링 채널).
     * 입력: text(한국어 메시지)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun post(text: String) {
        messageChannel.trySend(text) // 비차단 전송
    }

    companion object {
        const val VAD_GROUP = "VAD 모델" // VAD 모델 그룹 이름
        private const val PROGRESS_STEP = 0.005f // 진행률 반영 최소 변화(0.5%)
        private const val MB = 1024L * 1024L // 1MB 바이트

        /**
         * 목적: 바이트 → MB(올림)
         * 기능: 0 바이트는 0, 그 밖은 올림(작은 파일이 0MB 로 보이지 않게).
         * 입력: bytes
         * 출력: 없음
         * 리턴: MB
         */
        fun megabytesCeil(bytes: Long): Long = (bytes + MB - 1) / MB // 올림 나눗셈

        /**
         * 목적: 언어 코드 → 한국어 라벨
         * 기능: ko=한국어, en=영어(코드 순서 ko, en 먼저, 나머지는 코드 그대로), 비어 있으면 "언어 무관"(VAD).
         * 입력: codes(언어 코드 집합)
         * 출력: 없음
         * 리턴: ", " 로 이은 문자열
         */
        fun languageLabel(codes: Set<String>): String {
            if (codes.isEmpty()) return "언어 무관" // VAD
            val order = listOf("ko", "en") // 표시 순서
            val sorted = codes.sortedWith(compareBy({ order.indexOf(it).let { i -> if (i < 0) order.size else i } }, { it })) // 정렬
            return sorted.joinToString(", ") { when (it) { "ko" -> "한국어"; "en" -> "영어"; else -> it } } // 라벨
        }

        /**
         * 목적: 앱 전역 객체로 ViewModel 팩토리 만들기
         * 기능: App 의 modelStore/modelInstaller/dirs 싱글턴을 그대로 넘긴다(새 인스턴스를 만들면 사용 횟수 보호가 깨진다).
         * 입력: app(App 인스턴스)
         * 출력: 없음
         * 리턴: ViewModelProvider.Factory
         */
        fun factory(app: App): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // 요청 클래스는 ModelsViewModel 뿐
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ModelsViewModel::class.java)) { "지원하지 않는 ViewModel: ${modelClass.name}" } // 다른 타입 거부
                return ModelsViewModel(app.modelStore, app.modelInstaller, ModelCatalog.all, app.dirs.importDir) as T // 싱글턴 주입
            }
        }
    }
}
