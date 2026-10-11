// 파일명: ManagedEngine.kt
// 목적: 엔진 해제(close) 시점에 모델 사용 표시(ModelStore acquire/release)를 함께 정리하는 위임 래퍼 (Ruling R5)
// 기능: - ManagedEngine: 모든 AsrEngine 호출을 원본 엔진에 그대로 넘기고, close 는 정확히 1번만 delegate.close → onClose 순으로 실행한다
//         (delegate.close 가 던져도 onClose 를 부른 뒤 원래 예외를 다시 던짐, onClose 예외는 suppressed 로 붙음).
//       - ManagedEngine.factory: BatchRunner 의 engineFactory 로 쓰는 생성기. acquire → READY 확인 → EngineFactory.create → 래핑,
//         중간에 실패하면 release 후 예외를 그대로 던져 사용 횟수가 새지 않게 한다.
//       AsrEngine 계약 자체는 바꾸지 않는다.
//       change(add)-hyungchul-2026-10-11-19:18 factory 에 systemCreate(시스템 엔진 생성기, 기본 = NOT_IMPLEMENTED 로 거부) 추가 — 시스템 spec(isSystem)은 모델 폴더 없이 systemCreate 로 만든다(2차 작업 6).
// change(add)-hyungchul-2026-10-11-02:40
package com.cowork.stt_ondevice.engine // 엔진 패키지

import com.cowork.stt_ondevice.model.InstallState // 설치 상태
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소(사용 횟수)
import com.cowork.stt_ondevice.model.isSystem // 시스템 엔진 여부 // change(add)-hyungchul-2026-10-11-19:18
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import java.io.File // 파일
import java.util.concurrent.atomic.AtomicBoolean // 1회 close 보장용 원자 플래그

// change(add)-hyungchul-2026-10-11-02:40
/**
 * 목적: close 훅이 붙은 엔진 위임 래퍼
 * 기능: spec/supports/prepare/transcribe/cancel 은 delegate 에 그대로 넘긴다. close 는 여러 번 불려도(다른 스레드 포함) 처음 한 번만
 *       delegate.close() 후 onClose() 를 실행한다.
 * 입력: delegate(실제 엔진), onClose(delegate.close 직후 1번 실행할 정리 작업 — 보통 ModelStore.release)
 */
