// 파일명: ui/run/RunSetupScreen.kt
// 목적: 실행 설정 화면(Compose) — 입력 언어·파일, 모델(선택 순서), VAD 프리셋(계열별 접기), chunk 모드·maxChunk·스레드를 고르고 배치 실행을 시작
// 기능: RunSetupViewModel.state 를 섹션별 목록으로 그린다. 미설치 모델·VAD 모델 미설치 프리셋은 비활성과 사유를 보인다. 예상 조합 수를 실시간 표시하고
//       "실행" 을 누르면 (Android 13+ 에서 알림 권한이 없으면 먼저 요청 — 거부해도 실행은 계속) buildConfig(지금 시각) → RunService.start.
//       시작이 거부되면 RunService.events 의 사유를 스낵바로 보이고, 시작되면 onStarted 로 진행 탭으로 이동한다.
//       알림 권한 요청 도우미(rememberNotificationGate)는 결과 화면의 이어하기도 함께 쓴다.
//       change(add)-hyungchul-2026-10-11-03:59 "음원 가져오기(SAF)": OpenDocumentTree 로 폴더를 고르면 SafBridge.inspectTree 로 구조를 보고(최상위 kr/en → 그 폴더들,
//       트리 자체가 kr/en → 그 언어 폴더, 그 밖 → 언어를 묻는 대화상자 → input_sound/<lang>/<트리 이름>/) RunSetupViewModel.runImport 로 복사한 뒤 목록을 다시 읽는다.
//       마이크 화면에서 넘어온 preselectPath 는 다시 스캔 후 그 파일을 선택한다. 입력이 없을 때 안내에 adb 스크립트(tools/adb/push_inputs.sh — push 후 권한 정리)와 SAF 를 권장한다(R41).
//       change(add)-hyungchul-2026-10-11-12:51 입력 스캔 오류(같은 폴더에 대소문자만 다른 같은 이름 wav — 실행 목록에서 뺌)를 입력 파일 섹션에 빨간 글씨로 보인다(T3 이월).
//       change(add)-hyungchul-2026-10-11-20:15 (2차 작업 9) VAD 프리셋 목록의 기본 계열 뒤에 "사용자 프리셋" 소제목을 두고 [복사해서 만들기](기준 고르기 → 값 폼 → 이름 → 저장)와 USER 행별 [삭제]를 붙인다.
//       삭제가 미완료 실행을 건드리면 경고 대화상자를 보이고, USER 프리셋 저장소 읽기 오류(손상 파일 등)는 소제목 아래에 빨간 글씨로 보인다.
// change(add)-hyungchul-2026-10-11-03:31
package com.cowork.stt_ondevice.ui.run // 실행 화면 패키지

