// pasink — a tiny PulseAudio client that suspends/resumes a sink by name.
//
// Why this exists: our guest audio runs through a bundled PulseAudio 13.0 daemon. When the app is
// backgrounded (or the HDMI/output route changes) the sink's AAudio output stream dies and does not
// re-open itself, leaving the game silent. GameNative's proven fix is `pactl suspend-sink AAudioSink`
// (suspend then resume), which reopens the sink's output stream and re-grabs the current route.
//
// We can't load a control MODULE into the daemon (the bundled module-cli is a 17.0 build, ABI-
// incompatible with the 13.0 daemon), and bundling a stock `pactl` drags a large codec-lib chain.
// Instead this helper dlopen()s the 13.0 libpulse CLIENT that already ships in files/pulseaudio and
// calls pa_context_suspend_sink_by_name() directly over the native socket — the same protocol path a
// 17.0 pactl used successfully against the 13.0 daemon in on-device testing (protocol 35<->33).
//
// It links nothing pulse at build time (pure dlopen/dlsym), so it needs no pulse headers and adds no
// runtime NEEDED deps beyond liblog/libdl. Runs on pause/resume only, never at startup, so it cannot
// affect winepulse's initial device init.

#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>

#define TAG "pasink"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// PulseAudio opaque handles are just pointers to us.
typedef void pa_threaded_mainloop;
typedef void pa_mainloop_api;
typedef void pa_context;
typedef void pa_operation;

// Enum values from PulseAudio's public API (stable ABI).
enum { PA_CONTEXT_READY = 4, PA_CONTEXT_FAILED = 5, PA_CONTEXT_TERMINATED = 6 };
enum { PA_OPERATION_RUNNING = 0, PA_OPERATION_DONE = 1, PA_OPERATION_CANCELLED = 2 };
#define PA_INVALID_INDEX ((uint32_t) -1)

typedef void (*pa_context_notify_cb_t)(pa_context *c, void *userdata);
typedef void (*pa_context_success_cb_t)(pa_context *c, int success, void *userdata);
// A module load reports the new module's index; PA_INVALID_INDEX on failure.
typedef void (*pa_context_index_cb_t)(pa_context *c, uint32_t idx, void *userdata);
// Sink-input list: called once per input then once with eol>0. We only ever read the input's
// index, which is the FIRST field (uint32_t at offset 0) of pa_sink_input_info — ABI-stable — so
// we take `const void*` and never touch the rest of the struct (no fragile layout assumptions).
typedef void (*pa_sink_input_info_cb_t)(pa_context *c, const void *i, int eol, void *userdata);

typedef struct {
    pa_threaded_mainloop *(*mainloop_new)(void);
    int   (*mainloop_start)(pa_threaded_mainloop *m);
    void  (*mainloop_stop)(pa_threaded_mainloop *m);
    void  (*mainloop_free)(pa_threaded_mainloop *m);
    void  (*mainloop_lock)(pa_threaded_mainloop *m);
    void  (*mainloop_unlock)(pa_threaded_mainloop *m);
    void  (*mainloop_wait)(pa_threaded_mainloop *m);
    void  (*mainloop_signal)(pa_threaded_mainloop *m, int wait_for_accept);
    pa_mainloop_api *(*mainloop_get_api)(pa_threaded_mainloop *m);
    pa_context *(*context_new)(pa_mainloop_api *mainloop, const char *name);
    int   (*context_connect)(pa_context *c, const char *server, int flags, const void *api);
    void  (*context_disconnect)(pa_context *c);
    void  (*context_unref)(pa_context *c);
    void  (*context_set_state_callback)(pa_context *c, pa_context_notify_cb_t cb, void *userdata);
    int   (*context_get_state)(pa_context *c);
    pa_operation *(*context_suspend_sink_by_name)(pa_context *c, const char *name, int suspend,
                                                  pa_context_success_cb_t cb, void *userdata);
    pa_operation *(*context_load_module)(pa_context *c, const char *name, const char *argument,
                                         pa_context_index_cb_t cb, void *userdata);
    pa_operation *(*context_unload_module)(pa_context *c, uint32_t idx,
                                           pa_context_success_cb_t cb, void *userdata);
    pa_operation *(*context_move_sink_input_by_name)(pa_context *c, uint32_t idx, const char *sink_name,
                                                     pa_context_success_cb_t cb, void *userdata);
    pa_operation *(*context_set_default_sink)(pa_context *c, const char *name,
                                              pa_context_success_cb_t cb, void *userdata);
    pa_operation *(*context_get_sink_input_info_list)(pa_context *c, pa_sink_input_info_cb_t cb,
                                                      void *userdata);
    int   (*operation_get_state)(pa_operation *o);
    void  (*operation_unref)(pa_operation *o);
} pa_api;

