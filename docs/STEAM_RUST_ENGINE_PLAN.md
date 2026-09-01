# Bannerlator Unified Native Rust Steam Engine — Phased Plan

Status: **Phase 0 DONE, Phase 1 (A/B/C) IMPLEMENTED — awaiting CI + device proof.** See "Status" below.
Author basis: direct study of WinNative's `wnsteam` (local clone `/home/claude-user/winnative`, tip `eaa4640`) + Bannerlator current state (`/home/claude-user/bannerlators-mainrel`, main `f68522b3`).

---

## 0. Status (living section)

| Phase | State | Where |
|---|---|---|
| **0 — toolchain, crate, CI, connect/login MVP** | **DONE** (`feat/blsteam-engine-p0` @ `f4f2c47e`): crate vendored as `app/src/main/cpp/bl-steam-client/` → `libblsteam.so`; Kotlin facades `com.winlator.star.store.blsteam.*`; CI cargo step + caches; hidden flag `use_rust_steam_engine` (default OFF, Log Manager dev toggle); `SteamRepository` routes connect/logon to `BlSteamEngine` when ON. | branch p0 |
| **1-A — owned library / PICS on the engine (flag ON)** | **IMPLEMENTED, not device-proven.** `BlLibraryCrawler` (license list → package info → app tokens → app product-info in 25-app batches) feeds the SAME per-app parser (`SteamRepository.processAppKv`, extracted verbatim from the JavaSteam path) and the SAME `steam_*` rows + `LibraryProgress`/`LibrarySynced` events. `syncLibrary` / `refreshAppProductInfo` / `resolveOwnedDlc` are engine-agnostic (`fetchProductInfoKv`). Dev parity diff under log tag **`BL_STEAM_PICS`** (+ `<externalFiles>/bl_steam_pics_diff.txt`): snapshot of the previous engine's rows vs the Rust crawl, app-by-app. `BlSteamEngine` now decodes logon-response / logged-off EResults, never retries a rejected token, caps re-logons (3/min). Downloads, cloud, achievements, friends still JavaSteam (honest refusal when ON). | branch p1 |
| **1-B — session doorman + launch pre-flight (ships to everyone)** | **IMPLEMENTED, not device-proven.** `store/SteamSessionManager` (`ensureSession`, `maybeRenewRefreshToken` [Rust: wires `BlSteamEngine.renewRefreshToken()` when the JWT `exp` is < 14 days out], `preflightAsync`: session → Steam Cloud pull → update check). `ui/screens/SteamPreflightDialog` ("Getting Steam ready") runs BEFORE `XServerDisplayActivity` for every RealSteam launch (popup pick + remembered); Sign in / Launch with Goldberg / Retry / Launch anyway / Update. Activity skips its own cloud pull on `preflightDone`. Goldberg/Raw/non-Steam launches untouched. | branch p1 |
| **1-C — live agent↔app channel** | **IMPLEMENTED, not device-proven.** Agent (`bl-wt-steam-vac/agent-src`, `steam.exe`, MinGW) connects to `127.0.0.1:$BL_AGENT_PORT` and streams newline JSON events (`started`, `logged_in`(masked), `login_failed`, `appinfo`, `launch_accepted`, `launch_refused`, `insecure_fallback`, `direct_exe`, `game_spawned{secure}`, `session_lost`, `achievement`, `game_exited`, `shutdown`, `status`); accepts `{"cmd":"status"|"logoff"}`. App: `store/SteamAgentChannel` (loopback `ServerSocket`), `RealSteamLauncher.prepare(..., agentPort)` → `BL_AGENT_PORT`, overlay hints from real state, failure card with Retry / Launch with Goldberg on `login_failed` / pre-render `insecure_fallback` / no sign-in in 75 s, `SteamLiteLogCollector` DIAGNOSTICS prefers the events. Rebuilt agent NOT published (needs an explicit go). | branch p1 + agent-src |
| 1 (plan §3 "Friends/chat/presence") | NOT started — deferred behind 1-A/B/C (the launch-reliability items were pulled forward). Stretch "chat over the agent socket" not done; message shapes TODO in `SteamAgentChannel` docs. | — |
| 2 — cloud / achievements / downloads on the engine | NOT started. The pre-flight's cloud step is SKIPPED when the flag is ON until 2b lands. | — |
| 3 / 4 | NOT started. | — |

**Phase-1 scope note.** The executed Phase 1 differs from §3's original ordering: library/PICS (originally 2a), the session doorman and the agent channel were pulled forward because they gate launch reliability; social (original Phase 1) moves after them.

---

## 1. Recommendation (read this first)

