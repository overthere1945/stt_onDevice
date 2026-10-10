// 파일명: VadEngineFactoryTest.kt
// 목적: VadEngineFactory 의 계열별 엔진 생성과 모델 없음 처리 검증
// 기능: 모델이 필요 없는 계열 생성, 모델 폴더/파일 없음 시 VadUnavailableException, (있으면) Silero 실제 생성을 확인한다.
package com.cowork.stt_ondevice.vad // VAD 테스트 패키지

import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertThrows // 예외 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Assume.assumeTrue // 준비물 없으면 건너뜀
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더 규칙
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: 팩토리 회귀 방지
 * 기능: 계열별 모델 요구 사항과 오류 형식을 확인한다.
 */
class VadEngineFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder() // 임시 폴더

    /**
     * 목적: 모델 불필요 계열 생성 검증
     * 기능: 모델 폴더 함수가 항상 null 이어도 NONE/WEBRTC/TEN 은 생성되고 family 가 맞다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun noModelFamiliesCreateWithoutModelDir() {
        val factory = VadEngineFactory { null } // 모델 폴더 없음
        for (family in listOf(VadFamily.NONE, VadFamily.WEBRTC, VadFamily.TEN)) { // 모델 불필요 계열
            factory.create(family).use { assertEquals(family, it.family) } // 생성 + 계열 확인
        }
    }

    /**
     * 목적: 모델 폴더 null 이면 예외
     * 기능: 모델이 필요한 모든 계열이 VadUnavailableException(family) 을 던진다(대체 없음).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun nullDirThrowsUnavailable() {
        val factory = VadEngineFactory { null } // 모델 폴더 없음
        val needModel = VadFamily.entries - setOf(VadFamily.NONE, VadFamily.WEBRTC, VadFamily.TEN) // 모델 필요 계열
        for (family in needModel) { // 계열마다
            val e = assertThrows(VadUnavailableException::class.java) { factory.create(family) } // 예외 확인
            assertEquals(family, e.family) // 계열 보존
            assertTrue(e.message!!.isNotBlank()) // 사유 있음
        }
    }

    /**
     * 목적: 폴더는 있으나 모델 파일이 없으면 예외
     * 기능: 빈 폴더를 주면 파일 이름이 정해진 계열(Silero/pyannote/Yamnet)과 폴더 내용이 필요한 계열 모두 예외.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun dirWithoutFileThrowsUnavailable() {
        val empty = tmp.newFolder("empty") // 빈 폴더
        val factory = VadEngineFactory { empty } // 모든 계열에 빈 폴더
        for (family in listOf(VadFamily.SILERO, VadFamily.PYANNOTE, VadFamily.YAMNET)) { // 파일 이름 고정 계열
            val e = assertThrows(VadUnavailableException::class.java) { factory.create(family) } // 예외 확인
            assertEquals(family, e.family) // 계열 보존
            assertTrue(e.message!!.contains(family.name)) // 메시지에 계열 포함
        }
    }

    /**
     * 목적: 폴더 경로가 파일이거나 존재하지 않을 때 예외
     * 기능: 존재하지 않는 폴더를 주면 FSMN 도 VadUnavailableException.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun missingDirThrowsUnavailable() {
        val factory = VadEngineFactory { File(tmp.root, "nope") } // 없는 폴더
        for (family in listOf(VadFamily.FSMN, VadFamily.FIRERED, VadFamily.MARBLENET, VadFamily.SILERO)) { // 폴더 계열 포함
            assertThrows(VadUnavailableException::class.java) { factory.create(family) } // 예외 확인
        }
    }

    /**
     * 목적: Silero 실제 생성(양성 사례)
     * 기능: 골든 모델 폴더가 있으면 SILERO 엔진이 생성되고 family 가 맞다. 없으면 건너뜀.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    @Test
    fun sileroCreatesWhenModelPresent() {
        val dir = File("../tools/golden/models/silero_v6.2.3").absoluteFile // app/ 기준 상대 경로
        assumeTrue("Silero 모델 없음: $dir", File(dir, "silero_vad.onnx").isFile) // 없으면 건너뜀
        VadEngineFactory { dir }.create(VadFamily.SILERO).use { assertEquals(VadFamily.SILERO, it.family) } // 생성 확인
    }
}
