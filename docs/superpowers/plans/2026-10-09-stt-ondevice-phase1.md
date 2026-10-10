# STT_OnDevice Phase 1 구현 계획서

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** Galaxy S25 Ultra에서 무료·상업 이용 가능 STT 엔진 × VAD 프리셋 조합으로 한국어/영어 WAV를 배치 전사하고, 사용자의 PC 평가기(`run_eval.py`)가 그대로 읽는 결과 폴더를 만드는 Android 앱.

**구조:** 단일 `:app` 모듈 + 기능별 패키지. 순수 Kotlin 로직(WAV, VAD 후처리, ChunkPlanner, 출력 형식, 배치 실행)은 JVM 단위 테스트로 Python 원본과 골든 비교하고, 네이티브(whisper.cpp, libfvad, TEN, sherpa-onnx, Vosk, ORT, LiteRT)는 빌드·16KB·실기기 smoke 로 검증한다.

**기술 스택:** Kotlin 2.4.21, AGP 9.4.1(built-in Kotlin), Jetpack Compose BOM 2026.09.00, Coroutines 1.11.0, kotlinx-serialization 1.11.0, NDK 30.0.16248370, CMake 4.1.2, whisper.cpp v1.9.5, sherpa-onnx v1.13.8(static ORT AAR), onnxruntime-android 1.30.0, Vosk 0.3.75, libfvad, TEN VAD, LiteRT, commons-compress. PC 도구: Python 3.12 venv.

**Spec:** `docs/superpowers/specs/2026-10-09-stt-ondevice-phase1-design.md` (실행자는 계획서와 spec을 함께 읽는다. 프리셋 값·출력 경로·CSV 매핑은 spec 표가 원본이다.)

## Global Constraints

- 모든 설명·문서·커밋 메시지는 **한국어**(사용자는 영어를 읽지 못함). 코드 식별자·경로는 영어 그대로.
- 모든 코드 파일 상단 한국어 주석(파일명·목적·기능), 함수마다 한국어 주석(목적/기능/입력/출력/리턴), 라인별 한국어 설명 주석.
- 추가·변경 코드에 `change(add)-hyungchul-YYYY-MM-DD-HH:mm` 주석 — 시각은 `TZ=Asia/Seoul date '+%Y-%m-%d-%H:%M'` 실제 값.
- 외부에서 그대로 복사한 서드파티 소스(sherpa-onnx Kotlin API)는 원문 유지, 같은 폴더 `README_한국어.md`에 출처·버전·라이선스 기록.
- JSON 등 주석 불가 파일은 같은 폴더의 한국어 설명 문서로 설명.
- minSdk 31, compileSdk/targetSdk 37, Java 17, ABI `arm64-v8a` 전용, NDK 30.0.16248370, CMake 4.1.2.
- `packaging { jniLibs { useLegacyPackaging = false } }`, **`pickFirst` 사용 금지**. 모든 `.so` LOAD 정렬 0x4000(16 KB).
- 측정 불가 값은 빈 값/`null` — 0이나 추정치로 채우지 않는다.
- 조용한 fallback 금지(다른 모델/VAD로 자동 대체 금지).
- 모델·음원 파일은 git에 넣지 않는다(원격 저장소가 PUBLIC). 골든 테스트 음원 클립도 gitignore, 파생 JSON(프레임 점수·구간)만 커밋.
- 커밋은 로컬만. **push는 사용자 요청 시에만.** 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- 결과 경로: `output_stt/<run_id>/<lang>/<algo>_<model>_<VAD>/<relDir>/<stem>.txt` (`lang`은 입력 폴더명 `kr`/`en` 그대로), run_id = `yyyyMMdd_HHmmss`.
- 패키지 루트 `com.cowork.stt_ondevice`. JVM 테스트 명령 `./gradlew :app:testDebugUnitTest --tests "<FQCN>"`.

## Review Focus

1. **수 시간~수일짜리 배치가 중간에 죽음**(앱 종료, 발열, 사용자가 취소) → 같은 run_id로 재개하면 이미 쓴 txt/ERROR.txt는 건너뛰고 나머지만 실행해야 한다. (Task 25 `resumeSkipsCompletedItems`)
2. **대형 모델(medium f16, Omnilingual 1B)의 네이티브 크래시/OOM** 은 catch 불가 → 재개 시 "시작했지만 끝나지 않은 항목"을 `ERROR(stage=TRANSCRIBE, code=CRASH_OR_KILLED)`로 기록하고 다음으로 넘어가야 한다. (Task 25 `resumeMarksInFlightItemAsCrashed`)
3. **무음/발화 0개 파일** → 빈 전체 텍스트의 txt를 쓰고 CSV `status=NO_MATCH`, 엔진은 호출하지 않는다. (Task 5 `noSegmentsGivesNoChunks`, Task 25 `silentFileWritesNoMatch`)
4. **언어 미지원 조합**(zipformer-ko × `en` 폴더, vosk small-ko × `en`) → 실행·폴더 생성 없이 manifest `unsupported` 목록에만 기록. (Task 25 `unsupportedComboCreatesNoFolder`)
5. **16 kHz mono가 아닌 WAV**(44.1 kHz stereo, 8-bit, WAVE_FORMAT_EXTENSIBLE, LIST 청크 포함) → 16 kHz mono float로 변환하고 원본 rate/channels와 `preprocess_sec`를 기록, 지원 불가 형식은 `WavFormatException`. (Task 2 `extensibleHeaderParsed`, `stereo44kResampledTo16kMono`)

---

## 파일 구조 (생성/수정 대상 전체)

```
app/build.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, settings.gradle.kts   (수정)
app/src/main/AndroidManifest.xml                                                          (수정)
app/src/main/res/layout/activity_main.xml, values*/themes.xml                             (삭제/수정)
app/src/main/cpp/{CMakeLists.txt, whisper_jni.cpp, fvad_jni.cpp, ten_vad_jni.cpp}
app/src/main/cpp/third_party/{whisper.cpp(submodule v1.9.5), libfvad(submodule), ten-vad(다운로드)}
app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar                                    (다운로드, gitignore)
app/src/main/java/com/k2fsa/sherpa/onnx/*.kt + README_한국어.md                            (복사)
app/src/main/java/com/cowork/stt_ondevice/
  App.kt, MainActivity.kt
  core/audio/{WavData.kt, WavReader.kt, WavWriter.kt, PcmConverter.kt, AudioClip.kt, MicRecorder.kt}
  core/storage/{AppDirs.kt, InputItem.kt, InputScanner.kt, SafBridge.kt}
  core/device/{DeviceInfo.kt, ResourceSampler.kt}
  core/json/Json.kt
  model/{CommercialStatus.kt, ModelSpec.kt, ModelCatalog.kt, ModelCatalogData.kt(생성), ModelStore.kt,
         Downloader.kt, ArchiveExtractor.kt, Sha256.kt, ModelInstaller.kt}
  vad/{VadFamily.kt, VadParams.kt, VadPreset.kt, VadPresets.kt, SpeechSegment.kt, VadEngine.kt, VadEngineFactory.kt, VadCache.kt}
  vad/post/{SileroTimestamps.kt, PyannoteBinarize.kt, FsmnStateMachine.kt, WebRtcPost.kt, SimpleHangover.kt,
            NemoPostprocess.kt, FireRedPostprocess.kt}
  vad/feature/{KaldiFbank.kt, Lfr.kt, Cmvn.kt, NemoMelSpectrogram.kt}
  vad/impl/{NoneVad.kt, SileroVad.kt, PyannoteVad.kt, FsmnVad.kt, FireRedVad.kt, MarbleNetVad.kt,
            WebRtcVad.kt, FvadNative.kt, TenVad.kt, TenVadNative.kt, YamnetVad.kt}
  segment/{AudioChunk.kt, ChunkPlanner.kt}
  engine/{AsrEngine.kt, EngineFactory.kt}
  engine/whisper/{WhisperNative.kt, WhisperCppEngine.kt}
  engine/sherpa/{SherpaOfflineEngine.kt, SherpaStreamingEngine.kt, SherpaConfigs.kt, SenseVoiceText.kt}
  engine/vosk/VoskEngine.kt
  output/{OutputLayout.kt, TranscriptWriter.kt, SttCsvHeader.kt, ResultCsvWriter.kt, RunManifest.kt, DetailJson.kt}
  runner/{RunConfig.kt, RunEvent.kt, RunState.kt, BatchRunner.kt, RunService.kt}
  ui/{AppNav.kt, theme/Theme.kt, models/*, run/*, results/*, mic/*}
app/src/test/java/com/cowork/stt_ondevice/... (각 Task의 테스트) , app/src/test/resources/golden/*.json
app/src/androidTest/java/com/cowork/stt_ondevice/NativeSmokeTest.kt
tools/{fetch_native_deps.sh, check_16kb.sh, hash_models.py, check_pairing.sh}
tools/adb/{push_inputs.sh, push_models.sh, pull_results.sh}
tools/golden/{requirements.txt, make_clips.py, gen_*.py, README_한국어.md}
tools/export/{requirements.txt, requirements-nemo.txt, export_firered.py, export_marblenet.py, README_한국어.md}
docs/사용설명서.md, docs/테스트보고서_phase1.md
```

---

## A. 기반

### Task 1: Gradle·Compose 전환과 빈 Compose 화면

**Files:**
- Modify: `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/cowork/stt_ondevice/MainActivity.kt`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values-night/themes.xml`
- Delete: `app/src/main/res/layout/activity_main.xml`, `app/src/test/.../ExampleUnitTest.kt`, `app/src/androidTest/.../ExampleInstrumentedTest.kt`
- Create: `app/src/main/java/com/cowork/stt_ondevice/App.kt`, `ui/theme/Theme.kt`, `app/src/test/java/com/cowork/stt_ondevice/BuildSanityTest.kt`

**Interfaces:**
- Produces: Compose 기반 `MainActivity`(setContent { SttTheme { AppNav() } } 은 Task 27에서 연결, 지금은 제목 텍스트), `class App : Application`.

- [ ] **Step 1: 버전 카탈로그 갱신** — 제거: appcompat, material, constraintlayout. 추가(정확한 버전): `kotlin = "2.4.21"`, `composeBom = "2026.09.00"`, `activityCompose = "1.13.0"`, `lifecycle = "2.11.0"`, `navigation = "2.10.2"`, `coroutines = "1.11.0"`, `serialization = "1.11.0"`, `onnxruntime = "1.30.0"`, `vosk = "0.3.75"`, `jna = "5.18.1"`, `commonsCompress`(Maven Central 최신 안정판 확인 후 고정). 플러그인: `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization` (둘 다 `kotlin` 버전). `org.jetbrains.kotlin.android`는 **추가하지 않는다**(AGP 9 built-in).
- [ ] **Step 2: `app/build.gradle.kts`** — minSdk 31, Java 17(`compileOptions`), `ndk { abiFilters += "arm64-v8a" }`, `buildFeatures { compose = true }`, `packaging { jniLibs { useLegacyPackaging = false } }`, 의존성: compose-bom(platform), ui, material3, ui-tooling-preview, activity-compose, lifecycle-viewmodel-compose, lifecycle-runtime-compose, navigation-compose, coroutines-android, serialization-json; test: junit 4.13.2, coroutines-test, `com.microsoft.onnxruntime:onnxruntime:1.30.0`(JVM용). `onnxruntime-android`·vosk 등 네이티브 의존성은 해당 Task에서 추가.
- [ ] **Step 3: 테마를 `android:Theme.Material.Light.NoActionBar` 계열로 변경, `MainActivity : ComponentActivity` + `setContent { SttTheme { Text("STT OnDevice") } }`, Manifest에 `android:name=".App"`.**
- [ ] **Step 4: `BuildSanityTest.sanity()` — `assertEquals(4, 2 + 2)` (테스트 인프라 확인용)**
- [ ] **Step 5: 검증** — Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL`, 테스트 1개 PASS.
- [ ] **Step 6: 커밋** — `git add -A && git commit -m "Compose 전환과 빌드 설정(minSdk31, arm64, Java17)"`

