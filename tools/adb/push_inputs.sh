#!/usr/bin/env bash
# 파일명: tools/adb/push_inputs.sh
# 목적: PC 의 입력 음원 폴더(input_sound 구조: kr/<시나리오>/NN.wav, en/…)를 기기 앱 폴더 input_sound/ 로 adb push 하고, 앱이 읽을 수 있게 권한을 정리한다.
# 기능: 1) adb·기기 연결 확인(0대면 중단, 2대 이상이면 경고 후 ANDROID_SERIAL 지정 요구), 앱 설치·앱 폴더 존재 확인
#       2) 로컬 폴더에 kr/ 또는 en/ 이 있는지 확인하고, 있는 언어 폴더만 adb push --sync(바뀐 파일만)로 /sdcard/Android/data/<패키지>/files/input_sound/ 아래로 보낸다
#       3) R41: adb shell 이 만든 폴더·파일은 drwxrws--- (소유자 shell)라 앱이 못 읽으므로, 소유자가 shell 인 항목만 골라 chmod a+rwX 로 연다
#          (앱 소유 폴더는 shell 이 chmod 할 수 없고 할 필요도 없다 — 'chmod -R' 은 그 폴더에서 Operation not permitted 로 실패함을 기기에서 확인)
#       4) 기기의 wav 개수를 세고, shell 소유 항목 중 남에게 읽기(폴더는 읽기·실행·쓰기) 권한이 없는 것이 남았는지 검사한다(남으면 실패).
#          run-as 로 세는 방법은 쓰지 않는다: run-as 프로세스는 셸의 ext_data_rw 그룹을 그대로 가져 앱이 못 읽는 폴더도 읽히므로 확인이 되지 않는다.
#       PC 쪽 파일은 읽기만 하고 지우거나 바꾸지 않는다. 기기 쪽도 지우지 않는다(같은 이름은 --sync 규칙대로 새 파일이 덮어씀).
# 사용법: tools/adb/push_inputs.sh <input_sound 폴더>     예) tools/adb/push_inputs.sh ~/workspace/stt/input_sound
# 환경변수(선택): ANDROID_SERIAL(기기가 여러 대일 때 대상 기기 시리얼)
# change(add)-hyungchul-2026-10-11-03:59

set -euo pipefail # 오류·미정의 변수·파이프 실패 시 즉시 중단

PKG="com.cowork.stt_ondevice" # 앱 패키지
DEV_FILES="/sdcard/Android/data/${PKG}/files" # 기기의 앱 폴더(getExternalFilesDir)
DEV_INPUT="${DEV_FILES}/input_sound" # 기기의 입력 음원 폴더

# 목적: 오류 출력 후 종료
# 기능: 메시지를 표준오류로 내고 지정 코드로 끝낸다.
# 입력: $1 = 종료 코드, $2 = 한국어 메시지
# 출력: 표준오류에 메시지
# 리턴: 돌아오지 않음(exit)
die() {
  echo "오류: $2" >&2 # 메시지
  exit "$1" # 종료
}

# 목적: adb 와 기기 연결 확인
# 기능: adb 명령이 있는지, device 상태 기기가 정확히 1대인지(또는 ANDROID_SERIAL 로 지정했는지) 확인한다. 여러 대면 경고하고 지정을 요구한다.
# 입력: 없음(ANDROID_SERIAL 환경변수 참고)
# 출력: 표준출력에 대상 기기, 문제 시 표준오류 + exit 2
# 리턴: 0
check_device() {
  command -v adb >/dev/null 2>&1 || die 2 "adb 명령을 찾을 수 없습니다(Android SDK platform-tools 를 PATH 에 추가하세요)" # adb 없음
  local devs # device 상태 기기 시리얼 목록
  devs="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')" # 연결된 기기
  local n # 기기 수
  n="$(printf '%s\n' "${devs}" | grep -c . || true)" # 빈 줄 제외 개수
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then # 시리얼 지정
    printf '%s\n' "${devs}" | grep -qx "${ANDROID_SERIAL}" || die 2 "ANDROID_SERIAL=${ANDROID_SERIAL} 기기가 연결돼 있지 않습니다(adb devices 확인)" # 지정 기기 없음
    echo "대상 기기: ${ANDROID_SERIAL}" # 알림
    return 0 # 확인 끝
  fi
  [[ "${n}" -ge 1 ]] || die 2 "연결된 기기가 없습니다(USB 연결과 'USB 디버깅 허용'을 확인하세요)" # 0대
  if [[ "${n}" -gt 1 ]]; then # 여러 대
    echo "경고: 기기가 ${n}대 연결돼 있습니다:" >&2 # 경고
    printf '  %s\n' ${devs} >&2 # 목록
    die 2 "ANDROID_SERIAL=<시리얼> 로 대상 기기를 지정해 다시 실행하세요" # 지정 요구
  fi
  echo "대상 기기: ${devs}" # 알림
}

