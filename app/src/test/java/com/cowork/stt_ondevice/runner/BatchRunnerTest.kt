// 파일명: BatchRunnerTest.kt
// 목적: BatchRunner(엔진×VAD×파일 배치 실행기)의 순서·VAD 캐시·출력 트리·오류 격리·재개·취소·로그·공정성 검증
// 기능: 네이티브 없이 가짜 엔진(FakeEngine)·가짜 VAD(FakeVad/ScoredVad)·가짜 sampler·고정 환경 값으로 임시 폴더에서 배치를 실행하고
//       결과 파일(txt/ERROR.txt/result.csv/요약 CSV/detail JSON/run_manifest.json/로그)과 엔진 호출 기록을 확인한다.
//       change(add)-hyungchul-2026-10-11-12:51 (최종 리뷰 I-1·I-4) 처음 중단된 단계는 다시 시도·두 번째는 CRASH_OR_KILLED, 완료 run 의 CRASH_OR_KILLED 항목 다시 실행(rerunCrashed),
//       실행 동안 선택 모델 전부의 사용 표시(ModelStore 사용 횟수, 끝·실패·취소·거부 때 해제)를 확인한다.
//       change(add)-hyungchul-2026-10-11-18:14 (2차 작업 1) 다시 실행 정리 도중 죽은 run 을 일반 이어하기로 열면 정리가 먼저 다시 수행되는지(rerunCleanupPending) 확인한다.
// change(add)-hyungchul-2026-10-11-01:47
package com.cowork.stt_ondevice.runner // 배치 실행 테스트 패키지

import com.cowork.stt_ondevice.core.audio.WavTestBytes // 테스트 WAV 바이트 조립
import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.core.device.ResourceSampler // 자원 샘플러
import com.cowork.stt_ondevice.core.json.AppJson // 공용 JSON
import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더 구조
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // chunk 전사 결과
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 판정
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소(사용 횟수) // change(add)-hyungchul-2026-10-11-12:51
import com.cowork.stt_ondevice.model.Runtime // 런타임
import com.cowork.stt_ondevice.model.StreamingKind // 입력 방식
import com.cowork.stt_ondevice.model.VoskConfig // 엔진 설정(가짜용)
import com.cowork.stt_ondevice.output.DetailJson // 상세 JSON
import com.cowork.stt_ondevice.output.InputEntry // change(add)-hyungchul-2026-10-11-03:31 manifest 입력 항목
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.RunManifest // 실행 manifest
import com.cowork.stt_ondevice.output.STT_CSV_HEADER // PC CSV 헤더
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadEngine // VAD 계약
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 목록
import com.cowork.stt_ondevice.vad.VadUnavailableException // VAD 사용 불가 예외
import kotlinx.coroutines.CompletableDeferred // 진입 신호
import kotlinx.coroutines.CoroutineScope // 별도 코루틴 범위
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.flow.filterIsInstance // 이벤트 종류 필터
import kotlinx.coroutines.flow.take // 앞쪽 n 개만 받고 중단
import kotlinx.coroutines.flow.toList // 이벤트 수집
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.runBlocking // 테스트용 블로킹 실행
import kotlinx.coroutines.withContext // 디스패처 전환
import kotlinx.coroutines.withTimeout // 시간 제한
import org.junit.Assert.assertEquals // 같음 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Rule // 규칙
import org.junit.Test // 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.security.MessageDigest // SHA-1 계산
import java.util.Collections // 동기화 리스트
import java.util.Locale // 숫자 서식
import java.util.concurrent.CountDownLatch // 취소 대기
import java.util.concurrent.TimeUnit // 시간 단위
import java.util.concurrent.atomic.AtomicInteger // 스레드 안전 카운터

/**
 * 목적: 테스트용 가짜 STT 엔진
 * 기능: transcribe 호출을 "모델id|시작-끝" 으로 공유 목록에 기록하고 chunk spans 를 보관한다.
 *       prepareError 가 있으면 prepare 에서 던지고, failWhen 이 참인 chunk 에서 EngineException 을 던진다.
 *       blockOnCall 번째(0부터) 호출은 cancel() 이 올 때까지(최대 10초) IO 스레드에서 막혀 있다가 ABORTED 로 끝난다.
 * 입력: spec(모델), calls(공유 호출 기록), prepareError(준비 예외), failWhen(오류 chunk 판정), blockOnCall(막을 호출 순번),
 *       failError(failWhen 일 때 던질 예외, null 이면 EngineException), closeError(close 에서 던질 예외, null 이면 정상)
 */
private class FakeEngine(
    override val spec: ModelSpec, // 모델 메타
    private val calls: MutableList<String>, // 공유 호출 기록
    private val prepareError: EngineException? = null, // 준비 실패 예외
    private val failWhen: (AudioChunk) -> Boolean = { false }, // 오류를 낼 chunk 판정
    private val blockOnCall: Int? = null, // 취소까지 막을 호출 순번
    private val failError: Throwable? = null, // 오류 chunk 에서 던질 예외 // change(add)-hyungchul-2026-10-11-02:14
    private val closeError: Exception? = null, // close 에서 던질 예외 // change(add)-hyungchul-2026-10-11-02:14
) : AsrEngine {
    @Volatile var closed = false // close 호출 여부
    val cancelCount = AtomicInteger(0) // cancel 호출 수
    val spans: MutableList<List<SourceSpan>> = Collections.synchronizedList(mutableListOf()) // chunk 별 원본 매핑 기록
    val entered = CompletableDeferred<Unit>() // 막힌 호출 진입 신호
    private val cancelLatch = CountDownLatch(1) // cancel 대기 래치
    private var n = 0 // 호출 순번

    /** 목적: 가짜 준비. 기능: prepareError 가 있으면 던지고, 아니면 고정 PrepareInfo. 입력: threads. 출력: EngineException 가능. 리턴: PrepareInfo */
    override suspend fun prepare(threads: Int): PrepareInfo {
        prepareError?.let { throw it } // 준비 실패 흉내
        return PrepareInfo(0.5, "fake-1.0", "CPU", "int8", threads) // 고정 준비 결과
    }

    /** 목적: 가짜 전사. 기능: 호출 기록 후 막힘/오류/고정 텍스트 중 하나. 입력: chunk, lang. 출력: EngineException 가능. 리턴: ChunkText */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        val idx = n++ // 이번 호출 순번
        calls.add("${spec.id}|${fmt2(chunk.startSec)}-${fmt2(chunk.endSec)}") // 호출 기록
        spans.add(chunk.spans) // 매핑 기록
        if (idx == blockOnCall) { // 막을 호출이면
            withContext(Dispatchers.IO) { // 다른 스레드에서 블로킹(네이티브 전사 흉내)
                entered.complete(Unit) // 블로킹 구간 안에서 진입 알림(취소가 반드시 블로킹 중에 도착하게)
                cancelLatch.await(10, TimeUnit.SECONDS) // cancel 까지 대기
            }
            throw EngineException("TRANSCRIBE", "ABORTED", "전사 중단됨") // 중단 결과
        }
        if (failWhen(chunk)) throw failError ?: EngineException("TRANSCRIBE", "NATIVE_ERROR", "가짜 네이티브 오류") // 오류 흉내
        return ChunkText("안녕 세계", "<|ko|>안녕 세계", null, 0.1) // 고정 텍스트
    }

    /** 목적: 가짜 중단. 기능: 호출 수 증가 후 래치 해제. 입력: 없음. 출력: 없음. 리턴: 없음 */
    override fun cancel() {
        cancelCount.incrementAndGet() // 호출 수 증가
        cancelLatch.countDown() // 막힌 전사 해제
    }

    /** 목적: 가짜 해제. 기능: closed 표시 후 closeError 가 있으면 던진다. 입력: 없음. 출력: closeError. 리턴: 없음 */
    override fun close() {
        closed = true // 해제 표시
        closeError?.let { throw it } // 닫기 실패 흉내
    }
}

/**
 * 목적: 테스트용 가짜 VAD
 * 기능: 무음(전부 0)이면 구간 0개, 아니면 [시작, 길이-0.1] 한 구간. 시작은 SILERO_SAFE 0.2, 그 외 0.1(프리셋 구분용). 점수 없음.
 * 입력: family(계열), count(detect 호출 수 카운터), closeError(close 에서 던질 예외, null 이면 정상)
 */
private class FakeVad(override val family: VadFamily, private val count: AtomicInteger, private val closeError: Exception? = null) : VadEngine { // change(add)-hyungchul-2026-10-11-02:14: closeError
    /** 목적: 가짜 검출. 기능: 위 규칙대로 구간 생성. 입력: audio, preset. 출력: 없음. 리턴: VadOutput */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        count.incrementAndGet() // 호출 수 증가
        if (audio.all { it == 0f }) return VadOutput(emptyList(), null, null) // 무음 → 구간 없음
        val dur = audio.size / 16000.0 // 길이(초)
        val start = if (preset.id == "SILERO_SAFE") 0.2 else 0.1 // 프리셋별 시작
        return VadOutput(listOf(SpeechSegment(start, dur - 0.1)), null, null) // 한 구간
    }

    /** 목적: 가짜 해제. 기능: closeError 가 있으면 던진다. 입력: 없음. 출력: closeError. 리턴: 없음 */
    override fun close() { closeError?.let { throw it } } // 닫기 실패 흉내

}

/**
 * 목적: 프레임 점수를 내는 가짜 VAD(공정성 테스트용)
 * 기능: [0.5, 길이-0.5] 한 구간과 10 ms 간격 점수(모두 1, 3.3 초 프레임만 0)를 돌려준다.
 * 입력: family(계열), count(detect 호출 수 카운터)
 */
