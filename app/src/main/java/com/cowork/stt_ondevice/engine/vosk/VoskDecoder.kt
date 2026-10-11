// 파일명: VoskDecoder.kt
// 목적: PCM16 을 블록 단위로 Vosk 인식기에 넣고 발화별 결과를 모아 하나의 텍스트로 만드는 순수 로직(네이티브와 분리해 JVM 에서 검증)
// 기능: VoskStream(인식기 최소 인터페이스 — 실제로는 org.vosk.Recognizer 를 감싼 어댑터, 시험에서는 가짜),
//       VoskDecoder.decode — 블록마다 accept 를 호출하고, accept 가 true(발화 끝점)이면 result 의 text 를 모으며(빈 문자열은 건너뜀),
//       마지막에 finalResult 의 text 를 모아 공백 1칸으로 잇는다. 끝점마다 모으지 않으면 앞 발화가 버려진다.
// change(add)-hyungchul-2026-10-10-21:41
package com.cowork.stt_ondevice.engine.vosk // Vosk 엔진 패키지

// change(add)-hyungchul-2026-10-10-21:41
/**
 * 목적: Vosk 인식기 최소 인터페이스
 * 기능: acceptWaveForm / getResult / getFinalResult 세 동작만 노출한다(VoskEngine 이 Recognizer 를 감싸 구현, 시험은 가짜 구현).
 */
internal interface VoskStream {
    /** PCM16 블록(block 의 앞 n 샘플)을 넣고, 발화 끝점이 확정되었으면 true. */
    fun accept(block: ShortArray, n: Int): Boolean

    /** 직전에 확정된 발화의 결과 JSON. */
    fun result(): String

    /** 남은 발화를 마무리한 결과 JSON. */
    fun finalResult(): String
}

// change(add)-hyungchul-2026-10-10-21:41
/**
 * 목적: 블록 입력과 발화별 결과 누적
 * 기능: 위 파일 머리 설명 참조. 상태가 없는 순수 함수.
 */
internal object VoskDecoder {

    /**
     * 목적: PCM16 전체를 인식해 텍스트로 합치기
     * 기능: pcm 을 blockSamples 개씩(마지막은 짧을 수 있음) stream.accept 로 넣고, true 이면 result 의 text 를 모은다.
     *       각 블록을 넣기 전과 마무리 전에 beforeStep 을 부른다(중단 확인용, 예외를 던지면 그대로 전파).
     *       마지막에 finalResult 의 text 를 모아, 비어 있지 않은 것만 공백 1칸으로 잇는다.
     * 입력: pcm(PCM16 샘플), stream(인식기), blockSamples(블록 크기, 1 이상), beforeStep(단계 전 확인, 기본 없음)
     * 출력: 결과 JSON 이 깨졌으면 SerializationException, beforeStep 이 던진 예외
     * 리턴: 인식 텍스트(발화 사이 공백 1칸, 아무것도 없으면 "")
     */
    fun decode(pcm: ShortArray, stream: VoskStream, blockSamples: Int, beforeStep: () -> Unit = {}): String {
        require(blockSamples >= 1) { "blockSamples 는 1 이상이어야 함: $blockSamples" } // 인자 검사
        val parts = ArrayList<String>() // 발화별 text 모음
        val block = ShortArray(blockSamples) // 입력 블록 버퍼(재사용)
        var off = 0 // 다음 블록 시작 위치
        while (off < pcm.size) { // 블록마다
            beforeStep() // 블록 전 확인
            val n = minOf(blockSamples, pcm.size - off) // 이번 블록 샘플 수
            System.arraycopy(pcm, off, block, 0, n) // 블록 버퍼에 복사
            if (stream.accept(block, n)) { // 발화 끝점 → 확정 결과
                VoskResult.text(stream.result()).let { if (it.isNotEmpty()) parts.add(it) } // 비어 있지 않은 text 만 모으기
            }
            off += n // 다음 블록
        }
        beforeStep() // 마무리 전 확인
        VoskResult.text(stream.finalResult()).let { if (it.isNotEmpty()) parts.add(it) } // 남은 발화 flush
        return parts.joinToString(" ") // 발화 사이 공백 1칸
    }
}
