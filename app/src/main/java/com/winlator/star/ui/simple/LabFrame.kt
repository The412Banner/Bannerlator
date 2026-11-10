package com.winlator.star.ui.simple

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.store.SteamFriendsActivity
import com.winlator.star.store.SteamFriendsStore
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.ui.deck.DeckInput
import com.winlator.star.ui.deck.DeckStore
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.deck.isSignedIn
import com.winlator.star.ui.screens.ShortcutActionDialogs
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.rememberShortcutActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt

/** One storage volume: internal storage, an SD card or a USB drive. */
internal class LabVolume(val label: String, val usedGb: Float, val totalGb: Float, val removable: Boolean) {
    val fraction: Float get() = if (totalGb > 0f) usedGb / totalGb else 0f
}

/** The device's live numbers the Lab frame and home show. Read every 20 s, so a drive plugged in shows up on its own. */
internal class LabDeviceInfo(
    val battery: Int,
    val tempC: Int?,
    val ramUsedGb: Float,
    val ramTotalGb: Float,
    val diskUsedGb: Float,
    val diskTotalGb: Float,
    val time: String,
    /** Internal storage first, then every SD card and USB drive that is mounted (the Classic drawer's StorageRoots). */
    val volumes: List<LabVolume>,
)

internal fun readLabDevice(context: Context): LabDeviceInfo {
    val battery = batteryPercent(context)
    val temp = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it > 0 }?.let { (it / 10f).roundToInt() }
    }.getOrNull()
    val mem = ActivityManager.MemoryInfo()
    runCatching { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem) }
    val gb = 1024f * 1024f * 1024f
    val disk = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
    val total = disk?.totalBytes ?: 0L
    val free = disk?.availableBytes ?: 0L
    return LabDeviceInfo(
        battery = battery,
        tempC = temp,
        ramUsedGb = (mem.totalMem - mem.availMem) / gb,
        ramTotalGb = mem.totalMem / gb,
        diskUsedGb = (total - free) / gb,
        diskTotalGb = total / gb,
        time = android.text.format.DateFormat.getTimeFormat(context).format(Date()),
        volumes = buildList {
            if (total > 0) add(LabVolume(L("Internal"), (total - free) / gb, total / gb, removable = false))
            runCatching { com.winlator.star.core.StorageRoots.list(context) }.getOrDefault(emptyList())
                .filter { it.removable }
                .forEach { root ->
                    runCatching { StatFs(root.dir.path) }.getOrNull()?.let { st ->
                        // A volume that cannot be read reports 0 B; better no line than "0 / 0 GB".
                        if (st.totalBytes > 0) add(LabVolume(root.label, (st.totalBytes - st.availableBytes) / gb, st.totalBytes / gb, removable = true))
                    }
                }
        },
    )
}

@Composable
internal fun rememberLabDevice(): LabDeviceInfo? {
    val context = LocalContext.current
    var info by remember { mutableStateOf<LabDeviceInfo?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            info = withContext(Dispatchers.IO) { runCatching { readLabDevice(context) }.getOrNull() }
            delay(20_000)
        }
    }
    return info
}

internal fun fmtGb(v: Float): String = if (v >= 100f) "${v.roundToInt()}" else String.format(java.util.Locale.ENGLISH, "%.1f", v)

/** A thin progress bar in the accent colour. */
@Composable
internal fun LabBar(fraction: Float, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.height(5.dp).background(SimpleLine, SimpleShape)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(accent, SimpleShape))
    }
}

