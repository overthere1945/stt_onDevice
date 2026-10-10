// 파일명: InputScanner.kt
// 목적: input_sound 폴더에서 처리 대상 wav 목록 수집
// 기능: <루트>/<lang>/**.wav 를 재귀 탐색(대소문자 무시)하고 lang→relDir→stem 순으로 정렬한다.
package com.cowork.stt_ondevice.core.storage // 저장소 패키지

import java.io.File // 파일

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
     * 입력: inputRoot(input_sound 폴더, 없어도 됨)
     * 출력: 없음(폴더가 없으면 빈 목록)
     * 리턴: lang→relDir→stem 순으로 정렬된 InputItem 목록
     */
    fun scan(inputRoot: File): List<InputItem> {
        val items = mutableListOf<InputItem>() // 결과 누적
        for (lang in LANG_FOLDERS.keys) { // 알려진 언어만 순회
            val langDir = File(inputRoot, lang) // 언어 폴더
            if (!langDir.isDirectory) continue // 없으면 건너뜀
            langDir.walkTopDown() // 하위 전체 탐색
                .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) } // wav 만(대소문자 무시)
                .forEach { f ->
                    val rel = f.parentFile!!.relativeTo(langDir).path.replace(File.separatorChar, '/') // 상대 폴더를 '/' 로 통일
                    items += InputItem(lang, rel, f.nameWithoutExtension, f) // 항목 추가
                }
        }
        return items.sortedWith(compareBy({ it.lang }, { it.relDir }, { it.stem })) // 정렬
    }
}
