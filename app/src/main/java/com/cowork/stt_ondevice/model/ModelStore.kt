// 파일명: ModelStore.kt
// 목적: 설치된 모델 폴더(models/<id>)의 상태 조회·사용 중 표시·삭제·원자적 교체
// 기능: state() 는 `.ready.json` 존재와 선언 파일들의 존재·크기만 보고 판정한다(해시는 계산하지 않음 — 해시 검증은 ModelInstaller.verify()).
//       acquire/release 는 모델별 사용 횟수(스레드 안전)이고, 사용 중이면 delete 와 교체(commit)를 거부한다.
//       change(add)-hyungchul-2026-10-11-12:51 acquireAll: 여러 모델을 한꺼번에 사용 중으로 표시하고 close 한 번으로 해제하는 핸들(배치 실행 전체 동안 선택 모델 보호, 최종 리뷰 I-4).
package com.cowork.stt_ondevice.model // 모델 패키지

import com.cowork.stt_ondevice.core.json.AppJson // 공용 JSON
import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import kotlinx.serialization.Serializable // 직렬화 대상
import kotlinx.serialization.encodeToString // JSON 쓰기
import java.io.File // 파일
import java.nio.file.Files // 파일 도구
import java.nio.file.StandardCopyOption // 이동 옵션
import java.time.Instant // 시각

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 모델 설치 상태
 * 기능: NOT_INSTALLED(표식 없음) / DOWNLOADING(받는 중) / VERIFYING(검증·해제 중) / READY(사용 가능) / CORRUPT(표식은 있으나 파일 누락·크기 불일치).
 */
enum class InstallState { NOT_INSTALLED, DOWNLOADING, VERIFYING, READY, CORRUPT }

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: `.ready.json` 내용
 * 기능: 설치가 끝났고 모든 파일이 검증됐다는 표식이다.
 * 입력: id(모델 id), verifiedAt(검증 시각, ISO-8601 UTC), files(검증한 파일 수)
 */
@Serializable
data class ReadyMarker(val id: String, val verifiedAt: String, val files: Int) // 표식 필드

// change(add)-hyungchul-2026-10-10-20:18
/**
 * 목적: 모델 저장소
 * 기능: 모델 폴더 위치 계산, 상태 판정, 사용 횟수 관리, 삭제, 검증된 임시 폴더를 models/<id> 로 교체.
 * 입력: dirs(앱 폴더)
 */
class ModelStore(private val dirs: AppDirs) {
    companion object {
        const val READY_FILE = ".ready.json" // 설치 완료 표식 파일 이름
    }

    private val lock = Any() // acquire/release/delete/commit 직렬화용 잠금
    private val useCount = HashMap<String, Int>() // 모델 id → 사용 횟수 (lock 으로 보호)
    private val transient = java.util.concurrent.ConcurrentHashMap<String, InstallState>() // 설치 진행 중 상태 (id → DOWNLOADING/VERIFYING)

    /**
     * 목적: 모델 폴더 위치
     * 기능: models/<id>
     * 입력: spec
     * 출력: 없음
     * 리턴: 폴더(File, 생성하지 않음)
     */
    fun modelDir(spec: ModelSpec): File = File(dirs.models, spec.id) // models/<id>

    /**
     * 목적: 설치 상태 판정
     * 기능: 설치 진행 중이면 그 상태, 아니면 표식·파일 존재·크기로 판정한다(해시 계산 없음). 표식이 없으면 NOT_INSTALLED,
     *       표식이 깨졌거나 id·파일 수가 다르거나 선언 파일이 없거나 크기가 다르면 CORRUPT, 모두 맞으면 READY.
     * 입력: spec
     * 출력: 없음
     * 리턴: InstallState
     */
    fun state(spec: ModelSpec): InstallState {
        transient[spec.id]?.let { return it } // 설치 진행 중 상태가 우선
        val dir = modelDir(spec) // 모델 폴더
        val marker = File(dir, READY_FILE) // 표식 파일
        if (!marker.isFile) return InstallState.NOT_INSTALLED // 표식 없음
        val parsed = try {
            AppJson.decodeFromString<ReadyMarker>(marker.readText()) // 표식 읽기
        } catch (e: Exception) {
            return InstallState.CORRUPT // 깨진 표식
        }
        if (parsed.id != spec.id || parsed.files != spec.files.size) return InstallState.CORRUPT // 카탈로그와 다름
        for (f in spec.files) { // 선언 파일마다
            val file = File(dir, f.path) // 실제 파일
            if (!file.isFile || file.length() != f.bytes) return InstallState.CORRUPT // 없음/크기 다름
        }
        return InstallState.READY // 사용 가능
    }

    /**
     * 목적: 모델 사용 시작 표시
     * 기능: 사용 횟수를 1 올린다.
     * 입력: spec
     * 출력: 없음
     * 리턴: 없음
     */
    fun acquire(spec: ModelSpec) {
        synchronized(lock) { useCount[spec.id] = (useCount[spec.id] ?: 0) + 1 } // 횟수 증가
    }

