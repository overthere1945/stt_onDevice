// 파일명: NativeSmokeTest.kt (기기 계측 테스트)
// 목적: 실제 기기에서 libstt_native.so(libfvad JNI)·libten_vad.so 가 로드·동작하는지, 네이티브 판정이 골든과 맞는지 검증
//       change(add)-hyungchul-2026-10-10-15:43 + LiteRT(libLiteRt.so·liblitert_jni.so)로 yamnet.tflite 가 동작하는지, 점수가 PC 골든과 맞는지 검증
// 기능: - fvadDetectsSpeechInClip: 사용자 음원 kr/customer/01.wav 앞 20 s 를 WebRTC 3 프리셋으로 처리해 구간 1개 이상, 점수 0/1 확인
//       - tenVadReturnsProbabilities: 같은 20 s 를 TEN 2 프리셋으로 처리해 확률 개수 floor(N/256), 값 [0,1], 구간 1개 이상 확인
//       - nativeMatchesGoldenClips: 기기에 골든 클립(WAV)·JSON 이 있으면 WEBRTC_*·TEN_* 골든마다 네이티브 결과를 골든과 비교
//         (WebRTC: 프레임 판정 불일치 수·구간, TEN: 확률 최대 절대오차·구간). 골든 파일이 없으면 이 테스트만 건너뜀(assume, 보고서에 표시).
//       - yamnetRuns (change(add)-hyungchul-2026-10-10-15:43): 기기에 넣은 yamnet.tflite 의 SHA-256 이 PC 에서 라벨(index 0 = "Speech")을 확인한
//         파일과 같은지 확인, 같은 20 s 를 YAMNET 3 프리셋으로 처리해 점수 개수 floor(N/243), 값 [0,1], 구간 1개 이상 확인(처리 시간 logcat 기록)
//       - yamnetMatchesGoldenScores (change(add)-hyungchul-2026-10-10-15:43): golden/scores_YAMNET_*.json(PC ai-edge-litert 2.3.0 결과)과
//         기기 LiteRT Speech 점수 비교. 모델이 int8 양자화라 점수가 1/256 단위이므로 "프레임 99% 이상 비트 일치 + 나머지는 1 단계(1/256) 이내"를
//         기준으로 한다(1e-3 허용은 1 단계 차이 1 프레임에서도 실패 — 보고서 참조). 프리셋별 구간 일치 여부는 logcat 에 남긴다. 골든·클립이 없으면 건너뜀(assume).
//       입력 파일은 assets 가 아니라 앱 외부 파일 폴더에서 읽는다(adb push 필요):
//         /sdcard/Android/data/com.cowork.stt_ondevice/files/input_sound/kr/customer/01.wav
//         /sdcard/Android/data/com.cowork.stt_ondevice/files/golden/{WEBRTC_*,TEN_*}.json, golden/audio/*.wav
//         change(add)-hyungchul-2026-10-10-15:43 /sdcard/Android/data/com.cowork.stt_ondevice/files/models/yamnet/yamnet.tflite,
//         golden/scores_YAMNET_*.json
//       실행 순서(앱이 자기 폴더를 먼저 만들어야 하고, adb 가 만든 하위 폴더는 shell 소유 drwxrws--- 라 앱이 못 읽으므로 chmod 필요;
//       connectedDebugAndroidTest 는 끝나면 앱을 지워 이 파일들도 사라지므로 매 실행 전 다시 넣는다):
//         ./gradlew :app:installDebug
//         P=/sdcard/Android/data/com.cowork.stt_ondevice/files
//         adb shell mkdir -p $P/input_sound/kr/customer $P/golden/audio
//         adb push ~/workspace/stt/input_sound/kr/customer/01.wav $P/input_sound/kr/customer/01.wav
//         adb push app/src/test/resources/golden/audio/. $P/golden/audio/
//         for f in app/src/test/resources/golden/WEBRTC_*.json app/src/test/resources/golden/TEN_*.json; do adb push $f $P/golden/; done
//         (change(add)-hyungchul-2026-10-10-15:43 Yamnet)
//         adb shell mkdir -p $P/models/yamnet
//         adb push tools/golden/models/yamnet/yamnet.tflite $P/models/yamnet/yamnet.tflite
//         for f in app/src/test/resources/golden/scores_YAMNET_*.json; do adb push $f $P/golden/; done
//         adb shell chmod -R a+rwX $P/input_sound $P/golden $P/models
//         ./gradlew :app:connectedDebugAndroidTest
// change(add)-hyungchul-2026-10-10-15:26
package com.cowork.stt_ondevice // 앱 루트 패키지(계측 테스트)

