package com.winlator.star.ui.simple

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.box64.Box64Preset
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.core.EnvVars
import com.winlator.star.core.PresetOverrides
import com.winlator.star.core.PresetScope
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import com.winlator.star.perf.PerfGpuTurbo
import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.perf.TempWatchdog
import com.winlator.star.perf.TempWatchdog.ThresholdMode
import com.winlator.star.ui.screens.PerfCopy
import com.winlator.star.ui.screens.PerfDisclaimerCopy
import com.winlator.star.ui.screens.PresetKind
import com.winlator.star.ui.screens.VarSpec
import com.winlator.star.ui.screens.WATCHDOG_WHATS_THIS
import com.winlator.star.ui.screens.explainAllBody
import com.winlator.star.ui.screens.loadSpecs
import com.winlator.star.ui.screens.modeLabel
import com.winlator.star.ui.screens.presetHelp
import com.winlator.star.ui.screens.presetInfo
import com.winlator.star.ui.screens.rootStateLabel
import com.winlator.star.ui.screens.varHelp
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.widget.HudMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

// Lab's pages for Classic's Performance menu (root controls, free memory, the temperature watchdog and the "?" texts),
// Lossless.dll for LSFG Native, and the Box64 / FEX preset tools with their values editor. Same stores and calls as
// PerformanceSettingsScreen, WatchdogSection, SettingsScreen and PresetEditDialog; the global power defaults (sustained,
// priority, big cores, GPU lock) stay rows of Lab's Settings › Performance tab.

private val perfMain = android.os.Handler(android.os.Looper.getMainLooper())

// ───────────────────────────── Root controls and heat safety ─────────────────────────────

