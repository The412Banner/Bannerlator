# Agent channel (`BL_AGENT_PORT`) — SteamLite agent `steam.exe`

Optional live event channel between the in-container agent (`steam.exe`, this
directory, GPL-3.0 / WinNative-derived — see `NOTICE.md`) and the Bannerlator app.
Implemented in `agent_channel.h` (header-only, Winsock, ws2_32), hooked from
`main.cpp` and `clean_shutdown.cpp`. Agent revision tag: `"agent":"p3c"` (p2 added the
`region` field below and the `WN_STEAM_CMLIST` / `BL_STEAM_REGION` CM-list seed; p3 adds
the friends/chat relay in `agent_friends.h`, see the last section; p3b starts that relay
only AFTER the game process is running, adds the `friends_relay` event, the `vac` field on
`insecure_fallback` and the `WN_STEAM_VAC` secure-launch policy env; p3c makes the relay
SUBSCRIBE to friend presence — `RequestUserInformation` per friend, `PersonaStateChange_t`
consumed, flat `persona` events, `k` flag on roster entries, 30 s roster).

## Activation and guarantees

- Enabled only when env **`BL_AGENT_PORT=<decimal TCP port>`** is set. The agent
  connects to `127.0.0.1:<port>` right after `open_log()` in `main()` (before any env
  parsing or Steam work).
- If the env is **absent** the only thing that runs is one `getenv()`; no Winsock
  init, no thread, no socket. If it is set but the **connect fails** the agent logs
  once and continues. In both cases every emit is a no-op: same log lines, same flow,
  same exit codes as before this feature.
- Connect is non-blocking `connect()` + `select()` capped at **1500 ms**. Every
  `send()` runs with **`SO_SNDTIMEO = 500 ms`**. The first send/recv error marks the
  channel dead; all later emits are silently dropped. The launch flow never blocks on
  the socket.
- Log lines (once): `[wn-launcher] agent channel: connected to 127.0.0.1:<port>` or
  `[wn-launcher] agent channel: unavailable (<WSA error>)`. When a live channel drops
  later: `[wn-launcher] agent channel: closed (<why>, err=<n>)`.
- Privacy: the token, username and full SteamID are **never** sent. `steamid` is
  masked to `"***" + last 4 decimal digits`.

## Wire format

