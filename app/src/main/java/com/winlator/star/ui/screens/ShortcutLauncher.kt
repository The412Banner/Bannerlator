package com.winlator.star.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Shortcut
import com.winlator.star.core.WinePath
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.EaSupport
import com.winlator.star.store.GoldbergComponent
import com.winlator.star.store.GoldbergMode
import com.winlator.star.store.GoldbergPatcher
import com.winlator.star.store.SteamDatabase
import com.winlator.star.store.SteamGameUpdater
import com.winlator.star.store.SteamLiteComponent
import com.winlator.star.store.SteamLoginActivity
import com.winlator.star.store.SteamPrefs
import com.winlator.star.store.SteamSessionManager
import com.winlator.star.store.steamscript.InstallScriptExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The game-launch flow of the Games screen, lifted out of ShortcutsScreen so the Deck home launches
 * through the exact same path: EA checks, the launch-method sheet, the SteamLite pre-flight, Goldberg
 * patching, on-demand component downloads and the manual Steam update/verify runs.
 *
 * Hold one with [rememberShortcutLauncher], call [requestLaunch], and place [ShortcutLaunchDialogs]
 * in the same screen so its dialogs have somewhere to draw.
 */
@Stable
class ShortcutLauncher internal constructor(
    private val context: Context,
    private val activity: Activity,
    internal val eaScope: CoroutineScope,
) {
    // Steam launch-method popup (feature M3): the Steam-origin shortcut whose SteamLite-vs-Goldberg
    // chooser is open (null = closed). A Steam game routes through this before launching UNLESS it
    // already has a remembered choice (launchMode set + launchModeRemembered=="1").
    internal var launchChoiceFor by mutableStateOf<Shortcut?>(null)
    // EA support (see EaSupport): an EA title either needs its one-time EA Desktop setup, is unsupported
    // (Javelin anti-cheat), or launches straight through SteamLite with the EA chain armed.
    internal var eaSetupFor by mutableStateOf<Shortcut?>(null)
    internal var eaUnsupportedFor by mutableStateOf<Shortcut?>(null)
    internal var eaSetupBusy by mutableStateOf(false)
    // SteamLite launch pre-flight (session → network → cloud saves → update check, BEFORE the container opens):
    // the RealSteam game whose "Getting Steam ready" dialog is up (null = none). Every RealSteam
    // launch — the popup pick and a remembered pick — routes through it; Goldberg/Raw never do.
    internal var preflightFor by mutableStateOf<Shortcut?>(null)
    // Download-on-launch progress overlay: the game we're about to launch once its picked component
    // (SteamLite for RealSteam, or Goldberg) finishes downloading, plus a label + 0..1 fraction.
    // null target = nothing downloading.
    internal var componentDownloadFor by mutableStateOf<Shortcut?>(null)
    internal var componentDownloadLabel by mutableStateOf("")
    internal var componentDownloadProgress by mutableFloatStateOf(0f)
    // RealSteam manual maintenance (the launch popup's Verify / Update buttons — SteamLite roadmap #3):
    // the game whose maintenance run is in flight (null = none), its progress (<0 = indeterminate
    // "checking", 0..1 while working), a label, a dialog title, and the cancel handle. This is NO LONGER
    // on the launch path — update/verify are explicit now, so a run never launches the game.
    internal var steamUpdateFor by mutableStateOf<Shortcut?>(null)
    internal var steamUpdateProgress by mutableFloatStateOf(-1f)
    internal var steamUpdateLabel by mutableStateOf("")
    internal var steamUpdateTitle by mutableStateOf("")
    internal var steamUpdateHandle by mutableStateOf<SteamGameUpdater.UpdateHandle?>(null)
    // "Check for updates" is check-THEN-offer (never auto-applies): a cheap [checkForUpdate] probe runs
    // first, and when it finds a delta (or can't tell from cached data) this holds the game + its status so
    // the confirm dialog below can offer to apply it. null = no offer pending.
    internal var steamUpdateOffer by mutableStateOf<Pair<Shortcut, SteamGameUpdater.UpdateStatus>?>(null)

    // Manual RealSteam maintenance: run a delta [update] or a full "verify integrity" [verify] pass for the
    // game, reusing the shared progress modal. Standalone — it never launches the game; the outcome
    // surfaces as a toast. Cancellable via steamUpdateHandle (the modal's Cancel button).
    internal fun runSteamMaintenance(s: Shortcut, verify: Boolean) {
        val appId = steamAppIdOf(s)
        steamUpdateTitle = if (verify) "Verifying game files" else "Updating game"
        steamUpdateLabel = if (verify) "Verifying ${s.name}…" else "Checking ${s.name} for updates…"
        steamUpdateProgress = -1f
        steamUpdateFor = s
        val progress = SteamGameUpdater.ProgressCallback { frac, label ->
            steamUpdateProgress = frac; steamUpdateLabel = label
        }
        val done = SteamGameUpdater.DoneCallback { result, msg ->
            steamUpdateFor = null
            steamUpdateHandle = null
            // Every terminal result except a user cancel is worth a one-line toast (offline / failed /
            // "Files verified" / "Updated" / "Already up to date"). A launch never follows.
            if (result != SteamGameUpdater.Result.CANCELLED && msg.isNotBlank()) {
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        steamUpdateHandle =
            if (verify) SteamGameUpdater.verifyFiles(context, appId, progress, done)
            else SteamGameUpdater.updateNow(context, appId, progress, done)
    }

    // "Check for updates": a cheap, network-free [checkForUpdate] probe (shown in the shared progress modal
    // as the animated "Checking…" indeterminate phase), then BRANCH — we never auto-apply. Up-to-date /
    // not-installed just inform via a toast; an available (or can't-tell-offline) result opens the confirm
    // dialog, whose [Update now] / [Check online] hands off to runSteamMaintenance (the authoritative
    // updateNow pass). The onResult lands on the main thread (see checkForUpdate's doc).
    internal fun checkForUpdatesThenOffer(s: Shortcut) {
        val appId = steamAppIdOf(s)
        steamUpdateTitle = "Checking for updates"
        steamUpdateLabel = "Checking ${s.name}…"
        steamUpdateProgress = -1f   // opens in the animated indeterminate state, never a static 0%.
        steamUpdateFor = s
        steamUpdateHandle = SteamGameUpdater.checkForUpdate(context, appId) { status ->
            steamUpdateFor = null
            steamUpdateHandle = null
            when (status.state) {
                SteamGameUpdater.State.UP_TO_DATE -> {
                    val b = if (status.installedBuild > 0L) " (build ${status.installedBuild})" else ""
                    Toast.makeText(context, "${s.name} is up to date$b", Toast.LENGTH_LONG).show()
                }
                SteamGameUpdater.State.NOT_INSTALLED ->
                    Toast.makeText(context, "${s.name} isn't installed", Toast.LENGTH_LONG).show()
                SteamGameUpdater.State.UPDATE_AVAILABLE, SteamGameUpdater.State.UNKNOWN ->
                    steamUpdateOffer = s to status
            }
        }
    }

    // Goldberg (offline emulator) launch: persist the sub-mode, download the component on demand,
    // patch the install (resolved off-main from the Room steam_games row) and launch. Shared by the
    // popup's Goldberg pick and the pre-flight's "Launch with Goldberg" fallback.
    internal fun launchWithGoldberg(s: Shortcut, gm: GoldbergMode) {
        val appId = steamAppIdOf(s)
        SteamPrefs.init(context)
        SteamPrefs.setGoldbergMode(appId, gm)
        // Resolve the on-disk install dir (Room steam_games row) off the main thread, then
        // patch the tier and launch. Mirrors SteamGameDetailActivity.onGoldbergModeSelected.
        val applyThenLaunch = {
            Thread({
                val installDir = runCatching {
                    SteamDatabase.getInstance(context).getGame(appId)?.installDir
                }.getOrNull().orEmpty()
                activity.runOnUiThread {
                    if (installDir.isEmpty()) {
                        // Nothing to patch (unresolved install dir) — launch as-is.
                        launchShortcutNow(activity, s)
                    } else {
                        GoldbergPatcher.applyModeAsync(context, appId, installDir, s.name, gm) { _, _ ->
                            launchShortcutNow(activity, s)
                        }
                    }
                }
            }, "goldberg-apply-launch").start()
        }
        if (!GoldbergComponent.isInstalled(context)) {
            componentDownloadFor = s
            componentDownloadLabel = "Steam Emulator (Goldberg)"
            componentDownloadProgress = 0f
            GoldbergComponent.downloadAsync(
                context,
                { f -> componentDownloadProgress = f },
                { ok, msg ->
                    componentDownloadFor = null
                    if (ok) applyThenLaunch()
                    else Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
            )
        } else applyThenLaunch()
    }

    // SteamLite (RealSteam) launch: ensure the SteamLite package is present (download on demand if
    // not), then open the pre-flight dialog — the session/cloud/update checks run THERE, in the
    // library, so a dead sign-in or a stale build is reported before the container ever opens.
    internal fun launchWithSteamLite(s: Shortcut) {
        if (!SteamLiteComponent.isInstalled(context)) {
            componentDownloadFor = s
            componentDownloadLabel = "SteamLite (Real Steam / VAC)"
            componentDownloadProgress = 0f
            SteamLiteComponent.downloadAsync(
                context,
                { f -> componentDownloadProgress = f },
                { ok, msg ->
                    componentDownloadFor = null
                    if (ok) preflightFor = s
                    else Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
            )
        } else {
            preflightFor = s
        }
    }

    // The single launch choke point for the game grid/list. Every game opens the source-adaptive
    // launch-method popup first (Steam → SteamLite/Goldberg/Raw; Epic/GOG/Custom → Raw-only), UNLESS the
    // user already picked a method AND ticked "Remember" for it — a remembered pick launches DIRECTLY via
    // launchShortcutNow (the launchMode extra is honored by the launch pipeline; "Raw" is a plain launch).
    // A remembered RealSteam pick still goes through the SteamLite pre-flight (it is the launch's
    // session check, not part of the method choice).
    // An Android game whose app has since been uninstalled: say so and offer to drop the entry.
    internal var androidMissingFor by mutableStateOf<Shortcut?>(null)

    // Android games open the normal Android way, as their own task — no container, no session.
    // A start counts as played, so they reach "Continue playing" like any other game.
    private fun launchAndroidGame(shortcut: Shortcut) {
        when (val r = AndroidGames.launch(context, shortcut)) {
            AndroidGames.LaunchResult.STARTED -> recordLastPlayed(shortcut)
            AndroidGames.LaunchResult.NOT_INSTALLED -> androidMissingFor = shortcut
            else -> AndroidGames.failureMessage(shortcut, r)?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun requestLaunch(shortcut: Shortcut) {
        // Nothing about the launch-method popup (SteamLite/Goldberg/Raw) applies to an Android app.
        if (AndroidGames.isAndroidEntry(shortcut)) { launchAndroidGame(shortcut); return }
        // EA-published Steam titles have exactly one working path: the genuine client (SteamLite) via
        // EA Desktop's launcher chain. Skip the method popup, make sure the prefix is set up (wine-mono +
        // EA Desktop, one-time), and refuse titles that ship EA Javelin anti-cheat (kernel driver).
        if (isSteamOriginShortcut(shortcut)) {
            val ea = EaSupport.detectForShortcut(shortcut)
            if (ea != null) {
                if (ea.javelinAntiCheat) { eaUnsupportedFor = shortcut; return }
                // Persist: the launch pipeline re-reads the .desktop file, so an unsaved extra is a
                // plain Raw launch (device test #8 — the game started without the Steam client).
                shortcut.putExtra("launchMode", "RealSteam")
                shortcut.putExtra("launchModeRemembered", "1")
                shortcut.saveData()
                val installDir = EaSupport.installDirOf(shortcut)
                if (installDir == null) { launchWithSteamLite(shortcut); return }
                eaScope.launch {
                    val ready = withContext(Dispatchers.IO) {
                        try { EaSupport.prefixReady(context, shortcut.container, installDir) } catch (t: Throwable) { true }
                    }
                    if (ready) launchWithSteamLite(shortcut) else eaSetupFor = shortcut
                }
                return
            }
        }
        // The Linux runtime's own entry is not a Windows game: SteamLite, Goldberg and "Raw .exe"
        // all mean nothing for it, and the sheet was an extra tap on every single launch.
        if (LinuxShortcuts.isLinuxEntry(shortcut)) { launchShortcutNow(activity, shortcut); return }
        val remembered = shortcut.getExtra("launchMode", "").isNotEmpty() &&
            shortcut.getExtra("launchModeRemembered", "") == "1"
        when {
            remembered && shortcut.getExtra("launchMode", "") == "RealSteam" && isSteamOriginShortcut(shortcut) ->
                launchWithSteamLite(shortcut)
            remembered -> launchShortcutNow(activity, shortcut)
            else -> launchChoiceFor = shortcut
        }
    }

    // A SteamLite launch that failed inside the container (the launch overlay's "Retry" / "Launch with Goldberg" buttons) records a pending relaunch before the session's normal exit restarts the app.
    // The library screen calls this once its list is loaded to re-enter the matching flow.
    fun resumePendingRelaunch(shortcuts: List<Shortcut>) {
        if (shortcuts.isEmpty()) return
        val pending = SteamSessionManager.takePendingRelaunch(context) ?: return
        val s = shortcuts.firstOrNull { it.file.path == pending.shortcutPath } ?: return
        when (pending.mode) {
            SteamSessionManager.RelaunchMode.STEAMLITE -> launchWithSteamLite(s)
            SteamSessionManager.RelaunchMode.GOLDBERG -> {
                SteamPrefs.init(context)
                val gm = SteamPrefs.getGoldbergMode(steamAppIdOf(s)).let { if (it == GoldbergMode.OFF) GoldbergMode.REGULAR else it }
                launchWithGoldberg(s, gm)
            }
        }
    }
}

@Composable
fun rememberShortcutLauncher(): ShortcutLauncher {
    val context = LocalContext.current
    val eaScope = rememberCoroutineScope()
    return remember(context) { ShortcutLauncher(context, context as Activity, eaScope) }
}

/**
 * Every dialog the launch flow can raise. Place it where the launching screen draws its own dialogs.
 * [onRemove] drops an entry from the Games list (the "isn't installed anymore" dialog's Remove).
 */
@Composable
fun ShortcutLaunchDialogs(launcher: ShortcutLauncher, onRemove: (Shortcut) -> Boolean) {
    val context = LocalContext.current
    val activity = context as Activity

    launcher.androidMissingFor?.let { s ->
        OutlinedAlertDialog(
            onDismissRequest = { launcher.androidMissingFor = null },
            title = { Text("${s.name} isn't installed anymore") },
            text = { Text("The app is no longer on this phone. Remove it from your Games list?") },
            confirmButton = {
                TextButton(onClick = {
                    launcher.androidMissingFor = null
                    val ok = onRemove(s)
                    Toast.makeText(
                        context,
                        if (ok) "Shortcut removed." else "Failed to remove shortcut.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { launcher.androidMissingFor = null }) { Text("Keep") } },
        )
    }

    // ── Steam launch-method popup (M3): SteamLite (real Steam / VAC) vs Goldberg (offline) ──────────
    launcher.eaUnsupportedFor?.let { s ->
        AlertDialog(
            onDismissRequest = { launcher.eaUnsupportedFor = null },
            title = { Text("Not supported: EA anti-cheat") },
            text = {
                Text(
                    "\"${s.name}\" ships EA Javelin anti-cheat, which needs a Windows kernel driver. " +
                        "It cannot run under Wine on any Android emulator, so Bannerlator won't start the EA setup for it."
                )
            },
            confirmButton = { TextButton(onClick = { launcher.eaUnsupportedFor = null }) { Text("OK") } },
        )
    }
    launcher.eaSetupFor?.let { s ->
        AlertDialog(
            onDismissRequest = { if (!launcher.eaSetupBusy) launcher.eaSetupFor = null },
            title = { Text("Set up EA Desktop") },
            text = {
                Text(
                    "\"${s.name}\" is an EA title: it launches through EA Desktop, which isn't installed in this " +
                        "container yet. Bannerlator will open one setup session (wine-mono first if the container " +
                        "lacks it) and run EA's installer — follow its prompts when it shows them. The session closes " +
                        "by itself when the installer finishes and the app comes back. Then launch the game again and " +
                        "sign in to EA when it asks. This happens once per container."
                )
            },
            confirmButton = {
                TextButton(enabled = !launcher.eaSetupBusy, onClick = {
                    launcher.eaSetupBusy = true
                    launcher.eaScope.launch {
                        // Resolve with the SAME derivation that decided to show this dialog
                        // (EaSupport.installDirOf): a legacy shortcut written before steamAppId was stamped
                        // (pre-2026-08 downloads), or a drive-letter path the strict resolver can't map, used
                        // to dead-end here with a misleading "install folder" toast (reported on NFS Heat, 3.0.7).
                        val installDir = withContext(Dispatchers.IO) {
                            runCatching { EaSupport.installDirOf(s) }.getOrNull()
                        }
                        val exe = withContext(Dispatchers.IO) {
                            val resolved = runCatching { WinePath.resolveAndroidPath(s.container, s.path)?.absolutePath }.getOrNull()
                            // runForShortcut locates the depot from the exe's steam_games/ segment, so prefer a
                            // path inside the resolved depot when the direct mapping lacks that segment.
                            resolved?.takeIf { InstallScriptExecutor.locateInstallDir(File(it)) != null }
                                ?: installDir?.let { File(it, s.path.replace('\\', '/').substringAfterLast('/')).absolutePath }
                                ?: resolved
                        }
                        val appId = withContext(Dispatchers.IO) {
                            runCatching { EaSupport.resolveSteamAppId(s, installDir) }.getOrDefault(0)
                        }
                        if (exe != null && appId > 0) {
                            withContext(Dispatchers.IO) {
                                try { InstallScriptExecutor.runForShortcut(context, s.container, appId, exe, true) }
                                catch (t: Throwable) { android.util.Log.w("ShortcutsScreen", "EA setup failed", t) }
                            }
                        } else {
                            android.util.Log.w("ShortcutsScreen", "EA setup: cannot start for '${s.name}' — exe=$exe appId=$appId path='${s.path}' container=${s.container.id}")
                            Toast.makeText(
                                context,
                                if (exe == null) "Couldn't locate the game's install folder (${s.path})"
                                else "Couldn't work out this game's Steam app id — re-add it from the Steam library",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        launcher.eaSetupBusy = false
                        launcher.eaSetupFor = null
                    }
                }) { Text(if (launcher.eaSetupBusy) "Starting…" else "Set up") }
            },
            dismissButton = { TextButton(enabled = !launcher.eaSetupBusy, onClick = { launcher.eaSetupFor = null }) { Text("Cancel") } },
        )
    }
    launcher.launchChoiceFor?.let { s ->
        val appId = steamAppIdOf(s)
        LaunchMethodSheet(
            shortcut = s,
            onDismiss = { launcher.launchChoiceFor = null },
            // Verify runs a full re-validate pass directly. "Check for updates" is check-THEN-offer: probe
            // first, then a confirm dialog lets the user choose to apply — it never auto-updates. Both
            // dismiss the sheet first (so no dialog is layered behind the ModalBottomSheet's window).
            onUpdateFiles = { launcher.launchChoiceFor = null; launcher.checkForUpdatesThenOffer(s) },
            onVerifyFiles = { launcher.launchChoiceFor = null; launcher.runSteamMaintenance(s, verify = true) },
            onLaunch = { mode, goldbergMode, remember, controllerPassthrough, vacLaunch ->
                // Persist the choice on the shortcut's [Extra Data] so a remembered pick skips the popup
                // next time (contract literals: launchMode ∈ RealSteam/Goldberg/Raw, launchModeRemembered="1").
                s.putExtra("launchMode", mode)
                s.putExtra("launchModeRemembered", if (remember) "1" else "0")
                // Per-game "Controller passthrough" (read only on RealSteam launches; inert otherwise).
                s.putExtra("controllerPassthrough", if (controllerPassthrough) "1" else "0")
                // Per-game "Requires secure (VAC) launch" override: "" = follow app-info detection, "1"/"0".
                s.putExtra("steamVacLaunch", vacLaunch)
                s.saveData()
                launcher.launchChoiceFor = null
                when (mode) {
                    "Goldberg" -> launcher.launchWithGoldberg(s, goldbergMode ?: GoldbergMode.REGULAR)
                    "Raw" -> {
                        // Raw: run the game's .exe directly with no Steam layer (Epic/GOG/Custom, or a
                        // Steam game the user chose to run raw). The launchMode="Raw" extra is inert to the
                        // launch pipeline (only "RealSteam" stages the agent), so this is a plain launch.
                        launchShortcutNow(activity, s)
                    }
                    else -> {
                        // RealSteam (SteamLite): SteamLite package on demand, then the pre-flight dialog
                        // (session → network → cloud saves → update check) and only then the container. Update/
                        // verify remain the popup's manual buttons; the pre-flight only OFFERS an update.
                        launcher.launchWithSteamLite(s)
                    }
                }
            },
        )
    }

    // ── SteamLite pre-flight ("Getting Steam ready") — runs BEFORE XServerDisplayActivity ──────────
    launcher.preflightFor?.let { s ->
        val appId = steamAppIdOf(s)
        val installDir = remember(s) {
            runCatching { SteamDatabase.getInstance(context).getGame(appId)?.installDir }.getOrNull().orEmpty()
        }
        val savePrefs = remember { context.getSharedPreferences("save_manager_prefs", Context.MODE_PRIVATE) }
        SteamPreflightDialog(
            shortcut = s,
            request = SteamSessionManager.PreflightRequest(
                appId = appId,
                installDir = installDir,
                gameName = s.name,
                pullCloudSaves = savePrefs.getBoolean("auto_download_steam_on_launch", true),
            ),
            onLaunch = { launcher.preflightFor = null; launchShortcutNow(activity, s, preflightDone = true) },
            onDismiss = { launcher.preflightFor = null },
            onSignIn = {
                launcher.preflightFor = null
                context.startActivity(Intent(context, SteamLoginActivity::class.java))
            },
            onGoldberg = {
                launcher.preflightFor = null
                SteamPrefs.init(context)
                launcher.launchWithGoldberg(s, SteamPrefs.getGoldbergMode(appId).let { if (it == GoldbergMode.OFF) GoldbergMode.REGULAR else it })
            },
            onUpdate = { launcher.preflightFor = null; launcher.runSteamMaintenance(s, verify = false) },
        )
    }

    // Blocking progress dialog while the picked component downloads before launch (SteamLite / Goldberg).
    launcher.componentDownloadFor?.let {
        OutlinedAlertDialog(
            onDismissRequest = { /* keep up until the download finishes */ },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Downloading ${launcher.componentDownloadLabel}", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { launcher.componentDownloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(launcher.componentDownloadProgress.coerceIn(0f, 1f) * 100).toInt()}% — the game launches when this finishes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
    }

    // RealSteam manual maintenance: progress while a user-triggered Update or Verify pass runs.
    // Cancellable — cancelling aborts the pass and stays in the library (a run never launches the game).
    launcher.steamUpdateFor?.let {
        OutlinedAlertDialog(
            onDismissRequest = { /* modal until it finishes or is cancelled */ },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text(launcher.steamUpdateTitle, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    if (launcher.steamUpdateProgress < 0f) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { launcher.steamUpdateProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // Prefix a live "N%" once real download progress starts; the indeterminate
                        // "checking/setup" phase (fraction < 0) shows just the phase label.
                        if (launcher.steamUpdateProgress >= 0f)
                            "${(launcher.steamUpdateProgress.coerceIn(0f, 1f) * 100).toInt()}% — ${launcher.steamUpdateLabel}"
                        else launcher.steamUpdateLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { launcher.steamUpdateHandle?.cancel() }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.primary)
                }
            },
        )
    }

    // "Check for updates" outcome: a delta is (or might be) due — offer to apply it. Never auto-updates;
    // [Update now] / [Check online] runs the authoritative updateNow pass, [Later] / [Cancel] does nothing.
    launcher.steamUpdateOffer?.let { (s, status) ->
        val available = status.state == SteamGameUpdater.State.UPDATE_AVAILABLE
        OutlinedAlertDialog(
            onDismissRequest = { launcher.steamUpdateOffer = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    if (available) "Update available" else "Check online?",
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(
                    if (available) {
                        if (status.installedBuild > 0L && status.liveBuild > 0L)
                            "${s.name}: build ${status.installedBuild} → ${status.liveBuild}. Update now?"
                        else "A newer build of ${s.name} is available. Update now?"
                    } else {
                        "Couldn't check ${s.name} from cached data. Do an online check now " +
                            "(and update if it's behind)?"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = { launcher.steamUpdateOffer = null; launcher.runSteamMaintenance(s, verify = false) }) {
                    Text(if (available) "Update now" else "Check online", color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { launcher.steamUpdateOffer = null }) {
                    Text(if (available) "Later" else "Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}
