// 파일명: ui/models/ModelsScreen.kt
// 목적: 모델 관리 화면(Compose) — 카탈로그 목록과 다운로드·취소·가져오기·검증·삭제 조작
// 기능: ModelsViewModel.rows 를 그룹(ASR 은 algo 별, 마지막 "VAD 모델")으로 묶어 카드로 그린다. 카드에는 표시 이름·크기(MB)·언어·라이선스·
//       색 배지(상업 이용 상태)·설치 상태·진행률 바가 있고 상태에 맞는 버튼만 보인다. 삭제는 확인 대화상자, 가져오기는 폴더 경로 안내 대화상자,
//       검증은 결과 대화상자를 거치며 오류·완료 메시지는 스낵바(한국어)로 보인다.
//       change(add)-hyungchul-2026-10-11-03:59 가져오기 대화상자에 "폴더 선택(SAF)"(OpenDocumentTree)·"파일 선택(SAF)"(OpenDocument, 압축 1개)을 더했다. 고르면 import/<id>/saf_… 로 복사한 뒤
//       기존 가져오기(installer.importFrom)를 그대로 쓴다(ModelsViewModel.importFromSaf). adb 안내는 tools/adb/push_models.sh 사용을 권장한다(R41 — push 후 권한 정리).
//       change(add)-hyungchul-2026-10-11-19:52 (2차 작업 7) 목록 맨 위에 "시스템 엔진" 카드 2개(ML Kit Basic·플랫폼 온디바이스)를 더했다. 카드에는 제목·"Google 서비스 약관 적용"·언어 줄(상태: 사용 가능/다운로드 필요/
//       다운로드 중/사용 불가(사유))·[다운로드](다운로드 필요이고 앱에서 요청 가능할 때)·[새로고침]이 있다. 삭제·가져오기·검증 버튼은 없고, 동의 창은 사용자가 직접 누른다.
//       change(add)-hyungchul-2026-10-11-21:50 (최종 리뷰 수정) LanguageStatus.CheckFailed(일시적 확인 실패)는 사용 불가와 같은 색으로 그린다.
package com.cowork.stt_ondevice.ui.models // 모델 화면 패키지

