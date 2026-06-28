# Star-Compose — Progress Log

---

## 2026-06-28 — 🛠️ BUILT: Debanding + NIS upscaler (branch `feat/deband-nis`, CI pending, NOT device-tested)

Implemented Track-1 step 1 from the master plan below. **Branch `feat/deband-nis` off main, 6 commits
`194b7b9`→`cc3e0e7` (+log `a53a226`), PUSHED, NOT merged, NOT device-tested.** Shaders compile clean
(glslangValidator), all cross-layer contracts reviewed+consistent. **CI dispatched: run `28319416413`
(build-artifacts.yml — CI is manual workflow_dispatch, not auto-on-push).** Awaiting CI result + on-device A/B.

**DEBANDING** — terminal TPDF/IGN dither pass (float-only hash, Adreno-safe), `deband.frag`+header,
appended LAST as `FX_DEBAND`, registered in fxOn in BOTH planUpscaleFrame AND recordUpscalePasses
(known "scaling drops chain" bug avoided). `setDeband(bool,int strength)` JNI↔C++↔Java; GL `DebandEffect`
+ dedicated terminal slot in EffectComposer (render() + renderUpscaled()). strength 0..200 → /100 LSBs
(default 100 = ±1/255). Session-live (not persisted).

**NIS** — new upscaler mode int **7**. Faithful NVScaler port from authentic NVIDIA reference (MIT)
into single-pass `nis.frag` (edge map + 6-tap scale/USM + directional filters + CalcLTI + luma recolor);
**exact fp32 coef_scale/coef_usm baked as `const float[384]`** (transcribed programmatically from
NIS_Config.h, NOT hand-typed, first-row verified); fp32 path (no fp16/bitwise); NO 2nd descriptor binding.
Reuses existing sharpness slider. GL `NISEffect` + EffectComposer `case 7`. Engages only when render
res < display (like SGSR mode 3). Drawer: `7 to "NIS"` + mode-7 sharpness in both GL+Vulkan blocks.

**⚠️ Needs device-verification (agent's honest flags):** (1) NIS math not pixel-compared to a reference
NIS — A/B on the high-freq SPACE scene 720p→1080p, GL AND Vulkan. (2) GL NISEffect runtime-compiles a
heavy unrolled NVScaler + 2×384 const arrays on the Adreno GLES driver = the runtime-compile-crash class
the roadmap flags — DEVICE-TEST GL NIS specifically (Vulkan NIS is precompiled SPIR-V, safer). (3) NIS =
37 texture fetches/fragment, perf untested. (4) CI is the first real compile of the non-shader code.
Files: `cpp/winlator/{deband,nis}.frag`+headers+`gen_shaders.sh`, `VulkanRendererContext.{cpp,h}`,
`vulkan_jni.cpp`, `renderer/vulkan/VulkanRenderer.java`, `renderer/EffectComposer.java`,
`renderer/effects/{DebandEffect,NISEffect}.java`, `ui/XServerDrawer.kt`, `ui/XServerDialogState.kt`,
`XServerDisplayActivity.java`.

---

## 2026-06-28 — 🗺️ MASTER PLAN: Graphics smoothness/sharpness roadmap + App theming/icons

> **STATUS: RESEARCH + RECON + 1 HTML MOCKUP + CODE-GROUNDED PLANS ONLY. NOTHING CODED, NOTHING
> COMMITTED, NOTHING DEVICE-TESTED.** This entry consolidates a full session of exploration into one
> plan. Deep detail (every file:line touchpoint) lives in the two memory files:
> `project_bannerlator_smooth_sharp_render_roadmap.md` + `project_bannerlator_theming_icons.md`.

Two parallel tracks scoped this session. Recommended first build = **debanding + NIS** (Track 1, small/visible/low-risk).

---

### TRACK 1 — SMOOTHNESS + SHARPNESS RENDERING

**User goal:** keep games EXTREMELY SMOOTH, NO FPS loss (ideally gain), + clarity/sharpness.

**Core principle (the answer):** it's a STACK, not one effect →
**render a bit lower → spatial upscaler (sharp) → VRR refresh-match (smooth) → debanding (clarity).**
That gains real FPS, cuts heat/power, adds sharpness, zero added latency.
**Myth busted:** frame-gen does NOT give free FPS — it inflates the HUD number while costing GPU + latency
(a perceived-smoothness layer, optional cherry-on-top for single-player base≥45fps).

**Recommended build order:**
1. **Debanding + NIS** ← START HERE (ready, ~6 commits, low-risk)
2. **VRR / `setFrameRate`** (refresh-rate matching; biggest smoothness-per-effort; SurfaceFlinger path best home)
3. **Curated shader-loader platform** (force-multiplier; compile `.spv` OFFLINE in CI = Adreno-crash-safe; permissive-licensed shaders only)
4. **Moonshot: DXVK depth → true SGSR2 on TAA/FSR2 games** (XL, staged, multi-repo)
5. Keep frame-gen (`bionic-fg`) gated/off-by-default, labeled as a latency trade.
**DROP:** BFI, Anime4K-CNN, pixel-art scalers, heavy CRT (royale), NPU/AI super-res — each costs FPS or doesn't fit.

**Scorecard (vs goal):** SGSR1/NIS/FSR1 ★★★★★ · CAS / shader-loader-platform ★★★★ · 3D-LUT / panel-calibration / estimated-MV-temporal ★★★ · Anime4K(CNN heavy) / pixel-art / adaptive-sharpen(already have CAS+RCAS) / heavy-CRT ★★ · BFI ★½ skip.

**READY-TO-BUILD PLAN — Debanding + NIS (GL + native Vulkan; ASR path runs neither chain):**
Shared plumbing: Vk post shaders `cpp/winlator/*.frag` + committed `*_frag.h` SPIR-V (gen via
`glslangValidator -V x.frag --vn x_code -o x_frag.h`, NO CI compile step). `VulkanRendererContext.cpp`
`createPostPipelines`~:552 / `recordUpscalePasses`:1074 / `planUpscaleFrame`:1235 / locked effect-chain
:1204-1229 / SINGLE-sampler `createDSLayout`:402-407 (don't add a 2nd binding) / PC range 88B.
**Whole chain = R8G8B8A8_UNORM 8-bit** end-to-end. GL = `renderer/EffectComposer.java` + `renderer/effects/*.java`.
Drawer `ui/XServerDrawer.kt` (options :772-775, sharpness slider :815-838). Effects SESSION-LIVE (not persisted).
⚠️known bug: an effect silently no-ops under scaling unless added to fxOn in BOTH planUpscaleFrame:1250 AND recordUpscalePasses:1087.
- **Debanding:** new `deband.frag` = terminal dither (IGN/TPDF, float-only hash, ~1 LSB, display space, no texture taps, Adreno-safe). Last effect-chain entry `FX_DEBAND` (always→swapchain). Vk pipeline + push-const + `setDeband` JNI/Java. GL `DebandEffect` + dedicated TERMINAL slot in EffectComposer (GL has no fixed order). Toggle + optional strength slider. Optional SEPARATE gated commit: bump offscreenFmt→A2B10G10R10/R16F (then off↔swap cross-bind needs split pipelines) — ship dither-only first.
- **NIS:** upscaler mode int=7. new `nis.frag` single-pass NVScaler, BAKE coef tables as `const float[]` in shader (no 2nd descriptor binding; fp32 not fp16). Reuse sharpness slider. Vk pipeline + planUpscaleFrame mode-7 branch (like SGSR mode3) + recordUpscalePasses single-pass; no new JNI. GL `NISEffect` + setUpscaler `case 7`. Drawer add `7 to "NIS"`. MIT ©NVIDIA.
- **Commits:** 1)shaders+headers+gen_shaders.sh 2)NIS-Vk 3)deband-Vk+JNI 4)NIS-GL 5)deband-GL 6)drawer+wiring 7)opt fmt-bump 8)opt persist. **Device-test SPACE scene 720p→1080p, GL AND Vulkan, A/B, confirm no FPS drop + composes with upscalers.**

**STAGED MOONSHOT — SGSR2 (XL, MULTI-REPO):** VERDICT — shipping a patched DXVK is mechanically fine
(we build/ship own DXVK `.tzst` in `assets/dxwrapper/`, extract `XServerDisplayActivity.java:2582`). REAL WALL =
guest→host transport: today only 1 buffer (color) crosses via DRI3 1-AHB=1-FD (`DRI3Extension.java:141`
`modifiers==1255`); the SENDER of extra buffers lives in the guest Wine/WSI build (NOT this repo → wine-compat +
guest build). PREREQ A.0: add a sub-1.0 internal-res lever (renderScale only supersamples ≥1.0 today). **Stage A
depth** (med, useful alone → unlocks DoF/SSAO): patch DXVK to export chosen depth as **R32F** AHB (sidesteps Adreno
depth-AHB limits), new DRI3 `modifiers==1256`, depth import variant, prove via depth-grayscale DEBUG pass. **Stage B
SGSR2** on TAA/FSR2 games (they give MV+jitter free): new 2-pass-FS shaders, persistent history buffer, 4-binding
descriptor for SGSR2 only, per-title jitter profile, auto-fallback SGSR2→SGSR1→passthrough, Vulkan-compositor-only
(ASR can't). **Stage C generic = research wall** (jitter can't be generically injected) — spike only.
**Smallest prototype:** 1 FSR2/TAA game → DXVK export depth-only R32F → accept 1256 → grayscale debug overlay;
if frame-aligned depth correct, transport PROVEN, rest is shader math; else STOP.

**SurfaceFlinger/ASR renderer (the 3rd host renderer) findings:** it hands the guest buffer STRAIGHT to the system
compositor — NO programmable pass, so the upscaler/effect shaders CAN'T live there (by design = its speed/battery win).
What it CAN uniquely add: `setFrameRate`/VRR (best home for #2 above), present-fence timing into the HUD, scaling/aspect
geometry modes, true HDR10 passthrough (speculative), layer alpha/damage. Already wired: setBuffer/geometry/visibility/zorder.

---

### TRACK 2 — APP THEMING + BUTTON ICONS

**Deliverable in hand:** interactive HTML preview (live theme switch, dark/light, accent slider, typography
before/after, 4 screens with icon fixes, offline-safe inline SVG) at device
`/sdcard/Download/bannerlator_theme_icons_preview.html` (+ scratchpad + `~/Downloads/`).

**Finding:** the app ALREADY has a theme engine (`ui/theme/Theme.kt`/`ThemePreset.kt` = 8 presets + HSV picker
`AppearanceScreen.kt`) → this is POLISH not a rebuild. Brand accent `#0055FF` (`Color.kt:8`).
**3 weaknesses:** light mode is DEAD CODE (`toLightColorScheme` exists, `_isDarkMode` hardcoded true, no toggle);
no Material You; ~730 hardcoded color literals bypass the theme (custom accent only paints PART of app until centralized).

**Proposals:**
- 3 new themes (live in the HTML): **★Midnight Cobalt** (rec, evolves brand blue; primary `#2F6DFF`/accent `#6FA8FF`/bg `#0E1117`) · **Phosphor Terminal** (retro CRT amber+green, dark-only) · **Carbon & Ember** (graphite+orange `#FF7A33`). Rec: ship Midnight Cobalt default + keep AMOLED + add Phosphor preset.
- **Typography ramp** (high ROI, ~10 lines `Theme.kt`): app forces weight 600 on ALL text → give head700/body400/label500.
- Un-disable light mode (setter+toggle); optional Material You preset API31+.
- **Icon gaps:** store detail pages (`store/{Steam,Epic,Gog,Amazon}GameDetailActivity.kt`) are TEXT-ONLY buttons → add `Icons.Filled.{PlayArrow,Download,Update,Delete,InsertDriveFile,CloudUpload}`; text "←"→`ArrowBack`; Epic "✓ Installed"→`CheckCircle`; game-card placeholder `OpenInNew`→`SportsEsports`; magnifier "✕"→`Close`. `material-icons-extended` already a dep. **Highest-ROI = store action-button icons (pure additive).**

**3-bucket app-wide reach verdict:** (a) AUTO once colors centralized = all out-of-game Compose UI + in-game magnifier;
(b) small recolor fix = in-game DRAWER `XServerDrawer.kt` (wrapped in theme, inherits fonts, but ~40 hardcoded colors → stays blue under a custom accent);
(c) feed accent MANUALLY = FPS/perf HUD (`widget/FrameRating*.java`, `PerfHudView.java`), on-screen controls (`InputControlsView.java`), legacy XML editors — via existing bridge `AppThemeState.getCurrentAccentArgb()`.

**Theming sequencing:** 1) typography ramp 2) store-button icons + back-arrows 3) centralize ~730 colors per-screen 4) Midnight Cobalt + Phosphor 5) light-mode toggle + optional Material You.

---

### NEXT MOVE
Start coding **Track 1 → debanding + NIS** (commits 1-6, CI-green, then user device-tests on the SPACE scene before SGSR2 spike). Everything above is durable in the two memory files; this log entry is the master index.

---

## 2026-06-27 — 🏷️ 2.0 STABLE RELEASE CUT

Merged the #18 turnip-ICD branch to main, bumped to **2.0 (versionCode 32)**, rewrote the README
"What's New in 2.0" + feature sections, and cut the **2.0 stable release** (run `28309799973`,
prerelease:false + make_latest:true → now the Latest the in-app updater offers; 3 flavor APKs +
update.json attached). Closed PR #24 (not merged).

