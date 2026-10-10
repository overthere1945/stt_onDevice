#!/usr/bin/env bash
# 파일명: tools/fetch_native_deps.sh
# 목적: 저장소에 넣지 않는(gitignore) 외부 네이티브 의존성을 정해진 버전으로 내려받아 SHA-256 을 검사하고 빌드 경로에 놓는다.
# 기능: 1) libfvad git 서브모듈 초기화(커밋은 상위 저장소가 고정)
#          change(add)-hyungchul-2026-10-10-20:35 + whisper.cpp git 서브모듈(태그 v1.9.5) 초기화
#       2) TEN VAD(TEN-framework/ten-vad, 커밋 고정)의 Android arm64-v8a 공식 빌드 libten_vad.so, 헤더 ten_vad.h, LICENSE
#          → app/src/main/cpp/third_party/ten-vad/{lib/arm64-v8a, include, .}
#       3) sherpa-onnx v1.13.8 sherpa-onnx-static-link-onnxruntime-1.13.8.aar → app/libs/ (Task 21 에서 사용, 지금은 의존성에 넣지 않음)
#       - 이미 있고 해시가 맞으면 다시 받지 않는다. 받은 파일은 매번 새 빈 임시 폴더에 두고 해시가 맞을 때만 목적지로 옮긴다.
#       - 해시가 다르면 아무것도 덮어쓰지 않고 exit 1(다른 버전으로 조용히 대체하지 않음).
# 사용법: tools/fetch_native_deps.sh   (저장소 어디에서 실행해도 됨, curl·sha256sum·git 필요)
# change(add)-hyungchul-2026-10-10-15:26

set -euo pipefail # 오류·미정의 변수·파이프 실패 시 즉시 중단

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)" # 저장소 루트(이 스크립트의 상위 폴더)

# ---- 고정 버전과 해시 (2026-10-10 에 내려받아 확인한 값) ----
TEN_COMMIT="d68ab71c8d5fbd8f98e97ae7c9986af76b5d72b1" # ten-vad 커밋(골든 도구 gen_ten.py 가 쓴 Python 패키지 1.0.6.9 와 같은 커밋)
TEN_RAW="https://raw.githubusercontent.com/TEN-framework/ten-vad/${TEN_COMMIT}" # 커밋 고정 원본 파일 URL 접두어
TEN_SO_SHA="6bc63b352ff5d0752b7c90ca7eb0b23189d5e9cbea9979f82fac962d2ac3c5a5" # lib/Android/arm64-v8a/libten_vad.so
TEN_H_SHA="21409e22b80a104d1eeeb11527812baeaf96f9a59819e2e2fb3b3d462c609f7f" # include/ten_vad.h
TEN_LICENSE_SHA="03d862b922867b8c3406a0c17791e256ed3d76f197d88c1996c4aebfb79f688e" # LICENSE(Apache-2.0 + 추가 조건)
SHERPA_VERSION="1.13.8" # sherpa-onnx 릴리스 버전
SHERPA_AAR="sherpa-onnx-static-link-onnxruntime-${SHERPA_VERSION}.aar" # AAR 파일명(onnxruntime 정적 링크판)
SHERPA_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${SHERPA_AAR}" # 릴리스 다운로드 URL
SHERPA_SHA="b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471" # AAR SHA-256

TEN_DIR="${ROOT}/app/src/main/cpp/third_party/ten-vad" # TEN 배치 폴더(gitignore)
LIBS_DIR="${ROOT}/app/libs" # AAR 배치 폴더(*.aar 는 gitignore)

# 목적: 파일의 SHA-256 계산
# 기능: sha256sum 출력의 첫 칸(해시)만 돌려준다.
# 입력: $1 = 파일 경로
# 출력: 표준출력에 64자 16진 해시
# 리턴: sha256sum 종료 코드
sha_of() {
  sha256sum "$1" | cut -d' ' -f1 # 해시 칸만 출력
}

