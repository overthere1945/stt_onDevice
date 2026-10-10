# 파일명: export_firered.py
# 목적: FireRedVAD(오프라인 VAD·스트리밍 VAD) PyTorch 모델을 공식 export 스크립트로 ONNX 변환하고 수치 정합을 검증한다.
# 기능: (1) 고정 commit 의 공식 repo 클론·HF 체크포인트 SHA-256·설치된 fireredvad 패키지 원문 일치를 확인한다.
#       (2) 공식 fireredvad/bin/export_onnx.py 를 --task vad / stream_vad 로 실행해 tools/export/out/firered/ 에
#           fireredvad_vad.onnx, fireredvad_stream_vad.onnx, fireredvad_stream_vad_with_cache.onnx, cmvn.ark 를 만든다.
#       (3) 골든 클립 4개에서 공식 전처리(AudioFeat: fbank80 povey + Kaldi CMVN) → PyTorch DetectModel 확률과
#           ONNX Runtime 확률의 최대 |차| ≤ 1e-4 를 assert 한다(with_cache 모델은 1 프레임씩 캐시를 이어서 실행).
#       (4) 결과(최대 차이·SHA-256)를 out/firered/export_report.json 에 쓰고 표로 출력한다.
# 실행: tools/export/.venv/bin/python -I tools/export/export_firered.py
# change(add)-hyungchul-2026-10-10-14:25

import hashlib  # SHA-256 계산
import json  # 보고 JSON 저장
import os  # 경로
import subprocess  # 공식 export 스크립트 실행
import sys  # 인터프리터 경로·모듈 경로

EXPORT_DIR = os.path.dirname(os.path.abspath(__file__))  # tools/export 폴더
sys.path.insert(0, os.path.join(os.path.dirname(EXPORT_DIR), "golden"))  # tools/golden/golden_common 재사용(-I 실행 대비)

import numpy as np  # 수치 배열
import onnxruntime as ort  # ONNX 추론
import torch  # PyTorch 원본 추론

import fireredvad  # 클론에서 설치한 공식 패키지(위치 확인용)
from fireredvad.core.audio_feat import AudioFeat  # 공식 전처리(fbank + CMVN)
from fireredvad.core.detect_model import DetectModel  # 공식 DFSMN 모델

import golden_common as gc  # 클립 목록·로딩 공통 도우미

# ---- 고정 버전·경로 ----
FIRERED_COMMIT = "c30ec49e8cc69642b0ee65362eba11b9d11c6e54"  # github.com/FireRedTeam/FireRedVAD 고정 commit
HF_REVISION = "7990aaccc6b7aec1e527743bd30201f2c4a03b8c"  # huggingface.co/FireRedTeam/FireRedVAD 고정 revision
OUT_DIR = os.path.join(EXPORT_DIR, "out")  # 산출물 루트(gitignore)
SRC_DIR = os.path.join(OUT_DIR, "src", "FireRedVAD")  # 공식 repo 클론 폴더
MODEL_ROOT = os.path.join(OUT_DIR, "models", "FireRedVAD")  # HF 체크포인트 폴더
FIRERED_OUT = os.path.join(OUT_DIR, "firered")  # ONNX 출력 폴더
TOL = 1e-4  # PyTorch vs ONNX 허용 최대 |차|

# HF 체크포인트 SHA-256(다운로드 시 HF LFS 메타데이터와 같음을 확인한 값)
CKPT_SHA256 = {  # 상대 경로 → SHA-256
    "VAD/model.pth.tar": "63f4fb1b00a6b8607c118dd48efc18d5e40d67d99b7bf9aa7a8d61540cf23d71",  # 오프라인 VAD
    "Stream-VAD/model.pth.tar": "ec88a8ae8ac5f004cdbd20c1eac4a9e9d12067c3d51ac5b141afa1f921fb59c9",  # 스트리밍 VAD
    "VAD/cmvn.ark": "c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473",  # CMVN(두 모델 동일)
    "Stream-VAD/cmvn.ark": "c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473",  # CMVN(두 모델 동일)
}

