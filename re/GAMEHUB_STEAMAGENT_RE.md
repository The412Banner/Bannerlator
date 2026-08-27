# GameHub `SteamAgent.exe` + Steam Stack — Reverse-Engineering Report

**Target:** How GameHub (Xiaoji/GameSir `com.xiaoji.egggame`, and re-signed flavors) drives the **genuine Valve
Windows Steam client** so downloaded games reach real Steam services — and whether that actually passes VAC.
**Method:** READ-ONLY recon of the 6.2.1 decompile + on-device PE strings + a real runtime log. No redistribution.
**Author:** storefront/native Steam engineer · **Date:** 2026-08-27

### Artifacts cited (every load-bearing claim points at one)
- **Decompile:** `/home/claude-user/gamehub-6.2.1-jadx/sources/…`
  - `com/xiaoji/egggame/common/steam_sdk/bridge/SteamBridgeNativeInitializer.java`
  - `com/xiaoji/egggame/common/steam_sdk/bridge/a.java`
  - `defpackage/g0q.java` (JNA interface to `libsteamkit_core.so`)
  - `defpackage/zd5.java` (`SteamGameInfo` Parcelable — the launch contract)
  - `defpackage/mvp.java` (`SteamAgentLaunchAcceleration`), `jb0.java:209`
- **PE binary:** `bridge 'strings /sdcard/SteamAgent_unpacked.exe'` (MS PE32+ x86-64, the unpacked GameSir agent). Packed siblings `SteamAgent-{old,older,newer,60}.exe` exist but the `_unpacked` is the readable one.
- **Runtime log (ground truth for the wire protocol):** `/sdcard/steamagent_test.log` (267 MB logcat from a real GameHub session, 2026-06-06). Cited by timestamp.
- **Prior recon docs:** `/sdcard/STEAM_IMPLEMENTATION.md` (our JavaSteam port — the *legal* reference), `/sdcard/STEAMAGENT_LUDASHI_PLAN.md` (reuse plan), `/sdcard/steam.json`.
- The named `STEAMAGENT_ANALYSIS_2026-05-26.md` / `STEAM_PIPELINE_REPORT.md` in the task brief are absent/superseded on this device; the live artifacts above are richer and are what this report is built on.

> **PII note:** the runtime log carries the device owner's own Steam account (email `davidroethlein@***`, steamID `76561197963198101`). It is the user's own data; quoted values are redacted here.

---

## 1. BOTTOM LINE — does GameHub's method actually reach VAC?

**Verdict: VAC-CAPABLE by construction, but VAC-UNPROVEN in every artifact we have.** GameHub's method is the
architecturally strongest of the three approaches at satisfying VAC, because it runs **no Steam emulator at
runtime** — `SteamAgent.exe` is a headless replacement for `steam.exe` that loads and drives the **genuine Valve
`steamclient64.dll` + `steamservice.exe`** inside Wine and logs in a **real, user-owned Steam session**. That is
exactly the environment VAC expects: the only non-genuine layer is Wine/Proton translating PE calls (and box64/FEX
translating x86), not the Steam client itself.

What the artifacts **prove**:
- SteamAgent loads the genuine client: `steamclient64.dll`, `CLIENTENGINE_INTERFACE_VERSION005`, `IClientEngine`/`IClientUser`/`IClientApps`/`IClientFriends` are all in the PE (`strings /sdcard/SteamAgent_unpacked.exe`).
- It installs the genuine privileged service: `"Steamservice installation completed [1]/[2]"`, `steamservice.exe`, and sets registry `SteamClientDll64`/`SteamClientDll` (same strings dump).
- It logs in a real session: log `18:30:06 … event=login_success` after `login_start`, against real Valve CMs (the account is a real Steam account with real ownership; `sync_apps_complete` follows).
- The **game process actually launches** under it: `18:55:14 {"appid":291550,…"event":"app_launch"}` (Brawlhalla) and `18:56:43 {"appid":240,…"event":"app_launch"}` (Counter-Strike: Source — a VAC title).
- Proton's shim is **off**: `18:29:54 err:module:use_lsteamclient lsteamclient disabled` — the game talks to the **real Windows `steamclient64.dll`**, not Proton's `lsteamclient` bridge.

