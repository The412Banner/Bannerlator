package com.winlator.star.ui.simple

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.container.LayerList
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.contents.LAYER_TYPES
import com.winlator.star.contents.cachedLayerCatalog
import com.winlator.star.contents.toCatalogRow
import com.winlator.star.contents.toInstalledLayer
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.startContentDownload
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.COMMUNITY_KEY
import com.winlator.star.ui.screens.COMMUNITY_PREFS
import com.winlator.star.ui.screens.CommunityGroup
import com.winlator.star.ui.screens.fetchCommunityGroups
import com.winlator.star.ui.screens.loadProfiles
import com.winlator.star.ui.screens.normVer
import com.winlator.star.ui.theme.AppThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// The Wine / Proton lists (LayerList.kt decides what they hold): "Get Wine / Proton" (the catalog's layers) and
// "Wine / Proton version" (the installed ones a container can use), in Lab, Classic and the Deck styles alike.
// Portrait is for touch: big rows, a tappable close and "?", no button hints. Landscape works like the other Lab
// pop-ups: the first row (or the current layer) has the focus, the D-pad moves, A picks, B closes, L1/R1 switch
// Proton/Wine, Y the x86_64 switch, with the hints along the bottom; touch works there too.

private val LayerShape = RoundedCornerShape(14.dp)
private val PillShape = RoundedCornerShape(50)
private val Gold = Color(0xFFF2B84B)

/** Lab's colours in the Lab style, the Material theme's elsewhere. */
private class LayerColors(
    val panel: Brush, val chip: Color, val line: Color, val strong: Color, val ink: Color, val muted: Color, val dim: Color,
    val accent: Color, val onAccent: Color,
) {
    val light: Boolean get() = strong.luminance() < 0.5f
}

@Composable
private fun layerColors(): LayerColors {
    val cs = MaterialTheme.colorScheme
    return if (AppThemeState.simpleLook) {
        LayerColors(SimplePanelBrush, SimpleCard, SimpleLine, SimpleStrong, SimpleInk, SimpleMuted, SimpleDim, cs.primary, cs.onPrimary)
    } else {
        LayerColors(
            SolidColor(cs.surfaceContainerHigh), cs.surfaceContainerHighest, cs.outline, cs.onSurface, cs.onSurface,
            cs.onSurfaceVariant, cs.onSurfaceVariant.copy(alpha = 0.7f), cs.primary, cs.onPrimary,
        )
    }
}

private enum class PillKind { VERSION, NEW, INSTALLED, UPDATE, CURRENT, ARCH }

private class Pill(val text: String, val kind: PillKind)

private fun pillsOf(r: LayerList.Row): List<Pill> = buildList {
    if (r.verCode > 0) add(Pill("v${r.verCode}", PillKind.VERSION))
    if (r.archTag) add(Pill(LayerList.X86_64, PillKind.ARCH))
    when (r.status) {
        LayerList.Status.NEW -> add(Pill(L("New"), PillKind.NEW))
        LayerList.Status.INSTALLED -> add(Pill(L("Installed"), PillKind.INSTALLED))
        LayerList.Status.UPDATE -> add(Pill(L("Update"), PillKind.UPDATE))
        null -> {}
    }
    if (r.current) add(Pill(L("Current"), PillKind.CURRENT))
}