# 공식 export_onnx.py 의 task → (체크포인트 하위 폴더, 만들어지는 ONNX 파일들)
TASKS = {  # task → (하위 폴더, ONNX 목록)
    "vad": ("VAD", ["fireredvad_vad.onnx"]),  # 오프라인(비스트리밍) VAD
    "stream_vad": ("Stream-VAD", ["fireredvad_stream_vad.onnx", "fireredvad_stream_vad_with_cache.onnx"]),  # 스트리밍 VAD 2종
}


def sha256(path):
    """
    목적: 파일 SHA-256 계산
    기능: 파일을 1 MB 단위로 읽어 해시한다.
    입력: path(파일 경로)
    출력: 없음
    리턴: 16진 문자열
    """
    h = hashlib.sha256()  # 해시 객체
    with open(path, "rb") as f:  # 바이너리 읽기
        for block in iter(lambda: f.read(1 << 20), b""):  # 1 MB 블록
            h.update(block)  # 누적
    return h.hexdigest()  # 16진 문자열


def check_sources():
    """
    목적: 원본(repo·체크포인트·설치 패키지) 고정 상태 확인
    기능: 클론 HEAD 가 FIRERED_COMMIT 인지, 체크포인트 SHA-256 이 기록값인지,
          venv 에 설치된 fireredvad 패키지 소스가 클론의 fireredvad/ 와 바이트 단위로 같은지 assert 한다.
    입력: 없음
    출력: 없음(실패 시 AssertionError)
    리턴: 없음
    """
    head = subprocess.run(["git", "-C", SRC_DIR, "rev-parse", "HEAD"], check=True,  # 클론 HEAD 조회
                          capture_output=True, text=True).stdout.strip()  # 문자열
    assert head == FIRERED_COMMIT, f"FireRedVAD 클론 commit 불일치: {head}"  # commit 고정 확인
    for rel, want in CKPT_SHA256.items():  # 체크포인트마다
        got = sha256(os.path.join(MODEL_ROOT, rel))  # 실제 해시
        assert got == want, f"체크포인트 SHA-256 불일치 {rel}: {got}"  # 해시 확인
    pkg_dir = os.path.dirname(fireredvad.__file__)  # 설치된 패키지 폴더
    for root, _, files in os.walk(os.path.join(SRC_DIR, "fireredvad")):  # 클론 패키지 파일마다
        for name in files:  # 파일
            if not name.endswith(".py"):  # 파이썬 소스만
                continue  # 건너뜀
            src = os.path.join(root, name)  # 클론 파일
            rel = os.path.relpath(src, os.path.join(SRC_DIR, "fireredvad"))  # 패키지 내 상대 경로
            with open(src, "rb") as a, open(os.path.join(pkg_dir, rel), "rb") as b:  # 두 파일 열기
                assert a.read() == b.read(), f"설치 패키지가 클론과 다름: {rel}"  # 바이트 일치


def run_official_export():
    """
    목적: 공식 export_onnx.py 실행
    기능: 클론의 fireredvad/bin/export_onnx.py 를 격리 모드(-I)로 task 마다 실행한다(--model-dir 지정 → 다운로드 안 함).
          스크립트는 onnxsim 단순화·onnx.checker 검사 후 cmvn.ark 를 출력 폴더에 복사한다.
    입력: 없음
    출력: FIRERED_OUT/*.onnx, FIRERED_OUT/cmvn.ark
    리턴: 없음
    """
    script = os.path.join(SRC_DIR, "fireredvad", "bin", "export_onnx.py")  # 공식 스크립트
    os.makedirs(FIRERED_OUT, exist_ok=True)  # 출력 폴더
    for task, (subdir, _) in TASKS.items():  # task 마다
        cmd = [sys.executable, "-I", script, "--task", task,  # 공식 스크립트 호출
               "--model-dir", os.path.join(MODEL_ROOT, subdir), "--output-dir", FIRERED_OUT]  # 입력·출력 폴더
        print("$", " ".join(cmd))  # 실행 명령 기록
        subprocess.run(cmd, check=True, cwd=OUT_DIR)  # 실패 시 예외


