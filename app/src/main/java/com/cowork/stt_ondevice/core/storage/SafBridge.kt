// 파일명: core/storage/SafBridge.kt
// 목적: SAF(Storage Access Framework)로 사용자가 고른 폴더·파일과 앱 폴더 사이의 복사(음원 가져오기, 결과 내보내기, 모델 가져오기)
// 기능: - importTree: 고른 트리의 .wav 를 하위 폴더 구조(상대 경로) 그대로 앱 폴더(input_sound 등)로 복사한다. 이미 있는 파일은 덮어쓰지 않고 건너뛴다.
//         데이터셋 루트(최상위에 kr/ en/)를 고르면 그 언어 폴더만, 언어 폴더 자체(kr 또는 en)를 고르면 그 언어 폴더로,
//         그 밖이면 화면이 언어를 물어 input_sound/<lang>/<트리 이름>/ 아래로 복사한다(detectLayout·inputDest).
//       - exportDir: 앱의 run 폴더를 고른 트리 아래 새 하위 폴더(<runId>, 이미 있으면 <runId>_1, _2 …)로 통째 복사한다(기존 것은 덮어쓰지 않음).
//       - importModel: 모델 파일(압축 1개) 또는 폴더 트리를 앱 폴더(import/<모델id>/saf_…)로 복사한다(이후 ModelsViewModel 이 설치기에 넘김).
//       - 모든 IO 는 Dispatchers.IO 에서 하고, 파일 사이·64KB 마다 취소를 확인한다(취소 시 쓰던 파일은 지움). 결과는 복사·건너뜀 개수와 바이트 수.
//       - SAF 접근은 TreeSource(읽기)/TreeSink(쓰기) 인터페이스 뒤에 두어, 복사·경로 논리를 JVM 테스트에서 일반 폴더 가짜로 검증한다.
//       - 위험한 이름("", ".", "..", '/'·'\\'·NUL 포함)은 앱 폴더 밖으로 나가지 않도록 건너뛴다. 오류 메시지는 한국어.
// change(add)-hyungchul-2026-10-11-03:59
package com.cowork.stt_ondevice.core.storage // 저장소 패키지

import android.content.ContentResolver // 콘텐츠 접근
import android.content.Context // 컨텍스트
import android.net.Uri // 문서 URI
import android.provider.DocumentsContract // SAF 문서 API
import android.provider.OpenableColumns // 단일 문서 이름 열
import kotlinx.coroutines.CancellationException // 취소 예외
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.currentCoroutineContext // 현재 코루틴 컨텍스트
import kotlinx.coroutines.ensureActive // 취소 확인
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.io.FileNotFoundException // 파일 없음
import java.io.IOException // 입출력 오류
import java.io.InputStream // 입력 스트림
import java.io.OutputStream // 출력 스트림

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 복사 결과
 * 입력: copied(복사한 파일 수), skipped(건너뛴 수 — 이미 있음·위험한 이름), bytes(복사한 바이트), target(대상 — 내보내기는 만든 폴더 이름, 가져오기는 대상 경로)
 */
data class SafCopyResult(val copied: Int, val skipped: Int, val bytes: Long, val target: String)

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 고른 트리의 간단한 정보
 * 입력: name(트리 이름), topDirs(최상위 하위 폴더 이름들)
 */
data class SafTreeInfo(val name: String, val topDirs: List<String>)

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 음원 트리의 구조 판정 결과
 * 기능: DatasetRoot(최상위에 언어 폴더가 있음), LangFolder(트리 자체가 언어 폴더), Unknown(언어를 사용자에게 물어야 함).
 */
sealed interface InputTreeLayout {
    /** 목적: 데이터셋 루트 / 입력: langs(최상위에 있는 언어 폴더 이름들) */
    data class DatasetRoot(val langs: Set<String>) : InputTreeLayout

    /** 목적: 트리 자체가 언어 폴더 / 입력: lang(kr 또는 en) */
    data class LangFolder(val lang: String) : InputTreeLayout

