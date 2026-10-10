// 파일명: WhisperCppEngineTest.kt
// 목적: WhisperCppEngine 의 네이티브 호출 전 단계(언어 거부·준비 전 호출·모델 파일 없음·정밀도 판정·close 반복) 검증
// 기능: JVM 에는 libstt_native.so 가 없으므로 네이티브를 부르기 전에 끝나는 경로만 확인한다.
//       (실제 전사·중단은 기기 계측 테스트 NativeSmokeTest.whisperTinyTranscribes / abortStopsWithinOneSecond 가 확인)
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine.whisper // whisper 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그
import com.cowork.stt_ondevice.model.WhisperConfig // whisper 설정
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.runBlocking // suspend 함수 동기 실행
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: whisper 엔진의 JVM 검증 가능 부분 회귀 방지
 * 기능: 네이티브 호출 전에 결정되는 오류 코드와 정밀도 판정을 확인한다.
 */
class WhisperCppEngineTest {

    @get:Rule
    val tmp = TemporaryFolder() // 임시 폴더

    private val chunk = AudioChunk(0, FloatArray(16000), listOf(SourceSpan(0.0, 1.0, 0.0)), SplitMethod.NONE) // 1 s 무음 chunk

    /**
     * 목적: 지원하지 않는 언어 거부 검증
     * 기능: languages={"ko"} 스펙으로 "en"·"ja" 를 넣으면 준비 여부와 상관없이 EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE).
     *       (언어 검사가 네이티브 호출·준비 검사보다 먼저여야 JVM 에서 이 결과가 나온다)
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun unsupportedLanguageIsRejected() {
        val spec = ModelCatalog.byId("whispercpp-tiny").copy(languages = setOf("ko")) // 한국어 전용 스펙
        val engine = WhisperCppEngine(spec, File(tmp.root, "ggml-tiny.bin")) // 엔진(파일 없음, 준비 안 함)
        for (lang in listOf("en", "ja", "")) { // 미지원 언어들
            val e = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, lang) } } // 예외 확인
            assertEquals("TRANSCRIBE", e.stage) // 단계
            assertEquals("UNSUPPORTED_LANGUAGE", e.code) // 코드
        }
        engine.close() // 정리
    }

    /**
     * 목적: 준비 전 전사 거부 검증
     * 기능: prepare 를 부르지 않고 지원 언어로 transcribe 하면 EngineException(TRANSCRIBE, NOT_PREPARED).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun transcribeBeforePrepareIsRejected() {
        val engine = WhisperCppEngine(ModelCatalog.byId("whispercpp-tiny"), File(tmp.root, "ggml-tiny.bin")) // 엔진
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "ko") } } // 예외 확인
        assertEquals("TRANSCRIBE", e.stage) // 단계
        assertEquals("NOT_PREPARED", e.code) // 코드
        engine.close() // 정리
    }

    /**
     * 목적: 모델 파일 없음 검증
     * 기능: 없는 파일로 prepare 하면 네이티브를 부르기 전에 EngineException(MODEL_LOAD, MODEL_FILE_MISSING).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun prepareWithMissingFileFails() {
        val engine = WhisperCppEngine(ModelCatalog.byId("whispercpp-tiny"), File(tmp.root, "없는-ggml-tiny.bin")) // 엔진
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.prepare(4) } } // 예외 확인
        assertEquals("MODEL_LOAD", e.stage) // 단계
        assertEquals("MODEL_FILE_MISSING", e.code) // 코드
        engine.close() // 정리
    }

    /**
     * 목적: 스레드 수 검증
     * 기능: threads < 1 이면 네이티브 전에 IllegalArgumentException.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun prepareRejectsNonPositiveThreads() {
        val model = tmp.newFile("ggml-tiny.bin") // 빈 모델 파일(존재만)
        val engine = WhisperCppEngine(ModelCatalog.byId("whispercpp-tiny"), model) // 엔진
        assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.prepare(0) } } // 0 스레드 거부
        engine.close() // 정리
    }

    /**
     * 목적: 정밀도 판정 검증
     * 기능: whisper.cpp 파일 이름 규칙 — 접미사 없는 ggml-<크기>.bin 은 f16, -q5_0/-q8_0 은 그 값, 그 밖은 null.
     *       카탈로그 whisper 스펙마다 판정값이 spec.precision 과 같은지도 본다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun precisionFromFileName() {
        assertEquals("f16", WhisperCppEngine.precisionFromFileName("ggml-tiny.bin")) // 접미사 없음
        assertEquals("f16", WhisperCppEngine.precisionFromFileName("ggml-medium.bin")) // 접미사 없음
        assertEquals("q5_0", WhisperCppEngine.precisionFromFileName("ggml-large-v3-turbo-q5_0.bin")) // q5_0
        assertEquals("q8_0", WhisperCppEngine.precisionFromFileName("ggml-large-v3-turbo-q8_0.bin")) // q8_0
        assertNull(WhisperCppEngine.precisionFromFileName("model.onnx")) // 규칙 밖
        for (spec in ModelCatalog.all.filter { it.engineConfig is WhisperConfig }) { // whisper 스펙마다
            val name = spec.files.single { it.role == "model" }.path // 모델 파일 이름
            assertEquals(spec.id, spec.precision, WhisperCppEngine.precisionFromFileName(name)) // 카탈로그와 일치
        }
    }

    /**
     * 목적: close 반복 호출 안전성 검증
     * 기능: 준비하지 않은 엔진에서 cancel·close 를 여러 번 불러도 예외가 없고, close 후 transcribe 는 NOT_PREPARED.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun closeIsIdempotent() {
        val engine = WhisperCppEngine(ModelCatalog.byId("whispercpp-tiny"), File(tmp.root, "ggml-tiny.bin")) // 엔진
        engine.cancel() // 준비 전 취소(무시)
        engine.close() // 1회
        engine.close() // 2회(무시)
        val e = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "ko") } } // 예외 확인
        assertEquals("NOT_PREPARED", e.code) // 코드
    }
}
