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
- [ ] **2.1** Drop the `.so` (`usr/lib/`) + manifest (`implicit_layer.d/`) into imagefs (runbook §2).
- [ ] **2.2** Set `BIONIC_FG_ENABLE=1` + `VK_LOADER_DEBUG=all` in one container's Env Vars; write a
      test `conf.toml` at guest `$HOME/.config/bionic-fg/` (runbook §3).
- [ ] **2.3** Launch a real DXVK game; capture logcat to `/sdcard/Download/bionicfg_spike.txt`
      (runbook §4). **[needs device — user runs]**
- [ ] **2.4** Read the decision table (runbook §5): did the layer load? ABI error? swapchain seen?
- [ ] **2.5** Record the real shape; if ABI mismatch → Phase 1.5 glibc build (runbook §7).

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
