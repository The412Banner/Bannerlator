# ReShade — Tier 2 (in-game live add) & Step 4 (depth) implementation plan

**Status:** DESIGN / not started. Written 2026-07-14. Grounded in a two-agent recon of the
current working tree (main `7ee2ec98`, i.e. post-2.6.1) plus upstream `DadSchoorse/vkBasalt`
HEAD `4f97f09`. Everything below is design; no code has been written for either item.

**Scope of this doc:** the two remaining ReShade *features* — Tier 2 and Step 4. It does **not**
re-spec the shipped work (Step 3 platform + Tier 1 multi-effect switching, both device-proven and
merged). The two pre-release housekeeping items (codegen sweep of the ~109-effect catalog, and the
release-notes credits to DadSchoorse / Pipetto-crypto / StevenMXZ) are tracked separately and only
referenced here.

**Confidence legend used throughout:** **PROVEN** = verified in shipped code / on device ·
**COMPILES** = code exists and builds today · **WOULD-WORK** = sound by construction, not yet built ·
**RISK** = identified hazard.

---

## 0. Recap of the current architecture (what Tier 2 / Step 4 build on)

- We do **not** run real ReShade. We run **vkBasalt** — a guest-side Vulkan *instance layer* that
  hooks the game's DXVK/VKD3D swapchain **below** DXVK and emits SPIR-V to Turnip. It embeds the
  full `reshadefx` compiler, so it compiles real ReShade `.fx` on-device. It is **headless** (no
  ImGui); our in-game UI is the Compose **drawer "ReShade" tab**.
- The bundled, patched `libvkbasalt.so` ships inside
  `app/src/main/assets/graphics_driver/extra_libs.tzst`. Our patch is
  `patches/vkbasalt-reshade-livereload.patch`. The vkBasalt submodule
  (`app/src/main/cpp/vkbasalt/`) is **not checked out** in the tree — build from `.gitmodules`.
- **Config contract (locked, Tier 1):** guest `~/.config/vkBasalt/vkBasalt.conf`, line
  `effects = e1:e2:…:en:cas`; per effect `<ei> = <fxPath>`; uniforms `<ei>_<uniform>[_c]`;
  per-effect gate `<ei>_enabled = 0|1`; global master `enableOnLaunch`. `effectKey` =
  effect name lower-cased with `[^A-Za-z0-9_]→_`.
- **Live-reload patch (Tier 1, PROVEN):** vkBasalt mtime-watches the conf; on change it flips
  `presentEffect` (master on/off), memcpys slider values into the mapped UBO (`updateUniformsFromConfig`),
  and toggles per-effect gates (`updateEnabledFromConfig`). A gated-off effect becomes an identity
  image-copy passthrough; a gate flip does `QueueWaitIdle` + re-record command buffers (one-frame hitch).
- **Drop-in + catalog:** effects live app-global at `getExternalFilesDir(null)/ReShade/<id>/`
  (`ReshadeManager.getReshadeDir`). On-demand catalog (`ReshadeCatalog.kt` + `ReshadeDownloader.kt`)
  pulls per-effect `.tzst` from the `winlator-contents` `reshade-v1` release into that folder.
  Per game, a `ReshadeLoadout` persists as shortcut/Container extras. At launch,
  `writeVkBasaltConfig` (`XServerDisplayActivity.java:1411`) writes the conf and **stages** each
  effect into the container guest home at `<container>/…/.config/vkBasalt/effects/<name>/`.

**The load-bearing fact for both features:** the fake image pool and the effect chain are built
**once**, at `vkBasalt_CreateSwapchainKHR` (`basalt.cpp:399-505`), sized to the launch-time effect
count. The Tier-1 reload path **never constructs or destroys an effect** — it only touches
uniform/enable state. Both Tier 2 (add a *new* effect live) and Step 4 (a new depth *input*) push
against that once-at-launch boundary.

---

## 1. Tier 2 — in-game "add effect from catalog" (live, on-device recompile)

### 1.1 Goal / UX
From the in-game drawer ReShade tab, the user browses the **download catalog** (today it's
pre-launch only), taps to download an effect they don't have, and it appears in the live loadout
**without relaunching the game** — its typed controls auto-generate, it slots into Solo/Stack, and
it persists per-game. The unavoidable one-time Turnip shader compile is hidden behind the existing
freeze-frame + pulse.

