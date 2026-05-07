# Star-Compose — Progress Log

## 2026-05-07 — Shortcut Settings tab-switch state-loss fix (`fix/shortcut-envvars-tab-switch-loss`)

**Branch:** `fix/shortcut-envvars-tab-switch-loss` off `beta4`

**Bug reported by user:** Same tab-switch state-loss seen earlier in Container Settings was also reported in **Shortcut Settings** — editing a shortcut, adding an env var, switching tabs, then switching back: the var is gone.

**Root cause:** Identical pattern to the Container Settings fix from earlier today. `ScEnvVarsTab` and `ScAdvancedTab` in `ShortcutsScreen.kt` host legacy Java widgets (`EnvVarsView`, `CPUListView`) via `AndroidView`. Source of truth lives inside the widget; parent `save()` reads it through a `MutableState<View?>` ref. Tab switch destroys the Composable + widget, taking unsaved input with it. Fallback (`shortcut.getExtra(...)`) returns the stored extra, which was never updated during editing.

**Fix:**
- `ScEnvVarsTab` already receives `shortcut` — added `DisposableEffect(Unit) { onDispose { ... } }` that flushes `envVarsViewRef.value?.envVars` into `shortcut.putExtra("envVars", ...)` before disposal. `Shortcut.putExtra` mutates only the in-memory JSONObject; disk persistence still happens later via `save() → saveData()` so Cancel still discards correctly.
- `ScAdvancedTab` doesn't receive `shortcut` directly. Added an `onCpuListSnapshot: (String) -> Unit` parameter; the parent passes `{ shortcut.putExtra("cpuList", it) }`. DisposableEffect inside the tab calls the snapshot.
- Added explicit `import androidx.compose.runtime.DisposableEffect` (file uses explicit imports).