**Target architecture:** Adopt WinNative's `wnsteam` Rust engine as Bannerlator's single Android-side Steam brain — one `libwnsteam.so` (`libsteamclient.so` name is WN's *other* module; ours is `wnsteam`) driving friends/chat/presence + library/store + downloads + cloud saves + achievements, behind a thin JNI + Kotlin facade — **replacing the entire JavaSteam stack**. Keep the in-game VAC path (Bannerlator's genuine-Valve **SteamLite**) and Goldberg as-is; the Rust engine simply becomes their token/identity source, exactly as JavaSteam is today.

**Best-perf/compat verdict — split by failure domain, because they are genuinely different:**

- **Android-side brain (store/library/downloads/cloud/achievements/social): native Rust wins decisively on BOTH axes.**
  - *Performance:* native ARM64, no JVM — no GC pauses on the CM pump, no protobuf-reflection overhead, materially lower RAM than a `SteamClient` + `CallbackManager` + `protobuf-java` graph on the ART heap. The depot path is a self-contained streaming writer (`depot_writer.rs`, 1027 LOC) that spools to disk in native code — structurally lower-memory than the JVM depot engine Bannerlator forked specifically to dodge OOM. Startup is a single `System.loadLibrary` vs classloading a large JVM dependency.
  - *Compatibility:* one unified brain instead of JavaSteam-for-CM + a separate depot fork; **self-contained deps** (hand-rolled protobuf + VDF, `rustls` not OpenSSL — see §5) so no transitive JVM/proto conflicts; **resumable, retrying depot downloads** (`clean_pause_marker` + `manifest_retry_server_indices` + backoff) that directly target Bannerlator's known download-reliability failure domain (CM-reconnect → manifest-job timeout → "Unknown error"). It also carries native primitives for the dual-session problem (`is_playing_blocked` / `mark_playing_blocked` / `build_kick_playing_session` / `CMsgClientPlayingSessionState`).
- **In-game VAC path: native-in-app does NOT automatically win — genuine-Valve-in-Wine stays the VAC bet.** VAC-secure online play is proven today by running *real Valve binaries* inside Wine (Bannerlator SteamLite ≈ WinNative "PlanW"). WinNative's third path — an in-app **reimplemented** `libsteamclient.so` (bionic) that Wine's `lsteamclient.dll` talks to over loopback + a `LogonWithRefreshToken` vtable call — is lighter (no full Steam-client-in-Wine RAM/startup) but its VAC parity is **unproven for Bannerlator's titles**. Recommendation: adopt the Rust brain now; keep genuine SteamLite as the VAC path; treat WN's bionic bridge as an *optional later optimization*, gated behind on-device VAC proof, never a SteamLite retirement trigger until proven.

**Adapt vs ground-up: ADAPT `wnsteam` directly (GPL-3.0 → GPL-3.0, with attribution).** The WN Rust source is **complete and fork-friendly in the clone** (no LFS truncation in the Steam tree; protobufs are hand-written committed Rust over a custom `proto_wire` reader/writer — no `.proto`, no `prost`, no `protoc` codegen step; VDF hand-rolled; deps are pure-Rust/`rustls`). It is ~18 KLOC of layered, unit-tested Rust with a ready 73-function JNI facade and Kotlin facades. Ground-up would mean re-writing SteamKit-in-Rust (many months) for zero upside. Bannerlator and WinNative already **share Steam-Rust lineage** — `SteamChatImageUploader.kt` is annotated as ported from WN `chat_image.rs` — which de-risks the port. Adaptation effort is concentrated in mechanical integration (JNI package rename, CMake↔cargo wiring, CI toolchain, re-pointing one facade class), not in Steam protocol work. **Verdict: adapt.**

**Licensing (either path, but especially adapt):** `wnsteam` is `GPL-3.0-or-later`; Bannerlator is GPL-3.0 — compatible. Requirements: preserve WN copyright/headers, add a WinNative attribution in `NOTICE`/README, keep the derived module under GPL-3.0, and ship corresponding source. This is the same GPL-3.0 caveat already tracked for the ref4ik/Cmod-lineage ported Steam code.

