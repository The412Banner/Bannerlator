package com.winlator.star.linux

import android.util.Log
import com.winlator.star.xenvironment.EnvironmentComponent
import java.io.File
import java.io.RandomAccessFile

/**
 * The Adreno's stats as mangoapp (Deck mode's performance overlay) reads them.
 *
 * MangoHud takes an Adreno's load, clock and temperature from `gpu_busy_percentage`, `clock_mhz`
 * and `temp` in `/sys/class/kgsl/kgsl-3d0`, and ends the process on one of them it may not read -
 * not on one that is missing, which it skips. An enforcing SELinux policy (every retail phone)
 * refuses apps some or all of KGSL's sysfs, each vendor differently, so mangoapp died on start and
 * the session ran with no overlay. Where any of the three is refused, a directory of our own is
 * bound there instead, each value fed twice a second from the first source this device lets the
 * app read - the places other Android overlays (GameNative, WinNative, Winlator forks) read
 * an Adreno's numbers from, across Snapdragon generations and vendor kernels. A value with no
 * readable source is left out, and the overlay shows no line for it. Where all three are
 * readable, nothing is done.
 *
 * Valve's build also reads load and clock where a mainline msm kernel has them (debugfs
 * `perf_now`, the GPU's devfreq `cur_freq` in Hz) - the paths LinuxRuntime.bindAdrenoStats points
 * at KGSL's own files when it may - so the same values are fed there too, in the same formats
 * ([binds]).
 *
 * (From Droid-Deck/DroidDeck #127.)
 */
