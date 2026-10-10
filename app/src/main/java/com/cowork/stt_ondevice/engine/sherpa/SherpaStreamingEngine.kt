// 파일명: SherpaStreamingEngine.kt
// 목적: sherpa-onnx OnlineRecognizer(v1.13.8)로 chunk 를 스트리밍 방식으로 전사하는 AsrEngine 구현 (streaming zipformer KO, maxChunkSec 0)
// 기능: prepare — 스펙의 모든 파일 존재 확인 → SherpaConfigs.online 로 설정 → OnlineRecognizer 생성(모델 로드), 시간·버전 측정.
//       transcribe — 언어 검사 → 준비 확인 → chunk 마다 새 스트림(= chunk 별 초기화)을 만들어
//                    0.1 s(1600 샘플) 단위로 acceptWaveform → isReady 인 동안 decode 를 반복하고,
//                    끝에 0.8 s 0 패딩(마지막 프레임까지 인코더에 밀어 넣기) + inputFinished() 뒤 isReady 가 false 가 될 때까지 decode,
//                    getResult 의 text 를 최종 결과로 쓴다(rawText = 원문, text = 공백 정리본, detectedLang = null).
//                    끝점 검출은 설정에서 꺼 두었고 reset 하지 않으므로 chunk 전체가 한 결과로 이어진다.
//       cancel — 중단 플래그를 켠다. 0.1 s 블록마다·decode 호출마다 확인하므로 중단 지연은 decode 1회 시간 수준이다.
//       close — 새 작업 거부 → 중단 요청 → 진행 중 전사가 끝나기를 기다린 뒤 인식기를 1회만 해제(반복 호출 안전).
//       잠금: runLock 이 생성·전사·해제를 직렬화한다(SherpaOfflineEngine·WhisperCppEngine 과 같은 방식).
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
import com.k2fsa.sherpa.onnx.OnlineRecognizer // sherpa 스트리밍 인식기(복사한 API)
import com.k2fsa.sherpa.onnx.OnlineStream // sherpa 스트리밍 스트림
import com.k2fsa.sherpa.onnx.VersionInfo // sherpa 네이티브 버전
import kotlinx.coroutines.Dispatchers // 코루틴 디스패처
import kotlinx.coroutines.withContext // 디스패처 전환
import java.io.File // 파일
import java.util.concurrent.atomic.AtomicBoolean // 스레드 간 중단 플래그

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: sherpa-onnx 스트리밍 엔진
 * 기능: 위 파일 머리 설명 참조.
 * 입력: spec(runtime = SHERPA_ONLINE, engineConfig = SherpaConfig(STREAMING_ZIPFORMER)), modelDir(설치 폴더 models/<id>)
 * 출력: 스펙이 맞지 않으면 EngineException(MODEL_LOAD, CONFIG_MISMATCH)
 */
