package com.winlator.star.ui.simple

import android.app.Activity
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavGraphBuilder
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.CopyGameToDriveC
import com.winlator.star.core.GameSaveBackup
import com.winlator.star.core.MoveGameToContainer
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.NO_OTHER_CONTAINER
import com.winlator.star.ui.screens.ShortcutActions
import com.winlator.star.util.InAppFilePicker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Game menu actions and adding games (Lab). */
@Suppress("UNUSED_PARAMETER")
internal fun NavGraphBuilder.labGameRoutes(open: (String) -> Unit, back: () -> Unit) {
}

// ───────────────────────────── the dialogs' kit ─────────────────────────────

/** A FocusRequester for a Lab dialog's first stop, focused a moment after the dialog opens (as SimpleActionSheet does). */
@Composable
internal fun rememberLabFirstFocus(key: Any? = Unit): FocusRequester {
    val r = remember { FocusRequester() }
    LaunchedEffect(key) {
        delay(80)
        runCatching { r.requestFocus() }
    }
    return r
}

/**
 * A Lab dialog: the Lab panel with a title, an optional muted line under it, a body ([scroll]: the body scrolls; off for
 * a body that holds a lazy list of its own) and a row of buttons at the bottom. [dismissible] off: Back, B and a tap
 * outside do nothing (a copy or download running). [wide] for lists whose rows carry a picture and several lines.
 */
@Composable
internal fun LabActionDialog(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    dismissible: Boolean = true,
    wide: Boolean = false,
    scroll: Boolean = true,
    buttons: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = dismissible, usePlatformDefaultWidth = !wide),
    ) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .then(if (wide) Modifier.fillMaxWidth(0.94f).widthIn(max = 980.dp) else Modifier.widthIn(min = 300.dp, max = 560.dp))
                .heightIn(max = 660.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = SimpleMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            Column(
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content,
            )
            if (buttons != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

/** A line of text in a Lab dialog: muted by default, red for an error, small for a note. */
@Composable
internal fun LabActionText(text: String, muted: Boolean = true, error: Boolean = false, small: Boolean = false) {
    Text(
        text,
        color = when {
            error -> MaterialTheme.colorScheme.error
            muted -> SimpleMuted
            else -> SimpleStrong
        },
        fontSize = if (small) 12.sp else 14.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** A small heading over a group of rows in a Lab dialog. */
@Composable
internal fun LabDialogGroup(text: String) {
    Text(text.uppercase(), color = SimpleDim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp))
}

/** A LabButton that can be greyed out: then it stays reachable but does nothing. */
@Composable
internal fun LabDialogButton(
    label: String,
    enabled: Boolean = true,
    primary: Boolean = false,
    danger: Boolean = false,
    icon: ImageVector? = null,
    requester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    LabButton(label, primary = primary && enabled, icon = icon, requester = requester, danger = danger, modifier = if (enabled) Modifier else Modifier.alpha(0.45f)) {
        if (enabled) onClick()
    }
}

/**
 * One choice in a Lab dialog: [title] with a muted [subtitle] and a [note] (its own colour) under it. [selected] adds a
 * radio in front, [switch] a Lab switch on the right, [box] a tick box in front. A greyed-out row ([enabled] off) can be
 * reached but does nothing.
 */
@Composable
internal fun LabActionRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    note: String? = null,
    noteColor: Color = Color.Unspecified,
    selected: Boolean? = null,
    switch: Boolean? = null,
    box: Boolean? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
    requester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .labTile(onClick = { if (enabled) onClick() }, requester = requester)
            .then(if (enabled) Modifier else Modifier.alpha(0.45f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (selected != null) LabRadio(selected)
        if (box != null) LabTickBox(box)
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) MaterialTheme.colorScheme.error else SimpleStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = SimpleMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!note.isNullOrBlank()) Text(note, color = if (noteColor == Color.Unspecified) SimpleDim else noteColor, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (switch != null) LabSwitch(switch)
    }
}

@Composable
private fun LabRadio(on: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.size(20.dp).border(2.dp, if (on) accent else SimpleMuted, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(10.dp).background(accent, CircleShape))
    }
}

@Composable
private fun LabTickBox(on: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(20.dp)
            .background(if (on) accent else Color.Transparent, SimpleChipShape)
            .border(2.dp, if (on) accent else SimpleMuted, SimpleChipShape),
    ) {
        if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** A running step: a spinner and [text], or a bar when [fraction] is known; [trailing] on the right, [detail] under it. */
@Composable
internal fun LabProgressLine(text: String, fraction: Float? = null, trailing: String? = null, detail: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (fraction == null) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = SimpleStrong, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (trailing != null) Text(trailing, color = SimpleMuted, fontSize = 13.sp)
        }
        if (fraction != null) LabBar(fraction, Modifier.fillMaxWidth())
        if (!detail.isNullOrBlank()) Text(detail, color = SimpleMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Lab's one-line text field: the Lab card with an accent outline while it has focus, and ✕ to clear it. */
@Composable
internal fun LabActionField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onClear: (() -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = SimpleStrong, fontSize = 15.sp),
        cursorBrush = SolidColor(accent),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(SimpleCard, SimpleShape)
                    .border(if (focused) 2.dp else 1.dp, if (focused) accent else SimpleLine, SimpleShape)
                    .padding(horizontal = 12.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, color = SimpleDim, fontSize = 15.sp, maxLines = 1)
                    inner()
                }
                if (onClear != null && value.isNotEmpty()) {
                    Icon(Icons.Filled.Close, contentDescription = L("Clear"), tint = SimpleMuted, modifier = Modifier.size(20.dp).simpleTap(onClear))
                }
            }
        },
    )
}

