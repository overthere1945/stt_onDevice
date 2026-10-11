# STT_OnDevice Phase 2 테스트 보고서

<!-- change(add)-hyungchul-2026-10-11-19:18 (2차 작업 6) 신규 — 머리말과 "작업 6" 절만 작성, 이후 작업이 절을 덧붙인다 -->
<!-- change(add)-hyungchul-2026-10-11-20:32 (2차 작업 10) 맨 앞에 요약, 맨 뒤에 "작업 10 — 실기기 검증" 절 추가 -->
<!-- change(add)-hyungchul-2026-10-12-01:00 (2차 9단계) 요약 아래 9단계 안내 한 줄, 맨 뒤에 "9단계(2026-10-12)" 절 추가 -->
<!-- change(add)-hyungchul-2026-10-12-03:37 (멈춤 원인 구간 좁히기) 맨 뒤에 "10단계(2026-10-12)" 절 추가 -->

- 작성: 2026-10-11 (Asia/Seoul), 브랜치 `phase2`
- 사용한 기기: **Galaxy S22 Ultra (SM-S908N, Android 16)**, 시리얼 R5CT43TFS9A.
  **목표 기기 Galaxy S25 Ultra 측정은 미수행**입니다. 아래 기기 수치는 모두 S22 Ultra 값입니다.
- 근거 표기: "작업 <n>" 은 작업 보고서 `.superpowers/sdd/2026-10-11-stt-ondevice-phase2/task-<n>-report.md`(로컬 기록)입니다.
- 원칙: 실행하지 않은 검증·측정은 "미수행" 으로 적습니다. 측정 불가 값은 빈 칸입니다.
  사용자 음성 전사문은 공개 저장소에 넣지 않도록 **앞 30자 정도와 글자 수만** 적습니다.

## 요약 (작업 10 기준, 2026-10-11 21:25)

<!-- change(add)-hyungchul-2026-10-11-20:32 -->
| 항목 | 결과 |
|---|---|
| PC 단위 테스트·빌드·16 KB | **410개 통과**(60 클래스, 실패·건너뜀 0), debug·release·androidTest 빌드 성공, debug·release 모두 **11/11** 정렬 |
| 영어(en-US) 시스템 엔진 | **미수행(사용자 다운로드 미완료)** — 20:43·20:45 두 엔진 en-US 미설치, 21:03 은 플랫폼만 확인(미설치, 설치 목록 보조 조회는 한 번 실패). 실행에서는 사유와 함께 `unsupported` 로 기록됨(로그·manifest 로 확인) |
| 한국어 시스템 엔진 매트릭스(10-3) | **일부 수행.** ML Kit 1× · 플랫폼 1× × SILERO_DEFAULT × KR 01·05 = **OK 4**. 그다음 `NONE` × KR 01 의 같은 조각에서 **두 엔진 모두 실제로 멈춤(엔진마다 1회)** → 배치 일시정지. 4× 와 나머지 NONE 은 **미수행(서비스 멈춤 — 강제 중지 필요)** |
| 실제 멈춤 → 일시정지 경로 | **실기기에서 실제로 동작 확인**(두 엔진: 감시 → 1회 재시도 → 일시정지, 항목 미기록. ML Kit 쪽에서만: 진행 화면·알림 안내, [설정 열기] 가 "Google 음성 인식 및 합성" 앱 정보 화면을 엶. 플랫폼 쪽 진행 화면·[설정 열기]는 미확인) |
| 화면 끔 실행(10-4) | **미수행(서비스 멈춤 — 강제 중지 필요)** |
| USER 프리셋·취소·이어하기(10-5) | 화면 흐름 **수행**(이름은 `quiet-room` — 한글 입력 불가). 실행·취소·이어하기는 **whisper tiny 로 대신 수행해 통과**(OK 2, CSV 2행). ML Kit 4× 로는 **미수행(서비스 멈춤)** |
| 일시정지 화면 테스트(10-6) | `PausedUiDeviceTest` **OK (3 tests)** + 실제 일시정지 화면도 확인 |
| PC 채점(10-7) | kr 3개 run 채점(rc 0) — **간이(smoke)**: ML Kit 1× SILERO_DEFAULT·플랫폼 1× SILERO_DEFAULT 모두 WER 5.00 % / CER 2.58 %(2 발화) |
| S25 Ultra | 미수행(모든 기기 수치는 S22 Ultra) |

<!-- change(add)-hyungchul-2026-10-12-01:00 -->
> **9단계(2026-10-12)에서 위 표의 미수행 항목을 다시 확인했습니다**: 영어·x1/x4 일치·화면 끔·USER 프리셋 × 시스템 엔진 취소/이어하기 모두 수행·통과, 멈춤 원인 실험은 맨 뒤 "9단계" 절 참조.
<!-- change(add)-hyungchul-2026-10-12-03:37 -->
> **멈춤 원인 구간을 더 좁혔습니다**(KR01 60.0~70.5 초 재현): 맨 뒤 "10단계" 절 참조.

## 작업 6 — 시스템 엔진 Android 연결부(ML Kit Basic·플랫폼 온디바이스)와 실기기 계측

### 6.1 PC 검증 (2026-10-11 19:30~19:37)

