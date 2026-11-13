package com.winlator.star.sound

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * One installed sound pack: a folder of sound files named after Steam's UI sounds, with an optional
 * `pack.json` in the Audio Loader format (DeckThemes/SDH-AudioLoader, MIT License, Copyright (c) 2022
 * EMERALD0874): name, author, version, description, manifest_version, ignore, mappings, music.
 * A folder without `pack.json` (our Steam Big Picture zip) is a pack of plain Steam-named files.
 */
class UiSoundPack(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val folder: File,
    val ignore: Set<String>,
    val mappings: Map<String, List<String>>,
    val music: Boolean,
) {
    /**
     * The files this pack plays for Steam sound [steamName], as Audio Loader picks them: mapped files when
     * the pack maps the name (one is picked at random each time), else the file of that name; nothing
     * when the pack ignores the name or lacks the file (the base pack's sound plays then).
     */
    fun filesFor(steamName: String): List<File> {
        if (steamName in ignore) return emptyList()
        mappings[steamName]?.mapNotNull { inside(it) }?.takeIf { it.isNotEmpty() }?.let { return it }
        return listOfNotNull(inside(steamName))
    }

    private fun inside(relative: String): File? {
        val f = File(folder, relative).canonicalFile
        return f.takeIf { it.isFile && it.path.startsWith(folder.canonicalPath + File.separator) }
    }

    companion object {
        const val MANIFEST = "pack.json"

        /** Highest pack.json manifest_version understood (Audio Loader 3). */
        const val MANIFEST_VERSION = 3

        private const val MAX_ENTRIES = 400
        private const val MAX_UNPACKED = 120L * 1024 * 1024
        private val KEEP = setOf("json", "wav", "ogg", "mp3", "m4a", "flac", "txt", "md", "png", "jpg")

        /** Reads the pack in [folder]; [fallbackName] names a pack without `pack.json`. Null when it is not a usable pack. */
        fun read(folder: File, id: String, fallbackName: String): UiSoundPack? {
            val manifest = File(folder, MANIFEST)
            if (!manifest.isFile) {
                val hasSounds = UiCue.entries.any { c -> c.files.any { File(folder, it).isFile } }
                return if (hasSounds) UiSoundPack(id, fallbackName, "", "", folder, emptySet(), emptyMap(), false) else null
            }
            val j = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return null
            if (j.optInt("manifest_version", 1) > MANIFEST_VERSION) return null
            val ignore = j.optJSONArray("ignore")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
            val mappings = LinkedHashMap<String, List<String>>()
            j.optJSONObject("mappings")?.let { m ->
                for (k in m.keys()) {
                    val v = m.opt(k)
                    mappings[k] = when (v) {
                        is org.json.JSONArray -> (0 until v.length()).map { v.optString(it) }.filter { it.isNotEmpty() }
                        is String -> listOf(v)
                        else -> emptyList()
                    }
                }
            }
            return UiSoundPack(
                id = id,
                name = j.optString("name").ifBlank { fallbackName },
                author = j.optString("author"),
                version = j.optString("version"),
                folder = folder,
                ignore = ignore,
                mappings = mappings,
                music = j.optBoolean("music", false),
            )
        }

        /**
         * Unpacks [zip] into the empty folder [dest] (size and entry caps, no paths outside [dest], only
         * sound/text/picture files) and returns the pack root inside it: the folder holding `pack.json`,
         * or else the shallowest folder holding Steam-named sound files.
         */
        fun unpack(zip: File, dest: File): File {
            val root = dest.canonicalFile
            root.mkdirs()
            var entries = 0
            var total = 0L
            ZipInputStream(FileInputStream(zip).buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (++entries > MAX_ENTRIES) throw IOException("too many files in the zip")
                    val out = File(root, e.name).canonicalFile
                    val name = out.name
                    val skip = e.isDirectory || name.startsWith("._") || e.name.contains("__MACOSX") ||
                        name.substringAfterLast('.', "").lowercase() !in KEEP ||
                        !out.path.startsWith(root.path + File.separator)
                    if (!skip) {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = z.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_UNPACKED) throw IOException("the zip unpacks to more than ${MAX_UNPACKED / (1024 * 1024)} MB")
                                o.write(buf, 0, n)
                            }
                        }
                    }
                    e = z.nextEntry
                }
            }
            return findRoot(root) ?: throw IOException("no sound pack in the zip")
        }

        private fun findRoot(dir: File): File? {
            val queue = ArrayDeque(listOf(dir))
            var bySounds: File? = null
            while (queue.isNotEmpty()) {
                val d = queue.removeFirst()
                if (File(d, MANIFEST).isFile) return d
                if (bySounds == null && UiCue.entries.any { c -> c.files.any { File(d, it).isFile } }) bySounds = d
                d.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.forEach { queue.addLast(it) }
            }
            return bySounds
        }
    }
}
