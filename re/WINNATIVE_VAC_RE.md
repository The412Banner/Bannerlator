# WinNative Real-Steam / VAC Path — Reverse-Engineering Report

**Target:** `/home/claude-user/winnative-today` — detached read-only worktree, `origin/main @ 2bcd0a3`
(2026-08-27, WinNative-Emu/WinNative). GPL-3.0. Read-only RE — nothing modified or built.
Cross-referenced decompile available at `/home/claude-user/winnative-re/` (not needed; source was clear).

---

## 1. Bottom line on the VAC question (read this first)

**WinNative has *two different* Steam-online architectures, and the honest VAC answer is different for each.**

- **"Plan W" (the DEFAULT today — `wn_plan_w` defaults to `true`, `PrefManager.kt:179`)** does **not** emulate the
  Steam client at all. It ships Valve's **genuine, proprietary** `steamclient64.dll` (25.7 MB), `steam.exe`,
  and `steamservice.exe/.dll` **bundled inside the APK**, deploys them into the container's
  `C:\Program Files (x86)\Steam\`, and runs the real Valve client under Wine, **auto-logged-in** with a real
  refresh token that WinNative's own Rust CM client minted. The game keeps its **original genuine Valve
  `steam_api64.dll`** (restored from `.orig`, `XServerDisplayActivity.java:6613`, `:10321-10362`). In this
  mode `GetAuthSessionTicket` is served by Valve's real client against a real CM session, so it produces a
  **genuine, Valve-signed auth-session ticket with a real gameconnect token** — the kind a VAC-secured server
  *does* accept at the ticket-authentication layer. In other words, at the **auth/ownership layer this is a
  real Steam client and can genuinely authenticate to VAC-secured servers.**

- **BUT** "passing VAC" fully also requires the VAC **anti-cheat module** (downloaded by `steamservice`,
  injected into the game process, scans memory) to initialize and report clean. **WinNative contains ZERO
  anti-cheat / VAC-module / EAC / BattlEye code of its own** (whole-tree sweep: the only `VAC` token in the
  codebase is a local `app_vac_banned` boolean set, `runtime_state.h:47`, `isteam_stubs.cpp:524`). It relies
  entirely on Valve's bundled `steamservice`/`steamclient` to do whatever they do. Running an x86 VAC module
  under FEX/box64 translation on ARM is exactly the unproven, historically-failing step, and nothing in the
  source demonstrates an end-to-end VAC-secured gameplay session succeeding.

- The **other** path — **"Bionic bridge" (non-Plan W, the fallback)** — is the one the prior recon described,
  and for that path the prior recon is **correct**: it swaps WinNative's own `steam_api64.dll` bridge into the
  game, forwards ~everything to **gbe_fork/Goldberg** (fake local auth), and only redirects **matchmaking** to
  a from-scratch reimplemented `libsteamclient.so`. Its `BeginAuthSession` returns a **synthetic OK**
  (`isteam_stubs.cpp:345-357`), `BIsVACBanned` reads local state (`:520-524`), auth-session tickets are
  fabricated (`:218-282`), and **no gameconnect tokens exist anywhere**. This path **cannot** pass VAC — a
  genuine VAC server would reject the fabricated client ticket. What it *does* achieve is **real Steam
  lobbies** (create/join/list/chat via genuine Steam MMS servers) — not VAC.

**Net verdict:** WinNative's *default* path can genuinely **authenticate** to VAC-secured servers because it is
literally Valve's real client running under Wine (not an emulator) — but it does so by **bundling Valve's
proprietary binaries** (the license landmine), and it adds **no** anti-cheat capability, so whether the VAC
scanner actually runs under ARM/Wine and the server accepts the full session is unproven by the code. The
fallback bridge path fakes auth and definitively cannot pass VAC; its real capability is Steam **lobby**
matchmaking, not VAC dedicated servers. The single most load-bearing thing WinNative does that we'd need to
replicate is **"headless-launch Valve's genuine steamclient under Wine, auto-logged-in with a token our own CM
client minted"** — and the only reason it works is the bundled proprietary binaries.

---

## 2. Architecture

### 2.1 Three mutually-exclusive launch modes

Selected in `app/src/main/runtime/display/XServerDisplayActivity.java`:

| Mode | Gate | Steam client that runs | Auth reality |
|---|---|---|---|
| **Plan W** (default) | `isBionicSteamEnabledForShortcut()` true **and** `wnPlanW==true` | Genuine Valve `steamclient64.dll` in-Wine, real CM login | **Real** tickets |
| **Bionic bridge** | bionic true **and** `wnPlanW==false` | Reimpl `libsteamclient.so` + gbe_fork | **Fake** auth, real lobbies |
| **ColdClient** | `isColdClientEnabledForShortcut()` | Goldberg `steamclient_loader` | Fully offline emu |
| (Real desktop Steam) | `isRealSteamLaunchEnabledForShortcut()` — **hard-returns `false`** (`XServerDisplayActivity.java:4302-4304`) | dead in this build | — |

- Master gate `isBionicSteamEnabledForShortcut()` (`XServerDisplayActivity.java:4306-4339`): false if `useColdClient`;
  true if `launchBionicSteam` explicitly set; otherwise **auto-promotes to true when `wnPlanW` is on AND a
  refresh token AND `steamId64 > 0` are present** (`:4318-4337`).
- Sub-mode chosen by `PrefManager.getWnPlanW()` — **defaults `true`** (`PrefManager.kt:179` → `getBoolean("wn_plan_w", true)`).
- The staging split is the crux (`XServerDisplayActivity.java:6612-6621`):
  ```
  if (wnPlanWActive) { restoreSteamApiDlls(gameDir);              // keep GENUINE Valve steam_api64.dll
  } else {             installSteampipeBridgeIntoApp(this, gameDir); } // swap in WinNative gbe bridge
  ```
  Plan W also scrubs the `lsteamclient.dll` bridge (`:6594-6605`) and stages the genuine Valve binaries
  (`installPlanWValveSteam/Launcher/SteamService`, `:6638-6648`).

### 2.2 Plan W (DEFAULT) — genuine Valve client under Wine

- **In-Wine launcher** `app/src/main/cpp/wn-steam-launcher/src/main.cpp`, staged into the prefix as
  `C:\Program Files (x86)\Steam\steam.exe` (`WnSteamAssetsInstaller.kt:391-426`). Launched with argv
  `steam.exe "<gameExe>" <extraArgs>` (`XServerDisplayActivity.java:9340-9343`).
- It `LoadLibraryEx`s the **genuine** `steamclient64.dll` (`main.cpp:964-1031`), then drives Valve's *private*
  client vtables: `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` (`:1062`) →
  `GetIClientUser("CLIENTUSER_INTERFACE_VERSION001")` (`:1089`) → `SetLoginToken(token)` (`:1105`) →
  `LogOn(steamID)` (`:1128`) → polls `Steam_BLoggedOn` + callbacks `101 SteamServersConnected` (`:1148-1192`)
  → `IClientAppManager::LaunchApp` to start the game (`:1338`, `CreateProcessA` fallback `:451-480`).
- Env it sets (`main.cpp:923-932`): `SteamPath`, `SteamAppId`/`SteamGameId`, `SteamUser`, `SteamClientLaunch=1`,
  `SteamNoOverlayUIDrawing=1`, and **`Steam3Master=127.0.0.1:27036`** (the standard local Steam IPC port that a
  genuine `steam_api64.dll` connects to).
- Registry seed so the game's genuine `steam_api64.dll` finds the running client
  (`seed_active_process_registry`, `main.cpp:146-212`): `HKCU\...\ActiveProcess\{SteamClientDll,SteamClientDll64,
  ActiveUser,pid,Universe=1}`, `...\Apps\<appid>\{Installed=1,Running=1}`, `SteamPath`/`SteamExe`.
- Ready/teardown are driven by the log file `C:\wn-launcher.log`, tailed by `WnLauncherStatusTailer.kt`
  (phase strings `:161-181`, launch-complete `:112-134`, 35 s watchdog `:201`). Teardown =
  `wn-steam-launcher/clean_shutdown.cpp` (sentinel `C:\wn-launcher.shutdown` polled `:262-272`;
  `teardown()` `:150-246` = WM_CLOSE game → exit-cloud-sync → `Steam_LogOff` → release user/pipe).
  Android defers the JavaSteam resume by `WN_PLANW_REAP_OFFLINE_MS` so Steam reaps `games-played` before
  reconnect (`SteamServiceConnection.kt:403-440`), else next launch hits `AlreadyRunning 0x10`.

### 2.3 Bionic bridge (non-Plan W) — gbe_fork + reimplemented libsteamclient

The game loads **WinNative's** `steam_api64.dll` (`app/src/main/cpp/wn-steamapi-bridge/`, a MinGW PE).

- `SteamAPI_Init` → `gbe_init()` → `LoadLibraryA("original_steam_api64.dll")` (= gbe_fork/Goldberg, the
  bundled prebuilt) and calls its `SteamAPI_Init` (`steam_api_bridge_lifecycle.c:95-124`). **The bulk of the
  API forwards to gbe.**
- `SteamClient()` gets gbe's `ISteamClient`, then **one-shot patches vtable slots 10 & 11**
  (`GetISteamMatchmaking`, `GetISteamMatchmakingServers`) to thunks that return WinNative's own interfaces
  (`hook_gbe_matchmaking_slots`, `steam_api_bridge_lifecycle.c:197-223`, `:225-243`).
- Those thunks call `get_our_matchmaking()` (`steam_api_bridge_overrides.c:228-244`), which loads
  `steamclient64.dll` (`resolve_steam_client_locked`, `:85-154`) — in this mode that resolves to **Proton's
  `lsteamclient.dll`** which bridges PE→unix over loopback TCP to WinNative's reimplemented `libsteamclient.so`
  (Steam3Master `127.0.0.1:57343`, SteamClientService `:57344`; `tcp_services.cpp:219-220`).
- The reimplemented `libsteamclient.so` (`app/src/main/cpp/wn-libsteamclient/`, CMakeLists.txt:1-10 —
  "an open-source libsteamclient.so we build ourselves ... backed by the open-source Rust wnsteam CM client")
  serves matchmaking from the Rust client: `ISteamMatchmakingStub::RequestLobbyList → wn_cm_lobby_get_list`
  (`isteam_stubs.cpp:2492`), `CreateLobby → wn_cm_lobby_create` (`:2563`), `JoinLobby → wn_cm_lobby_join`
  (`:2598`), plus set-data/chat/invite/owner — **genuine Steam MMS lobbies.** The internet server **browser**
  (`ISteamMatchmakingServersStub`, `:2392-2439`) is a **dead no-op** (every `Request*ServerList` returns a
  fake handle `1`, `GetServerCount`→0, pings→-1) — dedicated-server browsing does not work.

### 2.4 The Rust CM client (`app/src/main/cpp/wn-steam-client/rust/`)

A from-scratch Steam3 CM protocol client (`libwnsteam.so`). It:
- Does channel encryption, `CLIENT_LOGON`, PICS, `CLIENT_LICENSE_LIST`, depot keys, cloud, user-stats,
  friends/persona, and full MMS lobby ops (`emsg.rs:5-72`, `cm_client.rs`).
- **Logs in** via the modern CAuthentication service: `BeginAuthSessionViaCredentials`/`ViaQr` +
  `PollAuthSessionStatus` → refresh/access token (`auth_session.rs:124-324`, `pb/cauthentication.rs`).
  Credentials come from Kotlin JNI (`jni.rs` `nativeStartLoginWithCredentials/Qr`); the token is persisted
  Kotlin-side (`SteamServiceLogin.kt:176-177`) and re-fed via `LogonWithRefreshToken`
  (`cm_client.rs:329-345`).
- **Mints only two ticket types**: app **ownership** tickets (`CMsgClientGetAppOwnershipTicket`,
  `cm_client.rs:674-688`, cached in `ticket_cache.rs`) and **encrypted app tickets**
  (`CMsgClientRequestEncryptedAppTicket`, `:690-708`). **It does NOT mint auth-session / GC tickets** — an
  exhaustive grep of the Rust tree finds **no** `CMsgClientGameConnectTokens`, no `CMsgClientAuthList`, no
  `gc_token`, no `GetAuthSessionTicket`, no `AuthenticateUserTicket`, no `VAC`.
- Its matchmaking/lobby API is exposed only over the C-ABI to the in-Wine client (`cm_bridge.rs`
  `wn_cm_lobby_*`), **not** to Kotlin — so it feeds the reimplemented `libsteamclient.so`, giving games real
  lobbies in bridge mode.
- In **Plan W** the Rust client's job is narrower: it performs the login and hands the **refresh token** to the
  genuine Valve client (via `WN_STEAM_TOKEN` env / JNI), and feeds cached ownership tickets. The genuine Valve
  `steamclient64.dll` then does its own real CM logon and produces the real auth-session tickets.

---

## 3. Exact real-vs-faked interface routing

### 3.1 Plan W (default): the game talks to the GENUINE Valve client

The game runs its **original Valve `steam_api64.dll`** (WinNative's bridge is *not* installed;
`restoreSteamApiDlls`, `XServerDisplayActivity.java:6613`, `:10321-10362`). Every interface —
`ISteamUser::GetAuthSessionTicket`, `ISteamGameServer`, `ISteamMatchmaking`, `ISteamFriends`,
`ISteamNetworking*` — is served by Valve's genuine `steamclient64.dll` over the standard Steam IPC
(`Steam3Master=127.0.0.1:27036`), which is logged into **real** Steam CM. **Nothing is faked at the interface
layer.** WinNative's only involvement is (a) providing the login token and (b) launching/registering the
client. This is why the ticket path is genuine.

### 3.2 Bionic bridge (non-Plan W): surgical split, proven at the linker level

`app/src/main/cpp/wn-steamapi-bridge/steam_api_bridge.def` is the authoritative export map:
**1180 exports forward verbatim to `original_steam_api64.dll` (gbe_fork/Goldberg); ~76 are local, and they are
exclusively matchmaking + lifecycle.**

| Interface / call | Routed to | Real? | Evidence |
|---|---|---|---|
| `ISteamMatchmaking_*` (Create/Join/RequestLobbyList/SetLobbyData/SendLobbyChatMsg/… — 37 calls) | WinNative reimpl `libsteamclient.so` → Rust `wn_cm_lobby_*` → genuine Steam **MMS** | **REAL** (real lobbies) | `.def` (local), `overrides.c:247-625`, `isteam_stubs.cpp:2455-2960` |
| `ISteamMatchmakingServers_*` (internet/LAN/friends/history server lists — 18 calls) | WinNative reimpl | **DEAD stub** (returns fake handle, 0 servers) | `isteam_stubs.cpp:2392-2439` |
| `SteamClient::GetISteamMatchmaking/Servers` (slots 10/11) | vtable-patched to the above | REAL(lobbies)/dead | `lifecycle.c:197-223`, `steam_api_bridge_steamclient.c:19-43` |
| `ISteamUser::GetAuthSessionTicket` / `GetAuthTicketForWebApi` | **gbe_fork** (transparent `self`-vtable thunk) | **FAKE** | `flat.c:4320-4325`; `.def` → `original_steam_api64` |
| `ISteamUser::BeginAuthSession` / `EndAuthSession` / `CancelAuthTicket` | **gbe_fork** | **FAKE** | `.def` (all `= original_steam_api64.*`) |
| `ISteamUser::GetEncryptedAppTicket` / `RequestEncryptedAppTicket` | **gbe_fork** | FAKE (Rust can supply real encrypted ticket, but game path hits gbe) | `.def` |
| `ISteamGameServer::GetAuthSessionTicket` / `BeginAuthSession` | **gbe_fork** | **FAKE** | `flat.c:1038,1044`; `.def` |
| `ISteamNetworking_*` P2P (SendP2PPacket/ReadP2PPacket/AcceptP2PSession…) | **gbe_fork** | FAKE/local (gbe P2P, not Valve relay) | `flat.c:2468-2510`; `.def` |
| `ISteamApps::BIsVACBanned` | **gbe_fork** | local state | `flat.c:57`; `.def` |
| `ISteamUserStats`, `ISteamFriends`, `ISteamApps`, `ISteamRemoteStorage`, `ISteamInput`, `ISteamUGC`, … (everything else) | **gbe_fork** | FAKE/emulated | `.def` (1180 forwards) |

**The reimplemented `libsteamclient.so`'s own auth (reached by the matchmaking path / any consumer of the
reimpl), for completeness — all fake:**
- `ISteamUser::BeginAuthSession(const void* /*ticket*/, …)` → posts a synthetic `ValidateAuthTicketResponse`
  with `k_EAuthSessionResponseOK` and **returns OK ignoring the ticket entirely** — log string literally says
  `"OK (synthetic validation)"` (`isteam_stubs.cpp:345-357`).
- `ISteamUser::GetAuthSessionTicket` wraps a genuine cached **ownership** ticket in a **fabricated** 24-byte
  GC/session header (`ConnectionID`/timestamp/`ConnectionCount` = a local handle + zeros, **not** a real
  gameconnect token; `:243-248`), or a fully synthetic `WNAT` blob if no ownership ticket (`:254-262`).
- `ISteamGameServer::GetAuthSessionTicket` → `0` (`k_HAuthTicketInvalid`); `BeginAuthSession` → `5`
  (`k_EBeginAuthSessionResultServerNotConnectedToSteam`) — **the reimpl cannot act as a VAC gameserver**
  (`:3349-3356`).
- `BIsVACBanned` reads local `app_vac_banned` set (`:520-524`).

**Why the bridge path cannot pass a real VAC server:** the ticket the game presents comes from gbe_fork (or,
for the reimpl, a fabricated GC header over a real ownership ticket). A genuine VAC-secured gameserver validates
the client's ticket by calling `AuthenticateUserTicket` against **Valve's** backend, which checks the
**gameconnect token** signature/session. WinNative never obtains gameconnect tokens (`CMsgClientGameConnectTokens`
is absent from the entire tree), so any ticket it presents fails real server-side validation → the player is
rejected. Two WinNative peers can "validate" each other (both fake-accept), which is enough for Goldberg-style
LAN/P2P lobby play, but not for VAC-secured servers with genuine players.

---

## 4. How it sources the genuine Valve binaries

**Bundled inside the APK** (not downloaded from Valve's CDN, not extracted from a user's install):

- `app/src/main/assets/wnsteam/bionic/valve-steam-x86_64.tzst` — a 16 MB zstd tarball authored by user
  `max/max` (2026-05-21) containing Valve's **proprietary** binaries: `steamclient64.dll` (**25,739,928 bytes**
  — a Goldberg reimpl would be ~100 KB), `steamclient.dll` (21 MB), `Steam.dll`, `Steam2.dll`, `tier0_s64.dll`,
  `tier0_s.dll`, `vstdlib_s64.dll`, `vstdlib_s.dll`.
- Loose bundled binaries: `steam.exe` (1.06 MB), `steamservice.exe` (2.95 MB), `steamservice.dll` (3.72 MB),
  `wn-steam-helper.exe` (176 KB), plus Valve's signed SteamService manifests
  `service_current_versions.vdf` / `service_minimum_versions.vdf` (carrying Valve's RSA `kvsignatures`).
- **Placement code** (`app/src/main/feature/stores/steam/wnsteam/WnSteamAssetsInstaller.kt`):
  - `installPlanWValveSteam()` (`:307-389`) extracts the tarball and copies `steamclient64.dll` (and
    `Steam.dll`/`Steam2.dll`) into `<container>/.wine/drive_c/Program Files (x86)/Steam/`, and
    `tier0_s64.dll`/`vstdlib_s64.dll` into `system32` (32-bit pair into `syswow64`).
  - `installPlanWLauncher()` (`:391-448`) copies `steam.exe` into the Steam dir.
  - `installPlanWSteamService()` (`:136-198`) stages `steamservice.exe/.dll` + the two `*_versions.vdf` into
    `…/Steam/bin`.
- The **emulated** path's binaries are also bundled but distinct: `assets/steampipe/steamclient64.dll` (111 KB)
  + `assets/wnsteam/steampipe/{steam_api64.dll, original_steam_api64.dll (7.3 MB gbe backend)}`, injected into
  the game dir by `installSteampipeBridgeIntoApp()` (`:532-584`). The Wine-side GPL `lsteamclient` reimpl ships
  as `wnsteam/lsteamclient-{arm64ec,x86_64}.tzst`.
- **No runtime CDN fetch of the client**: `cdn_client.rs`/`depot_downloader.rs`/`content_manifest.rs` download
  only **game content depots** from `*.steamcontent.com`; grep for Steam-client app/depot IDs (769/753/1006/1007,
  `steam_client_win`, bootstrapper manifests) across the downloader is **empty**.

**License-risk conclusion:** the default (Plan W) path works *because* Valve's proprietary `steamclient64.dll`,
`steam.exe`, `steamservice.*`, `tier0_s*`, and `vstdlib_s*` are **redistributed inside the APK** and copied into
each prefix. This is precisely the redistribution WinNative's own memory notes flag as "Max's blobs, provenance
unclear." It is not sourced from the user's own Steam install.

---

## 5. What we can legally reuse vs. what we'd have to build (for Bannerlator)

Bannerlator is GPL-lineage; WinNative is GPL-3.0, so the **source WinNative wrote** is transliterable. The
**proprietary Valve binaries it bundles are not** — that's the wall.

**Reusable (WinNative's own GPL source — safe to transliterate):**
- The **Rust CM client design** (`wn-steam-client/rust/`): CAuthentication credentials/QR login + refresh-token
  logon, PICS/licenses/depot-keys, ownership + encrypted-app tickets, and the **MMS lobby** protocol
  (create/join/list/data/chat) — this is our own clean-room-ish CM stack and gives **real Steam lobbies**.
  Bannerlator already has JavaSteam, which covers the same CM surface (memory's Phase-0 plan: JavaSteam can
  itself mint the tokens).
- The **reimplemented `libsteamclient.so`** (`wn-libsteamclient/`) + the **PE↔unix loopback TCP bridge**
  (`tcp_services.cpp`, Steam3Master/SteamClientService) + the **steam_api64 bridge design**
  (`wn-steamapi-bridge/`: forward-to-gbe `.def` + surgical vtable-slot redirect of just matchmaking). This is
  the whole "keep gbe for identity, splice in real lobbies" trick — cleanly reusable and it's the *shippable*
  ceiling (real lobbies, no proprietary binaries).
- The **launch wiring / env block / registry seeding / lifecycle** (`wn-steam-launcher/main.cpp`,
  `WnWineEnvVars.kt`, `WnSteamAssetsInstaller.kt`, `WnLauncherStatusTailer.kt`, `clean_shutdown.cpp`,
  the reap-offline handoff) — the token-handoff and clean-shutdown/reap logic are the non-obvious, valuable
  parts and are pure WinNative source.

**NOT reusable — we'd have to source ourselves (the Valve binaries):**
- The genuine `steamclient64.dll` (25.7 MB), `steam.exe`, `steamservice.exe/.dll`, `tier0_s*`, `vstdlib_s*`.
  These are what make Plan W's tickets real. We **cannot** bundle them (Valve SSA / redistribution). To match
  Plan W's VAC-ticket capability legally, Bannerlator would have to obtain these from the **user's own Steam
  install / Valve's own bootstrapper at runtime on-device**, never bake them into the APK — the exact approach
  our memory's `steam_vac_multiplayer_plan` already reaches for ("source Steam files legally; assets DOWNLOADED
  not baked"). WinNative took the shortcut (bundle) that we've decided we can't take.

**What this means for our roadmap (honest framing):**
- If the goal is **real Steam lobbies / Goldberg-style P2P** — WinNative's *bridge* path is a fully reusable,
  proprietary-binary-free blueprint. Ship that.
- If the goal is **VAC-secured servers with real players** — the only mechanism that authenticates is running
  Valve's genuine client (Plan W). We can replicate the *wiring* from GPL source, but we must supply the Valve
  binaries the legal way, and even then VAC's anti-cheat module under ARM/Wine/FEX is unproven — WinNative adds
  nothing there and there is no in-source proof it works end-to-end. Phase-0 on-device smoke test remains the
  right next step before investing.

---

## Appendix — load-bearing files (all absolute)

- Mode gate / staging split: `/home/claude-user/winnative-today/app/src/main/runtime/display/XServerDisplayActivity.java`
  (`:4302-4339` gates, `:6612-6621` genuine-vs-bridge split, `:6638-6648` Plan W staging, `:7314-7345`/`:9340-9343`
  env+argv, `:10321-10362` `restoreSteamApiDlls`)
- Plan W default toggle: `/home/claude-user/winnative-today/app/src/main/feature/stores/steam/utils/PrefManager.kt:179`
- In-Wine genuine launcher: `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-launcher/src/main.cpp`
  (`:923-932` env, `:146-212` registry, `:964-1031` load genuine DLL, `:1089-1192` real login, `:1338` LaunchApp),
  teardown `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-launcher/clean_shutdown.cpp`
- steam_api64 bridge (non-Plan W): `/home/claude-user/winnative-today/app/src/main/cpp/wn-steamapi-bridge/`
  (`steam_api_bridge.def` 1180 gbe-forwards, `steam_api_bridge_lifecycle.c:95-243`, `steam_api_bridge_overrides.c:85-244`,
  `steam_api_bridge_flat.c:1038/1044/2468/4320`, `steam_api_bridge_steamclient.c`)
- Reimpl libsteamclient (matchmaking backend + fake auth): `/home/claude-user/winnative-today/app/src/main/cpp/wn-libsteamclient/`
  (`CMakeLists.txt:1-45`, `src/isteam_stubs.cpp:218-372/520-524/2392-2960/3349-3356`, `src/tcp_services.cpp:219-220`)
- Rust CM client: `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-client/rust/src/`
  (`auth_session.rs`, `cm_client.rs`, `cm_bridge.rs`, `ticket_cache.rs`, `emsg.rs`, `pb/cauthentication.rs`,
  `pb/cmsg_client_get_app_ownership_ticket.rs`) — **no gameconnect-token / authlist code anywhere**
- Android bootstrap (non-Plan W in-process): `/home/claude-user/winnative-today/app/src/main/cpp/wn-steam-bootstrap/src/steam_bootstrap.cpp`
- Genuine-binary bundling + placement: `/home/claude-user/winnative-today/app/src/main/feature/stores/steam/wnsteam/WnSteamAssetsInstaller.kt`
  (`:136-198`, `:307-389`, `:391-448`, `:532-584`) + assets `/home/claude-user/winnative-today/app/src/main/assets/wnsteam/bionic/valve-steam-x86_64.tzst`
- Token store / handoff lifecycle: `/home/claude-user/winnative-today/app/src/main/feature/stores/steam/service/SteamServiceLogin.kt:176-177`,
  `.../service/SteamServiceConnection.kt:403-440`