| 항목 | 명령 | 결과 |
|---|---|---|
| JVM 단위 테스트 전체 | `./gradlew :app:testDebugUnitTest` | **371개 통과**(실패 0, 건너뜀 0) |
| 빌드 | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease` | 성공 |
| 16 KB 정렬 | `tools/check_16kb.sh` (debug·release APK) | 둘 다 **11/11** — ML Kit 이 새 `.so` 를 넣지 않음 |

### 6.2 ML Kit(genai-speech-recognition 1.0.0-alpha1)이 병합 매니페스트에 더한 항목

작업 6 이전 HEAD(2d27eba)와 이번 빌드의 release 병합 매니페스트를 비교했습니다.

- **새 권한: 없음.** (ACCESS_NETWORK_STATE·FOREGROUND_SERVICE_DATA_SYNC·RECEIVE_BOOT_COMPLETED 등은 이전부터 다른 라이브러리가 넣던 것)
- 새 `<queries>`: `com.google.android.aicore`(ML Kit 이 추가)
- 새 provider: `com.google.mlkit.common.internal.MlKitInitProvider`(exported=false)
- 새 service: `com.google.mlkit.common.internal.MlKitComponentDiscoveryService`(exported=false),
  `com.google.android.datatransport.runtime.backends.TransportBackendDiscovery`(exported=false),
  `com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService`(exported=false, BIND_JOB_SERVICE)
- 새 receiver: `com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver`(exported=false)
- 새 activity: `com.google.android.gms.common.api.GoogleApiActivity`(exported=false)
- 앱이 직접 더한 것: `<queries>` 에 `com.google.android.tts`·`com.google.android.as` 패키지와 `android.speech.RecognitionService` 인텐트
  (서비스 versionName 을 결과 engine_version 에 기록하기 위한 패키지 가시성)

### 6.3 실기기 계측 — `SystemEngineDeviceTest` (2026-10-11 19:38:29~19:39:11)

- 설치: `adb install -r app-debug.apk`, `adb install -r app-debug-androidTest.apk`(앱 삭제·`connectedAndroidTest` 사용 안 함)
- 실행: `adb shell am instrument -w -e class com.cowork.stt_ondevice.engine.system.SystemEngineDeviceTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner`
- 결과: **`OK (3 tests)`**, 41.4 초
- 입력: 앱 입력 폴더 `input_sound/kr/Customer_Service/01.wav` 의 첫 25 초(조각 1개). RECORD_AUDIO 는 이미 허용 상태였음.
- 서비스 버전(엔진이 기록한 engine_version):
  - ML Kit: `mlkit-genai-sr 1.0.0-alpha1 / com.google.android.tts googletts.google-speech-apk_20260914.02_p2.986315744`
  - 플랫폼: `com.google.android.as B.29.playstore.oemfull.988117775`

| 엔진(공급 배속) | 조각 | 벽시계 | 공급 바이트 | 문장부호 | 글자 수 | 전사문 앞부분 |
|---|---|---|---|---|---|---|
| mlkit-basic-x1 | 0~25 초 | 25,023 ms | 800,000 | 있음 | 171 | "안녕하세요. 혹시 이번 주 토요일 저녁 6시에 네명…" |
| mlkit-basic-x4 | 0~25 초 | 6,339 ms | 800,000 | 있음 | 171 | x1 과 **글자 하나까지 같음** |
| mlkit-basic-x4 (같은 엔진 두 번째 세션) | 10~15 초 | 1,302 ms | 160,000 | 있음 | 37 | "잠시만 조회해 드리겠습니다. 아, 다행히…" |
| android-ondevice-asi-x4 | 0~25 초 | 6,321 ms | 800,000 | **있음**(FORMATTING_OPTIMIZE_QUALITY) | 171 | ML Kit 과 **같은 전사문** |
| android-ondevice-asi-x4 (같은 엔진 두 번째 세션) | 10~15 초 | 1,323 ms | 160,000 | 있음 | 37 | ML Kit 두 번째 세션과 같음 |

- 단언: x1·x4 텍스트 비어 있지 않음·서로 같음, x4 벽시계(6,339) < x1(25,023) ÷ 2 — 통과. 플랫폼 텍스트 비어 있지 않음 — 통과.
- 벽시계 ≈ 공급 시간 + 약 0.02~0.09 초(실험 보고서와 같은 경향). 블록 쓰기 최대 1 ms(공급기 막힘 없음).
- 실험(spike) 때 문장부호가 없던 플랫폼 경로는 `EXTRA_ENABLE_FORMATTING` 을 넣어 이번에는 문장부호가 붙었다.

**en-US 미설치 보고(`englishUnavailableIsReported`)** — 다운로드(`download()`)는 부르지 않음

| 엔진 | `unavailableReason(…, "en")` 사유 | 실제 en 전사 결과 | 걸린 시간 |
|---|---|---|---|
| mlkit-basic-x4 | 언어 미설치(en-US) — Speech Services by Google 언어 팩 다운로드 필요(상태 DOWNLOADABLE) | `LANGUAGE_UNAVAILABLE`(원래 오류 errorCode=0 …ERROR_TYPE_INVALID_REQUEST 보존) | 188 ms |
| android-ondevice-asi-x4 | 언어 미설치(en-US) — Android System Intelligence 언어 팩 필요(설치됨: ko-KR) | `LANGUAGE_UNAVAILABLE`(onError=13) | 90 ms |

- 엔진이 먼저 오류로 끝나도 공급기가 막히지 않고 즉시 끝남(감시 시간 15 초 이상을 기다리지 않음).

### 6.3a 파이프 막힘 해제 — `AudioPipeDeviceTest` (2026-10-11 19:49, 리뷰 라운드 1)

<!-- change(add)-hyungchul-2026-10-11-19:47 -->
- 엔진 프로세스가 읽기 쪽 사본을 쥐고 읽지 않는 상황을 `readSide.dup()` 으로 흉내 내고, 작업 스레드가 3,200 B 블록을 계속 쓰게 했다.
- 결과: **`OK (2 tests)`**. 두 경우 모두 51,200 B 에서 막힘(300 ms 진행 없음) 확인 →
  다른 스레드에서 쓰기 쪽 닫기(`closeWrite`)는 0 ms, `closeAll` 은 2~3 ms 뒤 작업 스레드가
  `java.io.InterruptedIOException: write interrupted by close() on another thread` 로 끝남(1 초 안 단언 통과).
- 해석: 막힌 공급기를 푸는 근거는 쓰기 쪽 `ParcelFileDescriptor.close()` 이다. 읽기 쪽을 닫아도 엔진 쪽 사본이 열려 있으면 EPIPE 는 나지 않는다.
- 수정 후 `SystemEngineDeviceTest` 재실행도 `OK (3 tests)`(ML Kit x1 25,032 ms·x4 6,337 ms, 플랫폼 x4 6,346 ms, 전사문 동일).

### 6.4 release APK 시작 확인 (2026-10-11 19:39)

- `app-release-unsigned.apk` 를 로컬 debug 키로 서명 → `adb install -r` → `adb shell am start -W -n com.cowork.stt_ondevice/.MainActivity`
  → 시작 354 ms, `uiautomator dump` 에서 "모델 관리" 화면 확인, 크래시 로그 없음.
- release 에서 시스템 엔진 전사 실행은 **미수행**(시작·모델 화면 진입만 확인). 확인 뒤 debug APK 를 다시 `install -r` 했고 입력 WAV 54개·모델 폴더 8개 그대로.

### 6.5 미수행·미확인

- S25 Ultra 측정: 미수행.
- en-US 전사(두 엔진): 언어 팩 미설치라 미수행(설치는 사용자 승인 사항).
- 배치 실행기(RunService)로 시스템 엔진 조합 전체를 돌리는 종단 실행: 이 작업 범위 밖(미수행).
- 멈춤(hang) 상태에서의 복구: 이번 실행에서 멈춤이 나지 않아 미확인.

## 작업 7 — 모델 화면 시스템 엔진 언어별 상태와 다운로드

<!-- change(add)-hyungchul-2026-10-11-20:05 -->
### 7.1 PC 단위 테스트

- 새 테스트 `SystemEnginesSectionTest`(9개)·`SystemEnginesReasonTest`(6개) 통과(수정 라운드 1 후). 전체 `./gradlew :app:testDebugUnitTest` 386개 중 실패 0, `:app:assembleDebug` 성공.
- 검증 내용: 엔진 2개 × 언어 ko·en 상태 행, 다운로드 중 `busyLang`, 완료·실패 뒤 상태 재조회, 안내 메시지 1회, 15 초 제한 시간(가상 시간),
  실행 전 확인(`unavailableReason`)이 작업 6 의 사유 문구·예외 처리 규칙을 그대로 유지.

### 7.2 실기기 확인 (Galaxy S22 Ultra, Android 16 / API 36) — 2026-10-11 19:59

- debug APK `adb install -r` → `adb shell am start` → 앱 첫 화면이 모델 탭. `uiautomator dump` 와 `screencap` 으로 확인(스크린샷은 scratchpad 에만 저장, 커밋하지 않음).
- 표시된 상태(앱이 `checkStatus`/`checkRecognitionSupport` 로 조회한 값):

| 엔진 | 한국어 | 영어 |
|---|---|---|
| ML Kit GenAI Basic (Speech Services by Google) | 사용 가능 | 다운로드 필요 (+[다운로드] 버튼) |
| Android 온디바이스 인식기 (Android System Intelligence) | 사용 가능 | 다운로드 필요 (+[다운로드] 버튼, API 36 ≥ 34) |

- 두 카드 모두 "Google 서비스 약관 적용" 문구와 [새로고침] 이 있고 삭제·가져오기·검증 버튼은 없음. 크래시 로그 없음.
- 플랫폼 영어는 `supportedOnDeviceLanguages` 에 있으나 `installedOnDeviceLanguages` 에 없어 "다운로드 필요"로 표시됨(작업 6 의 "언어 미설치" 와 일치).
- **[다운로드]·[새로고침] 은 누르지 않음**(영어 모델 동의는 작업 10 에서 사용자가 직접). 다른 앱 창은 뜨지 않았고 앱 삭제·데이터 삭제 없음.
- 미수행: 실제 다운로드 흐름(동의 창·진행·완료 후 재조회)의 기기 확인 — 사용자 승인이 필요해 작업 10 으로 남김. Android 13 이하 기기 경로, 상태 확인 15 초 초과의 기기 재현은 미수행(PC 단위 테스트만).

## 작업 10 — 실기기 검증(Galaxy S22 Ultra)·문서

<!-- change(add)-hyungchul-2026-10-11-20:32 -->
- 기기: SM-S908N, Android 16(API 36). Speech Services by Google `googletts.google-speech-apk_20260914.02_p2.986315744`,
  Android System Intelligence `B.29.playstore.oemfull.988117775`. RECORD_AUDIO·POST_NOTIFICATIONS 는 이미 허용 상태.
- 설치는 `adb install -r`(debug·androidTest), 계측은 `adb shell am instrument -w …` 만 사용. 앱 삭제·`connectedAndroidTest`·시스템 설정 변경 없음.
  다른 앱 화면(Google 다운로드·동의 창, 설정의 강제 중지)은 누르지 않았습니다.
- 기기 조작은 앱 화면만 `uiautomator dump` + `adb shell input tap/text/swipe` 로 했고, 조작 직전마다 앞 화면이 이 앱(또는 런처)인지 확인했습니다
  (검증 중 사용자가 폰을 쓰는 상황은 없었음).
- 가져온 결과·스크린샷·logcat 은 PC 임시 폴더에만 두고 커밋하지 않았습니다. 전사문 원문은 이 문서에 넣지 않습니다(글자 수·해시 비교만).

### 10.1 빌드·정적 확인 (20:33~20:43)

| 항목 | 명령 | 결과 |
|---|---|---|
| 단위 테스트 + 빌드 | `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` | BUILD SUCCESSFUL, **60 클래스 410 테스트, 실패·오류·건너뜀 0** |
| 화면 테스트 의존성 추가 뒤 다시 빌드 | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease` | BUILD SUCCESSFUL |
| 16 KB 정렬 | `tools/check_16kb.sh` debug / release APK | **11/11 · 11/11** |