/** "1 game", "3 games". */
internal fun labCount(n: Int, one: String, many: String): String = if (n == 1) L(one) else L(many, n)

/**
 * A container to pick, as Lab rows (name, Wine version · screen size). [systemPickerChecked] non-null adds the "Use the
 * system file picker" switch under the list (the .exe import only).
 */
@Composable
internal fun LabContainerPickerDialog(
    title: String,
    subtitle: String?,
    containers: List<Container>,
    emptyText: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    systemPickerChecked: Boolean? = null,
    onSystemPickerChange: (Boolean) -> Unit = {},
) {
    val first = rememberLabFirstFocus()
    LabActionDialog(
        title = title,
        subtitle = subtitle,
        onDismiss = onDismiss,
        buttons = { LabDialogButton(L("Cancel"), requester = if (containers.isEmpty()) first else null, onClick = onDismiss) },
    ) {
        if (containers.isEmpty()) LabActionText(emptyText)
        containers.forEachIndexed { i, c ->
            LabActionRow(
                title = c.name?.takeIf { it.isNotEmpty() } ?: L("Container %1\$s", c.id),
                subtitle = listOf(c.wineVersion.orEmpty(), c.screenSize.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null },
                icon = Icons.Filled.Folder,
                requester = if (i == 0) first else null,
                onClick = { onPick(i) },
            )
        }
        if (systemPickerChecked != null && containers.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            LabActionRow(L("Use the system file picker"), switch = systemPickerChecked, onClick = { onSystemPickerChange(!systemPickerChecked) })
        }
    }
}

// ───────────────────────────── the game menu's actions ─────────────────────────────

/**
 * Lab's versions of the per-game dialogs of ShortcutActionDialogs: Remove, Clone to container, Copy to Drive C, Change
 * executable, Move to another container, Back up / Restore saves (with the emulator-account question) and Properties.
 * Same state ([actions]) and the same steps as the Classic dialogs; ShortcutActionDialogs draws these instead of its own
 * while Lab is on. [runBackup] is Classic's backup runner (its toasts included).
 */
