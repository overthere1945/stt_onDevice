// 파일명: ResourceSampler.kt
// 목적: 전사 1건 동안의 메모리(PSS) 피크와 CPU 사용 시간을 측정한다(스펙 §10: rss_peak_mb = PSS peak 200 ms 샘플링, cpu_time_sec = 프로세스 CPU 시간).
// 기능: - start(scope): 즉시 첫 표본을 동기로 읽고, scope 안의 코루틴이 intervalMs 마다 표본을 추가로 읽는다.
//       - stop(): 반복을 취소하고 마지막 표본을 읽은 뒤 ResourceStats 를 돌려준다(최소 2 표본 보장).
//       - 읽기 함수(readPssKb/readCpuMs)를 주입받아 JVM 테스트에서 가짜로 바꿀 수 있다. 실제 기기용 기본 읽기 함수는 defaultReadPssKb/defaultReadCpuMs.
// 비용 주의: android.os.Debug.getPss() 는 /proc/self/smaps_rollup 등을 읽어 호출당 수 ms 걸릴 수 있다. 200 ms 간격이면 약 1~3 % 의 한 코어 부하이며,
//           표본 사이의 짧은 피크는 놓칠 수 있다(그래서 ResourceStats.method 에 "200ms 샘플링"을 명시한다). 간격을 더 줄이면 측정 자체가 결과를 흔든다.
// 스레드 안전: 표본 상태는 lock 으로 보호하고, stop() 이후에는 어떤 표본도 추가하지 않는다.
package com.cowork.stt_ondevice.core.device // 기기·자원 측정 패키지

import android.os.Debug // PSS 읽기
import android.os.Process // 프로세스 CPU 시간 읽기
import kotlinx.coroutines.CoroutineScope // 코루틴 범위
import kotlinx.coroutines.Job // 코루틴 작업 핸들
import kotlinx.coroutines.delay // 가상 시간 지원 지연
import kotlinx.coroutines.isActive // 코루틴 활성 여부
import kotlinx.coroutines.launch // 코루틴 시작

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: 측정 결과 값 객체
 * 기능: PSS 피크(MB)·CPU 시간(초)·표본 수·측정 방법 문자열을 담는다.
 * 입력: pssPeakMb(모든 PSS 읽기가 null 이면 null — 0 으로 채우지 않는다), cpuTimeSec(시작~종료 CPU 시간 차이, 초),
 *       samples(표본 시각 수 = 첫 표본 + 주기 표본 + 마지막 표본), method(결과 JSON 에 남길 측정 방법 설명)
 */
data class ResourceStats(
    val pssPeakMb: Double?, // PSS 피크(MB), 측정 불가면 null
    val cpuTimeSec: Double, // CPU 시간(초)
    val samples: Int, // 표본 시각 수
    val method: String = "Debug.getPss() 200ms 샘플링", // 측정 방법 기록
)

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: PSS 피크·CPU 시간 샘플러
 * 기능: start() 에서 즉시 1회, 이후 intervalMs 마다, stop() 에서 마지막 1회 표본을 읽는다. 1회용 객체(start/stop 각 1회).
 * 입력: readPssKb(현재 PSS KB, 읽을 수 없으면 null), readCpuMs(프로세스 누적 CPU ms), intervalMs(표본 간격, 기본 200 ms)
 * 주의: start 는 한 번만, stop 은 start 이후 한 번만 호출할 수 있다. 어긋나면 IllegalStateException(조용히 같은 값을 돌려주지 않는다).
 */
