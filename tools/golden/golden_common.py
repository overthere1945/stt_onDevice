# 파일명: golden_common.py
# 목적: 골든 데이터 생성 스크립트(gen_*.py, make_clips.py)가 함께 쓰는 공통 도우미
# 기능: 경로 상수, 프리셋 값(앱 VadPresets.kt 와 동일), 클립 로딩, float32 정확 표기 변환,
#       골든 JSON 공통 형식 작성·저장을 제공한다.
# change(add)-hyungchul-2026-10-10-12:19

import json  # JSON 저장
import math  # 무한대 판정
import os  # 경로 조작

import numpy as np  # 수치 배열
import soundfile as sf  # WAV 읽기

# ---- 경로 상수 ----
TOOLS_DIR = os.path.dirname(os.path.abspath(__file__))  # tools/golden 폴더
PROJECT_ROOT = os.path.dirname(os.path.dirname(TOOLS_DIR))  # 프로젝트 루트
GOLDEN_DIR = os.path.join(PROJECT_ROOT, "app", "src", "test", "resources", "golden")  # 골든 JSON 출력 폴더(커밋)
AUDIO_DIR = os.path.join(GOLDEN_DIR, "audio")  # 골든 클립 WAV 폴더(gitignore)
MODELS_DIR = os.path.join(TOOLS_DIR, "models")  # 다운로드 모델 폴더(gitignore)

SAMPLE_RATE = 16000  # 모든 클립의 샘플레이트(Hz)

# 골든 클립 3종(파일명 = 클립 이름)
CLIPS = [
    "kr_customer_01_20s.wav",  # 한국어 고객상담 01 앞 20 s
    "en_customer_01_20s.wav",  # 영어 Customer_Service 01 앞 20 s
    "synth_silence_noise_8s.wav",  # 합성: 무음 2 s + 백색잡음 4 s + 무음 2 s
]

INF = float("inf")  # 최대 발화 길이 제한 없음 표기

# ---- 프리셋 값: app/src/main/java/com/cowork/stt_ondevice/vad/VadPresets.kt 와 동일해야 한다 ----
# Silero 계열: (threshold, minSpeechMs, minSilenceMs, padMs, maxSpeechSec)
SILERO_PRESETS = {
    "SILERO": (0.5, 250, 400, 150, INF),  # 프로젝트 기준선
    "SILERO_SAFE": (0.3, 150, 600, 300, INF),  # 프로젝트 ASR Safe
    "SILERO_FW": (0.5, 0, 2000, 400, INF),  # faster-whisper VadOptions
    "SILERO_DEFAULT": (0.5, 250, 100, 30, INF),  # silero 기본값
    "SILERO_SHERPA": (0.5, 250, 500, 0, 20.0),  # sherpa-onnx C++ 기본값
    "SILERO_QWEN3": (0.5, 1500, 500, 30, INF),  # Qwen3-ASR-Toolkit
    "SILERO_WS": (0.5, 0, 500, 100, INF),  # whisper_streaming
    "SILERO_USER": (0.4, 250, 300, 100, INF),  # 사용자 정의
}

# pyannote 계열: (onset, offset, minDurationOnSec, minDurationOffSec)
PYANNOTE_PRESETS = {
    "PYA3_DEFAULT": (0.5, 0.5, 0.0, 0.0),  # segmentation-3.0 VAD pipeline 기본
    "PYA2_DIHARD3": (0.767, 0.377, 0.136, 0.067),  # 2.x 카드 DIHARD3
    "PYA2_AMI": (0.684, 0.577, 0.181, 0.037),  # 2.x 카드 AMI
    "PYA2_VOXCONVERSE": (0.767, 0.713, 0.182, 0.501),  # 2.x 카드 VoxConverse
}

# FSMN 계열: config.yaml model_conf 에 덮어쓸 값(나머지는 FSMN_DEFAULT 와 동일)
FSMN_DEFAULT_CONF = {
    "max_end_silence_time": 800, "max_start_silence_time": 3000, "window_size_ms": 200,  # 무음/창
    "sil_to_speech_time_thres": 150, "speech_to_sil_time_thres": 150, "speech_2_noise_ratio": 1.0,  # 전이
    "do_extend": 1, "lookback_time_start_point": 200, "lookahead_time_end_point": 100,  # 확장
    "max_single_segment_time": 60000, "snr_thres": -100.0, "decibel_thres": -100.0,  # 최대 구간/SNR/dB
    "speech_noise_thres": 0.6, "fe_prior_thres": 1e-4, "speech_noise_thresh_low": -0.1,  # 잡음 임계
    "speech_noise_thresh_high": 0.3, "frame_in_ms": 10, "frame_length_ms": 25,  # 프레임
    "detect_mode": 1, "snr_mode": 0,  # 모드
}
FSMN_PRESETS = {
    "FSMN_DEFAULT": dict(FSMN_DEFAULT_CONF),  # funasr/fsmn-vad config.yaml 값
    "FSMN_ASR30": dict(FSMN_DEFAULT_CONF, max_single_segment_time=30000),  # 최대 구간 30 s
}

