// 파일명: core/audio/MicRecorderTest.kt
// 목적: 마이크 녹음기(MicRecorder)의 녹음 루프·WAV 저장·저장 경로 규칙을 검증
// 기능: 가짜 PCM 소스(PcmSource)로 (1) stop 신호까지 읽은 블록 길이의 합이 결과 길이와 같고 소스가 해제되는지(recordsUntilStop),
//       (2) 16 kHz mono 소스는 그대로, 48 kHz stereo 소스는 16 kHz mono 로 변환해 WAV(44바이트 헤더, PCM16)로 저장하고 실제 형식 정보 파일을 남기는지(writesWavAt16kMono),
//       (3) 저장 경로가 input_sound/<lang>/mic/<yyyyMMdd_HHmmss>.wav 이고 같은 이름이 있으면 _1 을 붙이는지(micFilePath) 확인한다.
// change(add)-hyungchul-2026-10-11-03:59
package com.cowork.stt_ondevice.core.audio // 오디오 테스트 패키지

import kotlinx.coroutines.flow.MutableStateFlow // 정지 신호
import kotlinx.coroutines.runBlocking // 블로킹 실행
import kotlinx.coroutines.withTimeout // 제한 시간
import org.junit.Assert.assertArrayEquals // 배열 일치
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertFalse // 거짓
import org.junit.Assert.assertTrue // 참
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.time.LocalDateTime // 시각 값

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 테스트용 가짜 PCM 소스
 * 기능: 미리 정한 블록을 차례로 돌려주고, 마지막 블록을 준 뒤 stop 신호를 켠다(녹음 중 사용자가 "정지"를 누른 것 흉내). 해제 여부를 기록한다.
 * 입력: sampleRate(Hz), channels(채널 수), blocks(돌려줄 블록 목록), stop(마지막 블록 뒤 켤 신호)
 */
