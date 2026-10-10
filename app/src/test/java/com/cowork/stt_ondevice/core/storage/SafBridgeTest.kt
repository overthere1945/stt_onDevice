// 파일명: core/storage/SafBridgeTest.kt
// 목적: SAF 입출력 다리(SafBridge)의 순수 경로 논리와 트리 복사 논리를 JVM 에서 검증
// 기능: 실제 SAF(DocumentsContract)는 JVM 에 없으므로, 같은 인터페이스(TreeSource/TreeSink)를 일반 폴더로 흉내 낸 가짜로
//       (1) 언어 폴더 감지(데이터셋 루트 / 언어 폴더 자체 / 알 수 없음)와 가져오기 대상 폴더 계산,
//       (2) 가져오기: 상대 경로 보존, wav 만(대소문자 무시), 지정한 최상위 언어 폴더만, 이미 있는 파일은 덮어쓰지 않고 건너뜀, 위험한 이름(..) 차단, 취소 시 반쯤 쓴 파일 없음,
//       (3) 내보내기: run 폴더 이름으로 새 하위 폴더를 만들고 이름이 겹치면 _1, _2 접미(기존 것 덮어쓰지 않음), 하위 폴더·파일 내용 보존을 확인한다.
// change(add)-hyungchul-2026-10-11-03:59
package com.cowork.stt_ondevice.core.storage // 저장소 테스트 패키지

import kotlinx.coroutines.CancellationException // 취소 예외
import kotlinx.coroutines.Job // 작업 핸들
import kotlinx.coroutines.runBlocking // 블로킹 실행
import kotlinx.coroutines.withContext // 컨텍스트 전환
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // 거짓
import org.junit.Assert.assertNull // null
import org.junit.Assert.assertThrows // 예외 확인
import org.junit.Assert.assertTrue // 참
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.io.InputStream // 입력 스트림
import java.io.OutputStream // 출력 스트림

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 일반 폴더를 SAF 읽기 트리처럼 보이게 하는 가짜
 * 기능: 문서 id = 루트 기준 상대 경로("" 가 루트). extraNames 로 특정 폴더에 위험한 이름의 가짜 항목을 끼워 넣을 수 있다. onOpen 은 파일을 열 때마다 불린다.
 * 입력: root(흉내 낼 폴더), extraNames(폴더 id → 추가 항목 이름), onOpen(열기 콜백)
 */