class ManagedEngine(
    private val delegate: AsrEngine, // 실제 엔진
    private val onClose: () -> Unit, // 해제 후 정리 작업
) : AsrEngine {

    private val closed = AtomicBoolean(false) // close 실행 여부(한 번만)

    /** 원본 엔진의 모델 메타데이터. */
    override val spec: ModelSpec
        get() = delegate.spec // 위임

    /**
     * 목적: 언어 지원 여부(위임)
     * 기능: 원본 엔진의 판단을 그대로 쓴다(원본이 기본 구현을 재정의했을 수 있음).
     * 입력: lang(언어 코드)
     * 출력: 없음
     * 리턴: 지원하면 true
     */
    override fun supports(lang: String): Boolean = delegate.supports(lang) // 위임

    /**
     * 목적: 모델 로드(위임)
     * 기능: delegate.prepare 를 그대로 호출한다.
     * 입력: threads(스레드 수)
     * 출력: 원본 엔진이 던지는 예외 그대로
     * 리턴: PrepareInfo
     */
    override suspend fun prepare(threads: Int): PrepareInfo = delegate.prepare(threads) // 위임

    /**
     * 목적: chunk 전사(위임)
     * 기능: delegate.transcribe 를 그대로 호출한다.
     * 입력: chunk(오디오 조각), lang(언어 코드)
     * 출력: 원본 엔진이 던지는 예외 그대로
     * 리턴: ChunkText
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText = delegate.transcribe(chunk, lang) // 위임

    /**
     * 목적: 진행 중 전사 중단(위임)
     * 기능: delegate.cancel 을 그대로 호출한다(다른 스레드에서 불러도 됨 — 원본 계약).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() = delegate.cancel() // 위임

    /**
     * 목적: 엔진 해제와 정리 작업(1회)
     * 기능: 처음 호출에서만 delegate.close() → onClose() 를 실행한다. delegate.close 가 던지면 onClose 를 실행한 뒤 그 예외를 다시 던지고,
     *       그때 onClose 도 던지면 그 예외는 원래 예외의 suppressed 로 붙인다. 두 번째 이후 호출은 아무것도 하지 않는다.
     * 입력: 없음
     * 출력: delegate.close 예외(있으면), delegate.close 가 정상이고 onClose 가 던지면 그 예외
     * 리턴: 없음
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return // 이미 닫힘 → 무시
        try {
            delegate.close() // 원본 엔진 해제
        } catch (t: Throwable) {
            try {
                onClose() // 해제 실패여도 정리 작업은 실행(사용 횟수 누수 방지)
            } catch (t2: Throwable) {
                t.addSuppressed(t2) // 정리 실패는 원래 예외에 붙임
            }
            throw t // 원래 예외 전파
        }
        onClose() // 정상 해제 후 정리 작업
    }

    companion object {
        /**
         * 목적: ModelStore 사용 표시를 묶은 엔진 생성기 만들기(BatchRunner.engineFactory 용)
         * 기능: 돌려준 함수는 모델마다 store.acquire → READY 확인(아니면 EngineException(MODEL_LOAD, MODEL_NOT_READY)) →
         *       create(spec, models/<id>) → ManagedEngine(onClose = store.release) 순으로 엔진을 만든다.
         *       acquire 를 먼저 해서 확인 이후 삭제·교체가 끼어들지 못하게 하고, 확인·생성이 실패하면 release 후 같은 예외를 던진다.
         *       change(add)-hyungchul-2026-10-11-19:18 시스템 spec(spec.isSystem, 항상 READY)은 create 대신 systemCreate(spec) 로 만든다(모델 폴더 없음). systemCreate 를 주지 않으면
         *       EngineException(MODEL_LOAD, NOT_IMPLEMENTED) 으로 거부한다(일반 생성기·다른 엔진으로 대체하지 않음).
         * 입력: store(모델 저장소), create(스펙·모델 폴더 → 엔진, 기본 EngineFactory::create — 테스트에서 가짜로 교체),
         *       systemCreate(시스템 spec → 엔진, 앱: SystemEngines.create, 기본은 NOT_IMPLEMENTED 로 거부) // change(add)-hyungchul-2026-10-11-19:18
         * 출력: 없음
         * 리턴: (ModelSpec) -> AsrEngine 생성 함수
         */
        fun factory(
            store: ModelStore, // 모델 저장소
            create: (ModelSpec, File) -> AsrEngine = EngineFactory::create, // 실제 엔진 생성기
            systemCreate: (ModelSpec) -> AsrEngine = { throw EngineException("MODEL_LOAD", "NOT_IMPLEMENTED", "시스템 엔진 생성기가 없음: ${it.id}") }, // 시스템 엔진 생성기 // change(add)-hyungchul-2026-10-11-19:18
        ): (ModelSpec) -> AsrEngine = { spec -> // 모델마다 호출
            store.acquire(spec) // 사용 시작 표시(삭제·교체 차단)
            try {
                val state = store.state(spec) // 설치 상태(표식·파일 크기, 시스템 spec 은 항상 READY)
                if (state != InstallState.READY) { // 미설치·손상·설치 중
                    throw EngineException("MODEL_LOAD", "MODEL_NOT_READY", "모델이 사용 가능 상태가 아님(${state.name}): ${spec.id}") // 다른 모델로 대체하지 않음
                }
                val engine = if (spec.isSystem) systemCreate(spec) else create(spec, store.modelDir(spec)) // 시스템 엔진은 전용 생성기 // change(add)-hyungchul-2026-10-11-19:18
                ManagedEngine(engine) { store.release(spec) } // 생성 후 래핑(close 때 release)
            } catch (t: Throwable) {
                store.release(spec) // 실패 시 사용 표시 해제(누수 방지)
                throw t // 같은 예외 전파
            }
        }
    }
}
