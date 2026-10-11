# tools/golden — VAD 골든 데이터 생성 도구

change(add)-hyungchul-2026-10-10-12:19
change(add)-hyungchul-2026-10-10-12:52 수정 라운드 1(R14): 클립 길이를 hop 배수가 아니게 변경, 70 s 긴 클립 추가,
손작성 max_speech 골든 추가, FSMN 다중 블록 설명, 프레임 수 규칙 검사.

이 폴더의 Python 스크립트는 각 VAD 의 **원본 Python 구현**으로 음원 클립 4개(+ 음원 없는 손작성 확률 3종)를 처리해
"기준 결과(골든)" JSON 을 만든다. Kotlin 으로 포팅한 후처리·모델 추론은 이 JSON 과 비교해 정합성을 검증한다
(계획서 Task 8~12, spec §7.4). JSON 은 주석을 달 수 없으므로 형식·의미를 이 문서에서 설명한다.
(출력 폴더의 요약 설명: `app/src/test/resources/golden/README_한국어.md`)

- 출력(커밋): `app/src/test/resources/golden/*.json`, `app/src/test/resources/golden/fsmn_am.mvn`, 같은 폴더 `README_한국어.md`
- 출력(커밋 안 함, gitignore): `app/src/test/resources/golden/audio/*.wav`(클립), `tools/golden/.venv/`, `tools/golden/models/`

## 1. 파일 구성

| 파일 | 역할 |
|---|---|
| `requirements.txt` | 의존성 목록(torch CPU 휠 인덱스 포함) |
| `requirements.lock.txt` | 실제 설치된 정확한 버전(`pip freeze`) |
| `golden_common.py` | 경로·클립 목록·프리셋 값(앱 `VadPresets.kt` 와 동일)·클립 로딩·JSON 저장 공통 함수 |
| `hangover_ref.py` | **SimpleHangover 정의(기준 구현)** 와 연속 프레임 병합(RAW). import 시 계획서 손작성 케이스 자체 검사 |
| `make_clips.py` | 클립 4종 생성 |
| `gen_silero.py` | Silero 8 프리셋 × 4 클립 + `scores_SILERO_*` |
| `gen_pyannote.py` | pyannote 4 프리셋 × 4 클립 + `scores_PYANNOTE_*` |
| `gen_fsmn.py` | FSMN 2 프리셋 × 4 클립 + `scores_FSMN_*` (60 s 초과 클립은 다중 블록) |
| `gen_webrtc.py` | WebRTC 3 프리셋 × 4 클립 (py-webrtcvad `example.py` 의 `frame_generator`/`vad_collector` 복사본 + MIT 고지 포함) |
| `gen_ten.py` | TEN 2 프리셋 × 4 클립 |
| `gen_maxspeech.py` | 손작성 확률 3종 × (SILERO_SHERPA window 512, TEN_SHERPA window 256) — max_speech 분할 분기 골든 |
| `gen_fbank.py` | `fbank_*`, `lfr_cmvn_*`, `fsmn_am.mvn` 복사 |
| `verify_golden.py` | 계획서 Step 4 검증 + hop·프레임 수 규칙·totalSamples·손작성 골든 분할 확인 |
| `gen_yamnet.py` | (change(add)-hyungchul-2026-10-10-15:43) Yamnet Speech 점수 골든 `scores_YAMNET_kr_customer_01_20s_plus77.json` (9절, 별도 venv `.venv-yamnet`) |
| `requirements-yamnet.txt` / `requirements-yamnet.lock.txt` | (change(add)-hyungchul-2026-10-10-15:43) `gen_yamnet.py` 전용 의존성(ai-edge-litert 2.3.0, numpy, soundfile) / 실제 설치 버전 |

## 2. 재현 방법

```bash
cd /home/hyungchul/AndroidStudioProjects/STT_OnDevice
python3 -m venv tools/golden/.venv
tools/golden/.venv/bin/pip install -r tools/golden/requirements.txt      # 또는 requirements.lock.txt
# (모델 다운로드: 3절 표의 URL 을 각자 새 빈 폴더 tools/golden/models/<폴더>/ 에 받는다. pyannote tar.bz2 는
#  models/pyannote_seg30_dl/ 에 받고 새 빈 폴더 models/pyannote_seg30/ 에 압축 해제)
# 다운로드 파일을 읽으므로 -I(격리 모드)로 실행(zsh/bash 공통)
for s in make_clips gen_silero gen_pyannote gen_fsmn gen_webrtc gen_ten gen_maxspeech gen_fbank verify_golden; do
  tools/golden/.venv/bin/python -I tools/golden/$s.py || break
done
```

- 모든 스크립트는 결정적이다(난수는 seed 고정, ONNX 스레드 1). 두 번 실행하면 JSON 이 바이트 단위로 같다(확인함).
- Python 3.12.3(시스템), CPU 전용 torch 2.8.0.
- **TEN(libten_vad.so)은 `libc++.so.1` 이 필요**하다. 공식 안내는 `sudo apt install libc++1` 이다. 시스템에 없으면
  `gen_ten.py` 가 프로젝트 NDK(30.0.16248370)의 호스트용 libc++ 폴더
  `~/Android/Sdk/ndk/30.0.16248370/toolchains/llvm/prebuilt/linux-x86_64/lib/x86_64-unknown-linux-gnu`
  (`ANDROID_NDK_HOME` 이 있으면 그 아래 같은 경로)를 `LD_LIBRARY_PATH` 앞에 넣고 자신을 다시 실행한다.
  (이번 생성은 이 NDK libc++ 로 실행했다.)

## 3. 다운로드 출처와 SHA-256

