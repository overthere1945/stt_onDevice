// 파일명: VoskEngine.kt
// 목적: Vosk(Kaldi, vosk-android 0.3.75)로 chunk 를 전사하는 AsrEngine 구현 (small KO 0.22 / small EN-US 0.15, maxChunkSec 0)
// 기능: prepare — 스펙의 모든 파일 존재 확인 → Model(모델 폴더 절대경로) 1회 생성(모델 로드), 로드 시간 측정.
//       transcribe — 언어 검사 → 준비 확인 → chunk 마다 새 Recognizer(model, 16000f)를 만들어 PCM16(PcmConverter.floatToPcm16)을
//                    4000 샘플 블록으로 acceptWaveForm(ShortArray, n) 로 흘려 넣고, 블록 사이마다 중단 요청을 확인한다.
//                    acceptWaveForm 이 true(발화 끝점)를 돌려주면 그때까지의 결과(getResult)를 모아 두고, 마지막에 getFinalResult 의 text 를 이어 붙인다
//                    (끝점마다 모으지 않으면 앞 발화가 버려진다). Recognizer 는 finally 에서 항상 close.
//                    ChunkText(text = 발화별 text 를 공백으로 이은 것, rawText = 같은 값, detectedLang = null, inferSec).
//       cancel — 중단 플래그를 켠다. 블록(0.25 s 분량)마다 확인하므로 중단 지연은 블록 1개 처리 시간 수준이다.
//       close — 새 작업 거부 → 중단 요청 → 진행 중 전사·로드가 끝나기를 기다린 뒤 Model 을 1회만 close(반복 호출 안전).
//       잠금 lock 1개가 로드·전사·해제를 직렬화한다(sherpa 의 SherpaEngineCore 와 같은 순서: 전사 시작 때 중단 플래그를 먼저 지우고 그 다음 closed 확인 →
//       그 사이에 온 close 는 반드시 NOT_PREPARED 로 막힘). sherpa 용 코드는 메시지가 sherpa 전용이라 공유하지 않고 같은 규칙만 이 파일에 따로 구현했다.
//       Vosk 는 스레드 수 설정이 없어 PrepareInfo.threads 는 실제 값 1 이다(측정/실제 값 원칙). 실행 장치는 CPU, 정밀도는 카탈로그 값.
// change(add)-hyungchul-2026-10-10-21:41
package com.cowork.stt_ondevice.engine.vosk // Vosk 엔진 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // float → PCM16
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // 전사 결과
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import com.cowork.stt_ondevice.model.VoskConfig // Vosk 설정
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import kotlinx.coroutines.Dispatchers // 코루틴 디스패처
import kotlinx.coroutines.withContext // 디스패처 전환
import org.vosk.Model // Vosk 모델(네이티브 핸들)
import org.vosk.Recognizer // Vosk 인식기
import java.io.File // 파일
import java.io.IOException // Vosk 모델·인식기 생성 실패
import java.util.concurrent.atomic.AtomicBoolean // 스레드 간 중단 플래그

// change(add)-hyungchul-2026-10-10-21:41
/**
 * 목적: Vosk 엔진
 * 기능: 위 파일 머리 설명 참조.
 * 입력: spec(runtime = VOSK, engineConfig = VoskConfig), modelDir(설치 폴더 models/<id>, Model 이 이 폴더 전체를 연다)
 * 출력: 스펙이 맞지 않으면 EngineException(MODEL_LOAD, CONFIG_MISMATCH)
 */
