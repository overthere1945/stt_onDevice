// 파일명: VadCache.kt
// 목적: (VAD 프리셋, 음원) 별 VAD 결과를 한 실행(run) 안에서 한 번만 계산하고 재사용
// 기능: OutputLayout.vadSegments 경로에 {presetId, audioSha1, segments, frameHopSec, vadSec} JSON 을 원자적으로 저장하고,
//       파일이 있고 sha 가 맞으면 재사용한다. 프레임 점수는 JSON 옆 <stem>.scores.f32 (little-endian float32)로 함께 저장·복원한다.
package com.cowork.stt_ondevice.vad // VAD 패키지

import com.cowork.stt_ondevice.core.json.AppJson // 공용 JSON
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 음원 모델
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import kotlinx.serialization.SerialName // JSON 필드명 지정
import kotlinx.serialization.Serializable // 직렬화 대상
import java.io.File // 파일
import java.nio.ByteBuffer // 바이트 버퍼
import java.nio.ByteOrder // 바이트 순서
import java.nio.file.Files // 원자적 이동
import java.nio.file.StandardCopyOption // 이동 옵션

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: VadCache 호출 결과
 * 기능: VAD 출력과 측정 시간, 재사용 여부를 함께 돌려준다.
 *       output.frameScores 는 계산한 결과든 디스크에서 복원한 결과든 원본과 비트 단위로 같다(원래 null 인 VAD 는 null).
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
 * 기능: 프레임 점수 본체는 별도 파일(scoresFile, little-endian float32 원시 배열)에 두고 여기에는 파일명과 개수만 기록한다.
 *       점수가 없는 VAD 는 frameHopSec·scoresFile 이 null, scoresCount 는 0.
 */
