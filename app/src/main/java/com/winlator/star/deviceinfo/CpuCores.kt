package com.winlator.star.deviceinfo

import java.io.File
import java.util.Locale

/**
 * The device's CPU cores, read from the device itself: each core's design (from `/proc/cpuinfo` "CPU part", or the
 * core's `midr_el1` register file) and its top clock (`cpuinfo_max_freq`), grouped into clusters for the core pickers
 * and the device-info pop-up. Phones with faster clocks than the chip's reference design (Samsung "for Galaxy" chips)
 * show their own clocks because nothing here comes from a table of chip specs.
 *
 * The parsing and grouping are pure functions (unit-tested on the JVM); only [read] touches the device.
 */
object CpuCores {

    enum class Tier(val label: String) { PRIME("Prime"), PERFORMANCE("Performance"), EFFICIENCY("Efficiency") }

    data class Core(
        val index: Int,
        val implementer: Int = -1,
        val part: Int = -1,
        val variant: Int = -1,
        val maxKhz: Long = 0,
        val minKhz: Long = 0,
        val capacity: Int = 0,
    ) {
        val name: String? get() = coreName(implementer, part)
    }

    data class Cluster(val tier: Tier, val name: String?, val maxKhz: Long, val minKhz: Long, val cores: List<Int>)

    // Part numbers from Linux arch/arm64/include/asm/cputype.h (torvalds/linux cd2a6b71f974, 2026-10-15).
    private val ARM_PARTS = mapOf(
        0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35", 0xd05 to "Cortex-A55", 0xd07 to "Cortex-A57",
        0xd08 to "Cortex-A72", 0xd09 to "Cortex-A73", 0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76",
        0xd0d to "Cortex-A77", 0xd0e to "Cortex-A76AE", 0xd41 to "Cortex-A78", 0xd42 to "Cortex-A78AE",
        0xd44 to "Cortex-X1", 0xd46 to "Cortex-A510", 0xd47 to "Cortex-A710", 0xd48 to "Cortex-X2",
        0xd4b to "Cortex-A78C", 0xd4c to "Cortex-X1C", 0xd4d to "Cortex-A715", 0xd4e to "Cortex-X3",
        0xd80 to "Cortex-A520", 0xd81 to "Cortex-A720", 0xd82 to "Cortex-X4", 0xd85 to "Cortex-X925",
        0xd87 to "Cortex-A725", 0xd88 to "Cortex-A520AE", 0xd89 to "Cortex-A720AE",
        0xd8a to "C1-Nano", 0xd8b to "C1-Pro", 0xd8c to "C1-Ultra", 0xd90 to "C1-Premium",
    )
    private val QCOM_PARTS = mapOf(
        0x001 to "Oryon",
        0x800 to "Kryo 2xx Gold", 0x801 to "Kryo 2xx Silver", 0x802 to "Kryo 3xx Gold", 0x803 to "Kryo 3xx Silver",
        0x804 to "Kryo 4xx Gold", 0x805 to "Kryo 4xx Silver",
    )

    /** Small, power-saving core designs. */
    private val EFFICIENCY_NAMES = setOf(
        "Cortex-A35", "Cortex-A53", "Cortex-A55", "Cortex-A510", "Cortex-A520", "Cortex-A520AE", "C1-Nano",
        "Kryo 2xx Silver", "Kryo 3xx Silver", "Kryo 4xx Silver",
    )

    /** Without core names, a cluster below this share of the fastest clock counts as efficiency cores. */
    private const val EFFICIENCY_PERCENT = 70

    fun coreName(implementer: Int, part: Int): String? = when (implementer) {
        0x41 -> ARM_PARTS[part]
        0x51 -> QCOM_PARTS[part]
        0x53 -> "Samsung Mongoose"
        else -> null
    }

    /** "processor : N" blocks of /proc/cpuinfo → index to (implementer, part, variant). Missing fields are -1. */
    fun parseCpuInfo(text: String): Map<Int, Triple<Int, Int, Int>> {
        val out = LinkedHashMap<Int, Triple<Int, Int, Int>>()
        var index = -1
        var impl = -1; var part = -1; var variant = -1
        fun flush() { if (index >= 0 && (impl >= 0 || part >= 0)) out[index] = Triple(impl, part, variant) }
        for (raw in text.lineSequence()) {
            val colon = raw.indexOf(':')
            if (colon < 0) continue
            val key = raw.substring(0, colon).trim().lowercase(Locale.US)
            val value = raw.substring(colon + 1).trim()
            when (key) {
                "processor" -> {
                    val n = value.toIntOrNull() ?: continue
                    flush(); index = n; impl = -1; part = -1; variant = -1
                }
                "cpu implementer" -> impl = parseNumber(value)
                "cpu part" -> part = parseNumber(value)
                "cpu variant" -> variant = parseNumber(value)
            }
        }
        flush()
        return out
    }

    /** The midr_el1 register file ("0x00000000410fd801") → (implementer, part, variant). */
    fun parseMidr(text: String): Triple<Int, Int, Int>? {
        val v = text.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: return null
        return Triple(((v shr 24) and 0xff).toInt(), ((v shr 4) and 0xfff).toInt(), ((v shr 20) and 0xf).toInt())
    }

