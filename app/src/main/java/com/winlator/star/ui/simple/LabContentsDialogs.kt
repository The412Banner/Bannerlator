package com.winlator.star.ui.simple

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.contents.ContentsHubViewModel
import com.winlator.star.ui.screens.contents.ContentsTypes
import com.winlator.star.ui.screens.contents.RemoteSourceRepository
import com.winlator.star.ui.screens.contents.SetAsDefault
import com.winlator.star.ui.screens.contents.driverFeedFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Lab's own versions of the Contents pop-ups Classic draws with Material: the install progress (with Cancel and the
// result), Set as default, Add repository, a text entry, and a help text. Same state and calls as the Classic ones.

/** A help text to show in a Lab sheet ([LabHelpSheet]). */
internal class LabHelpText(val title: String, val text: String)

/** [note]'s text under its title; OK or B closes it. */
@Composable
internal fun LabHelpSheet(note: LabHelpText, onDismiss: () -> Unit) {
    SimpleActionSheet(note.title, listOf(MenuAction(L("OK"), {})), onDismiss = onDismiss, message = note.text) { onDismiss() }
}

/** The line over a Contents list while something downloads, installs or saves: "Downloading DXVK 2.6 · 45%". */
internal fun labContentsBanner(states: Map<String, ContentDownloadState>): String? {
    val st = states.values.firstOrNull { !it.terminal } ?: return null
    val pct = (st.fraction * 100).toInt()
    return when {
        st.phase == ContentDownloadPhase.DOWNLOADING -> L("Downloading %1\$s · %2\$s%%", st.title, pct)
        st.saveOnly -> L("Saving %1\$s", st.title)
        else -> L("Installing %1\$s · %2\$s%%", st.title, pct)
    }
}

/**
 * Classic's install popup (ContentsHubScreen's InstallProgressDialog host) for Lab. [auto] picks the installs it opens by
 * itself (by registry key); [request] opens one by key (A on a running download) and [onRequestTaken] clears it. Like
 * Classic it keeps the finished install's last state until Done; unlike Classic, B or Hide closes it while the install
 * goes on (the row and the banner keep its progress).
 */
@Composable
internal fun LabInstallProgressHost(request: String? = null, onRequestTaken: () -> Unit = {}, auto: (String) -> Boolean = { false }) {
    val registry by ContentDownloadRegistry.states.collectAsState()
    var shownKey by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf<ContentDownloadState?>(null) }
    var hidden by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(registry, request) {
        if (hidden.any { it !in registry }) hidden = hidden.filter { it in registry }.toSet()
        if (request != null) {
            registry[request]?.let { shownKey = request; shown = it; hidden = hidden - request }
            onRequestTaken()
        }
        val k = shownKey
        if (k == null) {
            registry.entries.firstOrNull { auto(it.key) && !it.value.terminal && it.key !in hidden }?.let { shownKey = it.key; shown = it.value }
        } else registry[k]?.let { shown = it }
    }
    shown?.let { st ->
        LabInstallProgressDialog(
            state = st,
            onCancel = { ContentDownloadRegistry.requestCancel(st.key) },
            onHide = { hidden = hidden + st.key; shown = null; shownKey = null },
            onDismiss = {
                // As Classic's Done / Close: the entry leaves the registry, so the next install can show.
                ContentDownloadRegistry.remove(st.key)
                shown = null
                shownKey = null
            },
        )
    }
}

/** "Version 2.4.1 • build 63", either half left out when it is not known. */
private fun versionLine(st: ContentDownloadState): String? {
    val name = st.verName?.takeIf { it.isNotBlank() }
    val code = st.verCode?.takeIf { it.isNotBlank() && it != "0" }
    return when {
        name != null && code != null -> L("Version %1\$s • build %2\$s", name, code)
        name != null -> L("Version %1\$s", name)
        code != null -> L("build %1\$s", code)
        else -> null
    }
}

