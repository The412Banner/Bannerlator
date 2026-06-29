# STEP 3 — In-Game ReShade Effects (vkBasalt drop-in) — Design Doc & User Report

**Status:** DESIGN — **spike DEVICE-PROVEN ✅** (on-device `.fx` compile + apply confirmed on real hardware), no feature code yet.
**Date opened:** 2026-06-29
**Branch (spike, throwaway):** `spike/vkbasalt-reshade` (`1400d06`)
**Roadmap slot:** STEP 3 of the smooth + sharp graphics roadmap (after STEP 1 debanding/NIS and STEP 2 VRR).
**Depends on / relates to:** the existing CAS/DLS "sharpness" feature (already vkBasalt-powered), the in-game drawer, the per-shortcut/container editor.

This is the source-of-truth design + user-facing report for turning ReShade `.fx` effects into a real,
user-facing feature: pick an effect per game, tune it live from an in-game overlay.

---

## 0. The one-paragraph version (read this first)

We already ship the **full vkBasalt** inside the app — a Vulkan layer with the **entire ReShade FX compiler
embedded**. It's currently only wired up to drive the "sharpness" slider. STEP 3 unlocks the rest of it: users
drop ReShade `.fx` effects into a folder, **select one per game shortcut**, and **tune its sliders live** from a
new **"ReShade" button in the in-game side menu** that opens a slider overlay. Effects compile **on-device to
SPIR-V** (no Adreno GLSL-compiler crash risk) and apply to **DXVK/VKD3D (Vulkan) games**. The only real
engineering unknown is a small patch to vkBasalt so slider changes apply **live** instead of needing a relaunch.

---

## 1. Why this is now possible (what the spike proved)

The earlier honest answer was "arbitrary `.fx` drop-in is a stretch goal — three blockers." Recon of the
`Pipetto-crypto/winlator @ dev` fork plus binary inspection of our own shipped libs **flipped two of the three
blockers.** This is the key finding that makes STEP 3 real:

- **We already bundle full vkBasalt.** `app/src/main/assets/graphics_driver/extra_libs.tzst` contains
  `usr/lib/libvkbasalt.so` (~17.5 MB) — and a symbol dump confirms it embeds the **entire `reshadefx`
  compiler** (`lexer` / `preprocessor` / `effect_parser` / `codegen_spirv`) plus `vkBasalt::ReshadeEffect` and
  the config keys `reshadeTexturePath` / `reshadeIncludePath` / `depthCapture` / `toggleKey`. It compiles
  `.fx` → **SPIR-V on-device** → Turnip. License = **zlib** (redistributable). **No tzst repack needed.**
- **It's already wired.** vkBasalt is the engine behind today's CAS/DLS sharpness feature
  (`XServerDisplayActivity` field `vkbasaltConfig` → `ENABLE_VKBASALT=1` + `VKBASALT_CONFIG`). So Vulkan layer
  discovery (`VK_LAYER_PATH`, `WRAPPER_LAYER_PATH`) **already works** — we're extending a live path, not building
  one.

### Blocker scorecard (updated)

| # | Old blocker | Status now | Why |
|---|---|---|---|
| 1 | ReShade `.fx` is a language we can't read | ✅ **GONE** | vkBasalt's own `reshadefx` parser reads `.fx` directly. No CI transpile needed. |
| 2 | On-device compile = Adreno GLSL-compiler crash class | ✅ **GONE** | vkBasalt emits **SPIR-V** and feeds **Turnip** — it never touches Adreno's fragile GLSL compiler. |
| 3 | Many ReShade effects need the depth buffer | ⚠️ **STANDS** | `depthCapture` exists but DXVK/mobile depth is flaky/costly. **Color effects work now; depth effects (SSAO/DOF/MXAO) are STEP 4.** |

### 1.1 ✅ DEVICE-PROVEN (2026-06-29) — the spike actually ran on hardware

