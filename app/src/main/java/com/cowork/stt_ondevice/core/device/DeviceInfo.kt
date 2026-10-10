// 파일명: DeviceInfo.kt
// 목적: 결과 JSON(manifest/detail)에 남길 기기 정보와 환경(발열·배터리) 스냅샷을 수집한다(스펙 §10).
// 기능: - DeviceInfo.collect(context): 제조사·모델·SoC·안드로이드 버전·ABI·총 RAM·앱 버전
//       - EnvSnapshot.snapshot(context): 열 상태·열 여유(headroom)·배터리 %·충전 여부. 읽을 수 없는 값은 null(0·추정치로 채우지 않음)
//       - 순수 변환 함수(normalizeSocModel/headroomOrNull/batteryPctOrNull/isChargingOrNull)는 JVM 테스트로 검증한다.
package com.cowork.stt_ondevice.core.device // 기기·자원 측정 패키지

import android.app.ActivityManager // 총 RAM 조회
import android.content.Context // 안드로이드 컨텍스트
import android.content.Intent // 배터리 sticky 브로드캐스트
import android.content.IntentFilter // 배터리 브로드캐스트 필터
import android.os.BatteryManager // 배터리 extra 상수
import android.os.Build // 기기·OS 정보
import android.os.PowerManager // 열 상태·headroom

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: 기기 정보 값 객체
 * 기능: 한 번의 실행(run) 동안 변하지 않는 기기·앱 정보를 담는다.
 * 입력: manufacturer/model(Build), socModel(Build.SOC_MODEL, 알 수 없으면 null), androidRelease/sdkInt(OS 버전),
 *       abi(기본 ABI), totalRamMb(총 RAM MB), appVersion("versionName (versionCode)" 형식)
 */
data class DeviceInfo(
    val manufacturer: String, // 제조사
    val model: String, // 모델명
    val socModel: String?, // SoC 모델(Build.SOC_MODEL), 알 수 없으면 null
    val androidRelease: String, // 안드로이드 버전 문자열(예: 16)
    val sdkInt: Int, // API 레벨
    val abi: String, // 기본(첫 번째) ABI
    val totalRamMb: Long, // 총 RAM(MB)
    val appVersion: String, // 앱 버전
) {
    companion object {
        /**
         * 목적: 현재 기기 정보 수집
         * 기능: Build·ActivityManager.MemoryInfo·PackageManager 에서 값을 읽는다(minSdk 31 이라 SOC_MODEL 은 항상 접근 가능).
         * 입력: context(안드로이드 컨텍스트)
         * 출력: 없음
         * 리턴: DeviceInfo
         */
        fun collect(context: Context): DeviceInfo {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager // 메모리 정보 서비스
            val memInfo = ActivityManager.MemoryInfo() // 메모리 정보 채움 대상
            am.getMemoryInfo(memInfo) // 총 RAM 등 채우기
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0) // 앱 패키지 정보
            return DeviceInfo(
                manufacturer = Build.MANUFACTURER, // 제조사
                model = Build.MODEL, // 모델
                socModel = normalizeSocModel(Build.SOC_MODEL), // SoC(unknown 이면 null)
                androidRelease = Build.VERSION.RELEASE, // 안드로이드 버전 문자열
                sdkInt = Build.VERSION.SDK_INT, // API 레벨
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(), // 기본 ABI(없으면 빈 문자열)
                totalRamMb = memInfo.totalMem / (1024L * 1024L), // 바이트 → MB
                appVersion = "${pkg.versionName.orEmpty()} (${pkg.longVersionCode})", // versionName (versionCode)
            )
        }
    }
}

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: 환경 스냅샷 값 객체(파일 시작/끝 시점의 발열·배터리)
 * 기능: 읽을 수 없는 값은 null 로 둔다.
 * 입력: thermalStatus(PowerManager.THERMAL_STATUS_* 0~6), thermalHeadroom(getThermalHeadroom(0), 미지원이면 null),
 *       batteryPct(0~100), charging(충전 중이거나 완충 상태로 전원 연결이면 true)
 */
