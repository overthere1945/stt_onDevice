// 파일명: ItemProcessor.kt
// 목적: 조합(모델 × VAD 프리셋) × 입력 파일 1건을 처리해 상세 결과(DetailJson)를 만든다 (spec §8, §10)
// 기능: WAV 읽기·16k mono 변환(preprocess_sec) → VadCache(음원 SHA-1 키, 모든 엔진이 같은 구간·점수 사용) → ChunkPlanner → chunk 별 transcribe(transcribe_sec 합)
//       → 상태(OK/NO_MATCH/ERROR) 결정. 파일 단위 예외는 stage/code 로 기록하고 다음 항목이 계속되게 한다(오류 격리).
//       자원 측정(ResourceSampler)은 SupervisorJob 자식 범위에서 돌려 샘플러 실패가 실행을 취소하지 않게 하고, 항상 stop 한다.
//       코루틴이 취소되면 감시 코루틴이 engine.cancel() 을 불러 네이티브 전사를 멈춘다(엔진은 Job 취소를 스스로 감지하지 않음).
//       change(add)-hyungchul-2026-10-11-18:37 chunk 결과에 시스템 엔진 세션 기록(ChunkText.session → ChunkJson.session)을 함께 담는다(2차 작업 3).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // 16k mono 변환
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 읽기
import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.core.device.ResourceSampler // 자원 샘플러
import com.cowork.stt_ondevice.core.device.ResourceStats // 자원 측정 결과
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // chunk 전사 결과
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.output.ChunkJson // chunk 결과 JSON
import com.cowork.stt_ondevice.output.DetailJson // 상세 JSON
import com.cowork.stt_ondevice.output.EngineMetaJson // 엔진 메타 JSON
import com.cowork.stt_ondevice.output.EnvSnapshotJson // 환경 JSON
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.output.SessionJson // 세션 기록 JSON // change(add)-hyungchul-2026-10-11-18:37
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.ChunkPlanner // chunk 분할기
import com.cowork.stt_ondevice.vad.VadCache // VAD 캐시
import com.cowork.stt_ondevice.vad.VadEngine // VAD 계약
import kotlinx.coroutines.CoroutineExceptionHandler // 샘플러 예외 처리
import kotlinx.coroutines.CoroutineScope // 코루틴 범위
import kotlinx.coroutines.CoroutineStart // 즉시 시작 옵션
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.Job // 작업 핸들
import kotlinx.coroutines.SupervisorJob // 실패 격리 Job
import kotlinx.coroutines.awaitCancellation // 취소까지 대기
import kotlinx.coroutines.coroutineScope // 자식 범위
import kotlinx.coroutines.currentCoroutineContext // 현재 코루틴 문맥
import kotlinx.coroutines.ensureActive // 취소 확인
import kotlinx.coroutines.launch // 코루틴 시작
import java.util.concurrent.atomic.AtomicBoolean // 스레드 안전 플래그
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 준비가 끝난 모델
 * 기능: 엔진과 prepare 결과를 묶는다.
 * 입력: engine(준비된 엔진), info(prepare 결과)
 */
internal data class PreparedModel(
    val engine: AsrEngine, // 엔진
    val info: PrepareInfo, // 준비 결과
) {
    /** 목적: 엔진 메타 JSON. 기능: PrepareInfo 를 EngineMetaJson 으로 바꾼다. 입력: 없음. 출력: 없음. 리턴: EngineMetaJson */
    fun meta(): EngineMetaJson = EngineMetaJson(info.runtimeVersion, info.backend, info.precision, info.threads) // 변환
}

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 항목 1건 처리기
 * 기능: process()(정상 경로), failed()(모델 로드 실패·VAD 사용 불가·크래시처럼 처리 전에 정해진 오류의 detail)를 제공한다.
 * 입력: clock(단조 시계 ns), now(ISO8601 현재 시각), env(환경 스냅샷, 못 읽으면 null), samplerFactory(항목마다 새 샘플러)
 */
