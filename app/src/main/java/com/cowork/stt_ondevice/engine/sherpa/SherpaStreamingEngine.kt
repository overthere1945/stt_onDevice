// 파일명: SherpaStreamingEngine.kt
// 목적: sherpa-onnx OnlineRecognizer(v1.13.8)로 chunk 를 스트리밍 방식으로 전사하는 AsrEngine 구현 (streaming zipformer KO, maxChunkSec 0)
// 기능: prepare — 스펙의 모든 파일 존재 확인 → SherpaConfigs.online 로 설정 → OnlineRecognizer 생성(모델 로드), 시간·버전 측정.
//       transcribe — 언어 검사 → 준비 확인 → chunk 마다 새 스트림(= chunk 별 초기화)을 만들어
//                    0.1 s(1600 샘플) 단위로 acceptWaveform → isReady 인 동안 decode 를 반복하고,
//                    끝에 0.8 s 0 패딩(마지막 프레임까지 인코더에 밀어 넣기) + inputFinished() 뒤 isReady 가 false 가 될 때까지 decode,
//                    getResult 의 text 를 최종 결과로 쓴다(rawText = 원문, text = 공백 정리본, detectedLang = null).
//                    끝점 검출은 설정에서 꺼 두었고 reset 하지 않으므로 chunk 전체가 한 결과로 이어진다.
//       cancel — 중단 플래그를 켠다. 0.1 s 블록마다·decode 호출마다 확인하므로 중단 지연은 decode 1회 시간 수준이다.
//       close — 새 작업 거부 → 중단 요청 → 진행 중 전사가 끝나기를 기다린 뒤 인식기를 1회만 해제(반복 호출 안전).
//       잠금: 생성·전사·해제를 직렬화한다(SherpaOfflineEngine·WhisperCppEngine 과 같은 방식).
//       change(add)-hyungchul-2026-10-10-21:32 리뷰 수정: 생명주기(준비·잠금·중단 플래그·해제)와 예외 매핑은 SherpaEngineCore/SherpaEngineSupport 로 옮기고
//         이 파일에는 스트리밍 입력·decode 단계만 남김(동작 동일, 중단 요청 소실 경합만 수정).
// change(add)-hyungchul-2026-10-10-20:57
package com.cowork.stt_ondevice.engine.sherpa // sherpa 엔진 패키지

import com.cowork.stt_ondevice.engine.AsrEngine // 엔진 계약
import com.cowork.stt_ondevice.engine.ChunkText // 전사 결과
import com.cowork.stt_ondevice.engine.EngineException // 엔진 오류
import com.cowork.stt_ondevice.engine.PrepareInfo // 준비 결과
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.Runtime // 런타임 구분
import com.cowork.stt_ondevice.model.SherpaKind // sherpa 모델 종류
import com.cowork.stt_ondevice.segment.AudioChunk // 엔진 입력 chunk
import com.k2fsa.sherpa.onnx.OnlineRecognizer // sherpa 스트리밍 인식기(복사한 API)
import com.k2fsa.sherpa.onnx.OnlineStream // sherpa 스트리밍 스트림
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-20:57
/**
 * 목적: sherpa-onnx 스트리밍 엔진
 * 기능: 위 파일 머리 설명 참조.
 * 입력: spec(runtime = SHERPA_ONLINE, engineConfig = SherpaConfig(STREAMING_ZIPFORMER)), modelDir(설치 폴더 models/<id>)
 * 출력: 스펙이 맞지 않으면 EngineException(MODEL_LOAD, CONFIG_MISMATCH)
 */
