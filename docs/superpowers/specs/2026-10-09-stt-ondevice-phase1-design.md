# STT_OnDevice Phase 1 설계서 — 엔진 × VAD 온디바이스 전사 비교 앱 (Android)

- 작성: 2026-10-09 (Asia/Seoul), 작성자 hyungchul + Claude
- 상태: 사용자 설계 승인 완료(인터뷰 3회), 본 문서 검토 대기
- 근거 자료: `docs/research/2026-10-09-engine-vad-research.md`,
  `~/Downloads/google_stt_개발/Android_iOS_ASR_STT_구현가능성_개발요구사항_20261008.md`,
  `~/Downloads/google_stt_개발/다른_AI_전달용_Android_ASR_STT_개발프롬프트_20261008.md`,
  PC Tool `~/workspace/python/asr_stt/stt_all_gui_2nd/` (출력 규칙·CSV 헤더·평가기 페어링)

## 1. 목적과 성공 기준

**목적**: API/라이선스 비용이 없고 상업 이용이 가능한 STT 엔진을 Galaxy S25 Ultra 안에서 직접 실행하여,
VAD(무음 제거 + 엔진 최대 입력길이 맞춤 cut)와의 **엔진 × VAD 조합**별 한국어·영어 전사 결과를 만들고,
사용자의 기존 **PC Python 평가 Tool(WER/CER)** 에 그대로 넣어 최적 조합을 찾는다.

**성공 기준 (Phase 1)**

1. Android Studio에서 Gradle sync·`assembleDebug` 성공, arm64 `.so` 전부 16 KB 정렬.
2. 앱에서 엔진/모델 다중 선택 × VAD 프리셋 다중 선택 × 입력 폴더(kr/en)로 배치 전사가 끝까지 실행되고,
   실패 항목은 `NN.ERROR.txt`와 CSV `status=ERROR`로 남고 나머지는 계속 실행된다.
3. 결과 폴더를 PC로 가져와 사용자의 `run_eval.py --ref answer_sheet/kr --hyp <run>/kr` 실행 시
   모든 조합이 `<algo>_<model>_<VAD>` 모델명으로 페어링된다.
4. 각 VAD의 Kotlin 포팅 결과가 Python 원본 구현의 구간과 허용오차 내로 일치한다(골든 테스트).
5. 측정 불가 값은 빈 값(null)으로 남기고, 추정치를 만들지 않는다.

**하지 않는 것 (Phase 1 범위 밖)**: 앱 내부 WER/CER 계산(PC 평가기 사용), 실시간 partial streaming UI,
Moonshine·ML Kit·Android SpeechRecognizer(Phase 2), 연구 3종 + Gemma 4 E2B(Phase 3), iOS(Phase 4), Cloud.

## 2. 로드맵

| Phase | 내용 |
|---|---|
| **1 (본 문서)** | 공통 골격, 오디오 입출력(adb+SAF), 모델 설치, VAD 9종+NONE 및 프리셋, ChunkPlanner, 엔진: whisper.cpp / sherpa-onnx(Zipformer KO·EN·streaming KO, SenseVoice, Qwen3-ASR 0.6B, Omnilingual CTC v1·v2·1B) / Vosk, 배치 실행, PC 호환 출력, 마이크 녹음→WAV |
| 2 | Moonshine(EN MIT / KO 비상업 표시), ML Kit GenAI Basic, Android on-device SpeechRecognizer, 실시간 streaming partial/final UI |
| 3 | 연구실: Qwen3-ASR 1.7B, Fun-ASR-MLT-Nano-2512, Nemotron 3.5 ASR 0.6B, Gemma 4 E2B(LiteRT-LM) |
| 4 | iOS (whisper.cpp, sherpa-onnx, Apple SpeechTranscriber/SFSpeechRecognizer) |

제외 결정: **Picovoice Cobra** — 무료(개인/비상업) 플랜 없음, AccessKey+온라인 검증 필수 → "유료 라이선스 없는 제품" 조건 불충족. 조사 노트에만 기록.

## 3. 확정 결정 사항 (인터뷰 결과)

