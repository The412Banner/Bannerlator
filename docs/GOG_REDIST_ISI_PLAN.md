# GOG Redistributables + Installer-Script-Interpreter (gap #3) — Plan & Task List

**Branch:** `feat/gog-redist-isi` (off `main` `9e664905`)

## ✅ DECISIONS LOCKED (user, 2026-08-22)
- **Trigger = install-on/after-add-to-container.** Redists install into the CONTAINER's Wine
  prefix (runtimes register system-wide, not per-game), so there is NO valid target until a
  container is chosen — installing at download time is impossible. Flow: prompt once right
  after "Add to container" (*"This game needs X — install into <container> now?"*) + a manual
  "Install prerequisites" action on the GOG detail page for re-runs. No container yet → show
  "add to a container first", install nothing.
- **Main path = the Windows-components installer** (`ComponentExecInstaller`) — it RUNS the real
  installer so Windows registers the runtime (registry / side-by-side assembly cache). Plain
  file-extraction does NOT register a runtime, so it can't be the main path.
- **Fallback = file-manager extraction** for stubborn installers. If a redist `.exe` won't run
  cleanly under Wine, reuse the in-app File Manager's extract methods to crack the wrapper open,
  pull out the inner installer (`.msi`/inner `.exe`), and run THAT via the components installer.
  Extraction is the unpack tool; running still does the registering. Wire it as a fallback, not
  the primary.
**Goal:** Make GOG games that need Microsoft VC++ runtimes (and other GOG "dependencies"
redistributables) actually LAUNCH, by auto-detecting a game's required redists from its
build manifest, downloading them from GOG's dependencies repository, and silently
installing them into the game's Wine container prefix after install. Scope the GOG
"scriptInterpreter" (ISI) step and the per-game launch-arg fixes, ship the redist slice
first.
**Source of gap:** memory `reference_gog_gamenative_gap_roadmap` item **#3** ("gamefixes +
auto-redistributables (VC++) + installer-script-interpreter"). GN implements this via
`GOGDependencyFix` (auto-installs `_CommonRedist`/repository MSVC), a
`GogScriptInterpreterStep`/`Dependency` gated on `GOGManifestUtils.needsScriptInterpreter`,
and ~12 `GOG_<id>.kt` per-game fixes. We have NONE of this today.

**Clean-room:** GN's GOG code is **GPL-3.0**. Everything below is reconstructed from the
**live GOG dependencies protocol** (probed read-only, real shapes captured in this doc) and
from **our own existing component-install machinery**. GN symbols are cited as
**behavioural anchors by name only** (`GOGDependencyFix`, `GogScriptInterpreterStep`,
`GOGManifestUtils.needsScriptInterpreter`, `GOG_<id>.kt`/`KeyedLaunchArgFix`) — no GN Kotlin
is read or lifted. Do not open GN source to implement this.

---

## In plain English (what this does for the user)
- **Auto VC++ / runtimes** — a GOG game that needs the Visual C++ 2017 runtime (etc.) gets
  it installed into its container automatically after download, instead of the game
  failing to start with a missing-DLL / `VCRUNTIME140.dll` error the user has to solve by
  hand.
- **Scriptinterpreter (P2)** — some GOG titles ship a GOG "install script" that must run
  once to finish setup (registry, shortcuts, config); scoped here, deferred past P1.
- **Per-game fixes (P2)** — a small catalog of "this game needs `-lang=eng`" style launch
  tweaks so a handful of specific titles start correctly.

---

## What we already have (REUSE — do not rebuild)

### The redist DOWNLOAD pipeline is already 95% built
`GogDownloadManager.java` (gen2) already does **exactly** the chunk format the dependencies
repository uses. The dependency depot manifest is byte-for-byte the same shape as a game
depot manifest, and the chunk store is the same CDN host (`gog-cdn-fastly.gog.com`), just
under `/dependencies/…` paths and **without a `secure_link`** (the dependency store is
unauthenticated — verified).
- `decompressBytes()` / `fetchBytes()` — zlib-inflate + fetch (reuse verbatim).
- `parseDepotManifest()` + the gen2 chunk loop — download chunk → verify `compressedMd5`
  → inflate → verify decompressed `md5`+`size` → assemble (the #1 MD5 work already landed;
  reuse it for redist integrity for free).
