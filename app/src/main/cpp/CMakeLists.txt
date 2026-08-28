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
        winlator/perf_turbo.c
        winlator/ring_fence.c
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

# DrawableBufferPool native heap (ERL bug report #2): tiny dependency-free lib
# (libc + JNI only) backing the X-server Drawable buffer pool with calloc/free in
# the native heap, bypassing ART's small non-compacting non-moving space which
# fragments under cursor/drawable churn. Own .so so it stays off every other path.
add_library(drawablepool SHARED
        winlator/drawablepool.c
)

target_compile_options(drawablepool PRIVATE -Wall -Wextra)

target_link_libraries(drawablepool
        log
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

# DXVK's DXBC->SPIR-V translator subset (zlib licence), vendored under
# thirdparty/dxbc from the same subset lsfg-vk uses. Builds a static `dxbc`
# lib; only arm64-v8a is built so its x86 intrinsics paths are never taken.
add_subdirectory(thirdparty/dxbc)

# Host-side Lossless Scaling frame generation (ported from WinNative PR #697,
# GPL-3.0-or-later; dual eden/lsfg-vk copyright headers preserved on the chain
# files). Self-contained: the whole optical-flow compute chain sits behind the
# `vkr_lsfg` C API and a private `vkd` dispatch table populated from the
# compositor's own driver handle. Uses VK_NO_PROTOTYPES internally, so it links
# no Vulkan library — every call goes through function pointers. Built as its
# own static lib so the C sources are not forced onto the C++17 renderer flags.
add_library(lsfg_host STATIC
        winlator/lsfg/vk_dispatch.c
        winlator/lsfg/lsfg_dll.c
        winlator/lsfg/lsfg_probe.c
        winlator/lsfg/lsfg_dxbc.cpp
        winlator/lsfg/lsfg_common.cpp
        winlator/lsfg/lsfg_shaders.cpp
        winlator/lsfg/lsfg_mipmaps.cpp
        winlator/lsfg/lsfg_alpha.cpp
        winlator/lsfg/lsfg_beta.cpp
        winlator/lsfg/lsfg_gamma.cpp
        winlator/lsfg/lsfg_delta.cpp
        winlator/lsfg/lsfg_generate.cpp
        winlator/lsfg/lsfg_chain.cpp
        winlator/lsfg/lsfg_pacer.cpp
        winlator/lsfg/vkr_lsfg.cpp
        winlator/lsfg/lsfg_host_bridge.cpp
)

set_target_properties(lsfg_host PROPERTIES
        C_STANDARD 11
        CXX_STANDARD 17
        CXX_STANDARD_REQUIRED ON
        POSITION_INDEPENDENT_CODE ON)

# The dispatch table declares Android-specific PFN types (AndroidSurfaceKHR,
# AHardwareBuffer props), so every lsfg_host TU needs the Android platform types
# from <vulkan/vulkan.h>.
target_compile_definitions(lsfg_host PRIVATE VK_USE_PLATFORM_ANDROID_KHR=1)

target_include_directories(lsfg_host PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/winlator/lsfg)

target_link_libraries(lsfg_host PUBLIC dxbc log android dl)

add_library(vulkan_renderer SHARED
        winlator/vulkan_jni.cpp
        winlator/VulkanRendererContext.cpp
        winlator/VulkanRendererScanout.cpp
        winlator/lsfg/lsfg_host_jni.cpp
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
        lsfg_host
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


# pasink — tiny PulseAudio client (dlopen-based) to suspend/resume the guest audio sink and recover
# the output route after backgrounding / HDMI route changes. No pulse link deps (pure dlopen/dlsym).
add_library(pasink SHARED
        pasink/pasink.c
)

target_link_libraries(pasink
        log
        dl
)
