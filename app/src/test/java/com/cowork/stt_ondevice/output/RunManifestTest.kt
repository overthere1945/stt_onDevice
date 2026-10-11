// 파일명: RunManifestTest.kt
// 목적: RunConfig·RunManifest·DetailJson 스키마(Task 24) 검증
// 기능: manifest 왕복 직렬화, null 명시 출력, maxChunk 덮어쓰기, 프리셋 params 문자열 안정성, 원자적 쓰기, 손상 파일 읽기, RunConfig 입력 검증을 확인한다.
package com.cowork.stt_ondevice.output // 출력 테스트 패키지

import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.device.EnvSnapshot // 환경 스냅샷
import com.cowork.stt_ondevice.core.json.AppJson // 공용 Json
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.model.ModelCatalog // 모델 카탈로그
import com.cowork.stt_ondevice.runner.RunConfig // 실행 설정
import com.cowork.stt_ondevice.segment.AudioChunk // 오디오 chunk
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import com.cowork.stt_ondevice.vad.VadPresets // VAD 프리셋 목록
import org.junit.Assert.assertEquals // 같음 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertThrows // 예외 검증 // change(add)-hyungchul-2026-10-11-12:51
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Rule // 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 실행 설정/manifest/detail 스키마 회귀 방지
 * 기능: 실제 카탈로그·프리셋으로 만든 값이 JSON 으로 손실 없이 오가는지 확인한다.
 */
class RunManifestTest {

    @get:Rule // 테스트마다 새 임시 폴더
    val tmp = TemporaryFolder()

    /** 목적: 테스트용 기기 정보. 기능: 고정 값 DeviceInfo. 입력: 없음. 출력: 없음. 리턴: DeviceInfo */
    private fun device() = DeviceInfo("samsung", "SM-S938N", "SM8750", "16", 36, "arm64-v8a", 11800L, "1.0 (1)") // 고정 기기

    /** 목적: 테스트용 manifest. 기능: 카탈로그 첫 ASR 모델과 두 프리셋으로 채운다. 입력: inFlight(진행 중 키). 출력: 없음. 리턴: RunManifest */
    private fun manifest(inFlight: String? = "a_b_SILERO|kr|s1|01"): RunManifest {
        val spec = ModelCatalog.asr.first() // 첫 ASR 모델
        return RunManifest(
            runId = "20261010_220500", // 실행 ID
            appVersion = "1.0 (1)", // 앱 버전
            startedAt = "2026-10-10T22:05:00+09:00", // 시작 시각
            device = DeviceInfoJson.from(device()), // 기기
            models = listOf(ModelEntry.from(spec, spec.maxChunkSec)), // 모델
            presets = listOf("SILERO_SHERPA", "WEBRTC_ANDROIDVAD").map { PresetEntry.from(VadPresets.byId(it)) }, // 프리셋
            chunkMode = ChunkMode.MERGE.name, // 묶기 방식
            maxChunkOverride = mapOf(spec.id to 12.5), // 덮어쓰기
            threads = 4, // 스레드
            resume = false, // 이어하기 아님
            inputRoot = "/sdcard/stt/input_sound", // 입력 루트
            unsupported = listOf("combo|en"), // 미지원 조합
            inFlight = inFlight, // 진행 중
            finishedAt = null, // 아직 끝나지 않음
        )
    }

