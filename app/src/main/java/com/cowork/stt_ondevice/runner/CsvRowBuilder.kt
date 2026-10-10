// 파일명: CsvRowBuilder.kt
// 목적: 파일 1건의 상세 JSON(DetailJson)으로 PC 평가기 호환 result.csv 한 행(spec §11 매핑)을 만든다
// 기능: 행을 항상 DetailJson 에서만 만든다 — 정상 실행과 재개 시 행 복구가 같은 함수를 써서 같은 행이 나온다.
//       텍스트 통계(chars/words/text_sha1)는 PC stt_common.text_stats 와 같다(공백류를 공백 1개로 정리·양끝 trim 후 길이(공백 포함)/공백 분할 수/SHA-1 앞 12자).
//       숫자 서식은 PC build_row 와 같다(시간 3자리, rss 1자리, rtf 4자리, Locale.ROOT). 측정하지 못한 값은 빈 칸.
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.core.device.DeviceInfo // 기기 정보
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import com.cowork.stt_ondevice.output.ChunkJson // chunk 결과
import com.cowork.stt_ondevice.output.DetailJson // 상세 JSON
import com.cowork.stt_ondevice.output.ErrorJson // 오류 JSON
import java.security.MessageDigest // SHA-1
import java.util.Locale // 숫자 서식 로케일

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: SHA-1 16진 문자열
 * 기능: 바이트 배열의 SHA-1 을 소문자 16진 40자로 만든다(음원 캐시 키·text_sha1 공용).
 * 입력: bytes(대상 바이트)
 * 출력: 없음
 * 리턴: 16진 문자열
 */
internal fun sha1Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) } // 바이트마다 2자리 16진

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: CSV 행 생성기
 * 기능: build()(60열 행), summaryRow()(요약 CSV 용 + vad 열), fullText()(chunk 텍스트 → 전체 텍스트), note()(note 열)를 제공한다.
 */
internal object CsvRowBuilder {

    // change(add)-hyungchul-2026-10-11-02:40: "(?U)\\s+" 는 안드로이드(ICU 정규식)에서 PatternSyntaxException(UNICODE_CHARACTER_CLASS 미지원)이라
    //   기기에서 이 object 초기화가 실패해 모든 항목의 CSV 쓰기가 ExceptionInInitializerError 로 실행 전체를 멈췄다(Task 27 기기 테스트에서 발견).
    //   JVM·ICU 공통으로 같은 결과가 나오도록 Python str.isspace() 집합을 문자 클래스로 직접 적는다:
    //   \t \n \u000B \f \r, \u001C–\u001F(정보 구분자), 공백, \u0085(NEL), \u00A0(NBSP), \u1680, \u2000–\u200A, \u2028, \u2029, \u202F, \u205F, \u3000
    private val WHITESPACE = Regex("[\\t\\n\\u000B\\f\\r\\u001C-\\u001F \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+") // 유니코드 공백류 연속(Python str 의 \s·split 과 동일) // change(add)-hyungchul-2026-10-11-02:40

    /**
     * 목적: PC one_line 과 같은 한 줄 정리
     * 기능: 공백류(줄바꿈·탭·연속 공백)를 공백 1개로 바꾸고 양끝을 다듬는다.
     * 입력: s(원문)
     * 출력: 없음
     * 리턴: 한 줄 문자열
     */
    fun oneLine(s: String): String = s.replace(WHITESPACE, " ").trim() // 공백 정리

    /**
     * 목적: 전체 텍스트 생성
     * 기능: chunk 텍스트를 한 줄로 정리하고 빈 것은 빼고 공백 1개로 잇는다.
     * 입력: chunks(chunk 결과 목록)
     * 출력: 없음
     * 리턴: 전체 텍스트(chunk 가 없으면 "")
     */
    fun fullText(chunks: List<ChunkJson>): String =
        chunks.map { oneLine(it.text) }.filter { it.isNotEmpty() }.joinToString(" ") // 정리 → 빈 것 제외 → 연결

    /**
     * 목적: note 열 생성
     * 기능: "vad=<preset>;chunk_mode=<MERGE|INDIVIDUAL>;max_chunk=<sec>;alias=<별칭 또는 빈 값>", 오류면 ";stage=..;code=.." 를 덧붙인다.
     * 입력: combo(조합), error(오류, 없으면 null)
     * 출력: 없음
     * 리턴: note 문자열
     */
    fun note(combo: Combo, error: ErrorJson?): String {
        val base = "vad=${combo.preset.id};chunk_mode=${combo.chunkMode.name};max_chunk=${combo.maxChunkSec};alias=${combo.spec.compatAlias.orEmpty()}" // 기본 설명
        return if (error == null) base else "$base;stage=${error.stage};code=${error.code}" // 오류면 stage/code 추가
    }

    /**
     * 목적: 고정 소수 자릿수 문자열(PC _fmt 와 동일)
     * 기능: null 이면 null(빈 칸), 아니면 Locale.ROOT 로 digits 자리 서식.
     * 입력: v(값), digits(소수 자릿수)
     * 출력: 없음
     * 리턴: 문자열 또는 null
     */
    private fun fmt(v: Double?, digits: Int): String? = v?.let { String.format(Locale.ROOT, "%.${digits}f", it) } // 서식화

