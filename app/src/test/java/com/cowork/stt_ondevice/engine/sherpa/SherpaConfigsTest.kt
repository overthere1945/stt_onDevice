// 파일명: SherpaConfigsTest.kt
// 목적: SherpaConfigs 가 카탈로그 role → sherpa-onnx v1.13.8 설정 필드를 README_카탈로그.md 표대로 채우는지 검증
// 기능: 가짜 스펙(임시 폴더)으로 종류별(ZIPFORMER_TRANSDUCER·STREAMING_ZIPFORMER·SENSEVOICE·QWEN3_ASR·OMNILINGUAL_CTC) 경로·공통값을 확인하고,
//       실제 카탈로그의 모든 sherpa 스펙으로 설정을 만들 수 있는지, role 누락·종류 불일치·언어 코드 변환 오류가 정해진 EngineException 인지 본다.
//       설정 클래스는 복사한 sherpa Kotlin API 의 data class 라 네이티브 라이브러리 없이 JVM 에서 만들 수 있다.
// change(add)-hyungchul-2026-10-10-20:57
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelCatalog // 실제 카탈로그
import com.cowork.stt_ondevice.model.ModelFile // 모델 파일 항목
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import com.cowork.stt_ondevice.model.SherpaConfig // sherpa 설정
import com.cowork.stt_ondevice.model.SherpaKind // sherpa 모델 종류
import com.cowork.stt_ondevice.model.WhisperConfig // whisper 설정(불일치 시험용)
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: sherpa 설정 생성 규칙 회귀 방지
 * 기능: 위 파일 머리 설명 참조.
 */
class SherpaConfigsTest {

    @get:Rule
    val tmp = TemporaryFolder() // 임시 폴더(모델 폴더 대용)

    /**
     * 목적: 시험용 가짜 스펙 만들기
     * 기능: 카탈로그의 zipformer-ko 스펙을 바탕으로 파일 목록·엔진 설정·런타임만 바꾼다(해시·크기는 검사 대상이 아니라 아무 값).
     * 입력: kind(sherpa 모델 종류), runtime(런타임), files(경로 to 역할 목록)
     * 출력: 없음
     * 리턴: ModelSpec
     */
    private fun fakeSpec(kind: SherpaKind, runtime: Runtime, vararg files: Pair<String, String>): ModelSpec =
        ModelCatalog.byId("sherpa-zipformer-ko-2024-06-24").copy( // 바탕 스펙
            id = "fake-${kind.name.lowercase()}", // 가짜 id
            runtime = runtime, // 런타임
            files = files.map { (path, role) -> ModelFile(path, 1L, "0".repeat(64), role) }, // 파일 항목
            engineConfig = SherpaConfig(kind), // 종류
        )