### 1.2 Why it's not free: the core blocker (PROVEN)
- Chain is built once: `effects=` parsed at `basalt.cpp:399`; fake image pool sized
  `imageCount * (effectStrings.size() + …)` in one allocation at `basalt.cpp:402-405`; each
  `ReshadeEffect` constructed at `basalt.cpp:471-477` and **captures its input/output image slices
  by value** in its ctor (`effect_reshade.cpp:43-44`). SPIR-V + Turnip pipeline compile happen in
  that ctor (`effect_reshade.cpp:57`, pipeline creation ≈ the real stall).
- Tier-1 reload (`vkBasalt_QueuePresentKHR`, patch lines 33-109) does **only**: rebuild `Config`,
  set `presentEffect`, `updateUniformsFromConfig`, `updateEnabledFromConfig`, and — only if a gate
  flipped — `QueueWaitIdle` + `writeCommandBuffers` to swap `applyEffect`↔`applyIdentity`.
  **No `new ReshadeEffect`, no compile, no pool realloc.**
- App side matches: every effect the user can toggle **must already be in the launch `effects=`
  line** (`XServerDisplayActivity.java:1489`) and thus already compiled. So "add a *new* effect
  live" is genuinely unimplemented on both sides.

### 1.3 Native design — reserved-slot recompile (WOULD-WORK; recommended)
A naïve "reallocate the pool and rewire" is unsafe because live effects captured their image slices
by value. Sidestep pool reallocation entirely:

1. **Oversize the pool at launch** by `K` reserved slots: `basalt.cpp:402`
   `effectStrings.size()` → `effectStrings.size() + K`. Populate each reserved slot with an
   **identity passthrough placeholder** owning the `block[i] → block[i+1]` handoff (reuse the
   existing `applyIdentity` copy). Because every slot already reads `block[i]`/writes `block[i+1]`
   regardless of enabled-vs-identity — exactly how the shipped bypass works — **the image plumbing
   never changes when a slot is later filled.**
2. **Track slot→name** state: add `std::vector<std::string> effectNames` (+ `uint32_t reservedSlots`)
   to `LogicalSwapchain` (`logical_swapchain.hpp`), and a `virtual std::string getName()` on base
   `Effect` (`effect.hpp`) overridden by `ReshadeEffect` to return its private `effectName`
   (`effect_reshade.cpp:46`).
3. **Extend the reload block** (the existing `if (liveConfigChanged)` hunk, patch lines 84-109):
   diff the new `effects=` list against `effectNames`. For a key newly present: pick a free reserved
   slot → `QueueWaitIdle` → `new ReshadeEffect(… slotInput, slotOutput …)` bound to that slot's
   **existing** image slices → drop into `pLogicalSwapchain->effects[slot]` → update `effectNames`.
   For a key removed: `QueueWaitIdle` → destroy (`~ReshadeEffect`, `effect_reshade.cpp:1003-1017`) →
   restore the identity placeholder. Then the existing `writeCommandBuffers` re-record handles the rest.

This confines live work to: SPIR-V compile (CPU, tens of ms) + **pipeline creation (Turnip compile —
the stall)** + small per-effect buffer/texture allocations. No pool realloc, no rewiring of other effects.

**Native hazards (RISK):**
- **Present-thread allocation.** All construction runs inside `QueuePresentKHR` under `globalLock`.
  Functionally fine (consistent with the existing depth-image hooks under the same lock) but it
  **blocks the present** for the compile duration → must be hidden (§1.5).
- **Turnip compile cost.** Hundreds of ms for heavy effects + first-use pipeline-cache miss. This is
  the whole UX problem.
- **Reserved-slot VRAM.** Each slot = swapchain-sized RGBA × `imageCount` (~8 MB/image at 1080p),
  held permanently whether used or not. `K=3` ≈ **~72 MB reserved**. Keep `K` small/configurable.
- **Removal lifetime.** Destroy only under `QueueWaitIdle`; revert slot to identity.

### 1.4 App-side design (phased) — mostly wiring on existing seams
The pre-launch catalog picker is Compose and the drawer is Compose, so the sheet is **directly
reusable**.

- **T2-1 — surface the catalog in the tab.** Add a "Browse / add effect" button at the top of
  `ReshadeSection` (`XServerDrawer.kt` ~:981) that opens the existing `ReshadeCatalogSheet`
  (`ReshadeCatalogPicker.kt:118`, currently `private` → promote/lift into a shared composable both
  the pre-launch picker and the drawer call). Reuse `ReshadeCatalog.loadCached` (offline cache works
  in-game), `startDownload` (:176), progress UI, and the grouped Installed/Available rows verbatim.
