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
| 2. 엔진 × VAD × 입력 배치가 끝까지 실행, 실패는 ERROR 로 남고 계속 | **통과(S22 Ultra)** | T25(JVM 오류 격리 테스트), T27·T29·T30(기기 배치), 3.3절 |
| 3. PC `run_eval.py` 에서 모든 조합이 `<algo>_<model>_<VAD>` 로 페어링 | **통과** | T26(`tools/check_pairing.sh`), T30·3.6절(실제 기기 결과 채점) |
| 4. VAD Kotlin 포팅이 Python 원본과 허용오차 내 일치 | **통과(9 계열)** | 5절 |
| 5. 측정 불가 값은 빈 값 | **통과** | T23·T25(빈 값 규칙 테스트) |

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

## 3. 3차 검증 — 실기기 (Galaxy S22 Ultra)

### 3.1 이전 작업에서 수행한 기기 검증 (근거)
| 항목 | 결과 | 근거 |
|---|---|---|
| WebRTC(libfvad)·TEN 네이티브 골든 | 20건 일치(WebRTC 판정 불일치 0 프레임, TEN 확률 최대 오차 2.03e-6) | T15 |
| Yamnet(LiteRT) | 1317 중 1316 프레임 비트 일치, 1 프레임 1/256 차이, 3 프리셋 구간 완전 일치 | T16 |
| whisper.cpp tiny·base load/전사/중단 | tiny load 0.084 s, 0–20 s 전사 2.5 s, 중단 36.8 ms / base 중단 약 0.9~1.0 s | T20 |
| sherpa-onnx 8개 모델 load/전사 | 아래 4절 표, `OK (14 tests)` | T21 |
| Vosk ko·en load/전사 | `OK (2 tests)`, 앞·뒤 단어 포함 단언 | T22 |
| 기기 정보·자원 측정 | 발열·배터리·PSS 실측 확인 | T23 |
| 서비스 배치·취소 | 실제 배치·취소 성공, 기기에서만 나는 CSV 정규식 버그 발견·수정(R37) | T27 |
| 모델 화면 | 다운로드·진행률·취소·이어받기·설치 | T28 |
| 실행·진행·결과 화면 | whisper tiny × {NONE, WEBRTC_ANDROIDVAD} × 2 파일 실행 → 취소 → 이어하기 → 완료 | T29 |
| SAF·마이크·adb 스크립트 | push 스크립트 권한 정리, 마이크 16 kHz mono 저장, SAF 내보내기 md5 일치, PC 채점 | T30 |

### 3.2 이번 작업(Task 31)의 기기 준비 — 2026-10-11
1. `adb install -r app-debug.apk`(데이터 유지).
2. `tools/adb/push_models.sh` 로 5개 모델을 `files/import/<id>/` 에 넣음(PC 캐시 `tools/models_cache` 의 원본 압축/파일):
   vosk-small-ko-0.22(zip), vosk-small-en-us-0.15(zip), sherpa-zipformer-ko-2024-06-24(tar.bz2), sherpa-qwen3asr-0.6b-int8-2026-03-25(tar.bz2),
   vad-fsmn-zh-cn-16k-onnx(파일 3개를 한 폴더로).
3. 앱 **모델** 탭의 "가져오기 → 가져오기 실행" 을 uiautomator 로 눌러 설치(압축 해제·SHA-256 검증):
   Vosk KO·Vosk EN·Zipformer KO·Qwen3 0.6B → **"가져오기와 검증을 마쳤습니다" / 설치됨**. (whisper tiny·base, Silero, pyannote 는 이전 작업에서 설치됨)
   FSMN 은 push 까지만 하고 가져오기는 하지 못했습니다(3.3 참고).
4. `tools/adb/push_inputs.sh` 로 임시 트리(kr/customer/01.wav, en/Customer_Service/01.wav)를 보냄 → "권한 확인: 모두 앱에서 읽기·쓰기 가능".

### 3.3 배치 smoke (모델 × VAD 프리셋 × 2 파일), 화면 끔, 취소·이어하기 — **보류(미수행)**
- 계획: {whispercpp tiny, sherpa-zipformer ko, sherpa-qwen3asr 0.6B, vosk ko/en} × {NONE, SILERO, PYA3_DEFAULT, FSMN_DEFAULT, WEBRTC_ANDROIDVAD, TEN_DEFAULT} × 2 파일,
  중간 취소 → 결과 탭 이어하기, 실행 중 화면 10분 이상 끔.