@Composable
private fun LabInstallProgressDialog(state: ContentDownloadState, onCancel: () -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val red = MaterialTheme.colorScheme.error
    val terminal = state.terminal
    val first = remember { FocusRequester() }
    LaunchedEffect(terminal) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    val frac = state.fraction.coerceIn(0f, 1f)
    // A GPU driver from a file installs in one step with no progress to read: a moving bar until it ends.
    val stepless = !terminal && state.phase == ContentDownloadPhase.INSTALLING && !state.hasDownload && frac == 0f && ContentsTypes.isDriver(state.type ?: "")
    val label = when {
        state.cancelled -> L("Cancelled")
        state.phase == ContentDownloadPhase.ERROR -> state.error ?: if (state.saveOnly) L("Save failed.") else L("Install failed.")
        state.phase == ContentDownloadPhase.DONE -> if (state.saveOnly) L("Saved to My files") else state.note ?: L("Installed")
        state.phase == ContentDownloadPhase.DOWNLOADING -> L("Downloading %1\$s%%", (frac * 100).toInt())
        state.saveOnly -> L("Saving to My files")
        stepless -> L("Installing…")
        else -> L("Installing %1\$s%%", (frac * 100).toInt())
    }
    val labelColor = when {
        state.phase == ContentDownloadPhase.ERROR && !state.cancelled -> red
        state.phase == ContentDownloadPhase.DONE -> accent
        else -> SimpleMuted
    }
    LabDialogFrame(onDismiss = { if (terminal) onDismiss() else onHide() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Memory, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                state.verName ?: state.title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        state.type?.takeIf { it.isNotBlank() }?.let { LabChip(it) }
        if (!state.desc.isNullOrBlank()) Text(state.desc, color = SimpleInk, fontSize = 13.sp)
        versionLine(state)?.let { Text(it, color = SimpleMuted, fontSize = 13.sp) }
        Text(label, color = labelColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (stepless) StepBar()
        else if (!terminal || state.phase == ContentDownloadPhase.DONE) {
            // As Classic: the download fills the bar in a darker accent, the install sweeps over it in the accent.
            val dl = when {
                !state.hasDownload -> 0f
                state.phase == ContentDownloadPhase.DOWNLOADING -> frac
                else -> 1f
            }
            val inst = if (state.phase == ContentDownloadPhase.DOWNLOADING) 0f else frac
            Box(Modifier.fillMaxWidth().height(5.dp).clip(SimpleShape).background(SimpleLine)) {
                if (dl > 0f) Box(Modifier.fillMaxWidth(dl).fillMaxHeight().background(lerp(accent, Color.Black, 0.4f)))
                if (inst > 0f) Box(Modifier.fillMaxWidth(inst).fillMaxHeight().background(accent))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            if (!terminal) {
                LabButton(L("Hide"), requester = first, onClick = onHide)
                LabButton(L("Cancel"), danger = true, onClick = onCancel)
            } else {
                LabButton(if (state.phase == ContentDownloadPhase.DONE) L("Done") else L("Close"), primary = true, requester = first, onClick = onDismiss)
            }
        }
    }
}

/** A bar with a segment running across it: work going on with no progress to show. */
@Composable
private fun StepBar() {
    val accent = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "labStepBar")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "labStepBarX")
    BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp).clip(SimpleShape).background(SimpleLine)) {
        val w = maxWidth * 0.3f
        Box(Modifier.offset(x = (maxWidth + w) * x - w).width(w).fillMaxHeight().background(accent))
    }
}

/** A Lab pop-up: a panel with an optional title over [content], scrolling when it is taller than the screen. */
@Composable
private fun LabDialogFrame(title: String? = null, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 560.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (title != null) Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            content()
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp, modifier = Modifier.padding(top = 4.dp))
}

/** A row in a Lab pop-up the controller can reach: a title, an optional line under it, and something on the right. */
@Composable
private fun LabOptionRow(
    title: String,
    sub: String?,
    enabled: Boolean = true,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .background(SimpleCard, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .labTile(onClick = { if (enabled) onClick() }, requester = requester)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = SimpleStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (!sub.isNullOrBlank()) Text(sub, color = SimpleMuted, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** A Lab text field: the label over it, the accent outline while it has the focus. */
@Composable
private fun LabEntryField(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    label: String?,
    placeholder: String? = null,
    requester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Text(label, color = SimpleMuted, fontSize = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = SimpleStrong, fontSize = 15.sp),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onDone = { onIme() }, onSearch = { onIme() }, onGo = { onIme() }),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .background(SimpleCard, SimpleShape)
                .border(if (focused) 2.dp else 1.dp, if (focused) accent else SimpleLine, SimpleShape)
                .padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner ->
                Box {
                    if (value.text.isEmpty() && placeholder != null) Text(placeholder, color = SimpleDim, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
            },
        )
    }
}

/**
 * One line of text to type, in Lab's look (a profile name, a search). [confirm] keeps it; Cancel or B leaves it. A blank
 * text is refused unless [allowBlank] (an empty search clears it).
 */
@Composable
internal fun LabTextEntryDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
    search: Boolean = false,
    onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { field.requestFocus() }
    }
    val ok = allowBlank || text.text.isNotBlank()
    fun done() { if (ok) onDone(text.text.trim()) }
    LabDialogFrame(title, onDismiss) {
        LabEntryField(text, { text = it }, label = null, requester = field, imeAction = if (search) ImeAction.Search else ImeAction.Done, onIme = { done() })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            LabButton(L("Cancel"), onClick = onDismiss)
            LabButton(confirm, primary = ok, modifier = Modifier.alpha(if (ok) 1f else 0.5f), onClick = { done() })
        }
    }
}

