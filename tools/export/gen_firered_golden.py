# 파일명: gen_firered_golden.py
# 목적: FireRedVAD 골든 데이터 생성(Kotlin FireRedVad 전처리·추론·FireRedPostprocess 포팅 검증 기준)
# 기능: 공식 repo(fireredvad, commit 고정) Python 코드를 그대로 실행한다.
#       - FIRERED_OFFLINE: FireRedVad.from_pretrained(VAD) + FireRedVadConfig(프리셋 값).detect(int16 파형)
#       - FIRERED_STREAM : FireRedStreamVad.from_pretrained(Stream-VAD) + FireRedStreamVadConfig(프리셋 값).detect_full(int16 파형)
#       결과 구간(초)·프레임 구간·후처리 단계별 결정 구간·PyTorch 프레임 확률을 FIRERED_<preset>_<clip>.json 에,
#       export_firered.py 가 만든 ONNX 의 확률을 scores_FIRERED_<clip>.json(오프라인)·scores_FIRERED_STREAM_<clip>.json(스트리밍) 에,
#       공식 fbank(povey 창, CMVN 전) 앞 50 프레임을 firered_fbank_<clip>.json, CMVN 후(모델 입력) 앞 50 프레임을
#       firered_fbank_cmvn_<clip>.json 에, cmvn.ark 해석값(mean·inverse std)을 firered_cmvn.json 에 쓰고 cmvn.ark 를 복사한다.
#       독립 구현(kaldi-native-fbank 직접 실행 + numpy CMVN)이 공식 결과와 완전히 같은지 assert 한다.
#       실제 클립에서는 20 s 초과 구간이 없어 max_speech 분기가 실행되지 않으므로, 손작성 확률(synthetic_firered_long)로
#       공식 후처리를 실행한 분할 골든 2개(오프라인·스트리밍)를 추가로 쓴다.
# 실행: tools/export/.venv/bin/python -I tools/export/gen_firered_golden.py  (export_firered.py 먼저 실행)
# change(add)-hyungchul-2026-10-10-14:25

import json  # 기존 FSMN fbank 골든 읽기
import os  # 경로
import shutil  # cmvn.ark 복사
import sys  # 모듈 경로 추가
from importlib.metadata import version  # 패키지 버전

EXPORT_DIR = os.path.dirname(os.path.abspath(__file__))  # tools/export 폴더
sys.path.insert(0, os.path.join(os.path.dirname(EXPORT_DIR), "golden"))  # tools/golden/golden_common 재사용(-I 실행 대비)
sys.path.insert(0, EXPORT_DIR)  # export_firered 상수 재사용

import kaldi_native_fbank as knf  # Kaldi 호환 fbank(독립 실행용)
import kaldiio  # Kaldi ark 읽기(독립 CMVN 해석)
import numpy as np  # 수치 배열
import torch  # 스레드 고정

from fireredvad import FireRedVad, FireRedVadConfig  # 공식 오프라인 VAD
from fireredvad import FireRedStreamVad, FireRedStreamVadConfig  # 공식 스트리밍 VAD

import export_firered as ef  # 경로·commit·ONNX 실행 함수 재사용
import golden_common as gc  # 골든 JSON 공통 형식

HOP = 0.010  # 프레임 간격(초) = FRAME_SHIFT_S
FEAT_FRAMES = 50  # 저장할 특징 프레임 수
CMVN_ARK = os.path.join(ef.FIRERED_OUT, "cmvn.ark")  # export 가 복사한 CMVN(원본과 해시 동일)
TOOL = (f"fireredvad {ef.FIRERED_COMMIT[:7]} (github FireRedTeam/FireRedVAD) + HF FireRedTeam/FireRedVAD {ef.HF_REVISION[:7]}; "
        f"torch {torch.__version__}; kaldi-native-fbank {version('kaldi-native-fbank')}")  # 도구 표기

# ---- 프리셋 값: app/src/main/java/com/cowork/stt_ondevice/vad/VadPresets.kt 와 동일해야 한다 ----
# FireRedParams(mode, threshold, smoothWindow, minSpeech, maxSpeech, minSilence, mergeSilence, extend, padStart)
FIRERED_OFFLINE = dict(smooth_window_size=5, speech_threshold=0.4, min_speech_frame=20, max_speech_frame=2000,  # 오프라인
                       min_silence_frame=20, merge_silence_frame=0, extend_speech_frame=0)  # vad.py 예시 값과 동일
