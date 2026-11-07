package com.winlator.star.ui.deck.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.box64.Box64Preset
import com.winlator.star.box64.Box64PresetManager
import com.winlator.star.container.Container
import com.winlator.star.core.AppUtils
import com.winlator.star.core.EnvVars
import com.winlator.star.core.FileUtils
import com.winlator.star.core.PresetOverrides
import com.winlator.star.core.PresetScope
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineUtils
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.fexcore.FEXCorePresetManager
import com.winlator.star.perf.PerfRootApplier
import com.winlator.star.perf.PerformanceSettings
import com.winlator.star.perf.RootManager
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.deck.deckInset
import com.winlator.star.ui.deck.deckLine
import com.winlator.star.ui.deck.settings.SettingLevel.ADVANCED
import com.winlator.star.ui.deck.settings.SettingLevel.EXPERT
import com.winlator.star.ui.screens.HelpTextDialog
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.PerfDisclaimerCopy
import com.winlator.star.ui.screens.PerfDisclaimerDialog
import com.winlator.star.ui.screens.PresetKind
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale

// The Performance page's remaining classic parts, native: the Custom services list (a row per
// service), editing the values inside a Box64 / WOWBox64 / FEX preset and the preset list's own
// actions, and a game's power profile, GPU clock lock and root switches.
//
// Storage is the classic editors' own. Custom services: the container's startupServices and a game's
// "startupServices" extra (comma-separated raw names; launch reads the game's, else the container's).
// Preset values: PresetOverrides' "presetId|VARS" on the container (box64PresetVars /
// fexcorePresetVars) or the game's extras of the same names. Power and root: one extra per key, kept
// only while it differs from the app-wide default (Settings › Performance), as the classic perf dialog
// writes them.
//
// What lands when: a game's preset values ride its draft like every row (they are shortcut extras,
// and writing the shortcut beside the draft would be overwritten by the next Save). A container's go
// to its file on the dialog's Save, as the classic editor does (PresetOverrides commits them; the form
// has no field for them), and All containers edits the shared preset as App Settings does (the profile
// has no container to keep them on). The preset LIST (new, duplicate, delete, import, export) is
// app-wide and changes on the button press, as in every classic screen.

internal object PerformanceRows {

    const val ID_BOX64_VARS = "performance.box64PresetVars"
    const val ID_FEX_VARS = "performance.fexPresetVars"
    const val ID_SERVICES = "performance.startupServices"
    const val ID_BOX64_PRESET = "performance.box64Preset"
    const val ID_FEX_PRESET = "performance.fexPreset"
    const val ID_SUSTAINED = "performance.power.sustained"
    const val ID_PRIORITY = "performance.power.priorityBoost"
    const val ID_BIG_CORES = "performance.power.bigCores"

    fun rootId(key: String) = "performance.root.$key"

    /** The two root switches that can overheat the device: turned on only through the risk statement. */
    val DANGER_KEYS = listOf(PerfRootApplier.KEY_THERMAL_DISABLE, PerfRootApplier.KEY_FAN_MAX)

    private const val SERVICES_EXTRA = "startupServices"

    fun defs(): List<SettingDef> =
        WineUtils.STARTUP_SERVICES.mapIndexed { i, entry -> serviceRow(i, entry) } +
            listOf(
                rootRow(PerfRootApplier.KEY_GPU_CLOCK_LOCK, "Power", "Lock the GPU to its top clock", ADVANCED,
                    "Adreno: no root needed (KGSL turbo). Other GPUs need root."),
                rootRow(PerfRootApplier.KEY_CPU_GOVERNOR, "Root controls", "CPU governor: performance", EXPERT,
                    "Forces every CPU cluster to the performance governor."),
                rootRow(PerfRootApplier.KEY_CPU_FREQ_LOCK, "Root controls", "Lock CPU frequency to max", EXPERT,
                    "Sets every cluster's minimum frequency to its maximum."),
                rootRow(PerfRootApplier.KEY_CORES_ONLINE, "Root controls", "Keep all cores online", EXPERT,
                    "Stops the kernel taking cores offline."),
            ) +
            DANGER_KEYS.map { k -> hiddenDef(rootId(k), "performance", vm = null, game = appOverride(k), worlds = WINE_LINUX) } +
            listOf(
                hiddenDef(ID_BOX64_VARS, "performance", vm = null, game = ownExtra(PresetOverrides.KEY_BOX64_VARS)),
                hiddenDef(ID_FEX_VARS, "performance", vm = null, game = ownExtra(PresetOverrides.KEY_FEX_VARS), worlds = WINE_LINUX),
            )

