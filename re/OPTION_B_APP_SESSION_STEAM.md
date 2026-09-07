# Option B — Steam games on the APP's Steam session (genuine bionic `libsteamclient.so` in-process)

**Date:** 2026-09-02 · **Scope:** READ-ONLY research spike (no repo/device changes) · **Author:** native-steam engineer
**Question:** can a Bannerlator-launched Steam game use the app's own session (Rust engine `libblsteam.so` already
logged in) instead of a second login inside the container, so store/friends/chat stay online and the in-game drawer
Friends tab is fed by the app — GameNative's "bionic Steam" model — and is that VAC-capable?

**Bottom line (details + evidence below):**
1. **Sourcing is legally clean and no longer needs GameNative's CDN.** Valve's own signed client CDN publishes an
   `androidarm64` build of `libsteamclient.so` (+ `steamservice.so`, `libtier0_s.so`, `libvstdlib_s.so`,
   `libsteamnetworkingsockets.so`) inside the `steam_client_linuxarm64` manifest. GameNative merely mirrors the same
   5 files (older build). We can fetch it at runtime from Valve, verify the manifest `sha2`, never bake it.
2. **The contract is fully documented from OPEN sources** (GameNative's Kotlin/Java + WinNative's GPL
   `steam_bootstrap.cpp` + Proton's BSD `lsteamclient`/`steam_helper`). The only closed piece (GN's
   `libsteambootstrap.so`) is replaceable by ~800 LOC of our own C++ modelled on WinNative's GPL bootstrap; its
   `strings` confirm the same call sequence.
