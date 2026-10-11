// 파일명: ui/mic/MicViewModel.kt
// 목적: 마이크 화면의 상태(언어·녹음 중·경과 시간·레벨·저장 결과)와 녹음 시작/정지·저장을 담당하는 ViewModel
// 기능: start() 는 소스 생성 함수(앱: AudioRecordSource.open)로 마이크를 열고 MicRecorder.record 를 viewModelScope 에서 돌린다.
//       블록마다 레벨(RMS)과 경과 시간(누적 프레임 ÷ 실제 샘플레이트)을 반영하고, stop() 이 정지 신호를 켜면 녹음을 마치고
//       input_sound/<lang>/mic/<yyyyMMdd_HHmmss>.wav(16 kHz mono PCM16)로 저장한다. 실제 녹음 형식이 16 kHz mono 가 아니면 변환 사실을 note 로 보인다.
//       권한 없음(SecurityException)·열기 실패는 한국어 메시지(크래시 없음). 화면을 떠나면 화면이 stop() 을 불러 녹음을 끝내고 저장한다.
//       change(add)-hyungchul-2026-10-11-04:39 (정정) ViewModel 이 사라질 때(예: 시스템 뒤로 가기로 마이크 화면이 스택에서 빠짐)도 녹음은 버리지 않는다:
//       녹음·저장 블록을 NonCancellable 로 돌려 viewModelScope 취소의 영향을 받지 않게 하고, onCleared 가 정지 신호를 켜서 그때까지의 녹음을 저장한다.
//       최대 길이(기본 10분)에 닿으면 자동으로 멈추고 저장하며 안내한다. 메모리 부족(OutOfMemoryError)은 녹음기가 그때까지의 녹음으로 멈추고,
//       저장 중 메모리 부족은 한국어 메시지로 알린다(크래시 없음).
// change(add)-hyungchul-2026-10-11-03:59
package com.cowork.stt_ondevice.ui.mic // 마이크 화면 패키지

import androidx.lifecycle.ViewModel // 뷰모델 기본 클래스
import androidx.lifecycle.ViewModelProvider // 뷰모델 팩토리
import androidx.lifecycle.viewModelScope // 뷰모델 코루틴 범위
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.core.audio.AudioRecordSource // 실제 마이크 소스
import com.cowork.stt_ondevice.core.audio.MicRecorder // 녹음기
import com.cowork.stt_ondevice.core.audio.PcmSource // 소스 추상화
import kotlinx.coroutines.CoroutineDispatcher // 디스패처 타입
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.NonCancellable // change(add)-hyungchul-2026-10-11-04:39 취소되지 않는 컨텍스트(ViewModel 정리 중에도 저장)
import kotlinx.coroutines.flow.MutableStateFlow // 변경 가능한 상태 흐름
import kotlinx.coroutines.flow.StateFlow // 읽기 전용 상태 흐름
import kotlinx.coroutines.flow.asStateFlow // 읽기 전용 변환
import kotlinx.coroutines.flow.update // 원자적 갱신
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.time.LocalDateTime // 현재 시각

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 마이크 화면 상태
 * 입력: lang(저장할 언어 폴더 kr/en), recording(녹음 중), saving(저장 중), elapsedSec(경과 초), level(0..1 RMS 레벨),
 *       savedPath(마지막 저장 wav 절대 경로 또는 null), savedInfo(저장 형식 설명 또는 null), note(16 kHz 대체 녹음 안내 또는 null),
 *       message(일회성 안내 — 화면이 보인 뒤 consumeMessage)
 */
data class MicState(
    val lang: String = "kr", // 언어 폴더
    val recording: Boolean = false, // 녹음 중
    val saving: Boolean = false, // 저장 중
    val elapsedSec: Double = 0.0, // 경과 시간
    val level: Float = 0f, // 레벨
    val savedPath: String? = null, // 저장 경로
    val savedInfo: String? = null, // 저장 형식 설명
    val note: String? = null, // 대체 녹음 안내
    val message: String? = null, // 안내 문구
)

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 마이크 화면 ViewModel
 * 기능: 파일 머리말 참조.
 * 입력: inputRoot(input_sound 폴더), openSource(마이크 소스를 열어 (소스, 대체 안내) 를 돌려주는 함수 — 테스트에서 교체),
 *       clock(현재 시각 — 파일 이름), io(녹음 루프·저장 디스패처), maxSec(change(add)-hyungchul-2026-10-11-04:39 최대 녹음 길이(초), 기본 600 = 10분)
 */
