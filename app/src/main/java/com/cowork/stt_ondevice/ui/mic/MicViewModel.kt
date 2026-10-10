// 파일명: ui/mic/MicViewModel.kt
// 목적: 마이크 화면의 상태(언어·녹음 중·경과 시간·레벨·저장 결과)와 녹음 시작/정지·저장을 담당하는 ViewModel
// 기능: start() 는 소스 생성 함수(앱: AudioRecordSource.open)로 마이크를 열고 MicRecorder.record 를 viewModelScope 에서 돌린다.
//       블록마다 레벨(RMS)과 경과 시간(누적 프레임 ÷ 실제 샘플레이트)을 반영하고, stop() 이 정지 신호를 켜면 녹음을 마치고
//       input_sound/<lang>/mic/<yyyyMMdd_HHmmss>.wav(16 kHz mono PCM16)로 저장한다. 실제 녹음 형식이 16 kHz mono 가 아니면 변환 사실을 note 로 보인다.
//       권한 없음(SecurityException)·열기 실패는 한국어 메시지(크래시 없음). 화면을 떠나면 화면이 stop() 을 불러 녹음을 끝내고 저장한다.
//       ViewModel 이 사라지면(onCleared) 녹음 작업이 취소되고 소스는 해제된다(저장하지 않음).
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
 *       clock(현재 시각 — 파일 이름), io(녹음 루프·저장 디스패처)
 */
class MicViewModel(
    private val inputRoot: File, // input_sound
    private val openSource: () -> Pair<PcmSource, String?>, // 소스 열기
    private val clock: () -> LocalDateTime = LocalDateTime::now, // 현재 시각
    private val io: CoroutineDispatcher = Dispatchers.IO, // 작업 디스패처
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
                val pcm = recorder.record(stop) { rms, frames -> // 블록마다(io 스레드)
                    _state.update { it.copy(level = rms, elapsedSec = frames.toDouble() / recorder.actualSampleRate) } // 레벨·경과 시간
                }
                _state.update { it.copy(recording = false, saving = true, level = 0f) } // 저장 단계
                if (pcm.isEmpty()) { // 녹음된 것 없음
                    _state.update { it.copy(message = "녹음된 소리가 없어 저장하지 않았습니다") } // 안내
                    return@launch // 끝
                }
                val info = withContext(io) { recorder.save(pcm, MicRecorder.micFile(inputRoot, lang, clock())) } // 저장
                val desc = "녹음 ${recorder.actualSampleRate} Hz · ${recorder.actualChannels}ch PCM16" + // 실제 녹음 형식
                    (if (info.converted) " → 16 kHz mono 로 변환해 저장" else " → 16 kHz mono 그대로 저장") + // 변환 여부
                    " · 길이 ${"%.1f".format(java.util.Locale.ROOT, info.durationSec)}초" // 길이
                _state.update { it.copy(savedPath = info.file.absolutePath, savedInfo = desc, message = "저장했습니다: ${info.file.name}") } // 결과
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 취소(ViewModel 정리)는 그대로 — 소스는 record 가 해제
            } catch (e: Exception) {
                _state.update { it.copy(message = "녹음 실패: ${e.message ?: e::class.java.simpleName}") } // 실패
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

    /**
     * 목적: 안내 문구 소비 / 기능: 화면이 스낵바로 보인 뒤 부른다 / 입력·출력: 없음 / 리턴: 없음
     */
    fun consumeMessage() {
        _state.update { it.copy(message = null) } // 비우기
    }

    companion object {
        val LANGS = listOf("kr", "en") // 저장 언어 폴더(입력 규칙과 같음)

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
