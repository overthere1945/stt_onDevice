// 파일명: ResourceSamplerTest.kt
// 목적: ResourceSampler 의 PSS 피크·CPU 시간 계산, 표본 수, 생명주기 규칙을 가상 시간으로 검증(JVM)
// 기능: 가짜 읽기 함수를 주입하고 runTest + backgroundScope 로 200 ms 간격 표본을 재현한다.
package com.cowork.stt_ondevice.core.device // 기기·자원 측정 패키지

import kotlinx.coroutines.ExperimentalCoroutinesApi // 테스트 코루틴 API 표시
import kotlinx.coroutines.test.advanceTimeBy // 가상 시간 전진
import kotlinx.coroutines.test.runTest // 가상 시간 테스트
import org.junit.Assert.assertEquals // 값 비교
import org.junit.Assert.assertNull // null 확인
import org.junit.Assert.assertThrows // 예외 확인
import org.junit.Test // 테스트 표시

// change(add)-hyungchul-2026-10-10-21:58
@OptIn(ExperimentalCoroutinesApi::class)
class ResourceSamplerTest {

    /**
     * 목적: 호출 순서대로 값을 돌려주는 가짜 읽기 함수 만들기
     * 기능: 값 목록을 차례로 반환하고, 목록이 끝나면 마지막 값을 반복한다. 호출 횟수를 calls 로 센다.
     * 입력: values(순서대로 돌려줄 값)
     * 출력: 없음
     * 리턴: (읽기 함수, 호출 횟수 조회 함수)
     */
    private fun <T> sequenceReader(vararg values: T): Pair<() -> T, () -> Int> {
        var calls = 0 // 호출 횟수
        val reader: () -> T = { values[minOf(calls++, values.size - 1)] } // 순서대로 반환, 끝나면 마지막 값 반복
        return reader to { calls } // 읽기 함수와 호출 횟수 조회
    }

    @Test
    fun peakIsMaxOfSamples() = runTest {
        val (pss, _) = sequenceReader<Long?>(100 * 1024, 300 * 1024, 200 * 1024, 250 * 1024) // 0, 200, 400 ms 표본 + 마지막 표본
        val (cpu, _) = sequenceReader(0L)
        val sampler = ResourceSampler(pss, cpu, 200) // 200 ms 간격
        sampler.start(backgroundScope) // 시작 표본(100 MB)
        advanceTimeBy(450) // 200 ms(300 MB), 400 ms(200 MB) 표본 실행
        val stats = sampler.stop() // 마지막 표본(250 MB)
        assertEquals(300.0, stats.pssPeakMb!!, 1e-9) // 최댓값 300 MB
        assertEquals(4, stats.samples) // 시작 1 + 주기 2 + 종료 1
        assertEquals("Debug.getPss() 200ms 샘플링", stats.method) // 방법 문자열 기본값
    }

    @Test
    fun nullPssGivesNullPeak() = runTest {
        val (cpu, _) = sequenceReader(0L)
        val sampler = ResourceSampler({ null }, cpu, 200) // PSS 를 전혀 못 읽는 기기
        sampler.start(backgroundScope)
        advanceTimeBy(450)
        val stats = sampler.stop()
        assertNull(stats.pssPeakMb) // 전부 null 이면 피크도 null(0 아님)
        assertEquals(4, stats.samples) // 표본 시각은 그대로 센다
    }

    @Test
    fun someNullPssAreIgnored() = runTest {
        val (pss, _) = sequenceReader<Long?>(null, 512 * 1024, null) // null 섞인 읽기
        val (cpu, _) = sequenceReader(0L)
        val sampler = ResourceSampler(pss, cpu, 200)
        sampler.start(backgroundScope) // null
        advanceTimeBy(250) // 512 MB
        val stats = sampler.stop() // null
        assertEquals(512.0, stats.pssPeakMb!!, 1e-9) // 읽힌 값만 반영
    }

    @Test
    fun cpuTimeIsDelta() = runTest {
        val (cpu, _) = sequenceReader(5_000L, 6_000L, 7_500L) // 시작 5 s, 중간 6 s, 종료 7.5 s
        val sampler = ResourceSampler({ 1024L }, cpu, 200)
        sampler.start(backgroundScope) // 5000
        advanceTimeBy(250) // 6000
        val stats = sampler.stop() // 7500
        assertEquals(2.5, stats.cpuTimeSec, 1e-9) // 종료 - 시작 = 2.5 s
    }

    @Test
    fun immediateStopStillHasTwoSamples() = runTest {
        val (pss, _) = sequenceReader<Long?>(1024L, 2048L)
        val (cpu, _) = sequenceReader(100L, 130L)
        val sampler = ResourceSampler(pss, cpu, 200)
        sampler.start(backgroundScope)
        val stats = sampler.stop() // 시간 경과 없이 즉시 종료
        assertEquals(2, stats.samples) // 시작·종료 표본
        assertEquals(2.0, stats.pssPeakMb!!, 1e-9) // 2048 KB = 2 MB
        assertEquals(0.03, stats.cpuTimeSec, 1e-9) // 30 ms
    }

    @Test
    fun stopCancelsPeriodicSampling() = runTest {
        val (pss, calls) = sequenceReader<Long?>(1024L)
        val (cpu, _) = sequenceReader(0L)
        val sampler = ResourceSampler(pss, cpu, 200)
        sampler.start(backgroundScope)
        advanceTimeBy(450)
        sampler.stop()
        val callsAtStop = calls() // 종료 시점까지의 읽기 횟수
        advanceTimeBy(2_000) // 종료 후 시간이 흘러도
        assertEquals(callsAtStop, calls()) // 더 읽지 않는다
    }

    @Test
    fun lifecycleMisuseThrows() = runTest {
        val sampler = ResourceSampler({ 1024L }, { 0L }, 200)
        assertThrows(IllegalStateException::class.java) { sampler.stop() } // start 전 stop
        sampler.start(backgroundScope)
        assertThrows(IllegalStateException::class.java) { sampler.start(backgroundScope) } // 중복 start
        sampler.stop()
        assertThrows(IllegalStateException::class.java) { sampler.stop() } // 중복 stop
    }

    @Test
    fun nonPositiveIntervalRejected() {
        assertThrows(IllegalArgumentException::class.java) { ResourceSampler({ 1L }, { 0L }, 0) } // 0 간격 금지
    }
}
