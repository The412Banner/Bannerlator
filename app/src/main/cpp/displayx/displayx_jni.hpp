#pragma once

#include <jni.h>
#include <android/native_window_jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/surface_control.h>

#define LOG_TAG "DisplayX"
#define printf(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define HAL_PIXEL_FORMAT_BGRA_8888 5

#define LOAD_METHOD_ID(method, env, cls, name, sig) \
    (method) = (env)->GetMethodID((cls), (name), (sig));

#define LOAD_FIELD_ID(field, env, cls, name, sig) \
    (field) = (env)->GetFieldID((cls), (name), (sig));

// Mirrors the Java-side XServer handles DisplayX needs to reach from the compositor thread.
// Unlike upstream there is no displayDriver/isDisplayX fork here: libdisplayx.so is only
// loaded when the DisplayX renderer is selected, so every path in this library is DisplayX.
class JNIXServer {
    public:
        jobject windowManager;
        jobject inputDeviceManager;
        jobject xserver;

        JNIXServer() {}
};

class JNICache {
    public:
        JavaVM *vm;

        jclass xserverClass;
        jfieldID windowManager;
        jfieldID inputDeviceManager;

        jclass inputDeviceManagerClass;
        jmethodID getPointWindow;

        jclass windowManagerClass;
        jfieldID rootWindow;

        jclass cursorClass;
        jmethodID cursorIsVisible;
        jfieldID cursorID;
        jfieldID cursorHotspotX;
        jfieldID cursorHotspotY;
        jfieldID cursorImage;

        jclass windowClass;
        jmethodID windowGetWidth;
        jmethodID windowGetHeight;
        jmethodID windowGetX;
        jmethodID windowGetY;
        jmethodID windowGetClassName;
        jmethodID windowGetContent;
        jmethodID windowIsInputOutput;
        jfieldID windowID;
        jfieldID windowAttributes;

        jclass windowAttributesClass;
        jmethodID windowAttributesIsEnabled;

        jclass drawableClass;
        jmethodID drawableGetData;
        jfieldID drawableID;
        jfieldID drawableWidth;
        jfieldID drawableHeight;

        jclass gpuImageClass;
        jmethodID gpuImageGetStride;
        jmethodID gpuImageGetHardwareBufferPtr;

        JNICache() {}

        JNIEnv *getEnv() {
            JNIEnv *env = nullptr;
            jint result = vm->GetEnv((void**)&env, JNI_VERSION_1_6);
            if (result == JNI_EDETACHED) {
                vm->AttachCurrentThread(&env, nullptr);
            }
            return env;
        }

        void detachEnv() {
            vm->DetachCurrentThread();
        }

        void init (JavaVM *vm, JNIEnv *env) {
            this->vm = vm;

            jclass xServerClass = env->FindClass("com/winlator/star/xserver/XServer");
            jclass windowClass = env->FindClass("com/winlator/star/xserver/Window");
            jclass windowAttributesClass = env->FindClass("com/winlator/star/xserver/WindowAttributes");
            jclass windowManagerClass = env->FindClass("com/winlator/star/xserver/WindowManager");
            jclass inputDeviceManagerClass = env->FindClass("com/winlator/star/xserver/InputDeviceManager");
            jclass drawableClass = env->FindClass("com/winlator/star/xserver/Drawable");
            jclass cursorClass = env->FindClass("com/winlator/star/xserver/Cursor");
            jclass gpuImageClass = env->FindClass("com/winlator/star/renderer/GPUImage");

            LOAD_FIELD_ID(inputDeviceManager, env, xServerClass, "inputDeviceManager", "Lcom/winlator/star/xserver/InputDeviceManager;");
            LOAD_METHOD_ID(getPointWindow, env, inputDeviceManagerClass, "getPointWindow", "()Lcom/winlator/star/xserver/Window;");

            LOAD_FIELD_ID(windowManager, env, xServerClass, "windowManager", "Lcom/winlator/star/xserver/WindowManager;");
            LOAD_FIELD_ID(rootWindow, env, windowManagerClass, "rootWindow", "Lcom/winlator/star/xserver/Window;");

            LOAD_METHOD_ID(windowGetHeight, env, windowClass, "getHeight", "()S");
            LOAD_METHOD_ID(windowGetWidth, env, windowClass, "getWidth", "()S");
            LOAD_METHOD_ID(windowGetX, env, windowClass, "getX", "()S");
            LOAD_METHOD_ID(windowGetY, env, windowClass, "getY", "()S");
            LOAD_METHOD_ID(windowGetClassName, env, windowClass, "getClassName", "()Ljava/lang/String;");
            LOAD_METHOD_ID(windowIsInputOutput, env, windowClass, "isInputOutput", "()Z");
            LOAD_METHOD_ID(windowAttributesIsEnabled, env, windowAttributesClass, "isEnabled", "()Z");
            LOAD_METHOD_ID(windowGetContent, env, windowClass, "getContent", "()Lcom/winlator/star/xserver/Drawable;");
            LOAD_FIELD_ID(windowID, env, windowClass, "id", "I");
            LOAD_FIELD_ID(windowAttributes, env, windowClass, "attributes", "Lcom/winlator/star/xserver/WindowAttributes;");

            LOAD_METHOD_ID(drawableGetData, env, drawableClass, "getData", "()Ljava/nio/ByteBuffer;");
            LOAD_FIELD_ID(drawableID, env, drawableClass, "id", "I");
            LOAD_FIELD_ID(drawableWidth, env, drawableClass, "width", "S");
            LOAD_FIELD_ID(drawableHeight, env, drawableClass, "height", "S");

            LOAD_METHOD_ID(cursorIsVisible, env, cursorClass, "isVisible", "()Z");
            LOAD_FIELD_ID(cursorID, env, cursorClass, "id", "I");
            LOAD_FIELD_ID(cursorHotspotX, env, cursorClass, "hotSpotX", "I");
            LOAD_FIELD_ID(cursorHotspotY, env, cursorClass, "hotSpotY", "I");
            LOAD_FIELD_ID(cursorImage, env, cursorClass, "cursorImage", "Lcom/winlator/star/xserver/Drawable;");

            // Our GPUImage keeps hardwareBufferPtr private and carries no format field
            // (upstream reads both as public fields); go through the accessors instead.
            LOAD_METHOD_ID(gpuImageGetStride, env, gpuImageClass, "getStride", "()S");
            LOAD_METHOD_ID(gpuImageGetHardwareBufferPtr, env, gpuImageClass, "getHardwareBufferPtr", "()J");

            this->xserverClass = (jclass)env->NewGlobalRef(xServerClass);
            this->windowClass = (jclass)env->NewGlobalRef(windowClass);
            this->windowManagerClass = (jclass)env->NewGlobalRef(windowManagerClass);
            this->windowAttributesClass = (jclass)env->NewGlobalRef(windowAttributesClass);
            this->inputDeviceManagerClass = (jclass)env->NewGlobalRef(inputDeviceManagerClass);
            this->drawableClass = (jclass)env->NewGlobalRef(drawableClass);
            this->cursorClass = (jclass)env->NewGlobalRef(cursorClass);
            this->gpuImageClass = (jclass)env->NewGlobalRef(gpuImageClass);
        }
};
