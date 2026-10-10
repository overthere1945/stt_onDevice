// 파일명: ui/results/ResultsScreen.kt
// 목적: 결과 화면(Compose) — output_stt 의 run 목록, 조합별 OK/NO_MATCH/ERROR 수, 파일별 전사 미리보기, 이어하기, 내보내기(Task 30 연결 지점)
// 기능: ResultsViewModel.state 를 run 카드 목록(최신 먼저)으로 그린다. 카드를 누르면 펼쳐져 시작·종료 시각, manifest 상태, 미지원 조합, 조합별 집계가 보이고,
//       조합을 누르면 그 조합의 파일(result.csv 행) 목록, 파일을 누르면 txt(ERROR 는 ERROR.txt) 미리보기 대화상자가 열린다.
//       미완료 run 에는 "이어하기"(알림 권한 확인 후 ViewModel.resume — 시작되면 onResumeStarted 로 진행 탭 이동), onExport 가 있으면 "내보내기" 버튼을 보인다
//       (R6: onExport 가 null 이면 버튼을 숨긴다 — Task 30 이 SAF 내보내기를 연결).
// change(add)-hyungchul-2026-10-11-03:31
package com.cowork.stt_ondevice.ui.results // 결과 화면 패키지

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
import androidx.compose.runtime.remember // 기억
import androidx.compose.runtime.rememberCoroutineScope // 코루틴 범위
import androidx.compose.ui.Alignment // 정렬
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.platform.LocalContext // 컨텍스트
import androidx.compose.ui.text.font.FontFamily // 고정폭 글꼴
import androidx.compose.ui.unit.dp // dp 단위
import androidx.lifecycle.compose.collectAsStateWithLifecycle // 생명주기 인식 상태 수집
import androidx.lifecycle.viewmodel.compose.viewModel // 화면용 ViewModel
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.ui.run.rememberNotificationGate // 알림 권한 확인 도우미
import kotlinx.coroutines.launch // 코루틴 시작
import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 결과 화면
 * 기능: 파일 머리말 참조. 화면에 들어올 때마다 목록을 다시 읽고, initialRunId 가 있으면 그 run 을 펼친다.
 * 입력: initialRunId(진행 화면 "결과 보기" 로 넘어온 runId 또는 null), onExport(run 폴더 내보내기 — null 이면 버튼 숨김, Task 30),
 *       onResumeStarted(이어하기가 시작됐을 때 — 진행 탭 이동), viewModel(기본은 App 싱글턴으로 만든 ResultsViewModel)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun ResultsScreen(
    initialRunId: String?,
    onExport: ((File) -> Unit)?,
    onResumeStarted: () -> Unit,
    viewModel: ResultsViewModel = viewModel(factory = ResultsViewModel.factory(App.from(LocalContext.current))),
) {
    val s by viewModel.state.collectAsStateWithLifecycle() // 화면 상태
    val snackbar = remember { SnackbarHostState() } // 스낵바
    val scope = rememberCoroutineScope() // 스낵바용 범위
    val gate = rememberNotificationGate { // 알림 권한 확인(이어하기)
        scope.launch { snackbar.showSnackbar("알림 권한이 없어 진행 알림이 보이지 않을 수 있습니다(실행은 계속됩니다)") } // 거부 안내
    }

    LaunchedEffect(Unit) { viewModel.refresh() } // 들어올 때마다 다시 읽기
    LaunchedEffect(initialRunId) { initialRunId?.let(viewModel::openRun) } // 넘어온 run 펼치기
    LaunchedEffect(s.message) { // 안내 문구
        s.message?.let { viewModel.consumeMessage(); snackbar.showSnackbar(it) } // 소비 후 표시
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
            items(s.runs, key = { it.runId }) { run -> // run 마다
                RunCard(
                    run = run, // run
                    state = s, // 화면 상태(열린 조합·파일)
                    onToggle = { if (s.openRunId == run.runId) viewModel.closeRun() else viewModel.openRun(run.runId) }, // 펼치기/접기
                    onCombo = { c -> if (sameCombo(s.openCombo, c)) viewModel.closeCombo() else viewModel.openCombo(c) }, // 조합 열기/닫기
                    onFile = viewModel::openPreview, // 미리보기
                    onResume = { gate { viewModel.resume(run.runId) } }, // 이어하기(권한 확인 후)
                    onExport = onExport?.let { export -> { export(run.dir) } }, // 내보내기(Task 30)
                )
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

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: run 카드 1개
 * 기능: 접힌 상태에서는 runId·완료 여부·시작/종료 시각·manifest 상태·조합 수 합계를, 펼치면 미지원 조합·버튼·조합별 집계·열린 조합의 파일 목록을 그린다.
 * 입력: run(목록 행), state(화면 상태), onToggle(펼치기/접기), onCombo(조합 열기/닫기), onFile(파일 미리보기), onResume(이어하기), onExport(내보내기 또는 null)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunCard(
    run: RunEntry,
    state: ResultsState,
    onToggle: () -> Unit,
    onCombo: (ComboResult) -> Unit,
    onFile: (FileResult) -> Unit,
    onResume: () -> Unit,
    onExport: (() -> Unit)?,
) {
    val open = state.openRunId == run.runId // 펼침 여부
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalArrangement = Arrangement.spacedBy(2.dp)) { // 머리(누르면 펼침)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(run.runId, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)) // runId
                    val (label, color) = when { // 상태 라벨
                        run.manifestStatus != ManifestStatus.OK -> run.manifestStatus.label to MaterialTheme.colorScheme.error // manifest 문제
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
            }
            if (open) { // 펼쳤을 때만 상세
                HorizontalDivider() // 구분선
                if (run.unsupported.isNotEmpty()) { // 미지원 조합
                    Text("미지원 조합(언어 미지원으로 실행 안 함): ${run.unsupported.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 목록
                }
                if (run.manifest != null) { // 설정 요약
                    Text("모델: ${run.manifest.models.joinToString(", ") { it.id }} · 프리셋: ${run.manifest.presets.joinToString(", ") { it.id }} · ${run.manifest.chunkMode} · 스레드 ${run.manifest.threads}", style = MaterialTheme.typography.bodySmall) // 설정
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { // 버튼
                    if (run.resumable) TextButton(onClick = onResume) { Text("이어하기") } // 미완료 run
                    if (onExport != null) TextButton(onClick = onExport) { Text("내보내기") } // Task 30
                }
                if (run.combos.isEmpty()) Text("조합 결과(result.csv)가 아직 없습니다.", style = MaterialTheme.typography.bodySmall) // 비어 있음
                run.combos.forEach { c -> // 조합마다
                    val comboOpen = sameCombo(state.openCombo, c) // 열림 여부(집계가 갱신돼도 같은 조합으로 판단)
                    Column(modifier = Modifier.fillMaxWidth().clickable { onCombo(c) }.padding(vertical = 4.dp)) {
                        Text("${c.lang} · ${c.combo}", style = MaterialTheme.typography.bodyMedium, color = if (comboOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) // 조합명
                        val counts = "OK ${c.ok} · NO_MATCH ${c.noMatch} · ERROR ${c.error}" + if (c.other > 0) " · 기타 ${c.other}" else "" // 집계
                        Text(c.readError ?: counts, style = MaterialTheme.typography.bodySmall, color = if (c.readError != null || c.error > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) // 집계 또는 오류
                    }
                    if (comboOpen) { // 파일 목록
                        if (state.files.isEmpty()) Text("파일 목록을 읽는 중이거나 행이 없습니다.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp)) // 비어 있음
                        state.files.forEach { f -> // 파일마다
                            Row(modifier = Modifier.fillMaxWidth().clickable { onFile(f) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
                                Text(listOf(f.relDir, f.file).filter { it.isNotEmpty() }.joinToString("/"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)) // 위치
                                Text(f.status, style = MaterialTheme.typography.labelMedium, color = if (f.status == "ERROR") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) // 상태
                            }
                        }
                    }
                }
            }
        }
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
