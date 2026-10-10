// 파일명: VadCache.kt
// 목적: (VAD 프리셋, 음원) 별 VAD 결과를 한 실행(run) 안에서 한 번만 계산하고 재사용
// 기능: OutputLayout.vadSegments 경로에 {presetId, audioSha1, segments, frameHopSec, vadSec} JSON 을 원자적으로 저장하고,
//       파일이 있고 sha 가 맞으면 재사용한다. 방금 계산한 결과(frameScores 포함)는 메모리 1칸에 보관한다.
package com.cowork.stt_ondevice.vad // VAD 패키지

import com.cowork.stt_ondevice.core.json.AppJson // 공용 JSON
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 음원 모델
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import kotlinx.serialization.SerialName // JSON 필드명 지정
import kotlinx.serialization.Serializable // 직렬화 대상
import java.io.File // 파일
import java.nio.file.Files // 원자적 이동
import java.nio.file.StandardCopyOption // 이동 옵션

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: VadCache 호출 결과
 * 기능: VAD 출력과 측정 시간, 재사용 여부를 함께 돌려준다.
 *       output.frameScores 는 이번 호출에서 계산했거나 같은 키의 직전 계산 결과가 메모리에 남아 있을 때만 있고, 디스크에서 읽은 경우 null 이다.
 * 입력: output(VAD 출력), vadSec(compute 실측 시간 초; 재사용이면 최초 계산 때 저장된 값), reused(true 면 이번 호출에서 compute 를 실행하지 않음)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class CachedVad(
    val output: VadOutput, // VAD 출력
    val vadSec: Double, // VAD 계산 시간(초, wall-clock)
    val reused: Boolean, // 기존 결과 재사용 여부
)

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: 디스크 저장 형식(구간 1개)
 * 기능: start/end 초 한 쌍.
 */
@Serializable
private data class StoredSegment(
    @SerialName("start") val start: Double, // 시작(초)
    @SerialName("end") val end: Double, // 끝(초)
)

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: 디스크 저장 형식(VAD 결과 파일)
 * 기능: 프레임 점수는 저장하지 않는다(크기). 점수가 없는 VAD 는 frameHopSec 이 null.
 */
@Serializable
private data class StoredVad(
    @SerialName("presetId") val presetId: String, // 프리셋 id
    @SerialName("audioSha1") val audioSha1: String, // 음원 sha1
    @SerialName("segments") val segments: List<StoredSegment>, // 구간 목록
    @SerialName("frameHopSec") val frameHopSec: Double?, // 프레임 간격(초), 없으면 null
    @SerialName("vadSec") val vadSec: Double, // VAD 계산 시간(초)
)

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: VAD 결과 캐시
 * 기능: 같은 (프리셋, 음원 sha1) 는 compute 를 한 번만 실행해 모든 STT 엔진이 동일 구간을 쓰게 한다.
 *       메모리 캐시는 마지막 계산 1건((presetId, sha1) 키)이며 ChunkPlanner 가 계산 직후 frameScores 를 쓸 수 있게 하는 용도다.
 *       동시 호출 시 같은 키의 중복 계산을 막지는 않는다(배치 러너는 순차 호출).
 * 입력: runDir(output_stt/<run_id>)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class VadCache(private val runDir: File) {

    private class Entry(val presetId: String, val sha1: String, val output: VadOutput) // 메모리 1칸 항목

    @Volatile
    private var last: Entry? = null // 마지막 계산 결과(점수 포함)

    /**
     * 목적: 캐시 조회 또는 계산
     * 기능: 파일이 있고 sha 가 일치하면 재사용(메모리 항목이 같은 키면 frameScores 포함), 아니면 compute 를 실행·시간 측정 후 원자적으로 저장한다.
     *       읽을 수 없는(깨진) 파일은 신뢰하지 않고 재계산해 덮어쓴다. compute 가 예외를 던지면 그대로 전파하며 파일을 만들지 않는다.
     * 입력: preset(VAD 프리셋), item(입력 음원), audioSha1(음원 sha1), compute(VAD 실행 람다)
     * 출력: compute·파일 쓰기 예외 전파
     * 리턴: CachedVad(output, vadSec, reused)
     */
    fun getOrCompute(preset: VadPreset, item: InputItem, audioSha1: String, compute: () -> VadOutput): CachedVad {
        val file = OutputLayout.vadSegments(runDir, preset.id, item) // 캐시 파일 경로
        val stored = readStored(file) // 기존 파일(없거나 깨지면 null)
        if (stored != null && stored.presetId == preset.id && stored.audioSha1 == audioSha1) { // 같은 프리셋·같은 음원이면 재사용
            val mem = last // 메모리 항목
            val output = if (mem != null && mem.presetId == preset.id && mem.sha1 == audioSha1) mem.output // 점수가 남아 있으면 사용
            else VadOutput(stored.segments.map { SpeechSegment(it.start, it.end) }, stored.frameHopSec, null) // 디스크 복원(점수 없음)
            return CachedVad(output, stored.vadSec, true) // 저장된 vadSec 그대로
        }
        val t0 = System.nanoTime() // 측정 시작
        val output = compute() // VAD 실행
        val vadSec = (System.nanoTime() - t0) / 1_000_000_000.0 // 초 단위 wall-clock
        val data = StoredVad( // 저장 형식 변환
            preset.id, audioSha1, output.segments.map { StoredSegment(it.startSec, it.endSec) }, output.frameHopSec, vadSec,
        )
        writeAtomic(file, AppJson.encodeToString(StoredVad.serializer(), data)) // 임시 파일 + 이름 바꾸기
        last = Entry(preset.id, audioSha1, output) // 메모리 1칸 갱신(점수 포함)
        return CachedVad(output, vadSec, false) // 새로 계산함
    }

    /**
     * 목적: 기존 캐시 파일 읽기
     * 기능: 파일이 없거나 JSON 이 깨졌으면 null(재계산 유도).
     * 입력: file(캐시 파일)
     * 출력: 없음
     * 리턴: StoredVad 또는 null
     */
    private fun readStored(file: File): StoredVad? {
        if (!file.isFile) return null // 없음
        return try {
            AppJson.decodeFromString(StoredVad.serializer(), file.readText()) // 파싱
        } catch (e: kotlinx.serialization.SerializationException) { // 깨진 JSON/필드 누락
            null // 신뢰하지 않음
        } catch (e: IllegalArgumentException) { // 잘못된 값
            null // 신뢰하지 않음
        }
    }

    /**
     * 목적: 파일 원자적 쓰기
     * 기능: 같은 폴더의 임시 파일에 쓰고 ATOMIC_MOVE 로 교체해 중간 상태 파일이 남지 않게 한다. 실패하면 임시 파일을 지운다.
     * 입력: file(최종 경로), text(내용)
     * 출력: IO 예외 전파
     * 리턴: 없음
     */
    private fun writeAtomic(file: File, text: String) {
        val dir = file.parentFile!! // 부모 폴더
        dir.mkdirs() // 폴더 생성
        val tmp = File(dir, file.name + ".tmp") // 임시 파일
        try {
            tmp.writeText(text) // 임시 파일에 쓰기
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) // 원자적 교체
        } finally {
            tmp.delete() // 실패 시 잔재 제거(성공 시 이미 없음)
        }
    }
}
