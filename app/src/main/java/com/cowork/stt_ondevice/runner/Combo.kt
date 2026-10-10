// 파일명: Combo.kt
// 목적: 배치 실행의 "조합"(모델 × VAD 프리셋) 1개를 나타내는 값 객체
// 기능: 결과 폴더 이름(<algo>_<model>_<VAD>)과 이 조합에 실제로 적용되는 chunk 설정(실효 maxChunkSec, 묶기 방식)을 함께 보관한다.
package com.cowork.stt_ondevice.runner // 배치 실행 패키지

import com.cowork.stt_ondevice.model.ModelSpec // 모델 메타
import com.cowork.stt_ondevice.output.OutputLayout // 출력 경로 규칙
import com.cowork.stt_ondevice.segment.ChunkMode // chunk 묶기 방식
import com.cowork.stt_ondevice.vad.VadPreset // VAD 프리셋

// change(add)-hyungchul-2026-10-11-01:47
/**
 * 목적: 모델 × VAD 프리셋 조합
 * 기능: name 으로 결과 폴더 이름을 돌려준다. maxChunkSec 는 RunConfig.maxChunkFor 로 계산한 실효값이다.
 * 입력: spec(모델), preset(VAD 프리셋), maxChunkSec(실효 최대 chunk 길이, 0 = 제한 없음), chunkMode(묶기 방식)
 */
data class Combo(
    val spec: ModelSpec, // 모델
    val preset: VadPreset, // VAD 프리셋
    val maxChunkSec: Double, // 실효 최대 chunk 길이(초)
    val chunkMode: ChunkMode, // 묶기 방식
) {
    /** 결과 폴더 이름 "<algo>_<model>_<presetId>". */
    val name: String get() = OutputLayout.comboName(spec.algo, spec.model, preset.id) // 조합명
}