| 항목 | 결정 |
|---|---|
| UI | Jetpack Compose (템플릿 XML/AppCompat 제거) |
| 모듈 | 단일 `:app` + 기능별 패키지 |
| minSdk / ABI | 31 / arm64-v8a 전용 |
| 모델 설치 | 앱 내 다운로드 + adb push/SAF import 모두 |
| 입출력 | adb 앱전용 외부폴더 + SAF 폴더 선택 모두 Phase 1 |
| 평가 | 앱은 전사만, WER/CER은 PC 평가기 |
| 출력 경로 | `<lang>/<algo>_<model>_<VAD>/<입력 상대폴더>/<음원명>.txt` |
| txt 형식 | `[전체 텍스트]` + `[구간별]` 헤더 형식 (PC faster-whisper 형식) |
| 구간 전달 | 병합 후 엔진 최대길이 cut(기본) + 구간별 개별 전사 모드 옵션 |
| 추가 엔진 | Phase1: Whisper large-v3-turbo, Omnilingual CTC v2 300M/1B. Phase2: Android SpeechRecognizer. Phase3: Gemma 4 E2B |
| 추가 VAD | TEN VAD, FireRedVAD, NVIDIA MarbleNet v2.0, Yamnet |
| 모델 변환 | 이 PC에 `tools/export/.venv`(CPU torch) 생성, NeMo는 별도 venv, 직접 변환·검증 |
| Git | `git init` + origin=`git@github.com:overthere1945/stt_onDevice.git`(원격 Initial commit 위에 구성), 로컬 커밋은 진행, **push는 사용자 요청 시에만** |

## 4. 빌드 구성

| 항목 | 값 | 비고 |
|---|---|---|
| AGP / Gradle | 9.4.1 / 9.6.0 | 기존 유지. AGP 9 built-in Kotlin → `org.jetbrains.kotlin.android` 미적용 |
| Kotlin plugins | `org.jetbrains.kotlin.plugin.compose`, `...serialization` 2.4.21 | |
| compileSdk / targetSdk / minSdk | 37 / 37 / 31 | |
| Java | 17 | Compose/Kotlin 2.4 권장 수준으로 상향 |
| NDK / CMake | 30.0.16248370 / 4.1.2 | 설치본 고정 |
| Compose BOM | 2026.09.00 | material3, activity-compose 1.13.0, lifecycle-viewmodel-compose 2.11.0, navigation-compose 2.10.2 |
| kotlinx | coroutines-android 1.11.0, serialization-json 1.11.0 | |
| sherpa-onnx | `app/libs/sherpa-onnx-static-link-onnxruntime-1.13.8.aar` + Kotlin API 소스 복사 | ORT 정적 링크 → libonnxruntime.so 충돌 없음. AAR은 `tools/fetch_native_deps.sh`로 받음(git 미포함) |
| ONNX Runtime | `com.microsoft.onnxruntime:onnxruntime-android:1.30.0` | Silero/pyannote/FSMN/FireRed/MarbleNet. JVM 테스트는 `com.microsoft.onnxruntime:onnxruntime:1.30.0` |
| whisper.cpp | v1.9.5, git submodule `app/src/main/cpp/third_party/whisper.cpp` | CMake 빌드, `-Wl,-z,max-page-size=16384` |
| libfvad (WebRTC VAD) | git submodule `app/src/main/cpp/third_party/libfvad` (BSD-3) | CMake 빌드 |
| TEN VAD | 공식 prebuilt `libten_vad.so`(arm64) + 헤더, `tools/fetch_native_deps.sh` | 자체 JNI 래퍼 |
| Vosk | `com.alphacephei:vosk-android:0.3.75` + `net.java.dev.jna:jna:5.18.1@aar` | keep rule `com.sun.jna.**` |
| LiteRT | Yamnet 실행용 (구현 착수 시 최신 artifact·16KB 확인) | android-vad의 구형 TFLite 미사용 |
| commons-compress | tar.bz2 / zip 압축 해제 | |
| packaging | `useLegacyPackaging=false`, **`pickFirst` 사용 금지** | 중복 .so 발생 시 원인 해결 |

## 5. 아키텍처와 파일 구성

