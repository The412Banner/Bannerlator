/*
 * JNI entry for the embedded Wayland compositor (experimental parallel runtime).
 * Starts the compositor on a dedicated thread (it blocks in the wl event loop).
 * The render-to-Surface backend + input are added in the M4 phase; this brings up
 * the server so a Wayland client (eventually winewayland.drv) can connect.
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <android/log.h>

extern int banner_wayland_run(void);

#define TAG "BannerWayland"

static void *comp_thread(void *arg) {
    (void)arg;
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread starting");
    banner_wayland_run();
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread exited");
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeStart(JNIEnv *env, jclass clazz,
                                                             jstring xdgRuntimeDir) {
    if (xdgRuntimeDir) {
        const char *dir = (*env)->GetStringUTFChars(env, xdgRuntimeDir, NULL);
        if (dir) {
            setenv("XDG_RUNTIME_DIR", dir, 1);
            (*env)->ReleaseStringUTFChars(env, xdgRuntimeDir, dir);
        }
    }
    pthread_t t;
    if (pthread_create(&t, NULL, comp_thread, NULL) == 0)
        pthread_detach(t);
    else
        __android_log_print(ANDROID_LOG_ERROR, TAG, "pthread_create failed");
}
