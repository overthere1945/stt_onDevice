// 파일명: InputScanner.kt
// 목적: input_sound 폴더에서 처리 대상 wav 목록 수집
// 기능: <루트>/<lang>/**.wav 를 재귀 탐색(대소문자 무시)하고 lang→relDir→stem 순으로 정렬한다.
//       change(add)-hyungchul-2026-10-11-12:51 (T3 이월) 이름이 '.' 으로 시작하는 파일(`._01.wav` macOS 메타 파일, `.trashed-…` 휴지통 파일 등)은 건너뛴다.
//       같은 폴더에 stem 이 대소문자만 다른(또는 같은) wav 가 둘 이상 있으면(01.wav / 01.WAV) 출력 파일 이름이 겹치므로 어느 것도 고르지 않고
//       목록에서 모두 빼고 입력 오류(errors)로 알린다(scanWithErrors — 실행 설정 화면이 보여 줌). 조용히 하나만 고르지 않는다.
package com.cowork.stt_ondevice.core.storage // 저장소 패키지

import java.io.File // 파일

// change(add)-hyungchul-2026-10-11-12:51
/**
 * 목적: 입력 스캔 결과(항목 + 입력 오류)
 * 기능: 실행 대상으로 쓸 수 있는 항목과, 쓸 수 없어 뺀 파일에 대한 한국어 오류 문구를 담는다.
 * 입력: items(정렬된 입력 항목), errors(입력 오류 문구 목록, 없으면 빈 목록)
 */
data class InputScanResult(
    val items: List<InputItem>, // 실행 대상 항목
    val errors: List<String>, // 입력 오류 문구
)

// change(add)-hyungchul-2026-10-10-12:06
/**
 * 목적: 입력 음원 스캔
 * 기능: 알려진 언어 폴더(kr, en)만 대상으로 wav 를 InputItem 으로 만든다.
 */
object InputScanner {

    val LANG_FOLDERS: Map<String, String> = mapOf("kr" to "ko", "en" to "en") // 폴더명 → 엔진 언어 코드

    /**
     * 목적: 입력 음원 목록 수집
     * 기능: 언어 폴더별로 하위 wav 를 재귀 탐색해 상대 폴더('/' 구분)·stem 을 계산하고 정렬한다. 알 수 없는 언어 폴더와 비 wav 는 무시한다.
     *       change(add)-hyungchul-2026-10-11-12:51 scanWithErrors(root).items 와 같다('.' 시작 파일 제외, stem 충돌 파일 제외 — 오류 문구는 버림).
     * 입력: inputRoot(input_sound 폴더, 없어도 됨)
     * 출력: 없음(폴더가 없으면 빈 목록)
     * 리턴: lang→relDir→stem 순으로 정렬된 InputItem 목록
     */
    fun scan(inputRoot: File): List<InputItem> = scanWithErrors(inputRoot).items // 항목만 // change(add)-hyungchul-2026-10-11-12:51 공통 구현으로 위임

    // change(add)-hyungchul-2026-10-11-12:51
    /**
     * 목적: 입력 음원 목록과 입력 오류 수집
     * 기능: 언어 폴더별 wav 를 재귀 탐색하되 이름이 '.' 으로 시작하는 파일은 건너뛴다. 같은 (언어, 상대 폴더) 안에서 stem 을 소문자로 비교해
     *       둘 이상이면 그 파일들을 모두 빼고 "<lang>/<relDir>: a.wav, A.WAV — …" 오류를 남긴다. 나머지는 lang→relDir→stem 순으로 정렬한다.
     * 입력: inputRoot(input_sound 폴더, 없어도 됨)
     * 출력: 없음(폴더가 없으면 빈 결과)
     * 리턴: InputScanResult(정렬된 항목, 오류 문구 — 위치 순)
     */
    fun scanWithErrors(inputRoot: File): InputScanResult {
        val found = mutableListOf<InputItem>() // 찾은 항목(충돌 검사 전)
        for (lang in LANG_FOLDERS.keys) { // 알려진 언어만 순회
            val langDir = File(inputRoot, lang) // 언어 폴더
            if (!langDir.isDirectory) continue // 없으면 건너뜀
            langDir.walkTopDown() // 하위 전체 탐색
                .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) } // wav 만(대소문자 무시)
                .filterNot { it.name.startsWith('.') } // 숨김·메타 파일 제외(._01.wav, .trashed-…) // change(add)-hyungchul-2026-10-11-12:51
                .forEach { f ->
                    val rel = f.parentFile!!.relativeTo(langDir).path.replace(File.separatorChar, '/') // 상대 폴더를 '/' 로 통일
                    found += InputItem(lang, rel, f.nameWithoutExtension, f) // 항목 추가
                }
        }
        val items = mutableListOf<InputItem>() // 쓸 수 있는 항목
        val errors = mutableListOf<String>() // 오류 문구
        val groups = found.groupBy { Triple(it.lang, it.relDir, it.stem.lowercase()) } // 같은 폴더·같은 stem(대소문자 무시)끼리
        for ((key, group) in groups.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }, { it.key.third }))) { // 위치 순
            if (group.size == 1) { items += group[0]; continue } // 충돌 없음
            val names = group.map { it.file.name }.sorted().joinToString(", ") // 충돌 파일 이름
            val where = listOf(key.first, key.second).filter { it.isNotEmpty() }.joinToString("/") // 위치(언어/상대 폴더)
            errors += "$where: $names — 이름이 대소문자만 다른 wav 가 있어 결과 파일 이름이 겹칩니다. 하나만 남기고 다시 읽어 주세요(이 파일들은 실행 목록에서 뺐습니다)" // 오류
        }
        return InputScanResult(items.sortedWith(compareBy({ it.lang }, { it.relDir }, { it.stem })), errors) // 정렬 결과
    }
}
