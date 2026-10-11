// 파일명: SystemEngineDeviceTest.kt (기기 계측 테스트)
// 목적: 실제 기기에서 시스템 엔진(ML Kit GenAI Basic·Android 온디바이스 인식기)이 파이프 입력으로 사용자 음원을 전사하는지 확인 (2차 작업 6)
// 기능: - mlkitKoreanX1AndX4Match: KR 01 의 첫 25 초를 조각 1개로 mlkit-basic-x1·x4 각각 전사 → 두 텍스트가 비어 있지 않고 같으며, x4 벽시계 < x1 ÷ 2.
//         (추가 확인) x4 엔진으로 짧은 조각(10~15 초)을 한 번 더 전사해 같은 엔진(캐시된 클라이언트)의 두 번째 세션도 동작하는지 본다.
//       - platformKoreanX4: android-ondevice-asi-x4 로 같은 25 초 전사 → 비어 있지 않음, 문장부호 포함 여부는 로그만(단언 안 함).
//         (추가 확인) 같은 엔진(재사용 인식기)으로 짧은 조각 두 번째 세션.
//       - englishUnavailableIsReported: unavailableReason(ctx, spec, "en") 가 en-US 미설치면 사유를 돌려준다(설치돼 있으면 null → 그 엔진은 건너뜀).
//         사유가 있으면 실제 전사도 LANGUAGE_UNAVAILABLE 로 곧 끝나는지(공급기가 막히지 않는지) 확인한다. 다운로드(download())는 부르지 않는다.
//       입력: 앱 입력 폴더(App.from(ctx).dirs.inputSound)/kr/Customer_Service/01.wav. 없으면 assumeTrue 로 건너뜀(실패로 가장하지 않음).
//       RECORD_AUDIO(플랫폼 인식기 필요)는 허용돼 있지 않으면 UiAutomation 으로 앱 자신에게 부여한다.
//       결과(전사문·벽시계·문장부호)는 logcat 태그 SystemEngineDeviceTest 로 남긴다.
//       실행: adb shell am instrument -w -e class com.cowork.stt_ondevice.engine.system.SystemEngineDeviceTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner
// change(add)-hyungchul-2026-10-11-19:18
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지(계측 테스트)

import android.Manifest // 권한 이름
import android.content.Context // 컨텍스트
import android.content.pm.PackageManager // 권한 확인 결과
import android.util.Log // 결과 로그
import androidx.test.ext.junit.runners.AndroidJUnit4 // JUnit4 계측 러너
import androidx.test.platform.app.InstrumentationRegistry // 대상 컨텍스트·UiAutomation
import com.cowork.stt_ondevice.App // 전역 객체(입력 폴더)
import com.cowork.stt_ondevice.core.audio.PcmConverter // 16 kHz mono 변환
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 읽기
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // 전사 결과
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.SystemCatalog // 시스템 엔진 카탈로그
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.runBlocking // 테스트에서 코루틴 실행
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Assume.assumeTrue // 전제 미충족 시 건너뜀
import org.junit.Before // 준비
import org.junit.Test // 테스트 표시
import org.junit.runner.RunWith // 러너 지정
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-19:18
/**
 * 목적: 시스템 엔진 기기 동작 검증
 * 기능: 파일 머리말 참조. 각 테스트는 엔진을 만들어 prepare → transcribe → close 한다(실제 앱의 BatchRunner 와 같은 경로: SystemEngines.create).
 */
