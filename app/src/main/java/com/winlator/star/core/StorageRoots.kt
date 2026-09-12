package com.winlator.star.core

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

/**
 * A storage volume offered in the file manager's drive menu.
 *
 * [dir] is the best path we found for the volume: its root when that is listable, otherwise the
 * deepest directory we can actually read on the way down to the app-specific directory. A volume
 * always gets exactly one entry, even if several paths lead to it.
 */
data class StorageRoot(
    val label: String,
    val dir: File,
    val removable: Boolean,
    /** False when no path on the volume could be listed; the entry is still shown. */
    val readable: Boolean,
)

/**
 * Enumerates mounted storage volumes.
 *
 * Listing `/storage` is not enough on its own: a volume can be absent from this process's mount
 * view (notably after a container exits and the app process is restarted onto a stale storage
 * sandbox) even though it is mounted and healthy. The framework knows about it regardless, so the
 * volume set is built from several independent sources:
 *
 *  1. [StorageManager.getStorageVolumes] — the authoritative list, read over Binder.
 *  2. [Context.getExternalFilesDirs] — per-app directories, granted separately from shared storage.
 *  3. `/storage` and `/mnt/media_rw` directory listings — the filesystem view, as a backstop.
 *
 * Those sources overlap heavily: one SD card is reachable as `/storage/<uuid>`, as
 * `/mnt/media_rw/<uuid>`, and via its app-specific directory, and typically only some of them are
 * readable. So paths are grouped by the *volume* they belong to rather than by path, and each
 * volume contributes a single entry pointing at the best path available for it.
 */
object StorageRoots {

    private const val EMULATED_PREFIX = "/storage/emulated"
    private const val INTERNAL_PATH = "/storage/emulated/0"

    /** Identifies the physical volume a path belongs to; paths sharing a key are the same card. */
    private const val PRIMARY_KEY = "primary"

    private class Volume(val key: String) {
        var label: String? = null
        var removable: Boolean = key != PRIMARY_KEY
        /** Candidate directories, best-guess first. */
        val candidates = LinkedHashSet<File>()
        /** App-specific directories, used to find a readable path inside an unreadable root. */
        val seeds = LinkedHashSet<File>()
    }

    fun list(context: Context): List<StorageRoot> {
        val volumes = LinkedHashMap<String, Volume>()

        fun volumeOf(key: String) = volumes.getOrPut(key) { Volume(key) }

        // Internal storage is never removable and never absent; seed it first so it heads the menu.
        volumeOf(PRIMARY_KEY).apply {
            label = "Internal"
            removable = false
            candidates += File(INTERNAL_PATH)
        }

        val appDirs = appSpecificDirs(context)
        val storageManager = context.getSystemService(StorageManager::class.java)

        // 1 + 2 — framework-reported volumes. Authoritative, and unaffected by our mount view.
        storageManager?.storageVolumes.orEmpty()
            .filter { isMounted(it.state) }
            .forEach { volume ->
                val uuid = volume.uuid?.takeIf { it.isNotBlank() }
                val entry = volumeOf(if (volume.isPrimary) PRIMARY_KEY else uuid ?: return@forEach)
                entry.removable = !volume.isPrimary && volume.isRemovable
                if (entry.label == null) entry.label = labelFor(context, volume, uuid)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    volume.directory?.let { entry.candidates += it }
                }
                if (uuid != null) {
                    entry.candidates += File("/storage/$uuid")
                    // Last resort only: the raw mount is normally unreadable to an app, so it must
                    // never outrank /storage/<uuid> and must never become an entry of its own.
                    entry.candidates += File("/mnt/media_rw/$uuid")
                }
                appDirs.filter { belongsTo(storageManager!!, it, volume) }.forEach { entry.seeds += it }
            }

