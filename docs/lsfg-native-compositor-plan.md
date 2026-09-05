# Native LSFG frame generation in the Bannerlator compositor

Phased plan to move Lossless Scaling frame generation from the in-container
Vulkan layer (`lsfg-vk`) into Bannerlator's own Vulkan compositor, on the
Android side of the Wine boundary.

**Status:** DEVICE-PROVEN on `feat/lsfg-native-compositor`, rebased onto
`main` 3.0.5 (vc 79). 30 fps in, 118-120 fps at the panel on DiRT Showdown
(Adreno 750), confirmed independently by the AYANEO system overlay and by the
user's own eyes.

| Phase | State |
|---|---|
| 0 — device features + probe | device-proven (Vulkan 1.3.128, all three features, storage fmt) |
| 1 — `Lossless.dll` → modules + cache | device-proven (25 modules, dxbc-translated) |
| 1b — DXBC → SPIR-V (vendored DXVK `dxbc`) | device-proven |
| 2 — storage-capable composite ring | device-proven |
| 3 — compute + the 25-shader chain | device-proven |
| 4 — multi-present, one submit per source frame | device-proven (30 → 120, 4 presents/source) |
| 5 — probe/backoff governor + thermals | built; **OFF by default** at the user's decision |
| 6 — engine wiring, UI, per-present cursor, HUDs | device-proven except the cursor overlay (no desktop-cursor run yet) |
| 7 — CI build + staged APK | r11 staged |

Policy, decided by the user after six device runs: the governor is bypassed
(the device's own thermal management is the authority); the panel-headroom
clamp is removed; while native FG generates the FPS limiter is locked ON and
Auto refresh (VRR) locked OFF; every launch starts with frame gen OFF. See the
progress log for the nine bugs found on device and why each mattered.

The unmerged `feat/framegen-compositor-slot` branch is **not** the base. Its
pacer and multi-present cadence were instead built fresh here, designed for a
generating producer from the start rather than retrofitted from a
de-coalescing one. That branch remains independent; the overlap is the pacer,
and this implementation supersedes it.

---

## 1. Why

Today `lsfg-vk` runs **inside the container**: an implicit Vulkan layer in the
guest's Vulkan loader, hooking the game's own swapchain, driven by a
`conf.toml` we rewrite and a `vsync.txt` clock file we publish for it to
phase-lock against. Everything it generates then has to travel out through
Wine → DRI3/Present → our X server → the compositor → SurfaceFlinger.

That round-trip is the source of the entire bug class we have been chasing:

| Symptom | Where it lives today |
|---|---|
| Generated frames counted by the HUD but never reaching the panel | `VulkanRendererContext::updateWindowContentAHB` keeps one latest-wins texture per window; a generated frame arriving microseconds before the next real frame is overwritten before the render thread presents it |
| MAILBOX forced on whenever multiplying | `XServerDisplayActivity.java:1631` — "FIFO backpressure would strangle the generated frames" |
| Full pause / surface-teardown / "Resume" prompt on every multiplier change | `maybeTriggerFgReset`, because a swapchain-only recreate leaves the layer over-queued and generated frames present black |
| A clock file published for the layer to sync to | `startVsyncClock()` → `.config/lsfg-vk/vsync.txt` |

None of these are bugs in the interpolation. They are all the cost of
generating a frame on the far side of a process and translation boundary and
then trying to shepherd it back.

The precondition to delete all of it is already satisfied here: **the guest's
finished frame is already a `VkImage` in our own Vulkan device before anything
is presented** — `VulkanRendererContext.cpp:809 importAHBToWinTex()`, imported
zero-copy from the DRI3 `AHardwareBuffer`. We are structurally in the position
eden's Vulkan renderer is in, not the position a screen-capture overlay is in.

Prior art reviewed: WinNative's `docs/lsfg-frame-generation.md` (commit
`994171e`), which ports eden PR #4263 by Camille LaVey. Both GPL-3.0, as are
we. Their measurements and their ordering are reused where they hold; their
file layout is not, because our renderer is not theirs.

### What this is not

- **It does not replace win-fg.** win-fg stays the default engine: our own,
  weightless, needs no DLL.
