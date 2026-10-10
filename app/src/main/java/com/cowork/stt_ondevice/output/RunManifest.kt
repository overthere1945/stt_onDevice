// 파일명: RunManifest.kt
// 목적: 실행 1회의 재현 정보(run_manifest.json) 스키마와 읽기·쓰기 도우미 정의 (스펙 §10~§11)
// 기능: 기기·앱 빌드·모델(hash/라이선스/런타임)·VAD 프리셋 값·UNSUPPORTED 목록·진행 중 항목을 JSON 으로 남긴다.
//       도메인 타입(DeviceInfo/ModelSpec/VadPreset)은 @Serializable 이 아니므로 미러 타입(DeviceInfoJson/ModelEntry/PresetEntry)과 from() 변환기를 둔다.
//       측정·확인하지 못한 값은 null (AppJson 의 explicitNulls 로 JSON 에 null 이 명시된다).
//       change(add)-hyungchul-2026-10-11-03:31 입력 파일 목록(inputs, InputEntry)도 남겨 결과 화면의 이어하기가 같은 입력을 복원할 수 있게 한다.
package com.cowork.stt_ondevice.output // 출력 패키지

import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.json.AppJson // 공용 Json
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.vad.FireRedParams // FireRed 파라미터
import com.cowork.stt_ondevice.vad.FsmnParams // FSMN 파라미터
import com.cowork.stt_ondevice.vad.HangoverParams // 행오버 파라미터
import com.cowork.stt_ondevice.vad.NemoParams // NeMo 파라미터
import com.cowork.stt_ondevice.vad.NoneParams // 없음 파라미터
import com.cowork.stt_ondevice.vad.PyannoteParams // pyannote 파라미터
import com.cowork.stt_ondevice.vad.SileroParams // Silero 파라미터
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadParams // 파라미터 상위 타입
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.VadPresets // 프리셋 목록(상업 판정)
import com.cowork.stt_ondevice.vad.WebRtcParams // WebRTC 파라미터
import kotlinx.serialization.Serializable // 직렬화 대상 표시
import kotlinx.serialization.SerializationException // 직렬화 오류
import java.io.File // 파일
import java.io.IOException // 입출력 오류
import java.nio.charset.StandardCharsets // 문자셋
import java.nio.file.Files // 파일 유틸
import java.nio.file.StandardCopyOption // 이동 옵션

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 기기 정보의 JSON 미러
 * 기능: DeviceInfo 의 필드를 그대로 담는다(socModel 은 알 수 없으면 null).
 * 입력: DeviceInfo 와 동일한 필드
 */
@Serializable
data class DeviceInfoJson(
    val manufacturer: String, // 제조사
    val model: String, // 모델명
    val socModel: String?, // SoC 모델, 알 수 없으면 null
    val androidRelease: String, // 안드로이드 버전
    val sdkInt: Int, // API 레벨
    val abi: String, // 기본 ABI
    val totalRamMb: Long, // 총 RAM(MB)
    val appVersion: String, // 앱 버전
) {
    companion object {
        /**
         * 목적: DeviceInfo → DeviceInfoJson 변환
         * 기능: 필드를 1:1 로 복사한다.
         * 입력: d(기기 정보)
         * 출력: 없음
         * 리턴: DeviceInfoJson
         */
        fun from(d: DeviceInfo): DeviceInfoJson = DeviceInfoJson(
            d.manufacturer, d.model, d.socModel, d.androidRelease, d.sdkInt, d.abi, d.totalRamMb, d.appVersion, // 1:1 복사
        )
    }
}

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: 모델 파일 1개의 검증 정보(JSON)
 * 기능: 재현성을 위해 경로·SHA-256·크기를 남긴다.
 * 입력: path(모델 폴더 기준 상대 경로), sha256(소문자 16진), bytes(크기)
 */
