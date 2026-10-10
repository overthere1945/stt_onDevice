// 파일명: RunErrors.kt
// 목적: 배치 실행 중 잡은 예외를 spec §13 오류 형식(stage, code, message)으로 바꾸는 규칙을 한 곳에 둔다
// 기능: 엔진/VAD/WAV/입출력/메모리 부족/네이티브 라이브러리 누락을 서로 다른 code 로 나눈다.
//       코루틴 취소와 그 밖의 치명적 JVM 오류는 항목 오류로 삼키지 않도록 null 을 돌려준다(호출자가 다시 던짐).
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.audio.WavFormatException // WAV 형식 오류
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import com.cowork.stt_ondevice.vad.VadUnavailableException // VAD 사용 불가
import java.io.IOException // 입출력 오류
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 예외 → 오류 기록 변환
 * 기능: toError() 하나만 제공한다.
 */
internal object RunErrors {

    /**
     * 목적: 항목 격리용 오류 변환
     * 기능: EngineException 은 엔진이 보고한 stage/code, VadUnavailableException 은 VAD/VAD_UNAVAILABLE, WavFormatException 은 INPUT/WAV_FORMAT,
     *       IOException 은 (stage, IO_ERROR), OutOfMemoryError 는 (stage, OOM), UnsatisfiedLinkError 는 (stage, NATIVE_LIB_MISSING),
     *       그 밖의 LinkageError(NoClassDefFoundError 등)는 (stage, LINKAGE_ERROR),
     *       그 밖의 Exception 은 (stage, defaultCode). 메시지 앞에는 예외 클래스 이름을 붙인다.
     * 입력: t(잡은 예외), stage(현재 단계: INPUT|VAD|MODEL_LOAD|TRANSCRIBE|OUTPUT), defaultCode(분류되지 않은 예외의 code)
     * 출력: 없음
     * 리턴: ErrorJson, 항목 오류로 삼키면 안 되는 예외(취소·그 밖의 Error)면 null
     */
    fun toError(t: Throwable, stage: String, defaultCode: String): ErrorJson? = when (t) { // 예외 종류별 분기
        is CancellationException -> null // 취소는 전파
        is EngineException -> ErrorJson(t.stage, t.code, describe(t)) // 엔진이 분류한 값 그대로
        is VadUnavailableException -> ErrorJson("VAD", "VAD_UNAVAILABLE", describe(t)) // VAD 모델 없음
        is WavFormatException -> ErrorJson("INPUT", "WAV_FORMAT", describe(t)) // 지원하지 않는/손상 WAV
        is IOException -> ErrorJson(stage, "IO_ERROR", describe(t)) // 파일 입출력 실패
        is OutOfMemoryError -> ErrorJson(stage, "OOM", describe(t)) // 메모리 부족
        is UnsatisfiedLinkError -> ErrorJson(stage, "NATIVE_LIB_MISSING", describe(t)) // .so 누락
        is LinkageError -> ErrorJson(stage, "LINKAGE_ERROR", describe(t)) // 클래스 누락·불일치(NoClassDefFoundError 등)도 항목 오류로 격리 // change(add)-hyungchul-2026-10-11-02:14
        is Exception -> ErrorJson(stage, defaultCode, describe(t)) // 그 밖의 예외
        else -> null // 그 밖의 Error 는 전파(항목 오류로 삼키지 않음)
    }

    /**
     * 목적: 사람이 읽는 오류 메시지
     * 기능: "클래스이름: 메시지" (메시지가 없으면 클래스 이름만).
     * 입력: t(예외)
     * 출력: 없음
     * 리턴: 메시지 문자열
     */
    private fun describe(t: Throwable): String =
        t.message?.let { "${t.javaClass.simpleName}: $it" } ?: t.javaClass.simpleName // 클래스명 + 메시지
}
