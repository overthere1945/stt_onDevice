// 파일명: EngineFactory.kt
// 목적: ModelSpec 의 런타임에 맞는 AsrEngine 구현을 만드는 단일 진입점
// 기능: WHISPER_CPP → WhisperCppEngine(모델 폴더의 role "model" 파일). 아직 구현하지 않은 런타임(sherpa-onnx·Vosk, Task 21·22 에서 채움)은
//       EngineException("MODEL_LOAD","NOT_IMPLEMENTED") — 다른 엔진으로 조용히 대체하지 않는다.
//       엔진 생성만 하며 모델 로드(네이티브 호출)는 prepare() 에서 한다.
// change(add)-hyungchul-2026-10-10-20:35
package com.cowork.stt_ondevice.engine // 엔진 패키지

import com.cowork.stt_ondevice.engine.whisper.WhisperCppEngine // whisper.cpp 엔진
import com.cowork.stt_ondevice.model.ModelKind // ASR/VAD
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:35
/**
 * 목적: 엔진 생성기
 * 기능: 런타임별로 분기해 엔진 객체를 만든다.
 */
object EngineFactory {

    const val MODEL_ROLE = "model" // whisper.cpp 단일 모델 파일의 역할 이름(README_카탈로그.md)

    /**
     * 목적: 스펙에 맞는 엔진 생성
     * 기능: ASR 스펙만 받는다. WHISPER_CPP 는 spec.files 중 role "model" 파일을 modelDir 기준으로 찾아 WhisperCppEngine 을 만든다.
     *       파일 존재 확인은 prepare() 에서 한다(설치 상태 판정은 ModelStore 담당).
     * 입력: spec(모델 메타), modelDir(설치 폴더 models/<id>)
     * 출력: ASR 아님 → EngineException(MODEL_LOAD, NOT_ASR_MODEL), role "model" 없음 → EngineException(MODEL_LOAD, MODEL_FILE_MISSING),
     *       미구현 런타임 → EngineException(MODEL_LOAD, NOT_IMPLEMENTED)
     * 리턴: AsrEngine(아직 준비 전)
     */
    fun create(spec: ModelSpec, modelDir: File): AsrEngine {
        if (spec.kind != ModelKind.ASR) { // VAD 모델 등
            throw EngineException("MODEL_LOAD", "NOT_ASR_MODEL", "ASR 모델이 아님: ${spec.id} (${spec.kind})") // 거부
        }
        return when (spec.runtime) { // 런타임별 분기
            Runtime.WHISPER_CPP -> { // whisper.cpp
                val file = spec.files.firstOrNull { it.role == MODEL_ROLE } // 단일 모델 파일 항목
                    ?: throw EngineException("MODEL_LOAD", "MODEL_FILE_MISSING", "스펙에 role \"$MODEL_ROLE\" 파일이 없음: ${spec.id}") // 카탈로그 오류
                WhisperCppEngine(spec, File(modelDir, file.path)) // 엔진 생성(로드는 prepare)
            }
            else -> throw EngineException("MODEL_LOAD", "NOT_IMPLEMENTED", "아직 구현하지 않은 런타임: ${spec.runtime.name} (${spec.id})") // 미구현(대체 금지)
        }
    }
}
