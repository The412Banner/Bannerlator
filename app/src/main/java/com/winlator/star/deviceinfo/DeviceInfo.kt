// Ported from DroidDeck (Droid-Deck/DroidDeck app/src/main/java/com/droiddeck/launcher/gpu/DeviceInfo.kt, GPL-3.0): device
// details by The412Banner (#295), extended by Kurt Himebauch, Danbro007, ASEbrahim, openMali, MaxsTechReview.
// The last section lists Bannerlator's own driver lists instead of DroidDeck's.
package com.winlator.star.deviceinfo

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.winlator.star.contents.AdrenotoolsManager
import com.winlator.star.contents.LinuxVulkanDriverManager
import com.winlator.star.contents.WaylandGameDriverManager
import java.io.File
import java.util.Locale

/**
 * A system file's text, or null when it is missing, empty or not readable by the app (many sysfs nodes are not).
 * Bannerlator's FileUtils.readString throws on a file it cannot read; DroidDeck's returns null, which this code expects.
 */
internal fun readSys(path: String): String? = try {
    File(path).readText().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}

object DeviceInfo {
    private const val TAG = "DeviceInfo"

    class Section(val title: String, val rows: List<Pair<String, String>>)

    /**
     * Every section with something in it. Reads files and asks the Vulkan driver: call off the main thread.
     * Each section is read on its own, so one that fails (logged) does not take the others with it.
     */
    fun collect(context: Context): List<Section> {
        val vk = VulkanInfo.query()
        val gpu = runCatching { GpuInfo.detect() }.onFailure { Log.w(TAG, "GPU detection failed", it) }.getOrNull()
        return listOfNotNull(
            safe("chip") { chip() },
            safe("gpu") { gpu?.let { gpu(it) } },
            safe("cpu") { cpu() },
            safe("memory") { memory(context) },
            safe("system") { system(context) },
            safe("vulkan") { vulkan(context, vk) },
            safe("drivers") { drivers(context) },
        ).filter { it.rows.isNotEmpty() }
    }

    private fun safe(name: String, build: () -> Section?): Section? =
        try {
            build()
        } catch (e: Exception) {
            Log.w(TAG, "section $name failed", e)
            null
        }

    /** The sections as plain text, for a bug report. */
    fun text(sections: List<Section>): String = buildString {
        append("Bannerlator device info\n")
        for (s in sections) {
            append('\n').append(s.title).append('\n')
            for ((k, v) in s.rows) append("  ").append(k).append(": ").append(v).append('\n')
        }
    }

    private fun MutableList<Pair<String, String>>.add(key: String, value: String?) {
        if (!value.isNullOrBlank() && value != Build.UNKNOWN) add(key to value.trim())
    }

    private fun read(path: String): String? = readSys(path)

    private fun prop(name: String): String = GpuInfo.systemProperty(name)

    private fun chip() = Section("Chip (SoC)", buildList<Pair<String, String>> {
        val model = SocNames.model()
        val platform = prop("ro.board.platform")
        add("Name", SocNames.name(model, platform))
        add("Model", model)
        if (Build.VERSION.SDK_INT >= 31) add("Maker", Build.SOC_MANUFACTURER)
        add("Platform", platform)
        add("Hardware", Build.HARDWARE)
        add("Board", Build.BOARD)
        val id = read("/sys/devices/soc0/soc_id")
        val machine = read("/sys/devices/soc0/machine")
        add("SoC id", listOfNotNull(id, machine).joinToString(" · "))
        add("Family", read("/sys/devices/soc0/family"))
    })

    private fun gpu(gpu: GpuInfo) = Section("GPU", buildList<Pair<String, String>> {
        add("GPU", gpu.name)
        add("Family", gpu.family)
        add("Model found from", when (gpu.modelSource) {
            "kernel" -> "the kernel's GPU name"
            "vulkan" -> "the Vulkan driver (the kernel's name has no model)"
            "platform" -> "the platform or SoC (the kernel and Vulkan names have no model)"
            else -> null
        })
        add("Kernel name", gpu.kgslName)
        add("Kernel chip id", gpu.kgslChipId)
        val kgsl = "/sys/class/kgsl/kgsl-3d0"
        add("Clock range", gpuRange())
        add("Load", read("$kgsl/gpu_busy_percentage")?.let { if (it.endsWith("%")) it else "$it %" })
        add("Temperature", read("$kgsl/temp")?.toLongOrNull()?.let { if (it > 1000) "${it / 1000} °C" else "$it °C" })
    })

    private fun cpu() = Section("CPU", buildList<Pair<String, String>> {
        add("Cores", Runtime.getRuntime().availableProcessors().toString())
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.matches(Regex("policy\\d+")) }
            ?.sortedBy { it.name.removePrefix("policy").toInt() }.orEmpty()
        for (p in policies) {
            val cpus = (read("${p.path}/related_cpus") ?: read("${p.path}/affected_cpus"))?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
            val min = read("${p.path}/cpuinfo_min_freq")?.toLongOrNull()
            val max = read("${p.path}/cpuinfo_max_freq")?.toLongOrNull() ?: continue
            val cores = if (cpus.size > 1) "cores ${cpus.first()}-${cpus.last()}" else "core ${cpus.firstOrNull() ?: p.name.removePrefix("policy")}"
            add("Cluster ${p.name.removePrefix("policy")}", "${cpus.size.coerceAtLeast(1)} × $cores · " +
                (if (min != null) ghz(min) + "–" else "") + ghz(max))
        }
        add("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
    })