/** Landscape: the section rail down the left, icons with short labels. */
@Composable
internal fun LabRail(current: Int, onSection: (Int) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(SimplePanelBrush, RectangleShape)
            .drawBehind { drawRect(SimpleLineDraw, topLeft = androidx.compose.ui.geometry.Offset(size.width - 1f, 0f), size = Size(1f, size.height)) }
            .padding(vertical = 14.dp),
    ) {
        LAB_SECTIONS.forEachIndexed { i, s ->
            val on = i == current
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier
                    .width(66.dp)
                    .then(if (on) Modifier.background(accent.copy(alpha = 0.16f), SimpleShape) else Modifier)
                    .labTile(onClick = { onSection(i) })
                    .padding(vertical = 7.dp),
            ) {
                Icon(s.icon, contentDescription = L(s.label), tint = if (on) accent else SimpleMuted, modifier = Modifier.size(21.dp))
                Text(L(s.short), color = if (on) SimpleStrong else SimpleMuted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** Kept apart so the draw lambda reads it at draw time. */
private val SimpleLineDraw: Color get() = SimpleLine

/** What the drawer asks of the top line, which stays on screen when the drawer closes: open the Community configs browser. */
internal object LabTopRequests {
    var community by mutableStateOf(false)
}

/** The shell's search, as Y opens it: the page's own search where it has one, else the search overlay. */
internal fun labOpenSearch() {
    DeckInput.handler?.invoke(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y))
}

/** Opens Steam friends and chat (the Steam store's own screen). */
internal fun labOpenFriends(context: Context) {
    runCatching { context.startActivity(Intent(context, SteamFriendsActivity::class.java)) }
}

/** Whether Steam is signed in (friends and chat need it), read again whenever the app comes back. */
@Composable
internal fun rememberSteamSignedIn(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return remember(tick) { isSignedIn(context, DeckStore.STEAM) }
}

/** Unread Steam chats and friend requests, as the Classic and Deck friends button counts them. */
@Composable
internal fun rememberSteamUnread(): Int {
    val unread by SteamFriendsStore.unread.collectAsState()
    val incoming by SteamFriendsStore.incomingRequests.collectAsState()
    val social by SteamFriendsStore.socialEnabled.collectAsState()
    return if (social) unread.values.sum() + incoming.size else 0
}

/** A square icon button of the top line, with a count over its corner when [badge] > 0. */
@Composable
private fun LabTopIcon(icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).labPanel().labTile(onClick = onClick)) {
        Icon(icon, contentDescription = label, tint = SimpleInk, modifier = Modifier.size(22.dp))
        if (badge > 0) {
            Text(
                if (badge > 99) "99+" else "$badge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).background(MaterialTheme.colorScheme.error, CircleShape).padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * The slim line over every Lab page: back, (portrait) the menu button, the name, then Search (Y's), and in landscape
 * Community configs and Steam friends (signed in only; in portrait they are in the drawer), then battery, temperature
 * and clock. Landscape also shows the device's name.
 */
@Composable
internal fun LabTopLine(portrait: Boolean, onMenu: () -> Unit, onBack: (() -> Unit)?, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    val dev = rememberLabDevice()
    val context = LocalContext.current
    // The Community configs browser and its flows are Classic's (ShortcutActionDialogs), mounted here so the drawer
    // can open it too: the top line stays on screen when the drawer closes.
    val vm: ShortcutsViewModel = viewModel()
    val actions = rememberShortcutActions(vm)
    LaunchedEffect(LabTopRequests.community) {
        if (LabTopRequests.community) { LabTopRequests.community = false; actions.showCommunityBrowser = true }
    }
    val steam = rememberSteamSignedIn()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().height(48.dp).padding(horizontal = if (portrait) 12.dp else 18.dp),
    ) {
        // Back on every page but Home (the system bars are hidden, so touch needs its own way back).
        if (onBack != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).labPanel().labTile(onClick = onBack),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L("Back"), tint = SimpleInk, modifier = Modifier.size(22.dp)) }
        }
        if (portrait) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(40.dp).labPanel().labTile(onClick = onMenu),
            ) { Icon(Icons.Filled.Menu, contentDescription = L("Menu"), tint = SimpleInk, modifier = Modifier.size(22.dp)) }
        }
        // Portrait has no room for the device's name (the drawer shows it): the app's name takes the space.
        Text(
            "Bannerlator", color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = if (portrait) Modifier.weight(1f) else Modifier,
        )
        if (!portrait) Text(Build.MODEL ?: "", color = SimpleMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        LabTopIcon(Icons.Filled.Search, L("Search")) { onSearch?.invoke() ?: labOpenSearch() }
        if (!portrait) {
            LabTopIcon(Icons.Filled.Public, L("Community configs")) { actions.showCommunityBrowser = true }
            if (steam) LabTopIcon(Icons.Filled.People, L("Steam friends"), badge = rememberSteamUnread()) { labOpenFriends(context) }
        }
        if (dev != null) {
            if (dev.battery >= 0) Text("${dev.battery}%", color = SimpleMuted, fontSize = 13.sp)
            dev.tempC?.let { Text(L("%1\$s°C", it), color = SimpleMuted, fontSize = 13.sp) }
            Text(dev.time, color = SimpleInk, fontSize = 13.sp)
        }
        // Portrait shows it in the drawer's header instead.
        if (!portrait) LabAccountButton()
    }
    ShortcutActionDialogs(actions)
}