@Composable
private fun PillTag(p: Pill, c: LayerColors) {
    val green = if (c.light) Color(0xFF1E8A50) else Color(0xFF6FE3A2)
    val amber = if (c.light) Color(0xFF9A6A10) else Gold
    val (bg, fg) = when (p.kind) {
        PillKind.VERSION -> c.accent.copy(alpha = 0.18f) to c.accent
        PillKind.NEW -> green.copy(alpha = 0.16f) to green
        PillKind.INSTALLED -> c.chip to c.ink
        PillKind.UPDATE -> amber.copy(alpha = 0.18f) to amber
        PillKind.CURRENT -> c.accent to c.onAccent
        PillKind.ARCH -> c.chip to c.muted
    }
    Text(
        p.text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(PillShape).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The sheet both lists share: title with "?" and close, the Proton | Wine tabs, the x86_64 switch when [x86] is set. */
@Composable
private fun LayerSheet(
    title: String,
    help: String,
    tab: LayerList.Tab,
    onTab: (LayerList.Tab) -> Unit,
    x86: Boolean?,
    onX86: () -> Unit,
    aLabel: String,
    xHint: Boolean,
    onDismiss: () -> Unit,
    body: @Composable (wide: Boolean, portrait: Boolean) -> Unit,
) {
    val c = layerColors()
    val conf = LocalConfiguration.current
    val portrait = conf.screenHeightDp > conf.screenWidthDp
    val wide = conf.screenWidthDp >= 720
    val narrow = conf.screenWidthDp <= 420
    var helpOpen by remember { mutableStateOf(false) }
    val otherTab = if (tab == LayerList.Tab.PROTON) LayerList.Tab.WINE else LayerList.Tab.PROTON
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .fillMaxWidth(if (wide) 0.88f else 0.94f)
                .widthIn(max = 980.dp)
                .heightIn(max = (conf.screenHeightDp * 0.92f).dp)
                .simpleDepth(high = true, shape = LayerShape)
                .background(c.panel, LayerShape)
                .border(1.dp, c.line, LayerShape)
                .onPreviewKeyEvent { ev ->
                    val k = ev.nativeKeyEvent
                    val act: (() -> Unit)? = when (k.keyCode) {
                        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> ({ onTab(otherTab) })
                        KeyEvent.KEYCODE_BUTTON_Y -> if (x86 != null) onX86 else null
                        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> onDismiss
                        else -> null
                    }
                    if (act == null) return@onPreviewKeyEvent false
                    if (k.action == KeyEvent.ACTION_DOWN && k.repeatCount == 0) act()
                    true
                },
        ) {
            // Title, "?" and close (finger-sized in portrait; the controller has B).
            val btn = if (portrait) 46.dp else 40.dp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            ) {
                Text(
                    title, color = c.strong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = if (narrow) 19.sp else 21.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                HeadButton(btn, c, { helpOpen = true }) { Text("?", color = c.strong, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                HeadButton(btn, c, onDismiss) { Icon(Icons.Filled.Close, contentDescription = L("Close"), tint = c.strong, modifier = Modifier.size(20.dp)) }
            }
            // Proton | Wine, then the x86_64 switch on the right.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 12.dp),
            ) {
                if (!portrait) { SimpleGlyph("L1"); Spacer(Modifier.width(8.dp)) }
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(c.chip).border(1.dp, c.line, RoundedCornerShape(10.dp)).padding(3.dp),
                ) {
                    for (t in LayerList.Tab.entries) {
                        val on = t == tab
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (on) c.accent else Color.Transparent)
                                .focusProperties { canFocus = false }
                                .clickable { if (!on) onTab(t) }
                                .padding(horizontal = if (portrait) 20.dp else 16.dp, vertical = if (portrait) 10.dp else 7.dp),
                        ) {
                            Text(
                                if (t == LayerList.Tab.PROTON) L("Proton") else L("Wine"),
                                color = if (on) c.onAccent else c.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            )
                        }
                    }
                }
                if (!portrait) { Spacer(Modifier.width(8.dp)); SimpleGlyph("R1") }
                Spacer(Modifier.weight(1f))
                if (x86 != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).focusProperties { canFocus = false }
                            .clickable { onX86() }.padding(horizontal = 6.dp, vertical = if (portrait) 10.dp else 6.dp),
                    ) {
                        LabSwitch(x86)
                        Spacer(Modifier.width(8.dp))
                        Text(if (narrow) LayerList.X86_64 else "${LayerList.X86_64} (box64)", color = c.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) { body(wide, portrait) }
            if (!portrait) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    LabHint("A", aLabel)
                    if (x86 != null) LabHint("Y", LayerList.X86_64)
                    if (xHint) LabHint("X", L("Options"))
                    LabHint("B", L("Close"))
                }
            }
        }
    }
    // A tap on the Y hint while no row has the focus (after touch) still flips the switch.
    if (x86 != null) LabHintFallback(KeyEvent.KEYCODE_BUTTON_Y) { onX86() }
    if (helpOpen) LabNote(title, help) { helpOpen = false }
}

@Composable
private fun HeadButton(size: androidx.compose.ui.unit.Dp, c: LayerColors, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(c.chip).border(1.dp, c.line, RoundedCornerShape(10.dp))
            .focusProperties { canFocus = false }.clickable(onClick = onClick),
    ) { content() }
}

