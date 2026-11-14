package com.winlator.star.ui.deck

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.R
import com.winlator.star.linux.LinuxComponents
import com.winlator.star.linux.LinuxTuning
import com.winlator.star.store.FriendAvatar
import com.winlator.star.store.InGameFriendsSource
import com.winlator.star.store.PRESENCE_ORDER
import com.winlator.star.store.SteamAgentFriendsBridge
import com.winlator.star.store.SteamFriendsStore
import com.winlator.star.store.dotColor
import com.winlator.star.ui.XServerDialogState
import com.winlator.star.ui.XServerDrawerState
import com.winlator.star.ui.simple.L
import com.winlator.star.ui.simple.SimpleCard
import com.winlator.star.ui.simple.SimpleLine
import com.winlator.star.ui.simple.SimpleMuted
import com.winlator.star.ui.simple.SimpleShape
import com.winlator.star.ui.simple.SimpleStrong
import kotlinx.coroutines.delay

// Lab-drawn panels for the in-game menu's Steam page (the Linux Steam client's switches and its per-Proton components),
// the Task Manager's header (stats, per-core clocks, container) and the Friends page. Each one is the Classic drawer's
// section (XServerDrawer.kt LinuxSteamSection / LinuxComponentsContent / TmStatGrid / TmCoreStrip / TmContainerPanel,
// XServerFriendsTab.kt FriendsContent) on the same state and callbacks, drawn with the in-game Lab rows.

// ───────────────────────────── Steam client (Linux) ─────────────────────────────

/** The Steam client's menus and the entry's session switches (Classic LinuxSteamSection). Every switch saves to the entry. */
@Composable
internal fun LabSteamClientPanel(state: XServerDrawerState) {
    val active by state.linuxSteamSession.collectAsState()
    if (!active) return
    val options by state.linuxOptions.collectAsState()
    val sysmem by state.linuxTurnipSysmem.collectAsState()
    val touch by state.linuxTouch.collectAsState()
    fun on(key: String) = options[key] ?: false
    fun flip(key: String, v: Boolean) {
        state.setLinuxOption(key, v)
        state.onLinuxOption?.accept(key, v)
    }
    val game = listOf(SaveScope.GAME)

    IgCard {
        IgRow(L("Steam menus"), hint = L("Presses the Steam button, or opens Steam's Quick Access Menu, on player one's controller.")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IgButton(L("Steam menu"), null, { state.onLinuxSteamGuide?.run() })
                IgButton(L("Quick Access"), null, { state.onLinuxSteamQam?.run() })
            }
        }
        IgDivider()
        IgRow(
            L("Touch"),
            hint = L("Touchscreen: fingers reach Steam as real touches (Big Picture scrolls under one). Touchpad: a drag moves the pointer, a tap clicks. App setting follows the Touchscreen switch every container uses. In Touchpad, Touch: Direct (Controls > Mouse) puts the pointer under the finger instead. Applies now."),
            scopes = game,
            wideControl = true,
        ) {
            IgSegmented(listOf("" to L("App setting"), "1" to L("Touchscreen"), "0" to L("Touchpad")), touch) { v ->
                state.setLinuxTouch(v)
                state.onLinuxTouch?.accept(v)
            }
        }
        IgDivider()
        IgToggleRow(L("On-screen Steam and Quick Access buttons"), on(LinuxTuning.EXTRA_STEAM_BUTTONS), hint = L("Applies now."), scopes = game) {
            flip(LinuxTuning.EXTRA_STEAM_BUTTONS, it)
        }
        IgDivider()
        IgToggleRow(L("Double Back opens Quick Access"), on(LinuxTuning.EXTRA_DOUBLE_BACK_QAM), hint = L("One Back press still opens this menu. Applies now."), scopes = game) {
            flip(LinuxTuning.EXTRA_DOUBLE_BACK_QAM, it)
        }
        IgDivider()
        IgToggleRow(
            L("Stretch games to fill the screen"), on(LinuxTuning.EXTRA_FILL_SCREEN),
            hint = L("Keeps a game that shrinks its window (FlatOut after Resume game) filling the screen. Applies now."), scopes = game,
        ) { flip(LinuxTuning.EXTRA_FILL_SCREEN, it) }
        IgDivider()
        IgToggleRow(
            L("Quake-engine games windowed"), on(LinuxTuning.EXTRA_IDTECH3),
            hint = L("Quake III, Team Arena, Return to Castle Wolfenstein and Jedi Academy run windowed at the session's size, the one way they start here. Applies at the next game start."),
            scopes = game,
        ) { flip(LinuxTuning.EXTRA_IDTECH3, it) }
        IgDivider()
        IgToggleRow(
            L("Offline mode"), on(LinuxTuning.EXTRA_OFFLINE),
            hint = L("Steam starts without signing in to Valve; installed games still launch. Needs one online sign-in on this device first. The client reads it only as it starts, so it applies at the next session."),
            scopes = game,
        ) { flip(LinuxTuning.EXTRA_OFFLINE, it) }
    }

    IgSectionLabel(L("Troubleshooting"))
    IgCard {
        IgToggleRow(
            L("Xalia off (PROTON_USE_XALIA=0)"), on(LinuxTuning.EXTRA_NO_XALIA),
            hint = L("On by default: xalia costs every game CPU time under FEX. Turn off only for a game that needs its gamepad navigation. Applies at the next game start."),
            scopes = game,
        ) { flip(LinuxTuning.EXTRA_NO_XALIA, it) }
        IgDivider()
        IgToggleRow(
            L("proot without seccomp"), on(LinuxTuning.EXTRA_PROOT_NO_SECCOMP),
            hint = L("Slower; for a device whose seccomp misbehaves. Applies at the next session."), scopes = game,
        ) { flip(LinuxTuning.EXTRA_PROOT_NO_SECCOMP, it) }
        IgDivider()
        IgRow(
            L("Turnip sysmem rendering"),
            hint = L("Automatic turns it on for the imported A710/A720/A722 Linux drivers. Applies at the next session."),
            scopes = game,
            wideControl = true,
        ) {
            IgSegmented(listOf("" to L("Automatic"), "1" to L("On"), "0" to L("Off")), sysmem) { v ->
                state.setLinuxTurnipSysmem(v)
                state.onLinuxTurnipSysmem?.accept(v)
            }
        }
    }
}

