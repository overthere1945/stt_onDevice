// 파일명: MlKitBackend.kt
// 목적: ML Kit GenAI 음성 인식 Basic(SODA, com.google.android.tts)을 RecognitionBackend 계약으로 감싼 백엔드 (2차 작업 6)
// 기능: 언어별 SpeechRecognizer(MODE_BASIC) 클라이언트를 백엔드 안에 1개씩 캐시한다.
//       open — 파이프를 만들고 startRecognition(AudioSource.fromPfd(읽기 쪽)) Flow 를 백엔드 소유 범위에서 수집한다.
//              FinalTextResponse.text 누적, CompletedResponse → Completed, ErrorResponse/Flow 예외 → Failed
//              (원래 오류가 INVALID_REQUEST 이고 checkStatus() 가 AVAILABLE 이 아니면 LANGUAGE_UNAVAILABLE, 아니면 ENGINE_ERROR, 메시지에 원래 코드 보존).
//              Flow 가 어떤 이유로든 끝나면 즉시 파이프를 닫아 공급기가 막히지 않게 한다(실측 교훈).
//       finishInput = 쓰기 쪽 닫기 + stopRecognition(), abort = 수집 취소 + 양쪽 닫기 + stopRecognition()(예외 무시), close = 클라이언트 close.
//       stopRecognition 은 suspend 라 백엔드 범위에서 비동기로 부르고(최대 5 초), 다음 open 은 그 정지와 앞 세션의 Flow 수집이 끝나기를(각각 최대 5 초,
//       수집이 안 끝나면 취소) 기다린다 — 늦게 도착한 정지·앞 세션 꼬리가 다음 세션을 끊거나 겹치지 않게 하기 위함.
// change(add)-hyungchul-2026-10-11-19:18
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import android.util.Log // 로그
import com.google.mlkit.genai.common.FeatureStatus // 기능 상태 상수
import com.google.mlkit.genai.common.GenAiException // ML Kit 오류
import com.google.mlkit.genai.common.audio.AudioSource // 오디오 입력(파이프)
import com.google.mlkit.genai.speechrecognition.SpeechRecognition // 클라이언트 생성
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer // ML Kit 인식기
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions // 인식기 옵션(모드)
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse // 인식 응답
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions // 옵션 DSL
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest // 요청 DSL
import kotlinx.coroutines.CompletableDeferred // 결과 신호
import kotlinx.coroutines.CoroutineExceptionHandler // 처리 안 된 예외 흡수
import kotlinx.coroutines.CoroutineScope // 백엔드 소유 범위
import kotlinx.coroutines.Dispatchers // IO 디스패처
import kotlinx.coroutines.Job // 코루틴 핸들
import kotlinx.coroutines.SupervisorJob // 실패 격리
import kotlinx.coroutines.cancel // 범위 취소
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withTimeoutOrNull // 제한 시간 대기
import java.io.OutputStream // 공급 스트림
import java.util.Locale // 언어 로캘
import java.util.concurrent.atomic.AtomicBoolean // 1회 플래그
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-19:18
/**
 * 목적: ML Kit GenAI Basic 백엔드
 * 기능: 파일 머리 설명 참조. 스레드 안전하며 close 는 반복 안전하다.
 * 입력: 없음(ML Kit 는 자체 초기화 공급자로 앱 컨텍스트를 얻음)
 */