    private fun parseNumber(s: String): Int =
        (if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toIntOrNull(16) else s.toIntOrNull()) ?: -1

    /**
     * Cores grouped by design and top clock (or scheduler capacity when a clock is unreadable), fastest first.
     * Efficiency = a small-core design (or, with no name, under [EFFICIENCY_PERCENT] % of the fastest); Prime = the
     * fastest of two or more remaining groups; the rest Performance.
     */
    fun clusters(cores: List<Core>): List<Cluster> {
        if (cores.isEmpty()) return emptyList()
        val useClock = cores.all { it.maxKhz > 0 }
        fun rating(c: Core): Long = if (useClock) c.maxKhz else c.capacity.toLong()
        val groups = cores.groupBy { Triple(it.implementer, it.part, rating(it)) }.values
            .sortedWith(compareByDescending<List<Core>> { rating(it.first()) }.thenBy { g -> g.minOf { it.index } })
        val peak = groups.maxOf { rating(it.first()) }
        val efficient = groups.map { g ->
            val name = g.first().name
            if (name != null) name in EFFICIENCY_NAMES
            else peak > 0 && rating(g.first()) > 0 && rating(g.first()) * 100 < peak * EFFICIENCY_PERCENT
        }
        val bigGroups = groups.indices.count { !efficient[it] }
        var primeGiven = false
        return groups.mapIndexed { i, g ->
            val tier = when {
                efficient[i] -> Tier.EFFICIENCY
                bigGroups >= 2 && !primeGiven -> { primeGiven = true; Tier.PRIME }
                else -> Tier.PERFORMANCE
            }
            Cluster(
                tier = tier,
                name = g.first().name,
                maxKhz = if (useClock) g.first().maxKhz else g.maxOf { it.maxKhz },
                minKhz = g.filter { it.minKhz > 0 }.minOfOrNull { it.minKhz } ?: 0,
                cores = g.map { it.index }.sorted(),
            )
        }
    }

    /** "Prime · Cortex-X4 · 3.30 GHz"; parts that are unknown are left out. */
    fun clusterTitle(c: Cluster, tierLabel: String = c.tier.label): String =
        listOfNotNull(tierLabel, c.name, if (c.maxKhz > 0) ghz(c.maxKhz) else null).joinToString(" · ")

    fun ghz(khz: Long): String = String.format(Locale.US, "%.2f GHz", khz / 1e6)

    /** Top clock of the fastest core in kHz, 0 when unreadable. */
    fun primeKhz(cores: List<Core> = read()): Long = cores.maxOfOrNull { it.maxKhz } ?: 0

    /** The chip's name for the core pickers ("Snapdragon G3 Gen 3 (SG8350P)"), empty when unknown. */
    @JvmStatic fun chipLabel(): String = runCatching { SocNames.label() }.getOrDefault("")

    @Volatile private var cached: List<Core>? = null

    /** This device's cores (read once per process). */
    fun read(): List<Core> = cached ?: readNow().also { cached = it }

    /** Number of cores the pickers show: the present cores, or the online count when that is unreadable. */
    fun count(): Int = read().size

    private fun readNow(): List<Core> {
        val n = presentCount().takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
        val count = n.coerceIn(1, 32)
        val ids = runCatching { parseCpuInfo(File("/proc/cpuinfo").readText()) }.getOrDefault(emptyMap())
        val base = "/sys/devices/system/cpu"
        return (0 until count).map { i ->
            val id = ids[i] ?: readText("$base/cpu$i/regs/identification/midr_el1")?.let(::parseMidr)
            Core(
                index = i,
                implementer = id?.first ?: -1,
                part = id?.second ?: -1,
                variant = id?.third ?: -1,
                maxKhz = readLong("$base/cpu$i/cpufreq/cpuinfo_max_freq") ?: policyValue(i, "cpuinfo_max_freq") ?: 0,
                minKhz = readLong("$base/cpu$i/cpufreq/cpuinfo_min_freq") ?: policyValue(i, "cpuinfo_min_freq") ?: 0,
                capacity = readLong("$base/cpu$i/cpu_capacity")?.toInt() ?: 0,
            )
        }
    }

    /** "/sys/devices/system/cpu/present" = "0-7" (or "0-3,6-7") → highest index + 1. */
    internal fun parsePresent(text: String): Int =
        text.trim().split(',').mapNotNull { r -> r.substringAfter('-').trim().toIntOrNull() }.maxOrNull()?.plus(1) ?: 0

    private fun presentCount(): Int = readText("/sys/devices/system/cpu/present")?.let(::parsePresent) ?: 0

    /** A value from the cpufreq policy that lists this core (when the per-core cpufreq link is missing). */
    private fun policyValue(core: Int, node: String): Long? {
        val policies = File("/sys/devices/system/cpu/cpufreq").listFiles { f -> f.name.startsWith("policy") } ?: return null
        for (p in policies) {
            val cpus = (readText("${p.path}/related_cpus") ?: readText("${p.path}/affected_cpus"))
                ?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }.orEmpty()
            if (core in cpus) return readLong("${p.path}/$node")
        }
        return null
    }

    private fun readText(path: String): String? = runCatching { File(path).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    private fun readLong(path: String): Long? = readText(path)?.toLongOrNull()?.takeIf { it > 0 }
}