private class FakePcmSource(
    override val sampleRate: Int, // 샘플레이트
    override val channels: Int, // 채널 수
    private val blocks: List<ShortArray>, // 블록 목록
    private val stop: MutableStateFlow<Boolean>, // 정지 신호
) : PcmSource {
    var reads = 0 // read 호출 수
    var released = false // 해제 여부

    /** 목적: 블록 1개 읽기 / 기능: 다음 블록을 buf 앞에 복사하고, 마지막 블록이면 stop 을 켠다. 블록이 없으면 0 / 입력: buf / 리턴: 읽은 샘플 수 */
    override fun read(buf: ShortArray): Int {
        if (reads >= blocks.size) return 0 // 더 줄 블록 없음
        val b = blocks[reads++] // 다음 블록
        b.copyInto(buf) // 버퍼 앞에 복사
        if (reads == blocks.size) stop.value = true // 마지막 블록 뒤 정지
        return b.size // 샘플 수
    }

    /** 목적: 해제 / 기능: 해제 여부 기록 / 입력: 없음 / 리턴: 없음 */
    override fun release() {
        released = true // 기록
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: MicRecorder 회귀 방지
 * 기능: 파일 머리말의 항목을 확인한다.
 */
class MicRecorderTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더

    /** 목적: 증가하는 값의 블록 만들기 / 입력: start(첫 값), n(길이) / 리턴: ShortArray */
    private fun block(start: Int, n: Int) = ShortArray(n) { (start + it).toShort() } // start, start+1, …

    /**
     * 목적: stop 신호까지 녹음
     * 기능: 3 블록(100·200·50 샘플)을 준 뒤 stop 이 켜지면 결과 길이 = 350 이고 값이 블록을 이어 붙인 것과 같으며, 블록마다 진행 콜백이 오고 소스가 해제된다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun recordsUntilStop() = runBlocking {
        val stop = MutableStateFlow(false) // 정지 신호
        val blocks = listOf(block(0, 100), block(1000, 200), block(5000, 50)) // 3 블록
        val src = FakePcmSource(16000, 1, blocks, stop) // 가짜 소스
        val rec = MicRecorder(src) // 녹음기
        val progress = mutableListOf<Long>() // 콜백으로 받은 누적 프레임 수
        val pcm = withTimeout(5_000) { rec.record(stop) { _, frames -> progress += frames } } // 녹음(정지까지)
        assertEquals(350, pcm.size) // 길이 합
        assertArrayEquals(blocks.reduce { a, b -> a + b }, pcm) // 값 이어 붙이기
        assertEquals(listOf(100L, 300L, 350L), progress) // 블록마다 누적 프레임
        assertEquals(3, src.reads) // stop 뒤에는 더 읽지 않음
        assertTrue(src.released) // 소스 해제
        assertEquals(16000, rec.actualSampleRate) // 실제 샘플레이트
        assertEquals(1, rec.actualChannels) // 실제 채널 수
    }

    /**
     * 목적: 16 kHz mono WAV 로 저장
     * 기능: (1) 16 kHz mono 소스: 변환 없이 같은 샘플로 저장(converted=false), 헤더 16000 Hz·1 ch·16 bit, 형식 정보 파일에 actual_sample_rate=16000.
     *       (2) 48 kHz stereo 소스 4800 프레임: 16 kHz mono 1600 샘플로 변환 저장(converted=true), 형식 정보 파일에 원래 값 48000·2 기록.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun writesWavAt16kMono() = runBlocking {
        val stop = MutableStateFlow(false) // 정지 신호
        val mono = block(-500, 1600) // 0.1초 분량
        val rec = MicRecorder(FakePcmSource(16000, 1, listOf(mono), stop)) // 16k mono 녹음기
        val pcm = withTimeout(5_000) { rec.record(stop) } // 녹음
        val out = File(tmp.root, "input_sound/kr/mic/20261011_120000.wav") // 저장 경로
        val info = rec.save(pcm, out) // 저장
        val wav = WavReader.read(out) // 다시 읽기
        assertEquals(16000, wav.sampleRate) // 16 kHz
        assertEquals(1, wav.channels) // mono
        assertEquals(16, wav.bitsPerSample) // PCM16
        assertArrayEquals(mono, wav.pcm) // 샘플 그대로
        assertFalse(info.converted) // 변환 없음
        assertEquals(0.1, info.durationSec, 1e-9) // 길이(초)
        assertFalse(File(out.path + ".part").exists()) // 임시 파일 남지 않음
        val infoText = info.infoFile.readText() // 형식 정보 파일
        assertTrue(infoText, infoText.contains("actual_sample_rate=16000") && infoText.contains("actual_channels=1") && infoText.contains("converted=false")) // 실제 값 기록

        val stop2 = MutableStateFlow(false) // 두 번째 정지 신호
        val stereo = ShortArray(4800 * 2) { if (it % 2 == 0) 1000 else 3000 } // 48k stereo 0.1초(L=1000, R=3000)
        val rec2 = MicRecorder(FakePcmSource(48000, 2, listOf(stereo), stop2)) // 48k stereo 녹음기
        val pcm2 = withTimeout(5_000) { rec2.record(stop2) } // 녹음
        val out2 = File(tmp.root, "input_sound/en/mic/20261011_120001.wav") // 저장 경로
        val info2 = rec2.save(pcm2, out2) // 저장(변환)
        val wav2 = WavReader.read(out2) // 다시 읽기
        assertEquals(16000, wav2.sampleRate) // 16 kHz 로 변환
        assertEquals(1, wav2.channels) // mono 로 다운믹스
        assertEquals(1600, wav2.pcm.size) // 4800 프레임 → 1600 샘플
        assertTrue(wav2.pcm.all { it.toInt() == 2000 }) // 채널 평균(1000+3000)/2
        assertTrue(info2.converted) // 변환함
        assertEquals(48000, info2.sourceSampleRate) // 원래 샘플레이트
        assertEquals(2, info2.sourceChannels) // 원래 채널 수
        val infoText2 = info2.infoFile.readText() // 형식 정보 파일
        assertTrue(infoText2, infoText2.contains("actual_sample_rate=48000") && infoText2.contains("actual_channels=2") && infoText2.contains("converted=true")) // 원래 값 기록
    }

    /**
     * 목적: 저장 경로 규칙
     * 기능: micFile(input_sound, "kr", 2026-10-11 12:34:56) = input_sound/kr/mic/20261011_123456.wav, 같은 파일이 있으면 20261011_123456_1.wav.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun micFilePath() {
        val root = File(tmp.root, "input_sound") // 입력 루트
        val now = LocalDateTime.of(2026, 10, 11, 12, 34, 56) // 고정 시각
        val f = MicRecorder.micFile(root, "kr", now) // 경로
        assertEquals(File(root, "kr/mic/20261011_123456.wav"), f) // 규칙
        f.parentFile!!.mkdirs(); f.writeBytes(ByteArray(44)) // 같은 이름 파일 만들기
        assertEquals(File(root, "kr/mic/20261011_123456_1.wav"), MicRecorder.micFile(root, "kr", now)) // 접미 붙임
    }
}
