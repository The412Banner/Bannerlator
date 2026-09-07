# M3 implementation map — `launchMode=RealSteam` + launch-method popup

Distilled from two thorough code-mapping passes (2026-08-28). **This is the resume doc** — with it, the
two implementation agents can be re-launched (or the work done by hand) WITHOUT re-exploring the codebase.
Branch `feat/steam-vac-phase0`. App module `app/` (`com.winlator.star`, **Jetpack Compose + Material3**).

## Status
- ✅ **DONE (in tree, uncommitted):** `app/src/main/java/com/winlator/star/store/SteamLiteComponent.kt` — download-on-demand
  installer for the hosted `steamlite-v1` package (clone of `GoldbergComponent`, catalog
  `https://raw.githubusercontent.com/The412Banner/winlator-contents/main/steamlite.json`, extracts to
  `{filesDir}/imagefs/opt/steamlite/`, marker `steam.exe`). API: `isInstalled(ctx)`, `installDir(ctx)`,
  `agentExe(ctx)`, `loadCatalogAsync{}`, `downloadAsync(ctx,progress,done)`.
- ⏭️ **TODO:** the two layers below (UI + launch hook). Two specialist agents were dispatched but died on the
  connection drop **before making any edits** — tree is clean.

## SHARED CONTRACT (both layers — EXACT literals)
- Per-shortcut method in `.desktop` `[Extra Data]` via `Shortcut.putExtra`/`getExtra(name,fallback)`/`saveData()`
  (free-form JSON map; `container/Shortcut.java:80-85,140-200`):
  - `launchMode` ∈ `"RealSteam"` | `"Goldberg"` | `"Raw"`. Empty/absent ⇒ not chosen ⇒ show popup.
  - `launchModeRemembered` = `"1"` when "Remember for this game" ticked (else show popup every launch).
- Goldberg sub-mode keeps EXISTING per-appId plumbing: `SteamPrefs.getGoldbergMode/setGoldbergMode` +
  `GoldbergPatcher.applyModeAsync`. Enum `GoldbergMode{OFF,REGULAR,EXPERIMENTAL,COLDCLIENT}` (`store/GoldbergPatcher.kt:23-32`).
- Agent env (`agent-src/main.cpp:168,896-907`): `WN_STEAM_TOKEN`,`WN_STEAM_USERNAME`,`WN_STEAM_STEAMID`,
  `WN_STEAM_APPID`,`WN_STEAM_GAMEEXE_FILE` (spec: line1=full Windows exe path under `steamapps\common`, line2=appId)
  + `PROTON_DISABLE_LSTEAMCLIENT=1`. Token is a REGISTERED SECRET — never log; pass only into `envVars`.

---
## LAYER A — UI / content / shortcut (Kotlin Compose). Agent = android-app-engineer.
Files: NEW `ui/screens/LaunchMethodSheet.kt`; edit `ShortcutsScreen.kt`, `BigPictureScreen.kt`, `SteamGameDetailActivity.kt`.
Visual reference: `/storage/emulated/0/Download/Bannerlator-Launch-Options-Mockup.html` (mini game-details bottom-sheet).

- **Single launch choke point:** `ui/screens/ShortcutsScreen.kt:7848 runShortcut(activity,shortcut)` builds the
  XServerDisplayActivity Intent (`container_id`,`shortcut_path`,`shortcut_name`,`disableXinput`). Tile-launch :858 +
  list-launch :886 both funnel here. Intercept: if `isSteamOriginShortcut(shortcut)` (:4510) AND NOT (launchMode set &&
  launchModeRemembered=="1") → set new state `launchChoiceFor=shortcut` (hoist near :320, by `settingsShortcut`) to open
  the sheet; else launch now. Factor the current body into `launchShortcutNow(activity,shortcut)`.
- **Couch launch:** `ui/screens/BigPictureScreen.kt:1529 launchShortcut(activity,shortcut)` — intercept the same way.
- **Steam gate exists:** `ShortcutsScreen.kt:4510 isSteamOriginShortcut`, `:4515 steamAppIdOf`.
- **Remove Goldberg from cog:** `store/SteamGameDetailActivity.kt:1509-1512` (the `GearMenuItem("🛡️","Goldberg mode"...)`).
  Keep other gear items (Choose branch :1503, Manage DLC :1506, Uninstall :1516). Goldberg state/dialog `:218-229`,
  `:1114-1150` (handlers), `:1867-1881` (invoke), `:2412-2596` (`GoldbergModeDialog`/`GoldbergSection`) — may leave dead or remove.
