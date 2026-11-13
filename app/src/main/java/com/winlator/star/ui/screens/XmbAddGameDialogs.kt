package com.winlator.star.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.core.GameFolderScanner
import com.winlator.star.store.SteamSdInstall
import com.winlator.star.store.SteamStoreSearch
import com.winlator.star.store.WindowsSteamClient
import com.winlator.star.ui.deck.NunitoSansFamily
import com.winlator.star.ui.deck.XmbTapToEdit
import com.winlator.star.ui.deck.xmbDoneActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// The XMB mode's versions of the "Add games" popups, drawn with the XmbDialogKit. Every one takes the state and the handlers of
// the stock dialog it replaces (GameActionsHost, AndroidGamePicker, AddToShortcutsFlow) as plain parameters: it holds no state of
// its own about the flow. The stock dialogs stay as they are for Classic, Lab and plain Deck.

/** One choice of a picker (a container): [title], a muted [subtitle] and a [note] in the tertiary colour. */
internal class XmbChoice(val title: String, val subtitle: String? = null, val note: String? = null)

private class XmbMethod(val title: String, val subtitle: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/**
 * "Add games": what to add. The four options are cards with an icon, in two columns when the dialog is wide enough (landscape)
 * and one column otherwise; Cancel in the footer.
 */
@Composable
internal fun XmbImportMethodDialog(
    onDismiss: () -> Unit,
    onExe: () -> Unit,
    onFolder: () -> Unit,
    onAndroid: () -> Unit,
    onWindowsSteam: () -> Unit,
) {
    val options = listOf(
        XmbMethod("Add game EXE", "Pick one game's .exe file", Icons.Filled.InsertDriveFile, onExe),
        XmbMethod("Add games folder", "Scan a folder that holds all your games for their .exe files", Icons.Filled.Folder, onFolder),
        XmbMethod("Add Android game", "Games installed on this phone, opened as normal Android apps", Icons.Filled.Android, onAndroid),
        XmbMethod("Add Windows Steam client x64", "Valve's 64-bit Steam for Windows, downloaded from Steam", Icons.Filled.DesktopWindows, onWindowsSteam),
    )
    XmbDialogFrame(
        title = "Add games",
        subtitle = "Choose what to add to your library",
        onDismiss = onDismiss,
        footer = { XmbDialogButton("Cancel", onDismiss) },
    ) {
        val columns = xmbOptionColumns(LocalXmbDialogSize.current.width, options.size)
        XmbOptionGrid(columns = columns, count = options.size) { i, cellModifier ->
            val o = options[i]
            XmbOptionRow(
                title = o.title,
                subtitle = o.subtitle,
                icon = o.icon,
                onClick = o.onClick,
                modifier = cellModifier.then(if (i == 0) Modifier.xmbInitialFocus() else Modifier),
            )
        }
    }
}

/**
 * A container picker: one option row per choice. [systemPickerChecked] non-null adds the "use the system file picker" tick at
 * the bottom (fixed, it does not scroll away with the list). [emptyText] is shown when there is nothing to pick.
 */
@Composable
internal fun XmbContainerPickerDialog(
    title: String,
    subtitle: String?,
    choices: List<XmbChoice>,
    emptyText: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    systemPickerChecked: Boolean? = null,
    onSystemPickerChange: (Boolean) -> Unit = {},
) {
    XmbDialogFrame(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        scrollBody = false,
        footer = { XmbDialogButton("Cancel", onDismiss) },
    ) {
        if (choices.isEmpty()) {
            XmbDialogText(emptyText, muted = true)
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                itemsIndexed(choices, key = { index, _ -> index }) { index, choice ->
                    XmbOptionRow(
                        title = choice.title,
                        subtitle = choice.subtitle,
                        note = choice.note,
                        icon = Icons.Filled.Folder,
                        onClick = { onPick(index) },
                        modifier = if (index == 0) Modifier.xmbInitialFocus() else Modifier,
                    )
                }
            }
            if (systemPickerChecked != null) {
                Box(Modifier.padding(top = 6.dp)) {
                    XmbCheckRow(
                        title = "Use the system file picker",
                        checked = systemPickerChecked,
                        onToggle = { onSystemPickerChange(!systemPickerChecked) },
                    )
                }
            }
        }
    }
}

