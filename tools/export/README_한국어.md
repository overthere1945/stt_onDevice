# tools/export — FireRedVAD·MarbleNet ONNX 변환·골든 도구

change(add)-hyungchul-2026-10-10-14:25

PC(Linux, CPU 전용, Python 3.12.3)에서 두 VAD 모델을 **공식 방법으로 ONNX 변환**하고, 원본(PyTorch/NeMo)과 ONNX 의 프레임 확률이
같은지 검증한 뒤, Kotlin 포팅(계획서 Task 14)이 맞춰야 할 **골든 JSON** 을 `app/src/test/resources/golden/` 에 만든다.
JSON 은 주석을 달 수 없으므로 형식·의미는 이 문서(8절)와 `app/src/test/resources/golden/README_한국어.md` 가 설명한다.
골든 공통 형식·클립 4종은 `tools/golden/README_한국어.md`(Task 7)와 같다(`tools/golden/golden_common.py` 를 그대로 import 해서 씀).

| VAD | 상태 | ONNX | 원본 대비 최대 확률 |차| (클립 4개) |
|---|---|---|---|
| FireRedVAD 오프라인(FIRERED_OFFLINE) | **완료** | `fireredvad_vad.onnx` | 5.36e-7 |
| FireRedVAD 스트리밍(FIRERED_STREAM) | **완료** | `fireredvad_stream_vad.onnx`, `fireredvad_stream_vad_with_cache.onnx` | 5.07e-7 / 5.07e-7 |
| MarbleNet v2.0(MARBLENET_NEMO) | **완료** | `frame_vad_multilingual_marblenet_v2.0.onnx` | 5.07e-7 (logits 2.86e-6) |

- 커밋 대상: 이 폴더의 스크립트·requirements·lock·README, `app/src/test/resources/golden/` 의 새 JSON·`firered_cmvn.ark`·README.
- 커밋 안 함(gitignore): `tools/export/.venv/`, `tools/export/.venv-nemo/`, `tools/export/out/`(클론·다운로드 모델·ONNX·로그), 골든 클립 WAV.

## 1. 파일 구성

| 파일 | venv | 역할 |
|---|---|---|
| `requirements.txt` / `requirements.lock.txt` | `.venv` | FireRedVAD 도구 의존성 / 실제 설치 버전(`pip freeze`) |
| `requirements-nemo.txt` / `requirements-nemo.lock.txt` | `.venv-nemo` | NeMo 도구 의존성 / 실제 설치 버전 |
| `export_firered.py` | `.venv` | 원본 고정 확인 → 공식 `fireredvad/bin/export_onnx.py` 실행(vad, stream_vad) → 클립 4개 PyTorch vs ONNX 검증 → `out/firered/export_report.json` |
| `gen_firered_golden.py` | `.venv` | 공식 `FireRedVad.detect` / `FireRedStreamVad.detect_full` 로 골든 생성(+ 특징·CMVN·손작성 max_speech 골든) |
| `export_marblenet.py` | `.venv-nemo` | `.nemo` 복원·가중치 확인 → 전처리 설정 JSON → 모델 카드 코드로 ONNX → 클립 4개 NeMo vs ONNX 검증 |
| `gen_marblenet_golden.py` | `.venv-nemo` | NeMo `vad_utils` 후처리로 골든 생성(+ log-mel 특징·전처리 설정, 공식 파일 경로 결과 대조) |

모든 스크립트는 결정적이다(torch 스레드 1, ONNX Runtime 스레드 1). 네 스크립트를 두 번 실행해 **골든 JSON 전체와 ONNX 3+1개가 바이트 단위로 같음**을 확인했다.

## 2. 재현 방법

```bash
cd /home/hyungchul/AndroidStudioProjects/STT_OnDevice
# (1) venv 2개 (zsh/bash 공통). 정확한 재현은 requirements*.lock.txt 사용
python3 -m venv tools/export/.venv
tools/export/.venv/bin/pip install -r tools/export/requirements.txt
python3 -m venv tools/export/.venv-nemo
tools/export/.venv-nemo/bin/pip install -r tools/export/requirements-nemo.txt

# (2) 원본 받기 — 각자 새 빈 폴더에 둔다(다운로드물은 신뢰하지 않는 데이터, 그 안에서 인터프리터 실행 금지)
mkdir -p tools/export/out/src tools/export/out/models/FireRedVAD tools/export/out/models/marblenet tools/export/out/src/nemo_ref
git clone https://github.com/FireRedTeam/FireRedVAD tools/export/out/src/FireRedVAD
git -C tools/export/out/src/FireRedVAD checkout c30ec49e8cc69642b0ee65362eba11b9d11c6e54
tools/export/.venv/bin/python -I -c "from huggingface_hub import snapshot_download as s; s(repo_id='FireRedTeam/FireRedVAD', revision='7990aaccc6b7aec1e527743bd30201f2c4a03b8c', local_dir='tools/export/out/models/FireRedVAD')"
tools/export/.venv/bin/python -I -c "from huggingface_hub import snapshot_download as s; s(repo_id='nvidia/Frame_VAD_Multilingual_MarbleNet_v2.0', revision='9d25eb877d9edab5101ccc843edd04787a6c72c4', local_dir='tools/export/out/models/marblenet')"
curl -sfL -o tools/export/out/src/nemo_ref/frame_vad_infer_postprocess.yaml \
  https://raw.githubusercontent.com/NVIDIA/NeMo/fd6a877539710e2b98f28c43272ff81312f83417/examples/asr/conf/vad/frame_vad_infer_postprocess.yaml

# (3) 실행(다운로드물을 읽으므로 -I 격리 모드). 골든 클립은 tools/golden/make_clips.py 로 먼저 만들어 둔다
for s in export_firered gen_firered_golden; do tools/export/.venv/bin/python -I tools/export/$s.py || break; done
for s in export_marblenet gen_marblenet_golden; do tools/export/.venv-nemo/bin/python -I tools/export/$s.py || break; done
```

