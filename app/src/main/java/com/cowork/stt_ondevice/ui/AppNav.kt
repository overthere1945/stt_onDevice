// 파일명: ui/AppNav.kt
// 목적: 앱 최상위 화면 골격 — 하단 탭 5개(모델 / 실행 / 진행 / 결과 / 마이크)와 화면 이동
// 기능: Scaffold + NavigationBar + Navigation Compose NavHost 로 탭을 전환한다(탭 상태 저장·복원, 같은 탭 재선택 시 중복 쌓기 방지).
//       시스템 바 인셋은 Scaffold 가 처리해 콘텐츠가 상태바·내비게이션 바 아래로 들어가지 않는다.
//       모델 탭만 실제 화면이고 나머지는 "준비 중" 임시 화면이다(Task 29~30 에서 교체). (change(add)-hyungchul-2026-10-11-03:59 이제 5개 탭 모두 실제 화면)
//       change(add)-hyungchul-2026-10-11-03:31 실행·진행·결과 탭을 실제 화면으로 교체(마이크는 Task 30). 결과 탭은 선택 인자 runId(results?runId=…)로 특정 run 을 펼쳐 열 수 있고,
//       실행 시작·이어하기 시작 시 진행 탭으로, 진행 화면의 "결과 보기" 는 그 run 의 결과 탭으로 이동한다.
//       change(add)-hyungchul-2026-10-11-03:59 마이크 탭을 실제 화면(MicScreen)으로 교체. 실행 탭은 선택 인자 select(run?select=<wav 절대 경로>)로 마이크 녹음 파일을 미리 선택해 열 수 있다.
//       결과 탭의 SAF 내보내기를 켠다(exportEnabled = true).
package com.cowork.stt_ondevice.ui // 화면 최상위 패키지

import androidx.annotation.DrawableRes // 리소스 id 표시
import androidx.compose.foundation.layout.padding // 여백
import androidx.compose.material.icons.Icons // 기본 아이콘 모음
import androidx.compose.material.icons.automirrored.filled.List // 목록 아이콘
import androidx.compose.material.icons.filled.PlayArrow // 재생 아이콘
import androidx.compose.material.icons.filled.Refresh // 새로고침 아이콘
import androidx.compose.material.icons.filled.Settings // 설정 아이콘
import androidx.compose.material3.Icon // 아이콘
import androidx.compose.material3.NavigationBar // 하단 탭 바
import androidx.compose.material3.NavigationBarItem // 하단 탭 항목
import androidx.compose.material3.Scaffold // 화면 골격
import androidx.compose.material3.Text // 텍스트
import androidx.compose.runtime.Composable // 컴포저블
import androidx.compose.runtime.getValue // 위임 읽기
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.graphics.vector.ImageVector // 벡터 아이콘 타입
import androidx.compose.ui.res.painterResource // 리소스 아이콘
import androidx.navigation.NavDestination.Companion.hierarchy // 목적지 계층
import androidx.navigation.NavGraph.Companion.findStartDestination // 시작 목적지
import androidx.navigation.NavType // change(add)-hyungchul-2026-10-11-03:31 경로 인자 타입
import androidx.navigation.compose.NavHost // 화면 호스트
import androidx.navigation.compose.composable // 화면 등록
import androidx.navigation.compose.currentBackStackEntryAsState // 현재 화면 상태
import androidx.navigation.compose.rememberNavController // 내비게이션 컨트롤러
import androidx.navigation.navArgument // change(add)-hyungchul-2026-10-11-03:31 경로 인자 정의
import android.net.Uri // change(add)-hyungchul-2026-10-11-03:59 경로 인자 인코딩
import com.cowork.stt_ondevice.R // 앱 리소스
import com.cowork.stt_ondevice.ui.mic.MicScreen // change(add)-hyungchul-2026-10-11-03:59 마이크 화면
import com.cowork.stt_ondevice.ui.models.ModelsScreen // 모델 관리 화면
import com.cowork.stt_ondevice.ui.results.ResultsScreen // change(add)-hyungchul-2026-10-11-03:31 결과 화면
import com.cowork.stt_ondevice.ui.run.RunProgressScreen // change(add)-hyungchul-2026-10-11-03:31 진행 화면
import com.cowork.stt_ondevice.ui.run.RunSetupScreen // change(add)-hyungchul-2026-10-11-03:31 실행 설정 화면

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 하단 탭 정의
 * 기능: 경로·한국어 라벨·아이콘을 묶는다(표시 순서 = 선언 순서).
 * 입력: route(경로), label(탭 이름), icon(기본 벡터 아이콘, 없으면 null), iconRes(change(add)-hyungchul-2026-10-11-03:24 리소스 아이콘 id, icon 이 null 일 때 사용)
 */
