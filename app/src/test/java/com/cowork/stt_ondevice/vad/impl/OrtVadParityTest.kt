// 파일명: OrtVadParityTest.kt
// 목적: ORT 기반 VAD(SileroVad·PyannoteVad·FsmnVad)의 모델 추론 결과가 원본 Python 참조(Task 7 골든)와 같은지 검증
// 기능: 골든 클립 4개(kr/en 20 s+77, 합성 8 s+123, kr 70 s+55)마다
//       (1) 모델 점수(Silero 확률 / pyannote 집계 speech 점수 / FSMN sil prob)를 scores_<FAMILY>_<clip>.json 과 비교
//           (Silero·pyannote |차| ≤ 1e-3, FSMN 은 FSMN_SCORE_TOL — 아래 상수 설명 참고. 실제 최대 차이 출력)
//       (2) detect() 끝단(모델 → 후처리) 구간을 프리셋 골든(SILERO_DEFAULT, PYA3_DEFAULT, FSMN_DEFAULT·FSMN_ASR30)과 비교한다.
//       FSMN 은 프레임 dB 를 골든 frameDb 와 비교하고, 골든에 없는 T % 6000 == 1 재처리 블록 경로를 70 s 클립 앞 960400 샘플(T = 6001)로
//       실행해 funasr_onnx 0.4.3 원본 실행값(테스트 상수, 출처는 함수 주석)과 비교한다.
//       모델(tools/golden/models/, gitignore)·클립(golden/audio/, gitignore)이 없으면 Assume 으로 건너뛴다(준비된 환경에서는 SKIPPED 없어야 함).
//       JVM 단위 테스트는 onnxruntime(JVM) 1.30.0 을, 앱은 onnxruntime-android 1.30.0 을 쓴다(같은 ai.onnxruntime API).
package com.cowork.stt_ondevice.vad.impl // VAD 구현 테스트 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // WAV → 16 kHz mono float
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 파싱
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.assertSegmentsClose // 구간 근사 비교
import com.cowork.stt_ondevice.vad.post.FsmnStateMachine // FSMN 블록 분할 규칙
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 준비물 없으면 건너뜀
import org.junit.Test // JUnit4 테스트 표시
import java.io.File // 모델·클립 파일
import kotlin.math.abs // 절댓값

// change(add)-hyungchul-2026-10-10-13:56
/**
 * 목적: ORT VAD 3종의 Python 참조 정합 회귀 방지
 * 기능: 계열마다 점수 정합 테스트와 detect() 끝단 구간 테스트를 둔다. 실제 최대 차이를 표준 출력에 남겨 보고서에 옮긴다.
 */
class OrtVadParityTest {

    /** 골든 클립 4개(파일명에서 .wav 를 뺀 이름). */
    private val clips = listOf(
        "kr_customer_01_20s_plus77", // 한국어 20 s + 77 샘플
        "en_customer_01_20s_plus77", // 영어 20 s + 77 샘플
        "synth_silence_noise_8s_plus123", // 합성 무음·잡음 8 s + 123 샘플
        "kr_customer_01_70s_plus55", // 한국어 70 s + 55 샘플(FSMN 다중 블록)
    )

    // ---------------------------------------------------------------- Silero

