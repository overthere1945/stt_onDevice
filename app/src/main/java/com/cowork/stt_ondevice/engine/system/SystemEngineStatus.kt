// 파일명: SystemEngineStatus.kt
// 목적: 시스템 엔진 언어별 상태 조회·다운로드 요청의 계약과 Android 구현 (2차 작업 7)
// 기능: SystemEngineStatus — 상태 조회(status)·다운로드 가능 여부(canDownload)·다운로드 요청(download). 모델 화면과 실행 전 언어 확인이 같은 조회를 쓴다.
//       withStatusTimeout — 조회가 제한 시간(기본 15 초) 안에 끝나지 않으면 Unavailable("상태 확인 시간 초과") (가상 시간으로 테스트하려고 순수 Kotlin 함수로 분리).
//       safeStatus — 조회 중 예외(취소·VirtualMachineError 제외)를 Unavailable("상태 확인 실패(<클래스>: <메시지>)") 로 바꾼다.
//       AndroidSystemEngineStatus — ML Kit checkStatus()/download(), 플랫폼 checkRecognitionSupport(API 33)/triggerModelDownload(API 34) 를 호출만 한다.
//                                   동의 창은 Google 서비스가 띄우며 앱이 대신 누르지 않는다.
//       change(add)-hyungchul-2026-10-11-20:05 (수정 라운드 1) installedLanguages — 플랫폼 기기에 설치된 언어 목록 보조 조회(실행 전 확인의 "언어 미설치(…)(설치됨: …)" 사유용).
// change(add)-hyungchul-2026-10-11-19:52
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import android.Manifest // 권한 이름
import android.content.Context // 컨텍스트
import android.content.Intent // 인식 요청 인텐트
import android.content.pm.PackageManager // 권한 결과
import android.os.Build // API 수준
import android.speech.ModelDownloadListener // 플랫폼 모델 다운로드 콜백(API 34)
import android.speech.RecognitionSupport // 지원 확인 결과
import android.speech.RecognitionSupportCallback // 지원 확인 콜백
import android.speech.RecognizerIntent // 인텐트 엑스트라
import android.speech.SpeechRecognizer // 플랫폼 인식기
import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류
import com.google.mlkit.genai.common.DownloadStatus // ML Kit 다운로드 상태
import com.google.mlkit.genai.common.FeatureStatus // ML Kit 기능 상태
import kotlinx.coroutines.CancellationException // 코루틴 취소
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.channels.awaitClose // 흐름 닫힘 대기
import kotlinx.coroutines.flow.Flow // 흐름
import kotlinx.coroutines.flow.callbackFlow // 콜백 → 흐름
import kotlinx.coroutines.flow.flow // 흐름 빌더
import kotlinx.coroutines.flow.flowOn // 실행 스레드 지정
import kotlinx.coroutines.suspendCancellableCoroutine // 콜백 → 코루틴
import kotlinx.coroutines.withContext // 스레드 전환
import kotlinx.coroutines.withTimeoutOrNull // 제한 시간
import kotlin.coroutines.resume // 계속 재개

/** 목적: 상태 확인 기본 제한 시간(ms) / 기능: 실측 정상 응답은 0.2 초 안 — 넘으면 "상태 확인 시간 초과" */
const val DEFAULT_STATUS_TIMEOUT_MS: Long = 15_000 // 15 초

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 시스템 엔진 상태 조회·다운로드 계약
 * 기능: 모델 화면(ViewModel)과 실행 전 언어 확인(SystemEngines.unavailableReason)이 같은 조회를 쓰게 하고, 테스트에서 가짜로 바꿀 수 있게 한다.
 */
interface SystemEngineStatus {
    /**
     * 목적: 언어 상태 조회
     * 기능: 종류·앱 언어(ko/en)의 현재 상태를 돌려준다(시간 제한은 구현이 가짐).
     * 입력: kind(엔진 종류), engineLang(앱 엔진 언어 "ko"/"en")
     * 출력: 조회 중 오류는 예외(호출자가 safeStatus 로 사유화), 취소 시 CancellationException
     * 리턴: LanguageStatus
     */
    suspend fun status(kind: SystemEngineKind, engineLang: String): LanguageStatus