- **T2-2 — mid-session add wiring.** New drawer callback `onReshadeCatalogAdd(effectName)`,
  implemented in the activity: `ReshadeManager.findEffect(name)` → reflect params + seed defaults →
  build a `ReshadeLoadoutItem` → append to the live loadout respecting Solo/Stack → call the
  **existing** `applyReshadeLive(master, mode, items)` (`XServerDisplayActivity.java:1612`), which
  already persists, rewrites conf, and fires the pulse.
- **T2-3 — re-seed the drawer without losing edits (the Compose gotcha, highest app risk).**
  `ReshadeSection` captures `seed = remember { XServerDialogState.reshadeLoadout.value }`
  (`XServerDrawer.kt:917`, deliberately `.value` not `collectAsState`), so a newly-added effect
  **won't appear** until the tab is closed/reopened, and a naïve re-key would collapse open rows /
  drop in-progress slider edits. Fix: key the derived edit maps on loadout **membership**
  (`remember(seed.map { it.name })`), seeding only newly-appeared names and preserving existing
  per-effect `valueState`. Mirrors `ReshadeLoadoutState.reconcile` (`ReshadeLoadoutEditor.kt:145`).
  **Must be device-verified:** open a tuned effect, add a second from catalog, confirm the first's
  slider values survive.
- **T2-4 — native recompile handshake.** `applyReshadeLive` → `writeVkBasaltConfig` already
  **auto-stages** a not-yet-staged effect (`XServerDisplayActivity.java:1442-1450`, dest dir absent →
  copy) and appends `<ei> = <path>`, uniform lines, and `<ei>_enabled = 1`. The one new app change is
  allowing `writeVkBasaltConfig` to **append a new key to `effects=` mid-session** (today the chain is
  fixed at launch). Beyond that, app-side Tier 2 is **blocked on the native reserved-slot workstream**:
  until the layer recompiles on `effects=` growth, an in-game add writes a conf whose new technique is
  silently ignored. Bump `EXTRA_LIBS_VERSION` (`XServerDisplayActivity.java:293`, currently `2`) when
  the recompile-capable `.so` ships so existing containers re-extract it.

**Persistence thread (already built):** catalog-add → `ReshadeDownloader.install` (app-global
drop-in) → `findEffect` reflects params → append `ReshadeLoadoutItem` → `applyReshadeLive` →
serialize + persist to shortcut-or-container per **`shortcutOwnsReshade()`** (`:4029`,
write-target == read-source so it survives relaunch) → `writeVkBasaltConfig` stages + rewrites →
`handleReshadePreviewChange` freezes/pulses. **No Room/schema change; no versionCode bump for the
app logic** — only the native `.so` swap warrants `EXTRA_LIBS_VERSION` + versionCode.

### 1.5 Hiding the compile stall (WOULD-WORK; one required tweak)
The pause/pulse infra exists and is PROVEN for the on/off case: `pulseReshadePreview()`
(`XServerDisplayActivity.java:1691`), `handleReshadePreviewChange()`/`setPausedState()` (:1672-1739)
driving `ProcessHelper.pause/resumeAllWineProcesses` (SIGSTOP/SIGCONT, `ProcessHelper.java:53-63`),
present-count gating via `PresentExtension.setPresentListener` (`PresentExtension.java:41`).

**Critical ordering (vkBasalt is passive — it only runs inside a present):** you cannot recompile
while frozen. Correct sequence = what `pulseReshadePreview` already does:
**rewrite conf → SIGCONT (one present fires, observes the new `effects=`, does the compile, blocks
for its duration) → SIGSTOP on the present callback.** The user stares at a frozen preview; the
compile is absorbed into the resume window.

**Required tweak:** `RESHADE_PULSE_FALLBACK_MS = 80` (`:271`) is far shorter than a Turnip pipeline
compile → the time-fallback would re-freeze **mid-compile**. For add-effect, either (a) gate the
re-freeze **purely** on the present callback (no time fallback) for that op, or (b) raise the
fallback to a compile-safe bound (~1500–3000 ms). `RESHADE_PULSE_TARGET_PRESENTS = 2` (:270) is fine.
Add a "compiling…" affordance since the pulse window is visibly longer.

