// 파일명: App.kt
// 목적: 앱 프로세스 전역 진입점(Application) 정의
// 기능: 현재는 빈 Application. 이후 Task 에서 전역 초기화(저장소, 로거 등)를 이 클래스에 추가한다.
package com.cowork.stt_ondevice // 앱 루트 패키지

import android.app.Application // 안드로이드 Application 기본 클래스

// change(add)-hyungchul-2026-10-10-11:56
/**
 * 목적: 앱 프로세스 전역 상태의 소유자
 * 기능: 프로세스 시작 시 한 번 생성되며 Manifest 의 android:name 으로 등록된다.
 */
class App : Application() // 아직 전역 초기화가 없어 본문이 비어 있음
