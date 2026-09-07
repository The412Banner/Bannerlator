# SteamLite By-Hand Prototype — On-Device Runbook (L4D2 / appId 550)

**Purpose:** the exact, executable procedure to reproduce Bannerlator's committed 8-step
"SteamLite" real-Steam launch (no Goldberg) **by hand**, in the `com.tencent.ig` container, to answer
the one open question: *does the x86 VAC anti-cheat module load and pass under this device's FEX/box64
x86→ARM64 translation?* (Ceiling: VAC-only.)

**Status of THIS document:** READ-ONLY PREP + a protective BACKUP are **DONE** (§1, §2). Everything in
**§4 (steps 3–8) is GATED — NOT YET RUN.** Do not stage into the prefix, edit the registry, start the
agent, or launch the game until the user approves. §4 is the copy-paste sequence to run *after* approval.

**Date:** 2026-08-27 · **Branch:** `feat/steam-vac-phase0` · **Reads-first:**
`STEAMLITE_PROJECT.md` §8.5, `re/GAMEHUB_REAL_LAUNCH_ORCHESTRATION.md`, `re/PHASE1A_VAC_TEST_AND_WIRING.md`.

> **PII / secrets — hard rules for anyone running this:**
> - **NEVER** print, paste, echo, or commit the owner's Steam **refresh token**. Every step below reads it
>   **on-device** from `steam_prefs.xml` into a shell var; it never appears in this file or in terminal output.
> - The owner's Steam **email / username / SteamID64** are redacted here. The launch writes the token into the
>   L4D2 `.desktop` (`execArgs`, a genuine-client CLI requirement) — that file is an **on-disk credential**;
>   §7 shreds it.
> - The staged `steam_client_0403` DLLs are **GameSir-re-hosted Valve Windows binaries** — throwaway test only,
>   **never shippable/bundled** (source Valve DLLs at runtime for the product).

---

## 1. Grounded facts (verified read-only on device, 2026-08-27)

### 1a. Container / prefix / shortcut — L4D2

| Fact | Value |
|---|---|
| Container id / name | **`3` / "P11-2 Arm"** |
| Container root (`rootDir` of prefix) | `/data/data/com.tencent.ig/files/imagefs/home/xuser-3/` |
| **WINEPREFIX** | `/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine` |
| Active-container symlink (app reaches prefix via this) | **`imagefs/home/xuser -> ./xuser-3`** — the app's env `WINEPREFIX=…/home/xuser/.wine` resolves here. **xuser-3 MUST be the active container at launch** (verify in §3). |
| `drive_c` | `…/home/xuser-3/.wine/drive_c` |
| imagefs root (guest `Z:` drive, launch CWD) | `/data/data/com.tencent.ig/files/imagefs` |
| Game install | `…/imagefs/steam_games/Left 4 Dead 2/left4dead2.exe` (365 056 B) — guest path `Z:\steam_games\Left 4 Dead 2\left4dead2.exe` |
| L4D2 shortcut | `…/home/xuser-3/.wine/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop` — `storeSource=steam`, `steamAppId=550`, `eos=0`, `Exec=wine Z:\…\left4dead2.exe` |
| Prefix registry files (seed target) | `…/xuser-3/.wine/{system.reg (3.85 MB), user.reg, userdef.reg}` |
| Prefix `C:\Program Files (x86)\Steam` | **does NOT exist yet** — clean staging target |
| Staging owner / SELinux (siblings) | uid/gid **`10249:10249`**, ctx **`u:object_r:app_data_file:s0:c249,c256,c512,c768`** |

### 1b. Refresh token (login credential)

- **File:** `/data/data/com.tencent.ig/shared_prefs/steam_prefs.xml`
- **Key:** `refresh_token` (`<string name="refresh_token">…</string>`) → referenced everywhere as
  **`…/shared_prefs/steam_prefs.xml:refresh_token`**, value **never** printed.
