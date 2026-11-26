package com.winlator.star.contents

import android.content.Context
import android.net.Uri
import android.util.Log
import com.winlator.star.ui.simple.L
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * One way in for a GPU driver zip, from a download list or a file. "GPU driver" is three things and
 * the zip's own meta.json says which ("kind"): an AdrenoTools driver (the Graphics Driver pickers),
 * a "-Linux" glibc ICD (Linux runtime drivers) or a "-Wayland" one (Wayland game drivers). Each is
 * installed where it can be used, and the result says where, or why nothing was installed.
 *
 * It also remembers which download became which driver. A download list's entry name ("v891.14 —
 * v891.14.zip") and the name inside the zip ("891.14") rarely match, so the lists used to guess
 * "Installed" from the names and were wrong both ways.
 *
 * A zip with several drivers in it (DroidDeck's DD-Turnip: android/ and linux/, listed in its
 * manifest.json) is a [Bundle]: the user picks which to install ([DriverPartsPrompt]), Android by default.
 */
object DriverInstaller {
    private const val TAG = "DriverInstaller"
    private const val PREFS = "driver_install_records"

    enum class Place { ANDROID, LINUX, WAYLAND }

    data class Result(
        /** The list the driver went into; null when nothing was installed. */
        val place: Place?,
        val id: String = "",
        val name: String = "",
        /** Why nothing was installed, ready to show. */
        val error: String? = null,
        /** A driver with the same name is installed; installing with replace = true updates it. */
        val sameName: Boolean = false,
        /** The list a same-name refusal or a failure refers to, when known (a bundle's parts). */
        val target: Place? = null,
        /** A bundle's other parts, each with its own outcome. */
        val more: List<Result> = emptyList(),
    ) {
        // A same-name refusal carries the installed driver's id but no place: nothing new was installed.
        val ok: Boolean get() = place != null && id.isNotEmpty()
    }

    /**
     * Installs [uri] into the list its meta.json names. [fileName] names a Linux or Wayland driver
     * whose zip does not. [sourceUrl] is the download it came from, remembered when the driver is
     * installed or one of its name already is. Blocking.
     */
    @JvmStatic
    @JvmOverloads
    fun install(context: Context, uri: Uri, fileName: String?, replace: Boolean = false, sourceUrl: String? = null): Result {
        val bundle = bundleOf(context, uri)
        val result = if (bundle != null) installBundle(context, uri, fileName, bundle, replace) else installOne(context, uri, fileName, replace)
        if (!sourceUrl.isNullOrBlank()) recordResult(context, sourceUrl, result)
        Log.i(TAG, "install ${fileName ?: uri}: " + (listOf(result) + result.more).joinToString(", ") {
            if (it.ok) "${it.place} ${it.id}" else "not installed (${it.error})"
        })
        return result
    }

    private fun installOne(context: Context, uri: Uri, fileName: String?, replace: Boolean): Result =
        try {
            when (zipKind(context, uri)) {
                "linux-vulkan-icd" -> LinuxVulkanDriverManager(context).let { m ->
                    val id = m.installDriver(uri, fileName)
                    Result(Place.LINUX, id, m.getDriverName(id))
                }
                "wayland-game-driver" -> WaylandGameDriverManager(context).let { m ->
                    val id = m.installDriver(uri, fileName)
                    Result(Place.WAYLAND, id, m.getDriverName(id))
                }
                else -> installAdrenotools(context, uri, replace)
            }
        } catch (e: IllegalArgumentException) {
            // The Linux and Wayland importers say what is wrong with the zip in their message.
            Result(null, error = e.message ?: e.toString())
        } catch (e: Exception) {
            Log.w(TAG, "driver install failed", e)
            Result(null, error = e.message ?: e.toString())
        }

    private fun installAdrenotools(context: Context, uri: Uri, replace: Boolean): Result {
        val m = AdrenotoolsManager(context)
        val r = m.install(uri, replace)
        return when {
            r.id.isNotEmpty() -> Result(Place.ANDROID, r.id, m.getDriverName(r.id).ifEmpty { r.id })
            r.reason == AdrenotoolsManager.InstallResult.SAME_NAME ->
                Result(null, r.detail ?: "", r.detail ?: "", error = L("%1\$s is already installed", r.detail ?: ""), sameName = true, target = Place.ANDROID)
            else -> Result(null, error = reasonText(r.reason, r.detail))
        }
    }