typedef struct {
    pa_api *api;
    pa_threaded_mainloop *m;
    int op_success;
} cb_ctx;

#define MAX_INPUTS 32
typedef struct {
    pa_api *api;
    pa_threaded_mainloop *m;
    int op_success;
    uint32_t op_idx;                // module index returned by load-module
    uint32_t inputs[MAX_INPUTS];    // sink-input indices collected during enumeration
    int n_inputs;
} rec_ctx;

static void state_cb(pa_context *c, void *userdata) {
    (void) c;
    cb_ctx *x = (cb_ctx *) userdata;
    x->api->mainloop_signal(x->m, 0);
}

static void success_cb(pa_context *c, int success, void *userdata) {
    (void) c;
    cb_ctx *x = (cb_ctx *) userdata;
    x->op_success = success;
    x->api->mainloop_signal(x->m, 0);
}

// Recovery-path callbacks (rec_ctx). Each signals the threaded mainloop so the caller's
// operation_get_state() wait loop wakes and re-checks.
static void rec_state_cb(pa_context *c, void *userdata) {
    (void) c;
    rec_ctx *x = (rec_ctx *) userdata;
    x->api->mainloop_signal(x->m, 0);
}
static void rec_index_cb(pa_context *c, uint32_t idx, void *userdata) {
    (void) c;
    rec_ctx *x = (rec_ctx *) userdata;
    x->op_idx = idx;
    x->api->mainloop_signal(x->m, 0);
}
static void rec_success_cb(pa_context *c, int success, void *userdata) {
    (void) c;
    rec_ctx *x = (rec_ctx *) userdata;
    x->op_success = success;
    x->api->mainloop_signal(x->m, 0);
}
static void rec_input_cb(pa_context *c, const void *i, int eol, void *userdata) {
    (void) c;
    rec_ctx *x = (rec_ctx *) userdata;
    if (eol) { x->api->mainloop_signal(x->m, 0); return; }  // end of list: wake the waiter
    if (i && x->n_inputs < MAX_INPUTS)
        x->inputs[x->n_inputs++] = *(const uint32_t *) i;   // index is the first field (offset 0)
}

// dlopen a lib in a dir with RTLD_GLOBAL so later libs resolve its symbols. Returns handle or NULL.
static void *open_lib(const char *dir, const char *name) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    void *h = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!h) LOGW("dlopen %s failed: %s", path, dlerror());
    return h;
}

#define SYM(field, symname) \
    do { *(void **)(&api.field) = dlsym(libpulse, symname); \
         if (!api.field) { LOGW("missing symbol %s", symname); dlclose(libpulse); return -3; } } while (0)

