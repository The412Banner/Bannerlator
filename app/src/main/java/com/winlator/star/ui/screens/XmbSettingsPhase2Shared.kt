package com.winlator.star.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.store.download.startContentDownload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Phase 2 of the nested XMB — shared helpers for the remaining game-settings sections (Win Components,
// Env Vars, Advanced) and the sub-editors (driver / DX wrapper config, Performance, Player slots).
// Same rules as Phase 1: read with the pop-up editor's fallbacks, write with its save() encoding.
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** The shortcut's extra, else [def] (the container value); never null (Java getters may return null). */
internal fun Shortcut.p2Ex(key: String, def: String?): String = getExtra(key, def ?: "") ?: (def ?: "")

/** Write one extra (null clears it so the game re-inherits the container), persist, flash Saved, rebuild. */
internal fun p2Put(xmb: XmbScope, s: Shortcut, key: String, value: String?) {
    s.putExtra(key, value)
    s.saveData()
    xmb.saved()
    xmb.refresh()
}

/** Several extras written together (the editor persists them as one save). */
internal fun p2PutAll(xmb: XmbScope, s: Shortcut, values: List<Pair<String, String?>>) {
    for ((k, v) in values) s.putExtra(k, v)
    s.saveData()
    xmb.saved()
    xmb.refresh()
}

/** Choice options must be unique (they key the picker rows): repeated labels get " (2)", " (3)"…. */
internal fun p2Unique(labels: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return labels.map { l ->
        val n = (seen[l] ?: 0) + 1
        seen[l] = n
        if (n == 1) l else "$l ($n)"
    }
}

/** Coroutines tied to one open menu: pollers/probes started by a builder, cancelled in onClose. */
internal class P2Jobs(xmb: XmbScope) {
    private val job: Job = SupervisorJob(xmb.scope.coroutineContext[Job])
    val scope: CoroutineScope = CoroutineScope(xmb.scope.coroutineContext + job)
    fun cancel() { job.cancel() }
}

/** Run [block] on the main thread (progress callbacks arrive on IO threads). */
internal fun p2OnMain(xmb: XmbScope, block: () -> Unit) {
    xmb.scope.launch(Dispatchers.Main) { block() }
}

/**
 * "Download more versions" as an XMB column — the ContentDownloadSheet's official catalog for one
 * [type]: installed versions first, then the downloadable ones. A pick goes through the same
 * process-lifetime pipeline as the sheet ([startContentDownload]), so it keeps going if the menu
 * closes; while open the row shows live progress. [onInstalled] lets the parent re-read its list so
 * the new version becomes selectable. Community repositories are not offered here.
 */
internal fun p2ContentDownloadMenu(
    xmb: XmbScope,
    title: String,
    type: ContentProfile.ContentType,
    onInstalled: () -> Unit,
): XmbMenu {
    val ctx = xmb.context
    val jobs = P2Jobs(xmb)
    var loading by mutableStateOf(true)
    var failed by mutableStateOf(false)
    var profiles by mutableStateOf<List<ContentProfile>>(emptyList())
    var states by mutableStateOf<Map<String, ContentDownloadState>>(ContentDownloadRegistry.states.value)
    val started = HashSet<String>()

    fun load() {
        jobs.scope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    val cm = ContentsManager(ctx)
                    val json = Downloader.downloadString(ContentsManager.REMOTE_PROFILES)
                    if (json != null) cm.setRemoteProfiles(json) else cm.syncContents()
                    val all: List<ContentProfile> = cm.getProfiles(type) ?: emptyList()
                    all.distinctBy { ContentsManager.getEntryName(it) }
                }.getOrNull()
            }
            failed = list == null
            profiles = (list ?: emptyList()).sortedByDescending { if (it.remoteUrl == null) 1 else 0 }
            loading = false
        }
    }
    load()
    // The registry is a StateFlow; poll it while the column is open (cheap, and no flow plumbing).
    jobs.scope.launch {
        while (true) {
            val m = ContentDownloadRegistry.states.value
            if (m != states) {
                val done = started.filter { m[it]?.phase == ContentDownloadPhase.DONE }
                states = m
                if (done.isNotEmpty()) {
                    started.removeAll(done.toSet())
                    load()
                    onInstalled()
                }
            }
            delay(300)
        }
    }

    return XmbMenu(title, Icons.Filled.CloudDownload, onClose = { jobs.cancel() }) {
        val rows = mutableListOf<XmbRow>()
        when {
            loading -> rows += XmbRow.Info("loading", "Loading versions…", Icons.Filled.CloudDownload)
            profiles.isEmpty() -> rows += XmbRow.Info("none",
                if (failed) "Couldn't read the catalog — check your connection" else "No versions available", Icons.Filled.Info)
            else -> {
                val local = profiles.filter { it.remoteUrl == null }
                val remote = profiles.filter { it.remoteUrl != null }
                if (local.isNotEmpty()) rows += XmbRow.Header("hInstalled", "Installed")
                local.forEach { p ->
                    val key = ContentsManager.getEntryName(p)
                    rows += XmbRow.Info("i:$key", p.verName ?: key, Icons.Filled.CheckCircle, "Installed")
                }
                if (remote.isNotEmpty()) rows += XmbRow.Header("hAvailable", "Available")
                remote.forEach { p ->
                    val key = ContentsManager.getEntryName(p)
                    val st = states[key]
                    val busy = st != null && !st.terminal
                    val value = when {
                        st == null -> null
                        st.phase == ContentDownloadPhase.DOWNLOADING -> "Downloading ${(st.fraction * 100).toInt()}%"
                        st.phase == ContentDownloadPhase.INSTALLING -> "Installing ${(st.fraction * 100).toInt()}%"
                        st.phase == ContentDownloadPhase.DONE -> "Installed"
                        else -> if (st.cancelled) "Cancelled" else (st.error ?: "Failed")
                    }
                    rows += XmbRow.Action(
                        "r:$key", p.verName ?: key, Icons.Filled.Download,
                        subtitle = p.desc?.takeIf { it.isNotBlank() } ?: "Download and install",
                        value = value,
                        disabledReason = if (busy) "Already downloading" else null,
                    ) {
                        started.add(key)
                        startContentDownload(ctx.applicationContext, p)
                        states = ContentDownloadRegistry.states.value
                    }
                }
            }
        }
        rows
    }
}
