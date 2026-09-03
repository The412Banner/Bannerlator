package com.winlator.star.store

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.winlator.star.store.download.DownloadsButton
import com.winlator.star.ui.theme.LocalAccentDim
import com.winlator.star.ui.theme.WinlatorTheme

/**
 * The Steam section's host — flipped from library-first to **store-first**.
 *
 * Was: a splash that immediately bounced to [SteamGamesActivity] or [SteamLoginActivity]. Now: the
 * login gate stays, but a signed-in user lands on a four-tab shell — **Store / Library / Friends /
 * Profile** — with the Store as the front door.
 *
 * ## Portrait vs landscape
 * These are gaming handhelds; landscape is arguably the primary orientation, so it is a designed
 * layout rather than a reflow. [rememberStorefrontLayout] resolves Material's real width breakpoint
 * from the live configuration:
 *  - **Compact width / portrait** → a top [TabRow], content beneath it.
 *  - **Landscape at Medium width or wider** → a left [NavigationRail]; every tab then switches to
 *    its own landscape layout (two-column Store and Profile, master-detail Friends, more grid
 *    columns in the Library).
 * Rotation just recomposes — the manifest gives this Activity `configChanges`, so the selected tab,
 * search text and scroll positions survive it.
 *
 * ## Gamepad / D-pad
 * The rail and every card carry a visible accent focus ring ([steamFocusRing]) because these are
 * controller devices. The rail is one focus group whose RIGHT lands in the content pane
 * ([contentFocus]); inside the content, lists and grids are focus groups so traversal reads
 * naturally (RIGHT = next card, DOWN = that card's action, LEFT back out to the rail).
 */
class SteamMainActivity : ComponentActivity() {

    companion object {
        private const val REQ_NOTIFICATIONS = 1001

        /** Intent extra: open straight onto a tab. One of [SteamTab]'s names. */
        const val EXTRA_TAB = "steam_tab"
    }

    /** Login state as Compose state so returning from the login flow re-renders without a restart. */
    private val loggedIn = mutableStateOf(false)
    private val startTab = mutableStateOf(SteamTab.STORE)

    /** True once the sign-in screen has been launched, so backing out of it closes this host
     *  instead of stranding the user on the boot spinner. */
    private var launchedLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)
        // Load the persisted social opt-in so the Friends tab and the settings cog agree from frame 1.
        SteamFriendsStore.init(this)

        applyIntent(intent)

        setContent {
            WinlatorTheme {
                if (loggedIn.value) {
                    SteamStorefrontHost(
                        initialTab = startTab.value,
                        onSignedOut = { loggedIn.value = false; finish() },
                        onClose = { finish() },
                    )
                } else {
                    SteamBootScreen()
                }
            }
        }

        if (needsNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        } else {
            proceed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
        // A login activity finishing back into us re-checks the gate.
        loggedIn.value = runCatching { SteamPrefs.isLoggedIn }.getOrDefault(loggedIn.value)
    }

    override fun onResume() {
        super.onResume()
        // The login screens finish() back onto this Activity, so the gate is re-read here rather
        // than only at onCreate.
        val signedIn = runCatching { SteamPrefs.isLoggedIn }.getOrDefault(loggedIn.value)
        loggedIn.value = signedIn
        if (!signedIn && launchedLogin) finish()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS) proceed()
    }

    private fun applyIntent(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_TAB) ?: return
        startTab.value = runCatching { SteamTab.valueOf(name) }.getOrDefault(SteamTab.STORE)
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * The login gate, unchanged in behaviour: start the Steam foreground service, then either show
     * the storefront (signed in) or hand off to the sign-in screen. The difference is that this
     * Activity now STAYS on the stack as the storefront rather than finishing into
     * [SteamGamesActivity].
     */
    private fun proceed() {
        SteamForegroundService.start(this)
        if (SteamPrefs.isLoggedIn) {
            StorefrontLog.i(StorefrontLog.HOST, "signed in — opening the storefront on ${startTab.value.name}")
            loggedIn.value = true
        } else {
            StorefrontLog.i(StorefrontLog.HOST, "not signed in — handing off to SteamLoginActivity")
            launchedLogin = true
            startActivity(Intent(this, SteamLoginActivity::class.java))
        }
    }
}

/** The four tabs, in order. [count] is filled at render time (library size / friends online). */
enum class SteamTab(val label: String, val icon: ImageVector) {
    STORE("Store", Icons.Filled.Storefront),
    LIBRARY("Library", Icons.Filled.VideoLibrary),
    FRIENDS("Friends", Icons.Filled.People),
    PROFILE("Profile", Icons.Filled.AccountCircle),
}

/** Pre-storefront spinner: shown for the frame or two before the login gate resolves. */
@Composable
private fun SteamBootScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * The tab shell itself. Holds the one [rememberSteamLibrary] state, so the Library grid and the
 * Store's "do I own this?" check read the same rows and a granted free license lights up in both.
 */
