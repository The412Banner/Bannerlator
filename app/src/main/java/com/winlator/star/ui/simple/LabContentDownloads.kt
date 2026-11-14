package com.winlator.star.ui.simple

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.contents.Downloader
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.startContentDownload
import com.winlator.star.ui.screens.COMMUNITY_KEY
import com.winlator.star.ui.screens.COMMUNITY_PREFS
import com.winlator.star.ui.screens.CommunityGroup
import com.winlator.star.ui.screens.OFFICIAL_KEY
import com.winlator.star.ui.screens.fetchCommunityGroups
import com.winlator.star.ui.screens.isInUse
import com.winlator.star.ui.screens.loadProfiles
import com.winlator.star.ui.screens.normVer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Lab's download pickers for the editors' download actions (Wine / Proton, DXVK, VKD3D, D7VK, Box64 / WOWBox64,
// FEXCore, Box64EC, VEGAS builds, stock VEGAS configs, Turnip for Wayland). Same signatures and steps as the
// Classic sheets (ContentDownloadSheet, VegasDownloadSheet, StockConfigDownloadSheet, AdrenoDriverDownloadSheet).

/**
 * Classic's component download sheet as a Lab list: the Official catalog and, with "Include community repos" on,
 * the Contents screen's component sources (each one a switch), the installed versions (A: info and remove), a
 * download's progress on its line with Cancel download, and Install from a file. After an install or a remove
 * [onContentChanged] runs, as in Classic. [inUseKey] marks the version in use.
 */
@Composable
internal fun LabContentDownloadSheet(
    contentTypes: List<ContentProfile.ContentType>,
    onDismiss: () -> Unit,
    onContentChanged: () -> Unit,
    inUseKey: String? = null,
) {
    val context = LocalContext.current
    val cm = remember { ContentsManager(context) }
    var profiles by remember { mutableStateOf<List<ContentProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selectedType by remember(contentTypes) { mutableStateOf(contentTypes.first()) }
    val multiType = contentTypes.size > 1

    val srcPrefs = remember { context.getSharedPreferences(COMMUNITY_PREFS, Context.MODE_PRIVATE) }
    var includeCommunity by remember { mutableStateOf(srcPrefs.getBoolean(COMMUNITY_KEY, false)) }
    var communityGroups by remember { mutableStateOf<List<CommunityGroup>>(emptyList()) }
    var communityLoading by remember { mutableStateOf(false) }
    var activeSources by remember { mutableStateOf(setOf(OFFICIAL_KEY)) }
    var selectAllOnLoad by remember { mutableStateOf(includeCommunity) }

    LaunchedEffect(contentTypes, refreshKey) {
        val json = withContext(Dispatchers.IO) { Downloader.downloadString(ContentsManager.REMOTE_PROFILES) }
        if (json != null) cm.setRemoteProfiles(json) else cm.syncContents()
        loadProfiles(cm, contentTypes) { profiles = it }
        loading = false
    }
    LaunchedEffect(includeCommunity, selectedType, refreshKey) {
        if (!includeCommunity) {
            communityGroups = emptyList(); communityLoading = false
            return@LaunchedEffect
        }
        communityLoading = true
        val groups = withContext(Dispatchers.IO) { fetchCommunityGroups(context, selectedType) }
        communityGroups = groups
        if (selectAllOnLoad) {
            activeSources = setOf(OFFICIAL_KEY) + groups.map { it.name }
            selectAllOnLoad = false
        }
        communityLoading = false
    }

    // The download started here (or one still running when the picker opened), as Classic's progress popup follows it.
    val states by ContentDownloadRegistry.states.collectAsState()
    var tracked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { tracked = ContentDownloadRegistry.states.value.entries.firstOrNull { !it.value.terminal }?.key }
    val trackedState = tracked?.let { states[it] }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(trackedState?.phase) {
        val st = trackedState ?: return@LaunchedEffect
        when {
            st.phase == ContentDownloadPhase.DONE -> {
                loadProfiles(cm, contentTypes) { profiles = it }
                refreshKey++
                onContentChanged()
                toast(context, L("Installed %1\$s", st.verName ?: st.title))
                tracked = null
            }
            st.phase == ContentDownloadPhase.ERROR -> {
                if (!st.cancelled) failure = st.error ?: L("Install failed.")
                tracked = null
            }
        }
    }

    val fileInstall = rememberLabFileInstall {
        loadProfiles(cm, contentTypes) { profiles = it }
        refreshKey++
        onContentChanged()
    }
    var info by remember { mutableStateOf<ContentProfile?>(null) }

    val official = profiles.filter { !multiType || it.type == selectedType }.sortedByDescending { p -> if (p.remoteUrl == null) 1 else 0 }
    val installedNorm = official.filter { it.remoteUrl == null }.map { normVer(it.type.toString(), it.verName) }.toSet()

    fun versionLine(group: String, index: Int, profile: ProfileRow): DlLine {
        val p = profile.profile
        val key = ContentsManager.getEntryName(p)
        val st = states[key]
        val busy = st != null && !st.terminal
        val local = p.remoteUrl == null
        val inUse = isInUse(p, inUseKey)
        val vc = if (local && p.verCode > 0) " · v${p.verCode}" else ""
        val sub = when {
            busy -> if (st!!.phase == ContentDownloadPhase.INSTALLING) L("Installing") else L("Downloading")
            inUse -> L("In use") + vc
            local -> L("Installed") + vc
            profile.communityInstalled -> L("Installed")
            !p.desc.isNullOrEmpty() -> p.desc
            else -> null
        }
        val download: () -> Unit = {
            if (tracked != null && states[tracked!!]?.terminal == false) toast(context, L("Wait until the current download is done"))
            else {
                startContentDownload(context.applicationContext, p)
                tracked = key
            }
        }
        return DlLine(
            key = "$group|$index|${p.remoteUrl ?: key}",
            title = p.verName ?: key,
            sub = sub,
            trailing = when {
                busy -> "${(st!!.fraction * 100).toInt()}%"
                st != null && st.phase == ContentDownloadPhase.ERROR && !st.cancelled -> L("Failed")
                else -> null
            },
            progress = if (busy) st!!.fraction else null,
            onA = if (!local && !busy && !profile.communityInstalled) download else null,
            actions = when {
                busy -> listOf(MenuAction(L("Cancel download"), { ContentDownloadRegistry.requestCancel(key) }, danger = true))
                local -> listOf(
                    MenuAction(L("Info"), { info = p }),
                    MenuAction(L("Remove"), {
                        cm.removeContent(p)
                        cm.syncContents()
                        loadProfiles(cm, contentTypes) { profiles = it }
                        onContentChanged()
                    }, danger = true, confirm = L("Remove \"%1\$s\"?", p.verName)),
                )
                else -> emptyList()
            },
        )
    }

    val lines = buildList<DlLine> {
        if (multiType) {
            add(DlLine("__type__", L("Type"), trailing = selectedType.toString(), onA = {
                selectedType = contentTypes[(contentTypes.indexOf(selectedType) + 1) % contentTypes.size]
            }, actions = contentTypes.map { t -> MenuAction(if (t == selectedType) "$t  ✓" else t.toString(), { selectedType = t }) }))
        }
        add(
            DlLine(
                "__community__", L("Include community repos"), sub = L("Add versions from the Contents screen's sources"), toggle = includeCommunity,
                onA = {
                    val on = !includeCommunity
                    includeCommunity = on
                    srcPrefs.edit().putBoolean(COMMUNITY_KEY, on).apply()
                    if (on) selectAllOnLoad = true else activeSources = setOf(OFFICIAL_KEY)
                },
            ),
        )
        if (includeCommunity) {
            fun chip(k: String) {
                activeSources = activeSources.toMutableSet().apply {
                    if (contains(k)) remove(k) else add(k)
                    if (isEmpty()) add(OFFICIAL_KEY)
                }
            }
            add(DlLine("__src__$OFFICIAL_KEY", L("Official"), toggle = OFFICIAL_KEY in activeSources, onA = { chip(OFFICIAL_KEY) }))
            communityGroups.forEach { g -> add(DlLine("__src__${g.name}", g.name, toggle = g.name in activeSources, onA = { chip(g.name) })) }
        }
        add(fileInstall.line())
        trackedState?.takeIf { !it.terminal }?.let { st ->
            add(DlLine("__cancel__", L("Cancel download"), sub = st.verName ?: st.title, accent = true, onA = { ContentDownloadRegistry.requestCancel(st.key) }))
        }
        if (loading) add(DlLine("__loading__", L("Loading the list…"), enabled = false))
        else {
            val sections = buildList<Triple<String, String, List<ProfileRow>>> {
                if (OFFICIAL_KEY in activeSources && official.isNotEmpty()) add(Triple(L("Official"), L("Official"), official.map { ProfileRow(it, false) }))
                if (includeCommunity) communityGroups.forEach { g ->
                    if (g.name in activeSources) add(Triple(g.name, L("Community"), g.profiles.map { ProfileRow(it, normVer(it.type.toString(), it.verName) in installedNorm) }))
                }
            }
            sections.forEachIndexed { si, (label, tag, rows) ->
                add(DlLine("__h$si", "$label · $tag", trailing = rows.size.toString(), header = true))
                rows.forEachIndexed { ri, r -> add(versionLine("s$si", ri, r)) }
            }
            if (sections.sumOf { it.third.size } == 0 && !communityLoading) add(DlLine("__none__", L("No content available."), enabled = false))
        }
        if (communityLoading) add(DlLine("__cloading__", L("Loading community repos…"), enabled = false))
    }
    val total = official.size.takeIf { OFFICIAL_KEY in activeSources } ?: 0
    val shown = total + if (includeCommunity) communityGroups.filter { it.name in activeSources }.sumOf { it.profiles.size } else 0
    val nSrc = (if (OFFICIAL_KEY in activeSources && official.isNotEmpty()) 1 else 0) +
        (if (includeCommunity) communityGroups.count { it.name in activeSources } else 0)
    LabDownloadList(
        title = if (multiType) L("Compatibility layer") else L("%1\$s downloads", contentTypes.first().toString()),
        message = if (loading) null else listOfNotNull(
            inUseKey?.takeIf { it.isNotEmpty() && selectedType in contentTypes }?.let { L("In use: %1\$s", it) },
            L("%1\$s versions from %2\$s sources", shown, nSrc) + if (!includeCommunity) " · " + L("community repos off") else "",
        ).joinToString("\n"),
        lines = lines,
        onDismiss = onDismiss,
    )
    info?.let { p -> LabNote(L("Content info"), contentInfoText(p)) { info = null } }
    failure?.let { msg -> LabNote(L("Download failed"), msg) { failure = null } }
}

/** A version line's profile, and whether a community duplicate of it is already installed. */
private class ProfileRow(val profile: ContentProfile, val communityInstalled: Boolean)