- **Sheet patterns:** `ui/screens/ContentDownloadSheet.kt:275` (`rememberModalBottomSheetState(skipPartiallyExpanded=true)`
  + `ModalBottomSheet`); radio-list style `SteamGameDetailActivity.kt:2464-2596`; `ui/screens/OutlinedAlertDialog.kt`.
- **Shortcut extras write template:** `store/StarLaunchBridge.java:295-318` (storeSource/steamAppId + Epic multi-field block).
- **On choice:** persist `launchMode`(+`launchModeRemembered`); SteamLite → if `!SteamLiteComponent.isInstalled` call
  `downloadAsync` (progress via ContentDownloadSheet/snackbar) then launch; Goldberg → `SteamPrefs.setGoldbergMode` +
  `GoldbergPatcher.applyModeAsync` + existing `GoldbergComponent` download; then `launchShortcutNow`.

---
## LAYER B — launch orchestration (Java). Agent = native-steam-engineer.
Files: NEW `store/RealSteamLauncher.java`; edit `XServerDisplayActivity.java` ONLY. Recipe source = `agent-src/test-scripts/{m0,m2,m2b}_setup.py` + tf2/css setup (the proven `.desktop`-repoint-to-agent flow).

- **Branch point:** `XServerDisplayActivity.java:4478 maybeSeedAndStartAchievementWatcher()`, called :5544 in
  `setupXEnvironment()` (launch worker thread) before `startEnvironmentComponents()` :5548. Goldberg-mode gate :4517.
- **Steam identity/appId:** `isGenuineSteamShortcut()` :3991; `resolveSteamIdentity()` :4330; `SteamAppRef{appId,installDir}` :4144;
  `resolveSteamAppRefFrom(execPath,storeSource,taggedAppId)` :4180 (Room via `SteamRepository.getInstance().getDatabase()` :4191,
  off-main-thread). `installDir` = game's `steamapps\common\<name>` root.
- **Env assembly:** field `envVars` (`EnvVars`) :285; per-shortcut env :5371; Epic env/prelaunch peer :5379-5382; final
  `guestProgramLauncherComponent.setEnvVars(envVars)` :5478. INJECT RealSteam env ~:5371-5382.
- **Launch command:** `getWineStartCommand()` :8033-8084 (execArgs :8041; `/dir <exeDir> "<exe>" <execArgs>` :8058; Epic
  arg-append twin :8069). Consumed :5276 (`wine explorer /desktop=shell,... winhandler.exe ...`). RealSteam: substitute exe
  target → `C:\Program Files (x86)\Steam\steam.exe` + execArgs = spec file.
- **Goldberg skip:** only read-only `analyze()` called at :4531 inside `mode!=OFF` (:4517) → RealSteam/OFF already skips.
- **Token/identity:** `SteamRepository.getInstance()` :96 → `getRefreshToken()` :1513, `getUsername()` :1512,
  `getSteamId64()` :1515, `getAccountId()` :1516 (prefs file "steam_prefs"). SECRET registered `SteamRepository.java:340`.
- **Guest spawn (ref):** `xenvironment/components/GuestProgramLauncherComponent.java:528-562`.
- **RealSteam staging (RealSteamLauncher):** (1) copy `imagefs/opt/steamlite/*` → prefix `drive_c/Program Files (x86)/Steam/`
  and `CommonFilesSteam/*` → `.../Common Files/Steam/`; (2) symlink game under `steamapps/common/<CanonicalName>` + write
  `steamapps/appmanifest_<appid>.acf` (`appid`,`name`,`installdir`,`StateFlags=4`) → secure LaunchApp; (3) write spec file
  `drive_c/<appid>.spec` (line1 Windows exe path, line2 appId); (4) env block (contract above), `WN_STEAM_GAMEEXE_FILE=C:\<appid>.spec`;
  (5) rewrite launch to run agent as steam.exe with spec execArgs; (6) NO Goldberg swap. Defensive fallback to normal launch if token/pkg missing.

---
## Resume steps
1. Re-dispatch the two agents (specs above) OR implement by hand from these anchors. Keep app COMPILING; leave uncommitted.
2. Review both changesets together (esp. token hygiene in Layer B). Commit as The412Banner (never mention Claude).
3. CI build (push branch, watch run) → on-device test on L4D2(550)/TF2(440)/CS:S(240) = **M4**. Device env already proven (xuser-3, container 3, Proton 11.0-2-arm64ec, `PROTON_DISABLE_LSTEAMCLIENT` works).
