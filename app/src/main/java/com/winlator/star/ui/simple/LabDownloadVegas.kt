package com.winlator.star.ui.simple

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.contentdialog.VegasTierPresets
import com.winlator.star.contents.ContentsManager
import com.winlator.star.ui.screens.VegasRelease
import com.winlator.star.ui.screens.VegasStockConfigFetcher
import com.winlator.star.ui.screens.downloadWcp
import com.winlator.star.ui.screens.fetchVegasReleases
import com.winlator.star.ui.screens.installWcp
import com.winlator.star.ui.screens.parkVegasReleaseConfig
import com.winlator.star.ui.screens.recordVegasReleaseTails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Classic's VEGAS Downloads as a Lab list: the releases of isygold/vegas-releases, A downloads and installs one (its
 * stock config is parked beside it, as in Classic), X shows its release notes. The list stays open until the
 * download and install are done; a good install runs [onContentChanged] and closes, as in Classic.
 */
@Composable
internal fun LabVegasDownloadSheet(onDismiss: () -> Unit, onContentChanged: () -> Unit) {
    val context = LocalContext.current
    val cm = remember { ContentsManager(context) }
    val scope = rememberCoroutineScope()
    var releases by remember { mutableStateOf<List<VegasRelease>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var downloadingTag by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var installing by remember { mutableStateOf(false) }
    // The release being downloaded or installed.
    var activeTag by remember { mutableStateOf<String?>(null) }
    var notesFor by remember { mutableStateOf<VegasRelease?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(retryKey) {
        loading = true
        errorMsg = null
        releases = emptyList()
        val (list, err) = withContext(Dispatchers.IO) { fetchVegasReleases() }
        releases = list
        errorMsg = err
        loading = false
    }

    fun notesOf(r: VegasRelease): String? = r.body ?: VegasTierPresets.BUNDLED_NOTES[r.tagName.removePrefix("vegas-")]?.joinToString("\n")

    fun download(r: VegasRelease) {
        val url = r.wcpAssetUrl ?: return
        if (downloadingTag != null || installing) return
        downloadingTag = r.tagName
        activeTag = r.tagName
        progress = 0f
        scope.launch {
            val uri = withContext(Dispatchers.IO) { downloadWcp(context, url, r.tagName) { p -> progress = p } }
            downloadingTag = null
            if (uri == null) {
                activeTag = null
                errorMsg = L("Download failed.")
                return@launch
            }
            installing = true
            installWcp(context, cm, uri) { ok, profile ->
                if (!ok) {
                    installing = false
                    activeTag = null
                    errorMsg = L("Install failed.")
                    return@installWcp
                }
                cm.syncContents()
                scope.launch {
                    withContext(Dispatchers.IO) { parkVegasReleaseConfig(context, r, profile?.verName) }
                    installing = false
                    activeTag = null
                    onContentChanged()
                    onDismiss()
                }
            }
        }
    }

    val busy = downloadingTag != null || installing
    val lines = buildList<DlLine> {
        when {
            loading -> add(DlLine("__loading__", L("Loading the list…"), enabled = false))
            releases.isEmpty() -> {
                add(DlLine("__none__", if (errorMsg != null) L("Could not load releases.") else L("No releases available."), enabled = false))
                add(DlLine("__retry__", L("Retry"), accent = true, onA = { errorMsg = null; retryKey++ }))
            }
            else -> releases.forEach { r ->
                val notes = notesOf(r)
                val mine = activeTag == r.tagName
                add(
                    DlLine(
                        key = r.tagName,
                        title = r.displayName,
                        sub = when {
                            mine && installing -> L("Installing VEGAS…")
                            mine -> L("Downloading")
                            notes != null -> r.tagName + " · " + notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
                            else -> r.tagName
                        },
                        trailing = if (mine && !installing) "${(progress * 100).toInt()}%" else null,
                        progress = if (mine && !installing) progress else null,
                        enabled = !busy || mine,
                        onA = { download(r) },
                        actions = buildList<MenuAction> {
                            add(MenuAction(L("Download and install"), { download(r) }))
                            if (notes != null) add(MenuAction(L("What's new"), { notesFor = r }))
                        },
                    ),
                )
            }
        }
    }
    LabDownloadList(
        title = L("VEGAS downloads"),
        message = when {
            installing -> L("Installing VEGAS…")
            downloadingTag != null -> L("Downloading %1\$s…", downloadingTag)
            else -> null
        },
        lines = lines,
        onDismiss = onDismiss,
        blockDismiss = busy,
    )
    // A list was loaded but something went wrong (a download or an install): the message with Retry, as Classic.
    errorMsg?.takeIf { !loading }?.let { msg ->
        SimpleActionSheet(
            title = L("Download failed"),
            message = msg,
            actions = listOf(MenuAction(L("OK"), { errorMsg = null }), MenuAction(L("Retry"), { errorMsg = null; retryKey++ })),
            onDismiss = { errorMsg = null },
        ) { it.run() }
    }
    notesFor?.let { r ->
        val notes = notesOf(r)
        if (notes != null) LabNote(L("What's new — %1\$s", r.displayName), notes) { notesFor = null }
    }
}

/**
 * Classic's "Stock configs" sheet as a Lab list: every VEGAS release with its config asset (or none); A fetches and
 * parks one release's config so it shows under Stock config. The versions seen are remembered as in Classic.
 */
@Composable
internal fun LabStockConfigDownloadSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var rows by remember { mutableStateOf(listOf<VegasStockConfigFetcher.ReleaseConf>()) }
    var parkedTag by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(retryKey) {
        loading = true
        rows = withContext(Dispatchers.IO) { VegasStockConfigFetcher.listReleaseConfigs() }
        loading = false
        recordVegasReleaseTails(context, rows)
    }
    fun park(rel: VegasStockConfigFetcher.ReleaseConf) {
        if (parkedTag != null) return
        parkedTag = rel.tag
        scope.launch {
            val res = withContext(Dispatchers.IO) { VegasStockConfigFetcher.park(context, rel) }
            parkedTag = null
            when (res) {
                is VegasStockConfigFetcher.ParkResult.Ok ->
                    android.widget.Toast.makeText(context, L("Parked as %1\$s.conf — now select \"%2\$s\" under Stock config", res.parkedAs, res.parkedAs), android.widget.Toast.LENGTH_LONG).show()
                is VegasStockConfigFetcher.ParkResult.Fail ->
                    android.widget.Toast.makeText(context, L("Failed: %1\$s", res.reason), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val lines = buildList<DlLine> {
        when {
            loading -> add(DlLine("__loading__", L("Loading the list…"), enabled = false))
            rows.isEmpty() -> {
                add(DlLine("__none__", L("Couldn't reach the releases feed — check connection and retry. (GitHub limit 60/h)"), enabled = false))
                add(DlLine("__retry__", L("Retry"), accent = true, onA = { retryKey++ }))
            }
            else -> rows.forEachIndexed { i, rel ->
                val hasConf = rel.confUrl != null
                val busy = parkedTag == rel.tag
                add(
                    DlLine(
                        key = "r$i-${rel.tag}",
                        title = rel.tag.ifEmpty { L("(untagged)") },
                        sub = buildString {
                            append(rel.confName ?: L("no config asset"))
                            if (rel.date.isNotEmpty()) append("  ·  ${rel.date}")
                        },
                        trailing = if (busy) "…" else null,
                        enabled = hasConf,
                        onA = { if (!busy) park(rel) },
                    ),
                )
            }
        }
    }
    LabDownloadList(
        title = L("Stock configs"),
        message = L("Tap a version to fetch its config. Builds without a shipped config show none."),
        lines = lines,
        onDismiss = onDismiss,
    )
}
