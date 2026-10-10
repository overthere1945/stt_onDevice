# 파일명: gen_ten.py
# 목적: TEN VAD 골든 데이터 생성(Kotlin TenVad 후처리 검증 기준)
# 기능: 공식 ten-vad Python 패키지(GitHub TEN-framework/ten-vad, libten_vad.so ctypes)로 hop 256 프레임마다
#       (확률, flag)를 얻고 두 프리셋 결과를 쓴다.
#         - TEN_DEFAULT: 라이브러리 flag 의 연속 구간 병합. hangover_ref.hangover_frames(prob, 0.5, 0, 0) 와 같은지 assert.
#         - TEN_SHERPA : silero-vad 6.2.3 get_speech_timestamps_from_probs(확률 입력 진입점)를 window 256 으로 적용.
#           라이브러리는 16 kHz 에서 window 512 를 고정하므로, sampling_rate=8000(window 256) 으로 호출하면서
#           ms 단위 파라미터를 모두 2배로 넘겨 모든 샘플 수가 16 kHz 기준 값과 정확히 같아지게 한다(README 에 증명).
# change(add)-hyungchul-2026-10-10-12:19

import os  # 경로
import sys  # 모듈 경로 추가
from importlib.metadata import version  # 패키지 버전

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 공통 모듈을 찾게 함

import ctypes  # 공유 라이브러리 선로딩
import ctypes.util  # 시스템 라이브러리 검색

from silero_vad import get_speech_timestamps_from_probs  # silero 후처리(확률 입력)
from ten_vad import TenVad  # 공식 TEN VAD Python 래퍼

import golden_common as gc  # 공통 도우미
import hangover_ref as hr  # hangover 기준 구현

TOOL_BASE = f"ten-vad {version('ten_vad')} (git d68ab71, libten_vad.so Linux x64)"  # 도구 표기
# libten_vad.so 는 libc++.so.1/libc++abi.so.1 에 의존한다. 시스템에 없으면(apt libc++1 미설치) 프로젝트가 쓰는
# NDK 의 호스트용 libc++ 폴더를 LD_LIBRARY_PATH 에 넣고 스크립트를 다시 실행한다
# (NDK 라이브러리의 SONAME 이 "libc++.so" 라서 선로딩만으로는 "libc++.so.1" 의존성이 해결되지 않는다).
NDK_HOST_LIB = os.path.join(  # NDK 호스트 libc++ 폴더(ANDROID_NDK_HOME 우선)
    os.environ.get("ANDROID_NDK_HOME", os.path.expanduser("~/Android/Sdk/ndk/30.0.16248370")),  # NDK 경로
    "toolchains", "llvm", "prebuilt", "linux-x86_64", "lib", "x86_64-unknown-linux-gnu",  # 호스트 x86_64 glibc 라이브러리
)


def preload_libcxx():
    """
    목적: TEN 네이티브 라이브러리 의존성(libc++) 준비
    기능: 시스템에서 libc++ 를 찾지 못하고 LD_LIBRARY_PATH 에 NDK 호스트 libc++ 폴더가 없으면,
          그 폴더를 LD_LIBRARY_PATH 앞에 넣어 같은 인자로 현재 스크립트를 다시 실행(execve)한다.
    입력: 없음
    출력: 없음(필요 시 프로세스 교체)
    리턴: 사용한 출처 문자열("system" 또는 NDK 경로)
    """
    paths = os.environ.get("LD_LIBRARY_PATH", "")  # 현재 검색 경로
    if NDK_HOST_LIB in paths.split(os.pathsep):  # 이미 들어 있으면(재실행된 프로세스)
        return NDK_HOST_LIB  # 사용 경로 반환
    if ctypes.util.find_library("c++"):  # 시스템 libc++ 존재
        return "system"  # 그대로 사용
    assert os.path.exists(os.path.join(NDK_HOST_LIB, "libc++.so.1")), f"libc++ 없음: {NDK_HOST_LIB}"  # 존재 확인
    env = dict(os.environ, LD_LIBRARY_PATH=os.pathsep.join([NDK_HOST_LIB] + ([paths] if paths else [])))  # 경로 추가
    os.execve(sys.executable, [sys.executable, "-I", os.path.abspath(__file__)] + sys.argv[1:], env)  # 재실행