FIRERED_STREAM = dict(smooth_window_size=0, speech_threshold=0.5, pad_start_frame=5, min_speech_frame=8,  # 스트리밍(smoothWindow 0)
                      max_speech_frame=2000, min_silence_frame=20)  # stream_vad.py 값
CHUNK_MAX_FRAME = 30000  # 공식 기본값(300 s). 골든 클립은 모두 이보다 짧아 분할 없음


def runs(decisions):
    """
    목적: 0/1 결정 열을 프레임 구간 목록으로 압축
    기능: 연속 1 구간을 [시작 프레임, 끝 프레임(배타)] 로 모은다.
    입력: decisions(0/1 시퀀스)
    출력: 없음
    리턴: [[start, end), ...] 리스트
    """
    out, start = [], None  # 결과, 현재 구간 시작
    for t, d in enumerate(decisions):  # 프레임마다
        if d == 1 and start is None:  # 구간 시작
            start = t  # 기록
        elif d == 0 and start is not None:  # 구간 끝
            out.append([start, t])  # 추가
            start = None  # 초기화
    if start is not None:  # 끝까지 음성이면
        out.append([start, len(decisions)])  # 마지막 구간
    return out  # 반환


def offline_stages(pp, probs):
    """
    목적: 오프라인 후처리 단계별 결과 기록(Kotlin 단계별 디버깅용)
    기능: VadPostprocessor.process 와 같은 순서로 공식 메서드를 하나씩 호출해 단계별 결정 구간을 모으고,
          최종 결과가 process() 결과와 같은지 assert 한다. 평활 확률과 임계값의 최소 거리도 돌려준다.
    입력: pp(VadPostprocessor), probs(파이썬 float 리스트)
    출력: 없음
    리턴: (최종 결정 리스트, 단계별 구간 dict, 최소 |평활 확률 − 임계값|)
    """
    smoothed = pp._smooth_prob(probs)  # 1) 이동 평균(앞 window−1 프레임은 누적 평균)
    binary = pp._apply_threshold(smoothed)  # 2) 평활 확률 ≥ threshold
    sm = pp._smooth_preds_with_state_machine(binary)  # 3) min_speech/min_silence 상태기계
    fixed = pp._fix_smooth_window_start(sm)  # 4) 시작점 앞 smooth_window 프레임 보정
    merged = pp._merge_short_silence_segments(fixed)  # 5) 짧은 무음 병합(merge 0 이면 그대로)
    extended = pp._extend_speech_segments(merged)  # 6) 앞뒤 확장(extend 0 이면 그대로)
    final = pp._split_long_speech_segments(extended, probs)  # 7) max_speech 초과 구간 분할
    assert final == pp.process(probs), "단계별 호출 결과 ≠ process()"  # 공식 process 와 동일 확인
    stages = {"binary": runs(binary), "stateMachine": runs(sm), "fixSmoothWindowStart": runs(fixed),  # 단계별 구간
              "mergeShortSilence": runs(merged), "extendSpeech": runs(extended), "splitLongSpeech": runs(final)}  # 단계별 구간
    margin = float(np.min(np.abs(np.asarray(smoothed) - pp.prob_threshold)))  # 임계값 경계 여유(작으면 Kotlin 오차 위험)
    return final, stages, margin  # 반환


def stream_events(frame_results):
    """
    목적: 스트리밍 프레임 결과에서 시작/끝 이벤트만 추출
    기능: is_speech_start 또는 is_speech_end 인 프레임의 (frame_idx, start?, end?, speech_start_frame, speech_end_frame) 을 모은다(1-based).
    입력: frame_results(StreamVadFrameResult 리스트)
    출력: 없음
    리턴: [[frameIdx, isStart(0/1), isEnd(0/1), speechStartFrame, speechEndFrame], ...]
    """
    return [[r.frame_idx, int(r.is_speech_start), int(r.is_speech_end), r.speech_start_frame, r.speech_end_frame]  # 이벤트 행
            for r in frame_results if r.is_speech_start or r.is_speech_end]  # 이벤트 프레임만


