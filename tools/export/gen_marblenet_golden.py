# 파일명: gen_marblenet_golden.py
# 목적: MarbleNet(Frame_VAD_Multilingual_MarbleNet_v2.0) 골든 데이터 생성(Kotlin 전처리·추론·NemoPostprocess 포팅 검증 기준)
# 기능: - NeMo 원본(PyTorch fp32) 음성 확률 softmax(logits)[..., 1] 을 MARBLENET_NEMO 프리셋으로 NeMo vad_utils 후처리
#         (prepare_gen_segment_table → generate_vad_segment_table_per_tensor = binarization → filtering → 정렬)한 구간을
#         MARBLENET_MARBLENET_NEMO_<clip>.json 에 저장한다(단계별 binarization 결과 포함).
#       - 공식 frame_vad_infer.py 경로(확률을 '{:0.4f}' 로 .frame 파일에 쓰고 generate_vad_segment_table 로 읽어 후처리)도 실행해
#         결과를 함께 기록한다(4자리 반올림 확률 기준).
#       - export_marblenet.py 가 만든 ONNX 의 확률을 scores_MARBLENET_<clip>.json 에 저장한다.
#       - NeMo preprocessor 출력(log-mel, 프레임×80) 앞 50 프레임 + 끝 4 프레임을 marblenet_mel_<clip>.json 에 저장하고,
#         README 에 적은 전처리 식을 numpy(float64) 로 독립 구현해 NeMo 출력과의 최대 |차| 를 assert 한다.
#       - 전처리 설정·창 값·mel 필터뱅크를 marblenet_preprocessor.json 으로 테스트 리소스에 복사한다.
#       - 프리셋 값이 NeMo v3.0.0 공식 frame_vad_infer_postprocess.yaml 과 같은지 먼저 assert 한다.
# 실행: tools/export/.venv-nemo/bin/python -I tools/export/gen_marblenet_golden.py  (export_marblenet.py 먼저 실행)
# change(add)-hyungchul-2026-10-10-14:25

import json  # JSON 읽기
import os  # 경로
import sys  # 모듈 경로
import tempfile  # 공식 파일 경로 재현용 임시 폴더
from importlib.metadata import version  # 패키지 버전

EXPORT_DIR = os.path.dirname(os.path.abspath(__file__))  # tools/export 폴더
sys.path.insert(0, os.path.join(os.path.dirname(EXPORT_DIR), "golden"))  # tools/golden/golden_common 재사용(-I 실행 대비)
sys.path.insert(0, EXPORT_DIR)  # export_marblenet 재사용

import numpy as np  # 수치 배열
import torch  # 텐서
import yaml  # NeMo 공식 후처리 설정 YAML 읽기
from nemo.collections.asr.parts.utils.vad_utils import (  # NeMo 원본 후처리 함수
    binarization,  # onset/offset 히스테리시스 + pad + 겹침 병합
    filtering,  # min_duration_on/off 필터
    generate_vad_segment_table,  # 공식 frame_vad_infer.py 가 쓰는 파일 기반 진입점
    generate_vad_segment_table_per_tensor,  # 텐서 1개 진입점(binarization → filtering → 정렬 → dur 열)
    prepare_gen_segment_table,  # onset/offset scale 계산, filter_speech_first → 1.0/0.0
)

import export_marblenet as em  # 모델 로딩·ONNX 실행 재사용
import golden_common as gc  # 골든 JSON 공통 형식

HOP = 0.02  # 출력 프레임 간격(초) = shift_length_in_sec
MEL_FRAMES = 50  # 저장할 log-mel 앞 프레임 수
TAIL_FRAMES = 4  # 저장할 log-mel 끝 프레임 수(마스크·pad_to 확인용)
PREPROC_TOL = 2e-3  # numpy 독립 전처리 vs NeMo 허용 최대 |차|(log-mel, float64 vs float32 계산 차이)
TOOL = (f"nemo_toolkit {version('nemo_toolkit')} EncDecFrameClassificationModel + vad_utils; "
        f"{em.HF_REPO}@{em.HF_REVISION[:7]}; torch {torch.__version__}")  # 도구 표기

# ---- 프리셋 값: app/src/main/java/com/cowork/stt_ondevice/vad/VadPresets.kt MARBLENET_NEMO 와 동일해야 한다 ----
# (NeMo examples/asr/conf/vad/frame_vad_infer_postprocess.yaml vad.parameters.postprocessing 값과 같음)
MARBLENET_NEMO = {"onset": 0.3, "offset": 0.3, "pad_onset": 0.2, "pad_offset": 0.2,  # 임계·패딩
                  "min_duration_on": 0.2, "min_duration_off": 0.2, "filter_speech_first": True}  # 필터
