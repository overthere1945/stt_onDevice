// 파일명: VadPool.kt
// 목적: 배치 실행 1회 동안 VAD 계열마다 VadEngine 을 하나만 만들어 재사용하고, 끝나면 모두 닫는다
// 기능: 처음 필요할 때 vadFactory 로 만들고(지연 생성), 생성 실패(VadUnavailableException 등)도 기억해 같은 계열은 다시 시도하지 않는다.
//       VAD 엔진은 단일 스레드 전제이며 러너는 순차 실행이므로 잠금은 두지 않는다.
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.vad.VadEngine // VAD 계약
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 계열별 VAD 엔진 보관소
 * 기능: get(family) 로 엔진 또는 생성 실패 예외를 돌려주고, close() 로 만든 엔진을 모두 닫는다.
 * 입력: factory(계열 → 엔진, 실패 시 예외)
 */
internal class VadPool(private val factory: (VadFamily) -> VadEngine) : AutoCloseable {

    private val entries = LinkedHashMap<VadFamily, Result<VadEngine>>() // 계열 → 생성 결과

    /**
     * 목적: 계열 엔진 얻기
     * 기능: 처음이면 factory 를 호출해 결과(성공/실패)를 저장하고, 이후엔 저장된 결과를 돌려준다.
     *       Exception·LinkageError(네이티브 로드 실패)는 실패 결과로 저장하고, 그 밖의 Error 는 그대로 던진다.
     * 입력: family(VAD 계열)
     * 출력: 치명적 Error 는 전파
     * 리턴: Result(성공이면 엔진, 실패면 예외)
     */
    fun get(family: VadFamily): Result<VadEngine> = entries.getOrPut(family) { // 없으면 생성
        try {
            Result.success(factory(family)) // 생성 성공
        } catch (e: Exception) {
            Result.failure(e) // 생성 실패(VadUnavailableException 포함)
        } catch (e: LinkageError) {
            Result.failure(e) // 네이티브 라이브러리 문제
        }
    }

    /**
     * 목적: 모든 엔진 해제
     * 기능: 만든 엔진을 하나씩 닫는다. 하나가 실패해도 나머지는 닫는다.
     * 입력: 없음
     * 출력: 없음(닫기 실패는 무시 — 실행 결과에 영향 없음)
     * 리턴: 없음
     */
    override fun close() {
        for (r in entries.values) { // 생성 결과마다
            val engine = r.getOrNull() ?: continue // 실패 항목은 건너뜀
            try { engine.close() } catch (e: Exception) { /* 닫기 실패는 결과에 영향 없음 */ } // 해제
        }
        entries.clear() // 비우기
    }
}
