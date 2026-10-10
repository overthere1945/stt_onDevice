# tools/golden — VAD 골든 데이터 생성 도구

change(add)-hyungchul-2026-10-10-12:19

이 폴더의 Python 스크립트는 각 VAD 의 **원본 Python 구현**으로 짧은 음원 클립 3개를 처리해
"기준 결과(골든)" JSON 을 만든다. Kotlin 으로 포팅한 후처리·모델 추론은 이 JSON 과 비교해 정합성을 검증한다
(계획서 Task 8~12, spec §7.4). JSON 은 주석을 달 수 없으므로 형식·의미를 이 문서에서 설명한다.

- 출력(커밋): `app/src/test/resources/golden/*.json`, `app/src/test/resources/golden/fsmn_am.mvn`
- 출력(커밋 안 함, gitignore): `app/src/test/resources/golden/audio/*.wav`(클립), `tools/golden/.venv/`, `tools/golden/models/`

## 1. 파일 구성

| 파일 | 역할 |
|---|---|
| `requirements.txt` | 의존성 목록(torch CPU 휠 인덱스 포함) |
| `requirements.lock.txt` | 실제 설치된 정확한 버전(`pip freeze`) |
| `golden_common.py` | 경로·프리셋 값(앱 `VadPresets.kt` 와 동일)·클립 로딩·JSON 저장 공통 함수 |
| `hangover_ref.py` | **SimpleHangover 정의(기준 구현)** 와 연속 프레임 병합(RAW). import 시 계획서 손작성 케이스 자체 검사 |
| `make_clips.py` | 클립 3종 생성 |
| `gen_silero.py` | Silero 8 프리셋 × 3 클립 + `scores_SILERO_*` |
| `gen_pyannote.py` | pyannote 4 프리셋 × 3 클립 + `scores_PYANNOTE_*` |
| `gen_fsmn.py` | FSMN 2 프리셋 × 3 클립 + `scores_FSMN_*` |
| `gen_webrtc.py` | WebRTC 3 프리셋 × 3 클립 (py-webrtcvad `example.py` 의 `frame_generator`/`vad_collector` 복사본 포함, MIT) |
| `gen_ten.py` | TEN 2 프리셋 × 3 클립 |
| `gen_fbank.py` | `fbank_*`, `lfr_cmvn_*`, `fsmn_am.mvn` 복사 |
| `verify_golden.py` | 계획서 Step 4 검증(개수 ≥ 45, `expected` 키, 조합 누락, 합성 클립 Silero 무음 미포함) |

## 2. 재현 방법

```bash
cd /home/hyungchul/AndroidStudioProjects/STT_OnDevice
python3 -m venv tools/golden/.venv
tools/golden/.venv/bin/pip install -r tools/golden/requirements.txt      # 또는 requirements.lock.txt
# (모델 다운로드: 3절 표의 URL 을 각자 새 빈 폴더 tools/golden/models/<폴더>/ 에 받는다. pyannote tar.bz2 는
#  models/pyannote_seg30_dl/ 에 받고 새 빈 폴더 models/pyannote_seg30/ 에 압축 해제)
# 다운로드 파일을 읽으므로 -I(격리 모드)로 실행(zsh/bash 공통)
for s in make_clips gen_silero gen_pyannote gen_fsmn gen_webrtc gen_ten gen_fbank verify_golden; do
  tools/golden/.venv/bin/python -I tools/golden/$s.py || break
done
```

