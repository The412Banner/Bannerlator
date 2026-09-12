package com.winlator.star.perf

import java.io.File
import java.util.Locale

/**
 * Resolves the ACTUAL sysfs node paths present on this SoC for the root perf tier, so a write ever
 * targets a real node rather than a hard-coded guess that only exists on one vendor. The discovery
 * mirrors [com.winlator.star.widget.HudMetrics] (Adreno/KGSL vs Mali/devfreq vs thermal_zone walks)
 * — that class reads these trees read-only for the HUD; this one enumerates the writable knobs.
 *
 * Nothing here writes. It only lists candidate paths (filtered to those that exist) for
 * [RootManager]/[PerfRevertRegistry] to snapshot-then-write. Results are cached per process.
 */
object PerfNodeResolver {

    // ── CPU (cpufreq / hotplug), per policy or per core ────────────────────────────────────────
    data class CpuCoreNodes(
        val index: Int,
        val governor: String?,     // scaling_governor
        val minFreq: String?,      // scaling_min_freq
        val maxFreq: String?,      // scaling_max_freq
        val online: String?,       // /sys/devices/system/cpu/cpuN/online
    )

    // ── GPU (Adreno KGSL or devfreq/Mali) ──────────────────────────────────────────────────────
    data class GpuNodes(
        val devfreqGovernor: String?,  // devfreq .../governor
        val minClock: String?,         // devfreq min_freq / kgsl min_clock_mhz
        val maxClock: String?,         // devfreq max_freq / kgsl max_clock_mhz
        val forceClkOn: String?,       // kgsl force_clk_on
        val forceBusOn: String?,       // kgsl force_bus_on
    )

    /**
     * Device thermal trip points, derived over the CPU/GPU zones we watch. Anchors the watchdog to
     * the hardware's OWN throttle points instead of a guessed fixed number.
     *  - [firstTripC] = lowest meaningful throttle trip (where the device first starts backing off).
     *  - [topTripC]   = highest / hard-critical trip.
     *  - [watchedTempPaths] = the `temp` nodes of exactly those zones, so the watchdog polls the same
     *    sensors the trips came from and watches the HOTTEST of them.
     * Any field is null when the device exposes no usable trips (caller falls back to a fixed ceiling).
     */
    data class ThermalTrips(
        val firstTripC: Int?,
        val topTripC: Int?,
        val watchedTempPaths: List<String>,
    )

    private var cpuCache: List<CpuCoreNodes>? = null
    private var gpuCache: GpuNodes? = null
    private var thermalModeCache: List<String>? = null
    private var thermalTripsCache: ThermalTrips? = null
    private var fanCache: List<String>? = null

    // Zone `type` tokens we treat as CPU/GPU/SoC temperature zones worth watching + trip-reading.
    private val THERMAL_WATCH_TOKENS = arrayOf(
        "cpu", "gpu", "soc", "cluster", "tsens", "mtktscpu", "mtktsgpu",
        "g3d", "kgsl", "mali", "apu", "silicon", "big", "little", "cpuss", "gpuss",
    )

    private fun existing(path: String): String? = if (File(path).exists()) path else null

    /** Per-core cpufreq governor / min / max / online nodes that exist on this device. */
    fun cpuCores(): List<CpuCoreNodes> {
        cpuCache?.let { return it }
        val n = Runtime.getRuntime().availableProcessors()
        val out = ArrayList<CpuCoreNodes>(n)
        for (i in 0 until n) {
            val base = "/sys/devices/system/cpu/cpu$i"
            val cpufreq = "$base/cpufreq"
            out.add(
                CpuCoreNodes(
                    index = i,
                    governor = existing("$cpufreq/scaling_governor"),
                    minFreq = existing("$cpufreq/scaling_min_freq"),
                    maxFreq = existing("$cpufreq/scaling_max_freq"),
                    // cpu0 has no `online` node (can't be offlined); the rest do.
                    online = existing("$base/online"),
                )
            )
        }
        return out.also { cpuCache = it }
    }

    /** Adreno-KGSL or Mali/devfreq GPU clock + governor + force-clk knobs present on this device. */
    fun gpu(): GpuNodes {
        gpuCache?.let { return it }
        // Adreno / KGSL fixed layout.
        val kgsl = "/sys/class/kgsl/kgsl-3d0"
        val kgslDevfreq = "$kgsl/devfreq"
        var governor = existing("$kgslDevfreq/governor")
        var minClock = existing("$kgsl/min_clock_mhz")
        var maxClock = existing("$kgsl/max_clock_mhz")
        val forceClkOn = existing("$kgsl/force_clk_on")
        val forceBusOn = existing("$kgsl/force_bus_on")

        // Fall back to a generic devfreq GPU node (Mali / Xclipse / PowerVR) for governor/min/max.
        if (governor == null || minClock == null || maxClock == null) {
            val devfreqGpu = findDevfreqGpuDir()
            if (devfreqGpu != null) {
                if (governor == null) governor = existing("$devfreqGpu/governor")
                if (minClock == null) minClock = existing("$devfreqGpu/min_freq")
                if (maxClock == null) maxClock = existing("$devfreqGpu/max_freq")
            }
        }
        return GpuNodes(governor, minClock, maxClock, forceClkOn, forceBusOn).also { gpuCache = it }
    }