class VoskEngine(
    override val spec: ModelSpec, // 모델 메타
    val modelDir: File, // 모델 폴더
) : AsrEngine {

    private val lock = Any() // 네이티브 생성·전사·해제 직렬화 잠금
    @Volatile private var model: Model? = null // Vosk 모델(null = 준비 안 됨/해제됨)
    @Volatile private var closed = false // close 호출 여부
    private val cancelRequested = AtomicBoolean(false) // 중단 요청 플래그

    init {
        if (spec.runtime != Runtime.VOSK || spec.engineConfig !is VoskConfig) { // Vosk 스펙이 아님
            throw EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "Vosk 스펙이 아님(runtime=${spec.runtime}, engineConfig=${spec.engineConfig}): ${spec.id}") // 거부
        }
    }

    /**
     * 목적: 모델 로드
     * 기능: 스레드 수·닫힘·중복 준비·파일 존재 확인 → Dispatchers.IO 에서 잠금을 잡고 다시 닫힘·중복 확인 → Model(절대경로) 생성(시간 측정, 예외 매핑) →
     *       그 사이 close 되었으면 즉시 해제하고 CLOSED → 모델 공개.
     * 입력: threads(추론 스레드 수, 1 이상 — Vosk 는 스레드 설정이 없어 값은 검사에만 쓴다)
     * 출력: threads < 1 → IllegalArgumentException,
     *       그 밖 → EngineException(MODEL_LOAD, CLOSED/ALREADY_PREPARED/MODEL_FILE_MISSING/OOM/NATIVE_LIB_MISSING/LOAD_FAILED)
     * 리턴: PrepareInfo(loadSec, 카탈로그 런타임 버전, "CPU", spec.precision, 실제 스레드 수 1)
     */
    override suspend fun prepare(threads: Int): PrepareInfo {
        require(threads >= 1) { "threads 는 1 이상이어야 함: $threads" } // 인자 검사
        if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 재사용 금지
        if (model != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 중복 로드 금지
        val missing = spec.files.filter { !File(modelDir, it.path).isFile }.map { it.path } // 없는 모델 파일들
        if (missing.isNotEmpty()) throw EngineException("MODEL_LOAD", "MODEL_FILE_MISSING", "모델 파일 없음(${spec.id}, ${modelDir.absolutePath}): $missing") // 네이티브 전 확인
        val loadSec = withContext(Dispatchers.IO) { // 파일 읽기 위주 → IO 디스패처
            synchronized(lock) { // 네이티브 직렬화
                if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 잠금 대기 중 닫힘
                if (model != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 동시 prepare
                val t0 = System.nanoTime() // 시작 시각
                val m = loadModel() // 네이티브 로드(예외 매핑)
                val sec = (System.nanoTime() - t0) / 1e9 // 로드 시간(초)
                if (closed) { // 로드 중 close 가 불렸으면(그 close 는 잠금 대기 중)
                    m.close() // 즉시 해제
                    throw EngineException("MODEL_LOAD", "CLOSED", "로드 중 엔진이 닫힘: ${spec.id}") // 닫힘 보고
                }
                model = m // 모델 공개
                sec // 로드 시간 반환
            }
        }
        return PrepareInfo(loadSec = loadSec, runtimeVersion = spec.runtimeVersion, backend = BACKEND, precision = spec.precision, threads = ENGINE_THREADS) // 준비 결과
    }

    /**
     * 목적: 네이티브 Model 생성과 예외 매핑
     * 기능: Model(modelDir 절대경로) 를 만들고, OOM → OOM, LinkageError(libvosk.so·libjnidispatch.so 로드 실패, 초기화 실패) → NATIVE_LIB_MISSING,
     *       IOException(모델 폴더가 올바르지 않아 ptr null) 및 RuntimeException → LOAD_FAILED 로 바꾼다.
     * 입력: 없음
     * 출력: 위 매핑의 EngineException(stage MODEL_LOAD)
     * 리턴: Vosk Model
     */
    private fun loadModel(): Model =
        try { // 네이티브 로드
            Model(modelDir.absolutePath) // vosk_model_new
        } catch (e: OutOfMemoryError) { // 메모리 부족
            throw EngineException("MODEL_LOAD", "OOM", "모델 로드 중 메모리 부족: ${spec.id}", e) // OOM 코드
        } catch (e: LinkageError) { // libvosk.so / libjnidispatch.so 없음, JNA 초기화 실패
            throw EngineException("MODEL_LOAD", "NATIVE_LIB_MISSING", "Vosk 네이티브 라이브러리 로드 실패: ${e.message}", e) // 누락 .so 코드
        } catch (e: IOException) { // "Failed to create a model"
            throw EngineException("MODEL_LOAD", "LOAD_FAILED", "Vosk 모델 로드 실패(${spec.id}): ${e.message}", e) // 일반 실패
        } catch (e: RuntimeException) { // 그 밖의 런타임 오류
            throw EngineException("MODEL_LOAD", "LOAD_FAILED", "Vosk 모델 로드 실패(${spec.id}): ${e.message}", e) // 일반 실패
        }

    /**
     * 목적: chunk 1개 전사
     * 기능: 언어 검사 → 준비 확인 → Dispatchers.Default 에서 잠금을 잡고 ① 중단 플래그 지움 ② 모델·closed 다시 확인 ③ 예외 매핑 안에서 recognize 실행.
     *       ①을 ②보다 먼저 해서, 그 사이에 close 가 와도 ②에서 거부된다(중단 요청 소실 없음).
     * 입력: chunk(16 kHz mono float 샘플), lang(언어 코드, spec.languages 안이어야 함)
     * 출력: EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE/NOT_PREPARED/ABORTED/OOM/NATIVE_ERROR)
     * 리턴: ChunkText(text, rawText = text, detectedLang = null, inferSec)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        if (!supports(lang)) throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "${spec.id} 는 언어 '$lang' 미지원(지원: ${spec.languages})") // 언어 검사(네이티브 전)
        if (closed || model == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "prepare 전이거나 닫힌 엔진: ${spec.id}") // 준비 확인
        return withContext(Dispatchers.Default) { // CPU 계산 → Default 디스패처
            synchronized(lock) { // 같은 모델 동시 사용·전사 중 해제 방지
                cancelRequested.set(false) // 새 전사 시작 — 이전 중단 요청 지움(closed 확인보다 먼저)
                val m = model // 잠금 안에서 다시 읽음(close 가 먼저 해제했을 수 있음)
                if (closed || m == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "닫힌 엔진: ${spec.id}") // 해제됨
                try { // 네이티브 예외 → EngineException
                    val t0 = System.nanoTime() // 시작 시각
                    val text = recognize(m, chunk) // 인식
                    val inferSec = (System.nanoTime() - t0) / 1e9 // 추론 시간(초)
                    ChunkText(text = text, rawText = text, detectedLang = null, inferSec = inferSec) // 결과(언어 고정이라 감지 언어 없음)
                } catch (e: EngineException) { // 중단은 그대로
                    throw e // 재던짐
                } catch (e: OutOfMemoryError) { // 메모리 부족
                    throw EngineException("TRANSCRIBE", "OOM", "전사 중 메모리 부족(chunk ${chunk.index})", e) // OOM 코드
                } catch (e: IOException) { // Recognizer 생성 실패
                    throw EngineException("TRANSCRIBE", "NATIVE_ERROR", "Vosk 전사 실패(chunk ${chunk.index}): ${e.message}", e) // 일반 실패
                } catch (e: RuntimeException) { // 결과 JSON 파싱 실패 등
                    throw EngineException("TRANSCRIBE", "NATIVE_ERROR", "Vosk 전사 실패(chunk ${chunk.index}): ${e.message}", e) // 일반 실패
                }
            }
        }
    }

    /**
     * 목적: Recognizer 하나로 chunk 전체 인식
     * 기능: Recognizer(model, 16000f) 생성 → PCM16 변환 → 4000 샘플 블록마다 중단 확인 후 acceptWaveForm, true(끝점)이면 getResult 의 text 를 모음 →
     *       getFinalResult 의 text 를 모아 공백으로 이음. Recognizer 는 finally 에서 close.
     * 입력: m(Vosk 모델), chunk(오디오)
     * 출력: 중단 요청 시 EngineException(TRANSCRIBE, ABORTED), 결과 JSON 이 깨졌으면 SerializationException
     * 리턴: 인식 텍스트(발화 사이 공백 1칸, 아무것도 없으면 "")
     */
    private fun recognize(m: Model, chunk: AudioChunk): String {
        val pcm = PcmConverter.floatToPcm16(chunk.samples) // float → PCM16
        val parts = ArrayList<String>() // 발화별 text 모음
        Recognizer(m, SAMPLE_RATE).use { rec -> // chunk 전용 새 인식기(= chunk 별 초기화), 끝나면 close
            val block = ShortArray(BLOCK_SAMPLES) // 입력 블록 버퍼(재사용)
            var off = 0 // 다음 블록 시작 위치
            while (off < pcm.size) { // 블록마다
                checkCancel(chunk) // 블록 전 중단 확인
                val n = minOf(BLOCK_SAMPLES, pcm.size - off) // 이번 블록 샘플 수(마지막은 짧을 수 있음)
                System.arraycopy(pcm, off, block, 0, n) // 블록 버퍼에 복사
                if (rec.acceptWaveForm(block, n)) { // 발화 끝점 → 확정 결과
                    VoskResult.text(rec.result).let { if (it.isNotEmpty()) parts.add(it) } // 확정 text 모으기
                }
                off += n // 다음 블록
            }
            checkCancel(chunk) // 마무리 전 중단 확인
            VoskResult.text(rec.finalResult).let { if (it.isNotEmpty()) parts.add(it) } // 남은 발화 flush
        }
        return parts.joinToString(" ") // 발화 사이 공백 1칸
    }

    /**
     * 목적: 중단 요청 확인
     * 기능: 플래그가 켜져 있으면 EngineException(TRANSCRIBE, ABORTED) 를 던진다.
     * 입력: chunk(메시지용)
     * 출력: 중단 요청 시 EngineException
     * 리턴: 없음
     */
    private fun checkCancel(chunk: AudioChunk) {
        if (cancelRequested.get()) throw EngineException("TRANSCRIBE", "ABORTED", "전사 중단됨(chunk ${chunk.index}, ${spec.id})") // 중단
    }

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: 중단 플래그를 켠다(다른 스레드에서 불러도 안전, 준비 전·해제 후에도 무해). 전사는 다음 블록 경계에서 ABORTED 로 끝난다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() {
        cancelRequested.set(true) // 플래그 켜기
    }

    /**
     * 목적: 모델 해제(반복 호출 안전)
     * 기능: closed 표시 → 중단 요청 → 잠금으로 진행 중 전사·로드가 끝나기를 기다림 → 모델을 null 로 바꾸고 1회만 close.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        closed = true // 새 작업 거부
        cancel() // 진행 중 전사가 있으면 다음 블록 경계에서 끝나게 함
        synchronized(lock) { // 전사·로드가 끝날 때까지 대기
            val m = model // 현재 모델
            model = null // 이후 전사는 거부
            m?.close() // 1회만 해제(두 번째 close 는 m == null)
        }
    }

    companion object {
        const val BACKEND = "CPU" // 실행 장치
        const val ENGINE_THREADS = 1 // Vosk 가 실제로 쓰는 추론 스레드 수(설정 불가)
        const val SAMPLE_RATE = 16000f // Recognizer 샘플레이트(Hz)
        const val BLOCK_SAMPLES = 4000 // 입력 블록 0.25 s(16 kHz)
    }
}
