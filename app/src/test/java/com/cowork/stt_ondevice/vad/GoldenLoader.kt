// 파일명: GoldenLoader.kt (테스트 소스)
// 목적: Task 7 이 만든 VAD 골든 JSON(app/src/test/resources/golden/*.json)을 JVM 단위 테스트에서 읽는 공용 도구
// 기능: 골든 1건 읽기(load), 계열별 구간 골든 전체 읽기(allFor), 계열 파일 수 세기(countFilesFor),
//       구간 목록 비교(assertSegmentsClose)를 제공한다. JSON 형식은 tools/golden/README_한국어.md §5 를 따른다.
//       모르는 키는 무시한다(JsonObject 로 필요한 키만 꺼냄, R13).
//       change(add)-hyungchul-2026-10-10-13:56 모델 추론 정합용 점수 골든 읽기(loadScores, Task 12) 추가.
//       change(add)-hyungchul-2026-10-10-15:04 원문 JSON 객체 읽기(loadRaw, Task 14) 추가.
package com.cowork.stt_ondevice.vad // VAD 테스트 패키지

import kotlinx.serialization.json.Json // kotlinx JSON 파서
import kotlinx.serialization.json.JsonArray // JSON 배열
import kotlinx.serialization.json.JsonNull // JSON null
import kotlinx.serialization.json.JsonObject // JSON 객체
import kotlinx.serialization.json.jsonArray // 요소 → 배열 변환
import kotlinx.serialization.json.jsonObject // 요소 → 객체 변환
import kotlinx.serialization.json.jsonPrimitive // 요소 → 기본값 변환
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertTrue // 참 검증
import java.io.File // 리소스 폴더 목록 조회

// change(add)-hyungchul-2026-10-10-13:04
/**
 * 목적: 구간 골든 JSON 1건의 메모리 표현
 * 기능: 공통 키(계획서 형식)와 Silero 계열 정밀 비교에 쓰는 추가 키(totalSamples, expectedSamples, windowSamples, params)를 담는다.
 *       추가 키가 JSON 에 없으면 null 이다(0 으로 채우지 않음).
 *       change(add)-hyungchul-2026-10-10-13:25 FSMN 정밀 비교용 expectedMs·blocks 추가(기본값 null).
 */
data class Golden(
    val name: String, // 골든 파일 이름(확장자 .json 제외)
    val clip: String, // 클립 이름(실제 클립은 .wav 파일명, 손작성은 synthetic_maxspeech_*)
    val family: String, // VAD 계열 문자열(SILERO / PYANNOTE / FSMN / WEBRTC / TEN)
    val presetId: String, // 앱 VadPresets id
    val hopSec: Double?, // 프레임 간격(초)
    val frameTimesSec: DoubleArray?, // pyannote 만: 프레임 중앙 시각(초)
    val frameScores: FloatArray, // 프레임 점수(float32 로 비트 단위 복원)
    val frameDb: FloatArray?, // FSMN 만: 프레임 dB
    val expected: List<SpeechSegment>, // 기대 구간(초)
    val totalSamples: Int?, // 입력 전체 샘플 수 N(JSON 값 그대로; 프레임 수 × 창 크기로 추정하지 않음)
    val expectedSamples: List<IntArray>?, // SILERO·TEN_SHERPA 만: 기대 구간 정수 샘플 [start, end]
    val windowSamples: Int?, // SILERO·TEN_SHERPA 만: 창(hop) 크기 샘플(512 또는 256)
    val params: JsonObject?, // 골든 생성 시 적용한 파라미터 원문(계열마다 키가 다름)
    // change(add)-hyungchul-2026-10-10-13:25 FSMN 정밀 비교용 추가 키(Task 9)
    val expectedMs: List<LongArray>? = null, // FSMN 만: 원본 ms 정수 구간 [start, end]
    val blocks: List<GoldenBlock>? = null, // FSMN 만: funasr_onnx 블록 호출 기록(호출 순서)
    // change(add)-hyungchul-2026-10-10-13:43 WEBRTC·TEN_DEFAULT 정밀 비교용 추가 키(Task 10)
    val expectedFrames: List<IntArray>? = null, // WEBRTC·TEN_DEFAULT 만: 기대 구간 프레임 인덱스 [시작, 끝(배타)]
)

