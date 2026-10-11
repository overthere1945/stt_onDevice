// 파일명: ModelInstaller.kt
// 목적: 모델 설치(다운로드)와 import(폴더/압축) 및 SHA-256 검증, 원자적 READY 전환
// 기능: tmp/<id>-<nonce>/ 작업 폴더에서 받기 → 압축 해시 확인(카탈로그에 있을 때) → 해제(또는 NONE 복사) → 선언된 files 전부(존재·크기·SHA-256) 검증 →
//       `.ready.json` 기록 → models/<id> 로 원자적 rename. 어떤 실패든 작업 폴더를 지운다.
//       다운로드 파일은 tmp/<id>.dl/ 에 두어 취소·네트워크 오류 때 .part 이어받기가 가능하다(성공·해시 불일치 시 지움).
package com.cowork.stt_ondevice.model // 모델 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.currentCoroutineContext // 현재 코루틴 컨텍스트
import kotlinx.coroutines.ensureActive // 취소 확인
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.io.FileNotFoundException // 파일 없음
import java.io.IOException // 입출력 예외
import java.nio.file.Files // 파일 도구
import java.util.UUID // 난수 식별자

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 저장공간 부족 예외
 * 기능: 필요량과 가용량을 알린다.
 * 입력: requiredBytes(필요 바이트), availableBytes(가용 바이트)
 */
class InsufficientStorageException(val requiredBytes: Long, val availableBytes: Long) :
    IOException("저장공간 부족: 필요 약 ${requiredBytes / (1024 * 1024)}MB, 사용 가능 ${availableBytes / (1024 * 1024)}MB") // 메시지

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 해시 불일치 예외
 * 기능: 받은 압축 파일의 SHA-256 이 카탈로그와 다를 때 던진다.
 * 입력: what(대상 설명), expected(기대 해시), actual(실제 해시)
 */
class ChecksumMismatchException(val what: String, val expected: String, val actual: String) :
    IOException("SHA-256 불일치: $what (기대 $expected, 실제 $actual)") // 메시지

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 파일 검증 실패 예외
 * 기능: 설치된 파일 중 누락·크기·해시 문제 목록을 담는다.
 * 입력: problems(문제 설명 목록, 비어 있지 않음)
 */
class ModelVerificationException(val problems: List<String>) :
    IOException("모델 파일 검증 실패: " + problems.joinToString("; ")) // 메시지

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: import 전용 모델에 install 을 부른 오류
 * 기능: downloads 가 비어 있는 spec 은 내려받을 수 없으므로 importFrom 을 쓰라고 알린다.
 * 입력: id(모델 id)
 */
class ImportOnlyModelException(id: String) :
    IllegalStateException("모델 '$id' 는 다운로드 경로가 없는 import 전용입니다. importFrom(폴더 또는 압축 파일)을 사용하세요.") // 메시지

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 모델 설치기
 * 기능: install(다운로드) / importFrom(로컬 폴더·압축) / verify(설치본 전수 해시 검사).
 * 입력: dirs(앱 폴더), store(모델 저장소), downloader(다운로더), usableSpace(저장공간 조회 함수 — 테스트에서 교체, 기본은 File.usableSpace)
 */
