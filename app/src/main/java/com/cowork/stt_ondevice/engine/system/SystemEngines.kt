// 파일명: SystemEngines.kt
// 목적: 시스템 엔진(ML Kit GenAI Basic·Android 온디바이스 인식기)의 Android 연결 진입점 (2차 작업 6)
// 기능: create — 시스템 spec 으로 SystemAsrEngine(백엔드 생성: MlKitBackend/PlatformBackend, 버전 문자열: versionInfo)을 만든다.
//       unavailableReason — 실행 전 언어 사용 가능 확인(ML Kit checkStatus / 플랫폼 checkRecognitionSupport 직접 호출). 사용 가능하면 null,
//                           아니면 한국어 사유. 확인 중 예외는 "상태 확인 실패(<예외 클래스>: <메시지>)" 사유로 돌려준다(실행 실패로 번지지 않게).
//                           change(add)-hyungchul-2026-10-11-19:47 (리뷰 라운드 1) 취소·VirtualMachineError(OOM 등)를 뺀 모든 Throwable(NoClassDefFoundError 같은 LinkageError 포함)을 사유로 바꾼다.
//                           (작업 7 에서 SystemEngineStatus 로 옮길 예정)
//       versionInfo — 결과 기록용 engine_version 문자열(ML Kit: "mlkit-genai-sr 1.0.0-alpha1 / com.google.android.tts <versionName>",
//                     플랫폼: "com.google.android.as <versionName>", 패키지 없음 → "<패키지> 없음").
// change(add)-hyungchul-2026-10-11-19:18
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import android.Manifest // 권한 이름
import android.content.Context // 컨텍스트
import android.content.Intent // 지원 확인 인텐트
import android.content.pm.PackageManager // 패키지·권한 조회
import android.os.Build // API 수준
import android.speech.RecognitionSupport // 지원 확인 결과
import android.speech.RecognitionSupportCallback // 지원 확인 콜백
import android.speech.RecognizerIntent // 인텐트 엑스트라
import android.speech.SpeechRecognizer // 플랫폼 인식기
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SystemEngineConfig // 시스템 엔진 설정
import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류
import com.google.mlkit.genai.common.FeatureStatus // ML Kit 기능 상태
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.suspendCancellableCoroutine // 콜백 → 코루틴
import kotlinx.coroutines.withContext // 스레드 전환
import kotlinx.coroutines.withTimeoutOrNull // 제한 시간
import kotlin.coroutines.cancellation.CancellationException // 코루틴 취소
import kotlin.coroutines.resume // 계속 재개

// change(add)-hyungchul-2026-10-11-19:18
/**
 * 목적: 시스템 엔진 Android 연결 진입점
 * 기능: 파일 머리 설명 참조.
 */
object SystemEngines {
    /** 목적: ML Kit 음성 인식 라이브러리 버전 / 기능: gradle/libs.versions.toml 의 mlkitGenaiSpeech 와 같은 값이어야 함 */
    private const val MLKIT_SR_VERSION = "1.0.0-alpha1" // 라이브러리 버전

    /** 목적: 상태 확인 제한 시간(ms) / 기능: 실측 정상 응답은 0.2 초 안 — 넘으면 사유로 보고 */
    private const val STATUS_TIMEOUT_MS: Long = 15_000 // 15 초

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
     * 기능: 앱 언어(ko/en)를 태그로 바꿔 종류별로 확인한다. 사용 가능하면 null, 아니면 한국어 사유.
     *       확인 중 예외(취소 제외)는 "상태 확인 실패(<예외 클래스>: <메시지>)" 사유로 돌려준다 — 그 조합만 건너뛰고 기록되게(실행 실패로 번지지 않음).
     *       change(add)-hyungchul-2026-10-11-19:47 Exception 뿐 아니라 Error(예: 라이브러리·서비스 버전 차이로 나는 NoClassDefFoundError·NoSuchMethodError 같은 LinkageError)도 사유로 바꾼다.
     *       단 VirtualMachineError(OutOfMemoryError·StackOverflowError 등)는 프로세스 상태 자체가 위험하므로 다시 던진다.
     * 입력: context(컨텍스트), spec(시스템 spec), engineLang(앱 엔진 언어 "ko"/"en")
     * 출력: 시스템 spec 이 아니면 EngineException(MODEL_LOAD, CONFIG_MISMATCH), 취소 시 CancellationException, VirtualMachineError 는 그대로
     * 리턴: null(사용 가능) 또는 사유 문자열
     */
    suspend fun unavailableReason(context: Context, spec: ModelSpec, engineLang: String): String? {
        val kind = kindOf(spec) // 엔진 종류
        val tag = SystemAsrEngine.LANG_TAGS[engineLang] ?: return "지원하지 않는 언어: '$engineLang'" // ko → ko-KR, en → en-US
        val app = context.applicationContext // 앱 컨텍스트
        return try {
            when (kind) {
                SystemEngineKind.MLKIT_BASIC -> mlkitReason(tag) // ML Kit 상태
                SystemEngineKind.PLATFORM_ONDEVICE -> platformReason(app, tag) // 플랫폼 지원
            }
        } catch (e: CancellationException) {
            throw e // 취소는 그대로
        } catch (e: VirtualMachineError) {
            throw e // OOM 등 VM 오류는 그대로(사유로 덮지 않음) // change(add)-hyungchul-2026-10-11-19:47
        } catch (e: Throwable) { // change(add)-hyungchul-2026-10-11-19:47 Exception → Throwable(LinkageError 등도 사유로)
            "상태 확인 실패(${e.javaClass.simpleName}: ${e.message})" // 예외를 사유로(전파하지 않음)
        }
    }