class MlKitBackend : RecognitionBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w(TAG, "ML Kit 백엔드 코루틴 예외(무시)", e) }) // 백엔드 소유 범위
    private val lock = Any() // 클라이언트 캐시 보호
    private val clients = HashMap<String, SpeechRecognizer>() // 언어 태그 → 클라이언트
    @Volatile private var closed = false // close 호출 여부
    @Volatile private var pendingStop: Job? = null // 마지막 stopRecognition 작업
    @Volatile private var lastCollect: Job? = null // 마지막 세션의 Flow 수집 작업

    /**
     * 목적: 세션 열기
     * 기능: 앞 세션의 정지 요청과 Flow 수집이 끝나기를 각각 최대 5 초 기다린 뒤(수집이 안 끝나면 취소), 언어 클라이언트로 파이프 입력 인식을 시작한다.
     *       대기 외에는 중단 지점이 없어 취소되면 세션을 시작하지 않은 채 끝난다(계약: 취소에 반응).
     * 입력: langTag(BCP-47 태그)
     * 출력: 닫힌 백엔드 → IllegalStateException, 파이프 생성 실패 → IOException, 취소 → CancellationException
     * 리턴: RecognitionSession
     */
    override suspend fun open(langTag: String): RecognitionSession {
        check(!closed) { "닫힌 ML Kit 백엔드에서 세션을 열 수 없음" } // 재사용 금지
        pendingStop?.let { withTimeoutOrNull(STOP_TIMEOUT_MS) { it.join() } } // 늦은 정지가 새 세션을 끊지 않게 대기(취소 가능)
        lastCollect?.let { j -> if (withTimeoutOrNull(STOP_TIMEOUT_MS) { j.join() } == null) j.cancel() } // 앞 세션 꼬리 정리(안 끝나면 취소)
        val client = clientFor(langTag) // 언어 클라이언트(캐시)
        val pipe = AudioPipe.create() // 오디오 파이프
        return Session(client, pipe, langTag).also { it.start() } // 수집 시작 후 반환
    }

    /**
     * 목적: 언어별 클라이언트 얻기
     * 기능: 캐시에 있으면 그대로, 없으면 MODE_BASIC 클라이언트를 만들어 넣는다.
     * 입력: tag(BCP-47 태그)
     * 출력: 닫힌 백엔드 → IllegalStateException
     * 리턴: SpeechRecognizer
     */
    private fun clientFor(tag: String): SpeechRecognizer = synchronized(lock) {
        check(!closed) { "닫힌 ML Kit 백엔드" } // 닫힘 확인(잠금 안)
        clients.getOrPut(tag) { newClient(tag) } // 언어마다 1개
    }

    /**
     * 목적: 세션 정지 요청
     * 기능: 백엔드 범위에서 stopRecognition() 을 최대 5 초 비동기로 부르고(예외 무시) 그 작업을 pendingStop 으로 기록한다.
     * 입력: client(정지할 클라이언트)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun requestStop(client: SpeechRecognizer) {
        pendingStop = scope.launch { // 비동기 정지(호출자 막지 않음)
            try {
                withTimeoutOrNull(STOP_TIMEOUT_MS) { client.stopRecognition() } // 정지(제한 시간)
            } catch (e: CancellationException) {
                throw e // 범위 취소는 그대로
            } catch (e: Exception) {
                Log.d(TAG, "stopRecognition 예외(무시): $e") // 이미 끝난 세션 등
            }
        }
    }

    /**
     * 목적: 백엔드 해제
     * 기능: 닫힘 표시 후 범위를 취소하고 캐시한 클라이언트를 모두 닫는다. 닫기는 별도 스레드에서 하고 최대 5 초만 기다린다(반복 안전).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        val toClose = synchronized(lock) { // 잠금 안에서 꺼내기
            closed = true // 닫힘 표시
            clients.values.toList().also { clients.clear() } // 닫을 클라이언트
        }
        scope.cancel() // 수집·정지 코루틴 취소
        if (toClose.isEmpty()) return // 닫을 것 없음(반복 호출)
        val t = Thread({ toClose.forEach { c -> runCatching { c.close() } } }, "mlkit-close") // 닫기 스레드(실패 무시)
        t.isDaemon = true // 프로세스 종료를 막지 않음
        t.start() // 시작
        t.join(STOP_TIMEOUT_MS) // 최대 5 초 대기(넘으면 포기)
    }

    /**
     * 목적: ML Kit 인식 세션 1회
     * 기능: start 로 Flow 수집을 시작하고, 결과를 result 에 한 번만 기록한다(먼저 온 결론 우선).
     * 입력: client(언어 클라이언트), pipe(오디오 파이프), tag(언어 태그 — 오류 메시지용)
     */
    private inner class Session(
        private val client: SpeechRecognizer, // 언어 클라이언트
        private val pipe: AudioPipe, // 오디오 파이프
        private val tag: String, // 언어 태그
    ) : RecognitionSession {
        override val audio: OutputStream get() = pipe.output // 공급 쪽(쓰기)
        private val result = CompletableDeferred<SessionOutcome>() // 세션 결과(1회)
        private val stopRequested = AtomicBoolean(false) // stopRecognition 1회
        @Volatile private var job: Job? = null // 수집 코루틴

        /**
         * 목적: 인식 시작
         * 기능: 백엔드 범위에서 startRecognition Flow 를 수집한다. 결론(완료·오류·Flow 끝·예외)이 나면 즉시 파이프를 닫고 결과를 기록한다.
         *       수집 코루틴이 어떤 이유로든 끝나면(취소 포함) 파이프를 닫고, 결과가 없으면 ENGINE_ERROR 로 기록한다.
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        fun start() {
            val segments = ArrayList<String>() // 최종 텍스트 조각(수집 코루틴 안에서만 접근)
            val j = scope.launch { // 수집 코루틴
                try {
                    client.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromPfd(pipe.readSide) }).collect { r -> // 응답마다
                        when (r) {
                            is SpeechRecognizerResponse.FinalTextResponse -> segments += r.text // 최종 텍스트 누적
                            is SpeechRecognizerResponse.ErrorResponse -> { // 엔진 오류
                                pipe.closeAll() // 공급기 즉시 해제
                                if (!result.isCompleted) result.complete(mapError(r.e)) // 오류 결론
                            }
                            is SpeechRecognizerResponse.CompletedResponse -> { // 세션 완료
                                pipe.closeAll() // 파이프 정리
                                result.complete(SessionOutcome.Completed(segments.toList())) // 완료 결론(오류가 먼저면 무시됨)
                            }
                            else -> Unit // 중간 결과 등은 쓰지 않음
                        }
                    }
                    pipe.closeAll() // Flow 끝 → 공급기 해제
                    result.complete(SessionOutcome.Failed("ENGINE_ERROR", "완료 응답 없이 인식이 끝남($tag)")) // 결론이 없었으면 오류
                } catch (e: CancellationException) {
                    throw e // abort·백엔드 닫기
                } catch (e: Throwable) { // Flow 예외
                    pipe.closeAll() // 공급기 즉시 해제
                    if (!result.isCompleted) result.complete(mapError(e)) // 오류 결론
                }
            }
            j.invokeOnCompletion { // 수집 코루틴 종료(정상·예외·취소)
                pipe.closeAll() // 파이프 정리
                result.complete(SessionOutcome.Failed("ENGINE_ERROR", "세션 중단됨($tag)")) // 결론이 없었으면 중단
            }
            job = j // 공개
            lastCollect = j // 다음 open 이 꼬리를 기다리게 기록
        }

        /**
         * 목적: ML Kit 오류 → 세션 결과
         * 기능: 원래 메시지에 INVALID_REQUEST 가 있고 checkStatus() 가 AVAILABLE 이 아니면 LANGUAGE_UNAVAILABLE, 그 밖은 ENGINE_ERROR.
         *       메시지에 원래 errorCode·메시지를 남긴다(상태 확인 실패·시간 초과면 ENGINE_ERROR).
         * 입력: e(ML Kit 오류 또는 Flow 예외)
         * 출력: 취소 시 CancellationException
         * 리턴: SessionOutcome.Failed
         */
        private suspend fun mapError(e: Throwable): SessionOutcome.Failed {
            val code = (e as? GenAiException)?.errorCode // 원래 오류 코드(없으면 null)
            val original = "원래 오류: errorCode=$code ${e.message ?: e.javaClass.simpleName}" // 원래 정보 보존
            if ((e.message ?: "").contains("INVALID_REQUEST")) { // 언어 미설치 등에서 나는 오류
                val status = statusOrNull(client) // 언어 상태
                if (status != null && status != FeatureStatus.AVAILABLE) { // 사용 불가 확인됨
                    return SessionOutcome.Failed("LANGUAGE_UNAVAILABLE", "언어 사용 불가($tag, 상태 ${statusName(status)}) — $original") // 언어 문제
                }
            }
            return SessionOutcome.Failed("ENGINE_ERROR", original) // 일반 엔진 오류
        }

        /**
         * 목적: 결과 대기
         * 기능: 결과가 기록될 때까지 기다린다(취소에 즉시 반응, abort 뒤 곧 돌아옴).
         * 입력: 없음
         * 출력: 취소 시 CancellationException
         * 리턴: SessionOutcome
         */
        override suspend fun awaitOutcome(): SessionOutcome = result.await() // 결과

        /**
         * 목적: 공급 끝 알림
         * 기능: 쓰기 쪽을 닫고(EOF) stopRecognition 을 비동기로 요청한다(1회). 막히지 않는다.
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        override fun finishInput() {
            pipe.closeWrite() // EOF
            stop() // 정지 요청
        }

        /**
         * 목적: 즉시 중단
         * 기능: 결과를 ENGINE_ERROR(중단)로 확정하고 수집을 취소, 파이프 양쪽을 닫고 stopRecognition 을 비동기로 요청한다(예외 무시, 반복 안전).
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        override fun abort() {
            result.complete(SessionOutcome.Failed("ENGINE_ERROR", "세션 중단됨($tag)")) // 대기 중 awaitOutcome 해제
            job?.cancel() // 수집 취소
            pipe.closeAll() // 파이프 정리(막힌 write 해제)
            stop() // 정지 요청
        }

        /**
         * 목적: 정지 요청 1회
         * 기능: 이 세션에서 처음 부를 때만 requestStop 을 부른다.
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        private fun stop() {
            if (stopRequested.compareAndSet(false, true)) requestStop(client) // 1회만
        }
    }

    companion object {
        private const val TAG = "MlKitBackend" // 로그 태그
        /** 목적: 정지·닫기·상태 확인 제한 시간(ms) / 기능: 실측에서 정상 응답은 0.2 초 안 — 넘으면 포기 */
        internal const val STOP_TIMEOUT_MS: Long = 5_000 // 5 초

        /**
         * 목적: ML Kit Basic 클라이언트 생성
         * 기능: 언어 태그 로캘과 MODE_BASIC 으로 SpeechRecognizer 를 만든다.
         * 입력: tag(BCP-47 태그)
         * 출력: 없음
         * 리턴: SpeechRecognizer
         */
        internal fun newClient(tag: String): SpeechRecognizer = SpeechRecognition.getClient(speechRecognizerOptions {
            locale = Locale.forLanguageTag(tag) // 언어
            preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC // Basic(SODA) 모드
        })

        /**
         * 목적: 언어 상태 확인(실패 시 null)
         * 기능: checkStatus() 를 최대 5 초 기다린다. 시간 초과·예외면 null(상태를 단정하지 않음).
         * 입력: client(클라이언트)
         * 출력: 취소 시 CancellationException
         * 리턴: FeatureStatus 값 또는 null
         */
        internal suspend fun statusOrNull(client: SpeechRecognizer): Int? = try {
            withTimeoutOrNull(STOP_TIMEOUT_MS) { client.checkStatus() } // 상태(제한 시간)
        } catch (e: CancellationException) {
            throw e // 취소는 그대로
        } catch (e: Exception) {
            null // 확인 실패
        }

        /**
         * 목적: 상태 값 이름
         * 기능: FeatureStatus 정수를 이름으로 바꾼다.
         * 입력: s(FeatureStatus 값)
         * 출력: 없음
         * 리턴: 이름 문자열
         */
        internal fun statusName(s: Int): String = when (s) {
            FeatureStatus.AVAILABLE -> "AVAILABLE" // 사용 가능
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE" // 내려받기 가능
            FeatureStatus.DOWNLOADING -> "DOWNLOADING" // 내려받는 중
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE" // 사용 불가
            else -> "UNKNOWN($s)" // 알 수 없음
        }
    }
}
