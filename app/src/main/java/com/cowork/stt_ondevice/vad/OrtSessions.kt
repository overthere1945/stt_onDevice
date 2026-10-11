// 파일명: OrtSessions.kt
// 목적: ONNX Runtime(ORT) 세션 생성 공통 도우미
// 기능: 프로세스 공용 OrtEnvironment 1 개(OrtEnvironment.getEnvironment())로 CPU 세션을 만든다.
//       세션 옵션은 intra-op 스레드 수만 지정하고(기본 1, 원본 Python 골든 생성과 같은 결정적 실행), 나머지는 ORT 기본값
//       (그래프 최적화 ORT_ENABLE_ALL)이다. 앱은 onnxruntime-android, JVM 단위 테스트는 onnxruntime(JVM) 1.30.0 을 쓴다(같은 API).
package com.cowork.stt_ondevice.vad // VAD 패키지

import ai.onnxruntime.OrtEnvironment // ORT 환경(프로세스 공용)
import ai.onnxruntime.OrtSession // ORT 세션
import java.io.File // 모델 파일

// change(add)-hyungchul-2026-10-10-13:56
/**
 * 목적: ORT 세션 생성 단일 진입점
 * 기능: 모델 파일 존재를 확인하고 공용 환경에서 세션을 연다. 세션은 호출자가 소유하며 close() 해야 한다.
 */
object OrtSessions {

    /**
     * 목적: 프로세스 공용 ORT 환경
     * 기능: OrtEnvironment.getEnvironment() 는 항상 같은 싱글톤을 돌려준다(닫지 않음). 텐서 생성에도 이 환경을 쓴다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: OrtEnvironment
     */
    val environment: OrtEnvironment
        get() = OrtEnvironment.getEnvironment() // 공용 싱글톤

    /**
     * 목적: 모델 파일로 CPU 세션 열기
     * 기능: 파일이 없으면 다른 모델로 대체하지 않고 예외를 던진다. intra-op 스레드 수를 지정한 옵션으로 세션을 만든 뒤
     *       옵션 객체는 닫는다(세션 생성 시 네이티브 쪽에 복사됨).
     * 입력: modelFile(.onnx 파일), threads(intra-op 스레드 수, 1 이상)
     * 출력: 파일이 없거나 threads < 1 이면 IllegalArgumentException, 모델 로드 실패 시 OrtException
     * 리턴: OrtSession(호출자가 close)
     */
    fun open(modelFile: File, threads: Int = 1): OrtSession {
        require(modelFile.isFile) { "ONNX 모델 파일 없음: ${modelFile.absolutePath}" } // 조용한 fallback 금지
        require(threads >= 1) { "ORT intra-op 스레드 수는 1 이상이어야 함: $threads" } // 잘못된 설정 거부
        OrtSession.SessionOptions().use { opts -> // 세션 옵션(사용 후 닫음)
            opts.setIntraOpNumThreads(threads) // 연산자 내부 병렬 스레드 수
            return environment.createSession(modelFile.absolutePath, opts) // 세션 생성
        }
    }
}
