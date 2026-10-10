// 파일명: DownloaderTest.kt
// 목적: Downloader 의 이어받기·리다이렉트·크기 검증·취소 동작 검증
// 기능: 로컬 HttpServer 로 Range(206/200/416/잘못된 Content-Range), 3xx 리다이렉트(최대 5회), 404, 크기 불일치, 코루틴 취소 후 이어받기를 확인한다.
package com.cowork.stt_ondevice.model // 모델 테스트 패키지

import kotlinx.coroutines.CancellationException // 취소 예외
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.Job // 작업
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.runBlocking // 블로킹 실행
import org.junit.After // 각 테스트 후
import org.junit.Assert.assertArrayEquals // 배열 일치
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // 거짓
import org.junit.Assert.assertNull // null
import org.junit.Assert.assertTrue // 참
import org.junit.Assert.fail // 실패
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.io.IOException // 입출력 예외
import java.util.Random // 난수

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: Downloader 회귀 방지
 * 기능: 서버 동작 변형(Range 처리·리다이렉트·오류)별로 최종 파일과 요청 기록을 확인한다.
 */
class DownloaderTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val server = TestHttpServer() // 로컬 서버
    private val data: ByteArray = ByteArray(300_000).also { Random(7).nextBytes(it) } // 서빙할 본문(64KB 버퍼 여러 번 읽기)
    private val downloader = Downloader() // 대상

    @After
    fun tearDown() = server.close() // 서버 종료

    /** 목적: 이어받기 준비 / 기능: dest.part 에 data 의 앞 n 바이트를 미리 써 둔다 / 입력: dest, n / 리턴: 없음 */
    private fun seedPart(dest: File, n: Int) = File(dest.path + ".part").writeBytes(data.copyOf(n)) // 부분 파일

    /**
     * 목적: 기본 다운로드
     * 기능: 처음부터 받아 dest 에 완성하고 .part 는 남기지 않으며, 진행률이 (전체,전체)로 끝난다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun downloadsWholeFile() = runBlocking {
        server.serve("/f.bin", data) // 등록
        val dest = File(tmp.root, "f.bin") // 목적지
        val reports = mutableListOf<Pair<Long, Long>>() // 진행 기록
        downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { d, t -> reports.add(d to t) } // 받기
        assertArrayEquals(data, dest.readBytes()) // 내용 일치
        assertFalse(File(dest.path + ".part").exists()) // .part 정리됨
        assertEquals(data.size.toLong() to data.size.toLong(), reports.last()) // 마지막 진행률
        assertNull(server.requests.single().range) // 처음엔 Range 없음
    }

    /**
     * 목적: Range 이어받기
     * 기능: .part 가 있으면 `Range: bytes=<have>-` 를 보내고 206 의 뒷부분을 이어 붙여 완성한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun resumeUsesRange() = runBlocking {
        server.serve("/f.bin", data) // 등록
        val dest = File(tmp.root, "f.bin") // 목적지
        seedPart(dest, 100_000) // 앞 100000 바이트 보유
        downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { _, _ -> } // 이어받기
        assertEquals("bytes=100000-", server.requests.single().range) // 서버가 받은 Range
        assertArrayEquals(data, dest.readBytes()) // 이어 붙인 결과가 원본과 같음
    }

    /**
     * 목적: Content-Range 시작 불일치 처리
     * 기능: 206 이지만 Content-Range 시작이 요청한 위치와 다르면 이어붙이지 않고 처음부터 다시 받는다(Range 없는 2번째 요청).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun contentRangeMismatchRestarts() = runBlocking {
        server.rangeMode = RangeMode.WRONG_START // 시작이 0 인 206 응답
        server.serve("/f.bin", data) // 등록
        val dest = File(tmp.root, "f.bin") // 목적지
        seedPart(dest, 100_000) // 앞부분 보유
        downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { _, _ -> } // 받기
        assertEquals(listOf("bytes=100000-", null), server.requests.map { it.range }) // 요청 2번, 두 번째는 Range 없음
        assertArrayEquals(data, dest.readBytes()) // 결과 정상
    }

    /**
     * 목적: 서버가 Range 를 무시(200)하는 경우
     * 기능: 200 이면 .part 를 버리고 처음부터 쓴다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rangeIgnoredRestartsFromZero() = runBlocking {
        server.rangeMode = RangeMode.IGNORE // Range 무시
        server.serve("/f.bin", data) // 등록
        val dest = File(tmp.root, "f.bin") // 목적지
        seedPart(dest, 100_000) // 앞부분 보유(내용이 달라도 버려져야 함)
        File(dest.path + ".part").writeBytes(ByteArray(100_000) { 9 }) // 쓰레기 앞부분
        downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { _, _ -> } // 받기
        assertArrayEquals(data, dest.readBytes()) // 쓰레기가 섞이지 않음
    }

    /**
     * 목적: 416 처리
     * 기능: 416(범위 불가)이면 처음부터 다시 받는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun range416RestartsFromZero() = runBlocking {
        server.rangeMode = RangeMode.ALWAYS_416 // 범위 요청은 416
        server.serve("/f.bin", data) // 등록
        val dest = File(tmp.root, "f.bin") // 목적지
        seedPart(dest, 100_000) // 앞부분 보유
        downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { _, _ -> } // 받기
        assertEquals(listOf("bytes=100000-", null), server.requests.map { it.range }) // 재요청
        assertArrayEquals(data, dest.readBytes()) // 정상
    }

    /**
     * 목적: 리다이렉트 추적
     * 기능: 302 를 따라가 최종 위치에서 받는다(여러 번 연쇄 포함).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun redirectFollowed() = runBlocking {
        server.serve("/final.bin", data) // 최종
        server.redirect("/r1", "/r2") // 1 → 2
        server.redirect("/r2", "/final.bin") // 2 → 최종
        val dest = File(tmp.root, "f.bin") // 목적지
        downloader.download(server.url("/r1"), dest, data.size.toLong()) { _, _ -> } // 받기
        assertArrayEquals(data, dest.readBytes()) // 정상
        assertEquals(listOf("/r1", "/r2", "/final.bin"), server.requests.map { it.path }) // 경로 순서
    }

    /**
     * 목적: 리다이렉트 횟수 제한
     * 기능: 5회를 넘는 리다이렉트(여기서는 순환)는 IOException 으로 끝난다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun tooManyRedirectsFails() {
        server.redirect("/loop", "/loop") // 자기 자신으로 순환
        try {
            runBlocking { downloader.download(server.url("/loop"), File(tmp.root, "f.bin"), 10L) { _, _ -> } } // 받기
            fail("IOException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: IOException) {
            assertEquals(6, server.requests.size) // 최초 1 + 리다이렉트 5
        }
    }

    /**
     * 목적: HTTP 오류 처리
     * 기능: 404 는 IOException(HttpStatusException)이며 파일을 만들지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun httpErrorThrows() {
        val dest = File(tmp.root, "f.bin") // 목적지
        try {
            runBlocking { downloader.download(server.url("/none"), dest, 10L) { _, _ -> } } // 받기
            fail("HttpStatusException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: HttpStatusException) {
            assertEquals(404, e.code) // 상태 코드
        }
        assertFalse(dest.exists()) // 완성 파일 없음
    }

    /**
     * 목적: 크기 검증(작게 끊김)
     * 기능: 연결이 중간에 끊겨 expectedBytes 보다 적게 받으면 IOException 이고 dest 는 없으며 .part 는 이어받기용으로 남는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun shortBodyKeepsPartAndFails() {
        server.serve("/f.bin", data) // 등록
        server.truncate("/f.bin", 150_000) // 150000 바이트만 보내고 끊음
        val dest = File(tmp.root, "f.bin") // 목적지
        try {
            runBlocking { downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { _, _ -> } } // 받기
            fail("IOException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: IOException) {
            // 기대한 실패
        }
        assertFalse(dest.exists()) // 미완성이라 완성 파일 없음
        assertTrue(File(dest.path + ".part").length() in 1 until data.size.toLong()) // 받은 만큼 보존
    }

    /**
     * 목적: 크기 검증(선언 크기 불일치)
     * 기능: 서버가 알려준 크기가 expectedBytes 와 다르면 IOException 으로 거부하고 dest 를 만들지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun sizeMismatchRejected() {
        server.serve("/f.bin", data) // 등록
        val dest = File(tmp.root, "f.bin") // 목적지
        try {
            runBlocking { downloader.download(server.url("/f.bin"), dest, data.size.toLong() - 1) { _, _ -> } } // 기대 크기가 다름
            fail("IOException 이 던져져야 합니다") // 도달하면 안 됨
        } catch (e: IOException) {
            // 기대한 실패
        }
        assertFalse(dest.exists()) // 완성 파일 없음
    }

    /**
     * 목적: 협력적 취소
     * 기능: 진행 중 코루틴을 취소하면 CancellationException 으로 끝나고 .part 를 지우지 않으며, 다시 호출하면 Range 로 이어받아 완성한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun cancelKeepsPartAndResumes() = runBlocking {
        server.serve("/f.bin", data) // 등록
        val dest = File(tmp.root, "f.bin") // 목적지
        lateinit var job: Job // 취소 대상
        var cancelled: Throwable? = null // 취소 결과
        job = launch(Dispatchers.IO) { // 받기 시작
            try {
                downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { d, _ -> if (d >= 60_000) job.cancel() } // 일정량 이후 취소
            } catch (e: CancellationException) {
                cancelled = e // 취소 기록
                throw e // 취소는 다시 던짐
            }
        }
        job.join() // 종료 대기
        assertTrue("CancellationException 이어야 함", cancelled is CancellationException) // 취소됨
        val partLen = File(dest.path + ".part").length() // 남은 부분
        assertTrue("part 가 남아 있어야 함($partLen)", partLen in 1 until data.size.toLong()) // 일부만
        assertFalse(dest.exists()) // 미완성
        downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { _, _ -> } // 이어받기
        assertEquals("bytes=$partLen-", server.requests.last().range) // 남은 위치부터 요청
        assertArrayEquals(data, dest.readBytes()) // 완성
    }

    /**
     * 목적: 이미 완성된 파일 재사용
     * 기능: dest 가 이미 expectedBytes 크기면 네트워크 요청 없이 끝난다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun alreadyCompleteSkipsNetwork() = runBlocking {
        val dest = File(tmp.root, "f.bin") // 목적지
        dest.writeBytes(data) // 이미 완성
        downloader.download(server.url("/f.bin"), dest, data.size.toLong()) { _, _ -> } // 호출
        assertTrue(server.requests.isEmpty()) // 요청 없음
    }
}
