// 파일명: EngineStalledException.kt
// 목적: 시스템 엔진이 감시 시간 안에 응답하지 않고, 백엔드를 새로 만들어 1회 재시도해도 또 응답하지 않을 때의 예외 (2차 작업 3)
// 기능: 어떤 시스템 엔진(kind)이 멈췄는지 담아 실행 쪽이 일시정지·안내(서비스 이름)를 할 수 있게 한다.
// change(add)-hyungchul-2026-10-11-18:37
package com.cowork.stt_ondevice.engine.system // 시스템 엔진 패키지

import com.cowork.stt_ondevice.model.SystemEngineKind // 시스템 엔진 종류

// change(add)-hyungchul-2026-10-11-18:37
/**
 * 목적: 시스템 엔진 멈춤 예외
 * 기능: 멈춘 엔진 종류와 한국어 설명(예 "<serviceLabel> 가 응답하지 않습니다(세션 n)")을 담는다.
 * 입력: kind(시스템 엔진 종류), message(한국어 설명)
 */
class EngineStalledException(
    val kind: SystemEngineKind, // 멈춘 엔진 종류
    message: String, // 설명
) : Exception(message)