- `buildCdnPath(hash)` — `<h[0:2]>/<h[2:4]>/<h>` fan-out (identical for dependency meta).
- **The game's required dependency list is ALREADY in hand.** In `runGen2` the build
  manifest is parsed into a `JSONObject manifest` at `GogDownloadManager.java:263`, right
  where `depots` is read (`:267`). The two fields we need sit at the SAME level and are
  simply never read today:
  - `manifest.optJSONArray("dependencies")` → the redist ids, e.g. `["MSVC2017_x64"]`.
  - `manifest.optBoolean("scriptInterpreter")` → the ISI signal.

### The redist INSTALL machinery already exists (this is the big win)
`components/ComponentExecInstaller.kt` already installs **vcredist / .NET runtimes** into a
container prefix. It is the exact pattern we need, and it already solves the hard parts:
- `startInstall(context, container, Component, onProgress)` (`ComponentExecInstaller.kt:126`)
  — requires the prefix to exist (`File(container.rootDir, ".wine").isDirectory`, `:127`).
- Stages the installer into `drive_c/windows/temp/bannerlator_components`, writes a
  transient `.desktop` with `Exec=wine <installer>` + `execArgs=` + `envVars=`, and launches
  `XServerDisplayActivity` with a `component_installer_exe=<name>` extra.
- That extra makes `XServerDisplayActivity` watch the guest process list and **auto-close
  the session when the installer process exits** (`startInstallerWatch()` /
  `looksLikeInstallerProc()` — match heuristics already include `vcredist`, `redist`,
  `msiexec`, `dotnet`, `ndp`, `setup`, `install`).
