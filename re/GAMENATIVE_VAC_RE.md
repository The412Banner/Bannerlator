# GameNative Real-Steam / VAC Reverse-Engineering Report

**Target:** utkarshdalal/GameNative (app id `app.gamenative`, "Pluvia")
**Worktree:** `/home/claude-user/gamenative-today`, detached at `upstream/master` @ `1ad70ae5` (2026-08-25)
**License:** GPL-3.0 (application) + a proprietary, source-withheld native blob (see §4)
**Scope:** READ-ONLY RE. Nothing modified or built.
**All file:line refs are on `upstream/master` @ `1ad70ae5` unless a branch is named.**

---

## 1. BOTTOM-LINE VAC VERDICT

**GameNative does NOT provide a demonstrated, working VAC-secured-multiplayer capability. There is zero VAC / anti-cheat code anywhere in the tree (all refs).** What GN actually ships is a mode that runs a **genuine, logged-in Valve Steam client next to the game**, so the game's Steamworks calls hit *real* Steam services. That gets you real ownership auth, real friends/lobbies/invites, and thus real multiplayer on **non-VAC / community / P2P** servers. It does **not** establish that Valve Anti-Cheat itself initializes or passes.

Precise breakdown:

| Capability | Verdict | Why |
|---|---|---|
| Log the *real* Valve client into real Steam CM on-device | **YES** | Genuine `libsteamclient.so` (bionic mode) or `steamclient64.dll` (real-steam mode) is run and logged in with the user's real refresh token. |
| Game's `GetAuthSessionTicket` / matchmaking / lobbies / rich-presence reach real Steam | **YES (by delegation)** | The game's Steamworks calls are bridged to the genuine running client — GN does not fake them. Friends/invites/joins are wired (`SteamOverlayClient.kt`, `GameInviteHandler.kt`). |
| Server-side `BeginAuthSession` ownership/session validation succeeds | **Very likely YES** | Those tickets are minted by the *genuine* client, not by GN. |
| **VAC anti-cheat verification on a VAC-secured server** | **UNPROVEN / effectively NO** | No VAC module handling exists. The topology (native **ARM** steamclient + **Wine/box64/FEX-emulated x86** game) is not one VAC supports; VAC has no ARM-Android module for these titles. Expect "VAC unable to verify game session" → kicked from VAC servers, even when session-auth itself passed. |

**Honest one-liner:** GN reaches *real Steam services* and can do *real online multiplayer* on non-VAC servers by running the genuine client; it has **no VAC mechanism** and its architecture is actively hostile to VAC ever loading. Do not represent GN as "passing VAC."

Supporting evidence for the negative:
- No hits for `VAC`, `anti-cheat`, `getAuthSessionTicket`, `SteamAuthTicket`, `BeginAuthSession`(as the game-server ticket API) anywhere across **all** `upstream/*` refs. (The only `beginAuthSession*` hits are the *login* flow `beginAuthSessionViaCredentials/QR`.)
- `SteamService.kt:3592-3593` **removes** the gameserver handlers from GN's own JavaSteam client: `removeHandler(SteamGameServer::class.java)` / `removeHandler(SteamMasterServer::class.java)`. GN's app-side client does no gameserver auth at all.
- The only ticket GN mints itself is an **EncryptedAppTicket** (ownership proof), and it is gated **OFF** for the real/bionic paths — it is fetched *only* for the Goldberg path: `XServerScreen.kt:4039` guards it with `!container.isLaunchRealSteam && !container.isLaunchBionicSteam`.

---

## 2. WHICH BRANCH HOLDS THE REAL-STEAM IMPL + STATE

**The real-Steam ("bionic Steam") implementation is already MERGED and shipping on `master`** as a per-container experimental toggle (`isLaunchBionicSteam` / `isLaunchRealSteam`, exposed in `GeneralTab.kt:379,390-395`). This is a change from prior recon (which expected branch-only) — the current, most-complete source is **master itself**.

Branch map (all measured against `master` @ `1ad70ae5`):

