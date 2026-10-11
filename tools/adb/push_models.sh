#!/usr/bin/env bash
# 파일명: tools/adb/push_models.sh
# 목적: PC 의 모델 폴더 또는 압축 파일(.tar.bz2/.tbz2/.tbz/.zip)을 기기 앱 폴더 import/<model_id>/ 로 adb push 하고, 앱이 읽을 수 있게 권한을 정리한다.
# 기능: 1) adb·기기 연결 확인(0대면 중단, 2대 이상이면 경고 후 ANDROID_SERIAL 지정 요구), 앱 설치·앱 폴더 존재 확인
#       2) model_id 형식 검사(영문·숫자·._- 만) — 앱 카탈로그(ModelCatalogData.kt)에 없는 id 면 경고(앱이 그 폴더를 쓰지 않음)
#       3) 기기의 import/<model_id>/ 가 비어 있지 않으면 중단(섞이면 앱이 원본을 판단할 수 없음 — 지우는 명령만 안내하고 직접 지우지 않음)
#       4) 폴더는 import/<model_id>/<폴더 이름>/ 으로, 압축 파일은 import/<model_id>/<파일 이름> 으로 push(앱 가져오기는 둘 다 인식)
#       5) R41: import 아래 소유자가 shell 인 항목만 chmod a+rwX(앱 소유 폴더는 shell 이 바꿀 수 없음)하고, 남에게 읽기 권한이 없는 shell 항목이 남았으면 실패
#          (run-as 확인은 쓰지 않음 — run-as 는 셸의 ext_data_rw 그룹을 가져 앱이 못 읽는 폴더도 읽힘)
#       6) 다음 단계(앱 모델 화면 → 가져오기 → 가져오기 실행)를 한국어로 출력
#       PC 쪽 파일은 읽기만 한다(지우거나 바꾸지 않음).
# 사용법: tools/adb/push_models.sh <model_id> <로컬 폴더 또는 압축 파일>
#         예) tools/adb/push_models.sh whispercpp-small tools/models_cache/whispercpp-small
# 환경변수(선택): ANDROID_SERIAL(기기가 여러 대일 때 대상 기기 시리얼)
# change(add)-hyungchul-2026-10-11-03:59

set -euo pipefail # 오류·미정의 변수·파이프 실패 시 즉시 중단

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)" # 저장소 루트
PKG="com.cowork.stt_ondevice" # 앱 패키지
DEV_FILES="/sdcard/Android/data/${PKG}/files" # 기기의 앱 폴더
CATALOG="${ROOT}/app/src/main/java/com/cowork/stt_ondevice/model/ModelCatalogData.kt" # 앱 카탈로그 소스(id 확인용)

# 목적: 오류 출력 후 종료 / 입력: $1 = 종료 코드, $2 = 한국어 메시지 / 출력: 표준오류 / 리턴: 돌아오지 않음
die() {
  echo "오류: $2" >&2 # 메시지
  exit "$1" # 종료
}

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

# 목적: 앱 설치와 앱 폴더 확인 / 기능: pm path 와 앱 폴더 존재 확인 / 입력: 없음 / 출력: 문제 시 오류 / 리턴: 0
check_app() {
  adb shell pm path "${PKG}" 2>/dev/null | grep -q '^package:' || die 2 "앱(${PKG})이 설치돼 있지 않습니다. 먼저 앱을 설치하세요(./gradlew :app:installDebug)" # 미설치
  [[ "$(adb shell "[ -d '${DEV_FILES}' ] && echo yes || echo no" | tr -d '\r')" == "yes" ]] || die 2 "앱 폴더가 없습니다(${DEV_FILES}). 앱을 한 번 실행한 뒤 다시 시도하세요" # 앱 폴더 없음
}

