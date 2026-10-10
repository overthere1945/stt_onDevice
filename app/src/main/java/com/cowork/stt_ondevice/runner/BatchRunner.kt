// 파일명: BatchRunner.kt
// 목적: 선택한 모든 모델 × VAD 프리셋 × 입력 파일을 순서대로 실행하고 PC 평가기 호환 결과를 쓰는 배치 실행기 (spec §10, §11, §13)
// 기능: - 시작: 새 실행이면 run_manifest.json 작성(기존 manifest 가 있으면 거부), 이어하기면 기존 manifest 를 읽고(읽을 수 없으면 거부) 설정 일치를 확인,
//         남은 inFlight 항목을 ResumeReconciler 로 정리.
//       - 언어 미지원 조합: 실행·폴더 생성 없이 manifest.unsupported 에 "combo|lang" 기록.
//       - 순서: 모델(선택 순) → 프리셋 → 파일. 모델마다 남은 항목이 있을 때만 엔진 생성·prepare(실패 시 그 모델 항목 전부 ERROR(MODEL_LOAD)),
//         항목마다 inFlight 기록 → ItemProcessor → ItemOutputs(txt → detail → CSV) → inFlight 해제. 이어하기면 txt/ERROR.txt 가 있는 항목은 건너뜀.
//       - VAD 엔진은 계열당 1개(VadPool), 실행 끝에 모두 닫음. 모델 엔진은 모델 단위로 finally 에서 close.
//       - 취소: 진행 중 전사는 engine.cancel() 로 멈추고(ItemProcessor), inFlight 를 지우고 finishedAt=null 로 둔 채 취소를 전파.
//       - 이벤트: 항목마다 Progress(전) / ItemDone(후), 마지막에 Finished. 진행 로그는 RunState.log.
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.core.device.ResourceSampler // 자원 샘플러
import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더 구조
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.core.storage.InputScanner // 언어 폴더 매핑
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.output.DetailJson // 상세 JSON
import com.cowork.stt_ondevice.output.DeviceInfoJson // 기기 JSON
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.output.ModelEntry // manifest 모델 항목
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.PresetEntry // manifest 프리셋 항목
import com.cowork.stt_ondevice.output.RunManifest // 실행 manifest
import com.cowork.stt_ondevice.vad.VadCache // VAD 캐시
import com.cowork.stt_ondevice.vad.VadEngine // VAD 계약
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import kotlinx.coroutines.currentCoroutineContext // 현재 코루틴 문맥
import kotlinx.coroutines.ensureActive // 취소 확인
import kotlinx.coroutines.flow.Flow // 이벤트 흐름
import kotlinx.coroutines.flow.flow // 흐름 빌더
import java.io.File // 파일
import java.io.IOException // 입출력 오류
import java.time.LocalDateTime // 현재 시각
import java.time.format.DateTimeFormatter // 시각 서식
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 기본 현재 시각 문자열
 * 기능: PC stt_common.now_iso 와 같은 "yyyy-MM-ddTHH:mm:ss"(로컬 시각, 초 단위) 형식.
 * 입력: 없음
 * 출력: 없음
 * 리턴: 시각 문자열
 */
private fun localIsoNow(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) // 초 단위 로컬 시각

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 모델 로드 결과
 * 기능: 만든 엔진(만들지 못했으면 null), 준비 성공 시 PreparedModel, 실패 시 오류를 담는다.
 * 입력: engine(엔진 또는 null), model(준비된 모델 또는 null), error(로드 오류 또는 null)
 */
private class LoadResult(val engine: AsrEngine?, val model: PreparedModel?, val error: ErrorJson?)

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 배치 실행기
 * 기능: run(config) 이 돌려주는 Flow 를 수집하면 실행된다(수집 코루틴 취소 = 실행 취소). 같은 runDir 에 대해 동시에 두 번 실행하지 않는다는 전제.
 * 입력: dirs(앱 폴더), engineFactory(모델 → 엔진; ModelStore acquire/release 는 이 래퍼가 담당), vadFactory(계열 → VAD 엔진, 불가면 VadUnavailableException),
 *       samplerFactory(항목마다 새 ResourceSampler), env(환경 스냅샷), device(기기 정보), clock(단조 시계 ns), now(현재 시각 문자열)
 */