# 목적: URL 을 받아 해시 검사 후 목적지에 배치
# 기능: 목적지에 이미 같은 해시의 파일이 있으면 건너뛴다. 아니면 새 빈 임시 폴더에 받아 해시를 비교하고,
#       맞으면 목적지로 옮기고, 다르면 임시 폴더를 지우고 실패한다(기존 파일 보존).
# 입력: $1 = URL, $2 = 목적지 파일 경로, $3 = 기대 SHA-256
# 출력: 진행 메시지(표준출력), 오류 메시지(표준오류)
# 리턴: 0 성공, 1 해시 불일치(set -e 로 스크립트 종료)
fetch() {
  local url="$1" dest="$2" want="$3" # 인자 이름 붙이기
  if [[ -f "${dest}" && "$(sha_of "${dest}")" == "${want}" ]]; then # 이미 올바른 파일이 있으면
    echo "OK(기존)  ${dest#"${ROOT}/"}" # 건너뜀 표시
    return 0 # 다운로드 생략
  fi
  local tmp # 임시 폴더 변수
  tmp="$(mktemp -d)" # 다운로드 전용 새 빈 폴더
  curl -sSfL --retry 3 -o "${tmp}/file" "${url}" # 내려받기(HTTP 오류면 실패)
  local got # 실제 해시 변수
  got="$(sha_of "${tmp}/file")" # 받은 파일 해시
  if [[ "${got}" != "${want}" ]]; then # 해시 불일치
    rm -rf "${tmp}" # 받은 파일 폐기
    echo "SHA-256 불일치: ${url}" >&2 # 오류 출력
    echo "  기대 ${want}" >&2 # 기대값
    echo "  실제 ${got}" >&2 # 실제값
    return 1 # 실패
  fi
  mkdir -p "$(dirname "${dest}")" # 목적지 폴더 생성
  mv -f "${tmp}/file" "${dest}" # 검사 통과 파일 배치
  rm -rf "${tmp}" # 임시 폴더 정리
  echo "OK(받음)  ${dest#"${ROOT}/"}  ${want}" # 결과 표시
}

# 1) libfvad 서브모듈(상위 저장소가 고정한 커밋으로 체크아웃)
git -C "${ROOT}" submodule update --init app/src/main/cpp/third_party/libfvad # 서브모듈 초기화
echo "OK(서브모듈) libfvad $(git -C "${ROOT}/app/src/main/cpp/third_party/libfvad" rev-parse HEAD)" # 체크아웃된 커밋 표시
git -C "${ROOT}" submodule update --init app/src/main/cpp/third_party/whisper.cpp # change(add)-hyungchul-2026-10-10-20:35 whisper.cpp 서브모듈(태그 v1.9.5 커밋) 초기화
echo "OK(서브모듈) whisper.cpp $(git -C "${ROOT}/app/src/main/cpp/third_party/whisper.cpp" rev-parse HEAD)" # change(add)-hyungchul-2026-10-10-20:35 체크아웃된 커밋 표시

# 2) TEN VAD 공식 Android 빌드와 헤더·라이선스
fetch "${TEN_RAW}/lib/Android/arm64-v8a/libten_vad.so" "${TEN_DIR}/lib/arm64-v8a/libten_vad.so" "${TEN_SO_SHA}" # arm64 공유 라이브러리
fetch "${TEN_RAW}/include/ten_vad.h" "${TEN_DIR}/include/ten_vad.h" "${TEN_H_SHA}" # C 헤더
fetch "${TEN_RAW}/LICENSE" "${TEN_DIR}/LICENSE" "${TEN_LICENSE_SHA}" # 라이선스 원문

# 3) sherpa-onnx AAR(Task 21 용, 의존성 추가는 그때)
fetch "${SHERPA_URL}" "${LIBS_DIR}/${SHERPA_AAR}" "${SHERPA_SHA}" # 정적 링크 onnxruntime 판 AAR

echo "완료: 네이티브 의존성 준비됨" # 전체 성공