@Serializable
private data class StoredVad(
    @SerialName("presetId") val presetId: String, // 프리셋 id
    @SerialName("audioSha1") val audioSha1: String, // 음원 sha1
    @SerialName("segments") val segments: List<StoredSegment>, // 구간 목록
    @SerialName("frameHopSec") val frameHopSec: Double?, // 프레임 간격(초), 없으면 null
    @SerialName("vadSec") val vadSec: Double, // VAD 계산 시간(초)
    @SerialName("scoresFile") val scoresFile: String?, // 점수 파일명(같은 폴더), 점수가 없으면 null
    @SerialName("scoresCount") val scoresCount: Int, // 점수 개수(점수가 없으면 0)
)

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: VAD 결과 캐시
 * 기능: 같은 (프리셋, 음원 sha1) 는 compute 를 한 번만 실행해 모든 STT 엔진이 동일 구간·동일 프레임 점수를 쓰게 한다.
 *       프레임 점수도 디스크에 저장·복원하므로(ChunkPlanner 의 MIN_SCORE 분할이 엔진 순서와 무관하게 같음) 별도 메모리 캐시는 두지 않는다.
 *       JSON 은 점수 파일을 먼저 쓴 뒤 마지막에 쓴다(JSON 이 "완료 표시"). 동시 호출 시 같은 키의 중복 계산은 막지 않는다(배치 러너는 순차 호출).
 * 입력: runDir(output_stt/<run_id>)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class VadCache(private val runDir: File) {

    /**
     * 목적: 캐시 조회 또는 계산
     * 기능: 파일이 있고 sha·점수 파일이 모두 신뢰 가능하면 재사용, 아니면 compute 를 실행·시간 측정 후 원자적으로 저장한다.
     *       JSON 이 깨졌거나 점수 파일이 없음/길이 불일치/기록과 불일치이면 신뢰하지 않고 재계산해 덮어쓴다.
     *       compute 가 예외를 던지면 그대로 전파하며 파일을 만들지 않는다.
     * 입력: preset(VAD 프리셋), item(입력 음원), audioSha1(음원 sha1), compute(VAD 실행 람다)
     * 출력: compute·파일 쓰기 예외 전파
     * 리턴: CachedVad(output, vadSec, reused)
     */
    fun getOrCompute(preset: VadPreset, item: InputItem, audioSha1: String, compute: () -> VadOutput): CachedVad {
        val file = OutputLayout.vadSegments(runDir, preset.id, item) // 캐시 JSON 경로
        val stored = readStored(file) // 기존 파일(없거나 깨지면 null)
        if (stored != null && stored.presetId == preset.id && stored.audioSha1 == audioSha1) { // 같은 프리셋·같은 음원이면
            val restored = restore(file, stored) // 점수까지 복원 시도
            if (restored != null) return CachedVad(restored, stored.vadSec, true) // 저장된 vadSec 그대로
        }
        val t0 = System.nanoTime() // 측정 시작
        val output = compute() // VAD 실행
        val vadSec = (System.nanoTime() - t0) / 1_000_000_000.0 // 초 단위 wall-clock
        val scoresFile = scoresFileOf(file) // 점수 파일 경로
        val scores = output.frameScores // 프레임 점수(없을 수 있음)
        if (scores != null) writeAtomic(scoresFile, floatsToBytes(scores)) // 점수 파일 먼저 쓰기
        else scoresFile.delete() // 이전 계산의 낡은 점수 파일 제거
        val data = StoredVad( // 저장 형식 변환
            preset.id, audioSha1, output.segments.map { StoredSegment(it.startSec, it.endSec) }, output.frameHopSec, vadSec,
            if (scores != null) scoresFile.name else null, scores?.size ?: 0,
        )
        writeAtomic(file, AppJson.encodeToString(StoredVad.serializer(), data).toByteArray(Charsets.UTF_8)) // JSON 은 마지막(완료 표시)
        return CachedVad(output, vadSec, false) // 새로 계산함
    }

    /**
     * 목적: 점수 파일 경로 계산
     * 기능: JSON 과 같은 폴더의 "<stem>.scores.f32".
     * 입력: jsonFile(캐시 JSON 경로)
     * 출력: 없음
     * 리턴: 점수 파일 경로
     */
    private fun scoresFileOf(jsonFile: File): File =
        File(jsonFile.parentFile, jsonFile.name.removeSuffix(".json") + ".scores.f32") // 확장자 교체

    /**
     * 목적: 저장된 결과를 VadOutput 으로 복원
     * 기능: 점수 기록과 실제 점수 파일을 대조한다. scoresFile==null 이면 scoresCount 가 0 이어야 하고 점수는 null.
     *       scoresFile 이 있으면 이름이 예상 이름과 같고 파일 크기가 scoresCount*4 이어야 한다.
     * 입력: jsonFile(캐시 JSON 경로), stored(파싱된 JSON)
     * 출력: 없음
     * 리턴: 복원된 VadOutput, 신뢰 불가이면 null(재계산 유도)
     */
    private fun restore(jsonFile: File, stored: StoredVad): VadOutput? {
        val segments = stored.segments.map { SpeechSegment(it.start, it.end) } // 구간 복원
        if (stored.scoresFile == null) { // 점수 없는 VAD
            if (stored.scoresCount != 0) return null // 기록 불일치
            return VadOutput(segments, stored.frameHopSec, null) // 점수 null
        }
        val sf = scoresFileOf(jsonFile) // 예상 점수 파일
        if (stored.scoresFile != sf.name || stored.scoresCount < 0) return null // 이름·개수 기록 불일치
        if (!sf.isFile || sf.length() != stored.scoresCount.toLong() * 4L) return null // 없음/길이 불일치
        val bytes = try { sf.readBytes() } catch (e: java.io.IOException) { return null } // 읽기 실패면 신뢰 불가
        return VadOutput(segments, stored.frameHopSec, bytesToFloats(bytes)) // 점수 복원
    }

    /**
     * 목적: float 배열 → little-endian 바이트
     * 기능: 각 float 의 비트를 그대로 4바이트로 쓴다.
     * 입력: scores(점수 배열)
     * 출력: 없음
     * 리턴: 길이 size*4 바이트 배열
     */
    private fun floatsToBytes(scores: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(scores.size * 4).order(ByteOrder.LITTLE_ENDIAN) // 4바이트씩
        buf.asFloatBuffer().put(scores) // float 일괄 기록
        return buf.array() // 바이트 배열
    }

    /**
     * 목적: little-endian 바이트 → float 배열
     * 기능: floatsToBytes 의 역변환(비트 동일).
     * 입력: bytes(길이가 4의 배수)
     * 출력: 없음
     * 리턴: 점수 배열
     */
    private fun bytesToFloats(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / 4) // 결과 배열
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out) // 일괄 읽기
        return out // 점수
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
        } catch (e: kotlinx.serialization.SerializationException) { // 깨진 JSON/필드 누락(옛 형식 포함)
            null // 신뢰하지 않음
        } catch (e: IllegalArgumentException) { // 잘못된 값
            null // 신뢰하지 않음
        }
    }

    /**
     * 목적: 파일 원자적 쓰기
     * 기능: 같은 폴더의 임시 파일에 쓰고 ATOMIC_MOVE 로 교체해 중간 상태 파일이 남지 않게 한다. 실패하면 임시 파일을 지운다.
     * 입력: file(최종 경로), bytes(내용)
     * 출력: IO 예외 전파
     * 리턴: 없음
     */
    private fun writeAtomic(file: File, bytes: ByteArray) {
        val dir = file.parentFile!! // 부모 폴더
        dir.mkdirs() // 폴더 생성
        val tmp = File(dir, file.name + ".tmp") // 임시 파일
        try {
            tmp.writeBytes(bytes) // 임시 파일에 쓰기
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) // 원자적 교체
        } finally {
            tmp.delete() // 실패 시 잔재 제거(성공 시 이미 없음)
        }
    }
}
