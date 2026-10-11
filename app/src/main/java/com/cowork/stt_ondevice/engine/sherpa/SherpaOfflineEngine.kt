// 파일명: SherpaOfflineEngine.kt
// 목적: sherpa-onnx OfflineRecognizer(v1.13.8)로 chunk 를 전사하는 AsrEngine 구현
//       (zipformer transducer KO/EN, SenseVoice, Qwen3-ASR, Omnilingual CTC — spec §9.2)
// 기능: prepare — 스펙의 모든 파일 존재 확인 → SherpaConfigs.offline 로 설정 → OfflineRecognizer 생성(모델 로드), 시간·버전 측정.
//       transcribe — 언어 검사(spec.languages) → 준비 확인 → chunk 마다 새 스트림 생성 → acceptWaveform(samples, 16000) → decode → getResult → 스트림 해제.
//                    언어 지정: SenseVoice 는 설정 필드 language(ko/en, 바뀔 때만 setConfig), Qwen3-ASR 은 스트림 옵션 "language"(Korean/English),
//                    zipformer(단일 언어 모델)·Omnilingual CTC 는 언어 지정 필드가 없다.
//                    결과: SenseVoice 는 rawText = lang/emotion/event 태그 + 본문(SenseVoiceText.rawOf), text = 태그 제거·공백 정리본.
//                          그 밖 모델은 rawText = sherpa 결과 text 원문, text = 공백 정리본. detectedLang = null(언어 고정 또는 감지 정보 없음).
//       cancel — 중단 플래그를 켠다. 단계 사이(스트림 입력 전, decode 전, decode 후)에서 확인해 EngineException(TRANSCRIBE, ABORTED) 로 끝낸다.
//                sherpa 오프라인 decode 한 번은 네이티브 안에서 중단할 수 없으므로 그 decode 가 끝날 때까지 기다린다(중단 지연 상한 = chunk 1개 decode 시간).
//       close — 새 작업 거부 → 중단 요청 → 진행 중 전사가 끝나기를 기다린 뒤 인식기를 1회만 해제(반복 호출 안전).
//       잠금: 생성·전사·setConfig·해제를 직렬화해 해제된 인식기를 쓰지 않게 한다(WhisperCppEngine 과 같은 방식).
//       change(add)-hyungchul-2026-10-10-21:32 리뷰 수정: 생명주기(준비·잠금·중단 플래그·해제)와 예외 매핑은 SherpaEngineCore/SherpaEngineSupport 로 옮기고
//         이 파일에는 오프라인 전사 단계와 SenseVoice 언어 처리만 남김(동작 동일, 중단 요청 소실 경합만 수정).
// change(add)-hyungchul-2026-10-10-20:57
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 패키지

import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // 전사 결과
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import com.cowork.stt_ondevice.model.SherpaKind // sherpa 모델 종류
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.k2fsa.sherpa.onnx.OfflineRecognizer // sherpa 오프라인 인식기(복사한 API)
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig // 오프라인 인식기 설정
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: sherpa-onnx 오프라인 엔진
 * 기능: 위 파일 머리 설명 참조.
 * 입력: spec(runtime = SHERPA_OFFLINE, engineConfig = SherpaConfig(스트리밍 아닌 종류)), modelDir(설치 폴더 models/<id>)
 * 출력: 스펙이 맞지 않으면 EngineException(MODEL_LOAD, CONFIG_MISMATCH)
 */
