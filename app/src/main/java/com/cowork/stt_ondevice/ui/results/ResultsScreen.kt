// 파일명: ui/results/ResultsScreen.kt
// 목적: 결과 화면(Compose) — output_stt 의 run 목록, 조합별 OK/NO_MATCH/ERROR 수, 파일별 전사 미리보기, 이어하기, 내보내기(Task 30 연결 지점)
// 기능: ResultsViewModel.state 를 run 카드 목록(최신 먼저)으로 그린다. 카드를 누르면 펼쳐져 시작·종료 시각, manifest 상태, 미지원 조합, 조합별 집계가 보이고,
//       조합을 누르면 그 조합의 파일(result.csv 행) 목록, 파일을 누르면 txt(ERROR 는 ERROR.txt) 미리보기 대화상자가 열린다.
//       미완료 run 에는 "이어하기"(알림 권한 확인 후 ViewModel.resume — 시작되면 onResumeStarted 로 진행 탭 이동), onExport 가 있으면 "내보내기" 버튼을 보인다
//       (R6: onExport 가 null 이면 버튼을 숨긴다 — Task 30 이 SAF 내보내기를 연결).
//       change(add)-hyungchul-2026-10-11-03:59 SAF 내보내기 연결: onExport(File) 대신 exportEnabled 를 받고, "내보내기" → OpenDocumentTree 선택기 → ResultsViewModel.export(runId, SafBridge.exportDir)
//       (고른 폴더 아래 <runId> 새 폴더, 진행 중에는 "내보내는 중…"·취소 버튼). 실행 중인 run 은 "실행 중" 라벨이고 이어하기·내보내기를 숨긴다(R42-2).
//       안내 스낵바는 화면 코루틴 범위에서 띄운다(이전: 메시지 소비로 LaunchedEffect 키가 바뀌어 스낵바가 즉시 취소되던 문제 수정).
//       목록은 run 머리 카드·상세·조합 행·파일 행을 각각 LazyColumn 항목(안정 키)으로 펼치고, 각 항목에는 필요한 값만 넘긴다(R42-3, 큰 run 성능).
//       change(add)-hyungchul-2026-10-11-12:51 완료된 run 에 CRASH_OR_KILLED 항목이 있으면 상세에 "CRASH_OR_KILLED 항목 다시 실행 (N개)" 버튼(알림 권한 확인 후 ViewModel.rerunCrashed — 시작되면 진행 탭)을 보이고,
//       조합 행에 크래시 행 수를 함께 적는다(최종 리뷰 I-1).
//       change(add)-hyungchul-2026-10-11-21:50 (최종 리뷰 수정) 미완료 run 의 manifest 에 일시정지 사유(pausedReason)가 있으면 머리 카드에 "일시정지: <사유>" 와
//       "해당 서비스 앱을 강제 중지한 뒤 [이어하기]를 누르세요" 안내를 보인다(진행 탭 안내는 앱을 다시 켜면 사라지므로). 실행 중이면 보이지 않는다.
// change(add)-hyungchul-2026-10-11-03:31
package com.cowork.stt_ondevice.ui.results // 결과 화면 패키지