    /**
     * 목적: 앱에서 다운로드를 요청할 수 있는지
     * 기능: ML Kit 은 항상 true, 플랫폼은 API 34 이상만 true.
     * 입력: kind(엔진 종류)
     * 출력: 없음
     * 리턴: 가능하면 true
     */
    fun canDownload(kind: SystemEngineKind): Boolean

    /**
     * 목적: 언어 팩 다운로드 요청
     * 기능: Google 공식 API 를 호출하고 진행 이벤트를 흘린다. 동의 창은 Google 서비스가 띄우며 사용자가 직접 누른다. Done/Failed 로 끝난다.
     * 입력: kind(엔진 종류), engineLang(앱 엔진 언어)
     * 출력: 흐름 수집을 취소하면 요청 감시를 멈춘다
     * 리턴: DownloadEvent 흐름
     */
    fun download(kind: SystemEngineKind, engineLang: String): Flow<DownloadEvent>

    // change(add)-hyungchul-2026-10-11-20:05
    /**
     * 목적: 기기에 설치된 언어 목록(보조 조회)
     * 기능: 플랫폼 인식기의 installedOnDeviceLanguages 를 돌려준다(실행 전 확인이 "설치됨: …" 를 사유에 붙이는 데 씀). 기본 구현은 알 수 없음(null) — ML Kit 은 목록이 없다.
     * 입력: kind(엔진 종류), engineLang(앱 엔진 언어)
     * 출력: 오류는 null 로(상태 조회가 아니라 보조 정보라 사유를 덮지 않음), 취소 시 CancellationException
     * 리턴: 설치된 언어 태그 목록 또는 null(알 수 없음)
     */
    suspend fun installedLanguages(kind: SystemEngineKind, engineLang: String): List<String>? = null
}

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 상태 조회 제한 시간 래퍼
 * 기능: block 을 timeoutMs 안에 끝내면 그 값, 못 끝내면 Unavailable("상태 확인 시간 초과"). 코루틴 취소는 그대로 전파한다.
 * 입력: timeoutMs(제한 시간 ms, 기본 15,000), block(상태 조회 본문)
 * 출력: 취소 시 CancellationException
 * 리턴: LanguageStatus
 */
suspend fun withStatusTimeout(timeoutMs: Long = DEFAULT_STATUS_TIMEOUT_MS, block: suspend () -> LanguageStatus): LanguageStatus =
    withTimeoutOrNull(timeoutMs) { block() } ?: LanguageStatus.Unavailable(StatusReasons.TIMEOUT) // 시간 초과면 사용 불가

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 예외를 사유로 바꾸는 상태 조회
 * 기능: status() 중 예외(Exception 뿐 아니라 NoClassDefFoundError 같은 LinkageError 도)를 Unavailable("상태 확인 실패(<클래스>: <메시지>)") 로 바꾼다.
 *       코루틴 취소와 VirtualMachineError(OOM 등)는 그대로 다시 던진다(작업 6 의 unavailableReason 규칙 그대로).
 * 입력: kind(엔진 종류), engineLang(앱 엔진 언어)
 * 출력: 취소 시 CancellationException, VirtualMachineError 는 그대로
 * 리턴: LanguageStatus
 */
suspend fun SystemEngineStatus.safeStatus(kind: SystemEngineKind, engineLang: String): LanguageStatus = try {
    status(kind, engineLang) // 조회
} catch (e: CancellationException) {
    throw e // 취소는 그대로
} catch (e: VirtualMachineError) {
    throw e // OOM 등 VM 오류는 그대로(사유로 덮지 않음)
} catch (e: Throwable) {
    LanguageStatus.Unavailable("상태 확인 실패(${e.javaClass.simpleName}: ${e.message})") // 예외를 사유로(전파하지 않음)
}

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: SystemEngineStatus 의 Android 구현
 * 기능: 파일 머리 설명 참조. 모든 Android 인식기 호출은 메인 스레드(플랫폼) 또는 IO(ML Kit)에서 하며 임시 클라이언트는 항상 닫는다.
 * 입력: context(아무 컨텍스트 — 앱 컨텍스트로 바꿔 씀)
 */
