// 파일명: InputItem.kt
// 목적: 입력 음원 한 건을 나타내는 데이터 모델
// 기능: 언어 폴더명, 상대 폴더, 파일 stem, 원본 파일과 엔진용 언어 코드를 담는다.
package com.cowork.stt_ondevice.core.storage // 저장소 패키지

import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-12:06
/**
 * 목적: 입력 음원 1건 표현
 * 기능: 출력 경로(lang/relDir/stem) 계산에 필요한 정보를 보관한다.
 * 입력: lang(폴더명 "kr"|"en"), relDir(언어 폴더 기준 상대 폴더, '/' 구분, 최상위면 ""), stem(확장자 없는 파일명), file(원본 wav)
 */
data class InputItem(
    val lang: String, // 입력 언어 폴더명
    val relDir: String, // 상대 폴더(항상 '/' 구분)
    val stem: String, // 확장자 없는 파일명
    val file: File, // 원본 wav 파일
) {
    /** 엔진에 넘길 언어 코드(kr→"ko", en→"en"). 알 수 없는 언어면 예외. */
    val engineLang: String
        get() = InputScanner.LANG_FOLDERS[lang] ?: throw IllegalStateException("알 수 없는 언어 폴더: $lang") // 매핑 조회
}