```
app/src/main/
 ├ cpp/
 │  ├ CMakeLists.txt            whisper.cpp + libfvad + TEN 래퍼 빌드, 16KB 링크 옵션
 │  ├ whisper_jni.cpp           whisper 컨텍스트 생성/전사/중단/해제
 │  ├ fvad_jni.cpp              libfvad 생성/모드/프레임 판정/해제
 │  └ ten_vad_jni.cpp           ten_vad create/process/destroy
 └ java/com/cowork/stt_ondevice/
    ├ App.kt, MainActivity.kt
    ├ core/audio/   WavReader.kt, PcmConverter.kt, AudioClip.kt, MicRecorder.kt
    ├ core/storage/ AppDirs.kt, SafBridge.kt, InputScanner.kt
    ├ core/device/  DeviceInfo.kt, ResourceSampler.kt(PSS/열/배터리)
    ├ model/        ModelSpec.kt, ModelCatalog.kt, ModelStore.kt, ModelInstaller.kt, ArchiveExtractor.kt, Sha256.kt
    ├ vad/          VadEngine.kt, VadPreset.kt, VadPresets.kt, SpeechSegment.kt, VadCache.kt
    │  ├ feature/   Fbank.kt, Lfr.kt, Cmvn.kt, MelSpectrogram.kt
    │  ├ impl/      SileroVad.kt, PyannoteVad.kt, FsmnVad.kt, WebRtcVad.kt, TenVad.kt,
    │  │            FireRedVad.kt, MarbleNetVad.kt, YamnetVad.kt, NoneVad.kt
    │  └ post/      SileroTimestamps.kt, PyannoteBinarize.kt, FsmnStateMachine.kt,
    │               WebRtcRingBuffer.kt, NemoPostprocess.kt, FireRedPostprocess.kt, SimpleHangover.kt
    ├ segment/      ChunkPlanner.kt, AudioChunk.kt
    ├ engine/       AsrEngine.kt, EngineResult.kt, EngineRegistry.kt
    │  ├ whisper/   WhisperNative.kt, WhisperCppEngine.kt
    │  ├ sherpa/    SherpaOfflineEngine.kt, SherpaStreamingEngine.kt, SherpaConfigs.kt (+ com/k2fsa/sherpa/onnx/*.kt 복사본)
    │  └ vosk/      VoskEngine.kt
    ├ runner/       RunConfig.kt, BatchRunner.kt, RunProgress.kt, RunService.kt(foreground)
    ├ output/       OutputLayout.kt, TranscriptWriter.kt, ResultCsvWriter.kt, RunManifestWriter.kt, VadSegmentWriter.kt
    └ ui/           AppNav.kt, theme/, models/ModelsScreen.kt, run/RunSetupScreen.kt,
                    run/RunProgressScreen.kt, results/ResultsScreen.kt, mic/MicScreen.kt, *ViewModel.kt
tools/
 ├ fetch_native_deps.sh         sherpa AAR, TEN .so 다운로드 + SHA-256 확인
 ├ adb/push_inputs.sh, adb/pull_results.sh, adb/push_models.sh
 ├ export/                      FireRedVAD·MarbleNet ONNX 변환 (README, requirements*.txt)
 ├ golden/                      Python 원본 VAD로 골든 구간 JSON 생성
 └ hash_models.py               모델 다운로드·SHA-256 산출 → ModelCatalog 고정값
app/src/test/resources/golden/  골든 JSON + 짧은 테스트 WAV
```

각 단위의 책임과 의존:

| 단위 | 책임 | 의존 |
|---|---|---|
| core/audio | WAV(RIFF) 파싱·PCM16→float, 16 kHz mono 검증/변환, 마이크 녹음 → WAV 저장 | 없음 |
| core/storage | 앱 폴더 경로, 입력 스캔(kr/en/하위폴더), SAF 복사 in/out | Android SDK |
| model | 카탈로그, 설치 상태, 다운로드(이어받기)/import/검증/압축해제, 사용 중 삭제 방지 | core/storage |
| vad | 오디오 → 프레임 점수 → 프리셋 후처리 → `SpeechSegment[]`, (프리셋,파일)별 캐시 | ORT, JNI, LiteRT, model |
| segment | 발화구간 → 엔진 최대길이 Chunk 계획 | vad 결과만 |
| engine | 모델 load/transcribe(chunk)/cancel/close, 실제 runtime·precision 메타데이터 | JNI, sherpa, vosk, model |
| runner | 엔진×VAD×파일 순차 실행, 진행/취소, 자원 측정, 오류 격리 | 모든 하위 |
| output | PC 호환 txt/CSV, manifest, VAD 구간 JSON | runner 결과 |
| ui | 화면·상태 표시만 (로직은 ViewModel → runner/model) | 전부 |

## 6. 오디오·저장소

- 앱 폴더(권한 불필요): `/sdcard/Android/data/com.cowork.stt_ondevice/files/`
  - `input_sound/{kr,en}/<상대폴더>/<음원>.wav` — 사용자 데이터 구조 그대로(`tools/adb/push_inputs.sh ~/workspace/stt/input_sound`)
  - `models/<model_id>/...`, `output_stt/<run_id>/...`, `tmp/`
- SAF: 입력 폴더 트리 선택 → 앱 폴더로 복사 후 사용, 결과 폴더 → 사용자가 고른 트리로 복사.
- 입력 규칙: `input_sound` 아래 첫 단계가 언어(`kr`→req_lang `ko`, `en`→`en`), 그 아래 상대 경로는 출력에 그대로 미러링
  (`kr/customer/test/01.wav` → `.../customer/test/01.txt`, PC Tool과 동일 동작).
- 입력 검증: RIFF/WAVE PCM16. 16 kHz mono가 아니면 리샘플/다운믹스 후 `preprocess_sec` 기록. 손상 파일은 ERROR.
- 마이크: AudioRecord 16 kHz mono PCM16 → `input_sound/<lang>/mic/<yyyyMMdd_HHmmss>.wav` 저장 후 동일 배치 파이프라인 사용.
  실제 format/rate 기록. Phase 1은 녹음 후 전사(실시간 partial 없음).

## 7. VAD

### 7.1 공통 인터페이스

