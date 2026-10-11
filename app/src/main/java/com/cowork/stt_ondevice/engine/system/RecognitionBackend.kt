// 파일명: RecognitionBackend.kt
// 목적: 시스템 엔진(ML Kit GenAI Basic·Android 온디바이스 SpeechRecognizer)의 인식 백엔드 계약 정의 (2차 작업 3)
// 기능: SessionOutcome(세션 결과: 완료/실패), RecognitionSession(오디오 공급 쪽·결과 대기·공급 끝·즉시 중단),
//       RecognitionBackend(언어 태그로 세션 열기, 닫기)를 정의한다. Android 의존 구현은 작업 6 이 이 계약을 구현해 붙인다.
//       change(add)-hyungchul-2026-10-11-18:51 구현이 반드시 지켜야 할 스레드·막힘 규칙(audio.close 가 막힌 write 를 풀 것, abort 뒤 awaitOutcome 이 곧 돌아올 것,
//       모든 메서드가 스레드 안전·반복·순서 무관하게 무해할 것)을 계약 주석에 명시(리뷰 라운드 1).
//       change(add)-hyungchul-2026-10-11-19:18 RecognitionBackend.open 은 코루틴 취소에 반응해야 한다는 규칙 추가 — 세션 핵심이 open 을 감시 시간으로 묶는다(2차 작업 6).
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import java.io.OutputStream // 공급 스트림

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 인식 세션 1회의 결과
 * 기능: Completed(인식된 조각 목록, 음성 없음 = 빈 목록) 또는 Failed(오류 코드·메시지).
 */
sealed interface SessionOutcome {
    /**
     * 목적: 정상 완료
     * 기능: 엔진이 낸 최종 텍스트 조각들을 순서대로 담는다. 음성이 없으면 빈 목록(오류 아님).
     * 입력: segments(텍스트 조각 목록)
     */
    data class Completed(val segments: List<String>) : SessionOutcome // 음성 없음 = 빈 목록

    /**
     * 목적: 실패
     * 기능: 엔진 오류를 앱 오류 코드로 바꾼 값을 담는다.
     * 입력: code(LANGUAGE_UNAVAILABLE | PERMISSION_MISSING | ENGINE_ERROR), message(사람이 읽는 설명)
     */
    data class Failed(val code: String, val message: String) : SessionOutcome // 오류 코드·메시지
}

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 인식 세션 1회
 * 기능: audio 에 PCM16 LE mono 16 kHz 를 쓰고, 다 쓰면 finishInput, awaitOutcome 으로 결과를 받는다. abort 는 즉시 중단이다.
 *
 * 구현 필수 규칙(SystemAsrEngine 의 감시·재시도·취소 시간 보장이 이 규칙에 기댄다): // change(add)-hyungchul-2026-10-11-18:51
 * 1. 스레드 안전: finishInput / abort / audio.close() 는 어느 스레드에서, 여러 번, 어떤 순서로(awaitOutcome 이 끝난 뒤 포함) 불러도 무해해야 한다.
 * 2. 막히지 않음: finishInput / abort / audio.close() 는 오래 막히지 않고 곧 돌아와야 한다(세션 핵심이 결과 수신·감시 초과·취소 경로에서 직접 부른다).
 * 3. audio.close() 는 다른 스레드에서 audio.write 에 막혀 있는 공급기를 IOException 으로 풀어야 한다(파이프 쓰기 쪽 닫기).
 *    change(add)-hyungchul-2026-10-11-19:47 실제 구현(AudioPipe)의 근거: 쓰기 쪽 ParcelFileDescriptor.close() 가 막힌 write 를 InterruptedIOException 으로 깨움
 *    (S22 Ultra 계측 AudioPipeDeviceTest). 읽기 쪽 닫기는 엔진 프로세스 사본이 열려 있으면 EPIPE 를 만들지 않으므로 근거가 아니다.
 * 4. abort() 뒤 진행 중이거나 새로 부른 awaitOutcome() 은 곧 돌아와야 한다(결과는 Failed 등 무엇이든 됨).
 * 5. awaitOutcome() 은 코루틴 취소에 즉시 반응해야 한다(감시 시간 withTimeoutOrNull 과 사용자 취소가 이것으로 동작).
 * (세션 핵심은 3 이 지켜지지 않아도 공급 코루틴을 기다리지 않으므로 멈추지 않지만, 그 경우 막힌 공급 스레드 1개가 남는다.)
 */
interface RecognitionSession {
    /** 목적: 공급 쪽(쓰기) / 기능: 파이프 쓰기 끝 등. 세션 핵심이 결과 수신·중단 시 닫는다. close 는 반복 안전·즉시 반환이며 다른 스레드의 막힌 write 를 IOException 으로 풀어야 한다 */ // change(add)-hyungchul-2026-10-11-18:51
    val audio: OutputStream // 공급 쪽(쓰기)

    /**
     * 목적: 세션 결과 대기
     * 기능: 엔진이 세션을 끝낼 때까지 기다린다(코루틴 취소에 즉시 반응해야 함). abort() 뒤에는 곧 돌아와야 한다. // change(add)-hyungchul-2026-10-11-18:51
     * 입력: 없음
     * 출력: 코루틴 취소 시 CancellationException
     * 리턴: SessionOutcome
     */
    suspend fun awaitOutcome(): SessionOutcome

    /**
     * 목적: 공급 끝 알림
     * 기능: 더 보낼 오디오가 없음을 엔진에 알린다(ML Kit 는 stopRecognition, 플랫폼은 audio 닫기).
     *       스레드 안전, 막히지 않음, 여러 번·abort 뒤·결과 뒤에 불러도 무해해야 한다. // change(add)-hyungchul-2026-10-11-18:51
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun finishInput()

    /**
     * 목적: 즉시 중단
     * 기능: 멈춤(감시 시간 초과)·취소 시 세션을 즉시 끝낸다. 여러 번 불러도 안전해야 한다.
     *       스레드 안전, 막히지 않음, 결과 뒤에 불러도 무해. 진행 중인 awaitOutcome 은 곧 돌아와야 한다. // change(add)-hyungchul-2026-10-11-18:51
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun abort()
}

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 인식 백엔드
 * 기능: 언어 태그로 세션을 연다. close 로 백엔드 자원(클라이언트·인식기)을 해제한다.
 *       close() 는 여러 번 불러도 안전해야 한다(멈춤 재생성 시 세션 핵심이 제한 시간을 두고 다른 스레드에서 부를 수 있다). // change(add)-hyungchul-2026-10-11-18:51
 */
interface RecognitionBackend : AutoCloseable {
    /**
     * 목적: 세션 열기
     * 기능: 지정 언어로 인식 세션을 시작한다.
     *       change(add)-hyungchul-2026-10-11-19:18 코루틴 취소에 반응해야 한다 — 세션 핵심이 open 을 그 조각의 감시 시간(withTimeoutOrNull)으로 묶고, 넘으면 응답 없음으로 처리한다.
     *       취소로 끝날 때 이미 시작한 엔진 세션이 있으면 스스로 중단·정리해야 한다(돌려주지 못한 세션이 남지 않게).
     * 입력: langTag(BCP-47 태그, 예 "ko-KR"/"en-US")
     * 출력: 열기 실패 시 구현이 정한 예외, 코루틴 취소 시 CancellationException
     * 리턴: RecognitionSession
     */
    suspend fun open(langTag: String): RecognitionSession
}
