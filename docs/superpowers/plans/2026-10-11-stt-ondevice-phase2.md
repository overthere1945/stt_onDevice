# STT_OnDevice Phase 2 구현 계획서

> **에이전트 작업자용:** 필수 하위 스킬 — superpowers:subagent-driven-development(권장) 또는 superpowers:executing-plans 로 작업을 하나씩 수행한다. 단계는 체크박스(`- [ ]`)로 추적한다.

**목표:** 모델 파일이 없는 시스템 엔진 2종(ML Kit GenAI Basic, Android 온디바이스 인식기)을 기존 VAD 조각 경로로 공정 비교하고, USER VAD 프리셋 편집과 1차 보류 정리를 더한다.

**구조:** 시스템 엔진은 Android 의존이 없는 세션 핵심(`SystemAsrEngine` + 공급기·감시·재시도, PC 단위 테스트)과, 얇은 Android 연결부(`MlKitBackend`·`PlatformBackend`, 실기기 계측 테스트)로 나눈다. 배치 실행기는 "멈춤 → 일시정지" 경로와 "언어 사용 가능 여부 확인"만 새로 받는다. USER 프리셋은 파라미터 ↔ 문자열 맵 변환기(`PresetParamCodec`)와 저장소(`UserPresetStore`), 기본+USER 합본(`PresetRegistry`)으로 만든다.

**기술 스택:** Kotlin 2.4.21, AGP 9.4.1(내장 Kotlin), Compose BOM 2026.09.00, minSdk 31, `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1`, `android.speech.SpeechRecognizer`(API 33+ 기능), kotlinx-coroutines 1.11.0(테스트는 `kotlinx-coroutines-test` 가상 시간), JUnit4.

**설계서:** `docs/superpowers/specs/2026-10-11-stt-ondevice-phase2-design.md` (이하 P2 spec). Phase 1 설계서 `docs/superpowers/specs/2026-10-09-stt-ondevice-phase1-design.md`. 실험 기록 `.superpowers/sdd/phase2-notes/spike-report.md`.

## 전역 제약

- 주석: 모든 새·수정 파일에 한국어 파일 머리말(파일명·목적·기능), 함수 머리말(목적/기능/입력/출력/리턴), 줄 주석. 바꾸거나 더한 줄에 `change(add)-hyungchul-YYYY-MM-DD-HH:mm`(Asia/Seoul 실제 시각). 기존 import·함수·변수 순서 유지.
- 모든 화면 문구·오류 메시지·로그 메시지는 한국어.
- 공개 저장소: 모델·음원·사용자 음성에서 나온 특징 데이터는 커밋 금지. push 는 사용자 요청 시에만.
- 기기: `connectedDebugAndroidTest`·앱 삭제 금지(사용자 WAV 54개·모델 8개가 지워짐). `adb install -r` + `adb shell am instrument` 만 사용. 시스템 설정 변경 금지. 다른 앱(Speech Services)의 동의 창을 대신 누르지 않는다.
- `.so` 16 KB 정렬(`tools/check_16kb.sh` 통과), `pickFirst` 금지.
- 측정 불가 값은 빈 값(null). 다른 엔진·모델로 조용히 대체하지 않는다.
- 시스템 엔진 언어 코드: 앱 `ko` → `ko-KR`, `en` → `en-US`.
- 시스템 엔진 공급 배속: 1·2·4. 감시 시간 = `ceil(조각초 × 1000 ÷ 배속) + 15000` ms. 공급 블록 = 100 ms(3,200 바이트, PCM16 LE mono 16 kHz).
- 시스템 엔진 maxChunkSec 기본 30.0, 덮어쓰기 허용 범위 (0, 60] 초(0 = 무제한 금지 — 61 초 넘는 세션 앞부분 잘림 실측).
- PC 단위 테스트: `./gradlew :app:testDebugUnitTest`. 빌드: `./gradlew :app:assembleDebug :app:assembleRelease`.

## 검토 중점 (어느 작업의 기본 테스트도 직접 겨냥하지 않지만 실제로 사용자를 괴롭힐 입력·상황)

1. **1× 로 30 초 조각을 공급하는 도중 사용자가 취소** → 30 초를 다 기다리지 않고 1 초 안에 세션이 중단되고 취소가 전파되어야 한다. (작업 3 `cancelDuringPacedFeedAbortsWithinOneSecond`)
2. **엔진이 오류로 세션을 먼저 끝냈는데 공급기가 아직 쓰는 중**(영어 모델 미설치 등) → 공급기가 막히지 않고 즉시 끝나며 항목은 `LANGUAGE_UNAVAILABLE` 오류. (작업 3 `engineErrorWhileFeedingClosesPipeAndMapsCode`)
3. **무음뿐인 조각·0.05 초짜리 조각** → 엔진의 "음성 없음"은 오류가 아니라 빈 텍스트, 항목은 정상 처리. (작업 3 `noSpeechIsEmptyText`, `tinyChunkIsFed`)
4. **USER 프리셋 이름이 기본 프리셋과 대소문자만 다르거나 앞뒤 공백·한글 자모 단독**(예 `silero`, ` 조용 `, `ㅋ`) → 저장 거부 또는 공백 제거 후 규칙 검사, 한국어 사유. (작업 8 `nameRules`)
5. **일시정지된 run 을 이어하는 사이 그 run 이 쓰던 USER 프리셋을 지움** → 이어하기가 기본값으로 대체하지 않고 "삭제된 USER 프리셋" 한국어 사유로 거부. (작업 9 `resumeWithDeletedUserPresetIsRejected`)

---

## 파일 구조

| 파일 | 책임 | 작업 |
|---|---|---|
| `runner/ResumeReconciler.kt`, `runner/ItemOutputs.kt`, `runner/CsvFiles.kt`, `output/RunManifest.kt` | 다시 실행 정리 순서·표식 | 1 |
| `model/ModelStore.kt` | 삭제 시 `tmp/<id>.dl` 정리, 시스템 엔진 READY | 1, 2 |
| `model/SystemEngineKind.kt`, `model/SystemCatalog.kt`, `model/ModelSpec.kt`, `model/ModelCatalog.kt` | 시스템 엔진 카탈로그 6항목(2엔진 × 배속 3) | 2 |
| `engine/system/PcmFrames.kt`, `PacedPcmFeeder.kt`, `RecognitionBackend.kt`, `SystemAsrEngine.kt`, `EngineStalledException.kt` | 세션 핵심(JVM) | 3 |
| `engine/AsrEngine.kt`, `output/DetailJson.kt` | `ChunkText.session`, `ChunkJson.session` | 3 |
| `runner/ItemProcessor.kt`, `runner/BatchRunner.kt`, `runner/RunEvent.kt`, `runner/RunUiState.kt`, `runner/RunCoordinator.kt`, `runner/RunService.kt`, `ui/run/RunProgressScreen.kt`, `ui/AppNav.kt` | 일시정지 경로 | 4 |
| `runner/BatchRunner.kt`, `runner/CsvRowBuilder.kt`, `runner/RunConfig.kt` | 언어 확인·CSV 매핑·덮어쓰기 범위 | 5 |
| `engine/system/MlKitBackend.kt`, `PlatformBackend.kt`, `SystemEngines.kt`, `engine/ManagedEngine.kt`, `App.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro` | Android 연결부 | 6 |
| `engine/system/LanguageStatus.kt`, `SystemEngineStatus.kt`, `ui/models/*` | 언어 상태·다운로드 화면 | 7 |
| `vad/PresetParamCodec.kt`, `vad/UserPresetStore.kt`, `vad/PresetRegistry.kt` | USER 프리셋 저장·검증 | 8 |
| `ui/run/*`, `ui/results/ResultsViewModel.kt`, `App.kt` | USER 프리셋 화면·이어하기 | 9 |
| `docs/*`, `README.md` | 문서·실기기 검증 | 10 |

