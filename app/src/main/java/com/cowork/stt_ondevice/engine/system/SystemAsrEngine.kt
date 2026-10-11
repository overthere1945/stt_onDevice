// 파일명: SystemAsrEngine.kt
// 목적: 시스템 엔진(ML Kit GenAI Basic·Android 온디바이스 SpeechRecognizer)을 AsrEngine 계약으로 감싸는 세션 핵심 (2차 작업 3)
// 기능: prepare — backendFactory 로 백엔드를 만들고 PrepareInfo(로드 시간, versionInfo(), kind.backend, 정밀도 "", 스레드 0)를 돌려준다.
//       transcribe — chunk 를 PCM16 LE 로 바꿔 세션을 열고, io 에서 배속 공급(PacedPcmFeeder) → 공급 끝이면 finishInput →
//                    감시 시간(ceil(조각 ms ÷ 배속) + 15 초) 안에 결과를 기다린다. 결과가 오면 즉시 공급 코루틴을 취소하고 audio 를 닫는다
//                    (엔진이 먼저 끝났을 때 공급기가 파이프 쓰기에서 막히지 않게 — 실측 교훈).
//                    감시 시간 초과면 세션 abort → 백엔드를 closeTimeoutMs 제한으로 닫고(넘으면 기다리지 않음) 새로 만들어 같은 조각으로 1회 재시도,
//                    또 초과면 EngineStalledException. Failed(code) 는 EngineException(TRANSCRIBE, code), 공급 끝 전 Completed 는 PIPE_NOT_CONSUMED.
//       cancel — 현재 세션 abort + 공급 코루틴 취소(진행 중 transcribe 는 ABORTED). close — 현재 세션 중단 후 백엔드 해제.
//       Android 에 의존하지 않으며, 실제 ML Kit/플랫폼 백엔드는 작업 6 이 RecognitionBackend 로 구현해 붙인다.
//       change(add)-hyungchul-2026-10-11-18:51 공급 코루틴을 attempt 의 자식이 아니라 엔진 소유 범위(workScope)에서 돌리고 결코 join 하지 않는다 —
//       파이프 write 가 막혀 audio.close() 로도 풀리지 않는 최악의 경우에도 감시·재시도·취소가 정해진 시간 안에 돌아온다(리뷰 라운드 1).
//       change(add)-hyungchul-2026-10-11-19:18 (2차 작업 6) 세션 열기(backend.open)도 그 조각의 감시 시간으로 묶어, 넘으면 응답 없음과 같게 처리한다(재생성 1회 재시도 → 또 넘으면 멈춤 예외).
//       엔진 소유 범위에 CoroutineExceptionHandler 를 달고 공급 코루틴이 Throwable 까지 잡아(abort 가 던져도) 공급 쪽 예외가 앱 프로세스를 죽이지 않게 한다.
//       LANG_TAGS 를 같은 모듈에서 쓰도록 internal 로 연다(SystemEngines 의 실행 전 언어 확인).
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // 전사 결과
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.engine.SessionInfo // 세션 기록
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SystemEngineConfig // 시스템 엔진 설정
import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import kotlinx.coroutines.CompletableDeferred // 완료 신호
import kotlinx.coroutines.CoroutineExceptionHandler // 분리 범위의 처리 안 된 예외 흡수 // change(add)-hyungchul-2026-10-11-19:18
import kotlinx.coroutines.CoroutineDispatcher // 디스패처 타입
import kotlinx.coroutines.CoroutineScope // 분리된 코루틴 범위
import kotlinx.coroutines.Dispatchers // 기본 디스패처
import kotlinx.coroutines.Job // 공급 코루틴 핸들
import kotlinx.coroutines.SupervisorJob // 실패 격리 Job // change(add)-hyungchul-2026-10-11-18:51
import kotlinx.coroutines.cancel // 범위 취소 // change(add)-hyungchul-2026-10-11-18:51
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withTimeoutOrNull // 제한 시간 대기
import java.util.concurrent.atomic.AtomicBoolean // 스레드 안전 플래그
import java.util.concurrent.atomic.AtomicInteger // 스레드 안전 카운터
import java.util.concurrent.atomic.AtomicReference // 스레드 안전 참조
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소 // change(add)-hyungchul-2026-10-11-18:51
import kotlin.math.ceil // 올림

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 시스템 엔진 AsrEngine 구현
 * 기능: 위 파일 머리 설명 참조. 세션 번호는 엔진 인스턴스의 누적 순번(재시도 포함, 1부터)이다.
 * 입력: spec(engineConfig 는 SystemEngineConfig 여야 함), backendFactory(백엔드 생성 함수 — prepare 1회 + 멈춤 재생성 시 1회씩),
 *       versionInfo(engine_version 문자열 공급 함수), io(공급·백엔드 닫기 디스패처), feeder(배속 공급기),
 *       clock(단조 시계 ns, 추론·로드 시간 측정), closeTimeoutMs(재생성 시 이전 백엔드 닫기 제한 시간)
 * 출력: engineConfig 가 SystemEngineConfig 가 아니면 EngineException(MODEL_LOAD, CONFIG_MISMATCH)
 */
