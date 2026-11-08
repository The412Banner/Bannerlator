package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import com.winlator.star.ui.deck.deckShape
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.core.DirectAudioSupport
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WinePath
import com.winlator.star.ui.components.DllOverrides
import com.winlator.star.ui.components.EnvVarType
import com.winlator.star.ui.components.KnownEnvVars
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.screens.OutlinedAlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

// The Expert page's environment variables, which were the classic editors' Env Vars tab
// (EnvVarsEditor). The storage is the classic one: a single space-separated "NAME=VALUE" string, the
// container form's envVarsStr for a container and All containers, and a game's own "envVars" extra
// (removed when empty), which the launch merges over its container's name by name. So a game lists
// only the variables set on it; the container's are shown under it and can be given a value of the
// game's own. Every edit re-reads the string first and changes only the variable it is about, so the
// keys other rows keep in the same string (the Audio page's BANNER_AUDIO_* latency keys and its
// microphone flag) are never lost: they are listed like any variable, with the setting that owns them.
//
// As in the classic game editor, a Wine game's sync variables (WINEESYNC / WINENTSYNC / WINEFSYNC) are
// not listed: Performance › Sync owns them. A game whose own string still carries them from before that
// row existed keeps them as they are here; the Sync row reads them and clears them when it is set.
// "Prefer game-folder DLLs" is the classic view over WINEDLLOVERRIDES (DllOverrides). Wine games, the
// Linux Steam client entry (whose string is the session's environment), containers and All containers.

internal object EnvVarRows {

    const val ID_ENV = "expert.env"
    private const val ENV = "envVars"

    fun defs(): List<SettingDef> = listOf(
        // The whole string: the container's, or the game's own (never its container's).
        hiddenDef(
            ID_ENV, "expert",
            vm = VmBinding(get = { _, vm -> vm.envVarsStr }, set = { _, vm, v -> vm.envVarsStr = v }),
            game = ownExtra(ENV),
            worlds = WINE_LINUX,
        ),
    )
}

/** One NAME=VALUE of the env string. */
internal data class EnvVar(val name: String, val value: String)

/**
 * The variable a dialog edits. [occ] tells two of one name apart (a hand-typed string can repeat one);
 * [fromContainer] is a container variable this game is about to get its own value of.
 */
internal class EnvEdit(val name: String, val occ: Int, val initial: String, val fromContainer: Boolean = false)

/** EnvVarsEditor's parse: tokens split on spaces, then on the first '='. A token without a name is dropped. */
internal fun parseEnv(raw: String): List<EnvVar> =
    raw.split(" ")
        .filter { it.isNotBlank() }
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else EnvVar(part.substring(0, i), part.substring(i + 1))
        }

/** EnvVarsEditor's render: every named variable, an empty value included ("NAME=" is valid). */
internal fun renderEnv(vars: List<EnvVar>): String =
    vars.filter { it.name.isNotBlank() }.joinToString(" ") { "${it.name}=${it.value}" }

/** The value a freshly added variable starts at (EnvVarsEditor.defaultValueFor). */
private fun envDefault(name: String): String {
    val known = KnownEnvVars.find(name) ?: return ""
    known.defaultValue?.let { return it }
    return when (known.type) {
        EnvVarType.CHECKBOX -> known.options.getOrElse(0) { "0" }
        EnvVarType.SELECT -> known.options.firstOrNull() ?: ""
        else -> ""
    }
}

private val SYNC_VARS = setOf("WINEESYNC", "WINENTSYNC", "WINEFSYNC")

/** The audio cog's own keys (audioConfigToEnv), one set per sound engine. */
private val AUDIO_OWNED = Regex("""^BANNER_AUDIO_(ALSA|PULSE|DIRECT)_(PRESET|PERF|ADAPTIVE|LAT|BF|MBF)$""")

