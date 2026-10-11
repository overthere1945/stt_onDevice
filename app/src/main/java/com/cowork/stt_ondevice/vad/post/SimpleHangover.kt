// 파일명: SimpleHangover.kt
// 목적: 프레임별 점수 → 음성 구간 hangover 상태기계 (WEBRTC_ANDROIDVAD, TEN_DEFAULT(0/0), 이후 YAMNET_* 가 공유)
// 기능: tools/golden/hangover_ref.py `hangover_frames`/`need_frames` 를 그대로 포팅한다(정의: tools/golden/README_한국어.md §6.5).
//       - score ≥ threshold 인 프레임이 음성 프레임
//       - needSpeech = max(1, ceil(round(speechMs / hopMs, 9))), needSilence 도 같은 식
//       - 비음성 상태: 연속 음성 프레임이 needSpeech 에 도달하면 그 연속의 첫 프레임에서 구간 시작
//       - 음성 상태: 연속 무음이 needSilence 에 도달하면 그 무음 연속의 첫 프레임에서 구간 종료(짧은 무음은 메움)
//       - 끝에서 음성 상태: 진행 중 무음 연속이 있으면 그 첫 프레임, 없으면 프레임 수 n 에서 종료
//       - 시각 = 프레임 인덱스 × hopSec. speechMs = silenceMs = 0 이면 연속 음성 프레임 병합(RAW)과 같다.
package com.cowork.stt_ondevice.vad.post // VAD 후처리 패키지

import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import java.math.BigDecimal // 정확한 십진 반올림(Python round(x, 9) 재현)
import java.math.RoundingMode // 반올림 모드

// change(add)-hyungchul-2026-10-10-13:43
/**
 * 목적: SimpleHangover 의 Kotlin 구현
 * 기능: 프레임 점수 배열을 hangover 규칙으로 음성 구간(초)으로 바꾼다.
 */
object SimpleHangover {

    /**
     * 목적: 시간(ms)을 필요한 연속 프레임 수로 변환 (hangover_ref.py `need_frames`)
     * 기능: max(1, ceil(round(ms / (hopSec × 1000), 9))). 소수 9자리 반올림은 15.000000001 같은 부동소수 오차로 16 이 되는 것을 막는다.
     *       Python round(x, 9) 는 double 의 정확한 값을 half-even 으로 반올림하므로 BigDecimal(x) 의 HALF_EVEN 으로 같은 결과를 낸다.
     * 입력: ms(밀리초), hopSec(프레임 간격 초)
     * 출력: 없음
     * 리턴: 프레임 수(최소 1)
     */
    internal fun needFrames(ms: Int, hopSec: Double): Int {
        val ratio = ms / (hopSec * 1000.0) // ms / hopMs (Python 과 같은 double 연산 순서)
        val rounded = BigDecimal(ratio).setScale(9, RoundingMode.HALF_EVEN) // 소수 9자리 반올림
        val ceil = rounded.setScale(0, RoundingMode.CEILING).toInt() // 올림
        return maxOf(1, ceil) // 최소 1 프레임
    }

    /**
     * 목적: 프레임 점수에 hangover 규칙을 적용해 음성 구간 생성
     * 기능: hangover_ref.py `hangover_frames` 를 줄 단위로 포팅하고 프레임 인덱스를 초로 바꾼다.
     *       음성 판정 비교는 Python(numpy float32 vs Python float → float64)과 같도록 점수를 double 로, 임계값을 Float 최단 10진 표기의
     *       double(0.5f → 0.5, 0.3f → 0.3)로 만들어 비교한다.
     * 입력: scores(프레임 점수), hopSec(프레임 간격 초), threshold(음성 임계값), speechMs(시작 확정 길이 ms), silenceMs(종료 확정 길이 ms)
     * 출력: 없음
     * 리턴: 음성 구간 목록(초, 시작순)
     */
    fun apply(scores: FloatArray, hopSec: Double, threshold: Float, speechMs: Int, silenceMs: Int): List<SpeechSegment> {
        val th = threshold.toString().toDouble() // 임계값(Python float 와 같은 double)
        val needSp = needFrames(speechMs, hopSec) // 시작에 필요한 연속 음성 프레임 수
        val needSil = needFrames(silenceMs, hopSec) // 종료에 필요한 연속 무음 프레임 수
        val out = ArrayList<SpeechSegment>() // 결과
        var inSpeech = false // 현재 음성 상태 여부
        var runStart = 0 // (비음성 상태) 현재 음성 연속의 첫 프레임
        var runLen = 0 // (비음성 상태) 현재 음성 연속 길이
        var silStart = 0 // (음성 상태) 현재 무음 연속의 첫 프레임
        var silLen = 0 // (음성 상태) 현재 무음 연속 길이
        var segStart = 0 // 현재 구간 시작 프레임
        for (i in scores.indices) { // 프레임마다
            val voiced = scores[i].toDouble() >= th // 음성 프레임 판정
            if (!inSpeech) { // 비음성 상태
                if (voiced) { // 음성 프레임이면
                    if (runLen == 0) runStart = i // 새 연속 시작: 첫 프레임 기록
                    runLen++ // 연속 길이 증가
                    if (runLen >= needSp) { // 시작 조건 충족
                        inSpeech = true // 음성 상태로
                        segStart = runStart // 연속 구간 첫 프레임에서 시작
                        silLen = 0 // 무음 카운트 초기화
                    }
                } else { // 무음 프레임이면
                    runLen = 0 // 음성 연속 끊김
                }
            } else { // 음성 상태
                if (voiced) { // 음성 프레임이면
                    silLen = 0 // 무음 연속 끊김(짧은 무음 메움)
                } else { // 무음 프레임이면
                    if (silLen == 0) silStart = i // 새 무음 연속 시작: 첫 프레임 기록
                    silLen++ // 무음 길이 증가
                    if (silLen >= needSil) { // 종료 조건 충족
                        out += SpeechSegment(segStart * hopSec, silStart * hopSec) // 무음 시작에서 종료
                        inSpeech = false // 비음성 상태로
                        runLen = 0 // 음성 카운트 초기화
                    }
                }
            }
        }
        if (inSpeech) { // 끝까지 음성 상태면
            val end = if (silLen > 0) silStart else scores.size // 진행 중 무음 시작 또는 끝(n)에서 종료
            out += SpeechSegment(segStart * hopSec, end * hopSec) // 마지막 구간
        }
        return out // 반환
    }
}
