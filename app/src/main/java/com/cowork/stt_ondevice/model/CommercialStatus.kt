// 파일명: CommercialStatus.kt
// 목적: 모델/라이브러리의 상업 이용 가능 여부 분류 정의
// 기능: 허용·조건부·비상업·미확인 4단계 열거형을 제공한다.
package com.cowork.stt_ondevice.model // 모델 메타 패키지

// change(add)-hyungchul-2026-10-10-12:16
/**
 * 목적: 상업 이용 가능 여부 표시
 * 기능: 라이선스 검토 결과를 4단계로 구분한다(화면 배지·결과 CSV 에 사용).
 */
enum class CommercialStatus {
    ALLOWED, // 상업 이용 허용(MIT/Apache/BSD 등)
    CONDITIONAL, // 조건부 허용(출처 표기·경쟁 서비스 금지 등)
    NONCOMMERCIAL, // 비상업 전용
    UNVERIFIED, // 아직 검토하지 못함
}
