package com.winlator.star.store

import com.winlator.star.store.download.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The pure decisions behind the Download Manager's pause / resume controls for Steam and Epic. */
class DownloadControlLogicTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun runningAndWaitingDownloads_pause() {
        assertEquals(PauseToggle.PAUSE, pauseToggleFor(DownloadState.DOWNLOADING))
        assertEquals(PauseToggle.PAUSE, pauseToggleFor(DownloadState.QUEUED))
    }

    @Test fun pausedDownload_resumes() {
        assertEquals(PauseToggle.RESUME, pauseToggleFor(DownloadState.PAUSED))
    }

    @Test fun finishedStates_haveNothingToToggle() {
        assertEquals(PauseToggle.NONE, pauseToggleFor(DownloadState.INSTALLED))
        assertEquals(PauseToggle.NONE, pauseToggleFor(DownloadState.FAILED))
        assertEquals(PauseToggle.NONE, pauseToggleFor(DownloadState.CANCELLED))
    }

    @Test fun percent_isWholeAndClamped() {
        assertEquals(0, downloadPercent(0L, 0L))
        assertEquals(0, downloadPercent(500L, 0L))
        assertEquals(50, downloadPercent(512L, 1024L))
        assertEquals(99, downloadPercent(999L, 1000L))
        assertEquals(100, downloadPercent(2000L, 1000L))
        assertEquals(0, downloadPercent(-5L, 1000L))
    }

    @Test fun partialData_missingOrEmptyFolder_isNone() {
        assertFalse(epicHasPartialData(null))
        assertFalse(epicHasPartialData(File(tmp.root, "nope")))
        assertFalse(epicHasPartialData(tmp.newFolder("empty")))
    }

    @Test fun partialData_aFileOrTheChunkCache_counts() {
        val withFile = tmp.newFolder("a")
        File(withFile, "data.bin").writeText("x")
        assertTrue(epicHasPartialData(withFile))
        val withCache = tmp.newFolder("b")
        File(withCache, ".chunks").mkdirs()
        assertTrue(epicHasPartialData(withCache))
    }

    @Test fun partialData_aPlainFileIsNotAFolder() {
        assertFalse(epicHasPartialData(tmp.newFile("file.txt")))
    }

    @Test fun pausedInfo_roundTrips() {
        val info = EpicPausedInfo("/data/files/imagefs/epic_games/Sugar Game", 37, 1_500_000L, 4_000_000L)
        assertEquals(info, decodeEpicPaused(encodeEpicPaused(info)))
    }

    @Test fun pausedInfo_aBarePathReadsAsZeroProgress() {
        assertEquals(EpicPausedInfo("/x/y", 0, 0L, 0L), decodeEpicPaused("/x/y"))
    }

    @Test fun pausedInfo_blankOrMissing_isNull() {
        assertNull(decodeEpicPaused(null))
        assertNull(decodeEpicPaused(""))
        assertNull(decodeEpicPaused("   "))
        assertNull(decodeEpicPaused("\n5\n1\n2"))
    }

    @Test fun pausedInfo_garbageNumbersAndRangesAreTamed() {
        assertEquals(EpicPausedInfo("/p", 0, 0L, 0L), decodeEpicPaused("/p\nx\ny\nz"))
        assertEquals(EpicPausedInfo("/p", 100, 0L, 0L), decodeEpicPaused("/p\n250\n-4\n-9"))
    }
}
