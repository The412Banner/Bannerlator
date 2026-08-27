# Synthesis — Building Our Own VAC-Capable Steam Agent

**Date:** 2026-08-27 · Branch `feat/steam-vac-phase0` · Based off main `68b528d9`
**Inputs:** three independent RE deep-dives (today's code) + the SteamAgent PE teardown
- `re/GAMEHUB_STEAMAGENT_RE.md` — GameHub SteamAgent (proprietary; behavior spec)
- `re/WINNATIVE_VAC_RE.md` — WinNative (GPL; **our legal code reference**)
- `re/GAMENATIVE_VAC_RE.md` — GameNative (GPL; **our legal asset-sourcing model**)
- `STEAMAGENT_ANALYSIS_2026-05-26.md` (device) — 466-line PE teardown of SteamAgent.exe

---

## 1. The converged architecture (all three agree)

There is exactly ONE architecture that can reach VAC, and all three apps implement the same core move:

> **Run a headless replacement for `steam.exe` inside Wine that loads and drives the GENUINE Valve `steamclient64.dll` (NOT an emulator), logs in the user's REAL Steam session with a refresh token our own CM client minted, and leaves the game bound to its OWN genuine `steam_api64.dll` — so `GetAuthSessionTicket()` returns a real, Valve-signed ticket with a real gameconnect token that a VAC server accepts at the auth layer.**

Mechanics, common across GameHub + WinNative-"Plan W" (and the shape of GameNative's bionic mode):
1. **Headless agent** loads the real client via `CreateInterface("CLIENTENGINE_INTERFACE_VERSION005")` → `IClientEngine`/`IClientUser`.
2. **Real login** with the user's own refresh token (minted off-Wine by a CM client — **Bannerlator already has this in JavaSteam**).
3. **Install + start `steamservice`**; seed registry `HKLM\Software\Valve\Steam` (`SteamClientDll64`, `SteamExe`, `SteamPath`, `InstallPath`, `ActiveProcess`, pid) + env (`SteamPath`, `ValvePlatformMutex`, `Steam3Master=127.0.0.1:<port>`).
4. **Game keeps its genuine `steam_api64.dll`** (NOT Goldberg) → attaches to the same live genuine session → real tickets.
5. **Control socket** (loopback, newline-delimited JSON) reports status to the Android side. Vocabulary (from SteamAgent): `init_start/success → login_start/success → sync_apps_* → sync_cloud_* → app_launch → game_terminated → app_exit` (+ `launch_failed <code>`, `overlay_*`). Game params (token, appid, cloud, join target) delivered by CLI args + env, NOT over the socket.
6. **Lifecycle:** agent launched with the game, torn down on game exit.

**The Goldberg-injection shortcut is dead** (confirmed earlier + by all three): Goldberg is an emulator, its `ticket=` is the encrypted *app* ticket (DRM), it fabricates the session ticket, and it cannot satisfy VAC. Not the path.

---

## 2. What each app contributes (and its wall)

| App | License | Role for us | Wall |
|---|---|---|---|
| **GameHub SteamAgent** | Proprietary, DRM-packed (Steam Stub `.bind*` V5 / custom packer V6) | **Behavior spec** — proven-in-practice real-client-via-IClientEngine-v005; PE doc + RE give exact startup sequence + socket vocabulary | Can't reuse code; bundles Valve DLLs; leaked GameSir dev token in `startSteam.bat` |
| **WinNative "Plan W"** (default on) | GPL-3.0 | **Legal CODE reference** — `wn-steam-launcher/src/main.cpp` does `SetLoginToken`+`LogOn` on the private vtable; Rust CM login + MMS lobby + launch/env/registry/reap wiring all GPL-reusable | **Bundles Valve's genuine `steamclient64.dll` (25.7 MB) in the APK** — the license landmine we must NOT copy |
| **GameNative bionic-steam** | GPL-3.0 (bootstrap `.so` proprietary/withheld) | **Legal ASSET-SOURCING model** — genuine Valve binaries downloaded from CDN at runtime, injected per boot, never baked in; open env/socket contract in `SteamBootstrap.kt` + `BionicProgramLauncherComponent.java` | Bootstrap C source deliberately gitignored ("withheld out of respect for Valve") → we can't transliterate it; VAC ticket path not wired (removes `SteamGameServer` handler) |

**Legal recipe = WinNative's launcher logic (GPL) + GameNative's download-at-runtime sourcing (never bundle) + our existing JavaSteam for the token.**

---

## 3. The ONE unproven risk (this is the whole ballgame)

**"VAC-capable by construction" ≠ "VAC-proven in practice."** No source and no artifact anywhere proves an end-to-end VAC-secured match actually completes on Android/ARM:
- GameHub's 267 MB runtime log: **zero** `vac`/`secure`/`steamservice` hits; successful launches (Brawlhalla, CS:Source) ran only **5–9 seconds** before `game_terminated`; most attempts `launch_failed 3005`.
- WinNative: no anti-cheat/VAC-module code at all — leans entirely on Valve's bundled binaries.
- The unknown: **does the x86 VAC anti-cheat module load and pass while running under FEX/box64 translation on ARM?** Historically fragile. This is the single thing to de-risk before building anything.

Everything else (real login, real ticket) is well-understood and reproducible. This one is not.

---

## 4. Revised plan (de-risk the real gate FIRST)

**Phase 1a — Decisive cheap test: prove (or disprove) a real VAC match on-device, BY HAND (no app code).**
Get the genuine Steam client running headless in a Bannerlator container using binaries the user legally has (their own PC Steam install, or Valve's official bootstrapper), log in with the user's own token, launch a VAC title (TF2 / L4D2), and actually **try to complete a match** — watch whether (a) the VAC module loads, (b) the session survives past the handshake (not 5–9 s), (c) we stay on a secure server with real players. This answers the ONLY open question, cheaply, before any code. THIS replaces the dead Goldberg "Phase 0".

**Phase 1b — Build our own agent (clean-room).** A headless `steam.exe` replacement (our code): `CreateInterface(CLIENTENGINE_INTERFACE_VERSION005)` the genuine `steamclient64.dll`, `LogOn` via the user's JavaSteam-minted refresh token, install `steamservice`, seed registry/env, open the control socket. **Reference WinNative's GPL `main.cpp`; use OpenSteamworks headers for the vtable; never copy GameHub's binary; never bundle Valve DLLs.**

**Phase 2 — Legal asset sourcing.** Fetch the genuine Steam client at runtime from Valve (official bootstrapper/depot) or from the user's own install — GameNative's download-and-inject model. Build the `lsteamclient` bridge (recipe already open on our `proton-wine` `*_add_steam` branches). Inject per boot.

**Phase 3 — Launch wiring + toggle.** Per-shortcut launch-mode picker: **Real Steam (online) / Goldberg (offline) / Raw**, gated on assets present. Start agent → poll ready (`sb_host_ready` / socket) → set env block → launch game on genuine `steam_api64.dll` → teardown on game exit (tied to the Wine subprocess, not the CM foreground service).

**Phase 4 — Prove it.** TF2 / CS:S / L4D2 — join a VAC-secured server, real players, stay connected through a full match.

---

## 5. Legal guardrails (hard rules)
- **NEVER bundle** Valve's Windows DLLs (`steamclient64.dll`, `steamservice.exe`, `vstdlib_s64.dll`, `tier0_s64.dll`) — source them at runtime from Valve or the user's own install. (Note: GameHub re-hosts `steam_client_0403` on the user's own `The412Banner/bannerhub-api` mirror — a shippable Bannerlator must NOT copy that.)
- **NEVER** reuse GameHub's SteamAgent binary or any leaked GameSir token.
- Borrowed WinNative/GameNative code is GPL-3.0 → keep attribution (Bannerlator is already GPL-lineage).
- **Ceiling:** VAC-only. Never kernel anti-cheat (BattlEye/EAC/Vanguard) — no Android-Wine path exists.
- Device logs from GameHub/GameNative leaked the owner's own Steam email/SteamID — keep those out of any committed artifact or external output.

---

## 6. Bottom line
The mechanism is no longer a mystery — it's confirmed from two independent GPL codebases plus a proprietary PE teardown, and it's the same trick three times. We know exactly what to build and have a legal path to build it. The single thing standing between "should work" and "works" is **whether VAC's anti-cheat module runs under ARM translation** — so we test that by hand FIRST (Phase 1a), and only pour engineering into 1b–4 if it passes.
