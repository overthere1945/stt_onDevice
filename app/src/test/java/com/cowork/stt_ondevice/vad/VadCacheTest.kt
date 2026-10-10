// 파일명: VadCacheTest.kt
// 목적: VadCache(프리셋×음원 VAD 결과 캐시)의 계산 1회·재사용·sha 불일치 재계산·파일 경로/내용·원자적 쓰기 검증
// 기능: compute 호출 횟수, 디스크 재사용 시 저장된 vadSec 반환, 메모리 1칸 캐시의 frameScores 유지, OutputLayout 경로 JSON 필드를 확인한다.
package com.cowork.stt_ondevice.vad // VAD 테스트 패키지

import com.cowork.stt_ondevice.core.json.AppJson // 공용 JSON
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 음원 모델
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import kotlinx.serialization.json.JsonObject // JSON 객체
import kotlinx.serialization.json.JsonArray // JSON 배열
import kotlinx.serialization.json.JsonNull // JSON null
import kotlinx.serialization.json.double // 숫자 읽기
import kotlinx.serialization.json.jsonArray // 배열 변환
import kotlinx.serialization.json.jsonObject // 객체 변환
import kotlinx.serialization.json.jsonPrimitive // 원시값 변환
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertNotNull // null 아님 검증
import org.junit.Assert.assertNull // null 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: VadCache 동작 회귀 방지
 * 기능: 임시 runDir 에서 캐시 파일 생성·재사용·덮어쓰기를 검증한다.
 */
class VadCacheTest {

    @get:Rule
    val tmp = TemporaryFolder() // 테스트마다 새 runDir

    private val preset = VadPresets.byId("SILERO") // 테스트용 프리셋
    private val otherPreset = VadPresets.byId("SILERO_SAFE") // 다른 프리셋

    /**
     * 목적: 테스트용 입력 음원 생성
     * 기능: kr/하위폴더/sample 위치의 InputItem 을 만든다(파일 자체는 읽지 않음).
     * 입력: 없음
     * 출력: 없음
     * 리턴: InputItem
     */
    private fun item(): InputItem = InputItem("kr", "scn1", "01", File(tmp.root, "01.wav")) // 상대 폴더가 있는 항목

    /**
     * 목적: 테스트용 VAD 출력 생성
     * 기능: 구간 2개, 점수 3프레임을 가진 VadOutput 을 만든다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: VadOutput
     */
    private fun output(): VadOutput = VadOutput(
        listOf(SpeechSegment(0.5, 2.25), SpeechSegment(3.0, 4.125)), // 구간 2개
        0.032, // 프레임 간격
        floatArrayOf(0.1f, 0.9f, 0.2f), // 프레임 점수
    )

