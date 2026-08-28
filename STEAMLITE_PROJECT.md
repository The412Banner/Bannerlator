# SteamLite — Real Steam Online Multiplayer (VAC) for Bannerlator

**Canonical project document.** Branch `feat/steam-vac-phase0` (off main `68b528d9`). Last updated 2026-08-27.
Detailed backing docs live in `re/` (see §9). This file is the single source of truth for goal, design, and state.

---

## 🏆 0. BREAKTHROUGH — VAC PROVEN ON DEVICE + EXACT RECIPE CAPTURED (2026-08-27)

**The core viability question is ANSWERED: VAC multiplayer works on this device (Adreno-750/ARM).** L4D2 (appId 550) was launched in GameHub, logged into the real Steam session, ran `left4dead2.exe -steam`, and reached a **VAC server** — proving the genuine-client method clears VAC on this hardware. Captured live from GameHub's running processes/env:

- **The agent binary is the one we already have.** GameHub's `c:\Program Files (x86)\Steam\steam.exe` is a **symlink → `components/SteamAgent2/SteamAgent.exe`**, md5 `538f9a8a7261aca067fc47a2a282a082`, 2,340,864 B — **byte-identical to our staged `/sdcard/Download/steamagent/SteamAgent.exe`.** It's GameHub's stripped "SteamLite," just symlinked and run *as* `steam.exe`. Nothing new to source.
- **Exact working invocation:**
  `wine c:\Program Files (x86)\Steam\steam.exe --username <acct> --token <rt> --rememberme --launchoption 0 --disablesteaminput --skip-appinfo-refresh --language english --cmaccel --cmlist "c:\Program Files (x86)\Steam\cmlist.json" --applaunch 550 --launchparameters -lv -novid -heapsize 900000`
  → it self-launches `left4dead2.exe -steam …` + `steamservice.exe /RunAsService`.
