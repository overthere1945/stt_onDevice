// 파일명: WebRtcVadTest.kt (테스트 소스)
// 목적: WebRtcVad(libfvad 네이티브 + Kotlin 후처리)의 Kotlin 쪽 프레임 처리가 골든과 정확히 일치하는지 JVM 에서 검증
// 기능: JVM 은 arm64 libstt_native.so 를 올릴 수 없으므로 네이티브 호출 자리에 가짜 함수를 주입한다.
//       가짜 함수는 (1) 엔진이 넘긴 mode·frameMs 가 프리셋 값인지 (2) 넘긴 PCM16 길이가 방식별 프레임 수 규칙
//       (RAW·HANGOVER = floor(N/L), RING_BUFFER = floor((N−1)/L), L = 16·frameMs)의 정확히 L 배인지
//       (3) float → PCM16 변환이 원래 int16 값을 그대로 복원했는지 확인한 뒤 골든 frameScores(py-webrtcvad 판정)를 돌려준다.
//       엔진 결과 구간이 WEBRTC_* 골든 12개의 expected 와 비트 단위로 같은지 본다. 실제 네이티브 판정은 기기 테스트(NativeSmokeTest)가 검증한다.
// change(add)-hyungchul-2026-10-10-15:26
package com.cowork.stt_ondevice.vad.impl // VAD 구현 테스트 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // float → PCM16 변환
import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.WebRtcParams // WebRTC 파라미터
import com.cowork.stt_ondevice.vad.WebRtcStyle // WebRTC 후처리 방식
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-15:26
/**
 * 목적: WebRtcVad 프레임 처리·후처리 연결의 골든 회귀 방지
 * 기능: 네이티브 판정 대신 골든 판정을 주입해 엔진 전체 경로(PCM 변환 → 프레임 수 결정 → 방식별 후처리 → VadOutput)를 검증한다.
 */
class WebRtcVadTest {

    /**
     * 목적: 테스트 입력 샘플 값 생성(결정적)
     * 기능: 샘플 위치마다 −32768..32767 전 범위를 고루 거치는 int16 값을 만든다(골든 판정을 주입하므로 내용은 판정과 무관).
     * 입력: i(샘플 위치)
     * 출력: 없음
     * 리턴: int16 범위 정수
     */
    private fun pattern(i: Int): Int = ((i.toLong() * 7919L) % 65536L).toInt() - 32768 // 소수 배수로 전 범위 순환

    /**
     * 목적: 길이 n 의 테스트 오디오(float) 생성
     * 기능: pattern 값을 앱 로딩과 같은 정규화(int16 / 32768f)로 float 로 만든다.
     * 입력: n(샘플 수)
     * 출력: 없음
     * 리턴: FloatArray(−1..1)
     */
    private fun audioOf(n: Int): FloatArray = FloatArray(n) { pattern(it) / 32768f } // PcmConverter.normalize 와 같은 식

