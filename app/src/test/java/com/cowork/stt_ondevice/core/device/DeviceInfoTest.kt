// 파일명: DeviceInfoTest.kt
// 목적: DeviceInfo.kt 의 순수 변환 함수(SoC 정규화·headroom·배터리 %·충전 판정)를 JVM 에서 검증
// 기능: 측정 불가 값이 0·추정치가 아니라 null 로 나오는지 확인한다. 실제 기기 값 수집은 androidTest(DeviceInfoInstrumentedTest)가 담당.
package com.cowork.stt_ondevice.core.device // 기기·자원 측정 패키지

import android.os.BatteryManager // 충전 상태 상수
import org.junit.Assert.assertEquals // 값 비교
import org.junit.Assert.assertFalse // false 확인
import org.junit.Assert.assertNull // null 확인
import org.junit.Assert.assertTrue // true 확인
import org.junit.Test // 테스트 표시

// change(add)-hyungchul-2026-10-10-21:58
class DeviceInfoTest {

    @Test
    fun unknownOrBlankSocIsNull() {
        assertNull(normalizeSocModel(null)) // null 입력
        assertNull(normalizeSocModel("")) // 빈 문자열
        assertNull(normalizeSocModel("   ")) // 공백
        assertNull(normalizeSocModel("unknown")) // unknown
        assertNull(normalizeSocModel("UNKNOWN")) // 대소문자 무시
        assertEquals("SM8450", normalizeSocModel(" SM8450 ")) // 정상 값은 trim
    }

    @Test
    fun nanHeadroomIsNull() {
        assertNull(headroomOrNull(Float.NaN)) // NaN → null
        assertNull(headroomOrNull(Float.POSITIVE_INFINITY)) // 무한대 → null
        assertEquals(0.75f, headroomOrNull(0.75f)!!, 0f) // 정상 값 유지
        assertEquals(0f, headroomOrNull(0f)!!, 0f) // 0 도 유효한 측정값
    }

    @Test
    fun batteryPercent() {
        assertEquals(50, batteryPctOrNull(50, 100)) // 일반
        assertEquals(33, batteryPctOrNull(1, 3)) // 반올림
        assertEquals(100, batteryPctOrNull(120, 100)) // 상한 제한
        assertNull(batteryPctOrNull(-1, 100)) // level 못 읽음
        assertNull(batteryPctOrNull(50, -1)) // scale 못 읽음
        assertNull(batteryPctOrNull(50, 0)) // 0 으로 나누기 방지
    }

    @Test
    fun chargingStatus() {
        assertTrue(isChargingOrNull(BatteryManager.BATTERY_STATUS_CHARGING)!!) // 충전 중
        assertTrue(isChargingOrNull(BatteryManager.BATTERY_STATUS_FULL)!!) // 전원 연결 완충
        assertFalse(isChargingOrNull(BatteryManager.BATTERY_STATUS_DISCHARGING)!!) // 방전
        assertFalse(isChargingOrNull(BatteryManager.BATTERY_STATUS_NOT_CHARGING)!!) // 충전 안 함
        assertNull(isChargingOrNull(BatteryManager.BATTERY_STATUS_UNKNOWN)) // 알 수 없음
        assertNull(isChargingOrNull(-1)) // 미정의
    }
}