- **It does not replace `lsfg-vk` either.** Native LSFG is a *third* engine
  (the fourth entry in the dropdown, after Off).
  It only exists on the native Vulkan compositor path, so the GL renderer, the
  ASR/SurfaceFlinger path and direct scanout still need the in-container layer.
  `lsfg-vk` is shipping and device-proven; it stays.
- **It does not reduce input latency.** Interpolation always adds one output
  interval (~16.7 ms at 60 Hz) because real frame N cannot be shown until the
  frames synthesised between N−1 and N have been shown first. Moving the code
  removes the *guest round-trip*, not the interpolation delay. Any user-facing
  copy must say "smoother, very slightly less responsive."
- **It bundles nothing.** The shader chain is parsed out of the user's own
  legally-owned `Lossless.dll`, which is mapped read-only and never loaded or
  executed. No repeat of the shader-provenance problem that killed the
  pre-win-fg layer.

---

## 2. Verified starting state

Everything below was read out of the tree, not recalled.

**Already ours, reusable as-is:**

- Zero-copy AHB → `VkImage` import in our device — `VulkanRendererContext.cpp:809`.
- An offscreen composite target and a full effect chain (SGSR, FSR EASU/RCAS,
  CAS, HDR, FXAA, toon, colour, NTSC, CRT, deband) — `:605`, currently
  `COLOR_ATTACHMENT | SAMPLED`, `offscreenRenderPass` at `:508`.
- Instance API version is already `VK_API_VERSION_1_3` — `:262`.
- `Lossless.dll` import flow (user picks it in Settings; lands at
  `filesDir/lsfg-vk/Lossless.dll`), per-container and per-shortcut engine
  override, multiplier 2–4×, flow scale, perf preset, FG drawer, HUD counters.
- Steam already knows Lossless Scaling: app 993090, and
  `SteamDepotDownloader.kt:92` already records that depot 993092's DLL lags
  993091 by a build.

**Confirmed missing:**

- **No compute path at all.** `grep -n "COMPUTE\|vkCmdDispatch\|ComputePipeline"`
  over `VulkanRendererContext.cpp` returns **zero hits**. Every pipeline is a
  graphics pipeline drawing a fullscreen triangle.
- **No device features enabled.** `createLogicalDevice()` at `:300-322` passes
  `pEnabledFeatures = nullptr` and no `pNext` chain. LSFG requires
  `vulkanMemoryModel`, `shaderStorageImageWriteWithoutFormat` and
  `shaderStorageImageExtendedFormats` — none are on.
- **Descriptor pool is single-type.** `:694` — `COMBINED_IMAGE_SAMPLER` × 160,
  `maxSets` 160. No `STORAGE_IMAGE` capacity.
- **`MAX_FRAMES_IN_FLIGHT = 2`** — `VulkanRendererContext.h:116`; sync objects
  are indexed per `currentFrame`, i.e. per *composite*, not per *present*.
- **Swapchain is `COLOR_ATTACHMENT_BIT` only** — `:369`. Not storage-capable,
  and Android drivers rarely offer storage on a swapchain format.
- No PE resource walk, no shader cache, no DXBC translator.

---

## 3. The design in one picture

```
guest DXVK ──DRI3/AHB──> VkImage (ours, zero-copy) ──> scene pass ──> effect chain
                                                                          │
                                                    ┌─────────────────────┴──────────────┐
                                                    ▼                                    ▼
                                          composite target ring                    LSFG input ring
                                    (STORAGE|SAMPLED|TRANSFER_SRC|DST                (2 deep)
                                          |COLOR_ATTACHMENT)                              │
                                                    │                        shared chain: mipmaps
                                                    │                        → alpha → beta → gamma
                                                    │                        → delta       │
                                                    │                        generate × N ─┤
                                                    ▼                                      ▼
                                        present(real frame N) ◄── after ── present(gen 0…N−1)
```

Generated frames are presented **before** the real frame: they belong between
N−1 and N, so N is held back one slot. This is the unavoidable interpolation
delay named above.

---

## 4. Phases

Each phase states its own acceptance criterion. Phases 0–2 all land safely with
frame generation disabled and must be provably zero-cost when off.

### Phase 0 — Device enablement and capability probe
*Small, invisible, unblocks everything.*

