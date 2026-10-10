# 파일명: export_marblenet.py
# 목적: NVIDIA Frame_VAD_Multilingual_MarbleNet_v2.0(.nemo)을 모델 카드의 "Export to ONNX" 코드대로 ONNX 변환하고 수치 정합을 검증한다.
# 기능: (1) .nemo SHA-256 확인 후 NeMo EncDecFrameClassificationModel.restore_from 으로 복원하고, 체크포인트의 모든 텐서가
#           모델에 그대로 들어갔는지 확인한다(NeMo 3.0.0 은 loss.weight 버퍼가 체크포인트에 없어 strict=False 필요 — README 참고).
#       (2) NeMo 전처리기(AudioToMelSpectrogramPreprocessor → FilterbankFeatures) 설정·실효값·mel 필터뱅크를
#           out/marblenet/marblenet_preprocessor.json 으로 저장한다(Kotlin 포팅 기준).
#       (3) 모델 카드 코드(typecheck 끄기 → preprocessor 출력으로 torch.onnx.export, 입력 processed_signal/processed_signal_length,
#           출력 output(logits))로 out/marblenet/frame_vad_multilingual_marblenet_v2.0.onnx 를 만든다(전처리는 그래프에 없음).
#       (4) 골든 클립 4개에서 NeMo(PyTorch fp32) 음성 확률 softmax(logits)[..., 1] 과 ONNX 확률의 최대 |차| ≤ 1e-4 를 assert 한다.
#       (5) 결과를 out/marblenet/export_report.json 에 쓰고 SHA-256 표를 출력한다.
# 실행: tools/export/.venv-nemo/bin/python -I tools/export/export_marblenet.py
# change(add)-hyungchul-2026-10-10-14:25

import hashlib  # SHA-256
import io  # 메모리 버퍼
import json  # JSON 저장
import os  # 경로
import sys  # 모듈 경로
import tarfile  # .nemo(tar) 읽기

EXPORT_DIR = os.path.dirname(os.path.abspath(__file__))  # tools/export 폴더
sys.path.insert(0, os.path.join(os.path.dirname(EXPORT_DIR), "golden"))  # tools/golden/golden_common 재사용(-I 실행 대비)

import numpy as np  # 수치 배열
import onnx  # ONNX 메타데이터 확인
import onnxruntime as ort  # ONNX 추론
import torch  # PyTorch
from importlib.metadata import version  # 패키지 버전

import nemo.collections.asr as nemo_asr  # NeMo ASR 모델
from nemo.core import typecheck  # 모델 카드 export 절차(typecheck 끄기)

import golden_common as gc  # 클립 목록·로딩

# ---- 고정 버전·경로 ----
HF_REPO = "nvidia/Frame_VAD_Multilingual_MarbleNet_v2.0"  # HF 저장소
HF_REVISION = "9d25eb877d9edab5101ccc843edd04787a6c72c4"  # HF 고정 revision
NEMO_FILE = "frame_vad_multilingual_marblenet_v2.0.nemo"  # 원본 .nemo 파일명
NEMO_SHA256 = "84bda37e925ac6fd740c2ced55642cb79f94f81348e1fa0db992ca50d4b09706"  # HF LFS SHA-256(다운로드 후 확인)
OUT_DIR = os.path.join(EXPORT_DIR, "out")  # 산출물 루트(gitignore)
NEMO_PATH = os.path.join(OUT_DIR, "models", "marblenet", NEMO_FILE)  # .nemo 경로
MB_OUT = os.path.join(OUT_DIR, "marblenet")  # ONNX 출력 폴더
ONNX_PATH = os.path.join(MB_OUT, "frame_vad_multilingual_marblenet_v2.0.onnx")  # ONNX 경로(모델 카드 이름)
PREPROC_JSON = os.path.join(MB_OUT, "marblenet_preprocessor.json")  # 전처리 설정 JSON
TOL = 1e-4  # 허용 최대 |차|


def sha256(path):
    """
    목적: 파일 SHA-256 계산
    기능: 1 MB 단위로 읽어 해시한다.
    입력: path(파일 경로)
    출력: 없음
    리턴: 16진 문자열
    """
    h = hashlib.sha256()  # 해시 객체
    with open(path, "rb") as f:  # 바이너리 읽기
        for block in iter(lambda: f.read(1 << 20), b""):  # 1 MB 블록
            h.update(block)  # 누적
    return h.hexdigest()  # 16진 문자열


