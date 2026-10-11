// 파일명: PlatformBackend.kt
// 목적: Android 플랫폼 온디바이스 SpeechRecognizer(실제 처리 com.google.android.as)를 RecognitionBackend 계약으로 감싼 백엔드 (2차 작업 6)
// 기능: 백엔드마다 SpeechRecognizer.createOnDeviceSpeechRecognizer 1개를 메인 스레드에서 만들어 세션마다 다시 쓴다(close = destroy).
//       open — 파이프를 만들고 EXTRA_AUDIO_SOURCE(읽기 쪽)·PCM16/mono/16 kHz·분할 세션(EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE)·
//              문장부호(EXTRA_ENABLE_FORMATTING=FORMATTING_OPTIMIZE_QUALITY)·FREE_FORM·PREFER_OFFLINE 인텐트로 startListening 한다.
//              onSegmentResults 첫 후보 누적, onEndOfSegmentedSession → Completed, onError 는 오류 표대로 바꾼다
//              (LANGUAGE_UNAVAILABLE/NOT_SUPPORTED → LANGUAGE_UNAVAILABLE, NO_MATCH/SPEECH_TIMEOUT → 음성 없음 Completed,
//               INSUFFICIENT_PERMISSIONS → PERMISSION_MISSING, SERVER_DISCONNECTED → SERVICE_DISCONNECTED, 그 밖 → ENGINE_ERROR "onError=<n>").
//              change(add)-hyungchul-2026-10-12-02:54 오류 표는 순수 함수 mapRecognizerError(PlatformErrors.kt)로 옮겼다(11 을 ENGINE_ERROR 와 구분).
//              API 33 미만이면 Failed(LANGUAGE_UNAVAILABLE, "Android 13 이상 필요"), RECORD_AUDIO 미허용이면 Failed(PERMISSION_MISSING) 세션을 돌려준다.
//       finishInput = 쓰기 쪽 닫기(EOF 가 세션 끝 신호), abort = cancel(), close = destroy(). 인식기 호출은 모두 메인 스레드(Handler/Dispatchers.Main)에서 한다.
// change(add)-hyungchul-2026-10-11-19:18
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import android.Manifest // 권한 이름
import android.content.Context // 컨텍스트
import android.content.Intent // 인식 요청 인텐트
import android.content.pm.PackageManager // 권한 확인 결과
import android.media.AudioFormat // PCM 인코딩 상수
import android.os.Build // API 수준
import android.os.Bundle // 콜백 결과 묶음
import android.os.Handler // 메인 스레드 게시
import android.os.Looper // 메인 루퍼
import android.speech.RecognitionListener // 인식 콜백
import android.speech.RecognizerIntent // 인텐트 엑스트라
import android.speech.SpeechRecognizer // 플랫폼 인식기
import kotlinx.coroutines.CompletableDeferred // 결과 신호
import kotlinx.coroutines.Dispatchers // 메인 디스패처
import kotlinx.coroutines.withContext // 메인 스레드 실행
import java.io.IOException // 입출력 오류
import java.io.OutputStream // 공급 스트림
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-19:18
/**
 * 목적: 플랫폼 온디바이스 인식 백엔드
 * 기능: 파일 머리 설명 참조. recognizer·current 는 메인 스레드에서만 읽고 쓴다.
 * 입력: context(아무 컨텍스트 — 앱 컨텍스트로 바꿔 보관)
 */
class PlatformBackend(context: Context) : RecognitionBackend {
    private val ctx: Context = context.applicationContext // 앱 컨텍스트
    private val main = Handler(Looper.getMainLooper()) // 메인 스레드 게시
    private var recognizer: SpeechRecognizer? = null // 인식기(메인 스레드 전용)
    private var current: Session? = null // 지금 듣는 세션(메인 스레드 전용)
    @Volatile private var closed = false // close 호출 여부