What the artifacts do **NOT** prove (be precise):
- **No VAC module ever observed.** `grep -i 'vac|secure|anti-cheat|valveanticheat|steamservice'` over the entire 267 MB runtime log returns **nothing**, and there is **no `VAC`/`secure` string in the PE**. VAC modules are downloaded/loaded by the genuine client at runtime and simply weren't reached in these captures.
- **No sustained secure-server session.** Every successful launch died in **5–9 seconds** (`app_launch 18:55:14 → game_terminated 18:55:19 → app_exit 18:55:22`; `app_launch 18:56:43 → game_terminated 18:56:52`). Most attempts didn't launch at all: repeated `{"appid":240,"details":"3005","event":"launch_failed"}` (error 3005). Nothing shows a player connected to a VAC-secured server with other humans.
- **SteamAgent mints no tickets itself.** No `GetAuthSessionTicket`/`BeginAuthSession`/`EncryptedAppTicket`/`AppOwnershipTicket` strings in the PE. It does **not** hand the game a ticket — it establishes the genuine logged-in session and lets the **game's own** in-process `steam_api64.dll → steamclient64.dll` do the ticketing against that live session, exactly as on a real PC.

**So:** the mechanism *stops at genuine auth + brief process launch* in the evidence. It does **not** "stop at ticket
auth" as an emulator would — it goes further (real client, real service, real process), which is why it's the best VAC
bet — but there is **no positive proof of VAC acceptance or secure-server multiplayer** on hand. Treat "passes VAC" as
a well-founded architectural expectation, not a demonstrated fact.

---

## 2. The `SteamAgent.exe` mechanism (CLIENTENGINE + login)

`SteamAgent.exe` is a proprietary GameSir C++ program — namespace `SteamAgent::SteamCore` (demangled symbol
`?LaunchApplication@SteamCore@SteamAgent@@`, `strings /sdcard/SteamAgent_unpacked.exe`). It is a **headless
`steam.exe`**: it drives the real Steam client through Steam's private `IClientEngine` family and reports status over
a socket; it never shows Steam UI.