data class EnvSnapshot(
    val thermalStatus: Int?, // 열 상태 코드
    val thermalHeadroom: Float?, // 열 여유(1.0 이면 스로틀링 임계), 미지원 null
    val batteryPct: Int?, // 배터리 %
    val charging: Boolean?, // 충전 여부
) {
    companion object {
        /**
         * 목적: 현재 환경 스냅샷 수집
         * 기능: PowerManager 에서 열 상태·headroom(forecastSeconds=0 → 현재 값), sticky ACTION_BATTERY_CHANGED 에서 배터리 %·충전 여부를 읽는다.
         *       각 항목은 서로 독립이라 하나가 실패해도 나머지는 채운다(실패한 항목만 null).
         * 입력: context(안드로이드 컨텍스트)
         * 출력: 없음
         * 리턴: EnvSnapshot
         */
        fun snapshot(context: Context): EnvSnapshot {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager // 전원 서비스
            val status = runCatching { pm?.currentThermalStatus }.getOrNull() // 열 상태(실패 시 null)
            val headroom = runCatching { pm?.getThermalHeadroom(0) }.getOrNull()?.let(::headroomOrNull) // headroom(NaN → null)
            val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) // sticky 배터리 인텐트(수신기 등록 없음)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1 // 현재 레벨
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1 // 최대 레벨
            val chargeStatus = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1 // 충전 상태 코드
            return EnvSnapshot(
                thermalStatus = status, // 열 상태
                thermalHeadroom = headroom, // 열 여유
                batteryPct = batteryPctOrNull(level, scale), // 배터리 %
                charging = if (battery == null) null else isChargingOrNull(chargeStatus), // 인텐트 없으면 null
            )
        }
    }
}

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: SoC 문자열 정규화
 * 기능: 공백이면 null, "unknown"(대소문자 무시)이면 null, 그 외는 trim 한 값.
 * 입력: raw(Build.SOC_MODEL)
 * 출력: 없음
 * 리턴: 의미 있는 SoC 문자열 또는 null
 */
internal fun normalizeSocModel(raw: String?): String? {
    val v = raw?.trim().orEmpty() // 앞뒤 공백 제거
    return if (v.isEmpty() || v.equals("unknown", ignoreCase = true)) null else v // 빈 값·unknown → null
}

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: thermal headroom 값 정규화
 * 기능: NaN(미지원·일시적 실패) 또는 무한대는 측정 불가로 보고 null.
 * 입력: v(PowerManager.getThermalHeadroom 반환값)
 * 출력: 없음
 * 리턴: 유한한 값 또는 null
 */
internal fun headroomOrNull(v: Float): Float? = if (v.isNaN() || v.isInfinite()) null else v // 비정상 값 → null

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: 배터리 % 계산
 * 기능: level/scale*100 을 반올림한다. level<0 이거나 scale<=0 이면 측정 불가로 null. 결과는 0..100 으로 제한.
 * 입력: level(EXTRA_LEVEL), scale(EXTRA_SCALE)
 * 출력: 없음
 * 리턴: 0..100 정수 또는 null
 */
internal fun batteryPctOrNull(level: Int, scale: Int): Int? {
    if (level < 0 || scale <= 0) return null // 값을 못 읽음
    return Math.round(level * 100f / scale).coerceIn(0, 100) // 백분율 반올림 후 범위 제한
}

// change(add)-hyungchul-2026-10-10-21:58
/**
 * 목적: 충전 여부 판정
 * 기능: CHARGING·FULL(전원 연결된 완충)은 true, DISCHARGING·NOT_CHARGING 은 false, 그 외(UNKNOWN·미정의)는 null.
 * 입력: status(EXTRA_STATUS)
 * 출력: 없음
 * 리턴: Boolean 또는 null
 */
internal fun isChargingOrNull(status: Int): Boolean? = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true // 충전 중/전원 연결 완충
    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false // 방전/충전 안 함
    else -> null // 알 수 없음
}