import android.util.Log // 결과 로그(logcat)
import androidx.test.ext.junit.runners.AndroidJUnit4 // JUnit4 계측 러너
import androidx.test.platform.app.InstrumentationRegistry // 대상 앱 Context 얻기
import com.cowork.stt_ondevice.core.audio.PcmConverter // WAV → 16 kHz mono float
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 파서
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.impl.TenVad // TEN VAD 엔진
import com.cowork.stt_ondevice.vad.impl.WebRtcVad // WebRTC VAD 엔진
import com.cowork.stt_ondevice.vad.impl.YamnetVad // change(add)-hyungchul-2026-10-10-15:43 Yamnet VAD 엔진(LiteRT)
import kotlinx.serialization.json.Json // JSON 파서
import kotlinx.serialization.json.jsonArray // 요소 → 배열
import kotlinx.serialization.json.jsonObject // 요소 → 객체
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 전제 미충족 시 건너뜀
import org.junit.Test // 테스트 표시
import org.junit.runner.RunWith // 러너 지정
import java.io.File // 파일 접근
import com.cowork.stt_ondevice.vad.HangoverParams // change(add)-hyungchul-2026-10-10-15:43 YAMNET 프리셋 파라미터
import com.cowork.stt_ondevice.vad.post.SimpleHangover // change(add)-hyungchul-2026-10-10-15:43 골든 점수 → 구간(비교용)
import java.security.MessageDigest // change(add)-hyungchul-2026-10-10-15:43 모델 SHA-256

// change(add)-hyungchul-2026-10-10-15:26
/**
 * 목적: 네이티브 VAD(WebRTC·TEN) 기기 스모크·골든 정합 테스트
 * 기능: 대상 앱의 외부 파일 폴더에서 음원·골든을 읽어 실제 JNI 경로로 처리한다.
 */
@RunWith(AndroidJUnit4::class)
class NativeSmokeTest {

    private val filesDir: File = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!! // 앱 외부 파일 폴더

    /**
     * 목적: 사용자 음원 앞 20 s 읽기
     * 기능: input_sound/kr/customer/01.wav 를 16 kHz mono float 로 바꾸고 앞 320000 샘플(20 s)만 남긴다.
     * 입력: 없음
     * 출력: 파일이 없으면 AssertionError(adb push 안내)
     * 리턴: FloatArray(최대 320000 샘플)
     */
    private fun first20s(): FloatArray {
        val f = File(filesDir, "input_sound/kr/customer/01.wav") // 입력 음원 경로
        assertTrue("음원 없음: ${f.absolutePath} — adb push 로 먼저 넣어야 함", f.isFile) // 파일 존재 확인
        val samples = PcmConverter.toMono16k(WavReader.read(f)).samples // 16 kHz mono float
        return samples.copyOf(minOf(samples.size, 20 * 16000)) // 0–20 s
    }

