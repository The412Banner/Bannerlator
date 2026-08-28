# GameHub "Real Steam (no Goldberg)" Launch Orchestration — Fork Spec

**Purpose:** Extract GameHub's *exact* recipe for launching a Windows Steam game with the **genuine Valve
client** (driven by its headless **SteamLite / `SteamAgent`** agent) instead of the Goldberg emulator — as the
concrete spec for a new Bannerlator **"Real Steam (online)"** launch mode that replaces Goldberg at launch of a
Steam-tagged shortcut.
**Method:** READ-ONLY. GameHub 6.2.1 (`com.xiaoji.egggame`, vc138) jadx decompile + on-device PE `strings` +
the on-device V6 component bundle. Nothing launched, installed, or modified.
**Author:** storefront / native-Steam engineer · **Date:** 2026-08-27 · **Branch:** `feat/steam-vac-phase0`

> **PII:** the device owner's Steam email and SteamID appear in on-device artifacts. They are the user's own data
> and are **redacted** here as `<owner-email>` / `<owner-steamid>`.

> **Companion docs (do not duplicate — this one is the launch-orchestration slice):**
> `re/GAMEHUB_STEAMAGENT_RE.md` (the agent PE + control-socket protocol + `libsteamkit_core` division of labor),
> `re/PHASE1A_VAC_TEST_AND_WIRING.md` (§D2-1..D2-5 Bannerlator wiring + by-hand VAC test),
> `re/SYNTHESIS_OWN_STEAM_AGENT_PLAN.md` (the clean-room build plan). This doc is the ordered **recipe** those
> reference.

---

## 0. Artifacts cited (every load-bearing claim points at one)

**Decompile** (`/home/claude-user/gamehub-6.2.1-jadx/sources/…`):
- `defpackage/zd5.java` — `SteamGameInfo` Parcelable = the launch contract (field map recovered from `toString`, zd5.java:60-99).
- `defpackage/jb0.java:209` — the real-vs-Goldberg decision comment.
- `defpackage/eqa.java:29-35` — the `SteamApi` method vocabulary (`getSteamGameLaunchContext`, `getSteamLaunchFilePath`, `canUseSteamClient`) log strings.
- `defpackage/mvp.java` — `SteamAgentLaunchAcceleration(appcacheRoot, cmListPath, cmDatacenter, cmServerCount)`.
- `defpackage/mtd.java:25-32` — `ImportedSteamGameInfo(steamAppId, steamAppFolder, installRoot, steamUserId, steamUserName, gameLaunchArgs)` (the persisted import descriptor).
- `com/xiaoji/egggame/common/steam_sdk/bridge/{SteamBridgeNativeInitializer,a}.java` — `libsteamkit_core` JNA bring-up.

**On-device PE** (via root bridge):
- `/sdcard/SteamAgent_unpacked.exe` (V5 family, sections restored — the readable agent; V6 `SteamAgent-60.exe` and the in-bundle `SteamAgent2/SteamAgent.exe` are packer-encrypted and yield no strings).
- `/storage/emulated/0/Download/steamagent/steam_client_0403/` — the V6 genuine-client component (build 10520955).

**Bannerlator worktree** (`/home/claude-user/bl-wt-steam-vac/…`):
- `app/src/main/java/com/winlator/star/store/GoldbergPatcher.kt` (the swap path Real-mode must NOT run).
- `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` (the launch activity + the Steam/Goldberg hook at `maybeSeedAndStartAchievementWatcher()` :4478, gate `isSteamShortcut` :3986/:3993, resolver `resolveSteamAppRefFrom` :4180).

---

## 1. The real-vs-Goldberg branch — one boolean

GameHub carries a single per-launch switch inside the `SteamGameInfo` parcel (`zd5.java`, field-letter → name from
`toString` at zd5.java:60-99):

