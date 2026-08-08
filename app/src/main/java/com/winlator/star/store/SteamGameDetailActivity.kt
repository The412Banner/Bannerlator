package com.winlator.star.store

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
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
import com.winlator.star.ui.screens.OutlinedAlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.star.R
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

/** The four granular save moves across the three tiers (Cloud ⇄ Library ⇄ Container), plus the two
 *  end-to-end combos the primary UI now leads with: SYNC_FROM = Download+Apply (cloud → into game),
 *  SYNC_TO = Collect+Upload (game → to cloud). The granular four stay for the Advanced expander. */
private enum class CloudMove { DOWNLOAD, UPLOAD, APPLY, COLLECT, SYNC_FROM, SYNC_TO }

/** A pending cloud-save confirm dialog: which move, the target container's label, and (for the
 *  moves that need it) a non-blocking staleness warning line — null when there's nothing to warn. */
private data class CloudConfirm(
    val move: CloudMove,
    val containerLabel: String,
    val stalenessWarning: String?,
)

/** The labeled size breakdown shown under the info chips. Empty strings render nothing. */
private data class SizeBreakdown(
    val downloadLabel: String = "",   // "Download: 4.5 GB"
    val picsLabel: String = "",       // "PICS estimate (Steam): 8.4 GB"
    val freeLabel: String = "",       // "Free space: 23.1 GB" (+ " — won't fit" when applicable)
    val fits: Boolean = true,         // false → render freeLabel in the error color
)

class SteamGameDetailActivity : AppCompatActivity(), SteamRepository.SteamEventListener {

    companion object {
        const val EXTRA_APP_ID = "steam_app_id"
    }

    private var appId: Int = 0
    private var game by mutableStateOf<SteamGame?>(null)

    @Volatile private var downloadHandle: SteamDepotDownloader.DownloadControl? = null
    private var lastSpeedTier = DownloadSpeedConfig.DEFAULT_TIER  // 24 = Fast

    // UI state
    private var headerBitmap by mutableStateOf<Bitmap?>(null)
    private var nameText by mutableStateOf("")
    private var typeText by mutableStateOf("")
    // Headline chip = on-disk footprint (estimate with "~", or the real measured size once installed).
    private var sizeText by mutableStateOf("")
    // Breakdown lines under the chips: download (compressed), PICS estimate (labeled), free space.
    private var sizeBreakdown by mutableStateOf(SizeBreakdown())
    // "Includes DLC: <names>" — owned DLC that WILL download (excluded ones dropped); "" hides the line.
    private var includedDlcText by mutableStateOf("")
    // DLC picker: all owned DLC bundled with the game (appId → name), the user's opt-out set, and
    // whether the picker sheet is open. Tapping the "Includes DLC" line opens the sheet.
    private var dlcEntries by mutableStateOf<Map<Int, String>>(emptyMap())
    private var excludedDlc by mutableStateOf<Set<Int>>(emptySet())
    private var showDlcSheet by mutableStateOf(false)
    // One-shot guard so the manifest-true size resolve fires at most once per detail view.
    private var sizeResolveStarted = false
    private var statusText by mutableStateOf("")
    private var gameStatus by mutableStateOf(GameStatus.NOT_INSTALLED)
    private var installBtnText by mutableStateOf("")
    private var installAction by mutableStateOf(InstallAction.INSTALL)
    private var installBtnEnabled by mutableStateOf(true)
    private var pauseBtnText by mutableStateOf("")
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

    // Steam Cloud saves — three-tier save library (Cloud ⇄ Library ⇄ Container). Only meaningful
    // once the game is installed AND a Steam session is live (SteamRepository.getSteamCloud() != null).
    private var cloudVisible by mutableStateOf(false)
    private var cloudBusy by mutableStateOf(false)
    private var cloudStatus by mutableStateOf<String?>(null)
    // Container resolution for the Apply/Collect tier. cloudResolved flips true once the async
    // resolveContainer() returns; cloudContainerReady = the game has a launch container (else the
    // section shows the "set up first" state). cloudContainerLabel names it in every confirm dialog.
    private var cloudResolved by mutableStateOf(false)
    private var cloudContainerReady by mutableStateOf(false)
    private var cloudContainerLabel by mutableStateOf<String?>(null)
    // Pending per-move confirm dialog (move + container label + optional staleness warning).
    private var cloudConfirm by mutableStateOf<CloudConfirm?>(null)
    // One-time third-party disclaimer (persisted in steam_prefs). pendingCloudMove is the move the
    // user tried to run before accepting; it's dispatched once they tap "I understand".
    private var showCloudDisclaimer by mutableStateOf(false)
    private var pendingCloudMove by mutableStateOf<CloudMove?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appId = intent.getIntExtra(EXTRA_APP_ID, 0)
        if (appId == 0) { finish(); return }

        nameText = getString(R.string.compose_steam_game_detail_loading)
        typeText = getString(R.string.compose_steam_game_detail_type_game)
        sizeText = getString(R.string.compose_steam_game_detail_size_unknown)
        statusText = getString(R.string.compose_steam_game_detail_not_installed)
        installBtnText = getString(R.string.compose_steam_game_detail_install)
        pauseBtnText = getString(R.string.compose_steam_game_detail_pause)

        SteamPrefs.init(this)
        SteamRepository.getInstance().initialize(this)
        // Lazy-connect: opening a detail page directly (e.g. drawer → Save Manager → tap a card) skips
        // the store home that starts SteamForegroundService, so the CM connection would stay down and
        // the status badge read offline. Ensure it here when signed in — idempotent (start/connect
        // guard double-connect), and a no-op for users without a saved Steam account.
        if (SteamPrefs.isLoggedIn) SteamForegroundService.start(this)

