# TV Options — in-game side-menu panel (spec)

Status: DRAFT for build. Feature = "Version A" external-display mode (game on TV, handheld as
controller), device-proven via the `feat/external-display-swap` auto-swap spike. This spec covers the
production UI: a **TV Options panel that appears in the in-game side menu only while a TV/external
display is connected**, letting the player adjust picture + latency for the big screen.

Related: `ExternalDisplayController` (the swap engine), `project_bannerlator_external_display_swap`
(memory). Version-B WiFi-streaming knobs (bitrate/codec/etc.) are OUT of scope here and listed at the
end as a future block.

---

## 1. Where it lives

The in-game "side menu" IS the Compose drawer:
- `app/src/main/java/com/winlator/star/ui/XServerDrawer.kt` — tab bar + `when(selectedTab)` content dispatch.
- `app/src/main/java/com/winlator/star/ui/XServerDrawerState.kt` — `enum TabType { GRAPHICS, HUD, RESHADE, CONTROLS, ADVANCED, TASK_MANAGER }` + StateFlows + `on*` callbacks.
- Activity wires callbacks → renderer in `XServerDisplayActivity` (`applyEffectivePresentMode()`, `applyFullscreenMode(int)`, `xServerView.setDisplayFrameRate(...)`, `showScreenEffectsDialog()`, etc.).

**Decision: add `TabType.TV`** and render `TvContent(state)` — reuses the existing tab pattern
(`TabIconButton` + `SectionHeader` + the drawer's `ExposedDropdownMenu`/toggle widgets), so it's
visually consistent and cheap. The tab (icon = a TV/cast glyph) is **conditionally shown**: only when
`state.tvConnected == true`. This satisfies "a dialog from the side menu that appears when plugged
into a TV" without a separate window. (Alternative — a standalone `AlertDialog` launched from a menu
row — is more code and breaks the drawer idiom; not recommended.)

Auto-select `TabType.TV` the first time a display connects mid-session (nice-to-have).

---

## 2. Settings

Legend — **Cost**: `REUSE` = state + Activity hook already exist, just add the TV-tab UI and route to
them; `WIRE` = small new plumbing; `NEW` = real new work. **Renderer**: which host renderer the control
applies to (some are Vulkan-only, some GL-only — see §4).

### 2a. Move / connection
| Control | UI | Values / default | Backing | Cost |
|---|---|---|---|---|
| **Play on TV** | toggle | on / **on when connected** | `ExternalDisplayController.setSwapEnabled(b)` — replaces the spike's always-on auto-swap. When off, game stays on handheld even with a display attached. | WIRE |
| **Auto-swap on connect** | toggle | **on** / off | same controller; if off, "Play on TV" is a manual push instead of automatic. | WIRE |
| **Bring back to handheld** | button | — | `ExternalDisplayController.moveGameToInternal()` | REUSE (spike) |

### 2b. Picture
| Control | UI | Values / default | Backing | Cost |
|---|---|---|---|---|
| **Aspect on TV** | segmented | Letterbox (**FIT**) / Stretch / Off | `state.setFullscreenMode()` → Activity `applyFullscreenMode(Container.FULLSCREEN_{FIT,STRETCH,OFF})`. Store a TV-specific value separate from the handheld's. | REUSE + persist |
| **Overscan / safe area** | slider | 0–8 %, **0 %** | NEW: an inset (padding) applied to `GamePresentation.root` in `ExternalDisplayController`. Fixes TVs that crop edges. Self-contained, no renderer change. | NEW (small) |
| **Scaling filter** | segmented | Sharp / **Smooth** | GL `EffectComposer` spatial upscale (SGSR/FSR). Reuse the RESHADE/screen-effects path (`state.onScreenEffects` / EffectComposer). **GL renderer only.** | REUSE (GL) |
| **TV render resolution** | dropdown | Match TV / Handheld / Custom; **Match TV** | `renderScale`/`screenSize` are currently consumed once in `setupUI` (launch-time, not live). v1 = "applies on next launch" note; live change needs an X-server resolution swap mid-session (see §4 caveat). | NEW (moderate) |

### 2c. Latency & pacing
| Control | UI | Values / default | Backing | Cost |
|---|---|---|---|---|
| **Latency mode** | segmented | **Smooth (V-Sync)** / Low latency | `state.setPresentMode("fifo"|"immediate")` → `state.onPresentModeChange` → Activity `applyEffectivePresentMode()`. Immediate = lowest latency (may tear); FIFO = smooth. **Vulkan renderer only** (`state.rendererIsVulkan`). | REUSE (Vulkan) |
| **Match TV refresh rate** | toggle | **on** | `state._matchRefreshRate` + `_supportedRefreshRates`/`_currentRefreshRate`; Activity `xServerView.setDisplayFrameRate(rate, VRR_FRAME_RATE_COMPATIBILITY)`. Reads the TV's modes, not the panel's. | REUSE |
| **Frame cap** | dropdown | Off / 30 / 60 / TV rate; **TV rate** | `state._fpsLimiterEnabled` + `_fpsLimit`. | REUSE |
| **Frame generation** | toggle | off / on (**inherit handheld**) | `state._frameGenEnabled` + `_frameGenEngine`/`_frameGenModel`. Smooths on the big screen at a small latency cost; let TV override the handheld choice. | REUSE + persist |
| **TV Game Mode tip** | static info row | — | Non-interactive one-liner: "For lowest lag, enable Game Mode on your TV." (Biggest wired-latency win is TV-side; we can only advise.) | NEW (trivial) |