    /** 목적: manifest 왕복 확인. 기능: encode→decode 결과가 원본과 같은지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun manifestRoundTrip() {
        val m = manifest() // 원본
        val text = AppJson.encodeToString(RunManifest.serializer(), m) // 직렬화
        assertEquals(m, AppJson.decodeFromString(RunManifest.serializer(), text)) // 역직렬화 후 동일
    }

    /** 목적: 측정 안 한 값의 null 명시 확인. 기능: detail 의 null 필드가 "key": null 로 존재하는지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun nullsAreExplicit() {
        val d = DetailJson(
            combo = "c", lang = "kr", relDir = "", stem = "01", file = "01.wav", status = "OK", error = null, // 식별
            vadPresetId = "NONE", vadReused = null, vadFrameScoresAvailable = false, startedAt = null, // VAD
            audioSec = 70.0, preprocessSec = null, vadSec = null, loadSec = null, transcribeSec = null, // 시간
            elapsedSec = null, rtf = null, cpuTimeSec = null, pssPeakMb = null, envStart = null, envEnd = null, chunks = emptyList(), // 나머지
        )
        val s = AppJson.encodeToString(DetailJson.serializer(), d) // 직렬화
        for (k in listOf("error", "vadReused", "startedAt", "preprocessSec", "vadSec", "loadSec", "transcribeSec", "elapsedSec", "rtf", "cpuTimeSec", "pssPeakMb", "envStart", "envEnd")) {
            assertTrue("$k 가 null 로 명시돼야 함:\n$s", s.contains("\"$k\": null")) // 키 존재 + null
        }
        assertEquals(d, AppJson.decodeFromString(DetailJson.serializer(), s)) // 왕복
        val m = AppJson.encodeToString(RunManifest.serializer(), manifest(inFlight = null)) // manifest
        assertTrue(m.contains("\"inFlight\": null")) // inFlight null 명시
        assertTrue(m.contains("\"finishedAt\": null")) // finishedAt null 명시
    }

    /** 목적: 변환기와 chunk/env 미러 확인. 기능: 도메인 값 → JSON 미러 필드 대응을 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun mirrorsConvertDomainValues() {
        val chunk = AudioChunk(2, FloatArray(0), listOf(SourceSpan(1.0, 3.5, 0.0), SourceSpan(5.0, 6.0, 2.6)), SplitMethod.MIN_SCORE) // 도메인 chunk
        val j = ChunkJson.from(chunk, "텍스트", "raw", null) // 변환(inferSec 미측정)
        assertEquals(2, j.index) // 순번
        assertEquals("MIN_SCORE", j.split) // enum 이름
        assertEquals(listOf(SpanJson(1.0, 3.5, 0.0), SpanJson(5.0, 6.0, 2.6)), j.spans) // span
        assertNull(j.inferSec) // 미측정은 null
        val env = EnvSnapshotJson.from(EnvSnapshot(1, null, 80, true)) // 환경
        assertEquals(EnvSnapshotJson(1, null, 80, true), env) // 필드 대응
        assertEquals("SM8750", DeviceInfoJson.from(device()).socModel) // SoC 대응
    }

    /** 목적: maxChunk 덮어쓰기 확인. 기능: override 가 있으면 그 값, 없으면 모델 기본값. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun maxChunkOverrideApplied() {
        val a = ModelCatalog.asr[0] // 덮어쓸 모델
        val b = ModelCatalog.asr.first { it.id != a.id && it.maxChunkSec > 0 } // 덮어쓰지 않을 모델
        val cfg = RunConfig(
            runId = "20261010_220500", items = emptyList(), models = listOf(a, b), presets = listOf(VadPresets.byId("SILERO")),
            chunkMode = ChunkMode.MERGE, maxChunkOverride = mapOf(a.id to 7.0),
        )
        assertEquals(7.0, cfg.maxChunkFor(a), 0.0) // 덮어쓴 값
        assertEquals(b.maxChunkSec, cfg.maxChunkFor(b), 0.0) // 기본값
        assertEquals(7.0, ModelEntry.from(a, cfg.maxChunkFor(a)).maxChunkSec, 0.0) // manifest 에는 실효값
        assertEquals(4, cfg.threads) // 기본 스레드
        assertFalse(cfg.resume) // 기본 이어하기 아님
    }

    /** 목적: RunConfig 입력 검증 확인. 기능: threads<1, runId 형식 오류가 예외인지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun runConfigValidatesInput() {
        fun make(runId: String, threads: Int) = RunConfig(runId, emptyList(), emptyList(), emptyList(), ChunkMode.MERGE, emptyMap(), threads) // 생성 도우미
        make("20261010_220500", 1) // 정상
        for ((id, t) in listOf("20261010_220500" to 0, "2026-10-10" to 4, "20261010220500" to 4, "20261010_22050" to 4, "x20261010_220500" to 4)) {
            try {
                make(id, t) // 잘못된 입력
                fail("예외가 나야 함: $id/$t")
            } catch (e: IllegalArgumentException) {
                assertNotNull(e.message) // 사유 메시지 존재
            }
        }
    }

    // change(add)-hyungchul-2026-10-11-01:47
    /** 목적: maxChunkOverride 검증 확인. 기능: NaN·무한대·음수 값과 선택하지 않은 모델 키가 예외인지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun runConfigValidatesMaxChunkOverride() {
        val a = ModelCatalog.asr[0] // 선택 모델
        fun make(o: Map<String, Double>) = RunConfig("20261010_220500", emptyList(), listOf(a), emptyList(), ChunkMode.MERGE, o) // 생성 도우미
        make(mapOf(a.id to 0.0)) // 0 은 허용(제한 없음)
        make(mapOf(a.id to 12.5)) // 양수 허용
        for (o in listOf(mapOf(a.id to Double.NaN), mapOf(a.id to Double.POSITIVE_INFINITY), mapOf(a.id to -1.0), mapOf("not-selected" to 5.0))) { // 잘못된 값들
            try {
                make(o) // 잘못된 입력
                fail("예외가 나야 함: $o") // 예외가 없으면 실패
            } catch (e: IllegalArgumentException) {
                assertNotNull(e.message) // 사유 메시지 존재
            }
        }
    }

    /** 목적: 프리셋 params 문자열 안정성. 기능: 정확한 문자열과 메타 필드를 비교한다(무한대는 inf). 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun presetParamsStringStable() {
        val sherpa = PresetEntry.from(VadPresets.byId("SILERO_SHERPA")) // 대상 1
        assertEquals("threshold=0.5,minSpeechMs=250,minSilenceMs=500,speechPadMs=0,maxSpeechSec=20.0", sherpa.params) // 정확한 문자열
        assertEquals("SILERO", sherpa.family) // 계열
        assertEquals("ALLOWED", sherpa.commercial) // 상업 판정
        assertEquals("sherpa-onnx C++ SileroVadModelConfig", sherpa.source) // 출처
        assertTrue(sherpa.note.isNotEmpty()) // 주의 문구
        val rtc = PresetEntry.from(VadPresets.byId("WEBRTC_ANDROIDVAD")) // 대상 2
        assertEquals("mode=3,frameMs=20,style=HANGOVER,paddingMs=300,ratio=0.9,speechMs=50,silenceMs=300", rtc.params) // 정확한 문자열
        assertEquals("inf", PresetEntry.from(VadPresets.byId("SILERO")).params.substringAfter("maxSpeechSec=")) // 무한대 표기
        assertEquals("", PresetEntry.from(VadPresets.byId("NONE")).params) // 파라미터 없음
        for (p in VadPresets.all) assertTrue(p.id, PresetEntry.from(p).let { p.params is com.cowork.stt_ondevice.vad.NoneParams || it.params.isNotEmpty() }) // 전 프리셋 변환 가능
    }

    /** 목적: 원자적 쓰기 확인. 기능: 쓴 뒤 .tmp 가 남지 않고, 덮어쓰기가 되며, 상위 폴더가 없어도 만들어지는지 본다. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun atomicWriteLeavesNoTmp() {
        val f = File(tmp.root, "run/run_manifest.json") // 상위 폴더 없는 대상
        manifest(inFlight = "k1").write(f) // 첫 쓰기
        manifest(inFlight = "k2").write(f) // 덮어쓰기
        assertEquals("k2", RunManifest.read(f)?.inFlight) // 최신 내용
        assertEquals(listOf("run_manifest.json"), f.parentFile!!.list()!!.toList()) // 임시 파일 없음
    }

    /** 목적: 손상/부재 파일 읽기 확인. 기능: 없는 파일·깨진 JSON·빈 파일은 null. 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun corruptManifestReadsNull() {
        assertNull(RunManifest.read(File(tmp.root, "none.json"))) // 부재
        val bad = tmp.newFile("bad.json") // 깨진 파일
        bad.writeText("{\"schemaVersion\": 1, \"runId\": ") // 잘린 JSON
        assertNull(RunManifest.read(bad)) // 손상 → null
        bad.writeText("") // 빈 파일
        assertNull(RunManifest.read(bad)) // 빈 파일 → null
        bad.writeText("{\"hello\": 1}") // 스키마 불일치
        assertNull(RunManifest.read(bad)) // 필수 필드 누락 → null
    }

    /** 목적: 진행 중 항목 키 형식 확인. 기능: "combo|lang|relDir|stem". 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit */
    @Test
    fun inFlightKeyFormat() {
        val item = InputItem("kr", "s1/a", "01", File("x.wav")) // 입력
        assertEquals("c|kr|s1/a|01", RunManifest.inFlightKey("c", item)) // 형식
        assertEquals("c|en||02", RunManifest.inFlightKey("c", InputItem("en", "", "02", File("y.wav")))) // relDir 빈 값
    }

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 중단 횟수 필드 호환·왕복과 fsync 저장 경로 확인(I-1·I-2)
     * 기능: inFlightAttempts 가 없는 이전 버전 JSON 은 빈 맵으로 읽히고, 값이 있으면 저장 후 그대로 읽힌다.
     *       저장(AtomicFile.write)은 이전처럼 임시 파일을 남기지 않고 원래 파일을 교체한다(manifest 경로가 비어 있지 않은 폴더면 예외 — 기존 파일 손상 없음).
     * 입력: 없음. 출력: 실패 시 AssertionError. 리턴: Unit
     */
    @Test
    fun inFlightAttemptsCompatAndAtomicWrite() {
        val f = File(tmp.root, "run2/run_manifest.json") // 대상
        val old = AppJson.encodeToString(RunManifest.serializer(), manifest(inFlight = "k1")) // 현재 형식 JSON
        f.parentFile!!.mkdirs() // 폴더
        f.writeText(old.replace(Regex(",?\\s*\"inFlightAttempts\"\\s*:\\s*\\{\\s*}"), "")) // 이전 버전(필드 없음) 흉내
        assertTrue(!f.readText().contains("inFlightAttempts")) // 필드 제거 확인
        assertEquals(emptyMap<String, Int>(), RunManifest.read(f)!!.inFlightAttempts) // 빈 맵으로 읽힘
        manifest(inFlight = "k1").copy(inFlightAttempts = mapOf("k1" to 1)).write(f) // 값 저장
        assertEquals(mapOf("k1" to 1), RunManifest.read(f)!!.inFlightAttempts) // 왕복
        assertEquals(listOf("run_manifest.json"), f.parentFile!!.list()!!.toList()) // 임시 파일 없음
        val blocked = File(tmp.root, "run3/run_manifest.json") // 폴더로 막힌 경로
        File(blocked, "x").apply { parentFile!!.mkdirs(); writeText("x") } // 비어 있지 않은 폴더
        assertThrows(java.io.IOException::class.java) { manifest().write(blocked) } // 교체 실패는 예외
        assertEquals(listOf("run_manifest.json"), blocked.parentFile!!.list()!!.toList()) // 임시 파일 정리
    }
}
