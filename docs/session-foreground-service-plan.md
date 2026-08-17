# Session Foreground Service — keep a running container/game alive in the background

**Branch:** `feat/session-foreground-service` (off main `76548421`)
**Problem (device-proven 2026-08-06):** backgrounding the app during a session → guest gets low-memory-killed → app self-runs its shutdown path on return → "Shutting down…". Full root-cause + evidence: memory `project_bannerlator_session_background_kill`.

## Why it happens (one paragraph)
There is **no foreground service** for a running session. On background, `com.tencent.ig` is demoted `oom_score_adj 0 → 700 (PREVIOUS_APP) → 900 (CACHED)` (traced live). At 900 the guest wine/box64 procs are LMK-reaped under memory pressure. On return the app resumes, `setTerminationCallback`/`evaluateGameExitTick` sees the guest gone and calls `exit()` → shutdown → `restartApplication()`. Zero `am_kill` for the UI proc — it self-exits. The existing on-screen notification ("Winlator is running, do not kill or swipe this notification", `XServerDisplayActivity.java:1701-1709`) is a **plain `notify()`, not `startForeground()`** — fake protection.

## Design
Convert the existing (fake) session notification into a **real foreground service** that owns the process priority for the session's lifetime. Reuse the existing channel/text/icon — no new UX (the notification already shows; it just becomes an FGS notification that actually holds the process at perceptible priority ~adj 200, off the LMK target list).

### targetSdk 28 = classic FGS, low risk
`app/build.gradle`: `minSdk 26`, **`targetSdk 28`**, `compileSdk 34`. Because targetSdk < 29, the Android 10/12/14 FGS-type enforcement (runtime type, `specialUse` `<property>` justification, dataSync 6h/day cap) does **not** apply. A plain `startForeground(id, notification)` works portably. Mirror the 3 existing `foregroundServiceType="dataSync"` services (`UnpackService`, `SteamForegroundService`, `DownloadForegroundService`) — no new permissions (`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` already declared; `POST_NOTIFICATIONS` already declared and granted since the user sees today's notification).

## Commits

### Commit 1 — GameSessionForegroundService (the fix)
- **New** `core/GameSessionForegroundService.java` (or `.kt`): a started service. `onStartCommand` pulls the game/shortcut label from the intent, builds the SAME notification (move the builder logic out of `XServerDisplayActivity:1701` or share a helper), calls `ServiceCompat.startForeground(this, NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`. `START_STICKY` is wrong here (we don't want it resurrecting after our own teardown) → `START_NOT_STICKY`. Provide `stop()`.
- **`XServerDisplayActivity.java`:**
  - Fix `NOTIFICATION_ID = -1` (`:171`) → a positive constant (e.g. `1001`); `startForeground`/`notify` with a negative id is fragile.
  - Replace the plain `NotificationManagerCompat.notify(NOTIFICATION_ID, …)` at `:1709` with `ContextCompat.startForegroundService(this, GameSessionForegroundService.intent(this, gameLabel))`. Keep `createNotifcationChannel()` (the service also needs the channel; call it in the service too to be safe).
  - In `exit()` (`:2494`) replace `cancel(NOTIFICATION_ID)` with stopping the service (`stopService(...)` → the FGS calls `stopForeground(STOP_FOREGROUND_REMOVE)` in `onDestroy`). Belt: also `cancel` as a fallback.
- **Manifest:** add
  `<service android:name="com.winlator.star.core.GameSessionForegroundService" android:foregroundServiceType="dataSync" android:exported="false"/>`
- **Acceptance:** repeat the exact device repro — open container, background, open other apps. `oom_score_adj` must stay ~**200 (perceptible)**, NOT climb to 900; guest survives; foregrounding resumes the live session (no "Shutting down…").

### Commit 2 — Root-tier oom pin (opt-in, belt-and-suspenders)
For rooted users only (reuses `perf/RootManager.writeNode`): when a session starts and root is GRANTED, write the app pid + guest pids `/proc/<pid>/oom_score_adj` to a low/negative value (and optionally add to the LMK exemption). Gated behind the existing root grant + a new opt-in toggle in the perf/root section. Non-root users rely solely on Commit 1 (the only lever without root). Revert/clear on exit (the process dies anyway, so mostly harmless, but keep it tidy).

### Commit 3 — Auto-close watcher guard
`evaluateGameExitTick` (`:~6886`) must NOT count "game gone" ticks while the activity is stopped/backgrounded — otherwise a guest reaped-while-cached (or a merely-slow guest) can trip `exit()` on return even with the FGS. Track an `isStopped` flag (set in `onStop`, cleared in `onResume`/`onStart`); skip tick evaluation (or pause the watcher) while stopped. With Commit 1 the guest shouldn't die anyway, but this closes the false-positive path.

## Testing
1. Device repro with the same `oom_score_adj` watcher used to diagnose (scratchpad `blwatch.log` method) — expect steady ~200, no 900, guest count steady, no shutdown on return.
2. Regression: normal exit (drawer Exit / game quits) still tears down cleanly and the notification/service is removed (no stuck FGS notification).
3. Confirm the FGS notification is now non-swipeable while running (it was swipeable before).
4. Long background (10+ min) + heavy app (a real game) → session survives.

## Risks / notes
- FGS notification becomes ongoing/non-dismissable — that's the intended behavior and matches the notification's own text.
- Don't leak the service: every `exit()` path (termination callback, watcher, drawer Exit, back-out) must stop it. `restartApplication()`'s `exit(0)` kills the process, which stops the FGS anyway, but stop it explicitly first.
- OEM aggressive killers (already why WAKE_LOCK / battery-opt-exempt exist for downloads) may still interfere; the battery-optimisation-exemption prompt could be offered for sessions too (future).
