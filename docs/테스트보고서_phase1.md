# STT_OnDevice Phase 1 테스트 보고서

<!-- change(add)-hyungchul-2026-10-11-04:46 (Task 31) -->

- 작성: 2026-10-11 (Asia/Seoul), 브랜치 `phase1`
- 사용한 기기: **Galaxy S22 Ultra (SM-S908N, SM8450, Android 16, 4 KB 페이지)**, 시리얼 R5CT43TFS9A.
  **목표 기기 Galaxy S25 Ultra 측정은 미수행**입니다. 아래 기기 수치는 모두 S22 Ultra 값이며 S25 Ultra 성능으로 읽으면 안 됩니다.
- 근거 표기: "T<n>" 은 작업 보고서 `.superpowers/sdd/2026-10-09-stt-ondevice-phase1/task-<n>-report.md`(로컬 기록)입니다.
- 원칙: 실행하지 않은 검증·측정은 "미수행" 으로 적었습니다. 측정 불가 값은 빈 칸입니다.

## 1. 요약

| 성공 기준(설계서 §1) | 결과 | 근거 |
|---|---|---|
| 1. Gradle sync·assembleDebug 성공, arm64 `.so` 전부 16 KB 정렬 | **통과** | 2절(2026-10-11 clean 빌드, 11/11 정렬) |
| 2. 엔진 × VAD × 입력 배치가 끝까지 실행, 실패는 ERROR 로 남고 계속 | **통과(S22 Ultra)** | T25(JVM 오류 격리), 3.6(42 조합)·3.7(54파일, 취소→이어하기)·3.8(54파일, 화면 끔) |
| 3. PC `run_eval.py` 에서 모든 조합이 `<algo>_<model>_<VAD>` 로 페어링 | **통과** | T26(`tools/check_pairing.sh`), 3.6(42 조합)·3.7·3.8(kr 24·en 30 발화 전부) |
| 4. VAD Kotlin 포팅이 Python 원본과 허용오차 내 일치 | **통과(9 계열)** | 5절 |
| 5. 측정 불가 값은 빈 값 | **통과** | T23·T25(빈 값 규칙 테스트) |
| (추가) release 빌드 실기기 | **통과** — 처음 시작 크래시(Room 생성자 제거)를 keep 규칙으로 수정(R45) | 3.3 |

## 2. 1차·2차 검증 (PC)

### 2.1 1차 — 정적·단위 (작업별, 각 보고서에 RED/GREEN 기록)
- WavReader·PcmConverter·InputScanner·OutputLayout·TranscriptWriter·ResultCsvWriter(60열·BOM)·ChunkPlanner·ModelInstaller(경로 탈출·SHA-256·이어받기)·
  BatchRunner(재개·오류 격리·크래시 복구)·각 VAD 후처리 골든 비교 — T2~T25.
- 샘플 출력 폴더에 사용자 평가기 `run_eval.py` 를 읽기 전용으로 실행해 페어링 확인 — T26 `tools/check_pairing.sh` exit 0
  (kr·en 모두 모델 4개, WER/CER 0.0, 유령 모델 0, 매칭 실패 0).

### 2.2 2차 — 빌드 (2026-10-11 10:06~10:12 실행)
| 항목 | 명령 | 결과 |
|---|---|---|
| 전체 빌드·단위 테스트 | `./gradlew clean :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest` | **BUILD SUCCESSFUL (3m 46s)** |
| 단위 테스트 | (위와 같음) | **51 클래스, 323 테스트, 실패 0, 오류 0, 건너뜀(SKIPPED) 0** |
| 16 KB 정렬(debug) | `tools/check_16kb.sh app/build/outputs/apk/debug/app-debug.apk` | **11/11 `.so` LOAD Align 0x4000** |
| 16 KB 정렬(release) | `tools/check_16kb.sh app/build/outputs/apk/release/app-release-unsigned.apk` | **11/11** |
| zip 16 KB 정렬 | `zipalign -c -P 16 4 <apk>` (build-tools 37.0.0) | debug·release 모두 OK |
| 중복 `.so` | `unzip -l <apk> \| awk '{print $4}' \| grep '\.so$' \| sort \| uniq -d` | 없음(경로·파일명 모두) |
| lint | `./gradlew :app:lintDebug` | **오류 0**, 경고 10 (아래) |

- APK 크기: debug 119.3 MB, release(서명 전) 87.7 MB.
- `.so` 11개: libLiteRt, libandroidx.graphics.path, libjnidispatch, liblitert_jni, libomp, libonnxruntime, libonnxruntime4j_jni,
  libsherpa-onnx-jni, libstt_native(whisper.cpp·libfvad·JNI), libten_vad, libvosk.
- lint: 이전 오류 `ByteOrderMark`(`output/ResultCsvWriter.kt:46` 의 BOM 문자 직접 사용)를 `'﻿'` 이스케이프로 고쳐 0 이 됐습니다
  (테스트 3파일의 같은 문자도 함께 정리, 커밋 `c55918a`). 남은 경고 10건은 고치지 않았습니다:
  AndroidGradlePluginVersion 1, ChromeOsAbiSupport 1, NewerVersionAvailable 3, UnusedResources 2, UsableSpace 2, UseKtx 1.