    /** 목적: 언어 폴더를 알 수 없음(화면이 언어를 물음) */
    data object Unknown : InputTreeLayout
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 읽기 트리의 항목 1개
 * 입력: id(문서 id), name(표시 이름), isDir(폴더 여부)
 */
internal data class TreeEntry(val id: String, val name: String, val isDir: Boolean)

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 읽기 트리 추상화(SAF 또는 테스트 가짜)
 * 기능: 폴더의 자식 목록과 파일 열기.
 */
internal interface TreeSource {
    /** 목적: 자식 목록 / 입력: dirId(폴더 문서 id) / 출력: 실패 시 IOException / 리턴: 항목 목록 */
    fun children(dirId: String): List<TreeEntry>

    /** 목적: 파일 열기 / 입력: id(파일 문서 id) / 출력: 실패 시 IOException / 리턴: 입력 스트림(호출자가 닫음) */
    fun open(id: String): InputStream
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 쓰기 트리 추상화(SAF 또는 테스트 가짜)
 * 기능: 자식 이름 조회, 폴더 만들기, 파일 만들기.
 */
internal interface TreeSink {
    /** 목적: 자식 이름들 / 입력: dirId / 출력: 실패 시 IOException / 리턴: 이름 집합 */
    fun childNames(dirId: String): Set<String>

    /** 목적: 하위 폴더 만들기 / 입력: parentId, name / 출력: 실패 시 IOException / 리턴: 새 폴더 문서 id */
    fun createDir(parentId: String, name: String): String

