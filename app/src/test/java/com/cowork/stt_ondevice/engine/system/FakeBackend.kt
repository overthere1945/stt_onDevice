// 파일명: FakeBackend.kt
// 목적: SystemAsrEngine 테스트용 가짜 인식 백엔드(ML Kit·플랫폼 인식기 대신) (2차 작업 3)
// 기능: 세션마다 각본(SessionScript)대로 응답한다 — 지정 시점(세션 시작 기준 ms)에 결과, 공급 끝(finishInput) 직후 결과, 또는 응답 없음(abort 까지 대기).
//       받은 바이트 수·audio 닫힘·finishInput/abort 호출 횟수와 첫 abort 시각, 백엔드 close 횟수·열린 언어 태그를 기록한다.
//       change(add)-hyungchul-2026-10-11-18:51 실제 스레드 테스트용으로 audio 스트림을 바꿔 끼울 수 있게 하고(audioFactory), 세션·백엔드 메서드를 스레드 안전(@Synchronized)하게 함.
//       change(add)-hyungchul-2026-10-11-19:18 open 자체가 끝나지 않는 각본(SessionScript.OPEN_HANG — 취소될 때까지 대기)을 추가(2차 작업 6, open 시간 제한 시험).
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 테스트 패키지

import kotlinx.coroutines.CompletableDeferred // 한 번 완료되는 신호
import kotlinx.coroutines.awaitCancellation // 취소될 때까지 대기 // change(add)-hyungchul-2026-10-11-19:18
import kotlinx.coroutines.delay // 가상 시간 대기
import java.io.IOException // 입출력 오류
import java.io.OutputStream // 출력 스트림

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 세션 1개의 응답 각본
 * 기능: outcome 이 null 이면 응답 없음(abort 될 때까지 끝나지 않음).
 *       atMs 가 null 이면 finishInput 직후 응답, 값이 있으면 awaitOutcome 호출 후 그 시간(가상 ms)에 응답한다.
 *       change(add)-hyungchul-2026-10-11-19:18 hangOnOpen 이 true 면 open 이 세션을 돌려주지 않고 코루틴이 취소될 때까지 기다린다.
 * 입력: outcome(돌려줄 결과 또는 null), atMs(응답 시점 또는 null), hangOnOpen(open 멈춤 여부)
 */
data class SessionScript(
    val outcome: SessionOutcome?, // 결과(null = 응답 없음)
    val atMs: Long? = null, // 응답 시점(null = 공급 끝 직후)
    val hangOnOpen: Boolean = false, // open 멈춤(취소될 때까지) // change(add)-hyungchul-2026-10-11-19:18
) {
    companion object {
        /** 목적: 응답 없음 각본 / 기능: abort 될 때까지 결과를 내지 않는다 */
        val NO_RESPONSE = SessionScript(null) // 응답 없음

        /** 목적: open 멈춤 각본 / 기능: open 이 취소될 때까지 돌아오지 않는다 */ // change(add)-hyungchul-2026-10-11-19:18
        val OPEN_HANG = SessionScript(null, hangOnOpen = true) // open 멈춤 // change(add)-hyungchul-2026-10-11-19:18
    }
}

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 받은 바이트·닫힘 기록용 출력 스트림
 * 기능: 막히지 않고 바이트 수만 센다. 닫힌 뒤 쓰면 IOException(실제 파이프와 같음).
 */
class RecordingOutputStream : OutputStream() {
    @Volatile var bytes: Long = 0 // 받은 바이트 수
    @Volatile var closed: Boolean = false // 닫힘 여부

    /**
     * 목적: 1 바이트 쓰기
     * 기능: 닫혔으면 IOException, 아니면 1 바이트 센다.
     * 입력: b(바이트 값)
     * 출력: 닫힌 뒤면 IOException
     * 리턴: 없음
     */
    override fun write(b: Int) {
        if (closed) throw IOException("닫힌 파이프") // 닫힌 뒤 쓰기
        bytes += 1 // 1 바이트
    }

    /**
     * 목적: 블록 쓰기
     * 기능: 닫혔으면 IOException, 아니면 len 바이트 센다.
     * 입력: b(버퍼), off(시작 위치), len(길이)
     * 출력: 닫힌 뒤면 IOException
     * 리턴: 없음
     */
    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("닫힌 파이프") // 닫힌 뒤 쓰기
        bytes += len // len 바이트
    }

    /**
     * 목적: 닫기
     * 기능: 닫힘을 기록한다(여러 번 불러도 안전).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        closed = true // 닫힘 기록
    }
}

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 각본대로 응답하는 가짜 세션
 * 기능: awaitOutcome 은 각본대로 결과를 내고, 응답 없음 각본은 abort 되면 ENGINE_ERROR 로 끝난다.
 * 입력: script(응답 각본), now(시계, ms), customAudio(공급 쪽을 바꿀 스트림, null 이면 RecordingOutputStream) // change(add)-hyungchul-2026-10-11-18:51
 */