| 대상 | URL | 저장 위치(tools/golden/models/) | SHA-256 |
|---|---|---|---|
| Silero v6.2.3 ONNX | https://github.com/snakers4/silero-vad/raw/v6.2.3/src/silero_vad/data/silero_vad.onnx (태그 v6.2.3 = 커밋 5cd7945) | `silero_v6.2.3/silero_vad.onnx` | `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3` (pip `silero-vad==6.2.3` 내장 `silero_vad.onnx` 와 동일 해시) |
| pyannote seg-3.0 (sherpa 변환본) | https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2 | `pyannote_seg30_dl/…tar.bz2` → `pyannote_seg30/` 에 해제 | tar.bz2 `24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488` |
| └ **사용 파일 `model.onnx`(fp32)** | (위 압축 안) | `pyannote_seg30/sherpa-onnx-pyannote-segmentation-3-0/model.onnx` | `220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079` |
| └ 미사용 `model.int8.onnx` | (위 압축 안) | 같은 폴더 | `d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d` |
| FSMN ONNX(양자화) | https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/model_quant.onnx | `fsmn_onnx/model_quant.onnx` | `5289eb2aa3c9af2d7a4284bcfa7c3ceb81d360814ed4203239b6c5d0569da8a1` |
| FSMN CMVN | https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/am.mvn | `fsmn_onnx/am.mvn` (→ `golden/fsmn_am.mvn` 로 복사, 커밋) | `6820fef9687708c4fc3fab2530179c8fcea6262daa25514380056cd8f6eb1754` |
| FSMN config | https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/config.yaml | `fsmn_onnx/config.yaml` | `2ef334f2d7776edd86ed696296774f87550206c256bfabc597872ad861831589` |
| py-webrtcvad example.py(참조용 원문) | https://raw.githubusercontent.com/wiseman/py-webrtcvad/e283ca41df3a84b0e87fb1f5cb9b21580a286b09/example.py | `webrtc_ref/example.py` | `536a108f61f7a9fa566516a21de07a300d344ffe1dd2fa4409424e8d79285bb4` |
| py-webrtcvad LICENSE(MIT 고지 원문) | https://raw.githubusercontent.com/wiseman/py-webrtcvad/e283ca41df3a84b0e87fb1f5cb9b21580a286b09/LICENSE | `webrtc_ref/LICENSE` | `c652961f6c92f978ebe4acea6b8b68d4d2d777bd0a1df130c9deffca12d482cd` |
| ten-vad Python 패키지 | `pip install git+https://github.com/TEN-framework/ten-vad.git@d68ab71c8d5fbd8f98e97ae7c9986af76b5d72b1` (공식 repo 설치 방법, 버전 1.0.6.9) | venv site-packages | 포함된 `lib/Linux/x64/libten_vad.so` `5abfe6bf6e9a4fcea6b440240f0a9a0f431ab5006e48a4e16465ebe681ffd90f` |

라이선스: Silero MIT, sherpa 변환본은 원본 pyannote/segmentation-3.0(MIT, HF gated) 변환물,
FSMN 은 FunASR Model License 1.1(출처·모델명 유지 조건), py-webrtcvad MIT(John Wiseman, `gen_webrtc.py` 에 고지 원문 포함),
TEN Apache-2.0+추가조건. 모델·클립은 공개 저장소에 넣지 않는다.

## 4. 클립 (`make_clips.py`)

길이는 일부러 **hop(512/256/480/320/160) 의 배수가 아니게** 했다(끝부분 처리 검증, R14).

| 클립 | 내용 | 샘플 수 |
|---|---|---|
| `kr_customer_01_20s_plus77.wav` | `~/workspace/stt/input_sound/kr/customer/01.wav` 앞 320077 샘플(int16 그대로 복사) | 320077 |
| `en_customer_01_20s_plus77.wav` | `~/workspace/stt/input_sound/en/Customer_Service/01.wav` 앞 320077 샘플 | 320077 |
| `synth_silence_noise_8s_plus123.wav` | 디지털 무음 2 s + 백색잡음 4 s + 무음 2 s + 무음 123 샘플. 잡음 = `np.random.default_rng(0).standard_normal(64000) × 10^(-30/20)`(−30 dBFS RMS) → `round(×32767)` → int16 clip | 128123 |
| `kr_customer_01_70s_plus55.wav` | `kr/customer/01.wav` 앞 1120055 샘플(FSMN 다중 블록·max_single_segment_time 검증용) | 1120055 |

모든 스크립트는 클립을 int16 으로 읽고 float 는 `int16 / 32768.0`(float32) 로 만든다(soundfile/torchaudio 와 같은 정규화).

끝부분 처리 검증 내용: Silero 마지막 창 0 패딩(626 창, 끝 구간 end = 320077 로 clamp), TEN floor 잔여(1250 프레임 + 77 샘플 버림),
WebRTC 잔여(30 ms 666 프레임 + 397 샘플, 20 ms 1000 프레임 + 77 샘플), FSMN 잔여(1998 프레임), pyannote 패딩 창(6.2 절).

## 5. JSON 형식

### 5.1 구간 골든 `golden/<family>_<presetId>_<clipStem>.json` (실제 클립 76개 + 손작성 6개)

공통 키(계획서 형식):

| 키 | 의미 |
|---|---|
| `clip` | 클립 파일명(손작성 골든은 `synthetic_maxspeech_a/b/c`, 음원 없음) |
| `family` | `SILERO` / `PYANNOTE` / `FSMN` / `WEBRTC` / `TEN` |
| `presetId` | 앱 `VadPresets` id |
| `hopSec` | 프레임 간격(초): Silero 0.032, pyannote 0.016875, FSMN 0.010, WebRTC 0.03/0.02, TEN 0.016 |
| `frameTimesSec` | pyannote 만: 각 프레임 중앙 시각(초). 나머지는 `null` |
| `frameScores` | 프레임 점수. float32 값을 **float32 로 정확히 복원되는 최단 10진 표기**로 저장(Kotlin `toFloat()` 가 비트 단위로 같음) |
| `frameDb` | FSMN 만: 프레임 dB. 나머지 `null` |
| `expected` | 기대 구간 `[{"start": 초, "end": 초}]` (시작순) |
| `tool` | 생성 도구·버전 |
| `totalSamples` | (모든 구간 골든) 입력 전체 샘플 수 N. 프레임 수 규칙과 Silero `audio_length_samples` 에 쓴다 |

계열별 추가 키(GoldenLoader 는 무시해도 되지만 정밀 비교에 쓰면 좋다):

| 계열 | 추가 키 |
|---|---|
| SILERO, TEN_SHERPA | `expectedSamples`(정수 샘플 `[start, end]`, 1 샘플 허용 비교용), `windowSamples`(512 또는 256), `params` |
| 손작성(synthetic) | 위 + `synthetic: true`, `branchesTaken`(라이브러리에서 실제 실행된 분기 이름) |
| PYANNOTE | `params`(onset/offset/minDurationOn/minDurationOff) |
| FSMN | `expectedMs`(원본 ms 정수 구간), `params`(적용한 model_conf), `originalConfigMaxSingleSegmentTime`(ModelScope config 원래 값 20000), `blocks`(블록 호출 기록 `[프레임 수, 파형 샘플 수, is_final]`) |
| WEBRTC | `expectedFrames`(`[시작 프레임, 끝 프레임(배타)]`), `params`(mode, frameMs, style, paddingMs, ratio, speechMs, silenceMs, threshold) |
| TEN_DEFAULT | `expectedFrames`, `frameFlags`(라이브러리 flag 0/1), `params` |

`expected` 의 초 값: Silero/TEN_SHERPA = 샘플/16000, WebRTC/TEN_DEFAULT = 프레임 인덱스 × hopSec,
FSMN = ms/1000, pyannote = 프레임 중앙 시각(Binarize 원본 값).

