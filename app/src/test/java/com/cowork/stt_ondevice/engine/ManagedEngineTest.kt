// 파일명: ManagedEngineTest.kt
// 목적: ManagedEngine(엔진 위임 래퍼)과 ModelStore 사용 횟수를 묶는 엔진 생성기(ManagedEngine.factory)의 동작 검증 (Ruling R5)
// 기능: - close: delegate.close → onClose 순서, onClose 정확히 1회, 두 번째 close 는 아무것도 하지 않음, delegate.close 가 던져도 onClose 실행 후 원래 예외 전파
//       - 위임: spec/supports/prepare/transcribe/cancel 이 원본 엔진으로 그대로 전달됨
//       - factory: 엔진 생성 실패·모델 미설치 시 사용 횟수가 0 으로 돌아옴(누수 없음), 성공 시 close 할 때까지 사용 중(삭제 거부)이고 close 후 0
//       change(add)-hyungchul-2026-10-11-19:18 factory 에 세 번째 인자(systemCreate)가 생겨 끝 람다가 그쪽에 붙지 않도록 생성기를 이름 인자(create = ...)로 넘긴다(2차 작업 6).
// change(add)-hyungchul-2026-10-11-02:40
package com.cowork.stt_ondevice.engine // 엔진 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더(임시 루트)
import com.cowork.stt_ondevice.model.ModelFile // 모델 파일 항목
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소(사용 횟수)
import com.cowork.stt_ondevice.model.testSpec // 테스트용 ModelSpec 생성
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.cowork.stt_ondevice.segment.SourceSpan // 원본 구간 매핑
import com.cowork.stt_ondevice.segment.SplitMethod // 분할 방식
import kotlinx.coroutines.test.runTest // 코루틴 테스트 실행기
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertSame // 동일 객체 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assert.fail // 실패 처리
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-02:40
/**
 * 목적: 호출 기록용 가짜 엔진
 * 기능: 모든 호출을 공유 기록(log)에 남기고, closeError 가 있으면 close 에서 그 예외를 던진다.
 * 입력: spec(모델 메타), log(호출 기록 목록), closeError(close 에서 던질 예외 또는 null)
 */
private class RecordingEngine(
    override val spec: ModelSpec, // 모델 메타
    private val log: MutableList<String>, // 호출 기록
    private val closeError: Exception? = null, // close 에서 던질 예외
) : AsrEngine {

    /**
     * 목적: 언어 지원 여부(기록)
     * 기능: 호출을 기록하고 "xx" 만 지원한다고 답한다(기본 구현과 다르게 해 위임 여부를 구분).
     * 입력: lang(언어 코드)
     * 출력: 기록 추가
     * 리턴: lang == "xx"
     */
    override fun supports(lang: String): Boolean {
        log += "supports:$lang" // 기록
        return lang == "xx" // 기본 구현과 다른 답
    }

    /**
     * 목적: 가짜 준비(기록)
     * 기능: 호출을 기록하고 고정 PrepareInfo 를 돌려준다.
     * 입력: threads(스레드 수)
     * 출력: 기록 추가
     * 리턴: PrepareInfo(0.25, "rec", CPU, f16, threads)
     */
    override suspend fun prepare(threads: Int): PrepareInfo {
        log += "prepare:$threads" // 기록
        return PrepareInfo(0.25, "rec", "CPU", "f16", threads) // 고정 값
    }

    /**
     * 목적: 가짜 전사(기록)
     * 기능: 호출을 기록하고 "t<index>-<lang>" 텍스트를 돌려준다.
     * 입력: chunk(오디오 조각), lang(언어)
     * 출력: 기록 추가
     * 리턴: ChunkText
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        log += "transcribe:${chunk.index}:$lang" // 기록
        return ChunkText("t${chunk.index}-$lang", "raw", null, 0.5) // 고정 값
    }

    /**
     * 목적: 가짜 취소(기록)
     * 기능: 호출을 기록한다.
     * 입력: 없음
     * 출력: 기록 추가
     * 리턴: 없음
     */
    override fun cancel() { log += "cancel" } // 기록

    /**
     * 목적: 가짜 해제(기록)
     * 기능: 호출을 기록하고 closeError 가 있으면 던진다.
     * 입력: 없음
     * 출력: 기록 추가, closeError 가 있으면 그 예외
     * 리턴: 없음
     */
    override fun close() {
        log += "delegate.close" // 기록
        closeError?.let { throw it } // 설정된 예외 던지기
    }
}