def load_model():
    """
    목적: 원본 MarbleNet 복원과 가중치 완전 적재 확인
    기능: .nemo 해시 확인 → restore_from(map_location cpu, strict=False) → eval. 이어서 .nemo 안 model_weights.ckpt 를
          weights_only=True 로 직접 읽어, 모델 state_dict 와 비교해 빠진 키가 loss.weight(학습용 손실 가중치 [1,1]) 하나뿐이고
          나머지 모든 텐서가 완전히 같은지 assert 한다. crop_or_pad 가 없는지도 확인한다(forward 가 전처리 출력을 그대로 encoder 에 넣음).
    입력: 없음
    출력: 없음
    리턴: EncDecFrameClassificationModel(eval, cpu)
    """
    assert sha256(NEMO_PATH) == NEMO_SHA256, ".nemo SHA-256 불일치"  # 원본 고정 확인
    model = nemo_asr.models.EncDecFrameClassificationModel.restore_from(NEMO_PATH, map_location="cpu", strict=False)  # 복원
    model.eval()  # 추론 모드(dither·spec augment 비활성)
    with tarfile.open(NEMO_PATH) as t:  # .nemo = tar
        ckpt = torch.load(io.BytesIO(t.extractfile("./model_weights.ckpt").read()), map_location="cpu", weights_only=True)  # 안전 로드
    msd = model.state_dict()  # 모델 텐서
    assert set(msd) - set(ckpt) == {"loss.weight"} and not set(ckpt) - set(msd), "state_dict 키 차이 예상과 다름"  # 키 차이 확인
    assert all(torch.equal(msd[k], ckpt[k]) for k in ckpt), "체크포인트 텐서가 모델과 다름"  # 값 완전 일치
    assert torch.equal(msd["loss.weight"], torch.tensor([1.0, 1.0])), "loss.weight 기본값 아님"  # 추론과 무관한 손실 가중치
    assert model.crop_or_pad is None, "crop_or_pad 존재"  # forward 경로 확인
    return model  # 반환


def preprocessor_info(model):
    """
    목적: NeMo 전처리 설정·실효값 수집(Kotlin 포팅 기준)
    기능: .nemo 의 preprocessor 설정 원문과 FilterbankFeatures 의 실제 속성(창 길이·hop·n_fft·창 함수 값·preemph·log guard 등),
          stft 호출 인자(center/pad_mode), mel 필터뱅크(80×257) 를 dict 로 모은다.
    입력: model(EncDecFrameClassificationModel)
    출력: 없음
    리턴: dict
    """
    fe = model.preprocessor.featurizer  # FilterbankFeatures
    return {  # 전처리 정보 dict
        "source": f"{HF_REPO}@{HF_REVISION} {NEMO_FILE} model_config.yaml preprocessor",  # 출처
        "nemoToolkit": version("nemo_toolkit"), "torch": torch.__version__,  # 버전
        "configPreprocessor": json.loads(json.dumps(dict(model.cfg.preprocessor))),  # 설정 원문
        "effective": {  # FilterbankFeatures 실제 값
            "class": f"{type(model.preprocessor).__name__} → {type(fe).__name__}",  # 클래스
            "sample_rate": fe.sample_rate, "win_length": fe.win_length, "hop_length": fe.hop_length,  # 창 400, hop 160
            "n_fft": fe.n_fft, "window": "hann(periodic=False, 400)",  # FFT 512, 대칭 hann
            "nfilt": fe.nfilt, "preemph": fe.preemph, "log": fe.log,  # mel 80, 0.97, log
            "log_zero_guard_type": fe.log_zero_guard_type, "log_zero_guard_value": fe.log_zero_guard_value,  # log(x + 2^-24)
            "mag_power": fe.mag_power, "normalize": fe.normalize,  # 2.0, "None" 문자열 → 정규화 없음
            "normalize_applied": fe.normalize in ("per_feature", "all_features"),  # 실제 정규화 여부
            "dither": fe.dither, "dither_applied_in_eval": False,  # 1e-5 이지만 training 일 때만
            "frame_splicing": fe.frame_splicing, "pad_to": fe.pad_to, "pad_value": fe.pad_value,  # 1, 2, 0
            "exact_pad": fe.exact_pad, "stft_center": not fe.exact_pad, "stft_pad_mode": "constant",  # center=True, 0 패딩
            "mel_norm": "slaney", "lowfreq": 0, "highfreq": fe.sample_rate / 2, "mel_htk": False,  # librosa mel 기본
            "nb_augmentation_prob": fe.nb_augmentation_prob, "use_grads": fe.use_grads,  # 0.0, False
        },
        "windowValues": [float(v) for v in fe.window.tolist()],  # 창 400 값(float32)
        "melFilterbank": [gc.f32(row) for row in fe.fb[0].tolist()],  # (80, 257) float32
        "seqLenFormula": "floor((N + 2*(n_fft//2) - n_fft) / hop) = floor(N/160)",  # 유효 프레임 수
        "stftFrames": "1 + floor(N/160) (center=True: 앞뒤 256 샘플 0 패딩)",  # STFT 프레임 수
        "paddedFrames": "stftFrames 를 pad_to(2) 배수로 올림. seqLen 이상 프레임은 pad_value 0",  # 최종 프레임 수
    }