3. **It is NOT literally "one session".** The genuine in-app client is a *second* CM logon with the same refresh
   token; the Rust engine keeps its own. Steam tolerates two logons on one account as long as only one reports
   "playing" — this is exactly what GameNative ships (its JavaSteam session stays up during a bionic launch).
   The real single-session design (engine routed through the genuine client's private `IClient*` vtables) is a
   large RE and throws away the engine; not recommended.
4. **VAC: zero evidence anywhere that it passes on Android/FEX**, and a strong negative prior from our own
   SteamLite device work (a game Steam did not spawn = insecure session). Decisive experiment defined in §4.
5. **Recommendation: GO for a bounded spike** (lsteamclient in our Proton-11 layer + our bootstrap host + one
   non-VAC title + one CS:S VAC probe), **NO-GO on retiring SteamLite**. Per-game selector: `AppSteam` for non-VAC
   titles, SteamLite stays default for `vac_secure` titles until §4 passes.

---

## 1. Sourcing — where a genuine ARM64 *bionic* `libsteamclient.so` comes from

### 1.1 Valve's own client CDN ships it (verified 2026-09-02, read-only `curl`)

- `https://client-update.steamstatic.com/steam_client_linuxarm64` → HTTP 200, KeyValues manifest, `"version" "1788291500"`,
  signed (`kvsign2`/`kvsignatures` blocks). `steam_client_publicbeta_linuxarm64` also 200. (`steam_client_androidarm64`,
  `steam_client_android`, `steam_client_linuxaarch64` → 404 — the Android build is a *package inside the linuxarm64
  client*, not a separate client.)
- Package entry (manifest line 257):
  ```
  "bins_androidarm64_linuxarm64"
  {
      "file"   "bins_androidarm64_linuxarm64.zip.a0be739e9a7b7b1750c02cbe23c79af2691687c7"
      "size"   "18018236"
      "sha2"   "d8c1a969da2463e5c7245fa5429a8dd9a6ffb4b673249a3f1cfcadbdab9ef7aa"
      "zipvz"  "bins_androidarm64_linuxarm64.zip.vz.e9ead5e80e00e513c55c7a7fbe31ef7b90f8b979_8829317"
  }
  ```
- Downloaded `https://client-update.steamstatic.com/bins_androidarm64_linuxarm64.zip.a0be739e…` → 18,018,236 B,
  sha256 matches the manifest `sha2`. Contents:
  ```
  androidarm64/libsteamclient.so            37,517,612
  androidarm64/libsteamnetworkingsockets.so  8,065,024
  androidarm64/libtier0_s.so                   519,144
  androidarm64/libvstdlib_s.so                 701,320
  androidarm64/steamservice.so               7,295,320
  ```
- `libsteamclient.so` is a **bionic** ELF: `ELF64 AArch64 DYN`, `NEEDED libandroid.so liblog.so libm.so libdl.so libc.so`,
  `SONAME libsteamclient.so`. Build path string inside: `/home/buildbot/buildslave/steam_rel_alt_androidarm64/build/…`.
  Exports the flat entry points the bootstraps use: `CreateInterface`, `Steam_CreateGlobalUser`, `Steam_CreateSteamPipe`,
  `Steam_BLoggedOn`, `Steam_LogOff`, `Steam_BGetCallback`, `Breakpad_SteamSetAppID`. It reads the env contract:
  strings `Steam3Master`, `SteamClientService`, `_STEAM_SETENV_MANAGER`, `STEAMVIDEOTOKEN`, `SteamOS`,
  `STEAM_SSL_CERT_FILE` are all present.
- The zip is plain (`.zip`); the `zipvz` variant is Valve's VZip (LZMA) — use the plain zip, no VZip decoder needed.

### 1.2 GameNative's CDN is a mirror of the same set (older build)

- `https://downloads.gamenative.app/steam-androidarm64-20260709.tzst` (13,576,200 B, Last-Modified 2026-07-10) →
  `usr/lib/{libsteamclient.so 37,426,716, libsteamnetworkingsockets.so, libtier0_s.so, libvstdlib_s.so, steamservice.so}`
  — identical file set, different (older) build; sha256 `c804d76d…` vs Valve current `4e9a9da5…`.
- GN CDN code: `gamenative-today/app/src/main/java/app/gamenative/service/SteamService.kt:1571-1572`; asset list
  `utils/launchdependencies/BionicSteamAssetsDependency.kt:34-40`. GN's own provenance stance: `THIRD_PARTY_NOTICES:105-184`
  ("Valve's binaries … in the user's own Steam installation … not redistributed in the repo").

### 1.3 Legal shape

- **Runtime download from Valve's CDN + manifest-sha verification = the clean path.** Nothing Valve-owned enters our APK or
  git. This is strictly better than GN (re-hosting) and WinNative (bundling `valve-steam-x86_64.tzst` in the APK).
- The bridge (`lsteamclient`, `steam_helper`) is Valve's Proton code under Proton's BSD-style LICENSE
  (`ValveSoftware/Proton/lsteamclient/LICENSE`) — buildable and shippable inside our own Proton layer.
- Caveat (not legal advice): driving Valve's client library from a third-party launcher is the same posture GN ships on
  Google Play; the Steam Subscriber Agreement is the document to review before a public release.

---

## 2. Contract — bootstrap, login, loopback services, Proton side

### 2.1 Bring-up sequence (open sources agree; GN blob strings corroborate)

Sources: WinNative GPL `winnative-today/app/src/main/cpp/wn-steam-bootstrap/src/steam_bootstrap.cpp` (header `:1-37`,
verified dance `:72-96`, code `:434-743`); GN `SteamBootstrap.kt:121-170`; GN blob strings (`libsteambootstrap.so`, 32,776 B).

| Step | What | Evidence |
|---|---|---|
| 0 | **setenv BEFORE dlopen**: `Steam3Master=127.0.0.1:57343`, `SteamClientService=127.0.0.1:57344`, `HOME=<prefix>/drive_c/Program Files (x86)` (client resolves `<HOME>/Steam/config/{config,local}.vdf`), `STEAM_SSL_CERT_FILE=<cacert.pem>`, `LD_LIBRARY_PATH=<lib dir>`, `SteamAppId`/`SteamGameId` | WN `:23-26` ("libsteamclient.so reads the IPC endpoint env vars at module init and binds the listening sockets there. Setting them later is a no-op"), `:434-498`; GN `SteamBootstrap.kt:144-152` |
| 1 | Preload siblings `RTLD_GLOBAL` in order tier0 → vstdlib → steamnetworkingsockets → steamservice; `SteamService_StartThread("SteamClientService")` | WN `:307-340`, `:513-522`; GN blob strings `preload_steam_siblings`, `SteamService_StartThread`, `SteamService_GetIPCServer`, `start_steamservice: could not resolve InitIPC; falling back to StartThread (in-process only)` |
| 2 | Stage `<HOME>/Steam/config/config.vdf` + `local.vdf` (client stats them; missing = silent bail). GN persists them per SteamID under `/data/data/<pkg>/files/imagefs/.steambootstrap/<steamid64>` and symlinks them in so later boots use "LogOn(cached) … no token over wire" | WN `:367-391`; GN blob strings `Found saved session files in %s, restoring…`, `Saved config.vdf to %s`, `LogOn(steam_id=%llu) [cached-creds path; no token over wire]` |
| 3 | `dlopen(libsteamclient.so, RTLD_NOW\|RTLD_GLOBAL)`; dlsym `CreateInterface`, `Steam_CreateGlobalUser`, `Steam_BLoggedOn`, `Steam_LogOff`, `Steam_ReleaseUser`, `Steam_BReleaseSteamPipe`, `Steam_BGetCallback`, `Steam_FreeLastCallback`, `Breakpad_SteamSetAppID(0)` | WN `:525-567`; all exported by Valve's lib (§1.1) |
| 4 | `Steam_CreateGlobalUser(&pipe)` → user handle (NOT `Steam_CreateSteamPipe` — returns 0 on Android, expects fork/exec helper) | WN `:569-582`, blob `SteamHost: pipe=%d user=%d` |
| 5 | `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `IClientEngine`; vtable `+0x40` `GetIClientUser(user,pipe)`; `IClientUser` slots: `+0x08 SetSteamID`, `+0x188 IsAccountLoggedIn`, `+0x190 SetAccount`, `+0x1B0 SetLoginInformation(account,"",1)`, `+0x1C0 LogonWithRefreshToken(token, account)` | WN `:162-170`, `:599-669` (slots "confirmed by Ghidra decomp of reference bootstrap") — **these are private-vtable offsets; they drift with client builds** |
| 6 | Poll `Steam_BLoggedOn` while draining `Steam_BGetCallback`/`Steam_FreeLastCallback` (cb 101 connected / 102 connect-failure w/ EResult / 113 disconnected); then a persistent ~50 Hz pump thread for the whole session | WN `:684-743`, `:178-223` |
| 7 | Prepare app: `IClientApps::RequestAppInfoUpdate` → wait `AppInfoUpdateComplete_t`, `BUpdateAppOwnershipTicket(appid)` (≤10 tries), `SetLanguage("english")`, `SetPersonaState(1)`, **`IClientAppManager::LaunchApp(appid) -> jobID`** (host-side), then "serving SteamClientService IPC" | GN blob strings `steam_prepare_app: …`; WN `nativePrepareApp` is a stub (`:1851-1859`) |
| 8 | GN only: **`patch_os_type_to_windows`** — mprotect+patch `GetOSType → k_eWindows10` and "engine oslist android→windows" so the CM/app-info treat the session as a Windows client | GN blob strings; WN does not do this (its games run on its own reimpl). Must be re-derived if needed |
| 9 | Ready handshake to Kotlin: GN writes `cacheDir/sb_host_ready` = `INIT` → `READY` \| `FAILED:{env,dlopen,nosym,login}` \| `CLOSED`, polled ≤60 s; GN runs the host as a **separate ELF subprocess** (`ProcessBuilder(nativeLibraryDir/libsteambootstrap.so, appId, libPath, readyFile)`, stdout→`sb_host.log`) so a crash cannot take the app down; WN runs it in-process over JNI | GN `SteamBootstrap.kt:126-170`; WN JNI `nativeInit/…` |
| 10 | Teardown: `Steam_LogOff`, `Steam_ReleaseUser`, `Steam_BReleaseSteamPipe`, `unsetenv` everything (else a later SteamLite launch inherits `Steam3Master`/`WINESTEAMCLIENTPATH`) | WN `:1735-1847`; GN `SteamBootstrap.stop()` SIGTERM + `XServerScreen.kt:4624-4635` |
| 11 | Sqlite quirk: symlink `libsqlite.so → libsqlite3.so.0` next to the lib when `/system/lib64/libsqlite.so` lacks OpenSSL symbols | GN `SteamBootstrap.kt:192-232` |

Credentials into the host: GN env `SB_ACCOUNT`, `SB_REFRESH_TOKEN`, `SB_STEAMID64` (`SteamBootstrap.kt:149-151`) — the SAME
JavaSteam refresh token the app is logged in with; WN passes them as JNI args. Ours = `steam_prefs.refresh_token/username/steam_id_64`
(already the SteamLite contract, `RealSteamLauncher.java:264-268`).

### 2.2 Loopback services the game side expects

- Two TCP listeners on **127.0.0.1:57343 (`Steam3Master`) and :57344 (`SteamClientService`)**, bound by the genuine
  lib itself from the env vars (WN header `:1-5`; GN `BionicProgramLauncherComponent.java:520-521`; WN reimpl mirrors
  the ports in `wn-libsteamclient/src/tcp_services.cpp:209-223`, LE-u32 length-framed messages `:110-176`).
- **Our crate's `wine_bridge.rs` is NOT an IPC server** — it binds the same two ports, reads the first 64 bytes and
  closes (`bannerlators/app/src/main/cpp/bl-steam-client/rust/src/wine_bridge.rs:138-156`). In AppSteam mode it must
  stay stopped or it will squat the ports the genuine lib needs.
- App↔host control channel (GN): abstract `LocalSocket` `gamenative-steam-overlay` (`SteamOverlayClient.kt:21,139-140`),
  line protocol `PING/SELF/LIST/INVITE/ACCEPT/RPJOIN/POLL` (blob strings `F %llu %d %u %d %llu %s`, `A %s %llu`). For us
  the equivalent already exists as the JSON agent channel (`SteamAgentChannel`); the drawer roster can simply stay on the
  engine (§3).

### 2.3 Proton side — what `lsteamclient` needs

- **Stock Proton 11 unix loader** (`ValveSoftware/Proton@proton_11.0 lsteamclient/unixlib.cpp` ~L765-780):
  `snprintf(path, "%s/.steam/sdk" STEAM_ARCH "/steamclient.so", getenv("HOME"))` with `STEAM_ARCH="arm64"` on aarch64,
  then `dlopen(path, RTLD_NOW)`. **No `WINESTEAMCLIENTPATH{,64}` in Proton 11** (that variable is legacy; GN still sets it
  at `BionicProgramLauncherComponent.java:504-505` but its own build ignores it).
- **GN's build hard-codes the Android path** (`GameNative/proton-wine@proton_11.0-2 lsteamclient/unixlib.cpp:784-786`):
  ```c
  #if defined(__ANDROID__)
      snprintf( path, PATH_MAX, "/data/data/app.gamenative/files/imagefs/usr/lib/libsteamclient.so" );
  #else
      snprintf( path, PATH_MAX, "%s/.steam/sdk" STEAM_ARCH "/steamclient.so", getenv( "HOME" ) );
  ```
  (plus `LSTEAM_LOGCAT` diagnostics and the error string `CreateSteamPipe returned 0 (libsteamclient.so daemon not
  reachable)`). WinNative byte-patches that string in the shipped `.so` to its own package path
  (`WnSteamAssetsInstaller.kt:474-519`). **For us: build it ourselves and make the path `WINESTEAMCLIENTPATH64`-driven
  (or `<HOME>/.steam/sdkarm64/steamclient.so` symlink → stock behaviour, zero patch).** The unix `.so` NEEDs
  `ntdll.so`, `libc++_shared.so` — it is a Wine unixlib, ABI-locked to the exact Proton build (GN keeps one archive per
  Proton: `BionicSteamAssetsDependency.kt:48-56`).
- **Source is open and already integrated in GN's Proton 11 tree:** `GameNative/proton-wine` PR #34 (2026-08-24,
  `dafe413a`) "Integrate lsteamclient + steam_helper into the Proton 11.0-2 build … produced and installed by the regular
  Proton 11.0-2 build on both x86_64 and arm64ec". Our fork `The412Banner/proton-wine@proton_10.0_add_steam` carries the
  same files (`lsteamclient/unixlib.cpp:787` has the GN hard-coded path) + `build-scripts/build-steam-targets.sh`, but
  **only for Proton 10; no Proton-11 branch, never built for our layers.**
- **Device state (root bridge, read-only, 2026-09-02):** none of the six layers under
  `/data/data/com.tencent.ig/files/contents/Proton/{10-arm64ec-0,10.0-2-arm64ec-1,10.0-4-arm64ec-2,11.0-2-arm64ec-1,11.0-5-arm64ec-1,11.0-6-arm64ec-2}`
  has `lib/wine/aarch64-windows/lsteamclient.dll`, `i386-windows/lsteamclient.dll`, or `aarch64-unix/lsteamclient.so`
  (explicit `ls` per path → "No such file"). The ntdll interception hook IS present (`strings` on
  `11.0-6-arm64ec-2/lib/wine/aarch64-windows/ntdll.dll`: `use_lsteamclient`, `lsteamclient.dll`, `lsteamclient disabled.`,
  `steamclient ImageBase %#Ix.`), so once `lsteamclient.dll` exists in system32/syswow64 the redirect works with
  **`PROTON_DISABLE_LSTEAMCLIENT` unset** (the inverse of SteamLite, `RealSteamLauncher.java:264`).
- PE-side registration: Proton's `steam_helper/steam.c:211-218` `LoadLibraryW("lsteamclient")` →
  `steamclient_init_registry()` writes `HKCU\Software\Valve\Steam\ActiveProcess` (`pid` at `:68`, `SteamClientDll{,64}`);
  GN instead writes the registry from Java (`XServerScreen.kt:5938-5953`: `ActiveUser`, `SteamClientDll=<Steam>\steamclient.dll`,
  `SteamClientDll64=<Steam>\steamclient64.dll`, `Universe=Public`) and stages genuine PE `steamclient{,64}.dll`
  (`steamclient-dlls-20260619.tzst` → `Program Files (x86)/Steam/steamclient64.dll` 25,739,928 B + `steamclient.dll`
  21,005,976 B — the same DLLs our `steamlite.tzst` already stages) so `steam_api64.dll`'s `LoadLibrary` target exists and the
  ntdll hook redirects it. GN's `steam-proton11.exe` (176,128 B, `PE32+ for WINE x86-64`) is Proton's stock `steam_helper`
  (`strings`: `steam_helper`, `steamclient_init_registry`, `STEAM_COMPAT_CLIENT_INSTALL_PATH`, `Steam3Master_SharedMemLock`).
- Game env block (GN `BionicProgramLauncherComponent.java:498-549`; WN `WnWineEnvVars.kt`):
  `_STEAM_SETENV_MANAGER=1`, `BREAKPAD_DUMP_LOCATION`, `STEAM_BASE_FOLDER=<prefix>/drive_c/Program Files (x86)/Steam`,
  `ENABLE_VK_LAYER_VALVE_steam_overlay_1=0`, `STEAMVIDEOTOKEN=1`, `SteamOS=1` (forces `IsOverlayEnabled()` so invite UI opens),
  `Steam3Master`, `SteamClientService`, `SteamUser`/`SteamAppUser=<login>`, `SteamClientLaunch=1`, `SteamEnv=1`,
  `SteamPath=C:\Program Files (x86)\Steam`, `ValvePlatformMutex`, `STEAMID=<steamid64>`, `SteamGameId`/`SteamAppId=<appid>`
  (+ WN `OWNED_DLCS=csv`). Per-boot: delete stale `config/{config,loginusers}.vdf`, `local.vdf`; copy `lsteamclient.dll` into
  system32 + syswow64 (GN `extractLsteamclientIntoPrefix`, `BionicSteamAssetsDependency.kt:145-171`); GN launches the
  game exe **directly** (`XServerScreen.kt:4500-4511`), not via `steam.exe -applaunch`.

---

## 3. Integration with our Rust engine — what "one session" really means

**Facts:**
- The genuine lib performs its own CM logon (`LogonWithRefreshToken`) — it cannot borrow the engine's socket. So Option B
  is inherently **engine session A + genuine-client session B**, both from the same refresh token.
- **Steam allows this.** GN ships exactly it: its JavaSteam client is never logged off for a bionic launch
  (`SteamService.kt` has no bionic-gated logoff; `XServerScreen.kt:3908-3910` only drops the local `SteamClientComponent`),
  it merely stops reporting `GamesPlayed` itself (`notifyRunningProcesses`, `SteamService.kt:2431-2486`, re-sent on
  reconnect `:3906-3910`) and handles `PlayingSessionState`/`LoggedInElsewhere` (`:4085-4120`). The conflict primitive
  is *playing*, not *logon*.
- Why SteamLite pauses us today (`SteamRepository.java:1325-1335`): "VAC Source titles tolerate the tug-of-war,
  live-service titles do not … and the agent's LaunchApp can stall". That is a *symptom of two clients both trying to own
  the game*, driven by the genuine in-Wine client doing `LaunchApp`. In Option B the genuine client is in *our* process
  and we control what the engine does, so the pause is not needed **if** the engine obeys the rules below.

**Minimal design = "two logons, one player" (recommended):**
```
APP PROCESS (arm64/bionic)
 ├─ libblsteam.so   (Rust CM engine)  = session A: store, library, downloads, cloud, achievements, FRIENDS/CHAT, drawer tab
 └─ libblsteamhost  (our bootstrap)   = session B: genuine androidarm64/libsteamclient.so, same refresh token,
      listens 127.0.0.1:57343 (Steam3Master) / :57344 (SteamClientService); does LaunchApp/ownership/DRM/matchmaking
        ▲ loopback
WINE (Proton 11 arm64ec, PROTON_DISABLE_LSTEAMCLIENT unset)
 game.exe (x86_64 via FEX) → genuine steam_api64.dll → LoadLibrary(steamclient64.dll)
   → ntdll use_lsteamclient → lsteamclient.dll (PE) → lsteamclient.so (unix, aarch64)
   → dlopen(libsteamclient.so) [in-game instance] → Steam3Master TCP → session B in the app
```
Rules that keep it stable (what would *break* the rule):
1. Engine must **never send `ClientGamesPlayed` while an AppSteam game runs** — gate `SteamRepository.setInGamePresence`
   (3a-3 offline presence, `XServerDisplayActivity.announceOfflineSteamPresence`) OFF for `launchMode=AppSteam`;
   `BlSteamSession.nativeNotifyGamesPlayed` (`BlSteamSession.kt:576`) stays idle. Session B reports playing.
2. Engine must **not `kickPlayingSession`** (`BlSteamSession.kt:591`) during the game; treat `PlayingSessionState{blocked}`
   from session B as expected (`markPlayingBlocked`, `:603`).
3. **No refresh-token renewal while B is up** — `SteamSessionManager.maybeRenewRefreshToken` (`SteamSessionManager.kt:140`,
   <14 d rule) must run in pre-flight *before* the host starts, and B must be started with the post-renewal token; B's
   own rotated token (it saves `config.vdf`/`local.vdf`) is per-host state we keep under
   `imagefs/.steamhost/<steamid64>/` like GN, so B's later boots use its cached creds and never fight A's token.
4. Persona: A sets Online once (opt-in); B's `SetPersonaState(1)` (GN does it) is harmless — same account, same presence.
5. `wine_bridge.rs` snoop listener must be off (port squat, §2.2). SteamLite's `suspendForRealSteam` must NOT be called
   for AppSteam (`XServerDisplayActivity.java:4580-4590` is gated on `realSteamPlan != null`, so a separate plan object
   keeps it off by construction).
6. In-game drawer Friends tab: `InGameFriendsSource` already models this — `Kind.APP_SESSION` "every other launch — the
   app's own CM session is not paused, so `SteamFriendsStore.isAvailable` is the whole test" (`InGameFriendsSource.kt:24-27`).
   AppSteam = `APP_SESSION`, no relay, no pause, chat over the engine. The 3b-5 agent relay stays SteamLite-only.

**The true single-session alternative (not recommended):** make B the only logon and route the engine's
friends/chat/library/cloud through B's private `IClientFriends`/`IClientApps`/… vtables (WN bootstrap Stage-2 already
walks the *public* `SteamClient020` → `SteamFriends017` path, `steam_bootstrap.cpp:745-935`; our agent p3 does the same
against `steamclient64.dll`). It gives one CM socket but costs the whole Rust engine surface (PICS, depots, cloud,
achievements would need private-vtable RE), and every client update can move slots. Two-logon is what GN proved in
production; take it.

