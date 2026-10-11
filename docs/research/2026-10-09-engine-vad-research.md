# 엔진·VAD·의존성 조사 노트 (2026-10-09)

- 목적: STT_OnDevice 앱 설계(spec) 근거 자료. 웹 공식 자료(소스코드·모델카드·Maven metadata)로 확인한 값만 ✔ 표시.
- ✘ / unverified 는 실기기 또는 원문 확인이 추가로 필요한 항목.

## 1. 추가 후보 STT 엔진 (KO+EN, 무료, 상업 이용 가능, 온디바이스)

| 엔진/모델 | 체크포인트 | KO | EN | 라이선스 | Android | iOS | 크기 | 판단 |
|---|---|---|---|---|---|---|---|---|
| Whisper large-v3-turbo | `openai/whisper-large-v3-turbo`, ggml `ggml-large-v3-turbo-q5_0.bin`/`q8_0.bin` | ✔ | ✔ | MIT ✔ | whisper.cpp / sherpa-onnx | whisper.cpp(+CoreML) | 809M, q5_0 574MB, q8_0 874MB ✔ | ADD |
| Android on-device SpeechRecognizer | `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API31+), 파일입력 `EXTRA_AUDIO_SOURCE` (API33+) | 기기별 ✘ | ✔ | 플랫폼 API(무료) | 내장 | - | 0 | ADD(baseline), S25에서 ko-KR 런타임 확인 필요 |
| Apple SpeechAnalyzer/SpeechTranscriber (iOS 26) | `SpeechTranscriber` | ✔(launch list) | ✔ | 플랫폼 API(무료) | - | 내장 | - | iOS 단계 ADD |
| Apple SFSpeechRecognizer on-device | `requiresOnDeviceRecognition` | ✘ | ✔ | 플랫폼 API | - | 내장 | - | iOS 단계 baseline |
| Gemma 4 E2B/E4B (오디오 LLM) | `litert-community/gemma-4-E2B-it-litert-lm` | 커뮤니티 테스트만 | ✔ | Apache-2.0 ✔ | LiteRT-LM | LiteRT-LM | E2B .litertlm 약 2.58GB | 연구 tier, 30s/요청 제한 → VAD chunk 필수 |
| Omnilingual ASR CTC 1B v2 / 300M v2 | `sherpa-onnx-omnilingual-asr-1600-languages-{1B,300M}-ctc-v2-int8-2026-02-05` | ✔ | ✔ | Apache-2.0 ✔ | sherpa-onnx | sherpa-onnx | 787MB / 292MB int8 | OPTIONAL (300M은 v2로 교체 고려) |
| Whisper turbo 한국어 fine-tune | `royshilkrot/whisper-large-v3-turbo-korean-ggml` | ✔ | 저하 가능 ✘ | 카드 Apache-2.0, 학습데이터 라이선스 ✘ | whisper.cpp | whisper.cpp | ~0.8B | OPTIONAL(법무 확인 전 상업 배포 보류) |
| Qualcomm AI Hub Whisper turbo (NPU) | `whisper_large_v3_turbo` | ✔ | ✔ | AI-HUB-MODELS-LICENSE ✘ | QNN | - | ✘ | OPTIONAL(라이선스 확인 필요) |
| Dolphin base/small CTC | sherpa-onnx dolphin | ✔ | ✗ | Apache-2.0 | sherpa | sherpa | 81/192MB | REJECT (영어 없음) |
| Canary-1b-v2 / Parakeet-TDT-0.6b-v3 | nvidia | ✗ | ✔ | CC-BY-4.0 | sherpa | - | - | REJECT (한국어 없음) |
| OWSM-CTC v4 1B | `espnet/owsm_ctc_v4_1B` | ✘ | ✔ | CC-BY-4.0 | 없음 | 없음 | 1B | REJECT (모바일 경로 없음) |
| FireRedASR2 / X-ASR / Distil-whisper / Kyutai / Granite 4 speech / Phi-4-mm | - | ✗ | ✔ | - | - | - | - | REJECT (한국어 없음) |

정정 사항:
- Moonshine 한국어는 공식 문서 기준 Community License(비상업) 유지. 일부 사이트의 MIT 표기는 오류.
- Whisper 공식 라이선스는 MIT (일부 사이트 Apache-2.0 표기는 오류).

## 2. VAD

### 2.1 Silero (MIT, 최신 v6.2.3 2026-09-23 ✔, 16k 512 samples + 64 context, 확률 출력, neg_threshold = th-0.15)

| 프리셋 | threshold/minSpeech/minSilence/pad | 출처 | 확인 |
|---|---|---|---|
| SILERO | 0.5/250/400/150 | 프로젝트 기준선 | - |
| SILERO_SAFE | 0.3/150/600/300 | 프로젝트 | - |
| SILERO_FW | 0.5/0/2000/400 (max_speech inf) | faster-whisper VadOptions | ✔ (Batched pipeline 은 minSilence 160, maxSpeech 30s) |
| SILERO_DEFAULT | 0.5/250/100/30 | silero get_speech_timestamps | ✔ |
| SILERO_SHERPA | 0.5/250/500/0, maxSpeech 20s | sherpa-onnx C++ 기본값 | ✔ (Kotlin API 기본값은 minSilence 250, maxSpeech 5s 로 다름) |
| SILERO_QWEN3 | 0.5/1500/500/30 | Qwen3-ASR-Toolkit audio_tools.py | ✔ (목표 120s / 최대 180s 분할점 선택용) |
| SILERO_WS | 0.5/0/500/100 | whisper_streaming FixedVADIterator | ✔ |
| SILERO_USER | 0.4/250/300/100 | 사용자 정의 | - |

- gkonovalov/android-vad 의 Silero 는 v4 → 사용하지 않음. 공식 v6 ONNX 를 onnxruntime-android 로 직접 실행.

### 2.2 pyannote (MIT, HF gated ✔)
- segmentation-3.0: 10s window, 기본 step 1s, powerset 7 class → speech 확률. sherpa-onnx 가 `sherpa-onnx-pyannote-segmentation-3-0` ONNX(6.96MB) 제공 ✔ (diarization 전용 API → VAD 는 직접 후처리 구현 필요).

| 프리셋 | onset/offset/min_on(s)/min_off(s) | 출처 |
|---|---|---|
| PYA3_DEFAULT | 0.5/0.5/0.0/0.0 | segmentation-3.0 VAD pipeline ✔ |
| PYA2_DIHARD3 (=pyannote 2.x 코드 기본) | 0.767/0.377/0.136/0.067 | pyannote/segmentation 카드 ✔ |
| PYA2_AMI | 0.684/0.577/0.181/0.037 | 동일 ✔ |
| PYA2_VOXCONVERSE | 0.767/0.713/0.182/0.501 | 동일 ✔ |

- 주의: PYA2_* 는 segmentation 2.x(비-powerset) 모델용 튜닝값. 3.0 ONNX 출력에 적용하면 "참고 프리셋"으로 표시.

### 2.3 Picovoice Cobra
- SDK Apache-2.0 이지만 AccessKey 필수, 온라인 검증, MAU 과금. 현재 FAQ: 개인/비상업 무료 플랜 없음, 일회성 Free Trial 만 ✔ → "유료 라이선스 없는 제품" 조건 불충족.
- `ai.picovoice:cobra-android:3.0.1` ✔. 데모 threshold 0.8 ✔. frame 길이는 런타임 `getFrameLength()` (512 ✘).

### 2.4 FunASR FSMN-VAD
- 코드 MIT, 모델 FunASR Model License 1.1(출처·모델명 유지 조건, 상업 금지 아님). ONNX `iic/speech_fsmn_vad_zh-cn-16k-common-onnx` (model_quant.onnx 507KB, am.mvn, config.yaml) ✔. sherpa-onnx 미지원 → onnxruntime + fbank80/LFR(m5,n1)/CMVN + 상태머신 Kotlin 포팅 필요.

| 프리셋 | 핵심 파라미터 | 출처 |
|---|---|---|
| FSMN_DEFAULT | max_end_silence_time 800, max_start_silence_time 3000, sil_to_speech 150, speech_to_sil 150, speech_noise_thres 0.6, lookback 200, lookahead 100, max_single_segment_time 60000, window 200, frame 10ms … | funasr/fsmn-vad config.yaml ✔ |
| FSMN_ASR30 | 기본 + max_single_segment_time 30000 (SenseVoice 사용 예, merge_length_s 15) | SenseVoice README ✔ |

### 2.5 WebRTC VAD (BSD-3)
- mode 0~3 (0=quality … 3=very aggressive), 기본 mode 0 ✔, frame 10/20/30ms, boolean 출력.

| 프리셋 | 파라미터 | 출처 |
|---|---|---|
| WEBRTC_LIB_DEFAULT | mode 0 | libfvad ✔ |
| WEBRTC_PYEXAMPLE | 30ms frame, padding 300ms ring buffer, 90% voiced/unvoiced trigger, mode 사용자 지정 | py-webrtcvad example.py ✔ |
| WEBRTC_ANDROIDVAD | 20ms(320), VERY_AGGRESSIVE(3), silence 300, speech 50 | gkonovalov/android-vad README ✔ |

### 2.6 추가 후보 VAD
| VAD | 라이선스 | 기본/권장 | Android |
|---|---|---|---|
| TEN VAD | Apache-2.0 + 추가조건(Agora 경쟁 서비스 배포 금지, 자체 앱·직접 사용자 한정) ✔ | th 0.5, hop 256; sherpa C++: minSil 0.5s, minSpeech 0.25s, maxSpeech 20s | sherpa-onnx `TenVadModelConfig` 또는 prebuilt .so |
| FireRedVAD (2026-03) | Apache-2.0 ✔ | offline: th 0.4, smooth 5, minSpeech 20f, minSilence 20f, maxSpeech 2000f; stream 기본 th 0.5 | ONNX export 스크립트, fbank80+CMVN (FSMN 프론트엔드와 공유 가능) |
| NVIDIA Frame VAD MarbleNet v2.0 | NVIDIA Open Model License(상업 가능) ✔ | onset/offset 0.3, pad 0.2s, min_on/off 0.2s | .nemo → ONNX 직접 export 필요, 학습데이터에 한국어 없음 |
| Yamnet | Apache-2.0 | NORMAL 0.3 / AGGR 0.5 / VERY 0.85 | TFLite(16KB 정렬 ✘) |

## 3. Android 의존성 (2026-10-09 기준 최신)

| 항목 | 좌표 | 버전 | 16KB |
|---|---|---|---|
| Compose BOM | androidx.compose:compose-bom | 2026.09.00 | - |
| Kotlin compose/serialization plugin | org.jetbrains.kotlin.plugin.compose / .serialization | 2.4.21 | - |
| coroutines / serialization-json | kotlinx | 1.11.0 / 1.11.0 | - |
| navigation-compose / lifecycle-viewmodel-compose / activity-compose | androidx | 2.10.2 / 2.11.0 / 1.13.0 | - |
| sherpa-onnx | GitHub release AAR (Maven 없음) | v1.13.8, `sherpa-onnx-static-link-onnxruntime-1.13.8.aar` 권장 | ✔ |
| whisper.cpp | 소스 빌드 | v1.9.5 | 직접 빌드(NDK r28+ 기본 16KB) |
| Vosk | com.alphacephei:vosk-android (+ jna 5.18.1@aar) | 0.3.75 | ✔ |
| Moonshine | ai.moonshine:moonshine-voice | 0.1.5 (자체 libonnxruntime.so 포함 → 충돌 주의) | ✔ |
| ML Kit GenAI Speech | com.google.mlkit:genai-speech-recognition | 1.0.0-alpha1 | - |
| ONNX Runtime | com.microsoft.onnxruntime:onnxruntime-android | 1.30.0 | ✔ |
| ORT QNN | onnxruntime-android-qnn | 1.29.0 | ✔ |

- libonnxruntime.so 충돌: sherpa 일반 AAR(ORT 1.28.2) vs onnxruntime-android(1.30.0) vs Moonshine(custom). → sherpa static-link AAR + onnxruntime-android 조합, `pickFirst` 금지.
- AGP 9 built-in Kotlin: `org.jetbrains.kotlin.android` 적용 금지, compose plugin 만 추가.