enum class AppTab(val route: String, val label: String, val icon: ImageVector? = null, @DrawableRes val iconRes: Int = 0) { // change(add)-hyungchul-2026-10-11-03:24 리소스 아이콘 지원
    MODELS("models", "모델", Icons.Default.Settings), // 모델 관리
    RUN("run", "실행", Icons.Default.PlayArrow), // 실행 설정
    PROGRESS("progress", "진행", Icons.Default.Refresh), // 진행 상황
    RESULTS("results", "결과", Icons.AutoMirrored.Filled.List), // 결과 보기
    MIC("mic", "마이크", iconRes = R.drawable.ic_mic), // 마이크 녹음 — change(add)-hyungchul-2026-10-11-03:24 마이크 벡터
}

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 앱 최상위 화면
 * 기능: 하단 탭 5개와 NavHost 를 구성한다. 시작 화면은 모델 탭.
 * 입력: 없음
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun AppNav() {
    val navController = rememberNavController() // 내비게이션 컨트롤러
    val backStackEntry by navController.currentBackStackEntryAsState() // 현재 화면
    val destination = backStackEntry?.destination // 현재 목적지
    // change(add)-hyungchul-2026-10-11-03:31
    /** 목적: 탭 이동 / 기능: 시작 화면까지 정리·상태 저장, 같은 화면 중복 방지, restore 가 참이면 이전 상태 복원 / 입력: route(경로), restore(복원 여부) / 리턴: 없음 */
    val navigateTab: (String, Boolean) -> Unit = { route, restore ->
        navController.navigate(route) { // 이동
            popUpTo(navController.graph.findStartDestination().id) { saveState = true } // 시작 화면까지 정리하고 상태 저장
            launchSingleTop = true // 같은 화면 중복 방지
            restoreState = restore // 이전 상태 복원 여부
        }
    }
    Scaffold(
        bottomBar = {
            NavigationBar { // 하단 탭 바
                AppTab.entries.forEach { tab -> // 탭마다
                    NavigationBarItem(
                        selected = destination?.hierarchy?.any { it.route?.substringBefore('?') == tab.route } == true, // 선택 여부(change(add)-hyungchul-2026-10-11-03:31 인자 부분 제외하고 비교)
                        onClick = { navigateTab(tab.route, true) }, // 탭 이동(이전 상태 복원) — change(add)-hyungchul-2026-10-11-03:31 공용 함수로
                        icon = { // change(add)-hyungchul-2026-10-11-03:24 벡터/리소스 중 있는 쪽
                            if (tab.icon != null) Icon(tab.icon, contentDescription = tab.label) else Icon(painterResource(tab.iconRes), contentDescription = tab.label) // 아이콘
                        },
                        label = { Text(tab.label) }, // 한국어 라벨
                    )
                }
            }
        },
    ) { padding -> // 인셋이 반영된 여백
        NavHost(navController, startDestination = AppTab.MODELS.route, modifier = Modifier.padding(padding)) {
            composable(AppTab.MODELS.route) { ModelsScreen() } // 모델 관리
            composable( // 실행 설정 — change(add)-hyungchul-2026-10-11-03:31 시작되면 진행 탭 / change(add)-hyungchul-2026-10-11-03:59 선택 인자 select(미리 선택할 wav)
                route = "${AppTab.RUN.route}?$SELECT_ARG={$SELECT_ARG}", // run?select={select}
                arguments = listOf(navArgument(SELECT_ARG) { type = NavType.StringType; nullable = true; defaultValue = null }), // 없으면 null
            ) { entry ->
                RunSetupScreen(onStarted = { navigateTab(AppTab.PROGRESS.route, false) }, preselectPath = entry.arguments?.getString(SELECT_ARG)) // 실행 설정(미리 선택 경로)
            }
            composable(AppTab.PROGRESS.route) { RunProgressScreen(onOpenResults = { runId -> navigateTab("${AppTab.RESULTS.route}?$RUN_ID_ARG=$runId", false) }) } // 진행 — change(add)-hyungchul-2026-10-11-03:31 결과 보기 → 그 run
            composable( // 결과 — change(add)-hyungchul-2026-10-11-03:31 선택 인자 runId
                route = "${AppTab.RESULTS.route}?$RUN_ID_ARG={$RUN_ID_ARG}", // results?runId={runId}
                arguments = listOf(navArgument(RUN_ID_ARG) { type = NavType.StringType; nullable = true; defaultValue = null }), // 없으면 null
            ) { entry ->
                ResultsScreen(
                    initialRunId = entry.arguments?.getString(RUN_ID_ARG), // 펼칠 run
                    exportEnabled = true, // change(add)-hyungchul-2026-10-11-03:59 SAF 내보내기 연결(이전: onExport = null)
                    onResumeStarted = { navigateTab(AppTab.PROGRESS.route, false) }, // 이어하기 시작 → 진행 탭
                )
            }
            composable(AppTab.MIC.route) { MicScreen(onOpenRunSetup = { path -> navigateTab("${AppTab.RUN.route}?$SELECT_ARG=${Uri.encode(path)}", false) }) } // 마이크 — change(add)-hyungchul-2026-10-11-03:59 실제 화면, "실행 설정으로" → 그 파일을 고른 실행 탭
        }
    }
}

private const val RUN_ID_ARG = "runId" // 결과 탭 경로 인자 이름 // change(add)-hyungchul-2026-10-11-03:31
private const val SELECT_ARG = "select" // 실행 탭 경로 인자 이름(미리 선택할 wav) // change(add)-hyungchul-2026-10-11-03:59

// change(add)-hyungchul-2026-10-11-03:59 임시 화면(PlaceholderScreen)은 마지막 탭(마이크)까지 실제 화면으로 바뀌어 제거했다.