### Task 2: WAV 읽기·쓰기와 16 kHz mono 변환 (`core/audio`)

**Files:**
- Create: `core/audio/WavData.kt`, `WavReader.kt`, `WavWriter.kt`, `PcmConverter.kt`, `AudioClip.kt`
- Test: `app/src/test/java/com/cowork/stt_ondevice/core/audio/WavReaderTest.kt`, `PcmConverterTest.kt`

**Interfaces:**
- Produces:
  - `data class WavData(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val pcm: ShortArray /* interleaved */)`
  - `class WavFormatException(message: String) : java.io.IOException(message)`
  - `object WavReader { fun read(file: java.io.File): WavData; fun read(bytes: ByteArray): WavData }` — RIFF/WAVE, `fmt `(PCM=1 또는 EXTENSIBLE=0xFFFE+PCM subformat), 8/16-bit, 임의 청크(LIST 등) 건너뜀, `data` 길이가 파일보다 길면 실제 길이로 자름.
  - `object WavWriter { fun write(file: java.io.File, pcm: ShortArray, sampleRate: Int) }` — 16-bit mono.
  - `data class AudioClip(val samples: FloatArray /* 16k mono, -1..1 */, val sourceSampleRate: Int, val sourceChannels: Int, val sourceBits: Int, val converted: Boolean) { val durationSec: Double; val sampleRate: Int get() = 16000 }`
  - `object PcmConverter { const val TARGET_RATE = 16000; fun toMono16k(wav: WavData): AudioClip; fun floatToPcm16(x: FloatArray): ShortArray }` — 다운믹스=채널 평균, 리샘플=선형보간(입력이 이미 16k mono면 그대로, `converted=false`). 8-bit는 unsigned → (b-128)/128.

