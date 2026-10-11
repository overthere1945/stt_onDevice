#!/usr/bin/env bash
# 파일명: tools/adb/pull_results.sh
# 목적: 기기 앱 폴더의 실행 결과 output_stt/<run_id> 를 PC 폴더로 adb pull 하고, PC 평가기(run_eval.py) 실행 명령을 언어별로 출력한다.
# 기능: 1) adb·기기 연결 확인(0대면 중단, 2대 이상이면 경고 후 ANDROID_SERIAL 지정 요구)
#       2) run_id 를 주지 않으면 기기의 output_stt 아래 yyyyMMdd_HHmmss 폴더 중 가장 최근 것을 고른다
#       3) PC 대상 <dest>/<run_id> 가 이미 있으면 중단(덮어쓰거나 지우지 않음). 받는 동안에는 <dest>/.pull_<run_id>.XXXXXX 임시 폴더에 받고
#          끝나면 <dest>/<run_id> 로 옮긴다(실패하면 이 스크립트가 만든 임시 폴더만 지움)
#       4) run_manifest.json 의 finishedAt 이 없으면 "미완료 run" 경고
#       5) 결과에 있는 언어(kr/en)마다 run_eval.py 명령을 출력한다(--hyp <dest>/<run_id>/<lang>, 출력은 <dest>/<run_id>_eval/<lang>). 실행은 사용자가 한다.
# 사용법: tools/adb/pull_results.sh [run_id] [PC 대상 폴더(기본: ./device_results)]
#         예) tools/adb/pull_results.sh                       # 최신 run 을 ./device_results 로
#             tools/adb/pull_results.sh 20261011_035022 ~/stt_runs
# 환경변수(선택): ANDROID_SERIAL(대상 기기), EVAL_PY_DIR(평가기 폴더), EVAL_PYTHON(평가기 venv python), ANSWER_SHEET_DIR(정답지 루트)
# change(add)-hyungchul-2026-10-11-03:59

set -euo pipefail # 오류·미정의 변수·파이프 실패 시 즉시 중단

PKG="com.cowork.stt_ondevice" # 앱 패키지
DEV_OUT="/sdcard/Android/data/${PKG}/files/output_stt" # 기기의 결과 폴더
EVAL_DIR="${EVAL_PY_DIR:-${HOME}/workspace/python/asr_stt/stt_all_gui_2nd}" # 사용자 평가기 폴더(수정 금지, 실행만)
PY="${EVAL_PYTHON:-${HOME}/workspace/python/venv_eval/bin/python}" # 평가기 전용 venv
SHEET="${ANSWER_SHEET_DIR:-${HOME}/workspace/stt/answer_sheet}" # 정답지 루트
TMP_DIR="" # 이 스크립트가 만든 임시 폴더(정리 대상)

# 목적: 오류 출력 후 종료 / 입력: $1 = 종료 코드, $2 = 한국어 메시지 / 출력: 표준오류 / 리턴: 돌아오지 않음
die() {
  echo "오류: $2" >&2 # 메시지
  exit "$1" # 종료
}

# 목적: 임시 폴더 정리 / 기능: 이 스크립트가 만든 임시 폴더가 남아 있으면 지운다(그 밖의 PC 파일은 건드리지 않음) / 입력·출력: 없음 / 리턴: 0
cleanup() {
  if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then rm -rf -- "${TMP_DIR}"; fi # 자기 임시 폴더만
}
trap cleanup EXIT # 종료 시 정리

# 목적: adb 와 기기 연결 확인
# 기능: adb 명령 존재, device 상태 기기가 1대인지(또는 ANDROID_SERIAL 지정) 확인. 여러 대면 경고 후 지정 요구.
# 입력: 없음 / 출력: 대상 기기 또는 오류 / 리턴: 0
check_device() {
  command -v adb >/dev/null 2>&1 || die 2 "adb 명령을 찾을 수 없습니다(Android SDK platform-tools 를 PATH 에 추가하세요)" # adb 없음
  local devs # 기기 시리얼 목록
  devs="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')" # 연결된 기기
  local n # 기기 수
  n="$(printf '%s\n' "${devs}" | grep -c . || true)" # 개수
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then # 시리얼 지정
    printf '%s\n' "${devs}" | grep -qx "${ANDROID_SERIAL}" || die 2 "ANDROID_SERIAL=${ANDROID_SERIAL} 기기가 연결돼 있지 않습니다(adb devices 확인)" # 지정 기기 없음
    echo "대상 기기: ${ANDROID_SERIAL}" # 알림
    return 0 # 끝
  fi
  [[ "${n}" -ge 1 ]] || die 2 "연결된 기기가 없습니다(USB 연결과 'USB 디버깅 허용'을 확인하세요)" # 0대
  if [[ "${n}" -gt 1 ]]; then # 여러 대
    echo "경고: 기기가 ${n}대 연결돼 있습니다:" >&2 # 경고
    printf '  %s\n' ${devs} >&2 # 목록
    die 2 "ANDROID_SERIAL=<시리얼> 로 대상 기기를 지정해 다시 실행하세요" # 지정 요구
  fi
  echo "대상 기기: ${devs}" # 알림
}