/** Classic Settings › Performance: root grant, root switches, free memory, the temperature watchdog, the explanations. */
@Composable
internal fun LabPerformancePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootState by PerformanceSettings.rootState.collectAsState()
    val harnessProven by PerformanceSettings.harnessProven.collectAsState()
    val granted = rootState == RootManager.RootState.GRANTED
    val rootKeys = listOf(
        PerfRootApplier.KEY_CPU_GOVERNOR to (L("CPU governor → performance") to PerfCopy.CPU_GOV),
        PerfRootApplier.KEY_CPU_FREQ_LOCK to (L("Lock CPU frequency to max") to PerfCopy.CPU_FREQ),
        PerfRootApplier.KEY_CORES_ONLINE to (L("Keep all cores online") to PerfCopy.CORES_ONLINE),
        PerfRootApplier.KEY_THERMAL_DISABLE to (L("Disable thermal throttling") to PerfCopy.THERMAL),
        PerfRootApplier.KEY_FAN_MAX to (L("Fan to maximum") to PerfCopy.FAN),
    )
    val on = (rootKeys.map { it.first } + PerfRootApplier.KEY_AUTO_DEEP_CLEAN).associateWith { k ->
        PerformanceSettings.rootDefaultFlow(k).collectAsState().value
    }
    val wdOn by TempWatchdog.enabled.collectAsState()
    val mode by TempWatchdog.mode.collectAsState()
    val ceiling by TempWatchdog.ceilingC.collectAsState()
    val manual by TempWatchdog.manualCeilingC.collectAsState()
    // Live temperatures while the page is open (the same HudMetrics reads as the in-game readouts).
    var cpuTemp by remember { mutableStateOf<Int?>(null) }
    var gpuTemp by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        val metrics = HudMetrics(context.applicationContext)
        while (true) {
            val c: Int? = withContext(Dispatchers.IO) { runCatching { metrics.cpuTempC }.getOrNull() }
            val g: Int? = withContext(Dispatchers.IO) { runCatching { metrics.gpuTempC }.getOrNull() }
            cpuTemp = c
            gpuTemp = g
            delay(1500)
        }
    }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var grantGate by remember { mutableStateOf(false) }
    var offGate by remember { mutableStateOf(false) }
    fun explain(title: String, body: String) = MenuAction(L("What's this?"), { info = title to L(body) })

    val root = buildList {
        add(MenuEntry(
            "status", L("Root status"), trailing = L(rootStateLabel(rootState)), icon = Icons.Filled.Security,
            subtitle = if (rootState == RootManager.RootState.UNAVAILABLE) L("No root manager detected (Magisk / KernelSU / APatch). Root controls stay disabled.") else null,
        ))
        if (rootState != RootManager.RootState.UNAVAILABLE && !granted) {
            add(MenuEntry(
                "grant", if (rootState == RootManager.RootState.DENIED) L("Grant root (retry)") else L("Grant root"),
                L("Read the warning first."), icon = Icons.Filled.Security, onA = { grantGate = true },
            ))
        }
        rootKeys.forEach { (key, copy) ->
            val gated = PerfRootApplier.isHarnessGated(key) && !harnessProven
            val reason = when {
                !granted -> L("Needs root.")
                gated -> L("Locked until safety-revert is verified on this device.")
                else -> null
            }
            val v = on[key] == true
            add(MenuEntry(
                "root:$key", copy.first, subtitle = reason, toggle = v, group = L("Root performance controls"),
                onA = {
                    if (reason != null) toast(context, reason)
                    else {
                        PerformanceSettings.setRootDefault(key, !v)
                        PerfRootApplier.apply(key, !v)
                    }
                },
                actions = listOf(explain(copy.first, copy.second)),
            ))
        }
        add(MenuEntry(
            "drop", L("Drop file caches"), L("Frees cached files. Little visible RAM; the system reclaims cache automatically."),
            icon = Icons.Filled.Memory, group = L("Free memory"),
            onA = { if (granted) PerfRootApplier.freeMemoryNow() else toast(context, L("Needs root.")) },
            actions = listOf(explain(L("Free memory"), PerfCopy.FREE_MEM_SECTION)),
        ))
        add(MenuEntry(
            "deep", L("Deep clean (free app memory)"), L("Force-closes background apps to free real memory. Won't touch your game or system."),
            icon = Icons.Filled.Memory, group = L("Free memory"),
            onA = { if (granted) PerfRootApplier.deepCleanMemory() else toast(context, L("Needs root.")) },
            actions = listOf(explain(L("Free memory"), PerfCopy.FREE_MEM_SECTION)),
        ))
        val auto = on[PerfRootApplier.KEY_AUTO_DEEP_CLEAN] == true
        add(MenuEntry(
            "autoclean", L("Auto deep-clean on launch"), subtitle = if (granted) null else L("Needs root."), toggle = auto, group = L("Free memory"),
            onA = { if (granted) PerformanceSettings.setRootDefault(PerfRootApplier.KEY_AUTO_DEEP_CLEAN, !auto) else toast(context, L("Needs root.")) },
            actions = listOf(explain(L("Auto deep-clean on launch"), PerfCopy.AUTO_DEEP_CLEAN)),
        ))
        add(MenuEntry("always", L("Undone on game exit, app background and crash"), L("Always on; it cannot be turned off."), icon = Icons.Filled.Restore, group = L("Help")))
        add(MenuEntry("explain", L("Explain toggles"), icon = Icons.Filled.HelpOutline, group = L("Help"),
            onA = { info = L("What the toggles do") to explainAllBody() }))
        if (!granted && !PerfGpuTurbo.isSupported) {
            add(MenuEntry("gpunote", L("Lock GPU to max clock"), L("Needs an Adreno (Qualcomm) GPU, or root on other GPUs."), icon = Icons.Filled.Info, group = L("Help")))
        }
    }

    val top = TempWatchdog.topTripC ?: TempWatchdog.MANUAL_MAX_C
    val sliderMax = minOf(TempWatchdog.MANUAL_MAX_C, top).coerceAtLeast(TempWatchdog.MANUAL_MIN_C + 1)
    val heat = buildList {
        add(MenuEntry(
            "wd", L("Thermal auto-revert"), L("Undoes every performance setting before the device gets too hot."),
            trailing = "~$ceiling °C · " + L(modeLabel(mode)), toggle = wdOn, icon = Icons.Outlined.Thermostat,
            // Turning it off needs the hard warning; turning it on does not.
            onA = { if (wdOn) offGate = true else TempWatchdog.setWatchdogEnabled(true) },
            actions = listOf(MenuAction(L("What's this?"), { info = L("What is the Temperature Watchdog?") to L(WATCHDOG_WHATS_THIS) })),
        ))
        add(MenuEntry(
            "temps", L("Now"), trailing = "CPU ${cpuTemp?.let { "$it °C" } ?: "—"} · GPU ${gpuTemp?.let { "$it °C" } ?: "—"}",
            subtitle = if (TempWatchdog.hasDeviceTrips) L("Your device: throttles ~%1\$s °C · hard limit %2\$s °C", TempWatchdog.firstTripC ?: "—", TempWatchdog.topTripC ?: "—")
            else L("Your device didn't expose thermal limits: using a safe %1\$s °C fallback.", TempWatchdog.FALLBACK_CEILING_C),
            icon = Icons.Filled.Info,
        ))
        ThresholdMode.values().forEach { m ->
            add(MenuEntry(
                "mode:${m.name}", L(modeLabel(m)), trailing = "~${TempWatchdog.resolvedCeilingFor(m)} °C", current = mode == m, group = L("Threshold"),
                onA = {
                    TempWatchdog.setMode(m)
                    // The presets explain themselves, with the °C on this device.
                    if (m != ThresholdMode.MANUAL) info = (L(modeLabel(m)) + " · ~${TempWatchdog.resolvedCeilingFor(m)} °C") to L(presetInfo(m))
                },
            ))
        }
        if (mode == ThresholdMode.MANUAL) {
            val warn = manual > top - 5
            add(MenuEntry(
                "manual", L("Manual temperature"),
                subtitle = if (warn) L("Close to the hard limit (%1\$s °C)", top) else null,
                trailing = "$manual °C" + if (warn) " ⚠" else "", icon = Icons.Filled.Tune, group = L("Threshold"),
                actions = (TempWatchdog.MANUAL_MIN_C..sliderMax).map { c ->
                    MenuAction(labSetMark("$c °C", c == manual), { TempWatchdog.setManualCeilingC(c) }, danger = c > top - 5)
                },
            ))
        }
    }
    SimpleMenuPage(
        overline = L("Settings › Performance"),
        title = L("Root controls and heat safety"),
        sections = listOf(
            MenuSection("root", L("Root and memory"), Icons.Filled.Speed, root),
            MenuSection("heat", L("Temperature watchdog"), Icons.Outlined.Thermostat, heat),
        ),
        onBack = onBack,
    )
    info?.let { (t, b) -> LabSetInfo(t, b) { info = null } }
    if (grantGate) {
        LabSetGate(
            title = L("Power-user performance — read first"), body = PerfDisclaimerCopy.ROOT_RISK,
            checkLabel = L("I understand and accept all risk"), confirmLabel = L("Grant Root"),
            requireScroll = true, danger = true,
            onConfirm = { grantGate = false; scope.launch { RootManager.requestGrant() } },
            onDismiss = { grantGate = false },
        )
    }
    if (offGate) {
        LabSetGate(
            title = L("Disable thermal safety?"), body = PerfDisclaimerCopy.WATCHDOG_OFF,
            checkLabel = L("I understand and accept all risk"), confirmLabel = L("Turn watchdog OFF"),
            requireScroll = true, danger = true,
            onConfirm = { offGate = false; TempWatchdog.setWatchdogEnabled(false) },
            onDismiss = { offGate = false },
        )
    }
}