| Branch | Head / date | State | What it is |
|---|---|---|---|
| **`master`** | `1ad70ae5` 2026-08-25 | **Canonical, shipping (experimental toggle)** | Full bionic-Steam path. Key files last substantively updated by **PR #1683** `268816f5` (2026-07-09) "Fixed bionic Steam regressions, improved stability, added lsteamclient for all proton builds". Study this. |
| `more-bionic-steam` | `158b60a8` 2026-06-21 | Stale precursor, NOT merged (4 ahead / 261 behind) | Older bootstrap (last touched `4f057b80` 2026-06-02, PR #1505). Superseded by master. |
| `aok-bionic-steam` | `6ce1297c` 2026-06-19 | Stale precursor, NOT merged (2 ahead / 275 behind) | "Updated bootstrap to support more versions." Superseded. |
| `allow-steamclient-install` | `8ceae86d` 2026-06-08 | NOT merged (1 ahead / 307 behind) | Adds *installing the full Windows Steam client after bionic is enabled* (+65 lines in `BionicProgramLauncherComponent.java` vs its base). A real-steam-full-client variant. |
| `steam-android-client` | `5c15f9b5` 2026-05-13 | NOT merged (behind master) | Early "experimental DRM to downloads needed for bionic steam." |
| `attempt-real-steam` | `89d7bc11` 2025-07-21 | **Dead/experimental**, 451 behind | Early attempt; only `steampipe/steam_api*.dll` present, no bootstrap. |
| `steamagent` | `db42a184` 2025-12-30 | **Dead/experimental**, 451 behind | The GameHub-`SteamAgent.exe`-style approach (`assets/steamagent.tzst`). Abandoned in favor of bionic. |
| `feat/steam-autologin`, `steam-offline-mode`, `gbe-experimental-steamclient`, `fix-max-recursion-steam-dlls` | 2025–2026 | Older/ancillary | Autologin VDF, offline mode, gbe/coldclient controller configs, DLL recursion fix. Mostly folded or superseded. |

**Note on the precursor blob divergence:** the branches' `SteamBootstrap.kt` differs from master by ~425 lines and their compiled `libsteambootstrap.so` blob differs from master's. master's PR #1683 rewrote the bootstrap into the current **host-subprocess** model (§4). The stale branches are of historical interest only; **master is authoritative**.

---

## 3. ARCHITECTURE (the four launch modes)

A container picks exactly one launch mode. The two "genuine Valve code" modes are **Bionic Steam** and **Real Steam**.

```
Mode 0  Goldberg (default)  — assets/steampipe/steam_api{,64}.dll swapped in; steam_settings/*. FAKE. No real services.
Mode 1  ColdClient (isUseLegacyDRM) — gbe_fork coldclient loader + steamclient_loader_x64.exe. FAKE (emulated).
Mode 2  Real Steam (isLaunchRealSteam) — runs the genuine WINDOWS steam.exe + steamclient64.dll under Wine/Proton (x86 emulated). REAL services.
Mode 3  Bionic Steam (isLaunchBionicSteam) — runs the genuine ANDROID/arm64 libsteamclient.so natively; Wine game bridges to it. REAL services. ← newest/preferred
```

`SteamService.getLaunchExecutable()` (`SteamService.kt:1396-1401`) returns the sentinel `"steam"` for both real modes so the launch pipeline is not blocked.

### 3.1 Mode 3 — Bionic Steam (the load-bearing design)

Two genuine-Valve processes, bridged over loopback + an abstract socket:

```
┌─ Android app process (app.gamenative) ───────────────────────────────┐
│  BionicProgramLauncherComponent.execGuestProgram()                     │
│    └ if isLaunchBionicSteam:                                            │
│        addRealSteamEnvVars(...)        (BionicProgramLauncherComponent.java:498) │
│        bootstrapNativeSteamClient(...) (…:563)                          │
│           └ SteamBootstrap.start(...)  → spawns HOST SUBPROCESS:        │
│                                                                        │
│   ┌─ HOST SUBPROCESS: libsteambootstrap.so (proprietary blob, §4) ──┐  │
│   │  argv: [bin, <appId>, <libsteamclientPath>, <sb_host_ready>]    │  │
│   │  dlopen()s the GENUINE bionic libsteamclient.so, logs it in     │  │
│   │  with SB_REFRESH_TOKEN, listens on 127.0.0.1:57343 / :57344     │  │
│   │  and abstract socket "gamenative-steam-overlay".                │  │
│   └─────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
        ▲ loopback IPC (Steam3Master / SteamClientService)
        │
┌─ Wine/Proton subprocess (the GAME) ─────────────────────────────────┐
│  game.exe → real steam_api64.dll → Proton lsteamclient.dll (PE)      │
│    → unix steamclient.so (WINESTEAMCLIENTPATH{,64})                  │
│      → connects to the bionic libsteamclient.so IPC peer above       │
└──────────────────────────────────────────────────────────────────────┘
```

- **Launch wiring / mode switch:** `BionicProgramLauncherComponent.java:325-330`.
- **Env block (group A/B/C):** `addRealSteamEnvVars()` `BionicProgramLauncherComponent.java:498-549`:
  - A. `WINESTEAMCLIENTPATH64 = <steamRootLinux>/linux64/steamclient.so`, `WINESTEAMCLIENTPATH = …/linux32/steamclient.so` (where PE `lsteamclient.dll` dlopens the unix bridge). `:504-505`
  - B. bootstrap-gate handshake: `_STEAM_SETENV_MANAGER=1`, `BREAKPAD_DUMP_LOCATION`, `STEAM_BASE_FOLDER=<steamRootLinux>`, `ENABLE_VK_LAYER_VALVE_steam_overlay_1=0`, `SteamOS=1` (forces `IsOverlayEnabled()` true so games open their invite/host UI), `STEAMVIDEOTOKEN=1`; IPC endpoints `Steam3Master=127.0.0.1:57343`, `SteamClientService=127.0.0.1:57344`. `:508-521`
  - C. Wine-side identity: `SteamUser`/`SteamAppUser=<username>`, `SteamClientLaunch=1`, `SteamEnv=1`, `SteamPath=C:\Program Files (x86)\Steam`, `ValvePlatformMutex`, `STEAMID=<steamId64>`, `SteamGameId`+`SteamAppId=<appid>`. `:523-548`
- **Host bring-up:** `bootstrapNativeSteamClient()` `:563-639` resolves `libsteamclient.so` at `imageFs.libDir`, passes the handshake vars + `SB_ACCOUNT`/`SB_REFRESH_TOKEN`/`SB_STEAMID64` into `SteamBootstrap.start()` (`:605-615`), then `SteamBootstrap.prepareApp(appId)` (`:629`) to pre-warm PICS + encrypted-ticket so the launch doesn't stall at "Validating Subscriptions."
- **Game exe launch:** direct, not via steam.exe — `XServerScreen.kt:4500-4511` builds the `C:\...\steamapps\common\<folder>\<exe>` path (contrast Real-Steam at `:4512-4515` which uses `steam.exe -applaunch <id>`).
- **Per-boot prefix prep** (`extractSteamFiles`, `XServerScreen.kt:5885-5955`, bionic branch): deletes stale `config.vdf/loginusers.vdf/local.vdf/steam-token.exe`; extracts `steamclient-dlls-20260619.tzst` (genuine Valve `steamclient.dll` for SteamStub); copies cached `steam.exe`; re-extracts the Proton-matched `lsteamclient` into the prefix (`BionicSteamAssetsDependency.extractLsteamclientIntoPrefix`, `:5936`); writes `HKCU\Software\Valve\Steam\ActiveProcess` reg values (`ActiveUser`, `SteamClientDll{,64}`, `Universe=Public`) `:5938-5953`.
- **Teardown on exit:** `XServerScreen.kt:4620-4635` — `SteamBootstrap.stop()` (SIGTERM the host subprocess, drop the pipe/user) so the next launch gets a fresh session. Also `cleanupBionicSteamAssets()` (`:5974-5985`) removes the injected `lsteamclient.dll` + `libsteamclient.so` when leaving bionic mode.
- **Power pinning:** `PowerManager.kt:1156-1162` pins the `libsteambootstrap.so` process to background cores.

### 3.2 The app↔host control channel and social/multiplayer surface

`SteamOverlayClient.kt` (whole file) is the app's only route into the running genuine client's friends/invite/join engine, over the **abstract-namespace LocalSocket `"gamenative-steam-overlay"`** owned by the host process. Line protocol: `PING/PONG`, `SELF` (steamId + connect string), `LIST` friends (`F <steamid> <personaState> <playingAppId> <rel> <lobbyId> <name>`), `INVITE`, `ACCEPT <lobbyId> <fromSteamId>`, `RPJOIN` (rich-presence join), `POLL` (game-raised overlay requests). `GameInviteHandler.kt` adds a JavaSteam handler for the game-invite protobuf that JavaSteam lacks (`SteamService.kt:3597`). **This is genuine Steam matchmaking/invite/join plumbing** — i.e., real online multiplayer via lobbies and rich-presence connect strings is a first-class, wired feature (limited by VAC as in §1).

### 3.3 Mode 2 — Real Steam (full Windows client under Wine)

- Runs genuine Windows `steam.exe` (`XServerScreen.kt:4512-4515`, `-silent -vgui -tcp -nobigpicture -nofriendsui -nochatui -nointro -applaunch <id>`).
- Auto-login is done by writing **VDF token files** via `SteamTokenLogin` (`XServerScreen.kt:4002-4010`, gated `if (container.isLaunchRealSteam)`). See §6 for formats.
- Full client tree comes from `steam.tzst` extracted per boot (`XServerScreen.kt:5964-5971`).
- This is the environment closest to what VAC targets (real Windows steamclient), but still under Wine+box64/FEX — VAC remains unaddressed and unsupported.

---

## 4. IS THE BOOTSTRAP SOURCE NOW AVAILABLE? — **NO. STILL BLOB-ONLY, DELIBERATELY WITHHELD.** (decisive)

**The bootstrap C source is intentionally excluded from the repo on every ref.** This is the decisive finding: we **cannot transliterate GN's bootstrap** — we must build our own (WinNative's GPL `wnsteam` is our reference instead).

