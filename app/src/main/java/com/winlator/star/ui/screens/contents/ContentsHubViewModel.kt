package com.winlator.star.ui.screens.contents

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the Contents hub: the source list, per-source catalogs, cross-source search, the
 * keep-raw toggle, the My Files library, and installed/saved status for badges.
 */
class ContentsHubViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RemoteSourceRepository(app)
    val library = ComponentLibrary(app)

    data class CatalogItem(
        val type: String,
        val displayName: String,
        val versionName: String,
        val downloadUrl: String,
        val sourceName: String,
        val publishedAt: String?,
        val sizeBytes: Long?,
        val isDriver: Boolean = ContentsTypes.isDriver(type),
    ) {
        val fileName: String
            get() = downloadUrl.substringAfterLast('/').substringBefore('?')
                .ifBlank { "${versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")}.wcp" }
    }

    private val _sources = MutableStateFlow<List<RemoteSourceRepository.RemoteSource>>(emptyList())
    val sources: StateFlow<List<RemoteSourceRepository.RemoteSource>> = _sources.asStateFlow()

    private val _keepRaw = MutableStateFlow(library.keepRaw())
    val keepRaw: StateFlow<Boolean> = _keepRaw.asStateFlow()

    private val _selected = MutableStateFlow<RemoteSourceRepository.RemoteSource?>(null)
    val selected: StateFlow<RemoteSourceRepository.RemoteSource?> = _selected.asStateFlow()

    private val _detailTypes = MutableStateFlow<List<String>>(emptyList())
    val detailTypes: StateFlow<List<String>> = _detailTypes.asStateFlow()

    private val _detailItems = MutableStateFlow<List<CatalogItem>>(emptyList())
    val detailItems: StateFlow<List<CatalogItem>> = _detailItems.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow<List<CatalogItem>>(emptyList())
    val searchResults: StateFlow<List<CatalogItem>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _installedKeys = MutableStateFlow<Set<String>>(emptySet())
    val installedKeys: StateFlow<Set<String>> = _installedKeys.asStateFlow()

    private val _installedDriverNorms = MutableStateFlow<Set<String>>(emptySet())

    private val _savedKeys = MutableStateFlow(library.savedKeys())
    val savedKeys: StateFlow<Set<String>> = _savedKeys.asStateFlow()

    private val _savedFolders = MutableStateFlow<Map<String, List<ComponentLibrary.SavedFile>>>(emptyMap())
    val savedFolders: StateFlow<Map<String, List<ComponentLibrary.SavedFile>>> = _savedFolders.asStateFlow()

    private val _baseDisplay = MutableStateFlow(library.baseDisplay())
    val baseDisplay: StateFlow<String> = _baseDisplay.asStateFlow()

    init {
        reloadSources()
        refreshStatus()
        refreshFolders()
    }

    // ── Sources ─────────────────────────────────────────────────────────────────
    fun reloadSources() {
        _sources.value = repo.getAllSources()
    }

    fun refreshBase() {
        _baseDisplay.value = library.baseDisplay()
    }

    // ── Keep-raw ────────────────────────────────────────────────────────────────
    fun setKeepRaw(value: Boolean) {
        library.setKeepRaw(value)
        _keepRaw.value = value
    }

    // ── Selection + per-source catalog ──────────────────────────────────────────
    fun selectSource(source: RemoteSourceRepository.RemoteSource?) {
        _selected.value = source
        if (source == null) {
            _detailTypes.value = emptyList()
            _detailItems.value = emptyList()
            return
        }
        loadDetail(source)
    }

    fun refreshSelected() {
        RemoteSourceRepository.clearCache()
        _selected.value?.let { loadDetail(it) }
    }

    private fun loadDetail(source: RemoteSourceRepository.RemoteSource) {
        _detailLoading.value = true
        _detailItems.value = emptyList()
        _detailTypes.value = emptyList()
        viewModelScope.launch {
            val types = withContext(Dispatchers.IO) { resolveTypes(source) }
            _detailTypes.value = types
            val items = withContext(Dispatchers.IO) {
                types.flatMap { type ->
                    runCatching { repo.fetchFromSource(source, type) }.getOrDefault(emptyList())
                        .map { it.toCatalog(type) }
                }
            }
            _detailItems.value = items
            _detailLoading.value = false
            refreshStatus()
        }
    }

    /** Display-canonical, de-duplicated type list for a source (GPU keywords collapsed). */
    private suspend fun resolveTypes(source: RemoteSourceRepository.RemoteSource): List<String> {
        val raw = if (source.supportedTypes.isNotEmpty()) source.supportedTypes
        else runCatching { repo.discoverTypes(source) }.getOrDefault(ContentsTypes.ALL)
        val seen = LinkedHashSet<String>()
        raw.forEach { seen.add(canonicalType(it)) }
        return seen.toList()
    }

    private fun canonicalType(key: String): String {
        if (ContentsTypes.isDriver(key) || RemoteSourceRepository.GPU_DRIVER_KEYWORDS.any { it.equals(key, true) }) {
            return ContentsTypes.GPU_DRIVERS
        }
        if (key.equals("fex", true) || key.equals("fexcore", true)) return "FEXCore"
        return ContentProfile.ContentType.getTypeByName(key)?.toString()
            ?: key.replaceFirstChar { it.uppercaseChar() }
    }

    private fun RemoteSourceRepository.RemoteItem.toCatalog(type: String) = CatalogItem(
        type = type,
        displayName = displayName,
        versionName = versionName,
        downloadUrl = downloadUrl,
        sourceName = sourceName,
        publishedAt = publishedAt,
        sizeBytes = sizeBytes,
    )

    // ── Cross-source search ─────────────────────────────────────────────────────
    fun setQuery(q: String) {
        _query.value = q
        if (q.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        _searching.value = true
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                if (!RemoteSourceRepository.hasCache()) {
                    runCatching { repo.refreshAllCache(_sources.value, ContentsTypes.ALL) }
                }
                RemoteSourceRepository.searchCache(q).map { it.item.toCatalog(canonicalType(it.componentType)) }
            }
            _searchResults.value = results
            _searching.value = false
            refreshStatus()
        }
    }

    // ── Status (installed / saved) ──────────────────────────────────────────────
    fun refreshStatus() {
        _savedKeys.value = library.savedKeys()
        viewModelScope.launch {
            val (keys, driverNorms) = withContext(Dispatchers.IO) { computeInstalled() }
            _installedKeys.value = keys
            _installedDriverNorms.value = driverNorms
        }
    }

    private fun computeInstalled(): Pair<Set<String>, Set<String>> {
        val ctx = getApplication<Application>()
        val keys = mutableSetOf<String>()
        val driverNorms = mutableSetOf<String>()
        runCatching {
            val cm = ContentsManager(ctx)
            cm.syncContents()
            ContentProfile.ContentType.values().forEach { ct ->
                cm.getProfiles(ct)?.forEach { p ->
                    if (p.remoteUrl == null && p.verName != null) {
                        keys.add(ContentsTypes.normalize(ct.toString()) + "::" + ContentsTypes.normalize(p.verName))
                    }
                }
            }
        }
        runCatching {
            AdrenotoolsManager(ctx).enumarateInstalledDrivers().forEach { name ->
                val n = ContentsTypes.normalize(name)
                driverNorms.add(n)
                keys.add(ContentsTypes.normalize(ContentsTypes.GPU_DRIVERS) + "::" + n)
            }
        }
        return keys to driverNorms
    }

    /** True if the item's version is already installed (components: exact key; drivers: fuzzy contains). */
    fun isInstalled(item: CatalogItem): Boolean {
        val key = ContentsTypes.normalize(item.type) + "::" + ContentsTypes.normalize(item.versionName)
        if (key in _installedKeys.value) return true
        if (item.isDriver) {
            val v = ContentsTypes.normalize(item.versionName)
            val d = ContentsTypes.normalize(item.displayName)
            return _installedDriverNorms.value.any { it == v || it == d || (v.isNotEmpty() && it.contains(v)) }
        }
        return false
    }

    fun isSaved(item: CatalogItem): Boolean =
        library.keyFor(item.type, item.fileName) in _savedKeys.value

    // ── My Files ────────────────────────────────────────────────────────────────
    fun refreshFolders() {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) { library.listSaved() }
            _savedFolders.value = map
            _savedKeys.value = library.savedKeys()
            _baseDisplay.value = library.baseDisplay()
        }
    }

    fun deleteSaved(file: ComponentLibrary.SavedFile) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { library.delete(file) }
            refreshFolders()
        }
    }

    // ── Source management ───────────────────────────────────────────────────────
    fun addSource(source: RemoteSourceRepository.RemoteSource) {
        repo.addCustomSource(source)
        reloadSources()
    }

    fun removeSource(source: RemoteSourceRepository.RemoteSource) {
        repo.removeSource(source)
        if (_selected.value?.name == source.name) selectSource(null)
        reloadSources()
    }

    fun restoreDefaultSources() {
        repo.restoreDefaultSources()
        reloadSources()
    }

    fun exportRepoListJson(): String = repo.exportRepoListJson()

    fun importRepoListJson(json: String, merge: Boolean): Boolean = runCatching {
        repo.applyRepoListImport(repo.parseRepoListJson(json), merge)
        reloadSources()
        true
    }.getOrDefault(false)

    fun browseUrl(source: RemoteSourceRepository.RemoteSource): String = repo.getBrowseUrl(source)
}