private class ScoredVad(override val family: VadFamily, private val count: AtomicInteger) : VadEngine {
    /** 목적: 점수 포함 검출. 기능: 위 규칙. 입력: audio, preset. 출력: 없음. 리턴: VadOutput */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput {
        count.incrementAndGet() // 호출 수 증가
        val dur = audio.size / 16000.0 // 길이(초)
        val frames = (dur / 0.01).toInt() // 프레임 수
        val scores = FloatArray(frames) { 1f } // 기본 점수 1
        scores[330] = 0f // 3.3 초 프레임만 최저점
        return VadOutput(listOf(SpeechSegment(0.5, dur - 0.5)), 0.01, scores) // 구간 + 점수
    }

    /** 목적: 가짜 해제. 기능: 없음. 입력: 없음. 출력: 없음. 리턴: 없음 */
    override fun close() {} // 자원 없음
}

/** 목적: 소수 2자리 문자열. 기능: Locale.ROOT 로 서식화. 입력: v(초). 출력: 없음. 리턴: 문자열 */
private fun fmt2(v: Double): String = String.format(Locale.ROOT, "%.2f", v) // 2자리 서식

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: BatchRunner 동작 회귀 방지
 * 기능: brief 의 필수 테스트 + 로그·공정성·재개 두 분기·manifest 손상·VAD 사용 불가·취소 시 engine.cancel 을 확인한다.
 */
class BatchRunnerTest {

    @get:Rule // 테스트마다 새 임시 폴더
    val tmp = TemporaryFolder()

    private val runId = "20261011_014700" // 고정 실행 ID
    private val calls: MutableList<String> = Collections.synchronizedList(mutableListOf()) // 엔진 호출 기록
    private val detects = AtomicInteger(0) // VAD detect 호출 수
    private val created: MutableList<FakeEngine> = Collections.synchronizedList(mutableListOf()) // 만들어진 엔진
    private var engineOf: (ModelSpec) -> FakeEngine = { FakeEngine(it, calls) } // 엔진 생성 규칙(테스트별 교체)
    private var vadOf: (VadFamily) -> VadEngine = { FakeVad(it, detects) } // VAD 생성 규칙(테스트별 교체)
    private var holdOf: (RunConfig) -> AutoCloseable = { AutoCloseable { } } // 모델 사용 표시 규칙(테스트별 교체) // change(add)-hyungchul-2026-10-11-12:51
    private var device = DeviceInfo("samsung", "SM-S938N", "SM8750", "16", 36, "arm64-v8a", 11800L, "1.0 (1)") // 고정 기기

    /** 목적: 앱 폴더. 기능: 임시 폴더 루트의 AppDirs. 입력: 없음. 출력: 없음. 리턴: AppDirs */
    private val dirs by lazy { AppDirs(tmp.root) } // 임시 루트

    /** 목적: 실행 폴더. 기능: output_stt/<runId>. 입력: 없음. 출력: 없음. 리턴: File */
    private val runDir by lazy { dirs.runDir(runId) } // 실행 폴더

    /** 목적: 러너 생성. 기능: 가짜 엔진/VAD/sampler/환경을 주입한다. 입력: 없음. 출력: 없음. 리턴: BatchRunner */
    private fun runner(): BatchRunner = BatchRunner(
        dirs = dirs, // 폴더
        engineFactory = { spec -> engineOf(spec).also { created.add(it) } }, // 엔진 생성 기록
        vadFactory = { family -> vadOf(family) }, // VAD 생성
        samplerFactory = { ResourceSampler(readPssKb = { 204_800L }, readCpuMs = { 0L }) }, // 고정 PSS 200 MB
        env = { EnvSnapshot(0, 0.5f, 80, true) }, // 고정 환경
        device = device, // 기기
        modelHold = { cfg -> holdOf(cfg) }, // 모델 사용 표시 // change(add)-hyungchul-2026-10-11-12:51
    )

    /** 목적: 가짜 모델. 기능: algo "fake", model=id 인 ModelSpec. 입력: id, languages, maxChunkSec. 출력: 없음. 리턴: ModelSpec */
    private fun spec(id: String, languages: Set<String> = setOf("ko", "en"), maxChunkSec: Double = 30.0): ModelSpec = ModelSpec(
        id = id, kind = ModelKind.ASR, algo = "fake", model = id, displayName = id, languages = languages, // 식별·언어
        runtime = Runtime.VOSK, runtimeVersion = "0.3", precision = "fp32", maxChunkSec = maxChunkSec, // 실행 정보
        streaming = StreamingKind.FILE_ONLY, downloads = emptyList(), files = emptyList(), originalRepo = "", // 설치 정보
        license = "test", licenseUrl = "", commercial = CommercialStatus.ALLOWED, compatAlias = null, engineConfig = VoskConfig, // 라이선스·설정
    )

    /** 목적: 입력 WAV 생성. 기능: 16k mono PCM16 파일을 input_sound/<lang>/<rel>/<stem>.wav 에 쓴다. 입력: lang, rel, stem, sec(길이), silent(무음 여부). 출력: 파일 생성. 리턴: InputItem */
    private fun item(lang: String, rel: String, stem: String, sec: Double, silent: Boolean = false): InputItem {
        val n = (sec * 16000).toInt() // 샘플 수
        val pcm = ShortArray(n) { if (silent) 0 else 1000 } // 무음 또는 상수 신호
        val f = File(File(File(dirs.inputSound, lang), rel), "$stem.wav") // 경로
        f.parentFile!!.mkdirs() // 폴더 생성
        f.writeBytes(WavTestBytes.wavBytes(16000, 1, 16, WavTestBytes.pcm16(pcm))) // WAV 저장
        return InputItem(lang, rel, stem, f) // 항목
    }

    /** 목적: 실행 설정. 기능: 고정 runId 로 RunConfig 생성. 입력: models, presets, items, resume, override. 출력: 없음. 리턴: RunConfig */
    private fun config(
        models: List<ModelSpec>, presets: List<String>, items: List<InputItem>, resume: Boolean = false, override: Map<String, Double> = emptyMap(),
    ): RunConfig = RunConfig(runId, items, models, presets.map { VadPresets.byId(it) }, ChunkMode.MERGE, override, 4, resume) // 설정