    /**
     * 목적: 모델 사용 종료 표시
     * 기능: 사용 횟수를 1 내린다(0 이면 항목 제거).
     * 입력: spec
     * 출력: acquire 없이 release 하면 IllegalStateException(호출 오류를 숨기지 않음)
     * 리턴: 없음
     */
    fun release(spec: ModelSpec) {
        synchronized(lock) {
            val n = useCount[spec.id] ?: 0 // 현재 횟수
            check(n > 0) { "acquire 없이 release 했습니다: ${spec.id}" } // 오류 방지
            if (n == 1) useCount.remove(spec.id) else useCount[spec.id] = n - 1 // 감소
        }
    }

    /**
     * 목적: 사용 횟수 조회
     * 기능: 현재 acquire 횟수를 돌려준다.
     * 입력: spec
     * 출력: 없음
     * 리턴: 횟수(0 이상)
     */
    fun useCountOf(spec: ModelSpec): Int = synchronized(lock) { useCount[spec.id] ?: 0 } // 횟수

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 여러 모델 한꺼번에 사용 시작 표시
     * 기능: specs 의 서로 다른 모델(id 기준 중복 제거)마다 acquire 를 한 번씩 하고, close 를 처음 부를 때 같은 모델들을 한 번씩 release 하는 핸들을 돌려준다
     *       (두 번째 이후 close 는 아무것도 하지 않음). 배치 실행 동안 아직 로드하지 않은 선택 모델도 삭제·교체되지 않게 할 때 쓴다.
     * 입력: specs(사용할 모델 목록, 중복 가능)
     * 출력: 없음
     * 리턴: AutoCloseable(close = 해제)
     */
    fun acquireAll(specs: List<ModelSpec>): AutoCloseable {
        val held = specs.distinctBy { it.id } // 모델마다 한 번
        held.forEach { acquire(it) } // 사용 시작 표시
        val closed = java.util.concurrent.atomic.AtomicBoolean(false) // 해제 여부(한 번만)
        return AutoCloseable { if (closed.compareAndSet(false, true)) held.forEach { release(it) } } // 처음 close 때만 해제
    }

    /**
     * 목적: 모델 삭제
     * 기능: 사용 중(횟수 > 0)이면 아무것도 하지 않고 false. 아니면 models/<id> 를 통째로 지운다.
     * 입력: spec
     * 출력: 없음
     * 리턴: 삭제 성공(폴더가 없어도 true) 여부, 사용 중이거나 지우지 못하면 false
     */
    fun delete(spec: ModelSpec): Boolean = synchronized(lock) {
        if ((useCount[spec.id] ?: 0) > 0) return@synchronized false // 사용 중 → 거부
        val dir = modelDir(spec) // 모델 폴더
        if (!dir.exists()) return@synchronized true // 이미 없음
        dir.deleteRecursively() && !dir.exists() // 삭제 결과
    }

    /**
     * 목적: 설치 진행 상태 표시(설치기 전용)
     * 기능: DOWNLOADING/VERIFYING 을 기록하거나(null 이면 해제) state() 가 그 값을 우선 돌려주게 한다.
     * 입력: spec, state(진행 상태 또는 null)
     * 출력: 없음
     * 리턴: 없음
     */
    internal fun setTransient(spec: ModelSpec, state: InstallState?) {
        if (state == null) transient.remove(spec.id) else transient[spec.id] = state // 기록/해제
    }

    /**
     * 목적: `.ready.json` 쓰기(설치기 전용)
     * 기능: 검증이 끝난 폴더 안에 표식을 쓴다(커밋 전에 써 두면 rename 한 번으로 READY 가 된다).
     * 입력: spec, dir(검증이 끝난 폴더)
     * 출력: 쓰기 실패는 IOException
     * 리턴: 없음
     */
    internal fun writeMarker(spec: ModelSpec, dir: File) {
        val json = AppJson.encodeToString(ReadyMarker(spec.id, Instant.now().toString(), spec.files.size)) // 표식 JSON
        File(dir, READY_FILE).writeText(json) // 파일로 저장
    }

    /**
     * 목적: 검증된 임시 폴더를 models/<id> 로 교체(설치기 전용)
     * 기능: 잠금 안에서 사용 중 여부를 확인하고, 기존 models/<id> 가 있으면 지운 뒤 staged 를 원자적 rename 한다.
     * 입력: spec, staged(검증·표식 기록이 끝난 폴더, models 와 같은 파일시스템)
     * 출력: 사용 중이면 IllegalStateException, rename 실패는 IOException
     * 리턴: 없음
     */
    internal fun commit(spec: ModelSpec, staged: File) = synchronized(lock) {
        check((useCount[spec.id] ?: 0) == 0) { "사용 중인 모델은 교체할 수 없습니다: ${spec.id}" } // 사용 중 거부
        val target = modelDir(spec) // 최종 위치
        dirs.models.mkdirs() // models 폴더 보장
        if (target.exists() && !target.deleteRecursively()) throw java.io.IOException("기존 모델 폴더를 지울 수 없습니다: $target") // 낡은 폴더 제거
        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) // 원자적 이동
    }

    /**
     * 목적: 사용 중 여부 확인(설치기 전용)
     * 기능: 설치를 시작하기 전에 교체 가능한지 미리 본다.
     * 입력: spec
     * 출력: 사용 중이면 IllegalStateException
     * 리턴: 없음
     */
    internal fun requireNotInUse(spec: ModelSpec) {
        check(useCountOf(spec) == 0) { "사용 중인 모델은 교체할 수 없습니다: ${spec.id}" } // 사용 중 거부
    }
}