---

## 4. VAC — evidence and the decisive experiment

**Evidence found (all negative or absent):**
- GameNative: zero VAC/anti-cheat code on any ref (`GAMENATIVE_VAC_RE.md §1`); no GitHub issue or discussion matching
  "VAC" (`gh search issues`, GraphQL discussion search → empty); no release note mentions VAC/secure (30 releases scanned);
  press only claims "online play may not always work". Its bionic mode launches the exe **directly** (`XServerScreen.kt:4500-4511`).
- Our own device fact (SteamLite, CS:S 240): a process Steam's `LaunchApp` did not spawn joins **insecure**
  (`RealSteamLauncher.java:249-255` comment; agent `insecure_fallback`). Option B as GN does it (direct exe, host-side
  `LaunchApp` that cannot spawn a Wine process) sits exactly in that insecure case unless the in-game `steam_api`
  registration is enough — unknown.
- VAC modules: Valve says VAC is "fully supported" on Steam Deck (x86-64 Linux, native `steamclient.so` in-process); the
  androidarm64 lib carries the VAC protobufs (`steammessages_vac.steamclient.pb.cc`, `k_EMsgClientVACResponse`,
  `IsVACBanned`) but whether Valve ships **arm64 VAC modules** and whether they accept a FEX-translated x86 game process
  is undocumented. Steam Frame statements only exclude kernel anti-cheat.
