package com.winlator.star.deviceinfo

import com.winlator.star.deviceinfo.CpuCores.Core
import com.winlator.star.deviceinfo.CpuCores.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Core names, clusters and chip names from real device readings (2026-11-23). */
class CpuCoresTest {

    private fun cpuinfo(vararg parts: Pair<Int, Int>): String = parts.withIndex().joinToString("\n\n") { (i, p) ->
        "processor\t: $i\nBogoMIPS\t: 38.40\nFeatures\t: fp asimd\nCPU implementer\t: 0x%x\nCPU architecture: 8\nCPU variant\t: 0x0\nCPU part\t: 0x%03x\nCPU revision\t: 1"
            .format(p.first, p.second)
    }

    private fun cores(ids: Map<Int, Triple<Int, Int, Int>>, maxMhz: List<Int>): List<Core> =
        maxMhz.mapIndexed { i, mhz -> ids[i].let { Core(i, it?.first ?: -1, it?.second ?: -1, it?.third ?: -1, mhz * 1000L) } }

    // AYANEO Pocket FIT (SG8350P): /proc/cpuinfo + cpuinfo_max_freq read on the device.
    private val fit = cores(
        CpuCores.parseCpuInfo(cpuinfo(0x41 to 0xd80, 0x41 to 0xd80, 0x41 to 0xd81, 0x41 to 0xd81, 0x41 to 0xd81,
            0x41 to 0xd81, 0x41 to 0xd81, 0x41 to 0xd82)),
        listOf(2265, 2265, 3148, 3148, 3148, 2956, 2956, 3302),
    )

    @Test fun fit_fourClustersNamedAndOrdered() {
        val c = CpuCores.clusters(fit)
        assertEquals(listOf(Tier.PRIME, Tier.PERFORMANCE, Tier.PERFORMANCE, Tier.EFFICIENCY), c.map { it.tier })
        assertEquals(listOf("Cortex-X4", "Cortex-A720", "Cortex-A720", "Cortex-A520"), c.map { it.name })
        assertEquals(listOf(listOf(7), listOf(2, 3, 4), listOf(5, 6), listOf(0, 1)), c.map { it.cores })
        assertEquals("Prime · Cortex-X4 · 3.30 GHz", CpuCores.clusterTitle(c[0]))
        assertEquals("Efficiency · Cortex-A520 · 2.27 GHz", CpuCores.clusterTitle(c[3]))
    }

    // Pocket FIT Elite (SM8750P): every core Oryon 0x51/0x001, variant 0x4 (cpu0-5) and 0x3 (cpu6-7).
    @Test fun elite_oryonPrimeAndPerformance() {
        val ids = (0..7).associateWith { Triple(0x51, 0x001, if (it < 6) 0x4 else 0x3) }
        val c = CpuCores.clusters(cores(ids, listOf(3532, 3532, 3532, 3532, 3532, 3532, 4320, 4320)))
        assertEquals(2, c.size)
        assertEquals("Prime · Oryon · 4.32 GHz", CpuCores.clusterTitle(c[0]))
        assertEquals(listOf(6, 7), c[0].cores)
        assertEquals("Performance · Oryon · 3.53 GHz", CpuCores.clusterTitle(c[1]))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), c[1].cores)
    }

    // Retroid Pocket Duo (CQ7790S): A520 x3, A720 x4, A720 x1.
    @Test fun duo_primeIsTheFastA720() {
        val ids = CpuCores.parseCpuInfo(cpuinfo(0x41 to 0xd80, 0x41 to 0xd80, 0x41 to 0xd80, 0x41 to 0xd81,
            0x41 to 0xd81, 0x41 to 0xd81, 0x41 to 0xd81, 0x41 to 0xd81))
        val c = CpuCores.clusters(cores(ids, listOf(1843, 1843, 1843, 2400, 2400, 2400, 2400, 2803)))
        assertEquals(listOf("Prime · Cortex-A720 · 2.80 GHz", "Performance · Cortex-A720 · 2.40 GHz",
            "Efficiency · Cortex-A520 · 1.84 GHz"), c.map { CpuCores.clusterTitle(it) })
    }

    // Galaxy Z Fold 8 Ultra (SM8850, Samsung clocks from the user's screenshot); core ids unknown here.
    @Test fun unnamedCores_tierFromClocks() {
        val c = CpuCores.clusters(cores(emptyMap(), listOf(3630, 3630, 3630, 3630, 3630, 3630, 4740, 4740)))
        assertEquals("Prime · 4.74 GHz", CpuCores.clusterTitle(c[0]))
        assertEquals("Performance · 3.63 GHz", CpuCores.clusterTitle(c[1]))
    }

    @Test fun singleCluster_isPerformance() {
        val ids = (0..3).associateWith { Triple(0x41, 0xd41, 0) }
        val c = CpuCores.clusters(cores(ids, listOf(2400, 2400, 2400, 2400)))
        assertEquals(listOf(Tier.PERFORMANCE), c.map { it.tier })
    }

    @Test fun noClocks_groupsByCapacity() {
        val c = CpuCores.clusters(listOf(Core(0, capacity = 379), Core(1, capacity = 379), Core(2, capacity = 1024)))
        assertEquals(listOf(listOf(2), listOf(0, 1)), c.map { it.cores })
        assertEquals(Tier.EFFICIENCY, c[1].tier)
    }

    @Test fun midrAndPresent() {
        assertEquals(Triple(0x41, 0xd82, 0x0), CpuCores.parseMidr("0x00000000410fd821"))
        assertEquals(Triple(0x51, 0x001, 0x3), CpuCores.parseMidr("0x00000000513f0014"))
        assertNull(CpuCores.parseMidr("garbage"))
        assertEquals(8, CpuCores.parsePresent("0-7"))
        assertEquals(8, CpuCores.parsePresent("0-3,6-7"))
        assertEquals(1, CpuCores.parsePresent("0"))
    }

    @Test fun chipNames() {
        assertEquals("Snapdragon G3 Gen 3", SocNames.name("SG8350P", "pineapple", "AYANEO", 3302400))
        assertEquals("Snapdragon 8 Elite", SocNames.name("SM8750P", "sun", "AYANEO", 4320000))
        assertEquals("Dragonwing Q-7790", SocNames.name("CQ7790S", "sun", "Moorechip", 2803200))
        assertEquals("Snapdragon 8 Elite Gen 5 for Galaxy", SocNames.name("SM8850", "canoe", "samsung", 4740000))
        assertEquals("Snapdragon 8 Elite Gen 5", SocNames.name("SM8850", "canoe", "OnePlus", 4610000))
        assertEquals("Snapdragon 8 Gen 3 for Galaxy", SocNames.name("SM8650-AC", "pineapple", "samsung", 3393600))
        assertEquals("Snapdragon 870", SocNames.name("SM8250-AC", "kona", "Xiaomi", 3187200))
        // An unknown non-phone part is not named after its platform; an unknown phone part still is.
        assertNull(SocNames.name("CQ1234X", "sun", "Moorechip", 0))
        assertEquals("Snapdragon 8 Elite family", SocNames.name("SM9999", "sun", "Xiaomi", 0))
    }
}
