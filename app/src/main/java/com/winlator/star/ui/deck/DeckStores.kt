package com.winlator.star.ui.deck

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.container.Shortcut
import com.winlator.star.linux.LinuxShortcuts
import com.winlator.star.store.AmazonCredentialStore
import com.winlator.star.store.AmazonMainActivity
import com.winlator.star.store.DownloadManagerActivity
import com.winlator.star.store.EpicCredentialStore
import com.winlator.star.store.EpicMainActivity
import com.winlator.star.store.GogMainActivity
import com.winlator.star.store.SteamMainActivity
import com.winlator.star.store.SteamPrefs
import com.winlator.star.store.SteamTab
import com.winlator.star.ui.Screen
import com.winlator.star.ui.screens.ShortcutsViewModel
import com.winlator.star.ui.screens.isAmazonShortcut
import com.winlator.star.ui.screens.isGogShortcut
import com.winlator.star.ui.screens.isSteamOriginShortcut
import com.winlator.star.ui.theme.AppThemeState

/** The four storefronts, in the order the drawer lists them. [screen] is what MainActivity.launchStore takes. */
private enum class DeckStore(val label: String, val screen: Screen, val owns: (Shortcut) -> Boolean) {
    STEAM("Steam", Screen.Steam, { s -> isSteamOriginShortcut(s) && !LinuxShortcuts.isLinuxEntry(s) }),
    EPIC("Epic Games", Screen.Epic, { s -> s.getExtra("storeSource") == "epic" }),
    GOG("GOG", Screen.Gog, { s -> isGogShortcut(s) }),
    AMAZON("Amazon Games", Screen.Amazon, { s -> isAmazonShortcut(s) }),
}

/** A store's second-level tabs. Friends is Steam-only. */
private enum class StoreTab(val label: String, val icon: ImageVector) {
    LIBRARY("Library", Icons.Filled.VideoLibrary),
    STORE("Store", Icons.Filled.ShoppingBag),
    FRIENDS("Friends", Icons.Filled.People),
    PROFILE("Profile", Icons.Filled.AccountCircle),
    DOWNLOADS("Downloads", Icons.Filled.Download),
}

private fun tabsOf(store: DeckStore): List<StoreTab> =
    if (store == DeckStore.STEAM) StoreTab.entries else StoreTab.entries.filter { it != StoreTab.FRIENDS }

private fun isSignedIn(context: Context, store: DeckStore): Boolean = runCatching {
    when (store) {
        DeckStore.STEAM -> { SteamPrefs.init(context); SteamPrefs.isLoggedIn }
        DeckStore.EPIC -> EpicCredentialStore.isLoggedIn(context)
        DeckStore.GOG -> context.getSharedPreferences("bh_gog_prefs", 0).getString("access_token", null) != null
        DeckStore.AMAZON -> AmazonCredentialStore.isLoggedIn(context)
    }
}.getOrDefault(false)

/**
 * The store's own activity opened straight on [tab]. Steam takes a [SteamTab] name; Epic, GOG and
 * Amazon take an index into their tab row (Store 0, Library 1, Profile 2). Downloads is the shared
 * download manager. A signed-out user lands on that store's sign-in first, as from the drawer.
 */
private fun storeIntent(context: Context, store: DeckStore, tab: StoreTab): Intent {
    if (tab == StoreTab.DOWNLOADS) return Intent(context, DownloadManagerActivity::class.java)
    val index = when (tab) { StoreTab.STORE -> 0; StoreTab.PROFILE -> 2; else -> 1 }
    return when (store) {
        DeckStore.STEAM -> Intent(context, SteamMainActivity::class.java).putExtra(
            SteamMainActivity.EXTRA_TAB,
            when (tab) {
                StoreTab.STORE -> SteamTab.STORE
                StoreTab.FRIENDS -> SteamTab.FRIENDS
                StoreTab.PROFILE -> SteamTab.PROFILE
                else -> SteamTab.LIBRARY
            }.name,
        )
        DeckStore.EPIC -> Intent(context, EpicMainActivity::class.java).putExtra(EpicMainActivity.EXTRA_TAB, index)
        DeckStore.GOG -> Intent(context, GogMainActivity::class.java).putExtra(GogMainActivity.EXTRA_TAB, index)
        DeckStore.AMAZON -> Intent(context, AmazonMainActivity::class.java).putExtra(AmazonMainActivity.EXTRA_TAB, index)
    }
}

