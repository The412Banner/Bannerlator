package com.winlator.star.ui.deck.settings

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.container.Container
import com.winlator.star.reshade.ReshadeCatalog
import com.winlator.star.reshade.ReshadeCatalogEntry
import com.winlator.star.reshade.ReshadeDownloader
import com.winlator.star.reshade.ReshadeLoadout
import com.winlator.star.reshade.ReshadeManager
import com.winlator.star.ui.DeckDialogBars
import com.winlator.star.ui.DeckDialogScaled
import com.winlator.star.ui.deck.XmbTapToEdit
import com.winlator.star.ui.deck.xmbDoneActions
import com.winlator.star.ui.deck.deckFocusRing
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.ui.screens.ReshadeLoadoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// The Visual effects page's ReShade loadout (vkBasalt drop-in): every choice of the classic
// ReshadeLoadoutEditor and its catalog sheet. Pick effects from the catalog (installed ones toggle,
// others download on press), order the chain, switch each one, Solo or Stack, and tune each effect's
// uniforms. The loadout is four extras written together, on the container and per game, exactly as
// the classic editors write them: reshadeLoadout (the ordered JSON array), reshadeMode, reshadeParams
// (nested per effect) and reshadeEffect (the first effect, for old readers). A game owns the whole set
// once it carries the loadout or the legacy effect (XServerDisplayActivity.shortcutOwnsReshade); until
// then it follows its container, and here it only gets its own set while that differs from the
// container's.

internal object ReshadeRows {

    /** The four stored strings as one value, never shown: what the section reads and writes. */
    const val ID_LOADOUT = "visuals.reshade.loadout"
    const val ID_MODE = "visuals.reshade.mode"
    const val GROUP = "ReShade effects"

    private const val K_LOADOUT = "reshadeLoadout"
    private const val K_MODE = "reshadeMode"
    private const val K_PARAMS = "reshadeParams"
    private const val K_EFFECT = "reshadeEffect"
    // The in-game master switch, stored on the game once it owns the loadout. Not edited here.
    private const val K_MASTER = "reshadeMasterEnabled"
    private val KEYS = listOf(K_LOADOUT, K_MODE, K_PARAMS, K_EFFECT)

    /** The four extras as stored (null = absent), packed into one string for the hidden row. */
    class Stored(val loadout: String?, val mode: String?, val params: String?, val effect: String?) {
        fun encode(): String = JSONObject().apply {
            loadout?.let { put("loadout", it) }
            mode?.let { put("mode", it) }
            params?.let { put("params", it) }
            effect?.let { put("effect", it) }
        }.toString()

        companion object {
            fun decode(s: String): Stored {
                val o = runCatching { JSONObject(s) }.getOrNull() ?: return Stored(null, null, null, null)
                fun opt(k: String) = if (o.has(k)) o.optString(k) else null
                return Stored(opt("loadout"), opt("mode"), opt("params"), opt("effect"))
            }

            /** What the classic save writes for a loadout (applyFormTo, the game editor's save). */
            fun of(state: ReshadeLoadoutState) =
                Stored(state.loadoutJsonOrNull(), state.mode, state.paramsJsonOrNull(), state.firstEffectName())

            fun ofContainer(c: Container) =
                Stored(c.getReshadeLoadout().ifEmpty { null }, c.getReshadeMode(), c.getReshadeParams().ifEmpty { null }, c.getReshadeEffect())
        }
    }

    /** [into] loaded from stored strings, as the classic editors load it (legacy single effect migrated). */
    fun load(into: ReshadeLoadoutState, effects: List<ReshadeManager.ReshadeEffect>, st: Stored) =
        into.init(effects, st.loadout?.ifEmpty { null }, st.mode, st.params?.ifEmpty { null }, st.effect)

    private fun stateOf(effects: List<ReshadeManager.ReshadeEffect>, st: Stored) = ReshadeLoadoutState().also { load(it, effects, st) }
    private fun normalized(effects: List<ReshadeManager.ReshadeEffect>, st: Stored) = Stored.of(stateOf(effects, st)).encode()