- 이번 실행: 네 스크립트 모두 exit 0. 이어서 `tools/golden/.venv/bin/python -I tools/golden/verify_golden.py` → `OK: JSON 142개 …`(기존 검사 영향 없음).
- `.venv` 의 `fireredvad` 는 위 commit 을 `pip install "fireredvad @ git+…@c30ec49…"` 로 설치한다. `export_firered.py` 가
  (a) 클론 HEAD = c30ec49, (b) 체크포인트 SHA-256, (c) 설치 패키지의 모든 `.py` 가 클론과 바이트 단위로 같음을 매번 assert 한다.
- PyTorch 체크포인트(`model.pth.tar`, `model_weights.ckpt`)는 pickle 이다. 공식 코드가 `torch.load(weights_only=False)` 로 읽기 전에
  pickle opcode 를 검사해 참조 전역이 `argparse.Namespace`, `collections.OrderedDict`, `torch.FloatStorage/LongStorage`,
  `torch._utils._rebuild_tensor_v2` 뿐임을 확인했다(임의 코드 실행 요소 없음). `export_marblenet.py` 는 `.nemo` 안 ckpt 를 `weights_only=True` 로 다시 읽어 비교한다.

## 3. 원본 출처·고정 버전·SHA-256

| 대상 | 출처(고정) | 저장 위치(`tools/export/out/`) | SHA-256 |
|---|---|---|---|
| FireRedVAD 코드 | https://github.com/FireRedTeam/FireRedVAD commit `c30ec49e8cc69642b0ee65362eba11b9d11c6e54`(2026-05-06) | `src/FireRedVAD/` | (git commit) |
| FireRedVAD 오프라인 체크포인트 | https://huggingface.co/FireRedTeam/FireRedVAD revision `7990aaccc6b7aec1e527743bd30201f2c4a03b8c` `VAD/model.pth.tar` | `models/FireRedVAD/VAD/` | `63f4fb1b00a6b8607c118dd48efc18d5e40d67d99b7bf9aa7a8d61540cf23d71` |
| FireRedVAD 스트리밍 체크포인트 | 같은 revision `Stream-VAD/model.pth.tar` | `models/FireRedVAD/Stream-VAD/` | `ec88a8ae8ac5f004cdbd20c1eac4a9e9d12067c3d51ac5b141afa1f921fb59c9` |
| FireRedVAD CMVN | 같은 revision `VAD/cmvn.ark` = `Stream-VAD/cmvn.ark`(동일 파일) | 위 두 폴더 | `c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473` |
| MarbleNet v2.0 | https://huggingface.co/nvidia/Frame_VAD_Multilingual_MarbleNet_v2.0 revision `9d25eb877d9edab5101ccc843edd04787a6c72c4` `frame_vad_multilingual_marblenet_v2.0.nemo` | `models/marblenet/` | `84bda37e925ac6fd740c2ced55642cb79f94f81348e1fa0db992ca50d4b09706` |
| NeMo 후처리 설정(참조) | NVIDIA/NeMo 태그 v3.0.0 = commit `fd6a877539710e2b98f28c43272ff81312f83417` `examples/asr/conf/vad/frame_vad_infer_postprocess.yaml` | `src/nemo_ref/` | `ac00fe65106b6c822aad33051def3b8c45a7fd838e399c7588155c9b7caf220e` |
| NeMo 공식 추론 스크립트(참조, 실행 안 함) | 같은 commit `examples/asr/speech_classification/frame_vad_infer.py` | `src/nemo_ref/` | `f0c2da296ed229dda431803fdeded25c3ebec3ed822b89b831384da90eb4262e` |

설치 버전: torch 2.8.0+cpu(두 venv), onnx 1.23.2, onnxruntime 1.31.0, onnxsim 0.8.1, kaldi-native-fbank 1.22.3(tools/golden 과 같음),
kaldiio 2.18.1, nemo_toolkit 3.0.0, numpy 2.5.3. 전체는 lock 파일.

## 4. 산출물(ONNX) 과 SHA-256 — `tools/export/out/`(gitignore, 앱 assets 로 옮길 때 이 해시로 확인)

| 파일 | 크기(바이트) | SHA-256 |
|---|---|---|
| `firered/fireredvad_vad.onnx` | 2392980 | `4adc9f0120affe96f6f570c9c8672230c670d5b15ec30b4b6a450f316ab4c9ab` |
| `firered/fireredvad_stream_vad.onnx` | 2302864 | `e3444c6dd25c4a313a7560f517d64f6df6f1c45e3d25fb051863b4e2549ba2e3` |
| `firered/fireredvad_stream_vad_with_cache.onnx` | 2305514 | `bfe0ca9678d4232ca033c2d1fd955bf0507f0ce10b654672d221212d12269d8f` |
| `firered/cmvn.ark` | 1311 | `c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473` |
| `marblenet/frame_vad_multilingual_marblenet_v2.0.onnx` | 430446 | `738e1713a45bcf5b1c46195eeb1edb94b960a10f1bf2fbabe5e8973626433739` |
| `marblenet/marblenet_preprocessor.json` | 179986 | `0e821a6ac90279935794d2f3627be1cfe6f98309195418849070093949d207b3` |

