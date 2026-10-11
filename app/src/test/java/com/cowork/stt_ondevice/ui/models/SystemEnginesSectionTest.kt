// 파일명: SystemEnginesSectionTest.kt
// 목적: 모델 화면의 "시스템 엔진" 영역(ModelsViewModel.systemRows·downloadSystem)과 상태 확인 제한 시간 래퍼(withStatusTimeout)를 검증 (2차 작업 7)
// 기능: 가짜 SystemEngineStatus 로 엔진별·언어별 상태 행 구성, 다운로드 진행 중 busyLang·완료/실패 뒤 상태 재조회·안내 메시지 1회, 15 초 제한 시간 처리를 확인한다.
//       change(add)-hyungchul-2026-10-11-21:50 (최종 리뷰 수정) 시간 초과는 CheckFailed(일시적 확인 실패)로, 취소 안내 문구는 "앱의 다운로드 추적을 끝냈습니다(…)" 로 확인한다.
//       change(add)-hyungchul-2026-10-11-20:05 (수정 라운드 1) 취소 버튼 동작(끝나지 않는 다운로드 취소 → busyLang 해제·재조회·취소 안내)과 느린 새로고침 + 빠른 다운로드 완료 경합(최종 Available)을 더했다.
// change(add)-hyungchul-2026-10-11-19:52
package com.cowork.stt_ondevice.ui.models // 모델 화면 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.engine.system.DownloadEvent // 다운로드 이벤트
import com.cowork.stt_ondevice.engine.system.LanguageStatus // 언어 상태
import com.cowork.stt_ondevice.engine.system.StatusReasons // 사유 상수
import com.cowork.stt_ondevice.engine.system.SystemEngineStatus // 상태 조회 계약
import com.cowork.stt_ondevice.engine.system.withStatusTimeout // 제한 시간 래퍼
import com.cowork.stt_ondevice.model.Downloader // 다운로더
import com.cowork.stt_ondevice.model.ModelInstaller // 설치기
import com.cowork.stt_ondevice.model.ModelStore // 저장소
import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.ExperimentalCoroutinesApi // 테스트 API 표시
import kotlinx.coroutines.CompletableDeferred // change(add)-hyungchul-2026-10-11-20:05 수동 해제 게이트
import kotlinx.coroutines.awaitCancellation // 끝나지 않는 대기
import kotlinx.coroutines.delay // change(add)-hyungchul-2026-10-11-20:05 실제 시간 대기
import kotlinx.coroutines.flow.Flow // 흐름
import kotlinx.coroutines.flow.MutableSharedFlow // 수동 방출 흐름
import kotlinx.coroutines.flow.first // 첫 값
import kotlinx.coroutines.flow.flowOf // 고정 흐름
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.runBlocking // 블로킹 실행
import kotlinx.coroutines.test.StandardTestDispatcher // 가상 시간 디스패처
import kotlinx.coroutines.test.UnconfinedTestDispatcher // 즉시 실행 테스트 디스패처
import kotlinx.coroutines.test.advanceTimeBy // 가상 시간 전진
import kotlinx.coroutines.test.resetMain // Main 복구
import kotlinx.coroutines.test.runCurrent // 현재 시각 작업 실행
import kotlinx.coroutines.test.runTest // 가상 시간 테스트
import kotlinx.coroutines.test.setMain // Main 교체
import kotlinx.coroutines.withTimeout // 제한 시간
import kotlinx.coroutines.withTimeoutOrNull // 제한 시간(null)
import org.junit.After // 각 테스트 후
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertNull // null
import org.junit.Assert.assertTrue // 참
import org.junit.Before // 각 테스트 전
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더

/**
 * 목적: 가짜 상태 조회기
 * 기능: 엔진·언어별 상태를 맵으로 돌려주고 조회 횟수를 센다. 다운로드 흐름은 테스트가 주입한다.
 * 입력: statuses(종류 → 언어 → 상태, 테스트가 중간에 바꿀 수 있음), downloadFlow(download 가 돌려줄 흐름), canDl(canDownload 값)
 */