- 이번 작업에서 더한 의존성: androidTest 에 BOM 관리 `androidx.compose.ui:ui-test-junit4`, debug 에 `ui-test-manifest`(`libs.versions.toml` 에 선언). release APK 에는 들어가지 않습니다.

### 10.2 영어(en-US) 모델 — 미수행(사용자 다운로드 미완료)

사용자가 폰에서 직접 [다운로드]·Google 동의 창을 누르기로 했으므로, 앱은 다운로드를 요청하지 않고 상태만 다시 확인했습니다.

| 시각 | 확인 방법 | ML Kit en-US | 플랫폼 en-US |
|---|---|---|---|
| 20:43:24 | `SystemEngineDeviceTest#englishUnavailableIsReported`(작업 6 과 같은 상태 조회, `download()` 안 부름) → OK (1 test) | 언어 미설치(상태 DOWNLOADABLE), 실제 전사는 156 ms 만에 `LANGUAGE_UNAVAILABLE` | 언어 미설치(설치됨: ko-KR), 107 ms 만에 `LANGUAGE_UNAVAILABLE` |
| 20:45:58 | 매트릭스 run 시작 시 앱의 실행 전 확인(같은 조회) | 같음 → 4개 조합 × en 을 `unsupported` 로 기록 | 같음 |
| 21:03:19 | 두 번째 run 시작 시 실행 전 확인 | — | 같음(asi-x1 은 보조 조회 실패로 "설치됨: 확인 불가") |

- 따라서 **영어 시스템 엔진 전사는 미수행**입니다. 영어 행이 빠지는 동작(사유 기록·조용한 대체 없음)은 확인했습니다:
  `run_manifest.json` `unsupported` 에 `<조합>|en` 8개, 실행 로그 `LANG_UNAVAILABLE … 언어 미설치(en-US) — …`, 결과 탭 "미지원 조합(언어 미지원으로 실행 안 함)".
- 21:14 이후에는 두 서비스가 멈춰(10.4) 상태 조회 자체가 15 초 시간 초과라 en-US 설치 여부를 알 수 없습니다.

### 10.3 시스템 엔진 매트릭스 — 일부 수행

계획: KR `Customer_Service/01·05`, EN `Customer_Service/01` × {`mlkit-basic-x1`, `mlkit-basic-x4`, `android-ondevice-asi-x1`, `android-ondevice-asi-x4`} × {`NONE`, `SILERO_DEFAULT`}.
앱 실행 탭에서 그대로 골라 시작(예상 조합 24건 → en 8건 제외, 실제 16항목). 결과는 `tools/adb/pull_results.sh` 로 가져왔습니다.

**run `20261011_204558`**(4개 모델, 20:45:58 시작) — 프리셋 실행 순서 SILERO_DEFAULT → NONE

| 조합 | 파일 | 상태 | audio_sec | transcribe_sec | 조각(세션 번호) | 공급 바이트 | 최대 막힘 | rtf | device | note 끝 |
|---|---|---|---|---|---|---|---|---|---|---|
| mlkit_basic-x1_SILERO_DEFAULT | kr/01 | OK | 84.135 | 75.688 | 3 (1·2·3) | 2,411,776 | 1 ms | 빈 값 | system(soda) | `feed=x1` |
| mlkit_basic-x1_SILERO_DEFAULT | kr/05 | OK | 84.323 | 75.960 | 3 (4·5·6) | 2,419,584 | 1 ms | 빈 값 | system(soda) | `feed=x1` |
| mlkit_basic-x1_NONE | kr/01 | **일시정지**(결과 미기록) | | | 3번째 조각(세션 9)·재시도(세션 10) 멈춤 | | | | | |
| 나머지(x1 NONE 05, x4 전부, asi 전부) | | **미수행** — 일시정지로 멈춤 | | | | | | | | |

**run `20261011_210319`**(플랫폼 x1·x4 만, 21:03:19 시작 — ML Kit 서비스가 멈춘 뒤 플랫폼만 따로 확인)

| 조합 | 파일 | 상태 | audio_sec | transcribe_sec | 조각(세션 번호) | 공급 바이트 | 최대 막힘 | rtf | device | note 끝 |
|---|---|---|---|---|---|---|---|---|---|---|
| android-ondevice_asi-x1_SILERO_DEFAULT | kr/01 | OK | 84.135 | 75.520 | 3 (1·2·3) | 2,411,776 | 1 ms | 빈 값 | system(asi) | `feed=x1` |
| android-ondevice_asi-x1_SILERO_DEFAULT | kr/05 | OK | 84.323 | 75.821 | 3 (4·5·6) | 2,419,584 | 6 ms | 빈 값 | system(asi) | `feed=x1` |
| android-ondevice_asi-x1_NONE | kr/01 | **일시정지**(결과 미기록) | | | 3번째 조각(세션 9)·재시도(세션 10) 멈춤 | | | | | |
| 나머지(x1 NONE 05, x4 전부) | | **미수행** — 일시정지로 멈춤 | | | | | | | | |

- 1× 는 transcribe_sec ≈ 공급한 음성 길이(2,411,776 B ÷ 32,000 B/s = 75.37 초) + 조각당 약 0.1 초. `rtf`·`compute_type`·`workers` 는 빈 값,
  `engine_version` 은 서비스 버전(위), `note` 끝에 `;feed=x1` — 설계서 §4.4 와 같습니다.