    private fun owns(e: GameExtras) = e.get(K_LOADOUT) != null || e.get(K_EFFECT) != null
    private fun ownStored(e: GameExtras) = Stored(e.get(K_LOADOUT), e.get(K_MODE), e.get(K_PARAMS), e.get(K_EFFECT))
    private fun current(e: GameExtras) = if (owns(e)) ownStored(e) else Stored.ofContainer(e.container)

    /**
     * The four keys written together, as the classic game editor's save does. A loadout equal to the
     * container's is dropped instead, so the game follows the container again (unless the game keeps
     * its own in-game master switch, which only applies while the game owns the loadout).
     */
    private fun writeGame(e: GameExtras, st: Stored) {
        val effects = ReshadeEffectsCache.get(e.env.context)
        if (e.get(K_MASTER) == null && normalized(effects, st) == normalized(effects, Stored.ofContainer(e.container))) {
            KEYS.forEach { e.put(it, null) }
        } else {
            e.put(K_LOADOUT, st.loadout)
            e.put(K_MODE, st.mode)
            e.put(K_PARAMS, st.params)
            e.put(K_EFFECT, st.effect)
        }
    }

    private fun mutate(e: GameExtras, block: (ReshadeLoadoutState) -> Unit) {
        val s = stateOf(ReshadeEffectsCache.get(e.env.context), current(e))
        block(s)
        writeGame(e, Stored.of(s))
    }

    /** Whether a packed loadout names any effect. */
    fun hasEffects(packed: String): Boolean =
        Stored.decode(packed).let { ReshadeLoadout.parse(it.loadout, it.effect).isNotEmpty() }

    fun defs(): List<SettingDef> = listOf(
        hiddenDef(
            ID_LOADOUT, "visuals",
            // The container form's ReshadeLoadoutState is the draft itself; the unsaved check serializes it.
            vm = VmBinding(
                get = { _, vm -> Stored.of(vm.reshadeLoadout).encode() },
                set = { _, vm, v -> load(vm.reshadeLoadout, vm.reshadeEffects, Stored.decode(v)) },
            ),
            game = GameBinding(
                keys = KEYS,
                read = { e -> if (owns(e)) ownStored(e).encode() else null },
                write = { e, v -> writeGame(e, Stored.decode(v)) },
            ),
        ),
        SettingDef(
            id = ID_MODE, category = "visuals", group = GROUP, level = SettingLevel.SIMPLE,
            label = "How effects combine", hint = "Solo runs one effect at a time, to compare them. Stack layers every effect that is on.",
            tech = K_MODE,
            control = { _, _ -> Control.Segmented(listOf(Opt(ReshadeLoadout.MODE_SOLO, "Solo"), Opt(ReshadeLoadout.MODE_STACK, "Stack"))) },
            vm = VmBinding(get = { _, vm -> vm.reshadeLoadout.mode }, set = { _, vm, v -> vm.reshadeLoadout.changeMode(v) }),
            game = GameBinding(
                keys = KEYS,
                read = { e -> if (owns(e)) ReshadeLoadout.normalizeMode(e.get(K_MODE)) else null },
                write = { e, v -> mutate(e) { it.changeMode(v) } },
                reset = { e ->
                    if (owns(e)) {
                        val theirs = ReshadeLoadout.normalizeMode(e.container.getReshadeMode())
                        mutate(e) { it.changeMode(theirs) }
                    }
                },
            ),
            visible = { env, values -> env.scope != EditorScope.APP && hasEffects(values(ID_LOADOUT)) },
        ),
    )
}

/**
 * The effects in the app's ReShade folder, with their reflected uniforms. Scanned once (it parses every
 * .fx) and again after a download or delete; [revision] tells the pages a rescan happened.
 */
internal object ReshadeEffectsCache {
    @Volatile
    private var effects: List<ReshadeManager.ReshadeEffect>? = null
    var revision by mutableIntStateOf(0)
        private set

    fun get(context: Context): List<ReshadeManager.ReshadeEffect> =
        effects ?: runCatching { ReshadeManager.scanEffects(context).toList() }.getOrDefault(emptyList()).also { effects = it }

