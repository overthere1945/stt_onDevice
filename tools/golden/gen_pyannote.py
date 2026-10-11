# 파일명: gen_pyannote.py
# 목적: pyannote VAD 골든 데이터 생성(Kotlin PyannoteVad·PyannoteBinarize 포팅 검증 기준)
# 기능: sherpa-onnx 변환본 segmentation-3.0 ONNX(model.onnx, fp32)를 pyannote.audio 의 실제 코드 경로
#       (VoiceActivityDetection pipeline → Inference.slide → Powerset 변환 → max-over-speakers → Inference.aggregate
#        → 마지막 창 패딩 crop → Binarize)에 끼워 넣어 speech 점수와 구간을 만든다.
#       ONNX 를 감싼 Model 하위 클래스(OnnxPyanNet)만 직접 만들고 나머지는 라이브러리 코드를 그대로 쓴다.
#       README 에 문서화한 알고리즘을 numpy 로 독립 구현(reference_scores)해 라이브러리 결과와 같은지 assert 한다.
#       HF 원본 체크포인트는 gated(토큰 없음)라 원본 pipeline 비교(expected_original)는 수행하지 않는다.
# change(add)-hyungchul-2026-10-10-12:19

import os  # 경로
import sys  # 모듈 경로 추가

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 golden_common 을 찾게 함

import numpy as np  # 수치 배열
import onnxruntime as ort  # ONNX 추론
import torch  # pyannote 입력 텐서
from pyannote.audio import __version__ as PYA_VERSION  # pyannote.audio 버전
from pyannote.audio.core.task import Problem, Resolution, Specifications  # 모델 사양
from pyannote.audio.models.segmentation import PyanNet  # 수용 영역 계산용 원본 구조
from pyannote.audio.pipelines import VoiceActivityDetection  # 원본 VAD pipeline
from pyannote.audio.utils.signal import Binarize  # 원본 이진화

import golden_common as gc  # 공통 도우미

MODEL_PATH = os.path.join(gc.MODELS_DIR, "pyannote_seg30", "sherpa-onnx-pyannote-segmentation-3-0", "model.onnx")  # fp32 ONNX
TOOL = f"pyannote.audio {PYA_VERSION} (VoiceActivityDetection/Inference/Binarize) + sherpa-onnx-pyannote-segmentation-3-0 model.onnx"  # 도구 표기


def make_session(path):
    """
    목적: ONNX 세션 생성
    기능: CPU, 스레드 1개로 결정적인 세션을 만든다.
    입력: path(ONNX 경로)
    출력: 없음
    리턴: onnxruntime.InferenceSession
    """
    opts = ort.SessionOptions()  # 세션 옵션
    opts.inter_op_num_threads = 1  # 스레드 1
    opts.intra_op_num_threads = 1  # 스레드 1
    return ort.InferenceSession(path, sess_options=opts, providers=["CPUExecutionProvider"])  # CPU 세션


class OnnxPyanNet(PyanNet):
    """
    목적: pyannote Inference 에 ONNX 모델을 끼워 넣기 위한 Model
    기능: PyanNet 구조(SincNet 수용 영역 계산)를 그대로 상속하고 forward 만 ONNX 추론으로 바꾼다.
          사양은 segmentation-3.0 과 같게(powerset 3화자/최대 2, 10 s, warm_up 0) 직접 지정한다.
    """

    def __init__(self, onnx_path):
        """
        목적: 모델 생성
        기능: ONNX 세션·메타데이터를 읽고 사양을 지정한 뒤 수용 영역이 메타데이터와 같은지 확인한다.
        입력: onnx_path(ONNX 경로)
        출력: 없음
        리턴: 없음
        """
        super().__init__(sample_rate=gc.SAMPLE_RATE, num_channels=1)  # PyanNet 기본 구조(가중치 미사용)
        self.session = make_session(onnx_path)  # ONNX 세션
        self.meta = self.session.get_modelmeta().custom_metadata_map  # sherpa 메타데이터
        num_speakers = int(self.meta["num_speakers"])  # 화자 수(3)
        self.specifications = Specifications(  # segmentation-3.0 사양
            problem=Problem.MONO_LABEL_CLASSIFICATION,  # powerset 다중 클래스
            resolution=Resolution.FRAME,  # 프레임 단위 출력
            duration=int(self.meta["window_size"]) / gc.SAMPLE_RATE,  # 10.0 s
            warm_up=(0.0, 0.0),  # warm-up 없음(Specifications 기본값)
            classes=[f"speaker#{i + 1}" for i in range(num_speakers)],  # 화자 클래스
            powerset_max_classes=int(self.meta["powerset_max_classes"]),  # 동시 최대 2
            permutation_invariant=True,  # 화자 순서 무관
        )
        rf = self.receptive_field  # pyannote 가 계산한 프레임 SlidingWindow
        assert round(rf.duration * gc.SAMPLE_RATE) == int(self.meta["receptive_field_size"]), "수용 영역 크기 불일치"  # 991
        assert round(rf.step * gc.SAMPLE_RATE) == int(self.meta["receptive_field_shift"]), "수용 영역 이동 불일치"  # 270

    def forward(self, waveforms):
        """
        목적: 창 묶음 추론
        기능: (batch, 1, 160000) 텐서를 창 하나씩 ONNX 로 추론해 (batch, 589, 7) log-softmax 를 돌려준다.
              Kotlin 구현과 같게 배치 1 로 실행한다.
        입력: waveforms(torch.Tensor)
        출력: 없음
        리턴: torch.Tensor (batch, frames, 7)
        """
        outs = []  # 창별 출력
        for w in waveforms:  # 창마다
            (y,) = self.session.run(["y"], {"x": w[None].cpu().numpy().astype(np.float32)})  # (1, 589, 7)
            outs.append(y)  # 기록
        return torch.from_numpy(np.concatenate(outs, axis=0))  # 배치로 합침


