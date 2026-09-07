# Container Layer Update (in-place Proton/Wine swap) + "layer update available" badge — Plan

**Branch:** `feat/container-layer-update` (off `main` `c0dd2ee5`) · **Status:** recon only, no app code touched.

**Goal:** a container created on `Proton-11.0-6-arm64ec-4` can be moved to the installed `…-5` layer
in place (prefix, games, registry, saves kept), with a revert path; and the container list tells the
user when a newer build of *their* layer line is installed/offered.

**Real case on the device (2026-09-07, `com.tencent.ig`):** `contents/Proton/` holds
`11.0-6-arm64ec-4` AND `11.0-6-arm64ec-5`; container `xuser-5` ("P11-6 GE Arm") is on
`Proton-11.0-6-arm64ec-4`, `xuser-6` on `…-5`, `xuser-3` on `Proton-11.0-6-arm64ec-2`
(layer no longer installed). All line refs below are against this worktree.

> Note: the worktree carries an UNCOMMITTED edit to `core/WineInfo.java:126-136` (verCode suffix
> stripped at `lastIndexOf('-')` instead of a fixed 2-char cut). This plan assumes that fix lands —
> without it any layer with `verCode >= 10` resolves to the bundled fallback path.

---

## A. Container anatomy (findings)

### A1. Where the layer is recorded and how it becomes a path
- `.container` JSON field `wineVersion` — `Container.java:80` (default = bundled
  `proton-9.0-x86_64`), read `Container.java:1211-1213`, written `Container.java:1126`,
  loaded by `ContainerManager.java:207`. Value = the contents **entry name**
  `<Type>-<profile.versionName>-<profile.versionCode>` (`ContentsManager.java:417-419`), e.g.
  `Proton-11.0-6-arm64ec-5` (device: `grep wineVersion xuser-*/.container`).
- Resolution at launch: `XServerDisplayActivity.java:2187-2190` →
  `WineInfo.fromIdentifier(...)` (`WineInfo.java:118-155`): `contentsManager.getProfileByEntryName`
  (`ContentsManager.java:421-449`, requires the install dir to EXIST, line 443) → path =
  `ContentsManager.getInstallDir` = `files/contents/<Type>/<verName>-<verCode>/`
  (`ContentsManager.java:300-302`); otherwise the bundled `imagefs/opt/<identifier>` fallback
  (`WineInfo.java:124,144,154`). `imageFs.setWinePath(wineInfo.path)` (`XSDA:2190`,
  `ImageFs.java:77-79`) is what every later component reads (`GuestProgramLauncherComponent.java:252`).
- Prefix path: `ImageFs.WINEPREFIX = /home/xuser/.wine` (`ImageFs.java:19,33`); `xuser` is a
  symlink to `xuser-<id>` re-pointed per launch by `ContainerManager.activateContainer`
  (`ContainerManager.java:155-160`, called `XSDA:2151`). Guest starts as
  `wine explorer /desktop=shell,… <cmd>` (`XSDA:6438`); wineboot runs implicitly (it is in the
  stale-process sweep list `XSDA:737`).

### A2. How the prefix is built at creation — and the system32/syswow64 truth
`ContainerManager.createContainer` (`ContainerManager.java:186-242`) →
`extractContainerPatternFile` (`:427-452`):
1. Tries the bundled asset `<wineVersion>_container_pattern.tzst` (`:429-430`; no such asset ships —
   `app/src/main/assets` only has `container_pattern_common.tzst` + `layers.tzst`), so for a
   contents layer it falls through to **the layer's `prefixPack.txz`** (`:433-434`, profile field
   `wine.prefixPack`, `ContentProfile.java:18`, device profile.json: `"prefixPack": "prefixPack.txz"`).
   That pack supplies the registry hives + drive_c skeleton (device `.wine/`: `system.reg`,
   `user.reg`, `userdef.reg`, `drive_c`, `dosdevices`, `.update-timestamp`).
