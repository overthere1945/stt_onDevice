// 파일명: VoskDecoderTest.kt
// 목적: VoskDecoder.decode 의 블록 입력·끝점 결과 누적·공백 결합 규칙을 가짜 인식기로 고정(네이티브 없이 JVM)
// 기능: accept 의 true/false 시퀀스별로 result 수집 여부, 빈 결과 건너뜀, finalResult 결합, 블록 분할(4000 + 나머지), 중단 훅 전파를 확인한다.
// change(add)-hyungchul-2026-10-10-21:41
package com.cowork.stt_ondevice.engine.vosk // Vosk 엔진 테스트 패키지

import com.cowork.stt_ondevice.engine.EngineException // 중단 예외 시험용
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-21:41
/**
 * 목적: 끝점 결과 누적 로직 회귀 방지(기기 smoke 는 이 퇴행을 못 잡음)
 * 기능: 위 파일 머리 설명 참조.
 */
class VoskDecoderTest {

    /**
     * 목적: 시험용 가짜 인식기
     * 기능: accept 호출마다 acceptSeq 의 다음 값을 돌려주고, 받은 블록 크기를 기록한다. result 는 resultSeq 를 차례로, finalResult 는 final 을 돌려준다.
     * 입력: acceptSeq(accept 반환 시퀀스), resultSeq(result 반환 시퀀스), final(finalResult 반환값)
     * 출력: 해당 없음
     * 리턴: 해당 없음
     */
    private class Fake(val acceptSeq: List<Boolean>, val resultSeq: List<String>, val final: String) : VoskStream {
        val sizes = ArrayList<Int>() // accept 로 받은 블록 크기들
        private var ri = 0 // 다음 result 위치
        override fun accept(block: ShortArray, n: Int): Boolean { sizes.add(n); return acceptSeq[sizes.size - 1] } // 크기 기록 후 시퀀스 값
        override fun result(): String = resultSeq[ri++] // 확정 결과 차례로
        override fun finalResult(): String = final // 마무리 결과
    }

    private fun json(t: String) = """{"text" : "$t"}""" // Vosk 서식 JSON 만들기

    /**
     * 목적: 끝점 결과 누적 검증
     * 기능: 블록 3개 중 1·3번째가 true → 두 확정 발화와 마무리 발화가 공백 1칸으로 이어진다(앞 발화가 버려지지 않음).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun accumulatesEndpointResultsBeforeFinal() {
        val f = Fake(listOf(true, false, true), listOf(json("안녕 하세요"), json("두번째")), json("마지막"))
        val out = VoskDecoder.decode(ShortArray(3000), f, 1000) // 1000 샘플 블록 3개
        assertEquals("안녕 하세요 두번째 마지막", out) // 순서대로 결합
        assertEquals(listOf(1000, 1000, 1000), f.sizes) // 블록 분할
    }

    /**
     * 목적: false 만 나오는 경우 검증
     * 기능: 끝점이 한 번도 없으면 result 는 부르지 않고(부르면 IndexOutOfBounds) finalResult 만 반환한다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun noEndpointUsesOnlyFinalResult() {
        val f = Fake(listOf(false, false), emptyList(), json("only final"))
        assertEquals("only final", VoskDecoder.decode(ShortArray(2000), f, 1000)) // final 만
    }

    /**
     * 목적: 빈 결과 처리 검증
     * 기능: 확정 결과·마무리 결과가 빈 text 이면 건너뛰어 앞뒤·가운데 공백이 생기지 않는다. 전부 비면 "".
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun emptyResultsAreSkipped() {
        val f1 = Fake(listOf(true, true), listOf(json(""), json("가운데")), json(""))
        assertEquals("가운데", VoskDecoder.decode(ShortArray(2000), f1, 1000)) // 빈 것 건너뜀
        val f2 = Fake(listOf(true), listOf("{}"), json(""))
        assertEquals("", VoskDecoder.decode(ShortArray(500), f2, 1000)) // 전부 비면 빈 문자열
    }

    /**
     * 목적: 공백 처리 검증
     * 기능: 각 발화 text 의 앞뒤 공백은 VoskResult 가 정리하고 결합 공백은 정확히 1칸이다(발화 안의 공백은 그대로).
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun spacesAreTrimmedAndJoinedWithSingleSpace() {
        val f = Fake(listOf(true), listOf(json("  a b  ")), json("  c "))
        assertEquals("a b c", VoskDecoder.decode(ShortArray(1000), f, 1000)) // 정리 + 1칸 결합
    }

    /**
     * 목적: 블록 분할 검증
     * 기능: 샘플 수가 블록 크기의 배수가 아니면 마지막 블록만 짧게(나머지 크기) 들어가고, 입력이 비면 accept 없이 finalResult 만 부른다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun splitsIntoBlocksWithShortTail() {
        val f = Fake(listOf(false, false, false), emptyList(), json("x"))
        VoskDecoder.decode(ShortArray(4000 * 2 + 123), f, 4000) // 4000, 4000, 123
        assertEquals(listOf(4000, 4000, 123), f.sizes) // 마지막은 짧음
        val empty = Fake(emptyList(), emptyList(), json("y"))
        assertEquals("y", VoskDecoder.decode(ShortArray(0), empty, 4000)) // 빈 입력
        assertEquals(emptyList<Int>(), empty.sizes) // accept 안 부름
    }

    /**
     * 목적: 중단 훅 전파 검증
     * 기능: beforeStep 이 두 번째 호출에서 EngineException 을 던지면 그대로 전파되고 이후 블록은 처리되지 않는다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun beforeStepAbortPropagates() {
        val f = Fake(listOf(false, false, false), emptyList(), json("x"))
        var calls = 0 // beforeStep 호출 수
        val e = assertThrows(EngineException::class.java) {
            VoskDecoder.decode(ShortArray(3000), f, 1000) { if (++calls == 2) throw EngineException("TRANSCRIBE", "ABORTED", "중단") } // 2번째 호출에서 중단
        }
        assertEquals("ABORTED", e.code) // 중단 코드
        assertEquals(1, f.sizes.size) // 첫 블록만 처리됨
    }
}
