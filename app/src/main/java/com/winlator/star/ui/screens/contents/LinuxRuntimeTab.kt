package com.winlator.star.ui.screens.contents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.winlator.star.container.ContainerManager
import com.winlator.star.linux.ChildProcessRestrictions
import com.winlator.star.linux.LinuxDeviceSupport
import com.winlator.star.linux.LinuxProtons
import com.winlator.star.linux.LinuxRuntime
import com.winlator.star.linux.LinuxRuntimeInstaller
import com.winlator.star.linux.LinuxRuntimeUpdate
import com.winlator.star.linux.LinuxSettings
import com.winlator.star.linux.LinuxShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

/**
 * The Linux runtime: one opt-in download that turns on gamescope sessions and Valve's native arm64
 * Steam client. It is a whole second userland, so it is deliberately not mixed in with the `.wcp`
 * components — nothing fetches it until the user asks.
 */
@Composable
fun LinuxRuntimeTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(LinuxRuntimeInstaller.installedVersion(context)) }
    // The Steam entry belongs to the runtime's own settings now, not to a Wine container. A runtime
    // installed before that was so still has its entry in a container, and the install path above
    // only runs on a download - so make sure of the entry whenever the tab is opened on an
    // installed runtime. Idempotent: it returns at once when the entry is already where it belongs.
    LaunchedEffect(installed) {
        if (installed != null) withContext(Dispatchers.IO) { addSteamEntry(context) }
    }
    var release by remember { mutableStateOf<LinuxRuntimeInstaller.Release?>(null) }
    var checking by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        LinuxRuntimeUpdate.refreshInstalled(context)
        release = withContext(Dispatchers.IO) { LinuxRuntimeInstaller.fetchRelease() }
        LinuxRuntimeUpdate.setAvailable(release)
        checking = false
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Both gates are read here, before the download.
        // Each is a failure the app cannot fix and that leaves nothing in any log to read.
        // The only thing worth doing about either is saying so first.
        if (!LinuxDeviceSupport.drawable(context)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This device cannot draw a Linux session",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        "The runtime draws with Turnip, an Adreno driver, and this device reports "
                            + "${LinuxDeviceSupport.gpuName(context)}. A session on it comes up as sound over a "
                            + "black screen. You can still download the runtime, but nothing will render — "
                            + "Mali, Xclipse and PowerVR are not supported yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (ChildProcessRestrictions.active(context)) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Turn off child process restrictions first",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer)
                    Text(
                        "A Linux session is dozens of processes — gamescope, the Steam client, its web "
                            + "helper and the game — and Android is set to kill an app's extra child "
                            + "processes. When it does, the session ends with no error and nothing in the "
                            + "log. Turn on \"Disable child process restrictions\" in Developer options "
                            + "before the first launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    if (ChildProcessRestrictions.hasSwitch()) {
                        OutlinedButton(onClick = {
                            if (!ChildProcessRestrictions.openDeveloperOptions(context)) {
                                message = "Could not open Developer options. Settings \u2192 " +
                                    "About phone \u2192 tap Build number seven times, then " +
                                    "Developer options."
                            }
                        }) { Text("Open Developer options") }
                    } else {
                        // Before Android 14 the switch is not in the UI at all, so adb is the only way.
                        // The app cannot write the setting itself whatever the user grants it.
                        Text(
                            "This Android version has no switch for it. From a computer:\n"
                                + ChildProcessRestrictions.adbCommand(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Linux runtime", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Runs Linux programs instead of Windows ones: a glibc rootfs with gamescope as "
                        + "the session compositor, and Valve's Steam client built for ARM. Games run "
                        + "through Steam's own Proton, not through this app's Wine. Optional — "
                        + "nothing else changes if you leave it off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    when {
                        installed != null -> "Installed: ${installed}"
                        checking -> "Checking for a build…"
                        release == null -> "No build available (could not reach the catalog)"
                        else -> "Not installed"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
                release?.let { r ->
                    val size = if (r.size > 0) " · ${r.size / (1024 * 1024)} MB download" else ""
                    Text("Available: ${r.version}$size",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (busy) {
                    Spacer(Modifier.height(4.dp))
                    // Same wording as the Games-tab card, from the same state.
                    Text(LinuxRuntimeUpdate.line(LinuxRuntimeUpdate.state.value),
                        style = MaterialTheme.typography.labelSmall)
                    if (percent in 0..100) {
                        LinearProgressIndicator(progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val target = release
                    Button(
                        enabled = !busy && target != null
                            && (installed == null || installed != target.version),
                        onClick = {
                            val r = target ?: return@Button
                            busy = true; message = null; stage = "Starting…"; percent = -1
                            LinuxRuntimeUpdate.begin()
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    LinuxRuntimeInstaller.install(context, r) { s, p ->
                                        stage = s; percent = p
                                        LinuxRuntimeUpdate.progress(s, p)
                                    }
                                }
                                if (ok) {
                                    // The runtime is only useful with something to launch, so the
                                    // Steam entry appears in the library the moment it lands.
                                    withContext(Dispatchers.IO) { addSteamEntry(context) }
                                    installed = LinuxRuntimeInstaller.installedVersion(context)
                                    message = null
                                } else {
                                    message = "Install failed — the existing runtime was left alone."
                                }
                                busy = false
                                LinuxRuntimeUpdate.finish(context)
                            }
                        }
                    ) { Text(if (installed == null) "Install" else "Update") }

                    OutlinedButton(
                        enabled = !busy && installed != null,
                        onClick = {
                            busy = true
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LinuxRuntimeInstaller.uninstall(context)
                                    removeSteamEntry(context)
                                }
                                installed = null
                                busy = false
                            }
                        }
                    ) { Text("Remove") }
                }

                if (installed != null) {
                    Text(
                        "\"${LinuxShortcuts.STEAM_NAME}\" is in your Games tab. Sign in there with "
                            + "your Steam account; installs and updates are Steam's own.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Only meaningful once the runtime is there: these land inside it, and the session is what
        // unpacks and registers them.
        if (installed != null) ProtonBuildsCard()
    }
}

/**
 * The Proton builds a game in the Steam client can be run with. Downloading one here does not
 * finish the job: the session unpacks and registers it at its next start, because a build's own
 * manifest asks Steam for a container Android cannot provide and has to be rewritten first. The
 * wording says so rather than claiming an install that has not happened yet.
 */
@Composable
private fun ProtonBuildsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var builds by remember { mutableStateOf<List<LinuxProtons.Build>>(emptyList()) }
    var states by remember { mutableStateOf<Map<String, LinuxProtons.State>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var workingOn by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("") }
    var percent by remember { mutableStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var used by remember { mutableStateOf(0L) }

    // Reads the runtime's filesystem, which may be half-built, absent, or owned by a session that
    // is running right now. A tab in Contents must not be able to take the app down, whatever it
    // finds there.
    fun refresh() {
        runCatching {
            states = builds.associate { it.name to LinuxProtons.stateOf(context, it) }
            used = LinuxProtons.installedBytes(context)
        }.onFailure { android.util.Log.w("LinuxRuntimeTab", "proton state", it) }
    }

    LaunchedEffect(Unit) {
        builds = withContext(Dispatchers.IO) { LinuxProtons.fetchCatalog() }
        withContext(Dispatchers.IO) { refresh() }
        loading = false
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Proton builds", style = MaterialTheme.typography.titleMedium)
            Text(
                "What the Steam client runs a Windows game with. Add one here and it appears in "
                    + "that game's Compatibility list, so you can pick a different one per game. "
                    + "A build is unpacked the next time you open the Steam client.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // The list Steam shows is built from the account's licences, not from anything on
            // disk, so it cannot be filtered from here. Saying which entries work is the next best
            // thing - and the ones labelled ARM64 that are not ours are the worst trap in it,
            // because they look like the right answer and fail with no window and no message.
            Text(
                "Steam's own list will also offer Protons that cannot start a game here, "
                    + "including some labelled ARM64. The ones that work are the builds listed "
                    + "below and \"Bannerlator Proton (ARM64)\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (used > 0) {
                Text("Using ${used / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading) {
                Text("Checking for builds…", style = MaterialTheme.typography.labelSmall)
            } else if (builds.isEmpty()) {
                Text("No builds available (could not reach the catalog)",
                    style = MaterialTheme.typography.labelSmall)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }

            builds.forEach { build ->
                val state = states[build.name] ?: LinuxProtons.State.ABSENT
                val busy = workingOn == build.name
                Spacer(Modifier.height(4.dp))
                Text(build.display, style = MaterialTheme.typography.labelLarge)
                if (build.notes.isNotEmpty()) {
                    Text(build.notes, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    when (state) {
                        LinuxProtons.State.INSTALLED -> "Added"
                        LinuxProtons.State.PENDING ->
                            if (build.isDepot) "Requested — the client fetches it next session"
                            else "Ready — unpacked next time you open the Steam client"
                        else ->
                            if (build.isDepot) "Downloaded by the Steam client"
                            else "${build.size / (1024 * 1024)} MB download"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Text(if (percent in 0..100) "$stage $percent%" else stage,
                        style = MaterialTheme.typography.labelSmall)
                    if (percent in 0..100) {
                        LinearProgressIndicator(progress = { percent / 100f },
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = workingOn == null && state == LinuxProtons.State.ABSENT,
                        onClick = {
                            workingOn = build.name; error = null
                            stage = "Starting…"; percent = -1
                            scope.launch {
                                val failure = withContext(Dispatchers.IO) {
                                    LinuxProtons.install(context, build) { s, p ->
                                        stage = s; percent = p
                                    }
                                }
                                error = failure
                                withContext(Dispatchers.IO) { refresh() }
                                workingOn = null
                            }
                        }
                    ) { Text(if (build.isDepot) "Request" else "Download") }

                    // Valve's depots belong to the client, which installs and removes them itself.
                    if (!build.isDepot) {
                        OutlinedButton(
                            enabled = workingOn == null && state != LinuxProtons.State.ABSENT,
                            onClick = {
                                workingOn = build.name; error = null
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        LinuxProtons.remove(context, build)
                                        refresh()
                                    }
                                    workingOn = null
                                }
                            }
                        ) { Text("Remove") }
                    }
                }
            }
        }
    }
}

/**
 * Puts the Steam entry in a container so the Games tab has something to launch: one the user has
 * marked as a gamescope container if there is one, else the first. The entry carries the runtime
 * itself, so it launches into the Linux runtime from either.
 */
internal fun addSteamEntry(context: android.content.Context) {
    runCatching {
        val manager = ContainerManager(context)
        val linux = manager.linuxContainer
        // The settings the client ran with lived at the CONTAINER level of the Wine container its
        // entry used to sit in - the HUD's master switch, its skin, LSFG flags, audio - and the
        // Linux settings start from the app's defaults otherwise. Seed them once from that container:
        // the one still holding the Steam entry, or, if the entry moved before this existed, the one
        // still holding any Linux entry (the desktop one stays behind).
        if (!LinuxSettings.isSeeded(linux)) {
            // In order of certainty: the container still holding the Steam entry; the one the entry
            // was moved out of, recorded at the move; one still holding any Linux entry; one marked
            // as a gamescope runtime. Say so when there is none - a silent miss cost a device its
            // HUD and LSFG settings before this was written.
            val movedFrom = linux.getExtra(LinuxSettings.EXTRA_MOVED_FROM, "").toIntOrNull()
            val source = manager.containers.firstOrNull { LinuxShortcuts.hasSteamShortcut(it) }
                ?: movedFrom?.let { id -> manager.containers.firstOrNull { it.id == id } }
                ?: manager.containers.firstOrNull { LinuxShortcuts.hasLinuxEntry(it) }
                ?: manager.containers.firstOrNull { it.isGamescopeRuntime }
            if (source != null) LinuxSettings.seedFrom(linux, source)
            else android.util.Log.w("LinuxRuntimeTab", "Linux settings not seeded: no container to take them from")
        }
        if (LinuxShortcuts.hasSteamShortcut(linux)) return
        // The entry used to be written into a Wine container, whichever one was first. A copy
        // still there is moved here as it is - its extras are the user's settings - so nothing is
        // lost and the Games tab does not show the client twice. It needs no container at all now.
        val old = manager.containers.firstOrNull { LinuxShortcuts.hasSteamShortcut(it) }
        if (old != null && LinuxShortcuts.moveSteamShortcut(old, linux, context)) {
            // Remembered so the seed above can still find its source on a later open, even after
            // every other entry has left that container.
            linux.putExtra(LinuxSettings.EXTRA_MOVED_FROM, old.id.toString())
            linux.saveData()
            return
        }
        LinuxShortcuts.createSteamShortcut(linux, context)
    }
}

internal fun removeSteamEntry(context: android.content.Context) {
    runCatching {
        val manager = ContainerManager(context)
        LinuxShortcuts.removeSteamShortcut(manager.linuxContainer)
        manager.containers.forEach { LinuxShortcuts.removeSteamShortcut(it) }
    }
}
