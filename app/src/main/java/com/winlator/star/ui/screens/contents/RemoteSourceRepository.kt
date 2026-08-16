package com.winlator.star.ui.screens.contents

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class RemoteSourceRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("remote_sources_prefs", Context.MODE_PRIVATE)

    companion object {
        // In-memory cache: "sourceName::componentType" → items
        private val cache = ConcurrentHashMap<String, List<RemoteItem>>()

        fun clearCache() = cache.clear()
        fun hasCache(): Boolean = cache.isNotEmpty()
        fun getFromCache(sourceName: String, componentType: String): List<RemoteItem>? =
            cache["$sourceName::$componentType"]
        fun putToCache(sourceName: String, componentType: String, items: List<RemoteItem>) {
            cache["$sourceName::$componentType"] = items
        }

        // In-memory set of downloaded file keys for fast indicator lookups
        private val downloadedKeys = ConcurrentHashMap.newKeySet<String>()

        fun isDownloaded(sourceName: String, componentType: String, fileName: String): Boolean =
            downloadedKeys.contains("$sourceName::$componentType::$fileName")

        private fun markDownloaded(sourceName: String, componentType: String, fileName: String) {
            downloadedKeys.add("$sourceName::$componentType::$fileName")
        }
        private fun unmarkDownloaded(sourceName: String, componentType: String, fileName: String) {
            downloadedKeys.remove("$sourceName::$componentType::$fileName")
        }

        const val GPU_DRIVER_TYPE = "GPU Drivers"
        val GPU_DRIVER_KEYWORDS = listOf("turnip", "adreno", "qualcomm", "mesa")

        /** Strip filesystem-unsafe characters from a folder name segment. */
        fun sanitizeFolderName(name: String): String =
            name.replace(Regex("""[/\\:*?"<>|]"""), "_").trim()

        fun formatFileSize(bytes: Long): String = when {
            bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824f)
            bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576f)
            bytes >= 1_024L         -> "%.1f KB".format(bytes / 1_024f)
            else                    -> "$bytes B"
        }

        /** Search all cached items across every source and type. */
        fun searchCache(query: String): List<SearchResult> {
            // Match EVERY whitespace-delimited token against a combined haystack (name + version +
            // type + source), so multi-word queries like "dxvk 2" work even though the words straddle
            // separate fields. Single-substring matching failed there — name/version are distinct fields.
            val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return emptyList()
            val results = mutableListOf<SearchResult>()
            cache.forEach { (key, items) ->
                val parts = key.split("::")
                if (parts.size != 2) return@forEach
                val (sourceName, componentType) = parts
                items.forEach { item ->
                    val hay = "${item.displayName} ${item.versionName} $componentType $sourceName".lowercase()
                    if (tokens.all { t -> hay.contains(t) }) results.add(SearchResult(sourceName, componentType, item))
                }
            }
            return results.distinctBy { it.item.downloadUrl }.sortedBy { it.item.displayName.lowercase() }
        }
    }

    data class SearchResult(
        val sourceName: String,
        val componentType: String,
        val item: RemoteItem
    )

    data class DownloadedFile(
        val sourceName: String,
        val componentType: String,
        val fileName: String,
        val fileSizeBytes: Long = 0L,
        val downloadedAt: Long = 0L,
        val uriString: String? = null
    )

    init {
        // Hydrate the in-memory downloaded keys from persisted records on first instantiation
        getAllDownloads().forEach {
            markDownloaded(it.sourceName, it.componentType, it.fileName)
        }
    }

    fun recordDownload(
        sourceName: String, componentType: String, fileName: String,
        fileSizeBytes: Long = 0L, uriString: String? = null
    ) {
        val record = DownloadedFile(sourceName, componentType, fileName, fileSizeBytes,
            System.currentTimeMillis(), uriString)
        val current = getAllDownloads().toMutableList()
        current.removeAll { it.sourceName == sourceName && it.componentType == componentType && it.fileName == fileName }
        current.add(record)
        saveDownloadRecords(current)
        markDownloaded(sourceName, componentType, fileName)
    }

    fun removeDownloadRecord(record: DownloadedFile) {
        val current = getAllDownloads().toMutableList()
        current.removeAll { it.sourceName == record.sourceName && it.componentType == record.componentType && it.fileName == record.fileName }
        saveDownloadRecords(current)
        unmarkDownloaded(record.sourceName, record.componentType, record.fileName)
    }

    /** Check each download record's URI and remove any where the file no longer exists.
     *  Returns the number of stale records removed. */
    fun pruneStaleDownloadRecords(context: Context): Int {
        val stale = getAllDownloads().filter { record ->
            val uriStr = record.uriString ?: return@filter true // no URI = assume stale
            try {
                val uri = android.net.Uri.parse(uriStr)
                if (uri.scheme == "content") {
                    context.contentResolver.query(uri, arrayOf("_id"), null, null, null)
                        ?.use { cursor -> cursor.count == 0 } ?: true
                } else {
                    val path = uri.path ?: uriStr
                    !File(path).exists()
                }
            } catch (_: Exception) { true }
        }
        stale.forEach { removeDownloadRecord(it) }
        return stale.size
    }

    fun getAllDownloads(): List<DownloadedFile> {
        val jsonStr = prefs.getString("download_records", "[]") ?: "[]"
        val list = mutableListOf<DownloadedFile>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(DownloadedFile(
                    sourceName = obj.getString("sourceName"),
                    componentType = obj.getString("componentType"),
                    fileName = obj.getString("fileName"),
                    fileSizeBytes = obj.optLong("fileSizeBytes", 0L),
                    downloadedAt = obj.optLong("downloadedAt", 0L),
                    uriString = obj.optString("uriString").takeIf { it.isNotEmpty() }
                ))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun saveDownloadRecords(records: List<DownloadedFile>) {
        val array = JSONArray()
        for (r in records) {
            val obj = JSONObject()
            obj.put("sourceName", r.sourceName)
            obj.put("componentType", r.componentType)
            obj.put("fileName", r.fileName)
            obj.put("fileSizeBytes", r.fileSizeBytes)
            obj.put("downloadedAt", r.downloadedAt)
            if (r.uriString != null) obj.put("uriString", r.uriString)
            array.put(obj)
        }
        prefs.edit().putString("download_records", array.toString()).apply()
    }

    /** Convert an API or raw URL to a human-readable browser URL. */
    fun getBrowseUrl(source: RemoteSource): String {
        val url = source.url
        if (url.contains("api.github.com/repos/")) {
            val path = url.substringAfter("api.github.com/repos/")
                .substringBefore("/releases").substringBefore("/contents")
            return "https://github.com/$path"
        }
        if (url.contains("raw.githubusercontent.com/")) {
            val parts = url.substringAfter("raw.githubusercontent.com/").split("/")
            if (parts.size >= 2) return "https://github.com/${parts[0]}/${parts[1]}"
        }
        return url
    }

    data class RemoteItem(
        val displayName: String,
        val versionName: String,
        val downloadUrl: String,
        val sourceName: String,
        val publishedAt: String? = null,  // "YYYY-MM-DD" from GitHub releases; null for WCP JSON sources
        val sizeBytes: Long? = null,      // asset size in bytes; null when not available
        val description: String? = null   // release body/notes from GitHub; null for WCP JSON / Contents sources
    )

    /**
     * An additional endpoint for composite sources. Types listed here are fetched from
     * this url/format instead of the primary url/format.
     */
    data class ExtraEndpoint(
        val url: String,
        val format: SourceFormat,
        val types: List<String>
    )

    data class RemoteSource(
        val name: String,
        val url: String,
        val format: SourceFormat,
        val supportedTypes: List<String> = emptyList(), // Empty implies all types
        val isCustom: Boolean = false,
        // Extra endpoints for composite sources — each endpoint owns a subset of types
        val extraEndpoints: List<ExtraEndpoint> = emptyList(),
        // Individual GitHub release names the user opted in to browse as their own categories
        val releaseTags: List<String> = emptyList()
    )

    data class RepoListImport(
        val customSources: List<RemoteSource>,
        val removedDefaults: List<String>,
        val sourceOrder: List<String>
    )

    enum class SourceFormat {
        WCP_JSON,
        GITHUB_RELEASES_TURNIP,
        GITHUB_RELEASES_WCP,
        GITHUB_RELEASES_ZIP,         // All .zip assets from each release, no name filter
        GITHUB_REPO_CONTENTS,        // GitHub Contents API — folders = types, files inside = components
        RANKING_EMULATORS_JSON,      // HUB Emulators rankings.json — manifestDrivers (WCP) + results Drivers (GPU)
        GAMENATIVE_MANIFEST,         // GameNative manifest.json — items.driver/dxvk/proton/fexcore/wowbox64
        PACK_JSON                    // Flat [{type, verName, remoteUrl}] — Arihany pack.json / Nightlies relay files
    }

    // Default built-in sources mapped strictly to the components they provide
    private val defaultSources = listOf(
        RemoteSource(
            name = "StevenMXZ",
            url = "https://raw.githubusercontent.com/StevenMXZ/Winlator-Contents/main/contents.json",
            format = SourceFormat.WCP_JSON,
            supportedTypes = listOf("dxvk", "vkd3d", "box64", "fex", "fexcore", "wine", "proton", "GPU Drivers"),
            extraEndpoints = listOf(ExtraEndpoint("https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/stevenmxz_drivers.json", SourceFormat.PACK_JSON, listOf("GPU Drivers")))
        ),
        RemoteSource(
            name = "Arihany WCPHub",
            url = "https://raw.githubusercontent.com/Arihany/WinlatorWCPHub/refs/heads/main/pack.json",
            format = SourceFormat.PACK_JSON,
            supportedTypes = listOf("dxvk", "vkd3d", "box64", "fex", "fexcore", "wine", "proton", "GPU Drivers")
        ),
        RemoteSource("Xnick417x", "https://raw.githubusercontent.com/Xnick417x/Winlator-Bionic-Nightly-wcp/refs/heads/main/content.json", SourceFormat.WCP_JSON, listOf("dxvk", "vkd3d", "box64", "fex", "fexcore", "wine", "proton")),
        RemoteSource("AdrenoToolsDrivers (K11MCH1)", "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/kimchi_drivers.json", SourceFormat.PACK_JSON, listOf("GPU Drivers")),
        RemoteSource("freedreno Turnip CI (whitebelyash)", "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/white_drivers.json", SourceFormat.PACK_JSON, listOf("GPU Drivers")),
        RemoteSource("MaxesTechReview (MTR)", "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/mtr_drivers.json", SourceFormat.PACK_JSON, listOf("GPU Drivers")),
        RemoteSource("Nightlies by The412Banner", "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main/nightlies_components.json", SourceFormat.PACK_JSON, listOf("DXVK", "VKD3D", "FEXCore", "Box64", "WOWBox64")),
    )

    fun getAllSources(): List<RemoteSource> {
        val removedDefaults = getRemovedDefaultSources()
        val filteredDefaults = defaultSources.filter { it.name !in removedDefaults }
        val all = filteredDefaults + getCustomSources()
        val order = getSourceOrder()
        if (order.isEmpty()) return all
        val orderMap = order.withIndex().associate { (i, name) -> name to i }
        return all.sortedWith(compareBy { orderMap[it.name] ?: Int.MAX_VALUE })
    }

    fun saveSourceOrder(orderedNames: List<String>) {
        prefs.edit().putString("source_order", orderedNames.joinToString("\n")).apply()
    }

    private fun getSourceOrder(): List<String> {
        val str = prefs.getString("source_order", "") ?: return emptyList()
        return if (str.isEmpty()) emptyList() else str.split("\n")
    }

    private fun getRemovedDefaultSources(): List<String> {
        val jsonStr = prefs.getString("removed_defaults", "[]") ?: "[]"
        val removed = mutableListOf<String>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                removed.add(array.getString(i))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return removed
    }

    private fun saveRemovedDefaultSources(removed: List<String>) {
        val array = JSONArray()
        for (name in removed) {
            array.put(name)
        }
        prefs.edit().putString("removed_defaults", array.toString()).apply()
    }

    private fun parseRemoteSourceObject(obj: JSONObject): RemoteSource {
        val format = try {
            SourceFormat.valueOf(obj.getString("format"))
        } catch (_: Exception) {
            SourceFormat.WCP_JSON
        }
        val typesArray = obj.optJSONArray("supportedTypes")
        val typesList = mutableListOf<String>()
        if (typesArray != null) {
            for (j in 0 until typesArray.length()) typesList.add(typesArray.getString(j))
        }
        val extrasArray = obj.optJSONArray("extraEndpoints")
        val extrasList = mutableListOf<ExtraEndpoint>()
        if (extrasArray != null) {
            for (j in 0 until extrasArray.length()) {
                val ep = extrasArray.getJSONObject(j)
                val epFormat = try { SourceFormat.valueOf(ep.getString("format")) } catch (_: Exception) { SourceFormat.WCP_JSON }
                val epTypes = mutableListOf<String>()
                val epTypesArr = ep.optJSONArray("types")
                if (epTypesArr != null) for (k in 0 until epTypesArr.length()) epTypes.add(epTypesArr.getString(k))
                extrasList.add(ExtraEndpoint(ep.getString("url"), epFormat, epTypes))
            }
        }
        val releaseTagsArray = obj.optJSONArray("releaseTags")
        val releaseTagsList = mutableListOf<String>()
        if (releaseTagsArray != null) {
            for (j in 0 until releaseTagsArray.length()) releaseTagsList.add(releaseTagsArray.getString(j))
        }
        return RemoteSource(
            name = obj.getString("name"),
            url = obj.getString("url"),
            format = format,
            supportedTypes = typesList,
            isCustom = true,
            extraEndpoints = extrasList,
            releaseTags = releaseTagsList
        )
    }

    private fun getCustomSources(): List<RemoteSource> {
        val jsonStr = prefs.getString("custom_sources", "[]") ?: "[]"
        val customSources = mutableListOf<RemoteSource>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                customSources.add(parseRemoteSourceObject(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return customSources
    }

    fun addCustomSource(source: RemoteSource) {
        val current = getCustomSources().toMutableList()
        current.add(source.copy(isCustom = true))
        saveCustomSources(current)
    }

    fun removeSource(source: RemoteSource) {
        if (source.isCustom) {
            val current = getCustomSources().toMutableList()
            current.removeAll { it.name == source.name && it.url == source.url }
            saveCustomSources(current)
        } else {
            val removed = getRemovedDefaultSources().toMutableList()
            if (!removed.contains(source.name)) {
                removed.add(source.name)
                saveRemovedDefaultSources(removed)
            }
        }
        // Remove from saved order
        val order = getSourceOrder().toMutableList()
        if (order.remove(source.name)) saveSourceOrder(order)
    }

    fun restoreDefaultSources() {
        prefs.edit().remove("removed_defaults").remove("source_order").apply()
    }

    fun exportRepoListJson(): String {
        val obj = JSONObject()
        obj.put("version", 1)
        // custom sources — reuse the same serialization as saveCustomSources
        val customArray = JSONArray()
        for (source in getCustomSources()) {
            val s = JSONObject()
            s.put("name", source.name)
            s.put("url", source.url)
            s.put("format", source.format.name)
            val typesArr = JSONArray(); source.supportedTypes.forEach { typesArr.put(it) }
            s.put("supportedTypes", typesArr)
            if (source.releaseTags.isNotEmpty()) {
                val tagsArr = JSONArray(); source.releaseTags.forEach { tagsArr.put(it) }
                s.put("releaseTags", tagsArr)
            }
            if (source.extraEndpoints.isNotEmpty()) {
                val extrasArr = JSONArray()
                for (ep in source.extraEndpoints) {
                    val epObj = JSONObject()
                    epObj.put("url", ep.url)
                    epObj.put("format", ep.format.name)
                    val epTypes = JSONArray(); ep.types.forEach { epTypes.put(it) }
                    epObj.put("types", epTypes)
                    extrasArr.put(epObj)
                }
                s.put("extraEndpoints", extrasArr)
            }
            customArray.put(s)
        }
        obj.put("customSources", customArray)
        val removedArr = JSONArray(); getRemovedDefaultSources().forEach { removedArr.put(it) }
        obj.put("removedDefaults", removedArr)
        val orderArr = JSONArray(); getSourceOrder().forEach { orderArr.put(it) }
        obj.put("sourceOrder", orderArr)
        return obj.toString(2)
    }

    fun parseRepoListJson(json: String): RepoListImport {
        val obj = JSONObject(json)
        val customSources = mutableListOf<RemoteSource>()
        val customArr = obj.optJSONArray("customSources") ?: JSONArray()
        for (i in 0 until customArr.length()) {
            try { customSources.add(parseRemoteSourceObject(customArr.getJSONObject(i))) } catch (_: Exception) {}
        }
        val removedDefaults = mutableListOf<String>()
        val removedArr = obj.optJSONArray("removedDefaults") ?: JSONArray()
        for (i in 0 until removedArr.length()) removedDefaults.add(removedArr.getString(i))
        val sourceOrder = mutableListOf<String>()
        val orderArr = obj.optJSONArray("sourceOrder") ?: JSONArray()
        for (i in 0 until orderArr.length()) sourceOrder.add(orderArr.getString(i))
        return RepoListImport(customSources, removedDefaults, sourceOrder)
    }

    fun applyRepoListImport(import: RepoListImport, merge: Boolean) {
        if (merge) {
            val existing = getCustomSources()
            val existingNames = existing.map { it.name }.toSet()
            val toAdd = import.customSources.filter { it.name !in existingNames }
            saveCustomSources(existing + toAdd)
        } else {
            saveCustomSources(import.customSources)
        }
        saveRemovedDefaultSources(import.removedDefaults)
        if (import.sourceOrder.isNotEmpty()) saveSourceOrder(import.sourceOrder)
    }

    private fun saveCustomSources(sources: List<RemoteSource>) {
        val array = JSONArray()
        for (source in sources) {
            val obj = JSONObject()
            obj.put("name", source.name)
            obj.put("url", source.url)
            obj.put("format", source.format.name)
            val typesArray = JSONArray()
            for (type in source.supportedTypes) typesArray.put(type)
            obj.put("supportedTypes", typesArray)
            if (source.releaseTags.isNotEmpty()) {
                val tagsArray = JSONArray()
                for (tag in source.releaseTags) tagsArray.put(tag)
                obj.put("releaseTags", tagsArray)
            }
            if (source.extraEndpoints.isNotEmpty()) {
                val extrasArray = JSONArray()
                for (ep in source.extraEndpoints) {
                    val epObj = JSONObject()
                    epObj.put("url", ep.url)
                    epObj.put("format", ep.format.name)
                    val epTypes = JSONArray()
                    for (t in ep.types) epTypes.put(t)
                    epObj.put("types", epTypes)
                    extrasArray.put(epObj)
                }
                obj.put("extraEndpoints", extrasArray)
            }
            array.put(obj)
        }
        prefs.edit().putString("custom_sources", array.toString()).apply()
    }

    suspend fun fetchFromSource(
        source: RemoteSource,
        componentType: String
    ): List<RemoteItem> = withContext(Dispatchers.IO) {
        getFromCache(source.name, componentType)?.let { return@withContext it }
        // If the type is a user-opted release tag, fetch all assets from that release directly
        if (componentType in source.releaseTags) {
            val result = fetchGithubReleaseByTag(source.url, componentType, source.name)
            putToCache(source.name, componentType, result)
            return@withContext result
        }
        // Route to the extra endpoint that owns this type (if any), else use primary
        val isGpuDrivers = componentType == GPU_DRIVER_TYPE
        val extra = if (isGpuDrivers)
            source.extraEndpoints.firstOrNull { ep -> ep.types.any { it == GPU_DRIVER_TYPE || GPU_DRIVER_KEYWORDS.contains(it) } }
        else
            source.extraEndpoints.firstOrNull { componentType in it.types }
        val activeUrl = extra?.url ?: source.url
        val activeFormat = extra?.format ?: source.format
        val result = when (activeFormat) {
            SourceFormat.WCP_JSON -> fetchWcpJson(activeUrl, componentType, source.name)
            SourceFormat.GITHUB_RELEASES_TURNIP -> fetchTurnipReleases(activeUrl, source.name, if (isGpuDrivers) "" else componentType)
            SourceFormat.GITHUB_RELEASES_WCP -> fetchGithubReleasesWcp(activeUrl, componentType, source.name)
            SourceFormat.GITHUB_RELEASES_ZIP -> fetchGithubReleasesZip(activeUrl, source.name)
            SourceFormat.GITHUB_REPO_CONTENTS -> fetchGithubRepoContents(activeUrl, componentType, source.name)
            SourceFormat.RANKING_EMULATORS_JSON -> fetchRankingEmulators(activeUrl, componentType, source.name)
            SourceFormat.GAMENATIVE_MANIFEST -> fetchGameNativeManifest(activeUrl, componentType, source.name)
            SourceFormat.PACK_JSON -> fetchPackJson(activeUrl, componentType, source.name)
        }
        putToCache(source.name, componentType, result)
        result
    }

    /**
     * Refresh the entire cache by fetching all sources × all relevant component types in parallel.
     * Formats that don't filter by type (TURNIP, ZIP) are fetched once and cached under all
     * their supported types to avoid redundant API hits.
     */
    suspend fun refreshAllCache(sources: List<RemoteSource>, allTypes: List<String>) {
        clearCache()
        coroutineScope {
            sources.forEach { source ->
                launch(Dispatchers.IO) {
                    try {
                        // Primary types = all supportedTypes not owned by any extra endpoint
                        val extraTypeSet = source.extraEndpoints.flatMap { it.types }.toSet()
                        val primaryTypes = if (source.supportedTypes.isNotEmpty())
                            source.supportedTypes.filter { it !in extraTypeSet }
                        else allTypes.filter { it !in extraTypeSet }

                        when (source.format) {
                            SourceFormat.GITHUB_RELEASES_TURNIP -> {
                                val allItems = fetchTurnipReleases(source.url, source.name)
                                primaryTypes.forEach { type ->
                                    val filtered = if (type == GPU_DRIVER_TYPE) allItems else allItems.filter {
                                        it.displayName.contains(type, ignoreCase = true) ||
                                        it.versionName.contains(type, ignoreCase = true)
                                    }
                                    putToCache(source.name, type, filtered)
                                }
                            }
                            SourceFormat.GITHUB_RELEASES_ZIP -> {
                                val items = fetchGithubReleasesZip(source.url, source.name)
                                primaryTypes.forEach { putToCache(source.name, it, items) }
                            }
                            SourceFormat.WCP_JSON -> {
                                primaryTypes.forEach { type ->
                                    val items = fetchWcpJson(source.url, type, source.name)
                                    putToCache(source.name, type, items)
                                }
                            }
                            SourceFormat.GITHUB_RELEASES_WCP -> {
                                primaryTypes.forEach { type ->
                                    val items = fetchGithubReleasesWcp(source.url, type, source.name)
                                    putToCache(source.name, type, items)
                                }
                            }
                            SourceFormat.GITHUB_REPO_CONTENTS -> {
                                val folders = discoverTypes(source)
                                folders.forEach { folder ->
                                    val items = fetchGithubRepoContents(source.url, folder, source.name)
                                    putToCache(source.name, folder, items)
                                }
                            }
                            SourceFormat.RANKING_EMULATORS_JSON -> {
                                cacheAllRankingEmulators(source.url, source.name)
                            }
                            SourceFormat.GAMENATIVE_MANIFEST -> {
                                cacheAllGameNativeManifest(source.url, source.name)
                            }
                            SourceFormat.PACK_JSON -> {
                                cacheAllPackJson(source.url, source.name)
                            }
                        }

                        // Fetch each extra endpoint and cache under its declared types
                        source.extraEndpoints.forEach { ep ->
                            when (ep.format) {
                                SourceFormat.GITHUB_RELEASES_TURNIP -> {
                                    val allItems = fetchTurnipReleases(ep.url, source.name)
                                    ep.types.forEach { type ->
                                        val filtered = if (type == GPU_DRIVER_TYPE) allItems else allItems.filter {
                                            it.displayName.contains(type, ignoreCase = true) ||
                                            it.versionName.contains(type, ignoreCase = true)
                                        }
                                        putToCache(source.name, type, filtered)
                                    }
                                }
                                SourceFormat.GITHUB_RELEASES_ZIP -> {
                                    val items = fetchGithubReleasesZip(ep.url, source.name)
                                    ep.types.forEach { putToCache(source.name, it, items) }
                                }
                                SourceFormat.GITHUB_RELEASES_WCP -> {
                                    ep.types.forEach { type ->
                                        val items = fetchGithubReleasesWcp(ep.url, type, source.name)
                                        putToCache(source.name, type, items)
                                    }
                                }
                                SourceFormat.WCP_JSON -> {
                                    ep.types.forEach { type ->
                                        val items = fetchWcpJson(ep.url, type, source.name)
                                        putToCache(source.name, type, items)
                                    }
                                }
                                SourceFormat.PACK_JSON -> {
                                    cacheAllPackJson(ep.url, source.name)
                                }
                                else -> { /* GITHUB_REPO_CONTENTS not expected as extra endpoint */ }
                            }
                        }
                        // Cache any user-opted release tag categories
                        source.releaseTags.forEach { tag ->
                            val items = fetchGithubReleaseByTag(source.url, tag, source.name)
                            putToCache(source.name, tag, items)
                        }
                    } catch (_: Exception) { /* skip failed sources silently */ }
                }
            }
        }
    }

    /**
     * Fetches all GitHub release names from a GITHUB_RELEASES_* source so the user can
     * opt individual releases in as browseable categories in the edit dialog.
     * Returns an empty list for non-GitHub-releases formats.
     */
    suspend fun discoverReleaseTags(source: RemoteSource): List<String> = withContext(Dispatchers.IO) {
        val githubFormats = setOf(
            SourceFormat.GITHUB_RELEASES_WCP,
            SourceFormat.GITHUB_RELEASES_TURNIP,
            SourceFormat.GITHUB_RELEASES_ZIP
        )
        if (source.format !in githubFormats) return@withContext emptyList()
        try {
            val json = openUrl(source.url).inputStream.bufferedReader().readText()
            val array = JSONArray(json)
            val tags = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val release = array.getJSONObject(i)
                val name = release.optString("name").trim().ifEmpty {
                    release.optString("tag_name").trim()
                }
                if (name.isNotEmpty()) tags.add(name)
            }
            tags
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Fetches ALL assets from the GitHub release whose name or tag matches [releaseTag],
     * regardless of file extension. Used when the user opts a release in as a category.
     */
    private suspend fun fetchGithubReleaseByTag(
        url: String,
        releaseTag: String,
        sourceName: String
    ): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            val releaseName = release.optString("name").trim()
            val tagName = release.optString("tag_name").trim()
            if (releaseName != releaseTag && tagName != releaseTag) continue
            val publishedAt = release.optString("published_at").substringBefore("T").takeIf { it.isNotEmpty() }
            val description = release.optString("body").takeIf { it.isNotBlank() }
            val assets = release.optJSONArray("assets") ?: return@withContext emptyList()
            val result = mutableListOf<RemoteItem>()
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val assetName = asset.optString("name")
                result.add(
                    RemoteItem(
                        displayName = assetName,
                        versionName = assetName,
                        downloadUrl = asset.optString("browser_download_url"),
                        sourceName = sourceName,
                        publishedAt = publishedAt,
                        sizeBytes = asset.optLong("size", 0).takeIf { it > 0 },
                        description = description
                    )
                )
            }
            return@withContext result
        }
        emptyList()
    }

    /** Collapses individual GPU driver keywords into a single "GPU Drivers" entry in a type list. */
    private fun normalizeGpuTypes(types: List<String>): List<String> {
        val result = mutableListOf<String>()
        var gpuAdded = false
        for (t in types) {
            if (GPU_DRIVER_KEYWORDS.any { kw -> t.equals(kw, ignoreCase = true) }) {
                if (!gpuAdded) { result.add(GPU_DRIVER_TYPE); gpuAdded = true }
            } else {
                result.add(t)
            }
        }
        return result
    }

    private suspend fun fetchWcpJson(
        jsonUrl: String,
        componentType: String,
        sourceName: String
    ): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(jsonUrl).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        val all = mutableListOf<RemoteItem>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val type = obj.getString("type")
            val verName = obj.getString("verName")
            val url = obj.getString("remoteUrl")
            all.add(RemoteItem(displayName = "$type  $verName", versionName = verName, downloadUrl = url, sourceName = sourceName))
        }
        // Ensure we only return items strictly matching the requested component folder
        val filtered = when {
            componentType == GPU_DRIVER_TYPE ->
                all.filter { item -> GPU_DRIVER_KEYWORDS.any { kw -> item.displayName.contains(kw, ignoreCase = true) } }
            componentType.equals("fex", ignoreCase = true) ->
                all.filter { it.displayName.contains("fex", ignoreCase = true) }
            else ->
                all.filter { it.displayName.contains(componentType, ignoreCase = true) }
        }
        filtered.reversed()
    }

    private suspend fun fetchTurnipReleases(url: String, sourceName: String, filterKeyword: String = ""): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        val result = mutableListOf<RemoteItem>()
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            val releaseName = release.getString("name").trim()
            val publishedAt = release.optString("published_at").substringBefore("T").takeIf { it.isNotEmpty() }
            val description = release.optString("body").takeIf { it.isNotBlank() }
            val assets = release.getJSONArray("assets")
            val allAssets = (0 until assets.length()).map { assets.getJSONObject(it) }
            val matchedAssets = if (filterKeyword.isBlank()) allAssets
                else allAssets.filter { it.getString("name").contains(filterKeyword, ignoreCase = true) }
            for (asset in matchedAssets) {
                val assetName = asset.getString("name")
                val displayName = if (matchedAssets.size > 1) {
                    "$releaseName — ${assetName.removeSuffix(".zip")}"
                } else {
                    releaseName
                }
                result.add(
                    RemoteItem(
                        displayName = displayName,
                        versionName = assetName.removeSuffix(".zip"),
                        downloadUrl = asset.getString("browser_download_url"),
                        sourceName = sourceName,
                        publishedAt = publishedAt,
                        sizeBytes = asset.optLong("size", 0).takeIf { it > 0 },
                        description = description
                    )
                )
            }
        }
        result
    }

    private suspend fun fetchGithubReleasesWcp(url: String, componentType: String, sourceName: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        val result = mutableListOf<RemoteItem>()
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            val releaseName = release.getString("name").trim()
            val publishedAt = release.optString("published_at").substringBefore("T").takeIf { it.isNotEmpty() }
            val description = release.optString("body").takeIf { it.isNotBlank() }
            val assets = release.getJSONArray("assets")
            val wcpAssets = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".wcp", ignoreCase = true) }
            for (asset in wcpAssets) {
                val assetName = asset.getString("name")
                val gpuMatch = componentType == GPU_DRIVER_TYPE &&
                               GPU_DRIVER_KEYWORDS.any { assetName.contains(it, ignoreCase = true) }
                val fexBridge = (componentType.equals("fexcore", ignoreCase = true) && assetName.contains("fex", ignoreCase = true)) ||
                                (componentType.equals("fex", ignoreCase = true) && assetName.contains("fexcore", ignoreCase = true))
                if (componentType.isEmpty() || assetName.contains(componentType, ignoreCase = true) || fexBridge || gpuMatch) {
                    val displayName = if (wcpAssets.size > 1) "$releaseName — $assetName" else releaseName
                    result.add(
                        RemoteItem(
                            displayName = displayName,
                            versionName = assetName.removeSuffix(".wcp"),
                            downloadUrl = asset.getString("browser_download_url"),
                            sourceName = sourceName,
                            publishedAt = publishedAt,
                            sizeBytes = asset.optLong("size", 0).takeIf { it > 0 },
                            description = description
                        )
                    )
                }
            }
        }
        result
    }

    private suspend fun fetchGithubReleasesZip(url: String, sourceName: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        val result = mutableListOf<RemoteItem>()
        for (i in 0 until array.length()) {
            val release = array.getJSONObject(i)
            val releaseName = release.getString("name").trim()
            val publishedAt = release.optString("published_at").substringBefore("T").takeIf { it.isNotEmpty() }
            val description = release.optString("body").takeIf { it.isNotBlank() }
            val assets = release.getJSONArray("assets")
            val zipAssets = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .filter { it.getString("name").endsWith(".zip", ignoreCase = true) }
            for (asset in zipAssets) {
                val assetName = asset.getString("name")
                val displayName = if (zipAssets.size > 1) "$releaseName — ${assetName.removeSuffix(".zip")}" else releaseName
                result.add(
                    RemoteItem(
                        displayName = displayName,
                        versionName = assetName.removeSuffix(".zip"),
                        downloadUrl = asset.getString("browser_download_url"),
                        sourceName = sourceName,
                        publishedAt = publishedAt,
                        sizeBytes = asset.optLong("size", 0).takeIf { it > 0 },
                        description = description
                    )
                )
            }
        }
        result
    }

    /**
     * Fetches the repository and returns all component type names it actually contains.
     * For WCP_JSON: extracts unique "type" field values from the JSON entries.
     * For GITHUB_RELEASES_WCP/ZIP: scans asset names and matches against known types.
     * For GITHUB_RELEASES_TURNIP: always returns ["turnip", "adreno"].
     * Falls back to the source's existing supportedTypes (or all known types) on error.
     */
    suspend fun discoverTypes(source: RemoteSource): List<String> = withContext(Dispatchers.IO) {
        val knownTypes = listOf("dxvk", "vkd3d", "box64", "fex", "fexcore", "wined3d", "turnip", "adreno", "qualcomm", "mesa", "drivers", "wine", "proton")
        fun sortByKnown(set: Set<String>) = set.sortedBy { t -> knownTypes.indexOf(t).let { if (it == -1) Int.MAX_VALUE else it } }
        // Composite sources have all their types explicitly listed — no network scan needed
        if (source.extraEndpoints.isNotEmpty() && source.supportedTypes.isNotEmpty()) {
            return@withContext source.supportedTypes
        }
        try {
            when (source.format) {
                SourceFormat.WCP_JSON -> {
                    val conn = URL(source.url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                    val array = JSONArray(conn.inputStream.bufferedReader().readText())
                    val types = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        val t = array.getJSONObject(i).optString("type").lowercase().trim()
                        if (t.isNotEmpty()) types.add(t)
                    }
                    sortByKnown(types)
                }
                SourceFormat.GITHUB_RELEASES_TURNIP -> listOf(GPU_DRIVER_TYPE)
                SourceFormat.GITHUB_RELEASES_WCP -> {
                    val conn = URL(source.url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                    val array = JSONArray(conn.inputStream.bufferedReader().readText())
                    val found = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        val assets = array.getJSONObject(i).optJSONArray("assets") ?: continue
                        for (j in 0 until assets.length()) {
                            val name = assets.getJSONObject(j).optString("name").lowercase()
                            if (!name.endsWith(".wcp")) continue
                            knownTypes.forEach { if (name.contains(it)) found.add(it) }
                            // fex/fexcore bridge: a file matching either adds both types
                            if (name.contains("fex")) { found.add("fex"); found.add("fexcore") }
                        }
                    }
                    normalizeGpuTypes(sortByKnown(found))
                }
                SourceFormat.GITHUB_RELEASES_ZIP -> {
                    val conn = URL(source.url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                    val array = JSONArray(conn.inputStream.bufferedReader().readText())
                    val found = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        val assets = array.getJSONObject(i).optJSONArray("assets") ?: continue
                        for (j in 0 until assets.length()) {
                            val name = assets.getJSONObject(j).optString("name").lowercase()
                            if (!name.endsWith(".zip")) continue
                            knownTypes.forEach { if (name.contains(it)) found.add(it) }
                        }
                    }
                    // ZIP repos often have generic filenames; fall back to full known list if nothing matched
                    if (found.isEmpty()) normalizeGpuTypes(knownTypes) else normalizeGpuTypes(sortByKnown(found))
                }
                SourceFormat.GITHUB_REPO_CONTENTS -> {
                    // Return the actual folder names from the repo root (case-preserved for API calls)
                    val conn = URL(normalizeContentsUrl(source.url)).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                    val array = JSONArray(conn.inputStream.bufferedReader().readText())
                    val folders = mutableListOf<String>()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        if (item.getString("type") == "dir" && !item.getString("name").startsWith(".")) {
                            folders.add(item.getString("name")) // preserve original casing
                        }
                    }
                    folders
                }
                SourceFormat.RANKING_EMULATORS_JSON -> {
                    val conn = URL(rankingJsonUrl(source.url)).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                    val root = JSONObject(conn.inputStream.bufferedReader().readText())
                    val types = mutableListOf<String>()
                    // WCP types from manifestDrivers keys
                    val md = root.optJSONObject("manifestDrivers")
                    md?.keys()?.forEach { types.add(it.lowercase()) }
                    // GPU driver types from results Drivers assets — all collapse into a single "GPU Drivers" entry
                    var hasGpuDrivers = false
                    val results = root.optJSONArray("results")
                    if (results != null) {
                        outer@ for (i in 0 until results.length()) {
                            val proj = results.getJSONObject(i)
                            if (!proj.optString("category").equals("Drivers", ignoreCase = true)) continue
                            val releases = proj.optJSONArray("releases") ?: continue
                            for (r in 0 until releases.length()) {
                                val assets = releases.getJSONObject(r).optJSONArray("assets") ?: continue
                                for (a in 0 until assets.length()) {
                                    val name = assets.getJSONObject(a).optString("name").lowercase()
                                    if (GPU_DRIVER_KEYWORDS.any { name.contains(it) }) { hasGpuDrivers = true; break@outer }
                                }
                            }
                        }
                    }
                    if (hasGpuDrivers) types + listOf(GPU_DRIVER_TYPE) else types
                }
                SourceFormat.GAMENATIVE_MANIFEST -> {
                    val conn = URL(source.url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                    val root = JSONObject(conn.inputStream.bufferedReader().readText())
                    val items = root.optJSONObject("items") ?: return@withContext emptyList()
                    val types = mutableListOf<String>()
                    items.keys().forEach { key ->
                        if (key.equals("driver", ignoreCase = true)) types.add(GPU_DRIVER_TYPE)
                        else types.add(key.lowercase())
                    }
                    types
                }
                SourceFormat.PACK_JSON -> {
                    val conn = URL(source.url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
                    val array = JSONArray(conn.inputStream.bufferedReader().readText())
                    val types = mutableSetOf<String>()
                    for (i in 0 until array.length()) {
                        val rawType = array.getJSONObject(i).optString("type")
                        if (rawType.equals("GpuDriver", ignoreCase = true)) types.add(GPU_DRIVER_TYPE)
                        else if (rawType.isNotEmpty()) types.add(rawType.lowercase())
                    }
                    types.toList()
                }
            }
        } catch (_: Exception) {
            if (source.supportedTypes.isNotEmpty()) source.supportedTypes else knownTypes
        }
    }

    /**
     * Edit an existing source in-place.
     * Custom sources are updated directly. Built-in defaults are removed from the
     * active list and the edited version is saved as a new custom source.
     */
    fun editSource(oldSource: RemoteSource, newSource: RemoteSource) {
        if (oldSource.isCustom) {
            val current = getCustomSources().toMutableList()
            val idx = current.indexOfFirst { it.name == oldSource.name && it.url == oldSource.url }
            if (idx >= 0) current[idx] = newSource.copy(isCustom = true) else current.add(newSource.copy(isCustom = true))
            saveCustomSources(current)
        } else {
            removeSource(oldSource)   // marks as removed from defaults
            addCustomSource(newSource)
        }
    }

    /** Convert plain https://github.com/{owner}/{repo} to the GitHub Contents API URL. */
    private fun rankingJsonUrl(url: String): String {
        val base = url.trimEnd('/')
        return if (base.endsWith("rankings.json")) base else "$base/data/rankings.json"
    }

    private suspend fun fetchRankingEmulators(url: String, componentType: String, sourceName: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(rankingJsonUrl(url)).inputStream.bufferedReader().readText()
        val root = JSONObject(json)
        val result = mutableListOf<RemoteItem>()

        // manifestDrivers section — WCP components (dxvk, vkd3d, box64, fexcore, wine, etc.)
        // Skip for GPU Drivers — those come from the results/Drivers section
        val md = if (componentType != GPU_DRIVER_TYPE) root.optJSONObject("manifestDrivers") else null
        if (md != null) {
            val matchKey = md.keys().asSequence().firstOrNull { it.equals(componentType, ignoreCase = true) }
            if (matchKey != null) {
                val items = md.getJSONArray(matchKey)
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val dlUrl = item.optString("url")
                    if (dlUrl.isNotEmpty()) {
                        result.add(RemoteItem(
                            displayName = item.optString("name"),
                            versionName = item.optString("version"),
                            downloadUrl = dlUrl,
                            sourceName = sourceName,
                            publishedAt = item.optString("date").substringBefore("T").takeIf { it.isNotEmpty() }
                        ))
                    }
                }
                return@withContext result
            }
        }

        // results section — Drivers category (turnip, adreno, qualcomm)
        val results = root.optJSONArray("results") ?: return@withContext result
        for (i in 0 until results.length()) {
            val proj = results.getJSONObject(i)
            if (!proj.optString("category").equals("Drivers", ignoreCase = true)) continue
            val projName = proj.optString("name")
            val releases = proj.optJSONArray("releases") ?: continue
            for (r in 0 until releases.length()) {
                val release = releases.getJSONObject(r)
                val tag = release.optString("tag")
                val date = release.optString("date").substringBefore("T").takeIf { it.isNotEmpty() }
                val assets = release.optJSONArray("assets") ?: continue
                for (a in 0 until assets.length()) {
                    val asset = assets.getJSONObject(a)
                    val assetName = asset.optString("name")
                    val assetMatches = componentType == GPU_DRIVER_TYPE ||
                        GPU_DRIVER_KEYWORDS.any { assetName.contains(it, ignoreCase = true) } ||
                        assetName.contains(componentType, ignoreCase = true)
                    if (!assetMatches) continue
                    val dlUrl = asset.optString("url")
                    val size = asset.optLong("size", 0).takeIf { it > 0 }
                    result.add(RemoteItem(
                        displayName = "$projName $tag — ${assetName.removeSuffix(".zip")}",
                        versionName = tag,
                        downloadUrl = dlUrl,
                        sourceName = sourceName,
                        publishedAt = date,
                        sizeBytes = size
                    ))
                }
            }
        }
        result
    }

    /** Fetches rankings.json once and populates the cache for all available types. */
    private suspend fun cacheAllRankingEmulators(url: String, sourceName: String) = withContext(Dispatchers.IO) {
        val json = openUrl(rankingJsonUrl(url)).inputStream.bufferedReader().readText()
        val root = JSONObject(json)

        // manifestDrivers — one cache entry per category key
        val md = root.optJSONObject("manifestDrivers")
        md?.keys()?.forEach { key ->
            val type = key.lowercase()
            val items = md.getJSONArray(key)
            val list = mutableListOf<RemoteItem>()
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val dlUrl = item.optString("url")
                if (dlUrl.isNotEmpty()) {
                    list.add(RemoteItem(
                        displayName = item.optString("name"),
                        versionName = item.optString("version"),
                        downloadUrl = dlUrl,
                        sourceName = sourceName,
                        publishedAt = item.optString("date").substringBefore("T").takeIf { it.isNotEmpty() }
                    ))
                }
            }
            putToCache(sourceName, type, list)
        }

        // results Drivers — bucket assets by GPU driver keyword
        val gpuMap = mutableMapOf<String, MutableList<RemoteItem>>()
        val results = root.optJSONArray("results")
        if (results != null) {
            for (i in 0 until results.length()) {
                val proj = results.getJSONObject(i)
                if (!proj.optString("category").equals("Drivers", ignoreCase = true)) continue
                val projName = proj.optString("name")
                val releases = proj.optJSONArray("releases") ?: continue
                for (r in 0 until releases.length()) {
                    val release = releases.getJSONObject(r)
                    val tag = release.optString("tag")
                    val date = release.optString("date").substringBefore("T").takeIf { it.isNotEmpty() }
                    val assets = release.optJSONArray("assets") ?: continue
                    for (a in 0 until assets.length()) {
                        val asset = assets.getJSONObject(a)
                        val assetName = asset.optString("name")
                        val dlUrl = asset.optString("url")
                        val size = asset.optLong("size", 0).takeIf { it > 0 }
                        val item = RemoteItem(
                            displayName = "$projName $tag — ${assetName.removeSuffix(".zip")}",
                            versionName = tag,
                            downloadUrl = dlUrl,
                            sourceName = sourceName,
                            publishedAt = date,
                            sizeBytes = size
                        )
                        if (GPU_DRIVER_KEYWORDS.any { assetName.contains(it, ignoreCase = true) }) {
                            gpuMap.getOrPut(GPU_DRIVER_TYPE) { mutableListOf() }.add(item)
                        }
                    }
                }
            }
        }
        gpuMap.forEach { (type, items) -> putToCache(sourceName, type, items) }
    }

    private fun normalizeContentsUrl(url: String): String {
        val match = Regex("""https://github\.com/([^/]+)/([^/]+?)/?$""").find(url) ?: return url
        return "https://api.github.com/repos/${match.groupValues[1]}/${match.groupValues[2]}/contents"
    }

    private suspend fun fetchGithubRepoContents(
        url: String,
        folderName: String,
        sourceName: String
    ): List<RemoteItem> = withContext(Dispatchers.IO) {
        val apiUrl = normalizeContentsUrl(url)
        val folderUrl = "$apiUrl/$folderName"
        val json = openUrl(folderUrl).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        val result = mutableListOf<RemoteItem>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            if (item.getString("type") != "file") continue
            val name = item.getString("name")
            if (!name.endsWith(".wcp", ignoreCase = true) && !name.endsWith(".zip", ignoreCase = true)) continue
            val downloadUrl = item.optString("download_url").takeIf { it.isNotEmpty() } ?: continue
            result.add(RemoteItem(
                displayName = name.substringBeforeLast("."),
                versionName = name.substringBeforeLast("."),
                downloadUrl = downloadUrl,
                sourceName = sourceName,
                publishedAt = null,
                sizeBytes = item.optLong("size", 0).takeIf { it > 0 }
            ))
        }
        result
    }

    private suspend fun fetchGameNativeManifest(
        url: String,
        componentType: String,
        sourceName: String
    ): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val root = JSONObject(json)
        val updatedAt = root.optString("updatedAt").takeIf { it.isNotEmpty() }
        val items = root.optJSONObject("items") ?: return@withContext emptyList()
        val result = mutableListOf<RemoteItem>()
        val isGpu = componentType == GPU_DRIVER_TYPE
        // Map componentType → manifest key
        val targetKey = if (isGpu) "driver" else componentType
        val matchKey = items.keys().asSequence().firstOrNull { it.equals(targetKey, ignoreCase = true) }
            ?: return@withContext emptyList()
        val arr = items.getJSONArray(matchKey)
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val name = item.optString("name").ifEmpty { item.optString("id") }
            val dlUrl = item.optString("url")
            if (dlUrl.isEmpty()) continue
            result.add(RemoteItem(
                displayName = name,
                versionName = name,
                downloadUrl = dlUrl,
                sourceName = sourceName,
                publishedAt = updatedAt
            ))
        }
        result
    }

    /** Fetches the GameNative manifest once and populates the cache for all categories. */
    private suspend fun cacheAllGameNativeManifest(url: String, sourceName: String) = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val root = JSONObject(json)
        val updatedAt = root.optString("updatedAt").takeIf { it.isNotEmpty() }
        val items = root.optJSONObject("manifestItems") ?: root.optJSONObject("items") ?: return@withContext
        items.keys().forEach { key ->
            val cacheType = if (key.equals("driver", ignoreCase = true)) GPU_DRIVER_TYPE else key.lowercase()
            val arr = try { items.getJSONArray(key) } catch (_: Exception) { return@forEach }
            val list = mutableListOf<RemoteItem>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val name = item.optString("name").ifEmpty { item.optString("id") }
                val dlUrl = item.optString("url")
                if (dlUrl.isEmpty()) continue
                list.add(RemoteItem(
                    displayName = name,
                    versionName = name,
                    downloadUrl = dlUrl,
                    sourceName = sourceName,
                    publishedAt = updatedAt
                ))
            }
            putToCache(sourceName, cacheType, list)
        }
    }

    /** Fetches a flat [{type, verName, remoteUrl}] JSON and returns items matching componentType. */
    private suspend fun fetchPackJson(url: String, componentType: String, sourceName: String): List<RemoteItem> = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        val isGpu = componentType == GPU_DRIVER_TYPE
        val result = mutableListOf<RemoteItem>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val rawType = item.optString("type")
            val matches = if (isGpu) rawType.equals("GpuDriver", ignoreCase = true)
                          else rawType.equals(componentType, ignoreCase = true)
            if (!matches) continue
            val verName = item.optString("verName").trim()
            val remoteUrl = item.optString("remoteUrl")
            if (remoteUrl.isEmpty()) continue
            result.add(RemoteItem(
                displayName = verName,
                versionName = verName,
                downloadUrl = remoteUrl,
                sourceName = sourceName
            ))
        }
        result
    }

    /** Fetches a PACK_JSON file once and populates the cache for all type buckets it contains. */
    private suspend fun cacheAllPackJson(url: String, sourceName: String) = withContext(Dispatchers.IO) {
        val json = openUrl(url).inputStream.bufferedReader().readText()
        val array = JSONArray(json)
        val byType = mutableMapOf<String, MutableList<RemoteItem>>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val rawType = item.optString("type")
            val verName = item.optString("verName").trim()
            val remoteUrl = item.optString("remoteUrl")
            if (remoteUrl.isEmpty()) continue
            val cacheType = if (rawType.equals("GpuDriver", ignoreCase = true)) GPU_DRIVER_TYPE
                            else rawType.lowercase()
            byType.getOrPut(cacheType) { mutableListOf() }.add(
                RemoteItem(displayName = verName, versionName = verName, downloadUrl = remoteUrl, sourceName = sourceName)
            )
        }
        byType.forEach { (type, items) -> putToCache(sourceName, type, items) }
    }

    suspend fun downloadToTemp(
        url: String,
        onProgress: (String) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val partFile = File(context.cacheDir, "dl_${Math.abs(url.hashCode())}.part")
        var existingBytes = if (partFile.exists()) partFile.length() else 0L

        var conn = openDownloadUrl(url, existingBytes)
        val isResume: Boolean
        when (conn.responseCode) {
            206 -> isResume = true
            416 -> {
                // Stale partial file larger than the actual file; start over
                partFile.delete()
                existingBytes = 0L
                conn.disconnect()
                conn = openDownloadUrl(url, 0)
                isResume = false
            }
            else -> {
                if (existingBytes > 0) partFile.delete()
                existingBytes = 0L
                isResume = false
            }
        }

        val contentLength = conn.contentLengthLong
        val total = when {
            isResume && contentLength >= 0 -> existingBytes + contentLength
            contentLength >= 0 -> contentLength
            else -> -1L
        }
        var downloaded = existingBytes

        conn.inputStream.use { input ->
            FileOutputStream(partFile, isResume).use { output ->
                val buffer = ByteArray(16384)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    val msg = if (total > 0) {
                        val pct = (downloaded * 100 / total).toInt()
                        val mb = downloaded / 1_048_576f
                        "Downloading... $pct% (${String.format("%.1f", mb)} MB)"
                    } else {
                        val mb = downloaded / 1_048_576f
                        "Downloading... ${String.format("%.1f", mb)} MB"
                    }
                    onProgress(msg)
                }
            }
        }
        partFile
    }

    private fun openDownloadUrl(url: String, rangeStart: Long = 0): HttpURLConnection {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "*/*")
        conn.instanceFollowRedirects = true
        if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")
        conn.connect()
        val status = conn.responseCode
        if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
            status == HttpURLConnection.HTTP_MOVED_PERM ||
            status == 307 || status == 308
        ) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "*/*")
            conn.instanceFollowRedirects = true
            if (rangeStart > 0) conn.setRequestProperty("Range", "bytes=$rangeStart-")
            conn.connect()
        }
        return conn
    }

    private fun openUrl(urlString: String): HttpURLConnection {
        var conn = URL(urlString).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/json")
        conn.instanceFollowRedirects = true
        conn.connect()
        val status = conn.responseCode
        if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
            status == HttpURLConnection.HTTP_MOVED_PERM ||
            status == 307 || status == 308
        ) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connect()
        }
        return conn
    }

    // ── New-item notification ──────────────────────────────────────────────────

    /**
     * Builds a fingerprint set for the current cache: one entry per non-empty
     * source×type bucket, encoding the latest item's versionName.
     * Format: "$sourceName::$componentType::$versionName"
     */
    fun currentFingerprints(): Set<String> {
        val result = mutableSetOf<String>()
        cache.forEach { (key, items) ->
            val first = items.firstOrNull() ?: return@forEach
            result.add("$key::${first.versionName}")
        }
        return result
    }

    /**
     * Returns the set of source names that have at least one new fingerprint
     * compared to the saved snapshot.  Empty set if no snapshot exists yet.
     */
    fun getNewSourceNames(): Set<String> {
        val seen = loadSeenFingerprints()
        if (seen.isEmpty()) return emptySet()
        return currentFingerprints()
            .filter { it !in seen }
            .mapNotNull { fp -> fp.split("::").firstOrNull() }
            .toSet()
    }

    /**
     * Returns true when the current cache contains items not present in the last
     * saved snapshot.  On first run (no saved snapshot) establishes a baseline
     * from the current cache and returns false so there is no phantom badge.
     */
    fun hasNewItems(): Boolean {
        val seen = loadSeenFingerprints()
        val current = currentFingerprints()
        if (seen.isEmpty()) {
            if (current.isNotEmpty()) saveSeenFingerprints(current)
            return false
        }
        return current.any { it !in seen }
    }

    /** Saves the current cache fingerprints as the "seen" baseline. */
    fun markAllAsSeen() {
        saveSeenFingerprints(currentFingerprints())
    }

    private fun loadSeenFingerprints(): Set<String> {
        val json = prefs.getString("seen_fingerprints", null) ?: return emptySet()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
    }

    private fun saveSeenFingerprints(fps: Set<String>) {
        val arr = JSONArray()
        fps.forEach { arr.put(it) }
        prefs.edit().putString("seen_fingerprints", arr.toString()).apply()
    }
}