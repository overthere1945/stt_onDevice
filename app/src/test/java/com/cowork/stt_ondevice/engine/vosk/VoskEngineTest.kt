// 파일명: VoskEngineTest.kt
// 목적: VoskEngine 의 네이티브 호출 전 단계(스펙 검사·언어·준비 상태·파일 존재·닫힘) 오류 코드를 JVM 에서 검증
// 기능: 네이티브(libvosk.so)를 올리지 않고 확인 가능한 경로만 다룬다 — 설정 불일치, 미지원 언어, prepare 전 transcribe,
//       모델 파일 없음, threads < 1, close 뒤 prepare/transcribe, close 반복 호출. 실제 전사는 기기 계측 테스트(NativeSmokeTest.voskKoTranscribes 등)가 맡는다.
// change(add)-hyungchul-2026-10-10-21:41
package com.cowork.stt_ondevice.engine.vosk // Vosk 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그
import com.cowork.stt_ondevice.model.WhisperConfig // 설정 불일치 시험용
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.runBlocking // suspend 함수 동기 실행
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙

// change(add)-hyungchul-2026-10-10-21:41
/**
 * 목적: VoskEngine 상태·오류 코드 회귀 방지
 * 기능: 위 파일 머리 설명 참조.
 */
class VoskEngineTest {

    @get:Rule
    val tmp = TemporaryFolder() // 임시 폴더(빈 모델 폴더 대용)

    private val spec = ModelCatalog.byId("vosk-small-ko-0.22") // 시험용 Vosk 스펙
    private val chunk = AudioChunk(0, FloatArray(1600), listOf(SourceSpan(0.0, 0.1, 0.0)), SplitMethod.NONE) // 0.1 s 무음 chunk

    /**
     * 목적: 설정 불일치 거부 검증
     * 기능: VoskConfig 가 아닌 스펙으로 만들면 EngineException(MODEL_LOAD, CONFIG_MISMATCH).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun configMismatchIsRejected() {
        val e = assertThrows(EngineException::class.java) { VoskEngine(spec.copy(engineConfig = WhisperConfig(5)), tmp.root) } // 예외 확인
        assertEquals("MODEL_LOAD", e.stage) // 단계
        assertEquals("CONFIG_MISMATCH", e.code) // 코드
    }

    /**
     * 목적: 전사 전 단계 오류 검증
     * 기능: 미지원 언어(KO 모델에 en) → UNSUPPORTED_LANGUAGE, prepare 전 transcribe → NOT_PREPARED.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun transcribeErrorsBeforeNative() {
        VoskEngine(spec, tmp.root).use { engine -> // 엔진
            val lang = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "en") } } // 미지원 언어
            assertEquals("UNSUPPORTED_LANGUAGE", lang.code) // 코드
            val np = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "ko") } } // 준비 전
            assertEquals("TRANSCRIBE", np.stage) // 단계
            assertEquals("NOT_PREPARED", np.code) // 코드
        }
    }

    /**
     * 목적: prepare 사전 검사 검증
     * 기능: threads 0 → IllegalArgumentException, 모델 파일이 없는 폴더 → MODEL_FILE_MISSING(메시지에 없는 파일 경로).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun prepareChecksBeforeNative() {
        VoskEngine(spec, tmp.root).use { engine -> // 빈 모델 폴더
            assertThrows(IllegalArgumentException::class.java) { runBlocking { engine.prepare(0) } } // 스레드 수 오류
            val e = assertThrows(EngineException::class.java) { runBlocking { engine.prepare(4) } } // 파일 없음
            assertEquals("MODEL_LOAD", e.stage) // 단계
            assertEquals("MODEL_FILE_MISSING", e.code) // 코드
            assertEquals(true, e.message!!.contains("am/final.mdl")) // 없는 파일 이름이 메시지에 있음
        }
    }

    /**
     * 목적: close 뒤 동작 검증
     * 기능: close 를 두 번 불러도 안전하고, 닫힌 뒤 prepare → CLOSED, transcribe → NOT_PREPARED.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun closedEngineRejectsWork() {
        val engine = VoskEngine(spec, tmp.root) // 엔진
        engine.close() // 첫 close
        engine.close() // 반복 close 안전
        val p = assertThrows(EngineException::class.java) { runBlocking { engine.prepare(4) } } // 닫힌 뒤 prepare
        assertEquals("CLOSED", p.code) // 코드
        val t = assertThrows(EngineException::class.java) { runBlocking { engine.transcribe(chunk, "ko") } } // 닫힌 뒤 transcribe
        assertEquals("NOT_PREPARED", t.code) // 코드
    }
}