@Composable
internal fun LabShortcutActionDialogs(actions: ShortcutActions, runBackup: (Shortcut, GameSaveBackup.BackupLayout) -> Unit) {
    val vm = actions.vm
    val context = LocalContext.current
    val scope = actions.scope

    actions.confirmRemove?.let { s ->
        val close = { actions.confirmRemove = null }
        SimpleActionSheet(
            title = L("Remove shortcut?"),
            message = L("Remove \"%1\$s\"?", s.name),
            actions = listOf(
                MenuAction(L("Remove"), {
                    val ok = vm.remove(s, context)
                    actions.confirmRemove = null
                    Toast.makeText(context, if (ok) L("Shortcut removed.") else L("Failed to remove shortcut."), Toast.LENGTH_SHORT).show()
                }, danger = true),
                MenuAction(L("Cancel"), close),
            ),
            onDismiss = close,
        ) { it.run() }
    }

    // Clone: MoveGameToContainer with keepCopy, as Classic's.
    actions.cloneTarget?.let { s ->
        val containers = remember(s) { vm.containers().filter { it.id != s.container.id } }
        LabContainerPickerDialog(
            title = L("Select container"),
            subtitle = L("A copy of \"%1\$s\" goes into it", s.name),
            containers = containers,
            emptyText = L(NO_OTHER_CONTAINER),
            onPick = { i ->
                val c = containers[i]
                actions.cloneTarget = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { MoveGameToContainer.run(context, s, c, keepCopy = true) }
                    Toast.makeText(context, MoveGameToContainer.message(r, s.name, c.name, keepCopy = true), Toast.LENGTH_LONG).show()
                    if (r is MoveGameToContainer.Result.Done) vm.refresh()
                }
            },
            onDismiss = { actions.cloneTarget = null },
        )
    }

    LabCopyToDriveC(target = actions.copyToDriveCTarget, onFinished = { actions.copyToDriveCTarget = null; vm.refresh() })
    LabChangeExecutable(target = actions.changeExeTarget, onFinished = { actions.changeExeTarget = null; vm.refresh() })
    LabMoveToContainer(
        target = actions.moveTarget,
        containers = { vm.containers() },
        onEmuConflicts = { actions.emuConflicts = it },
        onFinished = { actions.moveTarget = null; vm.refresh() },
    )

    // Restore saves: the picked .zip goes into the container chosen here.
    actions.restoreZipUri?.let { uri ->
        val containers = remember(uri) { vm.containers() }
        LabContainerPickerDialog(
            title = L("Select container"),
            subtitle = L("The saves of \"%1\$s\" are restored into it", actions.restoreForName),
            containers = containers,
            emptyText = L("No containers yet — create one in the Containers screen first."),
            onPick = { i ->
                val chosen = containers[i]
                actions.restoreZipUri = null
                Toast.makeText(context, L("Restoring saves into \"%1\$s\"…", chosen.name), Toast.LENGTH_SHORT).show()
                GameSaveBackup.restore(context, uri, chosen) { r ->
                    Toast.makeText(
                        context,
                        if (r.ok) L("Restored %1\$s files to \"%2\$s\"", r.filesWritten, chosen.name)
                        else L("Restore failed: %1\$s", r.error ?: "unknown error"),
                        Toast.LENGTH_LONG,
                    ).show()
                    actions.emuConflicts = r.emuConflicts
                }
            },
            onDismiss = { actions.restoreZipUri = null },
        )
    }

    LabEmuAccountConflictDialog(conflicts = actions.emuConflicts) { applied, _ ->
        actions.emuConflicts = emptyList()
        if (applied > 0) Toast.makeText(context, L("Emulator account switched to the backup's — relaunch the game"), Toast.LENGTH_LONG).show()
    }

    // Back up saves: the archive layout first, as Classic asks.
    actions.backupFormatShortcut?.let { s ->
        val first = rememberLabFirstFocus(s)
        val close = { actions.backupFormatShortcut = null }
        LabActionDialog(
            title = stringResource(R.string.save_backup_format_title),
            subtitle = stringResource(R.string.save_backup_format_prompt),
            onDismiss = close,
            buttons = { LabDialogButton(L("Cancel"), onClick = close) },
        ) {
            LabActionRow(
                stringResource(R.string.save_backup_format_winlator),
                subtitle = stringResource(R.string.save_backup_format_winlator_sub),
                requester = first,
            ) { close(); runBackup(s, GameSaveBackup.BackupLayout.WINLATOR) }
            LabActionRow(
                stringResource(R.string.save_backup_format_gamehub),
                subtitle = stringResource(R.string.save_backup_format_gamehub_sub),
            ) { close(); runBackup(s, GameSaveBackup.BackupLayout.GAMEHUB) }
        }
    }

    actions.propertiesShortcut?.let { s -> LabPropertiesDialog(s) { actions.propertiesShortcut = null } }
}