The throwaway spike (one hardcoded CC0 **sepia** `.fx`, force-enabled at launch) was run on the real test device:
**The Saboteur (DXVK) launched fully sepia-tinted.** That single fact closes the whole risk question — an
arbitrary ReShade `.fx` was **parsed → compiled to SPIR-V → fed to Turnip → applied to a live DXVK swapchain**,
on this Adreno, with no crash. Blockers #1 and #2 are now *empirically* dead, not just dead on paper.

Live `/proc/<pid>/environ` of `Saboteur.exe` (and the whole wine tree) confirmed the wiring the feature will reuse:
`ENABLE_VKBASALT=1`, `VKBASALT_CONFIG_FILE=…/xuser/.config/vkBasalt/vkBasalt.conf`,
`VK_LAYER_PATH=…/implicit_layer.d:…/explicit_layer.d`, `LD_LIBRARY_PATH=…/usr/lib:/system/lib64`, DXVK active.

**Two real lessons from getting it to run** (both fold into the plan below):
- **The layer only extracts on a container's FIRST boot.** `extra_libs.tzst` (which carries `libvkbasalt.so` +
  `vkBasalt.json`) is unpacked only under `if (firstTimeBoot)` (`XServerDisplayActivity.java:~2511`). Existing
  containers created before the vkBasalt bundle never get it → the layer silently isn't there → no effect. **Fix
  for the feature: extract on version-change OR when a ReShade effect is selected, not just first boot** (see §6).
- **The built-in HOME `toggleKey` does NOT work from the on-screen keyboard.** vkBasalt grabs the X11 Home keysym
  on our X server, but the on-screen keyboard's Home key never reaches that grab. ⇒ **we must not rely on
  vkBasalt's hotkey for on/off** — drive enable/disable from our own UI (see §5.1).

---

## 2. Hard constraints found by the spike (these shape the whole design)

1. **Vulkan games only.** vkBasalt is a **guest-side Vulkan instance layer** — it hooks the *game's* Vulkan
   swapchain (DXVK/VKD3D via Turnip) **before** our X server/compositor. ⇒ **renderer-agnostic** (works whether
   our host renderer is GL or Vulkan) but it has **no effect on WineD3D / GL / GDI / software** titles. The UI
   must say so.
2. **No live config-watch in the shipped build.** Binary recon found **no inotify/mtime/reload** strings.
   vkBasalt reads its config **once at swapchain create** (game launch). The only built-in live control is the
   `toggleKey` (HOME) which flips the **whole effect on/off** — *not* per-parameter (and the HOME key doesn't
   reach the layer from the on-screen keyboard anyway). **⇒ Live on/off + live sliders require a vkBasalt
   config-watch patch (Section 5)** — the same patch the fork already applied to lsfg-vk for frame-gen, so it's a
   proven pattern, not an unknown.
3. **Color effects only (for now).** No depth → SSAO/DOF/MXAO and depth-aware AA are out until STEP 4. The
   effects people actually want here — **color grading / LUT / tonemap / sharpen / film grain / vignette / CRT /
   sepia / curves** — all work without depth.
4. **Host-absolute paths.** This fork does **not** proot; the guest uses host absolute paths
   (`HOME=imageFs.home_path`, `rootDir.getPath()+...`). Every path inside `vkBasalt.conf` must be **host-absolute**
   (not `/home/xuser/...`). The spike already handles this.
5. **vkBasalt reads `.fx` shaders, NOT ReShade preset `.ini` files.** It has its own config syntax. A dropped-in
   ReShade *preset* `.ini` will not be read — we store slider values in our own shortcut extras / config and write
   them into `vkBasalt.conf` ourselves (Section 4.3).

---

## 3. What the user gets (end result)

### 3.1 The experience, start to finish

1. **Drop in effects.** User puts ReShade effects into a single app-managed folder, **one self-contained
   subfolder per effect/preset** (`.fx` + any `.fxh` includes + textures). The app scans it and lists each
   subfolder as a selectable entry.
2. **Pick per game.** In a game **shortcut** (and/or its container) there's a new **"ReShade effect"** picker:
   *None* or any scanned effect. Picking one loads it at launch.