검증 보고: `out/firered/export_report.json`, `out/marblenet/export_report.json`(클립별 최대 |차|, 버전, opset, 입출력 정의).

## 5. 라이선스 근거

- **FireRedVAD — Apache-2.0**: 코드 repo `LICENSE`(Apache License 2.0 전문), `pyproject.toml` `license = "Apache-2.0"`,
  HF 모델 카드 머리 `license: apache-2.0`(revision 7990aac). 재배포 시 Apache-2.0 고지(저작권: Xiaohongshu FireRedTeam)를 유지한다.
  테스트 리소스 `golden/firered_cmvn.ark` 는 HF 원본 그대로 복사(해시 동일)했다.
- **MarbleNet v2.0 — NVIDIA Open Model License Agreement**: HF 모델 카드(revision 9d25eb8) "License/Terms of Use: GOVERNING TERMS: Your use
  of this model is governed by the NVIDIA Open Model License Agreement"(https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license),
  카드 머리 `license: other`, 본문 "This model is ready for commercial use." 조건(라이선스 고지 유지, 이용 제한 준수)을 지켜야 한다.
- **MarbleNet 학습 언어에 한국어 없음**: 카드의 Multilingual 지원 언어 = 중국어·영어·프랑스어·독일어·러시아어·스페인어, 학습 데이터 목록에도
  한국어 코퍼스가 없다("Potential Known Risks: … may experience a degradation in quality for languages and accents that are not included").
  이번 골든에서도 한국어 클립은 프레임의 77%(20 s)·85%(70 s)가 onset 0.3 을 넘어 **클립 전체가 구간 1개**(1.80–20.20 s, 1.80–70.20 s)가 됐다(영어 20 s 는 2개).
- NeMo toolkit 코드(후처리 함수)는 Apache-2.0(`src/nemo_ref/LICENSE`). 골든에는 NeMo 코드가 아니라 그 실행 결과만 들어간다.

## 6. FireRedVAD

### 6.1 모델·ONNX

- DFSMN(R 8 블록, H 256, P 128, lookback N1 20) + Linear(1) + sigmoid. 오프라인 모델은 lookahead N2 20(미래 20 프레임 참조, 비인과),
  스트리밍 모델은 N2 0(인과). 둘 다 입력 fbank 80 차원, 출력 프레임당 음성 확률 1개, hop 10 ms.
- 변환: 공식 `fireredvad/bin/export_onnx.py --task vad|stream_vad --model-dir <체크포인트> --output-dir out/firered`
  (TorchScript export, opset 18·IR 8, onnxsim 단순화, onnx.checker 통과, cmvn.ark 복사). 스크립트는 수정하지 않았다.

| ONNX | 입력 | 출력 |
|---|---|---|
| `fireredvad_vad.onnx` | `feat` float32 [batch, time, 80] | `probs` float32 [batch, time, 1] |
| `fireredvad_stream_vad.onnx` | `feat` float32 [batch, time, 80] | `probs` [batch, time, 1] + `cache_out_0..7` [batch, 128, 19] (캐시 입력 없음 = 0 캐시) |
| `fireredvad_stream_vad_with_cache.onnx` | `feat` float32 [1, time, 80], `caches_in` float32 [8, 1, 128, 19] | `probs` [1, time, 1], `caches_out` [8, 1, 128, 19] |

- 검증(클립 4개, 최대 |차|): vad 5.36e-7, stream(전체 1회) 5.07e-7, with_cache 1 프레임씩 5.07e-7, with_cache 100 프레임씩 5.07e-7
  (모두 ≤ 1e-4). 기준 PyTorch = 공식 `DetectModel.forward`(오프라인 모델, 스트리밍 모델 각각) 전체 1회 추론.
  repo 에 동봉된 사전 변환본(`pretrained_models/onnx_models/*.onnx`)도 같은 차이였다(참고).
- **앱 권장**: 파일 단위 처리이므로 `fireredvad_vad.onnx`(오프라인) / `fireredvad_stream_vad.onnx`(스트리밍, 전체 1회)를 쓰면 공식
  `detect()` / `detect_full()` 과 같은 경로다(`detect_full` 도 전체 특징을 한 번에 추론한 뒤 프레임별 후처리만 스트리밍 방식으로 한다).
  with_cache 모델은 1 프레임씩 넣어도 같은 확률임을 확인했다.
- 공식 코드는 특징이 30000 프레임(300 s)을 넘으면 30000 프레임씩 잘라 **캐시 없이** 따로 추론한다(`chunk_max_frame`). 앱 입력은 ≤ 94 s 라 해당 없음.

### 6.2 전처리(fbank + CMVN) — FSMN 의 KaldiFbank 와 **창 함수만 다르다**

공식 `fireredvad/core/audio_feat.py`: `KaldifeatFbank`(kaldi-native-fbank `OnlineFbank`) → `CMVN`.