- [ ] **Step 1: 실패 테스트 작성** — 테스트 안에서 WAV 바이트를 직접 조립하는 헬퍼 `wavBytes(rate, ch, bits, frames, extraChunks, extensible)` 사용:
  - `pcm16Mono16kRoundTrip`: `WavWriter`로 쓴 `shortArrayOf(0, 16384, -16384, 32767)` → `read` 결과 동일, rate 16000, ch 1.
  - `listChunkSkipped`: `fmt ` 앞뒤에 `LIST` 청크 → 정상 파싱.
  - `extensibleHeaderParsed`: 0xFFFE + PCM subformat GUID → 정상 파싱.
  - `truncatedDataChunkClamped`: data size 필드가 실제보다 큼 → 실제 샘플 수만큼.
  - `notRiffThrows`, `floatFormatThrows`(format 3) → `WavFormatException`.
  - `stereo44kResampledTo16kMono`: 44100 Hz 2ch 1초 → `samples.size == 16000`, `converted == true`, `sourceChannels == 2`.
  - `mono16kNotConverted`: `converted == false`, 값 = pcm/32768f.
  - `eightBitConverted`: 8-bit 128 → 0f.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :app:testDebugUnitTest --tests "com.cowork.stt_ondevice.core.audio.*"` → 컴파일 실패(미정의).
- [ ] **Step 3: 구현** — 위 시그니처대로. little-endian `ByteBuffer`.
- [ ] **Step 4: 통과 확인** — 같은 명령 → 전부 PASS. 추가로 실제 음원 1개 sanity: 테스트 `realDatasetFileIfPresent`는 `/home/hyungchul/workspace/stt/input_sound/kr/customer/01.wav`가 있을 때만(`Assume.assumeTrue`) 읽어 `durationSec`가 84.0..84.2.
- [ ] **Step 5: 커밋** — `"WAV 입출력과 16kHz mono 변환"`

### Task 3: 앱 폴더와 입력 스캔 (`core/storage`)

**Files:**
- Create: `core/storage/AppDirs.kt`, `InputItem.kt`, `InputScanner.kt`
- Test: `core/storage/InputScannerTest.kt`

**Interfaces:**
- Produces:
  - `class AppDirs(val root: java.io.File) { val inputSound: File /* root/input_sound */; val models: File; val output: File /* root/output_stt */; val tmp: File; fun runDir(runId: String): File; companion object { fun from(context: android.content.Context): AppDirs /* getExternalFilesDir(null) */ } }`
  - `data class InputItem(val lang: String /* 폴더명 "kr"|"en" */, val relDir: String /* "customer", "customer/test", 최상위면 "" */, val stem: String, val file: java.io.File) { val engineLang: String /* kr→"ko", en→"en" */ }`
  - `object InputScanner { val LANG_FOLDERS = mapOf("kr" to "ko", "en" to "en"); fun scan(inputRoot: File): List<InputItem> }` — `inputRoot/<lang>/**.wav`(대소문자 무시), 알 수 없는 언어 폴더·비 wav 무시, 정렬 lang→relDir→stem.

- [ ] **Step 1: 실패 테스트** (TemporaryFolder): `kr/customer/01.wav`, `kr/customer/test/01.wav`, `en/vLog/10.WAV`, `en/vLog/note.txt`, `jp/x/01.wav` 생성 →
  - `scanFindsWavsUnderKnownLangs`: 결과 3개, 순서 `[en vLog 10, kr customer 01, kr customer/test 01]`.
  - `engineLangMapping`: kr→"ko", en→"en".
  - `missingRootGivesEmpty`: 없는 폴더 → 빈 리스트.
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → **Step 4: PASS 확인** (`--tests "com.cowork.stt_ondevice.core.storage.*"`)
- [ ] **Step 5: 커밋** — `"앱 폴더 구조와 입력 음원 스캔"`

### Task 4: PC 평가기 호환 출력 (`output`)

**Files:**
- Create: `output/OutputLayout.kt`, `TranscriptWriter.kt`, `SttCsvHeader.kt`, `ResultCsvWriter.kt`, `core/json/Json.kt`
- Test: `output/OutputLayoutTest.kt`, `TranscriptWriterTest.kt`, `ResultCsvWriterTest.kt`

**Interfaces:**
- Consumes: `InputItem` (Task 3)
- Produces:
  - `object OutputLayout { fun comboName(algo: String, model: String, vadPresetId: String): String /* "${algo}_${model}_${vad}" */; fun transcript(runDir: File, item: InputItem, combo: String): File; fun errorFile(runDir: File, item: InputItem, combo: String): File /* <stem>.ERROR.txt */; fun resultCsv(runDir: File, lang: String, combo: String): File; fun detail(runDir: File, item: InputItem, combo: String): File /* details/<lang>/<combo>/<relDir>/<stem>.json */; fun vadSegments(runDir: File, presetId: String, item: InputItem): File /* vad_segments/<preset>/<lang>/<relDir>/<stem>.json */; fun manifest(runDir: File): File; fun log(runDir: File, runId: String): File /* _logs/run_<id>.log */; fun summaryCsv(runDir: File, runId: String): File /* _summary/all_result_<id>.csv */ }`
  - `data class TimedText(val startSec: Double, val endSec: Double, val text: String)`
  - `object TranscriptWriter { fun format(engineLabel: String, vadId: String, chunkCount: Int, fullText: String, segments: List<TimedText>): String; fun write(file: File, content: String) /* UTF-8 no BOM, 부모 폴더 생성 */ ; fun writeError(file: File, stage: String, code: String, message: String) }`
  - `val STT_CSV_HEADER: List<String>` — PC `stt_common.py` 94-180행의 60개 열을 **순서 그대로** 복사(run_id … text).
  - `class ResultCsvWriter(val file: File) { fun append(row: Map<String, String?>) }` — 파일이 없으면 BOM(EF BB BF)+헤더, 모든 행은 60열, 없는 키/null은 빈 칸, RFC4180 따옴표.
  - `val AppJson: kotlinx.serialization.json.Json` (prettyPrint, encodeDefaults, explicitNulls=true).

- [ ] **Step 1: 실패 테스트**
  - `comboAndPaths`: item(kr, "customer/test", "01") + combo `whispercpp_small_SILERO_SAFE` → transcript `run/kr/whispercpp_small_SILERO_SAFE/customer/test/01.txt`, error `.../01.ERROR.txt`, resultCsv `run/kr/whispercpp_small_SILERO_SAFE/result.csv`.
  - `formatMatchesPcHeaderStyle`: `format("whispercpp small","SILERO_SAFE",2,"안녕 하세요", [TimedText(0.42,28.1,"안녕"), TimedText(29.05,55.8,"하세요")])` ==
    ```
    [전체 텍스트] (엔진: whispercpp small / VAD: SILERO_SAFE / 조각 2개)
    안녕 하세요

    [구간별] start(sec), end(sec), text
    0.42, 28.10, 안녕
    29.05, 55.80, 하세요
    ```
    (줄바꿈 `\n`, 마지막 줄 뒤 `\n`). 전체 텍스트에 포함된 줄바꿈은 공백으로 치환.
  - `headerHas59Columns`: `STT_CSV_HEADER.size == 60`, first `run_id`, last `text`, index of `status` < `text`.
  - `csvBomHeaderAndEscaping`: 두 번 append → 바이트 앞 3개 EF BB BF, 헤더 1회, `text`에 `a,"b"\nc` → `"a,""b""\nc"`.
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → **Step 4: PASS** (`--tests "com.cowork.stt_ondevice.output.*"`)
- [ ] **Step 5: 커밋** — `"PC 평가기 호환 출력 경로·txt·CSV"`

### Task 5: ChunkPlanner (`segment`)

**Files:**
- Create: `segment/AudioChunk.kt`, `segment/ChunkPlanner.kt`, `vad/SpeechSegment.kt`
- Test: `segment/ChunkPlannerTest.kt`

**Interfaces:**
- Produces:
  - `data class SpeechSegment(val startSec: Double, val endSec: Double)`
  - `data class VadOutput(val segments: List<SpeechSegment>, val frameHopSec: Double?, val frameScores: FloatArray?)` (`vad/SpeechSegment.kt`에 함께)
  - `enum class ChunkMode { MERGE, INDIVIDUAL }`, `enum class SplitMethod { NONE, MIN_SCORE, FORCED }`
  - `data class SourceSpan(val startSec: Double, val endSec: Double, val chunkOffsetSec: Double)`
  - `data class AudioChunk(val index: Int, val samples: FloatArray, val spans: List<SourceSpan>, val split: SplitMethod) { val startSec: Double; val endSec: Double /* 원본 기준 첫 span 시작 ~ 마지막 span 끝 */ }`
  - `object ChunkPlanner { fun plan(audio: FloatArray, sampleRate: Int, vad: VadOutput, maxChunkSec: Double, mode: ChunkMode, joinGapSec: Double = 0.2): List<AudioChunk> }`

알고리즘(spec §8):
1. 각 segment를 오디오 범위로 clamp, 길이 0 이하 제거.
2. `maxChunkSec > 0` 이고 segment 길이 > maxChunkSec 이면 분할: 탐색 구간 `[start+1.0, start+maxChunkSec]` 안에서 `frameScores` 최저 프레임 중심 시각에서 자름(`MIN_SCORE`), 점수가 없으면 `start+maxChunkSec`(`FORCED`). 나머지에 반복.
3. INDIVIDUAL: 조각 하나 = chunk 하나.
4. MERGE: 누적 길이 + (비어있지 않으면 joinGap) + 조각 길이 ≤ maxChunkSec 이면 같은 chunk에 0으로 채운 joinGap 뒤에 붙이고, 아니면 새 chunk. `maxChunkSec == 0` 이면 전부 하나.
5. `spans[i].chunkOffsetSec` = 그 조각이 chunk 안에서 시작하는 위치.

- [ ] **Step 1: 실패 테스트** (sampleRate 16000, 오디오 100 s, 값 = 시간 인코딩)
  - `mergePacksUpToMax`: segs [0-10],[12-20],[25-40] max 30 → 2 chunks: chunk0 spans 2개, 샘플 수 = (10+0.2+8)*16000, chunk1 = [25-40].
  - `individualOnePerSegment`: 같은 입력 INDIVIDUAL → 3 chunks.
  - `longSegmentSplitAtMinScore`: seg [0-70], scores hop 0.1 s, 25.0 s 지점만 0.0 나머지 0.9 → 조각 [0-25],[25-55],[55-70] 중 첫 분할 MIN_SCORE; 두 번째는 탐색구간 최저점 기준.
  - `longSegmentForcedWithoutScores`: scores null → [0-30],[30-60],[60-70], split FORCED.
  - `unlimitedMaxMergesAll`: max 0 → 1 chunk.
  - `noSegmentsGivesNoChunks`: 빈 segments → 빈 리스트.
  - `clampsOutOfRange`: seg [95-120] → [95-100].
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → **Step 4: PASS** (`--tests "*.ChunkPlannerTest"`)
- [ ] **Step 5: 커밋** — `"VAD 구간을 엔진 최대길이로 묶고 자르는 ChunkPlanner"`

## B. VAD

### Task 6: VAD 프리셋 레지스트리와 인터페이스

**Files:**
- Create: `vad/VadFamily.kt`, `vad/VadParams.kt`, `vad/VadPreset.kt`, `vad/VadPresets.kt`, `vad/VadEngine.kt`, `vad/impl/NoneVad.kt`, `model/CommercialStatus.kt`
- Test: `vad/VadPresetsTest.kt`, `vad/impl/NoneVadTest.kt`

**Interfaces:**
- Consumes: `SpeechSegment`, `VadOutput` (Task 5)
- Produces:
  - `enum class CommercialStatus { ALLOWED, CONDITIONAL, NONCOMMERCIAL, UNVERIFIED }`
  - `enum class VadFamily { NONE, SILERO, PYANNOTE, FSMN, WEBRTC, TEN, FIRERED, MARBLENET, YAMNET }`
  - `sealed interface VadParams` 구현:
    - `SileroParams(threshold: Float, minSpeechMs: Int, minSilenceMs: Int, speechPadMs: Int, maxSpeechSec: Double = Double.POSITIVE_INFINITY)`
    - `PyannoteParams(onset: Float, offset: Float, minDurationOnSec: Double, minDurationOffSec: Double)`
    - `FsmnParams(maxEndSilenceTime: Int, maxStartSilenceTime: Int, windowSizeMs: Int, silToSpeechTimeThres: Int, speechToSilTimeThres: Int, speech2NoiseRatio: Float, doExtend: Int, lookbackTimeStartPoint: Int, lookaheadTimeEndPoint: Int, maxSingleSegmentTime: Int, snrThres: Float, decibelThres: Float, speechNoiseThres: Float, fePriorThres: Float, speechNoiseThreshLow: Float, speechNoiseThreshHigh: Float, frameInMs: Int, frameLengthMs: Int, detectMode: Int, snrMode: Int)`
    - `enum class WebRtcStyle { RAW, RING_BUFFER, HANGOVER }`; `WebRtcParams(mode: Int, frameMs: Int, style: WebRtcStyle, paddingMs: Int = 300, ratio: Float = 0.9f, speechMs: Int = 0, silenceMs: Int = 0)`
    - `HangoverParams(threshold: Float, speechMs: Int, silenceMs: Int)` (TEN_DEFAULT, YAMNET_*)
    - `enum class FireRedMode { OFFLINE, STREAM }`; `FireRedParams(mode: FireRedMode, threshold: Float, smoothWindow: Int, minSpeechFrames: Int, maxSpeechFrames: Int, minSilenceFrames: Int, mergeSilenceFrames: Int, extendFrames: Int, padStartFrames: Int)`
    - `NemoParams(onset: Float, offset: Float, padOnsetSec: Double, padOffsetSec: Double, minDurationOnSec: Double, minDurationOffSec: Double, filterSpeechFirst: Boolean)`
    - `data object NoneParams`
  - `data class VadPreset(val id: String, val family: VadFamily, val params: VadParams, val source: String, val note: String = "")`
  - `object VadPresets { val all: List<VadPreset>; fun byId(id: String): VadPreset; fun commercialOf(family: VadFamily): CommercialStatus }` — commercial: SILERO/PYANNOTE/WEBRTC/FIRERED/MARBLENET/YAMNET=ALLOWED, FSMN/TEN=CONDITIONAL, NONE=ALLOWED.
  - `interface VadEngine : AutoCloseable { val family: VadFamily; fun detect(audio: FloatArray, preset: VadPreset): VadOutput }` (입력은 항상 16 kHz mono)
  - `class NoneVad : VadEngine` — 전체 길이 1개 구간, scores null.

- [ ] **Step 1: 실패 테스트**
  - `presetCount`: `VadPresets.all.size == 26` (Silero 8 + pyannote 4 + FSMN 2 + WebRTC 3 + TEN 2 + FireRed 2 + MarbleNet 1 + Yamnet 3 + NONE 1), id 중복 없음.
  - `sileroValuesExact`: spec §7.3 Silero 표 8행을 `(id, th, minSpeech, minSilence, pad, maxSpeech)` 튜플 리스트로 그대로 assert (SILERO_SHERPA만 maxSpeech 20.0).
  - `otherFamiliesSpotCheck`: PYA2_VOXCONVERSE (0.767, 0.713, 0.182, 0.501), FSMN_ASR30.maxSingleSegmentTime == 30000, WEBRTC_ANDROIDVAD (mode 3, 20 ms, HANGOVER, 50, 300), TEN_SHERPA (0.5, 250, 500, pad 0, max 20.0), FIRERED_OFFLINE (0.4, smooth 5, min 20, max 2000, sil 20), MARBLENET_NEMO (0.3,0.3,0.2,0.2,0.2,0.2,true), YAMNET_VERY_AGGRESSIVE threshold 0.85.
  - `noneVadReturnsWholeFile`: 16000*5 샘플 → segments `[0.0-5.0]`.
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** — 모든 값은 spec §7.3 표에서 복사, 각 프리셋 `source`에 출처 문자열, SILERO_SHERPA/SILERO_WS/SILERO_FW/PYA2_*/WEBRTC_PYEXAMPLE에는 spec의 주의 문구를 `note`로.
- [ ] **Step 4: PASS** (`--tests "com.cowork.stt_ondevice.vad.*"`)
- [ ] **Step 5: 커밋** — `"VAD 프리셋 26종 레지스트리와 VadEngine 인터페이스"`

### Task 7: 골든 데이터 생성 도구 (`tools/golden`)

**Files:**
- Create: `tools/golden/requirements.txt`, `make_clips.py`, `gen_silero.py`, `gen_pyannote.py`, `gen_fsmn.py`, `gen_webrtc.py`, `gen_ten.py`, `gen_fbank.py`, `README_한국어.md`
- Create(커밋): `app/src/test/resources/golden/*.json`; (gitignore) `app/src/test/resources/golden/audio/*.wav`
- Modify: `.gitignore` (`app/src/test/resources/golden/audio/`)

**Interfaces:**
- Produces: 골든 JSON 공통 형식 (FireRed/MarbleNet 은 Task 16에서 같은 형식으로 추가)
  ```json
  {"clip": "kr_customer_01_20s.wav", "family": "SILERO", "presetId": "SILERO_SAFE",
   "hopSec": 0.032, "frameTimesSec": null, "frameScores": [..], "frameDb": null,
   "expected": [{"start": 0.42, "end": 3.10}], "tool": "silero-vad 6.2.3"}
  ```
  파일명 `golden/<family>_<presetId>_<clipStem>.json`. 모델 추론 정합용은 `golden/scores_<family>_<clipStem>.json` (`frameScores`만).

- [ ] **Step 1: venv** — `python3 -m venv tools/golden/.venv && tools/golden/.venv/bin/pip install -r tools/golden/requirements.txt` — requirements: `silero-vad`(6.2.x), `onnxruntime`, `numpy`, `soundfile`, `funasr-onnx`, `webrtcvad-wheels`, `kaldi-native-fbank`, ten-vad(공식 repo의 Python 설치 방법), `pyannote.audio`(선택). 설치된 실제 버전을 `pip freeze > tools/golden/requirements.lock.txt`로 고정.
- [ ] **Step 2: `make_clips.py`** — `~/workspace/stt/input_sound/kr/customer/01.wav` 0–20 s → `kr_customer_01_20s.wav`, `en/Customer_Service/01.wav` 0–20 s → `en_customer_01_20s.wav`, 합성 `synth_silence_noise_8s.wav`(2 s 무음 + 4 s 백색잡음 -30 dBFS + 2 s 무음, seed 0).
- [ ] **Step 3: 생성 스크립트** (각각 3개 클립 × 해당 family 전 프리셋)
  - `gen_silero.py`: 공식 v6 ONNX로 512-hop 확률 → `get_speech_timestamps(..., return_seconds=False)`를 프리셋 파라미터로 호출(`max_speech_duration_s`, `min_silence_duration_ms`, `speech_pad_ms`, `min_speech_duration_ms`, `threshold`), 확률 배열과 결과 저장. TEN_SHERPA 골든은 `gen_ten.py`가 TEN 확률에 같은 함수를 적용해 생성.
  - `gen_pyannote.py`: sherpa `pyannote-segmentation-3-0` ONNX를 pyannote Inference와 같은 창(10 s, step 1 s)으로 실행·집계한 speech 점수 → `pyannote.audio.utils.signal.Binarize(onset, offset, min_duration_on, min_duration_off)` 결과. `HF_TOKEN` 이 있으면 HF 원본 `pyannote/segmentation-3.0` VAD pipeline 결과도 `expected_original`로 저장해 집계 방식 차이를 기록, 없으면 README에 "원본 pipeline 비교 미수행" 기록.
  - `gen_fsmn.py`: `funasr_onnx.Fsmn_vad`(같은 ONNX) 실행, 내부 silence 확률·프레임 dB를 후킹해 `frameScores`(sil prob), `frameDb` 저장, 최종 구간 `expected`. FSMN_ASR30은 config의 `max_single_segment_time` 30000으로.
  - `gen_webrtc.py`: `webrtcvad` 프레임 bool(0/1 float로 `frameScores`) + RAW/RING_BUFFER(py-webrtcvad example.py 로직 그대로)/HANGOVER(android-vad 의미: speechMs 연속 음성 시 시작, silenceMs 연속 무음 시 종료) 결과.
  - `gen_ten.py`: ten-vad Python으로 hop 256 확률/flag → TEN_DEFAULT(flag 병합), TEN_SHERPA(Silero식) 결과.
  - `gen_fbank.py`: `kaldi-native-fbank`(dither 0, hamming, 25/10 ms, 80 mel, snip_edges=True, 입력 ×32768) 출력 앞 50프레임 → `fbank_<clip>.json`, FSMN `am.mvn` 적용 후 LFR(m5,n1) 앞 10프레임 → `lfr_cmvn_<clip>.json`.
- [ ] **Step 4: 검증** — `ls app/src/test/resources/golden/*.json | wc -l` ≥ 45, 각 JSON `expected` 키 존재, synth 클립의 Silero 결과에 무음 구간(0–2 s)이 포함되지 않음을 스크립트 assert로 확인.
- [ ] **Step 5: 커밋** — JSON·스크립트만 (`git add tools/golden app/src/test/resources/golden/*.json .gitignore`), `"VAD 골든 데이터 생성 도구와 기준 결과"`

### Task 8: 골든 로더 + Silero식 후처리 (`SileroTimestamps`)

**Files:**
- Create: `vad/post/SileroTimestamps.kt`, Test: `vad/GoldenLoader.kt`(test 소스), `vad/post/SileroTimestampsTest.kt`

**Interfaces:**
- Consumes: `SileroParams`, golden JSON (Task 7)
- Produces:
  - (test) `object GoldenLoader { fun load(name: String): Golden; fun allFor(family: String): List<Golden> }`, `data class Golden(val clip: String, val family: String, val presetId: String, val hopSec: Double?, val frameTimesSec: DoubleArray?, val frameScores: FloatArray, val frameDb: FloatArray?, val expected: List<SpeechSegment>)`, `fun assertSegmentsClose(expected, actual, tolSec: Double)` — 개수 동일 + 각 경계 |차| ≤ tol.
  - `object SileroTimestamps { fun apply(probs: FloatArray, windowSamples: Int, sampleRate: Int, totalSamples: Int, p: SileroParams): List<SpeechSegment> }` — silero-vad v6.2.3 `utils_vad.py get_speech_timestamps` 후처리 부분을 줄 단위로 포팅(neg_threshold, temp_end, prev_end/next_start, `min_silence_at_max_speech`=98 ms, `use_max_poss_sil_at_max_speech`=true, 패딩·인접구간 반분 규칙 포함). 원본 행 번호를 주석으로 남긴다.

- [ ] **Step 1: 실패 테스트** — `matchesGoldenForAllSileroPresets`: `GoldenLoader.allFor("SILERO")` 각 항목에 대해 `apply(scores, 512, 16000, totalSamples, VadPresets.byId(presetId).params)` vs `expected`, tol = 1 샘플(1/16000 s). + `tenSherpaGolden`: TEN_SHERPA 골든(TEN 확률, hop 256)도 같은 함수로 통과.
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → **Step 4: PASS** (`--tests "*.SileroTimestampsTest"`)
- [ ] **Step 5: 커밋** — `"Silero get_speech_timestamps 후처리 포팅(골든 일치)"`

### Task 9: pyannote Binarize·FSMN 상태머신 후처리

**Files:**
- Create: `vad/post/PyannoteBinarize.kt`, `vad/post/FsmnStateMachine.kt`
- Test: `vad/post/PyannoteBinarizeTest.kt`, `vad/post/FsmnStateMachineTest.kt`

**Interfaces:**
- Produces:
  - `object PyannoteBinarize { fun apply(scores: FloatArray, frameTimesSec: DoubleArray, p: PyannoteParams): List<SpeechSegment> }` — `pyannote.audio.utils.signal.Binarize.__call__` 포팅(onset/offset 히스테리시스, pad 0, min_duration_off 로 짧은 공백 메움, min_duration_on 미만 제거).
  - `object FsmnStateMachine { fun apply(silProb: FloatArray, frameDb: FloatArray, p: FsmnParams): List<SpeechSegment> }` — FunASR `funasr_onnx/utils/e2e_vad.py`(E2EVadModel) 의 오프라인 1회 호출(is_final=true) 경로 포팅. 프레임 10 ms.

- [ ] **Step 1: 실패 테스트** — `matchesGoldenPyannote` (4 프리셋 × 3 클립, tol 1 프레임 = 0.016875 s), `matchesGoldenFsmn` (2 프리셋 × 3 클립, tol 0.010 s).
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → **Step 4: PASS** (`--tests "com.cowork.stt_ondevice.vad.post.*"`)
- [ ] **Step 5: 커밋** — `"pyannote Binarize, FSMN 상태머신 후처리 포팅"`

### Task 10: WebRTC·Hangover 후처리

**Files:**
- Create: `vad/post/WebRtcPost.kt`, `vad/post/SimpleHangover.kt`
- Test: `vad/post/WebRtcPostTest.kt`, `vad/post/SimpleHangoverTest.kt`

**Interfaces:**
- Produces:
  - `object WebRtcPost { fun raw(flags: BooleanArray, frameMs: Int): List<SpeechSegment>; fun ringBuffer(flags: BooleanArray, frameMs: Int, paddingMs: Int, ratio: Float): List<SpeechSegment> }` — `ringBuffer`는 py-webrtcvad `example.py vad_collector` 포팅(구간 시작 = 링버퍼 첫 프레임 시각, 끝 = 트리거 해제 프레임 끝, 파일 끝에서 열린 구간 닫기).
  - `object SimpleHangover { fun apply(scores: FloatArray, hopSec: Double, threshold: Float, speechMs: Int, silenceMs: Int): List<SpeechSegment> }` — score ≥ threshold 를 음성 프레임으로, 연속 음성 ≥ speechMs 이면 그 연속 구간 시작부터 음성, 연속 무음 ≥ silenceMs 이면 무음 시작 시각에서 종료. speechMs=silenceMs=0 이면 연속 음성 프레임 병합(RAW와 동일).
- [ ] **Step 1: 실패 테스트** — 골든 WEBRTC_* 3 프리셋 × 3 클립(tol = frameMs), TEN_DEFAULT 골든(SimpleHangover th 0.5, 0, 0), 손작성 케이스 `hangoverBridgesShortSilence`: hop 0.01, 음성 50프레임·무음 20프레임·음성 50프레임, silenceMs 300 → 구간 1개 [0.0, 1.2].
- [ ] **Step 2~4** (`--tests "*.WebRtcPostTest" --tests "*.SimpleHangoverTest"`)
- [ ] **Step 5: 커밋** — `"WebRTC ring buffer·hangover 후처리"`

### Task 11: Kaldi fbank·LFR·CMVN (`vad/feature`)

**Files:**
- Create: `vad/feature/KaldiFbank.kt`, `Lfr.kt`, `Cmvn.kt`
- Test: `vad/feature/KaldiFbankTest.kt`

**Interfaces:**
- Produces:
  - `class KaldiFbank(val numMel: Int = 80, val frameLengthMs: Int = 25, val frameShiftMs: Int = 10, val sampleRate: Int = 16000) { fun compute(samples: FloatArray /* -1..1, 내부에서 ×32768 */): Array<FloatArray> }` — kaldi: dither 0, remove DC, preemph 0.97, povey→**hamming**(FunASR 설정), snip_edges=true, power spectrum, mel 20 Hz..Nyquist, log(max(eps)).
  - `object Lfr { fun apply(feats: Array<FloatArray>, m: Int = 5, n: Int = 1): Array<FloatArray> }` — FunASR `apply_lfr`(앞쪽 (m-1)/2 프레임 복제 패딩, 끝 부족분 마지막 프레임 복제).
  - `class Cmvn(val shift: FloatArray, val scale: FloatArray) { fun apply(x: Array<FloatArray>): Array<FloatArray>; companion object { fun loadAmMvn(file: java.io.File): Cmvn } }` — Kaldi nnet `am.mvn` 텍스트(`<AddShift>`, `<Rescale>`) 파싱, (x + shift) * scale.
- [ ] **Step 1: 실패 테스트** — `fbankMatchesKaldiNative`: 골든 `fbank_<clip>.json` 앞 50프레임 원소별 |차| ≤ 1e-3(log 영역), `lfrCmvnMatches`: 골든 `lfr_cmvn_*` 10프레임 |차| ≤ 1e-3 (am.mvn 은 테스트 리소스 `golden/fsmn_am.mvn` 로 Task 7에서 복사).
- [ ] **Step 2~4** (`--tests "*.KaldiFbankTest"`)
- [ ] **Step 5: 커밋** — `"Kaldi fbank·LFR·CMVN 전처리"`

### Task 12: ORT 기반 VAD — Silero, pyannote, FSMN

**Files:**
- Create: `vad/impl/SileroVad.kt`, `vad/impl/PyannoteVad.kt`, `vad/impl/FsmnVad.kt`, `vad/OrtSessions.kt`
- Test: `vad/impl/OrtVadParityTest.kt`
- Modify: `app/build.gradle.kts` (`implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")`)