- **ML Kit 1× 와 플랫폼 1× 의 전사문이 두 파일 모두 글자 하나까지 같았습니다**(KR 01 592자, KR 05 589자, `text_sha1` 동일). 작업 6 의 25 초 비교와 같은 결과입니다.
- **x1 · x4 전사 일치 여부는 이번 작업에서 미확인**(x4 실행 전에 멈춤). 참고: 작업 6 계측에서 KR 01 첫 25 초는 x1·x4 가 같았습니다(6.3절).
- 영어 행: 10.2 대로 미수행.

### 10.4 실제 멈춤(hang) 발생과 일시정지 경로 — 실기기 확인

두 엔진 모두 **같은 위치에서** 멈췄습니다: `kr/Customer_Service/01.wav` × `NONE`(30 초 고정 분할 → 0~30·30~60·60~84.1 초)의 **세 번째 조각(24.1 초)**.

| 시각 | ML Kit(run 204558) | 플랫폼(run 210319) |
|---|---|---|
| 조각 3 시작 | 20:49:33 세션 시작(서비스 로그) | 21:06:52 |
| 서비스 내부 오류 | 20:49:56 `No final state active` / `Result lattice is not set` — 그 뒤 음성 끝(20:49:57)까지 받았지만 **최종 결과·종료 신호를 보내지 않음** | 21:07:16 같은 오류 |
| 앱 감시 시간 초과 → 세션 취소·클라이언트 다시 만들기 | 20:50:12 (24.1 + 15 초) | 21:07:31 |
| 재시도 세션 | 서비스가 "Initialize Soda…" 에서 더 진행하지 않음 | 같음 |
| 재시도도 시간 초과 → **일시정지** | 20:50:51 "Speech Services by Google 가 응답하지 않습니다(세션 10)" | 21:08:11 "Android System Intelligence 가 응답하지 않습니다(세션 10)" |

- 일시정지 때 확인한 것(ML Kit run 204558): 그 항목은 `result.csv`·txt 에 **쓰이지 않았고**(결과 탭 "OK 2 · ERROR 0"), `run_manifest.json` `pausedReason` 에 사유, `inFlight` 없음·`inFlightAttempts` `{}`(크래시로 세지 않음),
  실행 로그 `RUN_PAUSED`, 진행 화면에 제목·핵심 문구·상세 사유·①②③ 안내·[설정 열기]·[이어하기], 알림 "실행 일시정지 / … 설정에서 강제 중지한 뒤 이어하기를 누르세요."
- 진행 화면 [설정 열기] → **설정 앱 `InstalledAppDetails` 가 "Google 음성 인식 및 합성"(= com.google.android.tts) 앱 정보 화면으로 열림** 확인. [강제 중지] 는 누르지 않고 뒤로 가기로 돌아왔습니다.
- 진행 화면 [이어하기] → 결과 탭에서 그 run 이 열림 → **강제 중지 없이** [이어하기](21:00:57): 같은 항목부터 다시 시작했지만 첫 조각부터 서비스가 응답하지 않아
  21:02:27 다시 일시정지("세션 2"). → **서비스는 강제 중지 전까지 회복되지 않습니다.**
- 앱 프로세스를 다시 시작해도(계측 테스트 실행) 두 서비스 모두 한국어 상태 조회가 "상태 확인 시간 초과(ko-KR, 15 초) — … 응답 없음" 으로 실패(21:13·21:14),
  모델 화면도 네 언어 모두 "사용 불가(상태 확인 시간 초과)".
- 플랫폼(run 210319) 일시정지는 실행 로그·manifest·logcat 으로만 확인했고, 그때의 진행 화면·알림·[설정 열기](Android System Intelligence 앱 정보 화면)는 보지 않았습니다(미확인).
- 해석(추정): 이 조각에서 서비스 내부 인식기가 오류 후 끝 신호를 보내지 않고, 그 상태가 서비스 프로세스에 남습니다. 세션 수 한도인지 이 음성 조각 때문인지는
  확정하지 못했습니다(두 엔진 모두 10번째 세션·같은 조각이었고, 작업 6·실험에서는 세션 396개까지 정상).
- **사용자 조치 필요**: 설정 → 애플리케이션 → "Google 음성 인식 및 합성"(확인한 이름) 과 Android System Intelligence(설정 화면 이름·위치 미확인) 를 각각 강제 중지 → 결과 탭에서 run `20261011_204558`·`20261011_210319` 이어하기.
  같은 KR 01 × NONE 조각에서 다시 멈출 수 있으니, 매트릭스를 다시 돌릴 때는 이 조합을 맨 뒤에 두거나 따로 확인하는 것을 권합니다.

### 10.5 화면 끔 실행 — 미수행

KR 05 × `mlkit-basic-x1` × `SILERO_DEFAULT` 를 화면을 끈 채 실행할 계획이었으나, 그 시점에 Speech Services by Google 이 멈춰(10.4) 있어 실행하지 못했습니다(강제 중지는 사용자 조치).
(1차 보고서 3.8 에서 whisper tiny 로 화면 끔 실행은 확인됨. 시스템 엔진의 화면 끔은 실험 보고서의 포그라운드 서비스 결과만 있음.)

### 10.6 USER 프리셋 화면 흐름과 취소·이어하기

**화면 흐름(실행 탭)** — 수행

1. [복사해서 만들기] → "복사할 기본 프리셋 선택" 목록 → `SILERO — SILERO` → "SILERO 복사해서 만들기" 폼(threshold·minSpeechMs·minSilenceMs·speechPadMs·maxSpeechSec, 칸마다 범위 안내).
2. threshold `0.5` → `0.35`. (조작 중 실수로 `0.35ㅛ` 가 들어갔을 때 칸이 빨갛게 "threshold: 숫자여야 합니다" 로 바뀌는 것도 봄 → 지워서 `0.35`.)
3. 프리셋 이름: **한글 `조용한방` 은 `adb shell input text` 가 한글을 넣지 못해(NullPointerException) 영문 `quiet-room` 으로 바꿨습니다.**
4. [저장] → 스낵바 "사용자 프리셋 'USER_quiet-room' 을(를) 저장했습니다", "사용자 프리셋 (1개, 선택 1개)" 아래 `USER_quiet-room (SILERO)`·"출처: USER(기준 SILERO)"·
   `threshold=0.35,minSpeechMs=250,minSilenceMs=400,speechPadMs=150,maxSpeechSec=inf`, **체크된 상태**. 앱 내부 `files/user_presets.json` 에 같은 값 저장 확인.
5. [삭제] → 확인 창 "USER_quiet-room 삭제 / USER 프리셋 USER_quiet-room 를 지울까요? 지우면 되돌릴 수 없습니다." [취소]/[지우기] → **[취소]**, 프리셋 그대로.

**실행 → 취소 → 이어하기** — 시스템 엔진이 멈춰 있어 **whisper tiny 로 대신 수행**(ML Kit 4× 로는 미수행)

| 단계 | 결과 |
|---|---|
| KR 01·05 × `whispercpp-tiny` × `USER_quiet-room` 실행(run `20261011_211836`, 21:18:36) | 예상 조합 2건 |
| 01 처리 중 진행 탭 [취소] → [취소하기](21:18:45) | "실행 취소됨", 로그 `RUN_CANCELLED`, 결과 탭 "미완료 · 조합 0개" |
| 결과 탭 [이어하기](21:19:06) | 로그 `RUN_START resume=true`, 01 을 처음부터 다시 → 01·05 OK, `RUN_END ok=2` |
| 결과 | txt 2개(`kr/whispercpp_tiny_USER_quiet-room/Customer_Service/01·05.txt`), `result.csv` **2행**, 요약 CSV **2행**(`vad=USER_quiet-room`), manifest 프리셋 params 가 저장 값과 같음 |

### 10.7 일시정지 화면 계측 테스트 — `PausedUiDeviceTest`

