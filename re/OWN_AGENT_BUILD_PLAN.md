# Bannerlator's OWN Clean-Room Headless Steam Agent — BUILD PLAN (Option B)

**Status:** design / scoping — grounded in real code, **not yet built.**
**Branch:** `feat/steam-vac-phase0` · **Author:** storefront / native-Steam engineer · **Date:** 2026-08-27
**Decision:** Option B is chosen — build **our own** headless in-Wine Steam-client driver ("SteamLite"), a
clean transliteration of WinNative's GPL `wn-steam-launcher`, instead of forking GameHub's DRM-packed
`SteamAgent.exe`. This doc is the concrete build plan for that agent and its app-side wiring.

> **PII / secrets:** the device owner's Steam email, SteamID64, and refresh token live only in on-device
> artifacts (`steam_prefs.xml`, GameHub logs). They are the user's own data and are **never** quoted, logged,
> or committed here. The agent must keep the token out of any log line and out of the process command line
> (see §2).

> **Failure-domain note (keep straight):** this plan is entirely in the **emulation-launch** domain — making a
> genuine-`steam_api` title *run* against a real logged-in session. It is not the *download* domain (L4D2 is
> already downloaded) and it does not use Goldberg (that's the offline *emulation* fallback). Three separate
> domains; this is only the launch one.

---

## 0. Recommendation up front (the two questions the brief asks)

### 0.1 Sub-approach: **(a) our own C++ `IClientEngine`-driver launcher — CHOSEN.** Not (b) real `steam.exe`+VDFs.

**Build our own headless launcher, transliterated from WinNative's GPL `wn-steam-launcher/src/main.cpp`.**
The deciding reasons (full comparison in §5):

1. **Weight/perf on device is the whole point.** Real Valve `steam.exe` drags the CEF/Chromium UI, the web
   helper, the store, and the auto-updater — each an extra **x86_64 binary under FEX translation on ARM**,
   i.e. more to JIT, more RAM, more crash surface, on a phone. GameHub *already built a lightweight agent
   ("SteamLite") for exactly this reason* and it is what cleared VAC on this device. The CEF weight buys us
   nothing for VAC and costs a lot everywhere else.
2. **Real `steam.exe` self-updates on launch.** It reaches Valve's client-depot CDN and **rewrites the very
   DLLs we staged**, then often relaunches itself — an uncontrolled moving target that historically breaks
   under Wine and would fight our runtime-sourcing (§4/§7). A headless driver loads the genuine
   `steamclient64.dll` and does *nothing else*.
3. **We have a working GPL reference.** WinNative's `main.cpp` is a proven headless driver that does
   `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `SetLoginToken` + `LogOn` on the private vtable and
   reaches `SteamServersConnected`. Transliteration is bounded (one ~1,560-line file + a teardown unit), and
   the vtable layout is public (OpenSteamworks). Writing from a blank file is not required.
4. **Determinism / robustness.** The refresh-token → `SetLoginToken`/`LogOn` path is the exact path both
   GameHub and WinNative use and it is deterministic. Real `steam.exe`'s auto-login via `loginusers.vdf` is
   fragile: Steam Guard/2FA web prompts, "please verify this device," forced-update loops, and version-skew
   between our staged DLLs and steam.exe's expectations.
5. **No licensing saving.** Real `steam.exe` still needs the same genuine Valve DLLs sourced at runtime (§7),
   so it does not avoid the one hard licensing line — it only *adds* the CEF/updater weight on top.

**(b) is kept only as a documented diagnostic fallback:** if our agent ever cannot reach a client state that
real Steam reaches, dropping the real `steam.exe` + Mode-3 auto-login VDFs into the same prefix is a one-off
A/B to isolate whether the gap is *our driver* or *the genuine client on ARM*. It is not a ship path.

### 0.2 First buildable milestone (detail in §8)

**M0 — "headless genuine-client login, by hand, in `xuser-3`, with OUR binary."** Build the login-only slice
of the agent (load `steamclient64.dll` → v005 → `CreateGlobalUser` → `SetLoginToken` → `LogOn` → poll
`BLoggedOn`, write a log, exit — **no game, no socket, no `LaunchApp`**), cross-compile it with MinGW-w64,
stage it as `C:\Program Files (x86)\Steam\steam.exe` in the already-prepared `xuser-3` prefix, and run it under
the container's Proton 11.0-2-arm64ec wine with `PROTON_DISABLE_LSTEAMCLIENT=1` and the owner's token passed by
**env**. **PASS = the agent's own log shows `CLIENTENGINE_INTERFACE_VERSION005 -> non-null`, a valid
pipe/user, `SetLoginToken -> 1`, and callback `101 SteamServersConnected` / `Steam_BLoggedOn=true`.** That
proves *our* code (not GameHub's binary) can drive the genuine client headless on our stack, with zero app
code, before we invest in game-attach or UI.

### 0.3 The 3 load-bearing WinNative files to transliterate

1. `wn-steam-launcher/src/main.cpp` — the driver model (login sequence, the v005 vtable offsets, registry seed,
   env block, `steamservice` install, app-manifest staging).
2. `wn-steam-launcher/clean_shutdown.cpp` + `clean_shutdown.h` — teardown/reap (`Steam_LogOff`, release
   user/pipe, sentinel-file trigger, the games-played reap that prevents `AlreadyRunning 0x10` next launch).
3. `wn-steam-launcher/build.sh` — the MinGW-w64 cross-compile recipe (toolchain, flags, static link,
   `--subsystem,windows`).

Plus one **app-side** pair to mirror (not native): `WnSteamAssetsInstaller.kt`
(`installPlanWLauncher` / `installPlanWSteamService`) for prefix staging, and the env-gate at
`XServerDisplayActivity.java:7316-7328` (`PROTON_DISABLE_LSTEAMCLIENT=1` + `WINEDLLOVERRIDES … ;lsteamclient=`).

### 0.4 Doc path

`/home/claude-user/bl-wt-steam-vac/re/OWN_AGENT_BUILD_PLAN.md` (this file).

---

## 1. The agent itself — "bl-steam-agent" (our SteamLite)

**Language:** C++17, compiled to a **Windows x86_64 PE** (see §4). Named **`steam.exe`** when staged — Valve's
`steamclient64.dll` `CGameLauncher` path requires its host process to *look like* real Steam
(WinNative `build.sh` header note). Single translation unit + a teardown unit; no external runtime deps
(static-linked).

**Coupled vs decoupled — we choose DECOUPLED.** WinNative's `main.cpp` is *coupled*: one process logs in AND
launches the game via `IClientAppManager::LaunchApp` AND watches it for exit (`main.cpp:1281-1554`). Bannerlator
already owns the game process through `GuestProgramLauncherComponent` (winhandler, exit detection, env). Having
the agent *also* launch and watch the game duplicates and fights that. So our agent's job is narrower and it
**stays resident as the client** while Bannerlator launches the game separately — which is also GameHub's actual
shape (its native `WinEmuModule` launches the game; the agent only reports `login_success` and Android gates the
spawn). Concretely we **transliterate `main.cpp` up to and including the login/service/registry stages, then
replace its game-launch/watch tail with "signal ready → park → teardown-on-sentinel."**

### 1.1 Step-by-step, each mapped to `wn-steam-launcher/src/main.cpp`

| # | Step | What our agent does | WinNative `main.cpp` ref | Keep / change |
|---|---|---|---|---|
| 1 | **Read inputs** | Token/username/steamID/appId from **env** (`BL_STEAM_TOKEN` / `_USERNAME` / `_STEAMID` / `_APPID`); no game exe (decoupled) | `:896-921` (`getenv WN_STEAM_*`, `argv[1]` game exe) | Keep env read; **drop** `argv[1]` game-exe |
| 2 | **Set client env + cwd** | `SteamPath`, `SteamGameId`, `SteamAppId`, `SteamUser`, `Steam3Master=127.0.0.1:27036`, `SteamClientLaunch=1`; `SetDllDirectory`/`SetCurrentDirectory` = `C:\Program Files (x86)\Steam` | `:923-932` | Keep verbatim |
| 3 | **Stage empty config** | Create `Steam\config\{config,local}.vdf` if absent (client expects them) | `stage_steam_config` `:214-232`, called `:937` | Keep |
| 4 | **Seed registry** | `HKCU\…\Steam\ActiveProcess` (`SteamClientDll`, `SteamClientDll64`, `Universe=1`, `pid`, `ActiveUser`); `HKCU`+`HKLM\…\Valve\Steam` (`SteamPath`, `SteamExe`, `InstallPath`); `…\Apps\<appid>\{Installed=1,Running=1}` | `seed_active_process_registry` `:146-212`, called `:938` | Keep; add `SteamClientDll64`→`HKLM` too (STEAM_WINE_LAYER_GAP §4/§5-#3) |
| 5 | **Stage app-manifest** | Write `steamapps\appmanifest_<appid>.acf` (`StateFlags=4` = installed) so the client/`steam_api` sees the app owned+installed | `stage_app_manifest` `:252-428`, called `:939` | Keep (feeds ownership/GetAppInstallState) |
| 6 | **Preload deps + load genuine client** | `LoadLibraryEx` `tier0_s64`/`vstdlib_s64`/`steamservice.dll`, then `steamclient64.dll` with the multi-strategy loader | preload `:941-956`; load `:964-1031` | Keep the whole retry ladder (Wine cold-load is flaky) |
| 7 | **Resolve flat exports** | `GetProcAddress`: `CreateInterface`, `Steam_CreateGlobalUser`, `Steam_BLoggedOn`, `Steam_BGetCallback`, `Steam_FreeLastCallback`, `Breakpad_SteamSetAppID` | `:1033-1049` | Keep |
| 8 | **`CreateInterface(v005)` → IClientEngine** | `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` (v004 fallback) | `:1062-1069` | Keep; **pin v005** (confirmed export of build 10520955) |
| 9 | **`Steam_CreateGlobalUser`** | Get `pipe` + `hUser` handles | `:1075-1082` | Keep |
| 10 | **IClientUser** | `engine_vt[GetIClientUser]("CLIENTUSER_INTERFACE_VERSION001")` | `:1084-1090` | Keep |
| 11 | **`SetLoginToken(token, account)`** | Private-vtable call, slot 56 (offset `0x1C0`) | `:1100-1107` | Keep — **the load-bearing call** |
| 12 | **`LogOn(steamID)`** | Private-vtable call, slot 1 (offset `0x08`); `GetSteamID` first, env-fallback | `:1109-1136` | Keep |
| 13 | **Poll `Steam_BLoggedOn` + drain callbacks** | Up to 60 s; watch cb `101 SteamServersConnected` / `102 ConnectFailure` / `103 Disconnected`; on logon → **arm clean-shutdown** | `:1143-1199` (arm at `:1180`) | Keep. **This is the readiness gate.** |
| 14 | **App-info + ownership prime** | `IClientApps::RequestAppInfoUpdate(appId)`; `IClientAppManager::RefreshAppInfo` + `GetAppInstallState` until `FullyInstalled` | `:1201-1279` | Keep (so the game's `steam_api` finds the app ready) |
| 15 | **Install + start `steamservice`** | `CreateServiceA "Steam Client Service"` → `steamservice.exe /RunAsService`; wait RUNNING | `start_steam_client_service` `:581-662`, called `:1283` | Keep — the privileged service VAC/CEG needs |
| 16 | **★ Signal READY (our change)** | Write sentinel `C:\bl-steam-agent.ready` after step 13's `login_success` (+ step 14). Bannerlator polls it before spawning the game | — (WinNative logs to `C:\wn-launcher.log`, tailed by `WnLauncherStatusTailer`) | **New, replaces coupled launch.** §2 for why sentinel not socket |
| 17 | **★ Park resident** | Loop: pump callbacks (`Steam_BGetCallback`/`FreeLastCallback`) to keep the session alive; poll for the teardown sentinel `C:\bl-steam-agent.shutdown` | replaces `main.cpp:1519-1554` game-watch | **New.** Agent is now "the client," not the launcher |
| 18 | **Teardown on sentinel** | On `…​.shutdown` (written by Bannerlator when the game exits): clean-shutdown = exit-cloud-sync → `Steam_LogOff` → release user/pipe → **reap games-played** so next launch isn't `AlreadyRunning 0x10` | `clean_shutdown.cpp` (`wn_launcher_clean_shutdown_now` / `wait_clean_shutdown`, invoked `main.cpp:1546-1551`) | Keep the teardown unit; trigger it from the sentinel instead of game-exit |

**Dropped from WinNative (decoupled ⇒ Bannerlator owns the game):** `IClientAppManager::LaunchApp` +
`GetIClientUtils` result-polling + `CreateProcess` fallback + `count_game_processes` game-watch
(`main.cpp:1301-1554`), and `scan_and_install_redists` (`:831-885,:1281` — Bannerlator already has its own
redist/VC++ handling). These are ~500 lines we do not transliterate.

**The v005 vtable offsets to carry over verbatim** (`main.cpp:35-57`; these are the OpenSteamworks-documented
layout WinNative hard-codes as byte-offset ÷ 8 into `void**`):
`IClientEngine`: `GetIClientUser 0x40`, `GetIClientApps 0x88`, `GetIClientUtils 0x70`,
`GetIClientAppManager 0x158`. `IClientUser`: `LogOn 0x08`, `BLoggedOn 0x20`, `GetSteamID 0x50`,
`BHasCachedCredentials 0x188`, `SetLoginToken 0x1C0`. `IClientApps`: `RequestAppInfoUpdate 0x38`.

---

## 2. Token hand-off (JavaSteam → in-Wine agent)

**Recommendation: pass the refresh token (and username/steamID/appId) by ENVIRONMENT VARIABLE, not CLI arg.**
This is what WinNative does (`WN_STEAM_TOKEN` etc., `main.cpp:896-899`); adopt the same as `BL_STEAM_*`.

Why env over the CLI `--token` that GameHub uses:
- **Secret hygiene.** A process's command line is world-readable via `/proc/<pid>/cmdline` and shows up in
  `ps` / the wineserver process list; its environment is only readable by the same uid. The refresh token is a
  live credential — keep it off the command line. (Bannerlator's own leak-audit rule already flags secrets in
  logs; this extends it to argv.)
- **Simplicity.** Bannerlator already assembles a launch env (`XServerDisplayActivity.envVars` :285,
  consumed by `GuestProgramLauncherComponent`). Adding four `BL_STEAM_*` keys is a couple of `envVars.put(...)`
  calls in the `RealSteam` branch — no file to write, parse, or clean up.
- **Robustness.** No file to leak on disk, no parse/escape edge cases (tokens are JWT-ish base64url; fine in
  env, but a stray quote in a VDF/CLI is a footgun).

**Source of the token:** our existing JavaSteam / `SteamRepository` already mints the user's own refresh token
(the same one it uses for CM logon). Read it there, put it in the child env for the agent process only.
**Never** persist it into a VDF for `--rememberme`-style reuse in Phase 1/2 (that is a second copy to secure);
if offline re-use is wanted later, let the genuine client's own `config.vdf` hold it (the client writes that
itself once logged in).

**Readiness / teardown IPC = sentinel files, not a socket (for Phase 1-2).** GameHub uses a TCP
`SteamAgentServer` on `STEAMAGENT_PORT`; that is real work (a Kotlin `ServerSocket`, JSON framing, lifecycle).
For the first shippable version, reuse WinNative's proven **sentinel-file** mechanism instead:
- Agent writes `C:\bl-steam-agent.ready` after `login_success` (§1 step 16); Bannerlator polls the prefix path
  for it before spawning the game (this is the same "block until logged in" gate as the download-reliability
  `ensureLoggedIn(timeout)` pattern).
- Bannerlator writes `C:\bl-steam-agent.shutdown` on game exit; the agent polls for it and tears down (§1 step
  18) — exactly WinNative's `C:\wn-launcher.shutdown` design.
- A richer newline-JSON control socket (GameHub's shape) is a **Phase 3** productionization if we want live
  overlay/friends/`launch_failed` telemetry; not needed to ship the launch.

---

## 3. The CM list — needed, or acceleration?

**Our own agent does NOT need `cmlist.json` for correctness — it is a pure acceleration.** The genuine
`steamclient64.dll` discovers Connection Managers on its own (its built-in bootstrap + `ISteamDirectory/GetCMList`
web fetch). **WinNative proves this:** `wn-steam-launcher` passes **no** CM list anywhere and still reaches
callback `101 SteamServersConnected` (`main.cpp:1156-1158`) purely from `SetLoginToken`+`LogOn`. GameHub's
`--cmlist`/`--cmaccel` + a pre-seeded `cmlist.json` exist only to (a) skip the CM-discovery round-trip and (b)
reuse the CM session its Android-side Rust client already warmed — latency, not capability.

**Plan:**
- **Phase 1/2: skip it.** The agent logs in with just the token; the client finds CMs itself. One fewer moving
  part for the milestone that proves viability.
- **Phase 3 (optional pre-warm):** if cold-connect latency is poor on device, have **our JavaSteam** mint the
  list from `ISteamDirectory/GetCMList` (it already talks to that surface) and write a `cmlist.json`
  alongside; feed it to the agent via a `BL_STEAM_CMLIST` env path. This mirrors GameHub's `mvp` acceleration
  and WinNative/GN's ownership-cache pre-seeding, but is strictly opt-in.

---

## 4. Build toolchain — x86_64 PE via MinGW-w64, run in-Wine under arm64ec+FEX

**Compiler:** MinGW-w64, **POSIX-threads** variant (the teardown unit uses `std::thread`), exactly
WinNative's `build.sh`:
```
x86_64-w64-mingw32-g++-posix -std=c++17 -O2 -static -static-libgcc -static-libstdc++ \
    -Wl,--subsystem,windows -o bl-steam-agent.exe src/main.cpp clean_shutdown.cpp \
    -ladvapi32 -lkernel32 -luser32
x86_64-w64-mingw32-strip bl-steam-agent.exe
```
- **Why x86_64 PE (not arm64ec-native):** it hosts Valve's x86_64 `steamclient64.dll`; the whole stack runs as
  x86_64 PE translated by **FEXCore / `libwow64fex.dll`** under our Proton 11.0-2-**arm64ec**. This is
  **confirmed working** — GameHub's `SteamAgent.exe` is a PE32+ x86_64 and ran on this device; our staged prefix
  uses `HODLL=libwow64fex.dll`.
- **Why `--subsystem,windows` + static runtime:** no console (avoids a transient console X11 window racing the
  X server), and no MinGW DLLs dragged into the prefix.
- **Where it slots in the repo:** a new native module `app/src/main/cpp/bl-steam-agent/`
  (mirror `wn-steam-launcher/`), with its own `build.sh`. **Gradle does NOT compile it** — like WinNative, the
  Android build only *packages* the prebuilt PE. A dedicated **CI job** (GitHub Actions, `The412Banner/Bannerlator`)
  runs `build.sh` on a MinGW image and publishes the artifact.
- **Packaging + staging:**
  - The **agent PE is ours** → bundle it in the APK at `app/src/main/assets/steam/bl-steam-agent.exe`
    (~1 MB), staged into the prefix at launch as `…/Steam/steam.exe` by a new `SteamClientStager.kt`
    (mirror `WnSteamAssetsInstaller.installPlanWLauncher` `:391-448`). Bundling *our* code is fine.
  - The **genuine Valve DLLs are NOT ours** → sourced at runtime, never bundled (§7). `SteamClientStager` places
    them into `…/Steam` + `…/Steam/bin` (mirror `installPlanWValveSteam` `:307-389` / `installPlanWSteamService`
    `:136-198`), read-only.
- **CI build order:** MinGW agent job → artifact → consumed by the APK packaging job. Reproducible, no local
  builds (repo rule: push + CI).

---

## 5. Evaluate the alternative — (a) own C++ driver vs (b) real `steam.exe` + Mode-3 VDFs

| Axis | (a) Own C++ `IClientEngine` driver *(WinNative model)* | (b) Real Valve `steam.exe` + auto-login VDFs *(GN Mode-3 shape)* |
|---|---|---|
| **Custom native code** | ~1,000 lines transliterated from GPL `main.cpp` + teardown | **Zero** custom native code |
| **On-device weight** | **Tiny** — one headless PE, loads only `steamclient64.dll`+`steamservice` | **Heavy** — CEF/Chromium UI, web helper, store, updater, all x86_64 under FEX |
| **Perf/RAM on a phone** | Minimal | Large (CEF renderer + JIT of every extra binary) |
| **Self-update behavior** | None — loads the staged client and stops | **Reaches Valve CDN, rewrites our staged DLLs, may relaunch** — uncontrolled, breaks under Wine |
| **Login determinism** | Deterministic `SetLoginToken`+`LogOn` (token → session) | Fragile `loginusers.vdf`/`config.vdf` auto-login: Guard/2FA prompts, "verify device," version skew |
| **Licensing** | Same genuine DLLs sourced at runtime (§7) | **Same** genuine DLLs *plus* real `steam.exe` sourced at runtime — no saving |
| **VAC plausibility** | Proven shape (GameHub SteamLite = same idea, cleared VAC-auth on this device) | Marginally "more genuine," but VAC is auth+integrity of the *game/session*, which (a) already satisfies |
| **Shippability** | High — bounded, controllable, headless | Low — this is exactly the weight GameHub built SteamLite to *avoid* |
| **Effort to first-working** | Moderate (compile + transliterate + wire) | Low to *stand up*, high to *stabilize* (updater/Guard/version fights) |

**Verdict: (a).** The only thing (b) wins on is "no custom code," and that saving is illusory because (b)'s
runtime — CEF + auto-updater + Guard flows under FEX on ARM — is precisely the fragile, heavy surface a phone
can least afford and that GameHub deliberately engineered away. (a) is a bounded transliteration of a *working
GPL reference* that yields a controllable, headless, deterministic client. **Keep (b) only as a one-off A/B
diagnostic** (§0.1) to attribute a failure to "our driver" vs "the genuine client on ARM."

---

## 6. App-side integration (worktree `/home/claude-user/bl-wt-steam-vac`)

All paths below are real and confirmed in the worktree.

**6.1 The launch-mode switch.** Add a per-appId `launchMode ∈ {GOLDBERG, REAL_STEAM, RAW}` stored next to the
existing Goldberg mode in `app/src/main/java/com/winlator/star/store/SteamPrefs.kt` (mirror
`getGoldbergMode(appId)` :85 / `setGoldbergMode` :89, key prefix `goldberg_mode_` :82 → add
`realsteam_launchmode_`). This is GameHub's `zd5.fakeSteamClient` boolean generalized.

**6.2 The branch point.** `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` —
`maybeSeedAndStartAchievementWatcher()` (the Steam-launch hook called once from `setupXEnvironment`, see the
comment at :266; it gates on `isSteamShortcut` / `storeSource=steam` ~:3986 and resolves the appId). Add: if
`launchMode(appId) == REAL_STEAM`, run the RealSteam orchestration **instead of** the Goldberg apply.

**6.3 Un-Goldberg first.** Call `GoldbergPatcher.restore(installDir, gameName)`
(`app/src/main/java/com/winlator/star/store/GoldbergPatcher.kt:407`) so no gbe_fork shim shadows the game's
genuine `steam_api64.dll`. RealSteam and Goldberg (`applyModeBlocking` :140) are mutually exclusive.

**6.4 Stage the client + agent.** New `SteamClientStager.kt` (mirror `WnSteamAssetsInstaller.kt`
`installPlanWLauncher` :391 / `installPlanWSteamService` :136 / `installPlanWValveSteam` :307): stage OUR
`bl-steam-agent.exe` as `…/Steam/steam.exe`, plus the runtime-sourced genuine DLLs into `…/Steam` + `…/Steam/bin`.

**6.5 The env block + the env gate.** In the `RealSteam` branch, on `envVars`
(`XServerDisplayActivity.java:285`, consumed by
`app/src/main/java/com/winlator/star/xenvironment/components/GuestProgramLauncherComponent.java`):
- **Agent creds (env hand-off, §2):** `BL_STEAM_TOKEN`, `BL_STEAM_USERNAME`, `BL_STEAM_STEAMID`,
  `BL_STEAM_APPID`.
- **★ The env gate (the STEAM_WINE_LAYER_GAP finding — this is what makes the genuine client load at all):**
  `PROTON_DISABLE_LSTEAMCLIENT=1` **and** append `;lsteamclient=` to `WINEDLLOVERRIDES`. Without these, our
  stock Proton's ntdll `use_lsteamclient` hook hijacks the `steamclient64.dll` load toward a non-existent
  `lsteamclient.dll` and the agent dies. Both working implementations set them (GameHub log-proven; WinNative
  planW source `XServerDisplayActivity.java:7316-7328`). Coordinate with **wine-compat-engineer**.
- **Game process env (set when Bannerlator spawns the game, not the agent):** `SteamAppId=<appid>`,
  `SteamGameId=<appid>`, `SteamClientLaunch=1`, `SteamPath`, `ValvePlatformMutex`. **Must NOT set**
  `WINESTEAMCLIENTPATH*` (that re-routes to the shim). Launch the game exe with `-steam`.

**6.6 Decoupled launch sequence (in the branch):**
1. Stage (6.4) → un-Goldberg (6.3).
2. Start `…/Steam/steam.exe` (our agent) with the `BL_STEAM_*` env + the env gate.
3. **Block** until the agent writes `C:\bl-steam-agent.ready` (poll the prefix path; timeout → auto-fall-back
   to Goldberg-offline). Same session-ready guard as `ensureLoggedIn(timeout)`.
4. `GuestProgramLauncherComponent` launches `<game>.exe -steam` with the 6.5 game env; the game's genuine
   `steam_api64.dll` attaches to the agent's live session.
5. On game exit, write `C:\bl-steam-agent.shutdown`; the agent tears down (logoff + reap).

**6.7 The picker UI.** In `app/src/main/java/com/winlator/star/store/SteamGameDetailActivity.kt` (which already
hosts the Goldberg gear), add a **Launch method** picker: **Real Steam (online) [default] / Goldberg (offline,
existing sub-modes nested) / Raw**, stored per-shortcut via 6.1, read at the 6.2 hook. Defer the Compose/UI
detail to **android-app-engineer**.

**6.8 Runtime sourcing of the genuine `steamclient64.dll`** — download from Valve / user install, **never
bundle** (§7). Wire into `SteamClientStager` as a download-on-demand component (mirror `GoldbergComponent`).

---

## 7. Legality

- **Our agent code = ours.** A clean transliteration of WinNative's **GPL-3.0** `wn-steam-launcher` (interface
  names + v005 vtable offsets are facts, not IP). Bannerlator is GPL-lineage; **keep GPL attribution** for the
  WinNative-derived files.
- **Genuine Valve DLLs (`steamclient64.dll`, `steamservice.exe/.dll`, `tier0_s64`, `vstdlib_s64`) = sourced at
  runtime, NEVER bundled** — from Valve's own bootstrapper/CDN or the user's own install (GameNative's model).
  Do **not** re-host them the way GameHub does (its `steam_client_0403` on `The412Banner/bannerhub-api` is the
  redistribution a shippable clone must not copy).
- **Never** ship or reuse GameHub's `SteamAgent.exe` binary or any leaked GameSir dev token — the login is the
  **end user's own** refresh token, minted by our JavaSteam.
- **Ceiling: VAC-class only.** This satisfies server-side anti-cheat that checks the genuine Steam session +
  game integrity. It **cannot** beat kernel/ring-0 anti-cheat (EAC/BattlEye kernel, Vanguard) — no Android-Wine
  path exists. Be honest about this in the UI.
- **PII:** keep the owner's Steam email/SteamID/token out of every committed artifact, log line, and the process
  command line (§2).

---

## 8. Phased plan

**Honest gate (unchanged from the synthesis):** "VAC-capable by construction" ≠ "VAC-proven on ARM." GameHub
cleared VAC-*auth* on this device with L4D2; nobody has yet shown a *sustained* secure-server match under ARM
translation. Prove that on device before pouring in engineering. The phases below front-load the cheapest
proofs.

### Phase 0 — env de-risk (already characterized; may already be done)
Confirm `PROTON_DISABLE_LSTEAMCLIENT=1` (+ `WINEDLLOVERRIDES … ;lsteamclient=`) lets a genuine-client load
proceed in `xuser-3` (STEAM_WINE_LAYER_GAP §6-b). If not yet device-proven for our Proton build, do it first —
it is a prerequisite for M0.

### ★ M0 (FIRST buildable milestone) — headless genuine-client login, by hand, with OUR binary
**Build:** the login-only slice of `bl-steam-agent` — §1 steps 1-2, 6-13 only (env read → load
`steamclient64.dll` → v005 → `CreateGlobalUser` → `GetIClientUser` → `SetLoginToken` → `LogOn` → poll
`BLoggedOn`, write `C:\bl-steam-agent.log`, exit). **No** registry/appmanifest/steamservice/game/socket yet.
Cross-compile with the §4 MinGW recipe. (Pragmatic seed: compile WinNative's `main.cpp` unmodified as the
compiling base, then strip to the login slice and rename — that *is* building our own agent, starting from a
green build.)

**Test in `xuser-3`** (reuse `STEAMLITE_PROTO_RUNBOOK.md`'s grounded facts, swapping GameHub's `SteamAgent.exe`
for OUR binary):
- Container **id 3 / `xuser-3`**; `WINEPREFIX=…/imagefs/home/xuser-3/.wine`; wine =
  `contents/Proton/11.0-2-arm64ec-1/bin/wine` (`HODLL=libwow64fex.dll`).
- Genuine `steam_client_0403` already staged in `…/Steam`; stage our `bl-steam-agent.exe` as `…/Steam/steam.exe`.
- Run (token via env, read from `steam_prefs.xml`, **never printed**):
  `env WINEPREFIX=… PROTON_DISABLE_LSTEAMCLIENT=1 WINEDLLOVERRIDES='…;lsteamclient=' BL_STEAM_USERNAME=… BL_STEAM_TOKEN=… BL_STEAM_STEAMID=… BL_STEAM_APPID=550 …/bin/wine 'C:\Program Files (x86)\Steam\steam.exe'`
- Capture `C:\bl-steam-agent.log` via the root bridge; grep for the markers (redact token/steamID before
  quoting anywhere).

**PASS:** log shows `CLIENTENGINE_INTERFACE_VERSION005 -> non-null`, valid `pipe`/`user`, `SetLoginToken -> 1`,
and `callback 101 SteamServersConnected` / `Steam_BLoggedOn=true`.
**FAIL modes:** load fails → env-gate/DLL-placement (STEAM_WINE_LAYER_GAP §5); `SetLoginToken`/`LogOn` non-1 →
token/account mismatch (`102` EResult tells which); no `101` → CM reachability (add cmlist §3).
**Why this first:** it isolates *our driver on our stack* from every downstream concern (game, socket, UI) and
needs zero app code.

### M1 — full agent: session + service + registry + ready/park/teardown
Add §1 steps 3-5, 14-18: registry seed, app-manifest, `RequestAppInfoUpdate`, install/start `steamservice`,
write the `…​.ready` sentinel, park resident, tear down on `…​.shutdown` (transliterate
`clean_shutdown.cpp`). Test by hand in `xuser-3`: run agent → confirm `…​.ready` appears → confirm
`steamservice` RUNNING + registry seeded → touch `…​.shutdown` → confirm clean logoff (and that a *second*
run does not hit `AlreadyRunning 0x10`).

### M2 — game attach (decoupled), by hand
With the agent parked (M1), launch `left4dead2.exe -steam` in the same prefix with the game env (§6.5).
**PASS:** L4D2's genuine `steam_api` attaches to the agent's session, reaches the main menu, and **joins a
VAC-secured server and stays connected past ~5-9 s** (the GameHub failure window) — the real VAC gate. Still no
app code.

### M3 — app-side feature
Build §6: `SteamClientStager.kt`, the `RealSteam` branch in `XServerDisplayActivity`
(`maybeSeedAndStartAchievementWatcher`), the env block + env gate in `GuestProgramLauncherComponent`, the
`SteamPrefs` launchMode, the `SteamGameDetailActivity` picker, runtime Valve-DLL sourcing, and auto-fall-back to
Goldberg-offline on ready-timeout. Ship behind the picker (Real Steam default, Goldberg fallback).

### M4 — prove it
TF2 / CS:S / L4D2 on VAC-secured servers, real players, a full match, on device.

### Phase-3 options (post-ship)
`cmlist.json` pre-warm (§3); the newline-JSON control socket with overlay/friends/`launch_failed` telemetry
(GameHub's `STEAMAGENT_PORT` shape) replacing the sentinel files (§2).

---

## Appendix — load-bearing WinNative files to transliterate (all absolute)

- **Driver model:** `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-launcher/src/main.cpp`
  — v005 vtable offsets `:35-57`; env block `:923-932`; registry seed `:146-212`; config `:214-232`;
  app-manifest `:252-428`; steamservice `:581-662`; genuine-DLL load `:964-1031`; flat exports `:1033-1049`;
  `CreateInterface(v005)` `:1062`; `CreateGlobalUser` `:1075`; `GetIClientUser` `:1084-1090`;
  **`SetLoginToken` `:1100-1107`**; **`LogOn` `:1126-1131`**; logon poll `:1143-1199`.
  **Drop for decoupled:** `LaunchApp` + utils poll + `CreateProcess` fallback + game-watch `:1301-1554`;
  redists `:831-885`.
- **Teardown / reap:** `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-launcher/clean_shutdown.cpp`
  + `.../clean_shutdown.h` (C-ABI: `wn_launcher_arm_clean_shutdown`, `wn_launcher_clean_shutdown_now`,
  `wn_launcher_wait_clean_shutdown`).
- **Build recipe:** `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-launcher/build.sh`
  (MinGW-w64 POSIX-threads, static, `--subsystem,windows`, `-ladvapi32 -lkernel32 -luser32`).
- **App-side staging + env gate (mirror, not transliterate):**
  `/home/claude-user/winnative-today/app/src/main/feature/stores/steam/wnsteam/WnSteamAssetsInstaller.kt`
  (`installPlanWLauncher :391`, `installPlanWSteamService :136`, `installPlanWValveSteam :307`);
  env gate `/home/claude-user/winnative-today/app/src/main/runtime/display/XServerDisplayActivity.java:7316-7328`.
- **v005 vtable layout reference (non-GPL, community-RE'd, usable):** OpenSteamworks
  `IClientEngine`/`IClientUser`/`IClientApps` headers — the same layout WinNative's `:35-57` offsets encode.

## Backing docs (in `re/`)
`GAMEHUB_REAL_LAUNCH_ORCHESTRATION.md` (the launch recipe), `GAMEHUB_STEAMAGENT_RE.md` (agent PE + socket),
`WINNATIVE_VAC_RE.md` (the GPL code reference), `STEAM_WINE_LAYER_GAP.md` (the `PROTON_DISABLE_LSTEAMCLIENT`
env gate), `STEAMLITE_PROTO_RUNBOOK.md` (the grounded `xuser-3` by-hand runbook), `SYNTHESIS_OWN_STEAM_AGENT_PLAN.md`
(3-way convergence). Canonical project doc: `../STEAMLITE_PROJECT.md`.
