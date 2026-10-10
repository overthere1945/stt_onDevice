# 파일명: make_clips.py
# 목적: 골든 테스트용 음원 클립 4종 생성
# 기능: 사용자 음원(읽기 전용)의 앞 20 s+77 샘플을 KO/EN 각 1개, KO 앞 70 s+55 샘플 1개 잘라내고, 합성 무음/잡음 클립 1개를 만들어
#       app/src/test/resources/golden/audio/ 에 16 kHz mono PCM16 WAV 로 저장한다(이 폴더는 gitignore).
# change(add)-hyungchul-2026-10-10-12:19

import os  # 경로
import sys  # 모듈 경로 추가

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 golden_common 을 찾게 함

import numpy as np  # 수치 배열
import soundfile as sf  # WAV 입출력

import golden_common as gc  # 공통 상수

INPUT_ROOT = os.path.expanduser("~/workspace/stt/input_sound")  # 사용자 음원 루트(읽기 전용)

# (원본 상대 경로, 출력 클립 이름, 잘라낼 샘플 수) 목록
# change(add)-hyungchul-2026-10-10-12:52 수정 라운드 1: hop 배수가 아닌 길이(+77, +55 샘플)와 70 s 긴 클립
SOURCES = [
    ("kr/customer/01.wav", "kr_customer_01_20s_plus77.wav", 20 * 16000 + 77),  # 한국어 20 s + 77 샘플
    ("en/Customer_Service/01.wav", "en_customer_01_20s_plus77.wav", 20 * 16000 + 77),  # 영어 20 s + 77 샘플
    ("kr/customer/01.wav", "kr_customer_01_70s_plus55.wav", 70 * 16000 + 55),  # 한국어 70 s + 55 샘플
]
SYNTH_NAME = "synth_silence_noise_8s_plus123.wav"  # 합성 클립 이름
SYNTH_TAIL = 123  # 합성 클립 끝에 덧붙이는 무음 샘플 수


def cut_head(src_rel, dst_name, n):
    """
    목적: 원본 음원 앞부분 잘라 저장
    기능: 원본을 int16 그대로 읽어 앞 n 샘플을 PCM16 WAV 로 쓴다(재양자화 없음).
    입력: src_rel(INPUT_ROOT 기준 상대 경로), dst_name(출력 파일명), n(샘플 수)
    출력: AUDIO_DIR/dst_name WAV 파일
    리턴: 저장 샘플 수
    """
    src = os.path.join(INPUT_ROOT, src_rel)  # 원본 경로
    pcm, sr = sf.read(src, dtype="int16")  # int16 원본 읽기
    assert sr == gc.SAMPLE_RATE and pcm.ndim == 1, "16 kHz mono 가 아님"  # 형식 확인
    assert len(pcm) >= n, "원본이 요청 길이보다 짧음"  # 길이 확인
    sf.write(os.path.join(gc.AUDIO_DIR, dst_name), pcm[:n], gc.SAMPLE_RATE, subtype="PCM_16")  # 앞 n 샘플 저장
    return n  # 샘플 수 반환


def make_synth(dst_name):
    """
    목적: 합성 무음/잡음 클립 생성
    기능: 무음 2 s + 백색잡음 4 s + 무음 2 s + 무음 SYNTH_TAIL 샘플(총 128123 샘플). 잡음은 numpy default_rng(seed 0) 표준정규분포에
          RMS 10^(-30/20)(= -30 dBFS RMS, 풀스케일 1.0 기준)을 곱하고 ×32767 반올림 후 int16 로 자른다.
    입력: dst_name(출력 파일명)
    출력: AUDIO_DIR/dst_name WAV 파일
    리턴: 저장 샘플 수
    """
    sr = gc.SAMPLE_RATE  # 샘플레이트
    rng = np.random.default_rng(0)  # 재현 가능한 난수(seed 0)
    rms = 10.0 ** (-30.0 / 20.0)  # -30 dBFS RMS 진폭
    noise = rng.standard_normal(4 * sr) * rms  # 4 s 백색잡음(float)
    noise_i16 = np.clip(np.round(noise * 32767.0), -32768, 32767).astype(np.int16)  # int16 변환
    silence = np.zeros(2 * sr, dtype=np.int16)  # 2 s 디지털 무음
    tail = np.zeros(SYNTH_TAIL, dtype=np.int16)  # change(add)-hyungchul-2026-10-10-12:52 hop 배수가 아니게 하는 꼬리 무음
    pcm = np.concatenate([silence, noise_i16, silence, tail])  # 무음+잡음+무음+꼬리
    sf.write(os.path.join(gc.AUDIO_DIR, dst_name), pcm, sr, subtype="PCM_16")  # 저장
    return len(pcm)  # 샘플 수 반환


def main():
    """
    목적: 클립 4종 생성 진입점
    기능: 출력 폴더를 만들고 KO/EN 잘라내기와 합성 클립 생성을 수행한 뒤 결과를 출력한다.
    입력: 없음
    출력: WAV 3개, 콘솔 로그
    리턴: 없음
    """
    os.makedirs(gc.AUDIO_DIR, exist_ok=True)  # 출력 폴더 보장
    for src_rel, dst, n in SOURCES:  # 원본마다
        print(dst, cut_head(src_rel, dst, n))  # 잘라 저장하고 샘플 수 출력
    print(SYNTH_NAME, make_synth(SYNTH_NAME))  # 합성 클립 생성


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
