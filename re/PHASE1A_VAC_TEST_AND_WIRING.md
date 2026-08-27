# Phase 1a — By-Hand VAC Smoke Test + Real-Steam Launch Wiring Spec

**Date:** 2026-08-27 · Branch `feat/steam-vac-phase0` (off main `68b528d9`)
**Author:** storefront / native-Steam engineer
**Scope:** Planning + a runnable manual procedure. **No app code is built or changed here.**
**Reads first:** `re/SYNTHESIS_OWN_STEAM_AGENT_PLAN.md`, `re/WINNATIVE_VAC_RE.md`,
`re/GAMENATIVE_VAC_RE.md`, `re/GAMEHUB_STEAMAGENT_RE.md`, and the on-device PE teardown
`/sdcard/STEAMAGENT_ANALYSIS_2026-05-26.md` (recipe source for §D1-4/5).

---

## 0. The one question this de-risks

All three sibling apps converge on the **same** VAC-capable move (headless genuine `steamclient64.dll`
under Wine + real login + game keeps its own genuine `steam_api64.dll`). That mechanism is
"VAC-capable **by construction**" but "VAC-**unproven** in every artifact we have" — GameHub's own 267 MB
runtime log shows **zero** `vac`/`secure` hits and successful launches that died in **5-9 seconds**.

> **Decisive open question:** *does the x86 Steam VAC anti-cheat module actually load and pass while the
> game (and its VAC module) run under FEX/box64 x86→ARM64 translation on Android?* No source proves it.

This doc's Deliverable 1 is a by-hand test that answers **only** that question, cheaply, before any code.
Deliverable 2 specs where the feature would wire in **if** the test passes.

---

## ⚠️ Device-state correction (verified today, 2026-08-27)

The task brief assumed GameHub's standalone genuine-client folders are sitting at
`/storage/emulated/0/steam_9866233/` etc. **They have been cleaned up since the 2026-05-26 analysis and
are GONE from `/sdcard`.** What still exists, verified via the root bridge:

