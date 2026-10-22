package com.winlator.star.store

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.winlator.star.R
import com.winlator.star.ui.screens.OutlinedAlertDialog
import com.winlator.star.util.InAppFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Steam library locations" manager (Settings → Steam, the Steam store's overflow menu, and the
 * install dialog's "Manage locations…" link): add a folder, remove one, make one the default, and see
 * each location's free space and the games in it. Removing only forgets the entry — see
 * [SteamLibraryLocations.remove] — so nothing on the disk is ever deleted from here.
 *
 * [onChanged] fires after every add / remove / default change so a host holding its own copy of the
 * location list (the install dialog) can reload it.
 */
@Composable
internal fun SteamLibraryManagerDialog(onDismiss: () -> Unit, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SteamLibraryLocations.Entry>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<SteamLibraryLocations.Location?>(null) }

    // describe() walks the volume list, StatFs and two DB reads — keep it off the main thread.
    LaunchedEffect(tick) {
        entries = withContext(Dispatchers.IO) { SteamLibraryLocations.describe(ctx) }
    }

    fun say(text: String, error: Boolean) {
        message = text
        messageIsError = error
    }

    // The app's own file manager in folder-pick mode: real absolute paths (works on SD), no SAF.
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data) else null
        if (path != null) {
            scope.launch {
                // Validation creates folders and does a write probe: disk work, so IO.
                val r = withContext(Dispatchers.IO) { SteamLibraryLocations.add(ctx, path) }
                when (r) {
                    is SteamLibraryLocations.AddResult.Added -> {
                        // Games another Steam client already installed in this folder's steamapps are
                        // picked up right away (reads manifests + the games DB: disk work, so IO).
                        val found = withContext(Dispatchers.IO) { SteamLibraryImport.importInstalled(ctx) }
                        val added = if (found > 0) ctx.getString(R.string.steam_lib_added_found, r.library.label, found)
                        else ctx.getString(R.string.steam_lib_added, r.library.label)
                        say(
                            if (r.lowSpace) added + " " + ctx.getString(R.string.steam_lib_low_space) else added,
                            false,
                        )
                        onChanged()
                        tick++
                    }
                    is SteamLibraryLocations.AddResult.Rejected -> say(r.reason, true)
                }
            }
        }
    }

    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.steam_lib_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.steam_lib_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val rows = entries
                if (rows == null) {
                    Text(
                        text = stringResource(R.string.steam_lib_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    rows.forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        LocationRow(
                            entry = entry,
                            onMakeDefault = {
                                SteamLibraryLocations.setDefault(ctx, entry.location.id)
                                onChanged()
                                tick++
                            },
                            onRemove = { confirmRemove = entry.location },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { pickLauncher.launch(InAppFilePicker.buildDirIntent(ctx, ctx.getString(R.string.steam_lib_pick_title))) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.steam_lib_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.steam_lib_done))
            }
        },
    )

    confirmRemove?.let { loc ->
        OutlinedAlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.steam_lib_remove_confirm_title)) },
            text = { Text(stringResource(R.string.steam_lib_remove_confirm_body, loc.label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { SteamLibraryLocations.remove(ctx, loc.id) }
                            when (r) {
                                is SteamLibraryLocations.RemoveResult.Removed -> {
                                    say(ctx.getString(R.string.steam_lib_removed, r.library.label), false)
                                    onChanged()
                                }
                                is SteamLibraryLocations.RemoveResult.Blocked -> say(
                                    ctx.getString(
                                        R.string.steam_lib_remove_blocked, r.library.label,
                                        r.games.joinToString(", "),
                                    ),
                                    true,
                                )
                                SteamLibraryLocations.RemoveResult.NotFound -> Unit
                            }
                            tick++
                        }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.steam_lib_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.steam_lib_cancel))
                }
            },
        )
    }
}

