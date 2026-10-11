// 파일명: PausedUiDeviceTest.kt (기기 계측 화면 테스트)
// 목적: 시스템 엔진 멈춤으로 배치가 일시정지됐을 때 진행 화면(RunProgressContent)이 안내 문구·[설정 열기]·[이어하기]를 보여 주고,
//       [설정 열기]가 멈춘 서비스의 패키지로 onOpenSettings 를 부르는지 확인 (2차 작업 10-6)
// 기능: - 실제 멈춤은 일부러 만들 수 없으므로 RunUiState.Paused 를 직접 만들어 RunProgressContent 에 넣고 그린다(서비스·엔진을 쓰지 않음).
//       - pausedShowsGuideAndButtons: 제목·"<서비스 이름> 가 응답하지 않습니다."·상세 사유·①②③ 단계 안내·두 버튼이 보이는지 확인.
//       - openSettingsCallsCallbackWithServicePackage: [설정 열기] 클릭 → onOpenSettings("com.google.android.tts") 1회, onOpenResults 는 불리지 않음.
//       - resumeOpensResultsWithRunId: [이어하기] 클릭 → onOpenResults(runId) 1회(결과 탭의 이어하기로 연결), onOpenSettings 는 불리지 않음.
//       취소 버튼은 RunService.cancel 을 직접 부르므로 이 테스트는 Running 상태를 그리지 않는다(Paused 화면에는 취소 버튼이 없음).
//       실행(앱 삭제 없이): adb install -r app-debug.apk, adb install -r app-debug-androidTest.apk 뒤
//         adb shell am instrument -w -e class com.cowork.stt_ondevice.ui.run.PausedUiDeviceTest com.cowork.stt_ondevice.test/androidx.test.runner.AndroidJUnitRunner
//       화면이 켜져 있고 잠금이 풀린 상태여야 한다(테스트용 빈 Activity 를 앞 화면에 띄움).
// change(add)-hyungchul-2026-10-11-20:32
package com.cowork.stt_ondevice.ui.run // 실행 화면 패키지(계측 테스트)

import androidx.compose.material3.MaterialTheme // 기본 테마
import androidx.compose.ui.test.assertIsDisplayed // 화면 표시 검증
import androidx.compose.ui.test.junit4.createComposeRule // Compose 화면 테스트 규칙
import androidx.compose.ui.test.onNodeWithText // 글자로 요소 찾기
import androidx.compose.ui.test.performClick // 클릭
import androidx.compose.ui.test.performScrollTo // 스크롤해서 보이게
import androidx.test.ext.junit.runners.AndroidJUnit4 // JUnit4 계측 러너
import com.cowork.stt_ondevice.runner.RunUiState // 실행 상태
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Rule // 규칙 표시
import org.junit.Test // 테스트 표시
import org.junit.runner.RunWith // 러너 지정

// change(add)-hyungchul-2026-10-11-20:32
/**
 * 목적: 일시정지 화면 검증
 * 기능: 파일 머리말 참조. 각 테스트는 같은 Paused 상태로 화면을 그리고, 콜백 호출 기록을 목록에 모아 단언한다.
 */
@RunWith(AndroidJUnit4::class)
class PausedUiDeviceTest {

    @get:Rule
    val compose = createComposeRule() // 테스트용 빈 Activity 에 Compose 화면을 그리는 규칙

    private val settingsCalls = mutableListOf<String>() // onOpenSettings 로 받은 패키지 기록
    private val resultCalls = mutableListOf<String>() // onOpenResults 로 받은 runId 기록

