// 파일명: TenVadTest.kt (테스트 소스)
// 목적: TenVad(TEN 네이티브 + Kotlin 후처리)의 Kotlin 쪽 프레임 처리가 골든과 정확히 일치하는지 JVM 에서 검증
// 기능: JVM 은 arm64 libten_vad.so 를 올릴 수 없으므로 네이티브 호출 자리에 가짜 함수를 주입한다.
//       가짜 함수는 (1) 엔진이 넘긴 threshold 가 프리셋 값인지 (2) 넘긴 PCM16 길이가 floor(N/256)·256 인지(잔여 샘플 버림)
//       (3) float → PCM16 변환이 원래 int16 값을 그대로 복원했는지 확인한 뒤 골든 frameScores(공식 ten-vad 확률)를 돌려준다.
//       TEN_DEFAULT 4개(SimpleHangover hop 0.016) + TEN_SHERPA 7개(SileroTimestamps window 256, 손작성 3종 포함)의 expected 와 비교한다.
//       실제 네이티브 확률은 기기 테스트(NativeSmokeTest)가 검증한다.
// change(add)-hyungchul-2026-10-10-15:26
package com.cowork.stt_ondevice.vad.impl // VAD 구현 테스트 패키지

import com.cowork.stt_ondevice.vad.Golden // 골든 1건
import com.cowork.stt_ondevice.vad.GoldenLoader // 골든 로더
import com.cowork.stt_ondevice.vad.HangoverParams // TEN_DEFAULT 파라미터
import com.cowork.stt_ondevice.vad.SileroParams // TEN_SHERPA 파라미터
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-15:26
/**
 * 목적: TenVad 프레임 처리·후처리 연결의 골든 회귀 방지
 * 기능: 네이티브 확률 대신 골든 확률을 주입해 엔진 전체 경로(PCM 변환 → hop 256 프레임 수 → 프리셋별 후처리 → VadOutput)를 검증한다.
 */
class TenVadTest {

    /**
     * 목적: 테스트 입력 샘플 값 생성(결정적)
     * 기능: 샘플 위치마다 int16 전 범위를 고루 거치는 값을 만든다(골든 확률을 주입하므로 내용은 결과와 무관).
     * 입력: i(샘플 위치)
     * 출력: 없음
     * 리턴: int16 범위 정수
     */
    private fun pattern(i: Int): Int = ((i.toLong() * 7919L) % 65536L).toInt() - 32768 // 소수 배수로 전 범위 순환

