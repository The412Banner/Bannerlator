package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    private var viewMode by mutableStateOf("grid")

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
                        val db = SteamRepository.getInstance().database
                        db.markUninstalled(game.appId)
                        if (game.installDir.isNotEmpty()) {
                            Thread { java.io.File(game.installDir).deleteRecursively() }.start()
                        }
                        loadGames()
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
                            StarLaunchBridge.addToLauncher(
                                this@SteamGamesActivity, data.gameName, chosen, data.coverUrl
                            )
                        },
                    )
                }
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
                statusText = if (phase == 0) "Syncing packages ($count)\u2026" else "Fetching $count app records\u2026"
            }
            event.startsWith("LibrarySynced:") -> {
                loadGames()
                statusText = "${games.size} games in library"
            }
            event == "LoggedOut" -> { finish() }
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
            android.widget.Toast.makeText(this, "Install directory not set", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val installDir = java.io.File(game.installDir)
        Thread {
            val exeFiles = mutableListOf<java.io.File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "No .exe found in install directory", android.widget.Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val lowerTitle = game.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }
            val coverUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${game.appId}/library_600x900.jpg"

            if (exeFiles.size == 1) {
                runOnUiThread { StarLaunchBridge.addToLauncher(this, game.name, exeFiles[0].absolutePath, coverUrl) }
                return@Thread
            }
            val candidates = exeFiles.map { it.absolutePath }
            runOnUiThread {
                showExePicker = SteamExePickerData(game.name, candidates, coverUrl)
            }
        }.start()
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
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onViewToggle: () -> Unit,
    onLogout: () -> Unit,
    onGameClick: (SteamGame) -> Unit,
    onUninstall: (SteamGame) -> Unit,
    onLaunch: (SteamGame) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("\u2190", color = Color(0xFF0055FF), fontSize = 18.sp) }
            Text(
                text = "Steam Library",
                fontSize = 18.sp,
                color = Color(0xFF0055FF),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Button(
                onClick = onViewToggle,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                Text(
                    if (viewMode == "grid") "\u2630" else "\u25A6",
                    color = Color.White, fontSize = 16.sp,
                )
            }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = onRefresh,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("Refresh", color = Color.White, fontSize = 13.sp) }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) { Text("Logout", color = Color.White, fontSize = 13.sp) }
        }

        // Status bar
        Text(
            text = statusText,
            fontSize = 12.sp,
            color = Color(0xFF0055FF),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )

        // Content
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (games.isEmpty() && !isLoading) {
                Text(
                    text = "No games found.\nIf sync just finished, tap Refresh.",
                    fontSize = 14.sp,
                    color = Color(0xFFAAAAAA),
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
                    color = Color(0xFF0055FF),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(Color.Black)
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover art
        GameCoverArt(
            appId = game.appId,
            modifier = Modifier.width(80.dp).height(140.dp),
        )

        // Info
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = game.name.ifEmpty { "App ${game.appId}" },
                fontSize = 14.sp,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (game.developer.isNotEmpty()) {
                Text(game.developer, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (game.genres.isNotEmpty()) {
                Text(game.genres, fontSize = 11.sp, color = Color(0xFFAAAAAA), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (game.sizeBytes > 0) {
                Text(fmtSize(game.sizeBytes), fontSize = 11.sp, color = Color(0xFFAAAAAA),
                    modifier = Modifier.padding(top = 2.dp))
            }
            if (game.metacriticScore > 0) {
                Text(
                    text = "Metacritic: ${game.metacriticScore}",
                    fontSize = 11.sp,
                    color = when {
                        game.metacriticScore >= 75 -> Color(0xFF4CAF50)
                        game.metacriticScore >= 50 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (game.isInstalled) {
                Text(
                    text = "\u25CF Installed",
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(Modifier.height(3.dp))
                Button(
                    onClick = onUninstall,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("Uninstall", color = Color.White, fontSize = 11.sp) }
                Spacer(Modifier.height(3.dp))
                Button(
                    onClick = onLaunch,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0055FF)),
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) { Text("Launch / Add to Shortcuts", color = Color.White, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun GameCoverArt(appId: Int, modifier: Modifier = Modifier) {
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
        modifier = modifier.background(Color.Black),
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
            Text("\u00d7", color = Color(0xFF666666), fontSize = 24.sp)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color(0xFF0055FF),
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
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, Color(0xFF0055FF).copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                GameCoverArt(
                    appId = game.appId,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(Color(0x44000000), Color(0xEE000000)),
                        ),
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    text = game.name.ifEmpty { "App ${game.appId}" },
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            Column {
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