    /**
     * 목적: WebRTC(libfvad) 네이티브가 실제 음성에서 구간을 내는지 확인
     * 기능: WEBRTC 3 프리셋마다 detect → 구간 ≥ 1, 점수 0/1, 점수 개수 = 방식별 프레임 수, 구간이 0–20 s 안인지 본다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun fvadDetectsSpeechInClip() {
        val audio = first20s() // 0–20 s
        WebRtcVad().use { vad -> // 실제 네이티브 엔진
            for (preset in VadPresets.all.filter { it.family == VadFamily.WEBRTC }) { // WEBRTC 프리셋 3종
                val out = vad.detect(audio, preset) // 네이티브 판정 + 후처리
                Log.i(TAG, "${preset.id}: 구간 ${out.segments.size}개, 프레임 ${out.frameScores!!.size}, 첫 구간 ${out.segments.firstOrNull()}") // 결과 기록
                assertTrue("${preset.id}: 구간이 1개 이상이어야 함", out.segments.isNotEmpty()) // 음성 검출
                assertTrue("${preset.id}: 점수는 0/1", out.frameScores!!.all { it == 0f || it == 1f }) // WebRTC 판정
                out.segments.forEach { assertTrue("${preset.id}: 구간 범위 이상 $it", it.startSec >= 0.0 && it.endSec <= 20.0 && it.startSec < it.endSec) } // 범위
            }
        }
    }

    /**
     * 목적: TEN 네이티브가 확률을 내고 구간을 만드는지 확인
     * 기능: TEN 2 프리셋마다 detect → 확률 개수 = floor(N/256), 모든 값 [0,1], 구간 ≥ 1.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun tenVadReturnsProbabilities() {
        val audio = first20s() // 0–20 s
        TenVad().use { vad -> // 실제 네이티브 엔진
            for (preset in VadPresets.all.filter { it.family == VadFamily.TEN }) { // TEN_DEFAULT, TEN_SHERPA
                val out = vad.detect(audio, preset) // 네이티브 확률 + 후처리
                val probs = out.frameScores!! // 프레임 확률
                Log.i(TAG, "${preset.id}: 구간 ${out.segments.size}개, 프레임 ${probs.size}, 확률 범위 ${probs.minOrNull()}..${probs.maxOrNull()}") // 결과 기록
                assertEquals("${preset.id}: 확률 개수 = floor(N/256)", audio.size / 256, probs.size) // hop 256
                assertTrue("${preset.id}: 확률은 [0,1]", probs.all { it in 0f..1f }) // 범위(NaN 이면 거짓)
                assertTrue("${preset.id}: 구간이 1개 이상이어야 함", out.segments.isNotEmpty()) // 음성 검출
            }
        }
    }

    /**
     * 목적: 네이티브 판정·확률을 골든(원본 Python: py-webrtcvad, 공식 ten-vad Linux x64)과 비교
     * 기능: 기기의 golden/ 폴더에서 WEBRTC_*·TEN_* JSON 중 클립 WAV 가 있는 것을 모두 처리한다.
     *       WebRTC: 프레임 판정이 전부 같아야 하고 구간도 expected 와 같아야 한다.
     *       TEN: 확률 최대 절대오차 ≤ TEN_PROB_TOL, 구간은 expected 와 같아야 한다(플랫폼별 부동소수 차이 허용 범위 안에서).
     *       결과 요약은 logcat(TAG)에 남긴다. 골든 폴더가 없으면 건너뜀(assume).
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun nativeMatchesGoldenClips() {
        val dir = File(filesDir, "golden") // 기기 골든 폴더
        val files = dir.listFiles { f -> f.name.endsWith(".json") && (f.name.startsWith("WEBRTC_") || f.name.startsWith("TEN_")) } // 대상 JSON
            ?.sortedBy { it.name }.orEmpty() // 이름순
        assumeTrue("기기에 골든 JSON 없음(${dir.absolutePath}) — 건너뜀", files.isNotEmpty()) // 없으면 건너뜀
        val json = Json { ignoreUnknownKeys = true } // 파서
        val failures = mutableListOf<String>() // 실패 목록
        var checked = 0 // 비교한 골든 수
        WebRtcVad().use { webrtc -> // WebRTC 엔진
            TenVad().use { ten -> // TEN 엔진
                for (f in files) { // 골든마다
                    val obj = json.parseToJsonElement(f.readText()).jsonObject // JSON 객체
                    val clip = obj["clip"]!!.jsonPrimitive.content // 클립 이름
                    val wav = File(dir, "audio/$clip") // 클립 WAV
                    if (!wav.isFile) continue // 손작성(synthetic) 골든 등 음원 없는 것은 대상 아님
                    val preset = VadPresets.byId(obj["presetId"]!!.jsonPrimitive.content) // 프리셋
                    val gScores = obj["frameScores"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray() // 골든 점수
                    val expected = obj["expected"]!!.jsonArray.map { e -> // 기대 구간
                        val o = e.jsonObject // {"start","end"}
                        SpeechSegment(o["start"]!!.jsonPrimitive.content.toDouble(), o["end"]!!.jsonPrimitive.content.toDouble()) // 초 구간
                    }
                    val audio = PcmConverter.toMono16k(WavReader.read(wav)).samples // 클립 오디오
                    val out = if (preset.family == VadFamily.WEBRTC) webrtc.detect(audio, preset) else ten.detect(audio, preset) // 네이티브 실행
                    val scores = out.frameScores!! // 네이티브 점수
                    checked++ // 비교 수 증가
                    if (scores.size != gScores.size) { // 프레임 수 불일치
                        failures += "${f.name}: 프레임 수 ${scores.size} ≠ ${gScores.size}" // 기록
                        continue // 다음 골든
                    }
                    var mismatch = 0 // WebRTC 판정 불일치 수
                    var maxAbs = 0.0 // TEN 확률 최대 절대오차
                    for (i in scores.indices) { // 프레임마다
                        if ((scores[i] >= 0.5f) != (gScores[i] >= 0.5f)) mismatch++ // 0.5 기준 판정 비교
                        maxAbs = maxOf(maxAbs, Math.abs(scores[i].toDouble() - gScores[i].toDouble())) // 절대오차
                    }
                    val segOk = out.segments == expected // 구간 완전 일치 여부
                    Log.i(TAG, "${f.name}: 프레임 ${scores.size}, 판정 불일치 $mismatch, 최대 절대오차 $maxAbs, 구간 ${out.segments.size}/${expected.size} 일치=$segOk") // 요약
                    if (preset.family == VadFamily.WEBRTC && mismatch != 0) failures += "${f.name}: WebRTC 판정 불일치 $mismatch 프레임" // WebRTC 는 완전 일치
                    if (preset.family == VadFamily.TEN && maxAbs > TEN_PROB_TOL) failures += "${f.name}: TEN 확률 최대 절대오차 $maxAbs > $TEN_PROB_TOL" // TEN 허용오차
                    if (!segOk) failures += "${f.name}: 구간 불일치\n 기대=$expected\n 실제=${out.segments}" // 구간 일치
                }
            }
        }
        Log.i(TAG, "골든 비교 ${checked}건, 실패 ${failures.size}건") // 전체 요약
        assertTrue("음원 있는 골든을 하나도 비교하지 못함", checked > 0) // 실제로 비교했는지
        assertTrue("${failures.size}/$checked 불일치\n" + failures.joinToString("\n"), failures.isEmpty()) // 전부 통과
    }

    // change(add)-hyungchul-2026-10-10-15:43
    /**
     * 목적: 기기에 넣은 yamnet.tflite 파일 얻기
     * 기능: 앱 외부 파일 폴더의 models/yamnet/yamnet.tflite 가 있는지 확인한다.
     * 입력: 없음
     * 출력: 파일이 없으면 AssertionError(adb push 안내)
     * 리턴: 모델 File
     */
    private fun yamnetModel(): File {
        val f = File(filesDir, "models/yamnet/yamnet.tflite") // 모델 경로
        assertTrue("모델 없음: ${f.absolutePath} — adb push 로 먼저 넣어야 함", f.isFile) // 존재 확인
        return f // 반환
    }