    /**
     * 목적: 일시정지 화면 그리기
     * 기능: 작업 brief 의 Paused 값으로 RunProgressContent 를 그리고, 두 콜백은 호출 기록만 남긴다.
     * 입력: 없음
     * 출력: 화면 표시
     * 리턴: 없음
     */
    private fun showPaused() {
        val state = RunUiState.Paused(RUN_ID, REASON, SERVICE_PKG, SERVICE_LABEL) // 멈춘 서비스 정보가 담긴 일시정지 상태
        compose.setContent { // 화면 내용 설정
            MaterialTheme { // 기본 테마 안에서
                RunProgressContent(state = state, onOpenResults = { resultCalls += it }, onOpenSettings = { settingsCalls += it }) // 진행 화면 내용
            }
        }
    }

    /**
     * 목적: 안내 문구와 버튼 표시 확인
     * 기능: 제목·핵심 문구·상세 사유·단계 안내 3줄·[설정 열기]·[이어하기]가 화면에 있는지 본다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun pausedShowsGuideAndButtons() {
        showPaused() // 화면 그리기
        compose.onNodeWithText("실행 일시정지: $RUN_ID").assertIsDisplayed() // 제목
        compose.onNodeWithText("$SERVICE_LABEL 가 응답하지 않습니다.").assertIsDisplayed() // 핵심 문구
        compose.onNodeWithText(REASON).assertIsDisplayed() // 상세 사유
        compose.onNodeWithText("① [설정 열기]를 눌러 $SERVICE_LABEL 의 앱 정보 화면을 엽니다.").assertIsDisplayed() // 1단계
        compose.onNodeWithText("② 그 화면에서 \"강제 중지\"를 누릅니다.").assertIsDisplayed() // 2단계
        compose.onNodeWithText("③ 이 앱으로 돌아와 [이어하기]를 누릅니다.").assertIsDisplayed() // 3단계
        compose.onNodeWithText("설정 열기").performScrollTo().assertIsDisplayed() // 설정 열기 버튼
        compose.onNodeWithText("이어하기").performScrollTo().assertIsDisplayed() // 이어하기 버튼
        assertEquals(0, settingsCalls.size + resultCalls.size) // 그리기만으로는 콜백이 불리지 않음
    }

    /**
     * 목적: [설정 열기] 동작 확인
     * 기능: 버튼을 누르면 onOpenSettings 가 멈춘 서비스 패키지로 정확히 1회 불리고 onOpenResults 는 불리지 않는지 본다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun openSettingsCallsCallbackWithServicePackage() {
        showPaused() // 화면 그리기
        compose.onNodeWithText("설정 열기").performScrollTo().performClick() // 설정 열기 클릭
        compose.waitForIdle() // 화면 처리 끝까지 대기
        assertEquals(listOf(SERVICE_PKG), settingsCalls) // 서비스 패키지로 1회
        assertEquals(emptyList<String>(), resultCalls) // 결과 탭 열기는 없음
    }

    /**
     * 목적: [이어하기] 동작 확인
     * 기능: 버튼을 누르면 onOpenResults 가 그 runId 로 정확히 1회 불리고(결과 탭의 이어하기로 이동) onOpenSettings 는 불리지 않는지 본다.
     * 입력: 없음
     * 출력: 없음(실패 시 AssertionError)
     * 리턴: 없음
     */
    @Test
    fun resumeOpensResultsWithRunId() {
        showPaused() // 화면 그리기
        compose.onNodeWithText("이어하기").performScrollTo().performClick() // 이어하기 클릭
        compose.waitForIdle() // 화면 처리 끝까지 대기
        assertEquals(listOf(RUN_ID), resultCalls) // 그 run 으로 1회
        assertEquals(emptyList<String>(), settingsCalls) // 설정 열기는 없음
    }

    private companion object {
        const val RUN_ID = "20261011_210000" // 가상의 runId(파일을 만들지 않음)
        const val REASON = "Speech Services by Google 가 응답하지 않습니다(세션 3)" // 작업 brief 의 상세 사유
        const val SERVICE_PKG = "com.google.android.tts" // 멈춘 서비스 패키지(ML Kit Basic 실행 위치)
        const val SERVICE_LABEL = "Speech Services by Google" // 화면에 보일 서비스 이름
    }
}