Evidence:
- `.gitignore` (master) contains, with an explicit rationale comment:
  ```
  # Steam bootstrap shim source — withheld out of respect for Valve, since it
  # encodes internal details of Steam's proprietary client that Valve does not publish.
  app/src/main/cpp/steambootstrap/steam_bootstrap.c
  ```
- `app/src/main/cpp/steambootstrap/` contains **only `CMakeLists.txt`** on every candidate ref (master, more-bionic-steam, aok-bionic-steam, allow-steamclient-install, steam-android-client). The CMake references `steam_bootstrap.c` (`add_executable(steambootstrap steam_bootstrap.c)`, defs `SB_HOST_BUILD=1 PRINT_LOG=1`) — but that `.c` is never committed.
- A whole-history scan of all `refs/remotes/upstream/*` for `steam_bootstrap.c` / any `steambootstrap/*.c|cpp|h` returns **nothing**.
- Only the compiled artifact ships: `app/src/main/jniLibs/arm64-v8a/libsteambootstrap.so` (**32,776 bytes** on master; a *different* blob on each stale branch).
- `THIRD_PARTY_NOTICES` §"Steam Client Bootstrap Shim (Source Withheld)" (lines 105-184) states it plainly: authored from scratch, source in `.gitignore`, binary is **Proprietary — all rights reserved by the GameNative maintainers**, aggregated (not derivative) with the GPL-3.0 app. It documents that the shim's knowledge (which exported symbols / proxy-vtable slots of `libsteamclient.so` map to pipe-create, global-user attach, token/login registration, logon, logoff, release, connection-state poll) was obtained by **local static analysis** of an installed `libsteamclient.so`.

