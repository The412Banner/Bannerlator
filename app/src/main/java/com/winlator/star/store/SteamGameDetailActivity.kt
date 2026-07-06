package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.star.store.compose.AddResultDialog
import com.winlator.star.store.compose.AddShortcutResult
import com.winlator.star.store.compose.AddToShortcutsRequest
import com.winlator.star.store.compose.ContainerPickerDialog
import com.winlator.star.store.compose.openShortcutsScreen
import com.winlator.star.store.download.DownloadsButton
import com.winlator.star.ui.theme.WinlatorTheme
import java.io.File
import java.net.URL

/** Semantic action carried by the install button; the colour resolves inside the composable
 *  (install/retry = primary, cancel/uninstall = error) so theme presets recolor it live. */
private enum class InstallAction { INSTALL, CANCEL, UNINSTALL, RETRY }

/** Semantic pause-button mode: PAUSE renders as a calm surfaceVariant container, RESUME as primary. */
private enum class PauseAction { PAUSE, RESUME }

/** Semantic install status; colour resolves inside the composable (installed = green,
 *  failed = error, everything else = onSurfaceVariant). */
private enum class GameStatus { NOT_INSTALLED, INSTALLED, CANCELLED, FAILED }

class SteamGameDetailActivity : ComponentActivity(), SteamRepository.SteamEventListener {

    companion object {
        const val EXTRA_APP_ID = "steam_app_id"
    }

    private var appId: Int = 0
    private var game by mutableStateOf<SteamGame?>(null)

    @Volatile private var downloadHandle: SteamDepotDownloader.DownloadControl? = null
    private var lastSpeedTier = DownloadSpeedConfig.DEFAULT_TIER  // 24 = Fast

    // UI state
    private var headerBitmap by mutableStateOf<Bitmap?>(null)
    private var nameText by mutableStateOf("Loading…")
    private var typeText by mutableStateOf("GAME")
    private var sizeText by mutableStateOf("Size unknown")
    // One-shot guard so the manifest-true size resolve fires at most once per detail view.
    private var sizeResolveStarted = false
    private var statusText by mutableStateOf("Not installed")
    private var gameStatus by mutableStateOf(GameStatus.NOT_INSTALLED)
    private var installBtnText by mutableStateOf("Install")
    private var installAction by mutableStateOf(InstallAction.INSTALL)
    private var installBtnEnabled by mutableStateOf(true)
    private var pauseBtnText by mutableStateOf("Pause")
    private var pauseAction by mutableStateOf(PauseAction.PAUSE)
    private var pauseBtnEnabled by mutableStateOf(false)
    private var launchBtnEnabled by mutableStateOf(false)
    private var progressVisible by mutableStateOf(false)
    private var progressValue by mutableIntStateOf(0)
    // Lighter "download" (network) fill that leads the solid install fill. On paused/DB-restored
    // views (compressed progress isn't persisted) it mirrors the install fraction.
    private var downloadProgressValue by mutableIntStateOf(0)
    private var progressText by mutableStateOf("")
    private var progressTextVisible by mutableStateOf(false)

    private var showSpeedPicker by mutableStateOf(false)
    // Non-null while an uninstall is deleting files → shows the blocking progress spinner.
    private var uninstallingName by mutableStateOf<String?>(null)
    // Non-null briefly after an uninstall → themed auto-dismiss confirmation bar (not a Toast).
    private var uninstallResult by mutableStateOf<String?>(null)
    private var showExePicker by mutableStateOf<ExePickerDataGame?>(null)
    private var addToShortcuts by mutableStateOf<AddToShortcutsRequest?>(null)
    private var addResult by mutableStateOf<AddShortcutResult?>(null)

    // Goldberg (Steam emulator) state — only meaningful once the game is installed.
    // The component is ONE global download shared by every game; the tier toggle
    // only lights up once it's installed.
    private var goldbergMode by mutableStateOf(GoldbergMode.OFF)
    private var goldbergBusy by mutableStateOf(false)
    private var goldbergMessage by mutableStateOf<String?>(null)
    private var goldbergInstalled by mutableStateOf(false)
    private var goldbergDownloading by mutableStateOf(false)
    private var goldbergDownloadProgress by mutableFloatStateOf(0f)
    private var goldbergSizeLabel by mutableStateOf("")

