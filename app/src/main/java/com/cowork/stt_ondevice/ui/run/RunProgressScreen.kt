// 파일명: ui/run/RunProgressScreen.kt
// 목적: 진행 화면(Compose) — 배치 실행의 현재 모델/VAD 프리셋/파일, 전체 진행률, 경과 시간, 오류 수, 취소, 끝난 뒤 요약
// 기능: RunService.events(StateFlow<RunUiState>)를 그대로 그린다(별도 ViewModel 없음 — 상태는 서비스 쪽 프로세스 메모리에 있음).
//       Idle: 안내 문구. Running: 진행 막대(done/total)·현재 작업 3줄·경과 시간(1초마다 갱신)·오류 수·최근 완료 항목·거부된 시작 요청 사유·취소(확인 대화상자).
//       Finished/Failed/Cancelled: runId 와 상태별 건수 또는 원인, "결과 보기" 버튼(onOpenResults 로 결과 탭의 그 run 을 연다).
// change(add)-hyungchul-2026-10-11-03:31
package com.cowork.stt_ondevice.ui.run // 실행 화면 패키지

import androidx.compose.foundation.layout.Arrangement // 배치 간격
import androidx.compose.foundation.layout.Column // 세로 배치
import androidx.compose.foundation.layout.fillMaxSize // 가득 채우기
import androidx.compose.foundation.layout.fillMaxWidth // 가로 채우기
import androidx.compose.foundation.layout.padding // 여백
import androidx.compose.foundation.rememberScrollState // 스크롤 상태
import androidx.compose.foundation.verticalScroll // 세로 스크롤
import androidx.compose.material3.AlertDialog // 대화상자
import androidx.compose.material3.Button // 버튼
import androidx.compose.material3.LinearProgressIndicator // 진행 막대
import androidx.compose.material3.MaterialTheme // 테마 값
import androidx.compose.material3.OutlinedButton // 외곽선 버튼
import androidx.compose.material3.Text // 텍스트
import androidx.compose.material3.TextButton // 텍스트 버튼
import androidx.compose.runtime.Composable // 컴포저블
import androidx.compose.runtime.LaunchedEffect // 부수 효과
import androidx.compose.runtime.getValue // 위임 읽기
import androidx.compose.runtime.mutableLongStateOf // Long 상태
import androidx.compose.runtime.mutableStateOf // 상태 생성
import androidx.compose.runtime.remember // 기억
import androidx.compose.runtime.setValue // 위임 쓰기
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.platform.LocalContext // 컨텍스트
import androidx.compose.ui.unit.dp // dp 단위
import androidx.lifecycle.compose.collectAsStateWithLifecycle // 생명주기 인식 상태 수집
import com.cowork.stt_ondevice.runner.RunService // 배치 실행 서비스
import com.cowork.stt_ondevice.runner.RunUiState // 실행 상태
import kotlinx.coroutines.delay // 대기
import java.util.Locale // 숫자 서식 로캘

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 진행 화면
 * 기능: 파일 머리말 참조.
 * 입력: onOpenResults(runId — 결과 탭에서 그 run 열기)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun RunProgressScreen(onOpenResults: (String) -> Unit) {
    val context = LocalContext.current // 컨텍스트
    val state by RunService.events.collectAsStateWithLifecycle() // 실행 상태
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) } // 경과 시간 계산용 현재 시각
    var confirmCancel by remember { mutableStateOf(false) } // 취소 확인 대화상자
    var cancelRequested by remember { mutableStateOf<String?>(null) } // 취소를 요청한 runId

    val running = state is RunUiState.Running // 실행 중 여부
    LaunchedEffect(running) { // 실행 중에만 1초마다 갱신
        while (running) {
            now = System.currentTimeMillis() // 현재 시각
            delay(1_000) // 1초
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), // 스크롤 가능
        verticalArrangement = Arrangement.spacedBy(8.dp), // 간격
    ) {
        Text("진행", style = MaterialTheme.typography.headlineSmall) // 제목
        when (val s = state) {
            RunUiState.Idle -> Text("실행 중인 작업이 없습니다. 실행 탭에서 설정한 뒤 시작하세요.", style = MaterialTheme.typography.bodyLarge) // 안내
            is RunUiState.Running -> {
                Text("실행 중: ${s.runId}", style = MaterialTheme.typography.titleMedium) // 실행 ID
                val p = s.progress // 최근 진행률
                if (p == null) {
                    Text("준비 중…(manifest 기록·모델 로드)", style = MaterialTheme.typography.bodyMedium) // 첫 진행 이벤트 전
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) // 진행률 미정
                } else {
                    val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f // 진행률
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) // 진행 막대
                    Text("${p.done} / ${p.total} 항목 완료 (${String.format(Locale.ROOT, "%.1f", fraction * 100)}%)", style = MaterialTheme.typography.bodyMedium) // 건수
                    val parts = p.current.split(" / ") // "모델 / 프리셋 / 파일"
                    if (parts.size == 3) { // 정상 형식
                        LabeledLine("모델", parts[0]) // 현재 모델
                        LabeledLine("VAD 프리셋", parts[1]) // 현재 프리셋
                        LabeledLine("파일", parts[2]) // 현재 파일
                    } else {
                        LabeledLine("현재 작업", p.current) // 그 밖 형식
                    }
                }
                LabeledLine("경과 시간", s.startedAtMs?.let { formatElapsed(now - it) } ?: "알 수 없음") // 경과 시간
                Text("오류: ${s.errorCount}건", style = MaterialTheme.typography.bodyMedium, color = if (s.errorCount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) // 오류 수
                s.lastItem?.let { d -> // 최근 완료 항목
                    val target = listOf(d.item.lang, d.item.relDir, d.item.stem).filter { it.isNotEmpty() }.joinToString("/") // 위치
                    Text("최근 완료: ${d.combo} · $target · ${d.status}", style = MaterialTheme.typography.bodySmall) // 표시
                }
                s.rejectedStart?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } // 거부된 시작 요청
                if (cancelRequested == s.runId) { // 이미 취소 요청함
                    Text("취소 요청됨 — 현재 처리 중인 조각이 끝나면 멈춥니다", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) // 안내
                } else {
                    OutlinedButton(onClick = { confirmCancel = true }, modifier = Modifier.fillMaxWidth()) { Text("취소") } // 취소
                }
                if (confirmCancel) {
                    AlertDialog(
                        onDismissRequest = { confirmCancel = false }, // 바깥 탭
                        title = { Text("실행 취소") }, // 제목
                        text = { Text("실행을 취소할까요? 끝난 항목의 결과는 남고, 결과 탭에서 같은 runId 로 이어하기를 할 수 있습니다.") }, // 내용
                        confirmButton = { TextButton(onClick = { confirmCancel = false; cancelRequested = s.runId; RunService.cancel(context) }) { Text("취소하기") } }, // 확인
                        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("계속 실행") } }, // 닫기
                    )
                }
            }
            is RunUiState.Finished -> {
                Text("실행 완료: ${s.runId}", style = MaterialTheme.typography.titleMedium) // 제목
                Text("OK ${s.counts.ok}건 · NO_MATCH ${s.counts.noMatch}건 · ERROR ${s.counts.error}건 · 미지원 조합 ${s.counts.unsupported}건", style = MaterialTheme.typography.bodyMedium) // 집계
                Text("(이번 실행에서 처리한 건수 — 이어하기였다면 이전 실행분은 결과 탭의 집계에 포함)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) // 설명
                Button(onClick = { onOpenResults(s.runId) }, modifier = Modifier.fillMaxWidth()) { Text("결과 보기") } // 결과 탭
            }
            is RunUiState.Failed -> {
                Text("실행 실패: ${s.runId}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error) // 제목
                Text(s.message, style = MaterialTheme.typography.bodyMedium) // 원인
                Button(onClick = { onOpenResults(s.runId) }, modifier = Modifier.fillMaxWidth()) { Text("결과 보기") } // 결과 탭(일부 결과)
            }
            is RunUiState.Cancelled -> {
                Text("실행 취소됨: ${s.runId}", style = MaterialTheme.typography.titleMedium) // 제목
                Text("끝난 항목의 결과는 남아 있습니다. 결과 탭에서 이 실행을 '이어하기' 로 계속할 수 있습니다.", style = MaterialTheme.typography.bodyMedium) // 안내
                Button(onClick = { onOpenResults(s.runId) }, modifier = Modifier.fillMaxWidth()) { Text("결과 보기") } // 결과 탭
            }
        }
    }
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: "이름: 값" 한 줄
 * 입력: label(이름), value(값)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
private fun LabeledLine(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodyMedium) // 한 줄
}

// change(add)-hyungchul-2026-10-11-03:31
/**
 * 목적: 경과 시간 서식
 * 기능: 밀리초를 H:MM:SS 로 만든다(음수는 0).
 * 입력: ms(밀리초)
 * 출력: 없음
 * 리턴: 문자열
 */
private fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000) // 전체 초
    return String.format(Locale.ROOT, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60) // H:MM:SS
}