def export_onnx(model, sample_audio):
    """
    목적: 모델 카드 "Export to ONNX" 코드 실행
    기능: typecheck 를 끄고 모델을 cpu 로 둔 뒤, 예제 입력의 preprocessor 출력을 inputs dict 로 만들어
          카드와 같은 인자(input_names, output_names=["output"], dynamic_axes)로 torch.onnx.export 한다.
    입력: model, sample_audio(float32 ndarray, 예제 입력 파형)
    출력: ONNX_PATH
    리턴: 없음
    """
    typecheck.set_typecheck_enabled(False)  # 카드: typecheck 끄기
    vad_model = model.cpu()  # 카드: cpu
    input_signal = torch.tensor(sample_audio).unsqueeze(0).float()  # (1, N)
    input_signal_length = torch.tensor([input_signal.shape[1]]).long()  # (1,)
    processed_signal, processed_signal_length = vad_model.preprocessor(input_signal=input_signal, length=input_signal_length)  # 카드: 전처리
    inputs = {"processed_signal": processed_signal, "processed_signal_length": processed_signal_length}  # 카드: 입력 dict
    torch.onnx.export(  # 카드 코드 그대로
        model=vad_model, args=inputs, f=ONNX_PATH,  # 모델·입력·경로
        input_names=list(inputs.keys()), output_names=["output"],  # 입출력 이름
        dynamic_axes={"processed_signal": {0: "batch_size", 2: "sequence_length"},  # 동적 축
                      "processed_signal_length": {0: "batch_size"},  # 동적 축
                      "output": {0: "batch_size", 1: "sequence_length"}},  # 동적 축
    )
    typecheck.set_typecheck_enabled(True)  # 이후 NeMo 호출은 원래대로
    onnx.checker.check_model(onnx.load(ONNX_PATH))  # 형식 검사


def session(path):
    """
    목적: 결정적 ONNX 세션 생성
    기능: CPU EP, 스레드 1.
    입력: path(ONNX 경로)
    출력: 없음
    리턴: InferenceSession
    """
    so = ort.SessionOptions()  # 옵션
    so.intra_op_num_threads = 1  # 스레드 1
    so.inter_op_num_threads = 1  # 스레드 1
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])  # 세션


def softmax_speech(logits):
    """
    목적: logits → 음성 확률
    기능: NeMo generate_vad_frame_pred 와 같이 마지막 축 softmax 후 클래스 1(speech) 을 취한다(float32).
    입력: logits((T, 2) float32 ndarray 또는 텐서)
    출력: 없음
    리턴: (T,) float32 ndarray
    """
    t = torch.as_tensor(logits)  # 텐서로
    return torch.softmax(t, dim=-1)[:, 1].numpy()  # torch.softmax(·, dim=-1)[:, 1]


def nemo_probs(model, audio):
    """
    목적: NeMo 원본(PyTorch fp32) 음성 확률과 전처리 출력
    기능: 모델 카드 "Inference with PyTorch" 와 같이 model(input_signal, input_signal_length) 를 no_grad 로 호출하고
          softmax[..., 1] 을 취한다. 같은 입력의 preprocessor 출력도 돌려준다.
    입력: model, audio(float32 ndarray)
    출력: 없음
    리턴: (확률 (T,), logits (T,2), processed_signal (1,80,F), processed_signal_length (1,))
    """
    x = torch.tensor(audio).unsqueeze(0).float()  # (1, N)
    n = torch.tensor([x.shape[1]]).long()  # (1,)
    with torch.no_grad():  # 추론
        logits = model(input_signal=x, input_signal_length=n)[0]  # (T, 2)
        ps, pl = model.preprocessor(input_signal=x, length=n)  # 전처리 출력
    return softmax_speech(logits), logits.numpy(), ps.numpy(), pl.numpy()  # 반환