@RunWith(AndroidJUnit4::class)
class SystemEngineDeviceTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext // 대상 앱 컨텍스트
    private val wav: File by lazy { File(App.from(ctx).dirs.inputSound, "kr/Customer_Service/01.wav") } // KR 01 원음

    /**
     * 목적: 플랫폼 인식기 권한 준비
     * 기능: RECORD_AUDIO 가 허용돼 있지 않으면 UiAutomation 으로 앱 자신에게 부여한다(앱이 원래 요청하는 권한).
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
     * 목적: KR 01 구간 읽기
     * 기능: 입력 폴더의 KR 01 을 16 kHz mono 로 읽어 [startSec, startSec+durSec) 구간을 돌려준다. 파일이 없으면 테스트를 건너뛴다.
     * 입력: startSec(시작 초), durSec(길이 초)
     * 출력: 파일 없음 → AssumptionViolatedException(건너뜀)
     * 리턴: 샘플 배열
     */
    private fun koSamples(startSec: Double, durSec: Double): FloatArray {
        assumeTrue("입력 파일 없음(건너뜀): ${wav.path}", wav.isFile) // 사용자 음원이 없으면 건너뜀
        val all = PcmConverter.toMono16k(WavReader.read(wav)).samples // 16 kHz mono
        val from = (startSec * 16_000).toInt().coerceAtMost(all.size) // 시작 샘플
        val to = ((startSec + durSec) * 16_000).toInt().coerceAtMost(all.size) // 끝 샘플
        return all.copyOfRange(from, to) // 구간
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
     * 목적: 조각 만들기
     * 기능: 샘플로 단일 구간 AudioChunk 를 만든다.
     * 입력: samples(샘플), index(조각 순번)
     * 출력: 없음
     * 리턴: AudioChunk
     */
    private fun chunk(samples: FloatArray, index: Int = 0): AudioChunk =
        AudioChunk(index, samples, listOf(SourceSpan(0.0, samples.size / 16_000.0, 0.0)), SplitMethod.NONE) // 단일 구간

    /**
     * 목적: 전사 1회와 벽시계 측정
     * 기능: 열린 엔진으로 transcribe 하고 걸린 시간(ms)을 잰다.
     * 입력: engine(준비된 엔진), samples(샘플), lang(언어), index(조각 순번)
     * 출력: 엔진 예외 그대로
     * 리턴: (전사 결과, 벽시계 ms)
     */
    private suspend fun timed(engine: AsrEngine, samples: FloatArray, lang: String, index: Int = 0): Pair<ChunkText, Long> {
        val t0 = System.nanoTime() // 시작
        val r = engine.transcribe(chunk(samples, index), lang) // 전사
        return r to (System.nanoTime() - t0) / 1_000_000 // 결과·경과
    }

    /**
     * 목적: ML Kit 1×·4× 결과 일치 검증
     * 기능: ko 사용 가능 확인(null) → x1·x4 엔진으로 같은 25 초 전사 → 비어 있지 않음·같음·x4 벽시계 < x1 ÷ 2.
     *       이어서 x4 엔진으로 10~15 초 조각을 한 번 더 전사(두 번째 세션, 비어 있지 않음, 세션 번호 2).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun mlkitKoreanX1AndX4Match() = runBlocking {
        val samples = koSamples(0.0, 25.0) // 첫 25 초
        assertNull("ko 사용 가능해야 함", SystemEngines.unavailableReason(ctx, spec("mlkit-basic-x1"), "ko")) // 실행 전 확인
        val (x1, w1) = SystemEngines.create(ctx, spec("mlkit-basic-x1")).use { e -> // 1× 엔진
            log("mlkit x1 prepare: ${e.prepare(0)}") // 준비(버전 문자열)
            timed(e, samples, "ko") // 전사
        }
        log("mlkit x1 wall=${w1}ms infer=${x1.inferSec}s session=${x1.session} len=${x1.text.length} text=${x1.text}") // 결과
        val engine4 = SystemEngines.create(ctx, spec("mlkit-basic-x4")) // 4× 엔진
        engine4.use { e ->
            log("mlkit x4 prepare: ${e.prepare(0)}") // 준비
            val (x4, w4) = timed(e, samples, "ko") // 전사
            log("mlkit x4 wall=${w4}ms infer=${x4.inferSec}s session=${x4.session} len=${x4.text.length} text=${x4.text}") // 결과
            assertTrue("x1 텍스트가 비어 있음", x1.text.isNotBlank()) // 비어 있지 않음
            assertTrue("x4 텍스트가 비어 있음", x4.text.isNotBlank()) // 비어 있지 않음
            assertEquals("x1·x4 전사문이 달라야 할 이유 없음", x1.text, x4.text) // 같은 전사문
            assertTrue("x4 wall($w4) < x1 wall($w1) ÷ 2", w4 < w1 / 2) // 배속 효과
            val (second, w2) = timed(e, koSamples(10.0, 5.0), "ko", index = 1) // 같은 엔진 두 번째 세션
            log("mlkit x4 second wall=${w2}ms session=${second.session} len=${second.text.length} text=${second.text}") // 결과
            assertTrue("두 번째 세션 텍스트가 비어 있음", second.text.isNotBlank()) // 클라이언트 재사용 동작
            assertEquals(2, second.session!!.number) // 누적 세션 번호
        }
    }

    /**
     * 목적: 플랫폼 4× 전사 검증
     * 기능: ko 사용 가능 확인(null) → android-ondevice-asi-x4 로 25 초 전사 → 비어 있지 않음. 문장부호 포함 여부는 로그로만 남긴다.
     *       이어서 같은 엔진으로 10~15 초 조각을 한 번 더 전사(재사용 인식기 두 번째 세션).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun platformKoreanX4() = runBlocking {
        val samples = koSamples(0.0, 25.0) // 첫 25 초
        assertNull("ko 사용 가능해야 함", SystemEngines.unavailableReason(ctx, spec("android-ondevice-asi-x4"), "ko")) // 실행 전 확인
        SystemEngines.create(ctx, spec("android-ondevice-asi-x4")).use { e -> // 4× 엔진
            log("asi x4 prepare: ${e.prepare(0)}") // 준비
            val (r, w) = timed(e, samples, "ko") // 전사
            val punct = r.text.any { it in ".,?!" } // 문장부호 포함 여부
            log("asi x4 wall=${w}ms infer=${r.inferSec}s session=${r.session} punct=$punct len=${r.text.length} text=${r.text}") // 결과
            assertTrue("플랫폼 텍스트가 비어 있음", r.text.isNotBlank()) // 비어 있지 않음
            val (second, w2) = timed(e, koSamples(10.0, 5.0), "ko", index = 1) // 두 번째 세션
            log("asi x4 second wall=${w2}ms session=${second.session} len=${second.text.length} text=${second.text}") // 결과
            assertTrue("두 번째 세션 텍스트가 비어 있음", second.text.isNotBlank()) // 인식기 재사용 동작
        }
    }

    /**
     * 목적: en-US 미설치 보고 검증
     * 기능: ML Kit x4·플랫폼 x4 각각 unavailableReason(ctx, spec, "en") 을 부른다. null(설치됨)이면 그 엔진은 건너뛰고,
     *       사유가 있으면 비어 있지 않음을 확인한 뒤 실제 en 전사가 15 초 안에 EngineException(LANGUAGE_UNAVAILABLE)로 끝나는지 본다.
     *       두 엔진 모두 설치돼 있으면 테스트 전체를 건너뛴다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun englishUnavailableIsReported() = runBlocking {
        val samples = koSamples(10.0, 3.0) // 3 초(내용 무관 — 언어 확인용)
        var checked = 0 // 사유를 확인한 엔진 수
        for (id in listOf("mlkit-basic-x4", "android-ondevice-asi-x4")) { // 두 엔진
            val reason = SystemEngines.unavailableReason(ctx, spec(id), "en") // 실행 전 확인
            log("$id en 사유=$reason") // 기록
            if (reason == null) continue // en-US 설치됨 → 이 엔진은 건너뜀
            assertTrue("$id: 사유가 비어 있음", reason.isNotBlank()) // 사유 있음
            checked++ // 확인 수
            SystemEngines.create(ctx, spec(id)).use { e -> // 엔진
                e.prepare(0) // 준비
                val t0 = System.nanoTime() // 시작
                try {
                    e.transcribe(chunk(samples), "en") // en 전사(미설치)
                    fail("$id: en-US 미설치인데 전사가 성공함") // 여기 오면 실패
                } catch (x: EngineException) {
                    val ms = (System.nanoTime() - t0) / 1_000_000 // 경과
                    log("$id en 전사 오류 code=${x.code} ${ms}ms msg=${x.message}") // 기록
                    assertEquals("$id: 오류 코드", "LANGUAGE_UNAVAILABLE", x.code) // 언어 미설치 코드
                    assertTrue("$id: ${ms}ms 안에 끝나야 함(공급기 막힘 없음)", ms < 15_000) // 감시 시간까지 기다리지 않음
                }
            }
        }
        assumeTrue("두 엔진 모두 en-US 설치됨 — 미설치 보고 단언 건너뜀", checked > 0) // 설치돼 있으면 건너뜀
    }

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
        private const val TAG = "SystemEngineDeviceTest" // logcat 태그
    }
}