/** The Expert page's variables and "Prefer game-folder DLLs", for a game, a container and All containers. */
internal class DeckEnvVarsSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {
    private val access = DefAccess(source)
    private val game get() = env.scope == EditorScope.GAME
    private val linux get() = env.world == World.LINUX
    /** A Wine game keeps its legacy sync variables out of the list, as the classic game editor does. */
    private val hidesSync get() = game && env.world == World.WINE

    var editing by mutableStateOf<EnvEdit?>(null)
    var adding by mutableStateOf(false)
    var rawText by mutableStateOf(false)
    /** The safe-list DLLs found next to the game's .exe (a game only), read once off the main thread. */
    var foundDlls by mutableStateOf<List<String>>(emptyList())
        private set
    private var scanned = false
    // WINEDLLOVERRIDES as it stood when the page was first shown: what a DLL switch restores when turned off.
    private var dllBaseline: String? = null

    override fun extras(): Map<String, CategoryExtra> {
        if (env.scope == EditorScope.APP || env.world == World.ANDROID || access.def(EnvVarRows.ID_ENV) == null) return emptyMap()
        // Listed from Advanced up, where the classic hand-off was.
        return mapOf("expert" to CategoryExtra(bottom = ::bottomItems, level = SettingLevel.ADVANCED))
    }

    // ── The string ──────────────────────────────────────────────────────────────────────────────

    private fun all(): List<EnvVar> = parseEnv(access.value(EnvVarRows.ID_ENV))

    private fun write(vars: List<EnvVar>) = access.set(EnvVarRows.ID_ENV, renderEnv(vars))

    private fun hidden(v: EnvVar) = hidesSync && v.name in SYNC_VARS

    /** The listed variables with their occurrence number. */
    private fun listed(vars: List<EnvVar>): List<Pair<EnvVar, Int>> {
        val seen = HashMap<String, Int>()
        return vars.mapNotNull { v ->
            val occ = seen[v.name] ?: 0
            seen[v.name] = occ + 1
            if (hidden(v)) null else v to occ
        }
    }

    private fun indexOf(vars: List<EnvVar>, name: String, occ: Int): Int {
        var n = 0
        vars.forEachIndexed { i, v -> if (v.name == name) { if (n == occ) return i; n++ } }
        return -1
    }

    /** EnvVarsEditor's setRow: values can't contain spaces (variables are separated by them). */
    private fun clean(value: String) = value.trim().replace(" ", "")

    fun names(): Set<String> = all().map { it.name }.toSet()

    fun setAt(name: String, occ: Int, value: String) {
        val vars = all().toMutableList()
        val i = indexOf(vars, name, occ)
        if (i < 0) return
        vars[i] = vars[i].copy(value = clean(value))
        write(vars)
    }

    fun removeAt(name: String, occ: Int) {
        val vars = all().toMutableList()
        val i = indexOf(vars, name, occ)
        if (i < 0) return
        vars.removeAt(i)
        write(vars)
    }

    /** EnvVarsEditor's putVar: an empty value removes the variable, otherwise it is updated in place or added. */
    private fun putVar(name: String, value: String) {
        val vars = all().toMutableList()
        val i = vars.indexOfFirst { it.name == name }
        if (value.isEmpty()) { if (i >= 0) vars.removeAt(i) }
        else if (i >= 0) vars[i] = vars[i].copy(value = value)
        else vars.add(EnvVar(name, value))
        write(vars)
    }

    /** Why [name] can't be added, or null. */
    fun addError(name: String): String? = when {
        name.isEmpty() -> null
        hidesSync && name in SYNC_VARS -> L("Performance › Sync sets this one.")
        name in names() -> "$name is already in the list."
        else -> null
    }

    /** Adds [name] and opens it, as the classic add-picker adds a row the user then sets. */
    fun addAndEdit(name: String, value: String) {
        if (name.isEmpty() || addError(name) != null) return
        val v = clean(value)
        write(all() + EnvVar(name, v))
        adding = false
        editing = EnvEdit(name, 0, v)
    }

