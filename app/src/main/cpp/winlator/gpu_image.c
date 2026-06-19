#include <android/log.h>
#include <android/hardware_buffer.h>
#include <jni.h>

static jmethodID g_setStride = NULL;
#include <unistd.h>

#define LOG_TAG "GPUImage"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_winlator_star_renderer_GPUImage_hardwareBufferFromSocket(JNIEnv *env, jclass obj, jint fd) {
    AHardwareBuffer *ahb;
    uint8_t buf = 1;
    if (write(fd, &buf, 1) == -1) {
        LOGE("hardwareBufferFromSocket: write failed");
        return 0;
    }
    if (AHardwareBuffer_recvHandleFromUnixSocket(fd, &ahb) != 0) {
        LOGE("hardwareBufferFromSocket: recvHandle failed");
        return 0;
    }
    return (jlong)ahb;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_star_renderer_GPUImage_createHardwareBuffer(JNIEnv *env, jclass obj, jshort width, jshort height) {
    AHardwareBuffer_Desc desc = {
        .width  = (uint32_t)width,
        .height = (uint32_t)height,
        .layers = 1,
        .usage  = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
                | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY,
        .format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,
    };
#if __ANDROID_API__ >= 29
    if (AHardwareBuffer_isSupported(&desc) == 0) {
        desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
        if (AHardwareBuffer_isSupported(&desc) == 0) {
            LOGE("createHardwareBuffer: unsupported desc");
            return 0;
        }
    }
#endif
    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0) {
        LOGE("createHardwareBuffer: alloc failed");
        return 0;
    }
    return (jlong)ahb;
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_GPUImage_destroyHardwareBuffer(JNIEnv *env, jclass obj, jlong ptr) {
    AHardwareBuffer *ahb = (AHardwareBuffer *)ptr;
    if (ahb) {
        AHardwareBuffer_release(ahb);
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_star_renderer_GPUImage_unlockHardwareBuffer(JNIEnv *env, jclass obj, jlong ptr) {
    AHardwareBuffer *ahb = (AHardwareBuffer *)ptr;
    if (!ahb) return -1;
    int fence_fd = -1;
    AHardwareBuffer_unlock(ahb, &fence_fd);
    return (jint)fence_fd;
}

JNIEXPORT jobject JNICALL
Java_com_winlator_star_renderer_GPUImage_lockHardwareBuffer(JNIEnv *env, jobject obj, jlong ptr) {
    AHardwareBuffer *ahb = (AHardwareBuffer *)ptr;
    if (!ahb) {
        LOGE("lockHardwareBuffer: null pointer");
        return NULL;
    }
    void *addr;
    if (AHardwareBuffer_lock(ahb, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, NULL, &addr) != 0) {
        LOGE("lockHardwareBuffer: lock failed");
        return NULL;
    }
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(ahb, &desc);

    static jclass    sCls       = NULL;
    static jmethodID sSetStride = NULL;
    if (!sSetStride) {
        jclass local = (*env)->GetObjectClass(env, obj);
        sCls       = (*env)->NewGlobalRef(env, local);
        sSetStride = (*env)->GetMethodID(env, sCls, "setStride", "(S)V");
        (*env)->DeleteLocalRef(env, local);
    }
    if (sSetStride)
        (*env)->CallVoidMethod(env, obj, sSetStride, (jshort)desc.stride);

    jobject buffer = (*env)->NewDirectByteBuffer(env, addr, (jlong)desc.stride * desc.height * 4);
    if (!buffer) {
        LOGE("lockHardwareBuffer: NewDirectByteBuffer failed");
        AHardwareBuffer_unlock(ahb, NULL);
    }
    return buffer;
}
