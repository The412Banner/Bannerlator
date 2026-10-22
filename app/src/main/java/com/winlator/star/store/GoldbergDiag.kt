package com.winlator.star.store

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Goldberg launch report: a plain-text file written next to the game's other logs
 * (`<log dir>/<game>/goldberg_diag.txt`) on EVERY Goldberg launch, whatever the Log Manager toggles say,
 * so a black-screen report can be sent back with what was really on disk and what was really launched:
 * the last apply report, the dlls swapped (sizes + md5 vs their `.bak`), the `steam_settings` we wrote,
 * the ColdClient ini, the exe handed to Wine, and the launch mode / Wine layer.
 */
object GoldbergDiag {

    /** File name inside the per-game log folder. Listed in [com.winlator.star.core.LogRotation.isOurRunLog]. */
    const val OUTPUT_NAME = "goldberg_diag.txt"

    private const val TAG = "BH_GOLDBERG"

    @Volatile private var outFile: File? = null
    private val lock = Any()

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun applyReportFile(ctx: Context, appId: Int): File =
        File(File(ctx.filesDir, "goldberg_apply").apply { mkdirs() }, "$appId.txt")

    /** Persist the apply report of [appId] (called by [GoldbergPatcher]). Never throws. */
    @JvmStatic
    fun recordApply(ctx: Context, appId: Int, lines: List<String>) {
        try {
            val body = "applied at ${stamp()}\n" + lines.joinToString("\n") + "\n"
            applyReportFile(ctx, appId).writeText(body)
        } catch (t: Throwable) {
            Log.w(TAG, "recordApply failed", t)
        }
    }

    /** The last persisted apply report for [appId], as lines (empty when none). */
    @JvmStatic
    fun lastApplyReport(ctx: Context, appId: Int): List<String> {
        return try {
            val f = applyReportFile(ctx, appId)
            if (f.isFile) f.readLines() else listOf("(no apply report on record for appId $appId)")
        } catch (t: Throwable) {
            listOf("(apply report unreadable: $t)")
        }
    }

    /** Start (overwrite) the report in [logDir]; later [append] calls extend it. Null on failure. */
    @JvmStatic
    fun begin(logDir: File, lines: List<String>): File? {
        return try {
            logDir.mkdirs()
            val f = File(logDir, OUTPUT_NAME)
            synchronized(lock) {
                f.writeText("== Bannerlator Goldberg launch report (${stamp()}) ==\n" + lines.joinToString("\n") + "\n")
                outFile = f
            }
            Log.i(TAG, "diagnostic report: ${f.path}")
            f
        } catch (t: Throwable) {
            Log.w(TAG, "couldn't write the diagnostic report into ${logDir.path}", t)
            outFile = null
            null
        }
    }

    /** Add a timestamped line to the current report (no-op when none is open). */
    @JvmStatic
    fun append(line: String) {
        val f = outFile ?: return
        synchronized(lock) {
            try { f.appendText("[${stamp()}] $line\n") } catch (_: Throwable) {}
        }
        Log.i(TAG, line)
    }

    /** Forget the open report (a non-Goldberg launch must not append to a stale file). */
    @JvmStatic
    fun end() { outFile = null }

    @JvmStatic
    fun md5(f: File): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (t: Throwable) {
            "?"
        }
    }

    /** What is on disk right now for [installDir]: swapped dlls, settings, loader ini, plus the component tree. */
    @JvmStatic
    fun describeInstall(ctx: Context, installDir: String): List<String> {
        val out = ArrayList<String>()
        try {
            val root = File(installDir)
            out.add("installDir=$installDir exists=${root.isDirectory}")

            val comp = GoldbergComponent.installDir(ctx)
            out.add("component: installed=${GoldbergComponent.isInstalled(ctx)} " +
                "version=${GoldbergComponent.installedVersion(ctx)} dir=${comp.path}")
            for (rel in listOf("regular", "experimental", "steamclient_experimental")) {
                val d = File(comp, rel)
                val parts = ArrayList<String>()
                d.walkTopDown().maxDepth(3).filter { it.isFile }.take(40).forEach {
                    parts.add("${it.relativeTo(comp).path}(${it.length()})")
                }
                out.add("  $rel: ${if (parts.isEmpty()) "<missing/empty>" else parts.joinToString(", ")}")
            }
            val regularDll = File(comp, "regular/x64/steam_api64.dll")
            if (regularDll.isFile) out.add("  component regular/x64/steam_api64.dll md5=${md5(regularDll)}")
            val regularDll32 = File(comp, "regular/x86/steam_api.dll")
            if (regularDll32.isFile) out.add("  component regular/x86/steam_api.dll md5=${md5(regularDll32)}")

            if (!root.isDirectory) return out
            val targets = GoldbergPatcher.analyze(root)
            out.add("steam_api dlls in the install: ${targets.size}")
            val dirs = LinkedHashSet<File>()
            for (t in targets) {
                dirs.add(t.dir)
                val bak = t.bakFile
                val dllMd5 = md5(t.dll)
                val bakMd5 = if (bak.isFile) md5(bak) else null
                val state = when {
                    bakMd5 == null -> "NO .bak (never patched, or backup missing)"
                    dllMd5 == bakMd5 -> "ORIGINAL (same as .bak) — Goldberg NOT swapped in"
                    else -> "SWAPPED (differs from .bak)"
                }
                out.add("  ${t.dll.path.removePrefix(root.path)} arch=${t.arch} size=${t.dll.length()} md5=$dllMd5 " +
                    "bak=${bakMd5 ?: "-"} -> $state")
                for (n in listOf(t.arch.steamclientDll, "GameOverlayRenderer.dll", "GameOverlayRenderer64.dll")) {
                    val f = File(t.dir, n)
                    if (f.isFile) out.add("    beside it: $n size=${f.length()} md5=${md5(f)}")
                }
            }
            for (d in dirs) {
                val sd = File(d, "steam_settings")
                out.add("  steam_settings in ${d.path.removePrefix(root.path).ifEmpty { "/" }}: " +
                    (sd.list()?.sorted()?.joinToString() ?: "<missing>"))
                for (n in listOf("steam_appid.txt")) {
                    val f = File(sd, n)
                    if (f.isFile) out.add("    $n: " + f.readText().trim().take(400).replace("\n", " | "))
                }
                val ifs = File(sd, "steam_interfaces.txt")
                if (ifs.isFile) out.add("    steam_interfaces.txt: " + ifs.readLines().joinToString(", ").take(600))
            }
            // Any ColdClient ini/loader (it lives beside the exe, which need not be a steam_api dir).
            val inis = root.walkTopDown().maxDepth(6)
                .filter { it.isFile && it.name.equals("ColdClientLoader.ini", ignoreCase = true) }.take(4).toList()
            if (inis.isEmpty()) out.add("ColdClientLoader.ini: none")
            for (ini in inis) {
                out.add("ColdClientLoader.ini at ${ini.path.removePrefix(root.path)}:")
                ini.readLines().filter { it.isNotBlank() }.take(40).forEach { out.add("    $it") }
                val loaders = ini.parentFile?.listFiles { f -> f.name.startsWith("steamclient_loader", ignoreCase = true) }
                out.add("    loaders beside it: " + (loaders?.joinToString { it.name } ?: "?"))
            }
        } catch (t: Throwable) {
            out.add("describeInstall failed: $t")
        }
        return out
    }
}