class SystemAsrEngine(
    override val spec: ModelSpec, // 모델 메타
    private val backendFactory: () -> RecognitionBackend, // 백엔드 생성
    private val versionInfo: () -> String, // 엔진 버전 문자열
    private val io: CoroutineDispatcher = Dispatchers.IO, // 공급 디스패처
    private val feeder: PacedPcmFeeder = PacedPcmFeeder(), // 배속 공급기
    private val clock: () -> Long = System::nanoTime, // 단조 시계(ns)
    private val closeTimeoutMs: Long = 5_000, // 백엔드 닫기 제한(ms)
) : AsrEngine {

    private val config: SystemEngineConfig = spec.engineConfig as? SystemEngineConfig // 시스템 엔진 설정
        ?: throw EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "시스템 엔진 스펙이 아님(engineConfig=${spec.engineConfig}): ${spec.id}") // 설정 불일치
    private val kind: SystemEngineKind = config.kind // 엔진 종류
    private val speed: Int = config.feedSpeed // 공급 배속
    @Volatile private var backend: RecognitionBackend? = null // 현재 백엔드(null = 준비 전/닫힘)
    @Volatile private var closed = false // close 호출 여부
    private val sessionSeq = AtomicInteger(0) // 누적 세션 순번
    private val active = AtomicReference<ActiveSession?>(null) // 진행 중 세션(cancel 대상)
    private val cancelRequested = AtomicBoolean(false) // 이번 transcribe 의 중단 요청
    // change(add)-hyungchul-2026-10-11-18:51: 공급·백엔드 닫기를 돌리는 엔진 소유 범위(attempt 가 join 하지 않으므로 막힌 write 가 transcribe 를 붙잡지 못함)
    private val workScope = CoroutineScope(SupervisorJob() + io + IGNORE_UNCAUGHT) // 엔진 소유 작업 범위 // change(add)-hyungchul-2026-10-11-19:18 처리 안 된 예외가 프로세스를 죽이지 않게
    /** 목적: 감시 여유 시간(ms) / 기능: 기본 15 초. 실제 스레드 테스트에서만 짧게 바꾼다 */
    internal var watchdogMarginMs: Long = WATCHDOG_MARGIN_MS // 감시 여유(테스트용 조정 가능) // change(add)-hyungchul-2026-10-11-18:51

    /**
     * 목적: 진행 중 세션 묶음
     * 기능: cancel/close 가 다른 스레드에서 세션 abort 와 공급 중단을 함께 할 수 있게 세션과 공급 코루틴을 묶는다.
     * 입력: session(인식 세션), feedJob(공급 코루틴)
     */
    private class ActiveSession(val session: RecognitionSession, val feedJob: Job)

    /**
     * 목적: 세션 1회 성공 결과
     * 기능: 엔진 조각 목록·공급 통계·추론 시간·세션 번호를 담는다.
     * 입력: segments(텍스트 조각), stats(공급 통계), inferSec(세션 시작~결과 수신, 초), number(세션 번호)
     */
    private class Attempt(val segments: List<String>, val stats: FeedStats, val inferSec: Double, val number: Int)

    /**
     * 목적: 엔진 준비
     * 기능: 닫힘·중복 준비를 확인하고 backendFactory 로 백엔드를 만든다. 생성 시간을 로드 시간으로 잰다.
     * 입력: threads(시스템 엔진은 쓰지 않음)
     * 출력: EngineException(MODEL_LOAD, CLOSED/ALREADY_PREPARED/LOAD_FAILED)
     * 리턴: PrepareInfo(loadSec, versionInfo(), kind.backend, precision "", threads 0)
     */
    override suspend fun prepare(threads: Int): PrepareInfo {
        if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 재사용 금지
        if (backend != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 중복 준비 금지
        val t0 = clock() // 시작 시각
        val b = createBackend("MODEL_LOAD") // 백엔드 생성
        val loadSec = (clock() - t0) / 1e9 // 생성 시간(초)
        backend = b // 공개
        if (closed) { // 생성 중 close 가 불렸으면
            backend = null // 회수
            b.close() // 즉시 해제
            throw EngineException("MODEL_LOAD", "CLOSED", "준비 중 엔진이 닫힘: ${spec.id}") // 닫힘 보고
        }
        return PrepareInfo(loadSec = loadSec, runtimeVersion = versionInfo(), backend = kind.backend, precision = "", threads = 0) // 준비 결과
    }

    /**
     * 목적: chunk 1개 전사
     * 기능: 첫 세션을 시도하고, 감시 시간 초과면 백엔드를 새로 만들어 같은 조각으로 1회 더 시도한다. 또 초과면 멈춤 예외.
     * 입력: chunk(16 kHz mono 오디오 조각), lang(앱 언어 코드 "ko"/"en")
     * 출력: EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE/NOT_PREPARED/ABORTED/PIPE_NOT_CONSUMED/엔진 코드/LOAD_FAILED),
     *       두 번 응답 없음 → EngineStalledException, 코루틴 취소 → CancellationException
     * 리턴: ChunkText(text, rawText, detectedLang null, inferSec, session)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        if (!supports(lang)) throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "${spec.id} 는 언어 '$lang' 미지원(지원: ${spec.languages})") // 언어 검사
        val tag = LANG_TAGS[lang] ?: throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "언어 태그 없음: '$lang'") // ko → ko-KR, en → en-US
        val first = backend // 현재 백엔드
        if (closed || first == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "prepare 전이거나 닫힌 엔진: ${spec.id}") // 준비 확인
        cancelRequested.set(false) // 이번 전사의 중단 요청 초기화
        val pcm = PcmFrames.toPcm16Le(chunk.samples) // PCM16 LE 변환
        val watchMs = watchdogMs(chunk.samples.size, speed, watchdogMarginMs) // 감시 시간 // change(add)-hyungchul-2026-10-11-18:51 여유 인자
        attempt(first, pcm, tag, watchMs, sessionSeq.incrementAndGet())?.let { return toChunkText(it, retried = false) } // 첫 세션
        val renewed = renewBackend(first) // 멈춤 → 백엔드 재생성
        val n2 = sessionSeq.incrementAndGet() // 재시도 세션 번호
        attempt(renewed, pcm, tag, watchMs, n2)?.let { return toChunkText(it, retried = true) } // 재시도 세션
        throw EngineStalledException(kind, "${kind.serviceLabel} 가 응답하지 않습니다(세션 $n2)") // 두 번 응답 없음
    }

    /**
     * 목적: 세션 1회 시도
     * 기능: 세션을 열고 엔진 소유 범위(workScope, io)에서 공급 코루틴을 시작한다(공급 완료 시 finishInput). 감시 시간 안에 결과를 기다리고,
     *       change(add)-hyungchul-2026-10-11-19:18 세션 열기도 감시 시간(watchMs)으로 묶어, 넘으면 결과 없음(null)으로 돌아온다(응답 없음과 같은 처리 — 계약상 open 은 취소에 반응).
     *       결과·초과·취소·예외 어느 경로든 즉시 공급 코루틴 취소 + audio 닫기 후 **공급 코루틴을 기다리지 않고** 돌아온다.
     *       초과면 abort 후 null. 취소·예외로 빠져나가면 세션을 abort 한다.
     *       반환 시간 보장: awaitOutcome 은 withTimeoutOrNull 로 감시 시간에 묶이고(계약상 취소에 반응), 그 뒤에는 막히지 않는
     *       호출(Job.cancel, audio.close, abort — 계약상 즉시 반환)만 하므로 막힌 write 가 있어도 감시 시간·취소 직후 돌아온다.
     *       change(add)-hyungchul-2026-10-11-18:51
     * 입력: b(백엔드), pcm(PCM16 LE), tag(언어 태그), watchMs(감시 시간 ms), number(세션 번호)
     * 출력: EngineException(TRANSCRIBE, ABORTED/PIPE_NOT_CONSUMED/ENGINE_ERROR(공급 중 예외)/엔진 코드), 코루틴 취소 → CancellationException
     * 리턴: Attempt, 감시 시간 초과(세션 열기 초과 포함)면 null
     */
    private suspend fun attempt(b: RecognitionBackend, pcm: ByteArray, tag: String, watchMs: Long, number: Int): Attempt? {
        val t0 = clock() // 세션 시작 시각
        val session = withTimeoutOrNull(watchMs) { b.open(tag) } ?: return null // 세션 열기(감시 시간 초과 = 응답 없음) // change(add)-hyungchul-2026-10-11-19:18
        val fed = AtomicReference<FeedStats?>(null) // 공급 결과(공급 코루틴이 채움)
        val feedError = AtomicReference<Throwable?>(null) // 공급 코루틴의 예외(finishInput 실패 등) // change(add)-hyungchul-2026-10-11-18:51 // change(add)-hyungchul-2026-10-11-19:18 Throwable
        val feedJob = workScope.launch { // 공급 코루틴(엔진 소유 범위, join 하지 않음) // change(add)-hyungchul-2026-10-11-18:51
            try {
                val stats = feeder.feed(session.audio, pcm, speed) // 배속 공급
                fed.set(stats) // 결과 공개(finishInput 보다 먼저)
                if (stats.completed) session.finishInput() // 다 썼으면 공급 끝 알림
            } catch (e: CancellationException) {
                throw e // 공급 중단은 정상 종료
            } catch (e: Throwable) { // 분리된 범위라 예외가 새지 않게 모두 잡는다 // change(add)-hyungchul-2026-10-11-19:18 Exception → Throwable
                feedError.set(e) // 기록(대기 쪽에서 오류로 보고)
                runCatching { session.abort() } // 대기 중인 awaitOutcome 을 곧 끝나게 함(계약) — abort 가 던져도 무시 // change(add)-hyungchul-2026-10-11-19:18
            }
        }
        val mine = ActiveSession(session, feedJob) // 진행 중 세션
        active.set(mine) // cancel 대상으로 등록
        if (cancelRequested.get()) session.abort() // 등록 전에 온 중단 요청 반영
        var settled = false // 정상 경로로 결론이 났는지
        try {
            val outcome = withTimeoutOrNull(watchMs) { session.awaitOutcome() } // 감시 시간 안에 결과 대기
            val t1 = clock() // 결과 수신 시각
            stopFeed(mine) // 엔진이 먼저 끝났어도 공급기가 막히지 않게 즉시 정리(기다리지 않음)
            settled = true // 아래는 모두 결론
            if (outcome == null) session.abort() // 응답 없음 → 즉시 중단
            if (cancelRequested.get()) throw EngineException("TRANSCRIBE", "ABORTED", "전사 중단됨(세션 $number)") // 취소 요청
            feedError.get()?.let { throw EngineException("TRANSCRIBE", "ENGINE_ERROR", "공급 중 오류(세션 $number): ${it.message}", it) } // 공급 쪽 예외 // change(add)-hyungchul-2026-10-11-18:51
            return when (outcome) { // 결과별 처리
                null -> null // 감시 시간 초과(호출자가 재시도/멈춤 처리)
                is SessionOutcome.Failed -> throw EngineException("TRANSCRIBE", outcome.code, "${kind.serviceLabel}: ${outcome.message}") // 엔진 오류 코드 그대로
                is SessionOutcome.Completed -> { // 정상 완료
                    val stats = fed.get() // 공급 결과
                    if (stats == null || !stats.completed) { // 공급이 끝나기 전에 완료
                        throw EngineException("TRANSCRIBE", "PIPE_NOT_CONSUMED", "공급이 끝나기 전에 결과가 옴(세션 $number) — 파이프 대신 다른 입력(마이크 등)을 썼을 가능성") // 입력 의심
                    }
                    Attempt(outcome.segments, stats, (t1 - t0) / 1e9, number) // 성공 결과
                }
            }
        } finally {
            stopFeed(mine) // 어떤 경로든 공급 정리(공급 코루틴 종료를 기다리지 않음)
            if (!settled) session.abort() // 취소·예외로 빠져나가면 세션 즉시 중단
            active.compareAndSet(mine, null) // 등록 해제
        }
    }

    /**
     * 목적: 공급 정리
     * 기능: 공급 코루틴을 취소하고 audio(파이프 쓰기 쪽)를 닫아 막힌 쓰기를 풀어 준다(여러 번 불러도 안전).
     *       공급 코루틴이 끝나기를 기다리지 않는다 — close 로도 풀리지 않는 write 는 그 스레드에 남겨 두고 진행한다. // change(add)-hyungchul-2026-10-11-18:51
     * 입력: s(진행 중 세션)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun stopFeed(s: ActiveSession) {
        s.feedJob.cancel() // 공급 코루틴 취소
        runCatching { s.session.audio.close() } // 쓰기 쪽 닫기(이미 닫혔거나 실패해도 무시)
    }

    /**
     * 목적: 멈춘 백엔드 교체
     * 기능: 이전 백엔드를 closeTimeoutMs 제한으로 닫고(넘으면 기다리지 않음) backendFactory 로 새로 만든다.
     * 입력: old(멈춘 백엔드)
     * 출력: 닫힌 엔진 → EngineException(TRANSCRIBE, NOT_PREPARED), 생성 실패 → EngineException(TRANSCRIBE, LOAD_FAILED)
     * 리턴: 새 백엔드
     */
    private suspend fun renewBackend(old: RecognitionBackend): RecognitionBackend {
        backend = null // 이전 백엔드 회수
        closeBounded(old) // 제한 시간 안에서 닫기
        if (closed) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "재시도 전에 엔진이 닫힘: ${spec.id}") // 닫힘
        val b = createBackend("TRANSCRIBE") // 새 백엔드
        backend = b // 공개
        if (closed) { // 생성 중 close 가 불렸으면
            backend = null // 회수
            b.close() // 즉시 해제
            throw EngineException("TRANSCRIBE", "NOT_PREPARED", "재시도 준비 중 엔진이 닫힘: ${spec.id}") // 닫힘 보고
        }
        return b // 새 백엔드
    }

    /**
     * 목적: 제한 시간 백엔드 닫기
     * 기능: io 의 분리된 범위에서 close 를 돌리고 closeTimeoutMs 까지만 기다린다. 넘으면 기다리지 않고 진행한다(닫기 실패도 무시).
     *       분리된 범위이므로 예외가 밖으로 새지 않게 모두 잡는다.
     * 입력: b(닫을 백엔드)
     * 출력: 없음
     * 리턴: 없음
     */
    private suspend fun closeBounded(b: RecognitionBackend) {
        val done = CompletableDeferred<Unit>() // 닫기 끝 신호
        workScope.launch { // 호출자와 분리(멈춘 close 를 기다리지 않기 위해) // change(add)-hyungchul-2026-10-11-18:51 엔진 소유 범위 사용
            runCatching { b.close() } // 닫기(실패 무시)
            done.complete(Unit) // 끝 알림
        }
        withTimeoutOrNull(closeTimeoutMs) { done.await() } // 제한 시간까지만 대기
    }

    /**
     * 목적: 백엔드 생성
     * 기능: backendFactory 를 부르고, EngineException 이 아닌 예외는 지정 단계의 LOAD_FAILED 로 바꾼다.
     * 입력: stage(오류 단계: MODEL_LOAD/TRANSCRIBE)
     * 출력: EngineException(stage, LOAD_FAILED 또는 팩토리가 던진 코드)
     * 리턴: RecognitionBackend
     */
    private fun createBackend(stage: String): RecognitionBackend = try {
        backendFactory() // 생성
    } catch (e: EngineException) {
        throw e // 이미 분류된 오류
    } catch (e: Exception) {
        throw EngineException(stage, "LOAD_FAILED", "${kind.serviceLabel} 백엔드 생성 실패(${spec.id}): ${e.message}", e) // 일반 실패
    }

    /**
     * 목적: 성공 결과 → ChunkText
     * 기능: text 는 조각마다 trim 후 빈 것을 빼고 공백으로, rawText 는 원래 조각을 줄바꿈으로 잇는다. 세션 기록을 붙인다.
     * 입력: a(세션 성공 결과), retried(재시도 세션 여부)
     * 출력: 없음
     * 리턴: ChunkText
     */
    private fun toChunkText(a: Attempt, retried: Boolean): ChunkText = ChunkText(
        text = a.segments.map(String::trim).filter(String::isNotEmpty).joinToString(" "), // 정리 텍스트(음성 없음 = "")
        rawText = a.segments.joinToString("\n"), // 엔진 원문
        detectedLang = null, // 언어 고정
        inferSec = a.inferSec, // 세션 시작~결과 수신
        session = SessionInfo(a.number, a.stats.writtenBytes, a.stats.maxBlockMs, retried), // 세션 기록
    )

    /**
     * 목적: 진행 중 전사 중단
     * 기능: 중단 요청을 표시하고 현재 세션을 원자적으로 잡아 abort + 공급 정리(코루틴 취소·audio 닫기). 다른 스레드에서 불러도 안전하다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() {
        cancelRequested.set(true) // 중단 요청 표시
        active.get()?.let { it.session.abort(); stopFeed(it) } // 세션 중단 + 공급 정리 // change(add)-hyungchul-2026-10-11-18:51 audio 도 닫음
    }

    /**
     * 목적: 엔진 해제
     * 기능: 진행 중 세션을 중단·공급 정리하고 엔진 소유 범위를 취소한 뒤 백엔드를 닫는다(반복 호출 안전).
     * 입력: 없음
     * 출력: 백엔드 close 가 던진 예외
     * 리턴: 없음
     */
    override fun close() {
        closed = true // 닫힘 표시
        active.get()?.let { it.session.abort(); stopFeed(it) } // 진행 중 세션 중단 + 공급 정리 // change(add)-hyungchul-2026-10-11-18:51
        workScope.cancel() // 엔진 소유 범위 취소(남은 공급 코루틴 정리) // change(add)-hyungchul-2026-10-11-18:51
        val b = backend // 현재 백엔드
        backend = null // 회수
        b?.close() // 해제
    }

    companion object {
        /** 목적: 앱 언어 → BCP-47 태그 / 기능: ko → ko-KR, en → en-US (change(add)-hyungchul-2026-10-11-19:18 internal — SystemEngines 의 언어 확인도 같은 표를 씀) */
        internal val LANG_TAGS: Map<String, String> = mapOf("ko" to "ko-KR", "en" to "en-US") // 언어 태그

        /** 목적: 엔진 소유 범위의 처리 안 된 예외 흡수 / 기능: 공급·닫기 코루틴에서 잡히지 않은 예외를 버린다(결과는 각 코루틴이 이미 기록) */ // change(add)-hyungchul-2026-10-11-19:18
        private val IGNORE_UNCAUGHT = CoroutineExceptionHandler { _, _ -> } // 프로세스 종료 방지 // change(add)-hyungchul-2026-10-11-19:18

        /** 목적: 감시 여유 시간 / 기능: 공급 시간 뒤 결과를 기다리는 여유(ms) */
        private const val WATCHDOG_MARGIN_MS: Long = 15_000 // 15 초

        /**
         * 목적: 감시 시간 계산
         * 기능: ceil(조각 초 × 1000 ÷ 배속) + 여유(기본 15,000 ms).
         * 입력: samples(16 kHz 샘플 수), speed(배속), marginMs(감시 여유 ms, 기본 15,000 — 실제 스레드 테스트만 짧게) // change(add)-hyungchul-2026-10-11-18:51
         * 출력: 없음
         * 리턴: 감시 시간(ms)
         */
        internal fun watchdogMs(samples: Int, speed: Int, marginMs: Long = WATCHDOG_MARGIN_MS): Long =
            ceil(samples / 16_000.0 * 1000 / speed).toLong() + marginMs // 공급 시간 + 여유
    }
}