    // ── Custom services ─────────────────────────────────────────────────────────────────────────

    private fun parse(csv: String?): Set<String> = WineUtils.parseStartupServicesCsv(csv ?: "")

    /** A set as the stored list, in the classic list's order. */
    private fun csvOf(set: Set<String>): String {
        val known = WineUtils.STARTUP_SERVICES.map { WineUtils.startupServiceRawName(it) }
        return (known.filter { it in set } + set.filter { it !in known }).joinToString(",")
    }

    private fun containerServices(e: GameExtras) = parse(e.container.startupServices)

    /** The game's list with [raw] on or off, or null when that is the container's list (the game then follows it). */
    private fun servicesWith(e: GameExtras, raw: String, on: Boolean): String? {
        val base = e.get(SERVICES_EXTRA)?.let { parse(it) } ?: containerServices(e)
        val next = if (on) base + raw else base - raw
        return if (next == containerServices(e)) null else csvOf(next)
    }

    private fun serviceRow(i: Int, entry: String): SettingDef {
        val raw = WineUtils.startupServiceRawName(entry)
        val label = WineUtils.STARTUP_SERVICE_LABELS.getOrElse(i) { raw }
        return SettingDef(
            id = "performance.service.$raw", category = "performance", group = "Custom services", level = ADVANCED,
            label = label, tech = raw,
            hint = when {
                i == 0 -> "Custom starts with every service off: turn on only what the game needs."
                raw == "winebus" || raw == "winehid" -> "Turning it off can break controllers."
                else -> null
            },
            control = { _, _ -> Control.Toggle },
            vm = VmBinding(
                get = { _, vm -> if (raw in vm.startupServicesEnabled) "1" else "0" },
                set = { _, vm, v ->
                    val next = if (v == "1") vm.startupServicesEnabled + raw else vm.startupServicesEnabled - raw
                    vm.startupServicesEnabled = LinkedHashSet(csvOf(next).split(",").filter { it.isNotEmpty() })
                },
            ),
            game = GameBinding(
                keys = listOf(SERVICES_EXTRA),
                // Only where the game's own list differs from its container's for this service.
                read = { e ->
                    e.get(SERVICES_EXTRA)?.let { own ->
                        val mine = raw in parse(own)
                        if (mine != raw in containerServices(e)) (if (mine) "1" else "0") else null
                    }
                },
                write = { e, v -> e.put(SERVICES_EXTRA, servicesWith(e, raw, v == "1")) },
                reset = { e -> if (e.get(SERVICES_EXTRA) != null) e.put(SERVICES_EXTRA, servicesWith(e, raw, raw in containerServices(e))) },
            ),
            visible = { _, values -> values(ID_SERVICES) == "${Container.STARTUP_SELECTION_CUSTOM}" },
        )
    }

    // ── Power and root: per game, over the app-wide default ──────────────────────────────────────

    /** A per-game switch kept only while it differs from the app's default, as the classic perf dialog saves it. */
    private fun appOverride(key: String) = GameBinding(
        keys = listOf(key),
        read = { e -> e.get(key)?.takeIf { it.isNotEmpty() }?.let { if (it == "1") "1" else "0" } },
        write = { e, v -> e.put(key, if ((v == "1") == rootDefault(key)) null else v) },
        inherit = Inherit.APP,
        inherited = { if (rootDefault(key)) "1" else "0" },
    )

    private fun rootDefault(key: String) = runCatching { PerformanceSettings.rootDefaultValue(key) }.getOrDefault(false)

    private fun rootRow(key: String, group: String, label: String, level: SettingLevel, hint: String) = SettingDef(
        id = rootId(key), category = "performance", group = group, level = level,
        label = label, hint = hint, tech = key,
        control = { _, _ -> Control.Toggle },
        game = appOverride(key),
        visible = { env, _ -> env.scope == EditorScope.GAME },
        worlds = WINE_LINUX,
        note = { _, _ ->
            if (runCatching { PerfRootApplier.isUsable(key) }.getOrDefault(true)) null
            else "Saved for this game and applied at launch once root is granted."
        },
    )
}

// ── The section ─────────────────────────────────────────────────────────────────────────────────

/**
 * The Performance page's lists and actions: preset values and the preset list for a Wine game, a
 * container or All containers (FEX only for the Linux Steam client entry), and a game's power profile
 * and the two overheating-risk root switches. Nothing for app settings or an Android game.
 */