- 모든 스크립트는 결정적이다(난수 없음, ONNX 스레드 1). 두 번 실행하면 JSON 이 바이트 단위로 같다(확인함).
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
| └ 사용 파일 `model.onnx`(fp32) | (위 압축 안) | `pyannote_seg30/sherpa-onnx-pyannote-segmentation-3-0/model.onnx` | `220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079` |
| └ 미사용 `model.int8.onnx` | (위 압축 안) | 같은 폴더 | `d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d` |
| FSMN ONNX(양자화) | https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/model_quant.onnx | `fsmn_onnx/model_quant.onnx` | `5289eb2aa3c9af2d7a4284bcfa7c3ceb81d360814ed4203239b6c5d0569da8a1` |
| FSMN CMVN | https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/am.mvn | `fsmn_onnx/am.mvn` (→ `golden/fsmn_am.mvn` 로 복사, 커밋) | `6820fef9687708c4fc3fab2530179c8fcea6262daa25514380056cd8f6eb1754` |
| FSMN config | https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/config.yaml | `fsmn_onnx/config.yaml` | `2ef334f2d7776edd86ed696296774f87550206c256bfabc597872ad861831589` |
| py-webrtcvad example.py(참조용 원문) | https://raw.githubusercontent.com/wiseman/py-webrtcvad/e283ca41df3a84b0e87fb1f5cb9b21580a286b09/example.py | `webrtc_ref/example.py` | `536a108f61f7a9fa566516a21de07a300d344ffe1dd2fa4409424e8d79285bb4` |
| ten-vad Python 패키지 | `pip install git+https://github.com/TEN-framework/ten-vad.git@d68ab71c8d5fbd8f98e97ae7c9986af76b5d72b1` (공식 repo 설치 방법, 버전 1.0.6.9) | venv site-packages | 포함된 `lib/Linux/x64/libten_vad.so` `5abfe6bf6e9a4fcea6b440240f0a9a0f431ab5006e48a4e16465ebe681ffd90f` |

라이선스: Silero MIT, sherpa 변환본은 원본 pyannote/segmentation-3.0(MIT, HF gated) 변환물,
FSMN 은 FunASR Model License 1.1, py-webrtcvad MIT(John Wiseman), TEN Apache-2.0+추가조건.
모델·클립은 공개 저장소에 넣지 않는다.

## 4. 클립 (`make_clips.py`)

| 클립 | 내용 | 샘플 수 |
|---|---|---|
| `kr_customer_01_20s.wav` | `~/workspace/stt/input_sound/kr/customer/01.wav` 앞 320000 샘플(int16 그대로 복사) | 320000 |
| `en_customer_01_20s.wav` | `~/workspace/stt/input_sound/en/Customer_Service/01.wav` 앞 320000 샘플 | 320000 |
| `synth_silence_noise_8s.wav` | 디지털 무음 2 s + 백색잡음 4 s + 무음 2 s. 잡음 = `np.random.default_rng(0).standard_normal(64000) × 10^(-30/20)`(−30 dBFS RMS) → `round(×32767)` → int16 clip | 128000 |

모든 스크립트는 클립을 int16 으로 읽고 float 는 `int16 / 32768.0`(float32) 로 만든다(soundfile/torchaudio 와 같은 정규화).

## 5. JSON 형식

### 5.1 구간 골든 `golden/<family>_<presetId>_<clipStem>.json` (57개)

공통 키(계획서 형식):

| 키 | 의미 |
|---|---|
| `clip` | 클립 파일명 |
| `family` | `SILERO` / `PYANNOTE` / `FSMN` / `WEBRTC` / `TEN` |
| `presetId` | 앱 `VadPresets` id |
| `hopSec` | 프레임 간격(초): Silero 0.032, pyannote 0.016875, FSMN 0.010, WebRTC 0.03/0.02, TEN 0.016 |
| `frameTimesSec` | pyannote 만: 각 프레임 중앙 시각(초). 나머지는 `null` |
| `frameScores` | 프레임 점수. float32 값을 **float32 로 정확히 복원되는 최단 10진 표기**로 저장(Kotlin `toFloat()` 가 비트 단위로 같음) |
| `frameDb` | FSMN 만: 프레임 dB. 나머지 `null` |
| `expected` | 기대 구간 `[{"start": 초, "end": 초}]` (시작순) |
| `tool` | 생성 도구·버전 |

계열별 추가 키(GoldenLoader 는 무시해도 되지만 정밀 비교에 쓰면 좋다):