/** The groups: one column when narrow, two side by side when wide. */
@Composable
private fun LayerGroups(sections: List<LayerList.Section>, wide: Boolean, c: LayerColors, row: @Composable (LayerList.Row) -> Unit) {
    val lines = if (wide) sections.chunked(2) else sections.map { listOf(it) }
    for (pair in lines) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            for (s in pair) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupTitle(L(s.group.title), c)
                    for (r in s.rows) row(r)
                }
            }
            if (wide && pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GroupTitle(text: String, c: LayerColors) {
    Text(
        text.uppercase(), color = c.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
    )
}

/** One layer: star when recommended, short name, tags; a bar while it downloads or installs. */
@Composable
private fun LayerTile(
    name: String,
    pills: List<Pill>,
    star: Boolean,
    portrait: Boolean,
    c: LayerColors,
    requester: FocusRequester,
    onClick: () -> Unit,
    onMenu: (() -> Unit)?,
    trailing: String? = null,
    progress: Float? = null,
    accent: Boolean = false,
    toggle: Boolean? = null,
) {
    Column(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (portrait) 56.dp else 48.dp)
            .labTile(onClick = onClick, onMenu = onMenu, requester = requester)
            .padding(horizontal = if (portrait) 14.dp else 12.dp, vertical = if (portrait) 10.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (star) Text("★", color = Gold, fontSize = 16.sp)
            Text(
                name, color = if (accent) c.accent else c.strong, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            for (p in pills) PillTag(p, c)
            trailing?.let { Text(it, color = c.muted, fontSize = 13.sp, maxLines = 1) }
            toggle?.let { LabSwitch(it) }
        }
        if (progress != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(c.line)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(3.dp).background(c.accent))
            }
        }
    }
}

@Composable
private fun RecommendedCard(name: String, pills: List<Pill>, c: LayerColors, requester: FocusRequester, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.22f), Color.Transparent)))
            .border(1.dp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .labTile(onClick = onClick, requester = requester)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text("★", color = Gold, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(L("Recommended").uppercase(), color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp, maxLines = 1)
            Text(name, color = c.strong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { for (p in pills) PillTag(p, c) }
    }
}

@Composable
private fun OlderToggle(count: Int, open: Boolean, portrait: Boolean, c: LayerColors, requester: FocusRequester, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (portrait) 56.dp else 48.dp)
            .border(1.dp, c.line, SimpleShape)
            .labTile(onClick = onClick, requester = requester)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(L("Older builds") + " · $count", color = c.muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("›", color = c.muted, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(if (open) 90f else 0f))
    }
}