**Startup / client bring-up** (all from the PE strings dump):
1. Loads `steamclient64.dll` and calls `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `IClientEngine`.
   Failure strings confirm the four it resolves: `Failed to get IClientEngine`, `Failed to get IClientUser`,
   `Failed to get IClientApps`, `Failed to get IClientFriends`. (`IClientRemoteStorage` is resolved too — see below.)
2. Creates a client pipe + global user: `Invalid HSteamUser or HSteamPipe`, `Shutting down SteamAgent: HSteamUser or
   HSteamPipe is invalid!` (the `CreateSteamPipe`/`ConnectToGlobalUser` handles).
3. Installs and starts the privileged **`steamservice.exe`** (the elevated service the real client needs for CEG/VAC):
   `Waiting for steamservice installation to complete [1]…`, `Steamservice installation completed [2]`,
   `Failed to start steamservice (attempt {})`, `Failed to install steamservice with CreateProcess/ShellExecute`.
4. Writes registry so the **game's own** `steam_api64.dll` finds the same client:
   `Failed to set registry value SteamClientDll64`, `…SteamClientDll` (i.e. it sets `HKCU\Software\Valve\Steam\SteamClientDll64` = the real `steamclient64.dll` path).

**Login / credentials (the key question):**
- The agent is a **CLI program** taking `--username`, `--password`, `--token`, `--launchoption`, `--offline`,
  `--runinstallscript` (PE strings). In practice it is driven **token-first**: the Android side hands it the
  **end user's own Steam refresh token** via `--token` (that token is minted by `libsteamkit_core` — §4 — and stored
  in `zd5.SteamGameInfo.steamToken`, §4). There is **no leaked GameSir dev JWT** and no hard-coded credential in the
  binary; the Ludashi-plan's "leaked JWT?" hypothesis is **not supported** — it logs in the *user's* real account.
- Full LogOn error surface present: `Account Logon Denied`, `…Need Twofactor Code`, `…Verified Email Required`,
  `Logon Session Replaced`, `Too Many Logon Attempts`, `Steam Guard has rejected the connection`.
- **Offline capable:** `--offline`, `Steam is running in offline mode`, `LogOnOffline Failed`, `Offline App Cache Is
  Invalid`, `User {} cannot logon offline` — it can run off the pre-seeded appcache without a live CM.
- **Steam Cloud** via `IClientRemoteStorage`: `sync_cloud_start`/`sync_cloud_complete`, `Waiting for Steam Cloud
  download/upload …`, `Resolve Steam Cloud conflict … AcceptLocalFiles`, `--disablecloud`.

**Launch:** `SteamAgent::SteamCore::LaunchApplication` starts the game (`App Running`, `IClientApps::GetAppData`). The
Android `SteamModule` resolves the exe first — log `18:29:37 resolveSteamExePath(240) … executable=…/Counter-Strike
Source/cstrike.exe, arguments=-steam, osArch=32` — i.e. the game is launched with `-steam`, and being a genuine depot
build it carries Valve's real `steam_api64.dll`, which attaches in-process to the running genuine client.

**Headless:** no window/UI strings; the only outward channel is the loopback status socket (§3). It reports lifecycle
and exits when the game exits (`app_exit`, then Android tears the server down).

---

## 3. The control-socket protocol (reconstructed from the wire)

**Transport:** loopback TCP, **dynamic port** chosen by the Android side and passed to the agent via the
`STEAMAGENT_PORT` environment variable (the literal `STEAMAGENT_PORT` and `127.0.0.1` are in the PE). In the runtime
log the port is a fresh ephemeral each launch — `SteamAgentServer started on port 34887` (18:29), `36301`, `46453`,
`37061` — **not** the fixed `47896` from `STEAMAGENT_LUDASHI_PLAN.md`; 47896 was that plan's own chosen constant, not
GameHub's. The Android peer is a `ServerSocket` named **`SteamAgentServer`**; the agent connects out to it
(`Client connected: /127.0.0.1:59494`).

**Framing:** newline-delimited JSON, one object per line. In every capture, traffic is **agent → Android** only
(status/event telemetry). The agent's *inputs* (which game, token, cloud on/off, join target) arrive out-of-band via
CLI args + env + the `zd5.SteamGameInfo` parcel — **not** over the socket. (An older exe exposed a request-direction
`fetch_steam_input_vdf` method per `STEAMAGENT_LUDASHI_PLAN.md:226`; it is **not** present in the 1.0.3 traffic.)

**Message shape** (verbatim from `/sdcard/steamagent_test.log`, 18:29–18:56):
```json
{"type":"status","event":"login_success","appid":0,"details":"","timestamp":"2026-06-06T18:30:06","username":"<acct-email>"}
{"type":"status","event":"launch_failed","appid":240,"details":"3005","timestamp":"…","username":"…"}
{"type":"event","event":"overlay_persona_state_change","appid":…,"details":"…","timestamp":"…","username":"…"}
```
- **Two `type` values:** `status` (lifecycle) and `event` (Steam overlay/friends). Fields: `type`, `event`, `appid`
  (int; 0 until a game is targeted), `details` (string; error code on failures, e.g. `"3005"`), `timestamp` (ISO-8601),
  `username` (the account email).
- **`type:"status"` events, in order:** `init_start → init_success` (client + pipe up) → `login_start →
  login_success` (genuine logon) → `sync_apps_start → sync_apps_complete` (IClientApps ownership sync) →
  `sync_cloud_start → sync_cloud_complete` (IClientRemoteStorage) → `app_launch` → `game_terminated` → `app_exit`;
  `launch_failed` (with `details`=error code) replaces `app_launch` on failure.
- **`type:"event"` events:** `overlay_persona_state_change`, `overlay_friend_rich_presence_update` — these feed the
  Android "join a friend's game" invite feature (`steam.action.OVERLAY_INVITE_IPC`; the join target flows *into* a
  subsequent launch as `zd5.joinLobbySteamId` / `zd5.connectString`, §4).
- **Android handling:** each line is dispatched as `SteamStatusCallback#N event=<name>, details=<…>`
  (`WinEmuModule … [EngineTrace] SteamStatusCallback#7 event=launch_failed, details=3005`). On `app_exit` for a Steam
  game the app fires a cloud-upload intent (`steam cloud exit upload intent sent gameId=240`).