// change(add)-hyungchul-2026-10-10-13:25
/**
 * 목적: FSMN 골든의 블록 호출 기록 1건(JSON `blocks` 원소 `[프레임 수, 파형 샘플 수, is_final]`)
 * 기능: funasr_onnx Fsmn_vad 가 E2EVadModel 을 부를 때 넘긴 블록 크기와 is_final 을 담는다.
 */
data class GoldenBlock(
    val frames: Int, // 블록 프레임 수(점수 개수)
    val waveSamples: Int, // 블록 파형 샘플 수
    val isFinal: Boolean, // is_final 인자
)

// change(add)-hyungchul-2026-10-10-13:56
/**
 * 목적: 모델 추론 정합용 점수 골든 `golden/scores_<family>_<clipStem>.json` 1건의 메모리 표현(Task 12)
 * 기능: 원본 Python 이 실제로 쓴 프레임 점수(Silero 확률 / pyannote 집계 speech 점수 / FSMN sil prob)와
 *       pyannote 프레임 중앙 시각, 전체 샘플 수를 담는다(tools/golden/README_한국어.md §5.2).
 */
data class GoldenScores(
    val name: String, // 파일 이름(확장자 제외)
    val clip: String, // 클립 파일명(.wav)
    val family: String, // 계열 문자열(SILERO / PYANNOTE / FSMN)
    val hopSec: Double?, // 프레임 간격(초)
    val frameScores: FloatArray, // 프레임 점수(float32 비트 단위 복원)
    val frameTimesSec: DoubleArray?, // pyannote 만: 프레임 중앙 시각(초)
    val totalSamples: Int, // 입력 전체 샘플 수 N
)

// change(add)-hyungchul-2026-10-10-13:04
/**
 * 목적: 골든 JSON 로더
 * 기능: 클래스패스의 golden/ 폴더에서 JSON 을 읽어 Golden 으로 바꾼다.
 */
object GoldenLoader {

    private const val DIR = "golden" // 클래스패스 안 골든 폴더 이름
    private val json = Json { ignoreUnknownKeys = true } // 파서(요소 트리로만 읽으므로 모르는 키는 자연히 무시)