import android.net.Uri // change(add)-hyungchul-2026-10-11-03:59 고른 문서 Uri
import androidx.activity.compose.rememberLauncherForActivityResult // change(add)-hyungchul-2026-10-11-03:59 SAF 선택기 런처
import androidx.activity.result.contract.ActivityResultContracts // change(add)-hyungchul-2026-10-11-03:59 SAF 선택기 계약
import androidx.compose.foundation.isSystemInDarkTheme // change(add)-hyungchul-2026-10-11-03:24 다크 모드 여부(색 대비)
import androidx.compose.foundation.layout.Arrangement // 배치 간격
import androidx.compose.foundation.layout.Column // 세로 배치
import androidx.compose.foundation.layout.ExperimentalLayoutApi // 실험적 레이아웃 표시
import androidx.compose.foundation.layout.FlowRow // 줄바꿈 가로 배치
import androidx.compose.foundation.layout.PaddingValues // 여백 값
import androidx.compose.foundation.layout.Row // 가로 배치
import androidx.compose.foundation.layout.WindowInsets // 시스템 인셋
import androidx.compose.foundation.layout.fillMaxSize // 가득 채우기
import androidx.compose.foundation.layout.fillMaxWidth // 가로 채우기
import androidx.compose.foundation.text.selection.SelectionContainer // 텍스트 선택(경로 복사용)
import androidx.compose.foundation.layout.padding // 여백
import androidx.compose.foundation.lazy.LazyColumn // 지연 목록
import androidx.compose.foundation.lazy.items // 목록 항목
import androidx.compose.foundation.shape.RoundedCornerShape // 둥근 모서리
import androidx.compose.material3.AlertDialog // 대화상자
import androidx.compose.material3.Card // 카드
import androidx.compose.material3.LinearProgressIndicator // 진행률 바
import androidx.compose.material3.MaterialTheme // 테마 값
import androidx.compose.material3.Scaffold // 화면 골격
import androidx.compose.material3.SnackbarHost // 스낵바 표시 영역
import androidx.compose.material3.SnackbarHostState // 스낵바 상태
import androidx.compose.material3.Surface // 배경 면
import androidx.compose.material3.Text // 텍스트
import androidx.compose.material3.TextButton // 텍스트 버튼
import androidx.compose.runtime.Composable // 컴포저블
import androidx.compose.runtime.LaunchedEffect // 부수 효과
import androidx.compose.runtime.getValue // 위임 읽기
import androidx.compose.runtime.mutableStateOf // 상태 생성
import androidx.compose.runtime.remember // 기억
import androidx.compose.runtime.saveable.rememberSaveable // change(add)-hyungchul-2026-10-11-03:59 선택기 왕복·회전에도 유지
import androidx.compose.runtime.setValue // 위임 쓰기
import androidx.compose.ui.Alignment // change(add)-hyungchul-2026-10-11-19:52 세로 정렬
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.graphics.Color // 색
import androidx.compose.ui.platform.LocalContext // 컨텍스트
import androidx.compose.ui.unit.dp // dp 단위
import androidx.lifecycle.compose.collectAsStateWithLifecycle // change(add)-hyungchul-2026-10-11-03:24 생명주기 인식 상태 수집
import androidx.lifecycle.viewmodel.compose.viewModel // 화면용 ViewModel 얻기
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.core.storage.SafBridge // change(add)-hyungchul-2026-10-11-03:59 SAF 입출력
import com.cowork.stt_ondevice.engine.system.LanguageStatus // change(add)-hyungchul-2026-10-11-19:52 언어 상태
import com.cowork.stt_ondevice.model.CommercialStatus // 상업 이용 상태
import com.cowork.stt_ondevice.model.InstallState // 설치 상태

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 모델 관리 화면
 * 기능: ViewModel(앱 전역 store/installer 사용)을 얻어 목록·대화상자·스낵바를 구성한다. 바깥 Scaffold(하단 탭)가 인셋을 처리하므로 여기서는 인셋을 더하지 않는다.
 * 입력: viewModel(기본은 App 싱글턴으로 만든 ModelsViewModel)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun ModelsScreen(viewModel: ModelsViewModel = viewModel(factory = ModelsViewModel.factory(App.from(LocalContext.current)))) {
    val rows by viewModel.rows.collectAsStateWithLifecycle() // 행 목록
    val importPrompt by viewModel.importPrompt.collectAsStateWithLifecycle() // 가져오기 안내
    val verifyReport by viewModel.verifyReport.collectAsStateWithLifecycle() // 검증 결과
    val systemRows by viewModel.systemRows.collectAsStateWithLifecycle() // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 카드
    val snackbar = remember { SnackbarHostState() } // 스낵바 상태
    var deleteTarget by remember { mutableStateOf<ModelRow?>(null) } // 삭제 확인 대상
    val context = LocalContext.current // change(add)-hyungchul-2026-10-11-03:59 컨텍스트(SAF)
    val bridge = remember { SafBridge(context.applicationContext) } // change(add)-hyungchul-2026-10-11-03:59 SAF 다리
    var safTarget by rememberSaveable { mutableStateOf<String?>(null) } // change(add)-hyungchul-2026-10-11-03:59 선택기를 연 모델 id
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> // change(add)-hyungchul-2026-10-11-03:59 폴더 선택 결과
        val id = safTarget; safTarget = null // 대상 모델(소비)
        if (uri != null && id != null) viewModel.importFromSaf(id) { dir -> bridge.importModel(uri, isTree = true, dest = dir) } // 폴더째 복사 → 가져오기
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> // change(add)-hyungchul-2026-10-11-03:59 파일 선택 결과
        val id = safTarget; safTarget = null // 대상 모델(소비)
        if (uri != null && id != null) viewModel.importFromSaf(id) { dir -> bridge.importModel(uri, isTree = false, dest = dir) } // 파일 복사 → 가져오기
    }

    LaunchedEffect(viewModel) { // 메시지 수집
        viewModel.messages.collect { snackbar.showSnackbar(it) } // 한국어 메시지를 스낵바로
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 인셋은 바깥 Scaffold 가 처리
        snackbarHost = { SnackbarHost(snackbar) }, // 스낵바 영역
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // 화면 전체
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), // 가장자리 여백
            verticalArrangement = Arrangement.spacedBy(8.dp), // 항목 간격
        ) {
            item(key = "title") { Text("모델 관리", style = MaterialTheme.typography.headlineSmall) } // 화면 제목
            item(key = "notice") { // change(add)-hyungchul-2026-10-11-03:24 다운로드 수명 안내
                Text("다운로드는 앱이 켜져 있는 동안 진행됩니다. 앱을 완전히 종료하면 취소되며, 받은 부분은 보관되어 다시 시작하면 이어받습니다(탭 이동은 괜찮습니다).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (systemRows.isNotEmpty()) { // change(add)-hyungchul-2026-10-11-19:52 시스템 엔진 영역(조회기가 없으면 숨김)
                item(key = "group-system") { // 영역 머리글
                    Text("시스템 엔진", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(systemRows, key = { "system-${it.kind.name}" }) { row -> // 엔진 카드
                    SystemEngineCard(
                        row = row,
                        canDownload = viewModel.canDownloadSystem(row.kind), // 앱에서 다운로드 요청 가능 여부
                        onDownload = { lang -> viewModel.downloadSystem(row.kind, lang) }, // 다운로드 요청
                        onCancel = { viewModel.cancelSystemDownload(row.kind) }, // change(add)-hyungchul-2026-10-11-20:05 다운로드 취소(동의 창을 닫아 멈춘 경우의 탈출구)
                        onRefresh = viewModel::refreshSystem, // 새로고침
                    )
                }
            }
            rows.groupBy { it.group }.forEach { (group, groupRows) -> // 그룹 순서는 카탈로그 순서
                item(key = "group-$group") { // 그룹 머리글
                    Text(group, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                }
                items(groupRows, key = { it.id }) { row -> // 모델 카드
                    ModelCard(
                        row = row,
                        onDownload = { viewModel.download(row.id) }, // 다운로드
                        onCancel = { viewModel.cancel(row.id) }, // 취소
                        onImport = { viewModel.requestImport(row.id) }, // 가져오기 안내
                        onVerify = { viewModel.verify(row.id) }, // 검증
                        onDelete = { deleteTarget = row }, // 삭제 확인 요청
                    )
                }
            }
        }
    }

    deleteTarget?.let { target -> // 삭제 확인 대화상자
        AlertDialog(
            onDismissRequest = { deleteTarget = null }, // 바깥 탭
            title = { Text("모델 삭제") }, // 제목
            text = { Text("'${target.name}' 을(를) 삭제할까요? 설치된 파일이 모두 지워집니다.") }, // 내용
            confirmButton = { TextButton(onClick = { viewModel.delete(target.id); deleteTarget = null }) { Text("삭제") } }, // 확인
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("취소") } }, // 취소
        )
    }

    importPrompt?.let { prompt -> // 가져오기 안내 대화상자
        AlertDialog(
            onDismissRequest = viewModel::dismissImportPrompt, // 바깥 탭
            title = { Text("모델 가져오기") }, // 제목
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("방법 1(휴대폰 안의 파일): 아래 버튼으로 모델 폴더 또는 .tar.bz2/.zip 압축 파일 1개를 고르면 앱 폴더로 복사한 뒤 바로 가져옵니다.") // change(add)-hyungchul-2026-10-11-03:59 SAF 안내
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { // change(add)-hyungchul-2026-10-11-03:59 SAF 버튼
                        TextButton(onClick = { safTarget = prompt.modelId; treePicker.launch(null) }) { Text("폴더 선택(SAF)") } // 폴더
                        TextButton(onClick = { safTarget = prompt.modelId; filePicker.launch(arrayOf("*/*")) }) { Text("파일 선택(SAF)") } // 압축 파일
                    }
                    Text("방법 2(PC): tools/adb/push_models.sh ${prompt.modelId} <폴더 또는 압축 파일> 로 아래 폴더에 넣은 뒤 '가져오기 실행'을 누르세요(스크립트가 push 후 앱이 읽을 수 있게 권한도 정리합니다 — adb push 만 하면 하위 폴더를 못 읽을 수 있습니다).") // 안내(change(add)-hyungchul-2026-10-11-03:59 R41 스크립트 권장)
                    SelectionContainer { Text(prompt.folderPath, style = MaterialTheme.typography.bodyMedium) } // 정확한 경로(선택·복사 가능)
                    Text("가져온 뒤에도 원본은 지워지지 않습니다(SAF 로 복사한 임시본은 앱이 정리). 필요 없으면 직접 지우세요.", style = MaterialTheme.typography.bodySmall) // 부가 안내
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmImport(prompt.modelId) }) { Text("가져오기 실행") } }, // 실행
            dismissButton = { TextButton(onClick = viewModel::dismissImportPrompt) { Text("닫기") } }, // 닫기
        )
    }

    verifyReport?.let { report -> // 검증 결과 대화상자
        AlertDialog(
            onDismissRequest = viewModel::dismissVerifyReport, // 바깥 탭
            title = { Text("검증 결과") }, // 제목
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(report.modelName, style = MaterialTheme.typography.titleSmall) // 모델 이름
                    if (report.problems.isEmpty()) {
                        Text("모든 파일이 정상입니다(크기·SHA-256 일치).") // 정상
                    } else {
                        Text("문제 ${report.problems.size}건:") // 건수
                        report.problems.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } // 문제 목록
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissVerifyReport) { Text("확인") } }, // 닫기
        )
    }
}

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 모델 1개 카드
 * 기능: 이름·배지·메타 정보·상태·진행률·상태별 버튼을 그린다. 받는 중에는 취소만, 검증 중에는 버튼 없음, 그 밖에는 가능한 동작만 보인다.
 * 입력: row(행 값), onDownload/onCancel/onImport/onVerify/onDelete(각 버튼 동작)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(
    row: ModelRow,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) { // 카드
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // 이름 + 배지
                Text(row.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)) // 표시 이름
                CommercialBadge(row.commercial) // 상업 이용 배지
            }
            Text("${row.sizeMb} MB · ${row.languages} · ${row.license}", style = MaterialTheme.typography.bodySmall) // 크기·언어·라이선스
            Text("상태: ${row.state.koreanLabel()}", style = MaterialTheme.typography.bodyMedium, color = stateColor(row.state)) // 설치 상태
            when (row.state) {
                InstallState.DOWNLOADING -> { // 받는 중: 진행률
                    val p = row.progress // 진행률 또는 null
                    if (p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()) // 확정 진행률
                        Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall) // 퍼센트
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // 진행률 미정
                    }
                }
                InstallState.VERIFYING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // 검증 중: 진행률 미정
                else -> Unit // 나머지는 바 없음
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { // 동작 버튼
                when (row.state) {
                    InstallState.DOWNLOADING -> TextButton(onClick = onCancel) { Text("취소") } // 받는 중: 취소만
                    InstallState.VERIFYING -> Unit // 검증 중: 버튼 없음
                    else -> {
                        if (row.hasDownload && row.state != InstallState.READY) TextButton(onClick = onDownload) { Text("다운로드") } // 다운로드(설치 전/손상)
                        if (row.state != InstallState.READY) TextButton(onClick = onImport) { Text("가져오기") } // 가져오기
                        if (row.state == InstallState.READY || row.state == InstallState.CORRUPT) { // 설치본이 있을 때
                            TextButton(onClick = onVerify) { Text("검증") } // 검증
                            TextButton(onClick = onDelete) { Text("삭제") } // 삭제
                        }
                    }
                }
            }
        }
    }
}

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 시스템 엔진 카드 1개
 * 기능: 제목·라이선스 문구·언어 줄(언어 이름 + 상태)을 그린다. 상태가 "다운로드 필요" 이고 앱에서 요청할 수 있고 다른 다운로드가 진행 중이 아니면 [다운로드],
 *       다운로드 중인 언어는 진행 바를 보인다. 카드 아래에 [새로고침]. 조회 전(언어 줄이 없음)에는 "상태 확인 중…". 삭제·가져오기·검증 버튼은 없다.
 *       [다운로드] 는 Google 공식 다운로드 요청만 하며 동의 창은 사용자가 직접 확인을 누른다.
 *       change(add)-hyungchul-2026-10-11-20:05 다운로드 중인 언어 줄에는 [취소] 가 보인다(동의 창을 닫아도 끝나지 않을 수 있어 사용자가 추적을 끝내는 수단).
 * 입력: row(카드 값), canDownload(앱에서 다운로드 요청 가능 여부), onDownload(언어 코드로 다운로드 요청), onCancel(change(add)-hyungchul-2026-10-11-20:05 다운로드 취소), onRefresh(상태 다시 확인)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemEngineCard(
    row: SystemEngineRow,
    canDownload: Boolean,
    onDownload: (String) -> Unit,
    onCancel: () -> Unit, // change(add)-hyungchul-2026-10-11-20:05
    onRefresh: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) { // 카드
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall) // 제목
            Text(ModelsViewModel.SYSTEM_LICENSE, style = MaterialTheme.typography.bodySmall) // 라이선스 문구
            if (row.langs.isEmpty()) Text("상태 확인 중…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) // 조회 전
            row.langs.forEach { (lang, status) -> // 언어 줄
                val downloading = row.busyLang == lang // 이 언어 다운로드 중 여부
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { // 언어 + 상태 + 버튼
                    Text(
                        "${ModelsViewModel.languageLabel(setOf(lang))}: ${if (downloading) LanguageStatus.Downloading.koreanLabel() else status.koreanLabel()}", // 언어: 상태
                        style = MaterialTheme.typography.bodyMedium,
                        color = systemStatusColor(if (downloading) LanguageStatus.Downloading else status), // 상태 색
                        modifier = Modifier.weight(1f),
                    )
                    if (downloading) TextButton(onClick = onCancel) { Text("취소") } // change(add)-hyungchul-2026-10-11-20:05 다운로드 중: 취소
                    else if (status == LanguageStatus.Downloadable && canDownload && row.busyLang == null) { // 받을 수 있을 때만
                        TextButton(onClick = { onDownload(lang) }) { Text("다운로드") } // 다운로드
                    }
                }
                if (downloading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // 다운로드 중 진행 바(진행률 미정)
            }
            TextButton(onClick = onRefresh) { Text("새로고침") } // 상태 다시 확인
        }
    }
}