// change(add)-hyungchul-2026-10-11-02:40
/**
 * 목적: ManagedEngine·factory 회귀 방지
 * 기능: 파일 머리말의 항목을 확인한다.
 */
class ManagedEngineTest {

    @get:Rule
    val tmp = TemporaryFolder() // 임시 폴더(앱 루트 대용)

    private val spec = testSpec("rec-model", emptyList(), listOf(ModelFile("m.bin", 4, "00", "model"))) // 4바이트 파일 1개짜리 테스트 스펙

    /**
     * 목적: close 순서와 1회 호출 확인
     * 기능: close 하면 delegate.close 다음에 onClose 가 정확히 1번 불린다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun closeCallsDelegateThenOnCloseOnce() {
        val log = mutableListOf<String>() // 호출 기록
        val e = ManagedEngine(RecordingEngine(spec, log)) { log += "onClose" } // 래퍼
        e.close() // 닫기
        assertEquals(listOf("delegate.close", "onClose"), log) // 순서·횟수
    }

    /**
     * 목적: close 멱등성 확인
     * 기능: 두 번 close 해도 delegate.close·onClose 는 각각 1번뿐이다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun closeIsIdempotent() {
        val log = mutableListOf<String>() // 호출 기록
        val e = ManagedEngine(RecordingEngine(spec, log)) { log += "onClose" } // 래퍼
        e.close() // 첫 번째
        e.close() // 두 번째(무시돼야 함)
        assertEquals(listOf("delegate.close", "onClose"), log) // 각 1회
    }

    /**
     * 목적: delegate.close 예외 경로 확인
     * 기능: delegate.close 가 던져도 onClose 가 1번 불리고 원래 예외가 그대로 전파되며, 다시 close 해도 아무 일 없다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun onCloseRunsWhenDelegateCloseThrows() {
        val log = mutableListOf<String>() // 호출 기록
        val boom = IllegalStateException("native close 실패") // 원본 엔진이 던질 예외
        val e = ManagedEngine(RecordingEngine(spec, log, boom)) { log += "onClose" } // 래퍼
        try {
            e.close() // 닫기(예외 기대)
            fail("close 예외가 전파되지 않음") // 여기 오면 실패
        } catch (t: IllegalStateException) {
            assertSame(boom, t) // 같은 예외 객체
        }
        assertEquals(listOf("delegate.close", "onClose"), log) // onClose 도 실행됨
        e.close() // 두 번째 close(예외·호출 없어야 함)
        assertEquals(listOf("delegate.close", "onClose"), log) // 변화 없음
    }

    /**
     * 목적: onClose 도 실패할 때 원래 예외 보존 확인
     * 기능: delegate.close·onClose 가 모두 던지면 delegate 예외가 전파되고 onClose 예외는 suppressed 로 붙는다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun onCloseFailureIsSuppressedUnderDelegateFailure() {
        val log = mutableListOf<String>() // 호출 기록
        val boom = IllegalStateException("native close 실패") // 원본 엔진 예외
        val release = IllegalStateException("release 실패") // onClose 예외
        val e = ManagedEngine(RecordingEngine(spec, log, boom)) { throw release } // 래퍼
        try {
            e.close() // 닫기(예외 기대)
            fail("close 예외가 전파되지 않음") // 여기 오면 실패
        } catch (t: IllegalStateException) {
            assertSame(boom, t) // 원래 예외가 우선
            assertTrue(t.suppressed.contains(release)) // onClose 예외는 suppressed
        }
    }

    /**
     * 목적: 위임 확인
     * 기능: spec·supports·prepare·transcribe·cancel 이 원본 엔진 결과·기록과 같다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun delegatesAllCalls() = runTest {
        val log = mutableListOf<String>() // 호출 기록
        val inner = RecordingEngine(spec, log) // 원본 엔진
        val e = ManagedEngine(inner) { log += "onClose" } // 래퍼
        assertSame(spec, e.spec) // spec 위임
        assertTrue(e.supports("xx")) // 원본의 supports 사용(기본 구현이면 false)
        assertFalse(e.supports("ko")) // 원본의 supports 사용(기본 구현이면 true)
        assertEquals(3, e.prepare(3).threads) // prepare 위임
        val chunk = AudioChunk(7, FloatArray(16), listOf(SourceSpan(0.0, 0.001, 0.0)), SplitMethod.NONE) // 1 ms chunk
        assertEquals("t7-ko", e.transcribe(chunk, "ko").text) // transcribe 위임
        e.cancel() // cancel 위임
        assertEquals(listOf("supports:xx", "supports:ko", "prepare:3", "transcribe:7:ko", "cancel"), log) // 순서대로 전달됨
    }

    /**
     * 목적: 테스트용 READY 설치 만들기
     * 기능: models/<id>/m.bin(4바이트)과 .ready.json 을 쓴다.
     * 입력: store(모델 저장소)
     * 출력: 파일 생성
     * 리턴: 없음
     */
    private fun installReady(store: ModelStore) {
        val dir = store.modelDir(spec) // 모델 폴더
        dir.mkdirs() // 폴더 생성
        File(dir, "m.bin").writeBytes(ByteArray(4)) // 선언 크기와 같은 파일
        store.writeMarker(spec, dir) // 설치 완료 표식
    }

