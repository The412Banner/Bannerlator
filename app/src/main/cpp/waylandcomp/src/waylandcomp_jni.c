/*
 * JNI entry for the embedded Wayland compositor (experimental parallel runtime).
 * Starts the compositor on a dedicated thread (it blocks in the wl event loop).
 * The render-to-Surface backend + input are added in the M4 phase; this brings up
 * the server so a Wayland client (eventually winewayland.drv) can connect.
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "vk_present.h"

extern int banner_wayland_run(void);

#define TAG "BannerWayland"

static void *comp_thread(void *arg) {
    (void)arg;
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread starting");
    banner_wayland_run();
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread exited");
    return NULL;
}

static void set_runtime_dir(JNIEnv *env, jstring xdgRuntimeDir) {
    if (!xdgRuntimeDir) return;
    const char *dir = (*env)->GetStringUTFChars(env, xdgRuntimeDir, NULL);
    if (dir) {
        setenv("XDG_RUNTIME_DIR", dir, 1);
        (*env)->ReleaseStringUTFChars(env, xdgRuntimeDir, dir);
    }
}

static void start_thread(void) {
    pthread_t t;
    if (pthread_create(&t, NULL, comp_thread, NULL) == 0)
        pthread_detach(t);
    else
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pthread_create failed");
}

/* Headless start (no output window) — used for bring-up tests. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeStart(JNIEnv *env, jclass clazz,
                                                             jstring xdgRuntimeDir) {
    set_runtime_dir(env, xdgRuntimeDir);
    start_thread();
}

static char *dup_jstr(JNIEnv *env, jstring s) {
    if (!s) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = c ? strdup(c) : NULL;
    if (c) (*env)->ReleaseStringUTFChars(env, s, c);
    return out;
}

/* Start with a real output Surface + the container's Turnip driver (adrenotools).
 * Frames committed by clients are composited to this Surface via Turnip. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeStartWithSurface(
        JNIEnv *env, jclass clazz, jobject surface, jstring xdgRuntimeDir,
        jstring driverPath, jstring libraryName, jstring nativeLibDir) {
    set_runtime_dir(env, xdgRuntimeDir);
    char *dp = dup_jstr(env, driverPath);
    char *ln = dup_jstr(env, libraryName);
    char *nl = dup_jstr(env, nativeLibDir);
    vk_present_set_driver(dp, ln, nl);
    free(dp); free(ln); free(nl);
    if (surface) {
        ANativeWindow *win = ANativeWindow_fromSurface(env, surface);
        vk_present_set_window(win); /* backend acquires; released on nativeSetSurface(null) */
        __android_log_print(ANDROID_LOG_INFO, TAG, "output window bound (%p)", (void *)win);
    }
    start_thread();
}

/* Swap/clear the output window (e.g. SurfaceView recreated/destroyed). */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSetSurface(
        JNIEnv *env, jclass clazz, jobject surface) {
    if (surface) {
        vk_present_set_window(ANativeWindow_fromSurface(env, surface));
    } else {
        vk_present_set_window(NULL);
    }
}