### The contract the blob obeys (THIS is our spec) — from `SteamBootstrap.kt`

Note: despite THIRD_PARTY_NOTICES describing a JNI `nativeInit/nativeShutdown` surface (stale wording), **master's shipped model runs the blob as a standalone ELF executable via `ProcessBuilder`** (`SteamBootstrap.kt:135-160`). CMake's `SB_HOST_BUILD=1` builds exactly that host-executable variant (output `libsteambootstrap.so`, run from `nativeLibraryDir`).

- **Invocation** (`prepareApp`, `SteamBootstrap.kt:121-166`): `argv = [ <nativeLibDir>/libsteambootstrap.so, <appId>, <libsteamclientPath>, <sb_host_ready path> ]`; stdout+stderr → `cacheDir/sb_host.log`.
- **Environment** (`SteamBootstrap.kt:143-152`):
  - `HOME` = `<wineprefix>/drive_c/Program Files (x86)` (blob resolves `<HOME>/Steam/config/config.vdf` etc. relative to it)
  - `Steam3Master` = `127.0.0.1:57343`, `SteamClientService` = `127.0.0.1:57344`
  - `SB_ACCOUNT` = Steam login name, `SB_REFRESH_TOKEN` = the JWT-style refresh token from GN's normal JavaSteam login, `SB_STEAMID64` = 64-bit SteamID
  - plus passthrough handshake vars: `_STEAM_SETENV_MANAGER`, `BREAKPAD_DUMP_LOCATION`, `STEAM_BASE_FOLDER`, `ENABLE_VK_LAYER_VALVE_steam_overlay_1`, `STEAMVIDEOTOKEN`, `SteamUser`, `SteamOS` (`BionicProgramLauncherComponent.java:580-594`)