```kotlin
interface VadEngine : AutoCloseable {
    val vadId: String                       // "SILERO", "PYANNOTE", ...
    fun detect(audio: FloatArray, preset: VadPreset): VadOutput   // 입력은 항상 16 kHz mono
}
data class SpeechSegment(val startSec: Double, val endSec: Double)
data class VadOutput(val segments: List<SpeechSegment>, val frameHopSec: Double, val frameScores: FloatArray?)
```

- 모든 VAD는 **프레임 점수(확률 또는 0/1)** 를 만든 뒤 프리셋이 지정한 후처리 계열로 구간을 만든다.
  sherpa/android-vad의 VAD API는 pad 미지원·확률 비공개라 사용하지 않는다.
- `NoneVad`: 전체 파일을 하나의 발화로 반환 → ChunkPlanner가 고정 길이로 자름(VAD 효과 비교 기준선).
- `VadCache`: 키 `(presetId, 입력 audio SHA-1)` → 결과를 `<run>/vad_segments/<preset>/<lang>/<rel>/<stem>.json` 로 저장·재사용.
  같은 프리셋이면 모든 엔진이 동일 구간을 사용한다.

### 7.2 VAD별 실행 방식·모델·라이선스

| VAD | 모델/라이브러리 | 프레임 | 상업 이용 |
|---|---|---|---|
| Silero v6.2.3 | 공식 `silero_vad.onnx`(v6) + ORT, 512 samples + 64 context | 32 ms | MIT ✔ |
| pyannote | `sherpa-onnx-pyannote-segmentation-3-0` ONNX + ORT, 10 s window, step 1 s, powerset → speech 확률(겹침 평균) | ~17 ms | MIT ✔ (원본 HF gated, 변환본 사용) |
| FSMN-VAD | `iic/speech_fsmn_vad_zh-cn-16k-common-onnx` model_quant.onnx + am.mvn, fbank80/LFR(5,1)/CMVN Kotlin | 10 ms | FunASR Model License 1.1 (출처·모델명 유지 조건) |
| WebRTC | libfvad (BSD-3), 16 kHz | 10/20/30 ms | BSD ✔ |
| TEN VAD | 공식 prebuilt `libten_vad.so`, hop 256 | 16 ms | Apache-2.0 + 추가조건(Agora 경쟁 서비스 금지) — 조건부 |
| FireRedVAD | PC에서 공식 `export_onnx.py`로 변환한 ONNX + fbank80/CMVN | 10 ms | Apache-2.0 ✔ |
| MarbleNet v2.0 | PC에서 NeMo로 export한 ONNX + 전처리(구현 시 NeMo preprocessor 설정 그대로 포팅) | 20 ms | NVIDIA Open Model License ✔(조건 준수) |
| Yamnet | LiteRT + yamnet.tflite, 0.975 s window, speech class 점수 | 구현 시 확정 | Apache-2.0 ✔ |

### 7.3 프리셋 (초기값, 앱에서 USER 프리셋 편집 가능)

**Silero 계열 — 후처리 `SileroTimestamps` (`get_speech_timestamps` 포팅, neg_threshold = th − 0.15)**

| 프리셋 | threshold | minSpeech ms | minSilence ms | pad ms | maxSpeech s | 출처 |
|---|---|---|---|---|---|---|
| SILERO | 0.5 | 250 | 400 | 150 | ∞ | 프로젝트 기준선(Balanced) |
| SILERO_SAFE | 0.3 | 150 | 600 | 300 | ∞ | 프로젝트 ASR Safe |
| SILERO_FW | 0.5 | 0 | 2000 | 400 | ∞ | faster-whisper `VadOptions` (WhisperModel 기본. Batched pipeline은 160 ms/30 s로 다름) |
| SILERO_DEFAULT | 0.5 | 250 | 100 | 30 | ∞ | silero `get_speech_timestamps` |
| SILERO_SHERPA | 0.5 | 250 | 500 | 0 | 20 | sherpa-onnx C++ `SileroVadModelConfig` (Kotlin API 기본은 250 ms/5 s) |
| SILERO_QWEN3 | 0.5 | 1500 | 500 | 30 | ∞ | Qwen3-ASR-Toolkit `audio_tools.py` |
| SILERO_WS | 0.5 | 0 | 500 | 100 | ∞ | whisper_streaming `FixedVADIterator` |
| SILERO_USER | 0.4 | 250 | 300 | 100 | ∞ | 사용자 정의 |

주: SHERPA·WS 원본은 스트리밍 iterator 로직이다. 본 앱은 동일 파라미터를 `get_speech_timestamps` 알고리즘으로 적용하며 설명란에 명시한다.

**pyannote — 후처리 `PyannoteBinarize` (onset/offset 히스테리시스 + min_duration_on/off, pad 0)**

