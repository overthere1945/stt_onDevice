// 파일명: NativeSmokeTest.kt (기기 계측 테스트)
// 목적: 실제 기기에서 libstt_native.so(libfvad JNI)·libten_vad.so 가 로드·동작하는지, 네이티브 판정이 골든과 맞는지 검증
//       change(add)-hyungchul-2026-10-10-15:43 + LiteRT(libLiteRt.so·liblitert_jni.so)로 yamnet.tflite 가 동작하는지, 점수가 PC 골든과 맞는지 검증
//       change(add)-hyungchul-2026-10-10-20:57 + sherpa-onnx(libsherpa-onnx-jni.so) 오프라인·스트리밍 엔진이 카탈로그 모델로 전사하는지 검증
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
//       change(add)-hyungchul-2026-10-10-20:35 whisper.cpp(JNI) 테스트:
//       - whisperTinyTranscribes: models/whispercpp-tiny/ggml-tiny.bin(SHA-256 = 카탈로그) 로 kr/customer/01.wav 0–20 s 를 ko 전사 →
//         한글이 들어 있는 비어 있지 않은 텍스트, PrepareInfo(버전 1.9.5·CPU·f16)·로드/추론 시간·텍스트를 logcat 에 남김.
//         en/Customer_Service/01.wav 가 있으면 같은 모델로 0–20 s en 전사 결과도 logcat 에 남김(라틴 문자 포함 확인).
//       - abortStopsWithinOneSecond: tiny 모델로 0–30 s 전사를 별도 스레드에서 시작, 500 ms 뒤 cancel →
//         1 s 안에 EngineException(TRANSCRIBE, ABORTED) 로 끝나는지, 그 뒤 같은 엔진으로 0–5 s 전사가 정상인지 확인.
//         base 가 있으면 같은 측정을 하고 시간은 logcat 기록만(인코딩 중 취소는 인코딩 종료까지 기다리는 whisper.cpp 동작 때문).
//       tiny 모델이 없으면 두 테스트는 건너뜀(assume). 넣는 법(installDebug 뒤, chmod 전):
//         adb shell mkdir -p $P/models/whispercpp-tiny $P/models/whispercpp-base $P/input_sound/en/Customer_Service
//         adb push tools/models_cache/whispercpp-tiny/dl0/ggml-tiny.bin $P/models/whispercpp-tiny/ggml-tiny.bin
//         adb push tools/models_cache/whispercpp-base/dl0/ggml-base.bin $P/models/whispercpp-base/ggml-base.bin
//         adb push ~/workspace/stt/input_sound/en/Customer_Service/01.wav $P/input_sound/en/Customer_Service/01.wav
//       change(add)-hyungchul-2026-10-10-20:57 sherpa-onnx 엔진 테스트(모두 4 스레드, 음원 앞 0–20 s, 결과 text·inferSec·PrepareInfo 를 logcat 에 남김):
//       - sherpaZipformerKoTranscribes: sherpa-zipformer-ko-2024-06-24, kr → 한글 포함. sherpa 버전 1.13.8·CPU·int8 확인, 정적 onnxruntime 버전 기록
//       - sherpaZipformerEnTranscribes: sherpa-zipformer-en-2023-06-26, en → 라틴 문자 포함
//       - sherpaStreamingZipformerKo: sherpa-zipformer-streaming-ko-2024-06-16(0.1 s 블록 스트리밍), kr → 한글 포함
//       - sherpaSenseVoice: sherpa-sensevoice-small-int8-2024-07-17, kr·en → rawText 는 고정 언어 태그(<|ko|>/<|en|>)로 시작하고 태그 3개 이상,
//         text 에는 태그 없음(SenseVoiceText.clean(rawText) 와 같음)
//       - sherpaQwen3Transcribes: sherpa-qwen3asr-0.6b-int8-2026-03-25, kr(스트림 옵션 language=Korean) → 한글 포함
//       - sherpaOmnilingual300mV2: sherpa-omnilingual-ctc300m-v2-int8-2026-02-05, kr → 비어 있지 않음
//       - sherpaOmnilingualOptional: ctc300m-v1·ctc1b-v2 가 기기에 있으면 kr 결과만 기록(단언은 비어 있지 않음)
//       각 모델은 models/<id>/ 아래 카탈로그 path 그대로 있어야 하고(파일마다 SHA-256 = 카탈로그 확인), 없으면 그 테스트만 건너뜀(assume).
//       넣는 법(installDebug 뒤): 모델마다 tools/models_cache/<id>/x0/<압축 상위 폴더>/ 의 카탈로그 파일만
//         adb shell mkdir -p $P/models/<id>/tokenizer ; adb push <파일> $P/models/<id>/<카탈로그 path>
//       (connectedDebugAndroidTest 는 끝나면 앱을 지우므로 큰 모델은 installDebug + installDebugAndroidTest 후
//        adb shell am instrument -w -e class com.cowork.stt_ondevice.NativeSmokeTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner
//        로 실행하면 다시 넣지 않아도 됨)
//       change(add)-hyungchul-2026-10-10-21:41 Vosk 엔진 테스트(4 스레드 요청, 음원 앞 0–20 s, 결과 text·inferSec·PrepareInfo 를 logcat 에 남김):
//       - voskKoTranscribes: vosk-small-ko-0.22, kr/customer/01.wav → 한글 포함 + 정답지 앞쪽 발화 단어 1개 이상 + 뒤쪽 발화 단어 1개 이상(끝점 결과 누적 확인). PrepareInfo(런타임 0.3.75·CPU·카탈로그 정밀도 fp32·실제 스레드 1) 확인
//       - voskEnTranscribes: vosk-small-en-us-0.15, en/Customer_Service/01.wav → 라틴 문자 포함 + 정답지 앞쪽·뒤쪽 발화 단어 각 1개 이상
//       각 모델은 models/<id>/ 아래 카탈로그 path 그대로 있어야 하고(파일마다 SHA-256 = 카탈로그 확인), 없으면 그 테스트만 건너뜀(assume).
//       넣는 법(installDebug 뒤): tools/models_cache/<id>/x0/vosk-model-.../ 의 카탈로그 파일만
//         adb shell mkdir -p $P/models/<id>/{am,conf,graph/phones,ivector}
//         (카탈로그 path 마다) adb push tools/models_cache/<id>/x0/<압축 상위 폴더>/<path> $P/models/<id>/<path>
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
import com.cowork.stt_ondevice.engine.EngineException // change(add)-hyungchul-2026-10-10-20:35 엔진 오류
import com.cowork.stt_ondevice.engine.EngineFactory // change(add)-hyungchul-2026-10-10-20:35 엔진 생성
import com.cowork.stt_ondevice.model.ModelCatalog // change(add)-hyungchul-2026-10-10-20:35 모델 카탈로그(SHA-256)
import com.cowork.stt_ondevice.model.ModelSpec // change(add)-hyungchul-2026-10-10-20:35 모델 메타
import com.cowork.stt_ondevice.segment.AudioChunk // change(add)-hyungchul-2026-10-10-20:35 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // change(add)-hyungchul-2026-10-10-20:35 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // change(add)-hyungchul-2026-10-10-20:35 분할 방식
import kotlinx.coroutines.runBlocking // change(add)-hyungchul-2026-10-10-20:35 suspend 함수 동기 실행
import java.util.concurrent.atomic.AtomicLong // change(add)-hyungchul-2026-10-10-20:35 스레드 간 종료 시각
import java.util.concurrent.atomic.AtomicReference // change(add)-hyungchul-2026-10-10-20:35 스레드 간 결과 전달
import com.cowork.stt_ondevice.engine.ChunkText // change(add)-hyungchul-2026-10-10-20:57 전사 결과
import com.cowork.stt_ondevice.engine.sherpa.SenseVoiceText // change(add)-hyungchul-2026-10-10-20:57 SenseVoice 태그 정리
import com.k2fsa.sherpa.onnx.VersionInfo // change(add)-hyungchul-2026-10-10-20:57 sherpa 네이티브 버전 정보

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

    // change(add)-hyungchul-2026-10-10-20:35
    /**
     * 목적: 기기에 넣은 whisper 모델 폴더 찾기
     * 기능: models/<id>/<role "model" 파일> 이 있으면 SHA-256 이 카탈로그 값과 같은지 확인하고 (스펙, 모델 폴더)를 돌려준다.
     * 입력: id(카탈로그 모델 id)
     * 출력: 해시 불일치 시 AssertionError
     * 리턴: 파일이 있으면 Pair(spec, 폴더), 없으면 null
     */
    private fun installedWhisper(id: String): Pair<ModelSpec, File>? {
        val spec = ModelCatalog.byId(id) // 카탈로그 스펙
        val dir = File(filesDir, "models/$id") // 카탈로그 배치(models/<id>/)
        val entry = spec.files.single { it.role == "model" } // 모델 파일 항목
        val f = File(dir, entry.path) // 모델 파일
        if (!f.isFile) return null // 없음
        val sha = sha256(f) // 기기 파일 해시
        assertEquals("$id SHA-256", entry.sha256, sha) // 카탈로그와 같은 파일인지
        return spec to dir // 반환
    }

    // change(add)-hyungchul-2026-10-10-20:35
    /**
     * 목적: 파일 SHA-256(스트리밍)
     * 기능: 1 MiB 버퍼로 읽으며 해시한다(큰 모델을 한 번에 메모리에 올리지 않음).
     * 입력: f(파일)
     * 출력: 없음
     * 리턴: 소문자 16진 64자
     */
    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256") // 해시기
        f.inputStream().use { input -> // 파일 열기
            val buf = ByteArray(1 shl 20) // 1 MiB 버퍼
            while (true) { // 끝까지
                val n = input.read(buf) // 읽기
                if (n < 0) break // 파일 끝
                md.update(buf, 0, n) // 해시 갱신
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) } // 16진 문자열
    }

    // change(add)-hyungchul-2026-10-10-20:35
    /**
     * 목적: 음원 앞부분을 chunk 로 만들기
     * 기능: WAV 를 16 kHz mono float 로 바꾸고 앞 sec 초만 잘라 원본 매핑 1개짜리 AudioChunk 로 만든다.
     * 입력: rel(input_sound 기준 상대 경로), sec(자를 길이, 초)
     * 출력: 파일이 없으면 AssertionError
     * 리턴: AudioChunk(index 0)
     */
    private fun chunkOf(rel: String, sec: Int): AudioChunk {
        val f = File(filesDir, "input_sound/$rel") // 음원 경로
        assertTrue("음원 없음: ${f.absolutePath} — adb push 로 먼저 넣어야 함", f.isFile) // 존재 확인
        val all = PcmConverter.toMono16k(WavReader.read(f)).samples // 16 kHz mono float
        val samples = all.copyOf(minOf(all.size, sec * 16000)) // 앞 sec 초
        return AudioChunk(0, samples, listOf(SourceSpan(0.0, samples.size / 16000.0, 0.0)), SplitMethod.NONE) // chunk
    }

    // change(add)-hyungchul-2026-10-10-20:35
    /**
     * 목적: whisper.cpp tiny 가 기기에서 한국어(가능하면 영어도)를 전사하는지 확인
     * 기능: 모델 SHA-256 확인 → EngineFactory → prepare(4 스레드) → kr/customer/01.wav 0–20 s ko 전사 → 한글 포함 비어 있지 않음.
     *       PrepareInfo(버전 1.9.5, CPU, f16, 4)를 확인하고, 로드·추론 시간과 텍스트를 logcat 에 남긴다.
     *       en/Customer_Service/01.wav 가 있으면 0–20 s en 전사 결과(라틴 문자 포함)도 남긴다. 모델이 없으면 건너뜀(assume).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun whisperTinyTranscribes() {
        val installed = installedWhisper("whispercpp-tiny") // 기기 모델
        assumeTrue("기기에 whispercpp-tiny 모델 없음 — 건너뜀", installed != null) // 없으면 건너뜀
        val (spec, dir) = installed!! // 스펙·폴더
        EngineFactory.create(spec, dir).use { engine -> // 엔진 생성
            val info = runBlocking { engine.prepare(4) } // 모델 로드
            Log.i(TAG, "whisper tiny prepare: $info") // 준비 결과 기록
            assertEquals("1.9.5", info.runtimeVersion) // 릴리스 태그 버전
            assertEquals("CPU", info.backend) // CPU 전용
            assertEquals("f16", info.precision) // 파일 이름 기준 정밀도
            assertEquals(4, info.threads) // 스레드 수
            val ko = runBlocking { engine.transcribe(chunkOf("kr/customer/01.wav", 20), "ko") } // 한국어 0–20 s 전사
            Log.i(TAG, "whisper tiny ko 0–20 s: inferSec=${"%.3f".format(ko.inferSec)}, text=${ko.text}") // 결과 기록
            assertTrue("한국어 전사가 비어 있음", ko.text.isNotBlank()) // 비어 있지 않음
            assertTrue("한글이 없음: ${ko.text}", ko.text.any { it in '\uAC00'..'\uD7A3' }) // 한글 음절 포함
            assertEquals(ko.text, ko.rawText) // whisper 는 원문 = 텍스트
            assertEquals(null, ko.detectedLang) // 언어 고정이라 감지 언어 없음
            val enFile = File(filesDir, "input_sound/en/Customer_Service/01.wav") // 영어 음원(선택)
            if (enFile.isFile) { // 있으면 영어도 기록
                val en = runBlocking { engine.transcribe(chunkOf("en/Customer_Service/01.wav", 20), "en") } // 영어 0–20 s 전사
                Log.i(TAG, "whisper tiny en 0–20 s: inferSec=${"%.3f".format(en.inferSec)}, text=${en.text}") // 결과 기록
                assertTrue("영어 전사에 라틴 문자가 없음: ${en.text}", en.text.any { it in 'a'..'z' || it in 'A'..'Z' }) // 라틴 문자 포함
            } else { // 없으면 기록만
                Log.i(TAG, "영어 음원 없음(${enFile.absolutePath}) — en 전사 생략") // 생략 기록
            }
        }
    }

    // change(add)-hyungchul-2026-10-10-20:35
    /**
     * 목적: 전사 도중 cancel 했을 때 멈추기까지 걸린 시간 측정(1 회)
     * 기능: 0–30 s 전사를 별도 스레드에서 시작하고 500 ms 뒤 cancel → 스레드 종료까지 시간을 잰다.
     *       결과가 EngineException(TRANSCRIBE, ABORTED) 인지, 그 뒤 같은 엔진으로 0–5 s 전사가 정상인지(중단 플래그가 풀리는지) 확인한다.
     *       500 ms 안에 전사가 이미 끝났으면 중단을 검증할 수 없으므로 실패로 알린다.
     * 입력: spec(모델 스펙), dir(모델 폴더)
     * 출력: 위 조건 불만족 시 AssertionError
     * 리턴: cancel → 전사 스레드 종료까지 걸린 시간(ms)
     */
    private fun measureAbortMs(spec: ModelSpec, dir: File): Double {
        val chunk30 = chunkOf("kr/customer/01.wav", 30) // 0–30 s chunk
        EngineFactory.create(spec, dir).use { engine -> // 엔진 생성
            runBlocking { engine.prepare(4) } // 모델 로드
            val result = AtomicReference<Any?>(null) // 전사 결과 또는 예외
            val doneAt = AtomicLong(0L) // 전사 스레드 종료 시각(ns)
            val worker = Thread { // 전사 스레드
                try { // 결과 저장
                    result.set(runBlocking { engine.transcribe(chunk30, "ko") }) // 30 s 전사
                } catch (t: Throwable) { // 예외 저장
                    result.set(t) // 예외 결과
                } finally { // 종료 시각
                    doneAt.set(System.nanoTime()) // 기록
                }
            }
            val startAt = System.nanoTime() // 시작 시각
            worker.start() // 전사 시작
            Thread.sleep(500) // 500 ms 대기
            assertTrue("500 ms 안에 전사가 끝나 중단을 검증할 수 없음(${spec.id}): ${result.get()}", worker.isAlive) // 아직 진행 중이어야 함
            val cancelAt = System.nanoTime() // 취소 시각
            engine.cancel() // 중단 요청
            worker.join(30_000) // 최대 30 s 대기(실패 판단용)
            assertTrue("${spec.id}: cancel 뒤 30 s 가 지나도 전사 스레드가 끝나지 않음", !worker.isAlive) // 종료 확인
            val stopMs = (doneAt.get() - cancelAt) / 1e6 // 중단 소요(ms)
            Log.i(TAG, "${spec.id} abort: 시작→취소 ${"%.0f".format((cancelAt - startAt) / 1e6)} ms, 취소→종료 ${"%.1f".format(stopMs)} ms, 결과 ${result.get()}") // 기록
            val r = result.get() // 결과
            assertTrue("${spec.id}: 중단 결과가 EngineException 이 아님: $r", r is EngineException) // 예외 종류
            assertEquals("TRANSCRIBE", (r as EngineException).stage) // 단계
            assertEquals("ABORTED", r.code) // 코드
            val again = runBlocking { engine.transcribe(chunkOf("kr/customer/01.wav", 5), "ko") } // 중단 뒤 재사용
            Log.i(TAG, "${spec.id} 중단 뒤 0–5 s: inferSec=${"%.3f".format(again.inferSec)}, text=${again.text}") // 기록
            assertTrue("${spec.id}: 중단 뒤 전사가 비어 있음", again.text.isNotBlank()) // 플래그가 풀렸는지
            return stopMs // 중단 소요 반환
        }
    }

    // change(add)-hyungchul-2026-10-10-20:35
    /**
     * 목적: cancel(abort_callback)이 진행 중 전사를 1 s 안에 멈추는지 확인
     * 기능: tiny 로 measureAbortMs → ≤ 1000 ms 확인.
     *       base 가 있으면 같은 측정을 하되 시간은 logcat 기록만 한다(ABORTED·재사용은 확인):
     *       whisper.cpp v1.9.5 는 abort_callback 을 인코더 그래프(conv·encoder·cross) 전체가 끝난 뒤와 디코더 호출마다만 확인하므로
     *       인코딩 중 취소하면 그 인코딩이 끝날 때까지 기다린다 — 중단 지연 상한 = 모델의 30 s 인코딩 시간(보고서 참조).
     *       tiny 모델이 없으면 건너뜀(assume).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun abortStopsWithinOneSecond() {
        val tiny = installedWhisper("whispercpp-tiny") // tiny 모델
        assumeTrue("기기에 whispercpp-tiny 모델 없음 — 건너뜀", tiny != null) // 없으면 건너뜀
        val tinyMs = measureAbortMs(tiny!!.first, tiny.second) // tiny 측정
        assertTrue("tiny 취소→종료 ${tinyMs} ms > 1000 ms", tinyMs <= 1000.0) // 1 s 이내
        val base = installedWhisper("whispercpp-base") // base 모델(선택)
        if (base != null) { // 있으면 측정·기록
            val baseMs = measureAbortMs(base.first, base.second) // base 측정
            Log.i(TAG, "whispercpp-base 취소→종료 ${"%.1f".format(baseMs)} ms (1 s 기준은 tiny 에만 적용, 인코딩 중 취소 시 인코딩 종료까지 대기)") // 기록
        }
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: 기기에 넣은 모델 폴더 찾기(파일 여러 개)
     * 기능: models/<id>/ 아래에 스펙의 모든 파일이 있으면 각 SHA-256 이 카탈로그 값과 같은지 확인하고 (스펙, 모델 폴더)를 돌려준다.
     * 입력: id(카탈로그 모델 id)
     * 출력: 해시 불일치 시 AssertionError
     * 리턴: 모든 파일이 있으면 Pair(spec, 폴더), 하나라도 없으면 null
     */
    private fun installedModel(id: String): Pair<ModelSpec, File>? {
        val spec = ModelCatalog.byId(id) // 카탈로그 스펙
        val dir = File(filesDir, "models/$id") // 카탈로그 배치(models/<id>/)
        if (spec.files.any { !File(dir, it.path).isFile }) return null // 하나라도 없으면 없음
        for (entry in spec.files) { // 파일마다
            assertEquals("$id/${entry.path} SHA-256", entry.sha256, sha256(File(dir, entry.path))) // 카탈로그와 같은 파일인지
        }
        return spec to dir // 반환
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: sherpa 모델 하나로 여러 음원 전사(공통 절차)
     * 기능: 모델이 없으면 건너뜀(assume) → EngineFactory → prepare(4) → PrepareInfo 확인(버전 1.13.8, CPU, 카탈로그 정밀도, 4 스레드) →
     *       (상대 경로, 언어) 마다 앞 sec 초 전사 → 비어 있지 않음 확인, 로드·추론 시간·텍스트를 logcat 에 남긴다.
     * 입력: id(카탈로그 모델 id), inputs(input_sound 기준 상대 경로 to 언어 코드 목록), sec(자를 길이, 기본 20 s)
     * 출력: 실패 시 AssertionError
     * 리턴: 입력 순서대로의 ChunkText 목록
     */
    private fun sherpaRun(id: String, vararg inputs: Pair<String, String>, sec: Int = 20): List<ChunkText> {
        val installed = installedModel(id) // 기기 모델
        assumeTrue("기기에 $id 모델 없음 — 건너뜀", installed != null) // 없으면 건너뜀
        return sherpaRunInstalled(installed!!.first, installed.second, inputs.toList(), sec) // change(add)-hyungchul-2026-10-10-21:32 실제 전사는 공통 함수
    }

    // change(add)-hyungchul-2026-10-10-21:32
    /**
     * 목적: 설치 확인이 끝난 sherpa 모델로 여러 음원 전사
     * 기능: EngineFactory → prepare(4) → PrepareInfo 확인(버전 1.13.8, CPU, 카탈로그 정밀도, 4 스레드) →
     *       (상대 경로, 언어) 마다 앞 sec 초 전사 → 비어 있지 않음·detectedLang null 확인, 로드·추론 시간·텍스트를 logcat 에 남긴다.
     * 입력: spec(모델 메타), dir(모델 폴더), inputs(input_sound 기준 상대 경로 to 언어 코드 목록), sec(자를 길이, 초)
     * 출력: 실패 시 AssertionError
     * 리턴: 입력 순서대로의 ChunkText 목록
     */
    private fun sherpaRunInstalled(spec: ModelSpec, dir: File, inputs: List<Pair<String, String>>, sec: Int): List<ChunkText> {
        val id = spec.id // 로그용 id
        EngineFactory.create(spec, dir).use { engine -> // 엔진 생성
            val info = runBlocking { engine.prepare(4) } // 모델 로드
            Log.i(TAG, "$id prepare: $info") // 준비 결과 기록
            assertEquals("1.13.8", info.runtimeVersion) // sherpa 릴리스 버전
            assertEquals("CPU", info.backend) // CPU 전용
            assertEquals(spec.precision, info.precision) // 카탈로그 정밀도
            assertEquals(4, info.threads) // 스레드 수
            return inputs.map { (rel, lang) -> // 음원마다
                val r = runBlocking { engine.transcribe(chunkOf(rel, sec), lang) } // 0–sec 초 전사
                Log.i(TAG, "$id $lang 0–$sec s: inferSec=${"%.3f".format(r.inferSec)}, text=${r.text}") // 정리본 기록
                Log.i(TAG, "$id $lang 0–$sec s rawText=${r.rawText}") // 원문 기록
                assertTrue("$id $lang 전사가 비어 있음", r.text.isNotBlank()) // 비어 있지 않음
                assertEquals(null, r.detectedLang) // 언어 고정/감지 정보 없음
                r // 결과
            }
        }
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: 한글 포함 여부 판정(change(add)-hyungchul-2026-10-10-21:32 주석 형식 정리)
     * 기능: 문자열에 한글 음절(U+AC00–U+D7A3)이 하나라도 있는지 본다.
     * 입력: s(검사할 문자열)
     * 출력: 없음
     * 리턴: 있으면 true
     */
    private fun hasHangul(s: String) = s.any { it in '\uAC00'..'\uD7A3' } // 한글 음절 범위

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: 영문 포함 여부 판정(change(add)-hyungchul-2026-10-10-21:32 주석 형식 정리)
     * 기능: 문자열에 라틴 문자(a–z, A–Z)가 하나라도 있는지 본다.
     * 입력: s(검사할 문자열)
     * 출력: 없음
     * 리턴: 있으면 true
     */
    private fun hasLatin(s: String) = s.any { it in 'a'..'z' || it in 'A'..'Z' } // 영문자

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: sherpa 오프라인 zipformer(한국어)가 기기에서 전사하는지 확인
     * 기능: sherpaRun(kr 0–20 s) → 한글 포함, rawText 공백 정리 = text. sherpa 에 정적 링크된 onnxruntime 버전도 기록한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError, 모델 없으면 건너뜀)
     * 리턴: 없음
     */
    @Test
    fun sherpaZipformerKoTranscribes() {
        val (ko) = sherpaRun("sherpa-zipformer-ko-2024-06-24", "kr/customer/01.wav" to "ko") // 한국어 전사
        assertTrue("한글이 없음: ${ko.text}", hasHangul(ko.text)) // 한글 포함
        assertContainsAnyOf("ko 앞쪽 발화", ko.text, listOf("안녕하세요", "혹시", "이번주", "이번 주")) // change(add)-hyungchul-2026-10-10-21:41 정답지 첫 문장(0–5 s) 단어 — 끝점 누적이 깨져 마지막 발화만 남으면 실패
        assertContainsAnyOf("ko 뒤쪽 발화", ko.text, listOf("다행", "정말", "네요")) // change(add)-hyungchul-2026-10-10-21:41 정답지 3번째 문장(15 s 이후) 단어
        assertEquals(ko.rawText.trim().replace(Regex("\\s+"), " "), ko.text) // text = 원문 공백 정리
        Log.i(TAG, "sherpa VersionInfo: version=${VersionInfo.version}, gitSha1=${VersionInfo.gitSha1}, onnxruntime=${VersionInfo.onnxruntimeVersion}") // 버전 기록
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: sherpa 오프라인 zipformer(영어)가 기기에서 전사하는지 확인
     * 기능: sherpaRun(en 0–20 s) → 라틴 문자 포함. 영어 음원이 없으면 실패(adb push 안내).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError, 모델 없으면 건너뜀)
     * 리턴: 없음
     */
    @Test
    fun sherpaZipformerEnTranscribes() {
        val (en) = sherpaRun("sherpa-zipformer-en-2023-06-26", "en/Customer_Service/01.wav" to "en") // 영어 전사
        assertTrue("라틴 문자가 없음: ${en.text}", hasLatin(en.text)) // 영문 포함
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: sherpa 스트리밍 zipformer(한국어)가 0.1 s 블록 스트리밍으로 전사하는지 확인
     * 기능: sherpaRun(kr 0–20 s) → 한글 포함.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError, 모델 없으면 건너뜀)
     * 리턴: 없음
     */
    @Test
    fun sherpaStreamingZipformerKo() {
        val (ko) = sherpaRun("sherpa-zipformer-streaming-ko-2024-06-16", "kr/customer/01.wav" to "ko") // 스트리밍 전사
        assertTrue("한글이 없음: ${ko.text}", hasHangul(ko.text)) // 한글 포함
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: SenseVoice 가 언어 고정(ko/en)으로 전사하고 원문 태그를 보존하는지 확인
     * 기능: 같은 엔진으로 kr → en 순서로 전사(두 번째에 setConfig 로 언어 변경) →
     *       rawText 는 고정 언어 태그로 시작하고 "<|" 태그가 3개 이상(lang/emotion/event), text 에는 태그가 없고 clean(rawText) 와 같음.
     *       ko 는 한글, en 은 라틴 문자 포함.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError, 모델 없으면 건너뜀)
     * 리턴: 없음
     */
    @Test
    fun sherpaSenseVoice() {
        val (ko, en) = sherpaRun( // 두 언어 전사
            "sherpa-sensevoice-small-int8-2024-07-17",
            "kr/customer/01.wav" to "ko", "en/Customer_Service/01.wav" to "en",
        )
        for ((r, tag) in listOf(ko to "<|ko|>", en to "<|en|>")) { // 언어별 확인
            assertTrue("rawText 가 $tag 로 시작하지 않음: ${r.rawText}", r.rawText.startsWith(tag)) // 고정 언어 태그
            assertTrue("rawText 태그가 3개 미만: ${r.rawText}", Regex("""<\|[^|]*\|>""").findAll(r.rawText).count() >= 3) // lang/emotion/event
            assertTrue("text 에 태그가 남음: ${r.text}", !r.text.contains("<|")) // 정리본에 태그 없음
            assertEquals(SenseVoiceText.clean(r.rawText), r.text) // 정리 규칙 일치
        }
        assertTrue("한글이 없음: ${ko.text}", hasHangul(ko.text)) // 한국어
        assertTrue("라틴 문자가 없음: ${en.text}", hasLatin(en.text)) // 영어
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: Qwen3-ASR 0.6B 가 한국어 고정(language=Korean)으로 전사하는지 확인
     * 기능: sherpaRun(kr 0–20 s) → 한글 포함. 이어서 최대 chunk 길이(maxChunkSec 30 s)로 한 번 더 전사해
     *       KV 캐시 1024·생성 512 설정에서 결과가 나오는지 확인하고 시간·텍스트를 기록한다(잘림 여부는 텍스트 끝을 보고 판단).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError, 모델 없으면 건너뜀)
     * 리턴: 없음
     */
    @Test
    fun sherpaQwen3Transcribes() {
        val (ko) = sherpaRun("sherpa-qwen3asr-0.6b-int8-2026-03-25", "kr/customer/01.wav" to "ko") // 한국어 전사
        assertTrue("한글이 없음: ${ko.text}", hasHangul(ko.text)) // 한글 포함
        val (ko30) = sherpaRun("sherpa-qwen3asr-0.6b-int8-2026-03-25", "kr/customer/01.wav" to "ko", sec = 30) // 30 s chunk
        assertTrue("30 s 전사에 한글이 없음: ${ko30.text}", hasHangul(ko30.text)) // 한글 포함
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: Omnilingual CTC 300M v2 가 한국어 음원을 전사하는지 확인
     * 기능: sherpaRun(kr 0–20 s) → 비어 있지 않음(언어 지정 필드가 없는 모델이라 문자 종류는 단언하지 않고 기록만).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError, 모델 없으면 건너뜀)
     * 리턴: 없음
     */
    @Test
    fun sherpaOmnilingual300mV2() {
        val (ko) = sherpaRun("sherpa-omnilingual-ctc300m-v2-int8-2026-02-05", "kr/customer/01.wav" to "ko") // 한국어 전사
        Log.i(TAG, "omnilingual 300M v2 한글 포함=${hasHangul(ko.text)}") // 문자 종류 기록
    }

    // change(add)-hyungchul-2026-10-10-20:57
    /**
     * 목적: 선택 모델(Omnilingual 300M v1, 1B v2) 기록
     * 기능: 모델마다 따로 판단한다(change(add)-hyungchul-2026-10-10-21:32) — 파일이 모두 있는 모델만 kr 0–20 s 전사해 결과를 남기고,
     *       없는(또는 파일이 덜 있는) 모델은 logcat 에 "건너뜀" 만 남기고 다음 모델로 간다. 하나도 실행하지 못했으면 테스트 전체를 건너뜀(assume).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun sherpaOmnilingualOptional() {
        val ids = listOf("sherpa-omnilingual-ctc300m-v1-int8-2025-11-12", "sherpa-omnilingual-ctc1b-v2-int8-2026-02-05") // 선택 모델
        var ran = 0 // change(add)-hyungchul-2026-10-10-21:32 실행한 모델 수
        for (id in ids) { // change(add)-hyungchul-2026-10-10-21:32 모델마다 따로
            val installed = installedModel(id) // 기기 모델(SHA-256 확인 포함)
            if (installed == null) { // 없으면
                Log.i(TAG, "$id 기기에 없음(또는 파일 일부 없음) — 이 모델만 건너뜀") // 건너뜀 기록
                continue // 다음 모델
            }
            val (ko) = sherpaRunInstalled(installed.first, installed.second, listOf("kr/customer/01.wav" to "ko"), 20) // 한국어 전사
            Log.i(TAG, "$id 한글 포함=${hasHangul(ko.text)}") // 문자 종류 기록
            ran++ // 실행 수 증가
        }
        assumeTrue("선택 Omnilingual 모델이 하나도 기기에 없음 — 건너뜀", ran > 0) // 하나도 못 했으면 건너뜀 표시
    }

    // change(add)-hyungchul-2026-10-10-21:41
    /**
     * 목적: Vosk 모델 하나로 음원 앞부분 전사(공통 절차)
     * 기능: 모델이 없으면 건너뜀(assume) → EngineFactory → prepare(4) → PrepareInfo 확인(카탈로그 런타임 버전, CPU, 카탈로그 정밀도, 실제 스레드 1) →
     *       앞 20 s 전사 → 비어 있지 않음·detectedLang null 확인, 로드·추론 시간·텍스트를 logcat 에 남긴다.
     * 입력: id(카탈로그 모델 id), rel(input_sound 기준 상대 경로), lang(언어 코드)
     * 출력: 실패 시 AssertionError
     * 리턴: ChunkText
     */
    private fun voskRun(id: String, rel: String, lang: String): ChunkText {
        val installed = installedModel(id) // 기기 모델(SHA-256 확인 포함)
        assumeTrue("기기에 $id 모델 없음 — 건너뜀", installed != null) // 없으면 건너뜀
        val (spec, dir) = installed!! // 스펙·폴더
        EngineFactory.create(spec, dir).use { engine -> // 엔진 생성
            val info = runBlocking { engine.prepare(4) } // 모델 로드
            Log.i(TAG, "$id prepare: $info") // 준비 결과 기록(loadSec 포함)
            assertEquals(spec.runtimeVersion, info.runtimeVersion) // 카탈로그 런타임 버전(0.3.75)
            assertEquals("CPU", info.backend) // CPU 전용
            assertEquals(spec.precision, info.precision) // 카탈로그 정밀도
            assertEquals(1, info.threads) // Vosk 는 스레드 설정이 없어 실제 값 1
            val r = runBlocking { engine.transcribe(chunkOf(rel, 20), lang) } // 0–20 s 전사
            Log.i(TAG, "$id $lang 0–20 s: inferSec=${"%.3f".format(r.inferSec)}, text=${r.text}") // 결과 기록
            assertTrue("$id $lang 전사가 비어 있음", r.text.isNotBlank()) // 비어 있지 않음
            assertEquals(null, r.detectedLang) // 언어 고정
            return r // 결과
        }
    }

    // change(add)-hyungchul-2026-10-10-21:41
    /**
     * 목적: 텍스트에 기대 단어 중 하나가 들어 있는지 확인
     * 기능: 대소문자 무시로 words 중 하나라도 text 에 포함되면 통과(엔진 오인식 여지를 고려해 핵심 단어 여러 개 중 하나).
     * 입력: label(실패 메시지용), text(전사 결과), words(후보 단어들)
     * 출력: 하나도 없으면 AssertionError
     * 리턴: 없음
     */
    private fun assertContainsAnyOf(label: String, text: String, words: List<String>) {
        assertTrue("$label: $words 중 하나도 없음 — text=$text", words.any { text.contains(it, ignoreCase = true) }) // 포함 여부
    }

    // change(add)-hyungchul-2026-10-10-21:41
    /**
     * 목적: Vosk small KO 가 기기에서 한국어를 전사하는지 확인
     * 기능: voskRun(kr 0–20 s) → 한글 포함.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun voskKoTranscribes() {
        val ko = voskRun("vosk-small-ko-0.22", "kr/customer/01.wav", "ko") // 한국어 전사
        assertTrue("한글이 없음: ${ko.text}", hasHangul(ko.text)) // 한글 포함
    }

    // change(add)-hyungchul-2026-10-10-21:41
    /**
     * 목적: Vosk small EN-US 가 기기에서 영어를 전사하는지 확인
     * 기능: voskRun(en 0–20 s) → 라틴 문자 포함.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun voskEnTranscribes() {
        val en = voskRun("vosk-small-en-us-0.15", "en/Customer_Service/01.wav", "en") // 영어 전사
        assertTrue("영문이 없음: ${en.text}", hasLatin(en.text)) // 라틴 문자 포함
        assertContainsAnyOf("en 앞쪽 발화", en.text, listOf("hello", "reserve", "table for four")) // change(add)-hyungchul-2026-10-10-21:41 정답지 첫 문장 단어 — 끝점 누적이 깨지면 실패
        assertContainsAnyOf("en 뒤쪽 발화", en.text, listOf("wonderful", "advance", "order", "dinner")) // change(add)-hyungchul-2026-10-10-21:41 정답지 3번째 문장 단어
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