def independent_features(pcm, cmvn_stats):
    """
    목적: 공식 전처리의 독립 재현(Kotlin 포팅 기준)
    기능: kaldi-native-fbank 를 povey 창·dither 0·snip_edges·80 mel 로 직접 실행(입력 = int16 값 그대로)하고,
          Kaldi CMVN 통계(2×81 double)로 mean = sum/count, var = sumsq/count − mean², floor 1e-20, istd = 1/sqrt(var)
          를 계산해 (x − mean) × istd (float64) → float32 를 만든다.
    입력: pcm(int16 ndarray), cmvn_stats((2, 81) float64)
    출력: 없음
    리턴: (fbank (T,80) float32, cmvn 적용 (T,80) float32, knf 옵션 문자열, means, istd)
    """
    opts = knf.FbankOptions()  # kaldi 기본 옵션(window_type 기본 povey)
    opts.frame_opts.samp_freq = 16000  # fs
    opts.frame_opts.frame_length_ms = 25  # 25 ms
    opts.frame_opts.frame_shift_ms = 10  # 10 ms
    opts.frame_opts.dither = 0.0  # 추론 시 dither 0
    opts.frame_opts.snip_edges = True  # 공식 설정
    opts.mel_opts.num_bins = 80  # 80 mel
    opts.mel_opts.debug_mel = False  # 공식 설정
    fb = knf.OnlineFbank(opts)  # 계산기
    fb.accept_waveform(16000, pcm.astype(np.float32).tolist())  # int16 값 그대로(×32768 스케일과 같은 크기)
    fbank = np.array([fb.get_frame(i) for i in range(fb.num_frames_ready)], dtype=np.float32)  # (T, 80)
    dim = cmvn_stats.shape[1] - 1  # 80
    count = cmvn_stats[0, dim]  # 프레임 수 통계
    means = cmvn_stats[0, :dim] / count  # 평균
    var = np.maximum(cmvn_stats[1, :dim] / count - means * means, 1e-20)  # 분산(하한 1e-20)
    istd = 1.0 / np.sqrt(var)  # 역표준편차
    normed = ((fbank.astype(np.float64) - means) * istd).astype(np.float32)  # float64 계산 후 float32
    return fbank, normed, str(opts), means, istd  # 반환


def check_frontend_vs_fsmn(knf_opts):
    """
    목적: FSMN(KaldiFbank) 전처리와의 차이 확인
    기능: 커밋된 FSMN fbank 골든의 knfOptions 문자열에서 window_type hamming → povey 로만 바꾼 값이
          FireRed 옵션 문자열과 같은지 assert 한다(= 창 함수 외 모든 kaldi 옵션 동일).
    입력: knf_opts(FireRed knf 옵션 문자열)
    출력: 없음(실패 시 AssertionError)
    리턴: 없음
    """
    with open(os.path.join(gc.GOLDEN_DIR, "fbank_" + gc.clip_stem(gc.CLIPS[0]) + ".json"), encoding="utf-8") as f:  # FSMN 골든
        fsmn_opts = json.load(f)["knfOptions"]  # FSMN 옵션 문자열
    assert fsmn_opts.replace("window_type: hamming", "window_type: povey") == knf_opts, "창 함수 외 옵션 차이 있음"  # 차이 확인


def synthetic_long_probs():
    """
    목적: max_speech(2000 프레임) 분할 분기용 손작성 확률
    기능: 실제 클립에서는 20 s 초과 구간이 생기지 않으므로, 45 s 남짓 이어지는 음성(0.9)에 짧은 저확률 구간을 넣은 확률열을 만든다.
          짧은 저확률 구간은 min_silence(20) 보다 짧아 구간을 끊지 않고 오프라인 분할 지점(argmin) 후보가 된다.
    입력: 없음
    출력: 없음
    리턴: (파이썬 float 리스트, 전체 샘플 수)
    """
    t = 4537  # 전체 프레임 수(45.37 s)
    p = np.full(t, 0.9)  # 기본 음성 확률
    p[:100] = 0.05  # 앞 1 s 무음
    p[1500:1505] = 0.2  # 짧은 저확률(5 프레임)
    p[2600:2604] = 0.1  # 짧은 저확률(4 프레임)
    p[3300] = 0.15  # 1 프레임 저확률
    p[-50:] = 0.02  # 끝 0.5 s 무음
    probs = [float(np.float32(v)) for v in p]  # float32 로 표현 가능한 값(JSON 왕복 정확)
    return probs, 160 * (t - 1) + 400  # 프레임 수 규칙 1 + (N − 400)/160 = t 를 만족하는 N


