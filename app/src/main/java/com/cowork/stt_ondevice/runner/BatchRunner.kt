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
//       - (change(add)-hyungchul-2026-10-11-02:14) 크래시 단계 표시: 모델 로드 직전 inFlight="<modelId>|MODEL_LOAD", VAD 계열 엔진 생성 직전 "<계열>|VAD_INIT"
//         (형식은 RunManifest.inFlight 주석). 재개 시 남아 있으면 ResumeReconciler 가 그 모델/계열의 남은 항목을 CRASH_OR_KILLED 로 기록하고 건너뛴다.
//       - 엔진·VAD close 실패, 취소 시 manifest 저장 실패는 실행을 멈추지 않되 로그(CLOSE_FAILED/CANCEL_SAVE_FAILED)에 남기고, 비정상 종료는 RUN_FAILED 로 남긴다.
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
    // change(add)-hyungchul-2026-10-11-02:14: 단계 표시(MODEL_LOAD/VAD_INIT) 정리·기록, 닫기·저장 실패 로그, RUN_FAILED 로그
    fun run(config: RunConfig): Flow<RunEvent> = flow {
        val runDir = dirs.runDir(config.runId) // 실행 폴더
        val state = RunState(runDir, config.runId, openManifest(config, runDir), now) // manifest 준비(실패 시 여기서 예외)
        val combosByModel = config.models.map { spec -> spec to config.presets.map { Combo(spec, it, config.maxChunkFor(spec), config.chunkMode) } } // 모델별 조합
        val work = combosByModel.map { (spec, combos) -> // 모델별 실행 목록(프리셋 → 파일 순)
            spec to combos.flatMap { c -> config.items.filter { it.engineLang in spec.languages }.map { c to it } } // 지원 언어만
        }
        val outputs = ItemOutputs(runDir, config.runId, device, config.threads) // 결과 쓰기
        val processor = ItemProcessor(clock, now, env, samplerFactory) // 항목 처리기
        val vadPool = VadPool(vadFactory) { family, e -> state.log("CLOSE_FAILED", family.name, "-", "ERROR", "VAD 엔진 close 실패: ${e.javaClass.simpleName}: ${e.message}") } // 계열별 VAD 엔진(닫기 실패는 기록)
        val cache = VadCache(runDir) // VAD 캐시
        try {
            state.log("RUN_START", "-", "-", "-", "resume=${config.resume} models=${config.models.size} presets=${config.presets.size} items=${config.items.size}") // 시작 로그
            val inFlight = state.manifest.inFlight // 이전 실행이 남긴 진행 단계 표시
            if (inFlight != null) { // 크래시 흔적이 있으면
                val r = ResumeReconciler(runDir, outputs, processor).reconcile(inFlight, work.flatMap { it.second }) // 정리
                val crashed = r.crashedModelId // 로드 중 죽은 모델
                state.update { m -> // 표시 해제(+모델 로드 크래시 기록)
                    m.copy(
                        inFlight = null, // 표시 해제
                        models = if (crashed == null) m.models else m.models.map { // 크래시 모델 항목 갱신
                            if (it.id == crashed) it.copy(loadSec = null, loadError = "MODEL_LOAD/CRASH_OR_KILLED: 이전 실행이 로드 중 비정상 종료") else it // 로드 오류 기록
                        },
                    )
                }
                state.log("RECONCILE", "-", r.target, r.status, r.message) // 정리 로그
                for ((c, it, st) in r.written) { // 새로 기록한 항목마다
                    state.count(st) // 집계
                    emit(RunEvent.ItemDone(c.name, it, st)) // 완료 이벤트
                }
            }
            recordUnsupported(config, combosByModel, state) // 미지원 조합 기록
            val total = work.sumOf { it.second.size } // 전체 항목 수
            var done = 0 // 끝난 항목 수(건너뛴 항목 포함)
            for ((spec, pairs) in work) { // 모델 순서대로
                val pending = pairs.filterNot { (c, it) -> outputs.isDone(c, it) } // 남은 항목(이어하기 건너뛰기)
                done += pairs.size - pending.size // 이미 끝난 항목
                if (pending.isEmpty()) continue // 할 일이 없으면 모델을 로드하지 않음(로드 크래시로 정리된 모델 포함)
                val load = loadModel(spec, config.threads, state) // 엔진 생성·준비(MODEL_LOAD 표시)
                try {
                    for ((combo, item) in pending) { // 프리셋 → 파일 순
                        emit(RunEvent.Progress(done, total, "${spec.algo} ${spec.model} / ${combo.preset.id} / ${target(item)}")) // 진행률
                        if (load.model != null) ensureVad(combo.preset.family, vadPool, state) // 계열 VAD 엔진 준비(VAD_INIT 표시)
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
                    closeAndLog(load.engine, "${spec.algo}_${spec.model}", state) // 모델 단위 해제 보장(실패는 기록)
                }
            }
            state.update { it.copy(finishedAt = now()) } // 종료 기록
            val fin = state.finished() // 집계
            state.log("RUN_END", "-", "-", "-", "ok=${fin.ok} no_match=${fin.noMatch} error=${fin.error} unsupported=${fin.unsupported}") // 종료 로그
            emit(fin) // 종료 이벤트
        } catch (e: CancellationException) {
            try {
                state.update { it.copy(inFlight = null, finishedAt = null) } // 사용자 취소는 크래시가 아님 → 표시 해제
            } catch (save: Exception) {
                state.log("CANCEL_SAVE_FAILED", "-", "-", "ERROR", "취소 시 manifest 저장 실패(inFlight 가 남아 재개 때 크래시로 처리될 수 있음): ${save.javaClass.simpleName}: ${save.message}") // 삼키되 기록
            }
            state.log("RUN_CANCELLED", "-", "-", "-", "취소됨") // 취소 로그
            throw e // 취소 전파
        } catch (t: Throwable) {
            state.log("RUN_FAILED", "-", "-", "ERROR", "실행 중단: ${t.javaClass.simpleName}: ${t.message}") // 비정상 종료 기록
            throw t // 전파
        } finally {
            vadPool.close() // VAD 엔진 해제(실패는 CLOSE_FAILED 로 기록)
        }
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /**
     * 목적: 계열 VAD 엔진 준비(크래시 표시 포함)
     * 기능: 이 계열을 아직 만들지 않았으면 inFlight 를 "<계열>|VAD_INIT" 로 저장한 뒤 vadPool.get 으로 만들고(실패도 기억) 표시를 지운다.
     *       생성 중 프로세스가 죽으면 재개 때 ResumeReconciler 가 그 계열 항목을 ERROR(VAD, CRASH_OR_KILLED)로 정리한다.
     * 입력: family(VAD 계열), vadPool(보관소), state(실행 상태)
     * 출력: manifest 저장
     * 리턴: 없음
     */
    private fun ensureVad(family: VadFamily, vadPool: VadPool, state: RunState) {
        if (vadPool.has(family)) return // 이미 시도함
        state.update { it.copy(inFlight = RunManifest.vadInitKey(family)) } // 생성 직전 표시
        val r = vadPool.get(family) // 생성(실패는 결과로 보관)
        state.update { it.copy(inFlight = null) } // 표시 해제
        state.log("VAD_INIT", family.name, "-", if (r.isSuccess) "OK" else "ERROR", r.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "") // 생성 로그
    }

    /**
     * 목적: 항목 표시 문자열
     * 기능: "lang/relDir/stem"(relDir 가 비면 "lang/stem").
     * 입력: item(입력 항목)
     * 출력: 없음
     * 리턴: 문자열
     */
    private fun target(item: InputItem): String = listOf(item.lang, item.relDir, item.stem).filter { it.isNotEmpty() }.joinToString("/") // 빈 조각 제외

    // change(add)-hyungchul-2026-10-11-02:14: 닫기 실패를 삼키되 로그에 남김(Task 27 에서 close 에 ModelStore.release 가 연결됨)
    /**
     * 목적: 엔진 닫기(실패 기록)
     * 기능: null 이 아니면 close 하고, 닫기 예외는 실행을 멈추지 않되 로그에 CLOSE_FAILED 줄(원인 포함)로 남긴다.
     * 입력: engine(엔진 또는 null), name(로그용 모델 이름), state(실행 상태)
     * 출력: 로그 추가
     * 리턴: 없음
     */
    private fun closeAndLog(engine: AsrEngine?, name: String, state: RunState) {
        try {
            engine?.close() // 해제
        } catch (e: Exception) {
            state.log("CLOSE_FAILED", name, "-", "ERROR", "엔진 close 실패: ${e.javaClass.simpleName}: ${e.message}") // 삼키되 기록
        }
    }

    /**
     * 목적: manifest 열기
     * 기능: 새 실행이면 manifest 가 없는지 확인하고 새로 쓴다. 이어하기면 읽어서(실패 시 예외) runId·앱 버전·모델(id 순서, 실효 maxChunk, runtimeVersion, precision,
     *       파일 hash)·프리셋(id, params)·chunk 모드·스레드가 같은지 확인하고 resume=true, finishedAt=null 로 다시 쓴다(inFlight 는 정리를 위해 유지).
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
        check(old.appVersion == fresh.appVersion) { "이어하기 실패: 앱 버전(${old.appVersion} → ${fresh.appVersion})이 이전 실행과 다릅니다" } // 앱 버전 // change(add)-hyungchul-2026-10-11-02:14
        check(old.models.map { it.id to it.maxChunkSec } == fresh.models.map { it.id to it.maxChunkSec }) { "이어하기 실패: 모델 구성(순서·실효 maxChunkSec)이 이전 실행과 다릅니다" } // 모델
        for ((o, n) in old.models.zip(fresh.models)) { // 모델마다 실행 환경·파일 비교 // change(add)-hyungchul-2026-10-11-02:14
            check(o.runtimeVersion == n.runtimeVersion && o.precision == n.precision) { "이어하기 실패: 모델 ${n.id} 의 런타임 버전/정밀도(${o.runtimeVersion}/${o.precision} → ${n.runtimeVersion}/${n.precision})가 다릅니다" } // 런타임·정밀도
            check(o.files == n.files) { "이어하기 실패: 모델 ${n.id} 의 파일 목록·SHA-256 이 이전 실행과 다릅니다" } // 파일 hash
        }
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
     * 기능: inFlight 를 "<modelId>|MODEL_LOAD" 로 저장한 뒤 engineFactory → prepare(threads). 성공하면 manifest 모델 항목에 loadSec 기록, 실패하면 loadSec=null·loadError 기록 후
     *       오류를 돌려준다(오류 stage 는 MODEL_LOAD 로 고정). 두 경우 모두 표시를 지운다. 로드 중 프로세스가 죽으면 표시가 남아 재개 때 그 모델 항목이
     *       ERROR(MODEL_LOAD, CRASH_OR_KILLED)로 정리된다(R33). 취소면 만든 엔진을 닫고 전파. 격리할 수 없는 Error 도 엔진을 닫고 전파(표시는 남김).
     * 입력: spec(모델), threads(스레드 수), state(실행 상태)
     * 출력: 취소 시 CancellationException
     * 리턴: LoadResult(실패해도 엔진이 만들어졌으면 engine 포함 — 호출자가 close)
     */
    private suspend fun loadModel(spec: ModelSpec, threads: Int, state: RunState): LoadResult {
        var engine: AsrEngine? = null // 만든 엔진
        val modelName = "${spec.algo}_${spec.model}" // 로그용 모델 이름
        state.update { it.copy(inFlight = RunManifest.modelLoadKey(spec.id)) } // 로드 직전 표시 // change(add)-hyungchul-2026-10-11-02:14
        try {
            val e = engineFactory(spec) // 엔진 생성(ModelStore acquire 포함 가능)
            engine = e // 기록(실패 시 close 대상)
            val info = e.prepare(threads) // 모델 로드
            state.update { m -> m.copy(inFlight = null, models = m.models.map { if (it.id == spec.id) it.copy(loadSec = info.loadSec, loadError = null) else it }) } // 로드 시간 기록·표시 해제
            state.log("MODEL_LOAD", modelName, "-", "OK", "load_sec=${info.loadSec} backend=${info.backend} precision=${info.precision} threads=${info.threads}") // 로드 로그
            return LoadResult(e, PreparedModel(e, info), null) // 성공
        } catch (c: CancellationException) {
            closeAndLog(engine, modelName, state) // 엔진 해제
            throw c // 취소 전파
        } catch (t: Throwable) {
            try { currentCoroutineContext().ensureActive() } catch (c: CancellationException) { closeAndLog(engine, modelName, state); throw c } // 취소 중이면 전파
            val err = RunErrors.toError(t, "MODEL_LOAD", "LOAD_FAILED")?.copy(stage = "MODEL_LOAD") // 오류 변환(stage 고정)
                ?: run { closeAndLog(engine, modelName, state); throw t } // 격리 불가 Error 는 전파
            state.update { m -> m.copy(inFlight = null, models = m.models.map { if (it.id == spec.id) it.copy(loadSec = null, loadError = "${err.stage}/${err.code}: ${err.message}") else it }) } // 실패 기록·표시 해제
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
     * 기능: outputs.write 가 IOException 으로 실패하면 ERROR(OUTPUT, WRITE_FAILED) 로 다시 쓴다. 다시 쓸 때는 찢긴 마지막 줄을 잘라내고 이 항목의 기존 행을 지운 뒤
     *       붙인다(첫 시도에서 일부 행이 써졌어도 중복·합쳐진 행이 생기지 않음). 그것도 실패하면 예외를 전파(실행 중단).
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
            outputs.write(combo, item, failed, replaceRows = true) // 오류로 다시 쓰기(기존 행 교체) // change(add)-hyungchul-2026-10-11-02:14
            failed // 기록된 결과
        }
}