class FakeSession(
    private val script: SessionScript, // 응답 각본
    private val now: () -> Long, // 시계(ms)
    private val customAudio: OutputStream? = null, // 바꿔 끼울 공급 스트림 // change(add)-hyungchul-2026-10-11-18:51
) : RecognitionSession {
    val recorder = RecordingOutputStream() // 공급 바이트 기록
    override val audio: OutputStream get() = customAudio ?: recorder // 공급 쪽(지정 스트림 우선) // change(add)-hyungchul-2026-10-11-18:51
    private val inputDone = CompletableDeferred<Unit>() // 공급 끝 신호
    private val aborted = CompletableDeferred<Unit>() // 중단 신호
    var finishCount = 0 // finishInput 호출 수
    var abortCount = 0 // abort 호출 수
    var firstAbortAtMs: Long? = null // 첫 abort 가상 시각(ms)

    /**
     * 목적: 결과 대기
     * 기능: 각본에 따라 지정 시점·공급 끝 직후에 결과를 내거나, 응답 없음이면 abort 까지 기다린다.
     * 입력: 없음
     * 출력: 코루틴 취소 시 CancellationException
     * 리턴: SessionOutcome
     */
    override suspend fun awaitOutcome(): SessionOutcome {
        val outcome = script.outcome // 각본 결과
        if (outcome == null) { // 응답 없음
            aborted.await() // abort 까지 대기
            return SessionOutcome.Failed("ENGINE_ERROR", "세션 중단됨") // 중단 후 오류로 끝
        }
        val at = script.atMs // 응답 시점
        if (at != null) delay(at) else inputDone.await() // 지정 시점 또는 공급 끝까지 대기
        return outcome // 결과
    }

    /**
     * 목적: 공급 끝 알림
     * 기능: 호출 수를 세고 공급 끝 신호를 완료한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Synchronized // 스레드 안전 // change(add)-hyungchul-2026-10-11-18:51
    override fun finishInput() {
        finishCount++ // 호출 수
        inputDone.complete(Unit) // 공급 끝 신호
    }

    /**
     * 목적: 즉시 중단
     * 기능: 호출 수와 첫 호출 시각을 기록하고 중단 신호를 완료한다(여러 번 안전).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Synchronized // 스레드 안전 // change(add)-hyungchul-2026-10-11-18:51
    override fun abort() {
        abortCount++ // 호출 수
        if (firstAbortAtMs == null) firstAbortAtMs = now() // 첫 시각
        aborted.complete(Unit) // 중단 신호
    }
}

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 가짜 인식 백엔드
 * 기능: open 할 때마다 공유 각본 큐에서 하나를 꺼내 FakeSession 을 만든다(재생성된 백엔드도 같은 큐를 이어 씀).
 * 입력: scripts(세션 순서대로의 각본 큐, 여러 백엔드가 공유), now(시계, ms),
 *       audioFactory(세션마다 공급 스트림을 만드는 함수, null 이면 기록용 스트림) // change(add)-hyungchul-2026-10-11-18:51
 */
class FakeBackend(
    private val scripts: ArrayDeque<SessionScript>, // 공유 각본 큐
    private val now: () -> Long, // 시계(ms)
    private val audioFactory: (() -> OutputStream)? = null, // 공급 스트림 생성 // change(add)-hyungchul-2026-10-11-18:51
) : RecognitionBackend {
    val sessions = ArrayList<FakeSession>() // 이 백엔드가 연 세션
    val openedLangs = ArrayList<String>() // 열 때 받은 언어 태그
    var closeCount = 0 // close 호출 수

    /**
     * 목적: 세션 열기
     * 기능: 언어 태그를 기록하고 다음 각본으로 세션을 만든다. change(add)-hyungchul-2026-10-11-19:18 각본이 OPEN_HANG 이면 세션을 만들지 않고 취소될 때까지 기다린다.
     * 입력: langTag(BCP-47 언어 태그)
     * 출력: 각본이 남지 않았으면 IllegalStateException, 멈춤 각본이 취소되면 CancellationException
     * 리턴: FakeSession
     */
    override suspend fun open(langTag: String): RecognitionSession {
        val script = synchronized(scripts) { // 공유 큐 보호 // change(add)-hyungchul-2026-10-11-18:51
            openedLangs += langTag // 언어 기록
            scripts.removeFirstOrNull() ?: error("각본이 더 없음") // 다음 각본
        }
        if (script.hangOnOpen) awaitCancellation() // open 멈춤 — 취소(감시 시간 초과)될 때까지 // change(add)-hyungchul-2026-10-11-19:18
        return synchronized(scripts) { FakeSession(script, now, audioFactory?.invoke()).also { sessions += it } } // 세션 기록(지정 스트림 전달)
    }

    /**
     * 목적: 백엔드 닫기
     * 기능: 호출 수만 센다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Synchronized // 스레드 안전 // change(add)-hyungchul-2026-10-11-18:51
    override fun close() {
        closeCount++ // 호출 수
    }
}
