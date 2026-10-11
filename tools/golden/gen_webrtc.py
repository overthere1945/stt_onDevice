# 파일명: gen_webrtc.py
# 목적: WebRTC VAD 골든 데이터 생성(Kotlin WebRtcPost·SimpleHangover 포팅 검증 기준)
# 기능: webrtcvad(webrtcvad-wheels) 프레임별 is_speech 결과(0/1 → frameScores)와 프리셋별 후처리 결과를 쓴다.
#         - RAW(WEBRTC_LIB_DEFAULT): 연속 voiced 프레임 병합
#         - RING_BUFFER(WEBRTC_PYEXAMPLE): py-webrtcvad example.py 의 frame_generator/vad_collector 를 그대로 복사
#           (출처: https://github.com/wiseman/py-webrtcvad/blob/e283ca41df3a84b0e87fb1f5cb9b21580a286b09/example.py ,
#            MIT License, Copyright (c) 2016 John Wiseman). 변경점은 함수 주석에 표시.
#         - HANGOVER(WEBRTC_ANDROIDVAD): hangover_ref.hangover_frames(계획서 의미)
#       WebRTC VAD 는 상태를 가지므로(잡음 적응) 프리셋마다 새 Vad 인스턴스로 프레임을 처음부터 순서대로 처리한다.
# change(add)-hyungchul-2026-10-10-12:19

import collections  # deque(ring buffer)
import os  # 경로
import sys  # 모듈 경로 추가
from importlib.metadata import version  # 패키지 버전

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # python -I 실행에서도 공통 모듈을 찾게 함

import webrtcvad  # py-webrtcvad

import golden_common as gc  # 공통 도우미
import hangover_ref as hr  # hangover 기준 구현

TOOL = f"webrtcvad-wheels {version('webrtcvad-wheels')} + py-webrtcvad example.py@e283ca4 vad_collector"  # 도구 표기


# ---------------- py-webrtcvad example.py 복사 시작 (MIT, John Wiseman) ----------------
# change(add)-hyungchul-2026-10-10-12:52 아래는 py-webrtcvad LICENSE(커밋 e283ca4)의 MIT 허락 고지 원문이다(복사본에 포함 의무).
# The MIT License (MIT)
#
# Copyright (c) 2016 John Wiseman
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in all
# copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
# SOFTWARE.
# 목적: 오디오 프레임 1개 표현(원본 클래스) / 기능: PCM 바이트·시작 시각·길이·번호 보관
class Frame(object):
    """Represents a "frame" of audio data."""
    # change(add)-hyungchul-2026-10-10-12:19 변경: 프레임 번호(index) 필드 추가(구간을 프레임 인덱스로 기록하기 위함)
    # 목적: 프레임 생성 / 기능: 필드 저장 / 입력: bytes, timestamp, duration, index / 출력: 없음 / 리턴: 없음
    def __init__(self, bytes, timestamp, duration, index=0):
        self.bytes = bytes  # PCM16 바이트
        self.timestamp = timestamp  # 시작 시각(초, 누적 합)
        self.duration = duration  # 길이(초)
        self.index = index  # 추가: 프레임 번호


# 목적: PCM 바이트를 고정 길이 프레임으로 나눔(원본 함수)
# 기능: offset + n < len(audio) 인 동안 n 바이트씩 Frame 을 만든다
# 입력: frame_duration_ms(프레임 ms), audio(PCM16 바이트), sample_rate / 출력: 없음 / 리턴: Frame 제너레이터
def frame_generator(frame_duration_ms, audio, sample_rate):
    """Generates audio frames from PCM audio data.

    Takes the desired frame duration in milliseconds, the PCM data, and
    the sample rate.

    Yields Frames of the requested duration.
    """
    # change(add)-hyungchul-2026-10-10-12:19 변경: Frame 에 index 전달만 추가(조건 offset + n < len(audio) 등은 원본 그대로)
    n = int(sample_rate * (frame_duration_ms / 1000.0) * 2)  # 프레임 바이트 수
    offset = 0  # 바이트 오프셋
    timestamp = 0.0  # 시작 시각
    duration = (float(n) / sample_rate) / 2.0  # 프레임 길이(초)
    index = 0  # 추가: 프레임 번호
    while offset + n < len(audio):  # 원본 조건(엄격한 <: 끝에 딱 맞는 마지막 프레임은 제외됨)
        yield Frame(audio[offset:offset + n], timestamp, duration, index)  # 프레임 생성
        timestamp += duration  # 시각 누적
        offset += n  # 다음 오프셋
        index += 1  # 추가: 번호 증가


