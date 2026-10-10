// 파일명: AsrEngine.kt
// 목적: 모든 STT 엔진(whisper.cpp·sherpa-onnx·Vosk)이 따르는 공통 계약 정의 (spec §9.1)
// 기능: PrepareInfo(모델 로드 결과), ChunkText(chunk 1개 전사 결과), EngineException(단계·코드가 붙은 엔진 오류),
//       AsrEngine(언어 지원 여부·준비·전사·취소·해제) 인터페이스를 제공한다.
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine // 엔진 패키지

import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: 엔진 준비(모델 로드) 결과
 * 기능: 결과 CSV·manifest 에 기록할 실제 로드 시간과 실행 환경을 담는다.
 * 입력: loadSec(모델 로드 시간, 초), runtimeVersion(네이티브 런타임이 보고한 버전), backend(실행 장치, 예: "CPU"),
 *       precision(가중치 정밀도, 예: f16/q5_0/q8_0/int8), threads(실제 사용 스레드 수)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class PrepareInfo(
    val loadSec: Double, // 모델 로드 시간(초)
    val runtimeVersion: String, // 런타임 버전
    val backend: String, // 실행 장치
    val precision: String, // 가중치 정밀도
    val threads: Int, // 스레드 수
)

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: chunk 1개 전사 결과
 * 기능: 결과 txt 에 쓸 정리된 텍스트(text), 엔진 원문(rawText, 예: SenseVoice 태그 포함), 엔진이 감지한 언어, 추론 시간을 담는다.
 * 입력: text(정리된 텍스트), rawText(엔진 원문), detectedLang(엔진 감지 언어, 언어를 고정했거나 알 수 없으면 null), inferSec(추론 시간, 초)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class ChunkText(
    val text: String, // 정리된 텍스트
    val rawText: String, // 엔진 원문
    val detectedLang: String?, // 감지 언어(없으면 null)
    val inferSec: Double, // 추론 시간(초)
)

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: 엔진 오류
 * 기능: spec §13 오류 형식의 stage(MODEL_LOAD/TRANSCRIBE 등)와 code(NOT_IMPLEMENTED/ABORTED/UNSUPPORTED_LANGUAGE 등)를 함께 전달한다.
 * 입력: stage(오류 단계), code(오류 코드), message(사람이 읽는 설명), cause(원인 예외, 없으면 null)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class EngineException(
    val stage: String, // 오류 단계
    val code: String, // 오류 코드
    message: String, // 설명
    cause: Throwable? = null, // 원인 예외
) : Exception(message, cause)

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: STT 엔진 공통 계약
 * 기능: 모델 단위로 1회 prepare → 여러 chunk transcribe → close 순서로 쓴다(모델 동시 적재 금지, spec §9.1).
 *       cancel 은 진행 중인 전사를 가능한 빨리 멈추게 하는 신호다(whisper.cpp 는 abort_callback, 그 밖의 엔진은 chunk 경계).
 */
interface AsrEngine : AutoCloseable {
    /** 이 엔진이 실행하는 모델의 메타데이터. */
    val spec: ModelSpec

    /**
     * 목적: 언어 지원 여부
     * 기능: 기본 구현은 spec.languages 에 들어 있는지만 본다.
     * 입력: lang(언어 코드, "ko"/"en")
     * 출력: 없음
     * 리턴: 지원하면 true
     */
    fun supports(lang: String): Boolean = lang in spec.languages // 스펙 언어 집합 포함 여부

    /**
     * 목적: 모델 로드
     * 기능: 네이티브 모델을 메모리에 올리고 로드 시간·실행 환경을 돌려준다.
     * 입력: threads(추론 스레드 수, 1 이상)
     * 출력: 실패 시 EngineException(stage = MODEL_LOAD)
     * 리턴: PrepareInfo
     */
    suspend fun prepare(threads: Int): PrepareInfo

    /**
     * 목적: chunk 1개 전사
     * 기능: chunk.samples(16 kHz mono float)를 지정 언어로 전사한다.
     * 입력: chunk(오디오 조각), lang(언어 코드)
     * 출력: 미지원 언어·준비 전·중단·네이티브 오류 시 EngineException(stage = TRANSCRIBE)
     * 리턴: ChunkText
     */
    suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: 다른 스레드에서 불러도 안전해야 한다. 진행 중인 transcribe 는 EngineException(TRANSCRIBE, ABORTED) 로 끝난다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun cancel()
}