**frameScores 길이 규칙**(verify_golden.py 가 확인): Silero `ceil(N/512)`, TEN `floor(N/256)`, FSMN `1 + (N−400)/160`,
WebRTC RAW·HANGOVER `floor(N/L)`, RING_BUFFER `floor((N−1)/L)`(L = 16·frameMs), pyannote 6.2 절 7·9 단계.
손작성 골든은 Silero `ceil(N/512)`, TEN `floor(N/256)` 로 만들었다.

### 5.2 모델 추론 정합용 `golden/scores_<family>_<clipStem>.json` (12개: SILERO/PYANNOTE/FSMN × 4)

공통 키와 같은 구조이며 `presetId: null`, `expected: null`(구간 없음; 검증 스크립트가 키 존재를 확인하므로 null 로 둠), `totalSamples` 포함.
- SILERO: `frameScores` = 512-hop 확률, 추가 `windowSamples`(512), `contextSamples`(64)
- PYANNOTE: `frameScores` = 집계 speech 점수, `frameTimesSec`, 추가 `windowSamples`(160000), `stepSamples`(16000), `receptiveFieldSize`(991), `receptiveFieldShift`(270)
- FSMN: `frameScores` = silence 확률(`logits[0][t][0]`), 추가 `scoreIndex`(0), `numClasses`(248)

### 5.3 특징 골든 `golden/fbank_<clipStem>.json`, `golden/lfr_cmvn_<clipStem>.json` (8개)

`{"clip", "kind": "fbank"|"lfr_cmvn", "numFrames", "dim", "expected": [[...], ...], "tool", "numFramesTotal", "inputScale": 32768, "knfOptions": "<kaldi 옵션 전체 문자열>"}`.
`fbank` 는 앞 50 프레임 × 80, `lfr_cmvn` 은 앞 10 프레임 × 400(+ `lfrM` 5, `lfrN` 1, `cmvnFile` "fsmn_am.mvn").

change(add)-hyungchul-2026-10-11-13:57 **로컬 전용**: 이 8개 중 사용자 음성 클립(`kr_customer_01_20s_plus77`, `en_customer_01_20s_plus77`, `kr_customer_01_70s_plus55`) 6개는
사용자 음성에서 파생된 특징이라 공개 저장소에 올리지 않는다(`.gitignore`, 파일은 로컬 유지). 저장소에는 합성 클립(`synth_silence_noise_8s_plus123`) 2개만 커밋된다.
`python gen_fbank.py` 로 다시 만든다(클립 WAV 는 `make_clips.py` 로 먼저 생성). 없는 환경에서 `KaldiFbankTest` 는 그 클립만 건너뛴다.

## 6. 계열별 알고리즘 (Kotlin 포팅 기준)

### 6.1 Silero (`gen_silero.py`, `gen_maxspeech.py`)

- 라이브러리 `silero_vad.get_speech_timestamps(audio, model, threshold, sampling_rate=16000, min_speech_duration_ms,
  max_speech_duration_s, min_silence_duration_ms, speech_pad_ms, return_seconds=False)` 를 프리셋 값으로 그대로 호출한다.
  model 은 라이브러리 `OnnxWrapper(models/silero_v6.2.3/silero_vad.onnx, force_onnx_cpu=True)` 를 감싼 기록 래퍼로,
  **라이브러리가 실제로 쓴 확률**을 순서대로 `frameScores` 에 저장한다.
- 모델 추론(Task 12 기준): 창 수 = `ceil(N/512)`, 마지막 창은 0 패딩. 각 창 입력 = `[직전 context 64 샘플 | 현재 512 샘플]`
  (1×576 float32, 첫 창 context 는 0), `state` (2,1,128) float32(처음 0, 매 창 `stateN` 으로 갱신), `sr` int64 스칼라 16000.
  출력 `output`(1,1) = 확률. context ← 입력 576 샘플의 마지막 64 샘플.
- 후처리: silero-vad 6.2.3 의 `get_speech_timestamps_from_probs`(utils_vad.py 421~588 행)가 정의다. 핵심:
  `neg_threshold = max(th − 0.15, 0.01)`, `cur_sample = 512·i`, 시작 `prob ≥ th`, 무음 후보 `prob < neg_threshold`,
  `min_speech_samples = 16000·ms/1000`(float), `max_speech_samples = 16000·maxSec − 512 − 2·pad_samples`,
  `min_silence_at_max_speech = 98 ms`, `use_max_poss_sil_at_max_speech = True`, 마지막 구간은 `audio_length_samples − start > min_speech_samples` 일 때 끝=N,
  pad 단계는 `int()` 절사·`silence_duration // 2` 를 쓰고 끝은 `min(N, …)` 로 clamp. 비교식의 `>`/`>=` 구분을 그대로 옮길 것.
- 검증: 같은 확률을 `get_speech_timestamps_from_probs(..., audio_length_samples=N)` 에 다시 넣은 결과가
  라이브러리 결과와 **완전히 같음**을 32 조합 모두 assert(통과). 따라서 Kotlin 은 "확률 입력 → 구간" 포팅만으로 일치해야 한다.
- **max_speech 분할 분기(손작성 골든, `gen_maxspeech.py`)**: 실제 클립에서는 SILERO_SHERPA/TEN_SHERPA(20 s) 분할이 일어나지 않아
  손작성 확률(기본 0.02, 음성 0.9, 0.1 = neg_th 미만 무음, 0.4/0.45 = neg_th~th 사이)을 라이브러리 함수에 넣었다.
  `sys.settrace` 로 라이브러리 줄 실행을 기록해 각 케이스가 의도한 분기를 탔는지 assert, 모든 구간 길이 ≤ 20 s, 구간 ≥ 2개 확인.
  | 케이스 | N | 실행 분기(`branchesTaken`) | 내용 |
  |---|---|---|---|
  | `synthetic_maxspeech_a` | 32 s + 37 | possible_end_append, possible_end_cut, continue_after_cut | 여러 짧은 무음 중 가장 긴 무음(9.0–9.4 s)에서 절단 후 바로 다음 구간 시작(9.4 s), 다시 27.0 s 최장 무음에서 절단 |
  | `synthetic_maxspeech_b` | 27 s + 211 | fallback_cut | neg_th 미만 무음이 없거나 98 ms 이하뿐 → possible_ends 비어 현재 샘플에서 강제 절단(구간 = 정확히 20 s), 다음 프레임에서 재시작 |
  | `synthetic_maxspeech_c` | 45 s + 99 | possible_end_append, possible_end_cut, continue_after_cut, end_at_audio_length | 0.2 s 버스트 버림, 절단 시점이 무음 진행 중, 마지막 구간 끝 = N |
  window 256(TEN_SHERPA) 은 6.6 절 방식으로 호출했다.
- 비교 권장: `expectedSamples` 와 1 샘플 허용. 확률 비교는 Kotlin 에서도 double 로 하는 편이 안전하다(float 임계값 0.3f 등 미세 차이 방지).