### 2d. Audio & power
| Control | UI | Values / default | Backing | Cost |
|---|---|---|---|---|
| **Audio output** | segmented | **Follow system** / TV / Handheld | Guest audio → `PulseAudioComponent` (AAudio sink, `set-default-sink AAudioSink`). "Follow system" = today's behaviour. Forcing TV/handheld needs `AudioManager`/AAudio `setPreferredDevice` on the output track. | NEW (moderate) |
| **Dim handheld while on TV** | toggle | **on** / off | `window.attributes.screenBrightness` on the Activity window (the handheld host) while `gameOnExternal`. Battery/heat saver. | NEW (small) |

---

## 3. New plumbing checklist
1. **`TabType.TV`** + `TvContent(state)` composable + conditional tab button (`if (state.tvConnected)`), icon `icon_display`/a cast glyph.
2. **`XServerDrawerState`**: add `_tvConnected`, `_tvAspect`, `_tvOverscan`, `_tvScalingFilter`, `_tvRenderRes`, `_tvLatencyMode`, `_tvFrameCap`, `_tvFrameGen`, `_tvAudioOut`, `_tvDimHandheld`, `_playOnTv`, `_autoSwap` StateFlows + `set*` mutators; callbacks `onPlayOnTvChange`, `onAutoSwapChange`, `onBringBack`, `onTvOverscanChange`, `onTvAudioOutChange` (the rest reuse existing present/fullscreen/vrr/framegen callbacks but with TV-scoped persistence).
3. **`ExternalDisplayController`**: add `setSwapEnabled(b)` gate; `setOverscanPercent(f)` (pad `root`); a `onGameOnExternalChanged` listener wired to `XServerDrawerState.setTvConnected(b)` so the tab appears/disappears on hot-plug; keep `moveGameToInternal()` public.
4. **Persistence**: TV settings are a distinct profile from handheld. Store as container extras with a `tv.` prefix (`tv.aspect`, `tv.latency`, `tv.overscan`, …) and layer per-game shortcut overrides, mirroring the existing `renderScale`/`fullscreenMode` extra pattern. Load in `setupUI`, apply on connect.
5. **Overscan/audio/dim**: the three NEW behaviours above.

---

## 4. Renderer-dependency caveats (must handle in UI)
- **Latency mode (present mode) is Vulkan-only** (`state.rendererIsVulkan`). On GL/SurfaceFlinger, gray it out with "Vulkan renderer only".
- **Scaling filter / SGSR / screen effects are GL `EffectComposer`-only** — the Vulkan renderer has no EffectComposer. Gray out on Vulkan (or route through the Vulkan compositor's own Lanczos where available).
- **Live TV render-resolution** is the one genuinely hard item: `screenSize`/`renderScale` are applied at X-server bring-up in `setupUI`, so changing them live means re-negotiating the guest display resolution mid-session (RandRefresh-adjacent, see `project_bannerlator_xserver_randr_refresh_modes`). v1 scope: expose the choice but apply on next launch, OR restrict live changes to the upscale filter (which IS live). Don't promise live 4K toggling in v1.
- **Frame-gen force-locks present mode to Mailbox while multiplying** — the drawer already blocks present-mode taps in that state (`frameGenGenerating()`), so the TV latency control must respect the same guard.

---

## 5. Phasing
- **v1 (REUSE-heavy, ship first):** TabType.TV gated on connect; Latency mode, Aspect, Match refresh, Frame cap, Frame gen, Scaling filter, Play-on-TV / auto-swap / bring-back, + the Game-Mode tip. All backed by existing state/hooks + the small controller `setSwapEnabled`/`tvConnected` wiring. TV-scoped persistence.
- **v2 (NEW behaviours):** Overscan slider, Audio output routing, Dim-handheld, live/next-launch TV render resolution.
- **v3 (Version B — WiFi caster):** a **Streaming** section, shown only when the WiFi caster exists: Bitrate, Codec (H.264/HEVC), Encoder latency mode, Transport (WebRTC/DLNA), Stream resolution/FPS, Jitter buffer. Grayed with "requires WiFi streaming" until then. Also DeX polish pass (launch onto DeX desktop, DeX touchpad).

---

## 6. Open questions
- Default for **Play on TV** — auto-swap on (matches spike, most magical) vs manual push (least surprising)? Leaning auto-on with the toggle to disable.
- Should TV settings **inherit** the handheld's per-game settings by default, or start from TV defaults? Proposal: inherit, then override.
- Confirm exact `Container.FULLSCREEN_FIT` constant name (menu comment implies OFF/FIT/STRETCH; verify in `Container`).