# WebRTC 계열: (mode, frameMs, style, paddingMs, ratio, speechMs, silenceMs)
WEBRTC_PRESETS = {
    "WEBRTC_LIB_DEFAULT": (0, 30, "RAW", 300, 0.9, 0, 0),  # libfvad 기본 mode, 후처리 없음
    "WEBRTC_PYEXAMPLE": (3, 30, "RING_BUFFER", 300, 0.9, 0, 0),  # py-webrtcvad example.py
    "WEBRTC_ANDROIDVAD": (3, 20, "HANGOVER", 300, 0.9, 50, 300),  # android-vad README
}

# TEN 계열
TEN_HOP = 256  # TEN hop(샘플)
TEN_DEFAULT = (0.5, 0, 0)  # (threshold, speechMs, silenceMs) → 연속 flag 병합
TEN_SHERPA = (0.5, 250, 500, 0, 20.0)  # (threshold, minSpeechMs, minSilenceMs, padMs, maxSpeechSec)


def f32(values):
    """
    목적: float32 값을 JSON 에 정확·간결하게 기록
    기능: 각 값을 numpy float32 로 바꾼 뒤 float32 를 정확히 복원하는 최단 10진 표기의 Python float 로 변환한다.
    입력: values(숫자 시퀀스)
    출력: 없음
    리턴: Python float 리스트(Kotlin 에서 toFloat() 하면 원래 float32 와 비트 단위로 같다)
    """
    return [float(str(np.float32(v))) for v in values]  # float32 최단 표기 → float


def load_clip(clip_name):
    """
    목적: 골든 클립 WAV 로딩
    기능: AUDIO_DIR 의 16 kHz mono PCM16 WAV 를 float32(-1~1, int16/32768) 와 int16 두 형태로 읽는다.
    입력: clip_name(클립 파일명)
    출력: 없음
    리턴: (float32 ndarray, int16 ndarray)
    """
    path = os.path.join(AUDIO_DIR, clip_name)  # 클립 경로
    pcm, sr = sf.read(path, dtype="int16")  # int16 원본 그대로 읽기
    assert sr == SAMPLE_RATE, f"샘플레이트 불일치: {sr}"  # 16 kHz 확인
    assert pcm.ndim == 1, "mono 가 아님"  # mono 확인
    audio = pcm.astype(np.float32) / 32768.0  # silero/soundfile 과 같은 float 정규화
    return audio, pcm  # 두 형태 반환


def clip_stem(clip_name):
    """
    목적: 클립 파일명에서 확장자 제거
    기능: "xxx.wav" → "xxx"
    입력: clip_name(클립 파일명)
    출력: 없음
    리턴: 확장자 없는 이름
    """
    return os.path.splitext(clip_name)[0]  # 확장자 제거


def segments_json(pairs):
    """
    목적: 구간 목록을 공통 형식으로 변환
    기능: (start, end) 초 단위 쌍 목록을 [{"start":..,"end":..}] 로 바꾼다.
    입력: pairs((start, end) 시퀀스)
    출력: 없음
    리턴: dict 리스트
    """
    return [{"start": float(s), "end": float(e)} for s, e in pairs]  # 공통 구간 형식


