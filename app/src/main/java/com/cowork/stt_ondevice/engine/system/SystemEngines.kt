// 파일명: SystemEngines.kt
// 목적: 시스템 엔진(ML Kit GenAI Basic·Android 온디바이스 인식기)의 Android 연결 진입점 (2차 작업 6)
// 기능: create — 시스템 spec 으로 SystemAsrEngine(백엔드 생성: MlKitBackend/PlatformBackend, 버전 문자열: versionInfo)을 만든다.
//       unavailableReason — 실행 전 언어 사용 가능 확인(AndroidSystemEngineStatus 로 상태를 조회해 사유로 변환). 사용 가능하면 null,
//                           아니면 한국어 사유. 확인 중 예외는 "상태 확인 실패(<예외 클래스>: <메시지>)" 사유로 돌려준다(실행 실패로 번지지 않게).
//                           change(add)-hyungchul-2026-10-11-19:47 (리뷰 라운드 1) 취소·VirtualMachineError(OOM 등)를 뺀 모든 Throwable(NoClassDefFoundError 같은 LinkageError 포함)을 사유로 바꾼다.
//                           change(add)-hyungchul-2026-10-11-19:52 (작업 7) 상태 조회(ML Kit checkStatus / 플랫폼 checkRecognitionSupport)를 SystemEngineStatus.kt 로 옮겼다 —
//                           모델 화면과 같은 조회를 쓰며, 사유 문구는 reasonOf 가 작업 6 의 문구로 바꾼다.
//       versionInfo — 결과 기록용 engine_version 문자열(ML Kit: "mlkit-genai-sr 1.0.0-alpha1 / com.google.android.tts <versionName>",
//                     플랫폼: "com.google.android.as <versionName>", 패키지 없음 → "<패키지> 없음").
// change(add)-hyungchul-2026-10-11-19:18
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import android.content.Context // 컨텍스트
import android.content.pm.PackageManager // 패키지·권한 조회
import android.os.Build // API 수준
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SystemEngineConfig // 시스템 엔진 설정
import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류

// change(add)-hyungchul-2026-10-11-19:18
/**
 * 목적: 시스템 엔진 Android 연결 진입점
 * 기능: 파일 머리 설명 참조.
 */
object SystemEngines {
    /** 목적: ML Kit 음성 인식 라이브러리 버전 / 기능: gradle/libs.versions.toml 의 mlkitGenaiSpeech 와 같은 값이어야 함 */
    private const val MLKIT_SR_VERSION = "1.0.0-alpha1" // 라이브러리 버전

    /**
     * 목적: 시스템 엔진 생성
     * 기능: spec 의 종류에 맞는 백엔드 생성 함수와 버전 문자열 함수로 SystemAsrEngine 을 만든다(백엔드는 prepare 때 생성).
     * 입력: context(아무 컨텍스트 — 앱 컨텍스트로 바꿔 씀), spec(시스템 spec)
     * 출력: 시스템 spec 이 아니면 EngineException(MODEL_LOAD, CONFIG_MISMATCH)
     * 리턴: AsrEngine
     */
    fun create(context: Context, spec: ModelSpec): AsrEngine {
        val app = context.applicationContext // 앱 컨텍스트(화면 누수 방지)
        val kind = kindOf(spec) // 엔진 종류
        return SystemAsrEngine(spec, { backendFor(app, kind) }, { versionInfo(app, kind) }) // 세션 핵심 + 실제 백엔드
    }

    /**
     * 목적: 종류별 백엔드 생성
     * 기능: ML Kit Basic → MlKitBackend, 플랫폼 온디바이스 → PlatformBackend.
     * 입력: context(앱 컨텍스트), kind(엔진 종류)
     * 출력: 없음
     * 리턴: RecognitionBackend
     */
    internal fun backendFor(context: Context, kind: SystemEngineKind): RecognitionBackend = when (kind) {
        SystemEngineKind.MLKIT_BASIC -> MlKitBackend() // ML Kit
        SystemEngineKind.PLATFORM_ONDEVICE -> PlatformBackend(context) // 플랫폼
    }

    /**
     * 목적: 실행 전 언어 사용 가능 확인
     * 기능: 앱 언어(ko/en)를 태그로 바꿔 AndroidSystemEngineStatus 로 상태를 조회하고(모델 화면과 같은 조회), 작업 6 의 사유 문구로 바꾼다. 사용 가능하면 null, 아니면 한국어 사유.
     *       조회 중 예외(취소 제외)는 "상태 확인 실패(<예외 클래스>: <메시지>)" 사유로 돌려준다 — 그 조합만 건너뛰고 기록되게(실행 실패로 번지지 않음).
     *       change(add)-hyungchul-2026-10-11-19:47 Exception 뿐 아니라 Error(예: 라이브러리·서비스 버전 차이로 나는 NoClassDefFoundError·NoSuchMethodError 같은 LinkageError)도 사유로 바꾼다.
     *       단 VirtualMachineError(OutOfMemoryError·StackOverflowError 등)는 프로세스 상태 자체가 위험하므로 다시 던진다.
     *       change(add)-hyungchul-2026-10-11-19:52 (작업 7) 상태 조회를 SystemEngineStatus(safeStatus) 로 옮겼다 — 예외 처리 규칙은 safeStatus 가 같게 가진다.
     * 입력: context(컨텍스트), spec(시스템 spec), engineLang(앱 엔진 언어 "ko"/"en")
     * 출력: 시스템 spec 이 아니면 EngineException(MODEL_LOAD, CONFIG_MISMATCH), 취소 시 CancellationException, VirtualMachineError 는 그대로
     * 리턴: null(사용 가능) 또는 사유 문자열
     */
    suspend fun unavailableReason(context: Context, spec: ModelSpec, engineLang: String): String? =
        unavailableReason(AndroidSystemEngineStatus(context), spec, engineLang) // 실제 조회기 // change(add)-hyungchul-2026-10-11-19:52