- `adb shell am instrument -w -e class com.cowork.stt_ondevice.ui.run.PausedUiDeviceTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner`
  → **OK (3 tests)**, 2.68 초(20:43:35).
- 그린 상태: `RunUiState.Paused("20261011_210000", "Speech Services by Google 가 응답하지 않습니다(세션 3)", "com.google.android.tts", "Speech Services by Google")`.
- 확인: 제목·핵심 문구·상세 사유·①②③ 안내·[설정 열기]·[이어하기] 표시 / [설정 열기] → `onOpenSettings("com.google.android.tts")` 1회 / [이어하기] → `onOpenResults(runId)` 1회.
  취소 버튼은 `RunService.cancel` 을 직접 부르므로 누르지 않았습니다(일시정지 화면에는 취소 버튼이 없음).
- 이번 검증 중 **실제 멈춤이 발생**해 같은 화면을 실기기에서도 확인했습니다(10.4).

### 10.8 PC 채점 — 간이(smoke) 결과

`~/workspace/python/asr_stt/stt_all_gui_2nd/run_eval.py` 를 `venv_eval` 로 실행만 함(수정 없음), `--ref ~/workspace/stt/answer_sheet/kr --no-noformat`. 세 run 모두 rc 0.
**발화 2개짜리 smoke 수치이며 엔진 우열 결론이 아닙니다.** (ML Kit·플랫폼은 전사문이 같아 수치도 같음)

| run | model(by_model.csv) | n_utt | WER(%) | CER(%) |
|---|---|---|---|---|
| 20261011_204558 | mlkit_basic-x1_SILERO_DEFAULT | 2 | 5.00 | 2.58 |
| 20261011_210319 | android-ondevice_asi-x1_SILERO_DEFAULT | 2 | 5.00 | 2.58 |
| 20261011_211836 | whispercpp_tiny_USER_quiet-room | 2 | 33.33 | 14.75 |

- 평가기가 `<algo>_<model>_<VAD>` 이름(USER 프리셋 포함)으로 페어링했습니다. 두 run 은 미완료(일시정지) 상태라 끝난 항목만 채점됐습니다.

### 10.9 문서

- 사용설명서: 12절 시스템 엔진(언어 상태·다운로드 동의·[취소] 는 앱 추적만 끝냄·공급 배속·RECORD_AUDIO 가 필요한 이유와 마이크 탭 권한 요청·
  일시정지 때 강제 중지 방법·앱을 다시 켜면 진행 탭 안내는 사라지지만 결과 탭에서 이어하기 가능·실측한 멈춤), 13절 USER 프리셋(만들기·이름 규칙·삭제 확인·NONE 불가),
  14절 결과 CSV 차이(rtf 빈 값, device `system(...)`, note `feed=xN`), 4·6·7·10·11절 안내 줄. README 기능 목록·문서 링크. 1차 설계서 §7.3·§12 의 "Phase 2 로 연기" 를 2차 설계서 §5 참조로.

### 10.10 기기에 남긴 상태

| 항목 | 상태 |
|---|---|
| 앱 | 이번 debug APK(`adb install -r`) + androidTest APK 설치 |
| 사용자 음원 / 모델 | `input_sound` wav **54개**, `models/` **8개** 그대로(작업 끝, 채점 뒤 `adb shell find`·`ls` 로 확인) |
| 새 run | `20261011_204558`(일시정지, OK 2), `20261011_210319`(일시정지, OK 2), `20261011_211836`(완료, OK 2) — 지우지 않음 |
| USER 프리셋 | `USER_quiet-room`(SILERO, threshold 0.35) 남김 |
| 서비스 | **Speech Services by Google·Android System Intelligence 모두 멈춘 상태** — 사용자가 강제 중지해야 함(10.4) |

### 10.11 미수행 항목 정리

| 항목 | 이유 |
|---|---|
| 영어 시스템 엔진 전사(두 엔진) | 사용자 다운로드 미완료(en-US 미설치) |
| 매트릭스의 4× 전체, x1 NONE, x1·x4 일치 비교 | 실행 중 두 서비스가 실제로 멈춤 → 강제 중지(사용자 조치) 전까지 회복 안 됨 |
| 화면 끔 시스템 엔진 실행(10-4) | 같은 이유 |
| USER 프리셋 × ML Kit 4× 실행·취소·이어하기 | 같은 이유(whisper tiny 로 같은 흐름은 통과) |
| 한글 이름 `조용한방` 입력 | `adb shell input text` 가 한글 미지원 → `quiet-room` 사용 |
| 다운로드 동의 흐름의 기기 확인 | 사용자가 직접 하는 단계(앱이 대신 누르지 않음) |
| S25 Ultra | 기기 없음 |

## 9단계(2026-10-12) — 남은 실기기 검증과 멈춤 원인 실험

<!-- change(add)-hyungchul-2026-10-12-01:00 (2차 9단계) 신규 절 -->
- 기기: Galaxy S22 Ultra(SM-S908N, Android 16). 사용자가 00:56 에 폰을 재시작해 두 서비스가 새로 뜬 상태에서 시작(재시작 뒤 인식 세션 0).
  Speech Services `googletts.google-speech-apk_20260914.02_p2.986315744`, Android System Intelligence `B.29.playstore.oemfull.988117775`.
- 앱: HEAD `4ad92b7`(최종 리뷰 수정 포함) debug APK + androidTest APK 를 01:05 에 `adb install -r`. 앱 삭제·`connected*`·설정 변경·다른 앱 버튼 누르기·`am force-stop` 없음.
- 순서: 멈출 위험이 낮은 검증(A) 먼저, 멈출 수 있는 실험(B) 맨 뒤. 원본 결과·logcat 은 PC 임시 폴더(커밋 안 함). 전사문 원문은 적지 않습니다(글자 수·SHA-1 앞 12자리만).
- 세션 수: 앱 detail JSON `chunks[].session` + 계측 테스트 기록, 그리고 서비스 프로세스가 세션마다 남기는 `Initialize Soda [locale: …]` 줄을 PID 별로 세어 맞춰 봤습니다(A 단계에서 정확히 일치).
  언어 상태 조회는 이 줄을 남기지 않아 세션으로 세지 않았습니다.
- 관찰: 쓰지 않는 동안 Speech Services 프로세스가 시스템에 의해 정리될 수 있습니다(01:02:01 `Killing 14060:com.google.android.tts … empty`, 다음 사용 때 PID 18071 로 새로 뜸).

### 9.1 수행·미수행 요약

| 항목 | 결과 |
|---|---|
| A0 영어(en-US) | **수행** — 사용자가 받은 en-US 언어 팩(01:00) 확인, 모델 화면 두 엔진 모두 "영어: 사용 가능" → A1 에 영어 포함 |
| A1 배속 매트릭스(KR01·05, EN01 × 4 모델 × SILERO_DEFAULT) | **수행, 12/12 OK** — x1·x4 전사문 모두 일치(글자 수·SHA-1), ML Kit·플랫폼 끼리도 일치 |
| A2 화면 끔(KR05 × mlkit-basic-x1) | **수행, 정상** — 실행 내내 `mWakefulness=Dozing`, 화면 켠 결과와 같은 전사문 |
| A3 USER 프리셋 + 취소/이어하기(KR01·05 × asi-x4 × USER_quiet-room) | **수행, 통과** — 결과 2·CSV 2행·중복 없음 |
| B1 ML Kit (b) 60.0~82.6 초 | **수행 — 멈춤**(누적 22번째 세션) |
| B1 ML Kit (a) 60.0~84.1 초, 40 세션 반복 | **미수행** — (b) 에서 이미 멈춤 |
| B2 최종 수정 실기기 확인 | **수행** — 일시정지·`finishedAt` null·`unsupported` 없음·결과 탭 사유 표시는 확인. 단 "상태 확인 시간 초과로 건너뜀" 경로는 일어나지 않음(멈춘 서비스가 상태 조회에 정상 응답) |
| B3 ASI (b) 60.0~82.6 초 | **수행 — 같은 내부 오류로 끝 신호 없음**(누적 26번째 세션), 재시도는 `onError=11` |
| B3 ASI (a)·40 세션 반복 | **미수행** — (b) 통과 못 함, 멈춘 것으로 간주 |
| PC 채점 | **수행**(A1·A2·A3, smoke) |
| S25 Ultra | 미수행(기기 없음) |

