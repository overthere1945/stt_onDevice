// 파일명: ArchiveExtractorTest.kt
// 목적: ArchiveExtractor 의 안전 해제 규칙 검증
// 기능: zip-slip(..·절대경로·역슬래시·드라이브), 심볼릭/하드 링크, 중복 항목, stripPrefix(적용·누락 거부·"./" 허용), zip 해제를 확인한다.
package com.cowork.stt_ondevice.model // 모델 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // 거짓
import org.junit.Assert.assertTrue // 참
import org.junit.Assert.fail // 실패
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: ArchiveExtractor 회귀 방지
 * 기능: 악성 압축 항목을 거부하고 정상 항목을 올바른 위치에 푼다.
 */
class ArchiveExtractorTest {

    @get:Rule val tmp = TemporaryFolder() // 테스트마다 새 임시 폴더

    /** 목적: 해제 대상 폴더 만들기 / 기능: tmp/sandbox/out 을 만든다(형제 위치 sandbox 는 탈출 검사용) / 리턴: out 폴더 */
    private fun newDest(): File = File(tmp.root, "sandbox/out").also { it.mkdirs() } // 해제 폴더

    /** 목적: 예외 기대 / 기능: block 이 UnsafeArchiveEntryException 을 던지는지 확인 / 입력: block / 리턴: 던진 예외 */
    private fun expectUnsafe(block: () -> Unit): UnsafeArchiveEntryException {
        try {
            block() // 실행
        } catch (e: UnsafeArchiveEntryException) {
            return e // 기대한 예외
        }
        fail("UnsafeArchiveEntryException 이 던져져야 합니다") // 안 던짐
        throw IllegalStateException() // 도달 불가
    }