    /**
     * 목적: 골든 1건 읽기
     * 기능: golden/<name>.json 을 읽어 공통 키와 추가 키를 꺼낸다. 구간 골든이 아니면(presetId/expected 가 null) 예외.
     * 입력: name(확장자 제외 파일 이름, 예: "SILERO_SILERO_kr_customer_01_20s_plus77")
     * 출력: 없음
     * 리턴: Golden
     */
    fun load(name: String): Golden {
        val res = javaClass.classLoader!!.getResource("$DIR/$name.json") // 클래스패스 리소스 찾기
            ?: throw IllegalArgumentException("골든 파일 없음: $DIR/$name.json") // 없으면 바로 실패
        val obj = json.parseToJsonElement(res.readText()).jsonObject // JSON 객체로 파싱
        return Golden( // 필요한 키만 꺼내 조립
            name = name, // 파일 이름
            clip = obj.str("clip")!!, // 클립 이름(필수)
            family = obj.str("family")!!, // 계열(필수)
            presetId = obj.str("presetId") ?: throw IllegalArgumentException("구간 골든 아님(presetId null): $name"), // 프리셋 id(필수)
            hopSec = obj.num("hopSec")?.toDouble(), // 프레임 간격
            frameTimesSec = obj.arr("frameTimesSec")?.let { a -> DoubleArray(a.size) { a[it].jsonPrimitive.content.toDouble() } }, // pyannote 시각
            frameScores = obj.arr("frameScores")!!.toFloatArray(), // 프레임 점수(필수)
            frameDb = obj.arr("frameDb")?.toFloatArray(), // FSMN dB
            expected = (obj.arr("expected") ?: throw IllegalArgumentException("구간 골든 아님(expected null): $name")) // 기대 구간(필수)
                .map { e -> // 각 구간 객체
                    val o = e.jsonObject // {"start", "end"}
                    SpeechSegment(o.num("start")!!.toDouble(), o.num("end")!!.toDouble()) // 초 단위 구간
                },
            totalSamples = obj.num("totalSamples")?.toInt(), // 전체 샘플 수
            expectedSamples = obj.arr("expectedSamples")?.map { p -> // 정수 샘플 구간
                val a = p.jsonArray // [start, end]
                intArrayOf(a[0].jsonPrimitive.content.toInt(), a[1].jsonPrimitive.content.toInt()) // 정수 쌍
            },
            windowSamples = obj.num("windowSamples")?.toInt(), // 창 크기
            params = obj["params"]?.takeUnless { it is JsonNull }?.jsonObject, // 파라미터 원문
            // change(add)-hyungchul-2026-10-10-13:25 FSMN 추가 키(없으면 null)
            expectedMs = obj.arr("expectedMs")?.map { p -> // ms 정수 구간
                val a = p.jsonArray // [start, end]
                longArrayOf(a[0].jsonPrimitive.content.toLong(), a[1].jsonPrimitive.content.toLong()) // 정수 쌍
            },
            blocks = obj.arr("blocks")?.map { b -> // 블록 호출 기록
                val a = b.jsonArray // [프레임 수, 파형 샘플 수, is_final]
                GoldenBlock(a[0].jsonPrimitive.content.toInt(), a[1].jsonPrimitive.content.toInt(), a[2].jsonPrimitive.content.toBooleanStrict()) // 원소 변환
            },
            // change(add)-hyungchul-2026-10-10-13:43 WEBRTC·TEN_DEFAULT 프레임 인덱스 구간(없으면 null)
            expectedFrames = obj.arr("expectedFrames")?.map { p -> // 프레임 구간
                val a = p.jsonArray // [시작, 끝(배타)]
                intArrayOf(a[0].jsonPrimitive.content.toInt(), a[1].jsonPrimitive.content.toInt()) // 정수 쌍
            },
        )
    }

    // change(add)-hyungchul-2026-10-10-13:56
    /**
     * 목적: 모델 추론 정합용 점수 골든 1건 읽기(Task 12)
     * 기능: golden/scores_<family>_<clipStem>.json 을 읽어 점수·시각·전체 샘플 수를 꺼내고 family 가 일치하는지 확인한다.
     * 입력: family(계열 문자열, 예: "SILERO"), clipStem(클립 이름에서 .wav 를 뺀 것)
     * 출력: 파일이 없거나 필수 키가 없으면 예외
     * 리턴: GoldenScores
     */
    fun loadScores(family: String, clipStem: String): GoldenScores {
        val name = "scores_${family}_$clipStem" // 파일 이름 규칙
        val res = javaClass.classLoader!!.getResource("$DIR/$name.json") // 클래스패스 리소스 찾기
            ?: throw IllegalArgumentException("점수 골든 파일 없음: $DIR/$name.json") // 없으면 바로 실패
        val obj = json.parseToJsonElement(res.readText()).jsonObject // JSON 객체로 파싱
        val g = GoldenScores( // 필요한 키만 꺼내 조립
            name = name, // 파일 이름
            clip = obj.str("clip")!!, // 클립 파일명(필수)
            family = obj.str("family")!!, // 계열(필수)
            hopSec = obj.num("hopSec")?.toDouble(), // 프레임 간격
            frameScores = obj.arr("frameScores")!!.toFloatArray(), // 점수(필수)
            frameTimesSec = obj.arr("frameTimesSec")?.let { a -> DoubleArray(a.size) { a[it].jsonPrimitive.content.toDouble() } }, // pyannote 시각
            totalSamples = obj.num("totalSamples")!!.toInt(), // 전체 샘플 수(필수)
        )
        assertEquals("JSON family 불일치: $name", family, g.family) // 파일 이름과 내용의 계열 일치
        return g // 결과
    }

