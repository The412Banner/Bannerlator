cmake_minimum_required(VERSION 3.22.1)

project(Winlator)

# Disable clang-tidy (AGP enables it by default but its include paths
# don't work with NDK cross-compilation for the lsfg-vk dependency)


set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(OpenXR-SDK)
add_subdirectory(patchelf)
add_subdirectory(adrenotools)

# DXVK's DXBC->SPIR-V translator (zlib licence), vendored under thirdparty/dxbc
# from the same subset upstream lsfg-vk uses. Required by native LSFG frame
# generation: the Lossless Scaling shader chain is DXBC in every build that is
# downloadable from Steam, so the precompiled-SPIR-V path alone is not enough.
add_subdirectory(thirdparty/dxbc)
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

add_library(vulkan_renderer SHARED
        winlator/vulkan_jni.cpp
        winlator/VulkanRendererContext.cpp
        winlator/VulkanRendererScanout.cpp
        winlator/lsfg/lsfg_probe.cpp
        winlator/lsfg/lsfg_dll.cpp
        winlator/lsfg/lsfg_dxbc.cpp
        winlator/lsfg/lsfg_vkd.cpp
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
        winlator/lsfg/lsfg_engine.cpp
        winlator/lsfg/lsfg_governor.cpp
        winlator/lsfg/lsfg_jni.cpp
        winlator/winfg/framegen.cpp
        winlator/winfg/winfg_vkd.cpp
        winlator/winfg/winfg_engine.cpp
        scanout/ScanoutContext.cpp
)

# --- win-fg chain provenance -------------------------------------------------
# The win-fg chain is COPIED into this tree from github.com/The412Banner/win-fg
# rather than linked, so nothing about it self-identifies: a device log could
# say which Bannerlator commit it came from, but not which win-fg. The layer
# build solves this by stamping `git describe` into libwin_fg.so; the native
# path needs its own answer.
#
# Two halves, deliberately:
#   WINFG_UPSTREAM   the upstream tag the sources were copied from. Hand-set,
#                    so UPDATE IT when you re-copy the chain.
#   WINFG_CHAIN_HASH a hash over the chain sources themselves, computed here at
#                    configure time. This one cannot rot - edit any chain file
#                    and it changes, including a local edit that never went
#                    upstream, which is exactly the case a hand-set tag hides.
set(WINFG_UPSTREAM "v0.2.1-5-g1cf96cf")
set(WINFG_CHAIN_SRC
        winlator/winfg/framegen.cpp
        winlator/winfg/framegen.hpp
        winlator/winfg/record_impl.inc
        winlator/winfg/config.hpp
        winlator/winfg/embedded_shaders.hpp
        winlator/winfg/winfg_engine.cpp
)
set(WINFG_HASH_INPUT "")
foreach(f ${WINFG_CHAIN_SRC})
    file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/${f}" _h)
    string(APPEND WINFG_HASH_INPUT "${_h}")
endforeach()
string(SHA256 WINFG_CHAIN_HASH_FULL "${WINFG_HASH_INPUT}")
string(SUBSTRING "${WINFG_CHAIN_HASH_FULL}" 0 12 WINFG_CHAIN_HASH)
message(STATUS "win-fg chain: ${WINFG_UPSTREAM} (src ${WINFG_CHAIN_HASH})")

target_compile_definitions(vulkan_renderer PRIVATE
        WINFG_UPSTREAM="${WINFG_UPSTREAM}"
        WINFG_CHAIN_HASH="${WINFG_CHAIN_HASH}"
)

target_compile_options(vulkan_renderer PRIVATE
        -Wall -Wextra -fvisibility=hidden -std=c++17
)

target_link_libraries(vulkan_renderer
        log
        android
        vulkan
        adrenotools
        dxbc
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


# pasink — tiny PulseAudio client (dlopen-based) to suspend/resume the guest audio sink and recover
# the output route after backgrounding / HDMI route changes. No pulse link deps (pure dlopen/dlsym).
add_library(pasink SHARED
        pasink/pasink.c
)

target_link_libraries(pasink
        log
        dl
)