### 6.2 pyannote (`gen_pyannote.py`) — 원본 pipeline 비교 미수행(HF gated, 토큰 없음)

HF 원본 `pyannote/segmentation-3.0` 체크포인트는 gated 이고 HF_TOKEN 이 없어 **원본 pipeline 비교(expected_original)는 수행하지 않았다.**
대신 sherpa 변환본 **`model.onnx`(fp32; int8 아님)** 를 **pyannote.audio 3.4.0 의 실제 코드 경로**에 끼워 넣었다.
**앱도 같은 `model.onnx`(fp32, SHA-256 `220ad67c…e1079`)를 써야 점수 정합(≤ 1e-3)이 성립한다.**
경로: `OnnxPyanNet`(PyanNet 상속, forward 만 ONNX 추론, 배치 1) → `VoiceActivityDetection(segmentation=model)` 의
`_segmentation`(= `Inference(model, pre_aggregation_hook=max over speakers)`) → `Binarize`.
사양은 segmentation-3.0 과 같게 직접 지정했다: MONO_LABEL_CLASSIFICATION, FRAME, duration 10.0 s, warm_up (0,0),
classes 3, powerset_max_classes 2(→ 7 클래스). PyanNet 수용 영역(991/270 샘플)이 ONNX 메타데이터와 같음을 assert.

**warm_up (0,0) 가정의 근거와 한계**: ONNX 메타데이터에는 warm_up 항목이 없다(키: num_speakers 3, powerset_max_classes 2,
num_classes 7, window_size 160000, receptive_field_size 991, receptive_field_shift 270, sample_rate 16000). 확인 가능한 근거는
(1) pyannote.audio 3.4.0 `Specifications.warm_up` 기본값 `(0.0, 0.0)`, (2) segmentation-3.0 을 학습하는 `SpeakerDiarization` task 의
`warm_up` 인자 기본값 `0.0`, (3) sherpa `export-onnx.py` 가 duration 10.0·수용 영역만 검사하고 warm_up 을 메타데이터로 내보내지 않음
(sherpa 쪽 구현도 warm-up 을 쓰지 않는다는 뜻으로 해석). 체크포인트 hparams 를 직접 열어 확인하지는 못했다(gated).
warm_up 이 0 이 아니면 Inference step 도 달라지므로(`step = warm_up[0]` if ≠ 0) 원본 비교가 가능해지면 재확인할 것.

**speech 점수 집계 알고리즘(정확한 정의, numpy 독립 구현 `reference_scores` 가 라이브러리와 |차| ≤ 1e-6 로 일치함을 assert):**

1. 입력 x: float32 파형(N 샘플). W = 160000(메타 `window_size`), S = 16000(step 1 s = `round(0.1 × 10 s × 16000)`).
2. 완전한 창 수 `C0 = (N ≥ W) ? (N − W) / S + 1 : 0`(정수 나눗셈). 마지막 창 존재 `hasLast = (N < W) || ((N − W) % S > 0)`.
   창 c(0 ≤ c < C0) = `x[c·S, c·S+W)`. hasLast 면 창 C0 = `x[C0·S, N)` 뒤를 0 으로 채워 W 샘플. 전체 창 수 `C = C0 + hasLast`.
   **클립별 경로**:
   - `kr/en_customer_01_20s_plus77`(N 320077): C0 = 11, hasLast = true(77 > 0) → **완전 창 ≥ 1 + 0 패딩 마지막 창 + loose crop**. T = 1245 → crop 후 1186.
   - `kr_customer_01_70s_plus55`(N 1120055): C0 = 61, hasLast = true(55 > 0) → 같은 경로. crop 후 4149 프레임.
   - `synth_silence_noise_8s_plus123`(N 128123 < W): C0 = 0, hasLast = true → 패딩 창 1개만. T = 594 → crop 후 475.
   - (hasLast = false 경로는 현재 클립에 없다. N = W + k·S 이면 crop 없이 T 그대로이며, 마지막 창이 덮지 않는 끝 프레임 몇 개는 점수 0 이 된다.)
3. 창마다 ONNX 입력 `x` (1,1,160000) → 출력 `y` (1,589,7) (powerset log-softmax). 창당 프레임 수 F = 589.
4. 프레임 k 의 speech_c[k] = `argmax_j y[k][j] ≠ 0 ? 1 : 0`. (pyannote `Powerset.to_multilabel(soft=False)` = argmax one-hot × mapping,
   mapping 행 0 = 무음 {}, 1~3 = 단일 화자, 4~6 = 화자쌍. 이어서 VAD pipeline 의 `np.max(scores, axis=-1)` = 화자 중 최대.
   동률이면 첫 인덱스(torch.argmax 규칙).)
5. 프레임 SlidingWindow: 간격 h = 270/16000 = 0.016875 s, 길이 d = 991/16000 = 0.0619375 s, start 0
   (aggregate 가 start 를 창 SlidingWindow 의 start 0 으로 다시 만든다).
6. 가중치 `w[k] = 0.54 − 0.46·cos(2πk/(F−1))`, k = 0..F−1 (`np.hamming(589)`; `Inference.slide` 가 `hamming=True` 로 호출).
   warm_up (0,0) 이므로 추가 가중치 없음.
7. 전체 프레임 수 `T = rint((10 + (C−1)·1) / h) + 1` (`closest_frame(마지막 창 끝 + d/2) + 1`, rint = 가장 가까운 정수, .5 는 짝수).
   창 c 의 시작 프레임 `s_c = rint(c / h)`(= rint(c·16000/270)).
8. `acc[s_c + k] += speech_c[k]·w[k]`, `cnt[s_c + k] += w[k]` (float32 배열에 누적), 평균 `avg[t] = acc[t] / max(cnt[t], 1e-12)`.
   어떤 창도 덮지 않는 프레임은 0(= `missing=0.0`).
9. hasLast 면 loose crop: `j = floor((N/16000) / h)`, 앞에서 `min(j+1, T)` 프레임만 남긴다(마지막 프레임 중앙이 오디오 끝을
   약간 넘을 수 있다: 20 s+77 → 20.0278 s). hasLast 가 아니면 T 그대로.
10. 프레임 시각(Binarize 기준) = pyannote 라이브러리 식 그대로 `start_i = 0 + i·h`, `t_i = 0.5·(start_i + (start_i + d))`
    (`SlidingWindow.__getitem__` → `Segment(start, start + duration).middle`) → `frameTimesSec`.
    **Kotlin 은 JSON 의 `frameTimesSec` 을 그대로 쓰거나 같은 식(같은 연산 순서, double)을 사용한다.**