internal class DeckPerformanceSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {
    private val access = DefAccess(source)
    private val context: Context get() = env.context

    /** Who a preset edit belongs to here. */
    internal enum class Owner { GAME, CONTAINER, SHARED }

    val owner: Owner = when {
        env.scope == EditorScope.GAME -> Owner.GAME
        env.scope == EditorScope.CONTAINER && env.vm?.container != null -> Owner.CONTAINER
        else -> Owner.SHARED
    }

    internal class Edit(val kind: PresetKind, val presetId: String?)
    internal class Ask(val title: String, val body: String, val confirm: String, val run: () -> Unit)

    /** Bumped when preset values or the preset list change, so the lines read their state again. */
    var revision by mutableIntStateOf(0)
        private set
    var editing by mutableStateOf<Edit?>(null)
    var ask by mutableStateOf<Ask?>(null)
    var importFor by mutableStateOf<PresetKind?>(null)
    var dangerFor by mutableStateOf<String?>(null)
    var grantGate by mutableStateOf(false)
    /** Set while a picked file is read in; the launchers live in [Dialogs]. */
    private var importing: PresetKind? = null

    /** The preset kinds offered here: Box64 for Wine, FEX on arm64ec Wine and for the Linux Steam client entry. */
    fun kinds(): List<PresetKind> = when {
        env.scope == EditorScope.APP || env.world == World.ANDROID -> emptyList()
        env.world == World.LINUX -> if (env.scope == EditorScope.GAME) listOf(PresetKind.FEXCORE) else emptyList()
        else -> listOfNotNull(PresetKind.BOX64, if (env.arm64ec) PresetKind.FEXCORE else null)
    }

    private val power: Boolean get() = env.scope == EditorScope.GAME && env.world in WINE_LINUX

    fun isFex(kind: PresetKind) = kind == PresetKind.FEXCORE
    fun presetRowId(kind: PresetKind) = if (isFex(kind)) PerformanceRows.ID_FEX_PRESET else PerformanceRows.ID_BOX64_PRESET
    fun varsId(kind: PresetKind) = if (isFex(kind)) PerformanceRows.ID_FEX_VARS else PerformanceRows.ID_BOX64_VARS
    fun selected(kind: PresetKind): String = access.value(presetRowId(kind))

    fun kindLabel(kind: PresetKind) = when {
        isFex(kind) -> "FEX"
        env.arm64ec -> "WOWBox64"
        else -> "Box64"
    }

    fun presetName(kind: PresetKind, id: String): String? = runCatching {
        if (isFex(kind)) FEXCorePresetManager.getPreset(context, id)?.name else Box64PresetManager.getPreset("box64", context, id)?.name
    }.getOrNull()

    fun isCustomPreset(kind: PresetKind, id: String) = id.startsWith(if (isFex(kind)) FEXCorePreset.CUSTOM else Box64Preset.CUSTOM)

    /** The game's own "presetId|VARS" for [presetId], from its draft; null when it follows its container. */
    fun gameLocal(kind: PresetKind, presetId: String): EnvVars? {
        val stored = access.value(varsId(kind))
        val cut = stored.indexOf('|')
        if (cut <= 0 || stored.substring(0, cut) != presetId) return null
        return stored.substring(cut + 1).takeIf { it.isNotEmpty() }?.let { EnvVars(it) }
    }

    private val container: Container? get() = env.shortcut?.container ?: env.vm?.container

    fun customised(kind: PresetKind, presetId: String): Boolean {
        revision
        if (presetId.isEmpty()) return false
        return runCatching {
            when (owner) {
                Owner.GAME -> gameLocal(kind, presetId) != null
                Owner.CONTAINER -> PresetOverrides.isCustomised(context, isFex(kind), presetId, PresetScope.CONTAINER, container, null)
                Owner.SHARED -> PresetOverrides.isCustomised(context, isFex(kind), presetId, PresetScope.GLOBAL, null, null)
            }
        }.getOrDefault(false)
    }

    /** What this scope would follow without its own copy: the container (or shared preset) for a game. */
    fun inherited(kind: PresetKind, presetId: String): EnvVars = when (owner) {
        Owner.GAME -> PresetOverrides.inheritedBy(context, isFex(kind), presetId, PresetScope.SHORTCUT, container, null)
        Owner.CONTAINER -> PresetOverrides.inheritedBy(context, isFex(kind), presetId, PresetScope.CONTAINER, container, null)
        Owner.SHARED -> PresetOverrides.inheritedBy(context, isFex(kind), presetId, PresetScope.GLOBAL, null, null)
    }