- **Status handshake** (`SteamBootstrap.kt:168-190`): the blob writes `cacheDir/sb_host_ready` with `INIT` → `READY` | `FAILED:<reason>` | `CLOSED`. Kotlin polls up to 60 s.
- **Also required** (`installSqliteCompatLink*`, `SteamBootstrap.kt:192-230`): symlink `libsqlite.so → libsqlite3.so.0` next to `libsteamclient.so` (the bionic client links a sqlite that must expose OpenSSL symbols).
- **Runtime services the blob must stand up** (per THIRD_PARTY_NOTICES + `SteamOverlayClient.kt`): dlopen genuine `libsteamclient.so`; create a Steam pipe; attach global user; register login info/token; kick logon (from `SB_REFRESH_TOKEN`); poll connection state; serve the loopback IPC (57343/57344) for the Wine-side `steamclient.so`; serve the `gamenative-steam-overlay` abstract socket for friends/invite/join; logoff + release on `SIGTERM`.

**Consequence for Bannerlator:** GN's bootstrap is a closed re-implementation of Steam-client-internal vtable/symbol calls. It cannot be lifted. Our own equivalent must be written from scratch (WinNative's `wnsteam` Rust CM client is the GPL reference we own), OR we reproduce the same host-executable + env/socket contract above against a genuine `libsteamclient.so`.

---

## 5. ASSET SOURCING + LEGALITY

Genuine Valve binaries are **downloaded per-container at first launch, not baked into any Proton/APK**, and injected into the prefix each boot. Source: `BionicSteamAssetsDependency.kt` + `XServerScreen.extractSteamFiles`.