@Serializable
data class ModelFileEntry(
    val path: String, // 상대 경로
    val sha256: String, // SHA-256
    val bytes: Long, // 크기(바이트)
)

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: manifest 에 남기는 모델 1개의 정보
 * 기능: 결과 폴더 이름(algo/model), 런타임·정밀도, 실효 maxChunkSec, 파일 hash, 라이선스·상업 판정, PC 호환 별칭, 로드 시간·로드 오류를 담는다.
 * 입력: 각 필드(아래 주석)
 */
@Serializable
data class ModelEntry(
    val id: String, // 모델 id
    val algo: String, // 결과 폴더 algo
    val model: String, // 결과 폴더 model
    val runtime: String, // 런타임 이름(Runtime enum 이름)
    val runtimeVersion: String, // 런타임 버전
    val precision: String, // 가중치 정밀도
    val maxChunkSec: Double, // 실효 최대 chunk 길이(덮어쓰기 반영 후), 0 = 제한 없음
    val files: List<ModelFileEntry>, // 파일 hash 목록
    val license: String, // 라이선스 이름
    val licenseUrl: String, // 라이선스 근거 URL
    val commercial: String, // 상업 이용 판정(CommercialStatus 이름)
    val compatAlias: String?, // PC 평가기 폴더 별칭, 없으면 null
    // change(add)-hyungchul-2026-10-11-01:47: 모델 로드 결과(Task 24 리뷰 지적) — BatchRunner 가 prepare 후 갱신
    val loadSec: Double? = null, // 모델 로드 시간(초), 아직 로드 안 했거나 실패면 null
    val loadError: String? = null, // 로드 실패 시 "stage/code: message", 성공·미실행이면 null
) {
    companion object {
        /**
         * 목적: ModelSpec → ModelEntry 변환
         * 기능: 카탈로그 값을 복사하되 maxChunkSec 는 실효값을 쓴다.
         * 입력: spec(모델), effectiveMaxChunkSec(RunConfig.maxChunkFor 결과)
         * 출력: 없음
         * 리턴: ModelEntry
         */
        fun from(spec: ModelSpec, effectiveMaxChunkSec: Double): ModelEntry = ModelEntry(
            id = spec.id, algo = spec.algo, model = spec.model, // 식별
            runtime = spec.runtime.name, runtimeVersion = spec.runtimeVersion, precision = spec.precision, // 실행 환경
            maxChunkSec = effectiveMaxChunkSec, // 실효 최대 길이
            files = spec.files.map { ModelFileEntry(it.path, it.sha256, it.bytes) }, // 파일 hash
            license = spec.license, licenseUrl = spec.licenseUrl, commercial = spec.commercial.name, // 라이선스
            compatAlias = spec.compatAlias, // PC 별칭
        )
    }
}

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: manifest 에 남기는 VAD 프리셋 1개의 정보
 * 기능: 프리셋의 모든 파라미터를 사람이 읽을 수 있는 안정된 문자열로 남긴다.
 * 입력: id(프리셋 id), family(계열 이름), params("name=value," 나열, 무한대는 inf), source(출처), note(주의 문구), commercial(상업 판정 이름)
 */
