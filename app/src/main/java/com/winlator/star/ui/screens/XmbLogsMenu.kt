package com.winlator.star.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WrapText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.star.container.Shortcut
import com.winlator.star.core.LogInventory
import com.winlator.star.core.LogLocation
import com.winlator.star.core.LogReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// "View logs" as XMB columns: Logs (run + files) › one file (follow / wrap / find / copy / share) ›
// View (the tail in a scrollable panel). Reads the same tail LogViewerScreen does — the last
// TAIL_BYTES, never the whole file (a Wine debug log can pass 1 GB) — with the same colouring.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * "View logs": straight to this game's logs, like the dialog. Three outcomes read differently, as
 * there: per-game folders off, nothing captured yet, or the logs themselves.
 */
internal fun xmbLogsMenu(xmb: XmbScope, shortcut: Shortcut): XmbMenu {
    val ctx = xmb.context
    val name = shortcut.name
    val entry = LogInventory.forGame(ctx, name)
    if (entry == null) {
        val perGameOff = !LogLocation.isPerGameEnabled(ctx)
        val msg = if (perGameOff) {
            "Per-game log folders are turned off, so everything is written to one shared folder and logs " +
                "can't be traced back to a single game. Turn them on in Settings › Logs, then play $name once."
        } else {
            "Nothing has been captured for $name yet. Logs are written while a game runs — play it once, " +
                "then check back here."
        }
        return XmbMenu(title = "Logs", icon = Icons.Filled.Description) {
            XmbTools.para("title", "No logs for $name", Icons.Filled.Info, ctx) +
                XmbTools.para("msg", msg, XmbTools.blankIcon, ctx)
        }
    }

    // Runs first (current, then archived launches), then the files inside the chosen run.
    val runs = LogInventory.runsIn(entry.dir)
    val runLabels = xmbUniqueLabels(runs.map { if (it.current) "Current" else runLabel(it.millis) })
    var runIdx by mutableIntStateOf(0)
    var files by mutableStateOf<List<File>>(LogInventory.filesIn(runs.firstOrNull()?.dir ?: entry.dir))
    fun runDir(): File = runs.getOrNull(runIdx)?.dir ?: entry.dir

    return XmbMenu(title = if (entry.isAppBucket) "App & crash logs" else "Logs", icon = Icons.Filled.Description) {
        buildList<XmbRow> {
            if (runs.size > 1) {
                add(
                    XmbRow.Choice(
                        key = "run", label = "Run", icon = Icons.Filled.History,
                        options = runLabels, selected = runLabels.getOrElse(runIdx) { runLabels.first() },
                    ) { picked ->
                        runIdx = runLabels.indexOf(picked).coerceAtLeast(0)
                        files = LogInventory.filesIn(runDir())
                    }
                )
            }
            if (files.isEmpty()) {
                add(XmbRow.Info("empty", "This folder has no log files right now.", Icons.Filled.Info))
            } else {
                files.forEach { f ->
                    add(
                        XmbRow.Link(
                            key = "f:${f.name}", label = f.name, icon = Icons.Filled.InsertDriveFile,
                            value = LogInventory.humanBytes(f.length()),
                            subtitle = "Updated ${runLabel(f.lastModified())}",
                        ) { xmbLogFileMenu(xmb, f) }
                    )
                }
            }
            val dir = runDir()
            add(XmbRow.Link(key = "report", label = "Report a problem", icon = Icons.Filled.BugReport) { xmbLogReportMenu(xmb, entry, dir) })
        }
    }
}

// Choice options must be distinct; archived runs can share a label ("2 hr ago").
private fun xmbUniqueLabels(labels: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return labels.map { l ->
        val n = (seen[l] ?: 0) + 1
        seen[l] = n
        if (n == 1) l else "$l ($n)"
    }
}

/** One log file's loaded tail plus its view settings; shared by its column and its panel. */
private class XmbLogView(val file: File) {
    var lines by mutableStateOf<List<String>>(emptyList())
    var truncatedFrom by mutableLongStateOf(0L)
    var loaded by mutableStateOf(false)
    var following by mutableStateOf(false)
    var wrap by mutableStateOf(false)
    var query by mutableStateOf("")
    val shown: List<String> by derivedStateOf {
        val q = query
        if (q.isBlank()) lines else lines.filter { it.contains(q, ignoreCase = true) }
    }
    val list = LazyListState()
    val hScroll = ScrollState(0)
    private var job: Job? = null

    /** Items in the panel's list: the "showing the last …" note (when truncated) plus the lines. */
    fun itemCount(): Int = shown.size + if (truncatedFrom > 0 && shown.isNotEmpty()) 1 else 0

