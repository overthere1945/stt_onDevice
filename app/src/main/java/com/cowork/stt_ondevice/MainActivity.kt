// 파일명: MainActivity.kt
// 목적: 앱의 단일 Activity (Compose 호스트)
// 기능: SttTheme 으로 감싼 Compose UI 를 표시한다. 현재는 제목 텍스트만 표시하며 AppNav 연결은 Task 27 에서 수행한다.
package com.cowork.stt_ondevice // 앱 루트 패키지

import android.os.Bundle // onCreate 에 전달되는 저장 상태
import androidx.activity.ComponentActivity // Compose 호스트용 Activity 기본 클래스
import androidx.activity.compose.setContent // Compose UI 를 Activity 에 설정하는 확장 함수
import androidx.activity.enableEdgeToEdge // 시스템 바 뒤까지 그리는 전체 화면 모드
import androidx.compose.material3.Text // 텍스트 표시 컴포저블
import com.cowork.stt_ondevice.ui.theme.SttTheme // 앱 공통 테마

// change(add)-hyungchul-2026-10-10-11:56
/**
 * 목적: 앱 진입 화면 호스팅
 * 기능: 엣지투엣지를 켜고 SttTheme 안에 제목 텍스트를 그린다.
 */
class MainActivity : ComponentActivity() { // AppCompat 대신 ComponentActivity 사용

    /**
     * 목적: Activity 생성 시 화면 구성
     * 기능: 엣지투엣지 설정 후 Compose 콘텐츠를 지정한다.
     * 입력: savedInstanceState - 이전 저장 상태(없으면 null)
     * 출력: 화면에 "STT OnDevice" 텍스트 표시
     * 리턴: Unit
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState) // 부모 클래스 초기화
        enableEdgeToEdge() // 시스템 바 영역까지 UI 확장
        setContent { // Compose 콘텐츠 설정
            SttTheme { // 앱 공통 테마 적용
                Text("STT OnDevice") // 임시 제목 텍스트 (AppNav 는 Task 27 에서 연결)
            }
        }
    }
}
