// 파일명: ItemOutputs.kt
// 목적: 파일 1건의 결과(txt 또는 ERROR.txt, detail JSON, result.csv 행, 요약 CSV 행)를 정해진 순서로 쓴다
// 기능: 순서 = txt/ERROR.txt(원자적) → detail JSON(원자적) → 조합 result.csv 행 → 요약 CSV 행 (R10).
//       txt 가 있으면 "완료"로 보므로 txt 는 항상 원자적으로 쓰고, CSV 행은 detail JSON 만으로 다시 만들 수 있게 한다(재개 복구).
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
     * 기능: <runDir>/<lang>/<combo>/result.csv 용 작성기를 만든다.
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
}