import android.net.Uri // change(add)-hyungchul-2026-10-11-03:59 고른 트리 Uri
import androidx.activity.compose.rememberLauncherForActivityResult // change(add)-hyungchul-2026-10-11-03:59 SAF 선택기 런처
import androidx.activity.result.contract.ActivityResultContracts // change(add)-hyungchul-2026-10-11-03:59 SAF 선택기 계약
import androidx.compose.foundation.clickable // 클릭 처리
import androidx.compose.foundation.layout.Arrangement // 배치 간격
import androidx.compose.foundation.layout.Column // 세로 배치
import androidx.compose.foundation.layout.ExperimentalLayoutApi // 실험적 레이아웃 표시
import androidx.compose.foundation.layout.FlowRow // 줄바꿈 가로 배치
import androidx.compose.foundation.layout.PaddingValues // 여백 값
import androidx.compose.foundation.layout.Row // 가로 배치
import androidx.compose.foundation.layout.WindowInsets // 시스템 인셋
import androidx.compose.foundation.layout.fillMaxSize // 가득 채우기
import androidx.compose.foundation.layout.fillMaxWidth // 가로 채우기
import androidx.compose.foundation.layout.heightIn // 최대 높이
import androidx.compose.foundation.layout.padding // 여백
import androidx.compose.foundation.lazy.LazyColumn // 지연 목록
import androidx.compose.foundation.lazy.items // 목록 항목
import androidx.compose.foundation.lazy.itemsIndexed // change(add)-hyungchul-2026-10-11-03:59 순번 있는 목록 항목
import androidx.compose.foundation.rememberScrollState // 스크롤 상태
import androidx.compose.foundation.text.selection.SelectionContainer // 텍스트 선택
import androidx.compose.foundation.verticalScroll // 세로 스크롤
import androidx.compose.material3.AlertDialog // 대화상자
import androidx.compose.material3.Card // 카드
import androidx.compose.material3.HorizontalDivider // 구분선
import androidx.compose.material3.MaterialTheme // 테마 값
import androidx.compose.material3.Scaffold // 화면 골격
import androidx.compose.material3.SnackbarHost // 스낵바 영역
import androidx.compose.material3.SnackbarHostState // 스낵바 상태
import androidx.compose.material3.Text // 텍스트
import androidx.compose.material3.TextButton // 텍스트 버튼
import androidx.compose.runtime.Composable // 컴포저블
import androidx.compose.runtime.LaunchedEffect // 부수 효과
import androidx.compose.runtime.getValue // 위임 읽기
import androidx.compose.runtime.mutableStateOf // change(add)-hyungchul-2026-10-11-03:59 상태 생성
import androidx.compose.runtime.remember // 기억
import androidx.compose.runtime.saveable.rememberSaveable // change(add)-hyungchul-2026-10-11-03:59 선택기 왕복 중 유지
import androidx.compose.runtime.setValue // change(add)-hyungchul-2026-10-11-03:59 위임 쓰기
import androidx.compose.runtime.rememberCoroutineScope // 코루틴 범위
import androidx.compose.ui.Alignment // 정렬
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.platform.LocalContext // 컨텍스트
import androidx.compose.ui.text.font.FontFamily // 고정폭 글꼴
import androidx.compose.ui.unit.dp // dp 단위
import androidx.lifecycle.compose.collectAsStateWithLifecycle // 생명주기 인식 상태 수집
import androidx.lifecycle.viewmodel.compose.viewModel // 화면용 ViewModel
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.core.storage.SafBridge // change(add)-hyungchul-2026-10-11-03:59 SAF 입출력
import com.cowork.stt_ondevice.ui.run.rememberNotificationGate // 알림 권한 확인 도우미
import kotlinx.coroutines.launch // 코루틴 시작

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 결과 화면
 * 기능: 파일 머리말 참조. 화면에 들어올 때마다 목록을 다시 읽고, initialRunId 가 있으면 그 run 을 펼친다.
 * 입력: initialRunId(진행 화면 "결과 보기" 로 넘어온 runId 또는 null), exportEnabled(change(add)-hyungchul-2026-10-11-03:59 SAF 내보내기 버튼 표시 여부 — 이전 onExport(File) 대체),
 *       onResumeStarted(이어하기가 시작됐을 때 — 진행 탭 이동), viewModel(기본은 App 싱글턴으로 만든 ResultsViewModel)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun ResultsScreen(
    initialRunId: String?,
    exportEnabled: Boolean, // change(add)-hyungchul-2026-10-11-03:59 onExport(File)? → 표시 여부
    onResumeStarted: () -> Unit,
    viewModel: ResultsViewModel = viewModel(factory = ResultsViewModel.factory(App.from(LocalContext.current))),
) {
    val context = LocalContext.current // change(add)-hyungchul-2026-10-11-03:59 컨텍스트(SAF)
    val s by viewModel.state.collectAsStateWithLifecycle() // 화면 상태
    val snackbar = remember { SnackbarHostState() } // 스낵바
    val scope = rememberCoroutineScope() // 스낵바용 범위
    val gate = rememberNotificationGate { // 알림 권한 확인(이어하기)
        scope.launch { snackbar.showSnackbar("알림 권한이 없어 진행 알림이 보이지 않을 수 있습니다(실행은 계속됩니다)") } // 거부 안내
    }
    val bridge = remember { SafBridge(context.applicationContext) } // change(add)-hyungchul-2026-10-11-03:59 SAF 다리
    var exportTarget by rememberSaveable { mutableStateOf<String?>(null) } // change(add)-hyungchul-2026-10-11-03:59 선택기를 연 run(선택기 왕복·회전에도 유지)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> // change(add)-hyungchul-2026-10-11-03:59 폴더 선택 결과
        val runId = exportTarget // 대상 run
        exportTarget = null // 비우기
        if (uri != null && runId != null) viewModel.export(runId) { dir -> bridge.exportDir(dir, uri) } // 고른 폴더로 내보내기(취소하면 아무 일 없음)
    }

    LaunchedEffect(Unit) { viewModel.refresh() } // 들어올 때마다 다시 읽기
    LaunchedEffect(initialRunId) { initialRunId?.let(viewModel::openRun) } // 넘어온 run 펼치기
    LaunchedEffect(s.message) { // 안내 문구
        s.message?.let { viewModel.consumeMessage(); scope.launch { snackbar.showSnackbar(it) } } // 소비 후 표시 — change(add)-hyungchul-2026-10-11-03:59 화면 범위에서 표시(소비로 키가 바뀌어 이 효과가 취소돼도 스낵바가 바로 사라지지 않게)
    }
    LaunchedEffect(s.resumeStarted) { // 이어하기 시작
        if (s.resumeStarted != null) { viewModel.consumeResumeStarted(); onResumeStarted() } // 진행 탭으로
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 인셋은 바깥 Scaffold 가 처리
        snackbarHost = { SnackbarHost(snackbar) }, // 스낵바
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // 화면 전체
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), // 여백
            verticalArrangement = Arrangement.spacedBy(8.dp), // 간격
        ) {
            item(key = "title") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("결과", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)) // 제목
                    TextButton(onClick = viewModel::refresh) { Text(if (s.loading) "읽는 중…" else "새로고침") } // 다시 읽기
                }
            }
            if (!s.loading && s.runs.isEmpty()) {
                item(key = "empty") { Text("아직 실행 결과가 없습니다.", style = MaterialTheme.typography.bodyLarge) } // 비어 있음
            }
            // change(add)-hyungchul-2026-10-11-03:59 run 마다 머리 카드 → (펼쳤으면) 상세 → 조합 행 → (열린 조합) 파일 행을 각각 항목으로(R42-3)
            s.runs.forEach { run -> // run 마다
                val open = s.openRunId == run.runId // 펼침 여부
                val running = s.isRunning(run.runId) // 실행 중 여부
                item(key = "run-${run.runId}") { // 머리 카드
                    RunHeader(
                        run = run, // run
                        open = open, // 펼침
                        running = running, // 실행 중
                        onToggle = { if (open) viewModel.closeRun() else viewModel.openRun(run.runId) }, // 펼치기/접기
                    )
                }
                if (open) { // 펼쳤을 때만 상세
                    item(key = "detail-${run.runId}") {
                        RunDetail(
                            run = run, // run
                            canResume = s.resumable(run), // 이어하기 가능(실행 중이면 숨김)
                            canRerunCrashed = s.rerunnable(run), // 크래시 항목 다시 실행 가능(완료·크래시 있음·실행 중 아님) // change(add)-hyungchul-2026-10-11-12:51
                            canExport = exportEnabled && !running, // 내보내기 가능(실행 중이면 숨김)
                            exporting = s.exportingRunId == run.runId, // 이 run 내보내는 중
                            onResume = { gate { viewModel.resume(run.runId) } }, // 이어하기(권한 확인 후)
                            onRerunCrashed = { gate { viewModel.rerunCrashed(run.runId) } }, // 크래시 항목 다시 실행(권한 확인 후) // change(add)-hyungchul-2026-10-11-12:51
                            onExport = { exportTarget = run.runId; picker.launch(null) }, // 폴더 고르기 → 내보내기
                            onCancelExport = viewModel::cancelExport, // 내보내기 취소
                        )
                    }
                    run.combos.forEach { c -> // 조합마다 — change(add)-hyungchul-2026-10-11-04:39 열린 조합의 파일 행을 그 조합 바로 아래에 두려고 조합별 항목으로
                        val comboOpen = sameCombo(s.openCombo, c) // 열림 여부
                        item(key = "combo-${c.runId}/${c.lang}/${c.combo}") { // 조합 행(키 규칙 유지)
                            ComboRow(c, comboOpen = comboOpen, onClick = { if (comboOpen) viewModel.closeCombo() else viewModel.openCombo(c) }) // 열기/닫기
                        }
                        if (comboOpen) { // 열린 조합이면 바로 아래에 파일 목록
                            if (s.files.isEmpty()) {
                                item(key = "files-empty-${c.runId}/${c.lang}/${c.combo}") { Text("파일 목록을 읽는 중이거나 행이 없습니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp)) } // 비어 있음
                            }
                            itemsIndexed(s.files, key = { i, f -> "file-${c.runId}/${c.lang}/${c.combo}/$i/${f.relDir}/${f.file}" }) { _, f -> // 파일 행(중복 행 대비 순번 포함 키)
                                FileRow(f, onClick = { viewModel.openPreview(f) }) // 미리보기
                            }
                        }
                    }
                }
            }
        }
    }

    s.preview?.let { p -> // 미리보기 대화상자
        AlertDialog(
            onDismissRequest = viewModel::closePreview, // 바깥 탭
            title = { Text(p.title, style = MaterialTheme.typography.titleSmall) }, // 위치
            text = {
                SelectionContainer { // 복사 가능
                    Text(p.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) // 본문(스크롤)
                }
            },
            confirmButton = { TextButton(onClick = viewModel::closePreview) { Text("닫기") } }, // 닫기
        )
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: run 머리 카드(접힌 상태 요약)
 * 기능: runId·상태 라벨(manifest 문제 / 실행 중 / 완료 / 미완료)·시작/종료 시각·조합 수와 합계를 그린다. 누르면 펼치기/접기.
 *       change(add)-hyungchul-2026-10-11-21:50 일시정지 사유가 있고 실행 중이 아니면 "일시정지: <사유>" 와 강제 중지 후 [이어하기] 안내 한 줄을 더 그린다.
 * 입력: run(목록 행), open(펼침 여부), running(실행 중 여부), onToggle(펼치기/접기)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
private fun RunHeader(run: RunEntry, open: Boolean, running: Boolean, onToggle: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle)) { // 카드(누르면 펼침)
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.runId + if (open) "  ▲" else "  ▼", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)) // runId + 펼침 표시
                val (label, color) = when { // 상태 라벨
                    run.manifestStatus != ManifestStatus.OK -> run.manifestStatus.label to MaterialTheme.colorScheme.error // manifest 문제
                    running -> "실행 중" to MaterialTheme.colorScheme.tertiary // 실행 중(R42-2)
                    run.finished -> "완료" to MaterialTheme.colorScheme.primary // 완료
                    else -> "미완료" to MaterialTheme.colorScheme.error // 미완료
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = color) // 상태
            }
            Text("시작 ${run.startedAt ?: "-"} · 종료 ${run.finishedAt ?: "-"}", style = MaterialTheme.typography.bodySmall) // 시각
            val ok = run.combos.sumOf { it.ok } // OK 합
            val nm = run.combos.sumOf { it.noMatch } // NO_MATCH 합
            val er = run.combos.sumOf { it.error } // ERROR 합
            Text("조합 ${run.combos.size}개 · OK $ok · NO_MATCH $nm · ERROR $er", style = MaterialTheme.typography.bodySmall) // 합계
            val paused = run.pausedReason // 일시정지 사유 // change(add)-hyungchul-2026-10-11-21:50
            if (paused != null && !running) { // 일시정지된 미완료 run(실행 중이면 숨김) // change(add)-hyungchul-2026-10-11-21:50
                Text("일시정지: $paused", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) // 사유 // change(add)-hyungchul-2026-10-11-21:50
                Text("해당 서비스 앱을 강제 중지한 뒤 카드를 펼쳐 [이어하기]를 누르세요", style = MaterialTheme.typography.bodySmall) // 안내 // change(add)-hyungchul-2026-10-11-21:50
            }
        }
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 펼친 run 의 상세(미지원 조합·설정 요약·버튼)
 * 기능: 미지원 조합, 모델·프리셋·chunk·스레드 요약, 이어하기·내보내기 버튼(내보내는 중이면 "내보내는 중…" 과 취소)을 그린다.
 * 입력: run(목록 행), canResume(이어하기 표시), canExport(내보내기 표시), exporting(이 run 내보내는 중), onResume/onExport/onCancelExport(버튼 동작),
 *       canRerunCrashed(change(add)-hyungchul-2026-10-11-12:51 "CRASH_OR_KILLED 항목 다시 실행" 표시), onRerunCrashed(change(add)-hyungchul-2026-10-11-12:51 그 버튼 동작)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunDetail(
    run: RunEntry,
    canResume: Boolean,
    canExport: Boolean,
    exporting: Boolean,
    onResume: () -> Unit,
    onExport: () -> Unit,
    onCancelExport: () -> Unit,
    canRerunCrashed: Boolean = false, // change(add)-hyungchul-2026-10-11-12:51 크래시 항목 다시 실행 표시
    onRerunCrashed: () -> Unit = {}, // change(add)-hyungchul-2026-10-11-12:51 크래시 항목 다시 실행 동작
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (run.unsupported.isNotEmpty()) { // 미지원 조합
            Text("미지원 조합(언어 미지원으로 실행 안 함): ${run.unsupported.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 목록
        }
        if (run.manifest != null) { // 설정 요약
            Text("모델: ${run.manifest.models.joinToString(", ") { it.id }} · 프리셋: ${run.manifest.presets.joinToString(", ") { it.id }} · ${run.manifest.chunkMode} · 스레드 ${run.manifest.threads}", style = MaterialTheme.typography.bodySmall) // 설정
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { // 버튼
            if (canResume) TextButton(onClick = onResume) { Text("이어하기") } // 미완료·실행 중 아님
            if (canRerunCrashed) TextButton(onClick = onRerunCrashed) { Text("CRASH_OR_KILLED 항목 다시 실행 (${run.crashed}개)") } // 완료·크래시 항목 있음 // change(add)-hyungchul-2026-10-11-12:51
            if (exporting) { // 내보내는 중
                Text("내보내는 중…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.CenterVertically)) // 진행 표시
                TextButton(onClick = onCancelExport) { Text("내보내기 취소") } // 취소
            } else if (canExport) {
                TextButton(onClick = onExport) { Text("내보내기(SAF)") } // 폴더 고르기
            }
        }
        if (run.combos.isEmpty()) Text("조합 결과(result.csv)가 아직 없습니다.", style = MaterialTheme.typography.bodySmall) // 비어 있음
        HorizontalDivider() // 구분선
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 조합 행 1개
 * 기능: 언어·조합명(열렸으면 주색)과 집계(또는 읽기 오류)를 그린다. 누르면 열기/닫기.
 * 입력: c(조합 집계), comboOpen(열림 여부), onClick(열기/닫기)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
private fun ComboRow(c: ComboResult, comboOpen: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text("${c.lang} · ${c.combo}", style = MaterialTheme.typography.bodyMedium, color = if (comboOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) // 조합명
        val counts = "OK ${c.ok} · NO_MATCH ${c.noMatch} · ERROR ${c.error}" + (if (c.crashed > 0) " (이 중 CRASH_OR_KILLED ${c.crashed})" else "") + if (c.other > 0) " · 기타 ${c.other}" else "" // 집계 // change(add)-hyungchul-2026-10-11-12:51 크래시 수
        Text(c.readError ?: counts, style = MaterialTheme.typography.bodySmall, color = if (c.readError != null || c.error > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) // 집계 또는 오류
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 파일 행 1개
 * 기능: 상대 폴더/파일명(고정폭)과 상태를 그린다. 누르면 미리보기.
 * 입력: f(파일 행), onClick(미리보기)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
private fun FileRow(f: FileResult, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, top = 4.dp, bottom = 4.dp)) {
        Text(listOf(f.relDir, f.file).filter { it.isNotEmpty() }.joinToString("/"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)) // 위치
        Text(f.status, style = MaterialTheme.typography.labelMedium, color = if (f.status == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) // 상태
    }
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 같은 조합인지 판단
 * 기능: 집계 숫자는 무시하고 runId·언어·조합명이 같으면 true(목록을 새로 읽어 건수가 바뀌어도 열린 조합을 유지).
 * 입력: a(열린 조합 또는 null), b(비교 대상)
 * 출력: 없음
 * 리턴: 같으면 true
 */
private fun sameCombo(a: ComboResult?, b: ComboResult): Boolean =
    a != null && a.runId == b.runId && a.lang == b.lang && a.combo == b.combo // 식별 값 비교