Newline-delimited JSON on one TCP socket, both directions: one object per line,
UTF-8, terminated by `\n`. Strings are escaped (`"`, `\`, control chars as
`\n \r \t` or `\u00XX`); bytes >= 0x80 are passed through as-is. Field order is as
listed. All `ms` values are milliseconds since agent start (`GetTickCount64`).
Integers are plain decimals.

## Events (agent -> app)

| `ev` | fields | when |
|---|---|---|
| `started` | `pid` u32, `appid` u32, `agent` `"p3"`, `region` string (`BL_STEAM_REGION` as received: `"auto"`, `"auto:<dc>"`, `"<dc>"`, or `""`) | first event, once the effective appId is known (env `WN_STEAM_APPID` or the spec-file override), before any Steam work |
| `logged_in` | `steamid` `"***1234"`, `ms` int | `Steam_BLoggedOn` first true |
| `login_failed` | `eresult` int (0 = unknown), `reason` string | logon wait ends without `BLoggedOn`: hard auth failure (EResult 5 `InvalidPassword` / 15 `AccessDenied` / 84 `RateLimitExceeded`), other `SteamServerConnectFailure` EResults (`16` `Timeout`, `3` `NoConnection`, else `"SteamServerConnectFailure"`), or plain `"timeout"` (eresult 0). `"Steam_BLoggedOn export missing"` if the export is absent. Emitted at most once. |
| `appinfo` | `state` `installed` \| `not_installed` \| `skipped` \| `timeout` | after the RefreshAppInfo / GetAppInstallState step. `installed` = install state has bit 4; `timeout` = not installed AND the in-Wine `RequestAppInfoUpdate` wait timed out; `not_installed` otherwise; `skipped` = step not run (not logged in, appId 0, no IClientAppManager, or slot not executable). |
| `launch_accepted` | — | the moment a `LaunchApp` attempt is marked ACCEPTED (`launchAppAccepted = true`) |
| `launch_refused` | `error` int (last polled `EAppUpdateError`, `-1` if never polled), `reason` string (`launchFailureReason`) | LaunchApp was refused/unavailable (null call handle exhausted, `EAppUpdateError > 0`, MissingConfig retries exhausted, null IClientAppManager/IClientEngine, appId 0). Emitted once, before the grace window / any fallback. Not emitted for an accepted launch that never spawned (see `insecure_fallback`). |
| `direct_exe` | `exe` string | `WN_STEAM_DIRECT_EXE` mode, right before the deliberate `CreateProcess` launch |
| `insecure_fallback` | `exe` string, `reason` string, `vac` bool (p3b) | right before `create_process_game()` is used as a fallback (not in direct-exe mode). `vac` = the `WN_STEAM_VAC` policy the app sent (`true` when the env is absent): `true` — the title needed a Steam-owned launch and the direct start loses it (VAC servers will reject, overlay warns); `false` — the title never needed one, the direct start is fine. |
| `game_spawned` | `exe` string (basename), `pid` u32 (0 if unknown), `secure` bool | game process first observed running (start of the exit watch). `secure:true` only for the Steam-owned LaunchApp path; `false` for the CreateProcess fallback and direct-exe mode. |
| `friends_relay` | `state` `live` \| `off`, `reason` string | p3b. Once per run, ~5 s after `game_spawned` (or at logon in the M1 resident loop): the friends/chat relay verdict. `live` → `reason` names the launch path (`LaunchApp path` / `CreateProcess fallback` / `M1 resident`) and a `friends` snapshot follows; `off` → `reason` is why (`BL_AGENT_FRIENDS not set`, `channel not up`, `no ISteamClient`, `no ISteamFriends`, `ISteamFriends slot N not exec`, …). Never emitted if the game could not be started at all (`shutdown.reason = launch-failed`). |
| `session_lost` | — | `Steam_BLoggedOn` flips false while the game is running (checked once per 1 s watch tick, channel up only) or in the M1 resident loop's existing 10 s check. Once per transition. |
| `achievement` | `api` string | inside `emit_achievement_event` (natural unlock via `UserAchievementStored` or sentinel fire), in addition to the file it writes |
| `game_exited` | `code` int (always `-1`: exit code is not observable — handles closed / process adopted), `ms` int | the watch loop declares the game gone (2 consecutive absent polls) |
| `status` | `logged_in` bool, `game_running` bool, `secure` bool, `appid` u32, `region` string (as in `started`) | reply to the `status` command |
| `shutdown` | `reason` string, `code` int | last event on every exit path; after it the agent half-closes its send side (app sees EOF). Emitted from a wrapper around the agent's `main()` (every `return`) plus the clean-shutdown sentinel `ExitProcess` path and the console-ctrl handler. |

`shutdown.reason` values: `game-exit`, `launch-failed`, `resident-stop`
(M1 `C:\wn-launcher.stop`), `app-logoff` (M1 loop, `logoff` command),
`steamclient-load-failed`, `steamclient-exports-missing`, `no-iclientengine`,
`invalid-pipe-user`, `sentinel` (`C:\wn-launcher.shutdown`, code 0),
`console-ctrl` (code 0), `exit` (any other path). `code` is the process exit code
(2/3/4/5/6/9/0 as before).

Typical happy path:
```
{"ev":"started","pid":1234,"appid":440,"agent":"p3c","region":"auto:fra2"}
{"ev":"logged_in","steamid":"***4567","ms":2310}
{"ev":"appinfo","state":"installed"}
{"ev":"launch_accepted"}
{"ev":"game_spawned","exe":"hl2.exe","pid":1300,"secure":true}
{"ev":"friends_relay","state":"live","reason":"LaunchApp path"}
{"ev":"friends","self":{...},"count":19,"list":[{"sid":"7656…","name":"…","state":0,"rel":3,"app":0,"k":0},…]}
{"ev":"persona","sid":"7656…","name":"…","state":1,"rel":3,"app":0,"k":1}
{"ev":"persona","sid":"7656…","name":"…","state":1,"rel":3,"app":440,"rp":"Playing on Upward","k":1}
{"ev":"achievement","api":"TF_PLAY_GAME_EVERYCLASS"}
{"ev":"game_exited","code":-1,"ms":901234}
{"ev":"shutdown","reason":"game-exit","code":0}
```

## Commands (app -> agent)

Same socket, same framing. Parsed with a tiny hand-written extractor: the first
`"cmd":"<value>"` pair on the line (whitespace allowed around the colon). Unknown or
malformed commands are ignored.

| command | effect |
|---|---|
| `{"cmd":"status"}` | agent replies with a `status` event |
| `{"cmd":"logoff"}` | sets an atomic flag. **M1 resident loop** (login-only mode): leaves the loop like the stop sentinel, then runs the clean teardown (`Steam_LogOff` + release) if armed, exits with the usual code, `shutdown.reason = "app-logoff"`. **Game-watch loop**: the game is NEVER killed; the agent logs `app logoff requested while "<exe>" is running — deferring logoff until the game exits` once and performs its normal game-exit clean shutdown (logoff) as soon as the game is gone. The flag is not consulted during login / launch attempts / grace windows. |

Logged when received: `[wn-launcher] agent channel: logoff requested by app`.

## Steam connection region seed (agent p2)

Env (both optional, set by `RealSteamLauncher.prepare` from Settings → Steam →
"Steam connection region"):

- **`BL_STEAM_REGION`** — free-text description reported back in `started` / `status`.
- **`WN_STEAM_CMLIST`** — path of a GameHub-format list,
  `{"datacenter":"<dc>","cm_list":[{"endpoint":"cmp1-<dc>.steamserver.net:443"},…]}`.
  Before `steamclient64.dll` is loaded the agent (re)writes the genuine client's CM cache
  in `config\config.vdf`: `InstallConfigStore/Software/Valve/Steam/CMWebSocket` →
  one `"host:port" { LastPingTimestamp, LastPingValue, LastLoadValue }` per endpoint
  (fresh skeleton when the file is empty; the existing block replaced or inserted
  otherwise, brace-matched; any parse doubt → skip + log). Log line:
  `[wn-launcher] cmlist: seeded config.vdf CMWebSocket (<fresh|replaced|inserted>) dc=<dc> with N endpoint(s), first=<endpoint>`
  or `[wn-launcher] cmlist: no WN_STEAM_CMLIST (region=…) - client discovers CMs itself`.
  Acceleration only — the client's own directory discovery is untouched.

## Secure-launch policy: `WN_STEAM_VAC` (agent p3b)

Env set by `RealSteamLauncher.prepare` per launch: **`WN_STEAM_VAC=1`** (or absent) — the
title needs a Steam-owned (VAC-secure) launch; **`WN_STEAM_VAC=0`** — it does not (the app
derives it from PICS app-info: `common/category/category_8` "Valve Anti-Cheat enabled" or
any `extended/vac*` key such as `vacmodulefilename`, with a per-shortcut override).

Effect, only when `LaunchApp` was ACCEPTED (`EAppUpdateError=0`) but the game process never
appears: with `1` the agent keeps the full secure window (20 s appear-wait + 20 s grace +
40 s extension ≈ 60 s) before the last-resort `CreateProcess`; with `0` the appear-wait is
15 s and there is no grace/extension — the direct start follows immediately (the title
loses nothing). Refused launches, direct-exe mode and a clean spawn are unchanged. Log
lines: `[wn-launcher] secure-launch policy: WN_STEAM_VAC=<v> -> …` at launch time, and on
the non-VAC fallback `[wn-launcher] Steam ACCEPTED but never spawned "<exe>" in ~15s — this
title needs no secure launch (WN_STEAM_VAC=0); starting it directly via CreateProcess`.

## Friends / chat relay (agent p3, `agent_friends.h`)

While a SteamLite game runs the app's own Steam session is paused (one session per
account), so the app cannot see friends or chat. The genuine client that runs the game
can; the agent relays it over this same socket.

Activation: channel up AND env **`BL_AGENT_FRIENDS=1`** (the app sets it only when its
friends/chat feature is opted in). Otherwise nothing below runs.

**When it starts (p3b):** NOT at logon. Creating the public `ISteamClient` /
`ISteamFriends` adapters (an extra pipe/user on the genuine client) before
`IClientAppManager::LaunchApp` made the Steam-spawned game die at startup (`c0000005` in
`kernel32`, Brawlhalla 291550, 2026-09-02; the same launch with the relay off spawned
cleanly). The relay is now armed only once the game process has been observed running for
**5 s** (5 watch ticks, so the game's own steam_api init is over) — after `game_spawned` on
both the LaunchApp path and the CreateProcess fallback — or at logon in the M1 resident
loop (no game there). A game that dies inside those 5 s never gets a relay (and no
`friends_relay` event). The verdict is reported as `friends_relay`. Log line
at logon: `[wn-launcher] friends: relay deferred until the game is running (no public
ISteamClient before LaunchApp)`; then `[wn-launcher] friends: starting relay (<path>)`.

Implementation uses the
PUBLIC Steamworks adapters (`CreateInterface("SteamClient021")` →
`ISteamClient::GetISteamFriends(hUser, hPipe, "SteamFriends017")`; slots 0–66, stable
since SteamFriends015; p3c adds slot 37 `RequestUserInformation` (required) and slot 45
`GetFriendRichPresence` (optional — a non-executable slot just drops `rp`)) — no IClient*
reverse engineering. Every vtable slot is checked for
executability once; a missing slot disables the relay for the run. Steam calls happen only
on the agent's main thread (the socket reader thread just queues commands). Chat text is
never written to `wn-launcher.log`.

Log lines: `[wn-launcher] friends: relay live (listen=1, rp=1, <path>)` (or `[wn-launcher]
friends: <reason> - relay off`), `[wn-launcher] friends: roster sent (N friend(s), K with known
presence)`, `… presence round R - N friend(s) queued`, `… persona requests sent (N), M already
cached`, `… chat message relayed (N byte(s))`, `… chat send -> ok|refused`.

### Presence subscription (agent p3c)

A headless client never asks Steam about its friends: the real Steam UI issues
`ClientRequestFriendData` per friend and consumes `PersonaStateChange_t`; without that,
`GetFriendPersonaState` answers `Offline` (0) for everyone right after logon — which is exactly
what p3/p3b relayed (every friend "Offline" in the app). p3c therefore:

1. enumerates the roster (`friends` event, as before), then calls
   `ISteamFriends::RequestUserInformation(sid, bRequireNameOnly=false)` for every friend —
   **20 per loop tick** (1 s in the game-watch loop, 0.5 s in the M1 resident loop; the first
   batch goes out immediately). A `false` return means the client already holds the data →
   the friend is marked known and its `persona` is emitted straight from the client's cache;
   `true` means the CM was asked → the answer arrives as `PersonaStateChange_t` (304).
2. drains `PersonaStateChange_t` in the existing callback pump (`Steam_BGetCallback` /
   `Steam_FreeLastCallback` on the relay's pipe, same loops that carry chat): on each, the
   friend is re-read (`GetFriendPersonaState`, `GetFriendPersonaName`, `GetFriendRelationship`,
   `GetFriendGamePlayed` → app id, and `GetFriendRichPresence(sid, "status")` when in a game)
   and a `persona` event is emitted **only if the snapshot differs from the last one sent**
   (per-SteamID cache). Callbacks for non-friends (clan members, chat peers, ourselves) are
   dropped by relationship.
3. re-sends the compact `friends` roster every **30 s** while live (late arrivals), and at that
   point re-queues `RequestUserInformation` for friends still unknown — at most **3 rounds** per
   run.

Every roster entry carries **`k`**: `1` = the client has confirmed this friend's presence
(request answered or already cached), `0` = still the post-logon default — the app must not
downgrade a friend to Offline on `k:0`. Nothing here runs before the relay is armed (p3b
ordering kept: ~5 s after the game process is running; never before `LaunchApp`), and all of
it is gated on `BL_AGENT_FRIENDS=1`. Expected on a 19-friend roster: `roster sent (19
friend(s), 0 with known presence)` → `persona requests sent (N), M already cached` within the
same second → `persona` events over the next few seconds → the 30 s roster shows `19 with
known presence`.

### Events (agent → app)

| `ev` | fields | when |
|---|---|---|
| `friends` | `self` `{name, state}`, `count` int, `list` `[ {sid string (SteamID64), name, state int (EPersonaState), rel int (EFriendRelationship: 3 = friend, 2 = incoming request, 4 = outgoing request), app u32 (game being played, 0 = none), k int (p3c: 1 = presence confirmed, 0 = unknown), rp string (p3c, optional: rich-presence `status`, only when in a game and non-empty)} … ]` | right after the relay comes up (after `game_spawned` / `friends_relay{live}`; M1: post-logon), every 30 s (p3c; was 60 s), and on `friends_refresh` |
| `persona` | p3c, FLAT: `sid` string, `name` string, `state` int, `rel` int, `app` u32, `rp` string (optional, as above), `k` int (always 1). p3/p3b nested the same entry under `friend`. | `PersonaStateChange_t` (304) for a friend / pending request whose snapshot changed since the last `persona` or roster line, and once per friend whose data the client already had when `RequestUserInformation` was called |
| `chat_in` | `sid` string, `text` string (UTF-8, JSON-escaped), `ts` int (unix seconds, agent clock) | `GameConnectedFriendChatMsg_t` (343) with a `k_EChatEntryTypeChatMsg` body, read back via `GetFriendMessage` |
| `chat_typing` | `sid` string | same callback with a `k_EChatEntryTypeTyping` entry |
| `chat_sent` | `sid` string, `ok` bool | reply to `chat_send` (`ReplyToFriendMessage` verdict) |

Friend SteamIDs are sent in full here (the app keys its roster on them); only the OWN
SteamID stays masked in `logged_in`. The app never logs these lines and keeps only a
body-free `{"ev":…}` marker in its event log.

### Commands (app → agent)

| command | effect |
|---|---|
| `{"cmd":"chat_send","sid":"7656…","text":"…"}` | `ReplyToFriendMessage(sid, text)` on the in-game client; answered by `chat_sent`. `text` is a JSON string (escapes `\" \\ \n \r \t \uXXXX` incl. surrogate pairs are decoded). |
| `{"cmd":"friends_refresh"}` | emit a fresh `friends` snapshot |
| `{"cmd":"chat_typing","sid":"…"}` | accepted and ignored — `ISteamFriends` has no typing primitive |

Commands are parsed by the channel's reader thread and queued (max 256); the main loop
(M1 resident tick / game-watch tick) drains them.
