// 파일명: WhisperCppEngine.kt
// 목적: whisper.cpp(JNI, WhisperNative)로 chunk 를 전사하는 AsrEngine 구현 (PC 대응: faster-whisper)
// 기능: prepare — 모델 파일 확인 후 WhisperNative.init 으로 로드하고 로드 시간·버전·정밀도(파일 이름)·스레드 수를 돌려준다.
//       transcribe — 언어 검사(spec.languages) → 준비 확인 → Dispatchers.Default 에서 WhisperNative.transcribe(BEAM_SEARCH, beam=WhisperConfig.beamSize,
//                    언어 고정, task=transcribe) → ChunkText(text, rawText=text, detectedLang=null(언어 고정), inferSec).
//       cancel — 원자 중단 플래그(abort_callback) 켜기. close — 진행 중 전사를 중단시키고 끝나기를 기다린 뒤 1회만 해제(반복 호출 안전).
//       네이티브 예외는 EngineException(stage, code) 로 바꾼다(중단 → TRANSCRIBE/ABORTED).
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine.whisper // whisper 엔진 패키지

import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // 전사 결과
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.WhisperConfig // whisper 설정(빔 크기)
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import kotlinx.coroutines.Dispatchers // 코루틴 디스패처
import kotlinx.coroutines.withContext // 디스패처 전환
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: whisper.cpp 엔진
 * 기능: 위 파일 머리 설명 참조. 잠금 2개를 쓴다 —
 *       runLock: 네이티브 init/transcribe/free 를 직렬화(같은 ctx 동시 사용 금지, 전사 중 free 금지),
 *       handleLock: ctx 값 읽기+abort 와 ctx 를 0 으로 바꾸는 일을 묶어 해제된 ctx 에 abort 하지 않게 한다.
 * 입력: spec(engineConfig 는 WhisperConfig 여야 함), modelFile(ggml-*.bin, EngineFactory 가 role "model" 로 찾은 경로)
 * 출력: engineConfig 가 WhisperConfig 가 아니면 IllegalArgumentException
 */