### 9.2 A 단계 결과

**A1** run `20261012_010933`(01:09:33~01:18:37, `RUN_END ok=12 error=0`). 모델 실행 순서 mlkit-x1 → mlkit-x4 → asi-x1 → asi-x4, 각 모델 안에서 en/01 → kr/01 → kr/05.

| 파일 | 조합 | 상태 | transcribe_sec | attempts | 세션 | 글자 수 | text_sha1 | device | note 끝 |
|---|---|---|---|---|---|---|---|---|---|
| en/01 (75.863 s) | mlkit_basic-x1_SILERO_DEFAULT | OK | 63.359 | 3 | 1·2·3 | 1087 | 3bb5de610a81 | system(soda) | `feed=x1` |
| en/01 | mlkit_basic-x4_SILERO_DEFAULT | OK | 16.228 | 3 | 1·2·3 | 1087 | 3bb5de610a81 | system(soda) | `feed=x4` |
| en/01 | android-ondevice_asi-x1_SILERO_DEFAULT | OK | 63.198 | 3 | 1·2·3 | 1087 | 3bb5de610a81 | system(asi) | `feed=x1` |
| en/01 | android-ondevice_asi-x4_SILERO_DEFAULT | OK | 16.140 | 3 | 1·2·3 | 1087 | 3bb5de610a81 | system(asi) | `feed=x4` |
| kr/01 (84.135 s) | mlkit_basic-x1_SILERO_DEFAULT | OK | 75.646 | 3 | 4·5·6 | 592 | 0df299a38bcc | system(soda) | `feed=x1` |
| kr/01 | mlkit_basic-x4_SILERO_DEFAULT | OK | 19.144 | 3 | 4·5·6 | 592 | 0df299a38bcc | system(soda) | `feed=x4` |
| kr/01 | android-ondevice_asi-x1_SILERO_DEFAULT | OK | 75.589 | 3 | 4·5·6 | 592 | 0df299a38bcc | system(asi) | `feed=x1` |
| kr/01 | android-ondevice_asi-x4_SILERO_DEFAULT | OK | 19.153 | 3 | 4·5·6 | 592 | 0df299a38bcc | system(asi) | `feed=x4` |
| kr/05 (84.323 s) | mlkit_basic-x1_SILERO_DEFAULT | OK | 75.980 | 3 | 7·8·9 | 589 | 0ab1586e51a6 | system(soda) | `feed=x1` |
| kr/05 | mlkit_basic-x4_SILERO_DEFAULT | OK | 19.239 | 3 | 7·8·9 | 589 | 0ab1586e51a6 | system(soda) | `feed=x4` |
| kr/05 | android-ondevice_asi-x1_SILERO_DEFAULT | OK | 75.887 | 3 | 7·8·9 | 589 | 0ab1586e51a6 | system(asi) | `feed=x1` |
| kr/05 | android-ondevice_asi-x4_SILERO_DEFAULT | OK | 19.225 | 3 | 7·8·9 | 589 | 0ab1586e51a6 | system(asi) | `feed=x4` |

- **x1 · x4 전사문 일치: 세 파일 × 두 엔진 모두 같음.** x4 transcribe_sec ≈ x1 의 1/4. 재시도 0(attempts = 조각 수). 서비스 내부 오류 0건.
- 작업 10 에서 미확인이던 "x1·x4 일치"와 "영어 시스템 엔진 전사"를 이번에 확인했습니다.

**A2 화면 끔** run `20261012_012131`: [실행] 01:21:31.0 → 01:21:32.2 `input keyevent 26` → 끝날 때까지 `Dozing` → OK, transcribe_sec 75.874, attempts 3, 589자·SHA-1 0ab1586e51a6(화면 켠 A1 과 같음).
화면을 켠 뒤에는 보안 잠금 화면이라 사용자가 풀 때까지(01:23~01:29) 기다렸습니다(잠금 해제는 하지 않음).

**A3 USER 프리셋 + 취소/이어하기** run `20261012_013101`: kr/01·05 × asi-x4 × `USER_quiet-room`(threshold 0.35) → [취소] 01:31:23(01 완료 직후, 05 시작 직후 — 05 는 서비스 세션을 열기 전) →
결과 탭 "미완료 · OK 1" → [이어하기] 01:31:48(`resume=true`, 05 만 처리) → "완료 · OK 2".
kr/01 OK 20.338 s·attempts 3·586자 / kr/05 OK 20.341 s·attempts 4(조각 4)·591자. txt 2, `result.csv` 2행, 요약 CSV 2행(`vad=USER_quiet-room`), 중복 행 없음, manifest `finishedAt` 기록·`unsupported` 없음.
(작업 10 에서 whisper tiny 로 대신 했던 흐름을 시스템 엔진으로 확인 — 플랫폼 x4 사용, ML Kit 4× 로는 하지 않음.)

**PC 채점(간이 smoke)** `run_eval.py`(venv_eval, 수정 없음), 모두 rc 0:

| run | 언어 | model | n_utt | WER(%) | CER(%) |
|---|---|---|---|---|---|
| 20261012_010933 | kr | 네 조합(mlkit x1·x4, asi x1·x4 _SILERO_DEFAULT) 모두 | 2 | 5.00 | 2.58 |
| 20261012_010933 | en | 네 조합 모두 | 1 | 2.01 | 2.09 |
| 20261012_012131 | kr | mlkit_basic-x1_SILERO_DEFAULT(화면 끔) | 1 | 6.67 | 2.81 |
| 20261012_013101 | kr | android-ondevice_asi-x4_USER_quiet-room | 2 | 5.00 | 2.93 |

### 9.3 B 단계 — 멈춤 원인 실험

계측 테스트 `SodaHangProbeTest`(androidTest, 9단계 진단 전용 — `engine` 인자 없이는 건너뜀)로 KR01 에서 메모리로 잘라 낸 조각을 세션 하나씩 넣었습니다(`SystemEngines.create` + `transcribe`, x4, 감시 시간 규칙 그대로).

KR01 끝부분 실측(입력 파일 그대로, 100 ms 창): 82.6~84.135 초는 |x| ≤ 1 LSB 인 **거의 디지털 무음**(정확히 0 은 마지막 0.092 초뿐). 81.2~81.7 초에도 같은 거의-무음 0.5 초가 있음.

| 시각 | 서비스(PID) | 조각 | 그 서비스 누적 세션 | 결과 |
|---|---|---|---|---|
| 01:33:15~01:33:57 | Speech Services(18071) | **60.0~82.6 초**(무음 꼬리 제거, 22.6 초) | 22번째(재시도 23) | **멈춤** — 오디오 끝 직후 `No final state active` / `Result lattice is not set`, 끝 신호 없음 → 감시 초과 → 재시도는 `Initialize Soda with language pack directory` 에서 멈춤 → `EngineStalledException`(41.4 초) |
| 01:39:09~01:39:30 | Android System Intelligence(5838) | **60.0~82.6 초** | 26번째 | 같은 내부 오류, 끝 신호 없음 → 감시 초과 → 재시도 새 인식기는 서비스에 닿기 전 `onError=11`(서버 연결 끊김) → ENGINE_ERROR(20.7 초) |