    /** 목적: CSV 읽기. 기능: BOM 제거 후 CRLF 로 줄을 나누고 쉼표로 열을 나눠 헤더→값 맵 목록을 만든다(테스트 값에는 쉼표가 없음). 입력: f. 출력: 없음. 리턴: 행 목록 */
    private fun rows(f: File): List<Map<String, String>> {
        val lines = f.readText(Charsets.UTF_8).removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() } // 줄 목록 // change(add)-hyungchul-2026-10-11-04:46 BOM 문자를 \uFEFF 이스케이프로 표기(lint ByteOrderMark)
        val header = lines.first().split(",") // 헤더
        return lines.drop(1).map { line -> // 데이터 줄마다
            val cols = line.split(",") // 열 분리
            assertEquals("열 수가 헤더와 같아야 함", header.size, cols.size) // 열 수 검사
            header.zip(cols).toMap() // 맵 변환
        }
    }

    /** 목적: manifest 읽기. 기능: run_manifest.json 파싱(실패 시 테스트 실패). 입력: 없음. 출력: 없음. 리턴: RunManifest */
    private fun manifest(): RunManifest = assertNotNullAndGet(RunManifest.read(OutputLayout.manifest(runDir))) // 파싱

    /** 목적: null 아님 확인 후 반환. 기능: assertNotNull 뒤 값 반환. 입력: v. 출력: 실패 시 AssertionError. 리턴: v */
    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! } // 확인 후 반환

    /** 목적: 조합명. 기능: fake_<model>_<preset>. 입력: model, preset. 출력: 없음. 리턴: 문자열 */
    private fun combo(model: String, preset: String) = "fake_${model}_$preset" // 조합명

    /** 목적: 실행 순서 확인. 기능: 모델 2 × 프리셋 2 × 파일 2 의 엔진 호출이 모델→프리셋→파일 순인지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun orderIsModelThenPresetThenFile() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 길이로 파일 구분
        runner().run(config(listOf(spec("m1"), spec("m2")), listOf("SILERO", "SILERO_SAFE"), files)).toList() // 실행
        val expected = mutableListOf<String>() // 기대 순서
        for (m in listOf("m1", "m2")) for (p in listOf(0.1, 0.2)) for (d in listOf(2.0, 3.0)) expected.add("$m|${fmt2(p)}-${fmt2(d - 0.1)}") // 모델→프리셋→파일
        assertEquals(expected, calls.toList()) // 순서 일치
    }

    /** 목적: VAD 캐시 확인. 기능: 모델 2개여도 detect 호출 수 = 프리셋 × 파일. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun vadComputedOncePerPresetAndFile() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        runner().run(config(listOf(spec("m1"), spec("m2")), listOf("SILERO", "SILERO_SAFE"), files)).toList() // 실행
        assertEquals(4, detects.get()) // 2 프리셋 × 2 파일
    }

    /** 목적: PC 호환 출력 트리 확인. 기능: txt 서식, result.csv 60열 1행, 요약 CSV vad 열, detail, manifest 를 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun writesPcCompatibleTree() = runBlocking {
        val it1 = item("kr", "customer", "01", 2.0) // 입력 1개
        val events = runner().run(config(listOf(spec("m1")), listOf("SILERO"), listOf(it1))).toList() // 실행
        val txt = File(runDir, "kr/fake_m1_SILERO/customer/01.txt") // 전사 txt
        assertTrue(txt.isFile) // 존재
        val lines = txt.readText(Charsets.UTF_8).split("\n") // 줄 목록
        assertEquals("[전체 텍스트] (엔진: fake m1 / VAD: SILERO / 조각 1개)", lines[0]) // 머리줄
        assertEquals("안녕 세계", lines[1]) // 전체 텍스트
        assertEquals("[구간별] start(sec), end(sec), text", lines[3]) // 구간 머리줄
        assertEquals("0.10, 1.90, 안녕 세계", lines[4]) // 원본 offset 구간
        val csv = OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO") // 조합 CSV
        assertFalse(File(runDir, "kr/fake_m1_SILERO/result.csv").exists()) // 평가 대상 폴더 안에는 result.csv 가 없어야 함(유령 모델 방지, R36) // change(add)-hyungchul-2026-10-11-02:50
        val bytes = csv.readBytes() // 바이트
        assertTrue(bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) // UTF-8 BOM
        val r = rows(csv) // 행 파싱
        assertEquals(1, r.size) // 1행
        assertEquals(STT_CSV_HEADER, r[0].keys.toList()) // 60열 헤더 순서
        val row = r[0] // 유일한 행
        val sha = MessageDigest.getInstance("SHA-1").digest("안녕 세계".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(12) // 기대 sha1
        val expect = mapOf( // 기대 값
            "run_id" to runId, "algo" to "fake", "model" to "m1", "exec_mode" to "embedded", "engine_version" to "fake-1.0", // 식별
            "rel_dir" to "customer", "file" to "01.wav", "audio_ext" to ".wav", "audio_bytes" to it1.file.length().toString(), // 입력 파일
            "audio_sec" to "2.000", "sample_rate" to "16000", "channels" to "1", "req_lang" to "ko", "detected_lang" to "", "task" to "", // 음원·언어
            "device" to "cpu", "device_name" to "SM-S938N (SM8750)", "compute_type" to "int8", "workers" to "4", // 실행 환경
            "rss_peak_mb" to "200.0", "cpu_time_sec" to "0.000", "upload_sec" to "", "attempts" to "1", // 자원·시간
            "status" to "OK", "utterances" to "1", "chars" to "5", "words" to "2", "text_sha1" to sha, // 결과
            "note" to "vad=SILERO;chunk_mode=MERGE;max_chunk=30.0;alias=", "text" to "안녕 세계", // 메모·텍스트
            "diarization" to "", "cost" to "", "gpu_util_avg" to "", // 미사용 열은 빈 값
        )
        for ((k, v) in expect) assertEquals("열 $k", v, row[k]) // 열마다 비교
        assertEquals(row["transcribe_sec"], row["cloud_total_sec"]) // 로컬은 업로드 0
        assertTrue(Regex("""\d+\.\d{4}""").matches(row["rtf"]!!)) // rtf 소수 4자리
        assertTrue(Regex("""\d+\.\d{3}""").matches(row["preprocess_sec"]!!)) // preprocess 3자리
        val summary = rows(OutputLayout.summaryCsv(runDir, runId)) // 요약 CSV
        assertEquals(STT_CSV_HEADER + "vad", summary[0].keys.toList()) // 61열
        assertEquals("SILERO", summary[0]["vad"]) // vad 열
        assertEquals(row, summary[0] - "vad") // 같은 행
        val detail = AppJson.decodeFromString(DetailJson.serializer(), OutputLayout.detail(runDir, it1, combo("m1", "SILERO")).readText()) // detail
        assertEquals("OK", detail.status) // 상태
        assertEquals(false, detail.vadReused) // 첫 계산
        assertEquals(0.5, detail.loadSec!!, 0.0) // 로드 시간
        assertEquals(1, detail.chunks.size) // chunk 1개
        assertNotNull(detail.envStart) // 시작 환경
        val m = manifest() // manifest
        assertNotNull(m.finishedAt) // 종료 기록
        assertNull(m.inFlight) // 진행 중 없음
        assertEquals(0.5, m.models[0].loadSec!!, 0.0) // 모델 로드 시간 기록
        assertEquals(listOf(InputEntry("kr", "customer", "01.wav")), m.inputs) // change(add)-hyungchul-2026-10-11-03:31 입력 항목 기록(이어하기 복원용)
        assertEquals(RunEvent.Finished(1, 0, 0, 0), events.last()) // 종료 이벤트
        assertTrue(events.first() is RunEvent.Progress) // 첫 이벤트는 진행률
    }

    /** 목적: 무음 파일 처리. 기능: 구간 0개 → 엔진 호출 없음, txt 빈 전체 텍스트, CSV NO_MATCH. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun silentFileWritesNoMatch() = runBlocking {
        val it1 = item("kr", "customer", "01", 2.0, silent = true) // 무음 파일
        runner().run(config(listOf(spec("m1")), listOf("SILERO"), listOf(it1))).toList() // 실행
        assertEquals(0, calls.size) // 엔진 호출 없음
        val lines = File(runDir, "kr/fake_m1_SILERO/customer/01.txt").readText().split("\n") // txt 줄
        assertEquals("[전체 텍스트] (엔진: fake m1 / VAD: SILERO / 조각 0개)", lines[0]) // 머리줄
        assertEquals("", lines[1]) // 빈 전체 텍스트
        val row = rows(OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO")).single() // CSV 행
        assertEquals("NO_MATCH", row["status"]) // 상태
        assertEquals("0", row["utterances"]) // 발화 0
        assertEquals("", row["text"]) // 빈 텍스트
        assertEquals("0.000", row["transcribe_sec"]) // 엔진 미호출 → 전사 시간 0(측정값) // change(add)-hyungchul-2026-10-11-02:14
        assertEquals("0.0000", row["rtf"]) // rtf 0
        assertEquals("0.000", row["cloud_total_sec"]) // transcribe 와 같음
    }

    /** 목적: 오류 격리 확인. 기능: 두 번째 파일 엔진 예외 → 02.ERROR.txt, 세 번째 파일은 정상. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun errorIsolated() = runBlocking {
        engineOf = { FakeEngine(it, calls, failWhen = { c -> c.endSec in 2.85..2.95 }) } // 3초 파일(끝 2.9)에서 오류
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0), item("kr", "customer", "03", 4.0)) // 파일 3개
        val events = runner().run(config(listOf(spec("m1")), listOf("SILERO"), files)).toList() // 실행
        val dir = File(runDir, "kr/fake_m1_SILERO/customer") // 조합 폴더
        assertTrue(File(dir, "01.txt").isFile) // 첫 파일 정상
        assertTrue(File(dir, "02.ERROR.txt").isFile) // 두 번째 오류 파일
        assertFalse(File(dir, "02.txt").exists()) // 두 번째 txt 없음
        assertTrue(File(dir, "03.txt").isFile) // 세 번째 정상
        val err = File(dir, "02.ERROR.txt").readText() // 오류 내용
        assertTrue(err.contains("stage: TRANSCRIBE")) // 단계
        assertTrue(err.contains("code: NATIVE_ERROR")) // 코드
        val r = rows(OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO")) // CSV
        assertEquals(listOf("OK", "ERROR", "OK"), r.map { it["status"] }) // 상태 순서
        assertTrue(r[1]["note"]!!.endsWith(";stage=TRANSCRIBE;code=NATIVE_ERROR")) // note 에 stage/code
        assertEquals("", r[1]["text"]) // 오류 행 텍스트 없음
        assertEquals(RunEvent.Finished(2, 0, 1, 0), events.last()) // 집계
    }

    /** 목적: 모델 로드 실패 처리. 기능: prepare 예외 → 그 모델 항목 전부 ERROR(MODEL_LOAD), 다음 모델 정상. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun modelLoadFailureMarksAllItems() = runBlocking {
        engineOf = { s -> if (s.id == "m1") FakeEngine(s, calls, prepareError = EngineException("MODEL_LOAD", "OOM", "메모리 부족")) else FakeEngine(s, calls) } // m1 로드 실패
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        runner().run(config(listOf(spec("m1"), spec("m2")), listOf("SILERO", "SILERO_SAFE"), files)).toList() // 실행
        for (p in listOf("SILERO", "SILERO_SAFE")) for (s in listOf("01", "02")) { // m1 항목 전부
            val err = File(runDir, "kr/${combo("m1", p)}/customer/$s.ERROR.txt") // 오류 파일
            assertTrue(err.isFile) // 존재
            assertTrue(err.readText().contains("stage: MODEL_LOAD")) // 단계
            assertTrue(err.readText().contains("code: OOM")) // 코드
            assertTrue(File(runDir, "kr/${combo("m2", p)}/customer/$s.txt").isFile) // m2 정상
        }
        assertTrue(calls.all { it.startsWith("m2|") }) // m1 은 전사 호출 없음
        assertTrue(created.first { it.spec.id == "m1" }.closed) // 실패 모델도 close
        val m = manifest() // manifest
        assertNull(m.models[0].loadSec) // 실패 모델 로드 시간 없음
        assertTrue(m.models[0].loadError!!.contains("OOM")) // 실패 사유 기록
        assertEquals(0.5, m.models[1].loadSec!!, 0.0) // 정상 모델 로드 시간
        assertEquals(4, rows(OutputLayout.resultCsv(runDir, "kr", combo("m1", "SILERO"))).size + rows(OutputLayout.resultCsv(runDir, "kr", combo("m1", "SILERO_SAFE"))).size) // m1 오류 행 4개
        val r1 = rows(OutputLayout.resultCsv(runDir, "kr", combo("m1", "SILERO")))[0] // m1 오류 행 // change(add)-hyungchul-2026-10-11-02:14
        assertEquals("0.3", r1["engine_version"]) // prepare 전 실패 → 카탈로그 런타임 버전
        assertEquals("", r1["compute_type"]) // 실제 정밀도 모름 → 빈 칸
        assertEquals("4", r1["workers"]) // 설정 스레드 수
        assertEquals("", r1["audio_sec"]) // 미측정 → 빈 칸
        assertTrue(r1["note"]!!.endsWith(";stage=MODEL_LOAD;code=OOM")) // note
        val r2 = rows(OutputLayout.resultCsv(runDir, "kr", combo("m2", "SILERO")))[0] // m2 정상 행
        assertEquals("fake-1.0", r2["engine_version"]) // prepare 보고 버전
        assertEquals("int8", r2["compute_type"]) // prepare 보고 정밀도
    }

    /** 목적: 언어 미지원 조합 처리. 기능: ko 전용 모델 × en 파일 → en 폴더 없음, manifest.unsupported 기록, Finished.unsupported 집계. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun unsupportedComboCreatesNoFolder() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("en", "vLog", "01", 2.0)) // 한국어·영어 파일
        val events = runner().run(config(listOf(spec("m1", languages = setOf("ko"))), listOf("SILERO"), files)).toList() // 실행
        assertFalse(File(runDir, "en").exists()) // en 결과 폴더 없음
        assertFalse(File(runDir, "details/en").exists()) // en detail 없음
        assertFalse(File(runDir, "vad_segments/SILERO/en").exists()) // en VAD 계산 없음
        assertTrue(File(runDir, "kr/fake_m1_SILERO/customer/01.txt").isFile) // kr 는 정상
        assertEquals(listOf("fake_m1_SILERO|en"), manifest().unsupported) // manifest 기록
        assertEquals(RunEvent.Finished(1, 0, 0, 1), events.last()) // 집계
        assertEquals(1, calls.size) // kr 만 전사
    }

    /** 목적: 이어하기 건너뛰기 확인. 기능: 1회차를 2개 완료 후 중단 → resume 2회차는 남은 2개만 엔진 호출. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun resumeSkipsCompletedItems() = runBlocking {
        val files = (1..4).map { item("kr", "customer", "0$it", 1.0 + it) } // 파일 4개(길이 2~5초)
        runner().run(config(listOf(spec("m1")), listOf("SILERO"), files)).filterIsInstance<RunEvent.ItemDone>().take(2).toList() // 2개 후 중단
        assertEquals(2, calls.size) // 1회차 2회 호출
        val m1 = manifest() // 중단 후 manifest
        assertNull(m1.finishedAt) // 끝나지 않음
        assertNull(m1.inFlight) // 사용자 중단은 진행 중 항목을 남기지 않음
        calls.clear() // 기록 초기화
        val events = runner().run(config(listOf(spec("m1")), listOf("SILERO"), files, resume = true)).toList() // 이어하기
        assertEquals(listOf("m1|0.10-3.90", "m1|0.10-4.90"), calls.toList()) // 남은 2개만
        assertEquals(4, rows(OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO")).size) // CSV 4행
        assertEquals(4, rows(OutputLayout.summaryCsv(runDir, runId)).size) // 요약도 4행
        assertNotNull(manifest().finishedAt) // 이번엔 완료
        assertTrue(manifest().resume) // 이어하기 표시
        assertEquals(RunEvent.Finished(2, 0, 0, 0), events.last()) // 이번 실행 집계
    }

    /** 목적: 크래시 항목 처리(txt 없음 분기). 기능: inFlight 가 남은 상태로 resume → 그 항목 ERROR(CRASH_OR_KILLED), 엔진 재호출 없음, 나머지 진행. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     *  change(add)-hyungchul-2026-10-11-12:51 이제 ERROR 는 같은 키를 두 번째로 만났을 때만 → manifest.inFlightAttempts[키]=1(이미 한 번 다시 시도함) 상태로 흉내낸다. 정리 뒤 횟수는 지워진다. */
    @Test
    fun resumeMarksInFlightItemAsCrashed() = runBlocking {
        val files = (1..3).map { item("kr", "customer", "0$it", 1.0 + it) } // 파일 3개
        val cfg = config(listOf(spec("m1")), listOf("SILERO"), files) // 설정
        runner().run(cfg).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // 01 만 완료
        val key = RunManifest.inFlightKey(combo("m1", "SILERO"), files[1]) // 02 의 키
        manifest().copy(inFlight = key, inFlightAttempts = mapOf(key to 1)).write(OutputLayout.manifest(runDir)) // 02 다시 시도 중에 또 크래시 흉내 // change(add)-hyungchul-2026-10-11-12:51 두 번째
        calls.clear() // 기록 초기화
        runner().run(cfg.copy(resume = true)).toList() // 이어하기
        val dir = File(runDir, "kr/fake_m1_SILERO/customer") // 조합 폴더
        val err = File(dir, "02.ERROR.txt") // 크래시 기록
        assertTrue(err.isFile) // 존재
        assertTrue(err.readText().contains("stage: TRANSCRIBE")) // 단계
        assertTrue(err.readText().contains("code: CRASH_OR_KILLED")) // 코드
        assertFalse(File(dir, "02.txt").exists()) // txt 없음
        assertEquals(listOf("m1|0.10-3.90"), calls.toList()) // 03 만 전사
        val r = rows(OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO")) // CSV
        assertEquals(listOf("01.wav" to "OK", "02.wav" to "ERROR", "03.wav" to "OK"), r.map { it["file"] to it["status"] }) // 행
        assertTrue(r[1]["note"]!!.endsWith(";stage=TRANSCRIBE;code=CRASH_OR_KILLED")) // note
        assertNull(manifest().inFlight) // 정리됨
        assertTrue(manifest().inFlightAttempts.isEmpty()) // 횟수도 정리됨 // change(add)-hyungchul-2026-10-11-12:51
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 처음 중단된 항목은 다시 실행(I-1)
     * 기능: 02 처리 중 프로세스가 한 번 죽은 흔적(inFlight=02, 부분 txt, detail 없음, 횟수 기록 없음)으로 resume 하면 ERROR 를 쓰지 않고
     *       부분 결과를 지운 뒤 02 를 다시 전사한다. 다시 시도하는 동안 manifest.inFlightAttempts[02]=1 이 저장돼 있고(또 죽으면 다음 재개에서 ERROR), 성공하면 지워진다.
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun resumeRetriesItemInterruptedOnce() = runBlocking {
        val files = (1..3).map { item("kr", "customer", "0$it", 1.0 + it) } // 파일 3개
        val cfg = config(listOf(spec("m1")), listOf("SILERO"), files) // 설정
        runner().run(cfg).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // 01 만 완료
        val key = RunManifest.inFlightKey(combo("m1", "SILERO"), files[1]) // 02 의 키
        val dir = File(runDir, "kr/fake_m1_SILERO/customer") // 조합 폴더
        File(dir, "02.txt").writeText("부분") // 부분 결과(detail 없음) 흉내
        manifest().copy(inFlight = key).write(OutputLayout.manifest(runDir)) // 02 처리 중 한 번 죽음 흉내(OS 종료 등)
        calls.clear() // 기록 초기화
        val seen = Collections.synchronizedList(mutableListOf<Map<String, Int>>()) // 엔진 생성 시점의 횟수
        engineOf = { s -> seen.add(manifest().inFlightAttempts); FakeEngine(s, calls) } // 생성 시 manifest 확인
        val events = runner().run(cfg.copy(resume = true)).toList() // 이어하기
        assertEquals(listOf(mapOf(key to 1)), seen.toList()) // 다시 시도 동안 횟수 1 저장
        assertEquals(listOf("m1|0.10-2.90", "m1|0.10-3.90"), calls.toList()) // 02 다시 전사 + 03
        assertFalse(File(dir, "02.ERROR.txt").exists()) // ERROR 아님
        assertTrue(File(dir, "02.txt").readText().startsWith("[전체 텍스트]")) // 새 결과로 교체
        for (f in listOf(OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO"), OutputLayout.summaryCsv(runDir, runId))) { // 두 CSV
            assertEquals(listOf("01.wav" to "OK", "02.wav" to "OK", "03.wav" to "OK"), rows(f).map { it["file"] to it["status"] }) // 항목마다 1행
        }
        assertTrue(manifest().inFlightAttempts.isEmpty()) // 성공 후 횟수 제거
        assertEquals(RunEvent.Finished(2, 0, 0, 0), events.last()) // 이번 실행 집계(02·03)
        assertTrue(OutputLayout.log(runDir, runId).readLines().any { it.contains("\tRECONCILE\t") && it.contains("\tRETRY\t") }) // 다시 시도 로그
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 처음 중단된 모델 로드는 다시 로드(I-1)
     * 기능: inFlight="m2|MODEL_LOAD"(횟수 기록 없음)로 resume 하면 m2 를 ERROR 로 만들지 않고 다시 로드해 모든 항목을 전사하고, 로드 성공 뒤 횟수를 지운다.
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun modelLoadInterruptedOnceIsRetried() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val cfg = config(listOf(spec("m1"), spec("m2")), listOf("SILERO"), files) // 설정
        runner().run(cfg).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // m1/01 만 완료
        manifest().copy(inFlight = RunManifest.modelLoadKey("m2")).write(OutputLayout.manifest(runDir)) // m2 로드 중 한 번 죽음 흉내
        calls.clear() // 기록 초기화
        created.clear() // 생성 기록 초기화
        val events = runner().run(cfg.copy(resume = true)).toList() // 이어하기
        assertTrue(created.any { it.spec.id == "m2" }) // m2 다시 로드
        assertEquals(listOf("m1|0.10-2.90", "m2|0.10-1.90", "m2|0.10-2.90"), calls.toList()) // 남은 항목 전부 전사
        assertFalse(File(runDir, "kr/${combo("m2", "SILERO")}/customer/01.ERROR.txt").exists()) // ERROR 아님
        val m = manifest() // manifest
        assertTrue(m.inFlightAttempts.isEmpty()) // 로드 성공 후 횟수 제거
        assertEquals(0.5, m.models[1].loadSec!!, 1e-9) // 로드 시간 기록
        assertNull(m.models[1].loadError) // 로드 오류 없음
        assertEquals(RunEvent.Finished(3, 0, 0, 0), events.last()) // 집계
    }

    /** 목적: 크래시 항목 처리(txt 있음 분기). 기능: txt·detail 은 썼지만 CSV 행 전에 죽은 경우 → 엔진 재호출 없이 detail 로 CSV 행 복구(원래 행과 동일), 행이 이미 있으면 중복 없음, 반쯤 쓴 줄 제거. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun resumeInFlightWithTxtRestoresMissingCsvRow() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val cfg = config(listOf(spec("m1")), listOf("SILERO"), files) // 설정
        runner().run(cfg).toList() // 정상 완료
        val csv = OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO") // 조합 CSV
        val summary = OutputLayout.summaryCsv(runDir, runId) // 요약 CSV
        val csvFull = csv.readText(Charsets.UTF_8) // 원래 내용
        val summaryFull = summary.readText(Charsets.UTF_8) // 원래 요약
        val txt = File(runDir, "kr/fake_m1_SILERO/customer/02.txt") // 02 txt
        val txtBefore = txt.readText() // 원래 txt
        val key = RunManifest.inFlightKey(combo("m1", "SILERO"), files[1]) // 02 키
        // 1) CSV 행 전에 죽음: 마지막 행 제거 + 반쯤 쓴 줄 추가
        csv.writeText(csvFull.substring(0, csvFull.trimEnd().lastIndexOf("\r\n") + 2) + "20261011_014700,partial", Charsets.UTF_8) // 02 행 삭제 + 찢긴 줄
        summary.writeText(summaryFull.substring(0, summaryFull.trimEnd().lastIndexOf("\r\n") + 2), Charsets.UTF_8) // 요약 02 행 삭제
        manifest().copy(inFlight = key, finishedAt = null).write(OutputLayout.manifest(runDir)) // 진행 중 표시
        calls.clear() // 기록 초기화
        runner().run(cfg.copy(resume = true)).toList() // 이어하기
        assertEquals(0, calls.size) // 엔진 재호출 없음
        assertEquals(txtBefore, txt.readText()) // txt 유지
        assertFalse(File(runDir, "kr/fake_m1_SILERO/customer/02.ERROR.txt").exists()) // 오류로 바꾸지 않음
        assertEquals(csvFull, csv.readText(Charsets.UTF_8)) // 복구된 CSV = 원래 CSV(행 동일, 찢긴 줄 제거)
        assertEquals(summaryFull, summary.readText(Charsets.UTF_8)) // 요약도 원래와 동일
        // 2) CSV 행까지 쓰고 inFlight 정리 전에 죽음: 중복 행이 생기면 안 됨
        manifest().copy(inFlight = key, finishedAt = null).write(OutputLayout.manifest(runDir)) // 다시 진행 중 표시
        runner().run(cfg.copy(resume = true)).toList() // 이어하기
        assertEquals(csvFull, csv.readText(Charsets.UTF_8)) // 중복 없음
        assertEquals(summaryFull, summary.readText(Charsets.UTF_8)) // 요약 중복 없음
        assertNull(manifest().inFlight) // 정리됨
    }

    /** 목적: 손상 manifest 이어하기 거부. 기능: resume=true 인데 manifest 를 읽을 수 없으면 예외, 기존 결과 유지, 엔진 미생성. 새 실행이 기존 run 폴더를 덮으려 해도 예외. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun unreadableManifestOnResumeFails() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0)) // 파일 1개
        val mf = OutputLayout.manifest(runDir) // manifest 경로
        mf.parentFile!!.mkdirs() // 폴더
        mf.writeText("{ 깨진 json") // 손상
        try {
            runner().run(config(listOf(spec("m1")), listOf("SILERO"), files, resume = true)).toList() // 이어하기 시도
            fail("손상 manifest 이어하기는 예외여야 함") // 예외가 없으면 실패
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("run_manifest.json")) // 사유에 파일명
        }
        assertEquals("{ 깨진 json", mf.readText()) // 기존 파일 유지
        assertTrue(created.isEmpty()) // 엔진 생성 없음
        assertFalse(File(runDir, "kr").exists()) // 결과 없음
        try {
            runner().run(config(listOf(spec("m1")), listOf("SILERO"), files, resume = false)).toList() // 같은 run 폴더에 새 실행
            fail("기존 run 폴더 위 새 실행은 예외여야 함") // 예외가 없으면 실패
        } catch (e: IllegalStateException) {
            assertNotNull(e.message) // 사유
        }
        assertEquals("{ 깨진 json", mf.readText()) // 여전히 유지
    }

    // change(add)-hyungchul-2026-10-11-03:31
    /** 목적: 입력 구성이 다른 이어하기 거부. 기능: 1회차 파일 2개 중 1개 후 중단 → 파일 1개만 담은 resume 은 예외(입력 구성 다름), 엔진 추가 생성 없음. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun resumeWithDifferentInputsFails() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        runner().run(config(listOf(spec("m1")), listOf("SILERO"), files)).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // 1개 후 중단
        val before = created.size // 지금까지 만든 엔진 수
        try {
            runner().run(config(listOf(spec("m1")), listOf("SILERO"), files.take(1), resume = true)).toList() // 파일 1개로 이어하기 시도
            fail("입력 구성이 다른 이어하기는 예외여야 함") // 예외가 없으면 실패
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("입력 파일")) // 한국어 사유
        }
        assertEquals(before, created.size) // 엔진 추가 생성 없음
    }

    /** 목적: VAD 사용 불가 처리. 기능: SILERO 생성 실패 → SILERO 조합 항목 전부 ERROR(VAD, VAD_UNAVAILABLE), 다른 계열 프리셋은 정상, 생성 시도는 계열당 1회. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun vadUnavailableMarksErrorsAndContinues() = runBlocking {
        val attempts = AtomicInteger(0) // SILERO 생성 시도 수
        vadOf = { f -> if (f == VadFamily.SILERO) { attempts.incrementAndGet(); throw VadUnavailableException(f, "모델 폴더 없음") } else FakeVad(f, detects) } // SILERO 불가
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val events = runner().run(config(listOf(spec("m1"), spec("m2")), listOf("SILERO", "WEBRTC_ANDROIDVAD"), files)).toList() // 실행
        for (m in listOf("m1", "m2")) for (s in listOf("01", "02")) { // 모델·파일마다
            val err = File(runDir, "kr/${combo(m, "SILERO")}/customer/$s.ERROR.txt") // SILERO 오류
            assertTrue(err.readText().contains("stage: VAD")) // 단계
            assertTrue(err.readText().contains("code: VAD_UNAVAILABLE")) // 코드
            assertTrue(File(runDir, "kr/${combo(m, "WEBRTC_ANDROIDVAD")}/customer/$s.txt").isFile) // WEBRTC 정상
        }
        assertEquals(1, attempts.get()) // 실패도 계열당 1회만 시도
        assertEquals(RunEvent.Finished(4, 0, 4, 0), events.last()) // 집계
    }

    /** 목적: 취소 시 엔진 해제. 기능: 전사 중 코루틴 취소 → 엔진 closed, manifest finishedAt/inFlight null, 결과 파일 없음. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun cancelClosesEngine() = runBlocking {
        val eng = cancelDuringTranscribe() // 취소 시나리오 실행
        assertTrue(eng.closed) // 해제됨
        val m = manifest() // manifest
        assertNull(m.finishedAt) // 끝나지 않음
        assertNull(m.inFlight) // 진행 중 표시 정리
        assertFalse(File(runDir, "kr/fake_m1_SILERO/customer/01.txt").exists()) // txt 없음
        assertFalse(File(runDir, "kr/fake_m1_SILERO/customer/01.ERROR.txt").exists()) // 오류로도 기록 안 함
    }

    /** 목적: 취소가 engine.cancel 로 전달되는지 확인. 기능: 네이티브처럼 막힌 transcribe 가 코루틴 취소만으로 풀리는지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun cancellationCallsEngineCancel() = runBlocking {
        val eng = cancelDuringTranscribe() // 취소 시나리오 실행
        assertTrue(eng.cancelCount.get() >= 1) // cancel 호출됨
    }

    /** 목적: 취소 시나리오 공통 실행. 기능: 첫 transcribe 에서 막히는 엔진으로 실행 → 진입 확인 후 Job 취소 → 종료 대기(10초 제한). 입력: 없음. 출력: 시간 초과 시 예외. 리턴: 사용한 FakeEngine */
    private suspend fun cancelDuringTranscribe(afterEntered: () -> Unit = {}): FakeEngine {
        val eng = FakeEngine(spec("m1"), calls, blockOnCall = 0) // 첫 호출에서 막힘
        engineOf = { eng } // 이 엔진 사용
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val job = CoroutineScope(Dispatchers.Default).launch { runner().run(config(listOf(spec("m1")), listOf("SILERO"), files)).toList() } // 별도 코루틴
        withTimeout(10_000) { eng.entered.await() } // 전사 진입 대기
        afterEntered() // 테스트별 추가 동작(취소 직전)
        job.cancel() // 취소
        withTimeout(5_000) { job.join() } // 종료 대기(cancel 이 전달되지 않으면 10초 막혀 시간 초과)
        return eng // 엔진 반환
    }

    /** 목적: 실행 로그 확인. 기능: 항목 수만큼 ITEM_END 줄과 MODEL_LOAD 줄이 있는지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun writesRunLog() = runBlocking {
        val files = (1..3).map { item("kr", "customer", "0$it", 1.0 + it) } // 파일 3개
        runner().run(config(listOf(spec("m1")), listOf("SILERO"), files)).toList() // 실행
        val lines = OutputLayout.log(runDir, runId).readLines() // 로그 줄
        assertEquals(3, lines.count { it.contains("\tITEM_END\t") }) // 항목 종료 3줄
        assertEquals(1, lines.count { it.contains("\tMODEL_LOAD\t") }) // 모델 로드 1줄
        assertTrue(lines.any { it.contains("\tITEM_END\t") && it.contains("fake_m1_SILERO") && it.contains("kr/customer/01") && it.contains("\tOK\t") }) // 내용
    }

    /** 목적: 엔진 간 공정성 확인. 기능: 두 번째 모델이 VAD 를 디스크에서 읽어도 chunk 구간이 첫 모델과 같고 MIN_SCORE 분할(3.3초)이 유지되는지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun enginesGetIdenticalChunksFromCachedVad() = runBlocking {
        vadOf = { f -> ScoredVad(f, detects) } // 점수 VAD
        val it1 = item("kr", "customer", "01", 12.0) // 12초 파일
        runner().run(config(listOf(spec("m1"), spec("m2")), listOf("SILERO"), listOf(it1), override = mapOf("m1" to 5.0, "m2" to 5.0))).toList() // 실행
        val e1 = created.first { it.spec.id == "m1" } // 첫 모델
        val e2 = created.first { it.spec.id == "m2" } // 두 번째 모델
        assertEquals(1, detects.get()) // VAD 1회
        assertTrue(e1.spans.size > 1) // 여러 chunk
        assertEquals(e1.spans.toList(), e2.spans.toList()) // 구간 동일
        assertEquals(3.3, e1.spans[0].last().endSec, 1e-6) // 점수 최저점에서 분할
        val d2 = AppJson.decodeFromString(DetailJson.serializer(), OutputLayout.detail(runDir, it1, combo("m2", "SILERO")).readText()) // m2 detail
        assertEquals(true, d2.vadReused) // 디스크 재사용
        assertTrue(d2.vadFrameScoresAvailable) // 점수 복원
        assertTrue(rows(OutputLayout.resultCsv(runDir, "kr", combo("m2", "SILERO")))[0]["note"]!!.contains("max_chunk=5.0")) // 실효 maxChunk 기록
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 닫기 실패 기록 확인. 기능: 엔진·VAD close 가 예외를 던져도 실행은 끝나고 로그에 CLOSE_FAILED(원인 포함)가 남는다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun closeFailuresAreLogged() = runBlocking {
        engineOf = { FakeEngine(it, calls, closeError = IllegalStateException("release 실패")) } // 엔진 close 실패
        vadOf = { FakeVad(it, detects, closeError = IllegalStateException("vad 닫기 실패")) } // VAD close 실패
        val events = runner().run(config(listOf(spec("m1")), listOf("SILERO"), listOf(item("kr", "customer", "01", 2.0)))).toList() // 실행
        assertEquals(RunEvent.Finished(1, 0, 0, 0), events.last()) // 실행은 정상 종료
        val lines = OutputLayout.log(runDir, runId).readLines() // 로그
        assertTrue(lines.any { it.contains("\tCLOSE_FAILED\t") && it.contains("fake_m1") && it.contains("release 실패") }) // 엔진 닫기 실패 기록
        assertTrue(lines.any { it.contains("\tCLOSE_FAILED\t") && it.contains("SILERO") && it.contains("vad 닫기 실패") }) // VAD 닫기 실패 기록
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 취소 시 manifest 저장 실패 기록 확인. 기능: manifest 경로를 비어 있지 않은 폴더로 바꿔 저장을 실패시키면 CANCEL_SAVE_FAILED 줄이 남고 취소는 그대로 끝난다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun cancelSaveFailureIsLogged() = runBlocking {
        val eng = cancelDuringTranscribe { // 취소 직전에
            val mf = OutputLayout.manifest(runDir) // manifest 경로
            mf.delete() // 파일 제거
            File(mf, "block").apply { parentFile!!.mkdirs(); writeText("x") } // 같은 이름의 비어 있지 않은 폴더 → 원자적 교체 실패
        }
        assertTrue(eng.closed) // 엔진은 닫힘
        val lines = OutputLayout.log(runDir, runId).readLines() // 로그
        assertTrue(lines.any { it.contains("\tCANCEL_SAVE_FAILED\t") }) // 저장 실패 기록
        assertTrue(lines.any { it.contains("\tRUN_CANCELLED\t") }) // 취소 기록
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 모델 로드 단계 표시와 크래시 재개 확인. 기능: 로드 중 inFlight="m2|MODEL_LOAD" 가 저장되고, 그 표시가 남은 채 재개하면 m2 를 로드하지 않고 남은 항목을 ERROR(MODEL_LOAD, CRASH_OR_KILLED)로 기록한다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun modelLoadCrashOnResumeSkipsModel() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String?>()) // 엔진 생성 시점의 inFlight
        engineOf = { s -> seen.add(RunManifest.read(OutputLayout.manifest(runDir))?.inFlight); FakeEngine(s, calls) } // 생성 시 manifest 확인
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val cfg = config(listOf(spec("m1"), spec("m2")), listOf("SILERO"), files) // 설정
        runner().run(cfg).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // m1/01 만 완료
        assertEquals(listOf<String?>("m1|MODEL_LOAD"), seen.toList()) // 로드 직전 표시 저장 확인
        val loadKey = RunManifest.modelLoadKey("m2") // m2 로드 표시 // change(add)-hyungchul-2026-10-11-12:51
        manifest().copy(inFlight = loadKey, inFlightAttempts = mapOf(loadKey to 1)).write(OutputLayout.manifest(runDir)) // m2 다시 로드하다 또 크래시 흉내 // change(add)-hyungchul-2026-10-11-12:51 두 번째
        calls.clear() // 기록 초기화
        created.clear() // 생성 기록 초기화
        val events = runner().run(cfg.copy(resume = true)).toList() // 이어하기
        assertTrue(created.none { it.spec.id == "m2" }) // m2 는 다시 로드하지 않음
        assertEquals(listOf("m1|0.10-2.90"), calls.toList()) // m1/02 만 전사
        for (s in listOf("01", "02")) { // m2 항목마다
            val err = File(runDir, "kr/${combo("m2", "SILERO")}/customer/$s.ERROR.txt").readText() // 오류 파일
            assertTrue(err.contains("stage: MODEL_LOAD")) // 단계
            assertTrue(err.contains("code: CRASH_OR_KILLED")) // 코드
        }
        val m = manifest() // manifest
        assertNull(m.inFlight) // 표시 해제
        assertNull(m.models[1].loadSec) // m2 로드 시간 없음
        assertTrue(m.models[1].loadError!!.contains("CRASH_OR_KILLED")) // m2 로드 오류 기록
        assertEquals(RunEvent.Finished(1, 0, 2, 0), events.last()) // 이번 실행 집계(m1/02 OK, m2 오류 2)
        assertTrue(m.inFlightAttempts.isEmpty()) // 횟수 정리 // change(add)-hyungchul-2026-10-11-12:51
        calls.clear() // 기록 초기화
        try { // change(add)-hyungchul-2026-10-11-12:51 완료된 run 은 일반 이어하기로 다시 열지 않음(반복 크래시 없음)
            runner().run(cfg.copy(resume = true)).toList() // 한 번 더 이어하기
            fail("완료된 run 의 이어하기는 거부되어야 함") // 예외가 없으면 실패
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("이미 완료된 실행")) // 사유
        }
        assertTrue(calls.isEmpty()) // 더 할 일 없음(반복 크래시 없음)
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: VAD 생성 단계 표시와 크래시 재개 확인. 기능: 생성 중 inFlight="SILERO|VAD_INIT" 가 저장되고, 그 표시가 남은 채 재개하면 SILERO 엔진을 만들지 않고 그 계열 항목(모든 모델)을 ERROR(VAD, CRASH_OR_KILLED)로 기록한다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun vadInitCrashOnResumeSkipsFamily() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<String>()) // 생성 시점의 "계열=inFlight"
        val siloAttempts = AtomicInteger(0) // SILERO 생성 수
        vadOf = { f -> // 생성 시 manifest 확인
            seen.add("${f.name}=${RunManifest.read(OutputLayout.manifest(runDir))?.inFlight}") // 기록
            if (f == VadFamily.SILERO) siloAttempts.incrementAndGet() // SILERO 생성 수
            FakeVad(f, detects) // 엔진
        }
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val cfg = config(listOf(spec("m1"), spec("m2")), listOf("SILERO", "WEBRTC_ANDROIDVAD"), files) // 설정
        runner().run(cfg).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // m1/SILERO/01 만 완료
        assertEquals(listOf("SILERO=SILERO|VAD_INIT"), seen.toList()) // 생성 직전 표시 저장 확인
        val vadKey = RunManifest.vadInitKey(VadFamily.SILERO) // SILERO 생성 표시 // change(add)-hyungchul-2026-10-11-12:51
        manifest().copy(inFlight = vadKey, inFlightAttempts = mapOf(vadKey to 1)).write(OutputLayout.manifest(runDir)) // SILERO 다시 만들다 또 크래시 흉내 // change(add)-hyungchul-2026-10-11-12:51 두 번째
        siloAttempts.set(0) // 초기화
        runner().run(cfg.copy(resume = true)).toList() // 이어하기
        assertEquals(0, siloAttempts.get()) // SILERO 엔진을 다시 만들지 않음
        assertTrue(File(runDir, "kr/${combo("m1", "SILERO")}/customer/01.txt").isFile) // 이미 끝난 항목은 유지
        for ((m, s) in listOf("m1" to "02", "m2" to "01", "m2" to "02")) { // 남은 SILERO 항목마다
            val err = File(runDir, "kr/${combo(m, "SILERO")}/customer/$s.ERROR.txt").readText() // 오류 파일
            assertTrue(err.contains("stage: VAD")) // 단계
            assertTrue(err.contains("code: CRASH_OR_KILLED")) // 코드
        }
        for (m in listOf("m1", "m2")) for (s in listOf("01", "02")) assertTrue(File(runDir, "kr/${combo(m, "WEBRTC_ANDROIDVAD")}/customer/$s.txt").isFile) // 다른 계열은 정상
        assertNull(manifest().inFlight) // 표시 해제
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 크래시 정리 시 기존 행 교체 확인. 기능: txt·CSV 행은 있는데 detail 이 깨진 항목을 재개하면 ERROR 로 바뀌고 CSV·요약에 그 항목 행이 정확히 1개(ERROR)만 남는다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun crashCleanupReplacesExistingRows() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val cfg = config(listOf(spec("m1")), listOf("SILERO"), files) // 설정
        runner().run(cfg).toList() // 정상 완료
        OutputLayout.detail(runDir, files[1], combo("m1", "SILERO")).writeText("{ 깨짐") // 02 detail 손상
        val key = RunManifest.inFlightKey(combo("m1", "SILERO"), files[1]) // 02 키 // change(add)-hyungchul-2026-10-11-12:51
        manifest().copy(inFlight = key, inFlightAttempts = mapOf(key to 1), finishedAt = null).write(OutputLayout.manifest(runDir)) // 02 진행 중 표시(두 번째) // change(add)-hyungchul-2026-10-11-12:51 두 번째
        runner().run(cfg.copy(resume = true)).toList() // 이어하기
        assertTrue(File(runDir, "kr/fake_m1_SILERO/customer/02.ERROR.txt").isFile) // 오류로 정리
        assertFalse(File(runDir, "kr/fake_m1_SILERO/customer/02.txt").exists()) // txt 제거
        val r = rows(OutputLayout.resultCsv(runDir, "kr", "fake_m1_SILERO")) // 조합 CSV
        assertEquals(listOf("01.wav" to "OK", "02.wav" to "ERROR"), r.map { it["file"] to it["status"] }) // 02 행 1개(ERROR)
        val sm = rows(OutputLayout.summaryCsv(runDir, runId)) // 요약 CSV
        assertEquals(listOf("01.wav" to "OK", "02.wav" to "ERROR"), sm.map { it["file"] to it["status"] }) // 요약도 같음
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: LinkageError 격리 확인. 기능: 엔진이 NoClassDefFoundError 를 던져도 그 항목만 ERROR(TRANSCRIBE, LINKAGE_ERROR)이고 다음 항목은 계속된다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun linkageErrorIsIsolated() = runBlocking {
        engineOf = { FakeEngine(it, calls, failWhen = { c -> c.endSec < 2.0 }, failError = NoClassDefFoundError("com/x/Missing")) } // 첫 파일에서 클래스 누락
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val events = runner().run(config(listOf(spec("m1")), listOf("SILERO"), files)).toList() // 실행
        val err = File(runDir, "kr/fake_m1_SILERO/customer/01.ERROR.txt").readText() // 오류 파일
        assertTrue(err.contains("stage: TRANSCRIBE")) // 단계
        assertTrue(err.contains("code: LINKAGE_ERROR")) // 코드
        assertTrue(File(runDir, "kr/fake_m1_SILERO/customer/02.txt").isFile) // 다음 항목 정상
        assertEquals(RunEvent.Finished(1, 0, 1, 0), events.last()) // 집계
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 비정상 종료 기록 확인. 기능: 수집 쪽 예외로 실행이 끝나면 로그에 RUN_FAILED 줄이 남고 예외가 전파된다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun runFailureIsLogged() = runBlocking {
        try {
            runner().run(config(listOf(spec("m1")), listOf("SILERO"), listOf(item("kr", "customer", "01", 2.0)))).collect { ev -> // 수집
                if (ev is RunEvent.ItemDone) throw IllegalStateException("수집기 오류") // 수집 쪽 실패
            }
            fail("예외가 전파되어야 함") // 예외가 없으면 실패
        } catch (e: IllegalStateException) {
            assertEquals("수집기 오류", e.message) // 원래 예외
        }
        assertTrue(OutputLayout.log(runDir, runId).readLines().any { it.contains("\tRUN_FAILED\t") && it.contains("수집기 오류") }) // 기록
    }

    // change(add)-hyungchul-2026-10-11-02:14
    /** 목적: 이어하기 설정 검증 확인. 기능: 앱 버전·모델 정밀도·모델 파일 hash 가 이전 실행과 다르면 이어하기를 거부한다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun resumeRejectsChangedAppOrModel() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0)) // 파일 1개
        val base = spec("m1") // 원래 모델
        runner().run(config(listOf(base), listOf("SILERO"), files)).filterIsInstance<RunEvent.Progress>().take(1).toList() // 시작만 하고 중단
        /** 목적: 이어하기 거부 확인. 기능: 예외 메시지에 기대 문구가 있는지 본다. 입력: models(모델), expect(문구). 출력: 실패 시 AssertionError. 리턴: Unit */
        suspend fun expectReject(models: List<ModelSpec>, expect: String) {
            try {
                runner().run(config(models, listOf("SILERO"), files, resume = true)).toList() // 이어하기 시도
                fail("거부되어야 함: $expect") // 예외가 없으면 실패
            } catch (e: IllegalStateException) {
                assertTrue(e.message, e.message!!.contains(expect)) // 사유
            }
        }
        val origDevice = device // 원래 기기
        device = device.copy(appVersion = "2.0 (2)") // 앱 버전 변경
        expectReject(listOf(base), "앱 버전") // 거부
        device = origDevice // 복원
        expectReject(listOf(base.copy(precision = "int8")), "정밀도") // 정밀도 변경 거부
        expectReject(listOf(base.copy(files = listOf(com.cowork.stt_ondevice.model.ModelFile("a.bin", 1L, "00".repeat(32), "model")))), "SHA-256") // 파일 hash 변경 거부
        runner().run(config(listOf(base), listOf("SILERO"), files, resume = true)).toList() // 같은 설정은 허용
        assertNotNull(manifest().finishedAt) // 완료
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 완료 run 의 CRASH_OR_KILLED 항목 다시 실행(I-1)
     * 기능: m1/02 가 두 번 중단돼 ERROR(CRASH_OR_KILLED), m3 는 일반 로드 실패(LOAD_FAILED)로 끝난 완료 run 에서
     *       - 일반 이어하기는 "이미 완료" 로 거부, 설정(스레드)을 바꾼 다시 실행도 거부(결과 그대로)
     *       - rerunCrashed 는 m1/02 의 ERROR.txt·detail·CSV 행만 지우고 그 항목만 다시 전사(m3 는 로드하지 않음)
     *       - 두 CSV 모두 항목마다 정확히 1행, CRASH_OR_KILLED 행 없음, LOAD_FAILED 행 유지, Finished 는 다시 실행한 1건, 다시 완료 표시.
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun rerunCrashedCleansAndRerunsOnlyCrashedItems() = runBlocking {
        engineOf = { s -> if (s.id == "m3") FakeEngine(s, calls, prepareError = EngineException("MODEL_LOAD", "LOAD_FAILED", "가짜 로드 실패")) else FakeEngine(s, calls) } // m3 는 로드 실패
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val cfg = config(listOf(spec("m1"), spec("m3")), listOf("SILERO"), files) // 설정
        runner().run(cfg).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // m1/01 만 완료
        val key = RunManifest.inFlightKey(combo("m1", "SILERO"), files[1]) // m1/02 키
        manifest().copy(inFlight = key, inFlightAttempts = mapOf(key to 1)).write(OutputLayout.manifest(runDir)) // 두 번째 중단 흉내
        runner().run(cfg.copy(resume = true)).toList() // 이어하기 → m1/02 CRASH, m3 LOAD_FAILED
        val dir = File(runDir, "kr/${combo("m1", "SILERO")}/customer") // m1 조합 폴더
        assertTrue(File(dir, "02.ERROR.txt").readText().contains("code: CRASH_OR_KILLED")) // 크래시 기록
        assertNotNull(manifest().finishedAt) // 완료 run
        val comboCsv = OutputLayout.resultCsv(runDir, "kr", combo("m1", "SILERO")) // m1 조합 CSV
        val summary = OutputLayout.summaryCsv(runDir, runId) // 요약 CSV
        val summaryBefore = summary.readText(Charsets.UTF_8) // 거부 전 요약
        suspend fun expectReject(c: RunConfig, expect: String) { // 거부 확인
            try {
                runner().run(c).toList() // 시도
                fail("거부되어야 함: $expect") // 예외가 없으면 실패
            } catch (e: IllegalStateException) {
                assertTrue(e.message, e.message!!.contains(expect)) // 사유
            }
        }
        expectReject(cfg.copy(resume = true), "이미 완료된 실행") // 일반 이어하기 거부
        expectReject(cfg.copy(resume = true, rerunCrashed = true, threads = 2), "스레드") // 설정 변경 거부
        assertTrue(File(dir, "02.ERROR.txt").isFile) // 거부 때는 결과 그대로
        assertEquals(summaryBefore, summary.readText(Charsets.UTF_8)) // 요약 그대로
        calls.clear() // 기록 초기화
        created.clear() // 생성 기록 초기화
        val events = runner().run(cfg.copy(resume = true, rerunCrashed = true)).toList() // 크래시 항목 다시 실행
        assertEquals(listOf("m1|0.10-2.90"), calls.toList()) // 크래시 항목만 전사
        assertTrue(created.none { it.spec.id == "m3" }) // 일반 오류 모델은 다시 로드하지 않음
        assertFalse(File(dir, "02.ERROR.txt").exists()) // 크래시 기록 제거
        assertTrue(File(dir, "02.txt").isFile) // 새 결과
        val d = AppJson.decodeFromString(DetailJson.serializer(), OutputLayout.detail(runDir, files[1], combo("m1", "SILERO")).readText()) // 새 detail
        assertEquals("OK", d.status) // 정상
        assertEquals(listOf("01.wav" to "OK", "02.wav" to "OK"), rows(comboCsv).map { it["file"] to it["status"] }) // 조합 CSV 항목마다 1행
        val sm = rows(summary) // 요약 행
        assertEquals(4, sm.size) // 2 모델 × 2 파일
        assertEquals(4, sm.map { it["model"] to it["file"] }.toSet().size) // 중복 없음
        assertTrue(sm.none { it["note"]!!.contains("CRASH_OR_KILLED") }) // 크래시 행 없음
        assertEquals(2, sm.count { it["model"] == "m3" && it["note"]!!.endsWith(";code=LOAD_FAILED") }) // 일반 오류 행 유지
        assertEquals(RunEvent.Finished(1, 0, 0, 0), events.last()) // 이번 실행 = 다시 실행한 1건
        assertNotNull(manifest().finishedAt) // 다시 완료
        assertTrue(OutputLayout.log(runDir, runId).readLines().any { it.contains("\tRERUN_CRASHED\t") && it.contains("1개") }) // 다시 실행 로그
    }

    // change(add)-hyungchul-2026-10-11-18:14
    /**
     * 목적: 다시 실행 정리 도중 죽은 run 의 일반 이어하기(2차 작업 1)
     * 기능: 크래시 항목(m1/02)이 있는 run 을 "다시 실행 정리 중 죽은 상태"로 만든다 — manifest finishedAt=null, rerunCleanupPending=true,
     *       그 항목의 CSV 행은 이미 없고 ERROR.txt 는 남음. 이 run 을 일반 이어하기(resume=true, rerunCrashed=false)로 시작하면
     *       정리가 먼저 다시 수행되어 그 항목이 다시 실행되고, 두 CSV 에 그 항목이 정확히 1행이며 끝난 뒤 rerunCleanupPending 이 false 이다.
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun rerunCleanupInterruptedIsRedoneOnPlainResume() = runBlocking {
        val files = listOf(item("kr", "customer", "01", 2.0), item("kr", "customer", "02", 3.0)) // 파일 2개
        val cfg = config(listOf(spec("m1")), listOf("SILERO"), files) // 설정
        runner().run(cfg).filterIsInstance<RunEvent.ItemDone>().take(1).toList() // m1/01 만 완료
        val key = RunManifest.inFlightKey(combo("m1", "SILERO"), files[1]) // m1/02 키
        manifest().copy(inFlight = key, inFlightAttempts = mapOf(key to 1)).write(OutputLayout.manifest(runDir)) // 두 번째 중단 흉내
        runner().run(cfg.copy(resume = true)).toList() // 이어하기 → m1/02 CRASH_OR_KILLED
        val dir = File(runDir, "kr/${combo("m1", "SILERO")}/customer") // m1 조합 폴더
        assertTrue(File(dir, "02.ERROR.txt").readText().contains("code: CRASH_OR_KILLED")) // 크래시 기록
        val comboCsv = OutputLayout.resultCsv(runDir, "kr", combo("m1", "SILERO")) // 조합 CSV
        val summary = OutputLayout.summaryCsv(runDir, runId) // 요약 CSV
        CsvFiles.removeRows(comboCsv, mapOf("file" to "02.wav")) // 행 정리까지만 끝난 상태: 조합 행 없음
        CsvFiles.removeRows(summary, mapOf("file" to "02.wav")) // 요약 행 없음
        manifest().copy(finishedAt = null, rerunCleanupPending = true).write(OutputLayout.manifest(runDir)) // 정리 도중 죽은 manifest
        assertTrue(File(dir, "02.ERROR.txt").isFile) // ERROR.txt 는 남음
        assertEquals(listOf("01.wav"), rows(comboCsv).map { it["file"] }) // 02 행 없음
        calls.clear() // 기록 초기화
        val events = runner().run(cfg.copy(resume = true, rerunCrashed = false)).toList() // 일반 이어하기
        assertEquals(listOf("m1|0.10-2.90"), calls.toList()) // 정리가 다시 수행되어 그 항목이 다시 전사됨
        assertFalse(File(dir, "02.ERROR.txt").exists()) // 크래시 기록 제거
        assertTrue(File(dir, "02.txt").isFile) // 새 결과
        assertEquals(listOf("01.wav" to "OK", "02.wav" to "OK"), rows(comboCsv).map { it["file"] to it["status"] }) // 조합 CSV 항목마다 정확히 1행
        val sm = rows(summary) // 요약 행
        assertEquals(listOf("01.wav", "02.wav"), sm.map { it["file"] }) // 요약도 정확히 1행씩
        assertEquals(RunEvent.Finished(1, 0, 0, 0), events.last()) // 이번 실행 = 다시 실행한 1건
        assertFalse(manifest().rerunCleanupPending) // 표식 해제
        assertNotNull(manifest().finishedAt) // 다시 완료
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 실행 동안 선택 모델 전부 사용 표시(I-4)
     * 기능: modelHold 를 실제 ModelStore.acquireAll(선택 ASR 모델 + VAD 모델)로 주면, m1 을 만들 때(m2 는 아직 로드 전) m1·m2·VAD 모델이 모두 사용 중이고
     *       m2 삭제가 거부된다. 정상 종료·수집 쪽 예외·취소·이어하기 거부(manifest 불일치) 모든 경로에서 끝나면 사용 횟수가 0 으로 돌아온다.
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun modelHoldCoversAllSelectedModelsOnEveryPath() = runBlocking {
        val store = ModelStore(dirs) // 실제 저장소(사용 횟수)
        val m1 = spec("m1") // 첫 모델
        val m2 = spec("m2") // 아직 로드 전인 모델
        val vadModel = spec("vad-silero") // VAD 계열 모델(흉내)
        holdOf = { cfg -> store.acquireAll(cfg.models + vadModel) } // 앱과 같은 방식(ASR + VAD 모델)
        val seen = Collections.synchronizedList(mutableListOf<String>()) // 엔진 생성 시점 관찰
        engineOf = { s -> seen.add("${s.id}:${store.useCountOf(m1)},${store.useCountOf(m2)},${store.useCountOf(vadModel)},del=${store.delete(m2)}"); FakeEngine(s, calls) } // 관찰
        /** 목적: 사용 횟수 0 확인. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
        fun assertReleased() { for (s in listOf(m1, m2, vadModel)) assertEquals(s.id, 0, store.useCountOf(s)) } // 모두 해제
        val files = listOf(item("kr", "customer", "01", 2.0)) // 파일 1개
        val cfg = config(listOf(m1, m2), listOf("SILERO"), files) // 설정
        runner().run(cfg).toList() // 정상 종료
        assertEquals("m1:1,1,1,del=false", seen.first()) // m1 로드 때 m2·VAD 도 사용 중, m2 삭제 거부
        assertReleased() // 끝나면 해제
        try { // 수집 쪽 예외로 끝남
            runner().run(cfg.copy(runId = "20261011_014702")).collect { if (it is RunEvent.ItemDone) throw IllegalStateException("수집기 오류") } // 실패
            fail("예외가 전파되어야 함") // 예외가 없으면 실패
        } catch (e: IllegalStateException) {
            assertEquals("수집기 오류", e.message) // 원래 예외
        }
        assertReleased() // 실패에도 해제
        try { // 이어하기 거부(이미 완료된 run)
            runner().run(cfg.copy(resume = true)).toList() // 거부
            fail("거부되어야 함") // 예외가 없으면 실패
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("이미 완료된 실행")) // 사유
        }
        assertReleased() // 거부에도 해제
        val h = store.acquireAll(listOf(m1, m1, m2)) // 중복 포함
        assertEquals(1, store.useCountOf(m1)) // 모델마다 한 번
        h.close() // 해제
        h.close() // 두 번째 close 는 무시
        assertReleased() // 정확히 0
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /** 목적: 취소 때도 모델 사용 표시 해제(I-4). 기능: 전사 중 취소한 뒤 선택 모델 사용 횟수가 0. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun modelHoldReleasedOnCancel() = runBlocking {
        val store = ModelStore(dirs) // 실제 저장소
        holdOf = { cfg -> store.acquireAll(cfg.models) } // 선택 모델 사용 표시
        var during = -1 // 취소 직전 사용 횟수
        cancelDuringTranscribe { during = store.useCountOf(spec("m1")) } // 전사 중 취소
        assertEquals(1, during) // 실행 중에는 사용 중
        assertEquals(0, store.useCountOf(spec("m1"))) // 취소 후 해제
    }
}
