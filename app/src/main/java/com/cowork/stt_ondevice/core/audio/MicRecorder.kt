// 파일명: core/audio/MicRecorder.kt
// 목적: 마이크 녹음(Phase 1: 녹음 후 전사) — PCM 소스에서 정지 신호까지 읽어 16 kHz mono PCM16 WAV 로 저장
// 기능: - PcmSource: 녹음 소스 추상화(실제 샘플레이트·채널 수·블록 읽기·해제). JVM 테스트는 가짜 소스를 쓴다.
//       - AudioRecordSource: AudioRecord(VOICE_RECOGNITION) 16 kHz mono PCM16 으로 연다. 기기가 16 kHz 를 거부하면 기기 기본 샘플레이트로 열고
//         (조용히 넘어가지 않고 실제 값을 기록·화면에 표시), 저장할 때 PcmConverter 로 16 kHz mono 로 바꾼다. 실제 값은 AudioRecord 가 보고한 값.
//       - MicRecorder.record: 정지 신호(StateFlow<Boolean>)가 켜질 때까지 블록을 읽어 이어 붙이고, 블록마다 RMS 레벨·누적 프레임 수를 알린다. 끝나면 소스를 해제한다.
//         change(add)-hyungchul-2026-10-11-04:39 최대 프레임 수(maxFrames)에 닿으면 스스로 멈추고(stoppedByLimit), 버퍼를 늘리다 메모리가 부족하면(OutOfMemoryError)
//         그때까지 녹음한 것을 돌려주고 멈춘다(stoppedByMemory) — 녹음 손실·크래시 대신 부분 저장.
//       - MicRecorder.save: 16 kHz mono 로 맞춘 PCM 을 <이름>.wav.part 에 쓰고 이름을 바꿔(스캐너가 반쯤 쓴 파일을 보지 않게) 저장하고,
//         실제 녹음 형식(샘플레이트·채널·인코딩·변환 여부·길이)을 같은 폴더의 <stem>.info.txt 에 기록한다(spec §6 "실제 format/rate 기록").
//       - MicRecorder.micFile: 저장 경로 input_sound/<lang>/mic/<yyyyMMdd_HHmmss>.wav (같은 이름이 있으면 _1, _2 …).
// change(add)-hyungchul-2026-10-11-03:59
package com.cowork.stt_ondevice.core.audio // 오디오 입출력 패키지

import android.annotation.SuppressLint // 권한 검사 경고 억제(호출 전에 화면이 권한을 확인)
import android.media.AudioFormat // 오디오 형식 상수
import android.media.AudioRecord // 마이크 녹음 API
import android.media.MediaRecorder // 오디오 소스 상수
import kotlinx.coroutines.CoroutineDispatcher // 디스패처 타입
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.ensureActive // 취소 확인
import kotlinx.coroutines.flow.StateFlow // 정지 신호
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.io.IOException // 입출력 오류
import java.time.LocalDateTime // 시각
import java.time.format.DateTimeFormatter // 시각 서식
import kotlin.math.sqrt // 제곱근

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 녹음 PCM 소스 추상화
 * 기능: 실제 샘플레이트·채널 수를 알려 주고, 인터리브 PCM16 블록을 읽고, 자원을 해제한다.
 * 입력: 없음(구현체가 결정)
 */
interface PcmSource {
    /** 실제 샘플레이트(Hz) — 소스가 보고한 값. */
    val sampleRate: Int

    /** 실제 채널 수(1=mono). */
    val channels: Int

    /**
     * 목적: PCM 블록 읽기
     * 기능: buf 앞부분에 인터리브 PCM16 샘플을 채운다(블로킹일 수 있음).
     * 입력: buf(채울 버퍼)
     * 출력: 없음
     * 리턴: 읽은 샘플 수(0 이상), 음수면 소스 오류 코드
     */
    fun read(buf: ShortArray): Int

