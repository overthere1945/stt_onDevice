// 파일명: EngineFactoryTest.kt
// 목적: EngineFactory 의 런타임별 분기와 오류 코드 검증(네이티브 라이브러리 없이 JVM 에서 실행)
// 기능: WHISPER_CPP 스펙은 WhisperCppEngine 을 만들고 모델 파일 경로가 role "model" 파일인지,
//       아직 구현하지 않은 런타임은 EngineException("MODEL_LOAD","NOT_IMPLEMENTED"),
//       ASR 이 아닌 스펙·role "model" 파일이 없는 whisper 스펙은 각각 정해진 코드로 거부되는지 확인한다.
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine // 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.whisper.WhisperCppEngine // whisper.cpp 엔진
import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그
import com.cowork.stt_ondevice.model.ModelKind // ASR/VAD
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertSame // 같은 객체 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: 엔진 팩토리 회귀 방지
 * 기능: 런타임 분기·오류 코드를 확인한다. 엔진 생성만 하고 prepare 는 부르지 않으므로 네이티브가 필요 없다.
 */
class EngineFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder() // 임시 폴더(모델 폴더 대용)

    /**
     * 목적: whisper.cpp 스펙 분기 검증
     * 기능: 카탈로그의 모든 WHISPER_CPP 스펙이 WhisperCppEngine 을 만들고, spec 이 그대로이며,
     *       모델 파일이 modelDir/<role "model" 파일 경로> 인지 확인한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun whisperRuntimeCreatesWhisperCppEngine() {
        val dir = tmp.newFolder("models", "x") // 모델 폴더(파일은 없어도 생성은 됨)
        val specs = ModelCatalog.all.filter { it.runtime == Runtime.WHISPER_CPP } // whisper 스펙들
        assertTrue("카탈로그에 whisper 스펙이 있어야 함", specs.isNotEmpty()) // 대상 존재
        for (spec in specs) { // 스펙마다
            val engine = EngineFactory.create(spec, dir) // 생성
            assertTrue("${spec.id}: WhisperCppEngine 이어야 함", engine is WhisperCppEngine) // 타입
            assertSame(spec, engine.spec) // 스펙 보존
            val expected = File(dir, spec.files.single { it.role == "model" }.path) // 기대 모델 파일
            assertEquals(expected, (engine as WhisperCppEngine).modelFile) // 경로 일치
            engine.close() // prepare 전 close 도 안전해야 함
        }
    }

    /**
     * 목적: 미구현 런타임 거부 검증
     * 기능: 카탈로그의 WHISPER_CPP 가 아닌 ASR 스펙은 모두 EngineException(MODEL_LOAD, NOT_IMPLEMENTED) 을 던진다(다른 엔진으로 대체 금지).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun otherRuntimesThrowNotImplemented() {
        val dir = tmp.newFolder("m") // 모델 폴더
        val specs = ModelCatalog.all.filter { it.kind == ModelKind.ASR && it.runtime != Runtime.WHISPER_CPP } // 미구현 ASR 스펙
        assertTrue("미구현 런타임 스펙이 있어야 함", specs.isNotEmpty()) // 대상 존재
        for (spec in specs) { // 스펙마다
            val e = assertThrows(EngineException::class.java) { EngineFactory.create(spec, dir) } // 예외 확인
            assertEquals("${spec.id}: stage", "MODEL_LOAD", e.stage) // 단계
            assertEquals("${spec.id}: code", "NOT_IMPLEMENTED", e.code) // 코드
            assertTrue("${spec.id}: 메시지에 런타임 이름", e.message!!.contains(spec.runtime.name)) // 사유
        }
    }

    /**
     * 목적: ASR 이 아닌 스펙 거부 검증
     * 기능: VAD 스펙을 넣으면 EngineException(MODEL_LOAD, NOT_ASR_MODEL).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun vadSpecIsRejected() {
        val vad = ModelCatalog.all.first { it.kind == ModelKind.VAD } // VAD 스펙 하나
        val e = assertThrows(EngineException::class.java) { EngineFactory.create(vad, tmp.root) } // 예외 확인
        assertEquals("MODEL_LOAD", e.stage) // 단계
        assertEquals("NOT_ASR_MODEL", e.code) // 코드
    }

    /**
     * 목적: whisper 스펙에 role "model" 파일이 없을 때 거부 검증
     * 기능: files 를 비운 whisper 스펙은 EngineException(MODEL_LOAD, MODEL_FILE_MISSING).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun whisperSpecWithoutModelRoleIsRejected() {
        val spec = ModelCatalog.byId("whispercpp-tiny").copy(files = emptyList()) // 파일 목록 없는 스펙
        val e = assertThrows(EngineException::class.java) { EngineFactory.create(spec, tmp.root) } // 예외 확인
        assertEquals("MODEL_LOAD", e.stage) // 단계
        assertEquals("MODEL_FILE_MISSING", e.code) // 코드
    }
}
