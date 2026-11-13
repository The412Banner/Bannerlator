package com.winlator.star.store

import android.content.Context
import android.util.Log
import com.winlator.star.core.StorageRoots
import com.winlator.star.core.WinePath
import org.json.JSONArray
import java.io.File

/**
 * Where Epic, GOG and Amazon games download to: internal storage (as before), every removable volume that is
 * mounted (SD card, USB drive) and folders the user picked. Each place keeps the store's own folder name inside it
 * (`epic_games`, `gog_games`, `Amazon`) because store badges, cloud saves and launch detection key off that
 * segment. The default per store is remembered; a default whose volume is gone falls back to internal.
 *
 * The same roots are the only places a game folder of that store may be deleted from ([safeDelete]), so an
 * uninstall or a cancelled download can never remove a library root, a storage root or a folder the user brought.
 */
object StoreInstallLocations {
    private const val TAG = "StoreInstallLocations"
    private const val PREFS = "store_install_locations"
    private const val KEY_CUSTOM = "custom_folders"
    const val ID_INTERNAL = "internal"
    private const val VOL_PREFIX = "vol:"
    private const val CUSTOM_PREFIX = "custom:"
    private const val EXTERNAL_SUBDIR = "bannerlator"

    enum class Store(val key: String, val segment: String) {
        EPIC("epic", "epic_games"),
        GOG("gog", "gog_games"),
        AMAZON("amazon", "Amazon");

        fun internalBase(ctx: Context): File = File(ctx.filesDir, "imagefs/$segment")
    }

    enum class Kind { INTERNAL, EXTERNAL, CUSTOM }