    /**
     * 목적: 자원 해제
     * 기능: 녹음을 멈추고 자원을 돌려준다. 여러 번 불러도 된다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    fun release()
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 저장 결과 정보
 * 기능: 저장한 WAV·형식 정보 파일과 실제 녹음 형식, 변환 여부, 저장된 16 kHz 샘플 수를 담는다.
 * 입력: file(저장한 wav), infoFile(형식 정보 파일), sourceSampleRate(녹음 실제 Hz), sourceChannels(녹음 실제 채널), converted(16k mono 로 변환했는지), samples(저장된 16 kHz 샘플 수)
 */
data class MicSaveInfo(
    val file: File, // 저장한 wav
    val infoFile: File, // 형식 정보 파일
    val sourceSampleRate: Int, // 녹음 실제 샘플레이트
    val sourceChannels: Int, // 녹음 실제 채널 수
    val converted: Boolean, // 변환 여부
    val samples: Int, // 저장된 샘플 수(16 kHz mono)
) {
    /** 저장된 길이(초). */
    val durationSec: Double get() = samples.toDouble() / PcmConverter.TARGET_RATE // 샘플 수 ÷ 16000
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 마이크 녹음기(1회용)
 * 기능: 파일 머리말 참조. record 는 한 번만 부른다(끝나면 소스가 해제됨).
 * 입력: source(PCM 소스), io(읽기 루프를 돌릴 디스패처 — 블로킹 read 이므로 기본 IO)
 */
class MicRecorder(private val source: PcmSource, private val io: CoroutineDispatcher = Dispatchers.IO) {

    /** 실제 샘플레이트(소스 보고 값). */
    val actualSampleRate: Int get() = source.sampleRate // 소스 값

    /** 실제 채널 수(소스 보고 값). */
    val actualChannels: Int get() = source.channels // 소스 값

    /** change(add)-hyungchul-2026-10-11-04:39 최대 길이(maxFrames)에 닿아 스스로 멈췄는지. */
    @Volatile var stoppedByLimit: Boolean = false
        private set

    /** change(add)-hyungchul-2026-10-11-04:39 버퍼를 늘릴 메모리가 없어 그때까지의 녹음으로 멈췄는지. */
    @Volatile var stoppedByMemory: Boolean = false
        private set

    /**
     * 목적: 정지 신호까지 녹음
     * 기능: io 에서 stop 이 켜지거나 코루틴이 취소될 때까지 블록을 읽어 이어 붙인다. 블록마다 onBlock(RMS 0..1, 누적 프레임 수)을 부른다.
     *       읽기 결과가 음수면 한국어 IOException. 끝나면(정상·오류·취소) 소스를 해제한다.
     *       change(add)-hyungchul-2026-10-11-04:39 누적 프레임이 maxFrames 에 닿으면 멈추고 stoppedByLimit=true(넘친 블록 끝부분은 자름). 버퍼를 늘리다 OutOfMemoryError 가 나면
     *       그 블록은 버리고 지금까지의 녹음으로 멈추며 stoppedByMemory=true. 버퍼는 maxFrames 분량보다 크게 늘리지 않는다.
     * 입력: stop(정지 신호), maxFrames(change(add)-hyungchul-2026-10-11-04:39 최대 프레임 수, 기본 제한 없음), onBlock(블록 콜백 — io 스레드에서 불림)
     * 출력: 소스 오류 시 IOException, 취소 시 CancellationException
     * 리턴: 인터리브 PCM16 전체(소스의 실제 형식 그대로)
     */
    suspend fun record(stop: StateFlow<Boolean>, maxFrames: Long = Long.MAX_VALUE, onBlock: (rms: Float, frames: Long) -> Unit = { _, _ -> }): ShortArray = withContext(io) {
        val ch = source.channels.coerceAtLeast(1) // 채널 수(0 방지)
        val buf = ShortArray(maxOf(ch, source.sampleRate / 10 * ch)) // 약 0.1초 블록 버퍼
        val maxSamples = if (maxFrames >= Int.MAX_VALUE.toLong() / ch) Int.MAX_VALUE - 8 else (maxFrames * ch).toInt() // change(add)-hyungchul-2026-10-11-04:39 최대 샘플 수(배열 한계 안)
        var out = ShortArray(minOf(source.sampleRate * ch, maxSamples)) // 누적 버퍼(처음 1초 분량, 모자라면 두 배로 — 최대 샘플 수까지)
        var size = 0 // 누적 샘플 수
        try {
            while (!stop.value) { // 정지 신호 전까지
                ensureActive() // 취소 확인
                val n = source.read(buf) // 블록 읽기
                if (n < 0) throw IOException("마이크 읽기 오류(코드 $n)") // 소스 오류
                if (n == 0) continue // 읽은 것 없음(다시 시도)
                val take = minOf(n, maxSamples - size) // change(add)-hyungchul-2026-10-11-04:39 최대 길이를 넘는 부분은 자름
                if (size + take > out.size) { // 공간 늘리기
                    try {
                        out = out.copyOf(minOf(maxOf(out.size.toLong() * 2, (size + take).toLong()), maxSamples.toLong()).toInt()) // 두 배(최대 샘플 수까지)
                    } catch (e: OutOfMemoryError) { // change(add)-hyungchul-2026-10-11-04:39 메모리 부족 — 지금까지의 녹음을 지킴
                        stoppedByMemory = true // 표시
                        break // 멈춤(이 블록은 버림)
                    }
                }
                buf.copyInto(out, size, 0, take) // 이어 붙이기
                size += take // 누적
                onBlock(rms(buf, n), (size / ch).toLong()) // 레벨·진행 알림
                if (size >= maxSamples) { stoppedByLimit = true; break } // change(add)-hyungchul-2026-10-11-04:39 최대 길이 도달
            }
        } finally {
            source.release() // 소스 해제(정상·오류·취소 모두)
        }
        out.copyOf(size) // 실제 길이만
    }

    /**
     * 목적: 녹음 결과를 16 kHz mono PCM16 WAV 로 저장
     * 기능: 소스가 16 kHz mono 면 그대로, 아니면 PcmConverter.toMono16k(채널 평균·선형보간) → floatToPcm16 으로 바꾼다.
     *       <파일>.part 에 쓰고 이름을 바꾼 뒤(이미 있으면 덮어쓰지 않고 실패), 형식 정보 파일 <stem>.info.txt 를 쓴다.
     * 입력: pcm(record 결과), file(저장 경로 — micFile 로 만든 값)
     * 출력: 폴더 생성·쓰기·이름 바꾸기 실패 시 IOException(한국어)
     * 리턴: MicSaveInfo
     */
    fun save(pcm: ShortArray, file: File): MicSaveInfo {
        val rate = source.sampleRate // 실제 샘플레이트
        val ch = source.channels // 실제 채널 수
        val converted = rate != PcmConverter.TARGET_RATE || ch != 1 // 변환 필요 여부
        val mono16k = if (converted) PcmConverter.floatToPcm16(PcmConverter.toMono16k(WavData(rate, ch, 16, pcm)).samples) else pcm // 16 kHz mono
        val dir = file.parentFile ?: throw IOException("저장 폴더를 알 수 없습니다: ${file.path}") // 부모 폴더
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("저장 폴더를 만들 수 없습니다: ${dir.path}") // 폴더 생성
        if (file.exists()) throw IOException("같은 이름의 파일이 이미 있습니다: ${file.path}") // 덮어쓰기 금지
        val part = File(file.path + ".part") // 임시 파일(스캐너는 .wav 만 봄)
        try {
            WavWriter.write(part, mono16k, PcmConverter.TARGET_RATE) // 16 kHz mono WAV 쓰기
            if (!part.renameTo(file)) throw IOException("파일 이름을 바꾸지 못했습니다: ${part.path} → ${file.name}") // 완성본으로
        } finally {
            part.delete() // 남은 임시 파일 정리(성공 시에는 이미 없음)
        }
        val info = File(dir, file.nameWithoutExtension + ".info.txt") // 형식 정보 파일
        info.writeText( // 실제 녹음 형식 기록(key=value, UTF-8)
            listOf(
                "source=AudioRecord(VOICE_RECOGNITION)", // 녹음 소스
                "requested=16000Hz mono PCM16", // 요청한 형식
                "actual_sample_rate=$rate", // 실제 샘플레이트
                "actual_channels=$ch", // 실제 채널 수
                "actual_encoding=PCM_16BIT", // 실제 인코딩
                "converted=$converted", // 변환 여부
                "saved=16000Hz mono PCM16", // 저장 형식
                "duration_sec=${"%.3f".format(java.util.Locale.ROOT, mono16k.size.toDouble() / PcmConverter.TARGET_RATE)}", // 길이(초)
            ).joinToString("\n", postfix = "\n"), // 줄 단위
            Charsets.UTF_8, // 인코딩
        )
        return MicSaveInfo(file, info, rate, ch, converted, mono16k.size) // 결과
    }

    companion object {
        private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss") // 파일 이름 서식(runId 와 같은 형식)

        /**
         * 목적: 녹음 저장 경로 만들기
         * 기능: inputRoot/<lang>/mic/<yyyyMMdd_HHmmss>.wav. 이미 있으면 _1, _2 … 를 붙인다(폴더는 만들지 않음).
         * 입력: inputRoot(input_sound), lang(언어 폴더 kr/en), now(현재 시각)
         * 출력: 없음
         * 리턴: File
         */
        fun micFile(inputRoot: File, lang: String, now: LocalDateTime): File {
            val dir = File(File(inputRoot, lang), "mic") // input_sound/<lang>/mic
            val base = now.format(NAME_FORMAT) // 시각 이름
            var f = File(dir, "$base.wav") // 기본 이름
            var i = 1 // 접미 번호
            while (f.exists()) f = File(dir, "${base}_${i++}.wav") // 겹치면 접미
            return f // 경로
        }

        /**
         * 목적: 블록 RMS 레벨
         * 기능: 앞 n 샘플의 제곱 평균 제곱근을 32768 로 나눠 0..1 로 돌려준다(n<=0 이면 0).
         * 입력: buf(샘플), n(유효 샘플 수)
         * 출력: 없음
         * 리턴: 0..1 레벨
         */
        fun rms(buf: ShortArray, n: Int): Float {
            if (n <= 0) return 0f // 빈 블록
            var sum = 0.0 // 제곱 합
            for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v } // 누적
            return (sqrt(sum / n) / 32768.0).toFloat().coerceIn(0f, 1f) // 정규화
        }
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: AudioRecord 기반 실제 마이크 소스
 * 기능: open() 으로 만든다. 생성자는 이미 녹음을 시작한 AudioRecord 를 받는다. 실제 값은 AudioRecord.sampleRate/channelCount 그대로.
 *       fallbackNote 는 16 kHz 를 거부당해 기기 기본 샘플레이트로 연 경우의 한국어 설명(아니면 null).
 * 입력: record(시작된 AudioRecord), fallbackNote(대체 설명 또는 null)
 */
class AudioRecordSource private constructor(private val record: AudioRecord, val fallbackNote: String?) : PcmSource {

    override val sampleRate: Int = record.sampleRate // 실제 샘플레이트(AudioRecord 보고 값)
    override val channels: Int = record.channelCount // 실제 채널 수(AudioRecord 보고 값)
    private var released = false // 해제 여부

    /**
     * 목적: 블록 읽기
     * 기능: AudioRecord.read(short[]) 블로킹 읽기. 해제 뒤에는 0.
     * 입력: buf(채울 버퍼)
     * 출력: 없음
     * 리턴: 읽은 샘플 수 또는 음수 오류 코드
     */
    override fun read(buf: ShortArray): Int = if (released) 0 else record.read(buf, 0, buf.size) // 읽기

    /**
     * 목적: 녹음 정지·해제
     * 기능: 한 번만 stop → release 한다(stop 실패는 무시하고 release 는 반드시).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun release() {
        if (released) return // 이미 해제
        released = true // 표시
        try {
            record.stop() // 녹음 정지
        } catch (_: IllegalStateException) {
            // 녹음 중이 아니면 무시(아래 release 는 계속)
        } finally {
            record.release() // 자원 해제
        }
    }

    companion object {
        /**
         * 목적: 마이크 열기
         * 기능: VOICE_RECOGNITION 소스로 16 kHz mono PCM16 AudioRecord 를 만들고 녹음을 시작한다. 16 kHz 를 기기가 거부하면(getMinBufferSize 오류 또는 초기화 실패)
         *       샘플레이트를 지정하지 않고(기기 기본값) 다시 열고 fallbackNote 에 실제 값을 적는다. 그래도 실패하면 한국어 IllegalStateException.
         *       RECORD_AUDIO 권한은 호출 전에 화면이 확인한다(없으면 SecurityException 이 그대로 전달됨).
         * 입력: 없음
         * 출력: 권한 없음 SecurityException, 열기 실패 IllegalStateException
         * 리턴: AudioRecordSource
         */
        @SuppressLint("MissingPermission") // 권한은 MicScreen 이 먼저 확인·요청
        fun open(): AudioRecordSource {
            val target = PcmConverter.TARGET_RATE // 16 kHz
            val minBuf = AudioRecord.getMinBufferSize(target, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) // 16k 최소 버퍼
            if (minBuf > 0) { // 16k 를 지원한다고 보고
                val r = build(target, minBuf) // 16k 로 열기
                if (r != null) return start(r, null) // 성공
            }
            val r = build(null, 0) ?: throw IllegalStateException("마이크를 열 수 없습니다(AudioRecord 초기화 실패)") // 기기 기본 샘플레이트로 다시
            return start(r, "기기가 16 kHz 녹음을 지원하지 않아 ${r.sampleRate} Hz · ${r.channelCount}ch 로 녹음한 뒤 16 kHz mono 로 변환해 저장합니다") // 대체 사실 기록
        }

        /**
         * 목적: AudioRecord 만들기
         * 기능: rate 가 null 이면 샘플레이트를 지정하지 않는다(기기 기본). 버퍼는 최소 버퍼의 4배와 1초 분량 중 큰 값. 초기화에 실패하면 해제하고 null.
         * 입력: rate(Hz 또는 null), minBuf(최소 버퍼 바이트, 모르면 0)
         * 출력: 없음
         * 리턴: 초기화된 AudioRecord 또는 null
         */
        @SuppressLint("MissingPermission") // 권한은 MicScreen 이 먼저 확인·요청
        private fun build(rate: Int?, minBuf: Int): AudioRecord? {
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO) // PCM16 mono
            if (rate != null) fmt.setSampleRate(rate) // 샘플레이트 지정(없으면 기기 기본)
            val bytes = maxOf(minBuf * 4, (rate ?: 48_000) * 2) // 버퍼 크기(1초 분량 이상)
            val r = try {
                AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(fmt.build()).setBufferSizeInBytes(bytes).build() // 만들기
            } catch (e: UnsupportedOperationException) {
                return null // 형식 미지원
            } catch (e: IllegalArgumentException) {
                return null // 잘못된 인자(형식 거부)
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null } // 초기화 실패
            return r // 성공
        }

        /**
         * 목적: 녹음 시작
         * 기능: startRecording 후 녹음 상태가 아니면 해제하고 한국어 IllegalStateException(다른 앱이 마이크 사용 중 등).
         * 입력: r(AudioRecord), note(대체 설명)
         * 출력: 시작 실패 시 IllegalStateException
         * 리턴: AudioRecordSource
         */
        private fun start(r: AudioRecord, note: String?): AudioRecordSource {
            try {
                r.startRecording() // 녹음 시작
            } catch (e: IllegalStateException) {
                r.release() // 정리
                throw IllegalStateException("마이크 녹음을 시작할 수 없습니다: ${e.message}") // 사유
            }
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { // 시작 안 됨
                r.release() // 정리
                throw IllegalStateException("마이크 녹음을 시작할 수 없습니다(다른 앱이 마이크를 쓰는 중일 수 있습니다)") // 사유
            }
            return AudioRecordSource(r, note) // 소스
        }
    }
}
