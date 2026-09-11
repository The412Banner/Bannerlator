# LSFG Native on low-end / phone GPUs — diagnosis and two experiments

_Companion to `lsfg-native-compositor-plan.md`. Diagnosed 2026-09-10 from device logs on a Xiaomi
23122PCD1G (garnet, Adreno 710, HyperOS / Android 16) running Bannerlator 3.0.9; measured again on
2026-09-11. Both experiments are per-container settings, default to today's behaviour, and sit behind
`FeatureFlags.LSFG_NATIVE_EXPERIMENTS_ENABLED`: with the flag off the controls are hidden and the
stored values are ignored, so the app behaves exactly as it does without this work._

## 1. What the logs said

Two in-app diagnostic logs plus `adb shell cmd gpu vkjson`.

**LSFG Native did nothing at all** on the container's default Renderer Driver:

```
Winlator_Renderer: createInstance: adrenotoolsHandle=0x0 (custom driver NOT SET - using stock driver)
Winlator_Renderer: lsfg-native: unsupported: device Vulkan version below 1.2 (features enabled=0, storage-on-fmt=1)
Winlator_Renderer: GPU: Adreno (TM) 710 ... apiVersion=1.1.128
```

The compositor (where LSFG Native runs — it is not a guest layer) was on the stock Qualcomm driver,
which reports Vulkan **1.1.128**; the probe requires 1.3. On 3.0.9 the app still armed, so it looked
on while generating nothing (3.1.0 now says so and falls back to Off). Setting the container's
**Renderer Driver** to the installed Mesa Turnip v26 made it work:

```
Winlator_Renderer: lsfg-native: supported (device Vulkan 1.4.328, fmt 37) (features enabled=1, storage-on-fmt=1)
LsfgEngine: chain built at 2712x1220, flow 1084x488 scale 0.40 (preset 1.00, guest 1024x576)
```

**But the chain was expensive.** It runs at **panel resolution (2712×1220)** — the composite ring is
the swapchain's size — while the game draws 1024×576, and it cost **~9.5 ms per generated frame**
(Adreno 750 reference: 2–4 ms). Only the flow pyramid is scaled (auto-clamped to 0.40 from the
guest/panel ratio, which is why the flow-scale slider at 0.60 changed nothing).

