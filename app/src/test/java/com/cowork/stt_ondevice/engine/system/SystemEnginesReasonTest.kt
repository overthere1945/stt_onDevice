// 파일명: SystemEnginesReasonTest.kt
// 목적: 실행 전 언어 확인(SystemEngines.unavailableReason)이 SystemEngineStatus 결과를 기존 사유 문구로 바꾸는지 검증 (2차 작업 7)
// 기능: 가짜 SystemEngineStatus 로 사용 가능 → null, 다운로드 필요·다운로드 중·사용 불가·시간 초과 → 작업 6 의 사유 문구, 예외 → "상태 확인 실패(<클래스>: <메시지>)",
//       취소·VirtualMachineError 는 그대로 전파되는지 확인한다.
//       change(add)-hyungchul-2026-10-11-20:05 (수정 라운드 1) 플랫폼 "언어 미설치" 사유의 "(설치됨: …)" 목록을 정확한 문구로 단언한다(다운로드 필요·미지원 두 분기, 목록 없음·여러 개·조회 실패).
// change(add)-hyungchul-2026-10-11-19:52
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지 테스트

import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 카탈로그
import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류
import kotlinx.coroutines.flow.Flow // 흐름
import kotlinx.coroutines.flow.emptyFlow // 빈 흐름
import kotlinx.coroutines.runBlocking // 블로킹 실행
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertNull // null
import org.junit.Assert.assertTrue // 참
import org.junit.Assert.fail // 실패
import org.junit.Test // 테스트
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: unavailableReason 의 상태 → 사유 변환 회귀 방지
 * 기능: 위 파일 머리말 참조.
 */
class SystemEnginesReasonTest {
    private val mlkit: ModelSpec = SystemCatalog.all.first { it.id == "mlkit-basic-x1" } // ML Kit spec
    private val platform: ModelSpec = SystemCatalog.all.first { it.id == "android-ondevice-asi-x1" } // 플랫폼 spec

    /** 목적: 고정 결과 상태 조회기 / 기능: 주어진 람다가 돌려주는 값(또는 던지는 예외)을 status 로 쓴다 / 입력: body */
    private fun fake(installed: () -> List<String>? = { null }, body: () -> LanguageStatus) = object : SystemEngineStatus {
        override suspend fun status(kind: SystemEngineKind, engineLang: String): LanguageStatus = body() // 결과
        override suspend fun installedLanguages(kind: SystemEngineKind, engineLang: String): List<String>? = installed() // change(add)-hyungchul-2026-10-11-20:05 설치된 언어
        override fun canDownload(kind: SystemEngineKind): Boolean = false // 사용 안 함
        override fun download(kind: SystemEngineKind, engineLang: String): Flow<DownloadEvent> = emptyFlow() // 사용 안 함
    }

    /** 목적: 사유 구하기 / 기능: 가짜 조회기로 unavailableReason 호출 / 리턴: 사유 또는 null */
    private fun reason(spec: ModelSpec, lang: String, body: () -> LanguageStatus): String? =
        runBlocking { SystemEngines.unavailableReason(fake(body = body), spec, lang) } // 호출

    /** 목적: 설치 목록 지정 사유 구하기(change(add)-hyungchul-2026-10-11-20:05) / 기능: 플랫폼 en 확인에 설치 목록 조회 결과를 붙인다 / 리턴: 사유 또는 null */
    private fun platformReason(installed: () -> List<String>?, body: () -> LanguageStatus): String? =
        runBlocking { SystemEngines.unavailableReason(fake(installed, body), platform, "en") } // 호출

    /** 목적: 사용 가능이면 null / 기능: Available → null / 리턴: 없음 */
    @Test
    fun availableIsNull() {
        assertNull(reason(mlkit, "ko") { LanguageStatus.Available }) // ML Kit
        assertNull(reason(platform, "ko") { LanguageStatus.Available }) // 플랫폼
    }