- **Present & non-empty:** yes — ~498-char JWT-style token (freshly updated, mtime 2026-08-27 19:30).
- **Owner's own account:** confirmed the **owner's** account (`username` masked; `account_id`, `steam_id_64`,
  `cell_id` all present). **NOT** the leaked GameSir dev acct `gy939543405` (`grep -c` = 0). Companion login
  key: `username` (read on-device alongside the token, never printed).

### 1c. SteamLite agent binary

| Item | Value |
|---|---|
| Runtime binary (V6, paired w/ client) | `/storage/emulated/0/Download/steamagent/SteamAgent.exe` (2 340 864 B; identical copy at `…/steamagent/SteamAgent2/SteamAgent.exe`) — packer-encrypted |
| Arch | **PE32+ x86_64** (e_lfanew `0x80`, machine field `0x8664` = AMD64) |
| Readable reference (V5 fam, strings) | `/sdcard/SteamAgent_unpacked.exe` |
| CLI flags (confirmed via strings) | `--username --password --token --applaunch --launchoption --offline --disablecloud --language --cellid --rememberme --version --help` |
| Socket / interface strings | `STEAMAGENT_PORT`, `127.0.0.1`, `CLIENTENGINE_INTERFACE_VERSION005`, `login_success`, `app_launch`, `Failed to get IClientEngine` |

### 1d. Genuine V6 client bundle (staging source)

- Root: `/storage/emulated/0/Download/steamagent/steam_client_0403/drive_c/Program Files (x86)/Steam/`
  (build **10520955**; manifest `install_root = drive_c/Program Files (x86)/Steam`, `readonly *.dll/*.exe`).
- Has **both** `steamclient.dll` (21 MB, 32-bit) **and** `steamclient64.dll` (25.7 MB, 64-bit) — L4D2 is
  **32-bit**, so it binds the **32-bit** `steamclient.dll`. Also `tier0_s{,64}.dll`, `vstdlib_s{,64}.dll`,
  `Steam.dll`, overlay DLLs, `bin/steamservice.exe`+`.dll`, `bin/x{64,86}launcher.exe`.
- **No `steam.exe`** (the agent replaces it). **No baked session** (`config/` has default `config.vdf`, no
  `loginusers.vdf`) → logs in fresh with the runtime `--token`. CLIENTENGINE pin **v005**.
- Stale artifact inside the bundle: `Steam/GameOverlayRenderer.log` (108 KB) — note its mtime as a baseline.

### 1e. Wine / box64 / FEX invocation pattern (how the container launches guest programs)

- **wine binary:** `/data/data/com.tencent.ig/files/contents/Proton/11.0-2-arm64ec-1/bin/wine`
  (winePath = `…/contents/Proton/11.0-2-arm64ec-1`; set via `imageFs.setWinePath(wineInfo.path)`).
- **Command shape (arm64ec + fexcore), from `GuestProgramLauncherComponent` + `XServerDisplayActivity:5276`:**
  `<winePath>/bin/wine explorer /desktop=shell,1280x720 winhandler.exe /dir <exeDir> "<exe>" <execArgs>`
  with **`HODLL=libwow64fex.dll`** (fexcore). `winhandler.exe` is Bannerlator's launcher that spawns `<exe>`
  (from the `.desktop` `Exec`) with `<execArgs>` (the `execArgs` extra) — this is the same branch that appends
  `-EpicPortal` for Epic.
- **Env** (excerpt): `WINEPREFIX=…/home/xuser/.wine`, `HOME=…/home/xuser`, `USER=xuser`,
  `PATH=<winePath>/bin:…/usr/bin`, `LD_LIBRARY_PATH=…/usr/lib:/system/lib64`, `DISPLAY=:0`, box64/FEX preset
  env, **CWD = imagefs root**. Container `envVars`: `WINEESYNC=1 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact
  MESA_SHADER_CACHE_* mesa_glthread=true TU_DEBUG=noconform,sysmem DXVK_HUD=fps,api WRAPPER_MAX_IMAGE_COUNT=0`.
