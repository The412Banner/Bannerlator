# DisplayX Direct-Feed Protocol (our own design)

**Status:** draft · **Author:** The412Banner · **Scope:** the guest→host frame handoff for the
DisplayX (SurfaceControl overlay) renderer.

This is **our** protocol. It is not Pipetto's DisplayX socket format and shares no code with it.
It is built entirely on machinery we already own: the X11 **Present** extension, our **GPUImage**
AHardwareBuffer sharing, and the X **Sync** extension. The goal is to reach the same place his
layer reaches (zero-copy, fenced, correct-colour present) using our existing transport, so there is
no clean-room obligation at all — both ends are already ours.

---

## 1. Why this exists / what it replaces

The DisplayX host compositor has two present paths:

- `updateWindow()` — **CPU memcpy** of window pixels into an AHB every frame. Slow. Current default
  for the direct-content-less case.
- `updateWindowDirect()` — shows an AHB the guest already produced, via
  `ASurfaceTransaction_setBuffer(txn, control, ahb, -1)`. Zero-copy, **but** the `-1` means **no
  acquire fence**, and there is **no release fence back to the guest**, so it is not safe for a
  continuously-rendering game (host may scan out a half-written frame; guest may overwrite a buffer
  the compositor is still reading).

This protocol defines the contract that makes `updateWindowDirect()` correct and complete:
guest-produced AHBs, an **acquire fence** in, a **release fence** back, and an explicit **format**.

**Good news — the transport already exists and is proven by ASR:**

- Guest DXVK/vkd3d renders into a `GPUImage` backed by an `AHardwareBuffer`
  (`GPUImage.getHardwareBufferPtr() != 0`).
- Guest presents via `PresentExtension.presentPixmap()` (X11 Present), carrying `serial`, the
  pixmap, and an `idleFence` (an X Sync fence id).
- For AHB-backed pixmaps the host already routes to a **FLIP** (direct) present and calls
  `asr.presentWindow(window, content)`; otherwise a **COPY** fallback.
- The ASR JNI entrypoint **already accepts `(ahbPtr, fenceFd, windowId, serial, slot, sfCompatMode)`** —
  i.e. an acquire-fence FD and a format-compat flag already flow to ASR. DisplayX's direct path just
  drops both.

So this is not a new pipe. It is **adding the two fields ASR already carries (acquire fence +
format) to the DisplayX direct path, and formalising the release-fence return** so buffers recycle
safely.

---

## 2. Actors and existing symbols

| Actor | Where | Role |
|---|---|---|
| Guest present | Wine/DXVK/vkd3d → X11 Present | Renders into AHB, calls PresentPixmap with an acquire fence |
| `PresentExtension` | `xserver/extensions/PresentExtension.java` | Receives present, decides FLIP vs COPY, sends Idle/Complete notifies, paces for FPS limiter |
| `SyncExtension` | `xserver/extensions/SyncExtension.java` | X Sync fences; `setTriggered(idleFence)` releases the guest |
| `GPUImage` | `cpp/winlator/gpu_image.c`, `renderer/GPUImage` | Pixmap texture backed by a shared AHB |
| `DisplayXRenderer` | `renderer/DisplayXRenderer.java` + `cpp/displayx/*` | Host SurfaceControl compositor |
| `directContents` map | `cpp/displayx/displayx_jni.cpp`, `window.hpp` | `drawableId → Drawable{ahb,…}` registry per window |

Current DisplayX JNI surface (branch `feat/displayx-renderer-current`):

- `nativeSetDirectContent(windowId, drawableObj, hardwareBuffer)` — register an AHB drawable
  (`isDirectContent=true`, `format=HAL_PIXEL_FORMAT_BGRA_8888` hardcoded).
- `nativeUpdateDirectContent(windowId, drawableId)` — set `currentDirectContent`, request update →
  `updateWindowDirect()` → `setBuffer(…, ahb, -1)`.
