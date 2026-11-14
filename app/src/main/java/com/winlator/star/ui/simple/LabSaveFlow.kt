package com.winlator.star.ui.simple

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winlator.star.R
import com.winlator.star.container.Container
import com.winlator.star.core.SaveLocator
import com.winlator.star.core.StringUtils
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.scanGameSaves
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

// Lab's steps of Classic's game-save backup / restore (ContainersScreen's SaveFlow) and its progress popups.

/** Classic's "Not foolproof yet" caution of the save flow, as one text for a Lab sheet's message. */
@Composable
internal fun labSaveCaution(): String =
    "⚠  " + stringResource(R.string.save_flow_caution_title) + "\n\n" + stringResource(R.string.save_flow_caution_body)

/** One choice of a [LabChoiceSheet]: a label with a line under it. */
internal class LabChoice(val label: String, val sub: String?, val run: () -> Unit)

/** A Lab list of choices that each have a description under them (the backup formats). A runs one, B closes. */
@Composable
internal fun LabChoiceSheet(title: String, message: String?, choices: List<LabChoice>, onDismiss: () -> Unit, back: (() -> Unit)? = null) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Dialog(onDismissRequest = onDismiss) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 540.dp)
                .heightIn(max = 620.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
        ) {
            Text(title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 10.dp))
            if (!message.isNullOrBlank()) Text(message, color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp))
            choices.forEachIndexed { i, c ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .labTile(onClick = c.run, requester = if (i == 0) first else null)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(c.label, color = SimpleStrong, fontSize = 15.sp)
                    if (!c.sub.isNullOrBlank()) Text(c.sub, color = SimpleMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (back != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { LabButton(L("Back"), onClick = back) }
            }
        }
    }
}

/** Classic's blocking progress popup (SaveFlowProgressDialog) in Lab's look: a spinner and [message], no way out. */
@Composable
internal fun LabProgressDialog(message: String) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(min = 260.dp, max = 520.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(22.dp),
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Text(message, color = SimpleStrong, fontSize = 15.sp, modifier = Modifier.padding(start = 18.dp))
        }
    }
}

/**
 * Classic's install-progress popup (InstallProgressDialog) in Lab's look, off the same [ContentDownloadState]: name,
 * type, description, version, phase with the two-pass bar, Cancel while it runs and Done / Close once it ended.
 */
@Composable
internal fun LabInstallProgress(state: ContentDownloadState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val terminal = state.terminal
    val button = remember { FocusRequester() }
    LaunchedEffect(terminal) {
        delay(60)
        runCatching { button.requestFocus() }
    }
    Dialog(
        onDismissRequest = { if (terminal) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = terminal, dismissOnClickOutside = terminal),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 300.dp, max = 560.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(state.verName ?: state.title, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            state.type?.takeIf { it.isNotBlank() }?.let { SimpleChip(it) }
            if (!state.desc.isNullOrBlank()) Text(state.desc, color = SimpleInk, fontSize = 13.sp)
            val name = state.verName?.takeIf { it.isNotBlank() }
            val code = state.verCode?.takeIf { it.isNotBlank() && it != "0" }
            val version = when {
                name != null && code != null -> L("Version %1\$s • build %2\$s", name, code)
                name != null -> L("Version %1\$s", name)
                code != null -> L("build %1\$s", code)
                else -> null
            }
            if (version != null) Text(version, color = SimpleMuted, fontSize = 12.5.sp)
            val frac = state.fraction.coerceIn(0f, 1f)
            val label = when {
                state.cancelled -> L("Cancelled")
                state.phase == ContentDownloadPhase.ERROR -> state.error ?: if (state.saveOnly) L("Save failed.") else L("Install failed.")
                state.phase == ContentDownloadPhase.DONE -> if (state.saveOnly) L("Saved to My Files") else state.note ?: L("Installed")
                state.phase == ContentDownloadPhase.DOWNLOADING -> L("Downloading %1\$s%%", (frac * 100).toInt())
                state.saveOnly -> L("Saving to My Files")
                else -> L("Installing %1\$s%%", (frac * 100).toInt())
            }
            val labelColor = when {
                state.phase == ContentDownloadPhase.ERROR && !state.cancelled -> MaterialTheme.colorScheme.error
                state.phase == ContentDownloadPhase.DONE -> accent
                else -> SimpleMuted
            }
            Spacer(Modifier.height(4.dp))
            Text(label, color = labelColor, fontSize = 13.sp)
            if (!terminal || state.phase == ContentDownloadPhase.DONE) {
                // Download pass darker, install pass lighter, as in Classic; a local file has the install pass only.
                val dlFrac = when {
                    !state.hasDownload -> 0f
                    state.phase == ContentDownloadPhase.DOWNLOADING -> frac
                    else -> 1f
                }
                val instFrac = if (state.phase == ContentDownloadPhase.DOWNLOADING) 0f else frac
                Box(Modifier.fillMaxWidth().height(5.dp).background(SimpleLine, SimpleShape)) {
                    if (dlFrac > 0f) Box(Modifier.fillMaxWidth(dlFrac).fillMaxHeight().background(lerp(accent, Color.Black, 0.40f), SimpleShape))
                    if (instFrac > 0f) Box(Modifier.fillMaxWidth(instFrac).fillMaxHeight().background(lerp(accent, Color.White, 0.12f), SimpleShape))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!terminal) LabButton(L("Cancel"), requester = button, onClick = onCancel)
                else LabButton(if (state.phase == ContentDownloadPhase.DONE) L("Done") else L("Close"), primary = true, requester = button, onClick = onDismiss)
            }
        }
    }
}