**Two-line summary for reuse:** *Loopback newline-delimited JSON; agent connects out to an Android `SteamAgentServer`
on a per-launch ephemeral port (`STEAMAGENT_PORT` env). It streams `{type,event,appid,details,timestamp,username}`
status/overlay events — `init/login/sync_apps/sync_cloud/app_launch/game_terminated/app_exit/launch_failed`. The game
config (token, appid, cloud, join target) is delivered to the agent by CLI args + env + the `SteamGameInfo` parcel, not
over the socket.*

---

## 4. `libsteamkit_core.so` vs `SteamAgent.exe` — division of labor

They are **two independent Steam clients** with a one-way feed between them.

**`libsteamkit_core.so` = GameHub's own Rust CM client (the "store side"), runs on Android, no Wine.**
- Loaded in the main process (`SteamBridgeNativeInitializer.java:13` `System.loadLibrary("steamkit_core")`;
  `a.java:24` refuses to init off-main-process; `a.java:34` `initPlatformVerifier` sets up the Android TLS verifier).
- Exposed via **JNA** as interface `g0q extends com.sun.jna.Library` (`g0q.java`): a self-contained async engine —
  `steam_bridge_create(configJson)` → handle; `steam_bridge_submit(handle, command, payloadJson)` → async op;
  `steam_bridge_wait_response(handle, opId)`; `steam_bridge_next_event(handle)` / `close_events` (event stream);
  `steam_bridge_cancel`/`cancel_all_operations`; `steam_bridge_set_network_status(handle, online)`;
  `steam_bridge_free_string`; `steam_bridge_destroy`. Config is a JSON'd `tt2` of prefix paths + emulation type
  (`a.java:48-51`).
- It does the **entire store/CM side**: login + token mint, PICS/ownership, friends, cloud triggers, and the **depot
  download itself** — runtime log: `I/SteamKit … [steamkit_core::download_runtime] start_task BEGIN app_id=240
  mode=Install branch=public … selection=DownloadSelection{ platform:Windows, architecture:X64, language:english …}`
  → `onSteamDownloadCompleted(240): … steamapps/common/Counter-Strike Source`. **This is GameHub's JavaSteam
  equivalent, written in Rust.**

**`SteamAgent.exe` = the "runtime side," runs in Wine.** It does **not** download or browse; it makes the
already-downloaded game **run** with a genuine Steam session (§2). It is delivered as its own component **`SteamAgent2`
v1.0.3** (log `18:29:38 ensureGlobalSteamComponents append SteamAgent2-1.0.3`; md5 `44a86207563ca327ca74ea84293d8843`,
1,022,529 bytes) and unpacked to `files/usr/home/components/SteamAgent2`.

**The feed — `steamkit_core` pre-warms the in-Wine client:** before launch, the Rust client writes a CM list and an
appcache under `files/steam_data/steamkit/…`, and Android hands their paths to the agent:
- `18:29:46 SteamAgentLaunchRuntime: resolveAppCacheRoot: steamId=… resolved=…/steamkit/accounts/<steamId>/appcache`
- `18:29:48 WinEmuModule: setupSteamAgentAcceleration appId=240 steamId=… appcacheRoot=…/appcache
  cmListPath=…/steam_agent/cmlist.json cmAccel=true`
