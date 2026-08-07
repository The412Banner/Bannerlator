# EA game support in GameHub — how it works, and what Bannerlator needs (installScript.vdf)

Research date: 2026-08-02. Trigger: user's Need for Speed Payback installs an "EA program" in
GameHub (`gamehub.lite` v6 Lite, 6.0.9 base / vc121) but the same cannot be reproduced in Bannerlator.
Evidence: on-device inspection of the actual game files + decompiles of GameHub 6.0.9/6.1.0 +
our `bannerhub-api` catalog mirror. Two RE agents (engine + container) + direct device teardown.

## TL;DR

GameHub did **not** build an EA client. Its "EA support" is that its Wine emulator **executes a Steam
game's `installScript.vdf` recipe inside the prefix at install time** — the same thing the real Steam
client does on PC. EA-published Steam titles ship the **real EA App (EA Desktop) installer inside the
depot**, and the installScript's `"Run Process"` step runs it. Bannerlator just copies files in and
launches the exe; it ignores `installScript.vdf`, so the EA app is never installed and the game bails.

The one feature to add: **a Steam `installScript.vdf` interpreter/executor** (Registry writes + Copy
Files + Run Process, with token substitution + a once-guard).

---

## Two separate things are labelled "EA" in GameHub — only one matters

### (A) Dormant Epic partner-activation engine — DEAD CODE (ignore)
Native Rust lib `libepickit_core.so` (an Epic Games Store client) can build
`link2ea://launchgame/{contentId}` deep links to hand an EGS-owned EA title to the EA App.
Enum `EpicActivationStoreRecord` = exactly `UBISOFT`, `EA`. **`activateApp()` is defined but NEVER
called** in 6.0.9; in 6.1.0 R8 stripped the `activate_app` JNI binding entirely (only `prepare_launch`
survives). `link2ea` exists only inside the `.so`. It would also require an online Epic account owning
the title. **Not the mechanism that installs EA. Red herring.**

### (B) The real mechanism — Steam `installScript.vdf` runs the game-bundled EA App installer
Confirmed by teardown of the user's actual copy:
`/data/data/gamehub.lite/files/Steam/steamapps/common/Need for Speed Payback/`

This is the **genuine Steam depot** — `steam_appid.txt = 1262580` (real Steam AppID for NFS Payback),
a real `installScript.vdf` with a valid Steam `kvsignatures` block. **Not** an anadius/cracked repack.
EA-published Steam games legitimately bundle the EA App installer in the depot.

