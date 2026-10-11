// 파일명: ui/run/UserPresetDialog.kt
// 목적: 실행 설정 화면의 USER VAD 프리셋 대화상자(기준 고르기, 값 폼, 삭제 경고)
// 기능: - BasePresetPickerDialog: 복사할 기본 프리셋(USER·NONE 제외)을 계열별로 고른다.
//       - UserPresetFormDialog: 기준 값으로 채운 필드 폼(필드마다 범위 안내와 한국어 오류)과 이름 칸, [저장]/[취소]를 보인다.
//       - DeleteWarningDialog: 미완료 실행이 쓰는 USER 프리셋을 지우기 전 경고 문구와 [지우기]/[취소]를 보인다.
//       상태는 RunSetupViewModel 이 갖고, 이 파일은 그리기와 콜백 전달만 한다.
// change(add)-hyungchul-2026-10-11-20:15
package com.cowork.stt_ondevice.ui.run // 실행 화면 패키지

import androidx.compose.foundation.layout.Arrangement // 배치 간격
import androidx.compose.foundation.layout.Column // 세로 배치
import androidx.compose.foundation.layout.fillMaxWidth // 가로 채우기
import androidx.compose.foundation.layout.heightIn // 최대 높이 제한
import androidx.compose.foundation.layout.padding // 여백
import androidx.compose.foundation.lazy.LazyColumn // 지연 목록
import androidx.compose.foundation.lazy.items // 목록 항목
import androidx.compose.foundation.rememberScrollState // 스크롤 상태
import androidx.compose.foundation.text.KeyboardOptions // 키보드 종류
import androidx.compose.foundation.verticalScroll // 세로 스크롤
import androidx.compose.material3.AlertDialog // 대화상자
import androidx.compose.material3.MaterialTheme // 테마 값
import androidx.compose.material3.OutlinedTextField // 입력 칸
import androidx.compose.material3.Text // 텍스트
import androidx.compose.material3.TextButton // 텍스트 버튼
import androidx.compose.runtime.Composable // 컴포저블
import androidx.compose.ui.Modifier // 수정자
import androidx.compose.ui.text.input.KeyboardType // 키보드 종류
import androidx.compose.ui.unit.dp // dp 단위

// change(add)-hyungchul-2026-10-11-20:15
/**
 * 목적: 복사할 기본 프리셋 고르기 대화상자
 * 기능: 후보(기본 프리셋 중 VAD 없음 제외)를 "id — 계열" 줄로 보이고, 누르면 onPick 으로 id 를 넘긴다. 후보가 없으면 안내만 보인다.
 * 입력: bases(후보 행 목록), onPick(고른 id 콜백), onDismiss(닫기 콜백)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun BasePresetPickerDialog(bases: List<PresetOption>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, // 바깥 탭 = 취소
        title = { Text("복사할 기본 프리셋 선택") }, // 제목
        text = {
            if (bases.isEmpty()) {
                Text("복사할 수 있는 기본 프리셋이 없습니다") // 후보 없음
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) { // 길면 스크롤
                    items(bases, key = { it.id }) { b ->
                        TextButton(onClick = { onPick(b.id) }, modifier = Modifier.fillMaxWidth()) { Text("${b.id} — ${b.family.name}") } // 한 줄 선택
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }, // 취소
    )
}

// change(add)-hyungchul-2026-10-11-20:15
/**
 * 목적: USER 프리셋 값 폼 대화상자
 * 기능: 기준 프리셋 id 를 제목에, 필드마다 입력 칸(아래에 오류 또는 범위 안내)을 그린 뒤 이름 칸을 그린다. 저장 단계 오류는 맨 아래 빨간 글씨로 보인다.
 * 입력: draft(폼 상태), onField(필드 입력 콜백 key,text), onName(이름 입력 콜백), onSave(저장 콜백), onCancel(취소 콜백)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun UserPresetFormDialog(
    draft: UserDraft,
    onField: (String, String) -> Unit,
    onName: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel, // 바깥 탭 = 취소(입력 버림)
        title = { Text("${draft.baseId} 복사해서 만들기") }, // 제목
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) { // 길면 스크롤
                draft.fields.forEach { f -> // 필드마다
                    OutlinedTextField(
                        value = f.text, // 입력 글자
                        onValueChange = { onField(f.key, it) }, // 검증 후 반영
                        label = { Text(f.key) }, // 키 이름
                        isError = f.error != null, // 오류 표시
                        supportingText = { Text(f.error ?: f.hint) }, // 오류 또는 범위 안내
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), // inf·true/false·목록 값도 쓰므로 일반 키보드
                        singleLine = true, // 한 줄
                        modifier = Modifier.fillMaxWidth(), // 가로 채우기
                    )
                }
                OutlinedTextField(
                    value = draft.name, // 이름 글자
                    onValueChange = onName, // 반영
                    label = { Text("프리셋 이름") }, // 안내
                    isError = draft.nameError != null, // 오류 표시
                    supportingText = { Text(draft.nameError ?: "한글·영문·숫자·'-' 1~32자 (저장되면 USER_<이름>)") }, // 오류 또는 규칙 안내
                    singleLine = true, // 한 줄
                    modifier = Modifier.fillMaxWidth(), // 가로 채우기
                )
                draft.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) } // 저장 단계 오류
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("저장") } }, // 저장
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } }, // 취소
    )
}

// change(add)-hyungchul-2026-10-11-20:15
/**
 * 목적: 삭제 경고 대화상자
 * 기능: 미완료 실행이 쓰는 USER 프리셋을 지우기 전에 경고 문구를 보이고, [지우기]를 누를 때만 onConfirm 을 부른다.
 * 입력: targetId(지울 프리셋 id), warning(경고 문구), onConfirm(지우기 콜백), onCancel(취소 콜백)
 * 출력: 화면 표시
 * 리턴: Unit
 */
@Composable
fun DeleteWarningDialog(targetId: String, warning: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel, // 바깥 탭 = 취소(지우지 않음)
        title = { Text("$targetId 삭제") }, // 제목
        text = { Text(warning) }, // 경고 문구
        confirmButton = { TextButton(onClick = onConfirm) { Text("지우기") } }, // 확인
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } }, // 취소
    )
}
