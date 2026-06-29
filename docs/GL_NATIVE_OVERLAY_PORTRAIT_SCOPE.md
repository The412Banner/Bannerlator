# GL Native Rendering — Full HWC-Overlay Win on Portrait-Native Panels (Scoping)

Status: DESIGN / SCOPE ONLY. No code in this document is implemented on `main`.
Author: graphics engineer. Date: 2026-06-29.
Prereq reading: `MEMORY` topic `project_bannerlator_gl_native_rendering`; `PROGRESS_LOG.md`
(Experiment A / Experiment B entries); Experiment B diagnostic branch
`exp/gl-scanout-prerotate-panelres` (`8b20b96`).

---

## (a) Direct answer to the user's portrait/landscape question

**Yes, it is achievable — Experiment B already proved the hard part on real hardware** —
but with an important honesty caveat about *how much it actually buys you on a portrait
panel*. The recipe is: lock the **activity** to the panel's native PORTRAIT orientation so
the window and the panel agree (no display-orientation fold), allocate the scanout
buffer at **panel resolution** (1080×1920), GPU-**pre-rotate** the landscape guest frame
90° into that buffer so it is already display-oriented, and present it **src==dst,
transform 0, no scale**. The DPU then accepts a plain non-UBWC BGRA_8888 buffer on a
hardware overlay pipe (Exp B: game layer `DEVICE/DEVICE`, GPU_TARGET unused). The player
keeps holding the device sideways and the *game* looks correct because we baked the
rotation into the pixels. **What is NOT free is everything that is NOT the game**: the
in-game drawer, perf HUD, on-screen touch controls, dialogs, and the cursor all belong to
the now-portrait activity, so they lay out/​draw rotated-wrong for a sideways player and
must each be counter-rotated or re-homed. That UI-under-portrait work — not the overlay
itself — is the real cost and the real risk.

**The caveat you must hear first (diminishing returns):** on a portrait panel the
*existing, normal* GL compositor (Native **OFF**) **already** gets the DPU overlay
(`DEVICE/DEVICE`, transform-0 UBWC surface — confirmed in every OFF baseline capture).
The compositor bakes the landscape→portrait rotation into its own fullscreen surface, which
the DPU happily scans out. So Experiment B did **not** unlock a capability the device
lacked under normal rendering — it brought the *native/direct-scanout path* up to parity
with what the ordinary compositor was already doing. The pre-rotate native path costs one
full-frame GPU blit (the rotate) — the same order of GPU work the normal compositor's blit
already spends — so on portrait the net win over just-using-the-normal-compositor is thin
(a slightly leaner present path + marginally better pacing/latency), **not** the
"GPU goes idle" jump people imagine. The genuinely clean, large win (guest buffer pushed
direct, zero app blit, DPU overlay) lands on **landscape-native** panels, where the merged
P4+P5 code *already* delivers it with no rotation at all. Read Phase 0 before building
anything.

---

## (b) Phased plan (each phase independently device-testable, P0-P6 style)

### Phase 0 — MEASURE-FIRST de-risk (decision gate, build almost nothing) — **S**
The whole effort hinges on one number nobody has yet: at **capped, equal FPS** on the
portrait device, does the Exp-B pre-rotate native path use **measurably less power /
lower temp / lower latency** than (1) the normal GL compositor (Native OFF) and (2) the
current merged P4 native (transform-90, `DEVICE/CLIENT`)? Because the OFF compositor
already overlays, the realistic delta is small — prove it exists before paying for Phases
2-5.
- Method: AIO DX11 cube or a real game, lock FPS to panel (60) via the guest-side
  IdleNotify limiter (the working one), drawer closed. Capture GPU busy %, SoC power
  rail / battery current, skin temp, and a latency proxy (touch-to-photon or
  present-to-present jitter) for three states: OFF-compositor, P4-native (current main),
  Exp-B-native (`8b20b96`). Hold scene/FPS identical.