- Emulator = **fexcore** (`FEX-2608+45-Nightly`, preset `PERFORMANCE_TSO`); box64 `0.4.1-0` `EXTREME`;
  dxwrapper `dxvk-2.4.1-1-gplasync` + `vkd3d-3.0.1`; audio `directaudio`; Proton `11.0-2-arm64ec-1`.
- **`WINESTEAMCLIENTPATH*` is NOT set** in the container config (good — §5 of the recipe requires it unset).
- **Design implication (what makes the by-hand test valid):** the agent and the game must run **as Wine
  processes under the one wineserver Bannerlator boots** — same as GameHub. So the lever is the L4D2
  **`.desktop`**: repoint it at the **staged agent** with `--applaunch 550`; the agent (like `steam.exe`)
  logs in and launches `left4dead2.exe -steam` itself, all under the container's normal boot (X `:0`,
  wineserver, sysvshm, fakeinput, box64/FEX, HODLL, DNS set up exactly as usual).

### 1f. L4D2 `steam_api` status — **GENUINE Valve, never Goldberg-swapped**

- `…/Left 4 Dead 2/bin/steam_api.dll` (263 080 B, sha256 `25a1975363e587f7c64eee7cf2fdcb02eb7511e178f1ae0b38783322a2bd6339`):
  carries the classic Valve string *"Either launch the game from Steam, or put the file steam_appid.txt
  containing the correct appID…"*, **no** `goldberg`/`gbe_fork`/`Detanup`/`steam_settings` markers, **no**
  `steamclient*.dll` dropped beside the game, **no** `steam_settings/` folder → **genuine.** No un-Goldberg step
  needed (GoldbergPatcher never ran on it; nothing to `restore()`).
- **Anomaly to handle:** `…/bin/steam_appid.txt` = **`879`** (WRONG — L4D2 is 550; sha256
  `4f97f2eebf92cde58c103466712fa2f65b10d06ff8f1934d78ff592fa0575e27`). The genuine `steam_api` reads this and
  would report app **879**. The real-Steam path (recipe §6) uses **no** `steam_appid.txt`, so §4 moves it aside
  (already in the backup).

---

## 2. Protective backup — TAKEN & VERIFIED ✅

**Location:** `/sdcard/Download/steamlite-proto-backup/20260827/`

| File | What it restores |
|---|---|
| **`steam_api.dll`** (263 080 B, sha256 `25a1975…6339`) | the **genuine** L4D2 `steam_api.dll` — plain file, fast single-file restore |
| **`steam_appid.txt`** (3 B, `879`) | the original (stale) appid file |
| `l4d2-bin.tar.gz` (44 MB, 82 entries, `gzip -t` OK) | belt-and-braces — the whole L4D2 `bin/` (genuine dll + all engine DLLs) if `bin/` is ever damaged |
| `system.reg`, `user.reg`, `userdef.reg` | the prefix registry (pre-seed state) |
| `Left 4 Dead 2.desktop` | the original L4D2 shortcut (pre-repoint) |
| `container-3.json` | the container config (pre any env edit) |

> The full 13 GB game dir was **deliberately NOT** archived — only `bin/` (the sole mutated game area) plus the
> two single files above. L4D2 is 32-bit: there is **no** `steam_api64.dll`, only `bin/steam_api.dll`.

Source SHAs recorded above (§1f). **Restore commands are in §7.**

---

## 3. Pre-flight (run BEFORE §4 — read-only, safe)

```
# 1. Root bridge alive
bridge 'id'                                                     # expect uid=0
# 2. xuser-3 is the ACTIVE container (else staging into xuser-3 won't be used)
bridge 'ls -l /data/data/com.tencent.ig/files/imagefs/home/xuser | head -1'   # expect -> ./xuser-3
# 3. Token still present (length only — never the value)
bridge 'sed -n "s/.*name=\"refresh_token\">\([^<]*\)<.*/\1/p" /data/data/com.tencent.ig/shared_prefs/steam_prefs.xml | wc -c'   # expect ~499
# 4. Staging source present
bridge 'ls "/storage/emulated/0/Download/steamagent/steam_client_0403/drive_c/Program Files (x86)/Steam/steamclient.dll" /storage/emulated/0/Download/steamagent/SteamAgent.exe'
# 5. (optional, DEBUG RULE) confirm the installed Bannerlator APK is the intended build before trusting device behavior
bridge 'pm path com.tencent.ig'
```

