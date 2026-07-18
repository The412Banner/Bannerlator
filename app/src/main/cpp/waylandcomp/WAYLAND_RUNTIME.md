# Bannerlator Wayland runtime (feat/wayland-runtime)

Experimental **parallel** display runtime: run/launch games through Wine's
`winewayland.drv` talking to our own embedded Wayland compositor, instead of the
X11 path (pure-Java X11 server + `libwinlator.so`). The X11 runtime stays the
default and is untouched — this is a separate flavor/branch.

## What's already proven (spike repo `bannerlator-wayland`, device-tested on Adreno 750)
- Minimal libwayland-server compositor: globals + xdg-shell handshake + buffer commit.
- **Turnip's Vulkan WSI exports real zero-copy dmabufs to our external compositor**
  (same Mesa path winewayland.drv uses for DXVK/VKD3D) — risk #1 retired.
- Compositor imports that dmabuf into its own Turnip VkImage (`vk_import.c`).
The staged `src/` here is that proven code, to grow into the app-embedded compositor.

## Dependencies
1. **A Proton 11 arm64ec wcp that ships `winewayland.drv`** — built on branch
   `The412Banner/proton-wine:feat/winewayland` (task #1). Nothing runs end-to-end
   without it.
2. **Wayland runtime libs in the imagefs** so `winewayland.so` (unixlib) loads:
   `libwayland-client.so`, `libwayland-egl.so`, `libxkbcommon.so`, `libxkbregistry.so`
   (bionic aarch64). The wcp bundles them in its `lib/` as a fallback; the clean home
   is the imagefs — add via `ImageFsInstaller` (new `installWaylandLibs()`), same
   pattern as `installFFmpeg8()`.

## Integration plan (M4)
- **CMake**: add `waylandcomp` as a native lib (`libbannerwayland.so`) built with the
  NDK, linking the bionic `libwayland-server`/`libvulkan`. Generate protocol glue from
  `protocols/*.xml` at build time (host `wayland-scanner`).
- **Surface**: a `WaylandDisplayActivity` (parallel to `XServerDisplayActivity`) hosts a
  `SurfaceView`; JNI hands the `ANativeWindow` to the compositor, which creates a Vulkan
  swapchain on it and blits the imported game VkImage each frame (the last un-proven
  render step; standard Vulkan once the window exists).
- **Input**: Android `MotionEvent`/`KeyEvent` → `wl_seat`/`wl_pointer`/`wl_keyboard`.
- **Launch wiring**: start the compositor, export `WAYLAND_DISPLAY`, select the wayland
  driver per-prefix (registry `Drivers\Graphics = winewayland`) instead of `winex11`,
  and point the container at the winewayland wcp.

## Status
- ✅ **Native lib foundation done + compile-verified.** Compositor + vk_import + pre-generated
  protocol glue build as `libbannerwayland.so` (CMake target added), linking the vendored
  bionic `libwayland-server`. Verified: compiles/links clean as an aarch64 bionic `.so`,
  exports `banner_wayland_run`, NEEDED = libwayland-server + libvulkan. JNI entry
  (`waylandcomp_jni.c`) + `WaylandCompositor.java` bring it up on a thread. Compositor-process
  runtime deps (libwayland-server/libffi/libandroid-support) staged in `jniLibs/arm64-v8a`.
- ⏭️ **Next phase (gated on the winewayland wcp landing green):** `WaylandDisplayActivity`
  (SurfaceView) + JNI `ANativeWindow`→Vulkan swapchain + blit the imported game VkImage to the
  window (last un-proven render step) + input + launch wiring (start compositor, `WAYLAND_DISPLAY`,
  per-prefix `Drivers\Graphics=winewayland`) + `ImageFsInstaller.installWaylandLibs()` (client/egl/xkb
  into the imagefs for winewayland.so).