- Data class `mvp.java` = `SteamAgentLaunchAcceleration(appcacheRoot, cmListPath, cmDatacenter, cmServerCount)`.
- This lets the genuine in-Wine client connect fast / run offline off a session the Android client already established.

**The launch contract — `zd5.java` = `SteamGameInfo` Parcelable** (`toString`, `zd5.java:60-99`) carries everything the
agent needs, and is the single clearest map of the whole design:
`steamDir, steamLibraryDir, steamGameDir, steamAccount, steamToken, steamAgentPath, steamUserId, steamAppId,
silentMode, offlineMode, noVerifyFile, cloudEnable, installScript, launchOption, cellId, language, fakeSteamClient,
steamInputEnable, cmAccel, cmProxy, cmDatacenter, appcacheRoot, skipAppInfoRefresh, cmListPath, joinLobbySteamId,
connectString`.
- **`steamToken`** = the user's refresh token fed to the agent as `--token` (§2).
- **`fakeSteamClient`** = the switch between the **Goldberg emulation path** and the **genuine-client SteamAgent path**
  (see §5). `jb0.java:209`: *"Steam exePath is blank; continue and let PcEmulatorLaunchStrategy decide whether real
  Steam client can launch via SteamAgent."*
- **`joinLobbySteamId` / `connectString`** = the friend-join / server-connect target that turns an overlay invite into
  a launch.

Net: **`steamkit_core` (Rust, Android) owns login+library+download; `SteamAgent.exe` (C++, Wine) owns run-with-real-Steam.
`steamkit_core` mints the token and pre-seeds the CM list/appcache that `SteamAgent.exe` consumes.**

---

## 5. How the genuine Valve DLLs get there

**They are bundled/redistributed as a component — the concrete copyright problem.** Runtime log, 18:29:40:
```
checkAndDownload … COMPONENT:steam_client_0403@1.0.0 … 未下载，需要下载   (not downloaded, needs download)
Starting download: … name=steam_client_0403 …
  url=https://github.com/The412Banner/bannerhub-api/releases/download/Components/08c498cef5c15d710d253681751068c1.tzst
  componentType=8, version=1.0.0, fileSize=64897035, fileMd5=08c498cef5c15d710d253681751068c1
extractComponent - ComponentStrategy(steam_client_0403) … success=true
```
- **`steam_client_0403`** (~62 MB, `.tzst`, md5 `08c498cef5c15d710d253681751068c1`) is the **genuine Valve Windows Steam
  client package** — it is what supplies `steamclient64.dll`, `steamservice.exe`/`steamservice.dll`, `tier0_s64.dll`,
  `vstdlib_s64.dll`, etc. that `SteamAgent.exe` then loads and installs (§2). GameHub does **not** extract it from a
  user's PC and does **not** fetch it from Valve at runtime — it **re-hosts Valve's copyrighted DLLs** on a components
  mirror and downloads from there. (The mirror here is `The412Banner/bannerhub-api` — i.e. this device is already
  re-hosting them; that is precisely the redistribution GameHub does and the thing a legal clone must not copy.)
- **Both paths are staged side by side:** log `18:29:38 ensureGlobalSteamComponents append goldberg-1.2 … append
  SteamAgent2-1.0.3`. **`goldberg-1.2`** is the Goldberg/GSE emulator for the `fakeSteamClient=true` path;
  `SteamAgent2` + `steam_client_0403` are the `fakeSteamClient=false` **genuine-client** path. The `fakeSteamClient`
  flag in `zd5` (§4) chooses at launch.
- The privileged service is installed **by the agent itself** from that package into the prefix, plus the
  `SteamClientDll64` registry write (§2).

---

## 6. Why it can't ship as-is, and the legal clean-room equivalent

**Three hard blockers, each mapped to a fix:**