// change(add)-hyungchul-2026-10-11-19:52
/**
 * 목적: 언어 상태 글자색
 * 기능: 사용 가능=초록(라이트 진한 / 다크 밝은), 다운로드 필요·중=주색, 사용 불가=보조색.
 * 입력: status(언어 상태)
 * 출력: 없음
 * 리턴: 색
 */
@Composable
private fun systemStatusColor(status: LanguageStatus): Color = when (status) {
    LanguageStatus.Available -> if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32) // 사용 가능
    LanguageStatus.Downloadable, LanguageStatus.Downloading -> MaterialTheme.colorScheme.primary // 진행 필요·중
    is LanguageStatus.Unavailable -> MaterialTheme.colorScheme.onSurfaceVariant // 사용 불가
    is LanguageStatus.CheckFailed -> MaterialTheme.colorScheme.onSurfaceVariant // 확인 실패(사용 불가와 같은 색) // change(add)-hyungchul-2026-10-11-21:50
}

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 상업 이용 상태 색 배지
 * 기능: 상태별 배경색(가능=초록, 조건부=황색, 비상업=빨강, 미확인=회색)과 한국어 라벨을 둥근 면으로 그린다. 글자색은 배경 대비를 위해 황색만 어둡게.
 * 입력: status(상업 이용 상태)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
