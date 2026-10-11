// 파일명: AudioPipeDeviceTest.kt (기기 계측 테스트)
// 목적: 실제 기기에서 AudioPipe 의 쓰기 쪽 닫기가 다른 스레드에서 이미 막힌 write 를 IOException 으로 푸는지 확인 (2차 작업 6 리뷰 라운드 1)
// 기능: 엔진 프로세스가 읽기 쪽 사본을 쥐고 읽지 않는 상황을 readSide.dup() 으로 흉내 낸다. 작업 스레드가 3,200 B 블록을 계속 쓰다
//       300 ms 동안 진행이 없으면 막힘으로 보고(막힌 바이트 수 기록), 다른 스레드에서
//       - closeWriteUnblocksBlockedWriter: pipe.output.close()(= closeWrite, 세션 핵심이 실제로 부르는 경로)
//       - closeAllUnblocksBlockedWriter: pipe.closeAll()(읽기 쪽까지 닫음 — 원격 사본이 열려 있어 EPIPE 는 기대하지 않음)
//       를 부른 뒤 작업 스레드가 1 초 안에 IOException 으로 끝나는지 단언한다. 끝나면 dup 사본을 닫는다.
//       실행: adb shell am instrument -w -e class com.cowork.stt_ondevice.engine.system.AudioPipeDeviceTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner
// change(add)-hyungchul-2026-10-11-19:47
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지(계측 테스트)

import android.util.Log // 결과 로그
import androidx.test.ext.junit.runners.AndroidJUnit4 // JUnit4 계측 러너
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // 테스트 표시
import org.junit.runner.RunWith // 러너 지정
import java.io.IOException // 입출력 오류
import java.util.concurrent.atomic.AtomicLong // 쓴 바이트 수
import java.util.concurrent.atomic.AtomicReference // 작업 스레드 예외

// change(add)-hyungchul-2026-10-11-19:47
/**
 * 목적: AudioPipe 막힌 write 해제 기기 검증
 * 기능: 파일 머리말 참조.
 */
@RunWith(AndroidJUnit4::class)
class AudioPipeDeviceTest {

    /**
     * 목적: 쓰기 쪽 닫기(closeWrite) 경로 검증
     * 기능: 막힌 작업 스레드를 pipe.output.close() 로 푼다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun closeWriteUnblocksBlockedWriter() = runCase("closeWrite") { it.output.close() } // 세션 핵심 경로

    /**
     * 목적: 양쪽 닫기(closeAll) 경로 검증
     * 기능: 막힌 작업 스레드를 pipe.closeAll() 로 푼다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun closeAllUnblocksBlockedWriter() = runCase("closeAll") { it.closeAll() } // 결론·abort 경로

    /**
     * 목적: 공통 시험 절차
     * 기능: ① 파이프 생성 + 읽기 쪽 dup(원격 사본 흉내, 읽지 않음) ② 작업 스레드가 3,200 B 블록을 계속 씀, 300 ms 진행 없음 = 막힘
     *       ③ 다른 스레드(이 테스트 스레드)에서 closeOp ④ join(1000) 후 살아 있지 않고 예외가 IOException 인지 단언 ⑤ dup·나머지 닫기.
     * 입력: label(로그 이름), closeOp(막힌 쓰기를 풀 닫기 동작)
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    private fun runCase(label: String, closeOp: (AudioPipe) -> Unit) {
        val pipe = AudioPipe.create() // 파이프
        val remote = pipe.readSide.dup() // 엔진 프로세스가 쥔 읽기 쪽 사본 흉내(읽지 않음)
        val written = AtomicLong(0) // 쓴 바이트
        val error = AtomicReference<Throwable?>(null) // 작업 스레드가 끝난 이유
        val writer = Thread({ // 공급기 흉내
            val block = ByteArray(3_200) // 100 ms 블록
            try {
                while (true) { // 막히거나 오류가 날 때까지
                    pipe.output.write(block, 0, block.size) // 쓰기(가득 차면 막힘)
                    written.addAndGet(block.size.toLong()) // 진행 기록
                }
            } catch (t: Throwable) {
                error.set(t) // 끝난 이유 기록
            }
        }, "audio-pipe-writer") // 스레드 이름
        writer.isDaemon = true // 실패해도 프로세스 종료를 막지 않음
        try {
            writer.start() // 쓰기 시작
            var last = -1L // 직전 관찰 값
            var stableSince = System.nanoTime() // 값이 멈춘 시각
            val deadline = System.nanoTime() + 5_000_000_000L // 막힘 탐지 최대 5 초
            while (System.nanoTime() < deadline) { // 막힐 때까지 관찰
                val now = written.get() // 현재 진행
                if (now != last) { last = now; stableSince = System.nanoTime() } // 진행 중
                else if (System.nanoTime() - stableSince >= 300_000_000L) break // 300 ms 진행 없음 = 막힘
                Thread.sleep(20) // 관찰 간격
            }
            val blockedAt = written.get() // 막힌 시점의 쓴 바이트
            log("$label: 막힘 탐지 written=$blockedAt B alive=${writer.isAlive} error=${error.get()}") // 기록
            assertTrue("$label: 작업 스레드가 막혀 있어야 함(error=${error.get()})", writer.isAlive) // 막힘 확인
            assertTrue("$label: 파이프 버퍼 근처에서 막혀야 함(written=$blockedAt)", blockedAt in 16_000L..1_048_576L) // 버퍼 크기 범위
            val t0 = System.nanoTime() // 닫기 시각
            closeOp(pipe) // 다른 스레드에서 닫기
            writer.join(1_000) // 최대 1 초 대기
            val ms = (System.nanoTime() - t0) / 1_000_000 // 풀리기까지 시간
            log("$label: 닫기 후 ${ms}ms alive=${writer.isAlive} 예외=${error.get()?.javaClass?.name}: ${error.get()?.message}") // 기록
            assertFalse("$label: 닫기 후 1 초 안에 작업 스레드가 끝나야 함", writer.isAlive) // 풀림
            assertTrue("$label: IOException 으로 끝나야 함(실제 ${error.get()})", error.get() is IOException) // 예외 종류
        } finally {
            runCatching { remote.close() } // dup 사본 닫기
            pipe.closeAll() // 나머지 닫기(실패 시에도 읽기 쪽이 모두 닫혀 EPIPE 로 작업 스레드 정리)
        }
    }

    /**
     * 목적: 결과 로그
     * 기능: logcat 에 남긴다.
     * 입력: msg(내용)
     * 출력: logcat
     * 리턴: 없음
     */
    private fun log(msg: String) {
        Log.i(TAG, msg) // 기록
    }

    companion object {
        private const val TAG = "AudioPipeDeviceTest" // logcat 태그
    }
}