- Chain `VkPhysicalDeviceVulkan12Features` (`vulkanMemoryModel`) and
  `VkPhysicalDeviceFeatures2` (`shaderStorageImageWriteWithoutFormat`,
  `shaderStorageImageExtendedFormats`) into `createLogicalDevice()` — query
  first, enable only what the device offers, never fail device creation.
- Add a `lsfgProbe()` reporting device API ≥ 1.3 (SPIR-V 1.6 will not load on a
  1.1 device), the three features, and
  `VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT` on the live swapchain format.
- Surface the verdict through JNI so the UI can grey the option out with a
  reason instead of failing later at `vkCreateShaderModule`.

**Files:** `VulkanRendererContext.cpp/.h`, `vulkan_jni.cpp`.
**Accept:** probe result logged on every device we can reach; existing rendering
byte-identical; no new extension is *required* for startup.

### Phase 1 — `Lossless.dll` → shader modules on device

- PE parser: walk headers, section table and resource tree, collect the RCDATA
  blobs for the chain. Map read-only; never load or execute.
- On-device cache keyed on file size + hash + variant, written via temp file +
  rename so a failed build cannot leave a half-written cache.
- Record which producer ran (`spirv-fp16` / `spirv-fp32` / `dxbc-translated`)
  in the cache header, surfaced in diagnostics.
- Keep the existing Settings import; **add** auto-detect of
  `<container>/drive_c/Program Files (x86)/Steam/steamapps/common/Lossless Scaling/Lossless.dll`,
  since unlike eden we actually install Steam games into containers.

**Accept:** 25 modules cached from a real DLL; status and variant visible in
settings; cache survives a restart; a wrong/corrupt file reports a clear
distinct error.

### Phase 1b — DXBC → SPIR-V translation
*The single biggest chunk, and non-optional.*

WinNative measured this and retracted their own earlier assumption: **no
currently downloadable Lossless Scaling build carries the precompiled SPIR-V
blobs.** Public buildId 19655272 (2025-08-19) is the newest on any branch,
`linux_testing` is byte-identical to `public` on depot 993091, a scan of the
whole 311 MB / 456-file install finds zero SPIR-V magic words, and the DLL's
202 RCDATA entries are all DXBC. Eden's translator-free path does not work on
anything a user can buy today.

- Vendor DXVK's `dxbc` subset (zlib licence) under `app/src/main/cpp/thirdparty/dxbc`.
- Translate with the **encounter-order binding renumber** that pairs with DXVK
  output (not a set/binding sort — that belongs on the precompiled path only).
- Keep both producers behind one consumer: a `shader id → SPIR-V words` map,
  so a future DLL that ships SPIR-V is picked up for free.

**Accept:** 25/25 modules translated from a real 3.2.x DLL and 25/25 clean
under `spirv-val --target-env vulkan1.3`; emitted SPIR-V 1.6 with
`OpMemoryModel Logical Vulkan`; bindings dense 0..n.

### Phase 2 — Storage-capable composite target ring, flag off

- Extend the existing offscreen target to
  `STORAGE | SAMPLED | TRANSFER_SRC | TRANSFER_DST | COLOR_ATTACHMENT`, gated
  on the Phase 0 format probe.
- Make it a **ring**, `(max_generations + 1) × (queue_target + 1)` deep,
  rotating on frame index — which also gives the 2-deep history the chain needs.
- With the flag on, the last effect pass (or the scene pass when there are no
  effects) targets a composite image and a blit moves it into the acquired
  swapchain image. With the flag off, the existing direct-to-swapchain path is
  untouched.
- Add `TRANSFER_DST` to swapchain usage **only** while armed, and include
  `TRANSFER` in the acquire-semaphore wait stage when the composite path is
  active — on that path the first access to the swapchain image is a transfer
  write, which precedes `COLOR_ATTACHMENT_OUTPUT` in pipeline order.

**Accept:** pixel-identical output with the flag off and no measurable frame
cost; with the flag on, output still pixel-identical (the blit is the only
difference); no validation errors.

### Phase 3 — Compute support and the chain

- New subsystem: compute pipeline creation, `STORAGE_IMAGE` descriptor pool
  capacity, storage-image barriers. Everything else in the renderer stays
  graphics.