- **CDN** (`SteamService.kt:1571-1572`): primary `https://downloads.gamenative.app/<fileName>`, fallback `https://pub-9fcd5294bd0d4b85a9d73615bf98f3b5.r2.dev/<fileName>` (Cloudflare R2). Fetched via `SteamService.downloadFile(fileName=…)`.
- **Files pulled** (`BionicSteamAssetsDependency.kt:32-56, 194-306`):
  - `steam-androidarm64-20260709.tzst` → genuine **Android/arm64 `libsteamclient.so`** (+ sibling libs); extracted into `imagefs/usr/lib/`. Version-gated by `.bionic_steam_version` marker.
  - `steamclient-dlls-20260619.tzst` → genuine Valve Windows `steamclient.dll`/`steamclient64.dll` (for SteamStub/SteamStub-DRM titles); extracted per boot (`XServerScreen.kt:5908-5919`).
  - `lsteamclient-<arch>-proton{9,10,11}.tzst` → the Wine↔native **bridge**: PE `lsteamclient.dll` + its unix-side `steamclient.so`. **ABI is wine-version-locked**, so one archive per Proton (`LSTEAMCLIENT_ARCHIVE_BY_WINE`, `:48-56`). Re-extracted + copied into system32/syswow64 every boot so a Proton switch can't leave a stale ABI (`extractLsteamclientIntoPrefix`, `:145-171`).
  - `steam.exe` / `steam-proton11.exe` (real-steam helper) and `steam.tzst` (full Windows client tree for Mode 2).
  - `steam-token.tzst` → `steam-token.exe` (Wine CryptProtectData helper, Mode 2 only; `PluviaMain.kt:1818-1824`).
  - `cacert.pem`.
- **Licensing shape:** The `lsteamclient` shim is Proton/Valve-origin (Wine-side, redistributable-ish). The Android `libsteamclient.so` and the Windows `steamclient*.dll` are **Valve proprietary redistributables**, not repackaged/modified by GN — they are Valve's own client binaries, merely re-hosted on GN's CDN and injected at runtime into the user's own prefix. GN's stance (THIRD_PARTY_NOTICES + the `.gitignore` note): keep Valve's binaries and the internal-symbol knowledge *out of the GPL source repo*; ship only GN's own GPL app + the aggregated proprietary bootstrap blob, and download Valve's parts. **For Bannerlator this is the legality template to weigh: (a) do not commit Valve binaries to source; (b) fetch Valve's own redistributable client at runtime; (c) any bootstrap/shim we write is our own IP, and if we reference WinNative's GPL `wnsteam` we inherit GPL, not Valve's terms.**

---

## 6. THE JAVASTEAM TICKET ROLE (and the VDF token flow)

**Correction to prior recon:** the current tree has **no** `getAuthSessionTicket` / `SteamAuthTicket.getAuthSessionTicketInternal` / `getAppOwnershipTicket` + `GameConnectTokens` minting — those symbols exist **nowhere** on any ref. GN's JavaSteam client mints exactly **one** kind of ticket itself:

- **EncryptedAppTicket** — `SteamService.getEncryptedAppTicket()` / `…Base64()` (`SteamService.kt:4650-4712`) via `steamApps.requestEncryptedAppTicket(appId)` (`EMsg.ClientRequestEncryptedAppTicket`), cached in the `EncryptedAppTicket` Room table. It is an **ownership** proof only.
  - **Used only on the GOLDBERG path:** seeded into Goldberg's `steam_settings/configs.user.ini` as `ticket=<base64>` (`SteamUtils.kt:272`), and pre-fetched at launch **only when NOT real/bionic** (`XServerScreen.kt:4036-4052`, guard `!isLaunchRealSteam && !isLaunchBionicSteam`).
  - **Not used for the real/bionic client path.** In those modes the *genuine* running client issues whatever tickets the game asks for (auth-session, ownership, etc.) directly — GN neither mints nor injects them.

Login-time JavaSteam auth (shared by all modes) is `beginAuthSessionViaCredentials` / `beginAuthSessionViaQR` → `pollingWaitForResult` → refresh token, persisted encrypted in DataStore (`SteamService.kt:2840, 2896`; token keys per device report §2.7). That **refresh token is the seam**: it is what GN feeds the real client to log it in (as `SB_REFRESH_TOKEN` in bionic Mode 3, or via VDF files in real Mode 2).

