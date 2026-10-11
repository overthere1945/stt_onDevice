// 파일명: FireRedMarbleNetParityTest.kt
// 목적: FireRedVAD·MarbleNet 의 전처리·ONNX 추론·detect() 끝단이 원본 Python(Task 13 골든)과 같은지 검증
// 기능: 골든 클립 4개(kr/en 20 s+77, 합성 8 s+123, kr 70 s+55)마다
//       (1) 전처리: FireRed fbank(KaldiFbank povey) 앞 50 프레임, FireRed CMVN(cmvn.ark 파서 해석값은 정확 일치, 적용 결과 앞 50 프레임),
//           MarbleNet log-mel(NemoMelSpectrogram) 앞 50 프레임 + 끝 4 프레임을 골든과 비교(실제 최대 |차| 출력)
//       (2) 모델 확률: FireRed 오프라인/스트리밍 ONNX, MarbleNet ONNX 확률을 scores_* 골든과 |차| ≤ 1e-3 로 비교
//       (3) detect() 끝단 구간을 FIRERED_OFFLINE·FIRERED_STREAM(0.01 s)·MARBLENET_NEMO(0.02 s) 골든과 비교(정확 일치 여부도 출력)
//       모델(tools/export/out/, gitignore)·클립(golden/audio/, gitignore)이 없으면 Assume 으로 건너뛴다(준비된 환경에서는 SKIPPED 없어야 함).
//       change(add)-hyungchul-2026-10-11-13:57 사용자 음성 파생 특징 골든(firered_fbank·firered_fbank_cmvn·marblenet_mel 의 kr/en customer 클립)은
//       로컬 전용(gitignore)이라 없으면 그 클립만 건너뛰고, 항상 커밋되는 합성 클립(synth_silence_noise_8s_plus123)은 반드시 실행한다.
package com.cowork.stt_ondevice.vad.impl // VAD 구현 테스트 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // WAV → 16 kHz mono float
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 파싱
import com.cowork.stt_ondevice.vad.FireRedMode // FireRed 모드
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.assertSegmentsClose // 구간 근사 비교
import com.cowork.stt_ondevice.vad.feature.FireRedCmvn // FireRed CMVN(Kaldi 바이너리)
import com.cowork.stt_ondevice.vad.feature.KaldiFbank // Kaldi fbank
import com.cowork.stt_ondevice.vad.feature.KaldiWindowType // 창 함수 종류
import com.cowork.stt_ondevice.vad.feature.NemoMelSpectrogram // NeMo log-mel
import kotlinx.serialization.json.JsonArray // JSON 배열
import kotlinx.serialization.json.jsonArray // 요소 → 배열
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 준비물 없으면 건너뜀
import org.junit.Test // JUnit4 테스트 표시
import java.io.File // 모델·클립 파일
import kotlin.math.abs // 절댓값

// change(add)-hyungchul-2026-10-10-15:04
/**
 * 목적: FireRedVAD·MarbleNet 의 Python 참조 정합 회귀 방지
 * 기능: 전처리 정합, 모델 점수 정합, detect() 끝단 구간 테스트를 둔다. 실제 최대 차이를 표준 출력에 남겨 보고서에 옮긴다.
 */
class FireRedMarbleNetParityTest {

    /** 골든 클립 4개(파일명에서 .wav 를 뺀 이름). */
    private val clips = listOf(
        "kr_customer_01_20s_plus77", // 한국어 20 s + 77 샘플
        "en_customer_01_20s_plus77", // 영어 20 s + 77 샘플
        "synth_silence_noise_8s_plus123", // 합성 무음·잡음 8 s + 123 샘플
        "kr_customer_01_70s_plus55", // 한국어 70 s + 55 샘플
    )

    // ---------------------------------------------------------------- 전처리

