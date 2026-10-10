// 파일명: ResumeReconciler.kt
// 목적: 이어하기(resume) 시작 시 manifest.inFlight 에 남은 항목(이전 실행이 처리 도중 죽은 항목)을 정리한다 (R10)
// 기능: 결과 쓰기 순서가 txt/ERROR.txt(원자적) → detail(원자적) → CSV 행 → inFlight 해제 이므로, 죽은 시점에 따라
//       ① 완료 표시(txt 또는 ERROR.txt)와 그에 맞는 detail 이 있으면 → 결과를 그대로 두고 빠진 CSV 행만 detail 로 복구(중복 추가 없음)
//       ② 그 밖(완료 표시 없음, 또는 detail 이 없거나 깨져 행을 복구할 수 없음) → 남은 부분 결과를 지우고 ERROR(TRANSCRIBE, CRASH_OR_KILLED) 기록
//       어느 경우든 먼저 그 조합 CSV·요약 CSV 의 반쯤 쓰인 마지막 줄을 잘라낸다. ②는 같은 항목을 다시 돌리지 않는다(네이티브 크래시 반복 방지).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: inFlight 정리 결과
 * 기능: 어떤 항목을 어떻게 처리했는지 담는다(로그·이벤트·집계용).
 * 입력: combo/item(찾은 조합·항목, 못 찾으면 null), status(최종 상태, 못 찾으면 null), newResult(이번에 ERROR 결과를 새로 썼으면 true), message(로그 문구)
 */
internal data class ReconcileOutcome(
    val combo: Combo?, // 조합
    val item: InputItem?, // 항목
    val status: String?, // 최종 상태
    val newResult: Boolean, // 새 결과 기록 여부
    val message: String, // 설명
)

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 재개 시 진행 중 항목 정리기
 * 기능: reconcile() 하나를 제공한다.
 * 입력: runDir(실행 폴더), outputs(결과 쓰기 도우미), processor(오류 detail 생성)
 */
internal class ResumeReconciler(
    private val runDir: File, // 실행 폴더
    private val outputs: ItemOutputs, // 결과 쓰기
    private val processor: ItemProcessor, // detail 생성
) {

    /**
     * 목적: inFlight 항목 정리
     * 기능: 키("combo|lang|relDir|stem")로 조합·항목을 찾고, 파일 머리말의 ①/② 규칙으로 정리한다.
     * 입력: key(manifest.inFlight), combos(이번 설정의 모든 조합), items(이번 설정의 입력 항목)
     * 출력: 결과 파일 생성/수정, 쓰기 실패 시 IOException
     * 리턴: ReconcileOutcome
     */
    fun reconcile(key: String, combos: List<Combo>, items: List<InputItem>): ReconcileOutcome {
        val parts = key.split('|', limit = 4) // combo|lang|relDir|stem
        val combo = combos.firstOrNull { it.name == parts.getOrNull(0) } // 조합 찾기
        val item = items.firstOrNull { it.lang == parts.getOrNull(1) && it.relDir == parts.getOrNull(2) && it.stem == parts.getOrNull(3) } // 항목 찾기
        if (combo == null || item == null) return ReconcileOutcome(null, null, null, false, "inFlight 항목을 현재 설정에서 찾지 못해 표시만 지움: $key") // 설정 불일치
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
            return ReconcileOutcome(combo, item, detail.status, false, "완료된 결과 유지(CSV 행 복구: 조합=$needCombo, 요약=$needSummary)") // 결과
        }
        txt.delete() // ② 남은 부분 결과 제거
        err.delete() // ② 남은 부분 결과 제거
        val crash = ErrorJson("TRANSCRIBE", "CRASH_OR_KILLED", "이전 실행이 이 항목을 처리하던 중 끝나지 않음(네이티브 크래시·OOM·강제 종료 추정)") // 크래시 오류
        outputs.write(combo, item, processor.failed(combo, item, crash, null)) // ERROR.txt → detail → CSV 행
        return ReconcileOutcome(combo, item, "ERROR", true, "크래시 항목을 ERROR(CRASH_OR_KILLED)로 기록") // 결과
    }
}