- GN's `patch_os_type_to_windows` suggests the CM otherwise sees an Android client — which may itself change VAC module
  selection. Unknown either way.

**Decisive experiment (one device session, no app code beyond the spike):**
1. Prereqs: our Proton-11 layer with `lsteamclient` built (§5 P0), Valve `androidarm64` set extracted to
   `imagefs/usr/lib/`, our bootstrap host logged on (`Steam_BLoggedOn=1`, `sb_host_ready=READY`).
2. Title: **Counter-Strike: Source (240)** — VAC-secured, device-proven online via SteamLite (so server/network/FEX are known-good),
   launcher exe `cstrike.exe` (not `_win64`).
3. Launch via AppSteam env block (§2.3), `PROTON_DISABLE_LSTEAMCLIENT` unset, registry `ActiveProcess` seeded, genuine
   `steamclient64.dll` present, `WINEDEBUG=+loaddll` once to confirm `lsteamclient.dll` loads instead of the genuine DLL.
4. In game: `connect <known VAC-secured server>`; then `status` → look for `secure` in the header; wait ≥120 s (VAC kicks
   arrive late); watch for "VAC unable to verify game session"/"Disconnected by VAC". Repeat once with a second secured server.
5. Capture: host log (`sb_host.log` equivalent), `wine_debug.log` (`lsteamclient`, `steamclient_init`), logcat `LSTEAM_LOGCAT`,
   `steam_debug.txt`, and whether `steamservice.so` fetched any VAC module (`strace -e openat` on the host is optional).