    /** OK in the variable dialog. A container variable becomes the game's own only here. */
    fun commit(e: EnvEdit, value: String) {
        if (e.fromContainer) {
            if (e.name in names()) setAt(e.name, 0, value) else write(all() + EnvVar(e.name, clean(value)))
        } else {
            setAt(e.name, e.occ, value)
        }
    }

    /** What "Edit as text" starts from: the listed variables. */
    fun rawVisible(): String = renderEnv(listed(all()).map { it.first })

    /** "Edit as text": stored as typed, as the classic text box does, with a Wine game's kept sync variables after it. */
    fun setRaw(text: String) {
        val typed = text.replace('\n', ' ')
        val kept = all().filter { hidden(it) }
        access.set(
            EnvVarRows.ID_ENV,
            if (kept.isEmpty()) typed else (listOf(typed.trim()).filter { it.isNotEmpty() } + renderEnv(kept)).joinToString(" "),
        )
    }

    private fun overrides(): String = all().firstOrNull { it.name == DllOverrides.VAR }?.value ?: ""

    private fun preferDlls(on: Boolean, dlls: List<String>) {
        val cur = overrides()
        putVar(DllOverrides.VAR, if (on) DllOverrides.enable(cur, dlls) else DllOverrides.disable(cur, dllBaseline ?: "", dlls))
    }

    /** The game's folder, scanned for the safe-list DLLs as the classic game editor does. Never applies anything. */
    fun scanGameDir() {
        if (!game || scanned) return
        scanned = true
        val s = env.shortcut ?: return
        scope.launch {
            foundDlls = withContext(Dispatchers.IO) {
                runCatching {
                    val dir: File = WinePath.resolveAndroidPath(s.container, s.path ?: return@runCatching emptyList<String>())?.parentFile
                        ?: return@runCatching emptyList<String>()
                    val present = dir.list()?.map { it.lowercase(Locale.ENGLISH) }?.toSet() ?: emptySet()
                    DllOverrides.PREFER_GAME_FOLDER.filter { "$it.dll" in present }
                }.getOrDefault(emptyList())
            }
        }
    }

    /** The setting that also writes [name], for the line under it. */
    private fun ownerOf(name: String): String? = when {
        AUDIO_OWNED.matches(name) -> L("Set by Audio › Sound latency")
        name == DirectAudioSupport.MIC_ENV_KEY -> L("Set by Audio › Microphone")
        name == DllOverrides.VAR -> L("Also switched by Prefer game-folder DLLs above")
        else -> null
    }

    // ── The page ────────────────────────────────────────────────────────────────────────────────