    /** 목적: 작업 6 사유 문구 유지 / 기능: 상태별 한국어 사유가 기존 문구와 같다 / 리턴: 없음 */
    @Test
    fun reasonsKeepTask6Wording() {
        assertEquals("언어 미설치(en-US) — Speech Services by Google 언어 팩 다운로드 필요(상태 DOWNLOADABLE)", reason(mlkit, "en") { LanguageStatus.Downloadable }) // 미설치
        assertEquals("언어 팩 다운로드 중(en-US, 상태 DOWNLOADING)", reason(mlkit, "en") { LanguageStatus.Downloading }) // 받는 중
        assertEquals("이 기기에서 사용할 수 없음(ko-KR, 상태 UNAVAILABLE)", reason(mlkit, "ko") { LanguageStatus.Unavailable(StatusReasons.MLKIT_UNSUPPORTED) }) // 미지원
        assertEquals("상태 확인 시간 초과(en-US, 15 초) — Speech Services by Google 응답 없음", reason(mlkit, "en") { LanguageStatus.Unavailable(StatusReasons.TIMEOUT) }) // 시간 초과
        assertEquals("언어 팩 설치 대기 중(en-US)", reason(platform, "en") { LanguageStatus.Downloading }) // 플랫폼 대기
        assertEquals("마이크(RECORD_AUDIO) 권한 없음 — 앱 권한에서 허용 필요", reason(platform, "ko") { LanguageStatus.Unavailable("마이크(RECORD_AUDIO) 권한 없음 — 앱 권한에서 허용 필요") }) // 그 밖 사유는 그대로
    }

    /**
     * 목적: 플랫폼 "언어 미설치" 사유의 설치 목록(change(add)-hyungchul-2026-10-11-20:05)
     * 기능: 작업 6 문구 "언어 미설치(<태그>) — <서비스> 언어 팩 필요(설치됨: <목록>)" 를 다운로드 필요·미지원 두 분기 모두 정확히 낸다. 목록이 비면 "없음", 여러 개는 쉼표, 조회 실패(null·예외)는 "확인 불가".
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun platformMissingReasonListsInstalledLanguages() {
        val head = "언어 미설치(en-US) — Android System Intelligence 언어 팩 필요" // 공통 앞부분
        assertEquals("$head(설치됨: ko-KR)", platformReason({ listOf("ko-KR") }) { LanguageStatus.Downloadable }) // 다운로드 필요 분기
        assertEquals("$head(설치됨: ko-KR)", platformReason({ listOf("ko-KR") }) { LanguageStatus.Unavailable(StatusReasons.PLATFORM_UNSUPPORTED) }) // 미지원 분기
        assertEquals("$head(설치됨: 없음)", platformReason({ emptyList() }) { LanguageStatus.Downloadable }) // 목록 비어 있음
        assertEquals("$head(설치됨: ko-KR,ja-JP)", platformReason({ listOf("ko-KR", "ja-JP") }) { LanguageStatus.Unavailable(StatusReasons.PLATFORM_UNSUPPORTED) }) // 여러 개
        assertEquals("$head(설치됨: 확인 불가)", platformReason({ null }) { LanguageStatus.Downloadable }) // 조회 실패(null)
        assertEquals("$head(설치됨: 확인 불가)", platformReason({ throw IllegalStateException("x") }) { LanguageStatus.Downloadable }) // 조회 예외
    }

    /** 목적: 예외 → 사유 / 기능: 일반 예외·LinkageError 는 "상태 확인 실패(<클래스>: <메시지>)" / 리턴: 없음 */
    @Test
    fun exceptionsBecomeReasons() {
        assertEquals("상태 확인 실패(IllegalStateException: boom)", reason(mlkit, "en") { throw IllegalStateException("boom") }) // 예외
        assertEquals("상태 확인 실패(NoClassDefFoundError: X)", reason(platform, "en") { throw NoClassDefFoundError("X") }) // 링크 오류
    }

    /** 목적: 취소·VM 오류 전파 / 기능: CancellationException 과 OutOfMemoryError 는 사유로 바뀌지 않고 그대로 던져진다 / 리턴: 없음 */
    @Test
    fun cancellationAndVmErrorPropagate() {
        try {
            reason(mlkit, "en") { throw CancellationException("c") } // 취소
            fail("취소가 전파되어야 함")
        } catch (e: CancellationException) {
            assertEquals("c", e.message) // 그대로
        }
        try {
            reason(mlkit, "en") { throw OutOfMemoryError("oom") } // VM 오류
            fail("VM 오류가 전파되어야 함")
        } catch (e: OutOfMemoryError) {
            assertEquals("oom", e.message) // 그대로
        }
    }

    /** 목적: 알 수 없는 언어 / 기능: 태그 표에 없는 언어는 조회 없이 기존 사유 / 리턴: 없음 */
    @Test
    fun unknownLanguage() {
        assertEquals("지원하지 않는 언어: 'fr'", reason(mlkit, "fr") { error("조회하면 안 됨") }) // 조회 전 거부
    }
}