/** What an item sets, as Set as default says it ("sets the graphics driver"). */
private fun settingName(kind: SetAsDefault.Kind): String = when (kind) {
    SetAsDefault.Kind.DRIVER -> L("graphics driver")
    SetAsDefault.Kind.DXVK, SetAsDefault.Kind.VEGAS -> L("DX wrapper")
    SetAsDefault.Kind.VKD3D -> L("VKD3D version")
    SetAsDefault.Kind.BOX64 -> L("Box64 version")
    SetAsDefault.Kind.WOWBOX64 -> L("WOWBox64 version")
    SetAsDefault.Kind.FEXCORE -> L("FEXCore version")
    SetAsDefault.Kind.LAYER -> L("compatibility layer")
}

/**
 * Classic's Set as default sheet (SetAsDefaultSheet) in Lab's look, on the same calls: the architecture, where it
 * applies (new container defaults, every existing container of that architecture, every game in them), Classic's
 * summary to confirm when containers or games are touched, then SetAsDefault.apply off the main thread.
 */
@Composable
internal fun LabSetAsDefaultDialog(item: SetAsDefault.Item, onDismiss: () -> Unit, onApplied: (SetAsDefault.Outcome) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = MaterialTheme.colorScheme.primary
    val isLayer = item.kind == SetAsDefault.Kind.LAYER
    var arch by remember(item) { mutableStateOf(if (SetAsDefault.ARM64EC in item.archs) SetAsDefault.ARM64EC else SetAsDefault.X86_64) }
    var toDefaults by remember(item) { mutableStateOf(true) }
    var toContainers by remember(item) { mutableStateOf(false) }
    var toGames by remember(item) { mutableStateOf(false) }
    var counts by remember(item, arch) { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    LaunchedEffect(item, arch) {
        counts = null
        val s = withContext(Dispatchers.IO) { runCatching { SetAsDefault.scope(context.applicationContext, arch) }.getOrNull() }
        counts = (s?.containers?.size ?: 0) to (s?.shortcuts?.size ?: 0)
    }
    val archText = SetAsDefault.archLabel(arch)
    fun n(v: Int?) = v?.toString() ?: "…"
    val nC = counts?.first
    val nG = counts?.second
    val setting = settingName(item.kind)
    val any = toDefaults || toContainers || (toGames && !isLayer)
    fun run() {
        busy = true
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                SetAsDefault.apply(context.applicationContext, item, arch, toDefaults, toContainers, toGames && !isLayer)
            }
            busy = false
            onApplied(out)
        }
    }

    LabDialogFrame(L("Set as default"), onDismiss = { if (!busy) onDismiss() }) {
        Text(L("%1\$s — sets the %2\$s", item.title, setting), color = SimpleMuted, fontSize = 13.sp)
        SheetLabel(L("Architecture"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SetAsDefault.ARM64EC, SetAsDefault.X86_64).forEach { a ->
                val on = arch == a
                val usable = a in item.archs && !busy
                Text(
                    SetAsDefault.archLabel(a),
                    color = if (on) Color.White else SimpleStrong, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (a in item.archs) 1f else 0.4f)
                        .background(if (on) accent else SimpleCard, SimpleShape)
                        .border(1.dp, if (on) accent else SimpleLine, SimpleShape)
                        .labTile(onClick = { if (usable) arch = a }, requester = if (on) first else null, onAccent = on)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        if (item.archs.size < 2) {
            Text(L("%1\$s only works with %2\$s containers.", item.title, SetAsDefault.archLabel(item.archs.first())), color = SimpleMuted, fontSize = 12.sp)
        }
        SheetLabel(L("Apply to"))
        LabOptionRow(L("New container defaults"), L("Every new %1\$s container starts with it.", archText), enabled = !busy, onClick = { toDefaults = !toDefaults }) { LabSwitch(toDefaults) }
        LabOptionRow(
            L("All existing containers"),
            L("%1\$s %2\$s containers switch to it now.", n(nC), archText) +
                if (isLayer) " " + L("Uses the in-place layer update with a revert snapshot; saves are kept.") else "",
            enabled = !busy, onClick = { toContainers = !toContainers },
        ) { LabSwitch(toContainers) }
        LabOptionRow(
            L("All game shortcuts"),
            if (isLayer) L("Games always use their container’s layer, so there is nothing to set per game.")
            else L("%1\$s games in those containers get it written into their own settings.", n(nG)),
            enabled = !isLayer && !busy, onClick = { toGames = !toGames },
        ) { LabSwitch(toGames && !isLayer) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (busy) Text(L("Applying…"), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            LabButton(L("Cancel"), onClick = { if (!busy) onDismiss() })
            LabButton(
                L("Apply"), primary = any && !busy, modifier = Modifier.alpha(if (any && !busy) 1f else 0.5f),
                onClick = { if (any && !busy) { if (toContainers || (toGames && !isLayer)) confirm = true else run() } },
            )
        }
    }

    if (confirm) {
        val lines = buildList {
            if (toDefaults) add(L("New %1\$s containers will use it", archText))
            if (toContainers) add(L("%1\$s %2\$s containers change their %3\$s", n(nC), archText, setting))
            if (toGames && !isLayer) add(L("%1\$s games get it set as their own %2\$s", n(nG), setting))
        }
        SimpleActionSheet(
            title = L("Apply %1\$s?", item.title),
            actions = listOf(MenuAction(L("Apply"), { run() }), MenuAction(L("Cancel"), {})),
            onDismiss = { confirm = false },
            message = lines.joinToString("\n") { "•  $it" } + "\n\n" + L("Containers or games that are running are skipped. You can undo this right after."),
        ) { a -> confirm = false; a.run() }
    }
}

/**
 * Classic's Add repository dialog (ContentsHubScreen's AddRepoDialog) in Lab's look: a name and a URL, GPU driver source
 * or not, and the source format for a component source. Added the same way (the shared driver-source store for a driver
 * source, the Contents source store otherwise).
 */
@Composable
internal fun LabAddRepoDialog(vm: ContentsHubViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var url by remember { mutableStateOf(TextFieldValue("")) }
    var isDriver by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(RemoteSourceRepository.SourceFormat.WCP_JSON) }
    var formatOpen by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    fun fmt(f: RemoteSourceRepository.SourceFormat) = f.name.replace('_', ' ')
    val ok = name.text.isNotBlank() && url.text.isNotBlank()
    fun add() {
        val n = name.text.trim()
        val u = url.text.trim()
        if (n.isBlank() || u.isBlank()) return
        if (isDriver) vm.addDriverSource(n, driverFeedFor(u))
        else vm.addComponentSource(RemoteSourceRepository.RemoteSource(name = n, url = u, format = format, supportedTypes = emptyList(), isCustom = true))
        onDismiss()
    }
    LabDialogFrame(L("Add repository"), onDismiss) {
        Text(L("Paste any component or GPU-driver source — it joins the list like a built-in."), color = SimpleMuted, fontSize = 13.sp)
        LabEntryField(name, { name = it }, L("Display name"), requester = first, imeAction = ImeAction.Next)
        LabEntryField(url, { url = it }, if (isDriver) L("GitHub repo (owner/repo) or JSON URL") else L("URL (repo, releases API, or JSON)"), onIme = { add() })
        LabOptionRow(L("GPU driver source"), L("Listed with the other GPU driver sources"), onClick = { isDriver = !isDriver }) { LabSwitch(isDriver) }
        if (!isDriver) {
            LabOptionRow(L("Source format"), null, onClick = { formatOpen = true }) {
                Text(fmt(format), color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            LabButton(L("Cancel"), onClick = onDismiss)
            LabButton(L("Add source"), primary = ok, modifier = Modifier.alpha(if (ok) 1f else 0.5f), onClick = { add() })
        }
    }
    if (formatOpen) {
        SimpleActionSheet(
            L("Source format"),
            RemoteSourceRepository.SourceFormat.values().map { f -> MenuAction(if (f == format) fmt(f) + "  ✓" else fmt(f), { format = f }) },
            onDismiss = { formatOpen = false },
        ) { a -> formatOpen = false; a.run() }
    }
}
