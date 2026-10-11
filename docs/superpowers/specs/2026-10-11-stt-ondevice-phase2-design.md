# STT_OnDevice Phase 2 설계서 — 시스템 엔진(ML Kit GenAI Basic · Android 온디바이스 인식기) · USER VAD 프리셋 · 1차 보류 정리

- 작성: 2026-10-11 (Asia/Seoul), 작성자 hyungchul + Claude
- 상태: 사용자 설계 승인 완료(인터뷰 3회 + 실험), 본 문서 검토 대기
- 기반: Phase 1 설계서 `docs/superpowers/specs/2026-10-09-stt-ondevice-phase1-design.md`(이하 P1 spec), main = 3ba5cd2
- 근거 자료: `.superpowers/sdd/phase2-notes/spike-report.md`(S22 Ultra 실측, 2026-10-11), `.superpowers/sdd/phase2-notes/interview.md`, 사용자 기존 구현 `/home/hyungchul/AndroidStudioProjects/google_stt/app/src/main/java/com/example/google_stt/stt/`(MlKitTranscriber, PlatformOnDeviceTranscriber, PcmFeeder, GoogleSttSession, SttConstants)

## 1. 목적과 성공 기준

**목적**: 1차 앱에 **모델 파일이 없는 시스템 엔진 2종**을 추가해 기존 엔진들과 **같은 VAD 조각**으로 공정하게 비교하고, 사용자가 **VAD 파라미터를 직접 정한 프리셋**으로 실험할 수 있게 하며, 1차 최종 리뷰에서 보류한 항목을 정리한다.

**성공 기준**
1. 실행 탭에서 ML Kit GenAI Basic, Android 온디바이스 인식기를 다른 엔진처럼 선택해 VAD 프리셋 × 파일로 배치 실행되고, 결과가 PC 평가기에서 `<algo>_<model>_<VAD>` 로 페어링된다.
2. 두 엔진의 언어별 상태(사용 가능/다운로드 필요/다운로드 중/사용 불가)가 모델 화면에 보이고, 사용 불가 언어는 실행 전에 걸러져 이유가 기록된다(조용한 대체 없음).
3. 엔진이 응답하지 않으면(멈춤) 감시 타이머가 잡아 1회 재시도하고, 그래도 안 되면 배치를 멈춘 뒤 한국어 복구 안내(설정 열기)와 이어하기로 계속할 수 있다. 결과가 영구 오류로 남지 않는다.
4. 사용자가 기존 프리셋을 복사해 이름 붙인 USER 프리셋을 저장·선택·실행할 수 있고, 이어하기와 호환된다.
5. 측정 불가 값은 빈 값(null). 특히 실시간 공급 엔진의 `rtf` 는 빈 값.
6. S22 Ultra 에서 4절(검증) 항목이 실제로 수행되고 결과가 문서에 정직하게 기록된다.

**하지 않는 것**: ML Kit Advanced(Pixel 전용, S22 에 AICore 없음), Moonshine, 실시간 partial 스트리밍 화면, S25 Ultra 실측(사용자가 사용설명서 절차로 나중에), 시스템 설정 자동 변경(기본 인식기·언어팩 설치는 사용자가 직접).

## 2. 실험으로 확정된 사실 (S22 Ultra, 2026-10-11, spike-report)

