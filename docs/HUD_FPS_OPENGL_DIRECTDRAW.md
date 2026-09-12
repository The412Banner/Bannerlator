# HUD FPS on OpenGL / DirectDraw vs. a benchmark's own counter

**Short version:** on **OpenGL** and **DirectDraw** titles the in-game HUD can read a
*lower* FPS than the app's own on-screen counter (e.g. a graphics benchmark). This is
expected and both numbers are correct — they measure different points in the pipeline.
On **Direct3D** and **Vulkan** titles the two match.

---

## Why

The HUD reports the **displayed frame rate** — frames that actually reach the screen.

- **Direct3D / Vulkan** (via DXVK / VKD3D / native Vulkan): every rendered frame is
  **explicitly *presented*** to its window, one present per frame. The emulator sees each
  present and counts it, so the HUD equals the game's render rate — the HUD and the app's
  own counter agree (e.g. a D3D12 cube reading ~900 on both).

- **OpenGL / DirectDraw** (run through **Zink**, OpenGL-on-Vulkan): the game renders
  **continuously into a shared buffer**, and the host compositor samples that buffer at the
  display's rate. Individual frames are **never presented as discrete events** the emulator
  can count. So:
  - the **app's built-in counter** shows how fast the game's render *loop* spins — often much
    higher and uncapped (e.g. "OpenGL 300 FPS");
  - the **HUD** shows how many of those frames are actually **composited to the screen**
    (e.g. ~90).

Neither is wrong. One is the app's internal loop rate; the other is the frames you actually
see. The gap only appears in **uncapped synthetic tests**; normal, frame-limited OpenGL
games render close to the display rate, so the two line up.

## Technical detail

Verified on-device (2026-07-26) by instrumenting the X server's draw/present handlers while
an OpenGL cube rendered continuously:

| Path | Calls/sec during a GL cube |
|------|----------------------------|
| `presentPixmap` (the Vulkan/DXVK/VKD3D present route) | **0** |
| `copyArea` | **0** |
| `putImage` | ~8 (incidental — the benchmark's own FPS-text overlay, not the 3D frames) |

So **guest OpenGL emits no per-frame X present** — its frames go straight into a shared
GL texture / `AHardwareBuffer` that the host `GLSurfaceView` compositor reads at ~display
rate. The true GL loop rate lives only inside the guest (Wine's `SwapBuffers`). Surfacing it
in the HUD would require **guest-side instrumentation** (hooking Wine `opengl32` `SwapBuffers`
or the Zink present inside the container) — a different, much deeper layer — and would only
benefit uncapped GL benchmarks, so it is intentionally not done. Real games (which render
through Direct3D/Vulkan, or are frame-limited on GL) read correctly.
