package com.winlator.star.store

import com.winlator.star.store.download.formatDownloadSize
import com.winlator.star.store.download.formatDownloadSpeed
import com.winlator.star.store.download.formatEta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed smoothing, ETA and the progress line of an Epic install ([EpicSpeedMeter], [epicProgressLabel]). Plain JVM. */
class EpicProgressTest {

    private val mb = 1_048_576L

    @Test
    fun meterIsUnknownUntilEnoughTimeHasPassed() {
        val m = EpicSpeedMeter()
        assertEquals(0L, m.add(0L, 0L))
        // 200 ms is below the minimum span: no rate yet.
        assertEquals(0L, m.add(200L, 5 * mb))
    }

    @Test
    fun meterReportsSteadyRate() {
        val m = EpicSpeedMeter()
        var bps = 0L
        for (i in 0..10) bps = m.add(i * 1000L, i * 10 * mb)
        // 10 MB every second (a byte of slack for floating point).
        assertTrue(Math.abs(bps - 10 * mb) <= 1L)
    }

    @Test
    fun meterIgnoresOutOfOrderByteCounts() {
        val m = EpicSpeedMeter()
        val twin = EpicSpeedMeter()
        m.add(0L, 0L); twin.add(0L, 0L)
        m.add(1000L, 10 * mb); twin.add(1000L, 10 * mb)
        // A pool thread reporting an older (smaller) total counts as no progress, never negative bytes.
        val bps = m.add(2000L, 5 * mb)
        assertEquals(twin.add(2000L, 10 * mb), bps)
        assertTrue(bps >= 0L)
    }

    @Test
    fun meterSmoothsASpeedChange() {
        val m = EpicSpeedMeter()
        var bytes = 0L
        var bps = 0L
        for (i in 0..8) { bytes += 10 * mb; bps = m.add(i * 1000L, bytes) }
        val steady = bps
        // The link suddenly doubles: the reported speed rises but does not jump to the new rate at once.
        bytes += 20 * mb
        val next = m.add(9000L, bytes)
        assertTrue(next > steady)
        assertTrue(next < 20 * mb)
    }

    @Test
    fun meterOnlyLooksAtTheWindow() {
        val m = EpicSpeedMeter(windowMs = 4000L, alpha = 1.0)
        // Fast for 10 s, then stalled for 10 s: with no smoothing the rate must fall to 0.
        for (i in 0..10) m.add(i * 1000L, i * 100 * mb)
        var bps = 0L
        for (i in 11..20) bps = m.add(i * 1000L, 10 * 100 * mb)
        assertEquals(0L, bps)
    }

    @Test
    fun etaFromSpeed() {
        assertEquals(40L, epicEtaSeconds(400 * mb, 800 * mb, 10 * mb))
        assertEquals(0L, epicEtaSeconds(900 * mb, 800 * mb, 10 * mb))
        assertEquals(-1L, epicEtaSeconds(0L, 800 * mb, 0L))
        assertEquals(-1L, epicEtaSeconds(0L, 0L, 10 * mb))
    }

    @Test
    fun meterEtaUsesItsOwnSpeed() {
        val m = EpicSpeedMeter()
        assertEquals(-1L, m.etaSeconds(0L, 100 * mb))
        for (i in 0..4) m.add(i * 1000L, i * 10 * mb)
        assertEquals(6L, m.etaSeconds(40 * mb, 100 * mb))
    }

    @Test
    fun labelBeforeAnyBytesIsJustThePercentage() {
        assertEquals("Downloading… 0%", epicProgressLabel(0, 0L, 0L, 0L, -1L))
        // Total known but nothing fetched yet: still no "0 B" counter.
        assertEquals("Downloading… 0%", epicProgressLabel(0, 0L, 4 * mb, 0L, -1L))
        assertEquals("Repairing… 12%", epicProgressLabel(12, 0L, 0L, 0L, -1L, lead = "Repairing…"))
    }

    @Test
    fun labelShowsBytesSpeedAndEta() {
        val done = 1_288_490_189L // ~1.2 GB
        val total = 4_831_838_208L // 4.5 GB
        val label = epicProgressLabel(26, done, total, 12 * mb, 300L)
        assertEquals(
            "Downloading… 26%  ·  ${formatDownloadSize(done)} / ${formatDownloadSize(total)}" +
                "  ·  ${formatDownloadSpeed(12 * mb)}  ·  ${formatEta(300L)}",
            label,
        )
    }

    @Test
    fun labelOmitsUnknownSpeedAndEta() {
        val label = epicProgressLabel(5, 10 * mb, 200 * mb, 0L, -1L)
        assertEquals("Downloading… 5%  ·  ${formatDownloadSize(10 * mb)} / ${formatDownloadSize(200 * mb)}", label)
    }

    @Test
    fun labelCanDropTheEta() {
        val label = epicProgressLabel(5, 10 * mb, 200 * mb, 2 * mb, 95L, includeEta = false)
        assertEquals(
            "Downloading… 5%  ·  ${formatDownloadSize(10 * mb)} / ${formatDownloadSize(200 * mb)}" +
                "  ·  ${formatDownloadSpeed(2 * mb)}",
            label,
        )
    }

    @Test
    fun labelWhileWritingFilesHasNoSizeOrSpeed() {
        assertEquals("Writing files…", epicProgressLabel(99, 200 * mb, 200 * mb, 0L, -1L))
    }

    @Test
    fun trackerPercentFollowsBytesAndNeverGoesBack() {
        val t = EpicProgressTracker("app")
        // Before any bytes: the manager's own percentage.
        assertEquals(0, t.percent(0))
        t.onBytes(50 * mb, 100 * mb, 5 * mb, 10L)
        assertEquals(50, t.percent(40))
        // A pool thread reports an older counter: neither the counter nor the percentage shrink.
        t.onBytes(40 * mb, 100 * mb, 5 * mb, 12L)
        assertEquals(50 * mb, t.done)
        assertEquals(50, t.percent(32))
        // All bytes in (file writing): capped at 99 until the install completes.
        t.onBytes(100 * mb, 100 * mb, 0L, -1L)
        assertEquals(99, t.percent(85))
        assertEquals(0L, t.speedBps)
        assertEquals(-1L, t.etaSeconds)
    }
}