6. Verdict rule: **secure + survives 2 full rounds on 2 servers = VAC-capable (promote AppSteam for VAC titles)**;
   insecure/kick = keep SteamLite for `vac_secure` titles (AppSteam still ships for non-VAC titles).

---

## 5. Effort / plan (per-game alternative; SteamLite untouched)

| Phase | Work | Files (Bannerlator, all NEW unless noted) | Size |
|---|---|---|---|
| **P0 — bridge in our Proton 11** (wine-compat-engineer) | Port `GameNative/proton-wine` PR #34 (`lsteamclient` + `steam_helper` integrated into the normal build) onto our `proton_11.0` bionic branch; replace the hard-coded `/data/data/app.gamenative/…` line with `getenv("WINESTEAMCLIENTPATH64")` fallback → `<HOME>/.steam/sdkarm64/steamclient.so`; ship `lsteamclient.dll` (aarch64-windows + i386-windows) + `lsteamclient.so` (aarch64-unix) + `steam.exe` INSIDE the layer wcp (ABI-locked → one per layer: 11.0-2/11.0-5/11.0-6; aligns with the 16KB-unify rebuild). Keep the ntdll hook default-on. | `proton-wine` repo only | 1–2 d build + CI |
| **P1 — bootstrap host** | `app/src/main/cpp/bl-steam-host/` (C++, GPL, modelled on WN `steam_bootstrap.cpp`): standalone ELF like GN (crash-isolated, run via `ProcessBuilder` from `nativeLibraryDir`) with the §2.1 sequence, `sb_host_ready` file handshake, persistent pump, per-SteamID session-file persistence, `prepareApp` (RequestAppInfoUpdate / ownership ticket / `LaunchApp`), clean `LogOff/ReleaseUser/ReleasePipe`. Decide on the OS-type patch after the first logon test (try without first). Vtable slots pinned to a manifest `version`; refuse to run on an unverified build. | `bl-steam-host/{CMakeLists.txt,main.cpp,client_iface.h}`, Kotlin `store/appsteam/SteamHost.kt` (start/stop/status) | 3–5 d |
| **P2 — assets** | `store/appsteam/SteamHostComponent.kt` (mirror `SteamLiteComponent.kt:36-112`): fetch `steam_client_linuxarm64` manifest → `bins_androidarm64_linuxarm64` entry → download plain zip → verify `sha2` → extract to `imagefs/usr/lib/` + version marker + sqlite symlink + `cacert.pem` (reuse `wnsteam_cacert.pem`). Settings toggle + Log Manager capture (`sb_host.log`, `LSTEAM_LOGCAT`). | `SteamHostComponent.kt`, `SteamHostLogCollector` hooks in `SteamLiteLogCollector.java` | 2 d |
| **P3 — launch path `launchMode=AppSteam`** | `store/AppSteamLauncher.java` (sibling of `RealSteamLauncher.java`): plan = stage genuine `steamclient{,64}.dll` (already in `steamlite.tzst`) + copy `lsteamclient.dll` into system32/syswow64 per boot + registry `ActiveProcess` (or run Proton `steam.exe` helper) + env block §2.3 + `WINESTEAMCLIENTPATH64`; `XServerDisplayActivity`: new branch beside `maybeStageRealSteam` (env merge at `:6020-6030`), **no** `suspendAppSteamSessionForRealSteam`, **no** `PROTON_DISABLE_LSTEAMCLIENT`, offline-presence guard off, host start before guest boot, host stop on exit; `LaunchMethodSheet.kt:112` enum + `APP_STEAM("AppSteam")`; pre-flight row "Steam host ready"; `InGameFriendsSource` → `APP_SESSION`. | `AppSteamLauncher.java`, edits in `XServerDisplayActivity.java`, `LaunchMethodSheet.kt`, `SteamPreflightDialog`, `SteamRepository` (presence gate) | 3–4 d |
| **P4 — proof** | Non-VAC online title first (Brawlhalla 291550 / Dead Cells) → then §4 CS:S VAC probe. | device | 1–2 d |

