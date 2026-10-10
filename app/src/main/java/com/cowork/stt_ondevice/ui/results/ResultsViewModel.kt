// 파일명: ui/results/ResultsViewModel.kt
// 목적: 결과 화면의 상태(run 목록·조합별 집계·파일 목록·전사 미리보기)와 이어하기(resume) 실행을 담당하는 ViewModel
// 기능: - run 목록: output_stt 아래 폴더를 이름(=runId yyyyMMdd_HHmmss) 내림차순(최신 먼저)으로 읽고, run_manifest.json 을 RunManifest.read 로 읽는다
//         (없으면 "manifest 없음", 읽을 수 없으면 "manifest 손상"). 시작·종료 시각, 완료/미완료, 미지원 조합 목록을 보인다.
//       - 조합별 집계: _results/<lang>/<combo>/result.csv 의 status 열을 OK/NO_MATCH/ERROR/그 밖으로 센다(CSV 는 CsvFiles.readRecords — BOM·따옴표·따옴표 안 줄바꿈 처리).
//       - 드릴다운: 조합 → result.csv 행(파일) → 전사 txt 미리보기(ERROR 는 <stem>.ERROR.txt). 너무 긴 파일은 앞부분만 읽는다.
//       - 이어하기: 미완료 run 의 manifest 로 RunConfig(같은 runId, resume=true)를 복원한다. 모델·프리셋·입력 파일 중 하나라도 되찾지 못하면
//         한국어 사유를 보이고 시작하지 않는다(다른 것으로 대체하지 않음). 시작은 주입한 함수(앱: RunService.start)로 하고, 실패 사유를 메시지로 보인다.
//         BatchRunner 가 manifest 와의 동일성을 다시 검사하며, 그 실패는 진행 화면의 실패 상태로 보인다.
// change(add)-hyungchul-2026-10-11-03:31
package com.cowork.stt_ondevice.ui.results // 결과 화면 패키지

import androidx.lifecycle.ViewModel // 뷰모델 기본 클래스
import androidx.lifecycle.ViewModelProvider // 뷰모델 팩토리 인터페이스
import androidx.lifecycle.viewModelScope // 뷰모델 코루틴 범위
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.core.storage.InputItem // 입력 항목
import com.cowork.stt_ondevice.core.storage.InputScanner // 언어 폴더 목록
import com.cowork.stt_ondevice.model.InstallState // 설치 상태
import com.cowork.stt_ondevice.model.ModelCatalog // 모델 카탈로그
import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.model.ModelStore // 모델 저장소
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.output.RunManifest // 실행 manifest
import com.cowork.stt_ondevice.runner.CsvFiles // 결과 CSV 읽기 도우미
import com.cowork.stt_ondevice.runner.RunConfig // 실행 설정
import com.cowork.stt_ondevice.runner.RunService // 배치 실행 서비스
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.ui.models.koreanLabel // 설치 상태 한국어 라벨
import com.cowork.stt_ondevice.ui.run.RunSetupViewModel // 시작 실패 사유 문구
import com.cowork.stt_ondevice.vad.VadFamily // VAD 계열
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋
import com.cowork.stt_ondevice.vad.VadPresets // VAD 프리셋 레지스트리
import kotlinx.coroutines.CoroutineDispatcher // 디스패처 타입
import kotlinx.coroutines.Dispatchers // 디스패처
import kotlinx.coroutines.flow.MutableStateFlow // 변경 가능한 상태 흐름
import kotlinx.coroutines.flow.StateFlow // 읽기 전용 상태 흐름
import kotlinx.coroutines.flow.asStateFlow // 읽기 전용 변환
import kotlinx.coroutines.flow.update // 원자적 갱신
import kotlinx.coroutines.launch // 코루틴 시작
import kotlinx.coroutines.withContext // 컨텍스트 전환
import java.io.File // 파일
import java.io.IOException // 입출력 오류
import java.io.RandomAccessFile // 앞부분만 읽기

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: run 폴더의 manifest 상태
 * 기능: OK(정상), MISSING(파일 없음), CORRUPT(읽기·해석 실패)와 한국어 라벨.
 * 입력: label(화면 표시 문구)
 */
