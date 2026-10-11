// 파일명: Downloader.kt
// 목적: 모델 artifact 를 HTTP(S) 로 내려받는다(이어받기·리다이렉트·크기 검증·협력적 취소)
// 기능: HttpURLConnection 으로 받는다. dest.part 가 있으면 `Range: bytes=<보유 크기>-` 로 이어받고, 206 의 Content-Range 시작이 보유 크기와
//       같을 때만 이어 붙인다(그 외 200/416/불일치는 처음부터). 3xx 는 최대 5회 직접 따라간다(HF/GitHub → CDN 처럼 호스트가 바뀌어도 동작).
//       최종 크기가 expectedBytes 와 같아야 dest 로 확정한다. 취소·네트워크 오류 때는 .part 를 지우지 않아 다음에 이어받을 수 있다.
//       SHA-256 검증은 하지 않는다(호출자 ModelInstaller 책임).
package com.cowork.stt_ondevice.model // 모델 패키지

import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.currentCoroutineContext // 현재 코루틴 컨텍스트
import kotlinx.coroutines.ensureActive // 취소 확인
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.io.FileOutputStream // 파일 출력
import java.io.IOException // 입출력 예외
import java.net.HttpURLConnection // HTTP 연결
import java.net.URL // URL
import java.nio.file.Files // 파일 도구
import java.nio.file.StandardCopyOption // 이동 옵션

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: HTTP 상태 코드 오류
 * 기능: 200/206 이 아닌 응답(404, 5xx 등)을 알린다.
 * 입력: code(상태 코드), url(요청 URL)
 */
class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code: $url") // 메시지

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 모델 다운로더
 * 기능: 위 파일 머리말의 규칙대로 URL 하나를 dest 로 받는다.
 * 입력: connectTimeoutMs(연결 제한 시간), readTimeoutMs(읽기 제한 시간 — 블로킹 읽기가 취소 확인 사이에 멈추는 최대 시간), maxRedirects(최대 리다이렉트 횟수)
 */