# 목적: ring buffer 방식 음성 구간 수집(원본 함수)
# 기능: 10 프레임 창의 90% 초과 voiced 면 시작, 90% 초과 unvoiced 면 종료
# 입력: sample_rate, frame_duration_ms, padding_duration_ms, vad(is_speech 제공 객체), frames(Frame 목록)
# 출력: 없음 / 리턴: (첫 프레임 번호, 마지막 프레임 번호) 제너레이터
def vad_collector(sample_rate, frame_duration_ms,
                  padding_duration_ms, vad, frames):
    """Filters out non-voiced audio frames.

    Given a webrtcvad.Vad and a source of audio frames, yields only
    the voiced audio.

    Uses a padded, sliding window algorithm over the audio frames.
    When more than 90% of the frames in the window are voiced (as
    reported by the VAD), the collector triggers and begins yielding
    audio frames. Then the collector waits until 90% of the frames in
    the window are unvoiced to detrigger.

    The window is padded at the front and back to provide a small
    amount of silence or the beginnings/endings of speech around the
    voiced frames.
    """
    # change(add)-hyungchul-2026-10-10-12:19 변경: (1) sys.stdout 출력 제거 (2) 오디오 바이트 대신
    #   (첫 voiced 프레임 번호, 마지막 voiced 프레임 번호) 를 yield. 판정 로직(0.9 비율, deque, clear)은 원본 그대로.
    num_padding_frames = int(padding_duration_ms / frame_duration_ms)  # ring buffer 길이(300/30 = 10)
    # We use a deque for our sliding window/ring buffer.
    ring_buffer = collections.deque(maxlen=num_padding_frames)  # 슬라이딩 창
    # We have two states: TRIGGERED and NOTTRIGGERED. We start in the
    # NOTTRIGGERED state.
    triggered = False  # 시작은 비트리거

    voiced_frames = []  # 수집 중인 프레임
    for frame in frames:  # 프레임마다
        is_speech = vad.is_speech(frame.bytes, sample_rate)  # WebRTC 판정

        if not triggered:  # 비트리거 상태
            ring_buffer.append((frame, is_speech))  # 창에 추가
            num_voiced = len([f for f, speech in ring_buffer if speech])  # voiced 수
            # If we're NOTTRIGGERED and more than 90% of the frames in
            # the ring buffer are voiced frames, then enter the
            # TRIGGERED state.
            if num_voiced > 0.9 * ring_buffer.maxlen:  # 90% 초과 voiced
                triggered = True  # 트리거
                # We want to yield all the audio we see from now until
                # we are NOTTRIGGERED, but we have to start with the
                # audio that's already in the ring buffer.
                for f, s in ring_buffer:  # 창 안 프레임부터
                    voiced_frames.append(f)  # 수집 시작
                ring_buffer.clear()  # 창 비움
        else:  # 트리거 상태
            # We're in the TRIGGERED state, so collect the audio data
            # and add it to the ring buffer.
            voiced_frames.append(frame)  # 수집
            ring_buffer.append((frame, is_speech))  # 창에 추가
            num_unvoiced = len([f for f, speech in ring_buffer if not speech])  # unvoiced 수
            # If more than 90% of the frames in the ring buffer are
            # unvoiced, then enter NOTTRIGGERED and yield whatever
            # audio we've collected.
            if num_unvoiced > 0.9 * ring_buffer.maxlen:  # 90% 초과 unvoiced
                triggered = False  # 비트리거
                yield (voiced_frames[0].index, voiced_frames[-1].index)  # 변경: 구간 프레임 번호 반환
                ring_buffer.clear()  # 창 비움
                voiced_frames = []  # 수집 초기화
    # If we have any leftover voiced audio when we run out of input,
    # yield it.
    if voiced_frames:  # 남은 수집분
        yield (voiced_frames[0].index, voiced_frames[-1].index)  # 변경: 구간 프레임 번호 반환
# ---------------- py-webrtcvad example.py 복사 끝 ----------------