**Everything in 2.0 since 1.9.2:** OpenGL renderer upscaler parity (SGSR/FSR/Sharpen) · sharpness
sliders retuned (0=off→100=max, SGSR doubled, Sharpen 5-stop snap, inverted-CAS fix) · OpenGL
filter-mode + glBlitFramebuffer plumbing (P4) · **game + container card redesign (#19)** ·
**auto-close session on game exit** · magnifier fix on Vulkan (#22) · FEXCore "Performance (TSO)"
preset (#20) · **Android-10 direct-ICD turnip driver (#18)**.

README/release note imageFS-reinstall reminder added (new turnip driver lives in imageFS).
Pinged issue #18 reporter (SD845/A10) to verify the new `turnip-26.1.0` driver — issue left open
pending their confirmation (no A10 device locally).

---

## 2026-06-27 — #18 direct-ICD turnip path for Android <11 (built, CI-green, awaiting reporter A10 test)

Diagnosis: the reporter's A10/SD845 problem is the driver LOADING MECHANISM — Bannerlator loads
turnip via adrenotools (linkernsbypass hook, needs ~A11+); Winlator loads it as a plain system
Vulkan ICD (works on any Android). Also closed PR #24 (strlcpy/snprintf — original code already
safe).

Branch `feat/turnip-icd-direct-android10` off main (`f43a319`), commits `60038cf` + `971c415`,
CI `28309292459` ✅ green, **NOT merged, NOT device-tested** (no A10 hardware here). New driver
option `turnip-26.1.0` (Winlator's Mesa Turnip 26.1.0, ICD format) that installs the freedreno
ICD + .so into the guest, points `VK_ICD_FILENAMES` at it, and **skips the adrenotools env**
entirely — bypassing the A11+ hook. Picker filter special-cased to gate on `isAdrenoGPU()` (the
normal adrenotools probe is exactly what fails on A10). Adrenotools path untouched; default still
turnip-sdk36. Top residual risk: host-vs-guest path assumption (if proot-remapped, library_path
would need a guest path). NEXT: reporter verifies a CI build on their SD845 → then merge.

---

## 2026-06-27 — Implement open issues #22 (magnifier) + #20 (FEX Performance+TSO preset)

gl-upscaler-parity merged to main (`6d5f75b`). Branch `fix/issues-22-magnifier-20-fextso` off
main, CI build `28308479676` ✅ green. **User device-confirmed both work → MERGED to main (ff
`6d5f75b..ecb8646`, branch deleted, no tag); GitHub auto-closed #22 and #20.**

**#22 magnifier (`d7a736e`):** `showMagnifierOverlay()` cast the renderer to `GLRenderer` and
no-op'd the zoom callback for anything else → on the default Vulkan renderer the overlay opened
stuck at 100% with dead +/- buttons. Now uses the `HostRenderer` interface (get/setMagnifierZoom
implemented by all 3 renderers; Vulkan applies it live via `updateTransform`). The other
GLRenderer cast in `showScreenEffectsDialog` is correct (effects are GL-only) — left as-is.

**#20 FEX "Performance (TSO)" preset (`55fb879`):** fetched the issue screenshot — the reporter's
preset is Performance with **only `FEX_TSOENABLED=1`** (vector/halfbarrier/memcpy TSO stay off,
x87-reduced + multiblock on), the lightweight single-TSO-flag variant. Added `PERFORMANCE_TSO`
to `FEXCorePreset` + a `getEnvVars` block + `getPresets` entry + `performance_tso` string.
Additive, no DB migration; auto-appears in the spinner and Compose container/shortcut editors.

NEXT: CI green → device-test (#22 magnifier on a **Vulkan** container; #20 pick preset + launch a
TSO game) → merge to main.

---

## 2026-06-27 — Open-issue triage + scopes (#22 magnifier, #20 FEX TSO preset) — QUEUED

Scoped while the gl-upscaler-parity slider CI built. To be implemented on a **fresh branch off
main AFTER the gl-parity sliders device-test + merge** (user: "after we device test we will
tackle them both").

**#22 — magnifier doesn't work / zoom — ROOT CAUSE FOUND (high confidence):**
`XServerDisplayActivity.showMagnifierOverlay()` (~line 3339) casts the renderer to
**GLRenderer only** and the zoom callback early-returns when it's not GL. The **default
renderer is Vulkan**, so the Magnifier overlay opens, shows 100%, and the +/− buttons are dead.
Confirmed clean fix: `HostRenderer` interface already declares `get/setMagnifierZoom` (all 3
renderers implement it) and `VulkanRenderer.setMagnifierZoom` calls `updateTransform()` → zoom
applies live. **Fix = use the `HostRenderer` reference instead of the GLRenderer cast** (~3
lines, 1 file). Optional: `ASurfaceRenderer.setMagnifierZoom` lacks a redraw trigger (add
`updateScene()`). Device-test on Vulkan (can fold into the slider session).

**#20 — Add FEX "Performance + TSO" preset:** the built-in PERFORMANCE preset sets
`FEX_TSOENABLED=0`; many games need TSO. **Fix = add a new "Performance (TSO)" built-in preset**
(Performance base + the 4 TSO flags on: TSOENABLED/VECTORTSOENABLED/MEMCPYSETTSOENABLED/
HALFBARRIERTSOENABLED=1, X87REDUCEDPRECISION=1, MULTIBLOCK=1) across `FEXCorePreset.java` +
`FEXCorePresetManager.java` (getEnvVars + getPresets) + `strings.xml`. No DB migration (presets
stored by id). Verify exact flags vs the reporter's screenshot.

**Also noted:** #18 (bundle brunodev Winlator turnip 26.1.0 for A10/SD845 — packaging easy but
adrenotools load may need A11; needs an A10 device) and PR #24 (bounded strlcpy/snprintf in
android_sysvshm.c — review + merge candidate).

---

## 2026-06-27 — GL upscaler parity: device-test, inverted-slider fix, sharpness-range tuning

**Branch:** `feat/gl-upscaler-parity` (off main `ec3bcb0`). Phase 1 (SGSR/FSR/Sharpen on the
OpenGL EffectComposer + drawer Scaling-mode picker, commits `efd5f4f`→`327ab9d`) was already
CI-green (`28306036455`). This session = device-test + fixes. NOT merged.

**Device test (build 1.9.2 vc31, AIO-Graphics-Test-32bit = OpenGL + 1280x720 on 1080p panel,
DX11 SPACE scene, frozen-frame A/B via drawer Pause):** GL parity LIVE — all 6 scaling chips
produce DISTINCT output on the GL renderer (frozen-frame re-upscale works). RMSE vs None:
Linear 0% (≡None ⇒ default sampling IS bilinear) · Nearest 1.51% (blocky) · Sharpen 0.36% ·
SGSR 0.68% · FSR 0.79% (crispest). Cursor stays crisp under Nearest = PASS (host cursor
exempt from point-scale). Zoomed montages confirm visual distinctness.

**🐛 Bug found + fixed (`52c7092`):** the upscale "Sharpness" slider was INVERTED for the
Sharpen mode (AMD CAS) — raising it SOFTENED. Root cause = `FSREffect`'s level scale is
inverted (level 1 = CAS sharpness 0.90 = sharpest, level 5 = 0.12 = softest) but both CAS
call sites mapped slider straight onto level. Fix: `EffectComposer.buildPickerCas():339`
`level=(1-upscaleSharpness)*4+1` + `XServerDisplayActivity.onSgsrUpdate:2088`
`level=(100-sharpness)/25+1`. SGSR (EdgeSharpness=1+s*1.333) and FSR RCAS (stops=1-s) were
already correct → untouched. CI `28307366153` triggered then cancelled (superseded below).

**✅ Slider-effectiveness tuning DONE + committed (graphics-vulkan-engineer, GL + Vulkan):**
every sharpness slider now spans **0 = nothing (neutral, no sharpening; upscale still runs)
→ 100 = max**, ZERO shader recompiles (all host-side push-constant / pass-gating math).
Final effective values, slider 0/50/100:
- GL SGSR EdgeSharpness **1.0 / 2.33 / 3.67** (`1.0+s*2.666` — span doubled, neutral floor 1.0)
- GL FSR RCAS lobe scale **0.0 / 0.5 / 1.0** (`clamp(sharpness,0,1)` — 0 = true passthrough)
- GL Sharpen mode 6 (snapped {0,25,50,75,100}) **OFF / CAS 0.50 / CAS 0.90** (0 = no CAS pass)
- GL "Sharpen (CAS)" toggle (snapped) **OFF / 0.50 / 0.90**
- Vulkan SGSR edge **0.5 / 2.5 / 4.5** (`0.5+s01*4.0` — span doubled)
- Vulkan FSR/Sharpen RCAS con.x **0.0 / 0.5 / 1.0** (`upscaleSharpness01` linear, 0 = passthrough)
- Vulkan CAS toggle **OFF / 0.5 / 1.0** (`casOn = casEnabled && casSharpness>0`)
Snap = `XServerDrawer.kt` IntSlider `steps` override (5 stops only in mode 6 + always for the
Sharpen(CAS) toggle; SGSR/FSR/Vulkan-Sharpen stay continuous). No over-drive past RCAS/CAS
ceiling → no ringing. Commits `beebb17` (GL SGSR-double + FSR 0=neutral) · `fac47ed` (GL Sharpen
5-stop snap + 0=OFF) · `0718c39` (Vulkan mirror). CI build **`28307792454`** ✅ green all 3 flavors.

**✅ GL device-test PASSED (AIO space scene, OpenGL|DXVK Adreno 750, live drawer-open method):**
Sharpen slider **snaps to 0/25/50/75/100** (tap ~38% → snapped to 50). FSR slider **0 = true
passthrough** (crop png 218 KB, softer than None's 245 KB, no sharpening) → **100 = strongly
sharp** (420 KB, +92%). SGSR **0→100 = +18% png**, montage shows visibly crisper limb/coastlines
(doubled range). 0 = neutral confirmed. Vulkan mirror = code-verified (shared host logic) but
not device-tested this session (AIO shortcut is OpenGL). Note for next time: this AIO test
**exits the scene on BACK while paused**, so the frozen method was abandoned — open the drawer
once via BACK while running, then switch chips live without pause/BACK. NEXT: merge gl-parity to main.

**❌ Dropped per user:** a persisted per-shortcut "upscaling on/off" toggle. Clarified that
720p→1080p plain stretch is ~free (final-blit sampler); only opt-in SGSR/FSR cost GPU and are
already default-None + session-live. User said "leave the upscaling alone."

---

## 2026-06-27 — P4 "Lean GL path" (render-upgrades roadmap, final phase) — steps 1+3

**Status:** branch `feat/p4-lean-gl-1-3` off main `35fd80d` (pushed). 2 commits. CI
`28304623016` (`build-artifacts.yml`, all 3 flavors) running. Baseline main build
`28304103719` ✅ green (known-good fallback). NOT merged; device-test pending.

**Context:** Vulkan render-upgrades (P1 SGSR/FSR framework, P1b/1c CAS/HDR/sliders, P2 the
5 GL effects → Vulkan, + native-mutex) are all DONE + on main. P3 (ReShade `.fx` engine)
DROPPED. **P4 = the last phase**, and it targets the *Java GL renderer only*.

**Recon (graphics-vulkan-engineer, read-only):** the roadmap's framing — "reduce
GLSurfaceView overhead" — is mostly the wrong target. In the default config (DRI3 on) the
game frame is **already zero-copy** on GL via AHardwareBuffer→EGLImageKHR (`GPUImage`), so
there's no per-frame CPU upload to kill. EGL context is already GLES3 (`XServerView.java:89`)
despite `GLES20.*` calls → GLES3 APIs available today. Real gaps: `setFilterMode` is a dead
no-op on GL; no low-res→cheap-upscale path; full-frame `glTexSubImage2D` only on the
SHM/DRI3-off/cursor path; effect chain renders base into a full-res FBO even for 1 effect.
GameHub's `libxserver.so` is proprietary but its "direct scanout" rides the same AHB/EGLImage
primitives we already own → P4 = clean-room reimpl with in-tree CAS/SGSR/FSR, nothing
license-blocked. Ladder = 5 rungs; this batch = the low-risk 1–3.

**Implemented (this batch):**
- **Step 1 — `f7e0670` setFilterMode real on GL.** `GLRenderer.java`: new `windowTexFilter`
  field; `setFilterMode(int)` maps `2→GL_NEAREST else→GL_LINEAR` (matches Vulkan convention),
  applied in `renderDrawable` ONLY when `material == windowMaterial` so the **cursor stays
  LINEAR**. Launch hook `XServerDisplayActivity.java:1785`
  `renderer.setFilterMode(container.getRendererFilterMode())` gated `instanceof GLRenderer`
  (was dead/unreachable before — nothing called `HostRenderer.setFilterMode`; Vulkan drives
  filtering via `setUpscaler`). `getRendererFilterMode()` verified to exist (`Container.java:497`).
- **Step 2 — PBO async upload — ❌ DROPPED.** First CI failed to compile
  (`Texture.java:153 int cannot be converted to Buffer`): this project's compileSdk exposes
  only the `Buffer` overload of `GLES30.glTexSubImage2D`, not the int-offset (PBO) variant →
  a PBO can't feed the texture, so no benefit (`glTexImage2D`-with-offset would realloc every
  frame, slower than the sync path). Cleanly reverted (`Texture.java` diff vs main now empty).
  Deferred, not delivered. Lesson: don't assume Android GLES30 int-offset texSubImage exists.
- **Step 3 — `c085dd9` glBlitFramebuffer for trivial copy stage.** `EffectComposer.render()`:
  null-material (pure-copy) pass now goes through `blitReadBufferTo` (GLES30
  `glBlitFramebuffer`, COLOR_BUFFER_BIT, LINEAR, scissor disabled) instead of program-bind +
  textured quad. ALL real shader passes (Color/FXAA/Toon/CRT/NTSC/CAS) + the source-less
  `drawFrame` scene render are UNCHANGED — only the degenerate null-material branch changed
  (which also fixes an old clear-then-draw-nothing→black bug). Bit-identical with-effects output.

**Deferred:** Step 4 (low-res render-target → cheap upscale = the actual "GameHub feel" win,
higher risk: letterbox/scissor + keep cursor full-res) and Step 5 (drop GLSurfaceView for an
owned EGL/SurfaceView present thread). Step 2 (async CPU-path upload) would need a non-PBO
mechanism given this SDK's bindings.

**Confidence:** step 1 = would-work/needs-device-proof (sampler state only, no fast-path
regression); step 3 = would-work (bit-identical for shipped effects). No SDK in the agent env
→ correctness proven by CI compile + device test, not local build.

**Next:** CI green → device-test (GL renderer + Filter toggle nearest/linear; confirm cursor
stays sharp; effects still composite) → merge to main. No tag/release (artifacts only).

---

## 2026-06-27 — #19 follow-up: Layout L wired + A/L card chooser

**Status:** branch `fix/shortcut-name-overflow` (pushed). Commit `324bb4a` (L + chooser) +
audio-drop follow-up edit. Compile CI `28300618235` (`main.yml`) running on `324bb4a`;
audio-drop fix needs a re-run after its commit. NOT merged.

**Ask:** user said "wire up L so I can choose between the two" — make the list-view card style
user-selectable between the existing layout A and the chosen layout L.

**Implementation:**
- `ShortcutsViewModel.kt`: persisted `useLayoutL` pref (`shortcuts_prefs` / `list_card_layout_l`,
  default `false` = A) mirroring `isGridView`; `setUseLayoutL()`.
- `ShortcutsScreen.kt`:
  - Top-bar **A/L toggle** — an "A"/"L" Text `IconButton`, shown only in list view (hidden in
    grid). Keyed the top-bar-actions `LaunchedEffect` on `(isGridView, useLayoutL)` so the toggle
    reflects live state (was `Unit` → captured stale values; the existing grid icon only updated
    via content recompose).
  - List branch now picks `ShortcutItemLayoutL` vs `ShortcutItem` on `useLayoutL`.
  - New `ShortcutItemLayoutL`: same 48×64 poster cover; subtitle = `container · resolution`;
    PRIMARY `FlowRow` of bright `CompChip`s — renderer (`ChipRendColor`), DXVK, frame-gen
    (`ChipFgColor`, "Bionic-FG" / "LSFG-VK"); SECONDARY `FlowRow` of `SecondarySpec` (7dp colour
    dot + dimmed `OnSurfaceVariant` 10sp label) — driver, VKD3D, backend (`ChipCpuColor`).
  - Renderer / frame-gen / backend resolved via `shortcut.getExtra(key, container.getX())`
    (`getRenderer` / `getFrameGenEngine` / `getEmulator`); backend name via
    `R.array.emulator_entries` + `StringUtils.parseIdentifier`.
  - **Refactor:** extracted the shared ⋮ menu into `ShortcutOverflowButton` (own `menuExpanded`),
    now used by BOTH A and L so the menu can't drift.
- **Audio dropped** from L's secondary (follow-up edit) to match `docs/shortcut_card_L_final.html`
  — lets driver · VKD3D · backend sit on one row. First commit `324bb4a` wrongly included it.
- **Deferred:** backend preset suffix ("FEXCore · TSO" / "Box64 · Perf" in the mockup) — needs the
  async `Box64PresetManager` / `FEXCorePresetManager`, too heavy to resolve per list-card; backend
  shows the emulator name only for now.

**Next:** commit audio-drop fix → CI green → device-test (both layouts render + pref persists
across the toggle and app restart) → merge → close #19. ⚠️ save-before-device-test rule.

---

## 2026-06-27 — Issue #19 "Name of games is empty" + game-card redesign

**Status:** branch `fix/shortcut-name-overflow` (pushed, NOT merged). Layout A build CI `28299970224` running.

**Root cause (#19):** in `ShortcutsScreen.kt` list-mode `ShortcutItem`, the right-aligned
resolution+DXVK/VKD3D info column had no width bound. In a `Row`, unweighted children are
measured before the weighted name column gets the remainder, so a long version string (e.g.
a DXVK/VKD3D nightly with a commit id in its name) grew unbounded → collapsed the weighted
name column to 0 width ("name is empty") AND pushed the trailing ⋮ overflow menu off-screen.

**Iterations on the branch:**
- `1dd8d4d` interim: capped info column `widthIn(max=120dp)` + ellipsize. Device-tested by user
  → fixed the blank name but now TRUNCATED the component versions (not acceptable).
- `e496040` interim: split DXVK/VKD3D onto own lines, wrap to 2 lines, cap 140dp.
- `b598adb` (current tip) **Layout A redesign**: replaced the info column with a 3:4 poster
  cover (reuses `shortcut.icon`, same bitmap the grid uses) + name/container + graphics
  components as colour-coded chips on a wrapping `FlowRow` (`CompChip` helper + 4 chip colors,
  `ExperimentalLayoutApi`). Long version strings wrap to another chip line / grow the row
  taller instead of clipping. CI building (`28299970224`).

**Design exploration (HTML mockups, rendered via headless chromium, saved to /sdcard/Download):**
- `docs/shortcut_card_layouts.html` — 6 layouts A–F (poster, square-icon+chips, hero banner,
  16:9 spec grid, two-tier stat strip, current-for-comparison).
- `docs/shortcut_card_layouts_dense.html` — 6 denser layouts G–L that ALSO show renderer
  (OpenGL/Vulkan/SurfaceFlinger), frame-gen (off/bionic/lsfg), audio (ALSA/PulseAudio) and
  x86 backend (FEXCore/Box64/wowbox64 + box64/FEX preset). Real values pulled from arrays.xml
  + ShortcutsScreen.
- `docs/shortcut_card_L_final.html` — **user likes layout L** (bright primary chips
  renderer·DXVK·frame-gen + muted secondary line driver·VKD3D·backend), **audio dropped per
  user so the secondary line fits one row / card is shorter**. Resolution moves into subtitle.

**NEXT:** user wants to SEE the layout A build on device first, but LIKES L → likely wire L
(swap the FlowRow chip cloud for L's two-tier primary/secondary) on the same branch. L needs
3 more shortcut extras resolved in ShortcutItem: `renderer`, `frameGenEngine`, `emulator`
(+ box64Preset/fexcorePreset). Keys all confirmed present.

---

## (legacy) Star-Compose

**Repo:** https://github.com/The412Banner/star-compose (main branch)  
**Mirror:** https://github.com/kalteatz24/winlator-test (star-compose branch)  
**Local:** `/data/data/com.termux/files/home/winlator-test`  
**Always push to both remotes after every commit:**
```
git push star-compose star-compose:main
git push kalteatz24 star-compose:star-compose
```
**Then trigger CI:**
```
gh workflow run "Any branch compilation." --repo The412Banner/star-compose --ref main
```

---

## ⚠️ WORKFLOW RULE — save before device tests / heavy jobs

We **device-test on the same physical device that hosts the working session** (PRoot/Termux + the app under
test are on one device). A device test, app install, screenshot/diff batch, or large agent/workflow can OOM and
crash the session, losing any un-saved context. **Always flush memory + this progress log + commit BEFORE running
a device test or heavy/memory-load job, and update continuously — not just at the end.** Memory + this log are the
durable checkpoint; the live session is volatile.

---

## 2026-06-27 (latest) — 🚀 Release 1.9.2 (stable patch)

Opacity fix device-confirmed working by user → cut **1.9.2** stable patch. Bumped `app/build.gradle`
versionCode 30→31, versionName 1.9.1→1.9.2 (`84b6bc1`), pushed main, dispatched `release.yml`
(run `28295471682`) with tag `1.9.2`, title `Bannerlator 1.9.2`, make_prerelease=`false`
(→ prerelease:false + make_latest:true). Workflow builds all 3 release APKs + generates/attaches
update.json (vc31) so the in-app updater offers it to stable users.

**What 1.9.2 ships (since 1.9.1):** full Vulkan effect suite — P1 SGSR + FSR1 upscalers (`5f5a4a0`),
P1b sharpen + render-scale, P1c CAS + fake-HDR + sharpness sliders, P2 FXAA/Toon/Color/CRT/NTSC screen
effects; Native-Rendering ↔ presets mutex; Linear default scaling mode; on-screen-controls overlay-opacity
drop-shadow fix (`1d9439e`). Plain numeric tag = stable per the versioning hard rule (patch X.Y.Z allowed
on explicit user request).

**✅ PUBLISHED — release run `28295879200` succeeded.** 1.9.2 is **Latest**, prerelease=false, all 3 flavor
APKs + `update.json` (vc31) attached → in-app updater offers the OTA to 1.9.1 (vc30) installs. Release body
rewritten to the polished 1.9.1-style layout (logo → ✨What's New → 📥Downloads → 🙏Credits → changelog)
with a full **graphics credits table**: SGSR ([SnapdragonStudios/snapdragon-gsr](https://github.com/SnapdragonStudios/snapdragon-gsr), BSD-3),
FSR/Sharpen ([GPUOpen-Effects/FidelityFX-FSR](https://github.com/GPUOpen-Effects/FidelityFX-FSR), MIT),
CAS ([GPUOpen-Effects/FidelityFX-CAS](https://github.com/GPUOpen-Effects/FidelityFX-CAS), MIT),
FSR-Fit/compositor blueprint ([utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative), GPL-3.0 — approach reimplemented, not copied),
and HDR/FXAA/Toon/CRT/NTSC/Color (upstream Winlator-Ludashi GLES2 effects ported to Vulkan). Attributions
sourced from the bundled shader headers at `app/src/main/cpp/winlator/*.frag`, which retain their upstream
license text. ⚠️First run `28295471682` FAILED at the update.json step — `release_notes` had literal
double-quotes that broke the bash `NOTES="..."` assignment (exit 127); re-ran with shell-safe plain notes.

**📝 Release-copy accuracy pass (post-publish body edits, no rebuild).** User flagged the marketing line
("only Winlator fork with both real spatial upscalers and a complete post-processing chain on Vulkan —
previously all OpenGL-only"). Verified in source against the other forks and corrected it:
- **GameNative** = FSR1-only on the Vulkan compositor (the blueprint we built on).
- **WinNative** (`/home/claude-user/winnative`) = **SGSR-only** on its Vulkan compositor (`cpp/winlator/vk/shaders/sgsr1.frag`
  + `SGSRUpscaler.java`) **plus an effect chain broader than ours** (sharpen/CRT/HDR/NTSC+NTSC2/Toon/ColorAdjust/
  ColorGrade/ColorBlind/Vivid/Scanlines/Pixelate/Natural); its `fsr.glsl` is only in the cnc-ddraw wrapper, not the compositor.
- So "real upscaler on Vulkan" and "full effect chain on Vulkan" are **NOT** unique to us; only **both SGSR *and* FSR1
  together** on the default path is. Also the upscalers were brand-new, not "previously OpenGL-only" (only the effects were).
- Rewrote the 1.9.2 intro: dropped the superlative, credited GameNative as FSR-on-Vulkan pioneer, noted SGSR exists in
  other Pluvia forks, claimed only the verified differentiator (both upscalers together). Also split What's New so
  **Render scale (supersampling)** is labeled *set before launch* (container/shortcut) vs the drawer-live upscalers/effects.
  Applied via `gh release edit 1.9.2 --notes-file` (APKs/update.json/Latest unchanged).

---

## 2026-06-27 — Native-mutex merge + on-screen controls opacity shadow fix

**1. Native-Rendering ↔ presets mutex MERGED to main.** User device-tested the latest `feat/vulkan-native-mutex`
build and confirmed it good. Fast-forwarded `main` `506ac6a`→`1c9c576` (`3ed78bb` mutex + `1c9c576` toast
black-box fix + Linear default scaling mode), pushed `origin/main`, deleted the feature branch local + remote.
The full Vulkan graphics program (P1 / P1b / P1c CAS+HDR / P2 effects / native-mutex) is now all on main.

**2. On-screen controls opacity bug FIXED** — `app/.../inputcontrols/ControlElement.java`, commit `1d9439e` on
main, CI run `28294667670` ✅ GREEN (all 3 flavors). **Device-test PENDING.**
- *Symptom (user, device screenshots 100% vs 6%):* at low Overlay Opacity the A–F keyboard strip fades fully, but
  the 4 compact keys MRB/BKSP/SPACE/ENTER keep a solid blue filled square while only their label text fades.
- *Root cause (pulled both screenshots to confirm):* NOT the fill paint — the GameHub `fillColor` already tracks
  `gameHubDim`. It was the **drop shadow**: the BUTTON draw path calls `paint.setShadowLayer(..., 0x401C85FE)`
  (hardcoded blue, alpha `0x40`) before the fill, and that shadow alpha never scaled with opacity. At low opacity
  the fill/stroke/text vanish but the blue glow persists — on the compact `SQUARE` keys it reads as a solid blue
  background; on the wide `ROUND_RECT` pills (A–F) it smears out and looks invisible. That asymmetry = the bug.
- *Fix:* added `int shadowColor = Color.argb((int)(0x40*gameHubDim*effectiveOpacity),0x1C,0x85,0xFE)` and used it
  in both `setShadowLayer` calls (trigger + non-trigger BUTTON paths). 0% opacity now truly vanishes. Only the
  BUTTON case has a shadow; STICK/D_PAD/TRACKPAD/RANGE_BUTTON unaffected.
- *Next:* CI green → install → device-test opacity at low values across both key shapes.

---

## 2026-06-27 — Phase 2: remaining GL screen effects → Vulkan post chain

**Branch `feat/vulkan-effects-p2`** (off `main` `71dceca`), commit `5dfcdbf` + fix `77c6b76`. Builds on the
now-merged P1/P1c Vulkan post-process framework. NOT merged.

**Device test (space scene):** all 5 effects work individually — Color/Brightness (washes out at 95), Toon
(edge outlines + posterized), CRT (RGB chromatic-aberration on stars), NTSC (horizontal chroma bleed). NTSC+CRT
2-effect combo renders clean. **Bug found + fixed (`77c6b76`):** with a *scaling* mode (SGSR/FSR/Sharpen/downscale)
active, the screen effects were silently dropped (toggles on, image clean) — `recordUpscalePasses`' local `fxOn`
only checked `cas||hdr`, so the scale pass treated itself as final and skipped the chain. Now includes all 7 effects.
(This also fixes P1c CAS/HDR, which had the same gap on the scaling path.) **Fix rebuilt: branch tip `aed6cde`, CI build `28290066760` ✅ green (all 3 flavors).** **Fix device-verified on the
space scene:** SGSR + CRT now shows the CRT fringing/scanlines on the upscaled image (was dropped pre-fix), and
SGSR + NTSC + CRT (3-deep chain) renders both effects cleanly — no black screen/corruption. **Phase 2 is
device-proven** (Color/Toon/CRT/NTSC visually confirmed + the scaling-chain fix; FXAA wired, subtle by nature).
Branch tip `0593385`. **Merged to main (ff `eee9d57`), branch deleted; artifacts build `28291121833` ✅ green.**

**Phase 3 (ReShade-style `.fx` engine) DROPPED 2026-06-27.** The upscalers (SGSR/FSR/Sharpen/downscale) are
resolution-reconstruction passes wired into the compositor — not ReShade-style fixed-res filters, so they stay hardcoded
(the headline differentiator). The cosmetic effects (CAS/HDR/FXAA/Toon/Color/CRT/NTSC) are fully covered by the curated
hardcoded set with better perf/reliability on mobile/Turnip. A data-driven engine only pays off for user/community
extensibility without rebuilds, which isn't a goal. **Effects work is complete.** Remaining: P4 (lean native-GLES2 GL path)
+ the queued overlay-opacity button-fill bug. The Vulkan renderer now carries the full stack: SGSR/FSR/Sharpen upscalers +
CAS + fake-HDR + FXAA/Toon/Color/CRT/NTSC, all on the default path.

Ported the 5 remaining GL-only screen effects onto the **same** Vulkan post chain as composable controls,
at full GL parity:
- **Color** — Brightness / Contrast / Gamma sliders (replicates `ColorEffect.java`: brightness `clamp(s/100,-1,1)`,
  contrast `clamp(s/100,0,2)` so negative contrast is a no-op like GL, gamma `clamp(0.1,5)`; neutral 0/0/1 ⇒ pass skipped).
- **FXAA · Toon · CRT · NTSC** — toggles (GL shader math ported verbatim).

**Locked canonical chain order** (best results): `composite → scale (SGSR/FSR) → FXAA → Toon → Color → CAS → HDR
→ NTSC → CRT → swapchain` — AA first, stylize/grade the clean image, sharpen, bloom, then the output-medium
emulation last (NTSC analog signal, then the CRT tube). The fixed 2-effect chain from P1c was generalized to an
ordered 7-effect list, ping-ponging `fx1`/`fx2` (2 buffers suffice); the last active effect writes the swapchain,
earlier ones write `offscreenRenderPass` fx targets (auto-barriers). Engages even at scaling mode 0/1/2.

5 new shaders (`fxaa/toon/color/ntsc/crt.frag` + compiled `*_frag.h`), 10 new pipelines (Off/Swap per effect),
all PC structs ≤ 28 B (≤ the 88 B shared range, unchanged). Plumbing mirrors P1c: 5 JNI entry points,
`VulkanRenderer.setScreenEffects(b,c,g,fxaa,toon,crt,ntsc)`, `XServerDialogState.onVulkanScreenEffectsApply`,
a "Screen Effects" subsection in the Vulkan drawer block, and launch-seed + callback wiring in
`XServerDisplayActivity`. **No-op safety:** with zero new effects enabled, control flow is identical to current main
(no regression to shipped P1/P1c behavior). Touched: `{fxaa,toon,color,ntsc,crt}.frag`(+`.h`),
`VulkanRendererContext.cpp/.h`, `vulkan_jni.cpp`, `VulkanRenderer.java`, `XServerDialogState.kt`, `XServerDrawer.kt`,
`XServerDisplayActivity.java`. `docs/render_upgrades_report.html` already shows P2 in-progress + the locked chain order.

---

## 2026-06-27 — Vulkan CAS + fake-HDR + sharpness sliders (Phase 1c) + on-device upscaler proof

**Branch `feat/vulkan-cas-hdr`** (off `feat/vulkan-upscaler-sgsr-fsr` tip `80c6d56`), commit `4fecbc6` +
docs `181500c`. **CI build `28287630767` ✅ GREEN** (standard/ludashi/pubg). NOT merged. Device-test pending.

**On-device upscaler verification (the resume from the smooth-blob test).** Re-ran the frozen-frame A/B on the
**DX11 "space" scene** (textured planet + coastlines + dense starfield), 720p container → 1080p panel, build 1.9.1:
the scaling modes are now clearly and usefully distinct (RMSE vs None ≈ 6× the smooth blob's <0.4%):

| Mode | RMSE vs None | On screen |
|---|---|---|
| Nearest | 0% (≡ None) | hard stair-step jaggies on the planet limb (point) |
| Linear | 1.79% | jaggies smoothed but whole frame softened |
| **SGSR** | 1.75% | edges cleaned, stars/detail stay crisp — sweet spot |
| **FSR / FSR-Fit** | 1.82% | same family as SGSR |
| **Sharpen** | 2.46% | brighter/punchier (RCAS), keeps base jaggies |

The earlier "no visible difference" was **bad test content** (smooth SDF blob, no high-freq edges), not a bug —
spatial upscalers are an edge-cleanup whose effect grows with the upscale ratio. "More RMSE" ≠ "better"; fidelity to
a native render is the goal. This test motivated the P1c sharpness sliders (strength was locked at 0.25).

**Phase 1c — three new composable Vulkan post controls + one rename:**
- **CAS toggle + "CAS Sharpness" slider (0–100, default 60)** — the same AMD CAS the GL path uses (`cas.frag`
  ported from `FSREffect.java`), layered on top of any scaling mode, runs even at native res.
- **HDR toggle** — the same fake-HDR (`hdr.frag` ported from `HDREffect.java`, HDRPower 1.30, binary).
- **"Sharpness" slider** for scaling modes SGSR/FSR/FSR-Fit/Sharpen — unlocks the real upscaler sharpness
  (was hard-coded 0.25 RCAS stops; default slider 75 keeps 0.25). SGSR `EdgeSharpness` moved const→push-constant.
- **GL "SGSR" → "Sharpen (CAS)"** — the GL toggle was never SGSR; it's AMD CAS sharpening at native res. Label-only.

Pipeline: `recordUpscalePasses` rewritten to chain `composite → scale → CAS → HDR → swapchain`, with optional
`fx1`/`fx2` intermediates ping-ponged through `offscreenRenderPass` (auto-barriers via its baked subpass deps; no
hand-rolled `vkCmdPipelineBarrier`). Cross-binding the swapchain-render-pass scale pipelines into an
`offscreenRenderPass` fx target is legal — both passes use `VK_FORMAT_R8G8B8A8_UNORM` (format-compatible). All PC
structs ≤ 88-byte range. Touched: `cas/hdr/sgsr.frag` (+ compiled `*_frag.h`), `VulkanRendererContext.cpp/.h`,
`vulkan_jni.cpp`, `VulkanRenderer.java`, `XServerDialogState.kt`, `XServerDrawer.kt`, `XServerDisplayActivity.java`.
Drawer-only / session-live (no DB persist), like the scaling mode. `docs/render_upgrades_report.html` updated with
the device-test results + P1c.

---

## 2026-06-27 — Vulkan spatial upscalers + sharpen + supersampling (Phase 1/1b)

Branch `feat/vulkan-upscaler-sgsr-fsr` (NOT merged). First fork with real SGSR **and** FSR1 upscaling on the
default Vulkan renderer, plus native-res sharpen and supersampling — in one app. Full design/provenance log:
`docs/SGSR_HDR_VULKAN_PLAN.md`; per-renderer summary `docs/render_upgrades_report.html`.

**Built a Vulkan post-process framework** in `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (offscreen
composite target at game res → post/upscale pass → swapchain), then layered features on it:

- **Scaling mode** (in-game drawer, live, Vulkan-only): None / Linear / Nearest / **SGSR** / **FSR** / **FSR (Fit)** /
  **Sharpen**. Modes via `VulkanRenderer.setUpscaler(int)` 0–6. Upscalers engage only when the game renders below
  display res; **Sharpen (6)** runs FSR RCAS at any res incl. native.
- **Supersampling ("Render scale")** — pre-launch container + per-game-shortcut setting Off/1.25x/1.5x/2x (stored via
  `renderScale` extra, no DB migration). Launch multiplies the X11 render res (aspect-preserve, clamp 7680x4320, even
  dims); compositor runs a new Lanczos-2 `downscale.frag` via `setHqDownscale(true)`. DSR/OGSSAA-style.
- **Per-renderer Graphics tab** — shows ONLY the active renderer's controls (OpenGL→SGSR/HDR+ScreenEffects;
  Vulkan→Scaling mode; SurfaceFlinger→"no enhancements" note) instead of greying out the rest.

**Shaders bundled** (offline-compiled to `.spv` C-array headers, license headers retained): SGSR 1.0 (Qualcomm,
BSD-3), FSR1 EASU+RCAS (AMD, MIT), Lanczos downscale. Approach for FSR-in-compositor / FSR-Fit credited to GameNative.
**HDR deferred** (Android WSI rarely exposes an HDR10 surface; revisit later).

**Commits:** `5f5a4a0` native upscaler + drawer · `28ab22d` per-renderer tab · `c3cbe49` Phase 1b sharpen+supersampling
· docs `33ad5f4`. **CI:** Phase 1 GREEN (`28276691564`, `28277238762`); full Phase-1b build `28277821185` ✅ GREEN
(all 3 flavors). **DEVICE-UNTESTED** — next step is on-device: sub-native upscale modes, native-res sharpen, 1.5x
supersampling, and per-renderer tab. Then **Phase 2** = port GL effects (FXAA/CRT/Toon/NTSC/color) to Vulkan.

CI for this repo is MANUAL: `gh workflow run build-artifacts.yml --ref <branch>`.

---

## 2026-06-25 — 1.9 STABLE cut ✅ (SurfaceFlinger renderer + DXVK 3.0 / Vulkan 1.4)

Merged `feat/surfaceflinger-renderer` to main (ff `d915798`), bumped to **1.9 / versionCode 29** (`eb39c2b`),
and cut **1.9 stable** (release run `28215839109`, `make_latest`, `update.json` attached → in-app updater
offers it on the stable channel). User explicitly authorized promoting to stable ("release 1.9").

**Shipped in 1.9:**
- **SurfaceFlinger (ASR) renderer** — experimental third host renderer, opt-in behind a reboot-risk warning
  dialog, default off. Ported from GameNative PR #1582 (André Vito) on StevenMXZ's scanout work.
- **DXVK 3.0 / Vulkan 1.4** option in the Turnip/Wrapper Driver Configuration.
- **Fixes:** per-game DXVK/VKD3D download sheet no longer hides behind the settings dialog; perf HUD labels
  SurfaceFlinger correctly.

**No imagefs reinstall required** — the 1.9 diff is purely app-side (renderer engine, a bundled native lib,
an env-var option, UI). No `imagefs/`, `assets/`, or `imgVersion` change; existing containers are untouched.

Release description was rewritten to the 1.8 layout with credits to GameNative (André Vito) + StevenMXZ for ASR.

---

## 2026-06-25 — SurfaceFlinger (ASR) renderer Phase 1 ✅ WORKING + device-proven (branch `feat/surfaceflinger-renderer`)

Took the SurfaceFlinger renderer from "selectable skeleton" (Phase 0) to a working scene compositor
that renders real D3D games fullscreen via Android SurfaceFlinger — no GL/Vulkan compositor. Ported
from GameNative PR #1582 (André Vito, on StevenMX's scanout work), adapted to Bannerlator's X-server API.

**Build-up:** scene engine (`ASurfaceRenderer` implements `WindowManager.OnWindowModificationListener`
+ `Pointer.OnPointerMotionListener`; `updateScene` walks the window tree under XLock → one SurfaceControl
layer per window via `nativeRegisterWindowSC`/`nativeUpdateWindow` in a begin/apply transaction; frames
pushed via `nativeSetWindowBuffer`) + additive `PresentExtension` ASR branch (routes the game AHB to the
SC; Vulkan/GL paths untouched).

**The hard debugging (device, Adreno 750, GTA IV + AIO Graphics Test, DXVK 3.0 + VK 1.4):** game ran
(audio) but showed a small top-left window. On-device logging (filtered logcat to a file — wine logs flood
the buffer) proved the whole Java chain worked (8000+ `ASR_Present`/pushes with valid AHBs, SC registered,
visible). Two stacked root causes, both fixed:
- **`Drawable.DRAWABLE_ASR_MODE`** (`98861c8`): port GameNative's flag so every Drawable is backed by a
  composer-compatible `GPUImage` AHB at construction (`data = AHB mapped memory`) — required for
  SurfaceFlinger to scan out. Wired `setAsrMode(true/false)` per renderer in `XServerView.initRenderer`.
- **Geometry** (`bf292bf`): `computeWindowRect` used the normalized GL `sceneScaleX` (~1.0), pinning the
  game at native size in the corner. Map through `viewTransformation.aspect` (surface-px-per-X-px, e.g.
  1.5×) + letterbox offset instead → fills the surface.
- **HUD** (`bf292bf` + `c4f6e5f`): wired `ASurfaceRenderer.setHudFrameTick` (FPS was blank — the tick was
  Vulkan-only) + fixed the renderer label (`XServerDisplayActivity:1710` binary vulkan?:OpenGL → +SF case).

**✅ DEVICE-PROVEN** (build `28213017959`, screenshot-every-5s/60s): GTA IV menu renders FULLSCREEN under
ASR, HUD reads `SurfaceFlinger | DXVK | … FPS: 398 2.5ms`, stable. **✅ GL/Vulkan regression pass:** all
three renderers render GTA fullscreen with correct labels/FPS (Vulkan 300, OpenGL 295, SurfaceFlinger 398)
— additive edits don't disturb GL/Vulkan, global ASR flag clears correctly. **✅ Debug logging stripped**
(`bb64f2b`, clean build `28213752314`).

Branch tip `bb64f2b`, carries the merged Vulkan 1.4 commit. **NOT merged** — awaiting call: merge to main vs
Phase 2 polish first (CPU desktop chrome compositing, cursor, fps-limit tearing — none block game render).
Process note: always `git push` BEFORE dispatching a CI build (a build was once cut from the pre-push commit;
verify via `gh run view <id> --json headSha`). 1.9-pre prerelease when cut.

---

## 2026-06-25 — DXVK 3.0 Vulkan 1.4 option ✅ merged + SurfaceFlinger renderer Phase-0 spike 🚧

**Context:** DXVK 3.0 shipped (all 4 `.wcp` flavors on The412Banner/Nightlies). DXVK 3.0 **hard-requires
Vulkan 1.4** (mandatory bump from 2.x's 1.3 — verified vs the release notes). The Turnip/Wrapper Driver
Configuration "Vulkan Version" dropdown capped at 1.3, so the wrapper exported `WRAPPER_VK_VERSION=1.3.x`
and DXVK 3.0 refused to init even on a VK1.4-capable driver.

**Fix — Vulkan 1.4 option (`785fe2b`, branch `feat/vulkan-1.4-dxvk3`, CI `28205826581` ✅ → ff-merged to
main 2026-06-25).** One-line: added `<item>1.4</item>` to `arrays.xml` `vulkan_version_entries`. Default
kept **1.3** (safe; 1.3-only drivers/A6xx unaffected) — user picks 1.4 manually for DXVK 3.0. Value flows
generically: dialog → `graphicsDriverConfig` `vulkanVersion=` token → `XServerDisplayActivity:2149` appends
the driver patch → `WRAPPER_VK_VERSION` env. **Proved load-bearing at the binary level:** disassembled the
bundled `libvulkan_wrapper.so` — `wrapper_GetPhysicalDeviceProperties` does `getenv("WRAPPER_VK_VERSION")` →
`sscanf` → `VK_MAKE_API_VERSION` → `str` into `pProperties->apiVersion` (offset 0), the exact field DXVK 3.0
gates on. Caveat: override is unconditional (no clamp to real driver max) → on A6xx (Turnip caps at 1.3)
picking 1.4 would lie to DXVK = footgun; default-1.3 avoids it. All 4 Nightlies DXVK release bodies updated
with the VK1.4 note + "Current version: 3.0". Driver side: The412Banner/Banners-Turnip builds report
**Vulkan 1.4.354** (Mesa main, `TU_API_VERSION=VK_MAKE_VERSION(1,4,..)` for chip≥7); device Adreno 750 (A7xx)
gets the 1.4 path. DEVICE-UNTESTED end-to-end (DXVK 3.0 launch w/ 1.4 selected).

**SurfaceFlinger renderer (ASR) — Phase-0 spike (branch `feat/surfaceflinger-renderer`, commit `068c3a5`,
CI `28208898551`).** 3rd host renderer ported from GameNative PR #1582 (André Vito; built on StevenMX's
scanout work). Confirmed our `cpp/winlator/VulkanRendererScanout.cpp` is **byte-identical** to GameNative's —
Steven's scanout foundation already in-tree. Spike = compiles + selectable (NOT a working compositor):
native `cpp/asurfacerenderer/` (JNI repackaged to `com_winlator_star_renderer`) → `libasurface_renderer.so`
via main CMakeLists; skeleton `ASurfaceRenderer` implements `HostRenderer` + loads lib + creates/destroys the
SF context on the surface lifecycle (per-window scene compositing deferred to Phase 1); selection wired in
`XServerView.initRenderer(String)` + `XServerDisplayActivity` (API<29 → Vulkan fallback) + "SurfaceFlinger"
added to container + per-game renderer dropdowns. NOT merged; device-test pending. See
`reference_gamenative_surfaceflinger_renderer` memory for the full Phase-1 plan.

---

## 2026-06-25 — 1.8 STABLE cut ✅ (updater picker fix + in-app OTA proven on a real stable)

Closed out the 1.8 cycle. One code blocker remained from the updater work, then cut stable.

**Picker correctness fix (`f1729a7`, branch `fix/updater-picker-sort`, CI `28200393133` ✅ → ff-merged to
main, branch deleted).** GitHub's list-releases API does **not** return pure newest-first — it pins the
`make_latest` release to the top, then lists the rest by date. Confirmed live: the API returned
`[1.7 (latest, published 01:46), 1.8-pre2 (published 20:30), 1.8-pre1, 1.6…]`. `UpdateManager.pickNewestWithUpdateJson`
took the **first** array element carrying `update.json`, which worked only because 1.7 had none (skipped).
Once 1.8 stable carried `update.json` + `make_latest`, it would have **shadowed a newer 1.9-preN** in the
prerelease channel. Fix = parse all releases into a list, `sortWith(compareByDescending { optString("published_at","") })`
(ISO-8601 sorts lexicographically = chronologically), then walk for the first with `update.json`.

**1.8 stable cut (user explicit go-ahead — required by the hard rule).** Bumped `versionCode 27→28` +
`versionName "1.8"` (`376e5fd`), dispatched `release.yml` with `release_tag=1.8 release_number=1.8
make_prerelease=false` → workflow auto-sets `prerelease:false` + `make_latest:true`. Release run
`28201699881` ✅. Verified: **Bannerlator 1.8 = Latest**, 1.7 demoted; assets = 3 flavor APKs +
`update.json`; **`releases/latest/download/update.json` now resolves to vc28/1.8** (stable updater
baseline live). Release body rewritten to match the 1.7 layout (logo / tagline / What's-New sections /
downloads table / credits / collapsible changelog) — intentionally **no reinstall-imageFS warning** since
1.8 is app-side only (HUD + updater), nothing changed in imageFS.

**✅ In-app OTA proven on a real stable cut:** a device running **1.8-pre2 (vc27)** auto-updated in-app to
**1.8 stable (vc28)** — the full updater loop (detect → download correct flavor → install) confirmed on a
genuine stable transition, not just pre→pre. Main tip `376e5fd`.

**1.8 ships:** GameHub-style perf HUD (2nd selectable overlay + live swap) · in-app updater (auto-install
+ optional prerelease channel) · setup-screen branding fix · updater picker fix. Next cycle → 1.9-preN
prereleases until an explicit stable call.

---

## 2026-06-25 (later) — In-app updater + prerelease channel (✅ device-proven, shipping via 1.8-preN)

Built a GitHub-releases-based **in-app update system** (modelled on the BannersComponentInjector /
BannerHub updater). Merged to main; being device-tested via prereleases.

**Core — `core/UpdateManager.kt`:** fetches `releases/latest/download/update.json`, compares
`BuildConfig.VERSION_CODE` (the integer is the source of truth, NOT the tag string), caches to
`cacheDir` (offline-safe), picks the flavor APK by `BuildConfig.APPLICATION_ID`, downloads via the
existing `HttpUtils` (reuses `DownloadProgressDialog`) and installs through the existing
`com.winlator.star.tileprovider` FileProvider. Install-permission guarded (`REQUEST_INSTALL_PACKAGES`,
Android 8+). **UI lives in 3 places:** Settings → new "Updates" section (readout, Check, Download &
install, Notify toggle); About dialog (latest-version line + "Update now"); app-wide amber home banner
(honours notify + skip-version). Manifest got `REQUEST_INSTALL_PACKAGES` + an `external-cache-path`;
`release.yml` generates + attaches `update.json` per release.

**"Include pre-releases" toggle (Settings, default OFF):** OFF = stable path (`releases/latest` only ever
resolves to a non-prerelease). ON = `checkViaApi` → GitHub releases API (`?per_page=30`, prereleases
included) → newest release carrying an `update.json` → its own asset URLs. **Gotcha: api.github.com 403s
without a `User-Agent`** → added one to `HttpUtils`' string fetch. `release.yml` gained a
`make_prerelease` input (sets `prerelease` + inverts `make_latest`); `update.json` now attaches to EVERY
release so the toggle has data.

**Versioning rule established (hard rule):** stables = plain numeric tag (`1.8`,`1.9`),
`prerelease:false` + `make_latest:true` — the ONLY thing the default updater offers. Everything between
stables = `X.Y-preN` (`1.9-pre1`,`pre2`…), `prerelease:true`, no make_latest, until explicitly promoted.
`versionCode` ticks up on EVERY build.

**Branding fix (`fix/setup-splash-branding`, merged):** the shared `DownloadProgressDialog` (first-launch
imagefs setup + HttpUtils downloads incl. the update download) hardcoded **"Star Bionic"** + **"Bionic
V1.1"** — caught from a device screenshot (pulled via root bridge). Title → `@string/app_name`
(per-flavor), version → `BuildConfig.VERSION_NAME` (dynamic, new `@+id/TVVersion`). Also cleaned the
leftover "Star Bionic" in the unused `about_dialog.xml`.

**Build log:**

| Step | Commit / Tag | CI Run | Result |
|---|---|---|---|
| Updater core (Settings/About/banner) | `41d7c06` | `28193511129` | ✅ green |
| Include-prereleases toggle + UA fix | `19b7e36` | `28195066124` | ✅ green |
| Merge + bump → 1.8-pre1 (vc26) | `ca87892` | `28195824422` (release) | ✅ published (prerelease) |
| Setup-screen branding fix | `b11814c` | `28197124387` | ✅ green |
| Merge + bump → 1.8-pre2 (vc27) | `2b10f53` | `28197773910` (release) | ✅ published (prerelease) |

**✅ DEVICE-PROVEN:** on the installed vc25 build, toggling Include-prereleases ON surfaced 1.8-pre1, and
Update downloaded + installed + launched it end-to-end. 1.8-pre2 (vc27) cut to re-test + carry the
branding fix. Stable 1.7 users untouched throughout (`releases/latest` still 404s for update.json since
1.7 predates the feature; no pre is make_latest). Main tip `2b10f53`.

**🐛 KNOWN latent bug (NOT yet fixed):** GitHub `/releases` API is not reliably newest-first — it hoists
the `make_latest` (stable) release to the top. `pickNewestWithUpdateJson` takes the first array element
with an `update.json`, which works while only prereleases carry it, but once a **stable + a newer
prerelease coexist**, the older stable would win over the newer beta for toggle-on users. **Fix before
cutting 1.8 stable: sort releases by `published_at` (fallback `created_at`) DESC before scanning.**

---

## 2026-06-25 — GameHub HUD: device-test crash fix + full in-game drawer mirror (branch `feat/gamehub-perf-hud`)

Continued the GameHub HUD port from P0–P4 (entry below) into on-device testing. Two follow-ups, neither merged.

**1. First-launch crash FIXED (`4808d51`, build `28179250039` ✅ green).** Installing the correct P4
build and enabling the GameHub HUD crashed the container + app the moment the overlay first refreshed.
Logcat (pulled via the root bridge):
```
FATAL EXCEPTION: Thread-6
android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that
  created a view hierarchy can touch its views. Expected: main Calling: Thread-6
    at com.winlator.star.widget.PerfHudView.update(PerfHudView.java:350)
    at ...XServerDisplayActivity$6.onUpdateWindowContent(:798)   ← X-server epoll thread (PresentExtension)
```
Root cause: `PerfHudView.update()` runs on the X-server epoll thread and called `requestLayout()` /
`invalidate()` directly — neither is thread-safe. `FrameRating` never hit this because it marshals via
`post(this)`. Fix = `post(refreshOnUi)` where `refreshOnUi = () -> { requestLayout(); invalidate(); }`.
The other two view-touching methods (`applyConfig`, `setVertical`) already run on the UI thread
(`runOnUiThread` at `XServerDisplayActivity:561` / tap handler). **Gotcha for future HUD work: anything
reached from `onUpdateWindowContent` is on the epoll thread → only `post()` / `postInvalidate()`.**

**2. In-game side drawer now fully mirrors the container dialog (`7437c3d`).** User wanted the same
settings/toggles in the in-game HUD tab. Before, `XServerDrawer.kt` → `HudContent` only had the classic
subset (scale/opacity + 6 toggles) and its `buildConfig()` **omitted `hudStyle` and every gamehub-only
key** — so changing anything in-game while the GameHub HUD was active stripped `hudStyle=gamehub` from the
saved container config (persisted via `onFpsConfigApply` → `setFPSCounterConfig` + `saveData` at
`XServerDisplayActivity:567`), reverting to the classic HUD on next launch. Rewrote `HudContent` to mirror
`FpsCounterConfigDialog` exactly: GameHub-style switch, FPS-graph / Power / GPU-model / dual-battery
toggles, skin/color/outline 3-stop chips (new drawer-styled `HudChipRow`, no FilterChip import), opacity
slider, and the **identical key set** (emits both classic + gamehub metric key names) so the drawer and
the pre-launch dialog are interchangeable. Metric/skin/scale/opacity changes apply **live** via
`onFpsConfigApply` → `perfHud.applyConfig` (UI-thread safe). The classic↔gamehub **view swap** still
applies on next launch only (the view is chosen at launch; a caption notes this) — live view-swap is a
possible follow-up.

Branch tip `7437c3d`. Combined build CI `28181338752` (in progress at time of writing). **NOT merged.**
Next: device-test the `28181338752` build — (a) enable GameHub HUD (master **Show FPS** must also be on),
launch → confirm renders without crash + tap flips orientation + metrics live; (b) open in-game drawer
HUD tab → confirm all GameHub controls present, apply live, and no revert-to-classic → tune dims/colors →
merge to main.

## 2026-06-25 — GameHub-style performance HUD port (branch `feat/gamehub-perf-hud`) — P0–P4 coded, device-test pending

A second, **selectable** in-game performance HUD modeled on GameHub 6.0.9's overlay, alongside the
existing `FrameRating`. User scope: **full parity** (17 controls), **per-container**, **all 3 skins**.
Clean reimplementation (our own View + data we already collect) — no GameHub code/assets copied.

Recon: 3 Explore agents over the jadx decompile (`/home/claude-user/gamehub-6.0.9-jadx`, GameHub =
Compose-Multiplatform `com.xiaoji.egggame`, obfuscated). HUD = plain Canvas/Paint/Path (no Compose/GL/
native for visuals; ref legacy View `o6m.java`). Two layouts (horizontal pill / vertical list), FPS line
graph (last 50 samples, peak clamped ≥60, 30fps guide), Classic/Neon/Mono skins, color-intensity
(0.72/0.88/1.0), text outline (off/1.0dp/1.4dp), scale 0.6–1.4, opacity. Most metrics already collected
by `FrameRating`; FPS comes from our own frame counter (GameHub's `libxserver.so` shm not needed).

- **P0 plan** `docs/GAMEHUB_PERF_HUD_PORT_PLAN.md`.
- **P1+P2** new `widget/PerfHudView.java` — self-contained Canvas view: both layouts, per-field colors,
  FPS graph, all 3 skins, color-intensity, outline, scale, opacity; parses the `fpsCounterConfig`
  KeyValueSet; `update()` frame-tick mirrors `FrameRating`; tap-toggle + drag. Standalone compile CI
  `28175068799` ✅ green.
- **P3** new `widget/HudMetrics.java` — shared collector: GPU% / temp / RAM / power+charging ported from
  `FrameRating`, **plus** overall CPU usage % (`/proc/stat` delta) and the dual-battery power fix (sums
  `battery`+`bms`+`main` `current_now` with abs()).
- **P0 wiring** `XServerDisplayActivity.java` — when `hudStyle=gamehub`, creates a `PerfHudView`
  (WRAP_CONTENT params) instead of the two `FrameRating` views; handled at every HUD site (create /
  show-hide / frame-tick update / live applyConfig / `toggleFpsHudOrientation`→`perfHud.setVertical` /
  DX-API detect→`setEngineLabel` / `_MESA_DRV_GPU_NAME`→`setGpuModel`). Classic path unchanged.
- **P4 config UI** `ContainerDetailScreen.kt` `FpsCounterConfigDialog` — "GameHub-style HUD" switch
  (`hudStyle`) + 9 metric toggles + dual-battery + scale/opacity sliders + skin/color/outline 3-stop
  FilterChip selectors (`HudToggleRow`/`HudThreeStop`); toggles emitted under both classic + gamehub key
  names; bounded scroll via `heightIn(screenHeight*0.7)`.

Config keys (per-container `fpsCounterConfig`): `hudStyle=classic|gamehub`, `hudMode`, `showFPS`,
`showFPSGraph`, `showCPUUsage`(+`showCPULoad`), `showGPULoad`, `showRAM`, `showPower`,
`showTemp`(+`showBatteryTemp`), `showEngine`(+`showRenderer`), `showGpuModel`, `hudDualBattery`,
`hudSkin=classic|neon|mono`, `hudColor=soft|mid|vivid`, `hudOutline=off|soft|strong`, `hudScale` (50–150),
`hudOpacity` (0–100), `hudTransparency` (classic).

Branch tip `b2fc55e`. Full artifact build CI `28176206476` (in progress at time of writing). **NOT merged.**
Next: verify build green → device-test (enable "GameHub-style HUD" in a container's FPS settings, launch,
confirm render + tap-flip + live metrics) → tune dimensions/colors on device → merge to main. Caveats:
HUD dimensions are first-guess; CPU% is overall-device (not per-game); in-game `XServerDrawer.kt` config
not extended (container-detail dialog only; orientation tap still works in-game).

## 2026-06-25 — Two HUD fixes merged to main (`ac6abbb`)

- **Fake FPS offset removed** (`19c9982`): stripped a fudge offset from `FrameRating.java` +
  `FrameRatingHorizontal.java` so the on-screen FPS reads true when native rendering is on.
- **Vertical HUD tap area fixed** (`ac6abbb`): the vertical `FrameRating` was added to the root
  `FrameLayout` with no LayoutParams, inheriting FrameLayout's `MATCH_PARENT × MATCH_PARENT` default — so
  its tap-to-toggle hit area covered the whole screen (a tap far from the overlay flipped orientation).
  Fixed with explicit `WRAP_CONTENT` + top-left gravity. Both built green (`28172970834`) and
  fast-forward-merged to main; `fix/fps-hud-tap-area` deleted. No release cut (still versionCode 25).

## 2026-06-24 — Steam detail-page revamp — branch `feat/steam-detail-revamp` (stacked on launch branch)

User asked to modernize the Steam game detail page. Picked all of: stored-info rows, last-played,
real playtime hours, bigger sheets (DLC/branch/cloud/add-home), and more robust/fluid download buttons +
accurate progress bars.

- **Chunk 1 DONE** (`SteamGameDetailActivity.kt` `ca90b96`): renders **developer / genres /
  metacritic** (already stored in our GameRow but previously hidden) + a **"Last played"** row from the
  install-dir timestamp (`relativeTime()`); reworked the downloads UI — **animated rounded progress bar**
  in a card with an **indeterminate "Preparing…" phase** and a separate %/bytes line; new
  `DetailActionButton` (48dp, rounded, disabled dimming) + `DetailInfoRow`. Pure UI/Compose.
- **Chunk 2 — real playtime hours: ABANDONED + REVERTED** (`ee85bf9`). Tried owned-games via JavaSteam
  unified messages (`SteamOwnedGames.kt`) but the API fought compilation (needed protobuf-java for the
  proto `GeneratedMessage` supertype; then `Player.getOwnedGames` return type is neither `Future` nor
  `CompletionStage` — `.get()`/`.toCompletableFuture()`/`.result`/`.body` unresolved). User chose to drop
  playtime recording; timestamp-based "Last played" (chunk 1) stays. Removed the file + Playtime row +
  protobuf-java dep.
- **TODO (remaining follow-up):** **bigger sheets** — DLC/depot manager, beta branch picker,
  cloud-save export/import/sync, add-to-home-screen (port from ref4ik `SteamLibrarySheets.kt` /
  `SteamGameActions.kt`).

**RESUME SNAPSHOT (2026-06-24, for crash recovery):** Two stacked feature branches, NONE merged, all
device-test-pending:
  • `feat/steam-pluvia-launch` (off main) — Pluvia Phase 1 coldclient launch, steps 1-4, commits
    `ff13265`/`1c6839d`/`f0b6106`/`73834d6`, all compile-green. Drawer "Steam" = unchanged store; adds
    emulation launch.
  • `feat/steam-detail-revamp` (stacked on the launch branch) — tip `ee85bf9` — detail revamp chunk 1
    only (chunk-2 playtime reverted). CI `28143904501`.
NEXT WHEN RESUMING: (1) confirm CI `28143904501` green; (2) device-test on the test device — Steam
download → add a steam_api game with "Steam emulation" → confirm Goldberg launch; detail page shows
dev/genres/metacritic/last-played + fluid progress; (3) optionally build the bigger sheets;
(4) then decide merge order (launch branch → main first, then rebase revamp → main). ref4ik clone at
`/home/claude-user/scratchpad/ref4ik`. Full design = `docs/STEAM_PLUVIA_PORT_PLAN.md`.

**imageFS reinstall?** No — for the Steam coldclient + detail revamp, updating 1.7 → next release needs
NO imageFS reinstall. The coldclient loader is a separate bundled APK asset extracted at runtime into the
existing imageFS (not baked into `imagefs.txz`), and the detail revamp is app-only. (Only carryover: if a
user never reinstalled imageFS for 1.7's ffmpeg-8, that 1.7 recommendation still applies.)

## 2026-06-24 — Pluvia Steam: Phase 1 (Goldberg/coldclient launch) — branch `feat/steam-pluvia-launch`

Implementing the recommended **Option A** from `docs/STEAM_PLUVIA_PORT_PLAN.md` — **UPGRADE the
existing Steam store** (browse/login/download/UI stay ours, unchanged from 1.7), adding only the
Goldberg/coldclient **launch** so SteamAPI titles actually run. ⚠️ User confirmed scope 2026-06-24:
NOT a full replacement (drawer "Steam" still opens our existing store). All work on the branch; NOT
merged; device-test pending.

- **Step 1 (`ff13265`)** — bundled asset `experimental-drm.tzst` (coldclient loader x32/x64 +
  emulated steamclient DLLs + extra_dlls; PE → host-arch independent) + `SteamClientManager.kt`
  (extracts it into the imageFs Steam dir). Ported from REF4IK/winlator-ref4ik- (GPL-3.0).
- **Step 2 (`1c6839d`)** — `SteamLaunchUtils.kt`: self-contained **offline** Goldberg helpers
  (writeColdClientIni, generateInterfacesFile, writeOfflineSteamSettings, backupSteamclientFiles,
  putBackSteamDlls, setupLightweightSteamConfig, skipFirstTimeSteamSetup, ensureSteamappsCommonSymlink).
  No dependency on ref4ik's Room SteamService; account read from our `steam_prefs`.
- **Step 3 (`f0b6106`)** — launch glue: `prepareColdClientLaunch` + `writeGameSteamSettings` +
  `StarLaunchBridge.addSteamGameToLauncher`/`writeSteamShortcut` (container picker → activateContainer →
  prepare env → write `.desktop` `Exec=wine C:/Program Files (x86)/Steam/steamclient_loader_x64.exe`
  with `game_source=STEAM` Extra Data). Prefix model = `activateContainer` repoints the `home/xuser`
  symlink → active container; corrected ref4ik's `skipFirstTimeSteamSetup` to take `imageFs.rootDir`.
- **Step 4 (`73834d6`)** — Compose UI: `SteamGameDetailActivity` shows a Compose AlertDialog
  ("Steam emulation" vs "Run .exe directly") after exe resolution → routes to the coldclient path or
  legacy raw path; `SteamGamesActivity` defaults its add paths to the emulation route.

Compile CIs: steps 1+2 `28141425805` ✅ green; steps 3 `28141874404` / 4 `28142049412` ⏳ pending.
**Next:** device-test a steam_api title (download → add with Steam emulation → boots under Goldberg)
→ then merge to main. Possible follow-ups: preferred-container, PICS LaunchInfo exe detection, cloud saves.

## 2026-06-24 — 🚀 Release 1.7

Cut **Bannerlator 1.7** (`versionName 1.7`, `versionCode 25`, commit `30c869c`). Version bumped in
`app/build.gradle`; splash screen reads `BuildConfig.VERSION_NAME` so it shows "V 1.7" automatically
(no hardcoded version strings anywhere). README version line + "What's New in 1.7" updated (1.6 notes
demoted to "Previously in 1.6"). Release build = workflow "Nightly Manual Release Build" run
`28140854161` (tag `1.7`, builds standard/ludashi/pubg release APKs) — ✅ GREEN. **PUBLISHED**
https://github.com/The412Banner/Bannerlator/releases/tag/1.7 with full notes; 3 assets each ~588.7 MB
(`Bannerlator-1.7-standard.apk` 588704729 B / `-ludashi.apk` 588704765 B / `-pubg.apk` 588704647 B).
Everything merged to main since the 1.6 tag is in this release:

- **Steam store — downloads fixed**: login-race guard (`9f6197e`) + BouncyCastle SHA-1 provider
  registration (`63e4366`). ⚠️ download-only; raw `wine exe` launch still has no steam-emu (DRM games
  may not run — see `docs/STEAM_PLUVIA_PORT_PLAN.md`).
- **Components installer (new)**: in-container Wine-dependency installer (Phase 2 file-drop + Phase 3b
  execute engine), copy_dll glob + arch-targeting fixes, win7/winXP set_windows, persisted Installed
  status.
- **On-screen controls**: overlay-opacity slider moved to in-game side menu, live, true 0–100 %.
- **FPS overlay**: tap to toggle orientation, live D3D API label (VKD3D vs DXVK).
- **Vulkan**: Advanced Vulkan / Graphics Driver dialogs scrollable.
- **Video**: full ffmpeg-8 libs bundled for winedmo.

⚠️ DEVICE-TEST status: Steam download fix, Components installer, and overlay-opacity were CI-green but
device-test was still pending/partial at release time.

## 2026-06-24 (late 2) — Steam download fixes + Pluvia/GameNative Steam-store recon & plan

**Steam download bug (✅ MERGED to main `63e4366`/`9f6197e`; compile CI `28139917719` ✅ green;
device-test pending).** User's Steam game downloads failed "Download failed: Unknown error".
Two distinct bugs found from the on-device `steam_debug.txt`:
1. *Login race* — `runInstall` started while `connected=true` but `loggedIn=false` (Steam CM
   connections cycle; re-logon after reconnect is async, license cache masked it). Manifest job
   timed out → `CancellationException`. Fix = new `SteamRepository.ensureLoggedIn(timeoutMs)` guard
   in `runInstall` (re-logon from saved token, wait up to 15s).
2. *SHA-1/BC* (the real download-killer, seen after re-login) — JavaSteam `DepotManifest.serialize`
   calls `MessageDigest.getInstance("SHA-1","BC")`; Android's built-in "BC" provider has SHA-1
   stripped → `NoSuchAlgorithmException`. App bundled `bcprov-jdk15on` but never registered it.
   Fix = static initializer in `SteamRepository` that removes stock BC + installs the full
   `BouncyCastleProvider`. Device-test of FlatOut 2 pending; then merge to main.

**Recon + plan: "Pluvia Steam" to replace the current Steam store.** Researched GameNative
(utkarshdalal/GameNative, GPL-3.0; local at `/home/claude-user/GameNative/`) and Pluvia
(oxters168/Pluvia, original, stalled). Key finding: **REF4IK/winlator-ref4ik-** (`com.winlator.cmod`)
already ported the GameNative/Pluvia Steam module into a Winlator **Cmod** fork — same lineage as
us — on the **same `in.dragonbra:javasteam:1.8.0`** we ship. It's an *upgrade*, not greenfield:
our download already works (post-fix); the real prize is the **Goldberg/coldclient launch model**
(ref4ik `SteamGameLauncher.kt`) — our store launches raw `wine exe`, so DRM/steam_api titles fail.
Recommendation = **Option A incremental** (Phase 1: Goldberg launch + loader assets +
`game_source`/`app_id` shortcut extras; then preferred-container, PICS LaunchInfo exe detection,
optional cloud-saves/updates). Full file-level seam map + risks (GPL-3.0, Goldberg asset arch,
bionic = coldclient only, A:↔Z: drive, Room↔SQLite) → **`docs/STEAM_PLUVIA_PORT_PLAN.md`** (this
commit). NOT STARTED — for down the road.

## 2026-06-24 (late) — Merged the day's branches to main + new Components installer fix

Rolled the day's feature branches onto `main` (linear rebase/ff, branches deleted). `main` tip now
`0ea1a84`. The 10 commits that make up today (oldest→newest):

1. `445f963` Components Phase 3b — execute engine for installer-based components (.NET/vcredist)
2. `1955f43` Components Phase 3b — auto-close installer sessions + cleanup
3. `c4399ce` Components — install win7/winXP via pure `set_windows` instead of N/A
4. `ce6561d` imagefs — bundle full ffmpeg-8 libs for winedmo video decode *(was the leftover "PENDING #2")*
5. `fe8e74d` HUD — live D3D API label (VKD3D vs DXVK) + tap overlay to toggle FPS orientation
6. `19ec967` HUD — tap overlay to toggle orientation live; dropped the settings dropdown
7. `de71493` Vulkan — make Advanced Vulkan / Graphics Driver dialogs scrollable
8. `16dc463` docs — components N/A backfill + quartz device-test (this log)
9. `4b9b0ad` Controls — overlay-opacity slider moved into in-game side menu (live, true 0–100 %)
10. `0ea1a84` **Components fix (new today)** — see below

**`0ea1a84` Components installer — two bugs fixed** (branch `fix/components-copy-and-installed-persist`,
rebased+ff to main, deleted; CI `28137352729` ✅ green; **device-untested**):
- **`copy_dll` glob was broken.** `copyMatching` built its regex as
  `Regex.escape(pattern).replace("\\*",".*")`, but Kotlin's `Regex.escape` uses `Pattern.quote`
  (`\Q…\E`), so a literal `"*"` file_name pattern compiled to `^\Q*\E$` — matching a file *named* `*`
  (nothing). The `*` components (`atmlib`/`devenum` + the pre-baked win7-SP1 set) set their DLL
  override but **never copied the DLL**. Fix: `pattern.split("*").joinToString(".*"){Regex.escape(it)}`
  → proper glob semantics. (`ComponentInstaller.kt`)
- **"Installed" status didn't persist.** It lived in an in-memory `remember{}` set, so it reset on
  every sheet close/reopen. Now persisted per container in SharedPreferences `component_installs`
  (key `c<id>`): loaded on open, written on each successful install. (`ComponentsSheet.kt`)

**Main artifact build:** triggered run **`28138274652`** (CI Build, artifacts only, `main`).
**Next:** device-test the glob fix + persisted-installed status (root bridge) → then cut 1.6.

## 2026-06-24 — Components: backfilled 15 "N/A" components + device-tested registration

**Backfill (winlator-contents `1f6eb72`).** The catalog had **17 components stuck at N/A**
(`needs-upstream`/`pending-manual`) because their source files were never mirrored. User supplied
the missing Microsoft files (`windows6.1-kb976932` Win7-SP1 x64+x86, ~1.5 GB; `powershell-wrapper.zip`),
covering **15 of 17**.

- The app has **no runtime cab engine** (verified: `ComponentInstaller`/`ComponentExecInstaller`
  handle neither `cab_extract`/`get_from_cab` nor `register_dll`; Phase 3b = installer-exec, not cab).
  The 12 already-working cab components were **pre-baked build-side** — so I followed the same method.
- **Pre-baked** the DLLs with `cabextract` straight out of the SP1 packages (all validated PE/`MZ`),
  packaged each as `<name>__libs.tar.xz` (`win32/`+`win64/` layout, gdiplus = 1.1.7601.17514),
  uploaded all **15** to release `system-libraries-v1` (~10 MB total — not the 1.5 GB raw `.exe`s,
  which would never install).
- **Rewrote** each component in `components.json` to the proven file-drop pattern
  (`archive_extract` + `copy_dll`(+`override_dll`)) — exactly like `devenum`/`riched20`; dropped the
  unsupported `register_dll` (native override inherits Wine's builtin COM registration). PowerShell
  repackaged into the same convention (its `powershell_core` dep was already `ready`).
- Catalog tally now **ready 112 / N/A 2**. Still N/A: `art2k7min` (needs AccessRuntime2007.exe),
  `vbrun6` (needs VB6 SP6 runtime).
- **No app rebuild needed:** `ComponentCatalog` fetches the catalog live (no cache) from
  `raw.githubusercontent.com/.../main/components.json`; installed builds see the 15 within minutes.

**Device test — quartz registration CONFIRMED (root bridge, `com.winlator.banner`).** Premise held:
every container prefix already has builtin Wine quartz fully COM-registered (**48** `quartz.dll`
InprocServer32 refs, FilterGraph CLSID `{e436ebb3-…}` + DirectShow Filters category present). Did a
reversible end-to-end install on `xuser-2` "P11 ARM" (backups `*.bak-comp`): native MS `quartz.dll`
→ system32 (1,572,352 B) + syswow64 (1,328,128 B), both `MZ`; inserted `"quartz"="native,builtin"`;
**48 CLSIDs still resolve to quartz.dll (now the native DLL), FilterGraph CLSID intact**. Only the
runtime-load under Wine/arm64ec (launch a DirectShow/FMV title) is left for a user-side check.

## 2026-06-24 — In-game overlay-opacity (controls) reworked + moved to side menu

On-screen controls "overlay opacity not working" → fixed + relocated. Was: draw curve
`0.5+0.7*opacity` (dead top ~29 %, never faint), `setOverlayOpacity()` never `invalidate()`d, editor
hardcoded 0.6. Now **linear 0–100 %** (0 % = fully invisible; accent-stroke alpha floors scaled with
opacity), live `invalidate()`, and the slider **moved from the Input-Controls profile screen into the
in-game side menu (Controls tab)** so it tunes the visible overlay live (`XServerDrawerState`
`overlayOpacity` + `onOverlayOpacityChange`; activity applies + persists). DEFAULT 0.4→0.75 (matches
old look under the new mapping). Branch `feat/ingame-overlay-opacity` `d3f2a8b`, compile CI
`28135045851` ✅ green. Next: device-test → merge for the next release.

## 2026-06-23 — Components installer: catalog + mirror DONE (app side next)

Building a **Components installer** for container settings — browse + install Wine dependencies
(mono, gecko, dotnet, vcredist, d3dx, …) into a container's prefix, the same set BannerHub/GameHub
offer (Bottles "Type 6 — System Libraries", 114 components).

- **Mirrored** all components' binaries to a new release **`system-libraries-v1`** on
  `The412Banner/winlator-contents` — **92 assets**, deduped by URL (shared payloads like the Win7-SP1
  packages referenced once, not per-component). Each asset named after its component.
- **6 not mirrored** (manual re-source list at `/sdcard/Download/winlator-components-needed.txt`):
  the 3 huge **Win7-SP1 platform-update** packages (shared by 14 components → referenced upstream) and
  3 dead/timed-out sources (`art2k7min`, `powershell`, `vbrun6`).
- **`components.json` committed + live** on winlator-contents
  (`raw.githubusercontent.com/The412Banner/winlator-contents/main/components.json`) — 114 components
  with full **Bottles-format install steps**, URLs rewritten to the mirror; `status` per component:
  **ready 97 / needs-upstream 14 / pending-manual 3**.
- **App side: Phase 2 + Phase 3a DONE & MERGED to main** (`4c732b8`, build `28072511822`).
  - **Phase 2** (`91ca6a3`): a "Components" browser in the Win Components tab (`ComponentsSheet`) +
    `ComponentCatalog` (reads the live components.json) + `ComponentInstaller` (file-drop Bottles
    steps → `system32`/`syswow64` + DLL overrides via `WineRegistryEditor`). **Device + root-verified:**
    installed `d3dcompiler_43`/`_47` — correct 64-bit→system32 / 32-bit→syswow64 + overrides set.
  - **Copy hardening** (`4c732b8`): `copy_dll` constrains source to the matching arch sub-tree.
  - **Phase 3a (pre-bake, no app change):** extracted the cab contents build-side with `cabextract`,
    hosted 12 components as `<name>__libs.tar.xz` on the `system-libraries-v1` release, and rewrote
    their catalog steps to file-drop. **22 components now installable** (10 file-drop + 12 pre-baked
    cab: d3dcompiler_42/46, xinput, xaudio2.7, msxml6, atmlib, riched20, vcredist6, winhttp, …).
  - The app reads components.json at runtime, so catalog updates are live without an app rebuild.
- **Still to do — Phase 3b:** the execute engine (`install_exe`/`install_msi` via launching the
  container session) for the +54 .NET / vcredist runtimes. Plan in memory `project_bannerlator_components_installer`.

---

## 1.6 RELEASE MANIFEST (in progress, since tag `1.5` / `dc74f67`) — NOT yet released

Everything queued for the next release:

**Merged to `main`** (device-confirmed):
1. On-screen dpad/stick multi-touch freeze fix (`fba6080`, merged `d1356d8`) — GitHub issue #5, reporter-confirmed.
2. In-app File Manager batch (`d086990`→`5521e0f`, +`ca26466`) — data-loss paste, silent Run, working dir, off-thread listing, copy-into-self guard, copy progress bar, PTR/scroll/file+exe icons, system-Back-up-one-dir, Run-executes-exe-in-container (`core/WinePath.kt`).
3. Per-game (shortcut) overrides for Renderer + Frame-Gen engine + FPS limiter (`08878be`).
4. Frame gen starts OFF in-game on every launch (`a669b8b`).

5. Standalone FPS limiter — guest-side X11 Present IdleNotify pacing (`bd990b2`) + lsfg≥2 guard (`4909549`); caps fps with Off / bionic-fg / lsfg-vk, both host renderers, live. ✅ merged to main (`a2ebd35`), GameNative credit (`0eadf16`).
6. Advanced Vulkan present settings now actually apply (native/presentMode/filter/swapRB) + renderer-dropdown label/gear fix. ✅ merged to main (`dcd9d47`).

**In progress (before 1.6, user's call)** — branch `feat/layer-download-menu`:
7. Compatibility-layer download menu rework — adrenotools-style cards, cloud opens the sheet directly, install-from-file in the sheet, Wine/Proton chips, in-use marker, byte-accurate install bar. See the dated section below.

Next: finish the download menu → merge → cut 1.6 (bump versionCode from 23 + splash).

---

## 2026-06-23 — Compatibility-layer download menu rework (branch `feat/layer-download-menu`, in progress)

Reworked the per-component download entry points into an adrenotools-style menu. The backend
(`ContentDownloadSheet` + `ContentsManager` + one remote `contents.json`) already covered all five
layers — the work is front-end consolidation + a real install bar. Confirmed design (HTML preview
first, then implemented):

- **Cloud icons replace the gears** on every layer (Wine/Proton, DXVK, VKD3D, Box64/WOWBox64, FEXCore);
  the cloud opens the download sheet **directly**. "Install from file" moved **into** the sheet header.
- **Adrenotools-style rows** — flat rows, `Memory` icon, name + "In use"/"Installed"/desc subtitle,
  trailing `CloudDownload`; chips restyled to the adrenotools `SourceChip` look. **Wine/Proton chips**
  split the compatibility-layer sheet; the others are single-type.
- **In-use marker** for the container's current version (Wine/Box64/FEXCore). Author/size are NOT in
  the manifest (`ContentProfile` has only type/verName/verCode/desc) — would need a manifest extension
  or a HEAD request; deferred.
- **Two determinate 0→100 bars** — blue "Downloading" (byte-accurate) and green "Installing", now also
  **byte-accurate**: `TarCompressorUtils` got a `CountingInputStream` + `OnReadProgressListener` and an
  `extract(…, total, listener)` overload reporting `bytesRead/total` off the compressed stream
  (single-pass, denominator = downloaded .wcp size); `ContentsManager.extraContentFile` got a matching
  overload; the sheet feeds it monotonically (ignoring the brief XZ-probe before the ZSTD pass).

VEGAS and the adrenotools GPU-driver downloader are left untouched. Kept as a centered `Dialog` for now
(bottom-sheet vs centered to be decided on device). First impl device-tested ("looks good").

**Build status (2026-06-23):** UI restyle + cloud-direct + file-in-sheet + in-use = `f4e551e` (CI
`28056314348` ✅). Byte-accurate install bar = `f9485ef` (CI **`28057297317`** — final combined build).
**⏸️ Resume:** verify `28057297317` green → download standard APK → device-test (cloud opens sheet
directly, adrenotools cards, Wine/Proton chips, install-from-file, in-use marker, real install bar) →
decide bottom-sheet vs centered + whether to add author/size → merge to main → cut 1.6.

---

## 2026-06-23 — Standalone FPS limiter (guest-side IdleNotify pacing) DEVICE-CONFIRMED ✅

Commit `bd990b2`, branch `feat/standalone-fps-limiter`, CI `28043133606` ✅ green.

The reworked limiter — guest-side X11 Present `IdleNotify` pacing in `PresentExtension`
(GameNative/Ludashi-3.1 mechanism: delay IdleNotify → DXVK blocks waiting for a free
buffer → the GUEST throttles), decoupled from the frame-gen layers — **caps fps in all
three FG modes: Off / bionic-fg / lsfg-vk.** Engine-agnostic, all-API, live in-game toggle.
This succeeds where the earlier host-side nanosleep pacer (`f8d7598`) failed (that one only
dropped frames at the compositor; the guest ran full-speed).

**✅ Confirmed on BOTH host renderers** — all 3 modes (Off / bionic-fg / lsfg-vk) cap fps on
the OpenGL host renderer AND the Vulkan host renderer.

**lsfg-mult≥2 guard wired (commit `4909549`, CI `28046025979`).** `lsfgGovernsFps()` returns true
when engine=lsfg + FG enabled + multiplier≥2; `applyFpsLimit()` clamps to 0 in that case, and
`reapplyFpsLimit()` runs from the lsfg branch of `onBionicFgConfigChange` so the guard engages the
moment the multiplier crosses 2. Rationale: lsfg paces itself when multiplying — layering our
IdleNotify limiter on top double-paces the stream (clamps the panel to the limiter value, kills the
FG gain, wastes GPU). Unaffected: bionic-fg, Off, and lsfg at 1× still cap.
> ⚠️ **SUPPORT NOTE:** if users report "the FPS limiter doesn't work / no cap" on **lsfg-vk**, this
> guard is the intended cause — the limiter is deliberately disabled while lsfg-vk multiplies
> (mult≥2). Documented in-code at `lsfgGovernsFps()`. Not a bug.

Remaining: guard CI green → merge `feat/standalone-fps-limiter` → main for 1.6.

---

## 2026-06-22 — lsfg-vk live reload CONFIRMED ✅ + Off→passthrough fix + engine badge + Task-Manager-on-Vulkan bug (diagnosing)

Session driven by live device questions ("which FG engine is running right now?").

**1. lsfg-vk 3× + LIVE RELOAD confirmed working (supersedes the 2026-06-21 "no live reload" finding).**
Probed the running game (`DOOMBLADE.exe`) on device: `liblsfg-vk.so` mapped into the game proc, env
`ENABLE_LSFG=1` / `LSFG_PROCESS=bannerlator-lsfg` / `LSFG_CONFIG=…/home/xuser/.config/lsfg-vk/conf.toml`,
`Lossless.dll` present. Logcat showed `lsfg-vk: Rereading configuration, as it is no longer valid.` →
`Reloaded configuration … Multiplier: 3` → `lsfg-vk-framegen: Entering Device::Device` — i.e. the
mtime-watch → OUT_OF_DATE → swapchain-recreate reload mechanism (GameNative fork `.so`) DOES fire on our
DXVK→vkd3d→wrapper_icd→Turnip stack now. Panel present rate ~138–143 fps on a 144 Hz panel = base ~46 × 3.
DXVK HUD correctly shows the BASE rate (~46) because lsfg-vk inserts frames downstream of DXVK's counter
(HUD≠panel is expected, and is itself proof FG works). Two conf.toml files exist: live
`home/xuser/.config/lsfg-vk/conf.toml` (read by the layer) and a stale `home/xuser-1/.config/bionic-fg/conf.toml`
(ignored by lsfg; its `fps_limit` field isn't an lsfg option). Minor cleanup candidate.

**2. Off-bug found + fixed.** Installed APK (`7f7ffb5`) predated the Off fix (`80e238a`), so in-game "Off"
wrote `multiplier = 2` (`Math.max(2,0)`) → still 2× frame gen. `80e238a` writes `1` (true passthrough).
Built off-fix APK (run `27941385132`, label `1.3-lsfg-offfix`, ✅ green). PROVEN on device by live-editing
the running conf.toml `multiplier 2→1`: reload fired (`Rereading` → `Multiplier: 1`), FPS dropped to native
~21–27. So `multiplier=1` = genuine off.

**3. Engine badge in in-game FG drawer (commit `740e779`).** Per user, replaced the standalone
"Frame Generation (AI)" header with a title + engine badge row — `Frame Generation  [● bionic-fg]` (green
dot = layer running this session; swaps to `lsfg-vk`; "Off" when disabled). No double labeling (user picked
the "Engine badge" layout). Plumbed `XServerDrawerState.frameGenEngine` ← `container.getFrameGenEngine()`,
wired in `XServerDisplayActivity` next to the other FG drawer-state setters.

**4. Task Manager reports nothing on the Vulkan host renderer (SAME container works on OpenGL). Diagnosing.**
Game runs fine; `winhandler.exe` (the process-list backend) is alive; no app crash. The new off-fix build's
Task Manager refreshes on a render-independent 1s timer and STILL shows empty on Vulkan → ruled out the
UI-tick/copyArea theory. `setupTmCallbacks`/listener registration are NOT renderer-conditional in source, so
nothing intends to disable it on Vulkan. Added WinHandler diagnostic logging (commit `e75d1d4`, tag
`WinHandlerTM`): logs INIT handshake, each `listProcesses` send + sendPacket result, every received request
code, and `GET_PROCESS` replies. One Vulkan run with Task Manager open will split it: `GET_PROCESS` arriving
but UI empty → Compose/StateFlow update problem on Vulkan; no `recv` at all → guest not replying / INIT never
happened. NOT yet root-caused.

**Builds:** off-fix `27941385132` (`1.3-lsfg-offfix`) ✅; logging-only `27943043968` (`1.3-tmlog`) ✅;
combined `27943884565` (`1.3-tmlog-badge` = off-fix + WinHandler logging + engine badge) — in progress.
Branch `feature/lsfg-vk-engine` tip `740e779`, pushed, NOT merged.
**NEXT:** deliver combined APK to `/sdcard/Download` + arm `WinHandlerTM` logcat → user opens Task Manager on
Vulkan once → read logs to root-cause → fix → merge to main.

---

## 2026-06-21 (night) — lsfg-vk DEVICE TEST: works (2×) but live in-game reload does NOT on our stack ⏸️ RESUME

Installed the test APK (`Bannerlator-1.3-lsfg-vk-standard.apk`, testkey, updates over current), imported a
`Lossless.dll`, selected lsfg-vk in a container, launched DOOMBLADE.

- ✅ **lsfg-vk loads + runs on our Turnip/Proton stack** (GameNative fork `.so` `93fa20bb`). Log
  `/sdcard/Download/lsfgvk_ingame_test.txt`: `Loaded configuration for bannerlator-lsfg` / `Shaders extracted` /
  layers init / AHB + swapchain contexts. **2× frame gen confirmed** (DXVK 39.4 → overlay 78). Opt-in
  `ENABLE_LSFG` gate, conf.toml driving, and `LSFG_PROCESS=bannerlator-lsfg` all work.
- 🐞 **Bug found + fixed (commit `80e238a`):** the in-game "Off" = drawer multiplier 0 (frame-gen stays
  enabled), and the callback did `Math.max(2,0)=2` → forced 2× on Off. Fixed to `mult>=2 ? mult : 1`
  (passthrough). Only matters on relaunch though, because…
- ❌ **Live conf.toml reload does NOT fire on our stack (definitive).** Bypassed the app entirely: `sed`'d the
  running game's conf.toml to `multiplier=1`, confirmed mtime changed → **zero `Rereading configuration` lines,
  FPS stayed 2×** (capture fresh through 19:36, not a gap). Then a fullscreen toggle (swapchain recreate) →
  still no change. So GameNative's mechanism (layer returns `VK_ERROR_OUT_OF_DATE_KHR` on conf change → DXVK
  recreates swapchain → layer re-reads) is **not propagating through DXVK → vkd3d → wrapper_icd → Turnip**.

**Decision pending (A vs B):**
- **(A, recommended)** ship lsfg-vk as **launch-time** config: restore the per-container Multiplier + Flow
  control (was in `1997a55`, removed in `7f7ffb5`); in-game drawer hides/labels lsfg FG controls as
  "relaunch to apply"; bionic-fg keeps its working live in-game control.
- **(B)** deep-dive why OUT_OF_DATE doesn't recreate/reload here (instrument a debug layer; uncertain).

**Resume state:** branch `feature/lsfg-vk-engine` @ `80e238a` (pushed, NOT merged). Build works; engine
selector + gray-out + DLL picker + lsfg-vk 2× all functional. Only the lsfg live in-game tuning is the gap.

## 2026-06-21 (evening) — lsfg-vk as a SECOND, selectable frame-gen engine (recon → spike → integration → in-game live)

New feature on branch **`feature/lsfg-vk-engine`** (off `main`; NOT merged): add **lsfg-vk** (Lossless
Scaling FG, PancakeTAS lineage) alongside the existing **bionic-fg** so users pick the engine per
container. User supplies their own `Lossless.dll` (we bundle nothing proprietary).

**Recon (3-repo lineage):** lsfg-vk source = `FrankBarretta/lsfg-vk-android@b55b182` (Android AHB port);
built by `The412Banner/LLS` CI (NDK 27, `-DLSFGVK_ANDROID_WINE=ON`, 2-line color-fix patch). Ludashi-plus
itself has NO lsfg-vk (it dropped the feature). LLS run `25313482636` has a clean prebuilt artifact (NO
`libc++_shared` dep — the libc++ blocker was only the old dead APK `.so`).

**Device spike (✅ SUCCESS):** staged the LLS prebuilt `.so` + manifest + the user's `Lossless.dll` into a
container's imagefs, env `LSFG_LEGACY=1 LSFG_DLL_PATH=… LSFG_MULTIPLIER=2 BIONIC_FG_DISABLE=1`. After fixing
my guest-relative DLL path → full Android path (guest is NOT chrooted), **DOOMBLADE ran with lsfg-vk doing
2× frame gen** (DXVK 39.3 → overlay 79). DEFINITIVELY confirmed lsfg-vk (not bionic) via live `/proc/*/maps`:
`liblsfg-vk.so` mapped r-xp in the game procs, `libbionic_fg.so` mapped in zero. lsfg-vk = plain implicit
layer, NO wrapper-ICD hack needed (unlike bionic-fg). ⚠️ it HARD-EXITS (bricks the container) if it can't read
the DLL → the feature must gate on a valid DLL.

**In-game LIVE control (GameNative recon):** stock lsfg-vk reads config once. GameNative makes mult/flow apply
mid-game by rewriting `conf.toml` → their **forked layer** watches the file mtime in its present hook → returns
`VK_ERROR_OUT_OF_DATE_KHR` → game recreates swapchain → layer re-reads config (the ~100ms "pause" the user sees
= that rebuild). NO SIGSTOP, no app-side swapchain call. So we **re-vendored GameNative's fork** (`.so` md5
`93fa20bb`, has the `Rereading configuration` mtime-watch) and drive via **conf.toml**, not `LSFG_LEGACY` env.

**Integration (commits `a8974d9`,`1b96cb4`,`1997a55`,`7f7ffb5`):**
- Layer staged opt-in: `assets/lsfg-vk/{liblsfg-vk.so,manifest}` + `ImageFsInstaller.installLsfgVkLayer()`;
  added `enable_environment ENABLE_LSFG=1` to the manifest so the on-by-default upstream layer can't brick
  other containers (loads only when a container selects lsfg-vk).
- Launch wiring (`XServerDisplayActivity`): engine==lsfg → `ENABLE_LSFG=1` + `LSFG_CONFIG=<home>/.config/lsfg-vk/conf.toml`
  + `LSFG_PROCESS=bannerlator-lsfg` + `writeLsfgConfig()` ([global].dll + [[game]] mult/flow/present=fifo),
  gated on the imported DLL existing; else bionic-fg path unchanged. Mutual exclusion = one engine's enable env.
- Data model (`Container`): `getFrameGenEngine/setFrameGenEngine/isLsfgEngine` ("off"/"bionic"/"lsfg"; default
  migrates legacy `frameGenEnabled`).
- UI: container FG control = engine selector ONLY (Off/bionic-fg/lsfg-vk); **lsfg-vk grayed out until a
  `Lossless.dll` is imported** (`LabeledDropdown` gained `disabledOptions`). DLL picker at the bottom of
  Settings (SAF → **copies into `filesDir/lsfg-vk/Lossless.dll`**, loads from the copy).
- **Unified in-game control:** the single in-game multiplier toggle + flow slider drive WHICHEVER engine the
  container runs — `onBionicFgConfigChange` branches to `writeLsfgConfig` for lsfg (live reload via the fork's
  mtime-watch); drawer activated for lsfg containers. No per-container mult/flow control.

**APK signing:** all builds now signed with the AOSP **testkey v1+v2+v3** (`keystore/testkey.p12`, commit
`e09ac71`) so releases/updates install over previous installs (one-time uninstall on first testkey build).

**Build:** test APK run `27920491173` @ `7f7ffb5` (label `lsfg-vk-ui-test3`) IN PROGRESS. ⚠️ Dispatch-race
gotcha hit again — verify `git ls-remote` tip == local before `gh workflow run`. **STILL UNVERIFIED ON DEVICE:**
GameNative fork `.so` loading on our Turnip stack + the live conf.toml reload mid-game (the test APK verifies).

## 2026-06-21 (later) — sticky sync-failure force-disable fix + new `.so` `9136405c` STAGED/SWAPPED

Found (via Jason's PR #6 `layer.cpp:1093` follow-up) that the runtime auto-disable wasn't sticky:
`noteFenceTimeout` stored the kill in `conf.enabled`, which the hot-reload path overwrites wholesale
(`st.conf = newConf`) — so any conf.toml touch (notably the in-game flow slider) silently re-armed
framegen on an ICD path already proven sync-incompatible. **Fix:** new sticky `SwapState::framegenForceDisabled`
that hot-reload never clears; QueuePresent gate honours it; clean re-attempt point stays a swapchain
recreate. Fork `The412Banner/bionic-fg`@`bannerlator-android-wrapper-icd-fixes` commit `c861d8c`
(4th unpushed commit ahead of PR remote `ac2f5c0`). App branch `feature/bionic-fg-pr-followups`
`6807e83` (patch regen 597→608L, applies clean).

**Build gotcha logged:** first dispatch (run 27916226393) checked out the STALE tip `56c6735`
(dispatched a beat too fast after push → byte-identical `4b99b2d1`, no change). Re-dispatched
27916284710 on confirmed remote tip `6807e83` → **new `.so` md5 `9136405c`**, `will NOT re-enable`
log string confirmed compiled in. RULE: after `git push`, verify `git ls-remote` tip == local before
`gh workflow run`, or the run may use the old ref.

**Hot-swapped onto device** (no reinstall — pure layer-internal change, no conf/JNI/ABI surface):
`imagefs/usr/lib/libbionic_fg.so` 4b99b2d1→`9136405c` (owner u0_a484, chmod 600); backups `.bak_c8e4`
(shipped) + `.bak_prev` (4b99b2d1). App force-stopped. Staged `/sdcard/Download/libbionic_fg_9136405c.so`.

⚠️ **Test scope:** the sticky-disable only ENGAGES on a sync-incompatible ICD (6 consecutive fence
timeouts). On the known-good Proton 11 + Turnip path framegen never force-disables, so the fix is
logic-verified by code; on-device this run is a **regression check** = confirm 2× still works + flow
slider still hot-reloads cleanly (i.e. my change didn't break the happy path). ⏳ awaiting user launch;
logcat → `/sdcard/Download/bionicfg_sticky_disable_test.txt`.

---

## 2026-06-21 — PR #6 review-followup `.so` (`4b99b2d1`) DEVICE-CONFIRMED ✅

Hot-swapped the new layer (`libbionic_fg_4b99b2d1.so`, build run 27915620310, fork HEAD `5f4fc03`)
into the installed app's imagefs — **no reinstall** — and tested on device. **All green.**

- **Frame gen 2× confirmed** (game "FOLLOW MY LIGHT", Screenshot_20260621-155950): DXVK base HUD
  **29.9 fps** → Banner overlay **60 fps** = 2.00×. Adreno 750, GPU 58%.
- **FPS-limiter pacing confirmed** (Screenshot_20260621-160008): in-game FPS Limiter "Limit FPS" ON,
  Max FPS = 30 → overlay ≈ **60–63 fps** (cap × mult). The relaxed clamp + new variance-aware
  pacing path work.
- **New container:** ran on **Proton 11.0-5-arm64ec** + Mesa Turnip v26.2.0 + zink (prior proofs
  were on older containers). Layer loads clean: `VK_LAYER_BIONIC_framegen Device created` →
  `SwapchainState provisioned 1280x720` → `FramegenContext ready mult=2 model=0
  graph=model0-full-of-chain`; config hot-reload flow 0.90→1.00 live.
- **CPU-temp HUD fix holds** (79.7 / 86.6 °C real values).
- **No app crash.** No FATAL/SIGSEGV/tombstone for `com.winlator.banner` or `libbionic_fg`. Display
  went `committedState OFF` at 15:59:41 (backgrounded); the ANR storm after 16:01 is
  `com.qti.diagservices` = AYANEO firmware nvkeeper/diag bug, unrelated. The Claude session is what
  died (known device-launch issue).
- **Benign noise (cleanup candidate):** `failed to load layer libVkLayer_LSFGVK_frame_generation.so:
  libc++_shared.so not found` — that's the *old* LSFGVK-named layer in the APK `lib/arm64`, not our
  layer. Our `VK_LAYER_BIONIC_framegen` (imagefs/usr/lib) loads fine. Strip-or-bundle-libc++ before
  the PR push.

Log `/sdcard/Download/bionicfg_pr_followups_test.txt`; `.so` staged `/sdcard/Download/libbionic_fg_4b99b2d1.so`.

**NEXT (now unblocked):** post the 3 drafted replies to Jason (1093/1334/manifest:17) → push fork
`5f4fc03` to PR #6 branch `bannerlator-android-wrapper-icd-fixes` (one clean batch) → build APK from
`feature/bionic-fg-pr-followups` for the app-side niceties (next reinstall). GN #1443 verify deferred.

---

## 2026-06-20 — 1.3 shipped public + upstream PR review cycle (in progress)

**1.3 released (public).** Repo flipped public (secret-scanned first), `Bannerlator 1.3` release
created (run 27878418873) with all 3 flavors; release notes credit xXJSONDeruloXx with links to
[bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) and [PR #6](https://github.com/xXJSONDeruloXx/bionic-fg/pull/6).
README updated to 1.3 (fixed standard package id `com.winlator.banner`, version line, added the
Frame Generation feature section).

**Upstream PR #6 opened and reviewed.** Fork `The412Banner/bionic-fg` branch
`bannerlator-android-wrapper-icd-fixes`. Jason (xXJSONDeruloXx) left 9 inline review comments;
all 9 answered. He's constructive and wants it in with refinements. Work plan (layer fixes land on
the fork branch → update the PR; app-side conf-key changes land on a new Bannerlator branch
`feature/bionic-fg-pr-followups`):

- **A — separate copy vs generated fence-timeout counters.** Real bug he found: the shared counter
  reset on copy-fence success, so generated-frame timeouts could never reach the disable threshold.
  **Done** (fork commit `4c259f8`): split into `copyFenceTimeouts` / `genFenceTimeouts`, each reset
  only on its own fence type.
- **B — timeout rework + split the generated-frame deadline from the sync-incompatibility recovery
  path.** **Done** (fork commit `345e35e`): frame-budget-scaled sync timeout (4× base interval,
  clamped 200 ms–1 s, else 500 ms), disable threshold 2→6, and a separate shorter generated-frame
  cadence deadline (~2× the output interval) that just skips a late frame without counting it as a
  sync failure. A+B compile-validated via CI.
- **C — fps_limit ergonomics:** justify/relax the 10–200 clamp + add an `fps_limit_enabled` bool so
  the value is remembered across toggles. (layer + app) — *todo*
- **D — optional even-pacing** of generated presents to `1s/(base×mult)` behind an opt-in flag,
  off by default. (layer + app) — *todo*
- **E — document** ENABLE+DISABLE precedence in the manifest (disable wins). Trivial. — *todo*

**Device-creation regression (his main concern) — direction changed after his reply.** The JNI/native
path is unchanged (`Device::create()`, owned + destroyed); only the Vulkan-layer path uses
`Device::wrap()` of the app's device. Jason confirmed **GameNative also uses the layer path**, so our
change reaches it too. Arch root cause: standalone mode runs a second VkDevice and hands frames across
it via `VK_QUEUE_FAMILY_EXTERNAL` transfers, which need a shared queue/timeline; a wrapper ICD bridges
each guest device to Turnip as a separate host context, so that cross-device sync never completes →
hang. Single-device avoids it by running interpolation on the app's own device (intra-device sync).

**Plan:** likely **no flag** — single-device is probably the correct layer default for both stacks.
**Critical next step: verify single-device on GameNative [PR #1443](https://github.com/utkarshdalal/GameNative/pull/1443).**
If it's clean there, make single-device the layer default; only add an init-time `single_device`
launch arg (it can't hot-reload — device is created at init) if GN regresses. Fork commits A–B are
held locally and not yet pushed to PR #6; push as one batch after the GN question is settled and our
stack is re-tested.

---

## 2026-06-20 — bionic-fg FRAME GEN + FPS LIMITER: merged to main, version → 1.3

**FPS-limiter pacing device-confirmed (Phase 4 complete).** The deferred pacer (built into
the bionic-fg Vulkan layer, `.so` md5 `c8e4b188`) ran on device with `fps_limit=30`: base
DXVK frames locked at ~30 while the on-screen overlay stepped 60 → 90 → 122 as the in-game
FG selector went 2× → 3× → 4× (i.e. on-screen = limit × multiplier). Proven on the OpenGL
host renderer (log `bionicfg_fpslimit_test.txt` + 5 screenshots). The pacer sits at the top
of `BionicFG_QueuePresentKHR`, gated `!st.inPresent && fpsLimit>0`, so it throttles only the
app's real frames; generated frames (presented with `inPresent` true) bypass it. Verified the
pacer `.so` is bundled in the shipped APK asset (`assets/bionic-fg/libbionic_fg.so` md5
`c8e4b188`), not hand-staged.

**Merged to main (`ddf46fb`).** Merged `feature/bionic-fg-framegen` (HEAD `f39b96a`) into main.
One conflict in `.github/workflows/build-bionic-fg.yml` (both branches had it) resolved by
keeping the feature branch's version (the one with the patch-apply step). Stale
`BIONIC_FG_UPSTREAM_REPORT.md` working-tree edit reverted (falsely said single-device crashes;
run6 disproved it). Feature branch kept until the upstream PR is cut.

**CI: artifacts build now produces all 3 flavors (`eb30d1b`).** Previous standard-only build was
a workaround for an OOM (exit 143) caused by packaging the ~588MB APKs in parallel.
`build-artifacts.yml` now runs `assembleStandardDebug assembleLudashiDebug assemblePubgDebug
--no-parallel --max-workers=1` with a larger Gradle heap (serialized packaging) and requires
all 3 uploads. Run 27877129792 confirmed green with all 3 artifacts.

**Version relabel → 1.3 (`9ee5cb2`, `90ce00b`).** Fresh-install Android "All files access"
permission screen showed `1.4-marcescene` (the APK `versionName`) under "Bannerlator Bionic".
Fixed: `app/build.gradle` `versionName "1.4-marcescene"→"1.3"`, `versionCode 20→21`; splash
`SplashScreen.kt` "V 1.2"→"V 1.3" (color unchanged — stays grey `0xFFAAAAAA`); about/main
`MainActivity.kt` stray "V 1.0"→"V 1.3". Build run 27877738210 label `1.3` dispatched on main;
standard APK to be delivered to `/sdcard/Download/Bannerlator-1.3-standard.apk`.

---

## 2026-06-19 — Vulkan/DXVK/vkd3d BLACK-SCREEN FIX (✅ both renderers device-confirmed)

**Symptom:** native Vulkan + DXVK(d3d8-11) + vkd3d(d3d12) rendered BLACK at full FPS on BOTH
host renderers; OpenGL/DirectDraw/D3D7 fine.

**Root cause:** marcescence shipped the native scanout machinery but left the AHB (DRI3 modifier
1255) present path UNWIRED, and the GL renderer had NO AHB->GL (EGLImage) sampling at all
(GPUImage textureId==0 -> black). Confirmed via device logcat (tag "Dri3": modifier 1255 ->
AHB path taken; pixmaps imported fine -> not an import failure).

**Fix (commit `7d5c9f8`, build label "vkfix3" run 27848179202):** ported proven wiring from
GameNative (utkarshdalal/GameNative, local ~/GameNative):
- `renderer/GPUImage.java` + `cpp/winlator/gpu_image.c`: GPUImage(int socketFd) now locks
  (valid getStride + virtualData) and gained EGLImage support (createImageKHR =
  eglGetNativeClientBufferANDROID + eglCreateImageKHR + glEGLImageTargetTexture2DOES). AHB
  allocated BGRA_8888 (matches X depth-32 / GL_BGRA -> correct colors). unlock-before-release.
- `xserver/extensions/DRI3Extension.java`: setDirectScanout(true) + getStride() width.
- `xserver/extensions/PresentExtension.java`: 3-branch present (Vulkan native+scanout=FLIP /
  Vulkan=COPY via onUpdateWindowContentDirect / GL+SHM=copyArea); relaxed depth 24<->32.
- `XServerDisplayActivity.setupUI` + `VulkanRenderer.setInitialNativeMode`: wired the
  previously-dead Vulkan toggles (native / presentMode / filterMode / swapRB).

**Device results (vkfix3):** ✅ OpenGL host renderer (native Vulkan 1432fps + D3D12/vkd3d
1748fps, correct colors). ✅ Vulkan host renderer (native Vulkan 1449fps, correct colors).

APKs delivered: `/sdcard/Download/Bannerlator-vkfix3-standard.apk` (md5 eebfe339…),
`-pubg.apk` (md5 a7c0acb3…).

---

## 2026-06-19 (PM) — Native Rendering toggle: device-tested + HUD-freeze fix

User tested the previously-untested Native Rendering+ toggle on the Vulkan host renderer
(AIO Graphics Test, native-Vulkan cube). Two findings:

**1. Windowed content stretches/distorts — EXPECTED, not a bug.** With the graphics test in a
*window* (sub-screen), enabling Native Rendering blits the active swapchain straight to the full
device surface, stretched (LUNARG cube visibly squished). Direct scanout (`onUpdateWindowContent`
FLIP branch → `nativeScanoutSetBuffer`) has no aspect-correct dst path for sub-screen windows;
the aspect-preserving letterbox (`ViewTransformation`, `Math.min`-based) only applies on the
copyArea path. ✅ With the test app **maximized to fullscreen**, native rendering renders the cube
correctly proportioned (FPS still climbs 582→743→…). So for real fullscreen games — the actual use
case — native rendering is correct. Windowed-distortion is a known limitation, not release-blocking.

**2. Perf HUD freezes in Native Rendering — FIXED (commit `f724ec2`).** When Native Rendering was
on, the horizontal perf HUD bar (Vulkan|DXVK|CPU|GPU|…|FPS) froze — values stopped updating while
the game kept animating. Root cause: commit `779967a` wired `hudFrameTick` (which drives
`frameRatingHorizontal.update()`, `XServerDisplayActivity.java:1345`) only into
`onUpdateWindowContentDirect` (the COPY present path). Native rendering uses the FLIP/scanout path
(`PresentExtension.java:154` → `VulkanRenderer.onUpdateWindowContent`), which never called it. Fix:
added `if (hudFrameTick != null) hudFrameTick.accept(window.id);` in the scanout-delivered branch
of `onUpdateWindowContent` (`VulkanRenderer.java:495`), mirroring the COPY path — ticks once per
presented game frame in native mode.

**Build:** `build-artifacts.yml` run `27852720105` (artifacts-only, no release, APK label `hudfix`),
triggered off `main` @ `f724ec2`. ⏳ standard APK to be dropped in `/sdcard/Download/` when green;
HUD fix in native mode still ⏳ device-unconfirmed.

**Next:** device-confirm HUD ticks (and shows a sane FPS — native mode pauses X-side rendering) →
then cut a tagged release (pick a real version; vkfix3/hudfix are just build labels). Cleanup:
graphicsDriverConfig has two competing dialog formats writing the same field.

---

## 2026-06-19 (PM) — New neon gamepad launcher icon (corner-clip fix)

User supplied a new icon (neon gamepad + magenta chevron + white L-bracket + corner stars on
black, white rounded border) — `/storage/emulated/0/Download/ADM/file_…588.jpg`, 1254×1254 — and
reported the previously-installed icon had its **border corners clipped** by the launcher's
round/squircle mask (device screenshot 20260619-194013, drawer): that old icon was **full-bleed**
(art edge-to-edge) so adaptive masks cut the corners.

**Done (commit `19d62f8`, all 15 files = 5 densities × ic_launcher + ic_launcher_round + adaptive
foreground):**
- Legacy `ic_launcher.png` / `ic_launcher_round.png` (mdpi 48 … xxxhdpi 192) = full image, exact.
- Adaptive foreground (mdpi 108 … xxxhdpi 432) = full art fit into the **safe zone** (~66% of
  canvas, centered, transparent pad) so the launcher mask only ever trims the black margin — the
  white border + corner stars stay fully visible under ANY mask shape. Generated with ImageMagick.
- Adaptive background was already `@color/ic_launcher_background` = `#000000` (matches art bg) → no
  change needed; seamless (image black bg blends into adaptive black).
- No per-flavor icon overrides → shared `main/res` applies to all 3 flavors (standard/ludashi/pubg).
- User explicitly chose "full white border visible" over a bigger near-full-bleed (88%) variant.

**Build:** `build-artifacts.yml` run `27853329322` (artifacts-only, label `neonicon`, off `main` @
`19d62f8`). ✅ standard APK delivered `/sdcard/Download/Bannerlator-neonicon-standard.apk` (md5
`13056a0e2845f56ca34b00405abd3afb`). ⏳ icon device-unconfirmed (note: Android caches launcher icons
— reboot / clear launcher cache if old clipped icon persists).

---

## 2026-06-19 (PM) — 🏁 RELEASE 1.2 + README features/download button

**Released Bannerlator 1.2** (`release.yml`, run `27853787348`): tag `1.2`, marked **Latest**,
non-prerelease, 3 flavor APKs attached. Standard APK → `/sdcard/Download/Bannerlator-1.2-standard.apk`
(md5 `e5d5689ecf4b9b1a91596d70658a752f`). `release.yml` inputs = `release_tag` / `release_title` /
`release_number` / `release_notes` (publishes `make_latest:true`, supersedes 1.1).

1.2 changelog (commits since `1.1` tag): Vulkan/DXVK/vkd3d black-screen fix (`7d5c9f8` + lead-ups
`b7d4f3a`/`c4d252c`), Native Rendering+ toggle wired (`779967a`), HUD-freeze fix on FLIP path
(`f724ec2`), GL-only effects greyed out on Vulkan (`ba06bc3`/`df3a5c7`), DXVK/VKD3D/Vegas version-list
refresh (`00a2544`), new neon icon (`19d62f8`).

**Splash version** bumped `V 1.1` → `V 1.2` (`SplashScreen.kt:164`, commit `a598584`) BEFORE the
release so it shipped in 1.2.

**README** (commits `ae5d9b7` + `18eab3d`): added **✨ Full Features** section (7 grouped categories —
Windows compat / graphics layers / renderers / containers / games+input / UI+overlay / builds; every
item cross-checked against actual code, NO invented features like AI frame-gen which isn't in this
app); bumped Information-table version V 1.0→V 1.2; added a centered shields.io **Download button**
linking to `/releases/latest` + a "Download" entry in the nav row.

**GameNative render-fix credit** (commit `8792f6d`): expanded the README GameNative credit row to
state its rendering pipeline was the **reference used to fix/rewire the render options** (AHB present
path → Vulkan/DXVK/VKD3D on both renderers: GPUImage socket-buffer lock + EGLImage sampling, DRI3
direct-scanout, Present FLIP/COPY branches, Native Rendering+ scanout). Also appended a **Credits**
section to the **1.2 GitHub release notes** (`gh release edit 1.2`) crediting GameNative (utkarshdalal)
for the same, linking back to the README Credits.

**Next:** device-confirm HUD-tick + new icon on 1.2; cleanup graphicsDriverConfig's 2 competing
dialog formats.

---

## 2026-06-19 (PM) — bionic-fg frame generation: recon + branch `feature/bionic-fg-framegen`

New feature kicked off: integrate [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) (Android/
bionic Vulkan frame-generation layer, LSFG lineage — same engine GameHub ships as `libGameScopeVK.so`)
as **(a)** a per-container option and **(b)** a live in-game side-menu control.

**Author permission GRANTED** (xXJSONDeruloXx). Terms: (1) credit in README, (2) if source goes in
tree do it as a **git submodule** (his preference), (3) feedback/PRs welcome.

**Recon findings:**
- Guest Vulkan goes through a **wrapper ICD** (`wrapper_icd.aarch64.json` + `GALLIUM_DRIVER=zink` +
  `WRAPPER_*` at `XServerDisplayActivity.java:1823–1861`) bridging to the **Android bionic GPU driver**
  via **adrenotools** — exactly the context bionic-fg targets.
- Tree already has frame-gen groundwork: `app/src/main/cpp/lsfg-vk/` (stub CMakeLists, build excluded)
  + root `build-lsfg-android.sh`. bionic-fg = the bionic-targeted sibling.
- **All 3 CI workflows already use `submodules: recursive`** → adding the submodule needs NO CI change.
- In-game drawer (`XServerDrawerState.kt`) uses StateFlow+Runnable; Native Rendering toggle is a
  turnkey template for a Frame-Gen toggle. bionic-fg **hot-reloads its TOML** → in-game live control
  by rewriting the config (multiplier 0=off / 2–4× / model 0-1 / flow_scale).
- ⚠️ **Critical unknown:** does the wrapper expose a `VkSwapchainKHR` for the layer to hook, or does
  it AHB-export with no WSI swapchain? Resolve with a verification spike BEFORE building UI.

**Deliverable this session:** branch `feature/bionic-fg-framegen` created off `main`; full recon +
phased job task list written to **`BIONIC_FG_INTEGRATION_REPORT.md`** (Phase 0 honor-terms → 1 native
build → 2 spike/de-risk → 3 container setting → 4 in-game menu → 5 polish/release/give-back).

**Next on branch:** Phase 0 — add bionic-fg as a submodule under `app/src/main/cpp/bionic-fg` +
README credit; then the Phase 2 verification spike (gate the rest on it).

### ✅ Phase 0 DONE (2026-06-19) — author terms honored
- Added **bionic-fg as a git submodule** at `app/src/main/cpp/bionic-fg` (his preference), pinned at
  `4f71770`; new root `.gitmodules`. CI needs no change (all 3 workflows already `submodules: recursive`).
- **README credit**: added xXJSONDeruloXx / bionic-fg to the Credits table (frame-generation layer,
  in-tree as a submodule with permission) + a "Frame Generation (bionic-fg)" row in the upstream-stack
  table.
- ⚠️ Submodule has **no LICENSE** → carry to Phase 5.2 (ask author before bundling in a release).
- **Next:** Phase 2 verification spike — build `libbionic_fg.so`, hand-wire one container's env +
  `conf.toml`, launch a DXVK game, confirm via logcat whether the layer engages (wrapper exposes a
  `VkSwapchainKHR`?) BEFORE any UI work.

### ✅ Phase 1 DONE (2026-06-19) — native build
- `build-bionic-fg.yml` (standalone, NDK 26.1.10909125 + cmake 3.22.1, arm64-v8a/android-26). Run
  **27854824786 ✅** → artifact `bionic-fg-arm64` (1.65 MB) = `libbionic_fg.so` (ELF aarch64,
  Android 26, NDK r26b — matches our minSdk 26) + `VkLayer_BIONIC_framegen.json`. Workflow also added
  to `main` (dispatch-only/inert) since workflow_dispatch requires the file on the default branch.
- **Manifest insights (sharpen the spike):** layer is **IMPLICIT** (`enable_environment
  BIONIC_FG_ENABLE=1`); `library_path ../../../lib/libbionic_fg.so` → manifest goes in
  `…/share/vulkan/implicit_layer.d/`, .so in sibling `lib/`. Implicit layers are found via system
  dirs / `VK_ADD_IMPLICIT_LAYER_PATH` (NOT `VK_LAYER_PATH`, which is explicit-only). Hooks
  vkGetInstance/DeviceProcAddr → sits above the ICD.
- **Refined crux:** bionic `.so` CANNOT load in the glibc guest (box64/Wine) → must load **host-side**
  where the wrapper-ICD server runs Turnip via adrenotools. Spike must confirm (1) host loader honors
  the implicit layer, (2) a real `VkSwapchainKHR` exists to hook (vs AHB-export = nothing to
  intercept). Copy GameHub `libGameScopeVK` imagefs placement.
- Artifact staged for device spike: `/sdcard/Download/bionic-fg/{libbionic_fg.so,VkLayer_BIONIC_framegen.json}`.
- **Next:** Phase 2 spike (needs a device launch — log to crash-surviving `/sdcard/Download/*.txt`
  per the device-launch rule; hold the actual launch for the user).

### Phase 2 spike — runbook written + device recon (2026-06-19)
- **`BIONIC_FG_SPIKE_RUNBOOK.md`** written: full device steps (place `.so` in `imagefs/usr/lib`,
  manifest in `implicit_layer.d`, `BIONIC_FG_ENABLE=1` + `VK_LOADER_DEBUG=all` in container Env Vars,
  conf.toml at guest `$HOME/.config/bionic-fg/`, logcat→`/sdcard/Download/bionicfg_spike.txt`) +
  a decision table + cleanup.
- **Device recon (root bridge):** guest uses its **own glibc Khronos loader**
  `imagefs/usr/lib/libvulkan.so.1.4.315` and already loads **glibc** implicit layers — **MangoHud**
  (`VK_LAYER_MANGOHUD_overlay_aarch64`) + `libutil_layer` — from `usr/share/vulkan/implicit_layer.d/`.
  MangoHud's manifest is structurally identical to bionic-fg's (enable_environment, `../../../lib/…`,
  same proc-addr hooks) → **discovery works**.
- ⚠️ **KEY HYPOTHESIS:** our NDK/**bionic** `libbionic_fg.so` (links libandroid/liblog/Android
  libvulkan) **will not load in the glibc guest loader** → real path is a **glibc aarch64 build**
  (new Phase 1.5), mirroring how MangoHud + GameHub `libGameScopeVK` ship in imagefs. The spike's
  Test A is designed to confirm this fast (expect a `cannot open shared object`/`libandroid` load
  error), then pivot.
- Standard pkg confirmed `com.winlator.banner` (pubg `com.tencent.ig`); both installed.
- **Next (user):** run the spike launch per the runbook; report the log signals.

### Phase 2 spike ARMED on device (2026-06-19) — awaiting user launch
- Test workload = **DOOMBLADE** (user's choice; real DX11/DXVK game) in **container 2 "P10arm"**
  (`imagefs/home/xuser-2`, the ACTIVE container; arm64ec, DXVK 2.4.1+vkd3d, Turnip, FPS HUD on).
- Staged via root bridge: `libbionic_fg.so` → `imagefs/usr/lib/`, `VkLayer_BIONIC_framegen.json` →
  `imagefs/usr/share/vulkan/implicit_layer.d/`, `conf.toml` (multiplier=2) →
  `imagefs/home/xuser-2/.config/bionic-fg/`. All chown'd back to app uid `u0_a478`.
- Container 2 `.container` env vars **prepended** `BIONIC_FG_ENABLE=1 VK_LOADER_DEBUG=all`
  (backup at `.container.bak_bfg`).
- Logcat capture → `/sdcard/Download/bionicfg_spike.txt`.
- **REVERT if needed:** restore `imagefs/home/xuser-2/.container.bak_bfg`; rm the staged
  `.so`/manifest/`.config/bionic-fg`.
- ⚠️ Expectation: bionic `.so` likely fails to load in glibc guest loader (ABI) → then Phase 1.5
  glibc build. Spike confirms.

---

## How to Resume a Session

1. Read this file top to bottom
2. Find the **Current Job** section — it tells you exactly what to do next
3. Check the last commit hash matches what's on GitHub before continuing
4. Run CI after every commit. Do not continue to the next job until CI is green.

---

## Completed Work (Pre-Plan)

Full Jetpack Compose migration of all screens and dialogs is complete.  
See `COMPOSE_MIGRATION_REPORT.md` for the full record.

**Last migration commit:** `6dff28e`  
**Bug fixes after migration:**
- `85b1e57` — controller name text + drive letter dropdown fix
- `6537038` — External Controllers header text fix
- `3323810` — Customizable theme: 8 presets + HSV color picker (AppearanceScreen)
- `beee77b` — Appearance entry missing from nav drawer (AppDrawer hardcoded)

**Latest commit:** `beee77b`  
**Latest CI:** run `24568759383` — in progress at time of writing

---

## Feedback Fix Plan

Source: Developer feedback comparing v1.1 (old Java/XML) vs Compose version.  
8 issues identified. Listed in execution order (smallest/highest impact first).

---

### Job 1 — Help and Support (BROKEN)
**Status:** ✅ COMPLETE — commit `93d0326`, CI run `24569312463`  
**File:** `app/src/main/java/com/winlator/cmod/ui/AppDrawer.kt`  
**Problem:** `onClick = { /* TODO: open help URL or dialog */ }` — tapping does nothing  
**Fix:** Replace the TODO with a Compose `AlertDialog` containing:
- GitHub repo link: https://github.com/The412Banner/star-compose
- Issue tracker link
- A "Close" button
Or alternatively open a URL via `Intent(Intent.ACTION_VIEW, Uri.parse(url))`.  
**Effort:** 30 min  
**Commit message:** `fix: implement Help and Support dialog`

---

### Job 2 — About Dialog (MISSING CONTENT)
**Status:** ✅ COMPLETE — commit `d18cae6`, CI run `24569669122`  
**File:** `app/src/main/java/com/winlator/cmod/MainActivity.kt` — `AboutDialog()` at bottom of file  
**Problem:** Current dialog is 4 lines of plain text. Missing: app icon/logo, version name, Wine/Box64/FEX versions, credits list.  
**Fix:** Rebuild `AboutDialog()` as a proper Compose `Dialog` (not AlertDialog — needs more space) with:
- App icon (R.mipmap.ic_launcher_foreground)
- App name + version (read from `BuildConfig.VERSION_NAME` + `BuildConfig.VERSION_CODE`)
- Powered-by section: Wine, Box64, FEX-Emu, Turnip
- Credits section with contributor names
- Close button  
**Effort:** 45 min  
**Commit message:** `feat: rebuild About dialog with logo, version, credits`

---

### Job 3 — Container Creation Loading Indicator
**Status:** ✅ COMPLETE — commit `2e5f4a1`, CI run `24570142005`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainerDetailScreen.kt` — Save button / confirm action
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainerDetailViewModel.kt` — `saveContainer()` or equivalent
**Problem:** When user taps Save on a new container, it creates silently with no progress feedback. On slow devices this looks like a freeze.  
**Fix:**
1. Add `isCreating: StateFlow<Boolean>` to `ContainerDetailViewModel`
2. Set it true before container creation starts, false when done
3. In `ContainerDetailScreen`, show a full-screen semi-transparent overlay with `CircularProgressIndicator` + "Creating container…" text when `isCreating == true`
4. Disable the Save button while creating  
**Effort:** 45 min  
**Commit message:** `feat: add loading overlay during container creation`

---

### Job 4 — Settings Theme Mismatch (Dark Mode Toggle Broken)
**Status:** ✅ COMPLETE — commit `44a4bdb`, CI run `24571445525`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/theme/AppThemeState.kt`
- `app/src/main/java/com/winlator/cmod/ui/theme/ThemePreset.kt`
- `app/src/main/java/com/winlator/cmod/ui/theme/Theme.kt`
- `app/src/main/java/com/winlator/cmod/MainActivity.kt`
**Problem (two parts):**
1. `SettingsFragment` uses Light XML AppTheme while the rest of the app is dark Compose — mismatched look inside the Settings screen
2. The `dark_mode` SharedPreferences toggle in SettingsFragment has no effect on the Compose UI — `WinlatorTheme` always uses `darkColorScheme()`  
**Fix:**
1. Read `PreferenceManager.getDefaultSharedPreferences(this).getBoolean("dark_mode", false)` in `AppThemeState.init()` and store it as `isDarkMode: StateFlow<Boolean>`
2. Add a light variant to each `ThemePreset` (or use Material3 `lightColorScheme()` as the light base)
3. `AppThemeState.colorScheme` flow emits light or dark scheme based on `isDarkMode`
4. Register a `SharedPreferences.OnSharedPreferenceChangeListener` so toggling dark mode in Settings updates the flow in real time without restart
5. For SettingsFragment XML mismatch: set `android:theme="@style/Theme.AppCompat.DayNight"` on the fragment's parent or override the fragment background to match Compose surface color  
**Effort:** 1.5 hours  
**Commit message:** `fix: wire dark_mode preference to Compose theme + fix Settings appearance`

---

### Job 5 — Sort Shortcut List
**Status:** ✅ COMPLETE — commit `00dc6a5`, CI run `24571836336`  
**File:** `app/src/main/java/com/winlator/cmod/ui/screens/ShortcutsScreen.kt`  
**Problem:** No sort option — shortcuts always appear in filesystem order  
**Fix:**
1. Add a sort icon button in the top bar or a sort dropdown in the shortcuts screen
2. Sort options: Name A→Z, Name Z→A, Last Played, Container
3. Store selected sort in `ShortcutsViewModel` (persisted to SharedPreferences)
4. Apply sort to the `shortcuts` StateFlow before emitting  
**Effort:** 1 hour  
**Commit message:** `feat: add sort options to shortcuts list`

---

### Job 6 — Import/Export Container
**Status:** ✅ COMPLETE — commit `8477b65`, CI run `24572308670`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainersScreen.kt`
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainersViewModel.kt`
**Problem:** The old `ContainersFragment` had import/export container options. These are missing from the Compose version.  
**Fix:**
1. Add "Import Container" and "Export Container" options to the container long-press context menu (already has Duplicate/Delete)
2. Check original `ContainersFragment.java` (deleted) — refer to git history if needed, or find the logic in `ContainerManager.java`
3. Export: zip the container directory → write to Downloads or user-picked location via `ActivityResultContracts.CreateDocument`
4. Import: user picks a zip via `ActivityResultContracts.GetContent` → unzip to containers directory → reload list  
**Check ContainerManager.java for existing import/export methods first** — they likely already exist.  
**Effort:** 1.5 hours  
**Commit message:** `feat: add import/export container to containers screen`

---

### Job 7 — Add Shortcut from External Storage
**Status:** ✅ COMPLETE — commit `546d25e`, CI run `24577265773`  
**Files:** `ShortcutsViewModel.kt`, `ShortcutsScreen.kt`

---

### Job 8 — Shortcut List Layout Toggle (Grid / List)
**Status:** ✅ COMPLETE — commit `546d25e`, CI run `24577265773`  
**Files:** `ShortcutsViewModel.kt`, `ShortcutsScreen.kt`

---

## Execution Order

```
Job 1 → Job 2 → Job 3 → Job 4 → Job 5 → Job 6 → Job 7 → Job 8
```

Each job: implement → commit → push both remotes → trigger CI → wait for green → update this log → proceed.

---

## Build Log

| Job | Commit | CI Run | Result | Date |
|---|---|---|---|---|
| Pre-plan: Appearance drawer fix | `beee77b` | `24568759383` | ✅ green | 2026-04-17 |
| Job 1: Help and Support dialog | `93d0326` | `24569312463` | ✅ green | 2026-04-17 |
| Job 2: About dialog rebuild | `d18cae6` | `24569669122` | ✅ green | 2026-04-17 |
| Job 3: Container creation loading overlay | `2e5f4a1` | `24570142005` | ✅ green (fix: `67844d2`) | 2026-04-17 |
| Job 4: Dark mode pref + Settings theme fix | `44a4bdb` | `24571445525` | ✅ green | 2026-04-17 |
| Job 5: Sort shortcuts list | `00dc6a5` | `24571836336` | ✅ green | 2026-04-17 |
| Job 6: Import/Export container | `8477b65` | `24572308670` | ✅ green | 2026-04-17 |
| Job 7+8: Import shortcut + grid/list toggle | `546d25e` | `24577265773` | ✅ green | 2026-04-17 |

---

## Current Job

**→ ALL 8 JOBS COMPLETE** ✅

Last commit: `546d25e`  
Last CI: `24577265773` ✅ green