**Files touched:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ShortcutsScreen.kt`

---

## 2026-05-07 — Container Settings tab-switch state-loss fix (`fix/envvars-tab-switch-loss`)

**Branch:** `fix/envvars-tab-switch-loss` off `beta4`

**Bug reported by user:** In Container Settings, after adding Environment Variables, switching to the Drives tab loses all the edits. They only persist if OK is clicked while still on the Env Vars tab.

**Root cause:** `EnvVarsTab` and `AdvancedTab` (CPU pin lists) use legacy Java widgets (`EnvVarsView`, `CPUListView`) hosted via `AndroidView`. The widget's internal state is the source of truth, with the parent screen reading it on save through a `MutableState<View?>` ref. When the user leaves the tab, the Composable disposes, the AndroidView destroys the underlying Java widget, and the user's unsaved input dies with it. The save path's fallback (`viewModel.envVarsStr`) is stale because nothing ever writes to it during editing.

**Fix:** Add a `DisposableEffect(Unit)` to both `EnvVarsTab` and `AdvancedTab` that, on dispose, reads the current widget value through the ref and writes it back to the corresponding `var` on `ContainerDetailViewModel` (`envVarsStr`, `cpuList`, `cpuListWoW64`). The `var ... by mutableStateOf(...)` setters cause the next composition to seed the widget from the latest value, so a round-trip Drives → Env Vars now shows the user's edits intact.

**Files touched:**
- `app/src/main/java/com/winlator/cmod/ui/screens/ContainerDetailScreen.kt` — added two `DisposableEffect` blocks (lines ~470, ~610)

**Long-term:** Replace `EnvVarsView` and `CPUListView` legacy widgets with native Compose components that two-way-bind the ViewModel directly (same pattern as `DrivesTab` already uses). Tracked under `project_star_compose_ui_cleanup.md` Java/XML→Compose migration.

---

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
| fix: skip enumerateExtensions for AdrenoTools (wrong approach) | `fc2b422` | — | superseded | 2026-04-19 |
| fix: guard atVersionsLoaded race (wrong approach) | `113b483` | — | superseded | 2026-04-19 |
| fix: restore enumerateExtensions on main thread | `8035420` | `24661167232` | ✅ green (partial — isDriverSupported still on IO) | 2026-04-20 |
| fix: move isDriverSupported() to main thread — full fix | `e22815c` | `24662739330` | ✅ green ✅ confirmed working | 2026-04-20 |

---

## Current Job

**→ ALL 8 JOBS COMPLETE + AdrenoTools/Turnip driver SIGSEGV fixed** ✅

---

## Recent Work (2026-04-22)

| Commit | Description | CI Run | Result |
|---|---|---|---|
| `dfd9ba6` | Fix null icon crash when pinning shortcut | — | pre-existing |
| `d1be7af` | Fancy splash screen + fix custom accent theme colors | `24788064210` | ❌ failed (missing clipRect import) |
| `3ee9fee` | Merge 5 upstream commits from kalteatz24/winlator-test | — | — |
| `65712d6` | fix: add missing clipRect import in SplashScreen | `24788390969` | ✅ green |

### Splash Screen enhancements (`SplashScreen.kt`)
- White 4-point sparkles floating around logo
- Logo pulse animation (scale 1.0→1.07)
- Glowing progress bar with shimmer sweep
- Cycling status text with animated dots
- Smooth animated percentage counter
- Proceed button fades/scales in on completion

### Theme fix (`AppThemeState.kt`)
- Custom accent now inherits background/surface from the active preset instead of resetting to gray

### Upstream merge (5 commits from kalteatz24)
- Remove numControllers event file pre-creation (XServerDisplayActivity)
- Add PlugPlay to changeServicesStatus exclusion list (WineUtils)
- Remove softRelease() method (FakeInputWriter)
- Switch controller disconnect from softRelease to destroy (WinHandler)
- Fix busy-loop + dead-file detection in native read() hook (fakeinput.cpp)

| `e8acb58` | fix: wire drawer and top bar to MaterialTheme colors | `24790554006` | ✅ green |
| `f79f1ef` | feat: migrate in-game side drawer to Jetpack Compose | `24792494907` | ❌ failed (@JvmField fix needed) |
| `9289d57` | fix: @JvmField + Runnable for Java callbacks | `24793070513` | ✅ green |
| `3659f74` | Merge compose-ingame-drawer → main | `24793931421` | 🔄 in progress |

**Last commit:** `3659f74` (main)  
**Last CI:** `24793931421` ✅ green (2026-04-22)

---

## VKD3D / DX12 Fix (2026-04-22→23) — MERGED TO MAIN ✅

**Branch:** `fix-vkd3d-content-profiles` → merged to main (`289609d`)
**CI Run:** `24835832785` (Manual Release Build, main)
**Verified:** D3D12 tests passing with content-profile VKD3D versions

### Root cause
The Compose migration broke VKD3D content-profile extraction in three ways:

1. **`loadVkd3dVersionList` identifier mismatch** — original code used `VKD3DVersionItem.getIdentifier()` which always produces `verName+"-"+verCode` (e.g. `"3.0b-0"`). The Compose migration changed this to omit verCode when 0, producing bare `"3.0b"`. This single-dash entry name crashed `getProfileByEntryName` via `substring(6,5)`, silently returning null.

2. **`getProfileByEntryName` crash on 2-part names** — fixed to handle both single-dash (verCode defaults to 0) and non-numeric verCode cases. Also gates match on install dir existing.

3. **Cache poisoned on failed extraction** — `extractDXWrapperFiles` was void; cache updated regardless of success, permanently locking the container. Changed to boolean; cache only updated on `return true`.

### Files changed
- `DXVKConfigDialog.java` — use `VKD3DVersionItem.getIdentifier()` in `loadVkd3dVersionList`
- `ContentsManager.java` — `getProfileByEntryName` single-dash + non-numeric verCode fix
- `XServerDisplayActivity.java` — `extractDXWrapperFiles` returns boolean; cache gated on success

---

## VKD3D / DX12 Debug Session (2026-04-22) — ROLLED BACK

**Restore point tag:** `pre-vkd3d-fixes` → commit `8485ac0`  
**Current main:** `8485ac0` (force-pushed back to restore point 2026-04-22)

### What was tried
A multi-session investigation into why D3D12 games fail with `E_NOINTERFACE` (0x80004002) in star-compose.

**Root causes identified:**
1. `ContentsManager.getProfileByEntryName` crashes on single-dash version strings like `"vkd3d-3.0b"` → `StringIndexOutOfBoundsException`, silently returns null
2. `extractDXWrapperFiles()` was `void` — cache updated even on failed extraction, poisoning containers permanently
3. Content server naming convention (`Vk3dk-arm64ec-3.0b`) doesn't match stored container configs (`3.0b`) — content-profile path never works for 3.0b
4. Built-in assets (2.8, 2.14.1) work fine; only content-profile / 3.0b versions were broken

**Fixes developed (all rolled back):**
- `getProfileByEntryName` single-dash + non-numeric verCode fix
- `extractDXWrapperFiles` → returns boolean; cache gated on success
- `DX_EXTRACT_VERSION` stamp to force cache bust across installs
- `VKD3DVersionItem` used in `loadVkd3dVersionList` for correct identifier format
- `vkd3d-3.0b.tzst` (4.8MB) bundled as asset alongside 2.8 and 2.14.1
- "3.0b" added to `vkd3d_version_entries` in arrays.xml

**Why rolled back:** User decision — returning to clean restore point to re-approach differently.

**Next session:** All the above fixes are documented and ready to re-apply. The bundled-asset approach (vkd3d-3.0b.tzst in assets/dxwrapper/) is the cleanest solution since content-server naming will never match container configs without a deeper refactor.

---

## Current Job (2026-04-22)

**Branch:** `compose-ingame-dialogs` → merged to main (`555eead`)  
**CI Run:** `24796752111` ✅ green (2026-04-22)

### In-game dialog migration (`compose-ingame-dialogs` branch)

**New files:**
- `ui/XServerDialogState.kt` — master bridge singleton (StateFlows + fun interface callbacks for all 8 dialogs + 2 overlays)
- `ui/XServerDialogHost.kt` — root composable + `setupDialogHost()` called from Activity; hosts all dialogs + overlays
- `ui/dialogs/VibrationDialog.kt` — multi-checkbox AlertDialog for controller vibration slots
- `ui/dialogs/DebugDialogContent.kt` — scrollable log viewer with Clear/Pause/Resume; LazyColumn auto-scrolls to bottom
- `ui/dialogs/InputControlsDialog.kt` — profile picker dropdown + 3 checkboxes + Profile Settings button
- `ui/dialogs/ScreenEffectsDialog.kt` — brightness/contrast/gamma sliders + shader checkboxes + profile add/remove
- `ui/dialogs/ActiveWindowsDialog.kt` — 2-column grid of windows with screenshot thumbnails (captured async with 100ms stagger)
- `ui/dialogs/TaskManagerDialog.kt` — process list with 1s refresh loop via LaunchedEffect; CPU + memory stats
- `ui/overlays/MagnifierOverlay.kt` — draggable floating panel with zoom +/- and hide button
- `ui/overlays/FSROverlay.kt` — draggable floating FSR/HDR control panel; live updates on every control change

**Activity changes (`XServerDisplayActivity.java`):**
- Removed: `DebugDialog`, `MagnifierView`, `TaskManagerDialog`, `ActiveWindowsDialog`, `ScreenEffectDialog`, `FSRControlFloatingDialog` direct instantiation
- Added: `XServerDialogHostKt.setupDialogHost()` call with full-size ComposeView in `FLXServerDisplay`
- All 8 drawer callbacks now populate `XServerDialogState` and call `show(ActiveDialog.XXX)`
- Debug log: `ProcessHelper.addDebugCallback(line -> dialogState.appendLog(line))` (no more DebugDialog instance)
- New private methods: `showScreenEffectsDialog()`, `showFsrOverlay()`, `showMagnifierOverlay()`, `showTaskManagerDialog()`, `findAppWindowsForCompose()`, `applyScreenEffects()`, `saveScreenEffectProfile()`, `updateTmCpuMemory()`

### In-game drawer migration (`compose-ingame-drawer` branch)
- `XServerDrawerState.kt` — singleton bridge (StateFlows + nullable callbacks) for Java↔Compose
- `XServerDrawer.kt` — full Compose drawer UI: all 15 menu items, collapsible Mouse & Cursor section with AnimatedVisibility, stateful pause/play icon, conditional Magnifier + Logs items
- `xserver_display_activity.xml` — `NavigationView` replaced with `ComposeView` (300dp, gravity=start)
- `XServerDisplayActivity.java` — removed `NavigationView`, all RecyclerView animation helpers (~400 lines), `onNavigationItemSelected`; added ComposeView + XServerDrawerState wiring

---

## AdrenoTools/Turnip Driver Fix (2026-04-20)

**Root cause:** The Compose migration moved `GPUInformation` JNI calls onto background coroutine threads (`Dispatchers.IO`). The AdrenoTools `hook_android_dlopen_ext` is not reentrant across threads — concurrent invocations from main + IO thread cause SIGSEGV.

**All `GPUInformation` native methods must run on the main thread:**
- `enumerateExtensions()` — moved to main thread via `LaunchedEffect(version)` (no `withContext`)
- `isDriverSupported()` / `getRenderer()` — moved to main thread in `LaunchedEffect(Unit)`, outside `withContext(Dispatchers.IO)`
- Pure file I/O (`enumarateInstalledDrivers()`, `gpu_cards.json`) stays on `Dispatchers.IO`

**File:** `ContainerDetailScreen.kt` → `GraphicsDriverConfigDialog` composable

---

## Controller Support — SDL2 SoName Symlink Fix (2026-05-06) — SHIPPED IN BETA 3 ✅

**Branch:** `fix/controller-support` → merged to `main` (PR #1, merge commit `7adfaf1`)
**Release:** [Winlator Star Bionic Compose Beta 3](https://github.com/The412Banner/star-compose/releases/tag/v7.1.4x-cmod-20260506-7adfaf1) — 2026-05-06
**CI:** Any-branch run `25456134263` ✅ green; Manual Release Build run `25456652846` ✅ green

### Root cause
Java→Compose splash-install migration introduced `SplashViewModel.installIfNeeded()` → `installFromAssetsWithCallback()` which is a copy-paste of the legacy `installFromAssets()` but lost the line that creates `<imagefs>/usr/lib/libSDL2-2.0.so.0` as a symlink to `libSDL2-2.0.so`. Wine xinput's `dlopen("libSDL2-2.0.so.0")` failed → fallback to winebus/HID stack → udev enumeration (broken on Android) → zero `/dev/input/event*` opens → libfakeinput hooks never fire → games saw no controller input.

Verification log: `star-controller-test-5.txt` (2026-05-06 15:09) shows `Adding controller fd 48 event event0..3`, `Hooking ioctl EVIOCGID/EV_KEY/EV_ABS/EV_FF`, `Assigned device 43 to slot 0`, `FakeInputWriter wrote slot=event0 bytes=48/48` then `72/72` — the full pipeline firing for the first time after the fix.

### Fix
Three commits cherry-picked clean from `controller-diag-combined` (which had diagnostic noise) onto `fix/controller-support`:
- `76c6628` — APK parity: `softRelease()` instead of `destroy()` on disconnect, releaseSlot keeps writers alive, pre-create event files, PlugPlay revert
- `81f1a9f` — pre-create all 4 event files at startup (Wine scans `/dev/input/` once at boot)
- `c48043f` — wire SDL2 SoName symlink into Compose splash install path; bump `LATEST_VERSION` 21→22 to force re-extract for existing users (Wine prefixes survive — `clearRootDir` preserves `home`)

Net diff: 5 files, +19/-5. Zero `InputDebug` references — all diagnostic code stripped.

### User verification
"yeah I could navigate in game menus and control the player fine" (gow.exe).

---

## Beta 3 Release Notes Pass (2026-05-06)

Renamed CI release to "**Winlator Star Bionic Compose Beta 3**" (matches Beta 2 naming). APK asset renamed via GH API to `Winlator-Star-Bionic-Compose-Beta3.apk`.

Release notes cover:
- Controller support root cause + 4 fixes
- Box64 component dropdown known limitation (cosmetic only — fix queued; later landed as Box64 fix below)
- Beta 2 → Beta 3 signing-key mismatch caveat (Android refuses install-on-top with sig mismatch; users must uninstall Beta 2 first, losing prefixes/containers)
- First-launch imagefs re-extract behavior (LATEST_VERSION 21→22) when signatures happen to match

---

## Box64 Dropdown — Stale-Display Bug Fix (2026-05-06) — ON BETA-4 ✅

**Branch:** `fix/box64-edit-dialog-stale-display` (commit `1330bb4`) → merged into `beta-4` (merge commit `fce8cf0`)
**CI:** Any-branch run `25459995549` ✅ green
**User verification:** "fixed and working correctly"

### Root cause
`ContainerDetailViewModel.loadContainerData()` calls `refreshWineDependent(selectedWineVersion)` which ends with:
```kotlin
selectedBox64Version = box64VersionEntries.firstOrNull() ?: ""  // resets to entry 0
```
Reset is correct on Wine-version change (Box64 entry list differs for arm64ec) but wrong on initial load — overrode the saved value before the dialog rendered. Save and runtime were always correct (DXVK HUD verified the chosen Box64 version was actually running); display-only bug.

### Fix
7 lines after `refreshWineDependent` call site at `ContainerDetailViewModel.kt:258`:
```kotlin
c?.box64Version
    ?.takeIf { it.isNotEmpty() && box64VersionEntries.contains(it) }
    ?.let { selectedBox64Version = it }