NEMO_COMMIT = "fd6a877539710e2b98f28c43272ff81312f83417"  # NVIDIA/NeMo 태그 v3.0.0(설치된 nemo_toolkit 3.0.0 과 같은 버전)
NEMO_YAML = os.path.join(em.OUT_DIR, "src", "nemo_ref", "frame_vad_infer_postprocess.yaml")  # 그 commit 의 공식 설정 파일


def check_preset_yaml():
    """
    목적: 프리셋 값이 NeMo 공식 설정과 같은지 확인
    기능: v3.0.0 의 examples/asr/conf/vad/frame_vad_infer_postprocess.yaml 을 읽어 vad.parameters.postprocessing 과
          shift_length_in_sec(0.02), window_length_in_sec(0.0), smoothing(False) 를 MARBLENET_NEMO·HOP 과 비교한다.
    입력: 없음
    출력: 없음(실패 시 AssertionError)
    리턴: 없음
    """
    with open(NEMO_YAML, encoding="utf-8") as f:  # 다운로드한 설정(데이터로만 읽음)
        params = yaml.safe_load(f)["vad"]["parameters"]  # vad.parameters
    assert params["postprocessing"] == MARBLENET_NEMO, f"프리셋 ≠ 공식 YAML: {params['postprocessing']}"  # 후처리 값
    assert params["shift_length_in_sec"] == HOP and params["window_length_in_sec"] == 0.0 and params["smoothing"] is False  # 프레임 설정


def f32_pairs(segments):
    """
    목적: NeMo 구간 텐서를 (start, end) float32 정확 표기 쌍으로 변환
    기능: (K, ≥2) float32 텐서의 앞 두 열을 float32 최단 10진 표기 float 로 바꾼다. 빈 텐서는 빈 목록.
    입력: segments(torch.Tensor)
    출력: 없음
    리턴: [(start, end), ...]
    """
    if segments.numel() == 0:  # 구간 없음
        return []  # 빈 목록
    rows = segments.tolist()  # 파이썬 리스트
    return [tuple(gc.f32(r[:2])) for r in rows]  # float32 정확 표기


def nemo_postprocess(probs):
    """
    목적: NeMo 원본 후처리 실행(텐서 경로)
    기능: generate_vad_segment_table_per_file 이 하는 것과 같이 prepare_gen_segment_table 로 인자를 만들고
          generate_vad_segment_table_per_tensor 를 호출한다. 단계 확인용으로 binarization·filtering 결과도 따로 구한다.
    입력: probs((T,) float32 ndarray)
    출력: 없음
    리턴: (최종 (K,3) 텐서, binarization 결과 텐서, filtering 결과 텐서, per_args_float)
    """
    seq = torch.tensor(probs, dtype=torch.float32)  # load_tensor_from_file 과 같은 float32 1차원 텐서
    per_args = dict(MARBLENET_NEMO, frame_length_in_sec=HOP, out_dir=None)  # generate_vad_segment_table 이 만드는 per_args 와 같은 키
    _, per_args_float = prepare_gen_segment_table(seq, per_args)  # scale absolute → onset/offset 그대로, filter_speech_first → 1.0
    table = generate_vad_segment_table_per_tensor(seq, per_args_float)  # 공식 진입점
    binarized = binarization(seq, per_args_float)  # 1단계(확인용)
    filtered = filtering(binarized, per_args_float)  # 2단계(확인용, 정렬 전)
    return table, binarized, filtered, per_args_float  # 반환


def official_file_route(probs, name):
    """
    목적: 공식 frame_vad_infer.py 의 파일 기반 후처리 경로 재현
    기능: generate_vad_frame_pred 처럼 확률을 '{0:0.4f}' 로 <name>.frame 에 쓰고, generate_vad_segment_table(use_rttm=False)
          로 후처리해 출력 txt("start dur speech", 소수 4자리)를 읽는다. 구간이 없으면 원본은 "0 0 speech" 한 줄을 쓴다.
    입력: probs((T,) float32), name(파일 이름 stem)
    출력: 없음
    리턴: [[start, dur], ...] (원본 txt 의 숫자, 빈 결과면 [])
    """
    with tempfile.TemporaryDirectory() as tmp:  # 임시 폴더
        with open(os.path.join(tmp, name + ".frame"), "w", encoding="utf-8") as f:  # .frame 파일
            for p in probs.tolist():  # 프레임마다
                f.write("{0:0.4f}\n".format(p))  # 원본과 같은 형식
        out_dir = generate_vad_segment_table(vad_pred_dir=tmp, postprocessing_params=dict(MARBLENET_NEMO),  # 공식 함수
                                             frame_length_in_sec=HOP, num_workers=1, out_dir=os.path.join(tmp, "seg"), use_rttm=False)  # 인자
        with open(os.path.join(out_dir, name + ".txt"), encoding="utf-8") as f:  # 결과 txt
            rows = [line.split() for line in f if line.strip()]  # 줄 분해
    rows = [[float(r[0]), float(r[1])] for r in rows]  # 숫자로
    return [] if rows == [[0.0, 0.0]] else rows  # "0 0 speech" = 구간 없음