**Does `wnsteam` actually do friends/chat/presence (or is that only GameHub's `steamkit_core`)?** `wnsteam` does it directly and fully. Evidence: `pb/cfriendmessages.rs` (SendMessage / IncomingMessage / GetRecentMessages), `cmsg_client_persona` (PersonaState), `cmsg_client_change_status` (set persona state), `cmsg_client_friends_list`, rich presence (`cplayer` SetRichPresence), `chat_image.rs` (chat image upload). JNI exports: `nativeSendFriendMessage`, `nativeGetRecentMessages`, `nativeDrainFriendMessages`, `nativeSendChatImage`, `nativeGetFriendsList`, `nativeGetFriendPersonas`, `nativeGetSelfPersona`, `nativeSetPersonaState`, `nativeSetPersonaName`, `nativeRequestFriendPersonas`. No dependency on any external `steamkit_core` — social is native to the engine, so Phase 1 (the live social pain point) is directly available.

---

## 2. Current-state map and what changes

### 2.1 Bannerlator today (what exists)

**JavaSteam stack (this is what the Rust engine REPLACES):**
- Dependency: `io.github.joshuatam:javasteam:1.8.0.1-26-20260801.180149-1` + `…:javasteam-depotdownloader:…` + a promoted `com.google.protobuf:protobuf-java:4.31.1`, **vendored** under `/home/claude-user/bannerlators-mainrel/vendor/maven/` (a fork of `in.dragonbra:javasteam`, chosen for its disk-spooling OOM-fixed depot engine).
- Chokepoint facade: **`app/src/main/java/com/winlator/star/store/SteamRepository.java` (1889 LOC)** — the only class owning the `SteamClient`, the `HandlerThread("SteamPump")` + `runWaitCallbacks` loop, the PICS sync state machine, and the `emit(String)` event bus. Only 4 files import `in.dragonbra.*` directly (`SteamRepository`, `SteamAuthManager`, `SteamQrAuthManager`, `SteamDepotDownloader.kt`); everything else consumes JavaSteam **through** this facade.
- Six consumer clusters (re-point targets):
  1. **Auth/session** — `store/SteamAuthManager.java` (184, credentials), `store/SteamQrAuthManager.java` (166, QR), `store/SteamPrefs.kt` (persists `steam_prefs`), `store/SteamForegroundService.kt` (keep-alive), `SteamLoginActivity.kt` / `QrLoginActivity.kt` (UI).
  2. **Friends/chat/presence** — `store/SteamFriendsStore.kt` (1206, `StateFlow`s), `SteamFriendsScreen.kt` (1632), `SteamFriendProfileScreen.kt` (622), `SteamChatNotifier.kt`, `SteamChatImageUploader.kt` (already WN-derived), `SteamUserSearch.kt`.
  3. **Library/PICS** — the PICS state machine in `SteamRepository`, `store/SteamDatabase.java` (1165, SQLite), `SteamGame.kt`, `SteamLibrarySync.kt`, `SteamGamesActivity.kt`, `SteamGameDetailActivity.kt` (**3239, launch hub**).
  4. **Downloads/depots** — `store/SteamDepotDownloader.kt` (1166, wraps the JavaSteam fork engine), `DownloadSpeedConfig.kt`, `DepotSizeResolver.kt`, `SteamGameUpdater.kt`, and the store-agnostic `store/download/` registry.
  5. **Cloud saves** — `store/SteamCloudSaveManager.kt` (1018), `SteamCloudSavePaths.kt`, `SaveSyncStore.kt`, `SteamSaveManagerActivity.kt`.
  6. **Achievements/user stats** — `store/SteamAchievementStore.kt` (636), `AchievementWatcher.kt`, `SteamLiteAchievementWatcher.kt`.
- Event model: a stringly-typed `SteamRepository.emit(String)` bus (the live path) plus per-`*Store` `StateFlow`s. `store/SteamEvent.kt` defines a typed `sealed class SteamEvent`/`SharedFlow` that is **currently unused (aspirational)** — the rewrite should adopt it.

**SteamLite = genuine-Steam-client-in-Wine VAC path (COORDINATE-WITH, don't replace):**
- The C++ agent (`steam.exe`) source is **not in this repo** — it is a prebuilt clean-room GPL agent + a matched genuine Valve client set (`steamclient64.dll`, `tier0_s*`, `vstdlib_s*`, `Steam.dll`, `steamservice.*`), delivered download-on-demand via `store/SteamLiteComponent.kt` from `The412Banner/winlator-contents` (`steamlite.tzst`).
- Orchestration (Kotlin/Java, engine-agnostic): `store/RealSteamLauncher.java` (617) builds the launch plan — stages the Valve stack into the prefix, writes `appmanifest_<appid>.acf` (`StateFlags=4`, `LauncherPath=steam.exe`) for a **secure `LaunchApp`**, writes the `C:\<appId>.spec`, and builds the env contract: `PROTON_DISABLE_LSTEAMCLIENT=1`, `WN_STEAM_TOKEN`, `WN_STEAM_USERNAME`, `WN_STEAM_STEAMID`, `WN_STEAM_APPID`, `WN_STEAM_GAMEEXE_FILE`. `XServerDisplayActivity.java` coordinates (`maybeStageRealSteam`, merges env, rewrites launch to run the agent as `steam.exe`). The watch-exe rule (launcher `.exe`, not the `_win64` child) lives in `RealSteamLauncher.preferLauncherExe()`.
- **Crucial for this plan:** SteamLite reads only `steam_prefs` (`refresh_token`, `username`, `steam_id_64`, `account_id`, `display_name`). As long as the Rust engine keeps populating those keys, SteamLite's VAC path is **unaffected** by the engine swap.
- Goldberg (`store/GoldbergComponent.kt` / `GoldbergPatcher.kt`, OFF/REGULAR/EXPERIMENTAL/COLDCLIENT) is the offline/emulation path — also unaffected.

**Native/build baseline:** namespace `com.winlator.star` (flavors `com.winlator.banner`, `com.ludashi.benchmark`, `com.tencent.ig`); single CMake tree `app/src/main/cpp/CMakeLists.txt` (`project(Winlator)`); NDK `29.0.14206865`; `abiFilters 'arm64-v8a'` only; `jniLibs/arm64-v8a/` holds prebuilt `.so`s. **No Rust anywhere** (no `Cargo.toml`/`cargo`/`rustup`/`.rs`). CI `.github/workflows/` builds C/C++ only (`_build.yml` = Gradle externalNativeBuild; `build-vkbasalt.yml` / `build-pulseaudio.yml` = Meson/NDK). No workflow builds Rust.

### 2.2 WinNative reference (what we adapt)

- Rust crate `wnsteam` at `app/src/main/cpp/wn-steam-client/rust` (crate-type `rlib`+`staticlib`+`cdylib`; `GPL-3.0-or-later`). ~18 KLOC across clean modules: `cm_client.rs` (2297, pure protocol state machine — builds/routes every CM/service message), `cm_runtime.rs` (620, drives the encrypted channel + jobs + heartbeat + callbacks), `jni.rs` (4549, 73 JNI exports), `cm_bridge.rs` (1538, 38 `wn_cm_*` C-ABI exports for the in-app libsteamclient), `library_store.rs`, `auth_session.rs` (RSA/guard/poll login), `depot_downloader.rs` + `cdn_client.rs` + `depot_writer.rs` + `content_manifest.rs` + `depot_chunk.rs` (self-contained SteamPipe CDN downloader), `cloud`/`inventory`/`published_file`/`family_groups` service calls, `chat_image.rs`, hand-written `pb/*` protobufs over `proto_wire.rs`, hand-rolled `vdf.rs`.
- Threading/runtime: **no tokio** — `std::thread::spawn` + a synchronous `Transport` trait (`ws_connection.rs` = `tungstenite` WSS to Steam CM) wrapped by `EncryptedChannel`. Blocking `reqwest` for CDN/web. Portable, testable, no async-runtime headaches on Android.
- JNI shape: package `com.winlator.cmod.feature.stores.steam.wnsteam`; classes `WnSteamClient` (loads lib), `WnConnection` (raw channel), `WnSteamSession` (the full session — 790-LOC Kotlin facade). Data crosses the boundary as **JSON strings / byte arrays**; native holds Kotlin observer objects as JNI global refs (`WnSteamStateObserver`, `WnLibraryObserver`, download listeners, auth callbacks) and dispatches on attached threads. `WnLibraryStore.kt` coalesces observer bursts into a `SharedFlow<WnLibrarySnapshot>`.
- Env contract for the in-game bridge (`WnWineEnvVars.kt`): `WINESTEAMCLIENTPATH{,64}` (path to the in-app bridge `.so`), `Steam3Master`/`SteamClientService` (`127.0.0.1:57343`/`57344`), `SteamClientLaunch=1`, `SteamAppId`/`STEAMID`/`SteamUser`, `OWNED_DLCS` (csv).
- In-game VAC path pieces (only relevant if Bannerlator later adopts WN's bionic bridge instead of leaning on SteamLite): `wn-libsteamclient` (CMake target `wn_libsteamclient`, `OUTPUT_NAME steamclient` → `libsteamclient.so`; `tcp_services.cpp` binds loopback 57343/57344, LE-u32 length-framed; current tip's `handle_connection` is receive-and-log/diagnostic — the load-bearing servicing is in-process `CreateInterface`/vtable calls that Wine's path-patched unix-side `lsteamclient.so` reaches by dlopen). `wn-steam-bootstrap` (`libwnsteambootstrap.so`) dlopens the bridge `.so` and drives login via `CLIENTENGINE_INTERFACE_VERSION005` → IClientUser `LogonWithRefreshToken` (vtable slot `0x1C0`). Token travels as a **JNI string arg**, not `SB_REFRESH_TOKEN` (that env var does not exist). gbe_fork pin `release-2026_05_16+stubdrm`; `StubDRM64.dll` via `extra_dlls/load_order.txt`.

### 2.3 What changes (summary)

| Area | Today | After |
|---|---|---|
| CM connect/login/session | JavaSteam `SteamClient` + pump thread | Rust `WnSteamSession` (native thread) |
| Friends/chat/presence | JavaSteam `SteamFriends` + callbacks | Rust engine (native social surface) |
| Library/PICS | JavaSteam PICS in `SteamRepository` | Rust `library_store` + JSON snapshots |
| Depot downloads | JavaSteam fork depot engine | Rust self-contained CDN downloader |
| Cloud saves | JavaSteam `SteamCloud` | Rust `ccloud` service calls |
| Achievements | JavaSteam `SteamUserStats` | Rust `store_user_stats` / `get_user_stats` |
| Event bus | `emit(String)` | typed `SteamEvent` `SharedFlow` (adopt existing stub) |
| `steam_prefs` (`refresh_token`, …) | written by JavaSteam auth | written by Rust auth — **contract preserved** |
| SteamLite (VAC), Goldberg | unchanged | unchanged (consume `steam_prefs`) |
| Rust toolchain / CI | none | new cargo↔CMake job (see phases) |

---

## 3. Phases

Each phase is independently shippable behind a build/runtime flag (`useRustSteamEngine`) so JavaSteam remains the fallback until each surface is device-proven. The `SteamRepository` facade is retained as the abstraction seam: internally it delegates to either JavaSteam (old) or `WnSteamSession` (new) per flag, so the six consumer clusters and all UI are untouched until a surface flips.

### Phase 0 — Toolchain, crate skeleton, CI, CM-connect/login MVP

**Goal:** `libwnsteam.so` builds in Bannerlator CI for `arm64-v8a`, loads at runtime, connects to a Steam CM and logs in from a saved refresh token — proving the whole native pipeline before any feature work.

**Scope:**
- Vendor the `wnsteam` crate into `app/src/main/cpp/wn-steam-client/rust` (adapt path; keep GPL headers + add WN attribution). Bring `Cargo.toml` + **`Cargo.lock`** (pin exactly — see §5). Keep the full module set even if some surfaces are dormant.
- **JNI package decision (pick one, document it):** either (a) rename every `Java_com_winlator_cmod_…` export in `jni.rs`/`cm_bridge.rs` to `Java_com_winlator_star_…` and place Kotlin facades under `com.winlator.star.feature.stores.steam.wnsteam`; or (b) keep the WN package path verbatim (`com.winlator.cmod.feature.stores.steam.wnsteam`) so **zero** Rust JNI symbols change (Kotlin package need not match `applicationId`). Recommend (b) for Phase 0 to minimize churn and reduce silent symbol-resolution failures; revisit (a) only if package hygiene demands it.
- Port the minimal Kotlin facades: `WnSteamClient.kt` (loader), `WnConnection.kt`, `WnSteamSession.kt` (subset: connect/login/refresh-token/state), `WnWineEnvVars.kt`, `CaBundleExtractor.kt` (+ `wnsteam_cacert.pem` asset — the engine uses `rustls` and a CA bundle path).
- Wire `SteamRepository` to optionally construct a `WnSteamSession`, connect (`nativePickCmUrl` → `nativeConnect`), and `logonWithRefreshToken` from `steam_prefs.refresh_token`. Persist tokens back into `steam_prefs` via the auth callback (`WnAuthResult`) so SteamLite/Goldberg keep working.

**Exact WN reference to model/adapt:** `Cargo.toml`; `app/src/main/cpp/wn-steam-client/CMakeLists.txt` (cargo invocation → `libwnsteam.so`); `lib.rs`; `jni.rs` (`WnSteamClient_nativeVersion`, `WnConnection_*`, `WnSteamSession_nativeCreate/Connect/LogonWithRefreshToken/State`, `JNI_OnLoad`); `cm_client.rs` `build_logon_with_refresh_token`, `ClientState`; `cm_runtime.rs`; `ws_connection.rs`; Kotlin `WnSteamClient.kt`, `WnConnection.kt`, `WnSteamSession.kt` (login subset), `WnWineEnvVars.kt`, `CaBundleExtractor.kt`.

**CI/toolchain steps (new — model on WN, adapt to Bannerlator's `_build.yml`):**
1. Add a Rust step before the Gradle native build: install stable Rust (rustup is preinstalled on `ubuntu-latest`; WN just runs `rustup target add aarch64-linux-android`).
2. Cross-compile glue: WN drives `cargo build --target aarch64-linux-android --release` from `wn-steam-client/CMakeLists.txt` (an `add_custom_command`/`ExternalProject`-style cargo invocation) and links/copies the resulting `libwnsteam.so`. Add a **`.cargo/config.toml`** (or env) setting the aarch64-linux-android **linker + `CC_aarch64-linux-android` / `AR_aarch64-linux-android`** to the NDK `29.0.14206865` clang/llvm-ar, because the one C-compiling dependency (`ring`, via `rustls`) needs a working cross C compiler. Everything else in the tree is pure Rust.
3. Package: emit the `cdylib` into `jniLibs/arm64-v8a/libwnsteam.so` (or an `IMPORTED` target in the app CMake tree) so it packages exactly like the existing native libs. Confirm `packagingOptions`/`jniLibs` don't strip it.
4. **Cache the cargo registry AND `target/` dir** — a cold ~18-KLOC + `ring`/`reqwest` build is the CI long pole (WN caches these). Keyed on `Cargo.lock`.
5. Keep `abiFilters 'arm64-v8a'` — single target, matches WN and the device fleet.

**Risk:** cross-compile of `ring` if NDK linker/CC env is wrong (mitigate with the `.cargo/config.toml` above; verify with a smoke `cargo build` in CI before Gradle). JNI symbol-name mismatch silently fails at runtime (mitigate: choose verbatim package (b); add a `nativeVersion()` boot log assertion). Adds minutes to CI (mitigate with caching). **No device behavior change yet** — flag defaults off.

### Phase 1 — Friends/chat/presence (replace JavaSteam social first)

**Goal:** the live pain point (social) runs entirely on the Rust engine behind the `SteamFriendsStore` `StateFlow`s, with zero UI change.

**Scope:**
- Re-point `SteamFriendsStore.kt` from the JavaSteam `SteamFriends` handler + callbacks to `WnSteamSession` social calls, feeding the same `StateFlow`s (`friends`, `self`, `chat`, `unread`, `typing`, requests).
- Map: `getFriendsList()`/`getFriendPersonas()`/`getSelfPersona()` (JSON) → the friends/persona flows; `setPersonaState()`/`setPersonaName()`; `requestFriendPersonas()`; `sendFriendMessage()` + `getRecentMessages()` + `drainFriendMessages()` (poll/drain incoming chat) → chat flow; `sendChatImage()` → replace/great-fit with the already-WN-derived `SteamChatImageUploader.kt`. Wire the native library/state observers to trigger flow refreshes.
- Keep `SteamChatNotifier` and `SteamUserSearch` (the latter is a web-API path, engine-agnostic — leave as-is).

**Exact WN reference:** `cm_client.rs` `build_send_friend_message` / `build_get_recent_messages` / `build_request_friend_personas` / `build_set_persona_state` / `build_set_persona_name`; `push_incoming_message`/`drain_incoming_messages`/`IncomingFriendMessage`; `pb/cfriendmessages.rs`, `pb/cmsg_client_persona.rs`, `pb/cmsg_client_friends_list.rs`, `pb/cmsg_client_change_status.rs`; `chat_image.rs`; `jni.rs` `nativeGetFriendsList/GetFriendPersonas/GetSelfPersona/SendFriendMessage/GetRecentMessages/DrainFriendMessages/SendChatImage/SetPersonaState/SetPersonaName/RequestFriendPersonas`; Kotlin `WnSteamSession.kt` (social section), and `WnLibraryStore.kt`'s coalesced-`SharedFlow` observer pattern (apply the same pattern to a `WnFriendsStore`).

**Risk:** chat delivery is poll/drain (`drainFriendMessages`) rather than pure push — need a lightweight poll loop or observer-driven refresh matching WN; verify message echo/history parity with the JavaSteam callbacks (`FriendMsgEcho`, `FriendMsgHistory`). Presence/rich-presence field mapping. Mitigate with a per-surface flag (`useRustSocial`) so JavaSteam social stays as fallback.

### Phase 2 — Library/store/downloads/cloud/achievements

**Goal:** the rest of the Android-side surfaces move to Rust; JavaSteam becomes removable.

**Scope (sub-ordered by risk):**
- **2a Library/PICS:** replace the `SteamRepository` PICS state machine with the engine's `library_store` — consume `getLibrarySnapshotJson()` via a `WnLibraryStore.kt`-style coalesced `SharedFlow`, and PICS helpers (`getPicsChangesSince`, `getPicsAppInfo`, `getPicsProductInfo`, `getPicsAccessTokens`). Keep `SteamDatabase.java` as the persistence layer — populate `steam_games`/`steam_licenses` from the snapshot instead of JavaSteam callbacks. Keep `LIBRARY_ALLOWLIST` semantics.
- **2b Cloud saves:** re-point `SteamCloudSaveManager.kt` to the engine's cloud service calls (`getCloudFileList`, `getCloudUserQuota`, `getCloudDownloadInfo`, `downloadCloudFile`, begin/commit/complete upload batch). Keep `SteamCloudSavePaths.kt` and `SaveSyncStore.kt` (path/UFS logic is engine-agnostic). Preserve GOG-style auto-sync triggers.
- **2c Achievements:** re-point `SteamAchievementStore.kt` to `getUserStatsSchema`/`getUserStatsFull`/`storeUserStats`. Keep GSE/GBE schema seeding + `AchievementWatcher` unchanged (they watch container files).
- **2d Downloads (highest risk, do last):** replace `SteamDepotDownloader.kt`'s JavaSteam fork engine with the engine's native downloader (`prepareApp` → `downloadApp` with a `WnDownloadListener`; resumable via clean-pause markers). Feed the existing `store/download/` registry + progress bus. Retain `DepotSizeResolver`/`SteamGameUpdater` behavior (size/update-on-launch) using engine PICS + manifest data. **Gate behind its own flag and validate against large-game OOM/reliability before flipping** — this is the surface Bannerlator invested most in.

**Exact WN reference:** `library_store.rs` (`ingest_*`, `snapshot_json`, observer); `jni.rs` `nativeGetLibrarySnapshot`, `nativeGetPics*`, cloud `nativeGetCloudFileList/UserQuota/DownloadInfo/CloudBegin*`, achievements `nativeGetUserStatsSchema/Full/StoreUserStats`, downloads `nativePrepareApp/DownloadApp/CancelDownload`; `depot_downloader.rs` (`fetch_manifest_with_retry`, `decide_depot_resume`, `clean_pause_marker_*`), `cdn_client.rs`, `depot_writer.rs`, `depot_chunk.rs` (VZip/LZMA via `lzma_rs`, zstd via `ruzstd`); Kotlin `WnLibraryStore.kt`, `WnDownloadListener.kt`, `WnPrepareAppCallback.kt`, `WnLibraryModels.kt`, `WnSteamSession.kt` (cloud/download sections).

**Risk:** depot-download reliability regression (the #1 functional risk — see §4). Library snapshot ↔ `SteamDatabase` schema mapping (depot grouping order matters — WN's `serde_json` `preserve_order` note about DLC content-depot grouping applies). Cloud path translation parity. Mitigate: per-surface flags, side-by-side validation on real accounts, keep JavaSteam downloader selectable until the Rust path clears the bar.

### Phase 3 — In-game path: keep genuine SteamLite; re-source its token from Rust

**Goal:** the VAC path works with the Rust engine as identity/token source, with SteamLite's genuine-Valve mechanism unchanged. (Optional stretch: evaluate WN's bionic bridge.)

**Scope (baseline — low risk):**
- **Nothing structural changes in SteamLite.** `RealSteamLauncher.java` already reads `steam_prefs`. Because Phase 0's auth writes the same keys (`refresh_token`, `username`, `steam_id_64`, `account_id`, `display_name`), SteamLite's env contract (`WN_STEAM_TOKEN` = `refresh_token`, etc.) is populated by the Rust engine transparently. Verify the Rust-obtained refresh token is accepted by the genuine Valve client for a secure `LaunchApp` (this is the real integration test).
- **Dual-session orchestration (the load-bearing part):** the Rust CM session (Android brain) + the genuine in-Wine Valve session = two sessions on one account → "logged in elsewhere" kick. This already exists with JavaSteam+SteamLite; replicate/improve using the engine's native primitives — `markPlayingBlocked`/`isPlayingBlocked`, `kickPlayingSession`, `CMsgClientPlayingSessionState` routing, and `notifyGamesPlayed` — to coordinate handoff (e.g. brain yields/kicks its own playing session when the in-game session starts).

**Scope (optional stretch — gated, VAC-unproven):**
- Evaluate porting WN's **bionic bridge** in-game path as a lighter alternative to genuine SteamLite: `wn-libsteamclient` (in-app reimpl `libsteamclient.so`), `wn-steam-bootstrap` (`libwnsteambootstrap.so`, `LogonWithRefreshToken` vtable drive), `WnSteamAssetsInstaller.kt` (lsteamclient.dll into system32/syswow64 + path-patched unix `lsteamclient.so`), `WnWineEnvVars.kt` guest env. This is a **major C++ port** (the reimplemented libsteamclient is a large effort) and its VAC parity is unproven — do it **only** behind a flag, only after device VAC proof, and never as a SteamLite retirement trigger.

**Exact WN reference:** baseline — `jni.rs` `nativeGetAppOwnershipTicket`/`nativeRequestEncryptedAppTicket`, `cm_client.rs` playing-session/kick builders; `SteamServiceLogin.persistLoginTokens` pattern (Rust→prefs). Stretch — `wn-libsteamclient/src/tcp_services.cpp`, `wn-steam-bootstrap/src/steam_bootstrap.cpp`, `WnSteamAssetsInstaller.kt` (`:29-45` bionic, `:100-114` lsteamclient.dll, `:307-395` PlanW), `cm_bridge.rs` (`wn_cm_*` C-ABI), `SteamClientManager.kt` + `tools/gbe_fork.version`.

**Risk:** VAC parity (see §4). Dual-session kick correctness. For the stretch: the bionic-bridge reimpl port is large and its VAC status is unknown — high effort/uncertain payoff; keep SteamLite as the proven path.

### Phase 4 — Retire JavaSteam; keep SteamLite as fallback

**Goal:** remove the JavaSteam dependency once Phases 1–2 surfaces are device-proven on real accounts; SteamLite/Goldberg remain.

**Scope:** drop `io.github.joshuatam:javasteam*` + the promoted `protobuf-java` + the vendored `vendor/maven/io/github/joshuatam/…`; delete the JavaSteam branch inside `SteamRepository`; remove the `in.dragonbra` imports from `SteamAuthManager`/`SteamQrAuthManager`/`SteamDepotDownloader`. Adopt the typed `SteamEvent` `SharedFlow` model, retiring the `emit(String)` bus. **Do NOT retire SteamLite** — the genuine-Valve VAC path stays the fallback (and, unless the Phase-3 stretch is VAC-proven, the *primary*) in-game path. Keep Goldberg (offline).

**Risk:** losing the JavaSteam fallback before every surface is proven. Mitigate: only remove per-surface after each has run flag-on in a released build without regressions; keep the vendored jars one release cycle past cutover for emergency revert.

---

## 4. Open risks + unknowns

1. **VAC parity + dual-session ("logged in elsewhere").** Two sessions per account (Rust CM brain + genuine in-Wine Valve) trigger Steam's single-session kick. Already a live concern with JavaSteam+SteamLite; the engine's `playing-session-state`/`kick_playing_session` primitives must be wired to coordinate handoff. If WN's bionic bridge is ever adopted, its VAC parity vs genuine Valve is unproven for Bannerlator's device-proven titles (TF2/L4D2/CS:S). **Keep genuine SteamLite as the VAC bet.**
2. **Depot-download reliability regression.** Bannerlator forked JavaSteam's depot engine specifically for large-game OOM/reliability. The Rust downloader is self-contained with resume markers + retry + a streaming native writer (promising, and structurally lower-memory), but is unproven at Bannerlator's bar. Must be validated side-by-side on large titles before flipping Phase 2d; keep JavaSteam downloader selectable until then.
3. **Build/CI + JNI-boundary integration.** No Rust toolchain today: add rustup + `aarch64-linux-android` target + cargo↔CMake glue + NDK cross `CC`/`AR` for `ring`, cache registry+`target/`, without breaking the existing C/C++ native build, and absorb the CI-time cost of a cold ~18-KLOC build. JNI symbol names are hardcoded — the package decision (verbatim vs rename) must be exact or symbols fail silently at runtime.
4. **`Cargo.lock` transitive deps for Android.** `reqwest 0.12` (even blocking-only) transitively pulls `tokio`/`hyper` (compiled but unused) and `ring` (the sole C-compiling dep). Confirmed no `openssl-sys`/native-tls (uses `rustls`). Pin `Cargo.lock` exactly to keep the cross-build reproducible; a future `reqwest`/`ring` bump could change cross requirements.
5. **Depot grouping / snapshot fidelity.** WN's `serde_json` `preserve_order` exists because DLC content-depot grouping is positional; the Kotlin snapshot/appinfo decoder must preserve that or download/install sizes zero out. Port the decoder faithfully.
6. **Event-model migration.** The typed `SteamEvent` `SharedFlow` is only half-wired today (no emitter). Adopting it during the swap is clean but is real work across the six consumer clusters.

---

## 5. Couldn't verify in the shallow clone — flag for full fetch / latest-release check

- **Shallow tip only (`eaa4640`).** The Steam Rust tree has **no LFS stubs** (verified) and reads complete, but this is one shallow commit. Before committing to the port, do a full/deep fetch of WinNative and diff `wn-steam-client/rust` against the latest release/tag — the engine is actively developed; there may be fixes past this tip.
- **Out-of-band binary assets (not in git, not needed for the CM brain).** WN's bionic-bridge in-game path needs `steam-androidarm64.tzst` (bionic runtime incl. `libsteamclient.so`), `lsteamclient-{arm64ec,x86_64}.tzst`, `valve-steam-x86_64.tzst` (PlanW genuine Valve), and the steampipe `steam_api64.dll` bridge — delivered via `assets/`/download-on-demand, not the crate. **The Rust CM engine (Phases 0–2) does NOT need any of these.** They only matter if Phase 3's stretch (bionic bridge) is pursued; Bannerlator's existing SteamLite already supplies the genuine-Valve equivalent.
- **`ring` cross-build not actually run here.** The `.cargo/config.toml` NDK linker/CC setup is inferred from WN's CMake + standard aarch64-linux-android practice; validate with a real CI `cargo build --target aarch64-linux-android` smoke before wiring Gradle.
- **The 5-part native VAC stack internals** (`wn-libsteamclient` 14 files, `wn-steam-bootstrap` 3, `wn-steamapi-bridge` MinGW `steam_api64.dll`, `wn-steam-launcher`, `steamwebhelper-preload`) were mapped at the interface level, not fully read. Only needed for the Phase-3 stretch.
- **`Cargo.lock` deep audit.** Confirmed `rustls`/no-OpenSSL and the `ring`/`tokio`(dead) shape at a high level; do a full `cargo tree`/license sweep of the pinned lock before merge (GPL-3.0 compat of all transitive crates, no unexpected `-sys` C deps beyond `ring`).
- **gbe_fork/stubdrm** (`release-2026_05_16+stubdrm`) is a WN detail for its emulated path; Bannerlator uses Goldberg and needs no change here — noted only for completeness.