    /**
     * 목적: ML Kit 언어 상태 → 사유
     * 기능: 임시 클라이언트로 checkStatus() 를 최대 15 초 기다리고 닫는다. AVAILABLE 이면 null.
     *       다운로드는 하지 않는다(음성 서비스의 사용자 확인 창이 뜸 — 사용자가 직접 승인할 일).
     * 입력: tag(BCP-47 태그)
     * 출력: 클라이언트 오류는 예외로 전파(호출자가 사유로 바꿈), 취소 시 CancellationException
     * 리턴: null 또는 사유
     */
    private suspend fun mlkitReason(tag: String): String? = withContext(Dispatchers.IO) {
        val client = MlKitBackend.newClient(tag) // 임시 클라이언트
        try {
            val status = withTimeoutOrNull(STATUS_TIMEOUT_MS) { client.checkStatus() } // 상태(제한 시간)
                ?: return@withContext "상태 확인 시간 초과($tag, ${STATUS_TIMEOUT_MS / 1000} 초) — ${SystemEngineKind.MLKIT_BASIC.serviceLabel} 응답 없음" // 응답 없음
            when (status) {
                FeatureStatus.AVAILABLE -> null // 사용 가능
                FeatureStatus.DOWNLOADABLE -> "언어 미설치($tag) — ${SystemEngineKind.MLKIT_BASIC.serviceLabel} 언어 팩 다운로드 필요(상태 DOWNLOADABLE)" // 미설치
                FeatureStatus.DOWNLOADING -> "언어 팩 다운로드 중($tag, 상태 DOWNLOADING)" // 받는 중
                FeatureStatus.UNAVAILABLE -> "이 기기에서 사용할 수 없음($tag, 상태 UNAVAILABLE)" // 미지원
                else -> "알 수 없는 상태($tag, ${MlKitBackend.statusName(status)})" // 그 밖
            }
        } finally {
            runCatching { client.close() } // 임시 클라이언트 닫기(실패 무시)
        }
    }

    /**
     * 목적: 플랫폼 언어 지원 → 사유
     * 기능: API 33 미만·RECORD_AUDIO 미허용·온디바이스 인식기 없음이면 사유. 아니면 메인 스레드에서 임시 인식기로
     *       checkRecognitionSupport 를 최대 15 초 기다려 installedOnDeviceLanguages 에 태그가 있으면 null, pending 이면 설치 대기 사유, 없으면 미설치 사유.
     * 입력: context(앱 컨텍스트), tag(BCP-47 태그)
     * 출력: 인식기 오류는 예외로 전파(호출자가 사유로 바꿈), 취소 시 CancellationException
     * 리턴: null 또는 사유
     */
    private suspend fun platformReason(context: Context, tag: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "Android 13 이상 필요(현재 API ${Build.VERSION.SDK_INT})" // 파이프 입력 불가
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { // 권한
            return "마이크(RECORD_AUDIO) 권한 없음 — 앱 권한에서 허용 필요" // 권한 없음
        }
        return withContext(Dispatchers.Main) { // 인식기 호출은 메인 스레드
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext "이 기기에서 온디바이스 인식기를 사용할 수 없음" // 인식기 없음
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(context) // 임시 인식기
            try {
                val support = withTimeoutOrNull(STATUS_TIMEOUT_MS) { checkSupport(context, rec, tag) } // 지원 확인(제한 시간)
                    ?: return@withContext "상태 확인 시간 초과($tag, ${STATUS_TIMEOUT_MS / 1000} 초) — ${SystemEngineKind.PLATFORM_ONDEVICE.serviceLabel} 응답 없음" // 응답 없음
                val (installed, pending, error) = support // 결과 분해
                when {
                    error != null -> "상태 확인 실패(onError=$error)" // 확인 오류
                    installed.any { it.equals(tag, ignoreCase = true) } -> null // 설치됨
                    pending.any { it.equals(tag, ignoreCase = true) } -> "언어 팩 설치 대기 중($tag)" // 대기
                    else -> "언어 미설치($tag) — ${SystemEngineKind.PLATFORM_ONDEVICE.serviceLabel} 언어 팩 필요(설치됨: ${installed.joinToString(",").ifEmpty { "없음" }})" // 미설치
                }
            } finally {
                runCatching { rec.destroy() } // 임시 인식기 해제
            }
        }
    }

    /**
     * 목적: 플랫폼 지원 확인 결과
     * 기능: 설치된 언어, 설치 대기 언어, 오류 값(정상이면 null)을 담는다.
     * 입력: installed(설치된 언어), pending(대기 언어), error(onError 값 또는 null)
     */
    private data class Support(val installed: List<String>, val pending: List<String>, val error: Int?)

    /**
     * 목적: checkRecognitionSupport 를 코루틴으로
     * 기능: 메인 스레드에서 지원 확인을 요청하고 콜백(onSupportResult/onError)으로 재개한다(취소 가능).
     * 입력: context(앱 컨텍스트), rec(임시 인식기), tag(언어 태그)
     * 출력: 취소 시 CancellationException
     * 리턴: Support
     */
    private suspend fun checkSupport(context: Context, rec: SpeechRecognizer, tag: String): Support = suspendCancellableCoroutine { cont ->
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH) // 확인 대상 요청
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag) // 언어
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM) // 자유 발화
        rec.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback { // 확인 요청
            override fun onSupportResult(recognitionSupport: RecognitionSupport) { // 결과
                if (cont.isActive) cont.resume(Support(recognitionSupport.installedOnDeviceLanguages, recognitionSupport.pendingOnDeviceLanguages, null)) // 재개
            }

            override fun onError(error: Int) { // 오류
                if (cont.isActive) cont.resume(Support(emptyList(), emptyList(), error)) // 오류로 재개
            }
        })
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