    private fun ghz(khz: Long) = String.format(Locale.US, "%.2f GHz", khz / 1e6)

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
        else -> String.format(Locale.US, "%d MB", bytes shr 20)
    }

    private fun gpuRange(): String? {
        fun mhz(hz: Long) = "${hz / 1_000_000} MHz"
        val kgsl = read("/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies")
            ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.filter { it > 0 }.orEmpty()
        if (kgsl.isNotEmpty()) return "${mhz(kgsl.min())}–${mhz(kgsl.max())} (${kgsl.size} steps)"
        read("/sys/class/kgsl/kgsl-3d0/max_gpuclk")?.toLongOrNull()?.let { return "up to ${mhz(it)}" }
        val node = File("/sys/class/devfreq").listFiles()?.firstOrNull { f ->
            val n = f.name.lowercase()
            (n.contains("gpu") || n.contains("mali") || n.contains("kgsl-3d")) && listOf("bus", "bw", "memlat").none { n.contains(it) }
        } ?: return null
        val steps = read("${node.path}/available_frequencies")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.filter { it > 0 }.orEmpty()
        if (steps.isNotEmpty()) return "${mhz(steps.min())}–${mhz(steps.max())} (${steps.size} steps)"
        val min = read("${node.path}/min_freq")?.toLongOrNull()
        val max = read("${node.path}/max_freq")?.toLongOrNull() ?: return null
        return (if (min != null) "${mhz(min)}–" else "up to ") + mhz(max)
    }

    private fun memory(context: Context) = Section("Memory", buildList<Pair<String, String>> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return@buildList
        val mi = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        add("Total", size(mi.totalMem))
        add("Available now", size(mi.availMem))
    })

    private fun system(context: Context) = Section("System", buildList<Pair<String, String>> {
        add("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        add("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        add("Security patch", Build.VERSION.SECURITY_PATCH)
        add("Kernel", System.getProperty("os.version"))
        add("Build", Build.DISPLAY)
        add("Bannerlator", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull())
    })

    private fun vulkan(context: Context, vk: Map<String, String>) = Section("Vulkan (the phone's own driver)", buildList<Pair<String, String>> {
        vk["error"]?.let { add("Error", it) }
        add("Device", vk["device"])
        add("Type", vk["type"])
        add("API version", vk["api"])
        add("Loader version", vk["loader"])
        add("Driver", listOfNotNull(vk["driver_name"], vk["driver_version"]).joinToString(" "))
        add("Driver build", vk["driver_info"]?.replace(Regex("(?i)^driver build:\\s*"), ""))
        add("Conformance", vk["conformance"])
        add("Vendor / device id", listOfNotNull(vk["vendor_id"], vk["device_id"]).joinToString(" / "))
        add("Max texture size", vk["max_image_2d"])
        add("GPU memory heap", vk["device_heap_mb"]?.let { "$it MB" })
        add("Extensions", vk["extension_count"])
        val features = listOf("geometry_shader" to "Geometry shaders", "tessellation_shader" to "Tessellation", "texture_bc" to "BC textures")
        for ((key, label) in features) add(label, vk[key])
        for ((key, value) in vk) if (key.startsWith("ext.")) add(key.removePrefix("ext."), value)
        val hw = context.packageManager.systemAvailableFeatures.associate { it.name to it.version }
        hw[PackageManager.FEATURE_VULKAN_HARDWARE_VERSION]?.let {
            add("Android-certified version", "${it shr 22}.${(it shr 12) and 0x3ff}.${it and 0xfff}")
        }
        hw[PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL]?.let { add("Android hardware level", it.toString()) }
    })

    /** Bannerlator's three driver lists, as installed (the bundled drivers are not listed). */
    private fun drivers(context: Context) = Section("Drivers installed in Bannerlator", buildList<Pair<String, String>> {
        fun names(ids: List<String>, name: (String) -> String, version: (String) -> String): String =
            ids.joinToString(", ") { id -> "${name(id)} ${version(id)}".trim() }.ifEmpty { "none" }
        runCatching {
            val m = AdrenotoolsManager(context)
            add("Graphics drivers", names(m.enumarateInstalledDrivers(), m::getDriverName, m::getDriverVersion))
        }
        runCatching {
            val m = LinuxVulkanDriverManager(context)
            add("Linux runtime drivers", names(m.enumerateInstalledDrivers(), m::getDriverName, m::getDriverVersion))
        }
        runCatching {
            val m = WaylandGameDriverManager(context)
            add("Wayland game drivers", names(m.enumerateInstalledDrivers(), m::getDriverName, m::getDriverVersion))
        }
    })
}
