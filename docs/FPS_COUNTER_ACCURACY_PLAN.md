# FPS Counter Accuracy — Audit & Implementation Plan

Goal: a single, more-accurate FPS counter shared by **both** in-game HUD overlays
(classic `FrameRating` + GameHub-style `PerfHudView`), uniform across all renderers /
devices, matching (and optionally exceeding) how GameNative sources FPS.

Status: **AUDIT ONLY — nothing built.** Grounded on `main` (tip at time of writing:
`17a6527f`).

---

## 1. How Bannerlator counts FPS today

Two overlays, **two independent counters**:

- `app/.../widget/FrameRating.java:323-354` — classic HUD. Own `frameCount`, 500 ms
  window, `lastFPS = frameCount*1000 / elapsed`.
- `app/.../widget/PerfHudView.java` — GameHub HUD. Its **own** `update()`/counter,
  re-implementing the same math + a 50-sample graph.

Both are ticked together at every present site in `XServerDisplayActivity.java`:

| Site | Path it covers |
|------|----------------|
| `:967-969` (`onUpdateWindowContent` WindowManager listener) | compositor copyArea path |
| `:2231-2233` (`vkRenderer.setHudFrameTick`) | Vulkan AHB / native scanout (bypasses copyArea) |
| `:2262-2264` (`glr.setHudFrameTick`) | GL native FLIP/scanout (bypasses onDrawFrame + copyArea) |
| `:2280-2282` (`asr.setHudFrameTick`) | ASR / SurfaceFlinger (no copyArea) |

Each site fires `frameRating.update(); frameRatingHorizontal.update(); perfHud.update();`.

**Consequence:** the two overlays are separate counters that can drift, and the GameHub
graph is fed by a re-implemented counter — a second place for the math to be wrong.

## 2. How GameNative does it (the reference)

GN uses **one authoritative source**, `com/winlator/widget/FrameRating.java`, ticked once
per real present *inside each renderer*:

- `GLRenderer.java:143` — `frameRating.update()` in the draw loop
- `VulkanRenderer.java:435,476` — `hudRef.update()`
- `ASurfaceRenderer.java:471,495` — `hudRef.update()` (CPU path is **half-rate**,
  `skipFPSCount >= 1`, `:470-475`)

Its fancy overlay `PerformanceHudView.kt` **counts nothing** — it *pulls*:

```kotlin
// XServerScreen.kt:722-725
fpsProvider = { frameRating?.currentFPS ?: 0f }
// PerformanceHudView.kt:213 — own render timer, decoupled from frame ticks
val rawFps = fpsProvider()
```

GN also exposes off that one source: `getCurrentFPS()`, `getAvgFPS()`, min/max,
`getSessionLengthSec()`, `writeSessionSummary()` (`FrameRating.java:104-165`).

## 3. Key finding

**Our counting *method* already matches GN.** The per-renderer `setHudFrameTick` plumbing
is complete across GL/VK/ASR, and our ASR half-rate skip (`ASurfaceRenderer.java:417-422`)
is identical to GN's (`GN ASurfaceRenderer.java:470-475`).

The gap is **architecture, not accuracy of the tick**: we have *two* counters instead of
*one source + N displays*. That's what makes the two overlays drift and doubles the
surface for renderer-specific bugs.

## 4. Plan A — Single-source (matches GN)  ← recommended default

Additive, low-risk, no ImageFs/native changes.

1. **One authoritative counter.** Keep `FrameRating` as the source of truth (or extract a
   tiny `FpsCounter` holding `frameCount`/`lastFPS`/window + `getCurrentFPS/avg/min/max`).
   Add GN's getters + optional `writeSessionSummary()`.
2. **PerfHudView stops counting.** Remove its internal frame counter; give it a
   `Supplier<Float> fpsProvider` and an internal refresh timer (mirror GN's
   `PerformanceHudView` pull model). Its FPS value + graph both read `fpsProvider.get()`.
3. **Tick exactly one thing per present.** At the four XSDA sites, replace the triple
   `update()` calls with a single `fpsCounter.tick()` (the source). Overlays refresh
   themselves (classic already `post(this)`; PerfHud via its own timer). Feed
   `frameRatingWindowId` gate unchanged.
4. **Wire the provider.** When building `perfHud`, pass `fpsProvider = () ->
   fpsCounter.getCurrentFPS()`. Both overlays now show the identical number.

Files: `FrameRating.java`, `PerfHudView.java`, `XServerDisplayActivity.java` (4 tick
sites + perfHud construction). Legacy behaviour preserved when only the classic HUD is on.

**Verify:** build all 3 flavors via CI; device-test each renderer (GL, GL-native,
Vulkan, Vulkan-native, ASR) with each HUD style — confirm the two overlays read the same
FPS and neither reads 0 on any renderer. Cross-check against DXVK_HUD=fps in one title.

## 5. Plan B — add-on for true device independence (optional, more invasive)

Source frames from the **guest** side: count X11 `PresentPixmap` requests in
`app/.../xserver/extensions/PresentExtension.java`. This is 1:1 with actual game presents,
independent of which host renderer draws, so it's uniform on every device and immune to
per-renderer present quirks (e.g. the ASR half-rate hack becomes unnecessary).

- Increment the counter where the guest completes a Present (the `onUpdateWindowContent` /
  `onUpdateWindowContentDirect` dispatch in `PresentExtension.java:300,306`), gated to the
  game window id.
- Feeds the same `FpsCounter` from Plan A, so both overlays inherit it for free.
- Trade-off: must confirm it matches DXVK's reported present rate across DX9/11/12 + Vulkan
  titles and both mailbox/fifo present modes before trusting it over the renderer tick.

## 6. Watch-outs

- **Thread safety:** ticks arrive on the X-server epoll thread. `FrameRating.update()`
  already handles this via `post(this)`; `PerfHudView.update()` was fixed once for exactly
  this (`CalledFromWrongThreadException`, commit `4808d51`) — keep any new provider/refresh
  UI-thread-safe (`post()`/`postInvalidate()` only off the epoll thread).
- **Double-count check:** confirm the WindowManager `onUpdateWindowContent` listener
  (`XSDA:959`) and a renderer's `setHudFrameTick` never both fire for the same frame on any
  one renderer — if they can, gate one off so we count each present once.
- **No native/ImageFs work** for Plan A. Plan B is still app-side (PresentExtension is Java).
