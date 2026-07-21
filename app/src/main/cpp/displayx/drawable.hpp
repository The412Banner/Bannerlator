#pragma once

#include <mutex>

#include "displayx_jni.hpp"

struct Drawable { 
    int id;
    int width;
    int format;
    int height;
    int stride;
    int textureId;
    bool isDirty;
    bool sizeChanged;
    bool isDirectContent;
    void *data;
    jobject drawableObj;
    AHardwareBuffer *ahb;
};