    /** The values in effect here: this scope's own copy, else what it follows. */
    fun current(kind: PresetKind, presetId: String): EnvVars = when (owner) {
        Owner.GAME -> gameLocal(kind, presetId) ?: inherited(kind, presetId)
        Owner.CONTAINER -> PresetOverrides.localOf(context, isFex(kind), presetId, PresetScope.CONTAINER, container, null)
            ?: inherited(kind, presetId)
        Owner.SHARED -> PresetOverrides.globalOf(context, isFex(kind), presetId)
    }

    /** Keeps [envVars] as this scope's values for [presetId], or drops its own copy when [unchanged]. */
    fun storeValues(kind: PresetKind, presetId: String, name: String, envVars: EnvVars, unchanged: Boolean) {
        val fex = isFex(kind)
        when (owner) {
            Owner.GAME -> access.set(varsId(kind), if (unchanged) "" else "$presetId|$envVars")
            Owner.CONTAINER ->
                if (unchanged) PresetOverrides.clearLocal(fex, PresetScope.CONTAINER, container, null)
                else PresetOverrides.writeLocal(fex, presetId, PresetScope.CONTAINER, container, null, envVars)
            Owner.SHARED -> when {
                unchanged -> if (fex) FEXCorePresetManager.resetPreset(context, presetId) else Box64PresetManager.resetPreset("box64", context, presetId)
                fex -> FEXCorePresetManager.editPreset(context, presetId, name, envVars)
                else -> Box64PresetManager.editPreset("box64", context, presetId, name, envVars)
            }
        }
        revision++
    }

    fun resetValues(kind: PresetKind, presetId: String) {
        val fex = isFex(kind)
        when (owner) {
            Owner.GAME -> access.set(varsId(kind), "")
            Owner.CONTAINER -> PresetOverrides.clearLocal(fex, PresetScope.CONTAINER, container, null)
            Owner.SHARED -> if (fex) FEXCorePresetManager.resetPreset(context, presetId) else Box64PresetManager.resetPreset("box64", context, presetId)
        }
        revision++
    }

    /** A new preset always joins the shared list; this scope then picks it. */
    fun createPreset(kind: PresetKind, name: String, envVars: EnvVars) {
        val newId = if (isFex(kind)) {
            FEXCorePresetManager.editPreset(context, null, name, envVars)
            FEXCorePresetManager.getPresets(context).lastOrNull()?.id
        } else {
            Box64PresetManager.editPreset("box64", context, null, name, envVars)
            Box64PresetManager.getPresets("box64", context).lastOrNull()?.id
        }
        listChanged()
        newId?.let { access.set(presetRowId(kind), it) }
    }

    /** The shared preset list changed: the container form re-reads it, keeping its picks. */
    fun listChanged() {
        runCatching { env.vm?.reloadPresetLists(context) }
        revision++
    }

    fun duplicate(kind: PresetKind) {
        val id = selected(kind)
        ask = Ask("Duplicate preset", context.getString(R.string.do_you_want_to_duplicate_this_preset), "Duplicate") {
            if (isFex(kind)) FEXCorePresetManager.duplicatePreset(context, id) else Box64PresetManager.duplicatePreset("box64", context, id)
            listChanged()
        }
    }

    fun delete(kind: PresetKind) {
        val id = selected(kind)
        if (!isCustomPreset(kind, id)) {
            AppUtils.showToast(context, R.string.you_cannot_remove_this_preset)
            return
        }
        ask = Ask("Delete preset", context.getString(R.string.do_you_want_to_remove_this_preset), "Delete") {
            if (isFex(kind)) FEXCorePresetManager.removePreset(context, id) else Box64PresetManager.removePreset("box64", context, id)
            listChanged()
        }
    }

    fun export(kind: PresetKind) {
        val id = selected(kind)
        if (!isCustomPreset(kind, id)) {
            AppUtils.showToast(context, "Only your own presets can be exported.")
            return
        }
        if (isFex(kind)) FEXCorePresetManager.exportPreset(context, id) else Box64PresetManager.exportPreset("box64", context, id)
    }

    fun importFrom(uri: Uri) {
        val kind = importing ?: return
        importing = null
        runCatching {
            context.contentResolver.openInputStream(uri).use { input ->
                if (isFex(kind)) FEXCorePresetManager.importPreset(context, input) else Box64PresetManager.importPreset("box64", context, input)
            }
            listChanged()
        }
    }