**Binarize(pyannote.audio.utils.signal.Binarize, pad 0):**
- 초기: `active = score[0] > onset`, start = t_0. 이후 프레임 i(1..T−1): active 이고 `score < offset` 이면 구간 [start, t_i] 추가 후 비활성;
  비활성이고 `score > onset` 이면 start = t_i, 활성. 끝까지 활성이면 [start, t_{T−1}] 추가. (`>`/`<` 모두 엄격)
- `min_duration_off > 0` 이면 `Annotation.support(collar=min_duration_off)`: 시간순으로 이웃 구간 사이 간격이
  `< min_duration_off`(또는 1 µs 미만)이면 병합.
- 그 다음 `min_duration_on > 0` 이면 길이 `< min_duration_on` 인 구간 삭제(병합 후 삭제 순서).
- 검증: PYA3_DEFAULT 결과가 `pipeline(file)`(VoiceActivityDetection.apply 전체)과 같음을 assert(통과).
- 비교 허용 오차: 1 프레임(0.016875 s).

### 6.3 FSMN (`gen_fsmn.py`)

- 실행: `funasr_onnx.Fsmn_vad(model_dir=<임시 폴더>, quantize=True, intra_op_num_threads=1)` 에 float32 파형(ndarray)을 넣는다.
  임시 폴더 = `model_quant.onnx`, `am.mvn` 복사 + 원본 `config.yaml` 의 `model_conf` 를 프리셋 값으로 덮어쓴 사본.
- **주의: ModelScope ONNX 패키지의 `config.yaml` 은 `max_single_segment_time: 20000` 이다.** 앱 프리셋 FSMN_DEFAULT 는
  spec(funasr/fsmn-vad config) 대로 60000 이므로 골든도 60000(FSMN_ASR30 은 30000)으로 덮어써 만들었다. 나머지 model_conf 값은 프리셋과 같다.
- 후킹: `funasr_onnx.vad_bin.E2EVadModel` 모듈 속성을 기록용 하위 클래스로 교체(site-packages 파일은 수정 안 함).
  `ComputeScores` 직후 블록 scores, `ComputeDecibel` 직후 누적 decibel, `__call__` 마다 (프레임 수, 파형 샘플 수, is_final) 을 복사한다.
- 전처리: 6.7 절의 fbank → LFR(5,1) → CMVN 을 **전체 파형에 한 번** 계산. T = `1 + (N − 400) / 160`(정수 나눗셈).
  ONNX 입력 `speech` (1, 블록 프레임 수, 400) float32, `in_cache0..3` (1,128,19,1), 출력 `logits` (1, 블록 프레임 수, 248)(softmax 확률), `out_cache0..3`.
- **sil prob = `logits[0][t][0]`** (config `sil_pdf_ids: [0]`, `silence_pdf_num: 1`) → `frameScores`(블록을 이어 붙인 전체 T 개).
  원본 판정(`GetFrameState`): `noise_prob = ln(sil)·speech_2_noise_ratio`, `speech_prob = ln(1 − sil)`,
  `exp(speech_prob) ≥ exp(noise_prob) + speech_noise_thres(0.6)` 이고 `snr ≥ snr_thres`, `dB ≥ decibel_thres` 이면 음성 프레임.
  dB < decibel_thres 면 바로 무음. 무음 판정 프레임에서 잡음 평균 dB 갱신: 평균 < −99.9 이면 = dB, 아니면 `(dB + 평균·99) / 100`
  (`noise_frame_num_used_for_snr` 100). snr = dB − 잡음 평균.
- **frame dB[t] = `10·log10( Σ_{i=0}^{399} x[160·t + i]² + 1e-6 )`**, x 는 **−1~1 float 파형(×32768 하지 않음)**,
  t = 0 .. T−1. 개수는 T 와 같음(assert). 완전 무음 프레임은 −60.0.
- **다중 블록 처리(funasr_onnx 0.4.3 `Fsmn_vad.__call__` 그대로, Kotlin 재현 기준)**:
  1. `S = min(T, 6000)`. 블록 시작 o 는 `range(0, T, S)` 로 미리 정해진다(o = 0, S, 2S, … < T; 이 step S 는 루프 시작 시 한 번만 계산).
  2. 변수 `step` 은 처음 S. 각 o 에서: `o + step ≥ T − 1` 이면 `step = T − o`, `is_final = True`; 아니면 `is_final = False`.
  3. 특징 `feats[o : o + step]`, 파형 `x[o·160 : min(N, (o + step − 1)·160 + 400)]` 를 넘긴다
     → **이웃 블록 파형은 240 샘플(400 − 160) 겹친다.** 블록 파형에서 다시 계산한 dB 는 전역 dB 와 같은 값이 이어진다.
  4. ONNX 는 블록마다 호출하고 `in_cache` 는 직전 블록의 `out_cache`(첫 블록은 0) → FSMN 메모리가 블록 경계에서 이어진다.
  5. **같은 E2EVadModel 인스턴스**를 `vad_scorer(scores, 블록 파형, is_final, max_end_sil=800, online=False)` 로 이어 부른다.
     is_final=False: `frm_cnt += 블록 프레임 수`, `DetectCommonFrames` 가 전역 프레임 `frm_cnt − 블록길이 … frm_cnt − 1` 을 처리
     (점수는 `scores[0][t − idx_pre_chunk]`), 끝에 `idx_pre_chunk += 블록길이`. 이 호출은 시작·끝점이 모두 확정된 구간만 반환한다.
     is_final=True: `DetectLastFrames` 가 마지막 프레임만 `is_final_frame=True` 로 처리, 남은 구간을 모두 반환하고 `AllResetDetection`.
  6. 원본의 특이점: `T > 6000` 이고 `T % 6000 == 1` 이면(예: o + 6000 = T − 1) 마지막 블록이 final 로 처리된 뒤 range 의 다음 o 가
     한 번 더 돌아 이미 처리한 프레임을 다시 final 로 처리한다(리셋된 상태기계로). 골든 클립은 해당 없음(T = 6998).
  - 골든: `kr_customer_01_70s_plus55` 는 T = 6998 → `blocks` = `[[6000, 960240, false], [998, 159920, true]]`.
- **FSMN_DEFAULT vs FSMN_ASR30**: 20 s·8 s 클립은 결과가 같다(가장 긴 구간 18.2 s < 30 s 라 max_single_segment_time 이 발동하지 않음).
  70 s 클립은 다르다: DEFAULT `[1.78, 40.59] [40.87, 44.85] [45.13, 69.98]`,
  ASR30 `[1.78, 31.79] [31.79, 40.59] [40.87, 44.85] [45.13, 69.98]`(시작 프레임 178 + 3000 프레임 초과 시점 31.79 s 에서 강제 종료 후 즉시 새 구간).
- 상태기계 포팅 기준은 이 골든을 만든 `funasr_onnx/utils/e2e_vad.py`(funasr_onnx 0.4.3) 이다.
- `expected` = 원본 ms 구간 / 1000. 허용 오차 0.010 s.