@Composable
fun SteamStorefrontHost(
    initialTab: SteamTab = SteamTab.STORE,
    onSignedOut: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val layout = rememberStorefrontLayout()
    val library = rememberSteamLibrary()
    val friends by SteamFriendsStore.friends.collectAsState()

    var tab by remember { mutableStateOf(initialTab) }
    // Tab transitions at info level: enough to reconstruct a session from the log.
    LaunchedEffect(tab) { StorefrontLog.i(StorefrontLog.HOST, "tab -> ${tab.name}") }
    var libraryViewMode by remember { mutableStateOf("grid") }
    var message by remember { mutableStateOf<String?>(null) }
    var showSignOut by remember { mutableStateOf(false) }

    // The rail's RIGHT target: focus lands in the content pane rather than wrapping around the
    // rail, so a D-pad user goes rail → content in one press.
    val contentFocus = remember { FocusRequester() }

    val onlineFriends = friends.count { it.presence != SteamFriendsStore.Presence.OFFLINE }
    val counts = mapOf(
        SteamTab.LIBRARY to library.games.size,
        SteamTab.FRIENDS to onlineFriends,
    )

    // Back leaves the Steam section from the Store tab; from anywhere else it returns to Store
    // first, so the hardware back button never dumps a user out of a tab they navigated into.
    BackHandler(enabled = true) {
        if (tab == SteamTab.STORE) onClose() else tab = SteamTab.STORE
    }

    val content: @Composable (Modifier) -> Unit = { mod ->
        when (tab) {
            SteamTab.STORE -> SteamStoreTab(
                wide = layout.wide,
                ownedAppIds = library.ownedAppIds,
                onOpenApp = { openSteamGameDetail(ctx, it) },
                onMessage = { message = it },
                onLibraryChanged = library.reload,
                modifier = mod,
            )
            SteamTab.LIBRARY -> SteamLibraryTab(
                state = library,
                wide = layout.wide,
                viewMode = libraryViewMode,
                onOpenApp = { openSteamGameDetail(ctx, it) },
                onMessage = { message = it },
                modifier = mod,
            )
            SteamTab.FRIENDS -> SteamFriendsTab(
                wide = layout.wide,
                onMessage = { message = it },
                modifier = mod,
            )
            SteamTab.PROFILE -> SteamProfileTab(
                wide = layout.wide,
                libraryCount = library.games.size,
                onOpenFriends = { tab = SteamTab.FRIENDS },
                onOpenApp = { openSteamGameDetail(ctx, it) },
                onMessage = { message = it },
                modifier = mod,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // The Steam activities are not edge-to-edge, so systemBars is usually zero — but the
            // display cutout is NOT, and in landscape on a handheld it eats the rail. Both, always.
            .systemBarsPadding()
            .displayCutoutPadding(),
    ) {
        if (layout.wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                StorefrontRail(
                    tab = tab,
                    counts = counts,
                    onSelect = { tab = it },
                    library = library,
                    viewMode = libraryViewMode,
                    onToggleView = { libraryViewMode = if (libraryViewMode == "grid") "list" else "grid" },
                    onSignOut = { showSignOut = true },
                    contentFocus = contentFocus,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        // Requester BEFORE the focus target: the rail's
                        // `focusProperties { right = contentFocus }` resolves against the group
                        // that follows it, so this order is load-bearing, not cosmetic.
                        .focusRequester(contentFocus)
                        .focusGroup(),
                ) { content(Modifier.fillMaxSize()) }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                StorefrontTopBar(
                    library = library,
                    viewMode = libraryViewMode,
                    onToggleView = { libraryViewMode = if (libraryViewMode == "grid") "list" else "grid" },
                    onSignOut = { showSignOut = true },
                )
                StorefrontTabRow(tab = tab, counts = counts, onSelect = { tab = it })
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .focusRequester(contentFocus)
                        .focusGroup(),
                ) { content(Modifier.fillMaxSize()) }
            }
        }

        if (showSignOut) {
            SteamSignOutDialog(
                onDismiss = { showSignOut = false },
                onConfirm = {
                    showSignOut = false
                    StorefrontLog.i(StorefrontLog.HOST, "sign-out confirmed — clearing the saved session")
                    Thread { runCatching { SteamRepository.getInstance().logout() } }.start()
                    onSignedOut()
                },
            )
        }

        // Drawn last so it floats over whichever tab is showing. System Toasts render as an empty
        // black box on this ROM, so every transient message goes through this bar.
        message?.let { StoreMessageBar(it) { message = null } }
    }
}

// ── Navigation chrome ─────────────────────────────────────────────────────────────────────────