**Mode 2 VDF/token auto-login** (`SteamTokenLogin.kt`, invoked at `XServerScreen.kt:4003-4009`):
- `setupSteamFiles()` → `SteamUtils.autoLoginUserChanges()` writes `loginusers.vdf` (OAuth refresh-token style; builder `SteamService.getLoginUsersVdfOauth`, `SteamService.kt:2718-2726`) + `HKCU\Software\Valve\Steam` reg (`AutoLoginUser`/`SteamExe`/`SteamPath`).
- `phase1SteamConfig()` writes `config.vdf` with the refresh token XOR-obfuscated under `ConnectCache` keyed by `CRC32(login)+"1"`, plus a random `MTBF` (`createConfigVdf` `:79-127`; cipher in `SteamTokenHelper.obfuscate`). It parses any existing `config.vdf`, deobfuscates, and checks JWT expiry to decide whether to rewrite (`:159-200`).
- **Phase 2** (newer Steam clients that dropped `ConnectCache` from `config.vdf`): writes `local.vdf` (`MachineUserConfigStore`), with the token encrypted by running the bundled Windows helper under Wine: `wine <root>/opt/apps/steam-token.exe encrypt <login> <token>` (`encryptToken` `:58-62`, `createLocalVdf` `:130-153`).

**Takeaway:** the JavaSteam layer's job in the real path is **not** ticket minting — it is (1) obtaining the real refresh token and (2) driving the genuine client's ownership/PICS/cloud/achievement plumbing. The genuine client does the game-facing auth.

---

## 7. THE ONE MECHANISM WE'D REPLICATE

**Run a genuine, logged-in Valve `libsteamclient.so` in a side process and bridge the Wine game's `steam_api → lsteamclient.dll → unix steamclient.so` to it over a local IPC endpoint** — i.e. reproduce the `SteamBootstrap` host-executable + env/socket contract (§4) against Valve's own client, logging it in with the app's real refresh token. That single move is what turns "downloaded game that fakes Steam (Goldberg)" into "game talking to real Steam services." Everything else (asset fetch, VDFs, PICS pre-warm, invite socket) is supporting scaffolding.

Because GN's bootstrap that performs the dlopen/vtable calls is **proprietary and source-withheld** (§4), we build that piece ourselves — **WinNative's GPL `wnsteam` Rust CM client is our reference**, not GN — while we can freely reuse GN's *open* contract, env block, asset layout, and VDF formats documented above.

**And set expectations honestly:** this yields real Steam services + real non-VAC multiplayer. It does **not** yield VAC. GN has no VAC path, and the native-ARM-client + emulated-x86-game topology is not one VAC can service. A Phase-0 on-device smoke test against an actual VAC-secured server (the memory's plan) remains the only way to know a given title's real ceiling — and the honest prior is "session-auth may pass, VAC will not."

---

### Appendix — load-bearing files (all `upstream/master` @ `1ad70ae5`)
- `app/src/main/java/app/gamenative/SteamBootstrap.kt` — host-process launcher + env/socket/status contract (§4).
- `app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java` — `addRealSteamEnvVars` (498), `bootstrapNativeSteamClient` (563); the launch env block + host bring-up.
- `app/src/main/java/app/gamenative/utils/launchdependencies/BionicSteamAssetsDependency.kt` — genuine-Valve asset download/inject; Proton→lsteamclient map.
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` — mode selection & exe path (4500+), SteamTokenLogin call (4003), per-boot injection `extractSteamFiles` (5885+), teardown (4620+), Goldberg-only ticket (4036).
- `app/src/main/java/app/gamenative/service/SteamOverlayClient.kt` + `service/handler/GameInviteHandler.kt` — real friends/invites/lobby-join control channel (multiplayer surface).
- `app/src/main/java/app/gamenative/utils/SteamTokenLogin.kt` (+ `SteamTokenHelper.kt`) — Mode 2 VDF/token auto-login.
- `app/src/main/java/app/gamenative/service/SteamService.kt` — JavaSteam client; `removeHandler(SteamGameServer)` (3592); `getEncryptedAppTicket` (4650); `getLoginUsersVdfOauth` (2718); CDN (1571).
- `app/src/main/cpp/steambootstrap/CMakeLists.txt` — builds the withheld `steam_bootstrap.c` → `libsteambootstrap.so`.
- `THIRD_PARTY_NOTICES` (105-184) + `.gitignore` — proprietary/source-withheld declaration (§4).
- Binary blob: `app/src/main/jniLibs/arm64-v8a/libsteambootstrap.so` (32,776 bytes).
