# Bionic-FG Frame Generation — Integration Report

**Branch:** `feature/bionic-fg-framegen`
**Date:** 2026-06-19
**Goal:** Integrate [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) (an Android/bionic Vulkan
frame-generation layer) into Bannerlator as **(a)** a per-container option and **(b)** a live in-game
side-menu control.

---

## 1. Permission & author terms

Permission granted by the author (**xXJSONDeruloXx**). His only asks:

1. **Credit in the README.**
2. **If the source goes in the tree, add it as a git submodule** (his stated preference).
3. **Feedback and PRs are welcome.**

All three are easy to honor and are baked into the plan below.

---

## 2. What bionic-fg is

A standalone **Vulkan frame-generation layer for Android / bionic libc, arm64-v8a, API 26+** — the
**LSFG (Lossless Scaling Frame Generation)** lineage, the same engine GameHub 6.0.8 ships as
`libGameScopeVK.so` for its "AI超级插帧" feature.

- Ships as `libbionic_fg.so` + an **implicit Vulkan layer** (`VkLayer_BIONIC_framegen.json` →
  `VK_LAYER_BIONIC_framegen`).
- Intercepts Vulkan swapchain presentation and injects interpolated frames via embedded SPIR-V
  compute shaders (2 models).
- Uses **AHardwareBuffer sharing** between the producer and the framegen device.
- Config via **TOML** (`$HOME/.config/bionic-fg/conf.toml`): `multiplier` (2–4×, 0 = off),
  `flow_scale` (0.2–1.0), `model` (0/1). Legacy `BIONIC_FG_*` env vars also supported.
- Enabled via `BIONIC_FG_ENABLE=1` (+ `VK_LAYER_PATH`, optional `BIONIC_FG_CONFIG`).
- **TOML hot-reloads at runtime** (multiplier / model changes rebuild the framegen context).
- C++ only, CMake/NDK build (`build-android-arm64.sh`). **No license file yet** (permission given
  directly; ask author to add an explicit license before shipping a release).
- Repo: 10 commits, no tagged releases, last pushed 2026-05-15.

---

## 3. Architecture findings (recon)

### How rendering works in Bannerlator
The guest (Wine + box64 + DXVK, **glibc**) does **not** present to a normal Android swapchain. Its
Vulkan goes through a **wrapper ICD**:

- `VK_ICD_FILENAMES = imagefs/…/vulkan/icd.d/wrapper_icd.aarch64.json`
- `GALLIUM_DRIVER = zink`
- `WRAPPER_*` env vars (`XServerDisplayActivity.java:1823–1861`)

The wrapper bridges the guest's Vulkan to the **Android-side bionic GPU driver** loaded via
**adrenotools** (`AdrenotoolsManager.setDriverById` → `ADRENOTOOLS_HOOKS_PATH`,
`AdrenotoolsManager.java:205`). **That bionic driver context is exactly what bionic-fg targets.**

### Existing frame-gen groundwork already in the tree
- `app/src/main/cpp/lsfg-vk/` — a stubbed `CMakeLists.txt` (LSFG lineage), **not** built by the main
  CMake (`add_subdirectory(lsfg-vk)` is commented out; intended to build separately).
- `build-lsfg-android.sh` (repo root) — standalone NDK arm64 build script for the lsfg-vk layer.

So bionic-fg is the **bionic-targeted layer** that fits the path marcescence was already gesturing
toward. Good cross-pollination note for the author.

### Integration touch-points
| Concern | Location |
|---|---|
| Container settings model (`envVars`, `graphicsDriverConfig`) | `container/Container.java` |
| Guest launch env assembly (Vulkan/wrapper block) | `XServerDisplayActivity.java:1823–1861`; `envVars` → `GuestProgramLauncherComponent.setEnvVars` (`:1285`) |
| Container settings UI (graphics section) | `ui/screens/ContainerDetailScreen.kt` |
| In-game side-menu state (StateFlow + Runnable pattern) | `ui/XServerDrawerState.kt` (template: `_nativeRenderingEnabled` / `onNativeRenderingToggle`) |
| In-game side-menu UI | `ui/XServerDrawer.kt`; callbacks wired in `XServerDisplayActivity.java` (~`:360`) |
| Native build | `app/src/main/cpp/CMakeLists.txt` (+ existing `build-lsfg-android.sh` precedent) |
| CI checkout (already `submodules: recursive`) | `main.yml`, `release.yml`, `build-artifacts.yml` |

---

## 4. ⚠️ Critical unknown (de-risk BEFORE building UI)