(경로의 `runner/` 등은 `app/src/main/java/com/cowork/stt_ondevice/` 아래, 테스트는 `app/src/test/java/com/cowork/stt_ondevice/` 아래 같은 패키지.)

---

### 작업 1: 1차 보류 항목 정리

**파일:**
- 수정: `runner/ItemOutputs.kt`(`removeResults`), `runner/ResumeReconciler.kt`(`clearCrashed`), `runner/BatchRunner.kt`(rerunCrashed 분기), `output/RunManifest.kt`(필드 추가), `runner/CsvFiles.kt`(읽기 횟수 훅), `model/ModelStore.kt`(`delete`)
- 테스트: `runner/RunnerHelpersTest.kt`, `runner/BatchRunnerTest.kt`, `model/ModelInstallerTest.kt`

**인터페이스:**
- 만듦: `RunManifest.rerunCleanupPending: Boolean = false`(기본값 있어 기존 manifest 읽기 호환), `internal var CsvFiles.onFullRead: ((File) -> Unit)? = null`(테스트 전용 관찰 훅, 운영 코드는 null)

- [ ] **1-1 실패 테스트 작성** — `RunnerHelpersTest`:
  - `removeRowsLeavesFilesForRedo`: 크래시 항목 2개를 만든 뒤 `ItemOutputs.removeRows(targets)`(행만 지우는 새 내부 함수 — "행 정리 직후 프로세스가 죽은 상태"를 흉내냄)만 부르면 조합·요약 CSV 에 해당 행 0개, `ERROR.txt` 는 남아 `isCrashed == true`. 이어서 `removeResults(targets)` 를 다시 부르면 예외 없이 파일까지 지워짐(멱등).
  - `markManyCrashedItemsIsFastAndConsistent` 의 `assertTrue(sec < 8.0)` 를 지우고, `CsvFiles.onFullRead` 로 요약 CSV 전체 읽기 횟수를 세어 `assertTrue(reads <= 2)` 로 바꾼다(400 항목에서 항목마다 다시 읽으면 400 이상).
- [ ] **1-2 실패 테스트 작성** — `BatchRunnerTest.rerunCleanupInterruptedIsRedoneOnPlainResume`: 크래시 항목 1개가 있는 run 을 "다시 실행 정리 중 죽은 상태"로 만든다 — manifest `finishedAt = null`, `rerunCleanupPending = true`, 그 항목의 CSV 행은 이미 없고 `ERROR.txt` 는 남음. 이 run 을 **일반 이어하기**(`resume=true, rerunCrashed=false`)로 시작하면 정리가 먼저 다시 수행되어 그 항목이 다시 실행되고, 조합·요약 CSV 에 그 항목이 정확히 1행, 끝난 뒤 `rerunCleanupPending == false`.
- [ ] **1-3 실패 테스트 작성** — `ModelInstallerTest.deleteAlsoRemovesPartialDownload`: `dirs.tmp/<id>.dl/x.bin.part` 를 만들고 설치된 모델을 `store.delete(spec)` → `.dl` 폴더가 없어짐. 설치되지 않은 모델이라도 `.dl` 만 있으면 지움.
- [ ] **1-4 실행해 실패 확인:** `./gradlew :app:testDebugUnitTest --tests '*RunnerHelpersTest*' --tests '*BatchRunnerTest*' --tests '*ModelInstallerTest*'` → 새 테스트 FAIL.
- [ ] **1-5 구현:**
  - `ItemOutputs.removeResults(targets)` = `removeRows(targets)` → `removeFiles(targets)` 순서(둘 다 `internal`). `removeRows` 는 기존의 찢긴 줄 정리 + 조합·요약 행 삭제, `removeFiles` 는 txt·ERROR.txt·detail 삭제.
  - `BatchRunner` rerunCrashed 분기: `state.update { rerunCleanupPending = true }` → `clearCrashed` → `state.update { rerunCleanupPending = false }`. 또 시작 직후(inFlight 정리 다음) `state.manifest.rerunCleanupPending == true` 이면 `rerunCrashed` 여부와 상관없이 같은 정리를 다시 수행(멱등 — 행이 이미 없으면 지울 행 0, `ERROR.txt` 가 남은 항목만 대상). (기존 `openManifest` 가 이어하기 때 `finishedAt = null` 로 저장하므로, 정리 도중 죽은 run 은 일반 이어하기로 다시 열린다.)
  - `CsvFiles.readRecords`·`removeRowsWhere`·`truncatePartialLastLine` 이 파일 전체를 읽을 때 `onFullRead?.invoke(file)`.
  - `ModelStore.delete(spec)`: 기존 동작 뒤 `File(dirs.tmp, "${spec.id}.dl").deleteRecursively()`(사용 중이면 기존처럼 거부하고 아무것도 지우지 않음).
- [ ] **1-6 통과 확인:** 같은 명령 PASS, 이어서 전체 `./gradlew :app:testDebugUnitTest` PASS(기존 336개 포함).
- [ ] **1-7 커밋:** `git commit -m "1차 보류 정리: 다시 실행 정리 순서(행→파일)·중단 표식, 구조적 속도 테스트, 모델 삭제 시 받다 만 파일 삭제"`

---

### 작업 2: 시스템 엔진 카탈로그

**파일:**
- 생성: `model/SystemEngineKind.kt`, `model/SystemCatalog.kt`
- 수정: `model/ModelSpec.kt`(Runtime·StreamingKind·EngineConfig 추가), `model/ModelCatalog.kt`(목록 합치기), `model/ModelStore.kt`(`state`·`delete`), `ui/models/ModelsViewModel.kt`(팩토리에서 시스템 항목 제외 — 작업 7 이 전용 영역 추가)
- 테스트: `model/SystemCatalogTest.kt`, `model/ModelCatalogTest.kt`(개수 단언 갱신)

**인터페이스(만듦):**
```kotlin
enum class SystemEngineKind(val algo: String, val modelPrefix: String, val servicePackage: String, val serviceLabel: String, val backend: String) {
    MLKIT_BASIC("mlkit", "basic", "com.google.android.tts", "Speech Services by Google", "system(soda)"),
    PLATFORM_ONDEVICE("android-ondevice", "asi", "com.google.android.as", "Android System Intelligence", "system(asi)"),
}
// ModelSpec.kt
enum class Runtime { …기존…, MLKIT_GENAI, PLATFORM_ONDEVICE }
enum class StreamingKind { …기존…, PACED_REALTIME }   // 실시간 배속 공급(rtf 측정 안 함)
data class SystemEngineConfig(val kind: SystemEngineKind, val feedSpeed: Int) : EngineConfig
val ModelSpec.isSystem: Boolean   // engineConfig is SystemEngineConfig
object SystemCatalog { val FEED_SPEEDS = listOf(1, 2, 4); val all: List<ModelSpec> }
```
- `SystemCatalog.all` 값(엔진 × 배속 순, 6개): id `"${algo}-${prefix}-x$speed"`(예 `mlkit-basic-x1`, `android-ondevice-asi-x4`), model `"$prefix-x$speed"`, displayName `"ML Kit GenAI Basic (공급 ${speed}×)"` / `"Android 온디바이스 인식기 (공급 ${speed}×)"`, languages `{"ko","en"}`, runtime `MLKIT_GENAI`/`PLATFORM_ONDEVICE`, runtimeVersion `"1.0.0-alpha1"`/`"platform"`, precision `"system"`, maxChunkSec `30.0`, streaming `PACED_REALTIME`, downloads·files 빈 목록, originalRepo `https://developers.google.com/ml-kit/genai/speech-recognition/android` / `https://developer.android.com/reference/android/speech/SpeechRecognizer`, license `"Google 서비스 약관(ML Kit GenAI 추가 약관)"` / `"Google 서비스 약관(기기 음성 서비스)"`, licenseUrl `https://developers.google.com/ml-kit/terms` / `https://policies.google.com/terms`, commercial `CONDITIONAL`, compatAlias null.
- `ModelCatalog.all = ModelCatalogData.all + SystemCatalog.all`(생성 파일은 고치지 않음).
- `ModelStore.state(spec)`: `spec.isSystem` 이면 항상 `READY`. `ModelStore.delete(spec)`: 시스템이면 `false`(지울 것 없음).

