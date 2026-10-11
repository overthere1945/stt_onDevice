// 파일명: ItemOutputs.kt
// 목적: 파일 1건의 결과(txt 또는 ERROR.txt, detail JSON, result.csv 행, 요약 CSV 행)를 정해진 순서로 쓴다
// 기능: 순서 = txt/ERROR.txt(원자적) → detail JSON(원자적) → 조합 result.csv 행 → 요약 CSV 행 (R10).
//       txt 가 있으면 "완료"로 보므로 txt 는 항상 원자적으로 쓰고, CSV 행은 detail JSON 만으로 다시 만들 수 있게 한다(재개 복구).
//       change(add)-hyungchul-2026-10-11-12:51 재개 정리용 도우미 추가: discard(항목 1건의 결과·행 지우기 — 처음 중단된 항목 다시 시도), isCrashed(CRASH_OR_KILLED 기록 여부),
//       truncateTornTails(여러 항목이 쓰는 CSV 들의 찢긴 마지막 줄을 한 번씩만 정리), removeResults(여러 항목의 결과 파일·행을 CSV 마다 한 번에 지우기).
//       change(add)-hyungchul-2026-10-11-18:14 removeResults 를 removeRows(행) → removeFiles(파일) 순서로 바꾸고 둘로 나눈다(정리 도중 죽어도 ERROR.txt 가 남아 다시 정리 가능, 2차 작업 1).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.json.AppJson // 공용 JSON
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.output.AtomicFile // 원자적 쓰기
import com.cowork.stt_ondevice.output.DetailJson // 상세 JSON
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.ResultCsvWriter // CSV 작성기
import com.cowork.stt_ondevice.output.STT_CSV_HEADER // PC CSV 헤더
import com.cowork.stt_ondevice.output.TimedText // 구간 텍스트
import com.cowork.stt_ondevice.output.TranscriptWriter // txt 작성기
import kotlinx.serialization.SerializationException // JSON 파싱 오류
import java.io.File // 파일
import java.io.IOException // 입출력 오류

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 항목 결과 쓰기
 * 기능: write()(전체 순서대로 쓰기), appendCsvRows()(CSV 행만), readDetail()(detail 읽기), hasCsvRow()/hasSummaryRow()(행 존재 확인), isDone()(완료 판정)을 제공한다.
 * 입력: runDir(실행 폴더), runId(실행 ID), device(기기), threads(설정 스레드 수)
 */