### 6.4 WebRTC (`gen_webrtc.py`)

- 프레임 판정: `webrtcvad.Vad(mode).is_speech(PCM16 프레임, 16000)`. WebRTC VAD 는 상태(잡음 적응)가 있으므로 프리셋마다
  **새 인스턴스로 처음 프레임부터 순서대로** 처리한다. `frameScores` = 1.0(voiced)/0.0.
- RAW, HANGOVER 의 프레임 = `floor(N / L)` 개 완전한 프레임(L = 16·frameMs 샘플; libfvad 사용 방식과 같음, 잔여 샘플 버림).
- **WEBRTC_LIB_DEFAULT(RAW, mode 0, 30 ms)**: 연속 voiced 프레임 병합 `[첫 프레임, 첫 unvoiced 프레임)`. hangover(0,0)과 같음을 assert.
- **WEBRTC_PYEXAMPLE(RING_BUFFER, mode 3, 30 ms, padding 300 ms)**: py-webrtcvad `example.py` 의 함수를 그대로 복사해 실행.
  변경점은 (1) stdout 출력 제거 (2) 오디오 바이트 대신 (첫 프레임 번호, 마지막 프레임 번호) 반환 뿐이다.
  - 프레임 생성기 조건은 원본대로 `offset + n < len(audio)`(엄격한 <) → 프레임 수 = `floor((N − 1) / L)`
    (N 이 L 의 배수면 마지막 프레임이 빠진다. 현재 클립은 배수가 아니라 floor(N/L) 과 같음: 666, 266, 2333).
  - ring buffer 길이 = `int(300/30)` = 10. 비트리거: (프레임, voiced) 추가 후 voiced 수 `> 0.9 × 10`(= 10개 모두) 이면 트리거,
    구간 시작 = 버퍼의 첫 프레임, 버퍼 비움. 트리거: 프레임 수집 + 버퍼 추가 후 unvoiced 수 `> 9` 이면 구간 종료(현재 프레임 포함),
    버퍼 비움. 입력 끝에서 트리거 상태면 마지막 프레임까지 구간.
  - 초 = 시작 프레임 × 0.03, 끝 = (마지막 프레임 + 1) × 0.03. `expectedFrames` 는 `[시작, 마지막+1)`.
- **WEBRTC_ANDROIDVAD(HANGOVER, mode 3, 20 ms, speech 50, silence 300)**: 6.5 절 SimpleHangover(threshold 0.5).
- 주의: 골든 프레임 판정은 py-webrtcvad(내장 WebRTC C 코드)이고 앱은 libfvad 이다. 후처리 골든은 `frameScores` 를 입력으로
  쓰므로 영향 없지만, 프레임 판정 자체의 일치는 이 골든으로 검증하지 않는다(기기 테스트 대상).

### 6.5 SimpleHangover 정의 (`hangover_ref.py` — Kotlin SimpleHangover 가 맞춰야 할 정의)

계획서 Task 10 의미를 그대로 구현했다. WEBRTC_ANDROIDVAD, TEN_DEFAULT(0/0), 이후 Yamnet 이 사용한다.

1. 음성 프레임: `score ≥ threshold`.
2. 필요 프레임 수: `needSpeech = max(1, ceil(round(speechMs / hopMs, 9)))`, `needSilence = max(1, ceil(round(silenceMs / hopMs, 9)))`
   (소수 9자리 반올림은 15.000000001 같은 부동소수 오차 방지. 예: 300/20 → 15, 50/20 → 3, 30/16 → 2, 0 → 1).
3. 비음성 상태: 음성 프레임이면 연속 길이 +1(연속 첫 프레임 기억), 무음 프레임이면 연속 길이 0.
   연속 길이가 needSpeech 에 도달하면 음성 상태로 바꾸고 **구간 시작 = 그 연속의 첫 프레임**.
4. 음성 상태: 음성 프레임이면 무음 연속 길이 0(짧은 무음 메움), 무음 프레임이면 무음 연속 +1(무음 첫 프레임 기억).
   무음 연속이 needSilence 에 도달하면 **구간 끝 = 그 무음 연속의 첫 프레임**, 비음성 상태로(음성 연속 길이 0).
5. 입력 끝에서 음성 상태면: 진행 중인 무음 연속이 있으면 그 첫 프레임, 없으면 n(전체 프레임 수)에서 끝.
6. 시각 = 프레임 인덱스 × hopSec. speechMs = silenceMs = 0 이면 연속 음성 프레임 병합(RAW)과 같다(assert).
7. 자체 검사(import 시): hop 0.01, 음성 50·무음 20·음성 50, silenceMs 300 → `[0.0, 1.2]` 1개.

### 6.6 TEN (`gen_ten.py`)

- 공식 `ten_vad.TenVad(hop_size=256, threshold=0.5)` 로 `floor(N/256)` 개 int16 프레임을 순서대로 처리(상태 유지, 잔여 샘플 버림) → (확률, flag).
  `frameScores` = 확률, `frameFlags` = flag. 네 클립 모두 `flag == (prob ≥ 0.5)` 불일치 0 프레임(확인).
- **TEN_DEFAULT**: 라이브러리 flag 연속 병합. `hangover_frames(prob, 0.016, 0.5, 0, 0)` 과 같음을 assert(통과).
- **TEN_SHERPA**(threshold 0.5, minSpeech 250, minSilence 500, pad 0, maxSpeech 20 s): silero-vad 6.2.3 의 확률 입력 진입점
  `get_speech_timestamps_from_probs` 를 사용(별도 복사본 `silero_post.py` 불필요). 이 함수는 16 kHz 에서 window 512 를 고정하므로
  `sampling_rate=8000`(→ window 256) 으로 호출하고 ms·초 파라미터를 모두 2배로 넘긴다:
  `8000·(2·ms)/1000 = 16000·ms/1000`, `8000·(2·20) − 256 − 2·pad = 16000·20 − 256 − 2·pad`,
  `min_silence_at_max_speech = 2·98 → 8000·196/1000 = 1568 = 16000·98/1000`, `cur_sample = 256·i`, `audio_length_samples = N`(16 kHz),
  `return_seconds=False`, step 1. 임계값은 그대로. 따라서 결과는 "16 kHz, window 256" 으로 같은 알고리즘을 돌린 값과 정확히 같다
  (모든 값이 정수라 부동소수 오차 없음). Kotlin: `SileroTimestamps.apply(scores, windowSamples=256, sampleRate=16000, totalSamples=N, params)`.

### 6.7 fbank / LFR / CMVN (`gen_fbank.py`)