3. **Tune live in game.** The **in-game side menu (drawer) gets a "ReShade" button**. Tapping it opens a
   **slider overlay** on top of the game showing exactly the controls that effect exposes — **auto-generated from
   the `.fx` itself** (every ReShade uniform declares `ui_min` / `ui_max` / `ui_step` / `ui_type` / default). Move
   a slider → the look changes **live**, no relaunch. A master on/off toggle (and the HOME hotkey) flips the whole
   effect.
4. **It persists.** Slider values save back to the shortcut so the next launch starts where they left off.

### 3.2 Concretely, what it looks like

```
GAME SHORTCUT EDITOR                 IN-GAME SIDE MENU (drawer)
┌─────────────────────────────┐     ┌──────────────────────────┐
│ Graphics                    │     │  ▸ Task Manager          │
│  Renderer       [Vulkan ▾]  │     │  ▸ Scaling / Effects     │
│  ReShade effect [Sepia   ▾] │     │  ▸ ReShade        ← NEW  │
│     (None / scanned list)   │     │  ▸ Refresh rate          │
└─────────────────────────────┘     └──────────────────────────┘
                                              │ tap
                                              ▼
                                     ┌──────────────────────────┐
                                     │  ReShade — Sepia    [⊗]  │
                                     │  Effect          [ ON  ] │
                                     │  Intensity   ──●──── 0.6 │  ← sliders auto-built
                                     │  Tint (R)    ────●── 0.8 │     from the .fx params
                                     │  Tint (G)    ──●──── 0.5 │
                                     │  [ Reset to defaults ]   │
                                     └──────────────────────────┘
```

### 3.3 Plain-English value (for release notes)

> **In-game ReShade effects.** Add cinematic color grading, film looks, sharpening, CRT/retro filters and more to
> your Vulkan games. Drop ReShade effects into a folder, pick one per game, then fine-tune it live from a new
> **ReShade** button in the in-game menu — no restart needed. Works with DX9/10/11/12 games running on DXVK/VKD3D.
> (Effects that need scene depth, like ambient occlusion or depth-of-field, are coming in a later update.)

---

## 4. Architecture

```
   ReShade/ folder (user drop-in)            OUR APP (Kotlin/Compose)                 GUEST (per game process)
   ┌───────────────────────┐    scan +      ┌────────────────────────┐   write       ┌──────────────────────┐
   │ Sepia/  sepia.fx       │──reflect────▶ │ effect list + param     │──conf+env──▶ │ vkBasalt layer        │
   │ CRT/    crt.fx + .fxh  │   params       │ metadata (Room/extras)  │              │  reshadefx → SPIR-V   │
   │ ...                    │                │ shortcut picker +        │   live       │  → Turnip → swapchain │
   └───────────────────────┘                │ in-game slider overlay  │◀─patch IPC──▶│  (game's frames)      │
                                            └────────────────────────┘  (Section 5)  └──────────────────────┘
```

### 4.1 Effect discovery + param reflection
- **Folder layout:** one app-managed root (e.g. `<imageFs.home>/.config/reshade/` mirrored to a user-visible
  path under `/sdcard/`), **one subfolder per effect**, each self-contained (`.fx` + `.fxh` + textures). Scanning
  = list subfolders that contain a `.fx`.
- **Param reflection:** to build sliders we need each effect's tunable uniforms + their `ui_*` annotations. Two
  options, pick one in design review:
  - **(A) Reuse vkBasalt's own parser** via a tiny JNI entry that runs `reshadefx` preprocess/parse and returns
    the uniform table (name, type, min/max/step/default, `ui_type`, `ui_label`). Single source of truth, exact.
  - **(B) Lightweight Kotlin annotation scraper** that regex-reads `uniform ... < ui_min=..; ui_max=..; >` from
    the `.fx`. No native call; good enough for the common annotation style; risks missing exotic cases.
  - **Recommendation:** (A) if the JNI surface is cheap; otherwise ship (B) first, upgrade to (A) later.

### 4.2 Launch wiring (already proven by the spike)
- In `extractGraphicsDriverFiles()` set `ENABLE_VKBASALT=1` and `VKBASALT_CONFIG_FILE=<host-absolute conf>`.
- Build `vkBasalt.conf` from the selected effect: `effects=<name>`, `reshadeTexturePath`/`reshadeIncludePath`
  pointing at the effect subfolder (host-absolute), the reflected uniform values, `enableOnLaunch=True`,
  `toggleKey=Home`.
