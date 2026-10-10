// 파일명: ModelCatalogTest.kt
// 목적: 생성된 모델 카탈로그(ModelCatalog/ModelCatalogData)의 내용 계약 검증
// 기능: Phase 1 ASR 모델 집합, SHA-256 형식, 언어, maxChunkSec, .en 금지, VAD 모델 존재·파일 이름, id 중복, 단일 파일 다운로드 ↔ 파일 연결,
//       sherpa 파일 역할 ↔ 설정 필드 연결을 확인한다.
package com.cowork.stt_ondevice.model // 모델 메타 테스트 패키지

import com.cowork.stt_ondevice.vad.VadEngineFactory // VAD 팩토리(기대 파일 이름 상수)
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 카탈로그 회귀 방지
 * 기능: 스크립트로 생성된 데이터가 spec §9.2·VadEngineFactory 계약을 지키는지 본다.
 */
class ModelCatalogTest {

    /**
     * 목적: Phase 1 ASR 모델 집합 검증
     * 기능: algo → model 집합이 spec §9.2 와 같다. EN zipformer 는 "en-" 로 시작하는 1종이다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun catalogHasAllPhase1Asr() {
        val byAlgo = ModelCatalog.asr.groupBy({ it.algo }, { it.model }).mapValues { it.value.toSet() } // algo → model 집합
        val zipformer = byAlgo.getValue("sherpa-zipformer") // 오프라인 zipformer 모델들
        val enZipformer = zipformer.filter { it.startsWith("en-") } // EN 항목
        assertEquals(1, enZipformer.size) // EN 1종
        val expected = mapOf( // 기대 집합(EN 은 실제 이름으로 치환)
            "whispercpp" to setOf("tiny", "base", "small", "medium", "large-v3-turbo-q5_0", "large-v3-turbo-q8_0"), // Whisper
            "sherpa-zipformer" to setOf("ko-2024-06-24") + enZipformer, // 오프라인 zipformer
            "sherpa-zipformer-streaming" to setOf("ko-2024-06-16"), // 스트리밍 zipformer
            "sherpa-sensevoice" to setOf("small-int8"), // SenseVoice
            "sherpa-qwen3asr" to setOf("0.6B-int8"), // Qwen3-ASR
            "sherpa-omnilingual" to setOf("ctc300m-v1-int8", "ctc300m-v2-int8", "ctc1b-v2-int8"), // Omnilingual
            "vosk" to setOf("small-ko-0.22", "small-en-us-0.15"), // Vosk
        )
        assertEquals(expected, byAlgo) // 정확히 같은 집합
        assertTrue(ModelCatalog.asr.all { it.kind == ModelKind.ASR }) // asr 목록은 ASR 만
    }

    /**
     * 목적: 파일 해시 형식 검증
     * 기능: 모든 항목의 모든 파일·다운로드 SHA-256 이 소문자 64 자리 16진수이고 크기가 양수다. 파일 목록은 비어 있지 않다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun everyFileHasSha256() {
        val hex64 = Regex("^[0-9a-f]{64}$") // SHA-256 형식
        assertTrue("카탈로그가 비어 있음", ModelCatalog.all.isNotEmpty()) // 빈 카탈로그로 통과하지 않도록 // change(add)-hyungchul-2026-10-10-20:00
        for (spec in ModelCatalog.all) { // 항목마다
            assertTrue("${spec.id} 파일 없음", spec.files.isNotEmpty()) // 파일 1개 이상
            for (f in spec.files) { // 파일마다
                assertTrue("${spec.id}/${f.path} sha256", hex64.matches(f.sha256)) // 형식
                assertTrue("${spec.id}/${f.path} bytes", f.bytes > 0) // 크기
                assertTrue("${spec.id}/${f.path} role", f.role.isNotBlank()) // 역할
            }
            for (d in spec.downloads) { // 다운로드마다
                assertTrue("${spec.id} ${d.url} sha256", hex64.matches(d.sha256)) // 형식
                assertTrue("${spec.id} ${d.url} bytes", d.bytes > 0) // 크기
                assertTrue("${spec.id} ${d.url} https", d.url.startsWith("https://")) // HTTPS
            }
        }
    }

    /**
     * 목적: 언어 집합 검증
     * 기능: 한국어 zipformer = {"ko"}, Vosk EN = {"en"}, Whisper = {"ko","en"}.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun languagesCorrect() {
        val asr = ModelCatalog.asr // ASR 목록
        asr.filter { it.algo.startsWith("sherpa-zipformer") && it.model.startsWith("ko-") } // 한국어 zipformer(오프라인·스트리밍)
            .also { assertEquals(2, it.size) } // 2종
            .forEach { assertEquals(setOf("ko"), it.languages) } // ko 만
        assertEquals(setOf("en"), asr.single { it.algo == "vosk" && it.model == "small-en-us-0.15" }.languages) // Vosk EN
        assertEquals(setOf("ko"), asr.single { it.algo == "vosk" && it.model == "small-ko-0.22" }.languages) // Vosk KO
        asr.filter { it.algo == "whispercpp" }.forEach { assertEquals(setOf("ko", "en"), it.languages) } // Whisper 다국어
    }

    /**
     * 목적: 최대 입력 길이 검증
     * 기능: Whisper 30, Vosk 0, 스트리밍 0, VAD 0.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun maxChunk() {
        ModelCatalog.asr.filter { it.algo == "whispercpp" }.forEach { assertEquals(30.0, it.maxChunkSec, 0.0) } // Whisper
        ModelCatalog.asr.filter { it.algo == "vosk" }.forEach { assertEquals(0.0, it.maxChunkSec, 0.0) } // Vosk
        ModelCatalog.asr.filter { it.streaming == StreamingKind.NATIVE_STREAMING }.forEach { assertEquals(0.0, it.maxChunkSec, 0.0) } // 스트리밍
        assertEquals(0.0, ModelCatalog.byId("sherpa-zipformer-streaming-ko-2024-06-16").maxChunkSec, 0.0) // 스트리밍 zipformer
        ModelCatalog.all.filter { it.kind == ModelKind.VAD }.forEach { assertEquals(0.0, it.maxChunkSec, 0.0) } // VAD
    }

    /**
     * 목적: 영어 전용 Whisper 금지 검증
     * 기능: whispercpp 모델 이름과 파일 경로에 ".en" 이 없다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun noWhisperEnglishOnly() {
        assertEquals(6, ModelCatalog.asr.count { it.algo == "whispercpp" }) // Whisper 6종이 있어야 검사가 의미 있음 // change(add)-hyungchul-2026-10-10-20:00
        for (spec in ModelCatalog.asr.filter { it.algo == "whispercpp" }) { // Whisper 항목마다
            assertTrue(spec.model, !spec.model.contains(".en")) // 모델 이름
            spec.files.forEach { assertTrue(it.path, !it.path.contains(".en")) } // 파일 이름
        }
    }

    /**
     * 목적: VAD 모델 존재 검증
     * 기능: SILERO, PYANNOTE, FSMN 계열 모델이 있고 kind=VAD, engineConfig 계열이 같다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun vadModelsPresent() {
        for (family in listOf(VadFamily.SILERO, VadFamily.PYANNOTE, VadFamily.FSMN)) { // 필수 계열
            val spec = ModelCatalog.vadModel(family) // 조회
            assertNotNull("$family 없음", spec) // 존재
            assertEquals(ModelKind.VAD, spec!!.kind) // VAD 종류
            assertEquals(VadModelConfig(family), spec.engineConfig) // 계열 일치
        }
        assertEquals(null, ModelCatalog.vadModel(VadFamily.NONE)) // 모델 없는 계열은 null
    }

    /**
     * 목적: id 고유성 검증
     * 기능: 모든 id 가 서로 다르고 소문자이며 byId 로 같은 항목을 찾는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun idsUnique() {
        val ids = ModelCatalog.all.map { it.id } // id 목록
        assertTrue("카탈로그가 비어 있음", ids.isNotEmpty()) // 빈 목록 통과 방지 // change(add)-hyungchul-2026-10-10-20:00
        assertEquals(ids.size, ids.toSet().size) // 중복 없음
        ids.forEach { assertEquals(it, it.lowercase()) } // 소문자
        ModelCatalog.all.forEach { assertTrue(ModelCatalog.byId(it.id) === it) } // 조회 일치
    }

    /**
     * 목적: VAD 모델 파일 이름 ↔ VadEngineFactory/구현체 기대 이름 일치 검증
     * 기능: 계열별로 엔진이 모델 폴더에서 찾는 파일 이름이 카탈로그 파일 목록에 있다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun vadModelFileNamesMatchFactory() {
        val expected = mapOf( // 계열 → 엔진이 여는 파일 이름
            VadFamily.SILERO to setOf(VadEngineFactory.SILERO_FILE), // SileroVad
            VadFamily.PYANNOTE to setOf(VadEngineFactory.PYANNOTE_FILE), // PyannoteVad
            VadFamily.YAMNET to setOf(VadEngineFactory.YAMNET_FILE), // YamnetVad
            VadFamily.FSMN to setOf("model_quant.onnx", "am.mvn", "config.yaml"), // FsmnVad
            VadFamily.FIRERED to setOf("fireredvad_vad.onnx", "fireredvad_stream_vad.onnx", "cmvn.ark"), // FireRedVad
            VadFamily.MARBLENET to setOf("frame_vad_multilingual_marblenet_v2.0.onnx", "marblenet_preprocessor.json"), // MarbleNetVad
        )
        for ((family, names) in expected) { // 계열마다
            val spec = ModelCatalog.vadModel(family) // 조회
            assertNotNull("$family 없음", spec) // 존재
            val paths = spec!!.files.map { it.path }.toSet() // 카탈로그 파일 경로
            assertTrue("$family: $names ⊄ $paths", paths.containsAll(names)) // 모두 포함
        }
    }

    /**
     * 목적: 단일 파일 다운로드 ↔ 설치 파일 연결 검증
     * 기능: archive=NONE 다운로드는 bytes·SHA-256 이 같은 ModelFile 이 정확히 1개 있어야 설치기가 저장 이름을 정할 수 있다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun noneDownloadsMapToFiles() {
        assertTrue(ModelCatalog.all.any { s -> s.downloads.any { it.archive == ArchiveType.NONE } }) // 단일 파일 다운로드가 실제로 있음 // change(add)-hyungchul-2026-10-10-20:00
        for (spec in ModelCatalog.all) { // 항목마다
            for (d in spec.downloads.filter { it.archive == ArchiveType.NONE }) { // 단일 파일 다운로드
                val hits = spec.files.filter { it.sha256 == d.sha256 && it.bytes == d.bytes } // 같은 파일
                assertEquals("${spec.id} ${d.url}", 1, hits.size) // 정확히 1개
            }
        }
    }

    // change(add)-hyungchul-2026-10-10-20:00
    /**
     * 목적: sherpa 모델 파일 역할(role) ↔ sherpa-onnx v1.13.8 Kotlin 설정 필드 연결 검증
     * 기능: SherpaKind 마다 엔진이 채울 설정 필드에 대응하는 역할이 정확히 있는지 본다(README_카탈로그.md 역할 표와 같음).
     *       단일 파일 역할은 정확히 1개, tokenizer 는 같은 폴더의 파일 1개 이상이다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun sherpaRolesMatchConfig() {
        val required = mapOf( // 종류 → 단일 파일 역할 집합
            SherpaKind.ZIPFORMER_TRANSDUCER to setOf("encoder", "decoder", "joiner", "tokens"), // transducer.encoder/decoder/joiner + tokens
            SherpaKind.STREAMING_ZIPFORMER to setOf("encoder", "decoder", "joiner", "tokens"), // OnlineTransducerModelConfig + tokens
            SherpaKind.SENSEVOICE to setOf("model", "tokens"), // senseVoice.model + tokens
            SherpaKind.QWEN3_ASR to setOf("convFrontend", "encoder", "decoder"), // qwen3Asr.convFrontend/encoder/decoder
            SherpaKind.OMNILINGUAL_CTC to setOf("model", "tokens"), // omnilingual.model + tokens
        )
        val sherpa = ModelCatalog.asr.filter { it.engineConfig is SherpaConfig } // sherpa 항목
        assertEquals(8, sherpa.size) // zipformer 2 + streaming 1 + sensevoice 1 + qwen3 1 + omnilingual 3 = 8
        for (spec in sherpa) { // 항목마다
            val kind = (spec.engineConfig as SherpaConfig).kind // sherpa 종류
            val single = spec.files.filter { it.role != "tokenizer" } // 단일 파일 역할들
            assertEquals("${spec.id} 역할", required.getValue(kind), single.map { it.role }.toSet()) // 역할 집합 일치
            assertEquals("${spec.id} 역할 중복", single.size, single.map { it.role }.toSet().size) // 역할마다 1개
            val tokenizer = spec.files.filter { it.role == "tokenizer" } // tokenizer 폴더 파일
            if (kind == SherpaKind.QWEN3_ASR) { // Qwen3 는 tokenizer 폴더 필요
                assertTrue("${spec.id} tokenizer 없음", tokenizer.isNotEmpty()) // 1개 이상
                assertEquals(1, tokenizer.map { it.path.substringBeforeLast('/') }.toSet().size) // 같은 폴더
            } else { // 그 외는 tokenizer 없음
                assertTrue("${spec.id} tokenizer 불필요", tokenizer.isEmpty()) // 없음
            }
        }
    }
}