    fun startImport(kind: PresetKind) { importing = kind }

    // ── Power ────────────────────────────────────────────────────────────────────────────────────

    /** Sets the game's switches as the classic perf dialog's profile chips do. The profile itself is not stored. */
    fun applyProfile(p: String) {
        fun on(id: String, v: Boolean) = access.set(id, if (v) "1" else "0")
        val gpu = PerformanceRows.rootId(PerfRootApplier.KEY_GPU_CLOCK_LOCK)
        val cpuRoot = listOf(PerfRootApplier.KEY_CPU_GOVERNOR, PerfRootApplier.KEY_CPU_FREQ_LOCK, PerfRootApplier.KEY_CORES_ONLINE)
            .map { PerformanceRows.rootId(it) }
        when (p) {
            "Off", "Battery" -> {
                on(PerformanceRows.ID_SUSTAINED, false); on(PerformanceRows.ID_PRIORITY, false); on(PerformanceRows.ID_BIG_CORES, false)
                on(gpu, false); cpuRoot.forEach { on(it, false) }
            }
            "Balanced" -> {
                on(PerformanceRows.ID_SUSTAINED, true); on(PerformanceRows.ID_PRIORITY, false); on(PerformanceRows.ID_BIG_CORES, false)
                on(gpu, false)
            }
            "Performance", "Turbo" -> {
                on(PerformanceRows.ID_SUSTAINED, true); on(PerformanceRows.ID_PRIORITY, true); on(PerformanceRows.ID_BIG_CORES, true)
                on(gpu, true); cpuRoot.forEach { on(it, true) }
            }
        }
    }

    private val powerIds: List<String>
        get() = listOf(PerformanceRows.ID_SUSTAINED, PerformanceRows.ID_PRIORITY, PerformanceRows.ID_BIG_CORES) +
            PerfRootApplier.ROOT_KEYS.map { PerformanceRows.rootId(it) }

    fun powerOverrides(): Int = powerIds.count { id -> access.def(id)?.let { source.origin(it) == Origin.SET_HERE } == true }

    /** Every power and root switch back to the app's default, as the classic dialog's Reset all. */
    fun resetPower() = powerIds.forEach { id -> access.def(id)?.let { source.reset(it) } }

    fun dangerOn(key: String) = access.value(PerformanceRows.rootId(key)) == "1"
    fun dangerSetHere(key: String) = access.def(PerformanceRows.rootId(key))?.let { source.origin(it) == Origin.SET_HERE } == true
    fun setDanger(key: String, on: Boolean) = access.set(PerformanceRows.rootId(key), if (on) "1" else "0")
    fun resetDanger(key: String) { access.def(PerformanceRows.rootId(key))?.let { source.reset(it) } }

    fun requestRoot() = scope.launch { RootManager.requestGrant() }

    // ── Page items ───────────────────────────────────────────────────────────────────────────────

    override fun extras(): Map<String, CategoryExtra> =
        if (kinds().isEmpty() && !power) emptyMap()
        else mapOf("performance" to CategoryExtra(bottom = this::bottomItems))

    private fun bottomItems(): List<PageExtra> = buildList {
        val kinds = kinds()
        if (kinds.isNotEmpty()) {
            add(PageExtra("perf-presets-label") { GroupLabel("Translator preset values") })
            kinds.forEachIndexed { i, kind ->
                add(PageExtra("perf-preset:${kind.name}") {
                    GroupCard(first = i == 0, last = i == kinds.lastIndex, highlight = false) { PresetLines(this@DeckPerformanceSection, kind) }
                })
            }
        }
        if (power) {
            add(PageExtra("perf-power-label") { GroupLabel("Power profile and root") })
            add(PageExtra("perf-power") {
                GroupCard(first = true, last = true, highlight = false) { PowerLines(this@DeckPerformanceSection) }
            })
        }
    }

