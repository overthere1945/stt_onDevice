// 파일명: SampleRunExportTest.kt
// 목적: 사용자 PC 평가기(run_eval.py)가 앱 출력 트리를 그대로 소비할 수 있는지 확인하기 위한 "샘플 실행 결과" 생성 (Task 26)
// 기능: 실제 BatchRunner 를 가짜 엔진·가짜 VAD 로 돌려 app/build/sample_run/<runId>/{kr,en}/fake_<모델>_<프리셋>/... 트리를 만들고,
//       그 runId 를 app/build/sample_run/LATEST 에 적는다. 가짜 엔진은 정답지 텍스트(화자 라벨 제거본)를 그대로 돌려주므로
//       tools/check_pairing.sh 가 평가기를 돌리면 모든 WER/CER 이 0.0 이어야 한다.
//       정답지 파일(기본 ~/workspace/stt/answer_sheet, 환경변수 STT_ANSWER_SHEET_DIR 로 변경)이 없으면 Assume 으로 건너뛴다(CI 등).
// change(add)-hyungchul-2026-10-11-02:26
package com.cowork.stt_ondevice.runner // 배치 실행 테스트 패키지

import com.cowork.stt_ondevice.core.audio.WavTestBytes // 테스트 WAV 바이트 조립
import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.core.device.ResourceSampler // 자원 샘플러
import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더 구조
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // chunk 전사 결과
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 판정
import com.cowork.stt_ondevice.model.ModelKind // 모델 종류
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.Runtime // 런타임
import com.cowork.stt_ondevice.model.StreamingKind // 입력 방식
import com.cowork.stt_ondevice.model.VoskConfig // 엔진 설정(가짜용)
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadEngine // VAD 계약
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadOutput // VAD 출력
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 목록
import kotlinx.coroutines.flow.toList // 이벤트 수집
import kotlinx.coroutines.runBlocking // 테스트용 블로킹 실행
import org.junit.Assert.assertEquals // 같음 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 조건 불충족 시 테스트 건너뜀
import org.junit.Rule // 규칙
import org.junit.Test // 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.text.SimpleDateFormat // runId 서식
import java.util.Date // 현재 시각
import java.util.Locale // 서식 로케일
import kotlin.math.PI // 원주율
import kotlin.math.roundToInt // 반올림
import kotlin.math.roundToLong // 반올림(Long)
import kotlin.math.sin // 사인파

/**
 * 목적: 정답지 텍스트의 화자 라벨 제거 + 한 줄화
 * 기능: PC 평가기 normalize.py 의 _SPK_PREFIX_RE 와 같은 규칙(각 줄 맨 앞의 한 단어짜리 "이름: " 제거)을 적용한 뒤 줄바꿈을 공백 하나로 합친다.
 *       이유: 평가기는 정답(reference)에서만 화자 라벨을 지우고(2026-10-09 수정) 엔진 출력(hypothesis)에서는 지우지 않는다.
 *       그래서 영어 정답("Customer: Hello ...")을 그대로 돌려주는 가짜 엔진은 WER 이 0 보다 커진다. 실제 엔진은 라벨을 말하지 않으므로
 *       가짜도 라벨 없는 텍스트를 내야 "경로·이름·형식 페어링 확인"이라는 이 테스트의 목적(점수 0.0)이 성립한다.
 *       한 줄화 이유: 결과 txt 의 [구간별] 표(start, end, text)와 CSV 가 줄바꿈으로 깨지지 않게 하기 위함(평가기는 공백 기준 토큰화).
 * 입력: raw(정답지 원문)
 * 출력: 없음
 * 리턴: 라벨 없는 한 줄 텍스트
 */
internal fun answerAsEngineText(raw: String): String {
    val speakerPrefix = Regex("^[ \\t]*[A-Za-z가-힣][A-Za-z가-힣0-9_\\-]{0,19}(?:[ \\t]+\\d{1,2})?[ \\t]*[:：][ \\t]+", RegexOption.MULTILINE) // normalize.py 와 같은 정규식
    val noLabel = speakerPrefix.replace(raw.replace("\r\n", "\n"), "") // 줄 앞 화자 이름 제거
    return noLabel.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") // 공백 하나로 합침
}

/**
 * 목적: 정답을 그대로 돌려주는 가짜 STT 엔진
 * 기능: chunk 의 원본 끝 시각(ms)으로 어느 입력 파일인지 찾아 그 파일의 정답 텍스트를 돌려준다.
 *       엔진 계약(transcribe(chunk, lang))에는 InputItem 이 없으므로, 이 테스트는 파일마다 길이가 다른 WAV(2.0/2.5/3.0 초)를 만들고
 *       가짜 VAD 가 [0.1, 길이-0.1] 한 구간을 내므로 chunk.endSec(1.9/2.4/2.9) 가 파일 식별자가 된다(길이가 같으면 테스트가 실패하도록 중복을 검사한다).
 * 입력: spec(모델), answerByEndMs(chunk 끝 ms → 정답 텍스트)
 */