## 3. 3차 검증 — 실기기 (Galaxy S22 Ultra, 2026-10-11)

### 3.1 이전 작업에서 수행한 기기 검증 (근거)
| 항목 | 결과 | 근거 |
|---|---|---|
| WebRTC(libfvad)·TEN 네이티브 골든 | 20건 일치(WebRTC 판정 불일치 0 프레임, TEN 확률 최대 오차 2.03e-6) — 3.2 에서 재확인 | T15 |
| Yamnet(LiteRT) | 1317 중 1316 프레임 비트 일치, 1 프레임 1/256 차이, 3 프리셋 구간 완전 일치 — 3.2 에서 재확인 | T16 |
| whisper.cpp tiny·base load/전사/중단 | tiny load 0.084 s, 0–20 s 전사 2.5 s, 중단 36.8 ms / base 중단 약 0.9~1.0 s | T20 |
| sherpa-onnx 8개 모델 load/전사 | 4절 표, `OK (14 tests)` | T21 |
| Vosk ko·en load/전사 | `OK (2 tests)`, 앞·뒤 단어 포함 단언 | T22 |
| 기기 정보·자원 측정 | 발열·배터리·PSS 실측 확인 | T23 |
| 서비스 배치·취소 | 실제 배치·취소 성공, 기기에서만 나는 CSV 정규식 버그 발견·수정(R37) | T27 |
| 모델 화면 | 다운로드·진행률·취소·이어받기·설치 | T28 |
| 실행·진행·결과 화면 | whisper tiny × {NONE, WEBRTC_ANDROIDVAD} × 2 파일 실행 → 취소 → 이어하기 → 완료 | T29 |
| SAF·마이크·adb 스크립트 | push 스크립트 권한 정리, 마이크 16 kHz mono 저장, SAF 내보내기 md5 일치, PC 채점 | T30 |

### 3.2 `./gradlew :app:connectedDebugAndroidTest` 전체 (10:55~10:57)
- 준비: 기기에 이미 설치돼 있던 모델(whisper tiny·base, zipformer-ko, qwen3 0.6B, vosk ko/en, silero, pyannote) +
  골든 클립·WEBRTC/TEN/Yamnet 골든 JSON(`files/golden/`)·`files/models/yamnet/yamnet.tflite` 를 push.
