/* JNI bridge for the lannet overlay tunnel (P1). Java hands us the VpnService
 * tun fd + relay/room params; we run the pump on a worker thread. */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <stdint.h>
#include <android/log.h>
#include "tunnel.h"

#define TAG "lannet"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct {
    lannet_tunnel t;
    pthread_t     th;
} lannet_handle;

static void *pump(void *v) {
    lannet_handle *h = v;
    lannet_tunnel_run(&h->t);
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_star_net_LanOverlay_nativeStart(JNIEnv *env, jclass clazz,
        jint tunFd, jstring relayIp, jint relayPort, jstring room, jint role,
        jint localBcast) {
    (void)clazz;
    const char *ip = (*env)->GetStringUTFChars(env, relayIp, NULL);
    const char *rm = (*env)->GetStringUTFChars(env, room, NULL);

    lannet_handle *h = calloc(1, sizeof(*h));
    int rc = h ? lannet_tunnel_open(&h->t, tunFd, ip, (int)relayPort, rm,
                                    (uint8_t)role, (uint32_t)localBcast) : -99;
    LOGI("nativeStart tunFd=%d relay=%s:%d room=%s role=%d localBcast=0x%08x rc=%d",
         tunFd, ip, relayPort, rm, role, (uint32_t)localBcast, rc);

    (*env)->ReleaseStringUTFChars(env, relayIp, ip);
    (*env)->ReleaseStringUTFChars(env, room, rm);

    if (rc != 0) { free(h); return 0; }
    if (pthread_create(&h->th, NULL, pump, h) != 0) {
        LOGE("pthread_create failed");
        lannet_tunnel_close(&h->t);
        free(h);
        return 0;
    }
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_com_winlator_star_net_LanOverlay_nativeStop(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (!handle) return;
    lannet_handle *h = (lannet_handle *)(intptr_t)handle;
    lannet_tunnel_stop(&h->t);
    pthread_join(h->th, NULL);
    lannet_tunnel_close(&h->t);
    LOGI("nativeStop done");
    free(h);
}
