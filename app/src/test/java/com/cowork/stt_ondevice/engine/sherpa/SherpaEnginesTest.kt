// 파일명: SherpaEnginesTest.kt
// 목적: SherpaOfflineEngine·SherpaStreamingEngine 의 네이티브 호출 전 검사(언어·준비·파일·닫힘·설정 종류)를 JVM 에서 검증
// 기능: 실제 카탈로그 스펙으로 엔진을 만들고, libsherpa-onnx-jni.so 를 올리기 전에 끝나는 경로만 시험한다
//       (UNSUPPORTED_LANGUAGE, NOT_PREPARED, MODEL_FILE_MISSING, CLOSED, close 반복, threads 검사, 생성자 CONFIG_MISMATCH).
//       실제 전사는 기기 계측 테스트(NativeSmokeTest)에서 확인한다.
// change(add)-hyungchul-2026-10-10-20:57
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.runBlocking // suspend 함수 동기 실행
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: sherpa 엔진의 네이티브 전 동작 회귀 방지
 * 기능: 위 파일 머리 설명 참조.
 */
class SherpaEnginesTest {

    @get:Rule
    val tmp = TemporaryFolder() // 임시 폴더(빈 모델 폴더)

    private val zipKo = ModelCatalog.byId("sherpa-zipformer-ko-2024-06-24") // 오프라인 한국어 전용 스펙
    private val streamKo = ModelCatalog.byId("sherpa-zipformer-streaming-ko-2024-06-16") // 스트리밍 한국어 전용 스펙
    private val chunk = AudioChunk(0, FloatArray(1600), listOf(SourceSpan(0.0, 0.1, 0.0)), SplitMethod.NONE) // 0.1 s 무음 chunk

    /**
     * 목적: 두 엔진을 같은 시험에 돌리기 위한 목록
     * 기능: 빈 임시 폴더를 모델 폴더로 하는 오프라인·스트리밍 엔진을 새로 만든다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 엔진 2개
     */
    private fun engines(): List<AsrEngine> = listOf( // 새 엔진들
        SherpaOfflineEngine(zipKo, tmp.newFolder()), // 오프라인
        SherpaStreamingEngine(streamKo, tmp.newFolder()), // 스트리밍
    )

    /**
     * 목적: 지원하지 않는 언어는 네이티브 전에 거부되는지
     * 기능: 한국어 전용 모델에 "en" 을 넣으면(준비 전이어도) EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun unsupportedLanguageRejectedFirst() {
        for (engine in engines()) { // 엔진마다
            val e = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "en") } } // 예외 확인
            assertEquals("TRANSCRIBE", e.stage) // 단계
            assertEquals("UNSUPPORTED_LANGUAGE", e.code) // 코드
        }
    }

    /**
     * 목적: prepare 전 전사 거부
     * 기능: 지원 언어라도 준비 전이면 EngineException(TRANSCRIBE, NOT_PREPARED).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun transcribeBeforePrepareRejected() {
        for (engine in engines()) { // 엔진마다
            val e = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "ko") } } // 예외 확인
            assertEquals("TRANSCRIBE", e.stage) // 단계
            assertEquals("NOT_PREPARED", e.code) // 코드
        }
    }

    /**
     * 목적: 모델 파일이 없으면 네이티브 로드 전에 거부되는지
     * 기능: 빈 폴더로 prepare → EngineException(MODEL_LOAD, MODEL_FILE_MISSING), threads 0 은 IllegalArgumentException.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun prepareChecksFilesAndThreads() {
        for (engine in engines()) { // 엔진마다
            assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.prepare(0) } } // 스레드 수 검사
            val e = assertThrows(EngineException::class.java) { runBlocking { engine.prepare(4) } } // 파일 없음
            assertEquals("MODEL_LOAD", e.stage) // 단계
            assertEquals("MODEL_FILE_MISSING", e.code) // 코드
        }
    }

    /**
     * 목적: close 반복 안전·닫힌 뒤 재사용 거부
     * 기능: 준비 전 close 2번이 예외 없이 끝나고, 그 뒤 prepare 는 EngineException(MODEL_LOAD, CLOSED),
     *       transcribe 는 NOT_PREPARED, cancel 은 아무 일도 하지 않는다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun closeIsIdempotent() {
        for (engine in engines()) { // 엔진마다
            engine.close() // 1회
            engine.close() // 2회(안전해야 함)
            engine.cancel() // 닫힌 뒤 취소(무시)
            val e = assertThrows(EngineException::class.java) { runBlocking { engine.prepare(4) } } // 재준비
            assertEquals("CLOSED", e.code) // 닫힘 코드
            val t = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "ko") } } // 전사
            assertEquals("NOT_PREPARED", t.code) // 준비 안 됨
        }
    }

    /**
     * 목적: 엔진과 스펙 종류가 맞지 않으면 생성 단계에서 거부되는지
     * 기능: 오프라인 엔진에 스트리밍 스펙, 스트리밍 엔진에 오프라인 스펙, 오프라인 엔진에 whisper 스펙 → EngineException(MODEL_LOAD, CONFIG_MISMATCH).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun constructorRejectsWrongSpec() {
        val whisper = ModelCatalog.byId("whispercpp-tiny") // whisper 스펙
        for (block in listOf( // 세 경우
            { SherpaOfflineEngine(streamKo, tmp.root) }, // 스트리밍 스펙 → 오프라인 엔진
            { SherpaStreamingEngine(zipKo, tmp.root) }, // 오프라인 스펙 → 스트리밍 엔진
            { SherpaOfflineEngine(whisper, tmp.root) }, // whisper 스펙 → 오프라인 엔진
        )) {
            val e = assertThrows(EngineException::class.java) { block() } // 예외 확인
            assertEquals("MODEL_LOAD", e.stage) // 단계
            assertEquals("CONFIG_MISMATCH", e.code) // 코드
        }
    }
}
