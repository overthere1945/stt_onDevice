// 파일명: KaldiFbankTest.kt (테스트 소스)
// 목적: FSMN 전처리(KaldiFbank, Lfr, Cmvn)가 kaldi-native-fbank / FunASR WavFrontend 결과와 수치적으로 같은지 검증
// 기능: 골든 fbank_<clip>.json(앞 50 프레임 × 80)과 lfr_cmvn_<clip>.json(앞 10 프레임 × 400, fbank → LFR → CMVN 순서)을
//       4개 클립 모두에 대해 원소별 |차| ≤ 1e-3 로 비교하고, 전체 프레임 수(snip_edges 규칙)가 골든 numFramesTotal 과 같은지 확인한다.
//       클립 WAV(golden/audio/, gitignore 대상)가 없으면 Assume 로 건너뛴다(새로 clone 한 환경 대비).
//       골든이 덮지 않는 LFR 끝부분 복제 규칙은 손으로 계산한 작은 입력으로 따로 검증한다.
//       change(add)-hyungchul-2026-10-11-13:57 사용자 음성 파생 특징 골든(kr/en customer 클립)은 공개 저장소에 올리지 않고 로컬 전용(gitignore)이다.
//       그 파일이 없으면 해당 클립만 건너뛰고(출력에 표시), 항상 커밋되는 합성 클립(synth_silence_noise_8s_plus123)은 반드시 실행한다(테스트가 비어 버리지 않게 함).
package com.cowork.stt_ondevice.vad.feature // 특징 추출 테스트 패키지

import com.cowork.stt_ondevice.core.audio.PcmConverter // WAV → 16 kHz mono float 변환
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 파싱
import kotlinx.serialization.json.Json // kotlinx JSON 파서
import kotlinx.serialization.json.JsonObject // JSON 객체
import kotlinx.serialization.json.jsonArray // 요소 → 배열 변환
import kotlinx.serialization.json.jsonObject // 요소 → 객체 변환
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값 변환
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 전제 조건(없으면 건너뜀)
import org.junit.Test // JUnit4 테스트 표시
import java.io.File // 리소스 파일

// change(add)-hyungchul-2026-10-10-13:49
/**
 * 목적: KaldiFbank·Lfr·Cmvn 골든 정합 테스트
 * 기능: 4개 클립에 대해 fbank 와 LFR+CMVN 을 골든과 비교한다.
 */
class KaldiFbankTest {

    private val clips = listOf( // tools/golden/golden_common.py CLIPS 와 같은 4개 클립
        "kr_customer_01_20s_plus77", // 한국어 20 s + 77 샘플
        "en_customer_01_20s_plus77", // 영어 20 s + 77 샘플
        "synth_silence_noise_8s_plus123", // 합성 무음·잡음 8 s + 123 샘플
        "kr_customer_01_70s_plus55", // 한국어 70 s + 55 샘플
    )
    private val synthClip = "synth_silence_noise_8s_plus123" // change(add)-hyungchul-2026-10-11-13:57 항상 커밋되는 합성 클립 이름
    private val tol = 1e-3 // 허용 오차(log 영역, brief 기준)
    private val json = Json { ignoreUnknownKeys = true } // JSON 파서