    fun rescan(context: Context) {
        effects = null
        get(context)
        revision++
    }
}

/**
 * The loadout list on the Visual effects page, for a Wine game, a container and All containers. A
 * container edits its form's ReshadeLoadoutState directly; a game edits a copy kept in step with its
 * draft (the hidden row). Downloads and deletes run only from the catalog dialog's own presses.
 */
internal class DeckReshadeSection(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {
    private val access = DefAccess(source)
    private val game = env.scope == EditorScope.GAME
    private val applies = env.world == World.WINE && env.scope != EditorScope.APP && access.def(ReshadeRows.ID_LOADOUT) != null

    // A game's loadout, loaded again whenever its stored value changes under it (Reset, Discard, a save).
    private val gameState = ReshadeLoadoutState()
    private var seen: String? = null

    var catalogOpen by mutableStateOf(false)
    var tuning by mutableStateOf<String?>(null)
    var catalog by mutableStateOf<List<ReshadeCatalogEntry>>(emptyList())
        private set
    var catalogSource by mutableStateOf(ReshadeCatalog.Source.NONE)
        private set
    var catalogLoading by mutableStateOf(false)
        private set
    var installed by mutableStateOf<Set<String>>(emptySet())
        private set
    var downloadingId by mutableStateOf<String?>(null)
        private set
    var phase by mutableStateOf("")
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
    var pendingDelete by mutableStateOf<ReshadeCatalogEntry?>(null)

    fun effects(): List<ReshadeManager.ReshadeEffect> {
        ReshadeEffectsCache.revision
        val vm = env.vm
        return if (!game && vm != null) vm.reshadeEffects else ReshadeEffectsCache.get(env.context)
    }

    fun state(): ReshadeLoadoutState {
        val vm = env.vm
        if (!game && vm != null) return vm.reshadeLoadout
        val v = access.value(ReshadeRows.ID_LOADOUT)
        if (v != seen) {
            seen = v
            ReshadeRows.load(gameState, effects(), ReshadeRows.Stored.decode(v))
        }
        return gameState
    }

    /** Changes the loadout; a game's change goes into its draft as the four extras. */
    fun edit(block: (ReshadeLoadoutState) -> Unit) {
        val s = state()
        block(s)
        if (game) {
            val v = ReshadeRows.Stored.of(s).encode()
            seen = v
            access.set(ReshadeRows.ID_LOADOUT, v)
        }
    }

    /** DXVK and VEGAS only: ReShade is a Vulkan layer. */
    fun supported(): Boolean = access.value(GraphicsRows.ID_WRAPPER).let { it.contains("dxvk") || it.contains("vegas") }

    fun setHere(): Boolean = access.def(ReshadeRows.ID_LOADOUT)?.let { source.origin(it) == Origin.SET_HERE } == true

    fun resetToInherited() {
        access.def(ReshadeRows.ID_LOADOUT)?.let { source.reset(it) }
        seen = null
    }

    fun toggle(name: String, add: Boolean) {
        if (add) effects().firstOrNull { it.name == name }?.let { eff -> edit { it.add(eff, null) } }
        else edit { it.remove(name) }
    }

    fun move(name: String, delta: Int) = edit { s ->
        val i = s.order.indexOf(name)
        val j = i + delta
        if (i >= 0 && j in s.order.indices) {
            s.order.removeAt(i)
            s.order.add(j, name)
        }
    }

    fun clearAll() = edit { s -> s.order.toList().forEach { s.remove(it) } }

    /** Each uniform of [effect] back to its .fx default. */
    fun resetParams(effect: ReshadeManager.ReshadeEffect) = edit { s ->
        effect.params.forEach { p ->
            val tmp = HashMap<String, Float>()
            ReshadeManager.seedValues(p, null, tmp)
            tmp.forEach { (k, v) -> s.setParam(effect.name, k, v) }
        }
    }

    /** The folder changed: read the effects again and drop the ones that are gone from the loadout. */
    private fun rescan() {
        ReshadeEffectsCache.rescan(env.context)
        val vm = env.vm
        when {
            !game && vm != null -> vm.rescanReshadeEffects()
            // A game that follows its container keeps following it: only its own loadout is reconciled.
            setHere() -> edit { it.reconcile(effects()) }
            else -> seen = null
        }
    }

    fun openCatalog() {
        catalogOpen = true
        error = null
        catalogLoading = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ReshadeCatalog.loadCached(env.context) to
                    runCatching { ReshadeManager.scanEffectNames(env.context).toSet() }.getOrDefault(emptySet())
            }
            catalog = r.first.entries
            catalogSource = r.first.source
            installed = r.second
            catalogLoading = false
        }
    }

    fun download(entry: ReshadeCatalogEntry) {
        if (downloadingId != null) return
        downloadingId = entry.id
        phase = "Downloading"
        progress = 0f
        error = null
        val main = Handler(Looper.getMainLooper())
        scope.launch {
            val ok = runCatching {
                ReshadeDownloader.install(env.context, entry) { p, f ->
                    main.post {
                        phase = if (p == ReshadeDownloader.Phase.EXTRACT) "Installing" else "Downloading"
                        progress = f
                    }
                }
            }.getOrDefault(false)
            downloadingId = null
            if (ok) {
                installed = installed + entry.id
                rescan()
                // A freshly installed effect joins the loadout, as in the classic sheet.
                toggle(entry.id, true)
            } else {
                error = "Couldn't download ${entry.name}."
            }
        }
    }

    fun delete(entry: ReshadeCatalogEntry) {
        pendingDelete = null
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ReshadeManager.deleteEffect(env.context, entry.id) }.getOrDefault(false) }
            if (ok) {
                installed = installed - entry.id
                toggle(entry.id, false)
                rescan()
            } else {
                error = "Couldn't delete ${entry.name}."
            }
        }
    }

    override fun extras(): Map<String, CategoryExtra> =
        if (!applies) emptyMap() else mapOf("visuals" to CategoryExtra(bottom = ::bottomItems))

    override fun onReturn() {
        seen = null
    }

    // The loadout card and one line per effect, in one group card. With effects listed, the mode row
    // above already carries the group's label.
    private fun bottomItems(): List<PageExtra> {
        val names = state().order.toList()
        val params = effects().associate { it.name to it.params }
        val out = mutableListOf<PageExtra>()
        if (names.isEmpty()) out += PageExtra("reshade-label") { GroupLabel(ReshadeRows.GROUP) }
        out += PageExtra("reshade-loadout") {
            GroupCard(first = true, last = names.isEmpty(), highlight = false) { LoadoutCard(this@DeckReshadeSection, names) }
        }
        names.forEachIndexed { i, name ->
            out += PageExtra("reshade-fx:$name") {
                GroupCard(first = false, last = i == names.lastIndex, highlight = false) {
                    EffectLine(this@DeckReshadeSection, name, i, names.size, params[name].orEmpty().size)
                }
            }
        }
        return out
    }

    @Composable
    override fun Dialogs() {
        if (!applies) return
        if (catalogOpen) CatalogDialog(this)
        pendingDelete?.let { entry ->
            OutlinedAlertDialog(
                onDismissRequest = { pendingDelete = null },
                confirmButton = { TextButton(onClick = { delete(entry) }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
                title = { Text("Delete ${entry.name}?") },
                text = { Text("This removes the downloaded effect from storage and takes it out of this loadout. You can download it again any time.") },
            )
        }
        tuning?.let { name ->
            val effect = effects().firstOrNull { it.name == name }
            if (effect == null || name !in state().order) {
                LaunchedEffect(name) { tuning = null }
            } else {
                TuneDialog(this, effect)
            }
        }
    }
}

// ── Page items ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LoadoutCard(sec: DeckReshadeSection, names: List<String>) {
    val cs = MaterialTheme.colorScheme
    DeckLine(
        label = "Effects",
        hint = if (names.isEmpty()) "None. Browse the catalog to add some."
               else "${names.size} effect${if (names.size == 1) "" else "s"}, run top to bottom: ${names.joinToString(", ")}",
        icon = Icons.Filled.AutoAwesome,
        trailing = { DeckSmallButton("Browse", Icons.Filled.CloudDownload) { sec.openCatalog() } },
    )
    val notes = buildList {
        if (names.isNotEmpty() && !sec.supported()) add("ReShade only applies to DXVK/VKD3D (Vulkan) games; it has no effect with this DirectX translation." to true)
        if (names.size > 6) add("${names.size} effects: the game takes longer to compile them at start." to false)
    }
    notes.forEach { (text, bad) ->
        Text(
            text,
            color = if (bad) cs.error else cs.onSurfaceVariant,
            fontSize = 12.5.sp,
            modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
        )
    }
    if (sec.setHere()) {
        DeckLine(
            label = if (sec.env.scope == EditorScope.GAME) "Set for this game" else "Set for this container",
            hint = if (sec.env.scope == EditorScope.GAME) "Reset follows the container's effects again." else "Reset goes back to the All containers effects.",
            trailing = { DeckSmallButton("Reset", Icons.Filled.Restore) { sec.resetToInherited() } },
        )
    }
}