| 프리셋 | onset | offset | min_on s | min_off s | 비고 |
|---|---|---|---|---|---|
| PYA3_DEFAULT | 0.5 | 0.5 | 0.0 | 0.0 | segmentation-3.0 VAD pipeline 기본 |
| PYA2_DIHARD3 | 0.767 | 0.377 | 0.136 | 0.067 | pyannote/segmentation(2.x) 카드 — 3.0 출력에 적용하는 참고값 |
| PYA2_AMI | 0.684 | 0.577 | 0.181 | 0.037 | 동일 |
| PYA2_VOXCONVERSE | 0.767 | 0.713 | 0.182 | 0.501 | 동일 |

**FSMN-VAD — 후처리 `FsmnStateMachine` (FunASR `fsmn_vad_streaming` E2EVadModel 포팅)**

| 프리셋 | 값 |
|---|---|
| FSMN_DEFAULT | max_end_silence_time 800, max_start_silence_time 3000, window_size_ms 200, sil_to_speech_time_thres 150, speech_to_sil_time_thres 150, speech_2_noise_ratio 1.0, do_extend 1, lookback_time_start_point 200, lookahead_time_end_point 100, max_single_segment_time 60000, snr_thres −100, decibel_thres −100, speech_noise_thres 0.6, fe_prior_thres 1e-4, speech_noise_thresh_low −0.1, speech_noise_thresh_high 0.3, frame_in_ms 10, frame_length_ms 25, detect_mode 1, snr_mode 0 (funasr/fsmn-vad config.yaml) |
| FSMN_ASR30 | FSMN_DEFAULT + max_single_segment_time 30000 (SenseVoice README 사용 예) |

**WebRTC — 후처리 `WebRtcRingBuffer` / `SimpleHangover`**

| 프리셋 | 값 | 출처 |
|---|---|---|
| WEBRTC_LIB_DEFAULT | mode 0, 30 ms, 후처리 없음(연속 voiced 프레임 병합) | libfvad 기본 mode |
| WEBRTC_PYEXAMPLE | 30 ms, padding 300 ms ring buffer, 90% voiced 시작/90% unvoiced 종료, mode는 예제 CLI 인자(기본값 없음) → 프리셋 값 3, 설명에 표기 | py-webrtcvad example.py |
| WEBRTC_ANDROIDVAD | mode 3(VERY_AGGRESSIVE), 20 ms, speech 50 ms, silence 300 ms | gkonovalov/android-vad README |

**TEN — 후처리 `SileroTimestamps`(확률 입력, pad 0) / 원시 flag 병합**

| 프리셋 | 값 | 출처 |
|---|---|---|
| TEN_DEFAULT | threshold 0.5, hop 256, 후처리 없음(연속 speech flag 병합) | ten-vad README |
| TEN_SHERPA | threshold 0.5, minSpeech 250, minSilence 500, maxSpeech 20 s, hop 256 | sherpa-onnx `ten-vad-model-config.h` |

**FireRedVAD — 후처리 `FireRedPostprocess` (fireredvad/vad.py, stream_vad.py 포팅)**

| 프리셋 | 값 |
|---|---|
| FIRERED_OFFLINE | threshold 0.4, smooth_window 5, min_speech 20 f, max_speech 2000 f, min_silence 20 f, merge_silence 0, extend 0 |
| FIRERED_STREAM | threshold 0.5, pad_start 5 f, min_speech 8 f, max_speech 2000 f, min_silence 20 f |

**MarbleNet — 후처리 `NemoPostprocess`**

| 프리셋 | 값 |
|---|---|
| MARBLENET_NEMO | onset 0.3, offset 0.3, pad_onset 0.2 s, pad_offset 0.2 s, min_duration_on 0.2 s, min_duration_off 0.2 s, filter_speech_first true (NeMo `frame_vad_infer_postprocess.yaml`) |

**Yamnet — 후처리 `SimpleHangover`**

| 프리셋 | 값 (android-vad 매핑) |
|---|---|
| YAMNET_NORMAL | threshold 0.3, speech 30 ms, silence 30 ms |
| YAMNET_AGGRESSIVE | threshold 0.5, 동일 |
| YAMNET_VERY_AGGRESSIVE | threshold 0.85, 동일 |

**NONE**: VAD 없음, 엔진 maxChunkSec 고정 길이 분할.

### 7.4 VAD 포팅 정합성 검증 (골든 테스트)

- `tools/golden/`가 사용자 음원 일부(짧게 자른 KO/EN 각 1개 + 합성 무음/잡음 1개)에 대해 Python 원본으로 구간 JSON을 만든다:
  silero-vad(pip), pyannote.audio `Binarize`(sherpa ONNX 출력에 적용 — HF gated 회피), funasr FSMN, py-webrtcvad,
  ten-vad(Python), FireRedVAD, NeMo postprocessing.
