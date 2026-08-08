package com.winlator.star.ui.screens

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.preference.PreferenceManager
import com.winlator.star.R
import com.winlator.star.core.FileUtils
import com.winlator.star.util.InAppFilePicker
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogLocation
import com.winlator.star.core.LogcatCapture
import com.winlator.star.ui.theme.DangerRed
import kotlinx.coroutines.launch
import java.io.File

private data class LogInfoContent(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
)

/**
 * App Settings → Log Manager. One place for everything logging: where logs go, which ones are
 * produced, how many past runs are kept, and what is on disk right now per game.
 *
 * Mirrors the Performance screen deliberately — same card layout, same always-live "?" help button
 * on every toggle. Here the help copy leads with the PERFORMANCE COST, because two of these
 * genuinely slow games down and users need to know that before leaving one on.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LogManagerScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var perGame by remember { mutableStateOf(prefs.getBoolean(LogLocation.PREF_PER_GAME, true)) }
    var keepLast by remember {
        mutableStateOf(prefs.getInt(LogLocation.PREF_KEEP_LAST, LogLocation.DEFAULT_KEEP_LAST))
    }
    var wineDebug by remember { mutableStateOf(prefs.getBoolean("enable_wine_debug", false)) }
    var box64Logs by remember { mutableStateOf(prefs.getBoolean("enable_box64_logs", false)) }
    var dxvkLogs by remember { mutableStateOf(prefs.getBoolean("enable_dxvk_logs", true)) }
    var logcat by remember { mutableStateOf(prefs.getBoolean("enable_logcat", true)) }
    var crashReports by remember { mutableStateOf(prefs.getBoolean("enable_crash_reports", true)) }

    // Location + channels moved here from the old Settings › Logs section, which this screen
    // replaces. They used to be saved by the Settings "Save" FAB; here every change is written
    // immediately, matching the Performance screen and removing the risk of two screens holding
    // the same preference and one overwriting the other on save.
    var locationMode by remember {
        mutableStateOf(prefs.getString(LogLocation.PREF_MODE, LogLocation.MODE_DOCUMENTS) ?: LogLocation.MODE_DOCUMENTS)
    }
    var customPath by remember {
        mutableStateOf(prefs.getString(LogLocation.PREF_CUSTOM_PATH, "") ?: "")
    }
    var showLocationMenu by remember { mutableStateOf(false) }
    var channels by remember {
        mutableStateOf(
            (prefs.getString("wine_debug_channels", com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS)
                ?: com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS)
                .split(",").filter { it.isNotBlank() }
        )
    }

    // Bumped after any destructive/refreshing action to force a re-scan of the filesystem.
    // Declared before saveMode() below, which increments it — a Kotlin local function can only
    // capture locals declared above it.
    var refreshTick by remember { mutableStateOf(0) }

    fun saveMode(mode: String) {
        locationMode = mode
        prefs.edit().putString(LogLocation.PREF_MODE, mode).apply()
        refreshTick++
    }
    fun saveChannels(list: List<String>) {
        channels = list
        prefs.edit().putString("wine_debug_channels", list.joinToString(",")).apply()
    }

    val dirLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            InAppFilePicker.pickedPath(result.data)?.let { path ->
                customPath = path
                prefs.edit().putString(LogLocation.PREF_CUSTOM_PATH, path).apply()
                saveMode(LogLocation.MODE_CUSTOM)
            }
        }
    }

    var info by remember { mutableStateOf<LogInfoContent?>(null) }
    var showExplainAll by remember { mutableStateOf(false) }
    var showKeepMenu by remember { mutableStateOf(false) }
    // Folder the embedded File Manager is showing, or null when it is closed.
    var browseDir by remember { mutableStateOf<File?>(null) }
    // Group open in the log viewer, and the two destructive confirmations.
    var viewing by remember { mutableStateOf<LogInventory.Entry?>(null) }
    var confirmDelete by remember { mutableStateOf<LogInventory.Entry?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    val entries = remember(refreshTick, perGame) { LogInventory.scan(context) }
    val selectLogFolderTitle = stringResource(R.string.log_manager_select_log_folder)

    fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.log_manager_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        stringResource(R.string.log_manager_action_close),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // ── Where logs go ────────────────────────────────────────────
            SectionLabel(stringResource(R.string.log_manager_where_logs_go))
            LogCard {
                PickRow(
                    label = when (locationMode) {
                        LogLocation.MODE_DOWNLOAD -> stringResource(R.string.log_manager_download)
                        LogLocation.MODE_CUSTOM -> if (customPath.isNotEmpty()) {
                            stringResource(R.string.log_manager_custom_folder)
                        } else {
                            stringResource(R.string.log_manager_choose_folder)
                        }
                        // MODE_DOCUMENTS (default) and the retired MODE_APP_DATA both resolve to Documents.
                        else -> stringResource(R.string.log_manager_documents)
                    },
                    sub = LogLocation.resolveLogDir(context)?.absolutePath ?: "—",
                    action = stringResource(R.string.log_manager_action_change),
                    onClick = { showLocationMenu = true }
                )
                Box {
                    // Same card, leading icons and dividers as the File Manager's drive menu —
                    // one menu style across the app, from the shared MenuStyle helpers.
                    DropdownMenu(
                        expanded = showLocationMenu,
                        onDismissRequest = { showLocationMenu = false },
                        modifier = Modifier.outlinedMenuCard(),
                    ) {
                        MenuRow(stringResource(R.string.log_manager_download), Icons.Default.Download) {
                            saveMode(LogLocation.MODE_DOWNLOAD); showLocationMenu = false
                        }
                        MenuItemDivider()
                        MenuRow(stringResource(R.string.log_manager_documents), Icons.Default.Description) {
                            saveMode(LogLocation.MODE_DOCUMENTS); showLocationMenu = false
                        }
                        MenuItemDivider()
                        MenuRow(stringResource(R.string.log_manager_choose_folder), Icons.Default.FolderOpen) {
                            showLocationMenu = false
                            dirLauncher.launch(InAppFilePicker.buildDirIntent(context, selectLogFolderTitle))
                        }
                    }
                }
                LogToggle(stringResource(R.string.log_manager_folder_for_each_game), perGame,
                    hint = stringResource(R.string.log_manager_folder_for_each_game_hint),
                    onInfo = {
                        info = LogInfoContent(R.string.log_manager_folder_for_each_game, LogCopy.PER_GAME)
                    }) {
                    perGame = it; putBool(LogLocation.PREF_PER_GAME, it); refreshTick++
                }
            }

            // ── What to record ───────────────────────────────────────────
            SectionLabel(stringResource(R.string.log_manager_what_to_record))
            LogCard {
                LogToggle(stringResource(R.string.log_manager_wine_debug), wineDebug,
                    hint = stringResource(R.string.log_manager_wine_debug_hint),
                    onInfo = { info = LogInfoContent(R.string.log_manager_wine_debug, LogCopy.WINE) }) {
                    wineDebug = it; putBool("enable_wine_debug", it)
                }
                if (wineDebug) {
                    WineChannelGroup(
                        selected = channels,
                        onChange = { saveChannels(it) },
                        modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
                    )
                }
                LogToggle(stringResource(R.string.log_manager_box64_fexcore), box64Logs,
                    hint = stringResource(R.string.log_manager_box64_fexcore_hint),
                    onInfo = { info = LogInfoContent(R.string.log_manager_box64_fexcore, LogCopy.BOX64) }) {
                    box64Logs = it; putBool("enable_box64_logs", it)
                }
                LogToggle(stringResource(R.string.log_manager_dxvk_vkd3d), dxvkLogs,
                    hint = stringResource(R.string.log_manager_dxvk_vkd3d_hint),
                    onInfo = { info = LogInfoContent(R.string.log_manager_dxvk_vkd3d, LogCopy.DXVK) }) {
                    dxvkLogs = it; putBool("enable_dxvk_logs", it)
                }
                LogToggle(stringResource(R.string.log_manager_android_logcat), logcat,
                    hint = stringResource(R.string.log_manager_android_logcat_hint),
                    onInfo = { info = LogInfoContent(R.string.log_manager_android_logcat, LogCopy.LOGCAT) }) {
                    logcat = it; putBool("enable_logcat", it)
                }
                LogToggle(stringResource(R.string.log_manager_crash_reports), crashReports,
                    hint = stringResource(R.string.log_manager_crash_reports_hint),
                    onInfo = { info = LogInfoContent(R.string.log_manager_crash_reports, LogCopy.CRASH) }) {
                    crashReports = it; putBool("enable_crash_reports", it)
                }

                // Outlined rather than a filled button: the design keeps solid accent for switches
                // only, and this is an occasional action, not the point of the screen.
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    CardAction(
                        stringResource(R.string.log_manager_capture_logcat_now),
                        modifier = Modifier.weight(1f).alpha(if (logcat) 1f else 0.4f)
                    ) {
                        if (!logcat) return@CardAction
                        // Runtime.exec + 1000 lines + a redaction pass + a file write: far too
                        // much for the UI thread (its own docs say so). Off to IO, refresh after.
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                LogcatCapture.captureToFile(context, LogcatCapture.DEFAULT_LINES)
                            }
                            refreshTick++
                        }
                    }
                    InfoDot {
                        info = LogInfoContent(R.string.log_manager_capture_logcat_now, LogCopy.CAPTURE_NOW)
                    }
                }
            }

            // ── Housekeeping ─────────────────────────────────────────────
            SectionLabel(stringResource(R.string.log_manager_housekeeping))
            LogCard {
                Box {
                    PickRow(
                        label = stringResource(R.string.log_manager_keep_last),
                        sub = if (keepLast == 0) {
                            stringResource(R.string.log_manager_no_history_each_run)
                        } else {
                            pluralStringResource(
                                R.plurals.log_manager_launches_per_game,
                                keepLast,
                                keepLast,
                            )
                        },
                        action = "▾",
                        onInfo = {
                            info = LogInfoContent(
                                R.string.log_manager_keep_last_launches,
                                LogCopy.KEEP_LAST,
                            )
                        },
                        onClick = { showKeepMenu = true }
                    )
                    DropdownMenu(
                        expanded = showKeepMenu,
                        onDismissRequest = { showKeepMenu = false },
                        modifier = Modifier.outlinedMenuCard(),
                    ) {
                        listOf(0, 1, 3, 5, 10, 20, 50).forEachIndexed { i, n ->
                            if (i > 0) MenuItemDivider()
                            MenuRow(
                                if (n == 0) {
                                    stringResource(R.string.log_manager_no_history)
                                } else {
                                    pluralStringResource(R.plurals.log_manager_launches, n, n)
                                },
                                if (n == 0) Icons.Default.HistoryToggleOff else Icons.Default.History
                            ) {
                                keepLast = n
                                prefs.edit().putInt(LogLocation.PREF_KEEP_LAST, n).apply()
                                showKeepMenu = false
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.log_manager_total_size),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.log_manager_total_size_across_folders,
                                entries.size,
                                LogInventory.humanBytes(LogInventory.totalBytes(entries)),
                                entries.size,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                        )
                    }
                    Text(stringResource(R.string.log_manager_action_browse), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { browseDir = LogLocation.resolveLogDir(context) }
                            .padding(horizontal = 8.dp, vertical = 6.dp))
                    Text(stringResource(R.string.log_manager_action_clear_all), color = DangerRed, fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { if (entries.isNotEmpty()) confirmClearAll = true }
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                            .alpha(if (entries.isEmpty()) 0.4f else 1f))
                }
            }

            // ── Logs by game ─────────────────────────────────────────────
            SectionLabel(stringResource(R.string.log_manager_logs_by_game))
            if (entries.isEmpty()) {
                LogCard {
                    Text(
                        stringResource(R.string.log_manager_no_logs_yet),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            } else {
                entries.forEach { e ->
                    GameLogCard(
                        entry = e,
                        onView = { viewing = e },
                        onShare = { shareLogGroup(context, e) },
                        onDelete = { confirmDelete = e }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.log_manager_explain_log_types),
                color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
                modifier = Modifier
                    .clickable { showExplainAll = true }
                    .padding(vertical = 6.dp, horizontal = 4.dp)
            )
            Text(
                // Third copy of this claim, and the one that overstated it most — "safe to share"
                // full stop. Same correction as LogReport and LogViewerScreen: name what is actually
                // stripped, and say plainly that paths are kept, because a path is often the thing
                // that makes a log worth having.
                stringResource(R.string.log_manager_privacy_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // The File Manager, opened at a log folder. This host block is what "Browse" needs to do
    // anything at all — without it browseDir is written and never read, which is exactly how the
    // button ended up dead. It also re-scans on close, since the File Manager can delete or move.
    browseDir?.let { dir ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { browseDir = null; refreshTick++ },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // A Dialog window is transparent and the File Manager draws no background of its own —
            // without this Surface it renders on top of whatever is behind the dialog.
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                FileManagerScreen(initialDir = dir)
            }
        }
    }

    viewing?.let { entry ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewing = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LogViewerScreen(entry = entry, onClose = { viewing = null })
            }
        }
    }

    confirmDelete?.let { entry ->
        val count = remember(entry.dir) { LogInventory.deletableCount(entry) }
        val entryDisplayName = logEntryDisplayName(entry)
        OutlinedAlertDialog(
            onDismissRequest = { confirmDelete = null },
            // The loose bucket is already named "Older logs"/"All logs", so "Delete ${name} logs?"
            // read "Delete Older logs logs?". Only a game name needs the word appending.
            title = {
                Text(
                    when {
                        entry.isAppBucket -> stringResource(R.string.log_manager_delete_app_crash_question)
                        entry.isLooseBucket -> stringResource(
                            R.string.log_manager_delete_bucket_question,
                            entryDisplayName,
                        )
                        else -> stringResource(
                            R.string.log_manager_delete_named_logs_question,
                            entry.name,
                        )
                    }
                )
            },
            text = {
                Text(
                    if (count == 0) {
                    // Reachable: the loose bucket counts every log-shaped file it finds, but only
                    // the ones we wrote are deletable. A folder holding nothing but a user's own
                    // files lands here, and must not imply we are about to touch them.
                        stringResource(R.string.log_manager_nothing_to_delete_message)
                    } else {
                        pluralStringResource(
                            R.plurals.log_manager_delete_group_message,
                            count,
                            count,
                        )
                    }
                )
            },
            confirmButton = {
                if (count > 0) {
                    TextButton(onClick = {
                        val n = LogInventory.deleteGroup(context, entry)
                        confirmDelete = null
                        refreshTick++
                        android.widget.Toast.makeText(
                            context,
                            context.resources.getQuantityString(
                                R.plurals.log_manager_deleted_files,
                                n,
                                n,
                            ),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) { Text(stringResource(R.string.log_manager_action_delete), color = DangerRed) }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) {
                    Text(
                        if (count == 0) stringResource(R.string.log_manager_action_ok)
                        else stringResource(R.string.log_manager_action_cancel)
                    )
                }
            }
        )
    }

    if (confirmClearAll) {
        val count = remember(refreshTick) { entries.sumOf { LogInventory.deletableCount(it) } }
        val fileCount = pluralStringResource(R.plurals.log_manager_log_file_count, count, count)
        val folderCount = pluralStringResource(
            R.plurals.log_manager_folder_count,
            entries.size,
            entries.size,
        )
        OutlinedAlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text(stringResource(R.string.log_manager_clear_all_question)) },
            text = {
                Text(
                    stringResource(
                        R.string.log_manager_clear_all_message,
                        fileCount,
                        folderCount,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    var n = 0
                    entries.forEach { n += LogInventory.deleteGroup(context, it) }
                    confirmClearAll = false
                    refreshTick++
                    android.widget.Toast.makeText(
                        context,
                        context.resources.getQuantityString(
                            R.plurals.log_manager_deleted_files,
                            n,
                            n,
                        ),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }) { Text(stringResource(R.string.log_manager_action_delete_all), color = DangerRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) {
                    Text(stringResource(R.string.log_manager_action_cancel))
                }
            }
        )
    }

    info?.let { content ->
        PerfInfoDialog(
            title = stringResource(content.titleRes),
            body = stringResource(content.bodyRes),
            onDismiss = { info = null },
        )
    }
    if (showExplainAll) {
        PerfInfoDialog(
            title = stringResource(R.string.log_manager_what_each_log_for),
            body = stringResource(LogCopy.EXPLAIN_ALL),
            onDismiss = { showExplainAll = false },
        )
    }
}

/** Small uppercase section heading that sits ABOVE its card, as in the design. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        letterSpacing = 0.08.em,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp)
    )
}

/** Untitled card — the heading lives above it now. */
@Composable
private fun LogCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) { content() }
}