        // 3 — filesystem backstop, for any volume the framework did not report.
        File("/storage").listFiles().orEmpty().forEach { child ->
            if (!child.isDirectory || child.name == "self" || child.name == "emulated") return@forEach
            volumeOf(child.name).candidates += child
        }
        File("/mnt/media_rw").listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) volumeOf(child.name).candidates += child
        }

        // App-specific directories, which stay reachable when shared storage is not.
        appDirs.forEach { dir ->
            val root = volumeRootOf(dir) ?: return@forEach
            val entry = volumeOf(if (isEmulated(root)) PRIMARY_KEY else root.name)
            entry.candidates += root
            entry.seeds += dir
        }

        val roots = volumes.values.map { it.toRoot() }
        val internal = roots.filter { !it.removable }
        val removable = roots.filter { it.removable }.sortedBy { it.label }
        return disambiguate(internal + removable)
    }

    private fun Volume.toRoot(): StorageRoot {
        // Prefer a path we can actually list; a readable child beats an unreadable root.
        val dir = candidates.firstOrNull(::canBrowse)
            ?: candidates.firstNotNullOfOrNull { root -> seeds.firstNotNullOfOrNull { deepestReadable(root, it) } }
            ?: seeds.firstOrNull(::canBrowse)
            // Report the volume anyway; an entry that lists empty beats one that silently vanished.
            ?: candidates.firstOrNull()
            ?: File("/storage/$key")

        return StorageRoot(
            label = label ?: defaultLabel(),
            dir = dir,
            removable = removable,
            readable = canBrowse(dir),
        )
    }

    private fun Volume.defaultLabel(): String = if (removable) "SD card" else key

    /** Appends the volume id to any label used by more than one volume, so entries stay tellable apart. */
    private fun disambiguate(roots: List<StorageRoot>): List<StorageRoot> {
        val counts = roots.groupingBy { it.label }.eachCount()
        return roots.map { root ->
            if (counts.getValue(root.label) == 1) root else root.copy(label = "${root.label} (${root.dir.name})")
        }
    }

    /** Walks up from [seed] towards [root] and returns the highest directory we can list. */
    private fun deepestReadable(root: File, seed: File): File? {
        var current: File? = seed.absoluteFile
        var best: File? = null
        while (current != null && isWithin(current, root)) {
            if (canBrowse(current)) best = current
            current = current.parentFile
        }
        return best
    }

    /** `/storage/ABCD-1234/Android/data/<pkg>/files` -> `/storage/ABCD-1234`. */
    private fun volumeRootOf(appDir: File): File? =
        generateSequence(appDir.absoluteFile) { it.parentFile }
            .firstOrNull { it.name.equals("Android", ignoreCase = true) }
            ?.parentFile

    private fun appSpecificDirs(context: Context): List<File> =
        context.getExternalFilesDirs(null)
            .orEmpty()
            .filterNotNull()
            .filter { isMounted(Environment.getExternalStorageState(it)) }

    private fun belongsTo(storageManager: StorageManager, dir: File, volume: StorageVolume): Boolean {
        val owner = storageManager.getStorageVolume(dir) ?: return false
        if (owner.isPrimary != volume.isPrimary) return false
        val ownerUuid = owner.uuid
        val volumeUuid = volume.uuid
        return if (!ownerUuid.isNullOrBlank() || !volumeUuid.isNullOrBlank()) {
            ownerUuid == volumeUuid
        } else {
            true
        }
    }

    /**
     * Names a volume the way the rest of the file manager does. The framework description is only
     * used when it says something more useful than "SD card" — on some devices it is a bare disk
     * name like "android", which tells the user nothing about which card they are looking at.
     */
    private fun labelFor(context: Context, volume: StorageVolume, uuid: String?): String {
        if (volume.isPrimary) return "Internal"
        if (volume.isRemovable) return "SD card"
        val description = volume.getDescription(context)?.trim()
        return description?.takeIf { it.isNotBlank() && !it.equals("android", ignoreCase = true) }
            ?: uuid
            ?: "Storage"
    }

    private fun isEmulated(dir: File): Boolean = dir.absolutePath.startsWith(EMULATED_PREFIX)

    private fun isMounted(state: String?): Boolean =
        state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    private fun canBrowse(dir: File?): Boolean =
        dir != null && dir.isDirectory && dir.canRead() && dir.listFiles() != null

    private fun isWithin(child: File, ancestor: File): Boolean {
        val c = canonicalPath(child)
        val a = canonicalPath(ancestor)
        return c == a || c.startsWith("$a/")
    }

    private fun canonicalPath(file: File): String =
        try {
            file.canonicalPath
        } catch (e: Exception) {
            file.absolutePath
        }.trimEnd('/')
}