@Serializable
data class PresetEntry(
    val id: String, // 프리셋 id
    val family: String, // VAD 계열 이름
    val params: String, // 파라미터 문자열
    val source: String, // 값의 출처
    val note: String, // 사용상 주의 문구
    val commercial: String, // 상업 이용 판정 이름
) {
    companion object {
        /**
         * 목적: VadPreset → PresetEntry 변환
         * 기능: 파라미터를 describeParams 로 문자열화하고 계열의 상업 판정을 붙인다.
         * 입력: p(프리셋)
         * 출력: 없음
         * 리턴: PresetEntry
         */
        fun from(p: VadPreset): PresetEntry = PresetEntry(
            id = p.id, family = p.family.name, params = describeParams(p.params), // 식별·계열·파라미터
            source = p.source, note = p.note, commercial = VadPresets.commercialOf(p.family).name, // 출처·주의·상업
        )

        /**
         * 목적: 숫자의 안정된 문자열 표현
         * 기능: 무한대는 "inf"/"-inf", 나머지는 Kotlin 기본 toString (예: 0.5, 20.0).
         * 입력: v(Double)
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
         * 기능: 무한대는 inf, 나머지는 Float.toString (0.9f → "0.9"; Double 로 넓히지 않아 0.8999999 같은 오차 표기를 피한다).
         * 입력: v(Float)
         * 출력: 없음
         * 리턴: 문자열
         */
        private fun num(v: Float): String = when { // 값 종류별 분기
            v == Float.POSITIVE_INFINITY -> "inf" // 양의 무한대
            v == Float.NEGATIVE_INFINITY -> "-inf" // 음의 무한대
            else -> v.toString() // 일반 값
        }

        /**
         * 목적: 프리셋 파라미터 전체를 한 줄 문자열로 표현
         * 기능: 필드를 선언 순서대로 "이름=값" 쉼표 연결한다(기본값 필드 포함). NoneParams 는 빈 문자열.
         *       새 VadParams 구현체를 추가하면 when 이 exhaustive 하지 않아 컴파일 오류로 알려준다.
         * 입력: p(파라미터)
         * 출력: 없음
         * 리턴: 문자열
         */
        fun describeParams(p: VadParams): String = when (p) { // 계열별 분기
            is SileroParams -> "threshold=${num(p.threshold)},minSpeechMs=${p.minSpeechMs},minSilenceMs=${p.minSilenceMs}," +
                "speechPadMs=${p.speechPadMs},maxSpeechSec=${num(p.maxSpeechSec)}" // Silero
            is PyannoteParams -> "onset=${num(p.onset)},offset=${num(p.offset)},minDurationOnSec=${num(p.minDurationOnSec)}," +
                "minDurationOffSec=${num(p.minDurationOffSec)}" // pyannote
            is FsmnParams -> "maxEndSilenceTime=${p.maxEndSilenceTime},maxStartSilenceTime=${p.maxStartSilenceTime},windowSizeMs=${p.windowSizeMs}," +
                "silToSpeechTimeThres=${p.silToSpeechTimeThres},speechToSilTimeThres=${p.speechToSilTimeThres}," +
                "speech2NoiseRatio=${num(p.speech2NoiseRatio)},doExtend=${p.doExtend},lookbackTimeStartPoint=${p.lookbackTimeStartPoint}," +
                "lookaheadTimeEndPoint=${p.lookaheadTimeEndPoint},maxSingleSegmentTime=${p.maxSingleSegmentTime},snrThres=${num(p.snrThres)}," +
                "decibelThres=${num(p.decibelThres)},speechNoiseThres=${num(p.speechNoiseThres)},fePriorThres=${num(p.fePriorThres)}," +
                "speechNoiseThreshLow=${num(p.speechNoiseThreshLow)},speechNoiseThreshHigh=${num(p.speechNoiseThreshHigh)}," +
                "frameInMs=${p.frameInMs},frameLengthMs=${p.frameLengthMs},detectMode=${p.detectMode},snrMode=${p.snrMode}" // FSMN
            is WebRtcParams -> "mode=${p.mode},frameMs=${p.frameMs},style=${p.style.name},paddingMs=${p.paddingMs}," +
                "ratio=${num(p.ratio)},speechMs=${p.speechMs},silenceMs=${p.silenceMs}" // WebRTC
            is HangoverParams -> "threshold=${num(p.threshold)},speechMs=${p.speechMs},silenceMs=${p.silenceMs}" // 행오버
            is FireRedParams -> "mode=${p.mode.name},threshold=${num(p.threshold)},smoothWindow=${p.smoothWindow},minSpeechFrames=${p.minSpeechFrames}," +
                "maxSpeechFrames=${p.maxSpeechFrames},minSilenceFrames=${p.minSilenceFrames},mergeSilenceFrames=${p.mergeSilenceFrames}," +
                "extendFrames=${p.extendFrames},padStartFrames=${p.padStartFrames}" // FireRed
            is NemoParams -> "onset=${num(p.onset)},offset=${num(p.offset)},padOnsetSec=${num(p.padOnsetSec)},padOffsetSec=${num(p.padOffsetSec)}," +
                "minDurationOnSec=${num(p.minDurationOnSec)},minDurationOffSec=${num(p.minDurationOffSec)},filterSpeechFirst=${p.filterSpeechFirst}" // NeMo
            NoneParams -> "" // 값 없음
        }
    }
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: manifest 에 기록하는 입력 파일 1건
 * 기능: 입력 루트(inputRoot) 기준 위치를 언어 폴더·상대 폴더·파일명(확장자 포함)으로 남긴다. 이어하기 때 inputRoot/<lang>/<relDir>/<file> 로 되찾는다.
 * 입력: lang(언어 폴더명 kr/en), relDir(상대 폴더, '/' 구분, 최상위면 빈 문자열), file(파일명)
 */
@Serializable
data class InputEntry(
    val lang: String, // 언어 폴더명
    val relDir: String, // 상대 폴더
    val file: String, // 파일명(확장자 포함)
) {
    companion object {
        /**
         * 목적: 입력 항목 → 기록 항목 변환
         * 기능: InputItem 의 lang·relDir·파일명을 옮긴다.
         * 입력: item(입력 항목)
         * 출력: 없음
         * 리턴: InputEntry
         */
        fun from(item: InputItem): InputEntry = InputEntry(item.lang, item.relDir, item.file.name) // 값 복사
    }
}

// change(add)-hyungchul-2026-10-10-22:05
/**
 * 목적: run_manifest.json 전체 스키마
 * 기능: 실행 시작 시 쓰고, 진행 중(inFlight)·종료(finishedAt) 때 다시 쓴다. 크래시 판정과 재현성 확인에 쓴다.
 * 입력: 각 필드(아래 주석). 기본값이 있는 필드는 이전 버전 파일과의 호환용이다.
 */
@Serializable
data class RunManifest(
    val schemaVersion: Int = 1, // 스키마 버전
    val runId: String, // 실행 ID
    val appVersion: String, // 앱 버전("versionName (versionCode)")
    val startedAt: String, // 실행 시작 시각(ISO8601)
    val device: DeviceInfoJson, // 기기 정보
    val models: List<ModelEntry>, // 선택 모델(실행 순서)
    val presets: List<PresetEntry>, // 선택 VAD 프리셋
    val chunkMode: String, // chunk 묶기 방식 이름
    val maxChunkOverride: Map<String, Double>, // 모델 id → 덮어쓴 maxChunkSec
    val threads: Int, // 추론 스레드 수
    val resume: Boolean, // 이어하기 실행 여부
    val inputRoot: String, // 입력 폴더 루트 경로
    val unsupported: List<String>, // 미지원 조합 "combo|lang"
    // change(add)-hyungchul-2026-10-11-02:14: 진행 단계 표시 3종(R33)
    //   ① 항목 처리 중      "combo|lang|relDir|stem"  (inFlightKey)
    //   ② 모델 로드 중      "<modelId>|MODEL_LOAD"    (modelLoadKey) — engineFactory+prepare 직전 기록, 성공/처리된 실패 후 해제
    //   ③ VAD 엔진 생성 중  "<VadFamily 이름>|VAD_INIT" (vadInitKey)  — 계열 엔진 생성 직전 기록, 생성 후 해제
    //   재개 시 남아 있으면 그 단계에서 프로세스가 죽은 것으로 보고 ResumeReconciler 가 CRASH_OR_KILLED 로 정리한다.
    val inFlight: String? = null, // 진행 단계 표시(위 3종), 없으면 null
    val finishedAt: String? = null, // 실행 종료 시각, 끝나지 않았으면 null
    // change(add)-hyungchul-2026-10-11-03:31: 실행에 넣은 입력 파일 목록(설정 순서) — 결과 화면의 "이어하기"가 같은 입력으로 RunConfig 를 복원하는 데 쓴다.
    //   이 필드가 없던 이전 파일은 null(입력 목록을 알 수 없어 화면 이어하기 불가 — 다른 입력으로 대체하지 않음).
    val inputs: List<InputEntry>? = null, // 입력 항목 목록, 기록 안 된 이전 파일이면 null
) {
    /**
     * 목적: manifest 원자적 저장
     * 기능: 같은 폴더의 임시 파일에 쓴 뒤 ATOMIC_MOVE 로 교체한다(쓰는 도중 크래시해도 기존 파일이 손상되지 않음). 상위 폴더가 없으면 만든다.
     * 입력: file(저장할 run_manifest.json)
     * 출력: file 생성/교체, 임시 파일은 남기지 않는다. 쓰기 실패 시 예외
     * 리턴: Unit
     */
    fun write(file: File) {
        val dir = file.absoluteFile.parentFile!! // 저장 폴더
        dir.mkdirs() // 폴더 보장
        val tmp = File(dir, file.name + ".tmp") // 같은 폴더의 임시 파일(원자적 이동 조건)
        try {
            Files.write(tmp.toPath(), AppJson.encodeToString(serializer(), this).toByteArray(StandardCharsets.UTF_8)) // 임시 파일에 기록
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE) // 원자적 교체
        } finally {
            tmp.delete() // 실패 시 남은 임시 파일 정리(성공 시엔 이미 없음)
        }
    }

    companion object {
        /**
         * 목적: manifest 읽기
         * 기능: 파일이 없거나, 비었거나, JSON/스키마가 맞지 않거나, 읽기 오류면 null 을 돌려준다(예외로 이어하기 판정을 막지 않음).
         * 입력: file(run_manifest.json)
         * 출력: 없음
         * 리턴: RunManifest 또는 null
         */
        fun read(file: File): RunManifest? {
            if (!file.isFile) return null // 파일 없음
            return try {
                AppJson.decodeFromString(serializer(), file.readText(StandardCharsets.UTF_8)) // 파싱
            } catch (e: SerializationException) {
                null // 손상·스키마 불일치
            } catch (e: IllegalArgumentException) {
                null // 잘못된 입력
            } catch (e: IOException) {
                null // 읽기 실패
            }
        }

        /**
         * 목적: 진행 중 항목 키 생성
         * 기능: "combo|lang|relDir|stem" 형식(relDir 가 비면 빈 칸).
         * 입력: combo(조합명), item(입력 항목)
         * 출력: 없음
         * 리턴: 키 문자열
         */
        fun inFlightKey(combo: String, item: InputItem): String = "$combo|${item.lang}|${item.relDir}|${item.stem}" // 파이프 연결

        // change(add)-hyungchul-2026-10-11-02:14
        const val MODEL_LOAD_MARK = "MODEL_LOAD" // 모델 로드 단계 표시 접미사
        // change(add)-hyungchul-2026-10-11-02:14
        const val VAD_INIT_MARK = "VAD_INIT" // VAD 엔진 생성 단계 표시 접미사

        // change(add)-hyungchul-2026-10-11-02:14
        /**
         * 목적: 모델 로드 단계 키 생성
         * 기능: "<modelId>|MODEL_LOAD"
         * 입력: modelId(모델 id)
         * 출력: 없음
         * 리턴: 키 문자열
         */
        fun modelLoadKey(modelId: String): String = "$modelId|$MODEL_LOAD_MARK" // 모델 id + 표시

        // change(add)-hyungchul-2026-10-11-02:14
        /**
         * 목적: VAD 엔진 생성 단계 키 생성
         * 기능: "<VadFamily 이름>|VAD_INIT"
         * 입력: family(VAD 계열)
         * 출력: 없음
         * 리턴: 키 문자열
         */
        fun vadInitKey(family: VadFamily): String = "${family.name}|$VAD_INIT_MARK" // 계열 이름 + 표시
    }
}