    /**
     * 목적: 세션 열기
     * 기능: API·권한을 확인하고(안 되면 실패 세션), 파이프를 만든 뒤 메인 스레드에서 인식기를 (처음이면 만들고) 세션 리스너로 startListening 한다.
     *       메인 스레드 대기 중 취소되면 시작한 세션을 중단하고 취소를 전파한다(계약: 취소에 반응, 돌려주지 못한 세션을 남기지 않음).
     *       인식기 생성·시작이 예외로 실패하면 ENGINE_ERROR 실패 세션을 돌려준다.
     * 입력: langTag(BCP-47 태그)
     * 출력: 취소 시 CancellationException, 파이프 생성 실패 시 IOException
     * 리턴: RecognitionSession
     */
    override suspend fun open(langTag: String): RecognitionSession {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { // 파이프 입력·분할 세션은 API 33+
            return FailedSession(SessionOutcome.Failed("LANGUAGE_UNAVAILABLE", "Android 13 이상 필요(현재 API ${Build.VERSION.SDK_INT})")) // 실행 불가
        }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { // 마이크 권한
            return FailedSession(SessionOutcome.Failed("PERMISSION_MISSING", "마이크(RECORD_AUDIO) 권한이 없어 Android 온디바이스 인식기를 쓸 수 없음 — 앱 권한에서 허용 필요")) // 권한 없음
        }
        val pipe = AudioPipe.create() // 오디오 파이프
        val session = Session(pipe, langTag) // 세션(리스너)
        try {
            withContext(Dispatchers.Main) { // 인식기 호출은 메인 스레드
                check(!closed) { "닫힌 플랫폼 백엔드에서 세션을 열 수 없음" } // 재사용 금지
                val rec = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx).also { recognizer = it } // 인식기(처음이면 생성)
                current = session // 지금 세션 표시(늦은 cancel 이 다른 세션을 끊지 않게)
                rec.setRecognitionListener(session) // 이 세션 콜백
                rec.startListening(intentFor(langTag, pipe)) // 파이프 입력 인식 시작
            }
        } catch (e: CancellationException) {
            session.abort() // 시작했을 수 있는 세션 중단
            throw e // 취소 전파
        } catch (e: Exception) {
            pipe.closeAll() // 파이프 정리
            return FailedSession(SessionOutcome.Failed("ENGINE_ERROR", "Android 온디바이스 인식기 시작 실패($langTag): ${e.javaClass.simpleName}: ${e.message}")) // 시작 실패
        }
        return session // 듣는 중인 세션
    }

    /**
     * 목적: 인식 요청 인텐트 만들기
     * 기능: 파일 머리 설명의 엑스트라를 채운다(API 33 이상에서만 호출).
     * 입력: tag(BCP-47 태그), pipe(오디오 파이프 — 읽기 쪽을 넘김)
     * 출력: 없음
     * 리턴: Intent
     */
    private fun intentFor(tag: String, pipe: AudioPipe): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM) // 자유 발화
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag) // 언어
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // 오프라인 우선
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe.readSide) // 파이프 입력(마이크 아님)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT) // PCM16
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1) // mono
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000) // 16 kHz
        putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE) // 분할 세션(없으면 빈 결과 — 실측)
        putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY) // 문장부호(ML Kit 과 공정 비교)
    }

    /**
     * 목적: 백엔드 해제
     * 기능: 닫힘 표시 후 메인 스레드에 destroy 를 게시한다(즉시 반환, 반복 안전).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        closed = true // 닫힘 표시
        main.post { // 메인 스레드에서
            current = null // 세션 표시 해제
            recognizer?.let { runCatching { it.destroy() } } // 인식기 해제(실패 무시)
            recognizer = null // 회수
        }
    }

    /**
     * 목적: 플랫폼 인식 세션 1회(리스너 겸)
     * 기능: 콜백(메인 스레드)에서 조각을 모으고 결론이 나면 파이프를 닫고 결과를 1회 기록한다.
     * 입력: pipe(오디오 파이프), tag(언어 태그 — 메시지용)
     */
    private inner class Session(
        private val pipe: AudioPipe, // 오디오 파이프
        private val tag: String, // 언어 태그
    ) : RecognitionSession, RecognitionListener {
        override val audio: OutputStream get() = pipe.output // 공급 쪽(쓰기)
        private val result = CompletableDeferred<SessionOutcome>() // 세션 결과(1회)
        private val segments = ArrayList<String>() // 분할 결과 조각(메인 스레드 전용)

        /**
         * 목적: 결론 기록
         * 기능: 파이프를 닫고(공급기 해제) 결과를 기록한다(먼저 온 결론 우선).
         * 입력: outcome(세션 결과)
         * 출력: 없음
         * 리턴: 이번 호출이 결론을 정했으면 true
         */
        private fun settle(outcome: SessionOutcome): Boolean {
            pipe.closeAll() // 파이프 정리
            return result.complete(outcome) // 1회 기록
        }

        /**
         * 목적: 결과 대기
         * 기능: 결론이 날 때까지 기다린다(취소에 즉시 반응, abort 뒤 곧 돌아옴).
         * 입력: 없음
         * 출력: 취소 시 CancellationException
         * 리턴: SessionOutcome
         */
        override suspend fun awaitOutcome(): SessionOutcome = result.await() // 결과

        /**
         * 목적: 공급 끝 알림
         * 기능: 쓰기 쪽만 닫는다(EOF 가 분할 세션 끝 신호). 막히지 않고 반복 안전.
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        override fun finishInput() = pipe.closeWrite() // EOF

        /**
         * 목적: 즉시 중단
         * 기능: 결과를 ENGINE_ERROR(중단)로 확정하고 파이프를 닫는다. 이번 호출이 결론을 정했으면(=아직 듣는 중이었으면)
         *       메인 스레드에 cancel() 을 게시하되, 그 시점에 이 세션이 여전히 현재 세션일 때만 부른다(다음 세션을 끊지 않음). 반복 안전.
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        override fun abort() {
            if (!settle(SessionOutcome.Failed("ENGINE_ERROR", "세션 중단됨($tag)"))) return // 이미 끝난 세션이면 할 일 없음
            main.post { // 메인 스레드에서
                if (current === this) { // 아직 이 세션이 듣는 중
                    runCatching { recognizer?.cancel() } // 인식 취소(실패 무시)
                    current = null // 표시 해제
                }
            }
        }

        /** 목적: 분할 결과 수신 / 기능: 첫 후보를 조각으로 누적한다 */
        override fun onSegmentResults(segmentResults: Bundle) {
            segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { segments += it } // 첫 후보
        }

        /** 목적: 분할 세션 끝 / 기능: 모은 조각으로 완료 */
        override fun onEndOfSegmentedSession() {
            if (current === this) current = null // 듣기 끝
            settle(SessionOutcome.Completed(segments.toList())) // 완료
        }

        /** 목적: 비분할 결과(방어용) / 기능: 분할 세션에서는 오지 않지만, 오면 첫 후보를 더해 완료로 본다(무한 대기 방지) */
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { segments += it } // 첫 후보
            if (current === this) current = null // 듣기 끝
            settle(SessionOutcome.Completed(segments.toList())) // 완료
        }

        /**
         * 목적: 인식 오류 수신
         * 기능: 파일 머리의 오류 표대로 결과를 정한다. 음성 없음(NO_MATCH·SPEECH_TIMEOUT)은 오류가 아니라 그때까지 모은 조각(보통 빈 목록)으로 완료.
         * 입력: error(SpeechRecognizer.ERROR_* 값)
         * 출력: 없음
         * 리턴: 없음
         */
        override fun onError(error: Int) {
            if (current === this) current = null // 듣기 끝
            settle(mapRecognizerError(error, tag, segments.toList())) // 결론 // change(add)-hyungchul-2026-10-12-02:54 순수 함수로 분리
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit // 사용 안 함
        override fun onBeginningOfSpeech() = Unit // 사용 안 함
        override fun onRmsChanged(rmsdB: Float) = Unit // 사용 안 함
        override fun onBufferReceived(buffer: ByteArray?) = Unit // 사용 안 함
        override fun onEndOfSpeech() = Unit // 사용 안 함(분할 세션 끝은 onEndOfSegmentedSession)
        override fun onPartialResults(partialResults: Bundle?) = Unit // 중간 결과 사용 안 함
        override fun onEvent(eventType: Int, params: Bundle?) = Unit // 사용 안 함
    }

    /**
     * 목적: 이미 실패한 세션
     * 기능: open 단계에서 실행할 수 없음을 안 경우(API·권한·시작 실패) 그 결과를 곧바로 돌려준다. audio 는 쓰기마다 IOException(공급기 즉시 종료).
     * 입력: outcome(실패 결과)
     */
    private class FailedSession(private val outcome: SessionOutcome) : RecognitionSession {
        override val audio: OutputStream = object : OutputStream() { // 쓰기 거부 스트림
            override fun write(b: Int) { throw IOException("실행할 수 없는 세션") } // 공급기 종료 유도
            override fun write(b: ByteArray, off: Int, len: Int) { throw IOException("실행할 수 없는 세션") } // 공급기 종료 유도
        }

        /** 목적: 결과 / 기능: 곧바로 실패 결과 */
        override suspend fun awaitOutcome(): SessionOutcome = outcome // 즉시

        /** 목적: 공급 끝 / 기능: 할 일 없음 */
        override fun finishInput() = Unit // 없음

        /** 목적: 중단 / 기능: 할 일 없음 */
        override fun abort() = Unit // 없음
    }
}