    private val GPU_NODE_TOKENS = arrayOf(
        "gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu"
    )

    private fun findDevfreqGpuDir(): String? {
        val roots = arrayOf(File("/sys/class/devfreq"), File("/sys/devices/virtual/devfreq"))
        for (root in roots) {
            if (!root.isDirectory) continue
            val nodes = root.listFiles(File::isDirectory) ?: continue
            for (node in nodes) {
                val name = node.path.lowercase(Locale.US)
                if (GPU_NODE_TOKENS.any { name.contains(it) } && File(node, "governor").exists()) {
                    return node.path
                }
            }
        }
        return null
    }

    /**
     * `mode` knobs of every thermal_zone that has one — writing "disabled"/"enabled" is how a root
     * toggle would pause/resume a zone's throttling. Discovery mirrors HudMetrics' thermal walk.
     */
    fun thermalZoneModes(): List<String> {
        thermalModeCache?.let { return it }
        val out = LinkedHashSet<String>()
        val dirs = arrayOf(File("/sys/class/thermal"), File("/sys/devices/virtual/thermal"))
        for (dir in dirs) {
            val zones = dir.listFiles { _, name -> name.startsWith("thermal_zone") } ?: continue
            for (zone in zones) {
                if (!zone.isDirectory) continue
                val mode = File(zone, "mode")
                if (mode.exists()) out.add(mode.path)
            }
        }
        return out.toList().also { thermalModeCache = it }
    }

    /**
     * Best-effort fan control nodes (mode + speed) across the hwmon and thermal-cooling trees. Fan
     * presence is device-specific (most phones have none); returns only paths that exist.
     */
    fun fanNodes(): List<String> {
        fanCache?.let { return it }
        val out = LinkedHashSet<String>()
        // hwmon pwm/fan targets.
        val hwmon = File("/sys/class/hwmon")
        hwmon.listFiles(File::isDirectory)?.forEach { chip ->
            chip.listFiles { _, name ->
                name.matches(Regex("pwm\\d+")) ||
                    name.matches(Regex("pwm\\d+_enable")) ||
                    name.matches(Regex("fan\\d+_target"))
            }?.forEach { out.add(it.path) }
        }
        // thermal cooling devices named like a fan.
        val thermal = File("/sys/class/thermal")
        thermal.listFiles { _, name -> name.startsWith("cooling_device") }?.forEach { cd ->
            val type = try { File(cd, "type").readText().trim().lowercase(Locale.US) } catch (_: Exception) { "" }
            if (type.contains("fan")) {
                existing("${cd.path}/cur_state")?.let { out.add(it) }
            }
        }
        return out.toList().also { fanCache = it }
    }

    /**
     * Enumerate the CPU/GPU thermal zones and derive the device's throttle trip points. Trip temps
     * are read from `trip_point_*_temp` (millidegrees, normalized to °C); degenerate values (< 40°C or
     * > 200°C, e.g. a placeholder "1") are ignored. Cached per process.
     */
    fun thermalTrips(): ThermalTrips {
        thermalTripsCache?.let { return it }
        val watchedTemp = LinkedHashSet<String>()
        val trips = ArrayList<Int>()
        val dirs = arrayOf(File("/sys/class/thermal"), File("/sys/devices/virtual/thermal"))
        for (dir in dirs) {
            val zones = dir.listFiles { _, name -> name.startsWith("thermal_zone") } ?: continue
            for (zone in zones) {
                if (!zone.isDirectory) continue
                val type = readLine(File(zone, "type"))?.lowercase(Locale.US) ?: continue
                if (THERMAL_WATCH_TOKENS.none { type.contains(it) }) continue
                if (File(zone, "temp").exists()) watchedTemp.add(File(zone, "temp").path)
                // Trip indices can be sparse; scan a fixed range rather than breaking on the first gap.
                for (i in 0..15) {
                    val tp = File(zone, "trip_point_${i}_temp")
                    if (!tp.exists()) continue
                    val raw = readLine(tp)?.trim()?.toIntOrNull() ?: continue
                    val c = if (raw > 1000) (raw + 500) / 1000 else raw
                    if (c in 40..200) trips.add(c)
                }
            }
        }
        return ThermalTrips(trips.minOrNull(), trips.maxOrNull(), watchedTemp.toList())
            .also { thermalTripsCache = it }
    }

    private fun readLine(file: File): String? = try {
        if (file.exists()) file.bufferedReader().use { it.readLine() } else null
    } catch (_: Exception) { null }

    /** Every node this resolver can currently target, flattened — handy for logging / diagnostics. */
    fun allKnownNodes(): List<String> {
        val out = ArrayList<String>()
        cpuCores().forEach { c ->
            listOfNotNull(c.governor, c.minFreq, c.maxFreq, c.online).forEach(out::add)
        }
        gpu().let { g ->
            listOfNotNull(g.devfreqGovernor, g.minClock, g.maxClock, g.forceClkOn, g.forceBusOn).forEach(out::add)
        }
        out.addAll(thermalZoneModes())
        out.addAll(fanNodes())
        return out
    }
}