def reference_scores(audio, session, meta):
    """
    목적: README 에 문서화한 집계 알고리즘의 독립 구현(Kotlin 포팅 기준)
    기능: 10 s 창/1 s step 으로 자르고(마지막 불완전 창은 0 패딩), 창마다 argmax≠0(어느 화자든 활성)을 speech 로,
          hamming 가중 평균으로 겹침 집계한 뒤, 마지막 창을 패딩했으면 오디오 길이까지 loose crop 한다.
    입력: audio(float32 파형), session(ONNX 세션), meta(메타데이터)
    출력: 없음
    리턴: (speech 점수 ndarray float32, 프레임 중앙 시각 ndarray)
    """
    sr = gc.SAMPLE_RATE  # 샘플레이트
    win = int(meta["window_size"])  # 창 크기 160000
    step = sr  # step 1 s = 16000 샘플(Inference 기본 0.1×duration)
    rf_size = int(meta["receptive_field_size"]) / sr  # 프레임 길이 991/16000 s
    rf_step = int(meta["receptive_field_shift"]) / sr  # 프레임 간격 270/16000 s
    n = len(audio)  # 샘플 수
    num_chunks = (n - win) // step + 1 if n >= win else 0  # 완전한 창 수
    has_last = (n < win) or ((n - win) % step > 0)  # 불완전한 마지막 창 존재 여부
    chunks = [audio[c * step:c * step + win] for c in range(num_chunks)]  # 완전한 창
    if has_last:  # 마지막 창이 있으면
        last = audio[num_chunks * step:]  # 남은 부분
        chunks.append(np.pad(last, (0, win - len(last))))  # 0 패딩
    speech = []  # 창별 speech(0/1)
    for ch in chunks:  # 창마다
        (y,) = session.run(["y"], {"x": ch.astype(np.float32)[None, None, :]})  # (1, F, 7)
        speech.append((np.argmax(y[0], axis=-1) != 0).astype(np.float32))  # powerset 0(무음) 외 = speech
    fpc = len(speech[0])  # 창당 프레임 수(589)
    hamming = np.hamming(fpc)  # 가중치 0.54-0.46cos(2πk/(F-1))
    total_chunks = len(chunks)  # 전체 창 수
    num_frames = int(np.rint(((total_chunks - 1) * 1.0 + 10.0) / rf_step)) + 1  # closest_frame(마지막 창 끝)+1
    acc = np.zeros(num_frames, dtype=np.float32)  # 가중 합
    cnt = np.zeros(num_frames, dtype=np.float32)  # 가중치 합
    for c, sp in enumerate(speech):  # 창마다
        s0 = int(np.rint((c * 1.0) / rf_step))  # 창 시작 프레임 = rint(c·1 s / 프레임 간격)
        acc[s0:s0 + fpc] += sp * hamming  # 가중 점수 누적(float32 누적)
        cnt[s0:s0 + fpc] += hamming  # 가중치 누적
    avg = acc / np.maximum(cnt, 1e-12)  # 가중 평균
    if has_last:  # 패딩한 창이 있었으면
        j = int(np.floor((n / sr) / rf_step))  # loose crop: start+j·step <= 오디오 끝 인 최대 j
        avg = avg[:min(j + 1, num_frames)]  # 앞에서부터 j+1 프레임만 유지
    times = np.arange(len(avg)) * rf_step + rf_size / 2.0  # 프레임 중앙 시각
    return avg.astype(np.float32), times  # 반환