        setContent {
            WinlatorTheme {
                SteamGameDetailScreen(
                    headerBitmap = headerBitmap,
                    steamStatus = steamStatus,
                    onReconnect = { SteamRepository.getInstance().reconnectNow() },
                    nameText = nameText,
                    typeText = typeText,
                    sizeText = sizeText,
                    sizeBreakdown = sizeBreakdown,
                    includedDlcText = includedDlcText,
                    dlcEntries = dlcEntries,
                    excludedDlc = excludedDlc,
                    showDlcSheet = showDlcSheet,
                    onDlcLineClick = { if (dlcEntries.isNotEmpty()) showDlcSheet = true },
                    onToggleDlc = { toggleDlc(it) },
                    onDismissDlcSheet = { showDlcSheet = false },
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
                    cloudVisible = cloudVisible,
                    cloudBusy = cloudBusy,
                    cloudStatus = cloudStatus,
                    cloudResolved = cloudResolved,
                    cloudContainerReady = cloudContainerReady,
                    cloudContainerLabel = cloudContainerLabel,
                    cloudLibraryPath = SteamCloudSavePaths.libraryDir(this, appId).absolutePath,
                    onCloudSyncFrom = { onCloudMoveRequested(CloudMove.SYNC_FROM) },
                    onCloudSyncTo = { onCloudMoveRequested(CloudMove.SYNC_TO) },
                    onCloudDownload = { onCloudMoveRequested(CloudMove.DOWNLOAD) },
                    onCloudApply = { onCloudMoveRequested(CloudMove.APPLY) },
                    onCloudCollect = { onCloudMoveRequested(CloudMove.COLLECT) },
                    onCloudUpload = { onCloudMoveRequested(CloudMove.UPLOAD) },
                    onCloudSetUp = { onLaunchClicked() },
                    onBack = { finish() },
                    onInstallClick = { onInstallClicked() },
                    onPauseResumeClick = { onPauseResumeClicked() },
                    onLaunchClick = { onLaunchClicked() },
                )

                // One-time third-party disclaimer — gates the FIRST cloud action (any game). On accept
                // we persist the flag and dispatch the move the user was trying to run.
                if (showCloudDisclaimer) {
                    OutlinedAlertDialog(
                        onDismissRequest = { showCloudDisclaimer = false; pendingCloudMove = null },
                        title = { Text(stringResource(R.string.compose_steam_game_detail_third_party_cloud_title)) },
                        text = {
                            Text(stringResource(R.string.compose_steam_game_detail_third_party_cloud_body))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showCloudDisclaimer = false
                                setCloudDisclaimerAccepted()
                                pendingCloudMove?.let { prepareCloudConfirm(it) }
                                pendingCloudMove = null
                            }) { Text(stringResource(R.string.compose_steam_game_detail_i_understand)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                showCloudDisclaimer = false
                                pendingCloudMove = null
                            }) { Text(stringResource(R.string.compose_steam_game_detail_cancel)) }
                        },
                    )
                }

