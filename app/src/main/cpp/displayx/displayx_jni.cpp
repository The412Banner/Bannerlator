#include "displayx.hpp"

// JNI surface for the DisplayX host renderer.
//
// Ported from Pipetto-crypto/winlator (branch winlator_bionic, MIT) —
// app/src/main/cpp/winlator/renderer/renderer_jni.cpp, reduced to the DisplayX half.
// Upstream keeps EGL and DisplayX in one library and forks on JNIXServer::isDisplayX();
// here DisplayX is its own libdisplayx.so that is only loaded when it is the selected
// renderer, so every isDisplayX() branch collapses away.
//
// Two other deliberate divergences from upstream:
//   * the root cursor bitmap is handed in from Java (R.drawable.cursor, matching GLRenderer)
//     instead of being decoded natively from assets/cursor.png with stb_image;
//   * z-order changes are not plumbed — upstream's nativeChangeWindowZOrder is a no-op in
//     DisplayX mode, layering comes from SurfaceControl parentage and creation order.

JNICache cache;
JNIXServer xserver;
WindowManager windowManager;
CursorManager cursorManager;
DisplayX displayX;

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    vm->GetEnv((void**)&env, JNI_VERSION_1_6);

    cache.init(vm, env);

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeInit(JNIEnv *env, jobject thiz, jobject xServer,
                                                            jobject rootCursorData, jint cursorWidth, jint cursorHeight) {
    jobject windowManagerObj = env->GetObjectField(xServer, cache.windowManager);
    jobject inputDeviceManagerObj = env->GetObjectField(xServer, cache.inputDeviceManager);
    jobject rootWindowObj = env->GetObjectField(windowManagerObj, cache.rootWindow);

    auto rootWindow = std::make_unique<struct Window>();

    rootWindow->id = env->GetIntField(rootWindowObj, cache.windowID);
    rootWindow->width = env->CallShortMethod(rootWindowObj, cache.windowGetWidth);
    rootWindow->height = env->CallShortMethod(rootWindowObj, cache.windowGetHeight);
    rootWindow->x = env->CallShortMethod(rootWindowObj, cache.windowGetX);
    rootWindow->y = env->CallShortMethod(rootWindowObj, cache.windowGetY);

    jstring className = (jstring)env->CallObjectMethod(rootWindowObj, cache.windowGetClassName);
    if (className) {
        const char *chars = env->GetStringUTFChars(className, nullptr);
        rootWindow->className = std::string(chars);
        env->ReleaseStringUTFChars(className, chars);
    }

    auto drawable = std::make_unique<struct Drawable>();
    jobject drawableObj = env->CallObjectMethod(rootWindowObj, cache.windowGetContent);
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->textureId = -1;
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);

    jobject dataBuf = env->CallObjectMethod(drawableObj, cache.drawableGetData);
    drawable->data = env->GetDirectBufferAddress(dataBuf);

    drawable->format = HAL_PIXEL_FORMAT_BGRA_8888;
    drawable->isDirty = false;
    drawable->isDirectContent = false;
    drawable->sizeChanged = false;
    drawable->ahb = nullptr;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);
    rootWindow->drawable = std::move(drawable);

    env->DeleteLocalRef(drawableObj);

    rootWindow->cursor = nullptr;
    rootWindow->parent = nullptr;
    rootWindow->mapped = true;
    rootWindow->control = nullptr;
    rootWindow->enabled = true;
    rootWindow->inputOutput = true;
    rootWindow->currentDirectContent = nullptr;

    jobject attributes = env->GetObjectField(rootWindowObj, cache.windowAttributes);
    rootWindow->attributes = env->NewGlobalRef(attributes);
    rootWindow->windowObj = env->NewGlobalRef(rootWindowObj);

    env->DeleteLocalRef(rootWindowObj);
    env->DeleteLocalRef(attributes);

    windowManager.setRootWindow(rootWindow.get());
    windowManager.addWindow(rootWindow->id, std::move(rootWindow));

    // Root cursor pixels come from Java (already decoded from R.drawable.cursor). The buffer
    // is owned by the Java Drawable, which DisplayXRenderer holds for its whole lifetime.
    auto cursorDrawable = std::make_unique<struct Drawable>();
    cursorDrawable->id = -1;
    cursorDrawable->textureId = -1;
    cursorDrawable->isDirectContent = false;
    cursorDrawable->format = HAL_PIXEL_FORMAT_BGRA_8888;
    cursorDrawable->width = cursorWidth;
    cursorDrawable->height = cursorHeight;
    cursorDrawable->data = env->GetDirectBufferAddress(rootCursorData);
    cursorDrawable->isDirty = true;
    cursorDrawable->sizeChanged = false;
    cursorDrawable->ahb = nullptr;
    cursorDrawable->drawableObj = nullptr;

    auto rootCursor = std::make_unique<struct Cursor>();
    rootCursor->id = cursorDrawable->id;
    rootCursor->image = std::move(cursorDrawable);
    rootCursor->hotspotX = 0;
    rootCursor->hotspotY = 0;
    rootCursor->visible = true;
    rootCursor->cursorObj = nullptr;

    cursorManager.setRootCursor(std::move(rootCursor));

    xserver.windowManager = env->NewGlobalRef(windowManagerObj);
    xserver.inputDeviceManager = env->NewGlobalRef(inputDeviceManagerObj);
    xserver.xserver = env->NewGlobalRef(xServer);

    env->DeleteLocalRef(windowManagerObj);
    env->DeleteLocalRef(inputDeviceManagerObj);

    displayX.windowManager = &windowManager;
    displayX.cursorManager = &cursorManager;
    displayX.cache = &cache;
    displayX.xServer = &xserver;

    displayX.start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeCreateWindow(JNIEnv *env, jobject thiz, jobject windowObj, jint parentId) {
    auto window = std::make_unique<struct Window>();
    window->id = env->GetIntField(windowObj, cache.windowID);
    window->width = env->CallShortMethod(windowObj, cache.windowGetWidth);
    window->height = env->CallShortMethod(windowObj, cache.windowGetHeight);
    window->x = env->CallShortMethod(windowObj, cache.windowGetX);
    window->y = env->CallShortMethod(windowObj, cache.windowGetY);

    jstring className = (jstring)env->CallObjectMethod(windowObj, cache.windowGetClassName);
    if (className) {
        const char *chars = env->GetStringUTFChars(className, nullptr);
        window->className = std::string(chars);
        env->ReleaseStringUTFChars(className, chars);
    }

    bool isInputOutput = env->CallBooleanMethod(windowObj, cache.windowIsInputOutput);
    window->inputOutput = isInputOutput;
    window->drawable = nullptr;

    if (isInputOutput) {
        auto drawable = std::make_unique<struct Drawable>();
        jobject drawableObj = env->CallObjectMethod(windowObj, cache.windowGetContent);
        drawable->id = env->GetIntField(drawableObj, cache.drawableID);
        drawable->textureId = -1;
        drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
        drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
        drawable->data = nullptr;
        drawable->format = HAL_PIXEL_FORMAT_BGRA_8888;
        drawable->isDirty = false;
        drawable->isDirectContent = false;
        drawable->sizeChanged = false;
        drawable->ahb = nullptr;
        drawable->drawableObj = env->NewGlobalRef(drawableObj);
        window->drawable = std::move(drawable);
        env->DeleteLocalRef(drawableObj);
    }

    window->cursor = nullptr;
    window->mapped = false;
    window->parent = nullptr;
    window->control = nullptr;
    window->currentDirectContent = nullptr;
    window->enabled = true;

    jobject attributes = env->GetObjectField(windowObj, cache.windowAttributes);
    window->attributes = env->NewGlobalRef(attributes);
    window->windowObj = env->NewGlobalRef(windowObj);

    env->DeleteLocalRef(attributes);

    if (parentId > -1) {
        auto parent = windowManager.getWindow(parentId);
        if (parent) {
            window->parent = parent;
            parent->children.push_back(window.get());
        }
    }

    displayX.queueEvent([ptr = window.get()] { displayX.createWindowControl(ptr); });

    windowManager.addWindow(window->id, std::move(window));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeMapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    window->mapped = true;
    window->enabled = env->CallBooleanMethod(window->attributes, cache.windowAttributesIsEnabled);
    displayX.queueEvent([window] { displayX.mapWindow(window); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeUnmapWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    window->mapped = false;
    displayX.queueEvent([window] { displayX.unmapWindow(window); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeDestroyWindow(JNIEnv *env, jobject thiz, jint id) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    displayX.queueEvent([window] { displayX.destroyWindowControl(window); });
    displayX.queueEvent([window] {
        JNIEnv* env = cache.getEnv();
        windowManager.deleteWindow(env, window);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeReparentWindow(JNIEnv *env, jobject thiz, jint id, jint parentId) {
    auto window = windowManager.getWindow(id);
    auto parent = windowManager.getWindow(parentId);

    if (!window || !parent) return;

    windowManager.reparentWindow(window, parent);
    displayX.queueEvent([window, parent] { displayX.reparentWindow(window, parent); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeUpdateWindowGeometry(JNIEnv *env, jobject thiz, jint id, jint width, jint height, jint x, jint y, jboolean resized) {
    auto window = windowManager.getWindow(id);
    if (!window) return;

    window->width = width;
    window->height = height;
    window->x = x;
    window->y = y;

    if (resized && window->inputOutput) {
        window->drawable->data = nullptr;
        window->drawable->width = width;
        window->drawable->height = height;
        window->drawable->sizeChanged = true;
    }

    displayX.queueEvent([window, resized] { displayX.changeGeometry(window, resized); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeUpdateWindowContent(JNIEnv *env, jobject thiz, jint id, jobject data) {
    auto window = windowManager.getWindow(id);
    if (!window || !window->drawable) return;

    if (window->drawable->data == nullptr)
        window->drawable->data = env->GetDirectBufferAddress(data);

    window->drawable->isDirty = true;

    displayX.requestWindowUpdate(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeCreateCursor(JNIEnv *env, jobject thiz, jobject cursorObj) {
    auto drawable = std::make_unique<struct Drawable>();
    jobject drawableObj = env->GetObjectField(cursorObj, cache.cursorImage);
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->data = nullptr;
    drawable->format = HAL_PIXEL_FORMAT_BGRA_8888;
    drawable->isDirectContent = false;
    drawable->isDirty = false;
    drawable->textureId = -1;
    drawable->sizeChanged = false;
    drawable->ahb = nullptr;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);

    env->DeleteLocalRef(drawableObj);

    auto cursor = std::make_unique<struct Cursor>();
    cursor->id = env->GetIntField(cursorObj, cache.cursorID);
    cursor->image = std::move(drawable);
    cursor->hotspotX = env->GetIntField(cursorObj, cache.cursorHotspotX);
    cursor->hotspotY = env->GetIntField(cursorObj, cache.cursorHotspotY);
    cursor->visible = env->CallBooleanMethod(cursorObj, cache.cursorIsVisible);
    cursor->cursorObj = env->NewGlobalRef(cursorObj);

    displayX.queueEvent([ptr = cursor.get()] { displayX.createCursor(ptr); });

    cursorManager.addCursor(cursor->id, std::move(cursor));
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeFreeCursor(JNIEnv *env, jobject thiz, jint id) {
    auto cursor = cursorManager.getCursor(id);
    if (!cursor) return;

    displayX.queueEvent([cursor] { displayX.destroyCursor(cursor); });
    displayX.queueEvent([cursor] {
        JNIEnv* env = cache.getEnv();
        cursorManager.removeCursor(env, cursor);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeBindCursor(JNIEnv *env, jobject thiz, jint windowId, jint cursorId, jboolean visible, jobject data) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;
    auto cursor = cursorManager.getCursor(cursorId);
    if (!cursor) return;

    if (cursor->image->data == nullptr)
        cursor->image->data = env->GetDirectBufferAddress(data);

    cursor->visible = visible;
    cursor->image->isDirty = true;

    window->cursor = cursor;
    for (auto &child : window->children)
        child->cursor = cursor;

    displayX.queueEvent([window] { displayX.updateCursor(window); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativePointerMove(JNIEnv *env, jobject thiz, jint posX, jint posY) {
    cursorManager.pointer.posX = posX;
    cursorManager.pointer.posY = posY;

    displayX.requestCursorUpdate();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeSetCursorVisible(JNIEnv *env, jobject thiz, jboolean visible) {
    displayX.cursorVisible = visible;
    displayX.queueEvent([] { displayX.drawRootCursor(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeToggleFullscreen(JNIEnv *env, jobject thiz) {
    displayX.queueEvent([] { displayX.toggleFullscreen(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeCreateSurface(JNIEnv *env, jobject thiz, jobject surface) {
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    displayX.createSurface(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeDestroySurface(JNIEnv *env, jobject thiz) {
    displayX.destroySurface();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeChangeSurface(JNIEnv *env, jobject thiz, jint width, jint height) {
    displayX.changeSurface(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativePause(JNIEnv *env, jobject thiz) {
    displayX.pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeResume(JNIEnv *env, jobject thiz) {
    displayX.resume();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeStop(JNIEnv *env, jobject thiz) {
    displayX.stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeAddDirectContent(JNIEnv *env, jobject thiz, jint windowId, jobject drawableObj, jobject gpuImageObj) {
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)env->CallLongMethod(gpuImageObj, cache.gpuImageGetHardwareBufferPtr);
    if (!hardwareBuffer) return;

    auto window = windowManager.getWindow(windowId);
    if (!window) return;

    auto drawable = std::make_unique<struct Drawable>();
    drawable->id = env->GetIntField(drawableObj, cache.drawableID);
    drawable->textureId = -1;
    drawable->width = env->GetShortField(drawableObj, cache.drawableWidth);
    drawable->height = env->GetShortField(drawableObj, cache.drawableHeight);
    drawable->data = nullptr;
    drawable->isDirty = false;
    drawable->format = HAL_PIXEL_FORMAT_BGRA_8888;
    drawable->sizeChanged = false;
    drawable->ahb = hardwareBuffer;
    drawable->isDirectContent = true;
    drawable->drawableObj = env->NewGlobalRef(drawableObj);

    window->currentDirectContent = nullptr;
    window->directContents[drawable->id] = std::move(drawable);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeUpdateDirectContent(JNIEnv *env, jobject thiz, jint windowId, jint drawableId) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;

    auto it = window->directContents.find(drawableId);
    if (it == window->directContents.end()) return;

    window->currentDirectContent = it->second.get();

    displayX.requestWindowUpdate(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_star_renderer_DisplayXRenderer_nativeRemoveDirectContent(JNIEnv *env, jobject thiz, jint windowId, jint drawableId) {
    auto window = windowManager.getWindow(windowId);
    if (!window) return;

    window->directContents.erase(drawableId);
}