class AndroidSystemEngineStatus(context: Context) : SystemEngineStatus {
    private val app: Context = context.applicationContext // 앱 컨텍스트(화면 누수 방지)

    /**
     * 목적: 언어 상태 조회
     * 기능: 앱 언어를 태그로 바꿔 종류별로 조회하고 withStatusTimeout(15 초)로 감싼다. 모르는 언어는 사유와 함께 사용 불가.
     * 입력: kind(엔진 종류), engineLang(앱 엔진 언어)
     * 출력: 인식기 오류는 예외로 전파(safeStatus 가 사유로 바꿈), 취소 시 CancellationException
     * 리턴: LanguageStatus
     */
    override suspend fun status(kind: SystemEngineKind, engineLang: String): LanguageStatus {
        val tag = SystemAsrEngine.LANG_TAGS[engineLang] ?: return LanguageStatus.Unavailable("지원하지 않는 언어: '$engineLang'") // ko → ko-KR, en → en-US
        return withStatusTimeout {
            when (kind) {
                SystemEngineKind.MLKIT_BASIC -> mlkitStatus(tag) // ML Kit
                SystemEngineKind.PLATFORM_ONDEVICE -> platformStatus(tag) // 플랫폼
            }
        }
    }

    /**
     * 목적: 다운로드 요청 가능 여부
     * 기능: ML Kit 은 true, 플랫폼 triggerModelDownload 는 API 34 이상만 true.
     * 입력: kind(엔진 종류)
     * 출력: 없음
     * 리턴: 가능하면 true
     */
    override fun canDownload(kind: SystemEngineKind): Boolean = when (kind) {
        SystemEngineKind.MLKIT_BASIC -> true // ML Kit download()
        SystemEngineKind.PLATFORM_ONDEVICE -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE // API 34
    }

    /**
     * 목적: 언어 팩 다운로드 요청
     * 기능: ML Kit → client.download() 흐름을 DownloadEvent 로, 플랫폼 → triggerModelDownload 콜백을 DownloadEvent 로 바꾼다.
     *       모르는 언어·API 부족은 Failed 한 번으로 끝낸다.
     * 입력: kind(엔진 종류), engineLang(앱 엔진 언어)
     * 출력: 수집 취소 시 임시 클라이언트를 닫는다
     * 리턴: DownloadEvent 흐름
     */
    override fun download(kind: SystemEngineKind, engineLang: String): Flow<DownloadEvent> {
        val tag = SystemAsrEngine.LANG_TAGS[engineLang] ?: return flow { emit(DownloadEvent.Failed("지원하지 않는 언어: '$engineLang'")) } // 모르는 언어
        return when (kind) {
            SystemEngineKind.MLKIT_BASIC -> mlkitDownload(tag) // ML Kit
            SystemEngineKind.PLATFORM_ONDEVICE -> platformDownload(tag) // 플랫폼
        }
    }