### 1.6 Fallbacks if live-construct proves unsafe (ranked)
1. **Fixed slots, launch-compiled only (safest, ≈ today+):** app compiles the entire candidate
   loadout at launch; "add" only enables an effect pre-declared with `_enabled=0`. New-to-session
   effects need a relaunch. Zero new native risk.
2. **Fixed slots, fill-once:** pre-allocate `K` empty slots; allow **one** live compile-into-slot per
   slot per session (no destroy/refill churn) — most of the win, avoids the teardown edge cases.
3. **Controlled swapchain recreate:** a passive layer can't cleanly force the guest to recreate its
   swapchain; the practical equivalent is a fast in-place relaunch. Least attractive.

### 1.7 Ranked risks (Tier 2)
1. **Native recompile-on-reload is a hard dependency (HIGH, external).** Without it the app feature is
   a visual no-op. Gate app work on the native reserved-slot workstream + `EXTRA_LIBS_VERSION` bump.
2. **Drawer re-seed / Compose-state gotcha (HIGH, app).** T2-3 must preserve per-effect `valueState`.
3. **Compile hitch > pulse budget (MEDIUM).** 80 ms fallback tuned for *reload*, not *cold compile*;
   need present-gated or lengthened fallback + a compile-done signal.
4. **Solo-mode UX on add (MEDIUM, product decision).** Does an added effect steal "active" (A/B intent)
   or arrive disabled? Recommend mirroring pre-launch `ReshadeLoadoutState.add` (enables + respects
   solo exclusivity).
5. **Sheet-reuse coupling (LOW).** Extracting `ReshadeCatalogSheet` to shared code touches the
   pre-launch container + shortcut editors; regression-check both.
6. **Mid-session file writes (LOW — favorable).** Download target (app-global external files) and
   staging target (container under app data) are both app-owned, same UID as wine (no proot,
   `ReshadeManager.java:34-38`) → identical SELinux ctx/uid as launch-time staging, which already
   happens. A brand-new `effects/<name>/` isn't in use by wine → no conflict. Still device-verify the
   extract-while-paused path.
7. **Disk (LOW).** Effects are single-digit KB; double storage (drop-in + staged) is negligible.

### 1.8 Mod sources — catalog, user-supplied presets, and Nexus (product scope)
The in-game "add" flow above is pointed at **our first-party catalog** (license-clean MIT/CC0 set on
`reshade-v1`). Two adjacent sources come up (esp. "load ReShade mods from Nexus"):