| 항목 | 사실 | 설계 반영 |
|---|---|---|
| ML Kit Basic 실행 위치 | `com.google.android.tts` 안 SODA | 멈춤 안내 대상 = Speech Services by Google |
| 플랫폼 온디바이스 실행 위치 | `com.google.android.as`(Android System Intelligence) | 멈춤 안내 대상 = Android System Intelligence |
| ko-KR | ML Kit AVAILABLE, 플랫폼 installed | 바로 사용 |
| en-US | ML Kit DOWNLOADABLE(62 MB, **Speech Services 동의 창** 필요), 플랫폼 LANGUAGE_UNAVAILABLE | 모델 화면 "다운로드"로 공식 API 호출 → 사용자가 동의 창에서 직접 승인 |
| 공급 속도 | 25 초 조각 1×·2×·4× 전사문 동일(ko 01·05), wall ≈ 공급시간 + 0.1 초 | 기본 1×, 선택 2×·4× |
| 61 초 넘는 세션 | 앞부분 잘림(사용자 실측) | 조각 ≤ 30 초(ChunkPlanner 기본) 유지, 시스템 엔진 maxChunkSec 30 |
| 세션 한도 | 약 36 한도 재현 안 됨(396 세션 정상) | 감시·복구는 유지(다른 버전·기기 대비), 세션 번호 기록 |
| 앱 내 복구 | `killBackgroundProcesses` 무효, 강제 중지는 앱에서 불가 | 사용자 강제 중지 안내 + 이어하기 |
| 화면 끔 | 포그라운드 서비스 안에서는 정상, 진짜 백그라운드는 오류 없이 무응답 | RunService 안에서만 실행 + 감시 타이머 필수 |
| 플랫폼 분할 세션 | `EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE` 없으면 결과가 빈 문자열 | 분할 세션 필수 |
| 플랫폼 문장부호 | 기본은 부호 없음 | `EXTRA_ENABLE_FORMATTING=FORMATTING_OPTIMIZE_QUALITY` 로 ML Kit 과 맞춤(평가기는 부호 제거) |
| Flow 오류 종료 | 엔진이 파이프를 더 안 읽어 공급 스레드가 막힘 | 세션 종료 즉시 파이프 쓰기 쪽을 닫는다 |
| 결과 형식 | 텍스트만(시간·신뢰도 없음) | `[구간별]` 은 조각 원본 offset 만, confidence 없음 |

## 3. 확정 결정 (인터뷰 결과)

| 항목 | 결정 |
|---|---|
| 범위 | 시스템 엔진 2종 + USER VAD 프리셋 편집 + 1차 보류 정리 |
| 접근 | A: 시스템 엔진을 기존 `AsrEngine` 계약·VAD 조각 경로에 맞춤 |
| 입력 | VAD 조각만 실시간 공급(무음 제거 후), 다른 엔진과 같은 ChunkPlanner 결과 |
| 공급 속도 | 기본 1×, 실행 설정에서 2×·4× 선택, 결과에 기록 |
| USER 프리셋 | 기존 프리셋 복사 → 값 변경 → `USER_<이름>` 으로 저장, 저장 후 불변, VAD 9종 모두 |
| 기기 | S22 Ultra 로 개발·검증, S25 는 사용자가 나중에 |
| 간이 테스트 음원 | KR 01·05, EN 01 (Customer_Service). 평가 중심은 장치 녹음본 KR 05~08, EN 01·02·05·08 |

## 4. 시스템 엔진 구조

### 4.1 구성

```
engine/system/
  SystemEngineKind.kt       MLKIT_BASIC, PLATFORM_ONDEVICE
  LanguageStatus.kt         AVAILABLE / DOWNLOADABLE / DOWNLOADING / UNAVAILABLE(+이유)
  SystemEngineStatus.kt     엔진·언어별 상태 조회·다운로드 요청(인터페이스 + 두 구현)
  PacedPcmFeeder.kt         float 조각 → PCM16 → 파이프에 배속(1/2/4) 공급, 세션 종료 시 즉시 닫기
  SessionWatchdog.kt        조각길이÷배속 + 15 초 안에 끝 신호가 없으면 멈춤 판정
  MlKitBasicEngine.kt       ML Kit GenAI SpeechRecognition(MODE_BASIC) 세션 = 조각 1개
  PlatformOnDeviceEngine.kt createOnDeviceSpeechRecognizer + EXTRA_AUDIO_SOURCE(파이프) + 분할 세션
  EngineStalledException.kt 재시도 후에도 멈춤 → 배치 일시정지 신호(엔진 종류·안내 대상 앱 포함)
```