                // Per-move confirm dialog — names the target container, states direction + semantics,
                // folds in the staleness warning (Apply/Upload) and the third-party reminder.
                cloudConfirm?.let { confirm ->
                    CloudConfirmDialog(
                        confirm = confirm,
                        onConfirm = {
                            cloudConfirm = null
                            executeCloudMove(confirm.move)
                        },
                        onDismiss = { cloudConfirm = null },
                    )
                }

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
                            installBtnText = getString(R.string.compose_steam_game_detail_starting)
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
                                req.gameName, req.exePath, req.coverUrl, appId,
                            ) { success, message ->
                                addResult = AddShortcutResult(req.gameName, success, message)
                                // The game now has a launch container — re-resolve so the Cloud Saves
                                // section flips out of the "set up first" state.
                                if (success) game?.let { resolveCloudContainer(it) }
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
                    OutlinedAlertDialog(
                        onDismissRequest = { goldbergMessage = null },
                        title = { Text(stringResource(R.string.compose_steam_game_detail_goldberg_title)) },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton(onClick = { goldbergMessage = null }) {
                                Text(stringResource(R.string.compose_steam_game_detail_ok))
                            }
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
                // Format: DownloadProgress:appId:installDone:installTotal:downloadDone:downloadTotal:etaSec:speedBps
                val parts = event.split(":")
                val id    = parts.getOrNull(1)?.toIntOrNull() ?: return
                if (id != appId) return
                val iDone  = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                val iTotal = parts.getOrNull(3)?.toLongOrNull() ?: 1L
                val dDone  = parts.getOrNull(4)?.toLongOrNull() ?: iDone
                val dTotal = parts.getOrNull(5)?.toLongOrNull() ?: iTotal
                val etaSec = parts.getOrNull(6)?.toLongOrNull() ?: -1L
                val speed  = parts.getOrNull(7)?.toLongOrNull() ?: 0L
                val iPct   = if (iTotal > 0) (iDone * 100 / iTotal).toInt().coerceIn(0, 100) else 0
                val dPct   = if (dTotal > 0) (dDone * 100 / dTotal).toInt().coerceIn(0, 100) else 0
                progressVisible = true
                progressValue = iPct               // solid install fill (bytes on disk)
                downloadProgressValue = dPct        // lighter download fill (bytes fetched)
                progressTextVisible = true
                // %/size text is the INSTALL fraction — what's actually on disk; append speed + ETA.
                val speedEta = buildString {
                    val s = fmtDownloadSpeed(speed)
                    if (s.isNotEmpty()) append(getString(R.string.compose_steam_game_detail_progress_segment, s))
                    val e = fmtEta(etaSec)
                    if (e.isNotEmpty()) append(getString(R.string.compose_steam_game_detail_progress_segment, e))
                }
                progressText = getString(
                    R.string.compose_steam_game_detail_downloading_progress,
                    iPct,
                    fmtSize(iDone),
                    fmtSize(iTotal),
                    speedEta,
                )
                installBtnEnabled = true
                installBtnText = getString(R.string.compose_steam_game_detail_cancel_download)
                installAction = InstallAction.CANCEL
                pauseBtnEnabled = true
                pauseBtnText = getString(R.string.compose_steam_game_detail_pause)
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
                progressText = getString(
                    R.string.compose_steam_game_detail_paused_progress,
                    pct,
                    fmtSize(done),
                    fmtSize(total),
                )
                installBtnEnabled = true
                installBtnText = getString(R.string.compose_steam_game_detail_cancel_download)
                installAction = InstallAction.CANCEL
                pauseBtnEnabled = true
                pauseBtnText = getString(R.string.compose_steam_game_detail_resume)
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
                statusText = getString(R.string.compose_steam_game_detail_download_cancelled)
                gameStatus = GameStatus.CANCELLED
                installBtnEnabled = true
                installBtnText = getString(R.string.compose_steam_game_detail_install)
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
                statusText = getString(
                    R.string.compose_steam_game_detail_download_failed,
                    reason,
                    logPath,
                )
                gameStatus = GameStatus.FAILED
                installBtnEnabled = true
                installBtnText = getString(R.string.compose_steam_game_detail_retry)
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
                runOnUiThread { game?.let { refreshSizeUi(it) } }   // real sizes landed → drop "~", update breakdown
            }
        }.apply { isDaemon = true; name = "SteamDetailSizeResolve" }.start()
    }

    /**
     * Compute the size section from the DB (pure, UI-thread-safe): the headline on-disk FOOTPRINT
     * (block-rounded estimate, "~" until resolved) plus the labeled breakdown — download (compressed),
     * the PICS estimate (explicitly labeled as Steam's), and free space with a "won't fit" flag. For an
     * installed game the estimate is replaced by the REAL measured footprint (async, best-effort).
     */
    private fun refreshSizeUi(g: SteamGame) {
        recomputeSizeDisplay(g)
        maybeResolveRealSize()
        if (g.isInstalled && g.installDir.isNotEmpty()) measureInstalledFootprint(g)
    }

    /**
     * Pure recompute of the size chips/breakdown from the depot rows, DROPPING any DLC the user opted
     * out of — so unchecking DLC lowers the shown download / on-disk / PICS numbers live. Sums per
     * depot (real sizes where resolved, PICS otherwise) instead of the app-level totals so exclusions
     * are honoured. Safe to call on the UI thread (pure DB read).
     */
    private fun recomputeSizeDisplay(g: SteamGame) {
        val rows = try { SteamRepository.getInstance().database.getDepotManifests(g.appId) } catch (_: Throwable) { emptyList() }
        val kept = rows.filter { it.depotId !in excludedDlc }
        var pics = 0L; var install = 0L; var download = 0L; var disk = 0L
        var anyResolved = false; var allResolved = kept.isNotEmpty()
        for (r in kept) {
            pics += r.sizeBytes
            if (r.realSizeBytes > 0L) {
                anyResolved = true
                install  += r.realSizeBytes
                download += r.realDownloadBytes
                disk     += if (r.realDiskBytes > 0L) r.realDiskBytes else r.realSizeBytes
            } else {
                allResolved = false
                install += r.sizeBytes
                disk    += r.sizeBytes
            }
        }
        // Empty depot table (not synced) → fall back to the app-level PICS size.
        if (rows.isEmpty()) { pics = g.sizeBytes; disk = g.sizeBytes }
        val resolved  = allResolved && anyResolved
        val footprint = if (resolved && disk > 0L) disk else pics

        sizeText = when {
            footprint > 0L && resolved -> getString(
                R.string.compose_steam_game_detail_size_on_disk,
                fmtSize(footprint),
            )
            footprint > 0L             -> getString(
                R.string.compose_steam_game_detail_size_on_disk_estimate,
                fmtSize(footprint),
            )
            else                       -> getString(R.string.compose_steam_game_detail_size_unknown)
        }

        // Download (compressed): the resolved per-depot sum is exclusion-aware; before resolve fall
        // back to the app-level PICS download estimate (not exclusion-aware, shown only until resolve).
        val downloadBytes = if (resolved && download in 1..maxOf(pics, download)) download
                            else try { SteamRepository.getInstance().getSelectedDownloadSize(g.appId) } catch (_: Throwable) { 0L }

        val free = try { freeInstallBytes() } catch (_: Throwable) { -1L }
        val fits = g.isInstalled || free < 0L || footprint <= 0L || free >= footprint
        sizeBreakdown = SizeBreakdown(
            downloadLabel = if (downloadBytes > 0L) getString(
                R.string.compose_steam_game_detail_download_size,
                fmtSize(downloadBytes),
            ) else "",
            picsLabel     = if (pics > 0L) getString(
                R.string.compose_steam_game_detail_pics_estimate,
                fmtSize(pics),
            ) else "",
            freeLabel     = if (free >= 0L) getString(
                if (fits) R.string.compose_steam_game_detail_free_space
                else R.string.compose_steam_game_detail_free_space_wont_fit,
                fmtSize(free),
            ) else "",
            fits          = fits,
        )
    }

    /** The "Includes DLC:" label from the owned DLC minus the user's opt-outs. "" hides the line
     *  (no owned DLC); "DLC: none selected" when everything's unchecked (keeps the line tappable). */
    private fun buildIncludedDlcText(): String {
        if (dlcEntries.isEmpty()) return ""
        val included = dlcEntries.filterKeys { it !in excludedDlc }.values
        return if (included.isEmpty()) getString(R.string.compose_steam_game_detail_dlc_none_selected)
               else getString(
                   R.string.compose_steam_game_detail_includes_dlc,
                   included.joinToString(", "),
               )
    }

    /** Toggle a DLC's opt-out state, persist it, and refresh the line. */
    private fun toggleDlc(dlcAppId: Int) {
        val g = game ?: return
        val next = excludedDlc.toMutableSet()
        if (dlcAppId in next) next.remove(dlcAppId) else next.add(dlcAppId)
        excludedDlc = next
        try { SteamPrefs.setExcludedDlc(g.appId, next) } catch (_: Throwable) {}
        includedDlcText = buildIncludedDlcText()
        recomputeSizeDisplay(g)   // drop the unticked DLC from the shown download/on-disk/PICS sizes
    }

    /** Available bytes on the partition the games install to. */
    private fun freeInstallBytes(): Long {
        val base = File(filesDir, "imagefs/steam_games")
        val dir  = if (base.exists()) base else filesDir
        val st   = android.os.StatFs(dir.path)
        return st.availableBytes
    }

    /** Real on-disk footprint of an installed game: sum each file rounded up to a block. Best-effort. */
    private fun measureInstalledFootprint(g: SteamGame) {
        Thread {
            val dir = File(g.installDir)
            if (!dir.exists()) return@Thread
            var sum = 0L
            try {
                dir.walkTopDown().forEach { f ->
                    if (f.isFile) {
                        val len = f.length()
                        sum += ((len + DepotSizeResolver.DEFAULT_BLOCK_BYTES - 1) /
                                DepotSizeResolver.DEFAULT_BLOCK_BYTES) * DepotSizeResolver.DEFAULT_BLOCK_BYTES
                    }
                }
            } catch (_: Throwable) { return@Thread }
            if (sum > 0L) runOnUiThread {
                sizeText = getString(R.string.compose_steam_game_detail_size_on_disk, fmtSize(sum))
            }
        }.apply { isDaemon = true; name = "SteamDetailDiskMeasure" }.start()
    }

    private fun resetPauseBtn() {
        pauseBtnEnabled = false
        pauseBtnText = getString(R.string.compose_steam_game_detail_pause)
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
                        progressText = getString(R.string.compose_steam_game_detail_downloading_percent, pct)
                        installBtnEnabled = true
                        installBtnText = getString(R.string.compose_steam_game_detail_cancel_download)
                        installAction = InstallAction.CANCEL
                        pauseBtnEnabled = true
                        pauseBtnText = getString(R.string.compose_steam_game_detail_pause)
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
                    progressText = getString(
                        R.string.compose_steam_game_detail_paused_progress,
                        pct,
                        fmtSize(dlRow.bytesDownloaded),
                        fmtSize(dlRow.bytesTotal),
                    )
                    installBtnEnabled = true
                    installBtnText = getString(R.string.compose_steam_game_detail_cancel_download)
                    installAction = InstallAction.CANCEL
                    pauseBtnEnabled = true
                    pauseBtnText = getString(R.string.compose_steam_game_detail_resume)
                    pauseAction = PauseAction.RESUME
                }
            }
        }
    }

    private fun refreshUI() {
        val g = game ?: return
        nameText = g.name.ifEmpty {
            getString(R.string.compose_steam_game_detail_app_id_fallback, g.appId)
        }
        typeText = g.type.uppercase()
        // Paint the manifest-TRUE size instantly if it's already resolved (no "~"), otherwise the PICS
        // "~estimate". A background resolve then drops the "~" once the real size lands. cached() is a
        // pure DB read; resolve() is gated off the UI thread + off active downloads inside the resolver.
        refreshSizeUi(g)
        dlcEntries = try { SteamRepository.getInstance().database.getIncludedDlcEntries(g.appId) } catch (_: Throwable) { emptyMap() }
        excludedDlc = try { SteamPrefs.getExcludedDlc(g.appId) } catch (_: Throwable) { emptySet() }
        includedDlcText = buildIncludedDlcText()
        maybeResolveRealSize()

        if (g.isInstalled) {
            statusText = getString(R.string.compose_steam_game_detail_installed)
            gameStatus = GameStatus.INSTALLED
            installBtnText = getString(R.string.compose_steam_game_detail_uninstall)
            installAction = InstallAction.UNINSTALL
            installBtnEnabled = true
            launchBtnEnabled = true
            // Cloud saves need a live Steam session (the SteamCloud handle is only bound after login).
            cloudVisible = SteamRepository.getInstance().steamCloud != null
            // Resolve the game's launch container off the UI thread AFTER the game row is bound —
            // drives the Apply/Collect tier and the "set up first" gate.
            if (cloudVisible) resolveCloudContainer(g)
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
            statusText = getString(R.string.compose_steam_game_detail_not_installed)
            gameStatus = GameStatus.NOT_INSTALLED
            installBtnText = getString(R.string.compose_steam_game_detail_install)
            installAction = InstallAction.INSTALL
            installBtnEnabled = true
            launchBtnEnabled = false
            cloudVisible = false
            cloudResolved = false
            cloudContainerReady = false
            cloudContainerLabel = null
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
            statusText = getString(R.string.compose_steam_game_detail_download_cancelled)
            gameStatus = GameStatus.CANCELLED
            installBtnText = getString(R.string.compose_steam_game_detail_install)
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
            statusText = getString(R.string.compose_steam_game_detail_download_cancelled)
            gameStatus = GameStatus.CANCELLED
            installBtnText = getString(R.string.compose_steam_game_detail_install)
            installAction = InstallAction.INSTALL
            installBtnEnabled = true
            resetPauseBtn()
            return
        }

        if (g.isInstalled) {
            uninstallingName = g.name
            val installDir = g.installDir

            // Then delete the game's files. Split out so the pre-uninstall save backup can run first
            // and this proceeds regardless of whether that backup succeeded.
            val proceedUninstall = {
                StoreUninstaller.run(
                    installDir = installDir,
                    mark = { SteamRepository.getInstance().database.markUninstalled(appId) },
                ) { ok ->
                    uninstallingName = null
                    uninstallResult = getString(
                        if (ok) R.string.compose_steam_game_detail_uninstalled
                        else R.string.compose_steam_game_detail_uninstall_incomplete,
                        g.name,
                    )
                    loadGame()
                }
            }

            // Best-effort: snapshot this game's saves into the local Library (Container -> Library)
            // BEFORE its files are deleted, so the Library is current at removal time (it lives on
            // external storage and already survives uninstall — this just makes it CURRENT). Collect
            // only — nothing is uploaded to the cloud. If the collect fails (e.g. never set up in a
            // container), log it and uninstall anyway — the backup must never block removal.
            SteamCloudSaveManager.collectFromContainer(this, appId, installDir,
                object : SteamCloudSaveManager.Callback {
                    override fun onStatus(message: String) {}
                    override fun onDone(summary: String) {
                        Log.i("BH_SAVE_SYNC", "pre-uninstall collect (appId $appId): $summary")
                        runOnUiThread { proceedUninstall() }
                    }
                    override fun onError(message: String) {
                        Log.w("BH_SAVE_SYNC", "pre-uninstall collect failed (appId $appId): $message")
                        runOnUiThread { proceedUninstall() }
                    }
                })
        } else {
            showSpeedPicker = true
        }
    }

    private fun onPauseResumeClicked() {
        val handle = downloadHandle
        if (handle != null) {
            handle.pause.run()
            downloadHandle = null
            pauseBtnText = getString(R.string.compose_steam_game_detail_resume)
            pauseAction = PauseAction.RESUME
            pauseBtnEnabled = true
            installBtnText = getString(R.string.compose_steam_game_detail_cancel_download)
            installBtnEnabled = true
            progressText = getString(R.string.compose_steam_game_detail_pausing_percent, progressValue)
        } else {
            val dlRow = SteamRepository.getInstance().database.getDownload(appId) ?: return
            if (dlRow.status != SteamDatabase.DL_PAUSED) return
            pauseBtnEnabled = false
            pauseBtnText = getString(R.string.compose_steam_game_detail_resuming)
            installBtnEnabled = false
            installBtnText = getString(R.string.compose_steam_game_detail_starting)
            downloadHandle = SteamDepotDownloader.resumeApp(appId, applicationContext, lastSpeedTier)
        }
    }

    private fun onLaunchClicked() {
        val g = game ?: return
        if (!g.isInstalled || g.installDir.isEmpty()) {
            uninstallResult = getString(R.string.compose_steam_game_detail_game_not_installed)
            return
        }
        val installDir = File(g.installDir)
        Thread {
            val exeFiles = mutableListOf<File>()
            AmazonLaunchHelper.collectExe(installDir, exeFiles)
            if (exeFiles.isEmpty()) {
                runOnUiThread {
                    uninstallResult = getString(R.string.compose_steam_game_detail_no_executable)
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

    // ── Steam Cloud saves — three-tier save library (Cloud ⇄ Library ⇄ Container) ────────────
    // Persisted one-time third-party disclaimer, keyed globally (any game). Stored in the same
    // steam_prefs store SteamPrefs uses; SteamPrefs itself is owned by a parallel workstream so we
    // read/write the flag directly here rather than adding a field to it.
    private fun cloudDisclaimerAccepted(): Boolean =
        getSharedPreferences("steam_prefs", MODE_PRIVATE)
            .getBoolean("cloud_saves_disclaimer_accepted", false)

    private fun setCloudDisclaimerAccepted() {
        getSharedPreferences("steam_prefs", MODE_PRIVATE)
            .edit().putBoolean("cloud_saves_disclaimer_accepted", true).apply()
    }

    /**
     * Resolve the game's launch container (via its shortcut) off the UI thread, then publish whether
     * it's set up + its label. Seeds the Apply/Collect tier state — called AFTER the game row is bound.
     */
    private fun resolveCloudContainer(g: SteamGame) {
        Thread {
            val container = try {
                SteamCloudSavePaths.resolveContainer(this, appId, g.installDir)
            } catch (_: Throwable) { null }
            val label = container?.let {
                try { SteamCloudSavePaths.containerLabel(it) } catch (_: Throwable) { null }
            }
            runOnUiThread {
                cloudContainerReady = container != null
                cloudContainerLabel = label
                cloudResolved = true
            }
        }.apply { isDaemon = true; name = "SteamCloudResolve" }.start()
    }

    /** Entry point for all four buttons: gate on the one-time disclaimer, then build the confirm. */
    private fun onCloudMoveRequested(move: CloudMove) {
        if (cloudBusy) return
        if (!cloudDisclaimerAccepted()) {
            pendingCloudMove = move
            showCloudDisclaimer = true
            return
        }
        prepareCloudConfirm(move)
    }

    /**
     * Build the per-move confirm. Apply and Upload first sample the container-vs-Library freshness
     * (off the UI thread) so the dialog can carry a non-blocking "Collect first?" warning when the
     * container holds newer saves than the Library. Download/Collect need no staleness check.
     */
    private fun prepareCloudConfirm(move: CloudMove) {
        val g = game ?: return
        val label = cloudContainerLabel ?: return   // gated by cloudContainerReady, but guard anyway
        if (move == CloudMove.APPLY || move == CloudMove.UPLOAD) {
            Thread {
                val stale = try {
                    SteamCloudSaveManager.staleness(this, appId, g.installDir)
                } catch (_: Throwable) { null }
                val warning = stale?.let { cloudStalenessWarning(it) }
                runOnUiThread { cloudConfirm = CloudConfirm(move, label, warning) }
            }.apply { isDaemon = true; name = "SteamCloudStale" }.start()
        } else {
            cloudConfirm = CloudConfirm(move, label, null)
        }
    }

    /**
     * Warn when the CONTAINER holds newer saves than the Library (the user played but forgot to
     * Collect): Apply would push a stale Library over newer container progress, and Upload would push
     * that stale Library to the cloud. Non-blocking — the user can still proceed.
     */
    private fun cloudStalenessWarning(s: SteamCloudSaveManager.Staleness): String? =
        if (s.containerFileCount > 0 && s.containerNewestMtime > s.libraryNewestMtime)
            getString(R.string.compose_steam_game_detail_staleness_warning)
        else null

    /** Dispatch the confirmed move to the frozen manager API. Each runs on its own worker thread. */
    private fun executeCloudMove(move: CloudMove) {
        if (cloudBusy) return
        val g = game ?: return
        cloudBusy = true
        cloudStatus = when (move) {
            CloudMove.DOWNLOAD  -> getString(R.string.compose_steam_game_detail_preparing_download)
            CloudMove.UPLOAD    -> getString(R.string.compose_steam_game_detail_preparing_upload)
            CloudMove.APPLY     -> getString(R.string.compose_steam_game_detail_applying_to_container)
            CloudMove.COLLECT   -> getString(R.string.compose_steam_game_detail_collecting_from_container)
            CloudMove.SYNC_FROM -> getString(R.string.compose_steam_game_detail_syncing_from_cloud)
            CloudMove.SYNC_TO   -> getString(R.string.compose_steam_game_detail_syncing_to_cloud)
        }
        val cb = object : SteamCloudSaveManager.Callback {
            override fun onStatus(message: String) { runOnUiThread { cloudStatus = message } }
            override fun onDone(summary: String) { runOnUiThread { cloudStatus = summary; cloudBusy = false } }
            override fun onError(message: String) {
                runOnUiThread {
                    cloudStatus = getString(R.string.compose_steam_game_detail_error, message)
                    cloudBusy = false
                }
            }
        }
        when (move) {
            CloudMove.DOWNLOAD  -> SteamCloudSaveManager.downloadToLibrary(this, appId, cb)
            CloudMove.UPLOAD    -> SteamCloudSaveManager.uploadFromLibrary(this, appId, cb)
            CloudMove.APPLY     -> SteamCloudSaveManager.applyToContainer(this, appId, g.installDir, cb)
            CloudMove.COLLECT   -> SteamCloudSaveManager.collectFromContainer(this, appId, g.installDir, cb)
            // Combos — chained Download+Apply / Collect+Upload; the manager guards not-set-up itself
            // and reports progress across both phases via onStatus.
            CloudMove.SYNC_FROM -> SteamCloudSaveManager.syncFromCloud(this, appId, g.installDir, cb)
            CloudMove.SYNC_TO   -> SteamCloudSaveManager.syncToCloud(this, appId, g.installDir, cb)
        }
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
            done = { success, _ ->
                goldbergDownloading = false
                goldbergInstalled = GoldbergComponent.isInstalled(this)
                goldbergMessage = getString(
                    if (success) R.string.compose_steam_game_detail_goldberg_download_succeeded
                    else R.string.compose_steam_game_detail_goldberg_download_failed,
                )
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
        GoldbergPatcher.applyModeAsync(this, appId, g.installDir, g.name, mode) { success, _ ->
            goldbergBusy = false
            if (success) goldbergMode = mode
            goldbergMessage = if (success) {
                when (mode) {
                    GoldbergMode.OFF -> getString(
                        R.string.compose_steam_game_detail_goldberg_disabled_result,
                        g.name,
                    )
                    GoldbergMode.REGULAR -> getString(
                        R.string.compose_steam_game_detail_goldberg_regular_result,
                        g.name,
                    )
                    GoldbergMode.EXPERIMENTAL -> getString(
                        R.string.compose_steam_game_detail_goldberg_experimental_result,
                        g.name,
                    )
                    GoldbergMode.COLDCLIENT -> getString(
                        R.string.compose_steam_game_detail_goldberg_cold_client_result,
                        g.name,
                    )
                }
            } else {
                getString(R.string.compose_steam_game_detail_goldberg_apply_failed)
            }
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> getString(
            R.string.compose_steam_game_detail_size_gigabytes,
            bytes / 1_073_741_824.0,
        )
        bytes >= 1_048_576L     -> getString(
            R.string.compose_steam_game_detail_size_megabytes,
            bytes / 1_048_576.0,
        )
        else                    -> getString(
            R.string.compose_steam_game_detail_size_kilobytes,
            bytes / 1024.0,
        )
    }

    private fun fmtDownloadSpeed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0L) "" else getString(
            R.string.compose_steam_game_detail_download_speed,
            fmtSize(bytesPerSecond),
        )

    private fun fmtEta(seconds: Long): String = when {
        seconds < 0L -> ""
        seconds < 60L -> getString(R.string.compose_steam_game_detail_eta_less_than_minute)
        seconds < 3600L -> {
            val minutes = (seconds / 60L).toInt()
            resources.getQuantityString(
                R.plurals.compose_steam_game_detail_eta_minutes,
                minutes,
                minutes,
            )
        }
        else -> {
            val hours = (seconds / 3600L).toInt()
            val minutes = ((seconds % 3600L) / 60L).toInt()
            if (minutes == 0) {
                resources.getQuantityString(
                    R.plurals.compose_steam_game_detail_eta_hours,
                    hours,
                    hours,
                )
            } else {
                getString(R.string.compose_steam_game_detail_eta_hours_minutes, hours, minutes)
            }
        }
    }
}

private data class ExePickerDataGame(
    val gameName: String,
    val candidates: List<String>,
    val coverUrl: String,
)

// --- Composable Screens ---

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SteamGameDetailScreen(
    headerBitmap: Bitmap?,
    steamStatus: SteamRepository.SteamStatus,
    onReconnect: () -> Unit,
    nameText: String,
    typeText: String,
    sizeText: String,
    sizeBreakdown: SizeBreakdown,
    includedDlcText: String,
    dlcEntries: Map<Int, String>,
    excludedDlc: Set<Int>,
    showDlcSheet: Boolean,
    onDlcLineClick: () -> Unit,
    onToggleDlc: (Int) -> Unit,
    onDismissDlcSheet: () -> Unit,
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
    cloudVisible: Boolean,
    cloudBusy: Boolean,
    cloudStatus: String?,
    cloudResolved: Boolean,
    cloudContainerReady: Boolean,
    cloudContainerLabel: String?,
    cloudLibraryPath: String,
    onCloudSyncFrom: () -> Unit,
    onCloudSyncTo: () -> Unit,
    onCloudDownload: () -> Unit,
    onCloudApply: () -> Unit,
    onCloudCollect: () -> Unit,
    onCloudUpload: () -> Unit,
    onCloudSetUp: () -> Unit,
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
                    contentDescription = stringResource(R.string.compose_steam_game_detail_back),
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
            // Labeled size breakdown: download (compressed), PICS estimate (Steam), free space.
            if (sizeBreakdown.downloadLabel.isNotEmpty() ||
                sizeBreakdown.picsLabel.isNotEmpty() ||
                sizeBreakdown.freeLabel.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Column {
                    if (sizeBreakdown.downloadLabel.isNotEmpty()) Text(
                        text = sizeBreakdown.downloadLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sizeBreakdown.picsLabel.isNotEmpty()) Text(
                        text = sizeBreakdown.picsLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sizeBreakdown.freeLabel.isNotEmpty()) Text(
                        text = sizeBreakdown.freeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (sizeBreakdown.fits) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                    )
                }
            }
            // Owned DLC that downloads alongside the game — with a clear button to choose which.
            if (includedDlcText.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = includedDlcText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedButton(
                    onClick = onDlcLineClick,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(stringResource(R.string.compose_steam_game_detail_choose_dlc))
                }
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
            ) { Text(stringResource(R.string.compose_steam_game_detail_launch), maxLines = 1) }
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

        if (cloudVisible) {
            CloudSavesSection(
                busy = cloudBusy,
                status = cloudStatus,
                resolved = cloudResolved,
                containerReady = cloudContainerReady,
                containerLabel = cloudContainerLabel,
                libraryPath = cloudLibraryPath,
                onSyncFromCloud = onCloudSyncFrom,
                onSyncToCloud = onCloudSyncTo,
                onDownload = onCloudDownload,
                onApply = onCloudApply,
                onCollect = onCloudCollect,
                onUpload = onCloudUpload,
                onSetUp = onCloudSetUp,
            )
        }
    }

    // DLC picker sheet — choose which owned DLC download with the game (opt-out).
    if (showDlcSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = onDismissDlcSheet, sheetState = sheetState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.compose_steam_game_detail_dlc_to_download),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.compose_steam_game_detail_dlc_unchecked_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                )
                dlcEntries.forEach { (id, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleDlc(id) }
                            .padding(vertical = 4.dp),
                    ) {
                        Checkbox(checked = id !in excludedDlc, onCheckedChange = { onToggleDlc(id) })
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onDismissDlcSheet,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) { Text(stringResource(R.string.compose_steam_game_detail_done)) }
            }
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
        GoldbergMode.OFF to stringResource(R.string.compose_steam_game_detail_goldberg_off),
        GoldbergMode.REGULAR to stringResource(R.string.compose_steam_game_detail_goldberg_regular),
        GoldbergMode.EXPERIMENTAL to stringResource(R.string.compose_steam_game_detail_goldberg_experimental),
        GoldbergMode.COLDCLIENT to stringResource(R.string.compose_steam_game_detail_goldberg_cold_client_loader),
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
            text = stringResource(R.string.compose_steam_game_detail_goldberg_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.compose_steam_game_detail_goldberg_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.compose_steam_game_detail_goldberg_warning),
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
                    text = stringResource(
                        R.string.compose_steam_game_detail_downloading_percent,
                        (downloadProgress.coerceIn(0f, 1f) * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        if (sizeLabel.isNotEmpty()) stringResource(
                            R.string.compose_steam_game_detail_download_steam_emulator_with_size,
                            sizeLabel,
                        ) else stringResource(R.string.compose_steam_game_detail_download_steam_emulator),
                        maxLines = 1,
                    )
                }
            }
            return@Column
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.compose_steam_game_detail_goldberg_tier_hint),
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
                    text = stringResource(R.string.compose_steam_game_detail_applying),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Steam Cloud saves — the three-tier "save library" manager (Cloud ⇄ Library ⇄ Container). Four
 * directional moves grouped into a Cloud row (Download / Upload) and a Container row (Apply / Collect):
 *   Download  Cloud → Library      Apply    Library → Container
 *   Upload    Library → Cloud      Collect  Container → Library
 * Every button routes through the caller, which shows a per-move confirm naming the target container.
 * If the game has no launch container yet (containerReady == false) the section shows a "set up first"
 * state offering the add-to-container flow instead of the buttons. Shown only when signed in
 * (caller's `cloudVisible`); a live Steam session is required for the Cloud row.
 */
@Composable
private fun CloudSavesSection(
    busy: Boolean,
    status: String?,
    resolved: Boolean,
    containerReady: Boolean,
    containerLabel: String?,
    libraryPath: String,
    onSyncFromCloud: () -> Unit,
    onSyncToCloud: () -> Unit,
    onDownload: () -> Unit,
    onApply: () -> Unit,
    onCollect: () -> Unit,
    onUpload: () -> Unit,
    onSetUp: () -> Unit,
) {
    // The four granular moves live under a collapsed "Advanced" expander; the primary UI is the two
    // end-to-end combos. Collapsed by default so re-opening the page starts simple.
    var advancedExpanded by remember { mutableStateOf(false) }
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
            text = stringResource(R.string.compose_steam_game_detail_cloud_saves_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.compose_steam_game_detail_cloud_saves_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Always-visible third-party disclaimer note.
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.compose_steam_game_detail_third_party_cloud_short_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        when {
            // Resolution still in flight — avoid flashing the "set up first" state before we know.
            !resolved -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.compose_steam_game_detail_checking_container),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // No launch container — Apply/Collect have nowhere to go. Offer the add-to-container flow.
            !containerReady -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.compose_steam_game_detail_set_up_container_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onSetUp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(stringResource(R.string.compose_steam_game_detail_set_up_container), maxLines = 1)
                }
            }

            else -> {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.compose_steam_game_detail_container_label,
                        containerLabel ?: "—",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.compose_steam_game_detail_library_label, libraryPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Primary: the two end-to-end combos, each with a one-line description underneath.
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onSyncFromCloud,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        stringResource(R.string.compose_steam_game_detail_sync_from_cloud),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.compose_steam_game_detail_sync_from_cloud_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onSyncToCloud,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        stringResource(R.string.compose_steam_game_detail_sync_to_cloud),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.compose_steam_game_detail_sync_to_cloud_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Advanced expander — the original four granular moves, hidden by default.
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { advancedExpanded = !advancedExpanded }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (advancedExpanded) R.string.compose_steam_game_detail_advanced_expanded
                            else R.string.compose_steam_game_detail_advanced_collapsed,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.compose_steam_game_detail_advanced_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (advancedExpanded) {
                    // Cloud row — Download (Cloud → Library) / Upload (Library → Cloud, additive).
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.compose_steam_game_detail_cloud),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onDownload,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.compose_steam_game_detail_download_arrow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = onUpload,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.compose_steam_game_detail_upload_arrow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    // Container row — Apply (Library → Container) / Collect (Container → Library).
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.compose_steam_game_detail_container),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onApply,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.compose_steam_game_detail_apply_arrow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        OutlinedButton(
                            onClick = onCollect,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.compose_steam_game_detail_collect_arrow),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = status ?: stringResource(R.string.compose_steam_game_detail_working),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!status.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Per-move confirm dialog. Names the target container, states the direction + additive/overwrite
 * semantics, surfaces the (non-blocking) staleness warning for Apply/Upload, and repeats the
 * third-party reminder. Confirm label matches the move.
 */
@Composable
private fun CloudConfirmDialog(
    confirm: CloudConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val container = confirm.containerLabel
    val (title, body, action) = when (confirm.move) {
        CloudMove.DOWNLOAD -> Triple(
            stringResource(R.string.compose_steam_game_detail_confirm_download_title),
            stringResource(R.string.compose_steam_game_detail_confirm_download_body),
            stringResource(R.string.compose_steam_game_detail_download),
        )
        CloudMove.APPLY -> Triple(
            stringResource(R.string.compose_steam_game_detail_confirm_apply_title),
            stringResource(R.string.compose_steam_game_detail_confirm_apply_body, container),
            stringResource(R.string.compose_steam_game_detail_apply),
        )
        CloudMove.COLLECT -> Triple(
            stringResource(R.string.compose_steam_game_detail_confirm_collect_title),
            stringResource(R.string.compose_steam_game_detail_confirm_collect_body, container),
            stringResource(R.string.compose_steam_game_detail_collect),
        )
        CloudMove.UPLOAD -> Triple(
            stringResource(R.string.compose_steam_game_detail_confirm_upload_title),
            stringResource(R.string.compose_steam_game_detail_confirm_upload_body),
            stringResource(R.string.compose_steam_game_detail_upload),
        )
        CloudMove.SYNC_FROM -> Triple(
            stringResource(R.string.compose_steam_game_detail_confirm_sync_from_title),
            stringResource(R.string.compose_steam_game_detail_confirm_sync_from_body, container),
            stringResource(R.string.compose_steam_game_detail_sync_from_cloud_action),
        )
        CloudMove.SYNC_TO -> Triple(
            stringResource(R.string.compose_steam_game_detail_confirm_sync_to_title),
            stringResource(R.string.compose_steam_game_detail_confirm_sync_to_body, container),
            stringResource(R.string.compose_steam_game_detail_sync_to_cloud_action),
        )
    }
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body)
                if (confirm.stalenessWarning != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = confirm.stalenessWarning,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.compose_steam_game_detail_third_party_cloud_reminder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(action) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.compose_steam_game_detail_cancel))
            }
        },
    )
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
        stringResource(R.string.compose_steam_game_detail_speed_slow) to DownloadSpeedConfig.TIER_SLOW,
        stringResource(R.string.compose_steam_game_detail_speed_medium) to DownloadSpeedConfig.TIER_MEDIUM,
        stringResource(R.string.compose_steam_game_detail_speed_fast) to DownloadSpeedConfig.TIER_FAST,
        stringResource(R.string.compose_steam_game_detail_speed_blazing) to DownloadSpeedConfig.TIER_BLAZING,
    )
    var selected by remember { mutableIntStateOf(selectedIndex) }
    // Per-download, not persisted — defaults off each time (scoped to this one download).
    var debugLog by remember { mutableStateOf(false) }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compose_steam_game_detail_download_speed_title)) },
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
                            text = stringResource(R.string.compose_steam_game_detail_debug_log_session),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(R.string.compose_steam_game_detail_debug_log_description),
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
                        text = stringResource(R.string.compose_steam_game_detail_debug_log_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDownload(options[selected].second, debugLog) }) {
                Text(stringResource(R.string.compose_steam_game_detail_download))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.compose_steam_game_detail_cancel))
            }
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
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.compose_steam_game_detail_select_executable, gameName))
        },
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