- [ ] **2-1 실패 테스트:** `SystemCatalogTest` — `sixEntriesWithUniqueIds`(6개, id 중복 없음, 기존 카탈로그 id 와도 겹치지 않음), `comboNamesCarrySpeed`(`Combo(spec(x4), SILERO).name == "mlkit_basic-x4_SILERO"`), `storeReportsReadyWithoutFiles`(빈 앱 폴더에서 `state == READY`, `delete == false`), `modelsScreenExcludesSystem`(ModelsViewModel 팩토리 목록에 `isSystem` 없음 — 목록 생성 함수를 `ModelsViewModel.catalogForScreen(ModelCatalog.all)` 로 빼서 테스트).
- [ ] **2-2 실패 확인** → **2-3 구현** → **2-4 통과 확인**(`--tests '*SystemCatalogTest*' --tests '*ModelCatalogTest*'`, 이어서 전체).
- [ ] **2-5 커밋:** `"시스템 엔진 카탈로그: ML Kit Basic·Android 온디바이스 × 공급 1/2/4배속 6항목, 파일 없는 READY"`

---

### 작업 3: 시스템 엔진 세션 핵심 (PC 단위 테스트)

**파일:**
- 생성: `engine/system/PcmFrames.kt`, `engine/system/PacedPcmFeeder.kt`, `engine/system/RecognitionBackend.kt`, `engine/system/SystemAsrEngine.kt`, `engine/system/EngineStalledException.kt`
- 수정: `engine/AsrEngine.kt`(`SessionInfo`, `ChunkText.session`), `output/DetailJson.kt`(`SessionJson`, `ChunkJson.session`), `runner/ItemProcessor.kt`(`ChunkJson.from` 에 session 전달만)
- 테스트: `engine/system/PacedPcmFeederTest.kt`, `engine/system/SystemAsrEngineTest.kt`, `engine/system/FakeBackend.kt`(테스트용)

**인터페이스(만듦):**
```kotlin
object PcmFrames { fun toPcm16Le(samples: FloatArray): ByteArray }   // [-1,1] 자르기 × 32767, 리틀엔디언
data class FeedStats(val writtenBytes: Long, val maxBlockMs: Long, val completed: Boolean)
class PacedPcmFeeder(private val delayMs: suspend (Long) -> Unit = { delay(it) }, private val nanoTime: () -> Long = System::nanoTime) {
    suspend fun feed(out: OutputStream, pcm: ByteArray, speed: Int): FeedStats   // 블록 3200B, 절대 일정 start + i×(100/speed)ms, 쓰기 IOException → completed=false 로 반환(던지지 않음)
}
sealed interface SessionOutcome {
    data class Completed(val segments: List<String>) : SessionOutcome          // 음성 없음 = 빈 목록
    data class Failed(val code: String, val message: String) : SessionOutcome  // code: LANGUAGE_UNAVAILABLE | PERMISSION_MISSING | ENGINE_ERROR
}
interface RecognitionSession {
    val audio: OutputStream            // 공급 쪽(쓰기)
    suspend fun awaitOutcome(): SessionOutcome
    fun finishInput()                  // 공급 끝 알림(ML Kit stopRecognition / 플랫폼은 audio 닫기)
    fun abort()                        // 즉시 중단(멈춤·취소), 여러 번 불러도 안전
}
interface RecognitionBackend : AutoCloseable { suspend fun open(langTag: String): RecognitionSession }
class EngineStalledException(val kind: SystemEngineKind, message: String) : Exception(message)
data class SessionInfo(val number: Int, val fedBytes: Long, val maxBlockMs: Long, val retried: Boolean)
// ChunkText 에 마지막 인자 추가: val session: SessionInfo? = null   (기존 엔진 호출부는 그대로 컴파일)
// ChunkJson 에 마지막 인자 추가: val session: SessionJson? = null   (SessionJson 필드 = SessionInfo 와 동일, @Serializable)
class SystemAsrEngine(
    override val spec: ModelSpec,                       // engineConfig 는 SystemEngineConfig 여야 함(아니면 EngineException("MODEL_LOAD","CONFIG_MISMATCH"))
    private val backendFactory: () -> RecognitionBackend,
    private val versionInfo: () -> String,              // engine_version 문자열(작업 6 이 서비스 패키지 버전으로 채움)
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val feeder: PacedPcmFeeder = PacedPcmFeeder(),
    private val clock: () -> Long = System::nanoTime,
    private val closeTimeoutMs: Long = 5_000,
) : AsrEngine
```
**동작(테스트가 못 정하는 부분):**
- `prepare(threads)`: `backendFactory()` 로 만들고 `PrepareInfo(loadSec, versionInfo(), kind.backend, precision = "", threads = 0)`.
- `transcribe(chunk, lang)`: `attempt()` 1회 → 감시 시간 초과면 세션 `abort()`, 백엔드를 `closeTimeoutMs` 제한으로 닫고(넘으면 버림) 새로 만들어 같은 조각으로 1회 더 → 또 초과면 `EngineStalledException(kind, "<serviceLabel> 가 응답하지 않습니다(세션 n)")`.
- `attempt()`: 세션 열기 → `io` 에서 공급 코루틴 시작 → 공급이 끝나면(`completed`) `finishInput()` → `withTimeoutOrNull(감시)` 로 `awaitOutcome()` → 결과가 오면 **즉시 공급 코루틴 취소 + `audio` 닫기**(엔진이 먼저 끝난 경우 공급 막힘 방지).
- `Completed`: `text = segments.map(String::trim).filter(String::isNotEmpty).joinToString(" ")`, `rawText = segments.joinToString("\n")`, `inferSec` = 세션 시작~결과 수신 벽시계. 단 공급이 끝나기 전에 `Completed` 가 오면 `EngineException("TRANSCRIBE", "PIPE_NOT_CONSUMED", …)`(파이프 대신 마이크 등 다른 입력을 썼을 가능성).
- `Failed(code)`: `EngineException("TRANSCRIBE", code, message)`.
- 세션 번호는 엔진 인스턴스의 누적 순번(재시도 포함). `cancel()`: 현재 세션 `abort()` + 공급 중단.

- [ ] **3-1 실패 테스트** — `PacedPcmFeederTest`(`runTest` 가상 시간, `delayMs = { delay(it) }`):
  - `pacesAtSpeed`: 1 초(32,000 B) 를 speed 1 → 가상 경과 ≥ 900 ms, speed 4 → ≥ 225 ms 이고 < 300 ms, `writtenBytes == 32000`, `completed`.
  - `tinyChunkIsFed`: 800 B(0.025 초) → 블록 1개, `writtenBytes == 800`.
  - `writeFailureStopsWithoutThrow`: 두 번째 블록에서 IOException 던지는 OutputStream → `completed == false`, 예외 없음.
  - `toPcm16Clamps`: `[1.5f, -1.5f, 0.5f]` → 바이트 `FF 7F 01 80 FF 3F`(32767, −32767, 16383).