// Returns 0 on success, negative on error.
JNIEXPORT jint JNICALL
Java_com_winlator_star_xenvironment_components_PulseAudioComponent_nativeSuspendSink(
        JNIEnv *env, jclass clazz, jstring jdir, jstring jserver, jstring jsink, jboolean suspend) {
    (void) clazz;
    const char *dir    = (*env)->GetStringUTFChars(env, jdir, NULL);
    const char *server = (*env)->GetStringUTFChars(env, jserver, NULL);
    const char *sink   = (*env)->GetStringUTFChars(env, jsink, NULL);
    jint rc = -1;

    // Resolve the 13.0 client stack that already sits in files/pulseaudio, in dependency order.
    open_lib(dir, "libsndfile.so");                 // libpulsecommon needs it; ignore failure here
    open_lib(dir, "libpulsecommon-13.0.so");        // libpulse needs it
    void *libpulse = open_lib(dir, "libpulse.so");
    if (!libpulse) { rc = -2; goto done; }

    pa_api api;
    memset(&api, 0, sizeof(api));
    SYM(mainloop_new,   "pa_threaded_mainloop_new");
    SYM(mainloop_start, "pa_threaded_mainloop_start");
    SYM(mainloop_stop,  "pa_threaded_mainloop_stop");
    SYM(mainloop_free,  "pa_threaded_mainloop_free");
    SYM(mainloop_lock,  "pa_threaded_mainloop_lock");
    SYM(mainloop_unlock,"pa_threaded_mainloop_unlock");
    SYM(mainloop_wait,  "pa_threaded_mainloop_wait");
    SYM(mainloop_signal,"pa_threaded_mainloop_signal");
    SYM(mainloop_get_api,"pa_threaded_mainloop_get_api");
    SYM(context_new,    "pa_context_new");
    SYM(context_connect,"pa_context_connect");
    SYM(context_disconnect,"pa_context_disconnect");
    SYM(context_unref,  "pa_context_unref");
    SYM(context_set_state_callback,"pa_context_set_state_callback");
    SYM(context_get_state,"pa_context_get_state");
    SYM(context_suspend_sink_by_name,"pa_context_suspend_sink_by_name");
    SYM(operation_get_state,"pa_operation_get_state");
    SYM(operation_unref,"pa_operation_unref");

    pa_threaded_mainloop *m = api.mainloop_new();
    if (!m) { rc = -4; goto done; }
    cb_ctx x = { &api, m, 0 };

    if (api.mainloop_start(m) < 0) { api.mainloop_free(m); rc = -5; goto done; }
    api.mainloop_lock(m);

    pa_context *ctx = api.context_new(api.mainloop_get_api(m), "bannerlator-pasink");
    if (!ctx) { api.mainloop_unlock(m); api.mainloop_stop(m); api.mainloop_free(m); rc = -6; goto done; }

    api.context_set_state_callback(ctx, state_cb, &x);
    if (api.context_connect(ctx, server, 0, NULL) < 0) { rc = -7; goto teardown; }

    // Wait until the context is READY (or fails). state_cb signals us on every transition.
    for (;;) {
        int st = api.context_get_state(ctx);
        if (st == PA_CONTEXT_READY) break;
        if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) { rc = -8; goto teardown; }
        api.mainloop_wait(m);
    }

    // Fire the suspend/resume and wait for the operation to finish.
    pa_operation *op = api.context_suspend_sink_by_name(ctx, sink, suspend ? 1 : 0, success_cb, &x);
    if (!op) { rc = -9; goto teardown; }
    while (api.operation_get_state(op) == PA_OPERATION_RUNNING) api.mainloop_wait(m);
    api.operation_unref(op);
    rc = x.op_success ? 0 : -10;

teardown:
    api.context_disconnect(ctx);
    api.context_unref(ctx);
    api.mainloop_unlock(m);
    api.mainloop_stop(m);
    api.mainloop_free(m);

done:
    if (rc == 0) LOGI("suspend-sink %s %d ok", sink, (int) suspend);
    else         LOGW("suspend-sink %s %d failed rc=%d", sink, (int) suspend, (int) rc);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    (*env)->ReleaseStringUTFChars(env, jserver, server);
    (*env)->ReleaseStringUTFChars(env, jsink, sink);
    return rc;
}

