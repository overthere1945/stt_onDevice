// 파일명: PacedPcmFeeder.kt
// 목적: 시스템 엔진에 PCM16 을 "실시간 × 배속" 속도로 공급하는 공급기 (2차 작업 3)
// 기능: 3,200 B(100 ms) 블록을 절대 일정 start + i × (100 ÷ 배속) ms 에 맞춰 출력 스트림(파이프 쓰기 쪽)에 쓴다.
//       쓰기 IOException(엔진이 파이프를 닫음 등)은 던지지 않고 completed=false 로 돌려준다. 블록 1개 쓰기에 걸린 최대 시간(막힘 정도)을 잰다.
//       대기는 주입된 delayMs(기본 delay — 코루틴 취소에 즉시 반응)만 쓰고 Thread.sleep 은 쓰지 않는다.
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import kotlinx.coroutines.currentCoroutineContext // 현재 코루틴 문맥
import kotlinx.coroutines.delay // 코루틴 대기
import kotlinx.coroutines.ensureActive // 취소 확인
import java.io.IOException // 입출력 오류
import java.io.OutputStream // 출력 스트림

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 공급 결과
 * 기능: 실제로 쓴 바이트 수, 블록 1개 쓰기의 최대 소요 시간(ms), 끝까지 썼는지 여부를 담는다.
 * 입력: writtenBytes(쓴 바이트), maxBlockMs(블록 쓰기 최대 ms), completed(모두 썼으면 true)
 */
data class FeedStats(
    val writtenBytes: Long, // 쓴 바이트 수
    val maxBlockMs: Long, // 블록 쓰기 최대 시간(ms)
    val completed: Boolean, // 끝까지 씀
)

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 배속 공급기
 * 기능: feed() 하나로 PCM 을 블록 단위로 일정에 맞춰 쓴다.
 * 입력: delayMs(대기 함수, 기본 delay), nanoTime(단조 시계 ns, 기본 System.nanoTime — 테스트는 가상 시계 주입)
 */
class PacedPcmFeeder(
    private val delayMs: suspend (Long) -> Unit = { delay(it) }, // 대기 함수
    private val nanoTime: () -> Long = System::nanoTime, // 단조 시계(ns)
) {
    /**
     * 목적: PCM 배속 공급
     * 기능: 블록 i 를 start + i × (100 ms ÷ speed) 에 쓴다(앞 블록이 늦어도 일정은 밀리지 않는 절대 일정). 마지막 블록은 남은 바이트만 쓴다.
     *       쓰기 IOException 이면 그 자리에서 멈추고 completed=false 로 돌려준다. 코루틴이 취소되면 CancellationException 이 그대로 전파된다.
     * 입력: out(공급 대상 스트림), pcm(PCM16 LE 바이트), speed(배속, 1 이상)
     * 출력: speed < 1 → IllegalArgumentException, 취소 → CancellationException
     * 리턴: FeedStats
     */
    suspend fun feed(out: OutputStream, pcm: ByteArray, speed: Int): FeedStats {
        require(speed >= 1) { "배속은 1 이상이어야 함: $speed" } // 인자 검사
        val intervalNs = PcmFrames.BLOCK_MS * 1_000_000L / speed // 블록 간격(ns)
        val start = nanoTime() // 일정 기준 시각
        var written = 0L // 쓴 바이트
        var maxBlockNs = 0L // 블록 쓰기 최대(ns)
        var offset = 0 // 다음 블록 시작 위치
        var i = 0L // 블록 순번
        while (offset < pcm.size) { // 남은 바이트가 있는 동안
            val waitNs = start + i * intervalNs - nanoTime() // 이 블록 예정 시각까지 남은 시간
            if (waitNs > 0) delayMs((waitNs + 999_999L) / 1_000_000L) // 올림 ms 대기(취소 가능)
            currentCoroutineContext().ensureActive() // 대기 없이 지나가도 취소 확인
            val len = minOf(PcmFrames.BLOCK_BYTES, pcm.size - offset) // 이번 블록 길이
            val w0 = nanoTime() // 쓰기 시작
            try {
                out.write(pcm, offset, len) // 블록 쓰기(파이프가 차면 막힐 수 있음)
            } catch (e: IOException) { // 엔진이 파이프를 닫음 등
                return FeedStats(written, maxBlockNs / 1_000_000L, completed = false) // 던지지 않고 미완료 반환
            }
            maxBlockNs = maxOf(maxBlockNs, nanoTime() - w0) // 블록 쓰기 최대 갱신
            written += len // 누적
            offset += len // 다음 위치
            i++ // 다음 블록
        }
        try {
            out.flush() // 버퍼가 있는 스트림이면 밀어내기
        } catch (e: IOException) { // 플러시 실패
            return FeedStats(written, maxBlockNs / 1_000_000L, completed = false) // 미완료 반환
        }
        return FeedStats(written, maxBlockNs / 1_000_000L, completed = true) // 완료
    }
}