    // change(add)-hyungchul-2026-10-11-20:05
    /**
     * 목적: 플랫폼 설치된 언어 목록
     * 기능: 플랫폼만 지원한다. 상태 조회와 같은 선행 조건(API 33·마이크 권한·온디바이스 인식기)을 확인하고 메인 스레드 임시 인식기로 지원 확인을 불러 installed 목록을 돌려준다(15 초 제한).
     *       조건 미충족·시간 초과·onError·예외는 null(보조 정보라 상태 판정을 덮지 않음). 취소는 전파.
     * 입력: kind(엔진 종류), engineLang(앱 엔진 언어)
     * 출력: 취소 시 CancellationException
     * 리턴: 설치된 언어 태그 목록 또는 null
     */
    override suspend fun installedLanguages(kind: SystemEngineKind, engineLang: String): List<String>? {
        if (kind != SystemEngineKind.PLATFORM_ONDEVICE) return null // 플랫폼만
        val tag = SystemAsrEngine.LANG_TAGS[engineLang] ?: return null // 모르는 언어
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null // API 부족
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null // 권한 없음
        return try {
            withTimeoutOrNull(DEFAULT_STATUS_TIMEOUT_MS) {
                withContext(Dispatchers.Main) { // 인식기 호출은 메인 스레드
                    if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) return@withContext null // 인식기 없음
                    val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(app) // 임시 인식기
                    try {
                        val support = checkSupport(rec, tag) // 지원 확인
                        if (support.error != null) null else support.installed // 오류면 알 수 없음
                    } finally {
                        runCatching { rec.destroy() } // 임시 인식기 해제
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e // 취소는 그대로
        } catch (e: VirtualMachineError) {
            throw e // OOM 등은 그대로
        } catch (e: Throwable) {
            null // 보조 정보 실패는 알 수 없음으로
        }
    }

    /**
     * 목적: ML Kit 언어 상태
     * 기능: 임시 클라이언트로 checkStatus() 를 부르고 닫는다. AVAILABLE→Available, DOWNLOADABLE→Downloadable, DOWNLOADING→Downloading,
     *       UNAVAILABLE→Unavailable("이 기기에서 지원하지 않음"), 그 밖→Unavailable("알 수 없는 상태(<태그>, <이름>)"). 다운로드는 하지 않는다.
     * 입력: tag(BCP-47 태그)
     * 출력: 클라이언트 오류는 예외로 전파, 취소 시 CancellationException
     * 리턴: LanguageStatus
     */
    private suspend fun mlkitStatus(tag: String): LanguageStatus = withContext(Dispatchers.IO) {
        val client = MlKitBackend.newClient(tag) // 임시 클라이언트
        try {
            when (val s = client.checkStatus()) {
                FeatureStatus.AVAILABLE -> LanguageStatus.Available // 사용 가능
                FeatureStatus.DOWNLOADABLE -> LanguageStatus.Downloadable // 받아야 함
                FeatureStatus.DOWNLOADING -> LanguageStatus.Downloading // 받는 중
                FeatureStatus.UNAVAILABLE -> LanguageStatus.Unavailable(StatusReasons.MLKIT_UNSUPPORTED) // 미지원
                else -> LanguageStatus.Unavailable("알 수 없는 상태($tag, ${MlKitBackend.statusName(s)})") // 그 밖
            }
        } finally {
            runCatching { client.close() } // 임시 클라이언트 닫기(실패 무시)
        }
    }

    /**
     * 목적: 플랫폼 언어 상태
     * 기능: API 33 미만·RECORD_AUDIO 미허용·온디바이스 인식기 없음이면 사용 불가. 아니면 메인 스레드에서 임시 인식기로 checkRecognitionSupport 를 불러
     *       installed 에 태그가 있으면 Available, pending 이면 Downloading, supported 이면 Downloadable, 모두 아니면 Unavailable("기기 인식기가 이 언어를 지원하지 않음").
     * 입력: tag(BCP-47 태그)
     * 출력: 인식기 오류는 예외로 전파, 취소 시 CancellationException
     * 리턴: LanguageStatus
     */
    private suspend fun platformStatus(tag: String): LanguageStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return LanguageStatus.Unavailable(StatusReasons.API_TOO_OLD) // 파이프 입력 불가
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { // 권한
            return LanguageStatus.Unavailable("마이크(RECORD_AUDIO) 권한 없음 — 앱 권한에서 허용 필요") // 권한 없음
        }
        return withContext(Dispatchers.Main) { // 인식기 호출은 메인 스레드
            if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) return@withContext LanguageStatus.Unavailable("이 기기에서 온디바이스 인식기를 사용할 수 없음") // 인식기 없음
            val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(app) // 임시 인식기
            try {
                val support = checkSupport(rec, tag) // 지원 확인
                when {
                    support.error != null -> LanguageStatus.Unavailable("상태 확인 실패(onError=${support.error})") // 확인 오류
                    support.installed.any { it.equals(tag, ignoreCase = true) } -> LanguageStatus.Available // 설치됨
                    support.pending.any { it.equals(tag, ignoreCase = true) } -> LanguageStatus.Downloading // 설치 대기
                    support.supported.any { it.equals(tag, ignoreCase = true) } -> LanguageStatus.Downloadable // 받을 수 있음
                    else -> LanguageStatus.Unavailable(StatusReasons.PLATFORM_UNSUPPORTED) // 미지원
                }
            } finally {
                runCatching { rec.destroy() } // 임시 인식기 해제
            }
        }
    }