| zd5 field | Name | Role |
|---|---|---|
| `a` | `steamDir` | genuine-client dir (`…/Steam`) |
| `b` | `steamLibraryDir` | library root |
| `c` | `steamGameDir` | the installed game dir |
| `d` | `steamAccount` | account name (`--username`) |
| **`e`** | **`steamToken`** | **the user's refresh token → `--token` (see §3)** |
| `f` | `steamAgentPath` | path to `SteamAgent.exe` (the headless steam.exe) |
| `g` | `steamUserId` (int) | |
| `h` | `steamAppId` (int) | the appid |
| `i` | `silentMode` | |
| `j` | `offlineMode` | → `--offline` |
| `k` | `noVerifyFile` | |
| `l` | `cloudEnable` | cloud on/off (else `--disablecloud`) |
| `m` | `installScript` | → `--runinstallscript` |
| `n` | `launchOption` (int) | which launch option / `--launchoption` |
| `o` | `cellId` (Integer) | → `--cellid` |
| `p` | `language` | → `--language` |
| **`q`** | **`fakeSteamClient`** | **THE SWITCH: `true` = Goldberg path; `false` = genuine `SteamAgent` path** |
| `r` | `steamInputEnable` | |
| `s` | `cmAccel` | CM-acceleration on |
| `t` | `cmProxy` | |
| `u` | `cmDatacenter` | |
| `v` | `appcacheRoot` | pre-seeded appcache (see §3 acceleration) |
| `w` | `skipAppInfoRefresh` | |
| `x` | `cmListPath` | pre-seeded CM list |
| `y` | `joinLobbySteamId` | friend-join target |
| `z` | `connectString` | server-connect target |

**`fakeSteamClient=false` selects the genuine-client (SteamAgent) launch; `true` selects Goldberg/GSE emulation.**
Both emulator components are staged side-by-side for every prefix (`ensureGlobalSteamComponents append goldberg-1.2 …
append SteamAgent2-1.0.3`, runtime log), and this flag chooses at launch.

The decision that *drives* the flag lives in `PcEmulatorLaunchStrategy` (native `pcengine` plugin around
`libsteamkit_core.so`). The Kotlin surface of it:
- `jb0.java:209` — *"Steam exePath is blank; continue and let `PcEmulatorLaunchStrategy` decide whether real Steam
  client can launch via `SteamAgent`."*
- `SteamApi.canUseSteamClient(steamAppId)` / `getSteamGameLaunchContext(steamAppId)` / `getSteamLaunchFilePath(steamAppId)`
  (`eqa.java:29-35` failure-log strings) — the capability probe: if the genuine client can serve this app, the
  strategy sets `fakeSteamClient=false`.

> **Bannerlator equivalent:** a new per-appId `launchMode ∈ {GOLDBERG, REAL_STEAM}` (store it next to
> `SteamPrefs.getGoldbergMode(appId)`), read at the same point GameHub reads `fakeSteamClient` — i.e. in
> `XServerDisplayActivity.maybeSeedAndStartAchievementWatcher()` (:4478), which already gates on `isSteamShortcut`
> (:3993, `storeSource=steam`) and resolves the appId via `resolveSteamAppRefFrom` (:4180). Goldberg is the `true`
> leg today; Real-Steam is the new `false` leg.

---

## 2. Genuine-client staging — `steam_client_0403` into the prefix

The genuine Valve **Windows** client is delivered as a downloadable component, **not** extracted from a user PC and
**not** fetched from Valve at runtime. On-device `steam_client_0403/manifest.json`:

```json
{ "id": "steam_client", "name": "Steam Client", "version": "10520955",
  "metadata": { "component_kind": "steam_client",
                "install_root": "drive_c/Program Files (x86)/Steam",
                "steam_service_version": "10520955" },
  "readonly": [ "*.dll", "*.exe" ] }
```

- **Install target = `C:\Program Files (x86)\Steam`** inside the Wine prefix (`install_root`), extracted verbatim
  from `drive_c/` in the `.tzst`.