def write_synthetic(off, stv):
    """
    목적: max_speech 분할 손작성 골든 저장
    기능: 공식 detect()/detect_full() 이 추론 뒤에 하는 후처리 호출(process → decision_to_segment(dur),
          process_one_frame 반복 → results_to_timestamps)을 손작성 확률에 그대로 적용하고, 분할 발생을 assert 한다.
    입력: off(FireRedVad), stv(FireRedStreamVad)
    출력: golden/FIRERED_FIRERED_OFFLINE_synthetic_firered_long.json, FIRERED_FIRERED_STREAM_synthetic_firered_long.json
    리턴: 없음
    """
    probs, n = synthetic_long_probs()  # 손작성 확률
    name = gc.SYNTHETIC_PREFIX + "firered_long"  # clip 이름(음원 없음)
    dur = n / 16000  # 공식 dur 계산과 같은 식
    final, stages, margin = offline_stages(off.vad_postprocessor, probs)  # 오프라인 단계별
    segs = off.vad_postprocessor.decision_to_segment(final, dur)  # 공식 구간 변환
    assert stages["splitLongSpeech"] != stages["extendSpeech"], "오프라인 분할 미발생"  # 분기 실행 확인
    gc.write_golden("FIRERED", "FIRERED_OFFLINE", name, HOP, probs, segs, TOOL,  # 오프라인 손작성 골든
                    extra={"synthetic": True, "totalSamples": n, "durSec": dur, "expectedFrames": runs(final),  # 공통 추가 키
                           "stages": stages, "minAbsSmoothedMinusThreshold": margin, "splitApplied": True,  # 단계별
                           "params": dict(FIRERED_OFFLINE, chunk_max_frame=CHUNK_MAX_FRAME)})  # 적용 값
    stv.reset()  # 상태 초기화
    results = [stv.postprocessor.process_one_frame(v) for v in probs]  # 공식 프레임별 후처리
    ts = stv.results_to_timestamps(results)  # 공식 구간 변환
    stv.reset()  # 상태 초기화
    assert any(r.is_speech_end and r.speech_end_frame - r.speech_start_frame + 1 >= 2000 for r in results), "스트리밍 max_speech 미발생"  # 분기 확인
    gc.write_golden("FIRERED", "FIRERED_STREAM", name, HOP, probs, ts, TOOL,  # 스트리밍 손작성 골든
                    extra={"synthetic": True, "totalSamples": n, "durSec": dur,  # 공통 추가 키
                           "expectedFrames": [[int(round(s * 100)), int(round(e * 100))] for s, e in ts],  # 0-based 프레임
                           "events": stream_events(results),  # 이벤트
                           "params": dict(FIRERED_STREAM, chunk_max_frame=CHUNK_MAX_FRAME,  # 적용 값
                                          effectiveSmoothWindow=stv.postprocessor.smooth_window_size,  # 실효 평활 창
                                          effectivePadStartFrame=stv.postprocessor.pad_start_frame)})  # 실효 시작 패딩
    print(name, "OFF", segs, "STREAM", ts)  # 진행 로그


