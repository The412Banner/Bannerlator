package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.star.store.compose.AddResultDialog
import com.winlator.star.store.compose.AddShortcutResult
import com.winlator.star.store.compose.AddToShortcutsRequest
import com.winlator.star.store.compose.ContainerPickerDialog
import com.winlator.star.store.compose.openShortcutsScreen
import com.winlator.star.store.download.DownloadsButton
import com.winlator.star.ui.theme.WinlatorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class SteamGamesActivity : ComponentActivity(), SteamRepository.SteamEventListener {

    private var games by mutableStateOf<List<SteamGame>>(emptyList())
    private var statusText by mutableStateOf("Loading library\u2026")
    private var isLoading by mutableStateOf(true)
    private var showSignOutDialog by mutableStateOf(false)
    private var showExePicker by mutableStateOf<SteamExePickerData?>(null)
    private var addToShortcuts by mutableStateOf<AddToShortcutsRequest?>(null)
    private var addResult by mutableStateOf<AddShortcutResult?>(null)
    private var viewMode by mutableStateOf("grid")
    private var steamStatus by mutableStateOf(SteamRepository.getInstance().status)
    // Non-null while an uninstall is deleting files → shows the blocking progress spinner.
    private var uninstallingName by mutableStateOf<String?>(null)
    // Non-null briefly after an uninstall → themed auto-dismiss confirmation bar (not a Toast).
    private var uninstallResult by mutableStateOf<String?>(null)

    private val imageCache = object : LruCache<Int, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val imageExecutor = Executors.newFixedThreadPool(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)

        setContent {
            WinlatorTheme {
                SteamGamesScreen(
                    games = games,
                    statusText = statusText,
                    isLoading = isLoading,
                    viewMode = viewMode,
                    steamStatus = steamStatus,
                    onReconnect = { SteamRepository.getInstance().reconnectNow() },
                    onBack = { finish() },
                    onRefresh = { SteamRepository.getInstance().syncLibrary() },
                    onViewToggle = {
                        viewMode = if (viewMode == "list") "grid" else "list"
                    },
                    onLogout = { showSignOutDialog = true },
                    onGameClick = { game ->
                        startActivity(Intent(this@SteamGamesActivity, SteamGameDetailActivity::class.java)
                            .putExtra(SteamGameDetailActivity.EXTRA_APP_ID, game.appId))
                    },
                    onUninstall = { game ->
                        uninstallingName = game.name
                        StoreUninstaller.run(
                            installDir = game.installDir,
                            mark = { SteamRepository.getInstance().database.markUninstalled(game.appId) },
                        ) { ok ->
                            uninstallingName = null
                            uninstallResult = if (ok) "${game.name} uninstalled" else "Couldn't fully remove ${game.name}"
                            loadGames()
                        }
                    },
                    onLaunch = { game -> launchInstalledGame(game) },
                )

                if (showSignOutDialog) {
                    AlertDialog(
                        onDismissRequest = { showSignOutDialog = false },
                        title = { Text("Sign out of Steam?") },
                        text = { Text("Your saved login will be removed. You will need to sign in again.") },
                        confirmButton = {
                            TextButton(onClick = {
                                showSignOutDialog = false
                                Thread { SteamRepository.getInstance().logout() }.start()
                            }) { Text("Sign Out") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
                        },
                    )
                }

                showExePicker?.let { data ->
                    ExePickerDialog(
                        title = "Select executable for \"${data.gameName}\"",
                        candidates = data.candidates,
                        onDismiss = { showExePicker = null },
                        onSelected = { chosen ->
                            showExePicker = null
                            startAddToShortcuts(data.gameName, chosen, data.coverUrl)
                        },
                    )
                }

                addToShortcuts?.let { req ->
                    ContainerPickerDialog(
                        gameName = req.gameName,
                        containers = req.containers,
                        onDismiss = { addToShortcuts = null },
                        onSelected = { container ->
                            addToShortcuts = null
                            StarLaunchBridge.writeShortcutAsync(
                                this@SteamGamesActivity, container,
                                req.gameName, req.exePath, req.coverUrl,
                            ) { success, message ->
                                addResult = AddShortcutResult(req.gameName, success, message)
                            }
                        },
                    )
                }

                addResult?.let { result ->
                    AddResultDialog(
                        result = result,
                        onOpenShortcuts = {
                            addResult = null
                            openShortcutsScreen(this@SteamGamesActivity)
                        },
                        onDismiss = { addResult = null },
                    )
                }

                uninstallingName?.let { UninstallProgressDialog(it) }
                uninstallResult?.let { UninstallResultBar(it) { uninstallResult = null } }
            }
        }

        SteamRepository.getInstance().addListener(this)
        loadGames()
        maybeAutoSync()
    }

    override fun onResume() {
        super.onResume()
        loadGames()
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        super.onDestroy()
    }

    override fun onEvent(event: String) {
        when {
            event.startsWith("LibraryProgress:") -> {
                val parts = event.split(":")
                val phase = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val count = parts.getOrNull(2)?.toIntOrNull() ?: 0
                // Phase 2 carries a 4th field (running total) so the app-record sync \u2014 now fetched in
                // small batches (SteamRepository.requestNextAppBatch) \u2014 counts up as "N/372".
                val total = parts.getOrNull(3)?.toIntOrNull() ?: 0
                statusText = when {
                    phase == 0            -> "Syncing packages ($count)\u2026"
                    phase == 2 && total > 0 -> "Fetching app records ($count/$total)\u2026"
                    else                  -> "Fetching $count app records\u2026"
                }
            }
            event.startsWith("LibrarySynced:") -> {
                loadGames()
                statusText = "${games.size} games in library"
            }
            event.startsWith("SteamStatus:") -> {
                val name = event.substringAfter("SteamStatus:")
                steamStatus = try { SteamRepository.SteamStatus.valueOf(name) } catch (e: Exception) { steamStatus }
            }
            event == "LoggedOut" -> {
                // Only bounce to sign-in on a real sign-out. On a different-client replacement we keep
                // the screen so the "Signed in elsewhere" pill stays tappable to reconnect.
                if (SteamRepository.getInstance().status != SteamRepository.SteamStatus.SIGNED_IN_ELSEWHERE) finish()
            }
            event == "Disconnected" -> { statusText = "Disconnected \u2014 reconnecting\u2026" }
            event == "Connected" -> {
                val repo = SteamRepository.getInstance()
                if (games.isEmpty() && repo.isLoggedIn) {
                    statusText = "Reconnected \u2014 syncing library\u2026"
                    repo.syncLibrary()
                }
            }
        }
    }

    private fun loadGames() {
        val repo = try {
            SteamRepository.getInstance()
        } catch (e: IllegalStateException) {
            startActivity(Intent(this, SteamMainActivity::class.java))
            finish()
            return
        }
        val rows = repo.getCachedGameRows()
        games = rows
            .filter { it.type == "game" }
            .map { SteamGame.fromGameRow(it) }
            .sortedBy { it.name.lowercase() }
        isLoading = false
        if (games.isNotEmpty()) {
            statusText = "${games.size} games in library"
        }
    }

    private fun maybeAutoSync() {
        val repo = SteamRepository.getInstance()
        if (!repo.isLoggedIn) return
        val staleThresholdSec = 4 * 60 * 60L
        val elapsed = System.currentTimeMillis() / 1000L - repo.lastSyncTime
        if (games.isEmpty() || elapsed > staleThresholdSec) {
            statusText = if (games.isEmpty()) "Syncing library\u2026" else "Refreshing library\u2026"
            repo.syncLibrary()
        }
    }

    private fun launchInstalledGame(game: SteamGame) {
        if (game.installDir.isEmpty()) {
            uninstallResult = "Install directory not set"
            return
        }
        val installDir = java.io.File(game.installDir)
        Thread {
            val exeFiles = mutableListOf<java.io.File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                runOnUiThread {
                    uninstallResult = "No .exe found in install directory"
                }
                return@Thread
            }
            val lowerTitle = game.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }
            val coverUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${game.appId}/library_600x900.jpg"

            if (exeFiles.size == 1) {
                runOnUiThread { startAddToShortcuts(game.name, exeFiles[0].absolutePath, coverUrl) }
                return@Thread
            }
            val candidates = exeFiles.map { it.absolutePath }
            runOnUiThread {
                showExePicker = SteamExePickerData(game.name, candidates, coverUrl)
            }
        }.start()
    }

    /** Compose add-to-shortcuts flow: load containers, then show the M3 picker. */
    private fun startAddToShortcuts(gameName: String, exePath: String, coverUrl: String?) {
        StarLaunchBridge.loadContainers(this) { containers ->
            addToShortcuts = AddToShortcutsRequest(gameName, exePath, coverUrl, containers)
        }
    }
}

