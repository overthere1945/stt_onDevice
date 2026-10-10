// 파일명: RunState.kt
// 목적: 배치 실행 1회의 변하는 상태(manifest·상태별 건수·진행 로그)를 한 곳에서 관리한다
// 기능: manifest 를 바꿀 때마다 run_manifest.json 을 원자적으로 다시 쓰고, 상태별 건수를 세며, _logs/run_<id>.log 에 한 줄씩 덧붙인다.
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.RunManifest // 실행 manifest
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 실행 상태 보관
 * 기능: update()(manifest 변경+저장), count()(상태 집계), log()(로그 한 줄), finished()(종료 이벤트)를 제공한다.
 * 입력: runDir(실행 폴더), runId(실행 ID), initial(시작 manifest — 이미 저장된 상태), now(ISO8601 현재 시각)
 */
internal class RunState(
    private val runDir: File, // 실행 폴더
    private val runId: String, // 실행 ID
    initial: RunManifest, // 시작 manifest
    private val now: () -> String, // 현재 시각
) {
    /** 현재 manifest(항상 디스크와 같은 내용). */
    var manifest: RunManifest = initial // 현재 manifest
        private set

    private var ok = 0 // OK 건수
    private var noMatch = 0 // NO_MATCH 건수
    private var error = 0 // ERROR 건수
    private val logFile = OutputLayout.log(runDir, runId) // 로그 파일

    /**
     * 목적: manifest 변경과 저장
     * 기능: change 를 적용하고 run_manifest.json 을 원자적으로 다시 쓴다.
     * 입력: change(현재 manifest → 새 manifest)
     * 출력: 쓰기 실패 시 IOException
     * 리턴: 없음
     */
    fun update(change: (RunManifest) -> RunManifest) {
        manifest = change(manifest) // 변경
        manifest.write(OutputLayout.manifest(runDir)) // 원자적 저장
    }

    /**
     * 목적: 상태 집계
     * 기능: OK/NO_MATCH/ERROR 건수를 1 늘린다(그 밖의 값은 무시).
     * 입력: status(항목 상태)
     * 출력: 없음
     * 리턴: 없음
     */
    fun count(status: String) {
        when (status) { // 상태별
            "OK" -> ok++ // 정상
            "NO_MATCH" -> noMatch++ // 발화 없음
            "ERROR" -> error++ // 오류
        }
    }

    /**
     * 목적: 종료 이벤트 생성
     * 기능: 이번 실행에서 처리한 항목의 상태별 건수와 미지원 조합 수(manifest.unsupported 의 "combo|lang" 개수)를 담는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: RunEvent.Finished
     */
    fun finished(): RunEvent.Finished = RunEvent.Finished(ok, noMatch, error, manifest.unsupported.size) // 집계

    /**
     * 목적: 진행 로그 한 줄 추가
     * 기능: "시각<TAB>이벤트<TAB>조합<TAB>대상<TAB>상태<TAB>메시지" 를 append 한다(줄바꿈·탭은 공백으로 바꿈). 로그 쓰기 실패는 실행을 멈추지 않는다.
     * 입력: event(RUN_START/MODEL_LOAD/ITEM_START/ITEM_END/RECONCILE/RUN_END/RUN_CANCELLED), combo(조합명 또는 "-"), target(lang/relDir/stem 또는 "-"),
     *       status(상태 또는 "-"), message(설명)
     * 출력: 로그 파일 추가
     * 리턴: 없음
     */
    fun log(event: String, combo: String, target: String, status: String, message: String) {
        val line = listOf(now(), event, combo, target, status, message).joinToString("\t") { it.replace(Regex("[\\t\\r\\n]+"), " ") } // 한 줄 구성
        try {
            logFile.parentFile?.mkdirs() // _logs 폴더
            logFile.appendText(line + "\n", Charsets.UTF_8) // 덧붙이기
        } catch (e: Exception) {
            // 로그는 보조 기록이므로 실패해도 실행은 계속한다(결과 파일·manifest 는 별도로 기록됨)
        }
    }
}