- **두 엔진 모두 `AsrEngine` 구현**: `prepare(threads)` 는 클라이언트 생성·상태 확인, `transcribe(chunk, lang)` 는 세션 1개(조각 1개), `cancel()` 은 세션 중단·파이프 닫기, `close()` 는 1회만 해제.
- **세션 = 조각 1개**: 61 초 잘림을 피하고 다른 엔진과 같은 단위로 비교한다.
- **언어 코드**: ko → `ko-KR`, en → `en-US`.
- **ML Kit**: `SpeechRecognition.getClient(options{locale, preferredMode=MODE_BASIC})`, `startRecognition(request{audioSource=AudioSource.fromPfd(read 쪽)})` Flow 에서 FinalText 들을 순서대로 공백으로 이어 `text`, 원문은 응답 순서대로 `rawText` 에 보존. `CompletedResponse` 또는 `ErrorResponse` 에서 종료. 공급이 끝나면 `stopRecognition()`.
- **플랫폼**: 메인 스레드에서 생성·호출(`withContext(Dispatchers.Main)`), 인텐트에 `EXTRA_AUDIO_SOURCE`(read 쪽 PFD), `EXTRA_AUDIO_SOURCE_ENCODING=PCM_16BIT`, `_CHANNEL_COUNT=1`, `_SAMPLING_RATE=16000`, `EXTRA_SEGMENTED_SESSION=EXTRA_AUDIO_SOURCE`, `EXTRA_LANGUAGE`, `EXTRA_PREFER_OFFLINE=true`, `EXTRA_ENABLE_FORMATTING=FORMATTING_OPTIMIZE_QUALITY`. `onSegmentResults` 를 이어 붙이고 `onEndOfSegmentedSession` 에서 종료. RECORD_AUDIO 권한 필요(API 요구) — 없으면 실행 전 거부.
- **마이크 대체 감지(플랫폼)**: 공급기가 쓴 바이트 수가 조각 크기와 같고 끝까지 쓰기가 진행됐는지 확인한다. 쓰기가 진행되지 않았는데 결과가 오면 `EngineException(TRANSCRIBE, PIPE_NOT_CONSUMED)`.
- **오류 매핑**: 언어 미설치(ML Kit INVALID_REQUEST·플랫폼 LANGUAGE_UNAVAILABLE) → `LANGUAGE_UNAVAILABLE`, 음성 없음(NO_SPEECH/NO_MATCH) → 빈 텍스트로 정상 처리(조각이 무음이면 NO_MATCH 와 같은 의미), 그 밖의 엔진 오류 → `NATIVE_ERROR` 계열 코드에 원래 코드·메시지 보존.

### 4.2 공급기·감시·복구

1. `PacedPcmFeeder`: `ParcelFileDescriptor.createPipe()` 의 쓰기 쪽에 100 ms 블록(3,200 바이트)을 `100 ms ÷ 배속` 간격으로 쓴다. 세션이 끝나면(정상·오류·취소 어느 쪽이든) 즉시 쓰기 쪽을 닫아 공급 스레드가 막히지 않게 한다. 쓴 바이트·막힌 최대 시간을 기록한다.
2. `SessionWatchdog`: `조각길이 ÷ 배속 + 15 초` 안에 끝 신호(ML Kit Completed/Error, 플랫폼 EndOfSegmentedSession/Error)가 없으면 멈춤.
3. 멈춤 1회: 클라이언트를 닫고(닫기도 5 초 제한) 새로 만들어 같은 조각을 1회 재시도.
4. 재시도도 멈춤: `EngineStalledException` → BatchRunner 가 **그 항목을 결과로 쓰지 않고**(inFlight 해제) 배치를 멈춘다. 실행 상태는 `Paused(이유, 안내 대상 앱)` — 진행 화면에 한국어 안내와 [설정 열기](`ACTION_APPLICATION_DETAILS_SETTINGS`, 대상 앱 패키지) [이어하기] 버튼. 이어하기는 기존 resume 경로로 그 항목부터 다시 실행한다. 이 경로는 크래시 횟수(inFlightAttempts)를 올리지 않는다.
5. 세션 번호: 엔진 인스턴스별 세션 순번을 detail JSON 에 기록(한도 재현 시 분석용).

### 4.3 상태 확인과 다운로드 (모델 화면)

