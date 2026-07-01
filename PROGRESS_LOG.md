# Star-Compose — Progress Log

---

## 2026-06-30 — In-game ReShade effect SWITCHING — Tier 1 (multi-effect loadout + per-effect enable gate) — 🏁 FULLY DEVICE-PROVEN 2026-07-01, NOT merged

> 🏁 **STATUS 2026-07-01 — TIER 1 DONE, DEVICE-PROVEN END-TO-END (ludashi).** Gates 1–8 device-verified earlier; then two device-found bugs were fixed and both confirmed on-device on fix build **CI `28492221848`** (branch `feat/reshade-multi-effect-switch` tip **`82c6799`**, headSha `82c67995…` == branch tip, +82/−16 across 3 files, `extra_libs.tzst`/patched .so untouched = app-side only):
> - **BUG A — per-game persistence (commit `801fee9`): FIXED + user-confirmed.** In-game changes to loadout/order, Solo/Stack, per-effect enabled, sliders, and master on/off now persist to the game's **shortcut** and restore on cold relaunch. Root cause = persist path and read path used different owner discriminators (`applyReshadeLive` wrote to the shortcut whenever non-null, but `resolveReshade` reads the shortcut only when it *owns* reshade, else the container) → container-configured reshade launched via a shortcut reverted; master on/off was never persisted at all. Fix = one shared `shortcutOwnsReshade()` (write-target == read-source) + persisted `reshadeMasterEnabled`. Disk-flush verified: each in-game change-commit synchronously rewrites the shortcut `.desktop` (`Shortcut.saveData()` → `FileUtils.writeString`, `XServerDisplayActivity.java:1528`; container branch `container.saveData()` `:1534`), sliders debounced to release — so quitting/killing the game cannot lose changes.
> - **BUG B — pause box on the freeze (commit `82c6799`): FIXED + user-confirmed ("pause and resume now shows up and works correctly").** Root cause = z-order: `PauseBoxOverlay` was an inline `Box` inside the dialog-host ComposeView, which the game SurfaceView (Vulkan/GL + ASR scanout) composites above → hidden on the frozen frame. Fix = host the pill in `androidx.compose.ui.window.Dialog` (own top-level window above the game surface), no dim scrim, non-modal so the drawer stays usable during a Live-preview-OFF freeze; tap → resume.
> ▶️ **REMAINING (all user-gated / non-functional):** (1) MERGE `feat/reshade-multi-effect-switch` → main; (2) version-aware-extraction fix (re-extract `extra_libs.tzst` when the bundled .so hash differs, so existing containers get the patched layer — needed before release); (3) codegen sweep to prune non-compiling catalog effects; (4) release notes (credits DadSchoorse / Pipetto / StevenMXZ); (5) Tier 2 (live add-from-catalog via on-device recompile). No merge/tag/version-bump without explicit user go.

**Goal (user):** select MULTIPLE ReShade effects per game and **toggle between them LIVE in-game** — Solo (A/B one at a time) or Stack (layered) — with auto-generated per-effect sliders. Built on top of the merged Step 3 stack. Baseline was single-effect only (`Container.getReshadeEffect()` one string, conf `effects=<reshade>:cas`). Two tiers agreed: **Tier 1 = pre-compiled loadout with an instant per-effect enable gate (this entry)**; Tier 2 = live "add from catalog" via on-device recompile (later).

