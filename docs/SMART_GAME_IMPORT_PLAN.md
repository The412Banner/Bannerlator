# Smart Game Import — build plan & progress

**Branch:** `feat/smart-game-import` (off `main`). All work stays on this one branch until explicitly told to merge.
**Owner rule:** commit as The412Banner; never bump versionCode on this feature branch (stays vc48/2.8 for dev builds).
**This doc is living** — update it as phases/jobs complete or scope changes.

## Goal
When a user adds a game via the "+" importer, (1) identify the game from its on-disk footprint and auto-fill the correct name so cover-art scrapes hit, and (2) recommend the Windows dependency components (vcredist/dotnet/dx/physx/…) it needs, one-tap installable via the component system we already ship.

## Design spine
- **The Steam appId is the master key** — one id drives correct name, correct art (SGDB-by-appid), and the redist list. Everything degrades gracefully when no appId is found.
- **Reuse, don't rebuild.** The component catalog (142 comps), installer, and `ComponentsSheet` already exist — wired only into the container editor today. Net-new is *detection* + *wiring*.
- **Recommend, never auto-install.** User stays in control (one-tap). Installs land in the container/prefix (shared) — copy reflects that.

## Key existing code (anchors)
- Importer: `ui/screens/ExeShortcutImporter.kt` (name today = raw exe filename at `:37` → why art misses); SGDB + PE-icon fallback `:35-48`.
- PE parsing pattern: `core/PeIconExtractor.kt`.
- Component system: `components/ComponentCatalog.kt` (`components.json`, 142 comps), `ComponentInstaller.kt`, `ComponentExecInstaller.kt`, `ui/screens/ComponentsSheet.kt`. Catalog names = winetricks verbs (vcredist2015, xact, xna40, physx, oalinst…).
- Per-shortcut editor: `ShortcutSettingsDialogScreen` (`ShortcutsScreen.kt:3713`, has `wincomponents` override ~:3989).
- Steam stack: JavaSteam 1.8.0; `store/SteamRepository.java` already calls `picsGetProductInfo` (`:776/:833`), parses `depots` (`:948-1155`) but discards redist fields (skips no-manifest depots at `:1046` → shared redist depot 228980 dropped). GOG post-download redist scan precedent at `GogDownloadManager.java:1047-1098`.

---

## PILLAR 1 — Import identification & auto-naming

### Phase 1.1 — Folder-signal identifier (pure logic) — ✅ CODE DONE (2026-07-23)
New `core/GameIdentifier.kt` + `core/PeVersionInfo.kt`. Filesystem + PE only, no network, never throws.
Signal chain (appId preferred; best name across all hits):
1. `steam_appid.txt` (exe dir / `steam_settings/` / ancestors) → appId
2. `ColdClientLoader.ini` / `steam_emu.ini` / etc. `AppId=` → appId
3. `appmanifest_<id>.acf` (up under `steamapps/`, matched by installdir) → appId + name
4. `goggame-<id>.info` → name (+ gogId)
5. PE `VS_VERSIONINFO` ProductName/FileDescription → name
6. cleaned folder / exe name → name (LOW confidence fallback)
Output: `GameIdentity(appId, gogId, name, source, confidence)`.
Tests: `app/src/test/java/com/winlator/star/core/GameIdentifierTest.kt` (JVM, signal-chain coverage; PE path exercised on device).
- [x] `PeVersionInfo.kt`
- [x] `GameIdentifier.kt`
- [x] unit tests (spec)
- [ ] CI compile-verify of the two main classes
- Note: CI `assemble` compiles main classes but does NOT run the `test` source set; unit tests are runnable in Android Studio / a future test job.