import android.Manifest // 권한 이름
import android.content.pm.PackageManager // 권한 결과 값
import android.os.Build // SDK 버전
import androidx.activity.compose.rememberLauncherForActivityResult // 권한 요청 런처
import androidx.activity.result.contract.ActivityResultContracts // 권한 요청 계약
import android.net.Uri // change(add)-hyungchul-2026-10-11-03:59 고른 트리 Uri
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
import androidx.compose.foundation.layout.padding // 여백
import androidx.compose.foundation.lazy.LazyColumn // 지연 목록
import androidx.compose.foundation.lazy.items // 목록 항목
import androidx.compose.foundation.text.KeyboardOptions // 키보드 종류
import androidx.compose.material.icons.Icons // 기본 아이콘
import androidx.compose.material.icons.filled.KeyboardArrowDown // 펼침 아이콘
import androidx.compose.material.icons.filled.KeyboardArrowUp // 접기 아이콘
import androidx.compose.material3.AlertDialog // change(add)-hyungchul-2026-10-11-03:59 언어 선택 대화상자
import androidx.compose.material3.Button // 버튼
import androidx.compose.material3.Card // 카드
import androidx.compose.material3.Checkbox // 체크박스
import androidx.compose.material3.FilterChip // 선택 칩
import androidx.compose.material3.HorizontalDivider // 구분선
import androidx.compose.material3.LinearProgressIndicator // change(add)-hyungchul-2026-10-11-03:59 가져오기 진행 표시
import androidx.compose.material3.Icon // 아이콘
import androidx.compose.material3.MaterialTheme // 테마 값
import androidx.compose.material3.OutlinedTextField // 입력 칸
import androidx.compose.material3.Scaffold // 화면 골격
import androidx.compose.material3.Slider // 슬라이더
import androidx.compose.material3.SnackbarHost // 스낵바 영역
import androidx.compose.material3.SnackbarHostState // 스낵바 상태
import androidx.compose.material3.Text // 텍스트
import androidx.compose.material3.TextButton // 텍스트 버튼
import androidx.compose.material3.TriStateCheckbox // 3상태 체크박스(폴더 전체 선택)
import androidx.compose.runtime.Composable // 컴포저블
import androidx.compose.runtime.LaunchedEffect // 부수 효과
import androidx.compose.runtime.getValue // 위임 읽기
import androidx.compose.runtime.mutableStateMapOf // 상태 맵
import androidx.compose.runtime.mutableStateOf // 상태 생성
import androidx.compose.runtime.remember // 기억
import androidx.compose.runtime.rememberCoroutineScope // 코루틴 범위
import androidx.compose.runtime.saveable.rememberSaveable // change(add)-hyungchul-2026-10-11-03:59 선택기 왕복·회전에도 유지
import androidx.compose.runtime.setValue // 위임 쓰기
import androidx.compose.ui.Alignment // 정렬
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.platform.LocalContext // 컨텍스트
import androidx.compose.ui.state.ToggleableState // 3상태 값
import androidx.compose.ui.text.font.FontFamily // 고정폭 글꼴
import androidx.compose.ui.text.input.KeyboardType // 숫자 키보드
import androidx.compose.ui.unit.dp // dp 단위
import androidx.core.content.ContextCompat // 권한 확인
import androidx.lifecycle.compose.collectAsStateWithLifecycle // 생명주기 인식 상태 수집
import androidx.lifecycle.viewmodel.compose.viewModel // 화면용 ViewModel
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.core.storage.InputScanner // 언어 폴더 목록
import com.cowork.stt_ondevice.core.storage.InputTreeLayout // change(add)-hyungchul-2026-10-11-03:59 음원 트리 구조
import com.cowork.stt_ondevice.core.storage.SafBridge // change(add)-hyungchul-2026-10-11-03:59 SAF 입출력
import com.cowork.stt_ondevice.runner.RunService // 배치 실행 서비스
import com.cowork.stt_ondevice.runner.RunUiState // 실행 상태
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.vad.VadFamily // change(add)-hyungchul-2026-10-11-20:15 기본 프리셋 후보에서 VAD 없음 제외
import kotlinx.coroutines.launch // 코루틴 시작
import java.io.File // change(add)-hyungchul-2026-10-11-03:59 파일
import java.io.IOException // change(add)-hyungchul-2026-10-11-03:59 입출력 오류
import java.time.LocalDateTime // 현재 시각
import kotlin.math.roundToInt // 반올림

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 알림 권한을 확인한 뒤 동작 실행(실행 시작용)
 * 기능: Android 13+ 에서 POST_NOTIFICATIONS 가 없으면 권한을 요청하고, 결과와 상관없이 동작을 실행한다(거부되면 onDenied 로 안내).
 *       12 이하이거나 이미 허용이면 바로 실행한다. 권한 대화상자 도중 화면이 다시 만들어지면 대기 동작은 사라진다(다시 누르면 됨).
 * 입력: onDenied(권한이 거부됐을 때 부를 안내 함수)
 * 출력: 없음
 * 리턴: (동작) -> Unit 형태의 실행 함수
 */
@Composable
fun rememberNotificationGate(onDenied: () -> Unit): (() -> Unit) -> Unit {
    val context = LocalContext.current // 컨텍스트
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) } // 권한 결과를 기다리는 동작
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> // 권한 결과
        if (!granted) onDenied() // 거부 안내
        val action = pending // 대기 동작
        pending = null // 비우기
        action?.invoke() // 결과와 상관없이 실행(거부가 실행을 막지 않음)
    }
    return { action ->
        val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && // Android 13+
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED // 아직 없음
        if (needsAsk) { // 요청 필요
            pending = action // 대기
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS) // 요청
        } else {
            action() // 바로 실행
        }
    }
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 언어 폴더명 → 한국어 라벨
 * 기능: kr=한국어(kr), en=영어(en), 그 밖은 그대로.
 * 입력: lang(언어 폴더명)
 * 출력: 없음
 * 리턴: 라벨
 */
