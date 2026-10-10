// 파일명: ui/mic/MicScreen.kt
// 목적: 마이크 화면(Compose) — 언어 선택, 녹음/정지, 경과 시간, 레벨 미터, 저장 경로, "실행 설정으로" 이동
// 기능: "녹음" 을 누르면 RECORD_AUDIO 권한을 확인하고(없으면 요청) MicViewModel.start, "정지" 는 stop(→ 저장).
//       권한이 거부되면 한국어 설명과 "권한 다시 요청" 버튼을 보인다(크래시 없음, 계속 거부되면 휴대폰 설정에서 허용 안내).
//       화면을 떠나거나(탭 이동) 앱이 백그라운드로 가면 녹음을 멈추고 저장한다. 저장되면 경로·실제 형식을 보이고,
//       "실행 설정으로" 를 누르면 그 파일을 선택한 실행 탭으로 이동한다(onOpenRunSetup).
// change(add)-hyungchul-2026-10-11-03:59
package com.cowork.stt_ondevice.ui.mic // 마이크 화면 패키지

import android.Manifest // 권한 이름
import android.content.pm.PackageManager // 권한 결과 값
import androidx.activity.compose.rememberLauncherForActivityResult // 권한 요청 런처
import androidx.activity.result.contract.ActivityResultContracts // 권한 요청 계약
import androidx.compose.foundation.layout.Arrangement // 배치 간격
import androidx.compose.foundation.layout.Column // 세로 배치
import androidx.compose.foundation.layout.ExperimentalLayoutApi // 실험적 레이아웃 표시
import androidx.compose.foundation.layout.FlowRow // 줄바꿈 가로 배치
import androidx.compose.foundation.layout.WindowInsets // 시스템 인셋
import androidx.compose.foundation.layout.fillMaxSize // 가득 채우기
import androidx.compose.foundation.layout.fillMaxWidth // 가로 채우기
import androidx.compose.foundation.layout.padding // 여백
import androidx.compose.foundation.rememberScrollState // 스크롤 상태
import androidx.compose.foundation.text.selection.SelectionContainer // 텍스트 선택(경로 복사)
import androidx.compose.foundation.verticalScroll // 세로 스크롤
import androidx.compose.material3.Button // 버튼
import androidx.compose.material3.ButtonDefaults // 버튼 색
import androidx.compose.material3.FilterChip // 선택 칩
import androidx.compose.material3.LinearProgressIndicator // 레벨 막대
import androidx.compose.material3.MaterialTheme // 테마 값
import androidx.compose.material3.OutlinedButton // 외곽선 버튼
import androidx.compose.material3.Scaffold // 화면 골격
import androidx.compose.material3.SnackbarHost // 스낵바 영역
import androidx.compose.material3.SnackbarHostState // 스낵바 상태
import androidx.compose.material3.Text // 텍스트
import androidx.compose.runtime.Composable // 컴포저블
import androidx.compose.runtime.DisposableEffect // 화면 이탈 처리
import androidx.compose.runtime.LaunchedEffect // 부수 효과
import androidx.compose.runtime.getValue // 위임 읽기
import androidx.compose.runtime.mutableStateOf // 상태 생성
import androidx.compose.runtime.remember // 기억
import androidx.compose.runtime.rememberCoroutineScope // 코루틴 범위
import androidx.compose.runtime.saveable.rememberSaveable // 재구성·회전에도 유지
import androidx.compose.runtime.setValue // 위임 쓰기
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.platform.LocalContext // 컨텍스트
import androidx.compose.ui.text.font.FontFamily // 고정폭 글꼴
import androidx.compose.ui.unit.dp // dp 단위
import androidx.core.content.ContextCompat // 권한 확인
import androidx.lifecycle.Lifecycle // 생명주기 이벤트
import androidx.lifecycle.compose.LifecycleEventEffect // 생명주기 이벤트 처리
import androidx.lifecycle.compose.collectAsStateWithLifecycle // 생명주기 인식 상태 수집
import androidx.lifecycle.viewmodel.compose.viewModel // 화면용 ViewModel
import com.cowork.stt_ondevice.App // 앱 전역 객체
import com.cowork.stt_ondevice.ui.run.langFolderLabel // 언어 폴더 라벨
import kotlinx.coroutines.launch // 코루틴 시작
import kotlin.math.log10 // 상용로그

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 마이크 화면
 * 기능: 파일 머리말 참조.
 * 입력: onOpenRunSetup(저장한 wav 절대 경로를 받아 실행 탭으로 이동), viewModel(기본은 App 싱글턴으로 만든 MicViewModel)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MicScreen(
    onOpenRunSetup: (String) -> Unit,
    viewModel: MicViewModel = viewModel(factory = MicViewModel.factory(App.from(LocalContext.current))),
) {
    val context = LocalContext.current // 컨텍스트
    val s by viewModel.state.collectAsStateWithLifecycle() // 화면 상태
    val snackbar = remember { SnackbarHostState() } // 스낵바
    val scope = rememberCoroutineScope() // 스낵바 표시 범위
    var denied by rememberSaveable { mutableStateOf(false) } // 권한 거부 여부
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> // 권한 결과
        denied = !granted // 거부 표시
        if (granted) viewModel.start() // 허용되면 바로 녹음
    }

    /** 목적: 권한 확인 후 녹음 시작 / 기능: 있으면 start, 없으면 요청 / 입력·출력: 없음 / 리턴: 없음 */
    fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { // 이미 허용
            denied = false // 거부 표시 해제
            viewModel.start() // 시작
        } else {
            launcher.launch(Manifest.permission.RECORD_AUDIO) // 요청
        }
    }

    DisposableEffect(Unit) { onDispose { viewModel.stop() } } // 화면을 떠나면 녹음 정지(→ 저장)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stop() } // 백그라운드로 가면 녹음 정지(→ 저장)
    LaunchedEffect(s.message) { // 안내 문구
        s.message?.let { viewModel.consumeMessage(); scope.launch { snackbar.showSnackbar(it) } } // 소비 후 표시 — change(add)-hyungchul-2026-10-11-03:59 화면 범위에서 표시(소비로 키가 바뀌어 이 효과가 취소돼도 스낵바가 바로 사라지지 않게)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0), // 인셋은 바깥 Scaffold 가 처리
        snackbarHost = { SnackbarHost(snackbar) }, // 스낵바
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), // 화면 전체(스크롤)
            verticalArrangement = Arrangement.spacedBy(12.dp), // 간격
        ) {
            Text("마이크 녹음", style = MaterialTheme.typography.headlineSmall) // 제목
            Text("녹음 후 저장한 파일을 실행 설정에서 골라 전사합니다(실시간 전사 없음). 16 kHz mono PCM16 WAV 로 저장됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 설명

            Text("저장 언어 폴더", style = MaterialTheme.typography.titleSmall) // 언어 머리글
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MicViewModel.LANGS.forEach { lang -> // 언어마다
                    FilterChip(selected = s.lang == lang, onClick = { viewModel.setLang(lang) }, enabled = !s.recording && !s.saving, label = { Text(langFolderLabel(lang)) }) // 선택 칩(녹음 중 비활성)
                }
            }

            if (s.recording) { // 녹음 중
                Button(onClick = viewModel::stop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()) { Text("정지") } // 정지
            } else {
                Button(onClick = ::startWithPermission, enabled = !s.saving, modifier = Modifier.fillMaxWidth()) { Text(if (s.saving) "저장 중…" else "녹음") } // 녹음
            }

            Text("경과 시간 ${formatElapsed(s.elapsedSec)}", style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace) // 경과 시간
            Text("입력 레벨", style = MaterialTheme.typography.bodySmall) // 레벨 머리글
            LinearProgressIndicator(progress = { levelToMeter(s.level) }, modifier = Modifier.fillMaxWidth()) // 레벨 막대(RMS, dBFS -60..0)
            s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } // 16 kHz 대체 녹음 안내

            if (denied) { // 권한 거부
                Text(
                    "마이크 권한이 거부되어 녹음할 수 없습니다. 아래 버튼으로 다시 요청하세요. 요청 창이 더 뜨지 않으면 휴대폰 설정 > 애플리케이션 > STT_OnDevice > 권한 에서 마이크를 허용하세요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                ) // 설명
                OutlinedButton(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) { Text("권한 다시 요청") } // 다시 요청
            }

            s.savedPath?.let { path -> // 저장 결과
                Text("저장한 파일", style = MaterialTheme.typography.titleSmall) // 머리글
                SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } // 경로(복사 가능)
                s.savedInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) } // 형식
                Button(onClick = { onOpenRunSetup(path) }, enabled = !s.recording && !s.saving) { Text("실행 설정으로") } // 이 파일로 실행 설정
            }
        }
    }
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: 경과 시간 표시
 * 기능: 초를 "mm:ss.s" 로.
 * 입력: sec(초)
 * 출력: 없음
 * 리턴: 문자열
 */
private fun formatElapsed(sec: Double): String {
    val m = (sec / 60).toInt() // 분
    val r = sec - m * 60 // 나머지 초
    return "%02d:%04.1f".format(java.util.Locale.ROOT, m, r) // 서식
}

// change(add)-hyungchul-2026-10-11-03:59
/**
 * 목적: RMS 레벨 → 막대 길이
 * 기능: dBFS(20·log10 rms)를 -60..0 dB 에서 0..1 로 펼친다(작은 소리도 보이게). 0 이하는 0.
 * 입력: rms(0..1)
 * 출력: 없음
 * 리턴: 0..1
 */
private fun levelToMeter(rms: Float): Float {
    if (rms <= 0f) return 0f // 무음
    val db = 20f * log10(rms) // dBFS
    return ((db + 60f) / 60f).coerceIn(0f, 1f) // -60..0 → 0..1
}