- [ ] **3-2 실패 테스트** — `SystemAsrEngineTest`(`FakeBackend` 는 세션마다 각본: 지정 시점에 Completed/Failed/응답 없음, 받은 바이트 기록):
  - `completedJoinsSegments`: `["안녕 ", "하세요"]` → text `"안녕 하세요"`, rawText `"안녕 \n하세요"`, session.number 1, fedBytes = 조각 바이트.
  - `noSpeechIsEmptyText`: `Completed(emptyList())` → text `""`, 예외 없음.
  - `engineErrorWhileFeedingClosesPipeAndMapsCode`: 10 초 조각 1× 공급 중 1 초 시점 `Failed("LANGUAGE_UNAVAILABLE")` → `EngineException.code == "LANGUAGE_UNAVAILABLE"`, 공급기 종료(가상 경과 < 2 초), `audio` 닫힘.
  - `completedBeforeFeedEndIsPipeNotConsumed`: 공급 절반에서 Completed(["x"]) → code `PIPE_NOT_CONSUMED`.
  - `stallRetriesOnceWithNewBackend`: 첫 세션 응답 없음, 두 번째 백엔드 세션 Completed → 결과 정상, `session.retried == true`, backendFactory 호출 2회(prepare 1 + 재생성 1), 첫 백엔드 close 호출됨.
  - `stallTwiceThrowsStalled`: 두 번 응답 없음 → `EngineStalledException`, 경과 시간 = 2 × (조각÷배속 + 15 초) ± 1 초.
  - `cancelDuringPacedFeedAbortsWithinOneSecond`: 30 초 조각 1× 공급 중 2 초 시점 Job 취소 → 가상 3 초 안에 `CancellationException`, 세션 `abort()` 호출됨.
  - `watchdogScalesWithSpeed`: 20 초 조각 4× → 감시 = 5,000 + 15,000 ms(응답 없음 시 첫 abort 시점으로 확인).
- [ ] **3-3 실패 확인:** `./gradlew :app:testDebugUnitTest --tests '*engine.system*'` → 컴파일 실패/FAIL.
- [ ] **3-4 구현** (위 인터페이스·동작대로). `ItemProcessor` 는 `ChunkJson.from(chunk, r.text, r.rawText, r.inferSec, r.session?.let(SessionJson::from))` 만 바꾼다.
- [ ] **3-5 통과 확인:** 같은 명령 PASS, 전체 단위 테스트 PASS.
- [ ] **3-6 커밋:** `"시스템 엔진 세션 핵심: 배속 공급기·감시 타이머·1회 재생성 재시도·멈춤 예외·세션 기록(PC 테스트)"`

---

### 작업 4: 멈춤 → 배치 일시정지 경로

**파일:**
- 수정: `runner/ItemProcessor.kt`, `runner/BatchRunner.kt`, `runner/RunEvent.kt`, `runner/RunUiState.kt`, `runner/RunCoordinator.kt`, `runner/RunService.kt`, `output/RunManifest.kt`, `ui/run/RunProgressScreen.kt`, `ui/AppNav.kt`
- 테스트: `runner/BatchRunnerTest.kt`, `runner/RunCoordinatorTest.kt`

**인터페이스:**
- 소비: `EngineStalledException(kind, message)`(작업 3), `SystemEngineKind.servicePackage/serviceLabel`(작업 2)
- 만듦: `RunEvent.Paused(val reason: String, val servicePackage: String, val serviceLabel: String)`, `RunUiState.Paused(val runId: String, val reason: String, val servicePackage: String, val serviceLabel: String)`, `RunManifest.pausedReason: String? = null`

**동작:**
- `ItemProcessor.process`: `catch (e: EngineStalledException) { throw e }` 를 `CancellationException` 다음, `Throwable` 앞에 둔다(항목 오류로 삼키지 않음, 샘플러는 기존 finally 로 정지).
- `BatchRunner` 항목 루프: `EngineStalledException` 을 잡으면 그 항목의 결과를 쓰지 않고 `inFlight = null`, `inFlightAttempts - key`, `pausedReason = message` 저장(finishedAt 은 null 유지), 로그 `RUN_PAUSED`, `emit(RunEvent.Paused(...))` 후 **정상 반환**(모델 엔진·VAD 는 기존 finally 로 닫힘). 이어하기로 다시 시작하면 `pausedReason = null` 로 지우고 그 항목부터 진행(이미 있는 `isDone` 판정 그대로).
- `RunCoordinator`: `Paused` 이벤트 → `terminal = RunUiState.Paused(...)`. 그 뒤 흐름이 끝나면 Paused 를 게시(Finished 로 덮지 않음 — BatchRunner 가 Paused 뒤 Finished 를 내지 않음).
- `RunService`: 결과 알림 제목 `"실행 일시정지"`, 본문 `"<serviceLabel> 가 응답하지 않습니다. 설정에서 강제 중지한 뒤 이어하기를 누르세요."`
- `RunProgressScreen` 을 상태를 받는 `RunProgressContent(state: RunUiState, onOpenResults: (String) -> Unit, onOpenSettings: (String) -> Unit)` 와 그것을 `RunService.events` 에 연결하는 껍데기로 나눈다(작업 10 의 화면 테스트용). Paused 이면 위 문구 + 단계 안내(① [설정 열기] → ② "강제 중지" → ③ 앱으로 돌아와 [이어하기]) + 버튼 [설정 열기](`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", servicePackage, null))`), [이어하기](`onOpenResults(runId)` — 결과 탭에서 그 run 을 펼쳐 기존 이어하기 버튼 사용).

- [ ] **4-1 실패 테스트** — `BatchRunnerTest`:
  - `stallPausesRunWithoutWritingItem`: 가짜 엔진이 3 항목 중 2번째에서 `EngineStalledException` → 이벤트 마지막이 `Paused`(Finished 없음), 1번째 항목만 결과 있음, manifest `inFlight == null`, `inFlightAttempts` 비어 있음, `finishedAt == null`, `pausedReason` 기록.
  - `resumeAfterPauseContinuesFromStalledItem`: 이어서 정상 엔진으로 `resume=true` → 2·3번째 항목만 실행, 조합 CSV 3행(중복 없음), `pausedReason == null`, CRASH_OR_KILLED 없음.
- [ ] **4-2 실패 테스트** — `RunCoordinatorTest.pausedEventPublishesPausedState`: 흐름이 `Progress, Paused` 를 내고 끝나면 최종 상태 `RunUiState.Paused`, `host.onRunEnded` 에 같은 값.
- [ ] **4-3 실패 확인** → **4-4 구현** → **4-5 통과 확인**(`--tests '*BatchRunnerTest*' --tests '*RunCoordinatorTest*'`, 이어서 전체) → `./gradlew :app:assembleDebug` 성공.
- [ ] **4-6 커밋:** `"엔진 멈춤 시 배치 일시정지: 항목 미기록·크래시 미집계, 강제 중지 안내·설정 열기·이어하기"`

---

### 작업 5: 언어 사용 가능 확인·CSV 매핑·덮어쓰기 범위

**파일:**
- 수정: `runner/BatchRunner.kt`, `output/RunManifest.kt`, `runner/CsvRowBuilder.kt`, `runner/ItemProcessor.kt`(rtf), `runner/RunConfig.kt`
- 테스트: `runner/BatchRunnerTest.kt`, `runner/RunnerHelpersTest.kt`(CSV 행), `runner/RunConfigTest` 가 없으면 `RunnerHelpersTest` 에 추가

**인터페이스:**
- `BatchRunner` 생성자 끝에 `private val availability: suspend (ModelSpec, String) -> String? = { _, _ -> null }` — 엔진 언어 코드(`ko`/`en`)를 받아 사용 가능하면 null, 아니면 한국어 사유(예 `"언어 미설치(en-US)"`). 작업 6 이 실제 확인 함수를 넣는다.
- `RunManifest.unavailableReasons: Map<String, String> = emptyMap()` — 키 `"combo|lang"`(기존 `unsupported` 항목과 같은 형식), 값 사유.