- **LOCKED conf-key contract** (app emits / patch reads, exact): `effects = e1:e2:…:en:cas`; per effect `<ei> = <fxPath>`; uniforms `<ei>_<uniform>[_c] = value` (unchanged `formatUniformLine`); **NEW `<ei>_enabled = 0|1`** (default 1 = active); global master stays `enableOnLaunch`. effectKey = `name→[^A-Za-z0-9_]→_` lowercased.
- **Native patch** — branch `feat/reshade-mes-patch` (`83930e2`), vkBasalt CI `28488420505` ✅GREEN. Extended `patches/vkbasalt-reshade-livereload.patch` (+219/−12): each `ReshadeEffect` reads `<name>_enabled` on construct + on the existing present-hook mtime reload; a disabled effect does an **identity image-copy passthrough** (reuses the proven `TransferEffect` barrier/`vkCmdCopyImage`/layout path — input `PRESENT_SRC→TRANSFER_SRC`, output `UNDEFINED→TRANSFER_DST`, copy, both back to `PRESENT_SRC`) so the ping-pong chain stays valid and downstream/present see the pre-effect frame. Gate flip → `QueueWaitIdle` + re-record `commandBuffersEffect` (one-frame hitch **only** on toggle, never per-frame). Base `Effect::enabled=true` so CAS/builtins are never gated; global `presentEffect`/`enableOnLaunch` orthogonal. Stripped patched `libvkbasalt.so` md5 `3129127c098dcaa7704cf264ef47f157` (1852976 B).
- **App loadout** — branch `feat/reshade-multi-effect-switch`, app work commit `fc3ce45`. android-app-engineer added: `reshade/ReshadeLoadout.java` (parse/serialize + **migration** old single-effect → 1-entry Solo loadout, `paramsForEffect`, `enforceSolo`), `ui/ReshadeLoadoutItem.kt`, `ui/screens/ReshadeLoadoutEditor.kt` (`ReshadeLoadoutState` + editor: mode switch, high-count hint, per-effect typed controls); `Container.java` `reshadeLoadout`/`reshadeMode` (legacy getters kept); `XServerDisplayActivity` `writeVkBasaltConfig` rewritten to iterate the loadout (emits the contract incl. `<ei>_enabled` + colon-joined texture/include paths; live apply skips folder re-staging), `resolveReshade()` = shortcut owns reshade as a **unit** (loadout+mode+params) else container; `XServerDialogState.kt` new `ReshadeApplyCallback(masterEnabled, mode, items)`; `XServerDrawer.kt` ReShade tab = master toggle + Solo/Stack + per-effect radio(solo)/checkbox(stack) + collapsible typed controls + per-effect Reset; `ReshadeCatalogPicker.kt` now **multi-select** (download-on-demand preserved); `ContainerDetail*`/`ShortcutsScreen` wired. Reorder not implemented (order = selection order).
- ⚠️ **Process catch — a FALSE CI PASS was corrected.** The app agent's first "CI green `28489017067`" actually built headSha `9f51ed7` (branch **base**, no Tier-1 code): it committed `fc3ce45` to a stray **local `main`** and never pushed to the feature branch, and `main.yml` ("Any branch compilation") is **workflow_dispatch-only**, building whatever the ref tip is at dispatch. Fixed: moved `feat/reshade-multi-effect-switch` → `fc3ce45` (clean; its parent IS the base), deleted the stray `main`. **Rule reaffirmed: verify the CI run's `headSha` == the intended commit, and push to the ref BEFORE `gh workflow run main.yml --ref <branch>`.**
- **Integration + REAL CI green:** repacked the patched `.so` into `app/src/main/assets/graphics_driver/extra_libs.tzst` (14 entries preserved, `vkBasalt.json` manifest unchanged) + copied the updated patch → commit **`cd1187e`** (pushed); dispatched `main.yml --ref feat/reshade-multi-effect-switch` → run **`28489656349` ✅GREEN** (`build: success`, headSha `cd1187e` verified). So the loadout + enable-gate `.so` genuinely compile in CI (not the earlier wrong-SHA run).
- **✅ Pause/preview UX DONE + INTEGRATED** — branch `feat/reshade-pause-pulse` (`c5cc755`, off `cd1187e`), **CI `28490017156` ✅GREEN (headSha c5cc755 verified, all 3 flavors `assembleRelease`)**. Because it branched off `cd1187e`, that one green build = the ENTIRE Tier 1 stack (loadout + integrated enable-gate `.so` + pause). Cherry-picked onto the feature branch → tip **`ab70dca`** (`git diff c5cc755 HEAD` = only PROGRESS_LOG differs). Impl: `PresentExtension` `PresentListener` (one fire per real present); `pulseReshadePreview()` = register listener + SIGCONT → on the 2nd present (`RESHADE_PULSE_TARGET_PRESENTS=2`) SIGSTOP, `postDelayed` 80ms (`RESHADE_PULSE_FALLBACK_MS`) fallback if the game isn't presenting, `AtomicBoolean`/`reshadePulseInProgress` guard so refreeze fires once and serializes; `setPausedState(boolean)` = single source of truth (SIGSTOP/SIGCONT + mirrors drawer `setIsPaused` + dialog `setPaused`, clears `reshadePreviewPaused` on resume); `PauseBoxOverlay.kt` (centered pill, only the pill `clickable`) hosted in the existing full-size dialog-host ComposeView above the SurfaceView, tap→`onRequestResume`; `onResume`/`exit()` clear-and-resume so teardown can't hang on a suspended guest. "Live preview" toggle itself doesn't route through `apply()` so opening the tab never freezes. **3 flavor APKs downloaded from run 28490017156 → `/home/claude-user/scratchpad/reshade-tier1-apk/{standard,ludashi,pubg}-debug/` (standard = `com.winlator.banner`).**
- 🚨 **DEVICE-TEST SETUP GOTCHA + PRE-RELEASE FOLLOW-UP:** the layer re-extraction (`XServerDisplayActivity.java:2929-2937`) re-extracts `extra_libs.tzst` only on `firstTimeBoot` OR when `libvkbasalt.so` is **absent** — it does NOT replace an **out-of-date** .so. So containers that already have the OLD libvkbasalt.so keep it and silently ignore `<ei>_enabled` (per-effect toggle won't bypass; the multi-effect chain still compiles). ⇒ **DEVICE TEST ON A FRESH CONTAINER** (firstTimeBoot extracts the new md5 `3129127…` .so). **REQUIRED FIX before release** (affects all existing users): make extraction **version-aware** — re-extract when the bundled .so differs (store a version/hash marker or bump on appVersion change), else Tier 1 no-ops on every pre-existing container.
- **(orig plan) Pause/preview UX (co-designed with user):** a persisted **"Live preview"** toggle in the ReShade tab (default OFF). **OFF = freeze-frame + pulse** — enter preview-pause on the first committed change (effect toggle, or slider **release**) via SIGSTOP; each committed change SIGCONT → count **1–2 real presents** (via `PresentExtension`) → SIGSTOP, so the change renders and the native re-record hitch is hidden inside the pulse; a compact **center pause-box** overlay (tap = full resume) shown while frozen, also generalised to normal manual Pause; sliders pulse on release only (not per-tick). **ON = game keeps running** (continuous live slider preview + ~1-frame toggle blip, no box). Reuses `ProcessHelper.pause/resumeAllWineProcesses` (SIGSTOP/SIGCONT), `isPaused`, `onPauseResume`, `PresentExtension`.
  - *Why not "re-present one frozen frame forever" (user idea):* vkBasalt is a **passive** layer (runs only on the game's present); SIGSTOP freezes the presenting thread (may hold the `VkQueue` → external-sync/deadlock if we drive present from outside); and our host compositor re-blitting shows effects already **baked** into the handed-off buffer. A true frozen live-preview would need vkBasalt to **self-drive** a present loop on a cached pre-effect frame, or a **game-clock freeze** (intercept QPC/timeGetTime) — both heavy/fragile. The pulse is the safe ~95% (1–2 frames ≈ the same moment). Kept as a possible future enhancement if the pulse feels insufficient on-device.
- ▶️ **NEXT = DEVICE TEST** (the real gate; branch tip `ab70dca`, APK ready). On a **FRESH container** (see gotcha above), on a **Vulkan/DXVK** title: (1) pre-launch multi-select loadout in shortcut + container settings; (2) in-game ReShade tab lists the loadout, Solo=radio / Stack=checkbox toggle switches effects LIVE; (3) per-effect sliders live-tune; (4) Live-preview OFF → first change freezes + center pause-box, toggle/slider-release pulses 1–2 frames to reveal, tap box resumes; (5) Live-preview ON → game keeps running, continuous slider preview + ~1-frame toggle blip, no box; (6) master on/off; (7) migration: an existing single-effect profile loads as a 1-entry Solo loadout; (8) no teardown hang when exiting while frozen. Then: version-aware-extraction fix, merge decision, Tier 2 (live add-from-catalog + pause-assisted recompile). **No merge / no release / no version bump without user go.**

---

## 2026-06-30 — Release 2.2 description rewritten + README accuracy pass + UI-rewrite explainer

**Docs/release only — no code.** Rewrote the **2.2 GitHub release** body (was the auto-generated commit-table) into the established release layout (centered logo → title → bold summary → `✨ What's New` emoji sections → Downloads table → updating note → thanks), covering the real 2.2 scope: themeable interface (app + in-game drawer), 9 new presets (16 total, AMOLED still default), per-game on-screen control colours, File Manager Favorites, rebuilt controller-binding screen, in-game Task Manager (New Task on Vulkan/Native + cards), consistency/readability. Updating note states 2.2 is **app-side only — no ImageFS reinstall**.

- **README:** the bump commit `2ccfda8` had already rewritten it accurately; verified preset names against `ThemePreset.kt` (16 named + Custom) and Favorites labels against `FileManagerScreen.kt:describeLocation` (Internal / SD card / Drive C: / Drive Z:). One fix pushed (`6355d0e`): controls toggle is labelled **"Follow app theme"** (`XServerDrawer.kt:1611`), README said "Follow theme".
- **NEW: "Under the hood — the UI rewrite" section added to the 2.2 release** at user request — what kind of Compose, what's converted, Compose-vs-XML/Java proportion. **Measured facts (for future reference):**
  - **Stack** = Jetpack Compose + **Material 3**, Kotlin, **single-Activity** `MainActivity` + **Navigation-Compose** (`compose-bom:2024.02.00`, `navigation-compose:2.7.6`, `activity-compose:1.8.2`), **Hilt**. ~**237 `@Composable`** across ~**62 Compose files** / **91 .kt** files (~**32k** Kotlin LOC).
  - **Out-of-game app = 100% Compose, no XML screens left.** All drawer destinations are Compose routes (`Screen.kt`): Containers, ContainerDetail, Games/Shortcuts, Contents, InputControls, AdrenoTools, Saves, FileManager, Settings, Appearance, Splash. All 4 storefronts (GOG/Epic/Amazon/Steam — main/login/QR/games/detail) are Compose `setContent {}`. **2.2 converted the last out-of-game holdout: `ExternalControllerBindingsActivity` → Compose.**
  - **In-game stays classic Java by design** = `XServerDisplayActivity` inflates `xserver_display_activity.xml` to host the native `SurfaceView`/`GLSurfaceView` (X11 + Vulkan/GL renderer + Wine draw there — can't live in a Compose tree). 2.2 made the in-game **drawer + dialogs Compose islands** via `ComposeView` (`XServerDrawer.kt`, `XServerDialogHost.kt`).
  - **Proportion honesty:** Java is larger by LOC (~**297 .java / ~53k LOC**) but it's the **emulation engine**, not screens — `xserver/` 7.4k, `renderer/` 5.3k, `core/` 5.2k, `inputcontrols/` 3.6k, store backends, box64/fexcore/winhandler/container/xenvironment/xconnector/alsa/midi/sysvshm. Remaining **legacy XML/Java UI** = perf HUD (`frame_rating`/`hud_*`), `BigPictureActivity`, `ControlsEditorActivity` (control-element editor), file/folder pickers (`ShortcutPicker`/`FolderPicker`/`CustomFilePicker`), native over-surface dialogs (`ContentDialog`/`DownloadProgressDialog`/`TaskManagerDialog.java`). Some XML now **orphaned** (e.g. `shortcut_settings_dialog.xml` — no longer referenced). **75 layout XML / ~5.4k LOC, shrinking.**
- Release: <https://github.com/The412Banner/Bannerlator/releases/tag/2.2>

---

## 2026-06-30 — UI rebuild MERGED to main + 9 new theme presets (artifacts build)

**The umbrella hold is collapsed.** User decided the rebuild is feature-complete enough to merge and apply small fixes forward, so `feat/ui-rebuild` was merged into `main` and a fresh batch of opt-in themes added.

- **Merge:** `feat/ui-rebuild` → `main` = merge commit `35c8a28`. Only `PROGRESS_LOG.md` conflicted (docs); resolved as a union. Code tree verified identical to the umbrella tip (no code conflicts). Brings in: drawers rebuild (P1), theme centralization, drawer dialogs (P2), app-screen colour sweep (P3), on-screen controls + per-profile custom control colour (P4a), legacy-XML accent (P4b), TM-cards, External Controller Bindings → Compose, and File Manager Favorites.
- **9 new theme presets** = commit `5d75439`: Midnight Cobalt, Phosphor, Carbon & Ember, Amethyst, Crimson, Synthwave, Royal Gold, Frost, Monochrome. All opt-in; **AMOLED stays the default**. Inserted *before* "Custom" in `themePresets` so existing saved preset indices 0..6 are unaffected; `AppThemeState.init` adds a one-time `preset_schema_v2` migration that remaps anyone on the old Custom slot (index 7) to the new `CUSTOM_PRESET_INDEX`. `onPrimary` forced dark on light/bright accents (phosphor/gold/frost/mono) for legible on-accent text. AppearanceScreen renders them automatically (`themePresets.chunked(4)`).
- **Build:** pushed to `origin/main` after a brief github outage; artifacts-only build (workflow_dispatch `main.yml`) run `28470653013` in progress. **No release, no version bump** (hard rule).
- ▶️ **GATE before any release:** consolidated on-device test of the whole merged stack — favorites, bindings-Compose, TM-cards, P4b, custom-control-colour sub-items (per-game persist/relaunch, out-of-game editor, back-compat), and the 9 new presets (each recolours app + drawer; AMOLED default unchanged; on-accent text legible). Nothing post-P4a is device-proven yet.

---

## 2026-06-30 — File Manager FAVORITES — CODE DONE + CI ✅GREEN (preview signed off)

**Status:** implemented on `feat/ui-rebuild` — commits `bd57830` (feature) + `34247eb` (Back closes Favorites first) + `c06a397` (toasts on add/remove). CI `28467705193` ✅GREEN (pre-toast); toast commit CI `28469094380` ✅GREEN (tip `c06a397`). NOT merged (umbrella hold). Decisions: both pin entry points + global/absolute-path scope. ▶️ At device-test gate.

- `app/src/main/java/com/winlator/star/util/FavoritesStore.kt` (new) — SharedPreferences, ordered JSON array of absolute paths (`list/isFavorite/add/remove/toggle`). Stores only the path; the card label is derived live.
- `FileManagerScreen.kt` — `describeLocation()`/`FavLocation`/`FavStorage`/`badgeColors`; ⭐ toggle in the path bar (this screen has no top bar — New-Folder is a bottom FAB); content-swap (favorites list REPLACES the file list, path shows "★ Favorites", drive chip dims); `FavoritesList`+`FavoriteCard` (FileItemRow card style + drive badge + container/source line + mono path line + unpin star); row ⋮ "Add to/Remove from Favorites"; "Pin current folder" header action; system Back closes favorites before navigating up. `favTick` drives recompute on pin/unpin.
- ▶️ NEXT: CI green → device test (dedicated list swap; full origin labels incl. container name; jump; both pin paths; persist across relaunch; dead-path drop; Back closes favorites; no FM regressions).

### (prior) DESIGN + HTML PREVIEW

A Discord user asked for favorite/bookmarked directories in the File Manager. After clarification the design is:
- A **⭐ star button** in the top bar that **toggles** a favorites strip directly under the path/drive bar (slides in/out; zero vertical cost when collapsed).
- Favorites render as File-Manager-style cards (`surfaceContainer` + outline), each with a **colour-coded LOCATION badge** showing where it lives — Internal (blue) / SD card (green) / `C:` + container name (amber) / `Z:` imagefs (purple). One-tap jump.
- **Pin** via each folder row's ⋮ menu ("Add to Favorites") + a "Pin current folder" header action; **unpin** via the card's filled ★. Empty-state prompt.
- Theme-follows-accent; badges keep semantic identity colours.

**Why it's cheap:** the "go there" mechanism already exists — `FileManagerScreen.kt:168 openDrive(File)` is a generic jump-to-any-dir. Favorites = persisted `(path)` entries fed into it. Generalize the existing `currentDriveLabel` (`:498-503`) into a `describeLocation(path)` helper for the badges (add container name for `C:` paths via `containers` at `:123` + `Container.getName()`). Pin item goes in the row ⋮ (`:779-807`). Persistence = SharedPreferences/DataStore string-set, global/absolute paths v1, dead paths filtered by the existing `exists()` pattern (`:358`). Pure app-layer (Compose + prefs).

**Open decisions (blocking Kotlin):** (1) container-drive badge text — letter / container name / both; (2) one vs both pin entry points; (3) scope global/absolute (recommended) vs per-container.

**Preview:** `bannerlator_fm_favorites_preview.html` (scratchpad + `~/Downloads`; not pushed to device — no adb this session).

---

## 2026-06-30 — In-game Task Manager rows as cards (match File Manager) + 2 P4 fixes bundled (CODE DONE + CI building)

**TL;DR:** Task Manager processes in the in-game drawer now render as cards like the app File Manager
rows (user request), and the two prior P4 device-test fixes ride along. Latest commit `3e94450` on
`feat/ui-rebuild`, CI `28462330620` building, at device gate. Not merged (umbrella hold).
- `XServerDrawer.kt`: `TmProcessRow` wrapped in a Card matching `FileManagerScreen.FileItemRow`
  (RoundedCornerShape 10dp, `surfaceContainer`, `outline` border, 3dp vertical margin); removed the
  single-surface column + inter-row dividers (cards self-space).
- Bundled (CI-green at `589566c`, not yet device-tested): controls-editor uses app theme accent in
  editMode; binding-spinner text luminance floor (never invisible).

---

## 2026-06-30 — P4 device-test fixes: controls-editor readability + binding text never invisible (CODE DONE + CI building)

**TL;DR:** Two bugs surfaced in device testing of P4b/custom-color, both fixed. Commit `589566c` on
`feat/ui-rebuild`, CI `28461375330` building, at device gate. Not merged (umbrella hold).
- **Controls editor** rendered the on-screen buttons/labels in the per-profile in-game *custom* accent
  (a dark custom colour → unreadable, and it ignored the app theme). Fix: `resolveBaseAccentArgb()` uses
  the app theme accent in `editMode` (the editor); in-game still honours the per-profile custom colour.
- **Binding-spinner text** (themed in P4b) could go invisible on the screen's black background under a
  dark accent — the original black-on-black bug. Fix: `AccentArrayAdapter` applies a luminance floor
  (0.18) → accent when legible, white when too dark; default blue + presets keep their colour.

---

## 2026-06-30 — UI rebuild Phase 4b: legacy XML surfaces follow runtime accent (CODE DONE + reviewed + CI building)

**TL;DR:** The remaining LIVE legacy `@color/colorPrimary` surfaces now follow the runtime theme accent
(fed at inflation via `AppThemeState.getCurrentAccentArgb()`); `colors.xml` keeps #0055FF as the static
fallback so the AMOLED default is unchanged. Dead/Compose-replaced layouts skipped. Commit `7ed3f10` on
`feat/ui-rebuild`, CI `28459471318` building, **at device gate**. Not merged (umbrella hold).

### What changed
- New `widget/AccentArrayAdapter` — re-applies the runtime accent to the controller-binding spinner item
  + dropdown TextViews (both binding-spinner activities point at it; they didn't share an adapter before).
- `ExternalControllerBindingsActivity`: toolbar header background → accent; type + binding spinners →
  AccentArrayAdapter (dropdown view resource preserved).
- `ControlsEditorActivity`: binding spinners → AccentArrayAdapter.
- `ContentDialog`: title icon tint + title text + bottom-bar label → accent; body `TVMessage` untouched.
- `InputControlsFragment` (+ layout id): the "External Controllers" section header → accent at runtime.
- Trash icon left as-is (styled `colorPrimaryDark` on a raster PNG, not `colorPrimary`).

### Device-test gate (pending)
Under a non-default preset: controller-bindings screen header bar + binding-spinner text recolor; native
ContentDialog prompts show accent title/icon/label (body stays readable); Input Controls "External
Controllers" header recolors. At AMOLED default everything stays #0055FF (unchanged).

---

## 2026-06-30 — Per-profile custom accent color for on-screen controls — ✅ CORE DEVICE-PROVEN

Commit `f6ea902` on `feat/ui-rebuild`, CI `28455766095` green. Device-proven (core) via user screenshot:
the in-game Controls tab shows the shared HSV picker ("Controls Accent", hex `#8F6A00`) and the on-screen
controls (A–F row + MRB/BKSP/SPACE/ENTER) render in that amber custom color while the app/drawer stay
green-themed — i.e. the controls are decoupled from the app theme, the override + live redraw work, and
the shared ColorPicker reuse works in-game. Not yet visually confirmed (wired, expected fine): per-game
persistence across relaunch, the out-of-game editor checkbox+swatch path, and old-profile back-compat.
Not merged (umbrella hold). Remaining rebuild work: P4b legacy XML, then single merge.

---

## 2026-06-30 — Per-profile custom accent color for on-screen controls (CODE DONE + reviewed + CI building)

**TL;DR:** Users can override the theme accent on the in-game touch controls with a **custom color saved
per control profile (= per game)**, so the same setup returns next launch. Follow-app-theme stays the
default. Commit `f6ea902` on `feat/ui-rebuild`, CI run `28455766095` building, **at the device gate**.
Not merged (umbrella hold).

### What changed (11 files)
- `ControlsProfile` gains `customAccentEnabled` + `customAccentColor`, serialized in `save()` (header,
  before the elements array). `InputControlsManager.loadProfile` parses them and replaces the brittle
  `fieldsRead==3` break with an explicit break at `elements`/`controllers` — robust to the optional
  fields and old profiles (which just default to follow-theme).
- `InputControlsView.resolveBaseAccentArgb()` = the active profile's custom color when it opted in,
  else the theme accent; the Phase-4a accent getters all derive from it (ControlElement inherits).
- Shared HSV picker extracted to `ui/components/ColorPicker.kt` (reused by Appearance, the in-game
  Controls tab, and a `ComposeView`-hosted `AlertDialog` for the legacy editor, with the three
  ViewTree owners wired so Compose runs outside the activity content view).
- In-game: `XServerDrawerState` + Controls-tab "Follow app theme" toggle + picker; `XServerDisplayActivity`
  seeds from the active profile and persists + redraws live on change / profile switch.
- Out-of-game: `InputControlsFragment` + layout get a Follow-theme checkbox + color swatch → shared picker.

### Device-test gate (pending)
Default = follow theme (unchanged); toggle off → pick color → controls recolor live; set on game A,
relaunch A → persists; game B keeps its own; toggle back on → returns to theme; editor picker persists;
old profiles still load.

---

## 2026-06-30 — UI rebuild Phase 4a: on-screen touch controls follow theme accent — ✅ DEVICE-PROVEN

Commit `df5ce64` on `feat/ui-rebuild`, CI `28453428988` green. Device-proven via user screenshot on a
green/Forest preset: the in-game GAMEHUB-style on-screen controls (A–F shoulder row + MRB/BKSP/SPACE/
ENTER keys) render in the theme accent (green) and the in-game Controls tab is themed to match —
confirming both the classic-path literal routing and the `resolveAccentColor()` stub-wiring (GAMEHUB
glass style) work on hardware. Not merged (umbrella hold). Next: per-profile custom control color
(in progress, same branch), then P4b legacy XML.

---

## 2026-06-30 — UI rebuild Phase 4a: on-screen touch controls follow theme accent (CODE DONE + CI building)

**TL;DR:** Phase 4 = the native/legacy surfaces that Compose theming doesn't reach, wired to the
accent via `AppThemeState.getCurrentAccentArgb()`. **P4a = the in-game on-screen touch controls** —
code-complete on `feat/ui-rebuild` (commit `df5ce64`), CI run `28453428988` building, **at the device
gate**. Not merged (umbrella hold). Next after device-proof = P4b (legacy XML).

### What changed (2 files)
- `widget/InputControlsView.java`: `getSecondaryColor()` now returns the live theme accent (keeping
  the overlay alpha) instead of hardcoded `#0277BD`. Added `getAccentColor()` (full-opacity accent) +
  `getAccentBrightColor()` (accent lerped 55% toward white, for the pressed highlight) + a small
  `lerpToWhite` helper. `getPrimaryColor()` (white idle controls) unchanged.
- `inputcontrols/ControlElement.java`: every `0xff0277bd` → `getAccentColor()` and every `0xff64ddff`
  → `getAccentBrightColor()` across the classic-style strokes, dpad/stick, and button-icon tints;
  collapsed the now-identical GAMEHUB/default icon-tint branch. **Key fix:** `resolveAccentColor()` was
  a `-1` stub (forcing the always-blue fallback) — wired it to `getAccentColor()`, so the GAMEHUB
  "glass" control style (fill/stroke/pressed/text/thumb) now follows the theme too.

### Deliberately out of scope
- **Perf HUD** (`PerfHudView` / `FrameRating*` / `HudMetrics`) — all colors are semantic (FPS
  thresholds + per-metric identity), left untouched (same reasoning as the P3 per-tech dots).
- Idle control tint (white), semantic reds/blacks, and `CPUListView`/`EnvVarsView` (already on the bridge).
- **P4b — legacy XML `@color/colorPrimary` surfaces** (binding spinners, content_dialog,
  input_controls_fragment, main_menu_header, …) — deferred to a separate follow-up; judgment-heavy and
  touches the intentional binding-spinner-blue fix.

### Device-test gate (pending)
Install (manual), launch a container, open the on-screen controls, apply Sunset → control accent
(selected/active/pressed strokes, dpad/stick, icon tints) should be orange not blue, in both the
classic and GAMEHUB visual styles; idle controls stay white; controls still register touch/press.

---

## 2026-06-30 — UI rebuild: Phase 3 DEVICE-PROVEN + Games-cards-match-Containers follow-up (device-proven)

**TL;DR:** Phase 3 (app-screen colour sweep) is now **device-proven** (all 4 checks pass), and a
small follow-up makes the **Games list cards look like the Containers cards** — also device-proven.
Still on the umbrella branch `feat/ui-rebuild` (no merge until the whole rebuild is done).
Next = Phase 4 (native/legacy surfaces via `getCurrentAccentArgb`).

### Phase 3 — app-screen colour sweep — ✅ DEVICE-PROVEN
Commits `8a97185` (sweep ~190 literals → theme tokens) + `b20a58d` (3b: elevated `surfaceContainer`
tokens to restore card depth). Device test on the ludashi build, all 4 pass: (a) AMOLED default card
depth restored, (b) Sunset recolors the whole app incl. headline FAB + renderer/DXVK chips, (c) wiring
intact, (d) semantic/per-tech colors kept.

### Follow-up — Games list cards match Containers cards — ✅ DEVICE-PROVEN
Commit `c6116f5`, CI `28451457959` green. User gripe: the Games list item was a flat edge-to-edge row
while the Containers entry is a floating card. Fix in `ShortcutsScreen.kt` — wrapped `ShortcutItemLayoutL`
in the same `Card` as `ContainersScreen` (rounded 12dp `surfaceVariant` panel, `outline` border, 16dp/6dp
outer margins, 12dp inner padding; `onRun` moved from `Row.clickable` → `Card` onClick) and removed the
inter-item `Divider`. Grid view unchanged (already a bordered tile). User installed manually + confirmed
"looks much better" from a screenshot = device-proven. Not merged (umbrella hold).

---

## 2026-06-30 — Theme + Drawer rebuild: Phase 3 CODE DONE + CI GREEN (at device gate); Phase 2 DEVICE-PROVEN

**TL;DR:** Catch-up checkpoint for Phases 2 & 3 of the UI rebuild. All work lives on the
umbrella branch **`feat/ui-rebuild`** (no merge to main until the whole rebuild is done).
**Phase 2 (drawer dialogs) is device-proven**; **Phase 3 (app-screen colour sweep) is code-complete
and CI-green, now at the device-test gate.**

### Phase 2 — drawer dialogs — ✅ DEVICE-PROVEN (2026-06-30, 2nd attempt)
Commit `33eeb6a` on `feat/ui-rebuild`, CI `28440636066` green. Driven on the ludashi build
(`com.ludashi.benchmark` code 34) via the root bridge, Sunset preset, Vulkan container. All four
checklist items passed, no regressions:
- **Recolor under preset** — in-game drawer, Task Manager tab, CPU/Memory sections, and the New
  Task dialog all themed orange under Sunset; selected rail tab = orange pill.
- **Task Manager MoreVert** — "Bring to Front" (FlipToFront icon, neutral) + "End Process"
  (X/Close icon, red error-tint, destructive); New Task… (accent) + Clear footer themed.
- **🎉 Group B win — New Task dialog VISIBLE on Vulkan AND works** — the old native
  `ContentDialog.prompt` was invisible on Vulkan/ASR; converted to a Compose `AlertDialog`
  (title, text field defaulting `taskmgr.exe`, Cancel/OK). Tapping OK → `winHandler.exec`
  launched the real Windows Task Manager (Running on guest). End-to-end proven.
- **Wiring intact** — dropdown opens, New Task exec spawns a process, Exit → clean Shutdown
  teardown → clean return to Games. No crash/hang.
- Capture note: a persistent detached `logcat` must write to `/data/local/tmp` (not `/sdcard`,
  which is namespace-isolated under magisk su) to survive the bridge connection dropping.

### Phase 3 — app-screen colour sweep — ✅ CODE DONE + CI GREEN, ▶️ AT DEVICE GATE
Two commits on `feat/ui-rebuild`, both CI-green (workflow_dispatch `main.yml`):
- **`8a97185` "phase 3" — the sweep** (CI `28447905004` ✅): ~190 hardcoded `Color(0x…)` sites
  across 13 in-scope Compose screens rerouted onto `MaterialTheme.colorScheme.*` /
  `LocalAccentDim.current`. Stores (`store/*`) and theme-definition files
  (Color/ThemePreset/AppThemeState/Theme) left untouched; colour-only.
  - **Headline fix:** `ShortcutsScreen` FAB(+), grid-tile gradient, scrape icon → `primary`;
    `SpecCardComponents` renderer + DXVK chips → `primary` (so they follow the accent).
  - **Kept semantic colours:** green success `4CAF50`, `installedBlue 4FC3F7`, amber `FFC107`,
    error reds, untrusted salmon, per-tech identity dots, contrast white/black.
- **`b20a58d` "phase 3b" — elevated surfaces (user-approved fix)** (CI `28449013883` ✅): phase-3
  had no token matching the bespoke navy card surfaces / dialog greys → they flattened to
  near-black at default. Fixed by adding Material3's built-in `surfaceContainer` family slots to
  `ThemePreset` (data-class fields + set in `toColorScheme`/`toLightColorScheme`; derived defaults
  `lerp(surface, onSurface, 0.05/0.09/0.14)` so every preset gets a recolouring elevation ramp).
  - **AMOLED override values:** `surfaceContainer=0xFF1A1A2E`, `surfaceContainerHigh=0xFF2A2A38`,
    `surfaceContainerHighest=0xFF38383F` (restores blue-on-black card depth).
  - Repointed Settings / InputControls / FileManager navy cards & buttons by original depth order,
    and themed the leftover dialog greys (`2A2A2A`→High, `333333` tracks→Highest, body text
    `CCCCCC/E0E0E0`→onSurface, `AAAAAA/B0BEC5`→onSurfaceVariant). `Theme.kt` DefaultColorScheme
    confirmed dead/unused.

### Two sanctioned default-look changes to eyeball on device
(User OK'd default changes for this rebuild.) (1) renderer/DXVK chips are now accent-blue at the
AMOLED default instead of teal/green — the requested "chips follow the theme"; (2) Settings/Input/
FileManager card depth — flattened by 3a, restored by 3b's elevated tokens → confirm it reads
~like the original at default and recolours under a preset.

### ▶️ Next
Device-test Phase 3 on the ludashi build (user installs the `ludashi-debug` artifact from CI run
`28449013883`): (a) AMOLED default — Settings/dialogs have raised card depth, nothing broken;
(b) apply Sunset → Games FAB + renderer/DXVK chips + Settings + InputControls + FileManager +
dialogs all recolour; (c) wiring intact; (d) green success buttons stayed green. Then Phase 4
(native/legacy via `getCurrentAccentArgb`) and Phase 5 (optional presets). Merge `feat/ui-rebuild`
→ main only when ALL phases are device-proven.

---

## 2026-06-30 — Theme + Drawer rebuild: Phase 1 DEVICE-PROVEN (all checklist items pass)

**TL;DR:** Phase 1 (themed icons + button restyle of BOTH drawers) is now **device-proven**.
On-device verification on the ludashi build (`com.ludashi.benchmark` code 34, branch
`feat/drawer-rebuild-p1` @ `f30db20`, CI `28434248077` green @ `f30db209`) passed all four
checklist items with **no wiring regressions**. At the merge decision; not yet merged.

### Device test results (release-device-engineer, root bridge)
- **(a) App drawer — PASS** — LIBRARY/SYSTEM/STORES section headers (STORES "· unchanged"
  subtitle), distinct gamepad Games icon, palette Appearance icon (distinct from Settings),
  "NEW" badge, accent glow bar on selected item.
- **(b) Appearance reachable + live recolor — PASS** — Appearance opens from the drawer;
  Sunset preset recolored the whole app AND the drawer live; restored to AMOLED default.
  (Also clears the previously-untested base build `96ed50e`: Appearance nav entry +
  PrimaryDim→accentDim fix both confirmed — scaling chips/borders recolored orange.)
- **(c) In-game drawer — PASS** — AIO Graphics Test container (OpenGL): Graphics rail shows a
  monitor/display icon as a filled accent pill; selected "Linear" scaling chip accent-filled
  with black text; whole in-game drawer themed orange under Sunset.
- **(d) Wiring intact — PASS, no regressions** — launch ×2, tab-switch, Task Manager (7 procs),
  End Process → real kill → clean Shutdown teardown to app, Bring-to-Front dispatched clean
  (visual no-op on single-window AIO = documented native-fullscreen visibility limit), Pause
  fired, Exit closed cleanly to Games.

### Decisions
- **"NEW" badge on Appearance: KEEP for this release** (clean accent pill, not noisy; treat
  as temporary — fine to drop later once Appearance is discovered).
- Out-of-P1-scope (NOT a regression): Games-screen FAB + renderer/DXVK chips stayed blue under
  Sunset — screen-level hardcoded literals = the deferred P3 336-literal sweep.

### Next
- **Merge decision** for `feat/drawer-rebuild-p1` (stacked off the unmerged base
  `feat/theme-centralize-drawer` @ `96ed50e` — merging P1 carries the base; both proven
  together). → then **Phase 2** (drawer dialogs, incl. native ContentDialog → Compose).

## 2026-06-30 — Theme + Drawer rebuild: plans reconciled, Phase 1 (drawer rebuild) building

**TL;DR:** Merged the recolor-only theme-centralization plan with the new drawer-rebuild
request into one plan (`docs/THEME_AND_DRAWER_REBUILD_PLAN.md`). User greenlit **Phase 1**
(themed icons + button restyle of BOTH drawers). Phase 1 is compiling on branch
`feat/drawer-rebuild-p1`. No device test yet — this is a checkpoint before that.

### Decisions
- **Default look may now CHANGE** for the drawers — user dropped the old byte-identical-to-2.1.1
  rule. The rebuilt drawer look ships as the new default. (Other phases' app-screen recolor stays
  visually conservative.)
- **Restyle depth only** — icons + button styling + accent-driven states. NO wiring / structure /
  tab-order / handler changes. End-Process / Bring-to-Front / Exit / Pause / launch / controller
  paths untouched. Stores excluded.
- **Typography ramp + light mode stay CUT** (keep close to original).

### Combined plan = 5 phases
- P0 foundation: Branch 1 in-game color centralization (device-proven) + follow-up `96ed50e`
  (PrimaryDim→LocalAccentDim + Appearance nav entry), CI `28431784626` GREEN — not device-tested
  in isolation; its only independent piece (Appearance nav entry) gets verified inside the P1 test.
- **P1 (BUILDING)** = drawer rebuild: `AppDrawer.kt` (centralize local consts → colorScheme/
  LocalAccentDim, add LIBRARY/SYSTEM/STORES section headers, fix icon gaps Games→distinct gamepad,
  Appearance→`icon_palette`) + `XServerDrawer.kt` (Graphics rail → display icon, scaling/frame-gen/
  toggle/HUD-chip buttons restyled to accent-fill states on the already-centralized colors) + new
  drawables. Branch `feat/drawer-rebuild-p1` stacked off `feat/theme-centralize-drawer` (build =
  base + P1). Worktree `/home/claude-user/wt-drawer-p1`.
- P2 drawer dialogs (incl. native ContentDialogs needing Compose conversion) · P3 app-screen color
  sweep (~336 literals, old Branch 2) · P4 native/legacy via `getCurrentAccentArgb()` · P5 optional
  Midnight Cobalt + Phosphor presets.

### Approved preview
`bannerlator_drawer_rebuild_preview.html` (scratchpad + ~/Downloads + device /sdcard/Download) —
both drawers, live preset + HSV switcher, Before/Rebuilt toggle. Signed off → became P1.

### Next
Agent finishes P1 → push → CI "Any branch compilation." green → **device test** (gate = looks right
+ wiring intact, NOT byte-identical). SAVE memory + this log + commit BEFORE that device test
(same-device OOM rule).

**UPDATE (2026-06-30, later):** P1 code DONE + **CI `28434248077` GREEN**. Branch
`feat/drawer-rebuild-p1` @ `f30db20`. 4 files: AppDrawer.kt + XServerDrawer.kt + new `icon_games.xml`
+ `icon_display.xml`. Wiring confirmed untouched (no handler/structure/order changes). Open question
for user: keep the additive "NEW" badge on Appearance? **Now AT the device-test gate** — checkpoint
re-flushed; user drives the install + test. Phase-completion checkpoint will follow once device-proven.

**⏸️ PAUSED (2026-06-30) — user lost Wi-Fi mid-session, resuming at work.** Nothing in flight; all
pushed. RESUME = device-test the `feat/drawer-rebuild-p1` build (CI `28434248077` green, ludashi
flavor) per the checklist above + decide the Appearance "NEW" badge; on pass → phase-completion
checkpoint → decide merge → Phase 2 (drawer dialogs). Full resume pointer in memory
`project_bannerlator_drawer_rebuild` (🔖 RESUME HERE at top).

---

## 2026-06-30 — Theme centralization: reroute hardcoded colors onto the live theme (branch 1 started)

**TL;DR:** Starting a refactor so the existing theme engine actually paints everywhere — a
preset/accent now recolors the **whole app AND the in-game drawer**, which it doesn't today.
**Hard constraint from the user: the DEFAULT look must stay byte-identical to 2.1.1 (AMOLED,
#0055FF on pure black).** New presets are opt-in; no surprise on update. Stores are out of scope —
only the out-of-game Compose UI and the in-game side drawer + its submenus.

### Why (recon on main 2.1.1)
- `ui/theme/Color.kt`: `Primary`/`GlowPurple`/`AccentBlue` are **static consts** — a custom accent
  never reaches code that uses them directly. Core bug.
- `ui/XServerDrawer.kt` (the in-game drawer, 6 tabs: Graphics/HUD/ReShade/Controls/Advanced/Task Mgr):
  wrapped in `WinlatorTheme` so it inherits fonts but reads `colorScheme` for **zero** colors — paints
  from 6 local hardcoded constants (PureBlack/DarkSurface/…) + the static Primary/GlowPurple. ~37 literal
  sites. So a red accent leaves the drawer blue-on-black.
- Out-of-game: **336+ hardcoded literals** bypass the theme (worst: SettingsScreen 77, ContentsScreen 26,
  InputControlsScreen 21).

### Scope (user-trimmed: "close to original, no extreme complications")
- **CUT** typography ramp (leave force-600) and light-mode revival (leave dead) — colors only.
- Default stays **AMOLED**; existing users keep their saved preset (`AppThemeState` already persists).

### Plan
- **Branch 1 (this one) = steps 0-2:** (0) kill the static Primary/GlowPurple/AccentBlue aliases →
  resolve from the live scheme; (1) make the **AMOLED** preset carry today's EXACT shades (incl. the
  drawer's #000/#0D0D0D/#1A1A1A) so centralization is a visual no-op; (2) move `XServerDrawer.kt` fully
  onto `MaterialTheme.colorScheme.*`. Delivers "in-game drawer follows theme."
- Branch 2 = step 3: sweep the 336+ out-of-game literals, screen by screen (Settings first).
- Branch 3 = step 4: add Midnight Cobalt + Phosphor as **optional** presets.
- **Verify gate (every branch):** on-device before/after screenshot diff proving the default look is
  unchanged. The only failure mode is shade drift.

### Status (updated 2026-06-30)
- Branch `feat/theme-centralize-drawer`. Step-2 edit DONE in `ui/XServerDrawer.kt` (color-only).
- **Diff reviewed = color-only, verified safe:** no function signatures, no `onClick`/`winHandler`/lambda/
  control-flow lines changed. Only color args swapped + 20 `val accent = MaterialTheme.colorScheme.primary`
  and 1 `val surface = ...colorScheme.surface` locals added (pure theme reads). The 3 static imports
  (`Primary`/`GlowPurple`/`PrimaryDim`) removed; `PrimaryDim` kept as a drawer-LOCAL `Color(0xFF002277)`.
- **Drift-safety honored:** every accent site resolves to `colorScheme.primary` (= #0055FF under default
  AMOLED → byte-identical); pure-black panel/rail bg → `colorScheme.surface` (= #000000 under AMOLED). All
  neutral greys (`DarkSurface`/`DimWhite`/`MutedWhite`/toggle-off/#1A1A1A dividers/#4CAF50 green) left local.
- **Known limitation (deliberate):** `PrimaryDim` sites (switch-ON track, AccentButton container,
  selected-tab gradient bottom, HudChip selected bg) do NOT recolor for a CUSTOM accent — they stay
  dark-blue. #002277 maps to no exact scheme slot, so routing it would drift the default. Revisit later if
  we want those to follow the accent (accept a tiny default shift).
- **Functional safety = the user's concern:** theme edit cannot alter wiring. App↔drawer, Wine/containers,
  controller input (legacy `InputControlsView.java`, NOT touched), and all drawer buttons keep their exact
  handlers. Verify gate = on-device functional pass (End Process / Bring to Front / Pause / Exit / Apply &
  Close / ReShade toggle / controller drives game) + before/after color diff. **User drives the device tests.**
- Committed + pushed branch `feat/theme-centralize-drawer`. CI APK build (main.yml,
  **run 28419854007**) **✅ GREEN** 2026-06-30 — all 3 flavors built clean (standard/pubg/ludashi-debug
  ~560 MB each). Color-only diff compiled with NO fixes needed. **APK READY for the morning device test**
  (download the `standard-debug` artifact from run 28419854007).
- **PLAN: user device-tests in the MORNING** (user went to bed 2026-06-30). Build is green; no overnight
  fix was needed. Green APK + test checklist are ready.
- ON-DEVICE CHECKLIST (user drives, I watch logcat): (1) launch game in a real container → open drawer;
  (2) wiring — Task Mgr End Process / Bring to Front / Pause-Resume / Exit-to-app / Controls Apply&Close /
  ReShade toggle / controller drives game; (3) app side — launch + edit a container/shortcut; (4) color —
  default AMOLED drawer unchanged, then custom accent → drawer highlights recolor. EXPECTED non-bug:
  switch-ON tracks + a couple selected-chip bgs stay dark-blue under custom accent (the PrimaryDim call).
- Preview mock: `bannerlator_theme_preview_v2.html` (~/Downloads + /sdcard/Download).

---

## 2026-06-29 — bionic-fg: upstream MERGED our compat PR #6; fork synced; branch-landscape mapped for later

**TL;DR:** Our Android wrapper-ICD compatibility fix was **merged into upstream bionic-fg**
(`xXJSONDeruloXx/bionic-fg` PR #6, squash commit `68497bf`). Synced our fork `main` to it
(clean fast-forward, identical). Audited what the shipped build contains vs every open branch/PR.
No code changes this entry — this is a checkpoint so the shader/model work can be picked up later.

### What merged (PR #6 — "Single-device mode + layer-dispatch routing for Android wrapper ICDs")
- Authored by The412Banner, +310/-22 / 7 files. Fixes the hang-at-first-interpolated-present on the
  Wine+DXVK -> Turnip `wrapper_icd` stack: (1) manifest `disable_environment`, (2) single-device mode
  (run gen on the app's OWN VkDevice -> kills the 2-device cross-instance deadlock), (3) dispatch
  routing via `memPropsFn` + bounded 250ms fence waits + optional `fps_limit`. Device-proven Adreno 750
  2x/3x/4x. Squash-merge `68497bf` rolled in the 4 layer-robustness refinements too.

### Build provenance (verified 2026-06-29)
- Bannerlator submodule still pinned to upstream base `4f71770`; CI (`build-bionic-fg.yml`) applies
  `patches/bionic-fg-bannerlator-fixes.patch` at build time. Verified: that patch applied to the base ==
  merged `68497bf` **byte-for-byte** (0-line diff). So our source == merged main.
- Cleanup available (NOT done): bump the submodule to `68497bf` and DELETE the now-redundant patch.
  Must be one combined change — bumping the pin without removing the patch breaks `git apply` (already
  applied) and fails the bionic-fg CI build.

### Branch / PR landscape vs the SHIPPED build (`68497bf`)
- `feat/toml-hot-reload` (upstream) — MERGED, already in our build.
- **`feat/shader-pool-gamescope-v2`** (our fork, tip `b0c2e5c`) — HIGHEST ROI. New = ~+22.7k lines
  `shaders_embedded.hpp` (FIXES the malformed `shader_02` we currently ship + pools GameScopeVK/V2
  shaders) + `model=2` "V2 engine". This is the open shader-pool thread.
- **`feat/fsr3-optical-flow-model`** (our fork, tip `603d26e`) — HIGHEST CEILING, heaviest. Superset of
  shader-pool + `model=3` AMD FidelityFX Optical Flow (4 new compute shaders, ~+24.8k embedded,
  `NOTICE_FIDELITYFX_OPTICALFLOW.md`, standalone `build-so.yml`). Needs on-device perf+visual validation.
- `fix/model1-remove-warpblend` (upstream, 1 commit) — cheap correctness fix (removes a model-1
  warp-blend stage that shouldn't exist). Not in our build.
- PR #5 `feat/future-refresh-pacing` (upstream, OPEN) — alt frame pacing, overlaps our `fps_limit`,
  unmerged + unproven on Turnip. Lowest confidence.

### Caveat for whoever resumes
All branches above pre-date the squash, so they sit on the un-squashed compat commits (same content,
different SHAs) and read "1 behind". To integrate cleanly: REBASE onto synced `main`/`68497bf`
(cherry-pick only the genuinely-new commits) so the compat changes don't reappear as conflicts.
**Recommended start = `feat/shader-pool-gamescope-v2`** (fixes a bug we currently ship + it's our own
half-done work), then FSR3 `model=3`. Save+commit before any same-device test.

---

## 2026-06-29 (cont.) — STEP 3 ReShade: P2 DEVICE-PROVEN + typed controls/tab/reset + on-demand download catalog LIVE

**TL;DR:** The in-game ReShade feature is now fully device-proven (P2 done). Added typed UI controls,
a dedicated ReShade drawer tab, and a Reset button. Switched the effect library from APK-bundled to
**on-demand download** — published a 100-effect catalog (`reshade.json` + `reshade-v1` release) on
`The412Banner/winlator-contents`; the app-side download UI is building.

### P2 device-proven (in-game ReShade)
- Live `.fx` compile, live on/off, and live per-uniform sliders all confirmed on hardware (Technicolor,
  ArcaneBloom over The Saboteur). The `formatUniformLine` `<effect>_<uniform>` key syntax is correct.

### `feat/reshade-typed-controls` (off `fix/reshade-live-toggle`) — CI `28411754406` GREEN
- `ReshadeManager.ParamType += COMBO, COLOR`; `reflectParams` parses `floatN`/`intN` + `ui_items`
  (`\0`-split), and now **skips `source=`-annotated uniforms** (engine semantics like timer/frametime —
  ArcaneBloom's `uTime`/`uFrameTime` were leaking in as dead sliders).
- Drawer renders by type: bool→toggle, combo/radio→dropdown, color→HSV picker (collapsed by default,
  tap swatch to expand), slider/drag→slider. Value transport stays float-based; color = `<u>_0.._N` keys.
- New **dedicated ReShade tab** (`TabType.RESHADE`, `icon_screen_effect`) — pulled the ReShade block out
  of the Graphics tab. **Reset** button re-seeds every param to its `.fx` default via `onReshadeApply`.
- Pre-launch editors (container + shortcut) render the same typed controls (color = R/G/B sliders there).
- Device-confirmed: toggle ("Use Limits"), dropdown ("Debug Options"), color picker, collapse, Reset.

### On-demand download catalog (replaces APK bundling)
- Decision: do NOT ship `.fx` in the APK. Host on `The412Banner/winlator-contents`; the pre-launch effect
  picker shows the full catalog GREYED, each row downloads-and-fills-in on tap.
- **Published + LIVE:** `reshade.json` on repo `main` (100 effects) + 100 per-effect `.tzst` as assets on
  release **`reshade-v1`**. License-safe set only (crosire/prod80/luluco250/fubax; AstrayFX excluded for
  license ambiguity, qUINT excluded). Built by `scratchpad/reshade_catalog.py` (include+texture closure;
  prod80 `PD80_NN_` prefixes stripped for clean ids; category-tagged).
- Schema: `{schemaVersion,category,release,mirrorBase,count,effects[{id,name,description,category,author,
  license,url,file_size,file_checksum(MD5),version}]}`. `id` = drop-in folder name; tzst extracts into
  `getExternalFilesDir/ReShade/<id>/`.
- App side building on `feat/reshade-download-catalog` (off `feat/reshade-typed-controls`).

### Credits (for the next stable's release notes)
- vkBasalt engine: **DadSchoorse** (original, our patched .so builds from this) + **Pipetto-crypto**
  (Winlator integration, commit `67b6dad`) + **StevenMXZ** (Winlator-Ludashi). Shader authors:
  crosire/ReShade, prod80, luluco250, Fubaxiusz.

### Still ahead
- Codegen sweep (verify which of the 100 actually compile on vkBasalt → prune/flag catalog).
- Tier-1 hardening: existing-container layer heal, GPU/Mali coverage, Vulkan-only boundary.
- Merge the ReShade stack to main + cut the stable (with credits). Depth effects = STEP 4.

---

## 2026-06-29 — ▶️ RESUME HERE: STEP 3 ReShade effects — BUILT end-to-end, one device test from done

**TL;DR:** In-game ReShade `.fx` effects via the bundled vkBasalt layer. App feature + a patched live-reload
vkBasalt layer are BOTH built and CI-green; integrated build is compiling. **Only the on-hardware device test
remains.** Design doc: `docs/RESHADE_STEP3_PLAN.md`.

### Branches (all pushed to origin)
- `feat/reshade-step3` — app-side feature (5 commits). CI `28401038692` ✅ GREEN.
- `feat/reshade-vkbasalt-build` — patched libvkbasalt.so source/patch/workflow. CI `28401364441` ✅ GREEN.
- **`feat/reshade-integrated`** — `step3` ⊕ `vkbasalt-build` + integration commit (patched .so repacked into
  `extra_libs.tzst` + uniform-key fix). **THIS is the branch to install/test.** Combined CI `28402115564`
  (workflow "CI Build (artifacts only)") — was IN PROGRESS at handoff; check it on resume.

### What was proven / built this session
1. **Spike DEVICE-PROVEN:** hardcoded sepia `.fx` compiled on-device (vkBasalt reshadefx→SPIR-V→Turnip) and
   applied to a live DXVK game (The Saboteur) → screen went sepia. ReShade `.fx` on Adreno = real. Blockers
   #1 (language) + #2 (Adreno compile) empirically dead. (Depth effects still STEP 4.)
2. **Two infra bugs found + fixed:** (a) `extra_libs.tzst` (carries libvkbasalt.so) only extracted on container
   `firstTimeBoot` → existing containers never got the layer (silent no-op). Fixed: extract whenever the .so is
   absent. (b) the SHIPPED libvkbasalt.so has its key-detection compiled out (`isKeyPressedX11`→return false) +
   no uniform-override path → its HOME toggle is dead for ALL inputs and it can't do live sliders. ⇒ any live
   control REQUIRES a rebuilt layer (the X11-Home-inject "free toggle" idea is DEAD — dropped).
3. **App-side Phase 1** (android-app-engineer): `reshade/ReshadeManager.java` (drop-in folder scan +
   `.fx` ui_* param regex reflection, scalar float/int/bool); `Container.java` persists `reshadeEffect`/
   `reshadeParams`; `XServerDisplayActivity.java` extraction-gate fix + `writeVkBasaltConfig()` (merged with CAS
   into one `effects = <effect>:cas` chain) + `applyReshadeLive()` seam; in-game drawer ReShade section
   (`XServerDialogState.kt`/`XServerDrawer.kt`); effect pickers in shortcut + container editors.
   Drop-in folder = `getExternalFilesDir(null)/ReShade/` (one subfolder per effect; copied into guest HOME
   `.config/vkBasalt/effects/<name>/` at launch for host-absolute paths). Gated to DXVK/VKD3D.
4. **Patched vkBasalt** (graphics-vulkan-engineer): `patches/vkbasalt-reshade-livereload.patch` vs upstream
   `DadSchoorse/vkBasalt@4f97f09` (submodule `app/src/main/cpp/vkbasalt`), built via new `build-vkbasalt.yml`
   (meson Android cross, NDK r27c, arm64-v8a/android-24, X11-free). **Part A** = live on/off (Config remembers
   opened path; present-hook mtime-watch re-reads conf → flips `presentEffect` from `enableOnLaunch`; lsfg-mirror,
   no swapchain recreate). **Part B** = live sliders (codegen uniforms-to-spec-constants=FALSE → ui_* uniforms in
   UBO; new `GenericUniform` + `ReshadeEffect::updateUniformsFromConfig()` overrides from conf key
   `"<effect>_<uniform>"` → next-frame memcpy, no recompile). CI-green + binary-symbol-verified.
5. **Integration:** patched .so (stripped 1.85MB) repacked into `extra_libs.tzst`; `formatUniformLine()` aligned
   to emit `<effectKey>_<uniform>` to match the patch's read key.

### ▶️ NEXT (on resume, when home / Wi-Fi back)
1. Check combined CI `28402115564` (branch `feat/reshade-integrated`); if green, grab the APK artifact.
2. **DEVICE TEST on The Saboteur (DXVK)** — the unproven gate (all green/binary-verified, Part A+B untested on
   hardware): install integrated APK → use a **FRESH container** (old `xuser-4` has `libvkbasalt.so.disabled`
   from the spike sepia-cleanup; extraction-heal/new container installs the patched .so) → pick a ReShade effect
   → verify (a) effect renders CORRECTLY [Part B UBO change didn't break render], (b) drawer ReShade on/off
   toggles LIVE [Part A], (c) sliders move the image LIVE [Part B + key syntax]. ⚠️ uniform-override key form is
   the most likely thing to need a tweak — centralized in `formatUniformLine()`.
3. If green on device → tidy (strip note, drop the throwaway `spike/vkbasalt-reshade`), then decide merge to main.
   Live SLIDERS depend entirely on the patched .so working; if Part B misbehaves on device, on/off (Part A) is the
   lower-risk fallback to ship first.

⚠️ Memory file `project_bannerlator_step3_shader_loader_reshade` has the full detail + every commit/run id.

---

## 2026-06-28 (s4) — ▶️ RESUME / CURRENT STATUS: VRR + manual picker built & verified; pacing tweak pending

**Where things stand on the graphics roadmap:**
- **STEP 1 (debanding + NIS): DONE — MERGED to main** (`feat/deband-nis` ff `71e2d27..4565b80`, branch deleted).
  Both device-proven on Vulkan via the new AIO torture cards (banding ramp + scaling combo). Not in a tagged
  release yet (per versioning rule — no stable cut without explicit say-so).
- **STEP 2 (VRR / refresh-rate matching): BUILT + DEVICE-VERIFIED**, branch `feat/vrr-refresh-rate` (off main,
  NOT merged). Took 3 fixes to get the panel to actually move: seamless→ALWAYS (`c29acc0`), capability gating
  (`83da657`), and the big one — window `preferredRefreshRate` was pinning the panel to max and out-voting our
  surface vote (`35dd636`). After that, all 4 states verified on-device (Vulkan, AYANEO 144Hz panel):
  cap+match→60, cap+nomatch→144, uncapped+match→144, incapable→greyed. Clean 60↔144 both directions.
- **Manual refresh-rate picker: BUILT** (`fa77da6`, CI `28333613335` GREEN) — unified 'Refresh rate' drawer
  control (Auto match-FPS + manual snap-to-supported-modes chips, auto-detected via getSupportedRefreshRates;
  Auto greys chips; whole group greys on incapable devices). Editor got a manual FilterChip row. Auto path
  byte-identical (reviewed). DEVICE-TEST OWED.

**Open issue — FPS oscillation (user-observed):** with limiter=60 + Auto-match ON, FPS swings 56↔64 every few
seconds. Two suspects: (1) **AYASpace system refresh control** — the test device is an AYANEO handheld whose
AYASpace overlay has its OWN 'Refresh Rate' control (Auto/144/120/90/60), was pinned to 144; its polling
service likely re-asserts 144 vs our VRR every few seconds (matches the timing). (2) **limiter/VSync beat** —
matching the panel to exactly the cap removes the 144Hz headroom that hid the limiter's pacing jitter (panel
likely 59.94 vs cap 60.0). 

**▶️ NEXT (test plan, before merge):** (1) DIAGNOSTIC — set AYASpace Refresh Rate→Auto, re-test: swing stops =
system contention (config fix, document it), persists = pacing beat. (2) Install build `28333613335`, verify
manual picker (Auto-off+pick 90/120 → dumpsys locks panel to it regardless of cap) + headroom check (manual
120 + cap 60 should be smooth) + auto-path 4/4 regression. (3) If pacing beat confirmed → add **cap-below-
refresh** fix (pace limiter to ~refresh−1 when Auto matching). (4) Then MERGE feat/vrr-refresh-rate (VRR +
manual picker + any pacing fix) to main → then STEP 3.

**Device-driving notes:** measure VRR while game is FOREGROUND (it releases the vote on background); 'go'
handshake + `sleep 12; dumpsys` works. Key greps: activeMode= , setFrameRate=/{10492, the per-layer
`Hz ... Always/OnlySeamless` vote line. Move-cursor-to-touchpoint is ON (tap absolute). Newest device
screenshots in /sdcard/Pictures/Screenshots/.

---

## 2026-06-28 (s4) — 🆕 Manual refresh-rate picker built (unified Auto + manual control)

On top of the verified VRR, added a unified "Refresh rate" drawer control: the match-refresh toggle is now
"Auto (match FPS)" + a chip row of the panel's supported rates (auto-detected via `getSupportedRefreshRates`).
Auto ON → chips greyed (VRR drives it); Auto OFF → pick a rate and the panel locks to it regardless of the FPS
cap; whole group greys on incapable devices. `applyVrr` extended with an additive manual branch (auto path
byte-identical, reviewed). New state manualRefreshRate/supportedRefreshRates/onManualRefreshChange + Container
`manualRefreshRate` extra + resolver + editor FilterChip row. Commit `fa77da6`, CI run `28333613335`.
Device-test owed: Auto-off + pick 90 → panel locks 90 regardless of cap; chips greyed when Auto on; auto path
4/4 regression. Then merge the whole `feat/vrr-refresh-rate` (VRR + manual picker) to main.

---

## 2026-06-28 (s4) — ✅✅ VRR device-test #3: WORKING (+ clear-path verified) — panel drops 144→60 to match the FPS cap

Build `28332650876` (seamless fix `c29acc0` + capability gating `83da657` + window-pin fix `35dd636`). On
Vulkan, FPS cap 60, Match-refresh ON, game foreground: **activeMode 144.00→60.00 Hz** — the panel physically
switched. Override `{10492, 60.00 Hz}`, both layer votes now `60 Hz SeamedAndSeamless` (game surface + window
pref agree). VRR is device-proven end-to-end. The `preferredRefreshRate` lever moves this panel, so both
auto-VRR and a future manual refresh-rate picker will work here.

**Before merge:** verify toggle-off/uncapped returns the panel to 144 (clear path); optional GL/ASR spot-check
(shared code, Vulkan proven). **Next feature (green-lit):** manual refresh-rate picker — one control with
'Auto (match FPS)' + manual snap-to-supported-modes (60/90/120/144 auto-detected via getSupportedModes); Auto
greys the slider; whole control greys on single-mode/pre-A11 devices. Same `preferredRefreshRate` lever.


**Clear-path verified (test #3b):** toggling "Match refresh rate to FPS" OFF returned the panel 144 Hz (activeMode 60→144, both votes restored to 144). So VRR does the full round trip — drops to the cap when on, restores max when off. Also verified the cap-dependency: limiter OFF + match ON (uncapped) → panel returns to 144 (VRR only acts while capping). All 4 states confirmed {ON+match→60, ON+nomatch→144, OFF+match→144, incapable→greyed}. **VRR comprehensively verified and ready to merge.**


---

## 2026-06-28 (s4) — 🐛➡️✅ VRR device-test #2: surface fix confirmed, fixed a 2nd blocker (window pins max refresh)

Re-tested with the seamless fix (`c29acc0`) + capability gating (`83da657`). Our game-surface vote is now correct —
`60.00 Hz Default SeamedAndSeamless` (the force-switch worked; was OnlySeamless). But the panel still held 144
because a 2nd layer voted 144: `XServerDisplayActivity.onCreate` pins `window.preferredRefreshRate = max` (144)
for smooth UI, and that window-level request out-votes the surface vote. **Fix `35dd636`:** new
`applyWindowPreferredRefreshRate(vrrRate)` (called from applyVrr) lowers the window preference to the matched
rate when VRR is capping, restores max otherwise. CI run `28332650876`. Retest: expect activeMode 144→60.
Lesson: an emulator that force-pins preferredRefreshRate to max blocks ANY app VRR vote — keep it in step.

---

## 2026-06-28 (s4) — 🐛➡️✅ VRR device-test #1: found + fixed the "panel won't drop" bug

Tested VRR on-device (AIO, OpenGL renderer, FPS cap 60, Match-refresh ON, game@60). Panel stayed at 144 Hz —
frameRateOverride showed our app un-throttled `{10492, 144}`. Root cause via dumpsys layer line
`60.00 Hz Default OnlySeamless`: our 60 Hz vote WAS placed correctly, but with **seamless-only** strategy, which
a peak-refresh 144 panel ignores. The code bug: `XServerView.applyFrameRateToSurface` only used the 3-arg
`setFrameRate(..., ONLY_IF_SEAMLESS)` when `FRAME_RATE_SEAMLESS_ONLY` was true, else fell through to the 2-arg
overload — which **also defaults to ONLY_IF_SEAMLESS**, so the "force" path was never taken.

**Fix `c29acc0`:** when SDK≥31, pass the strategy explicitly — `ONLY_IF_SEAMLESS` if seamless-only else
`CHANGE_FRAME_RATE_ALWAYS` (force the mode switch). CI run `28331250229`. Retest owed: reinstall, re-measure
(expect override→cap, activeMode drop). Notes: 40 fps is an awkward cap for a 144 panel (144/40=3.6) — use 60;
container was GL, also test Vulkan; if ALWAYS still won't drop it's device display policy, not our code.

**➕ Capability gating (`83da657`, CI `28332020195`):** the "Match refresh rate to FPS" toggle is now **greyed out** on devices that can't do VRR — `XServerView.isDisplayVrrCapable(display)` = SDK>=30 AND >1 distinct refresh rate among supported modes; gated in the in-game drawer (XServerDrawerState.vrrSupported, seeded at launch) + the ContainerDetail editor (probes the default display), with an "Unavailable on this display" hint. Single-mode/60Hz-only + pre-Android-11 → disabled. Build 28332020195 includes BOTH this AND the seamless-only fix `c29acc0`, so it supersedes 28331250229 — install THIS one for the retest. User's 144/120/90/60 panel = capable → toggle stays enabled.

**Same-device test protocol (1-thing-at-a-time):** VRR releases its vote on background (onStop→0), so measure
while the game is foreground — user stays in game, sends "go", switches back; I fire `sleep 12; dumpsys` to
capture with the vote reapplied. Confirm foreground via topResumedActivity.

---

## 2026-06-28 (s4) — 🆕 STEP 2: VRR / refresh-rate matching IMPLEMENTED (branch `feat/vrr-refresh-rate`, device-test owed)

Step 1 (debanding+NIS) merged to main earlier today; started Step 2 = make the panel refresh rate follow the
game FPS via `Surface.setFrameRate` votes (complementary to the FPS limiter: limiter=render rate, VRR=display
rate). One Surface-level vote on the parent SurfaceView covers all 3 host renderers (GL / Vulkan compositor /
SurfaceFlinger-ASR) since SF aggregates frame-rate votes over the layer subtree; existing Vulkan native child-SC
votes left intact.

5 commits on `feat/vrr-refresh-rate` (off main, pushed, NOT merged):
1. `4882473` XServerView.setDisplayFrameRate(float,int) — SDK_INT>=30 guard, picks active holder Surface,
   remembers last rate + re-asserts in surfaceChanged (added a holder callback to glSurfaceView which had none).
2. `4671c26` applyVrr()/reapplyVrr()/resolvedMatchRefreshRate() in XServerDisplayActivity — votes 0 when
   off/uncapped, `cap` normally, `cap×mult` in the lsfg-governs case; wired into applyFpsLimit + onStop(release)
   + onResume(reassert) + drawer onMatchRefreshChange.
3. `dcbefb5` Container `matchRefreshRate` extra (default ON) + shortcut resolver.
4. `2b603e5` drawer "Match refresh rate to FPS" toggle + XServerDrawerState flow.
5. `5ff90db` ContainerDetail editor switch + strings.

Reviewed rate logic + cross-layer contract names (sound; `getFrameGenMultiplier()` confirmed to exist).
CI run `28330068467` ✅GREEN (all flavors compile). No release/tag cut.

**▶️ DEVICE-TEST (user starting now):** `dumpsys SurfaceFlinger | grep -i frameRate` to confirm the panel takes
the vote. Verify: vote = cap when capped, cap×mult under lsfg, clears (0) when toggle-off/uncapped, re-asserts
bg→fg. **PREREQ: turn the FPS limiter ON + set a cap** (VRR only votes when capped; limiter is the existing
pre-feature, VRR just matches the panel to it). Needs a high-refresh panel (dumpsys shows the modes).
**Renderer priority:** Vulkan first (full stack, most likely to land) → OpenGL → SurfaceFlinger/ASR last + most
scrutiny (relies on SF aggregating the parent-Surface vote to the native child SC; if it doesn't land there →
do optional commit 6 = native ASurfaceTransaction_setFrameRate on the game child SC). Everyday use: Vulkan.
Risk: setFrameRate is a HINT (battery-saver may ignore).

---

## 2026-06-28 (s4) — ✅ VULKAN RETEST PASSED on new AIO torture cards → `feat/deband-nis` CLEARED TO MERGE

User built the AIO Graphics Test with the Banding scene + new "Scaling Tests >" sub-page (builder used the
exact scene ids from the brief: scaletest_combo/zoneplate/wedge/grid/checker/edges). Switched the AIO shortcut
to the **Vulkan** renderer (1280x720→1080p upscale) and drove it on-device:
- **Debanding (banding card, dark 0..16/255 ramp):** OFF = ~12 hard stair-step bands; ON = bands dissolved into
  fine dither grain. Max diff exactly 1/255, mean diff ~3x the smooth Space scene. The visual proof we couldn't
  get on Space/Nebula.
- **Scaling modes (combo card, grid patch sharpness std-dev):** none=linear 0.0590 (identical → None≡Linear
  label quirk), sgsr 0.0689, fsr 0.0693, **NIS 0.0757 (sharpest clean upscaler)**, nearest 0.0936 (blocky
  aliasing). All live-switch, all distinct; SGSR/FSR/NIS reconstruct cleanly above Linear.
- Host compositor confirmed Vulkan via drawer layout (CAS/Debanding). NOTE: AIO HUD "Renderer" line mislabels
  (showed "OpenGL" on the combo) — trust the container renderer, not that label.

**BOTH gate halves PASSED on Vulkan → `feat/deband-nis` is cleared to merge (awaiting user go-ahead).** Then
STEP 2 = VRR. Optional cleanup: None≡Linear label + CAS/Sharpen slider-snapping.

---

## 2026-06-28 — 🎨 `feat/deband-nis` FULLY DEVICE-PROVEN (NIS both renderers + Debanding) → MERGE-READY

**Branch `feat/deband-nis` (tip `cc3361f`, off main, PUSHED, NOT merged).** Detail memory =
`project_bannerlator_smooth_sharp_render_roadmap.md` (STEP 1). Device test driven via root bridge on the
SAME device (Adreno 750), AIO graphics test app, renderer = **OpenGL | DXVK**.

**NIS (NVIDIA Image Scaling, upscaler mode 7) — re-confirmed on GL (Space scene 720p→1080p):**
- ✅ **NO Adreno runtime-compile crash** selecting NIS on the GL renderer — the one big open risk (heavy
  unrolled NIS shader) is definitively cleared; scene keeps rendering. (Already proven on Vulkan in s2.)
- ✅ **Perf cost now measured** (was unbenchmarked): **290fps → 200fps (~31%)**, GPU load 72%→~86%. Still smooth.
- ✅ **Quality** (clean frozen-frame montage None/Nearest/Linear/NIS): NIS = crisp edges + detail preserved,
  distinct from blocky Nearest & soft Linear, no artifacts → NVScaler math correct. RMSE vs `none`: NIS 0.54%.
- ✅ **Sharpness slider** present, continuous, seeds 75, registers 0/100. Modulation confirmed qualitatively
  (clean pixel-delta elusive — the AIO Pause releases on a slider drag; first 4.9%/0.6% numbers were motion-contaminated, discarded).

**Debanding (terminal TPDF dither pass) — device-verified on GL Space scene (frozen-frame A/B):**
- ✅ Toggle works (drawer, below HDR); ON reveals "Dither strength" slider (seeds 100).
- ✅ **Max pixel diff = EXACTLY 1/255 (one 8-bit LSB)**, mean ~0.04/255 — textbook dither magnitude.
- ✅ **Mean brightness perfectly preserved** (0.282483→0.282532) — no bias/tint (hallmark of correct dither).
- ✅ Diff footprint = fine uniform noise across gradients (atmosphere/planet/moon), ~zero in black sky/saturated.
- ⚠️ **CAVEAT:** no dramatic "bands→smooth" before/after on Space — its gradients are dark/shallow + broken by
  stars/detail, so little gross banding to dramatize. Dither provably correct at LSB level; Space ≠ a showcase.
  **For a striking visual demo → AIO "Detailed Nebula" scene (next test).**

**🔎 SIDE-FINDING (cleanup candidate, unfixed):** in this build **None ≡ Linear EXACTLY** (RMSE 0) — base "None"
is doing bilinear; base-sampler labels (None/Linear/Nearest) worth a look. Pairs with the known CAS/Sharpen
slider-snapping inconsistency (GL 5-notch vs VK continuous).

**Debanding RE-TESTED on "Detailed Nebula" (s3, GL, frozen-frame A/B):** mechanism re-confirmed (game-area
max diff = exactly 1/255, mean-preserving); positive dither-footprint = fine stipple noise, ~8.8% of pixels in
the glow falloff nudged 1 LSB, densest at quantization boundaries. BUT even hard-amplified on the brightest
smooth ramp, OFF vs ON look identical — Nebula shows NO gross banding to dramatize either. CONCLUSION: AIO
test scenes render gradients clean enough that 8-bit banding is minimal in both Space & Nebula; debanding's
gain is real but sub-perceptual on THIS content (will matter on real games w/ heavy banding — fog/skyboxes/UE).
Build is dither-only on 8-bit chain (precision-bump step deferred). Note: whole-frame RMSE is meaningless here
(drawer changes between shots) — always crop to game area x>900.

**▶️ NEXT — ⛔ MERGE ON HOLD (user decision 2026-06-28):** do NOT merge `feat/deband-nis` yet. All prior
tests ran on AIO Space/Nebula scenes, which don't visibly band and barely separate upscalers (smooth content)
→ only proved debanding/scaling mathematically, not visually. GATE before merge: (1) AIO Graphics Test gets
its Banding scene (already built, commit `881f39e`, 1 past v1.6.0 — needs push+build+install) + new
Scaling-Test scenes (spec'd, not built — briefs in /sdcard/Download/SCALING_TESTS_BUILD_BRIEF.md +
BANDING_SCENE_FINDINGS.md) → (2) install new AIO binary → (3) **retest on the VULKAN renderer** with real
torture content (debanding on the dark 0..16/255 ramp, in-app dither OFF, scaling=None/1:1;
NIS/SGSR/FSR/FSR-Fit on zone-plate/wedge/grid at sub-native render-res). THEN merge if it passes → THEN STEP 2
= VRR. Optional: precision-bump (10-bit/R16F intermediate) would make debanding visibly stronger — revisit
only if a real game shows banding.

---

## 2026-06-28 — 🧩 BIONIC-FG: Track-3 FSR3 Optical Flow built + `.so` delivered for manual injection (NOT device-tested)

**Resume context for the bionic-fg frame-gen shader-pool / model-expansion effort.** Detail memory =
`project_bannerlator_bionic_fg_shader_pool.md`. Fork = `The412Banner/bionic-fg`, clone `/home/claude-user/bionic-fg-fork`.

**WHAT GOT DONE THIS SESSION:**
- ✅ **Track 3 = FSR3 Optical Flow added as runtime model 3.** Branch `feat/fsr3-optical-flow-model`
  (off `b0c2e5c`), commits `d6f4a09`(embed FSR3 OF SPIR-V idx66-69 + restore dropped `IsValidSpirv`) →
  `9f06376`(model-3 dispatch + clamp→3) → `5eb11a7`(GLSL src+docs) → `603d26e`(CI workflow). PUSHED, unmerged.
  Source = FidelityFX-SDK optical-flow passes (MIT), reimplemented subgroup-free GLSL for Turnip/no-DXC.
  15 OF passes → reuse model-0 warp/blend/synth back half. Models 0/1/2 byte-unchanged (additive).
- ✅ **Built `libbionic_fg.so` (arm64-v8a).** NEW self-build workflow in the fork `.github/workflows/build-so.yml`,
  CI run `28323607624` **GREEN** (NDK r26b 26.1.10909125 / android-26, matches app). No external patch needed —
  the single-device anti-deadlock fixes (fork commit `ac2f5c0`) are already an ANCESTOR of Track-3, so the
  source self-contains models 0/1/2/3 + single-device `create(device,…)` + manifest `disable_environment`.
- ✅ **Delivered to device for manual inject:** `/sdcard/Download/libbionic_fg.so` (6.3M, md5 `971e6aaa…`) +
  `/sdcard/Download/VkLayer_BIONIC_framegen.json`. User will inject + test LATER.

**🔑 CRITICAL ARCH FINDING (corrects prior records):** the app does NOT compile bionic-fg in its main NDK
build (no `add_subdirectory(bionic-fg)`); the layer ships as a **prebuilt asset**
`app/src/main/assets/bionic-fg/libbionic_fg.so`, built by the SEPARATE manual `build-bionic-fg.yml`. So the
recorded "model-2 app CI GREEN" only bundled the OLD prebuilt `.so` — **shipped layer = `9136405c` (Jun 21),
pre-model-1/2/3.** Models 1/2/3 + the shader_02 fix were NEVER in any shipped layer until this new `.so`.

**▶️ WHAT TO DO LATER (resume):**
1. **User injects + device-tests the new `.so`** — replace `<imagefs root>/usr/lib/libbionic_fg.so`,
   set `model = 3` in `…/home/<container>/.config/bionic-fg/conf.toml` (UI only writes 0/1), FG enabled.
   ⚠️ **CLOBBER CAVEAT:** `ImageFsInstaller.installBionicFgLayer` re-stages the OLD bundled asset whenever
   on-device size ≠ bundled size → inject right BEFORE launching or it reverts. Also worth testing model 2 (V2).
2. **Triage results:** FSR3 OF is compile-proven only — main risks = PERF (heavy SAD motion search; tunable
   search window `BR`/`SR` in `of3_flow.comp`) + visual correctness. shader_02 fix + model 2 also first-ever on device.
3. **If we want to ship for real** (not just inject): rebuild the bundled asset — either dispatch the app's
   `build-bionic-fg.yml` AFTER refreshing/removing the now-redundant 608-line `patches/bionic-fg-bannerlator-fixes.patch`
   (stale; fixes already in source), OR repoint that workflow to build the fork branch like `build-so.yml` does;
   then commit the new `.so` to `assets/`, bump versionCode (NOT a release per [[feedback_bannerlator_release_versioning_rule]]).
4. **Roadmap Tracks 4 & 5 BLOCKED on toolchains** (no sudo): Track 4 Lossless DXBC→SPIR-V needs `vkd3d-compiler`
   (meson/widl/spirv-headers all missing); Track 5 RIFE/IFRNet needs an `ncnn` build. Revisit when toolchains available.

---

## 2026-06-28 — 🛠️ BUILT: Debanding + NIS upscaler (branch `feat/deband-nis`, CI pending, NOT device-tested)

Implemented Track-1 step 1 from the master plan below. **Branch `feat/deband-nis` off main, 6 commits
`194b7b9`→`cc3e0e7` (+log `a53a226`), PUSHED, NOT merged, NOT device-tested.** Shaders compile clean
(glslangValidator), all cross-layer contracts reviewed+consistent. **CI ✅ GREEN — run `28319416413`
(build-artifacts.yml, all flavors; only a harmless Node-20 deprecation annotation).** First real compile of the
C++/JNI/Java/Kotlin passed. NEXT = on-device A/B (see ⚠️ below), then merge.

**DEBANDING** — terminal TPDF/IGN dither pass (float-only hash, Adreno-safe), `deband.frag`+header,
appended LAST as `FX_DEBAND`, registered in fxOn in BOTH planUpscaleFrame AND recordUpscalePasses
(known "scaling drops chain" bug avoided). `setDeband(bool,int strength)` JNI↔C++↔Java; GL `DebandEffect`
+ dedicated terminal slot in EffectComposer (render() + renderUpscaled()). strength 0..200 → /100 LSBs
(default 100 = ±1/255). Session-live (not persisted).

**NIS** — new upscaler mode int **7**. Faithful NVScaler port from authentic NVIDIA reference (MIT)
into single-pass `nis.frag` (edge map + 6-tap scale/USM + directional filters + CalcLTI + luma recolor);
**exact fp32 coef_scale/coef_usm baked as `const float[384]`** (transcribed programmatically from
NIS_Config.h, NOT hand-typed, first-row verified); fp32 path (no fp16/bitwise); NO 2nd descriptor binding.
Reuses existing sharpness slider. GL `NISEffect` + EffectComposer `case 7`. Engages only when render
res < display (like SGSR mode 3). Drawer: `7 to "NIS"` + mode-7 sharpness in both GL+Vulkan blocks.

**⚠️ Needs device-verification (agent's honest flags):** (1) NIS math not pixel-compared to a reference
NIS — A/B on the high-freq SPACE scene 720p→1080p, GL AND Vulkan. (2) GL NISEffect runtime-compiles a
heavy unrolled NVScaler + 2×384 const arrays on the Adreno GLES driver = the runtime-compile-crash class
the roadmap flags — DEVICE-TEST GL NIS specifically (Vulkan NIS is precompiled SPIR-V, safer). (3) NIS =
37 texture fetches/fragment, perf untested. (4) CI is the first real compile of the non-shader code.
Files: `cpp/winlator/{deband,nis}.frag`+headers+`gen_shaders.sh`, `VulkanRendererContext.{cpp,h}`,
`vulkan_jni.cpp`, `renderer/vulkan/VulkanRenderer.java`, `renderer/EffectComposer.java`,
`renderer/effects/{DebandEffect,NISEffect}.java`, `ui/XServerDrawer.kt`, `ui/XServerDialogState.kt`,
`XServerDisplayActivity.java`.

---

## 2026-06-28 — 🗺️ MASTER PLAN: Graphics smoothness/sharpness roadmap + App theming/icons

> **STATUS: RESEARCH + RECON + 1 HTML MOCKUP + CODE-GROUNDED PLANS ONLY. NOTHING CODED, NOTHING
> COMMITTED, NOTHING DEVICE-TESTED.** This entry consolidates a full session of exploration into one
> plan. Deep detail (every file:line touchpoint) lives in the two memory files:
> `project_bannerlator_smooth_sharp_render_roadmap.md` + `project_bannerlator_theming_icons.md`.

Two parallel tracks scoped this session. Recommended first build = **debanding + NIS** (Track 1, small/visible/low-risk).

---

### TRACK 1 — SMOOTHNESS + SHARPNESS RENDERING

**User goal:** keep games EXTREMELY SMOOTH, NO FPS loss (ideally gain), + clarity/sharpness.

**Core principle (the answer):** it's a STACK, not one effect →
**render a bit lower → spatial upscaler (sharp) → VRR refresh-match (smooth) → debanding (clarity).**
That gains real FPS, cuts heat/power, adds sharpness, zero added latency.
**Myth busted:** frame-gen does NOT give free FPS — it inflates the HUD number while costing GPU + latency
(a perceived-smoothness layer, optional cherry-on-top for single-player base≥45fps).

**Recommended build order:**
1. **Debanding + NIS** ← START HERE (ready, ~6 commits, low-risk)
2. **VRR / `setFrameRate`** (refresh-rate matching; biggest smoothness-per-effort; SurfaceFlinger path best home)
3. **Curated shader-loader platform** (force-multiplier; compile `.spv` OFFLINE in CI = Adreno-crash-safe; permissive-licensed shaders only)
4. **Moonshot: DXVK depth → true SGSR2 on TAA/FSR2 games** (XL, staged, multi-repo)
5. Keep frame-gen (`bionic-fg`) gated/off-by-default, labeled as a latency trade.
**DROP:** BFI, Anime4K-CNN, pixel-art scalers, heavy CRT (royale), NPU/AI super-res — each costs FPS or doesn't fit.

**Scorecard (vs goal):** SGSR1/NIS/FSR1 ★★★★★ · CAS / shader-loader-platform ★★★★ · 3D-LUT / panel-calibration / estimated-MV-temporal ★★★ · Anime4K(CNN heavy) / pixel-art / adaptive-sharpen(already have CAS+RCAS) / heavy-CRT ★★ · BFI ★½ skip.

**READY-TO-BUILD PLAN — Debanding + NIS (GL + native Vulkan; ASR path runs neither chain):**
Shared plumbing: Vk post shaders `cpp/winlator/*.frag` + committed `*_frag.h` SPIR-V (gen via
`glslangValidator -V x.frag --vn x_code -o x_frag.h`, NO CI compile step). `VulkanRendererContext.cpp`
`createPostPipelines`~:552 / `recordUpscalePasses`:1074 / `planUpscaleFrame`:1235 / locked effect-chain
:1204-1229 / SINGLE-sampler `createDSLayout`:402-407 (don't add a 2nd binding) / PC range 88B.
**Whole chain = R8G8B8A8_UNORM 8-bit** end-to-end. GL = `renderer/EffectComposer.java` + `renderer/effects/*.java`.
Drawer `ui/XServerDrawer.kt` (options :772-775, sharpness slider :815-838). Effects SESSION-LIVE (not persisted).
⚠️known bug: an effect silently no-ops under scaling unless added to fxOn in BOTH planUpscaleFrame:1250 AND recordUpscalePasses:1087.
- **Debanding:** new `deband.frag` = terminal dither (IGN/TPDF, float-only hash, ~1 LSB, display space, no texture taps, Adreno-safe). Last effect-chain entry `FX_DEBAND` (always→swapchain). Vk pipeline + push-const + `setDeband` JNI/Java. GL `DebandEffect` + dedicated TERMINAL slot in EffectComposer (GL has no fixed order). Toggle + optional strength slider. Optional SEPARATE gated commit: bump offscreenFmt→A2B10G10R10/R16F (then off↔swap cross-bind needs split pipelines) — ship dither-only first.
- **NIS:** upscaler mode int=7. new `nis.frag` single-pass NVScaler, BAKE coef tables as `const float[]` in shader (no 2nd descriptor binding; fp32 not fp16). Reuse sharpness slider. Vk pipeline + planUpscaleFrame mode-7 branch (like SGSR mode3) + recordUpscalePasses single-pass; no new JNI. GL `NISEffect` + setUpscaler `case 7`. Drawer add `7 to "NIS"`. MIT ©NVIDIA.
- **Commits:** 1)shaders+headers+gen_shaders.sh 2)NIS-Vk 3)deband-Vk+JNI 4)NIS-GL 5)deband-GL 6)drawer+wiring 7)opt fmt-bump 8)opt persist. **Device-test SPACE scene 720p→1080p, GL AND Vulkan, A/B, confirm no FPS drop + composes with upscalers.**

**STAGED MOONSHOT — SGSR2 (XL, MULTI-REPO):** VERDICT — shipping a patched DXVK is mechanically fine
(we build/ship own DXVK `.tzst` in `assets/dxwrapper/`, extract `XServerDisplayActivity.java:2582`). REAL WALL =
guest→host transport: today only 1 buffer (color) crosses via DRI3 1-AHB=1-FD (`DRI3Extension.java:141`
`modifiers==1255`); the SENDER of extra buffers lives in the guest Wine/WSI build (NOT this repo → wine-compat +
guest build). PREREQ A.0: add a sub-1.0 internal-res lever (renderScale only supersamples ≥1.0 today). **Stage A
depth** (med, useful alone → unlocks DoF/SSAO): patch DXVK to export chosen depth as **R32F** AHB (sidesteps Adreno
depth-AHB limits), new DRI3 `modifiers==1256`, depth import variant, prove via depth-grayscale DEBUG pass. **Stage B
SGSR2** on TAA/FSR2 games (they give MV+jitter free): new 2-pass-FS shaders, persistent history buffer, 4-binding
descriptor for SGSR2 only, per-title jitter profile, auto-fallback SGSR2→SGSR1→passthrough, Vulkan-compositor-only
(ASR can't). **Stage C generic = research wall** (jitter can't be generically injected) — spike only.
**Smallest prototype:** 1 FSR2/TAA game → DXVK export depth-only R32F → accept 1256 → grayscale debug overlay;
if frame-aligned depth correct, transport PROVEN, rest is shader math; else STOP.

**SurfaceFlinger/ASR renderer (the 3rd host renderer) findings:** it hands the guest buffer STRAIGHT to the system
compositor — NO programmable pass, so the upscaler/effect shaders CAN'T live there (by design = its speed/battery win).
What it CAN uniquely add: `setFrameRate`/VRR (best home for #2 above), present-fence timing into the HUD, scaling/aspect
geometry modes, true HDR10 passthrough (speculative), layer alpha/damage. Already wired: setBuffer/geometry/visibility/zorder.

---

### TRACK 2 — APP THEMING + BUTTON ICONS

**Deliverable in hand:** interactive HTML preview (live theme switch, dark/light, accent slider, typography
before/after, 4 screens with icon fixes, offline-safe inline SVG) at device
`/sdcard/Download/bannerlator_theme_icons_preview.html` (+ scratchpad + `~/Downloads/`).

**Finding:** the app ALREADY has a theme engine (`ui/theme/Theme.kt`/`ThemePreset.kt` = 8 presets + HSV picker
`AppearanceScreen.kt`) → this is POLISH not a rebuild. Brand accent `#0055FF` (`Color.kt:8`).
**3 weaknesses:** light mode is DEAD CODE (`toLightColorScheme` exists, `_isDarkMode` hardcoded true, no toggle);
no Material You; ~730 hardcoded color literals bypass the theme (custom accent only paints PART of app until centralized).

**Proposals:**
- 3 new themes (live in the HTML): **★Midnight Cobalt** (rec, evolves brand blue; primary `#2F6DFF`/accent `#6FA8FF`/bg `#0E1117`) · **Phosphor Terminal** (retro CRT amber+green, dark-only) · **Carbon & Ember** (graphite+orange `#FF7A33`). Rec: ship Midnight Cobalt default + keep AMOLED + add Phosphor preset.
- **Typography ramp** (high ROI, ~10 lines `Theme.kt`): app forces weight 600 on ALL text → give head700/body400/label500.
- Un-disable light mode (setter+toggle); optional Material You preset API31+.
- **Icon gaps:** store detail pages (`store/{Steam,Epic,Gog,Amazon}GameDetailActivity.kt`) are TEXT-ONLY buttons → add `Icons.Filled.{PlayArrow,Download,Update,Delete,InsertDriveFile,CloudUpload}`; text "←"→`ArrowBack`; Epic "✓ Installed"→`CheckCircle`; game-card placeholder `OpenInNew`→`SportsEsports`; magnifier "✕"→`Close`. `material-icons-extended` already a dep. **Highest-ROI = store action-button icons (pure additive).**

**3-bucket app-wide reach verdict:** (a) AUTO once colors centralized = all out-of-game Compose UI + in-game magnifier;
(b) small recolor fix = in-game DRAWER `XServerDrawer.kt` (wrapped in theme, inherits fonts, but ~40 hardcoded colors → stays blue under a custom accent);
(c) feed accent MANUALLY = FPS/perf HUD (`widget/FrameRating*.java`, `PerfHudView.java`), on-screen controls (`InputControlsView.java`), legacy XML editors — via existing bridge `AppThemeState.getCurrentAccentArgb()`.

**Theming sequencing:** 1) typography ramp 2) store-button icons + back-arrows 3) centralize ~730 colors per-screen 4) Midnight Cobalt + Phosphor 5) light-mode toggle + optional Material You.

---

### NEXT MOVE
Start coding **Track 1 → debanding + NIS** (commits 1-6, CI-green, then user device-tests on the SPACE scene before SGSR2 spike). Everything above is durable in the two memory files; this log entry is the master index.

---

## 2026-06-27 — 🏷️ 2.0 STABLE RELEASE CUT

Merged the #18 turnip-ICD branch to main, bumped to **2.0 (versionCode 32)**, rewrote the README
"What's New in 2.0" + feature sections, and cut the **2.0 stable release** (run `28309799973`,
prerelease:false + make_latest:true → now the Latest the in-app updater offers; 3 flavor APKs +
update.json attached). Closed PR #24 (not merged).

**Everything in 2.0 since 1.9.2:** OpenGL renderer upscaler parity (SGSR/FSR/Sharpen) · sharpness
sliders retuned (0=off→100=max, SGSR doubled, Sharpen 5-stop snap, inverted-CAS fix) · OpenGL
filter-mode + glBlitFramebuffer plumbing (P4) · **game + container card redesign (#19)** ·
**auto-close session on game exit** · magnifier fix on Vulkan (#22) · FEXCore "Performance (TSO)"
preset (#20) · **Android-10 direct-ICD turnip driver (#18)**.

README/release note imageFS-reinstall reminder added (new turnip driver lives in imageFS).
Pinged issue #18 reporter (SD845/A10) to verify the new `turnip-26.1.0` driver — issue left open
pending their confirmation (no A10 device locally).

---

## 2026-06-27 — #18 direct-ICD turnip path for Android <11 (built, CI-green, awaiting reporter A10 test)

Diagnosis: the reporter's A10/SD845 problem is the driver LOADING MECHANISM — Bannerlator loads
turnip via adrenotools (linkernsbypass hook, needs ~A11+); Winlator loads it as a plain system
Vulkan ICD (works on any Android). Also closed PR #24 (strlcpy/snprintf — original code already
safe).

Branch `feat/turnip-icd-direct-android10` off main (`f43a319`), commits `60038cf` + `971c415`,
CI `28309292459` ✅ green, **NOT merged, NOT device-tested** (no A10 hardware here). New driver
option `turnip-26.1.0` (Winlator's Mesa Turnip 26.1.0, ICD format) that installs the freedreno
ICD + .so into the guest, points `VK_ICD_FILENAMES` at it, and **skips the adrenotools env**
entirely — bypassing the A11+ hook. Picker filter special-cased to gate on `isAdrenoGPU()` (the
normal adrenotools probe is exactly what fails on A10). Adrenotools path untouched; default still
turnip-sdk36. Top residual risk: host-vs-guest path assumption (if proot-remapped, library_path
would need a guest path). NEXT: reporter verifies a CI build on their SD845 → then merge.

---

## 2026-06-27 — Implement open issues #22 (magnifier) + #20 (FEX Performance+TSO preset)

gl-upscaler-parity merged to main (`6d5f75b`). Branch `fix/issues-22-magnifier-20-fextso` off
main, CI build `28308479676` ✅ green. **User device-confirmed both work → MERGED to main (ff
`6d5f75b..ecb8646`, branch deleted, no tag); GitHub auto-closed #22 and #20.**

**#22 magnifier (`d7a736e`):** `showMagnifierOverlay()` cast the renderer to `GLRenderer` and
no-op'd the zoom callback for anything else → on the default Vulkan renderer the overlay opened
stuck at 100% with dead +/- buttons. Now uses the `HostRenderer` interface (get/setMagnifierZoom
implemented by all 3 renderers; Vulkan applies it live via `updateTransform`). The other
GLRenderer cast in `showScreenEffectsDialog` is correct (effects are GL-only) — left as-is.

**#20 FEX "Performance (TSO)" preset (`55fb879`):** fetched the issue screenshot — the reporter's
preset is Performance with **only `FEX_TSOENABLED=1`** (vector/halfbarrier/memcpy TSO stay off,
x87-reduced + multiblock on), the lightweight single-TSO-flag variant. Added `PERFORMANCE_TSO`
to `FEXCorePreset` + a `getEnvVars` block + `getPresets` entry + `performance_tso` string.
Additive, no DB migration; auto-appears in the spinner and Compose container/shortcut editors.

NEXT: CI green → device-test (#22 magnifier on a **Vulkan** container; #20 pick preset + launch a
TSO game) → merge to main.

---

## 2026-06-27 — Open-issue triage + scopes (#22 magnifier, #20 FEX TSO preset) — QUEUED

Scoped while the gl-upscaler-parity slider CI built. To be implemented on a **fresh branch off
main AFTER the gl-parity sliders device-test + merge** (user: "after we device test we will
tackle them both").

**#22 — magnifier doesn't work / zoom — ROOT CAUSE FOUND (high confidence):**
`XServerDisplayActivity.showMagnifierOverlay()` (~line 3339) casts the renderer to
**GLRenderer only** and the zoom callback early-returns when it's not GL. The **default
renderer is Vulkan**, so the Magnifier overlay opens, shows 100%, and the +/− buttons are dead.
Confirmed clean fix: `HostRenderer` interface already declares `get/setMagnifierZoom` (all 3
renderers implement it) and `VulkanRenderer.setMagnifierZoom` calls `updateTransform()` → zoom
applies live. **Fix = use the `HostRenderer` reference instead of the GLRenderer cast** (~3
lines, 1 file). Optional: `ASurfaceRenderer.setMagnifierZoom` lacks a redraw trigger (add
`updateScene()`). Device-test on Vulkan (can fold into the slider session).

**#20 — Add FEX "Performance + TSO" preset:** the built-in PERFORMANCE preset sets
`FEX_TSOENABLED=0`; many games need TSO. **Fix = add a new "Performance (TSO)" built-in preset**
(Performance base + the 4 TSO flags on: TSOENABLED/VECTORTSOENABLED/MEMCPYSETTSOENABLED/
HALFBARRIERTSOENABLED=1, X87REDUCEDPRECISION=1, MULTIBLOCK=1) across `FEXCorePreset.java` +
`FEXCorePresetManager.java` (getEnvVars + getPresets) + `strings.xml`. No DB migration (presets
stored by id). Verify exact flags vs the reporter's screenshot.

**Also noted:** #18 (bundle brunodev Winlator turnip 26.1.0 for A10/SD845 — packaging easy but
adrenotools load may need A11; needs an A10 device) and PR #24 (bounded strlcpy/snprintf in
android_sysvshm.c — review + merge candidate).

---

## 2026-06-27 — GL upscaler parity: device-test, inverted-slider fix, sharpness-range tuning

**Branch:** `feat/gl-upscaler-parity` (off main `ec3bcb0`). Phase 1 (SGSR/FSR/Sharpen on the
OpenGL EffectComposer + drawer Scaling-mode picker, commits `efd5f4f`→`327ab9d`) was already
CI-green (`28306036455`). This session = device-test + fixes. NOT merged.

**Device test (build 1.9.2 vc31, AIO-Graphics-Test-32bit = OpenGL + 1280x720 on 1080p panel,
DX11 SPACE scene, frozen-frame A/B via drawer Pause):** GL parity LIVE — all 6 scaling chips
produce DISTINCT output on the GL renderer (frozen-frame re-upscale works). RMSE vs None:
Linear 0% (≡None ⇒ default sampling IS bilinear) · Nearest 1.51% (blocky) · Sharpen 0.36% ·
SGSR 0.68% · FSR 0.79% (crispest). Cursor stays crisp under Nearest = PASS (host cursor
exempt from point-scale). Zoomed montages confirm visual distinctness.

**🐛 Bug found + fixed (`52c7092`):** the upscale "Sharpness" slider was INVERTED for the
Sharpen mode (AMD CAS) — raising it SOFTENED. Root cause = `FSREffect`'s level scale is
inverted (level 1 = CAS sharpness 0.90 = sharpest, level 5 = 0.12 = softest) but both CAS
call sites mapped slider straight onto level. Fix: `EffectComposer.buildPickerCas():339`
`level=(1-upscaleSharpness)*4+1` + `XServerDisplayActivity.onSgsrUpdate:2088`
`level=(100-sharpness)/25+1`. SGSR (EdgeSharpness=1+s*1.333) and FSR RCAS (stops=1-s) were
already correct → untouched. CI `28307366153` triggered then cancelled (superseded below).

**✅ Slider-effectiveness tuning DONE + committed (graphics-vulkan-engineer, GL + Vulkan):**
every sharpness slider now spans **0 = nothing (neutral, no sharpening; upscale still runs)
→ 100 = max**, ZERO shader recompiles (all host-side push-constant / pass-gating math).
Final effective values, slider 0/50/100:
- GL SGSR EdgeSharpness **1.0 / 2.33 / 3.67** (`1.0+s*2.666` — span doubled, neutral floor 1.0)
- GL FSR RCAS lobe scale **0.0 / 0.5 / 1.0** (`clamp(sharpness,0,1)` — 0 = true passthrough)
- GL Sharpen mode 6 (snapped {0,25,50,75,100}) **OFF / CAS 0.50 / CAS 0.90** (0 = no CAS pass)
- GL "Sharpen (CAS)" toggle (snapped) **OFF / 0.50 / 0.90**
- Vulkan SGSR edge **0.5 / 2.5 / 4.5** (`0.5+s01*4.0` — span doubled)
- Vulkan FSR/Sharpen RCAS con.x **0.0 / 0.5 / 1.0** (`upscaleSharpness01` linear, 0 = passthrough)
- Vulkan CAS toggle **OFF / 0.5 / 1.0** (`casOn = casEnabled && casSharpness>0`)
Snap = `XServerDrawer.kt` IntSlider `steps` override (5 stops only in mode 6 + always for the
Sharpen(CAS) toggle; SGSR/FSR/Vulkan-Sharpen stay continuous). No over-drive past RCAS/CAS
ceiling → no ringing. Commits `beebb17` (GL SGSR-double + FSR 0=neutral) · `fac47ed` (GL Sharpen
5-stop snap + 0=OFF) · `0718c39` (Vulkan mirror). CI build **`28307792454`** ✅ green all 3 flavors.

**✅ GL device-test PASSED (AIO space scene, OpenGL|DXVK Adreno 750, live drawer-open method):**
Sharpen slider **snaps to 0/25/50/75/100** (tap ~38% → snapped to 50). FSR slider **0 = true
passthrough** (crop png 218 KB, softer than None's 245 KB, no sharpening) → **100 = strongly
sharp** (420 KB, +92%). SGSR **0→100 = +18% png**, montage shows visibly crisper limb/coastlines
(doubled range). 0 = neutral confirmed. Vulkan mirror = code-verified (shared host logic) but
not device-tested this session (AIO shortcut is OpenGL). Note for next time: this AIO test
**exits the scene on BACK while paused**, so the frozen method was abandoned — open the drawer
once via BACK while running, then switch chips live without pause/BACK. NEXT: merge gl-parity to main.

**❌ Dropped per user:** a persisted per-shortcut "upscaling on/off" toggle. Clarified that
720p→1080p plain stretch is ~free (final-blit sampler); only opt-in SGSR/FSR cost GPU and are
already default-None + session-live. User said "leave the upscaling alone."

---

## 2026-06-27 — P4 "Lean GL path" (render-upgrades roadmap, final phase) — steps 1+3

**Status:** branch `feat/p4-lean-gl-1-3` off main `35fd80d` (pushed). 2 commits. CI
`28304623016` (`build-artifacts.yml`, all 3 flavors) running. Baseline main build
`28304103719` ✅ green (known-good fallback). NOT merged; device-test pending.

**Context:** Vulkan render-upgrades (P1 SGSR/FSR framework, P1b/1c CAS/HDR/sliders, P2 the
5 GL effects → Vulkan, + native-mutex) are all DONE + on main. P3 (ReShade `.fx` engine)
DROPPED. **P4 = the last phase**, and it targets the *Java GL renderer only*.

**Recon (graphics-vulkan-engineer, read-only):** the roadmap's framing — "reduce
GLSurfaceView overhead" — is mostly the wrong target. In the default config (DRI3 on) the
game frame is **already zero-copy** on GL via AHardwareBuffer→EGLImageKHR (`GPUImage`), so
there's no per-frame CPU upload to kill. EGL context is already GLES3 (`XServerView.java:89`)
despite `GLES20.*` calls → GLES3 APIs available today. Real gaps: `setFilterMode` is a dead
no-op on GL; no low-res→cheap-upscale path; full-frame `glTexSubImage2D` only on the
SHM/DRI3-off/cursor path; effect chain renders base into a full-res FBO even for 1 effect.
GameHub's `libxserver.so` is proprietary but its "direct scanout" rides the same AHB/EGLImage
primitives we already own → P4 = clean-room reimpl with in-tree CAS/SGSR/FSR, nothing
license-blocked. Ladder = 5 rungs; this batch = the low-risk 1–3.

**Implemented (this batch):**
- **Step 1 — `f7e0670` setFilterMode real on GL.** `GLRenderer.java`: new `windowTexFilter`
  field; `setFilterMode(int)` maps `2→GL_NEAREST else→GL_LINEAR` (matches Vulkan convention),
  applied in `renderDrawable` ONLY when `material == windowMaterial` so the **cursor stays
  LINEAR**. Launch hook `XServerDisplayActivity.java:1785`
  `renderer.setFilterMode(container.getRendererFilterMode())` gated `instanceof GLRenderer`
  (was dead/unreachable before — nothing called `HostRenderer.setFilterMode`; Vulkan drives
  filtering via `setUpscaler`). `getRendererFilterMode()` verified to exist (`Container.java:497`).
- **Step 2 — PBO async upload — ❌ DROPPED.** First CI failed to compile
  (`Texture.java:153 int cannot be converted to Buffer`): this project's compileSdk exposes
  only the `Buffer` overload of `GLES30.glTexSubImage2D`, not the int-offset (PBO) variant →
  a PBO can't feed the texture, so no benefit (`glTexImage2D`-with-offset would realloc every
  frame, slower than the sync path). Cleanly reverted (`Texture.java` diff vs main now empty).
  Deferred, not delivered. Lesson: don't assume Android GLES30 int-offset texSubImage exists.
- **Step 3 — `c085dd9` glBlitFramebuffer for trivial copy stage.** `EffectComposer.render()`:
  null-material (pure-copy) pass now goes through `blitReadBufferTo` (GLES30
  `glBlitFramebuffer`, COLOR_BUFFER_BIT, LINEAR, scissor disabled) instead of program-bind +
  textured quad. ALL real shader passes (Color/FXAA/Toon/CRT/NTSC/CAS) + the source-less
  `drawFrame` scene render are UNCHANGED — only the degenerate null-material branch changed
  (which also fixes an old clear-then-draw-nothing→black bug). Bit-identical with-effects output.

**Deferred:** Step 4 (low-res render-target → cheap upscale = the actual "GameHub feel" win,
higher risk: letterbox/scissor + keep cursor full-res) and Step 5 (drop GLSurfaceView for an
owned EGL/SurfaceView present thread). Step 2 (async CPU-path upload) would need a non-PBO
mechanism given this SDK's bindings.

**Confidence:** step 1 = would-work/needs-device-proof (sampler state only, no fast-path
regression); step 3 = would-work (bit-identical for shipped effects). No SDK in the agent env
→ correctness proven by CI compile + device test, not local build.

**Next:** CI green → device-test (GL renderer + Filter toggle nearest/linear; confirm cursor
stays sharp; effects still composite) → merge to main. No tag/release (artifacts only).

---

## 2026-06-27 — #19 follow-up: Layout L wired + A/L card chooser

**Status:** branch `fix/shortcut-name-overflow` (pushed). Commit `324bb4a` (L + chooser) +
audio-drop follow-up edit. Compile CI `28300618235` (`main.yml`) running on `324bb4a`;
audio-drop fix needs a re-run after its commit. NOT merged.

**Ask:** user said "wire up L so I can choose between the two" — make the list-view card style
user-selectable between the existing layout A and the chosen layout L.

**Implementation:**
- `ShortcutsViewModel.kt`: persisted `useLayoutL` pref (`shortcuts_prefs` / `list_card_layout_l`,
  default `false` = A) mirroring `isGridView`; `setUseLayoutL()`.
- `ShortcutsScreen.kt`:
  - Top-bar **A/L toggle** — an "A"/"L" Text `IconButton`, shown only in list view (hidden in
    grid). Keyed the top-bar-actions `LaunchedEffect` on `(isGridView, useLayoutL)` so the toggle
    reflects live state (was `Unit` → captured stale values; the existing grid icon only updated
    via content recompose).
  - List branch now picks `ShortcutItemLayoutL` vs `ShortcutItem` on `useLayoutL`.
  - New `ShortcutItemLayoutL`: same 48×64 poster cover; subtitle = `container · resolution`;
    PRIMARY `FlowRow` of bright `CompChip`s — renderer (`ChipRendColor`), DXVK, frame-gen
    (`ChipFgColor`, "Bionic-FG" / "LSFG-VK"); SECONDARY `FlowRow` of `SecondarySpec` (7dp colour
    dot + dimmed `OnSurfaceVariant` 10sp label) — driver, VKD3D, backend (`ChipCpuColor`).
  - Renderer / frame-gen / backend resolved via `shortcut.getExtra(key, container.getX())`
    (`getRenderer` / `getFrameGenEngine` / `getEmulator`); backend name via
    `R.array.emulator_entries` + `StringUtils.parseIdentifier`.
  - **Refactor:** extracted the shared ⋮ menu into `ShortcutOverflowButton` (own `menuExpanded`),
    now used by BOTH A and L so the menu can't drift.
- **Audio dropped** from L's secondary (follow-up edit) to match `docs/shortcut_card_L_final.html`
  — lets driver · VKD3D · backend sit on one row. First commit `324bb4a` wrongly included it.
- **Deferred:** backend preset suffix ("FEXCore · TSO" / "Box64 · Perf" in the mockup) — needs the
  async `Box64PresetManager` / `FEXCorePresetManager`, too heavy to resolve per list-card; backend
  shows the emulator name only for now.

**Next:** commit audio-drop fix → CI green → device-test (both layouts render + pref persists
across the toggle and app restart) → merge → close #19. ⚠️ save-before-device-test rule.

---

## 2026-06-27 — Issue #19 "Name of games is empty" + game-card redesign

**Status:** branch `fix/shortcut-name-overflow` (pushed, NOT merged). Layout A build CI `28299970224` running.

**Root cause (#19):** in `ShortcutsScreen.kt` list-mode `ShortcutItem`, the right-aligned
resolution+DXVK/VKD3D info column had no width bound. In a `Row`, unweighted children are
measured before the weighted name column gets the remainder, so a long version string (e.g.
a DXVK/VKD3D nightly with a commit id in its name) grew unbounded → collapsed the weighted
name column to 0 width ("name is empty") AND pushed the trailing ⋮ overflow menu off-screen.

**Iterations on the branch:**
- `1dd8d4d` interim: capped info column `widthIn(max=120dp)` + ellipsize. Device-tested by user
  → fixed the blank name but now TRUNCATED the component versions (not acceptable).
- `e496040` interim: split DXVK/VKD3D onto own lines, wrap to 2 lines, cap 140dp.
- `b598adb` (current tip) **Layout A redesign**: replaced the info column with a 3:4 poster
  cover (reuses `shortcut.icon`, same bitmap the grid uses) + name/container + graphics
  components as colour-coded chips on a wrapping `FlowRow` (`CompChip` helper + 4 chip colors,
  `ExperimentalLayoutApi`). Long version strings wrap to another chip line / grow the row
  taller instead of clipping. CI building (`28299970224`).

**Design exploration (HTML mockups, rendered via headless chromium, saved to /sdcard/Download):**
- `docs/shortcut_card_layouts.html` — 6 layouts A–F (poster, square-icon+chips, hero banner,
  16:9 spec grid, two-tier stat strip, current-for-comparison).
- `docs/shortcut_card_layouts_dense.html` — 6 denser layouts G–L that ALSO show renderer
  (OpenGL/Vulkan/SurfaceFlinger), frame-gen (off/bionic/lsfg), audio (ALSA/PulseAudio) and
  x86 backend (FEXCore/Box64/wowbox64 + box64/FEX preset). Real values pulled from arrays.xml
  + ShortcutsScreen.
- `docs/shortcut_card_L_final.html` — **user likes layout L** (bright primary chips
  renderer·DXVK·frame-gen + muted secondary line driver·VKD3D·backend), **audio dropped per
  user so the secondary line fits one row / card is shorter**. Resolution moves into subtitle.

**NEXT:** user wants to SEE the layout A build on device first, but LIKES L → likely wire L
(swap the FlowRow chip cloud for L's two-tier primary/secondary) on the same branch. L needs
3 more shortcut extras resolved in ShortcutItem: `renderer`, `frameGenEngine`, `emulator`
(+ box64Preset/fexcorePreset). Keys all confirmed present.

---

## (legacy) Star-Compose

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

## ⚠️ WORKFLOW RULE — save before device tests / heavy jobs

We **device-test on the same physical device that hosts the working session** (PRoot/Termux + the app under
test are on one device). A device test, app install, screenshot/diff batch, or large agent/workflow can OOM and
crash the session, losing any un-saved context. **Always flush memory + this progress log + commit BEFORE running
a device test or heavy/memory-load job, and update continuously — not just at the end.** Memory + this log are the
durable checkpoint; the live session is volatile.

---

## 2026-06-27 (latest) — 🚀 Release 1.9.2 (stable patch)

Opacity fix device-confirmed working by user → cut **1.9.2** stable patch. Bumped `app/build.gradle`
versionCode 30→31, versionName 1.9.1→1.9.2 (`84b6bc1`), pushed main, dispatched `release.yml`
(run `28295471682`) with tag `1.9.2`, title `Bannerlator 1.9.2`, make_prerelease=`false`
(→ prerelease:false + make_latest:true). Workflow builds all 3 release APKs + generates/attaches
update.json (vc31) so the in-app updater offers it to stable users.

**What 1.9.2 ships (since 1.9.1):** full Vulkan effect suite — P1 SGSR + FSR1 upscalers (`5f5a4a0`),
P1b sharpen + render-scale, P1c CAS + fake-HDR + sharpness sliders, P2 FXAA/Toon/Color/CRT/NTSC screen
effects; Native-Rendering ↔ presets mutex; Linear default scaling mode; on-screen-controls overlay-opacity
drop-shadow fix (`1d9439e`). Plain numeric tag = stable per the versioning hard rule (patch X.Y.Z allowed
on explicit user request).

**✅ PUBLISHED — release run `28295879200` succeeded.** 1.9.2 is **Latest**, prerelease=false, all 3 flavor
APKs + `update.json` (vc31) attached → in-app updater offers the OTA to 1.9.1 (vc30) installs. Release body
rewritten to the polished 1.9.1-style layout (logo → ✨What's New → 📥Downloads → 🙏Credits → changelog)
with a full **graphics credits table**: SGSR ([SnapdragonStudios/snapdragon-gsr](https://github.com/SnapdragonStudios/snapdragon-gsr), BSD-3),
FSR/Sharpen ([GPUOpen-Effects/FidelityFX-FSR](https://github.com/GPUOpen-Effects/FidelityFX-FSR), MIT),
CAS ([GPUOpen-Effects/FidelityFX-CAS](https://github.com/GPUOpen-Effects/FidelityFX-CAS), MIT),
FSR-Fit/compositor blueprint ([utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative), GPL-3.0 — approach reimplemented, not copied),
and HDR/FXAA/Toon/CRT/NTSC/Color (upstream Winlator-Ludashi GLES2 effects ported to Vulkan). Attributions
sourced from the bundled shader headers at `app/src/main/cpp/winlator/*.frag`, which retain their upstream
license text. ⚠️First run `28295471682` FAILED at the update.json step — `release_notes` had literal
double-quotes that broke the bash `NOTES="..."` assignment (exit 127); re-ran with shell-safe plain notes.

**📝 Release-copy accuracy pass (post-publish body edits, no rebuild).** User flagged the marketing line
("only Winlator fork with both real spatial upscalers and a complete post-processing chain on Vulkan —
previously all OpenGL-only"). Verified in source against the other forks and corrected it:
- **GameNative** = FSR1-only on the Vulkan compositor (the blueprint we built on).
- **WinNative** (`/home/claude-user/winnative`) = **SGSR-only** on its Vulkan compositor (`cpp/winlator/vk/shaders/sgsr1.frag`
  + `SGSRUpscaler.java`) **plus an effect chain broader than ours** (sharpen/CRT/HDR/NTSC+NTSC2/Toon/ColorAdjust/
  ColorGrade/ColorBlind/Vivid/Scanlines/Pixelate/Natural); its `fsr.glsl` is only in the cnc-ddraw wrapper, not the compositor.
- So "real upscaler on Vulkan" and "full effect chain on Vulkan" are **NOT** unique to us; only **both SGSR *and* FSR1
  together** on the default path is. Also the upscalers were brand-new, not "previously OpenGL-only" (only the effects were).
- Rewrote the 1.9.2 intro: dropped the superlative, credited GameNative as FSR-on-Vulkan pioneer, noted SGSR exists in
  other Pluvia forks, claimed only the verified differentiator (both upscalers together). Also split What's New so
  **Render scale (supersampling)** is labeled *set before launch* (container/shortcut) vs the drawer-live upscalers/effects.
  Applied via `gh release edit 1.9.2 --notes-file` (APKs/update.json/Latest unchanged).

---

## 2026-06-27 — Native-mutex merge + on-screen controls opacity shadow fix

**1. Native-Rendering ↔ presets mutex MERGED to main.** User device-tested the latest `feat/vulkan-native-mutex`
build and confirmed it good. Fast-forwarded `main` `506ac6a`→`1c9c576` (`3ed78bb` mutex + `1c9c576` toast
black-box fix + Linear default scaling mode), pushed `origin/main`, deleted the feature branch local + remote.
The full Vulkan graphics program (P1 / P1b / P1c CAS+HDR / P2 effects / native-mutex) is now all on main.

**2. On-screen controls opacity bug FIXED** — `app/.../inputcontrols/ControlElement.java`, commit `1d9439e` on
main, CI run `28294667670` ✅ GREEN (all 3 flavors). **Device-test PENDING.**
- *Symptom (user, device screenshots 100% vs 6%):* at low Overlay Opacity the A–F keyboard strip fades fully, but
  the 4 compact keys MRB/BKSP/SPACE/ENTER keep a solid blue filled square while only their label text fades.
- *Root cause (pulled both screenshots to confirm):* NOT the fill paint — the GameHub `fillColor` already tracks
  `gameHubDim`. It was the **drop shadow**: the BUTTON draw path calls `paint.setShadowLayer(..., 0x401C85FE)`
  (hardcoded blue, alpha `0x40`) before the fill, and that shadow alpha never scaled with opacity. At low opacity
  the fill/stroke/text vanish but the blue glow persists — on the compact `SQUARE` keys it reads as a solid blue
  background; on the wide `ROUND_RECT` pills (A–F) it smears out and looks invisible. That asymmetry = the bug.
- *Fix:* added `int shadowColor = Color.argb((int)(0x40*gameHubDim*effectiveOpacity),0x1C,0x85,0xFE)` and used it
  in both `setShadowLayer` calls (trigger + non-trigger BUTTON paths). 0% opacity now truly vanishes. Only the
  BUTTON case has a shadow; STICK/D_PAD/TRACKPAD/RANGE_BUTTON unaffected.
- *Next:* CI green → install → device-test opacity at low values across both key shapes.

---

## 2026-06-27 — Phase 2: remaining GL screen effects → Vulkan post chain

**Branch `feat/vulkan-effects-p2`** (off `main` `71dceca`), commit `5dfcdbf` + fix `77c6b76`. Builds on the
now-merged P1/P1c Vulkan post-process framework. NOT merged.

**Device test (space scene):** all 5 effects work individually — Color/Brightness (washes out at 95), Toon
(edge outlines + posterized), CRT (RGB chromatic-aberration on stars), NTSC (horizontal chroma bleed). NTSC+CRT
2-effect combo renders clean. **Bug found + fixed (`77c6b76`):** with a *scaling* mode (SGSR/FSR/Sharpen/downscale)
active, the screen effects were silently dropped (toggles on, image clean) — `recordUpscalePasses`' local `fxOn`
only checked `cas||hdr`, so the scale pass treated itself as final and skipped the chain. Now includes all 7 effects.
(This also fixes P1c CAS/HDR, which had the same gap on the scaling path.) **Fix rebuilt: branch tip `aed6cde`, CI build `28290066760` ✅ green (all 3 flavors).** **Fix device-verified on the
space scene:** SGSR + CRT now shows the CRT fringing/scanlines on the upscaled image (was dropped pre-fix), and
SGSR + NTSC + CRT (3-deep chain) renders both effects cleanly — no black screen/corruption. **Phase 2 is
device-proven** (Color/Toon/CRT/NTSC visually confirmed + the scaling-chain fix; FXAA wired, subtle by nature).
Branch tip `0593385`. **Merged to main (ff `eee9d57`), branch deleted; artifacts build `28291121833` ✅ green.**

**Phase 3 (ReShade-style `.fx` engine) DROPPED 2026-06-27.** The upscalers (SGSR/FSR/Sharpen/downscale) are
resolution-reconstruction passes wired into the compositor — not ReShade-style fixed-res filters, so they stay hardcoded
(the headline differentiator). The cosmetic effects (CAS/HDR/FXAA/Toon/Color/CRT/NTSC) are fully covered by the curated
hardcoded set with better perf/reliability on mobile/Turnip. A data-driven engine only pays off for user/community
extensibility without rebuilds, which isn't a goal. **Effects work is complete.** Remaining: P4 (lean native-GLES2 GL path)
+ the queued overlay-opacity button-fill bug. The Vulkan renderer now carries the full stack: SGSR/FSR/Sharpen upscalers +
CAS + fake-HDR + FXAA/Toon/Color/CRT/NTSC, all on the default path.

Ported the 5 remaining GL-only screen effects onto the **same** Vulkan post chain as composable controls,
at full GL parity:
- **Color** — Brightness / Contrast / Gamma sliders (replicates `ColorEffect.java`: brightness `clamp(s/100,-1,1)`,
  contrast `clamp(s/100,0,2)` so negative contrast is a no-op like GL, gamma `clamp(0.1,5)`; neutral 0/0/1 ⇒ pass skipped).
- **FXAA · Toon · CRT · NTSC** — toggles (GL shader math ported verbatim).

**Locked canonical chain order** (best results): `composite → scale (SGSR/FSR) → FXAA → Toon → Color → CAS → HDR
→ NTSC → CRT → swapchain` — AA first, stylize/grade the clean image, sharpen, bloom, then the output-medium
emulation last (NTSC analog signal, then the CRT tube). The fixed 2-effect chain from P1c was generalized to an
ordered 7-effect list, ping-ponging `fx1`/`fx2` (2 buffers suffice); the last active effect writes the swapchain,
earlier ones write `offscreenRenderPass` fx targets (auto-barriers). Engages even at scaling mode 0/1/2.

5 new shaders (`fxaa/toon/color/ntsc/crt.frag` + compiled `*_frag.h`), 10 new pipelines (Off/Swap per effect),
all PC structs ≤ 28 B (≤ the 88 B shared range, unchanged). Plumbing mirrors P1c: 5 JNI entry points,
`VulkanRenderer.setScreenEffects(b,c,g,fxaa,toon,crt,ntsc)`, `XServerDialogState.onVulkanScreenEffectsApply`,
a "Screen Effects" subsection in the Vulkan drawer block, and launch-seed + callback wiring in
`XServerDisplayActivity`. **No-op safety:** with zero new effects enabled, control flow is identical to current main
(no regression to shipped P1/P1c behavior). Touched: `{fxaa,toon,color,ntsc,crt}.frag`(+`.h`),
`VulkanRendererContext.cpp/.h`, `vulkan_jni.cpp`, `VulkanRenderer.java`, `XServerDialogState.kt`, `XServerDrawer.kt`,
`XServerDisplayActivity.java`. `docs/render_upgrades_report.html` already shows P2 in-progress + the locked chain order.

---

## 2026-06-27 — Vulkan CAS + fake-HDR + sharpness sliders (Phase 1c) + on-device upscaler proof

**Branch `feat/vulkan-cas-hdr`** (off `feat/vulkan-upscaler-sgsr-fsr` tip `80c6d56`), commit `4fecbc6` +
docs `181500c`. **CI build `28287630767` ✅ GREEN** (standard/ludashi/pubg). NOT merged. Device-test pending.

**On-device upscaler verification (the resume from the smooth-blob test).** Re-ran the frozen-frame A/B on the
**DX11 "space" scene** (textured planet + coastlines + dense starfield), 720p container → 1080p panel, build 1.9.1:
the scaling modes are now clearly and usefully distinct (RMSE vs None ≈ 6× the smooth blob's <0.4%):

| Mode | RMSE vs None | On screen |
|---|---|---|
| Nearest | 0% (≡ None) | hard stair-step jaggies on the planet limb (point) |
| Linear | 1.79% | jaggies smoothed but whole frame softened |
| **SGSR** | 1.75% | edges cleaned, stars/detail stay crisp — sweet spot |
| **FSR / FSR-Fit** | 1.82% | same family as SGSR |
| **Sharpen** | 2.46% | brighter/punchier (RCAS), keeps base jaggies |

The earlier "no visible difference" was **bad test content** (smooth SDF blob, no high-freq edges), not a bug —
spatial upscalers are an edge-cleanup whose effect grows with the upscale ratio. "More RMSE" ≠ "better"; fidelity to
a native render is the goal. This test motivated the P1c sharpness sliders (strength was locked at 0.25).

**Phase 1c — three new composable Vulkan post controls + one rename:**
- **CAS toggle + "CAS Sharpness" slider (0–100, default 60)** — the same AMD CAS the GL path uses (`cas.frag`
  ported from `FSREffect.java`), layered on top of any scaling mode, runs even at native res.
- **HDR toggle** — the same fake-HDR (`hdr.frag` ported from `HDREffect.java`, HDRPower 1.30, binary).
- **"Sharpness" slider** for scaling modes SGSR/FSR/FSR-Fit/Sharpen — unlocks the real upscaler sharpness
  (was hard-coded 0.25 RCAS stops; default slider 75 keeps 0.25). SGSR `EdgeSharpness` moved const→push-constant.
- **GL "SGSR" → "Sharpen (CAS)"** — the GL toggle was never SGSR; it's AMD CAS sharpening at native res. Label-only.

Pipeline: `recordUpscalePasses` rewritten to chain `composite → scale → CAS → HDR → swapchain`, with optional
`fx1`/`fx2` intermediates ping-ponged through `offscreenRenderPass` (auto-barriers via its baked subpass deps; no
hand-rolled `vkCmdPipelineBarrier`). Cross-binding the swapchain-render-pass scale pipelines into an
`offscreenRenderPass` fx target is legal — both passes use `VK_FORMAT_R8G8B8A8_UNORM` (format-compatible). All PC
structs ≤ 88-byte range. Touched: `cas/hdr/sgsr.frag` (+ compiled `*_frag.h`), `VulkanRendererContext.cpp/.h`,
`vulkan_jni.cpp`, `VulkanRenderer.java`, `XServerDialogState.kt`, `XServerDrawer.kt`, `XServerDisplayActivity.java`.
Drawer-only / session-live (no DB persist), like the scaling mode. `docs/render_upgrades_report.html` updated with
the device-test results + P1c.

---

## 2026-06-27 — Vulkan spatial upscalers + sharpen + supersampling (Phase 1/1b)

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

---

## 2026-06-28 — NIS device-test checkpoint (feat/deband-nis)

**Status:** Vulkan NIS ✅ device-proven by user ("it works on Vulkan"). OpenGL/GL NIS ⏳ device-test in progress (Adreno runtime-compile risk = the open question).

**NIS sharpness slider (resolved from committed source, this session):**
- CONTINUOUS on BOTH renderers — every value 0–100 is live (GL `XServerDrawer.kt:463` steps=-1 for mode 7; VK `:547` no steps arg). Not notched.
- GL & VK share the SAME NVIDIA `NVScalerUpdateConfig` constants (stock, untuned) → identical strength on both:
  - slider 0 ≈ OFF (max USM strength ~0.03, overshoot limit ±20%)
  - slider 50 = NVIDIA neutral default (1.6, ±50%)
  - slider 75 = our seeded default (~2.16, ±69%)
  - slider 100 = hard max (~2.73, ±87% — halo onset)
- Input is `clamp(sharpness,0,1)`; 100 cannot be exceeded without editing shader constants. Curve is piecewise-linear with one slope kink at 50 (steeper above), plus soft floors ~17 & ~37 at the low end.

**Side-finding (NOT NIS — cleanup candidate, UNFIXED):** CAS/Sharpen slider snapping is inconsistent across renderers.
- OpenGL: Scaling-mode "Sharpen" (mode 6, `glUpscaleSharpness` steps=3) + standalone "Sharpen (CAS)" toggle (`sgsrSharpness` steps=3) → snap to 5 notches {0,25,50,75,100}.
- Vulkan: "Sharpen" mode 6 (`upscaleSharpness` :547) + standalone "CAS" toggle (`casSharpness` :568) → continuous.
- Fix later: add `steps=3` to the two VK sliders, or drop `steps` on the two GL ones (lean continuous-everywhere; GL keeps notches only for the "stop 0 = OFF" guarantee).

**UPDATE 2026-06-28 (later):** ✅ OpenGL NIS DEVICE-PROVEN — user: "it worked like vulkan". Adreno GL shader-compile risk CLEARED. NIS now proven on BOTH renderers, matching looks. (Install: NIS vc32 needed `pm install -r -d` over the bionic-fg vc33 build via root bridge.) NIS feature merge-ready; debanding (other half of branch) may want a quick dark-gradient check before merge.

**RESUME POINT (2026-06-28, user driving home):** Step 1 NIS = DONE, device-proven both renderers. NEXT SESSION = **Step 2: VRR / refresh-rate matching** (full plan in roadmap memory file). Open optional threads to fold in: (a) debanding dark-gradient check + merge `feat/deband-nis` to main; (b) CAS/Sharpen snapping cleanup (GL 5-notch vs VK continuous). Branch tip `cc3361f`, pushed, unmerged.

---

## 2026-06-28 — AMA bot: fix question form + switch to auto-answer-every-issue

**Context:** User reported a friend submitted a test question but it never got answered — came in with no label, and manually adding `ama-request` after the fact did nothing.

**Root causes found (two):**
1. **Form template broken since day one.** `.github/ISSUE_TEMPLATE/ask-the-ai.yml` used the singular key `validation:` instead of GitHub's schema key `validations:`. GitHub strict-rejects invalid form templates → the `?template=ask-the-ai.yml` link silently fell back to the BLANK "Create new issue" page (no `Q:` title, "No labels") → submissions carried no `ama-request` → bot never fired. Fixed singular→plural (`62a223e`). Template now parses clean (verified with js-yaml).
2. **After-the-fact labeling is a no-op.** The `labeled` trigger only listens for the `question` label, not `ama-request` — so manually adding `ama-request` later never triggers anything. (Adding `question` does — used it to force-answer the friend's #34.)

**Decision (user chose): drop the fragile form/label dependency entirely → auto-answer EVERY new issue.** GitHub's new mobile issue-creation UI made the form/chooser unreliable across devices, so relying on it was the real friction.

**Workflow change (`035ef08`):**
- Trigger: job now runs on ANY `issues:[opened]` (skips bot-opened + already-`answered`); `labeled`+`question` kept as a maintainer force-rerun on older issues.
- Per-user daily counter rewritten to count `label:answered author:X created:>=dayAgo` with `>=` (new issues no longer carry `ama-request`, so the old counter would never fire).
- Now prepends the issue **Title** to the question (bug reports often put the ask in the title with an empty body).
- Rate limits unchanged: 5/user/day (maintainers exempt) + 200/month.
- Side effect (user accepted): bot now also answers bug reports / feature requests, not just questions.

**Docs (`a394c2b`):** README badge + 3-steps now point to plain `/issues/new` ("Open an issue"); maintainer notes drop the form-only/`ama-request` wording (only `answered`+`question` labels needed now).

**Proven:** plain unlabeled test issue #35 ("Native Rendering+") auto-answered in ~1–2 min with accurate citations (`XServerDisplayActivity.java:1936-2007`, `XServerDrawer.kt:633`) and got `answered`; closed as test. Also confirmed `OPENCODE_AUTH` secret is set and #34 answered correctly. No stale "No answer generated" comments remain on #32–#35.

**Commits:** form fix `62a223e`, workflow `035ef08`, README `a394c2b` — all on `main` via API.

---

## 2026-06-29 — 2.1 stable cut + GL Native Rendering P0 (scanout extraction)

### ✅ 2.1 STABLE released
Cut `2.1` (versionCode 33, plain tag, `prerelease:false`/`make_latest:true`, releases/latest→2.1). Bumped `app/build.gradle` first (`282a674`) since `release.yml` reads version FROM there. Successful run `28341709108`; 3 APKs + `update.json` vc33 attached. Shipped: **VRR / refresh-rate matching** (Auto match-FPS + manual 60/90/120/144 snap slider, all 3 renderers), **NIS** upscaler (NVScaler mode 7, Vulkan), **debanding** (Vulkan compositor, strength slider), **Task Manager Vulkan/ASR fix**, **install-progress 98%→100% fix**, AIO Graphics Test v1.6.1 bundled.
- ⚠️ **Release-notes trap (recurring):** first dispatch `28341637256` had a literal `"` in the notes → would break `release.yml`'s `NOTES="${{ inputs.release_notes }}"` bash line (the 1.9.2 failure). CANCELLED before the build finished (no tag leaked), re-dispatched with curly-quote-safe notes. **Lesson: pass release notes via a file / no straight `"`.** Final body replaced post-publish via `gh release edit --notes-file` for the clean 2.0-style layout.
- README "What's New in 2.0"→"in 2.1" rewritten + Full Features updated (NIS/deband/VRR) — `d019bc3`. Verified each feature is real in renderer code before writing (deband `setDeband`, NIS NVScaler mode 7, `matchRefreshRate`).

### ▶️ GL Native Rendering — P0 (renderer-neutral scanout extraction) — CI-GREEN, device-test owed
Goal: bring **Native Rendering** (direct scanout via `SurfaceControl`→HWC overlay) to the **OpenGL** renderer (Vulkan-only today). Plan committed `docs/GL_DIRECT_SCANOUT_PLAN.md` (`228319f`). Phased P0–P6.

**P0 (delegated to graphics-vulkan-engineer, branch `feat/p0-scanout-extract`, off main, pushed, NOT merged):** behavior-preserving extraction of the scanout impl into a standalone renderer-neutral `app/src/main/cpp/scanout/ScanoutContext.{h,cpp}` (zero Vulkan/GL/EGL). Methods moved (initScanout→initFromWindow, scanoutSetBuffer→setBuffer, applyScanoutBuffer→applyPendingCursor, dst/cursor setters) + state + SC_CREATE/ST_* macros + 9 dlsym fn ptrs. **Cursor threading split:** ScanoutContext gets pure setters + `applyPendingCursor()`; `needsRender`/`dirtyCV`/`cursorMoved` STAY in VulkanRendererContext, render loop calls `scanout.applyPendingCursor()` at `VulkanRendererContext.cpp:1485`. `VulkanRendererScanout.cpp` → thin forwarders. **`vulkan_jni.cpp` UNCHANGED → libvulkan_renderer ABI preserved.** Dropped 3 dead members; 1 log-only line touched. **CI run `28342956403` GREEN all 3 flavors.** CI-green only — NOT device-proven.

**RESUME POINT / NEXT ACTION (user is about to test):** **P0 GATE = Vulkan-native REGRESSION device test.** Install the `feat/p0-scanout-extract` build (CI `28342956403`, release_number `p0-scanout`), open a **VULKAN** container with **Native Rendering ON**, run a DXVK game (AIO DX11 SPACE scene = known-good). Confirm UNCHANGED vs before: presents correctly, colors right, cursor right (pos/hotspot, not double-drawn), no black screen, clean teardown on rotate/background. **This must pass before P1.** Then P1 = new `libdirect_scanout.so` + `DirectScanout.java` (dormant, no wiring). Project memory: `project_bannerlator_gl_native_rendering.md`.

**UPDATE — P0 device check + dumpsys SurfaceFlinger analysis (2026-06-29):** Tested the `p0-scanout` build on device "Pocket FIT" (Adreno 750 / SD8Gen3, Vulkan|DXVK), AIO DX11 cube test, Native Rendering OFF vs ON.
- **Renders correctly both ways** (colors/geometry/cursor fine, no black screen) → **P0 extraction looks behavior-preserving** (regression-clean; strict A/B vs pre-P0 2.1 Native-ON not run, but the feature works on the refactored build).
- **`dumpsys SurfaceFlinger` (root bridge, game in foreground) CONFIRMS overlay promotion WORKS:** `winlator_game_buf#66438` = **composition type=DEVICE (2)** = HWC hardware overlay (parent `winlator_game#66434` is the empty container SC). Game buffer `1280x702 BGRA_8888`, display controller SCALEs it ~1.5x→1080p **for free**. So direct scanout offloads the GPU from compositing, as intended. (3 layers total on DEVICE overlays; 128 idle/INVALID.)
- **User Q "why is FPS lower with Native ON?"** OFF=766fps/29% GPU/86°C/1.3ms vs ON=584fps/27% GPU/96°C/1.7ms. **Not a failure** — overlay is promoting. Direct scanout couples the guest to a real vsync-paced, triple-buffered overlay queue with buffer-release backpressure → the guest can't sprint ahead; the OFF path lets it race to 766 into Winlator's looser offscreen compositor, but **most of those frames are never displayed**. GPU load staying low = GPU offloaded (the win); CPU temp up = per-frame `ASurfaceTransaction`/fence cost. **Native Rendering is a latency/power feature, not a more-FPS feature**; an uncapped microbench is the worst way to measure it (cap to panel 60/120 and compare power/temp/latency at equal displayed FPS to see the benefit). Same backpressure tradeoff will apply to the GL port at P4.
- **NEXT: merge P0 → start P1** (`libdirect_scanout.so` = `scanout/directscanout_jni.cpp` + `ScanoutContext.cpp`, links log/android/dl/atomic only, NO vulkan; + `DirectScanout.java`; dormant, not wired).

**UPDATE — GL Native Rendering P0 merged, P1 done+merged, P2 in flight (2026-06-29):**
- **P0 MERGED** to main (merge `9575164`, no-ff, branch deleted) — renderer-neutral `ScanoutContext` extraction, Vulkan-native overlay device-confirmed (see above).
- **P1 DONE + CI-green + MERGED** (graphics-vulkan-engineer; CI `28344514069`; merge `51ccb3a`, branch deleted). New **`libdirect_scanout.so`** (CMake target `direct_scanout`: `scanout/directscanout_jni.cpp` + `ScanoutContext.cpp`, links **log/android/dl/atomic only — NO vulkan/adreno**; ScanoutContext.cpp object-duplicated across this + vulkan_renderer, intended). JNI = heap `ScanoutContext*` as jlong, 1:1 forwarders; cursor applied **inline** (GL model, no needsRender/dirtyCV). New **`DirectScanout.java`** — lifted child-SC builder/teardown/`applyScanoutSwapTransform`(R/B-swap)/`releaseScanoutSurfaces` from `VulkanRenderer.java:614-679`/`:168-300`, generalized `enable(SurfaceControl parent,...)` to take parent SC as arg. **VulkanRenderer.java byte-for-byte unchanged; nothing wires DirectScanout yet → behavior-neutral, no device test needed.**
- **P2 IN PROGRESS** (main session, one-liner): `XServerView.getSurfaceControl()` (`:153`) now returns `glSurfaceView.getSurfaceControl()` for GL too (was null; GLSurfaceView extends SurfaceView → inherits it, API29+). Vulkan return unchanged. **Behavior-neutral now** — only callers are `VulkanRenderer.java:173`/`:623` (Vulkan-only path); no GL path calls it until P3, so the "non-null SC on GL" device check lands at P3. Branch `feat/p2-gl-surfacecontrol`, CI run `28345217160` building, NOT merged.
- **NEXT: P2 CI-green → merge → P3** = GLRenderer scanout lifecycle (`nativeMode`/`setNativeMode`/`setInitialNativeMode`, `DirectScanout.enable/disable`, dst + cursor, implement GL `setRenderingEnabled`→`xServer`; toggle wiring §4 activity+drawer). **P3 = first GL device validation** (§3: non-null GL SC + cursor SC composites ABOVE GL content). P4 = per-frame game push (feature lights up). Delegate P3 to graphics-vulkan-engineer.

---

## 2026-06-29 — Controller bindings persistence + scrollable profiles (#37) MERGED

**✅ DEVICE-PROVEN (Odin 2) + MERGED to main** (merge `d3ee2d5`, no-ff).
- `2f5cf0a` persist controller bindings + harden profile import (issue #37)
- `4867789` make the Download Profiles list scrollable
- Touches: `Binding.java`, `ControlsProfile.java`, `InputControlsScreen.kt`.
- Build: manual "Any branch compilation" run `28365146389` GREEN (all 3 flavors). User device-tested → working.
- No release cut (still 2.1 stable). Branch `fix/odin2-controller-bindings` can be deleted.

---

## 2026-06-29 — GL Native Rendering P2 MERGED, P3 started

**P2 MERGED** to main (merge `460725f`, no-ff; branch `feat/p2-gl-surfacecontrol` deleted local+remote). One file: `XServerView.getSurfaceControl()` now also returns `glSurfaceView.getSurfaceControl()` (GLSurfaceView extends SurfaceView → inherits it, API29+). Behavior-neutral (only Vulkan path calls it today). CI run `28345217160` was green.

**P3 STARTED** — delegated to graphics-vulkan-engineer. Scope = GLRenderer scanout LIFECYCLE only (no per-frame push, that's P4): `nativeMode`/`setNativeMode`/`setInitialNativeMode`, `DirectScanout.enable/disable`, dst + cursor SCs, implement `setRenderingEnabled`→`xServer`, toggle wiring §4 (activity + drawer). Goal: enabling GL native builds the child SCs + shows cursor SC; game still GL-composited. **P3 = first real GL device validation** (cursor SC composites ABOVE GL content). Plan `docs/GL_DIRECT_SCANOUT_PLAN.md`.

**UPDATE — P3 CI GREEN, device-test owed (2026-06-29):** Branch `feat/p3-gl-scanout-lifecycle` (`f414e38`), CI run `28367896129` GREEN all 3 flavors (build 14m54s). +184 lines / 3 files: `GLRenderer.java` (lifecycle: setNativeMode/setInitialNativeMode/isNativeMode, enableScanout/disableScanout = game SC layer1 + cursor SC layer2 above GL, setRenderingEnabled→xServer, sendCursorToScanout skips GL cursor pass, updateScanoutDst, onSurfaceDestroyed/forceCleanup teardown), `XServerDisplayActivity.java` (toggle wiring in drawer onChange + launch path: setInitialNativeMode from container.isRendererNative() + setSwapRB), `XServerView.java`. NO per-frame push (P4). **NOT merged — P3 is the device gate.** Device-test (GL container, Native ON): cursor SC composites ABOVE GL content (not double-drawn, right pos/hotspot); `dumpsys SurfaceFlinger` shows child game+cursor SCs; rotate/app-switch/exit no leak/black; Native OFF = regression-clean. Merge only after device pass.

**UPDATE — P3 DEVICE-VERIFIED in-game (2026-06-29, device "Pocket FIT" Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube):** `dumpsys SurfaceFlinger` (game in fg, Native Rendering ON) confirms the P3 lifecycle BUILT the child SurfaceControls under the GLSurfaceView SC: `winlator_game#3181` + `winlator_game_buf#3185` + `winlator_cursor#3183` + `winlator_cursor_buf#3186`(16x16) all present. **Cursor SC is a separate composited layer** (winlator_cursor_buf, CLIENT, ROT_90, 1064,0-1080,16 = top-right) ABOVE the GL SurfaceView(#3165) → cursor-on-own-SC WORKS, no double-draw. **Game still GL-composited** (the GL SurfaceView carries the full game image; `winlator_game_buf` NOT in the active HWC composited set = game SC is bufferless, no per-frame push) = EXACTLY correct for P3. No overlay promotion of the GAME yet (that's P4). No black screen, renders fine. ✅ **P3 DEVICE GATE PASSED.**

**📌 User finding — "screen effects still work with Native Rendering ON" (screenshot: FXAA+CRT+Toon+NTSC all checked + Native toggle ON + effects visibly applied to the cube):** EXPECTED in P3, NOT a bug. P3 only moves the CURSOR to its own SC; the GAME still flows through the GL renderer → EffectComposer → onDrawFrame, so effects still apply. The "effects can't work with native rendering" end-state only kicks in at **P4** (game frame pushed straight to HWC overlay, bypassing GL entirely) + **P5** (grey-out the effect toggles when native on). So P3 = effects coexist with native; that flips at P4.

**▶️ NEXT: P3 passed device gate → MERGE `feat/p3-gl-scanout-lifecycle` → P4** (per-frame game push = feature lights up + effects-bypass behavior appears).

**UPDATE — P4 CI GREEN, device-test owed (2026-06-29):** Branch `feat/p4-gl-perframe-push` (`d44b560`), CI run `28369663007` GREEN all 3 flavors (artifacts `Bannerlator-p4-gl-perframe-{standard,ludashi,pubg}`). +70 lines, 3 files: **GLRenderer.java** new `presentScanout(Window,Drawable)` (lift of VulkanRenderer.onUpdateWindowContent AHB body: synchronized(content.renderLock), GPUImage g=content.getTexture(), ahbPtr=g.getHardwareBufferPtr(), fence=g.unlock(), scanout.present(ahbPtr,rx,ry,w,h,fence), g.lock(), refreshDataFromTexture(), first-delivery pause via xServer.setRenderingEnabled(false)+xRenderingPausedForScanout, hudFrameTick.accept) + `setHudFrameTick(IntConsumer)`. **PresentExtension.java** GL-native FLIP branch before final else (mirrors Vulkan isNative: content.setTexture(pixmap GPUImage)+setDirectScanout(true)+sendCompleteNotify FLIP+presentScanout+emitIdleNotify). **XServerDisplayActivity.java** glr.setHudFrameTick wired (updates frameRating/frameRatingHorizontal/perfHud on frameRatingWindowId). Reuses DirectScanout.present()/isGameFrameDelivered() from P1. Vulkan/ASR untouched. **NOT merged — P4 is the full-feature device gate.** Device-test (GL container, Native ON, DXVK game): game presents correct (no black, colors right=swapRB, letterbox); `dumpsys SurfaceFlinger` GAME layer on HWC overlay (composition type=DEVICE) GL layer skipped (= the win vs P3 CLIENT/GL); cursor correct not double-drawn; HUD ticks (not frozen); rotate/bg no leak; effects now silently stop applying (expected, P5 greys toggles). Merge after device pass.

**UPDATE — P5 CI GREEN (stacked on P4), device-test owed (2026-06-29):** Branch `feat/p5-gl-effect-exclusion` (`64b7cfb`) OFF `feat/p4-gl-perframe-push` (`d44b560`) — so ONE test build = P4 per-frame push + P5 grey-out. CI run `28373207641` GREEN all 3 flavors (`Bannerlator-...{standard,ludashi,pubg}` — note artifact name prefix per agent). +87 lines, 2 files: **XServerDisplayActivity.java** — `disableNativeRenderingForPreset()` got a GLRenderer arm (setNativeMode(false)); new `resetGlEffectsForNative(GLRenderer)` (Direction B, mirrors resetVulkanPresets: filter→1/setUpscaler(0), remove FSREffect+HDREffect, applyScreenEffects neutral, deband off, reset XServerDialogState flows glUpscalerMode/Sharpness/sgsr*/hdrEnabled/se*/deband* — touches EffectComposer+StateFlows only, never apply callbacks → no re-entry); Native toggle GL arm calls resetGlEffectsForNative on enable; Direction A wired into onGlUpscalerApply(mode≥3)/onSgsrUpdate(enabled||hdr)/onScreenEffectsApply(non-neutral)/GL onDebandApply(enabled) → each calls guarded disableNativeRenderingForPreset(). **XServerDrawer.kt** — GraphicsContent derives `glEnabled = !nativeRenderingEnabled` (existing reactive XServerDrawerState flow, no new flow) + dimmed glHeaderColor; passes glEnabled to UpscalerModeButtons/scaling+SGSR IntSliders/Sharpen(CAS)+HDR ToggleRows/DebandControls/Screen-Effects sliders/SeShaderToggles; added `enabled` param to IntSlider+DebandControls (Vulkan callers default true → unchanged). Vulkan/ASR + P3/P4 untouched. **NOT merged.** Device-test: GL+Native ON → all GL effect+scaling controls greyed/disabled (~0.4 alpha) + headers dim; toggle Native live un-greys instantly; Direction A (effect→native off+toast)/B (native→effects reset); Vulkan unchanged. ⚠️Deviation: grey-out applied to GL block only (Vulkan resets-on-enable but doesn't grey — plan phrasing "matching Vulkan greys" was inaccurate; followed explicit GL-grey requirement).

**▶️NEXT: device-test the combined P4+P5 build → if pass, merge `feat/p5-gl-effect-exclusion` to main (brings P4+P5 together) → P6 optional cleanup (fold Vulkan/ASR onto DirectScanout).**

**UPDATE — Combined P4+P5 DEVICE-TESTED (2026-06-29, Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube). Mixed result → overlay-promotion fix started.**
Driven via root bridge (toggle in-drawer, dumpsys OFF vs ON, drawer open vs closed):
- ✅ **P4 functional:** Native ON builds scanout SCs (winlator_SC_count 0→14): winlator_game/winlator_game_buf (game buffer 1280×702 ROT_90 → disp 0,0,1080,1920 fullscreen), winlator_cursor/winlator_cursor_buf (16×16). Game renders perfectly, colors correct (swapRB good), cursor on own SC. Toast "Native Rendering+ Enabled".
- ✅ **P5 grey-out:** toggling Native ON instantly dims+disables FXAA/CRT/Toon/NTSC + sliders (verified screenshot vs Native-OFF bright state).
- ❌ **THE BUG — no HWC overlay promotion:** Native OFF = GL SurfaceView is `composition type=DEVICE` (single fullscreen overlay, efficient). Native ON (even drawer CLOSED) = winlator_game_buf AND GL SurfaceView both **CLIENT** (GPU). Raw HWC = `composition: DEVICE/CLIENT` = SF requested overlay, HWC REJECTED → GPU fallback. So zero power/latency win; currently WORSE than native-off. Vulkan native promotes to DEVICE on same device/scene/scale → rejection is GL-specific.
- **Diagnosis:** gameSC IS opaque in both (DirectScanout.java:85-86 / VulkanRenderer.java:174-175) — NOT the cause. The game/cursor SCs are CHILDREN of the renderer's own SC (parent = xServerView.getSurfaceControl() = GLSurfaceView's SC). On GL the paused GLSurfaceView keeps its last opaque fullscreen buffer on the parent layer → SF can't drop it → overlay rejected. Classic hole-punch issue. Can't just hide the parent SC (children hide too).
- **▶️FIX STARTED** — branch `feat/gl-scanout-overlay-fix` off `feat/p5-gl-effect-exclusion` (graphics-vulkan-engineer, agentId a1c4b9fd82603ed55): investigate how Vulkan's base surface stays non-competing, then make the GL base layer transparent/hole-punched/bufferless when scanout active so SF can overlay-promote the opaque child game SC. Primary device gate = dumpsys winlator_game_buf = composition type=DEVICE, GL layer skipped.
- ⚠️ Native left toggled ON on the test container.

**UPDATE — GL overlay-promotion fix #1 (idle base layer) CI-GREEN, device-test owed (2026-06-29):** Branch `feat/gl-scanout-overlay-fix` (`b418e53` off P5 `64b7cfb`), CI run `28376490129` GREEN. +38 lines GLRenderer.java only. Fix = once first game frame reaches game SC, onDrawFrame renders ONE cleared frame (glClearColor 0,0,0,0) then early-returns every subsequent frame (no GL compositing of game/cursor); onPointerMove no longer requestRenders in native mode → base GLSurfaceView goes IDLE holding a single cleared buffer (mirrors Vulkan render-loop single clearing frame + ASR bufferless base). `xRenderingPausedForScanout` made volatile (X-thread write / GL-thread read). Theory: idle base lets SF occlusion-cull it so HWC promotes the opaque game SC to DEVICE. **CAVEAT: addresses BLOCKER #1 ONLY (competing base layer). Base still OPAQUE black (agent noted translucent-format fallback lever). Did NOT address BLOCKER #2 (SC-level ROT_90+scale that Adreno overlay pipes may reject) — libwinemu intel arrived after agent committed.** This build isolates hypothesis #1. **DEVICE GATE = dumpsys winlator_game_buf composition type CLIENT→DEVICE.** If DEVICE → #1 was the whole fix (don't touch orientation). If still CLIENT → blocker #2 confirmed → GameHub-style geometry rework (no SC-level rotate/scale, fit via setBuffersGeometry). ⚠️Agent also committed PROGRESS_LOG.md ON the branch (+36) → diverges from main, reconcile on merge.

**UPDATE — overlay-fix #1 (idle base) DEVICE-TESTED 2026-06-29: INSUFFICIENT, blocker #2 CONFIRMED.** GL container + Native ON + drawer closed, dumpsys: winlator_game_buf STILL = CLIENT (raw `composition: DEVICE/CLIENT` = HWC rejected overlay). Game renders fine / no black / no regression — but no DEVICE promotion. Game SC still carries `ROT_90` + source 1280×702 → disp 1080×1920 (rotate + 1.5× scale). So idle-base was necessary-but-not-sufficient. **Blocker #2 = Adreno HWC won't overlay a layer needing SIMULTANEOUS rotation + scaling** (matches libwinemu RE: GameHub does ZERO SC-level geometry, sizes via setBuffersGeometry+RGBA_8888). ▶️Agent a1c4b9fd82603ed55 resumed on same branch `feat/gl-scanout-overlay-fix` to do blocker #2: eliminate SC-level rotate+scale on the game SC (compare why Vulkan native IS overlay-eligible on this device but GL isn't). ⚠️Risky (orientation/fit). Device gate unchanged: winlator_game_buf CLIENT→DEVICE + game still correct.

**NOTE — interpreting "native ON feels different" (2026-06-29, important for the upcoming test):** User observes real latency/FPS difference Native ON vs OFF and asked if the overlay is already working. THREE distinct things change when Native turns on — don't conflate:
- **A. P5 toggles grey out** — pure UI, no perf impact unless an effect was active (confound: turning off an active effect changes FPS).
- **B. GL compositor pass is SKIPPED** — even with zero effects, Native-OFF does a per-frame GPU blit (game texture → GLSurfaceView + swap); Native-ON's `presentScanout` pushes the AHB straight to the game SC and idles the base, removing that GPU stage + changing present pacing (SurfaceControl queue vsync/backpressure). **This is a REAL, PARTIAL win and is what the user is measuring — it happens WHETHER OR NOT the overlay promotes.**
- **C. HWC overlay promotion (`DEVICE`)** — display controller does the final composite, GPU does nothing for it. **STILL NOT happening (dumpsys = CLIENT).** This is the big win blocker #2 targets.
Corrected an earlier overstatement ("pointless/regression"): B is a legitimate benefit on its own; C is the additional win still missing. **To verify C specifically: compare GPU load/power/temp at CAPPED equal FPS (e.g. lock 60) OFF vs ON — only C drops GPU load meaningfully; FPS alone is downstream of B and will differ regardless.** The `dumpsys` DEVICE-vs-CLIENT line is the authoritative overlay readout.

**TEST QUEUED (after blocker-#2 build finishes):** blocker #2 fix is building on `feat/gl-scanout-overlay-fix` (agent a1c4b9fd82603ed55, eliminate SC-level rotate+scale). When green → device-test the full thing: (1) dumpsys winlator_game_buf CLIENT→DEVICE (the gate), (2) game still correct orientation/fit/colors, (3) capped-FPS GPU/power A/B to quantify C, (4) cursor/HUD/lifecycle.

---

## ⏸️ RESUME-HERE CHECKPOINT (2026-06-29, before user device reboot)

**Active task: GL Native Rendering — overlay-promotion fix.** Session runs on the device → reboot kills it + the background CI watch (CI continues server-side).

**MERGED to main:** P0 (scanout extract) + P1 (libdirect_scanout) + P2 (GL getSurfaceControl) + P3 (GL scanout lifecycle). main tip = `7aaacaf` (docs only after the P3 merge `2fe3f10`).

**NOT merged — all on branch `feat/gl-scanout-overlay-fix` (tip `e036124`):** stacked P4 (`d44b560` per-frame push) → P5 (`64b7cfb` effect/scaling grey-out) → overlay-fix#1 (`b418e53` idle base) → overlay-fix#2 (`e036124` TRANSLUCENT base). ⚠️ Agent also committed PROGRESS_LOG.md ON this branch (+36) → diverges from main's PROGRESS_LOG; reconcile (keep main's) on eventual merge.

**Build in flight:** CI run **`28378890898`** for `e036124` (translucent base) — was BUILDING at checkpoint, status NOT yet confirmed. ON RESUME: `gh run view 28378890898 --json status,conclusion`; if green, artifacts = `Bannerlator-glscanout-overlayfix-{standard,ludashi,pubg}`.

**DEVICE-PROVEN SO FAR (Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube):** P4 functional (game flows through scanout, renders correct, swapRB ok, cursor own SC), P5 grey-out works. ❌ Overlay NOT promoting: every Native-ON test shows `winlator_game_buf = CLIENT` (raw `composition: DEVICE/CLIENT` = HWC rejected). fix#1 (idle OPAQUE base) tested INSUFFICIENT. fix#2 (translucent base) = current build, UNTESTED.

**KEY FINDING (agent, code-read):** the game-SC geometry (src 1280×702→dst fullscreen + ROT_90=global display orientation) is BYTE-FOR-BYTE the same as the Vulkan native path, which DOES promote to DEVICE on this device → so rotate+scale is NOT the GL-specific blocker (my earlier blocker-#2 hypothesis likely WRONG). The GL-specific difference = GLSurfaceView base is OPAQUE & composited (CLIENT) even when idle, starving the game overlay's HWC plane. fix#2 makes it TRANSLUCENT so SF can skip it (Vulkan base = idle swapchain, ASR = bufferless — both non-competing).

**NEXT ACTION ON RESUME:** 1) confirm CI `28378890898` green. 2) Device-test translucent build (GL container, Native ON, DX11 scene, drawer closed): **THE GATE = dumpsys `winlator_game_buf` composition type CLIENT→DEVICE** + game still correct (orientation/fit/colors/no-black). 3) If promoted → quantify the win: capped-60 GPU/power/temp A/B (off vs on). 4) If STILL CLIENT → deeper dig: why does identical-geometry Vulkan promote but GL not (plane budget? layer count? the extra activity/decor layers? try fewer layers / check HWC plane caps). 5) If promotes + correct → merge `feat/gl-scanout-overlay-fix` chain to main (reconcile PROGRESS_LOG), then P6 optional. If unfixable → gate GL-native experimental (plan §3 fallback).

**Interpretation reminder (A/B/C):** user feels a real latency/FPS diff Native ON vs OFF = the **B** win (GL compositor pass skipped) which happens regardless of overlay; **C** (the DEVICE overlay) is the missing big win. Verify C via capped-FPS GPU/power, NOT raw FPS. dumpsys DEVICE/CLIENT = authoritative.

**Bridge test recipe (from this session):** `getlog --exec` (PATH +=/data/data/com.termux/files/usr/bin). Graphics tab icon tap (70,138); Native Rendering toggle (765,968); close drawer keyevent 4; dumpsys → `/sdcard/Download/_x.txt`; check `grep -iE "HWC layers" -A18 | grep -iE "winlator_game|SurfaceView\[com.winlator|DEVICE|CLIENT"`; SCcount `grep -icE "winlator_game|winlator_cursor"` (0=native off, ~14=on). Foreground session: `monkey -p com.termux -c android.intent.category.LAUNCHER 1`. ⚠️ Native left toggled ON on test container (container 2 / the AIO test container).

---

## ❌ overlay-fix #2 (TRANSLUCENT base) DEVICE-TESTED 2026-06-29 — STILL CLIENT, base regressed. Translucent approach backfired.

Build `e036124` (CI `28378890898` green, manually installed by user). Device = Adreno 750/SD8Gen3, GL|DXVK, AIO DX11 cube, drawer closed. Driven via root bridge (toggle 765,968; dumpsys OFF vs ON).

**THE GATE = FAIL.** dumpsys SurfaceFlinger composition (requested/actual):
- **Native OFF baseline:** base `SurfaceView[…XServerDisplayActivity]` = `composition: DEVICE/DEVICE` ✅ — ONE clean fullscreen HWC overlay, 0 scanout SCs. (FPS 750 / GPU 26% / CPU 90.7°C)
- **Native ON (fix #2):** SC count 0→14 (scanout built, native active, P5 grey-out confirmed, cube renders correct/right colors). BUT:
  - base `SurfaceView` = `composition: DEVICE/CLIENT` ❌ (was DEVICE/DEVICE off — **REGRESSED**, making it translucent did NOT get SF to cull it; it stays in the stack at z:0 and now forces GPU comp)
  - `winlator_game_buf#781` AHB = `DEVICE/CLIENT` ❌ — ROT_90, src 1280×702 → dst 1080×1920 (rotate + 1.5× scale)
  - `winlator_cursor_buf#782` AHB = `DEVICE/CLIENT` ❌
  - VRI + ScreenDecor also CLIENT → **EVERY layer GPU-composited.** (FPS 704 / GPU 28% / CPU 93.1°C = slightly WORSE than off)

**VERDICT:** translucent base = INSUFFICIENT and counterproductive. It didn't make SF skip the base (base still composited, just now blended/CLIENT instead of a clean opaque DEVICE overlay), and the whole frame fell to GPU. So the checkpoint's "opaque competing base is the GL-specific blocker" hypothesis is NOT confirmed by making it translucent — the base being *present at all* (not its opacity) plus the rotate+scale game buffer is what's blocking. **C (HWC overlay) still not happening; only the B win (skipped GL compositor pass) remains, and it's marginal here.**

**▶️ NEXT HYPOTHESES (not yet tried, ranked):**
1. **Make the base GLSurfaceView genuinely BUFFERLESS/absent when scanout active** (like ASR's bufferless base), not merely translucent — currently it's still a composited layer at z:0. If SF still can't drop it, the GLSurfaceView architecture itself may be the wall (can't host children AND vanish).
2. **Drop the SC-level SCALE on the game buffer** — set the game SC to display size via setBuffersGeometry/setGeometry so it carries ONLY ROT_90 (the global display transform), matching GameHub/libwinemu (zero SC-level geometry). The 1280×702→1080×1920 rotate+scale combo is a known Adreno overlay-rejection trigger. (Re-opens old "blocker #2"; checkpoint thought it was wrong but this run keeps it live.)
3. **DECISIVE DIAGNOSTIC: capture Vulkan-native ON dumpsys on the SAME device/scene** and diff layer-for-layer vs this GL one — Vulkan promotes to DEVICE here, so the diff (layer count? base swapchain state? is the Vulkan base even present? buffer geometry/transform?) points straight at the real GL-specific blocker instead of guessing.

⚠️ Container left with Native toggled ON after this test. fix #2 NOT merged (still on branch `feat/gl-scanout-overlay-fix` tip `e036124`).

---

## 🔬 ROOT-CAUSE DIAGNOSIS 2026-06-29 (graphics-vulkan-engineer code-read, agentId aa8c3b24c81f5e41c) — it's the GLSurfaceView base + child-parenting, NOT geometry/opacity.

Read both scanout paths (Vulkan works→DEVICE, GL fails→CLIENT) against the device evidence. Eliminations:
- **Geometry/SC-scale = RED HERRING.** Both renderers feed the SAME `ScanoutContext::setBuffer` (`ScanoutContext.cpp:173-211`) with transform arg **0** (identity); the ROT_90 you see in dumpsys is the global display orientation applied to EVERY layer (incl base), and the 1280×702→1080×1920 is the inherent src≠dst guest→display scale. This exact block runs on the Vulkan path that DOES promote on this device. So `setBuffersGeometry`/dropping the scale would change nothing GL-specific.
- **Parenting topology = IDENTICAL.** Both parent the game/cursor SCs under `xServerView.getSurfaceControl()` = their own base-view's SC (Vulkan `VulkanRenderer.java:623`, GL `DirectScanout.java:85-89`). Only variable = base VIEW TYPE: plain `SurfaceView` (Vulkan/ASR) vs `GLSurfaceView` (GL).
- **"Base still updating" = NOT it.** Both idle the base after scanout starts (Vulkan `VulkanRendererContext.cpp:1486-1499` renders ONE empty frame then `return`s forever — its plain-SurfaceView BufferQueue genuinely flatlines so SF can drop it; GL mirrors via `setRenderingEnabled(false)`+early-return `GLRenderer.java:262-285,387-395`).

**THE WALL:** `GLSurfaceView` OWNS/manages its own `EGLSurface` (`XServerView.java:102-111`, preserveEGLContextOnPause) → it ALWAYS holds its last fullscreen EGL buffer and can't be made bufferless/absent like a stopped Vulkan swapchain or ASR's bufferless host. AND because the game/cursor SCs are its CHILDREN, you can't hide/remove the base without hiding them (parent setVisibility(false) hides subtree). `setGlSurfaceTranslucent` only changes the holder format → turns the base into a BLENDED fullscreen layer at z=0 that HWC must client-composite → cascades the whole frame to CLIENT = exactly the fix-#2 regression. Matches the GameHub/libwinemu RE: GameHub promotes because it uses a **dedicated standalone surface with no competing base**.

**▶️ RECOMMENDED FIX (a), IMPLEMENT FIRST:** reparent the scanout SCs OFF the GLSurfaceView. Concrete lowest-risk form: add a **dedicated plain `SurfaceView`** sibling of `glSurfaceView` in the `XServerView` FrameLayout, parent game/cursor SCs under THAT SC, and set the GLSurfaceView GONE / its SC invisible while native active → single opaque fullscreen game SC over a clean base = the same topology that already promotes on the Native-OFF baseline AND the Vulkan path. Files: `XServerView.java` (`getSurfaceControl()` :183, add dedicated SurfaceView + base-hide hook by `setGlSurfaceTranslucent` :168), `GLRenderer.enableScanout/disableScanout` (:608/:646), `DirectScanout.enable` (parent arg already generalized). (Plan §3 Fallback-A via `getRootSurfaceControl()` API30+ also works but fussier on lifecycle — dedicated-SurfaceView is closer to the proven Vulkan path.)
- **(b) genuinely bufferless GL base** = SECOND/fallback — needs replacing GLSurfaceView with self-managed EGL-on-SurfaceView (big rewrite, deferred "drop GLSurfaceView" step). (a) reaches the same end-state without it.
- **(c) drop SC scale** = DO NOT — proven not the blocker (shared with working Vulkan path).

**Device tests now CONFIRMATORY, not exploratory:** Vulkan-ON would re-confirm the P0 result (already proven DEVICE on this device); GameHub-GL-ON would independently prove a GL-origin standalone overlay promotes here + hand us the target layer structure. Optional belt-and-suspenders before building fix (a).

---

## 🧪 GAMEHUB 5.3.5 NATIVE-RENDERING+ DEVICE-TESTED 2026-06-29 — ALSO fails to promote (DEVICE/CLIENT), BUT confounded by windowed-desktop mode.

Ran the upstream reference (GameHub 5.3.5, pkg `com.tencent.ig`/`com.xj.winemu.WineActivity` — basis of Banner Hub 3.8.0) on the SAME Adreno750/SD8Gen3, SAME AIO DX11 cube, DXVK, right-side drawer. "Native Rendering+" is a 3-way radio Auto/Disabled/**Force Enable**. dumpsys composition (requested/actual):
- **Native OFF (Disabled):** `SurfaceView[com.tencent.ig/…WineActivity]#5` = `DEVICE/DEVICE` ✅ (clean fullscreen overlay, RGBX_8888, transform 0, src 720×1280→dst 1080×1920) + VRI `DEVICE/DEVICE`. HUD "DXVK".
- **Native ON (Force Enable):** HUD flips to "DXVK+". ALL layers `DEVICE/CLIENT` ❌ (requested overlay, HWC REJECTED→GPU): base SurfaceView#5 (still PRESENT, RGBX), `AHardwareBuffer pid[27828]` z1 (game, RGBA_8888, transform **90**/ROT_90), `bbq-adapter#1` z2 (RGBA_8888, transform 90), VRI z3. **EXACT same failure shape as our GL path** (base stays present + ROT_90 game AHB + everything rejected to CLIENT).

**⚠️ CRITICAL CONFOUND — GameHub was running its Wine DESKTOP (windowed), NOT a borderless fullscreen game:** taskbar at bottom + title bar + window chrome; the game content layer is INSET (dst 61,2–1035,1919, not fullscreen). A non-fullscreen scene with competing chrome will be rejected for overlay promotion REGARDLESS of renderer. So this is NOT a clean apples-to-apples vs our fullscreen container, and does NOT cleanly serve as the "GL-origin overlay CAN promote here" reference we wanted.

**What it DOES tell us:**
1. GameHub's native path ALSO keeps its base SurfaceView present (doesn't vanish it) and ALSO applies ROT_90+scale on the game AHB — so the libwinemu-RE "zero SC-level geometry / standalone surface, no competing base" claim is NOT what this build does in desktop mode. (RE may describe fullscreen-game path or a different code branch.)
2. It does NOT refute the engineer's diagnosis for OUR GL-vs-Vulkan: that remains a CLEAN A/B (same container, same fullscreen config, same scene — only renderer differs; Vulkan promotes to DEVICE, GL doesn't). The GameHub non-promotion is explained by windowing.

**▶️ To make GameHub a decisive reference: re-run it with a BORDERLESS FULLSCREEN game (no taskbar/title bar).** If fullscreen GameHub promotes → confirms fullscreen+standalone-surface is the recipe (supports fix (a)). If even fullscreen GameHub stays CLIENT on this device → this Adreno750/Android build may be stingy about overlays generally (re-scope expectations). Meanwhile fix (a) is still well-founded on our own Vulkan A/B (the clean fullscreen proof that this geometry promotes on this device).
⚠️ Left GameHub with Native Rendering+ = Force Enable; may have toggled "RTS Touch Controls" on the Controls page (harmless, cosmetic).

---

## 🚨 GAMEHUB 5.3.5 FULLSCREEN Native-ON DEVICE-TESTED 2026-06-29 — STILL CLIENT even fullscreen+base-dropped. Blocker looks DEVICE-LEVEL (rotated non-UBWC buffer), not our GLSurfaceView.

Re-ran GameHub as a BORDERLESS FULLSCREEN game (no taskbar/title bar — confirmed via screenshot, cube fills screen + horizontal top HUD bar "DXVK+"), Native Rendering+ = Force Enable, drawer closed. SAME Adreno750/SD8Gen3.
- **The base WineActivity SurfaceView is GONE from the active HWC set** (in fullscreen GameHub DID drop its base — unlike windowed-desktop where it stayed). Game buffer is now z:0 (bottom).
- **Yet STILL no promotion — ALL `DEVICE/CLIENT`:** `AHardwareBuffer pid[32674]` z0 (game, RGBA_8888, **transform 90**), `bbq-adapter#1` z1 (RGBA_8888, **transform 90**), `VRI[WineActivity]` z2 (RGBA_8888_UBWC, transform 0). HWC requested overlay, rejected every layer to GPU.

**🔑 PATTERN across EVERY capture today (sharp):**
- Layers that PROMOTE (`DEVICE/DEVICE`): `RGBX/RGBA_8888_UBWC`, **transform 0**. (GameHub OFF baseline base SurfaceView; our Native-OFF GL base.)
- Layers that get REJECTED (`DEVICE/CLIENT`): `RGBA_8888` NON-UBWC, **transform 90** (ROT_90). (our GL game AHB; GameHub game AHB windowed AND fullscreen.)
→ Strongly suggests a **device/DPU limitation: this Adreno display controller won't HWC-overlay a ROTATED, non-UBWC AHardwareBuffer** (Adreno rotator typically requires UBWC; a transform-90 linear buffer is overlay-ineligible → forced CLIENT, and one ineligible layer drags the whole frame to CLIENT). The landscape-game→portrait-panel 90° rotation is the likely poison.

**⚠️ THIS WEAKENS "just do fix (a)":** fix (a) = reparent scanout SCs off the GLSurfaceView + drop the base. But GameHub fullscreen ALREADY effectively does that (base dropped, standalone game buffer) and STILL doesn't promote. So dropping the competing base is necessary-but-NOT-sufficient on this device — the rotated non-UBWC buffer itself is rejected.

**🎯 THE NOW-DECISIVE TEST (no longer redundant): capture OUR Bannerlator VULKAN native-ON game buffer's composition + TRANSFORM + FORMAT on this device.** Our P0 gate recorded Vulkan promotes to `DEVICE` (BGRA_8888) but did NOT record the transform. Two outcomes:
- If Vulkan promotes with transform 90 + non-UBWC → then GameHub/GL rejection is something else (layer count? a GameHub quirk?) and the rotation theory is wrong — re-examine.
- If Vulkan's promoting buffer is transform 0 (pre-rotated content) or UBWC → THAT is the recipe: the GL/native path must deliver a pre-rotated and/or UBWC buffer so the DPU can overlay it. Fix shifts from "reparent" to "fix the buffer orientation/format."
(Earlier I called the Vulkan capture redundant — this GameHub result makes it the key missing measurement.)

⚠️ Left GameHub fullscreen + Native+=Force Enable.

---

## 🚨🚨 BOMBSHELL 2026-06-29 — OUR VULKAN NATIVE ALSO DOES NOT PROMOTE ON THIS DEVICE. The whole "Vulkan promotes / GL doesn't" premise is FALSE. Blocker = the 90° rotation (landscape game → portrait panel), device-level, renderer-agnostic.

Captured OUR Bannerlator **Vulkan|DXVK** native-ON dumpsys (same Adreno750/SD8Gen3, same AIO cube, Native ON confirmed via screenshot — Renderer: Vulkan|DXVK, P5 grey-out active, 564fps/GPU20%):
- `SurfaceView[com.winlator.banner/…XServerDisplayActivity]#2` z0 = `DEVICE/CLIENT` ❌ (RGBA_8888_UBWC, **transform 90**)
- `AHardwareBuffer pid[5980]` z1 = `DEVICE/CLIENT` ❌ (BGRA_8888, **transform 90**) = the game buffer
- `VRI[XServerDisplayActivity]` z2 = `DEVICE/CLIENT` ❌ (RGBA_8888_UBWC, transform 0)
- HWC layers table shows winlator_game_buf actual = **CLIENT**; active `---------client target---------` = full GPU composition. **NO overlay.**

**⛔ This means our Vulkan native NEVER actually promoted on this device.** The P0 gate's "winlator_game_buf composition type=DEVICE = HWC overlay confirmed" was a **MISREAD of the REQUESTED composition type** (the HWC hint column says DEVICE = "SF asked for overlay") **not the ACTUAL** (post-validateDisplay = CLIENT = HWC rejected → GPU). Every native-render path requests DEVICE; this device rejects them all.

**🔑 NOW the picture is consistent across ALL FOUR captures today:**
| Path | game buf | result |
|---|---|---|
| Bannerlator Vulkan native ON | BGRA_8888, ROT_90 | DEVICE/**CLIENT** ❌ |
| Bannerlator GL native ON | BGRA/RGBA, ROT_90 | DEVICE/**CLIENT** ❌ |
| GameHub native ON (windowed) | RGBA_8888, ROT_90 | DEVICE/**CLIENT** ❌ |
| GameHub native ON (fullscreen, base dropped) | RGBA_8888, ROT_90 | DEVICE/**CLIENT** ❌ |
| (any renderer) Native OFF baseline | UBWC, **transform 0** | DEVICE/**DEVICE** ✅ |

**ROOT CAUSE = the 90° rotation.** This is a PORTRAIT-NATIVE panel (SF display 1080×1920); the game runs landscape and the direct-scanout buffer is handed to SurfaceFlinger with **transform=ROT_90** so the DPU must rotate it for display. This Adreno DPU/HWC will NOT take a rotated layer as an overlay (rotation on the overlay path is unsupported / disqualifying here) → falls back to GPU/CLIENT for the whole frame. In the Native-OFF baseline the app's own compositor bakes the rotation into a transform-0 UBWC surface, which DOES promote. So: rotation baked-in (OFF) = overlayable; rotation requested on the scanout layer (ON) = rejected.

## ⛳ STRATEGIC CONSEQUENCES (big)
1. **The "C win" (true HWC hardware overlay, GPU idle) is NOT achievable on this portrait device for landscape content — for ANY renderer.** Not a GL bug, not a Vulkan win. It's a display-rotation/DPU limitation.
2. **fix (a) / the whole GLSurfaceView-base theory is MOOT for overlay promotion** — Vulkan uses a plain SurfaceView and STILL doesn't promote. Dropping the GL base would not unlock the overlay. Stop the overlay-fix branch attempts (fix#1 idle, fix#2 translucent, proposed fix(a) reparent) — they chase an unattainable C on this device.
3. **What native rendering DOES deliver here = the "B win"** (skip the app's own compositor blit + change present pacing → lower latency / the real feel the user reports). That is renderer-agnostic and ALREADY delivered by P4/P5. So GL native = latency PARITY with Vulkan; both get B, neither gets C on this device.
4. **The C win likely WORKS on a LANDSCAPE-NATIVE panel** (game buffer arrives transform 0, no DPU rotation needed) — e.g. AYANEO/landscape handhelds. The feature isn't useless; its overlay benefit is display-orientation-dependent.

## ▶️ RECOMMENDED NEW DIRECTION
- **Re-scope native rendering = a latency/pacing feature (B), not an overlay feature (C)**, on portrait devices. Document that HWC-overlay promotion needs a transform-0 (landscape-native) path.
- **Salvage the GL work:** merge P4+P5 (the functional per-frame push + effect grey-out) as "GL native rendering (latency parity)", DROP the failed overlay-fix commits (#1 idle, #2 translucent) — they targeted C. Reconcile branch PROGRESS_LOG.
- **Two confirmations worth doing (cheap):** (1) capture native-ON on a **landscape-native device** (AYANEO) — if game buf = transform 0 → DEVICE, the rotation theory is proven and C works there. (2) optional: try forcing this container/display to landscape-native orientation and re-capture — if it promotes, we have a per-device path.
⚠️ Vulkan container left Native ON.

---

## 🔁 LANDSCAPE LONG-SHOT DEVICE-TESTED 2026-06-29 — DID NOT help; but revealed the ROT_90 is applied by the SCANOUT CODE, not system orientation.

User forced the whole Android system to landscape, ran AIO cube on GL|DXVK, Native ON, drawer closed. (Note: forcing orientation RESTARTS the XServerDisplayActivity → reloads container; came back OpenGL/Native-off, re-enabled Native.) dumpsys (composition | format | transform):
- base `SurfaceView[…XServerDisplayActivity]#2` z0 = DEVICE/**CLIENT** ❌, RGBA_8888_UBWC, **transform 0** (now 0 because the activity surface follows the landscape system)
- game `AHardwareBuffer pid[17795]` z1 = DEVICE/**CLIENT** ❌, BGRA_8888, **transform 90** ← STILL ROTATED even though system is landscape
- cursor `AHardwareBuffer pid[17527]` z2 = DEVICE/**CLIENT** ❌, RGBA_8888, transform 90
- VRI z3 = DEVICE/**CLIENT** ❌, UBWC, transform 0

**KEY NEW INSIGHT:** the base surface went transform 0 in landscape (follows system), but the **GAME scanout buffer is STILL transform 90**. So the 90° rotation on the game buffer is applied by the **native-rendering handoff itself** (ScanoutContext/DirectScanout sets the buffer's transform), NOT by the global display orientation as previously assumed. Forcing landscape therefore could NOT remove it → still a rotated layer in the stack → HWC still rejects the WHOLE frame to CLIENT (the transform-0 UBWC base + VRI get dragged to CLIENT too, exactly as the pattern predicts). The cube still displays CORRECTLY with ROT_90+landscape (so the rotation is currently part of producing the right image — naively removing it would likely break orientation).

**CONCLUSION: landscape long-shot = DEAD END on this device with current code.** The overlay still doesn't engage. The blocker (rotated game buffer, overlay-ineligible on this Adreno DPU) is confirmed and is baked into the scanout handoff.

**One UNTESTED lever this surfaces (speculative, code change):** make the scanout transform ORIENTATION-AWARE — deliver the game buffer at transform 0 when the display is genuinely landscape-native (and ensure buffer dims match), so no rotated layer is in the stack. MIGHT promote + still display correctly IF the guest render orientation is adjusted to match. Risky (orientation/fit), unproven, and GameHub doesn't do it. NOT recommended without the engineer validating it's even coherent. The safe path remains: re-scope native = latency (B) feature, salvage P4+P5, and confirm the true overlay win on a genuinely landscape-native panel where the buffer naturally needs no rotation.

---

## ✅ SALVAGE MERGE 2026-06-29 — P4+P5 landed on main as "GL Native Rendering (Low-Latency Mode)"; dead overlay-fix reverted.

Per user direction ("give me P4 and P5 with a solid explanation for the next release"), after today's findings (overlay/C-win unattainable on this portrait device for ANY renderer; native = latency/B-win feature):
- **Reverted `7aaacaf`** (overlay-fix #2 translucent base — was the ONLY overlay-fix code that had reached main; proven dead/counterproductive on device today). Revert `ee63ab1` (-43 lines, restores clean P3 base).
- **Cherry-picked P4 (`d44b560`→`fcaf104`)** per-frame game push + **P5 (`64b7cfb`→`ba0d35d`)** effect/scaling grey-out onto the clean base (authored against 2fe3f10 = post-revert state → applied with ZERO conflicts). Verified: GLRenderer.presentScanout present, XServerDrawer glEnabled present.
- main now = P0+P1+P2+P3+P4+P5, NO overlay-fix code. P4+P5 were device-proven FUNCTIONAL (native active, game renders correct, P5 grey-out works) — only the overlay promotion failed, which we're no longer claiming.
- **NOT cutting a release** (per versioning rule — no tag/make_latest without explicit say-so). Release notes prepared at `docs/release_notes/gl_native_rendering.md` (paste-ready, honest: latency feature, effects mutually exclusive, power/overlay win is device/orientation-dependent).
- Dropped (NOT merged, stay on branch `feat/gl-scanout-overlay-fix`): overlay-fix #1 idle base (`b418e53`), overlay-fix #2 translucent (`e036124`) — both chase the unattainable C-win.
- ▶️ NEXT: CI build to confirm green on main; (optional, when available) confirm the overlay/power win on a landscape-native device (AYANEO).

---

## 🔬 PRE-ROTATION FEASIBILITY (graphics-vulkan-engineer, 2026-06-29) — reframes "rotation is THE blocker" as CONFOUNDED; cheap diagnostic experiment identified.

**Key correction (to my own conclusion):** "transform-0 promotes / transform-90 rejects → rotation is the blocker" is CONFOUNDED. The only transform-0 layer ever seen promoting is the BASE SurfaceView, which differs from the rejected game AHB in FOUR ways at once:
| | promoting base | rejected game AHB |
|---|---|---|
| transform | 0 | 90 |
| tiling | UBWC | non-UBWC/linear |
| usage | composer/scanout-grade | lacks COMPOSER_OVERLAY |
| geometry | full-screen 1:1 | scaled 1280×702→1080×1920 |
Rotation is ONE suspect; **non-UBWC + missing COMPOSER_OVERLAY usage is an independent, very-likely-decisive co-blocker that pre-rotation would NOT fix** (and is renderer-agnostic → would explain why Vulkan also fails).

**Where ROT_90 comes from (Q1): NOT our code.** Every scanout `setGeometry` passes transform 0 (`ScanoutContext.cpp:196` game, `:256` cursor; ASR `:246,383`). No `ASurfaceTransaction_setBufferTransform` anywhere in the scanout path (symbol only loaded by ASR `ASurfaceRendererContext.cpp:120`, unused). The ROT_90 is **SurfaceFlinger folding the display/window orientation** onto the layer: a landscape container runs the activity landscape on a portrait-native 1080×1920 panel (`XServerDisplayActivity.java:1015-1019` only locks PORTRAIT for portrait containers), so SF must rotate the game child-SC's fixed landscape guest buffer 90° to reach the panel.

**Critical wrinkle:** the game scanout buffer is **GUEST-allocated (Mesa/turnip Android WSI export), received zero-copy over socket** (`DRI3Extension.java:154-156`→`GPUImage(fd)`→`AHardwareBuffer_recvHandleFromUnixSocket` `gpu_image.c:81`). The host `createHardwareBuffer` (`gpu_image.c:88-97`, lacks COMPOSER_OVERLAY, CPU_WRITE_OFTEN/BGRA→linear) is only the CPU/SHM fallback, NOT game frames. So we do NOT control the game buffer's tiling/usage/format host-side without COPYING.

**Pre-rotation verdict (Q2-Q4):** Option (a) — render the guest frame via a GL pass into a HOST AHB allocated COMPOSER_OVERLAY+UBWC-friendly at panel res — is feasible and fixes 3 of 4 differences at once (transform, usage/tiling, scale). BUT re-introduces a per-frame GPU blit → "zero-GPU" dream gone; remaining win = direct HWC scanout + lower latency, MODEST over today's GL compositor. Option (b) just-force-transform-0 = sideways image (we don't set ROT_90; forcing it 0 without rotating pixels mis-orients). Option (c) patch guest WSI to allocate scanout-grade buffers = no host copy but Mesa/turnip change (wine-compat), risky, leaves rotation. Honest promotion odds even with pre-rotation: MODERATE — usage/UBWC, the scale, BGRA, and plane budget could each independently block.

**▶️ RECOMMENDED EXPERIMENT A (cheapest, isolates the most-likely + unexamined blocker, host-only ~40-60 lines GL):** in `GLRenderer.presentScanout`, blit the guest GPUImage into a host AHB allocated `GPU_SAMPLED_IMAGE|COMPOSER_OVERLAY` RGBA_8888 (reuse cursor alloc pattern `ScanoutContext.cpp:279-285`), KEEPING transform 90 + scale, present THAT. dumpsys: if game flips CLIENT→DEVICE at transform 90 → rotation was a red herring, blocker was usage/UBWC (renderer-agnostic, ~done). If still CLIENT → rotation implicated → Experiment B (add 90° rotate + render at exact 1080×1920 + lock portrait for the probe, accept wrong cursor/HUD). 
Key files: `ScanoutContext.cpp:185-196,256,279-285`, `gpu_image.c:81,88-97`, `GPUImage.java:18-38`, `DRI3Extension.java:154-156`, `GLRenderer.java presentScanout ~:725`, `XServerDisplayActivity.java:1015-1019`.

**✅ CI GREEN on main (run `28388609799`) — P4+P5 salvage compiles clean in the fresh main combination (revert `ee63ab1` + P4 `fcaf104` + P5 `ba0d35d`). GL Native Rendering (Low-Latency Mode) is now build-verified on main. Release notes ready at docs/release_notes/gl_native_rendering.md; no release cut.**

**🧪 EXPERIMENT A BUILT (graphics-vulkan-engineer, 2026-06-29) — branch `exp/gl-scanout-composer-overlay-ahb` (`75115bb`, off main `9c7156c`, NOT merged). CI run `28390468273`.** GL native path blits the guest game AHB (one passthrough quad, SAME size/orientation/scale, NO pre-rotation) into a HOST AHB allocated `GPU_SAMPLED|GPU_FRAMEBUFFER|COMPOSER_OVERLAY` (BGRA_8888, swapRB still applies), presents THAT — isolates buffer-usage/UBWC hypothesis from ROT_90. Files: `gpu_image.c` createScanoutHardwareBuffer JNI; `GPUImage.java` scanout ctor; NEW `ScanoutBlitMaterial.java` (passthrough+V-flip); `GLRenderer.presentScanout` rewritten to marshal blit to GL thread (queueEvent+latch, epoll thread has no GL ctx) +ensureHostScanout/releaseHostScanout. Fallback: alloc/FBO/roundtrip fail or 3 timeouts → sticky-revert to direct guest present (never black-screens). +1 quad blit/frame. Caveat: samples guest tex post-unlock w/o write-fence → rare tearing on fast frames (irrelevant to signal). **DEVICE GATE: GL+Native ON, dumpsys winlator_game_buf CLIENT→DEVICE at transform 90? YES=usage/UBWC was blocker (rotation red herring, likely renderer-agnostic→explains Vulkan). STILL CLIENT=rotation implicated→Experiment B. Confirm cube renders correct (V-flip=1-line lever, doesn't affect reading).**

## 🧪 EXPERIMENT A DEVICE-TESTED 2026-06-29 — PROBE ENGAGED, but COMPOSER_OVERLAY usage RULED OUT as the blocker. Game still CLIENT.
GL container, Native ON, drawer closed, Adreno750. dumpsys: game `AHardwareBuffer` is now **pid 11930 (= the app process, `pidof com.winlator.banner`=11930 → host blit ENGAGED)**, buffer-cache `usage: 0xb00` (= COMPOSER_OVERLAY 0x800 + GPU_FRAMEBUFFER 0x200 + GPU_SAMPLED 0x100, no CPU flags) — vs the original guest buffer's 0x333. So the host AHB with COMPOSER_OVERLAY IS what's presented. **YET STILL `composition: DEVICE/CLIENT` (rejected), transform 90, BGRA_8888 non-UBWC (compressed:false), still scaled 1280×702→1080×1920.** Content rendered (not black; fallback did NOT trigger; no V-flip black-screen). 
**⇒ COMPOSER_OVERLAY usage flag is NOT the blocker (set it, still rejected). Engineer's leading hypothesis disproven.** Remaining co-varying suspects on the game layer vs the (would-promote) base: **(1) transform 90 (rotation), (2) non-UBWC/linear (base is RGBA_8888_UBWC), (3) the SC-level scale.** Note also: the base SurfaceView (UBWC/transform0) is ALSO DEVICE/CLIENT here while it was DEVICE/DEVICE in the OFF baseline → the rotated game layer poisons the whole stack to client (or plane budget). 
**▶️ Experiment B (the likely-decisive next probe): pre-rotate the blit + render the host AHB at exact panel res (1920×1080 so ROT_90→1080×1920 = NO SC scale), lock portrait for the probe. Neutralizes rotation AND scale at once, leaving only UBWC.** If B promotes → rotation/scale was it (path exists, w/ cursor/orientation plumbing cost). If B still CLIENT → it's UBWC (hard/maybe-impossible to force on a GL render-target AHB on Adreno) or fundamental → gate GL-native overlay unsupported on portrait; the clean overlay win stays the landscape-native-device path. Honest: diminishing returns; even B success = modest win (per-frame blit + plumbing); landscape-native handheld remains the clean payoff.

## 🧪 EXPERIMENT B BUILT (graphics-vulkan-engineer, 2026-06-29) — branch `exp/gl-scanout-prerotate-panelres` (`8b20b96`, stacked on Exp A `75115bb`, NOT merged). CI run `28392607613`.
Extends Exp A: host scanout AHB allocated at PANEL res (1080×1920, portrait-locked), blit ROTATES guest frame 90° into it (new `ScanoutBlitRot90Material`, UV transpose) so content is display-oriented, then presents at **transform 0, src==dst (no SC scale)** via setContainerSize/setDst(0,0,pw,ph). Activity portrait-locked during native (`setProbeOrientation(true)` in enableScanout, restore sensorLandscape on native-off; configChanges has orientation|screenSize → reconfigure in place, NO activity recreation). Keeps COMPOSER_OVERLAY usage + the sticky fallback (alloc/FBO/roundtrip fail or 3 timeouts → direct guest present, never black). Files: NEW `ScanoutBlitRot90Material.java`; `GLRenderer.java` (blitGuestIntoHostScanoutAndPresent → panel-res+rotate+transform0/src==dst, setProbeOrientation, portrait lock wiring). DIAGNOSTIC: cursor/HUD/input + image orientation intentionally WRONG — only the dumpsys reading + not-black matter. **DEVICE GATE: GL+Native ON, dumpsys winlator_game_buf = `DEVICE/DEVICE` at transform 0? DEVICE→rotation/scale was the blocker (path exists w/ cursor/orientation plumbing). STILL CLIENT→UBWC/compression or fundamental→gate GL-native overlay unsupported on portrait; landscape-native device = the win.** Confirm cube image present (rotated/odd = fine, just not black).

## 🟢🟢 EXPERIMENT B DEVICE-TESTED (2026-06-29, post-crash resume) — PROMOTES! TRUE HWC OVERLAY ON THE PORTRAIT DEVICE. The "C-win unattainable on portrait" bombshell is OVERTURNED.
Device "Pocket FIT" Adreno750/SD8Gen3, GL|DXVK, AIO DX11 cube, Native ON (game already foreground after the session crash; 14 scanout SCs alive; captured with drawer open — promotion holds with drawer up).

**DPU/SDM hardware composition pipe table (ground truth — the Snapdragon Display Engine's actual scanout plan, NOT a requested-hint column, so immune to the earlier requested-vs-actual misread):**
- idx0 base SurfaceView  RGBA_8888_UBWC  = SDE  (overlay)
- idx1 **GAME  BGRA_8888  = SDE pipe149, src 0 0 1080 1920 -> dst 0 0 1080 1920 (NO SCALE), Transform 0**
- idx2 cursor  RGBA_8888 16x16  = SDE
- idx3 VRI  RGBA_8888_UBWC  = SDE
- idx5 GPU_TARGET = **NO layers assigned = GPU does ZERO composition**

Raw HWC `layer:` list corroborates (game/cursor are named `AHardwareBuffer pid[15581]` = the app process = exp-B host-blit AHB, NOT `winlator_*` — that's why the first grep missed them):
- game  z1  `composition: DEVICE/DEVICE`  BGRA_8888  transform 0/0/0
- cursor z2 `composition: DEVICE/DEVICE`  RGBA_8888  transform 0/0/0
- base + VRI  `composition: DEVICE/DEVICE`  RGBA_8888_UBWC  transform 0/0/0
- count of `DEVICE/CLIENT` + `CLIENT/CLIENT` across the whole dump = **0**

Screenshot (scratchpad/eb1.png) = image produced, not black (Wine window chrome visible on the strip beside the open drawer; orientation/aspect intentionally wrong per the diagnostic design).

**VERDICT: the HWC-overlay blocker was ROTATION (transform 90) + SCALE (src != dst) TOGETHER — NOT UBWC, NOT GLSurfaceView, NOT portrait-orientation-per-se.** Pre-rotating the guest frame 90° into a panel-res (1080x1920) host AHB (so it's display-oriented = transform 0) + presenting src==dst (no SC-level scale) + portrait-locking the activity => this Adreno DPU accepts a **plain non-UBWC BGRA_8888** buffer on a hardware overlay pipe.

**Overturns:** (a) bombshell "C-win unattainable on this portrait device for ANY renderer" = FALSE — C IS attainable, you must hand the DPU a transform-0, unscaled buffer; (b) the "UBWC required" hypothesis (Exp A's last suspect) = FALSE (game promoted as plain BGRA_8888); (c) the landscape long-shot finding (ROT_90 applied by the scanout handoff, not the display orientation) = CONFIRMED as the cause, and Exp B's pre-rotation is the fix.

**Exp B is a DIAGNOSTIC THROWAWAY (branch `exp/gl-scanout-prerotate-panelres` `8b20b96`, do-NOT-merge as-is):** hardcoded 90° UV-transpose one direction; cursor/HUD/input/aspect intentionally wrong; extra full-frame GPU blit + GL-thread roundtrip per frame; portrait-lock hack.

**Next (decision pending with user) — productionize C-win on portrait vs. ship latency-only:** orientation-aware correct pre-rotation (all 4 rotations, right handedness/flip), fix cursor/HUD/input mapping under the rotated present, weigh the host-blit cost (extra blit + roundtrip + possible base double-buffer) against the overlay/GPU-idle win — vs. just keeping the already-merged latency-only P4+P5. Container left Native ON, exp-B build installed, Claude/Termux session brought back to foreground.

## ⏸️ PHASE 0 (GL native overlay portrait — power/perf A/B) STARTED then PAUSED by user (2026-06-29) — SETUP ONLY, NO NUMBERS. Resume when home.
User chose to run P0 (measure-first), then stopped it (about to lose Wi-Fi). Reached SETUP only; no OFF/ON numbers captured. Partial log: `scratchpad/p0_results.md`.

Resume state:
- Test container = `xuser-3` "P11 x86-64" (renderer=opengl, rendererNative=false). exp-B build (`exp/gl-scanout-prerotate-panelres` `8b20b96`) is the installed/active APK.
- **Container config WAS MODIFIED:** DXVK `dxwrapperConfig` framerate `0`->`60` (maxFrameRate, guest-side cap for matched-FPS A/B). **Backup at `<imagefs>/home/xuser-3/.container.p0bak`.** Keep the 60 cap to resume P0, or restore from backup to abandon.
- Termux/Claude session brought back to foreground.

Resume recipe: launch GL container xuser-3 -> AIO DX11 cube -> enable perf HUD -> State A Native OFF (dwell ~30s, 3+ samples) -> State B Native ON (toggle in drawer, dwell ~30s, same samples). Sample {`/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage`, `gpuclk`, `/sys/class/power_supply/battery/current_now` uA, thermal_zone temp, displayed FPS}. Confirm overlay via dumpsys SDE pipe table (ON: game AHB DEVICE/DEVICE on SDE pipe + GPU_TARGET empty; OFF: base SurfaceView DEVICE/DEVICE, no game AHB). VERDICT: ON lower GPU% AND power at equal FPS -> portrait worth productionizing; flat/worse -> drop portrait, headline the free landscape-native win.
