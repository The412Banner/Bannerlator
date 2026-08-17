// JNI wrapper for the renderer-neutral ScanoutContext, packaged as the small
// standalone libdirect_scanout.so (links log/android/dl/atomic only — NO Vulkan,
// NO adrenotools). Loaded by com.winlator.star.renderer.DirectScanout so a GL
// container can drive direct scanout without dragging in libvulkan_renderer.
//
// P1 of the GL direct-scanout plan: this lib + DirectScanout.java are DORMANT —
// wired into no renderer yet. It only needs to compile and load.
//
// Threading model (the GL model): unlike the Vulkan path, there is no render
// loop here. The cursor setters apply their pending transaction INLINE: when
// setCursorImage()/setCursorPos() report a deferred apply is pending, this
// wrapper calls applyPendingCursor() immediately on the caller's thread. A
// SurfaceControl transaction apply is thread-safe (ASR already applies cursor
// transactions off the epoll thread), so no needsRender/dirtyCV deferral exists
// in this lib.

#include <jni.h>
#include <android/native_window_jni.h>
#include "ScanoutContext.h"

static inline ScanoutContext* ctx(jlong handle) {
    return reinterpret_cast<ScanoutContext*>(handle);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeInit(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new ScanoutContext());
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetWindows(
        JNIEnv* env, jobject, jlong handle, jobject gameSurface, jobject cursorSurface) {
    auto* c = ctx(handle);
    if (!c) return;
    ANativeWindow* gw = ANativeWindow_fromSurface(env, gameSurface);
    ANativeWindow* cw = ANativeWindow_fromSurface(env, cursorSurface);
    if (!gw || !cw) {
        if (gw) ANativeWindow_release(gw);
        if (cw) ANativeWindow_release(cw);
        c->initFromWindow();   // fallback (uses stored fallback window if set)
        return;
    }
    c->initFromWindows(gw, cw);   // takes ownership + releases both windows
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetFallbackWindow(
        JNIEnv* env, jobject, jlong handle, jobject surface) {
    auto* c = ctx(handle);
    if (!c) return;
    ANativeWindow* win = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    c->setFallbackWindow(win);
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetBuffer(
        JNIEnv*, jobject, jlong handle, jlong ahbPtr,
        jint x, jint y, jint w, jint h, jint fenceFd) {
    auto* c = ctx(handle);
    if (c && ahbPtr)
        c->setBuffer(reinterpret_cast<AHardwareBuffer*>(ahbPtr), x, y, w, h, fenceFd);
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetCursorImage(
        JNIEnv* env, jobject, jlong handle, jobject buf, jshort w, jshort h, jshort stride) {
    auto* c = ctx(handle);
    if (!c || !buf) return;
    void* px = env->GetDirectBufferAddress(buf);
    if (!px || env->GetDirectBufferCapacity(buf) < (jlong)w * h * 4) return;
    // GL model: apply inline if the setter reports a pending transaction.
    if (c->setCursorImage(px, w, h, stride)) c->applyPendingCursor();
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetCursorPos(
        JNIEnv*, jobject, jlong handle, jshort x, jshort y, jshort hotX, jshort hotY) {
    auto* c = ctx(handle);
    if (!c) return;
    // GL model: apply inline if the setter reports a pending transaction.
    if (c->setCursorPos(x, y, hotX, hotY)) c->applyPendingCursor();
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeApplyPendingCursor(
        JNIEnv*, jobject, jlong handle) {
    if (auto* c = ctx(handle)) c->applyPendingCursor();
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetDst(
        JNIEnv*, jobject, jlong handle, jint x, jint y, jint w, jint h) {
    if (auto* c = ctx(handle)) c->setDst(x, y, w, h);
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetSurfaceSize(
        JNIEnv*, jobject, jlong handle, jint w, jint h) {
    if (auto* c = ctx(handle)) c->setSurfaceSize(w, h);
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeSetContainerSize(
        JNIEnv*, jobject, jlong handle, jint w, jint h) {
    if (auto* c = ctx(handle)) c->setContainerSize(w, h);
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeIsActive(JNIEnv*, jobject, jlong handle) {
    auto* c = ctx(handle);
    return c ? (jboolean)c->isActive() : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeIsGameFrameDelivered(JNIEnv*, jobject, jlong handle) {
    auto* c = ctx(handle);
    return c ? (jboolean)c->isGameFrameDelivered() : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DirectScanout_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* c = ctx(handle);
    if (!c) return;
    c->destroy();
    delete c;
}

} // extern "C"
