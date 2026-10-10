// 파일명: App.kt
// 목적: 앱 프로세스 전역 진입점(Application) 정의
// 기능: (change(add)-hyungchul-2026-10-11-03:00 정정) 프로세스 전역 객체 그래프를 보유한다(아래 02:40 항목). 이후 Task 에서 필요한 전역 초기화도 이 클래스에 추가한다.
//       change(add)-hyungchul-2026-10-11-02:40 프로세스 전역 객체 그래프(AppDirs·ModelStore·Downloader·ModelInstaller·VadEngineFactory)를
//       지연 생성 싱글턴으로 보유하고, 배치 실행기(BatchRunner)를 실제 의존성으로 조립하는 newBatchRunner() 를 제공한다.
//       ModelStore 의 사용 횟수와 ModelInstaller 의 동시 설치 방지는 인스턴스 단위이므로(Task 19 리뷰) 서비스·화면 ViewModel 모두
//       반드시 여기 있는 같은 인스턴스를 써야 한다(App.from(context).modelStore 등). 새로 만들면 보호가 깨진다.
package com.cowork.stt_ondevice // 앱 루트 패키지

import android.app.Application // 안드로이드 Application 기본 클래스
import android.content.Context // change(add)-hyungchul-2026-10-11-02:40 컨텍스트(App 조회)
import android.util.Log // change(add)-hyungchul-2026-10-11-03:00 환경 스냅샷 실패 로그
import com.cowork.stt_ondevice.core.device.DeviceInfo // change(add)-hyungchul-2026-10-11-02:40 기기 정보
import com.cowork.stt_ondevice.core.device.EnvSnapshot // change(add)-hyungchul-2026-10-11-02:40 발열·배터리 스냅샷
import com.cowork.stt_ondevice.core.device.ResourceSampler // change(add)-hyungchul-2026-10-11-02:40 메모리·CPU 샘플러
import com.cowork.stt_ondevice.core.storage.AppDirs // change(add)-hyungchul-2026-10-11-02:40 앱 폴더 구조
import com.cowork.stt_ondevice.engine.ManagedEngine // change(add)-hyungchul-2026-10-11-02:40 acquire/release 묶은 엔진 생성기
import com.cowork.stt_ondevice.model.Downloader // change(add)-hyungchul-2026-10-11-02:40 다운로더
import com.cowork.stt_ondevice.model.InstallState // change(add)-hyungchul-2026-10-11-02:40 설치 상태
import com.cowork.stt_ondevice.model.ModelCatalog // change(add)-hyungchul-2026-10-11-02:40 모델 카탈로그(VAD 모델 조회)
import com.cowork.stt_ondevice.model.ModelInstaller // change(add)-hyungchul-2026-10-11-02:40 모델 설치기
import com.cowork.stt_ondevice.model.ModelStore // change(add)-hyungchul-2026-10-11-02:40 모델 저장소
import com.cowork.stt_ondevice.runner.BatchRunner // change(add)-hyungchul-2026-10-11-02:40 배치 실행기
import com.cowork.stt_ondevice.vad.VadEngineFactory // change(add)-hyungchul-2026-10-11-02:40 VAD 엔진 생성기
import com.cowork.stt_ondevice.vad.VadFamily // change(add)-hyungchul-2026-10-11-02:40 VAD 계열
import java.io.File // change(add)-hyungchul-2026-10-11-02:40 파일

// change(add)-hyungchul-2026-10-10-11:56
/**
 * 목적: 앱 프로세스 전역 상태의 소유자
 * 기능: 프로세스 시작 시 한 번 생성되며 Manifest 의 android:name 으로 등록된다.
 *       change(add)-hyungchul-2026-10-11-02:40 전역 객체는 처음 쓸 때 만든다(by lazy, 스레드 안전). AppDirs.from 이 실패하면(외부 저장소 없음)
 *       그 속성에 접근할 때마다 같은 예외가 다시 난다(lazy 는 실패를 캐시하지 않음).
 */
class App : Application() { // change(add)-hyungchul-2026-10-11-02:40 본문 추가(전역 객체 그래프)

    /** 앱 폴더(getExternalFilesDir(null) 루트). */
    val dirs: AppDirs by lazy { AppDirs.from(this) } // change(add)-hyungchul-2026-10-11-02:40 앱 폴더 싱글턴

    /** 모델 저장소 — 사용 횟수(acquire/release)를 프로세스에서 하나로 관리. */
    val modelStore: ModelStore by lazy { ModelStore(dirs) } // change(add)-hyungchul-2026-10-11-02:40 모델 저장소 싱글턴

