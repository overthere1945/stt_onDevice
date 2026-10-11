// 파일명: ui/models/ModelsViewModel.kt
// 목적: 모델 관리 화면의 상태(ModelRow 목록)와 동작(다운로드·취소·가져오기·검증·삭제)을 담당하는 ViewModel
// 기능: 카탈로그 각 모델의 설치 상태를 ModelStore 에서 읽어 rows 로 노출하고, 오래 걸리는 작업은 viewModelScope + IO 디스패처에서 모델 id 당 1개만 실행한다.
//       작업이 끝나면(성공·실패·취소) 상태를 다시 읽고, 실패는 한국어 메시지(messages)로 알린다. 가져오기는 adb push 폴더(files/import/<모델id>/)를 쓴다.
//       SAF(Uri) 가져오기는 Task 30 에서 이 클래스의 importFrom(id, source: File) 앞단에 Uri → 임시 파일/폴더 복사를 붙여 추가한다(확장 지점).
//       change(add)-hyungchul-2026-10-11-03:59 importFromSaf(id, copy): import/<id>/saf_<시각>/ 임시 폴더로 복사(화면이 SafBridge.importModel 로 만든 함수) → resolveImportSource 와 같은 규칙으로 원본 결정
//       → installer.importFrom → 임시 폴더 삭제(성공·실패·취소 모두, 사용자의 원본은 SAF 쪽에 그대로 있음).
//       change(add)-hyungchul-2026-10-11-18:21 catalogForScreen(list): 시스템 엔진 항목을 뺀 목록 — 팩토리가 모델 화면 목록에 쓴다(2차 작업 2).
//       change(add)-hyungchul-2026-10-11-19:52 (2차 작업 7) 시스템 엔진 영역: systemRows(엔진 2개 × 언어 ko·en 상태, busyLang), refreshSystem()(상태 다시 조회), downloadSystem(kind, lang)(Google 공식 다운로드 요청 —
//       동의 창은 사용자가 직접 누름, 끝나면 그 언어 상태 재조회), canDownloadSystem(kind). 생성자 끝의 systemStatus 가 null 이면 영역이 숨겨진다(systemRows 가 빈 목록).
package com.cowork.stt_ondevice.ui.models // 모델 화면 패키지