class RecordingVad:
    """
    목적: vad_collector 안에서 호출된 is_speech 결과를 기록
    기능: webrtcvad.Vad 호출을 위임하고 결과를 순서대로 저장한다.
    """

    def __init__(self, mode):
        """
        목적: 래퍼 생성
        기능: 새 webrtcvad.Vad(mode) 를 만들고 기록 목록을 비운다.
        입력: mode(0~3)
        출력: 없음
        리턴: 없음
        """
        self.vad = webrtcvad.Vad(mode)  # 새 인스턴스(상태 초기)
        self.flags = []  # 기록

    def is_speech(self, buf, sample_rate):
        """
        목적: 판정 위임 + 기록
        기능: 원본 판정 결과를 기록하고 그대로 돌려준다.
        입력: buf(PCM16 바이트), sample_rate
        출력: 없음
        리턴: bool
        """
        r = self.vad.is_speech(buf, sample_rate)  # 원본 판정
        self.flags.append(bool(r))  # 기록
        return r  # 반환


def full_frame_flags(pcm_bytes, mode, frame_ms):
    """
    목적: RAW/HANGOVER 용 프레임 판정
    기능: floor(N / 프레임 길이) 개의 완전한 프레임을 새 Vad 로 순서대로 판정한다(libfvad 사용 방식과 같음).
    입력: pcm_bytes(PCM16 바이트), mode, frame_ms
    출력: 없음
    리턴: bool 리스트
    """
    vad = webrtcvad.Vad(mode)  # 새 인스턴스
    n = gc.SAMPLE_RATE * frame_ms // 1000 * 2  # 프레임 바이트 수
    count = len(pcm_bytes) // n  # 완전한 프레임 수
    return [bool(vad.is_speech(pcm_bytes[i * n:(i + 1) * n], gc.SAMPLE_RATE)) for i in range(count)]  # 순서대로 판정


def main():
    """
    목적: WebRTC 골든 생성 진입점
    기능: 클립·프리셋마다 프레임 판정과 후처리 결과를 골든 JSON 으로 쓴다.
    입력: 없음
    출력: golden/WEBRTC_*.json
    리턴: 없음
    """
    for clip in gc.CLIPS:  # 클립마다
        _, pcm = gc.load_clip(clip)  # int16 파형
        pcm_bytes = pcm.astype("<i2").tobytes()  # little-endian PCM16 바이트
        for pid, (mode, frame_ms, style, pad_ms, ratio, sp_ms, sil_ms) in gc.WEBRTC_PRESETS.items():  # 프리셋마다
            hop = frame_ms / 1000.0  # 프레임 간격(초)
            if style == "RING_BUFFER":  # example.py 방식
                assert ratio == 0.9, "example.py 는 0.9 고정"  # 원본 상수 확인
                frames = list(frame_generator(frame_ms, pcm_bytes, gc.SAMPLE_RATE))  # 원본 프레임 생성기
                rv = RecordingVad(mode)  # 기록 Vad
                idx = list(vad_collector(gc.SAMPLE_RATE, frame_ms, pad_ms, rv, frames))  # 원본 수집기
                flags = rv.flags  # 프레임 판정
                frame_pairs = [[s, e + 1] for s, e in idx]  # [첫 프레임, 마지막 프레임+1)
            else:  # RAW / HANGOVER
                flags = full_frame_flags(pcm_bytes, mode, frame_ms)  # 완전한 프레임 판정
                if style == "RAW":  # 병합만
                    frame_pairs = hr.merge_contiguous_frames(flags)  # 연속 병합
                    assert frame_pairs == hr.hangover_frames([float(f) for f in flags], hop, 0.5, 0, 0), "RAW ≠ hangover(0,0)"  # 일치 확인
                else:  # HANGOVER
                    frame_pairs = hr.hangover_frames([float(f) for f in flags], hop, 0.5, sp_ms, sil_ms)  # 기준 구현
            pairs = hr.frames_to_sec(frame_pairs, hop)  # 초 변환
            gc.write_golden(  # 골든 저장
                "WEBRTC", pid, clip, hop, [1.0 if f else 0.0 for f in flags], pairs, TOOL,  # 0/1 점수
                extra={"expectedFrames": frame_pairs,  # 프레임 인덱스 구간 [s, e)
                       "totalSamples": len(pcm),  # change(add)-hyungchul-2026-10-10-12:52 전체 샘플 수
                       "params": {"mode": mode, "frameMs": frame_ms, "style": style, "paddingMs": pad_ms,  # 파라미터
                                  "ratio": ratio, "speechMs": sp_ms, "silenceMs": sil_ms, "threshold": 0.5}},  # 파라미터
            )
            print(clip, pid, len(flags), len(frame_pairs), frame_pairs[:4])  # 진행 로그


if __name__ == "__main__":  # 스크립트 실행 시
    main()  # 진입점 호출
