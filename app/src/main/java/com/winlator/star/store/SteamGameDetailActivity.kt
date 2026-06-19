package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.ui.theme.WinlatorTheme
import java.io.File
import java.net.URL

class SteamGameDetailActivity : ComponentActivity(), SteamRepository.SteamEventListener {

    companion object {
        const val EXTRA_APP_ID = "steam_app_id"
    }

    private val COLOR_INSTALL   = 0xFF1565C0.toInt()
    private val COLOR_CANCEL    = 0xFFCC3333.toInt()
    private val COLOR_UNINSTALL = 0xFFB71C1C.toInt()
    private val COLOR_LAUNCH    = 0xFF2E7D32.toInt()
    private val COLOR_PAUSE     = 0xFFE65100.toInt()
    private val COLOR_RESUME    = 0xFF2E7D32.toInt()

    private var appId: Int = 0
    private var game by mutableStateOf<SteamGame?>(null)

    @Volatile private var downloadHandle: SteamDepotDownloader.DownloadControl? = null
    private var lastThreadCount = 4

    // UI state
    private var headerBitmap by mutableStateOf<Bitmap?>(null)
    private var nameText by mutableStateOf("Loading\u2026")
    private var typeText by mutableStateOf("GAME")
    private var sizeText by mutableStateOf("Size unknown")
    private var statusText by mutableStateOf("Not installed")
    private var statusColor by mutableStateOf(0xFFAAAAAA.toInt())
    private var installBtnText by mutableStateOf("Install")
    private var installBtnColor by mutableIntStateOf(COLOR_INSTALL)
    private var installBtnEnabled by mutableStateOf(true)
    private var pauseBtnText by mutableStateOf("Pause")
    private var pauseBtnColor by mutableIntStateOf(COLOR_PAUSE)
    private var pauseBtnEnabled by mutableStateOf(false)
    private var pauseBtnAlpha by mutableStateOf(0.4f)
    private var launchBtnEnabled by mutableStateOf(false)
    private var launchBtnAlpha by mutableStateOf(0.4f)
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)
    private var progressText by mutableStateOf("")
    private var progressTextVisible by mutableStateOf(false)

    private var showSpeedPicker by mutableStateOf(false)
    private var showExePicker by mutableStateOf<ExePickerDataGame?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getIntExtra(EXTRA_APP_ID, 0)
        if (appId == 0) { finish(); return }

        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)

        setContent {
            WinlatorTheme {
                SteamGameDetailScreen(
                    headerBitmap = headerBitmap,
                    nameText = nameText,
                    typeText = typeText,
                    sizeText = sizeText,
                    statusText = statusText,
                    statusColor = statusColor,
                    installBtnText = installBtnText,
                    installBtnColor = installBtnColor,
                    installBtnEnabled = installBtnEnabled,
                    pauseBtnText = pauseBtnText,
                    pauseBtnColor = pauseBtnColor,
                    pauseBtnEnabled = pauseBtnEnabled,
                    pauseBtnAlpha = pauseBtnAlpha,
                    launchBtnEnabled = launchBtnEnabled,
                    launchBtnAlpha = launchBtnAlpha,
                    progressVisible = progressVisible,
                    progressValue = progressValue,
                    progressText = progressText,
                    progressTextVisible = progressTextVisible,
                    onBack = { finish() },
                    onInstallClick = { onInstallClicked() },
                    onPauseResumeClick = { onPauseResumeClicked() },
                    onLaunchClick = { onLaunchClicked() },
                )

                if (showSpeedPicker) {
                    DownloadSpeedPickerDialog(
                        selectedIndex = when (lastThreadCount) { 8 -> 1; 16 -> 2; else -> 0 },
                        onDismiss = { showSpeedPicker = false },
                        onDownload = { threadCount ->
                            showSpeedPicker = false
                            lastThreadCount = threadCount
                            installBtnEnabled = false
                            installBtnText = "Starting\u2026"
                            downloadHandle = SteamDepotDownloader.installApp(appId, applicationContext, lastThreadCount)
                        },
                    )
                }

                showExePicker?.let { data ->
                    ExePickerDialogGame(
                        gameName = data.gameName,
                        candidates = data.candidates,
                        onDismiss = { showExePicker = null },
                        onSelected = { chosen ->
                            showExePicker = null
                            StarLaunchBridge.addToLauncher(
                                this@SteamGameDetailActivity, data.gameName, chosen, data.coverUrl
                            )
                        },
                    )
                }
            }
        }

        SteamRepository.getInstance().addListener(this)
        loadGame()
        loadHeaderImage()
    }

    override fun onDestroy() {
        SteamRepository.getInstance().removeListener(this)
        super.onDestroy()
    }

    override fun onEvent(event: String) {
        when {
            event.startsWith("DownloadProgress:") -> {
                val parts = event.split(":")
                val id    = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val done  = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val total = parts.getOrNull(3)?.toLongOrNull() ?: 1L
                val pct   = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                progressVisible = true
                progressValue = pct
                progressTextVisible = true
                progressText = "Downloading\u2026 $pct%  (${fmtSize(done)} / ${fmtSize(total)})"
                installBtnEnabled = true
                installBtnText = "Cancel"
                installBtnColor = COLOR_CANCEL
                pauseBtnEnabled = true
                pauseBtnAlpha = 1f
                pauseBtnText = "Pause"
                pauseBtnColor = COLOR_PAUSE
            }
            event.startsWith("DownloadPaused:") -> {
                val id = event.substringAfter("DownloadPaused:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                val dlRow = SteamRepository.getInstance().database.getDownload(appId)
                val done  = dlRow?.bytesDownloaded ?: 0L
                val total = dlRow?.bytesTotal ?: 0L
                val pct   = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                progressVisible = true
                progressValue = pct
                progressTextVisible = true
                progressText = "Paused \u2014 $pct%  (${fmtSize(done)} / ${fmtSize(total)})"
                installBtnEnabled = true
                installBtnText = "Cancel"
                installBtnColor = COLOR_CANCEL
                pauseBtnEnabled = true
                pauseBtnAlpha = 1f
                pauseBtnText = "Resume"
                pauseBtnColor = COLOR_RESUME
            }
            event.startsWith("DownloadComplete:") -> {
                val id = event.substringAfter("DownloadComplete:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                progressVisible = false
                progressTextVisible = false
                resetPauseBtn()
                loadGame()
            }
            event.startsWith("DownloadCancelled:") -> {
                val id = event.substringAfter("DownloadCancelled:").toIntOrNull() ?: return
                if (id != appId) return
                downloadHandle = null
                progressVisible = false
                progressTextVisible = false
                statusText = "Download cancelled"
                statusColor = 0xFFAAAAAA.toInt()
                installBtnEnabled = true
                installBtnText = "Install"
                installBtnColor = COLOR_INSTALL
                resetPauseBtn()
            }
            event.startsWith("DownloadFailed:") -> {
                val parts = event.split(":")
                val id = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val reason = parts.drop(2).joinToString(":")
                val logPath = SteamDepotDownloader.debugLogPath
                downloadHandle = null
                progressVisible = false
                progressTextVisible = false
                statusText = "Download failed: $reason\nDebug log: $logPath"
                statusColor = 0xFFFF5555.toInt()
                installBtnEnabled = true
                installBtnText = "Retry"
                installBtnColor = COLOR_INSTALL
                resetPauseBtn()
            }
        }
    }

    private fun resetPauseBtn() {
        pauseBtnEnabled = false
        pauseBtnAlpha = 0.4f
        pauseBtnText = "Pause"
        pauseBtnColor = COLOR_PAUSE
    }

    private fun loadGame() {
        val row = SteamRepository.getInstance().database.getGame(appId) ?: run { finish(); return }
        game = SteamGame.fromGameRow(row)
        refreshUI()

        val dlRow = SteamRepository.getInstance().database.getDownload(appId)
        if (dlRow != null) {
            val pct = if (dlRow.bytesTotal > 0) (dlRow.bytesDownloaded * 100 / dlRow.bytesTotal).toInt().coerceIn(0, 100) else 0
            when (dlRow.status) {
                SteamDatabase.DL_DOWNLOADING -> {
                    if (SteamDepotDownloader.isDownloading(appId)) {
                        progressVisible = true
                        progressValue = pct
                        progressTextVisible = true
                        progressText = "Downloading\u2026 $pct%"
                        installBtnEnabled = true
                        installBtnText = "Cancel"
                        installBtnColor = COLOR_CANCEL
                        pauseBtnEnabled = true
                        pauseBtnAlpha = 1f
                        pauseBtnText = "Pause"
                        pauseBtnColor = COLOR_PAUSE
                    } else {
                        SteamRepository.getInstance().database.deleteDownload(appId)
                    }
                }
                SteamDatabase.DL_PAUSED -> {
                    progressVisible = true
                    progressValue = pct
                    progressTextVisible = true
                    progressText = "Paused \u2014 $pct%  (${fmtSize(dlRow.bytesDownloaded)} / ${fmtSize(dlRow.bytesTotal)})"
                    installBtnEnabled = true
                    installBtnText = "Cancel"
                    installBtnColor = COLOR_CANCEL
                    pauseBtnEnabled = true
                    pauseBtnAlpha = 1f
                    pauseBtnText = "Resume"
                    pauseBtnColor = COLOR_RESUME
                }
            }
        }
    }

    private fun refreshUI() {
        val g = game ?: return
        nameText = g.name.ifEmpty { "App ${g.appId}" }
        typeText = g.type.uppercase()
        sizeText = if (g.sizeBytes > 0) "~${fmtSize(g.sizeBytes)}" else "Size unknown"

        if (g.isInstalled) {
            statusText = "Installed"
            statusColor = 0xFF4CAF50.toInt()
            installBtnText = "Uninstall"
            installBtnColor = COLOR_UNINSTALL
            installBtnEnabled = true
            launchBtnEnabled = true
            launchBtnAlpha = 1f
        } else {
            statusText = "Not installed"
            statusColor = 0xFFAAAAAA.toInt()
            installBtnText = "Install"
            installBtnColor = COLOR_INSTALL
            installBtnEnabled = true
            launchBtnEnabled = false
            launchBtnAlpha = 0.4f
        }
    }

    private fun loadHeaderImage() {
        val g = game ?: return
        val url = g.headerUrl ?: return
        Thread {
            try {
                val bmp = BitmapFactory.decodeStream(URL(url).openStream())
                headerBitmap = bmp
            } catch (_: Exception) {}
        }.start()
    }

    private fun onInstallClicked() {
        val g = game ?: return

        val handle = downloadHandle
        if (handle != null) {
            val db = SteamRepository.getInstance().database
            val dir = db.getDownload(appId)?.installDir ?: ""
            handle.cancel.run()
            downloadHandle = null
            if (dir.isNotEmpty()) Thread { File(dir).deleteRecursively() }.start()
            progressVisible = false
            progressTextVisible = false
            statusText = "Download cancelled"
            statusColor = 0xFFAAAAAA.toInt()
            installBtnText = "Install"
            installBtnColor = COLOR_INSTALL
            installBtnEnabled = true
            resetPauseBtn()
            return
        }

        val db = SteamRepository.getInstance().database
        val dlRow = db.getDownload(appId)
        if (dlRow != null && dlRow.status == SteamDatabase.DL_PAUSED) {
            db.deleteDownload(appId)
            val dir = dlRow.installDir
            if (dir.isNotEmpty()) Thread { File(dir).deleteRecursively() }.start()
            progressVisible = false
            progressTextVisible = false
            statusText = "Download cancelled"
            statusColor = 0xFFAAAAAA.toInt()
            installBtnText = "Install"
            installBtnColor = COLOR_INSTALL
            installBtnEnabled = true
            resetPauseBtn()
            return
        }

        if (g.isInstalled) {
            SteamRepository.getInstance().database.markUninstalled(appId)
            if (g.installDir.isNotEmpty()) {
                Thread { File(g.installDir).deleteRecursively() }.start()
            }
            loadGame()
        } else {
            showSpeedPicker = true
        }
    }

    private fun onPauseResumeClicked() {
        val handle = downloadHandle
        if (handle != null) {
            handle.pause.run()
            downloadHandle = null
            pauseBtnText = "Resume"
            pauseBtnColor = COLOR_RESUME
            pauseBtnEnabled = true
            pauseBtnAlpha = 1f
            installBtnText = "Cancel"
            installBtnEnabled = true
            val cur = progressText
            if (cur.startsWith("Downloading")) progressText = cur.replace("Downloading", "Pausing")
        } else {
            val dlRow = SteamRepository.getInstance().database.getDownload(appId) ?: return
            if (dlRow.status != SteamDatabase.DL_PAUSED) return
            pauseBtnEnabled = false
            pauseBtnAlpha = 0.4f
            pauseBtnText = "Resuming\u2026"
            installBtnEnabled = false
            installBtnText = "Starting\u2026"
            downloadHandle = SteamDepotDownloader.resumeApp(appId, applicationContext, lastThreadCount)
        }
    }

    private fun onLaunchClicked() {
        val g = game ?: return
        if (!g.isInstalled || g.installDir.isEmpty()) {
            Toast.makeText(this, "Game not installed", Toast.LENGTH_SHORT).show()
            return
        }
        val installDir = File(g.installDir)
        Thread {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "No .exe found in install directory", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val lowerTitle = g.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }
            val coverUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${g.appId}/library_600x900.jpg"

            if (exeFiles.size == 1) {
                runOnUiThread { StarLaunchBridge.addToLauncher(this, g.name, exeFiles[0].absolutePath, coverUrl) }
                return@Thread
            }
            val candidates = exeFiles.map { it.absolutePath }
            runOnUiThread {
                showExePicker = ExePickerDataGame(g.name, candidates, coverUrl)
            }
        }.start()
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
        else                    -> "%.0f KB".format(bytes / 1024.0)
    }
}

