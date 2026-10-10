// 파일명: ArchiveExtractor.kt
// 목적: 모델 압축(tar.bz2, zip)을 안전하게 해제
// 기능: 항목 이름을 검사해 절대경로·'..'·역슬래시·드라이브 문자를 거부하고, 일반 파일·폴더 외 항목(심볼릭/하드 링크, 장치 등)을 거부하며,
//       최종 경로가 destDir 아래인지 다시 확인하고, 한 번의 해제 안에서 기존 파일을 덮어쓰지 않는다. stripPrefix 상위 폴더를 떼어 낸다.
//       선행 "./" 는 정규화해 허용한다(tools/hash_models.py 의 PC 쪽 규칙과 같되 "./" 허용은 앱 쪽 결정).
package com.cowork.stt_ondevice.model // 모델 패키지

import org.apache.commons.compress.archivers.tar.TarArchiveEntry // tar 항목
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream // tar 입력
import org.apache.commons.compress.archivers.tar.TarConstants // tar 상수
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry // zip 항목
import org.apache.commons.compress.archivers.zip.ZipFile // zip 파일
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream // bzip2 해제
import java.io.File // 파일
import java.io.IOException // 입출력 예외
import java.io.InputStream // 입력 스트림
import java.nio.file.FileAlreadyExistsException // 이미 존재 예외
import java.nio.file.Files // 파일 도구
import java.nio.file.StandardOpenOption // 열기 옵션

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 위험한 압축 항목 예외
 * 기능: 어떤 항목이 왜 거부됐는지 담는다.
 * 입력: entry(문제 항목의 원래 이름), reason(거부 사유, 한국어)
 */
class UnsafeArchiveEntryException(val entry: String, val reason: String = "") :
    IOException("압축 안 위험한 항목 거부: '$entry'" + if (reason.isEmpty()) "" else " ($reason)") // 메시지

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 모델 압축 해제 도구
 * 기능: tar.bz2 / zip 을 destDir 에 풀되 위 규칙을 지킨다.
 */
object ArchiveExtractor {
    private const val BUFFER_SIZE = 64 * 1024 // 복사 버퍼 크기(64KB)
    private const val ZIP_S_IFMT = 0xF000 // zip unix 모드의 파일 형식 마스크
    private const val ZIP_S_IFREG = 0x8000 // 일반 파일
    private const val ZIP_S_IFDIR = 0x4000 // 폴더

    /**
     * 목적: 압축 해제
     * 기능: archive 의 모든 항목을 검사하며 destDir 아래에 푼다. 하나라도 위험하면 즉시 중단한다(이미 푼 파일은 호출자가 destDir 째 지운다).
     * 입력: archive(압축 파일), type(TAR_BZ2/ZIP), destDir(해제 폴더, 없으면 만든다), stripPrefix(떼어낼 상위 폴더 이름 1단계, 예 "pkg-1/", 없으면 ""),
     *       checkCancel(항목·버퍼마다 부르는 취소 확인 훅, 취소면 예외를 던진다)
     * 출력: 위험 항목이면 UnsafeArchiveEntryException, type 이 NONE 이면 IllegalArgumentException, 읽기·쓰기 오류는 IOException
     * 리턴: 없음
     */
    fun extract(archive: File, type: ArchiveType, destDir: File, stripPrefix: String, checkCancel: () -> Unit = {}) {
        destDir.mkdirs() // 해제 폴더 보장
        val root = destDir.canonicalFile // 실제 경로(심볼릭 해소)로 탈출 검사 기준
        val prefix = stripPrefix.split("/").filter { it.isNotEmpty() && it != "." } // 떼어낼 접두 구성요소
        when (type) { // 형식별 해제
            ArchiveType.TAR_BZ2 -> extractTarBz2(archive, root, prefix, checkCancel) // tar.bz2
            ArchiveType.ZIP -> extractZip(archive, root, prefix, checkCancel) // zip
            ArchiveType.NONE -> throw IllegalArgumentException("압축 없음(NONE)은 해제 대상이 아닙니다") // 호출 오류
        }
    }