    /**
     * 목적: Silero 확률이 Python(silero-vad 6.2.3 OnnxWrapper)과 같은지 검증
     * 기능: 클립마다 probabilities() 를 scores_SILERO 골든과 비교한다(개수 = ceil(N/512), |차| ≤ 1e-3).
     * 입력: 없음
     * 출력: 클립별 최대 차이 출력
     * 리턴: 없음
     */
    @Test
    fun sileroProbsMatchPython() {
        SileroVad(requireModel(SILERO_MODEL)).use { vad -> // 모델 1개로 4 클립 처리(detect 마다 state 리셋)
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                val g = GoldenLoader.loadScores("SILERO", stem) // 골든 점수
                assertEquals("$stem totalSamples", g.totalSamples, audio.size) // 같은 클립인지
                val probs = vad.probabilities(audio) // 모델 확률
                assertEquals("$stem 창 수 = ceil(N/512)", (audio.size + 511) / 512, probs.size) // 창 수 규칙
                val maxDiff = assertScoresClose("SILERO $stem", g.frameScores, probs, SCORE_TOL) // 점수 비교
                println("SILERO 점수 $stem: 프레임 ${probs.size}, 최대 |차| = $maxDiff") // 보고용 출력
            }
        }
    }

    /**
     * 목적: Silero detect() 끝단(모델 → SileroTimestamps) 구간이 SILERO_DEFAULT 골든과 같은지 검증
     * 기능: 클립마다 detect 결과를 expectedSamples(정수 샘플)과 1 샘플 이내로 비교하고, 출력의 hop·점수를 확인한다.
     * 입력: 없음
     * 출력: 클립별 최대 경계 차이 출력
     * 리턴: 없음
     */
    @Test
    fun sileroDetectMatchesPresetGolden() {
        val preset = VadPresets.byId("SILERO_DEFAULT") // 비교 프리셋
        SileroVad(requireModel(SILERO_MODEL)).use { vad -> // 모델
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                val g = GoldenLoader.load("SILERO_SILERO_DEFAULT_$stem") // 구간 골든
                val out = vad.detect(audio, preset) // 끝단 실행
                assertEquals("hop", 0.032, out.frameHopSec!!, 0.0) // 프레임 간격
                assertArrayEquals("frameScores = probabilities", vad.probabilities(audio), out.frameScores!!, 0f) // 점수 = 확률(state 리셋으로 재현 가능)
                val maxDiff = segmentsMaxDiff(g.expected, out.segments) // 최대 경계 차이(초)
                assertSegmentsClose(g.expected, out.segments, 1.0 / 16000 + 1e-9) // 1 샘플 허용
                println("SILERO_DEFAULT 구간 $stem: ${out.segments.size} 개, 최대 경계 차이 = $maxDiff s") // 보고용 출력
            }
        }
    }

    // ---------------------------------------------------------------- pyannote

    /**
     * 목적: pyannote 집계 speech 점수·프레임 시각이 Python(pyannote.audio 3.4.0 Inference 경로)과 같은지 검증
     * 기능: 클립마다 speechScores() 를 scores_PYANNOTE 골든과 비교한다(점수 |차| ≤ 1e-3, 시각 |차| ≤ 1e-9).
     * 입력: 없음
     * 출력: 클립별 최대 차이 출력
     * 리턴: 없음
     */
    @Test
    fun pyannoteScoresMatchPython() {
        PyannoteVad(requireModel(PYANNOTE_MODEL)).use { vad -> // 모델(fp32 model.onnx)
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                val g = GoldenLoader.loadScores("PYANNOTE", stem) // 골든 점수
                assertEquals("$stem totalSamples", g.totalSamples, audio.size) // 같은 클립인지
                val (scores, times) = vad.speechScores(audio) // 모델 점수·시각
                val maxDiff = assertScoresClose("PYANNOTE $stem", g.frameScores, scores, SCORE_TOL) // 점수 비교
                val expTimes = g.frameTimesSec!! // 골든 시각
                assertEquals("$stem 시각 개수", expTimes.size, times.size) // 개수 일치
                val maxTimeDiff = expTimes.indices.maxOfOrNull { abs(expTimes[it] - times[it]) } ?: 0.0 // 최대 시각 차이
                assertTrue("$stem 프레임 시각 차이 $maxTimeDiff > 1e-9", maxTimeDiff <= 1e-9) // 시각 일치
                println("PYANNOTE 점수 $stem: 프레임 ${scores.size}, 최대 |차| = $maxDiff, 최대 시각 차이 = $maxTimeDiff s") // 보고용 출력
            }
        }
    }

    /**
     * 목적: pyannote detect() 끝단(모델 → PyannoteBinarize) 구간이 PYA3_DEFAULT 골든과 같은지 검증
     * 기능: 클립마다 detect 결과를 골든 expected 와 1 프레임(0.016875 s) 이내로 비교하고 hop 을 확인한다.
     * 입력: 없음
     * 출력: 클립별 최대 경계 차이 출력
     * 리턴: 없음
     */
    @Test
    fun pyannoteDetectMatchesPresetGolden() {
        val preset = VadPresets.byId("PYA3_DEFAULT") // 비교 프리셋
        PyannoteVad(requireModel(PYANNOTE_MODEL)).use { vad -> // 모델
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                val g = GoldenLoader.load("PYANNOTE_PYA3_DEFAULT_$stem") // 구간 골든
                val out = vad.detect(audio, preset) // 끝단 실행
                assertEquals("hop", 270.0 / 16000.0, out.frameHopSec!!, 1e-12) // 프레임 간격 0.016875 s
                assertNotNull("frameScores", out.frameScores) // 점수 포함
                val maxDiff = segmentsMaxDiff(g.expected, out.segments) // 최대 경계 차이(초)
                assertSegmentsClose(g.expected, out.segments, 270.0 / 16000.0 + 1e-9) // 1 프레임 허용
                println("PYA3_DEFAULT 구간 $stem: ${out.segments.size} 개, 최대 경계 차이 = $maxDiff s") // 보고용 출력
            }
        }
    }

    // ---------------------------------------------------------------- FSMN

    /**
     * 목적: FSMN sil prob·프레임 dB 가 Python(funasr_onnx 0.4.3 Fsmn_vad, model_quant.onnx)과 같은지 검증
     * 기능: 클립마다 scores() 의 전체 프레임 sil prob 을 scores_FSMN 골든과(|차| ≤ FSMN_SCORE_TOL), 프레임 dB 를 FSMN_DEFAULT 골든 frameDb 와 비교한다.
     *       |차| > 1e-3 인 프레임 수도 출력한다(보고용).
     *       블록 분할은 FsmnStateMachine.blockPlan 과 같아야 하며 70 s 클립은 2 블록(cache 이어 받음)이다.
     * 입력: 없음
     * 출력: 클립별 최대 차이 출력
     * 리턴: 없음
     */
    @Test
    fun fsmnScoresMatchPython() {
        FsmnVad(requireModelDir(FSMN_DIR)).use { vad -> // 모델 폴더(model_quant.onnx, am.mvn, config.yaml)
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                val g = GoldenLoader.loadScores("FSMN", stem) // 골든 sil prob
                assertEquals("$stem totalSamples", g.totalSamples, audio.size) // 같은 클립인지
                val s = vad.scores(audio) // 모델 점수
                assertEquals("$stem 프레임 수 T = 1 + (N − 400)/160", 1 + (audio.size - 400) / 160, s.silProb.size) // 프레임 수 규칙
                val maxDiff = assertScoresClose("FSMN $stem", g.frameScores, s.silProb, FSMN_SCORE_TOL) // sil prob 비교(특징 차이 허용폭)
                val over = g.frameScores.indices.count { abs(g.frameScores[it] - s.silProb[it]) > 1e-3f } // 1e-3 초과 프레임 수(보고용)
                val gDb = GoldenLoader.load("FSMN_FSMN_DEFAULT_$stem").frameDb!! // 골든 dB(원본 double 의 9 자리 표기)
                assertEquals("$stem dB 개수", gDb.size, s.frameDb.size) // 개수 일치
                val maxDbDiff = gDb.indices.maxOfOrNull { abs(gDb[it] - s.frameDb[it]) } ?: 0f // 최대 dB 차이
                assertTrue("$stem 프레임 dB 차이 $maxDbDiff > 1e-4", maxDbDiff <= 1e-4f) // dB 일치(9 자리 표기 반올림 이내)
                assertEquals("$stem 블록 수", FsmnStateMachine.blockPlan(s.silProb.size).size, s.blockSilProb.size) // 블록 분할 일치
                println("FSMN 점수 $stem: 프레임 ${s.silProb.size}, 블록 ${s.blockSilProb.size}, 최대 |차| = $maxDiff, |차| > 1e-3 프레임 $over 개, 최대 dB 차이 = $maxDbDiff") // 보고용 출력
            }
        }
    }

    /**
     * 목적: FSMN detect() 끝단(fbank→LFR→CMVN→ONNX→상태기계) 구간이 FSMN_DEFAULT·FSMN_ASR30 골든과 같은지 검증
     * 기능: 두 프리셋 × 4 클립 결과를 골든 expected 와 0.010 s 이내로 비교하고, ms 정수 구간이 정확히 같은지도 센다(정확 일치 기대).
     * 입력: 없음
     * 출력: 프리셋·클립별 최대 경계 차이와 정확 일치 여부 출력
     * 리턴: 없음
     */
    @Test
    fun fsmnSegmentsMatchPython() {
        FsmnVad(requireModelDir(FSMN_DIR)).use { vad -> // 모델 폴더
            for (pid in listOf("FSMN_DEFAULT", "FSMN_ASR30")) { // 프리셋마다
                val preset = VadPresets.byId(pid) // 프리셋
                for (stem in clips) { // 클립마다
                    val audio = loadClip(stem) // 파형
                    val g = GoldenLoader.load("FSMN_${pid}_$stem") // 구간 골든
                    val out = vad.detect(audio, preset) // 끝단 실행
                    assertEquals("hop", 0.010, out.frameHopSec!!, 0.0) // 프레임 간격
                    val maxDiff = segmentsMaxDiff(g.expected, out.segments) // 최대 경계 차이(초)
                    assertSegmentsClose(g.expected, out.segments, 0.010 + 1e-9) // 0.010 s 허용
                    val exact = g.expected == out.segments // ms/1000 값 정확 일치 여부
                    println("$pid 구간 $stem: ${out.segments.size} 개, 최대 경계 차이 = $maxDiff s, 정확 일치 = $exact") // 보고용 출력
                }
            }
        }
    }

    /**
     * 목적: 골든에 없는 FSMN T % 6000 == 1 재처리 블록 경로 검증(funasr_onnx 원본 실행값과 비교)
     * 기능: 70 s 클립 앞 960400 샘플(T = 1 + (960400 − 400)/160 = 6001)을 넣는다. funasr_onnx 0.4.3 은 블록
     *       [(0, 6001, final), (6000, 1, final)] 로 ONNX 를 두 번 부르며, 두 번째(재처리) 블록은 첫 블록의 out_cache 를 이어 받아
     *       마지막 프레임을 다시 추론한다. 기대값은 tools/golden/.venv 의 funasr_onnx 0.4.3 Fsmn_vad(quantize=True, 스레드 1)를
     *       gen_fsmn.py 와 같은 방식(기록용 E2EVadModel 하위 클래스)으로 같은 입력에 실행해 얻은 값이다(보고서 task-12-report.md 에 명령 기록):
     *       FSMN_DEFAULT [[1780, 40590], [40870, 44850], [45130, 60010]], FSMN_ASR30 [[1780, 31790], [31790, 40590], [40870, 44850], [45130, 60010]],
     *       첫 블록의 마지막 프레임 sil = 0.0014792850706726313, 재처리 블록 sil = 0.0014617517590522766.
     *       (프리셋 sil_to_speech 150 ms 에서는 1 프레임 재처리 블록이 구간을 만들 수 없어 결과는 재처리 점수와 무관하다.)
     * 입력: 없음
     * 출력: 재처리 점수 차이 출력
     * 리턴: 없음
     */
    @Test
    fun fsmnReprocessBlockMatchesFunasr() {
        FsmnVad(requireModelDir(FSMN_DIR)).use { vad -> // 모델 폴더
            val audio = loadClip("kr_customer_01_70s_plus55").copyOf(960400) // T = 6001 이 되도록 자름
            val s = vad.scores(audio) // 모델 점수
            assertEquals("프레임 수", 6001, s.silProb.size) // T
            assertEquals("블록 수(재처리 포함)", 2, s.blockSilProb.size) // 6001 프레임 블록 + 1 프레임 재처리 블록
            assertEquals("첫 블록 길이", 6001, s.blockSilProb[0].size) // 블록 0
            assertEquals("재처리 블록 길이", 1, s.blockSilProb[1].size) // 블록 1
            assertArrayEquals("전체 sil prob 은 첫 블록 결과(재처리 제외)", s.blockSilProb[0], s.silProb, 0f) // 전체 = 블록 0
            val firstPass = s.silProb[6000] // 첫 블록의 마지막 프레임 점수
            val reprocessed = s.blockSilProb[1][0] // 재처리 블록 점수(cache 이어 받음)
            assertEquals("첫 블록 마지막 프레임", 0.0014792850706726313, firstPass.toDouble(), 1e-4) // 원본 값(특징 차이로 ~2e-5 차)
            assertEquals("재처리 블록 점수", 0.0014617517590522766, reprocessed.toDouble(), 1e-4) // 원본 값(cache 이어 받은 재추론)
            assertTrue("재처리 블록이 실제로 다시 추론되어야 함(첫 추론 값 재사용이 아님)", firstPass != reprocessed) // cache 를 이어 받으면 값이 달라짐
            println("FSMN 재처리 블록: 첫 추론 $firstPass, 재추론 $reprocessed (원본 0.0014792851 / 0.0014617518)") // 보고용 출력
            val def = vad.detect(audio, VadPresets.byId("FSMN_DEFAULT")).segments // 기본 프리셋
            assertEquals(listOf(seg(1780, 40590), seg(40870, 44850), seg(45130, 60010)), def) // 원본 결과와 정확 일치
            val asr = vad.detect(audio, VadPresets.byId("FSMN_ASR30")).segments // 30 s 프리셋
            assertEquals(listOf(seg(1780, 31790), seg(31790, 40590), seg(40870, 44850), seg(45130, 60010)), asr) // 원본 결과와 정확 일치
        }
    }

    // ---------------------------------------------------------------- 공통

    /**
     * 목적: 다른 계열 프리셋·잘못된 params 를 조용히 처리하지 않는지 검증
     * 기능: 세 VAD 모두 다른 계열 프리셋이면 IllegalArgumentException 을 던져야 한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun detectRejectsOtherFamilyPreset() {
        val audio = FloatArray(16000) // 1 s 무음
        SileroVad(requireModel(SILERO_MODEL)).use { v -> // Silero
            assertThrows(IllegalArgumentException::class.java) { v.detect(audio, VadPresets.byId("PYA3_DEFAULT")) } // pyannote 프리셋 거부
        }
        PyannoteVad(requireModel(PYANNOTE_MODEL)).use { v -> // pyannote
            assertThrows(IllegalArgumentException::class.java) { v.detect(audio, VadPresets.byId("FSMN_DEFAULT")) } // FSMN 프리셋 거부
        }
        FsmnVad(requireModelDir(FSMN_DIR)).use { v -> // FSMN
            assertThrows(IllegalArgumentException::class.java) { v.detect(audio, VadPresets.byId("SILERO")) } // Silero 프리셋 거부
            assertThrows(IllegalArgumentException::class.java) { // 계열은 FSMN 이지만 params 가 다른 타입
                v.detect(audio, VadPresets.byId("FSMN_DEFAULT").copy(params = VadPresets.byId("SILERO").params)) // 잘못된 params
            }
        }
    }

    /**
     * 목적: 두 점수 배열 비교
     * 기능: 길이가 같고 모든 원소 |차| ≤ tol 인지 확인한다. 실패 시 최대 차이 위치를 메시지에 넣는다.
     * 입력: label(메시지 머리), expected(골든), actual(모델), tol(허용 오차)
     * 출력: 불일치 시 AssertionError
     * 리턴: 최대 |차|
     */
    private fun assertScoresClose(label: String, expected: FloatArray, actual: FloatArray, tol: Double): Double {
        assertEquals("$label 점수 개수", expected.size, actual.size) // 개수 일치
        var maxDiff = 0.0 // 최대 차이
        var at = -1 // 최대 차이 위치
        for (i in expected.indices) { // 원소마다
            val d = abs(expected[i].toDouble() - actual[i].toDouble()) // 차이
            if (d > maxDiff) { maxDiff = d; at = i } // 최대 갱신
        }
        assertTrue("$label 최대 |차| $maxDiff > $tol (프레임 $at)", maxDiff <= tol) // 허용 오차 이내
        return maxDiff // 보고용
    }

    /**
     * 목적: 구간 목록의 최대 경계 차이(보고용)
     * 기능: 개수가 같으면 순번별 시작·끝 |차| 의 최댓값, 다르면 +∞ 를 돌려준다(판정은 assertSegmentsClose 가 함).
     * 입력: expected, actual(구간 목록)
     * 출력: 없음
     * 리턴: 최대 차이(초)
     */
    private fun segmentsMaxDiff(expected: List<SpeechSegment>, actual: List<SpeechSegment>): Double {
        if (expected.size != actual.size) return Double.POSITIVE_INFINITY // 개수 다름
        return expected.indices.maxOfOrNull { maxOf(abs(expected[it].startSec - actual[it].startSec), abs(expected[it].endSec - actual[it].endSec)) } ?: 0.0 // 최대 경계 차이
    }

    /** 목적: ms 정수 쌍 → SpeechSegment(초 = ms / 1000.0, FsmnStateMachine 과 같은 식). 입력: startMs, endMs. 리턴: 구간. */
    private fun seg(startMs: Long, endMs: Long) = SpeechSegment(startMs / 1000.0, endMs / 1000.0) // ms → 초

    /**
     * 목적: 골든 클립 읽기
     * 기능: 클래스패스 golden/audio/<stem>.wav 를 WavReader → PcmConverter 로 16 kHz mono float(int16/32768)로 읽는다. 없으면 건너뜀.
     * 입력: stem(클립 이름, 확장자 제외)
     * 출력: 클립 없으면 AssumptionViolatedException
     * 리턴: 파형
     */
    private fun loadClip(stem: String): FloatArray {
        val url = javaClass.classLoader!!.getResource("golden/audio/$stem.wav") // 클립 리소스(gitignore)
        assumeTrue("골든 클립 없음: golden/audio/$stem.wav (tools/golden/make_clips.py 로 생성)", url != null) // 없으면 건너뜀
        return PcmConverter.toMono16k(WavReader.read(File(url!!.toURI()))).samples // 파일 → float 파형
    }

    /**
     * 목적: 모델 파일 경로 확인
     * 기능: 저장소 루트 기준 상대 경로의 파일이 있으면 돌려주고, 없으면 테스트를 건너뛴다.
     * 입력: rel(저장소 루트 기준 상대 경로)
     * 출력: 없으면 AssumptionViolatedException
     * 리턴: 모델 파일
     */
    private fun requireModel(rel: String): File {
        val f = File(repoRoot(), rel) // 절대 경로
        assumeTrue("모델 없음: $f (tools/golden/README_한국어.md §3 참고)", f.isFile) // 없으면 건너뜀
        return f // 모델 파일
    }

    /**
     * 목적: FSMN 모델 폴더 확인
     * 기능: model_quant.onnx·am.mvn·config.yaml 이 모두 있으면 폴더를, 아니면 테스트를 건너뛴다.
     * 입력: rel(저장소 루트 기준 상대 경로)
     * 출력: 없으면 AssumptionViolatedException
     * 리턴: 모델 폴더
     */
    private fun requireModelDir(rel: String): File {
        val d = File(repoRoot(), rel) // 절대 경로
        val ok = listOf("model_quant.onnx", "am.mvn", "config.yaml").all { File(d, it).isFile } // 3 파일 확인
        assumeTrue("FSMN 모델 폴더 불완전: $d", ok) // 없으면 건너뜀
        return d // 모델 폴더
    }

    /**
     * 목적: 저장소 루트 찾기
     * 기능: 작업 폴더(gradle 단위 테스트는 app/)에서 위로 올라가며 settings.gradle.kts 가 있는 폴더를 찾는다.
     * 입력: 없음
     * 출력: 못 찾으면 IllegalStateException
     * 리턴: 저장소 루트 폴더
     */
    private fun repoRoot(): File {
        var dir: File? = File(checkNotNull(System.getProperty("user.dir")) { "user.dir 없음" }).absoluteFile // 시작 폴더
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile // 위로 이동
        return dir ?: throw IllegalStateException("저장소 루트(settings.gradle.kts)를 찾지 못함") // 결과
    }

    private companion object {
        const val SCORE_TOL = 1e-3 // Silero·pyannote 점수 허용 오차(brief)

        /**
         * FSMN sil prob 허용 오차. 원인(조사 결과, task-12-report.md): ORT 추론 자체는 같은 특징을 넣으면 Python 과 비트 단위로 같다
         * (Kotlin 특징을 Python onnxruntime 에 넣은 결과 = Kotlin 결과, 차 0). 차이는 전처리에서 온다 — 골든의 kaldi-native-fbank 1.22.3 은
         * kissfft 를 -ffast-math 로 빌드한 float FFT 라 연산 순서를 재현할 수 없고, Task 11 KaldiFbank(double FFT)와 LFR·CMVN 후
         * 최대 ~1.3e-4 다르다. 양자화 FSMN 모델(입력 동적 양자화)이 이 차이를 일부 프레임에서 키운다(실측 최대 0.0295, 70 s 클립).
         * 구간 결과는 8 조합 모두 ms 단위로 정확히 같다(fsmnSegmentsMatchPython).
         */
        const val FSMN_SCORE_TOL = 0.05 // FSMN sil prob 허용 오차
        const val SILERO_MODEL = "tools/golden/models/silero_v6.2.3/silero_vad.onnx" // Silero v6.2.3
        const val PYANNOTE_MODEL = "tools/golden/models/pyannote_seg30/sherpa-onnx-pyannote-segmentation-3-0/model.onnx" // pyannote fp32
        const val FSMN_DIR = "tools/golden/models/fsmn_onnx" // FSMN 모델 폴더
    }
}