- Because ending a session restarts the app, it persists an ordered **install plan** to
  `SharedPreferences` (`PREF_PLAN`) and resumes it via `resume(context, onProgress)` across
  the restart — so a multi-redist sequence (e.g. Witcher 2 EE's 4 deps) is driven to
  completion automatically.
- Installed-state store: `component_installs` SharedPreferences, key `c<containerId>` (a set
  of component names) — reuse for **idempotency** (skip an already-installed redist).
- Model: `components/ComponentCatalog.kt` — `Component(name, description, provider, status,
  dependencies, steps)` with `ComponentStep(action, obj)`; installer steps use actions
  `install_exe` / `install_msi`, and the step's `environment` JSON carries
  `file_name`/`url`/`arguments` plus env vars.
- Simplest generic exec, if we ever want it: `util/ContainerExeRunner.run(context,
  container, exeFile)` runs any `.exe` in a chosen prefix (no auto-close extra).

**Consequence:** P1 is mostly *plumbing two things we already have together* — GOG's
dependency list/blobs on one side, our exec-installer on the other — not writing a new Wine
launcher.

### Container / prefix model (for targeting the right prefix)
- Container prefixes live at `<imagefs>/home/xuser-<id>/.wine` (`ContainerManager.java`
  `homeDir` + `setRootDir`); `drive_c` at `<rootDir>/.wine/drive_c`;
  `system32` at `…/drive_c/windows/system32`.
- `core/WinePath.kt` — `resolveWindowsPath(container, androidPath)` for the `Exec=` path,
  `escapeForExec` for the 4-backslash `.desktop` form.
- ⚠️ **Prefix-targeting caveat:** at launch `XServerDisplayActivity` sets
  `WINEPREFIX = imageFs.wineprefix` (the DEFAULT prefix); the per-container binding happens
  elsewhere in the activity's launch setup. `ComponentExecInstaller` already installs into a
  *chosen* container correctly — we inherit whatever it does, but the device test must
  confirm the redist lands in the SAME container the game's shortcut launches in.

---

## Verified GOG protocol (real shapes captured 2026-08-22, unauthenticated)

### 1. Dependencies repository index
`GET https://content-system.gog.com/dependencies/repository?generation=2` → HTTP 200:
```json
{ "repository_manifest": "https://gog-cdn-fastly.gog.com/content-system/v2/dependencies/meta/e4/be/e4be9c2bae009cf9473447fb8e479fee",
  "build_id": "59705672826648994", "generation": 2 }
```

### 2. Repository manifest (the redist catalog) — zlib-deflate at the URL above
Inflates to `{ "depots": [ … ] }` — **68 entries** at the current build. Each entry:
```json
{ "dependencyId": "MSVC2017_x64",
  "readableName": "Visual C++ Redist 2017 (x64)",
  "executable": { "path": "__redist/MSVC2017_x64/VC_redist.x64.exe",
                  "arguments": "/install /quiet /norestart" },
  "internal": false, "languages": ["*"], "osBitness": ["64"],
  "manifest": "e710f0abc7bd095ac6ec89fba805b591",
  "size": 25635768, "compressedSize": 25385405, "signature": "…" }
```
Key facts:
- The **silent-install flags ship in the manifest** (`executable.arguments`) — read them,
  don't hardcode. MSVC2015–2026 use `/install /quiet /norestart`; older MSVC use
  `vcredist_x*.exe` with their own quiet flags; DirectX = `DXSETUP.exe`; XNA/PhysXLegacy =
  `.msi` (→ `install_msi`, i.e. `msiexec /i … /qn`).
- `internal:true` marks GOG-internal deps we must NOT treat as user redists —
  **`ISI`** (scriptinterpreter), `SuspendLauncher`, `DOSBOXConfigurator`, `language_setup`.
  Skip `internal:true` in the redist path (ISI is handled by the P2 scriptInterpreter path).
- Full id set present (for the catalog map): `MSVC2005..2026` (+`_x64`), `DirectX`,
  `dotNet35/35C/4/4C/45/46/47/472`, `dotNetCore318(_x64)`,
  `dotNetDesktopRuntime60/70/90(_x64)`, `openAL`, `XNA/XNA_40`, `PHYSX*`, `UE4REDIST`,
  `AdobeAir`, `Foxit`, `QuickTime`, `nGlide*`, `DOSBox*`, `ScummVM`, `ISI`,
  `SuspendLauncher`, `DOSBOXConfigurator`, `language_setup`.

### 3. Per-dependency depot manifest — zlib-deflate
`https://gog-cdn-fastly.gog.com/content-system/v2/dependencies/meta/<h[0:2]>/<h[2:4]>/<h>`
(h = the entry's `manifest` hash) → same shape as a game depot manifest:
```json
{ "depot": { "items": [
    { "path": "__redist/MSVC2017_x64/VC_redist.x64.exe",
      "md5": "…", "sha256": "…", "type": "DepotFile",
      "chunks": [ { "compressedMd5": "84661973…", "compressedSize": 10234036,
                    "md5": "17ce6904…", "size": 10485760 }, … ] } ] },
  "version": 1 }
```

### 4. Chunk blobs — unauthenticated, range-capable
`https://gog-cdn-fastly.gog.com/content-system/v2/dependencies/store/<cMd5[0:2]>/<cMd5[2:4]>/<cMd5>`
(cMd5 = chunk `compressedMd5`). **Proven end-to-end:** fetched the MSVC2017_x64 first
chunk, zlib-inflated to exactly `size` (10485760) bytes, inflated MD5 == manifest `md5`,
first bytes = `MZ` (a real PE). So our existing gen2 download+inflate+verify path
reconstructs `VC_redist.x64.exe` verbatim with **no new download engine and no secure_link**.

### 5. The game side — how a game declares its deps
Top-level fields in the gen2 **build manifest** (which we already fetch+parse):
- ELDERBORN (product `1732383191`): `"dependencies": ["MSVC2017_x64"]`,
  `"scriptInterpreter": true`.
- The Witcher 2 EE (product `1207658930`):
  `"dependencies": ["MSVC2010","MSVC2010_x64","DirectX","dotNet4"]`,
  `"scriptInterpreter": true`.
- **No `_CommonRedist` in ELDERBORN's gen2 depots** (0 of 379 items) — modern galaxy-2
  titles ship NO bundled redists; the `dependencies` array + the repository is the
  authoritative and only source. (Legacy/gen1 `_CommonRedist` bundling is out of scope; if a
  game ever ships one we can extract-and-run it, but P1 targets the repository.)

---

## P1 scope — ship the redist auto-install

**Detect → download → silent-install a game's required GOG redists into its container prefix,
idempotently, driven by the existing exec-installer plan.**

IN:
1. **Capture the dep list at download time.** In `GogDownloadManager.runGen2`, read
   `manifest.optJSONArray("dependencies")` (next to `depots` at `:267`) and
   `manifest.optBoolean("scriptInterpreter")`. Persist per game — simplest is a
   `SharedPreferences` key `gog_deps_<gameId>` (JSON: `{deps:[…], scriptInterpreter:bool}`),
   OR stamp it into the shortcut `[Extra Data]` via `StarLaunchBridge` (a `gogDeps=` /
   `gogScriptInterpreter=1` line) so it's attached to the launch target.
2. **New `store/GogDependencyRepository.java`** — the clean-room protocol client:
   - `fetchRepository()` → GET the repository index, inflate the repository manifest, return
     a `Map<dependencyId, DepEntry>` (readableName, exePath, arguments, internal, osBitness,
     manifest-hash, size). Cache in-process; it's ~40 KB.
   - `downloadRedist(depId, destDir, cb)` → resolve the entry's depot manifest, then reuse
     `GogDownloadManager`'s chunk download+inflate+MD5-verify to assemble the installer file
     (`__redist/<id>/<exe>`) into `destDir`. Refactor the gen2 chunk loop into a small
     reusable helper if needed so both game files and redists share it (keeps the MD5
     integrity guarantees in one place).
3. **New `store/GogRedistInstaller.kt`** — the orchestrator that bridges to the exec-installer:
   - Given a container + the game's dep list: filter to `internal:false`, filter by
     `osBitness` (we run a 64-bit prefix — install `_x64` where present; keep x86 for the
     32-bit-only runtimes), and drop any already recorded in `component_installs`/`c<id>`.
   - For each remaining redist: pre-stage the assembled installer into the container's
     `drive_c/windows/temp/…` and build a `Component` with a single `install_exe`/`install_msi`
     step whose `arguments` = the manifest's `executable.arguments`.
   - **Minimal reuse extension:** teach `ComponentExecInstaller` to accept a *pre-staged local
     installer* (skip its `Downloader` fetch when the step already points at a local file) —
     one small branch. Then hand the ordered `Component` list to the existing plan engine
     (`startInstall` → plan persist → `component_installer_exe` auto-close → `resume` across
     restart). This keeps ONE install-plan/restart engine for both system components and GOG
     redists.
   - Record each completed redist as `GOG:<depId>` in `component_installs`/`c<containerId>`
     for idempotency.
4. **Trigger point + UX.** Offer redist install right after "Add to container" (we know the
   container then) — a dialog: *"ELDERBORN needs Visual C++ 2017 (x64). Install it into
   <container> now?"* → runs the plan. Also expose a manual **"Install prerequisites"** action
   on the GOG detail page for re-runs. Do NOT silently hijack; the exec-installer opens a
   visible Wine session (see risks), so the user should know a session is about to launch.
5. **Self-evidencing.** Log to the GOG debug buffer: deps parsed, repository build_id, each
   redist resolved (id → exe → args → size), staged path, plan launched, and idempotency
   skips — the device test reads this.

OUT of P1 (see deferred):
- The scriptInterpreter/ISI execution, the per-game fix catalog, DOSBox `.conf` /
  ScummVM / nGlide handling, and any legacy `_CommonRedist` extraction.

---

## Deferred — P2 / P3

### P2a — Installer Script Interpreter (ISI / scriptInterpreter)
- **Signal (verified):** build-manifest top-level `scriptInterpreter: true`. This is GN's
  `GOGManifestUtils.needsScriptInterpreter` equivalent — captured in P1 already.
- **What it is:** GOG ships an internal `ISI` dependency (`internal:true`,
  `__redist/ISI/scriptinterpreter.exe`) that, post-install, runs the game's GOG install
  script (the `goggame-*.script` / support files) to finish setup (registry keys, Start-menu
  entries, config). GN runs it as a `GogScriptInterpreterStep`.
- **Why deferred:** it needs `scriptinterpreter.exe` run against the installed game dir with
  the right arguments, and the script's effects (registry writes) must land in the game's
  prefix; the exact CLI contract and how much of the script Wine honours is unproven, and
  many titles launch fine without it. Scope in P2 as its own device-proven slice: download
  the `ISI` dep (same pipeline), run it via the exec-installer against the install dir, verify
  on a `scriptInterpreter:true` title that genuinely needs it.

### P2b — Per-game launch-arg fix catalog (GN `GOG_<id>.kt`)
- GN keeps ~12 tiny per-game fixes — mostly `KeyedLaunchArgFix`-style launch-arg tweaks
  (`-lang=eng`, DOSBox `-conf <file>`, working-dir overrides). Clean-room equivalent = a
  **data-driven** map (a small JSON on `winlator-contents`, keyed by GOG product id →
  `{execArgs, workingDir, …}`) applied when writing the shortcut in `StarLaunchBridge`. Cheap,
  additive, and low-risk — a candidate to fold into P1's tail if time allows, but not
  required for the redist win. Do NOT transcribe GN's list; re-derive per-game args from the
  game's own `goggame-*.info` / start-menu link or from public GOG data.

### P3 — DOSBox / ScummVM / nGlide / glide launchers, legacy `_CommonRedist` extraction,
mid-plan exit-code verification (currently completion is inferred from session end, not an
exit code).

---

## Files we'd touch (P1)
- `app/src/main/java/com/winlator/star/store/GogDownloadManager.java` — read + persist
  `dependencies` + `scriptInterpreter` in `runGen2`; refactor the gen2 chunk
  download/inflate/verify into a reusable helper the redist client can call.
- `app/src/main/java/com/winlator/star/store/GogDependencyRepository.java` — **new**;
  repository index + manifest client, `depId → installer file` assembler.
- `app/src/main/java/com/winlator/star/store/GogRedistInstaller.kt` — **new**; dep filtering,
  staging, `Component` construction, drives `ComponentExecInstaller`, idempotency.
- `app/src/main/java/com/winlator/star/components/ComponentExecInstaller.kt` — small
  extension: accept a pre-staged local installer (skip re-download) so GOG redists reuse the
  one plan/restart engine.
- `app/src/main/java/com/winlator/star/store/GogGameDetailActivity.kt` (+ the add-to-container
  flow / `StarLaunchBridge`) — the "Install prerequisites" action + the post-add prompt; and
  (optionally) stamp `gogDeps=`/`gogScriptInterpreter=` into the shortcut Extra Data.
- (No new download engine, no new Wine launcher, no `secure_link` work.)

---

## Keystone risks
1. **Silent install still shows a Wine desktop.** `ComponentExecInstaller` strips `/qn /S`
   by design (a truly silent install draws nothing, looking hung). Redist manifests give us
   `/install /quiet /norestart`; if we KEEP those flags the session shows a black desktop then
   auto-closes — acceptable but must be messaged ("a setup window will appear briefly"). Decide
   per-redist whether to keep silent flags or show the wizard. Device-verify the auto-close
   heuristic actually fires for `VC_redist.x64.exe` (its process name).
2. **Prefix targeting.** Must land the redist in the SAME container the game launches in, not
   the default prefix (see the `WINEPREFIX = imageFs.wineprefix` caveat). Inherit
   `ComponentExecInstaller`'s container binding but explicitly confirm on-device.
3. **App-restart plan across multiple redists.** Witcher 2 EE needs 4 deps → 4 sessions +
   restarts driven by the persisted plan. Verify the plan survives, resumes, and the user
   isn't stranded mid-sequence. Order matters for some stacks (e.g. runtime before a game that
   links it) — install in manifest order.
4. **Idempotency / already-installed.** Redist installers are self-idempotent (detect &
   no-op), but re-running still costs a session+restart. Gate on
   `component_installs`/`c<id>` = `GOG:<depId>`. Also consider a fast "is the runtime already
   present in the prefix?" check (registry/DLL probe) before scheduling.
5. **osBitness / arch.** Our prefix is arm64ec/64-bit under FEX; VC++ x86 runtimes must still
   install (32-bit apps need them). Install both `_x64` and x86 variants when the game lists
   them; don't drop x86.
6. **MSVC2026 etc.** The catalog already lists runtimes newer than any real installer we've
   tested under our Wine/FEX; a future-dated redist could fail to run. Fail soft per-redist —
   one redist failing must not block the game or the other redists; log and continue.
7. **Repository drift.** `build_id`/manifest hashes change when GOG updates redists. Always
   fetch the index fresh (don't pin a hash); cache only within a session.

---

## Open questions
- **Persist deps where** — `SharedPreferences gog_deps_<gameId>` vs shortcut `[Extra Data]`?
  (Extra Data ties it to the launch target and the chosen container; a pref is simpler to
  read at add-time. Likely: pref at download, mirror into Extra Data at add-to-container.)
- **Trigger** — auto-prompt on add-to-container vs a required manual "Install prerequisites"
  tap? (Lean: prompt once on add, always available manually.)
- **Keep or strip silent flags** per redist, given the visible-desktop trade-off (risk #1).
- **Pre-launch DLL/registry probe** to skip redists already satisfied by the base image — is
  it worth it, or is the `component_installs` marker enough for P1?
- **DirectX (`DXSETUP.exe`) / `.msi` deps** — do they install cleanly under our Wine/FEX, or
  do they need the wizard? (Device-probe before promising DirectX in P1; MSVC is the safe
  first target.)
- **ISI real contract (P2)** — exact `scriptinterpreter.exe` CLI + working dir, and whether
  Wine honours its registry/shortcut writes. Needs a dedicated probe on a title that fails
  without it.

---

## Task checklist (P1)
- [ ] `GogDownloadManager.runGen2`: parse + persist `dependencies` + `scriptInterpreter`.
- [ ] Refactor gen2 chunk download/inflate/MD5-verify into a reusable helper.
- [ ] `GogDependencyRepository`: repository index + manifest client (real shapes above),
      `depId → assembled installer file`. Skip `internal:true`.
- [ ] `GogRedistInstaller`: filter (internal/osBitness/already-installed) → stage into
      `drive_c/windows/temp` → build `Component` list → drive `ComponentExecInstaller`.
- [ ] `ComponentExecInstaller`: accept a pre-staged local installer (skip re-download).
- [ ] Idempotency markers `GOG:<depId>` in `component_installs`/`c<containerId>`.
- [ ] UX: post-add prompt + GOG-detail "Install prerequisites" action; message the visible
      setup session.
- [ ] Debug logging to the GOG buffer (deps, build_id, per-redist resolve/stage/skip).
- [ ] Self-review brace/compile-sanity (no local build).

## Build / verify (repo rules — NEVER build locally)
- [ ] Commit as The412Banner; push `feat/gog-redist-isi`.
- [ ] CI `build-artifacts.yml` on the branch; verify run headSha == pushed SHA; 3 flavors green.
- [ ] Stage the `pubg` artifact to `/sdcard/Download/` (cp only) for device test.
- [ ] Device test: **ELDERBORN** (single dep `MSVC2017_x64`) — add to a fresh container,
      run the prereq install, confirm the session auto-closes, the runtime lands in THAT
      container, and the game launches. Then **Witcher 2 EE** (4 deps) to exercise the
      multi-redist plan + resume-across-restart. Read the GOG debug buffer to confirm resolve
      → stage → install → idempotency-skip on a second run.
- [ ] No versionCode bump (feature build, not a release cut).

## Notes
- Do NOT regress the gen1/standalone-installer path — redist work is gen2-only. Classic
  installers often bundle their own prereqs; only apply repository redists when the game's
  gen2 build manifest lists `dependencies`.
- Keep everything fail-soft: a redist that won't download or install must log and let the
  game proceed, never hard-block launch.
</content>
</invoke>
