# 파일명: verify_golden.py
# 목적: 생성된 골든 JSON 검증(계획서 Task 7 Step 4)
# 기능: golden/*.json 개수(≥45), 모든 JSON 의 "expected" 키 존재, 계열×프리셋×클립 조합 누락 없음,
#       합성 클립 Silero 결과가 앞 무음(0–2 s)을 포함하지 않음, 계열별 hopSec 값과
#       frameScores 길이가 원본 프레임 수 규칙(totalSamples 기준)과 같은지, 클립 WAV 가 있으면 totalSamples 가
#       실제 길이와 같은지, 손작성 max_speech 골든 존재·분할 여부를 assert 한다.
# change(add)-hyungchul-2026-10-10-12:19
# change(add)-hyungchul-2026-10-10-12:52 수정 라운드 1: 프레임 수·hop 규칙 검사, 합성 점수 골든, 클립 4종

import glob  # 파일 목록
import json  # JSON 읽기
import math  # 올림/내림
import os  # 경로
import sys  # 모듈 경로 추가

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 golden_common 을 찾게 함

import numpy as np  # rint(짝수 반올림)
import soundfile as sf  # WAV 길이 확인

import golden_common as gc  # 공통 도우미

# 계열 → 프리셋 id 목록
FAMILIES = {
    "SILERO": list(gc.SILERO_PRESETS),  # Silero 8종
    "PYANNOTE": list(gc.PYANNOTE_PRESETS),  # pyannote 4종
    "FSMN": list(gc.FSMN_PRESETS),  # FSMN 2종
    "WEBRTC": list(gc.WEBRTC_PRESETS),  # WebRTC 3종
    "TEN": ["TEN_DEFAULT", "TEN_SHERPA"],  # TEN 2종
}
SYNTHETIC = ["synthetic_maxspeech_a", "synthetic_maxspeech_b", "synthetic_maxspeech_c"]  # 손작성 점수 케이스


def pyannote_frames(n):
    """
    목적: pyannote 집계 프레임 수 규칙
    기능: README 6.2 의 2·7·9 단계(창 수, rint 프레임 수, loose crop)를 계산한다.
    입력: n(전체 샘플 수)
    출력: 없음
    리턴: 프레임 수
    """
    w, s, h = 160000, 16000, 270 / 16000  # 창, step, 프레임 간격
    c0 = (n - w) // s + 1 if n >= w else 0  # 완전한 창 수
    has_last = (n < w) or ((n - w) % s > 0)  # 패딩 창 여부
    c = c0 + (1 if has_last else 0)  # 전체 창 수
    t = int(np.rint((10.0 + (c - 1) * 1.0) / h)) + 1  # 집계 프레임 수
    if has_last:  # loose crop
        t = min(int(math.floor((n / 16000) / h)) + 1, t)  # 오디오 끝까지
    return t  # 반환


def expected_frames(fam, d):
    """
    목적: 계열별 (hopSec, 프레임 수) 규칙
    기능: totalSamples(N)와 프리셋 파라미터로 원본 구현의 프레임 수를 계산한다.
    입력: fam(계열), d(골든 dict)
    출력: 없음
    리턴: (hopSec, 프레임 수)
    """
    n = d["totalSamples"]  # 전체 샘플 수
    if fam == "SILERO":  # 512 창, 마지막 0 패딩
        return 0.032, math.ceil(n / 512)  # ceil
    if fam == "TEN":  # 256 hop, 완전한 프레임만
        return 0.016, n // 256  # floor
    if fam == "FSMN":  # 25/10 ms, snip_edges
        return 0.010, 1 + (n - 400) // 160  # kaldi 프레임 수
    if fam == "PYANNOTE":  # 집계 프레임
        return 270 / 16000, pyannote_frames(n)  # README 규칙
    p = d["params"]  # WebRTC
    length = 16 * p["frameMs"]  # 프레임 샘플 수
    count = (n - 1) // length if p["style"] == "RING_BUFFER" else n // length  # example.py 엄격 < / 완전한 프레임
    return p["frameMs"] / 1000.0, count  # 반환


def check_frames(fam, d, path):
    """
    목적: hopSec·frameScores 길이 규칙 확인
    기능: expected_frames 와 비교하고, 프레임 시각/dB 가 있으면 길이가 같은지 확인한다.
    입력: fam, d(골든 dict), path(파일 경로)
    출력: 없음(실패 시 AssertionError)
    리턴: 없음
    """
    hop, count = expected_frames(fam, d)  # 규칙 값
    assert abs(d["hopSec"] - hop) < 1e-12, f"hopSec 불일치 {d['hopSec']} vs {hop}: {path}"  # hop 확인
    assert len(d["frameScores"]) == count, f"frameScores 길이 {len(d['frameScores'])} ≠ {count}: {path}"  # 길이 확인
    if d.get("frameTimesSec") is not None:  # 프레임 시각
        assert len(d["frameTimesSec"]) == count, path  # 길이 일치
    if d.get("frameDb") is not None:  # 프레임 dB
        assert len(d["frameDb"]) == count, path  # 길이 일치


