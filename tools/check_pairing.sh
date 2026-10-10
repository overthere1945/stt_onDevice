#!/usr/bin/env bash
# 파일명: tools/check_pairing.sh
# 목적: 앱의 출력 트리를 사용자의 기존 PC 평가기(run_eval.py)가 그대로 소비하는지(페어링) 확인한다. (Task 26)
# 기능: app/build/sample_run/LATEST 가 가리키는 샘플 실행(SampleRunExportTest 가 만든 것)을 kr·en 각각 평가기로 채점하고
#       by_model.csv 에서 (1) 모델 4개(fake_m1_NONE, fake_m1_SILERO, fake_m2_NONE, fake_m2_SILERO)가 모두 있는지,
#       (2) 모든 행의 WER·CER(micro/macro 모든 (%) 열)가 0.0 인지 확인한다. 조합 폴더의 result.csv 때문에 생기는
#       '<조합명>_<model>' 유령 모델은 경고로만 출력한다(실패 아님). 하나라도 어긋나면 해당 행을 출력하고 exit 1, 모두 맞으면 exit 0.
#       평가기(~/workspace/python 아래)는 읽기 전용으로 "실행만" 한다: 평가기 폴더로 cd 해서 사용자와 똑같이 돌리되(상대 import 때문),
#       --out 은 전부 app/build/eval_check/<lang> 아래라 평가기 폴더에는 아무것도 쓰지 않는다
#       (python 은 PYTHONDONTWRITEBYTECODE=1 로 __pycache__ 생성도 막는다).
# 사전 준비: ./gradlew :app:testDebugUnitTest --tests "com.cowork.stt_ondevice.runner.SampleRunExportTest"
# 환경변수(선택): EVAL_PY_DIR(평가기 폴더), EVAL_PYTHON(평가기 venv python), ANSWER_SHEET_DIR(정답지 루트)
# change(add)-hyungchul-2026-10-11-02:26

set -euo pipefail # 오류·미정의 변수·파이프 실패 시 즉시 중단

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" # 프로젝트 루트
EVAL_DIR="${EVAL_PY_DIR:-${HOME}/workspace/python/asr_stt/stt_all_gui_2nd}" # 사용자 평가기 폴더(수정 금지)
PY="${EVAL_PYTHON:-${HOME}/workspace/python/venv_eval/bin/python}" # 평가기 전용 venv
SHEET="${ANSWER_SHEET_DIR:-${HOME}/workspace/stt/answer_sheet}" # 정답지 루트
SAMPLE_ROOT="${ROOT}/app/build/sample_run" # 샘플 실행 루트
OUT_ROOT="${ROOT}/app/build/eval_check" # 채점 결과 폴더(빌드 산출물 영역)
EXPECTED_MODELS=(fake_m1_NONE fake_m1_SILERO fake_m2_NONE fake_m2_SILERO) # 기대 모델 이름(언어별)

[[ -f "${SAMPLE_ROOT}/LATEST" ]] || { echo "LATEST 없음: 먼저 SampleRunExportTest 를 실행하세요 (${SAMPLE_ROOT}/LATEST)" >&2; exit 2; } # 샘플 실행 없음
RUN_ID="$(tr -d '[:space:]' < "${SAMPLE_ROOT}/LATEST")" # 최신 runId
RUN_DIR="${SAMPLE_ROOT}/${RUN_ID}" # 샘플 실행 폴더
[[ -d "${RUN_DIR}" ]] || { echo "샘플 실행 폴더 없음: ${RUN_DIR}" >&2; exit 2; } # 폴더 없음
[[ -x "${PY}" ]] || { echo "평가기 python 없음: ${PY}" >&2; exit 2; } # venv 없음
[[ -f "${EVAL_DIR}/run_eval.py" ]] || { echo "평가기 없음: ${EVAL_DIR}/run_eval.py" >&2; exit 2; } # 평가기 없음
echo "샘플 실행: ${RUN_DIR}" # 대상 알림