| 계열 | 추가 키 |
|---|---|
| SILERO, TEN_SHERPA | `expectedSamples`(정수 샘플 `[start, end]`, 1 샘플 허용 비교용), `totalSamples`(=audio_length_samples), `windowSamples`(512 또는 256), `params` |
| PYANNOTE | `params`(onset/offset/minDurationOn/minDurationOff) |
| FSMN | `expectedMs`(원본 ms 정수 구간), `params`(적용한 model_conf), `originalConfigMaxSingleSegmentTime`(ModelScope config 원래 값 20000) |
| WEBRTC | `expectedFrames`(`[시작 프레임, 끝 프레임(배타)]`), `params`(mode, frameMs, style, paddingMs, ratio, speechMs, silenceMs, threshold) |
| TEN_DEFAULT | `expectedFrames`, `frameFlags`(라이브러리 flag 0/1), `params` |

`expected` 의 초 값: Silero/TEN_SHERPA = 샘플/16000, WebRTC/TEN_DEFAULT = 프레임 인덱스 × hopSec,
FSMN = ms/1000, pyannote = 프레임 중앙 시각(Binarize 원본 값).

### 5.2 모델 추론 정합용 `golden/scores_<family>_<clipStem>.json` (9개: SILERO/PYANNOTE/FSMN × 3)

공통 키와 같은 구조이며 `presetId: null`, `expected: null`(구간 없음; 검증 스크립트가 키 존재를 확인하므로 null 로 둠).
- SILERO: `frameScores` = 512-hop 확률, 추가 `totalSamples`, `windowSamples`(512), `contextSamples`(64)
- PYANNOTE: `frameScores` = 집계 speech 점수, `frameTimesSec`, 추가 `windowSamples`(160000), `stepSamples`(16000), `receptiveFieldSize`(991), `receptiveFieldShift`(270)
- FSMN: `frameScores` = silence 확률(`logits[0][t][0]`), 추가 `scoreIndex`(0), `numClasses`(248)

### 5.3 특징 골든 `golden/fbank_<clipStem>.json`, `golden/lfr_cmvn_<clipStem>.json` (6개)

`{"clip", "kind": "fbank"|"lfr_cmvn", "numFrames", "dim", "expected": [[...], ...], "tool", "numFramesTotal", "inputScale": 32768, "knfOptions": "<kaldi 옵션 전체 문자열>"}`.
`fbank` 는 앞 50 프레임 × 80, `lfr_cmvn` 은 앞 10 프레임 × 400(+ `lfrM` 5, `lfrN` 1, `cmvnFile` "fsmn_am.mvn").

## 6. 계열별 알고리즘 (Kotlin 포팅 기준)

### 6.1 Silero (`gen_silero.py`)

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
  pad 단계는 `int()` 절사·`silence_duration // 2` 를 쓴다. 비교식의 `>`/`>=` 구분을 그대로 옮길 것.
- 검증: 같은 확률을 `get_speech_timestamps_from_probs(..., audio_length_samples=N)` 에 다시 넣은 결과가
  라이브러리 결과와 **완전히 같음**을 24 조합 모두 assert(통과). 따라서 Kotlin 은 "확률 입력 → 구간" 포팅만으로 일치해야 한다.
- 비교 권장: `expectedSamples` 와 1 샘플 허용. 확률 비교는 Kotlin 에서도 double 로 하는 편이 안전하다(float 임계값 0.3f 등 미세 차이 방지).

### 6.2 pyannote (`gen_pyannote.py`) — 원본 pipeline 비교 미수행(HF gated, 토큰 없음)

