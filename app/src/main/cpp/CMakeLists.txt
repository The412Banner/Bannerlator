cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

# Disable clang-tidy (AGP enables it by default but its include paths
# don't work with NDK cross-compilation for the lsfg-vk dependency)


set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)
add_subdirectory(adrenotools)
# lsfg-vk is built separately by build-lsfg.yml (standalone cmake)
# and its .so is copied to jniLibs. AGP's cmake integration enables
# clang-tidy which fails on NDK cross-compile of the lsfg-vk headers.
# add_subdirectory(lsfg-vk)

# Add Winlator shared library
add_library(winlator SHARED
        xr/engine.c
        xr/framebuffer.c
        xr/input.c
        xr/main.c
        xr/math.c
        xr/renderer.c
        winlator/drawable.c
        winlator/gpu_image.c
        winlator/sysvshared_memory.c
        winlator/xconnector_epoll.c
        winlator/alsa_client.c
        winlator/vulkan.c
)

target_compile_options(winlator PRIVATE -Wall -Wextra)

target_link_libraries(winlator
        log
        android
        jnigraphics
        openxr_loader
        aaudio
        EGL
        GLESv2
        GLESv3
        adrenotools
)

# Add Fake Input library for controller emulation (loaded via LD_PRELOAD)
add_library(fakeinput SHARED
        winlator/fakeinput.cpp
)

target_compile_options(fakeinput PRIVATE -Wall -Wextra -fvisibility=hidden)

target_link_libraries(fakeinput
        log
        dl
)

add_library(vulkan_renderer SHARED
        winlator/vulkan_jni.cpp
        winlator/VulkanRendererContext.cpp
        winlator/VulkanRendererScanout.cpp
        scanout/ScanoutContext.cpp
)

target_compile_options(vulkan_renderer PRIVATE
        -Wall -Wextra -fvisibility=hidden -std=c++17
)

target_link_libraries(vulkan_renderer
        log
        android
        vulkan
        adrenotools
        dl
        atomic
)

# SurfaceFlinger (ASurfaceRenderer / ASR) host renderer — ported from
# GameNative PR #1582 (André Vito), built on StevenMX's scanout work.
# Self-contained: standard NDK + dlsym'd ASurfaceControl/ASurfaceTransaction
# (gated to API>=29 at runtime); the scanout AHB arrives via JNI.
add_library(asurface_renderer SHARED
        asurfacerenderer/asurface_jni.cpp
        asurfacerenderer/ASurfaceRendererContext.cpp
        asurfacerenderer/blit_converter.cpp
)

target_compile_options(asurface_renderer PRIVATE
        -Wall -Wextra -fvisibility=hidden -std=c++17
)

# EGL + GLESv3: the BGRA->RGBA GPU converter (blit_converter.cpp, GN #1620) owns a
# private EGL context + GLES3.1 compute / GLES3.0 fragment blit on a dedicated thread.
target_link_libraries(asurface_renderer
        log
        android
        dl
        atomic
        EGL
        GLESv3
)

# AHBImage native (GN #1620): the fenced 3-buffer CPU scanout swapchain used by the
# ASR renderer for CPU-drawn window chrome. Kept as its own .so (mirrors GN) so it
# is not dragged onto the GL/Vulkan path; AHBImage.java does loadLibrary("ahbimage").
add_library(ahbimage SHARED
        asurfacerenderer/ahbimage.c
)

target_compile_definitions(ahbimage PRIVATE
        EGL_EGLEXT_PROTOTYPES
        GL_GLEXT_PROTOTYPES
)

target_compile_options(ahbimage PRIVATE -Wall -Wextra)

target_link_libraries(ahbimage
        log
        android
        EGL
        GLESv2
)

# Small standalone direct-scanout lib for the GL (and any non-Vulkan) renderer.
# Compiles ScanoutContext.cpp into itself (object duplicated with vulkan_renderer
# by design — simpler/safer than a third shared lib; ScanoutContext has no global
# state). Links log/android/dl/atomic only — NO vulkan, NO adrenotools — so a GL
# container can load it without dragging Vulkan/adreno deps onto the GL path.
add_library(direct_scanout SHARED
        scanout/directscanout_jni.cpp
        scanout/ScanoutContext.cpp
)

target_compile_options(direct_scanout PRIVATE
        -Wall -Wextra -fvisibility=hidden -std=c++17
)

target_link_libraries(direct_scanout
        log
        android
        dl
        atomic
)

