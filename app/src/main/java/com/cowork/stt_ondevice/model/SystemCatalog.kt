// 파일명: SystemCatalog.kt
// 목적: 시스템 엔진(모델 파일 없음)을 카탈로그 항목(ModelSpec)으로 만들어 제공
// 기능: 엔진 2종 × 공급 배속 1/2/4 = 6항목을 만든다. 파일·다운로드가 없으므로 항상 설치됨(READY)으로 취급되고, 모델 화면에는 나오지 않는다.
//       change(add)-hyungchul-2026-10-11-18:21 신규(2차 작업 2). 실제 엔진 생성은 이후 작업(EngineFactory 연결)에서 한다.
package com.cowork.stt_ondevice.model // 모델 메타 패키지

// change(add)-hyungchul-2026-10-11-18:21
/**
 * 목적: 시스템 엔진 항목 카탈로그
 * 기능: 종류 × 배속 순서의 ModelSpec 6개를 만든다(id = "<algo>-<접두>-x<배속>").
 */
object SystemCatalog {
    /** 목적: 지원 공급 배속 / 기능: 실시간 대비 1·2·4 배로 오디오를 공급한다 */
    val FEED_SPEEDS: List<Int> = listOf(1, 2, 4) // 공급 배속 목록

    /** 목적: 시스템 엔진 전체 항목 / 기능: 엔진 × 배속 순서의 6개 */
    val all: List<ModelSpec> = SystemEngineKind.entries.flatMap { kind -> FEED_SPEEDS.map { speed -> build(kind, speed) } } // 6개

    /**
     * 목적: 항목 1개 생성
     * 기능: 종류와 배속으로 ModelSpec 을 채운다(파일·다운로드 없음, 스트리밍 방식 PACED_REALTIME, maxChunkSec 30).
     * 입력: kind(시스템 엔진 종류), speed(공급 배속)
     * 출력: 없음
     * 리턴: ModelSpec
     */
    private fun build(kind: SystemEngineKind, speed: Int): ModelSpec {
        val mlkit = kind == SystemEngineKind.MLKIT_BASIC // ML Kit 여부
        return ModelSpec(
            id = "${kind.algo}-${kind.modelPrefix}-x$speed", // 고유 id
            kind = ModelKind.ASR, // 음성 인식
            algo = kind.algo, // 결과 폴더 algo
            model = "${kind.modelPrefix}-x$speed", // 결과 폴더 model
            displayName = (if (mlkit) "ML Kit GenAI Basic" else "Android 온디바이스 인식기") + " (공급 ${speed}×)", // 화면 이름
            languages = setOf("ko", "en"), // 지원 언어
            runtime = if (mlkit) Runtime.MLKIT_GENAI else Runtime.PLATFORM_ONDEVICE, // 런타임
            runtimeVersion = if (mlkit) "1.0.0-alpha1" else "platform", // 런타임 버전
            precision = "system", // 정밀도(시스템이 관리)
            maxChunkSec = 30.0, // 1회 입력 최대 길이
            streaming = StreamingKind.PACED_REALTIME, // 실시간 배속 공급
            downloads = emptyList(), // 내려받을 파일 없음
            files = emptyList(), // 검사할 파일 없음
            originalRepo = if (mlkit) "https://developers.google.com/ml-kit/genai/speech-recognition/android" else "https://developer.android.com/reference/android/speech/SpeechRecognizer", // 원본 문서
            license = if (mlkit) "Google 서비스 약관(ML Kit GenAI 추가 약관)" else "Google 서비스 약관(기기 음성 서비스)", // 약관 이름
            licenseUrl = if (mlkit) "https://developers.google.com/ml-kit/terms" else "https://policies.google.com/terms", // 약관 URL
            commercial = CommercialStatus.CONDITIONAL, // 상업 이용 판정
            compatAlias = null, // PC 평가기 별칭 없음
            engineConfig = SystemEngineConfig(kind, speed), // 엔진 설정
        )
    }
}