/**
 * "Confirm game": the game's name with Steam search beside it, the Steam results as radio cards (the linked one on top), and
 * [components] (the recommended-components section) under them. Save is the main action. [onDismiss] (Back, B) closes the
 * dialog as the stock one does; the keyboard-less field is the usual tap-to-edit one (A starts typing, the XMB keyboard).
 */
@Composable
internal fun XmbConfirmGameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onNameClear: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    results: List<SteamStoreSearch.SteamSuggestion>,
    linkedAppId: Int?,
    onPickResult: (SteamStoreSearch.SteamSuggestion) -> Unit,
    onSave: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    components: @Composable () -> Unit = {},
) {
    XmbDialogFrame(
        title = "Confirm game",
        subtitle = "Check the name and link it to its Steam page for the cover art",
        onDismiss = onDismiss,
        wide = true,
        footer = {
            XmbDialogButton("Skip", onSkip)
            XmbDialogButton("Save", onSave, primary = true, modifier = Modifier.xmbInitialFocus())
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                XmbTapToEdit(Modifier.weight(1f)) { fieldModifier, stopEditing ->
                    OutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        label = { Text("Game name") },
                        singleLine = true,
                        trailingIcon = {
                            if (name.isNotEmpty()) {
                                IconButton(onClick = onNameClear) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        keyboardActions = xmbDoneActions(stopEditing),
                        modifier = fieldModifier,
                    )
                }
                XmbDialogButton(
                    text = if (searching) "Searching…" else "Search",
                    onClick = onSearch,
                    enabled = !searching && name.isNotBlank(),
                    icon = Icons.Filled.Search,
                )
            }
            if (results.isNotEmpty()) {
                XmbDialogText("Tap a result to set the name and cover art", muted = true, small = true)
                // The linked result first, so the match is the first thing seen.
                results.sortedByDescending { it.appId == linkedAppId }.forEach { hit ->
                    val linked = hit.appId == linkedAppId
                    XmbRadioRow(
                        title = hit.name,
                        selected = linked,
                        onSelect = { onPickResult(hit) },
                        subtitle = "App ID: ${hit.appId}",
                        note = if (linked) "Linked" else null,
                        noteColor = MaterialTheme.colorScheme.primary,
                        leading = { SteamResultThumbnail(hit.appId) },
                    )
                }
            } else if (searching) {
                XmbProgressCard("Searching Steam…")
            } else {
                XmbDialogText(
                    "Not the right game? Edit the name and search Steam to pick the correct one.",
                    muted = true,
                    small = true,
                )
            }
            components()
        }
    }
}

/**
 * "Scanning for games": an indeterminate progress card with the folder being scanned (the scan reports nothing finer than that)
 * and Cancel, which stops waiting for it.
 */
@Composable
internal fun XmbScanProgressDialog(root: String, onCancel: () -> Unit) {
    XmbDialogFrame(
        title = "Scanning for games",
        subtitle = "Looking for each game's .exe",
        onDismiss = onCancel,
        footer = { XmbDialogButton("Cancel", onCancel, modifier = Modifier.xmbInitialFocus()) },
    ) {
        XmbProgressCard(title = "Scanning…", detail = root)
    }
}

