// 파일명: VadEngineFactory.kt
// 목적: VAD 계열별 VadEngine 생성
// 기능: 설치된 모델 폴더(vadModelDir)에서 계열에 맞는 모델 파일을 찾아 엔진을 만든다. 모델이 없으면 VadUnavailableException 을 던진다(다른 VAD 로 대체하지 않음).
package com.cowork.stt_ondevice.vad // VAD 패키지

import com.cowork.stt_ondevice.vad.impl.FireRedVad // FireRedVAD
import com.cowork.stt_ondevice.vad.impl.FsmnVad // FSMN-VAD
import com.cowork.stt_ondevice.vad.impl.MarbleNetVad // MarbleNet
import com.cowork.stt_ondevice.vad.impl.NoneVad // VAD 없음
import com.cowork.stt_ondevice.vad.impl.PyannoteVad // pyannote
import com.cowork.stt_ondevice.vad.impl.SileroVad // Silero
import com.cowork.stt_ondevice.vad.impl.TenVad // TEN VAD
import com.cowork.stt_ondevice.vad.impl.WebRtcVad // WebRTC(libfvad)
import com.cowork.stt_ondevice.vad.impl.YamnetVad // Yamnet
import java.io.File // 파일

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: 모델을 쓸 수 없는 VAD 계열을 호출자에게 알림
 * 기능: 어떤 계열이 왜 쓸 수 없는지(폴더 없음/파일 없음)를 담는다. 자동 대체는 하지 않는다.
 * 입력: family(요청한 VAD 계열), reason(사유)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class VadUnavailableException(
    val family: VadFamily, // 쓸 수 없는 계열
    val reason: String, // 사유(한국어)
) : Exception("VAD 모델 사용 불가: $family — $reason")

// change(add)-hyungchul-2026-10-10-16:03
/**
 * 목적: VadEngine 생성기
 * 기능: 모델이 필요한 계열은 vadModelDir(family) 가 돌려준 폴더 안에서 고정된 파일 이름(Silero silero_vad.onnx, pyannote model.onnx(fp32),
 *       Yamnet yamnet.tflite)을 찾고, FSMN/FireRed/MarbleNet 은 폴더 자체를 엔진에 넘긴다. NONE/WEBRTC/TEN 은 모델이 필요 없다.
 *       WEBRTC/TEN 은 생성 시점에 네이티브 라이브러리를 로드하지 않는다(detect 에서 처음 쓸 때 로드).
 * 입력: vadModelDir(계열 → 그 계열 모델 폴더, 없으면 null)
 * 출력: 해당 없음
 * 리턴: 해당 없음
 */
class VadEngineFactory(private val vadModelDir: (VadFamily) -> File?) {

    /**
     * 목적: 계열별 엔진 생성
     * 기능: 모델 폴더/파일을 확인한 뒤 해당 구현체를 만든다. 생성된 엔진은 호출자가 close 해야 한다.
     * 입력: family(VAD 계열)
     * 출력: 모델 폴더가 없거나 필요한 파일이 없으면 VadUnavailableException (모델 내용이 잘못된 경우의 예외는 각 구현체가 그대로 던짐)
     * 리턴: VadEngine
     */
    fun create(family: VadFamily): VadEngine = when (family) { // 계열 분기
        VadFamily.NONE -> NoneVad() // 모델 불필요
        VadFamily.WEBRTC -> WebRtcVad() // 모델 불필요(네이티브)
        VadFamily.TEN -> TenVad() // 모델 불필요(네이티브)
        VadFamily.SILERO -> SileroVad(modelFileIn(family, SILERO_FILE)) // silero_vad.onnx
        VadFamily.PYANNOTE -> PyannoteVad(modelFileIn(family, PYANNOTE_FILE)) // model.onnx(fp32)
        VadFamily.YAMNET -> YamnetVad(modelFileIn(family, YAMNET_FILE)) // yamnet.tflite
        VadFamily.FSMN -> FsmnVad(modelDirOf(family)) // 폴더 자체
        VadFamily.FIRERED -> FireRedVad(modelDirOf(family)) // 폴더 자체
        VadFamily.MARBLENET -> MarbleNetVad(modelDirOf(family)) // 폴더 자체
    }

    /**
     * 목적: 계열의 모델 폴더 확인
     * 기능: vadModelDir 결과가 null 이거나 폴더가 아니면 VadUnavailableException.
     * 입력: family(VAD 계열)
     * 출력: 폴더 없음 시 VadUnavailableException
     * 리턴: 존재하는 모델 폴더
     */
    private fun modelDirOf(family: VadFamily): File {
        val dir = vadModelDir(family) // 설치 위치 조회
            ?: throw VadUnavailableException(family, "모델이 설치되지 않음(모델 폴더 없음)") // 미설치
        if (!dir.isDirectory) throw VadUnavailableException(family, "모델 폴더가 존재하지 않음: ${dir.absolutePath}") // 폴더 아님
        return dir // 확인된 폴더
    }

    /**
     * 목적: 모델 폴더 안의 고정 이름 모델 파일 확인
     * 기능: 폴더를 확인하고 그 안의 fileName 이 파일로 존재하는지 본다.
     * 입력: family(VAD 계열), fileName(폴더 안 모델 파일 이름)
     * 출력: 파일 없음 시 VadUnavailableException
     * 리턴: 존재하는 모델 파일
     */
    private fun modelFileIn(family: VadFamily, fileName: String): File {
        val file = File(modelDirOf(family), fileName) // 폴더 + 고정 파일명
        if (!file.isFile) throw VadUnavailableException(family, "모델 파일이 없음: ${file.absolutePath}") // 파일 없음
        return file // 확인된 파일
    }

    companion object {
        const val SILERO_FILE = "silero_vad.onnx" // Silero v6 모델 파일명
        const val PYANNOTE_FILE = "model.onnx" // pyannote segmentation-3.0 fp32 모델 파일명
        const val YAMNET_FILE = "yamnet.tflite" // Yamnet 모델 파일명
    }
}
