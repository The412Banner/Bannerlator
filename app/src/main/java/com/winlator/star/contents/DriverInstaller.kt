package com.winlator.star.contents

import android.content.Context
import android.net.Uri
import android.util.Log
import com.winlator.star.ui.simple.L
import org.json.JSONObject
import java.io.File

/**
 * One way in for a GPU driver zip, from a download list or a file. "GPU driver" is three things and
 * the zip's own meta.json says which ("kind"): an AdrenoTools driver (the Graphics Driver pickers),
 * a "-Linux" glibc ICD (Linux runtime drivers) or a "-Wayland" one (Wayland game drivers). Each is
 * installed where it can be used, and the result says where, or why nothing was installed.
 *
 * It also remembers which download became which driver. A download list's entry name ("v891.14 —
 * v891.14.zip") and the name inside the zip ("891.14") rarely match, so the lists used to guess
 * "Installed" from the names and were wrong both ways.
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
        val result = try {
            when (zipKind(context, uri)) {
                "linux-vulkan-icd" -> LinuxVulkanDriverManager(context).let { m ->
                    val id = m.installDriver(uri, fileName)
                    Result(Place.LINUX, id, m.getDriverName(id))
                }
                "wayland-game-driver" -> WaylandGameDriverManager(context).let { m ->
                    val id = m.installDriver(uri, fileName)
                    Result(Place.WAYLAND, id, m.getDriverName(id))
                }
                else -> {
                    val m = AdrenotoolsManager(context)
                    val r = m.install(uri, replace)
                    when {
                        r.id.isNotEmpty() -> Result(Place.ANDROID, r.id, m.getDriverName(r.id).ifEmpty { r.id })
                        r.reason == AdrenotoolsManager.InstallResult.SAME_NAME ->
                            Result(null, r.detail ?: "", r.detail ?: "", error = L("%1\$s is already installed", r.detail ?: ""), sameName = true)
                        else -> Result(null, error = reasonText(r.reason, r.detail))
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            // The Linux and Wayland importers say what is wrong with the zip in their message.
            Result(null, error = e.message ?: e.toString())
        } catch (e: Exception) {
            Log.w(TAG, "driver install failed", e)
            Result(null, error = e.message ?: e.toString())
        }
        if (!sourceUrl.isNullOrBlank()) {
            if (result.ok) record(context, sourceUrl, result.place!!, result.id)
            // Refused because a driver of the same name is there: that is what this entry installs, so the
            // list says Installed (and offers Download and replace) instead of offering the same refusal again.
            else if (result.sameName && result.id.isNotEmpty()) record(context, sourceUrl, Place.ANDROID, result.id)
        }
        Log.i(TAG, "install ${fileName ?: uri}: " + if (result.ok) "${result.place} ${result.id}" else "not installed (${result.error})")
        return result
    }

    /** What to tell the user about [r], in one short line. */
    @JvmStatic
    fun message(r: Result): String = when {
        r.ok -> L("Installed %1\$s", r.name) + when (r.place) {
            Place.LINUX -> " · " + L("Linux runtime drivers")
            Place.WAYLAND -> " · " + L("Wayland game drivers")
            else -> ""
        }
        r.sameName -> r.error ?: ""
        else -> L("Install failed: %1\$s", r.error ?: "?")
    }

    private fun reasonText(reason: String?, detail: String?): String = when (reason) {
        AdrenotoolsManager.InstallResult.NO_META -> L("No meta.json in the zip")
        AdrenotoolsManager.InstallResult.BAD_META -> L("Its meta.json can't be read")
        AdrenotoolsManager.InstallResult.NO_LIBRARY -> L("Its meta.json names no library")
        AdrenotoolsManager.InstallResult.LIBRARY_MISSING -> L("%1\$s is not in the zip", detail ?: "")
        AdrenotoolsManager.InstallResult.BUILT_IN -> L("Same name as a built-in driver")
        // Routed by kind before AdrenoTools sees it; only a zip whose meta.json moved between reads lands here.
        AdrenotoolsManager.InstallResult.LINUX_KIND -> L("Linux runtime drivers")
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

    private fun record(context: Context, url: String, place: Place, id: String) {
        val value = place.name + "|" + id
        val p = prefs(context)
        val edit = p.edit()
        // A replaced driver keeps its id: the entry it came from before is no longer what is installed.
        p.all.forEach { (k, v) -> if (v == value) edit.remove(k) }
        edit.putString(normUrl(url), value).apply()
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
            val id = s.substringAfter('|')
            val there = when (s.substringBefore('|')) {
                Place.ANDROID.name -> id in android
                Place.LINUX.name -> id in linux
                Place.WAYLAND.name -> id in wayland
                else -> false
            }
            if (there) url else null
        }.toSet()
    }
}