/** A game's cover (or its first letter while it loads / has none), the size of the review list's leading picture. */
@Composable
private fun XmbGameCover(url: String?, name: String) {
    val cs = MaterialTheme.colorScheme
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(width = 36.dp, height = 50.dp).clip(RoundedCornerShape(5.dp)),
        loading = { Box(Modifier.fillMaxSize().background(cs.onSurface.copy(alpha = 0.10f))) },
        error = {
            Box(Modifier.fillMaxSize().background(cs.onSurface.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Text(name.take(1).uppercase(), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
    )
}

/**
 * The review of a scanned games folder ("Found N games"): one check card per game with its cover, name, folder and exe, and a
 * Change button beside it (a stop of its own); [componentsFor] adds a game's components expander under its card. While the import
 * runs ([importProgress] = done to total) the list gives way to a progress card (a bar when the total is known) and nothing can be
 * closed. [onAdd] / [onCancel] are the footer buttons.
 */
@Composable
internal fun XmbFolderReviewDialog(
    candidates: List<GameFolderScanner.Candidate>,
    selected: Set<String>,
    importing: Boolean,
    importProgress: Pair<Int, Int>?,
    onToggle: (GameFolderScanner.Candidate) -> Unit,
    onChangeExe: (GameFolderScanner.Candidate) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    componentsFor: @Composable (GameFolderScanner.Candidate) -> Unit,
) {
    val importable = candidates.count { !it.alreadyAdded }
    val skipped = candidates.size - importable
    XmbDialogFrame(
        title = "Found ${xmbCountLabel(importable, "game")}",
        subtitle = if (skipped > 0) "$skipped already added" else "Untick the games you do not want",
        onDismiss = onCancel,
        wide = true,
        dismissible = !importing,
        scrollBody = false,
        footer = {
            XmbDialogButton("Cancel", onCancel, enabled = !importing)
            XmbDialogButton(
                text = xmbAddLabel(selected.size, importing),
                onClick = onAdd,
                primary = true,
                enabled = selected.isNotEmpty() && !importing,
            )
        },
    ) {
        if (importing) {
            val done = importProgress?.first ?: 0
            val total = importProgress?.second ?: selected.size
            XmbProgressCard(
                title = "Adding ${xmbCountLabel(total, "game")}…",
                progress = xmbProgressFraction(done, total),
                trailing = if (total > 0) "$done / $total" else null,
                detail = "Writing the shortcuts and fetching the cover art",
            )
        } else {
            val warn = MaterialTheme.colorScheme.tertiary
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(candidates, key = { it.exe.absolutePath }) { c ->
                    val flagged = c.uncertain && !c.alreadyAdded
                    XmbCheckRow(
                        title = c.name,
                        checked = c.exe.absolutePath in selected,
                        onToggle = { onToggle(c) },
                        enabled = !c.alreadyAdded,
                        subtitle = c.folder.name,
                        // Flagged rows name the exe, because that is what the user is asked to judge.
                        note = when {
                            c.alreadyAdded -> "Already added"
                            flagged -> "Check this one: ${c.exe.name}"
                            else -> c.exe.name
                        },
                        noteColor = if (flagged) warn else Color.Unspecified,
                        leading = { XmbGameCover(c.coverUrl, c.name) },
                        trailing = {
                            // Every row, flagged or not: a confident pick can still be the wrong one.
                            if (!c.alreadyAdded) XmbDialogButton("Change", { onChangeExe(c) })
                        },
                        below = { componentsFor(c) },
                    )
                }
            }
        }
    }
}

/** The "Components (n)" expander under a scanned game's card, and the section itself once it is open. */
@Composable
internal fun XmbScanComponents(count: Int, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp)) {
        XmbChoiceChip(text = (if (open) "▾ " else "▸ ") + "Components ($count)", selected = open, onClick = onToggle)
        if (open) Box(Modifier.padding(top = 6.dp)) { content() }
    }
}

/** The exe override of one scanned game: a radio card per exe found in its folder (where it sits in the folder under its name). */
@Composable
internal fun XmbExePickerDialog(
    target: GameFolderScanner.Candidate,
    options: List<File>,
    onPick: (File) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit,
) {
    XmbDialogFrame(
        title = "Choose the game's .exe",
        subtitle = target.name,
        onDismiss = onDismiss,
        wide = true,
        scrollBody = false,
        footer = {
            XmbDialogButton("Cancel", onDismiss)
            // Escape hatch: the real exe can be missing from the list if it was filtered as an installer / helper or sits deeper
            // than the scan goes.
            XmbDialogButton("Browse…", onBrowse, icon = Icons.Filled.Folder)
        },
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(options, key = { it.absolutePath }) { exe ->
                val current = exe.absolutePath == target.exe.absolutePath
                // Where it sits inside the game folder: what tells two same-named exes apart (x86 vs x64, bin/ vs root).
                val rel = exe.absolutePath.removePrefix(target.folder.absolutePath).removePrefix("/")
                XmbRadioRow(
                    title = exe.name,
                    selected = current,
                    onSelect = { onPick(exe) },
                    subtitle = if (rel.contains('/')) rel.substringBeforeLast('/') else "(folder root)",
                    modifier = if (current) Modifier.xmbInitialFocus() else Modifier,
                )
            }
        }
    }
}