RUN_ID="${1:-}" # 실행 ID(없으면 최신)
DEST="${2:-${PWD}/device_results}" # PC 대상 폴더
[[ -z "${RUN_ID}" || "${RUN_ID}" =~ ^[0-9]{8}_[0-9]{6}$ ]] || die 2 "run_id 형식은 yyyyMMdd_HHmmss 입니다: ${RUN_ID}" # 형식 검사

check_device # 기기 확인

RUNS="$(adb shell "ls '${DEV_OUT}' 2>/dev/null" | tr -d '\r' | grep -E '^[0-9]{8}_[0-9]{6}$' | sort || true)" # 기기의 run 목록(오름차순)
[[ -n "${RUNS}" ]] || die 1 "기기에 실행 결과가 없습니다(${DEV_OUT})" # 결과 없음
if [[ -z "${RUN_ID}" ]]; then # 지정 안 함
  RUN_ID="$(printf '%s\n' "${RUNS}" | tail -n 1)" # 최신
  echo "run_id 를 지정하지 않아 최신 실행을 받습니다: ${RUN_ID}" # 알림
else
  printf '%s\n' "${RUNS}" | grep -qx "${RUN_ID}" || die 1 "기기에 그 실행이 없습니다: ${RUN_ID} (있는 것: $(printf '%s ' ${RUNS}))" # 없음
fi

FINAL="${DEST}/${RUN_ID}" # 최종 위치
[[ ! -e "${FINAL}" ]] || die 1 "PC 에 이미 있습니다: ${FINAL} — 덮어쓰지 않습니다. 다른 대상 폴더를 지정하거나 직접 정리하세요" # 덮어쓰기 금지
mkdir -p -- "${DEST}" # 대상 폴더
TMP_DIR="$(mktemp -d "${DEST}/.pull_${RUN_ID}.XXXXXX")" # 임시 폴더(이 스크립트 소유)
echo "===== pull: ${DEV_OUT}/${RUN_ID} → ${FINAL} =====" # 진행 알림
adb pull "${DEV_OUT}/${RUN_ID}" "${TMP_DIR}/" # 받기 → TMP/<run_id>
[[ -d "${TMP_DIR}/${RUN_ID}" ]] || die 1 "받은 폴더가 없습니다: ${TMP_DIR}/${RUN_ID}" # 확인
mv -- "${TMP_DIR}/${RUN_ID}" "${FINAL}" # 최종 위치로
rmdir -- "${TMP_DIR}" && TMP_DIR="" # 빈 임시 폴더 정리
echo "받은 파일 수: $(find "${FINAL}" -type f | wc -l | tr -d ' ')" # 결과

MANIFEST="${FINAL}/run_manifest.json" # manifest
if [[ ! -f "${MANIFEST}" ]]; then
  echo "경고: run_manifest.json 이 없습니다" >&2 # 없음
elif ! grep -Eq '"finishedAt"[[:space:]]*:[[:space:]]*"' "${MANIFEST}"; then # 종료 시각 없음
  echo "경고: 미완료 실행입니다(finishedAt 없음). 일부 파일만 채점됩니다" >&2 # 미완료
fi

FOUND=0 # 평가할 언어 수
echo "" # 빈 줄
echo "PC 평가기 실행 명령(복사해서 실행):" # 머리글
for L in kr en; do # 언어마다
  [[ -d "${FINAL}/${L}" ]] || continue # 그 언어 결과 없음
  FOUND=$((FOUND + 1)) # 집계
  # 평가기 폴더로 cd 해서(사용자 방식·상대 import) 실행. 출력은 PC 대상 폴더 아래 <run_id>_eval/<lang>.
  printf '  (cd %q && %q run_eval.py --ref %q --hyp %q --out %q --no-noformat)\n' \
    "${EVAL_DIR}" "${PY}" "${SHEET}/${L}" "${FINAL}/${L}" "${DEST}/${RUN_ID}_eval/${L}" # 명령 출력
done
[[ "${FOUND}" -gt 0 ]] || echo "  (결과에 kr/ 또는 en/ 폴더가 없습니다 — 평가할 전사가 없습니다)" # 없음