- JVM 단위 테스트(ORT JVM artifact)로 같은 입력의 Kotlin 결과와 비교: 구간 수 동일, 경계 오차 ≤ 1 프레임(+pad).
  네이티브 전용(TEN/WebRTC/Yamnet)은 후처리 단위 테스트(골든 프레임 점수 입력) + 기기 instrumented 테스트로 검증.

## 8. ChunkPlanner

입력: `SpeechSegment[]`, 오디오 길이, 엔진 `maxChunkSec`, 모드(`MERGE` 기본 / `INDIVIDUAL`), `joinGapSec`(기본 0.2 s 무음 삽입 — PC `vad_join_silence_ms`=200과 동일).

1. **MERGE**: 시간순으로 발화를 누적하되 누적 음성 길이(+삽입 무음)가 maxChunkSec를 넘기 직전에 chunk를 닫는다.
   chunk 오디오 = 각 발화 구간 오디오를 0.2 s 무음으로 이어붙인 것. 원본 offset 목록을 보존.
2. **INDIVIDUAL**: 발화 하나 = chunk 하나.
3. 단일 발화가 maxChunkSec 초과: 그 구간 안의 프레임 점수 최저 지점(경계에서 1 s 이상 떨어진)에서 재귀 분할,
   점수 정보가 없으면 maxChunkSec 지점에서 강제 분할. 분할 방식을 chunk 메타데이터에 기록.
4. `maxChunkSec = 0`(무제한 엔진: Vosk, streaming Zipformer)이면 MERGE는 파일당 1 chunk.
5. 발화가 0개면 chunk 0개 → 결과 text 빈 문자열, status `NO_MATCH`(PC 규칙과 동일).

엔진 출력의 `[구간별]` 시간은 chunk의 **원본 시작~끝 offset**으로 기록(엔진 내부 타임스탬프는 신뢰 가능할 때만 원본 시간으로 환산, 아니면 chunk 단위).

## 9. 엔진

### 9.1 공통 계약

```kotlin
interface AsrEngine : AutoCloseable {
    val spec: ModelSpec                                   // algo, model, 언어, maxChunkSec, runtime 메타데이터
    fun supports(lang: String): Boolean
    suspend fun prepare(): PrepareInfo                    // 로드 시간, 실제 backend/precision/threads
    suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText  // raw/clean 텍스트, 엔진 감지 언어
    fun cancel()                                          // native 중단 플래그 (whisper abort_callback, 그 외 chunk 경계)
}
```

- 지원하지 않는 언어 조합(예: zipformer-ko × en)은 실행하지 않고 manifest에 `UNSUPPORTED`로 기록(출력 폴더 미생성).
- 엔진은 모델 단위로 1회 로드 → 모든 VAD 프리셋 × 파일 실행 → close (모델 순차, 동시 적재 금지).
- Whisper는 `task=transcribe`, 언어 고정(ko/en), 다국어 모델만(`.en` 금지). SenseVoice는 raw 태그 원문을 detail JSON에 보존하고 txt에는 태그 제거 텍스트.

### 9.2 Phase 1 엔진/모델 카탈로그

