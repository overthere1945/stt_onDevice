# 파일명: gen_fsmn.py
# 목적: FSMN-VAD 골든 데이터 생성(Kotlin FsmnStateMachine·FsmnVad 포팅 검증 기준)
# 기능: funasr_onnx.Fsmn_vad(quantize=True → model_quant.onnx)를 프리셋별 config.yaml 로 실행하고,
#       E2EVadModel 을 기록용 하위 클래스로 바꿔(site-packages 수정 없이 모듈 속성만 교체) 내부의
#       ONNX 출력 scores[0][t][0](silence 확률)과 프레임 dB(ComputeDecibel 결과)를 가로채 저장한다.
#       프리셋 2종 × 클립 3종 결과 구간(ms→초)을 골든 JSON 으로 쓴다.
# change(add)-hyungchul-2026-10-10-12:19

import os  # 경로
import shutil  # 파일 복사
import sys  # 모듈 경로 추가
import tempfile  # 임시 모델 폴더

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 golden_common 을 찾게 함

import numpy as np  # 수치 배열
import yaml  # config.yaml 읽기/쓰기
import funasr_onnx.vad_bin as vad_bin  # Fsmn_vad 가 E2EVadModel 을 찾는 모듈
from funasr_onnx.utils.e2e_vad import E2EVadModel  # 원본 후처리 상태기계
from importlib.metadata import version  # 패키지 버전

import golden_common as gc  # 공통 도우미

MODEL_DIR = os.path.join(gc.MODELS_DIR, "fsmn_onnx")  # ModelScope 에서 받은 원본 3파일 폴더
TOOL = f"funasr_onnx {version('funasr_onnx')} Fsmn_vad(quantize=True) + iic/speech_fsmn_vad_zh-cn-16k-common-onnx model_quant.onnx"  # 도구 표기
RECORD = {}  # 가로챈 값(scores, decibel)


class RecordingE2EVadModel(E2EVadModel):
    """
    목적: 원본 E2EVadModel 내부 값을 가로채는 하위 클래스
    기능: ComputeScores/ComputeDecibel 을 원본대로 실행한 직후 scores 배열과 decibel 목록을 RECORD 에 복사한다.
          (원본 __call__ 은 is_final 일 때 AllResetDetection 으로 값을 지우므로 계산 직후 복사한다.)
    """

    def ComputeScores(self, scores):
        """
        목적: 점수 기록
        기능: 원본 ComputeScores 실행 후 scores(1, T, 248) 를 복사해 둔다.
        입력: scores(ONNX 출력)
        출력: RECORD["scores"]
        리턴: 없음
        """
        super().ComputeScores(scores)  # 원본 동작
        RECORD.setdefault("scores", []).append(np.array(scores, copy=True))  # 블록별 복사

    def ComputeDecibel(self):
        """
        목적: 프레임 dB 기록
        기능: 원본 ComputeDecibel 실행 후 누적 decibel 목록을 복사해 둔다.
        입력: 없음
        출력: RECORD["decibel"]
        리턴: 없음
        """
        super().ComputeDecibel()  # 원본 동작
        RECORD["decibel"] = list(self.decibel)  # 누적 목록 복사


def make_model_dir(tmp_root, preset_id, conf):
    """
    목적: 프리셋별 임시 모델 폴더 생성
    기능: model_quant.onnx·am.mvn 을 복사하고, 원본 config.yaml 의 model_conf 를 프리셋 값으로 덮어쓴 사본을 쓴다.
    입력: tmp_root(임시 루트), preset_id(프리셋 id), conf(model_conf 덮어쓸 dict)
    출력: 임시 폴더 파일들
    리턴: (폴더 경로, 원본 config 의 max_single_segment_time)
    """
    d = os.path.join(tmp_root, preset_id)  # 프리셋 폴더
    os.makedirs(d)  # 생성
    for name in ("model_quant.onnx", "am.mvn"):  # 모델·CMVN
        shutil.copy(os.path.join(MODEL_DIR, name), os.path.join(d, name))  # 복사
    with open(os.path.join(MODEL_DIR, "config.yaml"), encoding="utf-8") as f:  # 원본 config
        cfg = yaml.safe_load(f)  # 읽기
    original_max = cfg["model_conf"]["max_single_segment_time"]  # 원본 값(20000) 기록
    cfg["model_conf"].update(conf)  # 프리셋 값 덮어쓰기
    with open(os.path.join(d, "config.yaml"), "w", encoding="utf-8") as f:  # 사본 config
        yaml.safe_dump(cfg, f, allow_unicode=True, sort_keys=False)  # 쓰기
    return d, original_max  # 반환


def main():
    """
    목적: FSMN 골든 생성 진입점
    기능: E2EVadModel 을 기록용으로 교체하고 프리셋·클립마다 Fsmn_vad 를 실행해 구간·sil 확률·dB 를 저장한다.
    입력: 없음
    출력: golden/FSMN_*.json, golden/scores_FSMN_*.json
    리턴: 없음
    """
    vad_bin.E2EVadModel = RecordingE2EVadModel  # Fsmn_vad.__call__ 이 쓰는 클래스를 기록용으로 교체(모듈 속성만)
    with tempfile.TemporaryDirectory() as tmp:  # 임시 폴더
        for pid, conf in gc.FSMN_PRESETS.items():  # 프리셋마다
            model_dir, original_max = make_model_dir(tmp, pid, conf)  # 프리셋 config 적용 폴더
            vad = vad_bin.Fsmn_vad(model_dir=model_dir, quantize=True, intra_op_num_threads=1)  # 원본 실행기
            assert vad.vad_scorer_config["max_single_segment_time"] == conf["max_single_segment_time"], "config 미적용"  # 적용 확인
            for clip in gc.CLIPS:  # 클립마다
                audio, _ = gc.load_clip(clip)  # float32 파형(-1~1)
                RECORD.clear()  # 기록 초기화
                result = vad(audio)  # 원본 실행(ndarray 입력 → librosa 미사용)
                assert len(RECORD["scores"]) == 1, "20 s 이하 클립은 한 블록(6000 프레임 이하)이어야 함"  # 블록 1개
                scores = RECORD["scores"][0][0]  # (T, 248) 소프트맥스 출력
                sil = scores[:, 0]  # sil_pdf_ids=[0] → silence 확률
                db = RECORD["decibel"]  # 프레임 dB
                assert len(db) == len(sil), f"dB 프레임 수 {len(db)} ≠ 점수 프레임 수 {len(sil)}"  # 길이 일치
                ms_pairs = [[int(s), int(e)] for s, e in (result[0] if result and result[0] else [])]  # ms 구간
                pairs = [(s / 1000.0, e / 1000.0) for s, e in ms_pairs]  # 초 단위
                gc.write_golden(  # 골든 저장
                    "FSMN", pid, clip, 0.010, sil, pairs, TOOL, frame_db=db,  # 기본 필드 + dB
                    extra={"expectedMs": ms_pairs,  # 원본 ms 정수 구간
                           "params": conf,  # 적용한 model_conf 값
                           "originalConfigMaxSingleSegmentTime": original_max},  # ModelScope config 원래 값
                )
                if pid == "FSMN_DEFAULT":  # 점수는 프리셋과 무관하므로 한 번만
                    gc.write_scores("FSMN", clip, 0.010, sil, TOOL,  # 모델 추론 정합용 sil 확률
                                    extra={"scoreIndex": 0, "numClasses": int(scores.shape[1])})  # 사용 인덱스/클래스 수
                print(clip, pid, len(sil), ms_pairs[:4])  # 진행 로그


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
