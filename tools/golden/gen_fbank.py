# 파일명: gen_fbank.py
# 목적: FSMN 전처리(fbank80, LFR(5,1)+CMVN) 골든 데이터 생성(Kotlin Fbank·FSMN 특징 포팅 검증 기준)
# 기능: kaldi-native-fbank 를 FunASR funasr_onnx.WavFrontend 와 같은 옵션(dither 0, hamming, 25/10 ms, 80 mel,
#       snip_edges True, energy_floor 0, 입력 ×32768)으로 직접 실행해 앞 50 프레임을 fbank_<clip>.json 에,
#       WavFrontend.lfr_cmvn(LFR m5 n1 → am.mvn CMVN) 앞 10 프레임을 lfr_cmvn_<clip>.json 에 쓴다.
#       직접 실행한 fbank 가 WavFrontend.fbank 와 완전히 같은지, numpy 독립 LFR+CMVN 이 WavFrontend 와 같은지 assert 한다.
#       FSMN am.mvn 을 테스트 리소스 golden/fsmn_am.mvn 으로 복사한다.
# change(add)-hyungchul-2026-10-10-12:19

import os  # 경로
import shutil  # 파일 복사
import sys  # 모듈 경로 추가
from importlib.metadata import version  # 패키지 버전

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 golden_common 을 찾게 함

import kaldi_native_fbank as knf  # Kaldi 호환 fbank
import numpy as np  # 수치 배열
import yaml  # config.yaml 읽기
from funasr_onnx.utils.frontend import WavFrontend, load_cmvn  # FunASR 원본 전처리

import golden_common as gc  # 공통 도우미

FSMN_DIR = os.path.join(gc.MODELS_DIR, "fsmn_onnx")  # FSMN 원본 폴더
AM_MVN = os.path.join(FSMN_DIR, "am.mvn")  # CMVN 파일
FBANK_FRAMES = 50  # 저장할 fbank 프레임 수
LFR_FRAMES = 10  # 저장할 LFR+CMVN 프레임 수
TOOL = f"kaldi-native-fbank {version('kaldi-native-fbank')}; funasr_onnx {version('funasr_onnx')} WavFrontend"  # 도구 표기


def make_opts():
    """
    목적: WavFrontend 와 같은 fbank 옵션 생성
    기능: funasr_onnx/utils/frontend.py WavFrontend.__init__ 이 설정하는 값과 FSMN config.yaml frontend_conf 값을 그대로 넣는다.
    입력: 없음
    출력: 없음
    리턴: knf.FbankOptions
    """
    opts = knf.FbankOptions()  # 기본 옵션(그 밖의 값은 kaldi 기본값)
    opts.frame_opts.samp_freq = 16000  # fs
    opts.frame_opts.dither = 0.0  # config dither 0.0
    opts.frame_opts.window_type = "hamming"  # 해밍 창
    opts.frame_opts.frame_shift_ms = 10.0  # 10 ms
    opts.frame_opts.frame_length_ms = 25.0  # 25 ms
    opts.mel_opts.num_bins = 80  # 80 mel
    opts.energy_floor = 0  # WavFrontend 설정
    opts.frame_opts.snip_edges = True  # WavFrontend 설정
    opts.mel_opts.debug_mel = False  # WavFrontend 설정
    return opts  # 반환


def knf_fbank(audio, opts):
    """
    목적: kaldi-native-fbank 직접 실행
    기능: 입력을 ×32768 해 OnlineFbank 에 넣고 준비된 모든 프레임을 float32 행렬로 모은다.
    입력: audio(float32 -1~1), opts(FbankOptions)
    출력: 없음
    리턴: (T, 80) float32 ndarray
    """
    fb = knf.OnlineFbank(opts)  # fbank 계산기
    fb.accept_waveform(16000, (audio * 32768.0).tolist())  # int16 스케일 입력
    return np.array([fb.get_frame(i) for i in range(fb.num_frames_ready)], dtype=np.float32)  # 전체 프레임