    /**
     * 목적: FireRed fbank(povey 창, int16 크기 입력)가 kaldi-native-fbank 1.22.3 골든과 같은지 검증
     * 기능: KaldiFbank(windowType = POVEY) 로 전체 프레임 수(= numFramesTotal)와 앞 50 프레임 × 80 을 |차| ≤ 1e-3 으로 비교한다.
     * 입력: 없음
     * 출력: 클립별 최대 |차| 출력
     * 리턴: 없음
     */
    @Test
    fun fireRedFbankMatchesGolden() {
        val fbank = KaldiFbank(windowType = KaldiWindowType.POVEY) // FireRed 설정(창만 povey)
        for (stem in clipsWithGolden("firered_fbank")) { // change(add)-hyungchul-2026-10-11-13:57 골든이 있는 클립마다(합성 클립 필수)
            val audio = loadClip(stem) // 파형
            val raw = GoldenLoader.loadRaw("firered_fbank_$stem") // 골든
            assertEquals("$stem inputScale", "1", raw["inputScale"]!!.jsonPrimitive.content) // int16 값 그대로(= float × 32768)
            val feats = fbank.compute(audio) // 전체 fbank
            assertEquals("$stem 프레임 수", raw["numFramesTotal"]!!.jsonPrimitive.content.toInt(), feats.size) // snip_edges 프레임 수
            val maxDiff = assertMatrixClose("firered_fbank $stem", matrix(raw["expected"]!!.jsonArray), feats, FEATURE_TOL_FBANK) // 앞 50 프레임
            println("FireRed fbank $stem: 프레임 ${feats.size}, 최대 |차| = $maxDiff") // 보고용
        }
    }

    /**
     * 목적: FireRed CMVN 파서(Kaldi 바이너리 double 행렬)와 적용이 공식 CMVN 클래스와 같은지 검증
     * 기능: golden/firered_cmvn.ark 해석값(mean, inverse std)이 firered_cmvn.json 과 double 로 정확히 같은지 보고,
     *       골든 fbank(float32)에 적용한 결과가 골든 CMVN 결과와 비트 단위로 같은지(정확), Kotlin fbank → CMVN 결과가 |차| ≤ 1e-3 인지 본다.
     * 입력: 없음
     * 출력: 클립별 최대 |차| 출력
     * 리턴: 없음
     */
    @Test
    fun fireRedCmvnMatchesGolden() {
        val cmvn = FireRedCmvn.load(resourceFile("golden/firered_cmvn.ark")) // 테스트 리소스 ark(커밋된 원본)
        val ref = GoldenLoader.loadRaw("firered_cmvn") // 해석값 골든
        assertEquals("dim", 80, cmvn.means.size) // 80 차원
        assertArrayEquals("means", doubles(ref["means"]!!.jsonArray), cmvn.means, 0.0) // 평균 정확 일치
        assertArrayEquals("inverseStd", doubles(ref["inverseStdVariances"]!!.jsonArray), cmvn.inverseStd, 0.0) // 역표준편차 정확 일치
        val fbank = KaldiFbank(windowType = KaldiWindowType.POVEY) // FireRed fbank
        for (stem in clipsWithGolden("firered_fbank", "firered_fbank_cmvn")) { // change(add)-hyungchul-2026-10-11-13:57 두 골든이 모두 있는 클립마다(합성 클립 필수)
            val gFbank = matrix(GoldenLoader.loadRaw("firered_fbank_$stem")["expected"]!!.jsonArray) // 골든 fbank(float32)
            val gCmvn = GoldenLoader.loadRaw("firered_fbank_cmvn_$stem") // 골든 CMVN 결과
            val expected = matrix(gCmvn["expected"]!!.jsonArray) // 앞 50 프레임
            val exact = cmvn.apply(gFbank.toTypedArray()) // 같은 입력이면 비트 단위로 같아야 함
            for (t in expected.indices) assertArrayEquals("$stem CMVN 정확 [$t]", expected[t], exact[t], 0f) // 정확 일치
            val audio = loadClip(stem) // 파형
            val feats = cmvn.apply(fbank.compute(audio)) // Kotlin 전 경로
            assertEquals("$stem 프레임 수", gCmvn["numFramesTotal"]!!.jsonPrimitive.content.toInt(), feats.size) // 프레임 수
            val maxDiff = assertMatrixClose("firered_fbank_cmvn $stem", expected, feats, FEATURE_TOL_FBANK) // 앞 50 프레임
            println("FireRed fbank+CMVN $stem: 최대 |차| = $maxDiff (골든 fbank 입력 시 정확 일치)") // 보고용
        }
    }