- 보류 이유: 작업 중 기기를 사용자가 직접 쓰고 있었습니다(10:10, 10:12, 10:30 에 홈 → `com.example.google_stt` 실행 기록, logcat `ActivityTaskManager START … from uid 10165(런처)`).
  UI 자동화·화면 끄기·장시간 CPU 부하가 사용자의 작업을 방해하므로 멈췄습니다. 기기를 비워 둘 수 있을 때 이어서 수행합니다.

### 3.4 release 빌드 기기 확인(R31·R44) — **미수행(3.3 과 같은 이유)**
- 절차(예정): release APK 를 로컬 debug 키로 `apksigner` 서명 → 설치 → Vosk ko 1개·whisper tiny 1개 전사 → debug 재설치.

### 3.5 `connectedDebugAndroidTest` — **미수행(3.3 과 같은 이유)**
- 이 테스트는 끝나면 앱을 지워 앱 폴더(모델·음원·결과)가 사라지므로 배치 결과를 PC 로 받은 뒤 마지막에 실행합니다.

### 3.6 PC 평가기 채점 (실제 기기 결과, smoke)
- T30 의 whisper tiny 결과(run 20261011_035151, 파일 1개/언어)를 `pull_results.sh` 로 받아 평가기로 채점(rc 0, 매칭 실패 0):

| 언어 | model | n_utt | WER(%) | CER(%) |
|---|---|---|---|---|
| kr | whispercpp_tiny_NONE | 1 | 24.67 | 11.48 |
| kr | whispercpp_tiny_WEBRTC_ANDROIDVAD | 1 | 29.33 | 11.71 |
| en | whispercpp_tiny_NONE | 1 | 10.05 | 8.92 |
| en | whispercpp_tiny_WEBRTC_ANDROIDVAD | 1 | 10.05 | 9.04 |

- 파일 1개짜리 smoke 수치입니다. 엔진·VAD 우열 결론으로 쓰면 안 됩니다.

### 3.7 오프라인(비행기 모드) 확인 — **미수행**
사용자 기기 시스템 설정을 바꾸지 않는다는 결정(R44)에 따라 하지 않았습니다. 사용자 확인 절차:
1. 앱에서 쓸 모델이 모두 "설치됨" 인지 모델 탭에서 확인합니다.
2. 휴대폰 빠른 설정에서 **비행기 모드**를 켭니다(Wi-Fi·모바일 데이터 모두 꺼졌는지 상태 표시줄 확인).
3. 앱 **실행** 탭에서 모델 1개 × 프리셋 1개(예: whisper tiny × SILERO) × 파일 1개를 실행합니다.
4. 진행 탭에서 "실행 완료: OK 1건" 을 확인하고, 결과 탭 미리보기에 전사 텍스트가 보이는지 확인합니다.
5. 비행기 모드를 끕니다. `tools/adb/pull_results.sh` 로 받아 `run_manifest.json` 의 시작·종료 시각이 비행기 모드 시간대인지 확인합니다.

## 4. 엔진·모델별 완료 표 (요구사항 문서 §12 형식)

- 체크포인트 확인 = `tools/hash_models.py` 로 실제 다운로드·크기·SHA-256 고정(T18). Android build = 2절 APK 에 엔진 포함.
- 실제 load·file 전사 = S22 Ultra 계측 테스트(0–20 s 또는 0–30 s 구간). mic = 녹음 → WAV → 같은 배치(T30, 모든 모델 공통 경로).
- streaming = 실시간 partial UI 는 Phase 2 범위(미지원). offline = 3.7 미수행.

