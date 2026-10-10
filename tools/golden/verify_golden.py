# 파일명: verify_golden.py
# 목적: 생성된 골든 JSON 검증(계획서 Task 7 Step 4)
# 기능: golden/*.json 개수(≥45), 모든 JSON 의 "expected" 키 존재, 계열×프리셋×클립 조합 누락 없음,
#       합성 클립 Silero 결과가 앞 무음(0–2 s)을 포함하지 않음, frameScores 길이와 hop 의 정합을 assert 한다.
# change(add)-hyungchul-2026-10-10-12:19

import glob  # 파일 목록
import json  # JSON 읽기
import os  # 경로
import sys  # 모듈 경로 추가

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 golden_common 을 찾게 함

import golden_common as gc  # 공통 도우미

# 계열 → 프리셋 id 목록
FAMILIES = {
    "SILERO": list(gc.SILERO_PRESETS),  # Silero 8종
    "PYANNOTE": list(gc.PYANNOTE_PRESETS),  # pyannote 4종
    "FSMN": list(gc.FSMN_PRESETS),  # FSMN 2종
    "WEBRTC": list(gc.WEBRTC_PRESETS),  # WebRTC 3종
    "TEN": ["TEN_DEFAULT", "TEN_SHERPA"],  # TEN 2종
}


def main():
    """
    목적: 골든 검증 진입점
    기능: 파일 개수·키·조합·합성 클립 조건을 확인하고 요약을 출력한다.
    입력: 없음
    출력: 콘솔 요약(실패 시 AssertionError)
    리턴: 없음
    """
    files = sorted(glob.glob(os.path.join(gc.GOLDEN_DIR, "*.json")))  # 모든 골든 JSON
    assert len(files) >= 45, f"JSON 개수 부족: {len(files)}"  # 개수 확인
    for path in files:  # 파일마다
        with open(path, encoding="utf-8") as f:  # 열기
            data = json.load(f)  # 읽기
        assert "expected" in data, f"expected 키 없음: {path}"  # 키 확인
    count = 0  # 구간 골든 수
    for fam, pids in FAMILIES.items():  # 계열마다
        for pid in pids:  # 프리셋마다
            for clip in gc.CLIPS:  # 클립마다
                path = os.path.join(gc.GOLDEN_DIR, f"{fam}_{pid}_{gc.clip_stem(clip)}.json")  # 기대 파일명
                assert os.path.exists(path), f"누락: {path}"  # 존재 확인
                with open(path, encoding="utf-8") as f:  # 열기
                    d = json.load(f)  # 읽기
                assert d["family"] == fam and d["presetId"] == pid and d["clip"] == clip, path  # 메타 일치
                assert isinstance(d["expected"], list), path  # 구간 목록
                for seg in d["expected"]:  # 구간마다
                    assert 0.0 <= seg["start"] < seg["end"], f"잘못된 구간 {seg} {path}"  # 시작<끝
                if d["frameTimesSec"] is not None:  # 프레임 시각이 있으면
                    assert len(d["frameTimesSec"]) == len(d["frameScores"]), path  # 길이 일치
                if fam == "SILERO" and clip.startswith("synth_"):  # 합성 클립 Silero
                    for seg in d["expected"]:  # 구간마다
                        assert seg["start"] >= 2.0, f"합성 클립 무음(0–2 s) 포함: {pid} {seg}"  # 앞 무음 미포함
                count += 1  # 개수 증가
    for fam in ("SILERO", "PYANNOTE", "FSMN"):  # 점수 전용 파일
        for clip in gc.CLIPS:  # 클립마다
            assert os.path.exists(os.path.join(gc.GOLDEN_DIR, f"scores_{fam}_{gc.clip_stem(clip)}.json")), (fam, clip)  # 존재
    for kind in ("fbank", "lfr_cmvn"):  # 특징 파일
        for clip in gc.CLIPS:  # 클립마다
            assert os.path.exists(os.path.join(gc.GOLDEN_DIR, f"{kind}_{gc.clip_stem(clip)}.json")), (kind, clip)  # 존재
    assert os.path.exists(os.path.join(gc.GOLDEN_DIR, "fsmn_am.mvn")), "fsmn_am.mvn 없음"  # CMVN 복사본
    print(f"OK: JSON {len(files)}개, 구간 골든 {count}개, expected 키 전부 존재, 합성 클립 Silero 무음 미포함")  # 요약


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