private fun playtimeText(context: Context, s: Shortcut): String {
    val ms = context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE).getLong("${s.name}_playtime", 0L)
    val minutes = ms / 60_000L
    return when {
        ms <= 0L -> "Not played yet"
        minutes < 60L -> "${minutes.coerceAtLeast(1L)} min played"
        else -> "%.1f h played".format(ms / 3_600_000.0)
    }
}

/**
 * Stores tab: a switcher for the four storefronts (with their sign-in state) and, per store, the
 * Library / Store / Friends / Profile / Downloads strip. Library is native: the store's installed
 * games from the same list Home uses, each opening its Deck game page. Store, Friends, Profile and
 * Downloads explain what is there and open the store's own screen on that tab. Honours Appearance →
 * "Show game stores".
 */
@Composable
internal fun DeckStoresPage(
    onLaunchStore: (Screen) -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenGame: (String) -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val gutter = deckGutter()
    val compact = deckCompact()
    val showStores by AppThemeState.showStores.collectAsState()
    val vm: ShortcutsViewModel = viewModel()
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())

    var storeIndex by rememberSaveable { mutableIntStateOf(0) }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val store = DeckStore.entries[storeIndex.coerceIn(0, DeckStore.entries.size - 1)]
    val tabs = tabsOf(store)
    val tab = tabs[tabIndex.coerceIn(0, tabs.size - 1)]

    // Sign-in state is re-read whenever the app comes back, since the sign-in screens are other activities.
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumeTick++; vm.refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val signedIn = remember(resumeTick) { DeckStore.entries.associateWith { isSignedIn(context, it) } }

    // X / Start = store options.
    var optionsOpen by remember { mutableStateOf(false) }
    val deckActions = LocalDeckActions.current
    DisposableEffect(deckActions, showStores) {
        val open: () -> Unit = { optionsOpen = true }
        if (showStores) {
            deckActions.options.value = open
            deckActions.optionsLabel.value = "Store options"
        }
        onDispose { if (deckActions.options.value === open) deckActions.options.value = null }
    }

    if (!showStores) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = 16.dp),
        ) {
            DeckPageHeader(Icons.Filled.Storefront, "Stores", "Game stores are hidden. Turn on \"Show game stores\" in Appearance to list them here.")
            DeckCard {
                DeckRow(title = "Appearance", subtitle = "Show or hide the game stores", icon = Icons.Filled.Palette, onClick = onOpenAppearance)
            }
        }
        return
    }

    DeckPageTitle("Stores", "Your storefronts: sign in, see what each one has installed here, and open its store, friends and downloads.")
    Column(modifier = Modifier.fillMaxSize().padding(top = 14.dp)) {
        // Store switcher, with the open / more buttons on the right (under it on a phone).
        val switcher: @Composable () -> Unit = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
            ) {
                DeckStore.entries.forEachIndexed { i, st ->
                    DeckChoiceChip(
                        label = st.label,
                        selected = i == storeIndex,
                        dot = if (signedIn[st] == true) cs.primary else cs.outline,
                        trailing = if (signedIn[st] == true) null else "Sign in",
                    ) { if (i != storeIndex) { storeIndex = i; tabIndex = 0 } }
                }
            }
        }
        val storeButtons: @Composable () -> Unit = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(4.dp)) {
                DeckButton("Open ${store.label}", Icons.AutoMirrored.Filled.OpenInNew, { context.startActivity(storeIntent(context, store, tab)) })
                DeckButton("More", Icons.Filled.MoreHoriz, { optionsOpen = true }, glyph = "X")
            }
        }
        if (compact) {
            Column(modifier = Modifier.padding(horizontal = gutter - 4.dp)) {
                switcher()
                storeButtons()
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = gutter - 4.dp)) {
                Box(modifier = Modifier.weight(1f)) { switcher() }
                storeButtons()
            }
        }
        Spacer(Modifier.height(12.dp))
        DeckTabStrip(
            tabs = tabs.map { DeckTabItem(it.label, it.icon) },
            selected = tabs.indexOf(tab),
            onSelect = { tabIndex = it },
            label = "Store tab",
            modifier = Modifier.padding(horizontal = gutter),
        )
        Spacer(Modifier.height(10.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val isIn = signedIn[store] == true
            when (tab) {
                StoreTab.LIBRARY -> StoreLibrary(
                    store = store,
                    games = remember(shortcuts, store) { shortcuts.filter(store.owns).sortedBy { it.name.lowercase() } },
                    signedIn = isIn,
                    onOpenGame = onOpenGame,
                    onSignIn = { onLaunchStore(store.screen) },
                    onBrowseAll = { context.startActivity(storeIntent(context, store, StoreTab.LIBRARY)) },
                )
                else -> StoreHandOff(
                    store = store,
                    tab = tab,
                    signedIn = isIn || tab == StoreTab.DOWNLOADS,
                    onOpen = { context.startActivity(storeIntent(context, store, tab)) },
                    onSignIn = { onLaunchStore(store.screen) },
                )
            }
        }
    }

    if (optionsOpen) {
        StoreOptionsDialog(
            store = store,
            signedIn = signedIn[store] == true,
            onDismiss = { optionsOpen = false },
            onOpenTab = { t -> optionsOpen = false; context.startActivity(storeIntent(context, store, t)) },
            onSignIn = { optionsOpen = false; onLaunchStore(store.screen) },
            onAppearance = { optionsOpen = false; onOpenAppearance() },
        )
    }
}