private class AnswerEngine(override val spec: ModelSpec, private val answerByEndMs: Map<Long, String>) : AsrEngine {
    /** 목적: 가짜 준비. 기능: 고정 PrepareInfo. 입력: threads. 출력: 없음. 리턴: PrepareInfo */
    override suspend fun prepare(threads: Int): PrepareInfo = PrepareInfo(0.1, "fake-1.0", "CPU", "int8", threads) // 고정 준비 결과

    /** 목적: 정답 전사. 기능: chunk 끝 시각으로 정답을 찾아 반환(없으면 테스트 설정 오류로 예외). 입력: chunk, lang. 출력: IllegalStateException. 리턴: ChunkText */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        val key = (chunk.endSec * 1000).roundToLong() // 파일 식별 키(ms)
        val text = answerByEndMs[key] ?: throw IllegalStateException("알 수 없는 chunk 끝 시각 ${key}ms (테스트 설정 오류)") // 정답 조회
        return ChunkText(text, text, null, 0.05) // 정답 그대로
    }

    /** 목적: 가짜 중단. 기능: 없음. 입력: 없음. 출력: 없음. 리턴: 없음 */
    override fun cancel() {} // 즉시 끝나는 가짜라 할 일 없음

    /** 목적: 가짜 해제. 기능: 없음. 입력: 없음. 출력: 없음. 리턴: 없음 */
    override fun close() {} // 자원 없음
}

/**
 * 목적: 항상 한 구간을 내는 가짜 VAD
 * 기능: [0.1, 길이-0.1] 한 구간(점수 없음). NONE·SILERO 프리셋 모두 이 같은 가짜 VAD 를 쓰므로 실제 NONE(고정 길이 분할)·Silero 경로는
 *       검증 대상이 아니다. 이 테스트는 폴더·모델 이름·txt 형식이 PC 평가기와 페어링되는지만 확인한다. 엔진은 파일당 1번만 호출된다.
 * 입력: family(계열)
 */
private class OneSegmentVad(override val family: VadFamily) : VadEngine {
    /** 목적: 가짜 검출. 기능: 한 구간 반환. 입력: audio, preset. 출력: 없음. 리턴: VadOutput */
    override fun detect(audio: FloatArray, preset: VadPreset): VadOutput =
        VadOutput(listOf(SpeechSegment(0.1, audio.size / 16000.0 - 0.1)), null, null) // 한 구간

    /** 목적: 가짜 해제. 기능: 없음. 입력: 없음. 출력: 없음. 리턴: 없음 */
    override fun close() {} // 자원 없음
}

/**
 * 목적: 샘플 입력 한 건의 정의
 * 기능: 입력 폴더(lang/rel)·WAV 길이·대응하는 정답지 파일을 묶는다.
 * 입력: lang(kr|en), rel(상대 폴더), sec(WAV 길이, 파일 식별자 겸용), ref(정답지 파일)
 */
private data class SampleCase(val lang: String, val rel: String, val sec: Double, val ref: File)

// change(add)-hyungchul-2026-10-11-02:26
/**
 * 목적: PC 평가기 페어링용 샘플 실행 결과 생성
 * 기능: kr/customer/01(2.0초), kr/friend/01(2.5초), en/Customer_Service/01(3.0초) × 가짜 모델 m1·m2 × 프리셋 NONE·SILERO 를 BatchRunner 로 실행하고
 *       결과 트리를 app/build/sample_run/<runId>/ 로 복사한 뒤 LATEST 에 runId 를 쓴다.
 */
class SampleRunExportTest {

    @get:Rule // 입력 트리·앱 폴더용 임시 폴더
    val tmp = TemporaryFolder()

    /** 목적: 정답지 폴더. 기능: 환경변수 STT_ANSWER_SHEET_DIR 또는 ~/workspace/stt/answer_sheet. 입력: 없음. 출력: 없음. 리턴: File */
    private fun answerSheetDir(): File = File(System.getenv("STT_ANSWER_SHEET_DIR") ?: "${System.getProperty("user.home")}/workspace/stt/answer_sheet") // 기본 위치

    /**
     * 목적: 앱 모듈 폴더 찾기
     * 기능: Gradle 은 모듈 폴더(app)에서 테스트를 실행하므로 user.dir 를 쓰되, 프로젝트 루트에서 실행된 경우 app 하위를 쓰고, 둘 다 아니면 실패시킨다.
     * 입력: 없음
     * 출력: 모듈 폴더를 못 찾으면 AssertionError
     * 리턴: 모듈 폴더
     */
    // change(add)-hyungchul-2026-10-11-03:05
    private fun moduleDir(): File {
        val cwd = File(System.getProperty("user.dir")!!).absoluteFile // 현재 작업 폴더
        val dir = if (File(cwd, "src/test").isDirectory) cwd else File(cwd, "app") // 모듈 또는 루트 기준
        assertTrue("앱 모듈 폴더를 찾지 못함: $cwd", File(dir, "src/test").isDirectory) // 확인
        return dir
    }