- 입력: **int16 값 그대로**(공식 코드는 `sf.read(dtype="int16")` 값을 `tolist()` 로 넣음). FSMN(KaldiFbank)의 `float × 32768` 과 같은 크기다.
- kaldi 옵션: FSMN 골든(`fbank_*.json` 의 `knfOptions`)과 비교해 **`window_type: povey`(FSMN 은 hamming) 한 줄만 다르다**
  (`gen_firered_golden.py` 가 문자열 비교로 assert). 나머지: 16 kHz, 25 ms(400)/10 ms(160), dither 0, preemph 0.97, remove_dc_offset true,
  round_to_power_of_two(FFT 512), snip_edges true(프레임 수 `1 + (N − 400) / 160`), 80 mel, low_freq 20, high_freq 0(→ 8000), kaldi mel 식,
  use_energy false, energy_floor 0, raw_energy true, use_log_fbank true, use_power true.
  povey 창: `w[n] = (0.5 − 0.5·cos(2πn/(400−1)))^0.85`. → **Kotlin `KaldiFbank` 에 창 함수 선택(povey) 만 추가하면 재사용 가능**하다
  (현재 `KaldiFbank.kt` 는 hamming 고정).
  change(add)-hyungchul-2026-10-10-15:04 Task 14: `KaldiFbank(windowType = KaldiWindowType.POVEY)` 옵션을 추가했고(기본 HAMMING = FSMN 그대로),
  CMVN 은 `vad/feature/FireRedCmvn.kt`(cmvn.ark 바이너리 파서), 추론은 `vad/impl/FireRedVad.kt`·`vad/impl/MarbleNetVad.kt` 가 한다.
- CMVN 파일 `cmvn.ark`: **Kaldi 바이너리 double 행렬**(헤더 `\0B` + `DM`, 2 × 81). 0행 = 특징별 합 80개 + 마지막 열 프레임 수(count = 1694418954),
  1행 = 제곱합 80개 + 0. 공식 해석: `mean = sum/count`, `var = sumsq/count − mean²`(하한 1e-20), `istd = 1/sqrt(var)`(double).
  적용 `(x − mean) × istd` 를 **double 로 계산한 뒤 float32**(`torch.from_numpy(..).float()`). LFR 없음(FSMN 은 LFR(5,1) 후 am.mvn).
  해석값을 `golden/firered_cmvn.json`(`means`, `inverseStdVariances`, float64 정확 표기)에 저장했다.
- 독립 구현(knf 직접 실행 + numpy CMVN)이 공식 결과와 **완전히 같음**을 클립 4개 모두 assert(통과).

### 6.3 FIRERED_OFFLINE 후처리 — `fireredvad/core/vad_postprocessor.py` `VadPostprocessor.process` → `decision_to_segment`

프리셋(= 공식 `vad.py` CLI 기본값): smooth_window_size 5, speech_threshold 0.4, min_speech_frame 20, max_speech_frame 2000,
min_silence_frame 20, merge_silence_frame 0, extend_speech_frame 0, chunk_max_frame 30000. 입력 확률은 float32 값을 double 로 쓴 것(`probs.tolist()`).

1. 평활(`_smooth_prob`): w = max(1, 5). `np.convolve(p, ones(w)/w, 'full')[:T]` → i ≥ w−1 이면 `Σ_{k=0}^{4} p[i−k]·0.2`(double),
   i < w−1 이면 `mean(p[0..i])`. (합산 순서 차이로 Kotlin 결과가 ulp 단위로 다를 수 있다 — 골든의 `minAbsSmoothedMinusThreshold` 최소 1.4e-4 라 판정 영향 없음)
2. 이진화: `smoothed ≥ 0.4`.
3. 상태기계(`_smooth_preds_with_state_machine`, min_speech·min_silence 둘 다 ≤ 0 이면 생략): SILENCE —음성→ POSSIBLE_SPEECH(start=t);
   POSSIBLE_SPEECH: 음성이고 `t − start ≥ 20` 이면 SPEECH 로, `decisions[start:t] = 1`; 무음이면 SILENCE. SPEECH —무음→ POSSIBLE_SILENCE(silStart=t);
   POSSIBLE_SILENCE: 무음이고 `t − silStart ≥ 20` 이면 SILENCE, 음성이면 SPEECH. 각 t 의 결정 = (전이 후 상태 ∈ {SPEECH, POSSIBLE_SILENCE}).
   → 구간 끝에 무음 20 프레임이 포함된다.
4. `_fix_smooth_window_start`: 3단계 결과에서 `d[t−1]=0, d[t]=1`(t>0)인 시작마다 `[max(0, t−w), t)` 를 1 로(시작을 5 프레임 앞당김).
5. 짧은 무음 병합(merge 0 → 생략), 6. 확장(extend 0 → 생략).
7. `_split_long_speech_segments`: 6단계 결과를 `decision_to_segment(d)`(**wav_dur 없이**)로 초 구간으로 바꾼 뒤
   `start_frame = int(start_s / 0.01)`, `end_frame = int(end_s / 0.01)`(**초→프레임 부동소수 절사 특이점**: 예 0.29/0.01 = 28.999… → 28.
   마지막까지 이어진 구간의 end_s 는 `round(T·0.01 + 0.025, 3)`). `end_frame − start_frame > 2000` 이면 원 확률 `probs[start_frame:end_frame]` 에서
   `start=0` 부터: 남은 길이 ≤ 2000 이면 중단, 아니면 창 `[int(start+1000), int(start+2000))` 의 **첫 최솟값** 위치를 분할점으로, `start = 분할점+1` 반복.
   분할점 프레임 1개만 0 으로 만든다(분할 뒤 병합 없음).