// Recover the guest's audio after a mid-play output-route change (headphones/USB/BT/HDMI plug or
// unplug) DISCONNECTS the AAudio stream. A disconnected AAudio stream can never be restarted
// (AAUDIO_ERROR_DISCONNECTED / -895), so suspend/resume of the existing sink cannot help — the daemon
// doesn't even know its stream died (the sink just reads IDLE). The only cure is a fresh sink whose
// AAudio stream is opened NOW, on the current route. This mechanizes the exact recipe proven live with
// pactl against the 13.0 daemon: load a new module-aaudio-sink, move every guest sink-input onto it,
// make it default, then unload the previous (now-dead) recovery sink. Daemon + guest connection never
// drop. Returns the NEW module's index (>=0) so the caller can pass it back as `unloadModuleIdx` next
// time; negative on error. Call off the main thread.
JNIEXPORT jint JNICALL
Java_com_winlator_star_xenvironment_components_PulseAudioComponent_nativeRecreateSink(
        JNIEnv *env, jclass clazz, jstring jdir, jstring jserver, jstring jnewsink, jstring jextra, jint junload) {
    (void) clazz;
    const char *dir     = (*env)->GetStringUTFChars(env, jdir, NULL);
    const char *server  = (*env)->GetStringUTFChars(env, jserver, NULL);
    const char *newsink = (*env)->GetStringUTFChars(env, jnewsink, NULL);
    const char *extra   = jextra ? (*env)->GetStringUTFChars(env, jextra, NULL) : NULL;
    const int   unload  = (int) junload;
    jint rc = -1;
    rec_ctx x;
    memset(&x, 0, sizeof(x));       // zero-init up front so the error paths that skip setup are safe
    x.op_idx = PA_INVALID_INDEX;

    open_lib(dir, "libsndfile.so");
    open_lib(dir, "libpulsecommon-13.0.so");
    void *libpulse = open_lib(dir, "libpulse.so");
    if (!libpulse) { rc = -2; goto done; }

    pa_api api;
    memset(&api, 0, sizeof(api));
    SYM(mainloop_new,   "pa_threaded_mainloop_new");
    SYM(mainloop_start, "pa_threaded_mainloop_start");
    SYM(mainloop_stop,  "pa_threaded_mainloop_stop");
    SYM(mainloop_free,  "pa_threaded_mainloop_free");
    SYM(mainloop_lock,  "pa_threaded_mainloop_lock");
    SYM(mainloop_unlock,"pa_threaded_mainloop_unlock");
    SYM(mainloop_wait,  "pa_threaded_mainloop_wait");
    SYM(mainloop_signal,"pa_threaded_mainloop_signal");
    SYM(mainloop_get_api,"pa_threaded_mainloop_get_api");
    SYM(context_new,    "pa_context_new");
    SYM(context_connect,"pa_context_connect");
    SYM(context_disconnect,"pa_context_disconnect");
    SYM(context_unref,  "pa_context_unref");
    SYM(context_set_state_callback,"pa_context_set_state_callback");
    SYM(context_get_state,"pa_context_get_state");
    SYM(context_load_module,"pa_context_load_module");
    SYM(context_unload_module,"pa_context_unload_module");
    SYM(context_move_sink_input_by_name,"pa_context_move_sink_input_by_name");
    SYM(context_set_default_sink,"pa_context_set_default_sink");
    SYM(context_get_sink_input_info_list,"pa_context_get_sink_input_info_list");
    SYM(operation_get_state,"pa_operation_get_state");
    SYM(operation_unref,"pa_operation_unref");

    pa_threaded_mainloop *m = api.mainloop_new();
    if (!m) { rc = -4; goto done; }
    x.api = &api; x.m = m;

    if (api.mainloop_start(m) < 0) { api.mainloop_free(m); rc = -5; goto done; }
    api.mainloop_lock(m);

    pa_context *ctx = api.context_new(api.mainloop_get_api(m), "bannerlator-pasink");
    if (!ctx) { api.mainloop_unlock(m); api.mainloop_stop(m); api.mainloop_free(m); rc = -6; goto done; }

    api.context_set_state_callback(ctx, rec_state_cb, &x);
    if (api.context_connect(ctx, server, 0, NULL) < 0) { rc = -7; goto teardown; }
    for (;;) {
        int st = api.context_get_state(ctx);
        if (st == PA_CONTEXT_READY) break;
        if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) { rc = -8; goto teardown; }
        api.mainloop_wait(m);
    }

    pa_operation *op;

    // 1) Load a fresh AAudio sink — its output stream opens now, on the CURRENT route. Carry the
    //    preset args (performance_mode/adaptive/...) so recovery matches the configured audio mode.
    char arg[256];
    if (extra && extra[0])
        snprintf(arg, sizeof(arg), "sink_name=%s %s", newsink, extra);
    else
        snprintf(arg, sizeof(arg), "sink_name=%s", newsink);
    op = api.context_load_module(ctx, "module-aaudio-sink", arg, rec_index_cb, &x);
    if (!op) { rc = -11; goto teardown; }
    while (api.operation_get_state(op) == PA_OPERATION_RUNNING) api.mainloop_wait(m);
    api.operation_unref(op);
    if (x.op_idx == PA_INVALID_INDEX) { rc = -12; goto teardown; }   // module failed to load

    // 2) Enumerate the guest's sink-inputs (collect their indices).
    op = api.context_get_sink_input_info_list(ctx, rec_input_cb, &x);
    if (!op) { rc = -13; goto teardown; }
    while (api.operation_get_state(op) == PA_OPERATION_RUNNING) api.mainloop_wait(m);
    api.operation_unref(op);

    // 3) Move every guest stream onto the new sink (individual failures are non-fatal).
    for (int k = 0; k < x.n_inputs; k++) {
        op = api.context_move_sink_input_by_name(ctx, x.inputs[k], newsink, rec_success_cb, &x);
        if (!op) continue;
        while (api.operation_get_state(op) == PA_OPERATION_RUNNING) api.mainloop_wait(m);
        api.operation_unref(op);
    }

    // 4) Make the new sink the default (so any later stream lands there too).
    op = api.context_set_default_sink(ctx, newsink, rec_success_cb, &x);
    if (op) {
        while (api.operation_get_state(op) == PA_OPERATION_RUNNING) api.mainloop_wait(m);
        api.operation_unref(op);
    }

    // 5) Unload the previous recovery sink (now dead/idle). Skipped when caller passes < 0.
    if (unload >= 0) {
        op = api.context_unload_module(ctx, (uint32_t) unload, rec_success_cb, &x);
        if (op) {
            while (api.operation_get_state(op) == PA_OPERATION_RUNNING) api.mainloop_wait(m);
            api.operation_unref(op);
        }
    }

    rc = (jint) x.op_idx;   // >= 0: the new module index

teardown:
    api.context_disconnect(ctx);
    api.context_unref(ctx);
    api.mainloop_unlock(m);
    api.mainloop_stop(m);
    api.mainloop_free(m);

done:
    if (rc >= 0) LOGI("recreate-sink %s ok (module %d, moved %d inputs, unloaded %d)",
                      newsink, (int) rc, x.n_inputs, unload);
    else         LOGW("recreate-sink %s failed rc=%d", newsink, (int) rc);
    (*env)->ReleaseStringUTFChars(env, jdir, dir);
    (*env)->ReleaseStringUTFChars(env, jserver, server);
    (*env)->ReleaseStringUTFChars(env, jnewsink, newsink);
    if (extra) (*env)->ReleaseStringUTFChars(env, jextra, extra);
    return rc;
}