    /** 목적: 새 파일 만들기 / 입력: parentId, name / 출력: 실패 시 IOException / 리턴: 출력 스트림(호출자가 닫음) */
    fun createFile(parentId: String, name: String): OutputStream
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: SAF 입출력 다리
 * 기능: 파일 머리말 참조. 화면은 SAF 선택기(OpenDocumentTree / OpenDocument)로 받은 Uri 를 넘긴다(일회성 권한, 영구 권한은 받지 않음).
 * 입력: context(앱 컨텍스트 — ContentResolver 사용)
 */
class SafBridge(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver // 콘텐츠 접근자

    /**
     * 목적: 고른 트리의 이름과 최상위 폴더 이름 읽기
     * 기능: 트리 루트 문서의 표시 이름과 그 바로 아래 폴더 이름들을 io 에서 읽는다(언어 폴더 판정용).
     * 입력: treeUri(OpenDocumentTree 결과)
     * 출력: 읽기 실패 시 IOException(한국어 — change(add)-hyungchul-2026-10-11-04:39 SecurityException·IllegalArgumentException 등도 safeIo 로 변환해 크래시 방지)
     * 리턴: SafTreeInfo
     */
    suspend fun inspectTree(treeUri: Uri): SafTreeInfo = withContext(Dispatchers.IO) {
        safeIo("고른 폴더를 읽지 못했습니다") { // change(add)-hyungchul-2026-10-11-04:39 예외를 한국어 IOException 으로
            val src = SafTreeSource(resolver, treeUri) // SAF 읽기 트리
            val rootId = DocumentsContract.getTreeDocumentId(treeUri) // 루트 문서 id
            SafTreeInfo(src.nameOf(rootId), src.children(rootId).filter { it.isDir }.map { it.name }) // 이름 + 최상위 폴더
        }
    }

    /**
     * 목적: 음원 트리 가져오기
     * 기능: 트리의 .wav(대소문자 무시)를 상대 경로 그대로 dest 아래로 복사한다. topDirs 가 있으면 그 이름의 최상위 폴더만(최상위 파일 제외).
     *       이미 있는 파일은 건너뛴다(덮어쓰지 않음).
     * 입력: treeUri(고른 트리), dest(대상 폴더 — 보통 input_sound 또는 input_sound/<lang>/…), topDirs(최상위 폴더 제한 또는 null)
     * 출력: 실패 시 IOException(한국어), 취소 시 CancellationException
     * 리턴: SafCopyResult(copied = 복사한 wav 수)
     */
    suspend fun importTree(treeUri: Uri, dest: File, topDirs: Set<String>? = null): SafCopyResult =
        copyTreeToDir(SafTreeSource(resolver, treeUri), DocumentsContract.getTreeDocumentId(treeUri), dest, wavOnly = true, topDirs = topDirs) // 공통 복사

    /**
     * 목적: run 폴더 내보내기
     * 기능: 고른 트리 바로 아래에 src 이름(이미 있으면 접미)의 새 폴더를 만들고 src 를 통째로 복사한다.
     * 입력: src(output_stt/<runId>), treeUri(고른 트리)
     * 출력: 실패 시 IOException(한국어), 취소 시 CancellationException
     * 리턴: SafCopyResult(target = 만든 폴더 이름)
     */
    suspend fun exportDir(src: File, treeUri: Uri): SafCopyResult {
        if (!src.isDirectory) throw FileNotFoundException("내보낼 폴더가 없습니다: ${src.path}") // 원본 확인
        return copyDirToTree(src, SafTreeSink(resolver, treeUri), DocumentsContract.getTreeDocumentId(treeUri)) // 공통 복사
    }

    /**
     * 목적: 모델 가져오기(SAF)
     * 기능: isTree 면 트리의 모든 파일을 상대 경로 그대로 dest 로, 아니면 단일 문서를 dest/<표시 이름> 으로 복사한다(이미 있으면 건너뜀).
     * 입력: uri(OpenDocumentTree 또는 OpenDocument 결과), isTree(트리 여부), dest(대상 폴더 — import/<id>/saf_…)
     * 출력: 실패 시 IOException(한국어), 취소 시 CancellationException
     * 리턴: SafCopyResult
     */
    suspend fun importModel(uri: Uri, isTree: Boolean, dest: File): SafCopyResult {
        if (isTree) return copyTreeToDir(SafTreeSource(resolver, uri), DocumentsContract.getTreeDocumentId(uri), dest, wavOnly = false, topDirs = null) // 폴더째
        return withContext(Dispatchers.IO) { // 단일 파일
            val name = displayName(uri) // 표시 이름
            if (!isSafeName(name)) throw IOException("파일 이름을 쓸 수 없습니다: $name") // 위험한 이름
            if (!dest.isDirectory && !dest.mkdirs()) throw IOException("폴더를 만들 수 없습니다: ${dest.path}") // 대상 폴더
            val out = File(dest, name) // 대상 파일
            if (out.exists()) return@withContext SafCopyResult(0, 1, 0, out.path) // 이미 있음
            val bytes = copyToFile({ resolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없습니다: $name") }, out) // 복사
            SafCopyResult(1, 0, bytes, out.path) // 결과
        }
    }

    /**
     * 목적: 단일 문서 표시 이름
     * 기능: OpenableColumns.DISPLAY_NAME 을 읽는다(없으면 Uri 마지막 경로 조각).
     * 입력: uri(문서)
     * 출력: 읽기 실패 시 IOException
     * 리턴: 이름
     */
    private fun displayName(uri: Uri): String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> // 질의
            if (c.moveToFirst()) c.getString(0) else null // 첫 행 이름
        } ?: (uri.lastPathSegment ?: "model") // 없으면 경로 조각
    } catch (e: SecurityException) {
        throw IOException("파일 접근 권한이 없습니다: ${e.message}") // 권한 오류
    }

    companion object {
        private const val BUFFER = 64 * 1024 // 복사 버퍼(64KB, 이 단위로 취소 확인)
        private const val FALLBACK_TREE_NAME = "saf_import" // 위험한 트리 이름 대신 쓸 폴더 이름

        /**
         * 목적: 음원 트리 구조 판정
         * 기능: 최상위 폴더에 언어 폴더(kr/en, 대소문자 구분)가 있으면 DatasetRoot(그 언어들), 트리 이름 자체가 언어 폴더면 LangFolder, 아니면 Unknown.
         * 입력: treeName(트리 이름), topDirs(최상위 폴더 이름들)
         * 출력: 없음
         * 리턴: InputTreeLayout
         */
        fun detectLayout(treeName: String, topDirs: Collection<String>): InputTreeLayout {
            val langs = topDirs.filter { it in InputScanner.LANG_FOLDERS }.toSet() // 최상위 언어 폴더
            return when {
                langs.isNotEmpty() -> InputTreeLayout.DatasetRoot(langs) // 데이터셋 루트
                treeName in InputScanner.LANG_FOLDERS -> InputTreeLayout.LangFolder(treeName) // 언어 폴더 자체
                else -> InputTreeLayout.Unknown // 알 수 없음
            }
        }

        /**
         * 목적: 가져오기 대상 폴더 계산
         * 기능: DatasetRoot → (inputRoot, 언어 폴더 제한), LangFolder → (inputRoot/<lang>, null),
         *       Unknown → (inputRoot/<chosenLang>/<트리 이름>, null) — 트리 이름이 위험하면 "saf_import". Unknown 에서 언어가 없거나 kr/en 이 아니면 IllegalArgumentException.
         * 입력: inputRoot(input_sound), layout(판정 결과), chosenLang(사용자가 고른 언어 또는 null), treeName(트리 이름)
         * 출력: 잘못된 언어면 IllegalArgumentException
         * 리턴: (대상 폴더, 최상위 폴더 제한 또는 null)
         */
        fun inputDest(inputRoot: File, layout: InputTreeLayout, chosenLang: String?, treeName: String): Pair<File, Set<String>?> = when (layout) {
            is InputTreeLayout.DatasetRoot -> inputRoot to layout.langs // 언어 폴더만
            is InputTreeLayout.LangFolder -> File(inputRoot, layout.lang) to null // 그 언어 폴더로
            InputTreeLayout.Unknown -> {
                require(chosenLang != null && chosenLang in InputScanner.LANG_FOLDERS) { "가져올 언어 폴더(kr/en)를 골라야 합니다" } // 언어 필수
                File(File(inputRoot, chosenLang), if (isSafeName(treeName)) treeName else FALLBACK_TREE_NAME) to null // 언어/<트리 이름>
            }
        }

        /**
         * 목적: 겹치지 않는 이름
         * 기능: base 가 없으면 그대로, 있으면 base_1, base_2 … 중 처음 비는 것.
         * 입력: base(원하는 이름), existing(이미 있는 이름들)
         * 출력: 없음
         * 리턴: 이름
         */
        fun uniqueName(base: String, existing: Set<String>): String {
            if (base !in existing) return base // 그대로
            var i = 1 // 접미 번호
            while ("${base}_$i" in existing) i++ // 비는 번호 찾기
            return "${base}_$i" // 접미 이름
        }

        /**
         * 목적: 안전한 파일·폴더 이름인지
         * 기능: 비었거나 "."·".." 이거나 '/'·'\\'·NUL 을 포함하면 false(앱 폴더 밖으로 나가는 경로 방지).
         * 입력: name(이름)
         * 출력: 없음
         * 리턴: 안전하면 true
         */
        fun isSafeName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' } // 검사

        /**
         * 목적: 읽기 트리 → 앱 폴더 복사(공통)
         * 기능: rootId 아래를 깊이 우선으로 돌며 파일을 dest/<상대 경로> 로 복사한다. wavOnly 면 .wav(대소문자 무시)만,
         *       topDirs 가 있으면 최상위에서는 그 이름의 폴더만 들어간다. 위험한 이름·이미 있는 파일은 건너뜀(skipped). 파일마다 취소 확인.
         * 입력: src(읽기 트리), rootId(루트 문서 id), dest(대상 폴더), wavOnly(wav 만), topDirs(최상위 폴더 제한 또는 null)
         * 출력: 실패 시 IOException(한국어), 취소 시 CancellationException
         * 리턴: SafCopyResult(target = dest 경로)
         */
        internal suspend fun copyTreeToDir(src: TreeSource, rootId: String, dest: File, wavOnly: Boolean, topDirs: Set<String>?): SafCopyResult = withContext(Dispatchers.IO) {
            var copied = 0 // 복사 수
            var skipped = 0 // 건너뜀 수
            var bytes = 0L // 바이트 수

            /** 목적: 폴더 1개 처리(재귀) / 입력: dirId(문서 id), out(대응하는 앱 폴더), top(최상위 여부) / 리턴: 없음 */
            suspend fun walk(dirId: String, out: File, top: Boolean) {
                for (e in safeIo("폴더 목록을 읽지 못했습니다") { src.children(dirId) }) { // 자식마다
                    currentCoroutineContext().ensureActive() // 취소 확인
                    if (!isSafeName(e.name)) { skipped++; continue } // 위험한 이름
                    if (e.isDir) { // 폴더
                        if (top && topDirs != null && e.name !in topDirs) continue // 제한 밖 최상위 폴더
                        walk(e.id, File(out, e.name), false) // 재귀
                        continue // 다음
                    }
                    if (top && topDirs != null) continue // 언어 폴더 밖 최상위 파일
                    if (wavOnly && !e.name.endsWith(".wav", ignoreCase = true)) continue // wav 아님
                    val target = File(out, e.name) // 대상 파일
                    if (target.exists()) { skipped++; continue } // 덮어쓰지 않음
                    if (!out.isDirectory && !out.mkdirs()) throw IOException("폴더를 만들 수 없습니다: ${out.path}") // 대상 폴더
                    bytes += copyToFile({ safeIo("파일을 열 수 없습니다: ${e.name}") { src.open(e.id) } }, target) // 복사
                    copied++ // 집계
                }
            }

            walk(rootId, dest, true) // 루트부터
            SafCopyResult(copied, skipped, bytes, dest.path) // 결과
        }

        /**
         * 목적: 앱 폴더 → 쓰기 트리 복사(공통)
         * 기능: parentId 아래에 uniqueName(src 이름) 폴더를 새로 만들고 src 의 하위 폴더·파일을 이름순으로 그대로 만든다. 파일마다 취소 확인.
         * 입력: src(앱 폴더), sink(쓰기 트리), parentId(대상 트리 문서 id)
         * 출력: 실패 시 IOException(한국어), 취소 시 CancellationException
         * 리턴: SafCopyResult(target = 만든 폴더 이름)
         */
        internal suspend fun copyDirToTree(src: File, sink: TreeSink, parentId: String): SafCopyResult = withContext(Dispatchers.IO) {
            var copied = 0 // 복사 수
            var bytes = 0L // 바이트 수
            val name = uniqueName(src.name, safeIo("대상 폴더 목록을 읽지 못했습니다") { sink.childNames(parentId) }) // 겹치지 않는 이름
            val rootId = safeIo("대상에 폴더를 만들 수 없습니다: $name") { sink.createDir(parentId, name) } // 새 폴더

            /** 목적: 폴더 1개 복사(재귀) / 입력: dir(앱 폴더), dirId(대응 문서 id) / 리턴: 없음 */
            suspend fun walk(dir: File, dirId: String) {
                val children = dir.listFiles()?.sortedBy { it.name } ?: throw IOException("폴더를 읽을 수 없습니다: ${dir.path}") // 자식(이름순)
                for (f in children) { // 자식마다
                    currentCoroutineContext().ensureActive() // 취소 확인
                    if (f.isDirectory) { // 폴더
                        walk(f, safeIo("대상에 폴더를 만들 수 없습니다: ${f.name}") { sink.createDir(dirId, f.name) }) // 만들고 재귀
                    } else { // 파일
                        val out = safeIo("대상에 파일을 만들 수 없습니다: ${f.name}") { sink.createFile(dirId, f.name) } // 새 파일
                        bytes += out.use { o -> f.inputStream().use { i -> pump(i, o) } } // 복사
                        copied++ // 집계
                    }
                }
            }

            walk(src, rootId) // 루트부터
            SafCopyResult(copied, 0, bytes, name) // 결과
        }

        /**
         * 목적: 스트림 → 파일 복사(취소 시 정리)
         * 기능: <대상>.part 에 쓰고 끝나면 이름을 바꾼다. 실패·취소 시 .part 를 지운다.
         * 입력: open(입력 스트림 열기), target(대상 파일 — 없어야 함)
         * 출력: 실패 시 IOException, 취소 시 CancellationException
         * 리턴: 복사한 바이트 수
         */
        private suspend fun copyToFile(open: () -> InputStream, target: File): Long {
            val part = File(target.path + ".part") // 임시 파일(스캐너는 .wav 만 봄)
            try {
                val n = open().use { i -> part.outputStream().use { o -> pump(i, o) } } // 복사
                if (!part.renameTo(target)) throw IOException("파일 이름을 바꾸지 못했습니다: ${target.path}") // 완성본으로
                return n // 바이트 수
            } finally {
                part.delete() // 남은 임시 파일 정리(성공 시에는 이미 없음)
            }
        }

        /**
         * 목적: 스트림 펌프(취소 확인 포함)
         * 기능: 64KB 단위로 읽어 쓰고, 단위마다 취소를 확인한다.
         * 입력: i(입력), o(출력)
         * 출력: 실패 시 IOException, 취소 시 CancellationException
         * 리턴: 바이트 수
         */
        private suspend fun pump(i: InputStream, o: OutputStream): Long {
            val buf = ByteArray(BUFFER) // 버퍼
            var total = 0L // 누적
            while (true) { // 끝까지
                currentCoroutineContext().ensureActive() // 취소 확인
                val n = i.read(buf) // 읽기
                if (n < 0) break // 끝
                o.write(buf, 0, n) // 쓰기
                total += n // 누적
            }
            return total // 바이트 수
        }

        /**
         * 목적: SAF 호출의 예외를 한국어 IOException 으로
         * 기능: SecurityException·IllegalArgumentException·IllegalStateException·IOException 을 "<what>: <원인>" IOException 으로 바꾼다.
         *       취소(CancellationException 은 IllegalStateException 의 하위 타입)는 바꾸지 않고 그대로 던진다.
         * 입력: what(한국어 설명), block(호출)
         * 출력: 변환한 IOException
         * 리턴: block 결과
         */
        private inline fun <T> safeIo(what: String, block: () -> T): T = try {
            block() // 호출
        } catch (e: CancellationException) {
            throw e // 취소는 그대로
        } catch (e: IOException) {
            throw IOException("$what (${e.message})", e) // 입출력 오류
        } catch (e: SecurityException) {
            throw IOException("$what (접근 권한 없음: ${e.message})", e) // 권한 오류
        } catch (e: IllegalArgumentException) {
            throw IOException("$what (${e.message})", e) // 잘못된 문서
        } catch (e: IllegalStateException) {
            throw IOException("$what (${e.message})", e) // 상태 오류(가짜 트리 포함)
        }
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: SAF 트리 읽기 구현
 * 기능: DocumentsContract 의 자식 문서 질의와 openInputStream 으로 TreeSource 를 구현한다.
 * 입력: resolver(콘텐츠 접근자), treeUri(트리 Uri)
 */
private class SafTreeSource(private val resolver: ContentResolver, private val treeUri: Uri) : TreeSource {

    /** 목적: 자식 목록 / 기능: id·이름·MIME(폴더 판정) 질의 / 입력: dirId / 리턴: 항목 목록 */
    override fun children(dirId: String): List<TreeEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId) // 자식 질의 Uri
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE) // 열
        val out = ArrayList<TreeEntry>() // 결과
        (resolver.query(uri, cols, null, null, null) ?: throw IOException("목록 질의 결과가 없습니다")).use { c -> // 질의
            while (c.moveToNext()) out += TreeEntry(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) // 항목
        }
        return out.sortedBy { it.name } // 이름순
    }

