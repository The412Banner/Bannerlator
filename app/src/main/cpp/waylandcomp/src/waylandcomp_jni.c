/*
 * JNI entry for the embedded Wayland compositor (experimental parallel runtime).
 * Starts the compositor on a dedicated thread (it blocks in the wl event loop).
 * The render-to-Surface backend + input are added in the M4 phase; this brings up
 * the server so a Wayland client (eventually winewayland.drv) can connect.
 */
#include <jni.h>
#include <stdint.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "vk_present.h"

extern int banner_wayland_run(void);
extern void banner_wayland_send_pointer(int action, int x, int y);
extern void banner_wayland_send_key(int evdev, int state);
extern void banner_wayland_send_scene_input(int type, int a, int b);
extern void banner_wayland_vsync(int64_t frame_time_ns);
extern volatile int g_fps_limit;
extern volatile int g_hide_shell;
extern volatile int g_output_refresh_mhz;
extern volatile int g_output_w, g_output_h;

#define TAG "BannerWayland"

static JavaVM *g_jvm;
static jclass g_compositor_cls;      /* global ref */
static jmethodID g_on_first_frame;   /* static void onFirstFramePresented() */
static jmethodID g_on_game_surface;  /* static void onGameSurface(String, String) */
static jmethodID g_on_game_frame;    /* static void onGameFrame() */

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) {
        jclass c = (*env)->FindClass(env, "com/winlator/star/wayland/WaylandCompositor");
        if (c) {
            g_compositor_cls = (*env)->NewGlobalRef(env, c);
            g_on_first_frame = (*env)->GetStaticMethodID(env, g_compositor_cls,
                                                         "onFirstFramePresented", "()V");
            g_on_game_surface = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameSurface",
                                                          "(Ljava/lang/String;Ljava/lang/String;)V");
            g_on_game_frame = (*env)->GetStaticMethodID(env, g_compositor_cls, "onGameFrame", "()V");
        }
    }
    return JNI_VERSION_1_6;
}

/* Called from vk_present.c on the compositor thread when the first client frame is
 * presented. Attaches to the JVM (this thread is a bare pthread) and calls back into
 * Java so the launch overlay can dismiss. Fires exactly once. */
void banner_on_first_frame(void) {
    if (!g_jvm || !g_compositor_cls || !g_on_first_frame) return;
    JNIEnv *env = NULL;
    int attached = 0;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_first_frame);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    __android_log_print(ANDROID_LOG_INFO, TAG, "first client frame presented -> notified app");
}

/* The compositor thread stays attached once it first calls into Java: the HUD gets an upcall
 * for every game frame. */
static __thread JNIEnv *t_env;
static __thread int t_attached;

static JNIEnv *thread_env(void) {
    if (t_env) return t_env;
    if (!g_jvm) return NULL;
    JNIEnv *env = NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return NULL;
        t_attached = 1;
    }
    return t_env = env;
}

/* A window started presenting GPU frames (window = its description), or NULL when it closed. */
void banner_on_game_surface(const char *window, const char *gpu) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_surface || !(env = thread_env())) return;
    jstring jw = window ? (*env)->NewStringUTF(env, window) : NULL;
    jstring jg = gpu ? (*env)->NewStringUTF(env, gpu) : NULL;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_surface, jw, jg);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jw) (*env)->DeleteLocalRef(env, jw);
    if (jg) (*env)->DeleteLocalRef(env, jg);
}

/* One GPU frame from that window. */
void banner_on_game_frame(void) {
    JNIEnv *env;
    if (!g_compositor_cls || !g_on_game_frame || !(env = thread_env())) return;
    (*env)->CallStaticVoidMethod(env, g_compositor_cls, g_on_game_frame);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

static void *comp_thread(void *arg) {
    (void)arg;
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread starting");
    banner_wayland_run();
    __android_log_print(ANDROID_LOG_INFO, TAG, "compositor thread exited");
    if (t_attached) (*g_jvm)->DetachCurrentThread(g_jvm);
    t_env = NULL; t_attached = 0;
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

/* Inject a pointer event from the Android SurfaceView touch listener (UI thread).
 * action: 0=down 1=move 2=up; x/y in output space (0..1919, 0..1079). */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSendPointer(
        JNIEnv *env, jclass clazz, jint action, jint x, jint y) {
    banner_wayland_send_pointer(action, x, y);
}

/* Inject a key event. evdev = Linux input keycode (KEY_A=30…); state 1=down 0=up. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSendKey(
        JNIEnv *env, jclass clazz, jint evdev, jint state) {
    banner_wayland_send_key(evdev, state);
}

/* App X-server input in scene (virtual desktop) coordinates; see banner_wayland_send_scene_input. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSendSceneInput(
        JNIEnv *env, jclass clazz, jint type, jint a, jint b) {
    banner_wayland_send_scene_input(type, a, b);
}

/* One screen refresh (Choreographer frame callback, UI thread): the compositor draws once. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeVsync(JNIEnv *env, jclass clazz, jlong frameTimeNanos) {
    banner_wayland_vsync((int64_t)frameTimeNanos);
}

/* Shortcut launches: don't draw explorer's windows (desktop, taskbar), like X11's unviewable classes. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSetHideShell(JNIEnv *env, jclass clazz, jboolean hide) {
    g_hide_shell = hide ? 1 : 0;
}

/* The panel's refresh rate (Hz) for the advertised wl_output mode. Set before the compositor starts. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSetOutputRefreshRate(JNIEnv *env, jclass clazz, jfloat hz) {
    g_output_refresh_mhz = hz > 1.0f ? (int)(hz * 1000.0f + 0.5f) : 0;
}

/* The container's screen size for the advertised wl_output mode. Set before the compositor starts. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSetOutputSize(JNIEnv *env, jclass clazz, jint w, jint h) {
    g_output_w = w > 0 ? w : 0;
    g_output_h = h > 0 ? h : 0;
}

/* The in-game FPS limiter: frames per second, 0 = unlimited. */
JNIEXPORT void JNICALL
Java_com_winlator_star_wayland_WaylandCompositor_nativeSetFpsLimit(JNIEnv *env, jclass clazz, jint fps) {
    g_fps_limit = fps > 0 ? fps : 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "fps limit %d", fps);
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