/** Library tab: the store's games that are already on Home, as a cover grid, plus the store's full library. */
@Composable
private fun StoreLibrary(
    store: DeckStore,
    games: List<Shortcut>,
    signedIn: Boolean,
    onOpenGame: (String) -> Unit,
    onSignIn: () -> Unit,
    onBrowseAll: () -> Unit,
) {
    val context = LocalContext.current
    val gutter = deckGutter()
    val compact = deckCompact()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = if (compact) 108.dp else 150.dp),
        contentPadding = PaddingValues(start = gutter, end = gutter, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            DeckCard {
                if (!signedIn) {
                    DeckRow(
                        title = "Sign in to ${store.label}",
                        subtitle = "Your library, downloads and cloud saves need a signed-in account.",
                        icon = Icons.AutoMirrored.Filled.Login,
                        onClick = onSignIn,
                    )
                    DeckRowDivider()
                }
                DeckRow(
                    title = "Browse your full ${store.label} library",
                    subtitle = if (games.isEmpty()) "Nothing from ${store.label} is installed yet. Install a game there and it shows up here and on Home."
                               else "${games.size} installed here. Install more from the store's own library.",
                    icon = Icons.Filled.VideoLibrary,
                    onClick = onBrowseAll,
                )
            }
        }
        items(games, key = { it.file.path }) { s ->
            StoreGameCard(s, playtimeText(context, s)) { onOpenGame(s.file.path) }
        }
    }
}

