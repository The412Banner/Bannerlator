package com.winlator.star.ui.screens

import android.app.Activity
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.components.ComponentCatalog
import com.winlator.star.components.GameRecommendations
import com.winlator.star.container.Shortcut
import com.winlator.star.core.GameFolderScanner
import com.winlator.star.core.WinePath
import com.winlator.star.store.SteamSdInstall
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.store.WindowsSteamClient
import com.winlator.star.ui.AccountUiBus
import com.winlator.star.ui.ComponentReturnBus
import com.winlator.star.ui.deck.rememberXmbHome
import com.winlator.star.ui.theme.Divider as DividerColor
import com.winlator.star.ui.theme.OnSurface
import com.winlator.star.ui.theme.OnSurfaceVariant
import com.winlator.star.util.InAppFilePicker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The game actions that are not tied to a list on screen: the "Add games" flows (one exe, a games
 * folder scanned for its games with the review and Confirm game dialogs, Android games), and the
 * one-shot requests other screens post (GamesScreenRequests, AccountUiBus's My account, the
 * component-install return to a game's settings). Lifted out of the Games screen so the Deck shell
 * runs them over whatever page is showing instead of opening the classic All games list.
 *
 * Draws no list of its own. The per-game dialogs a request opens are drawn by [ShortcutActionDialogs]
 * for the same [actions], which the caller places too (the Games screen already does).
 */
@Composable
fun GameActionsHost(vm: ShortcutsViewModel, actions: ShortcutActions) {
    val shortcuts by vm.shortcuts.collectAsState(initial = emptyList())
    val context = LocalContext.current
    // The XMB interface draws these popups with the XMB dialog kit (XmbDialogKit); every other style keeps the stock dialogs.
    val xmb = rememberXmbHome()
    var settingsShortcut by actions::settingsShortcut
    var showMyAccount by actions::showMyAccount
    var showImportContainerPicker by remember { mutableStateOf(false) }
    var pendingImportContainerIndex by remember { mutableStateOf(-1) }
    // Bulk games-folder import: pick one folder holding many game folders, scan each for its exe,
    // then confirm the findings before anything is written to the container.
    var showImportMethodPicker by remember { mutableStateOf(false) }
    // The + flow asks WHAT to add first; only the two Wine imports then ask for a container, and
    // this holds which of them the container pick is for (null = none pending).
    var pendingImportKind by remember { mutableStateOf<ImportKind?>(null) }
    // "Add Android game": the picker, and the write that follows its Add.
    var showAndroidPicker by remember { mutableStateOf(false) }
    var androidAddRunning by remember { mutableStateOf(false) }
    // "Add Windows Steam client x64": the container it goes into (the storage is asked first, -1 = none), the
    // storages offered and the one picked, and the download's progress (null = not running).
    var windowsSteamConfirmIndex by remember { mutableStateOf(-1) }
    var windowsSteamPlaces by remember { mutableStateOf<List<WindowsSteamClient.Place>>(emptyList()) }
    var windowsSteamPick by remember { mutableStateOf(-1) }
    var windowsSteamProgress by remember { mutableStateOf<Int?>(null) }
    var folderScanRunning by remember { mutableStateOf(false) }
    // The running scan, so the XMB scanning card's Cancel can stop waiting for it (its result is then dropped).
    var folderScanJob by remember { mutableStateOf<Job?>(null) }
    // The bulk import's progress (done to total) for the XMB review dialog's bar; null = not importing.
    var folderImportProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var folderScanResults by remember { mutableStateOf<List<GameFolderScanner.Candidate>>(emptyList()) }
    var folderScanSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var folderScanRoot by remember { mutableStateOf("") }
    var folderImportRunning by remember { mutableStateOf(false) }
    // Manual exe override for a scanned game. The scanner keeps every runner-up, so correcting a
    // pick is a choice from a list rather than a rescan.
    var exePickerFor by remember { mutableStateOf<GameFolderScanner.Candidate?>(null) }
    // Per scanned game: how many components it needs (by exe path), and which rows show them.
    var scanRecCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var scanRecsOpen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var exeBrowseForPath by remember { mutableStateOf("") }
    // When checked, the shortcut import uses the system SAF picker instead of the in-app File Manager.
    var importUseSystemPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameDialogName by remember { mutableStateOf("") }          // current on-disk base (Save's oldName)
    var renameDialogContainerIndex by remember { mutableStateOf(-1) }
    // Confirm-game dialog (upgraded rename dialog) state.
    var confirmNameField by remember { mutableStateOf("") }          // editable name text field
    var confirmNameEdited by remember { mutableStateOf(false) }      // user typed → stop auto-overwriting
    var confirmAppId by remember { mutableStateOf<Int?>(null) }      // detected/selected Steam appId
    var steamSearchResults by remember { mutableStateOf<List<SteamStoreSearch.SteamSuggestion>>(emptyList()) }
    var steamSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun handleShortcutImport(uri: Uri) {
        if (pendingImportContainerIndex >= 0) {
            val result = vm.importShortcut(pendingImportContainerIndex, uri, context)
            when (result) {
                is ImportResult.Success -> {
                    renameDialogContainerIndex = pendingImportContainerIndex
                    renameDialogName = result.shortcutName
                    confirmNameField = result.shortcutName
                    confirmNameEdited = false
                    confirmAppId = result.appId
                    steamSearchResults = emptyList()
                    steamSearching = false
                    showRenameDialog = true
                }
                is ImportResult.Error -> Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            }
            pendingImportContainerIndex = -1
        }
    }
    // System SAF picker (secondary).
    val importFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handleShortcutImport(uri)
    }
    // Built-in in-app file picker (primary).
    val importFileInAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedUri(result.data)?.let { handleShortcutImport(it) }
    }
    // Games-folder picker. Uses buildDirIntent (a real absolute path, works on SD) rather than SAF,
    // which hands back /mnt/media_rw/... paths the scanner can't read.
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data) else null
        if (path == null) {
            pendingImportContainerIndex = -1
            return@rememberLauncherForActivityResult
        }
        val containerIndex = pendingImportContainerIndex
        folderScanRoot = path
        folderScanRunning = true
        folderScanJob = scope.launch {
            // Filesystem-heavy: a large library on SD walks a lot of directories.
            val found = withContext(Dispatchers.IO) { vm.scanGamesFolder(containerIndex, path) }
            folderScanResults = found
            // Pre-select everything importable; duplicates stay off and can't be ticked.
            folderScanSelected = found.filter { !it.alreadyAdded }.map { it.exe.absolutePath }.toSet()
            folderScanRunning = false
            // What each game needs (its redistributables, what Steam installs with it), counted for
            // the list; a row's components open as chips to install right from the list.
            scanRecCounts = emptyMap()
            scanRecsOpen = emptySet()
            launch {
                val counts = withContext(Dispatchers.IO) {
                    val catalog = runCatching { ComponentCatalog.load() }.getOrDefault(emptyList()).map { it.name }.toSet()
                    found.associate { c ->
                        c.exe.absolutePath to GameRecommendations.detect(c.exe, null, context).count {
                            it.componentName in catalog && GameRecommendations.group(it) == GameRecommendations.Group.RECOMMENDED
                        }
                    }
                }
                scanRecCounts = counts.filterValues { it > 0 }
            }
            // Games whose files name no Steam app: look each title up on the Steam store (same title
            // only, remembered per name), so the list shows their art and the import carries the id.
            // Four at a time, so a big library's list fills in within seconds rather than one by one.
            launch {
                found.filter { it.appId == null && !it.alreadyAdded }.chunked(4).forEach { batch ->
                    batch.map { c ->
                        async(Dispatchers.IO) {
                            c to runCatching { SteamStoreSearch.findAppIdByName(context, c.name) }.getOrNull()
                        }
                    }.awaitAll().forEach { (c, id) ->
                        if (id != null) {
                            folderScanResults = folderScanResults.map {
                                if (it.exe.absolutePath == c.exe.absolutePath && it.appId == null) {
                                    it.copy(appId = id, coverUrl = SteamStoreSearch.coverUrl(id))
                                } else it
                            }
                        }
                    }
                }
            }
            if (found.isEmpty()) {
                Toast.makeText(context, "No games found in that folder", Toast.LENGTH_LONG).show()
                pendingImportContainerIndex = -1
            }
        }
    }
    // The nav-drawer's profile header, or the Deck shell's avatar, flips this one-shot flag: open the sheet.
    LaunchedEffect(AccountUiBus.openMyAccountRequested) {
        if (AccountUiBus.openMyAccountRequested) {
            AccountUiBus.openMyAccountRequested = false
            showMyAccount = true
        }
    }
    // Tier-2 session-return: after an installer-based component install finishes and MainActivity has
    // routed us here, re-open the originating shortcut's settings (defaults to the Win Components tab,
    // where its recommended-components chips live). Keyed on the shortcuts list too, since it loads
    // async — wait for it, then resolve by container + base name. Best-effort: no match ⇒ nothing opens.
    LaunchedEffect(ComponentReturnBus.openShortcutSettings, shortcuts) {
        val target = ComponentReturnBus.openShortcutSettings ?: return@LaunchedEffect
        if (shortcuts.isEmpty()) return@LaunchedEffect // still loading (or none) — retry when it changes
        ComponentReturnBus.openShortcutSettings = null
        shortcuts.firstOrNull {
            it.container.id == target.containerId && it.name == target.shortcutBase
        }?.let { settingsShortcut = it }
    }

    // One-shot requests (see GamesScreenRequests): the + button, the Deck's "Add games", and the game
    // actions the Deck settings pages hand over (Game details, Copy to Drive C, Change executable, Move).
    LaunchedEffect(GamesScreenRequests.pending) {
        val req = GamesScreenRequests.pending ?: return@LaunchedEffect
        GamesScreenRequests.pending = null
        if (req.action == GameMenuAction.ADD_GAME) {
            // Ask what to add first (Android games never need a container).
            showImportMethodPicker = true
            return@LaunchedEffect
        }
        val path = req.shortcutPath ?: return@LaunchedEffect
        // A game added or renamed since this list last loaded is found after one re-read.
        val s = vm.currentShortcuts().firstOrNull { it.file.path == path }
            ?: run { vm.refresh(); vm.currentShortcuts().firstOrNull { it.file.path == path } }
            ?: return@LaunchedEffect
        actions.perform(req.action, s)
    }

    /**
     * Swaps the chosen exe for one scanned game. The user has now made the call explicitly, so the
     * "check this one" flag is cleared, the previous pick joins the alternatives (in case they want
     * to go back), and the selection set is re-keyed since it is keyed by exe path.
     */
    fun replaceScannedExe(target: GameFolderScanner.Candidate, newExe: File) {
        val oldKey = target.exe.absolutePath
        val newKey = newExe.absolutePath
        folderScanResults = folderScanResults.map { c ->
            if (c.exe.absolutePath != oldKey) c else c.copy(
                exe = newExe,
                uncertain = false,
                alternatives = (listOf(c.exe) + c.alternatives)
                    .distinctBy { it.absolutePath }
                    .filter { it.absolutePath != newKey },
            )
        }
        if (oldKey in folderScanSelected) folderScanSelected = folderScanSelected - oldKey + newKey
    }

    // "Browse…" result from the exe-override dialog: any file the user points at wins outright.
    val importExeBrowseLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val path = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data) else null
        val target = folderScanResults.firstOrNull { it.exe.absolutePath == exeBrowseForPath }
        if (path != null && target != null) replaceScannedExe(target, File(path))
        exeBrowseForPath = ""
    }

    // Manual exe override — every candidate exe found in that game's folder, best-ranked first.
    exePickerFor?.let { target ->
        val options = (listOf(target.exe) + target.alternatives).distinctBy { it.absolutePath }
        if (xmb) {
            XmbExePickerDialog(
                target = target,
                options = options,
                onPick = { exe ->
                    replaceScannedExe(target, exe)
                    exePickerFor = null
                },
                onBrowse = {
                    exeBrowseForPath = target.exe.absolutePath
                    exePickerFor = null
                    importExeBrowseLauncher.launch(
                        InAppFilePicker.buildIntent(context, InAppFilePicker.SHORTCUT, "Select the game's .exe")
                    )
                },
                onDismiss = { exePickerFor = null },
            )
            return@let
        }
        OutlinedAlertDialog(
            // The platform default width truncates exe names and their subfolder paths, which are
            // the whole point of this list.
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { exePickerFor = null },
            title = {
                Column {
                    Text("Choose the game's .exe")
                    Text(
                        target.name,
                        color = OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(options, key = { it.absolutePath }) { exe ->
                        val current = exe.absolutePath == target.exe.absolutePath
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable {
                                    replaceScannedExe(target, exe)
                                    exePickerFor = null
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = current, onClick = {
                                replaceScannedExe(target, exe)
                                exePickerFor = null
                            })
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    exe.name,
                                    color = OnSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                // Where it sits inside the game folder — the thing that actually
                                // distinguishes two same-named exes (x86 vs x64, bin/ vs root).
                                val rel = exe.absolutePath
                                    .removePrefix(target.folder.absolutePath)
                                    .removePrefix("/")
                                Text(
                                    if (rel.contains('/')) rel.substringBeforeLast('/') else "(folder root)",
                                    color = OnSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        }
                    }
                }
            },
            confirmButton = {
                // Escape hatch: the real exe can be missing from the list if it was filtered as an
                // installer/helper or sits deeper than the scan goes.
                TextButton(onClick = {
                    exeBrowseForPath = target.exe.absolutePath
                    exePickerFor = null
                    importExeBrowseLauncher.launch(
                        InAppFilePicker.buildIntent(context, InAppFilePicker.SHORTCUT, "Select the game's .exe")
                    )
                }) { Text("Browse…") }
            },
            dismissButton = {
                TextButton(onClick = { exePickerFor = null }) { Text("Cancel") }
            },
        )
    }

    // Once a container is picked for a Wine import, open the matching file/folder picker. A container
    // is chosen by then, so hand the in-app picker its C: drive: a game living on C: imports as C:\…
    // via WinePath and runs FROM the container's C:. Null-safe: only when the C: drive actually
    // exists on disk. The system picker (SAF) has no C: notion.
    fun launchImportPicker(kind: ImportKind) {
        val driveC = pendingImportContainerIndex.takeIf { it >= 0 }
            ?.let { vm.containers().getOrNull(it) }
            ?.let { File(it.rootDir, ".wine/drive_c") }
            ?.takeIf { it.isDirectory }
        when (kind) {
            ImportKind.EXE ->
                if (importUseSystemPicker) importFileLauncher.launch("*/*")
                else importFileInAppLauncher.launch(
                    InAppFilePicker.buildIntent(context, InAppFilePicker.SHORTCUT, "Select .exe / .desktop / .lnk", driveCPath = driveC?.absolutePath)
                )
            ImportKind.FOLDER -> importFolderLauncher.launch(
                InAppFilePicker.buildDirIntent(context, "Select your games folder", driveCPath = driveC?.absolutePath)
            )
            // One client per container: a container that has it already gets no second shortcut.
            ImportKind.WINDOWS_STEAM -> {
                val c = vm.containers().getOrNull(pendingImportContainerIndex)
                if (c != null && WindowsSteamClient.findShortcut(c) != null) {
                    Toast.makeText(context, "${WindowsSteamClient.SHORTCUT_NAME} is already in ${c.name}", Toast.LENGTH_LONG).show()
                    pendingImportContainerIndex = -1
                } else {
                    windowsSteamConfirmIndex = pendingImportContainerIndex
                }
            }
        }
    }

    // What to add, asked first: one exe (the original flow), a whole folder of game folders — both
    // then ask for a container — or Android games, which never use one.
    if (showImportMethodPicker && xmb) {
        XmbImportMethodDialog(
            onDismiss = { showImportMethodPicker = false },
            onExe = {
                showImportMethodPicker = false
                pendingImportKind = ImportKind.EXE
                showImportContainerPicker = true
            },
            onFolder = {
                showImportMethodPicker = false
                pendingImportKind = ImportKind.FOLDER
                showImportContainerPicker = true
            },
            onAndroid = {
                showImportMethodPicker = false
                showAndroidPicker = true
            },
            onWindowsSteam = {
                showImportMethodPicker = false
                pendingImportKind = ImportKind.WINDOWS_STEAM
                showImportContainerPicker = true
            },
        )
    } else if (showImportMethodPicker) {
        OutlinedAlertDialog(
            onDismissRequest = { showImportMethodPicker = false },
            title = { Text("Add games") },
            text = {
                Column {
                    MenuOptionCard(
                        title = "Add game EXE",
                        subtitle = "Pick one game's .exe file",
                        icon = Icons.Default.InsertDriveFile,
                    ) {
                        showImportMethodPicker = false
                        pendingImportKind = ImportKind.EXE
                        showImportContainerPicker = true
                    }
                    MenuOptionCard(
                        title = "Add games folder",
                        subtitle = "Pick the folder holding all your games — each one is scanned for its .exe",
                        icon = Icons.Default.Folder,
                    ) {
                        showImportMethodPicker = false
                        pendingImportKind = ImportKind.FOLDER
                        showImportContainerPicker = true
                    }
                    // No container: Android games live in their own home (see AndroidGames).
                    MenuOptionCard(
                        title = "Add Android game",
                        subtitle = "Pick games installed on this phone — they open as normal Android apps",
                        icon = Icons.Filled.Android,
                    ) {
                        showImportMethodPicker = false
                        showAndroidPicker = true
                    }
                    // Valve's 64-bit Windows Steam, downloaded from Valve onto the storage picked after the container.
                    MenuOptionCard(
                        title = "Add Windows Steam client x64",
                        subtitle = "Valve's 64-bit Steam for Windows, downloaded from Steam",
                        icon = Icons.Filled.DesktopWindows,
                    ) {
                        showImportMethodPicker = false
                        pendingImportKind = ImportKind.WINDOWS_STEAM
                        showImportContainerPicker = true
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportMethodPicker = false }) { Text("Cancel") }
            },
        )
    }

    // Storages are read off the main thread each time the dialog opens; the SD card is picked when it has room.
    LaunchedEffect(windowsSteamConfirmIndex) {
        windowsSteamPlaces = emptyList()
        windowsSteamPick = -1
        if (windowsSteamConfirmIndex < 0) return@LaunchedEffect
        val places = withContext(Dispatchers.IO) { WindowsSteamClient.places(context) }
        windowsSteamPlaces = places
        windowsSteamPick = places.indexOfFirst { it.removable && it.fits }.takeIf { it >= 0 }
            ?: places.indexOfFirst { it.fits }
    }

    if (windowsSteamConfirmIndex >= 0) {
        val target = vm.containers().getOrNull(windowsSteamConfirmIndex)
        val running = windowsSteamProgress != null
        val picked = windowsSteamPlaces.getOrNull(windowsSteamPick)
        val close = {
            windowsSteamConfirmIndex = -1
            pendingImportContainerIndex = -1
        }
        // The Download / Add action, shared by the stock dialog's button and the XMB dialog's.
        val startSteamAdd: () -> Unit = start@{
            val c = target
            val place = picked
            if (c == null || place == null) {
                close()
                return@start
            }
            windowsSteamProgress = 0
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    WindowsSteamClient.addClient(context, c, place.dir) { pct -> scope.launch { windowsSteamProgress = pct } }
                }
                windowsSteamProgress = null
                close()
                vm.refresh()
                val message = when (result) {
                    is WindowsSteamClient.AddResult.Added -> "Added ${WindowsSteamClient.SHORTCUT_NAME}"
                    is WindowsSteamClient.AddResult.AlreadyAdded -> "${WindowsSteamClient.SHORTCUT_NAME} is already in ${c.name}"
                    is WindowsSteamClient.AddResult.Failed -> result.message
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        if (xmb) {
            XmbWindowsSteamDialog(
                title = WindowsSteamClient.SHORTCUT_NAME,
                places = windowsSteamPlaces,
                pick = windowsSteamPick,
                onPick = { windowsSteamPick = it },
                progress = windowsSteamProgress,
                confirmText = if (picked?.hasClient == true) "Add" else "Download",
                confirmEnabled = picked != null && picked.fits,
                onConfirm = startSteamAdd,
                onCancel = close,
            )
        } else
        OutlinedAlertDialog(
            onDismissRequest = { if (!running) close() },
            title = { Text(WindowsSteamClient.SHORTCUT_NAME) },
            text = {
                val p = windowsSteamProgress
                if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Downloading Steam… $p%", color = OnSurfaceVariant)
                    }
                } else {
                    Column {
                        Text("Where should Steam go?", color = OnSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        windowsSteamPlaces.forEachIndexed { i, place ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = place.fits) { windowsSteamPick = i }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = windowsSteamPick == i,
                                    onClick = { windowsSteamPick = i },
                                    enabled = place.fits,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        place.label,
                                        color = if (place.fits) OnSurface else OnSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        when {
                                            place.hasClient -> "Steam is already here"
                                            place.fits -> "${SteamSdInstall.fmtBytes(place.freeBytes)} free"
                                            else -> "Not enough space (${SteamSdInstall.fmtBytes(place.freeBytes)} free)"
                                        },
                                        color = OnSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (!running) TextButton(enabled = picked != null && picked.fits, onClick = startSteamAdd) { Text(if (picked?.hasClient == true) "Add" else "Download") }
            },
            dismissButton = {
                if (!running) TextButton(onClick = close) { Text("Cancel") }
            },
        )
    }

    // After a Windows Steam shortcut is removed (any layout): "Also uninstall Steam?".
    val steamUninstallOffers by WindowsSteamClient.uninstallOffers.collectAsState()
    steamUninstallOffers.firstOrNull()?.let { offer -> WindowsSteamUninstallDialog(offer) }

    if (showAndroidPicker) {
        val added = remember(shortcuts) { AndroidGames.addedPackages(shortcuts) }
        AndroidGamePickerDialog(
            alreadyAdded = added,
            adding = androidAddRunning,
            onDismiss = { showAndroidPicker = false },
            onAdd = { apps ->
                androidAddRunning = true
                scope.launch {
                    val summary = withContext(Dispatchers.IO) { vm.addAndroidGames(apps, context) }
                    androidAddRunning = false
                    showAndroidPicker = false
                    val message = if (summary.failed == 0) {
                        "Added ${summary.added} Android game${if (summary.added == 1) "" else "s"}"
                    } else {
                        "Added ${summary.added}, ${summary.failed} failed — ${summary.failures.firstOrNull().orEmpty()}"
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    // Scanning progress — a big library on SD takes a moment.
    if (folderScanRunning && xmb) {
        XmbScanProgressDialog(
            root = folderScanRoot,
            onCancel = {
                // The scan is a blocking walk that cannot be interrupted: Cancel stops waiting for it and drops what it finds.
                folderScanJob?.cancel()
                folderScanJob = null
                folderScanRunning = false
                pendingImportContainerIndex = -1
            },
        )
    } else if (folderScanRunning) {
        OutlinedAlertDialog(
            onDismissRequest = {},
            title = { Text("Scanning for games") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(folderScanRoot, color = OnSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {},
        )
    }

    // Confirm screen — nothing is written until the user accepts this list.
    if (folderScanResults.isNotEmpty() && !folderScanRunning) {
        val importable = folderScanResults.filter { !it.alreadyAdded }
        val selectedCount = folderScanSelected.size
        // Writes the ticked games to the container: shared by the stock dialog's Add and the XMB review's.
        val startFolderImport: () -> Unit = {
            val containerIndex = pendingImportContainerIndex
            val chosen = folderScanResults.filter { it.exe.absolutePath in folderScanSelected }
            folderImportRunning = true
            folderImportProgress = 0 to chosen.size
            scope.launch {
                val summary = withContext(Dispatchers.IO) {
                    vm.importScannedGames(containerIndex, chosen, context) { done, total ->
                        scope.launch { folderImportProgress = done to total }
                    }
                }
                folderImportRunning = false
                folderImportProgress = null
                folderScanResults = emptyList()
                folderScanSelected = emptySet()
                pendingImportContainerIndex = -1
                val message = if (summary.failed == 0) {
                    "Added ${summary.added} game${if (summary.added == 1) "" else "s"}"
                } else {
                    "Added ${summary.added}, ${summary.failed} failed — ${summary.failures.firstOrNull().orEmpty()}"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        if (xmb) {
            XmbFolderReviewDialog(
                candidates = folderScanResults,
                selected = folderScanSelected,
                importing = folderImportRunning,
                importProgress = folderImportProgress,
                onToggle = { candidate ->
                    val key = candidate.exe.absolutePath
                    folderScanSelected = if (key in folderScanSelected) {
                        folderScanSelected - key
                    } else {
                        folderScanSelected + key
                    }
                },
                onChangeExe = { candidate -> exePickerFor = candidate },
                onAdd = startFolderImport,
                onCancel = {
                    // Back / B while the import runs is ignored by the dialog itself; this is the idle close.
                    folderScanResults = emptyList()
                    folderScanSelected = emptySet()
                    pendingImportContainerIndex = -1
                },
                componentsFor = { candidate ->
                    val recCount = scanRecCounts[candidate.exe.absolutePath] ?: 0
                    val scanContainer = vm.containers().getOrNull(pendingImportContainerIndex)
                    if (recCount > 0 && scanContainer != null) {
                        val key = candidate.exe.absolutePath
                        val open = key in scanRecsOpen
                        XmbScanComponents(
                            count = recCount,
                            open = open,
                            onToggle = { scanRecsOpen = if (open) scanRecsOpen - key else scanRecsOpen + key },
                        ) {
                            RecommendedComponentsSection(container = scanContainer, exeFile = candidate.exe)
                        }
                    }
                },
            )
        } else
        OutlinedAlertDialog(
            // Each row carries art, a checkbox, three lines of text and a Change action; at the
            // platform default width the titles and the exe name truncate to the point of being
            // unreadable, which defeats a screen whose whole job is letting the user check them.
            modifier = Modifier.fillMaxWidth(0.94f),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = {
                if (!folderImportRunning) {
                    folderScanResults = emptyList()
                    folderScanSelected = emptySet()
                    pendingImportContainerIndex = -1
                }
            },
            title = {
                Column {
                    Text("Found ${importable.size} game${if (importable.size == 1) "" else "s"}")
                    val skipped = folderScanResults.size - importable.size
                    if (skipped > 0) {
                        Text(
                            "$skipped already added",
                            color = OnSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(folderScanResults, key = { it.exe.absolutePath }) { candidate ->
                        Column(Modifier.fillMaxWidth()) {
                            ScannedGameRow(
                                candidate = candidate,
                                checked = candidate.exe.absolutePath in folderScanSelected,
                                enabled = !candidate.alreadyAdded && !folderImportRunning,
                                onToggle = {
                                    val key = candidate.exe.absolutePath
                                    folderScanSelected = if (key in folderScanSelected) {
                                        folderScanSelected - key
                                    } else {
                                        folderScanSelected + key
                                    }
                                },
                                onChangeExe = { exePickerFor = candidate },
                                footer = {
                                    val recCount = scanRecCounts[candidate.exe.absolutePath] ?: 0
                                    val scanContainer = vm.containers().getOrNull(pendingImportContainerIndex)
                                    if (recCount > 0 && scanContainer != null) {
                                        val key = candidate.exe.absolutePath
                                        val open = key in scanRecsOpen
                                        TextButton(
                                            onClick = { scanRecsOpen = if (open) scanRecsOpen - key else scanRecsOpen + key },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            modifier = Modifier.height(32.dp),
                                        ) {
                                            Text(
                                                (if (open) "▾ " else "▸ ") + "Components ($recCount)",
                                                fontSize = 13.sp,
                                            )
                                        }
                                        if (open) {
                                            RecommendedComponentsSection(
                                                container = scanContainer,
                                                exeFile = candidate.exe,
                                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedCount > 0 && !folderImportRunning,
                    onClick = startFolderImport,
                ) {
                    Text(if (folderImportRunning) "Adding…" else "Add $selectedCount")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !folderImportRunning,
                    onClick = {
                        folderScanResults = emptyList()
                        folderScanSelected = emptySet()
                        pendingImportContainerIndex = -1
                    },
                ) { Text("Cancel") }
            },
        )
    }

    // Import container picker
    if (showImportContainerPicker) {
        val containers = vm.containers()
        if (xmb) {
            XmbContainerPickerDialog(
                title = "Select container",
                subtitle = when (pendingImportKind) {
                    ImportKind.EXE -> "The game's .exe goes into this container"
                    ImportKind.FOLDER -> "The games found in the folder go into this container"
                    ImportKind.WINDOWS_STEAM -> "Steam for Windows goes into this container"
                    null -> null
                },
                choices = containers.map { c ->
                    XmbChoice(
                        title = c.name,
                        subtitle = listOf(c.wineVersion.orEmpty(), c.screenSize.orEmpty()).filter { it.isNotEmpty() }
                            .joinToString(" · ").ifEmpty { null },
                    )
                },
                emptyText = "No containers found.",
                onPick = { index ->
                    showImportContainerPicker = false
                    pendingImportContainerIndex = index
                    pendingImportKind?.let { launchImportPicker(it) }
                    pendingImportKind = null
                },
                onDismiss = {
                    showImportContainerPicker = false
                    pendingImportKind = null
                },
                // Only the .exe import can use the system picker (a folder never does).
                systemPickerChecked = if (pendingImportKind == ImportKind.EXE) importUseSystemPicker else null,
                onSystemPickerChange = { importUseSystemPicker = it },
            )
        } else
        OutlinedAlertDialog(
            onDismissRequest = {
                showImportContainerPicker = false
                pendingImportKind = null
            },
            title = { Text("Select container") },
            text = {
                Column {
                    if (containers.isEmpty()) {
                        Text("No containers found.", color = OnSurfaceVariant)
                    } else {
                        // Scroll the container list so it can't be clipped when there are many
                        // containers and vertical space is tight (landscape). "Pick via system…"
                        // stays pinned below the scroll area so it's always reachable.
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            containers.forEachIndexed { index, c ->
                                MenuOptionCard(
                                    title = c.name,
                                    icon = Icons.Default.Folder,
                                ) {
                                    showImportContainerPicker = false
                                    pendingImportContainerIndex = index
                                    pendingImportKind?.let { launchImportPicker(it) }
                                    pendingImportKind = null
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Checkbox(checked = importUseSystemPicker, onCheckedChange = { importUseSystemPicker = it })
                            Text("Pick via system…", color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showImportContainerPicker = false
                    pendingImportKind = null
                }) { Text("Cancel") }
            },
        )
    }

    // Follow the importer's async Steam-name auto-rename inside an open confirm dialog: keep the
    // Save target (renameDialogName = the on-disk base) in sync, and update the editable field
    // unless the user has already typed into it. Race-free — all on the main thread, one file writer.
    val importedNameUpdate by vm.importedNameUpdate.collectAsState()
    LaunchedEffect(importedNameUpdate) {
        val update = importedNameUpdate ?: return@LaunchedEffect
        if (showRenameDialog && renameDialogName == update.oldBase) {
            renameDialogName = update.newBase
            if (!confirmNameEdited) confirmNameField = update.newBase
        }
        vm.consumeImportedNameUpdate()
    }

    // Confirm game after import: editable name + "Search Steam" picker (fixes launcher-named
    // shortcuts + wrong cover art). Reuses the existing rename mechanism on Save.
    if (showRenameDialog) {
        val confirmContainer = vm.containers().getOrNull(renameDialogContainerIndex)
        // The result this game is linked to: the appId it was given, else the one whose title is
        // the game's (the rule the import's own Steam lookup uses). Save commits it.
        val linkedHit = confirmAppId ?: steamSearchResults
            .map { it to SteamStoreSearch.nameMatch(confirmNameField, it.name) }
            .filter { it.second >= 2 }.maxByOrNull { it.second }?.first?.appId
        // Save and Skip, shared by the stock dialog's buttons and the XMB dialog's.
        val confirmSave: () -> Unit = {
            val name = confirmNameField.trim()
            // The linked game (tapped, or the marked match) is committed by Save itself: its id
            // goes on the current file first, so it rides through the rename.
            val tapped = confirmAppId != null
            val linkId = confirmAppId ?: linkedHit
            linkId?.let { id ->
                confirmContainer?.let { c -> recordSteamAppId(c, renameDialogName, id) }
            }
            val oldBase = renameDialogName
            if (name.isNotEmpty()) {
                vm.renameImportedShortcut(renameDialogContainerIndex, renameDialogName, name)
            }
            if (linkId != null && confirmContainer != null) {
                val safe = name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                val base = if (safe.isNotBlank() && File(confirmContainer.getDesktopDir(), "$safe.desktop").isFile) safe else oldBase
                scope.launch(Dispatchers.IO) {
                    // Its Steam cover (unless one was just applied by tapping) and its details.
                    if (!tapped) applySteamCover(confirmContainer, base, linkId)
                    ExeShortcutImporter.linkSteamApp(confirmContainer, base, linkId)
                    withContext(Dispatchers.Main) { vm.refresh() }
                }
            }
            showRenameDialog = false
            Toast.makeText(context, "Shortcut imported.", Toast.LENGTH_SHORT).show()
        }
        val confirmSkip: () -> Unit = {
            showRenameDialog = false
            Toast.makeText(context, "Shortcut imported.", Toast.LENGTH_SHORT).show()
        }
        if (xmb) {
            // The search runs by itself when the dialog opens (see the stock dialog's note below).
            LaunchedEffect(renameDialogName) {
                val query = confirmNameField.trim()
                if (query.isNotEmpty() && steamSearchResults.isEmpty() && !steamSearching) {
                    steamSearching = true
                    steamSearchResults = withContext(Dispatchers.IO) { SteamStoreSearch.searchByName(query) }
                    steamSearching = false
                }
            }
            XmbConfirmGameDialog(
                name = confirmNameField,
                onNameChange = { confirmNameField = it; confirmNameEdited = true },
                onNameClear = { confirmNameField = ""; confirmNameEdited = true },
                searching = steamSearching,
                onSearch = {
                    val query = confirmNameField.trim()
                    if (query.isNotEmpty() && confirmContainer != null) {
                        steamSearching = true
                        steamSearchResults = emptyList()
                        scope.launch(Dispatchers.IO) {
                            val results = SteamStoreSearch.searchByName(query)
                            withContext(Dispatchers.Main) {
                                steamSearchResults = results
                                steamSearching = false
                            }
                        }
                    }
                },
                results = steamSearchResults,
                linkedAppId = linkedHit,
                onPickResult = { hit ->
                    // Set name + record appId; edit-guard off so the async auto-rename won't clobber the user's explicit pick.
                    confirmNameField = hit.name
                    confirmNameEdited = true
                    confirmAppId = hit.appId
                    // Apply this appId's cover art to the current on-disk shortcut.
                    if (confirmContainer != null) {
                        val base = renameDialogName
                        scope.launch(Dispatchers.IO) {
                            val bmp = applySteamCover(confirmContainer, base, hit.appId)
                            if (bmp != null) withContext(Dispatchers.Main) {
                                vm.reloadShortcut(
                                    File(confirmContainer.getDesktopDir(), "$base.desktop").path,
                                    bmp,
                                )
                            }
                        }
                    }
                },
                onSave = confirmSave,
                onSkip = confirmSkip,
                onDismiss = { showRenameDialog = false },
                // Recommended components: the redists this game bundles, one-tap installable into its container.
                components = {
                    if (confirmContainer != null) {
                        val importedExe = remember(confirmContainer, renameDialogName) {
                            runCatching {
                                val sc = Shortcut(
                                    confirmContainer,
                                    File(confirmContainer.getDesktopDir(), "$renameDialogName.desktop"),
                                )
                                WinePath.resolveAndroidPath(confirmContainer, sc.path)
                            }.getOrNull()
                        }
                        if (importedExe != null) {
                            RecommendedComponentsSection(
                                container = confirmContainer,
                                exeFile = importedExe,
                                shortcutBaseName = renameDialogName,
                            )
                        }
                    }
                },
            )
        } else
        OutlinedAlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Confirm game") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // The search runs by itself when the dialog opens, so the game the import matched
                    // is already listed (and marked) under the name, as if Search Steam had been tapped.
                    LaunchedEffect(renameDialogName) {
                        val query = confirmNameField.trim()
                        if (query.isNotEmpty() && steamSearchResults.isEmpty() && !steamSearching) {
                            steamSearching = true
                            steamSearchResults = withContext(Dispatchers.IO) { SteamStoreSearch.searchByName(query) }
                            steamSearching = false
                        }
                    }
                    OutlinedTextField(
                        value = confirmNameField,
                        onValueChange = { confirmNameField = it; confirmNameEdited = true },
                        label = { Text("Game name") },
                        singleLine = true,
                        trailingIcon = {
                            if (confirmNameField.isNotEmpty()) {
                                IconButton(onClick = { confirmNameField = ""; confirmNameEdited = true }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Button(
                        onClick = {
                            val query = confirmNameField.trim()
                            if (query.isEmpty() || confirmContainer == null) return@Button
                            steamSearching = true
                            steamSearchResults = emptyList()
                            scope.launch(Dispatchers.IO) {
                                val results = SteamStoreSearch.searchByName(query)
                                withContext(Dispatchers.Main) {
                                    steamSearchResults = results
                                    steamSearching = false
                                }
                            }
                        },
                        enabled = !steamSearching && confirmNameField.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (steamSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Searching…")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Search Steam")
                        }
                    }

                    if (steamSearchResults.isNotEmpty()) {
                        Text(
                            "Tap a result to set the name + cover art:",
                            color = OnSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        // The linked result first, tinted, so the match is the first thing seen.
                        steamSearchResults.sortedByDescending { it.appId == linkedHit }.forEach { hit ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (hit.appId == linkedHit) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else androidx.compose.ui.graphics.Color.Transparent
                                    )
                                    .clickable {
                                        // Set name + record appId; edit-guard off so the async
                                        // auto-rename won't clobber the user's explicit pick.
                                        confirmNameField = hit.name
                                        confirmNameEdited = true
                                        confirmAppId = hit.appId
                                        // Apply this appId's cover art to the current on-disk shortcut.
                                        if (confirmContainer != null) {
                                            val base = renameDialogName
                                            scope.launch(Dispatchers.IO) {
                                                val bmp = applySteamCover(confirmContainer, base, hit.appId)
                                                if (bmp != null) withContext(Dispatchers.Main) {
                                                    vm.reloadShortcut(
                                                        File(confirmContainer.getDesktopDir(), "$base.desktop").path,
                                                        bmp,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SteamResultThumbnail(hit.appId)
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(hit.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("App ID: ${hit.appId}", fontSize = 11.sp, color = OnSurfaceVariant)
                                }
                                if (hit.appId == linkedHit) {
                                    Text("✓ Linked", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    } else if (!steamSearching) {
                        Text(
                            "Not the right game? Edit the name and tap Search Steam to pick the correct one.",
                            color = OnSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }

                    // Recommended components — the redists this game bundles, one-tap installable into
                    // its container. Detection runs off-main-thread inside the section (Pillar 2/2.2).
                    if (confirmContainer != null) {
                        val importedExe = remember(confirmContainer, renameDialogName) {
                            runCatching {
                                val sc = Shortcut(
                                    confirmContainer,
                                    File(confirmContainer.getDesktopDir(), "$renameDialogName.desktop"),
                                )
                                WinePath.resolveAndroidPath(confirmContainer, sc.path)
                            }.getOrNull()
                        }
                        if (importedExe != null) {
                            Divider(color = DividerColor)
                            RecommendedComponentsSection(
                                container = confirmContainer,
                                exeFile = importedExe,
                                shortcutBaseName = renameDialogName,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = confirmSave) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = confirmSkip) { Text("Skip") }
            },
        )
    }
}

/** "Also uninstall Steam?" for a removed Windows Steam shortcut whose client folder nothing else uses. */
@Composable
private fun WindowsSteamUninstallDialog(offer: WindowsSteamClient.UninstallOffer) {
    val context = LocalContext.current
    var plan by remember(offer) { mutableStateOf<WindowsSteamClient.UninstallPlan?>(null) }
    val running = offer.clientDir in WindowsSteamClient.uninstalling.collectAsState().value
    LaunchedEffect(offer) {
        plan = withContext(Dispatchers.IO) { WindowsSteamClient.planUninstall(context, offer) }
    }
    val keep = { WindowsSteamClient.dismissUninstall(offer) }
    if (rememberXmbHome()) {
        XmbSteamUninstallDialog(
            plan = plan,
            running = running,
            onUninstall = { p ->
                val app = context.applicationContext
                WindowsSteamClient.uninstallAsync(app, p) { error ->
                    Toast.makeText(
                        app,
                        error ?: "Steam uninstalled (${SteamSdInstall.fmtBytes(p.bytes)} freed)",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
            onKeep = { keep() },
        )
        return
    }
    OutlinedAlertDialog(
        onDismissRequest = { if (!running) keep() },
        title = { Text("Also uninstall Steam?") },
        text = {
            val p = plan
            if (running || p == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(if (running) "Uninstalling Steam…" else "Checking Steam's folder…", color = OnSurfaceVariant)
                }
            } else {
                Column {
                    Text("Deletes Steam from ${p.where} (${SteamSdInstall.fmtBytes(p.bytes)}).", color = OnSurfaceVariant)
                    if (p.games > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (p.games == 1) "Includes 1 game installed in Steam's folder." else "Includes ${p.games} games installed in Steam's folder.",
                            color = OnSurfaceVariant,
                        )
                    }
                    if (p.keptGames > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (p.keptGames == 1) "Keeps 1 game from Bannerlator's Steam store." else "Keeps ${p.keptGames} games from Bannerlator's Steam store.",
                            color = OnSurfaceVariant,
                        )
                    }
                    if (p.keptLibraries > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (p.keptLibraries == 1) "1 library folder with games is kept." else "${p.keptLibraries} library folders with games are kept.",
                            color = OnSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!running) TextButton(enabled = plan != null, onClick = {
                val p = plan ?: return@TextButton
                val app = context.applicationContext
                WindowsSteamClient.uninstallAsync(app, p) { error ->
                    Toast.makeText(
                        app,
                        error ?: "Steam uninstalled (${SteamSdInstall.fmtBytes(p.bytes)} freed)",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }) { Text("Uninstall") }
        },
        dismissButton = {
            if (!running) TextButton(onClick = keep) { Text("Keep Steam") }
        },
    )
}

/**
 * The Deck shell's game actions, mounted once over every Deck page: "Add games", My account and the
 * game actions the Deck settings pages hand over open in place, never on the classic All games list.
 * Its list refreshes reach every other library on screen (the Deck home, a game page), so a game added
 * or renamed here shows up there at once.
 */
@Composable
fun DeckGameActionsHost(vm: ShortcutsViewModel = viewModel()) {
    DisposableEffect(vm) {
        vm.sharesRefreshes = true
        onDispose { vm.sharesRefreshes = false }
    }
    val actions = rememberShortcutActions(vm)
    GameActionsHost(vm, actions)
    ShortcutActionDialogs(actions)
}
