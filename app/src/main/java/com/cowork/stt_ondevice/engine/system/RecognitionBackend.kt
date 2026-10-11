// 파일명: RecognitionBackend.kt
// 목적: 시스템 엔진(ML Kit GenAI Basic·Android 온디바이스 SpeechRecognizer)의 인식 백엔드 계약 정의 (2차 작업 3)
// 기능: SessionOutcome(세션 결과: 완료/실패), RecognitionSession(오디오 공급 쪽·결과 대기·공급 끝·즉시 중단),
//       RecognitionBackend(언어 태그로 세션 열기, 닫기)를 정의한다. Android 의존 구현은 작업 6 이 이 계약을 구현해 붙인다.
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
 */
interface RecognitionSession {
    /** 목적: 공급 쪽(쓰기) / 기능: 파이프 쓰기 끝 등. 세션 핵심이 결과 수신·중단 시 닫는다 */
    val audio: OutputStream // 공급 쪽(쓰기)

    /**
     * 목적: 세션 결과 대기
     * 기능: 엔진이 세션을 끝낼 때까지 기다린다(코루틴 취소에 반응해야 함).
     * 입력: 없음
     * 출력: 코루틴 취소 시 CancellationException
     * 리턴: SessionOutcome
     */
    suspend fun awaitOutcome(): SessionOutcome

    /**
     * 목적: 공급 끝 알림
     * 기능: 더 보낼 오디오가 없음을 엔진에 알린다(ML Kit 는 stopRecognition, 플랫폼은 audio 닫기).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun finishInput()

    /**
     * 목적: 즉시 중단
     * 기능: 멈춤(감시 시간 초과)·취소 시 세션을 즉시 끝낸다. 여러 번 불러도 안전해야 한다.
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
 */
interface RecognitionBackend : AutoCloseable {
    /**
     * 목적: 세션 열기
     * 기능: 지정 언어로 인식 세션을 시작한다.
     * 입력: langTag(BCP-47 태그, 예 "ko-KR"/"en-US")
     * 출력: 열기 실패 시 구현이 정한 예외
     * 리턴: RecognitionSession
     */
    suspend fun open(langTag: String): RecognitionSession
}