    /**
     * 목적: TEN_* 골든 11개 전부를 엔진 경로로 재현
     * 기능: 개수(파일 수·TEN_DEFAULT 4·TEN_SHERPA 7)를 고정하고 골든마다 비교한다.
     * 입력: 없음
     * 출력: 없음(불일치 시 실패 목록을 담은 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun matchesAllTenGoldensThroughEngine() {
        val goldens = GoldenLoader.allFor("TEN") // TEN_* 구간 골든 전부
        assertEquals("읽은 골든 수 ≠ 파일 수", GoldenLoader.countFiles("TEN_"), goldens.size) // 빠진 파일 없음
        assertEquals("TEN_DEFAULT 골든 4개", 4, goldens.count { it.presetId == "TEN_DEFAULT" }) // 실제 클립 4
        assertEquals("TEN_SHERPA 골든 7개", 7, goldens.count { it.presetId == "TEN_SHERPA" }) // 실제 클립 4 + 손작성 3
        val failures = mutableListOf<String>() // 실패 메시지 모음
        for (g in goldens) { // 골든마다
            try { // 한 골든 실패가 나머지 비교를 막지 않도록
                checkOne(g) // 개별 비교
            } catch (e: AssertionError) { // 불일치
                failures += "${g.name}: ${e.message}" // 기록
            }
        }
        assertTrue("${failures.size}/${goldens.size} 개 불일치\n" + failures.joinToString("\n"), failures.isEmpty()) // 전부 통과해야 함
    }

    /**
     * 목적: 골든 1건을 엔진 경로로 비교
     * 기능: 가짜 네이티브가 threshold·PCM 길이·PCM 값을 확인하고 골든 확률을 돌려준다. 결과 구간은 expected 와 정확히 같아야 하고
     *       (TEN_DEFAULT 는 expectedFrames, TEN_SHERPA 는 expectedSamples 와도 비교), frameScores·frameHopSec 도 골든과 같아야 한다.
     * 입력: g(골든)
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    private fun checkOne(g: Golden) {
        val preset = VadPresets.byId(g.presetId) // 앱이 실제로 쓸 프리셋
        val threshold = when (val p = preset.params) { // 프리셋 임계값
            is HangoverParams -> p.threshold // TEN_DEFAULT
            is SileroParams -> p.threshold // TEN_SHERPA
            else -> throw AssertionError("TEN 프리셋 params 타입 이상: $p") // 그 외는 오류
        }
        val n = g.totalSamples!! // 전체 샘플 수 N
        assertEquals("골든 프레임 수 = floor(N/256)", n / 256, g.frameScores.size) // 골든 자체 확인(README §5.1)
        var calls = 0 // 네이티브 호출 횟수
        val engine = TenVad { th, pcm -> // 가짜 네이티브 확률 주입
            calls++ // 호출 기록
            assertEquals("threshold", threshold, th, 0.0f) // 프리셋 임계값 전달
            assertEquals("PCM 길이 = floor(N/256)·256", (n / 256) * 256, pcm.size) // 잔여 샘플 버림
            for (i in pcm.indices) if (pcm[i].toInt() != pattern(i)) throw AssertionError("PCM16 값 불일치 @$i") // 변환 복원 확인
            g.frameScores.copyOf() // 골든 확률 반환
        }
        val out = engine.detect(FloatArray(n) { pattern(it) / 32768f }, preset) // 엔진 실행
        assertEquals("네이티브 호출 횟수", 1, calls) // 정확히 1회
        assertEquals("구간(비트 단위 일치)", g.expected, out.segments) // 골든 구간과 동일
        assertEquals("frameHopSec", g.hopSec!!, out.frameHopSec!!, 0.0) // 0.016
        assertArrayEquals("frameScores", g.frameScores, out.frameScores!!, 0.0f) // 확률 그대로
        g.expectedFrames?.let { ef -> // TEN_DEFAULT: 프레임 인덱스 비교
            assertEquals("expectedFrames 개수", ef.size, out.segments.size) // 개수
            out.segments.forEachIndexed { i, s -> // 구간마다
                assertEquals("구간[$i] 시작 프레임", ef[i][0].toLong(), Math.round(s.startSec / g.hopSec)) // 시작 프레임
                assertEquals("구간[$i] 끝 프레임", ef[i][1].toLong(), Math.round(s.endSec / g.hopSec)) // 끝 프레임(배타)
            }
        }
        g.expectedSamples?.let { es -> // TEN_SHERPA: 정수 샘플 비교
            assertEquals("expectedSamples 개수", es.size, out.segments.size) // 개수
            out.segments.forEachIndexed { i, s -> // 구간마다
                assertEquals("구간[$i] 시작 샘플", es[i][0].toLong(), Math.round(s.startSec * 16000)) // 시작 샘플
                assertEquals("구간[$i] 끝 샘플", es[i][1].toLong(), Math.round(s.endSec * 16000)) // 끝 샘플
            }
        }
    }

    /**
     * 목적: 네이티브 확률 개수 이상 시 조용히 넘어가지 않음 검증
     * 기능: 확률 개수가 floor(N/256) 과 다르면 IllegalStateException 이어야 한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun rejectsWrongProbabilityCount() {
        val engine = TenVad { _, _ -> FloatArray(1) } // 항상 1개만 돌려주는 잘못된 결과
        assertThrows(IllegalStateException::class.java) { engine.detect(FloatArray(16000), VadPresets.byId("TEN_DEFAULT")) } // 개수 불일치 → 예외
    }

    /**
     * 목적: 다른 계열 프리셋 거부 검증
     * 기능: WebRTC 프리셋을 넣으면 IllegalArgumentException(다른 VAD 로 대체하지 않음).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun rejectsOtherFamilyPreset() {
        val engine = TenVad { _, _ -> FloatArray(0) } // 호출되면 안 되는 결과
        assertThrows(IllegalArgumentException::class.java) { engine.detect(FloatArray(256), VadPresets.byId("WEBRTC_LIB_DEFAULT")) } // 계열 불일치
    }
}