8. `decision_to_segment(d, dur)`: 연속 1 구간 `[s, e)` → `(s·0.01, e·0.01)`, 끝까지 이어진 구간의 끝 = `min(T·0.01 + 0.025, dur)`(dur = N/16000).
   모든 값 `round(x, 3)`(Python 은 double 의 정확한 10진값을 half-even 반올림 → Kotlin 은 `BigDecimal(x).setScale(3, HALF_EVEN)` 이 같다).
- 골든 `stages` 에 2~7 단계 결과를 프레임 구간으로 저장했다(단계별 디버깅용). 실제 클립에서는 7단계 분할이 일어나지 않아
  손작성 확률 `synthetic_firered_long`(45.37 s)로 분할 분기를 골든화했다: 6단계 `[[97, 4509]]` → 7단계 `[[97,1500],[1501,2600],[2601,4509]]`.

### 6.4 FIRERED_STREAM 후처리 — `fireredvad/core/stream_vad_postprocessor.py` `StreamVadPostprocessor.process_one_frame` → `FireRedStreamVad.results_to_timestamps`

프리셋(VadPresets.kt): threshold 0.5, **smooth_window 5**, pad_start 5, min_speech 8, max_speech 2000, min_silence 20.
change(add)-hyungchul-2026-10-10-14:54 R17: smooth_window 를 0 → **5(FireRedVAD stream 기본값)** 로 정정하고 골든을 다시 만들었다
(`FireRedStreamVadConfig`·`bin/stream_vad.py` CLI·`examples/inference_stream_vad.sh` 모두 5).
실효값: `smooth_window_size = max(1, 5) = 5`, `pad_start_frame = max(5, 5) = 5`(골든 `params.effective*` 에 기록).
평활: deque 이동 평균 — 프레임마다 `window_sum += p`, 창 길이가 5 를 넘으면 가장 오래된 값을 빼고(`window_sum −= left`), 평활 확률 = `window_sum / len(window)`
(처음 4 프레임은 1~4 개 평균). **누적합을 더하고 빼는 방식(double) 그대로** 옮겨야 비트 단위로 같다(매번 5 개를 다시 더하면 ulp 차이 가능).

- 프레임 번호 frame_cnt 는 1부터. `is_speech = (평활 확률 ≥ 0.5)`. 상태 SILENCE/POSSIBLE_SPEECH/SPEECH/POSSIBLE_SILENCE 와 speech_cnt·silence_cnt·
  hit_max_speech·last_speech_start/end_frame 을 쓰는 공식 코드를 **문자 그대로** 옮길 것(분기 순서·카운터 증가 위치가 결과에 영향).
  핵심: POSSIBLE_SPEECH 에서 `speech_cnt ≥ 8` 이면 시작 이벤트, 시작 프레임 = `max(1, frame_cnt − speech_cnt + 1 − 5, last_end + 1)`;
  SPEECH/POSSIBLE_SILENCE 에서 `speech_cnt ≥ 2000` 이면 그 프레임에서 끝 이벤트 + 다음 프레임에서 바로 새 시작(hit_max_speech);
  POSSIBLE_SILENCE 에서 `silence_cnt ≥ 20` 이면 그 프레임에서 끝 이벤트.
- 구간 변환: 시작 = `max(0, speech_start_frame − 1)`, 끝 = `max(0, speech_end_frame − 1)`(0-based), 끝 이벤트 없이 끝나면 끝 = `T − 1`.
  초 = 프레임 / 100(곱이 아니라 나눗셈). dur 로 자르지 않는다. 끝은 무음 20 프레임째 프레임이다.
- 골든 `events` = 시작/끝 이벤트 프레임 `[frame_idx, isStart, isEnd, speech_start_frame, speech_end_frame]`(1-based),
  `expectedFrames` = 0-based (시작, 끝). 손작성 `synthetic_firered_long` 으로 max_speech 강제 분할을 골든화했다(`[[97,2101],[2102,4101],[4102,4508]]`).
- `frameScores` 는 같은 입력의 반올림 전 확률이다(`StreamVadFrameResult.raw_prob` 는 `round(·,3)` 표시값이라 쓰지 않았다).

### 6.5 결과 요약(구간 수) 과 ONNX 확률 사용 시 동일성

| 클립 | OFFLINE | STREAM |
|---|---|---|
| kr 20 s | 7 | 9 |
| en 20 s | 4 | 5 |
| synth 8 s | 0 | 0 |
| kr 70 s | 19 | 32 |

ONNX 확률(`scores_FIRERED*_*.json`)을 같은 공식 후처리에 넣어도 8 조합 모두 구간이 같았다(`sameSegmentsWithOnnxProbs: true`).
(R17 smooth 5 재생성 후 STREAM 구간 수는 그대로이고 경계가 0.01~0.03 s 늦어졌다. 예: kr 20 s 첫 구간 2.04–2.94 → 2.06–2.96)

## 7. MarbleNet (Frame_VAD_Multilingual_MarbleNet_v2.0)

### 7.1 복원과 ONNX