fun langFolderLabel(lang: String): String = when (lang) {
    "kr" -> "한국어(kr)" // 한국어
    "en" -> "영어(en)" // 영어
    else -> lang // 그 밖
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 실행 설정 화면
 * 기능: 파일 머리말 참조. 화면에 들어올 때마다 입력 목록·설치 상태를 다시 읽는다(모델 탭에서 설치한 결과 반영).
 * 입력: onStarted(실행이 시작됐을 때 — 진행 탭으로 이동), preselectPath(change(add)-hyungchul-2026-10-11-03:59 마이크 화면에서 넘어온 녹음 wav 절대 경로 또는 null),
 *       viewModel(기본은 App 싱글턴으로 만든 RunSetupViewModel)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunSetupScreen(
    onStarted: (String) -> Unit,
    preselectPath: String? = null, // change(add)-hyungchul-2026-10-11-03:59 미리 선택할 파일
    viewModel: RunSetupViewModel = viewModel(factory = RunSetupViewModel.factory(App.from(LocalContext.current))),
) {
    val context = LocalContext.current // 컨텍스트
    val app = App.from(context) // 앱 전역 객체(입력 폴더 경로 안내용)
    val s by viewModel.state.collectAsStateWithLifecycle() // 화면 상태
    val runState by RunService.events.collectAsStateWithLifecycle() // 실행 상태
    val snackbar = remember { SnackbarHostState() } // 스낵바
    val scope = rememberCoroutineScope() // 스낵바용 범위
    val expandedFolders = remember { mutableStateMapOf<String, Boolean>() } // 펼친 입력 폴더
    val expandedFamilies = remember { mutableStateMapOf<String, Boolean>() } // 펼친 VAD 계열
    val gate = rememberNotificationGate { // 알림 권한 확인
        scope.launch { snackbar.showSnackbar("알림 권한이 없어 진행 알림이 보이지 않을 수 있습니다(실행은 계속됩니다)") } // 거부 안내
    }

    LaunchedEffect(Unit) { viewModel.refresh() } // 들어올 때마다 다시 읽기
    LaunchedEffect(preselectPath) { preselectPath?.let { viewModel.preselect(File(it)) } } // change(add)-hyungchul-2026-10-11-03:59 마이크 녹음 파일 미리 선택
    LaunchedEffect(s.message) { // change(add)-hyungchul-2026-10-11-03:59 안내 문구(가져오기 결과)
        s.message?.let { viewModel.consumeMessage(); scope.launch { snackbar.showSnackbar(it) } } // 소비 후 표시 — change(add)-hyungchul-2026-10-11-03:59 화면 범위에서 표시(소비로 키가 바뀌어 이 효과가 취소돼도 스낵바가 바로 사라지지 않게)
    }
    val bridge = remember { SafBridge(context.applicationContext) } // change(add)-hyungchul-2026-10-11-03:59 SAF 다리
    var askLangFor by rememberSaveable { mutableStateOf<String?>(null) } // change(add)-hyungchul-2026-10-11-03:59 언어를 물을 트리 Uri 문자열
    var askLangName by rememberSaveable { mutableStateOf("") } // change(add)-hyungchul-2026-10-11-03:59 그 트리 이름
    var pickingBase by rememberSaveable { mutableStateOf(false) } // 복사할 기본 프리셋 고르는 중 // change(add)-hyungchul-2026-10-11-20:15

    // change(add)-hyungchul-2026-10-11-03:59
    /** 목적: 정해진 대상으로 가져오기 시작 / 입력: uri(트리), layout(구조), lang(고른 언어 또는 null), name(트리 이름) / 리턴: 없음 */
    fun startImport(uri: Uri, layout: InputTreeLayout, lang: String?, name: String) {
        val (dest, topDirs) = SafBridge.inputDest(app.dirs.inputSound, layout, lang, name) // 대상 폴더·최상위 제한
        viewModel.runImport { bridge.importTree(uri, dest, topDirs) } // 복사 작업(ViewModel 범위 — 탭 이동에도 계속)
    }

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> // change(add)-hyungchul-2026-10-11-03:59 폴더 선택 결과
        if (uri == null) return@rememberLauncherForActivityResult // 취소
        scope.launch {
            val info = try { bridge.inspectTree(uri) } catch (e: IOException) { snackbar.showSnackbar("폴더를 읽지 못했습니다: ${e.message}"); return@launch } // 구조 읽기
            when (val layout = SafBridge.detectLayout(info.name, info.topDirs)) { // 구조 판정
                InputTreeLayout.Unknown -> { askLangFor = uri.toString(); askLangName = info.name } // 언어 묻기
                else -> startImport(uri, layout, null, info.name) // 바로 가져오기
            }
        }
    }

    val busy = runState is RunUiState.Running // 다른 실행이 진행 중인지

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 인셋은 바깥 Scaffold 가 처리
        snackbarHost = { SnackbarHost(snackbar) }, // 스낵바
        bottomBar = { // 하단 고정: 조합 수 + 실행
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HorizontalDivider() // 구분선
                val invalid = viewModel.validationError(s) // 실행 불가 사유 — 이 영역이 s 를 읽어 다시 그려질 때마다 같은 상태로 계산
                Text("예상 조합 수: ${s.comboCount}건 (모델 × 프리셋 × 지원 언어 파일)", style = MaterialTheme.typography.titleSmall) // 조합 수
                when {
                    busy -> Text("다른 실행이 진행 중입니다(${(runState as RunUiState.Running).runId}). 진행 탭에서 확인하세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) // 실행 중
                    invalid != null -> Text(invalid, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) // 실행 불가 사유
                }
                Button(
                    onClick = {
                        gate { // 권한 확인 후
                            val cfg = try {
                                viewModel.buildConfig(LocalDateTime.now()) // 설정 조립(runId = 지금)
                            } catch (e: IllegalStateException) {
                                scope.launch { snackbar.showSnackbar(e.message ?: "설정 오류") } // 사유
                                null
                            } catch (e: IllegalArgumentException) {
                                scope.launch { snackbar.showSnackbar(e.message ?: "설정 오류") } // RunConfig 검증 실패
                                null
                            }
                            if (cfg != null) {
                                if (RunService.start(context, cfg)) onStarted(cfg.runId) // 시작 → 진행 탭
                                else scope.launch { snackbar.showSnackbar("실행 시작 실패: ${RunSetupViewModel.startFailureReason(RunService.events.value)}") } // 거부 사유
                            }
                        }
                    },
                    enabled = !busy && invalid == null, // 실행 가능할 때만
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("실행") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding), // 화면 전체
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), // 여백
            verticalArrangement = Arrangement.spacedBy(6.dp), // 간격
        ) {
            item(key = "title") { Text("실행 설정", style = MaterialTheme.typography.headlineSmall) } // 제목

            // ---- 입력 언어 ----
            item(key = "lang-h") { SectionTitle("입력 언어") } // 머리글
            item(key = "lang") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InputScanner.LANG_FOLDERS.keys.forEach { lang -> // 언어마다
                        FilterChip(selected = lang in s.langs, onClick = { viewModel.toggleLang(lang) }, label = { Text(langFolderLabel(lang)) }) // 켜기/끄기
                    }
                }
            }

            // ---- 입력 파일 ----
            item(key = "files-h") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("입력 파일 (선택 ${s.selectedItems.count { it.lang in s.langs }}개)", Modifier.weight(1f)) // 머리글
                    TextButton(onClick = viewModel::refresh) { Text("새로고침") } // 다시 스캔
                }
            }
            item(key = "files-import") { // change(add)-hyungchul-2026-10-11-03:59 SAF 음원 가져오기
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.importing) { // 가져오는 중
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // 진행 표시(미정)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("음원을 앱 폴더로 복사하는 중…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) // 안내
                            TextButton(onClick = viewModel::cancelImport) { Text("가져오기 취소") } // 취소
                        }
                    } else {
                        TextButton(onClick = { treePicker.launch(null) }) { Text("음원 가져오기(SAF)") } // 폴더 고르기
                    }
                }
            }
            if (s.inputErrors.isNotEmpty()) { // 입력 스캔 오류가 있으면 // change(add)-hyungchul-2026-10-11-12:51 (T3 이월)
                item(key = "files-errors") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { // 오류 목록
                        Text("입력 오류 ${s.inputErrors.size}건 — 아래 파일은 실행 목록에서 뺐습니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) // 머리말
                        s.inputErrors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } // 오류마다 한 줄
                    }
                }
            }
            val visibleFolders = s.folders.filter { it.lang in s.langs } // 켜진 언어의 폴더
            if (s.loaded && visibleFolders.isEmpty()) {
                item(key = "files-empty") {
                    Text("입력 음원(wav)이 없습니다. 위 '음원 가져오기(SAF)' 로 폴더를 고르거나, PC 에서 tools/adb/push_inputs.sh <input_sound 폴더> 로 넣어 주세요(스크립트가 push 후 앱이 읽을 수 있게 권한도 정리합니다 — adb push 만 하면 앱이 하위 폴더를 못 읽습니다). 대상: ${app.dirs.inputSound.path}/kr, en", style = MaterialTheme.typography.bodySmall) // 안내(change(add)-hyungchul-2026-10-11-03:59 R41 스크립트·SAF 권장)
                }
            }
            visibleFolders.forEach { folder -> // 폴더마다
                val key = "${folder.lang}/${folder.relDir}" // 폴더 키
                val open = expandedFolders[key] == true // 펼침 여부
                val picked = folder.items.count { it in s.selectedItems } // 선택 수
                item(key = "folder-$key") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { expandedFolders[key] = !open }) {
                        TriStateCheckbox( // 폴더 전체 선택
                            state = when (picked) { 0 -> ToggleableState.Off; folder.items.size -> ToggleableState.On; else -> ToggleableState.Indeterminate }, // 상태
                            onClick = { viewModel.toggleFolder(folder.lang, folder.relDir) }, // 전체 선택/해제
                        )
                        Text("${folder.lang}/${folder.relDir.ifEmpty { "(최상위)" }}  ($picked/${folder.items.size})", modifier = Modifier.weight(1f)) // 폴더 이름
                        Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "접기" else "펼치기") // 펼침 표시
                    }
                }
                if (open) {
                    items(folder.items, key = { "file-${it.file.path}" }) { item -> // 파일마다
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 32.dp)) {
                            Checkbox(checked = item in s.selectedItems, onCheckedChange = { viewModel.toggleItem(item) }) // 선택
                            Text(item.file.name, style = MaterialTheme.typography.bodyMedium) // 파일명
                        }
                    }
                }
            }

            // ---- 모델 ----
            item(key = "models-h") { SectionTitle("모델 (선택 순서 = 실행 순서, ${s.selectedModels.size}개)") } // 머리글
            s.models.groupBy { it.group }.forEach { (group, rows) -> // algo 묶음
                item(key = "mgroup-$group") { Text(group, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) } // 묶음 이름
                items(rows, key = { "model-${it.id}" }) { m -> // 모델마다
                    val order = s.selectedModels.indexOf(m.id) // 선택 순번(-1 = 미선택)
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = order >= 0, onCheckedChange = { viewModel.toggleModel(m.id) }, enabled = m.selectable || order >= 0) // 선택(미설치는 비활성)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium, color = if (m.selectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant) // 이름
                                Text(m.languages + (m.disabledReason?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = if (m.disabledReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) // 언어·사유
                            }
                            if (order >= 0) Text("${order + 1}번", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) // 실행 순번
                        }
                        if (order >= 0) { // 선택한 모델만 maxChunk 입력
                            val input = s.maxChunk[m.id] // 입력 칸 상태
                            OutlinedTextField(
                                value = input?.text ?: "", // 입력 글자
                                onValueChange = { viewModel.setMaxChunkText(m.id, it) }, // 검증 후 반영
                                label = { Text("최대 chunk 길이(초) — 비우면 기본 ${formatSec(m.defaultMaxChunkSec)}") }, // 안내
                                isError = input?.error != null, // 오류 표시
                                supportingText = { Text(input?.error ?: "0 = 제한 없음") }, // 오류 또는 도움말
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), // 숫자 키보드
                                singleLine = true, // 한 줄
                                modifier = Modifier.fillMaxWidth().padding(start = 48.dp), // 들여쓰기
                            )
                        }
                    }
                }
            }

            // ---- VAD 프리셋 ----
            item(key = "presets-h") { SectionTitle("VAD 프리셋 (선택 ${s.selectedPresets.size}개)") } // 머리글
            s.presets.filter { !it.user }.groupBy { it.family }.forEach { (family, rows) -> // 계열마다(기본 프리셋만 — USER 는 아래 별도 소제목) // change(add)-hyungchul-2026-10-11-20:15
                val open = expandedFamilies[family.name] == true // 펼침 여부
                val picked = rows.count { it.id in s.selectedPresets } // 선택 수
                item(key = "family-${family.name}") {
                    Card(modifier = Modifier.fillMaxWidth().clickable { expandedFamilies[family.name] = !open }) { // 계열 머리글
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${family.name}  (선택 $picked/${rows.size})", style = MaterialTheme.typography.titleSmall) // 계열·선택 수
                                rows.firstOrNull { !it.selectable }?.disabledReason?.let { // 계열 공통 사유
                                    if (rows.none { it.selectable }) Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) // 전부 불가일 때 표시
                                }
                            }
                            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = if (open) "접기" else "펼치기") // 펼침 표시
                        }
                    }
                }
                if (open) {
                    items(rows, key = { "preset-${it.id}" }) { p -> // 프리셋마다
                        Row(modifier = Modifier.padding(start = 8.dp)) {
                            Checkbox(checked = p.id in s.selectedPresets, onCheckedChange = { viewModel.togglePreset(p.id) }, enabled = p.selectable || p.id in s.selectedPresets) // 선택
                            Column(modifier = Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(p.id, style = MaterialTheme.typography.bodyMedium) // id
                                Text("출처: ${p.source}", style = MaterialTheme.typography.bodySmall) // 출처
                                if (p.params.isNotEmpty()) Text(p.params, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) // 파라미터
                                if (p.note.isNotEmpty()) Text("주의: ${p.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 주의
                                p.disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } // 사유
                            }
                        }
                    }
                }
            }

            // ---- 사용자 프리셋 ---- // change(add)-hyungchul-2026-10-11-20:15
            val userRows = s.presets.filter { it.user } // USER 프리셋 행
            item(key = "user-presets-h") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("사용자 프리셋 (${userRows.size}개, 선택 ${userRows.count { it.id in s.selectedPresets }}개)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(top = 8.dp)) // 소제목
                    TextButton(onClick = { pickingBase = true }) { Text("복사해서 만들기") } // 기준 고르기 시작
                }
            }
            s.userPresetError?.let { err -> // 저장소 읽기 오류는 숨기지 않음
                item(key = "user-presets-err") { Text("사용자 프리셋 저장소 오류: $err", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } // 오류 문구
            }
            if (userRows.isEmpty()) {
                item(key = "user-presets-empty") { Text("만든 사용자 프리셋이 없습니다. [복사해서 만들기]로 기본 프리셋의 값을 바꿔 새 프리셋을 만드세요.", style = MaterialTheme.typography.bodySmall) } // 빈 안내
            }
            items(userRows, key = { "preset-${it.id}" }) { p -> // USER 프리셋마다
                Row(modifier = Modifier.padding(start = 8.dp)) {
                    Checkbox(checked = p.id in s.selectedPresets, onCheckedChange = { viewModel.togglePreset(p.id) }, enabled = p.selectable || p.id in s.selectedPresets) // 선택
                    Column(modifier = Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${p.id}  (${p.family.name})", style = MaterialTheme.typography.bodyMedium) // id·계열
                        Text("출처: ${p.source}", style = MaterialTheme.typography.bodySmall) // 출처
                        if (p.params.isNotEmpty()) Text(p.params, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) // 파라미터
                        p.disabledReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } // 사유
                    }
                    TextButton(onClick = { viewModel.requestDeleteUserPreset(p.id) }) { Text("삭제") } // 삭제(미완료 실행이 쓰면 경고)
                }
            }

            // ---- 실행 옵션 ----
            item(key = "opts-h") { SectionTitle("실행 옵션") } // 머리글
            item(key = "chunk") {
                Column {
                    Text("chunk 묶기 방식", style = MaterialTheme.typography.bodyMedium) // 이름
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.chunkMode == ChunkMode.MERGE, onClick = { viewModel.setChunkMode(ChunkMode.MERGE) }, label = { Text("MERGE (발화 합치기)") }) // 합치기
                        FilterChip(selected = s.chunkMode == ChunkMode.INDIVIDUAL, onClick = { viewModel.setChunkMode(ChunkMode.INDIVIDUAL) }, label = { Text("INDIVIDUAL (발화별)") }) // 발화별
                    }
                }
            }
            item(key = "threads") {
                Column {
                    Text("스레드 수: ${s.threads}", style = MaterialTheme.typography.bodyMedium) // 현재 값
                    Slider( // 1~8
                        value = s.threads.toFloat(), // 현재 값
                        onValueChange = { viewModel.setThreads(it.roundToInt()) }, // 반올림 반영
                        valueRange = RunSetupViewModel.MIN_THREADS.toFloat()..RunSetupViewModel.MAX_THREADS.toFloat(), // 범위
                        steps = RunSetupViewModel.MAX_THREADS - RunSetupViewModel.MIN_THREADS - 1, // 정수 눈금
                    )
                }
            }
        }
    }

    askLangFor?.let { uriText -> // change(add)-hyungchul-2026-10-11-03:59 언어 폴더를 알 수 없는 트리 → 언어 묻기
        AlertDialog(
            onDismissRequest = { askLangFor = null }, // 바깥 탭 = 취소
            title = { Text("어느 언어 폴더로 가져올까요?") }, // 제목
            text = { Text("고른 폴더 '$askLangName' 에 kr/ 또는 en/ 폴더가 없습니다. 고른 언어 폴더 아래 '$askLangName' 폴더로 wav 를 복사합니다(하위 폴더 구조 유지).") }, // 설명
            confirmButton = {
                Row {
                    InputScanner.LANG_FOLDERS.keys.forEach { lang -> // 언어마다
                        TextButton(onClick = { askLangFor = null; startImport(Uri.parse(uriText), InputTreeLayout.Unknown, lang, askLangName) }) { Text(langFolderLabel(lang)) } // 그 언어로
                    }
                }
            },
            dismissButton = { TextButton(onClick = { askLangFor = null }) { Text("취소") } }, // 취소
        )
    }

    if (pickingBase) { // 복사할 기본 프리셋 고르기 // change(add)-hyungchul-2026-10-11-20:15
        BasePresetPickerDialog(
            bases = s.presets.filter { !it.user && it.family != VadFamily.NONE }, // 기본 프리셋(VAD 없음 제외)
            onPick = { pickingBase = false; viewModel.startUserPreset(it) }, // 폼 열기
            onDismiss = { pickingBase = false }, // 취소
        )
    }
    s.userDraft?.let { draft -> // USER 프리셋 값 폼 // change(add)-hyungchul-2026-10-11-20:15
        UserPresetFormDialog(draft, onField = viewModel::setUserField, onName = viewModel::setUserName, onSave = viewModel::saveUserPreset, onCancel = viewModel::cancelUserDraft)
    }
    val warning = s.deleteWarning // 삭제 경고 // change(add)-hyungchul-2026-10-11-20:15
    val target = s.deleteTarget // 경고 중인 프리셋
    if (warning != null && target != null) {
        DeleteWarningDialog(target, warning, onConfirm = { viewModel.confirmDeleteUserPreset(target) }, onCancel = viewModel::cancelDeleteUserPreset)
    }
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 섹션 머리글
 * 기능: 주색 titleMedium 글자로 그린다.
 * 입력: text(머리글), modifier(수정자)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier.padding(top = 8.dp)) // 머리글
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 초 값 표시 문자열
 * 기능: 0 이면 "제한 없음", 정수면 "30초", 아니면 소수 그대로 "12.5초".
 * 입력: sec(초)
 * 출력: 없음
 * 리턴: 문자열
 */
private fun formatSec(sec: Double): String = when {
    sec == 0.0 -> "제한 없음" // 0
    sec == Math.floor(sec) -> "${sec.toLong()}초" // 정수
    else -> "${sec}초" // 소수
}
