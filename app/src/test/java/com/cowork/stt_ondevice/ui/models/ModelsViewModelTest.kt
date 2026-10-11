// 파일명: ModelsViewModelTest.kt
// 목적: 모델 관리 화면 ViewModel(ModelsViewModel)의 행 구성·삭제 거부·그룹/배지 라벨·import 폴더 안내·import 실행을 검증
// 기능: 임시 폴더 ModelStore/ModelInstaller 와 테스트용 ModelSpec 으로 rows 가 설치 상태를 반영하는지, 사용 중 모델 삭제가 한국어 오류 메시지로 거부되는지,
//       실제 카탈로그가 ASR(algo별) → VAD 모델 그룹으로 묶이고 라벨이 한국어인지, import 폴더 경로 안내와 폴더 해석·실행이 맞는지 확인한다.
//       change(add)-hyungchul-2026-10-11-03:59 SAF 가져오기(importFromSaf): import/<id>/saf_* 임시 폴더로 복사 → 설치 → 임시 폴더 정리를 확인한다.
//       change(add)-hyungchul-2026-10-11-18:21 그룹 구성 테스트가 화면용 목록(catalogForScreen, 시스템 엔진 제외)을 쓴다(2차 작업 2).
package com.cowork.stt_ondevice.ui.models // 모델 화면 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.core.storage.SafCopyResult // change(add)-hyungchul-2026-10-11-03:59 SAF 복사 결과
import com.cowork.stt_ondevice.model.ArchiveType // 압축 형식
import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 상태
import com.cowork.stt_ondevice.model.Download // 다운로드 정보
import com.cowork.stt_ondevice.model.Downloader // 다운로더
import com.cowork.stt_ondevice.model.InstallState // 설치 상태
import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그
import com.cowork.stt_ondevice.model.ModelFile // 모델 파일 정보
import com.cowork.stt_ondevice.model.ModelInstaller // 설치기
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소
import com.cowork.stt_ondevice.model.TestHttpServer // 로컬 HTTP 서버
import com.cowork.stt_ondevice.model.addFile // tar 항목 추가 도우미
import com.cowork.stt_ondevice.model.makeTarBz2 // tar.bz2 생성 도우미
import com.cowork.stt_ondevice.model.sha256Hex // SHA-256 도우미
import com.cowork.stt_ondevice.model.testSpec // 테스트용 ModelSpec
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.ExperimentalCoroutinesApi // 테스트 API 표시
import kotlinx.coroutines.flow.first // 첫 조건 값 대기
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.runBlocking // 블로킹 실행
import kotlinx.coroutines.test.UnconfinedTestDispatcher // 즉시 실행 테스트 디스패처
import kotlinx.coroutines.test.resetMain // Main 복구
import kotlinx.coroutines.test.setMain // Main 교체
import kotlinx.coroutines.withTimeout // 제한 시간
import org.junit.After // 각 테스트 후
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // change(add)-hyungchul-2026-10-11-03:59 거짓
import org.junit.Assert.assertNotNull // null 아님
import org.junit.Assert.assertNull // null
import org.junit.Assert.assertTrue // 참
import org.junit.Before // 각 테스트 전
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.util.Random // 난수

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: ModelsViewModel 회귀 방지
 * 기능: 합성 모델 2종(a: 설치됨 / b: 미설치)을 임시 저장소에 두고 ViewModel 동작을 확인한다.
 *       ViewModel 의 작업 디스패처에는 즉시 실행 테스트 디스패처를 넣고, 설치기 내부의 진짜 IO 스레드 작업은 시간 제한을 두고 기다린다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelsViewModelTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val dispatcher = UnconfinedTestDispatcher() // 즉시 실행 디스패처
    private lateinit var dirs: AppDirs // 앱 폴더
    private lateinit var store: ModelStore // 저장소
    private lateinit var installer: ModelInstaller // 설치기

    private val aBytes = ByteArray(2_000) { (it % 251).toByte() } // 모델 a 파일 내용
    private val bBytes = "model-b".toByteArray() // 모델 b 파일 내용
    private val specA = testSpec("model-a", emptyList(), listOf(ModelFile("a.bin", aBytes.size.toLong(), sha256Hex(aBytes), "model"))) // 설치된 모델
    private val specB = testSpec("model-b", emptyList(), listOf(ModelFile("b.bin", bBytes.size.toLong(), sha256Hex(bBytes), "model"))) // 미설치 모델

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher) // viewModelScope 용 Main 교체
        dirs = AppDirs(tmp.newFolder("root")) // 루트 폴더
        store = ModelStore(dirs) // 저장소
        installer = ModelInstaller(dirs, store, Downloader()) // 설치기
    }

    @After
    fun tearDown() = Dispatchers.resetMain() // Main 복구

    /** 목적: ViewModel 만들기 / 기능: 테스트 디스패처와 임시 import 루트를 넣는다 / 입력: specs / 리턴: ModelsViewModel */
    private fun newVm(specs: List<ModelSpec>): ModelsViewModel =
        ModelsViewModel(store, installer, specs, dirs.importDir, dispatcher) // 주입

    /** 목적: spec 을 READY 로 만들기 / 기능: 파일과 표식을 직접 기록한다 / 입력: spec, 내용 / 리턴: 없음 */
    private fun installDirectly(spec: ModelSpec, content: ByteArray) {
        val dir = store.modelDir(spec) // 모델 폴더
        File(dir, spec.files[0].path).apply { parentFile!!.mkdirs(); writeBytes(content) } // 파일 기록
        store.writeMarker(spec, dir) // 표식 기록
    }

    /**
     * 목적: rows 가 저장소 상태를 반영하는지
     * 기능: a 는 READY, b 는 NOT_INSTALLED 로 보이고, 표시 이름·언어·라이선스·상업 상태가 spec 에서 온다. a 를 지운 뒤 refresh 하면 NOT_INSTALLED 로 바뀐다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rowsReflectStoreState() {
        installDirectly(specA, aBytes) // a 설치
        val vm = newVm(listOf(specA, specB)) // ViewModel
        val rows = vm.rows.value.associateBy { it.id } // id → 행
        assertEquals(InstallState.READY, rows.getValue("model-a").state) // a 설치됨
        assertEquals(InstallState.NOT_INSTALLED, rows.getValue("model-b").state) // b 미설치
        assertEquals("model-a", rows.getValue("model-a").name) // 표시 이름
        assertEquals("한국어", rows.getValue("model-a").languages) // 언어 라벨
        assertEquals("test", rows.getValue("model-a").license) // 라이선스
        assertEquals(CommercialStatus.ALLOWED, rows.getValue("model-a").commercial) // 상업 상태
        assertNull(rows.getValue("model-a").progress) // 받는 중이 아니면 진행률 없음
        assertEquals(1L, rows.getValue("model-a").sizeMb) // 2,000 바이트는 올림해서 1MB
        assertTrue(store.delete(specA)) // 저장소에서 직접 삭제
        vm.refresh() // 상태 다시 읽기
        assertEquals(InstallState.NOT_INSTALLED, vm.rows.value.first { it.id == "model-a" }.state) // 반영
    }

    /**
     * 목적: 사용 중 모델 삭제 거부
     * 기능: a 를 acquire 한 채 delete 하면 한국어 오류 메시지("사용 중")가 나오고 모델은 그대로 READY 다. release 후에는 삭제된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun deleteInUseShowsError() = runBlocking {
        installDirectly(specA, aBytes) // a 설치
        val vm = newVm(listOf(specA, specB)) // ViewModel
        store.acquire(specA) // 사용 중 표시
        vm.delete("model-a") // 삭제 시도
        val msg = withTimeout(5_000) { vm.messages.first() } // 오류 메시지
        assertTrue(msg, msg.contains("삭제") && msg.contains("사용 중")) // 한국어 사유
        assertEquals(InstallState.READY, store.state(specA)) // 그대로 남음
        store.release(specA) // 사용 종료
        vm.delete("model-a") // 다시 삭제
        withTimeout(5_000) { vm.messages.first() } // 성공 메시지
        assertEquals(InstallState.NOT_INSTALLED, store.state(specA)) // 삭제됨
        assertEquals(InstallState.NOT_INSTALLED, vm.rows.value.first { it.id == "model-a" }.state) // 행도 갱신
    }

    /**
     * 목적: 그룹 구성과 한국어 라벨
     * 기능: 실제 카탈로그로 행을 만들면 ASR 은 algo 그룹(카탈로그 순서), 마지막은 "VAD 모델" 그룹이다.
     *       상업 상태·설치 상태 라벨이 지정한 한국어이며, whisper tiny 는 한국어/영어 지원이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun groupingAndBadges() {
        val screen = ModelsViewModel.catalogForScreen(ModelCatalog.all) // 화면용 카탈로그(시스템 엔진 제외) // change(add)-hyungchul-2026-10-11-18:21
        val vm = newVm(screen) // 실제 카탈로그 // change(add)-hyungchul-2026-10-11-18:21 화면용 목록
        val rows = vm.rows.value // 전체 행
        assertEquals(screen.size, rows.size) // 모든 모델 표시 // change(add)-hyungchul-2026-10-11-18:21
        val asrRows = rows.filter { it.group != ModelsViewModel.VAD_GROUP } // ASR 행
        assertEquals(screen.filter { it.kind == ModelKind.ASR }.map { it.algo }, asrRows.map { it.group }) // ASR 은 algo 그룹 // change(add)-hyungchul-2026-10-11-18:21
        val vadRows = rows.filter { it.group == ModelsViewModel.VAD_GROUP } // VAD 행
        assertEquals(ModelCatalog.all.count { it.kind == ModelKind.VAD }, vadRows.size) // VAD 개수
        assertEquals("VAD 모델", ModelsViewModel.VAD_GROUP) // 그룹 이름
        assertEquals(rows.size - vadRows.size, asrRows.size) // 나머지는 ASR
        assertTrue(rows.takeLast(vadRows.size).all { it.group == ModelsViewModel.VAD_GROUP }) // VAD 그룹이 맨 뒤
        val tiny = rows.first { it.id == "whispercpp-tiny" } // whisper tiny
        assertEquals("한국어, 영어", tiny.languages) // 언어 라벨
        assertTrue(tiny.sizeMb > 0) // 크기 표시
        assertEquals("언어 무관", vadRows.first().languages) // VAD 는 언어 무관
        assertEquals("상업 가능", CommercialStatus.ALLOWED.koreanLabel()) // 허용
        assertEquals("조건부", CommercialStatus.CONDITIONAL.koreanLabel()) // 조건부
        assertEquals("비상업", CommercialStatus.NONCOMMERCIAL.koreanLabel()) // 비상업
        assertEquals("미확인", CommercialStatus.UNVERIFIED.koreanLabel()) // 미확인
        assertEquals("미설치", InstallState.NOT_INSTALLED.koreanLabel()) // 미설치
        assertEquals("다운로드 중", InstallState.DOWNLOADING.koreanLabel()) // 받는 중
        assertEquals("검증 중", InstallState.VERIFYING.koreanLabel()) // 검증 중
        assertEquals("설치됨", InstallState.READY.koreanLabel()) // 설치됨
        assertEquals("손상", InstallState.CORRUPT.koreanLabel()) // 손상
    }

    /**
     * 목적: import 폴더 안내
     * 기능: requestImport 가 import/<id> 폴더를 만들고 그 정확한 경로를 importPrompt 로 노출한다. dismiss 하면 사라진다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun importFolderPathShown() {
        val vm = newVm(listOf(specA, specB)) // ViewModel
        assertNull(vm.importPrompt.value) // 처음엔 없음
        vm.requestImport("model-b") // 가져오기 요청
        val prompt = vm.importPrompt.value // 안내 상태
        assertNotNull(prompt) // 표시됨
        assertEquals("model-b", prompt!!.modelId) // 대상 모델
        assertEquals(File(dirs.importDir, "model-b").path, prompt.folderPath) // 정확한 경로
        assertTrue(File(prompt.folderPath).isDirectory) // 폴더가 만들어져 adb push 가능
        vm.dismissImportPrompt() // 닫기
        assertNull(vm.importPrompt.value) // 사라짐
    }

    /**
     * 목적: import 원본 해석
     * 기능: 폴더가 없거나 비면 한국어 오류, 압축 파일 1개면 그 파일, 선언 파일이 든 폴더 1개뿐이면 그 안(adb push 로 폴더째 넣은 경우), 그 밖엔 폴더 자체.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun resolveImportSourceRules() {
        val vm = newVm(listOf(specA, specB)) // ViewModel
        val base = File(dirs.importDir, "model-b") // import/model-b
        try { vm.resolveImportSource(specB); org.junit.Assert.fail("빈 폴더는 오류여야 함") } catch (e: java.io.FileNotFoundException) { assertTrue(e.message!!.contains(base.path)) } // 없음
        base.mkdirs() // 폴더 생성
        try { vm.resolveImportSource(specB); org.junit.Assert.fail("빈 폴더는 오류여야 함") } catch (e: java.io.FileNotFoundException) { assertTrue(e.message!!.contains("비어")) } // 비어 있음
        val archive = File(base, "m.tar.bz2").apply { writeBytes(byteArrayOf(1)) } // 파일 1개
        assertEquals(archive, vm.resolveImportSource(specB)) // 그 파일
        archive.delete() // 정리
        val wrapper = File(base, "wrapped").apply { mkdirs() } // 폴더 1개(아직 선언 파일 없음)
        assertEquals(base, vm.resolveImportSource(specB)) // 선언 파일이 안에 없으면 폴더 자체
        File(wrapper, "b.bin").writeBytes(bBytes) // 폴더째 넣은 모양
        assertEquals(wrapper, vm.resolveImportSource(specB)) // 그 폴더 안
        File(base, "b.bin").writeBytes(bBytes) // 폴더+파일 혼합
        assertEquals(base, vm.resolveImportSource(specB)) // 폴더 자체
    }

    /**
     * 목적: import 실행(폴더)
     * 기능: import/model-b/b.bin 을 두고 confirmImport 하면 설치기가 검증해 READY 가 되고 행이 갱신되며 안내가 닫힌다. 원본은 지우지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun confirmImportInstallsFromFolder() = runBlocking {
        val vm = newVm(listOf(specA, specB)) // ViewModel
        vm.requestImport("model-b") // 폴더 생성 + 안내
        File(dirs.importDir, "model-b/b.bin").writeBytes(bBytes) // adb push 흉내
        vm.confirmImport("model-b") // 실행
        withTimeout(10_000) { vm.rows.first { r -> r.first { it.id == "model-b" }.state == InstallState.READY } } // READY 대기
        assertEquals(InstallState.READY, store.state(specB)) // 설치됨
        assertNull(vm.importPrompt.value) // 안내 닫힘
        assertTrue(File(dirs.importDir, "model-b/b.bin").isFile) // 원본 유지
    }

    /**
     * 목적: import 실패 메시지
     * 기능: 내용이 잘못된 파일을 import 하면 한국어 실패 메시지가 나오고 미설치로 남는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun importWithWrongFileShowsError() = runBlocking {
        val vm = newVm(listOf(specA, specB)) // ViewModel
        vm.requestImport("model-b") // 폴더 생성
        File(dirs.importDir, "model-b/b.bin").writeBytes("틀린 내용".toByteArray()) // 해시 불일치 파일
        vm.confirmImport("model-b") // 실행
        val msg = withTimeout(10_000) { vm.messages.first() } // 오류 메시지
        assertTrue(msg, msg.contains("가져오기 실패")) // 한국어 실패
        assertEquals(InstallState.NOT_INSTALLED, store.state(specB)) // 미설치 유지
    }

    /**
     * 목적: 검증 결과 노출
     * 기능: 정상 설치본은 문제 목록이 비고, 파일이 변조된 설치본은 문제 목록이 채워져 verifyReport 로 나온다. dismiss 하면 사라진다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun verifyReportsProblems() = runBlocking {
        installDirectly(specA, aBytes) // a 설치
        val vm = newVm(listOf(specA, specB)) // ViewModel
        vm.verify("model-a") // 정상 검증
        val ok = withTimeout(10_000) { vm.verifyReport.first { it != null } }!! // 결과
        assertTrue(ok.problems.isEmpty()) // 문제 없음
        vm.dismissVerifyReport() // 닫기
        assertNull(vm.verifyReport.value) // 사라짐
        File(store.modelDir(specA), "a.bin").writeBytes(ByteArray(2_000) { 7 }) // 같은 크기, 다른 내용으로 변조
        vm.verify("model-a") // 다시 검증
        val bad = withTimeout(10_000) { vm.verifyReport.first { it != null } }!! // 결과
        assertTrue(bad.problems.single().contains("SHA-256 불일치")) // 해시 불일치 보고
    }

    /**
     * 목적: 압축 파일 import
     * 기능: import/model-b/ 에 tar.bz2 한 개만 있으면 그 압축으로 설치된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun confirmImportInstallsFromArchive() = runBlocking {
        val vm = newVm(listOf(specA, specB)) // ViewModel
        vm.requestImport("model-b") // 폴더 생성
        makeTarBz2(File(dirs.importDir, "model-b/b.tar.bz2")) { addFile("b.bin", bBytes) } // 압축 1개
        vm.confirmImport("model-b") // 실행
        withTimeout(10_000) { vm.rows.first { r -> r.first { it.id == "model-b" }.state == InstallState.READY } } // READY 대기
        assertEquals(InstallState.READY, store.state(specB)) // 설치됨
    }

    // change(add)-hyungchul-2026-10-11-03:24
    /**
     * 목적: 다운로드 시작·진행률·취소·재시작(이어받기) 전 과정
     * 기능: 로컬 서버가 본문 일부만 보내고 멈추게 해 "받는 도중"을 결정적으로 만든다.
     *       (1) download 직후 행이 DOWNLOADING 이고 진행률이 올라간다 (2) cancel 후 정리 전 재시도는 "정리하는 중" 안내로 거부된다(실패 메시지 아님)
     *       (3) 정리가 끝나면 NOT_INSTALLED 로 돌아가고 취소 메시지가 나오며 .part 가 일부만 남는다 (4) 다시 download 하면 Range 로 이어받아 READY 가 된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun downloadCancelResumeFlow() = runBlocking {
        val server = TestHttpServer() // 로컬 서버
        try {
            val body = ByteArray(200_000).also { Random(9).nextBytes(it) } // 받을 파일
            server.serve("/m.bin", body) // 서빙
            val spec = testSpec("model-dl", listOf(Download(server.url("/m.bin"), body.size.toLong(), sha256Hex(body), ArchiveType.NONE, "")),
                listOf(ModelFile("m.bin", body.size.toLong(), sha256Hex(body), "model"))) // 다운로드 가능한 spec
            val gate = server.gate("/m.bin", 100_000) // 100KB 보낸 뒤 멈춤
            val vm = newVm(listOf(spec)) // ViewModel
            fun row() = vm.rows.value.first { it.id == "model-dl" } // 현재 행
            vm.download("model-dl") // 시작
            assertEquals(InstallState.DOWNLOADING, row().state) // (1) 시작 직후 곧바로 받는 중으로 보임(취소 버튼 노출 조건)
            val moved = withTimeout(10_000) { vm.rows.first { r -> r.first().let { it.state == InstallState.DOWNLOADING && (it.progress ?: 0f) >= 0.3f } } } // 진행률 상승 대기
            assertTrue(moved.first().progress!! < 1f) // 아직 끝나지 않음
            vm.cancel("model-dl") // 취소 요청(서버가 멈춘 상태라 정리까지 시간이 걸림)
            vm.download("model-dl") // 정리 전 재시도
            val early = withTimeout(5_000) { vm.messages.first() } // 거부 메시지
            assertTrue(early, early.contains("정리하는 중") && !early.contains("실패")) // (2) 실패가 아니라 안내
            gate.countDown() // 서버 재개 → 다음 읽기에서 취소 확인
            withTimeout(10_000) { vm.rows.first { r -> r.first().state == InstallState.NOT_INSTALLED } } // 정리 후 미설치 복귀
            val cancelMsg = withTimeout(5_000) { vm.messages.first() } // 취소 메시지
            assertTrue(cancelMsg, cancelMsg.contains("취소했습니다")) // (3) 취소 메시지
            val part = File(dirs.tmp, "model-dl.dl/0.dl.part") // 이어받기용 .part
            assertTrue(part.isFile && part.length() > 0 && part.length() < body.size) // 일부만 보관
            val partLen = part.length() // 보관된 크기
            vm.download("model-dl") // (4) 재시작
            withTimeout(10_000) { vm.rows.first { r -> r.first().state == InstallState.READY } } // 설치됨
            assertEquals("bytes=$partLen-", server.requests.last().range) // 보관한 지점부터 이어받음
            assertEquals(InstallState.READY, store.state(spec)) // 저장소도 READY
            assertTrue(!part.exists()) // .part 정리됨
        } finally {
            server.close() // 서버 종료
        }
    }

    // change(add)-hyungchul-2026-10-11-03:24
    /**
     * 목적: 가져오기 진행 중 행 상태
     * 기능: 안내 없이 importFrom 으로 시작하면 곧바로 VERIFYING(검증 중)으로 보이고 끝나면 READY 가 된다(작업 시작 상태 반영).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun importShowsVerifyingThenReady() = runBlocking {
        val vm = newVm(listOf(specA, specB)) // ViewModel
        val src = tmp.newFolder("srcB") // 원본 폴더
        File(src, "b.bin").writeBytes(bBytes) // 원본 파일
        val seen = mutableSetOf<InstallState>() // 관찰한 상태
        val collector = launchCollect(this, vm, seen) // 상태 수집 시작
        vm.importFrom("model-b", src) // 가져오기
        withTimeout(10_000) { vm.rows.first { r -> r.first { it.id == "model-b" }.state == InstallState.READY } } // 완료 대기
        collector.cancel() // 수집 종료
        assertTrue(seen.toString(), InstallState.VERIFYING in seen) // 도중에 검증 중으로 보였음
    }

    /** 목적: model-b 행 상태 수집 / 기능: scope 에서 rows 를 구독해 model-b 의 상태를 seen 에 모은다(Unconfined 라 즉시 반영) / 리턴: 수집 Job */
    private fun launchCollect(scope: kotlinx.coroutines.CoroutineScope, vm: ModelsViewModel, seen: MutableSet<InstallState>) =
        scope.launch(dispatcher) { vm.rows.collect { r -> r.firstOrNull { it.id == "model-b" }?.let { seen.add(it.state) } } }

    // change(add)-hyungchul-2026-10-11-03:59
    /**
     * 목적: SAF 가져오기(폴더째 고른 경우)
     * 기능: copy 가 받은 임시 폴더(import/model-b/saf_…) 안에 "모델폴더/b.bin" 을 만들면(상위 폴더를 고른 경우 흉내) 그 하위 폴더를 원본으로 설치해 READY 가 되고,
     *       끝나면 임시 폴더가 지워진다. copy 가 실패하면 "가져오기 실패" 메시지와 함께 임시 폴더가 정리되고 미설치로 남는다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun importFromSafInstallsAndCleansStaging() = runBlocking {
        val vm = newVm(listOf(specA, specB)) // ViewModel
        var stage: File? = null // copy 에 넘어온 임시 폴더
        vm.importFromSaf("model-b") { dir -> // SAF 복사 흉내
            stage = dir // 기록
            File(dir, "picked/b.bin").apply { parentFile!!.mkdirs(); writeBytes(bBytes) } // 폴더째 복사된 모양
            SafCopyResult(copied = 1, skipped = 0, bytes = bBytes.size.toLong(), target = dir.path) // 결과
        }
        withTimeout(10_000) { vm.rows.first { r -> r.first { it.id == "model-b" }.state == InstallState.READY } } // READY 대기
        assertEquals(File(dirs.importDir, "model-b"), stage!!.parentFile) // import/<id> 아래 임시 폴더
        assertTrue(stage!!.name.startsWith("saf_")) // 이름 규칙
        withTimeout(5_000) { while (stage!!.exists()) kotlinx.coroutines.delay(10) } // 정리 대기
        assertFalse(stage!!.exists()) // 임시 폴더 삭제
        val failing = newVm(listOf(specA, specB)) // 새 ViewModel
        store.delete(specB) // 다시 미설치로
        var stage2: File? = null // 두 번째 임시 폴더
        failing.importFromSaf("model-b") { dir -> stage2 = dir; File(dir, "half.bin").writeBytes(ByteArray(3)); throw java.io.IOException("SAF 읽기 실패") } // 복사 실패
        val msg = withTimeout(10_000) { failing.messages.first { it.contains("실패") } } // 실패 메시지
        assertTrue(msg, msg.contains("가져오기 실패") && msg.contains("SAF 읽기 실패")) // 사유
        withTimeout(5_000) { while (stage2!!.exists()) kotlinx.coroutines.delay(10) } // 정리 대기
        assertEquals(InstallState.NOT_INSTALLED, store.state(specB)) // 미설치 유지
    }
}
