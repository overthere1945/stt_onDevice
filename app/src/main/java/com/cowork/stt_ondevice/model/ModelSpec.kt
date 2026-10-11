// 파일명: ModelSpec.kt
// 목적: ASR·VAD 모델 artifact 의 메타데이터 타입 정의
// 기능: 모델 종류·런타임·압축 형식·스트리밍 방식 열거형, 파일/다운로드 정보, 엔진별 설정(EngineConfig), 모델 1개를 나타내는 ModelSpec 을 제공한다.
//       실제 값은 tools/hash_models.py 가 생성하는 ModelCatalogData.kt 에 있다.
package com.cowork.stt_ondevice.model // 모델 메타 패키지

import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 모델 종류 구분
 * 기능: 음성 인식(ASR) 모델과 VAD 모델을 나눈다.
 */
enum class ModelKind {
    ASR, // 음성 인식 모델
    VAD, // 음성 구간 검출 모델
}

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 모델을 실행하는 런타임 구분
 * 기능: 엔진 생성 시 어떤 네이티브 라이브러리/API 를 쓸지 정한다.
 */
enum class Runtime {
    WHISPER_CPP, // whisper.cpp(JNI)
    SHERPA_OFFLINE, // sherpa-onnx OfflineRecognizer
    SHERPA_ONLINE, // sherpa-onnx OnlineRecognizer(스트리밍)
    VOSK, // Vosk(Kaldi)
    ORT, // ONNX Runtime 직접 사용(VAD)
    LITERT, // LiteRT(TFLite, Yamnet)
}

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 다운로드 파일의 압축 형식
 * 기능: 설치기가 해제 방법을 고른다. NONE 은 받은 파일 자체가 모델 파일이다.
 */
enum class ArchiveType {
    NONE, // 압축 없음(단일 파일)
    TAR_BZ2, // tar.bz2
    ZIP, // zip
}

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 엔진의 입력 처리 방식 구분
 * 기능: 파일(청크) 단위 일괄 인식인지, 네이티브 스트리밍 인식인지 나타낸다.
 */
enum class StreamingKind {
    FILE_ONLY, // 청크를 한 번에 넣어 인식
    NATIVE_STREAMING, // 엔진이 스트리밍 입력을 직접 처리
}

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 설치된 모델 폴더 안 파일 1개의 검증 정보
 * 기능: 설치 후 존재·크기·SHA-256 을 검사하고, role 로 엔진 설정 필드에 연결한다(역할 ↔ 필드 표는 README_카탈로그.md).
 * 입력: path(모델 폴더 기준 상대 경로, '/' 구분), bytes(크기), sha256(소문자 16진 64자), role(역할 이름)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class ModelFile(
    val path: String, // 모델 폴더 기준 상대 경로
    val bytes: Long, // 파일 크기(바이트)
    val sha256: String, // SHA-256(소문자 16진)
    val role: String, // 역할(encoder/decoder/joiner/tokens/model/... )
)

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 내려받을 artifact 1개의 정보
 * 기능: URL·크기·SHA-256 으로 받은 파일을 검증하고, 압축이면 stripPrefix 를 떼어 모델 폴더에 해제한다.
 *       archive=NONE 이면 bytes·sha256 이 같은 ModelFile 의 path 가 저장 이름이다.
 * 입력: url(HTTPS 주소), bytes(크기), sha256(받은 파일 SHA-256), archive(압축 형식), stripPrefix(압축 안 공통 상위 폴더, '/' 로 끝남, 없으면 "")
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class Download(
    val url: String, // HTTPS 주소
    val bytes: Long, // 받은 파일 크기(바이트)
    val sha256: String, // 받은 파일 SHA-256
    val archive: ArchiveType, // 압축 형식
    val stripPrefix: String, // 해제 시 떼어낼 상위 폴더
)

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 엔진별 설정의 공통 타입
 * 기능: 모델마다 어떤 엔진 구현을 어떤 설정으로 만들지 나타낸다.
 */
sealed interface EngineConfig

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: whisper.cpp 설정
 * 기능: 빔 서치 크기를 담는다.
 * 입력: beamSize(빔 크기, 기본 5)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class WhisperConfig(val beamSize: Int = 5) : EngineConfig // 빔 크기

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: sherpa-onnx 모델 종류
 * 기능: 엔진이 어떤 sherpa 모델 설정(transducer/senseVoice/qwen3Asr/omnilingual/online transducer)을 채울지 정한다.
 */
enum class SherpaKind {
    ZIPFORMER_TRANSDUCER, // OfflineTransducerModelConfig
    SENSEVOICE, // OfflineSenseVoiceModelConfig
    QWEN3_ASR, // OfflineQwen3AsrModelConfig
    OMNILINGUAL_CTC, // OfflineOmnilingualAsrCtcModelConfig
    STREAMING_ZIPFORMER, // OnlineTransducerModelConfig
}

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: sherpa-onnx 설정
 * 기능: sherpa 모델 종류를 담는다(파일 경로는 ModelFile.role 로 찾는다).
 * 입력: kind(sherpa 모델 종류)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class SherpaConfig(val kind: SherpaKind) : EngineConfig // sherpa 모델 종류

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: Vosk 설정
 * 기능: 별도 설정이 없다(모델 폴더 전체를 Model(path) 로 연다).
 */
data object VoskConfig : EngineConfig

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: VAD 모델 설정
 * 기능: 이 모델을 쓰는 VAD 계열을 담는다.
 * 입력: family(VAD 계열)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class VadModelConfig(val family: VadFamily) : EngineConfig // VAD 계열

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 모델 1개의 전체 메타데이터
 * 기능: 결과 폴더 이름(algo/model), 언어, 런타임, 최대 입력 길이, 다운로드·파일 검증 정보, 라이선스·상업 이용 판정, 엔진 설정을 담는다.
 * 입력: 각 필드(아래 주석)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
data class ModelSpec(
    val id: String, // 고유 id(소문자, 설치 폴더 이름)
    val kind: ModelKind, // ASR/VAD
    val algo: String, // 결과 폴더 algo(spec §9.2)
    val model: String, // 결과 폴더 model(spec §9.2)
    val displayName: String, // 화면 표시 이름
    val languages: Set<String>, // 지원 언어("ko","en"), VAD 는 빈 집합
    val runtime: Runtime, // 실행 런타임
    val runtimeVersion: String, // 런타임 버전
    val precision: String, // 가중치 정밀도(f16/q5_0/q8_0/int8/fp32)
    val maxChunkSec: Double, // 1회 입력 최대 길이(초), 0 = 제한 없음(스트리밍)
    val streaming: StreamingKind, // 입력 처리 방식
    val downloads: List<Download>, // 내려받을 artifact(비어 있으면 import 전용)
    val files: List<ModelFile>, // 설치 후 검사할 파일
    val originalRepo: String, // 원본 저장소 URL
    val license: String, // 라이선스 이름
    val licenseUrl: String, // 라이선스 근거 URL
    val commercial: CommercialStatus, // 상업 이용 판정
    val compatAlias: String?, // PC 평가기 폴더 별칭(없으면 null)
    val engineConfig: EngineConfig, // 엔진 설정
)