def numpy_logmel(audio, info):
    """
    목적: NeMo 전처리 독립 구현(Kotlin 포팅 기준, float64)
    기능: README 식 — preemph 0.97(첫 샘플 유지) → 앞뒤 256 샘플 0 패딩(center) → 512 프레임(hop 160), 대칭 hann 400 을
          가운데(왼쪽 56)에 둔 512 창 → rFFT 257 → |X| 의 제곱 → mel(80×257) 곱 → log(x + 2^-24) →
          seqLen(= N//160) 이상 프레임 0 → 프레임 수를 2 의 배수로 0 패딩.
    입력: audio(float32 ndarray), info(marblenet_preprocessor.json dict)
    출력: 없음
    리턴: (80, F) float64 ndarray, seqLen
    """
    x = audio.astype(np.float64)  # double
    n = len(x)  # 샘플 수
    y = np.concatenate([x[:1], x[1:] - 0.97 * x[:-1]])  # preemphasis
    pad = info["effective"]["n_fft"] // 2  # 256
    yp = np.concatenate([np.zeros(pad), y, np.zeros(pad)])  # center=True, pad_mode constant
    frames = 1 + n // 160  # STFT 프레임 수
    win = np.zeros(512)  # n_fft 창
    win[56:456] = np.asarray(info["windowValues"], dtype=np.float64)  # hann 400 을 가운데 배치(torch.stft 규칙)
    idx = np.arange(512)[None, :] + 160 * np.arange(frames)[:, None]  # 프레임 인덱스
    spec = np.fft.rfft(yp[idx] * win, axis=1)  # (F, 257) 복소
    power = np.abs(spec) ** 2  # |X|^mag_power(2)
    fb = np.asarray(info["melFilterbank"], dtype=np.float64)  # (80, 257)
    mel = np.log(fb @ power.T + 2.0 ** -24)  # (80, F) log(mel + guard)
    seq_len = n // 160  # 유효 프레임 수
    mel[:, seq_len:] = 0.0  # 마스크(pad_value 0)
    if mel.shape[1] % 2:  # pad_to 2
        mel = np.concatenate([mel, np.zeros((80, 1))], axis=1)  # 0 프레임 추가
    return mel, seq_len  # 반환