    // change(add)-hyungchul-2026-10-10-15:43
    /**
     * 목적: LiteRT Yamnet 이 기기에서 동작하고 Speech 점수로 구간을 만드는지 확인
     * 기능: (1) 모델 SHA-256 = YAMNET_SHA256 (PC 에서 내장 라벨 yamnet_label_list.txt 521 개·index 0 = "Speech" 를 확인한 파일;
     *           안드로이드 ZipFile 은 앞에 모델 바이트가 붙은 zip 을 거부해 기기에서 라벨을 직접 읽지 않는다)
     *       (2) YAMNET 3 프리셋마다 detect → 점수 개수 = floor(N/243), 값 [0,1], 구간 ≥ 1, 구간 범위 0–20 s. 프리셋별 처리 시간을 logcat 에 남긴다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun yamnetRuns() {
        val model = yamnetModel() // 모델 파일
        val sha = MessageDigest.getInstance("SHA-256").digest(model.readBytes()).joinToString("") { "%02x".format(it) } // 모델 해시
        assertEquals("yamnet.tflite SHA-256", YAMNET_SHA256, sha) // 라벨을 확인한 그 모델인지
        val audio = first20s() // 0–20 s
        YamnetVad(model).use { vad -> // LiteRT 엔진
            for (preset in VadPresets.all.filter { it.family == VadFamily.YAMNET }) { // YAMNET 3 프리셋
                val t0 = System.nanoTime() // 시작 시각
                val out = vad.detect(audio, preset) // 추론 + 후처리
                val ms = (System.nanoTime() - t0) / 1_000_000 // 처리 시간(ms)
                val scores = out.frameScores!! // Speech 점수
                Log.i(TAG, "${preset.id}: 구간 ${out.segments.size}개, 프레임 ${scores.size}, 점수 범위 ${scores.minOrNull()}..${scores.maxOrNull()}, 처리 ${ms} ms, 첫 구간 ${out.segments.firstOrNull()}") // 결과 기록
                assertEquals("${preset.id}: 점수 개수 = floor(N/243)", audio.size / 243, scores.size) // hop 243
                assertTrue("${preset.id}: 점수는 [0,1]", scores.all { it in 0f..1f }) // 범위(NaN 이면 거짓)
                assertTrue("${preset.id}: 구간이 1개 이상이어야 함", out.segments.isNotEmpty()) // 음성 검출
                out.segments.forEach { assertTrue("${preset.id}: 구간 범위 이상 $it", it.startSec >= 0.0 && it.endSec <= 20.0 && it.startSec < it.endSec) } // 범위
            }
        }
    }

    // change(add)-hyungchul-2026-10-10-15:43
    /**
     * 목적: 기기 LiteRT 점수를 PC 골든(ai-edge-litert 2.3.0, 같은 모델·같은 창)과 비교
     * 기능: golden/scores_YAMNET_*.json 마다 클립 WAV 를 YAMNET_NORMAL 로 detect 해
     *       프레임 수 일치, 값이 다른 프레임 비율 ≤ YAMNET_MAX_DIFF_RATIO, 최대 절대오차 ≤ YAMNET_SCORE_STEP(양자화 1 단계)를 확인한다.
     *       다른 프레임(최대 10개)의 위치·값, 최대 오차, YAMNET 3 프리셋 구간(기기 점수 vs 골든 점수)의 일치 여부를 logcat 에 남긴다.
     *       골든 파일이 없으면 건너뜀(assume).
     * 입력: 없음
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun yamnetMatchesGoldenScores() {
        val dir = File(filesDir, "golden") // 기기 골든 폴더
        val files = dir.listFiles { f -> f.name.startsWith("scores_YAMNET_") && f.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty() // 대상 JSON
        assumeTrue("기기에 Yamnet 골든 JSON 없음(${dir.absolutePath}) — 건너뜀", files.isNotEmpty()) // 없으면 건너뜀
        val json = Json { ignoreUnknownKeys = true } // 파서
        val failures = mutableListOf<String>() // 실패 목록
        var checked = 0 // 비교한 골든 수
        YamnetVad(yamnetModel()).use { vad -> // LiteRT 엔진
            for (f in files) { // 골든마다
                val obj = json.parseToJsonElement(f.readText()).jsonObject // JSON 객체
                val wav = File(dir, "audio/" + obj["clip"]!!.jsonPrimitive.content) // 클립 WAV
                if (!wav.isFile) continue // 클립 없으면 대상 아님
                val gScores = obj["frameScores"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray() // 골든 점수
                val audio = PcmConverter.toMono16k(WavReader.read(wav)).samples // 클립 오디오
                val scores = vad.detect(audio, VadPresets.byId("YAMNET_NORMAL")).frameScores!! // 기기 점수
                checked++ // 비교 수 증가
                if (scores.size != gScores.size) { // 프레임 수 불일치
                    failures += "${f.name}: 프레임 수 ${scores.size} ≠ ${gScores.size}" // 기록
                    continue // 다음 골든
                }
                var maxAbs = 0.0 // 최대 절대오차
                val diffs = mutableListOf<String>() // 값이 다른 프레임 기록
                for (i in scores.indices) { // 프레임마다
                    val d = Math.abs(scores[i].toDouble() - gScores[i].toDouble()) // 절대오차
                    if (d > 0.0) diffs += "#$i 기기 ${scores[i]} / 골든 ${gScores[i]}" // 다른 프레임 기록
                    maxAbs = maxOf(maxAbs, d) // 최대값 갱신
                }
                val segSame = VadPresets.all.filter { it.family == VadFamily.YAMNET }.joinToString { p -> // 프리셋별 구간 일치 여부
                    val h = p.params as HangoverParams // 임계값·길이
                    val mine = SimpleHangover.apply(scores, 243.0 / 16000, h.threshold, h.speechMs, h.silenceMs) // 기기 점수 구간
                    val gold = SimpleHangover.apply(gScores, 243.0 / 16000, h.threshold, h.speechMs, h.silenceMs) // 골든 점수 구간
                    "${p.id} 구간 ${mine.size}/${gold.size} 일치=${mine == gold}" // 요약
                }
                Log.i(TAG, "${f.name}: 프레임 ${scores.size}, 값이 다른 프레임 ${diffs.size} ${diffs.take(10)}, 최대 절대오차 $maxAbs, $segSame") // 요약
                if (diffs.size > scores.size * YAMNET_MAX_DIFF_RATIO) failures += "${f.name}: 값이 다른 프레임 ${diffs.size}/${scores.size} > ${YAMNET_MAX_DIFF_RATIO * 100}%" // 비율 기준
                if (maxAbs > YAMNET_SCORE_STEP) failures += "${f.name}: 최대 절대오차 $maxAbs > 1 단계 $YAMNET_SCORE_STEP" // 1 단계 기준
            }
        }
        assertTrue("클립 있는 Yamnet 골든을 하나도 비교하지 못함", checked > 0) // 실제로 비교했는지
        assertTrue("${failures.size}/$checked 불일치\n" + failures.joinToString("\n"), failures.isEmpty()) // 전부 통과
    }

    private companion object {
        const val TAG = "NativeSmokeTest" // logcat 태그
        // change(add)-hyungchul-2026-10-10-15:43 Yamnet 비교 기준(PC x64 골든 vs 기기 arm64; S22 Ultra 실측: 1317 중 1 프레임이 1 단계 차이)
        const val YAMNET_SCORE_STEP = 1.0 / 256 // int8 출력 양자화 1 단계(점수는 k/256 값만 가짐)
        const val YAMNET_MAX_DIFF_RATIO = 0.01 // 값이 달라도 되는 프레임 비율 상한(1 %)
        const val YAMNET_SHA256 = "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de" // yamnet.tflite(Kaggle classification-tflite v1) 해시
        const val TEN_PROB_TOL = 1e-4 // TEN 확률 허용 절대오차(Linux x64 골든 vs 기기 arm64 빌드; S22 Ultra 실측 최대 2.03e-6)
    }
}