def main():
    """
    목적: FireRedVAD 골든 생성 진입점
    기능: 클립마다 공식 오프라인·스트리밍 VAD 를 프리셋 값으로 실행하고, ONNX 확률·특징·CMVN 골든을 저장한다.
    입력: 없음
    출력: golden/FIRERED_*.json, scores_FIRERED_*.json, scores_FIRERED_STREAM_*.json, firered_fbank*_*.json,
          firered_cmvn.json, firered_cmvn.ark
    리턴: 없음
    """
    torch.set_num_threads(1)  # 결정적 실행
    ef.check_sources()  # repo commit·체크포인트·설치 패키지 고정 확인
    assert ef.sha256(CMVN_ARK) == ef.CKPT_SHA256["VAD/cmvn.ark"], "cmvn.ark 해시 불일치(export_firered.py 먼저 실행)"  # CMVN 확인
    off_cfg = FireRedVadConfig(use_gpu=False, chunk_max_frame=CHUNK_MAX_FRAME, **FIRERED_OFFLINE)  # 오프라인 설정
    str_cfg = FireRedStreamVadConfig(use_gpu=False, chunk_max_frame=CHUNK_MAX_FRAME, **FIRERED_STREAM)  # 스트리밍 설정
    off = FireRedVad.from_pretrained(os.path.join(ef.MODEL_ROOT, "VAD"), off_cfg)  # 공식 오프라인 VAD
    stv = FireRedStreamVad.from_pretrained(os.path.join(ef.MODEL_ROOT, "Stream-VAD"), str_cfg)  # 공식 스트리밍 VAD
    assert stv.postprocessor.smooth_window_size == 1 and stv.postprocessor.pad_start_frame == 5, "스트리밍 실효값 확인"  # max(1,0)=1, max(1,5)=5
    sess_off = ef.session(os.path.join(ef.FIRERED_OUT, "fireredvad_vad.onnx"))  # 오프라인 ONNX
    sess_str = ef.session(os.path.join(ef.FIRERED_OUT, "fireredvad_stream_vad.onnx"))  # 스트리밍 ONNX(캐시 없음)
    cmvn_stats = kaldiio.load_mat(CMVN_ARK)  # (2, 81) float64 통계
    for clip in gc.CLIPS:  # 클립마다
        audio, pcm = gc.load_clip(clip)  # float32, int16 파형
        n = len(pcm)  # 전체 샘플 수
        # ---- 특징(공식 vs 독립) ----
        raw = off.audio_feat.fbank((16000, pcm))  # 공식 KaldifeatFbank(CMVN 전)
        feats_t, dur = off.audio_feat.extract(pcm)  # 공식 AudioFeat.extract(CMVN 후, float32 텐서)
        feats = feats_t.numpy()  # numpy
        my_raw, my_norm, knf_opts, means, istd = independent_features(pcm, cmvn_stats)  # 독립 구현
        assert np.array_equal(my_raw, raw), "독립 fbank ≠ 공식 fbank"  # 완전 일치
        assert np.array_equal(my_norm, feats), "독립 CMVN ≠ 공식 CMVN"  # 완전 일치
        assert np.array_equal(means, off.audio_feat.cmvn.means) and np.array_equal(istd, off.audio_feat.cmvn.inverse_std_variances)  # CMVN 해석 일치
        t_frames = feats.shape[0]  # 전체 프레임 수
        assert t_frames == 1 + (n - 400) // 160, "프레임 수 규칙 불일치"  # snip_edges 프레임 수
        common = {"numFramesTotal": int(t_frames), "inputScale": 1, "knfOptions": knf_opts, "totalSamples": n}  # 특징 공통 키
        gc.write_feature("firered_fbank", clip, raw[:FEAT_FRAMES], TOOL, extra=common)  # CMVN 전 50 프레임
        gc.write_feature("firered_fbank_cmvn", clip, feats[:FEAT_FRAMES], TOOL,  # CMVN 후 50 프레임(모델 입력)
                         extra=dict(common, cmvnFile="firered_cmvn.ark"))  # CMVN 파일
        # ---- 오프라인(FIRERED_OFFLINE) ----
        result, probs_t = off.detect(pcm)  # 공식 실행(ndarray → sr 16000 가정)
        probs = probs_t.tolist()  # 파이썬 float(공식 후처리 입력과 동일)
        final, stages, margin = offline_stages(off.vad_postprocessor, probs)  # 단계별 기록
        segs = off.vad_postprocessor.decision_to_segment(final, dur)  # 공식 구간 변환
        assert segs == result["timestamps"], "단계별 재현 구간 ≠ detect() 결과"  # 동일 확인
        onnx_off = ef.onnx_full(sess_off, feats)  # 우리 ONNX 확률
        onnx_off_segs = off.vad_postprocessor.decision_to_segment(off.vad_postprocessor.process(onnx_off.astype(np.float64).tolist()), dur)  # ONNX 확률로 후처리
        gc.write_golden(  # 오프라인 골든
            "FIRERED", "FIRERED_OFFLINE", clip, HOP, probs, result["timestamps"], TOOL,  # 기본 필드
            extra={"totalSamples": n, "durSec": dur,  # 전체 샘플 수, 공식 dur(n/16000)
                   "expectedFrames": runs(final),  # 최종 결정 [시작, 끝(배타)) 프레임
                   "stages": stages,  # 후처리 단계별 결정 구간
                   "minAbsSmoothedMinusThreshold": margin,  # 임계값 경계 여유
                   "splitApplied": stages["splitLongSpeech"] != stages["extendSpeech"],  # max_speech 분할 발생 여부
                   "sameSegmentsWithOnnxProbs": onnx_off_segs == result["timestamps"],  # ONNX 확률로도 같은 구간인지
                   "params": dict(FIRERED_OFFLINE, chunk_max_frame=CHUNK_MAX_FRAME)})  # 적용 값
        gc.write_scores("FIRERED", clip, HOP, onnx_off, TOOL,  # 오프라인 ONNX 확률
                        extra={"model": "fireredvad_vad.onnx", "totalSamples": n,  # 모델·샘플 수
                               "torchMaxAbsDiff": float(np.max(np.abs(onnx_off - probs_t.numpy())))})  # PyTorch 대비 차
        # ---- 스트리밍(FIRERED_STREAM) ----
        frame_results, sresult = stv.detect_full(pcm)  # 공식 실행(전체 특징 1회 추론 → 프레임별 후처리)
        # 주의: frame_results 의 raw_prob 는 round(,3) 표시값이므로 frameScores 에는 같은 입력의 반올림 전 원본 확률을 쓴다
        sprobs_t = stv.vad_model.forward(feats_t.unsqueeze(0))[0][0, :, 0]  # detect_full 과 같은 1회 전체 추론(반올림 전)
        assert len(frame_results) == t_frames, "스트리밍 프레임 수 불일치"  # 길이 확인
        sframes = [[int(round(s * 100)), int(round(e * 100))] for s, e in sresult["timestamps"]]  # 0-based 프레임(초×100)
        onnx_str = ef.onnx_full(sess_str, feats)  # 우리 ONNX(스트리밍) 확률
        stv.reset()  # 후처리 상태 초기화
        onnx_str_ts = stv.results_to_timestamps([stv.postprocessor.process_one_frame(float(p)) for p in onnx_str])  # ONNX 확률로 후처리
        stv.reset()  # 상태 초기화
        gc.write_golden(  # 스트리밍 골든
            "FIRERED", "FIRERED_STREAM", clip, HOP, sprobs_t.numpy(), sresult["timestamps"], TOOL,  # 기본 필드
            extra={"totalSamples": n, "durSec": dur,  # 전체 샘플 수, dur
                   "expectedFrames": sframes,  # results_to_timestamps 의 0-based (시작, 끝) 프레임
                   "events": stream_events(frame_results),  # 시작/끝 이벤트 프레임(1-based)
                   "sameSegmentsWithOnnxProbs": onnx_str_ts == sresult["timestamps"],  # ONNX 확률로도 같은 구간인지
                   "params": dict(FIRERED_STREAM, chunk_max_frame=CHUNK_MAX_FRAME,  # 적용 값
                                  effectiveSmoothWindow=stv.postprocessor.smooth_window_size,  # 실효 평활 창(1 = 없음)
                                  effectivePadStartFrame=stv.postprocessor.pad_start_frame)})  # 실효 시작 패딩
        gc.write_scores("FIRERED_STREAM", clip, HOP, onnx_str, TOOL,  # 스트리밍 ONNX 확률
                        extra={"model": "fireredvad_stream_vad.onnx", "totalSamples": n,  # 모델·샘플 수
                               "torchMaxAbsDiff": float(np.max(np.abs(onnx_str - sprobs_t.numpy())))})  # PyTorch 대비 차
        print(clip, t_frames, "OFF", result["timestamps"], "STREAM", sresult["timestamps"],  # 진행 로그
              "margin", round(margin, 6), "onnxSame", onnx_off_segs == result["timestamps"], onnx_str_ts == sresult["timestamps"])  # 경계 여유·ONNX 동일
    write_synthetic(off, stv)  # max_speech 분할 손작성 골든
    check_frontend_vs_fsmn(knf_opts)  # FSMN 전처리와 창 함수만 다른지 확인
    dim = cmvn_stats.shape[1] - 1  # 80
    gc._dump("firered_cmvn.json", {  # CMVN 해석값(Kotlin 파서 검증용)
        "kind": "firered_cmvn", "sourceFile": "firered_cmvn.ark", "dim": int(dim),  # 종류·원본·차원
        "count": float(cmvn_stats[0, dim]), "varianceFloor": 1e-20,  # 프레임 수 통계·분산 하한
        "means": [float(v) for v in means], "inverseStdVariances": [float(v) for v in istd],  # float64 값(repr 왕복 정확)
        "expected": None, "tool": TOOL})  # 검증 스크립트용 expected 키, 도구
    shutil.copy(CMVN_ARK, os.path.join(gc.GOLDEN_DIR, "firered_cmvn.ark"))  # 원본 cmvn.ark 테스트 리소스 복사
    print("OK: FireRedVAD 골든 생성 완료")  # 성공


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
