// 파일명: AppDirs.kt
// 목적: 앱 전용 외부 저장소 아래 작업 폴더 경로를 한곳에서 관리
// 기능: input_sound / models / output_stt / tmp 경로와 실행(run)별 출력 폴더 경로를 제공한다.
package com.cowork.stt_ondevice.core.storage // 저장소 패키지

import android.content.Context // 안드로이드 컨텍스트
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:06
/**
 * 목적: 앱 폴더 구조 정의
 * 기능: 루트 폴더 기준으로 입력·모델·출력·임시 폴더를 노출한다(폴더 생성은 하지 않는다).
 * 입력: root(앱 전용 외부 저장소 루트)
 */
class AppDirs(val root: File) {

    val inputSound: File = File(root, "input_sound") // 입력 음원 폴더
    val models: File = File(root, "models") // 모델 폴더
    val output: File = File(root, "output_stt") // 결과 출력 폴더
    val tmp: File = File(root, "tmp") // 임시 폴더

    /**
     * 목적: 실행(run)별 출력 폴더 경로 계산
     * 기능: output_stt 아래 runId 폴더 경로를 만든다.
     * 입력: runId(yyyyMMdd_HHmmss)
     * 출력: 없음
     * 리턴: output/runId 폴더(File, 생성하지 않음)
     */
    fun runDir(runId: String): File = File(output, runId) // 출력 루트 아래 run 폴더

    companion object {
        /**
         * 목적: 컨텍스트에서 AppDirs 생성
         * 기능: getExternalFilesDir(null) 을 루트로 사용한다.
         * 입력: context(안드로이드 컨텍스트)
         * 출력: 외부 저장소를 쓸 수 없으면 IllegalStateException
         * 리턴: AppDirs
         */
        fun from(context: Context): AppDirs {
            val base = context.getExternalFilesDir(null) // 앱 전용 외부 저장소 루트
                ?: throw IllegalStateException("앱 전용 외부 저장소를 사용할 수 없습니다(getExternalFilesDir 가 null)") // 조용한 대체 금지
            return AppDirs(base) // 루트로 생성
        }
    }
}