**동작:**
- 실행 시작 때(정리 이후) 모델 × 입력 언어마다 `availability` 를 1회 호출해 실효 언어 집합을 만든다. 실효 언어에 없는 조합은 실행·폴더 생성 없이 `unsupported` 에 `"combo|lang"` 추가 + `unavailableReasons` 에 사유, 화면 안내는 기존 unsupported 표시 그대로. 이어하기에서 이제 사용 가능해진 조합은 `unsupported`·`unavailableReasons` 에서 뺀다(카탈로그 언어 미지원 항목은 그대로 유지).
- `ItemProcessor`: `combo.spec.streaming == PACED_REALTIME` 이면 `rtf = null`.
- `CsvRowBuilder.build`: 시스템 엔진이면 `compute_type` 빈 값, `workers` 빈 값, `device` = backend 그대로(`system(soda)`/`system(asi)` — 소문자 변환 결과도 같음), `engine_version` = `detail.engine.runtimeVersion`. `note` 끝에 `;feed=x<배속>` 추가(시스템 엔진만, 기존 note 규칙 뒤에 붙임).
- `RunConfig.init`: 시스템 엔진의 `maxChunkOverride` 값은 `0 < v ≤ 60` 이어야 함(아니면 `IllegalArgumentException("시스템 엔진 최대 조각 길이는 0 초 초과 60 초 이하여야 합니다: <id>=<v>")`). 실행 화면 입력 칸은 이 예외 문구를 그대로 오류로 보여 준다(기존 `MaxChunkInput.error` 경로).

- [ ] **5-1 실패 테스트:**
  - `BatchRunnerTest.unavailableLanguageIsSkippedAndRecorded`: `availability` 가 `en` 에 `"언어 미설치(en-US)"` → en 항목 실행 안 됨, en 결과 폴더 없음, `unsupported` 에 `"<combo>|en"`, `unavailableReasons` 값 일치, ko 항목은 정상.
  - `BatchRunnerTest.resumeDropsNowAvailableLanguage`: 위 run 을 미완료로 둔 뒤 availability 가 모두 null 로 이어하기 → en 항목 실행, `unavailableReasons` 비어 있음.
  - `RunnerHelpersTest.systemEngineCsvRow`: 시스템 spec(x4) 상세(transcribeSec 8.0, audioSec 32.0, engine meta `("mlkit-genai-sr 1.0.0-alpha1 / com.google.android.tts 1.2.3", "system(soda)", "", 0)`) → `rtf == ""`, `device == "system(soda)"`, `compute_type == ""`, `workers == ""`, `engine_version` 일치, `note` 가 `";feed=x4"` 로 끝남, `transcribe_sec == "8.000"`.
  - `RunnerHelpersTest.systemOverrideRange`: 시스템 spec 덮어쓰기 0.0·61.0 → 예외(문구 포함), 60.0 → 허용. 일반 spec 0.0 → 허용(기존 동작).
- [ ] **5-2 실패 확인** → **5-3 구현** → **5-4 통과 확인**(관련 테스트, 이어서 전체).
- [ ] **5-5 커밋:** `"시스템 엔진 실행 통합: 실행 전 언어 사용 가능 확인·사유 기록, rtf 빈 값·device·feed 표기, 최대 조각 덮어쓰기 범위"`

---

### 작업 6: Android 연결부(ML Kit·플랫폼 백엔드)와 실기기 계측

**파일:**
- 생성: `engine/system/MlKitBackend.kt`, `engine/system/PlatformBackend.kt`, `engine/system/SystemEngines.kt`
- 수정: `engine/ManagedEngine.kt`(`factory` 의 `create` 기본값), `App.kt`(`newBatchRunner` 에 availability·시스템 생성기), `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`(필요 시)
- 테스트: `app/src/androidTest/java/com/cowork/stt_ondevice/engine/system/SystemEngineDeviceTest.kt`(계측), `engine/EngineFactoryTest.kt`(시스템 spec 은 EngineFactory 가 아니라 SystemEngines 로 감)

**인터페이스:**
- 소비: `RecognitionBackend`/`RecognitionSession`/`SessionOutcome`(작업 3), `SystemAsrEngine`(작업 3), `BatchRunner.availability`(작업 5)
- 만듦:
```kotlin
object SystemEngines {
    fun create(context: Context, spec: ModelSpec): AsrEngine                 // SystemAsrEngine(spec, { backendFor(context, kind) }, { versionInfo(context, kind) })
    suspend fun unavailableReason(context: Context, spec: ModelSpec, engineLang: String): String?  // 작업 7 의 SystemEngineStatus 사용 — 이 작업에서는 ML Kit checkStatus / 플랫폼 checkRecognitionSupport 직접 호출로 먼저 구현하고 작업 7 에서 그 클래스로 옮긴다
    fun versionInfo(context: Context, kind: SystemEngineKind): String       // ML Kit: "mlkit-genai-sr 1.0.0-alpha1 / com.google.android.tts <versionName>", 플랫폼: "com.google.android.as <versionName>", 패키지 없음 → "<패키지> 없음"
}
// ManagedEngine.factory 에 세 번째 인자 추가:
fun factory(store: ModelStore, create: (ModelSpec, File) -> AsrEngine = EngineFactory::create,
            systemCreate: (ModelSpec) -> AsrEngine = { throw EngineException("MODEL_LOAD", "NOT_IMPLEMENTED", "시스템 엔진 생성기가 없음: ${it.id}") }): (ModelSpec) -> AsrEngine
// 본문: acquire → READY 확인(시스템은 항상 READY) → if (spec.isSystem) systemCreate(spec) else create(spec, store.modelDir(spec)) → ManagedEngine 래핑(기존과 동일)
```
**동작(실험에서 확정, spike-report 참조):**
- `MlKitBackend`: `SpeechRecognition.getClient(speechRecognizerOptions { locale = Locale.forLanguageTag(tag); preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC })` — 언어별 클라이언트를 백엔드 안에 1개씩 캐시. `open()` 은 `ParcelFileDescriptor.createPipe()` → `startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromPfd(read) })` Flow 를 백엔드 소유 범위에서 수집: `FinalTextResponse.text` 누적, `CompletedResponse` → `Completed`, `ErrorResponse`/Flow 예외 → `Failed`(GenAiException 코드가 INVALID_REQUEST 이고 `checkStatus() != AVAILABLE` 이면 `LANGUAGE_UNAVAILABLE`, 아니면 `ENGINE_ERROR`, 메시지에 원래 코드 보존). Flow 가 어떤 이유로든 끝나면 즉시 write 쪽 닫기. `finishInput()` = write 닫기 + `stopRecognition()`. `abort()` = 수집 Job 취소 + 양쪽 닫기 + `stopRecognition()`(예외 무시). `close()` = 클라이언트 `close()`.
- `PlatformBackend`: 모든 SpeechRecognizer 호출은 `Handler(Looper.getMainLooper())`/`withContext(Dispatchers.Main)` 에서. `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)`, 인텐트 엑스트라: `EXTRA_LANGUAGE_MODEL=FREE_FORM`, `EXTRA_LANGUAGE=tag`, `EXTRA_PREFER_OFFLINE=true`, `EXTRA_AUDIO_SOURCE=read`, `EXTRA_AUDIO_SOURCE_ENCODING=ENCODING_PCM_16BIT`, `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT=1`, `EXTRA_AUDIO_SOURCE_SAMPLING_RATE=16000`, `EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE`, `EXTRA_ENABLE_FORMATTING=FORMATTING_OPTIMIZE_QUALITY`. `onSegmentResults` 의 첫 후보 누적, `onEndOfSegmentedSession` → `Completed`, `onError`: `ERROR_LANGUAGE_UNAVAILABLE`/`ERROR_LANGUAGE_NOT_SUPPORTED` → `LANGUAGE_UNAVAILABLE`, `ERROR_NO_MATCH`/`ERROR_SPEECH_TIMEOUT` → `Completed(emptyList())`, `ERROR_INSUFFICIENT_PERMISSIONS` → `PERMISSION_MISSING`, 그 밖 → `ENGINE_ERROR`(`"onError=<n>"`). `finishInput()` = write 닫기(EOF 가 세션 끝 신호). `abort()` = `cancel()`. `close()` = `destroy()`. API 33 미만이면 `open()` 이 `Failed("LANGUAGE_UNAVAILABLE", "Android 13 이상 필요")`. RECORD_AUDIO 미허용이면 `PERMISSION_MISSING`.
- `App.newBatchRunner()`: `engineFactory = ManagedEngine.factory(modelStore, systemCreate = { SystemEngines.create(this, it) })`, `availability = { spec, lang -> if (spec.isSystem) SystemEngines.unavailableReason(this, spec, lang) else null }`.
- 의존성: `libs.versions.toml` 에 `mlkitGenaiSpeech = "1.0.0-alpha1"`, 라이브러리 `mlkit-genai-speech = { group = "com.google.mlkit", name = "genai-speech-recognition", version.ref = "mlkitGenaiSpeech" }`, `build.gradle.kts` 에 `implementation(libs.mlkit.genai.speech)`.

