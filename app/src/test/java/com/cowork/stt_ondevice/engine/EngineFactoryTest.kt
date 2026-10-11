// 파일명: EngineFactoryTest.kt
// 목적: EngineFactory 의 런타임별 분기와 오류 코드 검증(네이티브 라이브러리 없이 JVM 에서 실행)
// 기능: WHISPER_CPP 스펙은 WhisperCppEngine 을 만들고 모델 파일 경로가 role "model" 파일인지,
//       아직 구현하지 않은 런타임은 EngineException("MODEL_LOAD","NOT_IMPLEMENTED"),
//       ASR 이 아닌 스펙·role "model" 파일이 없는 whisper 스펙은 각각 정해진 코드로 거부되는지 확인한다.
//       change(add)-hyungchul-2026-10-10-20:57 SHERPA_OFFLINE/SHERPA_ONLINE 스펙은 SherpaOfflineEngine/SherpaStreamingEngine(모델 폴더 그대로)을 만들고,
//         whisper 런타임에 WhisperConfig 가 아닌 스펙은 EngineException("MODEL_LOAD","CONFIG_MISMATCH") 인지 확인한다.
//       change(add)-hyungchul-2026-10-10-21:41 VOSK 스펙은 VoskEngine(모델 폴더 그대로)을 만들고 VoskConfig 가 아니면 CONFIG_MISMATCH 인지 확인한다.
//         모든 ASR 런타임이 구현되었으므로 NOT_IMPLEMENTED 시험은 카탈로그에 없는 런타임(ORT)으로 바꾼 ASR 스펙으로 한다.
//       change(add)-hyungchul-2026-10-11-18:35 시스템 엔진 항목(MLKIT_GENAI·PLATFORM_ONDEVICE)은 아직 생성기가 없어(작업 6 에서 연결) 카탈로그 전수 생성 시험에서 제외한다(2차 작업 2).
//       change(add)-hyungchul-2026-10-11-19:18 시스템 spec 은 EngineFactory 가 아니라 ManagedEngine.factory 의 systemCreate(앱: SystemEngines)로 가는지,
//         systemCreate 를 주지 않으면 NOT_IMPLEMENTED 로 거부되고 다른 엔진으로 대체되지 않는지 확인한다(2차 작업 6).
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine // 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.sherpa.SherpaOfflineEngine // change(add)-hyungchul-2026-10-10-20:57 sherpa 오프라인 엔진
import com.cowork.stt_ondevice.engine.sherpa.SherpaStreamingEngine // change(add)-hyungchul-2026-10-10-20:57 sherpa 스트리밍 엔진
import com.cowork.stt_ondevice.engine.vosk.VoskEngine // change(add)-hyungchul-2026-10-10-21:41 Vosk 엔진
import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더(임시 루트) // change(add)-hyungchul-2026-10-11-19:18
import com.cowork.stt_ondevice.engine.whisper.WhisperCppEngine // whisper.cpp 엔진
import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그
import com.cowork.stt_ondevice.model.ModelKind // ASR/VAD
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타 // change(add)-hyungchul-2026-10-11-19:18
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소(사용 횟수) // change(add)-hyungchul-2026-10-11-19:18
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import com.cowork.stt_ondevice.model.SherpaConfig // change(add)-hyungchul-2026-10-10-20:57 sherpa 설정(불일치 시험용)
import com.cowork.stt_ondevice.model.SherpaKind // change(add)-hyungchul-2026-10-10-20:57 sherpa 모델 종류
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 엔진 카탈로그 // change(add)-hyungchul-2026-10-11-19:18
import com.cowork.stt_ondevice.model.WhisperConfig // change(add)-hyungchul-2026-10-10-21:41 whisper 설정(불일치 시험용)
import com.cowork.stt_ondevice.model.isSystem // change(add)-hyungchul-2026-10-11-18:35 시스템 엔진 여부
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk // change(add)-hyungchul-2026-10-11-19:18
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증 // change(add)-hyungchul-2026-10-11-19:18
import org.junit.Assert.assertNotSame // 다른 객체 검증 // change(add)-hyungchul-2026-10-11-19:18
import org.junit.Assert.assertSame // 같은 객체 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-19:18
/**
 * 목적: 시스템 생성기 경로 확인용 가짜 엔진
 * 기능: 아무 일도 하지 않고 close 호출 수만 센다(prepare·transcribe 는 이 테스트에서 부르지 않음).
 * 입력: spec(모델 메타)
 */