class ModelInstaller(
    private val dirs: AppDirs, // 앱 폴더
    private val store: ModelStore, // 모델 저장소
    private val downloader: Downloader, // 다운로더
    private val usableSpace: (File) -> Long = { it.usableSpace }, // 가용 공간 조회
) {
    private companion object {
        const val STORAGE_FACTOR = 2.2 // 저장공간 배수: 받은 압축(≈bytes)과 푼 결과(≈files)가 동시에 디스크에 있을 수 있어 큰 쪽의 2배 + 10% 여유
        const val COPY_BUFFER = 64 * 1024 // 복사 버퍼(64KB)
    }

    private val running = HashSet<String>() // 설치·import 진행 중인 모델 id (running 으로 보호)

    /**
     * 목적: 모델 내려받아 설치
     * 기능: 이미 READY 면 아무것도 하지 않는다. downloads 가 비면 ImportOnlyModelException. 저장공간 확인 후 받고(이어받기) 검증해 READY 로 만든다.
     *       실패·취소 시 작업 폴더(tmp/<id>-<nonce>)는 지운다(.part 는 이어받기를 위해 남기되 해시 불일치로 버린 파일은 지움).
     * 입력: spec, onProgress(받은 바이트 합계, 전체 바이트 합계)
     * 출력: ImportOnlyModelException / InsufficientStorageException / ChecksumMismatchException / ModelVerificationException /
     *       IllegalStateException(사용 중이거나 같은 모델 설치가 이미 진행 중) / IOException(네트워크 등) / CancellationException
     * 리턴: 없음
     */
    suspend fun install(spec: ModelSpec, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (spec.downloads.isEmpty()) throw ImportOnlyModelException(spec.id) // import 전용
        begin(spec) // 중복 실행 방지
        try {
            if (store.state(spec) == InstallState.READY) return@withContext // 이미 설치됨(재다운로드 안 함)
            store.requireNotInUse(spec) // 사용 중이면 교체 불가
            val total = spec.downloads.sumOf { it.bytes } // 전체 다운로드 바이트
            checkStorage(spec, total) // 저장공간 확인
            val ctx = currentCoroutineContext() // 취소 확인용
            val dlDir = File(dirs.tmp, "${spec.id}.dl") // 이어받기용 다운로드 폴더(작업 폴더와 분리)
            val work = newWorkDir(spec) // 작업 폴더
            val content = File(work, "content") // 해제 결과 폴더
            try {
                store.setTransient(spec, InstallState.DOWNLOADING) // 받는 중
                var base = 0L // 앞서 끝난 다운로드의 바이트 합
                spec.downloads.forEachIndexed { i, d -> // 다운로드마다
                    val dest = File(dlDir, "$i.${extensionOf(d.archive)}") // 받을 파일
                    downloader.download(d.url, dest, d.bytes) { done, _ -> onProgress(base + done, total) } // 받기(이어받기)
                    if (d.sha256.isNotEmpty()) { // 카탈로그에 압축 해시가 있으면
                        store.setTransient(spec, InstallState.VERIFYING) // 검증 중
                        val actual = Sha256.of(dest) // 실제 해시
                        if (!actual.equals(d.sha256, ignoreCase = true)) { // 불일치
                            dest.delete() // 오염된 파일은 이어받기에 쓰지 않도록 삭제
                            throw ChecksumMismatchException(d.url, d.sha256, actual) // 실패
                        }
                        store.setTransient(spec, InstallState.DOWNLOADING) // 다시 받는 중
                    }
                    ctx.ensureActive() // 취소 확인
                    store.setTransient(spec, InstallState.VERIFYING) // 해제·검증 단계
                    if (d.archive == ArchiveType.NONE) { // 압축 없음
                        placeSingleFile(spec, d, dest, content) // 선언된 파일 경로로 이동
                    } else { // 압축
                        ArchiveExtractor.extract(dest, d.archive, content, d.stripPrefix) { ctx.ensureActive() } // 안전 해제
                        dest.delete() // 해제가 끝난 압축 삭제(공간 절약)
                    }
                    store.setTransient(spec, InstallState.DOWNLOADING) // 다음 다운로드
                    base += d.bytes // 합계 갱신
                }
                store.setTransient(spec, InstallState.VERIFYING) // 파일 검증 중
                finalizeInstall(spec, content) // 전수 검증 → 표식 → 교체
            } finally {
                store.setTransient(spec, null) // 진행 상태 해제
                work.deleteRecursively() // 작업 폴더 정리(성공 시 content 는 이미 이동됨)
                dlDir.delete() // 비어 있으면 삭제(남은 .part 가 있으면 그대로 둠)
            }
        } finally {
            end(spec) // 진행 표시 해제
        }
    }

    /**
     * 목적: 로컬 파일에서 설치(import)
     * 기능: source 가 폴더면 spec.files 의 상대 경로만 복사하고, 압축 파일(.tar.bz2/.tbz2/.zip)이면 같은 안전 해제를 한다(stripPrefix 는 spec.downloads 첫 항목의 값, 없으면 "").
     *       이후 download 와 동일하게 전수 검증 → READY. 이미 READY 면 아무것도 하지 않는다.
     * 입력: spec, source(폴더 또는 압축 파일)
     * 출력: FileNotFoundException(없음) / IllegalArgumentException(지원하지 않는 확장자) / InsufficientStorageException / ModelVerificationException /
     *       UnsafeArchiveEntryException / IllegalStateException(사용 중·중복 진행) / IOException / CancellationException
     * 리턴: 없음
     */
    suspend fun importFrom(spec: ModelSpec, source: File): Unit = withContext(Dispatchers.IO) {
        if (!source.exists()) throw FileNotFoundException("import 원본이 없습니다: $source") // 원본 확인
        begin(spec) // 중복 실행 방지
        try {
            if (store.state(spec) == InstallState.READY) return@withContext // 이미 설치됨
            store.requireNotInUse(spec) // 사용 중이면 교체 불가
            checkStorage(spec, if (source.isFile) source.length() else 0L) // 저장공간 확인(폴더면 files 기준)
            val ctx = currentCoroutineContext() // 취소 확인용
            val work = newWorkDir(spec) // 작업 폴더
            val content = File(work, "content") // 결과 폴더
            try {
                store.setTransient(spec, InstallState.VERIFYING) // 처리·검증 중
                if (source.isDirectory) { // 폴더 import
                    for (f in spec.files) { // 선언 파일마다
                        val rel = ArchiveExtractor.safeParts(f.path) // 상대 경로 검사
                        val src = File(source, rel.joinToString("/")) // 원본 파일
                        if (!src.isFile) continue // 없으면 건너뜀(검증 단계에서 '없음'으로 보고)
                        val dst = File(content, rel.joinToString("/")) // 복사 대상
                        dst.parentFile!!.mkdirs() // 부모 폴더
                        copyCancellable(src, dst) { ctx.ensureActive() } // 복사
                    }
                } else { // 압축 파일 import
                    val type = archiveTypeOf(source) // 확장자로 형식 판정
                    val strip = spec.downloads.firstOrNull()?.stripPrefix ?: "" // 접두
                    ArchiveExtractor.extract(source, type, content, strip) { ctx.ensureActive() } // 안전 해제
                }
                finalizeInstall(spec, content) // 전수 검증 → 표식 → 교체
            } finally {
                store.setTransient(spec, null) // 진행 상태 해제
                work.deleteRecursively() // 작업 폴더 정리
            }
        } finally {
            end(spec) // 진행 표시 해제
        }
    }

    /**
     * 목적: 설치본 전수 검증
     * 기능: models/<id> 의 선언 파일마다 존재·크기·SHA-256 을 확인한다(큰 모델은 시간이 걸리므로 백그라운드에서 호출).
     * 입력: spec
     * 출력: 없음
     * 리턴: 문제 목록(비어 있으면 모두 정상)
     */
    fun verify(spec: ModelSpec): List<String> = verifyDir(spec, store.modelDir(spec)) // 모델 폴더 검증

    /**
     * 목적: 폴더 안 선언 파일 검증
     * 기능: spec.files 마다 일반 파일인지, 크기가 같은지, SHA-256 이 같은지 본다. 크기가 다르면 해시는 계산하지 않는다.
     * 입력: spec, dir(검증할 폴더)
     * 출력: 없음
     * 리턴: 문제 목록
     */
    private fun verifyDir(spec: ModelSpec, dir: File): List<String> {
        val problems = mutableListOf<String>() // 문제 목록
        for (f in spec.files) { // 파일마다
            val file = File(dir, f.path) // 실제 파일
            if (!file.isFile) { problems.add("${f.path}: 파일 없음"); continue } // 누락
            if (file.length() != f.bytes) { problems.add("${f.path}: 크기 불일치(기대 ${f.bytes}, 실제 ${file.length()})"); continue } // 크기
            val actual = Sha256.of(file) // 실제 해시
            if (!actual.equals(f.sha256, ignoreCase = true)) problems.add("${f.path}: SHA-256 불일치(기대 ${f.sha256}, 실제 $actual)") // 해시
        }
        return problems // 결과
    }

    /**
     * 목적: 검증 후 확정
     * 기능: content 를 전수 검증하고 통과하면 `.ready.json` 을 쓴 뒤 models/<id> 로 원자적 교체한다
     *       (표식을 rename 전에 써 두므로 models/<id> 는 READY 이거나 아예 새 폴더가 아닌 상태 중 하나뿐이다).
     * 입력: spec, content(해제 결과 폴더)
     * 출력: 문제가 있으면 ModelVerificationException
     * 리턴: 없음
     */
    private fun finalizeInstall(spec: ModelSpec, content: File) {
        content.mkdirs() // 파일이 하나도 없어도 폴더는 존재하도록(검증이 '없음'으로 보고)
        val problems = verifyDir(spec, content) // 전수 검증
        if (problems.isNotEmpty()) throw ModelVerificationException(problems) // 실패
        store.writeMarker(spec, content) // 표식 기록
        store.commit(spec, content) // 원자적 교체
    }

    /**
     * 목적: NONE 다운로드 파일 배치
     * 기능: 받은 파일(bytes·sha256 이 같은 ModelFile)을 content/<path> 로 옮긴다. 후보가 여럿이면 URL 마지막 이름으로 좁힌다. 이미 있으면 거부.
     * 입력: spec, d(다운로드 정보), downloaded(받은 파일), content(결과 폴더)
     * 출력: 대응 파일을 못 찾거나 모호하면 IllegalStateException(카탈로그 오류), 중복이면 UnsafeArchiveEntryException
     * 리턴: 없음
     */
    private fun placeSingleFile(spec: ModelSpec, d: Download, downloaded: File, content: File) {
        var candidates = spec.files.filter { it.bytes == d.bytes && (d.sha256.isEmpty() || it.sha256.equals(d.sha256, true)) } // 크기·해시 일치 후보
        if (candidates.size > 1) { // 모호하면 URL 이름으로
            val last = d.url.substringBefore('?').substringAfterLast('/') // URL 마지막 구성요소
            candidates = candidates.filter { it.path.substringAfterLast('/') == last } // 이름 일치
        }
        val target = candidates.singleOrNull() ?: throw IllegalStateException("카탈로그 오류: ${spec.id} 의 NONE 다운로드 ${d.url} 에 대응하는 files 항목을 하나로 정할 수 없음") // 대응 실패
        val rel = ArchiveExtractor.safeParts(target.path) // 경로 검사
        val dst = File(content, rel.joinToString("/")) // 배치 위치
        if (dst.exists()) throw UnsafeArchiveEntryException(target.path, "같은 경로가 이미 있음(덮어쓰기 금지)") // 중복
        dst.parentFile!!.mkdirs() // 부모 폴더
        Files.move(downloaded.toPath(), dst.toPath()) // 이동(같은 파일시스템, 덮어쓰기 없음)
    }

    /**
     * 목적: 저장공간 확인
     * 기능: 필요량 = max(내려받을/가져올 압축 바이트, files 바이트 합) × 2.2. 받은 압축과 푼 결과가 동시에 디스크에 있을 수 있어 큰 쪽의 약 2배에 10% 여유를 둔 값이다.
     *       dirs.root.usableSpace 가 그보다 작으면 InsufficientStorageException.
     * 입력: spec, archiveBytes(압축 바이트 합, 폴더 import 면 0)
     * 출력: InsufficientStorageException
     * 리턴: 없음
     */
    private fun checkStorage(spec: ModelSpec, archiveBytes: Long) {
        val basis = maxOf(archiveBytes, spec.files.sumOf { it.bytes }) // 큰 쪽
        val required = (basis * STORAGE_FACTOR).toLong() // 필요량
        dirs.root.mkdirs() // 루트 보장(없으면 usableSpace 가 0)
        val available = usableSpace(dirs.root) // 가용량
        if (available < required) throw InsufficientStorageException(required, available) // 부족
    }

    /**
     * 목적: 작업 폴더 생성
     * 기능: 이전 실행이 남긴 같은 모델의 낡은 작업 폴더(tmp/<id>-<8자리 16진>)를 지우고 새 tmp/<id>-<nonce> 를 만든다.
     * 입력: spec
     * 출력: 생성 실패는 IOException
     * 리턴: 작업 폴더
     */
    private fun newWorkDir(spec: ModelSpec): File {
        dirs.tmp.mkdirs() // tmp 보장
        val stale = Regex(Regex.escape(spec.id) + "-[0-9a-f]{8}") // 낡은 작업 폴더 이름 패턴
        dirs.tmp.listFiles()?.filter { it.isDirectory && stale.matches(it.name) }?.forEach { it.deleteRecursively() } // 정리
        val nonce = UUID.randomUUID().toString().replace("-", "").take(8) // 난수
        val work = File(dirs.tmp, "${spec.id}-$nonce") // 새 작업 폴더
        if (!work.mkdirs()) throw IOException("작업 폴더를 만들 수 없습니다: $work") // 생성
        return work // 결과
    }

    /**
     * 목적: 진행 등록
     * 기능: 같은 모델의 설치·import 가 동시에 둘 돌지 않게 한다.
     * 입력: spec
     * 출력: 이미 진행 중이면 IllegalStateException
     * 리턴: 없음
     */
    private fun begin(spec: ModelSpec) {
        synchronized(running) { check(running.add(spec.id)) { "이미 설치/import 진행 중인 모델입니다: ${spec.id}" } } // 등록
    }

    /**
     * 목적: 진행 해제
     * 기능: begin 의 짝.
     * 입력: spec
     * 출력: 없음
     * 리턴: 없음
     */
    private fun end(spec: ModelSpec) {
        synchronized(running) { running.remove(spec.id) } // 해제
    }

    /**
     * 목적: 다운로드 파일 확장자
     * 기능: 압축 형식별 저장 확장자를 정한다(NONE 은 dl).
     * 입력: type
     * 출력: 없음
     * 리턴: 확장자 문자열
     */
    private fun extensionOf(type: ArchiveType): String = when (type) {
        ArchiveType.NONE -> "dl" // 단일 파일
        ArchiveType.TAR_BZ2 -> "tar.bz2" // tar.bz2
        ArchiveType.ZIP -> "zip" // zip
    }

    /**
     * 목적: 파일 이름으로 압축 형식 판정
     * 기능: .tar.bz2/.tbz2/.tbz → TAR_BZ2, .zip → ZIP.
     * 입력: file
     * 출력: 지원하지 않는 확장자면 IllegalArgumentException
     * 리턴: ArchiveType(NONE 아님)
     */
    private fun archiveTypeOf(file: File): ArchiveType {
        val n = file.name.lowercase() // 소문자 이름
        return when {
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> ArchiveType.TAR_BZ2 // tar.bz2
            n.endsWith(".zip") -> ArchiveType.ZIP // zip
            else -> throw IllegalArgumentException("지원하지 않는 import 파일 형식(.tar.bz2/.zip 또는 폴더만 가능): ${file.name}") // 거부
        }
    }

    /**
     * 목적: 취소 가능한 파일 복사
     * 기능: 64KB 버퍼로 복사하며 버퍼마다 checkCancel 을 부른다. dst 가 이미 있으면 덮어쓰지 않고 실패한다.
     * 입력: src, dst, checkCancel
     * 출력: IOException / 취소 예외
     * 리턴: 없음
     */
    private fun copyCancellable(src: File, dst: File, checkCancel: () -> Unit) {
        src.inputStream().use { input -> // 원본
            Files.newOutputStream(dst.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { out -> // 새 파일만
                val buf = ByteArray(COPY_BUFFER) // 버퍼
                while (true) { // 끝까지
                    checkCancel() // 취소 확인
                    val n = input.read(buf) // 읽기
                    if (n < 0) break // 끝
                    out.write(buf, 0, n) // 쓰기
                }
            }
        }
    }
}