2. **`extractCommonDlls`** (`:406-425`) then **COPIES every regular file** from
   `<layer>/lib/wine/aarch64-windows/` → `.wine/drive_c/windows/system32/` (arm64ec; `x86_64-windows`
   otherwise) and `i386-windows/` → `syswow64/` via `FileUtils.copy` (`:423`), **skipping files that
   already exist** (`:418`). Not symlinks, not fake DLLs.
3. `EnableLUA` stamped into `system.reg` (`:220-225`), then `saveData()`.

**Device proof:** `xuser-6/.wine/drive_c/windows/system32/ntdll.dll` is `-rw-------` 1769472 B,
`find -type l | wc -l` = **0** symlinks vs 1136 regular files; its sha256
`5c7c30bd…` **equals** `contents/Proton/11.0-6-arm64ec-5/lib/wine/aarch64-windows/ntdll.dll`
(the v4 layer's ntdll is `100856bb…`). The copies carry Wine's `"Wine builtin DLL"` DOS-stub
signature (`head -c 96 | strings`), as do `libarm64ecfex.dll` / `wowbox64.dll`; DXVK's
`system32/d3d11.dll` does NOT (plain PE).

**Nothing refreshes those copies later.** `extractCommonDlls` has exactly one caller
(`ContainerManager.java:440-444`, creation only). The only later writers into system32/syswow64 are
selective: `restoreOriginalDllFiles` (`XSDA:9441-9460`, copies named DLLs from
`imageFs.getWinePath()` — the analog of what the refresh step must do for everything),
`extractDXWrapperFiles` (`XSDA:9181-9262`), `extractWinComponentFiles` (`XSDA:9403-9439`),
`extractEmulatorsDlls` (`GuestProgramLauncherComponent.java:110-156`). `applyGeneralPatches`
(`XSDA:10652-10660`) re-extracts only `container_pattern_common.tzst` + registry tweaks.
`ImageFsInstaller.java:26-38` writes a `wineprefixNeedsUpdate` extra that **nothing reads** (dead).

Wine's own upgrade hook IS armed in these prefixes: `.wine/.update-timestamp` = `1788759238` on
`xuser-6` == `stat %Y …/11.0-6-arm64ec-5/share/wine/wine.inf`; `xuser-5` holds `1788673858` == the
v4 layer's `wine.inf` mtime. On mismatch wineboot re-runs `wine.inf`, whose `[FakeDlls]`/`[FakeDllsWow64]`
sections contain the wildcard `11,,*` (wine.inf lines 852 and 918), and setupapi only overwrites
destinations that carry the "Wine builtin DLL"/"Wine placeholder DLL" stub — so after a layer swap the
FIRST launch would refresh builtin copies and keep native overrides (DXVK). This is a backstop, not the
plan: the app must do the refresh itself (deterministic, verifiable, no reliance on a guest boot).

### A3. Per-launch setup that re-runs every launch (survives a swap "for free")
| Step | Where | Keys off |
| --- | --- | --- |
| Layer path + WineInfo | `XSDA:2187-2190` | `wineVersion` (every launch) |
| `applyGeneralPatches` (pattern overlay, `WineUtils.applySystemTweaks` `WineUtils.java:56-78`) | `XSDA:6084-6097`, `:10652-10660` | gated on extras `appVersion`/`imgVersion`/`patternVersion` — NOT the layer |
| DX wrapper DLLs into system32/syswow64 | `XSDA:6099-6123`, `:9181-9262` | extra `dxwrapper` (cached string) — NOT the layer |
| WinComponents | `XSDA:6125-6130`, `:9403-9439` | extra `wincomponents` / `firstTimeBoot` |
| Start menu, dosdevices, wfm.exe | `XSDA:6143-6149` | every launch |
| Joystick/services registry | `XSDA:6172`, `WineUtils.changeServicesStatus :240-283` | every launch / extra `startupSelection` |
| Audio driver registry (`Audio=directaudio`) | `XSDA:10479-10497` | extra `audioDriver`; support gate `DirectAudioSupport.kt:19,30-33` matches tokens in the ENTRY NAME (`"11.0-6"` still matches after `-4`→`-5`) |
| DirectAudio driver overlay | `XSDA:3833-3874` (call `:6693`) | writes INTO THE LAYER DIR (`getInstallDir(profile)/lib/wine/…`), marker `.directaudio_bundled` per layer, build picked from the layer dir NAME (`:3815-3821`) → a new layer dir gets its own overlay on first launch |
| Refresh-rate unlock keys | `XSDA:10514-10532` | every launch; capability from `WineRandrSupport.java:32-62` (in-memory cache keyed by identifier+path → new identifier = fresh probe) |
| EA / Steam installScript registry parity | `XSDA:2532` → `InstallScriptExecutor.applyLocalStagesForLaunch` (`InstallScriptExecutor.kt:103-117`, `forceLocal = true`) | every launch, keyed by appId — not the layer |
| Epic fixes / overlay | `XSDA:6568-6571`, `:2536` | every launch |
| wowbox64 / FEXCore DLLs into system32 | `GuestProgramLauncherComponent.java:130-148` | extras `box64Version`/`fexcoreVersion` (cached) |
| FEX unixlib reconcile | `GuestProgramLauncherComponent.java:153`, `:181-218` | UNCONDITIONAL, writes the shared `imagefs/usr/lib/wine/aarch64-unix/` slot — layer-independent |
| winebus rumble patch | `GuestProgramLauncherComponent.java:249-259` | `imageFs.getWinePath()` → follows the new layer |
| `layers.tzst` / extra_libs | `XSDA:8613-8626` | `firstTimeBoot` (= extra `appVersion` empty, `XSDA:2185`) / version marker — not the layer |

### A4. Can the editor already change `wineVersion` on an existing container?
**No.** `ContainerDetailViewModel.kt:454` `wineVersionEnabled = !isEditMode`; the dropdown honours it
(`ContainerDetailScreen.kt:853-861`, `enabled = viewModel.wineVersionEnabled`). Edit-mode save
(`ContainerDetailViewModel.kt:917-992`) never calls `setWineVersion` — `wineVersion` is only written in
the create payload (`:1109`) and consumed by `createContainer` (`ContainerManager.java:207-209`).
`onWineVersionChanged` (`:758-773`) only re-seeds arch fields in create mode. No `wineboot -u` /
`--update` / prefix-upgrade code exists anywhere in `app/src/main` (grep `wineboot` hits only the sweep
list `XSDA:737` and two comments). The community-config path explicitly treats Proton as an advisory
(`ShortcutConfig.kt:30,147`, `ShortcutsScreen.kt:4638,4871`).

### A5. Registry files and reusable backup helpers
- Hives: `<container>/.wine/system.reg`, `user.reg`, `userdef.reg` (device listing; app refs
  `ContainerManager.java:221`, `WineUtils.java:58-59,82,243,295`, `XSDA:9407,10482,10521`). All app
  writes go through `WineRegistryEditor` (rewrite-on-close).
- Precedent for a hive backup next to itself: `EaSupport.cleanupFailedInstall` writes
  `system.reg.bak_ea_cleanup` before rewriting (`EaSupport.kt:288-305`).
- Whole-container copy preserving symlinks: `FileUtils.copyContainer` (`FileUtils.java:226-240`),
  used by duplicate (`ContainerManager.java:256`) and export (`:545-547`). Tarball:
  `TarCompressorUtils.compress(Type, File[], File, level, filter)` (`TarCompressorUtils.java:85-100`).
- Save-scoped snapshots exist (`CustomSaveVault.snapshot` `CustomSaveVault.kt:143`, `GameSaveBackup.kt`)
  but are game-save oriented — not a prefix snapshot.

### A6. What is STALE after a bare `wineVersion` swap (must be handled)
1. **system32/syswow64 builtin copies** (A2) — every Wine PE the old layer shipped stays. This is the
   bug that motivates the feature (new `ntdll.dll` never loads).
2. **Registry defaults from the old prefixPack** — wineboot's `.update-timestamp` mismatch re-runs
   `wine.inf` AddReg on first launch (user values preserved); app-side tweaks re-run only if the
   `patternVersion` gate trips (`XSDA:6089-6091`) → clear it.
3. **Cached "already applied" extras** that gate system32 writers: `dxwrapper` (`XSDA:6118`),
   `box64Version`/`fexcoreVersion` (`GPLC:130,140`). The refresh rule below (only overwrite
   builtin-signed files, and the layer ships no FEX/wowbox64 DLLs — device `ls aarch64-windows | grep
   -i fex|wowbox` = none) leaves DXVK/FEX/wowbox64 alone, so only `dxwrapper` needs clearing as cheap
   insurance (re-apply is idempotent).
4. **DirectAudio** — nothing in the prefix; the overlay marker lives in the layer dir (A3). `Audio=`
   key persists in `user.reg`. `DirectAudioSupport.isSupported` still true for the same line.
5. **Nothing else caches the layer path**: `.desktop` shortcuts store only the container id
   (`Shortcut.java:274-308`); community export reads `container.wineVersion` live
   (`ShortcutExporter.kt:48`); FusionHud/ShortcutsScreen resolve at launch/open
   (`ShortcutsScreen.kt:6265,6490`); `WineRandrSupport` cache is in-memory per identifier.
6. **Old-layer removal** (`ContentsManager.removeContent :386-415`) is independent — keep the old
   layer installed until the user confirms the update works (revert path).

---

## B. Catalog / notification (findings)

### B7. Installed vs remote identity — and the matching rule
- Installed layers = `files/contents/<Type>/<verName>-<verCode>/profile.json` scanned by
  `ContentsManager.syncContents` (`:126-154`); `profile.verName` = `profile.json.versionName`
  (`:263,284`). Device: `11.0-6-arm64ec-4/profile.json` → `versionName "11.0-6-arm64ec"`,
  `versionCode 4`; `…-5` → same name, `versionCode 5` (description differs: DA 1.3.1 vs 1.3.2).
- Remote catalog = `ContentsManager.REMOTE_PROFILES` (`:27`) parsed by `setRemoteProfiles`
  (`:103-124`, fields `type/verName/verCode/remoteUrl` only) and by `WcpJsonCatalog.parse`
  (`WcpJsonCatalog.kt:20-46`) for the hub (`RemoteSourceRepository.kt:290-297` official source).
- **Live catalog (fetched 2026-09-07):** `{"type":"Proton","verName":"GE-Proton-11.0-6-arm64ec (v5)",
  "verCode":5,…}`, likewise `Proton-11.0-1-arm64ec (v5)`, `Proton-10.0-4-arm64ec (v5)`,
  `GE-Proton-10.0-34-arm64ec (v5)`. The display label ≠ profile `versionName`.
- Consequences today: the merge dedupe `profile.verName.equals(remote.verName) && verCode==`
  (`ContentsManager.java:162`) never matches → the remote row is appended even when installed; the
  hub's installed badge compares `normalize(type)::normalize(verName)`
  (`ContentsHubViewModel.kt:326,343`) → `geproton1106arm64ecv5` ≠ `1106arm64ec`; the editor's
  in-use check (`ContentDownloadSheet.kt:691-696`) compares entry names → also misses. Prior art for
  fuzzy matching: `InstalledComponents.resolve/deriveToken` (`InstalledComponents.kt:45-87,117-123`,
  strips a trailing `-<verCode>` and known prefixes).

**Proposed rule (three tiers, most authoritative first):**
1. **Installed-only check needs no catalog at all**: "same `profile.verName` + `type`, higher
   `verCode` present under `contents/<Type>/`" — exactly the device state (v4 container, v5 installed).
   This is the cheap on-list-open check.
2. **Catalog entries gain a stable `versionName` field** (winlator-contents is ours; extra JSON keys are
   ignored by `setRemoteProfiles :111-114` and `WcpJsonCatalog :26-28`, so older app builds keep
   working). Key = `type + "::" + versionName.lowercase()` and compare `verCode`.
3. **Fallback for entries without the field** (community catalogs): derive
   `verName.replace(Regex("\\s*\\(v\\d+\\)$"),"").replace(Regex("^(ge-)?(proton|wine)-", IGNORE_CASE),"")
   .lowercase()` → `ge-proton-11.0-6-arm64ec (v5)` → `11.0-6-arm64ec` ✓ (all 8 live Proton rows
   reduce correctly). Never trust the heuristic for the *action*: after download the wcp's own
   `profile.json` is read before install (`ContentsInstaller.prescanFileProfile`
   `ContentsInstaller.kt:336-349`, `TarCompressorUtils.readTextFile :258`), and the update itself
   runs only against an INSTALLED profile (tier 1). A mismatch after download = "downloaded a different
   line" → show, don't auto-update.

### B8. Where the UI and the check live
- Container list: `ContainersScreen.kt:110-136` (screen, `vm.refresh()` on every ON_RESUME `:130-136`),
  `LazyColumn` `:202-235`, card `ContainerItem` `:514-540` (subtitle `wineVersion · screenSize`
  `:538-539` — the badge goes right there), overflow menu `:633-660` (add "Update layer…").
  `ContainersViewModel.kt:26-35` rebuilds a `ContainerManager` per refresh — add the update map there
  (Dispatchers.IO, one `ContentsManager.syncContents()` + a per-container lookup).
- Cheap app-start / list-open check: tier 1 is a directory scan (no network). The catalog fetch to
  add tier 2/3 already happens in three places — `ContentDownloadSheet.kt:152-159`,
  `ShortcutsScreen.kt:2722-2727`, and the hub via `RemoteSourceRepository.fetchFromSource`
  (`RemoteSourceRepository.kt:544`, memory cache `:25-28`, "new items" fingerprints `:1407-1454`
  currently unused elsewhere). Reuse `RemoteSourceRepository` + its cache, with a 24 h
  SharedPreferences stamp so the list never blocks on the network.
- Badge visual: reuse the drawer's `NewBadge` idiom (`AppDrawer.kt:429-445`) as `UpdateBadge("LAYER
  v5")` next to the subtitle; `SpecChipRows` (`ContainersScreen.kt:593-600`) is shared with the game
  cards — do not put the badge in there.

---

## C. Plan

### C1. Update action — `ContainerLayerUpdater` (new, `container/` package, Java or Kotlin to taste)
Runs off-main (`Executors.newSingleThreadExecutor()` like `ContainerManager.createContainerAsync :162`),
progress via `PreloaderState.show/hide` (`ContainerDetailViewModel.kt:854-857`). Refuse if a session
is running for that container (same rule as the editor's save).

1. **Resolve** `old = getProfileByEntryName(container.wineVersion)`, `new = getProfileByEntryName(target)`
   (`ContentsManager.java:421-449`). Both must exist on disk (`:443`); guardrails in C2.
2. **Snapshot** → `<container>/.layer-update/<oldEntry>-<epoch>/`: copy `.container` + `.wine/system.reg`,
   `user.reg`, `userdef.reg`, `.update-timestamp` (plain `FileUtils.copy :153`); optional full-prefix
   tar via `TarCompressorUtils.compress` (`:85`) with an `ExclusionFilter` for `drive_c/users`,
   `Program Files*`, `ProgramData` (game data is untouched by the swap and would make the tar huge).
   Write a `manifest.json` (old/new entry names, file list).
3. **Set** `container.setWineVersion(newEntry)` + `saveData()` (`Container.java:971-973,1071`).
4. **Refresh system32/syswow64 from the new layer** — a new `ContainerManager.refreshCommonDlls`
   (sibling of `extractCommonDlls :406-425`) that walks `aarch64-windows` (or `x86_64-windows`) →
   `system32` and `i386-windows` → `syswow64` with overwrite semantics: copy when the destination is
   absent OR its DOS stub carries `"Wine builtin DLL"` / `"Wine placeholder DLL"` at offset 0x40
   (setupapi's own `is_fake_dll` rule; device-proven signatures in A2). Keep the existing
   `iexplore.exe`/`tabtip.exe`/`icu.dll` special cases (`:413-416`). Delete destination files that are
   builtin-signed but have NO counterpart in the new layer (removed DLLs) — log the list. Never touch
   unsigned (native) files: DXVK, VKD3D, d7vk, wincomponent natives, `wowbox64.dll`/FEX (builtin-signed
   but absent from the layer → untouched).
5. **Invalidate caches** on the container: `putExtra("patternVersion", null)` (forces
   `applyGeneralPatches` `XSDA:6089-6092`), `putExtra("dxwrapper", null)` (re-applies wrapper DLLs
   `XSDA:6118-6123`), `putExtra("desktopTheme", null)`; keep `appVersion` (do NOT trip `firstTimeBoot`
   → no `layers.tzst` re-extract `XSDA:8613`). `saveData()`.
6. **Prefix update** — delete `.wine/.update-timestamp` so wineboot cannot skip the update on the next
   boot (belt and braces: the mtimes already differ, A2). No app-side `wineboot -u` exists; the first
   launch performs it (A1/A2). Optional explicit verify launch: start the container with
   `EXTRA_EXEC_ARGS`-style override (`XSDA:9505-9509`) running `wineboot.exe -u` then exit — defer to
   phase 2, the plain first launch is the same thing.
7. **Verify** (post-first-launch, from the container card or automatically on next list open): (a)
   `sha256(system32/ntdll.dll) == sha256(<newLayer>/lib/wine/aarch64-windows/ntdll.dll)`; (b)
   `.wine/.update-timestamp == mtime(<newLayer>/share/wine/wine.inf)`; (c) session reached a window
   (existing `winStarted`, `XSDA:2410`). Record `layerUpdate.verified=1` extra.
8. **Revert** (menu action while the snapshot exists): stop any session, restore the 4 files, put
   `wineVersion` back, re-run step 4 against the OLD layer (still installed — the updater never removes
   layers), clear the same extras, delete `.update-timestamp`. Snapshot is pruned only on explicit
   "Keep this update" or after N successful launches.

### C2. Guardrails
- **Automatic offer only for the same line**: same `type` + same `profile.verName` + `new.verCode >
  old.verCode` (tier 1 in B7). `WineInfo.fromIdentifier` must resolve both to `isArm64EC()` equal
  (`WineInfo.java:71`) — arch mismatch is a hard refuse (system32 would get the wrong PE arch).
- **Cross-line** (e.g. `11.0-2-arm64ec` → `11.0-6-arm64ec`, or GE ↔ plain): allowed only from the
  editor behind a warning dialog ("registry defaults, DirectAudio support and game fixes differ;
  snapshot is taken"), same arch enforced; DirectAudio coerced via the existing
  `coerceAudioDriverForWine` (`ContainerDetailViewModel.kt:887`) when the target is off the token list.
- **Never downgrade** automatically; a lower `verCode` target is a revert, not an update.
- **Target not installed** → button reads "Download v5 & update", drives the existing
  `ContentDownloadSheet` (`ContainerDetailScreen.kt:404-414` shape, `installContent`
  `ContentDownloadSheet.kt:720-776`) or `ContentsInstaller.install` (`ContentsInstaller.kt:49-145`),
  then re-checks tier 1 before running C1.
- **Bundled main version** (`WineInfo.isMainWineVersion :157`) and legacy `imagefs/opt` layers: skipped.
- **Missing old layer** (device `xuser-3` on `…-2`, dir gone): offer the update anyway (it is the only
  way to make that container bootable again) but mark the snapshot "no revert layer".

### C3. Notification
- Check on container list open (`ContainersViewModel.refresh :32-35` → IO): tier 1 scan; tier 2/3 with
  the cached catalog when the 24 h stamp is stale. Result = `Map<containerId, LayerUpdate(target,
  installed: Boolean)>` exposed as a `StateFlow`.
- Card: badge `LAYER v5` (installed) / `LAYER v5 ↓` (needs download) beside the subtitle
  (`ContainersScreen.kt:584-592`); tap = one-tap update dialog (what changes, snapshot note, buttons
  **Update**, **Later**, **Don't ask for this container**). Menu item "Update layer…" `:638-660`.
- "Later" = session-scoped dismissal (VM state); "Don't ask" = extra `layerUpdate.ignore=<newEntry>`
  (re-asks when an even newer verCode appears). Mirror the existing one-shot `_message` toast pattern
  (`ContainersViewModel.kt:22-24`) for results.
- Optional: drawer dot on "Containers" via the `showNew` slot (`AppDrawer.kt:364,421-424`).

### C4. Files to touch
| File | Change |
| --- | --- |
| `container/ContainerManager.java` | `refreshCommonDlls(...)` next to `extractCommonDlls :406-425`; `isWineBuiltinPe(File)` helper; snapshot/restore helpers or a new `container/ContainerLayerUpdater` |
| `contents/ContentsManager.java` | `findNewerInstalled(entryName)` (tier 1) + optional `versionName` field in `setRemoteProfiles :111-114`; fix dedupe `:162` to use it |
| `ui/screens/contents/WcpJsonCatalog.kt`, `RemoteSourceRepository.kt` | carry `versionName` through `RemoteItem :231`; derive-token fallback |
| `ui/screens/ContainersViewModel.kt` | update map + actions (`update`, `revert`, `ignore`) |
| `ui/screens/ContainersScreen.kt` | badge in `ContainerItem :576-592`, menu items `:633-660`, dialogs (reuse `OutlinedAlertDialog`) |
| `ui/screens/ContainerDetailViewModel.kt` | cross-line path: allow the dropdown in edit mode behind the warning (`:454`), route save through the updater instead of `setWineVersion` |
| `ui/screens/ContainerDetailScreen.kt` | warning dialog next to the dropdown `:853-866` |
| `winlator-contents/contents.json` (separate repo) | add `versionName` per Proton/Wine row |
| `res/values/strings.xml` | badge/dialog strings |

### C5. Effort, risks, device test
**Effort:** ~2 days — updater + refresh rule (0.5 d), list badge + dialogs + VM (0.5 d), catalog field
+ matching + dedupe fix (0.5 d), device proof + revert proof (0.5 d). No Room/DB change (containers are
file-backed), no versionCode bump beyond the normal CI tick.

**Risks:**
- Overwrite rule misclassifies a user-dropped DLL that happens to be winebuild-signed (e.g. a custom
  FEX in system32) — mitigated by "only when the new layer ships that name" + logging + snapshot.
- Wine's prefix update on first boot can take longer and rewrites `system.reg` — the snapshot is taken
  before, and every app registry write re-runs each launch (A3).
- A layer whose `prefixPack` changed structurally (new drive_c skeleton files) is NOT re-extracted — by
  design (would clobber user data); wine.inf handles the registry side. Document it.
- Cross-arch/cross-line misuse — hard-gated (C2).
- `WineInfo.fromIdentifier` 2-char cut (pre-existing, uncommitted fix present in this worktree) — must
  land first or `verCode >= 10` layers break.

**Device test plan (pubg flavor, stage per the staging rule, never `pm install`):**
1. Baseline: `xuser-5` on `…-4`; record `sha256 system32/ntdll.dll`, `.update-timestamp`, the three
   hives' sizes, and one EA/NFS launch to a window.
2. Open Containers → badge `LAYER v5` on "P11-6 GE Arm" only (not on `xuser-6`, not on the Wine 9.5
   x86_64 container; `xuser-3` shows it with the "no revert layer" note).
3. Update → check `.container` `wineVersion` = `Proton-11.0-6-arm64ec-5`, snapshot dir present,
   `system32/ntdll.dll` sha == v5 layer's, `system32/d3d11.dll` unchanged (DXVK), `libarm64ecfex.dll`
   and `wowbox64.dll` unchanged, `.update-timestamp` gone.
4. Launch the EA title from that container: reaches a window; afterwards `.update-timestamp` ==
   v5 `wine.inf` mtime; `wine_debug.log` fingerprints the P11 layer (log-provenance rule); DirectAudio
   still `Audio=directaudio` and the v5 layer dir has `.directaudio_bundled`.
5. Revert → hives byte-identical to the snapshot, `wineVersion` back to `…-4`, ntdll sha == v4, launch OK.
6. Negative: remove the v5 dir → badge flips to `↓` and the action goes through the download sheet.