- `EncDecFrameClassificationModel.restore_from(.nemo, map_location="cpu", strict=False)`. **NeMo 3.0.0 은 `strict=True` 복원이 실패**한다
  (`Missing key(s) in state_dict: "loss.weight"` — 학습용 CrossEntropyLoss 가중치 버퍼가 체크포인트에 없음). 그래서 strict=False 로 복원하고,
  `.nemo` 안 `model_weights.ckpt` 를 직접 읽어 **빠진 키는 `loss.weight`([1,1], 추론 무관) 하나뿐이고 나머지 모든 텐서가 완전히 같음**을 assert 한다.
  (모델 카드 Runtime 은 NeMo 2.3.0 이지만 같은 코드(`_setup_loss`)라 2.3.0 에서도 같을 것으로 보이며 직접 확인하지는 않았다.)
- 변환: 모델 카드 "Export to ONNX" 코드 그대로 — `typecheck.set_typecheck_enabled(False)` → preprocessor 출력으로
  `torch.onnx.export(model, args={"processed_signal", "processed_signal_length"}, output_names=["output"], dynamic_axes=…)`(opset 18·IR 8).
  **전처리는 그래프에 없다.** 예제 입력은 `kr_customer_01_20s_plus77`(동적 축이라 길이 무관; 802·2002·7002 프레임에서 검증).

| 이름 | 형태 | 의미 |
|---|---|---|
| 입력 `processed_signal` | float32 [batch, 80, F] | 7.2 절 log-mel (F = pad_to 2 로 짝수) |
| 입력 `processed_signal_length` | int64 [batch] | **seqLen = floor(N/160)**(F 가 아님) |
| 출력 `output` | float32 [batch, F/2, 2] | logits. **음성 확률 = softmax(output, axis=-1)[..., 1]**(NeMo `generate_vad_frame_pred` 와 같음). hop 20 ms |

- 검증(클립 4개): 확률 최대 |차| 5.07e-7, logits 2.86e-6(≤ 1e-4). 기준 = 모델 카드 "Inference with PyTorch"(fp32, autocast 없음).

### 7.2 전처리 — NeMo `AudioToMelSpectrogramPreprocessor` → `FilterbankFeatures`(nemo 3.0.0 `collections/asr/parts/preprocessing/features.py`)

`.nemo` 설정 원문: `normalize: None, window_size: 0.025, sample_rate: 16000, window_stride: 0.01, window: hann, features: 80, n_fft: 512,
frame_splicing: 1, dither: 1.0e-05, stft_conv: false, pad_to: 2`. 설정에 없는 값은 클래스 기본값이 쓰인다. 전부 float32 연산.

| 필드 | 값 | Kotlin 포팅 시 효과 |
|---|---|---|
| sample_rate | 16000 | 입력 16 kHz. 파형 = int16/32768(float32), `normalize_audio_db` 없음 |
| window_size → win_length | 0.025 → 400 | 창 길이 400 샘플 |
| window_stride → hop_length | 0.01 → 160 | hop 160 |
| n_fft | 512 | rFFT 257 bin. 창 400 을 512 의 **가운데(왼쪽 56, 오른쪽 56 은 0)** 에 둔다(torch.stft 규칙) |
| window | hann | `torch.hann_window(400, periodic=False)` = `0.5 − 0.5·cos(2πn/399)`(대칭). 값 400개를 `marblenet_preprocessor.json` `windowValues` 에 저장 |
| features(nfilt) | 80 | `librosa.filters.mel(sr=16000, n_fft=512, n_mels=80, fmin=0, fmax=8000, norm="slaney", htk=False)` → `melFilterbank`(80×257 float32) 저장 |
| normalize | 문자열 `"None"` | `normalize_batch` 의 else 분기 → **정규화 안 함**(실효 `normalize_applied: false`) |
| dither | 1e-5 | `self.training` 일 때만 → **추론 시 적용 안 함** |
| frame_splicing | 1 | 없음 |
| pad_to | 2 | 프레임 수를 2 의 배수로 `pad_value 0` 채움 |
| preemph(기본) | 0.97 | `y[0] = x[0]`, `y[i] = x[i] − 0.97·x[i−1]`(전체 파형, 패딩 전) |
| exact_pad(기본) | false | `center=True`: 앞뒤 `n_fft/2 = 256` 샘플 **0 패딩**(`pad_mode="constant"`) → STFT 프레임 수 `1 + floor(N/160)` |
| mag_power(기본) | 2.0 | `sqrt(re² + im²)` 를 구한 뒤 `pow(2)`(float32) |
| log, log_zero_guard(기본) | true, add, 2^-24 | `ln(mel + 5.960464477539063e-08)` |
| mel_norm/lowfreq/highfreq(기본) | slaney / 0 / None→8000 | 위 필터뱅크에 반영 |
| 마스크 | seqLen = floor(N/160) | **프레임 t ≥ seqLen 은 0**(STFT 마지막 프레임은 항상 0 이 됨) → 그 뒤 pad_to 2 |
| nb_augmentation_prob, use_grads, stft_conv | 0, false, false | 영향 없음 |

- 예: N = 320077 → seqLen 2000, STFT 2001 프레임 → 마지막 0 → 2002 프레임으로 패딩 → 출력 1001 프레임.
- 위 식의 numpy(float64) 독립 구현이 NeMo 출력과 최대 |차| 6.7e-5(log-mel) 이내임을 클립 4개 모두 assert(허용 2e-3).

### 7.3 MARBLENET_NEMO 후처리 — NeMo 3.0.0 `nemo/collections/asr/parts/utils/vad_utils.py`

