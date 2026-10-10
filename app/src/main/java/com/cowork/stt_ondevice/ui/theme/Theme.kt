// 파일명: ui/theme/Theme.kt
// 목적: 앱 전체에 적용되는 Compose 테마(SttTheme) 정의
// 기능: 시스템 다크 모드에 따라 Material3 기본 색상표를 선택해 하위 컴포저블에 적용한다.
package com.cowork.stt_ondevice.ui.theme // 테마 패키지

import androidx.compose.foundation.isSystemInDarkTheme // 시스템 다크 모드 여부 조회
import androidx.compose.material3.MaterialTheme // Material3 테마 적용 컴포저블
import androidx.compose.material3.darkColorScheme // 기본 다크 색상표 생성
import androidx.compose.material3.lightColorScheme // 기본 라이트 색상표 생성
import androidx.compose.runtime.Composable // 컴포저블 함수 표시 애노테이션

// change(add)-hyungchul-2026-10-10-11:56
/**
 * 목적: 앱 공통 Material3 테마 적용
 * 기능: 다크 모드 여부에 따라 색상표를 고르고 MaterialTheme 으로 content 를 감싼다.
 * 입력: darkTheme - 다크 모드 사용 여부(기본값은 시스템 설정), content - 테마를 적용할 UI
 * 출력: 없음(UI 구성)
 * 리턴: Unit
 */
@Composable // Compose 가 호출하는 UI 함수
fun SttTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // 시스템 다크 모드를 기본값으로 사용
    content: @Composable () -> Unit, // 테마 안에 그려질 UI
) {
    val colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme() // 모드에 맞는 색상표 선택
    MaterialTheme(colorScheme = colorScheme, content = content) // 선택한 색상표로 하위 UI 를 감싼다
}