    private fun bottomItems(): List<PageExtra> = buildList {
        val vars = all()
        val overrides = vars.firstOrNull { it.name == DllOverrides.VAR }?.value ?: ""
        if (dllBaseline == null) dllBaseline = DllOverrides.baselineOf(overrides)
        // The master switch covers the DLLs found next to the .exe, else the whole safe list.
        val found = foundDlls
        val master = found.ifEmpty { DllOverrides.PREFER_GAME_FOLDER }
        val allOn = DllOverrides.isEnabled(overrides, master)
        val anyOn = master.any { DllOverrides.isEnabled(overrides, it) }
        // A Wine game also follows its container's variables; the Linux entry's string is the whole session's.
        val containerVars = if (game && !linux) parseEnv(env.vm?.envVarsStr ?: "").associateBy { it.name } else emptyMap()

        if (linux) {
            add(PageExtra("env-linux") {
                GroupCard(first = true, last = true, highlight = false) {
                    DeckLine(
                        L("One environment for the whole session"),
                        L("These reach the Steam client and every game it launches. A variable here overrides the same name from the FEXCore preset."),
                        icon = Icons.Filled.Info,
                    )
                }
            })
        }

        add(PageExtra("env-compat-label") { GroupLabel(L("Compatibility")) })
        add(PageExtra("env-compat") {
            LaunchedEffect(Unit) { scanGameDir() }
            GroupCard(first = true, last = found.isEmpty(), highlight = false) {
                DeckLine(
                    L("Prefer game-folder DLLs"),
                    hint = (if (linux) L("For the games the client launches, not the client itself, and for every one of them: the session has one environment. ")
                        else "") +
                        L("Loads the game's own ") + DllOverrides.PREFER_GAME_FOLDER.joinToString(", ") +
                        L(" instead of Wine's. Needed by many wrappers and mod loaders. Graphics DLLs are never included: DXVK and VKD3D own those.") +
                        (if (anyOn && !allOn) L(" Some are on: see below.") else ""),
                    icon = Icons.Filled.Extension,
                    onClick = { preferDlls(!allOn, master) },
                ) { Switch(checked = allOn, onCheckedChange = null) }
            }
        })
        // One switch per DLL found in the game's folder: each flips only its own entry.
        found.forEachIndexed { i, dll ->
            add(PageExtra("env-dll:$dll") {
                GroupCard(first = false, last = i == found.lastIndex, highlight = false) {
                    val on = DllOverrides.isEnabled(overrides, dll)
                    DeckLine("$dll.dll", L("Found in the game folder."), onClick = { preferDlls(!on, listOf(dll)) }) {
                        Switch(checked = on, onCheckedChange = null)
                    }
                }
            })
        }

        add(PageExtra("env-vars-label") { GroupLabel(if (game && !linux) L("This game's variables") else L("Environment variables")) })
        val shown = listed(vars)
        if (shown.isEmpty()) {
            add(PageExtra("env-none") {
                GroupCard(first = true, last = false, highlight = false) {
                    DeckLine(
                        L("No environment variables set"),
                        when {
                            linux -> L("The session starts with the app's own variables only.")
                            game -> L("The game starts with its container's variables") + (if (containerVars.isEmpty()) "." else L(", listed below."))
                            env.scope == EditorScope.DEFAULTS -> L("New containers start with none.")
                            else -> L("Every game in this container starts with none of its own.")
                        },
                    )
                }
            })
        }
        shown.forEachIndexed { i, (v, occ) ->
            val notes = listOfNotNull(
                v.value.ifEmpty { L("(empty)") },
                ownerOf(v.name),
                containerVars[v.name]?.let { c -> if (c.value == v.value) L("Same as the container's") else "Replaces the container's ${c.value.ifEmpty { "(empty)" }}" },
            )
            add(PageExtra("env-var:${v.name}#$occ") {
                GroupCard(first = i == 0, last = false, highlight = false) {
                    DeckLine(v.name, notes.joinToString(" · "), icon = Icons.Filled.Tune, onClick = { editing = EnvEdit(v.name, occ, v.value) })
                }
            })
        }
        val kept = vars.filter { hidden(it) }
        if (kept.isNotEmpty()) {
            add(PageExtra("env-sync") {
                GroupCard(first = false, last = false, highlight = false) {
                    DeckLine(
                        L("Sync variables"),
                        renderEnv(kept) + L(" · Kept from before the Sync setting; Performance › Sync reads them and clears them when you set it."),
                        icon = Icons.Filled.Info,
                    )
                }
            })
        }
        add(PageExtra("env-add") {
            GroupCard(first = false, last = false, highlight = false) {
                DeckActionRow(DeckAction("add", Icons.Filled.Add, L("Add a variable"), L("Pick a known variable or type your own: NAME, or NAME=VALUE.")) { adding = true })
            }
        })
        add(PageExtra("env-raw") {
            GroupCard(first = false, last = true, highlight = false) {
                DeckActionRow(DeckAction("raw", Icons.Filled.Edit, L("Edit as text"), L("The whole list as NAME=VALUE, separated by spaces.")) { rawText = true })
            }
        })

        // The container's variables this game doesn't set itself: it starts with them as they are.
        val inherited = containerVars.values.filter { c -> vars.none { it.name == c.name } }
        if (inherited.isNotEmpty()) {
            add(PageExtra("env-container-label") { GroupLabel(L("From the container")) })
            inherited.forEachIndexed { i, c ->
                add(PageExtra("env-container:${c.name}") {
                    GroupCard(first = i == 0, last = i == inherited.lastIndex, highlight = false) {
                        DeckLine(
                            c.name,
                            listOfNotNull(c.value.ifEmpty { L("(empty)") }, ownerOf(c.name)).joinToString(" · ") + L(" · Pick it to give this game its own value."),
                            icon = Icons.Filled.Inventory2,
                            onClick = { editing = EnvEdit(c.name, 0, c.value, fromContainer = true) },
                        )
                    }
                })
            }
        }
    }