- 원본 그대로인 (a) 60.0~84.1 초와 누적 40 세션 반복(H2 확인)은 두 서비스 모두 **미수행**입니다((b) 에서 이미 멈춰, 멈춘 서비스로 더 시도하지 않음).
- 같은 서비스 프로세스에서 앞선 정상 세션: Speech Services 21개, ASI 25개(SILERO_DEFAULT·USER_quiet-room 조각, x1·x4, ko·en) — 모두 오류 없이 끝남.

**가설 판정(이번 증거 범위 안)**

| 가설 | 판정 | 근거 |
|---|---|---|
| H1 발화 뒤 긴 디지털 무음 꼬리 | **이번 결과와 맞지 않음** | 꼬리를 잘라 낸 (b) 에서도 두 서비스 모두 같은 오류·같은 멈춤. 단 (a) 를 돌리지 않아 꼬리가 "추가로" 영향을 주는지는 모름 |
| H2 서비스 프로세스 누적 세션 수(35 성공 → 36번째 먹통) | **이번 멈춤은 H2 로 설명되지 않음** | 멈춘 세션이 누적 22번째(ML Kit)·26번째(ASI), 작업 10 에서는 run 의 9번째. 36 세션 한도 자체가 이 기기·판에 있는지는 40 세션 반복을 하지 못해 **확인하지 못함** |
| (관찰) 이 음성 구간 자체 | **유력하지만 미확정** | 두 서비스·두 날짜(작업 10, 9단계)·서로 다른 누적 세션 수에서 KR01 60 초부터 시작하는 조각만 같은 오류. 같은 파일의 SILERO_DEFAULT 세 번째 조각(66.21~82.72 초, 발화 7개를 이어 붙이고 사이를 0.2 초 디지털 0 으로 채운 15.6 초)은 끝이 거의 같은데도 x1·x4 모두 정상. 차이는 60.0~66.2 초 부분(60.0 초 지점은 소리가 거의 없음, |x| ≤ 9 — 발화 중간을 자른 것은 아님)과 발화 사이 원래 배경음, 조각 길이(22.6 vs 15.6 초)인데 어느 것이 원인인지는 가르지 못함 |

### 9.4 B2 — 최종 리뷰 수정의 실기기 확인(Speech Services 멈춘 상태)

run `20261012_013639`(01:36:39~01:37:32): kr/05 × {whispercpp-tiny, mlkit-basic-x4} × SILERO_DEFAULT.

| 기대 | 실제 | 판정 |
|---|---|---|
| whisper 정상 완료 | OK(9.95 초) | 같음 |
| ML Kit 은 상태 확인 시간 초과로 건너뜀 | 멈춘 서비스가 `#checkModelAvailability` 에 **바로 답함**(설치됨 ko-KR·en-US) → 사용 가능 → 실제 시도 → 두 세션 모두 `Initialize Soda with language pack directory` 에서 멈춤 → `RUN_PAUSED "Speech Services by Google 가 응답하지 않습니다(세션 2)"` | **다름**(멈춤 경로로 일시정지) |
| 일시정지, `finishedAt == null` | 그대로, `inFlight` null, `inFlightAttempts` {} | 같음 |
| `unsupported` 에 ML Kit 없음 | `unsupported` [], `unavailableReasons` {} | 같음 |
| 결과 탭 "일시정지: …" | "일시정지: Speech Services by Google 가 응답하지 않습니다(세션 2)" + "해당 서비스 앱을 강제 중지한 뒤 카드를 펼쳐 [이어하기]를 누르세요"(uiautomator dump) | 같음 |

- "상태 확인 실패(Transient) → 미지원으로 닫지 않고 일시정지" 경로는 이번에 그 상황(상태 조회 시간 초과)이 생기지 않아 **실기기 미확인**(PC 단위 테스트로만 확인)입니다.
  작업 10 에서는 멈춘 뒤 수십 분 지나 상태 조회가 시간 초과됐지만, 이번에는 멈춘 뒤 약 3 분 시점에 상태 조회가 정상이었습니다.

### 9.5 서비스별 누적 세션(재시작 뒤)

| 서비스 | PID | A1 | A2 | A3 | B1 | B2 | B3 | 합계 | 끝 상태 |
|---|---|---|---|---|---|---|---|---|---|
| Speech Services by Google(ML Kit) | 18071(01:05 부터, 끝까지 같음) | 18 | 3 | — | 2(멈춤) | 2(멈춤) | — | **25** | **멈춤** |
| Android System Intelligence(플랫폼) | 5838(끝까지 같음) | 18 | — | 7 | — | — | 1(끝 신호 없음) + 재시도 0(서비스 도달 전 오류) | **26** | **멈춘 것으로 간주**(새 세션 시험 안 함) |

(Speech Services 는 00:56 재시작 뒤 PID 14060 이었으나 세션 0 인 채 01:02 에 시스템이 정리했고, 01:05 상태 조회 때 18071 로 새로 떴습니다.)

### 9.6 운영 코드 관찰(고치지 않음)

- 플랫폼 백엔드는 재시도 세션의 `onError=11`(ERROR_SERVER_DISCONNECTED)을 일반 `ENGINE_ERROR` 로 돌려줍니다. 그래서 "첫 세션 감시 초과 → 재시도가 연결 끊김으로 즉시 실패" 이면 배치가 **일시정지되지 않고 그 항목만 ERROR** 가 됩니다.
  B3 계측에서 이 순서를 실제로 봤고, 앱 배치에서 재현하지는 않았습니다(미확인). 재시도 쪽 11 을 "응답 없음"으로 볼지는 설계 판단이 필요합니다.
- **후속 수정으로 일시정지 처리** (change(add)-hyungchul-2026-10-12-02:54): 첫 시도가 멈춘 뒤 재시도가 `LANGUAGE_UNAVAILABLE`·`PERMISSION_MISSING` 이 아닌 `Failed` 로 끝나면 `EngineStalledException`(일시정지)으로 던지고, 11 은 새 코드 `SERVICE_DISCONNECTED` 로 구분합니다. PC 단위 테스트(`retryFailureAfterStallPauses` 등)로만 확인했고 실기기 재현은 하지 않았습니다.

### 9.7 기기에 남긴 상태

| 항목 | 상태 |
|---|---|
| 앱 | HEAD `4ad92b7` debug APK(DEBUGGABLE) + androidTest APK(`SodaHangProbeTest` 포함), 01:05 설치 |
| 사용자 음원 / 모델 | `input_sound` wav **54개**, `models/` **8개** 그대로(01:41 확인) |
| 새 run | `20261012_010933`·`20261012_012131`·`20261012_013101`(완료), `20261012_013639`(일시정지, OK 1) — 지우지 않음 |
| 서비스 | **Speech Services by Google 멈춤, Android System Intelligence 도 멈춘 것으로 간주 → 사용자 재시작(또는 두 서비스 강제 중지) 필요** |

## 10단계(2026-10-12) — 멈춤 원인 구간 좁히기 (S22 Ultra, ML Kit x4, SodaHangProbeTest)

<!-- change(add)-hyungchul-2026-10-12-03:37 (멈춤 원인 구간 좁히기) 신규 절. 번호 충돌을 피하려고 "작업 10"(10.1~10.11)과 구분해 "10단계-n" 으로 매김 -->
- 목적: 9단계에서 멈춘 KR01(`kr/Customer_Service/01.wav`, 84.135 초) 60.0~82.6 초 조각이 **어느 부분 때문에** 멈추는지 구간을 줄여 가며 확인.
- 기기: Galaxy S22 Ultra(SM-S908N, Android 16). 입력은 KR01 원음을 메모리에서 잘라 낸 조각 하나(세션 하나)입니다. 전사문 원문은 적지 않습니다(글자 수만).

