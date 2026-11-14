package com.winlator.star.ui.simple

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogReport
import com.winlator.star.ui.deck.SoraFamily
import com.winlator.star.ui.screens.TAIL_BYTES
import com.winlator.star.ui.screens.lineColor
import com.winlator.star.ui.screens.openLogReportIssue
import com.winlator.star.ui.screens.readTail
import com.winlator.star.ui.screens.runLabel
import com.winlator.star.ui.screens.shareLogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lab pages of Tools: the file manager at a folder, the Wine debug channels, Winlator's saves. */
internal object LabToolRoutes {
    /** The file manager opened at a folder (the Log Manager's Browse); the rail keeps Tools lit ("simple_files…"). */
    const val FILES_AT = "simple_files_at?dir={dir}"
    fun filesAt(dir: File) = "simple_files_at?dir=" + android.net.Uri.encode(dir.absolutePath)
    const val LOG_CHANNELS = "simple_logs_channels"
    /** Winlator's own save slots (Classic's Saves screen). */
    const val WINLATOR_SAVES = "simple_tools_saves"
}

/** File manager, file picker, logs and saves (Lab). */
internal fun NavGraphBuilder.labToolsRoutes(open: (String) -> Unit, back: () -> Unit) {
    composable(LabToolRoutes.FILES_AT, arguments = listOf(navArgument("dir") { type = NavType.StringType; defaultValue = "" })) { e ->
        LabFileManagerPage(onBack = back, initialDir = e.arguments?.getString("dir")?.takeIf { it.isNotBlank() }?.let(::File))
    }
    composable(LabToolRoutes.LOG_CHANNELS) { LabWineChannelsPage(onBack = back) }
    composable(LabToolRoutes.WINLATOR_SAVES) { LabWinlatorSavesPage(onBack = back) }
}

/**
 * Lab's viewer for one log entry (the Log Manager's logs, a game's "View logs"): Classic's log viewer drawn in Lab's
 * look. The run (current, Wayland, earlier ones) and the file as chips, follow it live, wrap, find in it (live filter),
 * copy what is shown, share the file, report a problem. The log scrolls with Up / Down (Left / Right sideways when not
 * wrapped); Y finds, X shares, Start follows, B closes. Draws Lab's wallpaper itself, so it works as a page or full
 * screen in a dialog.
 */
@Composable
internal fun LabLogViewer(entry: LogInventory.Entry, onClose: () -> Unit) {
    SimpleFlat { LabLogViewerBody(entry, onClose) }
}

