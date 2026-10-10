# 모델 카탈로그 (`ModelSpec`·`ModelCatalog`·`ModelCatalogData`)

<!-- change(add)-hyungchul-2026-10-10-16:12 (Task 18) -->
<!-- change(add)-hyungchul-2026-10-10-20:00 (Task 18 재개: 라이선스 근거 표·역할 표·결정 메모 작성) -->

- `ModelSpec.kt`: 타입 정의(ModelKind·Runtime·ArchiveType·StreamingKind·ModelFile·Download·EngineConfig·ModelSpec).
- `ModelCatalogData.kt`: **자동 생성 파일**. `python3 -I tools/hash_models.py --emit-kotlin` 이 `tools/model_sources.json` 과
  실제 다운로드·해제 결과로 만든다. 직접 고치지 말고 원본 목록을 고쳐 다시 생성한다(도구·JSON 형식 설명: `tools/README_모델카탈로그.md`).
- `ModelCatalog.kt`: 조회(`all`, `asr`, `byId`, `vadModel(family)`). 없는 id 는 예외, 다른 모델로 대체하지 않는다.
- 모든 SHA-256 은 2026-10-10 에 실제로 내려받아 계산한 값이다. 공개 메타데이터가 있는 경우(HF LFS sha256, GitHub release asset digest,
  기존 골든/변환 도구 해시) 스크립트가 `expectSha256` 으로 교차 확인했다.

## 1. 모델별 라이선스 근거 (확인일 2026-10-10)

상업 이용 판정: `ALLOWED`(허용) / `CONDITIONAL`(조건부) / `UNVERIFIED`(미확인 — 상업 가능으로 승격하지 않음).

### 1.1 ASR

