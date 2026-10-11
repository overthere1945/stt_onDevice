// 파일명: SherpaEngineSupport.kt
// 목적: SherpaOfflineEngine·SherpaStreamingEngine 이 같이 쓰는 생명주기·오류 처리 코드를 한 곳에 모으기(리뷰 수정 라운드 1)
// 기능: SherpaEngineSupport(internal object) — 실행 장치 이름(BACKEND), 공백 정리, 모델 파일 존재 검사,
//         네이티브 로드 예외 → EngineException(MODEL_LOAD, …) 매핑, 전사 예외 → EngineException(TRANSCRIBE, …) 매핑, PrepareInfo 생성.
//       SherpaEngineCore<R>(internal class) — 인식기 핸들 1개의 생명주기(준비·전사 직렬화·중단 플래그·1회 해제)를 담당한다.
//         엔진은 인식기 종류(R = OfflineRecognizer / OnlineRecognizer)와 생성·해제 방법만 넘기고 실제 전사 단계만 직접 쓴다.
//         잠금 lock 이 생성·전사·해제를 직렬화해 해제된 인식기를 쓰지 않게 한다(WhisperCppEngine 과 같은 방식).
// change(add)-hyungchul-2026-10-10-21:32
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 패키지

import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.k2fsa.sherpa.onnx.VersionInfo // sherpa 네이티브 버전
import kotlinx.coroutines.Dispatchers // 코루틴 디스패처
import kotlinx.coroutines.withContext // 디스패처 전환
import java.io.File // 파일
import java.util.concurrent.atomic.AtomicBoolean // 스레드 간 중단 플래그

// change(add)-hyungchul-2026-10-10-21:32
/**
 * 목적: sherpa 엔진 공통 도우미(상태 없음)
 * 기능: 위 파일 머리 설명 참조.
 */
internal object SherpaEngineSupport {

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

    /**
     * 목적: 모델 파일 존재 검사(네이티브 전)
     * 기능: spec.files 의 모든 path 가 dir 아래 파일로 있는지 보고, 없는 것을 모두 메시지에 담아 실패시킨다.
     * 입력: spec(모델 메타), dir(모델 폴더)
     * 출력: 하나라도 없으면 EngineException(MODEL_LOAD, MODEL_FILE_MISSING)
     * 리턴: 없음
     */
    fun checkModelFiles(spec: ModelSpec, dir: File) {
        val missing = spec.files.filter { !File(dir, it.path).isFile }.map { it.path } // 없는 파일들
        if (missing.isNotEmpty()) throw EngineException("MODEL_LOAD", "MODEL_FILE_MISSING", "모델 파일 없음(${spec.id}, ${dir.absolutePath}): $missing") // 실패
    }

    /**
     * 목적: 네이티브 인식기 생성 예외 매핑
     * 기능: block(인식기 생성)을 실행하고, OOM → OOM, LinkageError(UnsatisfiedLink·NoClassDefFound·InitializerError) → NATIVE_LIB_MISSING,
     *       RuntimeException(설정 검증 실패로 ptr 0 → require 등) → LOAD_FAILED 로 바꾼다. EngineException 은 그대로 통과한다.
     * 입력: spec(메시지용), block(생성 동작)
     * 출력: 위 매핑의 EngineException(stage MODEL_LOAD)
     * 리턴: block 의 결과
     */
    inline fun <T> loadNative(spec: ModelSpec, block: () -> T): T =
        try { // 네이티브 로드
            block() // 생성
        } catch (e: OutOfMemoryError) { // 메모리 부족
            throw EngineException("MODEL_LOAD", "OOM", "모델 로드 중 메모리 부족: ${spec.id}", e) // OOM 코드
        } catch (e: LinkageError) { // libsherpa-onnx-jni.so 없음/심볼 없음/초기화 실패
            throw EngineException("MODEL_LOAD", "NATIVE_LIB_MISSING", "sherpa 네이티브 라이브러리 로드 실패: ${e.message}", e) // 누락 .so 코드
        } catch (e: RuntimeException) { // 설정 검증 실패 등
            throw EngineException("MODEL_LOAD", "LOAD_FAILED", "sherpa 모델 로드 실패(${spec.id}): ${e.message}", e) // 일반 실패
        }

