package com.winlator.star.ui.simple

import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.linux.ChildProcessRestrictions
import com.winlator.star.linux.LinuxDeviceSupport
import com.winlator.star.linux.LinuxProtons
import com.winlator.star.linux.LinuxRuntimeInstaller
import com.winlator.star.linux.LinuxRuntimeUpdate
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.download.DownloadScope
import com.winlator.star.ui.screens.contents.addSteamEntry
import com.winlator.star.ui.screens.contents.removeSteamEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MB = 1024L * 1024L

/**
 * Contents › Linux runtime in Lab: Classic's LinuxRuntimeTab as Lab rows, on the same calls. The two warnings (a GPU the
 * runtime cannot draw on, Android's child process restrictions with the way to Developer options), the runtime with
 * Install / Update / Remove and its progress, the Steam entry it puts in the library, and the Proton builds with Download
 * (Request for Valve's) / Remove. Nothing is fetched until the tab is first shown ([active]), as in Classic. The runtime
 * install runs on the download scope, so it finishes (and its state is cleared) even when the page is left. [refresh]
 * changes when the page is shown again (the restrictions switch may have been flipped); [onNote] shows a help text.
 */
@Composable
internal fun rememberLabLinuxRuntime(active: Boolean, refresh: Int, onNote: (LabHelpText) -> Unit): List<MenuEntry> {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val rt by LinuxRuntimeUpdate.state.collectAsState()
    var release by remember { mutableStateOf<LinuxRuntimeInstaller.Release?>(null) }
    var checking by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // The GPU check asks the driver: only once the tab is shown. Null = drawable (or not checked yet), else the GPU's name.
    var undrawableGpu by remember { mutableStateOf<String?>(null) }
    val restricted = remember(refresh) { runCatching { ChildProcessRestrictions.active(context) }.getOrDefault(false) }

    LaunchedEffect(active) {
        if (!active || started) return@LaunchedEffect
        started = true
        if (!runCatching { LinuxDeviceSupport.drawable(context) }.getOrDefault(true)) {
            undrawableGpu = runCatching { LinuxDeviceSupport.gpuName(context) }.getOrDefault("this GPU")
        }
        LinuxRuntimeUpdate.refreshInstalled(context)
        release = withContext(Dispatchers.IO) { runCatching { LinuxRuntimeInstaller.fetchRelease() }.getOrNull() }
        LinuxRuntimeUpdate.setAvailable(release)
        checking = false
    }
    val installed = rt.installed
    // As Classic on opening the tab: an installed runtime gets its Steam entry where it belongs (idempotent).
    LaunchedEffect(installed, started) {
        if (started && installed != null) withContext(Dispatchers.IO) { addSteamEntry(context) }
    }

    // ── Proton builds (only once the runtime is there) ──
    var builds by remember { mutableStateOf<List<LinuxProtons.Build>>(emptyList()) }
    var states by remember { mutableStateOf<Map<String, LinuxProtons.State>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var protonsStarted by remember { mutableStateOf(false) }
    var workingOn by remember { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf("") }
    var percent by remember { mutableIntStateOf(-1) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorFor by remember { mutableStateOf<String?>(null) }
    var used by remember { mutableLongStateOf(0L) }
    // Reads the runtime's filesystem, which may be half-built or in use by a running session: never throws.
    suspend fun refreshProtons() {
        val read = withContext(Dispatchers.IO) {
            runCatching { builds.associate { it.name to LinuxProtons.stateOf(context, it) } to LinuxProtons.installedBytes(context) }
                .onFailure { Log.w("LabLinuxRuntime", "proton state", it) }.getOrNull()
        }
        if (read != null) { states = read.first; used = read.second }
    }
    LaunchedEffect(started, installed != null) {
        if (!started || installed == null || protonsStarted) return@LaunchedEffect
        protonsStarted = true
        builds = withContext(Dispatchers.IO) { runCatching { LinuxProtons.fetchCatalog() }.getOrDefault(emptyList()) }
        refreshProtons()
        loading = false
    }

    val busy = rt.busy || removing
    val target = release
    val canInstall = !busy && target != null && (installed == null || installed != target.version)
    fun install() {
        val r = release ?: return
        if (rt.busy || removing) return
        message = null
        LinuxRuntimeUpdate.begin()
        DownloadScope.io.launch {
            val ok = runCatching { LinuxRuntimeInstaller.install(app, r) { s, p -> LinuxRuntimeUpdate.progress(s, p) } }.getOrDefault(false)
            // The runtime is only useful with something to launch: the Steam entry appears the moment it lands.
            if (ok) runCatching { addSteamEntry(app) }
            LinuxRuntimeUpdate.finish(app)
            if (!ok) message = L("Install failed — the existing runtime was left alone.")
        }
    }
    fun remove() {
        if (busy) return
        removing = true
        message = null
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { LinuxRuntimeInstaller.uninstall(app) }
                removeSteamEntry(app)
            }
            LinuxRuntimeUpdate.refreshInstalled(app)
            removing = false
        }
    }
    fun installProton(b: LinuxProtons.Build) {
        if (workingOn != null) return
        workingOn = b.name; error = null; errorFor = null
        stage = L("Starting…"); percent = -1
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { LinuxProtons.install(context, b) { s, p -> stage = s; percent = p } }.getOrElse { it.message ?: it.toString() }
            }
            error = failure
            errorFor = if (failure != null) b.name else null
            refreshProtons()
            workingOn = null
        }
    }
    fun removeProton(b: LinuxProtons.Build) {
        if (workingOn != null) return
        workingOn = b.name; error = null; errorFor = null
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { LinuxProtons.remove(context, b) } }
            refreshProtons()
            workingOn = null
        }
    }

    val gpuNote = LabHelpText(
        L("This device cannot draw a Linux session"),
        L(
            "The runtime draws with Turnip, an Adreno driver, and this device reports %1\$s. A session on it comes up as sound over a black screen. You can still download the runtime, but nothing will render — Mali, Xclipse and PowerVR are not supported yet.",
            undrawableGpu ?: "",
        ),
    )
    val restrictionsText = L("A Linux session is dozens of processes — gamescope, the Steam client, its web helper and the game — and Android is set to kill an app's extra child processes. When it does, the session ends with no error and nothing in the log. Turn on \"Disable child process restrictions\" in Developer options before the first launch.") +
        if (ChildProcessRestrictions.hasSwitch()) "" else "\n\n" + L("This Android version has no switch for it. From a computer:") + "\n" + ChildProcessRestrictions.adbCommand()
    val restrictionsNote = LabHelpText(L("Turn off child process restrictions first"), restrictionsText)
    fun openDeveloperOptions() {
        if (!ChildProcessRestrictions.openDeveloperOptions(context)) {
            onNote(LabHelpText(L("Open Developer options"), L("Could not open Developer options. Settings → About phone → tap Build number seven times, then Developer options.")))
        }
    }
    val aboutRuntime = LabHelpText(
        L("Linux runtime"),
        L("Runs Linux programs instead of Windows ones: a glibc rootfs with gamescope as the session compositor, and Valve's Steam client built for ARM. Games run through Steam's own Proton, not through this app's Wine. Optional — nothing else changes if you leave it off."),
    )
    val aboutProtons = LabHelpText(
        L("Proton builds"),
        L("What the Steam client runs a Windows game with. Add one here and it appears in that game's Compatibility list, so you can pick a different one per game. A build is unpacked the next time you open the Steam client.") +
            "\n\n" + L("Steam's own list will also offer Protons that cannot start a game here, including some labelled ARM64. The ones that work are the builds listed below and \"Bannerlator Proton (ARM64)\"."),
    )

    return buildList {
        val group = L("Linux runtime")
        if (undrawableGpu != null) add(
            MenuEntry(
                "lx-gpu", gpuNote.title, gpuNote.text, icon = Icons.Filled.Warning, group = group,
                onA = { onNote(gpuNote) },
            ),
        )
        if (restricted) add(
            MenuEntry(
                "lx-cpr", restrictionsNote.title,
                if (ChildProcessRestrictions.hasSwitch()) L("Turn on \"Disable child process restrictions\" in Developer options before the first launch.")
                else L("This Android version has no switch for it. From a computer:") + " " + ChildProcessRestrictions.adbCommand(),
                icon = Icons.Filled.Warning, group = group,
                onA = { if (ChildProcessRestrictions.hasSwitch()) openDeveloperOptions() else onNote(restrictionsNote) },
                actions = buildList {
                    if (ChildProcessRestrictions.hasSwitch()) add(MenuAction(L("Open Developer options"), { openDeveloperOptions() }))
                    add(MenuAction(L("Why"), { onNote(restrictionsNote) }))
                },
            ),
        )
        val available = target?.let { r -> L("Available: %1\$s", r.version) + if (r.size > 0) " · " + L("%1\$s MB download", r.size / MB) else "" }
        add(
            MenuEntry(
                "lx-runtime", L("Linux runtime"),
                subtitle = when {
                    rt.busy -> LinuxRuntimeUpdate.line(rt)
                    removing -> L("Removing…")
                    message != null -> message
                    installed != null -> listOfNotNull(L("Installed: %1\$s", installed), available?.takeIf { target?.version != installed }).joinToString(" · ")
                    checking -> L("Checking for a build…")
                    target == null -> L("No build available (could not reach the catalog)")
                    else -> listOfNotNull(L("Not installed"), available).joinToString(" · ")
                },
                trailing = when {
                    rt.busy && rt.percent in 0..100 -> "${rt.percent}%"
                    installed != null -> L("Installed")
                    else -> null
                },
                current = installed != null,
                progress = if (rt.busy) (if (rt.percent in 0..100) rt.percent / 100f else 0f) else null,
                icon = Icons.Filled.Terminal, group = group,
                onA = if (canInstall) ({ install() }) else null,
                play = if (canInstall) ({ install() }) else null,
                playIcon = Icons.Filled.Download, playLabel = if (installed == null) L("Install") else L("Update"),
                actions = buildList {
                    if (canInstall) add(MenuAction(if (installed == null) L("Install") else L("Update"), { install() }))
                    if (!busy && installed != null) add(
                        MenuAction(L("Remove"), { remove() }, danger = true, confirm = L("Remove the Linux runtime? Its Steam entry goes with it.")),
                    )
                    add(MenuAction(L("About the Linux runtime"), { onNote(aboutRuntime) }))
                },
            ),
        )
        if (installed == null) return@buildList
        add(
            MenuEntry(
                "lx-steam", L("\"%1\$s\" is in your library", LinuxShortcuts.STEAM_NAME),
                L("Sign in there with your Steam account; installs and updates are Steam's own."),
                icon = Icons.Filled.SportsEsports, group = group,
            ),
        )

        val pGroup = L("Proton builds")
        add(
            MenuEntry(
                "px-about", L("Proton builds"),
                subtitle = when {
                    loading -> L("Checking for builds…")
                    builds.isEmpty() -> L("No builds available (could not reach the catalog)")
                    used > 0 -> L("Using %1\$s MB", used / MB)
                    else -> L("What the Steam client runs a Windows game with")
                },
                icon = Icons.Filled.Info, group = pGroup,
                onA = { onNote(aboutProtons) },
            ),
        )
        builds.forEach { b ->
            val state = states[b.name] ?: LinuxProtons.State.ABSENT
            val working = workingOn == b.name
            val stateText = when (state) {
                LinuxProtons.State.INSTALLED -> L("Added")
                LinuxProtons.State.PENDING ->
                    if (b.isDepot) L("Requested — the client fetches it next session") else L("Ready — unpacked next time you open the Steam client")
                else -> if (b.isDepot) L("Downloaded by the Steam client") else L("%1\$s MB download", b.size / MB)
            }
            val canGet = workingOn == null && state == LinuxProtons.State.ABSENT
            val getLabel = if (b.isDepot) L("Request") else L("Download")
            add(
                MenuEntry(
                    "px-${b.name}", b.display,
                    subtitle = when {
                        working -> if (percent in 0..100) "$stage $percent%" else stage
                        errorFor == b.name && error != null -> error
                        else -> listOfNotNull(b.notes.takeIf { it.isNotBlank() }, stateText).joinToString(" · ")
                    },
                    trailing = if (working && percent in 0..100) "$percent%" else null,
                    current = state == LinuxProtons.State.INSTALLED,
                    progress = if (working) (if (percent in 0..100) percent / 100f else 0f) else null,
                    icon = Icons.Filled.Layers, group = pGroup,
                    onA = if (canGet) ({ installProton(b) }) else null,
                    play = if (canGet) ({ installProton(b) }) else null,
                    playIcon = Icons.Filled.Download, playLabel = getLabel,
                    actions = buildList {
                        if (canGet) add(MenuAction(getLabel, { installProton(b) }))
                        // Valve's depots belong to the client, which installs and removes them itself.
                        if (!b.isDepot && workingOn == null && state != LinuxProtons.State.ABSENT) {
                            add(MenuAction(L("Remove"), { removeProton(b) }, danger = true, confirm = L("Remove %1\$s?", b.display)))
                        }
                    },
                ),
            )
        }
    }
}