- **Root DLLs shipped** (confirmed `ls` of the bundle's `…/Steam/`): `steamclient64.dll`, `steamclient.dll`,
  `Steam.dll`, `tier0_s64.dll`, `vstdlib_s64.dll`, `GameOverlayRenderer64.dll`/`.dll`, `steamerrorreporter64.exe`;
  `bin/` adds `steamservice.exe`, `steamservice.dll`, `x64launcher.exe`, `steam_monitor.exe`, plus the usual
  `friendsui/gameoverlayui/chromehtml` set and `config/*.vdf` + `appcache/`.
- **No `steam.exe` at root.** The agent *is* the `steam.exe` replacement; the `SteamExe` registry value (§4) points
  at `SteamAgent.exe`, not a Valve `steam.exe`. (`SteamAgentData/.DS_Store` in the bundle confirms it's a
  macOS-origin repack of a genuine install.)
- Provenance: fetched from a components mirror as `<md5>.tzst` (~62 MB, md5 `08c498cef5c15d710d253681751068c1`) —
  i.e. GameHub **re-hosts Valve's copyrighted DLLs**. *This is the one piece a legal clone must source differently*
  (Valve CDN / user-supplied), see §10.

> **Bannerlator equivalent:** a `SteamClientComponent` mirroring `GoldbergComponent` (download-on-demand from the
> contents catalog), extracted into `imagefs/home/xuser-<id>/.wine/drive_c/Program Files (x86)/Steam`. The DLLs must
> be marked read-only (manifest `readonly`) so the game/agent can't corrupt them.

---

## 3. SteamLite agent bring-up — argv, env, token, interface

The runtime side is **`SteamAgent2` v1.0.3** (component; binary `SteamAgent2/SteamAgent.exe`), a headless
`steam.exe` in the C++ namespace `SteamAgent::SteamCore`. It loads the genuine client and drives it via Steam's
private `IClientEngine` family; it never shows UI.

### 3a. Interface version — **CLIENTENGINE_INTERFACE_VERSION005** (confirmed for V6)

- Agent (V5 family, `strings SteamAgent_unpacked.exe`): `CLIENTENGINE_INTERFACE_VERSION005`, plus
  `IClientEngine`/`IClientUser`/`IClientApps`/`IClientFriends`/`IClientRemoteStorage`.
- **V6 resolution (this closes the prior open question):** the bundled V6 `steamclient64.dll` (build 10520955)
  exports exactly **`CLIENTENGINE_INTERFACE_VERSION005`** and no other CLIENTENGINE version
  (`strings ".../steam_client_0403/.../Steam/steamclient64.dll" | grep CLIENTENGINE`). So the V6 SteamLite agent
  targets **v005**, same as V5 — Valve did **not** bump the interface for 10520955. A clone pins `…VERSION005`.

Client bring-up sequence (agent strings): `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `IClientEngine`
→ `CreateSteamPipe` + `ConnectToGlobalUser` (`Invalid HSteamUser or HSteamPipe` guard) → install & start
`steamservice.exe` (`Waiting for steamservice installation to complete [1/2]`, `Steamservice installation completed
[1/2]`) → write registry (§4) → login → `SteamCore::LaunchApplication`.

### 3b. The exact CLI contract (agent argv)

Full flag set present in the agent PE (`strings SteamAgent_unpacked.exe`):

```
--username   --password   --token   --rememberme
--applaunch  --launchoption  --launchparameters  --cellid  --language
--offline    --disablecloud  --runinstallscript
--version    --help
```

Mapped from `zd5` (§1): `--username`=`steamAccount(d)`, `--token`=`steamToken(e)`, `--applaunch`=`steamAppId(h)`,
`--launchoption`=`launchOption(n)`, `--cellid`=`cellId(o)`, `--language`=`language(p)`, `--offline`=`offlineMode(j)`,
`--disablecloud`= when `!cloudEnable(l)`, `--runinstallscript`=`installScript(m)`.

### 3c. ⭐ Token handoff (the #1 unknown — resolved)

**The user's own Steam refresh token is handed to the agent on its command line as `--token <refresh_token>`.**
- The token is **minted by `libsteamkit_core.so`** (GameHub's Rust CM client — its JavaSteam equivalent), not by the
  agent, and is carried in `zd5.steamToken` (field `e`).
- It is the **end user's own account** token (`--username <owner-account>` alongside), **not** a shared GameSir/dev
  credential. (A leftover dev token exists only in an unrelated `startSteam.bat` in a *different* client folder and
  is not part of this path.)
- No token file / no vdf handoff for login: it is a **process-argument**. (`--rememberme` lets the agent persist it
  into the genuine client's own `config.vdf` for offline re-use, but the initial handoff is argv.)
- Full 2FA/Guard error surface is present (`Account Logon Denied`, `Need Twofactor Code`, `Steam Guard has rejected
  the connection`, `Logon Session Replaced`), i.e. it performs a real CM logon with the user's token.

### 3d. Control socket + acceleration (bring-up plumbing)

- **Env `STEAMAGENT_PORT`** = an ephemeral loopback port chosen by the Android side. The agent connects **out** to an
  Android `ServerSocket` (`SteamAgentServer`) at `127.0.0.1:<STEAMAGENT_PORT>` and streams newline-JSON `SteamRPC`
  status events (`{type,event,appid,details,timestamp,username}`). Config flows to the agent by argv+env+parcel, not
  over the socket. (Full protocol: `GAMEHUB_STEAMAGENT_RE.md §3`.)
- **CM pre-warm** (`mvp.SteamAgentLaunchAcceleration`): before launch the Rust client writes `cmListPath` +
  `appcacheRoot`; Android passes them via `zd5` (`cmAccel/cmProxy/cmDatacenter/appcacheRoot/cmListPath/skipAppInfoRefresh`)
  so the in-Wine genuine client connects fast or runs offline off the session the Android client already established.

> **Bannerlator equivalent:** our own headless agent (clean C++/Rust), launched in the same prefix with the same
> argv/env shape; token = the user's refresh token minted by **our JavaSteam** (`in.dragonbra:javasteam`) and passed
> as `--token`; a Kotlin `ServerSocket` peer plays `SteamAgentServer` on an ephemeral `STEAMAGENT_PORT`.

---

## 4. Registry seed (`HKLM\Software\Valve\Steam`)

The **agent itself** creates the key and writes the values (confirmed agent strings
`Failed to create registry key HKLM\Software\Valve\Steam`, `Failed to set registry value <X>`):

| Value | Points at |
|---|---|
| `SteamExe` | the agent (`SteamAgent.exe`) — the headless steam.exe |
| `SteamPath` | `C:\Program Files (x86)\Steam` |
| `SteamClientDll` | `…\Steam\steamclient.dll` |
| `SteamClientDll64` | `…\Steam\steamclient64.dll` |
| `InstallPath` | `C:\Program Files (x86)\Steam` |
| `SteamPID` | the agent's PID |

The running genuine client maintains the standard `HKCU\…\Steam\ActiveProcess` (pid + `SteamClientDll`) itself — a
counterpart Bannerlator does not seed by hand.

> This registry write is what lets the **game's own** in-process `steam_api64.dll` find and attach to the same
> genuine client. It replaces nothing in the game; it points the game at the real client.

---

## 5. The game-process env block

**Confirmed set by the agent** (`Failed to set environment variable: <X>` strings): **`SteamPath`** and
**`ValvePlatformMutex`**. These plus the §4 registry are the binding surface for the game's `steam_api64.dll`.

The remaining Steamworks child-env (`SteamAppId`, `SteamGameId`, `SteamClientLaunch`) is **injected by the launcher
that spawns the game** — in GameHub that is the native `WinEmuModule` launch path (the Android side resolves the exe
and launches it: log `resolveSteamExePath(240) … executable=…/cstrike.exe, arguments=-steam, osArch=32`). These
names are **not** in the Kotlin layer (grepped: absent) and **not** in the agent binary — they live in GameHub's
native launcher `.so`, so treat them as *"set by whoever forks the game process,"* which for the clone is
Bannerlator's `GuestProgramLauncherComponent`.

**Launch argument:** the game exe is started with **`-steam`** (a genuine depot build; its Valve `steam_api64.dll`
attaches in-process to the running client).

**Must NOT set (the opposite of the Goldberg/Proton paths):**
- **No `WINESTEAMCLIENTPATH` / `WINESTEAMCLIENTPATH64`** — absent from every artifact. GameHub **disables Proton's
  `lsteamclient` shim** (`err:module:use_lsteamclient lsteamclient disabled`) so the game talks to the **real
  Windows `steamclient64.dll`**, not Proton's unix bridge. Setting `WINESTEAMCLIENTPATH` would re-route to the shim.
- **No `SteamNoOverlay`** unless overlay is intentionally suppressed (overlay is wanted here; it's enabled via the
  Vulkan implicit-layer registry — `GAMEHUB_STEAMAGENT_RE.md §6`).
- **No Goldberg/gbe_fork vars, no `SteamAppId` steering toward an emulator, no `steam_appid.txt` drop** — those are
  the Goldberg path.

> **Bannerlator equivalent:** in the Real-Steam leg, `envVars` (XServerDisplayActivity :285 /
> `GuestProgramLauncherComponent`) gains `SteamPath`, `ValvePlatformMutex`, `SteamAppId=<appid>`,
> `SteamGameId=<appid>`, `SteamClientLaunch=1`; the exe is launched with `-steam`; and the build must **clear**
> any `WINESTEAMCLIENTPATH*` and keep `lsteamclient` disabled (coordinate the Proton/DLL-override side with
> wine-compat-engineer).

---

## 6. "Without Goldberg" — the game keeps its OWN genuine `steam_api64.dll`

In the real path there is **no dll swap**. This is the exact inverse of `GoldbergPatcher.kt`:

| Step | Goldberg path (`fakeSteamClient=true`) | Real path (`fakeSteamClient=false`) |
|---|---|---|
| `steam_api64.dll` | **replaced** by gbe_fork shim (`applyRegular`/`applyExperimental`, GoldbergPatcher.kt:183/194) | **untouched** — the game's genuine Valve dll stays |
| `steamclient64.dll` beside game | sometimes dropped (experimental) | **none dropped beside game** — the genuine one lives in `C:\…\Steam` |
| loader | optional `steamclient_loader_x64.exe` (COLDCLIENT) | **none** — game exe launched directly with `-steam` |
| `steam_appid.txt` / `steam_settings/` | **written** (GoldbergPatcher.kt:353) | **not written** |
| session source | emulated locally, no network | genuine running client + real logged-in session |

So the Real-Steam leg **skips `GoldbergPatcher.applyModeBlocking` entirely** (and must ensure a prior Goldberg apply
is restored to pristine via `GoldbergPatcher.restore()` — otherwise a swapped shim dll would shadow the genuine one).

---

## 7. Lifecycle — ordered, with teardown tied to game exit

From the runtime-log `SteamRPC` event vocabulary (`GAMEHUB_STEAMAGENT_RE.md §3`) and the agent behavior:

```
stage        → extract steam_client_0403 into prefix C:\Program Files (x86)\Steam  (once per prefix)
              + restore any Goldberg swap to pristine
agent start  → env STEAMAGENT_PORT=<ephemeral>; SteamAgent.exe --username <acct> --token <refresh>
               --applaunch <appid> [--offline|--disablecloud|--language|--cellid|--launchoption …]
               (Android opens ServerSocket "SteamAgentServer" first)
bring-up     → RPC: init_start → init_success           (client+pipe up; steamservice installed; registry §4 written)
login        → RPC: login_start → login_success          (real CM logon with the user's token)
sync         → RPC: sync_apps_start → sync_apps_complete  (IClientApps ownership)
               → sync_cloud_start → sync_cloud_complete   (IClientRemoteStorage, if cloudEnable)
POLL READY   → Android blocks the launch until login_success (+ sync) before spawning the game
launch game  → WinEmuModule launches <game>.exe -steam  with env §5; game's steam_api64 attaches to the live session
run          → RPC: app_launch … overlay_* events        (game running under genuine session)
exit         → RPC: game_terminated → app_exit           (agent detects game exit)
teardown     → Android tears down SteamAgentServer + agent; fires cloud-upload intent on app_exit
```

**Teardown is tied to the game process:** the agent emits `game_terminated`/`app_exit` when the game exits, and the
Android side then closes the socket and stops the agent. (A failed launch emits `launch_failed` with an error code,
e.g. `details=3005`, instead of `app_launch`.)

---

## 8. Bannerlator target — L4D2 in the `com.tencent.ig` container

**Container:** `com.tencent.ig` (the pubg-flavor staging package) — a single shared `imagefs/` container.
Confirmed on-device:

- **Install (genuine depot):** `/data/data/com.tencent.ig/files/imagefs/steam_games/Left 4 Dead 2/left4dead2.exe`
  (365 056 B) + `left4dead2.ico`, with all three DLCs (`left4dead2_dlc1/2/3`), `bin/`, `hl2/`, `platform/`,
  `update/`, and a `.DepotDownloader/` marker. Siblings in the same container: CS:S (`240`), CS2, HL2 (`220`),
  Lossless Scaling.
- **Library DB row** (`/data/data/com.tencent.ig/databases/steam.db`, table `steam_games`):
  name `Left 4 Dead 2`, `install_dir=/data/user/0/com.tencent.ig/files/imagefs/steam_games/Left 4 Dead 2`,
  `icon_hash=7d5a243f…`, `included_dlc="Left 4 Dead 2 Add-on Support"`, `is_installed=1`.
- **AppId = `550`** (confirmed by the GSE store dir `/data/data/com.tencent.ig/files/steam_achievements/550`).
- **Shortcut config** (Winlator `Shortcut` extras, read in `XServerDisplayActivity`): `storeSource=steam`,
  `steamAppId=550`, exec path under `steam_games/Left 4 Dead 2/left4dead2.exe`. (Steam-store games launch from the
  store screen / `StarLaunchBridge`; the extras are the identity signals — there is no per-game `.desktop` on disk,
  only the component `.desktop`s in `files/desktops/`.)

**Where the Real-Steam branch slots in (worktree):**
- `XServerDisplayActivity.maybeSeedAndStartAchievementWatcher()` (:4478, called once from `setupXEnvironment` per
  :266) — already the Steam-launch hook: `isSteamShortcut` gate (:3986/:3993 `storeSource=steam`) → `resolveSteamAppRef`
  (:4155) → `resolveSteamAppRefFrom` (:4180). **This is GameHub's `fakeSteamClient` branch point.** Add: if
  `launchMode(appId)==REAL_STEAM`, run the §9 recipe instead of (and having restored) the Goldberg apply.
- `GoldbergPatcher.kt` — the sibling emulation path; Real-Steam is a new peer mode, and must call
  `GoldbergPatcher.restore(installDir, name)` first so no shim dll shadows the genuine `steam_api64.dll`.
- `GuestProgramLauncherComponent` + `envVars` (XServerDisplayActivity :285) — where the §5 env + `-steam` arg + the
  agent process get wired.
- `SteamPrefs.getGoldbergMode(appId)` — the natural home for the new per-appId `launchMode`.

---

## 9. The ordered recipe (fork this, 8 steps)

Each step: **GameHub source ref → Bannerlator hook.**

1. **Decide the mode.** GameHub: `PcEmulatorLaunchStrategy` sets `zd5.fakeSteamClient` via
   `SteamApi.canUseSteamClient(appId)` (jb0.java:209; eqa.java:29-35). → Bannerlator: read per-appId
   `launchMode==REAL_STEAM` in `maybeSeedAndStartAchievementWatcher()` (XServerDisplayActivity:4478).
2. **Un-Goldberg.** GameHub: the two paths are mutually exclusive (`fakeSteamClient` picks one). → Bannerlator:
   `GoldbergPatcher.restore(installDir, name)` so the game's genuine `steam_api64.dll` is pristine (GoldbergPatcher.kt:407).
3. **Stage the genuine client into the prefix.** GameHub: extract `steam_client_0403` to
   `C:\Program Files (x86)\Steam` (manifest `install_root`, readonly `*.dll/*.exe`). → Bannerlator: new
   `SteamClientComponent` (mirror `GoldbergComponent`) extracted into the container prefix's `…/Steam`.
4. **Seed registry.** GameHub: agent writes `HKLM\Software\Valve\Steam` `SteamExe/SteamPath/SteamClientDll/
   SteamClientDll64/InstallPath/SteamPID` (§4). → Bannerlator: our agent (or a one-shot regedit) writes the same.
5. **Start the agent with the user's token.** GameHub: `env STEAMAGENT_PORT=<ephemeral>`; `SteamAgent.exe --username
   <acct> --token <refresh> --applaunch <appid> [flags]`; installs+starts `steamservice.exe`; `CreateInterface(
   CLIENTENGINE_INTERFACE_VERSION005)` (§3). → Bannerlator: our clean agent, token minted by **our JavaSteam**,
   Kotlin `SteamAgentServer` on the ephemeral port.
6. **Block until logged in.** GameHub: Android waits for RPC `login_success` (+ `sync_apps_complete`) before spawning
   the game (§7). → Bannerlator: poll the socket; gate the game spawn on `login_success`. (This is the same
   session-ready guard as the download-reliability `ensureLoggedIn(timeout)` pattern.)
7. **Launch the game on its OWN `steam_api64.dll`.** GameHub: `WinEmuModule` launches `<game>.exe -steam` with env
   `SteamPath`+`ValvePlatformMutex` (+ native `SteamAppId/SteamGameId/SteamClientLaunch`); `lsteamclient` disabled;
   no dll swap (§5, §6). → Bannerlator: `GuestProgramLauncherComponent` launches `left4dead2.exe -steam` with those
   env vars; ensure `WINESTEAMCLIENTPATH*` is unset and lsteamclient off (coordinate with wine-compat-engineer).
8. **Tear down on game exit.** GameHub: RPC `game_terminated`→`app_exit` → close socket + stop agent + cloud-upload
   intent (§7). → Bannerlator: on game-process exit, stop the agent, close `SteamAgentServer`, trigger cloud sync.

---

## 10. "Fork this" list (concrete, with the legal line)

| # | Fork from GameHub | What to build (legal) |
|---|---|---|
| 1 | `SteamAgent2` headless agent | **Our own** clean C++/Rust headless `steam.exe`: `CreateInterface(CLIENTENGINE_INTERFACE_VERSION005)` → `IClientEngine`/`IClientUser::LogOn(--token)` → install `steamservice` → registry §4 → `LaunchApplication -steam`. Interface names + version string are facts, not GameSir IP. **User has the GameHub devs' permission to fork their SteamLite agent.** |
| 2 | argv/env contract (§3b, §5) | Copy verbatim: `--username/--token/--applaunch/--offline/--disablecloud/--language/--cellid/--launchoption`; env `STEAMAGENT_PORT`, `SteamPath`, `ValvePlatformMutex`. |
| 3 | control-socket shape (§3d) | Copy verbatim: ephemeral loopback, `SteamAgentServer`, newline-JSON `{type,event,appid,details,timestamp,username}`, `init/login/sync/app_launch/game_terminated/app_exit/launch_failed`. |
| 4 | `zd5.SteamGameInfo` contract (§1) | Our launch-mode `LaunchSpec` with the same fields; the switch = `launchMode`. |
| 5 | CM pre-warm (`mvp`, §3d) | Optional: pre-seed `cmListPath`+`appcacheRoot` from our JavaSteam session for fast/offline connect. |
| ⛔ | `steam_client_0403` (redistributed Valve **Windows** DLLs) | **DO NOT fork/bundle.** Source the genuine Windows `steamclient64.dll`/`steamservice.exe` at runtime from **Valve's CDN** or a user-supplied install. This is the single hard licensing line. |
| ⛔ | Any shared/dev token | N/A — use the **end user's own** refresh token (our JavaSteam mints it). |

**Ceiling (honesty):** even done cleanly this is **VAC-class only** — it satisfies server-side anti-cheat that
checks the genuine Steam session + game integrity. It cannot beat kernel/ring-0 anti-cheats (EAC/BattlEye kernel,
Vanguard). And note the evidence gap in `GAMEHUB_STEAMAGENT_RE.md §1`: GameHub itself has **not** been observed
sustaining a VAC-secured multiplayer session in any artifact here (launches were brief or `launch_failed 3005`).
Treat "join a real VAC server with real players and stay connected" as the milestone to prove on device
(Phase-0 smoke test with L4D2/550), not a solved problem inherited from GameHub.

**Domain reminder:** this addresses the **launch/emulation** failure domain (making a DRM/`steam_api` title *run*
against a genuine session) — not the *download* domain. Downloading L4D2 (already done here) is separate from running
it under real Steam.