프리셋 = v3.0.0 `frame_vad_infer_postprocess.yaml` `vad.parameters.postprocessing`(onset 0.3, offset 0.3, pad_onset 0.2, pad_offset 0.2,
min_duration_on 0.2, min_duration_off 0.2, filter_speech_first True) + `shift_length_in_sec 0.02`, `window_length_in_sec 0.0`, `smoothing False`
(`gen_marblenet_golden.py` 가 YAML 과 같음을 assert). 호출 순서(공식 `generate_vad_segment_table_per_file` 와 같음):
`prepare_gen_segment_table`(scale absolute → onset/offset 그대로, filter_speech_first → 1.0) → `generate_vad_segment_table_per_tensor`
= `binarization` → `filtering` → `torch.sort(dim 0)` → dur 열(`end − start + 0.01`).

- 확률 텐서는 float32. 비교 `p > onset`, `p < offset` 은 **float32 비교**(파이썬 실수가 텐서 dtype 으로 맞춰짐 → Kotlin `p > 0.3f`).
- `binarization`(프레임 i 시각 = i·0.02, double): 비음성에서 `p > onset` 이면 start = i·0.02, 음성으로. 음성에서 `p < offset` 이면
  `i·0.02 + pad_offset > max(0, start − pad_onset)` 일 때 구간 `[max(0, start − pad_onset), i·0.02 + pad_offset]` 추가 후 비음성.
  끝까지 음성이면 `[max(0, start − pad_onset), (T−1)·0.02 + pad_offset]`(조건 없이). **구간 값은 `torch.tensor` → float32 로 저장**된다.
  마지막에 `merge_overlap_segment`: 시작 기준 정렬 후 `이전 end ≥ 다음 start`(float32) 이면 병합.
- `filtering`(filter_speech_first 1.0): `end − start ≥ 0.2`(float32) 인 구간만 남김 → 정렬 후 이웃 간격 `[end_k, start_{k+1}]` 중 길이 `< 0.2` 인 간격을
  구간으로 추가하고 다시 `merge_overlap_segment`.
- 골든 `expected` = 최종 (start, end) float32 값, `binarized`/`filtered` = 중간 결과, `expectedDur` = NeMo 표의 3번째 열.
- **공식 스크립트 경로와의 차이(기록)**: `frame_vad_infer.py` 는 확률을 `'{:0.4f}'` 로 `.frame` 파일에 쓰고 다시 읽어 후처리한다.
  골든은 반올림 전 확률을 쓴다. 공식 파일 경로(`generate_vad_segment_table`, 4자리 반올림)도 실행해 `officialFileRoute.rows`(start, dur)에 기록했고,
  4 클립 모두 반올림 전 결과와 같았다(`sameAsUnrounded: true`). 또 공식 스크립트는 `torch.amp.autocast(device)` 안에서 추론하므로
  **CPU 에서는 bfloat16** 으로 계산된다(측정: fp32 대비 확률 최대 |차| 1.1e-2~1.3e-2). 골든·ONNX 는 fp32(모델 카드 경로) 기준이다.
- ONNX 확률로 같은 후처리를 해도 4 클립 모두 구간이 같았다(`sameSegmentsWithOnnxProbs: true`).

### 7.4 주의: 마지막 출력 프레임(패딩 영역) 과 오디오 끝 초과

- 출력 프레임 수 = F/2 인데, 마지막 출력 프레임은 입력 프레임 `2j, 2j+1 ≥ seqLen`(마스크 0·패딩 0)만 보는 **패딩 영역**이다.
  4 클립 모두 이 프레임 확률이 **0.45888966(> onset 0.3)** 이다. NeMo 공식 경로는 모든 출력 프레임을 그대로 후처리하므로
  - 합성 클립(끝 2 s 디지털 무음)에서도 가짜 구간 `[7.80, 8.20]` 이 생기고,
  - 구간 끝이 오디오 길이를 넘는다(20 s+77 클립 끝 20.20 s > 20.0048 s, 70 s 클립 70.20 s).
- 골든은 NeMo 동작을 그대로 담았다. Kotlin 은 골든과 맞추려면 같은 프레임을 그대로 써야 한다.
- change(add)-hyungchul-2026-10-10-14:54 R18 컨트롤러 판정: **NeMo 동작 그대로 유지**(골든 변경 없음). 앱 `VadPresets.kt` MARBLENET_NEMO note 에
  "NeMo 그대로: 마지막(패딩) 프레임 확률 때문에 파일 끝에 짧은 구간이 생기거나 끝이 오디오 길이를 넘을 수 있음(앱의 ChunkPlanner 가 오디오 범위로 자름).
  학습 데이터에 한국어 없음." 을 넣었다. 오디오 길이 초과분은 `ChunkPlanner` 의 1단계 clamp(`minOf(seg.endSec, durationSec)`)가 자른다.
  파일 끝 짧은 가짜 구간 자체는 남는다(합성 클립 [7.80, 8.20] → clamp 후 [7.80, 8.0077]).

## 8. 골든 JSON 형식(`app/src/test/resources/golden/`, 공통 형식은 tools/golden README 5절)

