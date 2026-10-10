# 파일명: gen_maxspeech.py
# 목적: silero get_speech_timestamps 후처리의 "최대 발화 길이(max_speech) 분할" 분기 골든 생성
# 기능: 실제 클립(≤ 70 s)으로는 SILERO_SHERPA/TEN_SHERPA(maxSpeech 20 s)의 분할 분기가 실행되지 않으므로,
#       손으로 만든 확률 배열(음원 없음) 3종을 window 512(SILERO_SHERPA)와 window 256(TEN_SHERPA)으로
#       silero-vad 6.2.3 get_speech_timestamps_from_probs 에 넣어 "합성 점수 골든" 을 만든다.
#       sys.settrace 로 라이브러리 함수의 실행 줄을 기록해 각 케이스가 의도한 분기(possible_ends 최장 무음 절단,
#       무음 없는 강제 절단, 오디오 끝까지 이어지는 마지막 구간)를 실제로 탔는지 assert 한다.
# change(add)-hyungchul-2026-10-10-12:52

import inspect  # 라이브러리 소스 줄 번호 찾기
import math  # 올림
import os  # 경로
import sys  # 모듈 경로 추가, settrace

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 공통 모듈을 찾게 함

from importlib.metadata import version  # 패키지 버전

import silero_vad.utils_vad as uv  # 라이브러리 모듈(추적 대상 함수 포함)

import golden_common as gc  # 공통 도우미
from gen_ten import sherpa_style  # window 256 호출 방식(sampling_rate 8000 + ms 2배) 재사용

SR = gc.SAMPLE_RATE  # 16 kHz
BASE = 0.02  # 기본(무음) 확률
SPEECH = 0.9  # 음성 확률

# 케이스: 이름 → (전체 샘플 수, [(시작 초, 끝 초, 확률)], 기대 분기 집합)
#   - 0.1 구간: neg_threshold(0.35) 미만 → temp_end 설정. 98 ms(1568 샘플) 초과 후 음성 복귀 시 possible_ends 후보.
#   - 0.4/0.45 구간: neg_th 이상 th(0.5) 미만 → 무음으로 보지 않음(temp_end 없음).
CASES = {
    "synthetic_maxspeech_a": (  # 여러 짧은 무음 중 가장 긴 무음(9.0–9.4 s)에서 절단, 이어서 다음 최장 무음(27.0 s)에서 절단
        32 * SR + 37,  # 32 s + 37 샘플
        [(1.0, 31.0, SPEECH), (5.0, 5.2, 0.1), (9.0, 9.4, 0.1), (12.0, 12.5, 0.4), (15.0, 15.25, 0.1),
         (20.0, 20.3, 0.4), (24.0, 24.3, 0.1), (27.0, 27.45, 0.1)],  # 음성 구간과 무음/중간 확률 구간
        {"possible_end_cut", "continue_after_cut", "possible_end_append"},  # 기대 분기
    ),
    "synthetic_maxspeech_b": (  # neg_th 미만 무음이 없거나 98 ms 이하뿐 → possible_ends 비어 현재 샘플에서 강제 절단
        27 * SR + 211,  # 27 s + 211 샘플
        [(0.5, 26.0, SPEECH), (3.0, 3.5, 0.4), (7.0, 7.064, 0.1), (10.0, 10.3, 0.4), (18.0, 18.2, 0.45)],  # 구간
        {"fallback_cut"},  # 기대 분기
    ),
    "synthetic_maxspeech_c": (  # 짧은 버스트(0.2 s) 버림, 절단 시점이 무음 진행 중, 마지막 구간이 오디오 끝(N)까지
        45 * SR + 99,  # 45 s + 99 샘플
        [(0.3, 0.5, SPEECH), (2.0, 45.1, SPEECH), (6.0, 6.15, 0.1), (13.0, 13.2, 0.1), (21.9, 22.2, 0.1),
         (30.0, 30.35, 0.1), (36.0, 36.12, 0.1), (40.0, 41.0, 0.45)],  # 구간
        {"possible_end_cut", "continue_after_cut", "possible_end_append", "end_at_audio_length"},  # 기대 분기
    ),
}
PRESETS = {  # (계열, 프리셋 id, window, 프레임 수 규칙)
    "SILERO": ("SILERO_SHERPA", 512, "ceil"),  # Silero: ceil(N/512) (마지막 창 0 패딩)
    "TEN": ("TEN_SHERPA", 256, "floor"),  # TEN: floor(N/256) (완전한 프레임만)
}


def build_probs(total, window, regions, rule):
    """
    목적: 손작성 확률 배열 생성
    기능: 프레임 i 의 시작 시각 i·window/16000 이 각 구간 [시작, 끝) 에 들면 그 확률을 덮어쓴다(뒤 구간이 우선).
    입력: total(전체 샘플 수), window(창 샘플), regions([(s, e, p)]), rule("ceil"/"floor")
    출력: 없음
    리턴: float 리스트
    """
    n = math.ceil(total / window) if rule == "ceil" else total // window  # 프레임 수
    probs = [BASE] * n  # 기본 무음
    for s, e, p in regions:  # 구간마다
        for i in range(n):  # 프레임마다
            t = i * window / SR  # 프레임 시작 시각
            if s <= t < e:  # 구간 안이면
                probs[i] = p  # 확률 지정
    return probs  # 반환


