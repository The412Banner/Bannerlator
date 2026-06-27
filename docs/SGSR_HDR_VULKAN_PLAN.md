# SGSR Upscaling + True HDR on the Vulkan Compositor — Working Log & Plan

**Status:** RESEARCH COMPLETE — awaiting go/no-go on options. No code written yet.
**Date opened:** 2026-06-26
**Scope:** The native Vulkan host compositor only (`libwinlator`/`VulkanRendererContext.cpp`). Explicitly NOT the GL renderer or the ASR/SurfaceFlinger scanout path (see Caveats).

This file is the source-of-truth log for these two features: where each piece comes from, its license,
how it would be added, and the open decisions. Update it as work proceeds.

---

## 0. Why this exists / current state of the code

Today on the **Vulkan renderer**, both "SGSR" and "HDR" toggles in the in-game drawer are **GL-only and grayed out**:
- The drawer flag `XServerDialogState.setEffectsSupported(renderer instanceof GLRenderer)` (`XServerDisplayActivity.java:1778`)
  disables them, showing *"SGSR / HDR require the OpenGL renderer"* (`XServerDrawer.kt:447`).
- The existing "SGSR" effect (`renderer/effects/FSREffect.java`) is **misnamed**: it is AMD **CAS sharpening** (a GLSL ES
  shader), same-resolution — it does **not** upscale.
- The existing "HDR" effect (`renderer/effects/HDREffect.java`) is a **fake bloom+contrast** LDR shader, clamped to 0–1.
- The native Vulkan compositor is **hardcoded to 8-bit sRGB SDR**:
  `VK_FORMAT_R8G8B8A8_UNORM` (`VulkanRendererContext.cpp:250`) + `VK_COLOR_SPACE_SRGB_NONLINEAR_KHR` (`:278`).

So: nothing real exists on the Vulkan path. This plan adds genuine upscaling and (optionally) real HDR there.

---

## 1. FEATURE A — SGSR / FSR1 spatial upscaling (the strong recommendation)

### 1.1 Where it comes from (sources + licenses)

| Option | Repo | License | Shape | Mobile cost | Notes |
|---|---|---|---|---|---|
| **SGSR 1.0** (lead) | `github.com/SnapdragonGameStudios/snapdragon-gsr` (`sgsr/v1/`) | **BSD-3-Clause** | **Single** fragment pass, color-only | ~0.3–0.5 ms @ Adreno (≈2× bilinear) | GLSL provided; Adreno-tuned; Vulkan via `UseUniformBlock` define |
| **FSR 1.0** (2nd option) | `github.com/GPUOpen-Effects/FidelityFX-FSR` (`ffx_fsr1.h`) | **MIT** | **Two** passes: EASU upscale + RCAS sharpen | ~1.8 ms EASU (mobile-tuned) | Industry standard; fp16/fp32; portable HLSL/GLSL macro header |
| NIS | `github.com/NVIDIAGameWorks/NVIDIAImageScaling` v1.0.3 | MIT | Single pass | cheap | weaker upscale, no mobile track record — **skip** |
| vkBASalt | `github.com/DadSchoorse/vkBasalt` | Zlib | Vulkan **layer** (inside game) | — | CAS/FXAA/SMAA only, **no upscaling** — wrong layer — **skip** |

SGSR 2.0 is **temporal** (needs motion vectors) → **not usable** in a color-only compositor. Skip it.