    // change(add)-hyungchul-2026-10-10-15:04
    /**
     * 목적: 골든 JSON 원문 객체 읽기(Task 14: Golden 에 없는 계열별 추가 키 — binarized·filtered·특징 행렬 등 — 를 테스트가 직접 꺼내기 위함)
     * 기능: golden/<name>.json 을 읽어 JsonObject 그대로 돌려준다.
     * 입력: name(확장자 제외 파일 이름)
     * 출력: 파일 없으면 IllegalArgumentException
     * 리턴: JsonObject
     */
    fun loadRaw(name: String): JsonObject {
        val res = javaClass.classLoader!!.getResource("$DIR/$name.json") // 클래스패스 리소스 찾기
            ?: throw IllegalArgumentException("골든 파일 없음: $DIR/$name.json") // 없으면 바로 실패
        return json.parseToJsonElement(res.readText()).jsonObject // JSON 객체로 파싱
    }

    /**
     * 목적: 한 계열의 구간 골든 전체 읽기
     * 기능: 파일 이름이 "<family>_" 로 시작하는 JSON(구간 골든 명명 규칙 <family>_<presetId>_<clipStem>)을
     *       이름순으로 모두 읽는다. scores_/fbank_/lfr_cmvn_ 파일은 접두어가 달라 포함되지 않는다.
     * 입력: family(계열 문자열, 예: "SILERO", "TEN")
     * 출력: 없음
     * 리턴: Golden 목록(파일 이름순)
     */
    fun allFor(family: String): List<Golden> =
        fileNamesFor(family).map { load(it) } // 이름 목록을 차례로 읽음
            .onEach { assertEquals("JSON family 불일치: ${it.name}", family, it.family) } // 파일 이름과 JSON 내용의 계열이 같아야 함

    // change(add)-hyungchul-2026-10-10-13:43
    /**
     * 목적: 한 계열 안의 특정 프리셋 구간 골든만 읽기(TEN 처럼 한 계열에 프리셋이 여럿일 때 TEN_SHERPA 와 섞이지 않게 함)
     * 기능: 파일 이름이 "<family>_<presetId>_" 로 시작하는 JSON 을 이름순으로 모두 읽고, JSON 의 family·presetId 가 일치하는지 확인한다.
     * 입력: family(계열 문자열, 예: "TEN"), presetId(프리셋 id, 예: "TEN_DEFAULT")
     * 출력: 없음
     * 리턴: Golden 목록(파일 이름순)
     */
    fun allForPreset(family: String, presetId: String): List<Golden> =
        fileNamesFor("${family}_$presetId").map { load(it) } // "<family>_<presetId>_" 접두어 파일을 차례로 읽음
            .onEach { assertEquals("JSON family 불일치: ${it.name}", family, it.family) } // 계열 일치 확인
            .onEach { assertEquals("JSON presetId 불일치: ${it.name}", presetId, it.presetId) } // 프리셋 일치 확인

    /**
     * 목적: 테스트가 골든을 빠뜨리지 않았는지 대조할 기준 개수 제공
     * 기능: 골든 폴더에서 주어진 접두어로 시작하는 .json 파일 수를 센다(JSON 파싱 없음).
     * 입력: prefix(파일 이름 접두어, 예: "SILERO_", "TEN_TEN_SHERPA_")
     * 출력: 없음
     * 리턴: 파일 수
     */
    fun countFiles(prefix: String): Int = listJsonNames().count { it.startsWith(prefix) } // 접두어 일치 파일 수