    /**
     * 목적: 전사 예외 매핑
     * 기능: block(전사 단계)을 실행하고, EngineException(중단·언어 오류)은 그대로, OOM → OOM, RuntimeException(스트림 생성 실패 등) → NATIVE_ERROR 로 바꾼다.
     * 입력: chunk(메시지용), block(전사 동작)
     * 출력: 위 매핑의 EngineException(stage TRANSCRIBE)
     * 리턴: block 의 결과
     */
    inline fun <T> mapTranscribeError(chunk: AudioChunk, block: () -> T): T =
        try { // 네이티브 예외 → EngineException
            block() // 전사
        } catch (e: EngineException) { // 중단·언어 오류는 그대로
            throw e // 재던짐
        } catch (e: OutOfMemoryError) { // 메모리 부족
            throw EngineException("TRANSCRIBE", "OOM", "전사 중 메모리 부족(chunk ${chunk.index})", e) // OOM 코드
        } catch (e: RuntimeException) { // 스트림 생성 실패 등
            throw EngineException("TRANSCRIBE", "NATIVE_ERROR", "sherpa 전사 실패(chunk ${chunk.index}): ${e.message}", e) // 일반 실패
        }

    /**
     * 목적: 준비 결과 만들기
     * 기능: 네이티브가 보고한 sherpa 버전(VersionInfo.version), CPU, 카탈로그 정밀도, 스레드 수로 PrepareInfo 를 만든다.
     * 입력: spec(모델 메타), loadSec(로드 시간, 초), threads(스레드 수)
     * 출력: 없음
     * 리턴: PrepareInfo
     */
    fun prepareInfo(spec: ModelSpec, loadSec: Double, threads: Int) = PrepareInfo(
        loadSec = loadSec, // 로드 시간
        runtimeVersion = VersionInfo.version, // 네이티브가 보고한 sherpa 버전
        backend = BACKEND, // CPU 전용(provider "cpu")
        precision = spec.precision, // 카탈로그 정밀도(int8 등)
        threads = threads, // 스레드 수
    )
}

// change(add)-hyungchul-2026-10-10-21:32
/**
 * 목적: sherpa 인식기 핸들 1개의 생명주기 관리
 * 기능: 준비(검사 → 설정 → IO 디스패처에서 생성·시간 측정, 로드 중 닫히면 즉시 해제), 전사(Default 디스패처에서 잠금 안 실행, 예외 매핑),
 *       중단 플래그(cancel·checkCancel), 해제(반복 호출 안전, 1회만 release)를 제공한다.
 *       중단 요청 소실 방지: 전사 시작 때 플래그를 먼저 지우고 그 다음 closed 를 확인하므로, 그 사이에 온 close 는 반드시 NOT_PREPARED 로 막힌다.
 * 입력: spec(모델 메타, 메시지용), release(인식기 해제 동작)
 * 출력: 해당 없음
 */