/**
 * Quick swaps between the FEX / DXVK / VKD3D-Proton builds the entry already stored, per Proton (Classic
 * LinuxComponentsContent): what the running game's Proton uses, then one pick per component.
 */
@Composable
internal fun LabLinuxComponentsPanel(state: XServerDrawerState) {
    val active by state.linuxSteamSession.collectAsState()
    if (!active) return
    val snap by state.linuxComponents.collectAsState()
    // Read on every open: a game may have closed since, letting a waiting swap go in.
    LaunchedEffect(Unit) { state.onLinuxComponentsRefresh?.run() }
    // The Proton being edited: the running game's first, else the first there is; the pick survives the menu closing.
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val protonScope = listOf(SaveScope.other(L("Saved in that Proton, for every game using it")))

    IgCard {
        IgRow(L("Read the Protons again"), hint = L("Downloads, importing and deleting are on the Components tab of the Steam (Linux) entry's settings.")) {
            IgButton(L("Refresh"), Icons.Outlined.Refresh, { state.onLinuxComponentsRefresh?.run() })
        }
        val s = snap
        if (s == null) {
            IgDivider()
            IgNote(L("Reading the Protons…"))
            return@IgCard
        }
        val running = s.protons.filter { it.inUseByGame }
        IgDivider()
        if (running.isEmpty()) {
            IgRow(L("Running now"), hint = L("No game is running on a Proton. Changes apply the next time a game starts."))
        } else {
            running.forEach { r ->
                IgRow(
                    L("Running now"),
                    hint = r.proton.name + "\n" + LinuxComponents.COMPONENTS.joinToString("\n") { comp ->
                        "${LinuxComponents.LABEL.getValue(comp)}: ${r.components.getValue(comp).inUse}"
                    },
                )
            }
        }
        val view = s.protons.firstOrNull { it.proton.id == pick } ?: running.firstOrNull() ?: s.protons.firstOrNull()
        if (view == null) {
            IgDivider()
            IgNote(L("No Proton is installed yet. Start the Steam client once so it downloads its ARM64 Proton."))
            return@IgCard
        }
        val p = view.proton
        IgDivider()
        IgPickRow(
            L("Proton"),
            s.protons.map { it.proton.name },
            s.protons.indexOf(view),
            hint = listOfNotNull(
                p.version,
                L("a game is running on it").takeIf { view.inUseByGame },
                view.reappliedAt.takeIf { it > 0 }?.let {
                    L("re-applied at launch %1\$s", java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it * 1000)))
                },
            ).joinToString(" · "),
        ) { pick = s.protons[it].proton.id }
        val build = LinuxComponents.safeName(p.version)
        for (comp in LinuxComponents.COMPONENTS) {
            val st = view.components.getValue(comp)
            val stored = s.packages.filter { it.comp == comp }
            // Value -> label: the originals by build, then the stored packages by version (by file name where two share a
            // version, so no label is offered twice).
            val versions = stored.groupingBy { it.version }.eachCount()
            val options = view.originals.filter { it.comp == comp }
                .map { ("orig:" + it.protonVersion) to (if (it.protonVersion == build) L("Original") else L("Original · %1\$s", it.protonVersion)) } +
                stored.map { it.file to (if ((versions[it.version] ?: 0) > 1) it.file.removeSuffix(".wcp") else it.version) }
            if (options.isEmpty()) continue
            val current = st.activeFile ?: "orig:$build"
            val selected = options.indexOfFirst { it.first == current }
            val shown = options.getOrNull(selected)?.second
            // The pick already shows the choice: only say more when there is more to say.
            val detail = st.queued?.let { L("Next: %1\$s · after the game closes", it) } ?: st.inUse.takeIf { it != shown }
            IgDivider()
            IgPickRow(LinuxComponents.LABEL.getValue(comp), options.map { it.second }, selected, hint = detail, scopes = protonScope) { i ->
                val value = options[i].first
                // Picking what is already in place again cancels the swap waiting for the game.
                if (value != current || st.queued != null) state.onLinuxComponentSwap?.swap(p.id, comp, value)
            }
        }
        IgDivider()
        IgNote(
            if (view.inUseByGame) L("A game is running on this Proton: a change waits until it closes.")
            else L("Changes apply the next time a game starts."),
        )
    }
}