| id | algo / model | artifact | 언어 | maxChunkSec | 라이선스 | 상업 | 근거 URL |
|---|---|---|---|---|---|---|---|
| `whispercpp-tiny` | whispercpp / tiny | `ggml-tiny.bin`(f16) | ko,en | 30 | MIT | ALLOWED | https://github.com/openai/whisper/blob/main/LICENSE , https://huggingface.co/ggerganov/whisper.cpp (카드 `license: mit`) |
| `whispercpp-base` | whispercpp / base | `ggml-base.bin`(f16) | ko,en | 30 | MIT | ALLOWED | 위와 같음 |
| `whispercpp-small` | whispercpp / small | `ggml-small.bin`(f16) | ko,en | 30 | MIT | ALLOWED | 위와 같음 |
| `whispercpp-medium` | whispercpp / medium | `ggml-medium.bin`(f16) | ko,en | 30 | MIT | ALLOWED | 위와 같음 |
| `whispercpp-large-v3-turbo-q5_0` | whispercpp / large-v3-turbo-q5_0 | `ggml-large-v3-turbo-q5_0.bin` | ko,en | 30 | MIT | ALLOWED | 위와 같음 |
| `whispercpp-large-v3-turbo-q8_0` | whispercpp / large-v3-turbo-q8_0 | `ggml-large-v3-turbo-q8_0.bin` | ko,en | 30 | MIT | ALLOWED | 위와 같음 |
| `sherpa-zipformer-ko-2024-06-24` | sherpa-zipformer / ko-2024-06-24 | `sherpa-onnx-zipformer-korean-2024-06-24.tar.bz2` | ko | 30 | Apache-2.0(원본 카드) / KsponSpeech 이용조건 미확인 | **UNVERIFIED** | https://huggingface.co/johnBamma/icefall-asr-ksponspeech-zipformer-2024-06-24 (카드 `license: apache-2.0`), 변환본 https://huggingface.co/k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24 (라이선스 표기 없음) |
| `sherpa-zipformer-en-2023-06-26` | sherpa-zipformer / en-2023-06-26 | `sherpa-onnx-zipformer-en-2023-06-26.tar.bz2` | en | 30 | Apache-2.0 | ALLOWED | https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-en-2023-06-26 (카드 `license: apache-2.0`), 학습 데이터 LibriSpeech(CC BY 4.0) |
| `sherpa-zipformer-streaming-ko-2024-06-16` | sherpa-zipformer-streaming / ko-2024-06-16 | `sherpa-onnx-streaming-zipformer-korean-2024-06-16.tar.bz2` | ko | 0 | Apache-2.0(원본 카드) / KsponSpeech 이용조건 미확인 | **UNVERIFIED** | https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12 (카드 `license: apache-2.0`), 변환본 https://huggingface.co/k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16 (표기 없음) |
| `sherpa-sensevoice-small-int8-2024-07-17` | sherpa-sensevoice / small-int8 | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2` | ko,en | 30 | FunASR Model License 1.1 | CONDITIONAL | https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE (§2.2 출처·저자 표기, 모델 이름 유지), https://huggingface.co/FunAudioLLM/SenseVoiceSmall (카드 `license: other` → 위 링크) |
| `sherpa-qwen3asr-0.6b-int8-2026-03-25` | sherpa-qwen3asr / 0.6B-int8 | `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2` | ko,en | 30 | Apache-2.0 | ALLOWED | https://huggingface.co/Qwen/Qwen3-ASR-0.6B (카드 `license: apache-2.0`) |
| `sherpa-omnilingual-ctc300m-v1-int8-2025-11-12` | sherpa-omnilingual / ctc300m-v1-int8 | `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2` | ko,en | 30 | Apache-2.0 | ALLOWED | https://github.com/facebookresearch/omnilingual-asr/blob/main/LICENSE , https://huggingface.co/facebook/omniASR-CTC-300M (카드 `license: apache-2.0`), 압축 안 `LICENSE`(Meta Apache-2.0) |
| `sherpa-omnilingual-ctc300m-v2-int8-2026-02-05` | sherpa-omnilingual / ctc300m-v2-int8 | `...-300M-ctc-v2-int8-2026-02-05.tar.bz2` | ko,en | 30 | Apache-2.0 | ALLOWED | 위와 같음(압축 안 `LICENSE` 동일) |
| `sherpa-omnilingual-ctc1b-v2-int8-2026-02-05` | sherpa-omnilingual / ctc1b-v2-int8 | `...-1B-ctc-v2-int8-2026-02-05.tar.bz2` | ko,en | 30 | Apache-2.0 | ALLOWED | 위와 같음, https://huggingface.co/facebook/omniASR-CTC-1B (카드 `license: apache-2.0`) |
| `vosk-small-ko-0.22` | vosk / small-ko-0.22 | `vosk-model-small-ko-0.22.zip` | ko | 0 | Apache-2.0 | ALLOWED | https://alphacephei.com/vosk/models (공식 모델 표 License 열 "Apache 2.0") |
| `vosk-small-en-us-0.15` | vosk / small-en-us-0.15 | `vosk-model-small-en-us-0.15.zip` | en | 0 | Apache-2.0 | ALLOWED | 위와 같음 |

### 1.2 VAD

| id | VadFamily | artifact | 라이선스 | 상업 | 근거 URL |
|---|---|---|---|---|---|
| `vad-silero-v6.2.3` | SILERO | `silero_vad.onnx`(태그 v6.2.3) | MIT | ALLOWED | https://github.com/snakers4/silero-vad/blob/v6.2.3/LICENSE |
| `vad-pyannote-seg3.0` | PYANNOTE | `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` 의 `model.onnx`(fp32) | MIT | ALLOWED | https://huggingface.co/pyannote/segmentation-3.0 (카드 `license: mit`, 원본은 gated → sherpa-onnx 변환본 사용, 압축 안 `LICENSE` = MIT, Copyright CNRS) |
| `vad-fsmn-zh-cn-16k-onnx` | FSMN | modelscope `iic/speech_fsmn_vad_zh-cn-16k-common-onnx` 태그 v2.0.6 의 `model_quant.onnx`·`am.mvn`·`config.yaml` | FunASR Model License 1.1 | CONDITIONAL | https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE (modelscope 메타 표기는 "Apache License 2.0" 이지만 FunASR 모델 라이선스 조건을 보수적으로 적용) |
| `vad-yamnet-tflite-v1` | YAMNET | `lite-model_yamnet_classification_tflite_1.tflite` → `yamnet.tflite` | Apache-2.0 | ALLOWED | https://www.kaggle.com/models/google/yamnet (instance licenseName "Apache 2.0", `tools/golden/README_한국어.md` 9.2절) |
| `vad-firered-7990aac` | FIRERED | PC 변환 ONNX(import 전용, 다운로드 없음) | Apache-2.0 | ALLOWED | https://huggingface.co/FireRedTeam/FireRedVAD (카드 `license: apache-2.0`, revision 7990aac) |
| `vad-marblenet-v2.0` | MARBLENET | PC 변환 ONNX(import 전용, 다운로드 없음) | NVIDIA Open Model License | ALLOWED(조건 준수) | https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/ , https://huggingface.co/nvidia/Frame_VAD_Multilingual_MarbleNet_v2.0 ("GOVERNING TERMS" 절). 조건: 라이선스 고지 유지, 이용 제한(가드레일 우회 금지 등) 준수 |

WebRTC(libfvad)·TEN VAD 는 모델 파일이 없는 네이티브 라이브러리라 카탈로그에 없다(`vadModel()` = null).

## 2. 파일 역할(role) ↔ 엔진 설정 필드

sherpa-onnx 필드 이름은 **v1.13.8** Kotlin API(`sherpa-onnx/kotlin-api/OfflineRecognizer.kt`, `OnlineRecognizer.kt`, 태그 v1.13.8)에서 확인했다.
모델 폴더 = `models/<id>/`, 아래 경로는 그 폴더 기준.

| 엔진(EngineConfig) | role | 채울 설정 필드 | 파일 |
|---|---|---|---|
| `WhisperConfig` | `model` | whisper.cpp `whisper_init_from_file_with_params(path)` | `ggml-*.bin` |
| `SherpaConfig(ZIPFORMER_TRANSDUCER)` | `encoder` / `decoder` / `joiner` | `OfflineModelConfig.transducer` = `OfflineTransducerModelConfig(encoder, decoder, joiner)` | `encoder-epoch-99-avg-1.int8.onnx` / `decoder-epoch-99-avg-1.onnx` / `joiner-epoch-99-avg-1.int8.onnx` |
| 〃 | `tokens` | `OfflineModelConfig.tokens` (`modelType = "transducer"`, sherpa 예제 13번과 같음) | `tokens.txt` |
| `SherpaConfig(STREAMING_ZIPFORMER)` | `encoder` / `decoder` / `joiner` | `OnlineModelConfig.transducer` = `OnlineTransducerModelConfig(encoder, decoder, joiner)` | 위와 같은 이름 |
| 〃 | `tokens` | `OnlineModelConfig.tokens` (`modelType = "zipformer"`, sherpa Online 예제 14번과 같음) | `tokens.txt` |
| `SherpaConfig(SENSEVOICE)` | `model` | `OfflineModelConfig.senseVoice` = `OfflineSenseVoiceModelConfig(model)` | `model.int8.onnx` |
| 〃 | `tokens` | `OfflineModelConfig.tokens` | `tokens.txt` |
| `SherpaConfig(QWEN3_ASR)` | `convFrontend` / `encoder` / `decoder` | `OfflineModelConfig.qwen3Asr` = `OfflineQwen3AsrModelConfig(convFrontend, encoder, decoder)` | `conv_frontend.onnx` / `encoder.int8.onnx` / `decoder.int8.onnx` |
| 〃 | `tokenizer` | `OfflineQwen3AsrModelConfig.tokenizer` = **이 역할 파일들이 든 폴더 경로**(`<모델폴더>/tokenizer`). `tokens` 는 빈 문자열(sherpa 예제 61번) | `tokenizer/merges.txt`, `tokenizer/tokenizer_config.json`, `tokenizer/vocab.json` |
| `SherpaConfig(OMNILINGUAL_CTC)` | `model` | `OfflineModelConfig.omnilingual` = `OfflineOmnilingualAsrCtcModelConfig(model)` | `model.int8.onnx` |
| 〃 | `tokens` | `OfflineModelConfig.tokens` (sherpa 예제 44번) | `tokens.txt` |
| `VoskConfig` | `modelDir` | `org.vosk.Model(<모델폴더>)` — 역할 파일 전체가 든 모델 폴더 경로 | `am/`, `conf/`, `graph/`, `ivector/` 아래 파일(README 제외) |
| `VadModelConfig(SILERO)` | `model` | `VadEngineFactory.SILERO_FILE` | `silero_vad.onnx` |
| `VadModelConfig(PYANNOTE)` | `model` | `VadEngineFactory.PYANNOTE_FILE`(fp32) | `model.onnx` |
| `VadModelConfig(YAMNET)` | `model` | `VadEngineFactory.YAMNET_FILE` | `yamnet.tflite` |
| `VadModelConfig(FSMN)` | `model` / `cmvn` / `config` | `FsmnVad(modelDir)` 가 여는 파일 | `model_quant.onnx` / `am.mvn` / `config.yaml` |
| `VadModelConfig(FIRERED)` | `modelOffline` / `modelStream` / `cmvn` | `FireRedVad(modelDir)` 가 여는 파일 | `fireredvad_vad.onnx` / `fireredvad_stream_vad.onnx` / `cmvn.ark` |
| `VadModelConfig(MARBLENET)` | `model` / `preprocessor` | `MarbleNetVad(modelDir)` 가 여는 파일 | `frame_vad_multilingual_marblenet_v2.0.onnx` / `marblenet_preprocessor.json` |

압축 안에 있지만 `files` 에서 뺀 것(설치 검증 대상 아님): 테스트 음원(`test_wavs/`), `README*`, `LICENSE`(근거는 위 표), 내보내기 스크립트,
`.gitattributes`, `bpe.model`(핫워드용, 미사용), 쓰지 않는 정밀도 변형(zipformer fp32 encoder/joiner·int8 decoder, pyannote `model.int8.onnx`).

## 3. 결정 메모

- **EN offline zipformer = `sherpa-onnx-zipformer-en-2023-06-26`**: 한국어 zipformer 와 같은 구조·같은 파일 구성(인코더·조이너 int8 + 디코더 fp32)의
  공식 EN offline transducer 중, 변환본 카드에 `license: apache-2.0` 이 명시된 표준(중간) 크기 모델. small/large 변형과 libriheavy·gigaspeech 계열은 제외.
- **SenseVoice = 2024-07-17 int8**: 더 최신 `...-int8-2025-09-09` 는 압축 안 README 가 밝히듯 `ASLP-lab/WSYue-ASR` 의 `sensevoice_small_yue`
  (광둥어 미세조정본) 변환본이다. spec §9.2 의 원본 `FunAudioLLM/SenseVoiceSmall` 기준 ko/en 비교에 맞지 않아 원본 변환본 중 최신인 2024-07-17 을 고정했다.
- **한국어 zipformer 2종 UNVERIFIED**: 원본 icefall 카드는 Apache-2.0 이지만 sherpa 변환본에는 표기가 없고, 학습 데이터 KsponSpeech(AI Hub)
  이용조건(상업 이용 범위)을 확인하지 못해 승격하지 않았다.
- **FSMN CONDITIONAL**: modelscope 메타는 Apache 2.0 이나 FunASR 모델 라이선스(출처·모델 이름 유지)를 보수적으로 적용.
- **zipformer 정밀도 `int8`**: 인코더·조이너 int8 + 디코더 fp32 조합(sherpa 공식 Kotlin 예제와 같음).
- 런타임 버전: whisper.cpp 1.9.5, sherpa-onnx 1.13.8, Vosk 0.3.75, ONNX Runtime 1.30.0, LiteRT 2.3.0.