- **Exact env:** `STEAMAGENT_PORT=39367` (GameHub's SteamAgentServer assigns+listens — the port/server IS required), `SteamGameId=550`, `PROTON_DISABLE_LSTEAMCLIENT=1` (our fix, confirmed), `WINEUSERNAME=steamuser`/`USER=steamuser` (we use `xuser`), `WINEDEBUG=-all`, `LD_PRELOAD=…/pcengine/lib/arm64-v8a/libvfs.so:…/usr/lib/libsandboxfs.so`, `WINEPREFIX=…/virtual_containers/117886` (overlay on `containers/1`).
- **`cmlist.json`** (pre-seeded by GameHub's `libsteamkit_core`) = `{"datacenter":"lhr1","cm_list":[{"endpoint":"cmp2-lhr1.steamserver.net:27019"…}]}` — real Steam CM endpoints that `--cmlist`/`--cmaccel` consume.

**Why our by-hand attempts failed (SAME binary!):** we were missing the rich args (esp. `--cmlist`/`--cmaccel`/`--skip-appinfo-refresh`), the `cmlist.json`, a listening `SteamAgentServer` on `STEAMAGENT_PORT`, the `steamuser` prefix user, and the `libvfs`/`libsandboxfs` preload. The binary and `PROTON_DISABLE_LSTEAMCLIENT=1` were already correct.

**Port = replicate this recipe in Bannerlator:** symlink our agent as `steam.exe` → feed the full args+env → generate `cmlist.json` (our JavaSteam can mint the CM list, or copy GameHub's) → stand up a `SteamAgentServer` on `STEAMAGENT_PORT` → resolve `steamuser` vs `xuser` + the `libvfs`/`libsandboxfs` preload. **✅ Emulator settled: GameHub ran L4D2 on Proton arm64ec (user-confirmed + `PROTON_DISABLE_LSTEAMCLIENT` is a Proton var + ran as plain `wine`) — our Bannerlator Proton `11.0-2-arm64ec-1` is the correct environment; box64 is NOT needed.** This supersedes the "one unproven risk" in §5 — it's proven.

---

## 1. Goal
Give Bannerlator **real Steam online multiplayer on VAC-secured servers** (TF2 / CS:S / L4D2-class titles) by launching the game with the **genuine Valve Steam client** driven by a lightweight headless agent ("SteamLite") — the same method GameHub uses — built into Bannerlator, legally.
**Hard ceiling:** VAC-only. Never kernel anti-cheat (BattlEye / EAC / Vanguard) — no Android-Wine path exists.

## 2. The one VAC-capable architecture (confirmed by 3-way RE)
Reverse-engineering of WinNative, GameNative, and GameHub (today's code) all converge on ONE approach — there is no clever alternative:

> A **headless `steam.exe` replacement inside Wine ("SteamLite")** loads the **GENUINE Valve `steamclient64.dll`** (NOT an emulator) via `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `IClientEngine`/`IClientUser`, **logs in the user's REAL Steam session** with a refresh token (Bannerlator's JavaSteam already mints these), installs `steamservice`, seeds the registry + env, and the **game keeps its OWN genuine `steam_api64.dll`** (NOT Goldberg) → `GetAuthSessionTicket()` returns a real Valve-signed ticket with a real gameconnect token → **a VAC server accepts it at the auth layer.**

- "SteamLite" is GameHub's own internal name for its agent (found in the PE: PDB `D:\a\SteamLite\SteamLite\...\SteamAgent.pdb`). It's lightweight because it strips real Steam.exe's heavy CEF UI / auto-updater / store — just the client-driving core.
- **The Goldberg-injection shortcut is DEAD:** Goldberg is an emulator; its `ticket=` is the encrypted *app* (DRM) ticket, it fabricates the session ticket, and it cannot satisfy VAC. Not the path.

## 3. Design of record

### 3a. Launch-method hierarchy (per Steam game)
1. **SteamLite** — *Real Steam, online* → **PRIMARY / default.** Real client, VAC-capable. What we're building.
2. **Goldberg** — *offline / alternative* → **FALLBACK.** Already shipped + working (keeps its Regular/Experimental/ColdClient sub-modes). Auto-engaged when SteamLite fails (no network / login fail / uncooperative title).
3. **Raw** — third option (no Steam layer).

Auto-fall-back: try SteamLite → drop to Goldberg-offline on failure. **We keep the entire existing Goldberg path — just demote it from default to fallback.**

### 3b. UI home
The **Steam game detail page** (`SteamGameDetailActivity`) already hosts the Goldberg setup (gear → Goldberg Mode dialog). We add a **Launch method** picker there: SteamLite (default) / Goldberg (fallback, existing sub-modes nested) / Raw. Per-shortcut, stored on the shortcut, read at launch.

### 3c. Launch wiring
Rides on the tagging + hook Bannerlator already has:
- Steam shortcuts are tagged `storeSource=steam` + `steamAppId` (`StarLaunchBridge`), resolved at launch in `XServerDisplayActivity` (`resolveSteamIdentity()`), where `SteamDatabase` init + Goldberg patching already happen (the achievements/Goldberg hook `maybeSeedAndStartAchievementWatcher` ~:4478 is the reference branch point).
- **This is the exact same shape as Bannerlator's existing Epic online-launch hook** (`XServerDisplayActivity.java:8069-8078` appends `-EpicPortal` + a real minted auth triple, gated `storeSource=epic && epicEos!=0` — "NOT an emulator"). SteamLite is the Steam twin of that pattern: per-shortcut, launch-time, store-tagged, real online auth.
- New `launchMode=RealSteam` branch: stage genuine client → start SteamLite agent (log in w/ user token) → set env block → launch game on its genuine `steam_api64.dll` → teardown on exit.

## 4. What we fork / build / source (the legal posture)
- **Fork GameHub's SteamLite agent + launch orchestration** — the user has GameHub/GameSir devs' permission to use their files and fork their app/features. This simplifies the build from "write our own agent from scratch" to "fork theirs + wire to Bannerlator."
- **Reference WinNative's GPL code** (`wn-steam-launcher/src/main.cpp` `SetLoginToken`+`LogOn` on the private vtable) for the clean-room parts; OpenSteamworks headers cover the `IClientEngine v005` vtable.
- **Our JavaSteam** already mints the user's refresh token — the login credential the agent needs.
- **Valve's Windows DLLs** (`steamclient64.dll`/`steamservice.exe`/etc.): the ONE thing GameHub's permission doesn't cover (GameHub can't license Valve's IP). Steam is free to download but **not open source** — it's Valve's proprietary code. Clean sourcing = **download from Valve at runtime** (GameNative's model), or the user's own install. *The user also states Valve devs verbally said Steam's files are "fine to use" — if obtained in writing, that would additionally permit bundling; get written confirmation before relying on it for shipping.* For the by-hand test / local dev it's a non-issue.
- **Never** ship the leaked GameSir dev token; borrowed GPL code keeps attribution.

## 5. The one unproven risk (de-risk this)
**"VAC-capable by construction" ≠ "VAC-proven on ARM."** No artifact anywhere proves an end-to-end VAC match completes on Android/ARM: GameHub's logs show games dying in **5–9 s** (`launch_failed 3005`), zero `vac`/`secure` hits; WinNative has no VAC code. **The unknown: does the x86 VAC anti-cheat module load + pass under FEX/box64 translation on ARM?** Everything else (real login, real ticket) is reproducible; this is not. The chosen approach (build the real launch into Bannerlator and try L4D2 online) tests launch + multiplayer + this risk together.

## 6. Current state
- ✅ 3-way RE complete (WinNative `2bcd0a3`, GameNative `1ad70ae5` + steam branches, GameHub 6.2.1 + SteamAgent PE teardown). Reports in `re/`.
- ✅ Architecture + design of record settled (this doc).
- ✅ Device facts: GameHub (`com.xiaoji.egggame`) is logged into the **user's own account** (not the leaked dev acct); genuine V6 client at `/storage/emulated/0/Download/steamagent/steam_client_0403`, CLIENTENGINE pin = **v005**; L4D2 owned + **added to a Bannerlator container**.
- ✅ **Launch recipe extracted** (`re/GAMEHUB_REAL_LAUNCH_ORCHESTRATION.md`, `bec62a65`): **token hand-off = agent CLI `--username <acct> --token <refresh_token> --applaunch <appId>`** (our JavaSteam mints the token — same shape it already uses); interface pin **v005 confirmed** (client build 10520955 exports only `CLIENTENGINE_INTERFACE_VERSION005`); game keeps its OWN genuine `steam_api64.dll` (no dll swap, no `WINESTEAMCLIENTPATH`); the game's `SteamAppId`/`SteamGameId`/`SteamClientLaunch` env is set by GameHub's native `WinEmuModule` (NOT the agent) → Bannerlator sets these explicitly in `GuestProgramLauncherComponent`.
- ⛔ **Environment note:** the only Bannerlator install on device is `com.tencent.ig` (pubg-staged, normally verify-only) — no separate `com.winlator.banner`. L4D2's container is there.
- ✅ **Prototype prep DONE + backup verified** (`re/STEAMLITE_PROTO_RUNBOOK.md`, `39fbe20a`) — awaiting user GO to execute. Grounded: container **id 3 / `xuser-3`** ("P11-2 Arm"), WINEPREFIX `…/imagefs/home/xuser-3/.wine` (active-container symlink `home/xuser→xuser-3` must stay active at launch); L4D2 shortcut `.desktop` (`storeSource=steam`,`steamAppId=550`); **token** at `com.tencent.ig/shared_prefs/steam_prefs.xml:refresh_token` (owner's acct, NOT dev, value never read); **agent** = `/sdcard/Download/steamagent/SteamAgent.exe` (PE32+ x86_64, takes `--username/--token/--applaunch`, v005); **wine** = `contents/Proton/11.0-2-arm64ec-1/bin/wine` (arm64ec+fexcore, `HODLL=libwow64fex.dll`, `explorer /desktop=shell,… winhandler.exe`); **L4D2 = GENUINE Valve steam_api, never Goldberg-swapped, 32-BIT** (only `steam_api.dll`) → no un-Goldberg needed; anomaly `bin/steam_appid.txt=879` (moved aside). Backup = `/sdcard/Download/steamlite-proto-backup/20260827/` (bin/ tar 44MB + reg + .desktop + container json; NOT the 13GB assets). Approach = repoint the L4D2 `.desktop` at the staged agent w/ `--applaunch 550` so it rides Bannerlator's own wineserver. **Staging gotcha:** root-copied files need `chown 10249:10249` + `restorecon`/`chcon …c249…` or the app can't read them.

## 7. Open questions / decisions
- **[USER] Container:** prototype/build in the `com.tencent.ig` staged install (where L4D2 is), or set up a fresh/dedicated one? (Blocks any device mutation.) — STILL OPEN.
- ~~[TECH] Token hand-off~~ ✅ RESOLVED: agent CLI `--username <acct> --token <refresh_token> --applaunch <appId>`; our JavaSteam mints the token.
- ~~[TECH] agent interface pin~~ ✅ RESOLVED: v005.
- **[DECISION] Agent binary:** run GameHub's forked SteamLite binary as-is (permitted, fastest) vs reimplement our own v005 driver (WinNative GPL ref) — see Fork spec §8.5.

## 8. Plan
1. **[in progress]** Extract GameHub's no-Goldberg launch recipe (the fork spec).
2. Prototype the real-Steam launch in a Bannerlator container (fork GameHub's SteamLite agent + genuine client), launch L4D2 → join a VAC-Secured server. **Pass = sustained secure gameplay past ~5–9 s. Fail = "VAC authentication error / unable to verify your game session".**
3. If it passes: build the `launchMode=RealSteam` branch + the detail-page Launch-method picker + runtime Valve-client sourcing + `lsteamclient` bridge (recipe open on our `proton-wine *_add_steam`) + auto-fall-back to Goldberg.
4. Prove: TF2 / CS:S / L4D2 on VAC servers, real players, full match.

## 8.5 FORK SPEC — Bannerlator `launchMode=RealSteam`

### Components
| Piece | Action | Source / where |
|---|---|---|
| **SteamLite agent** (headless steam.exe replacement, drives genuine client via IClientEngine v005) | **FORK GameHub's binary** as-is (permitted, fastest to first-working) — OR reimplement our own v005 driver later (WinNative GPL `wn-steam-launcher/main.cpp` ref) | GameHub's `SteamAgent2` |
| **Genuine Valve client DLLs** (`steamclient64.dll`, `steamservice.exe`, `tier0_s64`, `vstdlib_s64`) | **SOURCE AT RUNTIME — never bundle** (download from Valve / user install). By-hand test uses the on-device copy | `/sdcard/Download/steamagent/steam_client_0403` (test only) |
| **Refresh token** (the login) | **REUSE** — Bannerlator's JavaSteam already mints the user's own refresh token | existing `SteamRepository` |
| **Shortcut tagging + launch hook + container/prefix + Goldberg (→fallback)** | **REUSE** existing | `StarLaunchBridge`, `XServerDisplayActivity`, `GoldbergPatcher` |
| **`launchMode=RealSteam` branch + orchestration + env + picker UI + auto-fallback** | **BUILD (new)** | see below |

### Ordered launch orchestration (mapped to Bannerlator hooks)
1. **Branch on mode** — `XServerDisplayActivity.maybeSeedAndStartAchievementWatcher()` (:4478, peer to `GoldbergPatcher`; gates `isSteamShortcut` :3993, resolves appId :4180): if shortcut `launchMode==RealSteam` → RealSteam branch (else Goldberg / Raw).
2. **Un-Goldberg** — ensure the game's OWN genuine `steam_api64.dll` is in place (NO gbe_fork swap) — the inverse of `GoldbergPatcher`.
3. **Stage genuine client** into the prefix `C:\Program Files (x86)\Steam\` (from runtime-sourced DLLs; test = `steam_client_0403`).
4. **Seed registry** `HKLM\Software\Valve\Steam`: `SteamExe`=agent, `SteamPath`, `SteamClientDll64`, `InstallPath`, `SteamPID`.
5. **Start SteamLite agent** — argv `--username <acct> --token <refresh_token> --applaunch <appId>`, env `STEAMAGENT_PORT=<loopback>`; agent installs `steamservice.exe`, logs in, drives the genuine client via `CLIENTENGINE_INTERFACE_VERSION005`. Token comes from JavaSteam.
6. **Block until `login_success`** over the agent's RPC socket (`STEAMAGENT_PORT`).
7. **Launch the game** (`<game>.exe -steam`) on its OWN genuine `steam_api64.dll`. Env: `SteamPath` + `ValvePlatformMutex`, and — set explicitly by Bannerlator in **`GuestProgramLauncherComponent`** — `SteamAppId` / `SteamGameId` / `SteamClientLaunch`. lsteamclient DISABLED; **no `WINESTEAMCLIENTPATH`**; no dll swap.
8. **Teardown** the agent on `game_terminated` / `app_exit`; **auto-fall-back to Goldberg-offline** if step 5/6 fails.

### UI
Launch-method picker on `SteamGameDetailActivity` (beside the existing Goldberg gear): **SteamLite (default) / Goldberg (fallback, existing sub-modes) / Raw**. Stored per-shortcut, read at the :4478 hook.

### Legal
Fork the agent (permitted) · source Valve DLLs at runtime, never bundle (unless written Valve permission) · never ship the leaked GameSir token · redact owner PII.

## 9. Backing docs (in `re/`)
- `SYNTHESIS_OWN_STEAM_AGENT_PLAN.md` — the 3-way convergence + build plan.
- `WINNATIVE_VAC_RE.md` / `GAMENATIVE_VAC_RE.md` / `GAMEHUB_STEAMAGENT_RE.md` — per-app deep dives.
- `PHASE1A_VAC_TEST_AND_WIRING.md` — the by-hand test + launch-wiring spec (L4D2 + GameHub-client config).
- `PHASE0_VAC_SCOPING.md` — original scoping (Goldberg-injection ruled out).
- `GAMEHUB_REAL_LAUNCH_ORCHESTRATION.md` — ✅ the no-Goldberg launch recipe (token via `--token`, v005, ordered steps) — basis for Fork spec §8.5.
- `STEAMLITE_PROTO_RUNBOOK.md` — ✅ grounded by-hand prototype runbook for L4D2 in `xuser-3` (backup+restore, gated stage→launch→join steps, log capture, PASS/FAIL) — awaiting user GO.

## 10. Guardrails
VAC-only, never kernel AC · never bundle Valve DLLs without written Valve permission (source at runtime) · never ship the leaked GameSir token · GPL attribution for WinNative/GameNative code · redact the owner's Steam email/SteamID from any committed artifact or external output.
