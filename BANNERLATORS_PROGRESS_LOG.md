# Bannerlators — Progress Log

Standalone fix/build fork of `star-compose` (Winlator-based Android app, marcescence 1.4 line).
Repo: https://github.com/The412Banner/bannerlators (public). Created 2026-06-18.

## Origin / what this repo is
- Fresh, **unattached** repo: brand-new git history (single initial commit), origin points
  only at `bannerlators`. No remote/history link to `The412Banner/star-compose`.
- Source = the `star-compose` working tree at the **1.4-marcescence** line
  (gradle `versionName "7.1.4x-cmod"`, `versionCode 20` — "1.4-marcescence" is the release
  tag name, not the gradle version).
- **Self-contained**: OpenXR-SDK + adrenotools (incl. nested linkernsbypass) submodule sources
  were fetched at their pinned commits and **vendored in as plain files**. `.gitmodules`
  removed; CI no longer needs `submodules: recursive`.
- Build artifacts excluded (`.gradle`, `build/`, `.cxx`, `local.properties`, `*.iml`, `.idea`).
  Large assets (`imagefs.txz`, `proton-9.0-*.txz`) stay gitignored, same as upstream.

## CI (both manual — no accidental releases)
- **`.github/workflows/main.yml`** — "Any branch compilation." `workflow_dispatch` only.
  Builds JavaSteam JARs → `assembleDebug` → uploads APK as artifact `compiled-debug`.
  Dropped the `submodules: recursive` checkout option (vendored now).
- **`.github/workflows/release.yml`** — "Release build" `workflow_dispatch` with inputs
  `tag` (required) + `prerelease` (bool, default true). Same build, then renames the APK to
  `bannerlators-<tag>.apk` and publishes a GitHub Release via `softprops/action-gh-release@v2`
  (`permissions: contents: write`, `generate_release_notes: true`).

## Fixes applied
### 2026-06-18 — Settings screen overlapping/collapsed rows
- **Symptom** (reported via two device screenshots of 1.4-marcescence): every section card on
  the Settings screen rendered its rows on top of each other — XServer checkboxes
  ("True Mouse Control…", "Disable Xinput…") piled onto the Cursor Speed slider; Box64/FEXCore
  preset spinner + icon row + label collapsed onto one line; "Winlator/Shortcut Export Path"
  labels overlapped, etc.
- **Root cause**: each section is a `LinearLayout style="@style/FieldSet.Dark"` that gets its
  `orientation="vertical"` **only from the style**. Under 1.4's new Compose host
  (`FragmentScreen` → `AndroidView` → `FragmentContainerView` w/ `ContextThemeWrapper`), the
  style's orientation wasn't being honored, so the LinearLayout fell back to its default
  **horizontal** → children stacked at the same spot. (Width *was* honored — cards are
  full-width — so only orientation was dropped.) Inner rows were fine because they set
  `android:orientation` directly on the tag.
- **Fix**: added `android:orientation="vertical"` directly to all **11** `FieldSet.Dark`
  LinearLayout tags in `app/src/main/res/layout/settings_fragment.xml`, decoupling orientation
  from the style. Low-risk, surgical.
- The earlier suspect (commit `12b01e3` switching `FieldSet`→`FieldSet.Dark`) was a **red
  herring** — that change only swaps the background drawable (`bordered_panel`→
  `bordered_panel_dark`, a `<solid>` fill, no padding/insets) and cannot change child stacking.
- **Status**: ⏳ awaiting device confirmation (CI green ≠ working). NOT yet device-tested.

### 2026-06-18 — Splash screen rebrand (`SplashScreen.kt`)
- First-run "Installing system files" overlay was hardcoded "Bionic Star" / "V1.2" + app icon.
- **Change**: swapped `R.mipmap.ic_launcher_foreground` → repo banner logo
  (`app/src/main/res/drawable/splash_logo.jpg`, copied from root `logo.jpg`), sized
  `fillMaxWidth()` so the wide 1245×602 banner isn't squished; **removed** the title Text
  (logo already carries the "Bannerlator" wordmark); version label → **`v1.0`**. Kept
  "Installing system files", progress bar, Proceed button. Dropped unused `size` import.

### 2026-06-22 — GOG login white screen — FIXED (candidate #5, device-confirmed)
- **Symptom**: GOG store login WebView rendered a blank white screen; the OAuth login
  iframe handshake never completed. Regression from the marcescence Compose rewrite that
  hosted the login WebView inside a Compose `AndroidView` and mutated the auth params.
- **Candidates tried on device** (branch `fix/gog-login-whitescreen`):
  1. Enable third-party cookies for the login iframe (`b3cc94d`) — ❌
  2. Drop `layout=client2` so the web form self-renders (`cce55d7`) — ❌
  3. Plain Chrome UA instead of the Galaxy UA (`70ddcaa`) — ❌
  4. (combinations of the above) — ❌
  5. **Mirror the proven star-compose `GogLoginActivity` exactly** (`ef3d6df`) — ✅ **WORKED**