class SherpaStreamingEngine(
    override val spec: ModelSpec, // 모델 메타
    val modelDir: File, // 모델 폴더
) : AsrEngine {

    private val kind: SherpaKind = SherpaConfigs.kindOf(spec) // sherpa 모델 종류(SherpaConfig 아니면 CONFIG_MISMATCH)
    private val core = SherpaEngineCore<OnlineRecognizer>(spec) { it.release() } // change(add)-hyungchul-2026-10-10-21:32 인식기 생명주기(잠금·중단·해제)

    init {
        if (spec.runtime != Runtime.SHERPA_ONLINE || kind != SherpaKind.STREAMING_ZIPFORMER) { // 스트리밍 스펙이 아님
            throw EngineException("MODEL_LOAD", "CONFIG_MISMATCH", "sherpa 스트리밍 스펙이 아님(runtime=${spec.runtime}, kind=$kind): ${spec.id}") // 거부
        }
    }

    /**
     * 목적: 모델 로드
     * 기능: SherpaEngineCore.load 로 스레드 수·닫힘·중복 준비·파일 존재 확인 → SherpaConfigs.online 설정 → OnlineRecognizer 생성(시간 측정).
     * 입력: threads(추론 스레드 수, 1 이상)
     * 출력: threads < 1 → IllegalArgumentException,
     *       그 밖 → EngineException(MODEL_LOAD, CLOSED/ALREADY_PREPARED/MODEL_FILE_MISSING/CONFIG_MISMATCH/OOM/NATIVE_LIB_MISSING/LOAD_FAILED)
     * 리턴: PrepareInfo(loadSec, sherpa 버전, "CPU", spec.precision, threads)
     */
    override suspend fun prepare(threads: Int): PrepareInfo {
        val loadSec = core.load( // change(add)-hyungchul-2026-10-10-21:32 공통 준비 절차
            threads, modelDir, // 스레드 수·모델 폴더
            configure = { SherpaConfigs.online(spec, modelDir, threads) }, // 설정(역할 → 필드)
            create = { OnlineRecognizer(config = it) }, // newFromFile(설정 검증 실패 시 ptr 0 → IllegalArgumentException)
        )
        return SherpaEngineSupport.prepareInfo(spec, loadSec, threads) // 준비 결과
    }

    /**
     * 목적: chunk 1개 스트리밍 전사
     * 기능: 언어 검사 → SherpaEngineCore.transcribe(준비 확인·잠금·예외 매핑) 안에서 새 스트림 → feed(블록 입력·decode, 꼬리 패딩·마무리) → getResult,
     *       시간 측정, 스트림 해제.
     * 입력: chunk(16 kHz mono float 샘플), lang(언어 코드, spec.languages 안이어야 함)
     * 출력: EngineException(TRANSCRIBE, UNSUPPORTED_LANGUAGE/NOT_PREPARED/ABORTED/OOM/NATIVE_ERROR)
     * 리턴: ChunkText(text, rawText, detectedLang = null, inferSec)
     */
    override suspend fun transcribe(chunk: AudioChunk, lang: String): ChunkText {
        if (!supports(lang)) throw EngineException("TRANSCRIBE", "UNSUPPORTED_LANGUAGE", "${spec.id} 는 언어 '$lang' 미지원(지원: ${spec.languages})") // 언어 검사(네이티브 전)
        return core.transcribe(chunk) { r -> // change(add)-hyungchul-2026-10-10-21:32 공통 잠금·예외 매핑 안에서
            val t0 = System.nanoTime() // 시작 시각
            val stream = r.createStream() // chunk 전용 새 스트림(= chunk 별 초기화)
            try { // 스트림은 반드시 해제
                feed(r, stream, chunk) // 입력·decode
                val res = r.getResult(stream) // 최종 결과
                val inferSec = (System.nanoTime() - t0) / 1e9 // 추론 시간(초)
                ChunkText(text = SherpaEngineSupport.normalizeSpaces(res.text), rawText = res.text, detectedLang = null, inferSec = inferSec) // 결과
            } finally { // 성공·실패 모두
                stream.release() // 네이티브 스트림 해제
            }
        }
    }

    /**
     * 목적: 스트림에 chunk 를 흘려 넣고 끝까지 decode
     * 기능: 0.1 s 블록마다 acceptWaveform → isReady 동안 decode, 이어서 0.8 s 0 패딩 + inputFinished → isReady 가 false 가 될 때까지 decode.
     *       블록·decode 마다 중단 요청을 확인한다(core 잠금 안에서만 호출).
     * 입력: r(인식기), stream(이 chunk 의 새 스트림), chunk(오디오)
     * 출력: 중단 요청 시 EngineException(TRANSCRIBE, ABORTED)
     * 리턴: 없음
     */
    private fun feed(r: OnlineRecognizer, stream: OnlineStream, chunk: AudioChunk) {
        val samples = chunk.samples // 입력 샘플
        var off = 0 // 다음 블록 시작 위치
        while (off < samples.size) { // 블록마다
            core.checkCancel(chunk) // 블록 전 중단 확인
            val end = minOf(off + BLOCK_SAMPLES, samples.size) // 블록 끝(마지막은 짧을 수 있음)
            stream.acceptWaveform(samples.copyOfRange(off, end), SherpaConfigs.SAMPLE_RATE) // 0.1 s 입력
            decodeWhileReady(r, stream, chunk) // 준비된 프레임 decode
            off = end // 다음 블록
        }
        core.checkCancel(chunk) // 마무리 전 중단 확인
        stream.acceptWaveform(FloatArray(TAIL_SAMPLES), SherpaConfigs.SAMPLE_RATE) // 0.8 s 0 패딩(마지막 음성 프레임까지 처리되게)
        stream.inputFinished() // 입력 끝 알림
        decodeWhileReady(r, stream, chunk) // 남은 프레임 모두 decode
    }

    /**
     * 목적: 준비된 프레임 모두 decode
     * 기능: isReady 가 true 인 동안 중단 확인 후 decode 를 반복한다.
     * 입력: r(인식기), stream(스트림), chunk(오류 메시지용)
     * 출력: 중단 요청 시 EngineException(TRANSCRIBE, ABORTED)
     * 리턴: 없음
     */
    private fun decodeWhileReady(r: OnlineRecognizer, stream: OnlineStream, chunk: AudioChunk) {
        while (r.isReady(stream)) { // 처리할 프레임이 있는 동안
            core.checkCancel(chunk) // decode 전 중단 확인
            r.decode(stream) // 네이티브 decode 1회
        }
    }

    /**
     * 목적: 진행 중 전사 중단 요청
     * 기능: 중단 플래그를 켠다(다른 스레드에서 불러도 안전, 준비 전·해제 후에도 무해).
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun cancel() {
        core.cancel() // change(add)-hyungchul-2026-10-10-21:32 공통 플래그 켜기
    }

    /**
     * 목적: 모델 해제(반복 호출 안전)
     * 기능: SherpaEngineCore.close — 새 작업 거부 → 중단 요청 → 진행 중 전사·로드 종료 대기 → 인식기 1회만 release.
     * 입력: 없음
     * 출력: 없음
     * 리턴: 없음
     */
    override fun close() {
        core.close() // change(add)-hyungchul-2026-10-10-21:32 공통 해제
    }

    companion object {
        const val BLOCK_SAMPLES = 1600 // 입력 블록 0.1 s(16 kHz)
        const val TAIL_SAMPLES = 12800 // 꼬리 0 패딩 0.8 s(16 kHz)
    }
}
