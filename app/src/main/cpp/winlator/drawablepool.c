// ERL bug report #2: own tiny library (libdrawablepool.so) — deliberately
// dependency-free (just libc + JNI) so it can be built and shipped independent
// of the main winlator native codebase. Backs the X-server Drawable buffer pool
// with native-heap calloc/free, bypassing ART's small non-moving space.
#include <jni.h>
#include <stdlib.h>
#include <string.h>

JNIEXPORT jobject JNICALL
Java_com_winlator_star_xserver_DrawableBufferPool_nativeAlloc(JNIEnv *env, jclass clazz, jint capacity) {
    (void) clazz;
    if (capacity <= 0) return NULL;
    void *mem = calloc(1, (size_t) capacity);
    if (mem == NULL) return NULL;
    jobject buffer = (*env)->NewDirectByteBuffer(env, mem, capacity);
    if (buffer == NULL) { free(mem); return NULL; }
    return buffer;
}

JNIEXPORT void JNICALL
Java_com_winlator_star_xserver_DrawableBufferPool_nativeFree(JNIEnv *env, jclass clazz, jobject buffer) {
    (void) clazz;
    if (buffer == NULL) return;
    void *mem = (*env)->GetDirectBufferAddress(env, buffer);
    if (mem != NULL) free(mem);
}

// Zero a pooled buffer being handed back out by obtain(), in place, via its own
// native address — avoids allocating a fresh (often multi-MB) Java array on every
// pool hit just to memcpy it in through JNI's GetByteArrayRegion, which was both
// wasteful and, in one build, the exact SIGSEGV crash frame in a captured tombstone.
JNIEXPORT void JNICALL
Java_com_winlator_star_xserver_DrawableBufferPool_nativeZero(JNIEnv *env, jclass clazz, jobject buffer, jint capacity) {
    (void) clazz;
    if (buffer == NULL || capacity <= 0) return;
    void *mem = (*env)->GetDirectBufferAddress(env, buffer);
    if (mem != NULL) memset(mem, 0, (size_t) capacity);
}
