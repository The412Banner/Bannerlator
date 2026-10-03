package com.winlator.star.linux

import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.winlator.star.xenvironment.EnvironmentComponent
import java.io.File
import java.io.RandomAccessFile

/**
 * The session's `/sys/class/hwmon`, as mangoapp (Deck mode's performance overlay) looks there.
 *
 * MangoHud finds a CPU's temperature by an hwmon sensor named `cpuN_thermal`, and an Adreno's
 * temperature, fan and power by one whose name has `gpuss` in it - a mainline Arm kernel's names.
 * An Android kernel has neither (its hwmon holds the PMIC's sensors, or nothing), and where the
 * app may not list hwmon at all the overlay logged an error twice a second. So the overlay showed
 * no CPU temperature and no fan. This is two sensors named so:
 *
 * - `hwmon0` (`cpu0_thermal`): `temp1_input` linked to a CPU thermal zone the app can read;
 * - `hwmon1` (`gpuss0_thermal`): `temp1_input` linked to the GPU's zone, and `fan1_input` with
 *   the device's fan speed where it has a fan the app can read - an AYN handheld's tachometer
 *   (`/sys/class/gpio5_pwm2/speed`), or a `fan*_input` in the device's own hwmon;
 * - `hwmon2` (`slg4ax46073v`): the same fan, for Valve's own Qualcomm reader (msm.cpp), which
 *   looks for its fan controller by that name and halves what it reads - that part reports twice
 *   the real speed - so this one holds twice it.
 *
 * Nothing in a phone's own hwmon is read by the overlay, so standing in for it loses nothing.
 *
 * (From Droid-Deck/DroidDeck #127.)
 */
class LinuxHwmonComponent(val dir: File, private val root: File) : EnvironmentComponent() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var fanSource: String? = null
    private val fanFile = File(dir, "hwmon1/fan1_input")
    private val valveFanFile = File(dir, "hwmon2/fan1_input")
    private var lastRpm = 0L
    private var lastRpmAt = 0L

    /** Writes the sensors; true when the directory should be bound over `/sys/class/hwmon`. */
    fun prepare(): Boolean = try {
        dir.mkdirs()
        clear(dir)
        val cpu = LinuxRuntime.cpuTempSource()
        if (cpu != null) sensor("hwmon0", "cpu0_thermal").also { link(cpu, File(it, "temp1_input")) }
        val gpu = LinuxRuntime.gpuTempSource()
        fanSource = FAN_SOURCES().firstOrNull { readNumber(it) != null }
        if (gpu != null || fanSource != null) {
            val sensor = sensor("hwmon1", "gpuss0_thermal")
            if (gpu != null) link(gpu, File(sensor, "temp1_input"))
            if (fanSource != null) {
                sensor("hwmon2", "slg4ax46073v")
                writeFan(sampleFan())
            }
        }
        Log.i(TAG, "hud: hwmon cpu temp ${cpu ?: "none"}, gpu temp ${gpu ?: "none"}, fan ${fanSource ?: "none"}")
        cpu != null || gpu != null || fanSource != null
    } catch (e: Exception) {
        Log.w(TAG, "hud: could not write the session's hwmon: $e")
        false
    }

    override fun start() {
        if (fanSource == null) return
        running = true
        thread = Thread({
            while (running) {
                try { Thread.sleep(FAN_PERIOD_MS) } catch (e: InterruptedException) { break }
                // A tachometer read busy-waits 100 ms in the kernel: only while the overlay shows.
                if (running && LinuxOverlayShown.check(root)) writeFan(sampleFan())
            }
        }, "hwmon-fan").apply { isDaemon = true; start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /**
     * The fan's speed in RPM. An AYN tachometer counts pulses over a short window per read and
     * often catches none, reading 0 while the fan turns; a reading is held until a newer one
     * comes or [FAN_HOLD_MS] passes with only zeros, when the fan is taken to have stopped.
     */
    private fun sampleFan(): Long {
        val now = SystemClock.elapsedRealtime()
        val rpm = fanSource?.let { readNumber(it) } ?: 0L
        if (rpm > 0) {
            lastRpm = rpm
            lastRpmAt = now
        } else if (now - lastRpmAt > FAN_HOLD_MS) {
            lastRpm = 0
        }
        return lastRpm
    }

    /** In place and at one length, so a reader that keeps the file open sees each value whole. */
    private fun writeFan(rpm: Long) {
        for ((file, value) in listOf(fanFile to rpm, valveFanFile to rpm * 2)) {
            try {
                RandomAccessFile(file, "rw").use { it.seek(0); it.write(String.format("%6d\n", value).toByteArray()) }
            } catch (e: Exception) {
            }
        }
    }

    private fun sensor(name: String, label: String): File =
        File(dir, name).apply { mkdirs(); File(this, "name").writeText("$label\n") }

    private fun link(target: String, link: File) {
        java.nio.file.Files.deleteIfExists(link.toPath())
        Os.symlink(target, link.path)
    }

    /** Only files and links are made here; deleting a link must not follow it into sysfs. */
    private fun clear(root: File) {
        root.listFiles()?.forEach { f ->
            if (!java.nio.file.Files.isSymbolicLink(f.toPath()) && f.isDirectory) clear(f)
            java.nio.file.Files.deleteIfExists(f.toPath())
        }
    }

    companion object {
        private const val TAG = "LinuxHud"
        /** The AYN tachometer reads 0 if read again within a couple of seconds. */
        private const val FAN_PERIOD_MS = 3_000L
        private const val FAN_HOLD_MS = 10_000L

        private val FAN_SOURCES = {
            val sources = mutableListOf("/sys/class/gpio5_pwm2/speed", "/sys/class/gpio5_pwm/speed")
            File("/sys/class/hwmon").listFiles()?.sortedBy { it.name }?.forEach { hwmon ->
                hwmon.listFiles { f -> f.name.matches(Regex("fan\\d+_input")) }?.sortedBy { it.name }
                    ?.forEach { sources.add(it.path) }
            }
            sources
        }

        private fun readNumber(path: String): Long? =
            runCatching { File(path).readText().trim().toLong() }.getOrNull()?.takeIf { it >= 0 }
    }
}