**Where does the layer actually load, and is there a swapchain to hook?**

### Manifest facts (from the built artifact, run 27854824786)
- `libbionic_fg.so` = **ELF aarch64, Android 26, NDK r26b** (bionic). 1.65 MB artifact.
- Layer is **implicit**: `"enable_environment": { "BIONIC_FG_ENABLE": "1" }` → the Vulkan loader only
  activates it when that env var is set, and only if the manifest is in an **`implicit_layer.d`**
  search dir (NOTE: `VK_LAYER_PATH` is for *explicit* layers; implicit layers come from the system
  dirs or `VK_ADD_IMPLICIT_LAYER_PATH` / `VK_IMPLICIT_LAYER_PATH`).
- `"library_path": "../../../lib/libbionic_fg.so"` → manifest at `…/share/vulkan/implicit_layer.d/`
  expects the `.so` at `…/lib/` (or rewrite to an absolute path).
- Hooks `vkGetInstanceProcAddr` / `vkGetDeviceProcAddr` → inserts **above the ICD** in the chain.

### The real crux (refined)
bionic-fg is a **bionic** `.so` — it **cannot** be `dlopen`'d by the **glibc** guest (box64/Wine in
imagefs). So it must load on the **host (Android/bionic) side**, where Winlator's **wrapper ICD
server** runs the real Turnip driver via adrenotools. Two things must both be true there:
1. The host-side wrapper server creates its `VkInstance`/`VkDevice` through the **Android Vulkan
   loader** (`libvulkan.so`), so an implicit layer can insert itself; **and**
2. there is a real **`VkSwapchainKHR`** to intercept — but Winlator presents via **AHB export**
   (the path we just wired), which may mean **no WSI swapchain exists** → nothing to hook.

GameHub ships its engine (`libGameScopeVK.so`) in the **imagefs**, which suggests its wrapper loads
the frame-gen module from an imagefs path on the side that runs the driver. **Copy that placement.**

### Spike must answer (in order)
1. Does the host-side wrapper/driver context honor an implicit layer at all? (set `BIONIC_FG_ENABLE=1`
   + place the manifest where the host loader scans; watch `VK_LOADER_DEBUG=all` in logcat for
   "insert instance layer VK_LAYER_BIONIC_framegen").
2. If it loads, does it see a swapchain / queue-present to interpolate, or does it sit idle because
   present is AHB-export?
3. Cross-check against GameHub's `libGameScopeVK` placement + env (decompiles in project memory).

**Do not build any UI until the spike answers #1 and #2.**

---

## 5. Recommended integration method

- **Build/bundle:** add bionic-fg to the native build for `arm64-v8a` (or reuse the standalone
  `build-lsfg-android.sh` pattern via a dedicated workflow). Ship `libbionic_fg.so` +
  `VkLayer_BIONIC_framegen.json` where the loader that the wrapper forwards to can find them
  (app native-lib dir **or** imagefs — TBD by the spike).
- **Container enable:** new "Frame Generation" container option. When on, inject `BIONIC_FG_ENABLE=1`
  + `VK_LAYER_PATH` and write the TOML config at launch. Store the preset in `graphicsDriverConfig`
  (already a free-form config field).
- **In-game live control:** the side-menu toggle/presets **rewrite the TOML at runtime** (bionic-fg
  hot-reloads). multiplier 0 = off, 2–4× = on, model 0/1, flow_scale slider — live, no relaunch.
  Mirror GameHub's preset UX.

---

## 6. Job task list

### Phase 0 — Honor author terms (do first, low risk) — ✅ DONE
- [x] **0.1** Add `bionic-fg` as a **git submodule** under `app/src/main/cpp/bionic-fg` → his repo.
      (Pinned at `4f71770`; `.gitmodules` created.)
- [x] **0.2** Verify CI still checks out clean (workflows already use `submodules: recursive`).
- [x] **0.3** **Credit** xXJSONDeruloXx / bionic-fg in the README Credits table + upstream-stack table.
- ⚠️ Submodule still has **no LICENSE** — carry to Phase 5.2 (ask author before any release).

### Phase 1 — Native build — ✅ DONE
- [x] **1.1** Standalone build workflow `build-bionic-fg.yml` (NDK 26.1.10909125 + cmake 3.22.1,
      arm64-v8a / android-26). Run **27854824786 ✅** → artifact `bionic-fg-arm64` (1.65 MB):
      `libbionic_fg.so` (ELF aarch64, Android 26, NDK r26b) + `VkLayer_BIONIC_framegen.json`.
      Workflow added to `main` too (dispatch-only/inert) so it can be triggered against the branch.