/** Properties: times played and total playtime (the playtime_stats Classic keeps), and Reset. */
@Composable
private fun LabPropertiesDialog(s: Shortcut, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("playtime_stats", Context.MODE_PRIVATE) }
    val playtimeKey = "${s.name}_playtime"
    val playCountKey = "${s.name}_play_count"
    var tick by remember { mutableIntStateOf(0) }
    val totalMs = remember(tick) { prefs.getLong(playtimeKey, 0L) }
    val playCount = remember(tick) { prefs.getInt(playCountKey, 0) }
    val formatted = String.format(
        java.util.Locale.ENGLISH, "%dd %02dh %02dm %02ds",
        totalMs / (1000 * 60 * 60 * 24), (totalMs / (1000 * 60 * 60)) % 24, (totalMs / (1000 * 60)) % 60, (totalMs / 1000) % 60,
    )
    val close = rememberLabFirstFocus()
    LabActionDialog(
        title = L("Properties"),
        subtitle = s.name,
        onDismiss = onDismiss,
        buttons = {
            LabDialogButton(L("Reset Properties"), danger = true) {
                prefs.edit().remove(playtimeKey).remove(playCountKey).apply()
                tick++
            }
            LabDialogButton(L("Close"), primary = true, requester = close, onClick = onDismiss)
        },
    ) {
        LabValueLine(L("Number of times played"), playCount.toString())
        LabValueLine(L("Playtime"), formatted)
    }
}

@Composable
private fun LabValueLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, color = SimpleStrong, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/**
 * Which emulator account a restored container runs under, when the backup's differs from the one already there (the
 * steps of EmuAccountConflictDialog: one conflict at a time, Back keeps the current one).
 */
@Composable
private fun LabEmuAccountConflictDialog(conflicts: List<GameSaveBackup.EmuIdConflict>, onFinished: (applied: Int, kept: Int) -> Unit) {
    if (conflicts.isEmpty()) return
    val scope = rememberCoroutineScope()
    var index by remember(conflicts) { mutableIntStateOf(0) }
    var applied by remember(conflicts) { mutableIntStateOf(0) }
    var kept by remember(conflicts) { mutableIntStateOf(0) }
    val conflict = conflicts.getOrNull(index) ?: return
    fun advance() { if (index + 1 >= conflicts.size) onFinished(applied, kept) else index += 1 }
    val keep: () -> Unit = {
        scope.launch {
            withContext(Dispatchers.IO) { GameSaveBackup.discardEmuIdentity(conflict) }
            kept += 1
            advance()
        }
    }
    val first = rememberLabFirstFocus(index)
    @Composable
    fun account(labelRes: Int, id: String, folder: String?) = LabActionText(
        if (folder != null) stringResource(R.string.emu_id_conflict_account_with_folder, stringResource(labelRes), id, folder)
        else stringResource(R.string.emu_id_conflict_account, stringResource(labelRes), id),
        muted = false,
    )
    LabActionDialog(
        title = stringResource(R.string.emu_id_conflict_title, conflict.label),
        onDismiss = keep,
        buttons = {
            LabDialogButton(stringResource(R.string.emu_id_conflict_keep), requester = first, onClick = keep)
            LabDialogButton(stringResource(R.string.emu_id_conflict_apply), primary = true) {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { GameSaveBackup.applyEmuIdentity(conflict) }
                    if (ok) applied += 1 else kept += 1
                    advance()
                }
            }
        },
    ) {
        LabActionText(stringResource(R.string.emu_id_conflict_body))
        Spacer(Modifier.height(6.dp))
        account(R.string.emu_id_conflict_from_backup, conflict.backupId, conflict.backupSaveFolder)
        account(R.string.emu_id_conflict_in_container, conflict.currentId, conflict.currentSaveFolder)
        Spacer(Modifier.height(6.dp))
        LabActionText(stringResource(R.string.emu_id_conflict_warning), small = true)
    }
}

private enum class LabCopyPhase { CONFIRM, OVERWRITE, COPYING, DELETE_ORIGINAL }

/**
 * Copy to Drive C in Lab: the steps of Classic's CopyToDriveCCoordinator (already on C: or not found → a toast; the
 * folder to copy with its size and Change folder…; the free-space check; Overwrite / Keep both; the copy with its
 * progress and Cancel; then Delete or Keep the original), on CopyGameToDriveC.
 */