- **Coexistence with the CAS sharpness feature:** both drive vkBasalt. Merge into **one** `vkBasalt.conf` /
  effect chain (e.g. `effects=cas;<reshade>`), not two competing `VKBASALT_CONFIG` sources. Decide order
  (sharpen-last is usual). The spike currently has the file path win over the inline CAS path — productionizing
  means **merging**, not overriding.

### 4.3 Persistence
- Selected effect + per-uniform values stored in **shortcut extras** (mirrors `sharpnessLevel` etc.) with a
  container-level default. On launch we materialize them into `vkBasalt.conf`. ReShade preset `.ini` files are
  **not** read (Section 2.5); if we ever want to import them, that's a small `.ini` → uniform-values translator,
  separate task.

### 4.4 The in-game overlay (our UI)
- vkBasalt is the **headless layer** — it has **no ReShade ImGui overlay**. We build our own in Compose, exactly
  like the existing drawer / Task Manager / HUD panels that already render over the game surface.
- New drawer entry **"ReShade"** → overlay with: master on/off, one control per reflected uniform (slider for
  float/int, switch for bool, color picker for `ui_type=color`), and **Reset to defaults**.
- Input focus / pause-game-input while open = the pattern the drawer already uses.
- **Gating:** show the button only when renderer path can carry vkBasalt and the game is Vulkan-backed
  (DXVK/VKD3D). Grey out with a one-line reason otherwise (mirrors how SGSR/HDR are gated today).

---

## 5. Live on/off + live sliders — the vkBasalt config-watch patch

### 5.1 The in-game "ReShade" toggle under Graphics — works exactly like Frame Generation

The target UX (user-requested): a **ReShade on/off toggle in the in-game drawer's Graphics tab**, flipping the
effect **live** — the same feel as the existing Frame Generation toggle. The mental model is correct, and so is
the reason it works:

> **Frame Generation toggles live because its layer was *patched* to.** The fork patched lsfg-vk to **watch its
> `conf.toml` mtime in its present hook and hot-reload** (`XServerDisplayActivity.java:~547` / `~1224`:
> *"the fork layer watches the file mtime and reloads … re-applies live"*). The in-game toggle just rewrites the
> conf; the layer notices and reloads — no relaunch.

**The vkBasalt build we ship has no such watch** (binary recon: no inotify/mtime/reload). So a drawer toggle that
only rewrites `vkBasalt.conf` would do nothing until next launch. To get true frame-gen-style live behavior we
give vkBasalt **the same treatment we already gave lsfg** — add a config-file watch to its present hook. This is
a known, repeated pattern in our tree, not new ground. **That one patch delivers both the live on/off toggle AND
the live sliders (§4.4).**

**Cheaper on/off-only fallback (no patch):** because we *own* the X server (pure-Java X11) and vkBasalt's HOME
`toggleKey` listens for an X11 Home keysym on it, the drawer toggle can **inject that Home keysym from our side**
to drive vkBasalt's *existing* toggle. Gives live on/off with zero patching (the on-screen keyboard's Home
doesn't reach the grab, but a deliberate inject would). Does **not** give live sliders. Useful as an interim
toggle while the config-watch patch lands.

| Route | Live on/off (drawer toggle) | Live sliders | Effort | When |
|---|---|---|---|---|
| Inject X11 Home keysym from our X server | ✅ | ❌ | Low | interim, if we want a toggle before the patch |
| **vkBasalt config-watch patch (mirror lsfg)** | ✅ | ✅ | Medium | **the real Phase 2 — recommended** |

### 5.2 Cost of each live change (informs what the patch must do)

To make the overlay's sliders **live**, the config-watch patch re-reads the conf on change. Crucial cost split:

| Live action | Cost | Plan |
|---|---|---|
| **Change a parameter** (slider) | **Cheap** — uniforms live in a buffer; update values with **no shader recompile** | Add a config/IPC watch that re-reads only the uniform block and re-uploads it per present. Smooth, real-time. |
| **Switch which effect** is active | **Expensive** — requires recompiling the `.fx` | Keep effect **selection pre-launch** (in the shortcut). Overlay = tune the loaded effect only. (Optional later: accept a brief recompile hitch to swap live.) |

**Recommended live mechanism:** a tiny **mtime/inotify watch** on `vkBasalt.conf` (or a small unix-socket
command channel) added to vkBasalt's present path that, when the file changes, re-parses **just** the uniform
values and updates the buffer — **no recompile**. The overlay writes the conf (or sends a command) on slider
move. This is the only component without a proven precedent in our tree; everything else reuses existing
patterns.

**Fallback if we defer the patch:** ship Phase 1 (Section 6) with **pre-launch sliders only** (set in the
shortcut, applied at launch) and a working on/off in-game. Honest, useful, no patch. Then add the patch to make
the overlay live.

---

## 6. Phased delivery

- **Phase 0 — close the spike. ✅ DONE (DEVICE-PROVEN 2026-06-29).** Sepia `.fx` compiled on-device and applied
  to a live DXVK game (The Saboteur). On-device `.fx`→SPIR-V→apply confirmed. (HOME toggle found unreliable from
  the on-screen keyboard → §5.1 handles it.) *Risk retired — everything below is now build work, not research.*
- **Phase 1 — selection + pre-launch sliders + reliable extraction (no patch).**
  - **Fix the extraction gate (prerequisite):** stop unpacking `extra_libs.tzst` only on `firstTimeBoot` — extract
    on app version-change, or when a ReShade effect (or CAS) is selected, so the layer is present on existing
    containers too. Without this, the feature silently no-ops on any pre-existing container.
  - Effect folder scan + reflection (4.1), shortcut picker, conf generation merged with CAS (4.2), persistence
    (4.3). Sliders live in the **shortcut editor**, applied at launch. **Ships real value with zero vkBasalt
    changes.** (Optional interim: the X11-Home-inject on/off in the drawer, §5.1.)
- **Phase 2 — live drawer toggle + slider overlay (headline).** The vkBasalt **config-watch patch** (§5.1, mirrors
  the lsfg live-reload), the in-game **ReShade on/off toggle under Graphics** (frame-gen-style, live), and the
  **slider overlay** (4.4) with live uniform updates. This is the experience the user asked for.
- **Phase 3 (optional/back-burner) — depth + presets.** STEP 4 depth extraction unlocks SSAO/DOF; optional
  ReShade `.ini` preset import; optional live effect-switch with a recompile hitch.

---

## 7. Risks / open decisions

- **Param reflection route** (4.1 A vs B) — decide in design review.
- **CAS coexistence** — must merge into one effect chain; confirm ordering + that the existing sharpness UI keeps
  working unchanged.
- **vkBasalt patch maintainability** — we'd carry a patched `libvkbasalt.so` in `extra_libs.tzst`; document the
  patch + build so it's reproducible (like our lsfg patch).
- **Effect compatibility variance** — not every community `.fx` will compile cleanly (includes, textures, depth
  assumptions). Need graceful failure (effect fails → log + skip → no black screen) and ideally a "this effect
  uses depth (unsupported)" hint.
- **Mali devices** — the bundled vkBasalt ships Adreno/freedreno; Mali needs its own build (out of scope; document
  the limit).
- **Per-game `.fx` security** — these are shaders the user supplies; compile failures must be contained. Low risk
  (SPIR-V → Turnip, same path as game shaders) but worth a note.

---

## 8. TL;DR for the user

You'll be able to **drop ReShade effects into a folder, choose one per game, and tweak it live from a new
ReShade button in the in-game menu** — color grading, film looks, sharpening, CRT/retro filters and more, on your
DXVK/VKD3D (Vulkan) games, compiled safely on-device with no crash risk. Selection happens before launch; live
tuning happens in-game once we add a small reload patch to the effect layer. Depth-based effects (ambient
occlusion, depth-of-field) come later with STEP 4.