- Port the chain — `mipmaps` (luma pyramid, 7 levels), `alpha` (coarse-to-fine
  flow, 3-deep history), `beta` (refinement), `gamma` (upsample/confidence),
  `delta` (occlusion + warp field), `generate` (final warp/blend). Only
  `generate` runs per generated frame; everything above is shared across all
  generations from one frame pair, which is why 3× costs far less than 1.5× of 2×.
- Two-frame warm-up before anything is emitted, so history slots are valid.
- Preserve eden's and lsfg-vk's GPL-3.0 headers verbatim on every ported file.

**Accept:** flow-pyramid debug dump matches the reference on the same input
pair; a generated frame is visually plausible in a still capture; no validation
errors; memory footprint logged.

### Phase 4 — Multi-present rework

- `MAX_FRAMES_IN_FLIGHT` 2 → `max_generations + 2`; swapchain `minImageCount`
  requested as `clamp((generations + 1) × (queue_target + 1), minImageCount + 1, 8)`.
- **Reindex sync objects per present, not per composite.** `vkAcquireNextImageKHR`
  is called N+1 times per real frame, so each pending present needs its own
  image-available / render-finished semaphore and fence. This is the part the
  renderer does not do today: sync objects are indexed per `currentFrame`,
  i.e. per composite.
- Implement `FrameProducer::produce(k, n)` for LSFG: dispatch `generate` for
  frame k into a composite target, blit, submit, present — then present the
  real frame last.
- **Present back-pressure must count real presents only.** Our
  `PresentExtension` IdleNotify pacer (`PresentExtension.java:131 emitIdleNotify`)
  releases guest buffers per present; if generated presents are counted, the
  guest is told it can render N× faster and the pacer fights itself.
- Keep the producer seam engine-agnostic: LSFG implements `produce(k, n)` by
  dispatching `generate`. A future guest-side engine that delivers
  pre-composited frames would implement the same seam by handing over its k-th
  queued buffer. One pacer, two possible producers.

**Accept:** 2× shows exactly 2 presents per guest frame in the renderer log and
in SurfaceFlinger; FG-off path unchanged; no validation errors; guest frame
rate does not inflate.

### Phase 5 — Pacer probe and backoff

Our `Pacer` has the smoothing and the refresh-headroom clamp. It is missing the
part that stops frame gen making a GPU-bound game *slower* — on a phone SoC the
chain competes with the game for the same GPU.

- Probe one extra generation periodically; keep it only if measured throughput
  improves by ≥1.15× **and** the base rate does not collapse below 0.70×.
- Backoff 5 / 15 / 30 / 60 s on repeated failures; 1 s stabilisation window;
  reject burst frames.
- Bannerlator-specific inputs: the existing FPS limiter target, the panel's
  real refresh rate (never generate above it), and thermal state — sustained
  frame gen on a handheld is a thermal decision as much as a perf one.

**Accept:** 3× on a deliberately GPU-bound title backs off instead of losing
real frames, visible in the log; a 60 Hz panel self-limits with no added latency.

### Phase 6 — Integration, UI, retirement of the layer

- New engine entry alongside `"bionic"` (Win-FG) and `"lsfg"` (LSFG-VK) —
  `SpecCardComponents.kt:61-62`, `ContainerDetailScreen.kt:1168-1180`,
  `ShortcutsScreen.kt:7162-7184`, `GlossarySheet.kt:122`.
- The guest-side workarounds — the MAILBOX override
  (`XServerDisplayActivity.java:1631`), `maybeTriggerFgReset`,
  `startVsyncClock()`/`vsync.txt`, the `conf.toml` rewrite — stay for the
  `"lsfg"` engine and are **bypassed**, not deleted, when the engine is
  `"lsfg-native"`. None of them apply to a generator running in our own
  command stream.
- HUD: real vs total frames, and the active variant.
- Recorder must capture **real** frames only, or recordings get generated
  frames at an inconsistent cadence.
- Software cursor: exclude it from interpolation and redraw per present, or it
  ghosts. `draw_scene_pass` already draws it separately from windows, so this
  is a pass split, not a redesign.
- Frame gen runs **after** the effect chain, so SGSR/CRT/HDR output is what gets
  interpolated and generated frames match the real ones visually.
