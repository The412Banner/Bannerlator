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