@Composable
private fun Empty(text: String, c: LayerColors) {
    Text(text, color = c.muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
}

/** The focus requesters of a list's lines, by key. */
private class LayerFocus {
    private val map = HashMap<String, FocusRequester>()
    fun of(key: String): FocusRequester = map.getOrPut(key) { FocusRequester() }
}

private fun layerTypeOf(tab: LayerList.Tab): ContentProfile.ContentType =
    if (tab == LayerList.Tab.WINE) ContentProfile.ContentType.CONTENT_TYPE_WINE else ContentProfile.ContentType.CONTENT_TYPE_PROTON

/**
 * "Get Wine / Proton": the catalog's Wine and Proton layers (LayerList.downloadPage), then the community repos (when
 * switched on) and Install from a file. A on a new layer downloads and installs it with the progress on its row; on an
 * installed one (or X) it offers Info and Remove; on a running one Cancel download. [onContentChanged] runs after an
 * install or a remove.
 */
@Composable
internal fun LayerDownloadDialog(
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
    startTab: LayerList.Tab = LayerList.Tab.PROTON,
) {
    val context = LocalContext.current
    val c = layerColors()
    val cm = remember { ContentsManager(context) }
    var profiles by remember { mutableStateOf<List<ContentProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var offline by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(startTab) }
    var x86 by remember { mutableStateOf(false) }
    var olderOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val json = withContext(Dispatchers.IO) { Downloader.downloadString(ContentsManager.REMOTE_PROFILES) }
        if (json != null) cm.setRemoteProfiles(json) else cm.syncContents()
        offline = json == null
        loadProfiles(cm, LAYER_TYPES) { profiles = it }
        loading = false
    }
    val reload: () -> Unit = { loadProfiles(cm, LAYER_TYPES) { profiles = it } }

    val catalog = remember(profiles) { profiles.mapNotNull { it.toCatalogRow() } }
    val installed = remember(profiles) { profiles.mapNotNull { it.toInstalledLayer() } }
    val remoteById = remember(profiles) { profiles.filter { !it.remoteUrl.isNullOrEmpty() }.associateBy { it.remoteUrl } }
    val localByEntry = remember(profiles) { profiles.filter { it.remoteUrl.isNullOrEmpty() }.associateBy { ContentsManager.getEntryName(it) } }
    val page = remember(tab, x86, catalog, installed) { LayerList.downloadPage(tab, x86, catalog, installed) }

    // Community repos (opt-in, the same switch as the component sheets): the active tab's type from each source.
    val srcPrefs = remember { context.getSharedPreferences(COMMUNITY_PREFS, Context.MODE_PRIVATE) }
    var community by remember { mutableStateOf(srcPrefs.getBoolean(COMMUNITY_KEY, false)) }
    var communityGroups by remember { mutableStateOf<List<CommunityGroup>>(emptyList()) }
    var communityLoading by remember { mutableStateOf(false) }
    LaunchedEffect(community, tab) {
        if (!community) { communityGroups = emptyList(); communityLoading = false; return@LaunchedEffect }
        communityLoading = true
        communityGroups = withContext(Dispatchers.IO) { fetchCommunityGroups(context, layerTypeOf(tab)) }
        communityLoading = false
    }
    val installedNorm = remember(profiles) { profiles.filter { it.remoteUrl.isNullOrEmpty() }.map { normVer(it.type.toString(), it.verName) }.toSet() }

    // The download started here (or one still running when the list opened).
    val states by ContentDownloadRegistry.states.collectAsState()
    var tracked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { tracked = ContentDownloadRegistry.states.value.entries.firstOrNull { !it.value.terminal }?.key }
    val trackedState = tracked?.let { states[it] }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trackedState?.phase) {
        val st = trackedState ?: return@LaunchedEffect
        when (st.phase) {
            ContentDownloadPhase.DONE -> {
                reload()
                onContentChanged()
                toast(context, L("Installed %1\$s", st.verName ?: st.title))
                tracked = null
            }
            ContentDownloadPhase.ERROR -> {
                if (!st.cancelled) failure = st.error ?: L("Install failed.")
                tracked = null
            }
            else -> {}
        }
    }
    val fileInstall = rememberLabFileInstall { reload(); onContentChanged() }

    var menuFor by remember { mutableStateOf<Pair<String, List<MenuAction>>?>(null) }
    var confirmFor by remember { mutableStateOf<MenuAction?>(null) }
    var info by remember { mutableStateOf<ContentProfile?>(null) }

    fun busy(p: ContentProfile): Boolean = states[ContentsManager.getEntryName(p)]?.terminal == false
    fun download(p: ContentProfile) {
        val running = tracked?.let { states[it] }?.terminal == false
        if (running) { toast(context, L("Wait until the current download is done")); return }
        startContentDownload(context.applicationContext, p)
        tracked = ContentsManager.getEntryName(p)
    }
    fun removeActions(local: ContentProfile): List<MenuAction> = listOf(
        MenuAction(L("Info"), { info = local }),
        MenuAction(L("Remove"), {
            cm.removeContent(local)
            cm.syncContents()
            reload()
            onContentChanged()
        }, danger = true, confirm = L("Remove \"%1\$s\"?", local.verName)),
    )
    fun menuOf(remote: ContentProfile, local: ContentProfile?): List<MenuAction>? = when {
        busy(remote) -> listOf(MenuAction(L("Cancel download"), { ContentDownloadRegistry.requestCancel(ContentsManager.getEntryName(remote)) }, danger = true))
        local != null -> removeActions(local)
        else -> null
    }
    fun open(name: String, remote: ContentProfile, local: ContentProfile?) {
        val m = menuOf(remote, local)
        if (m != null) menuFor = name to m else download(remote)
    }

    val focus = remember { LayerFocus() }
    val startKey = (page.sections.firstOrNull()?.rows?.firstOrNull() ?: page.older.firstOrNull()?.rows?.firstOrNull())?.id
    val conf = LocalConfiguration.current
    LaunchedEffect(tab, x86, loading, startKey) {
        if (conf.screenHeightDp > conf.screenWidthDp || loading) return@LaunchedEffect
        delay(80)
        runCatching { focus.of(startKey ?: "__file__").requestFocus() }
    }
    LaunchedEffect(tab) { olderOpen = false }

    LayerSheet(
        title = L("Get Wine / Proton"),
        help = L("Proton and Wine are separate tabs. x86_64 lists the box64 builds instead of the arm64ec ones. Older builds are earlier versions, still installable."),
        tab = tab, onTab = { tab = it }, x86 = x86, onX86 = { x86 = !x86; com.winlator.star.sound.toggleSound(x86) },
        aLabel = L("Get"), xHint = false, onDismiss = onDismiss,
    ) { wide, portrait ->
        val tile: @Composable (LayerList.Row) -> Unit = { r ->
            val remote = remoteById[r.id]
            if (remote != null) {
                val local = r.installedEntry?.let { localByEntry[it] }
                val st = states[ContentsManager.getEntryName(remote)]
                val running = st != null && !st.terminal
                LayerTile(
                    name = r.name, pills = pillsOf(r), star = r.recommended, portrait = portrait, c = c, requester = focus.of(r.id),
                    onClick = { open(r.name, remote, local) },
                    onMenu = menuOf(remote, local)?.let { m -> { menuFor = r.name to m } },
                    trailing = when {
                        running -> "${(st!!.fraction * 100).toInt()}%"
                        st != null && st.phase == ContentDownloadPhase.ERROR && !st.cancelled -> L("Failed")
                        else -> null
                    },
                    progress = if (running) st!!.fraction else null,
                )
            }
        }
        when {
            loading -> Empty(L("Loading the list…"), c)
            page.isEmpty -> Empty(if (offline) L("Could not load the list.") else L("Nothing to download here."), c)
            else -> {
                page.recommended?.let { r ->
                    val remote = remoteById[r.id]
                    if (remote != null) {
                        val local = r.installedEntry?.let { localByEntry[it] }
                        RecommendedCard(r.name, pillsOf(r), c, focus.of("__rec__")) { open(r.name, remote, local) }
                    }
                }
                LayerGroups(page.sections, wide, c, tile)
                if (page.olderCount > 0) {
                    OlderToggle(page.olderCount, olderOpen, portrait, c, focus.of("__older__")) { olderOpen = !olderOpen }
                    if (olderOpen) LayerGroups(page.older, wide, c, tile)
                }
            }
        }
        // Community repos: each source's builds of this tab's type, as they name them.
        for (g in communityGroups) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                GroupTitle(g.name, c)
                g.profiles.forEachIndexed { i, p ->
                    val key = ContentsManager.getEntryName(p)
                    val st = states[key]
                    val running = st != null && !st.terminal
                    val have = normVer(p.type.toString(), p.verName) in installedNorm
                    LayerTile(
                        name = p.verName ?: key, pills = if (have) listOf(Pill(L("Installed"), PillKind.INSTALLED)) else emptyList(),
                        star = false, portrait = portrait, c = c, requester = focus.of("c|${g.name}|$i"),
                        onClick = {
                            when {
                                running -> menuFor = (p.verName ?: key) to listOf(MenuAction(L("Cancel download"), { ContentDownloadRegistry.requestCancel(key) }, danger = true))
                                !have -> download(p)
                            }
                        },
                        onMenu = null,
                        trailing = if (running) "${(st!!.fraction * 100).toInt()}%" else null,
                        progress = if (running) st!!.fraction else null,
                    )
                }
            }
        }
        if (communityLoading) Empty(L("Loading community repos…"), c)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            trackedState?.takeIf { !it.terminal }?.let { st ->
                LayerTile(
                    name = L("Cancel download"), pills = emptyList(), star = false, portrait = portrait, c = c, requester = focus.of("__cancel__"),
                    onClick = { ContentDownloadRegistry.requestCancel(st.key) }, onMenu = null, trailing = st.verName ?: st.title, accent = true,
                )
            }
            val fl = fileInstall.line()
            LayerTile(
                name = fl.title, pills = emptyList(), star = false, portrait = portrait, c = c, requester = focus.of("__file__"),
                onClick = { val a = fl.onA; if (a != null) a() else if (fl.actions.isNotEmpty()) menuFor = fl.title to fl.actions },
                onMenu = if (fl.actions.isNotEmpty()) ({ menuFor = fl.title to fl.actions }) else null,
                trailing = fl.trailing, progress = fl.progress, accent = fl.onA != null,
            )
            LayerTile(
                name = L("Community repos"), pills = emptyList(), star = false, portrait = portrait, c = c, requester = focus.of("__community__"),
                onClick = {
                    community = !community
                    srcPrefs.edit().putBoolean(COMMUNITY_KEY, community).apply()
                    com.winlator.star.sound.toggleSound(community)
                },
                onMenu = null, toggle = community,
            )
        }
    }

    menuFor?.let { (title, actions) ->
        SimpleActionSheet(title, actions, onDismiss = { menuFor = null }) { a -> menuFor = null; if (a.confirm != null) confirmFor = a else a.run() }
    }
    confirmFor?.let { a -> SimpleConfirm(a.label, a.confirm ?: "", danger = a.danger, onDismiss = { confirmFor = null }) { confirmFor = null; a.run() } }
    info?.let { p -> LabNote(L("Content info"), contentInfoText(p)) { info = null } }
    failure?.let { msg -> LabNote(L("Download failed"), msg) { failure = null } }
}