def ten_frames(pcm, hop, threshold):
    """
    목적: TEN VAD 프레임 확률·flag 계산
    기능: 새 TenVad(hop, threshold) 로 floor(N/hop) 개의 int16 프레임을 순서대로 처리한다(상태 유지).
    입력: pcm(int16 ndarray), hop(샘플), threshold
    출력: 없음
    리턴: (확률 리스트, flag 리스트)
    """
    vad = TenVad(hop_size=hop, threshold=threshold)  # 새 인스턴스
    probs, flags = [], []  # 결과
    for i in range(len(pcm) // hop):  # 완전한 프레임마다
        p, f = vad.process(pcm[i * hop:(i + 1) * hop].copy())  # 연속 메모리 int16 프레임 처리
        probs.append(p)  # 확률
        flags.append(int(f))  # flag(0/1)
    return probs, flags  # 반환


def sherpa_style(probs, n_samples, th, min_sp, min_sil, pad, max_sp):
    """
    목적: window 256 확률에 silero get_speech_timestamps 후처리 적용
    기능: sampling_rate=8000(→ window 256)으로 호출하고 ms·초 파라미터를 2배로 넘겨
          min_speech/pad/max_speech/min_silence/min_silence_at_max_speech 샘플 수가 16 kHz 값과 같게 만든다.
    입력: probs(확률), n_samples(16 kHz 전체 샘플 수), th, min_sp(ms), min_sil(ms), pad(ms), max_sp(초)
    출력: 없음
    리턴: [{"start": 샘플, "end": 샘플}] (16 kHz 샘플 단위)
    """
    return get_speech_timestamps_from_probs(  # silero 라이브러리 후처리
        probs, sampling_rate=8000, threshold=th,  # 8000 → window_size_samples 256
        min_speech_duration_ms=2 * min_sp, max_speech_duration_s=2 * max_sp,  # 8000×2ms/1000 = 16000×ms/1000
        min_silence_duration_ms=2 * min_sil, speech_pad_ms=2 * pad,  # 같은 방식
        min_silence_at_max_speech=2 * 98,  # 기본 98 ms 도 2배
        return_seconds=False, audio_length_samples=n_samples,  # 16 kHz 샘플 단위
    )


def main():
    """
    목적: TEN 골든 생성 진입점
    기능: 클립마다 TEN 확률·flag 를 계산해 TEN_DEFAULT/TEN_SHERPA 골든을 쓴다.
    입력: 없음
    출력: golden/TEN_*.json
    리턴: 없음
    """
    print("libc++:", preload_libcxx())  # libten_vad.so 의존성 준비
    hop = gc.TEN_HOP  # 256 샘플
    hop_sec = hop / gc.SAMPLE_RATE  # 0.016 s
    for clip in gc.CLIPS:  # 클립마다
        _, pcm = gc.load_clip(clip)  # int16 파형
        th_d, sp_d, sil_d = gc.TEN_DEFAULT  # TEN_DEFAULT 파라미터
        probs, flags = ten_frames(pcm, hop, th_d)  # 확률·flag
        mism = [i for i, (p, f) in enumerate(zip(probs, flags)) if (p >= th_d) != bool(f)]  # flag 와 prob>=th 불일치
        print(clip, "flag≠(prob>=0.5) 프레임 수:", len(mism), mism[:10])  # 확인 로그
        default_frames = hr.merge_contiguous_frames(flags)  # 라이브러리 flag 병합(원본 정의)
        hang = hr.hangover_frames(probs, hop_sec, th_d, sp_d, sil_d)  # SimpleHangover(0.5, 0, 0)
        assert hang == default_frames, f"TEN_DEFAULT: flag 병합 ≠ hangover(prob, 0.5, 0, 0) ({clip})"  # 일치 확인
        gc.write_golden(  # TEN_DEFAULT 저장
            "TEN", "TEN_DEFAULT", clip, hop_sec, probs, hr.frames_to_sec(default_frames, hop_sec),  # 기본 필드
            f"{TOOL_BASE}; flag 연속 병합",  # 도구
            extra={"expectedFrames": default_frames, "frameFlags": flags,  # 프레임 구간, 원본 flag
                   "totalSamples": len(pcm),  # change(add)-hyungchul-2026-10-10-12:52 전체 샘플 수
                   "params": {"threshold": th_d, "speechMs": sp_d, "silenceMs": sil_d, "hopSamples": hop}},  # 파라미터
        )
        th, min_sp, min_sil, pad, max_sp = gc.TEN_SHERPA  # TEN_SHERPA 파라미터
        ts = sherpa_style(probs, len(pcm), th, min_sp, min_sil, pad, max_sp)  # silero 후처리
        samples = [[int(s["start"]), int(s["end"])] for s in ts]  # 샘플 구간
        gc.write_golden(  # TEN_SHERPA 저장
            "TEN", "TEN_SHERPA", clip, hop_sec, probs,  # 기본 필드
            [(s / gc.SAMPLE_RATE, e / gc.SAMPLE_RATE) for s, e in samples],  # 초 구간
            f"{TOOL_BASE}; silero-vad {version('silero-vad')} get_speech_timestamps_from_probs(window 256)",  # 도구
            extra={"expectedSamples": samples, "totalSamples": len(pcm), "windowSamples": hop,  # 샘플 정답·길이·창
                   "params": {"threshold": th, "minSpeechMs": min_sp, "minSilenceMs": min_sil,  # 파라미터
                              "padMs": pad, "maxSpeechSec": gc.json_max(max_sp)}},  # 파라미터
        )
        print(clip, "TEN_DEFAULT", len(default_frames), "TEN_SHERPA", samples[:4])  # 진행 로그


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