class ResourceSampler(
    private val readPssKb: () -> Long? = ::defaultReadPssKb, // PSS 읽기(KB)
    private val readCpuMs: () -> Long = ::defaultReadCpuMs, // CPU 시간 읽기(ms)
    private val intervalMs: Long = 200, // 표본 간격(ms)
) {
    private val lock = Any() // 표본 상태 보호용 잠금
    private var job: Job? = null // 주기 표본 코루틴(start 후 설정)
    private var started = false // start 호출 여부
    private var stopped = false // stop 호출 여부(이후 표본 추가 금지)
    private var firstCpuMs = 0L // 첫 표본의 CPU ms
    private var lastCpuMs = 0L // 가장 최근 표본의 CPU ms
    private var peakPssKb: Long? = null // 지금까지 본 PSS 최댓값(KB), 전부 null 이면 null 유지
    private var sampleCount = 0 // 표본 시각 수

    init {
        require(intervalMs > 0) { "intervalMs 는 양수여야 합니다: $intervalMs" } // 0 이하면 delay 가 바쁜 루프가 된다
    }

    /**
     * 목적: 측정 시작
     * 기능: 호출 스레드에서 첫 표본을 즉시 읽고(짧은 실행도 시작 표본이 보장됨), scope 에서 주기 표본 코루틴을 띄운다.
     *       코루틴은 scope 의 디스패처를 그대로 쓴다. 디스패처가 없는 scope(예: CoroutineScope(Job()))면 launch 가 Dispatchers.Default 를 쓴다.
     *       테스트에서는 TestScope 의 backgroundScope 를 넘겨 가상 시간으로 돌린다.
     * 입력: scope(주기 표본 코루틴을 소유할 범위; 취소되면 반복도 멈춘다)
     * 출력: 이미 start 했으면 IllegalStateException
     * 리턴: 없음
     */
    fun start(scope: CoroutineScope) {
        synchronized(lock) { // 상태 변경 보호
            check(!started) { "ResourceSampler 는 한 번만 start 할 수 있습니다" } // 재사용 금지
            started = true // 시작 표시
            sampleLocked(first = true) // 첫 표본(동기)
        }
        job = scope.launch { // 주기 표본 코루틴 시작
            while (isActive) { // 취소될 때까지 반복
                delay(intervalMs) // 다음 표본까지 대기(가상 시간 가능)
                synchronized(lock) { // 표본 추가 보호
                    if (stopped) return@launch // stop 이후면 표본 없이 종료
                    sampleLocked(first = false) // 주기 표본
                }
            }
        }
    }

    /**
     * 목적: 측정 종료와 결과 반환
     * 기능: 주기 코루틴을 취소하고, 마지막 표본을 호출 스레드에서 읽은 뒤 통계를 만든다.
     *       이 시점 이후 주기 코루틴이 표본을 추가하지 못하도록 stopped 를 먼저 세운다.
     * 입력: 없음
     * 출력: start 전이거나 이미 stop 했으면 IllegalStateException
     * 리턴: ResourceStats(pssPeakMb 는 PSS 읽기가 전부 null 이면 null)
     */
    fun stop(): ResourceStats {
        val stats: ResourceStats
        synchronized(lock) { // 상태 변경 보호
            check(started) { "start 하기 전에 stop 할 수 없습니다" } // 시작 전 호출 금지
            check(!stopped) { "stop 은 한 번만 호출할 수 있습니다" } // 중복 호출 금지
            stopped = true // 이후 주기 표본 차단
            job?.cancel() // 반복 취소
            sampleLocked(first = false) // 마지막 표본
            stats = ResourceStats( // 결과 조립
                pssPeakMb = peakPssKb?.let { it / 1024.0 }, // KB → MB, 전부 null 이면 null
                cpuTimeSec = (lastCpuMs - firstCpuMs) / 1000.0, // CPU ms 차이 → 초
                samples = sampleCount, // 표본 시각 수
            )
        }
        return stats
    }

    /**
     * 목적: 표본 1회 읽기(잠금을 쥔 상태에서만 호출)
     * 기능: PSS 를 읽어 null 이 아니면 최댓값을 갱신하고, CPU ms 를 읽어 첫/마지막 값을 갱신하고 표본 수를 올린다.
     * 입력: first(첫 표본이면 true — CPU 기준값으로 저장)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun sampleLocked(first: Boolean) {
        val pss = readPssKb() // 현재 PSS(KB) 또는 null
        if (pss != null) peakPssKb = maxOf(peakPssKb ?: pss, pss) // 읽힌 값만 최댓값에 반영
        val cpu = readCpuMs() // 현재 프로세스 누적 CPU ms
        if (first) firstCpuMs = cpu // 첫 표본이면 기준값 저장
        lastCpuMs = cpu // 가장 최근 CPU 값 갱신
        sampleCount++ // 표본 시각 수 증가
    }
}

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: 기기용 기본 PSS 읽기
 * 기능: android.os.Debug.getPss()(이 프로세스의 PSS, KB)를 읽는다. 호출당 수 ms 걸릴 수 있다.
 * 입력: 없음
 * 출력: 없음
 * 리턴: PSS KB. 0 이하이면 읽기 실패로 보고 null(0 으로 채우지 않는다)
 */
fun defaultReadPssKb(): Long? = Debug.getPss().takeIf { it > 0L } // 0 은 측정 실패 → null

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: 기기용 기본 CPU 시간 읽기
 * 기능: android.os.Process.getElapsedCpuTime()(이 프로세스 시작 후 누적 CPU 시간, ms)을 읽는다.
 * 입력: 없음
 * 출력: 없음
 * 리턴: 누적 CPU ms
 */
fun defaultReadCpuMs(): Long = Process.getElapsedCpuTime() // 프로세스 누적 CPU ms