    /**
     * 목적: 플랫폼 지원 확인 결과
     * 기능: 설치된 언어, 설치 대기 언어, 받을 수 있는 언어, 오류 값(정상이면 null)을 담는다.
     * 입력: installed(설치됨), pending(대기), supported(받을 수 있음), error(onError 값 또는 null)
     */
    private data class Support(val installed: List<String>, val pending: List<String>, val supported: List<String>, val error: Int?)

    /**
     * 목적: checkRecognitionSupport 를 코루틴으로
     * 기능: 메인 스레드에서 지원 확인을 요청하고 콜백(onSupportResult/onError)으로 재개한다(취소 가능).
     * 입력: rec(임시 인식기), tag(언어 태그)
     * 출력: 취소 시 CancellationException
     * 리턴: Support
     */
    private suspend fun checkSupport(rec: SpeechRecognizer, tag: String): Support = suspendCancellableCoroutine { cont ->
        rec.checkRecognitionSupport(recognitionIntent(tag), app.mainExecutor, object : RecognitionSupportCallback { // 확인 요청
            override fun onSupportResult(recognitionSupport: RecognitionSupport) { // 결과
                if (cont.isActive) cont.resume(Support(recognitionSupport.installedOnDeviceLanguages, recognitionSupport.pendingOnDeviceLanguages, recognitionSupport.supportedOnDeviceLanguages, null)) // 재개
            }

            override fun onError(error: Int) { // 오류
                if (cont.isActive) cont.resume(Support(emptyList(), emptyList(), emptyList(), error)) // 오류로 재개
            }
        })
    }