    /** 모델 다운로더(기본 제한 시간). */
    val downloader: Downloader by lazy { Downloader() } // change(add)-hyungchul-2026-10-11-02:40 다운로더 싱글턴

    /** 모델 설치기 — 같은 모델 동시 설치 방지가 인스턴스 단위라 하나만 둔다. */
    val modelInstaller: ModelInstaller by lazy { ModelInstaller(dirs, modelStore, downloader) } // change(add)-hyungchul-2026-10-11-02:40 설치기 싱글턴

    /** VAD 엔진 생성기 — 계열의 카탈로그 VAD 모델이 READY 일 때만 그 폴더를 넘긴다(NONE/WEBRTC/TEN 은 모델 불필요). */
    val vadEngineFactory: VadEngineFactory by lazy { VadEngineFactory(::vadModelDir) } // change(add)-hyungchul-2026-10-11-02:40 VAD 생성기 싱글턴

    // change(add)-hyungchul-2026-10-11-02:40
    /**
     * 목적: VAD 계열의 설치된 모델 폴더 조회
     * 기능: ModelCatalog.vadModel(family) 가 있고 그 모델이 READY 면 models/<id>, 아니면 null(→ VadEngineFactory 가 VadUnavailableException).
     *       다른 계열·다른 모델로 대체하지 않는다.
     * 입력: family(VAD 계열)
     * 출력: 없음
     * 리턴: 모델 폴더 또는 null
     */
    private fun vadModelDir(family: VadFamily): File? =
        ModelCatalog.vadModel(family)?.let { spec -> // 계열의 카탈로그 모델(없으면 null)
            if (modelStore.state(spec) == InstallState.READY) modelStore.modelDir(spec) else null // READY 일 때만 폴더
        }

    // change(add)-hyungchul-2026-10-11-02:40
    /**
     * 목적: 실제 의존성으로 배치 실행기 조립
     * 기능: engineFactory = ManagedEngine.factory(modelStore)(acquire → READY 확인 → EngineFactory.create, close 때 release),
     *       vadFactory = vadEngineFactory.create, samplerFactory = 항목마다 새 ResourceSampler, env = envSnapshotOrNull()(Exception 만 null + 로그),
     *       device = 지금 수집한 DeviceInfo. 실행(run)마다 새로 만든다(실행기 자체는 상태가 없지만 device 를 실행 시점 값으로 맞추기 위함).
     * 입력: 없음
     * 출력: 기기 정보 수집·폴더 조회 실패 시 그 예외
     * 리턴: BatchRunner
     */
    fun newBatchRunner(): BatchRunner = BatchRunner(
        dirs = dirs, // 앱 폴더
        engineFactory = ManagedEngine.factory(modelStore), // 사용 횟수를 묶은 엔진 생성
        vadFactory = vadEngineFactory::create, // VAD 엔진 생성(모델 없으면 VadUnavailableException)
        samplerFactory = { ResourceSampler() }, // 항목마다 새 샘플러(1회용)
        env = ::envSnapshotOrNull, // 환경 스냅샷(읽기 실패는 null — 추정치로 채우지 않음) // change(add)-hyungchul-2026-10-11-03:00 runCatching → Exception 만 잡고 로그
        device = DeviceInfo.collect(this), // 기기 정보
    )

    // change(add)-hyungchul-2026-10-11-03:00
    /**
     * 목적: 환경 스냅샷 읽기(실패 시 null)
     * 기능: EnvSnapshot.snapshot 을 부르고, Exception 이면 원인을 Log.w 로 남기고 null(측정 불가 값은 null). 취소·Error 는 삼키지 않는다.
     * 입력: 없음
     * 출력: 실패 시 로그
     * 리턴: EnvSnapshot 또는 null
     */
    private fun envSnapshotOrNull(): EnvSnapshot? = try {
        EnvSnapshot.snapshot(this) // 발열·배터리 읽기
    } catch (e: Exception) {
        Log.w(TAG, "환경 스냅샷 읽기 실패(이 항목의 env 값은 null)", e) // 원인 기록
        null // 측정 불가
    }

    companion object {
        private const val TAG = "App" // 로그 태그 // change(add)-hyungchul-2026-10-11-03:00

        // change(add)-hyungchul-2026-10-11-02:40
        /**
         * 목적: 컨텍스트에서 App 인스턴스 얻기
         * 기능: applicationContext 를 App 으로 변환한다.
         * 입력: context(아무 컨텍스트)
         * 출력: Manifest 의 Application 이 App 이 아니면 ClassCastException
         * 리턴: App
         */
        fun from(context: Context): App = context.applicationContext as App // 전역 인스턴스
    }
}
