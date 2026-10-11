// 파일명: WebRtcPost.kt
// 목적: WebRTC(libfvad) 프레임별 voiced/unvoiced 판정 → 음성 구간 후처리 (WEBRTC_LIB_DEFAULT, WEBRTC_PYEXAMPLE 용)
// 기능: - raw: 연속 voiced 프레임 병합(tools/golden/hangover_ref.py `merge_contiguous_frames` 포팅)
//       - ringBuffer: py-webrtcvad `example.py` 의 `vad_collector` 포팅(tools/golden/gen_webrtc.py 의 복사본과 같은 로직;
//         원본 출처 https://github.com/wiseman/py-webrtcvad/blob/e283ca41df3a84b0e87fb1f5cb9b21580a286b09/example.py , MIT License,
//         Copyright (c) 2016 John Wiseman. 이 파일은 그 로직을 Kotlin 으로 다시 쓴 파생물이며 원 저작권 고지는 tools/golden/gen_webrtc.py 에 전문이 있다)
//       - 입력은 "완전한 프레임" 만 담은 Boolean 배열이다. 잔여 샘플(프레임이 안 되는 끝부분)은 호출자가 이미 버린 상태이며,
//         구간 끝 시각은 항상 (마지막으로 판정된 프레임 번호 + 1) × frameMs/1000 이다(잔여 샘플 시간은 더하지 않음, 골든과 동일).
//       - HANGOVER(WEBRTC_ANDROIDVAD) 는 SimpleHangover 가 담당한다.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 패키지

import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간

// change(add)-hyungchul-2026-10-10-13:43
/**
 * 목적: WebRTC 프레임 판정의 구간화(RAW / RING_BUFFER) Kotlin 포팅
 * 기능: 프레임 i 의 시각은 i × (frameMs/1000.0)(프레임 시작)이며 골든(Python 의 frame 번호 × hop_sec)과 같은 double 연산을 쓴다.
 */
object WebRtcPost {

    /**
     * 목적: 후처리 없는 구간화(WEBRTC_LIB_DEFAULT)
     * 기능: hangover_ref.py `merge_contiguous_frames` 포팅. 참(voiced)이 이어진 프레임을 [첫 프레임, 첫 unvoiced 프레임) 구간으로 만들고
     *       끝까지 이어지면 마지막 프레임 끝(flags.size 번째 경계)까지로 닫는다. 시각 = 프레임 번호 × hop.
     * 입력: flags(프레임별 voiced 여부), frameMs(프레임 길이 ms)
     * 출력: 없음
     * 리턴: 음성 구간 목록(초, 시작순)
     */
    fun raw(flags: BooleanArray, frameMs: Int): List<SpeechSegment> {
        val hop = frameMs / 1000.0 // 프레임 간격(초): Python `frame_ms / 1000.0` 와 같은 연산
        val out = ArrayList<SpeechSegment>() // 결과 구간
        var start = -1 // 현재 연속 voiced 의 첫 프레임(없으면 -1; Python 의 None)
        for (i in flags.indices) { // 프레임마다
            if (flags[i] && start < 0) { // 연속 시작
                start = i // 시작 프레임 기록
            } else if (!flags[i] && start >= 0) { // 연속 끝
                out += SpeechSegment(start * hop, i * hop) // [start, i) 구간 추가
                start = -1 // 초기화
            }
        }
        if (start >= 0) out += SpeechSegment(start * hop, flags.size * hop) // 끝까지 이어진 구간은 마지막 프레임 끝에서 닫음
        return out // 반환
    }

