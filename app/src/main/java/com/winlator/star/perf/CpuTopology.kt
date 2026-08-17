package com.winlator.star.perf

import com.winlator.star.core.CPUStatus

/**
 * CPU-cluster detection for the "Prefer big cores" preset. The big/prime cluster is identified by
 * per-core `cpuinfo_max_freq` (the same node [CPUStatus.getMaxClockSpeed] and the HUD read): the
 * cores that share the single highest max frequency ARE the top cluster on every heterogeneous SoC
 * we target (4+3+1, 4+4, 6+2, …).
 *
 * The frequency→indices math is a pure function so it is unit-tested on the JVM; only
 * [detectBigCoreCpuList] touches the device.
 */
object CpuTopology {

    /**
     * Indices of the cores in the highest-frequency cluster, given each core's max frequency (kHz or
     * MHz — only relative ordering matters). Returns an empty list if the array is empty or every
     * reading is non-positive (unreadable), so callers fall back to the full affinity set.
     */
    fun bigCoreIndices(maxFreqs: IntArray): List<Int> {
        val top = maxFreqs.filter { it > 0 }.maxOrNull() ?: return emptyList()
        return maxFreqs.indices.filter { maxFreqs[it] == top }
    }

    /**
     * The big-core cluster as a comma-separated cpuList string (e.g. "6,7"), matching the format
     * [com.winlator.star.core.ProcessHelper.getAffinityMask] and the `.container` cpuList consume.
     * Empty string when detection fails (caller keeps the existing cpuList).
     */
    fun bigCoreCpuList(maxFreqs: IntArray): String =
        bigCoreIndices(maxFreqs).joinToString(",")

    /** Read each core's max frequency from sysfs and return the big-core cpuList, or "" on failure. */
    fun detectBigCoreCpuList(): String {
        val n = Runtime.getRuntime().availableProcessors()
        if (n <= 0) return ""
        val freqs = IntArray(n) { i ->
            // getMaxClockSpeed returns MHz (short); 0 when the node is unreadable.
            CPUStatus.getMaxClockSpeed(i).toInt()
        }
        return bigCoreCpuList(freqs)
    }
}