    /**
     * 목적: WEBRTC_* 골든 12개 전부를 엔진 경로로 재현
     * 기능: 골든 개수를 고정하고, 골든마다 가짜 네이티브 함수를 주입한 엔진으로 detect 해 구간·점수·hop 이 골든과 같은지 본다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun matchesAllWebRtcGoldensThroughEngine() {
        val goldens = GoldenLoader.allFor("WEBRTC") // WEBRTC_* 구간 골든 전부
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("WEBRTC_"), goldens.size) // 빠진 파일 없음
        assertEquals("WEBRTC 골든은 3 프리셋 × 4 클립 = 12 개여야 함", 12, goldens.size) // 발견 개수 고정
        val failures = mutableListOf<String>() // 실패 메시지 모음
        for (g in goldens) { // 골든마다
            try { // 한 골든 실패가 나머지 비교를 막지 않도록
                checkOne(g) // 개별 비교
            } catch (e: AssertionError) { // 불일치
                failures += "${g.name}: ${e.message}" // 기록
            }
        }
        assertTrue("${failures.size}/${goldens.size} 개 불일치\n" + failures.joinToString("\n"), failures.isEmpty()) // 전부 통과해야 함
    }

    /**
     * 목적: 골든 1건을 엔진 경로로 비교
     * 기능: 가짜 네이티브가 mode·frameMs·PCM 길이·PCM 값을 확인하고 골든 판정을 돌려준다. 결과 구간은 expected 와 정확히 같아야 하고
     *       frameScores 는 골든 점수(0/1), frameHopSec 는 골든 hopSec 와 같아야 한다. 네이티브 호출은 정확히 1회여야 한다.
     * 입력: g(골든)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun checkOne(g: Golden) {
        val preset = VadPresets.byId(g.presetId) // 앱이 실제로 쓸 프리셋
        val p = preset.params as WebRtcParams // WebRTC 파라미터
        val n = g.totalSamples!! // 클립 전체 샘플 수 N
        val l = 16 * p.frameMs // 프레임당 샘플 수 L
        val frames = if (p.style == WebRtcStyle.RING_BUFFER) (n - 1) / l else n / l // 골든 프레임 수 규칙(README §6.4)
        assertEquals("골든 프레임 수가 규칙과 다름", frames, g.frameScores.size) // 골든 자체 확인
        var calls = 0 // 네이티브 호출 횟수
        val engine = WebRtcVad { mode, frameMs, pcm -> // 가짜 네이티브 판정 주입
            calls++ // 호출 기록
            assertEquals("mode", p.mode, mode) // 프리셋 모드 전달
            assertEquals("frameMs", p.frameMs, frameMs) // 프리셋 프레임 길이 전달
            assertEquals("PCM 길이 = 프레임 수 × L", frames * l, pcm.size) // 잔여 샘플 처리 규칙
            for (i in pcm.indices) if (pcm[i].toInt() != pattern(i)) throw AssertionError("PCM16 값 불일치 @$i: ${pcm[i]} ≠ ${pattern(i)}") // 변환 복원 확인
            BooleanArray(g.frameScores.size) { g.frameScores[it] >= 0.5f } // 골든 판정 반환
        }
        val out = engine.detect(audioOf(n), preset) // 엔진 실행
        assertEquals("네이티브 호출 횟수", 1, calls) // 정확히 1회
        assertEquals("구간(비트 단위 일치)", g.expected, out.segments) // 골든 구간과 동일
        assertEquals("frameHopSec", g.hopSec!!, out.frameHopSec!!, 0.0) // hop 동일
        assertArrayEquals("frameScores(0/1)", g.frameScores, out.frameScores!!, 0.0f) // 점수 동일
    }

    /**
     * 목적: N 이 L 의 배수일 때 잔여 규칙 검증(골든 클립은 배수가 아니라 이 경계를 확인하지 못함)
     * 기능: N = 10·L 이면 RAW·HANGOVER 는 10 프레임, RING_BUFFER 는 py-webrtcvad 의 엄격한 < 조건대로 9 프레임만 네이티브에 넘기는지 본다.
     *       빈 입력은 모든 방식에서 0 프레임이다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun frameCountFollowsStyleRuleAtExactMultiple() {
        val cases = mapOf("WEBRTC_LIB_DEFAULT" to 10, "WEBRTC_PYEXAMPLE" to 9, "WEBRTC_ANDROIDVAD" to 10) // 프리셋 → 기대 프레임 수
        for ((id, want) in cases) { // 프리셋마다
            val preset = VadPresets.byId(id) // 프리셋
            val l = 16 * (preset.params as WebRtcParams).frameMs // 프레임당 샘플 수
            var got = -1 // 네이티브에 넘어간 프레임 수
            val engine = WebRtcVad { _, _, pcm -> got = pcm.size / l; BooleanArray(pcm.size / l) } // 길이 기록, 전부 unvoiced
            val out = engine.detect(FloatArray(10 * l), preset) // N = 10·L
            assertEquals("$id 프레임 수", want, got) // 규칙대로
            assertEquals("$id 구간 없음", emptyList<SpeechSegment>(), out.segments) // 전부 unvoiced → 구간 0
            var emptyGot = -1 // 빈 입력 프레임 수
            WebRtcVad { _, _, pcm -> emptyGot = pcm.size; BooleanArray(0) }.detect(FloatArray(0), preset) // 빈 입력
            assertEquals("$id 빈 입력 0 샘플", 0, emptyGot) // 0 프레임
        }
    }

    /**
     * 목적: 네이티브 결과 개수 이상 시 조용히 넘어가지 않음 검증
     * 기능: 판정 개수가 넘긴 프레임 수와 다르면 IllegalStateException 이어야 한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun rejectsWrongFlagCount() {
        val engine = WebRtcVad { _, _, _ -> BooleanArray(1) } // 항상 1개만 돌려주는 잘못된 판정
        assertThrows(IllegalStateException::class.java) { engine.detect(FloatArray(16000), VadPresets.byId("WEBRTC_LIB_DEFAULT")) } // 개수 불일치 → 예외
    }

    /**
     * 목적: 다른 계열 프리셋 거부 검증
     * 기능: TEN 프리셋을 넣으면 IllegalArgumentException(다른 VAD 로 대체하지 않음).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun rejectsOtherFamilyPreset() {
        val engine = WebRtcVad { _, _, _ -> BooleanArray(0) } // 호출되면 안 되는 판정
        assertThrows(IllegalArgumentException::class.java) { engine.detect(FloatArray(480), VadPresets.byId("TEN_DEFAULT")) } // 계열 불일치
    }

    /**
     * 목적: float → PCM16 왕복 정확성(골든이 webrtcvad·TEN 에 넣은 원래 int16 과 같은 값을 네이티브에 넣는지)
     * 기능: 모든 int16 값 v 에 대해 PcmConverter.floatToPcm16(v / 32768f) == v 인지 전수 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun pcm16RoundTripIsExactForAllInt16() {
        val all = FloatArray(65536) { (it - 32768) / 32768f } // −32768..32767 전부를 float 로
        val back = PcmConverter.floatToPcm16(all) // 다시 PCM16 로
        for (i in back.indices) assertEquals("int16 ${i - 32768}", (i - 32768).toShort(), back[i]) // 원래 값과 동일
    }
}