@Composable
private fun EffectLine(sec: DeckReshadeSection, name: String, index: Int, count: Int, paramCount: Int) {
    val s = sec.state()
    val on = s.isEnabled(name)
    val solo = s.mode == ReshadeLoadout.MODE_SOLO
    val status = when {
        solo && on -> "Active"
        solo -> "Off (Solo runs one at a time)"
        on -> "On"
        else -> "Off"
    }
    DeckLine(
        label = name,
        hint = status + if (paramCount > 0) " · $paramCount setting${if (paramCount == 1) "" else "s"}" else "",
        trailing = {
            Switch(
                checked = on,
                onCheckedChange = { v -> sec.edit { it.setEnabled(name, v) } },
                modifier = Modifier.deckFocusRing(RoundedCornerShape(20.dp), scaleTo = 1f),
            )
            DeckSmallButton("", Icons.Filled.ArrowUpward, enabled = index > 0) { sec.move(name, -1) }
            DeckSmallButton("", Icons.Filled.ArrowDownward, enabled = index < count - 1) { sec.move(name, 1) }
            DeckSmallButton("Tune", Icons.Filled.Tune, enabled = paramCount > 0) { sec.tuning = name }
            DeckSmallButton("", Icons.Filled.Close) { sec.edit { it.remove(name) } }
        },
    )
}

