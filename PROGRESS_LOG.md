# Star-Compose — Progress Log

**Repo:** https://github.com/The412Banner/star-compose (main branch)  
**Mirror:** https://github.com/kalteatz24/winlator-test (star-compose branch)  
**Local:** `/data/data/com.termux/files/home/winlator-test`  
**Always push to both remotes after every commit:**
```
git push star-compose star-compose:main
git push kalteatz24 star-compose:star-compose
```
**Then trigger CI:**
```
gh workflow run "Any branch compilation." --repo The412Banner/star-compose --ref main
```

---

## 2026-06-27 (latest) — Vulkan spatial upscalers + sharpen + supersampling (Phase 1/1b)

Branch `feat/vulkan-upscaler-sgsr-fsr` (NOT merged). First fork with real SGSR **and** FSR1 upscaling on the
default Vulkan renderer, plus native-res sharpen and supersampling — in one app. Full design/provenance log:
`docs/SGSR_HDR_VULKAN_PLAN.md`; per-renderer summary `docs/render_upgrades_report.html`.

**Built a Vulkan post-process framework** in `app/src/main/cpp/winlator/VulkanRendererContext.cpp` (offscreen
composite target at game res → post/upscale pass → swapchain), then layered features on it:

- **Scaling mode** (in-game drawer, live, Vulkan-only): None / Linear / Nearest / **SGSR** / **FSR** / **FSR (Fit)** /
  **Sharpen**. Modes via `VulkanRenderer.setUpscaler(int)` 0–6. Upscalers engage only when the game renders below
  display res; **Sharpen (6)** runs FSR RCAS at any res incl. native.
- **Supersampling ("Render scale")** — pre-launch container + per-game-shortcut setting Off/1.25x/1.5x/2x (stored via
  `renderScale` extra, no DB migration). Launch multiplies the X11 render res (aspect-preserve, clamp 7680x4320, even
  dims); compositor runs a new Lanczos-2 `downscale.frag` via `setHqDownscale(true)`. DSR/OGSSAA-style.
- **Per-renderer Graphics tab** — shows ONLY the active renderer's controls (OpenGL→SGSR/HDR+ScreenEffects;
  Vulkan→Scaling mode; SurfaceFlinger→"no enhancements" note) instead of greying out the rest.

**Shaders bundled** (offline-compiled to `.spv` C-array headers, license headers retained): SGSR 1.0 (Qualcomm,
BSD-3), FSR1 EASU+RCAS (AMD, MIT), Lanczos downscale. Approach for FSR-in-compositor / FSR-Fit credited to GameNative.
**HDR deferred** (Android WSI rarely exposes an HDR10 surface; revisit later).

**Commits:** `5f5a4a0` native upscaler + drawer · `28ab22d` per-renderer tab · `c3cbe49` Phase 1b sharpen+supersampling
· docs `33ad5f4`. **CI:** Phase 1 GREEN (`28276691564`, `28277238762`); full Phase-1b build `28277821185` ✅ GREEN
(all 3 flavors). **DEVICE-UNTESTED** — next step is on-device: sub-native upscale modes, native-res sharpen, 1.5x
supersampling, and per-renderer tab. Then **Phase 2** = port GL effects (FXAA/CRT/Toon/NTSC/color) to Vulkan.

CI for this repo is MANUAL: `gh workflow run build-artifacts.yml --ref <branch>`.

---

## 2026-06-25 — 1.9 STABLE cut ✅ (SurfaceFlinger renderer + DXVK 3.0 / Vulkan 1.4)

Merged `feat/surfaceflinger-renderer` to main (ff `d915798`), bumped to **1.9 / versionCode 29** (`eb39c2b`),
and cut **1.9 stable** (release run `28215839109`, `make_latest`, `update.json` attached → in-app updater
offers it on the stable channel). User explicitly authorized promoting to stable ("release 1.9").

**Shipped in 1.9:**
- **SurfaceFlinger (ASR) renderer** — experimental third host renderer, opt-in behind a reboot-risk warning
  dialog, default off. Ported from GameNative PR #1582 (André Vito) on StevenMXZ's scanout work.
- **DXVK 3.0 / Vulkan 1.4** option in the Turnip/Wrapper Driver Configuration.
- **Fixes:** per-game DXVK/VKD3D download sheet no longer hides behind the settings dialog; perf HUD labels
  SurfaceFlinger correctly.

**No imagefs reinstall required** — the 1.9 diff is purely app-side (renderer engine, a bundled native lib,
an env-var option, UI). No `imagefs/`, `assets/`, or `imgVersion` change; existing containers are untouched.

Release description was rewritten to the 1.8 layout with credits to GameNative (André Vito) + StevenMXZ for ASR.

---

## 2026-06-25 — SurfaceFlinger (ASR) renderer Phase 1 ✅ WORKING + device-proven (branch `feat/surfaceflinger-renderer`)

Took the SurfaceFlinger renderer from "selectable skeleton" (Phase 0) to a working scene compositor
that renders real D3D games fullscreen via Android SurfaceFlinger — no GL/Vulkan compositor. Ported
from GameNative PR #1582 (André Vito, on StevenMX's scanout work), adapted to Bannerlator's X-server API.

**Build-up:** scene engine (`ASurfaceRenderer` implements `WindowManager.OnWindowModificationListener`
+ `Pointer.OnPointerMotionListener`; `updateScene` walks the window tree under XLock → one SurfaceControl
layer per window via `nativeRegisterWindowSC`/`nativeUpdateWindow` in a begin/apply transaction; frames
pushed via `nativeSetWindowBuffer`) + additive `PresentExtension` ASR branch (routes the game AHB to the
SC; Vulkan/GL paths untouched).

**The hard debugging (device, Adreno 750, GTA IV + AIO Graphics Test, DXVK 3.0 + VK 1.4):** game ran
(audio) but showed a small top-left window. On-device logging (filtered logcat to a file — wine logs flood
the buffer) proved the whole Java chain worked (8000+ `ASR_Present`/pushes with valid AHBs, SC registered,
visible). Two stacked root causes, both fixed:
- **`Drawable.DRAWABLE_ASR_MODE`** (`98861c8`): port GameNative's flag so every Drawable is backed by a
  composer-compatible `GPUImage` AHB at construction (`data = AHB mapped memory`) — required for
  SurfaceFlinger to scan out. Wired `setAsrMode(true/false)` per renderer in `XServerView.initRenderer`.
- **Geometry** (`bf292bf`): `computeWindowRect` used the normalized GL `sceneScaleX` (~1.0), pinning the
  game at native size in the corner. Map through `viewTransformation.aspect` (surface-px-per-X-px, e.g.
  1.5×) + letterbox offset instead → fills the surface.
- **HUD** (`bf292bf` + `c4f6e5f`): wired `ASurfaceRenderer.setHudFrameTick` (FPS was blank — the tick was
  Vulkan-only) + fixed the renderer label (`XServerDisplayActivity:1710` binary vulkan?:OpenGL → +SF case).

**✅ DEVICE-PROVEN** (build `28213017959`, screenshot-every-5s/60s): GTA IV menu renders FULLSCREEN under
ASR, HUD reads `SurfaceFlinger | DXVK | … FPS: 398 2.5ms`, stable. **✅ GL/Vulkan regression pass:** all
three renderers render GTA fullscreen with correct labels/FPS (Vulkan 300, OpenGL 295, SurfaceFlinger 398)
— additive edits don't disturb GL/Vulkan, global ASR flag clears correctly. **✅ Debug logging stripped**
(`bb64f2b`, clean build `28213752314`).

Branch tip `bb64f2b`, carries the merged Vulkan 1.4 commit. **NOT merged** — awaiting call: merge to main vs
Phase 2 polish first (CPU desktop chrome compositing, cursor, fps-limit tearing — none block game render).
Process note: always `git push` BEFORE dispatching a CI build (a build was once cut from the pre-push commit;
verify via `gh run view <id> --json headSha`). 1.9-pre prerelease when cut.

---

## 2026-06-25 — DXVK 3.0 Vulkan 1.4 option ✅ merged + SurfaceFlinger renderer Phase-0 spike 🚧

**Context:** DXVK 3.0 shipped (all 4 `.wcp` flavors on The412Banner/Nightlies). DXVK 3.0 **hard-requires
Vulkan 1.4** (mandatory bump from 2.x's 1.3 — verified vs the release notes). The Turnip/Wrapper Driver
Configuration "Vulkan Version" dropdown capped at 1.3, so the wrapper exported `WRAPPER_VK_VERSION=1.3.x`
and DXVK 3.0 refused to init even on a VK1.4-capable driver.

**Fix — Vulkan 1.4 option (`785fe2b`, branch `feat/vulkan-1.4-dxvk3`, CI `28205826581` ✅ → ff-merged to
main 2026-06-25).** One-line: added `<item>1.4</item>` to `arrays.xml` `vulkan_version_entries`. Default
kept **1.3** (safe; 1.3-only drivers/A6xx unaffected) — user picks 1.4 manually for DXVK 3.0. Value flows
generically: dialog → `graphicsDriverConfig` `vulkanVersion=` token → `XServerDisplayActivity:2149` appends
the driver patch → `WRAPPER_VK_VERSION` env. **Proved load-bearing at the binary level:** disassembled the
bundled `libvulkan_wrapper.so` — `wrapper_GetPhysicalDeviceProperties` does `getenv("WRAPPER_VK_VERSION")` →
`sscanf` → `VK_MAKE_API_VERSION` → `str` into `pProperties->apiVersion` (offset 0), the exact field DXVK 3.0
gates on. Caveat: override is unconditional (no clamp to real driver max) → on A6xx (Turnip caps at 1.3)
picking 1.4 would lie to DXVK = footgun; default-1.3 avoids it. All 4 Nightlies DXVK release bodies updated
with the VK1.4 note + "Current version: 3.0". Driver side: The412Banner/Banners-Turnip builds report
**Vulkan 1.4.354** (Mesa main, `TU_API_VERSION=VK_MAKE_VERSION(1,4,..)` for chip≥7); device Adreno 750 (A7xx)
gets the 1.4 path. DEVICE-UNTESTED end-to-end (DXVK 3.0 launch w/ 1.4 selected).

**SurfaceFlinger renderer (ASR) — Phase-0 spike (branch `feat/surfaceflinger-renderer`, commit `068c3a5`,
CI `28208898551`).** 3rd host renderer ported from GameNative PR #1582 (André Vito; built on StevenMX's
scanout work). Confirmed our `cpp/winlator/VulkanRendererScanout.cpp` is **byte-identical** to GameNative's —
Steven's scanout foundation already in-tree. Spike = compiles + selectable (NOT a working compositor):
native `cpp/asurfacerenderer/` (JNI repackaged to `com_winlator_star_renderer`) → `libasurface_renderer.so`
via main CMakeLists; skeleton `ASurfaceRenderer` implements `HostRenderer` + loads lib + creates/destroys the
SF context on the surface lifecycle (per-window scene compositing deferred to Phase 1); selection wired in
`XServerView.initRenderer(String)` + `XServerDisplayActivity` (API<29 → Vulkan fallback) + "SurfaceFlinger"
added to container + per-game renderer dropdowns. NOT merged; device-test pending. See
`reference_gamenative_surfaceflinger_renderer` memory for the full Phase-1 plan.

---

## 2026-06-25 — 1.8 STABLE cut ✅ (updater picker fix + in-app OTA proven on a real stable)

Closed out the 1.8 cycle. One code blocker remained from the updater work, then cut stable.

**Picker correctness fix (`f1729a7`, branch `fix/updater-picker-sort`, CI `28200393133` ✅ → ff-merged to
main, branch deleted).** GitHub's list-releases API does **not** return pure newest-first — it pins the
`make_latest` release to the top, then lists the rest by date. Confirmed live: the API returned
`[1.7 (latest, published 01:46), 1.8-pre2 (published 20:30), 1.8-pre1, 1.6…]`. `UpdateManager.pickNewestWithUpdateJson`
took the **first** array element carrying `update.json`, which worked only because 1.7 had none (skipped).
Once 1.8 stable carried `update.json` + `make_latest`, it would have **shadowed a newer 1.9-preN** in the
prerelease channel. Fix = parse all releases into a list, `sortWith(compareByDescending { optString("published_at","") })`
(ISO-8601 sorts lexicographically = chronologically), then walk for the first with `update.json`.

