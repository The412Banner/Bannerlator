# Building lsfg-vk for Android (ARM64)

This directory contains the integration of [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)
— an open-source Lossless Scaling Frame Generation Vulkan layer — into star-is.

## Prerequisites

- Android NDK r26+ (set `ANDROID_NDK_HOME`)
- CMake 3.22+
- A Linux host (or WSL2 on Windows)
- Git

## Quick Build

```bash
# From the repo root:
./build-lsfg-android.sh
```

This downloads the lsfg-vk source from GitHub via FetchContent, cross-compiles
it for `arm64-v8a`, and copies `libVkLayer_LSFGVK_frame_generation.so` into
`app/src/main/jniLibs/arm64-v8a/`.

## How It Works

1. **Build time:** The lsfg-vk source is fetched from GitHub by CMake's
   FetchContent and compiled using the Android NDK. The resulting `.so` is
   a Vulkan implicit layer that hooks `vkQueuePresentKHR` to generate
   interpolated frames.

2. **APK packaging:** The `.so` in `jniLibs/arm64-v8a/` is auto-packaged by
   the Android Gradle Plugin. The layer manifest JSON is in
   `app/src/main/assets/lsfg/`.

3. **First boot:** When a container with LSFG enabled starts,
   `LsfgManager.ensureLayerInstalled()` copies the `.so` and manifest JSON
   to `<container-root>/usr/share/vulkan/implicit_layer.d/lsfg/`.

4. **Runtime:** `GuestProgramLauncherComponent` sets `VK_LAYER_PATH` to
   include this directory, and the Vulkan loader discovers the layer.
   Configuration is passed via environment variables (`LSFGVK_ENV=1`).

## Mali & Adreno Optimizations

The default build uses the unmodified lsfg-vk compute shaders. For optimal
performance on ARM GPUs:

### Mali (Valhall architecture)
- Enable `allow_fp16` — Mali G710+ supports fp16 heavily accelerated
- Reduce flow scale to 0.5-0.75 for better performance
- Use `performance_mode=1` for lighter model

### Adreno (Hexagon)
- Adreno 730+ supports fp16
- Larger wavefronts benefit from higher flow scale
- Avoid subgroup operations not supported on Adreno (some optional features)

### Shader modifications needed (advanced)
For best results, the compute shaders in `lsfg-vk-backend/src/shaderchains/`
should be modified:
- Reduce workgroup size to 64 (from 256) for Mali compatibility
- Add `coherent` qualifiers for Mali's tile-based memory model
- Avoid 64-bit operations (not supported on all ARM GPUs)
- Use `OpCapability Float16` where available

## Configuration

LSFG settings are per-container:
| Field | Env Var | Description |
|-------|---------|-------------|
| Multiplier | `LSFGVK_MULTIPLIER` | 2, 3, or 4 |
| Quality | `LSFGVK_PERFORMANCE_MODE` | 0=quality, 1=performance |
| Flow Scale | `LSFGVK_FLOW_SCALE` | 0.5 to 2.0 |
| GPU Arch | `LSFGVK_GPU` | auto, Mali, Adreno |