/**
 * "Add Android game": search and Games / All apps on one row, the apps as check cards with their icon, Add N / Cancel. [loading]
 * while the installed apps are read; [adding] swaps the list for a progress card. [subtitle] is the count line.
 */
@Composable
internal fun XmbAndroidGamePickerDialog(
    loading: Boolean,
    subtitle: String,
    shown: List<AndroidGames.InstalledApp>,
    query: String,
    onQueryChange: (String) -> Unit,
    gamesOnly: Boolean,
    onGamesOnlyChange: (Boolean) -> Unit,
    selected: Set<String>,
    alreadyAdded: Set<String>,
    adding: Boolean,
    chosenCount: Int,
    onToggle: (AndroidGames.InstalledApp) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    XmbDialogFrame(
        title = "Add Android game",
        subtitle = if (loading) "Reading installed apps…" else subtitle,
        onDismiss = onDismiss,
        wide = true,
        dismissible = !adding,
        scrollBody = false,
        footer = {
            XmbDialogButton("Cancel", onDismiss, enabled = !adding)
            XmbDialogButton(
                text = xmbAddLabel(chosenCount, adding),
                onClick = onAdd,
                primary = true,
                enabled = chosenCount > 0 && !adding,
            )
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            XmbTapToEdit(Modifier.weight(1f)) { fieldModifier, stopEditing ->
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search apps") },
                    singleLine = true,
                    modifier = fieldModifier,
                    keyboardActions = xmbDoneActions(stopEditing),
                )
            }
            XmbChoiceChip("Games", selected = gamesOnly, onClick = { onGamesOnlyChange(true) }, enabled = !adding)
            XmbChoiceChip("All apps", selected = !gamesOnly, onClick = { onGamesOnlyChange(false) }, enabled = !adding)
        }
        when {
            loading -> XmbProgressCard("Reading installed apps…")
            adding -> XmbProgressCard(title = "Adding ${xmbCountLabel(chosenCount, "game")}…", detail = "Writing the shortcuts")
            else -> {
                if (shown.isEmpty() && gamesOnly && query.isBlank()) {
                    XmbDialogText(
                        "Some games don't tell Android they are games: try All apps.",
                        muted = true,
                        small = true,
                    )
                }
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(shown, key = { it.packageName }) { app ->
                        val added = app.packageName in alreadyAdded
                        XmbCheckRow(
                            title = app.label,
                            checked = added || app.packageName in selected,
                            onToggle = { onToggle(app) },
                            enabled = !added,
                            subtitle = if (added) "Already added" else app.packageName,
                            leading = { XmbAppIcon(app) },
                        )
                    }
                }
            }
        }
    }
}