- [ ] **6-1 실패 테스트** — `EngineFactoryTest.systemSpecGoesToSystemCreator`: `ManagedEngine.factory(store, systemCreate = { fake })` 로 시스템 spec 생성 시 fake 가 반환되고 `EngineFactory.create` 는 호출되지 않음, close 시 release 됨.
- [ ] **6-2 계측 테스트 작성** — `SystemEngineDeviceTest`(입력: 기기 `/sdcard/Documents/input_sound/kr/Customer_Service/01.wav` 를 앱 내부 입력 폴더 사본에서 읽음 — 테스트는 앱 데이터의 `input_sound` 경로를 `App.from(ctx).dirs.inputSound` 로 찾고, 없으면 `assumeTrue` 로 건너뜀(실패로 가장하지 않음)):
  - `mlkitKoreanX1AndX4Match`: 첫 25 초를 조각 1개로 `mlkit-basic-x1`·`x4` 각각 전사 → 두 텍스트 비어 있지 않고 서로 같음, x4 wall < x1 wall ÷ 2.
  - `platformKoreanX4`: `android-ondevice-asi-x4` 전사 → 비어 있지 않음, 문장부호 포함 여부를 로그로 남김(단언하지 않음).
  - `englishUnavailableIsReported`: `unavailableReason(ctx, spec, "en")` 가 en-US 미설치면 null 이 아닌 사유(설치돼 있으면 null 이고 이 단언은 건너뜀).
- [ ] **6-3 실패 확인:** 단위 테스트 FAIL.
- [ ] **6-4 구현.**
- [ ] **6-5 통과 확인:** `./gradlew :app:testDebugUnitTest` PASS, `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease` 성공, `tools/check_16kb.sh` 통과, 병합 매니페스트에서 ML Kit 이 추가한 권한·서비스를 `docs/테스트보고서_phase2.md` 초안에 기록.
- [ ] **6-6 실기기 확인(S22):** `adb install -r app-debug.apk && adb install -r app-debug-androidTest.apk && adb shell am instrument -w -e class com.cowork.stt_ondevice.engine.system.SystemEngineDeviceTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner` → `OK`. 결과(텍스트·시간)를 보고서 초안에 기록. release APK 도 `install -r` 후 앱 실행·모델 화면 진입만 확인(시작 크래시 없음).
- [ ] **6-7 커밋:** `"시스템 엔진 Android 연결부: ML Kit Basic(SODA)·플랫폼 온디바이스(ASI) 파이프 입력 백엔드, 실행 전 언어 확인 연결, 실기기 계측"`

---

### 작업 7: 모델 화면 — 언어별 상태와 다운로드

**파일:**
- 생성: `engine/system/LanguageStatus.kt`, `engine/system/SystemEngineStatus.kt`
- 수정: `engine/system/SystemEngines.kt`(`unavailableReason` 이 `SystemEngineStatus` 사용), `ui/models/ModelsViewModel.kt`, `ui/models/ModelsScreen.kt`
- 테스트: `ui/models/SystemEnginesSectionTest.kt`

**인터페이스(만듦):**
```kotlin
sealed interface LanguageStatus {
    data object Available : LanguageStatus
    data object Downloadable : LanguageStatus
    data object Downloading : LanguageStatus
    data class Unavailable(val reason: String) : LanguageStatus
}
sealed interface DownloadEvent { data class Progress(val bytes: Long, val total: Long?) : DownloadEvent; data object Done : DownloadEvent; data class Failed(val message: String) : DownloadEvent }
interface SystemEngineStatus {
    suspend fun status(kind: SystemEngineKind, engineLang: String): LanguageStatus
    fun canDownload(kind: SystemEngineKind): Boolean            // 플랫폼은 API 34 이상만
    fun download(kind: SystemEngineKind, engineLang: String): Flow<DownloadEvent>
}
class AndroidSystemEngineStatus(context: Context) : SystemEngineStatus
data class SystemEngineRow(val kind: SystemEngineKind, val title: String, val license: String, val langs: List<Pair<String, LanguageStatus>>, val busyLang: String?)
// ModelsViewModel 생성자 끝에 systemStatus: SystemEngineStatus? = null 추가(null 이면 영역 숨김), val systemRows: StateFlow<List<SystemEngineRow>>, fun refreshSystem(), fun downloadSystem(kind, engineLang)
```
**동작:**
- ML Kit: `checkStatus()` → `FeatureStatus.AVAILABLE/DOWNLOADABLE/DOWNLOADING/UNAVAILABLE` 매핑(UNAVAILABLE 사유 `"이 기기에서 지원하지 않음"`), 15 초 안에 응답 없으면 `Unavailable("상태 확인 시간 초과")`. `download()` 의 `DownloadStarted/Progress/Completed/Failed` → `DownloadEvent`.
- 플랫폼: API 33 미만 `Unavailable("Android 13 이상 필요")`. `checkRecognitionSupport` 의 `installedOnDeviceLanguages` 포함 → Available, `pendingOnDeviceLanguages` → Downloading, `supportedOnDeviceLanguages` → Downloadable, 그 밖 → `Unavailable("기기 인식기가 이 언어를 지원하지 않음")`. 다운로드는 API 34 이상 `triggerModelDownload(intent, executor, listener)`.
- 화면: 모델 목록 맨 위 "시스템 엔진" 카드 2개 — 제목, `"Google 서비스 약관 적용"`, 언어 줄마다 상태(사용 가능 / 다운로드 필요 / 다운로드 중 / 사용 불가(사유)) 와 [다운로드](Downloadable 이고 `canDownload`), [새로고침]. 다운로드 시작 시 안내 `"Google 동의 창이 뜨면 직접 확인을 눌러 주세요(앱이 대신 누르지 않습니다)."` 삭제·가져오기·검증 버튼 없음. 다운로드 끝나면 그 언어 상태를 다시 확인.

- [ ] **7-1 실패 테스트** — `SystemEnginesSectionTest`(가짜 `SystemEngineStatus`):
  - `rowsShowPerLanguageStatus`: ML Kit ko=Available, en=Downloadable / 플랫폼 ko=Available, en=Unavailable("…") → `systemRows` 2행, 언어 순서 ko·en, 상태 일치.
  - `downloadRefreshesStatusAfterDone`: `downloadSystem(MLKIT_BASIC, "en")` → 진행 중 `busyLang == "en"`, `Done` 뒤 상태 재조회로 Available, `busyLang == null`, 안내 메시지 1회.
  - `downloadFailureShowsKoreanMessage`: `Failed("x")` → 메시지에 `"다운로드 실패"` 포함, 상태 재조회.
  - `statusTimeoutIsUnavailable`: 상태 함수가 끝나지 않으면(가상 시간 15 초 초과) `Unavailable("상태 확인 시간 초과")`(시간 제한은 ViewModel 이 아니라 `AndroidSystemEngineStatus` 에 있으므로 이 테스트는 시간 제한 래퍼 `withStatusTimeout` 을 별도 함수로 빼서 검사).