```
Mirrors the existing pattern used for FEXCore (`loadFEXCoreVersions()` then `selectedFEXCoreVersion = c?.getFEXCoreVersion()`).

---

## Branding/Version Rename (2026-05-06) — ON BETA-4 ✅

**Branch:** `feature/branding-rename-and-revamped-version` (commit `d7ee591`) → merged into `beta-4` (merge commit `6e89bd7`)
**CI:** run `25461593118` ✅ green
**User verification:** "all is changed correctly"

### Changes
4 user-facing strings updated in 4 files (+6/-6):

| File | Was | Now |
|---|---|---|
| `ui/screens/SplashScreen.kt` | "Bionic Star" + "V1.2" | **"Star Bionic"** + **"v1.2-REVAMPED"** |
| `MainActivity.kt` (about dialog) | "Bionic Star" + `Version ${BuildConfig.VERSION_NAME} (${VERSION_CODE})` | **"Star Bionic"** + **"v1.2-REVAMPED"** |
| `ui/AppDrawer.kt` (main app drawer header) | "Bionic Star" | **"Star Bionic"** |
| `ui/XServerDrawer.kt` (in-game drawer header) | "Bionic Star" | **"Star Bionic"** |

`build.gradle` `versionName` and `versionCode` intentionally **unchanged** so CI tag/APK naming convention continues working (`v7.1.4x-cmod-<date>-<sha>`).

---

## Compose Migration Report Sync + Part G (2026-05-06) — ON BETA-4 ✅

**Branches:**
- star-compose: `docs/migration-report-refresh` → merged into `beta-4` (`6c1329c`); then `docs/sync-with-nightlies-add-part-g` → merged into `beta-4` (`29b243e`)
- Nightlies: `docs/add-part-g-post-2026-04-22` → merged into `main` (`e688ef7`)

### What was wrong
The in-repo `COMPOSE_MIGRATION_REPORT.md` in star-compose was last updated 2026-04-17 at Part D. Past Claude sessions added Parts E (2026-04-20) and F (2026-04-22) directly to the canonical public copy in `The412Banner/Nightlies/main/COMPOSE_MIGRATION_REPORT.md` without syncing back to star-compose. The two had drifted by ~400 lines. My initial refresh attempt added a "Part E" section that name-collided with Nightlies' existing Part E and partly duplicated Part F4's content.

### What was done
1. Replaced star-compose's report with the Nightlies canonical content as the new base (catches up Parts E + F).
2. Dropped the wrongly-named "Part E" interim refresh.
3. Wrote a new **Part G** covering 2026-04-23 → 2026-05-06 work — applied identically to both repos:
   - G1. SDL2 controller fix (with portable lesson for fork developers)
   - G2. Box64 dropdown read/seed bug + reusable fix template
   - G3. UI branding pass
   - G4. Dead Java/XML cleanup status (5 safe to delete + 1 blocked on LogView fix + 4 contentdialog statics)
   - G5. New gotchas G26 (SoName symlinks during install migration) + G27 (ViewModel reset helpers need init-time override)
   - G6. Updated stats — 27 total documented gotchas
   - G7. Refreshed "Still Active — Needs Migration" roadmap

Both copies now identical at 2,476 lines. Memory entry saved (`feedback_compose_migration_doc_canonical.md`) so future sessions know Nightlies is the canonical home.

### Bonus: UI_MIGRATION_REPORT.md committed
The previously-untracked `UI_MIGRATION_REPORT.md` (originally generated 2026-04-22) was edited with current 2026-05-06 status markers and committed to `beta-4` for the first time.

---

## Beta-4 Integration Branch — Current State (2026-05-06)

After today's session, `beta-4` is at `6e89bd7` and contains 3 verified items + 2 doc passes:

| # | Merge | Verified | What |
|---|---|---|---|
| 1 | `fce8cf0` | ✅ | Box64 dropdown stale-display fix |
| 2 | `6c1329c` | n/a (docs) | Migration report refresh (initial Part E attempt — superseded) |
| 3 | `29b243e` | n/a (docs) | Sync with Nightlies canonical + Part G |
| 4 | `6e89bd7` | ✅ | Branding/version rename ("Star Bionic" + "v1.2-REVAMPED") |

Workflow established this session: features land on their own branch off `beta-4`, get tested and approved by user, then merge into `beta-4`. When user and developers happy, merge `beta-4` → `main` and trigger Manual Release Build for Beta 4. Memory entry: `feedback_star_compose_beta_branch_workflow.md`.