- `nativeRemoveDirectContent(windowId, drawableId)` — drop the registration.

---

## 3. The protocol

Three logical channels. All are already carried inside the X11 Present/Sync flow plus our JNI — no
new socket.

### 3.1 Registration (guest allocates, host imports) — once per buffer

```
REGISTER  { windowId, drawableId, ahbHandle, width, height, format, usage }
UNREGISTER{ windowId, drawableId }
```

- Guest allocates the swapchain images as AHBs (already done by GPUImage) and shares the handle.
  Host imports each **once** and caches it in `directContents[drawableId]` — never per frame.
- `format` is explicit (see §5). Registration is idempotent per `drawableId`.
- Maps to existing `nativeSetDirectContent` / `nativeRemoveDirectContent`, **plus a `format` arg**.

### 3.2 Present (guest → host) — once per frame

```
PRESENT { windowId, drawableId, serial, acquireFenceFd, damageRect?, targetMsc? }
```

| Field | Meaning |
|---|---|
| `drawableId` | which pre-registered AHB holds this frame |
| `serial` | guest's present serial (echoed back in Idle/Complete) |
| `acquireFenceFd` | **sync_fd** that signals when the guest's GPU render into the AHB is complete. `-1` only if the guest guarantees CPU-side completion (COPY path). |
| `damageRect?` | optional dirty region; absent = full surface |
| `targetMsc?` | optional target vsync for pacing / FPS limiter |

- Host passes `acquireFenceFd` straight into
  `ASurfaceTransaction_setBuffer(txn, control, ahb, acquireFenceFd)` — SurfaceFlinger waits on it
  before scanning out. **This is the single most important change** vs the current `-1`.
- Maps to `nativeUpdateDirectContent`, **extended with `acquireFenceFd` (and `serial`)**.

### 3.3 Release / completion (host → guest) — once per presented frame

```
IDLE_NOTIFY    { windowId, serial, releaseFenceFd }   // buffer is reusable
COMPLETE_NOTIFY{ windowId, serial, kind, mode, ust, msc }  // frame hit the screen (timing)
```

- `releaseFenceFd` comes from SurfaceFlinger via
  `ASurfaceTransaction_setOnCompleteCallback` → `ASurfaceTransactionStats_getPreviousReleaseFenceFd(control)`.
  It signals when the compositor is **done reading** the previous buffer.
- The guest must **wait on `releaseFenceFd` before rendering into that AHB again**. This is what
  makes N-buffering safe and prevents tearing/corruption. Today we only ever send a CPU-triggered
  idle (`syncExtension.setTriggered(idleFence)`), which does not actually know when the GPU/compositor
  finished — the release fence fixes that.
- We keep the existing `emitIdleNotify` FPS-limiter pacing; the release fence rides inside it.
- `COMPLETE_NOTIFY` already exists (`sendCompleteNotify`, UST/MSC) — unchanged.

### 3.4 One-frame sequence

```
guest: render into AHB[i]  ──(export sync_fd)──▶ acquireFenceFd
guest: PresentPixmap(drawableId=i, serial=S, acquireFenceFd)
host : setBuffer(control, AHB[i], acquireFenceFd); apply()          // SF waits on acquire
host : (on SF complete) releaseFenceFd = getPreviousReleaseFenceFd()
host : IDLE_NOTIFY(serial=S-? , releaseFenceFd)  +  COMPLETE_NOTIFY(serial=S, ust, msc)
guest: wait(releaseFenceFd) before reusing AHB[i]
```

---

## 4. Buffer ownership & lifecycle