private data class SteamExePickerData(
    val gameName: String,
    val candidates: List<String>,
    val coverUrl: String,
)

@Composable
private fun SteamGamesScreen(
    games: List<SteamGame>,
    statusText: String,
    isLoading: Boolean,
    viewMode: String,
    steamStatus: SteamRepository.SteamStatus,
    onReconnect: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onViewToggle: () -> Unit,
    onLogout: () -> Unit,
    onGameClick: (SteamGame) -> Unit,
    onUninstall: (SteamGame) -> Unit,
    onLaunch: (SteamGame) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = "Steam Library",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            SteamStatusPill(status = steamStatus, onReconnect = onReconnect)
            IconButton(onClick = onViewToggle) {
                Icon(
                    imageVector = if (viewMode == "grid") Icons.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = if (viewMode == "grid") "List view" else "Grid view",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            DownloadsButton()
            IconButton(onClick = onLogout) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Logout,
                    contentDescription = "Sign out",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Status line
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // Content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (games.isEmpty() && !isLoading) {
                Text(
                    text = "No games found.\nIf sync just finished, tap Refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            } else {
                if (viewMode == "grid") {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(games, key = { it.appId }) { game ->
                            GameGridTile(
                                game = game,
                                onClick = { onGameClick(game) },
                            )
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(games, key = { it.appId }) { game ->
                            GameListItem(
                                game = game,
                                onClick = { onGameClick(game) },
                                onUninstall = { onUninstall(game) },
                                onLaunch = { onLaunch(game) },
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun GameListItem(
    game: SteamGame,
    onClick: () -> Unit,
    onUninstall: () -> Unit,
    onLaunch: () -> Unit,
) {
    // Floating card matching the Containers/Shortcuts list idiom (rounded surfaceVariant
    // panel + outline border + side margins) with a tall poster tile for the game art.
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        ) {
            // 3:4 poster cover tile
            GameCoverArt(
                appId = game.appId,
                modifier = Modifier
                    .size(width = 60.dp, height = 80.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.name.ifEmpty { "App ${game.appId}" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (game.developer.isNotEmpty()) {
                    Text(
                        text = game.developer,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (game.genres.isNotEmpty()) {
                    Text(
                        text = game.genres,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (game.sizeBytes > 0) {
                    Text(
                        text = fmtSize(game.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (game.metacriticScore > 0) {
                    Text(
                        text = "Metacritic: ${game.metacriticScore}",
                        style = MaterialTheme.typography.bodySmall,
                        // Semantic review-score colours, deliberately not themed.
                        color = when {
                            game.metacriticScore >= 75 -> Color(0xFF4CAF50)
                            game.metacriticScore >= 50 -> Color(0xFFFFC107)
                            else -> Color(0xFFF44336)
                        },
                    )
                }
                if (game.isInstalled) {
                    Text(
                        text = "\u25CF Installed",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF4CAF50), // semantic installed-green
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onLaunch,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) { Text("Launch / Add", style = MaterialTheme.typography.labelSmall) }
                        OutlinedButton(
                            onClick = onUninstall,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                        ) { Text("Uninstall", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

// internal (not private): the cross-store Download Manager (store.download package) reuses
// this exact Steam poster loader for its list cards, so a downloading/installed row looks
// identical to a Library row. Steam art is resolved from the appId.
@Composable
internal fun GameCoverArt(appId: Int, modifier: Modifier = Modifier) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(appId) {
        loaded = false
        bitmap = withContext(Dispatchers.IO) {
            tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/library_600x900.jpg")
                ?: tryBitmap("https://shared.steamstatic.com/store_item_assets/steam/apps/$appId/header.jpg")
        }
        loaded = true
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (loaded) {
            Text(
                text = "\u00d7",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun GameGridTile(
    game: SteamGame,
    onClick: () -> Unit,
) {
    // Grid tile matching the Shortcuts game cards: rounded cover card with an outline
    // border and a bottom gradient scrim carrying the name.
    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        GameCoverArt(
            appId = game.appId,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    // Scrim sits over cover art, so it stays black/white regardless of theme.
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = game.name.ifEmpty { "App ${game.appId}" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private suspend fun tryBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 10_000
        conn.connect()
        if (conn.responseCode == 200)
            BitmapFactory.decodeStream(conn.inputStream)
        else null
    } catch (_: Exception) { null }
}

@Composable
private fun ExePickerDialog(
    title: String,
    candidates: List<String>,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // Long lists (e.g. HL2's dozens of bin/*.exe SDK tools) must scroll or the game exe
            // below the fold is unreachable. Cap at ~half the screen height so it fits + scrolls in
            // both portrait and the much shorter landscape.
            val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
            Column(
                modifier = Modifier
                    .heightIn(max = maxListHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                candidates.forEach { path ->
                    val f = java.io.File(path)
                    val parent = f.parentFile
                    val label = if (parent != null) "${parent.name}/${f.name}" else f.name
                    TextButton(
                        onClick = { onSelected(path) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(label, modifier = Modifier.weight(1f)) }
                }
            }
        },
        confirmButton = {},
    )
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    else                    -> "%.0f KB".format(bytes / 1024.0)
}