### 10단계-1 실험 방법

- **빌드·설치 없이** 9단계에 설치된 APK 의 기존 진단 테스트 `SodaHangProbeTest` 를 `adb shell am instrument` 로 실행했습니다(`engine=mlkit-basic-x4`, `mode=span`, 구간 `start`·`end` 지정).
  엔진은 ML Kit(Speech Services by Google, x4)만 썼습니다. 감시 시간은 `ceil(조각초×1000÷4)+15000` ms, 멈추면 테스트가 1회 재시도합니다(재시도도 멈춤).
- 한 번 멈추면 그 서비스로 더 시도하지 않고 라운드를 끝냈습니다(`am force-stop`·다른 앱 화면 조작 없음). 멈출 때마다 **사용자가 폰을 재시작**했고, 재시작은 **모두 4회**(라운드 1~4 앞)였습니다.
- 서비스 프로세스(tts) PID 별 누적 세션은 서비스 로그의 `Initialize Soda [locale: …]` 줄 수로 셌습니다(9단계와 같은 방법). 매 라운드 재시작 뒤 첫 실행 때는 0 세션에서 시작했고, 실행 직전 앞 화면은 모두 런처였습니다.
- 멈춤의 모양은 매번 같았습니다: 오디오를 다 받은 직후 `No final state active`(E) + `Result lattice is not set … SpeechError(-73542)` → 끝 신호 없음 → 감시 초과 → 재시도 세션이 `Initialize Soda … with language pack directory` 뒤에서 멈춤(`PROBE_STALL`).

### 10단계-2 라운드별 결과

표의 "글자 수"는 통과한 조각의 전사문 길이입니다. "PID·누적 세션"의 "n번째" 는 그 구간이 그 PID 의 몇 번째 세션이었는지입니다.

**라운드 1** (사용자 재시작 02:57)

| 시각 | 구간 | 길이 | 결과 | tts PID·누적 세션 |
|---|---|---|---|---|
| 02:58:42 | 66.2~82.6 (대조군) | 16.4 초 | 통과(117자) | 12956 · 1번째 |
| 02:58:58 | 60.0~66.2 | 6.2 초 | 통과(43자). 경고 `Result lattice is not set` 1회가 있었으나 `No final state active` 없이 정상 종료 | 12956 · 2번째 |
| 02:59:29 | 60.0~72.0 | 12.0 초 | **멈춤** | 12956 · 3번째(재시도 4번째) |
| — | 60.0~77.0, 60.0~82.6 | — | 미수행(멈춰서 라운드 종료) | — |

**라운드 2** (사용자 재시작 03:15)

| 시각 | 구간 | 길이 | 결과 | tts PID·누적 세션 |
|---|---|---|---|---|
| 03:18:03 | 66.0~72.0 | 6.0 초 | 통과(45자) | 12873 · 1번째 |
| 03:18:16 | 63.0~69.0(66.2 초 경계를 걸침) | 6.0 초 | 통과(45자) | 12873 · 2번째 |
| 03:18:27 | 60.0~72.0 (재실행) | 12.0 초 | **멈춤(재현)** | 12873 · 3번째(재시도 4번째) |

**라운드 3** (사용자 재시작 03:21 이후)

| 시각 | 구간 | 길이 | 결과 | tts PID·누적 세션 |
|---|---|---|---|---|
| 03:29:18 | 60.0~69.0 (재시작 뒤 첫 세션) | 9.0 초 | 통과(65자) | 14752 · 1번째 |
| 03:29:30 | 63.0~72.0 | 9.0 초 | 통과(69자) | 14752 · 2번째 |
| 03:29:43 | 61.5~70.5 | 9.0 초 | 통과(67자) | 14752 · 3번째 |
| 03:29:52 | 60.0~72.0 (재실행) | 12.0 초 | **멈춤(재현)** | 14752 · 4번째(재시도 5번째) |

**라운드 4** (사용자 재시작 03:32)

| 시각 | 구간 | 길이 | 결과 | tts PID·누적 세션 |
|---|---|---|---|---|
| 03:34:49 | 61.5~72.0 | 10.5 초 | 통과(82자) | 13184 · 1번째 |
| 03:34:59 | 60.0~70.5 | 10.5 초(끝에 무음 꼬리 없음) | **멈춤** | 13184 · 2번째(재시도 3번째) |
| — | 60.75~72.0, 60.0~71.25, 60.0~72.0 | — | 미수행(멈춰서 라운드 종료) | — |

- 세션 위치: 멈춘 세션은 2·3·3·4번째(이번 실험)와 22번째(9단계). 모든 PID 의 누적 세션은 5 이하였습니다.

### 10단계-3 재현 횟수

| 구간 | 멈춤 횟수 | 비고 |
|---|---|---|
| 60.0~72.0 | **3회**(라운드 1·2·3) | 통과 0회 |
| 60.0~70.5 | **1회**(라운드 4) | 재현 확인은 아직 안 함 |
| 그 밖의 8개 구간(6.0~16.4 초) | 0회 | 각 구간 1회씩 통과 |

### 10단계-4 배제된 가설(이번 증거 범위 안)

| 가설 | 판정 | 근거 |
|---|---|---|
| 끝의 무음 꼬리 | 배제 | 60.0~70.5 는 꼬리가 없는데(`nearZeroTailSec=0.000`) 멈춤. 반대로 같은 꼬리를 가진 66.0~72.0·63.0~72.0·61.5~72.0 은 통과 |
| 서비스 누적 36 세션 한도 | 배제 | 재시작 직후 2~4번째 세션에서 멈춤(9단계는 22번째) |
| 단순 길이 | 배제 | 16.4 초(66.2~82.6)는 통과, 9.0~10.5 초 중에도 통과·멈춤이 갈림 |
| 66.2 초 경계 하나 | 배제 | 경계를 걸친 63.0~69.0 통과, 60.0~66.2 와 66.2~82.6 도 각각 통과 |

### 10단계-5 현재 결론 (확정 아님)

- KR01 의 **60.0~70.5 초(10.5 초)를 세션 하나로 넣으면 ML Kit(Speech Services) 가 멈춥니다.**
- 이 조각은 **앞쪽 60.0~61.5 초와 끝쪽 69.0~70.5 초가 함께 있어야** 멈추는 것으로 보입니다(하나라도 빠진 60.0~69.0·61.5~70.5·61.5~72.0 은 통과).
- 원인이 그 두 부분의 **내용 조합** 인지, **이 내용에서 약 10 초를 넘는 길이** 인지는 **아직 가르지 못했습니다.**
- 경계는 앞 60.0~61.5, 뒤 69.0~70.5 로 각각 1.5 초 폭까지 좁혔습니다. 각 구간은 대부분 1회씩만 돌렸습니다(60.0~72.0 만 3회).
- 이 결과는 ML Kit 서비스 하나(S22 Ultra, x4)에서 얻은 것입니다. 플랫폼 인식기(ASI)는 이번 실험에서 쓰지 않았고, 다른 파일·다른 기기는 확인하지 않았습니다.

### 10단계-6 미수행·후보

- 미수행: 60.0~70.5 재현 확인, 60.0~69.75·60.75~70.5 처럼 0.75 초 단위로 더 좁히기, 재시작 뒤 **첫 세션** 에 60.0~72.0 을 넣어 세션 위치를 완전히 배제하기, ASI 로 같은 구간 확인.
- 원본 logcat·계측 출력은 로컬 임시 폴더에만 있습니다(커밋 안 함).