**Interfaces:**
- Consumes: post 객체들(Task 8, 9), feature(Task 11), `VadEngine`
- Produces:
  - `object OrtSessions { fun open(modelFile: java.io.File, threads: Int = 1): ai.onnxruntime.OrtSession }`
  - `class SileroVad(modelFile: File) : VadEngine` — v6 입력 `input`[1, 64+512], `state`[2,1,128], `sr`(int64 16000), 출력 확률·새 state, 매 창마다 앞 64 샘플 context 유지. `fun probabilities(audio: FloatArray): FloatArray` 공개(테스트용). `detect`는 `SileroTimestamps.apply` 로 구간, `frameHopSec = 0.032`, scores 포함.
  - `class PyannoteVad(modelFile: File) : VadEngine` — ONNX metadata(`window_size`, `receptive_field_size`, `receptive_field_shift`, `num_speakers`, `powerset_max_classes`, `num_classes`)를 읽어 사용. 10 s 창, step 1 s, 마지막 창 0 패딩, powerset→speech 점수 집계 방식은 Task 7 `gen_pyannote.py` 와 동일. `fun speechScores(audio): Pair<FloatArray, DoubleArray>`.
  - `class FsmnVad(modelDir: File /* model_quant.onnx, am.mvn, config.yaml */) : VadEngine` — fbank→LFR→CMVN→ONNX(입력 이름·cache 텐서 형상은 `funasr_onnx` `vad_bin.py` 와 동일하게) → sil 확률·프레임 dB → `FsmnStateMachine.apply`.
- [ ] **Step 1: 실패 테스트** (모델 파일이 `tools/golden/models/` 에 있고 클립 WAV가 있을 때만 `Assume`) — `sileroProbsMatchPython`(|차| ≤ 1e-3), `pyannoteScoresMatchPython`(≤ 1e-3), `fsmnSegmentsMatchPython`(구간 tol 0.010 s).
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → **Step 4: PASS** (`--tests "*.OrtVadParityTest"`), 출력에 SKIPPED 가 없음을 확인(모델·클립 준비 상태에서).
- [ ] **Step 5: 커밋** — `"ORT 기반 Silero·pyannote·FSMN VAD"`

### Task 13: FireRedVAD·MarbleNet ONNX 변환 도구 (`tools/export`)

