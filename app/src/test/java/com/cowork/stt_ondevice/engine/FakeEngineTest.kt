// 파일명: FakeEngineTest.kt
// 목적: AsrEngine 계약의 기본 동작(인터페이스 기본 구현) 검증
// 기능: 네이티브 없이 동작하는 가짜 엔진(FakeEngine)으로 supports() 기본 구현이 spec.languages 만 보고 판정하는지,
//       계약 타입(PrepareInfo·ChunkText·EngineException)이 값을 그대로 담는지 확인한다.
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine // 엔진 테스트 패키지

import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그(스펙 복사 원본)
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.test.runTest // 코루틴 테스트 실행기
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: 계약 기본 동작 테스트용 가짜 엔진
 * 기능: supports() 는 재정의하지 않아 인터페이스 기본 구현을 쓴다. prepare/transcribe 는 고정 값을 돌려주고, cancel/close 호출 수를 센다.
 * 입력: spec(모델 메타)
 */
private class FakeEngine(override val spec: ModelSpec) : AsrEngine {
    var cancelCount = 0 // cancel 호출 수
    var closeCount = 0 // close 호출 수

    /**
     * 목적: 가짜 준비
     * 기능: 고정 PrepareInfo 를 돌려준다.
     * 입력: threads(스레드 수)
     * 출력: 없음
     * 리턴: PrepareInfo(loadSec 0.5, 버전 "fake", CPU, f16, threads)
     */
    override suspend fun prepare(threads: Int): PrepareInfo = PrepareInfo(0.5, "fake", "CPU", "f16", threads) // 고정 값

    /**
     * 목적: 가짜 전사
     * 기능: chunk 번호를 텍스트로 돌려준다.
     * 입력: chunk(오디오 조각), lang(언어)
     * 출력: 없음
     * 리턴: ChunkText("chunk-<index>", 같은 raw, 감지 언어 null, 0.1 초)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText =
        ChunkText("chunk-${chunk.index}", "chunk-${chunk.index}", null, 0.1) // 고정 값

    /**
     * 목적: 가짜 취소
     * 기능: 호출 수만 센다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() { cancelCount++ } // 호출 수 증가

    /**
     * 목적: 가짜 해제
     * 기능: 호출 수만 센다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() { closeCount++ } // 호출 수 증가
}

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: AsrEngine 계약 기본 동작 회귀 방지
 * 기능: supports 기본 구현과 계약 타입의 값 보존을 확인한다.
 */
class FakeEngineTest {

    /**
     * 목적: supports() 기본 구현 검증
     * 기능: languages={"ko"} 인 spec 으로 만든 엔진은 "ko" 만 지원하고 "en"·빈 문자열은 지원하지 않는다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun defaultSupports() {
        val spec = ModelCatalog.byId("whispercpp-tiny").copy(languages = setOf("ko")) // 한국어 전용으로 바꾼 스펙
        val engine = FakeEngine(spec) // 가짜 엔진
        assertTrue(engine.supports("ko")) // ko 지원
        assertFalse(engine.supports("en")) // en 미지원
        assertFalse(engine.supports("")) // 빈 언어 미지원
    }

    /**
     * 목적: 계약 타입의 값 보존과 AutoCloseable 동작 검증
     * 기능: prepare/transcribe 결과 필드가 그대로이고, use { } 블록이 close 를 1회 부르며, EngineException 이 stage·code·message 를 담는다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun contractTypesKeepValues() = runTest {
        val spec = ModelCatalog.byId("whispercpp-tiny") // ko,en 스펙
        val engine = FakeEngine(spec) // 가짜 엔진
        engine.use { e -> // AutoCloseable
            val info = e.prepare(4) // 준비
            assertEquals(PrepareInfo(0.5, "fake", "CPU", "f16", 4), info) // 필드 보존
            val chunk = AudioChunk(3, FloatArray(160), listOf(SourceSpan(0.0, 0.01, 0.0)), SplitMethod.NONE) // 10 ms chunk
            val text = e.transcribe(chunk, "ko") // 전사
            assertEquals("chunk-3", text.text) // 텍스트
            assertEquals("chunk-3", text.rawText) // 원문
            assertNull(text.detectedLang) // 감지 언어 없음
            assertEquals(0.1, text.inferSec, 0.0) // 추론 시간
            e.cancel() // 취소
        }
        assertEquals(1, engine.cancelCount) // cancel 1회
        assertEquals(1, engine.closeCount) // use 가 close 1회
        val ex = EngineException("TRANSCRIBE", "ABORTED", "중단됨") // 예외 생성
        assertEquals("TRANSCRIBE", ex.stage) // 단계 보존
        assertEquals("ABORTED", ex.code) // 코드 보존
        assertEquals("중단됨", ex.message) // 메시지 보존
    }
}
