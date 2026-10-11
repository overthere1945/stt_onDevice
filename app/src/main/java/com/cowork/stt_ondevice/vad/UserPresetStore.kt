// 파일명: UserPresetStore.kt
// 목적: 사용자가 만든 VAD 프리셋(USER_<이름>)의 파일 저장소
// 기능: 기본 프리셋을 복사해 값을 바꾼 프리셋을 user_presets.json 에 원자적으로 저장·조회·삭제한다.
//       이름 규칙·중복·기준 프리셋·값 범위를 검증하고, 손상된 파일은 덮어쓰지 않는다. 저장된 항목은 수정하지 않는다(수정 API 없음).
package com.cowork.stt_ondevice.vad // VAD 패키지

import com.cowork.stt_ondevice.core.json.AppJson // 공용 Json
import com.cowork.stt_ondevice.output.AtomicFile // 원자적 쓰기
import kotlinx.serialization.Serializable // 직렬화 표시
import java.io.File // 파일
import java.io.IOException // 입출력 예외

// change(add)-hyungchul-2026-10-11-20:08
/**
 * 목적: 저장 파일의 항목 1건
 * 기능: 복원에 필요한 최소 정보만 남긴다(파라미터는 검증된 문자열 map 으로 보관하고 읽을 때 다시 검증한다).
 * 입력: id(USER_<이름>), family(VadFamily 이름), baseId(복사한 기본 프리셋 id), values(키 → 값 문자열), createdAt(생성 시각)
 */
@Serializable
data class UserPresetJson(
    val id: String, // 프리셋 id
    val family: String, // 계열 이름(VadFamily.name)
    val baseId: String, // 기준 프리셋 id
    val values: Map<String, String>, // 파라미터 값(문자열)
    val createdAt: String, // 생성 시각
)

/**
 * 목적: USER 프리셋 저장소
 * 기능: 매 호출마다 파일을 읽어 항상 디스크 내용을 기준으로 한다. 쓰기는 AtomicFile 로 전체 파일을 교체한다.
 *       복원할 수 없는 항목(기준 프리셋이 사라짐 등)은 목록에서만 빼고 파일에는 그대로 남긴다(조용한 삭제 금지).
 * 입력: file(저장 파일 — 앱 files 폴더의 user_presets.json), builtIns(기본 프리셋 공급), now(생성 시각 문자열 공급)
 */
