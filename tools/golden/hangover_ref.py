# 파일명: hangover_ref.py
# 목적: SimpleHangover(프레임 점수 → 구간) 기준 구현과 연속 프레임 병합(RAW)
# 기능: 계획서(Task 10) 의미 그대로의 hangover 상태기계를 정의한다. 이 파일이 Kotlin SimpleHangover 의 "정의"이며
#       WEBRTC_ANDROIDVAD, TEN_DEFAULT(0/0), 이후 Yamnet 골든이 모두 이 구현으로 만들어진다.
#       규칙(README_한국어.md "SimpleHangover 정의" 절과 동일):
#         - score >= threshold 인 프레임을 음성 프레임으로 본다.
#         - 필요 프레임 수: needSpeech = max(1, ceil(speechMs / hopMs)), needSilence = max(1, ceil(silenceMs / hopMs))
#           (나눗셈 결과를 소수 9자리로 반올림한 뒤 올림 → 300/20=15, 50/20=3, 0→1)
#         - 비음성 상태: 연속 음성 프레임 수가 needSpeech 에 도달하면 그 연속 구간의 첫 프레임에서 구간 시작.
#           음성 연속이 끊기면(무음 프레임) 카운트를 0 으로.
#         - 음성 상태: 연속 무음 프레임 수가 needSilence 에 도달하면 그 무음 연속의 첫 프레임 시각에서 구간 종료.
#           무음 연속 중 음성 프레임이 나오면 무음 카운트를 0 으로(짧은 무음은 메움).
#         - 끝에서 음성 상태이면: 진행 중인 무음 연속이 있으면 그 첫 프레임에서, 없으면 마지막 프레임 끝(n·hop)에서 종료.
#         - 시각 = 프레임 인덱스 × hopSec. speechMs = silenceMs = 0 이면 연속 음성 프레임 병합(RAW)과 같다.
# change(add)-hyungchul-2026-10-10-12:19

import math  # 올림


def need_frames(ms, hop_sec):
    """
    목적: 시간(ms)을 필요한 연속 프레임 수로 변환
    기능: max(1, ceil(round(ms / hopMs, 9))) 를 계산한다(부동소수 오차로 15.000000001 → 16 이 되는 것을 방지).
    입력: ms(밀리초), hop_sec(프레임 간격 초)
    출력: 없음
    리턴: 프레임 수(int, 최소 1)
    """
    return max(1, math.ceil(round(ms / (hop_sec * 1000.0), 9)))  # 최소 1 프레임


def hangover_frames(scores, hop_sec, threshold, speech_ms, silence_ms):
    """
    목적: SimpleHangover 기준 구현(프레임 인덱스 단위)
    기능: 파일 머리 주석의 규칙대로 음성 구간을 [시작 프레임, 끝 프레임(배타)) 목록으로 만든다.
    입력: scores(프레임 점수 시퀀스), hop_sec, threshold, speech_ms, silence_ms
    출력: 없음
    리턴: [[startFrame, endFrameExclusive]] 리스트
    """
    need_sp = need_frames(speech_ms, hop_sec)  # 시작에 필요한 연속 음성 프레임 수
    need_sil = need_frames(silence_ms, hop_sec)  # 종료에 필요한 연속 무음 프레임 수
    segs = []  # 결과
    in_speech = False  # 현재 음성 상태 여부
    run_start = 0  # (비음성 상태) 현재 음성 연속의 첫 프레임
    run_len = 0  # (비음성 상태) 현재 음성 연속 길이
    sil_start = 0  # (음성 상태) 현재 무음 연속의 첫 프레임
    sil_len = 0  # (음성 상태) 현재 무음 연속 길이
    seg_start = 0  # 현재 구간 시작 프레임
    for i, s in enumerate(scores):  # 프레임마다
        voiced = s >= threshold  # 음성 프레임 판정
        if not in_speech:  # 비음성 상태
            if voiced:  # 음성 프레임이면
                if run_len == 0:  # 새 연속 시작
                    run_start = i  # 연속 첫 프레임 기록
                run_len += 1  # 연속 길이 증가
                if run_len >= need_sp:  # 시작 조건 충족
                    in_speech = True  # 음성 상태로
                    seg_start = run_start  # 연속 구간 첫 프레임에서 시작
                    sil_len = 0  # 무음 카운트 초기화
            else:  # 무음 프레임이면
                run_len = 0  # 음성 연속 끊김
        else:  # 음성 상태
            if voiced:  # 음성 프레임이면
                sil_len = 0  # 무음 연속 끊김(짧은 무음 메움)
            else:  # 무음 프레임이면
                if sil_len == 0:  # 새 무음 연속 시작
                    sil_start = i  # 무음 첫 프레임 기록
                sil_len += 1  # 무음 길이 증가
                if sil_len >= need_sil:  # 종료 조건 충족
                    segs.append([seg_start, sil_start])  # 무음 시작에서 종료
                    in_speech = False  # 비음성 상태로
                    run_len = 0  # 음성 카운트 초기화
    if in_speech:  # 끝까지 음성 상태면
        segs.append([seg_start, sil_start if sil_len > 0 else len(scores)])  # 진행 중 무음 시작 또는 끝에서 종료
    return segs  # 반환


def merge_contiguous_frames(flags):
    """
    목적: RAW 후처리(연속 음성 프레임 병합)
    기능: 참(1)인 프레임이 연속된 구간을 [시작, 끝(배타)) 프레임 목록으로 만든다.
    입력: flags(0/1 또는 bool 시퀀스)
    출력: 없음
    리턴: [[startFrame, endFrameExclusive]] 리스트
    """
    segs = []  # 결과
    start = None  # 현재 연속 시작
    for i, f in enumerate(flags):  # 프레임마다
        if f and start is None:  # 연속 시작
            start = i  # 기록
        elif not f and start is not None:  # 연속 끝
            segs.append([start, i])  # 구간 추가
            start = None  # 초기화
    if start is not None:  # 끝까지 연속이면
        segs.append([start, len(flags)])  # 마지막 구간
    return segs  # 반환


def frames_to_sec(frame_pairs, hop_sec):
    """
    목적: 프레임 구간을 초 단위로 변환
    기능: 각 인덱스에 hopSec 을 곱한다.
    입력: frame_pairs([[s, e]]), hop_sec
    출력: 없음
    리턴: [(startSec, endSec)] 리스트
    """
    return [(s * hop_sec, e * hop_sec) for s, e in frame_pairs]  # 초 변환


def _self_test():
    """
    목적: 계획서 손작성 케이스로 기준 구현 자체 확인
    기능: hop 0.01, 음성 50·무음 20·음성 50 프레임, silenceMs 300 → [0.0, 1.2] 1개, 0/0 은 RAW 와 같음을 확인한다.
    입력: 없음
    출력: 없음(실패 시 AssertionError)
    리턴: 없음
    """
    scores = [1.0] * 50 + [0.0] * 20 + [1.0] * 50  # 손작성 점수
    segs = frames_to_sec(hangover_frames(scores, 0.01, 0.5, 0, 300), 0.01)  # hangover 적용
    assert len(segs) == 1 and abs(segs[0][0]) < 1e-12 and abs(segs[0][1] - 1.2) < 1e-9, segs  # [0.0, 1.2]
    assert hangover_frames(scores, 0.01, 0.5, 0, 0) == merge_contiguous_frames([s >= 0.5 for s in scores])  # 0/0 = RAW
    assert need_frames(300, 0.02) == 15 and need_frames(50, 0.02) == 3 and need_frames(0, 0.016) == 1  # 프레임 수 규칙


_self_test()  # import 시 항상 자체 확인