| 파일 | 개수 | 내용 |
|---|---|---|
| `FIRERED_FIRERED_OFFLINE_<clip>.json` | 4 + 손작성 1 | hop 0.010, `frameScores` = 공식 PyTorch 오프라인 확률, `expected` = `detect()` 구간(초, 3자리). 추가: `totalSamples`, `durSec`, `expectedFrames`([시작, 끝) 프레임), `stages`(6.3 절 2~7 단계 프레임 구간), `minAbsSmoothedMinusThreshold`, `splitApplied`, `sameSegmentsWithOnnxProbs`, `params` |
| `FIRERED_FIRERED_STREAM_<clip>.json` | 4 + 손작성 1 | hop 0.010, `frameScores` = 공식 PyTorch 스트리밍 확률, `expected` = `detect_full()` 구간. 추가: `expectedFrames`(0-based), `events`, `params`(+ `effectiveSmoothWindow` 5, `effectivePadStartFrame` 5) |
| `MARBLENET_MARBLENET_NEMO_<clip>.json` | 4 | hop 0.02, `frameScores` = NeMo fp32 확률, `expected` = NeMo 후처리 (start, end) float32. 추가: `seqLen`, `processedFrames`, `expectedDur`, `binarized`, `filtered`, `params`, `perArgsFloat`, `sameSegmentsWithOnnxProbs`, `officialFileRoute` |
| `scores_FIRERED_<clip>.json` | 4 | **오프라인 ONNX**(`fireredvad_vad.onnx`) 확률, `expected: null`, `model`, `torchMaxAbsDiff` |
| `scores_FIRERED_STREAM_<clip>.json` | 4 | **스트리밍 ONNX**(`fireredvad_stream_vad.onnx`) 확률(두 모델이라 파일을 나눔) |
| `scores_MARBLENET_<clip>.json` | 4 | ONNX 확률 `softmax(output)[:, 1]`, `seqLen`, `processedFrames`, `torchMaxAbsDiff` |
| `firered_fbank_<clip>.json` | 4 | 공식 fbank(povey, CMVN 전) 앞 50 프레임 × 80, `numFramesTotal`, `inputScale` 1(int16 값 그대로), `knfOptions`, `totalSamples` |
| `firered_fbank_cmvn_<clip>.json` | 4 | CMVN 후(모델 입력) 앞 50 프레임 × 80, `cmvnFile` "firered_cmvn.ark" |
| `firered_cmvn.json` / `firered_cmvn.ark` | 1 / 1 | CMVN 해석값(`means`, `inverseStdVariances`, `count`, `varianceFloor`) / 원본 Kaldi 바이너리(파서 테스트용) |
| `marblenet_mel_<clip>.json` | 4 | NeMo log-mel `expected[t][m] = processed_signal[0][m][t]` 앞 50 프레임, `tailFrames`(끝 4 프레임: 마스크·패딩 0 확인), `numFramesTotal`(F), `seqLen`, `numpyFloat64MaxAbsDiff` |
| `marblenet_preprocessor.json` | 1 | 7.2 절 설정 원문·실효값·`windowValues`(400)·`melFilterbank`(80×257), `expected: null` |

change(add)-hyungchul-2026-10-11-13:57 **로컬 전용**: 위 `firered_fbank_<clip>.json`·`firered_fbank_cmvn_<clip>.json`·`marblenet_mel_<clip>.json` 중 사용자 음성 클립
(`kr_customer_01_20s_plus77`, `en_customer_01_20s_plus77`, `kr_customer_01_70s_plus55`) 9개는 사용자 음성에서 파생된 특징이라 공개 저장소에 올리지 않는다(`.gitignore`, 파일은 로컬 유지).
합성 클립(`synth_silence_noise_8s_plus123`) 3개와 `firered_cmvn.*`, 구간·점수 골든은 계속 커밋한다.
`gen_firered_golden.py`·`gen_marblenet_golden.py` 를 다시 실행하면 로컬 파일이 만들어진다. 없는 환경에서 `FireRedMarbleNetParityTest` 는 그 클립의 전처리 비교만 건너뛴다.

- 손작성 골든(`clip = "synthetic_firered_long"`, `synthetic: true`, 음원 없음): N = 726160(4537 프레임), 확률 = 앞 1 s 0.05, 나머지 0.9,
  [1500,1505) 0.2, [2600,2604) 0.1, 3300 0.15, 끝 50 프레임 0.02(모두 float32 로 정확히 표현되는 값).
- frameScores 길이 규칙: FireRed `1 + (N − 400)/160`(snip_edges), MarbleNet `F/2`(F = `1 + N/160` 를 짝수로 올림).
- 비교 허용 권장: FireRed 구간은 초 값 그대로(0.001 s 이내) 또는 `expectedFrames` 정확 비교, MarbleNet 구간 1e-4 s, 특징은 1e-4.

## 9. 한계·주의(요약)

- (R17 해결) FIRERED_STREAM smoothWindow 는 공식 기본값 5 로 정정했고 STREAM 골든 5개(클립 4 + 손작성 1)를 다시 만들었다.
- MarbleNet 마지막 패딩 프레임 동작(7.4 절)은 R18 판정대로 NeMo 그대로 유지한다. 끝 초과분은 ChunkPlanner 가 자르지만 파일 끝 짧은 구간은 남을 수 있다.
  MarbleNet 학습 데이터에 한국어가 없다(5절). 두 내용은 VadPresets.kt MARBLENET_NEMO note 에도 있다.
- NeMo 3.0.0 strict 복원 실패를 strict=False + 텐서 전수 비교로 우회했다(7.1 절). 모델 카드 Runtime(2.3.0)으로는 실행하지 않았다.
- 기기(S25 Ultra)에서의 ONNX 실행·수치 비교는 이 작업 범위가 아니며 수행하지 않았다.