HF 원본 `pyannote/segmentation-3.0` 체크포인트는 gated 이고 HF_TOKEN 이 없어 **원본 pipeline 비교(expected_original)는 수행하지 않았다.**
대신 sherpa 변환본 `model.onnx`(fp32) 를 **pyannote.audio 3.4.0 의 실제 코드 경로**에 끼워 넣었다:
`OnnxPyanNet`(PyanNet 상속, forward 만 ONNX 추론, 배치 1) → `VoiceActivityDetection(segmentation=model)` 의
`_segmentation`(= `Inference(model, pre_aggregation_hook=max over speakers)`) → `Binarize`.
사양은 segmentation-3.0 과 같게 직접 지정했다: MONO_LABEL_CLASSIFICATION, FRAME, duration 10.0 s, warm_up (0,0),
classes 3, powerset_max_classes 2(→ 7 클래스). PyanNet 수용 영역(991/270 샘플)이 ONNX 메타데이터와 같음을 assert.
(가정: 원본 체크포인트의 warm_up 이 기본값 (0,0) 이라는 점은 체크포인트를 열 수 없어 확인하지 못했다.)

**speech 점수 집계 알고리즘(정확한 정의, numpy 독립 구현 `reference_scores` 가 라이브러리와 |차| ≤ 1e-6 로 일치함을 assert):**

1. 입력 x: float32 파형(N 샘플). W = 160000(메타 `window_size`), S = 16000(step 1 s = `round(0.1 × 10 s × 16000)`).
2. 완전한 창 수 `C0 = (N ≥ W) ? (N − W) / S + 1 : 0`(정수 나눗셈). 마지막 창 존재 `hasLast = (N < W) || ((N − W) % S > 0)`.
   창 c(0 ≤ c < C0) = `x[c·S, c·S+W)`. hasLast 면 창 C0 = `x[C0·S, N)` 뒤를 0 으로 채워 W 샘플. 전체 창 수 `C = C0 + hasLast`.
   (20 s 클립: C0 = 11, hasLast = false. 8 s 클립: C0 = 0, hasLast = true → C = 1.)
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
   어떤 창도 덮지 않는 프레임은 0(= `missing=0.0`). 예: 20 s 클립은 마지막 창이 프레임 593~1181 을 덮고 T = 1186 이라
   **프레임 1182~1185 의 점수는 0** 이다(라이브러리 동작 그대로).
9. hasLast 면 loose crop: `j = floor((N/16000) / h)`, 앞에서 `min(j+1, T)` 프레임만 남긴다(8 s 클립: 475 프레임,
   마지막 프레임 중앙 8.0297 s 로 오디오 끝을 약간 넘는다). hasLast 가 아니면 T 그대로(20 s: 1186).
10. 프레임 시각(Binarize 기준) = 프레임 중앙 `t_i = i·h + d/2` → `frameTimesSec`.

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
  `ComputeScores` 직후 scores, `ComputeDecibel` 직후 decibel 목록을 복사한다.
- 전처리: 6.7 절의 fbank → LFR(5,1) → CMVN, ONNX 입력 `speech` (1, T, 400) float32, `in_cache0..3` (1,128,19,1) 0.
  출력 `logits` (1, T, 248) (softmax 확률). T = fbank 프레임 수 = `1 + (N − 400) / 160`(정수 나눗셈).
- **sil prob = `logits[0][t][0]`** (config `sil_pdf_ids: [0]`, `silence_pdf_num: 1`) → `frameScores`.
  원본 판정(`GetFrameState`): `noise_prob = ln(sil)·speech_2_noise_ratio`, `speech_prob = ln(1 − sil)`,
  `exp(speech_prob) ≥ exp(noise_prob) + speech_noise_thres(0.6)` 이고 `snr ≥ snr_thres`, `dB ≥ decibel_thres` 이면 음성 프레임.
  dB < decibel_thres 면 바로 무음. 무음 판정 프레임에서 잡음 평균 dB 갱신: 평균 < −99.9 이면 = dB, 아니면 `(dB + 평균·99) / 100`
  (`noise_frame_num_used_for_snr` 100). snr = dB − 잡음 평균.
- **frame dB[t] = `10·log10( Σ_{i=0}^{399} x[160·t + i]² + 1e-6 )`**, x 는 **−1~1 float 파형(×32768 하지 않음)**,
  t = 0 .. (N − 400)/160 (`range(0, N − 400 + 1, 160)`, frame_length 25 ms = 400, frame_in 10 ms = 160). 개수는 T 와 같음(assert).
  완전 무음 프레임은 −60.0.