    @Composable
    override fun Dialogs() {
        val inApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(r.data)?.let { importFrom(it) }
        }
        val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFrom(it) } }

        editing?.let { e -> PresetValuesDialog(this, e) { editing = null } }
        ask?.let { a ->
            OutlinedAlertDialog(
                onDismissRequest = { ask = null },
                confirmButton = { TextButton(onClick = { ask = null; a.run() }) { Text(a.confirm) } },
                dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancel") } },
                title = { Text(a.title) },
                text = { Text(a.body) },
            )
        }
        importFor?.let { kind ->
            OutlinedAlertDialog(
                onDismissRequest = { importFor = null },
                confirmButton = {
                    TextButton(onClick = {
                        importFor = null
                        startImport(kind)
                        inApp.launch(InAppFilePicker.buildIntent(context, emptyArray(), "Select preset"))
                    }) { Text("Browse files") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        importFor = null
                        startImport(kind)
                        system.launch(arrayOf("*/*"))
                    }) { Text("Pick via system…") }
                },
                title = { Text("Import a ${kindLabel(kind)} preset") },
                text = { Text("A .wbp preset file. It joins the preset list every container and game picks from.") },
            )
        }
        dangerFor?.let { key ->
            PerfDisclaimerDialog(
                title = dangerLabel(key), body = PerfDisclaimerCopy.ROOT_RISK, confirmLabel = "Enable",
                onConfirm = { dangerFor = null; setDanger(key, true) },
                onDismiss = { dangerFor = null },
            )
        }
        if (grantGate) {
            PerfDisclaimerDialog(
                title = "Power-user performance — read first", body = PerfDisclaimerCopy.ROOT_RISK, confirmLabel = "Grant Root",
                onConfirm = { grantGate = false; requestRoot() },
                onDismiss = { grantGate = false },
            )
        }
    }
}

private fun dangerLabel(key: String) = if (key == PerfRootApplier.KEY_THERMAL_DISABLE) "Disable thermal throttling" else "Fan to maximum"

// ── Preset lines ────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetLines(s: DeckPerformanceSection, kind: PresetKind) {
    val id = s.selected(kind)
    val label = s.kindLabel(kind)
    val name = s.presetName(kind, id) ?: id.ifEmpty { "No preset" }
    val custom = s.customised(kind, id)
    val where = when (s.owner) {
        DeckPerformanceSection.Owner.GAME -> if (custom) "Customised for this game" else "Follows the container"
        DeckPerformanceSection.Owner.CONTAINER -> if (custom) "Customised for this container" else "Follows the shared preset"
        DeckPerformanceSection.Owner.SHARED -> if (custom) "Shared preset, edited" else "Shared preset"
    }
    DeckLine(
        label = "$label preset values",
        hint = "$name · $where",
        icon = Icons.Filled.Tune,
        onClick = if (id.isEmpty()) null else ({ s.editing = DeckPerformanceSection.Edit(kind, id) }),
        enabled = id.isNotEmpty(),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
        Text(
            "The preset list is shared by every container and game.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckSmallButton("New", Icons.Filled.Add) { s.editing = DeckPerformanceSection.Edit(kind, null) }
            DeckSmallButton("Duplicate", Icons.Filled.ContentCopy, enabled = id.isNotEmpty()) { s.duplicate(kind) }
            DeckSmallButton("Delete", Icons.Filled.Delete, enabled = s.isCustomPreset(kind, id)) { s.delete(kind) }
            DeckSmallButton("Export", Icons.Filled.FileUpload, enabled = s.isCustomPreset(kind, id)) { s.export(kind) }
            DeckSmallButton("Import", Icons.Filled.FileDownload) { s.importFor = kind }
        }
    }
}

// ── Power lines ─────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PowerLines(s: DeckPerformanceSection) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 8.dp)) {
        Text("Power profile", color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Sets the power, GPU and root switches above for this game in one go. The profile itself isn't kept.",
            color = cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Off", "Battery", "Balanced", "Performance", "Turbo").forEach { p -> DeckSmallButton(p) { s.applyProfile(p) } }
        }
    }

    val rootState by RootManager.state.collectAsState()
    val granted = rootState == RootManager.RootState.GRANTED
    DeckLine(
        label = if (granted) "Root granted" else "No root",
        hint = if (granted) "Full CPU and GPU control is unlocked." else "The root switches are saved for this game and applied at launch once root is granted.",
        icon = Icons.Filled.Security,
        trailing = if (!granted && rootState != RootManager.RootState.UNAVAILABLE) ({ DeckSmallButton("Grant root") { s.grantGate = true } }) else null,
    )

    PerformanceRows.DANGER_KEYS.forEach { key ->
        val on = s.dangerOn(key)
        DeckLine(
            label = dangerLabel(key),
            hint = (if (key == PerfRootApplier.KEY_THERMAL_DISABLE) "Root. The temperature watchdog still guards." else "Root. Forces active cooling to full at launch.") +
                if (s.dangerSetHere(key)) " Set for this game." else " App setting.",
            icon = Icons.Filled.Warning,
            error = on,
            trailing = {
                if (s.dangerSetHere(key)) DeckSmallButton("", Icons.Filled.Restore) { s.resetDanger(key) }
                Switch(
                    checked = on,
                    onCheckedChange = { want -> if (want) s.dangerFor = key else s.setDanger(key, false) },
                    modifier = Modifier.deckFocusRing(RoundedCornerShape(20.dp), scaleTo = 1f),
                )
            },
        )
    }

    val count = s.powerOverrides()
    if (count > 0) {
        DeckLine(
            label = "Reset power and root to the app's defaults",
            hint = if (count == 1) "1 switch is set for this game." else "$count switches are set for this game.",
            icon = Icons.Filled.Restore,
            onClick = { s.resetPower() },
        )
    }
}