| Asset | Status today | Path |
|---|---|---|
| `steam_9866232/`, `steam_9866233/`, `steam_client_0403/` at `/sdcard` root | **GONE** | — |
| **Genuine Valve V6 client** (survives inside GameHub's app-private data) | **PRESENT** | `/data/data/com.xiaoji.egggame/files/usr/home/components/steam_client_0403/` |
| ↳ `steamclient64.dll` (25,739,928 B — genuine, not a 100 KB emulator) | PRESENT | `…/steam_client_0403/drive_c/Program Files (x86)/Steam/steamclient64.dll` |
| ↳ `tier0_s64.dll` (431,768) · `vstdlib_s64.dll` (488,088) | PRESENT | same `Steam/` dir |
| ↳ `GameOverlayRenderer64.dll` · `SteamOverlayVulkanLayer64.dll` + `.json` | PRESENT | same `Steam/` dir |
| ↳ `bin/steamservice.exe` (2,952,856) · `bin/steamservice.dll` · `bin/x64launcher.exe` | PRESENT | `…/Steam/bin/` |
| **Genuine V6 SteamAgent** (`SteamAgent.exe`, 2,340,864 B, packed) | PRESENT | `/data/data/com.xiaoji.egggame/files/usr/home/components/SteamAgent2/SteamAgent.exe` |
| Loose SteamAgent PE variants (V5/V5.1/V6) | PRESENT | `/sdcard/SteamAgent-{old,newer,60}.exe`, `/sdcard/SteamAgent_unpacked.exe` |
| `steam_9866233/startSteam.bat` (carried the **leaked GameSir dev token**) | **GONE with its folder** | — (moot now, but still forbidden — §Legal) |

**Two consequences for the test design:**
1. The on-device V6 bundle **has no `steam.exe`** (V6 boots the runtime through SteamAgent, not `steam.exe`).
   So the "launch `steam.exe` in a Wine desktop and log in through the UI" path **cannot** use the on-device
   folder — it needs a `steam.exe` from the **user's own PC install**.
2. The on-device genuine DLLs are usable for a manual proof, but they are **V6 build 10520955** paired with a
   **V6 SteamAgent whose `CLIENTENGINE` interface pin is unknown** (likely `v006` — the V5 `v005` won't match
   10520955). That pairing risk is another reason the recommended path below uses the user's own PC Steam
   (self-consistent `steam.exe` + `steamclient64.dll` of the same build).

---

# DELIVERABLE 1 — the by-hand Phase 1a VAC smoke test

Run in an **existing Bannerlator container that already boots a game** (so Wine + FEX/box64 x86_64 + DXVK/VKD3D
are known-good). Driven via `bridge '<cmd>'`, absolute paths, one command per line. **No Bannerlator code.**

## Pre-flight checklist

- [ ] A Bannerlator container that **already launches an x86_64 game to gameplay** (proves Wine + FEX/box64 +
      DXVK/VKD3D work). Verified candidate on this device: container **xuser-3**
      (`/data/data/com.tencent.ig/files/imagefs/home/xuser-3/`) — it has HL2 installed + tagged and is
      configured `emulator=fexcore`, `fexcorePreset=PERFORMANCE_TSO`, `FEX-2608+45-Nightly`, `box64 0.4.1
      EXTREME`, `dxwrapper=dxvk+vkd3d`. (Read from its `Half-Life 2.desktop` `[Extra Data]`.)
- [ ] The user is **logged into their own Steam once inside Bannerlator** (JavaSteam) — Bannerlator's Steam
      pill shows Online; a refresh token is in `steam_prefs.xml` (`SteamPrefs.K_REFRESH_TOKEN`). This is the
      real session that makes the ticket genuine.
- [ ] The chosen **VAC title is installed** in that container's library (see §D1-1).
- [ ] Genuine Valve binaries staged (see §D1-2) — **user's own PC Steam** (recommended) or the on-device
      GameHub V6 bundle (fallback, manual-test-only).
- [ ] Root bridge working (`bridge 'id'` returns root); a way to pull logs off-device.

## D1-1. Which VAC title, and why

Pick **one the user owns**. Two good options — both small, **VAC-only (no kernel anti-cheat)**, with plenty of
public secure servers and real players:

| Title | AppId | Why | Secure-server notes |
|---|---|---|---|
| **Team Fortress 2** | **440** | Free, tiny, Source engine, huge population, trivially-found VAC-Secure community/valve servers. **First choice.** | Server browser → Internet → sort by players; "VAC Secured" is the default for valve/most community servers. |
| **Left 4 Dead 2** | **550** | Source engine, cheap, VAC-only; Versus/Campaign on official + community secure servers. | Fewer populated pubs than TF2; use the in-game server browser or `mm_dedicated_search_maxping`. |

Avoid CS2 (VAC-*Live*/heavier), and anything BattlEye/EAC/Vanguard — those are kernel-mode and out of scope
(the hard ceiling). **Recommended: TF2 (440)** for the fastest path to a populated secure server.

## D1-2. Where the genuine Valve binaries come from — LEGALLY

The genuine-VAC path needs the real **Windows** `steamclient64.dll` (+ `steamservice`, `vstdlib_s64`,
`tier0_s64`), and a headless driver for it. Two legal sources; pick per path:

**Source A — the user's own PC Steam install (RECOMMENDED for the manual test).**
On the user's Windows/Steam PC, `C:\Program Files (x86)\Steam\` contains a self-consistent set:
`steam.exe`, `steamclient64.dll`, `steamservice.exe`/`.dll` (under `bin\`), `vstdlib_s64.dll`,
`tier0_s64.dll`, plus the overlay DLLs. Copy that whole `Steam\` folder to the device (e.g. push to
`/sdcard/Download/pc-steam/`). This gives a **matched `steam.exe` + client of the same build** so we can log in
through the normal UI (no token-handoff scripting, no interface-pin guessing) — the least-code proof.

**Source B — the on-device GameHub genuine V6 client (fallback, MANUAL-TEST-ONLY).**
`/data/data/com.xiaoji.egggame/files/usr/home/components/steam_client_0403/drive_c/Program Files (x86)/Steam/`
has genuine `steamclient64.dll` + `tier0_s64.dll` + `vstdlib_s64.dll` + overlay + `bin/steamservice.*`, and a
paired genuine V6 SteamAgent at `…/components/SteamAgent2/SteamAgent.exe`. Usable to save the PC copy, **but**:
no `steam.exe` (must drive via SteamAgent, which needs `--token` and whose interface pin is unverified), and it
is GameSir-proprietary re-hosted Valve code. Fine for a throwaway proof; **never** shippable.

> **Not shippable either way.** A shipped Bannerlator must **never bundle or re-host** Valve's Windows DLLs.
> The product sources them at runtime from **Valve's official Windows bootstrapper/CDN** or from the **user's
> own install** (GameNative's download-and-inject model), and never from GameHub's mirror.

## D1-3. Where to place them in a Bannerlator container prefix

Container path convention (from `ContainerManager.java:44,156` + `Container.java:971` + `ImageFs.java:15,19`):

```
<pkg filesDir>/imagefs/home/xuser-<id>/            ← container root (rootDir); has .container marker
    └── .wine/                                     ← WINEPREFIX
        └── drive_c/
            ├── Program Files (x86)/
            │   └── Steam/                          ← ★ CREATE THIS; drop the genuine client here
            │       ├── steamclient64.dll
            │       ├── vstdlib_s64.dll  tier0_s64.dll
            │       ├── steam.exe                    (Source A only)
            │       ├── GameOverlayRenderer64.dll  SteamOverlayVulkanLayer64.dll(.json)
            │       └── bin/  steamservice.exe  steamservice.dll  x64launcher.exe
            ├── steam_games/<Game>/…                ← where Bannerlator installs Steam titles
            └── users/xuser/Desktop/*.desktop        ← per-game shortcuts (tags live in [Extra Data])
```

Verified real prefix on this device: `/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine/drive_c/`
(has `Program Files (x86)`, `Games`, `steam_games`, `users/xuser/Desktop/…`).

**Find the container id** = the `xuser-<id>` whose `.container` you can read, then:
```
bridge 'ls /data/data/<pkg>/files/imagefs/home/'                       # xuser, xuser-3, xuser-4 …
```
**Stage the DLLs (Source A example):**
```
bridge 'mkdir -p "/data/data/<pkg>/files/imagefs/home/xuser-<id>/.wine/drive_c/Program Files (x86)/Steam/bin"'
bridge 'cp -a /sdcard/Download/pc-steam/. "/data/data/<pkg>/files/imagefs/home/xuser-<id>/.wine/drive_c/Program Files (x86)/Steam/"'
```
(Fallback Source B: `cp -a` from the `com.xiaoji.egggame` `steam_client_0403/drive_c/Program Files (x86)/Steam/.`
into the same target.)

## D1-4. Registry + env seeding (from the SteamAgent recipe)

Only needed for the **headless** path (§D1-5 option a). A real `steam.exe` UI login (option b) seeds all of
this itself — that's why it's the least-code path.

**Registry — `HKLM\Software\Valve\Steam`** (Wine `wine reg add`, run from the container's Wine env, or drop a
`.reg` and `regedit /s`). Keys the genuine client + the game's own `steam_api64.dll` look for
(`STEAMAGENT_ANALYSIS_2026-05-26.md §2` + WinNative `main.cpp:146-212`):
```
wine reg add "HKLM\Software\Valve\Steam" /v SteamPath        /t REG_SZ /d "C:\Program Files (x86)\Steam" /f
wine reg add "HKLM\Software\Valve\Steam" /v InstallPath      /t REG_SZ /d "C:\Program Files (x86)\Steam" /f
wine reg add "HKLM\Software\Valve\Steam" /v SteamExe         /t REG_SZ /d "C:\Program Files (x86)\Steam\steam.exe" /f
wine reg add "HKLM\Software\Valve\Steam" /v SteamClientDll   /t REG_SZ /d "C:\Program Files (x86)\Steam\steamclient.dll" /f
wine reg add "HKLM\Software\Valve\Steam" /v SteamClientDll64 /t REG_SZ /d "C:\Program Files (x86)\Steam\steamclient64.dll" /f
:: live-session handshake (steam_api64 attaches to the running client via these)
wine reg add "HKCU\Software\Valve\Steam\ActiveProcess" /v SteamClientDll   /t REG_SZ    /d "C:\Program Files (x86)\Steam\steamclient.dll" /f
wine reg add "HKCU\Software\Valve\Steam\ActiveProcess" /v SteamClientDll64 /t REG_SZ    /d "C:\Program Files (x86)\Steam\steamclient64.dll" /f
wine reg add "HKCU\Software\Valve\Steam\ActiveProcess" /v ActiveUser       /t REG_DWORD /d <accountid> /f
wine reg add "HKCU\Software\Valve\Steam\ActiveProcess" /v pid              /t REG_DWORD /d <agent pid> /f
wine reg add "HKCU\Software\Valve\Steam\ActiveProcess" /v Universe         /t REG_DWORD /d 1 /f
wine reg add "HKCU\Software\Valve\Steam\Apps\440"      /v Installed        /t REG_DWORD /d 1 /f
wine reg add "HKCU\Software\Valve\Steam\Apps\440"      /v Running          /t REG_DWORD /d 1 /f
```

**Env block** (set for both the agent and the game process; from SteamAgent §2 + WinNative `main.cpp:923-932`
+ GameNative `BionicProgramLauncherComponent.java:498-549`):
```
SteamPath=C:\Program Files (x86)\Steam
SteamAppId=440           SteamGameId=440           SteamOverlayGameId=440
SteamClientLaunch=1      SteamEnv=1                SteamUser=<login>   SteamAppUser=<login>
STEAMID=<steamid64>      ValvePlatformMutex=<mutex name>
Steam3Master=127.0.0.1:<port>        # the local Steam IPC endpoint steam_api64 dials
:: leave the overlay ON as a "real client is driving this" signal (do NOT set the *NoOverlay* vars):
:: (unset) SteamNoOverlay  SteamNoOverlayUIDrawing  DISABLE_VK_LAYER_VALVE_steam_overlay_1
```

**Overlay implicit-layer `.reg`** (registers `VK_LAYER_VALVE_steam_overlay` so DXVK/VKD3D titles get the overlay
— an excellent "is the genuine client really injecting into this game?" tell). From
`STEAMAGENT_ANALYSIS_2026-05-26.md §6`, apply **once** per prefix:
```
wine reg add "HKLM\SOFTWARE\Khronos\Vulkan\ImplicitLayers"          /v "C:\Program Files (x86)\Steam\SteamOverlayVulkanLayer64.json" /t REG_DWORD /d 0 /f
wine reg add "HKLM\SOFTWARE\WOW6432Node\Khronos\Vulkan\ImplicitLayers" /v "C:\Program Files (x86)\Steam\SteamOverlayVulkanLayer.json"  /t REG_DWORD /d 0 /f
```

## D1-5. Log in headlessly with the USER'S OWN token — pick the least-code path

**Option (a) — headless SteamAgent + `--token`.** Drive the genuine client via the on-device V6 `SteamAgent.exe`
(or the user's own `steam.exe`) with the user's **own** refresh token (read it from Bannerlator's
`steam_prefs.xml`, or re-mint via a fresh Bannerlator Steam login). SteamAgent CLI (PE strings):
`--token <refresh_token> --launchoption … [--offline] [--disablecloud]`. It installs `steamservice`, seeds the
registry/env above itself, and streams `{type,event,appid,details,timestamp,username}` on `STEAMAGENT_PORT`.
Downsides for a **by-hand** test: you must script the token handoff + a socket listener, and the V6 agent's
interface pin (`v006?`) must match the V6 client. **Not the simplest.**
⛔ **Never** use `steam_9866233/startSteam.bat`'s hard-coded `--username gy939543405 --token …` (a leaked GameSir
dev account, SteamID `76561198287233535`). The user logs in with **their** account only. (That file is gone
from this device anyway, but the rule stands.)

**Option (b) — real `steam.exe` in the Wine desktop, log in through the UI once. ★ RECOMMENDED (least code).**
Launch the user's own `steam.exe` (Source A) inside the container's Wine desktop, log in through the normal
Steam UI with the user's own account (Steam Guard as usual — approve the new "device"). Steam itself writes the
real session, `config.vdf`/`loginusers.vdf`, all registry keys, installs `steamservice`, and registers the
overlay — **you write none of §D1-4 by hand**. Leave Steam running; from its own Library launch TF2, or from a
terminal `wine "C:\Program Files (x86)\Steam\steam.exe" -applaunch 440`.

## D1-6. Launch the game + join a secure server

1. With the genuine client logged in (option b) and running, launch TF2 (`-applaunch 440`) or L4D2 (`550`).
2. In-game: **Find a game → Community/Official servers → Server browser → Internet tab.** Sort by players.
3. Confirm the target row is **"VAC Secured"** (TF2/valve servers are secure by default). Pick one with real
   players (not a bot-only pub).
4. **Join.** Watch the connection handshake all the way into spawning/playing.

## D1-7. ⭐ EXACTLY what to observe — the decisive pass/fail signals

| Outcome | What you see | Meaning |
|---|---|---|
| **PASS** | You connect through the full handshake, **spawn, and keep playing** on a VAC-Secured server with real players — surviving **well past the ~5-9 s** GameHub's logs died at (give it several minutes of real gameplay). No VAC kick. | The x86 VAC module loaded and reported clean under FEX/box64. **This is the green light to build Phase 1b.** |
| **FAIL — VAC kick** | Disconnect with **"VAC authentication error"** / **"unable to verify your game session"** / **"You have been kicked: VAC"**. | The exact failure we're testing for — VAC won't init/pass under ARM translation. Capture the **verbatim** disconnect reason. |
| **FAIL — early death** | Game process exits in seconds; agent/logcat shows `game_terminated` shortly after `app_launch`, or `launch_failed` **`3005`** (GameHub's dominant failure). | Launch/session plumbing broke before VAC was even reached — inconclusive on VAC; fix the launch first, then retest. |
| **FAIL — no secure session** | You can only join **insecure** servers, or the browser shows no VAC-secured joinable server. | Session-auth may be fine but you never exercised VAC — not a VAC verdict. Force a known-secure server. |

**Logs/paths to capture (all via the root bridge):**

- **Steam client logs:** `…/drive_c/Program Files (x86)/Steam/logs/` — especially `connection_log.txt`,
  `content_log.txt`, and any `vac*`/`bootstrap_log.txt`. Grep: `grep -iE 'vac|secure|VAC_|BeginAuthSession|steamservice|AuthenticateUserTicket|reject|kick'`.
- **Overlay proof (genuine client is injecting):**
  `…/Steam/GameOverlayRenderer.log` — look for `GameID = 440, OverlayGameID = 440` +
  `Hooking SetCursorPos, GetCursorPos, ShowCursor…`. Its presence with a fresh mtime = the real client
  loaded the overlay into the game.
- **Game console:** TF2/L4D2 with `-condebug` writes `…/steam_games/<Game>/<mod>/console.log` (e.g. `tf/console.log`).
  Grep: `grep -iE 'vac|secure|connection|challenge|reject'`.
- **Wine debug log:** run the launch with `WINEDEBUG=+module,+loaddll 2>wine.log` and grep
  `grep -iE 'steamclient64|steamservice|lsteamclient|Loaded layer VK_LAYER_VALVE_steam_overlay|vac'`.
  (Also confirm `lsteamclient disabled` if you intend the game to hit the **real** PE `steamclient64.dll`.)
- **Our logcat:** `bridge 'logcat -d'` around the launch — grep `grep -iE 'steam|vac|SteamStatus|launch_failed|game_terminated'`.
- **The decisive greps in one line:** `vac`, `secure`, `VAC_`, `BeginAuthSession`, `AuthenticateUserTicket`,
  `steamservice`, `Loaded layer VK_LAYER_VALVE_steam_overlay`.

> **PII hygiene:** these logs carry the user's Steam email + SteamID64. Keep them **out of any committed
> artifact or external output** (redact before sharing), per the synthesis §5 guardrail.

---

# DELIVERABLE 2 — implementation wiring spec (grounded in the worktree)

The user asked: *"will it be added at Steam game launch time like we did with Epic EOS games, with a Steam game
shortcut settings toggle, since we tag Steam games?"* — **Confirmed, with one correction and precise refs below.**
All paths under `/home/claude-user/bl-wt-steam-vac/app/src/main/java/com/winlator/star/`.

## D2-0. Answer up front

**Yes — the design the user describes is exactly right, and Bannerlator already has the closest analog they
named: the Epic online-launch hook is REAL in this app (not just BannerHub).** The real-Steam agent lifecycle
hangs off the **same launch-time, per-shortcut, store-tagged branch point** where the `-EpicPortal` args are
appended today. A per-shortcut **launch-mode toggle** lives beside the existing Goldberg picker on the Steam
detail page.

## D2-1. The launch-time Steam hook (the branch point)

`XServerDisplayActivity.java` — the guest-launch activity. Key sites:

- **Steam identity resolution:** `resolveSteamIdentity()` (`:4330`) → `SteamIdentity{execPath, storeSource,
  taggedAppId}` with `isGenuineSteam()` = `storeSource=="steam" || exec under steam_games/` (`:4318`,
  `:4180-4184`). `resolveSteamAppRef()` / `resolveSteamAppRefFrom()` (`:4158`, `:4180`) map identity → appId +
  install dir via `SteamDatabase`.
- **`SteamDatabase` init at launch:** `:1912` `SteamDatabase.getInstance(getApplicationContext())` (guarded;
  the root-cause fix that makes achievements/cloud fire in the launch process).
- **Goldberg prep at launch:** `maybeSeedAndStartAchievementWatcher()` (`:4478`) runs **synchronously on the
  launch worker, immediately before `startEnvironmentComponents()` boots the guest** (`:4470-4474` ordering
  note). It gates on `SteamPrefs.getGoldbergMode(appId)` (`:4517`) and calls `GoldbergPatcher.analyze()`
  (`:4531`) to seed GSE schema/achievements. **This synchronous, pre-boot, genuine-Steam-gated hook is the
  natural place to start/stop the real-Steam agent.**
- **The actual arg-string branch (where `-EpicPortal` is appended):** `getCommand()` builds
  `winhandler.exe <args>` and at `:8069-8078` appends the Epic launch args when
  `storeSource=="epic" && epicEos!="0"`. **This is the literal branch point the user is pointing at.**

## D2-2. Per-shortcut tagging (where a `launchMode` field lives)

- **Where tags are stamped:** `StarLaunchBridge.java` `writeShortcutAsync(...)` writes the `.desktop`
  `[Extra Data]` block (`:288-318`). Steam games get `storeSource=steam` + `steamAppId=<id>` (`:299-301`);
  Epic games get `storeSource=epic` + `epicAppName/epicSandboxId/epicCatalogId` + **`epicEos=1`** (`:306-310`).
  Verified on device — `Half-Life 2.desktop` `[Extra Data]` carries `storeSource=steam`, `steamAppId=220`,
  `eos=0`, and the full per-game settings incl. `emulator=fexcore`, `dxwrapper=dxvk+vkd3d`.
- **Where tags are read back at launch:** `shortcut.getExtra("storeSource")` / `getExtra("steamAppId")` /
  `getExtra("epicEos")` throughout `XServerDisplayActivity` (e.g. `:3993`, `:4029`, `:8074`).
- **Where a new `launchMode` would go — two options, pick one:**
  1. **`.desktop` `[Extra Data]` `launchMode=realsteam|goldberg|raw`** (stamped in `StarLaunchBridge.java:299`
     block, read at launch via `shortcut.getExtra("launchMode")` right beside the `:8073` Epic branch).
     **Recommended** — it mirrors the Epic hook exactly (the analog the user cited) and is read at the same
     launch site. Default `realsteam` off → falls back to current Goldberg behavior for legacy shortcuts.
  2. **`SteamPrefs` per-appId** — mirror `getGoldbergMode(appId)`: `SteamPrefs.kt:82-90` stores
     `goldberg_mode_<appId>` in SharedPreferences `steam_prefs`. A `steam_launchmode_<appId>` key would sit
     next to it and be read at `:4517`. Use this **only** if the mode should be keyed by appId rather than by
     shortcut (Goldberg's model). The `.desktop` option is cleaner for a per-shortcut toggle.

## D2-3. The toggle UI home

`SteamGameDetailActivity.kt` — the Steam game detail page, and the right host. It **already** owns the
launch-mode-style picker for Goldberg: a gear-menu popup titled **"Steam Emulator (Goldberg)"** (`:445`,
opened at `:1285`) driving a mode dropdown **Off / Regular / Experimental / Cold Client Loader** (`:2477-2480`),
persisted by `onGoldbergModeSelected()` → `GoldbergPatcher.applyModeAsync()` + `SteamPrefs.setGoldbergMode`
(`:1141-1151`), state field `goldbergMode` (`:218`). The **launch-mode** picker (Real Steam / Goldberg / Raw)
sits right beside this Goldberg gear/dialog — same screen, same "installed games only" gating, same
worker-thread-apply-then-persist shape. (The achievements plan already earmarked this page for the picker.)

## D2-4. The Epic comparison the user referenced — CONFIRMED (real, in-Bannerlator)

**Bannerlator HAS a genuine Epic online-launch hook — the user's premise is correct, and it is in *this* app,
not only BannerHub.** Evidence:

- `XServerDisplayActivity.java:8069-8078` — appends real-Epic launch args when `storeSource=="epic" &&
  epicEos!="0"`.
- `EpicLaunchArgs.java` — `buildArgString()` (`:76`) emits **`-EpicPortal`** (`:109`) + a **freshly-minted
  exchange-code AUTH triple** `-AUTH_LOGIN … -AUTH_PASSWORD=<exchangeCode> -AUTH_TYPE=exchangecode` (`:130-135`);
  file header (`:44`) states plainly *"NOT an emulator — the args hand the title a real, freshly-minted
  exchange code."* The exchange code is fetched live from Epic via `EpicSidecar.fetchExchangeCodeSync()`
  (`EpicSidecar.java:263-277`, the `oauth/exchange` endpoint).
- `EpicEosDetector.java` — real **EOS SDK** injection, gated `storeSource=epic && epicEos!=0` (`:155`).
- Tagged by `StarLaunchBridge.java:306-310` (`storeSource=epic`, `epicEos=1`), toggle in the shortcut settings.

**So the analog is exact:** Epic = per-shortcut (`storeSource=epic` + `epicEos` toggle), launch-time
(`getCommand` arg append), online-auth (real exchange code), **not** an emulator. Real Steam = per-shortcut
(`storeSource=steam` + a new `launchMode` toggle), launch-time (same branch), online-auth (genuine client +
real ticket), **not** Goldberg. Bannerlator's **Goldberg/achievements launch hook**
(`maybeSeedAndStartAchievementWatcher`) is the closest *Steam-side* precedent for a synchronous pre-boot,
genuine-Steam-gated action, and is where the agent start/stop belongs — but the **Epic EOS hook is the true
architectural twin** the user was recalling, and it genuinely exists here.

## D2-5. The delta to build (spec only — not implemented)

| # | New piece | Hooks into (file:line) |
|---|---|---|
| 1 | **`launchMode` field** (`realsteam`/`goldberg`/`raw`), default `goldberg` for legacy | write: `StarLaunchBridge.java:299` `[Extra Data]` block; read: `XServerDisplayActivity` beside `:8073`. (Or `SteamPrefs` per-appId mirroring `:82-90`.) |
| 2 | **Launch-mode toggle UI** on the detail page | `SteamGameDetailActivity.kt` beside the Goldberg picker (`:445`/`:1285`/`:2477`); persist via a new setter mirroring `onGoldbergModeSelected` (`:1141`). |
| 3 | **Agent start/teardown branch** on the launch hook | start in `maybeSeedAndStartAchievementWatcher()`-adjacent pre-boot slot (`:4478`, synchronous before `startEnvironmentComponents()`); when `launchMode==realsteam`, **skip** the Goldberg `steam_api64` swap and keep the game's genuine `steam_api64.dll` (mirror WinNative `restoreSteamApiDlls`). Teardown tied to the Wine game subprocess exit (mirror GameNative `SteamBootstrap.stop()` / WinNative `clean_shutdown.cpp`), **not** the CM foreground service. |
| 4 | **Env-block setter** | inject the §D1-4 env into the guest launch env (where `envVars`/`EXTRA_EXEC_ARGS` are assembled in `getCommand`, near `:8062`); `Steam3Master`, `SteamAppId`, `ValvePlatformMutex`, overlay-on. |
| 5 | **Genuine-DLL staging** (runtime, never bundled) | new stager into `<container>/.wine/drive_c/Program Files (x86)/Steam/` (path per §D1-3), sourced from Valve CDN or the user's install; mirror WinNative `WnSteamAssetsInstaller` / GameNative `BionicSteamAssetsDependency` shapes, but **download-only** (no APK bundle). |
| 6 | **`lsteamclient` bridge inject + disable-on-realsteam** | mirror GameNative `extractLsteamclientIntoPrefix`; for the genuine-PE path, ensure the game binds the **real** `steamclient64.dll` (disable Proton's `lsteamclient` so it isn't shimmed) — recipe already open on our `proton-wine` `*_add_steam` branches. |
| 7 | **Headless agent binary** (Phase 1b, our own code) | not a wiring delta — the clean-room `steam.exe` replacement itself (`CreateInterface(CLIENTENGINE_INTERFACE_VERSION005)` → `IClientUser::LogOn` with the JavaSteam token → install `steamservice` → seed registry → launch), referencing WinNative's GPL `wn-steam-launcher/main.cpp`. Gate all of 1-6 on this + assets-present. |

---

## Legal guardrails (hard rules for anything past the manual test)

- **Never bundle/re-host** Valve's Windows DLLs (`steamclient64.dll`, `steamservice.exe/.dll`, `vstdlib_s64`,
  `tier0_s64`). Source at runtime from **Valve's official bootstrapper/CDN** or the **user's own install**
  (GameNative model). The manual test may use the on-device GameHub V6 client for a one-off proof only.
- **Never** reuse GameHub's `SteamAgent.exe` binary or any leaked GameSir token (`gy939543405` /
  SteamID `76561198287233535`). The user logs in with **their own** account.
- Borrowed WinNative/GameNative code is **GPL-3.0** — keep attribution (Bannerlator is GPL-lineage).
- **Ceiling: VAC-only.** Never kernel anti-cheat (BattlEye/EAC/Vanguard) — no Android-Wine path exists.
- Keep the user's Steam email/SteamID out of any committed artifact or external output.

---

## Bottom line

The mechanism is understood and legally sourceable. The **single** thing between "should work" and "works" is
whether VAC's x86 anti-cheat module runs under FEX/box64 on ARM — so we test it **by hand first** (Deliverable 1),
and only pour engineering into the wiring (Deliverable 2) if TF2/L4D2 stays connected through a real VAC-secured
match.