### Phase 1.2 — Wire into the importer — 🟡 IN PROGRESS
- [x] **1.2a/b auto-name + cover art** (done): `ShortcutsViewModel.importExe` runs `GameIdentifier.identify(exe)`; the identified title becomes the shortcut name (fallback = exe filename) and is **written first**; then the cover-art thread searches **SGDB by appId → by that written name** (`ExeShortcutImporter.addToShortcuts` gained a `steamAppId` param; `StarLaunchBridge` gained `sgdbFetchCoverBySteamAppId` + a 6-arg `saveCoverArt`). File Manager add-path left name-based for now (out of scope).
- [ ] **1.2c editable confirm field** — show the detected name (editable) + source before saving, so a wrong auto-name can be corrected at add-time. Deferred until after the device gate confirms auto-naming hit-rate (the dialog's default value depends on it).

### Phase 1.3 — Device verify gate — ⬜ NOT STARTED (hard stop)
Stage APK, install, add: (a) Goldberg game w/ `steam_appid.txt`, (b) GOG game, (c) bare-exe game → confirm name + art resolve. Checkpoint memory + PROGRESS_LOG before Pillar 2.

---

> **PILLAR 1 (Smart Game Import) — ✅ COMPLETE & DEVICE-PROVEN 2026-07-23.** Auto-identify → Steam name+art by appId → "Confirm game" dialog w/ Search Steam picker. Passing: God of War, GTA IV, GTA V, Dark Souls. Phase B (full Edit Game screen) DEFERRED to the very end per user. **NEXT = Pillar 2 (component recommendation).**

## PILLAR 2 — Component recommendation — 🟡 BUILT 2026-07-23 (device test pending)
- **2.1 DependencyDetector** ✅ (`478d9dd7`) — scans redist folders → catalog names, Wine-aware curation, version-aware.
- **2.2 RecommendedComponentsSection** ✅ (`7e31804d`) — chips in Confirm dialog + shortcut settings; one-tap install reusing ComponentsSheet routing + prefix-exists gate; ComponentsSheet sorts installed-to-top.
- **Installed-state fix** ✅ (`7e31804d`) — exec/session installs weren't recorded (component_installs was empty). `ComponentExecInstaller.recordInstalled` at Result.Done + new `PrefixInstalledDetector` (OpenAL/mono/PhysX/GFWL prefix markers, verified vs fresh Proton-11). Union recorded∪detected in both UIs.
  - 🔑 Wine baseline: vcredist/dotnet/gecko/d3dx/xact ship builtin/baseline → NOT retro-detectable; only non-baseline footprints detectable. Prefixes at `imagefs/home/xuser-<id>/.wine`.
- Build p2 run 30036309321. Device gate: DiRT 3 chips + existing container shows OpenAL/mono/PhysX/GFWL checked.

<!-- superseded plan below kept for reference -->
### (original 2.x outline)
- **2.1 Detection** `components/DependencyDetector.kt`: scan `<gameDir>/_CommonRedist/**` → curation table → match to 142-catalog (`vcredist/`→vcredist201x, `PhysX/`→physx, `XNA/`→xna40, `dotNetFx45/`→dotnet45; **drop DirectX that DXVK covers**). Generalize the GOG redist scan.
- **2.2 UI indicators**: chips on the new shortcut + in `ShortcutSettingsDialogScreen`; each one-tap installs via existing `ComponentsSheet`. Reuse `component_installs` prefs.
- **2.3 Device verify gate**: chips appear, install one, verify it lands in prefix.

---

## PILLAR 3 — Steam appinfo enrichment — ⬜ OPTIONAL (gated on 1+2)
- **3a** coarse "bundles redistributables" flag — parse the `sharedinstall`/`depotfromapp`/228980 fields discarded at `SteamRepository.java:1046` (~free, no new CM message).
- **3b** exact redist list via `installscript.vdf` — small "fetch+decrypt+VDF-parse one file" helper reusing `getManifestRequestCode`/`getDepotKey`/CDN plumbing. Fills name + components even for lean Steam installs with no `_CommonRedist/`.

---

## Decisions / notes
- No SteamDB scraping (Cloudflare 403; ToS). Steam data comes from our own CM client / on-disk files.
- Curation table (redist → component id) authored once; small/static/editable.
- mono/gecko are prefix-level, NOT per-game → never in the per-game recommend list.

### Phase 1.3 — device gate (auto-name + auto-art) — 🟡 IN PROGRESS (test #1 ✅ PASS)
- Test 1 (God of War, FLT crack, `/storage/emulated/0/Winlator/Games/GodOfWar/GoW.exe`): shortcut named **"GoW"**, **no cover art**. Root-caused (all local/main-thread-safe):
  1. appid lived in **`flt.ini`** (`[GameSettings] AppId=1593500`) — emu-ini reader didn't check it → **FIX: read flt.ini + broad `*.ini` scan.**
  2. no appId→name resolution (network) — deferred; not needed for this game.
  3. `bestName` preferred ProductName ("GoW") over FileDescription ("God of War") — **FIX: prefer FileDescription when ProductName is a spaceless abbreviation.** (PE parser itself verified correct via python port.)
- Note: the app already shows a **"Rename Shortcut" (Skip/Save)** dialog on import, pre-filled with our name → 1.2c editable-confirm is effectively already shipped.
- After fixes, GoW should resolve name "God of War" (PE FileDescription) + art via SGDB-by-appid 1593500, no network.

### Device test findings (2026-07-23)
- ✅ **God of War** — name + art correct after flt.ini + bestName fixes.
- 🟡 **Dark Souls Remastered** — art correct; name was "DARK SOULS_ REMASTERED" (PE ProductName has a colon → shortcut writer sanitizes ':'→'_' since display name = .desktop filename). FIXED by name normalization (':'→' - ', strip ™).
- 🟡 **GTA IV** — art correct (appId 12210 from `Play - GTA IV.ini` ColdClientLoader); name "GSE" (user picked the launcher exe, PE ProductName = "GSE"). The ini even has `Exe=.\Game\...\GTAIV.exe` (real exe) — a signal we could follow.
- 🟡 **GTA V Enhanced** — user picked `PlayGTAV.exe` (Rockstar launcher) → name "Rockstar Games Launcher", art = Rockstar logo (wrong). No appId (not Steam-cracked). Real exe `GTA5_Enhanced.exe` sits alongside. Folder has BattlEye/D3D12-REDIST/Redistributables (→ Pillar 2 fodder).
- **Root pattern:** users pick LAUNCHER exes; launcher metadata ≠ game. Pure auto-detection can't win this.

## PILLAR 1.5 — Steam-powered naming + Search-Steam picker — 🟡 IN PROGRESS (user-directed 2026-07-23)
Decision: use Steam's own store API (port from user's BannersComponentInjector `SteamRepository`): `storesearch` (name→[{appId,name}]), Steam CDN `library_600x900.jpg` (cover by appId), `appdetails` (name by appId). Chosen UX = **both**: Phase A dialog now, Phase B full Edit screen later.
- **Phase A (building, android-app-engineer):** (1) Steam store helper; (2) auto path — appId → authoritative Steam name+art, beats launcher PE names, off-main-thread with rename-race guard; (3) upgrade the existing "Rename Shortcut" dialog into a "Confirm game" dialog w/ editable name + Search Steam button + tap-a-result list (cover+name+appId) that auto-fills name+art+appId.
- **Phase B (later):** full "Edit Game" screen from shortcut settings (name, Search Steam, cover, App-ID link/unlink, genres) — ported from injector `GameEditSheet`.
- Bonus: the picked appId also feeds Pillar 2 (component detection).

## Changelog
- 2026-07-23 — Branch cut off `main` (`ade2cb05`). Phase 1.1 code + tests. Phase 1.2a/b importer wiring. Phase 1.3 device test #1 (God of War) → 2 fixes (flt.ini reader, bestName heuristic) + regression tests. Rebuild held for compile-review agent.
