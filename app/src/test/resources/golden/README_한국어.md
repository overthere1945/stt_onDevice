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