- Device-testable: yes, today, using the existing `8b20b96` throwaway build. No new code.
- **Gate:** if Exp-B-native does not beat OFF-compositor by a margin worth the UI/input
  rework, **stop here on portrait** and ship the landscape-native path only (Phase 1).
  This single measurement can retire Phases 2-5.

### Phase 1 — Confirm the clean win on a landscape-native panel (no code) — **S**
On an AYANEO / landscape-native handheld, run merged P4+P5 (`main`) Native ON. Expect the
guest game buffer to arrive transform-0 already (panel and game agree), push direct with
**no blit**, and promote to `DEVICE/DEVICE`. This is the high-value payoff and it needs
zero new code — only confirmation via `dumpsys SurfaceFlinger` (SDE pipe table / raw
`layer:` list, read ACTUAL not REQUESTED composition).
- Device-testable: yes, on a landscape-native device.
- Outcome: documents "native rendering gives the full overlay+latency win on
  landscape-native panels" as the headline, portrait as best-effort.

### Phase 2 — Panel-orientation detection + strategy switch — **M**
Make native rendering pick its path from the panel's *physical native* orientation, not
the container/game orientation:
- Landscape-native panel → **direct path** (today's P4 push, transform-0, no blit) =
  clean B+C win.
- Portrait-native panel + landscape game → **pre-rotate path** (Phases 3-4) *iff* Phase 0
  said it is worth it, else stay latency-only (current behaviour, transform-90).
- Detection: compare the display's natural orientation / physical resolution to the
  current rotation. Use `Display.getRotation()` + the natural width/height
  (largest-dimension axis), or `WindowManager`/`DisplayMetrics` natural bounds; a panel
  whose `ROTATION_0` bounds are portrait (h>w) is portrait-native. Cross-check against the
  container's `screenInfo` (landscape game) to decide "rotation needed".
- Device-testable: pick-path logging + dumpsys on both a portrait and a landscape device.

### Phase 3 — Orientation-aware, correct pre-rotation (replace Exp B's hack) — **M**
Exp B hardcodes a one-direction 90° UV transpose (`ScanoutBlitRot90Material`) and accepts
mirroring/handedness as wrong. Productionize:
- Handle the device's real rotation: at minimum **sideways-left vs sideways-right**
  (90 vs 270), ideally all 4 (0/90/180/270), derived from `Display.getRotation()` vs the
  panel's natural orientation.
- Correct **handedness** (no mirror) and the **1920×1080 landscape guest → 1080×1920
  portrait panel** mapping with **no scale** (panel-res target, src==dst). Preserve the
  game's aspect ratio with letterbox if the container render-res differs from panel AR
  (the cube probe ignored this).
- Keep `swapRB` and the V-flip correct in the rotated material (Exp B folded them
  loosely).
- **Where the rotation must live (answering the design question):** it MUST be a GPU pass
  on the renderer side (GL blit, as Exp B does). It **cannot** move into
  `ScanoutContext`/`DirectScanout` "more cheaply" — `ScanoutContext` is pure NDK
  SurfaceControl/AHardwareBuffer with **zero GPU**, and the only rotation lever it has,
  `ASurfaceTransaction_setGeometry`'s transform arg, is exactly the **per-layer rotation
  the DPU rejects** (it currently always passes `0` — `ScanoutContext.cpp:196`). So the
  rotation is fundamentally a pixel operation: the buffer must *arrive* display-oriented
  and transform-0. There is no compositor-side shortcut on this DPU. (The genuinely
  cheaper-than-a-blit option — guest-side pre-transformed rendering via the Vulkan WSI
  surface-transform / a Mesa-turnip change — is a wine-compat/Mesa project, out of scope
  here and not generically feasible because games render fixed landscape.)
- Device-testable: image is correct (not just "not black") in both hand orientations;
  dumpsys still `DEVICE/DEVICE`, transform 0.

### Phase 4 — Non-game layers under a portrait activity (THE hard phase) — **L**
With the activity portrait-locked, the entire Android view tree lays out portrait while
the player holds the device landscape. Each surface needs a correct-for-sideways plan:
- **In-game drawer (Compose), perf HUD (`FrameRating`/`FrameRatingHorizontal`/
  `PerfHudView`), on-screen touch controls (`InputControlsView`/`TouchpadView`), dialog
  host ComposeView** — all children of `FLXServerDisplay` / `DrawerLayout`
  (`XServerDisplayActivity.java:665-674,1871-1884`). Two viable strategies:
  1. **Counter-rotate the root content** via `View.setRotation(90/270)` on the controls/
     HUD/drawer container so logically-landscape UI is rotated into the portrait window.
     Android transforms MotionEvents into a rotated view's local space automatically, so
     hit-testing for `setRotation`'d views mostly "just works" — but `DrawerLayout` edge
     swipes, Compose IME/inset handling, and full-screen dialogs under a rotated root are
     historically fragile and need real device validation.
  2. **Keep a landscape-oriented UI layer** separate from the portrait game surface (e.g.
     the UI/controls live on their own rotated container while only the game SC is the
     portrait overlay). Cleaner separation, more plumbing.
- **Cursor** (its own SC at transform-0 panel space, `ScanoutContext.cpp:239-257`,
  `sendCursorToScanout` `GLRenderer.java:663`): the cursor geometry is computed in
  container/dst space and must now map through the **same** rotation as the game blit, or
  the pointer will land 90° off. Easiest: apply the identical rotation transform used in
  Phase 3 to the cursor's px/py before `setGeometry`.
- **HUD epoll-thread rule still applies:** content updates arrive on the epoll thread; any
  HUD view mutation must stay `post()`/`postInvalidate()` only (this bit the HUD twice).
- Device-testable: hold device sideways, confirm drawer opens/reads correctly, HUD upright,
  touch controls land where drawn, dialogs (Task Manager confirm) appear correctly,
  cursor tracks the finger/mouse.
- This is the phase most likely to consume the budget and the one most likely to expose
  "not worth it."

### Phase 5 — Input coordinate mapping — **M**
Touch/pointer events arrive in portrait window space; the game and the rotated UI expect
landscape:
- **Direct game pointer path:** `XForm.transformPoint(xform, event.getX(), event.getY())`
  → `injectPointerMoveDelta` (`XServerDisplayActivity.java:1096-1100`) and the
  captured-pointer path (`handleCapturedPointer` `:1051`). The view→guest `xform` must
  fold in the 90/270 rotation (axis swap + sign per direction) so a sideways-physical
  swipe maps to the correct guest pixel. Captured-pointer deltas need the same axis
  swap/sign.
- **On-screen controls / touchpad:** if Phase 4 uses `setRotation` on the controls
  container, their MotionEvents are already in rotated-local space and need little extra;
  if not, they need explicit remap.
- What breaks if skipped: pointer moves along the wrong axis / inverted, touch controls
  fire from the wrong screen region, drag/aim feels rotated. Game is unplayable even though
  it renders correctly.
- Device-testable: aim/look in an FPS or move the desktop cursor diagonally; motion matches
  finger.

### Phase 6 — Gating, exposure, and Vulkan parity — **M** (S if Vulkan deferred)
- Expose as an **additive, experimental tier on top of the shipped latency mode** — do
  NOT change P4+P5 default behaviour. Suggest a per-container/experimental flag
  "Full Overlay (portrait pre-rotate)" gated like ASR (API≥29, Adreno-first, off by
  default, reboot-risk caveat). Latency-only native stays the default native path.
- **Vulkan parity:** the rotation cause is renderer-agnostic (the bombshell showed Vulkan
  native also fails to promote on this portrait panel for the same transform-90 reason).
  The fix is conceptually identical: a Vulkan blit pass that rotates the guest frame into a
  panel-res VkImage/AHB and presents transform-0/src==dst. It **cannot** be shared in
  `ScanoutContext` (no GPU there) — only the *geometry contract* (panel-res,
  `setContainerSize`/`setDst`/transform-0, `ScanoutContext.cpp:185-196`) is shared; each
  renderer owns its own GPU rotate. Same diminishing-returns caveat applies (Vulkan
  compositor OFF also already overlays on portrait), so Vulkan parity is only worth it if
  Phase 0 proves the portrait pre-rotate path nets positive. Recommend deferring Vulkan
  until GL portrait is proven worthwhile.

---

## (c) Per-phase files to touch (anchors)

- **Phase 0:** none (uses `8b20b96` build + `dumpsys`).
- **Phase 1:** none (landscape-native device + `dumpsys`).
- **Phase 2 (detection/strategy):**
  - `app/src/main/java/com/winlator/star/XServerDisplayActivity.java:1015-1019`
    (orientation lock decision — currently only locks PORTRAIT when the *container* is
    portrait; add panel-native detection here) and `:1810-1838` (renderer launch wiring,
    `setInitialNativeMode`/`setSwapRB`).
  - `app/src/main/java/com/winlator/star/renderer/GLRenderer.java:579-595`
    (`setInitialNativeMode`/`setNativeMode`), `:611-636` (`enableScanout`/`disableScanout`)
    — choose direct vs pre-rotate path here.
- **Phase 3 (correct pre-rotation):**
  - `app/src/main/java/com/winlator/star/renderer/GLRenderer.java:695-` (`presentScanout`),
    `:819-` (`blitGuestIntoHostScanoutAndPresent` in Exp B — production version), host-AHB
    alloc/`ensureHostScanout`.
  - New `app/src/main/java/com/winlator/star/renderer/material/ScanoutBlitRot90Material.java`
    (replace Exp B's transpose with orientation-parameterized rotation + handedness +
    swapRB).
  - `app/src/main/cpp/scanout/ScanoutContext.cpp:185-196` (geometry contract; keep
    transform `0`, src==dst — already correct).
- **Phase 4 (UI under portrait):**
  - `app/src/main/java/com/winlator/star/XServerDisplayActivity.java:665-674` (drawer +
    dialog-host ComposeViews), `:1871-1884` (`InputControlsView`/`TouchpadView` add),
    `:164-166,642-654,928-931` (HUD views + update), `:466-497` (DrawerLayout).
  - `app/src/main/java/com/winlator/star/widget/XServerView.java:23-127` (FrameLayout +
    GLSurfaceView child; if a separate UI layer strategy is chosen).
  - Cursor: `app/src/main/java/com/winlator/star/renderer/GLRenderer.java:663`
    (`sendCursorToScanout`), `app/src/main/cpp/scanout/ScanoutContext.cpp:239-257`.
- **Phase 5 (input):**
  - `app/src/main/java/com/winlator/star/XServerDisplayActivity.java:1094-1100`
    (`onPointerMove`/transform), `:1051-1118` (`handleCapturedPointer`), `:2661-2688`
    (`dispatchGenericMotionEvent`).
- **Phase 6 (gating + Vulkan):**
  - GL gating: `GLRenderer.java:587-595` + the container "native" toggle plumbing
    (`Container.isRendererNative`).
  - Vulkan parity: `app/src/main/java/com/winlator/star/renderer/VulkanRenderer.java`
    (scanout push path) + `VulkanRendererContext.cpp` (add rotate blit); shared geometry
    in `ScanoutContext.cpp:185-196`.

---

## (d) Ranked risk list

1. **(HIGHEST) The win may not survive the blit cost on portrait (economic risk).** The
   normal compositor (Native OFF) already gets the DPU overlay on portrait, and the
   pre-rotate native path re-adds a full-frame GPU blit — so the net power/latency gain
   over just-not-using-native could be near zero. If Phase 0 shows no meaningful delta,
   Phases 2-5 are wasted. *Mitigation: Phase 0 gates everything.*
2. **(HIGH) UI/HUD/drawer/dialog under a portrait-locked activity.** Counter-rotating
   Compose + `DrawerLayout` + full-screen dialogs is fragile (edge swipes, insets, IME,
   hit-testing). This is the largest engineering surface and the most likely to ship subtly
   broken (drawer that won't swipe, dialog rotated, HUD clipped). *Mitigation: prototype the
   `setRotation` approach on one view early; fall back to separate landscape UI layer.*
3. **(HIGH) Input mapping correctness.** If the pointer/touch rotation is wrong the game is
   unplayable despite rendering correctly; captured-pointer delta sign/axis errors are easy
   to get backwards. *Mitigation: derive one rotation matrix shared by blit + cursor +
   input.*
4. **(MED) Per-device DPU variability.** The overlay acceptance (plain BGRA, transform-0,
   no scale) is proven on **one** Adreno 750 / SD8Gen3. Other Adreno/DPU generations may
   reject for plane-budget, format, or layer-count reasons even at transform-0. *Mitigation:
   gate experimental, sticky-fallback to direct/normal present (already in Exp A/B).*
5. **(MED) Orientation-change / activity-recreation churn.** Portrait-lock toggling and
   `configChanges` reconfigure-in-place can leak SCs or black-flash on rotate/background→
   foreground. *Mitigation: reuse P3/P4 teardown lifecycle; test rotate + bg/fg.*
6. **(LOW) Aspect/letterbox at panel res.** Container render-res not matching panel AR needs
   correct letterbox in the rotate blit (Exp B ignored it). *Mitigation: standard
   aspect-fit math in Phase 3.*
7. **(LOW) Vulkan divergence.** Building GL-only leaves Vulkan native still transform-90 on
   portrait — acceptable (latency-only), but document so it is not mistaken for a bug.

---

## (e) Effort sizing

| Phase | Description | Size |
|---|---|---|
| 0 | Measure-first de-risk (capped-FPS power/latency A/B) | **S** |
| 1 | Confirm clean win on landscape-native panel | **S** |
| 2 | Panel-orientation detection + strategy switch | **M** |
| 3 | Orientation-aware correct pre-rotation | **M** |
| 4 | UI/HUD/drawer/cursor under portrait activity | **L** |
| 5 | Input coordinate mapping | **M** |
| 6 | Gating + (optional) Vulkan parity | **M** (S w/o Vulkan) |

Total to a shippable portrait Full-Overlay tier: **~2×S + 3×M + 1×L** (Phase 6 Vulkan
optional). The L (Phase 4) dominates and carries most of the schedule risk.

---

## (f) Recommendation

**Build Phase 0 and Phase 1 now; do NOT commit to Phases 2-5 until Phase 0 says yes.**

Be honest about where the value is:
- The **clean, high-value win is on landscape-native panels** (AYANEO etc.), and the
  **already-merged P4+P5 delivers it with zero new code** — guest buffer pushed direct,
  transform-0, DPU overlay, no blit. Phase 1 just confirms it. That is the headline
  feature and it is essentially free.
- On **portrait panels**, Experiment B genuinely proved the overlay is *attainable*, which
  is a great correction to the earlier "impossible" bombshell — but the normal compositor
  on those same panels **already overlays**, and the pre-rotate native path re-introduces a
  blit of comparable cost. So the marginal benefit is small and the cost (orientation-aware
  rotation + counter-rotating the entire Compose/HUD/controls/dialog UI + input remapping)
  is large and risky. This is the textbook diminishing-returns trap.

**Smallest first phase that de-risks the whole thing: Phase 0** — one capped-FPS
power/latency/temp A/B on the existing `8b20b96` build (OFF-compositor vs P4-native vs
Exp-B-native), no new code. If the pre-rotate path does not measurably beat the normal
compositor on portrait, we have our answer cheaply and we ship the landscape-native win
(Phase 1) plus the already-merged latency-mode, and we shelve the portrait pre-rotate.
If Phase 0 does show a worthwhile margin, proceed Phase 2 → 3 → 5 (input) → 4 (UI, the
expensive one) → 6, keeping it an experimental, off-by-default, gated tier exactly like
ASR, and defer Vulkan parity until GL portrait is proven in the field.