private class CountingEngine(override val spec: ModelSpec) : AsrEngine {
    var closeCount = 0 // close 호출 수

    /**
     * 목적: 가짜 준비
     * 기능: 고정 PrepareInfo 를 돌려준다.
     * 입력: threads(스레드 수)
     * 출력: 없음
     * 리턴: PrepareInfo
     */
    override suspend fun prepare(threads: Int): PrepareInfo = PrepareInfo(0.0, "fake", "system(fake)", "", 0) // 고정 값

    /**
     * 목적: 가짜 전사
     * 기능: 빈 텍스트를 돌려준다.
     * 입력: chunk(오디오 조각), lang(언어)
     * 출력: 없음
     * 리턴: ChunkText
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText = ChunkText("", "", null, 0.0) // 고정 값

    /**
     * 목적: 가짜 취소
     * 기능: 아무 일도 하지 않는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() = Unit // 없음

    /**
     * 목적: 가짜 해제
     * 기능: 호출 수를 센다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() { closeCount++ } // 호출 수
}

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
     * 목적: 미구현 런타임 거부와 카탈로그 ASR 구현 완료 검증
     * 기능: ① 카탈로그의 모든 ASR 스펙은 EngineFactory.create 가 NOT_IMPLEMENTED 를 던지지 않고 엔진을 만든다(생성만, 네이티브 로드 없음).
     *       ② 구현되지 않은 런타임(ORT·LITERT)을 가진 ASR 스펙은 EngineException(MODEL_LOAD, NOT_IMPLEMENTED) 을 던진다(다른 엔진으로 대체 금지).
     *       change(add)-hyungchul-2026-10-10-21:41 Vosk 까지 구현되어 카탈로그에는 미구현 ASR 스펙이 없으므로 ②는 whisper 스펙의 runtime 만 바꿔 시험한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun otherRuntimesThrowNotImplemented() {
        val dir = tmp.newFolder("m") // 모델 폴더
        val asr = ModelCatalog.all.filter { it.kind == ModelKind.ASR && !it.isSystem } // change(add)-hyungchul-2026-10-10-21:41 카탈로그 ASR 스펙들 // change(add)-hyungchul-2026-10-11-18:35 시스템 엔진은 제외(생성기 연결은 작업 6)
        assertTrue("카탈로그에 ASR 스펙이 있어야 함", asr.isNotEmpty()) // 대상 존재
        for (spec in asr) { // 스펙마다
            EngineFactory.create(spec, dir).close() // 생성만(prepare 안 함) — NOT_IMPLEMENTED 등 어떤 EngineException 도 나오면 실패
        }
        val specs = listOf(Runtime.ORT, Runtime.LITERT).map { ModelCatalog.byId("whispercpp-tiny").copy(runtime = it) } // 미구현 런타임 ASR 스펙
        for (spec in specs) { // 스펙마다
            val e = assertThrows(EngineException::class.java) { EngineFactory.create(spec, dir) } // 예외 확인
            assertEquals("${spec.id}: stage", "MODEL_LOAD", e.stage) // 단계
            assertEquals("${spec.id}: code", "NOT_IMPLEMENTED", e.code) // 코드
            assertTrue("${spec.id}: 메시지에 런타임 이름", e.message!!.contains(spec.runtime.name)) // 사유
        }
    }

    // change(add)-hyungchul-2026-10-10-21:41
    /**
     * 목적: Vosk 런타임 분기 검증
     * 기능: 카탈로그의 VOSK 스펙(KO·EN 2개)이 VoskEngine 을 만들고, spec·모델 폴더가 그대로인지, prepare 전 close 가 안전한지 확인한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun voskRuntimeCreatesVoskEngine() {
        val dir = tmp.newFolder("models", "v") // 모델 폴더
        val specs = ModelCatalog.all.filter { it.runtime == Runtime.VOSK } // Vosk 스펙들
        assertEquals(2, specs.size) // small ko / small en-us
        for (spec in specs) { // 스펙마다
            val engine = EngineFactory.create(spec, dir) // 생성
            assertTrue("${spec.id}: VoskEngine 이어야 함", engine is VoskEngine) // 타입
            assertSame(spec, engine.spec) // 스펙 보존
            assertEquals(dir, (engine as VoskEngine).modelDir) // 모델 폴더
            engine.close() // prepare 전 close 안전
        }
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: sherpa 런타임 분기 검증
     * 기능: 카탈로그의 SHERPA_OFFLINE 스펙은 SherpaOfflineEngine, SHERPA_ONLINE 스펙은 SherpaStreamingEngine 을 만들고,
     *       spec·모델 폴더가 그대로인지, prepare 전 close 가 안전한지 확인한다(네이티브는 prepare 에서만 올라감).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun sherpaRuntimesCreateSherpaEngines() {
        val dir = tmp.newFolder("models", "s") // 모델 폴더
        val offline = ModelCatalog.all.filter { it.runtime == Runtime.SHERPA_OFFLINE } // 오프라인 스펙들
        val online = ModelCatalog.all.filter { it.runtime == Runtime.SHERPA_ONLINE } // 스트리밍 스펙들
        assertEquals(7, offline.size) // zipformer KO/EN, SenseVoice, Qwen3, Omnilingual 3
        assertEquals(1, online.size) // streaming zipformer KO
        for (spec in offline) { // 오프라인
            val engine = EngineFactory.create(spec, dir) // 생성
            assertTrue("${spec.id}: SherpaOfflineEngine 이어야 함", engine is SherpaOfflineEngine) // 타입
            assertSame(spec, engine.spec) // 스펙 보존
            assertEquals(dir, (engine as SherpaOfflineEngine).modelDir) // 모델 폴더
            engine.close() // prepare 전 close 안전
        }
        for (spec in online) { // 스트리밍
            val engine = EngineFactory.create(spec, dir) // 생성
            assertTrue("${spec.id}: SherpaStreamingEngine 이어야 함", engine is SherpaStreamingEngine) // 타입
            assertSame(spec, engine.spec) // 스펙 보존
            assertEquals(dir, (engine as SherpaStreamingEngine).modelDir) // 모델 폴더
            engine.close() // prepare 전 close 안전
        }
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: 런타임과 설정이 맞지 않는 스펙 거부 검증
     * 기능: whisper 런타임 + SherpaConfig, sherpa 오프라인 런타임 + 스트리밍 종류, sherpa 스트리밍 런타임 + 오프라인 종류,
     *       change(add)-hyungchul-2026-10-10-21:41 Vosk 런타임 + 다른 설정 2가지 →
     *       모두 EngineException(MODEL_LOAD, CONFIG_MISMATCH)(IllegalArgumentException 이 새지 않음).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun configMismatchIsEngineException() {
        val specs = listOf( // 불일치 스펙 5개
            ModelCatalog.byId("whispercpp-tiny").copy(engineConfig = SherpaConfig(SherpaKind.SENSEVOICE)), // whisper + sherpa 설정
            ModelCatalog.byId("sherpa-zipformer-ko-2024-06-24").copy(engineConfig = SherpaConfig(SherpaKind.STREAMING_ZIPFORMER)), // 오프라인 + 스트리밍 종류
            ModelCatalog.byId("sherpa-zipformer-streaming-ko-2024-06-16").copy(engineConfig = SherpaConfig(SherpaKind.ZIPFORMER_TRANSDUCER)), // 스트리밍 + 오프라인 종류
            ModelCatalog.byId("vosk-small-ko-0.22").copy(engineConfig = WhisperConfig(5)), // change(add)-hyungchul-2026-10-10-21:41 Vosk + whisper 설정
            ModelCatalog.byId("vosk-small-en-us-0.15").copy(engineConfig = SherpaConfig(SherpaKind.SENSEVOICE)), // change(add)-hyungchul-2026-10-10-21:41 Vosk + sherpa 설정
        )
        for (spec in specs) { // 스펙마다
            val e = assertThrows(EngineException::class.java) { EngineFactory.create(spec, tmp.root) } // 예외 확인
            assertEquals("${spec.id}: stage", "MODEL_LOAD", e.stage) // 단계
            assertEquals("${spec.id}: code", "CONFIG_MISMATCH", e.code) // 코드
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

    // change(add)-hyungchul-2026-10-11-19:18
    /**
     * 목적: 시스템 spec 의 생성 경로 검증
     * 기능: ManagedEngine.factory(store, create = 기록용, systemCreate = 가짜) 로 카탈로그의 모든 시스템 spec 을 만들면
     *       가짜 엔진이 감싸져 나오고(일반 생성기 create 는 한 번도 불리지 않음), 생성 중 사용 중(useCount 1)이며 close 하면 가짜가 닫히고 release 된다(0).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun systemSpecGoesToSystemCreator() {
        val store = ModelStore(AppDirs(tmp.newFolder("app"))) // 실제 저장소(시스템 spec 은 항상 READY)
        var createCalls = 0 // 일반 생성기 호출 수
        val made = ArrayList<CountingEngine>() // systemCreate 가 만든 가짜
        val factory = ManagedEngine.factory( // 시험 대상 생성기
            store, // 저장소
            create = { _, _ -> createCalls++; error("일반 생성기가 불리면 안 됨") }, // 불리면 실패
            systemCreate = { s -> CountingEngine(s).also { made += it } }, // 가짜 시스템 생성기
        )
        assertEquals(6, SystemCatalog.all.size) // 엔진 2종 × 배속 3
        for (spec in SystemCatalog.all) { // 시스템 spec 마다
            val e = factory(spec) // 생성
            val fake = made.last() // 방금 만든 가짜
            assertNotSame(fake, e) // ManagedEngine 으로 감싸짐
            assertSame(spec, e.spec) // spec 위임
            assertEquals("${spec.id}: 생성 중 사용 중", 1, store.useCountOf(spec)) // acquire 됨
            e.close() // 해제
            assertEquals("${spec.id}: 가짜 close", 1, fake.closeCount) // 원본 닫힘
            assertEquals("${spec.id}: release", 0, store.useCountOf(spec)) // release 됨
        }
        assertEquals(0, createCalls) // 일반 생성기 미호출
        assertEquals(6, made.size) // 시스템 생성기 6회
    }

    // change(add)-hyungchul-2026-10-11-19:18
    /**
     * 목적: 시스템 생성기가 없을 때의 거부 검증
     * 기능: systemCreate 를 주지 않은 ManagedEngine.factory 는 시스템 spec 을 EngineException(MODEL_LOAD, NOT_IMPLEMENTED,
     *       "시스템 엔진 생성기가 없음")으로 거부하고, 일반 생성기 create 로 넘기지 않으며(다른 엔진으로 대체 금지), 사용 횟수를 되돌린다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun systemSpecWithoutSystemCreatorIsNotImplemented() {
        val store = ModelStore(AppDirs(tmp.newFolder("app2"))) // 실제 저장소
        var createCalls = 0 // 일반 생성기 호출 수
        val factory = ManagedEngine.factory(store, create = { s, _ -> createCalls++; CountingEngine(s) }) // systemCreate 생략
        for (spec in SystemCatalog.all) { // 시스템 spec 마다
            val e = assertThrows(EngineException::class.java) { factory(spec) } // 거부
            assertEquals("${spec.id}: stage", "MODEL_LOAD", e.stage) // 단계
            assertEquals("${spec.id}: code", "NOT_IMPLEMENTED", e.code) // 코드
            assertTrue("${spec.id}: 사유", e.message!!.contains("시스템 엔진 생성기가 없음")) // 한국어 사유
            assertEquals("${spec.id}: release", 0, store.useCountOf(spec)) // 사용 횟수 복귀
        }
        assertEquals(0, createCalls) // 다른 엔진으로 대체하지 않음
        assertFalse(SystemCatalog.all.any { store.useCountOf(it) != 0 }) // 누수 없음
    }
}
