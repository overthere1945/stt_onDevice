// 파일명: YamnetLiteRt.kt
// 목적: yamnet.tflite(Kaggle google/yamnet tfLite classification-tflite v1)를 LiteRT Interpreter 로 실행하는 얇은 래퍼
// 기능: 모델 파일을 열고 입출력 텐서 모양(입력 [15600] float32 파형, 출력 [1, 521] float32 클래스 점수)을 확인한 뒤,
//       0.975 s(15600 샘플) 창 하나를 넣어 521 클래스 점수를 돌려준다. 다른 모양의 모델이면 조용히 진행하지 않고 예외를 던진다.
//       (android-vad 는 TFLite Task Library AudioClassifier 를 썼지만 본 앱은 LiteRT Interpreter 로 같은 모델을 직접 실행한다.)
// change(add)-hyungchul-2026-10-10-15:43
package com.cowork.stt_ondevice.vad.impl // VAD 구현 패키지

import org.tensorflow.lite.Interpreter // LiteRT(구 TFLite) Interpreter API
import java.io.File // 모델 파일

// change(add)-hyungchul-2026-10-10-15:43
/**
 * 목적: Yamnet 모델 1개의 LiteRT 실행 세션
 * 기능: 생성 시 모델을 올리고 텐서 모양을 검사한다. classify 는 같은 스레드에서 순서대로 호출해야 한다(Interpreter 는 스레드 안전하지 않음).
 * 입력: modelFile(yamnet.tflite 경로)
 * 출력: 모델이 없거나 모양이 다르면 IllegalArgumentException / IllegalStateException
 * 리턴: 해당 없음
 */
class YamnetLiteRt(modelFile: File) : AutoCloseable {

    private val interpreter: Interpreter // LiteRT 실행기
    private val output = Array(1) { FloatArray(NUM_CLASSES) } // 출력 버퍼 [1][521] (호출마다 재사용)

    init {
        require(modelFile.isFile) { "Yamnet 모델 파일 없음: ${modelFile.absolutePath}" } // 파일 존재 확인(대체 모델 없음)
        interpreter = Interpreter(modelFile, Interpreter.Options()) // 기본 옵션(CPU, LiteRT 기본 XNNPACK) — Python 골든과 같은 기본 설정
        val inShape = interpreter.getInputTensor(0).shape() // 입력 텐서 모양
        val outShape = interpreter.getOutputTensor(0).shape() // 출력 텐서 모양
        if (!inShape.contentEquals(intArrayOf(WINDOW_SAMPLES)) || !outShape.contentEquals(intArrayOf(1, NUM_CLASSES))) { // 모양 검사
            interpreter.close() // 열린 자원 해제
            throw IllegalStateException("Yamnet 모델 텐서 모양 불일치: 입력 ${inShape.contentToString()}, 출력 ${outShape.contentToString()} (기대 [15600], [1, 521])") // 다른 모델 거부
        }
    }

    /**
     * 목적: 창 하나 분류
     * 기능: 15600 샘플 float 파형(−1..1)을 넣어 521 클래스 점수(각 0..1, 시그모이드)를 얻는다.
     * 입력: window(길이 15600 float 배열; 호출 후 다시 써도 됨)
     * 출력: 길이가 다르면 IllegalArgumentException
     * 리턴: 521 클래스 점수(새 배열)
     */
    fun classify(window: FloatArray): FloatArray {
        require(window.size == WINDOW_SAMPLES) { "Yamnet 창 길이 ${window.size} ≠ $WINDOW_SAMPLES" } // 입력 크기 확인
        interpreter.run(window, output) // 추론(입력 [15600] → 출력 [1][521])
        return output[0].copyOf() // 다음 호출에 덮어쓰이지 않게 복사해 반환
    }

    /**
     * 목적: 자원 해제
     * 기능: Interpreter 네이티브 자원을 닫는다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        interpreter.close() // LiteRT 해제
    }

    private companion object {
        const val WINDOW_SAMPLES = YamnetVad.WINDOW_SAMPLES // 입력 파형 길이(0.975 s @ 16 kHz) — YamnetVad 상수 공유
        const val NUM_CLASSES = YamnetVad.NUM_CLASSES // AudioSet 클래스 수 — YamnetVad 상수 공유
    }
}