- **`lsfg-vk` stays. This is a third engine, not a replacement.** Native LSFG
  only works on the native Vulkan compositor path — it is unavailable on the
  GL renderer, the ASR/SurfaceFlinger path, and direct scanout, so it cannot be
  universal. `lsfg-vk` is shipping and device-proven and remains the fallback
  for every path and every device that fails the Phase 0 probe. Retiring it, if
  ever, is a separate decision made after native LSFG is device-proven.
- Engine key `"lsfg-native"`, label "LSFG (Native)", greyed with a reason when
  the probe fails.

**Accept:** end to end on device, per-container and per-shortcut, engine
switchable without a restart.

### Phase 7 — Build and hand-off
*The deliverable.*

Per standing rules: never build locally, push and let CI build; keep
`versionCode` **frozen at 78**; build the **`pubg`** flavour, which installs as
`com.tencent.ig`; stage by copying the artifact to the device's
`/sdcard/Download/` — never `pm install`; verify the staged file's sha256
against the CI artifact before handing it over; and confirm the CI run's
`headSha` equals the pushed SHA.

You uninstall the previous build and install fresh, as always.

---

## 5. Risks, carried forward honestly

- **Adreno 6xx and Mali.** The chain is 25 dispatches over a 7-level pyramid.
  On older parts the shared chain alone may exceed the frame budget. The
  capability gate should be conservative and the pacer's backoff is the safety
  net. Your Adreno 750 is the proving ground; a Mali tester is still an open
  need across the project.
- **Storage-image format support.** Probe and disable rather than fail at
  pipeline creation.
- **Memory.** 7 mip levels × alpha history × beta/gamma/delta temporaries at
  swapchain resolution is a real allocation. Flow scale is the mitigation and
  should default to auto.
- **Direct scanout.** Our `scanoutActive` path bypasses the compositor
  entirely; frame gen must be gated off whenever it is active.
- **Interaction with SGSR1.** Frame gen must sit after upscaling or it
  interpolates at the wrong resolution and the upscaler re-processes
  synthesised content.
- **Overlap with the frame-gen slot branch.** That branch is unmerged, only
  partially device-proven, and its FG-off base-rate drop is unexplained. This
  work deliberately does not build on it. The overlapping piece is the pacer;
  this implementation supersedes it.
- **Licence hygiene.** Preserve eden's and lsfg-vk's GPL-3.0 headers on every
  ported file; DXVK `dxbc` is zlib. Never bundle, download, or redistribute any
  part of `Lossless.dll`.

---

## 6. Size

From the 2026-09-02 two-agent scope, unchanged by anything found since:
**~15 engineering-days of native/compositor work** to CI-green and first
device-proof on Adreno 750, plus **~5 days of app/UI work** that is inert until
the native half lands. The dominant risk and the dominant cost are both Phase 4,
the `renderFrame` restructure, because that is where the effect chain,
`OUT_OF_DATE`/resize recovery and black-screen regressions all collide.

## 7. Sequencing

Phase 0 first (everything depends on it). Phases 1/1b and Phase 2 are
independent of each other and both land safely with frame gen off. Phase 3
needs 0+1b+2. Phase 4 needs 3. Phases 5 and 6 need 4. Phase 7 is the CI build
and the staged APK, run once at the end against the complete feature.

## 9. Follow-ups built 2026-09-04 (branch `feat/lsfg-native-568`)

- **Present per generation.** One command buffer per pending present
  (`cmdSlot(k)`): slot 0 = composite + shared chain + generated frame 0; each
  later generated frame and the real frame are recorded, submitted and
  presented one at a time. The slot fence rides on the last submit (fence
  signals are ordered after all earlier submissions on the queue); early exits
  attach it to an empty submit. Cross-buffer ordering is a memory barrier at
  the top of each later buffer - barriers span command buffers on one queue.
- **Chain GPU cost.** Timestamp pair per frame slot: start (TOP_OF_PIPE)
  before `process`, end (COMPUTE_SHADER) after the last `generateInto`, before
  its swapchain copy so a vblank wait is not counted. Read back after the
  slot's fence wait, smoothed 0.1, exposed as stat [5] and shown in the drawer
  readout as "ms/frame GPU".
- **Shader cache at import.** Settings builds the SPIR-V cache right after
  Detect/Import, and on opening Settings if stale, with a status line under the
  DLL status. Remove also deletes the cache. Launch-time build remains as the
  fallback.
