package com.winlator.star.widget.gpu

import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * HUD GPU load and clock on Adreno devices whose kgsl sysfs nodes are SELinux-blocked for apps (for example the
 * Retroid Pocket Duo). HudMetrics asks here only when none of its own nodes can be read. One instance serves every
 * HUD in the process. The readers run while something keeps asking and close [IDLE_CLOSE_MS] after the last request.
 * Clock samples run on a worker thread, so no caller waits for a GPU job.
 */
object KgslGpuFallback {
    private const val KGSL = "/sys/class/kgsl/kgsl-3d0"
    private const val KGSL_DEV = "/dev/kgsl-3d0"
    private const val QCOM_GPU = "/sys/kernel/gpu"
    private const val WINDOW_MS = 1_000L
    private const val CLOCK_PERIOD_MS = 500L
    private const val CLOCK_STALE_MS = 3_000L
    private const val IDLE_CLOSE_MS = 5_000L
    private const val RETRY_MS = 30_000L

    private val LOAD_NODES = listOf(
        "$KGSL/gpubusy", "$KGSL/gpu_busy_percentage", "$KGSL/devfreq/gpu_load", "$KGSL/gpuload", "$QCOM_GPU/gpu_busy",
    )

    private val loadIsBlocked: Boolean by lazy { kgslPresent() && LOAD_NODES.none { File(it).canRead() } }
    private val clockIsBlocked: Boolean by lazy { kgslPresent() && clockNodes().none { File(it).canRead() } }

    private var reader: KgslBusy.Reader? = null
    private var load: MemstoreLoad? = null
    private var loadRetryAt = 0L

    private var sampler: KgslClock.Sampler? = null
    private var clockRetryAt = 0L
    private var clockPending = false
    private var clockAskedAt = 0L
    private var clockGoodAt = 0L
    private var clockLast: Int? = null

    private var lastAsk = 0L
    private var worker: ScheduledExecutorService? = null
    private var idleCheck: ScheduledFuture<*>? = null

    private fun kgslPresent(): Boolean = File(KGSL).exists() || File(KGSL_DEV).exists()

    private fun clockNodes(): List<String> {
        val nodes = mutableListOf(
            "$KGSL/clock_mhz", "$KGSL/gpu_clock", "$KGSL/gpuclk", "$KGSL/devfreq/cur_freq", "$QCOM_GPU/gpu_clock",
        )
        File("/sys/class/devfreq").list()?.sorted()?.forEach { node ->
            if (node.contains("kgsl")) nodes.add("/sys/class/devfreq/$node/cur_freq")
        }
        return nodes
    }

    /** True when this app can see kgsl but none of its GPU load nodes. */
    @JvmStatic fun loadBlocked(): Boolean = loadIsBlocked

    /** True when this app can see kgsl but none of its GPU clock nodes. */
    @JvmStatic fun clockBlocked(): Boolean = clockIsBlocked

    /** Whole-GPU busy share over about the last second, or null (not blocked, not working yet, or failed). */
    @JvmStatic @Synchronized fun busyPercent(): Int? {
        if (!loadIsBlocked) return null
        val now = SystemClock.elapsedRealtime()
        touch(now)
        if (reader == null) {
            if (now < loadRetryAt) return null
            val opened = KgslBusy.open()
            if (opened == null) {
                loadRetryAt = now + RETRY_MS
                return null
            }
            reader = opened
            load = MemstoreLoad(WINDOW_MS)
        }
        val r = reader ?: return null
        val l = load ?: return null
        r.run(true)
        val percent = l.percent(now, r.counts())
        if (l.failed()) {
            KgslBusy.status = "open, but its stamps did not move for 5 s (retried every 30 s)"
            closeReader()
            loadRetryAt = now + RETRY_MS
            return null
        }
        return percent
    }

    /** GPU clock in MHz from the GPU's cycle counter, or null. Returns the latest sample and asks for a new one. */
    @JvmStatic @Synchronized fun clockMhz(): Int? {
        if (!clockIsBlocked) return null
        val now = SystemClock.elapsedRealtime()
        touch(now)
        if (sampler == null && now < clockRetryAt) return null
        if (!clockPending && now - clockAskedAt >= CLOCK_PERIOD_MS) {
            clockPending = true
            clockAskedAt = now
            ensureWorker().execute(::sampleClock)
        }
        if (clockLast != null && now - clockGoodAt > CLOCK_STALE_MS) clockLast = null
        return clockLast
    }

    private fun sampleClock() {
        val s = synchronized(this) { sampler } ?: openSampler()
        val mhz = s?.mhz()
        synchronized(this) {
            if (mhz != null) {
                clockLast = mhz
                clockGoodAt = SystemClock.elapsedRealtime()
            }
            clockPending = false
        }
    }

    private fun openSampler(): KgslClock.Sampler? {
        val opened = KgslClock.open()
        synchronized(this) {
            if (opened == null) {
                clockRetryAt = SystemClock.elapsedRealtime() + RETRY_MS
            } else {
                sampler = opened
            }
        }
        return opened
    }

    /** Where the GPU load comes from, for the HUD diagnostics export. */
    @JvmStatic fun loadSource(): String = when {
        reader?.mode == KgslBusy.MODE_CONTEXTS -> "kgsl memstore per-context timestamps (all GPU work, no root)"
        reader != null -> "kgsl memstore ringbuffer timestamps (all GPU work, no root)"
        else -> "kgsl memstore (not open)"
    }

    /** Where the GPU clock comes from, for the HUD diagnostics export. */
    @JvmStatic fun clockSource(): String = "GPU cycle counter, own GPU job (no root)"

    /** Why the no-root readers work or not, for the HUD diagnostics export; empty where they are not needed. */
    @JvmStatic fun status(): String {
        if (!loadIsBlocked && !clockIsBlocked) return ""
        val parts = mutableListOf<String>()
        if (loadIsBlocked) parts.add("kgsl memstore ${KgslBusy.status}")
        if (clockIsBlocked) parts.add("GPU cycle counter ${KgslClock.status}")
        return parts.joinToString("; ")
    }

    private fun touch(now: Long) {
        lastAsk = now
        if (idleCheck == null) {
            idleCheck = ensureWorker().scheduleWithFixedDelay(::closeWhenIdle, 1, 1, TimeUnit.SECONDS)
        }
    }

    private fun ensureWorker(): ScheduledExecutorService =
        worker ?: Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "hud-gpu-kgsl").apply { isDaemon = true }
        }.also { worker = it }

    @Synchronized private fun closeWhenIdle() {
        if (SystemClock.elapsedRealtime() - lastAsk < IDLE_CLOSE_MS) return
        closeReader()
        sampler?.close()
        sampler = null
        clockLast = null
        idleCheck?.cancel(false)
        idleCheck = null
    }

    private fun closeReader() {
        reader?.close()
        reader = null
        load = null
    }
}