// ───────────────────────────── Task Manager header ─────────────────────────────

private data class LabTmTile(val caption: String, val big: String, val small: String, val tint: Color)

/** The Task Manager's live stat tiles (Classic TmStatGrid): CPU, GPU, GPU clock, FPS, RAM, swap, battery, battery °C. */
@Composable
internal fun LabTmStatGrid(h: XServerDialogState.TmHeaderStats?) {
    if (h == null) return
    val accent = MaterialTheme.colorScheme.primary
    val ink = SimpleStrong
    val light = com.winlator.star.ui.theme.AppThemeState.labLight
    val ok = if (light) Color(0xFF1F9D57) else Color(0xFF6FDF9A)
    val warn = if (light) Color(0xFFB5761A) else Color(0xFFE2B06F)
    fun numOf(s: String) = s.takeWhile { it.isDigit() || it == '.' }.ifEmpty { s }
    fun compact(s: String) = s.replace("iB", "")
    val tiles = buildList {
        add(LabTmTile("CPU", h.cpuPct?.let { "$it%" } ?: "—", h.cpuTempC?.let { " $it°" } ?: "", accent))
        add(LabTmTile("GPU", h.gpuPct?.let { "$it%" } ?: "—", h.gpuTempC?.let { " $it°" } ?: "", accent))
        add(LabTmTile(L("GPU CLK"), h.gpuClockMhz?.let { "$it" } ?: "—", if (h.gpuClockMhz != null) "MHz" else "", ink))
        add(LabTmTile("FPS", "${h.fps}", if (h.fpsMin > 0) " ·${h.fpsMin}min" else "", ok))
        add(LabTmTile("RAM", numOf(h.ramUsed), "/" + compact(h.ramTotal), ink))
        if (h.swap != null) add(LabTmTile("SWAP", numOf(h.swap.substringBefore("/")), "/" + compact(h.swap.substringAfter("/")), ink))
        else add(LabTmTile("SWAP", "—", "", ink))
        add(LabTmTile(L("BAT"), h.batteryPct?.let { "$it%" } ?: "—", " " + String.format("%.1f", h.batteryWatts) + "W" + (if (h.charging) " ⚡" else ""), warn))
        add(LabTmTile(L("BAT °C"), h.batteryTempC?.let { "$it°" } ?: "—", "", ink))
    }
    IgCard {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tiles.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { t -> LabTmTileBox(t, Modifier.weight(1f)) }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun LabTmTileBox(t: LabTmTile, modifier: Modifier) {
    Column(
        modifier
            .background(SimpleCard, SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(t.caption, color = SimpleMuted, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 9.5.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = t.tint)) { append(t.big) }
                if (t.small.isNotEmpty()) withStyle(SpanStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = SimpleMuted)) { append(t.small) }
            },
            fontFamily = NunitoSansFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Every core's current clock in full MHz, four to a row (Classic TmCoreStrip). */
@Composable
internal fun LabTmCoreStrip(h: XServerDialogState.TmHeaderStats?) {
    val cores = h?.perCoreMhz ?: return
    if (cores.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    IgCard {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            cores.indices.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { i ->
                        val mhz = cores[i]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f).background(accent.copy(alpha = 0.10f), SimpleShape).border(1.dp, accent.copy(alpha = 0.3f), SimpleShape).padding(vertical = 6.dp),
                        ) {
                            Text("C$i", color = SimpleMuted, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accent)) { append(if (mhz > 0) "$mhz" else "—") }
                                    if (mhz > 0) withStyle(SpanStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = SimpleMuted)) { append(" MHz") }
                                },
                                fontFamily = NunitoSansFamily,
                                maxLines = 1,
                            )
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The running container's configuration, folded open by default like the Classic panel (TmContainerPanel). */
@Composable
internal fun LabTmContainerPanel(info: XServerDialogState.TmContainerInfo?) {
    if (info == null) return
    val accent = MaterialTheme.colorScheme.primary
    var expanded by remember { mutableStateOf(true) }
    IgCard {
        IgRow(L("Container")) {
            IgButton(if (expanded) L("Hide") else L("Show"), if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, { expanded = !expanded })
        }
        if (expanded) {
            IgDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val wayland = info.displayBackend == "Wayland"
                // A Linux session runs no Wine and no DX wrapper of ours: say what is true and leave out what would be invented.
                LabInfoLine(if (info.linuxRuntime) L("Runtime") else "Wine", info.wine)
                LabInfoLine(L("Display backend"), info.displayBackend)
                if (!info.linuxRuntime) LabInfoLine(L("DX wrapper"), com.winlator.star.ui.prettyDxWrapper(info.dxWrapper), accent)
                LabInfoLine(
                    L("Renderer"),
                    when {
                        info.linuxRuntime -> L("Vulkan (gamescope → Wayland compositor)")
                        wayland -> L("Vulkan (Wayland compositor)")
                        else -> com.winlator.star.ui.prettyRenderer(info.renderer)
                    },
                    accent,
                )
                LabInfoLine(L("Graphics driver"), info.graphicsDriver)
                info.gpuSpoof?.let { LabInfoLine(L("GPU name"), L("%1\$s (spoofed, not the real GPU)", it)) }
                LabInfoLine(L("Resolution"), info.resolution)
                LabInfoLine("HDR", info.hdr)
                LabInfoLine(L("Device"), com.winlator.star.ui.tidyDevice(info.device))
            }
        }
    }
}

