# SteamLite agent — on-device reproduction (M0–M2)

Proven 2026-08-27 on-device (container `com.tencent.ig` / `xuser-3`, Proton 11.0-2-arm64ec + FEX,
Adreno 750): **our own clean-room agent logs into genuine Steam and launches L4D2 (550) securely
(via `LaunchApp`, `-steam`) — online, VAC-capable, no DRM, no GameHub binary/runtime.**

## Device state left set up (ready to relaunch)
- `xuser-3` prefix `C:\Program Files (x86)\Steam\` = WinNative's offset-matched genuine client + our
  `steam.exe` (= `our_steam.exe`, built from `agent-src/`). `steamservice` in `Common Files\Steam`.
- `steamapps\common\Left 4 Dead 2` → **symlink** to `imagefs/steam_games/Left 4 Dead 2` (so LaunchApp
  finds it installed). `C:\gameexe.txt` = that steamapps path.
- Container `.container` `envVars` has: `PROTON_DISABLE_LSTEAMCLIENT=1 WN_STEAM_TOKEN=<user token>
  WN_STEAM_USERNAME=<acct> WN_STEAM_STEAMID=<sid> WN_STEAM_APPID=550 WN_STEAM_GAMEEXE_FILE=C:/gameexe.txt`.
  ⚠️ the refresh token is on-disk here — restore `.container` from the backup to remove it.
- Backup of the original container state: `/sdcard/Download/steamlite-proto-backup/20260827/`.

## Relaunch (one command) — then join a VAC server
```
bridge 'DC="/data/data/com.tencent.ig/files/imagefs/home/xuser-3/.wine/drive_c"; rm -f "$DC/wn-launcher.log" "$DC/wn-launcher.stop"; am start -n com.tencent.ig/com.winlator.star.XServerDisplayActivity --ei container_id 3 --es shortcut_path "$DC/users/xuser/Desktop/Left 4 Dead 2.desktop" --es shortcut_name "Left 4 Dead 2"'
```
Wait for the intro → main menu → **Play → Online / Campaign → join a VAC-Secured server**.
Watch `wn-launcher.log` for `LaunchApp ... EAppUpdateError=0` + `left4dead2.exe -steam` = the secure launch.
Add `-novid` to skip the intro (append to `C:\gameexe.txt` args if wired, or via Steam launch options).

## Rebuild the agent (if `agent-src/main.cpp` changes)
Needs `g++-mingw-w64-x86-64` (installed via `fakeroot apt-get install`):
```
cd agent-src && x86_64-w64-mingw32-g++-posix -std=c++17 -O2 -w -static -static-libgcc -static-libstdc++ \
  -Wl,--subsystem,windows -I. -o /sdcard/Download/steamlite/m0/our_steam.exe main.cpp clean_shutdown.cpp \
  -ladvapi32 -lkernel32 -luser32 && x86_64-w64-mingw32-strip /sdcard/Download/steamlite/m0/our_steam.exe
```
Then re-stage into the prefix `Steam\steam.exe` (cp + chown 10249 + restorecon) and relaunch.

## Setup scripts (run via `bridge 'python3 <path>'` with termux python on PATH)
- `m0_setup.py` — stage client+agent, WN_STEAM_* login env, shortcut→steam.exe (M0 login-only).
- `m2_setup.py` — add WN_STEAM_APPID + WN_STEAM_GAMEEXE_FILE + write C:\gameexe.txt (game launch).
- `m2b_setup.py` — symlink L4D2 into steamapps\common (enables secure LaunchApp).
- `agentserver.py` — a stub SteamAgentServer listener (NOT needed for our agent; was for the GameHub-binary attempt).

## What's left (M3/M4)
- Confirm a real VAC-server match holds (user joins one).
- M3: build this into Bannerlator as `launchMode=RealSteam` (XServerDisplayActivity hook + detail-page
  picker), source the genuine Valve DLLs at runtime (never bundle), our JavaSteam mints the token,
  register the game in steamapps\common automatically. M4: prove on TF2/CS:S/L4D2.