    /**
     * 목적: 항목 이름 정규화·검사
     * 기능: 절대경로·역슬래시·드라이브 문자·NUL 을 거부하고 '.' 와 빈 구성요소를 제거하며 '..' 가 있으면 거부한다.
     *       정규화 결과가 비어 있으면(예: "./") 빈 리스트를 돌려준다(폴더 항목에서만 허용됨).
     * 입력: name(압축 안 항목 이름)
     * 출력: 위험하면 UnsafeArchiveEntryException
     * 리턴: 정규화된 경로 구성요소 리스트
     */
    fun safeParts(name: String): List<String> {
        if (name.startsWith("/")) throw UnsafeArchiveEntryException(name, "절대경로") // 절대경로
        if (name.contains('\\')) throw UnsafeArchiveEntryException(name, "역슬래시") // 윈도 구분자
        if (name.contains('\u0000')) throw UnsafeArchiveEntryException(name, "NUL 문자") // NUL
        if (name.length > 1 && name[1] == ':') throw UnsafeArchiveEntryException(name, "드라이브 문자") // 드라이브
        val parts = name.split("/").filter { it.isNotEmpty() && it != "." } // '.' 와 빈 칸 제거
        if (parts.any { it == ".." }) throw UnsafeArchiveEntryException(name, "상위 경로(..)") // 상위 탈출
        return parts // 정규화 결과
    }

    /**
     * 목적: 접두 제거 후 대상 상대 경로 결정
     * 기능: parts 가 prefix 로 시작해야 하며 그 뒤 나머지를 돌려준다. 접두 폴더 자체(또는 그 상위) 폴더 항목은 null(건너뜀)이다.
     * 입력: name(원래 이름, 예외 메시지용), parts(정규화된 구성요소), isDir(폴더 항목 여부), prefix(접두 구성요소)
     * 출력: 접두가 다르거나 파일이 접두 이름과 같으면 UnsafeArchiveEntryException
     * 리턴: 접두 뒤 나머지 구성요소(비어 있지 않음) 또는 null(건너뜀)
     */
    private fun relativeParts(name: String, parts: List<String>, isDir: Boolean, prefix: List<String>): List<String>? {
        if (prefix.isEmpty()) { // 접두 없음
            return if (parts.isEmpty()) { // 이름이 비었음
                if (isDir) null else throw UnsafeArchiveEntryException(name, "빈 이름") // 폴더면 건너뜀
            } else {
                parts // 그대로
            }
        }
        if (isDir && parts.size <= prefix.size && parts == prefix.take(parts.size)) return null // 접두 폴더 자체/상위 폴더 항목
        if (parts.size <= prefix.size || parts.take(prefix.size) != prefix) { // 접두로 시작하지 않음
            throw UnsafeArchiveEntryException(name, "stripPrefix '${prefix.joinToString("/")}/' 로 시작하지 않음") // 거부
        }
        return parts.drop(prefix.size) // 접두 뒤 나머지
    }

    /**
     * 목적: 최종 대상 경로 계산·탈출 재검사
     * 기능: root 아래 상대 구성요소를 붙이고 실제 경로(canonical)가 여전히 root 아래인지 확인한다.
     * 입력: name(원래 이름), root(해제 폴더 실제 경로), rel(상대 구성요소)
     * 출력: root 밖이면 UnsafeArchiveEntryException
     * 리턴: 대상 File
     */
    private fun resolveTarget(name: String, root: File, rel: List<String>): File {
        val target = File(root, rel.joinToString("/")).canonicalFile // 실제 경로
        if (!target.path.startsWith(root.path + File.separator)) throw UnsafeArchiveEntryException(name, "해제 폴더 밖") // 탈출
        return target // 안전한 대상
    }

    /**
     * 목적: 폴더 항목 처리
     * 기능: 대상 폴더를 만든다(같은 이름의 파일이 이미 있으면 거부).
     * 입력: name(원래 이름), root, rel(상대 구성요소)
     * 출력: 위험하면 UnsafeArchiveEntryException
     * 리턴: 없음
     */
    private fun makeDir(name: String, root: File, rel: List<String>) {
        val dir = resolveTarget(name, root, rel) // 대상
        if (!dir.isDirectory && !dir.mkdirs()) throw UnsafeArchiveEntryException(name, "폴더를 만들 수 없음(같은 이름의 파일 존재)") // 생성
    }