| Blocker (what GameHub does) | Why we can't copy it | Legal replacement (keep the *shape*) |
|---|---|---|
| Proprietary DRM-packed **`SteamAgent.exe`** (GameSir binary) | Not our code; can't ship/modify | **Write our own** headless agent (clean C++/Rust). The *interface names & version strings* it uses (`CLIENTENGINE_INTERFACE_VERSION005`, `IClientEngine`/`IClientUser`/`IClientApps`) are facts, not GameSir IP — reimplementing a headless `steam.exe` that calls the real client the way Valve's own `steam.exe` does is clean-room-able. |
| **`steam_client_0403`** = redistributed Valve **Windows** DLLs | Redistributing Valve's copyrighted `steamclient64.dll`/`steamservice.exe` is the licensing violation | **Never bundle them.** Source them at runtime on-device: (a) fetch the Steam Windows client depot from **Valve's own CDN** on first Steam launch, or (b) have the user point at an installed Steam. The genuine-VAC path specifically needs the real **Windows** `steamclient64.dll`, so this must be Valve-sourced, never baked into our APK or Proton. |
| Any **leaked token** | N/A here — GameHub already uses the *user's own* `--token` | **Use the end user's own Steam refresh token**, minted by our own CM client (below). No shared/dev credential. |

**Our clean-room stack (already 80% in hand):**
1. **Store/CM side → our JavaSteam** (`in.dragonbra.javasteam`), documented in `/sdcard/STEAM_IMPLEMENTATION.md`
   (login incl. QR, PICS, `DepotDownloader`, cloud). This is our legal `libsteamkit_core` — it logs the user in and
   **mints their real refresh token** and pre-seeds a CM list / appcache. Our memory's Phase-0 note is right: JavaSteam
   can already mint the real session; that's the cheap on-device VAC-server smoke test.
2. **Runtime side → our headless agent**, launched *alongside* the game in the same Wine prefix, that:
   `LoadLibrary(steamclient64.dll)` → `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `IClientEngine` →
   `IClientUser::LogOn` **with the user's token** → install `steamservice.exe` → set `SteamClientDll64` registry →
   `LaunchApp/RunApp` the game with `-steam` → stream lifecycle JSON on a loopback socket to an Android
   `SteamAgentServer`.
3. **Control socket → copy GameHub's shape verbatim:** ephemeral loopback port via `STEAMAGENT_PORT` env; newline JSON;
   `{type,event,appid,details,timestamp,username}` with the `init/login/sync/app_launch/app_exit/launch_failed` +
   overlay vocabulary from §3. Config to the agent via CLI/env/parcel, not the socket.
4. **DLLs → user-supplied / Valve-CDN only** (blocker #2). With WineHQ/our own Wine we control env and can drop the
   Valve DLLs and disable `lsteamclient` (`use_lsteamclient` off) so the game binds the real PE client.

**The single most important thing to replicate:** a **headless `steam.exe` replacement that
`CreateInterface(CLIENTENGINE_INTERFACE_VERSION005)`s the *genuine* Windows `steamclient64.dll`, logs the user's real
session in, installs `steamservice`, and sets `SteamClientDll64` so the game's own in-process `steam_api64.dll`
attaches to that same genuine session.** That genuine client — not any emulator — is what downloads and loads the real
VAC modules, and it is the one piece of GameHub's method that makes VAC even conceivable. Everything else (the socket,
the Rust store client, the overlay events) is replaceable scaffolding.

**Ceiling / honesty:** even done cleanly this is **VAC-class only** (server-side anti-cheat that checks the Steam
session + game integrity). It cannot beat kernel/ring-0 anti-cheats (EAC/BattlEye kernel, Vanguard). And note the
evidence gap from §1: GameHub itself has **not** been shown to sustain a VAC-secured multiplayer session in any
artifact here — launches were brief or failed (`3005`). A legal clone should treat "join a real VAC server with real
players and stay connected" as the **unverified milestone to prove on device**, not as a solved problem inherited from
GameHub.