**1.8 stable cut (user explicit go-ahead — required by the hard rule).** Bumped `versionCode 27→28` +
`versionName "1.8"` (`376e5fd`), dispatched `release.yml` with `release_tag=1.8 release_number=1.8
make_prerelease=false` → workflow auto-sets `prerelease:false` + `make_latest:true`. Release run
`28201699881` ✅. Verified: **Bannerlator 1.8 = Latest**, 1.7 demoted; assets = 3 flavor APKs +
`update.json`; **`releases/latest/download/update.json` now resolves to vc28/1.8** (stable updater
baseline live). Release body rewritten to match the 1.7 layout (logo / tagline / What's-New sections /
downloads table / credits / collapsible changelog) — intentionally **no reinstall-imageFS warning** since
1.8 is app-side only (HUD + updater), nothing changed in imageFS.

**✅ In-app OTA proven on a real stable cut:** a device running **1.8-pre2 (vc27)** auto-updated in-app to
**1.8 stable (vc28)** — the full updater loop (detect → download correct flavor → install) confirmed on a
genuine stable transition, not just pre→pre. Main tip `376e5fd`.

**1.8 ships:** GameHub-style perf HUD (2nd selectable overlay + live swap) · in-app updater (auto-install
+ optional prerelease channel) · setup-screen branding fix · updater picker fix. Next cycle → 1.9-preN
prereleases until an explicit stable call.

---

## 2026-06-25 (later) — In-app updater + prerelease channel (✅ device-proven, shipping via 1.8-preN)

Built a GitHub-releases-based **in-app update system** (modelled on the BannersComponentInjector /
BannerHub updater). Merged to main; being device-tested via prereleases.

**Core — `core/UpdateManager.kt`:** fetches `releases/latest/download/update.json`, compares
`BuildConfig.VERSION_CODE` (the integer is the source of truth, NOT the tag string), caches to
`cacheDir` (offline-safe), picks the flavor APK by `BuildConfig.APPLICATION_ID`, downloads via the
existing `HttpUtils` (reuses `DownloadProgressDialog`) and installs through the existing
`com.winlator.star.tileprovider` FileProvider. Install-permission guarded (`REQUEST_INSTALL_PACKAGES`,
Android 8+). **UI lives in 3 places:** Settings → new "Updates" section (readout, Check, Download &
install, Notify toggle); About dialog (latest-version line + "Update now"); app-wide amber home banner
(honours notify + skip-version). Manifest got `REQUEST_INSTALL_PACKAGES` + an `external-cache-path`;
`release.yml` generates + attaches `update.json` per release.

**"Include pre-releases" toggle (Settings, default OFF):** OFF = stable path (`releases/latest` only ever
resolves to a non-prerelease). ON = `checkViaApi` → GitHub releases API (`?per_page=30`, prereleases
included) → newest release carrying an `update.json` → its own asset URLs. **Gotcha: api.github.com 403s
without a `User-Agent`** → added one to `HttpUtils`' string fetch. `release.yml` gained a
`make_prerelease` input (sets `prerelease` + inverts `make_latest`); `update.json` now attaches to EVERY
release so the toggle has data.

**Versioning rule established (hard rule):** stables = plain numeric tag (`1.8`,`1.9`),
`prerelease:false` + `make_latest:true` — the ONLY thing the default updater offers. Everything between
stables = `X.Y-preN` (`1.9-pre1`,`pre2`…), `prerelease:true`, no make_latest, until explicitly promoted.
`versionCode` ticks up on EVERY build.

**Branding fix (`fix/setup-splash-branding`, merged):** the shared `DownloadProgressDialog` (first-launch
imagefs setup + HttpUtils downloads incl. the update download) hardcoded **"Star Bionic"** + **"Bionic
V1.1"** — caught from a device screenshot (pulled via root bridge). Title → `@string/app_name`
(per-flavor), version → `BuildConfig.VERSION_NAME` (dynamic, new `@+id/TVVersion`). Also cleaned the
leftover "Star Bionic" in the unused `about_dialog.xml`.

**Build log:**

| Step | Commit / Tag | CI Run | Result |
|---|---|---|---|
| Updater core (Settings/About/banner) | `41d7c06` | `28193511129` | ✅ green |
| Include-prereleases toggle + UA fix | `19b7e36` | `28195066124` | ✅ green |
| Merge + bump → 1.8-pre1 (vc26) | `ca87892` | `28195824422` (release) | ✅ published (prerelease) |
| Setup-screen branding fix | `b11814c` | `28197124387` | ✅ green |
| Merge + bump → 1.8-pre2 (vc27) | `2b10f53` | `28197773910` (release) | ✅ published (prerelease) |

**✅ DEVICE-PROVEN:** on the installed vc25 build, toggling Include-prereleases ON surfaced 1.8-pre1, and
Update downloaded + installed + launched it end-to-end. 1.8-pre2 (vc27) cut to re-test + carry the
branding fix. Stable 1.7 users untouched throughout (`releases/latest` still 404s for update.json since
1.7 predates the feature; no pre is make_latest). Main tip `2b10f53`.

**🐛 KNOWN latent bug (NOT yet fixed):** GitHub `/releases` API is not reliably newest-first — it hoists
the `make_latest` (stable) release to the top. `pickNewestWithUpdateJson` takes the first array element
with an `update.json`, which works while only prereleases carry it, but once a **stable + a newer
prerelease coexist**, the older stable would win over the newer beta for toggle-on users. **Fix before
cutting 1.8 stable: sort releases by `published_at` (fallback `created_at`) DESC before scanning.**

---

## 2026-06-25 — GameHub HUD: device-test crash fix + full in-game drawer mirror (branch `feat/gamehub-perf-hud`)

Continued the GameHub HUD port from P0–P4 (entry below) into on-device testing. Two follow-ups, neither merged.

**1. First-launch crash FIXED (`4808d51`, build `28179250039` ✅ green).** Installing the correct P4
build and enabling the GameHub HUD crashed the container + app the moment the overlay first refreshed.
Logcat (pulled via the root bridge):
```
FATAL EXCEPTION: Thread-6
android.view.ViewRootImpl$CalledFromWrongThreadException: Only the original thread that
  created a view hierarchy can touch its views. Expected: main Calling: Thread-6
    at com.winlator.star.widget.PerfHudView.update(PerfHudView.java:350)
    at ...XServerDisplayActivity$6.onUpdateWindowContent(:798)   ← X-server epoll thread (PresentExtension)
```
Root cause: `PerfHudView.update()` runs on the X-server epoll thread and called `requestLayout()` /
`invalidate()` directly — neither is thread-safe. `FrameRating` never hit this because it marshals via
`post(this)`. Fix = `post(refreshOnUi)` where `refreshOnUi = () -> { requestLayout(); invalidate(); }`.
The other two view-touching methods (`applyConfig`, `setVertical`) already run on the UI thread
(`runOnUiThread` at `XServerDisplayActivity:561` / tap handler). **Gotcha for future HUD work: anything
reached from `onUpdateWindowContent` is on the epoll thread → only `post()` / `postInvalidate()`.**

**2. In-game side drawer now fully mirrors the container dialog (`7437c3d`).** User wanted the same
settings/toggles in the in-game HUD tab. Before, `XServerDrawer.kt` → `HudContent` only had the classic
subset (scale/opacity + 6 toggles) and its `buildConfig()` **omitted `hudStyle` and every gamehub-only
key** — so changing anything in-game while the GameHub HUD was active stripped `hudStyle=gamehub` from the
saved container config (persisted via `onFpsConfigApply` → `setFPSCounterConfig` + `saveData` at
`XServerDisplayActivity:567`), reverting to the classic HUD on next launch. Rewrote `HudContent` to mirror
`FpsCounterConfigDialog` exactly: GameHub-style switch, FPS-graph / Power / GPU-model / dual-battery
toggles, skin/color/outline 3-stop chips (new drawer-styled `HudChipRow`, no FilterChip import), opacity
slider, and the **identical key set** (emits both classic + gamehub metric key names) so the drawer and
the pre-launch dialog are interchangeable. Metric/skin/scale/opacity changes apply **live** via
`onFpsConfigApply` → `perfHud.applyConfig` (UI-thread safe). The classic↔gamehub **view swap** still
applies on next launch only (the view is chosen at launch; a caption notes this) — live view-swap is a
possible follow-up.

Branch tip `7437c3d`. Combined build CI `28181338752` (in progress at time of writing). **NOT merged.**
Next: device-test the `28181338752` build — (a) enable GameHub HUD (master **Show FPS** must also be on),
launch → confirm renders without crash + tap flips orientation + metrics live; (b) open in-game drawer
HUD tab → confirm all GameHub controls present, apply live, and no revert-to-classic → tune dims/colors →
merge to main.

## 2026-06-25 — GameHub-style performance HUD port (branch `feat/gamehub-perf-hud`) — P0–P4 coded, device-test pending

A second, **selectable** in-game performance HUD modeled on GameHub 6.0.9's overlay, alongside the
existing `FrameRating`. User scope: **full parity** (17 controls), **per-container**, **all 3 skins**.
Clean reimplementation (our own View + data we already collect) — no GameHub code/assets copied.