    /**
     * 목적: 파일 항목 쓰기
     * 기능: 대상이 이미 있으면 거부하고, 없을 때만(CREATE_NEW) 만들어 내용을 복사한다. 버퍼마다 취소를 확인한다.
     * 입력: name(원래 이름), root, rel(상대 구성요소), src(내용 스트림), checkCancel(취소 훅)
     * 출력: 중복·경로 충돌이면 UnsafeArchiveEntryException, 쓰기 오류는 IOException
     * 리턴: 없음
     */
    private fun writeFile(name: String, root: File, rel: List<String>, src: InputStream, checkCancel: () -> Unit) {
        val target = resolveTarget(name, root, rel) // 대상
        if (target.exists()) throw UnsafeArchiveEntryException(name, "같은 경로가 이미 있음(덮어쓰기 금지)") // 중복
        val parent = target.parentFile!! // 부모 폴더
        if (!parent.isDirectory && !parent.mkdirs()) throw UnsafeArchiveEntryException(name, "부모 폴더를 만들 수 없음(같은 이름의 파일 존재)") // 부모 생성
        val out = try {
            Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE) // 새 파일만
        } catch (e: FileAlreadyExistsException) {
            throw UnsafeArchiveEntryException(name, "같은 경로가 이미 있음(덮어쓰기 금지)") // 경쟁 중복
        }
        out.use { o -> // 자동 닫기
            val buf = ByteArray(BUFFER_SIZE) // 복사 버퍼
            while (true) { // 끝까지
                checkCancel() // 취소 확인
                val n = src.read(buf) // 읽기
                if (n < 0) break // 끝
                o.write(buf, 0, n) // 쓰기
            }
        }
    }

    /**
     * 목적: tar.bz2 해제
     * 기능: 항목마다 종류(폴더/일반 파일 외 거부)·이름을 검사하고 푼다.
     * 입력: archive, root(해제 폴더 실제 경로), prefix(접두 구성요소), checkCancel
     * 출력: 위험 항목이면 UnsafeArchiveEntryException
     * 리턴: 없음
     */
    private fun extractTarBz2(archive: File, root: File, prefix: List<String>, checkCancel: () -> Unit) {
        archive.inputStream().buffered().use { raw -> // 파일 열기
            BZip2CompressorInputStream(raw).use { bz -> // bzip2 해제
                TarArchiveInputStream(bz).use { tar -> // tar 읽기
                    while (true) { // 항목 반복
                        checkCancel() // 취소 확인
                        val entry: TarArchiveEntry = tar.nextEntry ?: break // 다음 항목, 없으면 끝
                        val name = entry.name // 원래 이름
                        val flag = entry.linkFlag // 항목 종류 바이트
                        val isRegular = flag == TarConstants.LF_NORMAL || flag == TarConstants.LF_OLDNORM // 일반 파일 종류
                        val isDir = flag == TarConstants.LF_DIR || (isRegular && name.endsWith("/")) // 폴더 종류
                        if (!isRegular && !isDir) throw UnsafeArchiveEntryException(name, "일반 파일/폴더가 아님(링크·장치 등, type=$flag)") // 거부
                        if (entry.isSparse) throw UnsafeArchiveEntryException(name, "sparse 파일 미지원") // sparse 거부
                        val parts = safeParts(name) // 이름 검사
                        val rel = relativeParts(name, parts, isDir, prefix) ?: continue // 접두 처리(건너뜀이면 다음)
                        if (isDir) makeDir(name, root, rel) else writeFile(name, root, rel, tar, checkCancel) // 폴더/파일
                    }
                }
            }
        }
    }

    /**
     * 목적: zip 해제
     * 기능: 항목마다 unix 모드(심볼릭 링크 등 거부)·이름을 검사하고 푼다.
     * 입력: archive, root(해제 폴더 실제 경로), prefix(접두 구성요소), checkCancel
     * 출력: 위험 항목이면 UnsafeArchiveEntryException
     * 리턴: 없음
     */
    private fun extractZip(archive: File, root: File, prefix: List<String>, checkCancel: () -> Unit) {
        ZipFile.builder().setFile(archive).get().use { zf -> // zip 열기
            for (entry: ZipArchiveEntry in zf.entries) { // 항목 반복
                checkCancel() // 취소 확인
                val name = entry.name // 원래 이름
                val fmt = entry.unixMode and ZIP_S_IFMT // 유닉스 파일 형식(미표기면 0)
                if (entry.isUnixSymlink || (fmt != 0 && fmt != ZIP_S_IFREG && fmt != ZIP_S_IFDIR)) { // 링크·장치 등
                    throw UnsafeArchiveEntryException(name, "일반 파일/폴더가 아님(unix 모드 0x${fmt.toString(16)})") // 거부
                }
                val isDir = entry.isDirectory // 폴더 여부
                val parts = safeParts(name) // 이름 검사
                val rel = relativeParts(name, parts, isDir, prefix) ?: continue // 접두 처리
                if (isDir) {
                    makeDir(name, root, rel) // 폴더
                } else {
                    zf.getInputStream(entry).use { src -> writeFile(name, root, rel, src, checkCancel) } // 파일
                }
            }
        }
    }
}