    /**
     * 목적: MarbleNet log-mel(NemoMelSpectrogram)이 NeMo 3.0.0 FilterbankFeatures 출력과 같은지 검증
     * 기능: golden/marblenet_preprocessor.json(= 모델 폴더의 설정 파일과 같은 내용)으로 만든 계산기로 프레임 수(F, pad_to 2 포함)·
     *       앞 50 프레임·끝 4 프레임(마스크·패딩 0)을 |차| ≤ MEL_TOL 로 비교한다.
     * 입력: 없음
     * 출력: 클립별 최대 |차| 출력
     * 리턴: 없음
     */
    @Test
    fun marbleNetMelMatchesGolden() {
        val mel = NemoMelSpectrogram(resourceFile("golden/marblenet_preprocessor.json")) // 설정 파일
        for (stem in clipsWithGolden("marblenet_mel")) { // change(add)-hyungchul-2026-10-11-13:57 골든이 있는 클립마다(합성 클립 필수)
            val audio = loadClip(stem) // 파형
            val raw = GoldenLoader.loadRaw("marblenet_mel_$stem") // 골든
            val feats = mel.compute(audio) // F × 80
            val total = raw["numFramesTotal"]!!.jsonPrimitive.content.toInt() // F
            assertEquals("$stem 프레임 수 F", total, feats.size) // pad_to 2 포함 프레임 수
            assertEquals("$stem seqLen", raw["seqLen"]!!.jsonPrimitive.content.toInt(), audio.size / 160) // seqLen = floor(N/160)
            val head = assertMatrixClose("marblenet_mel $stem 앞", matrix(raw["expected"]!!.jsonArray), feats, MEL_TOL) // 앞 50 프레임
            val tailExp = matrix(raw["tailFrames"]!!.jsonArray) // 끝 4 프레임
            val tailAct = feats.copyOfRange(total - tailExp.size, total) // 계산 끝 4 프레임
            val tail = assertMatrixClose("marblenet_mel $stem 끝", tailExp, tailAct, MEL_TOL) // 끝 프레임
            println("MarbleNet mel $stem: 프레임 $total, 앞 50 최대 |차| = $head, 끝 4 최대 |차| = $tail") // 보고용
        }
    }

    // ---------------------------------------------------------------- 모델 확률

