// 파일명: SodaHangProbeTest.kt (기기 계측 테스트 — 9단계 멈춤 원인 실험 전용 진단 코드)
// 목적: 시스템 엔진(ML Kit Basic = Speech Services 의 SODA, 플랫폼 = Android System Intelligence 의 SODA)이 멈추는 원인을 가르기 위해
//       조각을 세션 1개씩 직접 넣고, 세션마다 시작·끝 시각과 결과(글자 수·SHA-1·세션 번호·재시도 여부) 또는 멈춤을 logcat 에 남긴다.
//       가설 H1(발화 뒤 긴 디지털 무음 꼬리) / H2(서비스 프로세스 누적 세션 수 한도)를 실기기에서 확인하는 데 쓴다.
// 기능: - probe: 계측 인자로 받은 조건대로 한 엔진 인스턴스(SystemEngines.create — 앱과 같은 경로, 감시 시간 규칙 그대로)를 만들어
//         조각을 차례로 transcribe 한다. 앱 배치(BatchRunner)는 거치지 않는다. 파일을 새로 저장하지 않고 메모리에서 잘라 쓴다.
//         · mode=span(기본): src 파일의 [start, end) 초 구간 하나(end=eof 면 파일 끝까지)를 sessions 번(기본 1) 넣는다.
//         · mode=vad: src 파일을 preset(기본 SILERO_DEFAULT)으로 VAD → ChunkPlanner(최대 30 초, MERGE)로 앱과 같은 조각을 만들고,
//           sessions 번(기본 조각 수) 조각을 순환하며 넣는다(세션 수 누적 실험).
//         · 멈춤(EngineStalledException)이나 엔진 오류가 나면 그 자리에서 기록하고 실패로 끝낸다(그 뒤 세션을 더 열지 않음).
//       인자(-e 이름 값): engine(필수, 예 mlkit-basic-x4), lang(기본 ko), src(입력 폴더 기준 상대 경로, 기본 kr/Customer_Service/01.wav),
//         mode(span|vad), start·end(span 용 초), preset(vad 용), sessions(세션 수), label(로그 구분용 이름).
//       engine 인자가 없으면(일반 계측 실행에 섞여 돌 때) 건너뛴다 — 서비스 세션을 쓰지 않게.
//       로그 태그: SodaHangProbe. 전사문 원문은 남기지 않는다(글자 수·SHA-1 만).
//       실행 예: adb shell am instrument -w -e class com.cowork.stt_ondevice.engine.system.SodaHangProbeTest
//                  -e engine mlkit-basic-x4 -e start 60.0 -e end 82.6 -e label B1b com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner
// change(add)-hyungchul-2026-10-12-01:00
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지(계측 테스트)

import android.Manifest // 권한 이름
import android.content.Context // 컨텍스트
import android.content.pm.PackageManager // 권한 확인 결과
import android.os.Bundle // 계측 인자
import android.util.Log // 결과 로그
import androidx.test.ext.junit.runners.AndroidJUnit4 // JUnit4 계측 러너
import androidx.test.platform.app.InstrumentationRegistry // 대상 컨텍스트·인자·UiAutomation
import com.cowork.stt_ondevice.App // 전역 객체(입력 폴더·VAD 생성기)
import com.cowork.stt_ondevice.core.audio.PcmConverter // 16 kHz mono 변환
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 읽기
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 엔진 카탈로그
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import com.cowork.stt_ondevice.segment.ChunkPlanner // VAD 구간 → chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import com.cowork.stt_ondevice.vad.VadPresets // VAD 프리셋 조회
import kotlinx.coroutines.runBlocking // 테스트에서 코루틴 실행
import org.junit.Assert.fail // 실패 처리
import org.junit.Assume.assumeTrue // 전제 미충족 시 건너뜀
import org.junit.Before // 준비
import org.junit.Test // 테스트 표시
import org.junit.runner.RunWith // 러너 지정
import java.io.File // 파일
import java.security.MessageDigest // SHA-1
import java.text.SimpleDateFormat // 시각 표기
import java.util.Date // 현재 시각
import java.util.Locale // 표기 지역
import kotlin.math.abs // 절댓값

// change(add)-hyungchul-2026-10-12-01:00
/**
 * 목적: 시스템 엔진 멈춤 원인 실험(9단계 진단)
 * 기능: 파일 머리말 참조. 테스트 하나(probe)가 계측 인자에 따라 세션을 열고 결과를 logcat 에 남긴다.
 */