- [ ] **1.2** Decide ship location (app native-lib dir vs imagefs) — finalized by Phase 2 (implicit
      layer → needs `implicit_layer.d` on whichever loader the host-side wrapper uses).

### Phase 2 — Verification spike (DE-RISK — gate the rest on this)
- [x] **2.0** **Runbook written** → `BIONIC_FG_SPIKE_RUNBOOK.md` (full device steps, decision table).
- [x] **2.0a** **Device recon done:** guest has its own **glibc Khronos loader**
      (`imagefs/usr/lib/libvulkan.so.1.4.315`) and already loads **glibc** implicit layers
      (MangoHud `VK_LAYER_MANGOHUD_overlay_aarch64`, `libutil_layer`) from
      `usr/share/vulkan/implicit_layer.d/`. bionic-fg's manifest is structurally identical to
      MangoHud's → **discovery will work**; the open question is **load (ABI)**.
      ⚠️ **Strong hypothesis: our NDK/bionic `.so` won't load in the glibc guest loader** (links
      `libandroid`/`liblog`/Android `libvulkan` absent in imagefs) → will need a **glibc aarch64
      build** (Phase 1.5), mirroring MangoHud / GameHub `libGameScopeVK`.
- [x] **2.1** Dropped the `.so` (`usr/lib/`) + manifest (`implicit_layer.d/`) into imagefs.
- [x] **2.2** Set `BIONIC_FG_ENABLE=1` + `VK_LOADER_DEBUG=all` in container Env Vars; wrote a
      test `conf.toml` (mult=2) at guest `$HOME/.config/bionic-fg/`.
- [x] **2.3** Launched Doomblade (DXVK); captured logcat to `/sdcard/Download/bionicfg_spike.txt`
      (7 MB / 57,969 lines; session crashed on launch but capture survived + exited clean).
- [x] **2.4 RESULT (2026-06-19):** Discovery **works** — glibc loader logged
      `Found manifest file …/VkLayer_BIONIC_framegen.json` 12×. But the layer was **SKIPPED 10×**:
      `Layer "VK_LAYER_BIONIC_framegen" doesn't contain required layer object disable_environment …,
      skipping this layer`. **`libbionic_fg.so` was NEVER dlopen'd — zero load attempts, zero
      ELF/ABI errors in the whole log.** → The ABI hypothesis (2.0a) was **never tested**; the loader
      bailed at manifest parse.
- [ ] **2.4a FIX (blocker):** upstream manifest is missing the spec-required `disable_environment`
      key for implicit layers. Add `"disable_environment": {"BIONIC_FG_DISABLE":"1"}` to the manifest
      (staged copy + imagefs copy + at source in `build-bionic-fg.yml`/submodule; propose PR upstream
      per author terms). **Then re-spike** — only that run can answer load(ABI)/swapchain.
- [x] **2.4b RE-SPIKE (2026-06-19, after manifest fix) — DE-RISK GATE PASSED:**
  - ✅ Manifest fix worked: layer enabled via `BIONIC_FG_ENABLE`, `disable_environment` recognized.
  - ✅ **Our NDK/bionic `libbionic_fg.so` LOADS in the glibc guest loader** (instance + device layer inserted), **zero ABI errors → NO Phase 1.5 glibc build needed.** (`libVkLayer_LSFGVK_*` lib-arm64 failure = old lsfg-vk stub, missing `libc++_shared.so`, unrelated/non-fatal.)
  - ✅ Layer inits fully: device created, all embedded SPIR-V loaded, `FramegenContext ready 1280x720 mult=2 model=0`.
  - ✅ **Real `VkSwapchainKHR` exists & is hooked** (the headline unknown): `SwapchainState ready 1280x720 mult=2 provisionedOutputs=3`. Frame-gen briefly ran (FPS 65→143 = mult=2 doubling) before crashing.
  - 🔴 **Crash cause:** bionic-fg gates external-memory/AHB setup on the **extension strings** `VK_KHR_external_memory_capabilities` + `VK_KHR_get_physical_device_properties2`, which are **core since Vulkan 1.1** (instance is 1.3) → no string → check fails → AHB import misconfigured → crash on first interpolated present. Wrapper/Turnip side has AHB fine (`VK_ANDROID_external_memory_android_hardware_buffer` + DRI3 AHB pixmaps).
  - Logs: `/sdcard/Download/bionicfg_spike_run1_manifestskip.txt` (manifest-skip) + `bionicfg_spike_run2_loaded_crash.txt` (load+crash).