internal class ItemOutputs(
    private val runDir: File, // 실행 폴더
    private val runId: String, // 실행 ID
    private val device: DeviceInfo, // 기기 정보
    private val threads: Int, // 설정 스레드 수
) {
    private val summary = ResultCsvWriter(OutputLayout.summaryCsv(runDir, runId), STT_CSV_HEADER + "vad") // 요약 CSV(61열)

    /**
     * 목적: 조합 result.csv 작성기
     * 기능: <runDir>/_results/<lang>/<combo>/result.csv 용 작성기를 만든다(OutputLayout.resultCsv, R36).
     * 입력: combo(조합), item(입력 항목 — lang 사용)
     * 출력: 없음
     * 리턴: ResultCsvWriter
     */
    fun comboCsv(combo: Combo, item: InputItem): ResultCsvWriter =
        ResultCsvWriter(OutputLayout.resultCsv(runDir, item.lang, combo.name)) // 60열 작성기

    /** 목적: 요약 CSV 파일. 기능: 경로 반환. 입력: 없음. 출력: 없음. 리턴: File */
    fun summaryFile(): File = summary.file // 요약 CSV 경로

    /**
     * 목적: 완료 판정(재개 건너뛰기 기준)
     * 기능: txt 또는 ERROR.txt 가 있으면 완료로 본다(둘 다 원자적으로 쓰므로 부분 파일은 없다).
     * 입력: combo(조합), item(입력 항목)
     * 출력: 없음
     * 리턴: 완료면 true
     */
    fun isDone(combo: Combo, item: InputItem): Boolean =
        OutputLayout.transcript(runDir, item, combo.name).isFile || OutputLayout.errorFile(runDir, item, combo.name).isFile // 둘 중 하나 존재

    /**
     * 목적: 항목 결과 전체 쓰기
     * 기능: status 가 ERROR 면 남아 있을 수 있는 txt 를 지우고 ERROR.txt 를, 아니면 txt 를 원자적으로 쓴 뒤 detail JSON(원자적) → CSV 행 2개 순으로 쓴다.
     *       txt 형식: 엔진 "algo model", VAD 프리셋 id, 조각 수, 전체 텍스트, chunk 별 원본 시작~끝 offset 과 텍스트.
     *       replaceRows=true 면 CSV 행을 붙이기 전에 두 CSV 의 찢긴 마지막 줄을 잘라내고 이 항목의 기존 행을 지운다(재시도·크래시 정리 시 중복/상충 행 방지).
     * 입력: combo(조합), item(입력 항목), detail(상세 JSON), replaceRows(기존 행 교체 여부, 기본 false)
     * 출력: 파일 생성, 쓰기 실패 시 IOException
     * 리턴: 없음
     */
    // change(add)-hyungchul-2026-10-11-02:14: replaceRows 추가
    fun write(combo: Combo, item: InputItem, detail: DetailJson, replaceRows: Boolean = false) {
        val txt = OutputLayout.transcript(runDir, item, combo.name) // txt 경로
        val err = OutputLayout.errorFile(runDir, item, combo.name) // ERROR.txt 경로
        val error = detail.error // 오류 정보
        if (detail.status == "ERROR" && error != null) { // 오류 항목
            txt.delete() // 정상 txt 가 남아 있으면 제거(완료 판정 혼동 방지)
            TranscriptWriter.writeError(err, error.stage, error.code, error.message) // ERROR.txt(원자적)
        } else { // 정상/NO_MATCH 항목
            val segments = detail.chunks.map { TimedText(it.spans.first().startSec, it.spans.last().endSec, it.text) } // chunk 별 원본 구간
            val body = TranscriptWriter.format( // txt 본문
                "${combo.spec.algo} ${combo.spec.model}", combo.preset.id, detail.chunks.size, CsvRowBuilder.fullText(detail.chunks), segments,
            )
            TranscriptWriter.write(txt, body) // txt(원자적)
        }
        AtomicFile.write(OutputLayout.detail(runDir, item, combo.name), AppJson.encodeToString(DetailJson.serializer(), detail).toByteArray(Charsets.UTF_8)) // detail(원자적)
        if (replaceRows) { // 기존 행 교체 모드 // change(add)-hyungchul-2026-10-11-02:14
            val comboFile = OutputLayout.resultCsv(runDir, item.lang, combo.name) // 조합 CSV
            CsvFiles.truncatePartialLastLine(comboFile) // 찢긴 줄 제거
            CsvFiles.truncatePartialLastLine(summary.file) // 찢긴 줄 제거
            CsvFiles.removeRows(comboFile, comboMatch(item)) // 이 항목의 기존 행 제거
            CsvFiles.removeRows(summary.file, summaryMatch(combo, item)) // 요약의 기존 행 제거
        }
        appendCsvRows(combo, item, detail, comboRow = true, summaryRow = true) // CSV 행
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 항목 1건의 결과 지우기(다시 시도 준비)
     * 기능: txt·ERROR.txt·detail JSON 을 지우고, 두 CSV 의 찢긴 마지막 줄을 잘라낸 뒤 이 항목의 기존 행을 지운다. 이후 isDone 은 false 가 된다.
     * 입력: combo(조합), item(입력 항목)
     * 출력: 파일 삭제·CSV 재작성, 실패 시 IOException
     * 리턴: 없음
     */
    fun discard(combo: Combo, item: InputItem) {
        OutputLayout.transcript(runDir, item, combo.name).delete() // txt 제거
        OutputLayout.errorFile(runDir, item, combo.name).delete() // ERROR.txt 제거
        OutputLayout.detail(runDir, item, combo.name).delete() // detail 제거
        val comboFile = OutputLayout.resultCsv(runDir, item.lang, combo.name) // 조합 CSV
        CsvFiles.truncatePartialLastLine(comboFile) // 찢긴 줄 제거
        CsvFiles.truncatePartialLastLine(summary.file) // 찢긴 줄 제거
        CsvFiles.removeRows(comboFile, comboMatch(item)) // 이 항목 행 제거
        CsvFiles.removeRows(summary.file, summaryMatch(combo, item)) // 요약의 이 항목 행 제거
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: CRASH_OR_KILLED 로 기록된 항목인지
     * 기능: ERROR.txt 가 있고 그 안에 "code: CRASH_OR_KILLED" 줄이 있으면 true(ERROR.txt 는 완료 표시이므로 detail 보다 우선해서 본다).
     * 입력: combo(조합), item(입력 항목)
     * 출력: 읽기 실패 시 IOException
     * 리턴: 크래시 기록이면 true
     */
    fun isCrashed(combo: Combo, item: InputItem): Boolean {
        val err = OutputLayout.errorFile(runDir, item, combo.name) // ERROR.txt
        if (!err.isFile) return false // 오류 기록 없음
        return err.readLines(Charsets.UTF_8).any { it.trim() == "code: $CRASH_CODE" } // 코드 줄 확인
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 여러 항목이 쓸 CSV 들의 찢긴 마지막 줄 정리(한 번씩만)
     * 기능: targets 의 조합 CSV(중복 제거)와 요약 CSV 에 truncatePartialLastLine 을 한 번씩 부른다. 이후 행을 replaceRows=false 로 붙여도 줄이 합쳐지지 않는다.
     * 입력: targets(이제 쓸 (조합, 항목) 목록)
     * 출력: 파일 길이 변경
     * 리턴: 없음
     */
    fun truncateTornTails(targets: List<Pair<Combo, InputItem>>) {
        if (targets.isEmpty()) return // 쓸 것 없음
        targets.map { (c, it) -> OutputLayout.resultCsv(runDir, it.lang, c.name) }.distinct().forEach { CsvFiles.truncatePartialLastLine(it) } // 조합 CSV 마다 1번
        CsvFiles.truncatePartialLastLine(summary.file) // 요약 CSV 1번
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 여러 항목의 결과 행·파일 한꺼번에 지우기
     * 기능: removeRows(CSV 행) → removeFiles(txt·ERROR.txt·detail) 순서로 지운다. 행을 먼저 지우므로 중간에 프로세스가 죽어도
     *       ERROR.txt 가 남아 그 항목이 계속 "크래시 기록"으로 보이고, 다시 부르면 이어서 끝까지 지운다(멱등). 파일을 먼저 지우면 행만 남은 항목이
     *       미완료가 되어 다시 실행될 때 중복 행이 생길 수 있다.
     *       change(add)-hyungchul-2026-10-11-18:14 순서를 파일 → 행에서 행 → 파일로 바꾸고 두 단계를 removeRows/removeFiles 로 나눔(2차 작업 1).
     * 입력: targets(지울 (조합, 항목) 목록)
     * 출력: CSV 재작성·파일 삭제, 실패 시 IOException
     * 리턴: 없음
     */
    fun removeResults(targets: List<Pair<Combo, InputItem>>) {
        if (targets.isEmpty()) return // 지울 것 없음
        removeRows(targets) // 1단계: CSV 행 먼저 // change(add)-hyungchul-2026-10-11-18:14
        removeFiles(targets) // 2단계: 결과 파일 // change(add)-hyungchul-2026-10-11-18:14
    }

    // change(add)-hyungchul-2026-10-11-18:14
    /**
     * 목적: 여러 항목의 CSV 행만 지우기(removeResults 1단계)
     * 기능: 두 CSV 의 찢긴 마지막 줄을 정리한 뒤 조합 CSV 는 파일마다, 요약 CSV 는 한 번만 읽고 써서 그 항목들의 행을 지운다. 결과 파일(txt·ERROR.txt·detail)은 건드리지 않는다.
     *       "행 정리 직후 프로세스가 죽은 상태"를 만들 수 있게 따로 두며(테스트), 행이 이미 없어도 안전하다.
     * 입력: targets(행을 지울 (조합, 항목) 목록)
     * 출력: CSV 재작성, 실패 시 IOException
     * 리턴: 없음
     */
    internal fun removeRows(targets: List<Pair<Combo, InputItem>>) {
        if (targets.isEmpty()) return // 지울 것 없음
        truncateTornTails(targets) // 찢긴 줄 정리
        targets.groupBy { (c, it) -> OutputLayout.resultCsv(runDir, it.lang, c.name) }.forEach { (file, group) -> // 조합 CSV 마다
            val keys = group.map { (_, it) -> comboMatch(it) }.toSet() // 지울 행의 열 값 묶음
            CsvFiles.removeRowsWhere(file) { row -> comboMatch(row) in keys } // 한 번에 제거
        }
        val summaryKeys = targets.map { (c, it) -> summaryMatch(c, it) }.toSet() // 요약에서 지울 행의 열 값 묶음
        CsvFiles.removeRowsWhere(summary.file) { row -> summaryMatch(row) in summaryKeys } // 한 번에 제거
    }

    // change(add)-hyungchul-2026-10-11-18:14
    /**
     * 목적: 여러 항목의 결과 파일만 지우기(removeResults 2단계)
     * 기능: 항목마다 txt·ERROR.txt·detail JSON 을 지운다(없으면 건너뜀). CSV 는 건드리지 않는다.
     * 입력: targets(파일을 지울 (조합, 항목) 목록)
     * 출력: 파일 삭제
     * 리턴: 없음
     */
    internal fun removeFiles(targets: List<Pair<Combo, InputItem>>) {
        for ((c, it) in targets) { // 항목마다
            OutputLayout.detail(runDir, it, c.name).delete() // detail 제거
            OutputLayout.transcript(runDir, it, c.name).delete() // txt 제거
            OutputLayout.errorFile(runDir, it, c.name).delete() // ERROR.txt 는 항목의 마지막에 제거(완료 표시가 마지막까지 남음)
        }
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /** 목적: CSV 행에서 조합 CSV 일치 열만 뽑기. 기능: rel_dir·file. 입력: row(헤더 → 값). 출력: 없음. 리턴: 열 → 값 */
    private fun comboMatch(row: Map<String, String>): Map<String, String> = COMBO_KEYS.associateWith { row[it].orEmpty() } // 같은 열 이름으로 비교

    // change(add)-hyungchul-2026-10-11-12:51
    /** 목적: CSV 행에서 요약 CSV 일치 열만 뽑기. 기능: algo·model·vad·req_lang·rel_dir·file. 입력: row(헤더 → 값). 출력: 없음. 리턴: 열 → 값 */
    private fun summaryMatch(row: Map<String, String>): Map<String, String> = SUMMARY_KEYS.associateWith { row[it].orEmpty() } // 같은 열 이름으로 비교

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 조합 CSV 에서 이 항목 행을 찾는 열 조건. 기능: rel_dir·file. 입력: item. 출력: 없음. 리턴: 열 → 값 */
    private fun comboMatch(item: InputItem): Map<String, String> = mapOf("rel_dir" to item.relDir, "file" to item.file.name) // 조합·언어별 파일이라 이 둘로 충분

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 요약 CSV 에서 이 항목 행을 찾는 열 조건. 기능: algo·model·vad·req_lang·rel_dir·file. 입력: combo, item. 출력: 없음. 리턴: 열 → 값 */
    private fun summaryMatch(combo: Combo, item: InputItem): Map<String, String> = mapOf(
        "algo" to combo.spec.algo, "model" to combo.spec.model, "vad" to combo.preset.id, // 조합
        "req_lang" to item.engineLang, "rel_dir" to item.relDir, "file" to item.file.name, // 항목
    )

    /**
     * 목적: CSV 행 추가
     * 기능: detail 로 행을 만들어 지정한 파일(조합 result.csv / 요약 CSV)에 붙인다.
     * 입력: combo(조합), item(입력 항목), detail(상세 JSON), comboRow(조합 CSV 에 쓸지), summaryRow(요약 CSV 에 쓸지)
     * 출력: 파일에 행 추가
     * 리턴: 없음
     */
    fun appendCsvRows(combo: Combo, item: InputItem, detail: DetailJson, comboRow: Boolean, summaryRow: Boolean) {
        val row = CsvRowBuilder.build(runId, combo, item, detail, device, threads) // 60열 행
        if (comboRow) comboCsv(combo, item).append(row) // 조합 CSV
        if (summaryRow) summary.append(CsvRowBuilder.summaryRow(row, combo)) // 요약 CSV
    }

    /**
     * 목적: 조합 CSV 에 이 항목 행이 있는지
     * 기능: rel_dir·file 이 같은 행을 찾는다(조합·언어별 파일이라 이 둘로 충분).
     * 입력: combo(조합), item(입력 항목)
     * 출력: 없음
     * 리턴: 있으면 true
     */
    fun hasCsvRow(combo: Combo, item: InputItem): Boolean =
        CsvFiles.containsRow(OutputLayout.resultCsv(runDir, item.lang, combo.name), comboMatch(item)) // 열 일치

    /**
     * 목적: 요약 CSV 에 이 항목 행이 있는지
     * 기능: 요약 CSV 는 모든 조합·언어가 섞이므로 algo·model·vad·req_lang·rel_dir·file 이 모두 같은 행을 찾는다.
     * 입력: combo(조합), item(입력 항목)
     * 출력: 없음
     * 리턴: 있으면 true
     */
    fun hasSummaryRow(combo: Combo, item: InputItem): Boolean = CsvFiles.containsRow(summary.file, summaryMatch(combo, item)) // 열 일치

    /**
     * 목적: detail JSON 읽기
     * 기능: 파일이 없거나 깨졌으면 null.
     * 입력: combo(조합), item(입력 항목)
     * 출력: 없음
     * 리턴: DetailJson 또는 null
     */
    fun readDetail(combo: Combo, item: InputItem): DetailJson? {
        val f = OutputLayout.detail(runDir, item, combo.name) // detail 경로
        if (!f.isFile) return null // 없음
        return try {
            AppJson.decodeFromString(DetailJson.serializer(), f.readText(Charsets.UTF_8)) // 파싱
        } catch (e: SerializationException) {
            null // 손상
        } catch (e: IllegalArgumentException) {
            null // 잘못된 값
        } catch (e: IOException) {
            null // 읽기 실패
        }
    }

    // change(add)-hyungchul-2026-10-11-12:51
    companion object {
        /** 크래시·강제 종료로 끝나지 않은 단계를 정리할 때 쓰는 오류 코드. */
        const val CRASH_CODE = "CRASH_OR_KILLED" // 오류 코드
        private val COMBO_KEYS = listOf("rel_dir", "file") // 조합 CSV 항목 식별 열(comboMatch(item) 과 같은 열)
        private val SUMMARY_KEYS = listOf("algo", "model", "vad", "req_lang", "rel_dir", "file") // 요약 CSV 항목 식별 열(summaryMatch(combo, item) 과 같은 열)
    }
}