| algo | model | 원본 / 변환 artifact | 언어 | maxChunkSec | 상업 이용 |
|---|---|---|---|---|---|
| whispercpp | tiny, base, small, medium | `ggerganov/whisper.cpp` ggml-{tiny,base,small,medium}.bin (f16) / 원본 openai/whisper | ko,en | 30 | MIT ✔ |
| whispercpp | large-v3-turbo-q5_0, large-v3-turbo-q8_0 | ggml-large-v3-turbo-q5_0.bin / q8_0.bin | ko,en | 30 | MIT ✔ |
| sherpa-zipformer | ko-2024-06-24 | `sherpa-onnx-zipformer-korean-2024-06-24` | ko | 30 | 모델 카드 확인 전 "미확인" |
| sherpa-zipformer | en-(구현 시 고정) | sherpa offline transducer EN artifact 1종 | en | 30 | 미확인 |
| sherpa-zipformer-streaming | ko-2024-06-16 | `sherpa-onnx-streaming-zipformer-korean-2024-06-16` | ko | 0 | 미확인 |
| sherpa-sensevoice | small-int8 | sherpa SenseVoice int8 artifact(구현 시 최신 날짜 고정) / `FunAudioLLM/SenseVoiceSmall` | ko,en | 30 | 조건부(FunASR Model License) |
| sherpa-qwen3asr | 0.6B-int8 | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` / `Qwen/Qwen3-ASR-0.6B` | ko,en | 30 | Apache-2.0 ✔ |
| sherpa-omnilingual | ctc300m-v1-int8 | `...-1600-languages-300M-ctc-int8-2025-11-12` / `facebook/omniASR-CTC-300M` | ko,en | 30 | Apache-2.0 ✔ |
| sherpa-omnilingual | ctc300m-v2-int8, ctc1b-v2-int8 | `...-300M-ctc-v2-int8-2026-02-05`, `...-1B-ctc-v2-int8-2026-02-05` | ko,en | 30 | Apache-2.0 ✔ |
| vosk | small-ko-0.22, small-en-us-0.15 | alphacephei 모델 zip | ko / en | 0 | 모델별 확인 |

- 모든 artifact의 정확한 URL·파일 목록·bytes·SHA-256은 구현 1단계에서 `tools/hash_models.py`로 실제 다운로드하여 `ModelCatalog.kt`에 고정한다.
  확인 못 한 라이선스는 `UNVERIFIED`로 표시(상업 가능으로 승격 금지).
- maxChunkSec는 UI에서 엔진별로 덮어쓸 수 있고, 실제 사용값을 결과에 기록.

### 9.3 모델 설치 (`ModelInstaller`)

- 상태: `NOT_INSTALLED → DOWNLOADING → VERIFYING → READY | CORRUPT`.
- 다운로드: HTTPS, `.part` 이어받기(Range), 취소/재시도, 저장공간 사전 검사(파일 크기 × 2.2 여유).
- 압축 해제: tar.bz2 / zip, 경로 탈출(`..`, 절대경로) 차단, 해제 후 필수 파일·SHA-256 검사 → `tmp/` 에서 `models/<id>/`로 rename(원자적 전환).
- import: adb로 `models/<id>/`에 직접 넣거나 SAF로 폴더/압축파일 선택 → 동일 검증.
- 실행 중 모델은 삭제·교체 불가.

## 10. 배치 실행 (`BatchRunner`)

- `RunConfig`: run_id(`yyyyMMdd_HHmmss`), 언어 목록, 입력 파일 목록, 엔진/모델 선택 순서, VAD 프리셋 목록, chunk 모드, maxChunkSec 덮어쓰기, threads(기본 4).
- 순서: 모델(선택 순) → VAD 프리셋 → 파일. VAD는 캐시 우선.
- foreground service(`specialUse` 타입 — `dataSync`는 Android 15+에서 24시간당 6시간 제한이 있어 장시간 배치에 부적합)에서 실행, 화면 꺼짐/회전에도 지속. 취소 시 현재 chunk 후 중단, 모델 close 보장.
- 측정: `preprocess_sec`(WAV 로드·변환), `vad_sec`(detail JSON), `load_sec`(모델 로드, manifest), `transcribe_sec`(엔진 순수 chunk 합),
  `elapsed_sec`(파일 전체), `rtf = transcribe_sec / audio_sec`, `rss_peak_mb` = PSS peak(200 ms 샘플링, 방법 명시),
  `cpu_time_sec`(Process CPU time), 열 상태/headroom·배터리·충전 여부는 파일 시작/끝 기록.
- 오류 격리: 파일 단위 try/catch → `NN.ERROR.txt`(stage, code, message) + CSV `status=ERROR`, 다음 항목 계속. 모델 로드 실패는 해당 모델의 모든 조합을 ERROR로 기록.

## 11. 출력 (PC 평가기 호환)

```
output_stt/<run_id>/
 ├ <lang>/<algo>_<model>_<VAD>/<rel_dir>/<stem>.txt          (실패: <stem>.ERROR.txt)
 ├ <lang>/<algo>_<model>_<VAD>/result.csv                    (UTF-8 BOM, PC STT_CSV_HEADER 60열 그대로)
 ├ details/<lang>/<algo>_<model>_<VAD>/<rel_dir>/<stem>.json (chunk별 offset/텍스트/raw, 시간, 자원, 엔진 메타)
 ├ vad_segments/<VAD>/<lang>/<rel_dir>/<stem>.json
 ├ run_manifest.json                                          (기기, 앱 빌드, 모델 hash/라이선스, 프리셋 값, UNSUPPORTED 목록)
 ├ _logs/run_<run_id>.log
 └ _summary/all_result_<run_id>.csv                          (모든 조합 result.csv 합본 + vad 열)