import androidx.lifecycle.ViewModel // 뷰모델 기본 클래스
import androidx.lifecycle.ViewModelProvider // 뷰모델 팩토리 인터페이스
import androidx.lifecycle.viewModelScope // 뷰모델 코루틴 범위
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.core.storage.SafCopyResult // change(add)-hyungchul-2026-10-11-03:59 SAF 복사 결과
import com.cowork.stt_ondevice.engine.system.AndroidSystemEngineStatus // change(add)-hyungchul-2026-10-11-19:52 Android 상태 조회 구현
import com.cowork.stt_ondevice.engine.system.DownloadEvent // change(add)-hyungchul-2026-10-11-19:52 다운로드 이벤트
import com.cowork.stt_ondevice.engine.system.LanguageStatus // change(add)-hyungchul-2026-10-11-19:52 언어 상태
import com.cowork.stt_ondevice.engine.system.SystemEngineStatus // change(add)-hyungchul-2026-10-11-19:52 상태 조회 계약
import com.cowork.stt_ondevice.engine.system.safeStatus // change(add)-hyungchul-2026-10-11-19:52 예외를 사유로 바꾸는 조회
import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 상태
import com.cowork.stt_ondevice.model.InstallState // 설치 상태
import com.cowork.stt_ondevice.model.ModelCatalog // 카탈로그
import com.cowork.stt_ondevice.model.ModelInstaller // 설치기
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 저장소
import com.cowork.stt_ondevice.model.SystemEngineKind // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 종류
import com.cowork.stt_ondevice.model.isSystem // 시스템 엔진 여부 // change(add)-hyungchul-2026-10-11-18:21
import kotlinx.coroutines.CancellationException // 취소 예외
import kotlinx.coroutines.CoroutineDispatcher // 디스패처 타입
import kotlinx.coroutines.CoroutineStart // 코루틴 시작 방식
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.Job // 작업 핸들
import kotlinx.coroutines.async // change(add)-hyungchul-2026-10-11-19:52 상태 병렬 조회
import kotlinx.coroutines.awaitAll // change(add)-hyungchul-2026-10-11-19:52 병렬 조회 대기
import kotlinx.coroutines.coroutineScope // change(add)-hyungchul-2026-10-11-19:52 병렬 조회 범위
import kotlinx.coroutines.channels.Channel // 일회성 메시지 채널
import kotlinx.coroutines.flow.Flow // 흐름
import kotlinx.coroutines.flow.MutableStateFlow // 변경 가능한 상태 흐름
import kotlinx.coroutines.flow.StateFlow // 읽기 전용 상태 흐름
import kotlinx.coroutines.flow.asStateFlow // 읽기 전용 변환
import kotlinx.coroutines.flow.receiveAsFlow // 채널 → 흐름
import kotlinx.coroutines.flow.transformWhile // change(add)-hyungchul-2026-10-11-19:52 끝 이벤트까지만 수집
import kotlinx.coroutines.flow.update // 원자적 갱신
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.io.FileNotFoundException // 파일 없음
import java.time.LocalDateTime // change(add)-hyungchul-2026-10-11-03:59 임시 폴더 이름 시각
import java.time.format.DateTimeFormatter // change(add)-hyungchul-2026-10-11-03:59 시각 서식
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

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 모델 화면 "시스템 엔진" 카드 1개의 값
 * 기능: 엔진 종류·제목·라이선스 문구·언어별 상태(ko, en 순서)·다운로드 중인 언어를 담는다. langs 가 비어 있으면 아직 조회 전이다.
 * 입력: kind(엔진 종류), title(카드 제목), license(라이선스 문구), langs(앱 언어 코드 → 상태 목록), busyLang(다운로드 중인 앱 언어 코드 또는 null)
 */