    /**
     * 목적: fbank 가 kaldi-native-fbank 골든과 같은지 검증
     * 기능: 클립마다 파형을 읽어 KaldiFbank 로 계산하고, 전체 프레임 수(= 1 + (N-400)/160 = numFramesTotal)와
     *       앞 50 프레임 × 80 원소별 |차| ≤ 1e-3 을 확인한다. 클립별 최대 차이를 출력한다.
     * 입력: 없음(클래스패스 golden/)
     * 출력: 클립별 최대 차이 로그, 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun fbankMatchesKaldiNative() {
        val fbank = KaldiFbank() // 기본 설정(80 mel, 25/10 ms, 16 kHz)
        for (clip in clipsWithGolden("fbank")) { // change(add)-hyungchul-2026-10-11-13:57 골든 파일이 있는 클립(합성 클립은 필수)
            val samples = loadClip(clip) // -1..1 파형
            val golden = loadFeature("fbank_$clip") // 골든 fbank
            val feats = fbank.compute(samples) // 전체 fbank 계산
            assertEquals("$clip 프레임 수 규칙(snip_edges)", 1 + (samples.size - 400) / 160, feats.size) // T = 1 + (N-400)/160
            assertEquals("$clip 골든 numFramesTotal", golden.numFramesTotal, feats.size) // 골든 전체 프레임 수와 같음
            val maxDiff = maxAbsDiff(clip, golden.expected, feats) // 앞 50 프레임 원소별 비교
            println("fbank $clip: 프레임 ${feats.size}, 최대 |차| = $maxDiff") // 보고서용 실제 최대 차이
        }
    }

    /**
     * 목적: LFR(5,1) → CMVN(am.mvn) 결과가 FunASR WavFrontend.lfr_cmvn 골든과 같은지 검증
     * 기능: fbank → Lfr.apply → Cmvn.apply 순서로 계산하고, 출력 프레임 수(= fbank 프레임 수)와
     *       앞 10 프레임 × 400 원소별 |차| ≤ 1e-3 을 확인한다. 클립별 최대 차이를 출력한다.
     * 입력: 없음(클래스패스 golden/, golden/fsmn_am.mvn)
     * 출력: 클립별 최대 차이 로그, 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun lfrCmvnMatches() {
        val cmvn = Cmvn.loadAmMvn(resourceFile("golden/fsmn_am.mvn")!!) // 테스트 리소스 am.mvn(커밋된 파일)
        assertEquals("am.mvn 차원", 400, cmvn.shift.size) // AddShift 400 개
        assertEquals("am.mvn 차원", 400, cmvn.scale.size) // Rescale 400 개
        val fbank = KaldiFbank() // 기본 설정
        for (clip in clipsWithGolden("lfr_cmvn")) { // change(add)-hyungchul-2026-10-11-13:57 골든 파일이 있는 클립(합성 클립은 필수)
            val samples = loadClip(clip) // -1..1 파형
            val golden = loadFeature("lfr_cmvn_$clip") // 골든 LFR+CMVN
            assertEquals("$clip lfrM", 5, golden.obj["lfrM"]!!.jsonPrimitive.content.toInt()) // 골든 LFR m = 5
            assertEquals("$clip lfrN", 1, golden.obj["lfrN"]!!.jsonPrimitive.content.toInt()) // 골든 LFR n = 1
            val feats = fbank.compute(samples) // fbank
            val out = cmvn.apply(Lfr.apply(feats, m = 5, n = 1)) // FunASR 순서: LFR 후 CMVN
            assertEquals("$clip LFR 출력 프레임 수", golden.numFramesTotal, out.size) // n=1 이면 ceil(T/1) = T
            val maxDiff = maxAbsDiff(clip, golden.expected, out) // 앞 10 프레임 원소별 비교
            println("lfr_cmvn $clip: 프레임 ${out.size}, 최대 |차| = $maxDiff") // 보고서용 실제 최대 차이
        }
    }

    /**
     * 목적: 골든이 덮지 않는 LFR 앞쪽 복제 패딩·끝부분 복제 규칙 검증
     * 기능: 1차원 프레임 3개 [1],[2],[3] 에 m=5, n=1 을 적용하면 FunASR apply_lfr 규칙상
     *       패딩 열 [1,1,1,2,3] 에서 [1,1,1,2,3], [1,1,2,3,3], [1,2,3,3,3] 이 나와야 한다(손 계산).
     *       n=2 이면 ceil(3/2)=2 프레임 [1,1,1,2,3], [1,2,3,3,3].
     * 입력: 없음
     * 출력: 불일치 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun lfrPadsEdgesLikeFunAsr() {
        val feats = arrayOf(floatArrayOf(1f), floatArrayOf(2f), floatArrayOf(3f)) // T=3, 차원 1
        val out = Lfr.apply(feats, m = 5, n = 1) // LFR(5,1)
        assertEquals(3, out.size) // ceil(3/1) = 3
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 2f, 3f), out[0], 0f) // 앞 2개 복제 패딩 + 0..2
        assertArrayEquals(floatArrayOf(1f, 1f, 2f, 3f, 3f), out[1], 0f) // 끝 1개 부족 → 마지막 프레임 복제
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 3f, 3f), out[2], 0f) // 끝 2개 부족 → 마지막 프레임 복제
        val out2 = Lfr.apply(feats, m = 5, n = 2) // LFR(5,2)
        assertEquals(2, out2.size) // ceil(3/2) = 2
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 2f, 3f), out2[0], 0f) // 시작 0
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 3f, 3f), out2[1], 0f) // 시작 2, 끝 2개 복제
    }

    // change(add)-hyungchul-2026-10-11-13:57
    /**
     * 목적: 특징 골든이 실제로 있는 클립만 골라내기(사용자 음성 파생 골든은 로컬 전용이라 새 clone 에는 없음)
     * 기능: clips 중 golden/<prefix>_<clip>.json 이 클래스패스에 있는 클립만 남긴다. 없는 클립은 건너뛴다고 출력한다.
     *       합성 클립 골든은 항상 커밋되므로 없으면 테스트를 실패시킨다(전부 건너뛰어 검증이 비는 것을 막음).
     * 입력: prefix(골든 파일 이름 접두어, 예: "fbank", "lfr_cmvn")
     * 출력: 건너뛴 클립 안내 로그, 합성 클립 골든이 없으면 AssertionError
     * 리턴: 골든이 있는 클립 이름 목록(원래 순서)
     */
    private fun clipsWithGolden(prefix: String): List<String> {
        val present = clips.filter { javaClass.classLoader!!.getResource("golden/${prefix}_$it.json") != null } // 파일이 있는 클립만
        assertTrue("합성 클립 골든은 항상 커밋되어야 함: ${prefix}_$synthClip", synthClip in present) // 합성 클립 필수
        for (c in clips - present.toSet()) println("건너뜀(로컬 전용 골든 없음): ${prefix}_$c") // 건너뛴 클립 표시
        return present // 결과
    }