def session(path):
    """
    목적: 결정적 ONNX 세션 생성
    기능: CPU EP, 스레드 1 로 InferenceSession 을 만든다.
    입력: path(ONNX 경로)
    출력: 없음
    리턴: onnxruntime.InferenceSession
    """
    so = ort.SessionOptions()  # 세션 옵션
    so.intra_op_num_threads = 1  # 스레드 1(결정적)
    so.inter_op_num_threads = 1  # 스레드 1
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])  # 세션


def onnx_full(sess, feats):
    """
    목적: 캐시 없는 ONNX 를 전체 특징으로 1회 실행
    기능: feat (1, T, 80) 을 넣고 probs (1, T, 1) 을 (T,) 로 돌려준다.
    입력: sess(세션), feats((T, 80) float32 ndarray)
    출력: 없음
    리턴: (T,) float32 ndarray
    """
    out = sess.run(["probs"], {"feat": feats[None, :, :]})[0]  # 추론
    return out[0, :, 0]  # (T,)


def onnx_with_cache(sess, feats, chunk=1):
    """
    목적: 캐시 입출력 ONNX 를 chunk 프레임씩 이어서 실행(스트리밍 재현)
    기능: caches_in 을 0 (8,1,128,19) 으로 시작해 매 호출의 caches_out 을 다음 caches_in 으로 넘긴다.
    입력: sess(세션), feats((T, 80) float32), chunk(호출당 프레임 수)
    출력: 없음
    리턴: (T,) float32 ndarray
    """
    shape = [d for d in sess.get_inputs()[1].shape]  # caches_in 고정 shape [8,1,128,19]
    caches = np.zeros(shape, dtype=np.float32)  # 첫 캐시 0
    probs = []  # 결과 모음
    for s in range(0, feats.shape[0], chunk):  # chunk 단위
        p, caches = sess.run(["probs", "caches_out"], {"feat": feats[None, s:s + chunk, :], "caches_in": caches})  # 추론+캐시 갱신
        probs.append(p[0, :, 0])  # (chunk,)
    return np.concatenate(probs)  # (T,)