internal class ItemProcessor(
    private val clock: () -> Long, // 단조 시계(ns)
    private val now: () -> String, // 벽시계 ISO8601
    private val env: () -> EnvSnapshot?, // 환경 스냅샷
    private val samplerFactory: () -> ResourceSampler, // 샘플러 생성
) {

    /**
     * 목적: 경과 초 계산
     * 기능: clock() 기준 시작 시각부터 지금까지의 초.
     * 입력: startNs(시작 시각 ns)
     * 출력: 없음
     * 리턴: 경과 초
     */
    private fun secSince(startNs: Long): Double = (clock() - startNs) / 1_000_000_000.0 // ns → 초

    /**
     * 목적: 환경 스냅샷 안전 읽기
     * 기능: env() 가 예외를 던지면 null(측정 불가)로 둔다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: EnvSnapshotJson 또는 null
     */
    private fun envJson(): EnvSnapshotJson? = try { env()?.let(EnvSnapshotJson::from) } catch (e: Exception) { null } // 실패는 null

    /**
     * 목적: 파일 1건 처리(정상 경로)
     * 기능: 시작 시각·환경 기록과 자원 측정을 시작하고, INPUT → VAD → TRANSCRIBE 단계를 차례로 실행한다.
     *       chunk 가 0개면 엔진을 부르지 않고 NO_MATCH. 예외는 현재 단계의 오류로 기록(취소는 다시 던짐).
     *       transcribe_sec 는 러너가 같은 시계로 잰 chunk 호출 시간의 합(엔진 간 같은 측정 방식), chunk JSON 의 inferSec 는 엔진 보고값.
     * 입력: combo(조합), item(입력 항목), model(준비된 모델), vad(이 프리셋 계열의 VAD 엔진), cache(VAD 캐시)
     * 출력: 코루틴 취소 시 CancellationException
     * 리턴: DetailJson(OK/NO_MATCH/ERROR)
     */
    suspend fun process(combo: Combo, item: InputItem, model: PreparedModel, vad: VadEngine, cache: VadCache): DetailJson {
        val startedAt = now() // 처리 시작 시각
        val t0 = clock() // 전체 시간 측정 시작
        val envStart = envJson() // 시작 환경
        val sampler = SamplerHandle.start(samplerFactory) // 자원 측정 시작
        var stage = "INPUT" // 현재 단계
        var audioSec: Double? = null // 음원 길이
        var sampleRate: Int? = null // 원본 샘플레이트
        var channels: Int? = null // 원본 채널 수
        var preprocessSec: Double? = null // 전처리 시간
        var vadSec: Double? = null // VAD 시간
        var vadReused: Boolean? = null // VAD 재사용 여부
        var scoresAvailable = false // 프레임 점수 유무
        var transcribeSec: Double? = null // 전사 시간 합
        var detectedLang: String? = null // 감지 언어
        var error: ErrorJson? = null // 오류
        var planned = 0 // 계획된 chunk 수
        val chunks = ArrayList<ChunkJson>() // 완료된 chunk 결과
        var stats: ResourceStats? = null // 자원 측정 결과
        try {
            val tp = clock() // 전처리 측정 시작
            val bytes = item.file.readBytes() // 원본 바이트(SHA-1 에도 사용)
            val wav = WavReader.read(bytes) // WAV 파싱
            val clip = PcmConverter.toMono16k(wav) // 16k mono 변환
            preprocessSec = secSince(tp) // 전처리 시간
            sampleRate = wav.sampleRate // 원본 샘플레이트
            channels = wav.channels // 원본 채널 수
            audioSec = clip.durationSec // 음원 길이
            stage = "VAD" // VAD 단계
            val cached = cache.getOrCompute(combo.preset, item, sha1Hex(bytes)) { vad.detect(clip.samples, combo.preset) } // 캐시 우선
            vadSec = cached.vadSec // VAD 시간
            vadReused = cached.reused // 재사용 여부
            scoresAvailable = cached.output.frameScores != null // 점수 유무
            val plan = ChunkPlanner.plan(clip.samples, clip.sampleRate, cached.output, combo.maxChunkSec, combo.chunkMode) // chunk 계획
            planned = plan.size // chunk 수
            stage = "TRANSCRIBE" // 전사 단계
            var sum = 0.0 // 전사 시간 누적
            for (chunk in plan) { // chunk 마다
                val tc = clock() // 호출 시간 측정 시작
                val r = transcribeCancellable(model.engine, chunk, item.engineLang) // 취소 연동 전사
                sum += secSince(tc) // 호출 시간 누적
                chunks.add(ChunkJson.from(chunk, r.text, r.rawText, r.inferSec, r.session?.let(SessionJson::from))) // chunk 결과(시스템 엔진은 세션 기록 포함) // change(add)-hyungchul-2026-10-11-18:37
                if (detectedLang == null) detectedLang = r.detectedLang // 첫 감지 언어
            }
            transcribeSec = sum // 전사 시간 합(chunk 0개면 0)
        } catch (e: CancellationException) {
            throw e // 취소 전파(샘플러는 finally 에서 종료)
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive() // 취소로 인한 ABORTED 면 오류로 기록하지 않고 취소 전파
            error = RunErrors.toError(t, stage, "${stage}_FAILED") ?: throw t // 격리 가능한 오류만 기록
        } finally {
            stats = sampler.stop() // 어떤 경로든 자원 측정 종료(범위 Job 정리 포함)
        }
        val elapsed = secSince(t0) // 전체 시간
        val status = when { // 상태 결정
            error != null -> "ERROR" // 오류
            planned == 0 -> "NO_MATCH" // 발화 없음
            else -> "OK" // 정상
        }
        val ts = transcribeSec // 스마트 캐스트용
        val au = audioSec // 스마트 캐스트용
        return DetailJson(
            combo = combo.name, lang = item.lang, relDir = item.relDir, stem = item.stem, file = item.file.name, // 식별
            status = status, error = error, // 상태·오류
            vadPresetId = combo.preset.id, vadReused = vadReused, vadFrameScoresAvailable = scoresAvailable, // VAD 정보
            startedAt = startedAt, audioSec = audioSec, preprocessSec = preprocessSec, vadSec = vadSec, loadSec = model.info.loadSec, // 시간
            transcribeSec = ts, elapsedSec = elapsed, // 전사·전체 시간
            rtf = if (ts != null && au != null && au > 0.0) ts / au else null, // 실시간 배율
            cpuTimeSec = stats?.cpuTimeSec, pssPeakMb = stats?.pssPeakMb, // 자원
            envStart = envStart, envEnd = envJson(), // 환경
            chunks = chunks, // chunk 결과
            audioBytes = item.file.length().takeIf { item.file.isFile }, sampleRate = sampleRate, channels = channels, // 원본 음원
            detectedLang = detectedLang, engine = model.meta(), // 언어·엔진 메타
        )
    }

    /**
     * 목적: 처리 전에 정해진 오류의 detail 생성
     * 기능: 모델 로드 실패·VAD 사용 불가·크래시 복구처럼 실제 처리를 하지 않은 항목의 ERROR detail 을 만든다. 측정하지 않은 값은 모두 null.
     * 입력: combo(조합), item(입력 항목), error(오류), model(준비된 모델, 로드 실패면 null)
     * 출력: 없음
     * 리턴: DetailJson(status ERROR)
     */
    fun failed(combo: Combo, item: InputItem, error: ErrorJson, model: PreparedModel?): DetailJson = DetailJson(
        combo = combo.name, lang = item.lang, relDir = item.relDir, stem = item.stem, file = item.file.name, // 식별
        status = "ERROR", error = error, // 상태·오류
        vadPresetId = combo.preset.id, vadReused = null, vadFrameScoresAvailable = false, // VAD 미실행
        startedAt = now(), audioSec = null, preprocessSec = null, vadSec = null, loadSec = model?.info?.loadSec, // 시간(미측정 null)
        transcribeSec = null, elapsedSec = null, rtf = null, cpuTimeSec = null, pssPeakMb = null, // 미측정
        envStart = null, envEnd = null, chunks = emptyList(), // 환경·chunk 없음
        audioBytes = item.file.length().takeIf { item.file.isFile }, sampleRate = null, channels = null, // 파일 크기만
        detectedLang = null, engine = model?.meta(), // 엔진 메타(있으면)
    )

    /**
     * 목적: 취소와 연동된 chunk 전사
     * 기능: 같은 범위에 감시 코루틴을 즉시 띄워 두고 transcribe 를 호출한다. transcribe 가 끝나기 전에 범위가 취소되면 감시 코루틴의 finally 가
     *       engine.cancel() 을 부른다(네이티브 블로킹 전사를 깨움). 정상·오류 종료 시에는 finished 를 먼저 세워 cancel 을 부르지 않는다.
     * 입력: engine(엔진), chunk(오디오 조각), lang(엔진 언어 코드)
     * 출력: 엔진 예외·취소 전파
     * 리턴: ChunkText
     */
    private suspend fun transcribeCancellable(engine: AsrEngine, chunk: AudioChunk, lang: String): ChunkText = coroutineScope {
        val finished = AtomicBoolean(false) // 전사 종료 여부
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { // 즉시 등록되는 감시 코루틴
            try {
                awaitCancellation() // 취소될 때까지 대기
            } finally {
                if (!finished.get()) engine.cancel() // 전사 도중 취소면 엔진 중단 요청
            }
        }
        try {
            engine.transcribe(chunk, lang) // 전사
        } finally {
            finished.set(true) // 종료 표시(감시 코루틴이 cancel 을 부르지 않게)
            watcher.cancel() // 감시 종료
        }
    }

    /**
     * 목적: 항목 단위 자원 측정 핸들
     * 기능: 부모 Job 의 SupervisorJob 자식 범위(Dispatchers.Default, 예외 처리기 포함)에서 샘플러를 돌린다.
     *       샘플러 시작/주기 표본/종료 중 하나라도 실패하면 측정값은 null(부분 표본으로 피크를 추정하지 않음)이고 실행은 계속된다.
     * 입력: sampler(시작된 샘플러, 시작 실패면 null), job(샘플러 범위 Job), failed(주기 표본 실패 플래그)
     */
    private class SamplerHandle(
        private val sampler: ResourceSampler?, // 샘플러
        private val job: Job, // 범위 Job
        private val failed: AtomicBoolean, // 실패 플래그
    ) {
        private var stopped = false // 중복 stop 방지
        private var result: ResourceStats? = null // 측정 결과

        /**
         * 목적: 측정 종료
         * 기능: 한 번만 sampler.stop() 을 부르고 범위 Job 을 취소한다(부모가 이 자식 때문에 끝나지 못하는 일이 없게). 두 번째 호출부터는 첫 결과를 돌려준다.
         * 입력: 없음
         * 출력: 없음
         * 리턴: ResourceStats 또는 null(측정 실패)
         */
        fun stop(): ResourceStats? {
            if (stopped) return result // 이미 종료
            stopped = true // 종료 표시
            try {
                val s = try { sampler?.stop() } catch (e: Exception) { failed.set(true); null } // 마지막 표본·통계
                result = if (failed.get()) null else s // 실패가 있었으면 null
            } finally {
                job.cancel() // 범위 정리
            }
            return result // 결과
        }

        companion object {
            /**
             * 목적: 측정 시작
             * 기능: 현재 코루틴 Job 을 부모로 하는 SupervisorJob 범위를 만들고 샘플러를 시작한다. 생성·시작 실패는 측정 불가로 처리.
             * 입력: factory(샘플러 생성)
             * 출력: 없음
             * 리턴: SamplerHandle
             */
            suspend fun start(factory: () -> ResourceSampler): SamplerHandle {
                val failed = AtomicBoolean(false) // 실패 플래그
                val job = SupervisorJob(currentCoroutineContext()[Job]) // 부모의 자식이지만 실패는 위로 전파하지 않음
                val scope = CoroutineScope(job + Dispatchers.Default + CoroutineExceptionHandler { _, _ -> failed.set(true) }) // 샘플러 범위(예외는 기록만)
                val sampler = try {
                    factory().also { it.start(scope) } // 생성·시작
                } catch (e: Exception) {
                    failed.set(true) // 시작 실패
                    null // 측정 불가
                }
                return SamplerHandle(sampler, job, failed) // 핸들
            }
        }
    }
}
