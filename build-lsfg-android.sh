#!/usr/bin/env bash
# Build script for lsfg-vk Vulkan layer for Android ARM64
#
# Prerequisites:
#   - Android NDK (set ANDROID_NDK_HOME or pass --ndk)
#   - CMake 3.22+
#   - Ninja (optional, uses Make by default)
#
# Usage:
#   ./build-lsfg-android.sh [--ndk /path/to/ndk] [--clean]

set -euo pipefail

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}"
CLEAN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --ndk) NDK="$2"; shift 2 ;;
        --clean) CLEAN=true; shift ;;
        *) echo "Unknown: $1"; exit 1 ;;
    esac
done

if [[ -z "$NDK" ]]; then
    echo "ERROR: Android NDK not found. Set ANDROID_NDK_HOME or pass --ndk"
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build/lsfg-vk-android"
OUTPUT_DIR="${SCRIPT_DIR}/app/src/main/jniLibs/arm64-v8a"

ABI="arm64-v8a"
API_LEVEL="26"
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
CMAKE="${TOOLCHAIN}/bin/cmake"
NINJA="${TOOLCHAIN}/bin/ninja"

if [[ "$CLEAN" == true ]]; then
    rm -rf "$BUILD_DIR"
    echo "Cleaned build directory"
fi

mkdir -p "$BUILD_DIR"

echo "=== Building lsfg-vk for Android ARM64 ==="
echo "NDK:      $NDK"
echo "ABI:      $ABI"
echo "API:      $API_LEVEL"
echo "Build:    $BUILD_DIR"
echo "Output:   $OUTPUT_DIR"

"$CMAKE" -S "$SCRIPT_DIR" -B "$BUILD_DIR" \
    -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-${API_LEVEL}" \
    -DANDROID_STL="c++_shared" \
    -DCMAKE_BUILD_TYPE=Release \
    -DLSFGVK_BUILD_VK_LAYER=ON \
    -DLSFGVK_BUILD_CLI=OFF \
    -DLSFGVK_BUILD_UI=OFF

"$CMAKE" --build "$BUILD_DIR" --parallel "$(nproc)"

# Copy the resulting .so to jniLibs
mkdir -p "$OUTPUT_DIR"
cp "$BUILD_DIR"/lsfg-vk-layer/libVkLayer_LSFGVK_frame_generation.so "$OUTPUT_DIR/"
echo "=== Done ==="
echo "Library copied to: ${OUTPUT_DIR}/libVkLayer_LSFGVK_frame_generation.so"
ls -lh "$OUTPUT_DIR/libVkLayer_LSFGVK_frame_generation.so"