- **The fix (#5)**: a plain `ComponentActivity` that hosts the WebView via `setContentView`
  (NOT a Compose `AndroidView`), keeps `layout=client2` **and** the `GOG Galaxy/2.0` UA,
  enables only JS + DOM storage. `ef3d6df` reverts the three dead-end attempts; the rest of
  the app stays Compose. BH_GOG diagnostic logging retained. See the locked-in note in
  `GogLoginActivity.kt` companion comment — do NOT reintroduce the dead-end changes.

### 2026-06-22 — Game Controller Test in Start menu + `.lnk` working-directory fix
- Bundled **Game Controller Test** (`GameConTest.exe`, SDL3 gamepad tester + `GameConTest.000`,
  `SDL3.dll`, 12 `.loc` files) into `container_pattern_common.tzst` at `C:\Game Controller Test\`
  and added a top-level Start-menu shortcut. Pulled from device
  `/storage/emulated/0/Winlator/Games/Game Controller Test/`; dropped runtime junk
  (vkd3d cache, d3d/dxgi logs). Appended into the decompressed tar preserving archive
  owner/perms (uid 10314 / gid 1023, setgid dirs, 660 files). Merge `4a3e974`.
- **`.lnk` WorkingDir ("Start in") fix** (`403cd64`): GameConTest only ran from its own folder
  (needs sibling files in cwd). The generated `.lnk` set no working dir. Added `WorkingDir`
  (`HasWorkingDir` 0x10) to `MSLink` in MS-SHLLINK StringData order; optional `"workingDir"`
  field in `wine_startmenu.json`. Device-confirmed working.

### 2026-06-22 — Ship RELEASE builds, not debug (Compose-sluggishness root cause)
- Users reported the Compose UI feels laggy vs the old XML/View UI. Root cause: **every CI
  artifact was `assembleDebug`** (main.yml, build-artifacts.yml, even release.yml). Compose is
  ~2–10× slower in debug; Views barely care → the gap reads as "Compose is sluggish."
- Branch `chore/release-builds`: switched all workflows to `assemble*Release` + APK output
  paths `/debug/`→`/release/`. Kept `minifyEnabled false` (NO R8 → no reflection/JNI risk);
  release type already testkey-signed so updates still install over installs.
- Release-only gotchas fixed: `lint { abortOnError false; checkReleaseBuilds false }`; and
  `release { crunchPngs false }` — `ab_*`/`ab_gear_*`/`ab_quilt_*` animation frames +
  `ic_stat_ab_gear*` are GIFs with a `.png` extension, which release's PNG cruncher rejects
  (debug skips crunching). First release build failed on this, then fixed.
- APK size audit (built APK ~564MB): imagefs 184MB + proton 91MB = ~49% (fetched at build by
  `downloadProton`), dxwrapper 69MB, graphics_driver 63MB, dex 30MB. Found a few orphan
  component `.tzst` (turnip25.1.0, dxvk-2.3.1) — **user chose to leave all assets as-is.** R8 +
  baseline profiles + download-on-first-run (the real 275MB lever) deferred.

## Branding / repo housekeeping (2026-06-18)
- **Repo renamed** `bannerlators` → **`Bannerlator`** (https://github.com/The412Banner/Bannerlator,
  old URL redirects). Local git remote updated; download badge + release-APK name → `Bannerlator-<tag>.apk`.
- **Logo**: replaced placeholder with neon **Bannerlator** banner (`logo.jpg`, 1245×602); old
  `logo.png` removed.
- **README**: professional rewrite (centered header, badges, quick-links nav, sectioned tables).
  Added **Project Notice** = personal continuation of the discontinued/archived Winlator
  *Star Bionic* ([star-emu/star](https://github.com/star-emu/star)); no original devs except
  The412Banner; built on their work + cherry-picked community commits; free to use/share. About
  section moved OUT of README into the GitHub repo "About" description. Discord
  (`discord.gg/n8S4G2WZQ4`) + Telegram (`t.me/The412BannerGaming`) point to The412Banner.
- **Credits expanded**: StevenMXZ (Winlator-Ludashi `ludashi`/`redmagic` variants),
  GameNative (utkarshdalal, Proton bionic layers), Star/Frost dev team (star-emu),
  leegao (BCn/ASTC/ETC Vulkan texture-compression layers), isygold (Star Engine / VEGAS
  Adreno DXVK fork — the `vegas` in `v1.3-vegas`).

## Build/run history
- 2026-06-18 ~23:21 UTC — first action build (run **27795368178**, "Any branch compilation."
  on `main`) for marcescence + orientation fix. **✅ SUCCESS** — artifact `compiled-debug`
  (~541 MB APK). Awaiting device test of the Settings screen.
- 2026-06-18 — README adopted from `The412Banner/star`, adapted for bannerlators (name, badge
  → The412Banner/bannerlators, banner → logo.png). External frontends-guide link left pointing
  at `star-emu/star` (doc not vendored here).
- 2026-06-18 — second action build (run **27797077384**, on `main`) with splash rebrand +
  branding work. **✅ SUCCESS** — artifact `compiled-debug` (~541 MB APK). Still awaiting
  device test (Settings fix + new splash).

## 2026-06-19 — ⚠️ CRITICAL: source was star-compose, NOT marcescence → full re-import
- **Bug found (user-reported):** the new builds were "not from marcescence." Investigation
  proved the initial import (`60dce24`) was a snapshot of **`star-compose/main`**
  (`versionName "7.1.4x-cmod"`, NO product flavors) — the star-compose *predecessor* of
  marcescence, not the 1.4 line. Runs 27795368178 + 27797077384 therefore shipped
  star-compose. Proof: bannerlator tree was 113 files off `star-compose/main` but **600**
  files off `star@marcescence`; `app/build.gradle` was byte-identical to star-compose/main.
- **Fix — full re-import** (commit `c55fe68`): replaced the entire app source with
  **`The412Banner/star @ marcescence`** (`versionName "1.4-marcescene"`, `versionCode 20`,
  **3 product flavors** standard `com.winlator.star` / ludashi `com.ludashi.benchmark` / pubg
  `com.tencent.ig`, `cmod`→`star` package, Vulkan + Compose settings/input tabs + SteamGridDB
  + drive/container pickers). marcescence lives at star@`marcescence` (tip `0139024`); also
  mirrored in private `The412Banner/marcescence-backup`@`f112fd1`.
- **Submodules vendored** as plain files (OpenXR-SDK, adrenotools + nested linkernsbypass);
  `.gitmodules` removed; verified 0 gitlinks staged.
- **CI switched to marcescence's flavor-aware workflows** (bannerlator's old flavor-less CI
  could not build marcescence). Kept marcescence `main.yml` (workflow_dispatch; installs NDK
  26.1.10909125 + cmake; uploads 3 artifacts `standard-debug`/`ludashi-debug`/`pubg-debug`) +
  `release.yml` (workflow_dispatch, input `release_notes`). **Removed 5 extra marcescence
  workflows incl. push-triggered `release-differentpkg.yml`** (would auto-release on push).
- **Add-ons re-applied:** branded README + progress logs; Settings overlapping-rows fix
  re-applied to marcescence `settings_fragment.xml` (all 11 `FieldSet.Dark` → `orientation="vertical"`).
- **Re-import build run 27798743622 = ✅ SUCCESS** — 3 flavor APKs ~588 MB each. Standard APK
  copied to `/sdcard/Download/Bannerlator-1.4-marcescene-standard.apk` (md5 `07c3034244…`).
- **Splash branding port** (commit `ad67a6a`, build run 27799338372): marcescence's
  `SplashScreen.kt` (star pkg) rendered `R.mipmap.ic_launcher_foreground` in a 120dp sparkle
  box + a `"Star Marcescence"` title — so the earlier `splash_logo.jpg` image swap had NO
  visible effect (this is why the user "didn't see" the logo/text change). Replaced the icon
  with the banner logo (`R.drawable.splash_logo`, `fillMaxWidth`) and dropped the title text.
  `SparkleCanvas`/`frameTime` now unused (harmless; no warnings-as-errors).
- **OPEN branding choices (user to decide):** in-app name still marcescence's (`star Bionic` /
  flavor IDs above), not "Bannerlator"; splash version line still reads `v1.4-marcescence`.

## Notes / TODO
- Device-test the Settings screen after the action build (verify rows now stack vertically).
- Dialog layouts (`shortcut_settings_dialog.xml`, `box64_edit_preset_dialog.xml`,
  `screen_effect_dialog.xml`) also use style-only-orientation FieldSet but render in classic
  dialogs (not the Compose AndroidView host), so they're NOT affected by this regression.
- `app/build/outputs/apk/debug/app-debug.apk` is the single debug output path the workflows
  use (confirm flavor handling if multi-flavor APKs are ever needed here).

## 2026-06-22 (PM) — Release builds shipped + 1.5 cut
- Switched all CI workflows debug→release (`chore/release-builds`); fixes laggy Compose UI
  (debug Compose 2–10× slower). Release-build gotchas fixed: `crunchPngs false` (GIF-as-.png
  drawables), `lint{abortOnError false}`, excluded dup okhttp-coroutines artifact, dropped
  hand-committed baseline.prof(m). CI green run `27971367549`.
- ✅ DEVICE-CONFIRMED — user ran release APK, UI lag gone ("works just fine").
- Merged `chore/release-builds` → main; bumped versionCode 22→23, versionName 1.4→1.5
  (`a31bc4b`); splash auto-reads BuildConfig.VERSION_NAME → shows "V 1.5".
- Repo flipped PUBLIC again (was private during bionic-fg work).
- Cut **1.5 release** via release.yml (run 27973731778): release builds + GOG login fix +
  start-menu apps (AIO Graphics Test / Game Controller Test) + WFM drive-icon fix.

## 2026-06-22 (PM) — 1.5 release notes polish + ImageFS-reinstall warning + screenshot
- Reworded the Performance note in BOTH the GitHub release body and README — dropped the
  "debug Compose ran 2–10× slower" phrasing; now reads "implemented to reduce the
  user-interface lag that some users had noticed."
- ⚠️ **Added a crystal-clear "Updating from 1.4? You MUST reinstall imageFS" warning** to the
  1.5 release body + README: open app **Settings → scroll to bottom → Reinstall ImageFS** after
  updating over a 1.4 install (else broken/inconsistent state).
- Committed user's screenshot `docs/imagefs-reinstall.jpg` (1080×1273, the Settings ImageFS
  section) and embedded it in the release body as a **240px clickable thumbnail laid out
  BESIDE the warning text** via a 2-column HTML `<table>` (text left / thumb right). Raw URL
  `…/docs/imagefs-reinstall.jpg?raw=true` verified HTTP 200.
- README also gained (earlier this session) "What's New in 1.5" + previously-undocumented
  "🛒 Built-in GOG store" + "🧰 Bundled Start-menu utilities" sections; WFM credited to
  StevenMXZ Winlator-Ludashi 3.1 hotfix in both README Credits and the release Credits block.

## 2026-06-22 (PM) — GitHub issue triage (repo now public)
- **#4 "Vulkan render" — CLOSED (already fixed).** Two Vulkan-host-renderer-only bugs, both
  already shipped in 1.4 + 1.5: (a) input-control profiles empty on Vulkan → `a77a76f` (moved
  input-controls init before the GL-only early return in `initInlineTabStates`); (b) Task
  Manager empty on Vulkan → `6a69195` (render-independent main-Handler poll; the Compose
  LaunchedEffect{delay()} loop stalls on the Vulkan present path). Filed on 1.3. Replied +
  closed.
- **#2 container crash (S24 Ultra / One UI 8.5 / Android 16) — OPEN.** No such device to repro.
  Replied: update to 1.5 + enable per-container "OneUI / HyperOS Fix" (ContainerDetail →
  Graphics Driver, `fdDevFeatures`) + asked for adb logcat. Left open.
- **#3 frame gen not working on HyperOS 3 — OPEN.** No device to repro. Replied: try the
  "OneUI / HyperOS Fix" option; asked which engine/version/symptom. Left open.
- **#5 on-screen dpad/stick FREEZE on multi-touch — FIXED.** Root cause in
  `widget/InputControlsView.java onTouchEvent`: ACTION_DOWN/UP key on the real
  `event.getPointerId(actionIndex)` but ACTION_MOVE passed the pointer INDEX `i` to
  `ControlElement.handleTouchMove`; that method only tracks D_PAD/STICK/TRACKPAD while
  `pointerId == currentPointerId`, so a 2nd finger shifting the index↔id mapping froze the
  stick/dpad at its last value until release. Fix = `int pid = event.getPointerId(i);` + pass
  `pid` (commit `fba6080`, branch `fix/onscreen-controls-multitouch-freeze`). Verified
  byte-for-byte against Ludashi 3.1 (`StevenMXZ/Winlator-Ludashi@ludashi-3.1`). Universal fix
  (all flavors). ⚠️ CRLF file → byte-exact python edit. Replied on #5. Test build
  `build-artifacts.yml` run 27983069051. NEXT: device-test multitouch → merge → next release.

## 2026-06-23 — File Manager: Back-button nav + Run-exe-in-container (device-found, branch `fix/file-manager-bugs`)
Two device-found bugs in the in-app File Manager (`ui/screens/FileManagerScreen.kt`).
✅ **COMMITTED `5521e0f` + pushed; CI build run `28026509542` (build-artifacts.yml) triggered.**
⏳ **NOT device-confirmed.**

**Bug 1 — system Back exited the File Manager from any depth.** The path-bar ArrowBack button
already went up one dir (clamped to `currentRoot`), but the Android system/gesture Back was
never intercepted, so it popped the whole `FileManager` nav route (`AppNavGraph.kt:85`). Fix:
added `BackHandler(enabled = currentDir != currentRoot)` that goes up exactly one directory;
at the drive's top layer it's disabled so Back propagates and closes the File Manager.
(`androidx.activity.compose.BackHandler`, dep `activity-compose:1.8.2` already present.)

**Bug 2 — Run/tap-exe launched the container but the exe never executed.** `runFileInContainer`
wrote `Exec=<android path>` (no `wine ` prefix, an Android path Wine can't run), the exe's
folder wasn't mapped into the container's drives, AND it passed the extra `desktop_file` while
`XServerDisplayActivity` reads `shortcut_path` (so the shortcut was ignored → fell back to
`wfm.exe`). Root-cause confirmation of the user's "it's listed as Drive F not C" hunch: **F:
vs C: was never the problem** — `Container.DEFAULT_DRIVES = "F:"+externalStorage + "D:"+Downloads`
so internal storage is already F:; Wine runs an exe from any letter (C: is the prefix's internal
`drive_c`, unrelated to phone storage). Fix mirrors the Games "add EXE" flow
(`ShortcutsViewModel.writeExeShortcut`/`resolveWindowsPath`): map the exe folder to a Wine drive
(existing F: for internal storage, else allocate+persist a new letter G–Y), write a `.desktop`
with `Exec=wine <X:\…>` (4-backslash escaping per `StringUtils.unescape`), launch via
`container_id`+`shortcut_path`+`shortcut_name`. User chose **transient run**: `.desktop` written
to app `filesDir/desktops` (NOT the container desktop dir) so it does **not** create a permanent
Games entry. Off-main-thread + failure toast.

**Refactor:** extracted the drive-mapping/path logic into a new shared `core/WinePath.kt`
(`resolveWindowsPath`, `bestDriveMatch`, `allocateDriveLetter`, `escapeForExec`);
`ShortcutsViewModel` now delegates to it so Games-import and File-Manager-Run share one
device-proven code path. Scope = `.exe` (`.msi`/`.bat`/`.sh` get plain `wine <path>`, not
special-cased). NEXT: commit to `fix/file-manager-bugs` → CI build → device-test Back + Run.
✅ Device-confirmed ("works great") + merged to main (ff `d1356d8`→`8e04e4f`, whole batch).

## 2026-06-23 — Per-game Renderer + Frame-Gen engine + FPS limiter (branch `feat/per-game-render-framegen`)
Lets users set Renderer + frame-gen per **game** (shortcut), not only per container. Uses the
app's native override pattern `shortcut.getExtra(key, container.value)` = follow container by
default, override per game, honored at launch. Commit `08878be`, CI build `28030816792`. ⏳ NOT
device-confirmed.

**Scope:** Renderer (OpenGL/Vulkan) + Frame Generation engine (off/bionic/lsfg) + FPS limiter.
Graphics Driver + DXVK were already per-game. **Advanced Vulkan present options deferred** — they're
non-functional today (container `VulkanSettingsDialog` discards its result `ContainerDetailScreen.kt:223`;
launch reads them from `graphicsDriverConfig` via comma-split `KeyValueSet` while that string is
semicolon-separated, so every read defaults) and touch the device-sensitive present path. Future fix
= use the container's dedicated `renderer*` fields as source of truth + per-shortcut extras.

**UI** (`ShortcutsScreen.kt` `ShortcutSettingsDialogScreen`, after DX Wrapper): Renderer dropdown,
FG engine dropdown (lsfg grayed + hint without `filesDir/lsfg-vk/Lossless.dll`), FPS limiter switch.
Each inited from `shortcut.getExtra(key, container.X)`; saved as extras `renderer`/`frameGenEngine`/
`fpsLimiterEnabled` in the `with(shortcut){…}` block.

**Launch** (`XServerDisplayActivity.java`): 3 read-only resolvers near `getExecutable()` —
`resolvedRenderer`/`resolvedFrameGenEngine`/`resolvedFpsLimiterEnabled` (shortcut value if present,
else container). Used at the drawer-state FG sync, the FG/limiter layer setup, renderer init +
HUD label, and the in-game live-tune routing. Shortcut now constructed BEFORE the drawer sync so
overrides resolve. **Read-only by design** — never written back, because the in-game FG/FPS toggle
calls `container.saveData()`; mutating the container at launch would leak a per-game value into the
container and break "follow container." Multiplier/flow stay live-tuned in-game + persisted on the
container, unchanged. CI build `28030816792` triggered. NEXT: device-test → merge to main.

**Advanced Vulkan settings — investigated, DEFERRED, on hold until current build is tested.** Confirmed
the renderer side is fully functional (`VulkanRenderer`: `setVkPresentMode`/`setSwapRB`/`setFilterMode`
call native; `nativeMode` drives the AHB direct-scanout path; `setInitialNativeMode` is the launch
entry point) — so the fix is real wiring, not stubs. Both ends healthy; only the two middle links are
broken: (1) container `VulkanSettingsDialog` discards `onConfirm` + uses `getDefaultVulkanConfig()` as
initial; (2) launch reads native/presentMode/filter/swapRB out of `graphicsDriverConfig` via comma-split
`KeyValueSet` while the string is semicolon-separated → always defaults. The container's dedicated
`renderer*` fields (saved/loaded but never read at launch) are the natural source of truth. Fix plan:
wire dialog→fields + read fields at launch → `vkRenderer.setX`, bypassing the broken config path, then
add per-game extras. Held as its own branch because it touches the device-sensitive present path and
needs a focused device test, not a ride-along with the safe toggles.

## 2026-06-23 — Frame gen starts OFF in-game every launch (branch `feat/framegen-default-off`)
**Request:** Even when frame generation is enabled in a container's settings, it must default to
**OFF in-game on every container launch**. The user opts in per session via the in-game FG drawer.
Container-settings FG = "available/configured", NOT "auto-on at launch."

**Key constraint:** a Vulkan frame-gen layer only loads at PROCESS START — it can't be injected
mid-session (`onBionicFgConfigChange` bails "needs a relaunch" if the layer wasn't loaded). So
"off but enable-able in-game" = **load the layer, start it in passthrough/off**, NOT "don't load it."

**4 edits (off main `2705f1b`):**
1. `XServerDisplayActivity` drawer seed (~568) — seed `setFrameGenMultiplier(0)` always (was
   `lsfgOn ? max(2,saved) : saved`). KEEP `setFrameGenEnabled`/`bionicFgActive` true-when-configured
   so the Off/2×/3×/4× multiplier row still renders and is live.
2. `XServerDisplayActivity` bionic-fg launch conf (~1372) — `writeBionicFgConfig` initial multiplier
   `0` always (was `fgOn ? saved : 0`). KEEP load cond `(fgOn || limiterOn)`. **FPS limiter UNTOUCHED.**
3. `XServerDisplayActivity` lsfg-vk launch conf (~1357) — `writeLsfgConfig(1, …)` passthrough (was
   `max(2, saved)`). KEEP `ENABLE_LSFG=1` so the layer still loads.
4. `XServerDrawer.kt` badge (~525) — `isRunning = layerActive && engine != "off" && initFgMult > 0`
   (was no mult check → green dot would lie while FG idle). Now tracks live as the user toggles.

**Persisted container values stay intact** — only the runtime seed + initial conf change. Net: an
FG-enabled container launches with the layer loaded but OFF (badge grey, row shows Off) → user taps
2×/3×/4× → live-on, no relaunch. ⏳ NEXT: CI build → device-test → merge to main (1.6).

## 2026-06-23 — Investigation: FPS limiter only works with bionic-fg; lsfg-vk has no cap
Not a code change — findings logged so we don't re-investigate.

**Symptom (user):** the FPS limiter doesn't seem to work when lsfg-vk is the selected
frame-gen engine.

**Root cause:** the FPS limiter is implemented BY the bionic-fg layer — applied via
`writeBionicFgConfig(mult, flow, limiterOn, fpsValue)`. The engine branches in
`XServerDisplayActivity` are mutually exclusive (`if(lsfg) … else (bionic)`):
- engine = **bionic-fg** → layer loads → limiter works live (conf.toml hot-reload). ✅
- engine = **off** + limiter on → `if(fgOn||limiterOn)` still loads bionic-fg as a PACER-ONLY
  (multiplier 0) → limiter already works LIVE today. ✅
- engine = **lsfg-vk** → takes the lsfg branch, never loads bionic-fg, lsfg has no cap field →
  limiter silently ignored. ❌ (the gap)

**Verified lsfg-vk (GameNative fork) has NO fps-limit field** by dumping strings of the SHIPPED
binary `app/src/main/assets/lsfg-vk/liblsfg-vk.so` (committed `1997a55`, manifest
`VkLayer_LS_frame_generation.json`). Every key it parses: TOML `exe`/`multiplier`/`flow_scale`/
`performance_mode`/`hdr_mode`/`experimental_present_mode`; env `LSFG_*` equivalents. NO frame-rate/
fps-limit/cap key exists. Same key set as PancakeTAS upstream (whose docs also say to cap externally).
The fork's only addition over upstream = conf.toml live-reload (`Rereading configuration`).

**GOTCHA — CMakeLists points at the WRONG source.** `app/src/main/cpp/lsfg-vk/CMakeLists.txt` +
`build-lsfg-android.sh` FetchContent `PancakeTAS/lsfg-vk@v2.0.0-dev` and output
`libVkLayer_LSFGVK_frame_generation.so` — but the SHIPPED layer is the prebuilt GameNative-fork
`liblsfg-vk.so` (different name). The CMake path is dead/aspirational; rebuilds must use the
GameNative fork, not that CMakeLists.

**Options for lsfg-vk + limiter (deferred, its own branch + device test):**
1. `DXVK_FRAME_RATE` env — launch-time only (DXVK reads once at start, NO hot-reload) + DXVK-only
   (no vkd3d/D3D12/native-Vulkan). Can't back a live in-game toggle. Weak fit.
2. Stack the bionic-fg pacer (multiplier 0) under lsfg-vk → live + all-API, but two present-hooking
   layers on one swapchain — needs device test for conflicts. **Preferred** (see reference design).

**REFERENCE DESIGN — how GameHub 6.0.9 does live, all-API fps limit** (verified in
`~/gamehub-6.0.9-jadx`): a **shared-memory present-level pacer**, not env vars. `tn2.java:1384-1392`
maps a 9-byte mmap'd file (`RandomAccessFile`→`FileChannel.map(READ_WRITE,0,9L)`) shared between the
app and the native wine process (offset 0 = short fps, offset 2 = a bool byte). Applier `f5o.h(int)`
does `putShort(clamp(fps)); force()` — same method called live from the in-game slider (`ba.java:92`)
and at launch (`lbo.java:71`). The native present loop in `libwinemu.so` reads the short EVERY FRAME
and paces (no "fps" string literals in the `.so` — raw offset read). Live = polled shared memory;
all-API = enforced at the present/swap layer ALL renderers funnel through. This is the same category
as our bionic-fg pacer — confirming option 2 (keep a present-level pacer loaded regardless of FG
engine) is the right shape, and DXVK_FRAME_RATE is a strictly worse imitation.

## 2026-06-23 — Standalone FPS limiter (host-side pacer) — branch `feat/standalone-fps-limiter`
Make the FPS limiter independent of frame gen so the in-game toggle caps fps with bionic-fg,
lsfg-vk, or Off — all guest APIs, both host renderers. GameHub-model: a host-side present pacer,
NOT a Vulkan layer (so no two layers stacked). User OK'd OUTPUT-cap semantics (on-screen fps),
replacing the old bionic-internal BASE-cap (which gave on-screen = limit × multiplier).

Completed the pre-existing-but-unwired `HostRenderer.setFpsLimit(int)` scaffold:
- **VulkanRenderer (native):** `VulkanRendererContext` gains `targetFrameIntervalNs` (atomic) +
  `paceFrame()` (absolute-time `clock_nanosleep` on CLOCK_MONOTONIC, no render locks held, rebases
  when behind). Called in `renderLoop()` before `renderFrame()` gated on `needsRender` (composite
  present), and in `scanoutSetBuffer()` before `ST_APPLY` (Native-Rendering/scanout present). New
  `nativeSetFpsLimit` JNI; `VulkanRenderer.setFpsLimit()` calls it live + re-applies on surface
  (re)create.
- **GLRenderer:** implemented the empty `setFpsLimit()` + `paceFrame()` (nanoTime/Thread.sleep) at
  the end of `onDrawFrame()` (before the implicit eglSwapBuffers).
- **Wiring:** new `XServerDrawerState.onFpsLimitChange`; the in-game Limit-FPS toggle/slider routes
  to it (was `onBionicFgConfigChange`); the activity handler calls `renderer.setFpsLimit(on?val:0)`
  + persists to the container. Applied once at launch (`setupUI`, after renderer created).
- **Decoupled from bionic-fg:** `onBionicFgConfigChange` no longer writes the limiter (passes
  false/0); the bionic-fg launch layer loads only when FG engine = bionic (dropped the
  `|| limiterOn` and the off-loads-bionic-as-pacer hack); drawer `bionicFgActive` no longer
  includes the limiter.
- **UI:** FPS Limiter section always available (removed the `bionicFgActive` gate + relaunch hint);
  text now "Caps on-screen FPS. Works with any frame-gen engine or none."

OPEN device-test unknown: does host pacing THROTTLE the guest (saves GPU/battery via backpressure)
or just DROP frames (cap visual only)? Needs a device check. Files: VulkanRendererContext.{h,cpp},
VulkanRendererScanout.cpp, vulkan_jni.cpp, VulkanRenderer.java, GLRenderer.java,
XServerDrawerState.kt, XServerDrawer.kt, XServerDisplayActivity.java.

**Build:** commit `f8d7598`, CI "CI Build (artifacts only)" run `28037736579` ✅ GREEN (native pacer
+ JNI + both renderer paths compile, full APK builds). ⏳ NEXT: device-test (all three FG modes ×
both host renderers; live slider; throttle-vs-drop check).

## 2026-06-23 — FPS limiter REWORK: guest-side via X11 Present IdleNotify pacing (the GameNative way)
Device test of the host-side pacer FAILED: it capped the DISPLAY (OS overlay showed the cap) but
NOT the game — DXVK HUD stayed at 50, GPU 98% (no backpressure to the guest). Recon of GameNative +
Ludashi 3.1 found the real mechanism: pace the **X11 Present extension** by DELAYING the IdleNotify
that tells the guest its buffer is free to reuse → the game (DXVK/vkd3d/...) BLOCKS → it throttles
itself → in-game HUD reflects the cap + GPU drops. Live, all host renderers, all APIs.

- REVERTED the host-side nanosleep pacer (VulkanRendererContext paceFrame/setFpsLimit + renderLoop
  call + time.h, VulkanRendererScanout paceFrame, vulkan_jni nativeSetFpsLimit, VulkanRenderer
  native decl/call/onSurfaceCreated, GLRenderer paceFrame/onDrawFrame). Kept VulkanRenderer's scanout
  SurfaceControl.setFrameRate hint (GameNative's secondary mechanism) and GLRenderer's empty stub.
- PORTED into our `PresentExtension`: `frameRateLimit` + `setFrameRateLimit`, per-window `WindowTiming`
  pacing, `emitIdleNotify` (delays IdleNotify to `nextIdleNs += 1e9/fps`), fired via Choreographer
  (vsync) or a MAX_PRIORITY CPU pacer thread fallback. Applied in all 3 present branches (FLIP/COPY/
  copyArea). Mirrors GameNative `xserver/extensions/PresentExtension.java`.
- WIRED: `XServerDisplayActivity.applyFpsLimit(fps)` → `PresentExtension.setFrameRateLimit` (+ renderer
  scanout hint); called from `onFpsLimitChange` (live in-game) and at launch from the resolved
  container/per-game value. Kept the bionic-fg decoupling + always-available UI from the prior commit.
- TODO (refinement, not blocking): when lsfg multiplier ≥ 2, force limit 0 (lsfg paces its own output)
  like GameNative — not yet wired (first cut targets the FG-off + bionic cases the user tested).

**Build:** commit `bd990b2`, CI run `28043133606` ✅ GREEN (PresentExtension IdleNotify pacer + wiring
compile, full APK builds). ⏳ NEXT device-test: FG-off + Limit FPS 30 → DXVK HUD should drop to ~30 +
GPU load drop (was pegged 98%); confirm live slider + bionic-fg case.

## 2026-06-26 — 1.9.1 hotfix (per-flavor provider authorities) + GameNative SurfaceFlinger fixes recon
**Bug:** the PUBG (`com.tencent.ig`) and Ludashi (`com.ludashi.benchmark`) flavors failed to install
alongside the standard (`com.winlator.banner`) flavor with a package conflict
(`INSTALL_FAILED_CONFLICTING_PROVIDER`). Root cause = both `<provider>` authorities in
AndroidManifest were hardcoded to the `com.winlator.star.*` namespace, so all three flavors declared
the SAME authorities; content-provider authorities must be globally unique per device.

**Fix (branch `fix/per-flavor-provider-authorities` → merged ff to main `ca21dae`, branch deleted):**
- `AndroidManifest.xml`: `tileprovider` + `WinlatorFilesProvider` authorities → `${applicationId}.*`.
- `UpdateManager.kt`: derive FileProvider authority at runtime from `packageName` (was a fixed const →
  would throw on ludashi/pubg).
- `SavesViewModel.kt`: save-share used a stale, never-declared authority (`com.winlator.fileprovider`)
  that threw on every flavor → pointed at the per-flavor `.tileprovider`.
- CI `28254302439` ✅ GREEN; device-confirmed (pubg installs beside standard).

**🏁 1.9.1 STABLE CUT 2026-06-26** (versionCode 29→**30**, versionName 1.9→**1.9.1**, main `3574cc2`).
- `release.yml` run `28259384338` ✅ — tag `1.9.1`, prerelease:false, make_latest:true; `releases/latest`
  → 1.9.1; 3 flavor APKs + `update.json` (vc30) attached. Per the versioning hard rule (plain numeric
  tag = stable; patch `X.Y.Z` is fine on explicit user say-so).
- Fixed a cosmetic `${applicationId}`→`()` shell-expansion in the `update.json` notes (re-uploaded via
  `gh release upload --clobber`); release body was already correct.
- Rewrote the 1.9.1 release body to the 1.9 layout + a "🩹 hotfix" callout.
- README updated (`c3ff5ba` then `b5d1c13`): Version row → 1.9.1/vc30; "What's New" now shows ONLY the
  latest release (dropped the "Previously in" 1.9/1.8/1.7/1.6 sections per user).

**🔎 GameNative SurfaceFlinger (ASR) upstream-fixes recon (user asked):** nothing merged to GN `master`
since the original ASR merge (#1582); 3 OPEN fix PRs — **#1620** (fences + color-format → prevent SF
crash + lib rename; mergeable; top port candidate for our experimental reboot risk), **#1622** (R/B
channel swap: alloc scanout AHBs RGBA not BGRA — Adreno HWC), **#1612** (setFrameRate refresh-rate
match). #1620 nightly.link artifact = a full `app.gamenative` v1.1.0 test APK (carries the fixed
`libasurface_renderer.so` + renamed `libahbimage.so`) → A/B-test only; `.so` not graftable into our
tree (Java/JNI changed) — port from source. Details in memory
`reference_gamenative_surfaceflinger_renderer.md`.

## 2026-06-29 — AIO Graphics Test cube-cull fix re-bundled into container template
The DX10/DX11 plain cube + cube-grid scenes rendered inside-out (CCW-front geometry hit
D3D's default CULL_BACK + CW-front since those scenes never set a rasterizer). Fixed in
AIO-Graphics-Test (global CULL_NONE, matching the GL/Vulkan cubes), CI run 28344205408,
device-proven. Swapped the rebuilt 32/64-bit exes into
`app/src/main/assets/container_pattern_common.tzst` (drive_c/AIO Graphics Test/) via
tar --delete + --append on the decompressed tar: only the 2 exes change, all other 100
entries byte-identical, swapped exes carry sibling metadata (uid 10314/gid 1023, mode
0660, mtime 2026-06-22 09:00). 102/102 entries; embedded exes MD5-match the CI artifacts
(64:0d12d92… 32:004678a7…). Committed `0eb3cde`, ff-merged to main + pushed.
⚠️ TEMPLATE = NEW containers only — existing containers keep the old exe (reinstall-imagefs
preserves home/), so to update them push the exes via root bridge or make a new container.
Lands in the next Bannerlator build; no tag/release cut.

## 2026-07-01 — Steam store Compose M3 restyle (branch feat/steam-store-compose)
Decision: keep our JavaSteam backend (same lib family GameNative uses; theirs is GPL-3.0 +
proprietary blobs + monolithic coupling — vendoring rejected), rebuild only the UI/UX. The five
Steam screens were already Compose, just hardcoded 0055FF/black — restyled all onto live
MaterialTheme.colorScheme tokens (`0ca11e0`): library header→icon buttons, grid tiles→2:3
Shortcuts idiom, list items→ContainerItem cards, detail screen Int-color state→semantic enums
(InstallAction/PauseAction/GameStatus) resolved in composition, login/QR themed, onPrimary
everywhere (white-accent-safe). Backend files untouched. Local compileLudashiDebugKotlin GREEN
(aapt2 override needed in PRoot: -Pandroid.aapt2FromMavenOverride=termux aapt2).
NEXT after this proves out: smart container-picker (rank existing containers; NO auto-create per
game — user rule) + community-config recommendations from The412Banner/bannerhub-game-configs
(2,869 games, per-device SoC-keyed GameHub-schema configs; needs precomputed aggregation +
name→appId mapping + component-version translation). Gate: CI green then on-device visual pass
(grid density, download/pause/cancel states, white-accent preset).
Update: branch pushed; CI artifacts run 28556819972 (label steam-store-m3) in flight with watcher;
root bridge pinged OK — on green, APK goes to device /sdcard/Download for the visual pass.

## 2026-07-01 (cont.) — Compose container picker + result dialog; black-toast root cause fixed
Device pass of build 1 found the add-to-shortcuts toast = BLACK BOX: targetSdk 28 renders toasts
in-app with @style/AppTheme = AppCompat.Light + colorBackground forced #000000 → black-on-black.
Replaced the whole handoff UI (`3910337`): StarLaunchBridge split (loadContainers/writeShortcutAsync
result callbacks; legacy dialog+toast path kept for Epic/GOG/Amazon but toasts now explicit
white-on-grey custom view), new store/compose/AddToShortcutsFlow.kt (M3 ContainerPickerDialog +
AddResultDialog), Steam screens wired to it, MainActivity EXTRA_OPEN_SCREEN deep-link (validated
against drawerItems; onNewIntent + pendingRoute→LaunchedEffect nav) so "Open Shortcuts" jumps
CLEAR_TOP|SINGLE_TOP into the Shortcuts (Games) route. Kotlin+Java compile GREEN. Device gate:
picker rows, result dialog, deep-link from store stack, legacy toast readability.

## 2026-07-01 (cont.) — PLAN: Goldberg auto-patch button (regular/experimental/coldclient ladder)
Motivation: device-tested Brawlhalla (installed via depot DL) — Goldberg got it BOOTING but hit
Brawlhalla "Error 3003" = its own SERVER connection fail (online-only game), NOT a Steam-client
error. Lesson baked into UI copy: Goldberg unlocks DRM/ownership-gated games so they START without
a running Steam client; it CANNOT make online-only games reach publisher servers. App has ZERO
Goldberg handling today (grep clean) — net-new feature. Goldberg folder on device =
/storage/emulated/0/Winlator/Games/Goldberg = gbe_fork build (regular/ = steam_api only;
experimental/ = steam_api + steamclient; steamclient_experimental/ = ColdClientLoader + loader exe;
tools/generate_interfaces; steam_settings.EXAMPLE/). Game installed under imagefs (ludashi pkg).

DESIGN (agreed): opt-in TOGGLE on game detail page, escalating tiers Off→Regular→Experimental→
ColdClient, each framed "try if previous failed". Store's edge = we KNOW appId+installDir → auto
steam_appid.txt (kills the appid=0 failure class).
ORDER OF OPS:
 SHARED PREP (once): (1) scan installDir for all steam_api(64).dll, record path+arch via PE header;
 (2) if none → message "game doesn't use Steam API, N/A"; (3) byte-scan ORIGINAL dll for
 SteamClient0xx interface strings → steam_settings/steam_interfaces.txt (reimplement
 generate_interfaces, no foreign binary); (4) back up each original → .dll.bak (only if absent =
 permanent pristine source); (5) write steam_settings/steam_appid.txt + steam_appid.txt beside each
 dll (known appId).
 TIER1 Regular: copy regular/<arch>/steam_api(64).dll over each. Default.
 TIER2 Experimental: experimental/<arch>/steam_api(64).dll + steamclient(64).dll alongside.
 TIER3 ColdClient: RESTORE original steam_api, drop steamclient_experimental files, gen
 ColdClientLoader.ini (appId+exe+rundir), REPOINT launch shortcut Exec→steamclient_loader_x64.exe.
GOLDEN RULES: always apply FROM .bak (restore-then-apply on every tier switch — idempotent, no
stacking); "Restore original" always available (also restores shortcut Exec for ColdClient).
WRINKLE: ColdClient changes launch command → per-game Goldberg mode must be stored + resolved at
shortcut-write/launch time (coordinates w/ StarLaunchBridge). Tiers 1-2 = pure file swap, no launch
change. Bundle a pinned Goldberg (gbe_fork) in app assets, extract to imagefs on first use (turnkey,
don't depend on user's Winlator/Games/Goldberg folder). Sequenced on branch stacked on
feat/steam-store-compose (picker work still device-gating build 2 run 28560163675).

## 2026-07-01 (cont.) — Goldberg auto-patch BUILT + catalog PUBLISHED
Engineer built the full feature on feat/steam-goldberg-patcher (commit 4563ae2, off device-proven
feat/steam-store-compose). Kotlin+Java compile GREEN. Two-pass: assets-bundle first, then reworked
to DOWNLOAD-ON-DEMAND per user request (mirror ReShade). GoldbergComponent.kt: goldberg.json →
Downloader.downloadFile → UPPERCASE-MD5 → TarCompressorUtils.extract(ZSTD) → {filesDir}/imagefs/
opt/goldberg/ (installed = dir exists, marker regular/x64/steam_api64.dll). Detail page shows
"Download Steam Emulator (~size)" until installed, then tiers Off/Regular/Experimental/ColdClient
on every game page (one global DL). Per-game mode in SteamPrefs goldberg_mode_<appId>.
GoldbergPatcher.kt: PE-header arch detect, interface-string scan→steam_interfaces.txt+steam_appid.txt,
restore-from-.bak-first golden rule, resolveLaunchExe (ColdClient→loader exe), honest online-only
UI caveat.
PACKAGE PUBLISHED (LIVE): pulled exact gbe_fork off device (byte-verified), rebuilt clean combined
goldberg.tzst (tier folders at root, essentials only) SIZE=35938867 MD5=BC48B103AD3B067D3ED7CDFDAF728A4A
(round-trip identical). Repo The412Banner/winlator-contents: release goldberg-v1 asset goldberg.tzst
(HTTP 200) + goldberg.json at repo root main. ColdClientLoader.ini = standard gbe_fork keys (verified).
GATE: NOT device-proven (patch+launch unexercised). Pushing branch + CI build for device test.

## 2026-07-01 (cont.) — branch consolidation
Deleted feat/steam-store-compose (local + remote). Verified it had ZERO unique commits — the
device-proven store rebuild (M3 restyle + Compose container picker + result dialog + toast fix)
is fully contained in feat/steam-goldberg-patcher, which was branched off it (strict superset:
store work + 2 Goldberg commits). Single line forward = feat/steam-goldberg-patcher. No code lost.
NOT merged to main; Goldberg still device-gating (CI build 28561426643).

## 2026-07-01 (cont.) — Goldberg DEVICE-PARTIAL-PROVEN (screenshots)
On the CI build (feat/steam-goldberg-patcher): download button appeared → fetched goldberg.json →
downloaded the published goldberg.tzst → MD5-verified → extracted; result dialog "Steam Emulator
installed. Pick a mode below." then the themed tier selector (Off/Regular/Experimental/Cold Client
Loader) renders correctly on colorScheme with the honest online-only caveat copy. CONFIRMED on device:
catalog delivery (my winlator-contents goldberg-v1 asset), global install gating, themed UI. STILL
UNPROVEN: actual patch→launch (DLL swap gets a game past the Steam check) — needs a SINGLE-PLAYER
Steam title (shown on Brawlhalla = online-only, so Error 3003 is server-side, not a patch validation).

## 2026-07-01 (cont.) — Steam download size/progress fix + overlapping dual-color bar
Commit ad4887f on feat/steam-goldberg-patcher (Kotlin+Java green on ludashi AND standard flavors).
native-steam-engineer decompiled javasteam-depotdownloader-1.8.0.jar, read DepotDownloader.getDepotInfo
selection logic, mirrored it EXACTLY in SteamRepository PICS parse: count a depot only if it has a
public manifest AND (no config OR oslist empty/contains windows) AND language empty/english AND not
lowviolence; osarch NOT checked (matches our AppItem downloadAllArchs=true). Fixes inflated size
(HL2 ~14.7GB→~half) since it no longer sums mac/linux/other-language/optional depots. Progress
denominator switched to summed SELECTED-depot manifest bytes (numerator+denominator same units).
onChunkCompleted now sums bytes across ALL depots via two ConcurrentHashMap<Int,Long> (install=
uncompressed, download=compressed) → fixes the ~50% stall (was tracking only largest single depot).
DUAL BAR: event now DownloadProgress:appId:installDone:installTotal:downloadDone:downloadTotal;
detail-page LinearProgressIndicator → Box track (surfaceVariant) + lighter download fill
(primary alpha .4) + solid install fill (primary) on top. downloadTotal from parsing
manifests/public/download (compressed), in-memory downloadSizeByApp map, NO DB schema change (still v3).
Pause/resume seeds both accumulators from persisted install bytes (download approximated by install
fraction on resume, dlog'd). Parser back-compat: dDone/dTotal default to install values.
GATE: device — HL2 size ~half + logcat "depots: selected=/skipped=" line; smooth 0→100 dual fills on
a multi-depot download; paused/resumed mid-download percentage sane.

## 2026-07-01 (cont.) — end of session; user testing in the morning
CI build 28563136132 (label steam-dl-progress) building overnight off feat/steam-goldberg-patcher
(tip = size/progress fix + dual bar ad4887f). User will grab the ludashi APK from the run page and
device-test in the morning (2026-07-02). MORNING CHECKLIST:
 1. Goldberg patch→launch on a SINGLE-PLAYER Steam title (Regular→Launch, past the Steam check) —
    the one still-unproven piece (download+UI already device-proven; Brawlhalla can't validate = online-only).
 2. Half-Life 2 (appid 220) size reads ~half of the old ~14.7GB; logcat "depots: selected=/skipped=" confirms set.
 3. Multi-depot download: bar climbs smooth 0→100, no ~48-50% stall; dual fills (solid install + lighter download).
 4. Pause/resume mid-download: percentage stays sane.
Branch NOT merged; accumulating (store rebuild + Goldberg + dl fix all on feat/steam-goldberg-patcher).

## 2026-07-01 (cont.) — BUG TO CHASE IN MORNING: download fails ~1hr after login (session expiry)
User report: logged in, ~1hr later can't start a game download. Suspects login/refresh-token needs
refreshing. QUICK DIAGNOSIS (code read, NOT yet confirmed on device):
LIKELY ROOT CAUSE = SteamRepository.onLoggedOff (LoggedOffCallback, :485-489) just sets loggedIn=false
+ emit("LoggedOut") and does NOT auto re-login with the stored refresh token. Contrast onDisconnected
(:445-464) which DOES auto-reconnect+relogin. So a socket DISCONNECT self-heals, but a Steam-side
LOGGED-OFF (session/access-token timeout after idle, ~1hr) leaves loggedIn=false with no recovery.
Then the download path (SteamDepotDownloader :158-182): steamClient non-null, but if !isLoggedIn it
calls ensureLoggedIn() (SteamRepository :831-834) which fires loginWithToken ASYNC (non-blocking) and
returns immediately → download proceeds before re-logon completes; licenses may still be cached
(onLoggedOff does NOT clear them) so the empty-check passes, but depot-key/manifest requests need a
live authenticated session → fail. User's "refresh token" intuition is basically right: token is
stored + could re-login, but nothing triggers it on LoggedOff and the download's ensureLoggedIn
doesn't WAIT.
MORNING: (1) reproduce + capture `getlog com.winlator.banner` at the moment of the failed download —
confirm whether onLoggedOff fired (LoggedOff:<result>) vs onDisconnected. (2) FIX DIRECTIONS:
  a. onLoggedOff: if !userInitiated && refresh token stored → auto loginWithToken (mirror
     onDisconnected auto-reconnect). Refresh token is long-lived; re-logon mints a fresh access token.
  b. Make download's ensureLoggedIn BLOCK on the LoggedOn callback (latch + timeout) before firing
     depot requests, so re-login completes first.
Note: access tokens from refresh-token logon ~24h, but CM session drops on idle far sooner; app
backgrounding + foreground-service socket idle is a plausible trigger. NOT fixed tonight (needs repro).