/**
 * "Wine / Proton version": the installed layers [installed] (LayerList.installedPage) with [current] marked. A picks one
 * ([onPick]); X or a long press shows Info, and Remove for a layer that isn't the current one ([onChanged] runs after).
 * "+ Download from source" ([onGetMore]) opens the catalog list.
 */
@Composable
internal fun LayerInstalledDialog(
    installed: List<LayerList.Installed>,
    current: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    onGetMore: (() -> Unit)? = null,
    onChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val c = layerColors()
    val cm = remember { ContentsManager(context).also { it.syncContents() } }
    val catalog = remember { cachedLayerCatalog() }
    var tab by remember { mutableStateOf(LayerList.tabOfEntry(current, installed)) }
    val page = remember(tab, installed, current) { LayerList.installedPage(tab, installed, catalog, current) }
    var menuFor by remember { mutableStateOf<Pair<String, List<MenuAction>>?>(null) }
    var confirmFor by remember { mutableStateOf<MenuAction?>(null) }
    var info by remember { mutableStateOf<ContentProfile?>(null) }

    fun actionsOf(r: LayerList.Row): List<MenuAction> {
        val p = cm.getProfileByEntryName(r.id) ?: return emptyList()
        return buildList {
            add(MenuAction(L("Info"), { info = p }))
            if (!r.current) add(MenuAction(L("Remove"), {
                cm.removeContent(p)
                cm.syncContents()
                onChanged()
            }, danger = true, confirm = L("Remove \"%1\$s\"?", r.name)))
        }
    }

    val focus = remember { LayerFocus() }
    val rows = page.sections.flatMap { it.rows }
    val startKey = (rows.firstOrNull { it.current } ?: rows.firstOrNull())?.id ?: "__more__"
    val conf = LocalConfiguration.current
    LaunchedEffect(tab) {
        if (conf.screenHeightDp > conf.screenWidthDp) return@LaunchedEffect
        delay(80)
        runCatching { focus.of(startKey).requestFocus() }
    }

    LayerSheet(
        title = L("Wine / Proton version"),
        help = L("Proton and Wine are separate tabs; the container's layer is marked Current. Hold a row (or press X) for its info or to remove it."),
        tab = tab, onTab = { tab = it }, x86 = null, onX86 = {}, aLabel = L("Pick"), xHint = true, onDismiss = onDismiss,
    ) { wide, portrait ->
        if (page.isEmpty) Empty(if (tab == LayerList.Tab.WINE) L("No Wine builds installed.") else L("Nothing installed yet."), c)
        LayerGroups(page.sections, wide, c) { r ->
            LayerTile(
                name = r.name, pills = pillsOf(r), star = r.recommended, portrait = portrait, c = c, requester = focus.of(r.id),
                onClick = { onPick(r.id) },
                onMenu = { val a = actionsOf(r); if (a.isNotEmpty()) menuFor = r.name to a },
            )
        }
        if (onGetMore != null) {
            LayerTile(
                name = L("+ Download from source"), pills = emptyList(), star = false, portrait = portrait, c = c,
                requester = focus.of("__more__"), onClick = onGetMore, onMenu = null, accent = true,
            )
        }
    }

    menuFor?.let { (title, actions) ->
        SimpleActionSheet(title, actions, onDismiss = { menuFor = null }) { a -> menuFor = null; if (a.confirm != null) confirmFor = a else a.run() }
    }
    confirmFor?.let { a -> SimpleConfirm(a.label, a.confirm ?: "", danger = a.danger, onDismiss = { confirmFor = null }) { confirmFor = null; a.run() } }
    info?.let { p -> LabNote(L("Content info"), contentInfoText(p)) { info = null } }
}