- [ ] **7-2 실패 확인** → **7-3 구현** → **7-4 통과 확인**(관련, 전체) → `assembleDebug`.
- [ ] **7-5 실기기 확인(S22):** 모델 탭에서 ML Kit ko 사용 가능 / en 다운로드 필요, 플랫폼 ko 사용 가능 / en 상태 표시 확인(스크린샷을 보고서용으로 `scratchpad` 에 저장, 커밋하지 않음). [다운로드]는 누르지 않는다 — 작업 10 에서 사용자가 직접 승인.
- [ ] **7-6 커밋:** `"모델 화면 시스템 엔진 영역: 언어별 상태·공식 다운로드 요청(동의는 사용자), 실행 전 확인을 같은 상태 조회로 통일"`

---

### 작업 8: USER VAD 프리셋 저장·검증

**파일:**
- 생성: `vad/PresetParamCodec.kt`, `vad/UserPresetStore.kt`, `vad/PresetRegistry.kt`
- 테스트: `vad/PresetParamCodecTest.kt`, `vad/UserPresetStoreTest.kt`

**인터페이스(만듦):**
```kotlin
object PresetParamCodec {
    data class Field(val key: String, val kind: Kind, val min: Double?, val max: Double?, val choices: List<String> = emptyList())  // Kind = INT, FLOAT, DOUBLE, BOOL, ENUM
    fun fields(params: VadParams): List<Field>                  // 계열 타입별 편집 필드(키 이름 = describeParams 키)
    fun toMap(params: VadParams): Map<String, String>           // describeParams 와 같은 키·숫자 표기
    fun fromMap(template: VadParams, values: Map<String, String>): Result<VadParams>   // 실패 사유는 한국어 "<키>: 0~1 사이여야 합니다" 형식
}
@Serializable data class UserPresetJson(val id: String, val family: String, val baseId: String, val values: Map<String, String>, val createdAt: String)
class UserPresetStore(private val file: File, private val builtIns: () -> List<VadPreset> = { VadPresets.all }, private val now: () -> String) {
    fun list(): List<VadPreset>                                  // 파일 없음 → 빈 목록, 손상 → 빈 목록 + lastError 설정(덮어쓰지 않음)
    fun create(baseId: String, name: String, values: Map<String, String>): Result<VadPreset>
    fun delete(id: String): Boolean
    val lastError: String?
}
class PresetRegistry(private val store: UserPresetStore) { fun all(): List<VadPreset> /* VadPresets.all + store.list() */; fun byId(id: String): VadPreset? }
```
**규칙:**
- 이름: 앞뒤 공백 제거 후 `^[가-힣A-Za-z0-9-]{1,32}$`(한글 완성형만 — 자모 단독 `ㅋ` 거부). id = `"USER_" + 이름`. 기본 프리셋 id·기존 USER id 와 대소문자 무시 비교로 중복 금지. 사유 문구: `"이름은 한글·영문·숫자·'-' 1~32자만 쓸 수 있습니다"`, `"이미 있는 이름입니다: <id>"`.
- 기준 프리셋이 `NONE` 계열이면 거부(`"VAD 없음(NONE)은 바꿀 값이 없어 복사할 수 없습니다"`). 나머지 8 계열은 모두 허용(TEN 은 기준 프리셋의 파라미터 타입 — Hangover 또는 Silero — 를 따른다).
- 범위: 확률·임계값 0~1, ms·프레임·초 ≥ 0, WebRTC `mode` 0~3, `frameMs` ∈ {10,20,30}, FireRed `mode` ∈ {OFFLINE,STREAM}, `maxSpeechSec` 는 양수 또는 `inf`, Silero/TEN-Silero 에서 `threshold - 0.15` 가 음수가 되는 값(0.15 미만)도 허용(원본 동작 그대로). 정수 필드에 소수 입력은 거부.
- 저장 파일: `AppDirs` 의 앱 files 폴더 `user_presets.json`, `AtomicFile` 로 원자 저장, `AppJson` 직렬화. 저장된 항목은 바꾸지 않는다(수정 API 없음). 프리셋 `source = "USER(기준 <baseId>)"`, `note = ""`.

- [ ] **8-1 실패 테스트** — `PresetParamCodecTest`:
  - `roundTripsAllBuiltInPresets`: `VadPresets.all` 중 NONE 제외 전부에 대해 `fromMap(p.params, toMap(p.params)) == p.params` 이고 `PresetEntry.describeParams(결과) == describeParams(원본)`.
  - `rejectsOutOfRange`: Silero threshold `"1.2"` → 실패, 사유에 `"threshold"` 포함. WebRTC frameMs `"25"` → 실패. FSMN `maxEndSilenceTime` `"80.5"` → 실패.
- [ ] **8-2 실패 테스트** — `UserPresetStoreTest`:
  - `nameRules`: `"조용한방"` 성공(id `USER_조용한방`), `" 조용 "` → id `USER_조용`, `"ㅋ"`·`"a b"`·`"x".repeat(33)`·`""` 실패, 기본 id 와 겹치는 이름이 생기지 않음(`USER_` 접두로 불가능함을 확인), 같은 이름 두 번째·대소문자만 다른 `"Quiet"`/`"quiet"` 두 번째 실패.
  - `persistsAcrossInstances`: 만든 뒤 새 인스턴스 `list()` 에 같은 프리셋(params 동일).
  - `corruptFileIsNotOverwritten`: 파일에 `"{깨짐"` → `list()` 빈 목록, `lastError` 설정, `create` 는 실패(사유 `"user_presets.json 을 읽을 수 없어 저장하지 않습니다"`), 파일 내용 그대로.
  - `noneBaseRejected`, `deleteRemoves`.
- [ ] **8-3 실패 확인** → **8-4 구현** → **8-5 통과 확인**(`--tests '*PresetParamCodecTest*' --tests '*UserPresetStoreTest*'`, 이어서 전체).
- [ ] **8-6 커밋:** `"USER VAD 프리셋 저장소: 기존 프리셋 복사·값 검증·이름 규칙·불변 저장, 기본+USER 합본 조회"`

---

### 작업 9: USER 프리셋 화면·실행·이어하기 연결

**파일:**
- 수정: `App.kt`(`userPresetStore`, `presetRegistry` 싱글턴), `ui/run/RunSetupViewModel.kt`, `ui/run/RunSetupScreen.kt`, `ui/results/ResultsViewModel.kt`(팩토리가 `presetRegistry.all()` 사용), 필요 시 `ui/run/UserPresetDialog.kt`(생성)
- 테스트: `ui/run/RunSetupUserPresetTest.kt`, `ui/results/ResultsViewModelTest.kt`

**인터페이스:**
- 소비: `PresetRegistry.all()`, `UserPresetStore.create/delete`, `PresetParamCodec.fields/toMap`(작업 8)
- 만듦: `RunSetupViewModel` 생성자 인자 `presets: () -> List<VadPreset>`(기존 `List` 를 공급 함수로 — `refresh()` 마다 다시 읽음), `userPresets: UserPresetStore?`, `unfinishedRunsUsing: (String) -> List<String> = { emptyList() }`(그 프리셋을 쓰는 미완료 runId 목록), 함수 `startUserPreset(baseId)`, `setUserField(key, text)`, `setUserName(text)`, `saveUserPreset()`, `requestDeleteUserPreset(id)`, `confirmDeleteUserPreset(id)`; 상태 `userDraft: UserDraft?`(기준 id, 필드 목록과 현재 문자열, 필드별 오류, 이름 오류), `deleteWarning: String?`.