---

## 4. ⛔ GATED — the staging + launch runbook (steps 3–8). DO NOT RUN until approved.

Two multi-line helper scripts are used (bridge multi-line resets the connection, so **create these files in
your own Termux shell** — Termux can write `/sdcard/Download/` without root — then run each via `bridge`).
Neither script contains the token; the token is read on-device at run time.

### Step 3 — stage genuine client + agent into the prefix; neutralize wrong `steam_appid.txt`

Create `/sdcard/Download/steamlite/stage.sh`:
```sh
#!/system/bin/sh
set -e
IMAGEFS=/data/data/com.tencent.ig/files/imagefs
PREFIX="$IMAGEFS/home/xuser-3/.wine"
STEAMDIR="$PREFIX/drive_c/Program Files (x86)/Steam"
SRC=/storage/emulated/0/Download/steamagent
GAMEBIN="$IMAGEFS/steam_games/Left 4 Dead 2/bin"
APPUID=10249
SECTX='u:object_r:app_data_file:s0:c249,c256,c512,c768'

# 3a. genuine V6 client -> C:\Program Files (x86)\Steam
mkdir -p "$STEAMDIR"
cp -a "$SRC/steam_client_0403/drive_c/Program Files (x86)/Steam/." "$STEAMDIR/"

# 3b. SteamLite agent -> beside the client (so it loads steamclient64.dll from its own dir)
cp -a "$SRC/SteamAgent.exe" "$STEAMDIR/SteamAgent.exe"

# 3c. move the stale/wrong steam_appid.txt (879 != 550) aside; real-Steam uses none
[ -f "$GAMEBIN/steam_appid.txt" ] && mv "$GAMEBIN/steam_appid.txt" "$GAMEBIN/steam_appid.txt.steamlite-bak"

# 3d. REQUIRED: give the staged tree the app's uid + SELinux label, else the app (uid 10249) can't read/exec
chown -R "$APPUID:$APPUID" "$STEAMDIR"
restorecon -RF "$STEAMDIR" 2>/dev/null || chcon -R "$SECTX" "$STEAMDIR"
echo STAGE_DONE
```
Run + verify:
```
bridge 'sh /sdcard/Download/steamlite/stage.sh'
bridge 'stat -c "%u %C %n" "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine/drive_c/Program Files (x86)/Steam/steamclient.dll"'   # expect 10249 + app_data_file ctx
```

### Step 4 — registry seed

The **agent seeds `HKLM\Software\Valve\Steam` itself** (recipe §4: `SteamExe`=agent, `SteamPath`,
`SteamClientDll{,64}`, `InstallPath`, `SteamPID`), and the running genuine client maintains
`HKCU\…\Steam\ActiveProcess`. **So no manual registry edit is required for the primary flow.**
*(Fallback only — if the agent fails to write the key: with the container booted, drop
`re/PHASE1A_VAC_TEST_AND_WIRING.md` §D1-4's `.reg` into `drive_c` and `reg import` it, or run the
`wine reg add …` lines there. Do not hand-edit `system.reg`.)*

### Step 5 — repoint the L4D2 shortcut at the agent (`--applaunch 550`); token read on-device