def main():
    """
    목적: MarbleNet 골든 생성 진입점
    기능: 클립마다 NeMo 확률·전처리 출력·ONNX 확률을 구하고, NeMo 후처리(텐서·파일 경로) 결과와 특징 골든을 저장한다.
    입력: 없음
    출력: golden/MARBLENET_MARBLENET_NEMO_*.json, scores_MARBLENET_*.json, marblenet_mel_*.json, marblenet_preprocessor.json
    리턴: 없음
    """
    torch.set_num_threads(1)  # 결정적 실행
    check_preset_yaml()  # 프리셋 = 공식 YAML 확인
    model = em.load_model()  # 원본 복원(가중치 완전 적재 확인 포함)
    with open(em.PREPROC_JSON, encoding="utf-8") as f:  # export 가 저장한 전처리 설정
        info = json.load(f)  # dict
    sess = em.session(em.ONNX_PATH)  # ONNX 세션
    for clip in gc.CLIPS:  # 클립마다
        audio, _ = gc.load_clip(clip)  # float32 파형(int16/32768, NeMo AudioSegment 와 같은 정규화)
        n = len(audio)  # 전체 샘플 수
        probs, _, ps, pl = em.nemo_probs(model, audio)  # NeMo 원본 확률·전처리 출력
        mine, seq_len = numpy_logmel(audio, info)  # 독립 전처리
        assert mine.shape == ps[0].shape and seq_len == int(pl[0]), "전처리 프레임 수 불일치"  # 형태 확인
        pre_diff = float(np.max(np.abs(mine - ps[0])))  # 최대 |차|
        assert pre_diff <= PREPROC_TOL, f"독립 전처리 차이 {pre_diff}"  # 허용 확인
        mel_t = ps[0].T  # (F, 80) 프레임 우선
        gc.write_feature("marblenet_mel", clip, mel_t[:MEL_FRAMES], TOOL,  # log-mel 앞 50 프레임
                         extra={"numFramesTotal": int(mel_t.shape[0]), "seqLen": int(pl[0]), "totalSamples": n,  # 프레임 수
                                "tailFrames": [gc.f32(r) for r in mel_t[-TAIL_FRAMES:]],  # 끝 4 프레임(마스크·패딩 0 확인)
                                "layout": "expected[t][m] = processed_signal[0][m][t] (ONNX 입력은 (1, 80, F))",  # 전치 설명
                                "numpyFloat64MaxAbsDiff": pre_diff})  # 독립 구현 차이
        table, binarized, filtered, per_args_float = nemo_postprocess(probs)  # NeMo 후처리(원본 확률)
        pairs = f32_pairs(table)  # (start, end)
        onnx_logits = sess.run(["output"], {"processed_signal": ps, "processed_signal_length": pl})[0][0]  # ONNX logits
        onnx_probs = em.softmax_speech(onnx_logits)  # ONNX 확률
        onnx_pairs = f32_pairs(nemo_postprocess(onnx_probs)[0])  # ONNX 확률로 같은 후처리
        file_rows = official_file_route(probs, gc.clip_stem(clip))  # 공식 파일 경로(4자리 반올림 확률)
        rounded = np.array([float("{0:0.4f}".format(p)) for p in probs.tolist()], dtype=np.float32)  # 같은 반올림
        rounded_pairs = f32_pairs(nemo_postprocess(rounded)[0])  # 반올림 확률로 텐서 경로
        same_file = len(file_rows) == len(rounded_pairs) and all(  # 파일 경로 = 반올림 텐서 경로(소수 4자리 비교)
            abs(r[0] - s) < 5e-5 and abs(r[1] - round(e - s + 0.01, 4)) < 1.5e-4 for r, (s, e) in zip(file_rows, rounded_pairs))  # start, dur
        assert same_file, f"공식 파일 경로 ≠ 텐서 경로 {clip}"  # 일치 확인
        gc.write_golden(  # 구간 골든
            "MARBLENET", "MARBLENET_NEMO", clip, HOP, probs, pairs, TOOL,  # 기본 필드
            extra={"totalSamples": n, "seqLen": int(pl[0]), "processedFrames": int(ps.shape[2]),  # 프레임 정보
                   "expectedDur": [gc.f32([r[2]])[0] for r in table.tolist()] if table.numel() else [],  # NeMo 표의 dur 열(end − start + 0.01)
                   "binarized": [list(p) for p in f32_pairs(binarized)],  # binarization 결과(병합 후, 정렬 전)
                   "filtered": [list(p) for p in f32_pairs(filtered)],  # filtering 결과(정렬 전)
                   "params": dict(MARBLENET_NEMO, frame_length_in_sec=HOP),  # 적용 값
                   "perArgsFloat": per_args_float,  # NeMo 에 실제로 넘어간 값
                   "sameSegmentsWithOnnxProbs": onnx_pairs == pairs,  # ONNX 확률로도 같은 구간인지
                   "officialFileRoute": {"rows": file_rows,  # 공식 경로 txt (start, dur) 소수 4자리
                                         "sameAsUnrounded": rounded_pairs == pairs}})  # 반올림 확률 결과가 원본 확률 결과와 같은지
        gc.write_scores("MARBLENET", clip, HOP, onnx_probs, TOOL,  # ONNX 확률(모델 추론 정합용)
                        extra={"model": os.path.basename(em.ONNX_PATH), "totalSamples": n,  # 모델·샘플 수
                               "seqLen": int(pl[0]), "processedFrames": int(ps.shape[2]),  # 입력 프레임
                               "scoreDefinition": "softmax(output[0], axis=-1)[:, 1]",  # 확률 정의
                               "torchMaxAbsDiff": float(np.max(np.abs(onnx_probs - probs)))})  # NeMo 대비 차
        print(clip, len(probs), pairs, "preDiff", pre_diff, "onnxSame", onnx_pairs == pairs, "fileRouteSame", rounded_pairs == pairs)  # 로그
    gc._dump("marblenet_preprocessor.json", dict(info, kind="marblenet_preprocessor", expected=None))  # 전처리 설정 복사(expected 키 필요)
    print("OK: MarbleNet 골든 생성 완료")  # 성공


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