    /**
     * 목적: 같은 (프리셋, 음원, sha) 는 compute 를 한 번만 호출함을 검증
     * 기능: 같은 인스턴스 2회 + 새 인스턴스(디스크만 남음) 1회 호출 후 호출 횟수와 결과를 본다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun computesOnceThenReuses() {
        var calls = 0 // compute 호출 횟수
        val cache = VadCache(tmp.root) // 캐시
        val first = cache.getOrCompute(preset, item(), "sha-A") { calls++; output() } // 최초: 계산
        assertEquals(1, calls) // 1회 호출
        assertFalse(first.reused) // 새로 계산함
        assertTrue(first.vadSec >= 0.0) // 측정된 시간
        val second = cache.getOrCompute(preset, item(), "sha-A") { calls++; output() } // 같은 인스턴스: 재사용
        assertEquals(1, calls) // 호출 증가 없음
        assertTrue(second.reused) // 재사용
        assertEquals(first.vadSec, second.vadSec, 0.0) // 저장된 vadSec 그대로
        assertNotNull(second.output.frameScores) // 메모리 캐시라 점수 유지
        val third = VadCache(tmp.root).getOrCompute(preset, item(), "sha-A") { calls++; output() } // 새 인스턴스: 디스크만
        assertEquals(1, calls) // 여전히 1회
        assertTrue(third.reused) // 재사용
        assertEquals(first.vadSec, third.vadSec, 0.0) // 디스크에 저장된 vadSec
        assertNull(third.output.frameScores) // 디스크에서 읽으면 점수 없음
        assertEquals(output().segments, third.output.segments) // 구간은 동일
        assertEquals(0.032, third.output.frameHopSec!!, 0.0) // 프레임 간격 보존
    }

    /**
     * 목적: sha 가 다르면 다시 계산하고 덮어씀을 검증
     * 기능: sha-A 저장 후 sha-B 로 호출 → 재계산, 이후 sha-B 는 재사용, sha-A 는 다시 재계산.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun shaMismatchRecomputes() {
        var calls = 0 // compute 호출 횟수
        val cache = VadCache(tmp.root) // 캐시
        cache.getOrCompute(preset, item(), "sha-A") { calls++; output() } // sha-A 계산
        val b = cache.getOrCompute(preset, item(), "sha-B") { calls++; VadOutput(listOf(SpeechSegment(1.0, 2.0)), null, null) } // sha-B: 재계산
        assertEquals(2, calls) // 두 번 호출
        assertFalse(b.reused) // 새로 계산
        val file = OutputLayout.vadSegments(tmp.root, preset.id, item()) // 캐시 파일
        val json = AppJson.parseToJsonElement(file.readText()).jsonObject // 파일 내용
        assertEquals("sha-B", json["audioSha1"]!!.jsonPrimitive.content) // 덮어써서 sha-B
        cache.getOrCompute(preset, item(), "sha-B") { calls++; output() } // sha-B 재사용
        assertEquals(2, calls) // 증가 없음
        VadCache(tmp.root).getOrCompute(preset, item(), "sha-A") { calls++; output() } // 디스크 sha-B ≠ sha-A → 재계산
        assertEquals(3, calls) // 세 번째 호출
    }

    /**
     * 목적: 파일 경로와 JSON 필드 검증
     * 기능: OutputLayout.vadSegments 위치에 {presetId, audioSha1, segments[{start,end}], frameHopSec, vadSec} 가 저장되고 임시 파일이 남지 않으며,
     *       점수 없는 출력은 frameHopSec 이 null 로 저장됨을 확인한다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun writesJsonAtLayoutPath() {
        val cache = VadCache(tmp.root) // 캐시
        cache.getOrCompute(preset, item(), "sha-A") { output() } // 저장
        val file = OutputLayout.vadSegments(tmp.root, preset.id, item()) // 기대 경로
        assertTrue("파일이 레이아웃 경로에 있어야 함: $file", file.isFile) // 존재 확인
        assertEquals(listOf(file.name), file.parentFile!!.list()!!.toList()) // 같은 폴더에 임시 파일 잔재 없음
        val json: JsonObject = AppJson.parseToJsonElement(file.readText()).jsonObject // 파싱
        assertEquals(setOf("presetId", "audioSha1", "segments", "frameHopSec", "vadSec"), json.keys) // 필드 집합
        assertEquals(preset.id, json["presetId"]!!.jsonPrimitive.content) // 프리셋 id
        assertEquals("sha-A", json["audioSha1"]!!.jsonPrimitive.content) // sha
        val segs: JsonArray = json["segments"]!!.jsonArray // 구간 배열
        assertEquals(2, segs.size) // 2개
        assertEquals(setOf("start", "end"), segs[0].jsonObject.keys) // 구간 필드
        assertEquals(0.5, segs[0].jsonObject["start"]!!.jsonPrimitive.double, 0.0) // 시작
        assertEquals(4.125, segs[1].jsonObject["end"]!!.jsonPrimitive.double, 0.0) // 끝
        assertEquals(0.032, json["frameHopSec"]!!.jsonPrimitive.double, 0.0) // 프레임 간격
        assertTrue(json["vadSec"]!!.jsonPrimitive.double >= 0.0) // 측정 시간
        assertFalse(json.containsKey("frameScores")) // 점수는 저장하지 않음
        cache.getOrCompute(otherPreset, item(), "sha-A") { VadOutput(emptyList(), null, null) } // 점수 없는 출력, 다른 프리셋
        val other = OutputLayout.vadSegments(tmp.root, otherPreset.id, item()) // 다른 프리셋 경로
        assertTrue(other.isFile) // 프리셋별 별도 파일
        val oj = AppJson.parseToJsonElement(other.readText()).jsonObject // 파싱
        assertEquals(JsonNull, oj["frameHopSec"]) // null 로 명시
        assertEquals(0, oj["segments"]!!.jsonArray.size) // 빈 구간
    }

    /**
     * 목적: 깨진 캐시 파일을 신뢰하지 않음을 검증
     * 기능: 잘린 JSON 이 있으면 재계산해 정상 파일로 덮어쓴다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun corruptFileRecomputes() {
        val file = OutputLayout.vadSegments(tmp.root, preset.id, item()) // 캐시 파일 경로
        file.parentFile!!.mkdirs() // 폴더 생성
        file.writeText("{\"presetId\": \"SILERO\", \"audioS") // 잘린 JSON
        var calls = 0 // compute 호출 횟수
        val r = VadCache(tmp.root).getOrCompute(preset, item(), "sha-A") { calls++; output() } // 재계산
        assertEquals(1, calls) // 한 번 계산
        assertFalse(r.reused) // 재사용 아님
        assertEquals("sha-A", AppJson.parseToJsonElement(file.readText()).jsonObject["audioSha1"]!!.jsonPrimitive.content) // 정상 파일로 교체
    }

    /**
     * 목적: compute 실패 시 캐시 파일을 만들지 않음을 검증
     * 기능: compute 가 예외를 던지면 그대로 전파되고 파일이 생기지 않는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun computeFailureLeavesNoFile() {
        val file = OutputLayout.vadSegments(tmp.root, preset.id, item()) // 캐시 파일 경로
        try {
            VadCache(tmp.root).getOrCompute(preset, item(), "sha-A") { throw IllegalStateException("VAD 실패") } // 실패 compute
            org.junit.Assert.fail("예외가 전파되어야 함") // 여기 오면 실패
        } catch (e: IllegalStateException) {
            assertEquals("VAD 실패", e.message) // 원래 예외
        }
        assertFalse(file.exists()) // 파일 없음
    }
}