@Composable
private fun LabInfoLine(label: String, value: String, valueColor: Color? = null) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, modifier = Modifier.weight(0.42f))
        Text(value, color = valueColor ?: SimpleStrong, fontFamily = NunitoSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, textAlign = TextAlign.End, modifier = Modifier.weight(0.58f))
    }
}

/** A running program's icon (the Classic Task Manager row's): the EXE's own, else the generic process glyph. */
@Composable
internal fun LabTmProcessIcon(p: XServerDialogState.TmProcess) {
    val icon = p.icon
    if (icon != null) {
        val img = remember(icon) { icon.asImageBitmap() }
        Image(bitmap = img, contentDescription = null, modifier = Modifier.size(24.dp).clip(SimpleShape))
    } else {
        Icon(painterResource(R.drawable.taskmgr_process), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
    }
}

// ───────────────────────────── Friends ─────────────────────────────

/**
 * Steam friends and 1:1 text chat while the game runs (Classic FriendsContent): the grouped roster with unread counts
 * and typing, and one conversation with its send box, on SteamFriendsStore's flows. The chat counts as open only while
 * the menu is open, as in Classic, so a message that lands while it is shut is unread and notifies.
 */
@Composable
internal fun LabFriendsPanel(state: XServerDrawerState) {
    val src by InGameFriendsSource.state.collectAsState()
    val selectedId by InGameFriendsSource.selectedFriendId.collectAsState()
    val friends by SteamFriendsStore.friends.collectAsState()
    val relayPresence by SteamAgentFriendsBridge.presence.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                when (src.kind) {
                    InGameFriendsSource.Kind.AGENT_RELAY -> L("via in-game Steam")
                    InGameFriendsSource.Kind.APP_SESSION -> L("via app session")
                    else -> ""
                },
                color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 12.5.sp, modifier = Modifier.weight(1f),
            )
            // Relay warm-up: until every friend is confirmed the unconfirmed ones sit under "Status unknown".
            if (src.kind == InGameFriendsSource.Kind.AGENT_RELAY && !relayPresence.complete) {
                Text(L("presence: %1\$s of %2\$s known", relayPresence.known, relayPresence.total), color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 11.5.sp)
                Spacer(Modifier.width(8.dp))
            }
            IgHelpButton(L("Friends"), L(com.winlator.star.ui.FRIENDS_HELP_TEXT))
        }
        Spacer(Modifier.height(8.dp))
        when {
            src.kind == InGameFriendsSource.Kind.RELAY_STOPPED -> IgCard { IgInfo(L(InGameFriendsSource.RELAY_STOPPED_TEXT)) }
            selectedId != 0L -> {
                // Prefer the live roster entry (presence updates); fall back to the store's snapshot.
                val friend = friends.firstOrNull { it.steamId == selectedId } ?: SteamFriendsStore.friendById(selectedId)
                LabChatThread(friend) { InGameFriendsSource.selectFriend(0L) }
            }
            else -> LabFriendsList(friends) { InGameFriendsSource.selectFriend(it.steamId) }
        }
    }
}