class BatchRunner(
    private val dirs: AppDirs, // 앱 폴더
    private val engineFactory: (ModelSpec) -> AsrEngine, // 엔진 생성
    private val vadFactory: (VadFamily) -> VadEngine, // VAD 엔진 생성
    private val samplerFactory: () -> ResourceSampler, // 샘플러 생성
    private val env: () -> EnvSnapshot?, // 환경 스냅샷
    private val device: DeviceInfo, // 기기 정보
    private val clock: () -> Long = System::nanoTime, // 단조 시계(ns)
    private val now: () -> String = ::localIsoNow, // 현재 시각
) {

    /**
     * 목적: 배치 실행
     * 기능: 파일 머리말의 순서대로 실행하며 이벤트를 내보낸다.
     * 입력: config(실행 설정)
     * 출력: 이어하기 manifest 를 읽을 수 없거나 설정이 다르면, 또는 새 실행인데 manifest 가 이미 있으면 IllegalStateException(결과를 건드리지 않음).
     *       결과 쓰기 실패가 이어지면 IOException. 수집 취소 시 CancellationException.
     * 리턴: RunEvent 흐름(Progress/ItemDone/Finished)
     */
    fun run(config: RunConfig): Flow<RunEvent> = flow {
        val runDir = dirs.runDir(config.runId) // 실행 폴더
        val state = RunState(runDir, config.runId, openManifest(config, runDir), now) // manifest 준비(실패 시 여기서 예외)
        val combosByModel = config.models.map { spec -> spec to config.presets.map { Combo(spec, it, config.maxChunkFor(spec), config.chunkMode) } } // 모델별 조합
        val outputs = ItemOutputs(runDir, config.runId, device, config.threads) // 결과 쓰기
        val processor = ItemProcessor(clock, now, env, samplerFactory) // 항목 처리기
        val vadPool = VadPool(vadFactory) // 계열별 VAD 엔진
        val cache = VadCache(runDir) // VAD 캐시
        try {
            state.log("RUN_START", "-", "-", "-", "resume=${config.resume} models=${config.models.size} presets=${config.presets.size} items=${config.items.size}") // 시작 로그
            val inFlight = state.manifest.inFlight // 이전 실행이 남긴 진행 중 항목
            if (inFlight != null) { // 크래시 흔적이 있으면
                val r = ResumeReconciler(runDir, outputs, processor).reconcile(inFlight, combosByModel.flatMap { it.second }, config.items) // 정리
                state.update { it.copy(inFlight = null) } // 표시 해제
                state.log("RECONCILE", r.combo?.name ?: "-", r.item?.let(::target) ?: inFlight, r.status ?: "-", r.message) // 정리 로그
                if (r.newResult && r.combo != null && r.item != null && r.status != null) { // 새 ERROR 결과를 썼으면
                    state.count(r.status) // 집계
                    emit(RunEvent.ItemDone(r.combo.name, r.item, r.status)) // 완료 이벤트
                }
            }
            recordUnsupported(config, combosByModel, state) // 미지원 조합 기록
            val work = combosByModel.map { (spec, combos) -> // 모델별 실행 목록(프리셋 → 파일 순)
                spec to combos.flatMap { c -> config.items.filter { it.engineLang in spec.languages }.map { c to it } } // 지원 언어만
            }
            val total = work.sumOf { it.second.size } // 전체 항목 수
            var done = 0 // 끝난 항목 수(건너뛴 항목 포함)
            for ((spec, pairs) in work) { // 모델 순서대로
                val pending = pairs.filterNot { (c, it) -> outputs.isDone(c, it) } // 남은 항목(이어하기 건너뛰기)
                done += pairs.size - pending.size // 이미 끝난 항목
                if (pending.isEmpty()) continue // 할 일이 없으면 모델을 로드하지 않음
                val load = loadModel(spec, config.threads, state) // 엔진 생성·준비
                try {
                    for ((combo, item) in pending) { // 프리셋 → 파일 순
                        emit(RunEvent.Progress(done, total, "${spec.algo} ${spec.model} / ${combo.preset.id} / ${target(item)}")) // 진행률
                        state.update { it.copy(inFlight = RunManifest.inFlightKey(combo.name, item)) } // 진행 중 기록(크래시 판정용)
                        state.log("ITEM_START", combo.name, target(item), "-", "") // 시작 로그
                        val detail = processItem(combo, item, load, vadPool, cache, processor) // 처리
                        val written = writeOutputs(outputs, processor, combo, item, detail, load) // txt → detail → CSV
                        state.update { it.copy(inFlight = null) } // 진행 중 해제
                        state.count(written.status) // 집계
                        state.log("ITEM_END", combo.name, target(item), written.status, written.error?.let { "${it.stage}/${it.code}: ${it.message}" } ?: "elapsed_sec=${written.elapsedSec}") // 종료 로그
                        done++ // 진행 수
                        emit(RunEvent.ItemDone(combo.name, item, written.status)) // 완료 이벤트
                    }
                } finally {
                    closeQuietly(load.engine) // 모델 단위 해제 보장
                }
            }
            state.update { it.copy(finishedAt = now()) } // 종료 기록
            val fin = state.finished() // 집계
            state.log("RUN_END", "-", "-", "-", "ok=${fin.ok} no_match=${fin.noMatch} error=${fin.error} unsupported=${fin.unsupported}") // 종료 로그
            emit(fin) // 종료 이벤트
        } catch (e: CancellationException) {
            try { state.update { it.copy(inFlight = null, finishedAt = null) } } catch (io: Exception) { /* 원래 취소를 우선 전파 */ } // 사용자 취소는 크래시가 아님
            state.log("RUN_CANCELLED", "-", "-", "-", "취소됨") // 취소 로그
            throw e // 취소 전파
        } finally {
            vadPool.close() // VAD 엔진 해제
        }
    }

    /**
     * 목적: 항목 표시 문자열
     * 기능: "lang/relDir/stem"(relDir 가 비면 "lang/stem").
     * 입력: item(입력 항목)
     * 출력: 없음
     * 리턴: 문자열
     */
    private fun target(item: InputItem): String = listOf(item.lang, item.relDir, item.stem).filter { it.isNotEmpty() }.joinToString("/") // 빈 조각 제외

    /**
     * 목적: 엔진 조용히 닫기
     * 기능: null 이 아니면 close 하고, 닫기 예외는 무시한다(결과는 이미 기록됨).
     * 입력: engine(엔진 또는 null)
     * 출력: 없음
     * 리턴: 없음
     */
    private fun closeQuietly(engine: AsrEngine?) {
        try { engine?.close() } catch (e: Exception) { /* 닫기 실패는 결과에 영향 없음 */ } // 해제
    }

    /**
     * 목적: manifest 열기
     * 기능: 새 실행이면 manifest 가 없는지 확인하고 새로 쓴다. 이어하기면 읽어서(실패 시 예외) runId·모델(id, 실효 maxChunk)·프리셋(id, params)·chunk 모드·스레드가
     *       같은지 확인하고 resume=true, finishedAt=null 로 다시 쓴다(inFlight 는 정리를 위해 유지).
     * 입력: config(실행 설정), runDir(실행 폴더)
     * 출력: 조건 위반 시 IllegalStateException(기존 파일은 건드리지 않음)
     * 리턴: 저장된 manifest
     */
    private fun openManifest(config: RunConfig, runDir: File): RunManifest {
        val file = OutputLayout.manifest(runDir) // run_manifest.json
        val fresh = RunManifest( // 이번 설정으로 만든 manifest
            runId = config.runId, appVersion = device.appVersion, startedAt = now(), device = DeviceInfoJson.from(device), // 식별·기기
            models = config.models.map { ModelEntry.from(it, config.maxChunkFor(it)) }, presets = config.presets.map(PresetEntry::from), // 모델·프리셋
            chunkMode = config.chunkMode.name, maxChunkOverride = config.maxChunkOverride, threads = config.threads, resume = config.resume, // 실행 옵션
            inputRoot = dirs.inputSound.absolutePath, unsupported = emptyList(), // 입력 루트·미지원
        )
        if (!config.resume) { // 새 실행
            check(!file.exists()) { "이미 run_manifest.json 이 있는 run 폴더에는 새 실행을 시작할 수 없습니다(이어하려면 resume=true): ${file.absolutePath}" } // 덮어쓰기 금지
            fresh.write(file) // 저장
            return fresh // 새 manifest
        }
        val old = RunManifest.read(file) // 기존 manifest
            ?: throw IllegalStateException("이어하기 실패: run_manifest.json 을 읽을 수 없습니다(없음·손상·스키마 불일치). 기존 결과 위에 새 실행을 시작하지 않습니다: ${file.absolutePath}") // 조용히 새로 시작하지 않음
        check(old.runId == config.runId) { "이어하기 실패: run_manifest.json 의 runId(${old.runId})가 설정(${config.runId})과 다릅니다" } // 실행 ID
        check(old.models.map { it.id to it.maxChunkSec } == fresh.models.map { it.id to it.maxChunkSec }) { "이어하기 실패: 모델 구성(순서·실효 maxChunkSec)이 이전 실행과 다릅니다" } // 모델
        check(old.presets.map { it.id to it.params } == fresh.presets.map { it.id to it.params }) { "이어하기 실패: VAD 프리셋 구성이 이전 실행과 다릅니다" } // 프리셋
        check(old.chunkMode == fresh.chunkMode && old.threads == fresh.threads) { "이어하기 실패: chunk 모드·스레드 수가 이전 실행과 다릅니다" } // 실행 옵션
        val resumed = old.copy(resume = true, finishedAt = null) // 이어하기 표시
        resumed.write(file) // 저장
        return resumed // 이어하기 manifest
    }

    /**
     * 목적: 언어 미지원 조합 기록
     * 기능: 입력 항목에 있는 언어 폴더마다, 모델 언어 집합에 그 엔진 언어가 없으면 그 모델의 모든 조합을 "combo|lang" 으로 manifest.unsupported 에 더한다(중복 없음).
     * 입력: config(실행 설정), combosByModel(모델별 조합), state(실행 상태)
     * 출력: manifest 저장
     * 리턴: 없음
     */
    private fun recordUnsupported(config: RunConfig, combosByModel: List<Pair<ModelSpec, List<Combo>>>, state: RunState) {
        val set = LinkedHashSet(state.manifest.unsupported) // 기존 기록(이어하기)
        val langs = config.items.map { it.lang }.distinct() // 입력 언어 폴더
        for ((spec, combos) in combosByModel) for (lang in langs) { // 모델 × 언어
            if (InputScanner.LANG_FOLDERS[lang] in spec.languages) continue // 지원하면 통과
            for (c in combos) set.add("${c.name}|$lang") // 미지원 조합 기록
        }
        if (set.toList() != state.manifest.unsupported) state.update { it.copy(unsupported = set.toList()) } // 바뀐 경우만 저장
    }

    /**
     * 목적: 모델 엔진 생성·준비
     * 기능: engineFactory → prepare(threads). 성공하면 manifest 모델 항목에 loadSec 기록, 실패하면 loadSec=null·loadError 기록 후 오류를 돌려준다
     *       (오류 stage 는 MODEL_LOAD 로 고정). 취소면 만든 엔진을 닫고 전파. 격리할 수 없는 Error 도 엔진을 닫고 전파.
     * 입력: spec(모델), threads(스레드 수), state(실행 상태)
     * 출력: 취소 시 CancellationException
     * 리턴: LoadResult(실패해도 엔진이 만들어졌으면 engine 포함 — 호출자가 close)
     */
    private suspend fun loadModel(spec: ModelSpec, threads: Int, state: RunState): LoadResult {
        var engine: AsrEngine? = null // 만든 엔진
        val modelName = "${spec.algo}_${spec.model}" // 로그용 모델 이름
        try {
            val e = engineFactory(spec) // 엔진 생성(ModelStore acquire 포함 가능)
            engine = e // 기록(실패 시 close 대상)
            val info = e.prepare(threads) // 모델 로드
            state.update { m -> m.copy(models = m.models.map { if (it.id == spec.id) it.copy(loadSec = info.loadSec, loadError = null) else it }) } // 로드 시간 기록
            state.log("MODEL_LOAD", modelName, "-", "OK", "load_sec=${info.loadSec} backend=${info.backend} precision=${info.precision} threads=${info.threads}") // 로드 로그
            return LoadResult(e, PreparedModel(e, info), null) // 성공
        } catch (c: CancellationException) {
            closeQuietly(engine) // 엔진 해제
            throw c // 취소 전파
        } catch (t: Throwable) {
            try { currentCoroutineContext().ensureActive() } catch (c: CancellationException) { closeQuietly(engine); throw c } // 취소 중이면 전파
            val err = RunErrors.toError(t, "MODEL_LOAD", "LOAD_FAILED")?.copy(stage = "MODEL_LOAD") // 오류 변환(stage 고정)
                ?: run { closeQuietly(engine); throw t } // 격리 불가 Error 는 전파
            state.update { m -> m.copy(models = m.models.map { if (it.id == spec.id) it.copy(loadSec = null, loadError = "${err.stage}/${err.code}: ${err.message}") else it }) } // 실패 기록
            state.log("MODEL_LOAD", modelName, "-", "ERROR", "${err.code}: ${err.message}") // 실패 로그
            return LoadResult(engine, null, err) // 실패
        }
    }

    /**
     * 목적: 항목 1건 처리 분기
     * 기능: 모델 로드 실패면 그 오류로, VAD 엔진을 만들 수 없으면 ERROR(VAD, VAD_UNAVAILABLE 등)로 detail 을 만들고, 아니면 ItemProcessor 로 처리한다.
     * 입력: combo(조합), item(입력 항목), load(모델 로드 결과), vadPool(VAD 엔진 보관소), cache(VAD 캐시), processor(처리기)
     * 출력: 취소 시 CancellationException
     * 리턴: DetailJson
     */
    private suspend fun processItem(combo: Combo, item: InputItem, load: LoadResult, vadPool: VadPool, cache: VadCache, processor: ItemProcessor): DetailJson {
        load.error?.let { return processor.failed(combo, item, it, null) } // 모델 로드 실패
        val model = load.model!! // 준비된 모델
        val vad = vadPool.get(combo.preset.family).getOrElse { t -> // VAD 엔진(실패 기억)
            val err = (RunErrors.toError(t, "VAD", "VAD_INIT_FAILED") ?: ErrorJson("VAD", "VAD_INIT_FAILED", t.toString())).copy(stage = "VAD") // 오류 변환
            return processor.failed(combo, item, err, model) // VAD 사용 불가
        }
        return processor.process(combo, item, model, vad, cache) // 정상 처리
    }

    /**
     * 목적: 결과 쓰기(쓰기 실패 격리)
     * 기능: outputs.write 가 IOException 으로 실패하면 ERROR(OUTPUT, WRITE_FAILED) 로 다시 쓴다. 그것도 실패하면 예외를 전파(실행 중단).
     * 입력: outputs(결과 쓰기), processor(처리기), combo(조합), item(입력 항목), detail(처리 결과), load(모델 로드 결과)
     * 출력: 두 번째 쓰기도 실패하면 IOException
     * 리턴: 실제로 기록된 DetailJson
     */
    private fun writeOutputs(outputs: ItemOutputs, processor: ItemProcessor, combo: Combo, item: InputItem, detail: DetailJson, load: LoadResult): DetailJson =
        try {
            outputs.write(combo, item, detail) // 정상 쓰기
            detail // 기록된 결과
        } catch (e: IOException) {
            val failed = processor.failed(combo, item, ErrorJson("OUTPUT", "WRITE_FAILED", "${e.javaClass.simpleName}: ${e.message}"), load.model) // 쓰기 오류
            outputs.write(combo, item, failed) // 오류로 다시 쓰기
            failed // 기록된 결과
        }
}
