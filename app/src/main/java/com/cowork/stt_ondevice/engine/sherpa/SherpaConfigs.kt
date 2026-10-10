// 파일명: SherpaConfigs.kt
// 목적: 카탈로그 ModelSpec(파일 role) → sherpa-onnx v1.13.8 OfflineRecognizerConfig / OnlineRecognizerConfig 변환
// 기능: model/README_카탈로그.md 2절 "파일 역할(role) ↔ 엔진 설정 필드" 표를 그대로 코드로 옮긴다.
//       - ZIPFORMER_TRANSDUCER: transducer(encoder/decoder/joiner) + tokens, modelType "transducer"(sherpa 예제 13)
//       - SENSEVOICE: senseVoice.model + tokens, language 는 비워 두고 전사 때 withSenseVoiceLanguage 로 ko/en 지정, ITN 켬(예제 15)
//       - QWEN3_ASR: qwen3Asr(convFrontend/encoder/decoder) + tokenizer = role "tokenizer" 파일들의 폴더, tokens ""(예제 61),
//                    maxNewTokens 512·maxTotalLen 1024(아래 상수 설명). 언어는 설정 필드가 없어 스트림 옵션 "language"(qwen3Language)로 준다.
//       - OMNILINGUAL_CTC: omnilingual.model + tokens(예제 44). 언어 필드 없음(CTC 모델은 언어 지정 없이 전사).
//       - STREAMING_ZIPFORMER: OnlineTransducerModelConfig + tokens, modelType "zipformer"(Online 예제 14), 끝점 검출 끔.
//       공통: provider "cpu", numThreads = threads, decodingMethod "greedy_search", 특징 16 kHz·80 차원(기본값).
//       파일 경로는 모델 폴더 기준 절대 경로. 파일 존재 확인은 엔진 prepare 가 한다(여기서는 스펙만 본다).
// change(add)-hyungchul-2026-10-10-20:57
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 패키지

import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SherpaConfig // sherpa 설정(종류)
import com.cowork.stt_ondevice.model.SherpaKind // sherpa 모델 종류
import com.k2fsa.sherpa.onnx.FeatureConfig // 특징 설정(복사한 sherpa API)
import com.k2fsa.sherpa.onnx.OfflineModelConfig // 오프라인 모델 설정
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig // Omnilingual CTC 설정
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig // Qwen3-ASR 설정
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig // 오프라인 인식기 설정
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig // SenseVoice 설정
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig // 오프라인 transducer 설정
import com.k2fsa.sherpa.onnx.OnlineModelConfig // 스트리밍 모델 설정
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig // 스트리밍 인식기 설정
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig // 스트리밍 transducer 설정
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: sherpa-onnx 설정 생성기
 * 기능: 위 파일 머리 설명 참조. 상태가 없고 네이티브 라이브러리를 건드리지 않는다(설정 클래스는 순수 data class).
 */
object SherpaConfigs {

    const val PROVIDER = "cpu" // 실행 장치(onnxruntime CPU EP)
    const val DECODING_METHOD = "greedy_search" // 탐색 방식
    const val SAMPLE_RATE = 16000 // 입력 표본율(Hz)
    const val FEATURE_DIM = 80 // fbank 차원(sherpa 기본값)

    /**
     * Qwen3-ASR 1회 생성 토큰 상한. sherpa 기본 128 은 30 s 한국어 chunk 출력을 자를 수 있어
     * Qwen3-ASR 공식 기본값 512 를 쓴다(PC qwen3_asr_stt.py 도움말에 "공식 기본값은 512" 로 기록).
     */
    const val QWEN3_MAX_NEW_TOKENS = 512

    /**
     * Qwen3-ASR KV 캐시 길이(프롬프트 + 오디오 토큰 + 생성 토큰 합의 상한). sherpa 기본 512 는 30 s 오디오 토큰(수백 개) 뒤
     * 생성 여유가 거의 없어 출력이 잘리거나 오디오 토큰이 잘린다. decoder.int8.onnx 의 cache 길이 축이 동적('max_total_len')이라
     * 설정값이 그대로 쓰인다. 1024 = 오디오 + 512 생성 여유(KV 캐시 fp32 28층×8헤드×128차원 기준 약 235 MB).
     */
    const val QWEN3_MAX_TOTAL_LEN = 1024

