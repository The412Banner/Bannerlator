# GameHub-style Performance HUD — Port Plan

Add a **second, selectable in-game performance HUD** modeled on GameHub 6.0.9's
overlay, alongside the existing `FrameRating` HUD. Scope (decided 2026-06-25):
**full parity**, **per-container** selection, **all three skins**.

This is a clean reimplementation of the *look + option set* observed in GameHub
6.0.9 (proprietary, obfuscated). No GameHub code/assets are copied — our own
custom `View`, fed by data Bannerlator already collects.

## Source recon (GameHub 6.0.9, jadx `/home/claude-user/gamehub-6.0.9-jadx`)
- HUD is plain `android.graphics.Canvas`/`Paint`/`Path` — no Compose/GL/native for visuals.
  Legacy reference View: `sources/defpackage/o6m.java` (`onDraw`/`onMeasure`); current
  draw helpers `l79.D/E/F/G/H/I/J`, paint cache `g0a`, palette `zz9`, measure `g89.K`.
- Two layouts: horizontal top pill + vertical right list; auto-fallback to vertical if
  the horizontal pill exceeds screen width, plus an explicit "Vertical HUD" toggle.
- FPS graph: `Path` over last 50 samples, Y-peak clamped ≥60, faint 30fps guide line, green stroke.
- Font: MONOSPACE bold, letterSpacing 0.04 (uniform across skins).
- FPS source in GameHub = native `libxserver.so` shm — **not needed**: our `FrameRating`
  already counts FPS; we feed our own samples into the graph.

## Option set (full parity — 17 controls)
Metric toggles: GPU model, Engine, CPU usage, GPU usage, Power, Frame rate (FPS),
Frame (FPS graph), Memory (RAM), Temperature.
Appearance: HUD display (master), Dual-battery power fix, HUD scale (0.6–1.4, def 0.92),
HUD skin (Classic/Neon/Mono), Vertical HUD, HUD opacity (0.0–1.0, def 0.8),
HUD color (Soft 0.72 / Mid 0.88 / Vivid 1.0), HUD outline (Off / Soft 1.0dp / Strong 1.4dp).

Skins: Classic = per-field colors; Neon = uniform neon-green labels + amber values;
Mono = greyscale. Color intensity = ×RGB-channel factor. Outline = a black stroke Paint
(argb 132,0,0,0) drawn under the fill text. Per-field colors: engine=pink, GPU=green,
CPU=amber, RAM=cyan, PWR=violet, TMP=red, FPS=yellow, CHG=green; values=white.

## Data sources (portable vs new)
Already collected by `FrameRating`: FPS, GPU% (kgsl/mali sysfs), RAM%, temp (thermal sysfs),
power(W)+charging (power_supply/BatteryManager), frame time, engine label, GPU model
(`GPUInformation.getRenderer`).
**New work:** (1) CPU **usage %** via `/proc/stat` + `/proc/<pid>/task/*/stat` deltas
(we currently show CPU *temp*, not usage); (2) Dual-battery power fix — sum `battery`+`bms`/`main`
`current_now` channels with `abs()` + sanity windows (current 1..30000mA, V 0.01..80, P 2.5..20W).

## Bannerlator integration
- Config: extend the per-container `fpsCounterConfig` KeyValueSet
  (`Container.DEFAULT_FPS_COUNTER_CONFIG`) with new keys; add `hudStyle=classic|gamehub`.
- Launch: `XServerDisplayActivity` (~the `container.isShowFPS()` block, ~line 1668) creates the
  chosen HUD view (WRAP_CONTENT params — see the tap-area fix) and shows only the active one.
  Reuse existing tap-to-toggle-orientation + drag (and the wrap-content fix).
- UI: extend `FpsCounterConfigDialog` (`ContainerDetailScreen.kt`) + the in-game Controls
  menu (`XServerDrawer.kt`) with the full option set.

## Phases
- **P0** config keys + `hudStyle` selector wiring + launch swap.
- **P1** `PerfHudView extends View` — both layouts, per-field colors, FPS graph (core render).
- **P2** appearance: skin / color-intensity / outline / scale / opacity enums applied in `onDraw`.
- **P3** new metrics: CPU usage % sampler + dual-battery power fix + GPU-model row.
- **P4** full config UI (container + in-game).

Additive; legacy `FrameRating` untouched. No imagefs/native changes.
