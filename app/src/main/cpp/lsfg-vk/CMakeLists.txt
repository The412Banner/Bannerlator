# Android NDK CMake build for lsfg-vk Vulkan layer
# Downloads lsfg-vk source from PancakeTAS/lsfg-vk via FetchContent
# and builds the Vulkan implicit layer as a shared library.
#
# Prerequisites:
#   - Android NDK 25+ (r26 recommended)
#   - CMake 3.22+
#   - Vulkan headers (from NDK or Vulkan SDK)
#
# The resulting libVkLayer_LSFGVK_frame_generation.so is automatically
# packaged into jniLibs/arm64-v8a/ by the AGP build system.

cmake_minimum_required(VERSION 3.22)

# ---- Fetch lsfg-vk source ----
include(FetchContent)
FetchContent_Declare(
    lsfg-vk
    GIT_REPOSITORY  https://github.com/PancakeTAS/lsfg-vk.git
    GIT_TAG         v2.0.0-dev
    GIT_SHALLOW     TRUE
)

set(FETCHCONTENT_QUIET OFF)
FetchContent_MakeAvailable(lsfg-vk)

# ---- Android-specific adjustments ----
# The lsfg-vk project uses C++20 and targets desktop Vulkan.
# For Android we need to:
# 1. Override the layer library name to match NDK naming
# 2. Set proper Vulkan include paths for Android
# 3. Disable CLI and UI targets (not needed on Android)

# Point to NDK's Vulkan headers
find_path(VulkanHeaders_INCLUDE_DIR
    NAMES vulkan/vulkan.h
    PATHS "${ANDROID_NDK}/sources/third_party/vulkan/src/include"
          "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/include"
    NO_DEFAULT_PATH
)

if(VulkanHeaders_INCLUDE_DIR)
    target_include_directories(lsfg-vk-common SYSTEM BEFORE
        PRIVATE "${VulkanHeaders_INCLUDE_DIR}")
    target_include_directories(lsfg-vk-backend SYSTEM BEFORE
        PRIVATE "${VulkanHeaders_INCLUDE_DIR}")
    target_include_directories(lsfg-vk-layer SYSTEM BEFORE
        PRIVATE "${VulkanHeaders_INCLUDE_DIR}")
    message(STATUS "LSFG: Using Vulkan headers from NDK: ${VulkanHeaders_INCLUDE_DIR}")
else()
    message(WARNING "LSFG: Vulkan headers not found in NDK; build may fail")
endif()

# Override the layer library name for Android
set_target_properties(lsfg-vk-layer PROPERTIES
    OUTPUT_NAME "VkLayer_LSFGVK_frame_generation"
    PREFIX "lib"
    SUFFIX ".so"
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)

# Add compile definitions for Android
target_compile_definitions(lsfg-vk-common PRIVATE
    LSFGVK_ANDROID=1
)
target_compile_definitions(lsfg-vk-backend PRIVATE
    LSFGVK_ANDROID=1
)
target_compile_definitions(lsfg-vk-layer PRIVATE
    LSFGVK_ANDROID=1
)

# Remove filesystem dependency (use simpler path handling on Android)
target_compile_definitions(lsfg-vk-common PRIVATE
    _GLIBCXX_USE_CXX11_ABI=1
)

message(STATUS "LSFG: Build configured for Android ARM64")
