#include <android/log.h>
#include <android/hardware_buffer.h>

#define EGL_EGLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>
#include <unistd.h>

#define LOG_TAG "GPUImage"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// X server depth-32 pixmaps are BGRA; keep the imported/allocated AHB in the same
// channel order so GL (GL_BGRA sampling) and the X content match without a swizzle.
#define HAL_PIXEL_FORMAT_BGRA_8888 5

// Create an EGLImageKHR from an AHardwareBuffer and bind it to a GL texture so the
// buffer can be sampled directly on the GL renderer (zero-copy AHB -> GL texture).
static EGLImageKHR create_image_khr(AHardwareBuffer *hardwareBuffer, int textureId) {
    if (!hardwareBuffer) {
        LOGE("createImageKHR: invalid AHardwareBuffer pointer");
        return NULL;
    }

    const EGLint attribList[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
    AHardwareBuffer_acquire(hardwareBuffer);

    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (!clientBuffer) {
        LOGE("createImageKHR: failed to get native client buffer");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        LOGE("createImageKHR: invalid EGLDisplay");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    EGLImageKHR imageKHR = eglCreateImageKHR(eglDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribList);
    if (!imageKHR) {
        LOGE("createImageKHR: failed to create EGLImageKHR");
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, textureId);
    if (glGetError() != GL_NO_ERROR) {
        LOGE("createImageKHR: failed to bind texture");
        eglDestroyImageKHR(eglDisplay, imageKHR);
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, imageKHR);
    if (glGetError() != GL_NO_ERROR) {
        LOGE("createImageKHR: failed to bind EGLImage to texture");
        eglDestroyImageKHR(eglDisplay, imageKHR);
        AHardwareBuffer_release(hardwareBuffer);
        return NULL;
    }

    glBindTexture(GL_TEXTURE_2D, 0);
    return imageKHR;
}

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
                | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
        .format = HAL_PIXEL_FORMAT_BGRA_8888,
    };
    AHardwareBuffer *ahb = NULL;
    if (AHardwareBuffer_allocate(&desc, &ahb) != 0) {
        LOGE("createHardwareBuffer: alloc failed");
        return 0;
    }
    return (jlong)ahb;
}

JNIEXPORT jlong JNICALL
Java_com_winlator_star_renderer_GPUImage_createImageKHR(JNIEnv *env, jclass obj, jlong hardwareBufferPtr, jint textureId) {
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)hardwareBufferPtr;
    if (!hardwareBuffer) {
        LOGE("createImageKHR: invalid AHardwareBuffer pointer");
        return 0;
    }
    return (jlong)create_image_khr(hardwareBuffer, textureId);
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_GPUImage_destroyImageKHR(JNIEnv *env, jclass obj, jlong imageKHRPtr) {
    EGLImageKHR imageKHR = (EGLImageKHR)imageKHRPtr;
    if (imageKHR) {
        EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        eglDestroyImageKHR(eglDisplay, imageKHR);
    }
}

JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_GPUImage_destroyHardwareBuffer(JNIEnv *env, jclass obj, jlong ptr) {
    AHardwareBuffer *ahb = (AHardwareBuffer *)ptr;
    if (ahb) {
        AHardwareBuffer_unlock(ahb, NULL);
        AHardwareBuffer_release(ahb);
    }
}

JNIEXPORT jint JNICALL
Java_com_winlator_star_renderer_GPUImage_unlockHardwareBuffer(JNIEnv *env, jclass obj, jlong ptr) {
    AHardwareBuffer *ahb = (AHardwareBuffer *)ptr;
    if (!ahb) return -1;
    int fence_fd = -1;
    if (AHardwareBuffer_unlock(ahb, &fence_fd) != 0) return -1;
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

    jclass cls = (*env)->GetObjectClass(env, obj);
    jmethodID setStride = (*env)->GetMethodID(env, cls, "setStride", "(S)V");
    if (setStride)
        (*env)->CallVoidMethod(env, obj, setStride, (jshort)desc.stride);

    jobject buffer = (*env)->NewDirectByteBuffer(env, addr, (jlong)desc.stride * desc.height * 4);
    if (!buffer) {
        LOGE("lockHardwareBuffer: NewDirectByteBuffer failed");
        AHardwareBuffer_unlock(ahb, NULL);
    }
    return buffer;
}
