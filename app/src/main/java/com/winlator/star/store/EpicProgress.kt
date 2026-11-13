package com.winlator.star.store

import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreDownloadHooks
import com.winlator.star.store.download.formatDownloadSize
import com.winlator.star.store.download.formatDownloadSpeed
import com.winlator.star.store.download.formatEta
import java.util.concurrent.atomic.AtomicInteger

/**
 * Network speed for one Epic install: the bytes fetched over a sliding window (~4 s, so the lumpy
 * chunk completions of the parallel pool even out), then smoothed once more with an EMA so the
 * figure does not jitter. Fed by [EpicDownloadManager] on every chunk completion from several pool
 * threads, hence synchronized. Pure (the clock is a parameter on [add]) so it runs in JVM tests.
 */
class EpicSpeedMeter(
    private val windowMs: Long = 4_000L,
    private val alpha: Double = 0.4,
) {
    private val samples = ArrayList<LongArray>()
    private var lastBytes = 0L
    private var ema = 0.0
    private var seeded = false
    private var bps = 0L

    /** Record [bytesDone] (bytes fetched so far) at [nowMs]; returns the smoothed bytes/s (0 = unknown yet). */
    @Synchronized
    fun add(nowMs: Long, bytesDone: Long): Long {
        // Pool threads report out of order; the counter only ever grows.
        val b = maxOf(bytesDone, lastBytes)
        lastBytes = b
        samples.add(longArrayOf(nowMs, b))
        // Keep one sample at or before the window start so the span covers the whole window.
        while (samples.size > 2 && nowMs - samples[1][0] >= windowMs) samples.removeAt(0)
        val first = samples[0]
        val last = samples[samples.size - 1]
        val span = last[0] - first[0]
        if (span >= MIN_SPAN_MS) {
            val raw = (last[1] - first[1]).toDouble() * 1000.0 / span
            ema = if (seeded) alpha * raw + (1.0 - alpha) * ema else raw
            seeded = true
            bps = ema.toLong().coerceAtLeast(0L)
        }
        return bps
    }

    /** [add] stamped with the current clock. */
    @Synchronized
    fun addNow(bytesDone: Long): Long = add(System.currentTimeMillis(), bytesDone)

    /** Seconds left at the current smoothed speed, -1 while unknown. */
    @Synchronized
    fun etaSeconds(done: Long, total: Long): Long = epicEtaSeconds(done, total, bps)

    companion object {
        /** Shorter spans are too noisy to turn into a rate. */
        const val MIN_SPAN_MS = 500L
    }
}

/** Seconds to fetch the remaining bytes at [bps]; -1 when the speed or the sizes are unknown. */
fun epicEtaSeconds(done: Long, total: Long, bps: Long): Long =
    if (bps <= 0L || total <= 0L) -1L else (total - done).coerceAtLeast(0L) / bps

/**
 * Progress line for an Epic install: "Downloading… 45%  ·  1.2 GB / 4.5 GB  ·  12.4 MB/s  ·  ~4 min left".
 * Phases with no byte count yet (manifest, verifying files) keep the bare "[lead] 45%"; once all
 * bytes are in the install is only writing files out, so no size / speed is shown then.
 */
fun epicProgressLabel(
    pct: Int,
    done: Long,
    total: Long,
    speedBps: Long,
    etaSeconds: Long,
    lead: String = "Downloading…",
    includeEta: Boolean = true,
): String {
    if (total <= 0L || done <= 0L) return "$lead $pct%"
    if (done >= total) return "Writing files…"
    return listOf(
        "$lead $pct%",
        "${formatDownloadSize(done)} / ${formatDownloadSize(total)}",
        formatDownloadSpeed(speedBps),
        if (includeEta) formatEta(etaSeconds) else "",
    ).filter { it.isNotEmpty() }.joinToString("  ·  ")
}

/**
 * Carries the latest byte progress of one Epic install from [EpicDownloadManager.ProgressCallback.onBytes]
 * to the registry ticks and the labels (the manager calls `onBytes` right before the matching
 * `onProgress`). Every registry tick sends the last byte pair, speed and time left, as a pct-only tick
 * would reset them to unknown. [percent] is the share of the bytes fetched, capped at 99 until the
 * install completes and never going back, so the Download Manager card, the notification and the
 * detail page agree; before the first byte it is the manager's own percentage.
 */
internal class EpicProgressTracker(private val appName: String) {
    @Volatile var done = 0L
        private set
    @Volatile var total = 0L
        private set
    @Volatile var speedBps = 0L
        private set
    @Volatile var etaSeconds = -1L
        private set
    private val pct = AtomicInteger(0)

    fun onBytes(done: Long, total: Long, speedBps: Long, etaSeconds: Long) {
        this.done = maxOf(this.done, done)
        this.total = total
        this.speedBps = speedBps
        this.etaSeconds = etaSeconds
    }

    /** Percentage to show: bytes-based once bytes are flowing, else [cbPct] from the manager. */
    fun percent(cbPct: Int): Int {
        val t = total
        val d = done
        if (t <= 0L || d <= 0L) return cbPct.coerceIn(0, 100)
        val p = (minOf(d, t) * 100 / t).toInt().coerceAtMost(99)
        return pct.accumulateAndGet(p) { a, b -> maxOf(a, b) }
    }

    /** Push a registry tick (Download Manager + notification) and return the percentage used. */
    fun tick(cbPct: Int): Int {
        val p = percent(cbPct)
        StoreDownloadHooks.tick(
            Store.EPIC, appName, p,
            installDone = done, installTotal = total,
            speedBps = speedBps, etaSeconds = etaSeconds,
        )
        return p
    }

    fun label(pct: Int, lead: String = "Downloading…", includeEta: Boolean = true): String =
        epicProgressLabel(pct, done, total, speedBps, etaSeconds, lead, includeEta)
}
