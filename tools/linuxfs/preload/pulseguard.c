/* PulseAudio operations that never started, for the x86-64 Steam client.
 *
 * In Deck mode (-steamos3) the x86-64 client's SteamOS audio manager passes libpulse the operation a
 * failed request returned - none - and libpulse aborts the client on it: "Assertion 'o' failed at
 * ../src/pulse/operation.c:136 pa_operation_get_state()" right after start (device, 2026-10-02).
 * A missing operation is answered as cancelled and its unref/cancel ignored; every real one goes
 * to libpulse. Built into the x86 libraries only: the arm64 client does not hit it. */
#if defined(__x86_64__) || defined(__i386__)
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stddef.h>

typedef struct pa_operation pa_operation;

#define PA_OPERATION_CANCELLED 2

int pa_operation_get_state(pa_operation *o) {
    static int (*next)(pa_operation *);
    if (!o)
        return PA_OPERATION_CANCELLED;
    if (!next)
        next = (int (*)(pa_operation *))dlsym(RTLD_NEXT, "pa_operation_get_state");
    return next ? next(o) : PA_OPERATION_CANCELLED;
}

void pa_operation_unref(pa_operation *o) {
    static void (*next)(pa_operation *);
    if (!o)
        return;
    if (!next)
        next = (void (*)(pa_operation *))dlsym(RTLD_NEXT, "pa_operation_unref");
    if (next)
        next(o);
}

void pa_operation_cancel(pa_operation *o) {
    static void (*next)(pa_operation *);
    if (!o)
        return;
    if (!next)
        next = (void (*)(pa_operation *))dlsym(RTLD_NEXT, "pa_operation_cancel");
    if (next)
        next(o);
}
#else
typedef int pulseguard_arm_build_has_nothing_to_do;
#endif