- `ModelCatalog` 에 시스템 엔진 2개를 추가한다. `ModelKind.ASR`, 새 `Runtime.MLKIT_GENAI`, `Runtime.PLATFORM_ONDEVICE`, `downloads` 와 `files` 는 비어 있음, `engineConfig = SystemEngineConfig(kind)`, `languages = {ko, en}`, `maxChunkSec = 30`, `license = "Google 서비스 약관(ML Kit GenAI 추가 약관 / 기기 음성 서비스)"`, `commercial = CONDITIONAL`(약관 적용, 공개 가중치 아님).
- 설치 상태는 파일 대신 **언어별 `LanguageStatus`** 로 판단한다. 언어 상태 조회는 비동기(ML Kit `checkStatus()`, 플랫폼 `checkRecognitionSupport()`)이므로 동기 함수인 `ModelStore.state(spec)` 에 넣지 않는다. 시스템 엔진은 `ModelStore.state` 가 항상 `READY`(파일 없음) 를 돌려주고, `EngineFactory`·`ManagedEngine` 은 파일·READY 표식 검사를 건너뛴다. 실제 사용 가능 여부는 `SystemEngineStatus` 가 모델 화면(언어별 표시)과 실행 시작 직전(4.4)에 확인한다.
- API 수준: ML Kit Basic 은 minSdk 31 그대로. 플랫폼 엔진은 `EXTRA_AUDIO_SOURCE`·분할 세션·`checkRecognitionSupport` 가 API 33, `triggerModelDownload` 가 API 34 이므로, API 33 미만에서는 모든 언어를 `UNAVAILABLE(Android 13 이상 필요)` 로, API 33 에서는 다운로드 버튼을 숨긴다(S22·S25 는 해당 없음).
- "다운로드" 버튼: ML Kit 은 `download()`, 플랫폼은 `triggerModelDownload(intent, executor, listener)`(API 34). 동의 창은 해당 서비스가 띄우며 **사용자가 직접 승인**한다(앱이 누르지 않음). 진행·실패를 한국어로 표시.
- 삭제·가져오기·검증 버튼은 시스템 엔진에 표시하지 않는다.
- run 단위 모델 보호(1차 I-4)의 `acquireAll` 은 시스템 엔진도 그대로 거친다(사용 횟수만 올라가고 지울 파일이 없어 영향 없음). 별도 분기를 만들지 않는다.

### 4.4 실행 설정·결과 기록

- 실행 탭: 시스템 엔진 선택 시 "공급 배속"(1×/2×/4×, 기본 1×) 표시. 실행 시작 전 선택 언어별 상태를 확인해 사용 불가 언어는 그 엔진에서 제외하고 manifest `unsupported` 에 `combo|lang (언어 미설치)` 로 기록, 화면에 안내.
- 조합 이름: 배속을 모델 이름에 넣어 같은 run 에서 비교할 수 있게 한다 — algo `mlkit`, model `basic-x1`/`basic-x2`/`basic-x4`; algo `android-ondevice`, model `asi-x1`/… (예: `mlkit_basic-x1_SILERO`).
- CSV 매핑(P1 spec §11 확장): `device` = `system(soda)` / `system(asi)`(PrepareInfo.backend), `engine_version` = `mlkit-genai-sr 1.0.0-alpha1 / com.google.android.tts <versionName>` 또는 `com.google.android.as <versionName>`(PackageManager), `compute_type` 빈 값, `workers` 빈 값, `transcribe_sec` = 실제 걸린 시간 합, **`rtf` 빈 값**, `note` 에 `feed=x1` 추가.
- detail JSON: 조각별 세션 번호, 공급 바이트, 공급 최대 막힘(ms), 재시도 여부.
- RunService 밖(진짜 백그라운드)에서는 시스템 엔진을 실행하지 않는다 — 배치는 항상 RunService 경유(1차와 동일).

## 5. USER VAD 프리셋

- `vad/UserPresetStore.kt`: 앱 폴더 `user_presets.json`(AtomicFile 로 저장)에 `{id, family, baseId, params, createdAt}` 목록 보관.
- id 규칙: `USER_` + 이름, 이름은 `[가-힣A-Za-z0-9-]{1,32}`, 기본 프리셋·기존 USER 이름과 중복 금지(대소문자 무시).
- 생성: 실행 탭 프리셋 목록의 [복사해서 만들기] → 기준 프리셋 선택 → 해당 계열의 파라미터 편집 폼(계열별 필드·허용 범위 검증, 예: threshold 0~1, ms ≥ 0) → 이름 입력 → 저장. 저장 후 수정 불가(새 이름으로 복사).
- 등록: `VadPresets.all`(기본 26) + USER 목록을 합친 조회 함수(`PresetRegistry`)를 실행 설정·BatchRunner·이어하기 복원이 함께 사용한다. 기본 프리셋 동작은 변하지 않는다.
- 이어하기 호환: manifest `PresetEntry.params` 문자열과 저장된 USER 프리셋 params 가 같아야 재개된다(불변이므로 같음). 미완료 run 이 쓰는 USER 프리셋을 삭제하려 하면 경고, 삭제된 상태로 이어하기하면 한국어 사유로 거부.
- 결과 폴더: `<algo>_<model>_USER_<이름>` (한글 이름 그대로).
- P1 spec §7.3 의 "USER 프리셋 편집(Phase 2 로 연기)" 문구를 이 절로 대체한다.

