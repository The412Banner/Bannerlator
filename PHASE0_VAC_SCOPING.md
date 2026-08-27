# Phase 0 — Real Steam VAC Multiplayer: Recon & Scoping

**Status:** RECON ONLY. No production code changed. All refs grounded against this
checkout (`feat/steam-vac-phase0` @ `68b528d9`) and the vendored jar under
`vendor/maven/`.

**Scope ceiling (unchanged):** VAC-only. Never kernel anti-cheat (BattlEye / EAC /
Vanguard). Never bundle Valve Windows DLLs, GameSir's SteamAgent binary, or any leaked
token. If Phase 1 is needed, Steam files come from Valve legally.

---

## 0. BOTTOM LINE FIRST (the critical verdict, item 2b)

**The "JavaSteam mints ticket → inject into Goldberg → pass real VAC" path is NOT
viable. Real VAC fundamentally requires the genuine `steamclient` + genuine VAC modules
running alongside the game.**

There are **two independent gates** on a VAC-secured Valve server, and they fail for
different reasons:

| Gate | What it is | Can JavaSteam+Goldberg satisfy it? |
|---|---|---|
| **#1 Ticket auth** — `ISteamGameServer::BeginAuthSession(ticket, steamID)` | Server forwards the client's auth-session ticket to Valve's backend for validation | **Ticket: YES, injection: NO.** JavaSteam *can* mint a genuine, CM-registered ticket. But Goldberg gives no way to hand that exact blob to the game's `GetAuthSessionTicket` (see route (a)). |
| **#2 VAC module check** — live challenge/response between Valve's VAC backend and the client's genuine Steam client | Confirms the running process is being scanned by real, up-to-date VAC modules | **NO — by anyone.** VAC modules are encrypted, downloaded and executed only by the genuine Steam client. No emulator (Goldberg, gbe_fork, or even our own WinNative reimpl) implements them. |

Even if we solved gate #1, gate #2 is unsolvable without the genuine client. Therefore
Goldberg can **never** pass a real VAC server, and injecting a real ticket into it does
not change that.

**Hard evidence that even a "real client" reimpl fakes this** — WinNative's own
`libsteamclient` (`/home/claude-user/winnative/app/src/main/cpp/wn-libsteamclient/src/isteam_stubs.cpp`):
- `BeginAuthSession(...)` → `isteam_stubs.cpp:345-357` ignores the ticket bytes and
  pushes `k_EAuthSessionResponseOK` with the log string **"OK (synthetic validation)"**.
- `BIsVACBanned()` → `isteam_stubs.cpp:520-524` just reads a local `app_vac_banned` set.
- `GetAuthSessionTicket(...)` → `isteam_stubs.cpp:218-282` hand-assembles a ticket
  (CM-ownership-ticket-backed or a synthetic `"WNAT"` blob). It is *not* a CM-registered
  session ticket.
- Grep for VAC machinery across the whole WinNative cpp tree returns only
  `app_vac_banned` / `BIsVACBanned` (local state). There is **no** VAC challenge/response
  code anywhere, because reimplementing VAC is neither legal nor technically feasible.

