// 파일명: PresetParamCodec.kt
// 목적: VAD 파라미터 ↔ 문자열 map 변환과 값 검증
// 기능: 계열별 편집 필드 목록(키·종류·범위), 파라미터를 PresetEntry.describeParams 와 같은 키·표기의 map 으로 바꾸는 toMap,
//       사용자 입력 map 을 범위·형식 검증해 파라미터로 되돌리는 fromMap 을 제공한다. 실패 사유는 한국어 "<키>: ..." 형식이다.
package com.cowork.stt_ondevice.vad // VAD 패키지

// change(add)-hyungchul-2026-10-11-20:08
/**
 * 목적: USER 프리셋 저장·편집용 파라미터 변환기
 * 기능: 템플릿(기준 프리셋의 params) 타입을 따라 필드를 정하므로 TEN 은 기준 프리셋에 따라 Hangover 또는 Silero 필드를 쓴다.
 *       숫자 표기는 describeParams 와 같다(무한대 "inf", 나머지 Kotlin toString). 기본 프리셋 전체가 왕복해도 변하지 않는다.
 */
object PresetParamCodec {

    /** 목적: 필드 값 종류. 기능: 정수/실수(Float)/실수(Double)/불리언/열거. */
    enum class Kind { INT, FLOAT, DOUBLE, BOOL, ENUM }

    /**
     * 목적: 편집 가능한 필드 1개의 명세
     * 기능: 화면 입력 폼과 검증이 함께 쓴다. min/max 는 양끝 포함이며 범위가 없으면 null 이다.
     *       choices 가 비어 있지 않으면 그 문자열 중 하나여야 한다(ENUM 이나 frameMs 같은 INT 허용 목록).
     * 입력: key(describeParams 키), kind(종류), min(최솟값), max(최댓값), choices(허용 문자열 목록)
     */
    data class Field(
        val key: String, // 키(describeParams 와 같은 이름)
        val kind: Kind, // 값 종류
        val min: Double?, // 최솟값(포함), 없으면 null
        val max: Double?, // 최댓값(포함), 없으면 null
        val choices: List<String> = emptyList(), // 허용 문자열 목록(없으면 빈 목록)
    )

    /** 목적: maxSpeechSec 키 이름. 기능: 이 키만 "inf" 또는 0 보다 큰 값을 허용하는 특례를 적용하기 위한 상수. */
    private const val MAX_SPEECH_SEC = "maxSpeechSec"

    /** 목적: 숫자 문자열 형식. 기능: 지수 표기를 포함한 10진수만 허용(NaN·16진수·f/d 접미사 거부). */
    private val NUMBER_REGEX = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$")

    /** 목적: 정수 문자열 형식. 기능: 부호 + 숫자만 허용(소수점 거부). */
    private val INT_REGEX = Regex("^[+-]?\\d+$")

    /** 목적: 확률·임계값 필드 생성. 기능: 0~1 범위 Float 필드. 입력: key. 출력: 없음. 리턴: Field */
    private fun prob(key: String) = Field(key, Kind.FLOAT, 0.0, 1.0) // 0~1 Float

    /** 목적: 0 이상 정수 필드 생성. 기능: ms·프레임 수 등. 입력: key. 출력: 없음. 리턴: Field */
    private fun nonNegInt(key: String) = Field(key, Kind.INT, 0.0, null) // 0 이상 Int

    /** 목적: 0 이상 Double 필드 생성. 기능: 초 단위 값. 입력: key. 출력: 없음. 리턴: Field */
    private fun nonNegDouble(key: String) = Field(key, Kind.DOUBLE, 0.0, null) // 0 이상 Double

    /** 목적: 범위 없는 Float 필드 생성. 기능: 음수도 허용하는 FSMN 값 등. 입력: key. 출력: 없음. 리턴: Field */
    private fun freeFloat(key: String) = Field(key, Kind.FLOAT, null, null) // 범위 없는 Float

