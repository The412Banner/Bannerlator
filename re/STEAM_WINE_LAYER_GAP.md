# Steam Wine/Proton LAYER Gap — GameHub vs GameNative vs WinNative vs Bannerlator stock Proton

**Question (verbatim intent):** *"Is there something in GameHub's Proton/Wine layers we're missing to allow this to work?
Something like WinNative's or GameNative's layers labeled 'steam'? Their steam-labeled layers must have added
files, libs, or altered files specific to launching Steam games online."*

**Method:** READ-ONLY. On-device root-bridge inventory of the stock Proton + the live `xuser-3` prefix + GameHub's
own wine/components; source inventory of GameNative (`/home/claude-user/gamenative-today` `1ad70ae5`), WinNative
(`/home/claude-user/winnative-today`), and our `proton-wine` `*_add_steam` branches. Nothing installed/modified.
**Author:** wine/proton compat engineer · **Date:** 2026-08-27 · **Branch:** `feat/steam-vac-phase0`

> **TL;DR — the layer is NOT missing a steam FILE.** For GameHub's genuine-client (SteamAgent) path we already staged
> the exact same steam payload GameHub uses (`steam_client_0403` + `SteamAgent.exe`). The gap is a single **behavioral**
> difference: our stock Proton's `ntdll` still carries Proton's **`lsteamclient` load-interception hook**, and our launch
> **does not set `PROTON_DISABLE_LSTEAMCLIENT=1`** to turn it off. So when `SteamAgent.exe` `LoadLibrary`s the genuine
> `steamclient64.dll`, ntdll hijacks that load toward a **non-existent `lsteamclient.dll`**, the load fails, the agent
> shows an error dialog and exits — exactly the observed symptom. **`lsteamclient`/bionic is a red herring for this path.**

---

## 0. What each artifact actually is (so the table below is unambiguous)

There are **two mutually-exclusive Steam runtime architectures**. Do not blur them:

- **Path A — "genuine PE client, driven by a headless agent"** (GameHub's `SteamAgent`; WinNative's `planW`).
  The game loads its **own genuine Valve `steam_api(64).dll`**, which loads the **genuine Valve PE
  `steamclient(64).dll`** staged in the prefix. A headless `steam.exe` replacement drives that client via
  `CLIENTENGINE_INTERFACE_VERSION005`. **Proton's `lsteamclient` shim is DELIBERATELY DISABLED** so the game/agent talk
  to the *real* Windows DLL, not Proton's unix bridge. **This is the path our prototype is on.**