## 6. 1차 보류 항목 정리

| 항목 | 조치 |
|---|---|
| "CRASH_OR_KILLED 항목 다시 실행" 정리 순서(파일 삭제 → CSV 행 삭제 사이 중단 시 중복 행) | CSV 행 먼저 삭제 → 파일 삭제 순서로 바꾸고, 실행 기록에 "다시 실행 정리 중" 표식을 남겨 중단 후 재시작 시 정리를 다시 수행(멱등) |
| 대량 크래시 정리 속도 테스트가 시간 기준(8 초) | 요약 CSV 읽기·`removeRows` 호출 횟수를 세는 구조적 단언으로 교체 |
| 모델 삭제 시 받다 만 다운로드(`tmp/<id>.dl`) 남음 | 삭제 시 함께 지움 |

## 7. 오류 처리 원칙 (추가분)

- 새 오류 코드: `LANGUAGE_UNAVAILABLE`, `PIPE_NOT_CONSUMED`, `ENGINE_STALLED`(일시정지 사유), `PERMISSION_MISSING`(RECORD_AUDIO).
- 시스템 엔진의 사용 불가 언어는 실행 전에 걸러지고 기록된다(조용한 대체 금지).
- 일시정지(Paused)는 오류가 아니라 사용자 조치 대기 상태다. 알림에도 같은 안내를 띄운다.

## 8. 검증 계획

1. **PC 단위 테스트**: PacedPcmFeeder(배속 간격, 종료 시 즉시 닫힘, 쓴 바이트), SessionWatchdog, 재시도·일시정지 경로(가짜 클라이언트로 BatchRunner·RunCoordinator), LanguageStatus 매핑, 조합 이름·CSV 행(rtf 빈 값, device, note feed), UserPresetStore(이름 규칙·중복·불변·저장/읽기·이어하기 동일성), 보류 항목 3건.
2. **빌드**: assembleDebug/assembleRelease, 16 KB 검사, release keep 규칙(ML Kit 의존성 추가에 따른 R8 영향 확인).
3. **S22 Ultra**(사용자 데이터 보존: `connectedDebugAndroidTest` 금지, `install -r` + `am instrument` 또는 앱 UI):
   - 사용자가 영어 모델 동의 창을 승인한 뒤(ML Kit·플랫폼 각각) KR 01·05, EN 01 × {ML Kit, 플랫폼} × {NONE, SILERO_DEFAULT} × {1×, 4×} 실행, 결과 비교.
   - 화면 끔 상태 실행 1회.
   - 멈춤 경로: 실제 멈춤을 만들 수 없으면 가짜 엔진 계측 테스트로 일시정지·안내·이어하기 UI 확인.
   - USER 프리셋 1개(예: USER_SILERO-조용한방) 생성 → 실행 → 중단 → 이어하기.
   - PC 평가기 채점(by_model.csv 를 테스트보고서에 기록, smoke 표기).

## 9. 위험·미확인

| 항목 | 대응 |
|---|---|
| ML Kit 1.0.0-alpha1 API 변경 가능성 | 버전 고정, 어댑터 한 파일에 격리 |
| ML Kit 의존성(Play services·Firebase transport) 추가로 인한 R8·권한·크기 영향 | release 빌드·기기 확인, 병합 매니페스트 기록 |
| 영어 모델 동의 창을 사용자가 승인하지 않으면 영어 검증 불가 | 미수행으로 기록, 한국어만 검증 |
| 4× 공급이 54 파일 전체에서 1× 와 같은지 미확인 | 결과 폴더명에 배속을 넣어 사용자가 비교 |
| ASI 강제 중지 후 복구는 미시험 | 안내만 제공, 실제 멈춤 발생 시 기록 |
| 다른 기기·버전에서 세션 한도 재발 | 감시·재시도·일시정지·세션 번호 기록 |
