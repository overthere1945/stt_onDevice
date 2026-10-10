// 파일명: TestFixtures.kt
// 목적: 모델 설치 계열 테스트가 공유하는 도우미(압축 fixture 생성, 로컬 HTTP 서버, 테스트용 ModelSpec, SHA-256 계산)
// 기능: commons-compress 로 작은 tar.bz2/zip 을 만들고, JDK HttpServer 로 Range/리다이렉트 응답을 흉내 내며, 요청 기록을 남긴다.
package com.cowork.stt_ondevice.model // 모델 테스트 패키지

import com.sun.net.httpserver.HttpExchange // HTTP 교환 객체
import com.sun.net.httpserver.HttpServer // JDK 내장 HTTP 서버
import org.apache.commons.compress.archivers.tar.TarArchiveEntry // tar 항목
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream // tar 출력
import org.apache.commons.compress.archivers.tar.TarConstants // tar 상수
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry // zip 항목
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream // zip 출력
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream // bzip2 압축
import java.io.File // 파일
import java.net.InetAddress // 주소
import java.net.InetSocketAddress // 소켓 주소
import java.security.MessageDigest // 해시
import java.util.concurrent.CopyOnWriteArrayList // 스레드 안전 리스트
import java.util.concurrent.ConcurrentHashMap // 스레드 안전 맵
import java.util.concurrent.Executors // 실행기

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 바이트 배열의 SHA-256(소문자 16진) 계산
 * 기능: 테스트가 기대 해시를 만들 때 쓴다(프로덕션 Sha256 과 독립 구현으로 교차 확인).
 * 입력: bytes(대상 바이트)
 * 출력: 없음
 * 리턴: 소문자 16진 64자
 */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } // 해시 → 16진

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: tar.bz2 파일 생성
 * 기능: build 블록에서 항목을 추가하면 bzip2 로 압축해 dest 에 쓴다.
 * 입력: dest(출력 파일), build(항목 추가 블록)
 * 출력: dest 파일 생성
 * 리턴: dest
 */
fun makeTarBz2(dest: File, build: TarArchiveOutputStream.() -> Unit): File {
    BZip2CompressorOutputStream(dest.outputStream().buffered()).use { bz -> // bzip2 스트림
        TarArchiveOutputStream(bz).use { tar -> // tar 스트림
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX) // 긴 이름 허용
            tar.build() // 항목 추가
            tar.finish() // tar 끝 표시
        }
    }
    return dest // 만든 파일
}

/** 목적: tar 에 일반 파일 추가 / 기능: name 으로 bytes 를 쓴다 / 입력: name, bytes / 리턴: 없음 */
fun TarArchiveOutputStream.addFile(name: String, bytes: ByteArray) {
    val e = TarArchiveEntry(name) // 항목
    e.size = bytes.size.toLong() // 크기
    putArchiveEntry(e) // 시작
    write(bytes) // 내용
    closeArchiveEntry() // 끝
}

/** 목적: tar 에 폴더 추가 / 기능: name(끝 '/')으로 폴더 항목을 쓴다 / 입력: name / 리턴: 없음 */
fun TarArchiveOutputStream.addDir(name: String) {
    putArchiveEntry(TarArchiveEntry(name)) // 폴더 항목
    closeArchiveEntry() // 끝
}

/** 목적: tar 에 심볼릭 링크 추가 / 기능: name -> target 링크 항목을 쓴다 / 입력: name, target / 리턴: 없음 */
fun TarArchiveOutputStream.addSymlink(name: String, target: String) {
    val e = TarArchiveEntry(name, TarConstants.LF_SYMLINK) // 링크 항목
    e.linkName = target // 링크 대상
    putArchiveEntry(e) // 시작
    closeArchiveEntry() // 끝
}

/** 목적: tar 에 하드 링크 추가 / 기능: name -> target 하드링크 항목을 쓴다 / 입력: name, target / 리턴: 없음 */
fun TarArchiveOutputStream.addHardlink(name: String, target: String) {
    val e = TarArchiveEntry(name, TarConstants.LF_LINK) // 하드링크 항목
    e.linkName = target // 링크 대상
    putArchiveEntry(e) // 시작
    closeArchiveEntry() // 끝
}

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: zip 파일 생성
 * 기능: build 블록에서 항목을 추가하면 dest 에 zip 으로 쓴다.
 * 입력: dest(출력 파일), build(항목 추가 블록)
 * 출력: dest 파일 생성
 * 리턴: dest
 */
fun makeZip(dest: File, build: ZipArchiveOutputStream.() -> Unit): File {
    ZipArchiveOutputStream(dest).use { it.build() } // 항목 추가 후 닫기
    return dest // 만든 파일
}

/** 목적: zip 에 일반 파일 추가 / 기능: 이름은 그대로(검증 없이) 기록한다 / 입력: name, bytes, unixMode(0 이면 미표기) / 리턴: 없음 */
fun ZipArchiveOutputStream.addFile(name: String, bytes: ByteArray, unixMode: Int = 0) {
    val e = ZipArchiveEntry(name) // 항목
    if (unixMode != 0) e.unixMode = unixMode // 유닉스 모드(링크 흉내)
    putArchiveEntry(e) // 시작
    write(bytes) // 내용
    closeArchiveEntry() // 끝
}

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 테스트용 ModelSpec 생성
 * 기능: 설치 로직이 보는 필드(id, downloads, files)만 채우고 나머지는 고정값을 쓴다.
 * 입력: id, downloads, files
 * 출력: 없음
 * 리턴: ModelSpec
 */
