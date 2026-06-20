# Bionic-FG — Phase 2 Verification Spike Runbook

**Branch:** `feature/bionic-fg-framegen`
**Goal:** Determine empirically whether bionic-fg can be loaded as a frame-gen layer in Bannerlator's
guest Vulkan path, and whether there is a swapchain/present for it to interpolate — **before** any UI
work. This is the de-risk gate for Phases 3–5.

> ⚠️ Device-launch safety (per the standing rule): **save memory + progress log first**, and capture
> logcat to a **crash-surviving file** under `/sdcard/Download/`. Launching a game can crash the
> session; the log must survive.

---

## 0. Architecture recap (what the spike is really testing)

The guest (Wine + box64 + DXVK) uses its **own glibc Khronos Vulkan loader** inside the imagefs:

- `…/imagefs/usr/lib/libvulkan.so.1.4.315` (glibc Khronos loader)
- ICD: `…/usr/share/vulkan/icd.d/wrapper_icd.aarch64.json` → forwards to the host Android **bionic**
  Turnip driver (loaded via adrenotools/linkernsbypass).
- Implicit layers it already loads from `…/usr/share/vulkan/implicit_layer.d/`:
  **MangoHud** (`VK_LAYER_MANGOHUD_overlay_aarch64`) + `libutil_layer` — **glibc** builds.

bionic-fg's manifest is **structurally identical** to MangoHud's (`enable_environment`,
`library_path: ../../../lib/…`, same proc-addr hooks), so **discovery will work**. The open question
is **load (ABI)** and **whether there's a swapchain to hook**.

