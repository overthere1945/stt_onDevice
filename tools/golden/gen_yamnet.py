# 파일명: gen_yamnet.py
# 목적: Yamnet(yamnet.tflite) Speech 점수 골든 생성 — 기기 LiteRT 추론 결과를 비교할 수치 기준
# 기능: PC 의 ai-edge-litert(LiteRT Python, 앱과 같은 2.3.0) Interpreter 로 같은 모델을 실행해
#       골든 클립 1개(kr_customer_01_20s_plus77)의 프레임별 Speech(index 0) 점수를 golden/scores_YAMNET_<clipStem>.json 으로 쓴다.
#       창 구성은 앱 YamnetVad.kt 와 같다(android-vad FRAME_SIZE_243 재현):
#         프레임 수 floor(N/243), 프레임 i 창 = int16[(i+1)·243 − 15600, (i+1)·243) / 32767 (float32), 음수 위치는 0.
#       또한 (1) 모델 SHA-256, (2) 모델 내장 라벨(yamnet_label_list.txt)과 yamnet_class_map.csv 의 521 개 이름 일치·index 0 = "Speech" 를 확인하고,
#       (3) android-vad 판정(최고점 클래스가 Speech 이고 점수 > threshold)과 본 앱 판정(Speech 점수 ≥ threshold)이 다른 프레임 수를 출력한다.
# 실행: tools/golden/.venv-yamnet/bin/python -I tools/golden/gen_yamnet.py   (다운로드 모델을 읽으므로 -I)
# change(add)-hyungchul-2026-10-10-15:43

import csv  # 클래스 표 읽기
import hashlib  # SHA-256
import os  # 경로
import sys  # 모듈 경로 추가
import zipfile  # tflite 안 내장 라벨(zip) 읽기
from importlib.metadata import version  # 패키지 버전

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 공통 모듈을 찾게 함

import numpy as np  # 수치 배열
from ai_edge_litert.interpreter import Interpreter  # LiteRT Python Interpreter

import golden_common as gc  # 공통 도우미

MODEL_DIR = os.path.join(gc.MODELS_DIR, "yamnet")  # 모델 폴더(gitignore)
MODEL = os.path.join(MODEL_DIR, "yamnet.tflite")  # Kaggle google/yamnet tfLite classification-tflite v1 의 1.tflite
CLASS_MAP = os.path.join(MODEL_DIR, "yamnet_class_map.csv")  # tensorflow/models 클래스 표
MODEL_SHA256 = "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de"  # README 기록 해시
CLIP = "kr_customer_01_20s_plus77.wav"  # 골든 클립(한국어 음성 20 s + 77 샘플)
WINDOW = 15600  # 모델 입력 창(샘플)
HOP = 243  # android-vad FRAME_SIZE_243
SPEECH = 0  # Speech 클래스 index
THRESHOLDS = {"YAMNET_NORMAL": 0.3, "YAMNET_AGGRESSIVE": 0.5, "YAMNET_VERY_AGGRESSIVE": 0.85}  # 앱 프리셋 임계값


def sha256(path):
    """
    목적: 파일 SHA-256 계산
    기능: 파일 전체를 읽어 16진 해시를 만든다.
    입력: path(파일 경로)
    출력: 없음
    리턴: 16진 문자열
    """
    with open(path, "rb") as f:  # 바이너리로 열기
        return hashlib.sha256(f.read()).hexdigest()  # 해시


def check_labels():
    """
    목적: Speech index 확인
    기능: 모델에 내장된 yamnet_label_list.txt 와 클래스 표 CSV 의 이름 521 개가 같고 index 0 이 "Speech" 인지 assert 한다.
    입력: 없음
    출력: 실패 시 AssertionError
    리턴: 없음
    """
    embedded = zipfile.ZipFile(MODEL).read("yamnet_label_list.txt").decode("utf-8").splitlines()  # 모델 내장 라벨
    with open(CLASS_MAP, encoding="utf-8") as f:  # CSV 열기
        rows = list(csv.DictReader(f))  # index,mid,display_name
    names = [r["display_name"] for r in rows]  # 이름 목록
    assert [int(r["index"]) for r in rows] == list(range(521)), "CSV index 가 0..520 이 아님"  # index 순서
    assert embedded == names, "내장 라벨과 CSV 이름 불일치"  # 두 출처 일치
    assert names[SPEECH] == "Speech", names[SPEECH]  # index 0 = Speech