    fun scrollTo(xmb: XmbScope, index: Int) {
        val n = itemCount()
        if (n == 0) return
        xmb.scope.launch { list.scrollToItem(index.coerceIn(0, n - 1)) }
    }

    /** Load now; while following, reload every second and stay pinned to the end (as the viewer). */
    fun start(xmb: XmbScope) {
        job?.cancel()
        job = xmb.scope.launch {
            while (true) {
                val (text, from) = withContext(Dispatchers.IO) { readTail(file) }
                lines = text
                truncatedFrom = from
                loaded = true
                if (following) {
                    val last = itemCount() - 1
                    if (last >= 0) launch { list.scrollToItem(last) }
                    delay(1000)
                } else {
                    snapshotFlow { following }.first { it }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/** One file: open the tail, and the viewer's own controls (follow, wrap, find, copy, share). */
private fun xmbLogFileMenu(xmb: XmbScope, file: File): XmbMenu {
    val ctx = xmb.context
    val v = XmbLogView(file)
    v.start(xmb)
    return XmbMenu(title = file.name, icon = Icons.Filled.InsertDriveFile, initialKey = "view", onClose = { v.stop() }) {
        val shown = v.shown
        val count = "${shown.size} line${if (shown.size == 1) "" else "s"}"
        listOf(
            XmbRow.Link(
                key = "view", label = "View", icon = Icons.Filled.Description,
                value = LogInventory.humanBytes(file.length()),
                subtitle = if (v.loaded) count else "Loading…",
            ) { XmbMenu(title = "View", icon = Icons.Filled.Description, panel = XmbLogPanel(v, xmb)) },
            XmbRow.Toggle(key = "follow", label = "Follow", icon = Icons.Filled.Sync, value = v.following) { v.following = it },
            XmbRow.Toggle(key = "wrap", label = "Wrap", icon = Icons.Filled.WrapText, value = v.wrap) { v.wrap = it },
            XmbRow.Text(
                key = "find", label = "Find", icon = Icons.Filled.Search, value = v.query,
                placeholder = "Find in this log…",
                subtitle = if (v.query.isBlank()) null else count,
            ) { v.query = it },
            XmbRow.Action(
                key = "copy", label = "Copy", icon = Icons.Filled.ContentCopy,
                disabledReason = if (v.loaded) null else "Loading…",
            ) {
                val lines = v.shown
                val copied = runCatching {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText(file.name, lines.joinToString("\n")))
                    cm != null
                }.getOrDefault(false)
                if (copied) xmb.toast("Copied ${lines.size} lines")
            },
            XmbRow.External(key = "share", label = "Share", icon = Icons.Filled.Share) { shareLogFile(ctx, file) },
        )
    }
}

private val XMB_LOG_MUTED = Color(0xFF8A7F7A)
private val XMB_LOG_FOLLOW = Color(0xFF5FBF6B)

/**
 * The tail as a terminal-style panel. ▲▼ scroll a line, L1/R1 a page, ◀▶ sideways (when not
 * wrapping), A toggles follow; B goes back. Touch scrolls it directly.
 */
private class XmbLogPanel(private val v: XmbLogView, private val xmb: XmbScope) : XmbPanel {
    private var hStepPx = 240f

    @Composable
    override fun Content(modifier: Modifier) {
        hStepPx = with(LocalDensity.current) { 120.dp.toPx() }
        val shape = RoundedCornerShape(12.dp)
        val shown = v.shown
        Column(
            modifier
                .clip(shape)
                .background(Color(0xF20B0907))
                .border(1.dp, Color.White.copy(alpha = 0.1f), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (v.following) XMB_LOG_FOLLOW else Color(0xFF5A5A5A), RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (v.following) "following" else "follow",
                    color = if (v.following) XMB_LOG_FOLLOW else Color(0xFF9A9A9A), fontSize = 11.sp,
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "▲▼ line · L1 R1 page · ◀▶ scroll · A follow",
                    color = Color(0xFF7A7A7A), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${v.file.name} · ${LogInventory.humanBytes(v.file.length())}",
                    color = Color(0xFF9A9A9A), fontSize = 11.sp, maxLines = 1,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    !v.loaded -> Text("Loading…", color = XMB_LOG_MUTED, fontSize = 11.sp)
                    shown.isEmpty() -> Text(
                        if (v.query.isBlank()) "This log is empty." else "No lines match \"${v.query}\".",
                        color = XMB_LOG_MUTED, fontSize = 11.sp,
                    )
                    else -> LazyColumn(state = v.list, modifier = Modifier.fillMaxSize()) {
                        if (v.truncatedFrom > 0) {
                            item(key = "note") {
                                Text(
                                    "… showing the last ${LogInventory.humanBytes(TAIL_BYTES)} of " +
                                        LogInventory.humanBytes(v.truncatedFrom),
                                    color = XMB_LOG_MUTED, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        items(shown.size) { i ->
                            val line = shown[i]
                            Text(
                                line,
                                color = lineColor(line),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                softWrap = v.wrap,
                                maxLines = if (v.wrap) Int.MAX_VALUE else 1,
                                // One shared horizontal scroll so every line moves together.
                                modifier = if (v.wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(v.hScroll),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onKey(key: XmbKey): Boolean {
        val first = v.list.firstVisibleItemIndex
        val page = (v.list.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
        when (key) {
            XmbKey.Up -> { v.following = false; v.scrollTo(xmb, first - 1) }
            XmbKey.Down -> v.scrollTo(xmb, first + 1)
            XmbKey.L1 -> { v.following = false; v.scrollTo(xmb, first - page) }
            XmbKey.R1 -> v.scrollTo(xmb, first + page)
            XmbKey.Left -> if (!v.wrap) xmb.scope.launch { v.hScroll.scrollBy(-hStepPx) }
            XmbKey.Right -> if (!v.wrap) xmb.scope.launch { v.hScroll.scrollBy(hStepPx) }
            XmbKey.A -> v.following = !v.following
            XmbKey.B -> return false
        }
        return true
    }
}

/**
 * "Report a problem": the viewer's ReportDialog as a column — title, details, the app-logs toggle —
 * then LogReport builds the redacted zip into Downloads and GitHub's new-issue form opens.
 */
private fun xmbLogReportMenu(xmb: XmbScope, entry: LogInventory.Entry, runDir: File): XmbMenu {
    val ctx = xmb.context
    var title by mutableStateOf(if (entry.isAppBucket) "" else "${entry.name}: ")
    var description by mutableStateOf("")
    var includeApp by mutableStateOf(true)
    var busy by mutableStateOf(false)
    val willAttach = LogInventory.filesIn(runDir).map { it.name }
    val me = XmbOpen()

    fun submit() {
        if (busy) return
        busy = true
        xmb.scope.launch {
            // Reading, redacting and zipping several megabytes: not on the UI thread.
            val bundle = withContext(Dispatchers.IO) {
                runCatching { LogReport.build(ctx, entry, runDir, includeApp) }.getOrNull()
            }
            busy = false
            if (bundle == null) {
                xmb.toast("Couldn't build the report.")
                return@launch
            }
            me.close(xmb)
            xmb.toast("Saved ${bundle.zip.name} to Downloads")
            try {
                XmbTools.start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(LogReport.issueUrl(title, description, bundle.facts))))
            } catch (e: Exception) {
                xmb.toast("No browser to open GitHub with.")
            }
        }
    }

    return XmbMenu(title = "Report a problem", icon = Icons.Filled.BugReport, onClose = { me.isOpen = false }) {
        val locked = if (busy) "Working…" else null
        buildList<XmbRow> {
            add(XmbRow.Text(key = "title", label = "What went wrong?", icon = Icons.Filled.Edit, value = title, disabledReason = locked) { title = it })
            add(
                XmbRow.Text(
                    key = "desc", label = "Any detail that helps (optional)", icon = Icons.Filled.Description,
                    value = description, disabledReason = locked,
                ) { description = it }
            )
            add(
                XmbRow.Toggle(
                    key = "app", label = "Also app logcat and crash reports", icon = Icons.Filled.Tune,
                    value = includeApp, disabledReason = locked,
                ) { includeApp = it }
            )
            add(XmbRow.External(key = "go", label = if (busy) "Working…" else "Continue on GitHub", icon = Icons.Filled.OpenInNew) { submit() })
            add(XmbRow.Header("attach", "Will be attached"))
            willAttach.forEachIndexed { i, f -> add(XmbRow.Info("a$i", "• $f", Icons.Filled.InsertDriveFile)) }
            addAll(
                XmbTools.para(
                    "note",
                    "Saved as a zip in Downloads. E-mail addresses, auth tokens and your Steam account name are " +
                        "stripped; file paths are kept so they stay useful for debugging, so glance over them if a " +
                        "folder name identifies you. GitHub can't receive a file from a link, so attach the zip " +
                        "with 📎 once the form opens.",
                    Icons.Filled.Info, ctx,
                )
            )
        }
    }
}
