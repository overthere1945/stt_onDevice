// 파일명: VadPresetsTest.kt
// 목적: VAD 프리셋 레지스트리(26종)의 개수·id·값·상업 이용 구분 검증
// 기능: 프리셋 수/id 중복, Silero 8행 값 정확성, 타 계열 대표값, 상업 이용 판정, byId 오류를 확인한다.
package com.cowork.stt_ondevice.vad // VAD 테스트 패키지

import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 상태
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: spec §7.3 프리셋 값 회귀 방지
 * 기능: 레지스트리의 모든 숫자가 spec 표와 동일한지 확인한다.
 */
class VadPresetsTest {

    /**
     * 목적: 프리셋 개수·id 유일성 검증
     * 기능: 26 종이며 id 가 모두 다르고 계열별 개수가 spec 과 같은지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun presetCount() {
        val all = VadPresets.all // 전체 프리셋
        assertEquals(26, all.size) // 총 26 종
        assertEquals(26, all.map { it.id }.toSet().size) // id 중복 없음
        val byFamily = all.groupingBy { it.family }.eachCount() // 계열별 개수
        assertEquals(8, byFamily[VadFamily.SILERO]) // Silero 8
        assertEquals(4, byFamily[VadFamily.PYANNOTE]) // pyannote 4
        assertEquals(2, byFamily[VadFamily.FSMN]) // FSMN 2
        assertEquals(3, byFamily[VadFamily.WEBRTC]) // WebRTC 3
        assertEquals(2, byFamily[VadFamily.TEN]) // TEN 2
        assertEquals(2, byFamily[VadFamily.FIRERED]) // FireRed 2
        assertEquals(1, byFamily[VadFamily.MARBLENET]) // MarbleNet 1
        assertEquals(3, byFamily[VadFamily.YAMNET]) // Yamnet 3
        assertEquals(1, byFamily[VadFamily.NONE]) // NONE 1
    }

    /**
     * 목적: Silero 8행 값 검증
     * 기능: spec §7.3 Silero 표를 (id, th, minSpeech, minSilence, pad, maxSpeech) 튜플로 그대로 대조한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun sileroValuesExact() {
        val inf = Double.POSITIVE_INFINITY // 무제한 최대 발화
        val expected = listOf( // spec 표 그대로
            listOf("SILERO", 0.5f, 250, 400, 150, inf),
            listOf("SILERO_SAFE", 0.3f, 150, 600, 300, inf),
            listOf("SILERO_FW", 0.5f, 0, 2000, 400, inf),
            listOf("SILERO_DEFAULT", 0.5f, 250, 100, 30, inf),
            listOf("SILERO_SHERPA", 0.5f, 250, 500, 0, 20.0),
            listOf("SILERO_QWEN3", 0.5f, 1500, 500, 30, inf),
            listOf("SILERO_WS", 0.5f, 0, 500, 100, inf),
            listOf("SILERO_USER", 0.4f, 250, 300, 100, inf),
        )
        for (row in expected) { // 행마다
            val p = VadPresets.byId(row[0] as String) // 프리셋 조회
            assertEquals(VadFamily.SILERO, p.family) // 계열
            val s = p.params as SileroParams // 파라미터
            assertEquals(row[0] as String, row[1] as Float, s.threshold, 0f) // threshold
            assertEquals(row[0] as String, row[2], s.minSpeechMs) // minSpeech
            assertEquals(row[0] as String, row[3], s.minSilenceMs) // minSilence
            assertEquals(row[0] as String, row[4], s.speechPadMs) // pad
            assertEquals(row[0] as String, row[5] as Double, s.maxSpeechSec, 0.0) // maxSpeech
            assertTrue(row[0] as String, p.source.isNotBlank()) // 출처 비어있지 않음
        }
        // 주의 문구가 필요한 프리셋은 note 가 있어야 한다
        for (id in listOf("SILERO_SHERPA", "SILERO_WS", "SILERO_FW")) assertTrue(id, VadPresets.byId(id).note.isNotBlank())
    }

    /**
     * 목적: Silero 외 계열 대표값 검증
     * 기능: 계열마다 spec 의 대표 프리셋 값을 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun otherFamiliesSpotCheck() {
        val pya = VadPresets.byId("PYA2_VOXCONVERSE").params as PyannoteParams // pyannote
        assertEquals(0.767f, pya.onset, 0f) // onset
        assertEquals(0.713f, pya.offset, 0f) // offset
        assertEquals(0.182, pya.minDurationOnSec, 0.0) // min_on
        assertEquals(0.501, pya.minDurationOffSec, 0.0) // min_off
        assertTrue(VadPresets.byId("PYA2_VOXCONVERSE").note.isNotBlank()) // 주의 문구

        val fsmn = VadPresets.byId("FSMN_ASR30").params as FsmnParams // FSMN
        assertEquals(30000, fsmn.maxSingleSegmentTime) // 30 s
        val fsmnDef = VadPresets.byId("FSMN_DEFAULT").params as FsmnParams // FSMN 기본
        assertEquals(60000, fsmnDef.maxSingleSegmentTime) // 60 s
        assertEquals(800, fsmnDef.maxEndSilenceTime) // end silence
        assertEquals(-100f, fsmnDef.snrThres, 0f) // snr
        assertEquals(1e-4f, fsmnDef.fePriorThres, 0f) // fe prior
        // change(add)-hyungchul-2026-10-10-13:25 R11: ModelScope ONNX config(20000) 과 다르다는 주의 문구
        assertTrue(VadPresets.byId("FSMN_DEFAULT").note.contains("20000")) // 주의 문구에 20000 언급
        assertEquals(-0.1f, fsmnDef.speechNoiseThreshLow, 0f) // low

        val aw = VadPresets.byId("WEBRTC_ANDROIDVAD").params as WebRtcParams // android-vad
        assertEquals(3, aw.mode) // mode
        assertEquals(20, aw.frameMs) // 프레임
        assertEquals(WebRtcStyle.HANGOVER, aw.style) // 스타일
        assertEquals(50, aw.speechMs) // speech
        assertEquals(300, aw.silenceMs) // silence
        val lib = VadPresets.byId("WEBRTC_LIB_DEFAULT").params as WebRtcParams // libfvad 기본
        assertEquals(0, lib.mode) // mode 0
        assertEquals(WebRtcStyle.RAW, lib.style) // 후처리 없음
        val py = VadPresets.byId("WEBRTC_PYEXAMPLE").params as WebRtcParams // py-webrtcvad
        assertEquals(WebRtcStyle.RING_BUFFER, py.style) // ring buffer
        assertEquals(300, py.paddingMs) // padding
        assertEquals(0.9f, py.ratio, 0f) // 90%
        assertTrue(VadPresets.byId("WEBRTC_PYEXAMPLE").note.isNotBlank()) // mode 주의 문구

        val ten = VadPresets.byId("TEN_SHERPA").params as SileroParams // TEN sherpa
        assertEquals(VadFamily.TEN, VadPresets.byId("TEN_SHERPA").family) // 계열 TEN
        assertEquals(0.5f, ten.threshold, 0f) // th
        assertEquals(250, ten.minSpeechMs) // minSpeech
        assertEquals(500, ten.minSilenceMs) // minSilence
        assertEquals(0, ten.speechPadMs) // pad 0
        assertEquals(20.0, ten.maxSpeechSec, 0.0) // max 20 s
        val tenDef = VadPresets.byId("TEN_DEFAULT").params as HangoverParams // TEN 기본
        assertEquals(HangoverParams(0.5f, 0, 0), tenDef) // 후처리 없음

        val fr = VadPresets.byId("FIRERED_OFFLINE").params as FireRedParams // FireRed offline
        assertEquals(FireRedMode.OFFLINE, fr.mode) // 모드
        assertEquals(0.4f, fr.threshold, 0f) // th
        assertEquals(5, fr.smoothWindow) // smooth
        assertEquals(20, fr.minSpeechFrames) // min speech
        assertEquals(2000, fr.maxSpeechFrames) // max speech
        assertEquals(20, fr.minSilenceFrames) // min silence
        val fs = VadPresets.byId("FIRERED_STREAM").params as FireRedParams // FireRed stream
        assertEquals(FireRedMode.STREAM, fs.mode) // 모드
        assertEquals(0.5f, fs.threshold, 0f) // th
        assertEquals(0, fs.smoothWindow) // smooth 없음
        assertEquals(8, fs.minSpeechFrames) // min speech
        assertEquals(5, fs.padStartFrames) // pad start

        val nemo = VadPresets.byId("MARBLENET_NEMO").params as NemoParams // MarbleNet
        assertEquals(NemoParams(0.3f, 0.3f, 0.2, 0.2, 0.2, 0.2, true), nemo) // NeMo 기본

        val y = VadPresets.byId("YAMNET_VERY_AGGRESSIVE").params as HangoverParams // Yamnet
        assertEquals(0.85f, y.threshold, 0f) // th
        assertEquals(30, y.speechMs) // speech
        assertEquals(30, y.silenceMs) // silence
        assertEquals(0.3f, (VadPresets.byId("YAMNET_NORMAL").params as HangoverParams).threshold, 0f) // normal
        assertEquals(0.5f, (VadPresets.byId("YAMNET_AGGRESSIVE").params as HangoverParams).threshold, 0f) // aggressive

        val none = VadPresets.byId("NONE") // 기준선
        assertEquals(VadFamily.NONE, none.family) // 계열
        assertEquals(NoneParams, none.params) // 파라미터
    }

    /**
     * 목적: 상업 이용 구분 검증
     * 기능: 계열별 판정이 spec §7.2 와 같은지, 모든 계열이 판정 가능한지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun commercialStatus() {
        for (f in listOf(VadFamily.SILERO, VadFamily.PYANNOTE, VadFamily.WEBRTC, VadFamily.FIRERED, VadFamily.MARBLENET, VadFamily.YAMNET, VadFamily.NONE))
            assertEquals(f.name, CommercialStatus.ALLOWED, VadPresets.commercialOf(f)) // 허용
        assertEquals(CommercialStatus.CONDITIONAL, VadPresets.commercialOf(VadFamily.FSMN)) // 조건부
        assertEquals(CommercialStatus.CONDITIONAL, VadPresets.commercialOf(VadFamily.TEN)) // 조건부
    }

    /**
     * 목적: 없는 id 조회 처리 검증
     * 기능: 조용한 대체 없이 예외가 나는지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun byIdUnknownThrows() {
        try {
            VadPresets.byId("NOPE") // 존재하지 않는 id
            fail("예외가 나야 한다") // 도달하면 실패
        } catch (e: NoSuchElementException) { // 기대한 예외
            assertTrue(e.message!!.contains("NOPE")) // 메시지에 id 포함
        }
    }
}