### 1.2 Reference implementation already in the wild
- **GameNative v0.9.1 ships FSR 1.0 in exactly this compositor present-time slot** (scaling mode: None/Linear/Nearest/Fill/Stretch/**FSR 1.0**, EASU+RCAS, render-res→display-res). Best blueprint for the integration shape. **GameNative is GPL-3.0 — crib the approach, do NOT copy code verbatim** (we are not GPL).
- We already have an **SGSR GLSL shader in-tree on the GL path** — porting that shader math to a Vulkan fragment pass is largely a move, not a green-field write.

### 1.3 How we'd add it (integration points, all in `app/src/main/cpp/winlator/`)
The compositor is a **full-screen-quad fragment sampler** (no blit, no compute, no intermediate target today):
- Draw loop: `VulkanRendererContext.cpp:758-770` (bind pipeline + per-window descriptor set, push NDC rect, `CmdDraw(4)`).
- Game image format `VK_FORMAT_B8G8R8A8_UNORM`; **source res = game window content** (`wt.w/wt.h`), **output res = `swapchainExt`**.
- **The low-res→native plumbing ALREADY EXISTS**: container `screenSize` (default `1280x720`, `Container.java:31`) is the inner
  render res; the Android surface is the outer res; `ViewTransformation` scales between them. So if `screenSize` < display,
  the game already renders low-res and the compositor upscales (via the sampler) today. That is the exact precondition FSR/SGSR needs — **no new render-scale concept required.**

Add the pass:
1. Render the game window(s) into an **offscreen color target at render res** (new render pass + image + framebuffer).
   The existing `:758-770` loop becomes the "render to offscreen" stage.
2. Add a **second pipeline** with the SGSR (or EASU then RCAS) fragment shader sampling that target → swapchain at `swapchainExt`.
   - SGSR1 = one pass, existing single-binding descriptor layout suffices.
   - FSR1 = two passes (EASU→intermediate→RCAS→swapchain) + EASU/RCAS constants (extend `WindowPushConstants` or add a UBO).
3. Recreate offscreen+pipeline in the `fbResized` branch (`:845-853`) since it's output-res-dependent.
4. Only engage when render res < display res; else pass through. (Can still offer RCAS/CAS as same-res sharpen.)

**Shaders:** there is **no glsl→spv toolchain** in the build — shaders are hand-compiled SPIR-V committed as C arrays
(`window_vert.h`/`window_frag.h`, via `makeShader()` `:331`). Adding a pass = compile `.spv` offline → new `*_code[]` header,
**or** add a `glslc`/`glslangValidator` custom step to `CMakeLists.txt:61` (net-new build infra, optional).

**Kotlin→native config:** copy the filter-mode pattern — `VulkanRenderer.java:681` `setFilterMode` → `nativeSetFilterMode`
(`vulkan_jni.cpp:234`) → `VulkanRendererContext::setFilterMode` (`:1195`, does `DeviceWaitIdle`+rebuild). A new
`setUpscaler(off/sgsr/fsr)` follows the same path, stored as a per-container `KeyValueSet` key (like `fpsCounterConfig`).

### 1.4 Verdict
**Genuinely worth doing.** SGSR1 first (single pass, cheapest on Adreno, BSD-3, shader likely already in-tree),
with FSR1 (MIT, GameNative blueprint) as the second selectable mode. Expose as a per-container **Scaling mode**
dropdown: `None / Linear / Nearest / SGSR / FSR1`.

---

## 2. FEATURE B — True HDR on the Vulkan compositor (the hard one)

### 2.1 Two very different meanings
- **(A) True HDR10 passthrough** — a game renders native HDR10 (DXGI HDR swapchain); we carry the signal to the panel.
- **(B) SDR→HDR inverse tone mapping** — take the SDR game image, expand to a PQ/HDR10 swapchain with an inverse-tonemap curve (enhancement, not "real" HDR).

### 2.2 Sources + facts

**DXVK side (passthrough) — works, NOT the blocker:**
- `dxgi.enableHDR = True` (env `DXVK_HDR=1`), since **DXVK 2.1** (2023-01-24). Uses `VK_EXT_swapchain_colorspace` +
  `VK_EXT_hdr_metadata`, requests `VK_COLOR_SPACE_HDR10_ST2084_EXT`. Only makes DXVK *report* HDR; real output needs the host
  Vulkan surface to actually present that color space. ~a few hundred Windows games support native HDR.

**The Android blocker (this is what kills passthrough):**
- WSI only guarantees `VK_COLOR_SPACE_SRGB_NONLINEAR_KHR`. Many real devices return **sRGB-only surface formats even on HDR
  panels**. ARM's own engineer: the limiter is the **display controller + OEM WSI integration**, not the GPU driver.
- Panel engagement is owned by **SurfaceFlinger + HWC**, not the app. `vkSetHdrMetadataEXT` is advisory/non-binding.
- Three-level reality: extension string = common; **HDR10 surface color space actually offered = OEM-specific minority**
  (some Pixel/Samsung); panel actually goes HDR = framework-decided.

**Android platform path (for our own renderer):**
- `Window.setColorMode(COLOR_MODE_HDR)` (API 34) is the **wrong knob** — docs say it does NOT affect SurfaceView/SurfaceControl.
- Correct: produce buffers in an HDR dataspace (`ANativeWindow_setBuffersDataSpace` BT2020_PQ, API 28; or Vulkan
  `VK_EXT_swapchain_colorspace`), request headroom via `SurfaceControl.Transaction.setExtendedRangeBrightness` (API 34) /
  `setDesiredHdrHeadroom` (API 35). Probe `Display.getHdrCapabilities()`. Panel must be physically HDR-capable.

**SDR→HDR inverse tonemap (option B) building blocks:**
- **libplacebo** — `github.com/haasn/libplacebo`, **LGPLv2.1+** (bundle-friendly), Vulkan-native, has `inverse_tone_mapping`
  (BT.2446a/spline) + PQ/HDR10 output. Best candidate for the curve+colorspace math.
- Lilium ReShade HDR shaders — GPL-3.0 (copyleft constraint); curve math portable, pipeline not.
- RenoDX — MIT but **per-game ReShade add-on**, inherits the same Android HDR-surface blocker — **skip**.

### 2.3 How we'd add it (integration points)
- Swapchain: `createSwapchain()` `VulkanRendererContext.cpp:244-302`; format/colorspace hardcoded at `:250/:278`; surface formats
  are queried at `:248` but **ignored**. Negotiate `VK_FORMAT_A2B10G10R10_UNORM_PACK32` + `HDR10_ST2084` here.
- **Must enable instance ext `VK_EXT_swapchain_colorspace`** (`createInstance` `:173`, currently only surface+android_surface) —
  this is the gate for any non-sRGB color space. Optionally device ext `VK_EXT_hdr_metadata` (`:224`) + load `vkSetHdrMetadataEXT`
  into `VkTable` (`.h`).
- Output is plain UNORM passthrough today (`window.frag:15`), so an HDR10/PQ swapchain needs a **PQ encode added to the frag shader**
  (or a dedicated output pass). For option B, that same shader does the inverse-tonemap + PQ encode.
- HDR needs a **swapchain rebuild** (format baked at creation) — reuse the `fbResized` recreate path (`:845-853`).
- Image views `:292`, render pass attachment `:305`, framebuffers `:371` all must follow the chosen format.

### 2.4 Verdict
- **True HDR10 passthrough: NOT realistically bundleable in 2026.** Works on a handful of Pixel/flagship devices, silently does
  nothing (or breaks) elsewhere because the Android WSI won't offer an HDR10 surface. **No Android Winlator-style emulator does
  this today** (GameNative/GameHub/Ludashi "HDR" are all SDR bloom filters, by their authors' own admission).
- **SDR→HDR inverse tonemap (option B): the only realistic "real HDR" scope**, and only as a **runtime-gated experimental toggle**
  (like the ASR renderer) that probes for an HDR10/extended-range surface + HDR panel and **cleanly falls back to SDR** otherwise.
  Use libplacebo (LGPL) math or a hand-written PQ inverse-tonemap shader. Do **not** market it as true HDR.

---

## 3. Cross-cutting caveats (must be in any plan)
- **Three host renderers.** This compositor draw pass is **bypassed in ASR/Native-Rendering+ mode** (`renderFrame()` early-returns
  at `:829-839` when `scanoutActive`). FSR/SGSR added here will **NOT** apply in ASR mode; the GL renderer is separate too.
  Scope = Vulkan compositor; state the ASR limitation explicitly.
- HDR on ASR would be a SurfaceControl/HardwareBuffer dataspace problem (different work, `VulkanRenderer.java:258-278`).
- `apiVersion = VK_API_VERSION_1_3` (`:172`). Confirm Turnip/Adreno support for the 10-bit format + ST2084 on-device
  (device-provable, not CI-provable).
- No shader build toolchain exists — decide: offline-compiled `.spv` C-array headers (matches current pattern) vs adding glslc to CMake.

---

## 4. DECISIONS (locked 2026-06-26)
1. Upscaler: **BOTH SGSR1 + FSR1** as a Scaling-mode dropdown, **plus FSR-Fit** (aspect-preserving, from GameNative).
2. HDR: **DEFERRED entirely** (passthrough is DOA on Android; inverse-tonemap is weak payoff). Revisit later.
3. Shader build: **offline-compiled `.spv` C-array headers** (matches existing `window_*_h` pattern; zero CI risk).
   Local compiler available: `glslangValidator` (Termux, `/data/data/com.termux/files/usr/bin/glslangValidator`).
4. UI home: **in-game drawer** for the live upscaler toggle (mirrors FPS/HUD controls).
5. GL path: **also invest** — rework our Java GLSurfaceView GL renderer toward GameHub 5.3.5's lean native-GLES2 feel.
6. Folded-in scope: **port our GL effects to Vulkan**, build a **generic ReShade-style effect engine**, add **FSR-Fit**.

---

## 5. CROSS-FORK COMPARISON (research 2026-06-26)

Probed: GameHub 6.0.9 (`/home/claude-user/gamehub-6.0.9-jadx`), GameHub **5.3.5** GLES2 (`/home/claude-user/langcmp/j535`+`full535`),
GameNative (`/home/claude-user/GameNative`), Ludashi (`/home/claude-user/ludashi-decompile`), us (`/home/claude-user/bannerlators`).

| | Bannerlator | GH 5.3.5 (GLES2) | GH 6.0.9 | GameNative | Ludashi |
|---|---|---|---|---|---|
| Host renderer | GL+Vulkan+**SF/ASR** | **GLES2 only** | Vulkan only | GL(legacy)+Vulkan | Vulkan only |
| GL impl | Java GLSurfaceView+EffectComposer | **Native libxserver.so, SurfaceView** | — | Java GLSurfaceView | — |
| Real FSR/SGSR | ❌ (CAS mislabeled) | ❌ (GL blit+CAS) | ❌ (CAS) | ✅ **FSR1 EASU+RCAS** | ❌ |
| Sharpen | ✅ CAS | ✅ CAS | ✅ CAS | ✅ DLS | ✅ CAS/DLS (vkBasalt) |
| HDR | ✅ fake | ✅ fake (ReShade FakeHDR) | ✅ fake (5 lvl) | ❌ | ❌ |
| FXAA/CRT/Toon/NTSC | ✅ all | CRT only | CRT only | ✅ all+Vivid | CRT only |
| Direct scanout | ✅ ASR | ✅ **Native Rendering+** (SurfaceControl) | ✅ GPU Passthrough | ❌ | ❌ |
| Frame gen | ✅ bionic/lsfg | ❌ | ✅ AI interp | ❌ | ❌ |
| Effect engine | fixed classes | **ReShade preset (guest)** | **ReShade generic .fx** | fixed classes | vkBasalt |

**Why GH 5.3.5 GLES2 "felt best" (it had FEWER effects than us):** a lean, low-latency native pipeline, four parts stacked —
(1) **native GLES2 X-server** (`libxserver.so` on a plain `SurfaceView`, GL fully in native — lighter than our & GameNative's
Java GLSurfaceView), (2) **true low-res render → cheap `glBlitFramebuffer` upscale** (`scale_to_desktop`/`unscaled`, bilinear/nearest,
720p default), (3) **CAS sharpen** ("Super Resolution") on top to recover crispness cheaply, (4) **SurfaceControl direct scanout**
("Native Rendering+") removing the compositor hop. Source files: `full535/.../com/winemu/core/server/XServer.java`,
`.../core/ui/X11View.java`, `.../core/DirectRendering.java`, `.../openapi/{CASEffect,HDREffect,CRTEffect,ReshadeConfig}.java`,
`lib/arm64-v8a/libxserver.so` (embedded GLSL ES blit shaders). No host Vulkan/FSR/SGSR/frame-gen here — those came in 6.x.

**We already inherited the standout:** our **SurfaceFlinger/ASR renderer == GH "Native Rendering+"** direct scanout, and our
low-res→native plumbing already exists. Gap vs the pack = real upscaling on the Vulkan path (+ effects stuck on GL).

### Best-of-all cherry-pick
- **From GameNative:** real FSR1 (EASU+RCAS) on Vulkan + Scaling-mode dropdown incl. **FSR-Fit** (aspect-preserving).
- **From GH 5.3.5:** the lean low-res→cheap-upscale→CAS + direct-scanout philosophy (we have ASR + low-res; add Vulkan upscale).
- **From GH 6.0.9:** generic **ReShade-style effect engine** (load arbitrary `.fx`) vs hardcoded effect classes.
- **From Ludashi:** present-mode/refresh/swapRB present tuning (we already have most).
- **Keep ours:** 3-way renderer, full FXAA/CRT/Toon/NTSC/color suite, frame-gen, SGSR+HDR toggles.

---

## 6. CHOSEN PROGRAM & PHASED ROADMAP

Goal: be the only fork with real spatial upscaling (SGSR **and** FSR1) on the default Vulkan renderer, effects working on Vulkan
(not GL-only), a data-driven effect engine, AND a lean GL path — all in one app, on top of our existing ASR + low-res + frame-gen.

- **Phase 1 — Vulkan post-process FRAMEWORK + upscalers (foundational).**
  Add an offscreen render-target stage + a generic second "post" pipeline + offline `.spv` shader loader to `VulkanRendererContext.cpp`.
  Ship **SGSR1** (single pass) + **FSR1** (EASU+RCAS, two pass) + **FSR-Fit** + None/Linear/Nearest, exposed as a live **Scaling mode**
  in the in-game drawer (`nativeSetUpscaler` following the `setFilterMode` pattern). This framework is the shared foundation Phase 2 reuses.
  Integration points (from recon): swapchain `:244-302`; draw loop `:758-770`; recreate path `:845-853`; shaders are C-array headers via
  `makeShader() :331`; config pattern `VulkanRenderer.java:681`→`vulkan_jni.cpp:234`→`:1195`. ASR mode bypasses this pass (`:829-839`) — document.
- **Phase 2 — Port GL effects to Vulkan.** Bring FXAA/CRT/Toon/NTSC/color (+ the fake-HDR look) onto the Phase-1 post pipeline so the
  drawer effects are no longer GL-only/grayed-out on the Vulkan renderer. Remove the `effectsSupported = (renderer instanceof GLRenderer)`
  gate (`XServerDisplayActivity.java:1778`) once Vulkan implements them.
- **Phase 3 — Generic ReShade-style effect engine.** Replace hardcoded effect classes with a data-driven `.fx`/preset loader (GH model):
  load arbitrary effects + typed uniforms + technique toggles. Larger redesign; builds on Phase 1/2.
- **Phase 4 — Lean GL path.** Move our GL renderer toward GH 5.3.5's native-GLES2 feel (reduce Java GLSurfaceView overhead; cheaper
  blit/upscale path). Helps users who select the GL renderer; lower priority than 1–3.

HDR remains parked (Section 2) until Android WSI HDR-surface availability improves or we accept inverse-tonemap as experimental.

---

## 7. SOURCES & CREDITS (provenance — who/what each piece comes from)

Our project is **GPL-3.0** (per README; the `LICENSE` file is a leftover MIT from the Winlator/BrunoSX origin).
All sources below are **license-compatible with GPL-3.0** and require **attribution only**. Action item: add each
to the README "credited below" section when the corresponding code lands.

### Code/shaders we BUNDLE (verbatim or adapted — license notice required in-file)
| Piece | From | Author / credit | License | Compat w/ GPL-3.0 | Where we put it |
|---|---|---|---|---|---|
| **SGSR 1.0** spatial shader | `github.com/SnapdragonGameStudios/snapdragon-gsr` → `sgsr/v1/` | **Qualcomm Technologies, Inc. / Snapdragon Game Studios** | **BSD-3-Clause** | ✅ (keep copyright + disclaimer) | `cpp/winlator/sgsr.frag` + `sgsr_frag.h` |
| **FSR 1.0 EASU + RCAS** | `github.com/GPUOpen-Effects/FidelityFX-FSR` → `ffx_fsr1.h` | **Advanced Micro Devices, Inc. (AMD GPUOpen / FidelityFX)** | **MIT** | ✅ (keep notice) | `cpp/winlator/fsr_easu.frag`, `fsr_rcas.frag` + headers |

### Approach/design we REIMPLEMENT (NOT copying code — credit as inspiration)
| Idea | From | Author / credit | License | Note |
|---|---|---|---|---|
| Present-time FSR1 in the Vulkan compositor (blueprint); **FSR-Fit** aspect-preserving mode | GameNative v0.9.1 `github.com/utkarshdalal/GameNative` | **utkarshdalal & GameNative contributors** | GPL-3.0 (same as us) | Reimplementing for clean provenance; GPL would also permit verbatim reuse if ever needed |
| Lean GLES2 pipeline + CAS "Super Resolution" + **Native Rendering+** direct scanout (Phase 4 / our ASR lineage) | GameHub 5.3.5 (`com.xj.winemu`, `libxserver.so`) | **Xiaoji / GameHub** (proprietary) | proprietary | Inspiration only — no code taken; clean-room |
| Generic ReShade-style `.fx` effect engine (Phase 3) | GameHub 6.0.x effect engine; ReShade concept | **Crosire (ReShade)** / GameHub | ReShade = BSD-3 (if any code reused) | Phase 3; decide source when scoped |
| Mobile-optimized EASU (fp16 tap rework — possible future) | atyuwen "Optimizing AMD FSR for Mobiles" | **atyuwen** (blog + gist) | (blog/gist; confirm before reuse) | Only if we add an fp16 mobile EASU path later |

### Deferred-HDR references (not used yet — Section 2)
| Piece | From | Author | License |
|---|---|---|---|
| Inverse-tonemap + PQ/HDR10 math (if we ever do HDR) | libplacebo `github.com/haasn/libplacebo` | **Niklas Haas** | LGPL-2.1+ (GPL-3.0 compatible) |
| HDR10 passthrough (env `DXVK_HDR`) | DXVK `github.com/doitsujin/dxvk` | **Philip Rebohle (doitsujin) & contributors** | zlib/libpng |

### Our existing base (lineage already credited in README)
- Vulkan compositor + X-server: **Winlator** (`brunodev85`) → cmod → Bionic Nightly → **Star Bionic** (`star-emu/star`) → marcescence → Bannerlator.
- Existing GL effects (`renderer/effects/FSREffect`=CAS, `HDREffect`, FXAA/CRT/Toon/NTSC) already in-tree from that lineage.

**Attribution discipline:** (1) keep the upstream copyright header inside each shader file (BSD-3 for SGSR, MIT for FSR);
(2) add a line to README credits per bundled source; (3) note "approach inspired by GameNative/GameHub" in the relevant
source comments without copying their code.

## 8. Change log
- 2026-06-26 — Research complete (upscaler survey, HDR survey, native compositor recon). Plan drafted. No code.
- 2026-06-26 — Cross-fork comparison added (GH 6.0.9, GH 5.3.5 GLES2, GameNative, Ludashi). Decisions locked (§4): SGSR+FSR1+FSR-Fit,
  HDR deferred, offline `.spv`, in-game drawer, invest in GL path too, port GL effects to Vulkan + generic effect engine. 4-phase roadmap (§6).
- 2026-06-26 — Provenance/credits table added (§7); render_upgrades_report.html generated (also saved to /sdcard/Download).
- 2026-06-27 — **Phase 1 IMPLEMENTED + committed `5f5a4a0`** on branch `feat/vulkan-upscaler-sgsr-fsr` (pushed). Native upscaler
  framework (offscreen target + post pipelines + SGSR/EASU/RCAS .spv headers) + JNI `nativeSetUpscaler` + in-game-drawer "Scaling mode"
  picker (None/Linear/Nearest/SGSR/FSR/FSR-Fit, Vulkan-gated). Self-reviewed correct (subpass deps, init order, lifecycle, gating).
  Status: **compile-reviewed + shaders compile-verified; NDK build = CI run `28276691564` ✅ GREEN (all 3 flavors); device-UNTESTED.**
  NEXT after green: device-test with a container `screenSize` below panel res so the upscaler engages. Then Phase 2 (GL effects→Vulkan).
- 2026-06-27 — ✅ **CI `28276691564` GREEN** (commit `5f5a4a0`) — native upscaler + drawer compiles across all 3 flavors. THEN
  UX follow-up `28ab22d`: Graphics tab now shows ONLY the active renderer's controls (OpenGL→SGSR/HDR+ScreenEffects;
  Vulkan→Scaling mode; SurfaceFlinger→"no enhancements" note) instead of greying out the rest. ✅ CI `28277238762` GREEN.
- 2026-06-27 — **Phase 1b committed `c3cbe49`** (full build CI `28277821185` ✅ GREEN — Phase 1 + 1b compile across all 3 flavors). Adds: (a) **Sharpen-only** = Scaling-mode
  **6** (FSR RCAS, no upscale, works at NATIVE res, live drawer; 7 chips now in a 4+3 layout); (b) **Supersampling "Render scale"**
  = pre-launch container+shortcut setting Off/1.25x/1.5x/2x via `renderScale` extra (no DB migration). Launch multiplies the X11
  render res (aspect-preserving, clamp 7680x4320, even dims) → compositor runs new Lanczos-2 `downscale.frag` via
  `setHqDownscale(true)` (UPMODE_DOWNSCALE, engages when hqDownscale && render>display). DSR/OGSSAA-style. Mode enum now 0-6
  (6=sharpen) + separate `setHqDownscale(bool)`. ⚠️ compile-reviewed; CI pending; device-UNTESTED (2x≈4K internal on 1080p = VRAM
  + 81-tap Lanczos cost, allocation-guarded). NEXT: CI green → device-test (sub-native upscale modes + native sharpen + 1.5x SS).
