// 파일명: ui/models/ModelsScreen.kt
// 목적: 모델 관리 화면(Compose) — 카탈로그 목록과 다운로드·취소·가져오기·검증·삭제 조작
// 기능: ModelsViewModel.rows 를 그룹(ASR 은 algo 별, 마지막 "VAD 모델")으로 묶어 카드로 그린다. 카드에는 표시 이름·크기(MB)·언어·라이선스·
//       색 배지(상업 이용 상태)·설치 상태·진행률 바가 있고 상태에 맞는 버튼만 보인다. 삭제는 확인 대화상자, 가져오기는 폴더 경로 안내 대화상자,
//       검증은 결과 대화상자를 거치며 오류·완료 메시지는 스낵바(한국어)로 보인다.
package com.cowork.stt_ondevice.ui.models // 모델 화면 패키지

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
import androidx.compose.runtime.collectAsState // 상태 수집
import androidx.compose.runtime.getValue // 위임 읽기
import androidx.compose.runtime.mutableStateOf // 상태 생성
import androidx.compose.runtime.remember // 기억
import androidx.compose.runtime.setValue // 위임 쓰기
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.graphics.Color // 색
import androidx.compose.ui.platform.LocalContext // 컨텍스트
import androidx.compose.ui.unit.dp // dp 단위
import androidx.lifecycle.viewmodel.compose.viewModel // 화면용 ViewModel 얻기
import com.cowork.stt_ondevice.App // 앱 전역 객체
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
    val rows by viewModel.rows.collectAsState() // 행 목록
    val importPrompt by viewModel.importPrompt.collectAsState() // 가져오기 안내
    val verifyReport by viewModel.verifyReport.collectAsState() // 검증 결과
    val snackbar = remember { SnackbarHostState() } // 스낵바 상태
    var deleteTarget by remember { mutableStateOf<ModelRow?>(null) } // 삭제 확인 대상

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
                    Text("'${prompt.modelName}' 의 파일(폴더 또는 .tar.bz2/.zip 압축 파일 1개)을 PC 에서 아래 폴더로 adb push 한 뒤 '가져오기 실행'을 누르세요.") // 안내
                    SelectionContainer { Text(prompt.folderPath, style = MaterialTheme.typography.bodyMedium) } // 정확한 경로(선택·복사 가능)
                    Text("가져온 뒤에도 원본은 지워지지 않습니다. 필요 없으면 직접 지우세요.", style = MaterialTheme.typography.bodySmall) // 부가 안내
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
 * 기능: 설치됨=초록, 손상=오류색, 진행 중=주색, 미설치=기본 보조색.
 * 입력: state(설치 상태)
 * 출력: 없음
 * 리턴: 색
 */
@Composable
private fun stateColor(state: InstallState): Color = when (state) {
    InstallState.READY -> Color(0xFF2E7D32) // 설치됨
    InstallState.CORRUPT -> MaterialTheme.colorScheme.error // 손상
    InstallState.DOWNLOADING, InstallState.VERIFYING -> MaterialTheme.colorScheme.primary // 진행 중
    InstallState.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant // 미설치
}