Total ≈ 2–3 engineer-weeks to a device-proven non-VAC AppSteam; VAC verdict falls out of P4.

**Per-game selector semantics:** `AppSteam` = default for Steam-origin titles with `steam_games.vac_secure=0`
(SteamDatabase v11) once P4 non-VAC passes; `RealSteam` (SteamLite) stays default for `vac_secure=1` and the launch
popup's "Requires secure (VAC) launch" row keeps its override; Goldberg/Raw unchanged. If the §4 probe passes, flip the
`vac_secure` default to AppSteam per title after two device-proven sessions each.

**Risks:**
- Private vtable slots (`CLIENTENGINE_INTERFACE_VERSION005`, `IClientUser +0x1C0`) drift with Valve builds → pin the
  manifest `version`, verify sha, feature-flag; a bad slot = SIGSEGV in a *separate* host process (why GN made it a subprocess).
- `lsteamclient` ABI per Proton build → must live in each layer wcp; Proton-10 layers out of scope.
- Two logons: live-service titles that "do not tolerate the tug-of-war" (our own note) may still misbehave even with the
  playing rules — keep SteamLite selectable per title.
- +~60–80 MB RSS in the app process (37.5 MB lib + client heap) on top of the guest.
- OS-type patch (GN) is in-memory patching of Valve's code — only if logon/app-info proves to need it.
- Fixed ports 57343/57344 — one AppSteam game at a time; stale host from a crash must be reaped before launch.
- VAC prior is negative (§4); do not market AppSteam as VAC until proven.

