// 파일명: UserPresetStoreTest.kt
// 목적: USER VAD 프리셋 저장소(UserPresetStore)와 합본 조회(PresetRegistry) 검증
// 기능: 이름 규칙, 재생성 후 유지, 손상 파일 보존, NONE 기준 거부, 삭제, 값 검증 실패 시 미저장, 합본 조회를 확인한다.
package com.cowork.stt_ondevice.vad // VAD 테스트 패키지

import org.junit.Assert.assertEquals // 같음 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Rule // 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-20:08
/**
 * 목적: USER 프리셋 저장 규칙 회귀 방지
 * 기능: 임시 폴더의 user_presets.json 으로 저장소를 만들고 규칙을 확인한다.
 */
class UserPresetStoreTest {

    @get:Rule // 테스트마다 새 임시 폴더
    val tmp = TemporaryFolder()

    /** 목적: 테스트용 저장 파일 경로. 기능: 임시 폴더의 user_presets.json(아직 없음). 입력: 없음. 출력: 없음. 리턴: File */
    private fun file() = File(tmp.root, "user_presets.json") // 저장 파일

    /** 목적: 저장소 생성. 기능: 고정 시각을 쓰는 저장소를 만든다. 입력: f(저장 파일). 출력: 없음. 리턴: UserPresetStore */
    private fun store(f: File = file()) = UserPresetStore(f, now = { "2026-10-11T20:08:00+09:00" }) // 고정 시각

    /** 목적: SILERO 값 map. 기능: 기준 프리셋 SILERO 의 값 map 을 돌려준다. 입력: 없음. 출력: 없음. 리턴: Map */
    private fun sileroValues() = PresetParamCodec.toMap(VadPresets.byId("SILERO").params) // 기준 값