class UserPresetStore(
    private val file: File, // 저장 파일
    private val builtIns: () -> List<VadPreset> = { VadPresets.all }, // 기본 프리셋 공급
    private val now: () -> String, // 시각 공급
) {
    /** 목적: 마지막 읽기 오류. 기능: 가장 최근 읽기가 성공이면 null, 실패(손상·복원 불가 항목)면 한국어 사유. */
    @Volatile
    var lastError: String? = null // 마지막 읽기 오류
        private set

    /** 목적: 이름 정규식. 기능: 한글 완성형(가-힣)·영문·숫자·'-' 1~32자. */
    private val nameRegex = Regex("^[가-힣A-Za-z0-9-]{1,32}$")

    /**
     * 목적: 저장 파일 읽기
     * 기능: 파일이 없으면 빈 목록, 읽기·파싱 실패면 실패를 돌려주고 lastError 를 갱신한다.
     * 입력: 없음
     * 출력: lastError 갱신
     * 리턴: 항목 목록 Result
     */
    private fun readRaw(): Result<List<UserPresetJson>> {
        if (!file.exists()) { // 파일 없음 = 아직 저장한 적 없음
            lastError = null
            return Result.success(emptyList())
        }
        return try {
            val items = AppJson.decodeFromString<List<UserPresetJson>>(file.readText(Charsets.UTF_8)) // 파싱
            lastError = null // 성공
            Result.success(items)
        } catch (e: Exception) { // 손상·읽기 실패
            lastError = "${file.name} 을 읽을 수 없습니다: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}" // 사유
            Result.failure(e)
        }
    }

    /**
     * 목적: 저장 항목 → 프리셋 복원
     * 기능: 기준 프리셋을 찾아 템플릿으로 삼고 값을 다시 검증한다. 계열이 어긋나거나 검증 실패면 실패.
     * 입력: j(저장 항목)
     * 출력: 없음
     * 리턴: 성공 시 VadPreset, 실패 시 한국어 사유 Result
     */
    private fun restore(j: UserPresetJson): Result<VadPreset> {
        val base = builtIns().firstOrNull { it.id == j.baseId } // 기준 프리셋
            ?: return Result.failure(IllegalArgumentException("${j.id}: 기준 프리셋 ${j.baseId} 를 찾을 수 없습니다"))
        if (base.family.name != j.family) return Result.failure(IllegalArgumentException("${j.id}: 기준 프리셋의 계열이 저장 내용과 다릅니다")) // 계열 불일치
        val params = PresetParamCodec.fromMap(base.params, j.values).getOrElse { // 값 재검증
            return Result.failure(IllegalArgumentException("${j.id}: ${it.message}"))
        }
        return Result.success(VadPreset(j.id, base.family, params, "USER(기준 ${j.baseId})", "")) // 프리셋 조립
    }

    /**
     * 목적: USER 프리셋 목록
     * 기능: 저장 순서대로 복원한다. 파일 없음 → 빈 목록. 손상 → 빈 목록 + lastError(파일은 건드리지 않음).
     *       복원 불가 항목은 빠지고 lastError 에 사유가 남는다.
     * 입력: 없음
     * 출력: lastError 갱신
     * 리턴: VadPreset 목록
     */
    @Synchronized
    fun list(): List<VadPreset> {
        val raw = readRaw().getOrElse { return emptyList() } // 손상이면 빈 목록
        val out = ArrayList<VadPreset>() // 복원 결과
        val problems = ArrayList<String>() // 복원 불가 사유
        for (j in raw) { // 항목마다
            val r = restore(j) // 복원
            if (r.isSuccess) out.add(r.getOrThrow()) else problems.add(r.exceptionOrNull()?.message ?: j.id) // 성공/실패 분기
        }
        if (problems.isNotEmpty()) lastError = problems.joinToString("; ") // 일부 복원 불가 사유 기록
        return out
    }

    /**
     * 목적: USER 프리셋 생성·저장
     * 기능: 이름(공백 제거 후 규칙)·기준 프리셋(NONE 거부)·값 범위·중복(대소문자 무시, 기본 id 포함)을 검사하고 통과하면 즉시 원자적으로 저장한다.
     *       파일이 손상되어 읽을 수 없으면 저장하지 않는다. 저장되는 값은 검증된 파라미터를 정규 표기로 바꾼 것이다.
     * 입력: baseId(복사할 기본 프리셋 id), name(사용자 이름), values(키 → 값 문자열)
     * 출력: user_presets.json 갱신
     * 리턴: Result.success(만들어진 VadPreset) 또는 Result.failure(IllegalArgumentException(한국어 사유))
     */
    @Synchronized
    fun create(baseId: String, name: String, values: Map<String, String>): Result<VadPreset> {
        fun fail(msg: String): Result<VadPreset> = Result.failure(IllegalArgumentException(msg)) // 실패 도우미
        val trimmed = name.trim() // 앞뒤 공백 제거
        if (!nameRegex.matches(trimmed)) return fail("이름은 한글·영문·숫자·'-' 1~32자만 쓸 수 있습니다") // 이름 규칙
        val id = "USER_$trimmed" // 프리셋 id
        val builtInList = builtIns() // 기본 프리셋
        val base = builtInList.firstOrNull { it.id == baseId } ?: return fail("기준 프리셋을 찾을 수 없습니다: $baseId") // 기준 조회
        if (base.family == VadFamily.NONE) return fail("VAD 없음(NONE)은 바꿀 값이 없어 복사할 수 없습니다") // NONE 거부
        val params = PresetParamCodec.fromMap(base.params, values).getOrElse { return fail(it.message ?: "값이 올바르지 않습니다") } // 값 검증
        val existing = readRaw().getOrElse { return fail("${file.name} 을 읽을 수 없어 저장하지 않습니다") } // 손상이면 덮어쓰지 않음
        val taken = (builtInList.map { it.id } + existing.map { it.id }).map { it.lowercase() }.toSet() // 이미 쓰는 id(소문자)
        if (id.lowercase() in taken) { // 대소문자 무시 중복
            val same = (builtInList.map { it.id } + existing.map { it.id }).first { it.lowercase() == id.lowercase() } // 실제 겹친 id
            return fail("이미 있는 이름입니다: $same") // 중복 사유
        }
        val entry = UserPresetJson(id, base.family.name, baseId, PresetParamCodec.toMap(params), now()) // 저장 항목
        try {
            AtomicFile.write(file, AppJson.encodeToString(existing + entry).toByteArray(Charsets.UTF_8)) // 원자적 저장
        } catch (e: IOException) {
            return fail("${file.name} 을 저장하지 못했습니다: ${e.message}") // 쓰기 실패
        }
        return Result.success(VadPreset(id, base.family, params, "USER(기준 $baseId)", "")) // 새 프리셋
    }

    /**
     * 목적: USER 프리셋 삭제
     * 기능: 정확히 같은 id 의 항목을 파일에서 지운다. 파일을 읽을 수 없거나 해당 id 가 없으면 아무것도 하지 않는다.
     * 입력: id(USER_...)
     * 출력: user_presets.json 갱신
     * 리턴: 삭제했으면 true
     */
    @Synchronized
    fun delete(id: String): Boolean {
        val existing = readRaw().getOrElse { return false } // 손상이면 건드리지 않음
        if (existing.none { it.id == id }) return false // 없는 id
        return try {
            AtomicFile.write(file, AppJson.encodeToString(existing.filter { it.id != id }).toByteArray(Charsets.UTF_8)) // 원자적 저장
            true
        } catch (e: IOException) {
            lastError = "${file.name} 을 저장하지 못했습니다: ${e.message}" // 쓰기 실패 기록
            false
        }
    }
}
