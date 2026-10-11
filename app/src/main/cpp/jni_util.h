// 파일명: jni_util.h
// 목적: 앱 JNI 파일들(fvad_jni.cpp, ten_vad_jni.cpp, whisper_jni.cpp)이 함께 쓰는 JNI 보조 함수 모음
// 기능: - stt::throwJava: 클래스 이름으로 Java 예외를 설정(각 JNI 파일에 중복돼 있던 throwJava 를 한곳으로 모음)
//       - stt::newStringUtf8: 표준 UTF-8 바이트열을 Java String 으로 변환(NewStringUTF 는 "수정 UTF-8"만 받아
//         4 바이트 문자·깨진 바이트에서 CheckJNI 중단이 날 수 있으므로 new String(byte[], "UTF-8") 경로를 씀 — 잘못된 바이트는 U+FFFD 로 바뀜)
// change(add)-hyungchul-2026-10-10-20:35

#ifndef STT_ONDEVICE_JNI_UTIL_H
#define STT_ONDEVICE_JNI_UTIL_H

#include <jni.h>   // JNI 타입·함수
#include <string>  // std::string

namespace stt {

/**
 * 목적: Java 예외 던지기
 * 기능: 클래스 이름으로 예외 클래스를 찾아 메시지와 함께 ThrowNew 한다(호출 후 즉시 반환해야 함).
 * 입력: env(JNI 환경), cls(예외 클래스 이름, 예: "java/lang/IllegalStateException"), msg(메시지, 수정 UTF-8 — 한글 BMP 문자는 표준 UTF-8 과 같음)
 * 출력: Java 쪽 예외 대기 상태
 * 리턴: 없음
 */
inline void throwJava(JNIEnv *env, const char *cls, const char *msg) {
    jclass c = env->FindClass(cls); // 예외 클래스 조회
    if (c != nullptr) env->ThrowNew(c, msg); // 예외 설정(조회 실패면 FindClass 가 이미 NoClassDefFoundError 를 설정함)
}

/**
 * 목적: 표준 UTF-8 문자열을 Java String 으로 변환
 * 기능: byte[] 를 만들어 new String(bytes, "UTF-8") 생성자를 부른다.
 * 입력: env(JNI 환경), utf8(표준 UTF-8 바이트열, 깨진 바이트 허용)
 * 출력: 할당 실패 시 OutOfMemoryError 등 Java 예외 대기 상태
 * 리턴: jstring(실패 시 nullptr)
 */
inline jstring newStringUtf8(JNIEnv *env, const std::string &utf8) {
    const auto n = static_cast<jsize>(utf8.size()); // 바이트 수
    jbyteArray bytes = env->NewByteArray(n); // byte[] 생성
    if (bytes == nullptr) return nullptr; // 할당 실패(예외 설정됨)
    if (n > 0) env->SetByteArrayRegion(bytes, 0, n, reinterpret_cast<const jbyte *>(utf8.data())); // 바이트 복사
    jclass strCls = env->FindClass("java/lang/String"); // String 클래스
    if (strCls == nullptr) { env->DeleteLocalRef(bytes); return nullptr; } // 조회 실패(예외 설정됨)
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V"); // String(byte[], String) 생성자
    if (ctor == nullptr) { env->DeleteLocalRef(bytes); env->DeleteLocalRef(strCls); return nullptr; } // 조회 실패(예외 설정됨)
    jstring charset = env->NewStringUTF("UTF-8"); // 문자셋 이름
    if (charset == nullptr) { env->DeleteLocalRef(bytes); env->DeleteLocalRef(strCls); return nullptr; } // 할당 실패
    auto out = static_cast<jstring>(env->NewObject(strCls, ctor, bytes, charset)); // new String(bytes, "UTF-8")
    env->DeleteLocalRef(charset); // 지역 참조 정리
    env->DeleteLocalRef(bytes); // 지역 참조 정리
    env->DeleteLocalRef(strCls); // 지역 참조 정리
    return out; // 결과(예외 시 nullptr)
}

} // namespace stt

#endif // STT_ONDEVICE_JNI_UTIL_H