    // change(add)-hyungchul-2026-10-11-19:52
    /**
     * 목적: 실행 전 언어 확인(조회기 주입판)
     * 기능: kind 확인 → 언어 태그 확인 → status.safeStatus 조회 → reasonOf 로 사유 변환. 가짜 조회기로 테스트할 수 있게 분리했다.
     * 입력: status(상태 조회기), spec(시스템 spec), engineLang(앱 엔진 언어)
     * 출력: 시스템 spec 이 아니면 EngineException(MODEL_LOAD, CONFIG_MISMATCH), 취소 시 CancellationException, VirtualMachineError 는 그대로
     * 리턴: null 또는 사유
     */
    internal suspend fun unavailableReason(status: SystemEngineStatus, spec: ModelSpec, engineLang: String): String? {
        val kind = kindOf(spec) // 엔진 종류
        val tag = SystemAsrEngine.LANG_TAGS[engineLang] ?: return "지원하지 않는 언어: '$engineLang'" // ko → ko-KR, en → en-US
        return reasonOf(kind, tag, status.safeStatus(kind, engineLang)) // 조회 → 사유
    }

    // change(add)-hyungchul-2026-10-11-19:52
    /**
     * 목적: 언어 상태 → 실행 전 확인 사유
     * 기능: Available → null. 그 밖은 작업 6 의 사유 문구(상태 이름·서비스 이름·태그 포함)로 바꾼다. 상수가 아닌 Unavailable 사유(권한 없음·확인 실패 등)는 그대로 돌려준다.
     * 입력: kind(엔진 종류), tag(BCP-47 태그), status(조회 결과)
     * 출력: 없음
     * 리턴: null 또는 사유
     */
    internal fun reasonOf(kind: SystemEngineKind, tag: String, status: LanguageStatus): String? = when (status) {
        LanguageStatus.Available -> null // 사용 가능
        LanguageStatus.Downloadable -> when (kind) {
            SystemEngineKind.MLKIT_BASIC -> "언어 미설치($tag) — ${kind.serviceLabel} 언어 팩 다운로드 필요(상태 DOWNLOADABLE)" // ML Kit 미설치
            SystemEngineKind.PLATFORM_ONDEVICE -> "언어 미설치($tag) — ${kind.serviceLabel} 언어 팩 필요" // 플랫폼 미설치
        }
        LanguageStatus.Downloading -> when (kind) {
            SystemEngineKind.MLKIT_BASIC -> "언어 팩 다운로드 중($tag, 상태 DOWNLOADING)" // ML Kit 받는 중
            SystemEngineKind.PLATFORM_ONDEVICE -> "언어 팩 설치 대기 중($tag)" // 플랫폼 대기
        }
        is LanguageStatus.Unavailable -> when (status.reason) {
            StatusReasons.TIMEOUT -> "상태 확인 시간 초과($tag, ${DEFAULT_STATUS_TIMEOUT_MS / 1000} 초) — ${kind.serviceLabel} 응답 없음" // 응답 없음
            StatusReasons.MLKIT_UNSUPPORTED -> "이 기기에서 사용할 수 없음($tag, 상태 UNAVAILABLE)" // ML Kit 미지원
            StatusReasons.API_TOO_OLD -> "Android 13 이상 필요(현재 API ${Build.VERSION.SDK_INT})" // 플랫폼 API 부족
            StatusReasons.PLATFORM_UNSUPPORTED -> "언어 미설치($tag) — ${kind.serviceLabel} 언어 팩 필요(${StatusReasons.PLATFORM_UNSUPPORTED})" // 플랫폼 미지원 언어
            else -> status.reason // 그 밖 사유는 그대로
        }
    }

    /**
     * 목적: 결과 기록용 엔진 버전 문자열
     * 기능: 서비스 패키지의 versionName 을 읽는다(없으면 "<패키지> 없음"). ML Kit 은 라이브러리 버전을 앞에 붙인다.
     * 입력: context(컨텍스트), kind(엔진 종류)
     * 출력: 없음
     * 리턴: 버전 문자열
     */
    fun versionInfo(context: Context, kind: SystemEngineKind): String {
        val pkg = kind.servicePackage // 서비스 패키지
        val service = try {
            "$pkg ${context.packageManager.getPackageInfo(pkg, 0).versionName ?: "(버전 이름 없음)"}" // 패키지 버전
        } catch (e: PackageManager.NameNotFoundException) {
            "$pkg 없음" // 패키지 없음(또는 보이지 않음)
        }
        return when (kind) {
            SystemEngineKind.MLKIT_BASIC -> "mlkit-genai-sr $MLKIT_SR_VERSION / $service" // ML Kit
            SystemEngineKind.PLATFORM_ONDEVICE -> service // 플랫폼
        }
    }

    /**
     * 목적: spec → 엔진 종류
     * 기능: engineConfig 가 SystemEngineConfig 이면 그 종류.
     * 입력: spec(모델 메타)
     * 출력: 시스템 spec 이 아니면 EngineException(MODEL_LOAD, CONFIG_MISMATCH)
     * 리턴: SystemEngineKind
     */
    private fun kindOf(spec: ModelSpec): SystemEngineKind = (spec.engineConfig as? SystemEngineConfig)?.kind
        ?: throw EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "시스템 엔진 스펙이 아님(engineConfig=${spec.engineConfig}): ${spec.id}") // 다른 엔진으로 대체하지 않음
}