    // ── Zips with several drivers ────────────────────────────────────────────

    /** A zip that holds one driver per platform, each in its own folder, as its manifest.json lists them. */
    data class Bundle(val name: String, val paths: Map<Place, String>) {
        val parts: List<Place> get() = paths.keys.toList()
    }

    /**
     * The zip's [Bundle], or null when it is not one: a top-level manifest.json whose "drivers" names an
     * "android" and/or a "linux" folder (DroidDeck's DD-Turnip layout). Never throws.
     */
    @JvmStatic
    fun bundleOf(context: Context, uri: Uri): Bundle? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.trimStart('/') == "manifest.json") {
                        return@runCatching parseBundle(zis.readBytes().toString(Charsets.UTF_8))
                    }
                    entry = zis.nextEntry
                }
            }
        }
        null
    }.getOrNull()

    /** [bundleOf] for the text of a manifest.json. */
    @JvmStatic
    fun parseBundle(manifest: String): Bundle? = runCatching {
        val m = JSONObject(manifest)
        val drivers = m.optJSONObject("drivers") ?: return@runCatching null
        val paths = LinkedHashMap<Place, String>()
        for ((key, place) in listOf("android" to Place.ANDROID, "linux" to Place.LINUX)) {
            val path = drivers.optJSONObject(key)?.optString("path", "")?.trim()?.trim('/') ?: continue
            if (path.isEmpty() || path.contains("..") || path.contains('\\')) continue
            paths[place] = path
        }
        if (paths.isEmpty()) return@runCatching null
        val name = m.optString("tag", "").trim().ifEmpty {
            listOf(m.optString("package", ""), m.optString("version", "")).filter { it.isNotBlank() }.joinToString(" ")
        }
        Bundle(name, paths)
    }.getOrNull()

    private fun installBundle(context: Context, uri: Uri, fileName: String?, bundle: Bundle, replace: Boolean): Result {
        val title = bundle.name.ifEmpty { fileName?.removeSuffix(".zip") ?: "" }
        val chosen = DriverPartsPrompt.ask(title, bundle.parts)
        if (chosen.isNullOrEmpty()) return Result(null, error = L("Cancelled"))
        val results = ArrayList<Result>()
        // AdrenoTools takes the bundle's Android folder (AdrenotoolsManager.driverRoot); the Linux list its glibc library.
        if (Place.ANDROID in chosen) {
            val r = runCatching { installAdrenotools(context, uri, replace) }.getOrElse { Result(null, error = it.message ?: it.toString()) }
            results += if (r.ok) r else r.copy(target = Place.ANDROID)
        }
        if (Place.LINUX in chosen) results += installLinuxPart(context, uri, fileName, replace)
        val main = results.firstOrNull { it.ok } ?: results.firstOrNull { it.sameName } ?: results.first()
        return main.copy(more = results.filter { it !== main })
    }

    private fun installLinuxPart(context: Context, uri: Uri, fileName: String?, replace: Boolean): Result = try {
        val m = LinuxVulkanDriverManager(context)
        val id = m.installDriver(uri, fileName, replace)
        Result(Place.LINUX, id, m.getDriverName(id))
    } catch (e: LinuxVulkanDriverManager.SameNameException) {
        Result(null, e.id, e.name, error = L("%1\$s is already installed", e.name), sameName = true, target = Place.LINUX)
    } catch (e: Exception) {
        Log.w(TAG, "Linux part of a driver bundle not installed", e)
        Result(null, error = e.message ?: e.toString(), target = Place.LINUX)
    }

    /** What to tell the user about [r], in one short line. */
    @JvmStatic
    fun message(r: Result): String = (listOf(r) + r.more).joinToString("\n") { one(it, inBundle = r.more.isNotEmpty()) }

    private fun one(r: Result, inBundle: Boolean): String {
        val where = when (if (r.ok) r.place else r.target) {
            Place.LINUX -> " · " + L("Linux runtime drivers")
            Place.WAYLAND -> " · " + L("Wayland game drivers")
            Place.ANDROID -> if (inBundle) " · " + L("Android driver") else ""
            null -> ""
        }
        return when {
            r.ok -> L("Installed %1\$s", r.name)
            r.sameName -> r.error ?: ""
            else -> L("Install failed: %1\$s", r.error ?: "?")
        } + where
    }

    private fun reasonText(reason: String?, detail: String?): String = when (reason) {
        AdrenotoolsManager.InstallResult.NO_META -> L("No meta.json in the zip")
        AdrenotoolsManager.InstallResult.BAD_META -> L("Its meta.json can't be read")
        AdrenotoolsManager.InstallResult.NO_LIBRARY -> L("Its meta.json names no library")
        AdrenotoolsManager.InstallResult.LIBRARY_MISSING -> L("%1\$s is not in the zip", detail ?: "")
        AdrenotoolsManager.InstallResult.BUILT_IN -> L("Same name as a built-in driver")
        // Routed by kind before AdrenoTools sees it; only a zip whose meta.json moved between reads lands here.
        AdrenotoolsManager.InstallResult.LINUX_KIND -> L("Linux runtime drivers")
        AdrenotoolsManager.InstallResult.GLIBC -> L("It is a Linux driver: import it under Linux runtime drivers")
        AdrenotoolsManager.InstallResult.WAYLAND_KIND -> L("Wayland game drivers")
        else -> detail ?: "?"
    }

    /** The zip's meta.json "kind" (wherever meta.json sits in it), or "" when it has none. Never throws. */
    @JvmStatic
    fun zipKind(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            java.util.zip.ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && File(entry.name).name == "meta.json") {
                        val text = zis.readBytes().toString(Charsets.UTF_8)
                        return@runCatching JSONObject(text).optString("kind", "")
                    }
                    entry = zis.nextEntry
                }
            }
        }
        ""
    }.getOrDefault("")

    // ── Which download became which driver ──────────────────────────────────────

    /** A download URL without its query, the key a list entry and its record share. */
    @JvmStatic
    fun normUrl(url: String): String = url.substringBefore('#').substringBefore('?').trim()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // A record is "PLACE|id", or several joined by ';' for a bundle's parts.
    private fun recordResult(context: Context, url: String, r: Result) {
        val parts = (listOf(r) + r.more).mapNotNull { x ->
            when {
                x.ok -> x.place!!.name + "|" + x.id
                // Refused because a driver of the same name is there: that is what this entry installs, so the
                // list says Installed (and offers Download and replace) instead of offering the same refusal again.
                x.sameName && x.id.isNotEmpty() -> (x.target ?: Place.ANDROID).name + "|" + x.id
                else -> null
            }
        }
        if (parts.isNotEmpty()) record(context, url, parts)
    }

    private fun record(context: Context, url: String, values: List<String>) {
        val key = normUrl(url)
        val p = prefs(context)
        val edit = p.edit()
        // A replaced driver keeps its id: the entry it came from before is no longer what is installed.
        p.all.forEach { (k, v) ->
            if (k == key) return@forEach
            val old = (v as? String)?.split(';')?.filter { it.isNotEmpty() } ?: return@forEach
            val kept = old.filter { it !in values }
            if (kept.size != old.size) { if (kept.isEmpty()) edit.remove(k) else edit.putString(k, kept.joinToString(";")) }
        }
        // This entry's parts from an earlier install stay unless this one put a driver in the same list.
        val places = values.map { it.substringBefore('|') }.toSet()
        val earlier = (p.getString(key, null) ?: "").split(';').filter { it.isNotEmpty() && it.substringBefore('|') !in places }
        edit.putString(key, (earlier + values).joinToString(";")).apply()
    }

    /** The download URLs whose driver is still installed, normalized. Lists three folders: call off the main thread. */
    @JvmStatic
    fun installedSourceUrls(context: Context): Set<String> {
        val all = prefs(context).all
        if (all.isEmpty()) return emptySet()
        val android = runCatching { AdrenotoolsManager(context).enumarateInstalledDrivers().toSet() }.getOrDefault(emptySet())
        val linux = runCatching { LinuxVulkanDriverManager(context).enumerateInstalledDrivers().toSet() }.getOrDefault(emptySet())
        val wayland = runCatching { WaylandGameDriverManager(context).enumerateInstalledDrivers().toSet() }.getOrDefault(emptySet())
        return all.mapNotNull { (url, v) ->
            val s = v as? String ?: return@mapNotNull null
            val there = s.split(';').filter { it.isNotEmpty() }.any { part ->
                val id = part.substringAfter('|')
                when (part.substringBefore('|')) {
                    Place.ANDROID.name -> id in android
                    Place.LINUX.name -> id in linux
                    Place.WAYLAND.name -> id in wayland
                    else -> false
                }
            }
            if (there) url else null
        }.toSet()
    }
}