def windows(pcm):
    """
    목적: 앱 YamnetVad 와 같은 창 생성
    기능: int16 → float32 / 32767, 앞에 0 을 (15600 − 243) 개 붙이고 프레임 i 마다 [i·243, i·243 + 15600) 를 자른다.
    입력: pcm(int16 ndarray)
    출력: 없음
    리턴: (프레임 수, 창 생성기)
    """
    n = len(pcm) // HOP  # 완전한 프레임 수
    x = pcm[: n * HOP].astype(np.float32) / np.float32(32767)  # TensorAudio: short / Short.MAX_VALUE (float32 나눗셈)
    padded = np.concatenate([np.zeros(WINDOW - HOP, dtype=np.float32), x])  # 링버퍼 초기 0 재현
    return n, (padded[i * HOP: i * HOP + WINDOW] for i in range(n))  # 프레임 수, 창들


def main():
    """
    목적: 골든 생성 진입점
    기능: 해시·라벨 확인 → Interpreter 로 모든 창 추론 → Speech 점수 JSON 저장 → android-vad 판정 차이 출력.
    입력: 없음
    출력: golden/scores_YAMNET_kr_customer_01_20s_plus77.json, 콘솔 요약
    리턴: 없음
    """
    assert sha256(MODEL) == MODEL_SHA256, "yamnet.tflite SHA-256 불일치"  # 모델 확인
    check_labels()  # Speech index 확인
    it = Interpreter(model_path=MODEL)  # 기본 옵션(CPU XNNPACK) — 앱과 같은 기본 설정
    it.allocate_tensors()  # 텐서 할당
    inp = it.get_input_details()[0]  # 입력 [15600] float32
    out = it.get_output_details()[0]  # 출력 [1, 521] float32
    assert list(inp["shape"]) == [WINDOW] and list(out["shape"]) == [1, 521], (inp["shape"], out["shape"])  # 모양 확인
    _, pcm = gc.load_clip(CLIP)  # int16 원본
    n, wins = windows(pcm)  # 창
    speech = np.zeros(n, dtype=np.float32)  # Speech 점수
    top1 = np.zeros(n, dtype=np.int64)  # 최고점 클래스(android-vad 판정 비교용)
    top1_score = np.zeros(n, dtype=np.float32)  # 최고점 점수
    for i, w in enumerate(wins):  # 프레임마다
        it.set_tensor(inp["index"], w)  # 입력 설정
        it.invoke()  # 추론
        scores = it.get_tensor(out["index"])[0]  # 521 점수
        speech[i] = scores[SPEECH]  # Speech 점수
        top1[i] = int(np.argmax(scores))  # 최고점 index
        top1_score[i] = scores[top1[i]]  # 최고점 점수
    assert n == len(pcm) // HOP  # 프레임 수 규칙
    path = gc.write_scores(  # JSON 저장
        "YAMNET", CLIP, HOP / gc.SAMPLE_RATE, speech,
        f"ai-edge-litert {version('ai-edge-litert')} Interpreter(yamnet.tflite sha256 {MODEL_SHA256[:12]}…), android-vad FRAME_SIZE_243 창",
        extra={
            "totalSamples": int(len(pcm)),  # 입력 샘플 수
            "windowSamples": WINDOW,  # 창 길이
            "hopSamples": HOP,  # hop
            "classIndex": SPEECH,  # Speech index
            "numClasses": 521,  # 클래스 수
            "inputScale": 32767,  # int16 → float 나눗수
        },
    )
    print(f"저장: {path} (프레임 {n}, Speech 점수 {speech.min():.6f}..{speech.max():.6f})")  # 요약
    for pid, th in THRESHOLDS.items():  # 프리셋 임계값마다
        ours = speech.astype(np.float64) >= th  # 본 앱 판정
        avad = (top1 == SPEECH) & (top1_score.astype(np.float64) > th)  # android-vad 판정(최고점 = Speech 이고 > th)
        print(f"{pid}(th {th}): 본 앱 음성 프레임 {int(ours.sum())}, android-vad 방식 {int(avad.sum())}, 판정 다른 프레임 {int((ours != avad).sum())}/{n}")  # 차이


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
