package com.winlator.star.store

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Amazon library state for the storefront host — the twin of [rememberGogLibrary]. */
class AmazonLibraryState internal constructor(
    val games: List<AmazonGame>,
    val installedIds: Set<String>,
    val updatableIds: Set<String>,
    val isSyncing: Boolean,
    val statusText: String,
    /** Bumped when a sync backfilled posters, so tiles re-read `amazon_vcover_`. */
    val posterTick: Int,
    val refresh: (force: Boolean) -> Unit,
    val reload: () -> Unit,
)

@Composable
fun rememberAmazonLibrary(onMessage: (String) -> Unit = {}): AmazonLibraryState {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<AmazonGame>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var posterTick by remember { mutableStateOf(0) }

    val load: () -> Unit = {
        games = AmazonLibraryRepo.cached(appCtx)
        installed = AmazonLibraryRepo.installedIds(appCtx)
        posterTick++
        if (!syncing) status = if (games.isEmpty()) "" else "${games.size} game${if (games.size == 1) "" else "s"} · ${installed.size} installed"
    }

    val sync: (Boolean) -> Unit = { force ->
        if (!syncing) {
            syncing = true
            status = "Syncing library…"
            scope.launch {
                val result = AmazonLibraryRepo.sync(appCtx, force) { s -> status = s }
                syncing = false
                when (result) {
                    is AmazonLibraryRepo.SyncResult.Ok -> {
                        load()
                        if (force) onMessage("Library updated — ${result.games.size} game${if (result.games.size == 1) "" else "s"}")
                    }
                    is AmazonLibraryRepo.SyncResult.Failed -> { load(); onMessage(result.message) }
                    AmazonLibraryRepo.SyncResult.NotLoggedIn -> { load(); onMessage("Please sign in to Amazon Games first") }
                    AmazonLibraryRepo.SyncResult.Busy, AmazonLibraryRepo.SyncResult.Throttled -> load()
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) load() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { runCatching { AmazonLibrarySync.seed(appCtx) } }
        load()
        if (AmazonCredentialStore.isLoggedIn(appCtx)) sync(false)
    }

    return AmazonLibraryState(
        games = games,
        installedIds = installed,
        updatableIds = remember(games) { AmazonLibraryRepo.updatableIds(games) },
        isSyncing = syncing,
        statusText = status,
        posterTick = posterTick,
        refresh = sync,
        reload = load,
    )
}

/** The Library tab body: filter chips, a search box and the adaptive 2:3 tile grid. */
@Composable
fun AmazonLibraryTab(
    state: AmazonLibraryState,
    wide: Boolean,
    onOpen: (AmazonGame) -> Unit,
    onOpenFullLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var installedOnly by remember { mutableStateOf(false) }

    val shown = remember(state.games, state.installedIds, query, installedOnly) {
        val q = query.trim().lowercase()
        state.games.filter { g ->
            (!installedOnly || g.productId in state.installedIds) &&
                (q.isEmpty() || g.title.lowercase().contains(q))
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CatalogSearchField(
            query = query,
            placeholder = "Search your Amazon library…",
            onQueryChange = { query = it },
            onClear = { query = ""; focusManager.clearFocus() },
            onSearch = { focusManager.clearFocus() },
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StoreFilterChip("All", !installedOnly) { installedOnly = false }
            StoreFilterChip("Installed", installedOnly) { installedOnly = true }
            Spacer(Modifier.weight(1f))
            Text(
                text = state.statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        when {
            state.games.isEmpty() && state.isSyncing -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StoreNotice(title = "Syncing your library", body = state.statusText.ifBlank { "Fetching your Amazon games…" })
            }

            state.games.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StoreNotice(
                    title = "Your Amazon library is empty here",
                    body = "Sync to pull your games from Amazon, or open the full library screen.",
                    actionLabel = "Sync now",
                    onAction = { state.refresh(true) },
                )
            }

            shown.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StoreNotice(title = "No matches", body = "Nothing in your library matches that filter.")
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(if (wide) 132.dp else 118.dp),
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.productId }) { g ->
                    val item = remember(g.productId, state.posterTick) { AmazonLibraryRepo.toCatalogItem(ctx, g) }
                    CatalogLibraryTile(
                        item = item,
                        installed = g.productId in state.installedIds,
                        onOpen = { onOpen(g) },
                        badgeText = if (g.productId in state.updatableIds) "Update" else null,
                    )
                }
                item(key = "full_library_footer", span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        StoreNotice(
                            title = "Full library",
                            body = "Need the list or poster view, or in-list installs? Open the full library screen.",
                            actionLabel = "Open full library",
                            onAction = onOpenFullLibrary,
                        )
                    }
                }
            }
        }
    }
}