class FakeSystemEngineStatus(
    val statuses: MutableMap<Pair<SystemEngineKind, String>, LanguageStatus>, // (종류, 언어) → 상태
    var downloadFlow: Flow<DownloadEvent> = flowOf(DownloadEvent.Done), // 다운로드 흐름
    private val canDl: Boolean = true, // 다운로드 가능 여부
    var blockKey: Pair<SystemEngineKind, String>? = null, // change(add)-hyungchul-2026-10-11-20:05 이 조합의 첫 조회만 게이트에서 대기(값은 대기 전에 읽음 → 늦게 도착하는 오래된 결과 흉내)
    var gate: CompletableDeferred<Unit>? = null, // change(add)-hyungchul-2026-10-11-20:05 blockKey 조회가 기다릴 게이트
) : SystemEngineStatus {
    val calls = mutableListOf<Pair<SystemEngineKind, String>>() // 조회 기록

    override suspend fun status(kind: SystemEngineKind, engineLang: String): LanguageStatus {
        synchronized(calls) { calls.add(kind to engineLang) } // 조회 기록
        val value = statuses.getValue(kind to engineLang) // 현재 상태(대기 전에 읽음)
        val g = gate // change(add)-hyungchul-2026-10-11-20:05
        if (g != null && blockKey == kind to engineLang) { blockKey = null; g.await() } // change(add)-hyungchul-2026-10-11-20:05 첫 1회만 대기
        return value // 결과
    }

    override fun canDownload(kind: SystemEngineKind): Boolean = canDl // 다운로드 가능 여부

    override fun download(kind: SystemEngineKind, engineLang: String): Flow<DownloadEvent> = downloadFlow // 주입된 흐름

    fun callCount(kind: SystemEngineKind, lang: String): Int = synchronized(calls) { calls.count { it == kind to lang } } // 특정 조합 조회 횟수
}

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 모델 화면 시스템 엔진 영역 회귀 방지
 * 기능: 위 파일 머리말 참조.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemEnginesSectionTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val dispatcher = UnconfinedTestDispatcher() // 즉시 실행 디스패처
    private lateinit var dirs: AppDirs // 앱 폴더
    private lateinit var store: ModelStore // 저장소
    private lateinit var installer: ModelInstaller // 설치기

    private val guide = "Google 동의 창이 뜨면 직접 확인을 눌러 주세요(앱이 대신 누르지 않습니다)." // 다운로드 시작 안내

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher) // viewModelScope 용 Main 교체
        dirs = AppDirs(tmp.newFolder("root")) // 루트 폴더
        store = ModelStore(dirs) // 저장소
        installer = ModelInstaller(dirs, store, Downloader()) // 설치기
    }

    @After
    fun tearDown() = Dispatchers.resetMain() // Main 복구

    /** 목적: 기본 상태 맵 / 기능: ML Kit ko=Available·en=Downloadable, 플랫폼 ko=Available·en=Unavailable / 리턴: 변경 가능한 맵 */
    private fun defaultStatuses(): MutableMap<Pair<SystemEngineKind, String>, LanguageStatus> = mutableMapOf(
        (SystemEngineKind.MLKIT_BASIC to "ko") to LanguageStatus.Available, // ML Kit 한국어
        (SystemEngineKind.MLKIT_BASIC to "en") to LanguageStatus.Downloadable, // ML Kit 영어
        (SystemEngineKind.PLATFORM_ONDEVICE to "ko") to LanguageStatus.Available, // 플랫폼 한국어
        (SystemEngineKind.PLATFORM_ONDEVICE to "en") to LanguageStatus.Unavailable("기기 인식기가 이 언어를 지원하지 않음"), // 플랫폼 영어
    )

    /** 목적: ViewModel 만들기 / 기능: 시스템 영역만 쓰는 ViewModel(카탈로그 비움) / 리턴: ModelsViewModel */
    private fun newVm(status: SystemEngineStatus?): ModelsViewModel =
        ModelsViewModel(store, installer, emptyList(), dirs.importDir, dispatcher, status) // 주입

    /**
     * 목적: 엔진별 행·언어 순서·상태 일치
     * 기능: systemRows 가 2행(ML Kit, 플랫폼)이고 각 행의 언어가 ko·en 순서이며 상태가 가짜 조회 결과와 같다. 라이선스 문구와 busyLang=null 도 확인한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rowsShowPerLanguageStatus() {
        val vm = newVm(FakeSystemEngineStatus(defaultStatuses())) // 생성 즉시 조회
        val rows = vm.systemRows.value // 행 목록
        assertEquals(2, rows.size) // 엔진 2개
        assertEquals(listOf(SystemEngineKind.MLKIT_BASIC, SystemEngineKind.PLATFORM_ONDEVICE), rows.map { it.kind }) // 종류 순서
        assertEquals(listOf("ko", "en"), rows[0].langs.map { it.first }) // ML Kit 언어 순서
        assertEquals(listOf("ko", "en"), rows[1].langs.map { it.first }) // 플랫폼 언어 순서
        assertEquals(listOf(LanguageStatus.Available, LanguageStatus.Downloadable), rows[0].langs.map { it.second }) // ML Kit 상태
        assertEquals(listOf(LanguageStatus.Available, LanguageStatus.Unavailable("기기 인식기가 이 언어를 지원하지 않음")), rows[1].langs.map { it.second }) // 플랫폼 상태
        assertTrue(rows.all { it.license == "Google 서비스 약관 적용" }) // 라이선스 문구
        assertTrue(rows.all { it.busyLang == null }) // 다운로드 중 아님
        assertTrue(rows.all { it.title.isNotBlank() }) // 제목 있음
    }

    /** 목적: 상태 조회기가 없으면 영역 숨김 / 기능: systemStatus=null 이면 systemRows 가 비어 있다 / 리턴: 없음 */
    @Test
    fun nullStatusHidesSection() {
        val vm = newVm(null) // 조회기 없음
        assertTrue(vm.systemRows.value.isEmpty()) // 영역 없음
        vm.refreshSystem() // 아무 일 없음
        vm.downloadSystem(SystemEngineKind.MLKIT_BASIC, "en") // 아무 일 없음
        assertTrue(vm.systemRows.value.isEmpty()) // 여전히 없음
    }

    /**
     * 목적: 다운로드 완료 뒤 상태 재조회
     * 기능: downloadSystem(MLKIT_BASIC, "en") 중에는 busyLang=="en", Done 뒤에는 상태를 다시 조회해 Available 이 되고 busyLang==null, 안내 메시지는 1번만 나온다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun downloadRefreshesStatusAfterDone() = runBlocking {
        val flow = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8) // 수동 방출
        val fake = FakeSystemEngineStatus(defaultStatuses(), downloadFlow = flow) // 가짜 조회기
        val vm = newVm(fake) // ViewModel
        val before = fake.callCount(SystemEngineKind.MLKIT_BASIC, "en") // 시작 전 조회 횟수
        vm.downloadSystem(SystemEngineKind.MLKIT_BASIC, "en") // 다운로드 시작
        assertEquals("en", vm.systemRows.value.first { it.kind == SystemEngineKind.MLKIT_BASIC }.busyLang) // 진행 중 표시
        flow.emit(DownloadEvent.Progress(10, 100)) // 진행
        assertEquals("en", vm.systemRows.value.first { it.kind == SystemEngineKind.MLKIT_BASIC }.busyLang) // 여전히 진행 중
        fake.statuses[SystemEngineKind.MLKIT_BASIC to "en"] = LanguageStatus.Available // 기기 쪽 상태가 바뀜
        flow.emit(DownloadEvent.Done) // 완료
        val row = withTimeout(2_000) { vm.systemRows.first { rs -> rs.first { it.kind == SystemEngineKind.MLKIT_BASIC }.busyLang == null } }.first { it.kind == SystemEngineKind.MLKIT_BASIC } // 수집 코루틴이 Done 을 처리해 진행 표시를 풀 때까지 대기 후 ML Kit 행
        assertEquals(LanguageStatus.Available, row.langs.first { it.first == "en" }.second) // 재조회로 사용 가능
        assertNull(row.busyLang) // 진행 표시 해제
        assertTrue(fake.callCount(SystemEngineKind.MLKIT_BASIC, "en") > before) // 다시 조회함
        assertEquals(guide, withTimeout(2_000) { vm.messages.first() }) // 안내 메시지
        assertNull(withTimeoutOrNull(300) { vm.messages.first() }) // 그 뒤 메시지 없음(1회)
    }

    /**
     * 목적: 다운로드 실패 메시지
     * 기능: Failed("x") 이면 안내 뒤에 "다운로드 실패" 와 원인 "x" 가 든 한국어 메시지가 나오고, 상태를 다시 조회하며 busyLang 이 풀린다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun downloadFailureShowsKoreanMessage() = runBlocking {
        val fake = FakeSystemEngineStatus(defaultStatuses(), downloadFlow = flowOf(DownloadEvent.Failed("x"))) // 즉시 실패
        val vm = newVm(fake) // ViewModel
        val before = fake.callCount(SystemEngineKind.MLKIT_BASIC, "en") // 시작 전 조회 횟수
        vm.downloadSystem(SystemEngineKind.MLKIT_BASIC, "en") // 다운로드 시작
        assertEquals(guide, withTimeout(2_000) { vm.messages.first() }) // 안내
        val failure = withTimeout(2_000) { vm.messages.first() } // 실패 메시지
        assertTrue(failure, failure.contains("다운로드 실패")) // 한국어 실패 문구
        assertTrue(failure, failure.contains("x")) // 원인 포함
        assertTrue(fake.callCount(SystemEngineKind.MLKIT_BASIC, "en") > before) // 상태 재조회
        assertNull(vm.systemRows.value.first { it.kind == SystemEngineKind.MLKIT_BASIC }.busyLang) // 진행 표시 해제
    }

    /** 목적: 다운로드 불가 종류 거부 / 기능: canDownload=false 이면 다운로드 흐름을 시작하지 않고 한국어 안내만 낸다 / 리턴: 없음 */
    @Test
    fun downloadRefusedWhenNotSupported() = runBlocking {
        val flow = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 1) // 수동 방출
        val vm = newVm(FakeSystemEngineStatus(defaultStatuses(), downloadFlow = flow, canDl = false)) // 다운로드 불가
        vm.downloadSystem(SystemEngineKind.PLATFORM_ONDEVICE, "en") // 시도
        val m = withTimeout(2_000) { vm.messages.first() } // 안내
        assertTrue(m, m.contains("Android 14")) // 사유(API 34)
        assertNull(vm.systemRows.value.first { it.kind == SystemEngineKind.PLATFORM_ONDEVICE }.busyLang) // 진행 표시 없음
    }

    /**
     * 목적: 끝나지 않는 다운로드 취소(change(add)-hyungchul-2026-10-11-20:05)
     * 기능: 동의 창을 닫아 터미널 이벤트가 없는 다운로드도 cancelSystemDownload 로 끝낼 수 있다 — busyLang 이 풀리고, 취소 안내가 나오고, 상태를 다시 조회하며, 다시 다운로드할 수 있다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun cancelEndsNeverFinishingDownload() = runBlocking {
        val flow = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 8) // 끝나지 않는 흐름
        val fake = FakeSystemEngineStatus(defaultStatuses(), downloadFlow = flow) // 가짜 조회기
        val vm = newVm(fake) // ViewModel
        val before = fake.callCount(SystemEngineKind.MLKIT_BASIC, "en") // 시작 전 조회 횟수
        vm.downloadSystem(SystemEngineKind.MLKIT_BASIC, "en") // 다운로드 시작(동의 창에서 멈춤)
        assertEquals("en", vm.systemRows.value.first { it.kind == SystemEngineKind.MLKIT_BASIC }.busyLang) // 진행 중
        assertEquals(guide, withTimeout(2_000) { vm.messages.first() }) // 안내
        vm.cancelSystemDownload(SystemEngineKind.MLKIT_BASIC) // 취소
        withTimeout(2_000) { vm.systemRows.first { rs -> rs.first { it.kind == SystemEngineKind.MLKIT_BASIC }.busyLang == null } } // 진행 표시 해제 대기
        assertEquals("앱의 다운로드 추적을 끝냈습니다(이미 시작된 서비스 다운로드는 계속될 수 있으니 [새로고침]으로 확인하세요)", withTimeout(2_000) { vm.messages.first() }.substringAfter(": ")) // 취소 안내 // change(add)-hyungchul-2026-10-11-21:50 문구 변경
        var waited = 0 // 대기 횟수
        while (fake.callCount(SystemEngineKind.MLKIT_BASIC, "en") <= before && waited++ < 40) delay(50) // 재조회 대기(최대 2 초)
        assertTrue(fake.callCount(SystemEngineKind.MLKIT_BASIC, "en") > before) // 상태 재조회
        fake.downloadFlow = flowOf(DownloadEvent.Done) // 이제 정상 흐름
        vm.downloadSystem(SystemEngineKind.MLKIT_BASIC, "en") // 다시 다운로드 가능
        withTimeout(2_000) { vm.systemRows.first { rs -> rs.first { it.kind == SystemEngineKind.MLKIT_BASIC }.busyLang == null } } // 끝남
        assertEquals(guide, withTimeout(2_000) { vm.messages.first() }) // 다시 안내(거부 메시지가 아님)
    }

    /** 목적: 진행 중이 아닐 때 취소(change(add)-hyungchul-2026-10-11-20:05) / 기능: 다운로드 중이 아니면 아무 일도 없다(메시지 없음) / 리턴: 없음 */
    @Test
    fun cancelWithoutDownloadDoesNothing() = runBlocking {
        val vm = newVm(FakeSystemEngineStatus(defaultStatuses())) // ViewModel
        vm.cancelSystemDownload(SystemEngineKind.MLKIT_BASIC) // 진행 중 아님
        assertNull(withTimeoutOrNull(300) { vm.messages.first() }) // 메시지 없음
    }

    /**
     * 목적: 느린 새로고침이 다운로드 직후 갱신을 덮지 않음(change(add)-hyungchul-2026-10-11-20:05)
     * 기능: 새로고침이 en 조회 결과(Downloadable)를 읽은 채 지연되는 동안 다운로드가 끝나 Available 로 갱신되면, 늦게 도착한 오래된 Downloadable 은 버려져 최종 상태가 Available 이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun staleRefreshDoesNotOverwriteDownloadResult() = runBlocking {
        val fake = FakeSystemEngineStatus(defaultStatuses(), downloadFlow = flowOf(DownloadEvent.Done)) // 다운로드는 즉시 끝남
        val vm = newVm(fake) // ViewModel(첫 조회 끝남: en=Downloadable)
        val gate = CompletableDeferred<Unit>() // 새로고침 지연 게이트
        fake.gate = gate // 게이트 설치
        fake.blockKey = SystemEngineKind.MLKIT_BASIC to "en" // ML Kit en 의 첫 조회만 대기
        vm.refreshSystem() // 느린 새로고침 시작(오래된 Downloadable 을 읽고 대기)
        fake.statuses[SystemEngineKind.MLKIT_BASIC to "en"] = LanguageStatus.Available // 기기 쪽 상태가 바뀜
        vm.downloadSystem(SystemEngineKind.MLKIT_BASIC, "en") // 다운로드가 끝나며 Available 로 갱신
        withTimeout(2_000) { vm.systemRows.first { rs -> rs.first { it.kind == SystemEngineKind.MLKIT_BASIC }.langs.firstOrNull { it.first == "en" }?.second == LanguageStatus.Available } } // 갱신 확인
        gate.complete(Unit) // 늦은 새로고침 결과 도착
        delay(300) // 늦은 결과가 반영될 시간
        val en = vm.systemRows.value.first { it.kind == SystemEngineKind.MLKIT_BASIC }.langs.first { it.first == "en" }.second // 최종 상태
        assertEquals(LanguageStatus.Available, en) // 오래된 결과가 덮지 않음
    }

    /**
     * 목적: 상태 확인 제한 시간
     * 기능: 15 초 안에 끝나는 상태는 그대로, 끝나지 않으면(가상 시간 15 초 초과) CheckFailed("상태 확인 시간 초과")(change(add)-hyungchul-2026-10-11-21:50 Unavailable → CheckFailed) 를 돌려준다. 정확히 15 초 직전에는 아직 대기 중이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun statusTimeoutIsUnavailable() = runTest(StandardTestDispatcher()) {
        assertEquals(LanguageStatus.Available, withStatusTimeout { LanguageStatus.Available }) // 정상 응답
        var result: LanguageStatus? = null // 결과
        val job = launch { result = withStatusTimeout { awaitCancellation() } } // 끝나지 않는 조회
        runCurrent() // 시작
        advanceTimeBy(14_999) // 15 초 직전
        runCurrent()
        assertNull(result) // 아직 대기
        advanceTimeBy(2) // 15 초 초과
        runCurrent()
        job.join() // 종료 대기
        assertEquals(LanguageStatus.CheckFailed("상태 확인 시간 초과"), result) // 시간 초과(일시적 확인 실패) // change(add)-hyungchul-2026-10-11-21:50
        assertEquals(StatusReasons.TIMEOUT, "상태 확인 시간 초과") // 상수 문구
        var custom: LanguageStatus? = null // 사용자 지정 시간
        val job2 = launch { custom = withStatusTimeout(timeoutMs = 100) { awaitCancellation() } } // 100 ms
        advanceTimeBy(101) // 초과
        runCurrent()
        job2.join()
        assertEquals(LanguageStatus.CheckFailed(StatusReasons.TIMEOUT), custom) // 지정 시간도 적용 // change(add)-hyungchul-2026-10-11-21:50
    }
}