fun testSpec(id: String, downloads: List<Download>, files: List<ModelFile>): ModelSpec = ModelSpec(
    id = id, // 고유 id
    kind = ModelKind.ASR, // 종류
    algo = "test", // algo
    model = id, // model
    displayName = id, // 표시 이름
    languages = setOf("ko"), // 언어
    runtime = Runtime.VOSK, // 런타임
    runtimeVersion = "0", // 런타임 버전
    precision = "fp32", // 정밀도
    maxChunkSec = 0.0, // 청크 길이
    streaming = StreamingKind.FILE_ONLY, // 입력 방식
    downloads = downloads, // 다운로드
    files = files, // 검증 파일
    originalRepo = "", // 원본 저장소
    license = "test", // 라이선스
    licenseUrl = "", // 라이선스 URL
    commercial = CommercialStatus.ALLOWED, // 상업 이용
    compatAlias = null, // 별칭
    engineConfig = VoskConfig, // 엔진 설정
)

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 서버가 Range 요청을 다루는 방식 구분
 * 기능: 정상(HONOR) / Range 무시하고 200 전체(IGNORE) / 206 이지만 Content-Range 시작이 0(WRONG_START) / 항상 416(ALWAYS_416).
 */
enum class RangeMode { HONOR, IGNORE, WRONG_START, ALWAYS_416 }

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 수신한 요청 1건의 기록
 * 기능: 경로와 Range 헤더(없으면 null)를 남긴다.
 * 입력: path, range
 */
data class RecordedRequest(val path: String, val range: String?)

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 로컬 HTTP 서버(127.0.0.1, 임의 포트)
 * 기능: 경로별 바이트를 서빙하고 Range·리다이렉트를 흉내 내며, 받은 요청을 requests 에 기록한다.
 */
class TestHttpServer : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0) // 서버
    private val routes = ConcurrentHashMap<String, ByteArray>() // 경로 → 본문
    private val redirects = ConcurrentHashMap<String, String>() // 경로 → 이동할 경로
    private val truncateTo = ConcurrentHashMap<String, Int>() // 경로 → 본문을 이 길이로 잘라 보냄(Content-Length 는 원래 길이)
    val requests = CopyOnWriteArrayList<RecordedRequest>() // 수신 기록
    @Volatile var rangeMode: RangeMode = RangeMode.HONOR // Range 처리 방식

    init {
        server.executor = Executors.newCachedThreadPool() // 요청 처리 스레드
        server.createContext("/") { ex -> handle(ex) } // 모든 경로 처리
        server.start() // 시작
    }

    /** 목적: URL 만들기 / 기능: 서버 주소 + path / 입력: path('/' 로 시작) / 리턴: 전체 URL */
    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path" // 로컬 URL

    /** 목적: 경로에 본문 등록 / 입력: path, bytes / 리턴: 없음 */
    fun serve(path: String, bytes: ByteArray) { routes[path] = bytes } // 등록

    /** 목적: 경로에 리다이렉트 등록 / 입력: path, toPath / 리턴: 없음 */
    fun redirect(path: String, toPath: String) { redirects[path] = toPath } // 등록

    /** 목적: 본문을 중간에 끊어 보내도록 설정 / 입력: path, bytes(실제로 보낼 앞부분 길이) / 리턴: 없음 */
    fun truncate(path: String, bytes: Int) { truncateTo[path] = bytes } // 등록

    /** 목적: 요청 1건 처리 / 기능: 리다이렉트 → 본문 순으로 응답한다 / 입력: ex / 리턴: 없음 */
    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.path // 요청 경로
        val range = ex.requestHeaders.getFirst("Range") // Range 헤더
        requests.add(RecordedRequest(path, range)) // 기록
        ex.use { // 항상 닫기
            val to = redirects[path] // 리다이렉트 대상
            if (to != null) { // 이동
                ex.responseHeaders.add("Location", url(to)) // 절대 URL
                ex.sendResponseHeaders(302, -1) // 본문 없음
                return
            }
            val body = routes[path] // 본문
            if (body == null) { // 없음
                ex.sendResponseHeaders(404, -1) // 404
                return
            }
            val start = range?.let { Regex("""bytes=(\d+)-""").find(it)?.groupValues?.get(1)?.toLong() } // 요청 시작
            when {
                start != null && rangeMode == RangeMode.ALWAYS_416 -> { // 항상 416
                    ex.sendResponseHeaders(416, -1) // 범위 불가
                }
                start != null && rangeMode == RangeMode.HONOR -> { // 정상 206
                    ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}") // 범위 표기
                    send(ex, 206, body.copyOfRange(start.toInt(), body.size), path) // 뒷부분
                }
                start != null && rangeMode == RangeMode.WRONG_START -> { // 틀린 206
                    ex.responseHeaders.add("Content-Range", "bytes 0-${body.size - 1}/${body.size}") // 시작이 0
                    send(ex, 206, body, path) // 전체 본문
                }
                else -> send(ex, 200, body, path) // 200 전체
            }
        }
    }

    /** 목적: 본문 전송 / 기능: truncate 설정이면 Content-Length 는 전체, 실제 전송은 앞부분만 한다 / 입력: ex, code, body, path / 리턴: 없음 */
    private fun send(ex: HttpExchange, code: Int, body: ByteArray, path: String) {
        val cut = truncateTo[path] // 잘라 보낼 길이
        ex.sendResponseHeaders(code, body.size.toLong()) // 헤더(선언 길이는 전체)
        ex.responseBody.use { out -> out.write(if (cut != null && cut < body.size) body.copyOf(cut) else body) } // 본문
    }

    /** 목적: 서버 종료 / 기능: 즉시 중지 / 입력: 없음 / 리턴: 없음 */
    override fun close() = server.stop(0) // 종료
}
