# Rust Steam engine — parity sweep (`use_rust_steam_engine` ON)

Acceptance rule (Phase 3b-6): with the flag ON the app is a FULL replacement of JavaSteam —
no surface refuses, returns a null handler to a caller, or skips a step. JavaSteam paths are
byte-identical with the flag OFF. Status legend: **DONE** = implemented on the engine (or
engine-agnostic); **n/a** = the item has no meaning on the engine and no caller reaches it
under the flag (documented, not a gap). Nothing in this file is left open.

Branch `feat/blsteam-engine-p3` (stacked on p0 → p1 → p2); crate
`app/src/main/cpp/bl-steam-client/rust/` → `libblsteam.so`; Kotlin facades
`com.winlator.star.store.blsteam.*`. Device proof is still outstanding for every row — see
`docs/STEAM_RUST_ENGINE_PLAN.md` §0 for the per-phase test notes.

## 1. `SteamRepository` public API

| Method | Rust status | How |
|---|---|---|
| `getInstance`, `addListener`, `removeListener`, `emit` | DONE | engine-agnostic event bus (same `LoggedIn:` / `Connected` / `Disconnected` / `LibraryProgress:` / `LibrarySynced:` / `Download*:` strings) |
| `initialize` | DONE | reads the flag once; engine path also binds `BlSteamEngineLog` |
| `isConnected`, `isLoggedIn`, `isSessionLoggedOn`, `getStatus`, `getLastSessionStatus` | DONE | engine state → status pill (3a-1) |
| `connect`, `reconnectNow`, `disconnect`, `refreshFgsStatus` | DONE | `rustConnect` / `BlSteamEngine.start`/`stop`; FGS unchanged |
| `loginWithToken`, `ensureLoggedIn`, `reconnectAndRelogin` | DONE | engine token logon + reconnect ladder (2 s·n, 5) + storm guard (3a-1) |
| `loginWithCredentials`, `saveSession` (both), `logout` | DONE | `SteamAuthManager` / `SteamQrAuthManager` run the engine auth session on the connect-only channel; same `steam_prefs` keys; logout = ClientLogOff + prefs + redactor clear (3a-1) |
| `suspendForRealSteam`, `resumeAfterRealSteam`, `isSuspendedForRealSteam` | DONE | engine session torn down / re-driven around a SteamLite game; `PAUSED_FOR_GAME` kept |
| `setInGamePresence`, `clearInGamePresence`, `getInGameAppId` | DONE | `CMsgClientGamesPlayed` (3a-3) — JavaSteam path is a no-op by design |
| `setRichPresence` | DONE | `Player.SetRichPresence` (3a-3) |
| `syncLibrary`, `refreshAppProductInfo`, `resolveOwnedDlc`, `fetchAppKeyValues` | DONE | `BlLibraryCrawler` → the shared `processAppKv` parser; single-app hops via `fetchProductInfoKv` (1-A, 3b-1) |
| `getLicenses` | n/a on the engine (JavaSteam-typed `License` list; its only consumer is the JavaSteam depot path). Engine-agnostic reads: `SteamDatabase.getLicensedAppIds` / `getLicensedPackageIds`, raw JSON `BlSteamSession.getLicenseList()`; family-sharing (borrowed) licenses included with their package access tokens (3b-3) | — |
| `getCachedGameRows`, `invalidateGameCache`, `getLastSyncTime`, `getDatabase`, `submitLibraryWork`, `isDownloadActive`, `setDownloadActive` | DONE | engine-agnostic (DB / worker / flag) |
| `getBranches`, `getSelectableBranches`, `checkBranchPassword` | DONE | `ClientCheckAppBetaPassword` on the engine → `steam_unlocked_branches` (3b-3) |
| `getSelectedDownloadSize` | DONE | populated by the shared parser on both engines |
| `getDepotKey`, `requestDepotKey`, `getManifestCode`, `requestManifestCode`, `storeManifestCode`, `getCdnAuthToken`, `requestCdnAuthToken`, `storeCdnAuthToken`, `bumpPendingJobTimeouts` | n/a (JavaSteam depot-engine plumbing — keys, request codes and CDN tokens are resolved inside `libblsteam.so` per download; no engine caller) | — |
| `getSteamCloud`, `getSteamUserStats`, `getSteamFriends`, `getCallbackManager`, `getSteamClient`, `getSteamApps`, `getSteamContent` | n/a (JavaSteam handler getters; null under the engine by design). Every consumer branches first: cloud → `SteamCloudBackend`, achievements → `SteamAchievementStore` engine branch, friends → `SteamFriendsStore` engine branch / agent relay, PICS → `fetchAppKeyValues`, sizes → `DepotSizeResolver` engine branch, auth → engine auth session, downloads → `BlDepotInstaller` | — |
| `getUsername`, `getRefreshToken`, `getAccessToken`, `getSteamId64`, `getAccountId`, `getDisplayName`, `setDisplayName`, `appContextOrNull` | DONE | prefs (written by the engine paths) |

## 2. Store classes / surfaces (checklist)