class SherpaOfflineEngine(
    override val spec: ModelSpec, // 모델 메타
    val modelDir: File, // 모델 폴더
) : AsrEngine {

    private val kind: SherpaKind = SherpaConfigs.kindOf(spec) // sherpa 모델 종류(SherpaConfig 아니면 CONFIG_MISMATCH)
    private val core = SherpaEngineCore<OfflineRecognizer>(spec) { it.release() } // change(add)-hyungchul-2026-10-10-21:32 인식기 생명주기(잠금·중단·해제)
    private var baseConfig: OfflineRecognizerConfig? = null // prepare 에서 만든 설정(core 잠금 안에서만 접근)
    private var senseVoiceLang: String? = null // 지금 인식기에 들어 있는 SenseVoice 언어(core 잠금 안에서만 접근, null = 미지정)

    init {
        if (spec.runtime != Runtime.SHERPA_OFFLINE || kind == SherpaKind.STREAMING_ZIPFORMER) { // 오프라인 스펙이 아님
            throw EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "sherpa 오프라인 스펙이 아님(runtime=${spec.runtime}, kind=$kind): ${spec.id}") // 거부
        }
    }

    /**
     * 목적: 모델 로드
     * 기능: SherpaEngineCore.load 로 스레드 수·닫힘·중복 준비·파일 존재 확인 → SherpaConfigs.offline 설정 → OfflineRecognizer 생성(시간 측정).
     *       로드가 끝나면 잠금 안에서 설정을 보관하고 SenseVoice 언어를 미지정으로 둔다.
     * 입력: threads(추론 스레드 수, 1 이상)
     * 출력: threads < 1 → IllegalArgumentException,
     *       그 밖 → EngineException(MODEL_LOAD, CLOSED/ALREADY_PREPARED/MODEL_FILE_MISSING/CONFIG_MISMATCH/OOM/NATIVE_LIB_MISSING/LOAD_FAILED)
     * 리턴: PrepareInfo(loadSec, sherpa 버전, "CPU", spec.precision, threads)
     */
    override suspend fun prepare(threads: Int): PrepareInfo {
        val loadSec = core.load( // change(add)-hyungchul-2026-10-10-21:32 공통 준비 절차
            threads, modelDir, // 스레드 수·모델 폴더
            configure = { SherpaConfigs.offline(spec, modelDir, threads) }, // 설정(역할 → 필드)
            create = { OfflineRecognizer(config = it) }, // newFromFile(설정 검증 실패 시 ptr 0 → IllegalArgumentException)
        ) { _, config -> // 잠금 안 후처리
            baseConfig = config // 설정 보관(SenseVoice 언어 변경용)
            senseVoiceLang = null // 언어 미지정 상태
        }
        return SherpaEngineSupport.prepareInfo(spec, loadSec, threads) // 준비 결과
    }

    /**
     * 목적: chunk 1개 전사
     * 기능: 언어 검사 → SherpaEngineCore.transcribe(준비 확인·잠금·예외 매핑) 안에서 SenseVoice 언어 적용 → 새 스트림 →
     *       (Qwen3 언어 옵션) → acceptWaveform → decode → getResult, 시간 측정(언어 설정 변경은 제외), 스트림 해제.
     * 입력: chunk(16 kHz mono float 샘플), lang(언어 코드, spec.languages 안이어야 함)
     * 출력: EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE/NOT_PREPARED/ABORTED/OOM/NATIVE_ERROR)
     * 리턴: ChunkText(text, rawText, detectedLang = null, inferSec)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        if (!supports(lang)) throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "${spec.id} 는 언어 '$lang' 미지원(지원: ${spec.languages})") // 언어 검사(네이티브 전)
        return core.transcribe(chunk) { r -> // change(add)-hyungchul-2026-10-10-21:32 공통 잠금·예외 매핑 안에서
            applySenseVoiceLanguage(r, lang) // SenseVoice 면 언어 설정(바뀔 때만)
            val t0 = System.nanoTime() // 시작 시각
            val stream = r.createStream() // chunk 전용 새 스트림
            try { // 스트림은 반드시 해제
                if (kind == SherpaKind.QWEN3_ASR) stream.setOption("language", SherpaConfigs.qwen3Language(lang)) // Qwen3 언어 고정
                core.checkCancel(chunk) // 입력 전 중단 확인
                stream.acceptWaveform(chunk.samples, SherpaConfigs.SAMPLE_RATE) // 오디오 입력(16 kHz)
                core.checkCancel(chunk) // decode 전 중단 확인
                r.decode(stream) // 전사(네이티브, 중간 중단 불가)
                core.checkCancel(chunk) // decode 후 중단 확인
                val res = r.getResult(stream) // 결과
                val inferSec = (System.nanoTime() - t0) / 1e9 // 추론 시간(초)
                if (kind == SherpaKind.SENSEVOICE) { // SenseVoice: 태그 보존 원문 + 정리본
                    val raw = SenseVoiceText.rawOf(res.lang, res.emotion, res.event, res.text) // 원문 직렬화
                    ChunkText(text = SenseVoiceText.clean(raw), rawText = raw, detectedLang = null, inferSec = inferSec) // 언어 고정이라 감지 언어 없음
                } else { // 그 밖 모델: 원문 그대로 + 공백 정리본
                    ChunkText(text = SherpaEngineSupport.normalizeSpaces(res.text), rawText = res.text, detectedLang = null, inferSec = inferSec) // 감지 정보 없음
                }
            } finally { // 성공·실패 모두
                stream.release() // 네이티브 스트림 해제
            }
        }
    }

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: 중단 플래그를 켠다(다른 스레드에서 불러도 안전, 준비 전·해제 후에도 무해). 전사는 다음 확인 지점에서 ABORTED 로 끝난다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() {
        core.cancel() // change(add)-hyungchul-2026-10-10-21:32 공통 플래그 켜기
    }

    /**
     * 목적: 모델 해제(반복 호출 안전)
     * 기능: SherpaEngineCore.close — 새 작업 거부 → 중단 요청 → 진행 중 전사·로드 종료 대기 → 인식기 1회만 release. 잠금 안에서 설정 참조도 정리한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        core.close { baseConfig = null } // change(add)-hyungchul-2026-10-10-21:32 공통 해제 + 설정 참조 정리
    }

    /**
     * 목적: SenseVoice 언어 설정
     * 기능: SenseVoice 이고 현재 인식기 언어가 lang 과 다르면 언어만 바꾼 설정을 setConfig 로 넣는다(core 잠금 안에서만 호출).
     *       sherpa SenseVoice 구현은 decode 때마다 설정의 language 를 읽는다. 다른 종류는 아무것도 하지 않는다.
     * 입력: r(인식기), lang(앱 언어 코드)
     * 출력: 미지원 언어 → EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE)
     * 리턴: 없음
     */
    private fun applySenseVoiceLanguage(r: OfflineRecognizer, lang: String) {
        if (kind != SherpaKind.SENSEVOICE || senseVoiceLang == lang) return // 해당 없음 또는 이미 같은 언어
        val base = baseConfig ?: throw EngineException("TRANSCRIBE", "NOT_PREPARED", "설정 없음: ${spec.id}") // 준비 설정
        r.setConfig(SherpaConfigs.withSenseVoiceLanguage(base, lang)) // 언어 바꾼 설정 적용
        senseVoiceLang = lang // 현재 언어 기록
    }
}