@Composable
private fun LabCopyToDriveC(target: Shortcut?, onFinished: () -> Unit) {
    if (target == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = remember(target) { CopyGameToDriveC.parse(target) }
    val actionable = info.exeAndroid != null && !info.onDriveC
    LaunchedEffect(target) {
        if (info.onDriveC) {
            Toast.makeText(context, L("\"%1\$s\" already runs from Drive C.", target.name), Toast.LENGTH_SHORT).show()
            onFinished()
        } else if (info.exeAndroid == null) {
            Toast.makeText(context, L("Couldn't locate this game's files on disk."), Toast.LENGTH_LONG).show()
            onFinished()
        }
    }
    if (!actionable) return
    val exeAndroid = info.exeAndroid!!

    var phase by remember(target) { mutableStateOf(LabCopyPhase.CONFIRM) }
    var sourceRoot by remember(target) { mutableStateOf(CopyGameToDriveC.defaultSourceRoot(exeAndroid)) }
    var sourceSize by remember(target) { mutableStateOf<Long?>(null) }
    var validationError by remember(target) { mutableStateOf<String?>(null) }
    val cancelFlag = remember(target) { java.util.concurrent.atomic.AtomicBoolean(false) }
    var progress by remember(target) { mutableStateOf<CopyGameToDriveC.Progress?>(null) }
    fun fmt(bytes: Long) = android.text.format.Formatter.formatShortFileSize(context, bytes)
    val notThere = L("That folder doesn't contain this game's .exe. Pick the folder the game runs from.")

    LaunchedEffect(sourceRoot) {
        validationError = if (!CopyGameToDriveC.isAncestor(sourceRoot, exeAndroid)) notThere else null
        sourceSize = null
        sourceSize = withContext(Dispatchers.IO) { CopyGameToDriveC.folderSize(sourceRoot) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) InAppFilePicker.pickedPath(result.data)?.let { sourceRoot = File(it) }
    }

    fun startCopy(dest: File, cleanFirst: Boolean) {
        cancelFlag.set(false)
        val total = sourceSize ?: 0L
        val src = sourceRoot
        progress = CopyGameToDriveC.Progress(0, total, "")
        phase = LabCopyPhase.COPYING
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    if (cleanFirst) dest.deleteRecursively()
                    CopyGameToDriveC.copyTree(src, dest, total, { cancelFlag.get() }) { p -> progress = p }
                    CopyGameToDriveC.repoint(target, src, dest) ?: throw java.io.IOException("Couldn't repoint the shortcut to the copied files.")
                }
            }
            outcome.fold(
                onSuccess = { phase = LabCopyPhase.DELETE_ORIGINAL },
                onFailure = { e ->
                    if (e is CopyGameToDriveC.CancelledException) {
                        Toast.makeText(context, L("Copy cancelled — nothing was changed."), Toast.LENGTH_SHORT).show()
                    } else {
                        android.util.Log.e("CopyToDriveC", "copy/repoint failed for ${target.name}", e)
                        Toast.makeText(context, L("Copy failed: %1\$s. Nothing was changed.", e.message ?: "unknown error"), Toast.LENGTH_LONG).show()
                    }
                    onFinished()
                },
            )
        }
    }
    fun confirmAndCopy() {
        if (!CopyGameToDriveC.isAncestor(sourceRoot, exeAndroid)) { validationError = notThere; return }
        val size = sourceSize ?: return
        if (!CopyGameToDriveC.hasRoomFor(context, size)) {
            validationError = L("Not enough space on Drive C. Needs %1\$s plus headroom, but only %2\$s is free.", fmt(size), fmt(CopyGameToDriveC.freeBytes(context)))
            return
        }
        val dest = CopyGameToDriveC.destRootFor(target.container, sourceRoot.name)
        if (dest.exists()) phase = LabCopyPhase.OVERWRITE else startCopy(dest, cleanFirst = false)
    }

    when (phase) {
        LabCopyPhase.CONFIRM -> {
            val first = rememberLabFirstFocus()
            LabActionDialog(
                title = L("Copy \"%1\$s\" to Drive C", target.name),
                onDismiss = onFinished,
                buttons = {
                    LabDialogButton(L("Cancel"), onClick = onFinished)
                    LabDialogButton(L("Copy"), primary = true, enabled = validationError == null && sourceSize != null, requester = first) { confirmAndCopy() }
                },
            ) {
                LabActionText(L("Copies the game onto this container's C: drive (native app storage) and points the shortcut there. Fixes games that stall streaming from shared storage."), small = true)
                LabDialogGroup(L("Folder to copy"))
                LabActionRow(
                    sourceRoot.absolutePath,
                    subtitle = when {
                        validationError != null -> L("Size —")
                        sourceSize == null -> L("Calculating size…")
                        else -> L("Size: %1\$s", fmt(sourceSize!!))
                    },
                    note = L("Change folder…"),
                    noteColor = MaterialTheme.colorScheme.primary,
                    icon = Icons.Filled.Folder,
                ) {
                    val driveC = File(target.container.rootDir, ".wine/drive_c").takeIf { it.isDirectory }
                    folderPicker.launch(
                        InAppFilePicker.buildDirIntent(context, "Select the game's folder", initialDir = sourceRoot.absolutePath, driveCPath = driveC?.absolutePath)
                    )
                }
                LabActionText(L("Pick the folder that holds everything the game needs, not just the .exe."), small = true)
                validationError?.let { LabActionText(it, error = true, small = true) }
            }
        }
        LabCopyPhase.OVERWRITE -> SimpleActionSheet(
            title = L("Already on Drive C"),
            message = L("A folder named \"%1\$s\" already exists under C:\\%2\$s. Overwrite it, or keep both by copying to a new folder?", sourceRoot.name, CopyGameToDriveC.GAMES_SUBDIR),
            actions = listOf(
                MenuAction(L("Keep both (new folder)"), {
                    startCopy(CopyGameToDriveC.autoRenamedDest(CopyGameToDriveC.destRootFor(target.container, sourceRoot.name)), cleanFirst = false)
                }),
                MenuAction(L("Overwrite"), { startCopy(CopyGameToDriveC.destRootFor(target.container, sourceRoot.name), cleanFirst = true) }, danger = true),
                MenuAction(L("Cancel"), onFinished),
            ),
            onDismiss = onFinished,
        ) { it.run() }
        LabCopyPhase.COPYING -> {
            val p = progress
            val total = p?.totalBytes ?: 0L
            val done = p?.copiedBytes ?: 0L
            val first = rememberLabFirstFocus()
            LabActionDialog(
                title = L("Copying to Drive C…"),
                onDismiss = {},
                dismissible = false,
                buttons = { LabDialogButton(L("Cancel"), requester = first) { cancelFlag.set(true) } },
            ) {
                LabProgressLine(
                    text = if (total > 0L) "${fmt(done)} / ${fmt(total)}" else L("Copying…"),
                    fraction = if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else null,
                    detail = p?.currentFile?.takeIf { it.isNotEmpty() },
                )
            }
        }
        LabCopyPhase.DELETE_ORIGINAL -> {
            val keep = {
                Toast.makeText(context, L("Copied to Drive C — original kept."), Toast.LENGTH_SHORT).show()
                onFinished()
            }
            SimpleActionSheet(
                title = L("Copied to Drive C"),
                message = L("\"%1\$s\" now runs from Drive C. Delete the original folder to free %2\$s, or keep it as a backup?", target.name, sourceSize?.let { fmt(it) } ?: L("space")),
                actions = listOf(
                    MenuAction(L("Keep original"), keep),
                    MenuAction(L("Delete original"), {
                        val src = sourceRoot
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { src.deleteRecursively() } }
                            Toast.makeText(context, L("Moved to Drive C — original deleted."), Toast.LENGTH_SHORT).show()
                            onFinished()
                        }
                    }, danger = true),
                ),
                onDismiss = {
                    Toast.makeText(context, L("\"%1\$s\" now runs from Drive C.", target.name), Toast.LENGTH_SHORT).show()
                    onFinished()
                },
            ) { it.run() }
        }
    }
}