data class SystemEngineRow(
    val kind: SystemEngineKind, // 엔진 종류
    val title: String, // 카드 제목
    val license: String, // 라이선스 문구
    val langs: List<Pair<String, LanguageStatus>>, // 언어별 상태
    val busyLang: String?, // 다운로드 중인 언어
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

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 언어 상태의 한국어 라벨
 * 기능: Available=사용 가능, Downloadable=다운로드 필요, Downloading=다운로드 중, Unavailable=사용 불가(사유)
 * 입력: 없음(수신 객체)
 * 출력: 없음
 * 리턴: 한국어 문자열
 */
fun LanguageStatus.koreanLabel(): String = when (this) {
    LanguageStatus.Available -> "사용 가능" // 바로 사용
    LanguageStatus.Downloadable -> "다운로드 필요" // 언어 팩 필요
    LanguageStatus.Downloading -> "다운로드 중" // 받는 중
    is LanguageStatus.Unavailable -> "사용 불가($reason)" // 사유
}

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 모델 관리 화면 ViewModel
 * 기능: 위 파일 머리말 참조. 앱 전역 store/installer 와 같은 인스턴스를 받아야 사용 횟수·동시 설치 보호가 유지된다.
 * 입력: store(모델 저장소), installer(설치기), catalog(표시할 모델 목록, 기본은 전체 카탈로그), importRoot(adb push 가져오기 루트: files/import),
 *       io(오래 걸리는 작업용 디스패처 — 테스트에서 교체),
 *       systemStatus(change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 상태 조회기 — null 이면 시스템 엔진 영역 숨김, 기본 null)
 */
class ModelsViewModel(
    private val store: ModelStore, // 모델 저장소
    private val installer: ModelInstaller, // 설치기
    private val catalog: List<ModelSpec> = ModelCatalog.all, // 표시 목록
    private val importRoot: File, // 가져오기 루트
    private val io: CoroutineDispatcher = Dispatchers.IO, // 작업 디스패처
    private val systemStatus: SystemEngineStatus? = null, // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 상태 조회기(null 이면 영역 숨김)
) : ViewModel() {

    private val specs: Map<String, ModelSpec> = catalog.associateBy { it.id } // id → spec
    private val _rows = MutableStateFlow<List<ModelRow>>(emptyList()) // 행 목록(내부)
    private val progress = ConcurrentHashMap<String, Float>() // 받는 중인 모델의 진행률
    private val jobs = ConcurrentHashMap<String, Job>() // 모델 id → 진행 중인 작업(모델당 1개)
    private val busy = ConcurrentHashMap<String, InstallState>() // change(add)-hyungchul-2026-10-11-03:24 작업 시작 직후부터 행에 보일 상태(DOWNLOADING/VERIFYING) — 설치기가 상태를 올리기 전 구간 보완
    private val messageChannel = Channel<String>(Channel.BUFFERED) // 일회성 메시지(스낵바)
    private val _importPrompt = MutableStateFlow<ImportPrompt?>(null) // 가져오기 안내
    private val _verifyReport = MutableStateFlow<VerifyReport?>(null) // 검증 결과
    private val _systemRows = MutableStateFlow(initialSystemRows(systemStatus)) // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 카드(내부)
    private val systemJobs = ConcurrentHashMap<SystemEngineKind, Job>() // change(add)-hyungchul-2026-10-11-19:52 엔진별 진행 중인 다운로드(엔진당 1개)
    @Volatile private var systemRefreshJob: Job? = null // change(add)-hyungchul-2026-10-11-19:52 진행 중인 전체 상태 조회

    /** 목적: 화면에 그릴 행 목록 / 기능: 카탈로그 순서(ASR → VAD), 상태가 바뀔 때마다 갱신 */
    val rows: StateFlow<List<ModelRow>> = _rows.asStateFlow() // 읽기 전용 노출

    /** 목적: 일회성 사용자 메시지 / 기능: 한국어 오류·완료 메시지를 순서대로 흘린다(스낵바 표시용) */
    val messages: Flow<String> = messageChannel.receiveAsFlow() // 채널 → 흐름

    /** 목적: 가져오기 안내 상태 / 기능: null 이면 대화상자 없음 */
    val importPrompt: StateFlow<ImportPrompt?> = _importPrompt.asStateFlow() // 읽기 전용 노출

    /** 목적: 검증 결과 상태 / 기능: null 이면 대화상자 없음 */
    val verifyReport: StateFlow<VerifyReport?> = _verifyReport.asStateFlow() // 읽기 전용 노출

    /** 목적: 시스템 엔진 카드 목록 / 기능: 엔진 2개 × 언어 ko·en 의 상태. systemStatus 가 null 이면 빈 목록(영역 숨김), 조회 전에는 langs 가 빈 카드 */
    val systemRows: StateFlow<List<SystemEngineRow>> = _systemRows.asStateFlow() // change(add)-hyungchul-2026-10-11-19:52 읽기 전용 노출

    init {
        refresh() // 첫 상태 읽기
        refreshSystem() // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 상태 첫 조회
    }

    // change(add)-hyungchul-2026-10-11-19:52
    /**
     * 목적: 시스템 엔진 상태 전체 다시 조회
     * 기능: 엔진 × 언어(ko, en) 상태를 병렬로 조회해(각 15 초 제한, 예외는 사유로) 카드에 반영한다. 이미 조회 중이면 그것을 취소하고 새로 시작한다. 다운로드 중 표시(busyLang)는 건드리지 않는다.
     *       systemStatus 가 null 이면 아무 일도 없다.
     * 입력: 없음
     * 출력: systemRows 갱신
     * 리턴: 없음
     */
    fun refreshSystem() {
        val status = systemStatus ?: return // 조회기 없음 → 영역 숨김
        systemRefreshJob?.cancel() // 이전 조회 취소
        systemRefreshJob = viewModelScope.launch(io) { // 백그라운드에서
            val results = coroutineScope { // 병렬 조회
                SYSTEM_KINDS.flatMap { kind -> SYSTEM_LANGS.map { lang -> async { Triple(kind, lang, status.safeStatus(kind, lang)) } } }.awaitAll() // 4개 동시
            }
            _systemRows.update { rows -> rows.map { row -> row.copy(langs = SYSTEM_LANGS.map { lang -> lang to results.first { it.first == row.kind && it.second == lang }.third }) } } // 상태 반영(busyLang 유지)
        }
    }

    // change(add)-hyungchul-2026-10-11-19:52
    /**
     * 목적: 시스템 엔진 언어 팩 다운로드 요청
     * 기능: 엔진당 1개 작업으로 systemStatus.download 를 수집한다. 시작하면 busyLang 을 그 언어로 두고 안내 메시지
     *       "Google 동의 창이 뜨면 직접 확인을 눌러 주세요(앱이 대신 누르지 않습니다)." 를 1번 낸다. 동의 창은 Google 서비스가 띄우며 사용자가 직접 누른다.
     *       Failed 이벤트·예외는 "<제목> <언어> 다운로드 실패 — 원인" 메시지. 끝나면(Done·Failed·예외) 그 언어 상태를 다시 조회하고 busyLang 을 푼다.
     *       다운로드를 요청할 수 없는 엔진(플랫폼 API 33)이거나 이미 다운로드 중이면 안내만 내고 시작하지 않는다.
     * 입력: kind(엔진 종류), engineLang(앱 언어 "ko"/"en")
     * 출력: 메시지 발행, systemRows 갱신
     * 리턴: 없음
     */
    fun downloadSystem(kind: SystemEngineKind, engineLang: String) {
        val status = systemStatus ?: return // 조회기 없음
        val title = systemTitle(kind) // 카드 제목
        if (!status.canDownload(kind)) return post("$title: 이 기기(Android 버전)에서는 앱에서 다운로드를 요청할 수 없습니다. Android 14 이상이 필요하며, 시스템 설정의 음성 인식 설정에서 받을 수 있습니다") // 불가
        val running = systemJobs[kind] // 이 엔진의 기존 다운로드
        if (running != null && !running.isCompleted) return post("$title: 이미 다운로드가 진행 중입니다") // 엔진당 1개
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { // 시작 전에 등록하려고 지연 시작
            setSystemBusy(kind, engineLang) // 진행 표시
            post("Google 동의 창이 뜨면 직접 확인을 눌러 주세요(앱이 대신 누르지 않습니다).") // 안내(1회)
            try {
                var failure: String? = null // 실패 원인
                try {
                    withContext(io) { status.download(kind, engineLang).transformWhile { ev -> emit(ev); ev is DownloadEvent.Progress }.collect { ev -> if (ev is DownloadEvent.Failed) failure = ev.message } } // Done/Failed 를 받으면 흐름이 안 끝나도 수집 종료(진행은 "다운로드 중" 표시로 충분)
                } catch (e: CancellationException) {
                    throw e // 취소는 그대로
                } catch (e: VirtualMachineError) {
                    throw e // OOM 등은 그대로
                } catch (e: Throwable) {
                    failure = "${e.javaClass.simpleName}: ${e.message}" // 예외를 실패 원인으로
                }
                if (failure != null) post("$title ${languageLabel(setOf(engineLang))} 다운로드 실패 — $failure") // 한국어 실패 메시지
                val fresh = withContext(io) { status.safeStatus(kind, engineLang) } // 그 언어 상태 재조회
                _systemRows.update { rows -> rows.map { row -> if (row.kind == kind) row.copy(langs = row.langs.map { (l, st) -> if (l == engineLang) l to fresh else l to st }, busyLang = null) else row } } // 상태 반영 + 진행 표시 해제
            } finally {
                setSystemBusy(kind, null) // 취소돼도 진행 표시 해제
            }
        }
        systemJobs[kind] = job // 등록
        job.invokeOnCompletion { systemJobs.remove(kind, job) } // 끝나면 제거
        job.start() // 시작
    }

    // change(add)-hyungchul-2026-10-11-19:52
    /**
     * 목적: 앱에서 다운로드를 요청할 수 있는지
     * 기능: systemStatus.canDownload(kind) (조회기가 없으면 false). 화면이 [다운로드] 버튼 표시에 쓴다.
     * 입력: kind(엔진 종류)
     * 출력: 없음
     * 리턴: 가능하면 true
     */
    fun canDownloadSystem(kind: SystemEngineKind): Boolean = systemStatus?.canDownload(kind) == true // 가능 여부

    // change(add)-hyungchul-2026-10-11-19:52
    /**
     * 목적: 다운로드 중 표시 바꾸기
     * 기능: 그 엔진 카드의 busyLang 만 바꾼다(다른 값은 그대로).
     * 입력: kind(엔진 종류), lang(다운로드 중인 언어 또는 null)
     * 출력: systemRows 갱신
     * 리턴: 없음
     */
    private fun setSystemBusy(kind: SystemEngineKind, lang: String?) {
        _systemRows.update { rows -> rows.map { if (it.kind == kind) it.copy(busyLang = lang) else it } } // 해당 카드만
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

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: SAF 로 고른 파일·폴더에서 가져오기
     * 기능: 안내 대화상자를 닫고 모델당 1개 작업으로: import/<id>/saf_<yyyyMMdd_HHmmss>/ 를 만들어 copy 에 넘기고(SAF → 앱 폴더 복사),
     *       그 안에서 resolveImportSource 규칙(압축 1개 / 선언 파일이 든 하위 폴더 1개 / 폴더 자체)으로 원본을 정해 installer.importFrom 을 부른다.
     *       끝나면(성공·실패·취소) 임시 폴더를 지운다(앱이 만든 복사본일 뿐, 사용자의 원본은 그대로). 복사한 파일이 없으면 한국어 오류.
     * 입력: id(모델 id), copy(임시 폴더를 받아 SAF 내용을 복사하는 함수)
     * 출력: 실패는 "가져오기 실패 — …" 메시지
     * 리턴: 없음
     */
    fun importFromSaf(id: String, copy: suspend (File) -> SafCopyResult) {
        dismissImportPrompt() // 대화상자 닫기
        runJob(id, "가져오기", "가져오기와 검증을 마쳤습니다", InstallState.VERIFYING) { spec ->
            val stage = File(importFolderOf(spec), "saf_" + LocalDateTime.now().format(STAGE_FORMAT)) // 임시 폴더
            try {
                if (!stage.isDirectory && !stage.mkdirs()) throw IllegalStateException("임시 폴더를 만들 수 없습니다(${stage.path})") // 폴더 생성
                post("${spec.displayName}: 선택한 위치에서 앱 폴더로 복사하는 중입니다(큰 모델은 시간이 걸립니다)") // 진행 안내
                val r = copy(stage) // SAF → 임시 폴더
                if (r.copied == 0) throw FileNotFoundException("복사된 파일이 없습니다(선택한 위치가 비었거나 같은 이름 파일만 있음)") // 빈 선택
                installer.importFrom(spec, resolveImportSource(spec, stage)) // 원본 해석 후 설치
            } finally {
                stage.deleteRecursively() // 임시 복사본 정리
            }
        }
    }

    /**
     * 목적: import/<id> 안에서 설치기에 넘길 원본 결정
     * 기능: 폴더가 없거나 비면 한국어 오류. 항목이 압축 파일(.tar.bz2/.tbz2/.tbz/.zip) 1개면 그 파일, 폴더 1개뿐이고 선언 파일이 바로 그 안에 있으면 그 폴더(adb push 로 폴더째 넣은 경우), 그 밖이면 import/<id> 자체.
     * 입력: spec(대상 모델), folder(change(add)-hyungchul-2026-10-11-03:59 해석할 폴더 — 기본 import/<id>, SAF 는 그 아래 임시 폴더)
     * 출력: 폴더 없음/비어 있음이면 FileNotFoundException(경로 포함 한국어 메시지)
     * 리턴: 원본 File
     */
    fun resolveImportSource(spec: ModelSpec, folder: File = importFolderOf(spec)): File { // change(add)-hyungchul-2026-10-11-03:59 폴더 인자 추가
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
        private val SYSTEM_KINDS: List<SystemEngineKind> = SystemEngineKind.entries // change(add)-hyungchul-2026-10-11-19:52 카드 순서(ML Kit → 플랫폼)
        private val SYSTEM_LANGS: List<String> = listOf("ko", "en") // change(add)-hyungchul-2026-10-11-19:52 언어 순서
        const val SYSTEM_LICENSE = "Google 서비스 약관 적용" // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 라이선스 문구
        private const val PROGRESS_STEP = 0.005f // 진행률 반영 최소 변화(0.5%)
        private const val MB = 1024L * 1024L // 1MB 바이트
        private val STAGE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss") // change(add)-hyungchul-2026-10-11-03:59 SAF 임시 폴더 이름 서식

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

        // change(add)-hyungchul-2026-10-11-19:52
        /**
         * 목적: 시스템 엔진 카드 제목
         * 기능: ML Kit → "ML Kit GenAI Basic (Speech Services by Google)", 플랫폼 → "Android 온디바이스 인식기 (Android System Intelligence)".
         * 입력: kind(엔진 종류)
         * 출력: 없음
         * 리턴: 제목 문자열
         */
        fun systemTitle(kind: SystemEngineKind): String = when (kind) {
            SystemEngineKind.MLKIT_BASIC -> "ML Kit GenAI Basic (${kind.serviceLabel})" // ML Kit
            SystemEngineKind.PLATFORM_ONDEVICE -> "Android 온디바이스 인식기 (${kind.serviceLabel})" // 플랫폼
        }

        // change(add)-hyungchul-2026-10-11-19:52
        /**
         * 목적: 시스템 엔진 카드 초기값
         * 기능: 조회기가 있으면 엔진마다 langs 가 빈(조회 전) 카드, 없으면 빈 목록.
         * 입력: status(상태 조회기 또는 null)
         * 출력: 없음
         * 리턴: 카드 목록
         */
        private fun initialSystemRows(status: SystemEngineStatus?): List<SystemEngineRow> =
            if (status == null) emptyList() else SYSTEM_KINDS.map { SystemEngineRow(it, systemTitle(it), SYSTEM_LICENSE, emptyList(), null) } // 조회 전 카드

        // change(add)-hyungchul-2026-10-11-18:21
        /**
         * 목적: 모델 화면에 보일 카탈로그 고르기
         * 기능: 시스템 엔진 항목(파일이 없어 받기·삭제 대상이 아님)을 뺀다. 원래 순서는 유지한다.
         * 입력: list(카탈로그 전체)
         * 출력: 없음
         * 리턴: 시스템 항목을 뺀 목록
         */
        fun catalogForScreen(list: List<ModelSpec>): List<ModelSpec> = list.filterNot { it.isSystem } // 시스템 항목 제외

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
                return ModelsViewModel(app.modelStore, app.modelInstaller, catalogForScreen(ModelCatalog.all), app.dirs.importDir, systemStatus = AndroidSystemEngineStatus(app)) as T // 싱글턴 주입 // change(add)-hyungchul-2026-10-11-18:21 시스템 항목 제외 // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 상태 조회기
            }
        }
    }
}