    class Location(
        val id: String,
        val kind: Kind,
        val label: String,
        /** The volume root or the folder the user picked. */
        val root: File,
        /** Where this store's games go: `<root>/…/<segment>`. */
        val base: File,
        val available: Boolean,
        val freeBytes: Long,
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun customFolders(ctx: Context): List<String> = try {
        val a = JSONArray(prefs(ctx).getString(KEY_CUSTOM, "[]"))
        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
    } catch (_: Throwable) { emptyList() }

    private fun saveCustom(ctx: Context, list: List<String>) {
        prefs(ctx).edit().putString(KEY_CUSTOM, JSONArray(list.distinct()).toString()).apply()
    }

    private fun free(dir: File): Long {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return try { d?.usableSpace ?: 0L } catch (_: Throwable) { 0L }
    }

    /** Internal first, then each mounted removable volume, then the user's folders. Blocking (reads volumes). */
    fun locations(ctx: Context, store: Store): List<Location> {
        val out = ArrayList<Location>()
        val internal = store.internalBase(ctx)
        out += Location(ID_INTERNAL, Kind.INTERNAL, "Internal storage", ctx.filesDir, internal, true, free(internal))
        val roots = try { StorageRoots.list(ctx) } catch (_: Throwable) { emptyList() }
        for (r in roots) {
            if (!r.removable) continue
            val volRoot = WinePath.storageVolumeRootOf(r.dir.absolutePath) ?: continue
            if (volRoot != r.dir.absolutePath) continue
            val root = File(volRoot)
            val base = File(File(root, EXTERNAL_SUBDIR), store.segment)
            out += Location(VOL_PREFIX + volRoot, Kind.EXTERNAL, r.label.ifBlank { root.name }, root, base, root.canWrite() || base.canWrite(), free(root))
        }
        for (path in customFolders(ctx)) {
            val root = File(path)
            val base = if (root.name == store.segment) root else File(root, store.segment)
            out += Location(CUSTOM_PREFIX + path, Kind.CUSTOM, root.name.ifBlank { path }, root, base, root.isDirectory, free(root))
        }
        return out
    }

    /** The default location's id for [store], or [ID_INTERNAL] when unset or no longer available. */
    fun defaultId(ctx: Context, store: Store, locations: List<Location>? = null): String {
        val id = prefs(ctx).getString("default_" + store.key, ID_INTERNAL) ?: ID_INTERNAL
        val list = locations ?: locations(ctx, store)
        return if (list.any { it.id == id && it.available }) id else ID_INTERNAL
    }

    fun setDefault(ctx: Context, store: Store, id: String) {
        prefs(ctx).edit().putString("default_" + store.key, id).apply()
    }

    /** Adds a folder the user picked; returns an error message, or null when it was added (or already there). */
    fun addCustom(ctx: Context, path: String): String? {
        val dir = File(path).absoluteFile
        if (!dir.isDirectory) return "That folder doesn't exist."
        val vol = WinePath.storageVolumeRootOf(dir.absolutePath) ?: return "Pick a folder on internal storage, an SD card or a USB drive."
        if (dir.absolutePath.trimEnd('/') == vol.trimEnd('/')) return "Pick or make a folder inside the drive, not the whole drive."
        val probe = File(dir, ".bannerlator_write_probe")
        val ok = try { probe.writeText("1"); true } catch (_: Throwable) { false } finally { probe.delete() }
        if (!ok) return "Bannerlator can't write to that folder."
        val list = customFolders(ctx)
        if (dir.absolutePath !in list) saveCustom(ctx, list + dir.absolutePath)
        return null
    }

    fun customId(path: String): String = CUSTOM_PREFIX + File(path).absolutePath

    fun removeCustom(ctx: Context, path: String) = saveCustom(ctx, customFolders(ctx) - path)

    /** Every folder this store's games may live in (for delete checks), including the legacy Amazon base. */
    fun knownBases(ctx: Context, store: Store): List<File> {
        val bases = ArrayList<File>()
        bases += store.internalBase(ctx)
        if (store == Store.AMAZON) bases += File(ctx.filesDir, "Amazon")
        try {
            for (r in StorageRoots.list(ctx)) {
                if (!r.removable) continue
                val vol = WinePath.storageVolumeRootOf(r.dir.absolutePath) ?: continue
                bases += File(File(File(vol), EXTERNAL_SUBDIR), store.segment)
            }
        } catch (_: Throwable) {}
        for (path in customFolders(ctx)) {
            val root = File(path)
            bases += if (root.name == store.segment) root else File(root, store.segment)
        }
        return bases
    }

    private fun canon(f: File): String = try { f.canonicalPath } catch (_: Throwable) { f.absolutePath }

    /**
     * True when [dir] is one game's folder inside one of [store]'s bases: strictly below a base (never a base,
     * its parent or a storage root) and not a folder the user brought (`.bannerlator_adopted`).
     */
    fun isGameFolder(ctx: Context, store: Store, dir: File): Boolean {
        if (dir.path.isBlank()) return false
        if (File(dir, SteamInstallPaths.ADOPTED_MARKER).exists()) return false
        val d = canon(dir).trimEnd('/')
        return knownBases(ctx, store).any { b ->
            val base = canon(b).trimEnd('/')
            d.startsWith("$base/") && d.length > base.length + 1
        }
    }

    /** Deletes a game folder of [store] only when [isGameFolder] agrees; otherwise keeps it. True when gone. */
    fun safeDelete(ctx: Context, store: Store, dir: File?): Boolean {
        if (dir == null || !dir.exists()) return true
        if (!isGameFolder(ctx, store, dir)) {
            Log.w(TAG, "refusing to delete ${dir.absolutePath}: not a ${store.key} game folder inside a known location")
            return false
        }
        dir.deleteRecursively()
        return !dir.exists()
    }

    /**
     * True when [path] is on a removable volume that is not mounted right now (card out, USB unplugged): a missing
     * game folder there does not mean the game is gone, so its install record must be kept.
     */
    fun onMissingVolume(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val vol = WinePath.storageVolumeRootOf(path) ?: return false
        if (vol.startsWith("/storage/emulated")) return false
        return !File(vol).isDirectory
    }

    /** The game folder [name] for a fresh install at [location]. */
    fun gameDir(location: Location, name: String): File = File(location.base, name)
}
