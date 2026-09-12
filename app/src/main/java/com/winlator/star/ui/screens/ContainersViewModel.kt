package com.winlator.star.ui.screens

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerLayerUpdater
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.WineInfo
import com.winlator.star.store.download.ContentDownloadPhase
import com.winlator.star.store.download.ContentDownloadRegistry
import com.winlator.star.store.download.ContentDownloadState
import com.winlator.star.store.download.startContentDownload
import com.winlator.star.ui.screens.contents.RemoteSourceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ContainersViewModel(app: Application) : AndroidViewModel(app) {

    private val _containers = MutableStateFlow<List<Container>>(emptyList())
    val containers: StateFlow<List<Container>> = _containers

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // One-shot user message (e.g. a failed duplicate). Screen shows it then calls messageShown().
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    // Per-container in-place layer update state (see ContainerLayerUpdater). layerUpdates maps a
    // container id to the entry name of a newer INSTALLED build of its layer line; layerSnapshots
    // to the snapshot a previous update left behind (= "Revert" is possible).
    private val _layerUpdates = MutableStateFlow<Map<Int, String>>(emptyMap())
    val layerUpdates: StateFlow<Map<Int, String>> = _layerUpdates

    // A newer build that exists only in the online catalog (needs a download first). Only filled for
    // containers WITHOUT an installed candidate — an installed newer layer always wins.
    private val _layerUpdateRemote = MutableStateFlow<Map<Int, ContainerLayerUpdater.CatalogCandidate>>(emptyMap())
    val layerUpdateRemote: StateFlow<Map<Int, ContainerLayerUpdater.CatalogCandidate>> = _layerUpdateRemote

    // Live state of the catalog download+install that precedes a remote layer update (null = none).
    // Mirrors the registry entry so the progress dialog survives the registry's linger-removal.
    private val _layerDownload = MutableStateFlow<ContentDownloadState?>(null)
    val layerDownload: StateFlow<ContentDownloadState?> = _layerDownload

    private val _layerSnapshots = MutableStateFlow<Map<Int, ContainerLayerUpdater.Snapshot>>(emptyMap())
    val layerSnapshots: StateFlow<Map<Int, ContainerLayerUpdater.Snapshot>> = _layerSnapshots
    private val _layerCurrentMissing = MutableStateFlow<Set<Int>>(emptySet())
    /** Containers whose CURRENT layer is no longer installed: an update cannot be reverted. */
    val layerCurrentMissing: StateFlow<Set<Int>> = _layerCurrentMissing

    // Blocking progress text while an update/revert runs (null = idle).
    private val _layerBusy = MutableStateFlow<String?>(null)
    val layerBusy: StateFlow<String?> = _layerBusy

    private var manager: ContainerManager = ContainerManager(app)
    private val layerUpdater = ContainerLayerUpdater(app)

    init {
        refresh()
    }

    fun refresh() {
        manager = ContainerManager(getApplication())
        val list = manager.getContainers().toList()
        _containers.value = list
        scanLayerUpdates(list)
    }

    // Cheap directory scan (installed layers only, no network) off the main thread, then a
    // best-effort catalog pass for the containers that got nothing from it.
    private fun scanLayerUpdates(list: List<Container>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contents = ContentsManager(getApplication()).apply { syncContents() }
            val updates = HashMap<Int, String>()
            val snapshots = HashMap<Int, ContainerLayerUpdater.Snapshot>()
            val currentMissing = HashSet<Int>()
            for (c in list) {
                layerUpdater.findNewerInstalled(contents, c)?.let { target ->
                    updates[c.id] = target
                    // The confirm dialog promises a revert; only true while the current layer's
                    // files are still on the device (an uninstalled old layer = update only).
                    val cur = contents.getProfileByEntryName(c.wineVersion)
                    if (cur == null || !ContentsManager.getInstallDir(getApplication(), cur).isDirectory) currentMissing.add(c.id)
                }
                layerUpdater.latestSnapshot(c)
                    ?.takeIf { it.oldEntry != c.wineVersion }
                    ?.let { snapshots[c.id] = it }
            }
            _layerUpdates.value = updates
            _layerSnapshots.value = snapshots
            _layerCurrentMissing.value = currentMissing

            // Catalog tier: containers on a contents layer with no installed candidate. Any failure
            // (offline, bad JSON) silently leaves the installed-only result in place.
            val remote = HashMap<Int, ContainerLayerUpdater.CatalogCandidate>()
            val wantCatalog = list.filter { it.id !in updates && !WineInfo.isMainWineVersion(it.wineVersion) }
            if (wantCatalog.isNotEmpty() && runCatching { loadCatalog(contents) }.getOrDefault(false)) {
                for (c in wantCatalog) {
                    runCatching { layerUpdater.findNewerInCatalog(contents, c) }.getOrNull()?.let { cand ->
                        remote[c.id] = cand.copy(sizeBytes = ContainerLayerUpdater.probeRemoteSize(cand.remoteUrl))
                    }
                }
            }
            _layerUpdateRemote.value = remote
        }
    }

    /**
     * Puts the Official catalog's rows on [contents] WITHOUT a fetch of our own when any earlier
     * fetch is still around: ContentsManager's last parse (component sheet / community installer),
     * then the Contents hub's per-type cache. Only if both are empty does it do one fetch with short
     * timeouts. Returns whether [contents] now carries remote rows.
     */
    private fun loadCatalog(contents: ContentsManager): Boolean {
        ContentsManager.getCachedRemoteProfiles()?.let { contents.setRemoteProfiles(it); return true }
        hubCachedCatalog()?.let { contents.setRemoteProfiles(it); return true }
        val json = fetchShort(ContentsManager.REMOTE_PROFILES) ?: return false
        contents.setRemoteProfiles(json)
        return true
    }

    /** The hub's cached Official Proton/Wine items bridged to catalog profiles; null when it has none. */
    private fun hubCachedCatalog(): List<ContentProfile>? {
        val out = ArrayList<ContentProfile>()
        var any = false
        for (ctype in listOf(ContentProfile.ContentType.CONTENT_TYPE_PROTON, ContentProfile.ContentType.CONTENT_TYPE_WINE)) {
            val items = RemoteSourceRepository.getFromCache(RemoteSourceRepository.OFFICIAL_SOURCE_NAME, ctype.toString()) ?: continue
            any = true
            for (item in items) {
                val code = item.verCode ?: continue
                out += ContentProfile().apply {
                    type = ctype
                    verName = item.versionName
                    verCode = code
                    remoteUrl = item.downloadUrl
                    versionName = item.profileVersionName
                }
            }
        }
        return if (any) out else null
    }

    /** One GET with short timeouts (the list must never wait long on the network); null on any error. */
    private fun fetchShort(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    } catch (e: Exception) {
        null
    }

    fun updateLayer(container: Container, targetEntry: String) {
        runLayerJob("Updating layer to ${ContainerLayerUpdater.codeLabel(targetEntry)}…") { contents ->
            layerUpdater.update(contents, container, targetEntry)
        }
    }

    /**
     * Catalog-only candidate: download + install the layer through the normal contents pipeline
     * ([startContentDownload] — process-lifetime, FGS-bracketed, progress in [ContentDownloadRegistry]),
     * then run the ordinary [updateLayer] against the entry that landed on disk. A failed or cancelled
     * download leaves its dialog up with the error and never touches the container.
     */
    fun downloadAndUpdateLayer(container: Container, candidate: ContainerLayerUpdater.CatalogCandidate) {
        if (_layerBusy.value != null || _layerDownload.value != null) return
        val app = getApplication<Application>()
        val key = ContentsManager.getEntryName(candidate.profile)
        startContentDownload(app, candidate.profile)
        val initial = ContentDownloadRegistry.get(key)
        if (initial == null) {
            _message.value = "Couldn't start the layer download"
            return
        }
        _layerDownload.value = initial
        viewModelScope.launch {
            val final = ContentDownloadRegistry.states
                .map { it[key] }
                .onEach { st -> if (st != null) _layerDownload.value = st }
                .first { it == null || it.terminal }
            if (final?.phase != ContentDownloadPhase.DONE) {
                if (final == null) {
                    _layerDownload.value = null
                    _message.value = "Layer download failed"
                }
                return@launch
            }
            ContentDownloadRegistry.remove(key)
            _layerDownload.value = null
            // The wcp's own profile.json decides the entry name; refuse to update if what landed is
            // not the line/build the catalog promised (never guess from a label).
            val landed = withContext(Dispatchers.IO) {
                ContentsManager(app).apply { syncContents() }.getProfileByEntryName(candidate.entryName) != null
            }
            if (!landed) {
                _message.value = "Downloaded layer is not ${candidate.entryName} — container left unchanged"
                refresh()
                return@launch
            }
            updateLayer(container, candidate.entryName)
        }
    }

    /** Best-effort cancel of the in-flight layer download (shared registry path). */
    fun cancelLayerDownload() {
        _layerDownload.value?.let { ContentDownloadRegistry.requestCancel(it.key) }
    }

    /** The download dialog's terminal Close: drop the registry entry and the mirrored state. */
    fun layerDownloadDismissed() {
        _layerDownload.value?.let { ContentDownloadRegistry.remove(it.key) }
        _layerDownload.value = null
    }

    fun revertLayer(container: Container, snapshot: ContainerLayerUpdater.Snapshot) {
        runLayerJob("Reverting layer to ${ContainerLayerUpdater.codeLabel(snapshot.oldEntry)}…") { contents ->
            layerUpdater.revert(contents, container, snapshot)
        }
    }

    private fun runLayerJob(busyText: String, job: (ContentsManager) -> Result<String>) {
        if (_layerBusy.value != null) return
        _layerBusy.value = busyText
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                job(ContentsManager(getApplication()).apply { syncContents() })
            }
            _layerBusy.value = null
            _message.value = result.getOrElse { it.message ?: "Layer update failed" }
            refresh()
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun duplicate(container: Container, onDone: () -> Unit) {
        _isLoading.value = true
        // duplicateContainerAsync posts its callback (with the new Container, or null on
        // hard failure) on the main Handler internally.
        manager.duplicateContainerAsync(container) { result ->
            _isLoading.value = false
            refresh()
            _message.value = if (result == null) "Couldn't duplicate container" else "Container duplicated"
            onDone()
        }
    }

    fun exportContainer(container: Container, onDone: (exportPath: String?) -> Unit) {
        _isLoading.value = true
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/Containers"
        )
        manager.exportContainer(container) {
            _isLoading.value = false
            val path = File(exportDir, container.getRootDir().name)
            onDone(if (path.exists()) path.absolutePath else null)
        }
    }

    fun importContainer(dir: File, onDone: () -> Unit) {
        _isLoading.value = true
        manager.importContainer(dir) {
            _isLoading.value = false
            refresh()
            onDone()
        }
    }

    fun availableBackups(): List<File> {
        val backupDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/Containers"
        )
        return backupDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    }

    /** Installed games (shortcuts) belonging to [container] — the source for the per-game backup picker. */
    fun shortcutsFor(container: Container): List<Shortcut> =
        manager.loadShortcuts().filter { it.container.id == container.id }

    fun remove(container: Container, context: Context, onDone: () -> Unit) {
        // Disable any home-screen shortcuts pinned for this container before removing it
        manager.loadShortcuts()
            .filter { it.container == container }
            .forEach { ShortcutsViewModel.disableOnScreen(context, it) }

        _isLoading.value = true
        // removeContainerAsync posts its callback on the main Handler internally
        manager.removeContainerAsync(container) {
            _isLoading.value = false
            refresh()
            onDone()
        }
    }
}