@Composable
private fun LabLogViewerBody(entry: LogInventory.Entry, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val accent = MaterialTheme.colorScheme.primary
    val scope = rememberCoroutineScope()

    // As Classic: the runs first (the kept ones too), then the files of the chosen run; open on the first run with files.
    val runs = remember(entry.dir) { LogInventory.runsIn(entry.dir) }
    var selectedRun by remember { mutableStateOf(runs.firstOrNull { LogInventory.filesIn(it.dir).isNotEmpty() } ?: runs.firstOrNull()) }
    val files = remember(selectedRun) { LogInventory.filesIn(selectedRun?.dir ?: entry.dir) }
    var selected by remember(selectedRun) { mutableStateOf(files.firstOrNull()) }
    var following by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var findOpen by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var truncatedFrom by remember { mutableLongStateOf(0L) }
    var reportOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val hScroll = rememberScrollState()
    val bodyFocus = remember { FocusRequester() }
    var bodyFocused by remember { mutableStateOf(false) }

    // As Classic: read the tail on IO, again every second while following.
    LaunchedEffect(selected, following) {
        val f = selected ?: return@LaunchedEffect
        do {
            val (text, from) = withContext(Dispatchers.IO) { readTail(f) }
            lines = text
            truncatedFrom = from
            if (following) {
                listState.scrollToItem(maxOf(0, lines.size - 1))
                delay(1000)
            }
        } while (following)
    }
    val shown = remember(lines, query) { if (query.isBlank()) lines else lines.filter { it.contains(query, ignoreCase = true) } }
    LaunchedEffect(Unit) { delay(80); runCatching { bodyFocus.requestFocus() } }
    val dialogOpen = findOpen || reportOpen
    LaunchedEffect(dialogOpen) { if (!dialogOpen) { delay(60); runCatching { bodyFocus.requestFocus() } } }

    fun share() { selected?.let { shareLogFile(context, it) } }
    fun copy() {
        clipboard.setText(AnnotatedString(shown.joinToString("\n")))
        toast(context, if (shown.size == 1) L("Copied 1 line") else L("Copied %1\$s lines", shown.size))
    }
    BackHandler { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { ev ->
                if (dialogOpen) return@onPreviewKeyEvent false
                when (simpleInputOf(ev.nativeKeyEvent)) {
                    SIn.B -> { onClose(); true }
                    SIn.Y -> { findOpen = true; true }
                    SIn.X -> { share(); true }
                    SIn.START -> { following = !following; true }
                    else -> false
                }
            },
    ) {
        LabWallpaper()
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(L("Logs").uppercase(), color = accent, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp)
                    Text(
                        if (entry.isAppBucket) L("App & crash logs") else entry.name,
                        color = SimpleStrong, fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                selected?.let { f -> Text("${f.name} · ${LogInventory.humanBytes(f.length())}", color = SimpleMuted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) }
            }
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty() && runs.size <= 1) {
                Text(L("This folder has no log files right now."), color = SimpleMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                Spacer(Modifier.weight(1f))
                Row { LabButton(L("Close"), primary = true, requester = bodyFocus, onClick = onClose) }
                return@Column
            }
            // The runs, shown when there is history to switch to.
            if (runs.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(L("Run"), color = SimpleDim, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                    runs.forEach { r ->
                        LabViewerChip(if (r.current) L("Current") else if (r.wayland) L("Wayland") else runLabel(r.millis), r == selectedRun) {
                            selectedRun = r
                            query = ""
                            // Following a finished run would poll a file that can never change.
                            if (!r.current && !r.wayland) following = false
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            // One chip per file of the run.
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(L("File"), color = SimpleDim, fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
                files.forEach { f -> LabViewerChip(f.name, f == selected) { selected = f; query = "" } }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LabViewerChip(if (following) L("Following") else L("Follow"), following, dot = if (following) Color(0xFF5FBF6B) else SimpleDim) { following = !following }
                LabViewerChip(L("Wrap"), wrap) { wrap = !wrap }
                LabViewerChip(L("Find"), query.isNotBlank()) { findOpen = true }
                LabViewerChip(L("Copy"), false) { copy() }
                LabViewerChip(L("Share"), false) { share() }
                LabViewerChip(L("Report a problem"), false) { reportOpen = true }
            }
            if (query.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (shown.size == 1) L("1 line matches \"%1\$s\"", query) else L("%1\$s lines match \"%2\$s\"", shown.size, query),
                        color = SimpleStrong, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(L("Clear"), color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(SimpleCard, SimpleShape).simpleTap { query = "" }.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            // The log as a terminal (dark in both looks, as Classic: the severity colours read on it).
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF0B0807), SimpleShape)
                    .border(if (bodyFocused) 2.dp else 1.dp, if (bodyFocused) accent else SimpleLine, SimpleShape)
                    .focusRequester(bodyFocus)
                    .onFocusChanged { bodyFocused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        val a = simpleInputOf(ev.nativeKeyEvent) ?: return@onPreviewKeyEvent false
                        when (a) {
                            SIn.UP -> if (listState.canScrollBackward) { scope.launch { listState.animateScrollBy(-260f) }; true } else false
                            SIn.DOWN -> { if (listState.canScrollForward) scope.launch { listState.animateScrollBy(260f) }; true }
                            SIn.LEFT -> if (!wrap && hScroll.value > 0) { scope.launch { hScroll.animateScrollBy(-240f) }; true } else false
                            SIn.RIGHT -> if (!wrap && hScroll.value < hScroll.maxValue) { scope.launch { hScroll.animateScrollBy(240f) }; true } else false
                            else -> false
                        }
                    }
                    .focusable()
                    .padding(8.dp),
            ) {
                if (shown.isEmpty()) {
                    Text(if (query.isBlank()) L("This log is empty.") else L("No lines match \"%1\$s\".", query), color = Color(0xFF8A7F7A), fontSize = 12.sp)
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        if (truncatedFrom > 0) item {
                            Text(
                                L("… showing the last %1\$s of %2\$s", LogInventory.humanBytes(TAIL_BYTES), LogInventory.humanBytes(truncatedFrom)),
                                color = Color(0xFF8A7F7A), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 4.dp),
                            )
                        }
                        items(shown.size) { i ->
                            val line = shown[i]
                            Text(
                                line, color = lineColor(line), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                                softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1,
                                modifier = if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(hScroll),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SimpleLegend(
                listOf("B" to L("Close"), "▲▼" to L("Scroll"), "Y" to L("Find"), "X" to L("Share"), "Start" to if (following) L("Stop following") else L("Follow")),
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
        }
    }

    if (findOpen) {
        LabTextPrompt(
            title = L("Find in this log"), initial = query, confirm = L("Done"), placeholder = L("Text to find…"),
            valid = { true }, onChange = { query = it }, onDismiss = { findOpen = false },
        ) { v -> query = v; findOpen = false }
    }
    if (reportOpen) LabLogReportDialog(entry, selectedRun?.dir ?: entry.dir) { reportOpen = false }
}

/** A chip of the viewer (a run, a file, a switch): the accent outline when on, Lab's focus when the controller is on it. */
@Composable
private fun LabViewerChip(label: String, on: Boolean, dot: Color? = null, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(if (on) accent.copy(alpha = 0.16f) else SimpleCard, SimpleShape)
            .border(1.dp, if (on) accent else SimpleLine, SimpleShape)
            .labTile(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).background(dot, SimpleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (on) SimpleStrong else SimpleInk, fontSize = 13.sp, maxLines = 1)
    }
}

/**
 * Classic's "Report a problem" in Lab's look: a title and a description, which files go with it, the app logs too or
 * not, then the redacted zip is built and GitHub's new-issue form opens (same steps as Classic).
 */
@Composable
private fun LabLogReportDialog(entry: LogInventory.Entry, runDir: File, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(if (entry.isAppBucket) "" else "${entry.name}: ") }
    var description by remember { mutableStateOf("") }
    var includeApp by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val willAttach = remember(runDir) { LogInventory.filesIn(runDir).map { it.name } }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(80); runCatching { field.requestFocus() } }
    LabDialog(L("Report a problem"), onDismiss = { if (!busy) onDismiss() }, wide = true) {
        LabDialogHeading(L("What went wrong?"))
        LabField(title, { title = it }, requester = field)
        LabDialogHeading(L("Any detail that helps (optional)"))
        LabField(description, { description = it }, singleLine = false)
        LabDialogHeading(L("Will be attached"))
        willAttach.forEach { Text("• $it", color = SimpleInk, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
        LabDialogRow(L("Also app logcat and crash reports"), toggle = includeApp) { includeApp = !includeApp }
        Text(
            L("Saved as a zip in Downloads. E-mail addresses, auth tokens and your Steam account name are stripped; file paths are kept so they stay useful for debugging, so glance over them if a folder name identifies you. GitHub can't receive a file from a link, so attach the zip with 📎 once the form opens."),
            color = SimpleMuted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
        LabDialogButtons {
            LabButton(L("Cancel")) { if (!busy) onDismiss() }
            LabButton(if (busy) L("Working…") else L("Continue on GitHub"), primary = true) {
                if (busy) return@LabButton
                busy = true
                scope.launch {
                    // Reading, redacting and zipping several megabytes: not on the UI thread.
                    val bundle = withContext(Dispatchers.IO) { LogReport.build(context, entry, runDir, includeApp) }
                    busy = false
                    if (bundle == null) {
                        Toast.makeText(context, "Couldn't build the report.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    onDismiss()
                    openLogReportIssue(context, bundle, title, description)
                }
            }
        }
    }
}
