#!/usr/bin/env bash
# 파일명: tools/check_16kb.sh
# 목적: APK 안 모든 arm64-v8a 네이티브 라이브러리가 16 KB 페이지 기기에서 올라갈 수 있게 정렬됐는지 검사
# 기능: APK 의 lib/arm64-v8a/*.so 를 하나씩 새 임시 폴더에 풀어 NDK llvm-readelf -l 로 LOAD 세그먼트의 Align 값을 읽고,
#       모든 LOAD 의 Align 이 0x4000(16384) 이상인지 확인한다. 하나라도 미달이거나 .so 가 하나도 없으면 exit 1.
#       (APK 안 .so 의 zip 내 오프셋 정렬은 zipalign/AGP 가 담당하며 이 스크립트는 ELF 세그먼트 정렬만 본다.)
# 사용법: tools/check_16kb.sh <apk 경로>
#         NDK 경로는 환경변수 ANDROID_NDK_HOME 이 있으면 그것을, 없으면 ~/Android/Sdk/ndk/30.0.16248370 을 쓴다.
# change(add)-hyungchul-2026-10-10-15:26

set -euo pipefail # 오류·미정의 변수·파이프 실패 시 즉시 중단

APK="${1:-}" # 첫 인자: APK 경로
if [[ -z "${APK}" || ! -f "${APK}" ]]; then # 인자 없음 또는 파일 없음
  echo "사용법: $0 <apk 경로> (파일 없음: '${APK}')" >&2 # 사용법 출력
  exit 2 # 인자 오류
fi

NDK="${ANDROID_NDK_HOME:-${HOME}/Android/Sdk/ndk/30.0.16248370}" # 프로젝트 고정 NDK
READELF="${NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" # NDK llvm-readelf
if [[ ! -x "${READELF}" ]]; then # 도구 없음
  echo "llvm-readelf 없음: ${READELF}" >&2 # 오류 출력
  exit 2 # 환경 오류
fi
MIN_ALIGN=16384 # 요구 정렬 0x4000

TMP="$(mktemp -d)" # .so 를 풀 새 빈 임시 폴더
trap 'rm -rf "${TMP}"' EXIT # 종료 시 정리

mapfile -t LIBS < <(unzip -Z1 "${APK}" | grep -E '^lib/arm64-v8a/[^/]+\.so$' || true) # APK 안 arm64 .so 목록
if [[ ${#LIBS[@]} -eq 0 ]]; then # .so 없음
  echo "FAIL: ${APK} 에 lib/arm64-v8a/*.so 가 없음" >&2 # 검사 대상 없음도 실패
  exit 1 # 실패
fi

fail=0 # 실패 개수
for entry in "${LIBS[@]}"; do # .so 마다
  name="$(basename "${entry}")" # 파일 이름
  unzip -p "${APK}" "${entry}" > "${TMP}/${name}" # 임시 폴더로 추출
  # LOAD 행의 마지막 칸이 Align(16진수) — 모든 LOAD 의 Align 을 모은다
  mapfile -t aligns < <("${READELF}" -lW "${TMP}/${name}" | awk '$1 == "LOAD" { print $NF }') # LOAD Align 목록
  if [[ ${#aligns[@]} -eq 0 ]]; then # LOAD 세그먼트를 못 읽음
    echo "FAIL ${entry}: LOAD 세그먼트 없음" # 실패 표시
    fail=$((fail + 1)) # 실패 증가
    continue # 다음 .so
  fi
  min="" # 이 .so 의 최소 Align
  for a in "${aligns[@]}"; do # LOAD 마다
    v=$((a)) # 0x 16진수 → 10진수
    if [[ -z "${min}" || ${v} -lt ${min} ]]; then min=${v}; fi # 최소값 갱신
  done
  if [[ ${min} -ge ${MIN_ALIGN} ]]; then # 모든 LOAD 가 16 KB 이상
    printf 'OK   %s  (LOAD %d개, 최소 Align 0x%x)\n' "${entry}" "${#aligns[@]}" "${min}" # 통과 표시
  else # 미달
    printf 'FAIL %s  (최소 Align 0x%x < 0x%x)\n' "${entry}" "${min}" "${MIN_ALIGN}" # 실패 표시
    fail=$((fail + 1)) # 실패 증가
  fi
done

if [[ ${fail} -ne 0 ]]; then # 하나라도 실패
  echo "결과: ${fail}/${#LIBS[@]} 개 .so 가 16 KB 정렬 미달" >&2 # 요약
  exit 1 # 실패
fi
echo "결과: ${#LIBS[@]}/${#LIBS[@]} 개 .so 모두 16 KB 정렬" # 전체 통과