    /**
     * 목적: 골든 특징 JSON 1건의 메모리 표현
     * 기능: 필요한 키(expected, numFramesTotal)와 원본 객체를 담는다.
     */
    private class Feature(val expected: List<FloatArray>, val numFramesTotal: Int, val obj: JsonObject)

    /**
     * 목적: 골든 특징 JSON 읽기
     * 기능: golden/<name>.json 을 읽어 expected(float32 최단 표기 → Float 비트 단위 복원)와 numFramesTotal 을 꺼낸다.
     * 입력: name(확장자 제외 파일 이름)
     * 출력: 파일 없으면 AssertionError
     * 리턴: Feature
     */
    private fun loadFeature(name: String): Feature {
        val file = resourceFile("golden/$name.json") // 골든 파일
        assertTrue("골든 파일 없음: $name", file != null) // 골든 JSON 은 커밋되어 있어야 함
        val obj = json.parseToJsonElement(file!!.readText()).jsonObject // JSON 객체
        val expected = obj["expected"]!!.jsonArray.map { row -> // 프레임마다
            val a = row.jsonArray // 차원 배열
            FloatArray(a.size) { a[it].jsonPrimitive.content.toFloat() } // 원문 → float32
        }
        assertEquals("$name numFrames", obj["numFrames"]!!.jsonPrimitive.content.toInt(), expected.size) // 저장 프레임 수 확인
        assertEquals("$name dim", obj["dim"]!!.jsonPrimitive.content.toInt(), expected[0].size) // 차원 확인
        return Feature(expected, obj["numFramesTotal"]!!.jsonPrimitive.content.toInt(), obj) // 조립
    }

    /**
     * 목적: 클립 WAV 를 -1..1 float 파형으로 읽기
     * 기능: golden/audio/<clip>.wav 가 없으면 assumeTrue 로 테스트를 건너뛰고, 있으면 WavReader → PcmConverter 로 변환한다.
     * 입력: clip(확장자 제외 클립 이름)
     * 출력: 파일 없으면 AssumptionViolatedException(건너뜀)
     * 리턴: 16 kHz mono float 파형
     */
    private fun loadClip(clip: String): FloatArray {
        val file = resourceFile("golden/audio/$clip.wav") // gitignore 된 클립(로컬에만 있음)
        assumeTrue("클립 WAV 없음(골든 음원은 git 에 없음): $clip", file != null) // 없으면 건너뜀
        val wav = WavReader.read(file!!) // WAV 파싱
        assertEquals("$clip 샘플레이트", 16000, wav.sampleRate) // 16 kHz 확인
        assertEquals("$clip 채널", 1, wav.channels) // mono 확인
        return PcmConverter.toMono16k(wav).samples // int16/32768 정규화 파형
    }

    /**
     * 목적: 클래스패스 리소스를 파일로 찾기
     * 기능: 리소스 URL 이 있으면 File 로 바꾼다(JVM 단위 테스트에서는 실제 디렉터리).
     * 입력: path(클래스패스 상대 경로)
     * 출력: 없음
     * 리턴: File 또는 null(없음)
     */
    private fun resourceFile(path: String): File? =
        javaClass.classLoader!!.getResource(path)?.let { File(it.toURI()) } // URL → File

    /**
     * 목적: 골든 행렬과 계산 행렬의 원소별 비교
     * 기능: 골든 프레임 수만큼 앞에서부터 같은 차원인지 보고 원소별 |차| ≤ tol 을 확인한다. 최대 차이를 돌려준다.
     * 입력: clip(메시지용), expected(골든 행렬), actual(계산 행렬)
     * 출력: 불일치 시 AssertionError
     * 리턴: 최대 |차|
     */
    private fun maxAbsDiff(clip: String, expected: List<FloatArray>, actual: Array<FloatArray>): Double {
        assertTrue("$clip 계산 프레임 수 부족", actual.size >= expected.size) // 비교할 프레임이 있어야 함
        var maxDiff = 0.0 // 최대 차이
        for (t in expected.indices) { // 골든 프레임마다
            assertEquals("$clip 프레임 $t 차원", expected[t].size, actual[t].size) // 차원 일치
            for (d in expected[t].indices) { // 원소마다
                val diff = Math.abs(expected[t][d].toDouble() - actual[t][d].toDouble()) // 절대 차이
                assertTrue("$clip [$t][$d] 기대=${expected[t][d]} 실제=${actual[t][d]}", diff <= tol) // 허용 오차 이내
                if (diff > maxDiff) maxDiff = diff // 최대 갱신
            }
        }
        return maxDiff // 최대 차이
    }
}
