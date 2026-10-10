# 파일명: gen_silero.py
# 목적: Silero VAD 골든 데이터 생성(Kotlin SileroTimestamps·SileroVad 포팅 검증 기준)
# 기능: silero-vad 6.2.3 공식 ONNX(models/silero_v6.2.3/silero_vad.onnx)를 라이브러리 OnnxWrapper 로 실행하면서
#       get_speech_timestamps(return_seconds=False) 가 내부에서 계산한 512-hop 확률을 그대로 가로채 저장하고,
#       Silero 프리셋 8종 × 클립 4종 결과(샘플·초)를 골든 JSON 으로 쓴다.
#       같은 확률을 get_speech_timestamps_from_probs 에 다시 넣어 라이브러리 결과와 완전히 같은지 assert 한다.
# change(add)-hyungchul-2026-10-10-12:19

import os  # 경로
import sys  # 모듈 경로 추가

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 golden_common 을 찾게 함

import torch  # silero 입력 텐서
from silero_vad import __version__ as SILERO_VERSION  # 설치 버전
from silero_vad import get_speech_timestamps, get_speech_timestamps_from_probs  # 라이브러리 후처리
from silero_vad.utils_vad import OnnxWrapper  # 라이브러리 ONNX 래퍼

import golden_common as gc  # 공통 도우미

MODEL_PATH = os.path.join(gc.MODELS_DIR, "silero_v6.2.3", "silero_vad.onnx")  # 다운로드한 공식 ONNX
WINDOW = 512  # 16 kHz 창 크기(샘플) = hop
TOOL = f"silero-vad {SILERO_VERSION} (get_speech_timestamps, OnnxWrapper silero_vad.onnx v6.2.3)"  # 도구 표기


class RecordingModel:
    """
    목적: 라이브러리가 쓰는 확률을 가로채는 모델 래퍼
    기능: OnnxWrapper 호출을 그대로 위임하고 반환 확률(.item())을 probs 에 순서대로 기록한다.
    """

    def __init__(self, inner):
        """
        목적: 래퍼 생성
        기능: 내부 OnnxWrapper 를 보관하고 기록 목록을 비운다.
        입력: inner(OnnxWrapper)
        출력: 없음
        리턴: 없음
        """
        self.inner = inner  # 실제 모델
        self.probs = []  # 기록된 확률

    def reset_states(self, batch_size=1):
        """
        목적: 상태 초기화 위임
        기능: get_speech_timestamps 시작 시 호출되며 내부 상태와 기록을 모두 초기화한다.
        입력: batch_size
        출력: 없음
        리턴: 없음
        """
        self.inner.reset_states(batch_size)  # 내부 상태 초기화
        self.probs = []  # 기록 초기화

    def __call__(self, x, sr):
        """
        목적: 한 창 추론 위임 + 기록
        기능: 내부 모델 출력(텐서)을 돌려주면서 float 값을 기록한다.
        입력: x(512 샘플 텐서), sr(샘플레이트)
        출력: 없음
        리턴: 모델 출력 텐서
        """
        out = self.inner(x, sr)  # 실제 추론
        self.probs.append(out.item())  # 확률 기록
        return out  # 그대로 반환


def main():
    """
    목적: Silero 골든 생성 진입점
    기능: 클립마다 프리셋 8종을 라이브러리로 실행해 골든을 쓰고, 확률 재사용 경로 일치·합성 클립 무음 미포함을 assert 한다.
    입력: 없음
    출력: golden/SILERO_*.json, golden/scores_SILERO_*.json
    리턴: 없음
    """
    model = RecordingModel(OnnxWrapper(MODEL_PATH, force_onnx_cpu=True))  # 공식 ONNX + 기록 래퍼
    for clip in gc.CLIPS:  # 클립마다
        audio, _ = gc.load_clip(clip)  # float32 파형
        wav = torch.from_numpy(audio)  # 라이브러리 입력 텐서
        n = len(audio)  # 전체 샘플 수
        first_probs = None  # 첫 프리셋의 확률(이후 프리셋과 동일해야 함)
        for pid, (th, min_sp, min_sil, pad, max_sp) in gc.SILERO_PRESETS.items():  # 프리셋마다
            ts = get_speech_timestamps(  # 라이브러리 원본 호출
                wav, model, threshold=th, sampling_rate=gc.SAMPLE_RATE,  # 임계/샘플레이트
                min_speech_duration_ms=min_sp, max_speech_duration_s=max_sp,  # 최소/최대 발화
                min_silence_duration_ms=min_sil, speech_pad_ms=pad, return_seconds=False,  # 무음/pad/샘플 단위
            )
            probs = list(model.probs)  # 이번 호출에서 쓰인 확률
            assert len(probs) == -(-n // WINDOW), "확률 개수 = ceil(N/512) 이어야 함"  # 마지막 창 0 패딩 포함
            if first_probs is None:  # 첫 프리셋이면
                first_probs = probs  # 보관
            assert probs == first_probs, "프리셋마다 확률이 달라짐(상태 초기화 문제)"  # 결정적 추론 확인
            again = get_speech_timestamps_from_probs(  # 확률 재사용 경로
                probs, sampling_rate=gc.SAMPLE_RATE, threshold=th,  # 임계
                min_speech_duration_ms=min_sp, max_speech_duration_s=max_sp,  # 최소/최대 발화
                min_silence_duration_ms=min_sil, speech_pad_ms=pad,  # 무음/pad
                return_seconds=False, audio_length_samples=n,  # 샘플 단위, 실제 길이
            )
            assert again == ts, f"from_probs 경로가 라이브러리 결과와 다름: {pid} {clip}"  # 완전 일치 확인
            samples = [[int(s["start"]), int(s["end"])] for s in ts]  # 샘플 단위 구간
            pairs = [(s / gc.SAMPLE_RATE, e / gc.SAMPLE_RATE) for s, e in samples]  # 초 단위 변환
            if clip.startswith("synth_"):  # 합성 클립이면
                for s, e in pairs:  # 모든 구간이
                    assert s >= 2.0, f"합성 클립 무음(0–2 s)이 구간에 포함됨: {pid} {s}-{e}"  # 앞 무음 미포함 확인
            gc.write_golden(  # 골든 저장
                "SILERO", pid, clip, WINDOW / gc.SAMPLE_RATE, probs, pairs, TOOL,  # 기본 필드
                extra={  # 추가 필드
                    "expectedSamples": samples,  # 샘플 단위 정답(1 샘플 허용 비교용)
                    "totalSamples": n,  # 전체 샘플 수(audio_length_samples)
                    "windowSamples": WINDOW,  # 창 크기
                    "params": {"threshold": th, "minSpeechMs": min_sp, "minSilenceMs": min_sil,  # 사용 파라미터
                               "padMs": pad, "maxSpeechSec": gc.json_max(max_sp)},  # inf 는 null
                },
            )
            print(clip, pid, len(samples), samples[:3])  # 진행 로그
        gc.write_scores("SILERO", clip, WINDOW / gc.SAMPLE_RATE, first_probs, TOOL,  # 모델 추론 정합용 확률
                        extra={"totalSamples": n, "windowSamples": WINDOW, "contextSamples": 64})  # 창/문맥 크기


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