// ── The catalog ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun CatalogDialog(sec: DeckReshadeSection) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val selected = sec.state().order.map { it.lowercase() }.toSet()
    val offline = sec.catalogSource != ReshadeCatalog.Source.NETWORK
    // Catalog entries plus effects dropped into the folder by hand, filtered, Installed first, A to Z.
    val groups = remember(sec.catalog, sec.installed, query) {
        val q = query.trim()
        val ids = sec.catalog.map { it.id }.toSet()
        val dropped = sec.installed.filter { it !in ids }.map { ReshadeCatalogEntry(it, it, "", "Installed", "", "", "", 0L, "", 1) }
        val all = (sec.catalog + dropped).filter { e ->
            q.isEmpty() || e.name.contains(q, true) || e.author.contains(q, true) || e.category.contains(q, true)
        }
        val byName = compareBy<ReshadeCatalogEntry> { it.name.lowercase() }
        all.filter { it.id in sec.installed }.sortedWith(byName) to all.filter { it.id !in sec.installed }.sortedWith(byName)
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(sec.catalogLoading) {
        if (!sec.catalogLoading) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    fun click(entry: ReshadeCatalogEntry) {
        val isInstalled = entry.id in sec.installed
        when {
            isInstalled -> sec.toggle(entry.id, entry.id.lowercase() !in selected)
            sec.downloadingId != null -> {}
            offline -> sec.error = "Connect to the internet to download effects."
            else -> sec.download(entry)
        }
    }
    Dialog(onDismissRequest = { sec.catalogOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DeckDialogBars()
        DeckDialogScaled {
            Surface(shape = RoundedCornerShape(22.dp), color = cs.surface, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.92f)) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ReShade effects", color = cs.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                        if (selected.isNotEmpty()) DeckSmallButton("Clear all") { sec.clearAll() }
                        DeckSmallButton("Done", Icons.Filled.Check) { sec.catalogOpen = false }
                    }
                    if (offline && !sec.catalogLoading) {
                        Text(
                            if (sec.catalogSource == ReshadeCatalog.Source.CACHE) "Offline: showing a saved list. Connect to download new effects."
                            else "Offline: showing installed effects only. Connect to browse the catalog.",
                            color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    XmbTapToEdit(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp)) { fieldModifier, stopEditing ->
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text("Search effects…") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            shape = RoundedCornerShape(14.dp),
                            modifier = fieldModifier,
                            keyboardActions = xmbDoneActions(stopEditing),
                        )
                    }
                    sec.error?.let { Text(it, color = cs.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(8.dp))
                    if (sec.catalogLoading) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator() }
                    } else {
                        val (installedRows, availableRows) = groups
                        val firstId = (installedRows + availableRows).firstOrNull()?.id
                        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (installedRows.isNotEmpty()) item("hdr-installed") { GroupLabel("Installed (${installedRows.size})") }
                            items(installedRows, key = { "i_${it.id}" }) { entry ->
                                CatalogLine(
                                    sec, entry, installed = true, selected = entry.id.lowercase() in selected, offline = offline,
                                    modifier = if (entry.id == firstId) Modifier.focusRequester(first) else Modifier,
                                    onClick = { click(entry) },
                                )
                            }
                            if (availableRows.isNotEmpty()) item("hdr-available") { GroupLabel("Available (${availableRows.size})") }
                            items(availableRows, key = { "a_${it.id}" }) { entry ->
                                CatalogLine(
                                    sec, entry, installed = false, selected = false, offline = offline,
                                    modifier = if (entry.id == firstId) Modifier.focusRequester(first) else Modifier,
                                    onClick = { click(entry) },
                                )
                            }
                            if (installedRows.isEmpty() && availableRows.isEmpty()) item("empty") {
                                Text(
                                    if (query.isNotBlank()) "No effects match \"$query\"." else "No effects available. Connect to the internet to browse the catalog.",
                                    color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(24.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One catalog entry: A toggles an installed effect in the loadout, or downloads one that isn't installed. */
@Composable
private fun CatalogLine(
    sec: DeckReshadeSection,
    entry: ReshadeCatalogEntry,
    installed: Boolean,
    selected: Boolean,
    offline: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val busy = sec.downloadingId == entry.id
    val alpha = if (installed || busy) 1f else 0.6f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f)
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, color = cs.onSurface.copy(alpha = alpha), fontWeight = FontWeight.Bold, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOf(entry.category, entry.author, entry.license).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = cs.onSurfaceVariant.copy(alpha = alpha), fontSize = 12.5.sp)
                if (entry.description.isNotBlank()) Text(entry.description, color = cs.onSurfaceVariant.copy(alpha = alpha), fontSize = 12.5.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!installed && offline && !busy) Text("Needs a connection to download", color = cs.onSurfaceVariant.copy(alpha = alpha), fontSize = 12.5.sp)
            }
            Spacer(Modifier.width(10.dp))
            when {
                busy -> {}
                installed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DeckSmallButton("", Icons.Filled.Delete) { sec.pendingDelete = entry }
                    Checkbox(checked = selected, onCheckedChange = null)
                }
                offline -> Icon(Icons.Filled.CloudOff, contentDescription = "Offline", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                else -> Icon(Icons.Filled.CloudDownload, contentDescription = "Download", tint = cs.primary, modifier = Modifier.size(22.dp))
            }
        }
        if (busy) {
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sec.phase, color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                Text("${(sec.progress.coerceIn(0f, 1f) * 100).toInt()}%", color = cs.onSurfaceVariant, fontSize = 12.5.sp)
            }
            Spacer(Modifier.height(3.dp))
            LinearProgressIndicator(progress = sec.progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(4.dp))
        }
    }
}

