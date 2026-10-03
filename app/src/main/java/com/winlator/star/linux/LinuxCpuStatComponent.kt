package com.winlator.star.linux

import android.os.SystemClock
import android.util.Log
import com.winlator.star.xenvironment.EnvironmentComponent
import java.io.File

/**
 * A `/proc/stat` that moves.
 *
 * Android has refused apps `/proc/stat` since 8.0, and the session's stand-in for it was one
 * `cpu0` line of zeros - enough for glibc and Steam to start, but every CPU load in the session
 * read 0 % (the performance overlay showed one core, idle). The kernel still lets apps read each
 * core's idle residency (`cpuidle/stateN/time`, µs, the same label as the cpufreq files), which is
 * how overlays on Android work out CPU load: a core's busy time is the time that passed less the
 * time it spent idle. This writes `/proc/stat` from that, one line per core, refreshed twice a
 * second. Counters only grow - a sample's idle is never more than the time that passed - so a
 * reader taking the difference of two reads never sees a negative. Where `/proc/stat` is readable,
 * or the idle times are not, nothing is done.
 *
 * (From Droid-Deck/DroidDeck #127.)
 */
class LinuxCpuStatComponent(val file: File) : EnvironmentComponent() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val cores = (0 until coreCount()).toList()
    private val busy = LongArray(cores.size)
    private val idle = LongArray(cores.size)
    private val lastIdleUs = LongArray(cores.size)
    private var lastMs = 0L
    private val bootSeconds = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000

    /** Writes the first sample; true when it should be bound over `/proc/stat`. */
    fun prepare(): Boolean {
        if (File("/proc/stat").canRead() || cores.isEmpty() || idleUs(0) == null) return false
        file.parentFile?.mkdirs()
        lastMs = SystemClock.elapsedRealtime()
        for (i in cores) lastIdleUs[i] = idleUs(i) ?: 0L
        write()
        Log.i(TAG, "hud: /proc/stat from the cores' idle times (${cores.size} cores)")
        return true
    }

    override fun start() {
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                if (!running) break
                sample()
                write()
            }
        }, "cpu-stat").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun sample() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - lastMs) / MS_PER_TICK
        if (elapsed <= 0) return
        lastMs += elapsed * MS_PER_TICK
        for (i in cores) {
            // An offline core has no idle residency to count and runs nothing: all idle.
            val us = idleUs(i)
            val idleTicks = if (us == null) elapsed else ((us - lastIdleUs[i]) / US_PER_TICK).coerceIn(0, elapsed)
            if (us != null) lastIdleUs[i] = us
            idle[i] += idleTicks
            busy[i] += elapsed - idleTicks
        }
    }

    /** The whole file at once, renamed into place: a reader opens either the last sample or this one. */
    private fun write() {
        val text = StringBuilder()
        text.append("cpu  ${busy.sum()} 0 0 ${idle.sum()} 0 0 0 0 0 0\n")
        for (i in cores) text.append("cpu$i ${busy[i]} 0 0 ${idle[i]} 0 0 0 0 0 0\n")
        text.append("intr 0\nctxt 0\nbtime $bootSeconds\nprocesses 1\nprocs_running 1\nprocs_blocked 0\n")
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text.toString())
            if (!tmp.renameTo(file)) tmp.delete()
        } catch (e: Exception) {
        }
    }

    companion object {
        private const val TAG = "LinuxHud"
        private const val PERIOD_MS = 500L
        /** /proc/stat counts in USER_HZ, 100 a second on every Linux the guest runs. */
        private const val MS_PER_TICK = 10L
        private const val US_PER_TICK = 10_000L

        private fun coreCount(): Int =
            File("/sys/devices/system/cpu").list()?.count { it.matches(Regex("cpu\\d+")) } ?: 0

        /** The core's total idle residency in µs, or null when it has none to read (offline). */
        private fun idleUs(core: Int): Long? {
            val states = File("/sys/devices/system/cpu/cpu$core/cpuidle").listFiles { f -> f.name.startsWith("state") }
                ?: return null
            if (states.isEmpty()) return null
            var total = 0L
            for (state in states) {
                total += runCatching { File(state, "time").readText().trim().toLong() }.getOrNull() ?: return null
            }
            return total
        }
    }
}
