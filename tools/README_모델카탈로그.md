# 모델 카탈로그 도구 (`hash_models.py`, `model_sources.json`)

<!-- change(add)-hyungchul-2026-10-10-16:12 (Task 18) -->
<!-- change(add)-hyungchul-2026-10-10-20:00 (Task 18 재개: 캐시 무효 처리·용량·role 값 설명 갱신) -->

앱이 설치하는 모든 Phase 1 ASR·VAD 모델 artifact 를 **실제로 내려받아** 파일별 크기·SHA-256 을 고정하고,
앱 소스 `app/src/main/java/com/cowork/stt_ondevice/model/ModelCatalogData.kt` 를 생성한다.
모델별 라이선스 근거·역할(role) ↔ 엔진 설정 필드 표는 `app/src/main/java/com/cowork/stt_ondevice/model/README_카탈로그.md` 에 있다.

## 1. 실행

```bash
cd /home/hyungchul/AndroidStudioProjects/STT_OnDevice
python3 -I tools/hash_models.py --list            # 받고 해제한 뒤 파일 목록만 출력(files 선언 전 확인용)
python3 -I tools/hash_models.py --only vosk-small-ko-0.22   # 일부 항목만 검증
python3 -I tools/hash_models.py --emit-kotlin     # 전체 검증 + ModelCatalogData.kt 생성
```

- `-I`(격리 모드)로 실행한다. 내려받은 폴더의 파일이 import 되지 않게 하기 위함이다. 내려받은 내용은 읽고 해시만 하며 실행하지 않는다.
- 외부 패키지가 필요 없다(Python 3.12 표준 라이브러리만 사용).
- 재실행 가능: 검증 표식(`dl<n>.json`, `x<n>.json`)이 맞는 캐시는 다시 받지 않고 해시만 다시 확인한다. 중간에 끊긴 다운로드는 `.part` 에서 이어받는다(HTTP Range).
  표식이 URL·크기·해시와 맞지 않거나 표식 없이 최종 파일만 있으면 그 `dl<n>/` 폴더를 지우고 새 빈 폴더에 다시 받는다.
  해제 표식(`x<n>.json`)이 없는 해제 폴더(중단된 해제)는 지우고 새 빈 폴더에 다시 해제한다.
- 전체 다운로드 약 7.3 GB(2026-10-10 실측 7,306,798,038 B), 해제본 포함 캐시 약 12 GB.

## 2. 캐시 구조 (`tools/models_cache/`, gitignore)

| 경로 | 내용 |
|---|---|
| `<id>/dl<n>/<URL 마지막 이름>` | n 번째 다운로드(각자 새 빈 폴더) |
| `<id>/dl<n>.json` | 다운로드 검증 표식(URL·bytes·sha256) |
| `<id>/x<n>/` | 압축 해제 결과(새 빈 폴더). 다시 해제할 때는 지우고 새로 만든다 |
| `<id>/x<n>.json` | 해제 표식(압축 SHA-256) |
| `catalog.lock.json` | 마지막 실행의 계산 결과(다운로드·파일·제외 목록) |

안전 해제: 절대경로·드라이브 문자·역슬래시·`..` 항목, 심볼릭/하드 링크·장치·FIFO 항목은 거부하고, 해제 폴더 밖으로 나가는 경로를 다시 확인하며, 같은 파일을 두 번 쓰지 않는다. 권한 비트는 복원하지 않는다.

## 3. `model_sources.json` 형식 (JSON 은 주석을 못 달아 여기서 설명)

최상위: `checkedDate`(라이선스 확인일), `models`(항목 배열, 이 순서대로 Kotlin 목록이 만들어진다).

| 키 | 설명 |
|---|---|
| `id` | 고유 id(소문자). 앱 설치 폴더 이름 |
| `kind` | `ASR` / `VAD` |
| `algo`, `model` | 결과 폴더 이름(spec §9.2 문자열 그대로). VAD 는 `vad-<계열>` / 버전 |
| `displayName` | 화면 표시 이름 |
| `languages` | 지원 언어 배열(VAD 는 빈 배열) |
| `runtime`, `runtimeVersion` | `WHISPER_CPP`/`SHERPA_OFFLINE`/`SHERPA_ONLINE`/`VOSK`/`ORT`/`LITERT` 와 그 버전 |
| `precision` | 파일 이름 기준 정밀도(f16/q5_0/q8_0/int8/fp32) |
| `maxChunkSec` | 1회 입력 최대 길이(초). 스트리밍·Vosk·VAD 는 0 |
| `streaming` | `FILE_ONLY` / `NATIVE_STREAMING` |
| `engineConfig` | `{"type":"whisper","beamSize":5}` / `{"type":"sherpa","kind":"<SherpaKind>"}` / `{"type":"vosk"}` / `{"type":"vad","family":"<VadFamily>"}` |
| `originalRepo`, `license`, `licenseUrl`, `commercial` | 원본 저장소, 라이선스 이름, 근거 URL, `ALLOWED`/`CONDITIONAL`/`NONCOMMERCIAL`/`UNVERIFIED` |
| `compatAlias` | PC 평가기 폴더 별칭(없으면 null) |
| `note` | 생성 Kotlin 항목 주석에 들어가는 한국어 메모 |
| `downloads[]` | `url`(HTTPS), `archive`(`NONE`/`TAR_BZ2`/`ZIP`), `stripPrefix`(압축 안 상위 폴더, `/` 로 끝남), `saveAs`(NONE 일 때 설치 파일 이름 — 스크립트 전용), `expectBytes`/`expectSha256`(공개 메타데이터·기존 골든 해시와 교차 확인, 선택) |
| `files[]` | `path`(모델 폴더 기준), `role`(엔진 설정 필드 연결용 — 값 목록과 대응 필드는 `model/README_카탈로그.md` 2절), `expectSha256`(선택). import 전용 항목은 `bytes`·`sha256` 고정값 |
| `importLocalDir` | import 전용 항목의 로컬 산출물 폴더(있으면 해시 재확인) |

- `downloads` 가 비어 있으면 **import 전용**(FireRedVAD·MarbleNet: PC 에서 변환한 ONNX, `tools/export/README_한국어.md` 4절 해시).
- `files` 에 없는 압축 안 파일(테스트 음원·README·내보내기 스크립트·쓰지 않는 정밀도 변형 등)은 실행 로그에 `[제외]` 로 출력하고 카탈로그에 넣지 않는다.
- archive=NONE 다운로드는 bytes·SHA-256 이 같은 `files` 항목이 정확히 1개여야 한다(앱 설치기는 이 규칙으로 저장 이름을 정한다).