enum class ManifestStatus(val label: String) {
    OK("정상"), // 읽음
    MISSING("manifest 없음"), // 파일 없음
    CORRUPT("manifest 손상"), // 읽기 실패
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 조합 1개의 결과 집계
 * 기능: result.csv 의 status 열을 센 값. 읽기에 실패하면 readError 에 사유(건수는 0).
 * 입력: runId(실행 ID), lang(언어 폴더), combo(조합명 <algo>_<model>_<VAD>), ok/noMatch/error/other(상태별 건수), readError(읽기 실패 사유 또는 null)
 */
data class ComboResult(
    val runId: String, // 실행 ID
    val lang: String, // 언어 폴더
    val combo: String, // 조합명
    val ok: Int, // OK 건수
    val noMatch: Int, // NO_MATCH 건수
    val error: Int, // ERROR 건수
    val other: Int, // 그 밖의 상태 건수
    val readError: String? = null, // CSV 읽기 실패 사유
)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: run 1개의 목록 행
 * 기능: manifest(있으면)와 조합별 집계를 묶고, 화면에 필요한 파생 값(시작·종료 시각, 완료 여부, 이어하기 가능 여부, 미지원 목록)을 준다.
 * 입력: runId(폴더 이름), dir(run 폴더), manifestStatus(manifest 상태), manifest(읽은 값 또는 null), combos(조합별 집계)
 */
data class RunEntry(
    val runId: String, // 실행 ID
    val dir: File, // run 폴더
    val manifestStatus: ManifestStatus, // manifest 상태
    val manifest: RunManifest?, // manifest
    val combos: List<ComboResult>, // 조합별 집계
) {
    /** 시작 시각(manifest 없으면 null). */
    val startedAt: String? get() = manifest?.startedAt // 시작 시각

    /** 종료 시각(미완료·manifest 없음이면 null). */
    val finishedAt: String? get() = manifest?.finishedAt // 종료 시각

    /** 완료 여부. */
    val finished: Boolean get() = finishedAt != null // 완료

    /** 이어하기 버튼을 보일지(manifest 를 읽었고 미완료). */
    val resumable: Boolean get() = manifest != null && manifest.finishedAt == null // 이어하기 가능

    /** 미지원 조합 "combo|lang" 목록. */
    val unsupported: List<String> get() = manifest?.unsupported ?: emptyList() // 미지원
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 조합 안의 파일 1건(result.csv 1행)
 * 기능: rel_dir·file·status 열과 파일명에서 뽑은 stem.
 * 입력: relDir(상대 폴더), file(원본 파일명), stem(확장자 뺀 이름), status(상태)
 */
data class FileResult(val relDir: String, val file: String, val stem: String, val status: String)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 미리보기 대화상자 내용
 * 입력: title(제목 — 위치), text(본문)
 */
data class Preview(val title: String, val text: String)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 이어하기 설정 복원 결과
 * 기능: Ready(복원한 설정) 또는 Error(한국어 사유).
 */
sealed interface ResumePlan {
    /** 목적: 복원 성공. 입력: config(같은 runId, resume=true). */
    data class Ready(val config: RunConfig) : ResumePlan

    /** 목적: 복원 실패. 입력: message(한국어 사유). */
    data class Error(val message: String) : ResumePlan
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 결과 화면 상태
 * 입력: loading(목록 읽는 중), runs(최신 먼저), openRunId(펼친 run), openCombo(열린 조합), files(열린 조합의 파일), preview(미리보기),
 *       message(일회성 안내 — 화면이 보여 준 뒤 consumeMessage), resumeStarted(이어하기를 시작한 runId — 화면이 진행 탭으로 이동한 뒤 consumeResumeStarted)
 */
data class ResultsState(
    val loading: Boolean = false, // 읽는 중
    val runs: List<RunEntry> = emptyList(), // run 목록
    val openRunId: String? = null, // 펼친 run
    val openCombo: ComboResult? = null, // 열린 조합
    val files: List<FileResult> = emptyList(), // 파일 목록
    val preview: Preview? = null, // 미리보기
    val message: String? = null, // 안내 문구
    val resumeStarted: String? = null, // 이어하기 시작한 runId
)

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 결과 화면 ViewModel
 * 기능: 파일 머리말 참조.
 * 입력: outputRoot(output_stt 폴더), store(설치 상태 판정 — App 싱글턴), catalog(이어하기 모델 조회 대상, 기본 ModelCatalog.asr),
 *       presets(이어하기 프리셋 조회 대상, 기본 VadPresets.all), startRun(설정으로 실행 시작 — 성공 null, 실패 시 한국어 사유),
 *       io(파일 읽기 디스패처 — 테스트에서 교체), vadModelOf(계열 → 카탈로그 VAD 모델)
 */
class ResultsViewModel(
    private val outputRoot: File, // output_stt
    private val store: ModelStore, // 설치 상태
    private val catalog: List<ModelSpec> = ModelCatalog.asr, // 모델 목록
    private val presets: List<VadPreset> = VadPresets.all, // 프리셋 목록
    private val startRun: (RunConfig) -> String?, // 실행 시작
    private val io: CoroutineDispatcher = Dispatchers.IO, // 작업 디스패처
    private val vadModelOf: (VadFamily) -> ModelSpec? = ModelCatalog::vadModel, // 계열 → VAD 모델
) : ViewModel() {

    private val _state = MutableStateFlow(ResultsState()) // 내부 상태

    /** 목적: 화면 상태 */
    val state: StateFlow<ResultsState> = _state.asStateFlow() // 읽기 전용 노출

    init {
        refresh() // 첫 읽기
    }

    /**
     * 목적: run 목록 다시 읽기
     * 기능: io 에서 scanRuns 로 목록을 만들고 state.runs 에 넣는다. 열린 조합이 있으면 그 파일 목록도 다시 읽는다.
     * 입력: 없음
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun refresh() {
        _state.update { it.copy(loading = true) } // 읽는 중 표시
        viewModelScope.launch(io) {
            val runs = scanRuns(outputRoot) // 목록
            val combo = _state.value.openCombo // 열린 조합
            val files = combo?.let { c -> readComboFiles(File(outputRoot, c.runId), c.lang, c.combo) } // 파일 다시 읽기
            _state.update { s -> s.copy(loading = false, runs = runs, files = files ?: s.files) } // 반영
        }
    }

    /**
     * 목적: run 펼치기
     * 기능: 그 run 을 펼친다. 다른 run 이 열려 있었으면 그 조합·파일·미리보기는 닫는다(같은 run 이면 그대로).
     * 입력: runId(실행 ID)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun openRun(runId: String) {
        _state.update { s -> if (s.openRunId == runId) s else s.copy(openRunId = runId, openCombo = null, files = emptyList(), preview = null) } // 펼치기
    }

    /**
     * 목적: run 접기
     * 기능: 열린 run·조합·파일·미리보기를 닫는다.
     * 입력: 없음
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun closeRun() {
        _state.update { it.copy(openRunId = null, openCombo = null, files = emptyList(), preview = null) } // 닫기
    }

    /**
     * 목적: 조합 열기(파일 목록)
     * 기능: io 에서 그 조합의 result.csv 행을 읽어 files 에 넣는다. 읽기 실패는 메시지.
     * 입력: combo(조합 집계 행)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun openCombo(combo: ComboResult) {
        _state.update { it.copy(openCombo = combo, files = emptyList(), preview = null) } // 먼저 열기
        viewModelScope.launch(io) {
            val files = try {
                readComboFiles(File(outputRoot, combo.runId), combo.lang, combo.combo) // 파일 목록
            } catch (e: IOException) {
                _state.update { it.copy(message = "result.csv 를 읽지 못했습니다: ${e.message}") } // 실패 안내
                emptyList() // 빈 목록
            }
            _state.update { s -> if (s.openCombo == combo) s.copy(files = files) else s } // 그 사이 다른 조합을 열지 않았을 때만
        }
    }

    /**
     * 목적: 조합 닫기
     * 기능: 열린 조합·파일·미리보기를 닫는다.
     * 입력: 없음
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun closeCombo() {
        _state.update { it.copy(openCombo = null, files = emptyList(), preview = null) } // 닫기
    }

    /**
     * 목적: 파일 미리보기 열기
     * 기능: io 에서 previewOf 로 txt(ERROR 면 ERROR.txt) 앞부분을 읽어 preview 에 넣는다.
     * 입력: file(파일 행)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun openPreview(file: FileResult) {
        val combo = _state.value.openCombo ?: return // 열린 조합 없으면 무시
        viewModelScope.launch(io) {
            val p = previewOf(File(outputRoot, combo.runId), combo.lang, combo.combo, file) // 미리보기
            _state.update { it.copy(preview = p) } // 반영
        }
    }

    /**
     * 목적: 미리보기 닫기
     * 입력·출력: 없음 / state 갱신
     * 리턴: 없음
     */
    fun closePreview() {
        _state.update { it.copy(preview = null) } // 닫기
    }

    /**
     * 목적: 안내 문구 소비
     * 기능: 화면이 스낵바로 보인 뒤 부른다.
     * 입력·출력: 없음 / state 갱신
     * 리턴: 없음
     */
    fun consumeMessage() {
        _state.update { it.copy(message = null) } // 비우기
    }

    /**
     * 목적: 이어하기 시작 신호 소비
     * 기능: 화면이 진행 탭으로 이동한 뒤 부른다.
     * 입력·출력: 없음 / state 갱신
     * 리턴: 없음
     */
    fun consumeResumeStarted() {
        _state.update { it.copy(resumeStarted = null) } // 비우기
    }

    /**
     * 목적: 이어하기 설정 복원(이 ViewModel 의 의존성으로)
     * 기능: buildResumeConfig(동반 객체)를 output_stt/<runId> 에 적용한다. 파일을 읽으므로 io 에서 부르는 것이 좋다.
     * 입력: runId(실행 ID)
     * 출력: 없음
     * 리턴: ResumePlan
     */
    fun buildResumeConfig(runId: String): ResumePlan =
        buildResumeConfig(File(outputRoot, runId), catalog, presets, store::state, vadModelOf) // 복원

    /**
     * 목적: 이어하기 실행
     * 기능: io 에서 설정을 복원하고, 실패면 사유를 메시지로 보인다. 성공이면 startRun 을 부르고, 시작 실패면 그 사유를 메시지로,
     *       성공이면 resumeStarted 에 runId 를 넣는다(화면이 진행 탭으로 이동).
     * 입력: runId(실행 ID)
     * 출력: state 갱신
     * 리턴: 없음
     */
    fun resume(runId: String) {
        viewModelScope.launch {
            val plan = withContext(io) { buildResumeConfig(runId) } // 복원
            when (plan) {
                is ResumePlan.Error -> _state.update { it.copy(message = "이어하기 불가: ${plan.message}") } // 복원 실패
                is ResumePlan.Ready -> {
                    val failure = startRun(plan.config) // 시작
                    if (failure != null) _state.update { it.copy(message = "이어하기 시작 실패: $failure") } // 시작 실패
                    else _state.update { it.copy(message = "이어하기를 시작했습니다($runId)", resumeStarted = runId) } // 시작함
                }
            }
        }
    }

    companion object {
        private const val PREVIEW_LIMIT_BYTES = 64 * 1024 // 미리보기 최대 바이트

        /**
         * 목적: run 목록 만들기
         * 기능: outputRoot 바로 아래 폴더를 이름 내림차순(최신 먼저)으로, 각 폴더의 manifest 상태와 조합별 집계를 읽는다.
         * 입력: outputRoot(output_stt)
         * 출력: 없음
         * 리턴: RunEntry 목록
         */
        fun scanRuns(outputRoot: File): List<RunEntry> {
            val dirs = outputRoot.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList() // run 폴더(최신 먼저)
            return dirs.map { dir -> // 폴더마다
                val file = OutputLayout.manifest(dir) // run_manifest.json
                val manifest = RunManifest.read(file) // 읽기(실패 null)
                val status = when {
                    manifest != null -> ManifestStatus.OK // 정상
                    file.exists() -> ManifestStatus.CORRUPT // 있는데 못 읽음
                    else -> ManifestStatus.MISSING // 없음
                }
                RunEntry(dir.name, dir, status, manifest, readCombos(dir)) // 행
            }
        }

        /**
         * 목적: 조합별 집계 읽기
         * 기능: <run>/_results/<lang>/<combo>/result.csv 를 언어·조합 이름 순으로 읽어 status 를 센다. 읽기 실패는 readError 에 사유.
         * 입력: runDir(run 폴더)
         * 출력: 없음
         * 리턴: ComboResult 목록
         */
        fun readCombos(runDir: File): List<ComboResult> {
            val results = File(runDir, "_results") // 조합 CSV 루트
            val langs = results.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList() // 언어 폴더
            return langs.flatMap { langDir -> // 언어마다
                val combos = langDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList() // 조합 폴더
                combos.map { comboDir -> // 조합마다
                    val csv = OutputLayout.resultCsv(runDir, langDir.name, comboDir.name) // result.csv
                    try {
                        val statuses = CsvFiles.readRecords(csv).map { it["status"].orEmpty() } // status 열
                        ComboResult(
                            runId = runDir.name, lang = langDir.name, combo = comboDir.name, // 식별
                            ok = statuses.count { it == "OK" }, noMatch = statuses.count { it == "NO_MATCH" }, // OK·NO_MATCH
                            error = statuses.count { it == "ERROR" }, other = statuses.count { it != "OK" && it != "NO_MATCH" && it != "ERROR" }, // ERROR·그 밖
                        )
                    } catch (e: IOException) {
                        ComboResult(runDir.name, langDir.name, comboDir.name, 0, 0, 0, 0, "result.csv 읽기 실패: ${e.message}") // 읽기 실패
                    }
                }
            }
        }

        /**
         * 목적: 조합의 파일 목록 읽기
         * 기능: result.csv 각 행의 rel_dir·file·status 를 CSV 순서대로 돌려준다(stem = 마지막 '.' 앞).
         * 입력: runDir(run 폴더), lang(언어 폴더), combo(조합명)
         * 출력: 읽기 실패 시 IOException
         * 리턴: FileResult 목록
         */
        fun readComboFiles(runDir: File, lang: String, combo: String): List<FileResult> =
            CsvFiles.readRecords(OutputLayout.resultCsv(runDir, lang, combo)).map { r -> // 행마다
                val file = r["file"].orEmpty() // 파일명
                FileResult(r["rel_dir"].orEmpty(), file, file.substringBeforeLast('.'), r["status"].orEmpty()) // 행 값
            }

        /**
         * 목적: 파일 미리보기 만들기
         * 기능: ERROR 면 <stem>.ERROR.txt, 아니면 <stem>.txt 를 앞 64KB 까지 UTF-8 로 읽는다(더 길면 "…(이하 생략)").
         *       파일이 없으면 "파일 없음: 경로" 안내(NO_MATCH 인데 txt 가 없는 경우 등).
         * 입력: runDir(run 폴더), lang(언어 폴더), combo(조합명), file(파일 행)
         * 출력: 없음
         * 리턴: Preview
         */
        fun previewOf(runDir: File, lang: String, combo: String, file: FileResult): Preview {
            val item = InputItem(lang, file.relDir, file.stem, File(file.file)) // 경로 계산용 항목(원본 파일은 읽지 않음)
            val target = if (file.status == "ERROR") OutputLayout.errorFile(runDir, item, combo) else OutputLayout.transcript(runDir, item, combo) // 읽을 파일
            val title = listOf(lang, combo, file.relDir, target.name).filter { it.isNotEmpty() }.joinToString("/") // 제목(위치)
            if (!target.isFile) return Preview(title, "파일 없음: ${target.path}") // 없음
            return try {
                Preview(title, readHead(target)) // 앞부분
            } catch (e: IOException) {
                Preview(title, "읽기 실패: ${e.message}") // 실패
            }
        }

        /**
         * 목적: 파일 앞부분 읽기
         * 기능: 최대 64KB 를 읽어 UTF-8 로 풀고, 잘렸으면 끝에 생략 표시를 붙인다(잘린 마지막 글자는 대체 문자로 보일 수 있음).
         * 입력: f(파일)
         * 출력: 읽기 실패 시 IOException
         * 리턴: 문자열
         */
        private fun readHead(f: File): String = RandomAccessFile(f, "r").use { raf -> // 읽기 전용 열기
            val n = minOf(raf.length(), PREVIEW_LIMIT_BYTES.toLong()).toInt() // 읽을 길이
            val buf = ByteArray(n) // 버퍼
            raf.readFully(buf) // 읽기
            val text = String(buf, Charsets.UTF_8) // UTF-8 해석
            if (raf.length() > n) "$text\n…(이하 생략)" else text // 잘림 표시
        }

        /**
         * 목적: 이어하기 RunConfig 복원
         * 기능: manifest 를 읽어(실패 시 Error) 미완료인지, 입력 목록(inputs)이 기록됐는지 확인한 뒤
         *       모델(카탈로그 id, READY 필수) → 프리셋(id, 계열 VAD 모델 READY 필수 — NONE/WEBRTC/TEN 제외) → chunk 모드 → 입력 파일(inputRoot/<lang>/<relDir>/<file> 존재 필수)
         *       순으로 되찾아 RunConfig(같은 runId, threads·maxChunkOverride 그대로, resume=true)를 만든다. 하나라도 실패하면 한국어 사유의 Error(대체 없음).
         * 입력: runDir(run 폴더), catalog(모델 목록), presets(프리셋 목록), installState(모델 설치 상태 판정), vadModelOf(계열 → VAD 모델)
         * 출력: 없음
         * 리턴: ResumePlan
         */
        fun buildResumeConfig(
            runDir: File, catalog: List<ModelSpec>, presets: List<VadPreset>,
            installState: (ModelSpec) -> InstallState, vadModelOf: (VadFamily) -> ModelSpec?,
        ): ResumePlan {
            val m = RunManifest.read(OutputLayout.manifest(runDir)) ?: return ResumePlan.Error("run_manifest.json 을 읽을 수 없습니다(manifest 없음 또는 손상)") // manifest
            if (m.finishedAt != null) return ResumePlan.Error("이미 완료된 실행입니다(${m.finishedAt}) — 이어할 항목이 없습니다") // 완료 run
            val inputs = m.inputs ?: return ResumePlan.Error("이 실행의 manifest 에 입력 파일 목록이 기록돼 있지 않아(이전 버전) 같은 입력을 되찾을 수 없습니다") // 입력 미기록
            val models = m.models.map { e -> // 모델마다
                val spec = catalog.firstOrNull { it.id == e.id } ?: return ResumePlan.Error("카탈로그에 없는 모델입니다: ${e.id}") // 카탈로그 밖
                val st = installState(spec) // 설치 상태
                if (st != InstallState.READY) return ResumePlan.Error("모델 '${spec.displayName}' 이(가) ${st.koreanLabel()} 상태입니다(모델 탭에서 설치한 뒤 다시 시도)") // 미설치 등
                spec // 모델
            }
            val chosen = m.presets.map { e -> // 프리셋마다
                val p = presets.firstOrNull { it.id == e.id } ?: return ResumePlan.Error("알 수 없는 VAD 프리셋입니다: ${e.id}") // 레지스트리 밖
                if (p.family != VadFamily.NONE && p.family != VadFamily.WEBRTC && p.family != VadFamily.TEN) { // 모델이 필요한 계열
                    val vm = vadModelOf(p.family) // 계열 모델
                    if (vm == null || installState(vm) != InstallState.READY) return ResumePlan.Error("VAD 프리셋 ${p.id} 의 VAD 모델이 설치돼 있지 않습니다") // 미설치
                }
                p // 프리셋
            }
            val mode = ChunkMode.entries.firstOrNull { it.name == m.chunkMode } ?: return ResumePlan.Error("알 수 없는 chunk 모드입니다: ${m.chunkMode}") // 묶기 방식
            val root = File(m.inputRoot) // 입력 루트
            val items = inputs.map { e -> // 입력마다
                if (e.lang !in InputScanner.LANG_FOLDERS) return ResumePlan.Error("알 수 없는 언어 폴더입니다: ${e.lang}") // 언어 폴더 검사
                val langDir = File(root, e.lang) // 언어 폴더
                val f = File(if (e.relDir.isEmpty()) langDir else File(langDir, e.relDir), e.file) // 파일 경로
                if (!f.isFile) return ResumePlan.Error("입력 파일이 없습니다: ${f.path}") // 없음
                InputItem(e.lang, e.relDir, e.file.substringBeforeLast('.'), f) // 항목
            }
            return try {
                ResumePlan.Ready(RunConfig(m.runId, items, models, chosen, mode, m.maxChunkOverride, m.threads, resume = true)) // 설정
            } catch (e: IllegalArgumentException) {
                ResumePlan.Error("실행 설정을 만들 수 없습니다: ${e.message}") // 검증 실패
            }
        }

        /**
         * 목적: 앱 전역 객체로 ViewModel 팩토리 만들기
         * 기능: output 루트 = dirs.output, store = App 싱글턴, 시작 = RunService.start(실패 시 RunService.events 의 사유).
         * 입력: app(App 인스턴스)
         * 출력: 없음
         * 리턴: ViewModelProvider.Factory
         */
        fun factory(app: App): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") // 요청 클래스는 ResultsViewModel 뿐
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ResultsViewModel::class.java)) { "지원하지 않는 ViewModel: ${modelClass.name}" } // 다른 타입 거부
                val start: (RunConfig) -> String? = { cfg -> // 시작 함수
                    if (RunService.start(app, cfg)) null else RunSetupViewModel.startFailureReason(RunService.events.value) // 성공 null, 실패 사유
                }
                return ResultsViewModel(app.dirs.output, app.modelStore, startRun = start) as T // 싱글턴 주입
            }
        }
    }
}