// change(add)-hyungchul-2026-10-10-21:41 참고: engine/vosk/VoskEngine.kt 가 같은 잠금·중단 규칙을 따로 구현함 — 이 규칙(전사 시작 때 플래그 먼저 지움 → closed 확인, 1회 해제)을 바꾸면 그쪽도 확인할 것
internal class SherpaEngineCore<R : Any>(
    private val spec: ModelSpec, // 모델 메타
    private val release: (R) -> Unit, // 인식기 해제 동작
) {
    private val lock = Any() // 네이티브 생성·전사·해제 직렬화 잠금
    @Volatile private var handle: R? = null // 인식기(null = 준비 안 됨/해제됨)
    @Volatile private var closed = false // close 호출 여부
    private val cancelRequested = AtomicBoolean(false) // 중단 요청 플래그

    /** 준비가 끝났고 아직 닫히지 않았는지. */
    val isReady: Boolean get() = !closed && handle != null // 상태 조회

    /**
     * 목적: 인식기 준비(모델 로드)
     * 기능: threads ≥ 1 → CLOSED/ALREADY_PREPARED → 모델 파일 존재 → configure() 로 설정 생성 →
     *       Dispatchers.IO 에서 잠금을 잡고 다시 CLOSED/ALREADY_PREPARED 확인 → create(설정) 시간 측정(loadNative 예외 매핑) →
     *       그 사이 close 되었으면 즉시 해제하고 CLOSED → onLoaded(인식기, 설정) → 핸들 공개.
     * 입력: threads(추론 스레드 수), modelDir(모델 폴더), configure(설정 생성), create(인식기 생성), onLoaded(잠금 안 후처리)
     * 출력: threads < 1 → IllegalArgumentException, 그 밖 → EngineException(MODEL_LOAD, …)
     * 리턴: 로드 시간(초)
     */
    suspend fun <C> load(
        threads: Int, // 스레드 수
        modelDir: File, // 모델 폴더
        configure: () -> C, // 설정 생성
        create: (C) -> R, // 인식기 생성
        onLoaded: (R, C) -> Unit = { _, _ -> }, // 잠금 안 후처리
    ): Double {
        require(threads >= 1) { "threads 는 1 이상이어야 함: $threads" } // 인자 검사
        if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 재사용 금지
        if (handle != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 중복 로드 금지
        SherpaEngineSupport.checkModelFiles(spec, modelDir) // 파일 확인(네이티브 전)
        val config = configure() // 설정(역할 → 필드)
        return withContext(Dispatchers.IO) { // 파일 읽기 위주 → IO 디스패처
            synchronized(lock) { // 네이티브 직렬화
                if (closed) throw EngineException("MODEL_LOAD", "CLOSED", "이미 닫힌 엔진: ${spec.id}") // 잠금 대기 중 닫힘
                if (handle != null) throw EngineException("MODEL_LOAD", "ALREADY_PREPARED", "이미 준비됨: ${spec.id}") // 동시 prepare
                val t0 = System.nanoTime() // 시작 시각
                val r = SherpaEngineSupport.loadNative(spec) { create(config) } // 네이티브 로드(예외 매핑)
                val loadSec = (System.nanoTime() - t0) / 1e9 // 로드 시간(초)
                if (closed) { // 로드 중 close 가 불렸으면(그 close 는 잠금 대기 중)
                    release(r) // 즉시 해제
                    throw EngineException("MODEL_LOAD", "CLOSED", "로드 중 엔진이 닫힘: ${spec.id}") // 닫힘 보고
                }
                onLoaded(r, config) // 엔진별 후처리(설정 보관 등)
                handle = r // 인식기 공개
                loadSec // 로드 시간 반환
            }
        }
    }

    /**
     * 목적: 인식기로 chunk 1개 처리(잠금·예외 매핑 포함)
     * 기능: 준비 확인 → Dispatchers.Default 에서 잠금을 잡고 ① 중단 플래그 지움 ② 핸들·closed 다시 확인 ③ mapTranscribeError 안에서 block(인식기) 실행.
     *       ①을 ②보다 먼저 해서, 그 사이에 close 가 와도 ②에서 거부된다(요청 소실 없음).
     * 입력: chunk(메시지용), block(전사 단계)
     * 출력: 준비 전·닫힘 → EngineException(TRANSCRIBE, NOT_PREPARED), 그 밖은 mapTranscribeError 매핑
     * 리턴: block 의 결과
     */
    suspend fun <T> transcribe(chunk: AudioChunk, block: (R) -> T): T {
        if (!isReady) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "prepare 전이거나 닫힌 엔진: ${spec.id}") // 준비 확인
        return withContext(Dispatchers.Default) { // CPU 계산 → Default 디스패처
            synchronized(lock) { // 같은 인식기 동시 사용·전사 중 해제 방지
                cancelRequested.set(false) // 새 전사 시작 — 이전 중단 요청 지움(closed 확인보다 먼저)
                val r = handle // 잠금 안에서 다시 읽음(close 가 먼저 해제했을 수 있음)
                if (closed || r == null) throw EngineException("TRANSCRIBE", "NOT_PREPARED", "닫힌 엔진: ${spec.id}") // 해제됨
                SherpaEngineSupport.mapTranscribeError(chunk) { block(r) } // 전사(예외 매핑)
            }
        }
    }

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: 중단 플래그를 켠다(다른 스레드에서 불러도 안전, 준비 전·해제 후에도 무해). 전사는 다음 checkCancel 지점에서 ABORTED 로 끝난다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun cancel() {
        cancelRequested.set(true) // 플래그 켜기(준비 전·해제 후에도 무해)
    }

    /**
     * 목적: 중단 요청 확인
     * 기능: 플래그가 켜져 있으면 EngineException(TRANSCRIBE, ABORTED) 를 던진다.
     * 입력: chunk(메시지용)
     * 출력: 중단 요청 시 EngineException
     * 리턴: 없음
     */
    fun checkCancel(chunk: AudioChunk) {
        if (cancelRequested.get()) throw EngineException("TRANSCRIBE", "ABORTED", "전사 중단됨(chunk ${chunk.index}, ${spec.id})") // 중단
    }

    /**
     * 목적: 인식기 해제(반복 호출 안전)
     * 기능: closed 표시 → 중단 요청 → 잠금으로 진행 중 전사·로드가 끝나기를 기다림 → 핸들을 null 로 바꾸고 1회만 release → onClosed(잠금 안 정리).
     * 입력: onClosed(엔진별 정리, 기본 없음)
     * 출력: 없음
     * 리턴: 없음
     */
    fun close(onClosed: () -> Unit = {}) {
        closed = true // 새 작업 거부
        cancel() // 진행 중 전사가 있으면 다음 확인 지점에서 끝나게 함
        synchronized(lock) { // 전사·로드가 끝날 때까지 대기
            val r = handle // 현재 인식기
            handle = null // 이후 전사는 거부
            onClosed() // 엔진별 정리
            if (r != null) release(r) // 1회만 해제(두 번째 close 는 r == null)
        }
    }
}