- kaldi-native-fbank 1.22.3 `OnlineFbank`, 입력 `float 파형 × 32768`. 옵션(=`funasr_onnx` WavFrontend + FSMN config frontend_conf):
  samp_freq 16000, frame_shift_ms 10, frame_length_ms 25, **dither 0**, preemph_coeff 0.97, remove_dc_offset true,
  window_type **hamming**, round_to_power_of_two true(FFT 512), blackman_coeff 0.42(미사용), **snip_edges true**,
  num_bins 80, low_freq 20, high_freq 0(→ 8000), vtln 미사용, htk_mode false, is_librosa false(kaldi mel 식),
  use_energy false, **energy_floor 0**, raw_energy true, htk_compat false, use_log_fbank true, use_power true.
  로그 하한은 kaldi 규칙(float epsilon) → 완전 무음 프레임 값 ≈ −15.9424(합성 클립 앞 50 프레임).
- 프레임 수 T = `1 + (N − 400) / 160`. 직접 실행 결과가 `WavFrontend.fbank` 와 완전히 같음을 assert(통과).
- LFR(m=5, n=1) → CMVN 순서(FunASR `lfr_cmvn`): 앞에 첫 프레임 2개 복제, 출력 i = 패딩 프레임 i..i+4 연결(400 차원),
  끝에서 부족하면 마지막 프레임 복제. CMVN = `(x + mean) × var` (am.mvn 의 `<AddShift>` 다음 줄 `<LearnRateCoef>` 값 = mean,
  `<Rescale>` 다음 줄 값 = var, float64 로 계산 후 float32). numpy 독립 구현이 WavFrontend 와 완전히 같음을 assert(통과).
- **계획서 문구와의 차이**: brief 는 "am.mvn 적용 후 LFR" 이라 썼지만 FunASR 원본은 **LFR 후 CMVN**(am.mvn 이 400 차원)이며 골든은 원본 순서를 따랐다.

## 7. 검증 결과 (이번 생성)

- `verify_golden.py`: `OK: JSON 102개, 구간 골든 76개 + 손작성 max_speech 골든 6개, expected 키 전부 존재, hop·프레임 수 규칙 일치, 합성 클립 Silero 무음 미포함`.
- 합성 클립: Silero 8 프리셋 모두 구간 0개, pyannote 4 프리셋 0개. (FSMN 은 1.75–2.39 s, WebRTC RAW/HANGOVER, TEN 은 잡음 시작부를 음성으로 판정.)
- 스크립트 내부 assert 전부 통과: Silero from_probs 재현, pyannote 독립 구현·pipeline.apply 일치(패딩 창 경로 포함),
  fbank/LFR 독립 구현 일치, WebRTC RAW = hangover(0,0), TEN_DEFAULT flag 병합 = hangover(prob, 0.5, 0, 0),
  FSMN 마지막 블록만 is_final, 손작성 max_speech 분기 실행·구간 ≤ 20 s.

## 8. 한계·주의

- pyannote 원본 pipeline(HF 체크포인트) 비교 미수행(HF gated, 토큰 없음). sherpa 변환본 ONNX 와 원본 PyTorch 의 수치 차이,
  warm_up (0,0) 가정은 확인하지 못했다(6.2 절 근거 참고).
- pyannote 골든은 fp32 `model.onnx` 기준이다. 앱이 `model.int8.onnx` 를 쓰면 점수 정합(≤ 1e-3)은 보장되지 않는다.
- FSMN 다중 블록의 `T % 6000 == 1` 특이 경로(6.3 절 6항)는 골든으로 검증하지 않는다.
- max_speech 분할은 손작성 확률로만 검증한다(실제 음원에서는 발생하지 않음).

## 9. Yamnet (`gen_yamnet.py`) — change(add)-hyungchul-2026-10-10-15:43 (Task 16)

### 9.1 재현

```bash
cd /home/hyungchul/AndroidStudioProjects/STT_OnDevice
python3 -m venv tools/golden/.venv-yamnet
tools/golden/.venv-yamnet/bin/pip install -r tools/golden/requirements-yamnet.lock.txt
# 모델·클래스 표를 새 빈 폴더 tools/golden/models/yamnet/ 에 받는다(9.2 표). Kaggle tar.gz 의 1.tflite 를 yamnet.tflite 로 둔다.
tools/golden/.venv-yamnet/bin/python -I tools/golden/gen_yamnet.py
```

- 기존 `.venv`(torch 등)와 섞지 않으려고 별도 venv 를 쓴다. ai-edge-litert 2.3.0 = 앱 LiteRT(`com.google.ai.edge.litert:litert:2.3.0`)와 같은 버전.
- 결정적: 기본 옵션(XNNPACK), 스레드 1·4·기본 모두 같은 점수(확인함). XNNPACK 을 끄면(내장 커널) 1317 중 282 프레임이 1 단계 이상 달라진다.

### 9.2 다운로드 출처·SHA-256·라이선스 (`tools/golden/models/yamnet/`, gitignore)

| 대상 | URL | 저장 이름 | SHA-256 | 라이선스 |
|---|---|---|---|---|
| Yamnet TFLite(공식, Kaggle `google/yamnet` tfLite `classification-tflite` v1) | https://www.kaggle.com/api/v1/models/google/yamnet/tfLite/classification-tflite/1/download (→ `yamnet-tflite-classification-tflite-v1.tar.gz`) | `kaggle_yamnet-tflite-classification-tflite-v1.tar.gz` | `1b971b2132760273a5fb8f5dc504e86783b27853bb3135d555ae06f1e1e365f3` | Apache-2.0 (Kaggle 모델 페이지 instance licenseName "Apache 2.0") |
| └ **사용 파일** (tar 안 `1.tflite`, 4126810 B) | (위 압축 안) | `yamnet.tflite` | `10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de` | 위와 같음 |
| (대조) android-vad v2.0.10 번들 모델 | https://raw.githubusercontent.com/gkonovalov/android-vad/1b006d0aaf8fa269986a8c95a196055550c9b877/yamnet/src/main/assets/yamnet.tflite | (받아 해시만 비교 후 삭제) | `10c95ea3…05c317de` — **Kaggle 공식 파일과 완전히 같음** | 번들 `LICENSE` = Apache-2.0(tensorflow/models) |
| 클래스 표 | https://raw.githubusercontent.com/tensorflow/models/34a21326906b9574fa11c4d6d0a5c534ff039267/research/audioset/yamnet/yamnet_class_map.csv (이 파일의 마지막 변경 커밋 dfffd62, 2019-11-21) | `yamnet_class_map.csv` (→ `app/src/test/resources/yamnet/` 로 복사, 커밋) | `cdf24d193e196d9e95912a2667051ae203e92a2ba09449218ccb40ef787c6df2` | Apache-2.0 (tensorflow/models) |