    /**
     * 목적: 플랫폼 인식 요청 인텐트
     * 기능: 지원 확인·다운로드 요청에 쓰는 자유 발화 인텐트(언어 지정)를 만든다.
     * 입력: tag(언어 태그)
     * 출력: 없음
     * 리턴: Intent
     */
    private fun recognitionIntent(tag: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH) // 요청
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag) // 언어
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM) // 자유 발화

    /**
     * 목적: ML Kit 언어 팩 다운로드
     * 기능: 임시 클라이언트의 download() Flow 를 DownloadEvent 로 바꾼다(Started→Progress(0,전체), Progress→Progress(받은 양,전체), Completed→Done, Failed→Failed(메시지)).
     *       Flow 가 Completed/Failed 없이 끝나도 Done 으로 끝낸다(실제 상태는 호출자가 다시 조회). 끝나거나 취소되면 클라이언트를 닫는다.
     * 입력: tag(언어 태그)
     * 출력: 클라이언트 생성·수집 오류는 Failed 로 변환, 취소는 전파
     * 리턴: DownloadEvent 흐름
     */
    private fun mlkitDownload(tag: String): Flow<DownloadEvent> = flow {
        val client = MlKitBackend.newClient(tag) // 임시 클라이언트
        try {
            var total: Long? = null // 전체 바이트(Started 에서 알림)
            var terminal = false // Completed/Failed 를 보냈는지
            client.download().collect { st ->
                when (st) {
                    is DownloadStatus.DownloadStarted -> { total = st.bytesToDownload; emit(DownloadEvent.Progress(0, total)) } // 시작
                    is DownloadStatus.DownloadProgress -> emit(DownloadEvent.Progress(st.totalBytesDownloaded, total)) // 진행
                    is DownloadStatus.DownloadCompleted -> { terminal = true; emit(DownloadEvent.Done) } // 완료
                    is DownloadStatus.DownloadFailed -> { terminal = true; emit(DownloadEvent.Failed(st.e.message ?: st.e.javaClass.simpleName)) } // 실패
                    else -> Unit // 알 수 없는 상태 무시
                }
            }
            if (!terminal) emit(DownloadEvent.Done) // 끝 신호 없이 끝남 → 상태 재조회에 맡김
        } catch (e: CancellationException) {
            throw e // 취소는 그대로
        } catch (e: VirtualMachineError) {
            throw e // OOM 등은 그대로
        } catch (e: Throwable) {
            emit(DownloadEvent.Failed("${e.javaClass.simpleName}: ${e.message}")) // 오류를 실패 이벤트로
        } finally {
            runCatching { client.close() } // 임시 클라이언트 닫기
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 목적: 플랫폼 언어 팩 다운로드
     * 기능: API 34 이상에서 메인 스레드의 임시 인식기로 triggerModelDownload 를 부르고 콜백을 DownloadEvent 로 바꾼다
     *       (onProgress→Progress(퍼센트,100), onSuccess→Done, onScheduled→Done[기기가 조건이 맞을 때 받음 — 상태는 "다운로드 중"으로 다시 조회됨], onError→Failed(코드)).
     *       API 34 미만·권한 없음·인식기 없음은 Failed. 끝나거나 취소되면 인식기를 해제한다. 동의 창은 시스템이 띄운다.
     * 입력: tag(언어 태그)
     * 출력: 수집 취소 시 인식기 해제
     * 리턴: DownloadEvent 흐름
     */
    private fun platformDownload(tag: String): Flow<DownloadEvent> = callbackFlow {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34 미만
            trySend(DownloadEvent.Failed("Android 14 이상에서만 앱에서 다운로드를 요청할 수 있습니다")); close(); return@callbackFlow // 불가
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { // 권한
            trySend(DownloadEvent.Failed("마이크(RECORD_AUDIO) 권한 없음 — 앱 권한에서 허용 필요")); close(); return@callbackFlow // 불가
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(app)) { // 인식기 없음
            trySend(DownloadEvent.Failed("이 기기에서 온디바이스 인식기를 사용할 수 없음")); close(); return@callbackFlow // 불가
        }
        val rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(app) // 임시 인식기(메인 스레드)
        try {
            rec.triggerModelDownload(recognitionIntent(tag), app.mainExecutor, object : ModelDownloadListener { // 공식 다운로드 요청
                override fun onProgress(completedPercent: Int) { trySend(DownloadEvent.Progress(completedPercent.toLong(), 100)) } // 진행(퍼센트)
                override fun onSuccess() { trySend(DownloadEvent.Done); close() } // 완료
                override fun onScheduled() { trySend(DownloadEvent.Done); close() } // 예약됨(조건이 맞으면 기기가 받음)
                override fun onError(error: Int) { trySend(DownloadEvent.Failed("인식기 오류 코드 $error")); close() } // 오류
            })
        } catch (e: Throwable) {
            if (e is CancellationException || e is VirtualMachineError) throw e // 취소·VM 오류는 그대로
            trySend(DownloadEvent.Failed("${e.javaClass.simpleName}: ${e.message}")); close() // 호출 오류를 실패 이벤트로
        }
        awaitClose { runCatching { rec.destroy() } } // 끝나거나 취소되면 인식기 해제
    }.flowOn(Dispatchers.Main)
}
