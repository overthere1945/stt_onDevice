// 파일명: ResumeReconciler.kt
// 목적: 이어하기(resume) 시작 시 manifest.inFlight 에 남은 진행 단계 표시(이전 실행이 그 단계에서 죽은 흔적)를 정리한다 (R10, R33)
// 기능: 표시 3종(RunManifest.inFlight 주석 참고)에 따라
//   [항목 "combo|lang|relDir|stem"] 결과 쓰기 순서가 txt/ERROR.txt(원자적) → detail(원자적) → CSV 행 → inFlight 해제 이므로, 죽은 시점에 따라
//       ① 완료 표시(txt 또는 ERROR.txt)와 그에 맞는 detail 이 있으면 → 결과를 그대로 두고 빠진 CSV 행만 detail 로 복구(중복 추가 없음)
//       ② 그 밖(완료 표시 없음, 또는 detail 이 없거나 깨져 행을 복구할 수 없음) → 남은 부분 결과·기존 행을 지우고 ERROR(TRANSCRIBE, CRASH_OR_KILLED) 기록
//       어느 경우든 먼저 그 조합 CSV·요약 CSV 의 반쯤 쓰인 마지막 줄을 잘라낸다. ②는 같은 항목을 다시 돌리지 않는다(네이티브 크래시 반복 방지).
//   [모델 로드 "<modelId>|MODEL_LOAD"] 그 모델의 아직 기록 안 된 모든 항목을 ERROR(MODEL_LOAD, CRASH_OR_KILLED)로 기록 → 이후 그 모델은 로드하지 않는다.
//   [VAD 생성 "<계열>|VAD_INIT"] 그 계열의 모든 프리셋 항목(모든 모델)을 ERROR(VAD, CRASH_OR_KILLED)로 기록 → 이후 그 계열 엔진은 만들지 않는다.
//   change(add)-hyungchul-2026-10-11-12:51 (최종 리뷰 I-1) 위의 ERROR 기록(②·모델 로드·VAD 생성)은 같은 키를 "두 번째로" 만났을 때만 한다.
//       처음(manifest.inFlightAttempts[키] 가 0)이면 ERROR 를 쓰지 않고 다시 시도하게 둔다(retry=true): 항목은 남은 부분 결과·행을 지워 미완료로 되돌리고,
//       모델 로드·VAD 생성은 아무것도 기록하지 않아 본 실행 루프가 정상적으로 다시 로드·생성한다. 횟수 증가·삭제는 BatchRunner 가 manifest 에 저장한다.
//       한 번의 OS 종료·강제 종료·배터리 방전·adb install -r 로 항목(또는 모델 하나의 모든 항목)이 영구 ERROR 가 되던 문제를 막고, 같은 단계에서 두 번 죽으면 R33 대로 건너뛴다.
//   change(add)-hyungchul-2026-10-11-12:51 (최종 리뷰 I-3) 여러 항목을 ERROR 로 기록할 때(markAll) CSV 찢긴 줄 정리를 한 번만 하고 행은 replaceRows=false 로 붙인다
//       (대상은 완료 표시가 없는 항목이라 기존 행이 있을 수 없음 — 항목마다 요약 CSV 전체를 다시 읽던 O(n²) 제거).
//   change(add)-hyungchul-2026-10-11-12:51 (최종 리뷰 I-1) clearCrashed: "CRASH_OR_KILLED 항목 다시 실행" 모드에서 그 항목들의 ERROR.txt·detail·CSV 행을 지운다.
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.RunManifest // 단계 표시 상수
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: inFlight 정리 결과
 * 기능: 이번 정리로 새로 기록한 항목, 모델 로드 크래시면 그 모델 id, 로그용 문구를 담는다.
 * 입력: written(새로 기록한 (조합, 항목, 상태) 목록), crashedModelId(MODEL_LOAD 크래시 모델 id, 아니면 null),
 *       target(로그 대상 문자열), status(로그 상태), message(로그 문구),
 *       retry(change(add)-hyungchul-2026-10-11-12:51 처음 만난 표시라 ERROR 를 쓰지 않고 그 단계를 다시 시도하게 뒀으면 true — 호출자가 횟수를 1 올림)
 */