    /**
     * 목적: device_name 열 값
     * 기능: SoC 를 알면 "모델 (SoC)", 모르면 모델명.
     * 입력: device(기기 정보)
     * 출력: 없음
     * 리턴: 문자열
     */
    private fun deviceName(device: DeviceInfo): String =
        device.socModel?.let { "${device.model} ($it)" } ?: device.model // SoC 있으면 괄호로 덧붙임

    /**
     * 목적: result.csv 한 행 생성(spec §11)
     * 기능: DetailJson 의 측정값과 조합·항목·기기 정보를 STT_CSV_HEADER 열 이름으로 매핑한다. 맵에 없는 열은 빈 칸이 된다.
     *       ERROR 행은 텍스트·통계를 비우고 utterances=0. task 는 whisper.cpp 만 "transcribe". cloud_total_sec = transcribe_sec.
     *       engine_version/compute_type/workers 는 prepare 가 보고한 값을 쓰고, 모델 로드 전 실패면 각각 카탈로그 버전/빈 칸/설정 스레드 수.
     * 입력: runId(실행 ID), combo(조합), item(입력 항목), detail(상세 JSON), device(기기), threads(설정 스레드 수)
     * 출력: 없음
     * 리턴: 열 이름 → 값(null 은 빈 칸)
     */
    fun build(runId: String, combo: Combo, item: InputItem, detail: DetailJson, device: DeviceInfo, threads: Int): Map<String, String?> {
        val isError = detail.status == "ERROR" // 오류 행 여부
        val text = if (isError) "" else fullText(detail.chunks) // 오류 행은 텍스트 없음
        val chars = text.codePointCount(0, text.length) // 글자 수(공백 포함, Python len 과 같이 코드포인트 단위)
        val words = if (text.isEmpty()) 0 else text.split(' ').size // 어절 수(정리된 텍스트라 공백 1개 구분)
        val sha = if (text.isEmpty()) "" else sha1Hex(text.toByteArray(Charsets.UTF_8)).take(12) // SHA-1 앞 12자
        val spec = combo.spec // 모델
        return mapOf(
            "run_id" to runId, // 실행 ID
            "started_at" to detail.startedAt, // 파일 처리 시작 시각
            "algo" to spec.algo, // 엔진 이름
            "model" to spec.model, // 모델 이름
            "exec_mode" to "embedded", // 온디바이스
            "engine_version" to (detail.engine?.runtimeVersion ?: spec.runtimeVersion), // 런타임 버전
            "rel_dir" to item.relDir, // 상대 폴더
            "file" to detail.file, // 원본 파일명
            "audio_ext" to "." + detail.file.substringAfterLast('.', "").lowercase(), // 확장자(PC 는 소문자)
            "audio_bytes" to detail.audioBytes?.toString(), // 파일 크기
            "audio_sec" to fmt(detail.audioSec, 3), // 음원 길이
            "sample_rate" to detail.sampleRate?.toString(), // 원본 샘플레이트
            "channels" to detail.channels?.toString(), // 원본 채널 수
            "req_lang" to item.engineLang, // 요청 언어
            "detected_lang" to detail.detectedLang, // 엔진 감지 언어
            "task" to if (spec.runtime == Runtime.WHISPER_CPP) "transcribe" else null, // whisper 만
            "device" to "cpu", // 실행 장치
            "device_name" to deviceName(device), // 기기 이름
            "compute_type" to detail.engine?.precision, // 실제 정밀도
            "workers" to (detail.engine?.threads ?: threads).toString(), // 스레드 수
            "cpu_time_sec" to fmt(detail.cpuTimeSec, 3), // CPU 시간
            "rss_peak_mb" to fmt(detail.pssPeakMb, 1), // PSS 피크
            "preprocess_sec" to fmt(detail.preprocessSec, 3), // 전처리 시간
            "transcribe_sec" to fmt(detail.transcribeSec, 3), // 전사 시간
            "cloud_total_sec" to fmt(detail.transcribeSec, 3), // 로컬은 업로드 0 → transcribe 와 같음
            "elapsed_sec" to fmt(detail.elapsedSec, 3), // 파일 전체 시간
            "rtf" to fmt(detail.rtf, 4), // 실시간 배율
            "attempts" to "1", // 재시도 없음
            "status" to detail.status, // 상태
            "utterances" to (if (isError) 0 else detail.chunks.size).toString(), // chunk 수
            "chars" to chars.toString(), // 글자 수
            "words" to words.toString(), // 어절 수
            "text_sha1" to sha, // 텍스트 해시
            "note" to note(combo, detail.error), // 부가 설명
            "text" to text, // 전사문
        )
    }

    /**
     * 목적: 요약 CSV 행 생성
     * 기능: build() 결과에 "vad" 열(프리셋 id)을 더한다.
     * 입력: row(build 결과), combo(조합)
     * 출력: 없음
     * 리턴: 열 이름 → 값
     */
    fun summaryRow(row: Map<String, String?>, combo: Combo): Map<String, String?> = row + ("vad" to combo.preset.id) // vad 열 추가
}