/**
 * The user's picture: the Community configs account's when signed in, else the signed-in Steam account's (live from
 * the friends connection, else the one saved with the account), else the sign-in figure. Opens My account, as
 * Classic's drawer header and the Deck top bar do.
 */
@Composable
internal fun LabAccountButton(onOpen: () -> Unit = {}) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { runCatching { com.winlator.star.ui.AccountUiBus.refresh(context) } }
    val account = com.winlator.star.ui.AccountUiBus.account
    val self by com.winlator.star.store.SteamFriendsStore.self.collectAsState()
    val saved = remember(self) {
        runCatching {
            val id = com.winlator.star.store.SteamAccountStore.activeSteamId(context)
            if (id == 0L) null else com.winlator.star.store.SteamAccountStore.list(context).firstOrNull { it.steamId64 == id }?.avatarUrl
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
    val url = account?.displayAvatarUrl?.takeIf { it.isNotBlank() } ?: self?.avatarUrl ?: saved
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).labTile(onClick = { onOpen(); com.winlator.star.ui.AccountUiBus.requestMyAccount() }),
    ) {
        com.winlator.star.ui.AccountAvatar(avatarUrl = url, size = 36.dp, fallbackTint = SimpleInk)
    }
}

/**
 * Portrait: the drawer. Its own window, so the controller stays in it until it closes (B, a tap outside, or a pick).
 * Device numbers on top, the sections, and any download in progress at the bottom.
 */