class Downloader(
    private val connectTimeoutMs: Int = 15_000, // 연결 제한(ms)
    private val readTimeoutMs: Int = 30_000, // 읽기 제한(ms)
    private val maxRedirects: Int = 5, // 최대 리다이렉트
) {
    private companion object {
        const val BUFFER_SIZE = 64 * 1024 // 읽기 버퍼 크기(64KB)
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""") // Content-Range 형식
    }

    /**
     * 목적: 파일 하나 내려받기
     * 기능: dest 가 이미 expectedBytes 크기면 건너뛴다. 아니면 dest.part 에 받아(이어받기 포함) 크기가 맞으면 dest 로 원자적 이동한다.
     *       버퍼를 읽을 때마다 코루틴 취소를 확인한다(취소 시 .part 보존).
     * 입력: url(주소), dest(최종 파일, .part 는 dest 경로 + ".part"), expectedBytes(기대 크기, 양수), onProgress(받은 바이트, 전체 바이트)
     * 출력: 취소는 CancellationException, HTTP 오류는 HttpStatusException, 크기 불일치·연결 끊김·리다이렉트 초과는 IOException
     *       (선언 크기가 다르거나 기대보다 많이 받으면 .part 를 지우고, 기대보다 적게 받고 끊기면 .part 를 남긴다)
     * 리턴: 없음
     */
    suspend fun download(url: String, dest: File, expectedBytes: Long, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        require(expectedBytes > 0) { "expectedBytes 는 양수여야 합니다: $expectedBytes" } // 호출 검증
        val ctx = currentCoroutineContext() // 취소 확인용 컨텍스트
        dest.absoluteFile.parentFile?.mkdirs() // 목적지 폴더 보장
        if (dest.isFile && dest.length() == expectedBytes) { // 이미 완성된 파일
            onProgress(expectedBytes, expectedBytes) // 진행률 100%
            return@withContext // 네트워크 없이 종료
        }
        if (dest.exists()) dest.delete() // 크기가 다른 낡은 완성 파일은 버림
        val part = File(dest.path + ".part") // 이어받기 파일
        var have = if (part.isFile) part.length() else 0L // 보유 바이트
        if (have > expectedBytes) { // 기대보다 큼 → 쓸 수 없음
            part.delete() // 버림
            have = 0L // 처음부터
        }
        if (have < expectedBytes) { // 더 받아야 함
            have = fetchInto(url, part, have, expectedBytes, onProgress) // 받기(최종 보유 크기 반환)
            ctx.ensureActive() // 취소 확인
        }
        if (have != expectedBytes) { // 이론상 도달 불가(방어)
            throw IOException("다운로드 크기 불일치: $have != $expectedBytes ($url)") // 실패
        }
        onProgress(expectedBytes, expectedBytes) // 진행률 100%
        Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) // 확정
    }

    /**
     * 목적: 서버에 접속해 part 에 본문 기록
     * 기능: 보유량이 있으면 Range 로 요청하고 응답 코드에 따라 이어붙이기/처음부터를 정한다. 읽을 때마다 취소를 확인한다.
     * 입력: url, part(.part 파일), haveStart(현재 보유 바이트), expectedBytes, onProgress
     * 출력: 취소·HTTP 오류·크기 오류·끊김은 예외(위 download 의 출력 참고)
     * 리턴: 기록 후 part 의 크기(= expectedBytes)
     */
    private suspend fun fetchInto(url: String, part: File, haveStart: Long, expectedBytes: Long, onProgress: (Long, Long) -> Unit): Long {
        val ctx = currentCoroutineContext() // 취소 확인용 컨텍스트
        var conn = connect(url, haveStart) // 접속(+리다이렉트)
        try {
            var code = conn.responseCode // 응답 코드
            var append = false // 이어붙이기 여부
            if (haveStart > 0) { // 이어받기 요청이었음
                when {
                    code == 206 && contentRangeStart(conn) == haveStart -> append = true // 정상 이어받기
                    code == 200 -> Unit // Range 무시 → 같은 연결로 처음부터
                    code == 206 || code == 416 -> { // Content-Range 불일치 / 범위 불가 → 처음부터 다시 요청
                        conn.disconnect() // 이전 연결 닫기
                        conn = connect(url, 0L) // Range 없이 재접속
                        code = conn.responseCode // 새 응답 코드
                    }
                    else -> throw HttpStatusException(code, url) // 그 외 오류
                }
            }
            if (!append) { // 처음부터 받는 경우
                if (code != 200) throw HttpStatusException(code, url) // 200 이어야 함
                val len = conn.contentLengthLong // 선언 크기(-1 이면 모름)
                if (len >= 0 && len != expectedBytes) { // 선언 크기가 다름
                    part.delete() // 쓸 수 없는 부분 파일
                    throw IOException("서버 선언 크기($len)가 기대 크기($expectedBytes)와 다름: $url") // 실패
                }
            } else { // 이어붙이는 경우: Content-Range 전체 크기 확인
                val total = CONTENT_RANGE.find(conn.getHeaderField("Content-Range") ?: "")?.groupValues?.get(3)?.toLongOrNull() // 전체 크기
                if (total != null && total != expectedBytes) { // 전체 크기가 다름
                    part.delete() // 쓸 수 없는 부분 파일
                    throw IOException("서버 전체 크기($total)가 기대 크기($expectedBytes)와 다름: $url") // 실패
                }
            }
            var done = if (append) haveStart else 0L // 현재까지 확보한 바이트
            onProgress(done, expectedBytes) // 시작 진행률
            val buf = ByteArray(BUFFER_SIZE) // 읽기 버퍼
            FileOutputStream(part, append).use { out -> // append=false 면 여기서 파일이 비워짐
                conn.inputStream.use { input -> // 본문
                    while (true) { // 끝까지
                        ctx.ensureActive() // 협력적 취소(.part 는 그대로 둠)
                        val n = input.read(buf) // 읽기
                        if (n < 0) break // 끝
                        if (done + n > expectedBytes) { // 기대보다 많음
                            out.close() // 닫고
                            part.delete() // 오염된 파일 삭제
                            throw IOException("서버가 기대($expectedBytes)보다 많은 데이터를 보냄: $url") // 실패
                        }
                        out.write(buf, 0, n) // 기록
                        done += n // 누적
                        onProgress(done, expectedBytes) // 진행률
                    }
                }
            }
            if (done < expectedBytes) throw IOException("연결이 일찍 끊김: $done/$expectedBytes ($url) — 다음에 이어받기") // .part 보존
            return done // 완료
        } finally {
            conn.disconnect() // 연결 정리
        }
    }

    /**
     * 목적: 접속(리다이렉트 수동 추적)
     * 기능: HttpURLConnection 은 프로토콜이 바뀌는 리다이렉트를 따라가지 않으므로 3xx 를 직접 따라간다(최대 maxRedirects 회, https→http 격하는 거부).
     *       gzip 자동 해제를 막기 위해 Accept-Encoding: identity 를 보낸다(Range·크기 검증이 압축 전 바이트 기준이어야 함).
     * 입력: url(시작 주소), rangeFrom(0 보다 크면 Range: bytes=rangeFrom- 헤더)
     * 출력: 리다이렉트 초과·Location 누락·격하는 IOException
     * 리턴: 응답이 3xx 가 아닌 연결(응답 코드는 호출자가 확인)
     */
    private fun connect(url: String, rangeFrom: Long): HttpURLConnection {
        var current = URL(url) // 현재 주소
        for (hop in 0..maxRedirects) { // 최초 1 + 리다이렉트 maxRedirects
            val c = current.openConnection() as HttpURLConnection // 연결
            try {
                c.instanceFollowRedirects = false // 자동 추적 끔(직접 처리)
                c.connectTimeout = connectTimeoutMs // 연결 제한
                c.readTimeout = readTimeoutMs // 읽기 제한
                c.setRequestProperty("Accept-Encoding", "identity") // 압축 응답 금지
                c.setRequestProperty("User-Agent", "STT_OnDevice/1.0") // 식별
                if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=$rangeFrom-") // 이어받기
                val code = c.responseCode // 응답 코드(여기서 실제 요청)
                if (code !in intArrayOf(301, 302, 303, 307, 308)) return c // 리다이렉트 아님 → 사용
                val loc = c.getHeaderField("Location") ?: throw IOException("리다이렉트인데 Location 이 없음: $current") // 이동 위치
                c.disconnect() // 이 연결은 버림
                if (hop == maxRedirects) throw IOException("리다이렉트가 ${maxRedirects}회를 넘음: $url") // 횟수 초과
                val next = URL(current, loc) // 상대 주소도 해석
                if (current.protocol == "https" && next.protocol != "https") throw IOException("https → ${next.protocol} 격하 리다이렉트 거부: $next") // 보안
                current = next // 다음 hop
            } catch (e: IOException) {
                c.disconnect() // 실패 시 정리
                throw e // 그대로 전달
            }
        }
        throw IOException("리다이렉트 처리 오류: $url") // 도달 불가
    }

    /**
     * 목적: Content-Range 시작 위치 읽기
     * 기능: "bytes <start>-<end>/<total>" 의 start 를 파싱한다.
     * 입력: conn(응답 연결)
     * 출력: 없음
     * 리턴: start, 헤더가 없거나 형식이 다르면 null
     */
    private fun contentRangeStart(conn: HttpURLConnection): Long? =
        CONTENT_RANGE.find(conn.getHeaderField("Content-Range") ?: "")?.groupValues?.get(1)?.toLongOrNull() // 시작 위치
}