FAIL=0 # 실패 여부
for LANG_DIR in kr en; do # 언어 폴더마다
  OUT="${OUT_ROOT}/${LANG_DIR}" # 이 언어의 채점 결과 폴더
  rm -rf "${OUT}" # 이전 결과 제거(빌드 산출물 폴더 안)
  mkdir -p "${OUT}" # 폴더 생성
  echo "===== ${LANG_DIR}: 평가기 실행 =====" # 구분선
  # 평가기 폴더에서 사용자와 같은 방식으로 실행(읽기만 함). 진단 출력은 로그 파일에 보관하고 실패 시에만 보여 준다.
  if ! (cd "${EVAL_DIR}" && PYTHONDONTWRITEBYTECODE=1 "${PY}" run_eval.py \
        --ref "${SHEET}/${LANG_DIR}" --hyp "${RUN_DIR}/${LANG_DIR}" --out "${OUT}" --no-noformat) > "${OUT}.log" 2>&1; then
    echo "평가기 실행 실패 (${LANG_DIR}). 로그: ${OUT}.log" >&2 # 실행 실패
    tail -n 20 "${OUT}.log" >&2 # 마지막 로그
    FAIL=1 # 실패 표시
    continue # 다음 언어
  fi
  BY_MODEL="${OUT}/by_model.csv" # 모델별 요약 CSV
  [[ -f "${BY_MODEL}" ]] || { echo "by_model.csv 없음: ${BY_MODEL}" >&2; FAIL=1; continue; } # 요약 없음
  echo "--- ${LANG_DIR} by_model.csv ---" # 머리글
  cat "${BY_MODEL}" # 실제 내용 출력
  # CSV 검사는 파일을 읽기만 하므로 -I(격리 모드)로 실행한다. 기대 모델 목록은 인자로 넘긴다.
  if ! "${PY}" -I - "${BY_MODEL}" "${LANG_DIR}" "${EXPECTED_MODELS[@]}" <<'PYEOF'
import csv, sys
path, lang, expected = sys.argv[1], sys.argv[2], sys.argv[3:]
with open(path, encoding="utf-8-sig", newline="") as f:
    rows = list(csv.DictReader(f))
names = [r.get("model", "") for r in rows]
bad, warn = [], []
for m in expected:                                   # 기대 모델이 모두 있는지
    if m not in names:
        bad.append(f"[{lang}] 모델 누락: {m}")
for m in names:                                      # 기대하지 않은 모델이 있는지
    if m in expected:
        continue
    if any(m.startswith(e + "_") for e in expected): # '<조합명>_<model>' = 조합 폴더의 result.csv 가 만든 유령 모델(아래 설명)
        warn.append(f"[{lang}] 주의: 유령 모델 {m}")
    else:
        bad.append(f"[{lang}] 예상 밖 모델: {m}")
rate_cols = [c for c in (rows[0].keys() if rows else []) if (c.startswith("WER") or c.startswith("CER")) and c.endswith("(%)")]
if not rate_cols:
    bad.append(f"[{lang}] WER/CER 열을 찾지 못함")
for r in rows:                                       # 모든 WER/CER(micro·macro) 열이 0.0 인지
    for c in rate_cols:
        try:
            ok = float(r[c]) == 0.0
        except (KeyError, ValueError):
            ok = False
        if not ok:
            bad.append(f"[{lang}] {c} 가 0.0 아님: {r}")
if warn:                                             # 유령 모델은 실패가 아니라 경고(사용자 GUI 의 'txt 만 평가' 옵션이 막는 현상)
    print("\n".join(warn), file=sys.stderr)
    print(f"[{lang}] 설명: 앱은 조합 폴더마다 result.csv(model 열=모델명)를 쓰는데, run_eval.py 는 폴더 접두어(조합명)와 model 열을 이어 "
          "'<조합명>_<model>' 이름으로 한 번 더 등록한다. 사용자 GUI 의 '요약 CSV 를 평가에서 제외(txt 만)' 옵션을 쓰면 나타나지 않는다.", file=sys.stderr)
if bad:
    print("\n".join(bad), file=sys.stderr)
    sys.exit(1)
print(f"[{lang}] OK: 모델 {len(expected)}개 존재, 모든 WER/CER = 0.0" + (f" (유령 모델 경고 {len(warn)}건)" if warn else ""))
PYEOF
  then
    FAIL=1 # 검사 실패
  fi
done

if [[ "${FAIL}" -ne 0 ]]; then # 하나라도 실패
  echo "페어링 확인 실패" >&2 # 결과
  exit 1 # 실패 종료
fi
echo "페어링 확인 통과: kr·en 모두 모델 4개, WER/CER 0.0" # 성공