Create `/sdcard/Download/steamlite/shortcut.sh` (reads the token **on-device**; the token never prints and
never enters this runbook — only the produced `.desktop` will hold it, shredded in §7):
```sh
#!/system/bin/sh
set -e
PREFIX=/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine
DESKTOP="$PREFIX/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop"
PREFS=/data/data/com.tencent.ig/shared_prefs/steam_prefs.xml
APPUID=10249
SECTX='u:object_r:app_data_file:s0:c249,c256,c512,c768'

TOK="$(sed -n 's/.*name="refresh_token">\([^<]*\)<.*/\1/p' "$PREFS")"
ACCT="$(sed -n 's/.*name="username">\([^<]*\)<.*/\1/p' "$PREFS")"
[ -n "$TOK" ] && [ -n "$ACCT" ] || { echo "MISSING token/username"; exit 1; }

# 4x '\' per separator = the exact escaping StringUtils.unescape expects (matches the original Exec).
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Name=Left 4 Dead 2
Exec=wine C:\\\\Program Files (x86)\\\\Steam\\\\SteamAgent.exe
Icon=Left 4 Dead 2
Type=Application
StartupWMClass=explorer

[Extra Data]
storeSource=steam
steamAppId=550
customCoverArtPath=/data/user/0/com.tencent.ig/files/imagefs/home/xuser-3/app_data/cover_arts/Left 4 Dead 2.png
eos=0
execArgs=--username $ACCT --token $TOK --applaunch 550
EOF

chown "$APPUID:$APPUID" "$DESKTOP"
restorecon -F "$DESKTOP" 2>/dev/null || chcon "$SECTX" "$DESKTOP"
echo SHORTCUT_DONE
```
Run + verify (verify with the token masked):
```
bridge 'sh /sdcard/Download/steamlite/shortcut.sh'
bridge 'sed -E "s/--token [^ ]+/--token <REDACTED>/" "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop"'
```

*(Optional insurance for the game's Steamworks env — the agent's `--applaunch` normally sets these for the
child like `steam.exe` does. If SteamAPI_Init misidentifies the app, add `SteamAppId=550 SteamGameId=550
SteamClientLaunch=1` to the container `envVars` in `container-3.json` — a mutation covered by the
`container-3.json` backup — and re-launch.)*

### Step 6 — start it (block-until-login is internal to the agent)

**Launch "Left 4 Dead 2" from the Bannerlator UI (container "P11-2 Arm").** This boots the container normally;
`winhandler.exe` runs `SteamAgent.exe`; the agent loads the genuine `steamclient64.dll`
(`CLIENTENGINE_INTERFACE_VERSION005`), installs `steamservice.exe`, logs in the owner's session with `--token`,
seeds the registry, then (via `--applaunch 550`) launches `left4dead2.exe -steam` itself. The login→launch gate
(recipe step 6) is thus internal to the agent; watch the client logs (§5) for `login_success` / connection.

### Step 7 — the game runs (agent-driven)