/** An installed app's icon (rasterised off the main thread, then cached), its first letter until it is there. */
@Composable
private fun XmbAppIcon(app: AndroidGames.InstalledApp) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val iconPx = with(LocalDensity.current) { 40.dp.roundToPx() }
    val icon by produceState<android.graphics.Bitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) { AndroidGames.iconBitmap(context.applicationContext, app.packageName, iconPx) }
    }
    Box(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = icon
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(cs.onSurface.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                Text(app.label.take(1).uppercase(), color = cs.onSurfaceVariant, fontFamily = NunitoSansFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

/**
 * "Windows Steam client": the storage to put it on as radio cards with their free space (a full one is dimmed), or, once the
 * download runs ([progress] is its percent), a determinate progress card. The download cannot be cancelled, so the footer is gone
 * while it runs and the dialog cannot be closed. [confirmText] is Download, or Add when the client is already on that storage.
 */
@Composable
internal fun XmbWindowsSteamDialog(
    title: String,
    places: List<WindowsSteamClient.Place>,
    pick: Int,
    onPick: (Int) -> Unit,
    progress: Int?,
    confirmText: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val running = progress != null
    val buttons: @Composable RowScope.() -> Unit = {
        XmbDialogButton("Cancel", onCancel)
        XmbDialogButton(confirmText, onConfirm, primary = true, enabled = confirmEnabled)
    }
    XmbDialogFrame(
        title = title,
        subtitle = if (running) "This can take a minute" else "Where should Steam go?",
        onDismiss = onCancel,
        dismissible = !running,
        footer = if (running) null else buttons,
    ) {
        if (progress != null) {
            XmbProgressCard(
                title = "Downloading Steam…",
                progress = progress / 100f,
                detail = places.getOrNull(pick)?.let { "Saving to ${it.label}" },
            )
        } else if (places.isEmpty()) {
            XmbProgressCard("Checking the storage…")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                places.forEachIndexed { i, place ->
                    XmbRadioRow(
                        title = place.label,
                        selected = pick == i,
                        onSelect = { onPick(i) },
                        enabled = place.fits,
                        subtitle = when {
                            place.hasClient -> "Steam is already here"
                            place.fits -> "${SteamSdInstall.fmtBytes(place.freeBytes)} free"
                            else -> "Not enough space (${SteamSdInstall.fmtBytes(place.freeBytes)} free)"
                        },
                        modifier = if (i == pick) Modifier.xmbInitialFocus() else Modifier,
                    )
                }
            }
        }
    }
}

/** "Also uninstall Steam?": what would go, or a progress card while the plan is read / the uninstall runs. Keeping is the safe default. */
@Composable
internal fun XmbSteamUninstallDialog(
    plan: WindowsSteamClient.UninstallPlan?,
    running: Boolean,
    onUninstall: (WindowsSteamClient.UninstallPlan) -> Unit,
    onKeep: () -> Unit,
) {
    val buttons: @Composable RowScope.() -> Unit = {
        XmbDialogButton("Keep Steam", onKeep, primary = true, modifier = Modifier.xmbInitialFocus())
        XmbDialogButton("Uninstall", { plan?.let(onUninstall) }, enabled = plan != null, danger = true)
    }
    XmbDialogFrame(
        title = "Also uninstall Steam?",
        onDismiss = onKeep,
        dismissible = !running,
        footer = if (running) null else buttons,
    ) {
        if (running || plan == null) {
            XmbProgressCard(if (running) "Uninstalling Steam…" else "Checking Steam's folder…")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                XmbDialogText("Deletes Steam from ${plan.where} (${SteamSdInstall.fmtBytes(plan.bytes)}).")
                if (plan.games > 0) {
                    XmbDialogText(
                        if (plan.games == 1) "Includes 1 game installed in Steam's folder." else "Includes ${plan.games} games installed in Steam's folder.",
                        muted = true,
                    )
                }
                if (plan.keptGames > 0) {
                    XmbDialogText(
                        if (plan.keptGames == 1) "Keeps 1 game from Bannerlator's Steam store." else "Keeps ${plan.keptGames} games from Bannerlator's Steam store.",
                        muted = true,
                    )
                }
                if (plan.keptLibraries > 0) {
                    XmbDialogText(
                        if (plan.keptLibraries == 1) "1 library folder with games is kept." else "${plan.keptLibraries} library folders with games are kept.",
                        muted = true,
                    )
                }
            }
        }
    }
}

/**
 * A message with one or two buttons (the store flow's "Added to Shortcuts" / "Couldn't add shortcut"): [confirmText] is the
 * main action, [dismissText] (null = none) the secondary one. Back and B call [onDismiss].
 */
@Composable
internal fun XmbMessageDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String? = null,
) {
    XmbDialogFrame(
        title = title,
        onDismiss = onDismiss,
        footer = {
            if (dismissText != null) XmbDialogButton(dismissText, onDismiss)
            XmbDialogButton(confirmText, onConfirm, primary = true, modifier = Modifier.xmbInitialFocus())
        },
    ) {
        XmbDialogText(message)
    }
}