### The central hypothesis
bionic-fg's `.so` is an **NDK/bionic** build linking `libandroid` / `liblog` / Android `libvulkan`.
The guest loader is **glibc** and those Android libs don't exist in the imagefs. **Expectation: the
glibc loader discovers the layer, tries to `dlopen` it, and fails with a missing-library / ABI
error.** That result is a *success for the spike* — it tells us the real integration needs a **glibc
aarch64 build** of the layer (exactly how MangoHud and GameHub's `libGameScopeVK` ship in imagefs).

---

## 1. Pre-flight (do every time, before launch)

1. Confirm package: standard flavor = **`com.winlator.banner`** (pubg = `com.tencent.ig`). Paths below
   assume standard; swap the package if testing pubg.
2. `IMG=/data/data/com.winlator.banner/files/imagefs`
3. Save state: update `PROGRESS_LOG.md` + memory with "spike attempt N starting" so a crash leaves a
   breadcrumb.
4. Artifact is already staged at `/sdcard/Download/bionic-fg/` (`libbionic_fg.so` +
   `VkLayer_BIONIC_framegen.json`).

---

## 2. Install the layer into the guest (Test A — bionic build as-is)

Run via the root bridge (`getlog --exec '<single-line>'`, uid-0). Place the `.so` in `usr/lib` and the
manifest in `implicit_layer.d` (matching the `../../../lib/libbionic_fg.so` relative path):

```
getlog --exec 'IMG=/data/data/com.winlator.banner/files/imagefs; cp /sdcard/Download/bionic-fg/libbionic_fg.so $IMG/usr/lib/libbionic_fg.so && cp /sdcard/Download/bionic-fg/VkLayer_BIONIC_framegen.json $IMG/usr/share/vulkan/implicit_layer.d/VkLayer_BIONIC_framegen.json && chmod 755 $IMG/usr/lib/libbionic_fg.so && ls -la $IMG/usr/lib/libbionic_fg.so $IMG/usr/share/vulkan/implicit_layer.d/VkLayer_BIONIC_framegen.json'
```

Write a minimal frame-gen config (TOML at the guest `$HOME` = `/home/xuser`):

```
getlog --exec 'IMG=/data/data/com.winlator.banner/files/imagefs; mkdir -p $IMG/home/xuser/.config/bionic-fg && printf "multiplier = 2\nflow_scale = 1.0\nmodel = 0\n" > $IMG/home/xuser/.config/bionic-fg/conf.toml && cat $IMG/home/xuser/.config/bionic-fg/conf.toml'
```

---

## 3. Enable it for one container (env vars — no rebuild)

In the app: **Container ▸ (edit) ▸ Environment Variables**, append (space-separated):

```
BIONIC_FG_ENABLE=1 VK_LOADER_DEBUG=all
```

- `BIONIC_FG_ENABLE=1` — the layer's `enable_environment` trigger (loader only activates it when set).
- `VK_LOADER_DEBUG=all` — makes the glibc loader log every discovery/load step (the signal we need).
- (Optional fallback if TOML isn't picked up: also add `BIONIC_FG_MULTIPLIER=2`.)
- Leave `MANGOHUD` unset so HUD layers don't muddy the log.

---

## 4. Launch + capture logcat to a crash-surviving file

Start the capture **before** launching the game, redirected to `/sdcard/Download/` so it survives a
crash:

```
getlog --exec 'logcat -c; (logcat -v time > /sdcard/Download/bionicfg_spike.txt 2>&1 &) ; echo capturing'
```

Then launch the container with a **DXVK** game/app from the app UI. Let it run ~30–60s, then stop the
capture:

```
getlog --exec 'pkill -f "logcat -v time"; wc -l /sdcard/Download/bionicfg_spike.txt'
```

(If loader output goes to the guest stderr rather than logcat, also grab the in-app log: enable the
drawer **Show Logs** toggle, or check `…/imagefs/tmp` / the Wine log path.)

---

## 5. Read the result — decision signals

Grep the capture:

```
getlog --exec 'grep -iE "BIONIC|framegen|VK_LAYER_BIONIC|implicit layer|Insert instance layer|failed to load|cannot open shared object|libandroid|liblog" /sdcard/Download/bionicfg_spike.txt | head -80'
```

| Signal in log | Meaning | Next step |
|---|---|---|
| `Insert instance layer VK_LAYER_BIONIC_framegen` + bionic-fg init lines, **no load error** | Layer **loaded** in the guest glibc loader (surprising — would mean ABI is compatible) | Go to §6 (swapchain check) |
| `Found manifest … VkLayer_BIONIC_framegen.json` then `failed to load` / `cannot open shared object` / `libandroid.so` / `liblog.so` not found | **Discovered but ABI/dep mismatch** (expected) | **Confirmed: need a glibc aarch64 build** → Phase 1.5 below |
| No `BIONIC` / `framegen` lines at all | Loader never saw the manifest (path/enable wrong) | Re-check manifest path + `BIONIC_FG_ENABLE=1`; confirm a swapchain-creating app was used |
| Game renders, HUD multiplier visible, FPS ~doubles | **Frame gen actually working** | Jackpot → proceed to Phases 3–4 |

---

## 6. If it loads — confirm there's something to interpolate

bionic-fg intercepts swapchain present. Winlator's wrapper may present via **AHB export with no WSI
swapchain**. Check the log for:

- `vkCreateSwapchainKHR` / `vkQueuePresentKHR` reaching the layer, **or**
- bionic-fg complaining it sees no swapchain / no frames.

If the layer loads but **never sees a swapchain**, frame-gen has nothing to hook → we need to feed it
the AHB present path (the one we wired in 1.2) or hook `vkQueueSubmit`/AHB export instead. Document the
exact symptom.

---

## 7. Likely outcome → Phase 1.5 (glibc build)

If Test A confirms the ABI mismatch (most likely), the real integration mirrors MangoHud /
`libGameScopeVK`: a **glibc aarch64** build of the frame-gen layer placed in imagefs
`implicit_layer.d`. Action:

1. Add a glibc-aarch64 build target for bionic-fg (cross-toolchain, drop/stub the NDK
   `libandroid`/`liblog` deps; keep `libvulkan` + the SPIR-V shaders). **Coordinate with the author —
   this is exactly the feedback/PR he asked for.**
2. Re-run §2–§5 with the glibc `.so`.
3. Only after it loads + sees a swapchain → build the container setting (Phase 3) and in-game menu
   (Phase 4).

---

## 8. Cross-reference

Pull GameHub's `libGameScopeVK` placement + enable mechanism from the decompiles
(`reference_gamehub_608_ai_frame_interpolation_engine` in project memory) to confirm the imagefs
layout and any extra control file (GameHub uses a `gamescope.control` mmap). Reuse its proven shape
rather than rediscovering it.

---

## Cleanup (to revert the spike)

```
getlog --exec 'IMG=/data/data/com.winlator.banner/files/imagefs; rm -f $IMG/usr/lib/libbionic_fg.so $IMG/usr/share/vulkan/implicit_layer.d/VkLayer_BIONIC_framegen.json; rm -rf $IMG/home/xuser/.config/bionic-fg; echo reverted'
```

…and remove `BIONIC_FG_ENABLE` / `VK_LOADER_DEBUG` from the container's Environment Variables.