@Composable
private fun StoreGameCard(shortcut: Shortcut, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "deckStoreCardScale")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(
                    if (focused) Modifier
                        .shadow(18.dp, shape, ambientColor = cs.primary, spotColor = cs.primary)
                        .border(3.dp, cs.primary, shape)
                    else Modifier.shadow(6.dp, shape)
                )
                .clip(shape)
                .background(cs.surfaceVariant),
        ) {
            val bmp = shortcut.icon
            if (bmp != null) {
                val img = remember(bmp) { bmp.asImageBitmap() }
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = cs.primary, modifier = Modifier.size(40.dp))
            }
        }
        Text(
            text = shortcut.name,
            color = if (focused) cs.primary else cs.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        Text(subtitle, color = cs.onSurfaceVariant, fontSize = 12.5.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

/** What each hand-off tab holds, in the words the store screens use. */
private fun handOffText(store: DeckStore, tab: StoreTab): Pair<String, List<String>> = when (tab) {
    StoreTab.STORE -> "Browse the ${store.label} store" to listOf(
        "Featured games, deals and search",
        "Game pages with media, requirements and price",
        "Buy or claim, then install straight into a container",
    )
    StoreTab.FRIENDS -> "Steam friends and chat" to listOf(
        "Who is online and what they are playing",
        "Chat, invites and joining a friend's game",
    )
    StoreTab.PROFILE -> "Your ${store.label} account" to listOf(
        "Avatar, name and account details",
        "Store settings, cloud saves and sign-out",
    )
    else -> "Downloads" to listOf(
        "Every store download and component download in one queue",
        "Pause, resume, cancel and re-order",
    )
}

/**
 * Store, Friends, Profile and Downloads: a Deck panel saying what is behind the tab, and a button
 * that opens the store's own screen on it (these need the store backends, so they stay native to
 * the store activities for now).
 */
@Composable
private fun StoreHandOff(
    store: DeckStore,
    tab: StoreTab,
    signedIn: Boolean,
    onOpen: () -> Unit,
    onSignIn: () -> Unit,
) {
    val gutter = deckGutter()
    val (title, lines) = handOffText(store, tab)
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = gutter, vertical = 8.dp),
    ) {
        DeckCard(modifier = Modifier.widthIn(max = 900.dp)) {
            DeckRow(title = title, subtitle = lines.joinToString(" · "), icon = tab.icon)
            DeckRowDivider()
            if (signedIn) {
                DeckRow(
                    title = if (tab == StoreTab.DOWNLOADS) "Open downloads" else "Open ${store.label} ${tab.label}",
                    subtitle = "Opens the ${store.label} screen on this tab. Back returns here.",
                    trailing = { DeckButton("Open", Icons.AutoMirrored.Filled.OpenInNew, onOpen, primary = true) },
                    onClick = onOpen,
                )
            } else {
                DeckRow(
                    title = "Sign in to ${store.label}",
                    subtitle = "You'll come back here once you're signed in.",
                    trailing = { DeckButton("Sign in", Icons.AutoMirrored.Filled.Login, onSignIn, primary = true) },
                    onClick = onSignIn,
                )
            }
        }
    }
}

/** X on the Stores tab: jump into any of the store's screens, sign in, or hide the stores. */
@Composable
private fun StoreOptionsDialog(
    store: DeckStore,
    signedIn: Boolean,
    onDismiss: () -> Unit,
    onOpenTab: (StoreTab) -> Unit,
    onSignIn: () -> Unit,
    onAppearance: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(cs.surfaceContainerHigh)
                .padding(vertical = 14.dp),
        ) {
            Text(
                text = "${store.label} options",
                color = cs.onSurface,
                fontFamily = SoraFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (!signedIn) DeckRow(title = "Sign in to ${store.label}", icon = Icons.AutoMirrored.Filled.Login, onClick = onSignIn)
                tabsOf(store).filter { it != StoreTab.LIBRARY }.forEach { t ->
                    DeckRow(title = "Open ${t.label}", icon = t.icon, onClick = { onOpenTab(t) })
                }
                DeckRow(title = "Open full library", icon = Icons.Filled.VideoLibrary, onClick = { onOpenTab(StoreTab.LIBRARY) })
                DeckRow(title = "Hide game stores", subtitle = "Appearance → Show game stores", icon = Icons.Filled.Palette, onClick = onAppearance)
            }
        }
    }
}