/** One location in the manager: label (+ Default badge), path, free space, games, and its actions. */
@Composable
private fun LocationRow(
    entry: SteamLibraryLocations.Entry,
    onMakeDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    val loc = entry.location
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = loc.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (entry.isDefault) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = stringResource(R.string.steam_lib_default_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Text(
            text = locationDescription(loc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (loc.available) {
            Text(
                text = stringResource(R.string.steam_lib_free, SteamSdInstall.fmtBytes(loc.freeBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.steam_lib_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            text = if (entry.games.isEmpty()) stringResource(R.string.steam_lib_games_none)
            else stringResource(R.string.steam_lib_games_list, gameSummary(entry.games)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val canDefault = !entry.isDefault && loc.available
        val canRemove = loc.kind == SteamLibraryLocations.Kind.LIBRARY
        if (canDefault || canRemove) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (canDefault) {
                    TextButton(onClick = onMakeDefault, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.steam_lib_make_default))
                    }
                }
                if (canRemove) {
                    TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.steam_lib_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/**
 * The "where do you want to install this?" section of the download dialog — a radio list of every
 * location with its free space, then the size-vs-free verdict and the speed warning for the selected
 * one. Only meant to be shown when [locations] has more than the internal default.
 *
 * [warning] draws one red warning line; the portrait and landscape dialogs each pass their own look.
 */
@Composable
internal fun InstallLocationPicker(
    locations: List<SteamLibraryLocations.Location>,
    selectedId: String,
    installBytes: Long,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
    warning: @Composable (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.steam_lib_install_location),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (installBytes > 1L) {
        Text(
            text = stringResource(R.string.steam_lib_install_size, SteamSdInstall.fmtBytes(installBytes)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    locations.forEach { loc ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(enabled = loc.available) { onSelect(loc.id) }
                .padding(vertical = 2.dp),
        ) {
            RadioButton(
                selected = loc.id == selectedId,
                onClick = { onSelect(loc.id) },
                enabled = loc.available,
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = loc.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = locationDescription(loc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (loc.available) stringResource(R.string.steam_lib_free, SteamSdInstall.fmtBytes(loc.freeBytes))
                    else stringResource(R.string.steam_lib_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loc.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    val sel = locations.firstOrNull { it.id == selectedId }
    if (sel != null) {
        if (!sel.fits(installBytes)) {
            Spacer(Modifier.height(6.dp))
            warning(
                stringResource(
                    R.string.steam_lib_no_room,
                    SteamSdInstall.fmtBytes(installBytes), SteamSdInstall.fmtBytes(sel.freeBytes),
                ),
            )
        }
        when (sel.kind) {
            SteamLibraryLocations.Kind.SD -> {
                Spacer(Modifier.height(6.dp))
                warning(stringResource(R.string.steam_lib_sd_warning))
            }
            SteamLibraryLocations.Kind.LIBRARY -> {
                Spacer(Modifier.height(6.dp))
                warning(stringResource(R.string.steam_lib_slow_warning))
            }
            SteamLibraryLocations.Kind.INTERNAL -> Unit
        }
    }
    TextButton(onClick = onManage, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(R.string.steam_lib_manage_ellipsis))
    }
}

/**
 * The second line under a location's name: what it is for the internal default, the folder games
 * actually install to for a library (its `steamapps/common`), else the card's root.
 */
@Composable
internal fun locationDescription(loc: SteamLibraryLocations.Location): String = when (loc.kind) {
    SteamLibraryLocations.Kind.INTERNAL -> stringResource(R.string.steam_lib_internal_desc)
    SteamLibraryLocations.Kind.LIBRARY -> loc.base.absolutePath
    SteamLibraryLocations.Kind.SD -> loc.rootPath
}

/** "A, B, C +2" — the first three game names and how many more there are. */
private fun gameSummary(games: List<String>): String =
    games.take(3).joinToString(", ") + if (games.size > 3) " +${games.size - 3}" else ""