    /**
     * 목적: 이름 규칙 검증
     * 기능: 정상 이름·공백 제거·거부 이름·중복(대소문자 무시)을 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun nameRules() {
        val s = store() // 저장소
        val ok = s.create("SILERO", "조용한방", sileroValues()) // 정상
        assertTrue(ok.isSuccess) // 성공
        assertEquals("USER_조용한방", ok.getOrThrow().id) // id 접두
        assertEquals("USER(기준 SILERO)", ok.getOrThrow().source) // 출처
        assertEquals("", ok.getOrThrow().note) // 주의 문구 없음
        assertEquals(VadFamily.SILERO, ok.getOrThrow().family) // 계열 상속
        assertEquals("USER_조용", s.create("SILERO", " 조용 ", sileroValues()).getOrThrow().id) // 앞뒤 공백 제거
        for (bad in listOf("ㅋ", "ㅏ", "a b", "x".repeat(33), "", "   ", "가_나", "한글!")) { // 거부 이름
            val r = s.create("SILERO", bad, sileroValues()) // 시도
            assertTrue("'$bad' 는 거부되어야 한다", r.isFailure) // 실패
            assertTrue(r.exceptionOrNull() is IllegalArgumentException) // 예외 타입
            assertEquals("이름은 한글·영문·숫자·'-' 1~32자만 쓸 수 있습니다", r.exceptionOrNull()!!.message) // 사유
        }
        assertTrue(s.create("SILERO", "x".repeat(32), sileroValues()).isSuccess) // 32자 경계 허용
        assertTrue(s.create("SILERO", "Quiet-1", sileroValues()).isSuccess) // 영문·숫자·'-'
        val dup = s.create("SILERO", "Quiet-1", sileroValues()) // 같은 이름 두 번째
        assertEquals("이미 있는 이름입니다: USER_Quiet-1", dup.exceptionOrNull()!!.message) // 중복 사유
        assertTrue(s.create("SILERO", "quiet-1", sileroValues()).isFailure) // 대소문자만 다른 이름
        assertTrue(s.create("SILERO", "QUIET-1", sileroValues()).isFailure) // 대소문자만 다른 이름
        // 기본 프리셋 id 와 겹침: 모든 USER id 는 'USER_' 접두라 기본 id 와 같아질 수 없고, 저장소 목록에도 기본 id 가 없다
        val builtInIds = VadPresets.all.map { it.id }.toSet() // 기본 id
        assertTrue(s.list().none { it.id in builtInIds }) // 겹침 없음
        // 기본 id 가 USER_ 로 시작하는 경우를 주입해 대소문자 무시 중복 검사 자체를 확인
        val custom = UserPresetStore(file(), builtIns = { VadPresets.all + VadPreset("USER_Tricky", VadFamily.NONE, NoneParams, "시험") }, now = { "t" }) // 기본 id 주입
        assertEquals("이미 있는 이름입니다: USER_Tricky", custom.create("SILERO", "tricky", sileroValues()).exceptionOrNull()!!.message) // 기본 id 와 대소문자만 다름
    }

    /**
     * 목적: 재생성 후 유지 검증
     * 기능: 만든 프리셋이 새 인스턴스의 list() 에도 같은 params 로 나오는지 본다(TEN 두 타입 포함).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun persistsAcrossInstances() {
        val s = store() // 저장소
        val a = s.create("SILERO", "아침", sileroValues() + ("threshold" to "0.42")).getOrThrow() // Silero 복사
        val tenBase = VadPresets.byId("TEN_DEFAULT") // TEN(행오버)
        val b = s.create("TEN_DEFAULT", "텐", PresetParamCodec.toMap(tenBase.params) + ("silenceMs" to "120")).getOrThrow() // TEN 복사
        val c = s.create("TEN_SHERPA", "텐-실로", PresetParamCodec.toMap(VadPresets.byId("TEN_SHERPA").params)).getOrThrow() // TEN(Silero 후처리) 복사
        assertEquals(0.42f, (a.params as SileroParams).threshold, 0f) // 값 반영
        assertTrue(b.params is HangoverParams) // TEN 행오버 타입
        assertTrue(c.params is SileroParams) // TEN Silero 타입
        val listed = store().list() // 새 인스턴스
        assertEquals(listOf(a, b, c), listed) // 동일(순서 포함)
        assertNull(store().lastError) // 오류 없음
        assertEquals("2026-10-11T20:08:00+09:00", AppJsonRead.createdAt(file())) // 생성 시각 기록
    }

    /**
     * 목적: 손상 파일 보존 검증
     * 기능: 깨진 파일이면 list() 는 빈 목록 + lastError, create/delete 는 파일을 건드리지 않는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun corruptFileIsNotOverwritten() {
        val f = file() // 저장 파일
        f.writeText("{깨짐") // 손상 내용
        val s = store(f) // 저장소
        assertTrue(s.list().isEmpty()) // 빈 목록
        assertNotNull(s.lastError) // 오류 설정
        val r = s.create("SILERO", "새것", sileroValues()) // 저장 시도
        assertTrue(r.isFailure) // 실패
        assertEquals("user_presets.json 을 읽을 수 없어 저장하지 않습니다", r.exceptionOrNull()!!.message) // 사유
        assertFalse(s.delete("USER_새것")) // 삭제도 거부
        assertEquals("{깨짐", f.readText()) // 내용 그대로
        assertFalse(File(tmp.root, "user_presets.json.tmp").exists()) // 임시 파일 잔재 없음
    }

    /**
     * 목적: 파일 없음 처리 검증
     * 기능: 파일이 없으면 빈 목록·오류 없음, 첫 create 후 파일이 생기는지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun missingFileIsEmptyWithoutError() {
        val s = store() // 저장소
        assertTrue(s.list().isEmpty()) // 빈 목록
        assertNull(s.lastError) // 오류 없음
        assertFalse(file().exists()) // 읽기만으로 파일을 만들지 않음
        assertTrue(s.create("SILERO", "첫", sileroValues()).isSuccess) // 생성
        assertTrue(file().exists()) // 파일 생성
    }

    /**
     * 목적: NONE 기준 거부 검증
     * 기능: NONE 과 없는 기준 id 를 한국어 사유로 거부하고 아무것도 저장하지 않는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun noneBaseRejected() {
        val s = store() // 저장소
        val r = s.create("NONE", "빈것", emptyMap()) // NONE 기준
        assertEquals("VAD 없음(NONE)은 바꿀 값이 없어 복사할 수 없습니다", r.exceptionOrNull()!!.message) // 사유
        val r2 = s.create("NOPE", "없는기준", sileroValues()) // 없는 기준
        assertEquals("기준 프리셋을 찾을 수 없습니다: NOPE", r2.exceptionOrNull()!!.message) // 사유
        assertTrue(s.list().isEmpty()) // 저장 없음
        assertFalse(file().exists()) // 파일 없음
    }

    /**
     * 목적: 삭제 검증
     * 기능: 삭제하면 목록에서 사라지고 파일에도 반영되며, 없는 id 는 false 이다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun deleteRemoves() {
        val s = store() // 저장소
        s.create("SILERO", "하나", sileroValues()).getOrThrow() // 생성
        s.create("SILERO", "둘", sileroValues()).getOrThrow() // 생성
        assertTrue(s.delete("USER_하나")) // 삭제 성공
        assertEquals(listOf("USER_둘"), store().list().map { it.id }) // 파일에도 반영
        assertFalse(s.delete("USER_하나")) // 이미 없음
        assertFalse(s.delete("SILERO")) // 기본 프리셋은 지울 수 없음
        assertTrue(s.create("SILERO", "하나", sileroValues()).isSuccess) // 지운 이름은 다시 쓸 수 있음
    }

    /**
     * 목적: 값 검증 실패 시 미저장 검증
     * 기능: 범위 밖 값이면 create 가 한국어 사유로 실패하고 파일이 생기지 않는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun invalidValuesAreNotSaved() {
        val s = store() // 저장소
        val r = s.create("SILERO", "나쁜값", sileroValues() + ("threshold" to "1.2")) // 범위 밖
        assertEquals("threshold: 0~1 사이여야 합니다", r.exceptionOrNull()!!.message) // 사유
        assertFalse(file().exists()) // 저장 없음
    }

    /**
     * 목적: 복원 불가 항목 보존 검증
     * 기능: 기준 프리셋이 사라져 복원할 수 없는 항목은 list() 에서 빠지고 lastError 가 설정되지만, 이후 create/delete 가 그 항목을 파일에서 지우지 않는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun unrestorableEntryIsKept() {
        val f = file() // 저장 파일
        store(f).create("SILERO", "옛것", sileroValues()).getOrThrow() // 먼저 저장
        val noSilero = UserPresetStore(f, builtIns = { VadPresets.all.filter { it.id != "SILERO" } }, now = { "t" }) // 기준이 사라진 환경
        assertTrue(noSilero.list().isEmpty()) // 복원 불가라 목록에서 제외
        assertNotNull(noSilero.lastError) // 사유 기록
        assertTrue(noSilero.create("SILERO_FW", "새것", PresetParamCodec.toMap(VadPresets.byId("SILERO_FW").params)).isSuccess) // 다른 저장은 가능
        assertEquals(listOf("USER_옛것", "USER_새것"), AppJsonRead.ids(f)) // 파일에는 둘 다 남음
        assertTrue(store(f).list().any { it.id == "USER_옛것" }) // 기준이 돌아오면 다시 보임
    }

    /**
     * 목적: 합본 조회 검증
     * 기능: PresetRegistry.all() 이 기본 26종 뒤에 USER 를 붙이고 byId 가 둘 다 찾으며 없는 id 는 null 인지 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun registryMergesBuiltInAndUser() {
        val s = store() // 저장소
        val reg = PresetRegistry(s) // 합본
        assertEquals(VadPresets.all, reg.all()) // USER 없으면 기본 그대로
        val made = s.create("SILERO", "합본", sileroValues()).getOrThrow() // 생성
        assertEquals(VadPresets.all + made, reg.all()) // 기본 뒤에 USER
        assertEquals(made, reg.byId("USER_합본")) // USER 조회
        assertEquals(VadPresets.byId("SILERO"), reg.byId("SILERO")) // 기본 조회
        assertNull(reg.byId("USER_없음")) // 없는 id
    }
}

/**
 * 목적: 저장 파일 검사 도우미(테스트 전용)
 * 기능: 저장 JSON 에서 id 목록·첫 항목 createdAt 만 읽는다.
 */
private object AppJsonRead {
    /** 목적: id 목록 읽기. 기능: 파일의 UserPresetJson 배열에서 id 만 뽑는다. 입력: f(저장 파일). 출력: 없음. 리턴: id 목록 */
    fun ids(f: File): List<String> = parse(f).map { it.id } // id 만

    /** 목적: 첫 항목 createdAt 읽기. 기능: 첫 항목의 createdAt 을 돌려준다. 입력: f(저장 파일). 출력: 없음. 리턴: 문자열 */
    fun createdAt(f: File): String = parse(f).first().createdAt // 첫 시각

    /** 목적: 파일 파싱. 기능: 공용 Json 으로 배열을 읽는다. 입력: f(저장 파일). 출력: 없음. 리턴: UserPresetJson 목록 */
    private fun parse(f: File): List<UserPresetJson> =
        com.cowork.stt_ondevice.core.json.AppJson.decodeFromString(f.readText()) // 파싱
}