- **Path B — "Proton `lsteamclient` shim → bionic native client"** (GameNative default; WinNative's default "Bionic
  Steam"). A PE `lsteamclient.dll` (in system32/syswow64) thunks Steamworks calls over `WINE_UNIX_CALL` to a
  **bionic/Android `libsteamclient.so`** running in the app process. This path **REQUIRES `lsteamclient`** and a native
  `.so` CM client. It is a different design and is **NOT** what GameHub/SteamAgent does.

Everything about the prototype failure lives inside Path A.

---

## 1. The comparison table — steam files present / absent

| Layer piece | GameHub (Path A) | GameNative (Path B) | WinNative `planW` (Path A) | WinNative default (Path B) | **Bannerlator stock Proton 11.0-2 + our staging** |
|---|---|---|---|---|---|
| genuine PE `steamclient64.dll` / `steamclient.dll` | ✓ `steam_client_0403` → `C:\Program Files (x86)\Steam` | ✓ `steamclient-dlls-20260619.tzst` (SteamStub DRM only) | ✓ `wnsteam/bionic/valve-steam-x86_64.tzst` | ✓ (DRM) | **✓ STAGED** (Steam dir) |
| `tier0_s(64).dll`, `vstdlib_s(64).dll` | ✓ (Steam dir) | ✓ | ✓ (Steam dir + copies to system32/syswow64) | — | **✓ STAGED** (Steam dir only; not in system32) |
| `steamservice.exe` + `.dll` | ✓ (agent installs) | — | ✓ `Steam/bin/` (registers WinService) | — | **✓ STAGED** (`Steam/bin/`) |
| headless `steam.exe` replacement (agent) | ✓ `SteamAgent2/SteamAgent.exe` | — | ✓ `wn-steam-launcher` = `Steam/steam.exe` | `wn-steam-helper.exe` | **✓ STAGED** (`SteamAgent.exe`) |
| PE `lsteamclient.dll` (Proton shim) | **✗ disabled** | ✓ system32 + syswow64 | **✗ disabled** | ✓ | **✗ absent — BUT ntdll hook still active** |
| unix `lsteamclient.so` | ✗ | ✓ `lib/wine/*-unix/` | ✗ | ✓ | ✗ |
| bionic `libsteamclient.so` (native CM client) | ✗ | ✓ `imagefs/usr/lib/` | ✗ | ✓ `filesDir/libsteamclient.so` | ✗ |
| **ntdll `use_lsteamclient` interception hook** | present but **gated OFF** | present, **ON** | present but **gated OFF** | present, ON | **present, DEFAULT-ON ← the problem** |
| **env `PROTON_DISABLE_LSTEAMCLIENT=1`** | **✓ (log proves)** | ✗ (wants the shim) | **✓ (`XServer…:7328`)** | ✗ | **✗ MISSING** |
| **env `WINEDLLOVERRIDES=…;lsteamclient=`** | (implied by A) | ✗ | **✓ (`XServer…:7316`)** | ✗ | **✗ MISSING** |
| `WINESTEAMCLIENTPATH{,64}` | **✗ (must NOT set)** | ✓ → `linux{64,32}/steamclient.so` | **✗** | ✓ → `filesDir/libsteamclient.so` | ✗ (correct for A) |
| registry `HKLM/HKCU\…\Valve\Steam` `SteamExe`/`SteamPath`/`SteamClientDll64`/`InstallPath` | agent writes at runtime | Java + runtime | agent writes at runtime (+ Java) | | **only `InstallPath` present** (agent writes rest — but agent never ran) |
| registry `…\Steam\ActiveProcess` (`SteamClientDll64`, `ActiveUser`, `Universe`) | running client writes | Java (`XServer…:5942`) | agent writes (`main.cpp:146`) | | not present (agent never ran) |
| env `SteamAppId`/`SteamGameId`/`SteamClientLaunch`/`SteamPath`/`ValvePlatformMutex` | native launcher | ✓ | ✓ | ✓ | **✗ not wired** (game env) |
| control socket: env `STEAMAGENT_PORT` + Android `SteamAgentServer` | ✓ | ✓ (`57343/57344`) | ✓ (`WN_STEAM_*` + ports) | ✓ | **✗ absent (separate task)** |

**Evidence for each stock-Proton cell is in §2–§4.**

---

## 2. Bannerlator stock Proton 11.0-2-arm64ec-1 — what's actually on device

`/data/data/com.tencent.ig/files/contents/Proton/11.0-2-arm64ec-1/`

- `profile.json`: *"stock Valve + fast-yield gate + FEX-unixlib loader + DirectAudio v1.3.1 + Android fixes."* i.e. a
  vanilla upstream-Valve Proton 11.0-2 base.
- **Zero steam files in the entire tree.** `find … -maxdepth 4 -iname "*steam*"` → empty. No `lsteamclient.dll`, no
  `libsteamclient.so`, no `steamclient*.dll`, no `steam.exe`, in any of `lib/wine/{aarch64-windows,i386-windows,aarch64-unix}`
  or `bin/`.
- **BUT the ntdll `lsteamclient` interception survives from upstream Proton** — this is the load-bearing finding:
  - PE `lib/wine/aarch64-windows/ntdll.dll` contains (verified with `strings -a` and `strings -a -e l`):
    - ASCII: `use_lsteamclient`, `lsteamclient.dll`, `lsteamclient disabled.`, `steamclient ImageBase %#Ix.`
    - UTF-16LE (wide): `PROTON_DISABLE_LSTEAMCLIENT`, `lsteamclient64.dll`, `steamclient`, `steamclient64`
  - unix `lib/wine/aarch64-unix/ntdll.so` contains: `steamclient_setup_trampolines`, `steamclient`.
  - This is upstream Proton's `dlls/ntdll/loader.c` `use_lsteamclient()` + `dlls/ntdll/unix/loader.c`
    `steamclient_setup_trampolines()`: **when any module named `steamclient`/`steamclient64` is loaded, ntdll
    `LdrLoadDll("lsteamclient.dll")` and re-points the genuine DLL's entrypoints into it** — unless
    `PROTON_DISABLE_LSTEAMCLIENT` is set, in which case it logs `lsteamclient disabled.` and leaves the genuine DLL
    alone.

**Our prefix `…/imagefs/home/xuser-3/.wine/`:**
- `drive_c/Program Files (x86)/Steam/`: staged genuine client is present and correct —
  `steamclient64.dll`, `steamclient.dll`, `tier0_s(64).dll`, `vstdlib_s(64).dll`, `SteamAgent.exe`,
  `bin/steamservice.exe`, `bin/steamservice.dll`, `bin/x64launcher.exe`, `bin/steam_monitor.exe`. **Placement matches
  GameHub exactly.**
- `drive_c/windows/system32` and `syswow64`: **no steam files** (no `lsteamclient.dll`, no genuine steam DLLs).
- `system.reg` `[Software\Wow6432Node\Valve\Steam]`: **only `"InstallPath"="C:\Program Files (x86)\Steam"`** — no
  `SteamExe`, `SteamClientDll`, `SteamClientDll64`, `SteamPath`, `SteamPID`. (This is normal: the agent writes those at
  runtime. See §3 — GameHub's own prefix is identical.)
- **No global `[Software\Wine\DllOverrides]`** section at all (only per-app `AppDefaults\<game>.exe\DllOverrides`);
  no steam override anywhere.
- Container env (`container-3.json` `envVars`, backed up):
  `WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false
  MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 TU_DEBUG=noconform,sysmem DXVK_HUD=fps,api`
  — **no `PROTON_DISABLE_LSTEAMCLIENT`, no `WINEDLLOVERRIDES`.** `wineVersion: Proton-11.0-2-arm64ec-1`.

**Conclusion:** the genuine-client *files* are complete. What is missing is the env that turns off the ntdll hook.

---

## 3. GameHub's own layer — proves the fix and proves the file-set is equivalent

`com.xiaoji.egggame`:
- Wine: `metadata.json` → `proton11.0-arm64x` at `usr/opt/wine_proton11.0-arm64x` — **same Proton 11.0 arm64ec major
  as ours**. Its wine tree has **no** steam files and **no** `lsteamclient` (`find … -iname "*lsteam*"` empty).
- Steam components (the ONLY steam-labeled things GameHub ships): `usr/home/components/steam_client_0403` (genuine PE
  Valve client) + `usr/home/components/SteamAgent2/SteamAgent.exe` (the headless agent). **These are the exact two we
  staged.** No third "steam wine layer."
- `usr/home/components/base` overlay = a `system32`/`syswow64` DLL delta of **winetricks payload only** (d3dx9/10/11,
  d3dcompiler_33..47, msvcr/msvcp, atl, quartz, dsound, icu…). **No steam DLL, no patched wine builtin.**
- GameHub container `containers/0` registry: `[Software\Wow6432Node\Valve\Steam]` = **only `InstallPath`** (identical to
  ours → confirms the agent writes `SteamExe`/`SteamClientDll64`/… at runtime). Its global
  `[Software\Wine\DllOverrides]` is the standard winetricks set + `libarm64ecfex`/`libwow64fex` — **zero steam
  entries** (no `steamclient`, no `lsteamclient`).
- **The smoking gun (from the runtime log, `GAMEHUB_STEAMAGENT_RE.md §1`):** `err:module:use_lsteamclient lsteamclient
  disabled`. That literal is the disabled-branch of the very `use_lsteamclient()` hook we found compiled into *our*
  ntdll (`lsteamclient disabled.`). **GameHub runs Path A precisely by setting `PROTON_DISABLE_LSTEAMCLIENT`.** Same
  wine, same steam files as us — the difference that makes it work is that env gate.

---

## 4. GameNative / WinNative — what their "steam" layers add (and why most of it is Path B)

**GameNative (`1ad70ae5`)** — Path B only for the online client:
- Assets (downloaded at runtime, `BionicSteamAssetsDependency.kt`): `lsteamclient-{x86_64|arm64ec}-proton{9,10,11}.tzst`
  → `lsteamclient.dll` into `system32`/`syswow64` + `lsteamclient.so` into `lib/wine/*-unix`;
  `steam-androidarm64-20260709.tzst` → `imagefs/usr/lib/libsteamclient.so`;
  `steamclient-dlls-20260619.tzst` → genuine steamclient (DRM only); `steam-proton11.exe` → `Steam/steam.exe`;
  `cacert.pem`.
- Env (`BionicProgramLauncherComponent.java:498+`): `WINESTEAMCLIENTPATH{,64}=…/linux{64,32}/steamclient.so`,
  `_STEAM_SETENV_MANAGER=1`, `Steam3Master=127.0.0.1:57343`, `SteamClientService=127.0.0.1:57344`,
  `SteamAppId/SteamGameId/SteamClientLaunch`, `SteamPath`, `ValvePlatformMutex`, `SteamUser/SteamAppUser`, `STEAMID`.
- Registry (Java): `HKCU\…\Steam\ActiveProcess` (`SteamClientDll(64)`, `ActiveUser`, `Universe`), `HKCU\…\Steam`
  (`AutoLoginUser`, `SteamExe`, `SteamPath`, `InstallPath`).
- **This is Path B — the game's steamworks calls go through `lsteamclient` to a bionic client. Irrelevant to SteamAgent
  except as the thing we must keep OFF.** GN sets **no** `PROTON_DISABLE_LSTEAMCLIENT` (it *wants* the shim).

**WinNative** — ships BOTH, and its **`planW` mode is Path A = an exact analog of what we're doing:**
- planW files (`WnSteamAssetsInstaller.kt`): `wnsteam/bionic/valve-steam-x86_64.tzst` → genuine
  `steamclient64.dll`/`steamclient.dll`/`tier0_s(64)`/`vstdlib_s(64)` into `…/Steam` (+ `tier0_s64`/`vstdlib_s64` copied
  to `system32`, `tier0_s`/`vstdlib_s` to `syswow64`); `bionic/steam.exe` (`wn-steam-launcher`) → `Steam/steam.exe`;
  `bionic/steamservice.{exe,dll}` + version `.vdf`s → `Steam/bin/`; `wnsteam_cacert.pem`.
- **planW env (`XServerDisplayActivity.java:7316-7328`) — the piece we need:**
  - **`WINEDLLOVERRIDES += ";lsteamclient="`** (empty value = **disabled**), comment: *stop the bionic lsteamclient
    bridge from hijacking exports so the game talks to the real in-prefix `steamclient64.dll`.*
  - **`PROTON_DISABLE_LSTEAMCLIENT=1`**, comment: *"bypass ntdll lsteamclient hooks for Proton 10+."*
- planW launcher (`wn-steam-launcher/src/main.cpp`) drives the genuine client via
  `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `IClientEngine` → `SetLoginToken`+`LogOn` →
  `IClientAppManager::LaunchApp`, writes `ActiveProcess`/`Valve\Steam` registry itself, and registers
  `Steam Client Service` (`steamservice.exe /RunAsService`) via `CreateServiceA`. `build.sh:5-11`: *"it hosts Valve's
  real steamclient64.dll … named 'steam.exe' because steamclient's CGameLauncher path requires its host process to look
  like real Steam."* **Same shape as GameHub's SteamAgent — and it explicitly disables the ntdll hook. This is the
  independent confirmation of the fix.**

**Our `proton-wine *_add_steam` branches:** only `proton_9.0`/`proton_10.0` exist — **there is NO `proton_11.0_add_steam`**.
They build the **Path B `lsteamclient.dll` + unix `lsteamclient.so` shim pair** (`build-scripts/build-steam-targets.sh`,
`make -j lsteamclient/all steam_helper/all`) plus Valve's BSD `steam_helper/steam.exe` stub; the ntdll trampoline code
they re-add is *the very hook* Path A must disable. **These branches are the wrong tool for the SteamAgent path.**

---

## 5. The definitive missing-layer-pieces list for the SteamAgent (Path A) launch

Ordered by whether it blocks the immediate failure:

1. **[BLOCKER — the gap] `PROTON_DISABLE_LSTEAMCLIENT=1` in the launch env.** Without it, our stock Proton's ntdll
   intercepts the genuine `steamclient64.dll` load, tries to `LdrLoadDll("lsteamclient.dll")` (which does not exist in
   our layer), the load fails, and `SteamAgent.exe` errors out (the observed `Button/#32769` error dialog → teardown).
   GameHub sets it (log-proven); WinNative planW sets it (source-proven).
2. **[BELT-AND-SUSPENDERS, same fix] `WINEDLLOVERRIDES` append `;lsteamclient=`** (empty value → the module is force-
   disabled at the loader level too). WinNative planW sets both #1 and #2 together. Cheap; set both.
3. **[needed, but the agent does it at runtime] registry seed** `HKLM\Software\Valve\Steam`:
   `SteamExe`=`…\SteamAgent.exe`, `SteamPath`/`InstallPath`=`C:\Program Files (x86)\Steam`,
   `SteamClientDll`=`…\steamclient.dll`, `SteamClientDll64`=`…\steamclient64.dll`, `SteamPID`. Our prefix has only
   `InstallPath` — but so does GameHub's; the agent writes the rest **once it actually runs** (i.e. once #1 unblocks it).
   Only pre-seed these if we can't rely on the (unproven-source) agent to.
4. **[game-env, when the game finally spawns] `SteamAppId`/`SteamGameId`/`SteamClientLaunch`/`SteamPath`/
   `ValvePlatformMutex`** on the game process (not the agent). Not wired in the prototype. Needed for the game's
   `steam_api.dll` to bind, but only reached after the agent is up.
5. **[optional hardening] copy `tier0_s64.dll`/`vstdlib_s64.dll` → `system32`, `tier0_s.dll`/`vstdlib_s.dll` →
   `syswow64`.** WinNative planW does this; GameHub does not (co-location in the Steam dir suffices). Do it only if a
   dependency-resolution error shows up after #1.
6. **[NOT a layer piece — the other task] env `STEAMAGENT_PORT` + an Android `SteamAgentServer` socket.** The agent
   streams status/telemetry to it and the launcher gates the game spawn on `login_success`. Even with #1 fixed, the
   agent still needs this to report readiness. Out of scope here; tracked as candidate-cause (2).

**Files we are NOT missing:** the genuine PE steam DLLs, `steamservice`, and the agent are all staged and correctly
placed. **We do NOT need `lsteamclient.dll` or a bionic `libsteamclient.so` for this path** — those belong to Path B and
would fight the genuine client.

---

## 6. Answers to the four questions

**(a) Definitive missing-layer-pieces list for the SteamAgent genuine-client path:** see §5. Headline: it is **not a
missing file** — it is the missing env **`PROTON_DISABLE_LSTEAMCLIENT=1`** (+ `WINEDLLOVERRIDES=…;lsteamclient=`) that
neutralizes the `use_lsteamclient` ntdll hook still baked into our stock Proton. Downstream, once the agent runs, it
needs its registry seed (agent-written) and the game-env block; and separately the `STEAMAGENT_PORT`/`SteamAgentServer`
plumbing (other task).

**(b) The single highest-value thing to add next:** **set `PROTON_DISABLE_LSTEAMCLIENT=1` (and append `;lsteamclient=`
to `WINEDLLOVERRIDES`) in the Real-Steam launch env.** It directly explains and clears the immediate exit, requires no
new binaries, and is exactly what both working implementations (GameHub log; WinNative planW source) do. Concretely for
the by-hand prototype: add it to the container `envVars` (or the shortcut's `extraEnv`); for the shipped feature, add it
in the `launchMode=RealSteam` branch (`GuestProgramLauncherComponent`/`envVars` in `XServerDisplayActivity`).

**(c) Is `lsteamclient`/bionic needed for GameHub's approach, or a red herring?** **Red herring for Path A.** GameHub
(and WinNative planW) run the genuine PE `steamclient64.dll` and **explicitly disable** `lsteamclient`. `lsteamclient` +
bionic `libsteamclient.so` are the Path B (GameNative-style) architecture; adding them would re-route Steamworks to a
bionic bridge and undermine the genuine-session/VAC posture. Keep `lsteamclient` OFF for SteamAgent.

**(d) Are the `proton-wine *_add_steam` outputs the thing to inject (for Proton 11)?** **No.** Those branches build the
Path B `lsteamclient` shim, they re-introduce the very ntdll hook Path A must suppress, and **no `proton_11.0_add_steam`
branch even exists** (only 9.0/10.0). GameHub sidesteps `lsteamclient` entirely; our gap is the env gate (and the
agent's Android server), not a proton-wine steam build.

**(d, doc path):** `/home/claude-user/bl-wt-steam-vac/re/STEAM_WINE_LAYER_GAP.md`

---

## 7. Concrete recommendation — "to make the layer Steam-capable, add/alter X, Y, Z"

1. **X — env gate (do first, this is the whole gap):** in the Real-Steam launch env set
   `PROTON_DISABLE_LSTEAMCLIENT=1` and append `;lsteamclient=` to `WINEDLLOVERRIDES`. Nothing else about the wine
   layer needs to change for the agent to get past its immediate exit.
2. **Y — verify, then wire the rest of Path A:** with X in place, re-run; expect the log to show `lsteamclient disabled.`
   and the agent to proceed to `steamclient64.dll` load / `steamservice` install / `CLIENTENGINE_INTERFACE_VERSION005`.
   Then ensure the registry seed exists (let the agent write it, or pre-seed §5-#3) and set the game-env block (§5-#4).
3. **Z — the parallel dependency (separate task):** provide `STEAMAGENT_PORT` + an Android `SteamAgentServer` so the
   agent can report `login_success` and the launcher can gate the game spawn. This is candidate-cause (2), not the
   layer — but the agent is not fully functional without it.

**Do NOT** inject `lsteamclient.dll` / bionic `libsteamclient.so` / the `proton-wine *_add_steam` outputs for this path;
they implement the opposite (Path B) architecture and conflict with the genuine-client design.

> **Confidence:** the layer inventory and the ntdll-hook mechanism are **device-proven** (binary strings + live prefix +
> container env). That `PROTON_DISABLE_LSTEAMCLIENT=1` alone clears the *immediate* exit is a strong, evidence-backed
> expectation (matches GameHub's log and WinNative planW's source) but is **not yet device-proven for our build** — it
> is the next thing to test on device.