Recon: 3 Explore agents over the jadx decompile (`/home/claude-user/gamehub-6.0.9-jadx`, GameHub =
Compose-Multiplatform `com.xiaoji.egggame`, obfuscated). HUD = plain Canvas/Paint/Path (no Compose/GL/
native for visuals; ref legacy View `o6m.java`). Two layouts (horizontal pill / vertical list), FPS line
graph (last 50 samples, peak clamped ≥60, 30fps guide), Classic/Neon/Mono skins, color-intensity
(0.72/0.88/1.0), text outline (off/1.0dp/1.4dp), scale 0.6–1.4, opacity. Most metrics already collected
by `FrameRating`; FPS comes from our own frame counter (GameHub's `libxserver.so` shm not needed).

- **P0 plan** `docs/GAMEHUB_PERF_HUD_PORT_PLAN.md`.
- **P1+P2** new `widget/PerfHudView.java` — self-contained Canvas view: both layouts, per-field colors,
  FPS graph, all 3 skins, color-intensity, outline, scale, opacity; parses the `fpsCounterConfig`
  KeyValueSet; `update()` frame-tick mirrors `FrameRating`; tap-toggle + drag. Standalone compile CI
  `28175068799` ✅ green.
- **P3** new `widget/HudMetrics.java` — shared collector: GPU% / temp / RAM / power+charging ported from
  `FrameRating`, **plus** overall CPU usage % (`/proc/stat` delta) and the dual-battery power fix (sums
  `battery`+`bms`+`main` `current_now` with abs()).
- **P0 wiring** `XServerDisplayActivity.java` — when `hudStyle=gamehub`, creates a `PerfHudView`
  (WRAP_CONTENT params) instead of the two `FrameRating` views; handled at every HUD site (create /
  show-hide / frame-tick update / live applyConfig / `toggleFpsHudOrientation`→`perfHud.setVertical` /
  DX-API detect→`setEngineLabel` / `_MESA_DRV_GPU_NAME`→`setGpuModel`). Classic path unchanged.
- **P4 config UI** `ContainerDetailScreen.kt` `FpsCounterConfigDialog` — "GameHub-style HUD" switch
  (`hudStyle`) + 9 metric toggles + dual-battery + scale/opacity sliders + skin/color/outline 3-stop
  FilterChip selectors (`HudToggleRow`/`HudThreeStop`); toggles emitted under both classic + gamehub key
  names; bounded scroll via `heightIn(screenHeight*0.7)`.

Config keys (per-container `fpsCounterConfig`): `hudStyle=classic|gamehub`, `hudMode`, `showFPS`,
`showFPSGraph`, `showCPUUsage`(+`showCPULoad`), `showGPULoad`, `showRAM`, `showPower`,
`showTemp`(+`showBatteryTemp`), `showEngine`(+`showRenderer`), `showGpuModel`, `hudDualBattery`,
`hudSkin=classic|neon|mono`, `hudColor=soft|mid|vivid`, `hudOutline=off|soft|strong`, `hudScale` (50–150),
`hudOpacity` (0–100), `hudTransparency` (classic).

Branch tip `b2fc55e`. Full artifact build CI `28176206476` (in progress at time of writing). **NOT merged.**
Next: verify build green → device-test (enable "GameHub-style HUD" in a container's FPS settings, launch,
confirm render + tap-flip + live metrics) → tune dimensions/colors on device → merge to main. Caveats:
HUD dimensions are first-guess; CPU% is overall-device (not per-game); in-game `XServerDrawer.kt` config
not extended (container-detail dialog only; orientation tap still works in-game).

## 2026-06-25 — Two HUD fixes merged to main (`ac6abbb`)

- **Fake FPS offset removed** (`19c9982`): stripped a fudge offset from `FrameRating.java` +
  `FrameRatingHorizontal.java` so the on-screen FPS reads true when native rendering is on.
- **Vertical HUD tap area fixed** (`ac6abbb`): the vertical `FrameRating` was added to the root
  `FrameLayout` with no LayoutParams, inheriting FrameLayout's `MATCH_PARENT × MATCH_PARENT` default — so
  its tap-to-toggle hit area covered the whole screen (a tap far from the overlay flipped orientation).
  Fixed with explicit `WRAP_CONTENT` + top-left gravity. Both built green (`28172970834`) and
  fast-forward-merged to main; `fix/fps-hud-tap-area` deleted. No release cut (still versionCode 25).

## 2026-06-24 — Steam detail-page revamp — branch `feat/steam-detail-revamp` (stacked on launch branch)

User asked to modernize the Steam game detail page. Picked all of: stored-info rows, last-played,
real playtime hours, bigger sheets (DLC/branch/cloud/add-home), and more robust/fluid download buttons +
accurate progress bars.

- **Chunk 1 DONE** (`SteamGameDetailActivity.kt` `ca90b96`): renders **developer / genres /
  metacritic** (already stored in our GameRow but previously hidden) + a **"Last played"** row from the
  install-dir timestamp (`relativeTime()`); reworked the downloads UI — **animated rounded progress bar**
  in a card with an **indeterminate "Preparing…" phase** and a separate %/bytes line; new
  `DetailActionButton` (48dp, rounded, disabled dimming) + `DetailInfoRow`. Pure UI/Compose.
- **Chunk 2 — real playtime hours: ABANDONED + REVERTED** (`ee85bf9`). Tried owned-games via JavaSteam
  unified messages (`SteamOwnedGames.kt`) but the API fought compilation (needed protobuf-java for the
  proto `GeneratedMessage` supertype; then `Player.getOwnedGames` return type is neither `Future` nor
  `CompletionStage` — `.get()`/`.toCompletableFuture()`/`.result`/`.body` unresolved). User chose to drop
  playtime recording; timestamp-based "Last played" (chunk 1) stays. Removed the file + Playtime row +
  protobuf-java dep.
- **TODO (remaining follow-up):** **bigger sheets** — DLC/depot manager, beta branch picker,
  cloud-save export/import/sync, add-to-home-screen (port from ref4ik `SteamLibrarySheets.kt` /
  `SteamGameActions.kt`).

**RESUME SNAPSHOT (2026-06-24, for crash recovery):** Two stacked feature branches, NONE merged, all
device-test-pending:
  • `feat/steam-pluvia-launch` (off main) — Pluvia Phase 1 coldclient launch, steps 1-4, commits
    `ff13265`/`1c6839d`/`f0b6106`/`73834d6`, all compile-green. Drawer "Steam" = unchanged store; adds
    emulation launch.
  • `feat/steam-detail-revamp` (stacked on the launch branch) — tip `ee85bf9` — detail revamp chunk 1
    only (chunk-2 playtime reverted). CI `28143904501`.
NEXT WHEN RESUMING: (1) confirm CI `28143904501` green; (2) device-test on the test device — Steam
download → add a steam_api game with "Steam emulation" → confirm Goldberg launch; detail page shows
dev/genres/metacritic/last-played + fluid progress; (3) optionally build the bigger sheets;
(4) then decide merge order (launch branch → main first, then rebase revamp → main). ref4ik clone at
`/home/claude-user/scratchpad/ref4ik`. Full design = `docs/STEAM_PLUVIA_PORT_PLAN.md`.

**imageFS reinstall?** No — for the Steam coldclient + detail revamp, updating 1.7 → next release needs
NO imageFS reinstall. The coldclient loader is a separate bundled APK asset extracted at runtime into the
existing imageFS (not baked into `imagefs.txz`), and the detail revamp is app-only. (Only carryover: if a
user never reinstalled imageFS for 1.7's ffmpeg-8, that 1.7 recommendation still applies.)

## 2026-06-24 — Pluvia Steam: Phase 1 (Goldberg/coldclient launch) — branch `feat/steam-pluvia-launch`

Implementing the recommended **Option A** from `docs/STEAM_PLUVIA_PORT_PLAN.md` — **UPGRADE the
existing Steam store** (browse/login/download/UI stay ours, unchanged from 1.7), adding only the
Goldberg/coldclient **launch** so SteamAPI titles actually run. ⚠️ User confirmed scope 2026-06-24:
NOT a full replacement (drawer "Steam" still opens our existing store). All work on the branch; NOT
merged; device-test pending.

- **Step 1 (`ff13265`)** — bundled asset `experimental-drm.tzst` (coldclient loader x32/x64 +
  emulated steamclient DLLs + extra_dlls; PE → host-arch independent) + `SteamClientManager.kt`
  (extracts it into the imageFs Steam dir). Ported from REF4IK/winlator-ref4ik- (GPL-3.0).
- **Step 2 (`1c6839d`)** — `SteamLaunchUtils.kt`: self-contained **offline** Goldberg helpers
  (writeColdClientIni, generateInterfacesFile, writeOfflineSteamSettings, backupSteamclientFiles,
  putBackSteamDlls, setupLightweightSteamConfig, skipFirstTimeSteamSetup, ensureSteamappsCommonSymlink).
  No dependency on ref4ik's Room SteamService; account read from our `steam_prefs`.
- **Step 3 (`f0b6106`)** — launch glue: `prepareColdClientLaunch` + `writeGameSteamSettings` +
  `StarLaunchBridge.addSteamGameToLauncher`/`writeSteamShortcut` (container picker → activateContainer →
  prepare env → write `.desktop` `Exec=wine C:/Program Files (x86)/Steam/steamclient_loader_x64.exe`
  with `game_source=STEAM` Extra Data). Prefix model = `activateContainer` repoints the `home/xuser`
  symlink → active container; corrected ref4ik's `skipFirstTimeSteamSetup` to take `imageFs.rootDir`.
- **Step 4 (`73834d6`)** — Compose UI: `SteamGameDetailActivity` shows a Compose AlertDialog
  ("Steam emulation" vs "Run .exe directly") after exe resolution → routes to the coldclient path or
  legacy raw path; `SteamGamesActivity` defaults its add paths to the emulation route.

Compile CIs: steps 1+2 `28141425805` ✅ green; steps 3 `28141874404` / 4 `28142049412` ⏳ pending.
**Next:** device-test a steam_api title (download → add with Steam emulation → boots under Goldberg)
→ then merge to main. Possible follow-ups: preferred-container, PICS LaunchInfo exe detection, cloud saves.

## 2026-06-24 — 🚀 Release 1.7

Cut **Bannerlator 1.7** (`versionName 1.7`, `versionCode 25`, commit `30c869c`). Version bumped in
`app/build.gradle`; splash screen reads `BuildConfig.VERSION_NAME` so it shows "V 1.7" automatically
(no hardcoded version strings anywhere). README version line + "What's New in 1.7" updated (1.6 notes
demoted to "Previously in 1.6"). Release build = workflow "Nightly Manual Release Build" run
`28140854161` (tag `1.7`, builds standard/ludashi/pubg release APKs) — ✅ GREEN. **PUBLISHED**
https://github.com/The412Banner/Bannerlator/releases/tag/1.7 with full notes; 3 assets each ~588.7 MB
(`Bannerlator-1.7-standard.apk` 588704729 B / `-ludashi.apk` 588704765 B / `-pubg.apk` 588704647 B).
Everything merged to main since the 1.6 tag is in this release:

- **Steam store — downloads fixed**: login-race guard (`9f6197e`) + BouncyCastle SHA-1 provider
  registration (`63e4366`). ⚠️ download-only; raw `wine exe` launch still has no steam-emu (DRM games
  may not run — see `docs/STEAM_PLUVIA_PORT_PLAN.md`).
- **Components installer (new)**: in-container Wine-dependency installer (Phase 2 file-drop + Phase 3b
  execute engine), copy_dll glob + arch-targeting fixes, win7/winXP set_windows, persisted Installed
  status.
- **On-screen controls**: overlay-opacity slider moved to in-game side menu, live, true 0–100 %.
- **FPS overlay**: tap to toggle orientation, live D3D API label (VKD3D vs DXVK).
- **Vulkan**: Advanced Vulkan / Graphics Driver dialogs scrollable.
- **Video**: full ffmpeg-8 libs bundled for winedmo.

⚠️ DEVICE-TEST status: Steam download fix, Components installer, and overlay-opacity were CI-green but
device-test was still pending/partial at release time.

## 2026-06-24 (late 2) — Steam download fixes + Pluvia/GameNative Steam-store recon & plan

**Steam download bug (✅ MERGED to main `63e4366`/`9f6197e`; compile CI `28139917719` ✅ green;
device-test pending).** User's Steam game downloads failed "Download failed: Unknown error".
Two distinct bugs found from the on-device `steam_debug.txt`:
1. *Login race* — `runInstall` started while `connected=true` but `loggedIn=false` (Steam CM
   connections cycle; re-logon after reconnect is async, license cache masked it). Manifest job
   timed out → `CancellationException`. Fix = new `SteamRepository.ensureLoggedIn(timeoutMs)` guard
   in `runInstall` (re-logon from saved token, wait up to 15s).
2. *SHA-1/BC* (the real download-killer, seen after re-login) — JavaSteam `DepotManifest.serialize`
   calls `MessageDigest.getInstance("SHA-1","BC")`; Android's built-in "BC" provider has SHA-1
   stripped → `NoSuchAlgorithmException`. App bundled `bcprov-jdk15on` but never registered it.
   Fix = static initializer in `SteamRepository` that removes stock BC + installs the full
   `BouncyCastleProvider`. Device-test of FlatOut 2 pending; then merge to main.

**Recon + plan: "Pluvia Steam" to replace the current Steam store.** Researched GameNative
(utkarshdalal/GameNative, GPL-3.0; local at `/home/claude-user/GameNative/`) and Pluvia
(oxters168/Pluvia, original, stalled). Key finding: **REF4IK/winlator-ref4ik-** (`com.winlator.cmod`)
already ported the GameNative/Pluvia Steam module into a Winlator **Cmod** fork — same lineage as
us — on the **same `in.dragonbra:javasteam:1.8.0`** we ship. It's an *upgrade*, not greenfield:
our download already works (post-fix); the real prize is the **Goldberg/coldclient launch model**
(ref4ik `SteamGameLauncher.kt`) — our store launches raw `wine exe`, so DRM/steam_api titles fail.
Recommendation = **Option A incremental** (Phase 1: Goldberg launch + loader assets +
`game_source`/`app_id` shortcut extras; then preferred-container, PICS LaunchInfo exe detection,
optional cloud-saves/updates). Full file-level seam map + risks (GPL-3.0, Goldberg asset arch,
bionic = coldclient only, A:↔Z: drive, Room↔SQLite) → **`docs/STEAM_PLUVIA_PORT_PLAN.md`** (this
commit). NOT STARTED — for down the road.

## 2026-06-24 (late) — Merged the day's branches to main + new Components installer fix

Rolled the day's feature branches onto `main` (linear rebase/ff, branches deleted). `main` tip now
`0ea1a84`. The 10 commits that make up today (oldest→newest):

1. `445f963` Components Phase 3b — execute engine for installer-based components (.NET/vcredist)
2. `1955f43` Components Phase 3b — auto-close installer sessions + cleanup
3. `c4399ce` Components — install win7/winXP via pure `set_windows` instead of N/A
4. `ce6561d` imagefs — bundle full ffmpeg-8 libs for winedmo video decode *(was the leftover "PENDING #2")*
5. `fe8e74d` HUD — live D3D API label (VKD3D vs DXVK) + tap overlay to toggle FPS orientation
6. `19ec967` HUD — tap overlay to toggle orientation live; dropped the settings dropdown
7. `de71493` Vulkan — make Advanced Vulkan / Graphics Driver dialogs scrollable
8. `16dc463` docs — components N/A backfill + quartz device-test (this log)
9. `4b9b0ad` Controls — overlay-opacity slider moved into in-game side menu (live, true 0–100 %)
10. `0ea1a84` **Components fix (new today)** — see below

**`0ea1a84` Components installer — two bugs fixed** (branch `fix/components-copy-and-installed-persist`,
rebased+ff to main, deleted; CI `28137352729` ✅ green; **device-untested**):
- **`copy_dll` glob was broken.** `copyMatching` built its regex as
  `Regex.escape(pattern).replace("\\*",".*")`, but Kotlin's `Regex.escape` uses `Pattern.quote`
  (`\Q…\E`), so a literal `"*"` file_name pattern compiled to `^\Q*\E$` — matching a file *named* `*`
  (nothing). The `*` components (`atmlib`/`devenum` + the pre-baked win7-SP1 set) set their DLL
  override but **never copied the DLL**. Fix: `pattern.split("*").joinToString(".*"){Regex.escape(it)}`
  → proper glob semantics. (`ComponentInstaller.kt`)
- **"Installed" status didn't persist.** It lived in an in-memory `remember{}` set, so it reset on
  every sheet close/reopen. Now persisted per container in SharedPreferences `component_installs`
  (key `c<id>`): loaded on open, written on each successful install. (`ComponentsSheet.kt`)

**Main artifact build:** triggered run **`28138274652`** (CI Build, artifacts only, `main`).
**Next:** device-test the glob fix + persisted-installed status (root bridge) → then cut 1.6.

## 2026-06-24 — Components: backfilled 15 "N/A" components + device-tested registration

**Backfill (winlator-contents `1f6eb72`).** The catalog had **17 components stuck at N/A**
(`needs-upstream`/`pending-manual`) because their source files were never mirrored. User supplied
the missing Microsoft files (`windows6.1-kb976932` Win7-SP1 x64+x86, ~1.5 GB; `powershell-wrapper.zip`),
covering **15 of 17**.

- The app has **no runtime cab engine** (verified: `ComponentInstaller`/`ComponentExecInstaller`
  handle neither `cab_extract`/`get_from_cab` nor `register_dll`; Phase 3b = installer-exec, not cab).
  The 12 already-working cab components were **pre-baked build-side** — so I followed the same method.
- **Pre-baked** the DLLs with `cabextract` straight out of the SP1 packages (all validated PE/`MZ`),
  packaged each as `<name>__libs.tar.xz` (`win32/`+`win64/` layout, gdiplus = 1.1.7601.17514),
  uploaded all **15** to release `system-libraries-v1` (~10 MB total — not the 1.5 GB raw `.exe`s,
  which would never install).
- **Rewrote** each component in `components.json` to the proven file-drop pattern
  (`archive_extract` + `copy_dll`(+`override_dll`)) — exactly like `devenum`/`riched20`; dropped the
  unsupported `register_dll` (native override inherits Wine's builtin COM registration). PowerShell
  repackaged into the same convention (its `powershell_core` dep was already `ready`).
- Catalog tally now **ready 112 / N/A 2**. Still N/A: `art2k7min` (needs AccessRuntime2007.exe),
  `vbrun6` (needs VB6 SP6 runtime).
- **No app rebuild needed:** `ComponentCatalog` fetches the catalog live (no cache) from
  `raw.githubusercontent.com/.../main/components.json`; installed builds see the 15 within minutes.

**Device test — quartz registration CONFIRMED (root bridge, `com.winlator.banner`).** Premise held:
every container prefix already has builtin Wine quartz fully COM-registered (**48** `quartz.dll`
InprocServer32 refs, FilterGraph CLSID `{e436ebb3-…}` + DirectShow Filters category present). Did a
reversible end-to-end install on `xuser-2` "P11 ARM" (backups `*.bak-comp`): native MS `quartz.dll`
→ system32 (1,572,352 B) + syswow64 (1,328,128 B), both `MZ`; inserted `"quartz"="native,builtin"`;
**48 CLSIDs still resolve to quartz.dll (now the native DLL), FilterGraph CLSID intact**. Only the
runtime-load under Wine/arm64ec (launch a DirectShow/FMV title) is left for a user-side check.

## 2026-06-24 — In-game overlay-opacity (controls) reworked + moved to side menu

On-screen controls "overlay opacity not working" → fixed + relocated. Was: draw curve
`0.5+0.7*opacity` (dead top ~29 %, never faint), `setOverlayOpacity()` never `invalidate()`d, editor
hardcoded 0.6. Now **linear 0–100 %** (0 % = fully invisible; accent-stroke alpha floors scaled with
opacity), live `invalidate()`, and the slider **moved from the Input-Controls profile screen into the
in-game side menu (Controls tab)** so it tunes the visible overlay live (`XServerDrawerState`
`overlayOpacity` + `onOverlayOpacityChange`; activity applies + persists). DEFAULT 0.4→0.75 (matches
old look under the new mapping). Branch `feat/ingame-overlay-opacity` `d3f2a8b`, compile CI
`28135045851` ✅ green. Next: device-test → merge for the next release.

## 2026-06-23 — Components installer: catalog + mirror DONE (app side next)

Building a **Components installer** for container settings — browse + install Wine dependencies
(mono, gecko, dotnet, vcredist, d3dx, …) into a container's prefix, the same set BannerHub/GameHub
offer (Bottles "Type 6 — System Libraries", 114 components).

- **Mirrored** all components' binaries to a new release **`system-libraries-v1`** on
  `The412Banner/winlator-contents` — **92 assets**, deduped by URL (shared payloads like the Win7-SP1
  packages referenced once, not per-component). Each asset named after its component.
- **6 not mirrored** (manual re-source list at `/sdcard/Download/winlator-components-needed.txt`):
  the 3 huge **Win7-SP1 platform-update** packages (shared by 14 components → referenced upstream) and
  3 dead/timed-out sources (`art2k7min`, `powershell`, `vbrun6`).
- **`components.json` committed + live** on winlator-contents
  (`raw.githubusercontent.com/The412Banner/winlator-contents/main/components.json`) — 114 components
  with full **Bottles-format install steps**, URLs rewritten to the mirror; `status` per component:
  **ready 97 / needs-upstream 14 / pending-manual 3**.
- **App side: Phase 2 + Phase 3a DONE & MERGED to main** (`4c732b8`, build `28072511822`).
  - **Phase 2** (`91ca6a3`): a "Components" browser in the Win Components tab (`ComponentsSheet`) +
    `ComponentCatalog` (reads the live components.json) + `ComponentInstaller` (file-drop Bottles
    steps → `system32`/`syswow64` + DLL overrides via `WineRegistryEditor`). **Device + root-verified:**
    installed `d3dcompiler_43`/`_47` — correct 64-bit→system32 / 32-bit→syswow64 + overrides set.
  - **Copy hardening** (`4c732b8`): `copy_dll` constrains source to the matching arch sub-tree.
  - **Phase 3a (pre-bake, no app change):** extracted the cab contents build-side with `cabextract`,
    hosted 12 components as `<name>__libs.tar.xz` on the `system-libraries-v1` release, and rewrote
    their catalog steps to file-drop. **22 components now installable** (10 file-drop + 12 pre-baked
    cab: d3dcompiler_42/46, xinput, xaudio2.7, msxml6, atmlib, riched20, vcredist6, winhttp, …).
  - The app reads components.json at runtime, so catalog updates are live without an app rebuild.
- **Still to do — Phase 3b:** the execute engine (`install_exe`/`install_msi` via launching the
  container session) for the +54 .NET / vcredist runtimes. Plan in memory `project_bannerlator_components_installer`.

---

## 1.6 RELEASE MANIFEST (in progress, since tag `1.5` / `dc74f67`) — NOT yet released

Everything queued for the next release:

**Merged to `main`** (device-confirmed):
1. On-screen dpad/stick multi-touch freeze fix (`fba6080`, merged `d1356d8`) — GitHub issue #5, reporter-confirmed.
2. In-app File Manager batch (`d086990`→`5521e0f`, +`ca26466`) — data-loss paste, silent Run, working dir, off-thread listing, copy-into-self guard, copy progress bar, PTR/scroll/file+exe icons, system-Back-up-one-dir, Run-executes-exe-in-container (`core/WinePath.kt`).
3. Per-game (shortcut) overrides for Renderer + Frame-Gen engine + FPS limiter (`08878be`).
4. Frame gen starts OFF in-game on every launch (`a669b8b`).

5. Standalone FPS limiter — guest-side X11 Present IdleNotify pacing (`bd990b2`) + lsfg≥2 guard (`4909549`); caps fps with Off / bionic-fg / lsfg-vk, both host renderers, live. ✅ merged to main (`a2ebd35`), GameNative credit (`0eadf16`).
6. Advanced Vulkan present settings now actually apply (native/presentMode/filter/swapRB) + renderer-dropdown label/gear fix. ✅ merged to main (`dcd9d47`).

**In progress (before 1.6, user's call)** — branch `feat/layer-download-menu`:
7. Compatibility-layer download menu rework — adrenotools-style cards, cloud opens the sheet directly, install-from-file in the sheet, Wine/Proton chips, in-use marker, byte-accurate install bar. See the dated section below.

Next: finish the download menu → merge → cut 1.6 (bump versionCode from 23 + splash).

---

## 2026-06-23 — Compatibility-layer download menu rework (branch `feat/layer-download-menu`, in progress)

Reworked the per-component download entry points into an adrenotools-style menu. The backend
(`ContentDownloadSheet` + `ContentsManager` + one remote `contents.json`) already covered all five
layers — the work is front-end consolidation + a real install bar. Confirmed design (HTML preview
first, then implemented):

- **Cloud icons replace the gears** on every layer (Wine/Proton, DXVK, VKD3D, Box64/WOWBox64, FEXCore);
  the cloud opens the download sheet **directly**. "Install from file" moved **into** the sheet header.
- **Adrenotools-style rows** — flat rows, `Memory` icon, name + "In use"/"Installed"/desc subtitle,
  trailing `CloudDownload`; chips restyled to the adrenotools `SourceChip` look. **Wine/Proton chips**
  split the compatibility-layer sheet; the others are single-type.
- **In-use marker** for the container's current version (Wine/Box64/FEXCore). Author/size are NOT in
  the manifest (`ContentProfile` has only type/verName/verCode/desc) — would need a manifest extension
  or a HEAD request; deferred.
- **Two determinate 0→100 bars** — blue "Downloading" (byte-accurate) and green "Installing", now also
  **byte-accurate**: `TarCompressorUtils` got a `CountingInputStream` + `OnReadProgressListener` and an
  `extract(…, total, listener)` overload reporting `bytesRead/total` off the compressed stream
  (single-pass, denominator = downloaded .wcp size); `ContentsManager.extraContentFile` got a matching
  overload; the sheet feeds it monotonically (ignoring the brief XZ-probe before the ZSTD pass).

VEGAS and the adrenotools GPU-driver downloader are left untouched. Kept as a centered `Dialog` for now
(bottom-sheet vs centered to be decided on device). First impl device-tested ("looks good").

**Build status (2026-06-23):** UI restyle + cloud-direct + file-in-sheet + in-use = `f4e551e` (CI
`28056314348` ✅). Byte-accurate install bar = `f9485ef` (CI **`28057297317`** — final combined build).
**⏸️ Resume:** verify `28057297317` green → download standard APK → device-test (cloud opens sheet
directly, adrenotools cards, Wine/Proton chips, install-from-file, in-use marker, real install bar) →
decide bottom-sheet vs centered + whether to add author/size → merge to main → cut 1.6.

---

## 2026-06-23 — Standalone FPS limiter (guest-side IdleNotify pacing) DEVICE-CONFIRMED ✅

Commit `bd990b2`, branch `feat/standalone-fps-limiter`, CI `28043133606` ✅ green.

The reworked limiter — guest-side X11 Present `IdleNotify` pacing in `PresentExtension`
(GameNative/Ludashi-3.1 mechanism: delay IdleNotify → DXVK blocks waiting for a free
buffer → the GUEST throttles), decoupled from the frame-gen layers — **caps fps in all
three FG modes: Off / bionic-fg / lsfg-vk.** Engine-agnostic, all-API, live in-game toggle.
This succeeds where the earlier host-side nanosleep pacer (`f8d7598`) failed (that one only
dropped frames at the compositor; the guest ran full-speed).

**✅ Confirmed on BOTH host renderers** — all 3 modes (Off / bionic-fg / lsfg-vk) cap fps on
the OpenGL host renderer AND the Vulkan host renderer.

**lsfg-mult≥2 guard wired (commit `4909549`, CI `28046025979`).** `lsfgGovernsFps()` returns true
when engine=lsfg + FG enabled + multiplier≥2; `applyFpsLimit()` clamps to 0 in that case, and
`reapplyFpsLimit()` runs from the lsfg branch of `onBionicFgConfigChange` so the guard engages the
moment the multiplier crosses 2. Rationale: lsfg paces itself when multiplying — layering our
IdleNotify limiter on top double-paces the stream (clamps the panel to the limiter value, kills the
FG gain, wastes GPU). Unaffected: bionic-fg, Off, and lsfg at 1× still cap.
> ⚠️ **SUPPORT NOTE:** if users report "the FPS limiter doesn't work / no cap" on **lsfg-vk**, this
> guard is the intended cause — the limiter is deliberately disabled while lsfg-vk multiplies
> (mult≥2). Documented in-code at `lsfgGovernsFps()`. Not a bug.

Remaining: guard CI green → merge `feat/standalone-fps-limiter` → main for 1.6.

---

## 2026-06-22 — lsfg-vk live reload CONFIRMED ✅ + Off→passthrough fix + engine badge + Task-Manager-on-Vulkan bug (diagnosing)

Session driven by live device questions ("which FG engine is running right now?").

**1. lsfg-vk 3× + LIVE RELOAD confirmed working (supersedes the 2026-06-21 "no live reload" finding).**
Probed the running game (`DOOMBLADE.exe`) on device: `liblsfg-vk.so` mapped into the game proc, env
`ENABLE_LSFG=1` / `LSFG_PROCESS=bannerlator-lsfg` / `LSFG_CONFIG=…/home/xuser/.config/lsfg-vk/conf.toml`,
`Lossless.dll` present. Logcat showed `lsfg-vk: Rereading configuration, as it is no longer valid.` →
`Reloaded configuration … Multiplier: 3` → `lsfg-vk-framegen: Entering Device::Device` — i.e. the
mtime-watch → OUT_OF_DATE → swapchain-recreate reload mechanism (GameNative fork `.so`) DOES fire on our
DXVK→vkd3d→wrapper_icd→Turnip stack now. Panel present rate ~138–143 fps on a 144 Hz panel = base ~46 × 3.
DXVK HUD correctly shows the BASE rate (~46) because lsfg-vk inserts frames downstream of DXVK's counter
(HUD≠panel is expected, and is itself proof FG works). Two conf.toml files exist: live
`home/xuser/.config/lsfg-vk/conf.toml` (read by the layer) and a stale `home/xuser-1/.config/bionic-fg/conf.toml`
(ignored by lsfg; its `fps_limit` field isn't an lsfg option). Minor cleanup candidate.

**2. Off-bug found + fixed.** Installed APK (`7f7ffb5`) predated the Off fix (`80e238a`), so in-game "Off"
wrote `multiplier = 2` (`Math.max(2,0)`) → still 2× frame gen. `80e238a` writes `1` (true passthrough).
Built off-fix APK (run `27941385132`, label `1.3-lsfg-offfix`, ✅ green). PROVEN on device by live-editing
the running conf.toml `multiplier 2→1`: reload fired (`Rereading` → `Multiplier: 1`), FPS dropped to native
~21–27. So `multiplier=1` = genuine off.

**3. Engine badge in in-game FG drawer (commit `740e779`).** Per user, replaced the standalone
"Frame Generation (AI)" header with a title + engine badge row — `Frame Generation  [● bionic-fg]` (green
dot = layer running this session; swaps to `lsfg-vk`; "Off" when disabled). No double labeling (user picked
the "Engine badge" layout). Plumbed `XServerDrawerState.frameGenEngine` ← `container.getFrameGenEngine()`,
wired in `XServerDisplayActivity` next to the other FG drawer-state setters.

**4. Task Manager reports nothing on the Vulkan host renderer (SAME container works on OpenGL). Diagnosing.**
Game runs fine; `winhandler.exe` (the process-list backend) is alive; no app crash. The new off-fix build's
Task Manager refreshes on a render-independent 1s timer and STILL shows empty on Vulkan → ruled out the
UI-tick/copyArea theory. `setupTmCallbacks`/listener registration are NOT renderer-conditional in source, so
nothing intends to disable it on Vulkan. Added WinHandler diagnostic logging (commit `e75d1d4`, tag
`WinHandlerTM`): logs INIT handshake, each `listProcesses` send + sendPacket result, every received request
code, and `GET_PROCESS` replies. One Vulkan run with Task Manager open will split it: `GET_PROCESS` arriving
but UI empty → Compose/StateFlow update problem on Vulkan; no `recv` at all → guest not replying / INIT never
happened. NOT yet root-caused.

**Builds:** off-fix `27941385132` (`1.3-lsfg-offfix`) ✅; logging-only `27943043968` (`1.3-tmlog`) ✅;
combined `27943884565` (`1.3-tmlog-badge` = off-fix + WinHandler logging + engine badge) — in progress.
Branch `feature/lsfg-vk-engine` tip `740e779`, pushed, NOT merged.
**NEXT:** deliver combined APK to `/sdcard/Download` + arm `WinHandlerTM` logcat → user opens Task Manager on
Vulkan once → read logs to root-cause → fix → merge to main.

---

## 2026-06-21 (night) — lsfg-vk DEVICE TEST: works (2×) but live in-game reload does NOT on our stack ⏸️ RESUME

Installed the test APK (`Bannerlator-1.3-lsfg-vk-standard.apk`, testkey, updates over current), imported a
`Lossless.dll`, selected lsfg-vk in a container, launched DOOMBLADE.

- ✅ **lsfg-vk loads + runs on our Turnip/Proton stack** (GameNative fork `.so` `93fa20bb`). Log
  `/sdcard/Download/lsfgvk_ingame_test.txt`: `Loaded configuration for bannerlator-lsfg` / `Shaders extracted` /
  layers init / AHB + swapchain contexts. **2× frame gen confirmed** (DXVK 39.4 → overlay 78). Opt-in
  `ENABLE_LSFG` gate, conf.toml driving, and `LSFG_PROCESS=bannerlator-lsfg` all work.
- 🐞 **Bug found + fixed (commit `80e238a`):** the in-game "Off" = drawer multiplier 0 (frame-gen stays
  enabled), and the callback did `Math.max(2,0)=2` → forced 2× on Off. Fixed to `mult>=2 ? mult : 1`
  (passthrough). Only matters on relaunch though, because…
- ❌ **Live conf.toml reload does NOT fire on our stack (definitive).** Bypassed the app entirely: `sed`'d the
  running game's conf.toml to `multiplier=1`, confirmed mtime changed → **zero `Rereading configuration` lines,
  FPS stayed 2×** (capture fresh through 19:36, not a gap). Then a fullscreen toggle (swapchain recreate) →
  still no change. So GameNative's mechanism (layer returns `VK_ERROR_OUT_OF_DATE_KHR` on conf change → DXVK
  recreates swapchain → layer re-reads) is **not propagating through DXVK → vkd3d → wrapper_icd → Turnip**.

**Decision pending (A vs B):**
- **(A, recommended)** ship lsfg-vk as **launch-time** config: restore the per-container Multiplier + Flow
  control (was in `1997a55`, removed in `7f7ffb5`); in-game drawer hides/labels lsfg FG controls as
  "relaunch to apply"; bionic-fg keeps its working live in-game control.
- **(B)** deep-dive why OUT_OF_DATE doesn't recreate/reload here (instrument a debug layer; uncertain).

**Resume state:** branch `feature/lsfg-vk-engine` @ `80e238a` (pushed, NOT merged). Build works; engine
selector + gray-out + DLL picker + lsfg-vk 2× all functional. Only the lsfg live in-game tuning is the gap.

## 2026-06-21 (evening) — lsfg-vk as a SECOND, selectable frame-gen engine (recon → spike → integration → in-game live)

New feature on branch **`feature/lsfg-vk-engine`** (off `main`; NOT merged): add **lsfg-vk** (Lossless
Scaling FG, PancakeTAS lineage) alongside the existing **bionic-fg** so users pick the engine per
container. User supplies their own `Lossless.dll` (we bundle nothing proprietary).

**Recon (3-repo lineage):** lsfg-vk source = `FrankBarretta/lsfg-vk-android@b55b182` (Android AHB port);
built by `The412Banner/LLS` CI (NDK 27, `-DLSFGVK_ANDROID_WINE=ON`, 2-line color-fix patch). Ludashi-plus
itself has NO lsfg-vk (it dropped the feature). LLS run `25313482636` has a clean prebuilt artifact (NO
`libc++_shared` dep — the libc++ blocker was only the old dead APK `.so`).

**Device spike (✅ SUCCESS):** staged the LLS prebuilt `.so` + manifest + the user's `Lossless.dll` into a
container's imagefs, env `LSFG_LEGACY=1 LSFG_DLL_PATH=… LSFG_MULTIPLIER=2 BIONIC_FG_DISABLE=1`. After fixing
my guest-relative DLL path → full Android path (guest is NOT chrooted), **DOOMBLADE ran with lsfg-vk doing
2× frame gen** (DXVK 39.3 → overlay 79). DEFINITIVELY confirmed lsfg-vk (not bionic) via live `/proc/*/maps`:
`liblsfg-vk.so` mapped r-xp in the game procs, `libbionic_fg.so` mapped in zero. lsfg-vk = plain implicit
layer, NO wrapper-ICD hack needed (unlike bionic-fg). ⚠️ it HARD-EXITS (bricks the container) if it can't read
the DLL → the feature must gate on a valid DLL.

**In-game LIVE control (GameNative recon):** stock lsfg-vk reads config once. GameNative makes mult/flow apply
mid-game by rewriting `conf.toml` → their **forked layer** watches the file mtime in its present hook → returns
`VK_ERROR_OUT_OF_DATE_KHR` → game recreates swapchain → layer re-reads config (the ~100ms "pause" the user sees
= that rebuild). NO SIGSTOP, no app-side swapchain call. So we **re-vendored GameNative's fork** (`.so` md5
`93fa20bb`, has the `Rereading configuration` mtime-watch) and drive via **conf.toml**, not `LSFG_LEGACY` env.

**Integration (commits `a8974d9`,`1b96cb4`,`1997a55`,`7f7ffb5`):**
- Layer staged opt-in: `assets/lsfg-vk/{liblsfg-vk.so,manifest}` + `ImageFsInstaller.installLsfgVkLayer()`;
  added `enable_environment ENABLE_LSFG=1` to the manifest so the on-by-default upstream layer can't brick
  other containers (loads only when a container selects lsfg-vk).
- Launch wiring (`XServerDisplayActivity`): engine==lsfg → `ENABLE_LSFG=1` + `LSFG_CONFIG=<home>/.config/lsfg-vk/conf.toml`
  + `LSFG_PROCESS=bannerlator-lsfg` + `writeLsfgConfig()` ([global].dll + [[game]] mult/flow/present=fifo),
  gated on the imported DLL existing; else bionic-fg path unchanged. Mutual exclusion = one engine's enable env.
- Data model (`Container`): `getFrameGenEngine/setFrameGenEngine/isLsfgEngine` ("off"/"bionic"/"lsfg"; default
  migrates legacy `frameGenEnabled`).
- UI: container FG control = engine selector ONLY (Off/bionic-fg/lsfg-vk); **lsfg-vk grayed out until a
  `Lossless.dll` is imported** (`LabeledDropdown` gained `disabledOptions`). DLL picker at the bottom of
  Settings (SAF → **copies into `filesDir/lsfg-vk/Lossless.dll`**, loads from the copy).
- **Unified in-game control:** the single in-game multiplier toggle + flow slider drive WHICHEVER engine the
  container runs — `onBionicFgConfigChange` branches to `writeLsfgConfig` for lsfg (live reload via the fork's
  mtime-watch); drawer activated for lsfg containers. No per-container mult/flow control.

**APK signing:** all builds now signed with the AOSP **testkey v1+v2+v3** (`keystore/testkey.p12`, commit
`e09ac71`) so releases/updates install over previous installs (one-time uninstall on first testkey build).

**Build:** test APK run `27920491173` @ `7f7ffb5` (label `lsfg-vk-ui-test3`) IN PROGRESS. ⚠️ Dispatch-race
gotcha hit again — verify `git ls-remote` tip == local before `gh workflow run`. **STILL UNVERIFIED ON DEVICE:**
GameNative fork `.so` loading on our Turnip stack + the live conf.toml reload mid-game (the test APK verifies).

## 2026-06-21 (later) — sticky sync-failure force-disable fix + new `.so` `9136405c` STAGED/SWAPPED

Found (via Jason's PR #6 `layer.cpp:1093` follow-up) that the runtime auto-disable wasn't sticky:
`noteFenceTimeout` stored the kill in `conf.enabled`, which the hot-reload path overwrites wholesale
(`st.conf = newConf`) — so any conf.toml touch (notably the in-game flow slider) silently re-armed
framegen on an ICD path already proven sync-incompatible. **Fix:** new sticky `SwapState::framegenForceDisabled`
that hot-reload never clears; QueuePresent gate honours it; clean re-attempt point stays a swapchain
recreate. Fork `The412Banner/bionic-fg`@`bannerlator-android-wrapper-icd-fixes` commit `c861d8c`
(4th unpushed commit ahead of PR remote `ac2f5c0`). App branch `feature/bionic-fg-pr-followups`
`6807e83` (patch regen 597→608L, applies clean).

**Build gotcha logged:** first dispatch (run 27916226393) checked out the STALE tip `56c6735`
(dispatched a beat too fast after push → byte-identical `4b99b2d1`, no change). Re-dispatched
27916284710 on confirmed remote tip `6807e83` → **new `.so` md5 `9136405c`**, `will NOT re-enable`
log string confirmed compiled in. RULE: after `git push`, verify `git ls-remote` tip == local before
`gh workflow run`, or the run may use the old ref.

**Hot-swapped onto device** (no reinstall — pure layer-internal change, no conf/JNI/ABI surface):
`imagefs/usr/lib/libbionic_fg.so` 4b99b2d1→`9136405c` (owner u0_a484, chmod 600); backups `.bak_c8e4`
(shipped) + `.bak_prev` (4b99b2d1). App force-stopped. Staged `/sdcard/Download/libbionic_fg_9136405c.so`.

⚠️ **Test scope:** the sticky-disable only ENGAGES on a sync-incompatible ICD (6 consecutive fence
timeouts). On the known-good Proton 11 + Turnip path framegen never force-disables, so the fix is
logic-verified by code; on-device this run is a **regression check** = confirm 2× still works + flow
slider still hot-reloads cleanly (i.e. my change didn't break the happy path). ⏳ awaiting user launch;
logcat → `/sdcard/Download/bionicfg_sticky_disable_test.txt`.

---

## 2026-06-21 — PR #6 review-followup `.so` (`4b99b2d1`) DEVICE-CONFIRMED ✅

Hot-swapped the new layer (`libbionic_fg_4b99b2d1.so`, build run 27915620310, fork HEAD `5f4fc03`)
into the installed app's imagefs — **no reinstall** — and tested on device. **All green.**

- **Frame gen 2× confirmed** (game "FOLLOW MY LIGHT", Screenshot_20260621-155950): DXVK base HUD
  **29.9 fps** → Banner overlay **60 fps** = 2.00×. Adreno 750, GPU 58%.
- **FPS-limiter pacing confirmed** (Screenshot_20260621-160008): in-game FPS Limiter "Limit FPS" ON,
  Max FPS = 30 → overlay ≈ **60–63 fps** (cap × mult). The relaxed clamp + new variance-aware
  pacing path work.
- **New container:** ran on **Proton 11.0-5-arm64ec** + Mesa Turnip v26.2.0 + zink (prior proofs
  were on older containers). Layer loads clean: `VK_LAYER_BIONIC_framegen Device created` →
  `SwapchainState provisioned 1280x720` → `FramegenContext ready mult=2 model=0
  graph=model0-full-of-chain`; config hot-reload flow 0.90→1.00 live.
- **CPU-temp HUD fix holds** (79.7 / 86.6 °C real values).
- **No app crash.** No FATAL/SIGSEGV/tombstone for `com.winlator.banner` or `libbionic_fg`. Display
  went `committedState OFF` at 15:59:41 (backgrounded); the ANR storm after 16:01 is
  `com.qti.diagservices` = AYANEO firmware nvkeeper/diag bug, unrelated. The Claude session is what
  died (known device-launch issue).
- **Benign noise (cleanup candidate):** `failed to load layer libVkLayer_LSFGVK_frame_generation.so:
  libc++_shared.so not found` — that's the *old* LSFGVK-named layer in the APK `lib/arm64`, not our
  layer. Our `VK_LAYER_BIONIC_framegen` (imagefs/usr/lib) loads fine. Strip-or-bundle-libc++ before
  the PR push.

Log `/sdcard/Download/bionicfg_pr_followups_test.txt`; `.so` staged `/sdcard/Download/libbionic_fg_4b99b2d1.so`.

**NEXT (now unblocked):** post the 3 drafted replies to Jason (1093/1334/manifest:17) → push fork
`5f4fc03` to PR #6 branch `bannerlator-android-wrapper-icd-fixes` (one clean batch) → build APK from
`feature/bionic-fg-pr-followups` for the app-side niceties (next reinstall). GN #1443 verify deferred.

---

## 2026-06-20 — 1.3 shipped public + upstream PR review cycle (in progress)

**1.3 released (public).** Repo flipped public (secret-scanned first), `Bannerlator 1.3` release
created (run 27878418873) with all 3 flavors; release notes credit xXJSONDeruloXx with links to
[bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) and [PR #6](https://github.com/xXJSONDeruloXx/bionic-fg/pull/6).
README updated to 1.3 (fixed standard package id `com.winlator.banner`, version line, added the
Frame Generation feature section).

**Upstream PR #6 opened and reviewed.** Fork `The412Banner/bionic-fg` branch
`bannerlator-android-wrapper-icd-fixes`. Jason (xXJSONDeruloXx) left 9 inline review comments;
all 9 answered. He's constructive and wants it in with refinements. Work plan (layer fixes land on
the fork branch → update the PR; app-side conf-key changes land on a new Bannerlator branch
`feature/bionic-fg-pr-followups`):

- **A — separate copy vs generated fence-timeout counters.** Real bug he found: the shared counter
  reset on copy-fence success, so generated-frame timeouts could never reach the disable threshold.
  **Done** (fork commit `4c259f8`): split into `copyFenceTimeouts` / `genFenceTimeouts`, each reset
  only on its own fence type.
- **B — timeout rework + split the generated-frame deadline from the sync-incompatibility recovery
  path.** **Done** (fork commit `345e35e`): frame-budget-scaled sync timeout (4× base interval,
  clamped 200 ms–1 s, else 500 ms), disable threshold 2→6, and a separate shorter generated-frame
  cadence deadline (~2× the output interval) that just skips a late frame without counting it as a
  sync failure. A+B compile-validated via CI.
- **C — fps_limit ergonomics:** justify/relax the 10–200 clamp + add an `fps_limit_enabled` bool so
  the value is remembered across toggles. (layer + app) — *todo*
- **D — optional even-pacing** of generated presents to `1s/(base×mult)` behind an opt-in flag,
  off by default. (layer + app) — *todo*
- **E — document** ENABLE+DISABLE precedence in the manifest (disable wins). Trivial. — *todo*

**Device-creation regression (his main concern) — direction changed after his reply.** The JNI/native
path is unchanged (`Device::create()`, owned + destroyed); only the Vulkan-layer path uses
`Device::wrap()` of the app's device. Jason confirmed **GameNative also uses the layer path**, so our
change reaches it too. Arch root cause: standalone mode runs a second VkDevice and hands frames across
it via `VK_QUEUE_FAMILY_EXTERNAL` transfers, which need a shared queue/timeline; a wrapper ICD bridges
each guest device to Turnip as a separate host context, so that cross-device sync never completes →
hang. Single-device avoids it by running interpolation on the app's own device (intra-device sync).

**Plan:** likely **no flag** — single-device is probably the correct layer default for both stacks.
**Critical next step: verify single-device on GameNative [PR #1443](https://github.com/utkarshdalal/GameNative/pull/1443).**
If it's clean there, make single-device the layer default; only add an init-time `single_device`
launch arg (it can't hot-reload — device is created at init) if GN regresses. Fork commits A–B are
held locally and not yet pushed to PR #6; push as one batch after the GN question is settled and our
stack is re-tested.

---

## 2026-06-20 — bionic-fg FRAME GEN + FPS LIMITER: merged to main, version → 1.3

**FPS-limiter pacing device-confirmed (Phase 4 complete).** The deferred pacer (built into
the bionic-fg Vulkan layer, `.so` md5 `c8e4b188`) ran on device with `fps_limit=30`: base
DXVK frames locked at ~30 while the on-screen overlay stepped 60 → 90 → 122 as the in-game
FG selector went 2× → 3× → 4× (i.e. on-screen = limit × multiplier). Proven on the OpenGL
host renderer (log `bionicfg_fpslimit_test.txt` + 5 screenshots). The pacer sits at the top
of `BionicFG_QueuePresentKHR`, gated `!st.inPresent && fpsLimit>0`, so it throttles only the
app's real frames; generated frames (presented with `inPresent` true) bypass it. Verified the
pacer `.so` is bundled in the shipped APK asset (`assets/bionic-fg/libbionic_fg.so` md5
`c8e4b188`), not hand-staged.

**Merged to main (`ddf46fb`).** Merged `feature/bionic-fg-framegen` (HEAD `f39b96a`) into main.
One conflict in `.github/workflows/build-bionic-fg.yml` (both branches had it) resolved by
keeping the feature branch's version (the one with the patch-apply step). Stale
`BIONIC_FG_UPSTREAM_REPORT.md` working-tree edit reverted (falsely said single-device crashes;
run6 disproved it). Feature branch kept until the upstream PR is cut.

**CI: artifacts build now produces all 3 flavors (`eb30d1b`).** Previous standard-only build was
a workaround for an OOM (exit 143) caused by packaging the ~588MB APKs in parallel.
`build-artifacts.yml` now runs `assembleStandardDebug assembleLudashiDebug assemblePubgDebug
--no-parallel --max-workers=1` with a larger Gradle heap (serialized packaging) and requires
all 3 uploads. Run 27877129792 confirmed green with all 3 artifacts.

**Version relabel → 1.3 (`9ee5cb2`, `90ce00b`).** Fresh-install Android "All files access"
permission screen showed `1.4-marcescene` (the APK `versionName`) under "Bannerlator Bionic".
Fixed: `app/build.gradle` `versionName "1.4-marcescene"→"1.3"`, `versionCode 20→21`; splash
`SplashScreen.kt` "V 1.2"→"V 1.3" (color unchanged — stays grey `0xFFAAAAAA`); about/main
`MainActivity.kt` stray "V 1.0"→"V 1.3". Build run 27877738210 label `1.3` dispatched on main;
standard APK to be delivered to `/sdcard/Download/Bannerlator-1.3-standard.apk`.

---

## 2026-06-19 — Vulkan/DXVK/vkd3d BLACK-SCREEN FIX (✅ both renderers device-confirmed)

**Symptom:** native Vulkan + DXVK(d3d8-11) + vkd3d(d3d12) rendered BLACK at full FPS on BOTH
host renderers; OpenGL/DirectDraw/D3D7 fine.

**Root cause:** marcescence shipped the native scanout machinery but left the AHB (DRI3 modifier
1255) present path UNWIRED, and the GL renderer had NO AHB->GL (EGLImage) sampling at all
(GPUImage textureId==0 -> black). Confirmed via device logcat (tag "Dri3": modifier 1255 ->
AHB path taken; pixmaps imported fine -> not an import failure).

**Fix (commit `7d5c9f8`, build label "vkfix3" run 27848179202):** ported proven wiring from
GameNative (utkarshdalal/GameNative, local ~/GameNative):
- `renderer/GPUImage.java` + `cpp/winlator/gpu_image.c`: GPUImage(int socketFd) now locks
  (valid getStride + virtualData) and gained EGLImage support (createImageKHR =
  eglGetNativeClientBufferANDROID + eglCreateImageKHR + glEGLImageTargetTexture2DOES). AHB
  allocated BGRA_8888 (matches X depth-32 / GL_BGRA -> correct colors). unlock-before-release.
- `xserver/extensions/DRI3Extension.java`: setDirectScanout(true) + getStride() width.
- `xserver/extensions/PresentExtension.java`: 3-branch present (Vulkan native+scanout=FLIP /
  Vulkan=COPY via onUpdateWindowContentDirect / GL+SHM=copyArea); relaxed depth 24<->32.
- `XServerDisplayActivity.setupUI` + `VulkanRenderer.setInitialNativeMode`: wired the
  previously-dead Vulkan toggles (native / presentMode / filterMode / swapRB).

**Device results (vkfix3):** ✅ OpenGL host renderer (native Vulkan 1432fps + D3D12/vkd3d
1748fps, correct colors). ✅ Vulkan host renderer (native Vulkan 1449fps, correct colors).

APKs delivered: `/sdcard/Download/Bannerlator-vkfix3-standard.apk` (md5 eebfe339…),
`-pubg.apk` (md5 a7c0acb3…).

---

## 2026-06-19 (PM) — Native Rendering toggle: device-tested + HUD-freeze fix

User tested the previously-untested Native Rendering+ toggle on the Vulkan host renderer
(AIO Graphics Test, native-Vulkan cube). Two findings:

**1. Windowed content stretches/distorts — EXPECTED, not a bug.** With the graphics test in a
*window* (sub-screen), enabling Native Rendering blits the active swapchain straight to the full
device surface, stretched (LUNARG cube visibly squished). Direct scanout (`onUpdateWindowContent`
FLIP branch → `nativeScanoutSetBuffer`) has no aspect-correct dst path for sub-screen windows;
the aspect-preserving letterbox (`ViewTransformation`, `Math.min`-based) only applies on the
copyArea path. ✅ With the test app **maximized to fullscreen**, native rendering renders the cube
correctly proportioned (FPS still climbs 582→743→…). So for real fullscreen games — the actual use
case — native rendering is correct. Windowed-distortion is a known limitation, not release-blocking.

**2. Perf HUD freezes in Native Rendering — FIXED (commit `f724ec2`).** When Native Rendering was
on, the horizontal perf HUD bar (Vulkan|DXVK|CPU|GPU|…|FPS) froze — values stopped updating while
the game kept animating. Root cause: commit `779967a` wired `hudFrameTick` (which drives
`frameRatingHorizontal.update()`, `XServerDisplayActivity.java:1345`) only into
`onUpdateWindowContentDirect` (the COPY present path). Native rendering uses the FLIP/scanout path
(`PresentExtension.java:154` → `VulkanRenderer.onUpdateWindowContent`), which never called it. Fix:
added `if (hudFrameTick != null) hudFrameTick.accept(window.id);` in the scanout-delivered branch
of `onUpdateWindowContent` (`VulkanRenderer.java:495`), mirroring the COPY path — ticks once per
presented game frame in native mode.

**Build:** `build-artifacts.yml` run `27852720105` (artifacts-only, no release, APK label `hudfix`),
triggered off `main` @ `f724ec2`. ⏳ standard APK to be dropped in `/sdcard/Download/` when green;
HUD fix in native mode still ⏳ device-unconfirmed.

**Next:** device-confirm HUD ticks (and shows a sane FPS — native mode pauses X-side rendering) →
then cut a tagged release (pick a real version; vkfix3/hudfix are just build labels). Cleanup:
graphicsDriverConfig has two competing dialog formats writing the same field.

---

## 2026-06-19 (PM) — New neon gamepad launcher icon (corner-clip fix)

User supplied a new icon (neon gamepad + magenta chevron + white L-bracket + corner stars on
black, white rounded border) — `/storage/emulated/0/Download/ADM/file_…588.jpg`, 1254×1254 — and
reported the previously-installed icon had its **border corners clipped** by the launcher's
round/squircle mask (device screenshot 20260619-194013, drawer): that old icon was **full-bleed**
(art edge-to-edge) so adaptive masks cut the corners.

**Done (commit `19d62f8`, all 15 files = 5 densities × ic_launcher + ic_launcher_round + adaptive
foreground):**
- Legacy `ic_launcher.png` / `ic_launcher_round.png` (mdpi 48 … xxxhdpi 192) = full image, exact.
- Adaptive foreground (mdpi 108 … xxxhdpi 432) = full art fit into the **safe zone** (~66% of
  canvas, centered, transparent pad) so the launcher mask only ever trims the black margin — the
  white border + corner stars stay fully visible under ANY mask shape. Generated with ImageMagick.
- Adaptive background was already `@color/ic_launcher_background` = `#000000` (matches art bg) → no
  change needed; seamless (image black bg blends into adaptive black).
- No per-flavor icon overrides → shared `main/res` applies to all 3 flavors (standard/ludashi/pubg).
- User explicitly chose "full white border visible" over a bigger near-full-bleed (88%) variant.

**Build:** `build-artifacts.yml` run `27853329322` (artifacts-only, label `neonicon`, off `main` @
`19d62f8`). ✅ standard APK delivered `/sdcard/Download/Bannerlator-neonicon-standard.apk` (md5
`13056a0e2845f56ca34b00405abd3afb`). ⏳ icon device-unconfirmed (note: Android caches launcher icons
— reboot / clear launcher cache if old clipped icon persists).

---

## 2026-06-19 (PM) — 🏁 RELEASE 1.2 + README features/download button

**Released Bannerlator 1.2** (`release.yml`, run `27853787348`): tag `1.2`, marked **Latest**,
non-prerelease, 3 flavor APKs attached. Standard APK → `/sdcard/Download/Bannerlator-1.2-standard.apk`
(md5 `e5d5689ecf4b9b1a91596d70658a752f`). `release.yml` inputs = `release_tag` / `release_title` /
`release_number` / `release_notes` (publishes `make_latest:true`, supersedes 1.1).

1.2 changelog (commits since `1.1` tag): Vulkan/DXVK/vkd3d black-screen fix (`7d5c9f8` + lead-ups
`b7d4f3a`/`c4d252c`), Native Rendering+ toggle wired (`779967a`), HUD-freeze fix on FLIP path
(`f724ec2`), GL-only effects greyed out on Vulkan (`ba06bc3`/`df3a5c7`), DXVK/VKD3D/Vegas version-list
refresh (`00a2544`), new neon icon (`19d62f8`).

**Splash version** bumped `V 1.1` → `V 1.2` (`SplashScreen.kt:164`, commit `a598584`) BEFORE the
release so it shipped in 1.2.

**README** (commits `ae5d9b7` + `18eab3d`): added **✨ Full Features** section (7 grouped categories —
Windows compat / graphics layers / renderers / containers / games+input / UI+overlay / builds; every
item cross-checked against actual code, NO invented features like AI frame-gen which isn't in this
app); bumped Information-table version V 1.0→V 1.2; added a centered shields.io **Download button**
linking to `/releases/latest` + a "Download" entry in the nav row.

**GameNative render-fix credit** (commit `8792f6d`): expanded the README GameNative credit row to
state its rendering pipeline was the **reference used to fix/rewire the render options** (AHB present
path → Vulkan/DXVK/VKD3D on both renderers: GPUImage socket-buffer lock + EGLImage sampling, DRI3
direct-scanout, Present FLIP/COPY branches, Native Rendering+ scanout). Also appended a **Credits**
section to the **1.2 GitHub release notes** (`gh release edit 1.2`) crediting GameNative (utkarshdalal)
for the same, linking back to the README Credits.

**Next:** device-confirm HUD-tick + new icon on 1.2; cleanup graphicsDriverConfig's 2 competing
dialog formats.

---

## 2026-06-19 (PM) — bionic-fg frame generation: recon + branch `feature/bionic-fg-framegen`

New feature kicked off: integrate [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) (Android/
bionic Vulkan frame-generation layer, LSFG lineage — same engine GameHub ships as `libGameScopeVK.so`)
as **(a)** a per-container option and **(b)** a live in-game side-menu control.

**Author permission GRANTED** (xXJSONDeruloXx). Terms: (1) credit in README, (2) if source goes in
tree do it as a **git submodule** (his preference), (3) feedback/PRs welcome.

**Recon findings:**
- Guest Vulkan goes through a **wrapper ICD** (`wrapper_icd.aarch64.json` + `GALLIUM_DRIVER=zink` +
  `WRAPPER_*` at `XServerDisplayActivity.java:1823–1861`) bridging to the **Android bionic GPU driver**
  via **adrenotools** — exactly the context bionic-fg targets.
- Tree already has frame-gen groundwork: `app/src/main/cpp/lsfg-vk/` (stub CMakeLists, build excluded)
  + root `build-lsfg-android.sh`. bionic-fg = the bionic-targeted sibling.
- **All 3 CI workflows already use `submodules: recursive`** → adding the submodule needs NO CI change.
- In-game drawer (`XServerDrawerState.kt`) uses StateFlow+Runnable; Native Rendering toggle is a
  turnkey template for a Frame-Gen toggle. bionic-fg **hot-reloads its TOML** → in-game live control
  by rewriting the config (multiplier 0=off / 2–4× / model 0-1 / flow_scale).
- ⚠️ **Critical unknown:** does the wrapper expose a `VkSwapchainKHR` for the layer to hook, or does
  it AHB-export with no WSI swapchain? Resolve with a verification spike BEFORE building UI.

**Deliverable this session:** branch `feature/bionic-fg-framegen` created off `main`; full recon +
phased job task list written to **`BIONIC_FG_INTEGRATION_REPORT.md`** (Phase 0 honor-terms → 1 native
build → 2 spike/de-risk → 3 container setting → 4 in-game menu → 5 polish/release/give-back).

**Next on branch:** Phase 0 — add bionic-fg as a submodule under `app/src/main/cpp/bionic-fg` +
README credit; then the Phase 2 verification spike (gate the rest on it).

### ✅ Phase 0 DONE (2026-06-19) — author terms honored
- Added **bionic-fg as a git submodule** at `app/src/main/cpp/bionic-fg` (his preference), pinned at
  `4f71770`; new root `.gitmodules`. CI needs no change (all 3 workflows already `submodules: recursive`).
- **README credit**: added xXJSONDeruloXx / bionic-fg to the Credits table (frame-generation layer,
  in-tree as a submodule with permission) + a "Frame Generation (bionic-fg)" row in the upstream-stack
  table.
- ⚠️ Submodule has **no LICENSE** → carry to Phase 5.2 (ask author before bundling in a release).
- **Next:** Phase 2 verification spike — build `libbionic_fg.so`, hand-wire one container's env +
  `conf.toml`, launch a DXVK game, confirm via logcat whether the layer engages (wrapper exposes a
  `VkSwapchainKHR`?) BEFORE any UI work.

### ✅ Phase 1 DONE (2026-06-19) — native build
- `build-bionic-fg.yml` (standalone, NDK 26.1.10909125 + cmake 3.22.1, arm64-v8a/android-26). Run
  **27854824786 ✅** → artifact `bionic-fg-arm64` (1.65 MB) = `libbionic_fg.so` (ELF aarch64,
  Android 26, NDK r26b — matches our minSdk 26) + `VkLayer_BIONIC_framegen.json`. Workflow also added
  to `main` (dispatch-only/inert) since workflow_dispatch requires the file on the default branch.
- **Manifest insights (sharpen the spike):** layer is **IMPLICIT** (`enable_environment
  BIONIC_FG_ENABLE=1`); `library_path ../../../lib/libbionic_fg.so` → manifest goes in
  `…/share/vulkan/implicit_layer.d/`, .so in sibling `lib/`. Implicit layers are found via system
  dirs / `VK_ADD_IMPLICIT_LAYER_PATH` (NOT `VK_LAYER_PATH`, which is explicit-only). Hooks
  vkGetInstance/DeviceProcAddr → sits above the ICD.
- **Refined crux:** bionic `.so` CANNOT load in the glibc guest (box64/Wine) → must load **host-side**
  where the wrapper-ICD server runs Turnip via adrenotools. Spike must confirm (1) host loader honors
  the implicit layer, (2) a real `VkSwapchainKHR` exists to hook (vs AHB-export = nothing to
  intercept). Copy GameHub `libGameScopeVK` imagefs placement.
- Artifact staged for device spike: `/sdcard/Download/bionic-fg/{libbionic_fg.so,VkLayer_BIONIC_framegen.json}`.
- **Next:** Phase 2 spike (needs a device launch — log to crash-surviving `/sdcard/Download/*.txt`
  per the device-launch rule; hold the actual launch for the user).

### Phase 2 spike — runbook written + device recon (2026-06-19)
- **`BIONIC_FG_SPIKE_RUNBOOK.md`** written: full device steps (place `.so` in `imagefs/usr/lib`,
  manifest in `implicit_layer.d`, `BIONIC_FG_ENABLE=1` + `VK_LOADER_DEBUG=all` in container Env Vars,
  conf.toml at guest `$HOME/.config/bionic-fg/`, logcat→`/sdcard/Download/bionicfg_spike.txt`) +
  a decision table + cleanup.
- **Device recon (root bridge):** guest uses its **own glibc Khronos loader**
  `imagefs/usr/lib/libvulkan.so.1.4.315` and already loads **glibc** implicit layers — **MangoHud**
  (`VK_LAYER_MANGOHUD_overlay_aarch64`) + `libutil_layer` — from `usr/share/vulkan/implicit_layer.d/`.
  MangoHud's manifest is structurally identical to bionic-fg's (enable_environment, `../../../lib/…`,
  same proc-addr hooks) → **discovery works**.
- ⚠️ **KEY HYPOTHESIS:** our NDK/**bionic** `libbionic_fg.so` (links libandroid/liblog/Android
  libvulkan) **will not load in the glibc guest loader** → real path is a **glibc aarch64 build**
  (new Phase 1.5), mirroring how MangoHud + GameHub `libGameScopeVK` ship in imagefs. The spike's
  Test A is designed to confirm this fast (expect a `cannot open shared object`/`libandroid` load
  error), then pivot.
- Standard pkg confirmed `com.winlator.banner` (pubg `com.tencent.ig`); both installed.
- **Next (user):** run the spike launch per the runbook; report the log signals.

### Phase 2 spike ARMED on device (2026-06-19) — awaiting user launch
- Test workload = **DOOMBLADE** (user's choice; real DX11/DXVK game) in **container 2 "P10arm"**
  (`imagefs/home/xuser-2`, the ACTIVE container; arm64ec, DXVK 2.4.1+vkd3d, Turnip, FPS HUD on).
- Staged via root bridge: `libbionic_fg.so` → `imagefs/usr/lib/`, `VkLayer_BIONIC_framegen.json` →
  `imagefs/usr/share/vulkan/implicit_layer.d/`, `conf.toml` (multiplier=2) →
  `imagefs/home/xuser-2/.config/bionic-fg/`. All chown'd back to app uid `u0_a478`.
- Container 2 `.container` env vars **prepended** `BIONIC_FG_ENABLE=1 VK_LOADER_DEBUG=all`
  (backup at `.container.bak_bfg`).
- Logcat capture → `/sdcard/Download/bionicfg_spike.txt`.
- **REVERT if needed:** restore `imagefs/home/xuser-2/.container.bak_bfg`; rm the staged
  `.so`/manifest/`.config/bionic-fg`.
- ⚠️ Expectation: bionic `.so` likely fails to load in glibc guest loader (ABI) → then Phase 1.5
  glibc build. Spike confirms.

---

## How to Resume a Session

1. Read this file top to bottom
2. Find the **Current Job** section — it tells you exactly what to do next
3. Check the last commit hash matches what's on GitHub before continuing
4. Run CI after every commit. Do not continue to the next job until CI is green.

---

## Completed Work (Pre-Plan)

Full Jetpack Compose migration of all screens and dialogs is complete.  
See `COMPOSE_MIGRATION_REPORT.md` for the full record.

**Last migration commit:** `6dff28e`  
**Bug fixes after migration:**
- `85b1e57` — controller name text + drive letter dropdown fix
- `6537038` — External Controllers header text fix
- `3323810` — Customizable theme: 8 presets + HSV color picker (AppearanceScreen)
- `beee77b` — Appearance entry missing from nav drawer (AppDrawer hardcoded)

**Latest commit:** `beee77b`  
**Latest CI:** run `24568759383` — in progress at time of writing

---

## Feedback Fix Plan

Source: Developer feedback comparing v1.1 (old Java/XML) vs Compose version.  
8 issues identified. Listed in execution order (smallest/highest impact first).

---

### Job 1 — Help and Support (BROKEN)
**Status:** ✅ COMPLETE — commit `93d0326`, CI run `24569312463`  
**File:** `app/src/main/java/com/winlator/cmod/ui/AppDrawer.kt`  
**Problem:** `onClick = { /* TODO: open help URL or dialog */ }` — tapping does nothing  
**Fix:** Replace the TODO with a Compose `AlertDialog` containing:
- GitHub repo link: https://github.com/The412Banner/star-compose
- Issue tracker link
- A "Close" button
Or alternatively open a URL via `Intent(Intent.ACTION_VIEW, Uri.parse(url))`.  
**Effort:** 30 min  
**Commit message:** `fix: implement Help and Support dialog`

---

### Job 2 — About Dialog (MISSING CONTENT)
**Status:** ✅ COMPLETE — commit `d18cae6`, CI run `24569669122`  
**File:** `app/src/main/java/com/winlator/cmod/MainActivity.kt` — `AboutDialog()` at bottom of file  
**Problem:** Current dialog is 4 lines of plain text. Missing: app icon/logo, version name, Wine/Box64/FEX versions, credits list.  
**Fix:** Rebuild `AboutDialog()` as a proper Compose `Dialog` (not AlertDialog — needs more space) with:
- App icon (R.mipmap.ic_launcher_foreground)
- App name + version (read from `BuildConfig.VERSION_NAME` + `BuildConfig.VERSION_CODE`)
- Powered-by section: Wine, Box64, FEX-Emu, Turnip
- Credits section with contributor names
- Close button  
**Effort:** 45 min  
**Commit message:** `feat: rebuild About dialog with logo, version, credits`

---

### Job 3 — Container Creation Loading Indicator
**Status:** ✅ COMPLETE — commit `2e5f4a1`, CI run `24570142005`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainerDetailScreen.kt` — Save button / confirm action
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainerDetailViewModel.kt` — `saveContainer()` or equivalent
**Problem:** When user taps Save on a new container, it creates silently with no progress feedback. On slow devices this looks like a freeze.  
**Fix:**
1. Add `isCreating: StateFlow<Boolean>` to `ContainerDetailViewModel`
2. Set it true before container creation starts, false when done
3. In `ContainerDetailScreen`, show a full-screen semi-transparent overlay with `CircularProgressIndicator` + "Creating container…" text when `isCreating == true`
4. Disable the Save button while creating  
**Effort:** 45 min  
**Commit message:** `feat: add loading overlay during container creation`

---

### Job 4 — Settings Theme Mismatch (Dark Mode Toggle Broken)
**Status:** ✅ COMPLETE — commit `44a4bdb`, CI run `24571445525`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/theme/AppThemeState.kt`
- `app/src/main/java/com/winlator/cmod/ui/theme/ThemePreset.kt`
- `app/src/main/java/com/winlator/cmod/ui/theme/Theme.kt`
- `app/src/main/java/com/winlator/cmod/MainActivity.kt`
**Problem (two parts):**
1. `SettingsFragment` uses Light XML AppTheme while the rest of the app is dark Compose — mismatched look inside the Settings screen
2. The `dark_mode` SharedPreferences toggle in SettingsFragment has no effect on the Compose UI — `WinlatorTheme` always uses `darkColorScheme()`  
**Fix:**
1. Read `PreferenceManager.getDefaultSharedPreferences(this).getBoolean("dark_mode", false)` in `AppThemeState.init()` and store it as `isDarkMode: StateFlow<Boolean>`
2. Add a light variant to each `ThemePreset` (or use Material3 `lightColorScheme()` as the light base)
3. `AppThemeState.colorScheme` flow emits light or dark scheme based on `isDarkMode`
4. Register a `SharedPreferences.OnSharedPreferenceChangeListener` so toggling dark mode in Settings updates the flow in real time without restart
5. For SettingsFragment XML mismatch: set `android:theme="@style/Theme.AppCompat.DayNight"` on the fragment's parent or override the fragment background to match Compose surface color  
**Effort:** 1.5 hours  
**Commit message:** `fix: wire dark_mode preference to Compose theme + fix Settings appearance`

---

### Job 5 — Sort Shortcut List
**Status:** ✅ COMPLETE — commit `00dc6a5`, CI run `24571836336`  
**File:** `app/src/main/java/com/winlator/cmod/ui/screens/ShortcutsScreen.kt`  
**Problem:** No sort option — shortcuts always appear in filesystem order  
**Fix:**
1. Add a sort icon button in the top bar or a sort dropdown in the shortcuts screen
2. Sort options: Name A→Z, Name Z→A, Last Played, Container
3. Store selected sort in `ShortcutsViewModel` (persisted to SharedPreferences)
4. Apply sort to the `shortcuts` StateFlow before emitting  
**Effort:** 1 hour  
**Commit message:** `feat: add sort options to shortcuts list`

---

### Job 6 — Import/Export Container
**Status:** ✅ COMPLETE — commit `8477b65`, CI run `24572308670`  
**Files:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainersScreen.kt`
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainersViewModel.kt`
**Problem:** The old `ContainersFragment` had import/export container options. These are missing from the Compose version.  
**Fix:**
1. Add "Import Container" and "Export Container" options to the container long-press context menu (already has Duplicate/Delete)
2. Check original `ContainersFragment.java` (deleted) — refer to git history if needed, or find the logic in `ContainerManager.java`
3. Export: zip the container directory → write to Downloads or user-picked location via `ActivityResultContracts.CreateDocument`
4. Import: user picks a zip via `ActivityResultContracts.GetContent` → unzip to containers directory → reload list  
**Check ContainerManager.java for existing import/export methods first** — they likely already exist.  
**Effort:** 1.5 hours  
**Commit message:** `feat: add import/export container to containers screen`

---

### Job 7 — Add Shortcut from External Storage
**Status:** ✅ COMPLETE — commit `546d25e`, CI run `24577265773`  
**Files:** `ShortcutsViewModel.kt`, `ShortcutsScreen.kt`

---

### Job 8 — Shortcut List Layout Toggle (Grid / List)
**Status:** ✅ COMPLETE — commit `546d25e`, CI run `24577265773`  
**Files:** `ShortcutsViewModel.kt`, `ShortcutsScreen.kt`

---

## Execution Order

```
Job 1 → Job 2 → Job 3 → Job 4 → Job 5 → Job 6 → Job 7 → Job 8
```

Each job: implement → commit → push both remotes → trigger CI → wait for green → update this log → proceed.

---

## Build Log

| Job | Commit | CI Run | Result | Date |
|---|---|---|---|---|
| Pre-plan: Appearance drawer fix | `beee77b` | `24568759383` | ✅ green | 2026-04-17 |
| Job 1: Help and Support dialog | `93d0326` | `24569312463` | ✅ green | 2026-04-17 |
| Job 2: About dialog rebuild | `d18cae6` | `24569669122` | ✅ green | 2026-04-17 |
| Job 3: Container creation loading overlay | `2e5f4a1` | `24570142005` | ✅ green (fix: `67844d2`) | 2026-04-17 |
| Job 4: Dark mode pref + Settings theme fix | `44a4bdb` | `24571445525` | ✅ green | 2026-04-17 |
| Job 5: Sort shortcuts list | `00dc6a5` | `24571836336` | ✅ green | 2026-04-17 |
| Job 6: Import/Export container | `8477b65` | `24572308670` | ✅ green | 2026-04-17 |
| Job 7+8: Import shortcut + grid/list toggle | `546d25e` | `24577265773` | ✅ green | 2026-04-17 |

---

## Current Job

**→ ALL 8 JOBS COMPLETE** ✅

Last commit: `546d25e`  
Last CI: `24577265773` ✅ green