class MicViewModel(
    private val inputRoot: File, // input_sound
    private val openSource: () -> Pair<PcmSource, String?>, // 소스 열기
    private val clock: () -> LocalDateTime = LocalDateTime::now, // 현재 시각
    private val io: CoroutineDispatcher = Dispatchers.IO, // 작업 디스패처
    private val maxSec: Int = MAX_SEC, // change(add)-hyungchul-2026-10-11-04:39 최대 녹음 길이(초)
) : ViewModel() {

    private val _state = MutableStateFlow(MicState()) // 내부 상태
    private var stopFlag: MutableStateFlow<Boolean>? = null // 진행 중 녹음의 정지 신호(메인 스레드에서만 읽고 씀)

    /** 목적: 화면 상태 */
    val state: StateFlow<MicState> = _state.asStateFlow() // 읽기 전용 노출

    /**
     * 목적: 저장 언어 바꾸기
     * 기능: 녹음 중이 아니면 kr/en 중 하나로 바꾼다(그 밖 값·녹음 중은 무시).
     * 입력: lang(언어 폴더)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun setLang(lang: String) {
        _state.update { s -> if (s.recording || s.saving || lang !in LANGS) s else s.copy(lang = lang) } // 조건부 변경
    }

    /**
     * 목적: 녹음 시작
     * 기능: 이미 녹음·저장 중이면 무시. 소스를 열고(권한 없음·열기 실패는 한국어 메시지) 녹음 작업을 시작한다.
     *       정지 신호가 켜지면 결과를 micFile 경로에 저장하고 savedPath·savedInfo 를 채운다. 녹음된 샘플이 없으면 저장하지 않고 안내만.
     *       change(add)-hyungchul-2026-10-11-04:39 녹음·저장은 NonCancellable 로 돌린다(ViewModel 정리로 viewModelScope 가 취소돼도 onCleared 의 정지 신호로 끝나고 저장됨).
     *       maxSec 에 닿으면 자동 정지·저장 후 안내, 메모리 부족으로 멈추면 그 사실을 안내한다.
     * 입력: 없음
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun start() {
        val s0 = _state.value // 현재 상태
        if (s0.recording || s0.saving) return // 중복 시작 방지
        val (source, note) = try {
            openSource() // 마이크 열기
        } catch (e: SecurityException) {
            _state.update { it.copy(message = "마이크 권한이 없어 녹음할 수 없습니다. 권한을 허용한 뒤 다시 시도하세요") } // 권한 없음
            return // 중단
        } catch (e: Exception) {
            _state.update { it.copy(message = "마이크를 열 수 없습니다: ${e.message ?: e::class.java.simpleName}") } // 열기 실패
            return // 중단
        }
        val recorder = MicRecorder(source, io) // 녹음기
        val stop = MutableStateFlow(false) // 정지 신호
        stopFlag = stop // 보관
        val lang = s0.lang // 시작 시점 언어
        _state.update { it.copy(recording = true, elapsedSec = 0.0, level = 0f, note = note, savedPath = null, savedInfo = null) } // 녹음 중 표시
        viewModelScope.launch {
            try {
                withContext(NonCancellable) { // change(add)-hyungchul-2026-10-11-04:39 ViewModel 정리(viewModelScope 취소) 중에도 녹음 종료·저장까지 마침
                    val maxFrames = maxSec.toLong() * recorder.actualSampleRate // 최대 프레임 수
                    val pcm = recorder.record(stop, maxFrames) { rms, frames -> // 블록마다(io 스레드)
                        _state.update { it.copy(level = rms, elapsedSec = frames.toDouble() / recorder.actualSampleRate) } // 레벨·경과 시간
                    }
                    _state.update { it.copy(recording = false, saving = true, level = 0f) } // 저장 단계
                    if (pcm.isEmpty()) { // 녹음된 것 없음
                        _state.update { it.copy(message = "녹음된 소리가 없어 저장하지 않았습니다") } // 안내
                        return@withContext // 끝
                    }
                    val info = withContext(io) { recorder.save(pcm, MicRecorder.micFile(inputRoot, lang, clock())) } // 저장
                    val desc = "녹음 ${recorder.actualSampleRate} Hz · ${recorder.actualChannels}ch PCM16" + // 실제 녹음 형식
                        (if (info.converted) " → 16 kHz mono 로 변환해 저장" else " → 16 kHz mono 그대로 저장") + // 변환 여부
                        " · 길이 ${"%.1f".format(java.util.Locale.ROOT, info.durationSec)}초" // 길이
                    val why = when { // change(add)-hyungchul-2026-10-11-04:39 자동 정지 사유
                        recorder.stoppedByLimit -> "최대 녹음 길이(${maxSec / 60}분)에 닿아 자동으로 멈췄습니다. " // 길이 제한
                        recorder.stoppedByMemory -> "메모리가 부족해 녹음을 멈췄습니다(그때까지 녹음한 부분만 저장). " // 메모리 부족
                        else -> "" // 사용자 정지
                    }
                    _state.update { it.copy(savedPath = info.file.absolutePath, savedInfo = desc, message = "${why}저장했습니다: ${info.file.name}") } // 결과
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // change(add)-hyungchul-2026-10-11-04:39 정리 후 돌아올 때의 취소 — 저장은 이미 끝났으므로 실패 안내 없이 그대로 전파
            } catch (e: Exception) {
                _state.update { it.copy(message = "녹음 실패: ${e.message ?: e::class.java.simpleName}") } // 실패
            } catch (e: OutOfMemoryError) { // change(add)-hyungchul-2026-10-11-04:39 저장(변환·WAV 버퍼) 중 메모리 부족 — 크래시 대신 안내
                _state.update { it.copy(message = "메모리가 부족해 녹음을 저장하지 못했습니다. 더 짧게 녹음해 주세요") } // 안내
            } finally {
                _state.update { it.copy(recording = false, saving = false, level = 0f) } // 상태 정리
                if (stopFlag === stop) stopFlag = null // 핸들 정리
            }
        }
    }

    /**
     * 목적: 녹음 정지(저장으로 이어짐)
     * 기능: 진행 중인 녹음의 정지 신호를 켠다. 녹음 중이 아니면 아무 일도 없다. 화면을 떠날 때도 부른다.
     * 입력: 없음
     * 출력: 녹음 작업이 끝나며 저장
     * 리턴: 없음
     */
    fun stop() {
        stopFlag?.value = true // 정지 신호
    }

    // change(add)-hyungchul-2026-10-11-04:39
    /**
     * 목적: ViewModel 정리 시 녹음 마무리
     * 기능: 정지 신호를 켠다. 녹음 블록은 NonCancellable 이라 viewModelScope 가 취소돼도 지금까지의 녹음을 저장하고 끝난다
     *       (예: 녹음 중 시스템 뒤로 가기로 마이크 화면이 스택에서 빠질 때).
     * 입력·출력: 없음 / 저장으로 이어짐
     * 리턴: 없음
     */
    override fun onCleared() {
        stop() // 정지 → 저장
    }

    /**
     * 목적: 안내 문구 소비 / 기능: 화면이 스낵바로 보인 뒤 부른다 / 입력·출력: 없음 / 리턴: 없음
     */
    fun consumeMessage() {
        _state.update { it.copy(message = null) } // 비우기
    }

    companion object {
        val LANGS = listOf("kr", "en") // 저장 언어 폴더(입력 규칙과 같음)
        const val MAX_SEC = 600 // change(add)-hyungchul-2026-10-11-04:39 최대 녹음 길이(초) — 10분(16 kHz mono 약 19 MB)

        /**
         * 목적: 앱 전역 객체로 ViewModel 팩토리 만들기
         * 기능: inputRoot = dirs.inputSound, 소스 = AudioRecordSource.open()(대체 안내 포함).
         * 입력: app(App 인스턴스)
         * 출력: 없음
         * 리턴: ViewModelProvider.Factory
         */
        fun factory(app: App): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // 요청 클래스는 MicViewModel 뿐
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(MicViewModel::class.java)) { "지원하지 않는 ViewModel: ${modelClass.name}" } // 다른 타입 거부
                return MicViewModel(app.dirs.inputSound, { AudioRecordSource.open().let { it to it.fallbackNote } }) as T // 실제 마이크
            }
        }
    }
}