class WhisperCppEngine(
    override val spec: ModelSpec, // 모델 메타
    val modelFile: File, // 모델 파일
) : AsrEngine {

    private val beamSize: Int = (spec.engineConfig as? WhisperConfig)?.beamSize // 빔 크기(PC faster-whisper 기본 5)
        ?: throw IllegalArgumentException("whisper.cpp 스펙이 아님(engineConfig=${spec.engineConfig}): ${spec.id}") // 설정 불일치
    private val runLock = Any() // 네이티브 실행 직렬화 잠금
    private val handleLock = Any() // ctx 값·abort 보호 잠금
    @Volatile private var ctx: Long = 0L // 네이티브 구조체 주소(0 = 준비 안 됨/해제됨)
    @Volatile private var closed = false // close 호출 여부
    @Volatile private var threads: Int = 0 // prepare 에서 정한 스레드 수

    /**
     * 목적: 모델 로드
     * 기능: 스레드 수·닫힘·중복 준비·파일 존재를 확인한 뒤 Dispatchers.IO 에서 WhisperNative.init 을 부르고 시간을 잰다.
     * 입력: threads(추론 스레드 수, 1 이상)
     * 출력: threads < 1 → IllegalArgumentException, 그 밖의 실패 → EngineException(MODEL_LOAD, CLOSED/ALREADY_PREPARED/MODEL_FILE_MISSING/OOM/NATIVE_LIB_MISSING/LOAD_FAILED)
     * 리턴: PrepareInfo(loadSec, whisper_version(), "CPU", 정밀도, threads)
     */
    override suspend fun prepare(threads: Int): PrepareInfo {
        require(threads >= 1) { "threads 는 1 이상이어야 함: $threads" } // 인자 검사
        if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 재사용 금지
        if (ctx != 0L) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 중복 로드 금지
        if (!modelFile.isFile) throw EngineException("MODEL_LOAD", "MODEL_FILE_MISSING", "모델 파일 없음: ${modelFile.absolutePath}") // 파일 확인
        return withContext(Dispatchers.IO) { // 파일 읽기 위주 → IO 디스패처
            synchronized(runLock) { // 네이티브 직렬화
                val t0 = System.nanoTime() // 시작 시각
                val c = try { // 네이티브 로드
                    WhisperNative.init(modelFile.absolutePath) // whisper_init_from_file_with_params
                } catch (e: OutOfMemoryError) { // 메모리 부족
                    throw EngineException("MODEL_LOAD", "OOM", "모델 로드 중 메모리 부족: ${spec.id}", e) // OOM 코드
                } catch (e: UnsatisfiedLinkError) { // libstt_native.so 없음/심볼 없음
                    throw EngineException("MODEL_LOAD", "NATIVE_LIB_MISSING", "네이티브 라이브러리 로드 실패: ${e.message}", e) // 누락 .so 코드
                } catch (e: RuntimeException) { // IllegalStateException 등 로드 실패
                    throw EngineException("MODEL_LOAD", "LOAD_FAILED", "whisper 모델 로드 실패(${spec.id}): ${e.message}", e) // 일반 실패
                }
                val loadSec = (System.nanoTime() - t0) / 1e9 // 로드 시간(초)
                synchronized(handleLock) { ctx = c } // 주소 공개
                this@WhisperCppEngine.threads = threads // 스레드 수 저장
                if (closed) { // 로드 중 close 가 불렸으면(그 close 는 아직 ctx 를 못 봤음)
                    synchronized(handleLock) { ctx = 0L } // 주소 회수
                    WhisperNative.free(c) // 즉시 해제
                    throw EngineException("MODEL_LOAD", "CLOSED", "로드 중 엔진이 닫힘: ${spec.id}") // 닫힘 보고
                }
                PrepareInfo( // 준비 결과
                    loadSec = loadSec, // 로드 시간
                    runtimeVersion = WhisperNative.version(), // 네이티브가 보고한 버전
                    backend = BACKEND, // CPU 전용(use_gpu=false)
                    precision = precisionFromFileName(modelFile.name) ?: spec.precision, // 파일 이름 규칙 밖이면 카탈로그 값
                    threads = threads, // 스레드 수
                )
            }
        }
    }

    /**
     * 목적: chunk 1개 전사
     * 기능: 언어 검사 → 준비 확인 → Dispatchers.Default 에서 runLock 을 잡고 네이티브 전사, 시간 측정.
     * 입력: chunk(16 kHz mono float 샘플), lang(언어 코드, spec.languages 안이어야 함)
     * 출력: EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE/NOT_PREPARED/ABORTED/OOM/NATIVE_ERROR)
     * 리턴: ChunkText(text, rawText=text, detectedLang=null, inferSec)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        if (!supports(lang)) throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "${spec.id} 는 언어 '$lang' 미지원(지원: ${spec.languages})") // 언어 검사(네이티브 전)
        if (closed || ctx == 0L) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "prepare 전이거나 닫힌 엔진: ${spec.id}") // 준비 확인
        return withContext(Dispatchers.Default) { // CPU 계산 → Default 디스패처
            synchronized(runLock) { // 같은 ctx 동시 사용·전사 중 free 방지
                val c = ctx // 잠금 안에서 다시 읽음(close 가 먼저 해제했을 수 있음)
                if (closed || c == 0L) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "닫힌 엔진: ${spec.id}") // 해제됨
                val t0 = System.nanoTime() // 시작 시각
                val text = try { // 네이티브 전사
                    WhisperNative.transcribe(c, chunk.samples, lang, threads, beamSize) // whisper_full
                } catch (e: WhisperAbortedException) { // abort_callback 으로 중단
                    throw EngineException("TRANSCRIBE", "ABORTED", "전사 중단됨(chunk ${chunk.index})", e) // 중단 코드
                } catch (e: OutOfMemoryError) { // 메모리 부족
                    throw EngineException("TRANSCRIBE", "OOM", "전사 중 메모리 부족(chunk ${chunk.index})", e) // OOM 코드
                } catch (e: RuntimeException) { // whisper_full 실패 등
                    throw EngineException("TRANSCRIBE", "NATIVE_ERROR", "whisper 전사 실패(chunk ${chunk.index}): ${e.message}", e) // 일반 실패
                }
                val inferSec = (System.nanoTime() - t0) / 1e9 // 추론 시간(초)
                ChunkText(text = text, rawText = text, detectedLang = null, inferSec = inferSec) // 언어 고정이라 감지 언어 없음
            }
        }
    }

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: handleLock 안에서 ctx 가 살아 있으면 WhisperNative.abort 로 원자 플래그를 켠다(준비 전·해제 후에는 아무것도 안 함).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() {
        synchronized(handleLock) { // 해제와 경합 방지
            val c = ctx // 현재 주소
            if (c != 0L) WhisperNative.abort(c) // 살아 있으면 중단 플래그
        }
    }

    /**
     * 목적: 모델 해제(반복 호출 안전)
     * 기능: closed 표시 → 진행 중 전사 중단 요청 → runLock 으로 전사가 끝나기를 기다림 → ctx 를 0 으로 바꾸고 1회만 free.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        closed = true // 새 작업 거부
        cancel() // 진행 중 전사가 있으면 빨리 끝나게 함
        synchronized(runLock) { // 전사·로드가 끝날 때까지 대기
            val c = synchronized(handleLock) { // 주소 회수
                val old = ctx // 현재 주소
                ctx = 0L // 이후 cancel/transcribe 는 무시·거부
                old // 해제 대상
            }
            if (c != 0L) WhisperNative.free(c) // 1회만 해제(두 번째 close 는 c == 0)
        }
    }

    companion object {
        const val BACKEND = "CPU" // 실행 장치(use_gpu=false, ggml CPU 백엔드)
        private val QUANT_SUFFIX = Regex("""^ggml-.+-(q\d_\d|q\d_k|f16|f32)\.bin$""") // 정밀도 접미사가 있는 이름
        private val PLAIN_NAME = Regex("""^ggml-[^.]+\.bin$""") // 접미사 없는 이름(점 없는 크기 이름)

        /**
         * 목적: 모델 파일 이름에서 가중치 정밀도 판정
         * 기능: whisper.cpp 배포 이름 규칙 — ggml-<크기>-<q5_0|q8_0|...>.bin 은 그 접미사, 접미사 없는 ggml-<크기>.bin 은 f16
         *       (models/convert-pt-to-ggml.py 기본 출력), 그 밖(.en 모델 등)은 null.
         * 입력: name(파일 이름)
         * 출력: 없음
         * 리턴: 정밀도 문자열 또는 null
         */
        fun precisionFromFileName(name: String): String? {
            QUANT_SUFFIX.find(name)?.let { return it.groupValues[1] } // 접미사 있음
            return if (PLAIN_NAME.matches(name)) "f16" else null // 접미사 없음 = f16, 규칙 밖 = null
        }
    }
}