def main():
    """
    목적: MarbleNet ONNX 변환·검증 진입점
    기능: 원본 복원 → 전처리 JSON → 카드 방식 export → 클립 4개 NeMo vs ONNX 최대 |차| assert → 보고·SHA-256 출력.
    입력: 없음
    출력: out/marblenet/{*.onnx, marblenet_preprocessor.json, export_report.json}
    리턴: 없음
    """
    torch.set_num_threads(1)  # 결정적 실행
    os.makedirs(MB_OUT, exist_ok=True)  # 출력 폴더
    model = load_model()  # 원본 복원
    with open(PREPROC_JSON, "w", encoding="utf-8") as f:  # 전처리 설정 저장
        json.dump(preprocessor_info(model), f, ensure_ascii=False, indent=1)  # 사람이 읽기 쉬운 형식
    sample, _ = gc.load_clip(gc.CLIPS[0])  # 예제 입력(카드는 임의 wav; 여기선 한국어 20 s 클립)
    export_onnx(model, sample)  # 카드 방식 export
    sess = session(ONNX_PATH)  # ONNX 세션
    meta = onnx.load(ONNX_PATH)  # 메타데이터
    report = {"hfRepo": HF_REPO, "hfRevision": HF_REVISION, "nemoToolkit": version("nemo_toolkit"),  # 보고 머리
              "torch": torch.__version__, "onnxruntime": ort.__version__,  # 버전
              "opset": [(o.domain, o.version) for o in meta.opset_import], "tolerance": TOL,  # opset, 허용 오차
              "inputs": [(i.name, str(i.type.tensor_type)) for i in meta.graph.input],  # 입력 정의
              "outputs": [(o.name, str(o.type.tensor_type)) for o in meta.graph.output], "clips": {}}  # 출력 정의
    for clip in gc.CLIPS:  # 클립마다
        audio, _ = gc.load_clip(clip)  # float32 파형(int16/32768)
        probs, logits, ps, pl = nemo_probs(model, audio)  # NeMo 원본
        out = sess.run(["output"], {"processed_signal": ps, "processed_signal_length": pl})[0][0]  # ONNX logits (T, 2)
        oprobs = softmax_speech(out)  # ONNX 확률
        assert oprobs.shape == probs.shape, f"프레임 수 불일치 {clip}"  # 길이 확인
        diff = float(np.max(np.abs(oprobs - probs)))  # 확률 최대 |차|
        ldiff = float(np.max(np.abs(out - logits)))  # logits 최대 |차|
        row = {"totalSamples": len(audio), "seqLen": int(pl[0]), "processedFrames": int(ps.shape[2]),  # 전처리 프레임
               "outputFrames": int(probs.shape[0]), "maxAbsDiffProb": diff, "maxAbsDiffLogits": ldiff}  # 출력·차이
        report["clips"][clip] = row  # 보고에 추가
        print(clip, json.dumps(row))  # 진행 로그
        assert diff <= TOL, f"{clip}: 확률 |차| {diff} > {TOL}"  # 허용 오차 확인
    report["sha256"] = {os.path.basename(p): sha256(p) for p in (ONNX_PATH, PREPROC_JSON)}  # 산출물 해시
    report["sizes"] = {os.path.basename(p): os.path.getsize(p) for p in (ONNX_PATH, PREPROC_JSON)}  # 크기
    with open(os.path.join(MB_OUT, "export_report.json"), "w", encoding="utf-8") as f:  # 보고 저장
        json.dump(report, f, ensure_ascii=False, indent=1)  # 저장
    print("| 파일 | 크기(바이트) | SHA-256 |")  # README 용 표
    for name, h in report["sha256"].items():  # 파일마다
        print(f"| `{name}` | {report['sizes'][name]} | `{h}` |")  # 표 행
    print("OK: MarbleNet ONNX NeMo 대비 최대 |차| ≤", TOL)  # 성공


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
