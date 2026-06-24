package com.winlator.star.components

import android.content.Context
import com.winlator.star.container.Container
import com.winlator.star.contents.Downloader
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.core.WineRegistryEditor
import java.io.File

/**
 * Runs a component's Bottles-format install steps against a container's Wine prefix.
 *
 * Phase 2 supports the "file-drop" actions that need NO Wine execution:
 * download_archive, archive_extract, copy_dll, copy_file, override_dll, set_register_key, delete_dlls.
 * Components that need cab extraction or running an installer (install_exe/install_msi/get_from_cab/…)
 * are reported as not-yet-installable so we never half-install them.
 *
 * To stay robust against Bottles' internal temp-path conventions, copy_dll/copy_file glob the whole
 * extracted tree for files matching file_name rather than following the manifest's literal temp paths.
 */
object ComponentInstaller {
    private val FILE_DROP_ACTIONS = setOf(
        "download_archive", "archive_extract", "copy_dll", "copy_file", "override_dll", "set_register_key", "delete_dlls"
    )
    private const val DLL_OVERRIDES = "Software\\Wine\\DllOverrides"

    /** True when every step is a supported file-drop action and the component is mirrored (ready). */
    fun isInstallable(c: Component): Boolean =
        c.ready && c.steps.isNotEmpty() && c.steps.all { it.action in FILE_DROP_ACTIONS }

    /** Reason a component can't be installed yet (or null if it can). */
    fun blockedReason(c: Component): String? = when {
        c.status == "needs-upstream" -> "Needs a large package that isn't mirrored yet"
        c.status == "pending-manual" -> "Source unavailable — awaiting a re-hosted file"
        !isInstallable(c) -> "Needs an installer step not yet supported"
        else -> null
    }

    /** Install into [container]'s prefix. [onProgress] gets 0..1. Returns null on success, else an error message. */
    fun install(context: Context, container: Container, c: Component, onProgress: (Float) -> Unit): String? {
        val root = container.rootDir
        val system32 = File(root, ".wine/drive_c/windows/system32")
        val syswow64 = File(root, ".wine/drive_c/windows/syswow64")
        val userReg = File(root, ".wine/user.reg")
        if (!File(root, ".wine").isDirectory) return "Container has no Wine prefix yet — launch it once first."
        val tmp = File(context.cacheDir, "comp_${c.name}_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val total = c.steps.size.coerceAtLeast(1)
            c.steps.forEachIndexed { idx, step ->
                runStep(step, tmp, system32, syswow64, userReg)
                onProgress((idx + 1).toFloat() / total)
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            e.message ?: e.javaClass.simpleName
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun runStep(step: ComponentStep, tmp: File, system32: File, syswow64: File, userReg: File) {
        when (step.action) {
            "download_archive", "archive_extract" -> {
                val url = step.str("url")
                if (url.startsWith("http")) {
                    val name = step.str("file_name").ifEmpty { url.substringBefore('?').substringAfterLast('/') }
                    val dl = File(tmp, name)
                    if (!Downloader.downloadFile(url, dl) { _ -> }) throw Exception("download failed: $name")
                    extractArchive(dl, tmp)
                }
            }
            "copy_dll", "copy_file" -> {
                // win64 DLLs -> system32, win32 DLLs -> syswow64. Constrain the SOURCE to the matching
                // arch sub-tree so a 64-bit DLL never lands in syswow64 (and vice versa).
                val (arch, target) = when (step.str("dest")) {
                    "win64", "system32" -> "win64" to system32
                    "win32", "syswow64" -> "win32" to syswow64
                    else -> null to system32   // unspecified: 64-bit prefix default
                }
                copyMatching(tmp, step.str("file_name"), target, arch)
            }
            "override_dll" -> WineRegistryEditor(userReg).use { reg ->
                val dll = step.str("dll")
                if (dll.isNotEmpty()) reg.setStringValue(DLL_OVERRIDES, dll, step.str("type").ifEmpty { "native,builtin" })
                step.bundle()?.let { b ->
                    for (i in 0 until b.length()) {
                        val o = b.getJSONObject(i)
                        reg.setStringValue(DLL_OVERRIDES, o.optString("value"), o.optString("data").ifEmpty { "native,builtin" })
                    }
                }
            }
            "set_register_key" -> WineRegistryEditor(userReg).use { reg ->
                reg.setCreateKeyIfNotExist(true)
                val key = step.str("key"); val name = step.str("value"); val data = step.str("data")
                if (step.str("type") == "REG_DWORD") {
                    val v = data.toIntOrNull() ?: data.toLongOrNull(16)?.toInt() ?: 0
                    reg.setDwordValue(key, name, v)
                } else reg.setStringValue(key, name, data)
            }
            "delete_dlls" -> {
                step.bundle()?.let { b ->
                    for (i in 0 until b.length()) {
                        val nm = b.getJSONObject(i).optString("value")
                        if (nm.isNotEmpty()) {
                            File(system32, ensureDll(nm)).delete()
                            File(syswow64, ensureDll(nm)).delete()
                        }
                    }
                }
            }
            else -> throw Exception("unsupported action: ${step.action}")
        }
    }

    private fun ensureDll(n: String) = if (n.endsWith(".dll", true) || n.endsWith(".exe", true)) n else "$n.dll"

    private fun extractArchive(file: File, dest: File) {
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, file, dest))
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, file, dest)
    }

    private fun copyMatching(srcRoot: File, pattern: String, dest: File, arch: String?) {
        if (pattern.isEmpty()) return
        dest.mkdirs()
        val rx = Regex("^" + Regex.escape(pattern).replace("\\*", ".*") + "$", RegexOption.IGNORE_CASE)
        var matches = srcRoot.walkTopDown().filter { it.isFile && rx.matches(it.name) }.toList()
        if (arch != null) {
            // Prefer files under the matching arch sub-dir; otherwise just exclude the wrong arch.
            val inArch = matches.filter { it.path.contains("${File.separator}$arch${File.separator}", true) }
            matches = if (inArch.isNotEmpty()) inArch else {
                val other = if (arch == "win64") "win32" else "win64"
                matches.filterNot { it.path.contains("${File.separator}$other${File.separator}", true) }
            }
        }
        matches.forEach { f -> f.copyTo(File(dest, f.name), overwrite = true) }
    }
}