@RunWith(AndroidJUnit4::class)
class SodaHangProbeTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext // 대상 앱 컨텍스트
    private val args: Bundle = InstrumentationRegistry.getArguments() // -e 인자
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US) // 로그 시각 형식

    /**
     * 목적: 플랫폼 인식기 권한 준비
     * 기능: RECORD_AUDIO 가 허용돼 있지 않으면 UiAutomation 으로 앱 자신에게 부여한다(앱이 원래 요청하는 권한, SystemEngineDeviceTest 와 같음).
     * 입력: 없음
     * 출력: 권한 부여
     * 리턴: 없음
     */
    @Before
    fun grantMic() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { // 미허용
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.RECORD_AUDIO) // 부여
            log("RECORD_AUDIO 권한을 UiAutomation 으로 부여함") // 기록
        }
    }

    /**
     * 목적: 조각을 세션 1개씩 넣는 실험
     * 기능: 인자로 엔진·입력·구간(또는 VAD 조각)·세션 수를 정하고, 한 엔진 인스턴스로 세션을 차례로 연다.
     *       세션마다 PROBE_BEGIN / PROBE_OK(또는 PROBE_STALL·PROBE_ERROR) 줄을 남긴다. 멈춤·오류는 기록 뒤 실패로 끝낸다.
     * 입력: 계측 인자(파일 머리말 참조)
     * 출력: logcat(태그 SodaHangProbe), 멈춤·오류 시 AssertionError
     * 리턴: 없음
     */
    @Test
    fun probe() = runBlocking {
        val engineId = args.getString("engine") // 엔진 id
        assumeTrue("engine 인자 없음 — 9단계 실험 전용이라 건너뜀", engineId != null) // 일반 실행에서는 세션을 쓰지 않음
        val spec = spec(engineId!!) // 카탈로그 항목
        val lang = args.getString("lang") ?: "ko" // 언어
        val src = args.getString("src") ?: "kr/Customer_Service/01.wav" // 입력 상대 경로
        val mode = args.getString("mode") ?: "span" // 조각 만드는 방식
        val label = args.getString("label") ?: "probe" // 로그 구분 이름
        val file = File(App.from(ctx).dirs.inputSound, src) // 앱 입력 폴더의 원음
        assumeTrue("입력 파일 없음(건너뜀): ${file.path}", file.isFile) // 사용자 음원이 없으면 건너뜀
        val all = PcmConverter.toMono16k(WavReader.read(file)).samples // 16 kHz mono 전체(메모리에서만 사용)
        val chunks = when (mode) { // 조각 목록
            "span" -> listOf(spanChunk(all)) // 지정 구간 1개
            "vad" -> vadChunks(all) // 앱과 같은 VAD 조각
            else -> throw IllegalArgumentException("mode 는 span|vad: $mode") // 잘못된 인자
        }
        assumeTrue("조각 없음(건너뜀)", chunks.isNotEmpty()) // 넣을 조각이 없으면 건너뜀
        val sessions = args.getString("sessions")?.toInt() ?: if (mode == "vad") chunks.size else 1 // 열 세션 수
        log("PROBE_PLAN label=$label engine=$engineId lang=$lang src=$src mode=$mode chunks=${chunks.size} sessions=$sessions") // 계획
        chunks.forEachIndexed { i, c -> log("PROBE_CHUNK label=$label i=$i ${describe(c)}") } // 조각별 길이·꼬리 특성
        SystemEngines.create(ctx, spec).use { e -> // 앱과 같은 생성 경로(공급 배속·감시 시간 규칙 그대로)
            log("PROBE_PREPARE label=$label ${e.prepare(0)}") // 준비(서비스 버전)
            for (n in 0 until sessions) { // 세션마다
                val c = chunks[n % chunks.size] // 순환해서 고른 조각
                val t0 = System.nanoTime() // 벽시계 시작
                log("PROBE_BEGIN label=$label n=${n + 1} chunk=${n % chunks.size} at=${now()} durSec=${"%.3f".format(c.samples.size / 16_000.0)}") // 세션 시작
                try {
                    val r = e.transcribe(c, lang) // 세션 1개(멈춤이면 앱처럼 1회 재시도 후 EngineStalledException)
                    val ms = (System.nanoTime() - t0) / 1_000_000 // 경과
                    log("PROBE_OK label=$label n=${n + 1} at=${now()} wallMs=$ms engineSession=${r.session?.number} retried=${r.session?.retried} " +
                        "fedBytes=${r.session?.fedBytes} maxBlockMs=${r.session?.maxBlockMs} len=${r.text.length} sha1=${sha1(r.text)}") // 결과(원문 없이)
                } catch (x: EngineStalledException) {
                    val ms = (System.nanoTime() - t0) / 1_000_000 // 경과
                    log("PROBE_STALL label=$label n=${n + 1} at=${now()} wallMs=$ms kind=${x.kind} msg=${x.message}") // 멈춤 기록
                    fail("멈춤(label=$label, 세션 ${n + 1}번째 호출, ${ms}ms): ${x.message}") // 더 진행하지 않음
                } catch (x: EngineException) {
                    val ms = (System.nanoTime() - t0) / 1_000_000 // 경과
                    log("PROBE_ERROR label=$label n=${n + 1} at=${now()} wallMs=$ms code=${x.code} msg=${x.message}") // 오류 기록
                    fail("엔진 오류(label=$label, 세션 ${n + 1}번째 호출): ${x.code} ${x.message}") // 더 진행하지 않음
                }
            }
        }
        log("PROBE_DONE label=$label at=${now()}") // 끝
    }

    /**
     * 목적: 지정 구간 조각 만들기
     * 기능: 인자 start·end(초, end=eof 면 파일 끝)로 샘플을 잘라 단일 구간 AudioChunk 를 만든다.
     * 입력: all(16 kHz 전체 샘플)
     * 출력: 인자 누락·범위 오류면 IllegalArgumentException
     * 리턴: AudioChunk
     */
    private fun spanChunk(all: FloatArray): AudioChunk {
        val start = requireNotNull(args.getString("start")) { "span 모드에는 start 인자가 필요" }.toDouble() // 시작 초
        val endArg = requireNotNull(args.getString("end")) { "span 모드에는 end 인자가 필요" } // 끝 초 문자열
        val from = (start * 16_000).toInt().coerceIn(0, all.size) // 시작 샘플
        val to = if (endArg == "eof") all.size else (endArg.toDouble() * 16_000).toInt().coerceIn(0, all.size) // 끝 샘플
        require(to > from) { "구간이 비어 있음: $start~$endArg" } // 빈 구간 거부
        val s = all.copyOfRange(from, to) // 구간 샘플
        return AudioChunk(0, s, listOf(SourceSpan(from / 16_000.0, to / 16_000.0, 0.0)), SplitMethod.NONE) // 단일 구간
    }

    /**
     * 목적: 앱과 같은 VAD 조각 만들기
     * 기능: preset(기본 SILERO_DEFAULT) 계열 VAD 엔진으로 구간을 찾고 ChunkPlanner(최대 30 초 = 시스템 엔진 기본, MERGE)로 chunk 를 만든다.
     * 입력: all(16 kHz 전체 샘플)
     * 출력: VAD 모델이 없으면 VadUnavailableException 등 그대로
     * 리턴: chunk 목록
     */
    private fun vadChunks(all: FloatArray): List<AudioChunk> {
        val preset = VadPresets.byId(args.getString("preset") ?: "SILERO_DEFAULT") // 프리셋
        val out = App.from(ctx).vadEngineFactory.create(preset.family).use { it.detect(all, preset) } // VAD 구간
        return ChunkPlanner.plan(all, 16_000, out, 30.0, ChunkMode.MERGE) // 앱 기본과 같은 묶기
    }

    /**
     * 목적: 조각 특성 요약
     * 기능: 길이, 원본 구간, 끝에서 이어지는 |x| ≤ 1 LSB(거의 디지털 무음) 꼬리 길이, 그중 정확히 0 인 샘플 비율을 문자열로 만든다.
     * 입력: c(조각)
     * 출력: 없음
     * 리턴: 설명 문자열
     */
    private fun describe(c: AudioChunk): String {
        val lsb = 1.5f / 32_768f // 1 LSB 남짓(PCM16 → float 변환 오차 포함)
        var i = c.samples.size // 꼬리 시작 위치
        while (i > 0 && abs(c.samples[i - 1]) <= lsb) i-- // 끝에서부터 거의 무음 구간을 거슬러 감
        val tail = c.samples.size - i // 꼬리 샘플 수
        val zeros = (i until c.samples.size).count { c.samples[it] == 0f } // 그중 정확히 0
        val spans = c.spans.joinToString(";") { "%.3f-%.3f".format(it.startSec, it.endSec) } // 원본 구간
        return "durSec=${"%.3f".format(c.samples.size / 16_000.0)} spans=$spans nearZeroTailSec=${"%.3f".format(tail / 16_000.0)} exactZeroInTail=$zeros/$tail" // 요약
    }

    /**
     * 목적: 카탈로그 spec 조회
     * 기능: id 로 SystemCatalog 항목을 찾는다.
     * 입력: id(예 "mlkit-basic-x4")
     * 출력: 없으면 NoSuchElementException
     * 리턴: ModelSpec
     */
    private fun spec(id: String): ModelSpec = SystemCatalog.all.first { it.id == id } // 카탈로그 항목

    /**
     * 목적: 전사문 지문
     * 기능: UTF-8 SHA-1 의 앞 12자리 16진수(원문을 남기지 않고 같음 여부만 비교).
     * 입력: s(전사문)
     * 출력: 없음
     * 리턴: 16진수 문자열
     */
    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12) // 앞 12자리

    /**
     * 목적: 현재 시각 문자열
     * 기능: 기기 시각을 HH:mm:ss.SSS 로 만든다(logcat·PC 기록과 맞춰 보기 위함).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 시각 문자열
     */
    private fun now(): String = clock.format(Date()) // 현재 시각

    /**
     * 목적: 결과 로그
     * 기능: logcat 에 남긴다(긴 문자열은 나눠서).
     * 입력: msg(내용)
     * 출력: logcat
     * 리턴: 없음
     */
    private fun log(msg: String) {
        msg.chunked(3_500).forEach { Log.i(TAG, it) } // 4 KB 제한 대비 분할
    }

    companion object {
        private const val TAG = "SodaHangProbe" // logcat 태그
    }
}