/** A row that reads as a value with its detail underneath and an action on the right. */
@Composable
private fun PickRow(
    label: String,
    sub: String,
    action: String,
    onInfo: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2)
        }
        if (onInfo != null) InfoDot(onInfo)
        Text(action, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
    }
}

/** A drive-menu-style row: leading icon tinted primary, label, whole row clickable. */
@Composable
private fun MenuRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        },
        onClick = onClick,
    )
}

/** The channels people actually turn on. The other 500 are behind the search field. */
private val COMMON_WINE_CHANNELS = listOf(
    "err", "warn", "fixme", "seh", "module", "loaddll", "process", "thread",
    "sync", "file", "reg", "heap", "ntdll", "d3d", "dxgi", "vulkan", "opengl", "relay"
)

/**
 * Wine debug channels, built like the DXVK_HUD group in the env-var editor: a disclosure header,
 * the selection shown as static labels while collapsed, and a FilterChip grid when open.
 *
 * One deliberate difference. DXVK_HUD has ~20 options and TU_DEBUG ~30, so those can show every
 * chip when expanded; Wine has **521**, which is a wall of chips nobody can read and a slow
 * composition besides. So the expanded grid shows the channels that actually get used, and a
 * search field reaches the rest — the same escape hatch the env-var editor's own add-picker uses
 * for its oversized catalog. Anything already selected is always shown, however obscure.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WineChannelGroup(
    selected: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var browseAll by remember { mutableStateOf(false) }

    val all = remember {
        try {
            val arr = org.json.JSONArray(FileUtils.readString(context, "wine_debug_channels.json"))
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Selected first so a channel can always be switched back off, then the common set, then
    // whatever the query matches.
    val shown = remember(query, selected, all) {
        if (query.isBlank()) (selected + COMMON_WINE_CHANNELS).distinct()
        else all.filter { it.contains(query.trim(), ignoreCase = true) }.take(60)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
        ) {
            Text(
                stringResource(R.string.log_manager_channels),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                if (expanded) stringResource(R.string.log_manager_collapse_channels)
                else stringResource(R.string.log_manager_expand_channels),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        if (!expanded) {
            // Static labels, not chips: a tap that silently deselected a channel from a summary
            // view would be a destructive action hidden behind a collapsed control.
            if (selected.isEmpty()) {
                Text(stringResource(R.string.log_manager_none_selected), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 4.dp))
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    selected.forEach { ch ->
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(ch, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 11.sp)
                        }
                    }
                }
            }
            return@Column
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = {
                Text(
                    pluralStringResource(
                        R.plurals.log_manager_search_all_channels,
                        all.size,
                        all.size,
                    )
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            shown.forEach { ch ->
                val isOn = ch in selected
                FilterChip(
                    selected = isOn,
                    onClick = { onChange(if (isOn) selected - ch else selected + ch) },
                    label = { Text(ch, fontSize = 11.sp) }
                )
            }
            // Last chip in the flow, so it reads as "…and the rest are through here" rather than
            // as another channel. Searching only helps once you know a name to type; this is the
            // way in for someone who doesn't.
            if (all.isNotEmpty()) {
                AssistChip(
                    onClick = { browseAll = true },
                    label = {
                        Text(
                            pluralStringResource(
                                R.plurals.log_manager_browse_all_channels,
                                all.size,
                                all.size,
                            ),
                            fontSize = 11.sp,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ListAlt, null, modifier = Modifier.size(14.dp))
                    },
                )
            }
        }
        if (query.isNotBlank() && shown.isEmpty()) {
            Text(stringResource(R.string.log_manager_no_channel_matches, query),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }

        Text(
            stringResource(R.string.log_manager_reset_defaults),
            color = MaterialTheme.colorScheme.primary, fontSize = 12.sp,
            modifier = Modifier
                .clickable {
                    onChange(com.winlator.star.SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS
                        .split(",").filter { it.isNotBlank() })
                    query = ""
                }
                .padding(top = 6.dp, bottom = 2.dp)
        )
    }

    if (browseAll) {
        AllChannelsDialog(
            all = all,
            selected = selected,
            onChange = onChange,
            onDismiss = { browseAll = false },
        )
    }
}

/**
 * Every Wine channel, browsable. The chip grid above deliberately shows only the ~18 people use, and
 * the search field only helps if you already know a name to type — this is the way in for someone
 * who doesn't know what exists.
 *
 * Grouped by family rather than listed alphabetically: 521 names in one column is technically "all
 * of them" and practically unreadable, whereas "Sound" holding 15 entries is something a user can
 * actually shop from. "What's this?" turns on a one-line explanation under every row, which is the
 * difference between a list of names and a reference — it is off by default because with it on each
 * row is three lines tall and scanning gets slower.
 */