- 모델 입출력(ai-edge-litert 로 확인): 입력 `waveform_binary` float32 `[15600]`, 출력 float32 `[1, 521]`.
  내부는 float 전처리(RFFT2D·COMPLEX_ABS·LOG) → QUANTIZE → **int8** MobileNet → LOGISTIC → DEQUANTIZE. 그래서 **점수는 k/256 값만 가진다**
  (골든 클립 1317 프레임에 서로 다른 값 25개).
- tflite 끝에 붙은 zip(메타데이터 연관 파일) `yamnet_label_list.txt` 521 줄이 CSV `display_name` 521 개와 완전히 같고 index 0 = `Speech`
  (`gen_yamnet.py` 가 매번 assert). 안드로이드 `java.util.zip.ZipFile` 은 앞에 모델 바이트가 붙은 zip 을 거부하므로 기기 테스트는 SHA-256 으로 같은 파일인지 본다.

### 9.3 android-vad(gkonovalov/android-vad v2.0.10, MIT) Yamnet 동작 — 원본 읽기

태그 `2.0.10` = 커밋 `1b006d0aaf8fa269986a8c95a196055550c9b877`.

- `yamnet/src/main/java/com/konovalov/vad/yamnet/VadYamnet.kt`
  (https://github.com/gkonovalov/android-vad/blob/1b006d0aaf8fa269986a8c95a196055550c9b877/yamnet/src/main/java/com/konovalov/vad/yamnet/VadYamnet.kt)
  - `TensorAudio.create(16 kHz, 15600)` 링버퍼 1개, `AudioClassifier.createFromFileAndOptions(.., "yamnet.tflite", maxResults 1)`.
  - `classifyAudio(...)` 마다 `tensor.load(audioData)` 후 `classifier.classify(tensor)`: 받은 프레임(FRAME_SIZE 샘플)을 링버퍼에 넣고
    **가장 최근 15600 샘플(시작 직후에는 앞이 0)** 을 한 번 분류한다. ⇒ hop = 프레임 크기, 창 = 0.975 s.
  - `getResult`: 최고점 1개 클래스(maxResults 1) 중 `score > threshold` 이면 그 label, 아니면 "Silence".
    `threshold()`: NORMAL 0.3, AGGRESSIVE 0.5, VERY_AGGRESSIVE 0.85, 그 외 0.
  - `isContinuousVoice(label, event)`: `maxSpeechFramesCount = getFramesCount(16000, frameSize, speechDurationMs)`
    (`utils/AudioUtils.kt`: `durationMs / (frameSize / (sampleRate / 1000))`, 정수 나눗셈 → 243 이면 `30 / 15 = 2`).
    label 이 같으면 speech 카운트 증가(최대 max+1), 카운트 > max 이면 silence 카운트 0·음성 반환.
    다르면 silence 카운트 증가, silence 카운트 > max 이면 speech 카운트 0·무음, 아니고 speech 카운트 > max 이면 아직 음성 유지.
- `config/FrameSize.kt`: `FRAME_SIZE_243(243), 487, 731, 975` — 한 번에 넣는 샘플 수(hop). README 권장값 243, NORMAL, silence 30 ms, speech 30 ms.
- `tensorflow-lite-task-audio:0.4.4` 의 `TensorAudio`(tflite-support v0.4.4 = 커밋 133228616ca14e1b64c03f6a0ea8610f41a60783,
  https://github.com/tensorflow/tflite-support/blob/v0.4.4/tensorflow_lite_support/java/src/java/org/tensorflow/lite/support/audio/TensorAudio.java):
  `load(short[])` 는 `src * 1.f / Short.MAX_VALUE`(**÷32767**), `FloatRingBuffer` 는 0 으로 시작해 오래된 것 → 최근 순으로 꺼낸다.

### 9.4 본 앱 재현 방식(`YamnetVad.kt`)과 골든

- hop = **243 샘플(15.1875 ms)** 로 고정(FRAME_SIZE_243). 프레임 수 = `floor(N/243)`(잔여 버림).
  프레임 i 의 창 = 원본 int16 `[(i+1)·243 − 15600, (i+1)·243)`, 음수 위치 0, 값 = `float32(int16) / float32(32767)`.
  프레임 i 시각 = `i × 243/16000` s(그 프레임 시작; 판정은 그 프레임 끝까지의 0.975 s 를 본다 → 구간 끝이 실제 발화 끝보다 최대 ~1 s 늦을 수 있음).
- 프레임 점수 = 출력 index 0(Speech) 점수. 구간 = **SimpleHangover**(6.5 절, threshold, speech 30 ms, silence 30 ms → 필요 프레임 ceil(30/15.1875) = 2).
- `golden/scores_YAMNET_kr_customer_01_20s_plus77.json`: 5.2 절 점수 형식 + `totalSamples` 320077, `windowSamples` 15600, `hopSamples` 243,
  `classIndex` 0, `numClasses` 521, `inputScale` 32767, `hopSec` 0.0151875. 프레임 1317, Speech 점수 0.00390625..0.99609375.
- **android-vad 와 다른 점**(컨트롤러 판정: 프로젝트 공통 SimpleHangover 유지, 프리셋 note 에 명시):
  1. 음성 프레임 판정: android-vad = "최고점 클래스가 Speech 이고 점수 > threshold", 본 앱 = "Speech 점수 ≥ threshold".
     골든 클립에서 판정이 다른 프레임: NORMAL 0/1317, AGGRESSIVE 9/1317(본 앱 1150 vs android-vad 1141), VERY_AGGRESSIVE 0/1317.
  2. 시작·종료 확정 규칙: android-vad 는 정수 나눗셈 2 프레임을 "초과"(3 번째부터)하고 speech 카운트를 무음 확정 때까지 유지(연속 아님),
     본 앱은 연속 2 프레임(SimpleHangover). 구간 시작 위치도 SimpleHangover 정의(연속의 첫 프레임)를 따른다.
  3. 입력: android-vad 의 16-bit PCM 경로(÷32767)를 재현했다. float 입력 경로(`×32767` 후 절삭)는 쓰지 않는다.

### 9.5 기기 검증 결과(Galaxy S22 Ultra SM-S908N, Android 16 — S25 Ultra 아님)

- `NativeSmokeTest.yamnetMatchesGoldenScores`: 1317 프레임 중 1316 프레임 비트 일치, 1 프레임(#1154) 기기 0.9921875 / 골든 0.98828125
  (양자화 1 단계 = 1/256 차이). YAMNET 3 프리셋 구간은 기기 점수·골든 점수에서 완전히 같음.
  int8 모델이라 차이는 0 또는 1/256 의 배수뿐이어서 1e-3 기준은 1 프레임만 달라도 실패한다 →
  기준을 "값이 다른 프레임 ≤ 1 %, 최대 차이 ≤ 1/256" 로 두었다(보고서 우려사항).
- `yamnetRuns`: 20 s(1316 프레임) 프리셋당 약 1.76 s(프레임당 약 1.3 ms, LiteRT 기본 옵션).

