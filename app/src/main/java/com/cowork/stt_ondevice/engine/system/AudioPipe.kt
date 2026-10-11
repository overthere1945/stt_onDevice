// 파일명: AudioPipe.kt
// 목적: 시스템 엔진 백엔드(ML Kit·플랫폼)가 함께 쓰는 오디오 공급 파이프 (2차 작업 6)
// 기능: ParcelFileDescriptor.createPipe() 로 읽기 쪽(엔진에 넘김)·쓰기 쪽(공급기가 씀)을 만들고,
//       쓰기 쪽을 RecognitionSession.audio 로 쓸 OutputStream 으로 감싼다. 닫기는 스레드 안전·반복 안전·즉시 반환이며,
//       쓰기 쪽 닫기는 다른 스레드에서 write 에 막힌 공급기를 IOException 으로 풀어 준다(ParcelFileDescriptor.close 가 막힌 스레드에 신호를 보냄).
//       읽기 쪽까지 닫으면 상대(엔진)가 더 읽지 않을 때도 쓰기가 EPIPE 로 끝난다(실측: 엔진이 먼저 끝나면 64 KB 에서 공급기가 막힘).
// change(add)-hyungchul-2026-10-11-19:18
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import android.os.ParcelFileDescriptor // 파이프 끝(파일 디스크립터)
import java.io.FileOutputStream // 디스크립터 쓰기 스트림
import java.io.OutputStream // 출력 스트림
import java.util.concurrent.atomic.AtomicBoolean // 1회 닫기 플래그

// change(add)-hyungchul-2026-10-11-19:18
/**
 * 목적: 오디오 공급 파이프 1개
 * 기능: readSide 는 엔진(ML Kit AudioSource.fromPfd / 플랫폼 EXTRA_AUDIO_SOURCE)에 넘기고, output 에는 공급기가 PCM 을 쓴다.
 *       closeWrite 는 쓰기 쪽만(EOF = 공급 끝 신호), closeAll 은 양쪽을 닫는다(세션 종료·중단).
 * 입력: read(읽기 쪽), write(쓰기 쪽)
 */
internal class AudioPipe private constructor(
    private val read: ParcelFileDescriptor, // 읽기 쪽
    private val write: ParcelFileDescriptor, // 쓰기 쪽
) {
    private val writeClosed = AtomicBoolean(false) // 쓰기 쪽 닫힘
    private val readClosed = AtomicBoolean(false) // 읽기 쪽 닫힘

    /** 목적: 엔진에 넘길 읽기 쪽 / 기능: 세션 시작 요청에 담는다(바인더 전달 시 엔진 프로세스에 복제됨) */
    val readSide: ParcelFileDescriptor get() = read // 읽기 쪽

    /** 목적: 공급기용 출력 스트림 / 기능: 쓰기 쪽에 PCM 을 쓴다. close() = closeWrite() */
    val output: OutputStream = WriteStream() // 공급 스트림

    /**
     * 목적: 쓰기 쪽 닫기
     * 기능: 처음 한 번만 쓰기 쪽 디스크립터를 닫는다(EOF 신호, 막힌 write 해제). 실패는 무시한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun closeWrite() {
        if (writeClosed.compareAndSet(false, true)) runCatching { write.close() } // 1회만 닫기
    }

    /**
     * 목적: 양쪽 닫기
     * 기능: 쓰기 쪽과 읽기 쪽을 모두 닫는다(각각 1회, 실패 무시). 세션이 어떤 이유로든 끝나면 부른다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun closeAll() {
        closeWrite() // 쓰기 쪽
        if (readClosed.compareAndSet(false, true)) runCatching { read.close() } // 읽기 쪽(1회)
    }

    /**
     * 목적: 쓰기 쪽 스트림
     * 기능: 쓰기 쪽 디스크립터에 바로 쓴다(버퍼 없음). close 는 closeWrite 로 위임해 반복·다른 스레드 호출에 안전하다.
     */
    private inner class WriteStream : OutputStream() {
        private val out = FileOutputStream(write.fileDescriptor) // 디스크립터 쓰기(소유하지 않음 — 닫기는 write.close)

        /**
         * 목적: 1 바이트 쓰기
         * 기능: 디스크립터에 쓴다. 닫힌 뒤면 IOException.
         * 입력: b(바이트 값)
         * 출력: 닫힘·EPIPE → IOException
         * 리턴: 없음
         */
        override fun write(b: Int) = out.write(b) // 쓰기

        /**
         * 목적: 블록 쓰기
         * 기능: 디스크립터에 len 바이트를 쓴다(파이프가 차면 막힘 — closeWrite 가 풀어 줌).
         * 입력: b(버퍼), off(시작), len(길이)
         * 출력: 닫힘·EPIPE → IOException
         * 리턴: 없음
         */
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len) // 쓰기

        /**
         * 목적: 닫기
         * 기능: closeWrite 로 위임한다(반복 안전).
         * 입력: 없음
         * 출력: 없음
         * 리턴: 없음
         */
        override fun close() = closeWrite() // 쓰기 쪽 닫기
    }

    companion object {
        /**
         * 목적: 새 파이프 만들기
         * 기능: ParcelFileDescriptor.createPipe() 로 [읽기, 쓰기] 쌍을 만든다.
         * 입력: 없음
         * 출력: 생성 실패 시 IOException
         * 리턴: AudioPipe
         */
        fun create(): AudioPipe {
            val p = ParcelFileDescriptor.createPipe() // [0]=읽기, [1]=쓰기
            return AudioPipe(p[0], p[1]) // 묶음
        }
    }
}
