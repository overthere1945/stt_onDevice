# STT_OnDevice

<!-- change(add)-hyungchul-2026-10-11-04:46 (Task 31) -->

스마트폰(Android) 안에서 무료·상업 이용 가능한 음성인식(STT) 엔진과 VAD(음성 구간 검출)를 **조합별로** 실행해
한국어·영어 음원을 전사하는 비교용 앱입니다. 결과 폴더를 PC 로 가져와 기존 PC 평가기(`run_eval.py`)로 WER/CER 을 계산해
가장 좋은 엔진 × VAD 조합을 찾습니다. 앱은 전사만 하고, 정확도 계산은 PC 평가기가 합니다.

- 엔진(Phase 1): whisper.cpp(tiny~large-v3-turbo), sherpa-onnx(Zipformer KO·EN·스트리밍 KO, SenseVoice, Qwen3-ASR 0.6B, Omnilingual CTC 3종), Vosk(KO·EN)
- VAD: Silero, pyannote, FSMN, WebRTC, TEN, FireRedVAD, MarbleNet, Yamnet + NONE — 프리셋 26종(원본 구현의 기본값 재현, 골든 테스트로 확인)
- 출력: PC Tool 과 같은 폴더·txt·CSV(60열) 형식 → `run_eval.py --hyp <run>/kr` 에서 `<algo>_<model>_<VAD>` 로 페어링
- 대상: Android 12 이상, arm64-v8a, 모든 네이티브 라이브러리 16 KB 정렬

## 문서

- [사용설명서](docs/사용설명서.md) — 빌드·설치·모델·음원·실행·결과·PC 평가·문제 해결
- [Phase 1 테스트 보고서](docs/테스트보고서_phase1.md) — 빌드·단위·기기 검증 결과, 엔진·모델별 완료 표, VAD 정합성, 미완료 항목
- [Phase 1 설계서](docs/superpowers/specs/2026-10-09-stt-ondevice-phase1-design.md)

## 빠른 시작

```bash
git submodule update --init
tools/fetch_native_deps.sh
./gradlew :app:installDebug
```

모델·음원은 저장소에 들어 있지 않습니다. 앱에서 내려받거나 `tools/adb/` 스크립트로 넣으세요(사용설명서 4·5절).