@Composable
private fun LabFriendsList(friends: List<SteamFriendsStore.SteamFriend>, onOpen: (SteamFriendsStore.SteamFriend) -> Unit) {
    val unread by SteamFriendsStore.unread.collectAsState()
    val collapsedSections by SteamFriendsStore.collapsedSections.collectAsState()
    val typingMap by SteamFriendsStore.typing.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    if (friends.isEmpty()) {
        IgCard { IgInfo(L("Waiting for your friends list…")) }
        return
    }
    val grouped = friends.groupBy { it.presence }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((presence, label) in PRESENCE_ORDER) {
            val group = grouped[presence].orEmpty()
            if (group.isEmpty()) continue
            val collapsed = presence in collapsedSections
            item(key = "hdr_$presence") {
                LabFriendsHeader(L(label), group.size, collapsed) { SteamFriendsStore.setSectionCollapsed(presence, !collapsed) }
            }
            if (!collapsed) {
                items(group, key = { it.steamId }) { friend ->
                    LabFriendRow(friend, unread[friend.steamId] ?: 0, (typingMap[friend.steamId] ?: 0L) > nowMs) { onOpen(friend) }
                }
            }
        }
    }
}

@Composable
private fun LabFriendsHeader(label: String, count: Int, collapsed: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .deckFocusRing(SimpleShape, scaleTo = 1.01f)
            .clip(SimpleShape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text("${label.uppercase()}  $count", color = SimpleMuted, fontFamily = NunitoSansFamily, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
        Icon(if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, contentDescription = if (collapsed) L("Show") else L("Hide"), tint = SimpleMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun LabFriendRow(friend: SteamFriendsStore.SteamFriend, unread: Int, typing: Boolean, onOpen: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .deckFocusRing(SimpleShape, scaleTo = 1.01f)
            .clip(SimpleShape)
            .background(deckCardFill(), SimpleShape)
            .border(1.dp, SimpleLine, SimpleShape)
            .clickable(onClick = onOpen)
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box {
            FriendAvatar(friend = friend, size = 34.dp)
            Box(Modifier.align(Alignment.BottomEnd).size(10.dp).background(dotColor(friend.presence), CircleShape).border(1.5.dp, SimpleCard, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(friend.displayName, color = SimpleStrong, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (typing) L("typing…") else friend.statusText,
                color = if (typing) accent else dotColor(friend.presence),
                fontFamily = NunitoSansFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (unread > 0) {
            Spacer(Modifier.width(8.dp))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = 20.dp).widthIn(min = 20.dp).background(Color(0xFFE53935), CircleShape).padding(horizontal = 6.dp)) {
                Text(if (unread > 99) "99+" else "$unread", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private val LAB_CHAT_TIME = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
private val LAB_CHAT_URL = Regex("""https?://\S+""")

/** The whole message is one image link (as the Friends screen treats it): show the picture. */
private fun labChatImageUrl(text: String): String? {
    val t = text.trim()
    val url = LAB_CHAT_URL.find(t)?.value ?: return null
    if (t != url) return null
    val bare = url.substringBefore('?').lowercase()
    val isImgExt = bare.endsWith(".jpg") || bare.endsWith(".jpeg") || bare.endsWith(".png") || bare.endsWith(".gif") || bare.endsWith(".webp")
    val isSteamImg = url.contains("steamusercontent.com") || url.contains("steamcdn") || url.contains("/ugc/")
    return if (isImgExt || isSteamImg) url else null
}

@Composable
private fun LabChatThread(friend: SteamFriendsStore.SteamFriend, onBack: () -> Unit) {
    val menuOpen by XServerDialogState.menuOpen.collectAsState()
    val session by SteamFriendsStore.chat.collectAsState()
    val self by SteamFriendsStore.self.collectAsState()
    val messages = if (session.steamId == friend.steamId) session.messages else emptyList()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = MaterialTheme.colorScheme.primary

    // Active only while the menu is open; closed on the way out so a message that lands while it is shut is unread + notifies.
    LaunchedEffect(friend.steamId, menuOpen) {
        if (menuOpen) {
            SteamFriendsStore.openChat(friend.steamId)
        } else {
            SteamFriendsStore.closeChat()
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    DisposableEffect(friend.steamId) { onDispose { SteamFriendsStore.closeChat() } }

    val typingMap by SteamFriendsStore.typing.collectAsState()
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1000) } }
    val isTyping = (typingMap[friend.steamId] ?: 0L) > nowMs

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IgButton(null, Icons.AutoMirrored.Outlined.ArrowBack, onBack)
            Spacer(Modifier.width(10.dp))
            FriendAvatar(friend = friend, size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(friend.displayName, color = SimpleStrong, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (isTyping) L("typing…") else friend.statusText,
                    color = if (isTyping) accent else dotColor(friend.presence),
                    fontFamily = NunitoSansFamily, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxWidth().background(deckCardFill(), SimpleShape).border(1.dp, SimpleLine, SimpleShape)) {
            if (messages.isEmpty()) {
                Text(L("No messages yet. Say hello!"), color = SimpleMuted, fontFamily = NunitoSansFamily, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(messages) { _, msg -> LabChatBubble(msg, friend, self) }
                }
            }
        }
        // Text only in-game; the soft keyboard attaches to this field like any other field in the app.
        var draft by remember(friend.steamId) { mutableStateOf("") }
        val send = {
            val body = draft.trim()
            if (body.isNotEmpty()) {
                SteamFriendsStore.sendMessage(friend.steamId, body)
                draft = ""
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            IgTextField(
                value = draft,
                onValueChange = { draft = it; if (it.isNotEmpty()) SteamFriendsStore.sendTyping(friend.steamId) },
                modifier = Modifier.weight(1f),
                placeholder = L("Message"),
                singleLine = false,
                maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            Spacer(Modifier.width(8.dp))
            IgButton(null, Icons.AutoMirrored.Outlined.Send, { send() }, primary = draft.isNotBlank(), enabled = draft.isNotBlank())
        }
    }
}

@Composable
private fun LabChatBubble(msg: SteamFriendsStore.ChatMessage, friend: SteamFriendsStore.SteamFriend, self: SteamFriendsStore.SteamFriend?) {
    val cs = MaterialTheme.colorScheme
    val mine = msg.fromSelf
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) {
            FriendAvatar(friend = friend, size = 28.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            Text(
                if (mine) (self?.displayName ?: L("You")) else friend.displayName,
                color = SimpleMuted, fontFamily = NunitoSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp),
            )
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .background(if (mine) cs.primary else SimpleCard, SimpleShape)
                    .then(if (mine) Modifier else Modifier.border(1.dp, SimpleLine, SimpleShape))
                    .padding(horizontal = 11.dp, vertical = 7.dp),
            ) {
                val imageUrl = remember(msg.text) { labChatImageUrl(msg.text) }
                if (imageUrl != null) {
                    coil.compose.AsyncImage(
                        model = imageUrl,
                        contentDescription = L("Image"),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.widthIn(max = 240.dp).heightIn(max = 240.dp).clip(SimpleShape),
                    )
                } else {
                    Text(msg.text, color = if (mine) cs.onPrimary else SimpleStrong, fontFamily = NunitoSansFamily, fontSize = 14.sp, lineHeight = 19.sp)
                }
                if (msg.timestampSec > 0) {
                    Text(
                        LAB_CHAT_TIME.format(java.util.Date(msg.timestampSec * 1000L)),
                        color = (if (mine) cs.onPrimary else SimpleMuted).copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp).align(if (mine) Alignment.End else Alignment.Start),
                    )
                }
            }
        }
        if (mine && self != null) {
            Spacer(Modifier.width(8.dp))
            FriendAvatar(friend = self, size = 28.dp)
        }
    }
}
