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
//       - (change(add)-hyungchul-2026-10-11-12:51, 최종 리뷰 수정)
//         I-1 재개 때 남은 inFlight 표시는 처음이면 다시 시도(manifest.inFlightAttempts[키]=1), 두 번째면 CRASH_OR_KILLED(ResumeReconciler).
//             그 단계가 프로세스가 죽지 않고 끝나면(모델 로드·VAD 생성·항목 쓰기 — 성공 또는 처리된 오류) 그 키의 횟수를 지운다.
//             rerunCrashed 모드(RunConfig)는 같은 run·같은 설정으로 CRASH_OR_KILLED 항목의 결과·CSV 행을 지우고 그 항목만 다시 실행한다(완료된 run 도 이 모드에서만 다시 열 수 있음).
//         I-4 실행 시작 때 modelHold(config) 로 선택한 ASR 모델 전부와 필요한 VAD 계열 모델 전부를 사용 중으로 표시하고, 끝(정상·실패·취소)에 해제한다
//             (엔진별 acquire/release 는 그대로 — 실행 중 어떤 선택 모델도 삭제·교체되지 않게).
//         I-3 재개 정리 전에 진행 이벤트("이전 실행 중단 정리 중")를 한 번 내보낸다.
//       - change(add)-hyungchul-2026-10-11-18:14 (2차 작업 1) 다시 실행 정리는 manifest.rerunCleanupPending=true 저장 → clearCrashed(CSV 행 먼저, 파일 나중) → false 저장 순서다.
//         정리 도중 죽은 run 은 일반 이어하기(rerunCrashed=false)로 열어도 이 표식이 true 이면 정리를 먼저 다시 수행한다(멱등).
//       - change(add)-hyungchul-2026-10-11-19:03 (2차 작업 4) 시스템 엔진 멈춤(EngineStalledException)이면 그 항목의 결과를 쓰지 않고 inFlight=null·그 키의 inFlightAttempts 제거·pausedReason 저장(finishedAt 은 null 유지),
//         로그 RUN_PAUSED, Paused 이벤트를 내고 오류 없이 끝낸다(Finished 없음 — 엔진·VAD 는 기존 finally 로 닫힘). 이어하기로 다시 열면 pausedReason 을 지우고 그 항목부터 진행한다.
//       - change(add)-hyungchul-2026-10-11-19:10 (2차 작업 5) 실행 시작 때(정리 이후) 카탈로그가 지원하는 모델 × 입력 언어마다 availability(spec, 엔진 언어)를 1회만 불러(run 안에서 재사용)
//         쓸 수 없는 조합은 실행·폴더 생성 없이 manifest.unsupported 에 "combo|lang" + unavailableReasons 에 사유를 기록한다(로그 LANG_UNAVAILABLE).
//         이어하기에서 이제 사용 가능해진 조합은 unsupported·unavailableReasons 에서 뺀다(unavailableReasons 에 키가 없는 카탈로그 언어 미지원 항목은 그대로 유지).
//       - change(add)-hyungchul-2026-10-11-21:50 (최종 리뷰 수정 Important 1) availability 는 종류 있는 결과(LangAvailability)를 돌려준다.
//         Unsupported(영구)만 위처럼 기록하고, Transient(상태 확인 시간 초과·확인 실패)인 (모델, 언어)는 기록 없이 이번 실행에서만 건너뛴다(로그 LANG_CHECK_FAILED, 다른 모델·언어는 계속).
//         루프가 끝났을 때 Transient 로 건너뛴 항목이 있으면 finishedAt 을 쓰지 않고 작업 4 의 일시정지처럼 끝낸다(pausedReason="<서비스> 상태 확인 실패(<사유>) — 강제 중지 후 이어하기",
//         로그 RUN_PAUSED, Paused 이벤트, Finished 없음). 이어하기에서 다시 확인해 사용 가능하면 그 항목이 실행된다.
//         남은(이어하기에서 끝나지 않은) 항목이 없는 (모델, 언어)에는 availability 를 부르지 않는다(불필요한 서비스 호출·시간 초과 방지).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.core.device.ResourceSampler // 자원 샘플러
import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더 구조
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.core.storage.InputScanner // 언어 폴더 매핑
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.LangAvailability // 실행 전 언어 확인 결과 종류 // change(add)-hyungchul-2026-10-11-21:50
import com.cowork.stt_ondevice.engine.system.EngineStalledException // 시스템 엔진 멈춤 예외 // change(add)-hyungchul-2026-10-11-19:03
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.output.DetailJson // 상세 JSON
import com.cowork.stt_ondevice.output.DeviceInfoJson // 기기 JSON
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.output.InputEntry // change(add)-hyungchul-2026-10-11-03:31 manifest 입력 항목
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
import kotlinx.coroutines.flow.FlowCollector // 이벤트 수집기(본 실행 함수 수신자) // change(add)-hyungchul-2026-10-11-12:51
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
 *       samplerFactory(항목마다 새 ResourceSampler), env(환경 스냅샷), device(기기 정보), clock(단조 시계 ns), now(현재 시각 문자열),
 *       modelHold(change(add)-hyungchul-2026-10-11-12:51 실행 설정 → 선택 모델 전체 사용 표시 핸들(close 로 해제). 앱: ModelStore.acquireAll(ASR 모델 + VAD 계열 모델). 기본은 아무것도 하지 않음),
 *       availability(change(add)-hyungchul-2026-10-11-19:10 (모델, 엔진 언어 코드 ko/en) → 사용 가능하면 null, 아니면 한국어 사유(예 "언어 미설치(en-US)"). 모델 × 언어마다 실행 시작 때 1회 호출. 기본은 항상 사용 가능. 실제 확인 함수는 작업 6 이 넣는다
 *                    change(add)-hyungchul-2026-10-11-21:50 → LangAvailability(Available / Unsupported(사유) / Transient(사유, 서비스 패키지, 서비스 이름)). 남은 항목이 있는 (모델, 언어)만 호출)
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
    private val modelHold: (RunConfig) -> AutoCloseable = { AutoCloseable { } }, // 실행 동안 모델 사용 표시 // change(add)-hyungchul-2026-10-11-12:51
    private val availability: suspend (ModelSpec, String) -> LangAvailability = { _, _ -> LangAvailability.Available }, // 언어 사용 가능 확인(종류 있는 결과) // change(add)-hyungchul-2026-10-11-19:10 // change(add)-hyungchul-2026-10-11-21:50 String? → LangAvailability
) {

    /**
     * 목적: 배치 실행
     * 기능: 파일 머리말의 순서대로 실행하며 이벤트를 내보낸다.
     * 입력: config(실행 설정)
     *       change(add)-hyungchul-2026-10-11-12:51 수집을 시작하면 먼저 modelHold(config) 로 선택 모델 전부를 사용 중으로 표시하고, 어떤 경로로 끝나든(정상·예외·취소) 해제한다.
     * 출력: 이어하기 manifest 를 읽을 수 없거나 설정이 다르면, 또는 새 실행인데 manifest 가 이미 있으면 IllegalStateException(결과를 건드리지 않음).
     *       change(add)-hyungchul-2026-10-11-19:03 시스템 엔진 멈춤은 예외 없이 Paused 이벤트 후 정상 종료한다.
     *       change(add)-hyungchul-2026-10-11-21:50 실행 전 언어 확인이 일시적으로 실패(Transient)한 조합이 있으면 나머지를 실행한 뒤 Paused 이벤트로 정상 종료한다.
     *       결과 쓰기 실패가 이어지면 IOException. 수집 취소 시 CancellationException.
     * 리턴: RunEvent 흐름(Progress/ItemDone/Finished, 엔진 멈춤·언어 확인 일시 실패면 Paused 로 끝남)
     */
    // change(add)-hyungchul-2026-10-11-02:14: 단계 표시(MODEL_LOAD/VAD_INIT) 정리·기록, 닫기·저장 실패 로그, RUN_FAILED 로그
    fun run(config: RunConfig): Flow<RunEvent> = flow {
        val hold = modelHold(config) // 선택 ASR·VAD 모델 전부 사용 중 표시(삭제·교체 차단) // change(add)-hyungchul-2026-10-11-12:51
        try {
            runBody(config) // 본 실행 // change(add)-hyungchul-2026-10-11-12:51
        } finally {
            hold.close() // 사용 표시 해제(정상·실패·취소 모두) // change(add)-hyungchul-2026-10-11-12:51
        }
    }

    // change(add)-hyungchul-2026-10-11-12:51: 기존 run 본문을 옮김(모델 사용 표시로 감싸기 위해)
    /**
     * 목적: 배치 실행 본문
     * 기능: run() 의 파일 머리말 순서대로 실행하며 이벤트를 내보낸다(manifest 준비 → inFlight 정리 → (rerunCrashed) 크래시 항목 지우기 → 모델·프리셋·파일 순 실행).
     * 입력: config(실행 설정)
     * 출력: run() 과 같음
     * 리턴: 없음
     */
    private suspend fun FlowCollector<RunEvent>.runBody(config: RunConfig) {
        val runDir = dirs.runDir(config.runId) // 실행 폴더
        val state = RunState(runDir, config.runId, openManifest(config, runDir), now) // manifest 준비(실패 시 여기서 예외)
        val combosByModel = config.models.map { spec -> spec to config.presets.map { Combo(spec, it, config.maxChunkFor(spec), config.chunkMode) } } // 모델별 조합
        val catalogWork = combosByModel.map { (spec, combos) -> // 모델별 실행 목록(프리셋 → 파일 순) // change(add)-hyungchul-2026-10-11-19:10 work → catalogWork (정리용: 카탈로그 지원 언어만, 언어 확인 전)
            spec to combos.flatMap { c -> config.items.filter { it.engineLang in spec.languages }.map { c to it } } // 지원 언어만
        }
        val outputs = ItemOutputs(runDir, config.runId, device, config.threads) // 결과 쓰기
        val processor = ItemProcessor(clock, now, env, samplerFactory) // 항목 처리기
        val vadPool = VadPool(vadFactory) { family, e -> state.log("CLOSE_FAILED", family.name, "-", "ERROR", "VAD 엔진 close 실패: ${e.javaClass.simpleName}: ${e.message}") } // 계열별 VAD 엔진(닫기 실패는 기록)
        val cache = VadCache(runDir) // VAD 캐시
        try {
            state.log("RUN_START", "-", "-", "-", "resume=${config.resume} rerunCrashed=${config.rerunCrashed} models=${config.models.size} presets=${config.presets.size} items=${config.items.size}") // 시작 로그 // change(add)-hyungchul-2026-10-11-12:51 rerunCrashed 표기
            val total = catalogWork.sumOf { it.second.size } // 정리 진행 표시용 항목 수(언어 확인 전) // change(add)-hyungchul-2026-10-11-12:51 정리 진행 표시에 쓰려고 위로 옮김 // change(add)-hyungchul-2026-10-11-19:10 catalogWork 기준
            val inFlight = state.manifest.inFlight // 이전 실행이 남긴 진행 단계 표시
            if (inFlight != null) { // 크래시 흔적이 있으면
                emit(RunEvent.Progress(0, total, "이전 실행 중단 정리 중… ($inFlight)")) // 정리 시작 알림(큰 정리는 시간이 걸릴 수 있음) // change(add)-hyungchul-2026-10-11-12:51
                val prior = state.manifest.inFlightAttempts[inFlight] ?: 0 // 이 표시를 이미 만난 횟수 // change(add)-hyungchul-2026-10-11-12:51
                val r = ResumeReconciler(runDir, outputs, processor).reconcile(inFlight, catalogWork.flatMap { it.second }, prior) // 정리(처음이면 다시 시도) // change(add)-hyungchul-2026-10-11-12:51 prior 전달
                val crashed = r.crashedModelId // 로드 중 죽은 모델
                state.update { m -> // 표시 해제(+모델 로드 크래시 기록)
                    m.copy(
                        inFlight = null, // 표시 해제
                        inFlightAttempts = if (r.retry) m.inFlightAttempts + (inFlight to prior + 1) else m.inFlightAttempts - inFlight, // 처음이면 횟수 +1, 정리 끝났으면 제거 // change(add)-hyungchul-2026-10-11-12:51
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
            val resumeCleanup = !config.rerunCrashed && state.manifest.rerunCleanupPending // 이전 "다시 실행 정리"가 도중에 죽었는지(일반 이어하기여도 정리를 먼저 끝냄) // change(add)-hyungchul-2026-10-11-18:14
            if (config.rerunCrashed || resumeCleanup) { // CRASH_OR_KILLED 항목 다시 실행 모드 또는 중단된 정리 마무리 // change(add)-hyungchul-2026-10-11-12:51 // change(add)-hyungchul-2026-10-11-18:14 조건 확장
                state.update { it.copy(rerunCleanupPending = true) } // 정리 시작 표식 저장(행 → 파일 순서 도중에 죽어도 다음 이어하기가 이어서 끝냄) // change(add)-hyungchul-2026-10-11-18:14
                val cleared = ResumeReconciler(runDir, outputs, processor).clearCrashed(catalogWork.flatMap { it.second }) // 크래시 항목 행 → 결과 파일 지우기(멱등)
                state.update { it.copy(rerunCleanupPending = false) } // 정리 끝 표식 해제 // change(add)-hyungchul-2026-10-11-18:14
                if (resumeCleanup) state.log("RERUN_CLEANUP_RESUMED", "-", "-", "-", "이전 '다시 실행' 정리가 도중에 끝나 CRASH_OR_KILLED 항목 ${cleared.size}개의 남은 ERROR.txt·detail·CSV 행을 마저 지우고 다시 실행") // 이어서 정리 기록 // change(add)-hyungchul-2026-10-11-18:14
                else state.log("RERUN_CRASHED", "-", "-", "-", "CRASH_OR_KILLED 항목 ${cleared.size}개의 ERROR.txt·detail·CSV 행을 지우고 다시 실행") // 기록
            }
            recordUnsupported(config, combosByModel, state) // 미지원 조합 기록
            val pendingLangs = catalogWork.flatMap { (spec, pairs) -> pairs.filterNot { (c, it) -> outputs.isDone(c, it) }.map { (_, it) -> spec.id to it.lang } }.toSet() // 남은 항목이 있는 (모델 id, 폴더명) // change(add)-hyungchul-2026-10-11-21:50
            val skipped = checkAvailability(config, combosByModel, pendingLangs, state) // 실행 전 언어 사용 가능 확인(정리 이후, 남은 항목이 있는 모델 × 언어 1회) // change(add)-hyungchul-2026-10-11-19:10 // change(add)-hyungchul-2026-10-11-21:50 종류별 결과·남은 항목 기준
            val work = catalogWork.map { (spec, pairs) -> spec to pairs.filterNot { (_, it) -> (spec.id to it.lang) in skipped } } // 쓸 수 없는(영구·일시) 언어 조합은 실행 목록에서 뺌 // change(add)-hyungchul-2026-10-11-19:10 // change(add)-hyungchul-2026-10-11-21:50 일시 실패 포함
            val runTotal = work.sumOf { it.second.size } // 실제 실행 목록의 전체 항목 수 // change(add)-hyungchul-2026-10-11-19:10
            var done = 0 // 끝난 항목 수(건너뛴 항목 포함)
            for ((spec, pairs) in work) { // 모델 순서대로
                val pending = pairs.filterNot { (c, it) -> outputs.isDone(c, it) } // 남은 항목(이어하기 건너뛰기)
                done += pairs.size - pending.size // 이미 끝난 항목
                if (pending.isEmpty()) continue // 할 일이 없으면 모델을 로드하지 않음(로드 크래시로 정리된 모델 포함)
                val load = loadModel(spec, config.threads, state) // 엔진 생성·준비(MODEL_LOAD 표시)
                try {
                    for ((combo, item) in pending) { // 프리셋 → 파일 순
                        emit(RunEvent.Progress(done, runTotal, "${spec.algo} ${spec.model} / ${combo.preset.id} / ${target(item)}")) // 진행률
                        if (load.model != null) ensureVad(combo.preset.family, vadPool, state) // 계열 VAD 엔진 준비(VAD_INIT 표시)
                        state.update { it.copy(inFlight = RunManifest.inFlightKey(combo.name, item)) } // 진행 중 기록(크래시 판정용)
                        state.log("ITEM_START", combo.name, target(item), "-", "") // 시작 로그
                        val key = RunManifest.inFlightKey(combo.name, item) // 이 항목 키 // change(add)-hyungchul-2026-10-11-12:51 // change(add)-hyungchul-2026-10-11-19:03 멈춤 처리에서도 쓰려고 위로 옮김
                        val detail = try {
                            processItem(combo, item, load, vadPool, cache, processor) // 처리
                        } catch (stall: EngineStalledException) { // 시스템 엔진이 재시도 후에도 응답하지 않음 → 오류로 끝내지 않고 일시정지 // change(add)-hyungchul-2026-10-11-19:03
                            val reason = stall.message ?: "${stall.kind.serviceLabel} 가 응답하지 않습니다" // 사유(메시지가 없으면 기본 문구)
                            state.update { it.copy(inFlight = null, inFlightAttempts = it.inFlightAttempts - key, pausedReason = reason) } // 항목 결과는 쓰지 않고 표시 해제·사유 기록(finishedAt 은 null 유지)
                            state.log("RUN_PAUSED", combo.name, target(item), "PAUSED", reason) // 일시정지 로그
                            emit(RunEvent.Paused(reason, stall.kind.servicePackage, stall.kind.serviceLabel)) // 일시정지 이벤트(마지막, Finished 없음)
                            return // 정상 반환 — 엔진·VAD 는 finally 로 닫힘
                        }
                        val written = writeOutputs(outputs, processor, combo, item, detail, load) // txt → detail → CSV
                        state.update { it.copy(inFlight = null, inFlightAttempts = it.inFlightAttempts - key) } // 진행 중 해제(+중단 횟수 제거) // change(add)-hyungchul-2026-10-11-12:51 횟수 제거
                        state.count(written.status) // 집계
                        state.log("ITEM_END", combo.name, target(item), written.status, written.error?.let { "${it.stage}/${it.code}: ${it.message}" } ?: "elapsed_sec=${written.elapsedSec}") // 종료 로그
                        done++ // 진행 수
                        emit(RunEvent.ItemDone(combo.name, item, written.status)) // 완료 이벤트
                    }
                } finally {
                    closeAndLog(load.engine, "${spec.algo}_${spec.model}", state) // 모델 단위 해제 보장(실패는 기록)
                }
            }
            val transient = skipped.values.filterIsInstance<LangAvailability.Transient>() // 일시적 확인 실패로 건너뛴 조합 // change(add)-hyungchul-2026-10-11-21:50
            if (transient.isNotEmpty()) { // 건너뛴 항목이 남아 있으면 완료가 아니라 일시정지 // change(add)-hyungchul-2026-10-11-21:50
                val first = transient.first() // 안내할 서비스(설정 열기 대상)
                val reason = transient.map { "${it.serviceLabel} 상태 확인 실패(${it.reason})" }.distinct().joinToString("; ") + " — 강제 중지 후 이어하기" // 일시정지 사유(서비스·사유별 1번)
                state.update { it.copy(pausedReason = reason) } // 사유 기록(finishedAt 은 null 유지 → 이어하기 대상)
                state.log("RUN_PAUSED", "-", "-", "PAUSED", reason) // 일시정지 로그
                emit(RunEvent.Paused(reason, first.servicePackage, first.serviceLabel)) // 일시정지 이벤트(마지막, Finished 없음)
                return // 정상 반환 — VAD 는 finally 로 닫힘(모델 엔진은 모델 단위 finally 에서 이미 닫힘)
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
        state.update { it.copy(inFlight = null, inFlightAttempts = it.inFlightAttempts - RunManifest.vadInitKey(family)) } // 표시 해제(+중단 횟수 제거 — 프로세스가 살아서 끝남) // change(add)-hyungchul-2026-10-11-12:51 횟수 제거
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
     *       change(add)-hyungchul-2026-10-11-03:31 기존 manifest 에 입력 목록(inputs)이 있으면 그것도 같아야 한다(없던 이전 파일은 검사하지 않음).
     *       change(add)-hyungchul-2026-10-11-12:51 이미 완료된(finishedAt 있음) run 은 rerunCrashed 모드일 때만 다시 연다(설정 일치 검사는 같음).
     *       change(add)-hyungchul-2026-10-11-19:03 이어하기로 열면 pausedReason 도 null 로 지운다(일시정지 해제).
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
            inputs = config.items.map(InputEntry::from), // change(add)-hyungchul-2026-10-11-03:31 입력 파일 목록(화면 이어하기 복원용)
        )
        if (!config.resume) { // 새 실행
            check(!file.exists()) { "이미 run_manifest.json 이 있는 run 폴더에는 새 실행을 시작할 수 없습니다(이어하려면 resume=true): ${file.absolutePath}" } // 덮어쓰기 금지
            fresh.write(file) // 저장
            return fresh // 새 manifest
        }
        val old = RunManifest.read(file) // 기존 manifest
            ?: throw IllegalStateException("이어하기 실패: run_manifest.json 을 읽을 수 없습니다(없음·손상·스키마 불일치). 기존 결과 위에 새 실행을 시작하지 않습니다: ${file.absolutePath}") // 조용히 새로 시작하지 않음
        check(old.runId == config.runId) { "이어하기 실패: run_manifest.json 의 runId(${old.runId})가 설정(${config.runId})과 다릅니다" } // 실행 ID
        check(old.finishedAt == null || config.rerunCrashed) { "이어하기 실패: 이미 완료된 실행입니다(${old.finishedAt}) — 완료된 실행은 'CRASH_OR_KILLED 항목 다시 실행'으로만 다시 열 수 있습니다" } // 완료 run 은 다시 실행 모드만 // change(add)-hyungchul-2026-10-11-12:51
        check(old.appVersion == fresh.appVersion) { "이어하기 실패: 앱 버전(${old.appVersion} → ${fresh.appVersion})이 이전 실행과 다릅니다" } // 앱 버전 // change(add)-hyungchul-2026-10-11-02:14
        check(old.models.map { it.id to it.maxChunkSec } == fresh.models.map { it.id to it.maxChunkSec }) { "이어하기 실패: 모델 구성(순서·실효 maxChunkSec)이 이전 실행과 다릅니다" } // 모델
        for ((o, n) in old.models.zip(fresh.models)) { // 모델마다 실행 환경·파일 비교 // change(add)-hyungchul-2026-10-11-02:14
            check(o.runtimeVersion == n.runtimeVersion && o.precision == n.precision) { "이어하기 실패: 모델 ${n.id} 의 런타임 버전/정밀도(${o.runtimeVersion}/${o.precision} → ${n.runtimeVersion}/${n.precision})가 다릅니다" } // 런타임·정밀도
            check(o.files == n.files) { "이어하기 실패: 모델 ${n.id} 의 파일 목록·SHA-256 이 이전 실행과 다릅니다" } // 파일 hash
        }
        check(old.presets.map { it.id to it.params } == fresh.presets.map { it.id to it.params }) { "이어하기 실패: VAD 프리셋 구성이 이전 실행과 다릅니다" } // 프리셋
        check(old.chunkMode == fresh.chunkMode && old.threads == fresh.threads) { "이어하기 실패: chunk 모드·스레드 수가 이전 실행과 다릅니다" } // 실행 옵션
        if (old.inputs != null) check(old.inputs == fresh.inputs) { "이어하기 실패: 입력 파일 구성(목록·순서)이 이전 실행과 다릅니다" } // change(add)-hyungchul-2026-10-11-03:31 입력 구성(기록된 경우만 — 이전 파일은 null)
        val resumed = old.copy(resume = true, finishedAt = null, pausedReason = null) // 이어하기 표시 // change(add)-hyungchul-2026-10-11-19:03 일시정지 사유 지움
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

    // change(add)-hyungchul-2026-10-11-19:10
    /**
     * 목적: 실행 전 언어 사용 가능 확인·기록
     * 기능: 카탈로그가 지원하는 모델 × 입력 언어(폴더명 kr/en)마다 availability(spec, 엔진 언어 ko/en)를 정확히 1회 부른다(결과는 이 run 안에서 재사용).
     *       사유가 있으면 그 모델의 모든 조합을 "combo|lang" 으로 manifest.unsupported 에 더하고 unavailableReasons 에 사유를 기록한다(로그 LANG_UNAVAILABLE).
     *       사용 가능하면 그 키가 unavailableReasons 에 있을 때만(= 이전 확인에서 불가로 기록됐던 조합) unsupported·unavailableReasons 에서 뺀다
     *       (카탈로그 언어 미지원으로 들어간 항목은 unavailableReasons 에 키가 없어 그대로 남음). 바뀐 경우에만 manifest 를 저장한다.
     *       change(add)-hyungchul-2026-10-11-21:50 (Important 1) 남은 항목이 없는 (모델, 언어)는 부르지 않는다(pendingLangs 에 없으면 건너뜀 — 기존 기록도 그대로).
     *       위의 "사유가 있으면" 은 Unsupported(영구)만이다. Transient(일시)는 unsupported·unavailableReasons 를 건드리지 않고(기존 기록 유지) 로그 LANG_CHECK_FAILED 만 남긴다.
     * 입력: config(실행 설정), combosByModel(모델별 조합), pendingLangs(change(add)-hyungchul-2026-10-11-21:50 남은 항목이 있는 (모델 id, 폴더명) 집합), state(실행 상태)
     * 출력: manifest 저장, 로그. 취소 시 CancellationException, availability 예외는 그대로 전파(조용히 가능으로 간주하지 않음)
     * 리턴: change(add)-hyungchul-2026-10-11-21:50 이번 실행에서 건너뛸 (모델 id, 입력 언어 폴더명) → 결과(Unsupported 또는 Transient) 맵(이전: 쓸 수 없는 쌍의 집합)
     */
    private suspend fun checkAvailability(config: RunConfig, combosByModel: List<Pair<ModelSpec, List<Combo>>>, pendingLangs: Set<Pair<String, String>>, state: RunState): Map<Pair<String, String>, LangAvailability> { // change(add)-hyungchul-2026-10-11-21:50 pendingLangs·반환 형식
        val unsupported = LinkedHashSet(state.manifest.unsupported) // 기존 기록(이어하기)
        val reasons = LinkedHashMap(state.manifest.unavailableReasons) // 기존 사유(이어하기)
        val skipped = LinkedHashMap<Pair<String, String>, LangAvailability>() // 이번에 건너뛸 (모델 id, 폴더명) → 결과 // change(add)-hyungchul-2026-10-11-21:50
        val langs = config.items.map { it.lang }.distinct() // 입력 언어 폴더
        for ((spec, combos) in combosByModel) for (lang in langs) { // 모델 × 언어(각 1회)
            val engineLang = InputScanner.LANG_FOLDERS[lang] ?: continue // 알 수 없는 폴더는 건너뜀(InputItem.engineLang 이 이미 거부)
            if (engineLang !in spec.languages) continue // 카탈로그 미지원은 recordUnsupported 가 처리(확인 불필요)
            if ((spec.id to lang) !in pendingLangs) continue // 남은 항목이 없으면 확인하지 않음(서비스 호출·시간 초과 방지) // change(add)-hyungchul-2026-10-11-21:50
            val result = availability(spec, engineLang) // 사용 가능 확인(종류 있는 결과) // change(add)-hyungchul-2026-10-11-21:50
            if (result is LangAvailability.Transient) { // 일시적 확인 실패 → 기록하지 않고 이번 실행에서만 건너뜀 // change(add)-hyungchul-2026-10-11-21:50
                skipped[spec.id to lang] = result // 건너뛸 대상(끝에 일시정지)
                state.log("LANG_CHECK_FAILED", "${spec.algo}_${spec.model}", lang, "SKIPPED", "${result.reason} — 이번 실행에서 건너뛰고 일시정지(이어하기에서 다시 확인)") // 로그
                continue // unsupported·unavailableReasons 는 건드리지 않음
            }
            val reason = (result as? LangAvailability.Unsupported)?.reason // 영구 사유(사용 가능이면 null) // change(add)-hyungchul-2026-10-11-21:50
            for (c in combos) { // 그 모델의 조합마다
                val key = "${c.name}|$lang" // manifest 키
                if (reason != null) { // 쓸 수 없음
                    unsupported.add(key) // 미지원 기록
                    reasons[key] = reason // 사유 기록
                } else if (key in reasons) { // 이제 사용 가능해짐(이전에 불가로 기록된 조합)
                    unsupported.remove(key) // 미지원에서 뺌
                    reasons.remove(key) // 사유 지움
                }
            }
            if (reason != null) { // 쓸 수 없는 조합
                skipped[spec.id to lang] = result // 실행 목록에서 뺄 대상 // change(add)-hyungchul-2026-10-11-21:50
                state.log("LANG_UNAVAILABLE", "${spec.algo}_${spec.model}", lang, "UNSUPPORTED", reason) // 로그
            }
        }
        if (unsupported.toList() != state.manifest.unsupported || reasons != state.manifest.unavailableReasons) { // 바뀐 경우만 저장
            state.update { it.copy(unsupported = unsupported.toList(), unavailableReasons = reasons.toMap()) } // 저장
        }
        return skipped // 건너뛸 쌍과 결과 // change(add)-hyungchul-2026-10-11-21:50
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
            state.update { m -> m.copy(inFlight = null, inFlightAttempts = m.inFlightAttempts - RunManifest.modelLoadKey(spec.id), models = m.models.map { if (it.id == spec.id) it.copy(loadSec = info.loadSec, loadError = null) else it }) } // 로드 시간 기록·표시 해제(+중단 횟수 제거) // change(add)-hyungchul-2026-10-11-12:51 횟수 제거
            state.log("MODEL_LOAD", modelName, "-", "OK", "load_sec=${info.loadSec} backend=${info.backend} precision=${info.precision} threads=${info.threads}") // 로드 로그
            return LoadResult(e, PreparedModel(e, info), null) // 성공
        } catch (c: CancellationException) {
            closeAndLog(engine, modelName, state) // 엔진 해제
            throw c // 취소 전파
        } catch (t: Throwable) {
            try { currentCoroutineContext().ensureActive() } catch (c: CancellationException) { closeAndLog(engine, modelName, state); throw c } // 취소 중이면 전파
            val err = RunErrors.toError(t, "MODEL_LOAD", "LOAD_FAILED")?.copy(stage = "MODEL_LOAD") // 오류 변환(stage 고정)
                ?: run { closeAndLog(engine, modelName, state); throw t } // 격리 불가 Error 는 전파
            state.update { m -> m.copy(inFlight = null, inFlightAttempts = m.inFlightAttempts - RunManifest.modelLoadKey(spec.id), models = m.models.map { if (it.id == spec.id) it.copy(loadSec = null, loadError = "${err.stage}/${err.code}: ${err.message}") else it }) } // 실패 기록·표시 해제(+중단 횟수 제거 — 처리된 오류) // change(add)-hyungchul-2026-10-11-12:51 횟수 제거
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