def numpy_lfr_cmvn(feat, cmvn, m=5, n=1):
    """
    목적: LFR+CMVN 독립 구현(Kotlin 포팅 기준)
    기능: 앞에 (m-1)//2 개 첫 프레임 복제, i 번째 출력 = 프레임 i..i+m-1 연결(끝은 마지막 프레임 복제),
          이어서 (x + mean) * var (am.mvn 의 AddShift, Rescale 값) 를 적용한다.
    입력: feat((T, 80) float32), cmvn((2, 400) float64), m, n
    출력: 없음
    리턴: (T, 400) float32 ndarray
    """
    t = feat.shape[0]  # 원래 프레임 수
    left = (m - 1) // 2  # 왼쪽 패딩 수(2)
    padded = np.vstack([np.tile(feat[0], (left, 1)), feat])  # 첫 프레임 복제 패딩
    total = t + left  # 패딩 후 길이
    out = []  # 결과
    for i in range(int(np.ceil(t / n))):  # 출력 프레임마다
        if m <= total - i * n:  # 충분한 프레임이 있으면
            out.append(padded[i * n:i * n + m].reshape(-1))  # 연결
        else:  # 끝부분
            rows = list(padded[i * n:])  # 남은 프레임
            rows += [padded[-1]] * (m - len(rows))  # 마지막 프레임 복제
            out.append(np.concatenate(rows))  # 연결
    lfr = np.vstack(out).astype(np.float32)  # float32 (WavFrontend.apply_lfr 와 동일)
    return ((lfr + cmvn[0:1, :]) * cmvn[1:2, :]).astype(np.float32)  # CMVN(float64 연산) 후 float32


def main():
    """
    목적: fbank/LFR 골든 생성 진입점
    기능: 클립마다 fbank 50 프레임, LFR+CMVN 10 프레임을 저장하고 am.mvn 을 테스트 리소스로 복사한다.
    입력: 없음
    출력: golden/fbank_*.json, golden/lfr_cmvn_*.json, golden/fsmn_am.mvn
    리턴: 없음
    """
    with open(os.path.join(FSMN_DIR, "config.yaml"), encoding="utf-8") as f:  # FSMN config
        frontend_conf = yaml.safe_load(f)["frontend_conf"]  # 전처리 설정
    front = WavFrontend(cmvn_file=AM_MVN, **frontend_conf)  # FunASR 원본 전처리(Fsmn_vad 와 같은 생성 방식)
    opts = make_opts()  # 직접 실행용 옵션
    cmvn = load_cmvn(AM_MVN)  # (2, 400) mean/var
    for clip in gc.CLIPS:  # 클립마다
        audio, _ = gc.load_clip(clip)  # float32 파형
        mine = knf_fbank(audio, opts)  # 직접 실행 fbank
        ref, _ = front.fbank(audio)  # WavFrontend fbank
        assert mine.shape == ref.shape and np.array_equal(mine, ref), "직접 실행 fbank ≠ WavFrontend.fbank"  # 완전 일치
        lfr, _ = front.lfr_cmvn(ref)  # WavFrontend LFR+CMVN(float64 결과)
        lfr32 = lfr.astype(np.float32)  # Fsmn_vad 가 ONNX 입력 전에 float32 로 바꾸는 것과 동일
        mine_lfr = numpy_lfr_cmvn(ref, cmvn)  # 독립 구현
        assert np.array_equal(mine_lfr, lfr32), "독립 LFR+CMVN ≠ WavFrontend"  # 완전 일치
        common = {"numFramesTotal": int(ref.shape[0]), "inputScale": 32768,  # 전체 프레임 수, 입력 배율
                  "knfOptions": str(opts)}  # 모든 kaldi 옵션 문자열
        gc.write_feature("fbank", clip, ref[:FBANK_FRAMES], TOOL, extra=common)  # fbank 앞 50 프레임
        gc.write_feature("lfr_cmvn", clip, lfr32[:LFR_FRAMES], TOOL,  # LFR+CMVN 앞 10 프레임
                         extra=dict(common, lfrM=5, lfrN=1, cmvnFile="fsmn_am.mvn"))  # LFR/CMVN 설정
        print(clip, ref.shape, lfr32.shape)  # 진행 로그
    shutil.copy(AM_MVN, os.path.join(gc.GOLDEN_DIR, "fsmn_am.mvn"))  # am.mvn 테스트 리소스 복사
    print(opts)  # 옵션 출력(README 기록용)


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
