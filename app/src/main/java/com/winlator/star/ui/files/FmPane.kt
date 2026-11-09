package com.winlator.star.ui.files

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.core.unpack.ArchiveException
import com.winlator.star.core.unpack.ArchiveIndex
import com.winlator.star.core.unpack.ArchiveIndexCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What a picker may show and return. Null = the full file manager. */
data class FmPick(val dirsOnly: Boolean, val extensions: List<String>) {
    fun accepts(file: File): Boolean {
        if (dirsOnly) return false
        if (extensions.isEmpty()) return true
        val n = file.name.lowercase()
        return extensions.any { n.endsWith(".$it") }
    }
}

/**
 * One browsing pane: where it is, what it lists, the selection and the search. Two of these side by side
 * make the dual-pane view; they share the clipboard and settings but nothing else.
 */
@Stable
class FmPane(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pick: FmPick?,
    /** Folders Up stops at before going to Home: storage roots, container drives, the system drive. */
    private val floors: () -> List<File>,
) {
    var loc by mutableStateOf<FmLoc>(FmLoc.Home)
        private set
    var items by mutableStateOf<List<FmItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var index by mutableStateOf<ArchiveIndex?>(null)
        private set
    /** An archive waiting for its password; [passwordWrong] when the last one failed. */
    var passwordFor by mutableStateOf<File?>(null)
    var passwordWrong by mutableStateOf(false)
    /** An archive 7-Zip can't list (a raw .bin/.img): the file manager sends it to the Unpack screen. */
    var unlistable by mutableStateOf<File?>(null)

    var selectionMode by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())

    var query by mutableStateOf("")
    var deepResults by mutableStateOf<List<FmItem>?>(null)
        private set
    var deepSearching by mutableStateOf(false)
        private set

    var sort by mutableStateOf(FmPrefs.sort(context))
    var viewMode by mutableStateOf(FmPrefs.viewMode(context))
    var showHidden by mutableStateOf(FmPrefs.showHidden(context))

    val listState = LazyListState()
    val gridState = LazyGridState()
    /** Bumped on every listing so rows re-read lazy details (item counts). */
    var listingTick by mutableIntStateOf(0)
        private set
    val childCounts = mutableStateMapOf<String, Int>()

    private val history = ArrayDeque<FmLoc>()
    private val scrollMemory = HashMap<FmLoc, Int>()
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    val currentDir: File? get() = (loc as? FmLoc.Dir)?.dir
    val inArchive: Boolean get() = loc is FmLoc.InArchive
    val canGoBack: Boolean get() = history.isNotEmpty()

    /** What the list shows: the listing filtered by the search box, or the subfolder search results. */
    fun shown(): List<FmItem> {
        deepResults?.let { return it }
        val q = query.trim()
        return if (q.isEmpty()) items else items.filter { it.name.contains(q, ignoreCase = true) }
    }

    fun open(target: FmLoc, remember: Boolean = true) {
        if (target == loc && items.isNotEmpty()) return refresh()
        if (remember && loc != target) {
            scrollMemory[loc] = currentScroll()
            history.addLast(loc)
            if (history.size > 64) history.removeFirst()
        }
        clearSearch()
        selectionMode = false
        selected = emptySet()
        loc = target
        load(resetScroll = true, restore = scrollMemory.remove(target))
    }

    fun openDir(dir: File) = open(FmLoc.Dir(dir))

    /** Back: the previous place, if any. */
    fun goBack(): Boolean {
        val prev = history.removeLastOrNull() ?: return false
        clearSearch()
        selectionMode = false
        selected = emptySet()
        loc = prev
        load(resetScroll = true, restore = scrollMemory.remove(prev))
        return true
    }

    /** One level up; from a storage root or a container drive, Home. False when already Home. */
    fun up(): Boolean {
        val parent: FmLoc = when (val l = loc) {
            FmLoc.Home -> return false
            is FmLoc.InArchive ->
                if (l.inner.isEmpty()) l.archive.parentFile?.let { FmLoc.Dir(it) } ?: FmLoc.Home
                else FmLoc.InArchive(l.archive, l.inner.substringBeforeLast('/', ""))
            is FmLoc.Dir -> {
                val d = l.dir
                val atFloor = floors().any { it.absolutePath == d.absolutePath }
                val p = d.parentFile
                if (atFloor || p == null || !p.canRead()) FmLoc.Home else FmLoc.Dir(p)
            }
        }
        open(parent)
        return true
    }

    fun refresh() = load(resetScroll = false, restore = null)

    fun toggleSelect(item: FmItem) {
        selected = if (item.key in selected) selected - item.key else selected + item.key
        if (selected.isEmpty()) selectionMode = false
    }

    fun startSelect(item: FmItem) {
        selectionMode = true
        selected = selected + item.key
    }

    fun selectAll() {
        val all = shown().map { it.key }.toSet()
        selected = if (selected.containsAll(all)) emptySet() else all
        selectionMode = selected.isNotEmpty()
    }

    fun clearSelection() {
        selectionMode = false
        selected = emptySet()
    }

    fun selectedItems(): List<FmItem> {
        val keys = selected
        return (deepResults ?: items).filter { it.key in keys }
    }

    fun clearSearch() {
        searchJob?.cancel()
        query = ""
        deepResults = null
        deepSearching = false
    }

    /** Searches every folder below the current one for names containing the query (real folders only). */
    fun searchSubfolders() {
        val root = currentDir ?: return
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        deepSearching = true
        deepResults = emptyList()
        searchJob = scope.launch {
            val found = ArrayList<FmItem>()
            withContext(Dispatchers.IO) {
                // A folder listed twice (#659) is walked and reported once.
                val seen = HashSet<String>()
                val stack = ArrayDeque<File>().apply { add(root) }
                var n = 0
                while (stack.isNotEmpty() && isActive && found.size < 2000) {
                    val d = stack.removeLast()
                    val kids = d.listFiles() ?: continue
                    for (k in kids) {
                        if (!showHidden && k.name.startsWith(".")) continue
                        if (!seen.add(k.absolutePath)) continue
                        val link = runCatching { java.nio.file.Files.isSymbolicLink(k.toPath()) }.getOrDefault(false)
                        if (k.isDirectory && !link) stack.add(k)
                        if (k.name.contains(q, ignoreCase = true) && admits(k)) found.add(diskItem(k))
                    }
                    if (++n % 64 == 0) {
                        val snapshot = found.toList()
                        withContext(Dispatchers.Main) { deepResults = snapshot }
                    }
                }
            }
            deepResults = found.toList().sortedFor(sort)
            deepSearching = false
        }
    }

    fun applySort(s: FmSort) {
        sort = s
        FmPrefs.setSort(context, s)
        items = items.sortedFor(s)
        deepResults = deepResults?.sortedFor(s)
    }

    fun applyViewMode(m: FmViewMode) {
        viewMode = m
        FmPrefs.setViewMode(context, m)
    }

    fun applyShowHidden(v: Boolean) {
        showHidden = v
        FmPrefs.setShowHidden(context, v)
        refresh()
    }

    /** Called after the password dialog: retries the archive. */
    fun retryWithPassword() {
        passwordFor = null
        refresh()
    }

    private fun admits(f: File): Boolean = when {
        pick == null -> true
        f.isDirectory -> true
        else -> pick.accepts(f)
    }

    private fun currentScroll(): Int =
        if (viewMode == FmViewMode.GRID) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex

    private fun load(resetScroll: Boolean, restore: Int?) {
        loadJob?.cancel()
        val target = loc
        loadJob = scope.launch {
            // Only show the spinner when a listing takes a moment (a big folder, an archive's first index).
            val spinner = launch { delay(150); loading = true }
            error = null
            when (target) {
                FmLoc.Home -> {
                    items = emptyList()
                    index = null
                }
                is FmLoc.Dir -> {
                    index = null
                    val hidden = showHidden
                    val list = withContext(Dispatchers.IO) {
                        val files = target.dir.listFiles()
                        // Some phones' storage lists a folder twice (#659); one row per path.
                        files?.asSequence()
                            ?.distinctBy { it.absolutePath }
                            ?.filter { hidden || !it.name.startsWith(".") }
                            ?.filter { pick == null || (if (pick.dirsOnly) it.isDirectory else admits(it)) }
                            ?.map(::diskItem)
                            ?.toList()
                            ?.sortedFor(sort)
                    }
                    if (list == null) error = if (target.dir.exists()) "Can't open this folder" else "This folder is gone"
                    items = list ?: emptyList()
                    childCounts.clear()
                }
                is FmLoc.InArchive -> {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ArchiveIndexCache.loadBlocking(context, target.archive) }
                    }
                    result.onSuccess { idx ->
                        index = idx
                        val tree = idx.tree
                        items = tree.children(target.inner)
                            .filter { showHidden || !it.name.startsWith(".") }
                            .map { e -> FmItem.Entry(target.archive, e, tree.sizeOf(e)) }
                            .distinctBy { it.key }
                            .sortedFor(sort)
                        error = idx.warning
                    }.onFailure { t ->
                        items = emptyList()
                        index = null
                        when (t) {
                            is ArchiveException.PasswordNeeded -> {
                                passwordWrong = t.wrong
                                passwordFor = target.archive
                                error = if (t.wrong) "Wrong password" else "This archive is password-protected"
                            }
                            is ArchiveException.NotArchive -> {
                                error = t.message ?: "7-Zip can't list this file"
                                unlistable = target.archive
                            }
                            else -> error = t.message ?: "Couldn't open ${target.archive.name}"
                        }
                    }
                }
            }
            spinner.cancel()
            loading = false
            listingTick++
            if (resetScroll) {
                val to = restore ?: 0
                runCatching { listState.scrollToItem(to) }
                runCatching { gridState.scrollToItem(to) }
            }
        }
    }
}