# 목적: 앱 설치와 앱 폴더 확인
# 기능: 패키지가 설치돼 있는지(pm path), 앱이 한 번 실행돼 앱 폴더가 만들어졌는지 확인한다(앱 설치 전에 push 하면 앱이 그 폴더를 못 읽음).
# 입력: 없음
# 출력: 문제 시 표준오류 + exit 2
# 리턴: 0
check_app() {
  adb shell pm path "${PKG}" 2>/dev/null | grep -q '^package:' || die 2 "앱(${PKG})이 설치돼 있지 않습니다. 먼저 앱을 설치하세요(./gradlew :app:installDebug)" # 미설치
  [[ "$(adb shell "[ -d '${DEV_FILES}' ] && echo yes || echo no" | tr -d '\r')" == "yes" ]] || die 2 "앱 폴더가 없습니다(${DEV_FILES}). 앱을 한 번 실행한 뒤 다시 시도하세요" # 앱 폴더 없음
}

SRC="${1:-}" # 로컬 input_sound 폴더
[[ -n "${SRC}" ]] || die 2 "사용법: $0 <input_sound 폴더>   (그 아래에 kr/ 또는 en/ 이 있어야 합니다)" # 인자 없음
[[ -d "${SRC}" ]] || die 2 "폴더가 없습니다: ${SRC}" # 폴더 없음
LANGS=() # 보낼 언어 폴더
for L in kr en; do [[ -d "${SRC}/${L}" ]] && LANGS+=("${L}"); done # 있는 것만
[[ "${#LANGS[@]}" -gt 0 ]] || die 2 "'${SRC}' 아래에 kr/ 또는 en/ 폴더가 없습니다(데이터셋 구조: kr/<시나리오>/NN.wav)" # 언어 폴더 없음

check_device # 기기 확인
check_app # 앱 확인

adb shell "mkdir -p '${DEV_INPUT}'" # 입력 폴더 만들기(있으면 그대로)
LOCAL_TOTAL=0 # 로컬 wav 수
for L in "${LANGS[@]}"; do # 언어 폴더마다
  CNT="$(find "${SRC}/${L}" -type f -iname '*.wav' | wc -l | tr -d ' ')" # 로컬 wav 수
  LOCAL_TOTAL=$((LOCAL_TOTAL + CNT)) # 누적
  echo "===== ${L}: wav ${CNT}개 push (바뀐 파일만) =====" # 진행 알림
  adb push --sync "${SRC}/${L}" "${DEV_INPUT}/" # 폴더째 보내기 → input_sound/<lang>/…
done

echo "권한 정리: ${DEV_INPUT} 아래 shell 소유 항목에 chmod a+rwX (adb 가 만든 폴더를 앱이 읽고 쓸 수 있게)" # R41 알림
adb shell "find '${DEV_INPUT}' -user shell -exec chmod a+rwX {} +" # shell 소유 항목만 권한 열기

DEV_CNT="$(adb shell "find '${DEV_INPUT}' -type f -iname '*.wav' | wc -l" | tr -d '\r ')" # 기기 wav 수(셸 기준)
echo "기기의 wav 개수: ${DEV_CNT} (이번에 보낸 로컬 wav: ${LOCAL_TOTAL})" # 결과
LOCKED="$(adb shell "find '${DEV_INPUT}' -user shell \\( -type d ! -perm -0007 -o -type f ! -perm -0006 \\)" | tr -d '\r')" # 아직 앱이 못 쓰는 shell 소유 항목
[[ -z "${LOCKED}" ]] || die 1 "권한을 열지 못한 항목이 남았습니다(앱이 못 읽음):
${LOCKED}" # 남음
echo "권한 확인: shell 소유 항목이 모두 앱에서 읽기·쓰기 가능합니다" # 확인
echo "완료. 다음 단계: 앱 '실행' 탭 → 입력 파일 '새로고침'" # 다음 단계