// ───────────────────────────── Lossless.dll ─────────────────────────────

private const val LSFG_ABOUT =
    "LSFG Native needs a Lossless Scaling \"Lossless.dll\". Download Lossless Scaling from the in-app Steam store and use " +
        "Detect, or import your own copy. Either way it is copied into the app and reused by every container whose frame " +
        "generation engine is LSFG Native."

/**
 * Classic Settings › Frame Generation (LSFG Native): status, Detect from the Steam store, import (in-app or system picker),
 * Remove, and the one-time shader preparation with its status line. Same files and preference as Classic.
 */
@Composable
internal fun LabLosslessPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val dll = remember { File(context.filesDir, "lsfg-vk/Lossless.dll") }
    val building = remember { AtomicBoolean(false) }
    fun ready() = dll.isFile && dll.length() > 0
    fun statusText(): String {
        if (!ready()) return L("Not set: LSFG Native stays off")
        val mb = dll.length() / (1024 * 1024)
        return when (prefs.getString("lsfg_dll_source", null)) {
            "store" -> L("Imported from the Steam store (Lossless Scaling)") + " · " + mb + " MB"
            "manual" -> L("Imported manually") + " · " + mb + " MB"
            else -> L("Imported") + " · " + mb + " MB"
        }
    }
    var status by remember { mutableStateOf(statusText()) }
    var shaders by remember { mutableStateOf("") }
    var about by remember { mutableStateOf(false) }
    // LSFG Native translates the DLL's shader chain once and caches it; doing it here puts the wait where the user acted.
    fun buildCache() {
        if (!ready()) { shaders = ""; return }
        if (!building.compareAndSet(false, true)) return
        val appCtx = context.applicationContext
        shaders = L("Preparing shaders for LSFG Native (one time, can take a minute)")
        Thread({
            val started = System.currentTimeMillis()
            val st = try { com.winlator.star.core.LsfgNative.ensureCache(appCtx, false) } catch (e: Throwable) { -1 }
            val took = (System.currentTimeMillis() - started) / 1000
            val text = when (st) {
                com.winlator.star.core.LsfgNative.STATUS_OK ->
                    if (took >= 2) L("Shaders ready for LSFG Native") + " (" + took + " s)" else L("Shaders ready for LSFG Native")
                -1 -> L("Shader preparation failed; it is retried when a game launches")
                else -> com.winlator.star.core.LsfgNative.explain(st)
            }
            perfMain.post { shaders = text; building.set(false) }
        }, "lsfg-native-cache").start()
    }
    fun importDll(uri: Uri) {
        try {
            dll.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input -> dll.outputStream().use { output -> input.copyTo(output) } }
            prefs.edit().putString("lsfg_dll_source", "manual").apply()
            status = statusText()
            buildCache()
        } catch (e: Exception) {
            status = L("Import failed") + ": " + e.message
        }
    }
    // A Lossless.dll from a Steam-store install (Lossless Scaling lands under imagefs/steam_games/<name>); the newest wins.
    fun detect() {
        val root = File(context.filesDir, "imagefs/steam_games")
        val src = if (!root.isDirectory) null else root.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.name.equals("Lossless.dll", ignoreCase = true) && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
        if (src == null) {
            toast(context, L("No Steam-store Lossless Scaling found: download it from the store or import it manually."))
            return
        }
        try {
            dll.parentFile?.mkdirs()
            src.inputStream().use { input -> dll.outputStream().use { output -> input.copyTo(output) } }
            prefs.edit().putString("lsfg_dll_source", "store").apply()
            status = statusText()
            buildCache()
            toast(context, L("Lossless.dll set from the Steam store install."))
        } catch (e: Exception) {
            status = L("Detect failed") + ": " + e.message
            toast(context, L("Detect failed") + ": " + e.message)
        }
    }
    fun remove() {
        dll.delete()
        com.winlator.star.core.LsfgNative.cacheFile(context).delete()
        prefs.edit().remove("lsfg_dll_source").apply()
        status = statusText()
        shaders = ""
    }
    val inApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importDll(it) }
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importDll(it) } }
    // As Classic on opening Settings: a DLL imported earlier gets its cache built now (nothing to do when it is up to date).
    LaunchedEffect(Unit) { buildCache() }
    val entries = buildList {
        add(MenuEntry("status", L("Lossless.dll"), subtitle = status + if (shaders.isNotEmpty()) "\n" + shaders else "", icon = Icons.Filled.Info,
            onA = { about = true }))
        add(MenuEntry("detect", L("Detect from the Steam store"), L("Download Lossless Scaling in the in-app Steam store first."),
            icon = Icons.Filled.Download, onA = { detect() }))
        add(MenuEntry("import", L("Import Lossless.dll"), L("Pick a Lossless.dll yourself."), icon = Icons.Filled.FileDownload,
            actions = listOf(
                MenuAction(L("Browse files"), { inApp.launch(InAppFilePicker.buildIntent(context, InAppFilePicker.DLL, L("Select Lossless.dll"))) }),
                MenuAction(L("Pick via system…"), { system.launch(arrayOf("*/*")) }),
            )))
        if (dll.isFile) {
            add(MenuEntry("remove", L("Remove Lossless.dll"), L("Deletes the copy and its shader cache; LSFG Native stays off."),
                icon = Icons.Filled.Delete, onA = { remove() }))
        }
        add(MenuEntry("about", L("About LSFG Native"), icon = Icons.Filled.HelpOutline, onA = { about = true }))
    }
    SimpleMenuPage(
        overline = L("Settings › Performance"),
        title = L("Frame generation: LSFG Native"),
        sections = listOf(MenuSection("lsfg", L("Lossless.dll"), Icons.Filled.Speed, entries)),
        onBack = onBack,
    )
    if (about) LabSetInfo(L("Frame generation: LSFG Native"), L(LSFG_ABOUT)) { about = false }
}