- 결과: **BUILD SUCCESSFUL (1m 35s), 22 테스트 = 통과 17 + 건너뜀(assume) 5, 실패 0.**
  - 건너뜀 5개는 기기에 모델이 없어서입니다(SenseVoice, Zipformer EN, 스트리밍 Zipformer KO, Omnilingual 300M v2, Omnilingual 선택 2종).
    AGP XML 은 assume 위반을 failure 태그로 적지만 Gradle 결과는 성공이며, 메시지가 모두 "기기에 … 모델 없음 — 건너뜀" 입니다.
  - **`sherpaZipformerKoTranscribes`(T22 에서 추가된 단어 단언) 통과** — load 1.38 s, 0–20 s inferSec 0.430.
  - `nativeMatchesGoldenClips`: WebRTC 12건 판정 불일치 0·구간 완전 일치, TEN 8건 최대 오차 2.03e-6·구간 완전 일치.
  - `yamnetMatchesGoldenScores`: 1317 프레임 중 1 프레임 1/256 차이(#1154), 3 프리셋 구간 일치.
  - `whisperTinyTranscribes`, `abortStopsWithinOneSecond`(tiny 취소→종료 367.5 ms, base 794.5 ms), `voskKo/EnTranscribes`(load 1.21 s / 0.34 s),
    `sherpaQwen3Transcribes`(load 3.78 s, 0–20 s 7.66 s, 0–30 s 13.94 s), `RunServiceInstrumentedTest` 3개, `DeviceInfoInstrumentedTest` 3개 통과.
- 이 테스트가 끝나며 앱이 제거돼 앱 폴더(이전 모델·음원·결과·T30 산출물·tmp 부분 다운로드)가 함께 지워졌습니다(사용자 승인 사항).

### 3.3 release 빌드 기기 확인(R31) — 처음 실패 → keep 규칙 추가 후 **통과**(R45)
- 절차: `apksigner sign --ks ~/.android/debug.keystore … app-release-unsigned.apk`(서명만 로컬 debug 키) → `adb install` → 앱 실행.
- 1차(10:58): 첫 화면 전에 프로세스 종료.
  `RuntimeException: Unable to get provider androidx.startup.InitializationProvider … Failed to create an instance of class androidx.work.impl.WorkDatabase…`
- 원인: LiteRT 의존성이 병합해 온 WorkManager(R21)가 앱 시작 때 Room DB `WorkDatabase` 를 만들고, Room 은 생성 클래스 `WorkDatabase_Impl` 을
  인자 없는 생성자로 리플렉션 생성합니다. room-runtime 2.5.0 소비자 규칙(`-keep class * extends androidx.room.RoomDatabase`)은 클래스만 남겨서,
  release R8(`optimization.packageScope = androidx.**`)이 `WorkDatabase_Impl.<init>()` 를 지웠습니다(`mapping/release/usage.txt` 에 제거 기록,
  `seeds.txt` 에는 클래스만 있음).
- 수정: `app/src/main/keepRules/rules.keep` 에 `-keep class * extends androidx.room.RoomDatabase { <init>(); }` 추가(생성자만 남기는 최소 범위).
  재빌드 후 `seeds.txt` 에 `WorkDatabase_Impl: WorkDatabase_Impl()` 가 남고 usage.txt 에서 빠진 것을 확인. 매니페스트(WorkManagerInitializer)는 바꾸지 않았습니다.
- 2차(12:25): release 를 같은 키로 `install -r`(앱 데이터 유지) → **시작 정상, FATAL 0**.
  run `20261011_122655`: kr/Customer_Service/01.wav × {whisper tiny, vosk ko} × {NONE, SILERO} → **OK 4 · ERROR 0**
  (whisper load 0.13 s, vosk load 2.01 s — release 에서 JNA·libvosk·whisper.cpp JNI·ORT(Silero) 로드와 전사 동작 확인).
  whisper tiny 결과 txt 는 debug 매트릭스(3.6)와 **바이트 단위로 같았습니다**. vosk 결과는 달랐는데, debug 에서 같은 조합을 다시 돌린 run `20261011_122926` 도
  이전 debug 결과와 달라 **Vosk 출력이 실행마다 조금씩 바뀌는 현상**으로 판단했습니다(release 와 무관, 7절).
- 확인 뒤 debug APK 를 다시 설치했습니다(데이터 유지 — 모델 8개·음원 54개 그대로).
- `tools/check_16kb.sh` release 11/11 정렬, `./gradlew :app:testDebugUnitTest --rerun` 323 테스트 통과(실패·건너뜀 0).

### 3.4 모델 설치 (debug 재설치 후)
- `tools/adb/push_models.sh` 로 8개(whisper tiny 폴더, silero 폴더, pyannote tar.bz2, fsmn 3파일 폴더, vosk ko/en zip, zipformer-ko·qwen3 tar.bz2)를
  `files/import/<id>/` 에 넣고, 앱 **모델** 탭 "가져오기 → 가져오기 실행" 을 uiautomator 로 눌러 설치(압축 해제·SHA-256 검증).
  8개 모두 설치됨(Qwen3 0.6B 는 약 288 s, 나머지는 수 초). 설치 뒤 `import/` 원본(약 1.3 GB)은 삭제.

### 3.5 입력 — 사용자 음원 54개를 SAF 로 가져오기
- 실행 탭 **음원 가져오기(SAF)** → 파일 선택기에서 휴대폰 `Documents/input_sound`(안에 `kr/`·`en/`) 를 고르고 "이 폴더 사용" → "허용".
- 결과: 스낵바 "음원 54개를 가져왔습니다". 언어 자동 인식으로 `input_sound/en/{Customer_Service,Friends_Conversation,vLog}` 각 10개,
  `input_sound/kr/{Customer_Service,Friends_Conversation,vLog}` 각 8개(셸 `find` 로 확인). 언어 질문 대화상자는 뜨지 않았습니다.
- 스크린샷: `task-31-s1-saf-picker-input_sound.png`, `task-31-s2-saf-allow.png`, `task-31-s3-saf-imported.png`(SDD 폴더).

### 3.6 매트릭스 smoke — run `20261011_112228`
- 설정: 파일 2개(kr/Customer_Service/01.wav, en/Customer_Service/01.wav) × 모델 5개(whisper tiny, zipformer-ko, qwen3 0.6B, vosk ko, vosk en)
  × 프리셋 6개(NONE, SILERO, PYA3_DEFAULT, FSMN_DEFAULT, WEBRTC_ANDROIDVAD, TEN_DEFAULT), MERGE, 스레드 4. 예상 조합 42건.
- 결과: **OK 42 · NO_MATCH 0 · ERROR 0 · 미지원 18**(zipformer-ko × en, vosk ko × en, vosk en × kr 을 실행하지 않고 manifest 에 기록).
  11:22:28 시작 → 11:37:06 종료(**14분 38초**).

| 모델 | 항목 | load_sec | RTF 평균(최소~최대) | PSS 최대 MB | elapsed 합(s) |
|---|---|---|---|---|---|
| whispercpp tiny | 12 | 0.07 | 0.111 (0.098~0.125) | 555 | 123.1 |
| sherpa-zipformer ko-2024-06-24 | 6 | 2.15 | 0.029 (0.025~0.035) | 930 | 15.3 |
| sherpa-qwen3asr 0.6B-int8 | 12 | 6.20 | 0.493 (0.379~0.656) | 3680 | 479.1 |
| vosk small-ko-0.22 | 6 | 3.53 | 0.366 (0.345~0.421) | 1069 | 185.4 |
| vosk small-en-us-0.15 | 6 | 0.82 | 0.130 (0.121~0.142) | 838 | 59.5 |

- PC 평가기(`run_eval.py --no-noformat`, 읽기 전용, kr·en 모두 rc 0, 매칭 실패 0) `by_model.csv` — **언어마다 파일 1개짜리 smoke 수치이며 벤치마크 결론이 아닙니다.**

kr (n_utt = 1)

| model | WER(%) | CER(%) |
|---|---|---|
| sherpa-qwen3asr_0.6B-int8_NONE | 7.33 | 2.11 |
| sherpa-qwen3asr_0.6B-int8_SILERO | 9.33 | 2.58 |
| sherpa-qwen3asr_0.6B-int8_PYA3_DEFAULT | 8.00 | 4.92 |
| sherpa-qwen3asr_0.6B-int8_FSMN_DEFAULT | 10.00 | 6.09 |
| sherpa-qwen3asr_0.6B-int8_WEBRTC_ANDROIDVAD | 5.33 | 2.34 |
| sherpa-qwen3asr_0.6B-int8_TEN_DEFAULT | 9.33 | 3.28 |
| sherpa-zipformer_ko-2024-06-24_NONE | 90.00 | 36.53 |
| sherpa-zipformer_ko-2024-06-24_SILERO | 90.00 | 35.83 |
| sherpa-zipformer_ko-2024-06-24_PYA3_DEFAULT | 93.33 | 41.22 |
| sherpa-zipformer_ko-2024-06-24_FSMN_DEFAULT | 90.00 | 29.74 |
| sherpa-zipformer_ko-2024-06-24_WEBRTC_ANDROIDVAD | 94.00 | 39.81 |
| sherpa-zipformer_ko-2024-06-24_TEN_DEFAULT | 91.33 | 44.96 |
| vosk_small-ko-0.22_NONE | 106.67 | 48.48 |
| vosk_small-ko-0.22_SILERO | 109.33 | 46.14 |
| vosk_small-ko-0.22_PYA3_DEFAULT | 105.33 | 49.65 |
| vosk_small-ko-0.22_FSMN_DEFAULT | 106.00 | 44.73 |
| vosk_small-ko-0.22_WEBRTC_ANDROIDVAD | 107.33 | 48.01 |
| vosk_small-ko-0.22_TEN_DEFAULT | 113.33 | 50.82 |
| whispercpp_tiny_NONE | 24.67 | 11.48 |
| whispercpp_tiny_SILERO | 27.33 | 12.65 |
| whispercpp_tiny_PYA3_DEFAULT | 27.33 | 11.94 |
| whispercpp_tiny_FSMN_DEFAULT | 26.00 | 10.54 |
| whispercpp_tiny_WEBRTC_ANDROIDVAD | 29.33 | 11.71 |
| whispercpp_tiny_TEN_DEFAULT | 32.00 | 13.35 |

en (n_utt = 1)

| model | WER(%) | CER(%) |
|---|---|---|
| sherpa-qwen3asr_0.6B-int8_NONE | 1.51 | 1.62 |
| sherpa-qwen3asr_0.6B-int8_SILERO | 1.01 | 0.70 |
| sherpa-qwen3asr_0.6B-int8_PYA3_DEFAULT | 1.51 | 1.62 |
| sherpa-qwen3asr_0.6B-int8_FSMN_DEFAULT | 1.01 | 0.70 |
| sherpa-qwen3asr_0.6B-int8_WEBRTC_ANDROIDVAD | 1.01 | 0.70 |
| sherpa-qwen3asr_0.6B-int8_TEN_DEFAULT | 1.01 | 0.70 |
| vosk_small-en-us-0.15_NONE | 4.02 | 1.51 |
| vosk_small-en-us-0.15_SILERO | 5.53 | 1.85 |
| vosk_small-en-us-0.15_PYA3_DEFAULT | 5.53 | 2.32 |
| vosk_small-en-us-0.15_FSMN_DEFAULT | 5.03 | 2.09 |
| vosk_small-en-us-0.15_WEBRTC_ANDROIDVAD | 6.03 | 2.55 |
| vosk_small-en-us-0.15_TEN_DEFAULT | 5.03 | 1.97 |
| whispercpp_tiny_NONE | 10.05 | 8.92 |
| whispercpp_tiny_SILERO | 9.55 | 8.57 |
| whispercpp_tiny_PYA3_DEFAULT | 10.55 | 9.27 |
| whispercpp_tiny_FSMN_DEFAULT | 10.55 | 9.27 |
| whispercpp_tiny_WEBRTC_ANDROIDVAD | 10.05 | 9.04 |
| whispercpp_tiny_TEN_DEFAULT | 11.06 | 9.39 |

- 참고: zipformer-ko 의 WER 이 높은 주된 이유는 출력에 띄어쓰기가 거의 없어서입니다(CER 이 더 의미 있음). vosk-ko 는 WER 100 % 초과(삽입 오류 많음).
- whisper tiny × {NONE, WEBRTC_ANDROIDVAD} 수치는 T30(같은 음원, 다른 run)과 같습니다(재현성).

### 3.7 전체 54파일 × whisper tiny × SILERO_DEFAULT — run `20261011_114231` (취소·이어하기)
- 11:42:31 시작 → 6건 완료 후 진행 탭 **취소 → 취소하기**(11:43:35 `RUN_CANCELLED`, 결과 탭 "미완료" · OK 6) →
  결과 탭 **이어하기**(11:43:59 `RUN_START resume=true`) → 11:54:07 완료(`RUN_END ok=48`). 총 **54건 OK · NO_MATCH 0 · ERROR 0**,
  manifest `resume: true`, `inFlight: null`. 경과 11분 36초(대기 24초 포함). RTF 평균 0.136, PSS 최대 587 MB.
- wake lock: `dumpsys power` 에 `STT_OnDevice:BatchRun` PARTIAL_WAKE_LOCK 이 실행 중에만 ACQ, 취소·완료 때 REL 로 기록(T27 수동 확인 5번).
- PC 평가기(kr·en rc 0, 매칭 실패 0) — **kr 24 발화·en 30 발화 모두 페어링**. 휴대폰 시나리오 폴더명(`Customer_Service`, `Friends_Conversation`, `vLog`)이
  평가기 `norm_scenario` 로 정답지(`kr/customer.txt`·`friend.txt`·`vLog.txt`, `en/Customer_Service.txt` 등)와 매칭됨:

| 언어 | model | scenario | n_utt | WER(%) | CER(%) |
|---|---|---|---|---|---|
| kr | whispercpp_tiny_SILERO_DEFAULT | (전체) | 24 | 33.44 | 16.52 |
| kr | 〃 | customer | 8 | 38.83 | 19.73 |
| kr | 〃 | friend | 8 | 28.73 | 12.75 |
| kr | 〃 | vLog | 8 | 31.43 | 15.97 |
| en | whispercpp_tiny_SILERO_DEFAULT | (전체) | 30 | 6.79 | 6.02 |
| en | 〃 | customer | 10 | 9.20 | 7.71 |
| en | 〃 | friend | 10 | 5.74 | 7.21 |
| en | 〃 | vLog | 10 | 5.56 | 3.63 |

(WER/CER 은 micro 값. 모델 1개·프리셋 1개 smoke 이며 조합 우열 결론이 아닙니다.)

### 3.8 화면 끔 10분 이상 동안 진행 지속 (T27 이월)
- 1차 시도(run `20261011_114231`, 이어하기 직후 11:44:07 `input keyevent 26`): 26초 뒤 11:44:33 **물리 전원 버튼**으로 화면이 켜졌습니다
  (logcat `Waking up from Dozing … reason=power_button, details=android.policy:POWER` — 우리 명령 아님). 그래서 10분 확인으로 인정하지 않고 다시 했습니다.
- 2차 시도(run `20261011_115617`, 54파일 × whisper tiny × **NONE**): 시작 직후 11:56:23 화면 끔 → 12:08:03 `keyevent 224` 로 켤 때까지
  **11분 40초 동안 켜짐 이벤트 없음**(logcat `Screen__On` 없음, 매분 `mWakefulness=Dozing`).
  그동안 `_logs` 의 ITEM_END 가 0 → 2 → 3 → 5 → 7 → 9 → 10 → 12 → 14 → 15 → 17 로 **계속 늘었습니다**(화면이 꺼져도 실행 지속 확인).
  12:20:24 완료: **OK 54 · NO_MATCH 0 · ERROR 0**, 24분 7초.
- 관찰: 화면이 꺼진 동안 끝난 20건의 RTF 평균 0.451, 켠 뒤 34건 0.259. 다만 기기 발열 상태(`thermalStatus`)가 이 run 내내 2~3(MODERATE~SEVERE)
  이었고 앞선 연속 실행(매트릭스에서는 4=CRITICAL 도 6건)의 열이 남아 있어, 속도 차이가 화면 끔 때문인지 발열 때문인지는 가리지 못했습니다.
- PC 평가기(kr·en rc 0, 매칭 실패 0, kr 24·en 30 발화 페어링):

| 언어 | model | scenario | n_utt | WER(%) | CER(%) |
|---|---|---|---|---|---|
| kr | whispercpp_tiny_NONE | (전체) | 24 | 36.31 | 18.79 |
| kr | 〃 | customer / friend / vLog | 8 / 8 / 8 | 41.58 / 31.47 / 34.56 | 22.31 / 14.18 / 18.55 |
| en | whispercpp_tiny_NONE | (전체) | 30 | 8.61 | 7.23 |
| en | 〃 | customer / friend / vLog | 10 / 10 / 10 | 10.90 / 6.86 / 8.06 | 8.90 / 8.14 / 5.07 |

(smoke 수치. 같은 54파일에서 tiny 는 SILERO_DEFAULT(3.7)가 NONE 보다 kr·en 모두 WER 이 낮았지만 모델 1개 비교일 뿐입니다.)

### 3.9 오프라인(비행기 모드) 확인 — **미수행**
사용자 기기 시스템 설정을 바꾸지 않는다는 결정(R44)에 따라 하지 않았습니다. 사용자 확인 절차:
1. 앱에서 쓸 모델이 모두 "설치됨" 인지 모델 탭에서 확인합니다.
2. 휴대폰 빠른 설정에서 **비행기 모드**를 켭니다(Wi-Fi·모바일 데이터 모두 꺼졌는지 상태 표시줄 확인).
3. 앱 **실행** 탭에서 모델 1개 × 프리셋 1개(예: whisper tiny × SILERO) × 파일 1개를 실행합니다.
4. 진행 탭에서 "실행 완료: OK 1건" 을 확인하고, 결과 탭 미리보기에 전사 텍스트가 보이는지 확인합니다.
5. 비행기 모드를 끕니다. `tools/adb/pull_results.sh` 로 받아 `run_manifest.json` 의 시작·종료 시각이 비행기 모드 시간대인지 확인합니다.

## 4. 엔진·모델별 완료 표 (요구사항 문서 §12 형식)

- 체크포인트 확인 = `tools/hash_models.py` 로 실제 다운로드·크기·SHA-256 고정(T18). Android build = 2절 APK 에 엔진 포함(release 는 3.3 에서 tiny·vosk ko 실행 확인).
- 실제 load·file 전사 = S22 Ultra. "배치" 는 3.6·3.7 의 앱 배치 실행(전체 파일), "계측" 은 계측 테스트(0–20/30 s 구간).
- mic = 녹음 → WAV → 같은 배치 경로(T30, 모델 공통). streaming = 실시간 partial UI 는 Phase 2 범위(미지원). offline = 3.9 미수행.
- 정확도 확인 = 사용자 PC 평가기로 실제 기기 결과 채점(smoke).

| 엔진·모델 | 체크포인트 확인 | Android build | 실제 load | file 전사 | mic/streaming | offline | 정확도 확인 | 미완료·원인 |
|---|---|---|---|---|---|---|---|---|
| whispercpp tiny | 수행 | 성공(debug·release) | 성공(0.07 s) | 성공(배치 2파일×6프리셋, 54파일 2회) | mic 녹음→전사 경로 공통 / streaming 미지원 | 미수행 | 수행(3.6·3.7) | S25·오프라인 미수행 |
| whispercpp base | 수행 | 성공 | 성공(계측) | 성공(계측 0–5 s) | 위와 같음 | 미수행 | 미수행 | 배치·채점 미수행 |
| whispercpp small | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 기기 미설치(466 MB) |
| whispercpp medium | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 미설치(1.46 GB). 메모리·발열 미측정 |
| whispercpp large-v3-turbo-q5_0 | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 미설치 |
| whispercpp large-v3-turbo-q8_0 | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 미설치 |
| sherpa-zipformer ko-2024-06-24 | 수행 | 성공 | 성공(2.15 s) | 성공(배치 kr 1파일×6) | 위와 같음 | 미수행 | 수행(3.6, WER 90 % 대) | 띄어쓰기 거의 없음(WER 불리), 라이선스 UNVERIFIED |
| sherpa-zipformer en-2023-06-26 | 수행 | 성공 | 성공(T21 계측) | 성공(계측) | 위와 같음 | 미수행 | 미수행 | 이번 기기에 미설치(계측 건너뜀) |
| sherpa-zipformer-streaming ko-2024-06-16 | 수행 | 성공 | 성공(T21 계측) | 성공(계측, chunk 단위) | 스트리밍 모델이나 Phase 1 은 배치 전사만 | 미수행 | 미수행 | 라이선스 UNVERIFIED |
| sherpa-sensevoice small-int8 | 수행 | 성공 | 성공(T21 계측) | 성공(계측 ko·en) | 위와 같음 | 미수행 | 미수행 | 상업 CONDITIONAL |
| sherpa-qwen3asr 0.6B-int8 | 수행 | 성공 | 성공(6.20 s) | 성공(배치 2파일×6) | 위와 같음 | 미수행 | 수행(3.6) | PSS 최대 3.7 GB, 취소는 chunk decode 끝까지 |
| sherpa-omnilingual ctc300m-v1-int8 | 수행 | 성공 | 성공(T21 계측) | 성공(계측) | 위와 같음 | 미수행 | 미수행 | 배치 미수행 |
| sherpa-omnilingual ctc300m-v2-int8 | 수행 | 성공 | 성공(T21 계측) | 성공(계측) | 위와 같음 | 미수행 | 미수행 | 배치 미수행 |
| sherpa-omnilingual ctc1b-v2-int8 | 수행 | 성공 | 성공(T21 계측) | 성공(계측) | 위와 같음 | 미수행 | 미수행 | 배치·메모리 측정 미수행 |
| vosk small-ko-0.22 | 수행 | 성공(debug·release) | 성공(3.53 s) | 성공(배치 kr 1파일×6) | 위와 같음 | 미수행 | 수행(3.6, WER 100 % 초과) | 실행마다 출력이 조금씩 다름(7절) |
| vosk small-en-us-0.15 | 수행 | 성공(debug) | 성공(0.82 s) | 성공(배치 en 1파일×6) | 위와 같음 | 미수행 | 수행(3.6) | release 는 ko 만 확인 |

## 5. VAD 계열별 정합성 (골든 테스트)과 기기 확인

골든 = `tools/golden/`·`tools/export/` 가 Python 원본 구현으로 만든 구간·점수 JSON(클립 4개: kr 20 s, en 20 s, 합성 무음·잡음 8 s, kr 70 s, 길이는 hop 배수가 아님 — R14).

| 계열 | 골든 비교 결과 | 허용오차 | 근거 | 기기 확인(S22 Ultra) |
|---|---|---|---|---|
| Silero v6.2.3 | 후처리 42개 골든 **샘플 단위 완전 일치**, ORT 전체 경로 4 클립 경계 차이 0.0 s | 1 샘플(실제 0) | T8, T12 | **통과** — 배치 SILERO(3.6), SILERO_DEFAULT 54파일(3.7) |
| pyannote seg-3.0 | 16개 골든 경계 차이 0.0 s, ORT 경로 0.0 s | 1 프레임(실제 0) | T9, T12 | **통과** — 배치 PYA3_DEFAULT(3.6) |
| FSMN (FunASR) | 8 조합 구간 ms 정확 일치, sil 점수 최대 차이 0.0295 | 점수 0.05(R15), 구간 10 ms(실제 0) | T9, T11, T12 | **통과** — 배치 FSMN_DEFAULT(3.6) |
| WebRTC (libfvad) | 후처리 12개 골든 비트 일치, 기기 libfvad 판정 = py-webrtcvad(불일치 0 프레임) | 0 | T10, T15, 3.2 | **통과** — 계측 + 배치 WEBRTC_ANDROIDVAD |
| TEN VAD | 11개 골든 비트 일치, 기기 확률 최대 오차 2.03e-6 | 확률 1e-4 | T10, T15, 3.2 | **통과** — 계측 + 배치 TEN_DEFAULT |
| FireRedVAD | OFFLINE 5개·STREAM 골든 정확 일치, 확률 오차 ≤ 1.23e-5 | 확률 1e-3 | T13, T14 | 미수행(모델 미설치) |
| MarbleNet v2.0 | 4개 골든 float32 정확 일치, 확률 오차 ≤ 1.79e-7 | 확률 1e-3 | T13, T14 | 미수행(모델 미설치) |
| Yamnet | 기기 점수 1316/1317 프레임 비트 일치, 1 프레임 1/256 차이, 구간 3/3 일치 | 다른 프레임 ≤ 1 %, 차이 ≤ 1/256 (R20) | T16, 3.2 | **통과**(계측), 배치 미수행 |
| NONE | 고정 길이 분할(ChunkPlanner 단위 테스트) | — | T5 | **통과** — 배치(3.6, 3.8) |

## 6. 설계·계획과 달라진 결정(ruling) 요약

| 번호 | 내용 |
|---|---|
| R9 | PC `STT_CSV_HEADER` 는 59열이 아니라 **60열**(PC 원본 기준, 문서 정정) |
| R10 | 전사 txt 는 임시 파일 → rename 원자적 쓰기, 재개 시 진행 중이던 항목의 부분 결과 정리 |
| R11 | FSMN_DEFAULT 최대 구간 60000 ms 유지(ONNX 패키지 config 는 20000 — 프리셋 note) |
| R14 | 골든 클립 길이를 hop 배수가 아니게 하고 70 s 긴 클립·max_speech 합성 골든 추가 |
| R15 | FSMN 점수 허용폭 0.05(kissfft 비트 재현 불가), 구간은 ms 정확 일치 |
| R17 | FIRERED_STREAM smoothWindow 0 → 5(원본 기본값) |
| R18 | MarbleNet 끝 프레임 가짜 구간은 NeMo 동작 그대로 유지(note), 길이 초과는 ChunkPlanner 가 자름 |
| R20 | Yamnet 점수 기준 "다른 프레임 ≤ 1 %, 최대 차이 ≤ 1/256"(int8 모델) |
| R22 | VAD 캐시가 프레임 점수(`<stem>.scores.f32`)도 저장 — 두 번째 모델부터도 같은 분할(엔진 간 공정성) |
| R23 | SenseVoice 는 원본 변환본 2024-07-17 int8 로 고정(최신본은 광둥어 미세조정) |
| R24 | 한국어 zipformer 2종 라이선스 UNVERIFIED, FSMN CONDITIONAL 유지 |
| R26 | whisper 취소는 whisper.cpp 확인 지점에 묶임(tiny ≤ 1 s, base 이상은 기록) |
| R28 | sherpa AAR 에서 arm64 JNI `.so` 만 추출(중복 ORT·클래스 제거, pickFirst 없이) |
| R29 | Qwen3 maxNewTokens 512 / maxTotalLen 1024, ORT 두 벌(1.30.0 + sherpa 정적 1.28.2) 공존 |
| R30 | Vosk 는 끝점마다 결과를 모아 finalResult 와 결합(앞 발화 소실 방지), threads 는 실제값 1 |
| R33 | 모델 로드·VAD 준비 중 크래시도 재개 시 `CRASH_OR_KILLED` 로 기록하고 건너뜀 |
| R36 | 조합별 `result.csv` 를 평가 폴더 밖 `_results/` 로 이동(평가기 유령 모델 방지), spec §11 갱신 |
| R37 | CSV 공백 정규식 `(?U)\s` → 명시 문자 집합(Android ICU 미지원, 기기에서만 발견) |
| R38 | wake lock 6 h(항목마다 연장), 실행 시 MODEL_NOT_READY 거부 |
| R40 | manifest 에 입력 목록 저장, 이어하기는 같은 설정·입력일 때만 |
| R41·R43 | adb push 폴더 권한 문제 → 스크립트가 shell 소유 항목만 chmod |
| R44 | 비행기 모드 등 기기 설정 변경 금지. 기기 테스트 산출물은 목록 보고 후 사용자 승인으로 정리(8절) |
| R45 | release 시작 크래시를 1차 범위에서 수정 — Room 생성 클래스 생성자 keep 규칙 추가(3.3) |

## 7. 알려진 문제·미완료와 다음 조치

| 항목 | 상태 | 다음 조치 |
|---|---|---|
| release 빌드 | 시작 크래시를 keep 규칙으로 수정, tiny·vosk ko 확인(3.3) | 다른 엔진(sherpa 등)의 release 실행은 미확인 — 필요 시 같은 방식으로 확인 |
| **Vosk 출력 비결정성** | 같은 파일·설정에서 실행마다 Vosk 텍스트가 조금씩 다름(3.3: debug 2회·release 1회 모두 다름) | 원인 조사(블록 입력 경계·Vosk 내부 설정), 평가 시 반복 실행 평균 고려 |
| S25 Ultra 측정 | 미수행(기기 없음) | S25 Ultra 연결 후 3.6~3.8 반복 |
| 오프라인 확인 | 미수행(R44) | 3.9 절차를 사용자가 수행 |
| 16 KB 페이지 기기 실행 | 미수행(S22 는 4 KB) | ELF·zip 정렬만 확인됨. 16 KB 기기·에뮬레이터에서 설치·실행 |
| whisper small/medium/large-turbo 기기 실행 | 미수행 | 설치 후 배치, PSS·발열 확인 |
| SenseVoice·Zipformer EN·스트리밍 KO·Omnilingual 배치 | 계측 테스트만(T21) | 설치 후 배치·채점 |
| FireRed·MarbleNet·Yamnet 배치 | 미수행 | `tools/export/out` 모델을 push_models 로 넣고 배치 |
| SAF 모델 가져오기 취소 | 기능 없음("검증 중" 표시) | 필요 시 취소 버튼 추가 |
| 한국어 zipformer 띄어쓰기 | 출력에 띄어쓰기 거의 없음 → WER 90 % 대(3.6) | PC sherpa 결과와 비교, CER 위주로 판단 |
| vosk-ko WER 100 % 초과 | 삽입 오류 많음(3.6, 파일 1개) | 전체 파일로 재확인 |
| 라이선스 UNVERIFIED | 한국어 zipformer 2종(KsponSpeech 이용조건 미확인) | 상업 이용 전 확인 |
| PC 평가기 EN 정답의 화자 라벨 | EN WER 과대 가능 | 평가기 쪽 별건 |
| LiteRT 병합 권한·WorkManager(R21) | 유지 중(WorkManager 는 keep 규칙으로 release 에서도 동작) | 필요 시 재평가 |

## 8. 기기 테스트 산출물 정리 (2026-10-11 정리 완료)

사용자 승인에 따라 이전 작업들이 기기에 남긴 산출물을 정리했습니다. 다른 사용자 파일(`/sdcard/Documents/input_sound` 등)과 다른 앱 데이터는 읽기만 했습니다.

| 지운 것 | 방법 |
|---|---|
| `/sdcard/Download/stt_export_test/`(T30 내보내기 사본 2개), `/sdcard/Download/stt_saf_in/`, `/sdcard/Download/stt_saf_model/` | `adb shell rm -rf` (승인 항목) |
| 앱 폴더의 이전 산출물: run `20261011_035022`·`035151`, 마이크 테스트 녹음, `kr/stt_saf_in`, push 테스트 음원, `import/` 원본, `tmp/whispercpp-small.dl`·`medium.dl`(부분 다운로드) | `connectedDebugAndroidTest` 종료 시 앱 제거로 함께 삭제(승인 항목) |
| 이번 작업의 `import/` 원본(8개 모델, 약 1.3 GB) | 설치 완료 후 `adb shell rm -rf …/import/*` |
| 이번 작업의 UI 덤프 임시 파일(`/data/local/tmp/t31_ui.xml`) | 작업 끝에 삭제 |

지금 기기에 남아 있는 것(이번 작업 결과 — 사용자가 계속 쓸 수 있는 상태), 앱 폴더 `A=/sdcard/Android/data/com.cowork.stt_ondevice/files`:

| 경로 | 내용 |
|---|---|
| debug 앱 `com.cowork.stt_ondevice` | 이번 빌드(debug 키 서명, R45 keep 규칙 포함) |
| `$A/models/` | whispercpp-tiny, sherpa-zipformer-ko, sherpa-qwen3asr 0.6B, vosk ko/en, vad silero/pyannote/fsmn (설치됨) |
| `$A/input_sound/{kr,en}/{Customer_Service,Friends_Conversation,vLog}/` | `Documents/input_sound` 에서 SAF 로 가져온 사용자 음원 54개(사본) |
| `$A/output_stt/20261011_112228` | 매트릭스 smoke 결과(3.6) |
| `$A/output_stt/20261011_114231` | 54파일 × tiny × SILERO_DEFAULT(3.7) |
| `$A/output_stt/20261011_115617` | 54파일 × tiny × NONE(3.8 화면 끔 확인) |
| `$A/output_stt/20261011_122655`, `20261011_122926` | release 확인 run(4건), debug Vosk 반복성 확인 run(1건) — 3.3 |
| 앱의 SAF 접근 권한 | `Documents/input_sound` 폴더 읽기 허용(가져오기 때 "허용") |
