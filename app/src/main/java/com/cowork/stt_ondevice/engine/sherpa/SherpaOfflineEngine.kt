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
//       잠금: runLock 이 생성·전사·setConfig·해제를 직렬화해 해제된 인식기를 쓰지 않게 한다(WhisperCppEngine 과 같은 방식).
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
import com.k2fsa.sherpa.onnx.VersionInfo // sherpa 네이티브 버전
import kotlinx.coroutines.Dispatchers // 코루틴 디스패처
import kotlinx.coroutines.withContext // 디스패처 전환
import java.io.File // 파일
import java.util.concurrent.atomic.AtomicBoolean // 스레드 간 중단 플래그

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
    private val runLock = Any() // 네이티브 생성·전사·해제 직렬화 잠금
    @Volatile private var recognizer: OfflineRecognizer? = null // 인식기(null = 준비 안 됨/해제됨)
    private var baseConfig: OfflineRecognizerConfig? = null // prepare 에서 만든 설정(runLock 안에서만 접근)
    private var senseVoiceLang: String? = null // 지금 인식기에 들어 있는 SenseVoice 언어(runLock 안에서만 접근, null = 미지정)
    @Volatile private var closed = false // close 호출 여부
    private val cancelRequested = AtomicBoolean(false) // 중단 요청 플래그

    init {
        if (spec.runtime != Runtime.SHERPA_OFFLINE || kind == SherpaKind.STREAMING_ZIPFORMER) { // 오프라인 스펙이 아님
            throw EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "sherpa 오프라인 스펙이 아님(runtime=${spec.runtime}, kind=$kind): ${spec.id}") // 거부
        }
    }

    /**
     * 목적: 모델 로드
     * 기능: 스레드 수·닫힘·중복 준비·파일 존재를 확인하고 설정을 만든 뒤 Dispatchers.IO 에서 OfflineRecognizer 를 생성, 시간을 잰다.
     * 입력: threads(추론 스레드 수, 1 이상)
     * 출력: threads < 1 → IllegalArgumentException,
     *       그 밖 → EngineException(MODEL_LOAD, CLOSED/ALREADY_PREPARED/MODEL_FILE_MISSING/CONFIG_MISMATCH/OOM/NATIVE_LIB_MISSING/LOAD_FAILED)
     * 리턴: PrepareInfo(loadSec, sherpa 버전, "CPU", spec.precision, threads)
     */
    override suspend fun prepare(threads: Int): PrepareInfo {
        require(threads >= 1) { "threads 는 1 이상이어야 함: $threads" } // 인자 검사
        if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 재사용 금지
        if (recognizer != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 중복 로드 금지
        val missing = spec.files.filter { !File(modelDir, it.path).isFile }.map { it.path } // 없는 파일들
        if (missing.isNotEmpty()) throw EngineException("MODEL_LOAD", "MODEL_FILE_MISSING", "모델 파일 없음(${spec.id}, ${modelDir.absolutePath}): $missing") // 파일 확인
        val config = SherpaConfigs.offline(spec, modelDir, threads) // 설정(역할 → 필드)
        return withContext(Dispatchers.IO) { // 파일 읽기 위주 → IO 디스패처
            synchronized(runLock) { // 네이티브 직렬화
                if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 잠금 대기 중 닫힘
                if (recognizer != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 동시 prepare
                val t0 = System.nanoTime() // 시작 시각
                val r = try { // 네이티브 로드
                    OfflineRecognizer(config = config) // newFromFile(설정 검증 실패 시 ptr 0 → IllegalArgumentException)
                } catch (e: OutOfMemoryError) { // 메모리 부족
                    throw EngineException("MODEL_LOAD", "OOM", "모델 로드 중 메모리 부족: ${spec.id}", e) // OOM 코드
                } catch (e: LinkageError) { // libsherpa-onnx-jni.so 없음/심볼 없음/초기화 실패
                    throw EngineException("MODEL_LOAD", "NATIVE_LIB_MISSING", "sherpa 네이티브 라이브러리 로드 실패: ${e.message}", e) // 누락 .so 코드
                } catch (e: RuntimeException) { // 설정 검증 실패 등
                    throw EngineException("MODEL_LOAD", "LOAD_FAILED", "sherpa 모델 로드 실패(${spec.id}): ${e.message}", e) // 일반 실패
                }
                val loadSec = (System.nanoTime() - t0) / 1e9 // 로드 시간(초)
                if (closed) { // 로드 중 close 가 불렸으면(그 close 는 runLock 대기 중)
                    r.release() // 즉시 해제
                    throw EngineException("MODEL_LOAD", "CLOSED", "로드 중 엔진이 닫힘: ${spec.id}") // 닫힘 보고
                }
                baseConfig = config // 설정 보관(SenseVoice 언어 변경용)
                senseVoiceLang = null // 언어 미지정 상태
                recognizer = r // 인식기 공개
                PrepareInfo( // 준비 결과
                    loadSec = loadSec, // 로드 시간
                    runtimeVersion = VersionInfo.version, // 네이티브가 보고한 sherpa 버전
                    backend = BACKEND, // CPU 전용(provider "cpu")
                    precision = spec.precision, // 카탈로그 정밀도(int8 등)
                    threads = threads, // 스레드 수
                )
            }
        }
    }

    /**
     * 목적: chunk 1개 전사
     * 기능: 언어 검사 → 준비 확인 → Dispatchers.Default 에서 runLock 을 잡고 스트림 생성·입력·decode·결과, 시간 측정(언어 설정 변경은 제외).
     * 입력: chunk(16 kHz mono float 샘플), lang(언어 코드, spec.languages 안이어야 함)
     * 출력: EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE/NOT_PREPARED/ABORTED/OOM/NATIVE_ERROR)
     * 리턴: ChunkText(text, rawText, detectedLang = null, inferSec)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        if (!supports(lang)) throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "${spec.id} 는 언어 '$lang' 미지원(지원: ${spec.languages})") // 언어 검사(네이티브 전)
        if (closed || recognizer == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "prepare 전이거나 닫힌 엔진: ${spec.id}") // 준비 확인
        return withContext(Dispatchers.Default) { // CPU 계산 → Default 디스패처
            synchronized(runLock) { // 같은 인식기 동시 사용·전사 중 해제 방지
                val r = recognizer // 잠금 안에서 다시 읽음(close 가 먼저 해제했을 수 있음)
                if (closed || r == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "닫힌 엔진: ${spec.id}") // 해제됨
                cancelRequested.set(false) // 새 전사 시작 — 이전 중단 요청 지움(whisper 엔진과 같은 규칙)
                try { // 네이티브 예외 → EngineException
                    applySenseVoiceLanguage(r, lang) // SenseVoice 면 언어 설정(바뀔 때만)
                    val t0 = System.nanoTime() // 시작 시각
                    val stream = r.createStream() // chunk 전용 새 스트림
                    try { // 스트림은 반드시 해제
                        if (kind == SherpaKind.QWEN3_ASR) stream.setOption("language", SherpaConfigs.qwen3Language(lang)) // Qwen3 언어 고정
                        checkCancel(chunk) // 입력 전 중단 확인
                        stream.acceptWaveform(chunk.samples, SherpaConfigs.SAMPLE_RATE) // 오디오 입력(16 kHz)
                        checkCancel(chunk) // decode 전 중단 확인
                        r.decode(stream) // 전사(네이티브, 중간 중단 불가)
                        checkCancel(chunk) // decode 후 중단 확인
                        val res = r.getResult(stream) // 결과
                        val inferSec = (System.nanoTime() - t0) / 1e9 // 추론 시간(초)
                        if (kind == SherpaKind.SENSEVOICE) { // SenseVoice: 태그 보존 원문 + 정리본
                            val raw = SenseVoiceText.rawOf(res.lang, res.emotion, res.event, res.text) // 원문 직렬화
                            ChunkText(text = SenseVoiceText.clean(raw), rawText = raw, detectedLang = null, inferSec = inferSec) // 언어 고정이라 감지 언어 없음
                        } else { // 그 밖 모델: 원문 그대로 + 공백 정리본
                            ChunkText(text = normalizeSpaces(res.text), rawText = res.text, detectedLang = null, inferSec = inferSec) // 감지 정보 없음
                        }
                    } finally { // 성공·실패 모두
                        stream.release() // 네이티브 스트림 해제
                    }
                } catch (e: EngineException) { // 중단·언어 오류는 그대로
                    throw e // 재던짐
                } catch (e: OutOfMemoryError) { // 메모리 부족
                    throw EngineException("TRANSCRIBE", "OOM", "전사 중 메모리 부족(chunk ${chunk.index})", e) // OOM 코드
                } catch (e: RuntimeException) { // 스트림 생성 실패 등
                    throw EngineException("TRANSCRIBE", "NATIVE_ERROR", "sherpa 전사 실패(chunk ${chunk.index}): ${e.message}", e) // 일반 실패
                }
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
        cancelRequested.set(true) // 플래그 켜기
    }

    /**
     * 목적: 모델 해제(반복 호출 안전)
     * 기능: closed 표시 → 중단 요청 → runLock 으로 진행 중 전사·로드가 끝나기를 기다림 → 인식기를 null 로 바꾸고 1회만 release.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        closed = true // 새 작업 거부
        cancel() // 진행 중 전사가 있으면 다음 확인 지점에서 끝나게 함
        synchronized(runLock) { // 전사·로드가 끝날 때까지 대기
            val r = recognizer // 현재 인식기
            recognizer = null // 이후 전사는 거부
            baseConfig = null // 설정 참조 정리
            r?.release() // 1회만 해제(두 번째 close 는 r == null)
        }
    }

    /**
     * 목적: SenseVoice 언어 설정
     * 기능: SenseVoice 이고 현재 인식기 언어가 lang 과 다르면 언어만 바꾼 설정을 setConfig 로 넣는다(runLock 안에서만 호출).
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

    /**
     * 목적: 중단 요청 확인
     * 기능: 플래그가 켜져 있으면 EngineException(TRANSCRIBE, ABORTED) 를 던진다.
     * 입력: chunk(오류 메시지용)
     * 출력: 중단 요청 시 EngineException
     * 리턴: 없음
     */
    private fun checkCancel(chunk: AudioChunk) {
        if (cancelRequested.get()) throw EngineException("TRANSCRIBE", "ABORTED", "전사 중단됨(chunk ${chunk.index}, ${spec.id})") // 중단
    }

    companion object {
        const val BACKEND = "CPU" // 실행 장치(provider "cpu")
        private val SPACES = Regex("""\s+""") // 연속 공백

        /**
         * 목적: 공백 정리
         * 기능: 연속 공백을 하나로 줄이고 양끝 공백을 없앤다(sherpa 토큰 결합 결과의 앞 공백 등).
         * 입력: s(원문)
         * 출력: 없음
         * 리턴: 정리된 문자열
         */
        fun normalizeSpaces(s: String): String = s.replace(SPACES, " ").trim() // 공백 정리
    }
}