    /**
     * 목적: 계열 타입별 편집 필드 목록
     * 기능: 키 이름과 순서는 PresetEntry.describeParams 와 같다. NoneParams 는 편집할 값이 없어 빈 목록이다.
     * 입력: params(템플릿 파라미터 — 값은 쓰지 않고 타입만 쓴다)
     * 출력: 없음
     * 리턴: Field 목록
     */
    fun fields(params: VadParams): List<Field> = when (params) { // 타입별 분기
        is SileroParams -> listOf(
            prob("threshold"), nonNegInt("minSpeechMs"), nonNegInt("minSilenceMs"), nonNegInt("speechPadMs"),
            Field(MAX_SPEECH_SEC, Kind.DOUBLE, 0.0, null), // 0 초과 또는 inf(특례는 parseDouble 에서 처리)
        ) // Silero / TEN-Silero
        is PyannoteParams -> listOf(prob("onset"), prob("offset"), nonNegDouble("minDurationOnSec"), nonNegDouble("minDurationOffSec")) // pyannote
        is FsmnParams -> listOf(
            nonNegInt("maxEndSilenceTime"), nonNegInt("maxStartSilenceTime"), nonNegInt("windowSizeMs"),
            nonNegInt("silToSpeechTimeThres"), nonNegInt("speechToSilTimeThres"),
            Field("speech2NoiseRatio", Kind.FLOAT, 0.0, null), nonNegInt("doExtend"), nonNegInt("lookbackTimeStartPoint"),
            nonNegInt("lookaheadTimeEndPoint"), nonNegInt("maxSingleSegmentTime"), freeFloat("snrThres"),
            freeFloat("decibelThres"), prob("speechNoiseThres"), prob("fePriorThres"),
            freeFloat("speechNoiseThreshLow"), freeFloat("speechNoiseThreshHigh"),
            nonNegInt("frameInMs"), nonNegInt("frameLengthMs"), nonNegInt("detectMode"), nonNegInt("snrMode"),
        ) // FSMN
        is WebRtcParams -> listOf(
            Field("mode", Kind.INT, 0.0, 3.0), // aggressiveness 0~3
            Field("frameMs", Kind.INT, null, null, listOf("10", "20", "30")), // 프레임 길이 허용 목록
            Field("style", Kind.ENUM, null, null, WebRtcStyle.entries.map { it.name }), // 후처리 방식
            nonNegInt("paddingMs"), prob("ratio"), nonNegInt("speechMs"), nonNegInt("silenceMs"),
        ) // WebRTC
        is HangoverParams -> listOf(prob("threshold"), nonNegInt("speechMs"), nonNegInt("silenceMs")) // 행오버(TEN·Yamnet)
        is FireRedParams -> listOf(
            Field("mode", Kind.ENUM, null, null, FireRedMode.entries.map { it.name }), // 후처리 모드
            prob("threshold"), nonNegInt("smoothWindow"), nonNegInt("minSpeechFrames"), nonNegInt("maxSpeechFrames"),
            nonNegInt("minSilenceFrames"), nonNegInt("mergeSilenceFrames"), nonNegInt("extendFrames"), nonNegInt("padStartFrames"),
        ) // FireRed
        is NemoParams -> listOf(
            prob("onset"), prob("offset"), nonNegDouble("padOnsetSec"), nonNegDouble("padOffsetSec"),
            nonNegDouble("minDurationOnSec"), nonNegDouble("minDurationOffSec"), Field("filterSpeechFirst", Kind.BOOL, null, null),
        ) // NeMo
        NoneParams -> emptyList() // 값 없음
    }

    /**
     * 목적: Double 의 안정된 문자열 표현
     * 기능: describeParams 와 같은 규칙(무한대 inf/-inf, 나머지 toString).
     * 입력: v
     * 출력: 없음
     * 리턴: 문자열
     */
    private fun num(v: Double): String = when { // 값 종류별 분기
        v == Double.POSITIVE_INFINITY -> "inf" // 양의 무한대
        v == Double.NEGATIVE_INFINITY -> "-inf" // 음의 무한대
        else -> v.toString() // 일반 값
    }