- 원본은 최대 6000 프레임(60 s) 블록 단위로 처리한다. 골든 클립은 모두 1 블록(is_final=True, `DetectLastFrames`)이다.
- 상태기계 포팅 기준은 이 골든을 만든 `funasr_onnx/utils/e2e_vad.py`(funasr_onnx 0.4.3) 이다.
- `expected` = 원본 ms 구간 / 1000. 허용 오차 0.010 s.

### 6.4 WebRTC (`gen_webrtc.py`)

- 프레임 판정: `webrtcvad.Vad(mode).is_speech(PCM16 프레임, 16000)`. WebRTC VAD 는 상태(잡음 적응)가 있으므로 프리셋마다
  **새 인스턴스로 처음 프레임부터 순서대로** 처리한다. `frameScores` = 1.0(voiced)/0.0.
- RAW, HANGOVER 의 프레임 = `floor(N / L)` 개 완전한 프레임(L = 16·frameMs 샘플; libfvad 사용 방식과 같음).
- **WEBRTC_LIB_DEFAULT(RAW, mode 0, 30 ms)**: 연속 voiced 프레임 병합 `[첫 프레임, 첫 unvoiced 프레임)`. hangover(0,0)과 같음을 assert.
- **WEBRTC_PYEXAMPLE(RING_BUFFER, mode 3, 30 ms, padding 300 ms)**: py-webrtcvad `example.py` 의 함수를 그대로 복사해 실행.
  변경점은 (1) stdout 출력 제거 (2) 오디오 바이트 대신 (첫 프레임 번호, 마지막 프레임 번호) 반환 뿐이다.
  - 프레임 생성기 조건은 원본대로 `offset + n < len(audio)`(엄격한 <) → 프레임 수 = `floor((N − 1) / L)`
    (N 이 L 의 배수면 마지막 프레임이 빠진다. 골든 클립은 30 ms 배수가 아니라 floor(N/L) 과 같음: 666, 266).
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

- 공식 `ten_vad.TenVad(hop_size=256, threshold=0.5)` 로 `floor(N/256)` 개 int16 프레임을 순서대로 처리(상태 유지) → (확률, flag).
  `frameScores` = 확률, `frameFlags` = flag. 세 클립 모두 `flag == (prob ≥ 0.5)` 불일치 0 프레임(확인).
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

- `verify_golden.py`: `OK: JSON 72개, 구간 골든 57개, expected 키 전부 존재, 합성 클립 Silero 무음 미포함`.
- 합성 클립: Silero 8 프리셋 모두 구간 0개, pyannote 4 프리셋 0개. (FSMN 은 1.75–2.39 s, WebRTC RAW/HANGOVER, TEN 은 잡음 시작부를 음성으로 판정.)
- 스크립트 내부 assert 전부 통과: Silero from_probs 재현, pyannote 독립 구현·pipeline.apply 일치, fbank/LFR 독립 구현 일치,
  WebRTC RAW = hangover(0,0), TEN_DEFAULT flag 병합 = hangover(prob, 0.5, 0, 0).

## 8. 한계·주의

- pyannote 원본 pipeline(HF 체크포인트) 비교 미수행(HF gated, 토큰 없음). sherpa 변환본 ONNX 와 원본 PyTorch 의 수치 차이는 확인하지 못했다.
- 골든 클립이 20 s 이하라 **최대 발화 길이 분할 경로**(SILERO_SHERPA·TEN_SHERPA maxSpeech 20 s, FSMN max_single_segment_time)는
  이 골든으로 검증되지 않는다(FSMN_DEFAULT 와 FSMN_ASR30 결과가 동일). Kotlin 측 손작성 테스트로 보완 필요.
- pyannote 골든은 fp32 `model.onnx` 기준이다. 앱이 `model.int8.onnx` 를 쓰면 점수 정합(≤ 1e-3)은 보장되지 않는다.