// ── Tuning one effect ───────────────────────────────────────────────────────────────────────────

@Composable
private fun TuneDialog(sec: DeckReshadeSection, effect: ReshadeManager.ReshadeEffect) {
    val cs = MaterialTheme.colorScheme
    val first = remember { FocusRequester() }
    LaunchedEffect(effect.name) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Dialog(onDismissRequest = { sec.tuning = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DeckDialogBars()
        DeckDialogScaled {
            Surface(shape = RoundedCornerShape(22.dp), color = cs.surface, modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.88f)) {
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(effect.name, color = cs.onSurface, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        DeckSmallButton("Reset", Icons.Filled.Restore) { sec.resetParams(effect) }
                        Box(modifier = Modifier.focusRequester(first)) {
                            DeckSmallButton("Done", Icons.Filled.Check) { sec.tuning = null }
                        }
                    }
                    Text("Reset puts every value back to the effect's own default.", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(effect.params, key = { it.name }) { p -> ParamControl(sec, effect.name, p) }
                    }
                }
            }
        }
    }
}

/** One reflected uniform: a switch, a pick of its items, colour components or a slider with steps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamControl(sec: DeckReshadeSection, effect: String, p: ReshadeManager.ReshadeParam) {
    val cs = MaterialTheme.colorScheme
    val s = sec.state()
    val value = s.paramValue(effect, p.name, p.defaultValue)
    when (p.type) {
        ReshadeManager.ParamType.BOOL -> DeckLine(
            label = p.label,
            trailing = {
                Switch(
                    checked = value >= 0.5f,
                    onCheckedChange = { on -> sec.edit { it.setParam(effect, p.name, if (on) 1f else 0f) } },
                    modifier = Modifier.deckFocusRing(RoundedCornerShape(20.dp), scaleTo = 1f),
                )
            },
        )
        ReshadeManager.ParamType.COMBO -> Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(p.label, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (p.options ?: emptyList()).forEachIndexed { i, o ->
                    DeckSmallButton(o, if (i == value.toInt()) Icons.Filled.Check else null) {
                        sec.edit { it.setParam(effect, p.name, i.toFloat()) }
                    }
                }
            }
        }
        ReshadeManager.ParamType.COLOR -> Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(p.label, color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.5.sp, modifier = Modifier.padding(horizontal = 18.dp))
            val names = listOf("Red", "Green", "Blue", "Alpha")
            for (c in 0 until p.components) {
                val k = "${p.name}_$c"
                val cv = s.paramValue(effect, k, p.componentDefaults?.getOrNull(c) ?: 0f)
                SliderLine(names.getOrElse(c) { "$c" }, cv, 0f, 1f, 0.01f, false) { v -> sec.edit { it.setParam(effect, k, v) } }
            }
        }
        else -> {
            val int = p.type == ReshadeManager.ParamType.INT
            val step = when {
                p.step > 0f -> p.step
                int -> 1f
                else -> ((p.max - p.min) / 100f).takeIf { it > 0f } ?: 0.01f
            }
            SliderLine(p.label, value, p.min, p.max, step, int) { v -> sec.edit { it.setParam(effect, p.name, v) } }
        }
    }
}

/** A slider with − and + steps for the controller, and its value. */
@Composable
private fun SliderLine(label: String, value: Float, min: Float, max: Float, step: Float, int: Boolean, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val lo = minOf(min, max)
    val hi = maxOf(min, max)
    val shown = value.coerceIn(lo, hi)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(if (int) shown.toInt().toString() else "%.2f".format(shown), color = cs.onSurface, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeckSmallButton("", Icons.Filled.Remove, enabled = shown > lo) { onChange((shown - step).coerceIn(lo, hi)) }
            Slider(
                value = shown,
                onValueChange = onChange,
                valueRange = lo..hi,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp).deckFocusRing(RoundedCornerShape(12.dp), scaleTo = 1f),
            )
            DeckSmallButton("", Icons.Filled.Add, enabled = shown < hi) { onChange((shown + step).coerceIn(lo, hi)) }
        }
    }
}