// change(add)-hyungchul-2026-10-11-02:14: 여러 항목·모델 크래시를 담도록 필드 변경
internal data class ReconcileOutcome(
    val written: List<Triple<Combo, InputItem, String>>, // 새로 기록한 항목
    val crashedModelId: String?, // 로드 중 죽은 모델 id
    val target: String, // 로그 대상
    val status: String, // 로그 상태
    val message: String, // 설명
    val retry: Boolean = false, // 다시 시도로 둠 // change(add)-hyungchul-2026-10-11-12:51
)

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 재개 시 진행 단계 표시 정리기
 * 기능: reconcile()(inFlight 정리)과 change(add)-hyungchul-2026-10-11-12:51 clearCrashed()(크래시 항목 다시 실행 준비)를 제공한다.
 * 입력: runDir(실행 폴더), outputs(결과 쓰기 도우미), processor(오류 detail 생성)
 */
internal class ResumeReconciler(
    private val runDir: File, // 실행 폴더
    private val outputs: ItemOutputs, // 결과 쓰기
    private val processor: ItemProcessor, // detail 생성
) {

    /**
     * 목적: inFlight 표시 정리
     * 기능: 키 모양으로 종류를 가려 파일 머리말 규칙대로 정리한다. "x|MODEL_LOAD"/"x|VAD_INIT" 는 2조각, 항목 키는 4조각.
     *       change(add)-hyungchul-2026-10-11-12:51 priorAttempts 가 0 이면 ERROR 대신 다시 시도(retry=true), 1 이상이면 ERROR 기록.
     * 입력: key(manifest.inFlight), pairs(이번 설정에서 실행 대상인 (조합, 항목) 전체 — 언어 지원 조합만),
     *       priorAttempts(change(add)-hyungchul-2026-10-11-12:51 manifest.inFlightAttempts[key] — 이 키를 재개 때 이미 만난 횟수, 기본 0)
     * 출력: 결과 파일 생성/수정, 쓰기 실패 시 IOException
     * 리턴: ReconcileOutcome
     */
    // change(add)-hyungchul-2026-10-11-02:14: 단계 표시 3종 처리(R33)
    fun reconcile(key: String, pairs: List<Pair<Combo, InputItem>>, priorAttempts: Int = 0): ReconcileOutcome { // change(add)-hyungchul-2026-10-11-12:51 priorAttempts
        val parts = key.split('|') // 조각
        if (parts.size == 2 && parts[1] == RunManifest.MODEL_LOAD_MARK) return modelLoadCrash(parts[0], pairs, priorAttempts) // 모델 로드 중 크래시
        if (parts.size == 2 && parts[1] == RunManifest.VAD_INIT_MARK) return vadInitCrash(parts[0], pairs, priorAttempts) // VAD 생성 중 크래시
        return itemCrash(key, pairs, priorAttempts) // 항목 처리 중 크래시
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /**
     * 목적: 모델 로드 중 크래시 정리
     * 기능: change(add)-hyungchul-2026-10-11-12:51 처음 만난 표시면 아무것도 기록하지 않고 다시 로드하게 둔다(retry).
     *       두 번째면 그 모델의 아직 기록 안 된(txt/ERROR.txt 없음) 모든 항목을 ERROR(MODEL_LOAD, CRASH_OR_KILLED)로 기록한다.
     * 입력: modelId(모델 id), pairs(실행 대상 전체), priorAttempts(이 키를 이미 만난 횟수)
     * 출력: 결과 파일 생성
     * 리턴: ReconcileOutcome(두 번째면 crashedModelId = modelId)
     */
    private fun modelLoadCrash(modelId: String, pairs: List<Pair<Combo, InputItem>>, priorAttempts: Int): ReconcileOutcome {
        if (priorAttempts == 0) { // change(add)-hyungchul-2026-10-11-12:51 처음 중단 → 다시 시도
            return ReconcileOutcome(emptyList(), null, modelId, "RETRY", "모델 로드 중 중단(1회째, OS 종료·강제 종료 가능): 이 모델을 다시 로드함 — 같은 단계에서 또 중단되면 CRASH_OR_KILLED 로 기록", retry = true) // 다시 시도
        }
        val err = ErrorJson("MODEL_LOAD", ItemOutputs.CRASH_CODE, "이전 실행이 이 모델을 로드하던 중 두 번 끝나지 않음(네이티브 크래시·OOM·강제 종료 추정) — 이 모델은 다시 로드하지 않음") // 오류 // change(add)-hyungchul-2026-10-11-12:51 "두 번" 문구
        val written = markAll(pairs.filter { it.first.spec.id == modelId }, err) // 남은 항목 기록
        return ReconcileOutcome(written, modelId, modelId, "ERROR", "모델 로드 중 크래시(2회째): 남은 항목 ${written.size}개를 ERROR(MODEL_LOAD, CRASH_OR_KILLED)로 기록") // 결과
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /**
     * 목적: VAD 엔진 생성 중 크래시 정리
     * 기능: change(add)-hyungchul-2026-10-11-12:51 처음 만난 표시면 아무것도 기록하지 않고 다시 만들게 둔다(retry).
     *       두 번째면 그 계열 프리셋을 쓰는 모든 모델의 아직 기록 안 된 항목을 ERROR(VAD, CRASH_OR_KILLED)로 기록한다.
     * 입력: familyName(VadFamily 이름), pairs(실행 대상 전체), priorAttempts(이 키를 이미 만난 횟수)
     * 출력: 결과 파일 생성
     * 리턴: ReconcileOutcome
     */
    private fun vadInitCrash(familyName: String, pairs: List<Pair<Combo, InputItem>>, priorAttempts: Int): ReconcileOutcome {
        if (priorAttempts == 0) { // change(add)-hyungchul-2026-10-11-12:51 처음 중단 → 다시 시도
            return ReconcileOutcome(emptyList(), null, familyName, "RETRY", "VAD 생성 중 중단(1회째, OS 종료·강제 종료 가능): 이 계열 엔진을 다시 만듦 — 같은 단계에서 또 중단되면 CRASH_OR_KILLED 로 기록", retry = true) // 다시 시도
        }
        val err = ErrorJson("VAD", ItemOutputs.CRASH_CODE, "이전 실행이 $familyName VAD 엔진을 만들던 중 두 번 끝나지 않음(네이티브 크래시·OOM·강제 종료 추정) — 이 계열은 다시 만들지 않음") // 오류 // change(add)-hyungchul-2026-10-11-12:51 "두 번" 문구
        val written = markAll(pairs.filter { it.first.preset.family.name == familyName }, err) // 남은 항목 기록
        return ReconcileOutcome(written, null, familyName, "ERROR", "VAD 생성 중 크래시(2회째): 남은 항목 ${written.size}개를 ERROR(VAD, CRASH_OR_KILLED)로 기록") // 결과
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /**
     * 목적: 여러 항목을 같은 오류로 기록
     * 기능: 아직 완료 표시가 없는 항목만 ERROR 결과(ERROR.txt → detail → CSV)로 쓴다.
     *       change(add)-hyungchul-2026-10-11-12:51 완료 표시가 없는 항목은 CSV 행도 있을 수 없으므로(행은 완료 표시 뒤에 씀) 기존 행 제거를 하지 않고,
     *       CSV 찢긴 줄 정리는 쓰기 전에 한 번만 한다(항목 수 × 요약 CSV 전체 읽기 제거, 최종 리뷰 I-3).
     * 입력: targets(대상 (조합, 항목)), err(오류)
     * 출력: 결과 파일 생성
     * 리턴: 새로 기록한 (조합, 항목, "ERROR") 목록
     */
    private fun markAll(targets: List<Pair<Combo, InputItem>>, err: ErrorJson): List<Triple<Combo, InputItem, String>> {
        val todo = targets.filterNot { (c, it) -> outputs.isDone(c, it) } // 남은 항목 // change(add)-hyungchul-2026-10-11-12:51
        outputs.truncateTornTails(todo) // 찢긴 줄 정리(CSV 마다 1번) // change(add)-hyungchul-2026-10-11-12:51
        return todo.map { (c, it) -> // 남은 항목마다
            outputs.write(c, it, processor.failed(c, it, err, null), replaceRows = false) // 오류 기록(행 추가만) // change(add)-hyungchul-2026-10-11-12:51 replaceRows=false
            Triple(c, it, "ERROR") // 기록 결과
        }
    }

    /**
     * 목적: 항목 처리 중 크래시 정리
     * 기능: 키("combo|lang|relDir|stem")로 조합·항목을 찾고, 파일 머리말의 ①/② 규칙으로 정리한다.
     *       change(add)-hyungchul-2026-10-11-12:51 ②에서 처음 만난 표시면 ERROR 대신 남은 부분 결과·행을 지워 다시 시도하게 둔다(retry).
     * 입력: key(항목 키), pairs(실행 대상 전체), priorAttempts(change(add)-hyungchul-2026-10-11-12:51 이 키를 이미 만난 횟수)
     * 출력: 결과 파일 생성/수정, 쓰기 실패 시 IOException
     * 리턴: ReconcileOutcome
     */
    private fun itemCrash(key: String, pairs: List<Pair<Combo, InputItem>>, priorAttempts: Int): ReconcileOutcome {
        val parts = key.split('|', limit = 4) // combo|lang|relDir|stem
        val found = pairs.firstOrNull { (c, it) -> // 조합·항목 찾기
            c.name == parts.getOrNull(0) && it.lang == parts.getOrNull(1) && it.relDir == parts.getOrNull(2) && it.stem == parts.getOrNull(3) // 모든 조각 일치
        } ?: return ReconcileOutcome(emptyList(), null, key, "-", "inFlight 항목을 현재 설정에서 찾지 못해 표시만 지움: $key") // 설정 불일치
        val (combo, item) = found // 분해
        val target = "${combo.name} ${item.lang}/${item.relDir}/${item.stem}" // 로그 대상
        CsvFiles.truncatePartialLastLine(OutputLayout.resultCsv(runDir, item.lang, combo.name)) // 조합 CSV 찢긴 줄 제거
        CsvFiles.truncatePartialLastLine(outputs.summaryFile()) // 요약 CSV 찢긴 줄 제거
        val txt = OutputLayout.transcript(runDir, item, combo.name) // txt
        val err = OutputLayout.errorFile(runDir, item, combo.name) // ERROR.txt
        val detail = outputs.readDetail(combo, item) // detail(없거나 깨지면 null)
        val consistent = detail != null && ( // 완료 표시와 detail 이 서로 맞는지
            (txt.isFile && detail.status != "ERROR") || (err.isFile && detail.status == "ERROR") // txt↔정상, ERROR.txt↔오류
            )
        if (consistent) { // ① 결과 유지
            val needCombo = !outputs.hasCsvRow(combo, item) // 조합 CSV 행 누락 여부
            val needSummary = !outputs.hasSummaryRow(combo, item) // 요약 CSV 행 누락 여부
            if (needCombo || needSummary) outputs.appendCsvRows(combo, item, detail, needCombo, needSummary) // 빠진 행만 detail 로 복구
            return ReconcileOutcome(emptyList(), null, target, detail.status, "완료된 결과 유지(CSV 행 복구: 조합=$needCombo, 요약=$needSummary)") // 결과
        }
        if (priorAttempts == 0) { // change(add)-hyungchul-2026-10-11-12:51 처음 중단 → 부분 결과를 지우고 다시 시도
            outputs.discard(combo, item) // txt·ERROR.txt·detail·CSV 행 제거(미완료로 되돌림)
            return ReconcileOutcome(emptyList(), null, target, "RETRY", "항목 처리 중 중단(1회째, OS 종료·강제 종료 가능): 부분 결과를 지우고 다시 실행 — 같은 항목에서 또 중단되면 CRASH_OR_KILLED 로 기록", retry = true) // 다시 시도
        }
        txt.delete() // ② 남은 부분 결과 제거
        err.delete() // ② 남은 부분 결과 제거
        val crash = ErrorJson("TRANSCRIBE", ItemOutputs.CRASH_CODE, "이전 실행이 이 항목을 처리하던 중 두 번 끝나지 않음(네이티브 크래시·OOM·강제 종료 추정)") // 크래시 오류 // change(add)-hyungchul-2026-10-11-12:51 "두 번" 문구
        outputs.write(combo, item, processor.failed(combo, item, crash, null), replaceRows = true) // ERROR.txt → detail → (기존 행 제거 후) CSV 행 // change(add)-hyungchul-2026-10-11-02:14: 중복·상충 행 방지
        return ReconcileOutcome(listOf(Triple(combo, item, "ERROR")), null, target, "ERROR", "크래시 항목(2회째)을 ERROR(CRASH_OR_KILLED)로 기록") // 결과 // change(add)-hyungchul-2026-10-11-12:51 2회째 표기
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: "CRASH_OR_KILLED 항목 다시 실행" 준비(최종 리뷰 I-1)
     * 기능: pairs 중 ERROR.txt 의 코드가 CRASH_OR_KILLED 인 항목을 찾아 ERROR.txt·detail JSON 과 조합 CSV·요약 CSV 의 그 행들을 지운다
     *       (CSV 는 파일마다 한 번만 다시 씀). 이후 그 항목들은 미완료가 되어 본 실행 루프가 다시 처리한다. 다른 오류(LOAD_FAILED 등)·정상 결과는 건드리지 않는다.
     * 입력: pairs(이번 설정에서 실행 대상인 (조합, 항목) 전체)
     * 출력: 파일 삭제·CSV 재작성, 실패 시 IOException
     * 리턴: 지운 (조합, 항목) 목록
     */
    fun clearCrashed(pairs: List<Pair<Combo, InputItem>>): List<Pair<Combo, InputItem>> {
        val crashed = pairs.filter { (c, it) -> outputs.isCrashed(c, it) } // 크래시 기록 항목
        outputs.removeResults(crashed) // 결과 파일·행 제거(없으면 아무것도 안 함)
        return crashed // 지운 항목
    }
}