/**
 * Change executable in Lab: the in-app file picker at the current exe's folder (as Classic's ChangeExecutableCoordinator),
 * then, when the game has launch arguments, Keep or Clear them; written through CopyGameToDriveC.setShortcutExe.
 */
@Composable
private fun LabChangeExecutable(target: Shortcut?, onFinished: () -> Unit) {
    if (target == null) return
    val context = LocalContext.current
    val info = remember(target) { CopyGameToDriveC.parse(target) }
    var pendingExe by remember(target) { mutableStateOf<File?>(null) }
    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("exe", "lnk", "desktop")
    fun applyExe(exe: File, argsSuffix: String) {
        val win = CopyGameToDriveC.setShortcutExe(target, exe, argsSuffix)
        Toast.makeText(
            context,
            if (win == null) L("That file isn't on a drive this container can reach. Keep the game on storage the container maps (internal/SD, or its C: drive).")
            else L("\"%1\$s\" now launches %2\$s", target.name, win),
            Toast.LENGTH_LONG,
        ).show()
        onFinished()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) { onFinished(); return@rememberLauncherForActivityResult }
        val file = InAppFilePicker.pickedPath(result.data)?.let { File(it) }
        if (file == null || !file.isFile || !isSupported(file.name)) {
            Toast.makeText(context, L("Pick a .exe, .lnk, or .desktop file."), Toast.LENGTH_LONG).show()
            onFinished(); return@rememberLauncherForActivityResult
        }
        if (info.argsSuffix.isBlank()) applyExe(file, "") else pendingExe = file
    }
    LaunchedEffect(target) {
        val driveC = File(target.container.rootDir, ".wine/drive_c").takeIf { it.isDirectory }
        picker.launch(
            InAppFilePicker.buildIntent(
                context, InAppFilePicker.SHORTCUT, "Select the game's .exe",
                initialDir = info.exeAndroid?.parentFile?.absolutePath, driveCPath = driveC?.absolutePath,
            )
        )
    }
    pendingExe?.let { exe ->
        SimpleActionSheet(
            title = L("Launch arguments"),
            message = L("This shortcut launches with arguments:") + "\n" + info.argsSuffix.trim(),
            actions = listOf(
                MenuAction(L("Keep arguments"), { applyExe(exe, info.argsSuffix) }),
                MenuAction(L("Clear arguments"), { applyExe(exe, "") }),
                MenuAction(L("Cancel"), onFinished),
            ),
            onDismiss = onFinished,
        ) { it.run() }
    }
}