def branch_lines():
    """
    목적: 추적할 라이브러리 분기 줄 번호 찾기
    기능: get_speech_timestamps_from_probs 소스에서 표식 문자열이 있는 줄의 절대 줄 번호를 구한다.
    입력: 없음
    출력: 없음
    리턴: {분기 이름: 줄 번호}
    """
    lines, start = inspect.getsourcelines(uv.get_speech_timestamps_from_probs)  # 소스와 시작 줄
    found = {}  # 결과
    cut_idx = None  # possible_ends 절단 줄 위치
    for k, text in enumerate(lines):  # 줄마다
        if "possible_ends.append((temp_end, sil_dur))" in text:  # 후보 무음 기록
            found["possible_end_append"] = start + k  # 기록
        if "prev_end, dur = max(possible_ends" in text:  # 최장 무음 절단
            found["possible_end_cut"] = start + k  # 기록
            cut_idx = k  # 위치
        if cut_idx is not None and "continue_after_cut" not in found and "current_speech['start'] = next_start" in text:  # 절단 후 계속
            found["continue_after_cut"] = start + k  # 기록
        if "current_speech['end'] = cur_sample" in text:  # 무음 없는 강제 절단
            found["fallback_cut"] = start + k  # 기록
        if "current_speech['end'] = audio_length_samples" in text:  # 끝까지 이어진 마지막 구간
            found["end_at_audio_length"] = start + k  # 기록
    assert len(found) == 5, f"분기 줄을 모두 찾지 못함: {found}"  # 라이브러리 버전 변화 감지
    return found  # 반환


def traced_call(fn):
    """
    목적: 라이브러리 함수 실행 줄 기록
    기능: sys.settrace 로 get_speech_timestamps_from_probs 코드 객체의 line 이벤트만 모은다.
    입력: fn(인자 없는 호출 함수)
    출력: 없음
    리턴: (fn 결과, 실행된 줄 번호 집합)
    """
    target = uv.get_speech_timestamps_from_probs.__code__  # 추적 대상 코드
    hit = set()  # 실행 줄

    def tracer(frame, event, arg):  # 전역 추적기
        if frame.f_code is target:  # 대상 함수 프레임이면
            def local(fr, ev, a):  # 지역 추적기
                if ev == "line":  # 줄 실행 이벤트
                    hit.add(fr.f_lineno)  # 기록
                return local  # 계속 추적
            return local  # 지역 추적 시작
        return None  # 다른 함수는 추적 안 함

    sys.settrace(tracer)  # 추적 시작
    try:  # 실행
        result = fn()  # 호출
    finally:  # 항상
        sys.settrace(None)  # 추적 종료
    return result, hit  # 반환


def main():
    """
    목적: 합성 max_speech 골든 생성 진입점
    기능: 케이스 3종 × (SILERO_SHERPA window 512, TEN_SHERPA window 256) 결과를 저장하고 분기 실행·구간 길이를 assert 한다.
    입력: 없음
    출력: golden/SILERO_SILERO_SHERPA_synthetic_*.json, golden/TEN_TEN_SHERPA_synthetic_*.json
    리턴: 없음
    """
    lines = branch_lines()  # 분기 줄 번호
    th, min_sp, min_sil, pad, max_sp = gc.TEN_SHERPA  # SILERO_SHERPA 와 같은 값(0.5, 250, 500, 0, 20)
    assert gc.SILERO_PRESETS["SILERO_SHERPA"] == gc.TEN_SHERPA, "두 프리셋 값이 같아야 함"  # 값 확인
    for name, (total, regions, expect) in CASES.items():  # 케이스마다
        for family, (pid, window, rule) in PRESETS.items():  # 계열마다
            probs = build_probs(total, window, regions, rule)  # 확률 배열
            if window == 512:  # Silero: 16 kHz 직접 호출
                call = lambda: uv.get_speech_timestamps_from_probs(  # noqa: E731 라이브러리 호출
                    probs, sampling_rate=SR, threshold=th, min_speech_duration_ms=min_sp,  # 임계/최소 발화
                    max_speech_duration_s=max_sp, min_silence_duration_ms=min_sil, speech_pad_ms=pad,  # 최대/무음/pad
                    return_seconds=False, audio_length_samples=total)  # 샘플 단위, 실제 길이
            else:  # TEN: window 256 호출 방식
                call = lambda: sherpa_style(probs, total, th, min_sp, min_sil, pad, max_sp)  # noqa: E731
            ts, hit = traced_call(call)  # 실행 + 줄 기록
            taken = {b for b, ln in lines.items() if ln in hit}  # 실행된 분기
            assert expect <= taken, f"{name} {pid}: 기대 분기 {expect} 중 미실행 {expect - taken}"  # 분기 실행 확인
            samples = [[int(s["start"]), int(s["end"])] for s in ts]  # 샘플 구간
            assert len(samples) >= 2, f"{name} {pid}: 분할이 일어나지 않음"  # 분할 발생 확인
            for s, e in samples:  # 구간마다
                assert e - s <= max_sp * SR, f"{name} {pid}: 구간 길이 {e - s} > max_speech"  # 최대 길이 이하
            gc.write_golden(  # 골든 저장
                family, pid, name, window / SR, probs, [(s / SR, e / SR) for s, e in samples],  # 기본 필드
                f"silero-vad {version('silero-vad')} get_speech_timestamps_from_probs(window {window}); 손작성 확률(음원 없음)",  # 도구
                extra={"synthetic": True,  # 음원 없는 합성 점수 골든
                       "expectedSamples": samples, "totalSamples": total, "windowSamples": window,  # 정답·길이·창
                       "branchesTaken": sorted(taken),  # 실행된 분기
                       "params": {"threshold": th, "minSpeechMs": min_sp, "minSilenceMs": min_sil,  # 파라미터
                                  "padMs": pad, "maxSpeechSec": max_sp}},  # 파라미터
            )
            print(name, pid, sorted(taken), samples)  # 진행 로그


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
