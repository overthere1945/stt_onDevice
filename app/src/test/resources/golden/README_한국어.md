# app/src/test/resources/golden — VAD 골든 테스트 리소스

change(add)-hyungchul-2026-10-10-12:52

이 폴더의 JSON 은 각 VAD 의 **원본 Python 구현**이 만든 기준 결과(골든)이며, Kotlin 포팅의 JVM 단위 테스트가 비교 대상으로 읽는다.
생성 스크립트·정확한 알고리즘·형식 정의·다운로드 출처는 **[tools/golden/README_한국어.md](../../../../../tools/golden/README_한국어.md)** 를 본다.
JSON 은 주석을 달 수 없으므로 이 문서와 위 문서가 설명을 대신한다. 이 폴더 파일은 직접 고치지 말고 생성 스크립트로 다시 만든다.

## 파일 종류 요약

| 파일 | 개수 | 내용 |
|---|---|---|
| `<FAMILY>_<presetId>_<clipStem>.json` | 76 | 실제 클립 4개 × 프리셋(Silero 8, pyannote 4, FSMN 2, WebRTC 3, TEN 2)의 프레임 점수 + 기대 구간 `expected` |
| `SILERO_SILERO_SHERPA_synthetic_maxspeech_{a,b,c}.json`, `TEN_TEN_SHERPA_synthetic_maxspeech_{a,b,c}.json` | 6 | 음원 없는 손작성 확률로 max_speech(20 s) 분할 분기를 검증하는 골든(`synthetic: true`) |
| `scores_{SILERO,PYANNOTE,FSMN}_<clipStem>.json` | 12 | 모델 추론 정합용 점수만(`expected: null`) |
| `fbank_<clipStem>.json` | 4 | kaldi-native-fbank 80 mel 앞 50 프레임 |
| `lfr_cmvn_<clipStem>.json` | 4 | LFR(5,1) → CMVN(fsmn_am.mvn) 앞 10 프레임 |
| `fsmn_am.mvn` | 1 | FSMN CMVN 파일(아래) |
| `audio/*.wav` | 4 | 골든 클립(사용자 음원 파생물, **gitignore — 저장소에 없음**). `tools/golden/make_clips.py` 로 재생성 |

### FireRedVAD·MarbleNet 골든 (change(add)-hyungchul-2026-10-10-14:25, Task 13)

생성 스크립트·알고리즘·전처리·ONNX 입출력 설명은 **[tools/export/README_한국어.md](../../../../../tools/export/README_한국어.md)** 를 본다(6~8절).

| 파일 | 개수 | 내용 |
|---|---|---|
| `FIRERED_FIRERED_OFFLINE_<clipStem>.json`, `FIRERED_FIRERED_STREAM_<clipStem>.json` | 8 | 공식 fireredvad(commit c30ec49) `detect()`/`detect_full()` 구간 + PyTorch 확률(hop 0.010) + `expectedFrames`·`stages`/`events` |
| `FIRERED_FIRERED_{OFFLINE,STREAM}_synthetic_firered_long.json` | 2 | 손작성 확률(음원 없음, `synthetic: true`)로 max_speech(2000 프레임) 분할 분기 골든 |
| `MARBLENET_MARBLENET_NEMO_<clipStem>.json` | 4 | NeMo 3.0.0 `vad_utils` 후처리 구간(float32 초) + NeMo fp32 확률(hop 0.02) + `binarized`·`filtered`·`officialFileRoute` |
| `scores_FIRERED_<clipStem>.json` / `scores_FIRERED_STREAM_<clipStem>.json` / `scores_MARBLENET_<clipStem>.json` | 12 | 변환한 ONNX(오프라인 / 스트리밍 / MarbleNet) 확률(모델 추론 정합용, `expected: null`) |
| `firered_fbank_<clipStem>.json`, `firered_fbank_cmvn_<clipStem>.json` | 8 | FireRed fbank(povey 창, int16 입력) 앞 50 프레임 × 80, CMVN 적용 후(모델 입력) 앞 50 프레임 |
| `firered_cmvn.json`, `firered_cmvn.ark` | 2 | FireRed CMVN 해석값(mean·inverse std, float64) / 원본 Kaldi 바이너리 행렬(아래) |
| `marblenet_mel_<clipStem>.json` | 4 | NeMo log-mel(프레임×80) 앞 50 프레임 + 끝 4 프레임(`tailFrames`) |
| `marblenet_preprocessor.json` | 1 | NeMo 전처리 설정 원문·실효값·hann 창 400 값·mel 필터뱅크 80×257 (`expected: null`) |

주의: MarbleNet 의 마지막 출력 프레임은 패딩 영역이며(확률 0.45888966), NeMo 동작 그대로라 합성 클립에도 끝 구간 `[7.80, 8.20]` 이 있고
구간 끝이 오디오 길이를 넘을 수 있다(tools/export README 7.4 절). 컨트롤러 판정(R18)으로 골든은 NeMo 그대로 유지하며, 앱에서는 ChunkPlanner 가 오디오 범위로 자른다.

change(add)-hyungchul-2026-10-10-14:54 R17: `FIRERED_FIRERED_STREAM_*`(클립 4 + 손작성 1)는 smooth_window 5(FireRedVAD stream 기본값,
`params.smooth_window_size` 5, `effectiveSmoothWindow` 5)로 다시 만들었다.

## firered_cmvn.ark 출처·라이선스

- 출처 URL: https://huggingface.co/FireRedTeam/FireRedVAD/resolve/7990aaccc6b7aec1e527743bd30201f2c4a03b8c/VAD/cmvn.ark (`Stream-VAD/cmvn.ark` 와 동일 파일)
- SHA-256: `c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473` (원본 그대로 복사, 수정 없음)
- 라이선스: **Apache-2.0** (FireRedVAD, Xiaohongshu FireRedTeam). 재배포 시 출처·라이선스 고지를 유지한다.
- 형식: Kaldi 바이너리 double 행렬 2×81(0행 = 특징별 합 + count, 1행 = 제곱합 + 0). 용도: Kotlin FireRed CMVN 파서·적용 단위 테스트 입력.

클립: `kr_customer_01_20s_plus77`(320077 샘플), `en_customer_01_20s_plus77`(320077), `synth_silence_noise_8s_plus123`(128123),
`kr_customer_01_70s_plus55`(1120055). 길이는 일부러 hop 배수가 아니다.

공통 JSON 키: `clip, family, presetId, hopSec, frameTimesSec, frameScores, frameDb, expected, tool, totalSamples`
(+ 계열별 추가 키 `expectedSamples`, `expectedFrames`, `expectedMs`, `params`, `windowSamples`, `blocks`, `frameFlags`, `synthetic`, `branchesTaken` 등 —
로더는 모르는 키를 무시해야 한다). `frameScores` 는 float32 로 정확히 복원되는 최단 10진 표기다.

## fsmn_am.mvn 출처·라이선스

- 출처 URL: https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/master/am.mvn
- 모델명: `iic/speech_fsmn_vad_zh-cn-16k-common-onnx` (FunASR FSMN-VAD, Alibaba DAMO Academy Speech Lab)
- SHA-256: `6820fef9687708c4fc3fab2530179c8fcea6262daa25514380056cd8f6eb1754` (원본 그대로 복사, 수정 없음)
- 라이선스: **FunASR Model License 1.1** — 사용·재배포 시 출처(FunASR / ModelScope)와 모델명을 유지해야 한다. 이 문서의 위 표기를 지우지 말 것.
- 용도: Kotlin FSMN 전처리(LFR 후 CMVN `(x + mean) × var`) 단위 테스트 입력.