/**
 * Move to another container in Lab: the steps of Classic's MoveToContainerCoordinator (the target, Keep a copy, what can
 * come along from MoveGameToContainer.plan, why a choice can't be used, the move with its step), as Lab rows.
 */
@Composable
private fun LabMoveToContainer(
    target: Shortcut?,
    containers: () -> List<Container>,
    onEmuConflicts: (List<GameSaveBackup.EmuIdConflict>) -> Unit,
    onFinished: () -> Unit,
) {
    if (target == null) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val others = remember(target) { containers().filter { it.id != target.container.id } }
    var chosen by remember(target) { mutableStateOf(others.singleOrNull()) }
    var keepCopy by remember(target) { mutableStateOf(false) }
    var plan by remember(target) { mutableStateOf<MoveGameToContainer.Plan?>(null) }
    var bringSaves by remember(target) { mutableStateOf(false) }
    var bringFolder by remember(target) { mutableStateOf(false) }
    var bringExtras by remember(target) { mutableStateOf(emptySet<String>()) }
    var problem by remember(target) { mutableStateOf<String?>(null) }
    var checking by remember(target) { mutableStateOf(false) }
    var stage by remember(target) { mutableStateOf<String?>(null) }
    val busy = stage != null

    LaunchedEffect(chosen) {
        val c = chosen ?: return@LaunchedEffect
        plan = null
        val p = withContext(Dispatchers.IO) { MoveGameToContainer.plan(context, target, c) }
        bringSaves = p.saves != null
        bringFolder = p.gameFolder != null && p.gameFolder.problem == null
        bringExtras = p.extras.filter { it.suggested && it.source != MoveGameToContainer.ExtraSource.UNAVAILABLE }.map { it.name }.toSet()
        plan = p
    }
    val options = MoveGameToContainer.Options(
        saves = bringSaves && plan?.saves != null,
        gameFolder = bringFolder && plan?.gameFolder != null,
        extras = bringExtras,
    )
    LaunchedEffect(chosen, keepCopy, plan, bringSaves, bringFolder, bringExtras) {
        val c = chosen ?: return@LaunchedEffect
        if (plan == null) return@LaunchedEffect
        checking = true
        problem = withContext(Dispatchers.IO) { MoveGameToContainer.problem(context, target, c, keepCopy, options, plan) }
        checking = false
    }
    val from = target.container.name
    val first = rememberLabFirstFocus()
    fun go() {
        val c = chosen ?: return
        val p = plan ?: return
        val copy = keepCopy
        val opts = options
        stage = if (copy) L("Copying…") else L("Moving…")
        scope.launch {
            val r = withContext(Dispatchers.IO) { MoveGameToContainer.run(context, target, c, copy, opts, p) { s -> stage = s } }
            stage = null
            when (r) {
                is MoveGameToContainer.Result.Done -> {
                    Toast.makeText(context, MoveGameToContainer.message(r, target.name, c.name, copy), Toast.LENGTH_LONG).show()
                    if (r.emuConflicts.isNotEmpty()) onEmuConflicts(r.emuConflicts)
                    onFinished()
                }
                is MoveGameToContainer.Result.Refused -> problem = r.reason
            }
        }
    }

    LabActionDialog(
        title = L("Move \"%1\$s\"", target.name),
        subtitle = L("Now in \"%1\$s\".", from),
        onDismiss = onFinished,
        dismissible = !busy,
        buttons = {
            LabDialogButton(L("Cancel"), enabled = !busy, requester = if (others.isEmpty()) first else null, onClick = onFinished)
            if (chosen != null) {
                LabDialogButton(if (keepCopy) L("Copy") else L("Move"), primary = true, enabled = !busy && !checking && plan != null && problem == null) { go() }
            }
        },
    ) {
        if (others.isEmpty()) {
            LabActionText(L(NO_OTHER_CONTAINER))
            return@LabActionDialog
        }
        LabDialogGroup(L("Move to"))
        others.forEachIndexed { i, c ->
            LabActionRow(c.name, subtitle = c.wineVersion, selected = chosen?.id == c.id, enabled = !busy, requester = if (i == 0) first else null) { chosen = c }
        }
        LabActionRow(L("Keep a copy in \"%1\$s\"", from), switch = keepCopy, enabled = !busy) { keepCopy = !keepCopy }
        val p = plan
        if (chosen != null) {
            LabDialogGroup(L("Bring along"))
            if (p == null) {
                LabProgressLine(L("Checking what this game uses…"))
            } else {
                LabActionRow(
                    L("Saves"), subtitle = p.saves?.label ?: L("No save folders found for this game"),
                    switch = bringSaves && p.saves != null, enabled = p.saves != null && !busy,
                ) { bringSaves = !bringSaves }
                p.gameFolder?.let { gf ->
                    val size = android.text.format.Formatter.formatShortFileSize(context, gf.sizeBytes)
                    LabActionRow(
                        L("Game files (C:\\%1\$s)", gf.relPath.replace('/', '\\')),
                        subtitle = gf.problem ?: if (keepCopy) L("%1\$s, copied", size) else L("%1\$s, moved (no copying)", size),
                        switch = bringFolder && gf.problem == null, enabled = gf.problem == null && !busy,
                    ) { bringFolder = !bringFolder }
                }
                if (p.extras.isEmpty()) LabActionText(L("Extras: nothing this game needs is missing in \"%1\$s\".", chosen?.name.orEmpty()), small = true)
                p.extras.forEach { e ->
                    LabActionRow(
                        e.label, subtitle = e.note, switch = e.name in bringExtras,
                        enabled = e.source != MoveGameToContainer.ExtraSource.UNAVAILABLE && !busy,
                    ) { bringExtras = if (e.name in bringExtras) bringExtras - e.name else bringExtras + e.name }
                }
            }
        }
        LabActionText(L("The game's settings, icon and cover art always come along."), small = true)
        problem?.let { LabActionText(it, error = true, small = true) }
        stage?.let { LabProgressLine(it) }
    }
}