    /**
     * 목적: brief 의 대표 시험 — 오프라인 transducer 파일을 role 로 찾는지
     * 기능: roles encoder/decoder/joiner/tokens 가짜 스펙 + 임시 폴더 → 설정의 각 경로가 그 파일의 절대 경로,
     *       numThreads·provider("cpu")·decodingMethod("greedy_search")·modelType("transducer")·특징(16 kHz, 80 차원)이 맞는지 확인한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun configPicksFilesByRole() {
        val dir = tmp.newFolder("models", "zip") // 모델 폴더
        val spec = fakeSpec( // 가짜 스펙(파일 이름을 role 과 다르게 두어 이름이 아닌 role 로 찾는지 확인)
            SherpaKind.ZIPFORMER_TRANSDUCER, Runtime.SHERPA_OFFLINE,
            "a.onnx" to "encoder", "b.onnx" to "decoder", "c.onnx" to "joiner", "t.txt" to "tokens",
        )
        val cfg = SherpaConfigs.offline(spec, dir, 3) // 설정 생성
        val m = cfg.modelConfig // 모델 설정
        assertEquals(File(dir, "a.onnx").absolutePath, m.transducer.encoder) // encoder 경로
        assertEquals(File(dir, "b.onnx").absolutePath, m.transducer.decoder) // decoder 경로
        assertEquals(File(dir, "c.onnx").absolutePath, m.transducer.joiner) // joiner 경로
        assertEquals(File(dir, "t.txt").absolutePath, m.tokens) // tokens 경로
        assertEquals(3, m.numThreads) // 스레드 수
        assertEquals("cpu", m.provider) // CPU 실행
        assertEquals("transducer", m.modelType) // sherpa 예제 13 과 같은 modelType
        assertEquals("greedy_search", cfg.decodingMethod) // 탐색 방식
        assertEquals(16000, cfg.featConfig.sampleRate) // 입력 표본율
        assertEquals(80, cfg.featConfig.featureDim) // fbank 차원
        assertEquals("", m.senseVoice.model) // 다른 모델 설정은 비어 있어야 함
        assertEquals("", m.qwen3Asr.encoder) // 〃
        assertEquals("", m.omnilingual.model) // 〃
    }

    /**
     * 목적: 스트리밍 zipformer 설정 검증
     * 기능: OnlineTransducerModelConfig·tokens 경로, modelType("zipformer"), numThreads·provider·decodingMethod,
     *       끝점 검출 끄기(enableEndpoint=false — chunk 전체를 한 결과로 받기 위함)를 확인한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun onlineConfigPicksFilesByRole() {
        val dir = tmp.newFolder("models", "stream") // 모델 폴더
        val spec = fakeSpec( // 가짜 스트리밍 스펙
            SherpaKind.STREAMING_ZIPFORMER, Runtime.SHERPA_ONLINE,
            "e.onnx" to "encoder", "d.onnx" to "decoder", "j.onnx" to "joiner", "tok.txt" to "tokens",
        )
        val cfg = SherpaConfigs.online(spec, dir, 2) // 설정 생성
        val m = cfg.modelConfig // 모델 설정
        assertEquals(File(dir, "e.onnx").absolutePath, m.transducer.encoder) // encoder 경로
        assertEquals(File(dir, "d.onnx").absolutePath, m.transducer.decoder) // decoder 경로
        assertEquals(File(dir, "j.onnx").absolutePath, m.transducer.joiner) // joiner 경로
        assertEquals(File(dir, "tok.txt").absolutePath, m.tokens) // tokens 경로
        assertEquals(2, m.numThreads) // 스레드 수
        assertEquals("cpu", m.provider) // CPU 실행
        assertEquals("zipformer", m.modelType) // sherpa Online 예제 14 와 같은 modelType
        assertEquals("greedy_search", cfg.decodingMethod) // 탐색 방식
        assertFalse(cfg.enableEndpoint) // 끝점 검출 끔
    }

    /**
     * 목적: SenseVoice 설정 검증
     * 기능: senseVoice.model·tokens 경로, 언어는 비워 둠(엔진이 전사 때 setConfig 로 ko/en 지정), ITN 켬(PC use_itn=True 와 같음).
     *       withSenseVoiceLanguage 가 원본을 바꾸지 않고 언어만 바꾼 사본을 주는지도 본다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun senseVoiceConfig() {
        val dir = tmp.newFolder("models", "sv") // 모델 폴더
        val spec = fakeSpec(SherpaKind.SENSEVOICE, Runtime.SHERPA_OFFLINE, "model.int8.onnx" to "model", "tokens.txt" to "tokens") // 가짜 스펙
        val cfg = SherpaConfigs.offline(spec, dir, 4) // 설정 생성
        assertEquals(File(dir, "model.int8.onnx").absolutePath, cfg.modelConfig.senseVoice.model) // 모델 경로
        assertEquals(File(dir, "tokens.txt").absolutePath, cfg.modelConfig.tokens) // tokens 경로
        assertEquals("", cfg.modelConfig.senseVoice.language) // 초기 언어 없음
        assertTrue(cfg.modelConfig.senseVoice.useInverseTextNormalization) // ITN 켬
        assertEquals("", cfg.modelConfig.transducer.encoder) // transducer 비어 있음
        val ko = SherpaConfigs.withSenseVoiceLanguage(cfg, "ko") // 한국어 사본
        assertEquals("ko", ko.modelConfig.senseVoice.language) // 언어 지정
        assertEquals(cfg.modelConfig.senseVoice.model, ko.modelConfig.senseVoice.model) // 나머지 그대로
        assertEquals("", cfg.modelConfig.senseVoice.language) // 원본 불변
        assertEquals("en", SherpaConfigs.withSenseVoiceLanguage(cfg, "en").modelConfig.senseVoice.language) // 영어
    }

    /**
     * 목적: Qwen3-ASR 설정 검증
     * 기능: convFrontend/encoder/decoder 경로, tokenizer = role "tokenizer" 파일들이 든 폴더 경로, tokens 빈 문자열(sherpa 예제 61),
     *       생성 길이 상한(maxNewTokens 512, maxTotalLen 1024 — 30 s chunk 의 오디오 토큰 + 출력이 잘리지 않게)을 확인한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun qwen3Config() {
        val dir = tmp.newFolder("models", "qwen") // 모델 폴더
        val spec = fakeSpec( // 가짜 스펙(카탈로그와 같은 역할 이름)
            SherpaKind.QWEN3_ASR, Runtime.SHERPA_OFFLINE,
            "conv_frontend.onnx" to "convFrontend", "encoder.int8.onnx" to "encoder", "decoder.int8.onnx" to "decoder",
            "tokenizer/merges.txt" to "tokenizer", "tokenizer/tokenizer_config.json" to "tokenizer", "tokenizer/vocab.json" to "tokenizer",
        )
        val q = SherpaConfigs.offline(spec, dir, 4).modelConfig // 모델 설정
        assertEquals(File(dir, "conv_frontend.onnx").absolutePath, q.qwen3Asr.convFrontend) // conv 경로
        assertEquals(File(dir, "encoder.int8.onnx").absolutePath, q.qwen3Asr.encoder) // encoder 경로
        assertEquals(File(dir, "decoder.int8.onnx").absolutePath, q.qwen3Asr.decoder) // decoder 경로
        assertEquals(File(dir, "tokenizer").absolutePath, q.qwen3Asr.tokenizer) // tokenizer 폴더
        assertEquals("", q.tokens) // tokens 없음
        assertEquals(SherpaConfigs.QWEN3_MAX_NEW_TOKENS, q.qwen3Asr.maxNewTokens) // 생성 상한
        assertEquals(SherpaConfigs.QWEN3_MAX_TOTAL_LEN, q.qwen3Asr.maxTotalLen) // 전체 길이 상한
        assertEquals(512, SherpaConfigs.QWEN3_MAX_NEW_TOKENS) // Qwen3-ASR 공식 기본값
        assertEquals(1024, SherpaConfigs.QWEN3_MAX_TOTAL_LEN) // 30 s 오디오 토큰 + 512 출력 여유
    }

    /**
     * 목적: Qwen3 tokenizer 파일들이 서로 다른 폴더에 있으면 거부하는지
     * 기능: sherpa 는 tokenizer 폴더 하나만 받으므로 role "tokenizer" 파일의 상위 폴더가 둘 이상이면 EngineException(MODEL_LOAD, CONFIG_MISMATCH).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun qwen3TokenizerMustBeOneFolder() {
        val spec = fakeSpec( // 폴더가 갈린 tokenizer
            SherpaKind.QWEN3_ASR, Runtime.SHERPA_OFFLINE,
            "c.onnx" to "convFrontend", "e.onnx" to "encoder", "d.onnx" to "decoder",
            "tok1/vocab.json" to "tokenizer", "tok2/merges.txt" to "tokenizer",
        )
        val e = assertThrows(EngineException::class.java) { SherpaConfigs.offline(spec, tmp.root, 1) } // 예외 확인
        assertEquals("MODEL_LOAD", e.stage) // 단계
        assertEquals("CONFIG_MISMATCH", e.code) // 코드
    }

    /**
     * 목적: Omnilingual CTC 설정 검증
     * 기능: omnilingual.model·tokens 경로(sherpa 예제 44). 언어 필드가 없는 모델이다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun omnilingualConfig() {
        val dir = tmp.newFolder("models", "omni") // 모델 폴더
        val spec = fakeSpec(SherpaKind.OMNILINGUAL_CTC, Runtime.SHERPA_OFFLINE, "model.int8.onnx" to "model", "tokens.txt" to "tokens") // 가짜 스펙
        val m = SherpaConfigs.offline(spec, dir, 6).modelConfig // 모델 설정
        assertEquals(File(dir, "model.int8.onnx").absolutePath, m.omnilingual.model) // 모델 경로
        assertEquals(File(dir, "tokens.txt").absolutePath, m.tokens) // tokens 경로
        assertEquals(6, m.numThreads) // 스레드 수
    }

    /**
     * 목적: 실제 카탈로그의 모든 sherpa 스펙으로 설정을 만들 수 있는지
     * 기능: SHERPA_OFFLINE 은 offline, SHERPA_ONLINE 은 online 으로 만들고, 스펙의 모든 role 파일 경로(qwen3 tokenizer 는 폴더)가
     *       설정 어딘가에 들어 있는지(빠뜨린 파일 없음) 확인한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun allCatalogSherpaSpecsBuild() {
        val dir = tmp.newFolder("models", "cat") // 모델 폴더
        val specs = ModelCatalog.all.filter { it.runtime == Runtime.SHERPA_OFFLINE || it.runtime == Runtime.SHERPA_ONLINE } // sherpa 스펙
        assertEquals(8, specs.size) // 카탈로그의 sherpa ASR 8개
        for (spec in specs) { // 스펙마다
            val text = if (spec.runtime == Runtime.SHERPA_OFFLINE) { // 오프라인
                SherpaConfigs.offline(spec, dir, 4).toString() // 설정 문자열(data class toString 은 모든 필드 포함)
            } else { // 스트리밍
                SherpaConfigs.online(spec, dir, 4).toString() // 설정 문자열
            }
            for (f in spec.files) { // 파일마다
                val expected = if (f.role == "tokenizer") File(dir, f.path).parentFile!!.absolutePath else File(dir, f.path).absolutePath // 기대 경로
                assertTrue("${spec.id}: ${f.path}(${f.role}) 가 설정에 없음", text.contains(expected)) // 포함 확인
            }
        }
    }

    /**
     * 목적: 필요한 role 이 스펙에 없을 때 거부 검증
     * 기능: transducer 스펙에서 joiner 를 빼면 EngineException(MODEL_LOAD, MODEL_FILE_MISSING), 메시지에 role 이름.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun missingRoleIsRejected() {
        val spec = fakeSpec(SherpaKind.ZIPFORMER_TRANSDUCER, Runtime.SHERPA_OFFLINE, "a" to "encoder", "b" to "decoder", "t" to "tokens") // joiner 없음
        val e = assertThrows(EngineException::class.java) { SherpaConfigs.offline(spec, tmp.root, 1) } // 예외 확인
        assertEquals("MODEL_LOAD", e.stage) // 단계
        assertEquals("MODEL_FILE_MISSING", e.code) // 코드
        assertTrue(e.message!!.contains("joiner")) // 빠진 role
    }

    /**
     * 목적: 설정 종류 불일치 거부 검증
     * 기능: 스트리밍 종류를 offline 으로, 오프라인 종류를 online 으로, whisper 설정 스펙을 offline 으로 만들면 EngineException(MODEL_LOAD, CONFIG_MISMATCH).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun kindMismatchIsRejected() {
        val files = arrayOf("a" to "encoder", "b" to "decoder", "c" to "joiner", "t" to "tokens") // 공통 파일
        val streaming = fakeSpec(SherpaKind.STREAMING_ZIPFORMER, Runtime.SHERPA_ONLINE, *files) // 스트리밍 스펙
        val offline = fakeSpec(SherpaKind.ZIPFORMER_TRANSDUCER, Runtime.SHERPA_OFFLINE, *files) // 오프라인 스펙
        val whisper = offline.copy(engineConfig = WhisperConfig()) // whisper 설정 스펙
        for (block in listOf( // 세 경우
            { SherpaConfigs.offline(streaming, tmp.root, 1) }, // 스트리밍 → offline
            { SherpaConfigs.online(offline, tmp.root, 1) }, // 오프라인 → online
            { SherpaConfigs.offline(whisper, tmp.root, 1) }, // whisper → offline
        )) {
            val e = assertThrows(EngineException::class.java) { block() } // 예외 확인
            assertEquals("MODEL_LOAD", e.stage) // 단계
            assertEquals("CONFIG_MISMATCH", e.code) // 코드
        }
    }

    /**
     * 목적: 모델별 언어 코드 변환 검증
     * 기능: SenseVoice 는 "ko"/"en" 그대로(sherpa 허용값 auto/zh/en/ja/ko/yue), Qwen3-ASR 은 언어 이름 "Korean"/"English"
     *       (sherpa 가 프롬프트에 "language <이름>" 으로 넣음, 예제 주석 "Korean"). 그 밖의 코드는 EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun languageCodesPerModel() {
        assertEquals("ko", SherpaConfigs.senseVoiceLanguage("ko")) // SenseVoice 한국어
        assertEquals("en", SherpaConfigs.senseVoiceLanguage("en")) // SenseVoice 영어
        assertEquals("Korean", SherpaConfigs.qwen3Language("ko")) // Qwen3 한국어
        assertEquals("English", SherpaConfigs.qwen3Language("en")) // Qwen3 영어
        for (block in listOf({ SherpaConfigs.senseVoiceLanguage("ja") }, { SherpaConfigs.qwen3Language("") })) { // 미지원
            val e = assertThrows(EngineException::class.java) { block() } // 예외 확인
            assertEquals("TRANSCRIBE", e.stage) // 단계
            assertEquals("UNSUPPORTED_LANGUAGE", e.code) // 코드
        }
    }
}