MODEL_ID="${1:-}" # 모델 id
SRC="${2:-}" # 로컬 폴더 또는 압축 파일
[[ -n "${MODEL_ID}" && -n "${SRC}" ]] || die 2 "사용법: $0 <model_id> <로컬 폴더 또는 .tar.bz2/.zip 파일>" # 인자 부족
[[ "${MODEL_ID}" =~ ^[A-Za-z0-9._-]+$ && "${MODEL_ID}" != "." && "${MODEL_ID}" != ".." ]] || die 2 "model_id 는 영문·숫자·._- 만 쓸 수 있습니다: ${MODEL_ID}" # 경로 안전
if [[ -f "${CATALOG}" ]] && ! grep -q "id = \"${MODEL_ID}\"" "${CATALOG}"; then # 카탈로그에 없음
  echo "경고: '${MODEL_ID}' 는 앱 카탈로그에 없는 id 입니다. 앱 모델 화면에 그 모델이 없으면 가져올 수 없습니다" >&2 # 경고(계속 진행)
fi
if [[ -d "${SRC}" ]]; then # 폴더
  KIND="폴더" # 종류
  LOCAL_CNT="$(find "${SRC}" -type f | wc -l | tr -d ' ')" # 로컬 파일 수
elif [[ -f "${SRC}" ]]; then # 파일
  LOWER="$(printf '%s' "${SRC}" | tr '[:upper:]' '[:lower:]')" # 소문자 이름
  [[ "${LOWER}" == *.tar.bz2 || "${LOWER}" == *.tbz2 || "${LOWER}" == *.tbz || "${LOWER}" == *.zip ]] || die 2 "지원하는 압축 형식은 .tar.bz2/.tbz2/.tbz/.zip 입니다: ${SRC}" # 형식 검사
  KIND="압축 파일" # 종류
  LOCAL_CNT=1 # 파일 1개
else
  die 2 "폴더나 파일이 없습니다: ${SRC}" # 없음
fi

check_device # 기기 확인
check_app # 앱 확인

DEST="${DEV_FILES}/import/${MODEL_ID}" # 기기 대상 폴더
EXISTING="$(adb shell "ls -A '${DEST}' 2>/dev/null | head -n 1" | tr -d '\r')" # 기존 내용 첫 항목
if [[ -n "${EXISTING}" ]]; then # 비어 있지 않음
  echo "기기의 가져오기 폴더가 비어 있지 않습니다: ${DEST}" >&2 # 알림
  echo "이전에 넣은 파일과 섞이면 앱이 원본을 고를 수 없습니다. 필요 없으면 직접 지운 뒤 다시 실행하세요:" >&2 # 이유
  echo "  adb shell rm -rf '${DEST}'" >&2 # 지우는 명령(실행하지 않음)
  exit 1 # 중단
fi

adb shell "mkdir -p '${DEST}'" # 대상 폴더 만들기
echo "===== ${KIND} push: ${SRC} → ${DEST}/ (로컬 파일 ${LOCAL_CNT}개) =====" # 진행 알림
adb push "${SRC}" "${DEST}/" # 보내기(폴더는 하위 폴더로, 파일은 그대로)

echo "권한 정리: ${DEV_FILES}/import 아래 shell 소유 항목에 chmod a+rwX (adb 가 만든 폴더를 앱이 읽을 수 있게)" # R41 알림
adb shell "find '${DEV_FILES}/import' -user shell -exec chmod a+rwX {} +" # shell 소유 항목만 권한 열기(import 폴더 자체도 shell 이 만들었을 수 있음)

DEV_CNT="$(adb shell "find '${DEST}' -type f | wc -l" | tr -d '\r ')" # 기기 파일 수
echo "기기의 파일 개수: ${DEV_CNT}" # 결과
LOCKED="$(adb shell "find '${DEV_FILES}/import' -user shell \\( -type d ! -perm -0007 -o -type f ! -perm -0006 \\)" | tr -d '\r')" # 아직 앱이 못 쓰는 shell 소유 항목
[[ -z "${LOCKED}" ]] || die 1 "권한을 열지 못한 항목이 남았습니다(앱이 못 읽음):
${LOCKED}" # 남음
echo "권한 확인: shell 소유 항목이 모두 앱에서 읽기 가능합니다" # 확인
echo "완료. 다음 단계: 앱 '모델' 탭 → '${MODEL_ID}' 카드의 '가져오기' → '가져오기 실행' (설치·SHA-256 검증 후 '설치됨'이 되면 끝)" # 다음 단계
echo "가져온 뒤에도 ${DEST} 의 파일은 남습니다. 공간이 필요하면: adb shell rm -rf '${DEST}'" # 정리 안내(실행하지 않음)