private data class ExePickerDataGame(
    val gameName: String,
    val candidates: List<String>,
    val coverUrl: String,
)

// --- Composable Screens ---

@Composable
private fun SteamGameDetailScreen(
    headerBitmap: Bitmap?,
    nameText: String,
    typeText: String,
    sizeText: String,
    statusText: String,
    statusColor: Int,
    installBtnText: String,
    installBtnColor: Int,
    installBtnEnabled: Boolean,
    pauseBtnText: String,
    pauseBtnColor: Int,
    pauseBtnEnabled: Boolean,
    pauseBtnAlpha: Float,
    launchBtnEnabled: Boolean,
    launchBtnAlpha: Float,
    progressVisible: Boolean,
    progressValue: Int,
    progressText: String,
    progressTextVisible: Boolean,
    onBack: () -> Unit,
    onInstallClick: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onLaunchClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1B))
            .verticalScroll(rememberScrollState()),
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF212121))
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("\u2190 Back", color = Color.White) }
        }

        // Header image
        Box(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (headerBitmap != null) {
                Image(
                    bitmap = headerBitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0D47A1)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = Color(0xFF0055FF),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }

        // Info section
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            Text(text = nameText, fontSize = 22.sp, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = typeText,
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier
                        .background(Color(0xFF263238))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(text = sizeText, fontSize = 12.sp, color = Color(0xFFAAAAAA))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = Color(statusColor),
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }

        // Progress
        if (progressVisible) {
            LinearProgressIndicator(
                progress = { progressValue / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                color = Color(0xFF0055FF),
                trackColor = Color(0xFF2A2A2A),
            )
        }
        if (progressTextVisible) {
            Text(
                text = progressText,
                fontSize = 11.sp,
                color = Color(0xFFAAAAAA),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(
                onClick = onInstallClick,
                enabled = installBtnEnabled,
                colors = ButtonDefaults.buttonColors(containerColor = Color(installBtnColor)),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(4.dp),
            ) { Text(installBtnText, color = Color.White) }

            Button(
                onClick = onPauseResumeClick,
                enabled = pauseBtnEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(pauseBtnColor).copy(alpha = pauseBtnAlpha),
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(4.dp),
            ) { Text(pauseBtnText, color = Color.White) }

            Button(
                onClick = onLaunchClick,
                enabled = launchBtnEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32).copy(alpha = launchBtnAlpha),
                ),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(4.dp),
            ) { Text("Launch", color = Color.White) }
        }
    }
}

@Composable
private fun DownloadSpeedPickerDialog(
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onDownload: (threadCount: Int) -> Unit,
) {
    val options = listOf(
        "Safe (4 threads) \u2014 least RAM/CPU usage" to 4,
        "Normal (8 threads) \u2014 balanced" to 8,
        "Fast (16 threads) \u2014 maximum speed" to 16,
    )
    var selected by remember { mutableIntStateOf(selectedIndex) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download speed") },
        text = {
            Column {
                options.forEachIndexed { index, (label, _) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = index }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(
                            selected = selected == index,
                            onClick = { selected = index },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF0055FF)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, fontSize = 14.sp, color = Color.White, modifier = Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(options[selected].second) }) {
                Text("Download", color = Color(0xFF0055FF))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFFAAAAAA)) }
        },
    )
}

@Composable
private fun ExePickerDialogGame(
    gameName: String,
    candidates: List<String>,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select executable for \"$gameName\"") },
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
