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

/** Epic library state for the storefront host — the twin of [rememberGogLibrary]. */
class EpicLibraryState internal constructor(
    val games: List<EpicGame>,
    val installedIds: Set<String>,
    /** Catalog item ids of everything owned — what store offers are matched against. */
    val ownedItemIds: Set<String>,
    val ownedNamespaces: Set<String>,
    val installedNamespaces: Set<String>,
    val isSyncing: Boolean,
    val statusText: String,
    val refresh: (force: Boolean) -> Unit,
    val reload: () -> Unit,
)

@Composable
fun rememberEpicLibrary(onMessage: (String) -> Unit = {}): EpicLibraryState {
    val ctx = LocalContext.current
    val appCtx = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var games by remember { mutableStateOf<List<EpicGame>>(emptyList()) }
    var installed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var syncing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    val load: () -> Unit = {
        games = EpicLibraryRepo.cached(appCtx)
        installed = EpicLibraryRepo.installedIds(appCtx)
        if (!syncing) status = if (games.isEmpty()) "" else "${games.size} game${if (games.size == 1) "" else "s"} · ${installed.size} installed"
    }

    val sync: (Boolean) -> Unit = { force ->
        if (!syncing) {
            syncing = true
            status = "Syncing library…"
            scope.launch {
                val result = EpicLibraryRepo.sync(appCtx, force) { s -> status = s }
                syncing = false
                when (result) {
                    is EpicLibraryRepo.SyncResult.Ok -> {
                        load()
                        if (force) onMessage("Library updated — ${result.games.size} game${if (result.games.size == 1) "" else "s"}")
                    }
                    is EpicLibraryRepo.SyncResult.Failed -> { load(); onMessage(result.message) }
                    EpicLibraryRepo.SyncResult.NotLoggedIn -> { load(); onMessage("Session expired — please sign in to Epic again") }
                    EpicLibraryRepo.SyncResult.Busy, EpicLibraryRepo.SyncResult.Throttled -> load()
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
        withContext(Dispatchers.IO) { runCatching { EpicLibrarySync.seed(appCtx) } }
        load()
        if (EpicCredentialStore.isLoggedIn(appCtx)) sync(false)
    }

    val ownedItemIds = remember(games) { games.mapNotNullTo(HashSet()) { it.catalogItemId.takeIf { id -> id.isNotBlank() } } }
    val ownedNamespaces = remember(games) { games.mapNotNullTo(HashSet()) { it.namespace.takeIf { ns -> ns.isNotBlank() } } }
    val installedNamespaces = remember(games, installed) {
        games.filter { it.appName in installed }.mapNotNullTo(HashSet()) { it.namespace.takeIf { ns -> ns.isNotBlank() } }
    }

    return EpicLibraryState(
        games = games,
        installedIds = installed,
        ownedItemIds = ownedItemIds,
        ownedNamespaces = ownedNamespaces,
        installedNamespaces = installedNamespaces,
        isSyncing = syncing,
        statusText = status,
        refresh = sync,
        reload = load,
    )
}

/** The Library tab body: filter chips, a search box and the adaptive 2:3 tile grid. */
@Composable
fun EpicLibraryTab(
    state: EpicLibraryState,
    wide: Boolean,
    onOpen: (EpicGame) -> Unit,
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
            (!installedOnly || g.appName in state.installedIds) &&
                (q.isEmpty() || g.title.lowercase().contains(q))
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        CatalogSearchField(
            query = query,
            placeholder = "Search your Epic library…",
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
                StoreNotice(title = "Syncing your library", body = state.statusText.ifBlank { "Fetching your Epic games…" })
            }

            state.games.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                StoreNotice(
                    title = "Your Epic library is empty here",
                    body = "Sync to pull your games from Epic, or open the full library screen.",
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
                items(shown, key = { it.appName }) { g ->
                    val eos = remember(g.appName) { runCatching { EpicEosDetector.isEosCached(ctx, g.appName) }.getOrDefault(false) }
                    CatalogLibraryTile(
                        item = EpicLibraryRepo.toCatalogItem(g),
                        installed = g.appName in state.installedIds,
                        onOpen = { onOpen(g) },
                        badgeText = if (eos) "EOS" else null,
                    )
                }
                item(key = "full_library_footer", span = { GridItemSpan(maxLineSpan) }) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        StoreNotice(
                            title = "Full library",
                            body = "Need the list view or in-list installs? Open the full library screen.",
                            actionLabel = "Open full library",
                            onAction = onOpenFullLibrary,
                        )
                    }
                }
            }
        }
    }
}