| 엔진·모델 | 체크포인트 확인 | Android build | 실제 load | file 전사 | mic/streaming | offline | 정확도 확인 | 미완료·원인 |
|---|---|---|---|---|---|---|---|---|
| whispercpp tiny | 수행 | 성공 | 성공(0.084 s, T20) | 성공(0–20 s 2.5 s; 배치 전 파일 T29) | mic 녹음→전사 경로 공통 / streaming 미지원 | 미수행 | PC 채점 smoke(3.6) | 오프라인·S25 미수행 |
| whispercpp base | 수행 | 성공 | 성공(T20) | 성공(0–5 s, T20) | 위와 같음 | 미수행 | 미수행 | PC 채점 미수행 |
| whispercpp small | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 기기에 미설치(466 MB) — 기기 load·전사 미수행 |
| whispercpp medium | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 미설치(1.46 GB, f16). 메모리·발열 미측정 |
| whispercpp large-v3-turbo-q5_0 | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 미설치 |
| whispercpp large-v3-turbo-q8_0 | 수행 | 성공 | 미수행 | 미수행 | 위와 같음 | 미수행 | 미수행 | 미설치 |
| sherpa-zipformer ko-2024-06-24 | 수행 | 성공 | 성공(1.36~1.93 s, T21) | 성공(0–20 s) | 위와 같음 | 미수행 | 미수행 | 출력 띄어쓰기가 거의 없음(WER 불리), 라이선스 UNVERIFIED |
| sherpa-zipformer en-2023-06-26 | 수행 | 성공 | 성공(T21) | 성공(0–20 s) | 위와 같음 | 미수행 | 미수행 | 출력이 대문자(평가기 정규화 확인 필요) |
| sherpa-zipformer-streaming ko-2024-06-16 | 수행 | 성공 | 성공(T21) | 성공(0–20 s, chunk 단위) | 스트리밍 모델이지만 Phase 1 은 배치 전사만 | 미수행 | 미수행 | 라이선스 UNVERIFIED, 띄어쓰기 거의 없음 |
| sherpa-sensevoice small-int8 | 수행 | 성공 | 성공(T21) | 성공(ko·en, 태그 제거 확인) | 위와 같음 | 미수행 | 미수행 | 상업 CONDITIONAL(FunASR Model License) |
| sherpa-qwen3asr 0.6B-int8 | 수행 | 성공 | 성공(3.5~5.3 s, T21) | 성공(0–30 s, 잘림 없음) | 위와 같음 | 미수행 | 미수행 | 취소는 chunk decode 끝까지(30 s chunk ≈ 14 s) |
| sherpa-omnilingual ctc300m-v1-int8 | 수행 | 성공 | 성공(T21) | 성공(0–20 s) | 위와 같음 | 미수행 | 미수행 | |
| sherpa-omnilingual ctc300m-v2-int8 | 수행 | 성공 | 성공(T21) | 성공(0–20 s) | 위와 같음 | 미수행 | 미수행 | |
| sherpa-omnilingual ctc1b-v2-int8 | 수행 | 성공 | 성공(3.0~3.9 s, T21) | 성공(0–20 s) | 위와 같음 | 미수행 | 미수행 | 메모리·발열 배치 측정 미수행 |
| vosk small-ko-0.22 | 수행 | 성공 | 성공(T22) | 성공(앞·뒤 단어 확인) | 위와 같음 | 미수행 | 미수행 | release(R8) 기기 확인 미수행(3.4) |
| vosk small-en-us-0.15 | 수행 | 성공 | 성공(T22) | 성공(앞·뒤 단어 확인) | 위와 같음 | 미수행 | 미수행 | 위와 같음 |

## 5. VAD 계열별 정합성 (골든 테스트)과 기기 확인

골든 = `tools/golden/`·`tools/export/` 가 Python 원본 구현으로 만든 구간·점수 JSON(클립 4개: kr 20 s, en 20 s, 합성 무음·잡음 8 s, kr 70 s, 길이는 hop 배수가 아님 — R14).

| 계열 | 골든 비교 결과 | 허용오차 | 근거 | 기기 확인(S22 Ultra) |
|---|---|---|---|---|
| Silero v6.2.3 | 후처리 42개 골든(8 프리셋 + 손작성 + TEN_SHERPA) **샘플 단위 완전 일치**, ORT 전체 경로 4 클립 경계 차이 0.0 s | 1 샘플(실제 0) | T8, T12 | 배치 미수행(3.3 보류) |
| pyannote seg-3.0 | 16개 골든 경계 차이 0.0 s, ORT 경로 0.0 s | 1 프레임(실제 0) | T9, T12 | 배치 미수행 |
| FSMN (FunASR) | 8 조합 구간 ms 정확 일치, sil 점수 최대 0.0295 | 점수 0.05(R15), 구간 10 ms(실제 0) | T9, T11, T12 | 미수행 |
| WebRTC (libfvad) | 후처리 12개 골든 비트 일치, 기기 libfvad 판정 = py-webrtcvad(불일치 0 프레임) | 0 | T10, T15 | **통과**(T15 계측, T29·T30 배치 WEBRTC_ANDROIDVAD) |
| TEN VAD | 11개 골든 비트 일치, 기기 확률 최대 오차 2.03e-6 | 확률 1e-4 | T10, T15 | **통과**(T15 계측) |
| FireRedVAD | OFFLINE 5개·STREAM 골든 정확 일치, 확률 오차 ≤ 1.23e-5 | 확률 1e-3 | T13, T14 | 미수행(모델 미설치) |
| MarbleNet v2.0 | 4개 골든 float32 정확 일치, 확률 오차 ≤ 1.79e-7 | 확률 1e-3 | T13, T14 | 미수행(모델 미설치) |
| Yamnet | 기기 점수 1316/1317 프레임 비트 일치, 1 프레임 1/256 차이, 구간 3/3 일치 | 다른 프레임 ≤ 1 %, 차이 ≤ 1/256 (R20) | T16 | **통과**(계측 테스트) |
| NONE | 고정 길이 분할(ChunkPlanner 단위 테스트) | — | T5 | **통과**(T29·T30 배치) |

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
| R44 | 비행기 모드 등 기기 설정 변경 금지, 기기 테스트 산출물은 삭제하지 않고 목록만 보고 |