`left4dead2.exe -steam` starts on its **own genuine `steam_api.dll`**, attaching in-process to the running
genuine client. No dll swap, no `steam_appid.txt`, no `WINESTEAMCLIENTPATH`. In L4D2: **server browser →
Internet → filter "Secure" (VAC) → join a populated VAC-Secured server** (real players, not a bots-only local
game). (Add `-condebug` to the game to get `left4dead2/console.log` — either via the container's per-game
launch args, or append it to the agent's `--launchoption`.)

### Step 8 — teardown

On game exit the agent exits and `winhandler` ends the launch. Then **run §7 rollback** to restore the original
shortcut (removes the on-disk token), the `steam_appid.txt`, and (if edited) the container env — before doing
anything else.

> **Variant (explicit 8-step, more control):** instead of `--applaunch`, point the shortcut at
> `wine cmd /c C:\SteamLite\run.bat` where `run.bat` does: `reg import` (step 4) → start `SteamAgent.exe`
> **without** `--applaunch` (step 5) → `timeout /t 25` as the login gate (step 6) → `start "" /d "Z:\steam_games\Left 4 Dead 2" left4dead2.exe -steam` with `set SteamAppId=550 …` (step 7). Use this only if the
> agent's self-launch proves unreliable under Bannerlator; it maps 1:1 to the committed 8 steps.

---

## 5. Log capture — run during / right after the attempt

```
# Genuine Steam CLIENT logs inside the prefix (connection / VAC / bootstrap)
bridge 'ls -t "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine/drive_c/Program Files (x86)/Steam/logs/" 2>/dev/null'
bridge 'grep -iaE "vac|secure|VAC_|BeginAuthSession|AuthenticateUserTicket|steamservice|reject|kick|login" "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine/drive_c/Program Files (x86)/Steam/logs/connection_log.txt" 2>/dev/null | tail -40'
# Overlay = the genuine client injected into the game (GameID/OverlayGameID = 550)
bridge 'grep -iaE "GameID|OverlayGameID|Hooking" "/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine/drive_c/Program Files (x86)/Steam/GameOverlayRenderer.log" 2>/dev/null | tail -20'
# Game console (needs -steam launch with -condebug)
bridge 'grep -iaE "vac|secure|connection|challenge|reject|steam" "/data/data/com.tencent.ig/files/imagefs/steam_games/Left 4 Dead 2/left4dead2/console.log" 2>/dev/null | tail -40'
# Bannerlator Wine debug log (ENABLE first: Settings > Log Manager > Wine debug). Default location:
bridge 'ls -t /sdcard/Android/data/com.tencent.ig/files/ 2>/dev/null | head'
bridge 'find /sdcard/Android/data/com.tencent.ig/files -maxdepth 3 -iname "wine_debug.log" 2>/dev/null'
# Live logcat around the attempt
bridge 'logcat -d' 2>/dev/null | grep -iE 'steam|vac|secure|SteamStatus|launch_failed|game_terminated|lsteamclient'
```
Decisive greps: `vac`, `secure`, `VAC_`, `BeginAuthSession`, `AuthenticateUserTicket`, `steamservice`,
`Loaded layer VK_LAYER_VALVE_steam_overlay`. Confirm `err:module:use_lsteamclient lsteamclient disabled`
(the game must hit the **real** PE `steamclient.dll`, not Proton's shim).

**PII:** these logs carry the owner's Steam email + SteamID64 — **redact before any commit or external output.**

---

## 6. ⭐ The ONE pass/fail signal

- **PASS** — you **spawn and keep playing on a VAC-Secured server with real players, sustained past the ~5–9 s
  mark** (give it minutes). The x86 VAC module loaded and reported clean under this device's FEX/box64
  translation → green light for Phase 1b.
- **FAIL** — a disconnect reading **"VAC authentication error"** / **"unable to verify your game session"**
  (or an immediate VAC kick, or the early-death `game_terminated` / `launch_failed 3005` pattern before VAC is
  even reached — inconclusive on VAC; fix launch, retest). Capture the **verbatim** disconnect string.

**After the test (regardless of outcome):** run §7 rollback, then **PULL-BACK** the terminal:
```
bridge 'am start -n com.termux/.app.TermuxActivity'
```

---

## 7. Rollback / restore (returns the prototype to pristine)

```
B=/sdcard/Download/steamlite-proto-backup/20260827
IMAGEFS=/data/data/com.tencent.ig/files/imagefs
PREFIX="$IMAGEFS/home/xuser-3/.wine"

# 1. Restore the original shortcut (REMOVES the on-disk token) + shred the mutated one
bridge "shred -u \"$PREFIX/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop\" 2>/dev/null; cp -a \"$B/Left 4 Dead 2.desktop\" \"$PREFIX/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop\""
bridge "chown 10249:10249 \"$PREFIX/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop\"; restorecon -F \"$PREFIX/drive_c/users/xuser/Desktop/Left 4 Dead 2.desktop\""

# 2. Remove the staged genuine client + agent (prefix Steam dir did not exist before)
bridge "rm -rf \"$PREFIX/drive_c/Program Files (x86)/Steam\""

# 3. Restore steam_appid.txt (move the .steamlite-bak back; or restore the plain backup copy)
bridge "mv \"$IMAGEFS/steam_games/Left 4 Dead 2/bin/steam_appid.txt.steamlite-bak\" \"$IMAGEFS/steam_games/Left 4 Dead 2/bin/steam_appid.txt\" 2>/dev/null || cp -a \"$B/steam_appid.txt\" \"$IMAGEFS/steam_games/Left 4 Dead 2/bin/steam_appid.txt\""
# (if steam_api.dll was ever overwritten, restore the genuine single file:)
# bridge "cp -a \"$B/steam_api.dll\" \"$IMAGEFS/steam_games/Left 4 Dead 2/bin/steam_api.dll\" && chown 10249:10249 \"$IMAGEFS/steam_games/Left 4 Dead 2/bin/steam_api.dll\" && restorecon -F \"$IMAGEFS/steam_games/Left 4 Dead 2/bin/steam_api.dll\""

# 4. Restore prefix registry (only if the agent/fallback wrote to it)
bridge "cp -a \"$B/system.reg\" \"$PREFIX/system.reg\"; cp -a \"$B/user.reg\" \"$PREFIX/user.reg\"; cp -a \"$B/userdef.reg\" \"$PREFIX/userdef.reg\""
bridge "chown 10249:10249 \"$PREFIX/system.reg\" \"$PREFIX/user.reg\" \"$PREFIX/userdef.reg\"; restorecon -F \"$PREFIX/system.reg\" \"$PREFIX/user.reg\" \"$PREFIX/userdef.reg\""

# 5. Restore container config (only if you edited envVars)
bridge "cp -a \"$B/container-3.json\" \"$IMAGEFS/home/xuser-3/.container\"; chown 10249:10249 \"$IMAGEFS/home/xuser-3/.container\"; restorecon -F \"$IMAGEFS/home/xuser-3/.container\""

# 6. (full game restore, if bin/ was ever damaged) — replaces the whole bin/
bridge "cd \"$IMAGEFS/steam_games/Left 4 Dead 2\" && rm -rf bin && tar -xzf \"$B/l4d2-bin.tar.gz\" && chown -R 10249:10249 bin && restorecon -RF bin"
```

---

## 8. Blockers & risks (call these out before running)

1. **The agent-driven launch has never actually been executed in Bannerlator** (PHASE1A deferred this "Route B"
   because the bundle has no `steam.exe`, so it must be agent-driven). Unknowns: whether `winhandler`-launched
   `SteamAgent.exe` self-launches the game via `--applaunch` and sets the full child env here → §4's *Variant*
   (`cmd` orchestrator) is the fallback.
2. **`STEAMAGENT_PORT` telemetry socket** — the agent connects out to an Android `SteamAgentServer` that does
   **not** exist by-hand. It's best-effort telemetry (agent should still proceed); the login/pass signal comes
   from the client logs + actual gameplay, not the socket. If the agent stalls waiting on it, drop the port env
   or add an in-guest listener.
3. **lsteamclient / `WINESTEAMCLIENTPATH`** — the game must bind the **real** PE `steamclient.dll` (via the
   registry the agent seeds), not Proton's shim. `WINESTEAMCLIENTPATH*` is unset in this container (good);
   **confirm `lsteamclient disabled` in the Wine log** (§5). Coordinate the Proton/DLL-override side with the
   wine-compat engineer if the shim intercepts.
4. **32-bit game + 64-bit agent** — the agent drives `steamclient64.dll`; the game binds `steamclient.dll`
   (32-bit). Both must attach to the same `steamservice`/session (works on PC; unverified on ARM).
5. **On-disk token** — the launch writes the refresh token into the `.desktop` `execArgs` (a genuine-client CLI
   requirement). §7 step 1 shreds it. Do not leave it after the test.
6. **GameSir-re-hosted Valve DLLs** — `steam_client_0403` is throwaway-test-only; never ship/bundle.
7. **Ceiling** — even a PASS is VAC-class only; it never beats kernel anti-cheat. The core unknown this test
   answers is exactly #whether the x86 VAC module loads under FEX/box64 on ARM.