    /** 목적: 파일 열기 / 입력: id / 리턴: 입력 스트림 */
    override fun open(id: String): InputStream =
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id)) ?: throw IOException("스트림을 열 수 없습니다") // 열기

    /** 목적: 문서 표시 이름 / 입력: id / 리턴: 이름(없으면 "") */
    fun nameOf(id: String): String =
        resolver.query(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null // 첫 행
        } ?: "" // 없으면 빈 이름
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: SAF 트리 쓰기 구현
 * 기능: DocumentsContract.createDocument 로 폴더·파일을 만들고 openOutputStream 으로 쓴다. 파일 MIME 은 application/octet-stream
 *       (제공자가 확장자를 덧붙이거나 바꾸지 않게 해 이름을 그대로 유지).
 * 입력: resolver(콘텐츠 접근자), treeUri(트리 Uri)
 */
private class SafTreeSink(private val resolver: ContentResolver, private val treeUri: Uri) : TreeSink {

    /** 목적: 자식 이름들 / 입력: dirId / 리턴: 이름 집합 */
    override fun childNames(dirId: String): Set<String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId) // 자식 질의 Uri
        val names = HashSet<String>() // 결과
        (resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null) ?: throw IOException("목록 질의 결과가 없습니다")).use { c -> // 질의
            while (c.moveToNext()) c.getString(0)?.let { names += it } // 이름
        }
        return names // 집합
    }

    /** 목적: 폴더 만들기 / 입력: parentId, name / 리턴: 새 문서 id */
    override fun createDir(parentId: String, name: String): String {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId) // 부모 Uri
        val made = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw IOException("폴더 생성 결과가 없습니다") // 만들기
        return DocumentsContract.getDocumentId(made) // 새 id
    }

    /** 목적: 파일 만들기 / 입력: parentId, name / 리턴: 출력 스트림 */
    override fun createFile(parentId: String, name: String): OutputStream {
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId) // 부모 Uri
        val made = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name) ?: throw IOException("파일 생성 결과가 없습니다") // 만들기
        return resolver.openOutputStream(made, "w") ?: throw IOException("출력 스트림을 열 수 없습니다") // 쓰기 스트림
    }
}