    @Composable
    override fun Dialogs() {
        if (extras().isEmpty()) return
        editing?.let { EnvVarDialog(this, it) }
        if (adding) EnvAddDialog(this)
        if (rawText) EnvRawDialog(this)
    }
}

/** One variable, with the control its catalog type calls for. OK writes it; Remove takes it out. */
@Composable
private fun EnvVarDialog(s: DeckEnvVarsSection, e: EnvEdit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val known = remember(e.name) { KnownEnvVars.find(e.name) }
    val type = known?.type ?: EnvVarType.TEXT
    var value by remember(e) { mutableStateOf(e.initial) }
    val help = remember(e.name) { StringUtils.getString(context, "env_var_help__" + e.name.lowercase(Locale.ENGLISH)) }
    val first = remember { FocusRequester() }
    val choices = type == EnvVarType.CHECKBOX || type == EnvVarType.SELECT_MULTIPLE || (type == EnvVarType.SELECT && known?.options?.isNotEmpty() == true)
    LaunchedEffect(Unit) {
        if (!choices) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    fun close() { s.editing = null }
    OutlinedAlertDialog(
        onDismissRequest = { close() },
        title = {
            Column {
                Text(e.name, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (e.fromContainer) {
                    Text(L("From the container. OK gives this game its own value."), fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                help?.let { Text(it, color = cs.onSurfaceVariant, fontSize = 13.5.sp, modifier = Modifier.padding(bottom = 10.dp)) }
                when (type) {
                    EnvVarType.CHECKBOX -> {
                        val off = known?.options?.getOrNull(0) ?: "0"
                        val on = known?.options?.getOrNull(1) ?: "1"
                        val checked = value == on || value == "1" || value == "true"
                        EnvChoiceLine(L("On"), "${e.name}=$on", selected = checked, multi = false, modifier = Modifier.focusRequester(first)) { value = on }
                        EnvChoiceLine(L("Off"), "${e.name}=$off", selected = !checked, multi = false) { value = off }
                    }
                    EnvVarType.SELECT -> {
                        val presets = known?.options.orEmpty().distinct()
                        val opts = if (value.isNotEmpty() && value !in presets) presets + value else presets
                        opts.forEachIndexed { i, o ->
                            EnvChoiceLine(o, null, selected = o == value, multi = false, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) { value = o }
                        }
                        EnvValueField(value, L("Or type any value"), numeric = false) { value = it }
                    }
                    EnvVarType.SELECT_MULTIPLE -> {
                        val selected = value.split(",").filter { it.isNotBlank() }
                        known?.options.orEmpty().distinct().forEachIndexed { i, o ->
                            EnvChoiceLine(o, null, selected = o in selected, multi = true, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                value = (if (o in selected) selected - o else selected + o).joinToString(",")
                            }
                        }
                        EnvValueField(value, L("As text, comma-separated"), numeric = false) { value = it }
                    }
                    EnvVarType.NUMBER, EnvVarType.TEXT -> EnvValueField(value, L("Value"), numeric = type == EnvVarType.NUMBER) { value = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                s.commit(e, value)
                close()
            }) { Text(if (e.fromContainer) L("Set for this game") else "OK") }
        },
        dismissButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!e.fromContainer) {
                    TextButton(onClick = {
                        s.removeAt(e.name, e.occ)
                        close()
                    }) { Text(L("Remove"), color = cs.error) }
                }
                TextButton(onClick = { close() }) { Text(L("Cancel")) }
            }
        },
    )
}

/** The classic add-picker: a typed NAME or NAME=VALUE, or any catalog variable not set yet. */
@Composable
private fun EnvAddDialog(s: DeckEnvVarsSection) {
    val cs = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    val cleaned = text.trim().replace(" ", "")
    val eq = cleaned.indexOf('=')
    val name = if (eq >= 0) cleaned.substring(0, eq) else cleaned
    val error = if (eq == 0) L("Type a name before the =.") else s.addError(name)
    val existing = s.names()
    val candidates = KnownEnvVars.names.filter { it !in existing && (name.isEmpty() || it.contains(name, ignoreCase = true)) }
    OutlinedAlertDialog(
        onDismissRequest = { s.adding = false },
        title = { Text(L("Add a variable")) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = error != null,
                    label = { Text(L("NAME or NAME=VALUE")) },
                    supportingText = { Text(error ?: L("Or pick a known one below. Spaces are dropped.")) },
                    shape = deckShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (candidates.isEmpty()) L("No known variable matches.") else L("Known variables"),
                    color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(candidates, key = { it }) { n ->
                        EnvChoiceLine(n, null, selected = false, multi = null) { s.addAndEdit(n, envDefault(n)) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { s.addAndEdit(name, if (eq >= 0) cleaned.substring(eq + 1) else envDefault(name)) },
                enabled = name.isNotEmpty() && error == null,
            ) { Text(L("Add")) }
        },
        dismissButton = { TextButton(onClick = { s.adding = false }) { Text(L("Cancel")) } },
    )
}

/** "Edit as text": the whole list in one box, stored as typed. */
@Composable
private fun EnvRawDialog(s: DeckEnvVarsSection) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(s.rawVisible()) }
    // Tokens without NAME=VALUE are skipped by the launch; say so while they can still be fixed.
    val malformed = text.replace('\n', ' ').split(" ").filter { it.isNotBlank() && it.indexOf('=') <= 0 }
    OutlinedAlertDialog(
        onDismissRequest = { s.rawText = false },
        title = { Text(L("Edit as text")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(L("NAME=VALUE, separated by spaces")) },
                isError = malformed.isNotEmpty(),
                supportingText = {
                    Text(
                        if (malformed.isEmpty()) L("Values can't contain spaces.")
                        else L("Ignored, needs NAME=VALUE: ") + malformed.joinToString(", ") + L(". Values can't contain spaces."),
                    )
                },
                shape = deckShape(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                s.setRaw(text)
                s.rawText = false
                if (malformed.isNotEmpty()) Toast.makeText(context, L("Saved. Entries without NAME=VALUE do nothing."), Toast.LENGTH_SHORT).show()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { s.rawText = false }) { Text(L("Cancel")) } },
    )
}

/** The value box of the variable dialog. Spaces are dropped as they are typed, and the box says so. */
@Composable
private fun EnvValueField(value: String, label: String, numeric: Boolean, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(it.replace(" ", "")) },
        singleLine = true,
        label = { Text(label) },
        supportingText = { Text(L("Spaces are dropped: variables are separated by spaces.")) },
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
        shape = deckShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

/**
 * A line of a dialog list: the whole line is the focus target and A picks it. [multi] true draws a tick
 * box, false a radio, null nothing (a plain pick).
 */
@Composable
internal fun EnvChoiceLine(label: String, hint: String?, selected: Boolean, multi: Boolean?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = deckShape(10.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .deckFocusRing(shape, scaleTo = 1f)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(start = if (multi == null) 12.dp else 0.dp, end = 8.dp, top = if (multi == null) 10.dp else 0.dp, bottom = if (multi == null) 10.dp else 0.dp),
    ) {
        when (multi) {
            true -> Checkbox(checked = selected, onCheckedChange = null)
            false -> RadioButton(selected = selected, onClick = null)
            null -> {}
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = cs.onSurface, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            hint?.let { Text(it, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