@Composable
internal fun LabDrawer(current: Int, onSection: (Int) -> Unit, onClose: () -> Unit) {
    // Slides in from the left with the shade fading in behind it, and back out on B, a tap outside or the back gesture;
    // the shell is told it closed once the slide-out has finished.
    val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
    val close = { shown.targetState = false }
    LaunchedEffect(shown.isIdle, shown.currentState, shown.targetState) {
        if (shown.isIdle && !shown.currentState && !shown.targetState) onClose()
    }
    Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = close,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val dev = rememberLabDevice()
        val downloads by ContentDownloadRegistry.states.collectAsState()
        val active = downloads.values.firstOrNull { !it.terminal }
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
        BackHandler { close() }
        Box(Modifier.fillMaxSize()) {
            androidx.compose.animation.AnimatedVisibility(
                visibleState = shown,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)),
            ) {
                Box(Modifier.fillMaxSize().background(Color(0x66000000)).simpleTap(close))
            }
            androidx.compose.animation.AnimatedVisibility(
                visibleState = shown,
                enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it },
                exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.FastOutLinearInEasing)) { -it },
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        // Slightly see-through: the page shows faintly behind it.
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                        .border(1.dp, SimpleLine)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 18.dp),
                ) {
                    Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Bannerlator", color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                            Spacer(Modifier.width(8.dp))
                            // Classic's Steam connection status (only while signed in; tap when offline to retry).
                            com.winlator.star.store.SteamConnectionPill()
                            Spacer(Modifier.weight(1f))
                            LabAccountButton(onOpen = onClose)
                        }
                        Text(Build.MODEL ?: "", color = SimpleMuted, fontSize = 12.sp)
                        if (dev != null) {
                            val showInternal by com.winlator.star.ui.theme.AppThemeState.showInternalStorage.collectAsState()
                            val showSd by com.winlator.star.ui.theme.AppThemeState.showSdStorage.collectAsState()
                            Spacer(Modifier.height(4.dp))
                            dev.volumes.filter { if (it.removable) showSd else showInternal }.forEach { v ->
                                Spacer(Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(L(v.label), color = SimpleMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(72.dp))
                                    LabBar(v.fraction, Modifier.weight(1f))
                                    Text(L("%1\$s / %2\$s GB", fmtGb(v.usedGb), fmtGb(v.totalGb)), color = SimpleMuted, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                listOfNotNull(
                                    if (dev.battery >= 0) "${dev.battery}%" else null,
                                    dev.tempC?.let { L("%1\$s°C", it) },
                                    L("RAM %1\$s / %2\$s GB", fmtGb(dev.ramUsedGb), fmtGb(dev.ramTotalGb)),
                                ).joinToString("  ·  "),
                                color = SimpleMuted, fontSize = 12.sp,
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SimpleLine))
                    Spacer(Modifier.height(6.dp))
                    LAB_SECTIONS.forEachIndexed { i, s ->
                        val on = i == current
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .then(if (on) Modifier.background(accent.copy(alpha = 0.16f), SimpleShape) else Modifier)
                                .labTile(onClick = { onSection(i) }, requester = if (on) first else null)
                                .padding(horizontal = 12.dp),
                        ) {
                            Icon(s.icon, contentDescription = null, tint = if (on) accent else SimpleMuted, modifier = Modifier.size(21.dp))
                            Text(L(s.label), color = if (on) SimpleStrong else SimpleInk, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                    // Classic's Community configs and Steam friends buttons (the top line has them in landscape).
                    val context = LocalContext.current
                    val steam = rememberSteamSignedIn()
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SimpleLine))
                    Spacer(Modifier.height(6.dp))
                    @Composable
                    fun extra(icon: ImageVector, label: String, badge: Int = 0, onClick: () -> Unit) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp).labTile(onClick = onClick).padding(horizontal = 12.dp),
                        ) {
                            Icon(icon, contentDescription = null, tint = SimpleMuted, modifier = Modifier.size(21.dp))
                            Text(label, color = SimpleInk, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            if (badge > 0) Text("$badge", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(MaterialTheme.colorScheme.error, CircleShape).padding(horizontal = 7.dp, vertical = 1.dp))
                        }
                    }
                    extra(Icons.Filled.Public, L("Community configs")) { LabTopRequests.community = true; close() }
                    if (steam) extra(Icons.Filled.People, L("Steam friends"), rememberSteamUnread()) { close(); labOpenFriends(context) }
                    if (active != null) {
                        Spacer(Modifier.height(14.dp))
                        Text(L("Downloading %1\$s · %2\$s%%", active.title, (active.fraction * 100).roundToInt()), color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp))
                        Spacer(Modifier.height(6.dp))
                        LabBar(active.fraction, Modifier.fillMaxWidth().padding(horizontal = 10.dp))
                    }
                }
            }
        }
    }
}

/**
 * Portrait: a swipe from the left edge to the right opens the drawer (as the ☰ button does). Only a drag that starts in
 * the edge strip and moves clearly sideways counts, so taps and scrolling near the edge are untouched. A band of the
 * edge is excluded from the system's back gesture on gesture-navigation devices, as Android's own drawers do.
 */
internal fun Modifier.labEdgeSwipe(onOpen: () -> Unit): Modifier = composed {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edge = with(density) { 24.dp.toPx() }
    val need = with(density) { 56.dp.toPx() }
    val bandTop = with(density) { 72.dp.toPx() }
    val bandHeight = with(density) { 200.dp.toPx() }
    val open by androidx.compose.runtime.rememberUpdatedState(onOpen)
    this
        .systemGestureExclusion { coords -> androidx.compose.ui.geometry.Rect(0f, bandTop, edge, (bandTop + bandHeight).coerceAtMost(coords.size.height.toFloat())) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.position.x > edge) return@awaitEachGesture
                var dx = 0f
                var dy = 0f
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val d = ch.positionChange()
                    dx += d.x
                    dy += d.y
                    if (dx > need && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                        ch.consume()
                        open()
                        break
                    }
                    if (kotlin.math.abs(dy) > need || dx < -edge) break
                }
            }
        }
}
