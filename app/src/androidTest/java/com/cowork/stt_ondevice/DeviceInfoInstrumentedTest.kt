// 파일명: DeviceInfoInstrumentedTest.kt (기기 계측 테스트)
// 목적: 실제 기기에서 DeviceInfo.collect / EnvSnapshot.snapshot / 기본 PSS·CPU 읽기가 동작하는지 검증
// 기능: - deviceInfoMatchesBuild: collect 결과가 Build 값과 같고 RAM·ABI·앱 버전이 채워졌는지 확인(모델은 logcat 에도 기록)
//       - envSnapshotHasThermalStatus: thermalStatus 가 null 이 아니고 0~6 범위인지, 배터리 %·충전 여부가 읽히는지 확인(headroom 은 null 가능 → logcat 기록)
//       - defaultReadersAndSamplerWork: 실제 Debug.getPss/Process.getElapsedCpuTime 으로 ResourceSampler 를 짧게 돌려 PSS>0, 표본 ≥2 확인
// 실행: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cowork.stt_ondevice.DeviceInfoInstrumentedTest
package com.cowork.stt_ondevice // 앱 패키지(기존 계측 테스트와 같은 위치)

import android.os.Build // 기기 정보
import android.util.Log // logcat
import androidx.test.platform.app.InstrumentationRegistry // 계측 컨텍스트
import androidx.test.ext.junit.runners.AndroidJUnit4 // 안드로이드 JUnit4 러너
import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.core.device.ResourceSampler // 자원 샘플러
import kotlinx.coroutines.CoroutineScope // 코루틴 범위
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.SupervisorJob // 부모 실패 격리 Job
import kotlinx.coroutines.cancel // 범위 취소
import org.junit.Assert.assertEquals // 값 비교
import org.junit.Assert.assertNotNull // null 아님 확인
import org.junit.Assert.assertTrue // 조건 확인
import org.junit.Test // 테스트 표시
import org.junit.runner.RunWith // 러너 지정

// change(add)-hyungchul-2026-10-10-21:58
@RunWith(AndroidJUnit4::class)
class DeviceInfoInstrumentedTest {
    private val tag = "DeviceInfoTest" // logcat 태그

    @Test
    fun deviceInfoMatchesBuild() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext // 앱 컨텍스트
        val info = DeviceInfo.collect(ctx) // 수집
        Log.i(tag, "DeviceInfo = $info") // 값 기록
        assertEquals(Build.MODEL, info.model) // 실제 모델과 일치
        assertEquals(Build.MANUFACTURER, info.manufacturer) // 제조사 일치
        assertEquals(Build.VERSION.SDK_INT, info.sdkInt) // API 레벨 일치
        assertEquals("arm64-v8a", info.abi) // 앱은 arm64-v8a 전용
        assertTrue("총 RAM 이 0 이하: ${info.totalRamMb}", info.totalRamMb > 1024) // 1 GB 초과
        assertTrue("앱 버전이 비어 있음", info.appVersion.isNotBlank()) // 버전 문자열
    }

    @Test
    fun envSnapshotHasThermalStatus() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext // 앱 컨텍스트
        val env = EnvSnapshot.snapshot(ctx) // 수집
        Log.i(tag, "EnvSnapshot = $env") // 값 기록(headroom 은 기기에 따라 null 일 수 있음)
        assertNotNull("thermalStatus 가 null", env.thermalStatus) // 열 상태는 읽혀야 한다
        assertTrue("열 상태 범위 밖: ${env.thermalStatus}", env.thermalStatus!! in 0..6) // THERMAL_STATUS_NONE..SHUTDOWN
        assertNotNull("batteryPct 가 null", env.batteryPct) // 배터리 %
        assertTrue("배터리 % 범위 밖: ${env.batteryPct}", env.batteryPct!! in 0..100) // 0..100
        assertNotNull("charging 이 null", env.charging) // 충전 여부
    }

    @Test
    fun defaultReadersAndSamplerWork() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default) // 기본 디스패처에서 도는 범위
        val sampler = ResourceSampler() // 기본 읽기 함수(Debug.getPss / Process.getElapsedCpuTime)
        sampler.start(scope) // 시작
        Thread.sleep(700) // 약 3 회 주기 표본
        val stats = sampler.stop() // 종료
        scope.cancel() // 범위 정리
        Log.i(tag, "ResourceStats = $stats") // 값 기록
        assertNotNull("PSS 피크가 null", stats.pssPeakMb) // 실제 기기에선 읽혀야 한다
        assertTrue("PSS 피크가 0 이하", stats.pssPeakMb!! > 0.0) // 양수
        assertTrue("표본이 2 미만: ${stats.samples}", stats.samples >= 2) // 시작·종료 포함
        assertTrue("CPU 시간이 음수", stats.cpuTimeSec >= 0.0) // 델타는 음수가 아님
    }
}