**Files:**
- Create: `tools/export/requirements.txt`, `requirements-nemo.txt`, `export_firered.py`, `export_marblenet.py`, `gen_firered_golden.py`, `gen_marblenet_golden.py`, `README_한국어.md`

- [ ] **Step 1: venv** — `tools/export/.venv`(CPU torch, FireRedVAD 공식 repo 의존성), `tools/export/.venv-nemo`(NeMo toolkit ASR). 설치 버전 `requirements*.lock.txt` 고정.
- [ ] **Step 2: FireRedVAD** — 공식 repo(FireRedTeam/FireRedVAD) 특정 commit 고정, 공식 `export_onnx.py`로 offline·stream 모델 ONNX, 원본 PyTorch vs ONNX 프레임 확률 |차| ≤ 1e-4 assert, 출력 `tools/export/out/firered/`(+ cmvn 파일).
- [ ] **Step 3: MarbleNet** — `nvidia/Frame_VAD_Multilingual_MarbleNet_v2.0` .nemo → 모델 카드 스크립트로 ONNX, NeMo preprocessor 설정(sample_rate, n_mels/n_mfcc, window, hop, normalize, dither=0)을 `marblenet_preprocessor.json`으로 저장, 원본 vs ONNX 확률 |차| ≤ 1e-4.
- [ ] **Step 4: 골든** — `gen_firered_golden.py`: FireRedVAD Python 원본 후처리(offline/stream) 결과 + 프레임 확률 + fbank 앞 50프레임을 Task 7 형식으로 `golden/`에 저장. `gen_marblenet_golden.py`: NeMo preprocessor 출력 앞 50프레임 + 확률 + `frame_vad_infer_postprocess.yaml` 기준 NeMo 후처리 결과.
- [ ] **Step 5: 검증** — 각 스크립트 exit 0, 생성 파일 목록과 SHA-256을 README에 기록. 변환 실패 시 해당 VAD를 `BLOCKED`로 README·테스트보고서에 기록하고 Task 14의 그 VAD 부분은 건너뛴다.
- [ ] **Step 6: 커밋** — `"FireRedVAD·MarbleNet ONNX 변환·골든 도구"`

### Task 14: FireRed·MarbleNet VAD (후처리 + 추론)

**Files:**
- Create: `vad/post/FireRedPostprocess.kt`, `vad/post/NemoPostprocess.kt`, `vad/feature/NemoMelSpectrogram.kt`, `vad/impl/FireRedVad.kt`, `vad/impl/MarbleNetVad.kt`
- Test: `vad/post/FireRedNemoPostTest.kt`, `vad/impl/FireRedMarbleNetParityTest.kt`

**Interfaces:**
- Produces:
  - `object FireRedPostprocess { fun apply(probs: FloatArray, p: FireRedParams): List<SpeechSegment> }` — OFFLINE은 `fireredvad/vad.py`, STREAM은 `stream_vad.py` 포팅, 프레임 10 ms.
  - `object NemoPostprocess { fun apply(probs: FloatArray, hopSec: Double, p: NemoParams): List<SpeechSegment> }` — NeMo `vad_utils.binarization` + `filtering` 포팅.
  - `class NemoMelSpectrogram(config: java.io.File /* marblenet_preprocessor.json */) { fun compute(samples: FloatArray): Array<FloatArray> }`
  - `class FireRedVad(modelDir: File) : VadEngine`, `class MarbleNetVad(modelDir: File) : VadEngine` (MarbleNet hop 0.02 s)
- [ ] **Step 1: 실패 테스트** — 후처리 골든(FIRERED_OFFLINE/STREAM × 3 클립 tol 0.01 s, MARBLENET_NEMO × 3 tol 0.02 s), 멜 특징 앞 50프레임 |차| ≤ 1e-3, 모델 확률 |차| ≤ 1e-3 (모델·클립 없으면 Assume skip).
- [ ] **Step 2~4** (`--tests "*.FireRedNemoPostTest" --tests "*.FireRedMarbleNetParityTest"`)
- [ ] **Step 5: 커밋** — `"FireRedVAD·MarbleNet VAD"`