## 7. 알려진 문제·미완료와 다음 조치

| 항목 | 상태 | 다음 조치 |
|---|---|---|
| S25 Ultra 측정 | 미수행(기기 없음) | S25 Ultra 연결 후 3.3 배치·성능 측정 반복 |
| 3.3 배치 smoke·화면 끔 10분·취소/이어하기 | 보류(기기 사용 중) | 기기를 비울 수 있을 때 수행 |
| release(R8) 기기 확인 | 보류 | 3.4 절차 |
| connectedDebugAndroidTest 전체 | 보류 | 결과 pull 후 실행 |
| 오프라인 확인 | 미수행(R44) | 3.7 절차를 사용자가 수행 |
| 16 KB 페이지 기기 실행 | 미수행(S22 는 4 KB) | ELF·zip 정렬만 확인됨. 16 KB 기기·에뮬레이터에서 설치·실행 |
| whisper small/medium/large-turbo 기기 실행 | 미수행 | 설치 후 배치, PSS·발열 확인 |
| FireRed·MarbleNet 기기 실행 | 미수행 | `tools/export/out` 모델을 push_models 로 넣고 배치 |
| SAF 모델 가져오기 취소 | 기능 없음("검증 중" 표시) | 필요 시 취소 버튼 추가 |
| 한국어 zipformer 띄어쓰기 | 출력에 띄어쓰기 거의 없음 → WER 불리 | PC sherpa 결과와 비교, CER 위주로 판단 |
| 라이선스 UNVERIFIED | 한국어 zipformer 2종(KsponSpeech 이용조건 미확인) | 상업 이용 전 확인 |
| PC 평가기 EN 정답의 화자 라벨 | EN WER 과대 가능 | 평가기 쪽 별건 |
| LiteRT 병합 권한(R21) | 유지 중 | 필요 시 재평가 |

## 8. 기기에 남아 있는 테스트 산출물 (삭제하지 않음 — 사용자 확인 후 정리)

R44 에 따라 지우지 않았습니다. 필요 없으면 직접 지우세요. 앱 폴더 `A=/sdcard/Android/data/com.cowork.stt_ondevice/files`.

| 경로 | 만든 작업 | 내용 |
|---|---|---|
| `/sdcard/Download/stt_export_test/20261011_035151`, `…_035151_1` | T30 | SAF 내보내기 확인용 복사본 |
| `/sdcard/Download/stt_saf_in/` (sub/01.wav, notes.txt) | T30 | SAF 음원 가져오기 확인용 |
| `/sdcard/Download/stt_saf_model/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` | T30 | SAF 모델 가져오기 확인용 |
| `$A/input_sound/kr/stt_saf_in/sub/01.wav` | T30 | SAF 로 가져온 음원 |
| `$A/input_sound/{kr,en}/mic/*.wav`, `*.info.txt` | T30 | 마이크 녹음 확인(테스트 녹음) |
| `$A/output_stt/20261011_035022`, `20261011_035151` | T29·T30 | 기기 배치 결과 |
| `$A/import/<model_id>/…` (vosk ko/en zip, zipformer-ko·qwen3 tar.bz2, fsmn 폴더, whisper tiny bin, silero dl0) | T28·T30·T31 | adb 로 넣은 가져오기 원본(설치 후에도 남음, 수백 MB~1 GB) |

- 앱 폴더 안의 항목은 앱을 지우거나 `connectedDebugAndroidTest` 를 실행하면 함께 사라집니다.
- `$A/tmp/whispercpp-small.dl`, `whispercpp-medium.dl`(09:36 의 부분 다운로드)은 이번 작업들이 만든 것이 아닌 것으로 보여 목록에서 뺐습니다.
- 정리 예: `adb shell rm -rf /sdcard/Download/stt_export_test` (직접 판단해 실행).