    /**
     * 목적: Float 의 안정된 문자열 표현
     * 기능: describeParams 와 같은 규칙. Double 로 넓히지 않아 0.9f → "0.9" 로 남는다.
     * 입력: v
     * 출력: 없음
     * 리턴: 문자열
     */
    private fun num(v: Float): String = when { // 값 종류별 분기
        v == Float.POSITIVE_INFINITY -> "inf" // 양의 무한대
        v == Float.NEGATIVE_INFINITY -> "-inf" // 음의 무한대
        else -> v.toString() // 일반 값
    }

    /**
     * 목적: 파라미터 → 문자열 map
     * 기능: fields() 순서대로 키·값을 담은 LinkedHashMap 을 만든다. 이어 붙이면 describeParams 문자열과 같다.
     * 입력: params
     * 출력: 없음
     * 리턴: 키 → 값 문자열 map(NoneParams 는 빈 map)
     */
    fun toMap(params: VadParams): Map<String, String> = when (params) { // 타입별 분기
        is SileroParams -> linkedMapOf(
            "threshold" to num(params.threshold), "minSpeechMs" to params.minSpeechMs.toString(), "minSilenceMs" to params.minSilenceMs.toString(),
            "speechPadMs" to params.speechPadMs.toString(), MAX_SPEECH_SEC to num(params.maxSpeechSec),
        )
        is PyannoteParams -> linkedMapOf(
            "onset" to num(params.onset), "offset" to num(params.offset),
            "minDurationOnSec" to num(params.minDurationOnSec), "minDurationOffSec" to num(params.minDurationOffSec),
        )
        is FsmnParams -> linkedMapOf(
            "maxEndSilenceTime" to params.maxEndSilenceTime.toString(), "maxStartSilenceTime" to params.maxStartSilenceTime.toString(),
            "windowSizeMs" to params.windowSizeMs.toString(), "silToSpeechTimeThres" to params.silToSpeechTimeThres.toString(),
            "speechToSilTimeThres" to params.speechToSilTimeThres.toString(), "speech2NoiseRatio" to num(params.speech2NoiseRatio),
            "doExtend" to params.doExtend.toString(), "lookbackTimeStartPoint" to params.lookbackTimeStartPoint.toString(),
            "lookaheadTimeEndPoint" to params.lookaheadTimeEndPoint.toString(), "maxSingleSegmentTime" to params.maxSingleSegmentTime.toString(),
            "snrThres" to num(params.snrThres), "decibelThres" to num(params.decibelThres), "speechNoiseThres" to num(params.speechNoiseThres),
            "fePriorThres" to num(params.fePriorThres), "speechNoiseThreshLow" to num(params.speechNoiseThreshLow),
            "speechNoiseThreshHigh" to num(params.speechNoiseThreshHigh), "frameInMs" to params.frameInMs.toString(),
            "frameLengthMs" to params.frameLengthMs.toString(), "detectMode" to params.detectMode.toString(), "snrMode" to params.snrMode.toString(),
        )
        is WebRtcParams -> linkedMapOf(
            "mode" to params.mode.toString(), "frameMs" to params.frameMs.toString(), "style" to params.style.name,
            "paddingMs" to params.paddingMs.toString(), "ratio" to num(params.ratio),
            "speechMs" to params.speechMs.toString(), "silenceMs" to params.silenceMs.toString(),
        )
        is HangoverParams -> linkedMapOf("threshold" to num(params.threshold), "speechMs" to params.speechMs.toString(), "silenceMs" to params.silenceMs.toString())
        is FireRedParams -> linkedMapOf(
            "mode" to params.mode.name, "threshold" to num(params.threshold), "smoothWindow" to params.smoothWindow.toString(),
            "minSpeechFrames" to params.minSpeechFrames.toString(), "maxSpeechFrames" to params.maxSpeechFrames.toString(),
            "minSilenceFrames" to params.minSilenceFrames.toString(), "mergeSilenceFrames" to params.mergeSilenceFrames.toString(),
            "extendFrames" to params.extendFrames.toString(), "padStartFrames" to params.padStartFrames.toString(),
        )
        is NemoParams -> linkedMapOf(
            "onset" to num(params.onset), "offset" to num(params.offset), "padOnsetSec" to num(params.padOnsetSec),
            "padOffsetSec" to num(params.padOffsetSec), "minDurationOnSec" to num(params.minDurationOnSec),
            "minDurationOffSec" to num(params.minDurationOffSec), "filterSpeechFirst" to params.filterSpeechFirst.toString(),
        )
        NoneParams -> linkedMapOf() // 값 없음
    }