```

- txt 형식(PC faster-whisper와 동일, UTF-8 no BOM):
  ```
  [전체 텍스트] (엔진: whispercpp small / VAD: SILERO_SAFE / 조각 4개)
  <전체 텍스트 한 줄>

  [구간별] start(sec), end(sec), text
  0.42, 28.10, ...
  ```
- result.csv 60열 매핑: `algo`=`<algo>_<VAD>`가 아닌 **`<algo>`**, `model`=`<model>`, `exec_mode`=`embedded`, `engine_version`=runtime 버전,
  `device`=`cpu`, `device_name`=SoC/모델명, `compute_type`=실제 precision, `workers`=threads, `cloud_total_sec`=transcribe_sec(로컬은 업로드 0),
  `note`에 `vad=<preset>;chunk_mode=..;max_chunk=..;alias=<PC 호환명>`. GPU·비용·Google/Azure 전용 열은 빈 값.
- PC 평가 사용: `python run_eval.py --ref ~/workspace/stt/answer_sheet/kr --hyp <run_id>/kr` → 모델명 `<algo>_<model>_<VAD>`.

## 12. 화면

1. **모델**: 카탈로그 목록(그룹/크기/언어/라이선스·상업 상태/설치 상태), 다운로드·취소·import·삭제·검증.
2. **실행 설정**: 언어(kr/en), 입력 파일 트리(체크), 엔진/모델 체크(선택 순서 표시), VAD 프리셋 체크(그룹별 접기, 값 표시, USER 편집),
   chunk 모드/maxChunk/threads, 예상 조합 수 표시 → 실행.
3. **진행**: 현재 모델/VAD/파일, 전체 진행률, 경과 시간, 오류 수, 취소.
4. **결과**: run 목록 → 조합별 OK/ERROR 수, 파일별 전사 미리보기, SAF로 결과 내보내기.
5. **마이크**: 녹음/정지 → WAV 저장 → 이 파일로 실행 설정 화면 이동.

## 13. 오류 처리 원칙

- 오류 = `{stage: INPUT|VAD|MODEL_LOAD|TRANSCRIBE|OUTPUT, code, message, recoverable}`.
- OOM(대형 모델), 누락 .so, ORT 미지원 op, checksum 불일치, 네트워크 실패를 서로 다른 code로 기록.
- 조용한 fallback 금지(다른 모델/VAD로 자동 대체하지 않음).

## 14. 검증 계획 (3단계)

1. **정적·단위**: 공식 소스 대조(프리셋 값·후처리 로직), JVM 테스트 — WavReader, 각 post-processor 골든 비교, ChunkPlanner(병합/개별/초과분할/0발화),
   OutputLayout 경로, TranscriptWriter 형식, ResultCsvWriter 60열/BOM, ModelInstaller 경로탈출·SHA 검사.
   추가로 샘플 출력 폴더에 사용자의 `run_eval.py`를 **읽기 전용 실행**하여 페어링 확인.
2. **빌드**: `./gradlew assembleDebug testDebugUnitTest`, `llvm-readelf`로 모든 `.so` LOAD p_align=16384 확인, 중복 .so 없음.
3. **실기기(S25 Ultra 연결 시)**: 모델 설치→KO/EN 파일 1개씩 엔진×(NONE, SILERO, 대표 VAD) smoke, 취소/재시작, 비행기 모드 전사, adb pull → PC 평가기 실행.
   기기 미연결 시 3단계는 "미수행"으로 보고하고 재현 절차를 제공.

## 15. 코딩 규칙 (사용자 필수)

- 모든 코드 파일 상단 한국어 주석: 파일명·목적·기능. 함수마다 목적/기능/입력/출력/리턴. 라인별 한국어 설명 주석.
- 추가·변경 코드에 `change(add)-hyungchul-YYYY-MM-DD-HH:mm`(Asia/Seoul 실제 시각).
- 기존 파일 수정 시 include/define/함수/변수 순서 보존, 수정 파일은 전체 제공(Beyond Compare 비교).
- 주석 불가 포맷(JSON 등)은 별도 한국어 설명 문서.
- 실행하지 않은 검증·측정은 수행했다고 쓰지 않는다.

## 16. 위험·미확인 항목

| 항목 | 대응 |
|---|---|
| sherpa static AAR + onnxruntime-android 동시 사용 시 심볼 충돌 | 빌드·기기 로드 테스트에서 확인, 문제 시 Silero 등도 sherpa 정적 ORT 경유 방안 검토 |
| whisper.cpp v1.9.5 × CMake 4.1.2 × NDK 30 호환 | 1단계 빌드에서 확인, 필요 시 `cmake_minimum_required` 정책 플래그 |
| MarbleNet 전처리 정확 재현 | NeMo preprocessor 출력과 Kotlin 출력 수치 비교 테스트 |
| FireRedVAD/MarbleNet export 실패 | 해당 VAD를 BLOCKED로 표시하고 나머지 진행, 원인 기록 |
| Yamnet speech class 해석·LiteRT 16KB | 구현 착수 시 확인, 미충족 시 BLOCKED |
| Zipformer EN/streaming KO·Vosk·SenseVoice artifact 라이선스 | 카드 확인 전 UNVERIFIED 표시 |
| PC 평가기 영어 정답의 화자 라벨(`Customer:` 등) 미제거로 EN WER 과대 | 앱 범위 밖. 사용자에게 별도 보고(평가기 수정은 별건) |
| 대형 모델(medium f16, Omni 1B) 메모리/발열 | 순차 로드·해제, PSS peak와 thermal 기록, 실패 시 ERROR(OOM) |