    /**
     * 목적: FireRed 오프라인·스트리밍 ONNX 확률이 Python ONNX 골든과 같은지 검증
     * 기능: 클립마다 probabilities(OFFLINE) 를 scores_FIRERED, probabilities(STREAM) 을 scores_FIRERED_STREAM 과 |차| ≤ 1e-3 로 비교한다.
     *       PyTorch 확률(구간 골든 frameScores)과의 최대 차이도 출력한다.
     * 입력: 없음
     * 출력: 클립·모드별 최대 |차| 출력
     * 리턴: 없음
     */
    @Test
    fun fireRedProbsMatchPython() {
        FireRedVad(requireDir(FIRERED_DIR, FIRERED_FILES)).use { vad -> // 모델 폴더
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                for ((mode, family, preset) in listOf(Triple(FireRedMode.OFFLINE, "FIRERED", "FIRERED_OFFLINE"), Triple(FireRedMode.STREAM, "FIRERED_STREAM", "FIRERED_STREAM"))) { // 모드마다
                    val g = GoldenLoader.loadScores(family, stem) // ONNX 점수 골든
                    assertEquals("$stem totalSamples", g.totalSamples, audio.size) // 같은 클립
                    val probs = vad.probabilities(audio, mode) // 모델 확률
                    assertEquals("$stem 프레임 수 = 1 + (N − 400)/160", 1 + (audio.size - 400) / 160, probs.size) // 프레임 수
                    val maxDiff = assertScoresClose("$family $stem", g.frameScores, probs, SCORE_TOL) // ONNX 골든 비교
                    val torchDiff = maxAbs(GoldenLoader.load("FIRERED_${preset}_$stem").frameScores, probs) // PyTorch 확률과 차이(보고용)
                    println("$family 점수 $stem: 프레임 ${probs.size}, ONNX 골든 최대 |차| = $maxDiff, PyTorch 골든 최대 |차| = $torchDiff") // 보고용
                }
            }
        }
    }

    /**
     * 목적: MarbleNet ONNX 확률(softmax[...,1])이 Python ONNX 골든과 같은지 검증
     * 기능: 클립마다 probabilities 를 scores_MARBLENET 과 |차| ≤ 1e-3 로 비교하고(개수 = F/2), NeMo fp32 확률과의 차이도 출력한다.
     * 입력: 없음
     * 출력: 클립별 최대 |차| 출력
     * 리턴: 없음
     */
    @Test
    fun marbleNetProbsMatchPython() {
        MarbleNetVad(requireDir(MARBLENET_DIR, MARBLENET_FILES)).use { vad -> // 모델 폴더
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                val g = GoldenLoader.loadScores("MARBLENET", stem) // ONNX 점수 골든
                assertEquals("$stem totalSamples", g.totalSamples, audio.size) // 같은 클립
                val probs = vad.probabilities(audio) // 모델 확률
                val f = audio.size / 160 + 1 // STFT 프레임 수
                assertEquals("$stem 출력 프레임 수 = ceil2(F)/2", (f + 1) / 2, probs.size) // pad_to 2 → F/2
                val maxDiff = assertScoresClose("MARBLENET $stem", g.frameScores, probs, SCORE_TOL) // ONNX 골든 비교
                val nemoDiff = maxAbs(GoldenLoader.load("MARBLENET_MARBLENET_NEMO_$stem").frameScores, probs) // NeMo fp32 확률과 차이
                println("MARBLENET 점수 $stem: 프레임 ${probs.size}, ONNX 골든 최대 |차| = $maxDiff, NeMo 골든 최대 |차| = $nemoDiff") // 보고용
            }
        }
    }

    // ---------------------------------------------------------------- detect() 끝단

    /**
     * 목적: FireRedVad.detect() 끝단(fbank → CMVN → ONNX → 후처리) 구간이 FIRERED_OFFLINE·FIRERED_STREAM 골든과 같은지 검증
     * 기능: 두 프리셋 × 4 클립 결과를 골든 expected 와 0.01 s 이내로 비교하고 정확 일치 여부를 출력한다. hop·점수 방향도 확인한다.
     * 입력: 없음
     * 출력: 프리셋·클립별 최대 경계 차이와 정확 일치 여부 출력
     * 리턴: 없음
     */
    @Test
    fun fireRedDetectMatchesPresetGoldens() {
        FireRedVad(requireDir(FIRERED_DIR, FIRERED_FILES)).use { vad -> // 모델 폴더
            for (pid in listOf("FIRERED_OFFLINE", "FIRERED_STREAM")) { // 프리셋마다
                val preset = VadPresets.byId(pid) // 프리셋
                for (stem in clips) { // 클립마다
                    val audio = loadClip(stem) // 파형
                    val g = GoldenLoader.load("FIRERED_${pid}_$stem") // 구간 골든
                    val out = vad.detect(audio, preset) // 끝단 실행
                    assertEquals("hop", 0.010, out.frameHopSec!!, 0.0) // 10 ms
                    assertEquals("점수 개수", g.frameScores.size, out.frameScores!!.size) // 프레임 수
                    val maxDiff = segmentsMaxDiff(g.expected, out.segments) // 최대 경계 차이
                    assertSegmentsClose(g.expected, out.segments, 0.010 + 1e-9) // 0.01 s 허용(brief)
                    println("$pid 구간 $stem: ${out.segments.size} 개, 최대 경계 차이 = $maxDiff s, 정확 일치 = ${g.expected == out.segments}") // 보고용
                }
            }
        }
    }

    /**
     * 목적: MarbleNetVad.detect() 끝단(log-mel → ONNX → softmax → NeMo 후처리) 구간이 MARBLENET_NEMO 골든과 같은지 검증
     * 기능: 4 클립 결과를 골든 expected 와 0.02 s 이내로 비교하고 float32 정확 일치 여부를 출력한다. hop 0.02 를 확인한다.
     * 입력: 없음
     * 출력: 클립별 최대 경계 차이 출력
     * 리턴: 없음
     */
    @Test
    fun marbleNetDetectMatchesPresetGolden() {
        val preset = VadPresets.byId("MARBLENET_NEMO") // 프리셋
        MarbleNetVad(requireDir(MARBLENET_DIR, MARBLENET_FILES)).use { vad -> // 모델 폴더
            for (stem in clips) { // 클립마다
                val audio = loadClip(stem) // 파형
                val g = GoldenLoader.load("MARBLENET_MARBLENET_NEMO_$stem") // 구간 골든
                val out = vad.detect(audio, preset) // 끝단 실행
                assertEquals("hop", 0.02, out.frameHopSec!!, 0.0) // 20 ms
                val maxDiff = segmentsMaxDiff(g.expected, out.segments) // 최대 경계 차이
                assertSegmentsClose(g.expected, out.segments, 0.020 + 1e-9) // 0.02 s 허용(brief)
                val exact = g.expected.size == out.segments.size && g.expected.indices.all { // float32 정확 일치 여부
                    g.expected[it].startSec.toFloat() == out.segments[it].startSec.toFloat() && g.expected[it].endSec.toFloat() == out.segments[it].endSec.toFloat() // 시작·끝
                }
                println("MARBLENET_NEMO 구간 $stem: ${out.segments.size} 개, 최대 경계 차이 = $maxDiff s, float32 정확 일치 = $exact") // 보고용
            }
        }
    }

    /**
     * 목적: 다른 계열 프리셋·잘못된 params 를 조용히 처리하지 않는지 검증
     * 기능: 두 VAD 모두 다른 계열 프리셋이면, 계열은 맞지만 params 타입이 다르면 IllegalArgumentException 을 던져야 한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun detectRejectsOtherFamilyPreset() {
        val audio = FloatArray(16000) // 1 s 무음
        FireRedVad(requireDir(FIRERED_DIR, FIRERED_FILES)).use { v -> // FireRed
            assertThrows(IllegalArgumentException::class.java) { v.detect(audio, VadPresets.byId("MARBLENET_NEMO")) } // 다른 계열
            assertThrows(IllegalArgumentException::class.java) { v.detect(audio, VadPresets.byId("FIRERED_OFFLINE").copy(params = VadPresets.byId("SILERO").params)) } // 잘못된 params
        }
        MarbleNetVad(requireDir(MARBLENET_DIR, MARBLENET_FILES)).use { v -> // MarbleNet
            assertThrows(IllegalArgumentException::class.java) { v.detect(audio, VadPresets.byId("FIRERED_STREAM")) } // 다른 계열
            assertThrows(IllegalArgumentException::class.java) { v.detect(audio, VadPresets.byId("MARBLENET_NEMO").copy(params = VadPresets.byId("SILERO").params)) } // 잘못된 params
        }
    }

    // ---------------------------------------------------------------- 도우미

    // change(add)-hyungchul-2026-10-11-13:57
    /**
     * 목적: 특징 골든이 실제로 있는 클립만 골라내기(사용자 음성 파생 골든은 로컬 전용이라 새 clone 에는 없음)
     * 기능: clips 중 모든 접두어에 대해 golden/<prefix>_<clip>.json 이 클래스패스에 있는 클립만 남긴다. 없는 클립은 건너뛴다고 출력한다.
     *       합성 클립 골든은 항상 커밋되므로 없으면 테스트를 실패시킨다(전부 건너뛰어 검증이 비는 것을 막음).
     * 입력: prefixes(골든 파일 이름 접두어들, 예: "firered_fbank", "marblenet_mel")
     * 출력: 건너뛴 클립 안내 로그, 합성 클립 골든이 없으면 AssertionError
     * 리턴: 골든이 있는 클립 이름 목록(원래 순서)
     */
    private fun clipsWithGolden(vararg prefixes: String): List<String> {
        val present = clips.filter { c -> prefixes.all { javaClass.classLoader!!.getResource("golden/${it}_$c.json") != null } } // 모든 접두어 파일이 있는 클립
        assertTrue("합성 클립 골든은 항상 커밋되어야 함: ${prefixes.joinToString("/")} $SYNTH_CLIP", SYNTH_CLIP in present) // 합성 클립 필수
        for (c in clips - present.toSet()) println("건너뜀(로컬 전용 골든 없음): ${prefixes.joinToString("/")} $c") // 건너뛴 클립 표시
        return present // 결과
    }

    /** 목적: JSON 2차원 숫자 배열 → float32 행렬. 입력: a. 리턴: 행 목록. */
    private fun matrix(a: JsonArray): List<FloatArray> = a.map { row -> row.jsonArray.let { r -> FloatArray(r.size) { r[it].jsonPrimitive.content.toFloat() } } } // 원문 → float32

    /** 목적: JSON 숫자 배열 → double 배열. 입력: a. 리턴: DoubleArray. */
    private fun doubles(a: JsonArray): DoubleArray = DoubleArray(a.size) { a[it].jsonPrimitive.content.toDouble() } // 원문 → double

    /**
     * 목적: 골든 행렬과 계산 행렬의 원소별 비교
     * 기능: 골든 행 수만큼 앞에서부터 차원과 |차| ≤ tol 을 확인한다. 실패 시 최대 차이 위치를 메시지에 넣는다.
     * 입력: label, expected(골든 행), actual(계산 행렬), tol
     * 출력: 불일치 시 AssertionError
     * 리턴: 최대 |차|
     */
    private fun assertMatrixClose(label: String, expected: List<FloatArray>, actual: Array<FloatArray>, tol: Double): Double {
        assertTrue("$label 계산 프레임 수 부족", actual.size >= expected.size) // 비교 프레임 존재
        var maxDiff = 0.0 // 최대 차이
        var at = "" // 위치
        for (t in expected.indices) { // 행마다
            assertEquals("$label 행 $t 차원", expected[t].size, actual[t].size) // 차원 일치
            for (d in expected[t].indices) { // 원소마다
                val diff = abs(expected[t][d].toDouble() - actual[t][d].toDouble()) // 차이
                if (diff > maxDiff) { maxDiff = diff; at = "[$t][$d] 기대=${expected[t][d]} 실제=${actual[t][d]}" } // 최대 갱신
            }
        }
        assertTrue("$label 최대 |차| $maxDiff > $tol $at", maxDiff <= tol) // 허용 오차 이내
        return maxDiff // 보고용
    }

    /**
     * 목적: 두 점수 배열 비교
     * 기능: 길이가 같고 모든 원소 |차| ≤ tol 인지 확인한다.
     * 입력: label, expected, actual, tol
     * 출력: 불일치 시 AssertionError
     * 리턴: 최대 |차|
     */
    private fun assertScoresClose(label: String, expected: FloatArray, actual: FloatArray, tol: Double): Double {
        assertEquals("$label 점수 개수", expected.size, actual.size) // 개수 일치
        val maxDiff = maxAbs(expected, actual) // 최대 차이
        assertTrue("$label 최대 |차| $maxDiff > $tol", maxDiff <= tol) // 허용 오차 이내
        return maxDiff // 보고용
    }

    /** 목적: 같은 길이 두 배열의 최대 |차|(길이 다르면 +∞). 입력: a, b. 리턴: 최대 차이. */
    private fun maxAbs(a: FloatArray, b: FloatArray): Double =
        if (a.size != b.size) Double.POSITIVE_INFINITY else a.indices.maxOfOrNull { abs(a[it].toDouble() - b[it].toDouble()) } ?: 0.0 // 최대

    /** 목적: 구간 목록 최대 경계 차이(보고용). 입력: 기대·실제. 리턴: 최대 차이(개수 다르면 +∞). */
    private fun segmentsMaxDiff(expected: List<SpeechSegment>, actual: List<SpeechSegment>): Double {
        if (expected.size != actual.size) return Double.POSITIVE_INFINITY // 개수 다름
        return expected.indices.maxOfOrNull { maxOf(abs(expected[it].startSec - actual[it].startSec), abs(expected[it].endSec - actual[it].endSec)) } ?: 0.0 // 최대
    }

    /**
     * 목적: 골든 클립 읽기
     * 기능: 클래스패스 golden/audio/<stem>.wav 를 16 kHz mono float(int16/32768)로 읽는다. 없으면 건너뜀.
     * 입력: stem(클립 이름)
     * 출력: 클립 없으면 AssumptionViolatedException
     * 리턴: 파형
     */
    private fun loadClip(stem: String): FloatArray {
        val url = javaClass.classLoader!!.getResource("golden/audio/$stem.wav") // 클립 리소스(gitignore)
        assumeTrue("골든 클립 없음: golden/audio/$stem.wav (tools/golden/make_clips.py 로 생성)", url != null) // 없으면 건너뜀
        return PcmConverter.toMono16k(WavReader.read(File(url!!.toURI()))).samples // 파일 → float 파형
    }

    /** 목적: 커밋된 테스트 리소스 파일 찾기. 입력: path(클래스패스 상대). 출력: 없으면 AssertionError. 리턴: File. */
    private fun resourceFile(path: String): File {
        val url = javaClass.classLoader!!.getResource(path) // 리소스 URL
        assertTrue("테스트 리소스 없음: $path", url != null) // 커밋된 파일이라 있어야 함
        return File(url!!.toURI()) // 파일
    }

    /**
     * 목적: 모델 폴더 확인
     * 기능: 저장소 루트 기준 폴더에 필요한 파일이 모두 있으면 폴더를, 아니면 테스트를 건너뛴다.
     * 입력: rel(저장소 루트 기준 상대 경로), files(필요 파일 이름)
     * 출력: 없으면 AssumptionViolatedException
     * 리턴: 모델 폴더
     */
    private fun requireDir(rel: String, files: List<String>): File {
        val d = File(repoRoot(), rel) // 절대 경로
        assumeTrue("모델 폴더 불완전: $d (tools/export/README_한국어.md 2절로 생성) 필요 파일 $files", files.all { File(d, it).isFile }) // 없으면 건너뜀
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
        const val SYNTH_CLIP = "synth_silence_noise_8s_plus123" // change(add)-hyungchul-2026-10-11-13:57 항상 커밋되는 합성 클립 이름
        const val SCORE_TOL = 1e-3 // 모델 확률 허용 오차(brief)
        const val FEATURE_TOL_FBANK = 1e-3 // FireRed fbank·CMVN 허용 오차(brief, FSMN KaldiFbankTest 와 같음)
        const val MEL_TOL = 1e-4 // MarbleNet log-mel 허용 오차(컨트롤러 지시: 가능하면 1e-4)
        const val FIRERED_DIR = "tools/export/out/firered" // FireRed 모델 폴더
        const val MARBLENET_DIR = "tools/export/out/marblenet" // MarbleNet 모델 폴더
        val FIRERED_FILES = listOf("fireredvad_vad.onnx", "fireredvad_stream_vad.onnx", "cmvn.ark") // FireRed 필요 파일
        val MARBLENET_FILES = listOf("frame_vad_multilingual_marblenet_v2.0.onnx", "marblenet_preprocessor.json") // MarbleNet 필요 파일
    }
}