def main():
    """
    목적: FireRedVAD ONNX 변환·검증 진입점
    기능: 원본 확인 → 공식 export → 클립 4개에서 PyTorch vs ONNX 최대 |차| 계산·assert → 보고 JSON·SHA-256 표 출력.
    입력: 없음
    출력: out/firered/*.onnx, cmvn.ark, export_report.json, 콘솔 표
    리턴: 없음
    """
    torch.set_num_threads(1)  # PyTorch 결정적 실행
    check_sources()  # 원본 고정 확인
    run_official_export()  # 공식 export 실행
    cmvn_path = os.path.join(FIRERED_OUT, "cmvn.ark")  # 복사된 CMVN
    assert sha256(cmvn_path) == CKPT_SHA256["VAD/cmvn.ark"], "복사된 cmvn.ark 해시 불일치"  # 원본과 동일 확인
    feat = AudioFeat(cmvn_path)  # 공식 전처리(두 모델 공통 CMVN)
    torch_models = {sub: DetectModel.from_pretrained(os.path.join(MODEL_ROOT, sub)) for sub in ("VAD", "Stream-VAD")}  # 원본 모델
    sessions = {name: session(os.path.join(FIRERED_OUT, name)) for _, names in TASKS.values() for name in names}  # 우리 ONNX
    prebuilt_dir = os.path.join(SRC_DIR, "pretrained_models", "onnx_models")  # repo 에 동봉된 사전 변환본(참고 비교)
    prebuilt = {name: session(os.path.join(prebuilt_dir, name)) for name in sessions}  # 같은 이름 3개
    report = {"fireredCommit": FIRERED_COMMIT, "hfRevision": HF_REVISION, "tolerance": TOL,  # 보고 머리
              "torch": torch.__version__, "onnxruntime": ort.__version__, "clips": {}}  # 버전
    for clip in gc.CLIPS:  # 클립마다
        _, pcm = gc.load_clip(clip)  # int16 파형(공식 코드는 int16 값을 그대로 fbank 에 넣는다)
        feats_t, _ = feat.extract(pcm)  # 공식 전처리 결과 (T, 80) float32 텐서
        feats = feats_t.numpy()  # numpy 로
        ref = {sub: m.forward(feats_t.unsqueeze(0))[0][0, :, 0].numpy() for sub, m in torch_models.items()}  # PyTorch 확률
        runs = {  # ONNX 실행 결과(모델 → 확률)
            "fireredvad_vad.onnx": onnx_full(sessions["fireredvad_vad.onnx"], feats),  # 오프라인 전체
            "fireredvad_stream_vad.onnx": onnx_full(sessions["fireredvad_stream_vad.onnx"], feats),  # 스트리밍 전체(캐시 출력 무시)
            "fireredvad_stream_vad_with_cache.onnx": onnx_with_cache(sessions["fireredvad_stream_vad_with_cache.onnx"], feats, 1),  # 1 프레임씩
        }
        chunk100 = onnx_with_cache(sessions["fireredvad_stream_vad_with_cache.onnx"], feats, 100)  # 100 프레임씩(청크 독립성 확인)
        row = {"numFrames": int(feats.shape[0])}  # 클립 보고
        for name, probs in runs.items():  # 모델마다
            sub = "VAD" if name == "fireredvad_vad.onnx" else "Stream-VAD"  # 기준 PyTorch 모델
            diff = float(np.max(np.abs(probs - ref[sub])))  # 최대 |차|
            pre = onnx_with_cache(prebuilt[name], feats, 1) if "with_cache" in name else onnx_full(prebuilt[name], feats)  # repo 동봉본
            row[name] = {"maxAbsDiffVsTorch": diff,  # 우리 ONNX vs PyTorch
                         "prebuiltMaxAbsDiffVsTorch": float(np.max(np.abs(pre - ref[sub])))}  # 동봉본 vs PyTorch(참고)
            assert probs.shape == ref[sub].shape, f"프레임 수 불일치 {name} {clip}"  # 길이 확인
            assert diff <= TOL, f"{name} {clip}: |차| {diff} > {TOL}"  # 허용 오차 확인
        diff100 = float(np.max(np.abs(chunk100 - ref["Stream-VAD"])))  # 100 프레임 청크 결과 차
        row["fireredvad_stream_vad_with_cache.onnx"]["chunk100MaxAbsDiffVsTorch"] = diff100  # 기록
        assert diff100 <= TOL, f"with_cache chunk100 {clip}: {diff100}"  # 허용 오차 확인
        report["clips"][clip] = row  # 보고에 추가
        print(clip, json.dumps(row, ensure_ascii=False))  # 진행 로그
    report["sha256"] = {name: sha256(os.path.join(FIRERED_OUT, name))  # 산출물 해시
                        for name in sorted(os.listdir(FIRERED_OUT)) if name.endswith((".onnx", ".ark"))}  # ONNX·CMVN
    report["sizes"] = {name: os.path.getsize(os.path.join(FIRERED_OUT, name)) for name in report["sha256"]}  # 크기
    with open(os.path.join(FIRERED_OUT, "export_report.json"), "w", encoding="utf-8") as f:  # 보고 저장
        json.dump(report, f, ensure_ascii=False, indent=1)  # 사람이 읽을 수 있게
    print("| 파일 | 크기(바이트) | SHA-256 |")  # README 용 표 머리
    for name, h in report["sha256"].items():  # 파일마다
        print(f"| `{name}` | {report['sizes'][name]} | `{h}` |")  # 표 행
    print("OK: FireRedVAD ONNX 3종 PyTorch 대비 최대 |차| ≤", TOL)  # 성공


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
