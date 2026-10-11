# STT_OnDevice Phase 2 테스트 보고서 (초안)

<!-- change(add)-hyungchul-2026-10-11-19:18 (2차 작업 6) 신규 — 머리말과 "작업 6" 절만 작성, 이후 작업이 절을 덧붙인다 -->

- 작성: 2026-10-11 (Asia/Seoul), 브랜치 `phase2`
- 사용한 기기: **Galaxy S22 Ultra (SM-S908N, Android 16)**, 시리얼 R5CT43TFS9A.
  **목표 기기 Galaxy S25 Ultra 측정은 미수행**입니다. 아래 기기 수치는 모두 S22 Ultra 값입니다.
- 근거 표기: "작업 <n>" 은 작업 보고서 `.superpowers/sdd/2026-10-11-stt-ondevice-phase2/task-<n>-report.md`(로컬 기록)입니다.
- 원칙: 실행하지 않은 검증·측정은 "미수행" 으로 적습니다. 측정 불가 값은 빈 칸입니다.
  사용자 음성 전사문은 공개 저장소에 넣지 않도록 **앞 30자 정도와 글자 수만** 적습니다.

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