// ───────────────────────────── Translator presets ─────────────────────────────

private fun isFex(kind: PresetKind) = kind == PresetKind.FEXCORE
private fun prefKey(kind: PresetKind) = if (isFex(kind)) "fexcore_preset" else "box64_preset"
private fun isCustom(kind: PresetKind, id: String) = id.startsWith(if (isFex(kind)) FEXCorePreset.CUSTOM else Box64Preset.CUSTOM)

/**
 * Classic Settings › Box64 / FEXCore Config: every preset of the shared list. A opens what it can do: use it for new
 * containers, edit its values, duplicate, remove and export (your own presets only); Y makes a new one, and a preset file
 * can be imported (in-app or system picker). The same managers and questions as Classic.
 */
@Composable
internal fun LabPresetsPage(open: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var tick by remember { mutableIntStateOf(0) }
    // Back from the values editor: read the lists again.
    OnResume { tick++ }
    var importKind by remember { mutableStateOf<PresetKind?>(null) }
    fun importFrom(uri: Uri) {
        val kind = importKind ?: return
        importKind = null
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                if (isFex(kind)) FEXCorePresetManager.importPreset(context, input) else Box64PresetManager.importPreset("box64", context, input)
            }
        } catch (_: Exception) { }
        tick++
    }
    val inApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importFrom(it) } else importKind = null
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) importFrom(uri) else importKind = null }

    fun section(kind: PresetKind): MenuSection {
        val fex = isFex(kind)
        val label = if (fex) "FEXCore" else "Box64"
        val presets: List<Pair<String, String>> = runCatching {
            if (fex) FEXCorePresetManager.getPresets(context).map { it.id to it.name }
            else Box64PresetManager.getPresets("box64", context).map { it.id to it.name }
        }.getOrDefault(emptyList())
        val default = prefs.getString(prefKey(kind), if (fex) FEXCorePreset.COMPATIBILITY else Box64Preset.COMPATIBILITY)
        val entries = presets.map { (id, name) ->
            val custom = isCustom(kind, id)
            val edited = !custom && runCatching { PresetOverrides.isCustomised(context, fex, id, PresetScope.GLOBAL, null, null) }.getOrDefault(false)
            MenuEntry(
                "$label:$id", name,
                subtitle = listOfNotNull(
                    if (custom) L("Your preset") else L("Built-in"),
                    if (edited) L("edited") else null,
                    if (id == default) L("used for new containers") else null,
                ).joinToString(" · "),
                current = id == default,
                icon = Icons.Filled.Tune,
                actions = listOfNotNull(
                    MenuAction(labSetMark(L("Use for new containers"), id == default), { prefs.edit().putString(prefKey(kind), id).apply(); tick++ }),
                    MenuAction(L("Edit values"), { open(LabSettingsRoutes.presetEdit(kind, id)) }),
                    MenuAction(L("Duplicate"), {
                        if (fex) FEXCorePresetManager.duplicatePreset(context, id) else Box64PresetManager.duplicatePreset("box64", context, id)
                        tick++
                    }, confirm = context.getString(R.string.do_you_want_to_duplicate_this_preset)),
                    if (custom) MenuAction(L("Export"), {
                        if (fex) FEXCorePresetManager.exportPreset(context, id) else Box64PresetManager.exportPreset("box64", context, id)
                    }) else null,
                    if (custom) MenuAction(L("Remove"), {
                        if (fex) FEXCorePresetManager.removePreset(context, id) else Box64PresetManager.removePreset("box64", context, id)
                        tick++
                    }, danger = true, confirm = context.getString(R.string.do_you_want_to_remove_this_preset)) else null,
                ),
            )
        } + MenuEntry(
            "$label:import", L("Import a preset"), L("A .wbp preset file. It joins the list every container and game picks from."),
            icon = Icons.Filled.FileDownload,
            actions = listOf(
                MenuAction(L("Browse files"), {
                    importKind = kind
                    inApp.launch(InAppFilePicker.buildIntent(context, emptyArray(), L("Select %1\$s preset", label)))
                }),
                MenuAction(L("Pick via system…"), { importKind = kind; system.launch(arrayOf("*/*")) }),
            ),
        )
        return MenuSection(
            kind.name, label, Icons.Filled.Tune, entries,
            primary = MenuAction(L("New %1\$s preset", label), { open(LabSettingsRoutes.presetEdit(kind, null)) }),
        )
    }
    // Read on every tick (a duplicate, an import, a removal, back from the editor).
    val sections = remember(tick) { listOf(PresetKind.BOX64, PresetKind.FEXCORE) }.map { section(it) }
    SimpleMenuPage(
        overline = L("Settings › Performance"),
        title = L("Translator presets"),
        sections = sections,
        onBack = onBack,
    )
}