@Composable
private fun AllChannelsDialog(
    all: List<String>,
    selected: List<String>,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val localeTag = context.resources.configuration.locales[0].toLanguageTag()
    var query by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    var selectedOnly by remember { mutableStateOf(false) }

    // Grouping is pure string work over 521 items, so it is cheap — but it must not rerun on every
    // recomposition (every keystroke, every toggle), hence the key on the list identity alone.
    val grouped = remember(all) {
        all.groupBy { WineChannelInfo.categoryOf(it) }
            .toList()
            .sortedBy { (cat, _) ->
                WineChannelInfo.CATEGORY_ORDER.indexOf(cat)
                    .let { if (it < 0) WineChannelInfo.CATEGORY_ORDER.size else it }
            }
            // Within a category: the everyday ones first, then ones we can explain, then the rest
            // alphabetically. Plain alphabetical put `debug_buffer` at the top of "Errors and
            // tracing" and pushed err/warn/fixme/seh below the fold — the exact channels someone
            // opening this list came for. Sorting by "has a description" alone does not fix it,
            // because debug_buffer has one too; COMMON_WINE_CHANNELS is the actual priority order.
            // Deliberately NOT selected-first: rows would jump under the finger as you tick them.
            .map { (cat, names) ->
                cat to names.sortedWith(
                    compareBy<String> {
                        val i = COMMON_WINE_CHANNELS.indexOf(it)
                        if (i < 0) COMMON_WINE_CHANNELS.size else i
                    }
                        .thenByDescending { WineChannelInfo.hasDetail(it) }
                        .thenBy { it }
                )
            }
    }

    val visible = remember(grouped, query, selectedOnly, selected, localeTag) {
        val q = query.trim()
        grouped.mapNotNull { (cat, names) ->
            val categoryName = WineChannelInfo.categoryName(context, cat)
            val kept = names.filter { ch ->
                (q.isBlank() || ch.contains(q, ignoreCase = true) ||
                    categoryName.contains(q, ignoreCase = true)) &&
                    (!selectedOnly || ch in selected)
            }
            if (kept.isEmpty()) null else cat to kept
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            // Nearly full-bleed. This device is a landscape handheld, and at 0.9 height with the
            // title, search field and chips each on their own row the list got a single visible
            // row — the one part of the dialog that matters. The header is now one row and the
            // list takes everything left over.
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.95f),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.log_manager_all_wine_channels),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(
                                R.string.log_manager_channel_counts_summary,
                                pluralStringResource(
                                    R.plurals.log_manager_channels_available,
                                    all.size,
                                    all.size,
                                ),
                                pluralStringResource(
                                    R.plurals.log_manager_channels_on,
                                    selected.size,
                                    selected.size,
                                ),
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilterChip(
                        selected = showHelp,
                        onClick = { showHelp = !showHelp },
                        label = { Text(stringResource(R.string.log_manager_whats_this), fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Outlined.HelpOutline, null, modifier = Modifier.size(14.dp))
                        },
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    FilterChip(
                        selected = selectedOnly,
                        onClick = { selectedOnly = !selectedOnly },
                        label = { Text(stringResource(R.string.log_manager_on_only), fontSize = 11.sp) },
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.log_manager_action_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.log_manager_search), fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )

                if (visible.isEmpty()) {
                    Text(
                        if (selectedOnly && query.isBlank()) {
                            stringResource(R.string.log_manager_no_channels_on)
                        } else {
                            stringResource(R.string.log_manager_nothing_matches, query)
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        visible.forEach { (category, names) ->
                            item(key = "hdr-${category.name}") {
                                Column(modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                                    Text(
                                        WineChannelInfo.categoryName(context, category).uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    if (showHelp) {
                                        Text(
                                            WineChannelInfo.categoryBlurb(context, category),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            items(names, key = { "ch-$it" }) { ch ->
                                val isOn = ch in selected
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onChange(if (isOn) selected - ch else selected + ch)
                                        }
                                        .padding(vertical = 3.dp),
                                ) {
                                    Checkbox(
                                        checked = isOn,
                                        onCheckedChange = {
                                            onChange(if (isOn) selected - ch else selected + ch)
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
                                        Text(
                                            ch,
                                            fontSize = 13.sp,
                                            color = if (isOn) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                        if (showHelp) {
                                            Text(
                                                WineChannelInfo.describe(context, ch),
                                                fontSize = 10.sp,
                                                lineHeight = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }
    }
}

/**
 * One game's logs: icon, name, what is on disk, then View / Share / Delete as in the design.
 * Tapping the name expands the file list.
 */
@Composable
private fun GameLogCard(
    entry: LogInventory.Entry,
    onView: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayName = logEntryDisplayName(entry)
    val fileCount = pluralStringResource(
        R.plurals.log_manager_log_file_count,
        entry.fileCount,
        entry.fileCount,
    )
    val keptRuns = if (entry.archivedRuns > 0) {
        pluralStringResource(
            R.plurals.log_manager_kept_run_count,
            entry.archivedRuns,
            entry.archivedRuns,
        )
    } else {
        null
    }
    val lastUpdated = relativeTime(entry.lastModified)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    when {
                        entry.isAppBucket -> Icons.Default.PhoneAndroid
                        // Loose files in the log root are not a game and must not look like one.
                        entry.isLooseBucket -> Icons.Default.History
                        entry.dir.name.startsWith("Container") -> Icons.Default.Settings
                        else -> Icons.Default.SportsEsports
                    },
                    null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                Text(displayName,
                    color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
                Text(
                    buildString {
                        append(fileCount)
                        append(" · ").append(LogInventory.humanBytes(entry.totalBytes))
                        if (keptRuns != null) append(" · ").append(keptRuns)
                        append(" · ").append(lastUpdated)
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            LogInventory.filesIn(entry.dir).forEach { f ->
                Text("• ${f.name}  (${LogInventory.humanBytes(f.length())})",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp))
            }
        }
        // Divider then a three-up action row, as in the design.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 9.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp)
        ) {
            CardAction(stringResource(R.string.log_manager_action_view), modifier = Modifier.weight(1f), primary = true, onClick = onView)
            CardAction(stringResource(R.string.log_manager_action_share), modifier = Modifier.weight(1f), onClick = onShare)
            CardAction(stringResource(R.string.log_manager_action_delete), modifier = Modifier.weight(1f), danger = true, onClick = onDelete)
        }
    }
}

@Composable
private fun logEntryDisplayName(entry: LogInventory.Entry): String = when {
    entry.isAppBucket -> stringResource(R.string.log_manager_app_crash_logs)
    entry.isLooseBucket && entry.name == "Older logs" -> {
        stringResource(R.string.log_manager_older_logs)
    }
    entry.isLooseBucket -> stringResource(R.string.log_manager_all_logs)
    else -> entry.name
}

/** One of the three per-card actions. */
@Composable
private fun CardAction(
    label: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val fg = when {
        danger -> DangerRed
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(7.dp))
            .border(
                1.dp,
                if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(7.dp)
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = fg, fontSize = 12.sp)
    }
}

/** The design's one non-theme colour: destructive actions read as red in both light and dark. */

/** "12 min ago" / "yesterday" — a timestamp is not what anyone is looking for in this list. */
@Composable
private fun relativeTime(millis: Long): String {
    if (millis <= 0) return "—"
    val mins = (System.currentTimeMillis() - millis) / 60000
    return when {
        mins < 1 -> stringResource(R.string.log_manager_just_now)
        mins < 60 -> pluralStringResource(
            R.plurals.log_manager_minutes_ago,
            mins.toInt(),
            mins,
        )
        mins < 60 * 24 -> {
            val hours = mins / 60
            pluralStringResource(R.plurals.log_manager_hours_ago, hours.toInt(), hours)
        }
        mins < 60 * 48 -> stringResource(R.string.log_manager_yesterday)
        else -> {
            val days = mins / (60 * 24)
            pluralStringResource(R.plurals.log_manager_days_ago, days.toInt(), days)
        }
    }
}

@Composable
private fun LogToggle(
    label: String,
    checked: Boolean,
    hint: String? = null,
    enabled: Boolean = true,
    onInfo: (() -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (enabled) Modifier.clickable { onCheckedChange(!checked) } else Modifier.alpha(0.4f))
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
            if (hint != null) {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        if (onInfo != null) InfoDot(onInfo)
        Spacer(Modifier.width(4.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Always-live "?" — a locked or off toggle must still be explainable. */
@Composable
private fun InfoDot(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(Icons.Outlined.HelpOutline, stringResource(R.string.log_manager_whats_this),
            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/**
 * Help copy. Every entry leads with the performance cost, because that is the thing a user needs to
 * decide with — two of these are genuinely expensive to leave on.
 */
private object LogCopy {
    @StringRes val PER_GAME = R.string.log_manager_copy_per_game
    @StringRes val WINE = R.string.log_manager_copy_wine
    @StringRes val BOX64 = R.string.log_manager_copy_box64
    @StringRes val DXVK = R.string.log_manager_copy_dxvk
    @StringRes val LOGCAT = R.string.log_manager_copy_logcat
    @StringRes val CRASH = R.string.log_manager_copy_crash
    @StringRes val CAPTURE_NOW = R.string.log_manager_copy_capture_now
    @StringRes val KEEP_LAST = R.string.log_manager_copy_keep_last
    @StringRes val EXPLAIN_ALL = R.string.log_manager_copy_explain_all
}
