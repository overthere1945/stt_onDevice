// 파일명: SttCsvHeader.kt
// 목적: PC 평가기(stt_common.py)의 STT_CSV_HEADER 열 정의를 앱에서 그대로 사용
// 기능: 실행 식별~결과까지 60개 열 이름을 PC 와 같은 순서로 보관한다. 열 순서를 바꾸면 PC 분석/평가 스크립트가 깨지므로 변경 금지.
package com.cowork.stt_ondevice.output // 출력 패키지

// change(add)-hyungchul-2026-10-10-12:08
/**
 * 목적: PC 도구와 동일한 CSV 헤더 제공
 * 기능: /home/hyungchul/workspace/python/asr_stt/stt_all_gui_2nd/stt_common.py 94-180행 STT_CSV_HEADER 를 순서 그대로 옮겼다.
 *       (brief 는 59열로 적었으나 PC 원본의 실제 열 수는 60개이며, 원본을 그대로 따른다.)
 *       앱이 측정하지 못하는 열은 행에 값을 넣지 않아 빈 칸으로 남긴다(0·추정치로 채우지 않음).
 */
val STT_CSV_HEADER: List<String> = listOf(
    // --- A. 실행 식별 ---
    "run_id", // 한 번의 실행(배치)을 구분하는 ID. 예: 20260819_093012
    "started_at", // 이 파일의 처리를 시작한 시각 (로컬 시간, ISO8601)
    "algo", // 엔진 이름. azure_cloud / azure_embedded / clova / soniox / google / faster-whisper
    "model", // 모델 이름. 모델 개념이 없으면 default
    "exec_mode", // cloud(클라우드 API) / server(사내 서버) / embedded(로컬 온디바이스)
    "engine_version", // 사용한 SDK/라이브러리 버전 (재현성 확보용)
    // --- B. 입력 파일 ---
    "rel_dir", // input_folder 기준 하위 폴더 (최상위면 빈 문자열)
    "file", // 원본 파일명 (확장자 포함)
    "audio_ext", // 원본 확장자 (.wav / .mp3 ...)
    "audio_bytes", // 원본 파일 크기(바이트)
    "audio_sec", // 음원 길이(초). RTF와 비용 계산의 기준값입니다.
    "sample_rate", // 원본 샘플레이트(Hz)
    "channels", // 원본 채널 수 (1=mono, 2=stereo)
    // --- C. 언어 / 인식 옵션 ---
    "req_lang", // 사용자가 요청한 언어 (ko-KR / en-US / ko / auto ...)
    "detected_lang", // 엔진이 감지한 언어 (지원하지 않으면 빈 문자열)
    "task", // transcribe / translate (whisper 계열만 의미 있음)
    "diarization", // on / off / unsupported / dropped(...)  화자 분리 상태
    "num_speakers", // 실제로 잡힌 화자 수 (없으면 0)
    "adaptation", // on / off / unsupported / dropped(...)  구문목록(Phrase List) 상태
    "phrase_count", // 실제로 전달한 구문(키워드) 개수
    "phrase_sets", // 구문을 나눠 담은 PhraseSet 개수 (Google 전용, 없으면 0)
    // --- D. 실행 환경 / 자원 사용 ---
    "device", // cuda:0 / cpu / remote(클라우드 처리라 로컬 장치 무관)
    "device_name", // GPU 모델명 또는 CPU 모델명
    "compute_type", // int8 / float16 / float32 등 연산 정밀도 (로컬 엔진만)
    "workers", // 이 실행의 동시 처리 스레드 수 (시간 해석에 반드시 필요)
    "cpu_percent_avg", // 전사 구간 동안의 프로세스 평균 CPU 사용률(%). 100%=코어 1개 완전 사용
    "cpu_time_sec", // 전사 구간 동안 프로세스가 실제로 쓴 CPU 시간(초, user+sys)
    "rss_peak_mb", // 전사 구간 동안의 프로세스 최대 메모리(MB)
    "gpu_util_avg", // 전사 구간 동안의 GPU 평균 사용률(%)
    "gpu_mem_peak_mb", // 전사 구간 동안의 GPU 최대 사용 메모리(MB)
    "gpu_energy_wh", // 전사 구간 동안 GPU가 소비한 전력량(Wh) = 평균전력 x 시간
    // --- E. 시간 (Latency) ---
    "preprocess_sec", // 오디오 변환/리샘플(ffmpeg 등)에 쓴 시간
    "upload_sec", // [진단용] 클라우드 업로드 구간 (분리 불가 엔진/로컬 엔진은 0 = 미측정)
    "wait_sec", // [진단용] 폴링 sleep 누적치. transcribe_sec 안에 이미 포함됨(중복 합산 금지)
    "transcribe_sec", // 엔진이 보고하는 전사 구간 (엔진마다 계측 범위가 달라 단독 비교는 주의)
    "cloud_total_sec", // ★ upload_sec + transcribe_sec. 엔진 간 비교의 핵심 지표입니다.
    "elapsed_sec", // 이 파일을 처리하는 데 걸린 전체 시간(재시도 포함)
    "rtf", // Real Time Factor = cloud_total_sec / audio_sec (작을수록 빠름)
    "attempts", // 실제 시도 횟수 (1이면 재시도 없음)
    // --- F. 비용 ---
    "billed_unit", // 과금 단위 문자열. second / 15second / minute / hour / token
    "billed_qty", // 과금 단위 기준 수량 (올림 규칙 적용 후)
    "unit_price", // 단위당 단가 (pricing.json 기준)
    "currency", // USD / KRW
    "cost", // billed_qty x unit_price. 단가가 없으면 빈 문자열
    "price_ref", // 단가의 출처와 기준일 (근거 추적용)
    // --- G. 엔진 고유 열 (합집합) ---
    "api", // Google: V1 / V2
    "model_sent", // Google: API에 실제로 보낸 model 문자열
    "enhanced", // Google: use_enhanced 적용 여부
    "location", // Google: V2 location (V1은 global)
    "recognition_mode", // Google: sync / sync_chunked / v1_longrunning_gcs / v2_batch_gcs
    "chunks", // Google: API 호출에 사용한 조각 수
    "model_version", // Azure Embedded: 설치된 모델 버전
    "locales", // Azure Embedded: 모델이 지원하는 locale 목록
    // --- H. 결과 ---
    "status", // OK / NO_MATCH / ERROR (대문자로 통일)
    "utterances", // 인식된 발화(세그먼트) 수
    "chars", // 전사문 글자 수
    "words", // 전사문 어절(공백 기준) 수
    "text_sha1", // 전사문 SHA1 앞 12자리 (같은 결과인지 빠르게 비교)
    "note", // 기능 생략/자동 해제/경고 등 부가 설명
    "text", // 전사문 (한 줄로 정리). 열이 길어 마지막에 둡니다.
)
