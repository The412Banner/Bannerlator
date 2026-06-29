# Release notes — GL Native Rendering (Low-Latency Mode on OpenGL)

> Paste-ready for the next release that ships P4+P5. Drop into the release body changelog.
> Status: code on `main` (P4 `fcaf104`, P5 `ba0d35d`, dead overlay-fix reverted `ee63ab1`). Device-proven functional. NOT yet in a tagged release.

---

## Short changelog blurb (paste into release notes)

**🆕 Native Rendering now works on the OpenGL renderer (Low-Latency Mode).**
Native Rendering — previously Vulkan-only — is now available on OpenGL containers too. When enabled, the game's frames are handed to the display through a more direct path, skipping an extra per-frame compositing step. The result is **lower input latency and a more responsive, "direct" feel**, especially in fast or high-FPS games.

It's an **opt-in toggle** (in the in-game drawer → Graphics → Native Rendering, off by default). While it's on, on-screen **effects and upscalers are disabled** (FXAA, CRT, Toon, NTSC, color/gamma, SGSR/FSR) — those controls grey out automatically so it's clear they're an either/or choice with Native Rendering. Turn Native Rendering off to use effects again.

---

## Plain-English explanation (for users who want the detail)

**What it does.** Normally the game's picture is redrawn one extra time by the app before it reaches the screen. Native Rendering removes that extra redraw and hands the picture to the screen more directly. Fewer hand-offs per frame = lower lag and a snappier feel.

**When to use it.** Best for fast-paced or high-frame-rate games where input responsiveness matters and you aren't using visual effects. For games where you want CRT/Toon/color effects or an upscaler, leave it off.

**What changes when it's on:**
- ✅ Lower input latency / more direct feel.
- ⚠️ Screen effects and upscalers are turned off (their controls grey out).
- ℹ️ The FPS counter may read lower than with Native Rendering off. This is normal — the off mode lets the game render lots of frames that are never actually shown; Native Rendering paces the game to the display, so the number is lower but the frames you see arrive more directly.

**A note on power/battery (technical, honest).** Native Rendering can also let some devices' display hardware take over final compositing entirely — a power/heat win. Whether that engages depends on your device's display controller and screen orientation: on devices/panels that require the game image to be rotated to fit the screen, the hardware overlay isn't available and you get the latency benefit only. On landscape-native handhelds the image needs no rotation, so the additional power/heat benefit is more likely to apply. Either way, the latency improvement above applies on all supported devices.

---

## Technical summary (for changelog "Technical" section if used)

- New: OpenGL renderer per-frame direct scanout via Android `SurfaceControl`/`ASurfaceTransaction` (`GLRenderer.presentScanout`, `PresentExtension` GL FLIP branch), mirroring the existing Vulkan native path. Cursor rides its own SurfaceControl above the game surface.
- New: effect/upscaler mutual-exclusion — enabling Native Rendering greys out and disables the GL effect + scaling controls (FXAA/Toon/CRT/NTSC/color + SGSR/FSR/sharpen/HDR/deband); enabling an effect cleanly disables Native Rendering.
- Scope note: HWC hardware-overlay promotion (display controller does final compositing) is gated by the display's required rotation and is device-dependent; the per-frame-path latency benefit is renderer- and device-agnostic.

Requires Android 10+ (API 29). OpenGL containers only for this toggle; Vulkan native rendering unchanged.
