package com.winlator.star.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM coverage for the pure command / topology builders behind the root perf tier. */
class PerfBuildersTest {

    @Test fun writeCmd_usesPrintfAndQuotesBothArgs() {
        assertEquals(
            "printf %s 'performance' > '/sys/devices/system/cpu/cpu7/cpufreq/scaling_governor'",
            PerfCmd.writeCmd("/sys/devices/system/cpu/cpu7/cpufreq/scaling_governor", "performance")
        )
    }

    @Test fun writeCmd_escapesEmbeddedSingleQuote() {
        // A stray quote in a value must not break out of the quoting.
        assertEquals("printf %s 'a'\\''b' > '/x'", PerfCmd.writeCmd("/x", "a'b"))
    }

    @Test fun readCmd_isPlainCat() {
        assertEquals("cat '/sys/class/kgsl/kgsl-3d0/max_clock_mhz'",
            PerfCmd.readCmd("/sys/class/kgsl/kgsl-3d0/max_clock_mhz"))
    }

    @Test fun normalizeRead_stripsTrailingNewline() {
        assertEquals("schedutil", PerfCmd.normalizeRead("schedutil\n"))
    }

    @Test fun roundTrip_readThenWriteIsStable() {
        // Value captured from sysfs (with newline) must re-emit byte-identically without one.
        val captured = PerfCmd.normalizeRead("825000\n")
        assertEquals("printf %s '825000' > '/n'", PerfCmd.writeCmd("/n", captured))
    }

    // ── big-core detection ──────────────────────────────────────────────────────────────────────

    @Test fun bigCores_1plus3plus4_picksSinglePrime() {
        // SD8Gen-style: 1 prime (highest), 3 big, 4 little.
        val freqs = intArrayOf(1800, 1800, 1800, 1800, 2500, 2500, 2500, 3200)
        assertEquals(listOf(7), CpuTopology.bigCoreIndices(freqs))
        assertEquals("7", CpuTopology.bigCoreCpuList(freqs))
    }

    @Test fun bigCores_4plus4_picksAllFourBig() {
        val freqs = intArrayOf(1800, 1800, 1800, 1800, 2800, 2800, 2800, 2800)
        assertEquals("4,5,6,7", CpuTopology.bigCoreCpuList(freqs))
    }

    @Test fun bigCores_unreadable_returnsEmpty() {
        assertTrue(CpuTopology.bigCoreIndices(intArrayOf(0, 0, 0, 0)).isEmpty())
        assertEquals("", CpuTopology.bigCoreCpuList(intArrayOf()))
    }
}