// ── Preset values dialog ────────────────────────────────────────────────────────────────────────

/** One variable of <prefix>_env_vars.json, as the classic preset editor reads it. */
private class VarSpec(val name: String, val values: List<String>, val kind: Kind, val defaultValue: String) {
    enum class Kind { TOGGLE, DROPDOWN, TEXT }
}

private fun loadSpecs(context: Context, prefix: String): List<VarSpec> = runCatching {
    val data = JSONArray(FileUtils.readString(context, prefix + "_env_vars.json"))
    (0 until data.length()).map { i ->
        val item = data.getJSONObject(i)
        val values = item.optJSONArray("values")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
        // The file spells the flags both ways and sometimes as strings, as PresetEditDialog accepts.
        val toggle = item.optBoolean("toggleSwitch", false) || item.optBoolean("toggleswitch", false) ||
            item.optString("toggleSwitch") == "true" || item.optString("toggleswitch") == "true"
        val text = item.optBoolean("editText", false) || item.optString("editText") == "true"
        VarSpec(
            name = item.getString("name"),
            values = values,
            kind = when {
                toggle -> VarSpec.Kind.TOGGLE
                text -> VarSpec.Kind.TEXT
                else -> VarSpec.Kind.DROPDOWN
            },
            defaultValue = item.optString("defaultValue", values.firstOrNull() ?: ""),
        )
    }
}.getOrDefault(emptyList())

private fun varHelp(context: Context, kind: PresetKind, name: String): String? =
    runCatching { StringUtils.getString(context, kind.prefix + "_env_var_help__" + name.replace(kind.varPrefix, "").lowercase(Locale.ENGLISH)) }.getOrNull()

/**
 * The values inside one preset at this editor's scope (or a new preset's name and values), with the
 * classic editor's rules: Save keeps a copy only where it differs from what the scope follows, and
 * Reset drops this scope's copy.
 */