Also seen, not addressed here: the game's own rate wandering 19–31 fps under a 30 cap, mostly
CPU-side (the dips tracked touch-input bursts, the chain's GPU time stayed flat), and HyperOS
ignoring the "Prefer big cores" pin (`AffinityDrift ... NOT-HONORED requested=0xf0 achieved=0xff`).

The stock driver's `vkjson` shows it carries everything the chain needs despite reporting 1.1:
`VK_KHR_spirv_1_4`, `VK_KHR_shader_float_controls`, `VK_KHR_vulkan_memory_model`,
`VK_KHR_shader_float16_int8` (float16=1, int8=1), `VK_KHR_16bit_storage`,
`shaderStorageImageWriteWithoutFormat=1`, `shaderStorageImageExtendedFormats=1`. It lacks
`shaderInt64` / `shaderFloat64`, which DXVK only emits when a shader uses them.

## 2. Experiment 1 — Capture resolution

**Setting:** container `fgCaptureResolution` = `panel` (default) | `game` | `WxH` (the Screen Size
list, plus Custom). Live in the in-game drawer as chips: Panel / Game / 360p … 1080p.

**Idea.** Run the whole chain — history copy, mipmaps, flow, `generate` — on a composite ring
smaller than the panel and blit the result up on the way out. Pixel count drives the cost:
2712×1220 → 1600×720 (the "Game" pick for a 1280×720 container on this phone) is 2.9× fewer pixels.

**Aspect rule.** The swapchain is the whole screen (≈2.22:1) while the list is 16:9 / 4:3, so the
pick sets the capture **height**; the width follows the swapchain's aspect
(`compositeExtentFor`: `W = round(H × swW / swH)`, even-aligned, H clamped to
`[swH/4, swH]`). Nothing is stretched.

**What changed.**
- `ensureCompositeTargets(ringW, ringH, want)` takes the computed extent; `Engine::prepare` and
  `generateInto` follow it (the chain rebuilds through `needsRebuild` when the extent changes — no
  swapchain recreate needed). `effectiveFlowScale` then sees a closer ratio, so the flow-scale
  preset takes effect.
- `recordCmdBuf`: with a downsized ring the direct path draws with
  `renderArea/viewport/scissor = ring extent`. Window quads are placed in NDC, so a same-aspect
  smaller target needs no new maths; the #413 clip rect is scaled. The spatial upscalers
  (`recordUpscalePasses`) step aside in that mode — the final blit is the upscale (linear).
- `recordCompositeToSwapchainTransfer`: `vkCmdCopyImage` when extents match (today's path, byte
  for byte), `vkCmdBlitImage` linear otherwise, for both the real frame
  (`copyCompositeToSwapchain`) and generated frames (`recordFrameGenGeneration`).
  `vkCmdBlitImage` added to the dispatch table. The cursor is still drawn per present at full
  resolution after the blit.
- Win-FG Native keeps passing capture height 0, so its ring stays at panel resolution.

**Trade-off.** Softer image below panel resolution, and SGSR/NIS/CAS are bypassed while a
downsized ring is armed (v1). A per-present upscale pass is the v2 if the softness matters.

**Measured** (Turnip v26, Dead Space 2 capped at 30, 2×, switching live six times):

| Capture | Ring / chain | GPU ms per generated frame | Game fps | Shown fps |
| --- | --- | --- | --- | --- |
| Panel | 2712×1220 | ~9.3 | ~30 | ~58 |
| 720 | 1600×720 | ~5.5 | ~30 | ~59 |
| 360 | 800×360 | ~1.7 | ~30 | ~59 |

Each switch: one `composite ring: WxH` line and one `chain built at WxH` line within ~0.1 s, no
acquire/fence errors. The game's rate did not move with the setting. Panel reproduces the
`2712x1220` line exactly.

## 3. Experiment 2 — LSFG on Vulkan 1.1 drivers

**Setting:** container `lsfgVk11Compat` ("LSFG on Vulkan 1.1 drivers (compat)", launch-time).

**Idea.** A 1.1 device that offers `VK_KHR_spirv_1_4` (+ `VK_KHR_shader_float_controls`) and
`VK_KHR_vulkan_memory_model` can run the chain as SPIR-V 1.4; a 1.2 device can run it as 1.5. The
cache is built at DLL import with no device in hand, always at DXVK's native 1.6, so the lowering
happens at load time.

**What changed.**
- `lsfg_probe`: `queryFeatures(vk, pd, deviceExtensions, allowVk11)`. Below 1.2 with the flag it
  requires the three extensions and queries `VkPhysicalDeviceVulkanMemoryModelFeaturesKHR`; on 1.2
  it accepts with a 1.5 target. New `extensionPath` / `spirvTarget`; `explain()` names the missing
  extension. Without the flag every device is judged exactly as before.
- `createLogicalDevice`: on the extension path the three extensions are enabled and the memory
  model is requested through the KHR struct (a 1.1 driver does not know `Vulkan12Features`). The
  retry-without-features fallback restores the pre-LSFG extension list.
- `lsfg::downgradeSpirv(words, target)`: rewrites the header version and, below 1.5, inserts
  `OpExtension "SPV_KHR_vulkan_memory_model"` (and `SPV_EXT_demote_to_helper_invocation` if that
  capability is present) after the capability block. `LsfgShaders` applies it to every cached
  module above the device's target; `Engine::init` receives the target from the probe.
- `dxbc_compiler.cpp`: the emitted version comes from `DxbcModuleInfo::spirvVersion` (0 = 1.6) and
  the compiler declares the memory-model extension itself below 1.5 — for tooling; the on-device
  cache still builds at 1.6.
- The flag reaches the renderer before `vkCreateDevice`: `VulkanRenderer.setLsfgVk11Compat` →
  `nativeInit(..., lsfgVk11Compat)` → constructor argument, set next to the Renderer Driver in
  `XServerDisplayActivity`.
- 3.1.0's "can't run on this Renderer Driver" notice names the compat setting next to Turnip when
  the reason is the Vulkan version and the setting is off.
- Win-FG Native is unaffected: its gate only checks the swapchain format, and its embedded shaders
  are SPIR-V 1.3.

**Risk.** A module using an Int64/Float64 capability fails `vkCreateShaderModule` on this driver;
the shader set then reports unusable and frame gen stays off — logged, never a crash.

**Measured** (stock driver, compat on, same game, cap 30, 2×):

```
createLogicalDevice: LSFG Vulkan 1.1 compat - enabling spirv_1_4 + vulkan_memory_model
lsfg-native: supported (device Vulkan 1.1.128 via extensions, SPIR-V lowered, fmt 37)
LsfgShaders: Lowered 25 modules to SPIR-V 0x10400 for this device
LsfgShaders: Created 25 LSFG shader modules, variant=dxbc-translated
lsfg-native: engine ready (spirv target 0x10400)
```

| Capture | GPU ms per generated frame | Game fps | Shown fps |
| --- | --- | --- | --- |
| Panel | ~22.7 | 22–27 | 48–52 |
| 720 | ~18 | ~30 | 57–60 |
| 360 | ~4.7 | ~30 | 57–60 |

It generates with no renderer or frame-gen errors, but the proprietary driver is 2.5–3× slower than
Turnip on the same GPU: at panel resolution the chain competes with the game for the GPU. The two
experiments combine: on the stock driver, capture 720 or lower is what holds 30 → 60. Compat off
reproduces the old `unsupported: device Vulkan version below 1.2`.

## 4. How to test

1. Leave `FeatureFlags.LSFG_NATIVE_EXPERIMENTS_ENABLED` on.
2. Container settings → Frame generation → LSFG Native. The *Experimental (LSFG Native)* block
   holds Capture resolution and the Vulkan 1.1 compat switch; Capture resolution is also in the
   in-game drawer while LSFG Native is the engine.
3. Settings → *Developer — Frame-gen training capture* → turn on **Diagnostic log** before each run
   (it records to `Download/win-fg-logs`).
4. One setting at a time, cap 30, 2×, same scene; read the drawer line and the
   `composite ring` / `chain built at` / `chain … ms per generated frame` log lines.
5. Panel with compat off must reproduce the pre-change log shape exactly (`2712x1220`, SPIR-V target
   `0x10600`).

## 5. Still open

- On the stock driver each chain build takes ~4 s (Turnip: well under 0.1 s), presumably pipeline
  compilation; a pipeline cache would help live capture changes there.
- HyperOS overriding core affinity (`AffinityDrift NOT-HONORED`) — needs its own investigation.
- The game/host double frame cap (Dead Space 2's built-in DXVK `d3d9.maxFrameRate = 60` versus the
  host limiter) paces the source unevenly; cap 30 on the host sidesteps it.