/**
 * Classic's GameSavesDialog in Lab's look: the remembered or found save folders of one game to tick, the emulator
 * folders the backup always takes, Add folder… (a browser inside the game's profile), Re-scan, Back and Next. The same
 * scan (scanGameSaves) and the same remembered set (SaveLocator.saveMap) as Classic.
 */
@Composable
internal fun LabGameSaves(
    container: Container,
    gameName: String,
    gameKey: String,
    exePath: String,
    wmClass: String,
    appIdExtra: String,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onNext: (roots: List<String>, appId: Int?) -> Unit,
) {
    val rows = remember(gameKey) { mutableStateListOf<SaveLocator.Candidate>() }
    val checked = remember(gameKey) { mutableStateListOf<String>() }
    var appId by remember(gameKey) { mutableStateOf<Int?>(null) }
    val emuDirs = remember(gameKey) { mutableStateListOf<Pair<String, Long>>() }
    var loading by remember(gameKey) { mutableStateOf(true) }
    var fromSidecar by remember(gameKey) { mutableStateOf(false) }
    var rescan by remember(gameKey) { mutableIntStateOf(0) }
    var showManualAdd by remember(gameKey) { mutableStateOf(false) }
    var escapeError by remember(gameKey) { mutableStateOf(false) }
    LaunchedEffect(gameKey, rescan) {
        loading = true
        val scan = withContext(Dispatchers.IO) { scanGameSaves(container, gameKey, gameName, exePath, wmClass, appIdExtra, useSidecar = rescan == 0) }
        appId = scan.appId
        emuDirs.clear(); emuDirs.addAll(scan.emuDirs)
        rows.clear(); rows.addAll(scan.rows)
        checked.clear(); checked.addAll(scan.rows.map { it.relPath })
        fromSidecar = scan.fromSidecar
        loading = false
    }
    if (loading) {
        LabProgressDialog(stringResource(R.string.save_backup_discovering))
        return
    }
    if (showManualAdd) {
        LabSaveFolderPicker(
            container = container,
            onCancel = { showManualAdd = false },
            onPick = { dir ->
                showManualAdd = false
                val rel = SaveLocator.relativizeUnderProfile(container, dir)
                if (rel == null) escapeError = true
                else {
                    escapeError = false
                    if (rows.none { it.relPath == rel }) rows.add(SaveLocator.Candidate(rel, dir.name, 100, SaveLocator.sizeOf(container, rel)))
                    if (!checked.contains(rel)) checked.add(rel)
                }
            },
        )
        return
    }
    val overlap = checked.any { a -> checked.any { b -> a != b && a.startsWith("$b/") } }
    val canNext = checked.isNotEmpty() || emuDirs.isNotEmpty()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { first.requestFocus() }
    }
    Dialog(onDismissRequest = onCancel) {
        com.winlator.star.sound.DialogSound()
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 600.dp)
                .heightIn(max = 640.dp)
                .simpleDepth(high = true)
                .background(SimplePanelBrush, SimpleShape)
                .border(1.dp, SimpleLine, SimpleShape)
                .padding(14.dp),
        ) {
            Text(gameName, color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, bottom = 6.dp))
            Text(
                stringResource(
                    when {
                        rows.isEmpty() -> R.string.save_backup_confirm_none
                        fromSidecar -> R.string.save_backup_confirm_remembered
                        else -> R.string.save_backup_confirm_prompt
                    },
                ),
                color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                rows.forEachIndexed { i, c ->
                    val on = checked.contains(c.relPath)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .labTile(onClick = { if (on) checked.remove(c.relPath) else checked.add(c.relPath) }, requester = if (i == 0) first else null)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.relPath, color = SimpleStrong, fontSize = 14.sp)
                            Text(StringUtils.formatBytes(c.sizeBytes), color = SimpleMuted, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        LabSwitch(on)
                    }
                }
                val id = appId
                if (id != null && emuDirs.isNotEmpty()) {
                    Text(stringResource(R.string.save_backup_emu_included, id), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
                    emuDirs.forEach { (rel, size) ->
                        Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 3.dp, bottom = 3.dp)) {
                            Text(rel, color = SimpleInk, fontSize = 14.sp)
                            Text(StringUtils.formatBytes(size), color = SimpleMuted, fontSize = 12.sp)
                        }
                    }
                }
                if (escapeError) Text(stringResource(R.string.save_backup_add_folder_escape), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
                if (overlap) Text(stringResource(R.string.save_backup_overlap_note), color = SimpleMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(stringResource(R.string.save_backup_add_folder), requester = if (rows.isEmpty()) first else null) { showManualAdd = true }
                if (fromSidecar) LabButton(stringResource(R.string.save_backup_rescan)) { rescan += 1 }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                LabButton(L("Back"), onClick = onBack)
                LabButton(stringResource(R.string.save_backup_next), primary = canNext) {
                    if (!canNext) return@LabButton
                    val roots = checked.toList()
                    // As Classic: the taught set is remembered off the UI thread; emulator folders alone teach nothing.
                    if (roots.isNotEmpty()) Thread { SaveLocator.saveMap(container, gameKey, gameName, roots) }.start()
                    onNext(roots, appId)
                }
            }
        }
    }
}