Key files in the game dir:
- `installScript.vdf` (5.8 KB) — Steam's install recipe (see decoded content below).
- `EAappInstaller_installScript.vdf` (439 B) — uninstall cleanup for `EAappInstaller.exe`.
- `EAStore.ini` — `StoreName=Steam`, `ManagementMode=ReadOnly` (tells EA Desktop the title is
  Steam-managed / read-only, so it won't try to manage/update it).
- `EAWebKit.dll`, `Engine.BuildInfo.dll`, `Core/` (`Activation.dll`, `ActivationUI.exe`, Qt4 libs =
  legacy Origin activation UI), `Support/` (EA Help, User Agreement).
- `__Installer/Origin/redist/internal/EAappInstaller.exe` — **the real EA Desktop installer**.
- `__Installer/LOC/Origin.OFR.50.0002149.dat` + `...0002168.dat` — **Origin LocalContent offline
  entitlement / DRM licence files**.
- `__Installer/` also has the standard EA/Origin bootstrapper (`Touchup.exe`, `Cleanup.exe`,
  `installerdata.xml`) + bundled `vc_redist` (vc2013/vc2015) + full DirectX redist (`DXSETUP.exe`).
- `NeedForSpeedPayback.exe` (172 MB), `NeedForSpeedPaybackTrial.exe`.

### What `installScript.vdf` does (decoded)
It is Valve KeyValues (VDF). Sections:

1. **`Registry`** — writes under both WOW64 views:
   - `HKLM\SOFTWARE\EA Games\Need for Speed Payback` (WOW64_32 **and** WOW64_64): `Install Dir =
     %INSTALLDIR%`, `Product GUID = {F4CF3D08-565C-40B7-B351-D3033DE2172B}`, per-locale `DisplayName`.
   - `HKLM\SOFTWARE\Origin Games\1035208` (the Origin content/offer id) with per-locale DisplayName.
   - `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{F4CF3D08-...}` (Add/Remove entry,
     `publisher = Electronic Arts, Inc.`, `DisplayIcon = %INSTALLDIR%\NeedForSpeedPayback.exe`).
   - Uses `utf8_registry_strings = 1` and `HKEY_LOCAL_MACHINE_WOW64_32` / `_WOW64_64` view tokens.

2. **`Copy Files` → `LocFiles`** — copies the two Origin LocalContent `.dat` licence files:
   `%INSTALLDIR%\__Installer\LOC\Origin.OFR.50.0002168.dat` →
   `%PROGRAMDATA%\Origin\LocalContent\Need for Speed Payback\Origin.OFR.50.0002168.dat` (and ...2149).
   This is the **offline entitlement** that lets EA Desktop authorize the game without a login.

3. **`Run Process` → `EADesktopSetup`** — the "EA program install" the user sees:
   - `Process 1 = %INSTALLDIR%\__Installer\Origin\redist\internal\EAappInstaller.exe`
   - `Command 1 = EAX_LAUNCH_CLIENT=0 IGNORE_INSTALLED=1` (silent, don't auto-launch the client)
   - Once-guard: `HasRunStringKey = HKLM\SOFTWARE\Electronic Arts\EA Desktop\InstallSuccessful`,
     `HasRunStringValue = true`, `RunType = 1`.

4. **`Delete Files On Uninstall`** — removes the copied `.dat` files.

So the full install-time sequence GameHub performs (that Bannerlator does not):
**write EA/Origin registry keys → copy the Origin LocalContent licence `.dat`s to `%PROGRAMDATA%` →
run `EAappInstaller.exe` once (silent) → then launch the game exe.** The game then finds EA Desktop
installed + the local entitlement present and runs.

> NOTE on anadius: a *different*, cracked-repack path exists for some titles — the container agent
> found `ItTakesTwo.tzst` (catalog id 309) seeding `AppData/Local/anadius/LSX emu/` (anadius' EA-client
> emulator). That applies to warez repacks, **not** this legit Steam Payback copy, which uses the real
> EA installer via installScript. Both ultimately rely on GameHub's installScript-execution step.

---

## Runtime facts (device-verified)
- **Container / Proton:** default arm64 container = id 2 `proton10.0-arm64x-2` — **Proton 10 arm64ec**
  is the default (launch log: `wine_proton10.0-arm64x-2`, `isArm64X = true`). Proton 11 arm64ec (id 11)
  is installed/available but opt-in. Both live under `/data/data/gamehub.lite/files/usr/opt/`.
- **Firmware/imagefs 1.4.2** (vc32, `imagefs_142.zst`, md5 `6bcdc2568d26d6dbe90468fcdb4490ce`,
  173,024,718 B) — bakes only mono/gecko. **Nothing EA-specific.** EA support is orthogonal to firmware.
- No EA/Origin/EADesktop entry exists as a component, container, or firmware layer in the catalog. The
  EA installer rides inside the Steam depot download.

## Why Bannerlator can't do it today
1. Our worker (`bannerhub-worker.js` ~L1429–1471) **proxies** game-detail/scheme to upstream GameHub, so
   for GameHub the game package + any scheme install step come from XiaoJi. (Not the core blocker for the
   Steam path — the installScript ships *with the depot*.)
2. Bannerlator (Winlator/Cmod lineage) has **no `installScript.vdf` interpreter** and **no install-time
   "run this exe / write these registry keys / copy these files in the prefix" stage**. It copies the
   game folder and launches the exe. So `EAappInstaller.exe` never runs, the registry keys and licence
   `.dat`s never land, and NFS Payback bails because EA "isn't installed."

## What to build (feature)
A **Steam `installScript.vdf` interpreter/executor** that, at install time (post-download, pre/at first
launch), for a Steam game whose depot contains `installScript.vdf`:
1. Parse the VDF (KeyValues). Reuse any existing VDF/KeyValues parser (appmanifest/loginusers) if one
   exists; else add a minimal KeyValues parser.
2. Token-substitute `%INSTALLDIR%`, `%PROGRAMDATA%`, `%USERDIR%`, `%WINDIR%` to the in-prefix paths.
3. Apply `Registry` writes (both WOW64_32/64 views) — via `.reg` import into the prefix, or the existing
   registry-hive helper.
4. Apply `Copy Files` (the LocalContent `.dat`s → `%PROGRAMDATA%\Origin\LocalContent\...`).
5. Execute `Run Process` steps **inside the prefix** (headless), passing `Command N` env vars, honoring
   the `HasRunStringKey`/`HasRunStringValue` **once-guard** (persist "ran" state so it fires only once).
6. Record uninstall actions (`Delete Files On Uninstall`) for later.

## Integration plan (Bannerlator `main` recon, 2026-08-02)

### Current Steam-install flow
- Download: `SteamGameDetailActivity` → `SteamDepotDownloader.installApp` → `runInstall`. Files land at
  `<filesDir>/imagefs/steam_games/<name>` = `%INSTALLDIR%`; seen in Wine as **`Z:\steam_games\<name>`**
  (imagefs root symlinked to `Z:`, `WineUtils.java:31-32`). **Never copied into drive_c.**
- `SteamDepotDownloader.onDownloadCompleted` (~:612): size guard → `db.markInstalled` → emit
  `DownloadComplete:$appId`. **No setup, no exe, no container chosen yet.**
- Launch (separate, manual): `onLaunchClicked` → `ContainerPickerDialog` →
  `StarLaunchBridge.writeShortcutAsync` (`StarLaunchBridge.java:176`) writes a `.desktop` into the
  chosen container. **First point where `(container, appId, installDir)` all exist.**
- `installScript` appears **zero** times in `app/src`.

### What already exists to reuse
- **Run exe in-prefix + auto-close + resume + once-guard:** `ComponentExecInstaller`
  (`components/ComponentExecInstaller.kt`) — stages exe → transient `.desktop`
  (`Exec=wine <target>`, `[Extra Data] execArgs/envVars`) → `XServerDisplayActivity` with
  `component_installer_exe`; watched by `startInstallerWatch`/`evaluateInstallerTick`
  (`XServerDisplayActivity` ~:3186/:6565) which calls `exit()` when the installer proc disappears;
  cross-restart plan in prefs `pending_component_install`; completion in prefs `component_installs`
  key `c<containerId>`. Spawn API: `GuestProgramLauncherComponent.setGuestExecutable/setEnvVars/
  execGuestProgram` → `ProcessHelper.exec(cmd, envp, workDir, cb)` (`ProcessHelper.java:77`).
- **Registry:** `WineRegistryEditor` (`core/WineRegistryEditor.java`) edits `system.reg`/`user.reg`
  directly (clone→edit→atomic rename). `setStringValue/setDwordValue/setHexValue/removeValue/
  removeKey/importReg`. **WOW64 done by hand** (write both `Software\…` and `Software\Wow6432Node\…`).
  `%PROGRAMDATA%` = per-container `.wine/drive_c/ProgramData` (`Container.java:880`).
  Copy-to-ProgramData precedent: `AmazonSdkManager.deploySdkToPrefix` (`store/AmazonSdkManager.java:143`).
- **No VDF parser exists.** `GameIdentifier.vdfValue()` = flat `.acf` regex; `KeyValueSet` = `key=value`.
  Must write a real recursive KeyValues tokenizer.

### New classes
`VdfParser` (recursive KeyValues, clean-room from Valve's documented schema — GPL-3.0 repo),
`InstallScriptModel`, `InstallScriptTokens` (token subst + WOW64 view mapping), `InstallScriptExecutor`.

### Hook + steps (per container)
- **Primary hook:** `StarLaunchBridge.writeShortcutAsync` (container/appId/installDir all known).
  **Robustness hook:** pre-first-launch in `XServerDisplayActivity` for `steamAppId`-tagged shortcuts.
- Steps: locate+parse `installScript.vdf` under `installDir` → token-subst (⚠️ `%INSTALLDIR%` = `Z:`,
  `%PROGRAMDATA%` = container `C:\ProgramData`) → apply `Registry` via `WineRegistryEditor`
  (`_WOW64_32`→`Software\Wow6432Node\…`, `_WOW64_64`/HKLM→`Software\…`, HKCU→`user.reg`) →
  apply `Copy Files` (LocalContent `.dat`s → `%PROGRAMDATA%\Origin\LocalContent\…`) → drive
  `Run Process` (`EAappInstaller.exe`) through the `ComponentExecInstaller` session mechanism,
  **passing args verbatim** (`EAX_LAUNCH_CLIENT=0 IGNORE_INSTALLED=1` — do NOT use `visibleArgs()`
  which strips `/quiet`).
- **Once-guard, per container:** honor the script's `HasRunStringKey` registry value (faithful), or
  mirror `component_installs` with prefs `installscript_executed` key `c<containerId>` (or an additive
  `steam_games` column via the existing `ALTER TABLE` pattern, `SteamDatabase.java:214`, bump DB_VERSION).

### Risks
1. **No container at download time** → executor must run at set-up/first-launch, once per container.
2. **arm64ec/FEX running x64 `EAappInstaller.exe`** — heavy EA bootstrapper may fork resident services;
   the 3-tick "process gone" auto-close (`:6565`) may mis-fire early or never fire. Prefer
   **success-detection via the guard key / output file** over process-gone. Fallback: pre-bake a
   container snapshot with EA Desktop already installed + a per-game entitlement drop.
3. **Silent vs visible** — pass installScript args verbatim; don't strip via `visibleArgs()`.
4. **WOW64 hive split** — map `_WOW64_32`→`Wow6432Node` (system.reg) vs `_64`/HKLM→system.reg,
   HKCU→user.reg; strip the hive-root prefix `WineRegistryEditor` doesn't expect.
5. **Token edges** — `%INSTALLDIR%`=`Z:` not `C:`; Copy Files source on `Z:`, dest `%PROGRAMDATA%` on
   per-container `C:`. `importReg` only supports String/Dword today (extend for binary/expand_string).
6. **Confirm the depot delivers `installScript.vdf`** (it does for the user's NFS Payback copy); else
   fetch via PICS `config`.
7. **Uninstall symmetry** — capture `Delete Files On Uninstall` → wire to `markUninstalled` so the
   Origin licence `.dat`s don't leak.
8. **Licensing** — keep the VDF parser + executor clean-room.

Companion recon report: scratchpad `bannerlator_installscript_recon.md`.

## On-device FEX test result (2026-08-02) — EA-CLIENT INSTALL IS THE WALL

Tested on a real Bannerlator **P10 Arm** container (id 3, `Proton-10.0-arm64ec-0`):
- ✅ Bannerlator's Steam downloader pulls the **full EA depot** (installScript.vdf +
  `__Installer/Origin/redist/internal/EAappInstaller.exe` 230 MB + `__Installer/LOC/*.dat`).
- ✅ **`EAappInstaller.exe` RUNS under FEX** — "Installing the EA app…" GUI + progress bar.
- ❌ **EA Desktop MSI FAILS: `Error 0x8007065b` = Win32 1627 `ERROR_FUNCTION_FAILED`**
  ("Failed to configure per-machine MSI package"), and the **MSI log is 0 bytes** ⇒ Wine's
  `msiexec` cannot run EA Desktop's MSI under arm64ec/FEX. (WixBundle burn log:
  `INST-14-1627`.) EA Desktop is known-hard under Wine; this is a runtime/compat wall, NOT an
  executor bug and NOT fixable via registry/entitlement (the client itself never installs).
- Game launch then errors: **"Origin is not installed, and is required to play your game"**
  (`Core\Activation.dll` requires a real EA/Origin client).

### Strategic conclusion
**GameHub has NO `EADesktop.exe` / `Origin.exe` / `EACore.ini` / `anadius` / Origin-client
registry in ANY of its prefixes** (deep-scanned). So GameHub is **not** installing the real EA
app either — it satisfies NFS Payback's Origin check another way, almost certainly an **Origin
emulator / cracked build** (matches the anadius LSX-emu pattern for EA games). GameHub's NFS
copy was deleted mid-session before its exact stub could be captured.

⇒ The legit "install the real EA app" path hits a **hard Wine wall** (EA Desktop MSI). Options:
1. **Capture GameHub's working launch** (logcat `gamehub.lite`+`:pcengine` + `pcLaunchLog` +
   before/after container diff) to learn the exact Origin-satisfying mechanism, then reproduce.
   ← chosen next step.
2. **Legacy Origin** (the game asks for "Origin", not EA Desktop; old Origin has better Wine
   odds) — untried; depot ships only the EA Desktop installer, would need OriginThinSetup.
3. **Origin emulator / crack** = GameHub's likely method, but warez tooling — out of scope to
   procure/bundle; can be identified + explained, not shipped.

The `installScript.vdf` executor feature is validated end-to-end EXCEPT the EA-client install,
which is an environment/Wine-compat problem independent of the executor.

## Key references
- Device game dir: `/data/data/gamehub.lite/files/Steam/steamapps/common/Need for Speed Payback/`
  (`installScript.vdf`, `EAappInstaller_installScript.vdf`, `EAStore.ini`, `steam_appid.txt=1262580`,
  `__Installer/Origin/redist/internal/EAappInstaller.exe`, `__Installer/LOC/Origin.OFR.50.*.dat`).
- Engine RE (dormant Epic): scratchpad `ea_engine_findings.md`; memory
  `reference_gamehub_ea_support_anadius_mechanism`, `reference_gamehub_608_ea_epic_support`.
- Container/runtime RE: scratchpad `ea_container_findings.md`; catalog
  `bannerhub-api/data/{imagefs.json,containers.json,defaults.json}`.
- Scheme mechanism: memory `reference_gamehub_dependency_scheme_mechanism`.