private class FileTreeSource(
    private val root: File, // 루트 폴더
    private val extraNames: Map<String, List<String>> = emptyMap(), // 끼워 넣을 이름
    private val onOpen: (String) -> Unit = {}, // 열기 콜백
) : TreeSource {
    /** 목적: 자식 목록 / 기능: 폴더의 실제 항목(이름순) + 끼워 넣은 가짜 파일 항목 / 입력: dirId / 리턴: TreeEntry 목록 */
    override fun children(dirId: String): List<TreeEntry> {
        val dir = if (dirId.isEmpty()) root else File(root, dirId) // 폴더
        val real = dir.listFiles()!!.sortedBy { it.name }.map { f -> TreeEntry(if (dirId.isEmpty()) f.name else "$dirId/${f.name}", f.name, f.isDirectory) } // 실제 항목
        val fake = extraNames[dirId].orEmpty().map { n -> TreeEntry("fake:$n", n, false) } // 가짜 항목
        return real + fake // 합침
    }

    /** 목적: 파일 열기 / 기능: 콜백 후 실제 파일 스트림(가짜 항목이면 빈 스트림) / 입력: id / 리턴: InputStream */
    override fun open(id: String): InputStream {
        onOpen(id) // 콜백
        return if (id.startsWith("fake:")) ByteArray(0).inputStream() else File(root, id).inputStream() // 스트림
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 일반 폴더를 SAF 쓰기 트리처럼 보이게 하는 가짜
 * 기능: 문서 id = 루트 기준 상대 경로. 폴더 만들기는 이미 있으면 실패(실제 SAF 는 이름을 바꾸지만, 여기서는 덮어쓰기 시도를 잡기 위해 실패시킴), 파일 만들기도 이미 있으면 실패.
 * 입력: root(대상 폴더)
 */
private class FileTreeSink(private val root: File) : TreeSink {
    /** 목적: 자식 이름 / 입력: dirId / 리턴: 이름 집합 */
    override fun childNames(dirId: String): Set<String> = (if (dirId.isEmpty()) root else File(root, dirId)).list()!!.toSet() // 이름들

    /** 목적: 폴더 만들기 / 기능: 이미 있으면 IllegalStateException / 입력: parentId, name / 리턴: 새 id */
    override fun createDir(parentId: String, name: String): String {
        val id = if (parentId.isEmpty()) name else "$parentId/$name" // 새 id
        check(File(root, id).mkdir()) { "이미 있음: $id" } // 덮어쓰기 금지
        return id // id
    }

    /** 목적: 파일 만들기 / 기능: 이미 있으면 IllegalStateException / 입력: parentId, name / 리턴: 출력 스트림 */
    override fun createFile(parentId: String, name: String): OutputStream {
        val f = File(root, if (parentId.isEmpty()) name else "$parentId/$name") // 파일
        check(f.createNewFile()) { "이미 있음: $f" } // 덮어쓰기 금지
        return f.outputStream() // 스트림
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: SafBridge 순수 논리 회귀 방지
 * 기능: 파일 머리말의 항목을 확인한다.
 */
class SafBridgeTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더

    /** 목적: 파일 만들기 / 입력: root, rel(상대 경로), text(내용) / 리턴: File */
    private fun put(root: File, rel: String, text: String = rel): File =
        File(root, rel).apply { parentFile!!.mkdirs(); writeText(text) } // 부모 폴더 생성 후 기록

    /**
     * 목적: 언어 폴더 감지와 가져오기 대상
     * 기능: 최상위에 kr·en 이 있으면 데이터셋 루트(그 두 폴더만), 트리 이름이 kr/en 이면 그 언어 폴더 자체, 그 밖은 Unknown(언어 선택 필요).
     *       대상: 데이터셋 루트 → input_sound(최상위 kr·en 만), 언어 폴더 → input_sound/<lang>, Unknown+선택 en → input_sound/en/<트리 이름>.
     *       Unknown 인데 언어를 고르지 않으면 IllegalArgumentException.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun languageFolderDetection() {
        assertEquals(InputTreeLayout.DatasetRoot(setOf("kr", "en")), SafBridge.detectLayout("input_sound", listOf("en", "kr", "docs"))) // 둘 다
        assertEquals(InputTreeLayout.DatasetRoot(setOf("kr")), SafBridge.detectLayout("data", listOf("kr"))) // kr 만
        assertEquals(InputTreeLayout.LangFolder("en"), SafBridge.detectLayout("en", listOf("vLog", "Meeting"))) // 언어 폴더 자체
        assertEquals(InputTreeLayout.Unknown, SafBridge.detectLayout("customer", listOf("a", "KR"))) // 대소문자 다르면 아님
        val root = File(tmp.root, "input_sound") // 입력 루트
        assertEquals(root to setOf("kr", "en"), SafBridge.inputDest(root, InputTreeLayout.DatasetRoot(setOf("kr", "en")), null, "x")) // 데이터셋 루트
        assertEquals(File(root, "kr") to null, SafBridge.inputDest(root, InputTreeLayout.LangFolder("kr"), null, "kr")) // 언어 폴더
        assertEquals(File(root, "en/customer") to null, SafBridge.inputDest(root, InputTreeLayout.Unknown, "en", "customer")) // 선택 언어 + 트리 이름
        assertThrows(IllegalArgumentException::class.java) { SafBridge.inputDest(root, InputTreeLayout.Unknown, null, "customer") } // 언어 미선택
        assertThrows(IllegalArgumentException::class.java) { SafBridge.inputDest(root, InputTreeLayout.Unknown, "jp", "customer") } // 모르는 언어
        assertEquals(File(root, "kr/saf_import") to null, SafBridge.inputDest(root, InputTreeLayout.Unknown, "kr", "..")) // 위험한 트리 이름은 대체 이름
    }

    /**
     * 목적: 이름 겹침 접미와 안전한 이름 판정
     * 기능: 없는 이름은 그대로, 있으면 _1, _1 도 있으면 _2. "", ".", "..", '/'·'\\'·NUL 포함 이름은 안전하지 않음.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun suffixOnCollision() {
        assertEquals("20261011_035022", SafBridge.uniqueName("20261011_035022", setOf("other"))) // 그대로
        assertEquals("20261011_035022_1", SafBridge.uniqueName("20261011_035022", setOf("20261011_035022"))) // _1
        assertEquals("20261011_035022_2", SafBridge.uniqueName("20261011_035022", setOf("20261011_035022", "20261011_035022_1"))) // _2
        assertTrue(SafBridge.isSafeName("01.wav")) // 정상
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a\u0000b")) assertFalse(bad, SafBridge.isSafeName(bad)) // 위험
    }

    /**
     * 목적: 가져오기 — 상대 경로 보존과 필터
     * 기능: 트리(kr/customer/01.wav, kr/customer/notes.txt, en/vLog/01.WAV, other/x.wav, top.wav, kr/customer 안의 가짜 ".." 항목)를
     *       topDirs={kr,en}, wav 만으로 가져오면 kr/customer/01.wav 와 en/vLog/01.WAV 2개만 같은 상대 경로로 복사되고(내용 동일), 위험한 이름은 건너뜀(1).
     *       다시 가져오면 기존 파일은 덮어쓰지 않고 모두 건너뜀(복사 0). 모든 파일(wav 만 아님) 모드에서는 notes.txt 도 복사된다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun importPreservesRelativePaths() = runBlocking {
        val src = tmp.newFolder("picked") // 고른 트리
        for (rel in listOf("kr/customer/01.wav", "kr/customer/notes.txt", "en/vLog/01.WAV", "other/x.wav", "top.wav")) put(src, rel) // 파일들
        val source = FileTreeSource(src, extraNames = mapOf("kr/customer" to listOf(".."))) // 가짜 SAF 트리(위험 이름 포함)
        val dest = File(tmp.root, "input_sound") // 대상
        val r = SafBridge.copyTreeToDir(source, "", dest, wavOnly = true, topDirs = setOf("kr", "en")) // 가져오기
        assertEquals(2, r.copied) // 2개 복사
        assertEquals(1, r.skipped) // 위험 이름 1개 건너뜀
        assertEquals("kr/customer/01.wav", File(dest, "kr/customer/01.wav").readText()) // 상대 경로·내용 보존
        assertEquals("en/vLog/01.WAV", File(dest, "en/vLog/01.WAV").readText()) // 대문자 확장자도 wav
        assertFalse(File(dest, "kr/customer/notes.txt").exists()) // wav 아님
        assertFalse(File(dest, "other").exists()) // 지정 밖 최상위 폴더
        assertFalse(File(dest, "top.wav").exists()) // 최상위 파일은 언어 폴더 밖
        assertTrue(dest.walkTopDown().none { it.name.endsWith(".part") }) // 임시 파일 없음
        File(dest, "kr/customer/01.wav").writeText("사용자 수정본") // 기존 파일 변경
        val again = SafBridge.copyTreeToDir(source, "", dest, wavOnly = true, topDirs = setOf("kr", "en")) // 다시 가져오기
        assertEquals(0, again.copied) // 복사 없음
        assertEquals(3, again.skipped) // 기존 2 + 위험 1
        assertEquals("사용자 수정본", File(dest, "kr/customer/01.wav").readText()) // 덮어쓰지 않음
        val all = File(tmp.root, "model_stage") // 모델 가져오기 흉내
        val m = SafBridge.copyTreeToDir(FileTreeSource(File(src, "kr")), "", all, wavOnly = false, topDirs = null) // 모든 파일
        assertEquals(2, m.copied) // 01.wav + notes.txt
        assertTrue(File(all, "customer/notes.txt").isFile) // 비 wav 도 복사
    }

    /**
     * 목적: 가져오기 취소
     * 기능: 두 번째 파일을 열 때 작업을 취소하면 CancellationException 이 나고, 첫 파일은 완성본으로 남고 두 번째 파일·.part 임시 파일은 남지 않는다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun importCancellable() {
        val src = tmp.newFolder("picked") // 고른 트리
        put(src, "kr/a/01.wav", "x".repeat(10_000)); put(src, "kr/a/02.wav", "y".repeat(10_000)) // 2개
        val dest = File(tmp.root, "input_sound") // 대상
        val job = Job() // 취소할 작업
        var opened = 0 // 연 파일 수
        val source = FileTreeSource(src, onOpen = { if (++opened == 2) job.cancel() }) // 두 번째 열 때 취소
        assertThrows(CancellationException::class.java) {
            runBlocking { withContext(job) { SafBridge.copyTreeToDir(source, "", dest, wavOnly = true, topDirs = setOf("kr")) } } // 취소되는 가져오기
        }
        assertEquals(10_000L, File(dest, "kr/a/01.wav").length()) // 첫 파일 완성
        assertFalse(File(dest, "kr/a/02.wav").exists()) // 두 번째 없음
        assertTrue(dest.walkTopDown().none { it.name.endsWith(".part") }) // 임시 파일 정리
    }

    /**
     * 목적: 내보내기 — 새 하위 폴더와 접미
     * 기능: run 폴더(run_manifest.json, kr/combo/customer/01.txt, _results/kr/combo/result.csv)를 내보내면 대상에 같은 이름 폴더가 생기고 파일 3개가 같은 상대 경로·내용으로 복사된다.
     *       한 번 더 내보내면 기존 폴더를 건드리지 않고 "<runId>_1" 에 복사된다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun exportCreatesUniqueSubfolder() = runBlocking {
        val run = File(tmp.newFolder("output_stt"), "20261011_035022") // run 폴더
        for (rel in listOf("run_manifest.json", "kr/combo/customer/01.txt", "_results/kr/combo/result.csv")) put(run, rel) // 파일들
        val target = tmp.newFolder("Download") // 사용자가 고른 트리
        val r = SafBridge.copyDirToTree(run, FileTreeSink(target), "") // 내보내기
        assertEquals(3, r.copied) // 파일 3개
        assertEquals("20261011_035022", r.target) // 만든 폴더 이름
        assertEquals("kr/combo/customer/01.txt", File(target, "20261011_035022/kr/combo/customer/01.txt").readText()) // 경로·내용 보존
        val r2 = SafBridge.copyDirToTree(run, FileTreeSink(target), "") // 두 번째 내보내기
        assertEquals("20261011_035022_1", r2.target) // 접미
        assertEquals(3, r2.copied) // 파일 3개
        assertTrue(File(target, "20261011_035022_1/_results/kr/combo/result.csv").isFile) // 새 폴더에 복사
        assertNull(File(target, "20261011_035022_2").listFiles()) // 세 번째 폴더는 없음
    }
}