- **First-party catalog (this feature).** Clean licensing, works offline once cached. The primary path.
- **User-supplied preset import (RECOMMENDED as a follow-on, independent of Nexus).** ReShade content
  on the web is overwhelmingly **presets** — `.ini` files referencing effects by name + uniform
  values. vkBasalt does **not** read ReShade preset `.ini` (own syntax) and needs the referenced
  `.fx` present. So a real deliverable is a **`.ini` → vkBasalt-conf translator**: map `Techniques`
  + per-uniform lines onto our `effects=` / `<ei>_<uniform>` contract, resolve referenced shaders
  from our catalog (skip/flag any that aren't in the license-clean set). This gives *content*
  compatibility with anything the user downloads themselves — no Nexus dependency.
- **Nexus Mods directly — heavily constrained, do NOT build an in-app auto-downloader.** Three
  problems: (a) the Nexus API gates download-link generation to **Premium** members with a per-user
  API key; automated third-party downloading for free users is against ToS. (b) The only compliant
  free path is the **`nxm://` handler**: the user clicks download *on the Nexus site*, Nexus mints a
  short-lived token, an `nxm://` deep-link fires, and a registered app catches it — user-driven,
  one click per mod. On Android that's an intent we *could* register. (c) Even then the file is a
  preset that needs the translator above + shaders that are frequently the qUINT / GPL / CC-BY-SA /
  non-commercial patchwork we deliberately excluded. **Recommendation:** don't chase an in-app Nexus
  browser or a real ReShade ImGui overlay (we already have the Compose overlay). If Nexus
  compatibility is wanted, build it as: preset-`.ini` importer first, then optionally the `nxm://`
  catch as a manual, per-mod convenience — both strictly user-initiated.

---

## 2. Step 4 — depth-buffer extraction from DXVK (SSAO / DOF / MXAO)

### 2.1 Goal
Unlock the ~22 quarantined depth effects (SSAO/MXAO, DOF, depth-fog, DisplayDepth) by giving
vkBasalt a valid, linearized scene-depth image from DXVK.

### 2.2 Ground truth — the depth code is already in the layer, just gated off (COMPILES)
Our patch drops only the X11 dep in `meson.build`; it **excludes nothing depth-related**. The whole
mechanism is compiled in and **runtime-gated by config**, not compiled out:
- **Gate:** `basalt.cpp:871-876` — the `CreateImage`/`DestroyImage`/`BindImageMemory` hooks are only
  installed when `depthCapture == "on"` (default `"off"` → hooks never returned from
  `GetDeviceProcAddr`).
- **Acquisition:** `vkBasalt_CreateImage` (`basalt.cpp:628-656`) — for a single-sampled depth-stencil
  attachment it **forces `VK_IMAGE_USAGE_SAMPLED_BIT` into the create info** (`:645`) and tracks it in
  `depthImages`. `vkBasalt_BindImageMemory` (`:658-707`) builds a sampled depth view + re-records.
  It naïvely uses `depthImages[0]` (`basalt.cpp:493-495`) — no selection heuristic.
- **Bind to effect:** `ReshadeEffect::useDepthImage` (`effect_reshade.cpp:790-834`) walks
  `module.textures` for `semantic == "DEPTH"` and writes the depth view into that sampler.
- **Barriers:** `command_buffer.cpp:54-106` transitions the depth image
  `DEPTH_STENCIL_ATTACHMENT_OPTIMAL → SHADER_READ_ONLY_OPTIMAL` around the passes.
- **App gap:** `writeVkBasaltConfig` emits **no** `depthCapture` key (grep-confirmed) → the entire
  path is dormant today.

**Architectural point:** vkBasalt sits *below* DXVK, so DXVK need not "expose" anything — vkBasalt
**intercepts DXVK's own `vkCreateImage`** and adds SAMPLED usage transparently. This is the same
desktop DXVK+vkBasalt+ReShade path that works on Linux. So Step 4 is **mostly app-side config +
un-quarantine + mobile validation, not porting missing native code.**

### 2.3 What DXVK we ship (PROVEN — packaging)
`app/src/main/assets/dxwrapper/` bundles many flavors selected per-container: `dxvk-1.10.3`,
`dxvk-1.11.1-sarek`, `dxvk-2.3.1`, `2.7.1.1-sdk36-arm64ec`, `d8vk-1.0`, `vegas-2.7.3`, VKD3D
`vkd3d-2.8` / `vkd3d-2.14.1`. So DXVK ranges **1.10.3 → 2.7.x** — depth behavior varies across builds.

### 2.4 Feasibility verdict
**Feasible to *enable*, hard to make *correct/reliable* on mobile.** Turning it on is a small change;
the effects will *sometimes* get valid depth and *often* garbage — the notorious vkBasalt-depth
behavior, amplified on tilers.

### 2.5 Hard risks, ranked (RISK)
1. **DXVK discards depth (`storeOp = DONT_CARE`) — #1 correctness killer, worst on tilers.** When the
   app doesn't need depth after the frame, DXVK sets depth `STORE_OP_DONT_CARE`. On an immediate GPU
   the memory still holds the values (desktop "gets away with it"); on **Adreno/Turnip tile memory**
   DONT_CARE means depth is genuinely never written back → vkBasalt samples undefined/cleared data.
   Adding SAMPLED usage does **not** change DXVK's store op. Not fixable in vkBasalt alone — needs a
   DXVK-side keep-depth coercion/config. **This is the reason the depth effects are quarantined.**
2. **Layout assumption (RISK).** `command_buffer.cpp:58` hard-assumes depth rests in
   `DEPTH_STENCIL_ATTACHMENT_OPTIMAL` at present; if DXVK leaves it elsewhere (or the image is
   transient/aliased) the barrier is wrong → validation errors / corruption on Turnip.
3. **Wrong-buffer selection.** `depthImages[0]` with no heuristic → often binds a shadow atlas, not
   scene depth.
4. **Missing linearization macros (definite visual bug).** vkBasalt seeds `BUFFER_WIDTH/HEIGHT/
   __RESHADE__` (`effect_reshade.cpp:1125-1134`) but **never** sets `RESHADE_DEPTH_INPUT_IS_REVERSED /
   _IS_UPSIDE_DOWN / _IS_LOGARITHMIC / RESHADE_DEPTH_LINEARIZATION_FAR_PLANE`. Modern D3D games use
   **reversed-Z**, and D3D↔Vulkan Y differs, so `ReShade.fxh` defaults linearize inverted/flipped →
   SSAO haloing, DOF focusing backwards. No config key exists → needs a small patch to
   `createReshadeModule` to `add_macro_definition` from config, or the app prepending `#define`s.
5. **Per-frame tiler cost.** Depth in `SHADER_READ_ONLY` across passes + forcing store-out defeats
   depth compression / tile-only residency on Adreno → extra bandwidth every frame, even for a cheap
   color effect sharing the chain.
6. **Format/tiling sampling.** `D24_UNORM_S8` / `D32_SFLOAT` sampling on Turnip is supported, but
   stencil-aspect + UBWC-compressed depth add edge cases; formats vary by DXVK version.

### 2.6 Phased plan (Step 4)
- **Phase 0 (spike, low cost, do first):** emit `depthCapture = on` in `writeVkBasaltConfig` for one
  known reversed-Z DXVK title + a trivial "visualize linearized depth" `.fx`; run on-device.
  Immediately answers whether *any* shipped DXVK surfaces usable depth (expect garbage/black on many
  titles due to risk #1). **This spike is the make-or-break gate for the whole feature.**
- **Phase 1 (linearization):** patch `createReshadeModule` to inject `RESHADE_DEPTH_*` macros from new
  config keys (`depthReversed`/`depthUpsideDown`/`depthLogarithmic`/`depthFarPlane`); app exposes them
  per-game. Fixes risk #4 wherever depth *is* valid.
- **Phase 2 (DXVK depth retention):** investigate a DXVK build/option (or targeted DXVK patch) that
  keeps the main depth buffer resident/stored on tilers. **Owned by wine-compat / DXVK-packaging.**
  Without this, tiler titles stay broken.
- **Phase 3 (buffer selection):** heuristic (largest depth image matching swapchain extent /
  most-recently-cleared) instead of `depthImages[0]`; per-game override index.
- **Phase 4 (un-quarantine):** enable depth effects only for container/DXVK/title combos proven in
  Phases 0-3; keep gated elsewhere.

---

## 3. Cross-cutting

- **`EXTRA_LIBS_VERSION` discipline:** every repack of `extra_libs.tzst` (new patched `.so`) **must**
  bump `EXTRA_LIBS_VERSION` (`XServerDisplayActivity.java:293`) so existing containers re-extract the
  new layer (marker `<libDir>/.extra_libs_version`). Both features ship a new `.so`.
- **Licensing:** keep the catalog MIT/CC0-clean. The preset importer / any Nexus path must resolve to
  clean shaders only; flag/skip qUINT-class content.
- **Release versioning HARD RULE:** no merge / tag / version-bump / stable cut without explicit user
  go. Everything here is `2.7-preN` territory (vc45+) when built. Credits owed at the ReShade stable:
  **DadSchoorse** (original vkBasalt engine, zlib) · **Pipetto-crypto** (bundled it into winlator) ·
  **StevenMXZ** (Ludashi fork) · bundled `.fx` authors per MIT/CC0.

## 4. Open unknowns to resolve before committing to build

- **(Tier 2)** Real Turnip pipeline-compile latency for a representative catalog effect on target
  Adreno — decides whether the lengthened pulse is ~300 ms or ~3 s of frozen preview. Only measurable
  on-device (time a single mid-session compile).
- **(Step 4)** Whether *any* shipped DXVK flavor (1.10.3 → 2.7.x) leaves scene depth stored/sampleable
  on Turnip **without** a DXVK patch — the make-or-break question, answered only by the Phase 0 spike.
- **(both)** Verify the **pinned** vkBasalt submodule commit matches upstream HEAD for the cited seams
  (`basalt.cpp:871` depth gate, `:402` pool sizing) before writing patches — the submodule wasn't
  checked out during recon; line numbers are from upstream `4f97f09` + the patch context (function
  names/structure are exact; line numbers may drift a few).

## 5. Suggested build order

1. **Tier 2 native reserved-slot recompile** (the gating dependency) → on-device compile-latency
   measurement → confirm the pulse hides it.
2. **Tier 2 app** T2-1…T2-4 on top → device-verify add-live + T2-3 state preservation.
3. **(optional) Preset `.ini` importer** — independent, unlocks user-supplied / Nexus content.
4. **Step 4 Phase 0 spike** — cheap, decides whether depth is worth pursuing at all before any real
   investment; if green, Phases 1→4 with DXVK retention (Phase 2) as the pole star.