    /**
     * 목적: 생성 실패 시 사용 횟수 누수 없음 확인
     * 기능: READY 모델인데 create 가 던지면 같은 예외가 전파되고 사용 횟수는 0 이다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun factoryReleasesWhenCreateThrows() {
        val store = ModelStore(AppDirs(tmp.root)) // 실제 저장소
        installReady(store) // READY 설치
        val boom = EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "가짜 생성 실패") // 생성 예외
        val factory = ManagedEngine.factory(store, create = { _, _ -> throw boom }) // 항상 실패하는 생성기 // change(add)-hyungchul-2026-10-11-19:18
        try {
            factory(spec) // 생성(예외 기대)
            fail("생성 예외가 전파되지 않음") // 여기 오면 실패
        } catch (t: EngineException) {
            assertSame(boom, t) // 같은 예외
        }
        assertEquals(0, store.useCountOf(spec)) // 누수 없음
    }

    /**
     * 목적: 미설치 모델 거부 확인
     * 기능: READY 가 아니면 create 를 부르지 않고 EngineException(MODEL_LOAD, MODEL_NOT_READY) 를 던지며 사용 횟수는 0 이다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun factoryRejectsModelNotReady() {
        val store = ModelStore(AppDirs(tmp.root)) // 실제 저장소(설치 없음)
        var created = false // create 호출 여부
        val factory = ManagedEngine.factory(store, create = { s, _ -> created = true; RecordingEngine(s, mutableListOf()) }) // 호출 기록 생성기 // change(add)-hyungchul-2026-10-11-19:18
        try {
            factory(spec) // 생성(예외 기대)
            fail("미설치 모델이 거부되지 않음") // 여기 오면 실패
        } catch (t: EngineException) {
            assertEquals("MODEL_LOAD", t.stage) // 단계
            assertEquals("MODEL_NOT_READY", t.code) // 코드
        }
        assertFalse(created) // 생성기 호출 안 됨
        assertEquals(0, store.useCountOf(spec)) // 누수 없음
    }

    /**
     * 목적: 성공 경로의 사용 횟수 확인
     * 기능: 생성기에 models/<id> 폴더가 전달되고, close 전에는 사용 횟수 1(삭제 거부), close 후 0(삭제 가능)이다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun factoryHoldsModelUntilClose() {
        val store = ModelStore(AppDirs(tmp.root)) // 실제 저장소
        installReady(store) // READY 설치
        var gotDir: File? = null // 생성기에 전달된 폴더
        val factory = ManagedEngine.factory(store, create = { s, dir -> gotDir = dir; RecordingEngine(s, mutableListOf()) }) // 기록 생성기 // change(add)-hyungchul-2026-10-11-19:18
        val e = factory(spec) // 생성
        assertEquals(store.modelDir(spec), gotDir) // 모델 폴더 전달
        assertEquals(1, store.useCountOf(spec)) // 사용 중
        assertFalse(store.delete(spec)) // 사용 중 삭제 거부
        e.close() // 닫기 → release
        e.close() // 두 번째 close 가 release 를 또 부르면 IllegalStateException
        assertEquals(0, store.useCountOf(spec)) // 사용 종료
        assertTrue(store.delete(spec)) // 이제 삭제 가능
    }
}