private fun CommercialBadge(status: CommercialStatus) {
    val (background, foreground) = when (status) {
        CommercialStatus.ALLOWED -> Color(0xFF2E7D32) to Color.White // 초록
        CommercialStatus.CONDITIONAL -> Color(0xFFF9A825) to Color.Black // 황색
        CommercialStatus.NONCOMMERCIAL -> Color(0xFFC62828) to Color.White // 빨강
        CommercialStatus.UNVERIFIED -> Color(0xFF757575) to Color.White // 회색
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(50)) { // 알약 모양
        Text(status.koreanLabel(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) // 라벨
    }
}

// change(add)-hyungchul-2026-10-11-03:09
/**
 * 목적: 설치 상태 글자색
 * 기능: 설치됨=초록(라이트 진한 / 다크 밝은), 손상=오류색, 진행 중=주색, 미설치=기본 보조색.
 * 입력: state(설치 상태)
 * 출력: 없음
 * 리턴: 색
 */
@Composable
private fun stateColor(state: InstallState): Color = when (state) {
    InstallState.READY -> if (isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF2E7D32) // 설치됨 — change(add)-hyungchul-2026-10-11-03:24 다크 모드에서는 밝은 초록으로 대비 확보
    InstallState.CORRUPT -> MaterialTheme.colorScheme.error // 손상
    InstallState.DOWNLOADING, InstallState.VERIFYING -> MaterialTheme.colorScheme.primary // 진행 중
    InstallState.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant // 미설치
}