class LinuxGpuStatsComponent(val dir: File) : EnvironmentComponent() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val feeds = ArrayList<Feed>()

    /** One of MangoHud's files and where its value comes from. */
    private class Feed(
        val file: File, val source: String, val read: () -> Long?, val format: String, val percent: Boolean = false,
    )

    /** Where Valve's msm reader looks, and the feed for it, as host:guest binds. */
    fun binds(): List<String> {
        val list = mutableListOf(dir.path + ":" + KGSL)
        if (File(dir, "perf_now").exists()) list.add(File(dir, "perf_now").path + ":" + MSM_LOAD)
        if (File(dir, "cur_freq").exists()) list.add(File(dir, "cur_freq").path + ":" + MSM_CLOCK)
        return list
    }

    /** Writes the directory; true when it should be bound over [KGSL]. */
    fun prepare(): Boolean {
        if (!File(KGSL).exists() || MANGOHUD_FILES.none { File(KGSL, it).let { f -> f.exists() && !f.canRead() } }) return false
        return try {
            dir.mkdirs()
            dir.listFiles()?.forEach { java.nio.file.Files.deleteIfExists(it.toPath()) }
            feeds.clear()
            loadFeed()?.let { load ->
                feeds.add(load)
                // KGSL's gpu_busy_percentage reads "37 %"; padded after, so a shorter value
                // written over a longer one leaves no digits behind.
                feeds.add(Feed(File(dir, "perf_now"), load.source, load.read, "%-6s\n", percent = true))
            }
            pick("clock_mhz", clockSources(), "%5d\n") { mhz(it) }?.let { clock ->
                feeds.add(clock)
                feeds.add(Feed(File(dir, "cur_freq"), clock.source, { clock.read()?.times(1_000_000) }, "%-10d\n"))
            }
            pick("temp", tempSources(), "%6d\n") { milliCelsius(it) }?.let { feeds.add(it) }
            feeds.forEach { feed -> feed.read()?.let { write(feed, it) } }
            Log.i(TAG, "hud: kgsl stats from the session's own: " + MANGOHUD_FILES.joinToString { name ->
                "$name " + (feeds.firstOrNull { it.file.name == name }?.source ?: "none")
            })
            true
        } catch (e: Exception) {
            Log.w(TAG, "hud: could not stand in for kgsl stats: $e")
            false
        }
    }

    override fun start() {
        if (feeds.isEmpty()) return
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!running) break
                for (feed in feeds) feed.read()?.let { write(feed, it) }
            }
        }, "gpu-stats").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Load: a file that states it in percent, else KGSL's busy and total of the last sample. */
    private fun loadFeed(): Feed? {
        pick("gpu_busy_percentage", LOAD_SOURCES, "%3d %%\n") { it.coerceIn(0, 100) }?.let { return it }
        val gpubusy = "$KGSL/gpubusy"
        val read = {
            runCatching {
                val parts = File(gpubusy).readText().trim().split(Regex("\\s+"))
                val total = parts[1].toLong()
                if (total > 0) (parts[0].toLong() * 100 / total).coerceIn(0, 100) else 0L
            }.getOrNull()
        }
        return if (read() != null) Feed(File(dir, "gpu_busy_percentage"), gpubusy, read, "%3d %%\n") else null
    }

    /** The first of [sources] that reads as a number, its value put in MangoHud's unit by [unit]. */
    private fun pick(name: String, sources: List<String>, format: String, unit: (Long) -> Long?): Feed? {
        for (source in sources) {
            val read = { firstNumber(source)?.let(unit) }
            if (read() != null) return Feed(File(dir, name), source, read, format)
        }
        return null
    }

    /** In place and at one length, so a reader that keeps the file open sees each value whole. */
    private fun write(feed: Feed, value: Long) {
        try {
            val text = if (feed.percent) String.format(feed.format, "$value %") else String.format(feed.format, value)
            RandomAccessFile(feed.file, "rw").use { it.seek(0); it.write(text.toByteArray()) }
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val TAG = "LinuxHud"
        const val KGSL = "/sys/class/kgsl/kgsl-3d0"
        /** Must match LinuxRuntime.bindAdrenoStats. */
        const val MSM_LOAD = "/sys/kernel/debug/dri/0/perf_now"
        const val MSM_CLOCK = "/sys/devices/platform/soc@0/3d00000.gpu/devfreq/3d00000.gpu/cur_freq"
        private const val PERIOD_MS = 500L
        private val MANGOHUD_FILES = listOf("gpu_busy_percentage", "clock_mhz", "temp")
        /** Qualcomm's own GPU summary, beside KGSL in vendor kernels, under the plain sysfs label. */
        private const val QCOM_GPU = "/sys/kernel/gpu"
        private val LOAD_SOURCES = listOf(
            "$KGSL/gpu_busy_percentage", "$KGSL/devfreq/gpu_load", "$KGSL/gpuload", "$QCOM_GPU/gpu_busy",
        )
        /** The GPU's devfreq node is named by its register address, which differs between Snapdragons. */
        private val DEVFREQ_NODES = listOf(
            "kgsl-3d0", "3d00000.qcom,kgsl-3d0", "2c00000.qcom,kgsl-3d0", "5000000.qcom,kgsl-3d0", "5900000.qcom,kgsl-3d0",
        )

        private fun clockSources(): List<String> {
            val sources = mutableListOf(
                "$KGSL/clock_mhz", "$KGSL/gpu_clock", "$KGSL/gpuclk", "$KGSL/devfreq/cur_freq", "$QCOM_GPU/gpu_clock",
            )
            DEVFREQ_NODES.forEach { sources.add("/sys/class/devfreq/$it/cur_freq") }
            File("/sys/class/devfreq").list()?.sorted()?.forEach { node ->
                if (node.contains("kgsl") || node.contains("gpu")) sources.add("/sys/class/devfreq/$node/cur_freq")
            }
            return sources.distinct()
        }

        private fun tempSources(): List<String> =
            listOfNotNull("$KGSL/temp", "$KGSL/devfreq/temp", "$QCOM_GPU/temp", LinuxRuntime.gpuTempSource()).distinct()

        /** The first integer in the file ("550", "37 %", "550000000"), or null. */
        private fun firstNumber(path: String): Long? =
            runCatching { Regex("-?\\d+").find(File(path).readText())?.value?.toLong() }.getOrNull()

        /** Hz, kHz or MHz to MHz, as the files differ; a stopped clock reads 0. */
        private fun mhz(raw: Long): Long? = when {
            raw < 0 -> null
            raw > 10_000_000 -> raw / 1_000_000
            raw > 10_000 -> raw / 1_000
            else -> raw
        }

        /** °C or m°C to MangoHud's m°C; nothing a GPU reads at is below 1 °C or above 1000. */
        private fun milliCelsius(raw: Long): Long? = when {
            raw <= 0 -> null
            raw < 1000 -> raw * 1000
            else -> raw
        }
    }
}