/** Portrait: the prototype's underlined tab strip. The indicator is the live accent. */
@Composable
private fun StorefrontTabRow(
    tab: SteamTab,
    counts: Map<SteamTab, Int>,
    onSelect: (SteamTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = tab.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
    ) {
        SteamTab.values().forEach { t ->
            Tab(
                selected = tab == t,
                onClick = { onSelect(t) },
                // Rectangular ring: a tab fills its slot, so a rounded ring would float oddly.
                modifier = Modifier.steamFocusRing(RoundedCornerShape(0.dp)),
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.label, fontWeight = FontWeight.Bold, maxLines = 1)
                        counts[t]?.takeIf { it > 0 }?.let {
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "($it)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
        }
    }
}

/**
 * Landscape: a Material [NavigationRail] on the left. Selected item = accent icon/label over the
 * dim-accent indicator ([LocalAccentDim]), so the whole rail follows a user-picked accent.
 *
 * The rail is one focus group, and `focusProperties { right = contentFocus }` is set on it so a
 * D-pad RIGHT anywhere in the rail enters the content pane instead of dead-ending. Coming back is
 * left to the default 2D focus search, which finds the rail without an explicit override that
 * could trap focus inside a pane.
 */
@Composable
private fun StorefrontRail(
    tab: SteamTab,
    counts: Map<SteamTab, Int>,
    onSelect: (SteamTab) -> Unit,
    library: SteamLibraryState,
    viewMode: String,
    onToggleView: () -> Unit,
    onSignOut: () -> Unit,
    contentFocus: FocusRequester,
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        // The host root already applies systemBars + displayCutout padding; NavigationRail's
        // default windowInsets would inset a second time and shove the rail down the screen.
        windowInsets = WindowInsets(0),
        modifier = Modifier
            .fillMaxHeight()
            .focusGroup()
            .focusProperties { right = contentFocus },
        header = {
            Spacer(Modifier.height(4.dp))
            SteamStatusPill(status = library.steamStatus, onReconnect = library.reconnect)
        },
    ) {
        Spacer(Modifier.height(6.dp))
        SteamTab.values().forEach { t ->
            NavigationRailItem(
                selected = tab == t,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon, contentDescription = t.label) },
                label = {
                    val n = counts[t]?.takeIf { it > 0 }
                    Text(
                        text = if (n != null) "${t.label} ($n)" else t.label,
                        maxLines = 1,
                        fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                alwaysShowLabel = true,
                modifier = Modifier.steamFocusRing(),
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = LocalAccentDim.current,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        // The action icons the old Steam Library header carried, stacked into the rail's tail so
        // landscape keeps every one of them without a second bar eating vertical space.
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StorefrontActions(
                library = library,
                viewMode = viewMode,
                onToggleView = onToggleView,
                onSignOut = onSignOut,
                vertical = true,
            )
        }
    }
}

/** Portrait: the action strip that used to live in the Steam Library header, right-aligned. */
@Composable
private fun StorefrontTopBar(
    library: SteamLibraryState,
    viewMode: String,
    onToggleView: () -> Unit,
    onSignOut: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Steam",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        SteamStatusPill(status = library.steamStatus, onReconnect = library.reconnect)
        StorefrontActions(
            library = library,
            viewMode = viewMode,
            onToggleView = onToggleView,
            onSignOut = onSignOut,
            vertical = false,
        )
    }
}

/**
 * Refresh / Save Manager / Downloads / Friends-and-chat settings / Sign out — the five actions the
 * old header had, laid out horizontally in portrait and stacked in the rail in landscape.
 */
@Composable
private fun StorefrontActions(
    library: SteamLibraryState,
    viewMode: String,
    onToggleView: () -> Unit,
    onSignOut: () -> Unit,
    vertical: Boolean,
) {
    val ctx = LocalContext.current
    var showSocialSettings by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary

    val items: @Composable () -> Unit = {
        IconButton(onClick = onToggleView, modifier = Modifier.steamFocusRing()) {
            Icon(
                imageVector = if (viewMode == "grid") Icons.Filled.ViewList else Icons.Filled.GridView,
                contentDescription = if (viewMode == "grid") "Library list view" else "Library grid view",
                tint = accent,
            )
        }
        IconButton(onClick = library.refresh, modifier = Modifier.steamFocusRing()) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh library", tint = accent)
        }
        IconButton(
            onClick = { runCatching { ctx.startActivity(Intent(ctx, SteamSaveManagerActivity::class.java)) } },
            modifier = Modifier.steamFocusRing(),
        ) {
            Icon(Icons.Filled.CloudSync, contentDescription = "Save Manager", tint = accent)
        }
        DownloadsButton()
        IconButton(onClick = { showSocialSettings = true }, modifier = Modifier.steamFocusRing()) {
            Icon(Icons.Filled.Settings, contentDescription = "Friends & chat settings", tint = accent)
        }
        IconButton(onClick = onSignOut, modifier = Modifier.steamFocusRing()) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out", tint = accent)
        }
    }

    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { items() }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) { items() }
    }

    if (showSocialSettings) {
        SteamSocialSettingsSheet(onDismiss = { showSocialSettings = false })
    }
}