class SherpaStreamingEngine(
    override val spec: ModelSpec, // 모델 메타
    val modelDir: File, // 모델 폴더
) : AsrEngine {

    private val kind: SherpaKind = SherpaConfigs.kindOf(spec) // sherpa 모델 종류(SherpaConfig 아니면 CONFIG_MISMATCH)
    private val runLock = Any() // 네이티브 생성·전사·해제 직렬화 잠금
    @Volatile private var recognizer: OnlineRecognizer? = null // 인식기(null = 준비 안 됨/해제됨)
    @Volatile private var closed = false // close 호출 여부
    private val cancelRequested = AtomicBoolean(false) // 중단 요청 플래그

    init {
        if (spec.runtime != Runtime.SHERPA_ONLINE || kind != SherpaKind.STREAMING_ZIPFORMER) { // 스트리밍 스펙이 아님
            throw EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "sherpa 스트리밍 스펙이 아님(runtime=${spec.runtime}, kind=$kind): ${spec.id}") // 거부
        }
    }

    /**
     * 목적: 모델 로드
     * 기능: 스레드 수·닫힘·중복 준비·파일 존재를 확인하고 설정을 만든 뒤 Dispatchers.IO 에서 OnlineRecognizer 를 생성, 시간을 잰다.
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
        val config = SherpaConfigs.online(spec, modelDir, threads) // 설정(역할 → 필드)
        return withContext(Dispatchers.IO) { // 파일 읽기 위주 → IO 디스패처
            synchronized(runLock) { // 네이티브 직렬화
                if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 잠금 대기 중 닫힘
                if (recognizer != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 동시 prepare
                val t0 = System.nanoTime() // 시작 시각
                val r = try { // 네이티브 로드
                    OnlineRecognizer(config = config) // newFromFile(설정 검증 실패 시 ptr 0 → IllegalArgumentException)
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
                recognizer = r // 인식기 공개
                PrepareInfo( // 준비 결과
                    loadSec = loadSec, // 로드 시간
                    runtimeVersion = VersionInfo.version, // 네이티브가 보고한 sherpa 버전
                    backend = SherpaOfflineEngine.BACKEND, // CPU 전용(provider "cpu")
                    precision = spec.precision, // 카탈로그 정밀도
                    threads = threads, // 스레드 수
                )
            }
        }
    }

    /**
     * 목적: chunk 1개 스트리밍 전사
     * 기능: 언어 검사 → 준비 확인 → Dispatchers.Default 에서 runLock 을 잡고 feed(새 스트림, 블록 입력·decode, 꼬리 패딩·마무리), 시간 측정.
     * 입력: chunk(16 kHz mono float 샘플), lang(언어 코드, spec.languages 안이어야 함)
     * 출력: EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE/NOT_PREPARED/ABORTED/OOM/NATIVE_ERROR)
     * 리턴: ChunkText(text, rawText, detectedLang = null, inferSec)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        if (!supports(lang)) throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "${spec.id} 는 언어 '$lang' 미지원(지원: ${spec.languages})") // 언어 검사(네이티브 전)
        if (closed || recognizer == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "prepare 전이거나 닫힌 엔진: ${spec.id}") // 준비 확인
        return withContext(Dispatchers.Default) { // CPU 계산 → Default 디스패처
            synchronized(runLock) { // 같은 인식기 동시 사용·전사 중 해제 방지
                val r = recognizer // 잠금 안에서 다시 읽음
                if (closed || r == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "닫힌 엔진: ${spec.id}") // 해제됨
                cancelRequested.set(false) // 새 전사 시작 — 이전 중단 요청 지움
                try { // 네이티브 예외 → EngineException
                    val t0 = System.nanoTime() // 시작 시각
                    val stream = r.createStream() // chunk 전용 새 스트림(= chunk 별 초기화)
                    try { // 스트림은 반드시 해제
                        feed(r, stream, chunk) // 입력·decode
                        val res = r.getResult(stream) // 최종 결과
                        val inferSec = (System.nanoTime() - t0) / 1e9 // 추론 시간(초)
                        ChunkText(text = SherpaOfflineEngine.normalizeSpaces(res.text), rawText = res.text, detectedLang = null, inferSec = inferSec) // 결과
                    } finally { // 성공·실패 모두
                        stream.release() // 네이티브 스트림 해제
                    }
                } catch (e: EngineException) { // 중단은 그대로
                    throw e // 재던짐
                } catch (e: OutOfMemoryError) { // 메모리 부족
                    throw EngineException("TRANSCRIBE", "OOM", "전사 중 메모리 부족(chunk ${chunk.index})", e) // OOM 코드
                } catch (e: RuntimeException) { // 스트림 생성 실패 등
                    throw EngineException("TRANSCRIBE", "NATIVE_ERROR", "sherpa 스트리밍 전사 실패(chunk ${chunk.index}): ${e.message}", e) // 일반 실패
                }
            }
        }
    }

    /**
     * 목적: 스트림에 chunk 를 흘려 넣고 끝까지 decode
     * 기능: 0.1 s 블록마다 acceptWaveform → isReady 동안 decode, 이어서 0.8 s 0 패딩 + inputFinished → isReady 가 false 가 될 때까지 decode.
     *       블록·decode 마다 중단 요청을 확인한다(runLock 안에서만 호출).
     * 입력: r(인식기), stream(이 chunk 의 새 스트림), chunk(오디오)
     * 출력: 중단 요청 시 EngineException(TRANSCRIBE, ABORTED)
     * 리턴: 없음
     */
    private fun feed(r: OnlineRecognizer, stream: OnlineStream, chunk: AudioChunk) {
        val samples = chunk.samples // 입력 샘플
        var off = 0 // 다음 블록 시작 위치
        while (off < samples.size) { // 블록마다
            checkCancel(chunk) // 블록 전 중단 확인
            val end = minOf(off + BLOCK_SAMPLES, samples.size) // 블록 끝(마지막은 짧을 수 있음)
            stream.acceptWaveform(samples.copyOfRange(off, end), SherpaConfigs.SAMPLE_RATE) // 0.1 s 입력
            decodeWhileReady(r, stream, chunk) // 준비된 프레임 decode
            off = end // 다음 블록
        }
        checkCancel(chunk) // 마무리 전 중단 확인
        stream.acceptWaveform(FloatArray(TAIL_SAMPLES), SherpaConfigs.SAMPLE_RATE) // 0.8 s 0 패딩(마지막 음성 프레임까지 처리되게)
        stream.inputFinished() // 입력 끝 알림
        decodeWhileReady(r, stream, chunk) // 남은 프레임 모두 decode
    }

    /**
     * 목적: 준비된 프레임 모두 decode
     * 기능: isReady 가 true 인 동안 중단 확인 후 decode 를 반복한다.
     * 입력: r(인식기), stream(스트림), chunk(오류 메시지용)
     * 출력: 중단 요청 시 EngineException(TRANSCRIBE, ABORTED)
     * 리턴: 없음
     */
    private fun decodeWhileReady(r: OnlineRecognizer, stream: OnlineStream, chunk: AudioChunk) {
        while (r.isReady(stream)) { // 처리할 프레임이 있는 동안
            checkCancel(chunk) // decode 전 중단 확인
            r.decode(stream) // 네이티브 decode 1회
        }
    }

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: 중단 플래그를 켠다(다른 스레드에서 불러도 안전, 준비 전·해제 후에도 무해).
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
            r?.release() // 1회만 해제(두 번째 close 는 r == null)
        }
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
        const val BLOCK_SAMPLES = 1600 // 입력 블록 0.1 s(16 kHz)
        const val TAIL_SAMPLES = 12800 // 꼬리 0 패딩 0.8 s(16 kHz)
    }
}