@Composable
private fun PresetValuesDialog(s: DeckPerformanceSection, edit: DeckPerformanceSection.Edit, onDismiss: () -> Unit) {
    val context = s.env.context
    val cs = MaterialTheme.colorScheme
    val kind = edit.kind
    val presetId = edit.presetId
    val specs = remember(kind) { loadSpecs(context, kind.prefix) }
    val isCustom = presetId != null && s.isCustomPreset(kind, presetId)
    val isBuiltIn = presetId != null && !isCustom
    val scoped = presetId != null && s.owner != DeckPerformanceSection.Owner.SHARED
    // What Reset goes back to and Save compares with; a custom preset has none when it is the shared one.
    val inherited = remember(presetId) {
        if (presetId == null || (!scoped && isCustom)) null else runCatching { s.inherited(kind, presetId) }.getOrNull()
    }
    val current = remember(presetId) { presetId?.let { runCatching { s.current(kind, it) }.getOrNull() } }
    val values = remember(presetId, specs) {
        mutableStateMapOf<String, String>().apply {
            specs.forEach { put(it.name, current?.takeIf { c -> c.has(it.name) }?.get(it.name) ?: it.defaultValue) }
        }
    }
    var modified by remember(presetId) { mutableStateOf(presetId != null && s.customised(kind, presetId)) }
    var name by remember(presetId) {
        mutableStateOf(
            presetId?.let { s.presetName(kind, it) } ?: (context.getString(R.string.preset) + "-" +
                runCatching { if (s.isFex(kind)) FEXCorePresetManager.getNextPresetId(context) else Box64PresetManager.getNextPresetId(context, "box64") }.getOrDefault(1))
        )
    }
    var help by remember { mutableStateOf<String?>(null) }
    help?.let { HelpTextDialog(it) { help = null } }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    fun seed(spec: VarSpec) = inherited?.takeIf { it.has(spec.name) }?.get(spec.name) ?: spec.defaultValue

    val where = when {
        presetId == null -> "A new preset joins the shared list straight away, and this ${if (s.owner == DeckPerformanceSection.Owner.GAME) "game" else "editor"} picks it."
        s.owner == DeckPerformanceSection.Owner.GAME ->
            (if (modified) "Customised for this game. Reset makes it follow its container again. " else "For this game only: no other game or container changes. ") +
                "Kept when you save the editor."
        s.owner == DeckPerformanceSection.Owner.CONTAINER ->
            (if (modified) "Customised for this container. Reset makes it follow the shared preset again. " else "For this container only: the shared preset is left alone. ") +
                "Saved to the container as soon as you press Save here, as in the classic editor."
        isBuiltIn -> "The shared preset every container follows unless it has its own values. Saved as soon as you press Save; Reset restores the original."
        else -> "The shared preset every container follows unless it has its own values. Saved as soon as you press Save."
    }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(if (presetId == null) "New ${s.kindLabel(kind)} preset" else "${s.kindLabel(kind)} preset values")
                Text(where, fontSize = 12.5.sp, color = if (modified) cs.primary else cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    // Built-in names come from the app's strings; a scope's own copy keeps the preset's name.
                    enabled = presetId == null || (isCustom && !scoped),
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Environment variables",
                    color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(specs, key = { it.name }) { spec ->
                        val h = remember(spec.name) { varHelp(context, kind, spec.name) }
                        VarLine(
                            spec = spec,
                            value = values[spec.name] ?: spec.defaultValue,
                            onValue = { values[spec.name] = it },
                            onHelp = h?.let { text -> { help = text } },
                            modifier = if (spec === specs.first()) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clean = name.trim().replace(Regex("[,|]+"), "")
                if (clean.isEmpty()) return@TextButton
                val envVars = EnvVars()
                specs.forEach { envVars.put(it.name, values[it.name] ?: it.defaultValue) }
                if (presetId == null) {
                    s.createPreset(kind, clean, envVars)
                } else {
                    // Compared with what the editor shows for the inherited values, since Save writes every variable.
                    val unchanged = (scoped || isBuiltIn) && specs.all { (values[it.name] ?: it.defaultValue) == seed(it) }
                    s.storeValues(kind, presetId, clean, envVars, unchanged)
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (presetId != null && (scoped || isBuiltIn) && modified) {
                    TextButton(onClick = {
                        s.resetValues(kind, presetId)
                        specs.forEach { values[it.name] = seed(it) }
                        modified = false
                    }) { Text("Reset", color = cs.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** One variable: its name (and "?"), then a switch, a pick list or a number field. */
@Composable
private fun VarLine(spec: VarSpec, value: String, onValue: (String) -> Unit, onHelp: (() -> Unit)?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            spec.name, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = cs.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (onHelp != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(30.dp).deckFocusRing(CircleShape, scaleTo = 1f).clip(CircleShape).clickable(onClick = onHelp),
            ) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "About ${spec.name}", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(Modifier.width(30.dp))
        }
        Spacer(Modifier.width(8.dp))
        when (spec.kind) {
            VarSpec.Kind.TOGGLE -> Switch(
                checked = value == "1",
                onCheckedChange = { onValue(if (it) "1" else "0") },
                modifier = modifier.deckFocusRing(RoundedCornerShape(20.dp), scaleTo = 1f),
            )
            VarSpec.Kind.TEXT -> OutlinedTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = modifier.width(110.dp),
            )
            VarSpec.Kind.DROPDOWN -> {
                var open by remember { mutableStateOf(false) }
                val shape = RoundedCornerShape(12.dp)
                Box {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = modifier
                            .width(130.dp)
                            .deckFocusRing(shape, scaleTo = 1f)
                            .clip(shape)
                            .background(deckInset())
                            .border(1.dp, deckLine(), shape)
                            .clickable { open = true }
                            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Text(value, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        spec.values.forEach { v ->
                            DropdownMenuItem(
                                text = { Text(v) },
                                leadingIcon = {
                                    if (v == value) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary)
                                    else Spacer(Modifier.size(24.dp))
                                },
                                onClick = { open = false; onValue(v) },
                            )
                        }
                    }
                }
            }
        }
    }
}