**Consequence for Phase 0 as literally framed:** "launch a VAC title → join a VAC server
→ observe accept vs kick" is testing the **wrong gate**. A Goldberg-injected client is
kicked at gate #2 (VAC) essentially unconditionally, so the kick tells us **nothing**
about whether our JavaSteam ticket (gate #1) is valid. It is not a decisive test of the
stated hypothesis.

**What Phase 0 SHOULD test instead (see §3):** isolate gate #1 off-device. Mint a ticket
with JavaSteam and validate it against Valve directly via the Steam Web API
`ISteamUserAuth/AuthenticateUserTicket` (or a SpaceWar `BeginAuthSession` harness) — no
game, no Goldberg, no VAC. That decisively answers the one thing we don't already know:
*can our shipped JavaSteam fork mint a genuine, Valve-accepted auth-session ticket?*
Real VAC still needs the genuine client regardless of that answer, so the strategic
recommendation is to run the cheap ticket-validity test **and** scope a minimal Phase 1
spike (genuine `steam.exe` headless auto-login in-Wine) in parallel — because Phase 1 is
the only path that reaches VAC.

---

## 1. Ticket minting — exact API surface (verified via `javap` on the vendored jar)

Jar: `vendor/maven/io/github/joshuatam/javasteam/1.8.0.1-26-SNAPSHOT/javasteam-1.8.0.1-26-20260801.180149-1.jar`

### 1.1 The classes exist and are public

**`in.dragonbra.javasteam.steam.handlers.steamauthticket.SteamAuthTicket`** (extends
`ClientMsgHandler`):
```
public CompletableFuture<TicketInfo> getAuthSessionTicket(int appId)
public CompletableFuture<TicketInfo> getAuthTicketForWebApi(int appId, String identity)
public Object getAuthSessionTicketInternal$javasteam(int, TicketType, String, Continuation)   // Kotlin suspend, internal
public void cancelAuthTicket$javasteam(TicketInfo)
// private plumbing:
private final LinkedBlockingQueue<byte[]> gameConnectTokens
private byte[] combineTickets(byte[], byte[], boolean)
private byte[] buildAuthTicket(byte[], TicketType)
private Pair<AsyncJobSingle<TicketAcceptedCallback>, Long> verifyTicket(int, byte[], byte[])
private AsyncJobSingle<TicketAcceptedCallback> sendTickets()
private void handleGameConnectTokens(IPacketMsg)     // consumes CMsgClientGameConnectTokens
private void handleTicketAuthComplete(IPacketMsg)
private void handleTicketAcknowledged(IPacketMsg)
enum TicketType { AuthSession, WebApiTicket }
```

**`...steamauthticket.TicketInfo`** (implements `Closeable`):
```
public byte[] getTicket()               // <-- the raw ticket blob we want
public int  getAppID$javasteam()
public long getTicketCRC$javasteam()
public void close()                      // CANCELS the ticket registration — do NOT call until done
```

**`in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps`** (extends
`ClientMsgHandler`):
```
public AsyncJobSingle<AppOwnershipTicketCallback> getAppOwnershipTicket(int appId)
public AsyncJobSingle<EncryptedAppTicketCallback> requestEncryptedAppTicket(int appId)
public AsyncJobSingle<EncryptedAppTicketCallback> requestEncryptedAppTicket(int appId, byte[] userData)
public void notifyGamesPlayed(List<GamePlayedInfo>, EOSType, int, boolean)   // + shorter overloads
```
- `AppOwnershipTicketCallback`: `getResult() : EResult`, `getAppID() : int`, `getTicket() : byte[]`.
- `EncryptedAppTicketCallback`: `getResult()`, `getAppID()`, `getEncryptedAppTicket() : EncryptedAppTicket` (protobuf).
- `GamePlayedInfo` is a large data class; the relevant fields are `gameId` (the appId as
  a GameID), `isSecure`, `token`, `gamePort`, `gameIpAddress`. There is a full
  constructor plus a `DefaultConstructorMarker` overload (Kotlin default args).

`RequestEncryptedAppTicket` at the wire level =
`SteammessagesClientserver$CMsgClientRequestEncryptedAppTicket` (present in jar).

### 1.2 Handler is auto-registered — no wiring needed

Bytecode of the `SteamClient` constructor (`javap -c`) allocates
`...steamauthticket/SteamAuthTicket` at offset **266** (right after `SteamApps` @98,
`SteamUserStats` @140), so it is auto-registered like every other core handler.
`steamClient.getHandler(SteamAuthTicket.class)` returns a live instance — exactly the
pattern `SteamRepository.initialize()` already uses for `SteamUser`/`SteamApps`/
`SteamCloud`/`SteamUserStats` (`store/SteamRepository.java:357-367`).

### 1.3 Nobody uses it yet

Grep across `app/src/main/java` for `SteamAuthTicket | getAuthSessionTicket |
getAppOwnershipTicket | requestEncryptedAppTicket | notifyGamesPlayed | GameConnectToken
| TicketInfo` → **zero hits**. This surface is completely unused in Bannerlator today; a
new (dev-only) hook is required to exercise it (see §4).

### 1.4 Preconditions for a valid ticket

- **Live logged-in session.** `getAuthSessionTicket` needs `SteamUser`/`SteamApps`
  handlers on a logged-on session. We have this — `SteamRepository.ensureLoggedIn(long)`
  (`store/SteamRepository.java:1288`) blocks until `LoggedOnCallback`.
- **A GameConnectToken must be queued.** The CM pushes a batch of
  `CMsgClientGameConnectTokens` shortly after logon; `handleGameConnectTokens` enqueues
  them into `gameConnectTokens`, and `getAuthSessionTicket` dequeues one per ticket
  (confirmed by the recon report §74.2, and by the private `gameConnectTokens` field).
  If minting is attempted in the first moment after logon, the queue can be empty and the
  future stalls — mitigate with a short settle after `ensureLoggedIn` (or retry).
- **Account should be marked "playing" the app.** For a *server* to validate the ticket,
  Valve must see our SteamID as in-game for that appId. Call
  `steamApps.notifyGamesPlayed([GamePlayedInfo(gameId=appId, ...)], EOSType.Unknown, 0)`
  before/around the mint. (Not required for the pure Web-API `AuthenticateUserTicket`
  check — that validates the ticket cryptographically, not the play-session.)
- **The mint registers with the CM.** `getAuthSessionTicket` → `verifyTicket` →
  `sendTickets` sends `CMsgClientAuthList` and awaits `TicketAcceptedCallback`. This CM
  registration is precisely what makes the JavaSteam ticket *genuine* (as opposed to the
  hand-assembled WinNative/Goldberg tickets, which are never registered). This is the one
  genuinely novel capability here.

### 1.5 Minimal call sequence to obtain a valid auth-session ticket blob

```
SteamRepository repo = SteamRepository.getInstance();
if (!repo.ensureLoggedIn(15_000)) return;                       // SteamRepository.java:1288
SteamApps apps = repo.getSteamApps();                            // SteamRepository.java:1434
// (recommended) mark us in-game so a server-side validation is consistent:
apps.notifyGamesPlayed(List.of(new GamePlayedInfo(/*gameId=*/appId, ...)), EOSType.Unknown, 0);
// short settle so the CM's GameConnectTokens batch has arrived:
Thread.sleep(1500);
SteamAuthTicket auth = repo.getSteamClient().getHandler(SteamAuthTicket.class);   // getSteamClient() @1433
TicketInfo ti = auth.getAuthSessionTicket(appId).get(15, TimeUnit.SECONDS);
byte[] blob = ti.getTicket();                                   // <-- the ISteamUser::GetAuthSessionTicket-equivalent bytes
// KEEP ti open (do NOT ti.close()) while the ticket must remain valid — close() cancels it.
```
`blob` is the same byte sequence a real client returns from
`ISteamUser::GetAuthSessionTicket`. It is what a game would send in a server-connect
handshake, and what the Steam Web API `AuthenticateUserTicket` validates (hex-encode it
for that call).

---

## 2. The injection route(s)

### Route (a) — Goldberg `configs.user.ini ticket=` seeding — **DEAD END for VAC**

Two independent reasons this cannot carry a real session ticket to a real server:

1. **Bannerlator never writes `configs.user.ini` at all today.** `GoldbergPatcher`
   (`store/GoldbergPatcher.kt`) only writes `steam_appid.txt`, `steam_interfaces.txt`,
   and `achievements.json` into `steam_settings/` (`GoldbergPatcher.kt:338-361`). Grep
   for `configs.user.ini | user::general | account_steamid` across the whole app → **zero
   hits**. The "GN seeds Goldberg with a real ticket" behavior is **not present** in this
   codebase; it would have to be added.

2. **Even where it exists (GN / gbe_fork), `ticket=` is the WRONG ticket type.** Per the
   on-device recon (`/sdcard/STEAM_PIPELINE_REPORT.md` §6.1 line ~381 and §28), the
   `[user::general] ticket={...}` value is the **base64 encrypted *app* ticket** (from
   `RequestEncryptedAppTicket`), consumed by gbe_fork's `GetEncryptedAppTicket` /
   `RequestEncryptedAppTicket` for DRM titles that call `SteamAPI_RequestEncryptedAppTicket`.
   It is **not** the auth-*session* ticket returned by `GetAuthSessionTicket`. gbe_fork
   *generates its own* fake auth-session ticket inside `GetAuthSessionTicket` and exposes
   **no config field to substitute a real one**.

**Can Goldberg EVER pass real VAC? No.** gbe_fork is a client *and* server emulator: it
intercepts the whole `ISteamUser`/`ISteamClient`/`ISteamGameServer` surface and satisfies
auth *locally* (peer-to-peer among gbe_fork instances, or single-player DRM). It never
relays a real ticket to Valve's game-server auth, and it has no VAC module. Against a
genuine VAC server: (i) it presents a fake session ticket that Valve rejects, and (ii)
even a genuine ticket can't answer the VAC challenge. Route (a) is only ever useful for
*getting a DRM game to launch* — which is exactly what `GoldbergPatcher`'s own header doc
says (`GoldbergPatcher.kt:38-40`: "CANNOT make an online-only game reach its publisher's
servers").

### Route (b) — genuine `steam.exe` auto-login via VDFs — **Phase 1+, and the ONLY VAC-capable route**

Per recon `/sdcard/STEAM_PIPELINE_REPORT.md` §10.5-10.6: write a refresh token into
`loginusers.vdf`, obfuscate a token into `config.vdf` (`SteamTokenHelper` XOR for older
clients), and for newer clients run `steam-token.exe` under Wine (uses `CryptProtectData`;
GN bundles `steam-token.tzst`) to produce `local.vdf`. This boots the **genuine Steam
client inside Wine**, which downloads and runs the **genuine VAC modules** alongside the
game.

This is the only architecture that can satisfy *both* gates. It is Phase 1+ territory:
it needs the real `steamclient`/`steam.exe` (sourced legally from Valve, never bundled)
plus a headless auto-login and a wine-side `lsteamclient` bridge — coordinate the
prefix/DLL-override side with wine-compat-engineer. **Phase 0 cannot reach real VAC
without this**, so Phase 0's value is strictly to (i) de-risk the ticket-minting half and
(ii) decide whether JavaSteam can shrink Phase 1's login to token-seeding.

> ⚠️ Single-session constraint for Phase 1: Steam allows one live client session per
> account. If the genuine in-Wine client logs in AND our JavaSteam FGS session
> (`SteamForegroundService` → `SteamRepository`) is also logged in on the same account,
> they collide (`LogonSessionReplaced` — already handled defensively at
> `SteamRepository.java:1244-1255`). In Phase 1, JavaSteam's role must therefore be
> *login-seeding only* (mint the refresh token / write the VDFs, then step back), not a
> concurrent live session.

### Recommended route for the Phase 0 test

**Neither (a) nor (b) in-game.** Use the **off-device Web-API validation** path (§3.A) to
test gate #1 in isolation. It needs no injection at all — it proves ticket validity
directly against Valve. If you additionally want to *see* the gate-#2 kick with your own
eyes, run the literal join once (§3.B) but treat the kick as expected and
non-diagnostic.

---

## 3. On-device / on-desk test procedure

### Title choice: **Team Fortress 2 (appid 440)** — recommended

- **Free-to-play**, so every Steam account "owns" it → app ownership ticket always
  mints; no purchase needed.
- Ubiquitous **public VAC-secured community servers** (Valve + community), plus the
  server browser makes VAC status explicit.
- **No kernel anti-cheat** — VAC only. Stays inside our ceiling.
- Source engine passes the auth-session ticket in the connect handshake and surfaces a
  distinct VAC kick string.
- Alternatives: L4D2 (550) and CS:S (240) are fine but not free (ownership required).
  Use TF2.

### 3.A — RECOMMENDED decisive test (off-device, isolates gate #1, ~½ day)

Proves the only unknown: *can our shipped JavaSteam fork mint a genuine, Valve-accepted
ticket?* No game, no Goldberg, no VAC.

1. On device, ensure the Steam FGS session is logged in (open the Steam store tab so
   `SteamForegroundService` → `SteamRepository.connect()` + auto-login run;
   `SteamForegroundService.kt:73-88`).
2. Fire the dev mint hook (§4) for appid 440. It logs the ticket as hex to logcat under a
   `BH_STEAM*` tag and/or writes `/sdcard/Download/steam_vac/authticket_440.hex`.
3. On a laptop, validate the blob against Valve:
   `GET https://api.steampowered.com/ISteamUserAuth/AuthenticateUserTicket/v1/?key=<publisher_web_api_key>&appid=440&ticket=<hexblob>`
   - **PASS** = JSON `response.params.result == "OK"` and `steamid` == our SteamID
     (`SteamRepository.getSteamId64()`, `SteamRepository.java:1515`).
   - **FAIL** = an error result (e.g. `1002`/expired, invalid ticket).
   - Note: `AuthenticateUserTicket` needs a **publisher** Web API key scoped to the
     appid. For appid 440 that's a Valve app, so if no such key is available, substitute
     the **SpaceWar (480) dedicated-server harness**: run the Steamworks SDK
     `steamclient` example dedicated server on a PC, have it call
     `BeginAuthSession(blob, ourSteamID)`, and read the `ValidateAuthTicketResponse`
     (`k_EAuthSessionResponseOK` = pass). 480 is the public SDK test app anyone can auth.
4. Capture: device logcat (`bridge 'logcat -d | grep -iE "BH_STEAM|SteamAuthTicket|TicketAccepted"'`)
   and the laptop-side HTTP/console response.

### 3.B — Literal VAC-join test (only if you want to *see* gate #2; NON-decisive)

This is the test as originally framed. Run it once for confirmation, but understand the
kick is expected and does not falsify the ticket hypothesis.

1. Install TF2 into a container; apply Goldberg (`SteamGameDetailActivity.kt:1146` →
   `GoldbergPatcher.applyModeAsync`), starting REGULAR, escalating to EXPERIMENTAL /
   COLDCLIENT if it won't launch. Launch resolves through
   `GoldbergPatcher.resolveLaunchExe` (`DownloadManagerActivity.kt:290`,
   `SteamGameDetailActivity.kt:1103`).
2. Launch TF2. In-game: open the server browser → **Internet** tab → pick a server whose
   **VAC Secured** column is set (or a Valve Casual/Community server).
3. Direct-connect and watch the console (enable `-console`; `developer 1`).
4. **What to observe — the distinguishing signals:**
   - **VAC-reject (expected here, gate #2):** `Disconnect: VAC authentication error.` /
     `VAC could not verify your game session.` / a "Server is VAC secured / you cannot
     connect" banner. This is the client failing the VAC module challenge — it happens
     **regardless of ticket validity**, which is exactly why 3.B is non-decisive.
   - **Ticket-reject (gate #1, would look different):** `STEAM validation rejected` /
     `No Steam logon` / `Client dropped by server: #Valve_Reject_Steam` — an *auth
     ticket* failure, distinct from the VAC string above.
   - **Accept (won't happen via Goldberg):** you spawn into the map with a live player
     slot.
   - As a control, join a `-insecure` / VAC-off server: Goldberg typically connects there
     (no ticket auth, no VAC), confirming the game itself runs and it is specifically the
     secure path that rejects.
5. Capture: TF2 console log (`Steam/…/tf/console.log` or `-condebug`), Wine log
   (stderr/`WINEDEBUG`), device logcat `BH_STEAM*`.

---

## 4. Minimal code needed to run the test (SPEC ONLY — do not implement yet)

There is **no** existing path to mint a ticket (§1.3). Smallest well-defined change:

**(1) One method on `SteamRepository`** (Java), placed next to `ensureLoggedIn` at
`store/SteamRepository.java:1299`:
```java
/** DEV-ONLY (Phase 0 VAC probe). Mints a genuine auth-session ticket for appId.
 *  Returns the raw ticket bytes, or null on failure. Caller must NOT close the
 *  TicketInfo while the ticket must stay valid. */
public byte[] mintAuthSessionTicket(int appId, long timeoutMs) {
    if (!ensureLoggedIn(timeoutMs)) return null;
    try {
        // optional: steamApps.notifyGamesPlayed(List.of(new GamePlayedInfo(appId, ...)), EOSType.Unknown, 0);
        SteamAuthTicket h = steamClient.getHandler(SteamAuthTicket.class);
        TicketInfo ti = h.getAuthSessionTicket(appId).get(timeoutMs, TimeUnit.MILLISECONDS);
        return ti != null ? ti.getTicket() : null;   // keep ti referenced elsewhere if it must stay live
    } catch (Throwable t) { Log.e(TAG, "mintAuthSessionTicket failed", t); return null; }
}
```
Reuses the existing `steamClient` field and the pump/login machinery. No new library.

**(2) A dev-only, adb-triggerable trigger.** Two options; pick the receiver:
- *BroadcastReceiver (recommended, adb-triggerable):* add a `BuildConfig.DEBUG`-gated
  receiver (registered in code from `SteamForegroundService.onCreate`, or a manifest
  entry that is `exported=false` in release). `BuildConfig.DEBUG` is already used in
  `store/SteamDepotDownloader.kt:303`, so the gate exists. On receive, call
  `mintAuthSessionTicket`, hex-encode, `Log.i("BH_STEAM_VAC", hex)` and write
  `/sdcard/Download/steam_vac/authticket_<appId>.hex`. Trigger:
  `adb shell am broadcast -a com.winlator.banner.DEBUG_MINT_TICKET --ei appId 440`
  (standard flavor applicationId is `com.winlator.banner`,
  `app/build.gradle:149`; pubg verify-flavor is `com.tencent.ig` @159; namespace
  `com.winlator.star` @61).
- *Hidden debug button:* a long-press affordance on the Steam store screen behind
  `BuildConfig.DEBUG`. Simpler but not scriptable.

**Estimated size:** ~40-60 lines across `SteamRepository.java` (one method) + a small
receiver class + one manifest line (or code registration). No jar, no native, no UI
framework changes. Keep it entirely `BuildConfig.DEBUG`-gated and out of release. This is
the "small, well-defined next build" — spec only here.

> For 3.A you only need the mint + a way to read the blob off the device; the Web-API /
> SpaceWar validation runs on a laptop, so nothing production-facing is touched.

---

## 5. Risks, unknowns, and the decision gate

**Things that make the test inconclusive / need care:**
- **Empty GameConnectToken queue at mint time** → `getAuthSessionTicket` future stalls.
  Mitigate: settle ~1-2 s after `ensureLoggedIn`, and/or `notifyGamesPlayed` first;
  retry once.
- **`AuthenticateUserTicket` needs a publisher key** scoped to the appid. If unavailable
  for 440, use the SpaceWar-480 `BeginAuthSession` harness instead (no key needed).
- **Ticket lifetime / `close()`.** `TicketInfo.close()` cancels the CM registration; a
  test that closes too early invalidates the blob before validation. Keep it referenced.
- **Play-session coupling.** If gate #1 rejects only in the *server* path but passes in
  the Web-API path, the difference is the missing `notifyGamesPlayed` play-session — note
  which path you ran.
- **Single-session collision (Phase 1 risk, not Phase 0).** Don't run a concurrent
  genuine in-Wine login on the same account as the live JavaSteam FGS session
  (`LogonSessionReplaced`).
- **3.B is structurally non-decisive** — a VAC kick is expected regardless of ticket
  validity, so never read a 3.B kick as "ticket minting failed."

**The single clearest pass/fail signal:** the **3.A** result —
`AuthenticateUserTicket` → `result: "OK"` + our SteamID (or SpaceWar
`ValidateAuthTicketResponse == k_EAuthSessionResponseOK`).

**What each outcome tells us:**
- **3.A PASS:** JavaSteam mints a genuine, Valve-accepted auth-session ticket. The ticket
  half is proven and can feed a future real-client bridge. **This does NOT unlock VAC** —
  gate #2 still requires the genuine client. Proceed to a **minimal Phase 1** (genuine
  `steam.exe` headless auto-login in-Wine), with JavaSteam's role reduced to
  login/token-seeding (route (b) VDFs), because that is the only VAC-capable path.
- **3.A FAIL:** JavaSteam on this fork cannot mint a valid ticket → the
  ticket-injection idea is dead end-to-end; go straight to full Phase 1 (genuine client
  owns login *and* ticketing), and log the failure mode (CM rejection vs empty GC-token
  vs expired) for the native-steam-engineer.

**Net recommendation:** run 3.A (cheap, decisive for the ticket half) and, in parallel,
have wine-compat-engineer + native-steam-engineer scope the minimal Phase 1
genuine-client auto-login spike. Do **not** spend budget wiring Goldberg ticket injection
(route (a)) toward VAC — it cannot get there.
