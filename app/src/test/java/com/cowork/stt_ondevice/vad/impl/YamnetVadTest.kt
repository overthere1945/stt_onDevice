// 파일명: YamnetVadTest.kt (테스트 소스)
// 목적: YamnetVad(LiteRT yamnet.tflite + SimpleHangover)의 Kotlin 쪽 처리(창 자르기·Speech 점수 선택·후처리)를 JVM 에서 검증
// 기능: LiteRT 는 JVM 런타임이 없으므로 추론 자리에 가짜 함수((15600 샘플 창) → 521 클래스 점수)를 주입한다.
//       - speechClassIndexIsZero: 공식 클래스 표(yamnet_class_map.csv, 테스트 리소스)에서 "Speech" 행의 index 가 엔진 상수와 같고 0 인지
//       - scoresToSegments: 고정 Speech 점수 배열 → YAMNET 3 프리셋의 기대 구간(손으로 계산)
//       - windowsAreTrailingWithZeroPadAndPcm16Scale: 프레임 i 의 창이 android-vad(TensorAudio 링버퍼)와 같은지
//         (끝 = (i+1)·243 샘플, 앞쪽 0 채움, 값 = int16 / 32767)
//       - 짧은 입력·잘못된 계열/파라미터/출력 크기·close 전달
//       실제 LiteRT 점수는 기기 테스트(NativeSmokeTest.yamnetRuns / yamnetMatchesGoldenScores)가 검증한다.
// change(add)-hyungchul-2026-10-10-15:43
package com.cowork.stt_ondevice.vad.impl // VAD 구현 테스트 패키지

import com.cowork.stt_ondevice.vad.SpeechSegment // 음성 구간
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 레지스트리
import com.cowork.stt_ondevice.vad.WebRtcParams // 잘못된 params 타입 예시
import com.cowork.stt_ondevice.vad.WebRtcStyle // WebRtcParams 생성용
import org.junit.Assert.assertArrayEquals // 배열 일치 검증
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Test // JUnit4 테스트 표시

// change(add)-hyungchul-2026-10-10-15:43
/**
 * 목적: YamnetVad 의 JVM 단위 테스트 모음
 * 기능: 추론 함수를 주입해 모델 없이 엔진 경로(PCM 변환 → 243 hop 창 → Speech 점수 → SimpleHangover → VadOutput)를 검증한다.
 */
class YamnetVadTest {

    private val hopSec = 243.0 / 16000 // 기대 프레임 간격(초) = android-vad FRAME_SIZE_243

    /**
     * 목적: 가짜 추론 함수 생성(호출 순서대로 정해진 Speech 점수를 돌려줌)
     * 기능: n 번째 호출에 521 칸 배열을 만들고 index 0 = speech[n], index 1 = 0.99(다른 클래스가 최고점이어도 index 0 만 쓰는지 확인용)로 채운다.
     * 입력: speech(프레임별 Speech 점수)
     * 출력: 없음
     * 리턴: (창) → 521 점수 함수
     */
    private fun scripted(speech: FloatArray): (FloatArray) -> FloatArray {
        var n = 0 // 호출 횟수
        return { window -> // 가짜 추론
            assertEquals("창 길이 15600", 15600, window.size) // 입력 창 크기 확인
            val out = FloatArray(521) // 521 클래스 점수
            out[1] = 0.99f // 다른 클래스(Child speech) 최고점 — 엔진은 무시해야 함
            out[0] = speech[n++] // Speech(index 0) 점수
            out // 반환
        }
    }