    /**
     * 목적: zip-slip(tar) 차단
     * 기능: "../evil.txt" 항목을 가진 tar.bz2 를 풀면 예외가 나고 dest 밖에 파일이 생기지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun zipSlipRejected() {
        val dest = newDest() // 해제 폴더
        val archive = makeTarBz2(File(tmp.root, "evil.tar.bz2")) { addFile("../evil.txt", "x".toByteArray()) } // 악성 tar
        val e = expectUnsafe { ArchiveExtractor.extract(archive, ArchiveType.TAR_BZ2, dest, "") } // 예외 확인
        assertEquals("../evil.txt", e.entry) // 문제 항목 이름
        assertFalse(File(dest.parentFile, "evil.txt").exists()) // 밖에 생기지 않음
        assertEquals(0, dest.listFiles()!!.size) // 안에도 아무것도 없음
    }

    /**
     * 목적: 위험한 zip 이름 거부
     * 기능: 절대경로, 역슬래시, 드라이브 문자, 중간 '..' 탈출 이름을 각각 거부한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun dangerousZipNamesRejected() {
        for (name in listOf("/abs.txt", "..\\evil.txt", "C:/x.txt", "a/../../evil.txt", "..", "a/..")) { // 위험 이름들(zip 라이브러리는 "/" 없는 이름의 역슬래시를 "/" 로 바꿔 읽음)
            val dest = newDest() // 해제 폴더
            val archive = makeZip(File(tmp.root, "z_${name.hashCode()}.zip")) { addFile(name, "x".toByteArray()) } // zip
            try {
                ArchiveExtractor.extract(archive, ArchiveType.ZIP, dest, "") // 해제 시도
                fail("$name: UnsafeArchiveEntryException 이 던져져야 합니다") // 거부 안 됨
            } catch (e: UnsafeArchiveEntryException) {
                // 기대한 거부
            }
            assertFalse("$name: 밖에 파일이 생김", File(dest.parentFile, "evil.txt").exists()) // 탈출 없음
            dest.deleteRecursively() // 다음 반복을 위해 정리
        }
    }

    /**
     * 목적: tar 의 역슬래시·드라이브 문자 이름 거부
     * 기능: 리눅스에서는 정상 문자인 역슬래시도 윈도 구분자 우회 가능성이 있어 거부하고, "C:" 로 시작하는 이름도 거부한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun tarBackslashAndDriveRejected() {
        for ((i, name) in listOf("a\\b.txt", "C:/x.txt").withIndex()) { // 위험 이름들
            val archive = makeTarBz2(File(tmp.root, "bs$i.tar.bz2")) { addFile(name, "x".toByteArray()) } // tar
            val dest = File(tmp.root, "sandbox/bs$i").also { it.mkdirs() } // 해제 폴더
            expectUnsafe { ArchiveExtractor.extract(archive, ArchiveType.TAR_BZ2, dest, "") } // 거부
        }
    }

    /**
     * 목적: 링크 항목 거부
     * 기능: tar 의 심볼릭 링크·하드 링크 항목이 있으면 예외가 난다(링크는 만들어지지 않는다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun symlinkEntryRejected() {
        val symlinkTar = makeTarBz2(File(tmp.root, "sym.tar.bz2")) { addSymlink("pkg/link", "/etc/passwd") } // 심볼릭 링크 tar
        val dest1 = newDest() // 해제 폴더
        expectUnsafe { ArchiveExtractor.extract(symlinkTar, ArchiveType.TAR_BZ2, dest1, "") } // 거부
        assertFalse(File(dest1, "pkg/link").exists()) // 링크 없음
        val hardTar = makeTarBz2(File(tmp.root, "hard.tar.bz2")) { addHardlink("pkg/hard", "pkg/a") } // 하드 링크 tar
        val dest2 = File(tmp.root, "sandbox/out2").also { it.mkdirs() } // 다른 해제 폴더
        expectUnsafe { ArchiveExtractor.extract(hardTar, ArchiveType.TAR_BZ2, dest2, "") } // 거부
    }

    /**
     * 목적: zip 의 심볼릭 링크 거부
     * 기능: unix 모드가 symlink(0120777)인 zip 항목을 거부한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun zipSymlinkRejected() {
        val archive = makeZip(File(tmp.root, "sym.zip")) { addFile("link", "/etc/passwd".toByteArray(), unixMode = 0b1010_000_111_111_111) } // 0120777
        expectUnsafe { ArchiveExtractor.extract(archive, ArchiveType.ZIP, newDest(), "") } // 거부
    }

    /**
     * 목적: 같은 해제 중 중복 항목 거부
     * 기능: 같은 경로를 두 번 쓰려 하면 덮어쓰지 않고 예외가 난다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun duplicateEntryRejectedAndNotOverwritten() {
        val dest = newDest() // 해제 폴더
        val archive = makeTarBz2(File(tmp.root, "dup.tar.bz2")) { // 중복 tar
            addFile("a.txt", "first".toByteArray()) // 첫 번째
            addFile("a.txt", "second".toByteArray()) // 같은 이름
        }
        expectUnsafe { ArchiveExtractor.extract(archive, ArchiveType.TAR_BZ2, dest, "") } // 거부
        assertEquals("first", File(dest, "a.txt").readText()) // 첫 내용 유지
    }

    /**
     * 목적: stripPrefix 적용
     * 기능: "pkg-1/" 를 떼어 내용이 dest 바로 아래에 풀리고 pkg-1 폴더는 만들어지지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun stripPrefixApplied() {
        val dest = newDest() // 해제 폴더
        val archive = makeTarBz2(File(tmp.root, "p.tar.bz2")) { // 접두 폴더가 있는 tar
            addDir("pkg-1/") // 접두 폴더 항목
            addFile("pkg-1/a.txt", "A".toByteArray()) // 파일
            addFile("pkg-1/sub/b.txt", "B".toByteArray()) // 하위 파일
        }
        ArchiveExtractor.extract(archive, ArchiveType.TAR_BZ2, dest, "pkg-1/") // 해제
        assertEquals("A", File(dest, "a.txt").readText()) // 접두 제거됨
        assertEquals("B", File(dest, "sub/b.txt").readText()) // 하위 경로 유지
        assertFalse(File(dest, "pkg-1").exists()) // 접두 폴더 없음
    }

    /**
     * 목적: 접두가 다른 항목 거부
     * 기능: stripPrefix 가 지정됐는데 그 접두로 시작하지 않는 항목이 있으면 예외가 난다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun entryWithoutPrefixRejected() {
        val archive = makeTarBz2(File(tmp.root, "np.tar.bz2")) { // 접두 불일치 tar
            addFile("pkg-1/a.txt", "A".toByteArray()) // 정상
            addFile("other/b.txt", "B".toByteArray()) // 접두 다름
        }
        val e = expectUnsafe { ArchiveExtractor.extract(archive, ArchiveType.TAR_BZ2, newDest(), "pkg-1/") } // 거부
        assertEquals("other/b.txt", e.entry) // 문제 항목
    }

    /**
     * 목적: 선행 "./" 허용
     * 기능: 항목 이름이 "./pkg-1/a.txt" 처럼 "./" 로 시작해도 정규화해 정상 해제한다("./" 자체 항목 포함).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun dotSlashPrefixAccepted() {
        val dest = newDest() // 해제 폴더
        val archive = makeTarBz2(File(tmp.root, "dot.tar.bz2")) { // "./" 접두 tar
            addDir("./") // 루트 항목
            addDir("./pkg-1/") // 접두 폴더
            addFile("./pkg-1/a.txt", "A".toByteArray()) // 파일
            addFile("./pkg-1/sub/b.txt", "B".toByteArray()) // 하위 파일
        }
        ArchiveExtractor.extract(archive, ArchiveType.TAR_BZ2, dest, "pkg-1/") // 해제
        assertEquals("A", File(dest, "a.txt").readText()) // 정상
        assertEquals("B", File(dest, "sub/b.txt").readText()) // 정상
    }

    /**
     * 목적: zip 정상 해제
     * 기능: stripPrefix 없는 zip 을 풀고 폴더 항목·중첩 파일이 만들어진다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun zipExtracted() {
        val dest = newDest() // 해제 폴더
        val archive = makeZip(File(tmp.root, "ok.zip")) { // 정상 zip
            addFile("model/", ByteArray(0)) // 폴더 항목
            addFile("model/am/x.bin", byteArrayOf(1, 2, 3), unixMode = 0b1000_000_110_100_100) // 일반 파일(0100644)
        }
        ArchiveExtractor.extract(archive, ArchiveType.ZIP, dest, "model/") // 해제
        assertTrue(File(dest, "am/x.bin").isFile) // 파일 생김
        assertEquals(3L, File(dest, "am/x.bin").length()) // 크기 일치
    }

    /**
     * 목적: 압축 없음 형식 거부
     * 기능: ArchiveType.NONE 으로 extract 를 부르면 호출 오류(IllegalArgumentException)다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test(expected = IllegalArgumentException::class)
    fun noneTypeRejected() {
        ArchiveExtractor.extract(File(tmp.root, "x"), ArchiveType.NONE, newDest(), "") // 잘못된 호출
    }
}