    /**
     * 목적: 숫자를 사유 문구용으로 표기
     * 기능: 정수값이면 소수점 없이(0.0 → "0"), 아니면 toString.
     * 입력: v
     * 출력: 없음
     * 리턴: 문자열
     */
    private fun shown(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString() // 표기

    /**
     * 목적: 범위 위반 사유 문구 생성
     * 기능: 양끝이 있으면 "0~1 사이", 하한만 있으면 "0 이상", 상한만 있으면 "N 이하" 로 쓴다.
     * 입력: f(필드)
     * 출력: 없음
     * 리턴: "<키>: ..." 한국어 사유
     */
    private fun rangeMessage(f: Field): String = when { // 범위 종류별
        f.key == MAX_SPEECH_SEC -> "${f.key}: 0보다 큰 값 또는 inf 여야 합니다" // 특례
        f.min != null && f.max != null -> "${f.key}: ${shown(f.min)}~${shown(f.max)} 사이여야 합니다" // 양끝
        f.min != null -> "${f.key}: ${shown(f.min)} 이상이어야 합니다" // 하한
        f.max != null -> "${f.key}: ${shown(f.max)} 이하여야 합니다" // 상한
        else -> "${f.key}: 숫자여야 합니다" // 범위 없음(형식 위반)
    }

    /**
     * 목적: 필드 1개의 문자열 값을 검증·변환
     * 기능: 종류별로 형식을 확인하고 허용 목록·범위를 검사한 뒤 Int/Float/Double/Boolean/String 으로 돌려준다.
     * 입력: f(필드), raw(앞뒤 공백 제거된 문자열)
     * 출력: 없음
     * 리턴: 성공 시 값, 실패 시 한국어 사유(IllegalArgumentException)
     */
    private fun parseField(f: Field, raw: String): Result<Any> {
        fun fail(msg: String): Result<Any> = Result.failure(IllegalArgumentException(msg)) // 실패 도우미
        if (f.choices.isNotEmpty() && raw !in f.choices) return fail("${f.key}: ${f.choices.joinToString(", ")} 중 하나여야 합니다") // 허용 목록
        return when (f.kind) { // 종류별 분기
            Kind.ENUM -> Result.success(raw) // 허용 목록 검사 끝
            Kind.BOOL -> when (raw) { // 불리언
                "true" -> Result.success(true)
                "false" -> Result.success(false)
                else -> fail("${f.key}: true 또는 false 여야 합니다")
            }
            Kind.INT -> {
                if (!INT_REGEX.matches(raw)) return fail("${f.key}: 정수여야 합니다") // 소수·문자 거부
                val v = raw.toIntOrNull() ?: return fail("${f.key}: 정수여야 합니다") // Int 범위 밖 거부
                if ((f.min != null && v < f.min) || (f.max != null && v > f.max)) return fail(rangeMessage(f)) // 범위
                Result.success(v)
            }
            Kind.FLOAT, Kind.DOUBLE -> {
                if (f.key == MAX_SPEECH_SEC && raw == "inf") return Result.success(Double.POSITIVE_INFINITY) // 무제한 특례
                if (!NUMBER_REGEX.matches(raw)) return fail("${f.key}: 숫자여야 합니다") // NaN·접미사 거부
                val v: Double = if (f.kind == Kind.FLOAT) raw.toFloat().toDouble() else raw.toDouble() // 실제 저장 정밀도로 변환
                if (v.isNaN() || v.isInfinite()) return fail(rangeMessage(f)) // 넘침(overflow) 거부
                if ((f.min != null && v < f.min) || (f.max != null && v > f.max)) return fail(rangeMessage(f)) // 범위
                if (f.key == MAX_SPEECH_SEC && v <= 0.0) return fail(rangeMessage(f)) // 0 이하 거부
                Result.success(if (f.kind == Kind.FLOAT) raw.toFloat() else v) // Float 은 Float 로
            }
        }
    }

    /**
     * 목적: 문자열 map → 파라미터 복원
     * 기능: 템플릿 타입의 필드를 순서대로 검증한다. 키 누락·모르는 키·범위 위반이면 첫 사유로 실패한다. NONE 템플릿은 실패한다.
     * 입력: template(타입을 정하는 기준 파라미터), values(키 → 문자열)
     * 출력: 없음
     * 리턴: Result.success(새 파라미터) 또는 Result.failure(IllegalArgumentException(한국어 사유))
     */
    fun fromMap(template: VadParams, values: Map<String, String>): Result<VadParams> {
        val fs = fields(template) // 필드 명세
        if (fs.isEmpty()) return Result.failure(IllegalArgumentException("VAD 없음(NONE)은 바꿀 값이 없어 복사할 수 없습니다")) // NONE
        val parsed = HashMap<String, Any>() // 검증된 값
        for (f in fs) { // 필드마다
            val raw = values[f.key]?.trim() ?: return Result.failure(IllegalArgumentException("${f.key}: 값이 없습니다")) // 누락
            parsed[f.key] = parseField(f, raw).getOrElse { return Result.failure(it) } // 검증 실패 시 즉시 반환
        }
        val unknown = values.keys.firstOrNull { k -> fs.none { it.key == k } } // 모르는 키
        if (unknown != null) return Result.failure(IllegalArgumentException("$unknown: 알 수 없는 항목입니다")) // 거부
        fun i(k: String) = parsed.getValue(k) as Int // Int 값
        fun f(k: String) = parsed.getValue(k) as Float // Float 값
        fun d(k: String) = parsed.getValue(k) as Double // Double 값
        return Result.success(when (template) { // 타입별 조립
            is SileroParams -> SileroParams(f("threshold"), i("minSpeechMs"), i("minSilenceMs"), i("speechPadMs"), d(MAX_SPEECH_SEC))
            is PyannoteParams -> PyannoteParams(f("onset"), f("offset"), d("minDurationOnSec"), d("minDurationOffSec"))
            is FsmnParams -> FsmnParams(
                i("maxEndSilenceTime"), i("maxStartSilenceTime"), i("windowSizeMs"), i("silToSpeechTimeThres"), i("speechToSilTimeThres"),
                f("speech2NoiseRatio"), i("doExtend"), i("lookbackTimeStartPoint"), i("lookaheadTimeEndPoint"), i("maxSingleSegmentTime"),
                f("snrThres"), f("decibelThres"), f("speechNoiseThres"), f("fePriorThres"), f("speechNoiseThreshLow"), f("speechNoiseThreshHigh"),
                i("frameInMs"), i("frameLengthMs"), i("detectMode"), i("snrMode"),
            )
            is WebRtcParams -> WebRtcParams(
                i("mode"), i("frameMs"), WebRtcStyle.valueOf(parsed.getValue("style") as String), i("paddingMs"), f("ratio"), i("speechMs"), i("silenceMs"),
            )
            is HangoverParams -> HangoverParams(f("threshold"), i("speechMs"), i("silenceMs"))
            is FireRedParams -> FireRedParams(
                FireRedMode.valueOf(parsed.getValue("mode") as String), f("threshold"), i("smoothWindow"), i("minSpeechFrames"),
                i("maxSpeechFrames"), i("minSilenceFrames"), i("mergeSilenceFrames"), i("extendFrames"), i("padStartFrames"),
            )
            is NemoParams -> NemoParams(
                f("onset"), f("offset"), d("padOnsetSec"), d("padOffsetSec"), d("minDurationOnSec"), d("minDurationOffSec"),
                parsed.getValue("filterSpeechFirst") as Boolean,
            )
            NoneParams -> NoneParams // 위에서 걸러짐(when 완결성용)
        })
    }
}
