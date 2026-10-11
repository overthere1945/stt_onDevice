// 파일명: PresetParamCodecTest.kt
// 목적: USER 프리셋 값 변환기(PresetParamCodec)의 왕복·검증 규칙 확인
// 기능: 기본 프리셋 전체의 map 왕복과 describeParams 동일성, 범위·형식 위반 거부, 키 누락·미지 키 거부, NONE 거부, 필드 목록을 검증한다.
package com.cowork.stt_ondevice.vad // VAD 테스트 패키지

import com.cowork.stt_ondevice.output.PresetEntry // describeParams 제공
import org.junit.Assert.assertEquals // 같음 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-11-20:08
/**
 * 목적: PresetParamCodec 회귀 방지
 * 기능: 기본 프리셋 25종(NONE 제외)이 손실 없이 문자열 map 으로 오가고, 잘못된 값은 한국어 사유로 거부되는지 확인한다.
 */
class PresetParamCodecTest {

    /**
     * 목적: 왕복 무손실 검증
     * 기능: NONE 을 뺀 모든 기본 프리셋에 대해 fromMap(toMap(p)) 이 원본과 같고 describeParams 문자열도 같은지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun roundTripsAllBuiltInPresets() {
        val targets = VadPresets.all.filter { it.family != VadFamily.NONE } // NONE 제외
        assertEquals(25, targets.size) // 25 종 확인
        for (p in targets) { // 프리셋마다
            val map = PresetParamCodec.toMap(p.params) // 문자열 map
            val back = PresetParamCodec.fromMap(p.params, map).getOrElse { throw AssertionError("${p.id}: ${it.message}") } // 복원
            assertEquals(p.id, p.params, back) // 파라미터 동일
            assertEquals(p.id, PresetEntry.describeParams(p.params), PresetEntry.describeParams(back)) // 표기 동일
            assertEquals(p.id, p.params.let { PresetEntry.describeParams(it) }, // toMap 의 키·표기가 describeParams 와 같은지
                map.entries.joinToString(",") { "${it.key}=${it.value}" }) // 선언 순서 그대로 이어 붙인 값과 비교
            assertEquals(p.id, map.keys.toList(), PresetParamCodec.fields(p.params).map { it.key }) // 필드 키 = map 키
        }
    }

    /**
     * 목적: 범위 위반 거부 검증
     * 기능: Silero threshold 1.2, WebRTC frameMs 25, FSMN 정수 필드 소수를 각각 거부하고 사유에 키 이름이 들어가는지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun rejectsOutOfRange() {
        val silero = VadPresets.byId("SILERO").params // Silero 기준
        val r1 = PresetParamCodec.fromMap(silero, PresetParamCodec.toMap(silero) + ("threshold" to "1.2")) // 임계값 초과
        assertTrue(r1.isFailure) // 실패
        assertTrue(r1.exceptionOrNull()!!.message!!.contains("threshold")) // 사유에 키
        assertEquals("threshold: 0~1 사이여야 합니다", r1.exceptionOrNull()!!.message) // 지정 형식

        val webrtc = VadPresets.byId("WEBRTC_ANDROIDVAD").params // WebRTC 기준
        val r2 = PresetParamCodec.fromMap(webrtc, PresetParamCodec.toMap(webrtc) + ("frameMs" to "25")) // 허용 외 프레임
        assertTrue(r2.isFailure) // 실패
        assertTrue(r2.exceptionOrNull()!!.message!!.startsWith("frameMs:")) // 사유 키

        val fsmn = VadPresets.byId("FSMN_DEFAULT").params // FSMN 기준
        val r3 = PresetParamCodec.fromMap(fsmn, PresetParamCodec.toMap(fsmn) + ("maxEndSilenceTime" to "80.5")) // 정수에 소수
        assertTrue(r3.isFailure) // 실패
        assertTrue(r3.exceptionOrNull()!!.message!!.startsWith("maxEndSilenceTime:")) // 사유 키
    }

    /**
     * 목적: 필드별 경계·형식 규칙 검증
     * 기능: 음수 ms, WebRTC mode 4, FireRed mode 오타, maxSpeechSec 0/inf, Double 필드 inf, 숫자 아닌 값, NaN 을 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun boundaryAndFormatRules() {
        val silero = VadPresets.byId("SILERO").params // Silero 기준
        val base = PresetParamCodec.toMap(silero) // 기준 map
        assertTrue(PresetParamCodec.fromMap(silero, base + ("minSpeechMs" to "-1")).isFailure) // 음수 ms 거부
        assertTrue(PresetParamCodec.fromMap(silero, base + ("minSpeechMs" to "0")).isSuccess) // 0 허용
        assertTrue(PresetParamCodec.fromMap(silero, base + ("threshold" to "0.1")).isSuccess) // threshold-0.15 음수여도 허용
        assertTrue(PresetParamCodec.fromMap(silero, base + ("threshold" to "NaN")).isFailure) // NaN 거부
        assertTrue(PresetParamCodec.fromMap(silero, base + ("threshold" to "abc")).isFailure) // 숫자 아님 거부
        assertTrue(PresetParamCodec.fromMap(silero, base + ("threshold" to "0.5f")).isFailure) // 접미사 거부
        assertTrue(PresetParamCodec.fromMap(silero, base + ("maxSpeechSec" to "0")).isFailure) // 0 거부
        assertTrue(PresetParamCodec.fromMap(silero, base + ("maxSpeechSec" to "-3")).isFailure) // 음수 거부
        assertEquals(Double.POSITIVE_INFINITY, (PresetParamCodec.fromMap(silero, base + ("maxSpeechSec" to "inf")).getOrThrow() as SileroParams).maxSpeechSec, 0.0) // inf 허용
        assertEquals(12.5, (PresetParamCodec.fromMap(silero, base + ("maxSpeechSec" to "12.5")).getOrThrow() as SileroParams).maxSpeechSec, 0.0) // 양수 허용
        val pya = VadPresets.byId("PYA3_DEFAULT").params // pyannote 기준
        assertTrue(PresetParamCodec.fromMap(pya, PresetParamCodec.toMap(pya) + ("minDurationOnSec" to "inf")).isFailure) // 다른 Double 은 inf 거부
        assertTrue(PresetParamCodec.fromMap(pya, PresetParamCodec.toMap(pya) + ("onset" to " 0.6 ")).isSuccess) // 앞뒤 공백은 제거
        val webrtc = VadPresets.byId("WEBRTC_PYEXAMPLE").params // WebRTC 기준
        assertTrue(PresetParamCodec.fromMap(webrtc, PresetParamCodec.toMap(webrtc) + ("mode" to "4")).isFailure) // mode 0~3 밖
        assertTrue(PresetParamCodec.fromMap(webrtc, PresetParamCodec.toMap(webrtc) + ("style" to "HANGOVER")).isSuccess) // style 선택
        assertTrue(PresetParamCodec.fromMap(webrtc, PresetParamCodec.toMap(webrtc) + ("style" to "hangover")).isFailure) // 대소문자 틀림 거부
        val fire = VadPresets.byId("FIRERED_STREAM").params // FireRed 기준
        assertTrue(PresetParamCodec.fromMap(fire, PresetParamCodec.toMap(fire) + ("mode" to "OFFLINE")).isSuccess) // mode 선택
        assertTrue(PresetParamCodec.fromMap(fire, PresetParamCodec.toMap(fire) + ("mode" to "LIVE")).isFailure) // 없는 mode 거부
        val nemo = VadPresets.byId("MARBLENET_NEMO").params // NeMo 기준
        assertTrue(PresetParamCodec.fromMap(nemo, PresetParamCodec.toMap(nemo) + ("filterSpeechFirst" to "false")).isSuccess) // 불리언 허용
        assertTrue(PresetParamCodec.fromMap(nemo, PresetParamCodec.toMap(nemo) + ("filterSpeechFirst" to "yes")).isFailure) // 불리언 아님 거부
    }

    /**
     * 목적: 키 누락·미지 키·NONE 거부 검증
     * 기능: 키가 빠지거나 모르는 키가 있으면 실패하고, NONE 템플릿은 필드 없음·변환 실패인지 본다. TEN 은 기준 타입을 따른다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun missingUnknownAndNone() {
        val silero = VadPresets.byId("SILERO").params // Silero 기준
        val full = PresetParamCodec.toMap(silero) // 전체 map
        val missing = PresetParamCodec.fromMap(silero, full - "speechPadMs") // 키 누락
        assertEquals("speechPadMs: 값이 없습니다", missing.exceptionOrNull()!!.message) // 누락 사유
        val extra = PresetParamCodec.fromMap(silero, full + ("bogus" to "1")) // 미지 키
        assertEquals("bogus: 알 수 없는 항목입니다", extra.exceptionOrNull()!!.message) // 미지 사유
        assertTrue(PresetParamCodec.fields(NoneParams).isEmpty()) // NONE 필드 없음
        assertTrue(PresetParamCodec.fromMap(NoneParams, emptyMap()).isFailure) // NONE 변환 거부
        assertEquals(listOf("threshold", "speechMs", "silenceMs"), PresetParamCodec.fields(VadPresets.byId("TEN_DEFAULT").params).map { it.key }) // TEN 행오버
        assertEquals(5, PresetParamCodec.fields(VadPresets.byId("TEN_SHERPA").params).size) // TEN Silero
    }
}