| Surface | Rust status | Where |
|---|---|---|
| **auth** — credentials, Steam Guard e-mail / mobile code / mobile confirmation, QR | DONE | `SteamAuthManager` / `SteamQrAuthManager` Rust branches → `BlSteamSession.startLoginWithCredentials` / `startLoginWithQr` (3a-1) |
| **session** — token logon, self-heal, network callback, logged-in-elsewhere, refresh-token renewal | DONE | `SteamRepository` engine listener, `SteamSessionManager.maybeRenewRefreshToken` (1-B, 3a-1) |
| **FGS** (`SteamForegroundService`) | DONE | engine-agnostic (status text from the repository) |
| **library** — licenses → packages → app tokens → appinfo, DLC, branches, depots | DONE | `BlLibraryCrawler` + shared parser (1-A); parity diff `BL_STEAM_PICS` |
| **licenses / family sharing** | DONE | engine license list incl. borrowed licenses (3b-3) |
| **branches / beta passwords** | DONE | `checkBranchPassword` + encrypted-manifest gid decryption in `BlDepotInstaller` (3b-3) |
| **downloads** — install / resume / pause / cancel / queue | DONE | `BlDepotInstaller` → `BlSteamSession.downloadApp` (2-A) |
| **verify** / **update** (`SteamGameUpdater`) | DONE | fresh PICS check + delta/verify passes through 2-A (2-B) |
| **SD** (`SteamSdInstall`, "Install to SD card") | DONE | `installRoot` + free-space guard in `BlDepotInstaller` (2-A) |
| **speed limit** (`DownloadSpeedConfig`) | DONE | tier → `maxDownloads` = engine worker count (`maxWorkers`, clamped 1..32) — the native pipeline's one throughput knob (3b-3) |
| **Download Manager** (`DownloadManagerActivity` / `DownloadRegistry`) | DONE | registry row upserted/updated on every engine progress callback + terminal states (2-A) |
| **depot sizes** (`DepotSizeResolver`) | DONE | `nativeFetchManifestSizes` metadata-only manifest fetch (3b-3) |
| **cloud** (`SteamCloudSaveManager`, `SaveSyncStore`, `SteamSaveManagerActivity`, pre-flight row, exit auto-upload, `SteamCloudSavePaths`) | DONE | `SteamCloudBackend` → engine `ccloud` calls; `%SteamUserBaseStorage%` root added (3b-1) |
| **achievements** (`SteamAchievementStore` fetch / cached / lookup / seedGse / schema / sync-back, `AchievementWatcher`, `SteamLiteAchievementWatcher`, unlock pill) | DONE | `nativeGetUserStatsJson` + `nativeStoreUserStatsBlocking`; watchers + pill are DB/file based (3b-2) |
| **friends** — roster, invites (add / accept / decline / cancel / remove), nicknames, avatars, quick-invite link | DONE | `BlSocialFeed` + `BlSteamSession` social calls (3a-2) |
| **chat** — send, receive, typing, history, images | DONE | 3a-2; **during a SteamLite game**: agent p3 relay (`SteamAgentFriendsBridge`, 3b-5; typing send is a documented no-op on the relay) |
| **images** (`SteamChatImageUploader`) | DONE | web token via `nativeGenerateWebAccessToken` (3a-2) |
| **notifications** (`SteamChatNotifier`) | DONE | same receive path on the engine and on the relay |
| **presence read** (friend persona / game / rich presence) | DONE | `CMsgClientPersonaState` decode (3a-2); relay `persona` events while paused (3b-5) |
| **presence set** (Online/Offline, in-game, rich presence) | DONE | `setPersonaState` / `CMsgClientGamesPlayed` / `Player.SetRichPresence` (3a-2, 3a-3) |
| **profile** (`SteamFriendProfileScreen`, `fetchProfile`) | DONE | `CMsgClientFriendProfileInfo` + `Player.GetOwnedGames` (3a-2) |
| **user search** (`SteamUserSearch`) | DONE | community cookie from the engine web token (3a-2) |
| **persona** (self name / avatar, `setPersonaName`) | DONE | `ClientAccountInfo` + `nativeSetPersonaName` (3a-1/3a-2) |
| **region** (`SteamRegion`) | DONE | engine CM pick + CDN preference + cell id (2-C) |
| **pre-flight** (`SteamSessionManager.preflightAsync`, `SteamPreflightDialog`) | DONE | session → cloud pull (engine, 3b-1) → update check (engine, 2-B) |
| **agent channel** (`SteamAgentChannel`) | DONE | engine-agnostic; p3 friends relay routed to the bridge (3b-5) |
| **diagnostics** (`SteamLiteLogCollector`, Log Manager capture, `SteamLogRedactor`) | DONE | `BlSteamEngineLog` folded into `steamlite.txt`; redaction at the source + final audit (3b-4) |
| **in-game presence** (Goldberg/Raw launches reported as playing) | DONE | 3a-3 |

## 3. Refusal audit

`grep -rn "isRustEngine" app/src/main/java` — every branch selects the engine implementation;
none returns "not available", null-to-caller or skips a step. The last ones removed in 3b:
the pre-flight cloud skip (3b-1), the achievement store's null-handler early returns (3b-2), the
beta-branch refusal in `BlDepotInstaller` and the `DepotSizeResolver` fallback (3b-3).

## 4. Not in scope of parity (unchanged on purpose)

- SteamLite (genuine-Valve VAC path) and Goldberg keep reading `steam_prefs`; the engine
  writes the same keys.
- JavaSteam remains selectable (flag OFF) until the engine is device-proven per surface;
  Phase 4 (remove the dependency) is gated on that proof.