def annotation_pairs(ann):
    """
    목적: pyannote Annotation 을 (start, end) 목록으로 변환
    기능: 모든 트랙 구간을 시작 시각 순으로 정렬해 돌려준다.
    입력: ann(Annotation)
    출력: 없음
    리턴: [(start, end)] 리스트
    """
    return sorted((seg.start, seg.end) for seg, _ in ann.itertracks())  # 시작순 정렬


def main():
    """
    목적: pyannote 골든 생성 진입점
    기능: 클립마다 원본 pipeline 경로로 speech 점수를 만들고, 독립 구현과 비교한 뒤 프리셋 4종 Binarize 결과를 쓴다.
          PYA3_DEFAULT 는 pipeline.apply() 전체 결과와도 같은지 확인한다.
    입력: 없음
    출력: golden/PYANNOTE_*.json, golden/scores_PYANNOTE_*.json
    리턴: 없음
    """
    model = OnnxPyanNet(MODEL_PATH)  # ONNX 를 감싼 모델
    pipeline = VoiceActivityDetection(segmentation=model)  # 원본 VAD pipeline(pre_aggregation_hook=max)
    pipeline.instantiate({"min_duration_on": 0.0, "min_duration_off": 0.0})  # segmentation-3.0 기본값
    hop = model.receptive_field.step  # 프레임 간격 0.016875 s
    for clip in gc.CLIPS:  # 클립마다
        audio, _ = gc.load_clip(clip)  # float32 파형
        file = {"waveform": torch.from_numpy(audio)[None, :], "sample_rate": gc.SAMPLE_RATE, "uri": clip}  # pyannote 입력
        seg = pipeline._segmentation(file)  # 원본 Inference 결과(SlidingWindowFeature, (F,1))
        scores = seg.data[:, 0].astype(np.float32)  # speech 점수
        frames = seg.sliding_window  # 프레임 SlidingWindow(start 0)
        times = np.array([frames[i].middle for i in range(len(scores))])  # 프레임 중앙 시각(Binarize 기준)
        ref, ref_times = reference_scores(audio, model.session, model.meta)  # 독립 구현
        assert len(ref) == len(scores), f"프레임 수 불일치 {len(ref)} vs {len(scores)}"  # 길이 일치
        assert float(np.max(np.abs(ref - scores))) <= 1e-6, "독립 구현 점수가 라이브러리와 다름"  # 점수 일치
        assert float(np.max(np.abs(ref_times - times))) <= 1e-9, "프레임 시각 불일치"  # 시각 일치
        full = annotation_pairs(pipeline(file))  # pipeline.apply 전체 결과(PYA3_DEFAULT)
        for pid, (onset, offset, min_on, min_off) in gc.PYANNOTE_PRESETS.items():  # 프리셋마다
            binarize = Binarize(onset=onset, offset=offset, min_duration_on=min_on, min_duration_off=min_off)  # 원본 이진화
            pairs = annotation_pairs(binarize(seg))  # 구간
            if pid == "PYA3_DEFAULT":  # 기본 프리셋이면
                assert pairs == full, "Binarize 결과가 pipeline.apply 결과와 다름"  # pipeline 과 일치 확인
            if clip.startswith("synth_"):  # 합성 클립이면
                print("  synth", pid, pairs)  # 참고 출력
            gc.write_golden(  # 골든 저장
                "PYANNOTE", pid, clip, hop, scores, pairs, TOOL, frame_times_sec=times,  # 기본 필드 + 프레임 시각
                extra={"params": {"onset": onset, "offset": offset, "minDurationOn": min_on, "minDurationOff": min_off},  # 파라미터
                       "totalSamples": len(audio)},  # change(add)-hyungchul-2026-10-10-12:52 전체 샘플 수
            )
            print(clip, pid, len(pairs), [(round(s, 3), round(e, 3)) for s, e in pairs[:3]])  # 진행 로그
        gc.write_scores("PYANNOTE", clip, hop, scores, TOOL, frame_times_sec=times,  # 모델 추론 정합용 점수
                        extra={"windowSamples": int(model.meta["window_size"]), "stepSamples": gc.SAMPLE_RATE,  # 창/step
                               "totalSamples": len(audio),  # change(add)-hyungchul-2026-10-10-12:52 전체 샘플 수
                               "receptiveFieldSize": int(model.meta["receptive_field_size"]),  # 프레임 길이(샘플)
                               "receptiveFieldShift": int(model.meta["receptive_field_shift"])})  # 프레임 간격(샘플)


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