---

## 6. Go / no-go

**GO — bounded spike (P0 + P1 + P4 non-VAC), gated on: (a) our Proton-11 layer builds lsteamclient, (b) the bootstrap host
reaches `Steam_BLoggedOn=1` with our engine's refresh token while the engine stays ONLINE, (c) one non-VAC online title
plays with the drawer Friends tab on the app session.** The legally clean Valve-CDN sourcing removes the blocker that
made this a "someday" in `STEAM_RUST_ENGINE_PLAN.md §Phase 3` (stretch). **NO-GO on any SteamLite retirement** until the
§4 CS:S probe passes on two secured servers.

## Appendix — files read (absolute)
- `/home/claude-user/bl-wt-steam-vac/re/{GAMENATIVE_VAC_RE,WINNATIVE_VAC_RE,SYNTHESIS_OWN_STEAM_AGENT_PLAN,STEAM_WINE_LAYER_GAP}.md`; `/home/claude-user/bannerlators/docs/STEAM_RUST_ENGINE_PLAN.md`
- GN: `/home/claude-user/gamenative-today/app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java`, `…/app/gamenative/SteamBootstrap.kt`, `…/utils/launchdependencies/BionicSteamAssetsDependency.kt`, `…/service/SteamService.kt`, `…/service/SteamOverlayClient.kt`, `…/ui/screen/xserver/XServerScreen.kt`, `THIRD_PARTY_NOTICES`, `app/src/main/jniLibs/arm64-v8a/libsteambootstrap.so` (strings/exports only)
- WN: `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-bootstrap/src/steam_bootstrap.cpp`, `…/include/steam_iface.h`, `…/wn-libsteamclient/src/tcp_services.cpp`, `…/feature/stores/steam/wnsteam/{WnSteamAssetsInstaller,WnWineEnvVars}.kt`
- Ours (read-only): `/home/claude-user/bannerlators/app/src/main/cpp/bl-steam-client/rust/src/wine_bridge.rs`, `…/java/com/winlator/star/store/{RealSteamLauncher.java,SteamRepository.java,InGameFriendsSource.kt,SteamLiteComponent.kt,SteamSessionManager.kt,blsteam/BlSteamSession.kt}`, `…/XServerDisplayActivity.java`, `…/ui/screens/LaunchMethodSheet.kt`
- Upstream: `ValveSoftware/Proton@proton_11.0 lsteamclient/unixlib.cpp, steam_helper/steam.c`; `GameNative/proton-wine@proton_11.0-2 lsteamclient/unixlib.cpp`, PR #34; `The412Banner/proton-wine@proton_10.0_add_steam`
- CDN artifacts (scratchpad only): Valve `bins_androidarm64_linuxarm64.zip.a0be739e…`, GN `steam-androidarm64-20260709.tzst`, `lsteamclient-arm64ec-proton11.tzst`, `steamclient-dlls-20260619.tzst`, `steam-proton11.exe`