    /**
     * 목적: Speech 클래스 index 를 라벨 파일에서 확인
     * 기능: 테스트 리소스 yamnet/yamnet_class_map.csv(tensorflow/models 공식 표, 모델 내장 yamnet_label_list.txt 와 521 개 모두 같음을 PC 에서 확인)
     *       를 읽어 행 수 = 521 = NUM_CLASSES, index 열 = 0..520, display_name 이 "Speech" 인 행이 1개이고 그 index = SPEECH_CLASS_INDEX = 0 인지 본다.
     * 입력: 없음
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun speechClassIndexIsZero() {
        val text = javaClass.getResourceAsStream("/yamnet/yamnet_class_map.csv")!!.bufferedReader(Charsets.UTF_8).use { it.readText() } // 라벨 파일
        val lines = text.lines().filter { it.isNotBlank() } // 빈 줄 제외
        assertEquals("CSV 머리글", "index,mid,display_name", lines.first()) // 형식 확인
        val rows = lines.drop(1).map { line -> // 데이터 행마다
            val first = line.indexOf(',') // index 열 끝
            val second = line.indexOf(',', first + 1) // mid 열 끝
            val index = line.substring(0, first).toInt() // index 값
            val name = line.substring(second + 1).removeSurrounding("\"") // display_name(쉼표 포함 이름은 따옴표로 감싸져 있음)
            index to name // (index, 이름)
        }
        assertEquals("클래스 수", YamnetVad.NUM_CLASSES, rows.size) // 521 클래스
        assertEquals("index 열은 0..520 순서", (0 until 521).toList(), rows.map { it.first }) // 순서 확인
        val speechRows = rows.filter { it.second == "Speech" } // "Speech" 행
        assertEquals("Speech 행은 1개", 1, speechRows.size) // 유일성
        assertEquals("Speech index = 엔진 상수", speechRows.single().first, YamnetVad.SPEECH_CLASS_INDEX) // 엔진 상수와 일치
        assertEquals("Speech index = 0", 0, YamnetVad.SPEECH_CLASS_INDEX) // brief 요구값
    }

    /**
     * 목적: 고정 Speech 점수 → 프리셋별 기대 구간 검증
     * 기능: 12 프레임(12·243 + 100 샘플) 입력에 점수 [0.1,0.2,0.6,0.9,0.6,0.1,0.1,0.95,0.2,0.4,0.4,0.1] 를 주입한다.
     *       hop 15.1875 ms 에서 speech/silence 30 ms → 필요 프레임 2.
     *       - NORMAL(0.3): 음성 2,3,4,7,9,10 → [2,5) (무음 5,6 에서 종료), 7 은 1 프레임이라 시작 안 함, [9,11) (끝에서 진행 중 무음 11 에서 종료)
     *       - AGGRESSIVE(0.5): 음성 2,3,4,7 → [2,5)
     *       - VERY_AGGRESSIVE(0.85): 음성 3,7 (각 1 프레임) → 구간 없음
     * 입력: 없음
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun scoresToSegments() {
        val speech = floatArrayOf(0.1f, 0.2f, 0.6f, 0.9f, 0.6f, 0.1f, 0.1f, 0.95f, 0.2f, 0.4f, 0.4f, 0.1f) // 프레임별 Speech 점수
        val audio = FloatArray(12 * 243 + 100) // 12 프레임 + 잔여 100 샘플(버림)
        val expected = mapOf( // 프리셋 → 기대 구간(프레임 인덱스)
            "YAMNET_NORMAL" to listOf(2 to 5, 9 to 11), // threshold 0.3
            "YAMNET_AGGRESSIVE" to listOf(2 to 5), // threshold 0.5
            "YAMNET_VERY_AGGRESSIVE" to emptyList(), // threshold 0.85
        )
        for ((id, frames) in expected) { // 프리셋마다
            val out = YamnetVad(scripted(speech)) {}.use { it.detect(audio, VadPresets.byId(id)) } // 새 가짜 추론으로 실행
            assertEquals("$id 프레임 간격", hopSec, out.frameHopSec!!, 0.0) // hop = 243/16000
            assertArrayEquals("$id 점수 = Speech(index 0) 점수", speech, out.frameScores, 0f) // index 0 만 사용
            assertEquals("$id 구간", frames.map { (s, e) -> SpeechSegment(s * hopSec, e * hopSec) }, out.segments) // 기대 구간
        }
    }

    /**
     * 목적: 창 구성이 android-vad(TensorAudio 링버퍼 15600 + FRAME_SIZE_243 load)와 같은지 검증
     * 기능: N = 15600 + 5·243 + 17 샘플(int16 패턴 / 32768 float)을 넣고 각 호출의 창을 복사해 둔다.
     *       프레임 수 = floor(N/243), 프레임 i 의 창 j 번째 값 = (src = (i+1)·243 − 15600 + j) ≥ 0 이면 int16[src] / 32767f, 아니면 0 (비트 단위 일치).
     * 입력: 없음
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun windowsAreTrailingWithZeroPadAndPcm16Scale() {
        val n = 15600 + 5 * 243 + 17 // 입력 샘플 수(243 배수 아님)
        val pcm = IntArray(n) { ((it.toLong() * 7919L) % 65536L).toInt() - 32768 } // int16 전 범위를 도는 결정적 패턴
        val audio = FloatArray(n) { pcm[it] / 32768f } // 앱 정규화(int16 / 32768)
        val windows = mutableListOf<FloatArray>() // 호출마다 받은 창(복사본)
        YamnetVad({ w -> windows += w.copyOf(); FloatArray(521) }) {}.use { it.detect(audio, VadPresets.byId("YAMNET_NORMAL")) } // 창 기록
        val numFrames = n / 243 // 기대 프레임 수 floor(N/243)
        assertEquals("프레임 수 = floor(N/243)", numFrames, windows.size) // 호출 수
        for (i in 0 until numFrames) { // 프레임마다
            val expected = FloatArray(15600) { j -> // 기대 창
                val src = (i + 1) * 243 - 15600 + j // 원본 샘플 위치(창 끝 = (i+1)·243)
                if (src >= 0) pcm[src].toFloat() / 32767f else 0f // TensorAudio: short / Short.MAX_VALUE, 링버퍼 초기값 0
            }
            assertArrayEquals("프레임 $i 창", expected, windows[i], 0f) // 비트 단위 일치
        }
        assertEquals("첫 창 끝 값 = int16[242] / 32767", pcm[242] / 32767f, windows[0][15599], 0f) // 첫 프레임 마지막 샘플
        assertEquals("첫 창 앞은 0", 0f, windows[0][15600 - 244], 0f) // 첫 프레임 앞쪽 0 채움
    }

    /**
     * 목적: 한 프레임(243 샘플)보다 짧은 입력 처리
     * 기능: 242 샘플 → 추론 호출 없음, 구간 없음, 점수 빈 배열, hop 은 그대로.
     * 입력: 없음
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun shortAudioGivesNoFrames() {
        var calls = 0 // 추론 호출 수
        val out = YamnetVad({ calls++; FloatArray(521) }) {}.use { it.detect(FloatArray(242), VadPresets.byId("YAMNET_NORMAL")) } // 짧은 입력
        assertEquals("추론 호출 없음", 0, calls) // 완전한 프레임 없음
        assertTrue("구간 없음", out.segments.isEmpty()) // 빈 구간
        assertEquals("점수 0 개", 0, out.frameScores!!.size) // 빈 점수(null 아님)
        assertEquals("hop", hopSec, out.frameHopSec!!, 0.0) // hop 유지
    }

    /**
     * 목적: 잘못된 입력·출력을 조용히 넘기지 않는지 확인
     * 기능: 다른 계열 프리셋·YAMNET 계열인데 params 타입이 다른 프리셋 → IllegalArgumentException,
     *       추론 결과 크기 ≠ 521 → IllegalStateException, close → 주입한 해제 함수 호출.
     * 입력: 없음
     * 출력: 없음(불일치 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun rejectsBadInputsAndForwardsClose() {
        val audio = FloatArray(243 * 3) // 3 프레임
        YamnetVad({ FloatArray(521) }) {}.use { vad -> // 정상 크기 추론
            assertThrows(IllegalArgumentException::class.java) { vad.detect(audio, VadPresets.byId("TEN_DEFAULT")) } // 다른 계열
            val badParams = VadPreset("YAMNET_BAD", VadFamily.YAMNET, WebRtcParams(0, 30, WebRtcStyle.RAW), "테스트") // 잘못된 params 타입
            assertThrows(IllegalArgumentException::class.java) { vad.detect(audio, badParams) } // params 타입 오류
        }
        YamnetVad({ FloatArray(520) }) {}.use { vad -> // 잘못된 출력 크기
            assertThrows(IllegalStateException::class.java) { vad.detect(audio, VadPresets.byId("YAMNET_NORMAL")) } // 521 아님
        }
        var closed = 0 // 해제 호출 수
        YamnetVad({ FloatArray(521) }) { closed++ }.close() // close 전달
        assertEquals("close 가 해제 함수를 1번 호출", 1, closed) // 해제 확인
    }
}