    /**
     * 목적: 계열 접두어에 맞는 골든 파일 이름 목록
     * 기능: "<family>_" 로 시작하는 이름을 정렬해 돌려준다.
     * 입력: family(계열 문자열)
     * 출력: 없음
     * 리턴: 확장자 제외 파일 이름 목록
     */
    private fun fileNamesFor(family: String): List<String> =
        listJsonNames().filter { it.startsWith("${family}_") }.sorted() // 접두어 필터 후 정렬

    /**
     * 목적: 골든 폴더의 JSON 파일 이름 나열
     * 기능: 클래스패스의 golden/ 폴더(JVM 단위 테스트에서는 실제 디렉터리)를 열어 .json 파일 이름을 모은다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 확장자 제외 파일 이름 목록
     */
    private fun listJsonNames(): List<String> {
        val url = javaClass.classLoader!!.getResource(DIR) // 골든 폴더 URL
            ?: throw IllegalStateException("클래스패스에 $DIR 폴더 없음") // 없으면 실패
        require(url.protocol == "file") { "골든 폴더가 디렉터리가 아님: $url" } // jar 안이면 나열 불가 → 명시적 실패
        val files = File(url.toURI()).listFiles() ?: emptyArray() // 폴더 안 파일
        return files.filter { it.isFile && it.name.endsWith(".json") } // JSON 파일만
            .map { it.name.removeSuffix(".json") } // 확장자 제거
    }

    /** 목적: 문자열 키 읽기. 입력: key. 리턴: 문자열 또는 null(키 없음/JSON null). */
    private fun JsonObject.str(key: String): String? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content // null 이 아니면 문자열 내용

    /** 목적: 숫자 키 읽기(원문 10진 표기 보존). 입력: key. 리턴: 10진 문자열을 담은 BigDecimal 또는 null. */
    private fun JsonObject.num(key: String): java.math.BigDecimal? =
        this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.toBigDecimal() // 원문 문자열 → BigDecimal

    /** 목적: 배열 키 읽기. 입력: key. 리턴: JsonArray 또는 null(키 없음/JSON null). */
    private fun JsonObject.arr(key: String): JsonArray? =
        this[key]?.takeUnless { it is JsonNull }?.jsonArray // null 이 아니면 배열

    /** 목적: 숫자 배열을 FloatArray 로. 기능: 원문 10진 표기를 바로 toFloat(최단 표기라 float32 비트 단위 복원). 리턴: FloatArray. */
    private fun JsonArray.toFloatArray(): FloatArray =
        FloatArray(size) { this[it].jsonPrimitive.content.toFloat() } // 원문 문자열 → float32
}

// change(add)-hyungchul-2026-10-10-13:04
/**
 * 목적: 구간 목록 근사 비교
 * 기능: 개수가 같고, 같은 순번 구간의 시작·끝 차이가 각각 tolSec 이하인지 검사한다. 실패 시 전체 목록을 메시지에 넣는다.
 * 입력: expected(기대 구간), actual(실제 구간), tolSec(허용 오차 초)
 * 출력: 없음(불일치 시 AssertionError)
 * 리턴: 없음
 */
fun assertSegmentsClose(expected: List<SpeechSegment>, actual: List<SpeechSegment>, tolSec: Double) {
    assertEquals("구간 개수 불일치\n기대=$expected\n실제=$actual", expected.size, actual.size) // 개수 동일
    for (i in expected.indices) { // 순번별 비교
        val e = expected[i] // 기대 구간
        val a = actual[i] // 실제 구간
        assertTrue("구간[$i] 시작 차이 초과: 기대=${e.startSec} 실제=${a.startSec} tol=$tolSec", Math.abs(e.startSec - a.startSec) <= tolSec) // 시작 경계
        assertTrue("구간[$i] 끝 차이 초과: 기대=${e.endSec} 실제=${a.endSec} tol=$tolSec", Math.abs(e.endSec - a.endSec) <= tolSec) // 끝 경계
    }
}