    const val TOKENIZER_ROLE = "tokenizer" // Qwen3 tokenizer 파일 역할(폴더로 넘김)

    /**
     * 목적: 오프라인 인식기 설정 만들기
     * 기능: spec.engineConfig 의 SherpaKind 에 따라 위 표대로 필드를 채운다. 언어 필드는 비워 둔다(엔진이 전사 때 지정).
     * 입력: spec(engineConfig = SherpaConfig, 오프라인 종류), dir(모델 폴더 models/<id>), threads(추론 스레드 수)
     * 출력: SherpaConfig 아님·스트리밍 종류 → EngineException(MODEL_LOAD, CONFIG_MISMATCH), 필요한 role 없음 → EngineException(MODEL_LOAD, MODEL_FILE_MISSING)
     * 리턴: OfflineRecognizerConfig
     */
    fun offline(spec: ModelSpec, dir: File, threads: Int): OfflineRecognizerConfig {
        val model = when (val kind = kindOf(spec)) { // 종류별 모델 설정
            SherpaKind.ZIPFORMER_TRANSDUCER -> OfflineModelConfig( // 오프라인 zipformer transducer
                transducer = OfflineTransducerModelConfig( // transducer 3 파일
                    encoder = path(spec, dir, "encoder"), // 인코더
                    decoder = path(spec, dir, "decoder"), // 디코더
                    joiner = path(spec, dir, "joiner"), // 조이너
                ),
                tokens = path(spec, dir, "tokens"), // 토큰 표
                modelType = "transducer", // sherpa 예제 13 과 같음
            )
            SherpaKind.SENSEVOICE -> OfflineModelConfig( // SenseVoice
                senseVoice = OfflineSenseVoiceModelConfig( // SenseVoice 모델
                    model = path(spec, dir, "model"), // 모델 파일
                    language = "", // 비움(엔진이 withSenseVoiceLanguage 로 지정)
                    useInverseTextNormalization = true, // 숫자·구두점 정규화(PC sensevoice.py use_itn 기본 True)
                ),
                tokens = path(spec, dir, "tokens"), // 토큰 표
            )
            SherpaKind.QWEN3_ASR -> OfflineModelConfig( // Qwen3-ASR
                qwen3Asr = OfflineQwen3AsrModelConfig( // Qwen3 모델
                    convFrontend = path(spec, dir, "convFrontend"), // conv 프런트엔드
                    encoder = path(spec, dir, "encoder"), // 오디오 인코더
                    decoder = path(spec, dir, "decoder"), // LLM 디코더
                    tokenizer = tokenizerDir(spec, dir), // tokenizer 폴더
                    maxTotalLen = QWEN3_MAX_TOTAL_LEN, // KV 캐시 길이
                    maxNewTokens = QWEN3_MAX_NEW_TOKENS, // 생성 상한
                ),
                tokens = "", // 쓰지 않음(sherpa 예제 61)
            )
            SherpaKind.OMNILINGUAL_CTC -> OfflineModelConfig( // Omnilingual CTC
                omnilingual = OfflineOmnilingualAsrCtcModelConfig( // CTC 모델
                    model = path(spec, dir, "model"), // 모델 파일
                ),
                tokens = path(spec, dir, "tokens"), // 토큰 표
            )
            SherpaKind.STREAMING_ZIPFORMER -> throw mismatch(spec, "스트리밍 종류($kind)는 offline 설정을 만들 수 없음") // 종류 불일치
        }
        model.numThreads = threads // 스레드 수
        model.provider = PROVIDER // CPU
        model.debug = false // sherpa 설정 로그 끔
        return OfflineRecognizerConfig( // 인식기 설정
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM), // 16 kHz, 80 차원
            modelConfig = model, // 모델 설정
            decodingMethod = DECODING_METHOD, // greedy_search
        )
    }

    /**
     * 목적: 스트리밍 인식기 설정 만들기
     * 기능: STREAMING_ZIPFORMER 만 받는다. 끝점 검출(enableEndpoint)은 끈다 — chunk 하나를 끝까지 한 결과로 받기 위해서다
     *       (엔진은 chunk 마다 새 스트림을 쓰고 reset 하지 않는다).
     * 입력: spec(engineConfig = SherpaConfig(STREAMING_ZIPFORMER)), dir(모델 폴더), threads(추론 스레드 수)
     * 출력: 종류 불일치 → EngineException(MODEL_LOAD, CONFIG_MISMATCH), 필요한 role 없음 → EngineException(MODEL_LOAD, MODEL_FILE_MISSING)
     * 리턴: OnlineRecognizerConfig
     */
    fun online(spec: ModelSpec, dir: File, threads: Int): OnlineRecognizerConfig {
        val kind = kindOf(spec) // 종류
        if (kind != SherpaKind.STREAMING_ZIPFORMER) throw mismatch(spec, "오프라인 종류($kind)는 online 설정을 만들 수 없음") // 종류 불일치
        val model = OnlineModelConfig( // 스트리밍 모델 설정
            transducer = OnlineTransducerModelConfig( // transducer 3 파일
                encoder = path(spec, dir, "encoder"), // 인코더
                decoder = path(spec, dir, "decoder"), // 디코더
                joiner = path(spec, dir, "joiner"), // 조이너
            ),
            tokens = path(spec, dir, "tokens"), // 토큰 표
            numThreads = threads, // 스레드 수
            debug = false, // 설정 로그 끔
            provider = PROVIDER, // CPU
            modelType = "zipformer", // sherpa Online 예제 14 와 같음
        )
        return OnlineRecognizerConfig( // 인식기 설정
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM), // 16 kHz, 80 차원
            modelConfig = model, // 모델 설정
            enableEndpoint = false, // 끝점 검출 끔
            decodingMethod = DECODING_METHOD, // greedy_search
        )
    }

    /**
     * 목적: SenseVoice 언어만 바꾼 설정 사본
     * 기능: 원본을 바꾸지 않고 modelConfig.senseVoice.language 만 senseVoiceLanguage(lang) 로 바꾼 깊은 사본을 만든다
     *       (OfflineRecognizer.setConfig 로 넘길 값 — sherpa SenseVoice 구현은 디코딩 때마다 이 필드를 읽는다).
     * 입력: config(offline() 로 만든 SenseVoice 설정), lang(앱 언어 코드 ko/en)
     * 출력: 미지원 언어 → EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE)
     * 리턴: 새 OfflineRecognizerConfig
     */
    fun withSenseVoiceLanguage(config: OfflineRecognizerConfig, lang: String): OfflineRecognizerConfig {
        val code = senseVoiceLanguage(lang) // sherpa 언어 값
        val sv = config.modelConfig.senseVoice.copy(language = code) // SenseVoice 설정 사본
        return config.copy(modelConfig = config.modelConfig.copy(senseVoice = sv)) // 상위 설정 사본
    }

    /**
     * 목적: 앱 언어 코드 → SenseVoice 언어 값
     * 기능: sherpa v1.13.8 offline-sense-voice-model-config.cc 의 허용값(auto/zh/en/ja/ko/yue) 중 앱이 쓰는 ko/en 만 그대로 통과시킨다.
     * 입력: lang(앱 언어 코드)
     * 출력: 그 밖의 값 → EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE)
     * 리턴: "ko" 또는 "en"
     */
    fun senseVoiceLanguage(lang: String): String = when (lang) { // 언어별
        "ko", "en" -> lang // 같은 코드 체계
        else -> throw unsupported("SenseVoice", lang) // 미지원
    }

    /**
     * 목적: 앱 언어 코드 → Qwen3-ASR 언어 이름
     * 기능: sherpa v1.13.8 Qwen3 구현은 스트림 옵션 "language" 값을 프롬프트에 "language <값>" 으로 넣는다.
     *       Qwen3-ASR 은 ISO 코드가 아니라 언어 이름을 쓴다(sherpa cxx 예제 주석 SetOption("language", "Korean"),
     *       python 예제 도움말 "e.g. Korean, Chinese, English", PC qwen3_asr_stt.py 의 LOCAL_LANGUAGE_MAP 과 같음).
     * 입력: lang(앱 언어 코드)
     * 출력: 그 밖의 값 → EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE)
     * 리턴: "Korean" 또는 "English"
     */
    fun qwen3Language(lang: String): String = when (lang) { // 언어별
        "ko" -> "Korean" // 한국어
        "en" -> "English" // 영어
        else -> throw unsupported("Qwen3-ASR", lang) // 미지원
    }

    /**
     * 목적: 스펙의 sherpa 모델 종류 꺼내기
     * 기능: engineConfig 가 SherpaConfig 인지 확인한다.
     * 입력: spec(모델 메타)
     * 출력: SherpaConfig 아님 → EngineException(MODEL_LOAD, CONFIG_MISMATCH)
     * 리턴: SherpaKind
     */
    fun kindOf(spec: ModelSpec): SherpaKind =
        (spec.engineConfig as? SherpaConfig)?.kind // sherpa 설정이면 종류
            ?: throw mismatch(spec, "sherpa 설정이 아님(engineConfig=${spec.engineConfig})") // 아니면 오류

    /**
     * 목적: role 하나의 파일 절대 경로
     * 기능: spec.files 에서 role 이 같은 첫 항목을 찾아 dir 기준 절대 경로를 만든다.
     * 입력: spec(모델 메타), dir(모델 폴더), role(역할 이름)
     * 출력: 없으면 EngineException(MODEL_LOAD, MODEL_FILE_MISSING)
     * 리턴: 절대 경로 문자열
     */
    private fun path(spec: ModelSpec, dir: File, role: String): String {
        val f = spec.files.firstOrNull { it.role == role } // 역할 항목
            ?: throw EngineException("MODEL_LOAD", "MODEL_FILE_MISSING", "스펙에 role \"$role\" 파일이 없음: ${spec.id}") // 카탈로그 오류
        return File(dir, f.path).absolutePath // 절대 경로
    }

    /**
     * 목적: Qwen3 tokenizer 폴더 경로
     * 기능: role "tokenizer" 파일들의 상위 폴더가 모두 같아야 하며(sherpa 는 폴더 하나를 받음) 그 폴더의 절대 경로를 돌려준다.
     * 입력: spec(모델 메타), dir(모델 폴더)
     * 출력: tokenizer 파일 없음 → MODEL_FILE_MISSING, 폴더가 둘 이상 → CONFIG_MISMATCH (둘 다 stage MODEL_LOAD)
     * 리턴: 절대 경로 문자열
     */
    private fun tokenizerDir(spec: ModelSpec, dir: File): String {
        val parents = spec.files.filter { it.role == TOKENIZER_ROLE } // tokenizer 항목들
            .map { File(dir, it.path).absoluteFile.parentFile!!.absolutePath } // 각 상위 폴더
            .distinct() // 중복 제거
        if (parents.isEmpty()) throw EngineException("MODEL_LOAD", "MODEL_FILE_MISSING", "스펙에 role \"$TOKENIZER_ROLE\" 파일이 없음: ${spec.id}") // 없음
        if (parents.size > 1) throw mismatch(spec, "tokenizer 파일이 여러 폴더에 있음: $parents") // 폴더 여러 개
        return parents.single() // 폴더 하나
    }

    /**
     * 목적: 설정 불일치 예외 만들기
     * 기능: stage MODEL_LOAD, code CONFIG_MISMATCH 예외를 만든다.
     * 입력: spec(모델 메타), why(사유)
     * 출력: 없음
     * 리턴: EngineException
     */
    private fun mismatch(spec: ModelSpec, why: String) =
        EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "${spec.id}: $why") // 예외 생성

    /**
     * 목적: 미지원 언어 예외 만들기
     * 기능: stage TRANSCRIBE, code UNSUPPORTED_LANGUAGE 예외를 만든다.
     * 입력: model(모델 이름), lang(언어 코드)
     * 출력: 없음
     * 리턴: EngineException
     */
    private fun unsupported(model: String, lang: String) =
        EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "$model 언어 코드 변환 불가: '$lang' (ko/en 만 지원)") // 예외 생성
}
