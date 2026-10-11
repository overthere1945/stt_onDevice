// 파일명: SystemCatalogTest.kt
// 목적: 시스템 엔진 카탈로그(SystemCatalog)의 내용·저장소 동작·모델 화면 제외 계약 검증
// 기능: 6항목과 고유 id, 조합명에 배속이 실림, 파일 없는 항목이 READY 로 보이고 delete 는 false, 모델 화면 목록에 시스템 항목이 없음, 항목 속성 값을 확인한다.
//       change(add)-hyungchul-2026-10-11-18:21 신규(2차 작업 2).
package com.cowork.stt_ondevice.model // 모델 테스트 패키지

import com.cowork.stt_ondevice.core.storage.AppDirs // 앱 폴더
import com.cowork.stt_ondevice.runner.Combo // 조합(모델 × VAD 프리셋)
import com.cowork.stt_ondevice.segment.ChunkMode // 묶기 방식
import com.cowork.stt_ondevice.ui.models.ModelsViewModel // 모델 화면 ViewModel(catalogForScreen)
import com.cowork.stt_ondevice.vad.VadPresets // VAD 프리셋 목록
import org.junit.Assert.assertEquals // 값 일치 검증
import org.junit.Assert.assertFalse // 거짓 검증
import org.junit.Assert.assertTrue // 참 검증
import org.junit.Rule // JUnit 규칙
import org.junit.Test // JUnit4 테스트 표시
import org.junit.rules.TemporaryFolder // 임시 폴더

// change(add)-hyungchul-2026-10-11-18:21
/**
 * 목적: 시스템 엔진 카탈로그 회귀 방지
 * 기능: 엔진 2종 × 공급 배속 1/2/4 = 6항목이 약속한 값으로 만들어지고, 파일이 없어도 READY 로 취급되며 모델 화면에는 나오지 않는지 본다.
 */
class SystemCatalogTest {

    @get:Rule val tmp = TemporaryFolder() // 임시 폴더

    /**
     * 목적: 6항목·id 고유성 검증
     * 기능: 시스템 항목이 6개이고 id 가 서로 다르며 생성 데이터(ModelCatalogData)의 id 와도 겹치지 않는다. 순서는 엔진 × 배속이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun sixEntriesWithUniqueIds() {
        val all = SystemCatalog.all // 시스템 항목
        assertEquals(6, all.size) // 6개
        val ids = all.map { it.id } // id 목록
        assertEquals(ids.size, ids.toSet().size) // 서로 다름
        assertTrue("기존 카탈로그 id 와 겹침", ids.none { id -> ModelCatalogData.all.any { it.id == id } }) // 기존 id 와 다름
        assertEquals( // 엔진 × 배속 순서와 id 규칙
            listOf("mlkit-basic-x1", "mlkit-basic-x2", "mlkit-basic-x4", "android-ondevice-asi-x1", "android-ondevice-asi-x2", "android-ondevice-asi-x4"),
            ids,
        )
        assertEquals(listOf(1, 2, 4), SystemCatalog.FEED_SPEEDS) // 공급 배속
        assertEquals(all, ModelCatalog.all.filter { it.isSystem }) // 전체 목록에 시스템 항목이 같은 순서로 합쳐짐
    }

    /**
     * 목적: 조합명에 배속이 실리는지 검증
     * 기능: x4 항목과 SILERO 프리셋의 조합 이름이 "mlkit_basic-x4_SILERO" 이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun comboNamesCarrySpeed() {
        val spec = SystemCatalog.all.single { it.id == "mlkit-basic-x4" } // x4 항목
        val combo = Combo(spec, VadPresets.byId("SILERO"), spec.maxChunkSec, ChunkMode.MERGE) // 조합
        assertEquals("mlkit_basic-x4_SILERO", combo.name) // 조합명
    }

    /**
     * 목적: 파일 없는 항목의 저장소 동작 검증
     * 기능: 빈 앱 폴더에서도 state 가 READY 이고 delete 는 false 이며 폴더를 만들지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun storeReportsReadyWithoutFiles() {
        val dirs = AppDirs(tmp.newFolder("root")) // 빈 앱 폴더
        val store = ModelStore(dirs) // 저장소
        for (spec in SystemCatalog.all) { // 항목마다
            assertEquals(spec.id, InstallState.READY, store.state(spec)) // 파일이 없어도 READY
            assertFalse(spec.id, store.delete(spec)) // 지울 것 없음
            assertFalse(spec.id, store.modelDir(spec).exists()) // 폴더를 만들지 않음
        }
    }

    /**
     * 목적: 모델 화면 제외 검증
     * 기능: catalogForScreen 결과에 시스템 항목이 없고, 나머지 항목은 원래 순서 그대로 남는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun modelsScreenExcludesSystem() {
        val screen = ModelsViewModel.catalogForScreen(ModelCatalog.all) // 화면용 목록
        assertTrue(screen.none { it.isSystem }) // 시스템 항목 없음
        assertEquals(ModelCatalogData.all, screen) // 나머지는 그대로
    }

    /**
     * 목적: 항목 속성 값 검증
     * 기능: 약속한 런타임·언어·정밀도·최대 입력·스트리밍 방식·라이선스·설정 값이 들어 있다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun entryPropertiesMatchContract() {
        for (spec in SystemCatalog.all) { // 항목마다
            val cfg = spec.engineConfig as SystemEngineConfig // 시스템 설정
            val mlkit = cfg.kind == SystemEngineKind.MLKIT_BASIC // ML Kit 여부
            assertTrue(spec.id, spec.isSystem) // 시스템 항목
            assertEquals(ModelKind.ASR, spec.kind) // ASR
            assertEquals(spec.id, "${spec.algo}-${spec.model}") // id = algo-model
            assertEquals(spec.model, "${cfg.kind.modelPrefix}-x${cfg.feedSpeed}") // model = 접두-x배속
            assertEquals(setOf("ko", "en"), spec.languages) // 언어
            assertEquals(if (mlkit) Runtime.MLKIT_GENAI else Runtime.PLATFORM_ONDEVICE, spec.runtime) // 런타임
            assertEquals(if (mlkit) "1.0.0-alpha1" else "platform", spec.runtimeVersion) // 런타임 버전
            assertEquals("system", spec.precision) // 정밀도
            assertEquals(30.0, spec.maxChunkSec, 0.0) // 최대 입력
            assertEquals(StreamingKind.PACED_REALTIME, spec.streaming) // 스트리밍 방식
            assertTrue(spec.downloads.isEmpty() && spec.files.isEmpty()) // 파일 없음
            assertEquals(CommercialStatus.CONDITIONAL, spec.commercial) // 상업 판정
            assertEquals(null, spec.compatAlias) // 별칭 없음
            assertTrue(cfg.feedSpeed in SystemCatalog.FEED_SPEEDS) // 허용 배속
            assertTrue(spec.displayName.endsWith("(공급 ${cfg.feedSpeed}×)")) // 표시 이름에 배속
            assertEquals(if (mlkit) "https://developers.google.com/ml-kit/terms" else "https://policies.google.com/terms", spec.licenseUrl) // 약관 URL
        }
        assertEquals("system(soda)", SystemEngineKind.MLKIT_BASIC.backend) // backend 문자열
        assertEquals("system(asi)", SystemEngineKind.PLATFORM_ONDEVICE.backend) // backend 문자열
    }
}