    private var steamStatus by mutableStateOf(SteamRepository.getInstance().status)

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
                    steamStatus = steamStatus,
                    onReconnect = { SteamRepository.getInstance().reconnectNow() },
                    nameText = nameText,
                    typeText = typeText,
                    sizeText = sizeText,
                    statusText = statusText,
                    gameStatus = gameStatus,
                    installBtnText = installBtnText,
                    installAction = installAction,
                    installBtnEnabled = installBtnEnabled,
                    pauseBtnText = pauseBtnText,
                    pauseAction = pauseAction,
                    pauseBtnEnabled = pauseBtnEnabled,
                    launchBtnEnabled = launchBtnEnabled,
                    progressVisible = progressVisible,
                    progressValue = progressValue,
                    downloadProgressValue = downloadProgressValue,
                    progressText = progressText,
                    progressTextVisible = progressTextVisible,
                    goldbergVisible = gameStatus == GameStatus.INSTALLED,
                    goldbergMode = goldbergMode,
                    goldbergBusy = goldbergBusy,
                    goldbergInstalled = goldbergInstalled,
                    goldbergDownloading = goldbergDownloading,
                    goldbergDownloadProgress = goldbergDownloadProgress,
                    goldbergSizeLabel = goldbergSizeLabel,
                    onGoldbergDownloadClick = { onGoldbergDownloadClicked() },
                    onGoldbergModeSelected = { onGoldbergModeSelected(it) },
                    onBack = { finish() },
                    onInstallClick = { onInstallClicked() },
                    onPauseResumeClick = { onPauseResumeClicked() },
                    onLaunchClick = { onLaunchClicked() },
                )

                if (showSpeedPicker) {
                    DownloadSpeedPickerDialog(
                        selectedIndex = when (lastSpeedTier) {
                            DownloadSpeedConfig.TIER_SLOW    -> 0
                            DownloadSpeedConfig.TIER_MEDIUM  -> 1
                            DownloadSpeedConfig.TIER_FAST    -> 2
                            DownloadSpeedConfig.TIER_BLAZING -> 3
                            else -> 2  // Fast
                        },
                        onDismiss = { showSpeedPicker = false },
                        onDownload = { tier, debugLog ->
                            showSpeedPicker = false
                            lastSpeedTier = tier
                            installBtnEnabled = false
                            installBtnText = "Starting…"
                            downloadHandle = SteamDepotDownloader.installApp(appId, applicationContext, lastSpeedTier, debugLog)
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
                                this@SteamGameDetailActivity, container,
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
                            openShortcutsScreen(this@SteamGameDetailActivity)
                        },
                        onDismiss = { addResult = null },
                    )
                }

                goldbergMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { goldbergMessage = null },
                        title = { Text("Steam Emulator (Goldberg)") },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton(onClick = { goldbergMessage = null }) { Text("OK") }
                        },
                    )
                }

                uninstallingName?.let { UninstallProgressDialog(it) }
                uninstallResult?.let { UninstallResultBar(it) { uninstallResult = null } }
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
            event.startsWith("SteamStatus:") -> {
                val name = event.substringAfter("SteamStatus:")
                steamStatus = try { SteamRepository.SteamStatus.valueOf(name) } catch (e: Exception) { steamStatus }
            }
            event.startsWith("DownloadProgress:") -> {
                // Format: DownloadProgress:appId:installDone:installTotal:downloadDone:downloadTotal
                val parts = event.split(":")
                val id    = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val iDone  = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val iTotal = parts.getOrNull(3)?.toLongOrNull() ?: 1L
                val dDone  = parts.getOrNull(4)?.toLongOrNull() ?: iDone
                val dTotal = parts.getOrNull(5)?.toLongOrNull() ?: iTotal
                val iPct   = if (iTotal > 0) (iDone * 100 / iTotal).toInt().coerceIn(0, 100) else 0
                val dPct   = if (dTotal > 0) (dDone * 100 / dTotal).toInt().coerceIn(0, 100) else 0
                progressVisible = true
                progressValue = iPct               // solid install fill (bytes on disk)
                downloadProgressValue = dPct        // lighter download fill (bytes fetched)
                progressTextVisible = true
                // %/size text is the INSTALL fraction — what's actually on disk.
                progressText = "Downloading… $iPct%  (${fmtSize(iDone)} / ${fmtSize(iTotal)})"
                installBtnEnabled = true
                installBtnText = "Cancel"
                installAction = InstallAction.CANCEL
                pauseBtnEnabled = true
                pauseBtnText = "Pause"
                pauseAction = PauseAction.PAUSE
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
                downloadProgressValue = pct   // compressed not persisted — mirror install
                progressTextVisible = true
                progressText = "Paused — $pct%  (${fmtSize(done)} / ${fmtSize(total)})"
                installBtnEnabled = true
                installBtnText = "Cancel"
                installAction = InstallAction.CANCEL
                pauseBtnEnabled = true
                pauseBtnText = "Resume"
                pauseAction = PauseAction.RESUME
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
                gameStatus = GameStatus.CANCELLED
                installBtnEnabled = true
                installBtnText = "Install"
                installAction = InstallAction.INSTALL
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
                gameStatus = GameStatus.FAILED
                installBtnEnabled = true
                installBtnText = "Retry"
                installAction = InstallAction.RETRY
                resetPauseBtn()
            }
        }
    }

    /**
     * Fire-and-forget: resolve the app's manifest-true install size off the UI thread, once. On
     * success, drop the "~" and show the real size. Silent + degrades to the estimate on any failure
     * (not-logged-in / download active / CM timeout — all handled inside DepotSizeResolver).
     */
    private fun maybeResolveRealSize() {
        if (sizeResolveStarted) return
        sizeResolveStarted = true
        Thread {
            val s = try { DepotSizeResolver.resolveBlocking(appId) } catch (_: Throwable) { null }
            if (s != null && s.complete && s.realInstallBytes > 0L) {
                runOnUiThread { sizeText = fmtSize(s.realInstallBytes) }
            }
        }.apply { isDaemon = true; name = "SteamDetailSizeResolve" }.start()
    }

    private fun resetPauseBtn() {
        pauseBtnEnabled = false
        pauseBtnText = "Pause"
        pauseAction = PauseAction.PAUSE
    }

    private fun loadGame() {
        val row = SteamRepository.getInstance().database.getGame(appId) ?: run { finish(); return }
        game = SteamGame.fromGameRow(row)
        refreshUI()

        val dlRow = SteamRepository.getInstance().database.getDownload(appId)
        if (dlRow != null) {
            val pct = if (dlRow.bytesTotal > 0) (dlRow.bytesDownloaded * 100 / dlRow.bytesTotal).toInt().coerceIn(0, 100) else 0
            // DB restore only has install bytes — mirror them onto the download fill.
            downloadProgressValue = pct
            when (dlRow.status) {
                SteamDatabase.DL_DOWNLOADING -> {
                    if (SteamDepotDownloader.isDownloading(appId)) {
                        progressVisible = true
                        progressValue = pct
                        progressTextVisible = true
                        progressText = "Downloading… $pct%"
                        installBtnEnabled = true
                        installBtnText = "Cancel"
                        installAction = InstallAction.CANCEL
                        pauseBtnEnabled = true
                        pauseBtnText = "Pause"
                        pauseAction = PauseAction.PAUSE
                    } else {
                        SteamRepository.getInstance().database.deleteDownload(appId)
                    }
                }
                SteamDatabase.DL_PAUSED -> {
                    progressVisible = true
                    progressValue = pct
                    downloadProgressValue = pct
                    progressTextVisible = true
                    progressText = "Paused — $pct%  (${fmtSize(dlRow.bytesDownloaded)} / ${fmtSize(dlRow.bytesTotal)})"
                    installBtnEnabled = true
                    installBtnText = "Cancel"
                    installAction = InstallAction.CANCEL
                    pauseBtnEnabled = true
                    pauseBtnText = "Resume"
                    pauseAction = PauseAction.RESUME
                }
            }
        }
    }

    private fun refreshUI() {
        val g = game ?: return
        nameText = g.name.ifEmpty { "App ${g.appId}" }
        typeText = g.type.uppercase()
        // Paint the manifest-TRUE size instantly if it's already resolved (no "~"), otherwise the PICS
        // "~estimate". A background resolve then drops the "~" once the real size lands. cached() is a
        // pure DB read; resolve() is gated off the UI thread + off active downloads inside the resolver.
        val cachedSizes = try { DepotSizeResolver.cached(g.appId) } catch (_: Throwable) { null }
        sizeText = when {
            cachedSizes != null && cachedSizes.complete && cachedSizes.realInstallBytes > 0L ->
                fmtSize(cachedSizes.realInstallBytes)
            g.sizeBytes > 0L -> "~${fmtSize(g.sizeBytes)}"
            else             -> "Size unknown"
        }
        maybeResolveRealSize()

        if (g.isInstalled) {
            statusText = "Installed"
            gameStatus = GameStatus.INSTALLED
            installBtnText = "Uninstall"
            installAction = InstallAction.UNINSTALL
            installBtnEnabled = true
            launchBtnEnabled = true
            goldbergMode = SteamPrefs.getGoldbergMode(appId)
            goldbergInstalled = GoldbergComponent.isInstalled(this)
            // If the global component isn't downloaded yet, fetch the catalog in
            // the background so the download button can show its size.
            if (!goldbergInstalled && goldbergSizeLabel.isEmpty()) {
                GoldbergComponent.loadCatalogAsync { cat ->
                    goldbergSizeLabel = cat?.takeIf { it.fileSize > 0 }?.let { fmtSize(it.fileSize) } ?: ""
                }
            }
        } else {
            statusText = "Not installed"
            gameStatus = GameStatus.NOT_INSTALLED
            installBtnText = "Install"
            installAction = InstallAction.INSTALL
            installBtnEnabled = true
            launchBtnEnabled = false
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
            gameStatus = GameStatus.CANCELLED
            installBtnText = "Install"
            installAction = InstallAction.INSTALL
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
            gameStatus = GameStatus.CANCELLED
            installBtnText = "Install"
            installAction = InstallAction.INSTALL
            installBtnEnabled = true
            resetPauseBtn()
            return
        }

        if (g.isInstalled) {
            uninstallingName = g.name
            StoreUninstaller.run(
                installDir = g.installDir,
                mark = { SteamRepository.getInstance().database.markUninstalled(appId) },
            ) { ok ->
                uninstallingName = null
                uninstallResult = if (ok) "${g.name} uninstalled" else "Couldn't fully remove ${g.name}"
                loadGame()
            }
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
            pauseAction = PauseAction.RESUME
            pauseBtnEnabled = true
            installBtnText = "Cancel"
            installBtnEnabled = true
            val cur = progressText
            if (cur.startsWith("Downloading")) progressText = cur.replace("Downloading", "Pausing")
        } else {
            val dlRow = SteamRepository.getInstance().database.getDownload(appId) ?: return
            if (dlRow.status != SteamDatabase.DL_PAUSED) return
            pauseBtnEnabled = false
            pauseBtnText = "Resuming…"
            installBtnEnabled = false
            installBtnText = "Starting…"
            downloadHandle = SteamDepotDownloader.resumeApp(appId, applicationContext, lastSpeedTier)
        }
    }

    private fun onLaunchClicked() {
        val g = game ?: return
        if (!g.isInstalled || g.installDir.isEmpty()) {
            uninstallResult = "Game not installed"
            return
        }
        val installDir = File(g.installDir)
        Thread {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                runOnUiThread {
                    uninstallResult = "No .exe found in install directory"
                }
                return@Thread
            }
            val lowerTitle = g.name.lowercase()
            exeFiles.sortWith { a, b ->
                AmazonLaunchHelper.scoreExe(b, lowerTitle) - AmazonLaunchHelper.scoreExe(a, lowerTitle)
            }
            val coverUrl = "https://shared.steamstatic.com/store_item_assets/steam/apps/${g.appId}/library_600x900.jpg"

            if (exeFiles.size == 1) {
                runOnUiThread { startAddToShortcuts(g.name, exeFiles[0].absolutePath, coverUrl) }
                return@Thread
            }
            val candidates = exeFiles.map { it.absolutePath }
            runOnUiThread {
                showExePicker = ExePickerDataGame(g.name, candidates, coverUrl)
            }
        }.start()
    }

    /** Compose add-to-shortcuts flow: load containers, then show the M3 picker. */
    private fun startAddToShortcuts(gameName: String, exePath: String, coverUrl: String?) {
        // If this game is in Cold Client Loader mode, the shortcut must launch the
        // Goldberg loader beside the exe instead of the game exe. Every other mode
        // (OFF/REGULAR/EXPERIMENTAL) returns exePath unchanged.
        val launchExe = GoldbergPatcher.resolveLaunchExe(this, appId, exePath)
        StarLaunchBridge.loadContainers(this) { containers ->
            addToShortcuts = AddToShortcutsRequest(gameName, launchExe, coverUrl, containers)
        }
    }

    /**
     * Downloads the ONE global Goldberg component (ReShade-style: catalog →
     * .tzst → MD5 verify → extract to imagefs/opt/goldberg). Once installed,
     * every game's detail page shows the tier toggle ready — no re-download.
     */
    private fun onGoldbergDownloadClicked() {
        if (goldbergDownloading || goldbergInstalled) return
        goldbergDownloading = true
        goldbergDownloadProgress = 0f
        GoldbergComponent.downloadAsync(
            this,
            progress = { fraction -> goldbergDownloadProgress = fraction },
            done = { success, message ->
                goldbergDownloading = false
                goldbergInstalled = GoldbergComponent.isInstalled(this)
                goldbergMessage = message
            },
        )
    }

    /**
     * Applies the chosen Goldberg tier on a worker thread, then persists it.
     * OFF restores the game to pristine. The patcher surfaces the N/A case
     * ("doesn't use the Steam API") and the not-bundled case as result messages.
     */
    private fun onGoldbergModeSelected(mode: GoldbergMode) {
        val g = game ?: return
        if (goldbergBusy || mode == goldbergMode) return
        if (!g.isInstalled || g.installDir.isEmpty()) return
        goldbergBusy = true
        GoldbergPatcher.applyModeAsync(this, appId, g.installDir, g.name, mode) { success, message ->
            goldbergBusy = false
            if (success) goldbergMode = mode
            goldbergMessage = message
        }
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
    steamStatus: SteamRepository.SteamStatus,
    onReconnect: () -> Unit,
    nameText: String,
    typeText: String,
    sizeText: String,
    statusText: String,
    gameStatus: GameStatus,
    installBtnText: String,
    installAction: InstallAction,
    installBtnEnabled: Boolean,
    pauseBtnText: String,
    pauseAction: PauseAction,
    pauseBtnEnabled: Boolean,
    launchBtnEnabled: Boolean,
    progressVisible: Boolean,
    progressValue: Int,
    downloadProgressValue: Int,
    progressText: String,
    progressTextVisible: Boolean,
    goldbergVisible: Boolean,
    goldbergMode: GoldbergMode,
    goldbergBusy: Boolean,
    goldbergInstalled: Boolean,
    goldbergDownloading: Boolean,
    goldbergDownloadProgress: Float,
    goldbergSizeLabel: String,
    onGoldbergDownloadClick: () -> Unit,
    onGoldbergModeSelected: (GoldbergMode) -> Unit,
    onBack: () -> Unit,
    onInstallClick: () -> Unit,
    onPauseResumeClick: () -> Unit,
    onLaunchClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.weight(1f))
            SteamStatusPill(status = steamStatus, onReconnect = onReconnect)
            DownloadsButton()
        }

        // Hero image with a subtle gradient into the background at the bottom
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
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        ),
                    ),
            )
        }

        // Info section
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                text = nameText,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoChip(typeText)
                Spacer(Modifier.width(8.dp))
                InfoChip(sizeText)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = when (gameStatus) {
                    GameStatus.INSTALLED -> Color(0xFF4CAF50) // semantic installed-green
                    GameStatus.FAILED    -> MaterialTheme.colorScheme.error
                    else                 -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Progress — overlapping dual bar. A lighter "download" (network) fill leads a
        // solid "install" (on-disk) fill; they move nearly together, download slightly ahead.
        if (progressVisible) {
            val installFrac  = (progressValue / 100f).coerceIn(0f, 1f)
            val downloadFrac = (downloadProgressValue / 100f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                // Download (network) fill — wider, lighter, underneath.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(downloadFrac)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                )
                // Install (on-disk) fill — narrower, solid, on top.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(installFrac)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (progressTextVisible) {
            Text(
                text = progressText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onInstallClick,
                enabled = installBtnEnabled,
                colors = when (installAction) {
                    InstallAction.CANCEL, InstallAction.UNINSTALL -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    )
                    else -> ButtonDefaults.buttonColors()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text(installBtnText, maxLines = 1) }

            Button(
                onClick = onPauseResumeClick,
                enabled = pauseBtnEnabled,
                colors = when (pauseAction) {
                    PauseAction.PAUSE -> ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    PauseAction.RESUME -> ButtonDefaults.buttonColors()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text(pauseBtnText, maxLines = 1) }

            Button(
                onClick = onLaunchClick,
                enabled = launchBtnEnabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Launch", maxLines = 1) }
        }

        if (goldbergVisible) {
            GoldbergSection(
                installed = goldbergInstalled,
                downloading = goldbergDownloading,
                downloadProgress = goldbergDownloadProgress,
                sizeLabel = goldbergSizeLabel,
                mode = goldbergMode,
                busy = goldbergBusy,
                onDownloadClick = onGoldbergDownloadClick,
                onModeSelected = onGoldbergModeSelected,
            )
        }
    }
}

/**
 * Opt-in "Steam Emulator (Goldberg)" section. Goldberg is ONE global download
 * shared by every game: until it's installed, this shows a "Download Steam
 * Emulator" button (with progress + MD5-verified extract); once installed it
 * shows the tier selector. Tiers are escalating fallbacks. The helper text is
 * deliberately honest: Goldberg only lets a game *start* without Steam — it
 * can't reach a publisher's own online servers.
 */
@Composable
private fun GoldbergSection(
    installed: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    sizeLabel: String,
    mode: GoldbergMode,
    busy: Boolean,
    onDownloadClick: () -> Unit,
    onModeSelected: (GoldbergMode) -> Unit,
) {
    val options = listOf(
        GoldbergMode.OFF to "Off",
        GoldbergMode.REGULAR to "Regular",
        GoldbergMode.EXPERIMENTAL to "Experimental",
        GoldbergMode.COLDCLIENT to "Cold Client Loader",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Text(
            text = "Steam Emulator (Goldberg)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Lets games that require Steam start without it. " +
                "Won't fix online-only games that can't reach their own servers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Please note: this is not a fix for all Steam games that require a " +
                "Steam client to run. It is not a guaranteed fix-all — use at your own risk!",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        if (!installed) {
            // Component not downloaded yet — offer the one-time global download.
            Spacer(Modifier.height(12.dp))
            if (downloading) {
                LinearProgressIndicator(
                    progress = { downloadProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Downloading… ${(downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val sizeSuffix = if (sizeLabel.isNotEmpty()) " (~$sizeLabel)" else ""
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("Download Steam Emulator$sizeSuffix", maxLines = 1) }
            }
            return@Column
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = "Regular works for most games; try Experimental, then " +
                "Cold Client Loader if a game still won't start.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        options.forEach { (optMode, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy) { onModeSelected(optMode) }
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(
                    selected = mode == optMode,
                    onClick = { onModeSelected(optMode) },
                    enabled = !busy,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (busy) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Applying…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DownloadSpeedPickerDialog(
    selectedIndex: Int,
    onDismiss: () -> Unit,
    onDownload: (speedTier: Int, debugLog: Boolean) -> Unit,
) {
    // Tiers mirror GameNative: cores × ratio scales download + decompress concurrency.
    // Higher tiers download faster but use more RAM/CPU during decompression.
    val options = listOf(
        "Slow — lowest RAM/CPU" to DownloadSpeedConfig.TIER_SLOW,
        "Medium — balanced" to DownloadSpeedConfig.TIER_MEDIUM,
        "Fast — recommended" to DownloadSpeedConfig.TIER_FAST,
        "Blazing — fastest, highest RAM/CPU" to DownloadSpeedConfig.TIER_BLAZING,
    )
    var selected by remember { mutableIntStateOf(selectedIndex) }
    // Per-download, not persisted — defaults off each time (scoped to this one download).
    var debugLog by remember { mutableStateOf(false) }

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
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                // Verbose diagnostics toggle — off by default; writes a detailed steam_debug.txt
                // for THIS download only. Failures are always traced to logcat regardless.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { debugLog = !debugLog }
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = debugLog,
                        onCheckedChange = { debugLog = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Log debug session",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Writes a detailed log to help diagnose download problems.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Shown only when the box is ticked. The log is scrubbed of credentials, but is a
                // diagnostic file — steer users away from posting it in public.
                if (debugLog) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "⚠️ Don't post this log publicly. Share it only directly with the " +
                            "developer or someone you trust — unless you're debugging it yourself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(options[selected].second, debugLog) }) {
                Text("Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
            // HL2 (and many Source games) ship dozens of bin/*.exe SDK tools, so this list can be
            // long — bound its height and make it scrollable, or the real game exe is unreachable.
            // Cap at ~half the CURRENT screen height so it fits + scrolls in both portrait and the
            // much shorter landscape (a fixed dp cap could overflow a short landscape dialog).
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