- **Guest owns allocation** (it already does — GPUImage). Host only imports/holds a reference.
- **N ≥ 2 buffers** required for pipelining (guest renders N+1 while host scans out N). Single-buffer
  = forced stall (this is the trap in Pipetto's single-image demo; we do not copy that limit).
- A buffer is **guest-writable again only after its `releaseFenceFd` signals.** Before that it is
  owned by the compositor.
- On resize / format change: UNREGISTER all, reallocate, REGISTER new set. `currentDirectContent`
  is cleared on unregister (already handled).
- On renderer teardown / surface loss: host releases all imported AHBs; guest fences are closed.

---

## 5. Format negotiation (kills the R/B swap)

The ASR path needs a GL shader swap because it samples the buffer; **SurfaceControl does not** — it
hands the AHB straight to SurfaceFlinger, which composites the buffer **in its declared HAL format**.
So the rule is simply: **declare the AHB in the format the guest actually writes.**

- DXVK swapchains are almost always `B8G8R8A8` → allocate/declare the AHB as
  `HAL_PIXEL_FORMAT_BGRA_8888` (which the branch already hardcodes). SurfaceFlinger composites BGRA
  overlays natively → **no swap, no shader, no `sfCompatMode` needed on this path.**
- Make `format` an explicit REGISTER field anyway (don't hardcode), so RGBA/RGBX/10-bit guests are
  handled and future HDR is possible.
- If a device's SurfaceFlinger refuses a given format as an overlay, **fall back to the ASR path**
  for that window (see §6) rather than showing wrong colours.

This is the concrete reason DisplayX can retire the GN#1620/#1622 colour-correction toggle on its
own path: the correction becomes "declare the truth," not "fix it after."

---

## 6. Fallback / failure handling

- No AHB on the pixmap (`getHardwareBufferPtr()==0`) → **COPY path** (existing `updateWindow` memcpy).
  Already how `presentPixmap` decides FLIP vs COPY.
- `acquireFenceFd < 0` → treat as already-signalled (CPU-complete) buffer; safe for COPY, discouraged
  for FLIP.
- Format rejected as overlay by SurfaceFlinger → downgrade that window to ASR renderer, log once.
- Import failure / OOM → COPY path, log once.
- Never block the guest indefinitely: if no release fence arrives within a deadline, fall back to the
  CPU-triggered `setTriggered(idleFence)` we use today (degraded but not hung).

---

## 7. Concrete deltas (implementation map)

**Native — `cpp/displayx/`**
- `updateWindowDirect()`: replace `setBuffer(…, ahb, -1)` with `setBuffer(…, ahb, acquireFenceFd)`.
- Register an `ASurfaceTransaction_setOnCompleteCallback`; in it call
  `getPreviousReleaseFenceFd(control)` and hand the FD + serial back up to Java.
- `Drawable`: carry `format` and the pending `acquireFenceFd`.

**JNI surface**
- `nativeSetDirectContent(windowId, drawableObj, hardwareBuffer, format)`  ← add `format`.
- `nativeUpdateDirectContent(windowId, drawableId, acquireFenceFd, serial)` ← add fence + serial.
- new: `nativeSetReleaseCallback(...)` / an upcall `onBufferReleased(windowId, serial, releaseFenceFd)`.

**Java — `PresentExtension.java`**
- On FLIP present to a DisplayX window: pass the guest's exported acquire fence FD and `serial`
  through to `nativeUpdateDirectContent` (mirror what ASR already receives as `fenceFd`).
- On `onBufferReleased`: route `releaseFenceFd` into the existing `emitIdleNotify`/`sendIdleNotify`
  path so the guest waits on it (extend `PresentIdleNotify` to carry a real fence, or trigger the X
  Sync `idleFence` off the release fence signalling).

**Guest side (Wine/DXVK WSI)**
- Export the present-complete semaphore as a `sync_fd` and attach it as the Present acquire fence.
- Import the returned release fence and wait on it before reusing the swapchain image.
- (If our WSI already exports a fence for the ASR path, reuse that — no new guest work.)

---

## 7a. Verified finding — where the acquire fence actually comes from (read the code, 2026-08-05)

The "does the guest already produce a paint-is-dry fence?" question has a **two-part answer**, and it
changes the cost of P1:

- **CPU / software content (`AHBImage`): fence already exists, fully wired.** The guest draws into a
  CPU/virtual buffer; ASR **copies** it into a fenced swapchain AHB slot
  (`AHBImage.copyHardwareBuffer(virtualData → targetAhb, waitFence)`), and the returned fence is
  exposed as `consumeAcquireFence()` and fed to `nativeSetWindowBuffer(..., acquireFence, ...)`.
  There is also a per-slot **release** fence back to the swapchain. **DisplayX can reuse this pattern
  almost for free** — but note this is the *copy-completion* fence, i.e. still the copy path, not
  zero-copy.
- **True GPU game frames (`GPUImage`, the FLIP path DisplayX cares about): no real GPU fence today.**
  `PresentExtension` routes AHB-backed pixmaps (`GPUImage.getHardwareBufferPtr() != 0`) to a direct
  FLIP present, but a grep of the native tree finds **no `sync_fd` / `VkGetFenceFdKHR` / external-fence
  export** anywhere. So the guest's DXVK render-completion is **not** currently exported as a fence on
  this path. Ordering is instead enforced at the **Present-protocol level**: the guest blocks on
  `PresentIdleNotify` before reusing a buffer.

**Consequences:**
- Passing `-1` as the acquire fence on the DisplayX FLIP path is **not catastrophic today** — the same
  Present/Idle handshake that lets ASR's FLIP path work covers ordering. A first device test can
  proceed without the acquire fence.
- The genuinely important fix is therefore **P2 (release fence back to the guest)**, not P1. Today's
  idle-notify can be sent (and is, paced by the FPS limiter) *before* SurfaceFlinger has finished
  reading the buffer — that is the real corruption risk for a continuously-rendering game.
- A **true** GPU acquire fence (P1 in its strong form) requires **new guest-side work**: export the
  DXVK present-complete semaphore as a `sync_fd`. That does not exist yet. It is an optimisation
  (lets guest render N+1 overlap host scanout of N), not a prerequisite for a working test.

## 8. Open decisions (need a call before coding)

1. ~~Does our WSI already export a `sync_fd`?~~ **Answered (see §7a):** yes for the CPU/`AHBImage`
   copy path, **no** for the true GPU/`GPUImage` FLIP path. Decision: **do P2 (release fence) first**
   — it is the actual correctness fix — and treat the strong GPU acquire fence as a later optimisation
   needing guest WSI work.
2. **Release-fence transport to guest** — extend `PresentIdleNotify` with a real fence FD, or keep
   using the X Sync `idleFence` object but signal it off the release fence? The latter changes less
   protocol; the former is cleaner. Leaning: **signal existing `idleFence` off the release fence.**
3. **Buffer count** — fix at 3, or let the guest's swapchain length drive it? Leaning: **follow the
   guest swapchain count**, minimum 2.
4. **Damage/`targetMsc`** — ship v1 without them (full-surface, immediate) and add for the FPS
   limiter later? Leaning: **yes, defer.**

---

## 9. Phasing

- **P0 (done):** host renderer rebased + CI-green on `feat/displayx-renderer-current`. Device-test
  the FLIP path as-is first — per §7a the Present/Idle handshake already covers ordering, so it may
  render correctly even before any fence work. Confirm colours (declared BGRA) and basic stability.
- **P1 — release fence back (the real fix, per §7a):** capture SurfaceFlinger's release fence via
  `getPreviousReleaseFenceFd()` and gate the guest's idle-notify on it instead of firing early.
  Device-test: a continuously-rendering game (not a menu) with no corruption over minutes.
- **P2 — explicit format field:** replace the hardcoded BGRA with a negotiated `format`, plus the
  overlay-rejected → ASR fallback.
- **P3 — strong GPU acquire fence (optimisation, needs guest WSI work):** export the DXVK
  present-complete semaphore as a `sync_fd`, feed it to `setBuffer(…, acquireFenceFd)` for
  render/scanout overlap. Then compare fps/frametime vs ASR on the same title to prove the win.

Each phase is independently testable and independently revertable.