/** Classic's ManualAddDialog in Lab's look: a folder browser confined to the container's profile; "Use this folder" picks. */
@Composable
private fun LabSaveFolderPicker(container: Container, onPick: (File) -> Unit, onCancel: () -> Unit) {
    val root = remember(container.id) { SaveLocator.profileDir(container) }
    var cur by remember(container.id) { mutableStateOf(root) }
    val subDirs = remember(cur.path) {
        cur.listFiles()?.filter { it.isDirectory }?.distinctBy { it.path }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }
    val atRoot = cur.path == root.path
    val here = stringResource(R.string.save_backup_add_folder_here)
    val actions = buildList {
        if (!atRoot) add(MenuAction(here, { onPick(cur) }))
        if (!atRoot) add(MenuAction("..", { cur.parentFile?.let { cur = it } }))
        subDirs.forEach { d -> add(MenuAction(d.name + "/", { cur = d })) }
        add(MenuAction(L("Cancel"), onCancel))
    }
    androidx.compose.runtime.key(cur.path) {
        SimpleActionSheet(
            title = stringResource(R.string.save_backup_add_folder_title),
            actions = actions,
            onDismiss = onCancel,
            message = "…/" + (if (atRoot) "" else cur.relativeTo(root).path.replace(File.separatorChar, '/')),
        ) { it.run() }
    }
}
