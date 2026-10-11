// 파일명: ModelInstallerTest.kt
// 목적: ModelInstaller / ModelStore 의 설치·검증·import·삭제 계약 검증
// 기능: 로컬 HttpServer 의 tar.bz2 로 설치(READY), 해시 불일치 시 정리, 폴더·압축 import, import 전용 spec 거부, 저장공간 부족, 사용 중 삭제 거부, 상태(CORRUPT) 판정을 확인한다.
//       change(add)-hyungchul-2026-10-11-18:14 모델 삭제가 tmp/<id>.dl(받다 만 파일)도 지우는지 확인한다(2차 작업 1).
package com.cowork.stt_ondevice.model // 모델 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import kotlinx.coroutines.runBlocking // 블로킹 실행
import org.junit.After // 각 테스트 후
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // 거짓
import org.junit.Assert.assertTrue // 참
import org.junit.Assert.fail // 실패
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.util.Random // 난수

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: ModelInstaller 회귀 방지
 * 기능: 작은 합성 모델(a.bin, sub/b.txt)을 tar.bz2 로 서빙·설치하고 결과 폴더와 상태를 확인한다.
 */
class ModelInstallerTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val server = TestHttpServer() // 로컬 서버
    private lateinit var dirs: AppDirs // 앱 폴더
    private lateinit var store: ModelStore // 저장소
    private lateinit var installer: ModelInstaller // 설치기

    private val aBin: ByteArray = ByteArray(5_000).also { Random(3).nextBytes(it) } // 모델 파일 1
    private val bTxt: ByteArray = "hello model".toByteArray() // 모델 파일 2
    private val files = listOf( // 검증 대상
        ModelFile("a.bin", aBin.size.toLong(), sha256Hex(aBin), "model"), // 파일 1
        ModelFile("sub/b.txt", bTxt.size.toLong(), sha256Hex(bTxt), "tokens"), // 파일 2
    )

    @org.junit.Before
    fun setUp() {
        dirs = AppDirs(tmp.newFolder("root")) // 루트 폴더
        store = ModelStore(dirs) // 저장소
        installer = ModelInstaller(dirs, store, Downloader()) // 설치기
    }

    @After
    fun tearDown() = server.close() // 서버 종료

    /** 목적: 합성 모델 tar.bz2 만들기 / 기능: pkg-1/ 접두 아래 a.bin, sub/b.txt 를 담는다 / 리턴: 압축 파일 */
    private fun makeArchive(): File = makeTarBz2(File(tmp.root, "model.tar.bz2")) { // tar.bz2
        addDir("pkg-1/") // 접두 폴더
        addFile("pkg-1/a.bin", aBin) // 파일 1
        addFile("pkg-1/sub/b.txt", bTxt) // 파일 2
    }

    /** 목적: 서버에 올린 합성 모델의 spec 만들기 / 기능: 서빙 후 해시·크기가 맞는 Download 를 가진 spec 을 돌려준다 / 입력: archiveSha(null 이면 실제 해시) / 리턴: ModelSpec */
    private fun servedSpec(id: String = "m1", archiveSha: String? = null, fileList: List<ModelFile> = files): ModelSpec {
        val archive = makeArchive() // 압축 파일
        server.serve("/model.tar.bz2", archive.readBytes()) // 서빙
        val dl = Download(server.url("/model.tar.bz2"), archive.length(), archiveSha ?: sha256Hex(archive.readBytes()), ArchiveType.TAR_BZ2, "pkg-1/") // 다운로드 정보
        return testSpec(id, listOf(dl), fileList) // spec
    }

    /** 목적: tmp 폴더 비었는지 확인용 / 기능: tmp 아래 항목 이름 목록 / 리턴: 이름 목록 */
    private fun tmpNames(): List<String> = dirs.tmp.list()?.toList() ?: emptyList() // 임시 폴더 내용

    /**
     * 목적: 정상 설치
     * 기능: 서버 tar.bz2 를 받아 풀고 검증해 models/<id> 에 두고 READY 가 되며 .ready.json 이 쓰이고 tmp 가 비어 있다. 진행 중 상태는 DOWNLOADING.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun installFromLocalHttpServer() = runBlocking {
        val spec = servedSpec() // spec
        assertEquals(InstallState.NOT_INSTALLED, store.state(spec)) // 처음엔 미설치
        val seen = mutableSetOf<InstallState>() // 진행 중 상태 기록
        var last: Pair<Long, Long>? = null // 마지막 진행률
        installer.install(spec) { d, t -> seen.add(store.state(spec)); last = d to t } // 설치
        assertEquals(InstallState.READY, store.state(spec)) // 완료 후 READY
        assertTrue(InstallState.DOWNLOADING in seen) // 진행 중 DOWNLOADING
        assertEquals(spec.downloads[0].bytes to spec.downloads[0].bytes, last) // 진행률이 끝까지
        assertArrayEqualsFile(aBin, File(store.modelDir(spec), "a.bin")) // 파일 1
        assertArrayEqualsFile(bTxt, File(store.modelDir(spec), "sub/b.txt")) // 파일 2
        val marker = File(store.modelDir(spec), ".ready.json").readText() // 표식
        assertTrue(marker.contains("\"id\": \"m1\"")) // id 기록
        assertTrue(marker.contains("verifiedAt")) // 검증 시각 기록
        assertTrue(marker.contains("\"files\": 2")) // 파일 수 기록
        assertEquals(emptyList<String>(), tmpNames()) // tmp 정리됨(.part·작업 폴더 없음)
        assertTrue(installer.verify(spec).isEmpty()) // 검증 문제 없음
    }

    /** 목적: 파일 내용 비교 / 입력: expected, actual / 리턴: 없음 */
    private fun assertArrayEqualsFile(expected: ByteArray, actual: File) =
        org.junit.Assert.assertArrayEquals(expected, actual.readBytes()) // 바이트 비교

    /**
     * 목적: 압축 해시 불일치 처리
     * 기능: 받은 압축의 SHA-256 이 카탈로그와 다르면 ChecksumMismatchException, 설치 안 됨(NOT_INSTALLED), tmp 정리(.part 포함), models 에 폴더 없음.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun checksumMismatchLeavesNotInstalled() = runBlocking {
        val spec = servedSpec(archiveSha = "0".repeat(64)) // 틀린 압축 해시
        try {
            installer.install(spec) { _, _ -> } // 설치 시도
            fail("ChecksumMismatchException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: ChecksumMismatchException) {
            // 기대한 실패
        }
        assertEquals(InstallState.NOT_INSTALLED, store.state(spec)) // 미설치
        assertFalse(store.modelDir(spec).exists()) // 모델 폴더 없음
        assertEquals(emptyList<String>(), tmpNames()) // tmp 정리됨
    }

    /**
     * 목적: 압축 해시 비어 있는 경우 건너뛰기
     * 기능: Download.sha256 이 빈 문자열이면 압축 해시 검사는 건너뛰고 파일별 검증으로 READY 가 된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun emptyArchiveShaSkipsArchiveCheck() = runBlocking {
        val spec = servedSpec(archiveSha = "") // 압축 해시 없음
        installer.install(spec) { _, _ -> } // 설치
        assertEquals(InstallState.READY, store.state(spec)) // 설치됨
    }

    /**
     * 목적: 파일별 해시가 진짜 관문
     * 기능: 압축 해시 검사를 건너뛰어도 풀린 파일의 SHA-256 이 다르면 ModelVerificationException 이고 설치되지 않으며 tmp 가 정리된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun extractedFileHashMismatchFails() = runBlocking {
        val bad = files.map { if (it.path == "a.bin") it.copy(sha256 = "f".repeat(64)) else it } // a.bin 해시만 틀림
        val spec = servedSpec(archiveSha = "", fileList = bad) // 압축 검사 없음 + 파일 해시 틀림
        try {
            installer.install(spec) { _, _ -> } // 설치 시도
            fail("ModelVerificationException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: ModelVerificationException) {
            assertTrue(e.problems.any { it.contains("a.bin") }) // 문제 파일 언급
        }
        assertEquals(InstallState.NOT_INSTALLED, store.state(spec)) // 미설치
        assertFalse(store.modelDir(spec).exists()) // 모델 폴더 없음
        assertEquals(emptyList<String>(), tmpNames()) // tmp 정리
    }

    /**
     * 목적: 선언 파일 누락 검출
     * 기능: 압축에 없는 파일이 files 에 선언되어 있으면 검증 실패로 설치되지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun missingDeclaredFileFails() = runBlocking {
        val extra = files + ModelFile("nope.bin", 3L, "a".repeat(64), "x") // 없는 파일 선언
        val spec = servedSpec(fileList = extra) // spec
        try {
            installer.install(spec) { _, _ -> } // 설치 시도
            fail("ModelVerificationException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: ModelVerificationException) {
            assertTrue(e.problems.any { it.contains("nope.bin") }) // 누락 파일 언급
        }
        assertEquals(InstallState.NOT_INSTALLED, store.state(spec)) // 미설치
    }

    /**
     * 목적: 이미 READY 면 재다운로드 안 함
     * 기능: READY 인 spec 에 install 을 다시 부르면 서버 요청 없이 끝난다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun installWhenReadyDoesNothing() = runBlocking {
        val spec = servedSpec() // spec
        installer.install(spec) { _, _ -> } // 첫 설치
        val before = server.requests.size // 요청 수
        installer.install(spec) { _, _ -> } // 두 번째
        assertEquals(before, server.requests.size) // 추가 요청 없음
    }

    /**
     * 목적: 일반 파일(NONE) 다운로드 설치
     * 기능: archive=NONE 다운로드는 bytes·sha256 이 같은 ModelFile 경로에 저장되어 설치된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun installNoneArchiveDownloads() = runBlocking {
        server.serve("/a.bin", aBin) // 파일 1
        server.serve("/b.txt", bTxt) // 파일 2
        val spec = testSpec( // 압축 없는 spec
            "m-none",
            listOf( // 다운로드 2개
                Download(server.url("/a.bin"), aBin.size.toLong(), sha256Hex(aBin), ArchiveType.NONE, ""), // a.bin
                Download(server.url("/b.txt"), bTxt.size.toLong(), sha256Hex(bTxt), ArchiveType.NONE, ""), // b.txt
            ),
            files, // a.bin, sub/b.txt
        )
        installer.install(spec) { _, _ -> } // 설치
        assertEquals(InstallState.READY, store.state(spec)) // 설치됨
        assertArrayEqualsFile(bTxt, File(store.modelDir(spec), "sub/b.txt")) // 하위 경로에 저장
    }

    /**
     * 목적: import 전용 spec 의 install 거부
     * 기능: downloads 가 빈 spec 은 install() 이 ImportOnlyModelException 을 던지고 아무것도 만들지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun importOnlySpecInstallThrows() = runBlocking {
        val spec = testSpec("imp", emptyList(), files) // import 전용
        try {
            installer.install(spec) { _, _ -> } // 설치 시도
            fail("ImportOnlyModelException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: ImportOnlyModelException) {
            assertTrue(e.message!!.contains("imp")) // 모델 id 포함
        }
        assertFalse(dirs.models.exists() && dirs.models.list()!!.isNotEmpty()) // 만든 것 없음
    }

    /**
     * 목적: 폴더 import 와 검증
     * 기능: 폴더에서 spec.files 의 상대 경로만 복사하고(여분 파일 제외) 검증 후 READY 가 된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun importFolderVerifies() = runBlocking {
        val spec = testSpec("imp", emptyList(), files) // import 전용 spec
        val src = tmp.newFolder("src") // 원본 폴더
        File(src, "a.bin").writeBytes(aBin) // 파일 1
        File(src, "sub").mkdirs() // 하위 폴더
        File(src, "sub/b.txt").writeBytes(bTxt) // 파일 2
        File(src, "extra.txt").writeText("무관한 파일") // 여분
        installer.importFrom(spec, src) // import
        assertEquals(InstallState.READY, store.state(spec)) // 설치됨
        assertFalse(File(store.modelDir(spec), "extra.txt").exists()) // 여분은 복사 안 함
        assertEquals(emptyList<String>(), tmpNames()) // tmp 정리
    }

    /**
     * 목적: import 검증 실패
     * 기능: 폴더의 파일 내용이 카탈로그 해시와 다르면 ModelVerificationException 이고 설치되지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun importFolderWithWrongContentFails() = runBlocking {
        val spec = testSpec("imp", emptyList(), files) // import 전용 spec
        val src = tmp.newFolder("src") // 원본 폴더
        File(src, "a.bin").writeBytes(aBin.copyOf().also { it[0] = (it[0] + 1).toByte() }) // 한 바이트 변조
        File(src, "sub").mkdirs() // 하위 폴더
        File(src, "sub/b.txt").writeBytes(bTxt) // 정상
        try {
            installer.importFrom(spec, src) // import 시도
            fail("ModelVerificationException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: ModelVerificationException) {
            assertTrue(e.problems.any { it.contains("a.bin") }) // 변조 파일 언급
        }
        assertEquals(InstallState.NOT_INSTALLED, store.state(spec)) // 미설치
        assertEquals(emptyList<String>(), tmpNames()) // tmp 정리
    }

    /**
     * 목적: 압축 파일 import
     * 기능: tar.bz2 파일을 source 로 주면 download 와 같은 해제 경로(stripPrefix 포함)로 풀어 검증·설치한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun importArchiveFile() = runBlocking {
        val archive = makeArchive() // 압축 파일
        val dl = Download("http://unused.invalid/m.tar.bz2", archive.length(), sha256Hex(archive.readBytes()), ArchiveType.TAR_BZ2, "pkg-1/") // stripPrefix 출처
        val spec = testSpec("imp-arc", listOf(dl), files) // spec
        installer.importFrom(spec, archive) // import
        assertEquals(InstallState.READY, store.state(spec)) // 설치됨
        assertArrayEqualsFile(aBin, File(store.modelDir(spec), "a.bin")) // 내용 정상
    }

    /**
     * 목적: 저장공간 부족 처리
     * 기능: usableSpace 가 (큰 쪽 바이트 × 2.2)보다 작으면 InsufficientStorageException, 서버 요청도 하지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun insufficientStorageThrows() = runBlocking {
        val spec = servedSpec() // spec
        val need = (maxOf(spec.downloads[0].bytes, files.sumOf { it.bytes }) * 2.2).toLong() // 필요량
        val poor = ModelInstaller(dirs, store, Downloader()) { need - 1 } // 여유가 1바이트 모자란 설치기
        try {
            poor.install(spec) { _, _ -> } // 설치 시도
            fail("InsufficientStorageException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: InsufficientStorageException) {
            assertEquals(need, e.requiredBytes) // 필요량
        }
        assertTrue(server.requests.isEmpty()) // 다운로드 시도 없음
        val rich = ModelInstaller(dirs, store, Downloader()) { need } // 딱 맞는 설치기
        rich.install(spec) { _, _ -> } // 통과
        assertEquals(InstallState.READY, store.state(spec)) // 설치됨
    }

    /**
     * 목적: 사용 중 삭제 거부
     * 기능: acquire 횟수가 0 보다 크면 delete 는 false 이고 폴더가 남으며, 모두 release 하면 삭제된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun deleteRefusedWhileAcquired() = runBlocking {
        val spec = servedSpec() // spec
        installer.install(spec) { _, _ -> } // 설치
        store.acquire(spec) // 사용 시작 1
        store.acquire(spec) // 사용 시작 2
        assertFalse(store.delete(spec)) // 거부
        assertTrue(store.modelDir(spec).exists()) // 남아 있음
        store.release(spec) // 해제 1
        assertFalse(store.delete(spec)) // 아직 1 개 사용 중
        store.release(spec) // 해제 2
        assertTrue(store.delete(spec)) // 삭제 성공
        assertFalse(store.modelDir(spec).exists()) // 폴더 없음
        assertEquals(InstallState.NOT_INSTALLED, store.state(spec)) // 미설치
    }

    // change(add)-hyungchul-2026-10-11-18:14
    /**
     * 목적: 모델 삭제 시 받다 만 파일도 지우기(2차 작업 1)
     * 기능: 설치된 모델을 delete 하면 tmp/<id>.dl 의 .part 도 함께 없어지고, 사용 중이면 둘 다 그대로이며,
     *       설치되지 않은 모델이라도 .dl 만 있으면 delete 가 그것을 지운다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: Unit
     */
    @Test
    fun deleteAlsoRemovesPartialDownload() = runBlocking {
        val spec = servedSpec() // spec
        installer.install(spec) { _, _ -> } // 설치
        val dl = File(dirs.tmp, "${spec.id}.dl") // 받다 만 폴더
        File(dl, "x.bin.part").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) } // 이어받기 조각 흉내
        store.acquire(spec) // 사용 중 표시
        assertFalse(store.delete(spec)) // 사용 중이라 거부
        assertTrue(File(dl, "x.bin.part").isFile) // 거부 때는 받다 만 파일도 그대로
        assertTrue(store.modelDir(spec).exists()) // 모델 폴더도 그대로
        store.release(spec) // 사용 해제
        assertTrue(store.delete(spec)) // 삭제 성공
        assertFalse(dl.exists()) // 받다 만 폴더 없어짐
        assertFalse(store.modelDir(spec).exists()) // 모델 폴더 없어짐
        val other = servedSpec(id = "m2") // 설치된 적 없는 모델
        val dl2 = File(dirs.tmp, "m2.dl") // 받다 만 폴더만 있음
        File(dl2, "y.bin.part").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) } // 조각
        assertEquals(InstallState.NOT_INSTALLED, store.state(other)) // 미설치
        assertTrue(store.delete(other)) // 삭제 성공
        assertFalse(dl2.exists()) // .dl 만 있어도 지워짐
    }

    /**
     * 목적: 파일 크기 불일치 → CORRUPT
     * 기능: .ready.json 이 있어도 파일 크기가 다르거나 파일이 없으면 CORRUPT, 표식이 없으면 NOT_INSTALLED. verify() 는 문제를 보고한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun stateCorruptWhenFileTruncated() = runBlocking {
        val spec = servedSpec() // spec
        installer.install(spec) { _, _ -> } // 설치
        File(store.modelDir(spec), "a.bin").writeBytes(ByteArray(10)) // 크기 변조
        assertEquals(InstallState.CORRUPT, store.state(spec)) // CORRUPT
        assertTrue(installer.verify(spec).any { it.contains("a.bin") }) // 문제 보고
        File(store.modelDir(spec), "a.bin").delete() // 파일 삭제
        assertEquals(InstallState.CORRUPT, store.state(spec)) // 여전히 CORRUPT
        File(store.modelDir(spec), ".ready.json").delete() // 표식 삭제
        assertEquals(InstallState.NOT_INSTALLED, store.state(spec)) // 표식 없으면 미설치
    }

    /**
     * 목적: CORRUPT 재설치
     * 기능: CORRUPT(READY 아님) 상태에서 install 하면 기존 폴더를 지우고 새로 설치해 READY 가 된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun reinstallReplacesCorruptDir() = runBlocking {
        val spec = servedSpec() // spec
        installer.install(spec) { _, _ -> } // 설치
        File(store.modelDir(spec), "a.bin").writeBytes(ByteArray(10)) // 손상
        File(store.modelDir(spec), "leftover.txt").writeText("옛 잔재") // 잔재
        installer.install(spec) { _, _ -> } // 재설치
        assertEquals(InstallState.READY, store.state(spec)) // 복구
        assertFalse(File(store.modelDir(spec), "leftover.txt").exists()) // 잔재 제거
    }

    /**
     * 목적: 사용 중 모델 교체 거부
     * 기능: acquire 중인 모델을 CORRUPT 상태에서 재설치하려 하면 IllegalStateException 으로 거부하고 폴더를 건드리지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun replaceRefusedWhileAcquired() = runBlocking {
        val spec = servedSpec() // spec
        installer.install(spec) { _, _ -> } // 설치
        File(store.modelDir(spec), "a.bin").writeBytes(ByteArray(10)) // 손상
        store.acquire(spec) // 사용 중
        try {
            installer.install(spec) { _, _ -> } // 재설치 시도
            fail("IllegalStateException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: IllegalStateException) {
            // 기대한 거부
        }
        assertTrue(File(store.modelDir(spec), "a.bin").exists()) // 폴더 유지
        assertEquals(emptyList<String>(), tmpNames()) // tmp 정리
    }
}
