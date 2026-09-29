package com.winlator.star.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.communityconfigs.CommunityConfigApply
import com.winlator.star.container.Shortcut
import com.winlator.star.communityconfigs.ShortcutExporter
import com.winlator.star.communityconfigs.UploadedConfigsStore.UploadedConfig
import com.winlator.star.core.GameSaveBackup
import com.winlator.star.store.StarLaunchBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * The Games screen's per-game ⋮ menu: which dialog is open for which game, and the actions that open
 * them. Lifted out of ShortcutsScreen so the Deck game page runs the very same flows in place. Hold one
 * with [rememberShortcutActions], open things with [perform] (or the named helpers), and place
 * [ShortcutActionDialogs] in the same screen so the dialogs have somewhere to draw.
 */
@Stable
class ShortcutActions internal constructor(
    internal val vm: ShortcutsViewModel,
    private val context: Context,
    internal val scope: CoroutineScope,
) {
    internal var confirmRemove by mutableStateOf<Shortcut?>(null)
    internal var cloneTarget by mutableStateOf<Shortcut?>(null)
    // Save Backup (custom-import games): a picked .zip awaiting a target-container choice, plus the
    // label of the game the restore was launched from (shown in the container picker title).
    internal var restoreZipUri by mutableStateOf<Uri?>(null)
    internal var restoreForName by mutableStateOf("")
    // Emulator account ids a restore held back because the container already runs a different one.
    internal var emuConflicts by mutableStateOf<List<GameSaveBackup.EmuIdConflict>>(emptyList())
    // The shortcut whose "Back up saves" layout-choice dialog is open (Winlator vs GameHub).
    internal var backupFormatShortcut by mutableStateOf<Shortcut?>(null)
    internal var settingsShortcut by mutableStateOf<Shortcut?>(null)
    // "Copy to Drive C…" target — the game whose folder is being copied onto the container's C:
    // drive (and then repointed). Both entry points (the ⋮ menu item and the editor's Storage row)
    // set this; the shared CopyToDriveCCoordinator owns the whole confirm→copy→repoint flow.
    internal var copyToDriveCTarget by mutableStateOf<Shortcut?>(null)
    // "Change executable…" target — the game being repointed at a different .exe/.lnk (launcher →
    // real exe, dx11 ↔ dx9, a config tool). Both entry points set it; ChangeExecutableCoordinator
    // owns the pick → args-choice → rewrite flow via the shared CopyGameToDriveC.setShortcutExe.
    internal var changeExeTarget by mutableStateOf<Shortcut?>(null)
    internal var gameDetailsShortcut by mutableStateOf<Shortcut?>(null)
    internal var propertiesShortcut by mutableStateOf<Shortcut?>(null)
    internal var logsShortcut by mutableStateOf<Shortcut?>(null)
    internal var scrapeTarget by mutableStateOf<Shortcut?>(null)
    internal val scrapeCovers = mutableStateListOf<Pair<Bitmap, String>>()
    internal var scrapeLoading by mutableStateOf(false)
    internal var communityTarget by mutableStateOf<Shortcut?>(null)
    internal var communityResult by mutableStateOf<CommunityMatchResult?>(null)
    internal var communityLoading by mutableStateOf(false)
    // Catalog browser (catalog-first entry from the header) + the shared Phase 2 apply flow.
    internal var showCommunityBrowser by mutableStateOf(false)
    internal var applyPicker by mutableStateOf<CommunityPick?>(null)
    internal var applyMismatch by mutableStateOf<Pair<Shortcut, CommunityPick>?>(null)
    internal var applyBusy by mutableStateOf(false)
    internal var applyResult by mutableStateOf<CommunityConfigApply.ConfigApplyResult?>(null)
    // The shortcut the current result was applied to — threaded through so a post-install component
    // fixup can write the resolved version sub-field back to the right shortcut.
    internal var applyTarget by mutableStateOf<Shortcut?>(null)
    // Missing component the user tapped "Install" on → opens its single-type download sheet.
    internal var installSheetFor by mutableStateOf<CommunityConfigApply.MissingComponent?>(null)
    // Missing GPU driver the user tapped "Browse all drivers" on → opens the adrenotools driver browser.
    internal var driverSheetFor by mutableStateOf<CommunityConfigApply.MissingDriver?>(null)
    // Phase 3 step 2 — LOCAL export/import.
    // The generated export artifact awaiting a Share / Save-to-Downloads choice (null = no export sheet).
    internal var exportResult by mutableStateOf<ShortcutExporter.ExportResult?>(null)
    // The shortcut a freshly-picked import file applies to; null means it came from the catalog browser
    // (no target yet) so the picked file is stashed in [importedConfigUri] and a target picker is shown.
    internal var importPendingTarget by mutableStateOf<Shortcut?>(null)
    internal var importedConfigUri by mutableStateOf<Uri?>(null)
    // Phase 3 (online sharing) — UPLOAD. uploadingConfig gates the busy state; uploadStarted flips the
    // button text from "Preparing…" to "Uploading…" once the real upload begins (after any replace
    // confirm). When the user already shared a config for this game the worker gate is surfaced as a
    // replace-confirm: (existing record, proceed, cancel) — Replace calls proceed(), Cancel calls cancel()
    // so the parked coroutine unwinds cleanly.
    internal var uploadingConfig by mutableStateOf(false)
    internal var uploadStarted by mutableStateOf(false)
    internal var replaceUploadPrompt by mutableStateOf<Triple<UploadedConfig, () -> Unit, () -> Unit>?>(null)
    // Phase 3 (online sharing) — MY UPLOADS. showMyUploads opens the manager dialog; myUploads is the
    // loaded list (null = still loading). The list is expandable (single-expand via expandedUploadSha);
    // the expanded row's inline description editor shares uploadDescText / uploadDescLoading (reloaded on
    // expand). deleteUploadRow drives the delete-confirm sub-dialog.
    internal var showMyUploads by mutableStateOf(false)
    // Phase 2 (optional accounts) — the "My account" sheet. Opened from the globe browser's person icon;
    // hosts create/login/reset when logged out and profile + "My uploads" + "Log out" when signed in.
    internal var showMyAccount by mutableStateOf(false)
    internal var myUploads by mutableStateOf<List<MyUploadRow>?>(null)
    internal var deleteUploadRow by mutableStateOf<MyUploadRow?>(null)
    internal var expandedUploadSha by mutableStateOf<String?>(null)
    internal var uploadDescText by mutableStateOf("")
    internal var uploadDescLoading by mutableStateOf(false)
    // A tapped config row → small "Apply to game… | View details" chooser. The pair carries the picked
    // config (a specific uploaded file, or a device-row fallback) plus the in-context shortcut (non-null
    // from the per-shortcut sheet, null from the catalog browser where a target hasn't been chosen yet).
    internal var configAction by mutableStateOf<Pair<CommunityPick, Shortcut?>?>(null)
    // The config whose read-only detail page is open (same pick + optional-context-shortcut pair).
    internal var detailFor by mutableStateOf<Pair<CommunityPick, Shortcut?>?>(null)

    // Set by ShortcutActionDialogs, which owns the activity-result launcher the save-restore picker needs.
    internal var launchRestorePicker: (() -> Unit)? = null

    /** Open the per-game menu item [action] for [shortcut], exactly as the Games screen's ⋮ menu does. */
    fun perform(action: GameMenuAction, shortcut: Shortcut) {
        when (action) {
            GameMenuAction.SETTINGS -> settingsShortcut = shortcut
            GameMenuAction.REMOVE -> confirmRemove = shortcut
            GameMenuAction.CLONE -> cloneTarget = shortcut
            GameMenuAction.COPY_TO_DRIVE_C -> copyToDriveCTarget = shortcut
            GameMenuAction.CHANGE_EXE -> changeExeTarget = shortcut
            GameMenuAction.ADD_TO_HOME -> addToHomeScreen(context, shortcut)
            GameMenuAction.EXPORT -> exportShortcut(context, shortcut)
            GameMenuAction.GAME_DETAILS -> gameDetailsShortcut = shortcut
            GameMenuAction.CLOUD_SAVES -> launchSaveManager(context, steamAppIdOf(shortcut))
            GameMenuAction.BACKUP_SAVES -> startSaveBackup(shortcut)
            GameMenuAction.RESTORE_SAVES -> startSaveRestore(shortcut)
            GameMenuAction.SCRAPE_COVER -> scrapeCoverFor(shortcut)
            GameMenuAction.COMMUNITY_CONFIGS -> communityConfigsFor(shortcut)
            GameMenuAction.VIEW_LOGS -> logsShortcut = shortcut
            GameMenuAction.PROPERTIES -> propertiesShortcut = shortcut
            GameMenuAction.ADD_GAME -> Unit
        }
    }

    // Shared "Scrape cover" action so both grid tiles and list rows fire the same flow.
    fun scrapeCoverFor(shortcut: Shortcut) {
        scrapeTarget = shortcut
        scrapeCovers.clear()
        scrapeLoading = true
        scope.launch(Dispatchers.IO) {
            val json = StarLaunchBridge.sgdbFetchGridsJson(shortcut.name)
            val covers = mutableListOf<Pair<Bitmap, String>>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val thumbUrl = obj.optString("thumb", "")
                    val fullUrl = obj.optString("url", "")
                    if (thumbUrl.isNotEmpty() && fullUrl.isNotEmpty()) {
                        val conn = java.net.URL(thumbUrl).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 10000
                        val bmp = BitmapFactory.decodeStream(conn.inputStream)
                        conn.disconnect()
                        if (bmp != null) covers.add(bmp to fullUrl)
                    }
                }
            } catch (_: Exception) {}
            withContext(Dispatchers.Main) {
                scrapeCovers.clear()
                scrapeCovers.addAll(covers)
                scrapeLoading = false
            }
        }
    }

    // Shared "Community configs" action — opens the sheet and kicks off the offline-first match.
    fun communityConfigsFor(shortcut: Shortcut) {
        communityTarget = shortcut
        communityResult = null
        communityLoading = true
        vm.matchCommunityConfigs(shortcut) { result ->
            communityResult = result
            communityLoading = false
        }
    }

    // "Back up saves" → first pick the archive layout (Winlator vs GameHub), mirroring the Containers
    // backup menu; the chosen layout runs in runCustomBackup (ShortcutActionDialogs).
    fun startSaveBackup(shortcut: Shortcut) {
        backupFormatShortcut = shortcut
    }

    // Restore: pick a .zip (SAF) → then choose the target container (ContainerPickerDialog) → restore.
    fun startSaveRestore(shortcut: Shortcut) {
        restoreForName = shortcut.name
        launchRestorePicker?.invoke()
    }
}

@Composable
fun rememberShortcutActions(vm: ShortcutsViewModel): ShortcutActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(vm, context) { ShortcutActions(vm, context, scope) }
}