def write_golden(family, preset_id, clip_name, hop_sec, frame_scores, expected_pairs, tool,
                 frame_times_sec=None, frame_db=None, extra=None):
    """
    목적: 골든 JSON 1개 저장
    기능: 공통 형식(clip/family/presetId/hopSec/frameTimesSec/frameScores/frameDb/expected/tool)에
          추가 키(extra)를 더해 golden/<family>_<presetId>_<clipStem>.json 으로 쓴다.
    입력: family, preset_id, clip_name, hop_sec, frame_scores(float32 시퀀스), expected_pairs((s,e) 초),
          tool(생성 도구 문자열), frame_times_sec(선택), frame_db(선택), extra(선택 dict)
    출력: JSON 파일
    리턴: 저장한 파일 경로
    """
    data = {  # 공통 키 순서 고정
        "clip": clip_name,  # 클립 파일명
        "family": family,  # VAD 계열
        "presetId": preset_id,  # 프리셋 id
        "hopSec": hop_sec,  # 프레임 간격(초)
        "frameTimesSec": None if frame_times_sec is None else [float(t) for t in frame_times_sec],  # 프레임 시각
        "frameScores": f32(frame_scores),  # 프레임 점수(float32 정확 표기)
        "frameDb": None if frame_db is None else [float(f"{d:.9g}") for d in frame_db],  # 프레임 dB
        "expected": segments_json(expected_pairs),  # 기대 구간(초)
        "tool": tool,  # 생성 도구/버전
    }
    if extra:  # 추가 키가 있으면
        data.update(extra)  # 덧붙임
    name = f"{family}_{preset_id}_{clip_stem(clip_name)}.json"  # 파일명 규칙
    return _dump(name, data)  # 저장


def write_scores(family, clip_name, hop_sec, frame_scores, tool, frame_times_sec=None, extra=None):
    """
    목적: 모델 추론 정합용 점수 JSON 저장
    기능: golden/scores_<family>_<clipStem>.json 에 frameScores(+선택 frameTimesSec) 를 쓴다.
          검증 스크립트가 모든 JSON 의 "expected" 키 존재를 확인하므로 "expected": null 을 넣는다.
    입력: family, clip_name, hop_sec, frame_scores, tool, frame_times_sec(선택), extra(선택)
    출력: JSON 파일
    리턴: 저장한 파일 경로
    """
    data = {  # 점수 전용 형식
        "clip": clip_name,  # 클립 파일명
        "family": family,  # VAD 계열
        "presetId": None,  # 프리셋 무관
        "hopSec": hop_sec,  # 프레임 간격
        "frameTimesSec": None if frame_times_sec is None else [float(t) for t in frame_times_sec],  # 프레임 시각
        "frameScores": f32(frame_scores),  # 모델 출력 점수
        "frameDb": None,  # 해당 없음
        "expected": None,  # 구간 없음(점수 전용)
        "tool": tool,  # 생성 도구
    }
    if extra:  # 추가 키
        data.update(extra)  # 덧붙임
    return _dump(f"scores_{family}_{clip_stem(clip_name)}.json", data)  # 저장


def write_feature(kind, clip_name, matrix, tool, extra=None):
    """
    목적: 특징(fbank, LFR+CMVN) 골든 JSON 저장
    기능: golden/<kind>_<clipStem>.json 에 "expected" = 프레임×차원 행렬(float32 정확 표기)로 쓴다.
    입력: kind("fbank" 또는 "lfr_cmvn"), clip_name, matrix(2차원 배열), tool, extra(선택)
    출력: JSON 파일
    리턴: 저장한 파일 경로
    """
    data = {  # 특징 형식
        "clip": clip_name,  # 클립 파일명
        "kind": kind,  # 특징 종류
        "numFrames": int(matrix.shape[0]),  # 저장 프레임 수
        "dim": int(matrix.shape[1]),  # 차원
        "expected": [f32(row) for row in matrix],  # 행렬
        "tool": tool,  # 생성 도구
    }
    if extra:  # 추가 키
        data.update(extra)  # 덧붙임
    return _dump(f"{kind}_{clip_stem(clip_name)}.json", data)  # 저장


def _dump(name, data):
    """
    목적: JSON 파일 쓰기
    기능: GOLDEN_DIR/name 에 UTF-8, 한 줄(공백 최소) 형식으로 저장한다. inf 는 JSON 에 넣지 않는다.
    입력: name(파일명), data(dict)
    출력: JSON 파일
    리턴: 파일 경로
    """
    os.makedirs(GOLDEN_DIR, exist_ok=True)  # 폴더 보장
    path = os.path.join(GOLDEN_DIR, name)  # 출력 경로
    with open(path, "w", encoding="utf-8") as f:  # 쓰기
        json.dump(data, f, ensure_ascii=False, separators=(",", ":"), allow_nan=False)  # 간결 저장(NaN/inf 금지)
        f.write("\n")  # 끝 줄바꿈
    return path  # 경로 반환


def json_max(value):
    """
    목적: 최대 발화 길이를 JSON 친화 값으로 변환
    기능: 무한대면 None, 아니면 float 를 돌려준다.
    입력: value(float)
    출력: 없음
    리턴: None 또는 float
    """
    return None if math.isinf(value) else float(value)  # inf → null