- [ ] **1.5 — NOT NEEDED** (ABI confirmed fine; struck from plan).
- [x] **2.4c REAL CRASH CAUSE FOUND (it's a HANG, not a segfault):** process went silent right after
      `SwapchainState ready` + `DXVK: Using 8 compiler threads`, froze ~36s, then `reacting to signal 3`
      (SIGQUIT/ANR trace dump) → killed. No SIGSEGV/VkError. The present hook `BionicFG_QueuePresentKHR`
      (`src/vk_layer/layer.cpp`) submits a copy cmd-buf waiting on the **application's own present
      semaphores** (DXVK's) then does `vkWaitForFences(..., UINT64_MAX)` on its fence. On the
      wrapper_icd→Turnip bridge that fence never signals → infinite wait → hang. (The
      `Device ext not available` warnings are cosmetic — those instance exts were filtered out before
      `vkCreateDevice`, which succeeded.)
- [x] **2.5 PATCH WRITTEN** → `patches/bionic-fg-bannerlator-fixes.patch` (submodule stays pinned;
      CI `build-bionic-fg.yml` applies it pre-build; manifest `jq` hack removed). This patch == the
      upstream PR we owe xXJSONDeruloXx. Contents:
      (a) **present-path fence waits `UINT64_MAX`→250ms** at all 3 cross-context sites (pre-copy, post-copy,
          per-generated-frame) + new `SwapState::fenceTimeouts` counter + `noteFenceTimeout()`: on timeout,
          degrade to a real-frame present; after 2 timeouts disable framegen for that swapchain (full speed,
          no ANR). Distinct log lines per site → next spike pinpoints which wait stalls.
      (b) **manifest** `disable_environment: BIONIC_FG_DISABLE` (Phase 2.4a fix, folded in).
      (c) **vk_impl.cpp** remove the 2 instance exts wrongly listed in `kRequiredDeviceExts` (cosmetic).
- [ ] **2.6 NEXT:** run `build-bionic-fg.yml` (workflow_dispatch) → new `libbionic_fg.so` → re-stage to
      device → re-spike. EXPECTED: no hang. Either (i) sustained frame-gen (success!), or (ii) graceful
      log `copy fence (post-submit) wait timed out` → `disabling framegen … real frames only` (game runs,
      and we've localized the exact deadlock for a deeper present-path fix). Then Phase 3 (container UI).
- [ ] **2.7 FOLLOW-UP:** `FramegenContext::present` (`framegen_context.cpp:829`) + `fgCtx->waitIdle()`
      (`layer.cpp` step 2) also use infinite waits but on bionic-fg's OWN compute device (lower hang risk);
      bound them too if (ii) shows the stall is compute-side, not the cross-context copy.

### Phase 3 — Container setting (only if Phase 2 passes)
- [ ] **3.1** Add Frame-Gen config to `Container.java` (enable flag + multiplier/model/flow_scale,
      stored in `graphicsDriverConfig`).
- [ ] **3.2** Inject env + write TOML at launch in `XServerDisplayActivity` Vulkan block.
- [ ] **3.3** Add the UI control to `ContainerDetailScreen.kt` graphics section.

### Phase 4 — In-game side-menu
- [ ] **4.1** Add `_frameGenEnabled` StateFlow + `onFrameGenToggle` (+ preset callbacks) to
      `XServerDrawerState.kt`, mirroring the Native Rendering toggle.
- [ ] **4.2** Render the toggle + multiplier/model/flow_scale controls in `XServerDrawer.kt`.
- [ ] **4.3** Wire callbacks in `XServerDisplayActivity.java` to **rewrite the TOML live** (hot-reload).
- [ ] **4.4** Gray out / hide on the OpenGL renderer if frame-gen is Vulkan-only (match the existing
      effect-gating pattern).

### Phase 5 — Polish, release, give back
- [ ] **5.1** Device-confirm both surfaces on a real game.
- [ ] **5.2** Ask author to add an explicit **license** before bundling in a tagged release.
- [ ] **5.3** Send **feedback / PRs** upstream (the wrapper-ICD / AHB-present finding; any fixes).
- [ ] **5.4** Update README Full Features + cut a release with Credits.

---

## 7. Credits & giving back
- README credit to **xXJSONDeruloXx** (bionic-fg) is Phase 0.3, locked in before any wiring.
- Upstream feedback to send: Bannerlator's wrapper-ICD/zink + AHB-export present model and whether it
  exposes a `VkSwapchainKHR`; the existing `lsfg-vk` groundwork in marcescence; any integration
  patches as PRs.
