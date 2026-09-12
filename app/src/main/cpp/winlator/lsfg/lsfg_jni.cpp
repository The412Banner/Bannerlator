// JNI surface for native LSFG shader-cache management.
//
// Deliberately free of any renderer handle: extracting the shader chain from
// Lossless.dll and caching it is a slow, once-per-DLL operation that the app
// runs off the render thread, long before a game launches.

#include "lsfg_dll.h"

#include <jni.h>
#include <string>

namespace {

std::string toStdString(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return {};
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_star_core_LsfgNative_nativeValidateDll(JNIEnv* env, jclass, jstring dllPath) {
    return (jint)lsfg::validateDll(toStdString(env, dllPath));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_star_core_LsfgNative_nativeDllVariant(JNIEnv* env, jclass, jstring dllPath,
                                                        jboolean preferFp16) {
    return (jint)lsfg::dllVariant(toStdString(env, dllPath), preferFp16 == JNI_TRUE);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_star_core_LsfgNative_nativeBuildCache(JNIEnv* env, jclass, jstring dllPath,
                                                        jstring cachePath, jboolean preferFp16) {
    return (jint)lsfg::buildCache(toStdString(env, dllPath), toStdString(env, cachePath),
                                  preferFp16 == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_star_core_LsfgNative_nativeCacheMatchesSource(JNIEnv* env, jclass,
                                                                jstring cachePath,
                                                                jstring dllPath) {
    bool matches = false;
    const lsfg::DllStatus status = lsfg::cacheMatchesSource(toStdString(env, cachePath),
                                                            toStdString(env, dllPath), matches);
    return (jboolean)(status == lsfg::DllStatus::Ok && matches);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_winlator_star_core_LsfgNative_nativeCacheVariant(JNIEnv* env, jclass, jstring cachePath) {
    lsfg::Variant variant = lsfg::Variant::None;
    if (lsfg::cacheVariant(toStdString(env, cachePath), variant) != lsfg::DllStatus::Ok)
        return (jint)lsfg::Variant::None;
    return (jint)variant;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_winlator_star_core_LsfgNative_nativeStatusName(JNIEnv* env, jclass, jint status) {
    return env->NewStringUTF(lsfg::statusName((lsfg::DllStatus)status));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_winlator_star_core_LsfgNative_nativeVariantName(JNIEnv* env, jclass, jint variant) {
    return env->NewStringUTF(lsfg::variantName((lsfg::Variant)variant));
}