    /**
     * 목적: ring buffer 방식 구간화(WEBRTC_PYEXAMPLE)
     * 기능: py-webrtcvad example.py `vad_collector` 포팅.
     *       - 링버퍼 길이 = paddingMs / frameMs(정수 나눗셈, Python `int(padding/frame)` 와 같음).
     *       - 비트리거: (프레임, voiced) 를 버퍼에 넣고 voiced 수 > ratio × 버퍼길이 이면 트리거, 구간 시작 = 버퍼의 첫(가장 오래된) 프레임, 버퍼 비움.
     *       - 트리거: 프레임 수집 + 버퍼에 넣고 unvoiced 수 > ratio × 버퍼길이 이면 종료(현재 프레임 포함, 끝 = 현재 프레임 번호 + 1), 버퍼 비움.
     *       - 입력 끝에서 트리거 상태면 마지막 프레임까지 구간(끝 = flags.size 번째 경계).
     *       - 비교식의 ratio 는 Float 의 최단 10진 표기를 double 로 읽어(0.9f → 0.9) Python 의 0.9 × 10 = 9.0 과 같은 값을 만든다
     *         (0.9f 를 그대로 double 로 바꾸면 0.8999999762 가 되어 9 개 voiced 에서 잘못 트리거됨).
     * 입력: flags(프레임별 voiced 여부; 완전한 프레임만), frameMs(프레임 길이 ms), paddingMs(링버퍼 패딩 ms), ratio(트리거 비율)
     * 출력: 없음
     * 리턴: 음성 구간 목록(초, 시작순)
     */
    fun ringBuffer(flags: BooleanArray, frameMs: Int, paddingMs: Int, ratio: Float): List<SpeechSegment> {
        val hop = frameMs / 1000.0 // 프레임 간격(초)
        val maxLen = paddingMs / frameMs // 링버퍼 길이 = int(padding_duration_ms / frame_duration_ms)
        require(maxLen >= 1) { "링버퍼 길이는 1 이상이어야 함: paddingMs=$paddingMs frameMs=$frameMs" } // 길이 0 은 원본에서도 무의미
        val limit = maxLen * ratio.toString().toDouble() // 0.9 * ring_buffer.maxlen (Python float 연산과 같은 double)
        val ringIdx = IntArray(maxLen) // 링버퍼의 프레임 번호(원형 배열; deque(maxlen) 대체)
        val ringVoiced = BooleanArray(maxLen) // 링버퍼의 voiced 값
        var ringStart = 0 // 가장 오래된 원소 위치
        var ringSize = 0 // 현재 원소 수
        val out = ArrayList<SpeechSegment>() // 결과 구간
        var triggered = false // 트리거 상태(시작은 NOTTRIGGERED)
        var segFirst = -1 // 수집 중인 구간의 첫 프레임 번호(voiced_frames[0].index)
        for (i in flags.indices) { // 프레임마다(Python: for frame in frames)
            val isSpeech = flags[i] // is_speech 결과
            // 링버퍼에 (프레임, voiced) 추가: 가득 차 있으면 가장 오래된 원소를 버림(deque maxlen)
            if (ringSize == maxLen) { // 가득 참
                ringStart = (ringStart + 1) % maxLen // 가장 오래된 원소 제거
                ringSize-- // 원소 수 감소
            }
            val pos = (ringStart + ringSize) % maxLen // 새 원소 위치
            ringIdx[pos] = i // 프레임 번호 저장
            ringVoiced[pos] = isSpeech // voiced 저장
            ringSize++ // 원소 수 증가
            var numVoiced = 0 // 버퍼 안 voiced 수
            for (k in 0 until ringSize) if (ringVoiced[(ringStart + k) % maxLen]) numVoiced++ // voiced 센다
            if (!triggered) { // 비트리거 상태
                if (numVoiced > limit) { // voiced 가 비율 초과
                    triggered = true // 트리거
                    segFirst = ringIdx[ringStart] // 구간 시작 = 버퍼의 첫 프레임 (voiced_frames 의 첫 원소)
                    ringSize = 0 // ring_buffer.clear()
                    ringStart = 0 // 위치도 초기화
                }
            } else { // 트리거 상태 (현재 프레임은 이미 수집·버퍼 추가됨)
                val numUnvoiced = ringSize - numVoiced // unvoiced 수
                if (numUnvoiced > limit) { // unvoiced 가 비율 초과
                    triggered = false // 비트리거로
                    out += SpeechSegment(segFirst * hop, (i + 1) * hop) // 시작 = 첫 수집 프레임, 끝 = 현재(마지막 수집) 프레임 + 1
                    ringSize = 0 // ring_buffer.clear()
                    ringStart = 0 // 위치도 초기화
                    segFirst = -1 // voiced_frames = []
                }
            }
        }
        if (triggered) out += SpeechSegment(segFirst * hop, flags.size * hop) // 입력 끝에서 열린 구간: 마지막 프레임 번호 + 1 = flags.size
        return out // 반환
    }
}
