// 파일명: ModelCatalogData.kt
// 목적: Phase 1 ASR·VAD 모델 카탈로그 데이터 (자동 생성 파일 — 직접 고치지 말 것)
// 기능: tools/hash_models.py --emit-kotlin 이 tools/model_sources.json(사람이 관리하는 원본 목록)과
//       실제 다운로드·압축 해제 결과(파일별 bytes·SHA-256)를 합쳐 만든다. 내용을 바꾸려면 원본 목록을 고치고 스크립트를 다시 실행한다.
//       라이선스 확인일 2026-10-10. 근거 URL 은 model/README_카탈로그.md 참고.
package com.cowork.stt_ondevice.model // 모델 메타 패키지

import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열(VAD 모델 항목)

// change(add)-hyungchul-2026-10-10-16:12
/**
 * 목적: 카탈로그 원본 데이터 보관
 * 기능: 모든 ModelSpec 을 원본 목록 순서대로 담는다. 조회는 ModelCatalog 를 쓴다.
 */
internal object ModelCatalogData {
    /** 목적: 전체 모델 목록 / 기능: ASR → VAD 순서의 고정 목록(파일 SHA-256 은 실제 다운로드로 계산한 값) */
    val all: List<ModelSpec> = listOf( // 전체 목록
        // ── whispercpp-tiny: ggerganov/whisper.cpp(카드 license: mit) 커밋 5359861 의 ggml-tiny.bin, 원본 openai/whisper-tiny(MIT), 다국어 모델
        ModelSpec( // 모델 1개
            id = "whispercpp-tiny", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "whispercpp", // 결과 폴더 algo
            model = "tiny", // 결과 폴더 model
            displayName = "Whisper tiny (whisper.cpp f16)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.WHISPER_CPP, // 실행 런타임
            runtimeVersion = "1.9.5", // 런타임 버전
            precision = "f16", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-tiny.bin", 77691713L, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("ggml-tiny.bin", 77691713L, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21", "model"), // 역할 model
            ),
            originalRepo = "https://huggingface.co/openai/whisper-tiny", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "faster-whisper", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = WhisperConfig(beamSize = 5), // 엔진 설정
        ),
        // ── whispercpp-base: ggerganov/whisper.cpp(카드 license: mit) 커밋 5359861 의 ggml-base.bin, 원본 openai/whisper-base(MIT), 다국어 모델
        ModelSpec( // 모델 1개
            id = "whispercpp-base", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "whispercpp", // 결과 폴더 algo
            model = "base", // 결과 폴더 model
            displayName = "Whisper base (whisper.cpp f16)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.WHISPER_CPP, // 실행 런타임
            runtimeVersion = "1.9.5", // 런타임 버전
            precision = "f16", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.bin", 147951465L, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("ggml-base.bin", 147951465L, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe", "model"), // 역할 model
            ),
            originalRepo = "https://huggingface.co/openai/whisper-base", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "faster-whisper", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = WhisperConfig(beamSize = 5), // 엔진 설정
        ),
        // ── whispercpp-small: ggerganov/whisper.cpp(카드 license: mit) 커밋 5359861 의 ggml-small.bin, 원본 openai/whisper-small(MIT), 다국어 모델
        ModelSpec( // 모델 1개
            id = "whispercpp-small", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "whispercpp", // 결과 폴더 algo
            model = "small", // 결과 폴더 model
            displayName = "Whisper small (whisper.cpp f16)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.WHISPER_CPP, // 실행 런타임
            runtimeVersion = "1.9.5", // 런타임 버전
            precision = "f16", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-small.bin", 487601967L, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("ggml-small.bin", 487601967L, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b", "model"), // 역할 model
            ),
            originalRepo = "https://huggingface.co/openai/whisper-small", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "faster-whisper", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = WhisperConfig(beamSize = 5), // 엔진 설정
        ),
        // ── whispercpp-medium: ggerganov/whisper.cpp(카드 license: mit) 커밋 5359861 의 ggml-medium.bin, 원본 openai/whisper-medium(MIT), 다국어 모델
        ModelSpec( // 모델 1개
            id = "whispercpp-medium", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "whispercpp", // 결과 폴더 algo
            model = "medium", // 결과 폴더 model
            displayName = "Whisper medium (whisper.cpp f16)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.WHISPER_CPP, // 실행 런타임
            runtimeVersion = "1.9.5", // 런타임 버전
            precision = "f16", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-medium.bin", 1533763059L, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("ggml-medium.bin", 1533763059L, "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208", "model"), // 역할 model
            ),
            originalRepo = "https://huggingface.co/openai/whisper-medium", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "faster-whisper", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = WhisperConfig(beamSize = 5), // 엔진 설정
        ),
        // ── whispercpp-large-v3-turbo-q5_0: ggerganov/whisper.cpp(카드 license: mit) 커밋 5359861 의 ggml-large-v3-turbo-q5_0.bin, 원본 openai/whisper-large-v3-turbo(MIT), 다국어 모델
        ModelSpec( // 모델 1개
            id = "whispercpp-large-v3-turbo-q5_0", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "whispercpp", // 결과 폴더 algo
            model = "large-v3-turbo-q5_0", // 결과 폴더 model
            displayName = "Whisper large-v3-turbo-q5_0 (whisper.cpp q5_0)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.WHISPER_CPP, // 실행 런타임
            runtimeVersion = "1.9.5", // 런타임 버전
            precision = "q5_0", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q5_0.bin", 574041195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("ggml-large-v3-turbo-q5_0.bin", 574041195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", "model"), // 역할 model
            ),
            originalRepo = "https://huggingface.co/openai/whisper-large-v3-turbo", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "faster-whisper", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = WhisperConfig(beamSize = 5), // 엔진 설정
        ),
        // ── whispercpp-large-v3-turbo-q8_0: ggerganov/whisper.cpp(카드 license: mit) 커밋 5359861 의 ggml-large-v3-turbo-q8_0.bin, 원본 openai/whisper-large-v3-turbo(MIT), 다국어 모델
        ModelSpec( // 모델 1개
            id = "whispercpp-large-v3-turbo-q8_0", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "whispercpp", // 결과 폴더 algo
            model = "large-v3-turbo-q8_0", // 결과 폴더 model
            displayName = "Whisper large-v3-turbo-q8_0 (whisper.cpp q8_0)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.WHISPER_CPP, // 실행 런타임
            runtimeVersion = "1.9.5", // 런타임 버전
            precision = "q8_0", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-large-v3-turbo-q8_0.bin", 874188075L, "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("ggml-large-v3-turbo-q8_0.bin", 874188075L, "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", "model"), // 역할 model
            ),
            originalRepo = "https://huggingface.co/openai/whisper-large-v3-turbo", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://github.com/openai/whisper/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "faster-whisper", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = WhisperConfig(beamSize = 5), // 엔진 설정
        ),
        // ── sherpa-zipformer-ko-2024-06-24: KsponSpeech 학습 icefall zipformer 의 sherpa 변환본(k2-fsa/sherpa-onnx-zipformer-korean-2024-06-24). 인코더·조이너 int8 + 디코더 fp32(sherpa 공식 예제 조합)
        //    압축 안 제외 파일 9개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-zipformer-ko-2024-06-24", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-zipformer", // 결과 폴더 algo
            model = "ko-2024-06-24", // 결과 폴더 model
            displayName = "Zipformer KO 2024-06-24 (int8)", // 화면 표시 이름
            languages = setOf("ko"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_OFFLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-korean-2024-06-24.tar.bz2", 329740690L, "24bd409318f389cd2de0e295eb1acf91f4e8dfcc0d650490dd2a01f5b50d2c77", ArchiveType.TAR_BZ2, "sherpa-onnx-zipformer-korean-2024-06-24/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 70784728L, "8b196d723421a0513c98ec25da2c43420c029e817f5e4a90b29ff80291c0af2b", "encoder"), // 역할 encoder
                ModelFile("decoder-epoch-99-avg-1.onnx", 11309084L, "b486221625f4680659c21eaf0505c50e64a897583af4f9ca6f87dea762a33885", "decoder"), // 역할 decoder
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 2581421L, "eb654db1ea2cc9d63474855f65958b6059084692a9f2eb4f3812aceb1e416a20", "joiner"), // 역할 joiner
                ModelFile("tokens.txt", 60246L, "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668", "tokens"), // 역할 tokens
            ),
            originalRepo = "https://huggingface.co/johnBamma/icefall-asr-ksponspeech-zipformer-2024-06-24", // 원본 저장소
            license = "Apache-2.0(원본 icefall 카드) / 학습데이터 KsponSpeech(AI Hub) 이용조건 미확인", // 라이선스
            licenseUrl = "https://huggingface.co/johnBamma/icefall-asr-ksponspeech-zipformer-2024-06-24", // 라이선스 근거 URL
            commercial = CommercialStatus.UNVERIFIED, // 상업 이용 판정
            compatAlias = "sherpa-onnx", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.ZIPFORMER_TRANSDUCER), // 엔진 설정
        ),
        // ── sherpa-zipformer-en-2023-06-26: LibriSpeech(CC BY 4.0) 학습 icefall zipformer(Zengwei/icefall-asr-librispeech-zipformer-2023-05-15) 의 sherpa 변환본. 선택 이유: 한국어 zipformer 와 같은 구조·같은 파일 구성(인코더·조이너 int8 + 디코더 fp32)의 공식 EN offline transducer 중 변환본 카드(csukuangfj/sherpa-onnx-zipformer-en-2023-06-26)에 license: apache-2.0 이 명시된 표준 크기 모델
        //    압축 안 제외 파일 10개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-zipformer-en-2023-06-26", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-zipformer", // 결과 폴더 algo
            model = "en-2023-06-26", // 결과 폴더 model
            displayName = "Zipformer EN 2023-06-26 (int8)", // 화면 표시 이름
            languages = setOf("en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_OFFLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-en-2023-06-26.tar.bz2", 307666046L, "652181d80dfe0e8e0659cf32036d1d6ef56fa2f1ab5b35c54d0cee8a663e8954", ArchiveType.TAR_BZ2, "sherpa-onnx-zipformer-en-2023-06-26/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 68778564L, "52a48f46c17b19a36fe3927c4d59479bb16eeb2493313ed82c4bf775c2cb8bc8", "encoder"), // 역할 encoder
                ModelFile("decoder-epoch-99-avg-1.onnx", 2092621L, "8740937f38aeb155e7ccc0623fe4053d10b9db36a0d4f264e79d7a6debe2246a", "decoder"), // 역할 decoder
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 259335L, "48de5d6467a2ab1e72cb5c4d828330be06524d877bc458118b6a4198ca031357", "joiner"), // 역할 joiner
                ModelFile("tokens.txt", 5048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb", "tokens"), // 역할 tokens
            ),
            originalRepo = "https://huggingface.co/Zengwei/icefall-asr-librispeech-zipformer-2023-05-15", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-en-2023-06-26", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "sherpa-onnx", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.ZIPFORMER_TRANSDUCER), // 엔진 설정
        ),
        // ── sherpa-zipformer-streaming-ko-2024-06-16: KsponSpeech 학습 스트리밍 zipformer(v1) 의 sherpa 변환본(k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16). 인코더·조이너 int8 + 디코더 fp32, modelType zipformer
        //    압축 안 제외 파일 10개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-zipformer-streaming-ko-2024-06-16", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-zipformer-streaming", // 결과 폴더 algo
            model = "ko-2024-06-16", // 결과 폴더 model
            displayName = "Streaming Zipformer KO 2024-06-16 (int8)", // 화면 표시 이름
            languages = setOf("ko"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_ONLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.NATIVE_STREAMING, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-korean-2024-06-16.tar.bz2", 418218652L, "e346a5882a409650472be17326237e24df7bf409db6b4a8a52e1a61422bf2500", ArchiveType.TAR_BZ2, "sherpa-onnx-streaming-zipformer-korean-2024-06-16/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("encoder-epoch-99-avg-1.int8.onnx", 126968852L, "8d0b1aa24fbedd4e3948564ab7facd151b8ce9b0c48fc987c541de2de3af5697", "encoder"), // 역할 encoder
                ModelFile("decoder-epoch-99-avg-1.onnx", 11309084L, "b29cfb4575141e50a30a22b2c4579934f3d4f45b83c9c8c08c3aef5a3fa7abfc", "decoder"), // 역할 decoder
                ModelFile("joiner-epoch-99-avg-1.int8.onnx", 2581421L, "128b80a66a1f718488af8560f9d15895109b99ff3e573f0a0130e03774ef1ced", "joiner"), // 역할 joiner
                ModelFile("tokens.txt", 60246L, "016bdf0965029263b7ad01b742366ee542ef0bef38261510e8176ff6f2e9e668", "tokens"), // 역할 tokens
            ),
            originalRepo = "https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12", // 원본 저장소
            license = "Apache-2.0(원본 icefall 카드) / 학습데이터 KsponSpeech(AI Hub) 이용조건 미확인", // 라이선스
            licenseUrl = "https://huggingface.co/johnBamma/icefall-asr-ksponspeech-pruned-transducer-stateless7-streaming-2024-06-12", // 라이선스 근거 URL
            commercial = CommercialStatus.UNVERIFIED, // 상업 이용 판정
            compatAlias = "sherpa-onnx", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.STREAMING_ZIPFORMER), // 엔진 설정
        ),
        // ── sherpa-sensevoice-small-int8-2024-07-17: FunAudioLLM/SenseVoiceSmall 원본의 sherpa int8 변환본(2024-07-17). 더 최신인 2025-09-09 int8 은 ASLP-lab/WSYue-ASR sensevoice_small_yue(광둥어 미세조정본) 변환본이라 원본 기준 ko/en 비교에서 제외. 조건: 출처·저자 표기와 모델 이름 유지(FunASR Model License 1.1 §2.2)
        //    압축 안 제외 파일 8개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-sensevoice-small-int8-2024-07-17", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-sensevoice", // 결과 폴더 algo
            model = "small-int8", // 결과 폴더 model
            displayName = "SenseVoice Small (int8, 2024-07-17)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_OFFLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2", 163002883L, "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e", ArchiveType.TAR_BZ2, "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("model.int8.onnx", 239233841L, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51", "model"), // 역할 model
                ModelFile("tokens.txt", 315894L, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc", "tokens"), // 역할 tokens
            ),
            originalRepo = "https://huggingface.co/FunAudioLLM/SenseVoiceSmall", // 원본 저장소
            license = "FunASR Model License 1.1", // 라이선스
            licenseUrl = "https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.CONDITIONAL, // 상업 이용 판정
            compatAlias = "sensevoice", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.SENSEVOICE), // 엔진 설정
        ),
        // ── sherpa-qwen3asr-0.6b-int8-2026-03-25: Qwen3-ASR-0.6B sherpa 변환본(conv_frontend fp32 + encoder/decoder int8). tokenizer 는 폴더(tokenizer/)를 통째로 넘긴다
        //    압축 안 제외 파일 18개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-qwen3asr-0.6b-int8-2026-03-25", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-qwen3asr", // 결과 폴더 algo
            model = "0.6B-int8", // 결과 폴더 model
            displayName = "Qwen3-ASR 0.6B (int8)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_OFFLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2", 878702423L, "393f8a14e2f5fb96746aaab342997a40641001fbd5bf9592a080a8329178ee96", ArchiveType.TAR_BZ2, "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("conv_frontend.onnx", 44148281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e", "convFrontend"), // 역할 convFrontend
                ModelFile("encoder.int8.onnx", 182491662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9", "encoder"), // 역할 encoder
                ModelFile("decoder.int8.onnx", 755914231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771", "decoder"), // 역할 decoder
                ModelFile("tokenizer/merges.txt", 1671853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5", "tokenizer"), // 역할 tokenizer
                ModelFile("tokenizer/tokenizer_config.json", 12487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c", "tokenizer"), // 역할 tokenizer
                ModelFile("tokenizer/vocab.json", 2776833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910", "tokenizer"), // 역할 tokenizer
            ),
            originalRepo = "https://huggingface.co/Qwen/Qwen3-ASR-0.6B", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://huggingface.co/Qwen/Qwen3-ASR-0.6B", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "qwen3_asr", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.QWEN3_ASR), // 엔진 설정
        ),
        // ── sherpa-omnilingual-ctc300m-v1-int8-2025-11-12: Meta Omnilingual ASR CTC sherpa int8 변환본(sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12)
        //    압축 안 제외 파일 6개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-omnilingual-ctc300m-v1-int8-2025-11-12", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-omnilingual", // 결과 폴더 algo
            model = "ctc300m-v1-int8", // 결과 폴더 model
            displayName = "Omnilingual CTC 300M v1 (int8)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_OFFLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12.tar.bz2", 292571207L, "cdcd0559c7c73efed54209a926e321afc914d046c5fdbf3665f00dc78180e5ed", ArchiveType.TAR_BZ2, "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("model.int8.onnx", 365352120L, "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c", "model"), // 역할 model
                ModelFile("tokens.txt", 86423L, "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31", "tokens"), // 역할 tokens
            ),
            originalRepo = "https://huggingface.co/facebook/omniASR-CTC-300M", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://github.com/facebookresearch/omnilingual-asr/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "omnilingual", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.OMNILINGUAL_CTC), // 엔진 설정
        ),
        // ── sherpa-omnilingual-ctc300m-v2-int8-2026-02-05: Meta Omnilingual ASR CTC sherpa int8 변환본(sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05)
        //    압축 안 제외 파일 6개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-omnilingual-ctc300m-v2-int8-2026-02-05", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-omnilingual", // 결과 폴더 algo
            model = "ctc300m-v2-int8", // 결과 폴더 model
            displayName = "Omnilingual CTC 300M v2 (int8)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_OFFLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2", 292313120L, "951b32409aade32bd525310bb39e9666773ba3fc611a39e817f620936d76c631", ArchiveType.TAR_BZ2, "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("model.int8.onnx", 365841453L, "e3042b2f3b3ef0af2211bf99d2b4bf94a21f5ac0e9898827e7dd6d003a860e91", "model"), // 역할 model
                ModelFile("tokens.txt", 90630L, "7d99997ef207ff14c2cfe825f2aa037528ea250113cc3c6392bfe49326884ba6", "tokens"), // 역할 tokens
            ),
            originalRepo = "https://github.com/facebookresearch/omnilingual-asr", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://github.com/facebookresearch/omnilingual-asr/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "omnilingual", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.OMNILINGUAL_CTC), // 엔진 설정
        ),
        // ── sherpa-omnilingual-ctc1b-v2-int8-2026-02-05: Meta Omnilingual ASR CTC sherpa int8 변환본(sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-v2-int8-2026-02-05)
        //    압축 안 제외 파일 6개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "sherpa-omnilingual-ctc1b-v2-int8-2026-02-05", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "sherpa-omnilingual", // 결과 폴더 algo
            model = "ctc1b-v2-int8", // 결과 폴더 model
            displayName = "Omnilingual CTC 1B v2 (int8)", // 화면 표시 이름
            languages = setOf("ko", "en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.SHERPA_OFFLINE, // 실행 런타임
            runtimeVersion = "1.13.8", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 30.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-v2-int8-2026-02-05.tar.bz2", 787296506L, "f4deae6e6cbf4ca785b89eaa3836156581208bf977ea2e6d7ae84d7efcfc3a40", ArchiveType.TAR_BZ2, "sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-v2-int8-2026-02-05/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("model.int8.onnx", 1032239439L, "8af72da192fc2c8567c328d4f8059bdf47f182a0369077893c295ef39740c637", "model"), // 역할 model
                ModelFile("tokens.txt", 90630L, "7d99997ef207ff14c2cfe825f2aa037528ea250113cc3c6392bfe49326884ba6", "tokens"), // 역할 tokens
            ),
            originalRepo = "https://github.com/facebookresearch/omnilingual-asr", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://github.com/facebookresearch/omnilingual-asr/blob/main/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "omnilingual", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = SherpaConfig(SherpaKind.OMNILINGUAL_CTC), // 엔진 설정
        ),
        // ── vosk-small-ko-0.22: alphacephei 공식 모델 목록(https://alphacephei.com/vosk/models) License 열 'Apache 2.0'. Kaldi 모델 폴더 전체를 Model(path) 로 연다(README 만 제외)
        //    압축 안 제외 파일 1개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "vosk-small-ko-0.22", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "vosk", // 결과 폴더 algo
            model = "small-ko-0.22", // 결과 폴더 model
            displayName = "Vosk small KO 0.22", // 화면 표시 이름
            languages = setOf("ko"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.VOSK, // 실행 런타임
            runtimeVersion = "0.3.75", // 런타임 버전
            precision = "fp32", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.NATIVE_STREAMING, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip", 86914329L, "eea36124087fed26c59996a4761519458e3bd185e8ea9d9865ad8760c4a1d989", ArchiveType.ZIP, "vosk-model-small-ko-0.22/"), // 압축 ZIP
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("am/final.mdl", 14356670L, "de1a0b3beaad1f266b18d9af624a8d2451ac32e500a575cec9826d415ecfd0b4", "modelDir"), // 역할 modelDir
                ModelFile("conf/mfcc.conf", 128L, "cdb0d516e5b8886092f1d840c628107703a65b571dc3b16b888a9a3978958b96", "modelDir"), // 역할 modelDir
                ModelFile("conf/model.conf", 289L, "dd7178da0e99fdbe489816c1e9702c3daa28e02accacc2993e0becb7af33b80c", "modelDir"), // 역할 modelDir
                ModelFile("graph/Gr.fst", 48498089L, "b13d06a65c73b4f32a7618844fbe62aaa2fd250bea63c124c40ec2a8b5c5735e", "modelDir"), // 역할 modelDir
                ModelFile("graph/HCLr.fst", 181533466L, "862822d277b283b16b293aca397e14d6e0bc460723004d966dace532db4110d0", "modelDir"), // 역할 modelDir
                ModelFile("graph/disambig_tid.int", 132L, "f393757ce5a99ce66ab60bb8aa89f6ac3ceff737f27e9d5d11fc60893d7aba64", "modelDir"), // 역할 modelDir
                ModelFile("graph/phones.txt", 1917L, "ca2c642d315ed11525c2004e95b688f3dd29a8bdd7735649babfef438702e42a", "modelDir"), // 역할 modelDir
                ModelFile("graph/phones/word_boundary.int", 2076L, "6de0418fb4dc7c42d0e9c75c906a239235830bc8415eb96a40099d204586d0ab", "modelDir"), // 역할 modelDir
                ModelFile("ivector/final.dubm", 168048L, "8dcb7b76385f6fe1a5e876185452261e218a9a25eb17d87b5b86e4fbb14ddce3", "modelDir"), // 역할 modelDir
                ModelFile("ivector/final.ie", 19757687L, "888bdd4437dfbd476e6939099c7af81de85a3c2d50217db0c311f67db0a66fed", "modelDir"), // 역할 modelDir
                ModelFile("ivector/final.mat", 44975L, "022aec6ec7f135cb494f89aabbccb9ab27653468d01a77ad480433de69f76990", "modelDir"), // 역할 modelDir
                ModelFile("ivector/global_cmvn.stats", 1060L, "397ea1dfa402d0ea434924b181fc70202777656bef5b4f37c9cf2ac96e6e74a9", "modelDir"), // 역할 modelDir
                ModelFile("ivector/online_cmvn.conf", 96L, "4cfe3a77bd2420961704432ba67201f242ef36ff97803c88275dcead7f4acd36", "modelDir"), // 역할 modelDir
                ModelFile("ivector/splice.conf", 35L, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756", "modelDir"), // 역할 modelDir
            ),
            originalRepo = "https://alphacephei.com/vosk/models", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://alphacephei.com/vosk/models", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "vosk", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VoskConfig, // 엔진 설정
        ),
        // ── vosk-small-en-us-0.15: alphacephei 공식 모델 목록(https://alphacephei.com/vosk/models) License 열 'Apache 2.0'. Kaldi 모델 폴더 전체를 Model(path) 로 연다(README 만 제외)
        //    압축 안 제외 파일 1개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "vosk-small-en-us-0.15", // 고유 id
            kind = ModelKind.ASR, // ASR/VAD
            algo = "vosk", // 결과 폴더 algo
            model = "small-en-us-0.15", // 결과 폴더 model
            displayName = "Vosk small EN-US 0.15", // 화면 표시 이름
            languages = setOf("en"), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.VOSK, // 실행 런타임
            runtimeVersion = "0.3.75", // 런타임 버전
            precision = "fp32", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.NATIVE_STREAMING, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 41205931L, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", ArchiveType.ZIP, "vosk-model-small-en-us-0.15/"), // 압축 ZIP
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("am/final.mdl", 15962575L, "75370a0137f9daf8f469dedd7daa4513ae7a621f03240c6e512e2b50b656a7b6", "modelDir"), // 역할 modelDir
                ModelFile("conf/mfcc.conf", 131L, "1e2228006d01d805ad1c267fee9f79709ca87ac51bd82b0e3f5c69ba543f0fc4", "modelDir"), // 역할 modelDir
                ModelFile("conf/model.conf", 290L, "8f14cb1eeb07c762c371db648c6be688d347236155ca0f64fb13b6567a8ce81f", "modelDir"), // 역할 modelDir
                ModelFile("graph/Gr.fst", 24013795L, "023c8b7e30704a9e37765c635c252e608a02f361235bf94abdcf2a5225d85b20", "modelDir"), // 역할 modelDir
                ModelFile("graph/HCLr.fst", 22416994L, "5caafba3081e1646545ac6bff0dd7a318e53dcbdc86f237909ce1d2ac1293d34", "modelDir"), // 역할 modelDir
                ModelFile("graph/disambig_tid.int", 102L, "9ad87cc166d0998f08f758f47a6223a120dfffcbee805c4849c3aa5e6bb3c0fc", "modelDir"), // 역할 modelDir
                ModelFile("graph/phones/word_boundary.int", 1761L, "da199d9c991e0e84681ddbb34627b915b26302d50a8fdaa23c51e2bc3a50b5c3", "modelDir"), // 역할 modelDir
                ModelFile("ivector/final.dubm", 168048L, "8c5d7dd69d2122313baaf19f61f35dd3fa18b70c62ac0687e311e1c46e6daca7", "modelDir"), // 역할 modelDir
                ModelFile("ivector/final.ie", 8288887L, "3f37faf90c375b9e4740b569398b5829ed9cc07d19be6d441f72c3b71d7efcc6", "modelDir"), // 역할 modelDir
                ModelFile("ivector/final.mat", 44975L, "ddd83586dc5f928cda8738b922c85ffe38fc789cb5f9151a712ca12f37265382", "modelDir"), // 역할 modelDir
                ModelFile("ivector/global_cmvn.stats", 1080L, "33be09afcc80059847a275c3d043b51f1ab954c7c2438ddbbf4745e8ba144ff9", "modelDir"), // 역할 modelDir
                ModelFile("ivector/online_cmvn.conf", 95L, "a2f3571754b64297cb7efb2e7ca3df61995c5a45fcbb97188f90613552bb2dfe", "modelDir"), // 역할 modelDir
                ModelFile("ivector/splice.conf", 35L, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756", "modelDir"), // 역할 modelDir
            ),
            originalRepo = "https://alphacephei.com/vosk/models", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://alphacephei.com/vosk/models", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = "vosk", // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VoskConfig, // 엔진 설정
        ),
        // ── vad-silero-v6.2.3: 공식 저장소 태그 v6.2.3(커밋 5cd7945) 의 silero_vad.onnx, 골든 도구 해시와 교차 확인
        ModelSpec( // 모델 1개
            id = "vad-silero-v6.2.3", // 고유 id
            kind = ModelKind.VAD, // ASR/VAD
            algo = "vad-silero", // 결과 폴더 algo
            model = "v6.2.3", // 결과 폴더 model
            displayName = "Silero VAD v6.2.3", // 화면 표시 이름
            languages = emptySet(), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.ORT, // 실행 런타임
            runtimeVersion = "1.30.0", // 런타임 버전
            precision = "fp32", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/snakers4/silero-vad/raw/v6.2.3/src/silero_vad/data/silero_vad.onnx", 2327524L, "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("silero_vad.onnx", 2327524L, "1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3", "model"), // 역할 model
            ),
            originalRepo = "https://github.com/snakers4/silero-vad", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://github.com/snakers4/silero-vad/blob/v6.2.3/LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = null, // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VadModelConfig(VadFamily.SILERO), // 엔진 설정
        ),
        // ── vad-pyannote-seg3.0: 원본 HF gated(MIT), sherpa-onnx 변환본(speaker-segmentation-models) 의 fp32 model.onnx 사용
        //    압축 안 제외 파일 10개(테스트 음원·README·스크립트·미사용 정밀도 등)
        ModelSpec( // 모델 1개
            id = "vad-pyannote-seg3.0", // 고유 id
            kind = ModelKind.VAD, // ASR/VAD
            algo = "vad-pyannote", // 결과 폴더 algo
            model = "seg3.0", // 결과 폴더 model
            displayName = "pyannote segmentation-3.0", // 화면 표시 이름
            languages = emptySet(), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.ORT, // 실행 런타임
            runtimeVersion = "1.30.0", // 런타임 버전
            precision = "fp32", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", 6958444L, "24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488", ArchiveType.TAR_BZ2, "sherpa-onnx-pyannote-segmentation-3-0/"), // 압축 TAR_BZ2
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("model.onnx", 5992913L, "220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079", "model"), // 역할 model
            ),
            originalRepo = "https://huggingface.co/pyannote/segmentation-3.0", // 원본 저장소
            license = "MIT", // 라이선스
            licenseUrl = "https://huggingface.co/pyannote/segmentation-3.0", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = null, // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VadModelConfig(VadFamily.PYANNOTE), // 엔진 설정
        ),
        // ── vad-fsmn-zh-cn-16k-onnx: modelscope iic/speech_fsmn_vad_zh-cn-16k-common-onnx 태그 v2.0.6(= master 와 같은 해시) 3파일. modelscope 메타 License 표기는 Apache License 2.0 이나 FunASR 모델은 FunASR Model License 1.1 적용 → 보수적으로 조건부. 조건: 출처·모델 이름 유지
        ModelSpec( // 모델 1개
            id = "vad-fsmn-zh-cn-16k-onnx", // 고유 id
            kind = ModelKind.VAD, // ASR/VAD
            algo = "vad-fsmn", // 결과 폴더 algo
            model = "zh-cn-16k-onnx", // 결과 폴더 model
            displayName = "FSMN-VAD (FunASR, int8)", // 화면 표시 이름
            languages = emptySet(), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.ORT, // 실행 런타임
            runtimeVersion = "1.30.0", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/v2.0.6/model_quant.onnx", 506744L, "5289eb2aa3c9af2d7a4284bcfa7c3ceb81d360814ed4203239b6c5d0569da8a1", ArchiveType.NONE, ""), // 압축 NONE
                Download("https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/v2.0.6/am.mvn", 8040L, "6820fef9687708c4fc3fab2530179c8fcea6262daa25514380056cd8f6eb1754", ArchiveType.NONE, ""), // 압축 NONE
                Download("https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx/resolve/v2.0.6/config.yaml", 1215L, "2ef334f2d7776edd86ed696296774f87550206c256bfabc597872ad861831589", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("model_quant.onnx", 506744L, "5289eb2aa3c9af2d7a4284bcfa7c3ceb81d360814ed4203239b6c5d0569da8a1", "model"), // 역할 model
                ModelFile("am.mvn", 8040L, "6820fef9687708c4fc3fab2530179c8fcea6262daa25514380056cd8f6eb1754", "cmvn"), // 역할 cmvn
                ModelFile("config.yaml", 1215L, "2ef334f2d7776edd86ed696296774f87550206c256bfabc597872ad861831589", "config"), // 역할 config
            ),
            originalRepo = "https://www.modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-onnx", // 원본 저장소
            license = "FunASR Model License 1.1", // 라이선스
            licenseUrl = "https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE", // 라이선스 근거 URL
            commercial = CommercialStatus.CONDITIONAL, // 상업 이용 판정
            compatAlias = null, // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VadModelConfig(VadFamily.FSMN), // 엔진 설정
        ),
        // ── vad-yamnet-tflite-v1: Google 공식 TFLite 예제 저장소의 yamnet classification tflite v1(Kaggle google/yamnet tfLite classification-tflite v1 의 1.tflite 와 같은 해시). 내부 int8 MobileNet
        ModelSpec( // 모델 1개
            id = "vad-yamnet-tflite-v1", // 고유 id
            kind = ModelKind.VAD, // ASR/VAD
            algo = "vad-yamnet", // 결과 폴더 algo
            model = "tflite-v1", // 결과 폴더 model
            displayName = "Yamnet (LiteRT)", // 화면 표시 이름
            languages = emptySet(), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.LITERT, // 실행 런타임
            runtimeVersion = "2.3.0", // 런타임 버전
            precision = "int8", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = listOf( // 내려받을 artifact
                Download("https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/audio_classification/android/lite-model_yamnet_classification_tflite_1.tflite", 4126810L, "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de", ArchiveType.NONE, ""), // 압축 NONE
            ),
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("yamnet.tflite", 4126810L, "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de", "model"), // 역할 model
            ),
            originalRepo = "https://www.kaggle.com/models/google/yamnet", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://www.kaggle.com/models/google/yamnet", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = null, // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VadModelConfig(VadFamily.YAMNET), // 엔진 설정
        ),
        // ── vad-firered-7990aac: HF FireRedTeam/FireRedVAD revision 7990aac 를 PC 에서 공식 export_onnx.py 로 변환(tools/export). 고지(Xiaohongshu FireRedTeam) 유지
        //    다운로드 없음 = import 전용(PC 에서 변환한 산출물을 adb/SAF 로 넣는다)
        ModelSpec( // 모델 1개
            id = "vad-firered-7990aac", // 고유 id
            kind = ModelKind.VAD, // ASR/VAD
            algo = "vad-firered", // 결과 폴더 algo
            model = "7990aac", // 결과 폴더 model
            displayName = "FireRedVAD (ONNX export)", // 화면 표시 이름
            languages = emptySet(), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.ORT, // 실행 런타임
            runtimeVersion = "1.30.0", // 런타임 버전
            precision = "fp32", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = emptyList(), // import 전용
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("fireredvad_vad.onnx", 2392980L, "4adc9f0120affe96f6f570c9c8672230c670d5b15ec30b4b6a450f316ab4c9ab", "modelOffline"), // 역할 modelOffline
                ModelFile("fireredvad_stream_vad.onnx", 2302864L, "e3444c6dd25c4a313a7560f517d64f6df6f1c45e3d25fb051863b4e2549ba2e3", "modelStream"), // 역할 modelStream
                ModelFile("cmvn.ark", 1311L, "c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473", "cmvn"), // 역할 cmvn
            ),
            originalRepo = "https://huggingface.co/FireRedTeam/FireRedVAD", // 원본 저장소
            license = "Apache-2.0", // 라이선스
            licenseUrl = "https://huggingface.co/FireRedTeam/FireRedVAD", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = null, // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VadModelConfig(VadFamily.FIRERED), // 엔진 설정
        ),
        // ── vad-marblenet-v2.0: HF nvidia/Frame_VAD_Multilingual_MarbleNet_v2.0 revision 9d25eb8 를 PC 에서 NeMo 로 export(tools/export). 조건: 라이선스 고지 유지·이용 제한 준수
        //    다운로드 없음 = import 전용(PC 에서 변환한 산출물을 adb/SAF 로 넣는다)
        ModelSpec( // 모델 1개
            id = "vad-marblenet-v2.0", // 고유 id
            kind = ModelKind.VAD, // ASR/VAD
            algo = "vad-marblenet", // 결과 폴더 algo
            model = "v2.0", // 결과 폴더 model
            displayName = "MarbleNet v2.0 (ONNX export)", // 화면 표시 이름
            languages = emptySet(), // 지원 언어(VAD 는 언어 무관 = 빈 집합)
            runtime = Runtime.ORT, // 실행 런타임
            runtimeVersion = "1.30.0", // 런타임 버전
            precision = "fp32", // 가중치 정밀도(파일 이름 기준)
            maxChunkSec = 0.0, // 1회 입력 최대 길이(초, 0 = 제한 없음/스트리밍)
            streaming = StreamingKind.FILE_ONLY, // 파일 단위/네이티브 스트리밍
            downloads = emptyList(), // import 전용
            files = listOf( // 설치 후 검사할 파일(경로는 모델 폴더 기준)
                ModelFile("frame_vad_multilingual_marblenet_v2.0.onnx", 430446L, "738e1713a45bcf5b1c46195eeb1edb94b960a10f1bf2fbabe5e8973626433739", "model"), // 역할 model
                ModelFile("marblenet_preprocessor.json", 179986L, "0e821a6ac90279935794d2f3627be1cfe6f98309195418849070093949d207b3", "preprocessor"), // 역할 preprocessor
            ),
            originalRepo = "https://huggingface.co/nvidia/Frame_VAD_Multilingual_MarbleNet_v2.0", // 원본 저장소
            license = "NVIDIA Open Model License", // 라이선스
            licenseUrl = "https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/", // 라이선스 근거 URL
            commercial = CommercialStatus.ALLOWED, // 상업 이용 판정
            compatAlias = null, // PC 평가기 폴더 별칭(없으면 null)
            engineConfig = VadModelConfig(VadFamily.MARBLENET), // 엔진 설정
        ),
    )
}