def load(path):
    """
    목적: JSON 읽기
    기능: UTF-8 JSON 파일을 dict 로 읽는다.
    입력: path(파일 경로)
    출력: 없음
    리턴: dict
    """
    with open(path, encoding="utf-8") as f:  # 열기
        return json.load(f)  # 읽기


def main():
    """
    목적: 골든 검증 진입점
    기능: 파일 개수·키·조합·프레임 규칙·합성 클립 조건·손작성 골든을 확인하고 요약을 출력한다.
    입력: 없음
    출력: 콘솔 요약(실패 시 AssertionError)
    리턴: 없음
    """
    files = sorted(glob.glob(os.path.join(gc.GOLDEN_DIR, "*.json")))  # 모든 골든 JSON
    assert len(files) >= 45, f"JSON 개수 부족: {len(files)}"  # 개수 확인
    for path in files:  # 파일마다
        assert "expected" in load(path), f"expected 키 없음: {path}"  # 키 확인
    lengths = {}  # 클립 → 실제 샘플 수(WAV 가 있을 때)
    for clip in gc.CLIPS:  # 클립마다
        wav = os.path.join(gc.AUDIO_DIR, clip)  # WAV 경로
        if os.path.exists(wav):  # 있으면(gitignore 라 없을 수 있음)
            lengths[clip] = sf.info(wav).frames  # 샘플 수
    count = 0  # 구간 골든 수
    for fam, pids in FAMILIES.items():  # 계열마다
        for pid in pids:  # 프리셋마다
            for clip in gc.CLIPS:  # 클립마다
                path = os.path.join(gc.GOLDEN_DIR, f"{fam}_{pid}_{gc.clip_stem(clip)}.json")  # 기대 파일명
                assert os.path.exists(path), f"누락: {path}"  # 존재 확인
                d = load(path)  # 읽기
                assert d["family"] == fam and d["presetId"] == pid and d["clip"] == clip, path  # 메타 일치
                if clip in lengths:  # WAV 가 있으면
                    assert d["totalSamples"] == lengths[clip], f"totalSamples ≠ WAV 길이: {path}"  # 길이 일치
                check_frames(fam, d, path)  # hop·프레임 수 규칙
                assert isinstance(d["expected"], list), path  # 구간 목록
                for seg in d["expected"]:  # 구간마다
                    assert 0.0 <= seg["start"] < seg["end"], f"잘못된 구간 {seg} {path}"  # 시작<끝
                if fam == "SILERO" and clip.startswith("synth_"):  # 합성 클립 Silero
                    for seg in d["expected"]:  # 구간마다
                        assert seg["start"] >= 2.0, f"합성 클립 무음(0–2 s) 포함: {pid} {seg}"  # 앞 무음 미포함
                count += 1  # 개수 증가
    synth_count = 0  # 손작성 골든 수
    for name in SYNTHETIC:  # 손작성 케이스마다
        for fam, pid in (("SILERO", "SILERO_SHERPA"), ("TEN", "TEN_SHERPA")):  # 두 window
            path = os.path.join(gc.GOLDEN_DIR, f"{fam}_{pid}_{name}.json")  # 파일명
            d = load(path)  # 읽기(없으면 예외)
            assert d["synthetic"] is True and d["clip"] == name, path  # 합성 표시
            check_frames(fam, d, path)  # 프레임 규칙(Silero ceil, TEN floor)
            assert len(d["expected"]) >= 2, f"분할 없음: {path}"  # 분할 발생
            synth_count += 1  # 개수 증가
    for fam in ("SILERO", "PYANNOTE", "FSMN"):  # 점수 전용 파일
        for clip in gc.CLIPS:  # 클립마다
            path = os.path.join(gc.GOLDEN_DIR, f"scores_{fam}_{gc.clip_stem(clip)}.json")  # 파일명
            check_frames(fam, load(path), path)  # 프레임 규칙
    for kind in ("fbank", "lfr_cmvn"):  # 특징 파일
        for clip in gc.CLIPS:  # 클립마다
            d = load(os.path.join(gc.GOLDEN_DIR, f"{kind}_{gc.clip_stem(clip)}.json"))  # 읽기
            if clip in lengths:  # WAV 가 있으면
                assert d["numFramesTotal"] == 1 + (lengths[clip] - 400) // 160, (kind, clip)  # kaldi 프레임 수
    assert os.path.exists(os.path.join(gc.GOLDEN_DIR, "fsmn_am.mvn")), "fsmn_am.mvn 없음"  # CMVN 복사본
    print(f"OK: JSON {len(files)}개, 구간 골든 {count}개 + 손작성 max_speech 골든 {synth_count}개, "
          f"expected 키 전부 존재, hop·프레임 수 규칙 일치, 합성 클립 Silero 무음 미포함")  # 요약


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