**동작:**
- 프리셋 목록의 USER 항목은 기본 목록 뒤 별도 소제목 "사용자 프리셋"으로 표시, 각 행에 [삭제].
- [복사해서 만들기] → 기준 프리셋 선택(NONE 제외) → 필드 폼(기준 값으로 채움, 범위 안내) → 이름 → [저장]. 저장 성공 시 목록에 나타나고 자동 선택. 오류는 필드 아래 한국어로.
- 삭제: `unfinishedRunsUsing(id)` 가 비어 있지 않으면 경고 `"미완료 실행(<runId 목록>)이 이 프리셋을 씁니다. 지우면 그 실행은 이어할 수 없습니다. 지울까요?"` 후 확인 시 삭제. `unfinishedRunsUsing` 은 앱에서 `output_stt/*/run_manifest.json` 중 `finishedAt == null` 이고 `presets` 에 그 id 가 있는 run.
- 이어하기: `ResultsViewModel` 이 프리셋을 `presetRegistry.all()` 에서 찾고, USER 프리셋이 없으면 `ResumePlan.Error("삭제된 USER 프리셋입니다: <id> — 이 실행은 이어할 수 없습니다")`. 있으면 기존 params 동일성 검사 그대로.

- [ ] **9-1 실패 테스트:**
  - `RunSetupUserPresetTest.createFromSileroAndSelect`: 임시 폴더 저장소 + 기준 `SILERO` → 필드 기본값이 `toMap(SILERO.params)` 와 같음, threshold `"0.35"`·이름 `"조용한방"` 저장 → 상태 프리셋 목록에 `USER_조용한방`, 선택됨, 조합 수 갱신.
  - `RunSetupUserPresetTest.fieldErrorBlocksSave`: threshold `"2"` → 저장 안 됨, 필드 오류 문구.
  - `RunSetupUserPresetTest.deleteWarnsWhenUnfinishedRunUsesIt`: `unfinishedRunsUsing` 이 `["20261011_120000"]` → `requestDelete` 후 `deleteWarning` 에 runId 포함, `confirmDelete` 후 목록에서 사라짐.
  - `ResultsViewModelTest.resumeWithDeletedUserPresetIsRejected`: manifest 프리셋 `USER_x`, 레지스트리에 없음 → 이어하기 결과 메시지가 위 문구, `startRun` 호출 안 됨.
  - `ResultsViewModelTest.resumeWithUserPresetStarts`: 레지스트리에 있고 params 같음 → `startRun` 의 설정 presets 에 그 USER 프리셋.
- [ ] **9-2 실패 확인** → **9-3 구현** → **9-4 통과 확인**(관련, 전체) → `assembleDebug`.
- [ ] **9-5 커밋:** `"USER VAD 프리셋 화면: 복사해서 만들기·값 편집·삭제 경고, 실행·이어하기에서 기본+USER 합본 사용"`

---

### 작업 10: 실기기 검증·문서

**파일:**
- 생성: `docs/테스트보고서_phase2.md`
- 수정: `docs/사용설명서.md`, `README.md`, `docs/superpowers/specs/2026-10-09-stt-ondevice-phase1-design.md`(§7.3 의 "Phase 2 로 연기" 문구를 P2 spec §5 참조로)

- [ ] **10-1 빌드·정적 확인:** `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` 성공, `tools/check_16kb.sh` 통과. 결과 수치(테스트 수)를 보고서에 기록.
- [ ] **10-2 사용자 승인 요청(멈춤 지점):** 사용자에게 한국어로 "모델 탭 → ML Kit 영어 [다운로드] → 폰에 뜨는 Google 동의 창(약 62 MB)을 직접 확인" 을 요청하고, 플랫폼 영어도 [다운로드] 가 보이면 같은 방법으로 요청한다. 사용자가 거절·보류하면 영어 시스템 엔진 항목은 "미수행(사용자 미승인)" 으로 기록하고 한국어만 진행.
- [ ] **10-3 실기기 매트릭스(S22, 화면 켬):** 입력 KR `Customer_Service/01.wav`·`05.wav`, EN `Customer_Service/01.wav` × 모델 {`mlkit-basic-x1`, `mlkit-basic-x4`, `android-ondevice-asi-x1`, `android-ondevice-asi-x4`} × 프리셋 {`NONE`, `SILERO_DEFAULT`}. 앱 UI 로 실행, `tools/adb/pull_results.sh` 로 가져와 x1·x4 전사 일치 여부, transcribe_sec, unsupported/사유를 보고서 표로.
- [ ] **10-4 화면 끔 실행 1회:** KR 05 × `mlkit-basic-x1` × `SILERO_DEFAULT` 를 시작 후 화면 끔 → 완료, 결과 정상.
- [ ] **10-5 USER 프리셋·이어하기:** 앱에서 `USER_조용한방`(기준 SILERO, threshold 0.35) 생성 → KR 01·05 × `mlkit-basic-x4` 실행 → 진행 중 취소 → 결과 탭 이어하기 → 결과 2개·CSV 2행 확인.
- [ ] **10-6 일시정지 UI:** 실제 멈춤은 일부러 만들 수 없으므로 계측 화면 테스트 `PausedUiDeviceTest`(androidTest 에 BOM 관리 `androidx.compose.ui:ui-test-junit4`, debug 에 `ui-test-manifest` 추가)로 `RunProgressContent(RunUiState.Paused(runId, "Speech Services by Google 가 응답하지 않습니다(세션 3)", "com.google.android.tts", "Speech Services by Google"))` 를 그려 안내 문구·[설정 열기]·[이어하기]가 보이고, [설정 열기] 클릭 시 `onOpenSettings("com.google.android.tts")` 가 불리는지 확인. `am instrument` 로 실행. 실제 멈춤이 검증 중 발생하지 않았다면 보고서에 그대로 기록.
- [ ] **10-7 PC 채점:** `~/workspace/python/asr_stt/stt_all_gui_2nd/run_eval.py` 를 venv_eval 로 실행(읽기 전용, 고치지 않음) → `by_model.csv` 의 시스템 엔진 행을 보고서에 "간이(smoke) 결과" 로 기록.
- [ ] **10-8 문서:** 사용설명서에 "시스템 엔진"(언어 상태·다운로드 동의·배속·일시정지 시 강제 중지 방법·RECORD_AUDIO 권한 필요 이유), "USER 프리셋"(만들기·이름 규칙·삭제 경고·NONE 불가), 결과 CSV 차이(rtf 빈 값, device `system(...)`, note `feed=xN`) 추가. README 기능 목록 갱신. 보고서에 수행·미수행 항목과 이유.
- [ ] **10-9 커밋:** `"문서: 2차 사용설명서·테스트 보고서(실기기 S22 시스템 엔진 매트릭스·화면 끔·USER 프리셋·이어하기·PC 채점)"`

---

## 자체 점검 결과

- **설계서 대응:** §1 성공기준 1→작업 2·5·6·10, 2→작업 5·7, 3→작업 3·4, 4→작업 8·9, 5→작업 5, 6→작업 10. §4.1→작업 3·6, §4.2→작업 3·4, §4.3→작업 2·7, §4.4→작업 2·5, §5→작업 8·9, §6→작업 1, §7→작업 3·4·5, §8→각 작업 테스트·작업 10.
- **설계서와 다르게 정한 점(구현 결정):** 공급 배속은 실행 탭의 별도 선택 칸 대신 **카탈로그 항목 3개(×1/×2/×4)** 로 두어 한 run 에서 여러 배속을 함께 비교한다(설계서 §4.4 의 조합 이름 규칙과 동일한 결과). USER 프리셋은 NONE 계열을 제외한 8 계열(NONE 은 바꿀 값이 없음). 일시정지 화면의 [이어하기]는 결과 탭의 기존 이어하기 버튼으로 이동한다.