### Task 15: 네이티브 VAD — libfvad(WebRTC)·TEN, CMake 골격

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`, `fvad_jni.cpp`, `ten_vad_jni.cpp`, `vad/impl/FvadNative.kt`, `vad/impl/WebRtcVad.kt`, `vad/impl/TenVadNative.kt`, `vad/impl/TenVad.kt`, `tools/fetch_native_deps.sh`, `tools/check_16kb.sh`
- Submodule: `app/src/main/cpp/third_party/libfvad` (dpirch/libfvad, 특정 commit 고정)
- Modify: `app/build.gradle.kts` (`externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }`, `ndkVersion = "30.0.16248370"`)
- Test: `app/src/androidTest/java/com/cowork/stt_ondevice/NativeSmokeTest.kt`

**Interfaces:**
- Produces:
  - `object FvadNative { external fun processAll(mode: Int, frameMs: Int, pcm: ShortArray): BooleanArray }` (libfvad: new→set_mode→set_sample_rate(16000)→프레임별 process→free)
  - `object TenVadNative { external fun create(hop: Int, threshold: Float): Long; external fun processAll(handle: Long, pcm: ShortArray): FloatArray /* hop 단위 확률 */; external fun destroy(handle: Long) }`
  - `class WebRtcVad : VadEngine` (style 에 따라 `WebRtcPost.raw/ringBuffer` 또는 `SimpleHangover`), `class TenVad : VadEngine`(TEN_DEFAULT→SimpleHangover, TEN_SHERPA→SileroTimestamps(windowSamples=256))
  - `tools/fetch_native_deps.sh` — sherpa AAR(v1.13.8 static-link-onnxruntime)과 TEN `lib/Android/arm64-v8a/libten_vad.so`·`include/ten_vad.h`(태그/커밋 고정)를 받아 SHA-256 검사, 경로: `app/libs/`, `app/src/main/cpp/third_party/ten-vad/`.
  - `tools/check_16kb.sh <apk>` — APK의 모든 `lib/arm64-v8a/*.so` 에 대해 NDK `llvm-readelf -l` LOAD Align이 `0x4000` 이상인지 검사, 하나라도 아니면 exit 1.
- [ ] **Step 1: `fetch_native_deps.sh` 작성·실행** → 파일 존재·SHA 일치.
- [ ] **Step 2: CMake** — `add_library(stt_native SHARED fvad_jni.cpp ten_vad_jni.cpp)`, libfvad 소스 add_subdirectory 또는 직접 소스 목록, TEN은 `IMPORTED` 공유 라이브러리로 링크하고 APK 포함(`jniLibs` 대신 CMake `IMPORTED` + gradle `jniLibs.srcDirs`는 금지 — CMake가 복사하도록 `add_custom_command` 또는 AGP가 IMPORTED 를 패키징하는지 빌드 결과로 확인). 링크 옵션 `-Wl,-z,max-page-size=16384`, `ANDROID_STL=c++_static`.
- [ ] **Step 3: 빌드 검증** — `./gradlew :app:assembleDebug && tools/check_16kb.sh app/build/outputs/apk/debug/app-debug.apk` → 모두 OK, `unzip -l` 에 `libstt_native.so`, `libten_vad.so` 존재.
- [ ] **Step 4: `NativeSmokeTest`(기기 필요)** — `fvadDetectsSpeechInClip`, `tenVadReturnsProbabilities`: assets 없이 테스트가 `/sdcard/Android/data/<pkg>/files/input_sound/kr/customer/01.wav` 를 읽어 0–20 s 처리, 구간 1개 이상. 실행: `./gradlew :app:connectedDebugAndroidTest` (기기 미연결이면 "미수행"으로 테스트보고서 기록).
- [ ] **Step 5: 커밋** — `"WebRTC(libfvad)·TEN VAD 네이티브와 16KB 검사 스크립트"`

### Task 16: Yamnet VAD (LiteRT)

**Files:**
- Create: `vad/impl/YamnetVad.kt`, Test: `vad/impl/YamnetVadTest.kt`
- Modify: `app/build.gradle.kts` (LiteRT Maven 최신 안정판 확인 후 고정)

**Interfaces:**
- Produces: `class YamnetVad(modelFile: File) : VadEngine` — 0.975 s(15600 샘플) 창, hop 은 android-vad `FRAME_SIZE_243` 의미를 소스로 확인해 고정(ms 또는 샘플), 출력 521 클래스 중 `Speech`(index 0) 점수 → `SimpleHangover`.
- [ ] **Step 1: 사전 확인** — LiteRT `.so` 16KB 정렬을 Task 15 스크립트로 확인. 불충족 시 Yamnet 을 `BLOCKED`(사유 기록)로 두고 Task 종료.
- [ ] **Step 2: 실패 테스트** — `speechClassIndexIsZero`(모델 metadata/라벨 파일에서 확인), `scoresToSegments`: 고정 점수 배열 → 기대 구간.
- [ ] **Step 3~4: 구현·PASS** (`--tests "*.YamnetVadTest"`), 기기 smoke 는 Task 15 `NativeSmokeTest`에 `yamnetRuns` 추가.
- [ ] **Step 5: 커밋** — `"Yamnet VAD(LiteRT)"`

### Task 17: VadEngineFactory와 VadCache

**Files:**
- Create: `vad/VadEngineFactory.kt`, `vad/VadCache.kt`
- Test: `vad/VadCacheTest.kt`

**Interfaces:**
- Consumes: 모든 VAD impl, `ModelStore`(Task 18의 `modelDir(spec)`는 아직 없으므로 생성자에 `vadModelDir: (VadFamily) -> File?` 주입)
- Produces:
  - `class VadEngineFactory(private val vadModelDir: (VadFamily) -> File?) { fun create(family: VadFamily): VadEngine /* 모델 없으면 VadUnavailableException(family) */ }`
  - `class VadCache(private val runDir: File) { fun getOrCompute(preset: VadPreset, item: InputItem, audioSha1: String, compute: () -> VadOutput): VadOutput }` — `OutputLayout.vadSegments` 에 `{presetId, audioSha1, segments, frameHopSec, vadSec}` 저장(scores는 저장하지 않고 메모리 LRU 1개만), 파일 존재+sha 일치 시 재사용.
- [ ] **Step 1: 실패 테스트** — `computesOnceThenReuses`(compute 호출 횟수 1), `shaMismatchRecomputes`, `writesJsonAtLayoutPath`.
- [ ] **Step 2~4** (`--tests "*.VadCacheTest"`)
- [ ] **Step 5: 커밋** — `"VAD 엔진 팩토리와 (프리셋,파일) 결과 캐시"`

## C. 모델 설치

### Task 18: 모델 카탈로그 고정 (`tools/hash_models.py`) + `ModelSpec`

**Files:**
- Create: `tools/hash_models.py`, `tools/model_sources.json`, `model/ModelSpec.kt`, `model/ModelCatalog.kt`, `model/ModelCatalogData.kt`(스크립트 생성), `model/README_카탈로그.md`
- Test: `model/ModelCatalogTest.kt`

**Interfaces:**
- Produces:
  - `enum class ModelKind { ASR, VAD }`, `enum class Runtime { WHISPER_CPP, SHERPA_OFFLINE, SHERPA_ONLINE, VOSK, ORT, LITERT }`, `enum class ArchiveType { NONE, TAR_BZ2, ZIP }`, `enum class StreamingKind { FILE_ONLY, NATIVE_STREAMING }`
  - `data class ModelFile(val path: String, val bytes: Long, val sha256: String, val role: String)`
  - `data class Download(val url: String, val bytes: Long, val sha256: String, val archive: ArchiveType, val stripPrefix: String)`
  - `sealed interface EngineConfig` : `WhisperConfig(val beamSize: Int = 5)`, `enum class SherpaKind { ZIPFORMER_TRANSDUCER, SENSEVOICE, QWEN3_ASR, OMNILINGUAL_CTC, STREAMING_ZIPFORMER }`, `SherpaConfig(val kind: SherpaKind)`, `data object VoskConfig`, `VadModelConfig(val family: VadFamily)`
  - `data class ModelSpec(val id: String, val kind: ModelKind, val algo: String, val model: String, val displayName: String, val languages: Set<String>, val runtime: Runtime, val runtimeVersion: String, val precision: String, val maxChunkSec: Double, val streaming: StreamingKind, val downloads: List<Download> /* 비어있으면 import 전용 */, val files: List<ModelFile>, val originalRepo: String, val license: String, val licenseUrl: String, val commercial: CommercialStatus, val compatAlias: String?, val engineConfig: EngineConfig)`
  - `object ModelCatalog { val all: List<ModelSpec>; fun byId(id: String): ModelSpec; val asr: List<ModelSpec>; fun vadModel(family: VadFamily): ModelSpec? }`
- `tools/model_sources.json` 항목(사람이 관리하는 원본 목록): spec §9.2 의 ASR 모델 전부 + VAD 모델(silero v6 onnx, pyannote seg-3.0 sherpa tar.bz2, fsmn onnx 3파일, yamnet tflite, firered/marblenet(로컬 export, url 없음)). 후보 URL:
  - Whisper: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{tiny,base,small,medium}.bin`, `ggml-large-v3-turbo-q5_0.bin`, `ggml-large-v3-turbo-q8_0.bin`
  - sherpa: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/<name>.tar.bz2` — 이름은 `gh release view asr-models -R k2-fsa/sherpa-onnx --json assets`로 실재 확인 후 고정(zipformer korean 2024-06-24, streaming korean 2024-06-16, EN offline zipformer 1종, SenseVoice int8 최신, qwen3-asr 0.6B int8 2026-03-25, omnilingual 300M v1/300M v2/1B v2 int8)
  - Vosk: `https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip`, `vosk-model-small-en-us-0.15.zip`
  - pyannote: `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2`
  - FSMN: modelscope `iic/speech_fsmn_vad_zh-cn-16k-common-onnx` 의 `model_quant.onnx`, `am.mvn`, `config.yaml`
  - Silero v6.2.3 / Yamnet: 공식 저장소에서 실제 경로 확인 후 고정
- `hash_models.py`: 각 항목 다운로드(`tools/models_cache/`, gitignore) → 압축 해제 → 파일 목록·bytes·SHA-256 계산 → 모델 카드/LICENSE 확인 결과(사람이 `model_sources.json`에 기록한 license/commercial) 와 합쳐 `ModelCatalogData.kt` 생성(한국어 헤더·항목 주석 포함). 확인 못 한 라이선스는 `UNVERIFIED`.

- [ ] **Step 1: 실패 테스트** — `catalogHasAllPhase1Asr`: algo/model 쌍 집합 == {whispercpp:{tiny,base,small,medium,large-v3-turbo-q5_0,large-v3-turbo-q8_0}, sherpa-zipformer:{ko-2024-06-24, en-*}, sherpa-zipformer-streaming:{ko-2024-06-16}, sherpa-sensevoice:{small-int8}, sherpa-qwen3asr:{0.6B-int8}, sherpa-omnilingual:{ctc300m-v1-int8, ctc300m-v2-int8, ctc1b-v2-int8}, vosk:{small-ko-0.22, small-en-us-0.15}}; `everyFileHasSha256`(64 hex); `languagesCorrect`(zipformer ko={"ko"}, vosk en={"en"}, whisper={"ko","en"}); `maxChunk`(whisper 30, vosk 0, streaming 0); `noWhisperEnglishOnly`(model 이름에 `.en` 없음); `vadModelsPresent`(SILERO, PYANNOTE, FSMN 존재).
- [ ] **Step 2: 실패 확인** → **Step 3: `model_sources.json` 작성 → `python3 tools/hash_models.py` 실행 → `ModelCatalogData.kt` 생성** (다운로드 약 7 GB, 시간 소요) → `ModelSpec.kt`, `ModelCatalog.kt` 작성.
- [ ] **Step 4: PASS** (`--tests "*.ModelCatalogTest"`), `README_카탈로그.md`에 각 모델 라이선스 근거 URL·확인일 표.
- [ ] **Step 5: 커밋** — `"모델 카탈로그(실제 SHA-256 고정)와 생성 스크립트"`

### Task 19: 설치·검증·import (`ModelStore`, `Downloader`, `ArchiveExtractor`, `ModelInstaller`)

**Files:**
- Create: `model/Sha256.kt`, `model/ArchiveExtractor.kt`, `model/Downloader.kt`, `model/ModelStore.kt`, `model/ModelInstaller.kt`
- Test: `model/ArchiveExtractorTest.kt`, `model/ModelInstallerTest.kt`
- Modify: `app/build.gradle.kts` (commons-compress)

**Interfaces:**
- Consumes: `ModelSpec`, `AppDirs`
- Produces:
  - `object Sha256 { fun of(file: File): String }`
  - `class UnsafeArchiveEntryException(entry: String) : java.io.IOException`
  - `object ArchiveExtractor { fun extract(archive: File, type: ArchiveType, destDir: File, stripPrefix: String) }` — `..`/절대경로/심볼릭 링크 차단.
  - `class Downloader { suspend fun download(url: String, dest: File /* .part 이어받기 */, expectedBytes: Long, onProgress: (Long, Long) -> Unit) }` — `HttpURLConnection`, `Range: bytes=<partSize>-`, 206 아니면 처음부터.
  - `enum class InstallState { NOT_INSTALLED, DOWNLOADING, VERIFYING, READY, CORRUPT }`
  - `class ModelStore(private val dirs: AppDirs) { fun modelDir(spec: ModelSpec): File; fun state(spec: ModelSpec): InstallState /* models/<id>/.ready.json 존재 + 파일 크기 일치 → READY */; fun acquire(spec: ModelSpec); fun release(spec: ModelSpec); fun delete(spec: ModelSpec): Boolean /* 사용 중이면 false */ }`
  - `class ModelInstaller(private val dirs: AppDirs, private val store: ModelStore, private val downloader: Downloader) { suspend fun install(spec: ModelSpec, onProgress: (Long, Long) -> Unit); suspend fun importFrom(spec: ModelSpec, source: File /* 폴더 또는 archive */); fun verify(spec: ModelSpec): List<String> /* 문제 목록, 비면 OK */ }` — tmp/<id>-<nonce>/ 에 받고 풀고 `files` 전부 SHA-256 검사 → `models/<id>` 로 rename + `.ready.json`. 저장공간: `dirs.root.usableSpace < sum(bytes) * 2.2` 이면 `InsufficientStorageException`.
- [ ] **Step 1: 실패 테스트** — `zipSlipRejected`(`../evil` 엔트리 → 예외, dest 밖 파일 없음), `stripPrefixApplied`, `installFromLocalHttpServer`: JDK `com.sun.net.httpserver.HttpServer`로 tar.bz2 제공 → READY, `resumeUsesRange`(서버가 Range 헤더 수신 기록), `checksumMismatchLeavesNotInstalled`(tmp 정리, state NOT_INSTALLED), `deleteRefusedWhileAcquired`, `importFolderVerifies`.
- [ ] **Step 2~4** (`--tests "com.cowork.stt_ondevice.model.*"`)
- [ ] **Step 5: 커밋** — `"모델 다운로드·import·SHA-256 검증·원자적 설치"`

## D. 엔진

### Task 20: 엔진 계약과 whisper.cpp

**Files:**
- Create: `engine/AsrEngine.kt`, `engine/EngineFactory.kt`, `engine/whisper/WhisperNative.kt`, `engine/whisper/WhisperCppEngine.kt`, `app/src/main/cpp/whisper_jni.cpp`
- Submodule: `app/src/main/cpp/third_party/whisper.cpp` @ tag `v1.9.5`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Test: `engine/FakeEngineTest.kt`(계약 기본 동작), `NativeSmokeTest.whisperTinyTranscribes`

**Interfaces:**
- Consumes: `AudioChunk`, `ModelSpec`, `ModelStore`
- Produces:
  - `data class PrepareInfo(val loadSec: Double, val runtimeVersion: String, val backend: String, val precision: String, val threads: Int)`
  - `data class ChunkText(val text: String, val rawText: String, val detectedLang: String?, val inferSec: Double)`
  - `class EngineException(val stage: String, val code: String, message: String) : Exception(message)`
  - `interface AsrEngine : AutoCloseable { val spec: ModelSpec; fun supports(lang: String): Boolean = lang in spec.languages; suspend fun prepare(threads: Int): PrepareInfo; suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText; fun cancel() }`
  - `object EngineFactory { fun create(spec: ModelSpec, modelDir: File): AsrEngine }` — runtime 별 분기(이후 Task 21, 22에서 채움, 미구현 runtime 은 `EngineException("MODEL_LOAD","NOT_IMPLEMENTED")`).
  - `object WhisperNative { external fun init(modelPath: String): Long; external fun transcribe(ctx: Long, samples: FloatArray, lang: String, threads: Int, beamSize: Int): String; external fun abort(ctx: Long); external fun free(ctx: Long); external fun version(): String }`
  - `class WhisperCppEngine(spec, modelFile: File) : AsrEngine` — `whisper_full_params`: BEAM_SEARCH beam 5(PC faster-whisper 기본과 동일), `language`=ko/en 고정, `translate=false`, `no_context=true`, `print_*=false`, `n_threads=threads`, abort_callback = 원자 플래그. 결과 = 모든 segment 텍스트 trim 후 공백 결합. precision 은 모델 파일명(f16/q5_0/q8_0)에서.
- [ ] **Step 1: CMake** — whisper.cpp `add_subdirectory`(BUILD_SHARED_LIBS OFF, `GGML_OPENMP` 기본, `-march=armv8.2-a+fp16+dotprod`), `whisper_jni.cpp` 를 `stt_native` 에 추가. CMake 4.1.2 에서 `cmake_minimum_required` 경고/오류 발생 시 `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` 를 arguments 로 추가하고 사유 주석.
- [ ] **Step 2: 실패 테스트(JVM)** — `FakeEngineTest.defaultSupports`: languages={"ko"} 인 spec 으로 `supports("en") == false`.
- [ ] **Step 3: 구현** → `./gradlew :app:assembleDebug` 성공, `tools/check_16kb.sh` OK.
- [ ] **Step 4: 기기 smoke** — `whisperTinyTranscribes`: tiny 모델이 설치된 경우(`Assume`) kr/customer/01.wav 0–20 s 전사 결과 비어있지 않음, `abortStopsWithinOneSecond`. 기기 없으면 미수행 기록.
- [ ] **Step 5: 커밋** — `"엔진 계약과 whisper.cpp JNI 엔진"`

### Task 21: sherpa-onnx 엔진 (Zipformer, streaming Zipformer, SenseVoice, Qwen3-ASR, Omnilingual)

**Files:**
- Copy: sherpa-onnx v1.13.8 `sherpa-onnx/kotlin-api/` 중 오프라인/온라인 인식에 필요한 `.kt` → `app/src/main/java/com/k2fsa/sherpa/onnx/`, + `README_한국어.md`
- Create: `engine/sherpa/SherpaConfigs.kt`, `SherpaOfflineEngine.kt`, `SherpaStreamingEngine.kt`, `SenseVoiceText.kt`
- Modify: `app/build.gradle.kts` (`implementation(files("libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"))`), `EngineFactory.kt`
- Test: `engine/sherpa/SenseVoiceTextTest.kt`, `engine/sherpa/SherpaConfigsTest.kt`

**Interfaces:**
- Produces:
  - `object SherpaConfigs { fun offline(spec: ModelSpec, dir: File, threads: Int): com.k2fsa.sherpa.onnx.OfflineRecognizerConfig; fun online(spec: ModelSpec, dir: File, threads: Int): com.k2fsa.sherpa.onnx.OnlineRecognizerConfig }` — 파일 경로는 `spec.files` 의 `role`(encoder/decoder/joiner/tokens/model/conv_frontend/tokenizer 등, Task 18에서 실제 archive 기준으로 부여)로 찾는다. 필드명은 복사한 v1.13.8 소스 그대로. `provider = "cpu"`, `numThreads = threads`, `decodingMethod = "greedy_search"`.
  - `object SenseVoiceText { fun clean(raw: String): String }` — `<|...|>` 태그 제거 + 공백 정리.
  - `class SherpaOfflineEngine(spec, dir) : AsrEngine` — chunk 마다 stream 생성→`acceptWaveform(samples, 16000)`→`decode`→`getResult`. SenseVoice/Qwen3/Omnilingual 언어 지정은 해당 config 의 language 필드(있을 때만)로 ko/en. `rawText` 에는 결과 원문(SenseVoice 는 lang/emotion/event 포함 직렬화), `text` 는 정제본.
  - `class SherpaStreamingEngine(spec, dir) : AsrEngine` — chunk 를 0.1 s 단위로 accept, ready 동안 decode, 끝에 0.8 s 0 패딩 + `inputFinished()` 후 decode, 최종 텍스트.
- [ ] **Step 1: 실패 테스트** — `senseVoiceTagsStripped`: `"<|ko|><|NEUTRAL|><|Speech|><|woitn|>안녕하세요"` → `"안녕하세요"`; `configPicksFilesByRole`: 가짜 spec(roles encoder/decoder/joiner/tokens) + 임시 폴더 → config 의 각 경로가 해당 파일 절대경로, numThreads 반영.
- [ ] **Step 2: 실패 확인** → **Step 3: 구현** → 빌드 + `check_16kb.sh` OK, APK 에 `libonnxruntime.so` 가 **onnxruntime-android 것 1개만** 존재(`unzip -l` 확인, 중복이면 원인 분석).
- [ ] **Step 4: PASS** (`--tests "com.cowork.stt_ondevice.engine.sherpa.*"`) + 기기 smoke `sherpaZipformerKoTranscribes`, `sherpaQwen3Transcribes`(설치 시).
- [ ] **Step 5: 커밋** — `"sherpa-onnx 오프라인·스트리밍 엔진(Zipformer, SenseVoice, Qwen3-ASR, Omnilingual)"`

### Task 22: Vosk 엔진

**Files:**
- Create: `engine/vosk/VoskEngine.kt`; Modify: `app/build.gradle.kts`(`com.alphacephei:vosk-android:0.3.75`, `net.java.dev.jna:jna:5.18.1@aar`), keep rules(`-keep class com.sun.jna.** { *; }`, `-keep class org.vosk.** { *; }`), `EngineFactory.kt`
- Test: `engine/vosk/VoskResultTest.kt`

**Interfaces:**
- Produces: `class VoskEngine(spec, dir) : AsrEngine` — `Model(dir)` 1회, chunk 마다 `Recognizer(model, 16000f)`→`acceptWaveForm(ShortArray, n)` 4000 샘플씩→`finalResult` JSON 의 `text`. `object VoskResult { fun text(json: String): String }`.
- [ ] **Step 1: 실패 테스트** — `parsesFinalResultText`: `{"text" : "안녕 하세요"}` → `"안녕 하세요"`, 빈 text → `""`.
- [ ] **Step 2~4** + 빌드·16KB 검사, 기기 smoke `voskKoTranscribes`.
- [ ] **Step 5: 커밋** — `"Vosk 엔진"`

## E. 배치 실행

### Task 23: 기기 정보·자원 측정

**Files:**
- Create: `core/device/DeviceInfo.kt`, `core/device/ResourceSampler.kt`
- Test: `core/device/ResourceSamplerTest.kt`

**Interfaces:**
- Produces:
  - `data class DeviceInfo(val manufacturer: String, val model: String, val socModel: String? /* Build.SOC_MODEL */, val androidRelease: String, val sdkInt: Int, val abi: String, val totalRamMb: Long, val appVersion: String) { companion object { fun collect(context: Context): DeviceInfo } }`
  - `data class EnvSnapshot(val thermalStatus: Int?, val thermalHeadroom: Float?, val batteryPct: Int?, val charging: Boolean?)` + `fun snapshot(context): EnvSnapshot`
  - `class ResourceSampler(private val readPssKb: () -> Long?, private val readCpuMs: () -> Long, private val intervalMs: Long = 200) { fun start(scope: CoroutineScope); fun stop(): ResourceStats }`, `data class ResourceStats(val pssPeakMb: Double?, val cpuTimeSec: Double, val samples: Int, val method: String = "Debug.getPss() 200ms 샘플링")`
- [ ] **Step 1: 실패 테스트** (가짜 함수 주입, `runTest` + virtual time) — `peakIsMaxOfSamples`, `nullPssGivesNullPeak`, `cpuTimeIsDelta`.
- [ ] **Step 2~4** (`--tests "*.ResourceSamplerTest"`) **Step 5: 커밋** — `"기기 정보·PSS·발열 측정"`

### Task 24: RunConfig·RunManifest·DetailJson

**Files:**
- Create: `runner/RunConfig.kt`, `runner/RunEvent.kt`, `output/RunManifest.kt`, `output/DetailJson.kt`
- Test: `output/RunManifestTest.kt`

**Interfaces:**
- Produces:
  - `data class RunConfig(val runId: String, val items: List<InputItem>, val models: List<ModelSpec> /* 선택 순서 */, val presets: List<VadPreset>, val chunkMode: ChunkMode, val maxChunkOverride: Map<String /* model id */, Double>, val threads: Int = 4, val resume: Boolean = false) { fun maxChunkFor(spec: ModelSpec): Double }`
  - `sealed interface RunEvent { data class Progress(val done: Int, val total: Int, val current: String) : RunEvent; data class ItemDone(val combo: String, val item: InputItem, val status: String) : RunEvent; data class Finished(val ok: Int, val noMatch: Int, val error: Int, val unsupported: Int) : RunEvent }`
  - `@Serializable data class RunManifest(val schemaVersion: Int = 1, val runId: String, val startedAt: String, val device: DeviceInfoJson, val models: List<ModelEntry> /* id, algo, model, files sha256, license, commercial, runtimeVersion */, val presets: List<PresetEntry> /* id, family, params 문자열, source, note, commercial */, val chunkMode: String, val threads: Int, val unsupported: List<String> /* "combo|lang" */, val inFlight: String? /* 진행 중 항목 키, 크래시 판정용 */, val finishedAt: String?)`
  - `@Serializable data class DetailJson(val combo: String, val file: String, val status: String, val error: ErrorJson?, val audioSec: Double, val preprocessSec: Double?, val vadSec: Double?, val loadSec: Double?, val transcribeSec: Double?, val elapsedSec: Double?, val rtf: Double?, val pssPeakMb: Double?, val env: EnvSnapshotJson?, val chunks: List<ChunkJson> /* index, spans, split, text, rawText, inferSec */)`
- [ ] **Step 1: 실패 테스트** — `manifestRoundTrip`(encode→decode 동일), `nullsAreExplicit`(측정 안 한 값이 JSON 에 `null` 로 존재), `maxChunkOverrideApplied`.
- [ ] **Step 2~4** (`--tests "*.RunManifestTest"`) **Step 5: 커밋** — `"실행 설정·manifest·상세 JSON 스키마"`

### Task 25: BatchRunner

**Files:**
- Create: `runner/BatchRunner.kt`, `runner/RunState.kt`
- Test: `runner/BatchRunnerTest.kt` (가짜 엔진·VAD·sampler)

**Interfaces:**
- Consumes: 위 모든 것
- Produces:
  - `class BatchRunner(private val dirs: AppDirs, private val engineFactory: (ModelSpec) -> AsrEngine, private val vadFactory: (VadFamily) -> VadEngine, private val samplerFactory: () -> ResourceSampler, private val env: () -> EnvSnapshot?, private val device: DeviceInfo, private val clock: () -> Long = System::nanoTime) { fun run(config: RunConfig): kotlinx.coroutines.flow.Flow<RunEvent> }`

동작(spec §10, §11):
1. 시작 시 manifest 작성(resume 이면 기존 manifest 읽기). `inFlight` 가 남아 있으면 그 항목을 `ERROR(TRANSCRIBE, CRASH_OR_KILLED)` 로 기록.
2. 언어 미지원 조합 → `unsupported` 에 추가, 폴더 생성 없음.
3. 모델 순서대로 `prepare` → (실패 시 그 모델의 모든 항목 ERROR(MODEL_LOAD, code)) → 프리셋 → 파일: `inFlight` 갱신 → WAV 읽기/변환(preprocess_sec) → `VadCache` → `ChunkPlanner` → chunk 별 `transcribe` → txt/CSV/detail 쓰기 → `inFlight=null`. resume 이고 txt 또는 ERROR.txt 가 있으면 건너뜀.
4. 발화 0개 → 엔진 호출 없이 빈 전체 텍스트 txt, status `NO_MATCH`.
5. 항목 예외 → `ERROR.txt` + CSV status `ERROR`, note 에 stage/code, 다음 항목 계속.
6. CSV 행: spec §11 매핑(algo, model, exec_mode=embedded, engine_version, rel_dir, file, audio_ext=.wav, audio_bytes, audio_sec, sample_rate/channels(원본), req_lang, detected_lang, task=transcribe(whisper만, 그 외 빈 값), device=cpu, device_name, compute_type, workers, cpu_time_sec, rss_peak_mb(=PSS peak), preprocess_sec, upload_sec 빈 값, transcribe_sec, cloud_total_sec=transcribe_sec, elapsed_sec, rtf=transcribe_sec/audio_sec 소수 4자리, attempts=1, status, utterances=chunk 수, chars, words, text_sha1(앞 12자), note=`vad=..;chunk_mode=..;max_chunk=..;alias=..`, text). 나머지 빈 값. 같은 행에 `_summary/all_result_<id>.csv` 에도 append(헤더 = 60열 + `vad`).
7. 취소: 코루틴 취소 시 현재 chunk 후 `engine.cancel()`/`close()` 보장, manifest `finishedAt=null`.
8. 모델 단위 `close()` 를 finally 로 보장(`ModelStore.acquire/release` 는 `engineFactory` 래퍼가 담당 — Task 27 `RunService` 에서 `{ spec -> store.acquire(spec); EngineFactory.create(spec, store.modelDir(spec)).also { onClose = { store.release(spec) } } }` 형태로 주입).
9. 진행 로그: 항목 시작/종료·오류·모델 로드 시간을 `OutputLayout.log(runDir, runId)` 에 한 줄씩 append(시각, combo, file, status, 메시지).

- [ ] **Step 1: 실패 테스트**
  - `orderIsModelThenPresetThenFile`: 모델 2, 프리셋 2, 파일 2 → 가짜 엔진 호출 순서 기록 검증.
  - `vadComputedOncePerPresetAndFile`: 모델 2개여도 VAD detect 호출 = 프리셋×파일.
  - `writesPcCompatibleTree`: 실행 후 `run/kr/fake_m1_SILERO/customer/01.txt` 가 `[전체 텍스트]` 형식, `result.csv` 60열 1행+헤더.
  - `silentFileWritesNoMatch`: VAD 0 구간 → 엔진 호출 0회, CSV status `NO_MATCH`.
  - `errorIsolated`: 두 번째 파일에서 엔진 예외 → `02.ERROR.txt`, 세 번째 파일 OK.
  - `modelLoadFailureMarksAllItems`: prepare 예외 → 그 모델 항목 전부 ERROR(MODEL_LOAD), 다음 모델 정상.
  - `unsupportedComboCreatesNoFolder`: languages={"ko"} 모델 × en 파일 → en 폴더 없음, manifest.unsupported 포함.
  - `resumeSkipsCompletedItems`: 1회차 중간 취소 → resume=true 2회차는 남은 항목만 엔진 호출.
  - `resumeMarksInFlightItemAsCrashed`: manifest.inFlight 설정 상태에서 resume → 그 항목 ERROR.txt(code CRASH_OR_KILLED), 나머지 진행.
  - `cancelClosesEngine`: 취소 후 가짜 엔진 `closed == true`.
  - `writesRunLog`: 실행 후 `_logs/run_<id>.log` 에 항목 수만큼 종료 줄 존재.
- [ ] **Step 2~4** (`--tests "*.BatchRunnerTest"`)
- [ ] **Step 5: 커밋** — `"엔진×VAD×파일 배치 실행기(재개·오류 격리)"`

### Task 26: PC 평가기 페어링 통합 확인 (`tools/check_pairing.sh`)

**Files:**
- Create: `tools/check_pairing.sh`, Test: `runner/SampleRunExportTest.kt`

**Interfaces:**
- Consumes: `BatchRunner`(가짜 엔진이 정답 텍스트를 그대로 돌려주도록)
- Produces: `SampleRunExportTest.exportSampleRun` 가 `app/build/sample_run/<run_id>/` 생성(kr: customer/01, friend/01 · en: Customer_Service/01, 가짜 모델 2 × 프리셋 NONE·SILERO). `tools/check_pairing.sh` 가 사용자 평가기를 **읽기 전용**으로 실행: `~/workspace/python/venv_eval/bin/python ~/workspace/python/asr_stt/stt_all_gui_2nd/run_eval.py --ref ~/workspace/stt/answer_sheet/<lang> --hyp app/build/sample_run/<id>/<lang> --out <scratch>/eval_<lang> --no-noformat`.
- [ ] **Step 1: 테스트 실행** → sample_run 생성. **Step 2: 스크립트 실행** → `by_model.csv` 에 `fake_m1_NONE`, `fake_m1_SILERO`, `fake_m2_*` 4개 모델이 kr/en 각각 존재, 모든 WER(%) == 0.0 이면 exit 0.
- [ ] **Step 3: 커밋** — `"PC 평가기 페어링 통합 확인 스크립트"`

### Task 27: 포그라운드 실행 서비스

**Files:**
- Create: `runner/RunService.kt`; Modify: `AndroidManifest.xml`
- Test: 기기 수동 확인 항목(테스트보고서)

**Interfaces:**
- Produces: `class RunService : Service` — `foregroundServiceType="specialUse"`(+ `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="on-device STT benchmark batch"/>`), 권한 `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`. `companion object { val events: StateFlow<RunUiState>; fun start(context, config: RunConfig); fun cancel(context) }`. 진행 알림(모델/VAD/파일, done/total), PARTIAL_WAKE_LOCK 은 실행 중에만.
- [ ] **Step 1: 구현** → **Step 2: 빌드 성공** → **Step 3: 기기 확인**(화면 끔 10분 후에도 진행, 알림 취소 버튼 동작) — 기기 없으면 미수행 기록.
- [ ] **Step 4: 커밋** — `"배치 실행 포그라운드 서비스"`

## F. 화면과 입출력

### Task 28: 화면 골격·모델 관리 화면

**Files:**
- Create: `ui/AppNav.kt`, `ui/models/ModelsScreen.kt`, `ui/models/ModelsViewModel.kt`; Modify: `MainActivity.kt`
- Test: `ui/models/ModelsViewModelTest.kt`

**Interfaces:**
- Produces: 하단 탭 5개(모델 / 실행 / 진행 / 결과 / 마이크). `class ModelsViewModel(store, installer, catalog) : ViewModel { val rows: StateFlow<List<ModelRow>>; fun download(id); fun cancel(id); fun import(id, uri); fun delete(id); fun verify(id) }`, `data class ModelRow(val id, val group: String /* algo */, val name, val sizeMb: Long, val languages: String, val license: String, val commercial: CommercialStatus, val state: InstallState, val progress: Float?)` — VAD 모델도 별도 그룹으로 표시. 상업 상태는 색 배지(허용/조건부/비상업/미확인).
- [ ] **Step 1: 실패 테스트** — `rowsReflectStoreState`(가짜 store: READY/NOT_INSTALLED 반영), `deleteInUseShowsError`.
- [ ] **Step 2~4** **Step 5: 커밋** — `"화면 골격과 모델 관리 화면"`

### Task 29: 실행 설정·진행·결과 화면

**Files:**
- Create: `ui/run/RunSetupScreen.kt`, `RunSetupViewModel.kt`, `ui/run/RunProgressScreen.kt`, `ui/results/ResultsScreen.kt`, `ResultsViewModel.kt`
- Test: `ui/run/RunSetupViewModelTest.kt`

**Interfaces:**
- Produces: `class RunSetupViewModel(scanner, catalog, store) { val state: StateFlow<RunSetupState>; fun toggleLang(lang); fun toggleItem(item); fun toggleModel(id) /* 선택 순서 유지 */; fun togglePreset(id); fun setChunkMode(m); fun setMaxChunk(id, sec); fun setThreads(n); fun comboCount(): Int; fun buildConfig(now: java.time.LocalDateTime): RunConfig }`. `comboCount` = Σ(설치된 선택 모델 × 선택 프리셋 × 그 모델이 지원하는 언어의 선택 파일). 미설치 모델은 선택 불가(이유 표시). 결과 화면: `output_stt` 의 run 목록 → combo 별 OK/NO_MATCH/ERROR 수(result.csv 집계), 파일별 txt 미리보기, "SAF로 내보내기" 버튼(Task 30).
- [ ] **Step 1: 실패 테스트** — `comboCountRespectsLanguage`(ko 전용 모델 1 + 공용 모델 1, 프리셋 2, kr 2·en 3 파일 → 1×2×2 + 1×2×5 = 14), `modelSelectionOrderPreserved`, `runIdFormat`(`20261009_131500`), `uninstalledModelNotSelectable`.
- [ ] **Step 2~4** **Step 5: 커밋** — `"실행 설정·진행·결과 화면"`

### Task 30: SAF 입출력·마이크 녹음·adb 스크립트

**Files:**
- Create: `core/storage/SafBridge.kt`, `core/audio/MicRecorder.kt`, `ui/mic/MicScreen.kt`, `MicViewModel.kt`, `tools/adb/push_inputs.sh`, `push_models.sh`, `pull_results.sh`
- Modify: `AndroidManifest.xml` (`RECORD_AUDIO`)
- Test: `core/audio/MicRecorderTest.kt`

**Interfaces:**
- Produces:
  - `class SafBridge(context) { suspend fun importTree(treeUri: Uri, dest: File): Int /* 복사 파일 수, wav 만 */; suspend fun exportDir(src: File, treeUri: Uri): Int }`
  - `class MicRecorder(private val source: PcmSource) { suspend fun record(stop: kotlinx.coroutines.flow.StateFlow<Boolean>): ShortArray; val actualSampleRate: Int; val actualChannels: Int }` + `interface PcmSource { val sampleRate: Int; val channels: Int; fun read(buf: ShortArray): Int; fun release() }` (`AudioRecordSource` = 16 kHz mono PCM16 실제 값 기록). 저장: `input_sound/<lang>/mic/<yyyyMMdd_HHmmss>.wav`.
  - adb 스크립트: `push_inputs.sh <input_sound 경로>` → `adb push` 로 `/sdcard/Android/data/com.cowork.stt_ondevice/files/input_sound/`, `push_models.sh <model_id> <로컬 폴더>`, `pull_results.sh [run_id] [PC 대상 폴더]`.
- [ ] **Step 1: 실패 테스트** — `recordsUntilStop`(가짜 소스 3 블록 후 stop → 길이 합), `writesWavAt16kMono`.
- [ ] **Step 2~4** **Step 5: 커밋** — `"SAF 입출력·마이크 녹음·adb 스크립트"`

## G. 마무리 검증

### Task 31: 전체 빌드 검증·기기 smoke·문서

**Files:**
- Create: `docs/사용설명서.md`, `docs/테스트보고서_phase1.md`

- [ ] **Step 1: 2차 검증** — `./gradlew clean :app:assembleDebug :app:testDebugUnitTest` 전부 PASS(SKIPPED 목록 기록), `tools/check_16kb.sh app/build/outputs/apk/debug/app-debug.apk` OK, APK 내 중복 `.so` 없음(`unzip -l | sort | uniq -d`).
- [ ] **Step 2: 3차 검증(S25 Ultra 연결 시)** — `adb devices` 확인 → 설치 → `push_inputs.sh ~/workspace/stt/input_sound` → 모델 설치(tiny, zipformer-ko, qwen3 0.6B, vosk ko/en, silero/pyannote/fsmn VAD) → 실행: kr/customer/01·en/Customer_Service/01 × {whispercpp tiny, sherpa-zipformer ko, sherpa-qwen3asr, vosk} × {NONE, SILERO, PYA3_DEFAULT, FSMN_DEFAULT, WEBRTC_ANDROIDVAD, TEN_DEFAULT} → 취소 후 재개 1회 → 비행기 모드 1조합 → `pull_results.sh` → `check_pairing.sh` 와 같은 방식으로 실제 결과에 `run_eval.py` 실행. `connectedDebugAndroidTest` 결과 첨부. 기기 미연결이면 전부 "미수행"으로 기록하고 재현 절차만 작성.
- [ ] **Step 3: `사용설명서.md`** — Android Studio 열기·빌드, `fetch_native_deps.sh`·submodule 초기화, 설치, 모델 설치(앱/adb/SAF), 음원 push, 실행 설정, 결과 pull, PC 평가 명령.
- [ ] **Step 4: `테스트보고서_phase1.md`** — 엔진·모델별 표(spec 프롬프트 §12 열: 체크포인트 확인 / Android build / 실제 load / file 전사 / mic / offline / 정확도 확인 / 미완료·원인), VAD별 골든 일치 결과, BLOCKED 항목과 다음 조치.
- [ ] **Step 5: 커밋** — `"Phase 1 검증 결과와 사용설명서"` (push 하지 않음)
