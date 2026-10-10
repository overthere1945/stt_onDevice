// 파일명: OutputLayout.kt
// 목적: PC 평가기가 읽는 결과 폴더/파일 경로 규칙을 한 곳에서 정의
// 기능: output_stt/<run_id>/<lang>/<algo>_<model>_<VAD>/<relDir>/<stem>.txt 등 모든 산출물의 경로를 계산한다(파일/폴더를 만들지는 않는다).
package com.cowork.stt_ondevice.output // 출력 패키지

import com.cowork.stt_ondevice.core.storage.InputItem // 입력 음원 모델
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: 출력 경로 규칙
 * 기능: runDir(= output_stt/<run_id>) 기준으로 각 산출물 경로를 돌려준다. relDir 가 빈 문자열이면 추가 경로 조각을 만들지 않는다.
 */
object OutputLayout {

    /**
     * 목적: relDir 가 비어도 안전한 경로 결합
     * 기능: base 아래에 relDir('/' 구분, 빈 문자열 가능)를 붙인다.
     * 입력: base(기준 폴더), relDir(상대 폴더 또는 "")
     * 출력: 없음
     * 리턴: 결합된 폴더 File
     */
    private fun withRel(base: File, relDir: String): File =
        if (relDir.isEmpty()) base else File(base, relDir) // 빈 값이면 그대로, 아니면 하위 폴더

    /**
     * 목적: 조합 폴더명 생성
     * 기능: "<algo>_<model>_<vadPresetId>" 문자열을 만든다.
     * 입력: algo(엔진 이름), model(모델 이름), vadPresetId(VAD 프리셋 ID)
     * 출력: 없음
     * 리턴: 조합명
     */
    fun comboName(algo: String, model: String, vadPresetId: String): String = "${algo}_${model}_$vadPresetId" // 밑줄로 연결

    /**
     * 목적: 전사 txt 경로
     * 기능: <runDir>/<lang>/<combo>/<relDir>/<stem>.txt
     * 입력: runDir(실행 폴더), item(입력 음원), combo(조합명)
     * 출력: 없음
     * 리턴: txt 파일 경로
     */
    fun transcript(runDir: File, item: InputItem, combo: String): File =
        File(withRel(File(File(runDir, item.lang), combo), item.relDir), "${item.stem}.txt") // 언어/조합/상대폴더/stem.txt

    /**
     * 목적: 오류 txt 경로
     * 기능: 전사 txt 와 같은 폴더의 <stem>.ERROR.txt
     * 입력: runDir(실행 폴더), item(입력 음원), combo(조합명)
     * 출력: 없음
     * 리턴: ERROR txt 파일 경로
     */
    fun errorFile(runDir: File, item: InputItem, combo: String): File =
        File(withRel(File(File(runDir, item.lang), combo), item.relDir), "${item.stem}.ERROR.txt") // stem.ERROR.txt

    /**
     * 목적: 조합별 결과 CSV 경로
     * 기능: <runDir>/<lang>/<combo>/result.csv
     * 입력: runDir(실행 폴더), lang(언어 폴더명), combo(조합명)
     * 출력: 없음
     * 리턴: result.csv 파일 경로
     */
    fun resultCsv(runDir: File, lang: String, combo: String): File =
        File(File(File(runDir, lang), combo), "result.csv") // 조합 폴더 바로 아래

    /**
     * 목적: 파일별 상세 JSON 경로
     * 기능: <runDir>/details/<lang>/<combo>/<relDir>/<stem>.json
     * 입력: runDir(실행 폴더), item(입력 음원), combo(조합명)
     * 출력: 없음
     * 리턴: 상세 JSON 파일 경로
     */
    fun detail(runDir: File, item: InputItem, combo: String): File =
        File(withRel(File(File(File(runDir, "details"), item.lang), combo), item.relDir), "${item.stem}.json") // details 하위

    /**
     * 목적: VAD 구간 JSON 경로
     * 기능: <runDir>/vad_segments/<presetId>/<lang>/<relDir>/<stem>.json
     * 입력: runDir(실행 폴더), presetId(VAD 프리셋 ID), item(입력 음원)
     * 출력: 없음
     * 리턴: VAD 구간 JSON 파일 경로
     */
    fun vadSegments(runDir: File, presetId: String, item: InputItem): File =
        File(withRel(File(File(File(runDir, "vad_segments"), presetId), item.lang), item.relDir), "${item.stem}.json") // vad_segments 하위

    /**
     * 목적: 실행 매니페스트 경로
     * 기능: <runDir>/manifest.json
     * 입력: runDir(실행 폴더)
     * 출력: 없음
     * 리턴: manifest.json 파일 경로
     */
    fun manifest(runDir: File): File = File(runDir, "manifest.json") // 실행 폴더 바로 아래

    /**
     * 목적: 실행 로그 경로
     * 기능: <runDir>/_logs/run_<runId>.log
     * 입력: runDir(실행 폴더), runId(실행 ID)
     * 출력: 없음
     * 리턴: 로그 파일 경로
     */
    fun log(runDir: File, runId: String): File = File(File(runDir, "_logs"), "run_$runId.log") // _logs 하위

    /**
     * 목적: 통합 요약 CSV 경로
     * 기능: <runDir>/_summary/all_result_<runId>.csv
     * 입력: runDir(실행 폴더), runId(실행 ID)
     * 출력: 없음
     * 리턴: 요약 CSV 파일 경로
     */
    fun summaryCsv(runDir: File, runId: String): File = File(File(runDir, "_summary"), "all_result_$runId.csv") // _summary 하위
}