/**
 * Classic's PresetEditDialog at its App Settings scope, as a Lab page: the name (your own presets only), then every
 * variable of <prefix>_env_vars.json as a switch, a pick list or a number, each with its "?" text. Save (Y) writes it
 * the same way (a built-in preset keeps its edits apart so Reset restores it); B leaves without saving.
 */
@Composable
internal fun LabPresetEditPage(kind: PresetKind, presetId: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    val fex = isFex(kind)
    val prefix = kind.prefix
    val custom = presetId != null && isCustom(kind, presetId)
    val builtIn = presetId != null && !custom
    val specs = remember(prefix) { loadSpecs(context, prefix) }
    // What Save compares with and Reset restores: the values this build ships (none for your own preset).
    val shipped = remember(presetId) {
        if (presetId == null || custom) null else runCatching { PresetOverrides.inheritedBy(context, fex, presetId, PresetScope.GLOBAL, null, null) }.getOrNull()
    }
    val current = remember(presetId) {
        if (presetId == null) null
        else runCatching { if (fex) FEXCorePresetManager.getEnvVars(context, presetId) else Box64PresetManager.getEnvVars(prefix, context, presetId) }.getOrNull()
    }
    val values = remember(presetId, specs) {
        mutableStateMapOf<String, String>().apply {
            specs.forEach { put(it.name, current?.takeIf { c -> c.has(it.name) }?.get(it.name) ?: it.defaultValue) }
        }
    }
    var modified by remember(presetId) {
        mutableStateOf(presetId != null && runCatching { PresetOverrides.isCustomised(context, fex, presetId, PresetScope.GLOBAL, null, null) }.getOrDefault(false))
    }
    val existingName = remember(presetId) {
        presetId?.let { id -> runCatching { if (fex) FEXCorePresetManager.getPreset(context, id)?.name else Box64PresetManager.getPreset(prefix, context, id)?.name }.getOrNull() }
    }
    var name by remember(presetId) {
        mutableStateOf(existingName ?: (context.getString(R.string.preset) + "-" +
            runCatching { if (fex) FEXCorePresetManager.getNextPresetId(context) else Box64PresetManager.getNextPresetId(context, prefix) }.getOrDefault(1)))
    }
    var dirty by remember(presetId) { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf<VarSpec?>(null) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var leave by remember { mutableStateOf(false) }
    val presetHelpText = remember(presetId) { presetHelp(context, prefix, presetId) }
    fun seed(spec: VarSpec) = shipped?.takeIf { it.has(spec.name) }?.get(spec.name) ?: spec.defaultValue
    fun setVar(spec: VarSpec, v: String) {
        if (values[spec.name] != v) { values[spec.name] = v; dirty = true }
    }
    fun save() {
        val clean = name.trim().replace(Regex("[,|]+"), "")
        if (clean.isEmpty()) { toast(context, L("Give the preset a name.")); return }
        val envVars = EnvVars()
        specs.forEach { envVars.put(it.name, values[it.name] ?: it.defaultValue) }
        // A built-in saved with the values this build ships drops its override instead of keeping an identical copy.
        val unchanged = builtIn && specs.all { (values[it.name] ?: it.defaultValue) == seed(it) }
        when {
            unchanged && presetId != null ->
                if (fex) FEXCorePresetManager.resetPreset(context, presetId) else Box64PresetManager.resetPreset(prefix, context, presetId)
            fex -> FEXCorePresetManager.editPreset(context, presetId, clean, envVars)
            else -> Box64PresetManager.editPreset(prefix, context, presetId, clean, envVars)
        }
        toast(context, L("Saved"))
        onBack()
    }
    val helpOf = remember(specs) { specs.associate { it.name to varHelp(context, kind, it.name) } }
    fun helpAction(spec: VarSpec): MenuAction? = helpOf[spec.name]?.let { h -> MenuAction(L("What's this?"), { info = spec.name to h }) }
    val entries = buildList {
        add(MenuEntry(
            "name", L("Name"), subtitle = if (builtIn) L("Built-in names can't be changed.") else null, trailing = name, icon = Icons.Filled.Tune,
            onA = { if (builtIn) toast(context, L("Built-in names can't be changed.")) else naming = true },
        ))
        if (presetHelpText != null) add(MenuEntry("presethelp", L("About this preset"), icon = Icons.Filled.HelpOutline, onA = { info = name to presetHelpText }))
        specs.forEach { spec ->
            val v = values[spec.name] ?: spec.defaultValue
            val help = helpAction(spec)
            add(when (spec.kind) {
                VarSpec.Kind.TOGGLE -> MenuEntry(
                    "var:${spec.name}", spec.name, toggle = v == "1", group = L("Environment variables"),
                    onA = { setVar(spec, if (v == "1") "0" else "1") }, actions = listOfNotNull(help),
                )
                VarSpec.Kind.TEXT -> MenuEntry(
                    "var:${spec.name}", spec.name, trailing = v, group = L("Environment variables"),
                    onA = { typing = spec }, actions = listOfNotNull(help),
                )
                VarSpec.Kind.DROPDOWN -> MenuEntry(
                    "var:${spec.name}", spec.name, trailing = v, group = L("Environment variables"),
                    actions = spec.values.map { o -> MenuAction(labSetMark(o, o == v), { setVar(spec, o) }) } + listOfNotNull(help),
                )
            })
        }
        // Reset only for a built-in that was edited: your own preset has no original to go back to.
        if (builtIn && modified && presetId != null) {
            add(MenuEntry("reset", L("Reset to the original values"), icon = Icons.Filled.Restore, onA = {
                if (fex) FEXCorePresetManager.resetPreset(context, presetId) else Box64PresetManager.resetPreset(prefix, context, presetId)
                specs.forEach { values[it.name] = seed(it) }
                modified = false
                dirty = false
                toast(context, L("Restored"))
            }))
        }
    }
    val title = context.getString(kind.titleRes)
    SimpleMenuPage(
        overline = L("Translator presets"),
        title = if (presetId == null) L("New %1\$s", title) else name,
        banner = when {
            presetId == null -> L("A new preset joins the preset list every container and game picks from.")
            builtIn && modified -> L("Built-in preset, edited. Reset restores the original.")
            builtIn -> L("Built-in preset: edits are saved separately and can be reset.")
            else -> null
        },
        sections = listOf(MenuSection("values", title, Icons.Filled.Tune, entries, primary = MenuAction(L("Save"), { save() }))),
        onBack = { if (dirty) leave = true else onBack() },
    )
    if (naming) {
        LabSetText(L("Name"), name, onDismiss = { naming = false }) { t ->
            naming = false
            if (t != name) { name = t; dirty = true }
        }
    }
    typing?.let { spec ->
        LabSetText(spec.name, values[spec.name] ?: spec.defaultValue, number = true, onDismiss = { typing = null }) { t ->
            typing = null
            setVar(spec, t)
        }
    }
    info?.let { (t, b) -> LabSetInfo(t, b) { info = null } }
    if (leave) {
        SimpleConfirm(L("Leave without saving?"), L("Your changes to this preset are not kept."), danger = true, onDismiss = { leave = false }) {
            leave = false
            onBack()
        }
    }
}