    /** 목적: 가짜 모델. 기능: algo "fake", model=id 인 ModelSpec(ko·en 지원). 입력: id. 출력: 없음. 리턴: ModelSpec */
    private fun spec(id: String): ModelSpec = ModelSpec(
        id = id, kind = ModelKind.ASR, algo = "fake", model = id, displayName = id, languages = setOf("ko", "en"), // 식별·언어
        runtime = Runtime.VOSK, runtimeVersion = "0.3", precision = "fp32", maxChunkSec = 30.0, // 실행 정보
        streaming = StreamingKind.FILE_ONLY, downloads = emptyList(), files = emptyList(), originalRepo = "", // 설치 정보
        license = "test", licenseUrl = "", commercial = CommercialStatus.ALLOWED, compatAlias = null, engineConfig = VoskConfig, // 라이선스·설정
    )

    /** 목적: 입력 WAV 생성. 기능: 440 Hz 사인파 16k mono PCM16 을 <inputRoot>/<lang>/<rel>/<stem>.wav 로 쓴다. 입력: inputRoot, lang, rel, stem, sec. 출력: 파일. 리턴: InputItem */
    private fun toneItem(inputRoot: File, lang: String, rel: String, stem: String, sec: Double): InputItem {
        val n = (sec * 16000).roundToInt() // 샘플 수
        val pcm = ShortArray(n) { (8000 * sin(2 * PI * 440.0 * it / 16000.0)).toInt().toShort() } // 사인파(무음 아님)
        val f = File(File(File(inputRoot, lang), rel), "$stem.wav") // 경로
        f.parentFile!!.mkdirs() // 폴더 생성
        f.writeBytes(WavTestBytes.wavBytes(16000, 1, 16, WavTestBytes.pcm16(pcm))) // WAV 저장
        return InputItem(lang, rel, stem, f) // 항목
    }

    /** 목적: 샘플 실행 생성. 기능: 위 클래스 설명대로 실행하고 결과를 build/sample_run 에 내보낸다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun exportSampleRun() = runBlocking {
        val sheet = answerSheetDir() // 정답지 폴더
        val cases = listOf( // 샘플 입력과 정답지 대응
            SampleCase("kr", "customer", 2.0, File(sheet, "kr/customer.txt")), // 한국어 고객센터
            SampleCase("kr", "friend", 2.5, File(sheet, "kr/friend.txt")), // 한국어 친구 대화
            SampleCase("en", "Customer_Service", 3.0, File(sheet, "en/Customer_Service.txt")), // 영어 고객센터
        )
        assumeTrue("정답지가 없어 건너뜀: $sheet", cases.all { it.ref.isFile }) // 데이터셋 없는 환경은 건너뜀

        val appDirs = AppDirs(tmp.newFolder("app")) // 임시 앱 폴더(입력·출력)
        val items = cases.map { toneItem(appDirs.inputSound, it.lang, it.rel, "01", it.sec) } // 입력 항목
        val answerByEndMs = cases.associate { Math.round((it.sec - 0.1) * 1000) to answerAsEngineText(it.ref.readText(Charsets.UTF_8)) } // 끝 ms → 정답
        assertEquals("파일 식별을 위해 길이가 모두 달라야 함", cases.size, answerByEndMs.size) // 키 중복 검사
        assertTrue("정답 텍스트가 비어 있음", answerByEndMs.values.all { it.isNotBlank() }) // 빈 정답 방지

        val runId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) // 실행 ID
        val runner = BatchRunner(
            dirs = appDirs, // 임시 앱 폴더
            engineFactory = { s -> AnswerEngine(s, answerByEndMs) }, // 정답 엔진
            vadFactory = { family -> OneSegmentVad(family) }, // 한 구간 VAD
            samplerFactory = { ResourceSampler(readPssKb = { 204_800L }, readCpuMs = { 0L }) }, // 고정 PSS
            env = { EnvSnapshot(0, 0.5f, 80, true) }, // 고정 환경
            device = DeviceInfo("samsung", "SM-S938N", "SM8750", "16", 36, "arm64-v8a", 11800L, "1.0 (1)"), // 고정 기기
        )
        val config = RunConfig(runId, items, listOf(spec("m1"), spec("m2")), listOf("NONE", "SILERO").map { VadPresets.byId(it) }, ChunkMode.MERGE, emptyMap(), 4, false) // 실행 설정
        val events = runner.run(config).toList() // 실행
        assertEquals(RunEvent.Finished(12, 0, 0, 0), events.last()) // 2 모델 × 2 프리셋 × 3 파일 모두 OK

        val outRoot = File(moduleDir(), "build/sample_run") // app/build/sample_run
        outRoot.listFiles()?.filter { it.name != "LATEST" }?.forEach { it.deleteRecursively() } // 오래된 샘플 실행 정리(LATEST 만 유지) // change(add)-hyungchul-2026-10-11-03:05
        val dest = File(outRoot, runId) // 내보낼 폴더
        dest.deleteRecursively() // 같은 초에 다시 실행된 경우 대비
        appDirs.runDir(runId).copyRecursively(dest, overwrite = true) // 결과 트리 복사
        File(outRoot, "LATEST").writeText(runId + "\n", Charsets.UTF_8) // 최신 runId 기록
        println("SAMPLE_RUN_PATH=${dest.path}") // 경로 출력
    }
}
