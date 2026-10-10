// 파일명: ui/mic/MicViewModelTest.kt
// 목적: 마이크 화면 ViewModel(MicViewModel)이 녹음 중 ViewModel 정리(예: 시스템 뒤로 가기로 화면이 스택에서 빠짐)에도 녹음을 저장하는지,
//       최대 길이에서 자동으로 멈추고 저장·안내하는지 검증
// 기능: 끝없이 블록을 주는 가짜 소스로 녹음을 시작한 뒤 (1) ViewModelStore.clear() 로 ViewModel 을 정리하면 onCleared → 정지 → NonCancellable 저장으로
//       input_sound/kr/mic/<시각>.wav 가 생기고 소스가 해제되는지, (2) maxSec=1 이면 1초(16000 샘플)에서 자동 정지·저장되고 안내 문구가 나오는지 확인한다.
//       녹음 루프는 실제 IO 스레드(Dispatchers.IO)에서 돌고, viewModelScope 의 Main 은 즉시 실행 테스트 디스패처로 바꾼다.
// change(add)-hyungchul-2026-10-11-04:39
package com.cowork.stt_ondevice.ui.mic // 마이크 화면 테스트 패키지

import androidx.lifecycle.ViewModel // 뷰모델 기본 클래스
import androidx.lifecycle.ViewModelProvider // 뷰모델 생성기
import androidx.lifecycle.ViewModelStore // 뷰모델 저장소(정리 흉내)
import com.cowork.stt_ondevice.core.audio.PcmSource // 소스 추상화
import com.cowork.stt_ondevice.core.audio.WavReader // WAV 읽기
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.ExperimentalCoroutinesApi // 테스트 API 표시
import kotlinx.coroutines.delay // 대기
import kotlinx.coroutines.runBlocking // 블로킹 실행
import kotlinx.coroutines.test.UnconfinedTestDispatcher // 즉시 실행 테스트 디스패처
import kotlinx.coroutines.test.resetMain // Main 복구
import kotlinx.coroutines.test.setMain // Main 교체
import kotlinx.coroutines.withTimeout // 제한 시간
import org.junit.After // 각 테스트 후
import org.junit.Assert.assertEquals // 값 일치
import org.junit.Assert.assertTrue // 참
import org.junit.Before // 각 테스트 전
import org.junit.Rule // 규칙
import org.junit.Test // 테스트
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일
import java.time.LocalDateTime // 시각

// change(add)-hyungchul-2026-10-11-04:39
/**
 * 목적: 끝없이 블록을 주는 가짜 마이크
 * 기능: read 마다 160 샘플(값 100)을 주고 1ms 쉰다. 읽기 횟수와 해제 여부를 기록한다.
 */
private class FakeMic : PcmSource {
    override val sampleRate = 16000 // 16 kHz
    override val channels = 1 // mono
    @Volatile var reads = 0 // 읽기 횟수
    @Volatile var released = false // 해제 여부

    /** 목적: 블록 읽기 / 입력: buf / 리턴: 160 */
    override fun read(buf: ShortArray): Int {
        reads++ // 기록
        Thread.sleep(1) // 실제 마이크처럼 조금 기다림
        buf.fill(100, 0, 160) // 값 채우기
        return 160 // 샘플 수
    }

    /** 목적: 해제 / 리턴: 없음 */
    override fun release() { released = true } // 기록
}

// change(add)-hyungchul-2026-10-11-04:39
/**
 * 목적: MicViewModel 회귀 방지
 * 기능: 파일 머리말의 항목을 확인한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MicViewModelTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더
    private val now = LocalDateTime.of(2026, 10, 11, 4, 40, 0) // 고정 시각(파일 이름)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher()) // viewModelScope 용 Main 교체

    @After
    fun tearDown() = Dispatchers.resetMain() // Main 복구

    /**
     * 목적: 저장소에 ViewModel 만들기
     * 기능: ViewModelProvider(store, 팩토리)로 만들어 store.clear() 로 실제 정리 경로(viewModelScope 취소 + onCleared)를 탈 수 있게 한다.
     * 입력: store(뷰모델 저장소), src(가짜 소스), maxSec(최대 길이)
     * 출력: 없음
     * 리턴: MicViewModel
     */
    private fun vmIn(store: ViewModelStore, src: FakeMic, maxSec: Int = MicViewModel.MAX_SEC): MicViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // MicViewModel 만 만든다
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MicViewModel(File(tmp.root, "input_sound"), { src to null }, { now }, Dispatchers.IO, maxSec) as T // 주입
        }
        return ViewModelProvider(store, factory)[MicViewModel::class.java] // 생성·등록
    }

    /**
     * 목적: 녹음 중 ViewModel 정리 → 저장
     * 기능: 녹음을 시작해 몇 블록 읽은 뒤 store.clear()(뒤로 가기로 화면이 빠질 때와 같은 정리)를 하면
     *       input_sound/kr/mic/20261011_044000.wav 가 생기고(16 kHz mono, 샘플 1개 이상) 소스가 해제된다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun savesWhenClearedWhileRecording() = runBlocking {
        val store = ViewModelStore() // 저장소
        val src = FakeMic() // 가짜 마이크
        val vm = vmIn(store, src) // ViewModel
        vm.start() // 녹음 시작
        assertTrue(vm.state.value.recording) // 녹음 중
        withTimeout(5_000) { while (src.reads < 10) delay(5) } // 몇 블록 읽을 때까지
        store.clear() // ViewModel 정리(viewModelScope 취소 + onCleared)
        val wav = File(tmp.root, "input_sound/kr/mic/20261011_044000.wav") // 기대 경로
        withTimeout(5_000) { while (!wav.isFile) delay(10) } // 저장 대기
        val data = WavReader.read(wav) // 읽기
        assertEquals(16000, data.sampleRate) // 16 kHz
        assertEquals(1, data.channels) // mono
        assertTrue(data.pcm.size >= 160 * 10) // 정리 전까지 읽은 만큼 이상 저장
        assertTrue(src.released) // 소스 해제
    }

    /**
     * 목적: 최대 길이 자동 정지
     * 기능: maxSec=1 이면 정지를 누르지 않아도 16000 샘플에서 멈추고 저장되며, 안내 문구에 "최대 녹음 길이" 가 들어간다.
     * 입력: 없음 / 출력: 실패 시 AssertionError / 리턴: 없음
     */
    @Test
    fun autoStopsAtMaxDuration() = runBlocking {
        val src = FakeMic() // 가짜 마이크
        val vm = vmIn(ViewModelStore(), src, maxSec = 1) // 1초 제한
        vm.start() // 녹음 시작
        withTimeout(10_000) { while (vm.state.value.savedPath == null) delay(10) } // 자동 정지·저장 대기
        val data = WavReader.read(File(vm.state.value.savedPath!!)) // 저장 파일
        assertEquals(16000, data.pcm.size) // 정확히 1초
        assertTrue(vm.state.value.message.toString(), vm.state.value.message!!.contains("최대 녹음 길이")) // 안내
        assertTrue(src.released) // 소스 해제
    }
}
