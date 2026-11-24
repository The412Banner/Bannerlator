package com.winlator.star.container

import com.winlator.star.container.LayerLine.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layer update lines: one family + arch + Proton major. Descriptions and labels are real ones, shortened. */
class LayerLineTest {

    // profile.json descriptions of installed layers (v14 GE 11-3 from build-ge-proton-11.0-3.yml, the rest from the FIT).
    private val geOld113 = "Proton 11.0-3 arm64ec (bionic) — Valve + fast-yield gate + FEX-unixlib loader + xrandr/xrender + " +
        "GE-Proton11-3 game-fixes (battlenet, maplestory, dai_xinput, eac, pso2, assettocorsa, silence-starcitizen, vgsoh, WM_ACTIVATEAPP)"
    private val ge119 = "GE-Proton 11.0-9 arm64ec (bionic) — GE-Proton11-9's own Valve Wine 981e2f0b9007 base (bleeding-edge, 2026-10-10)"
    private val ge1114 = "GE-Proton 11.0-14 arm64ec (bionic) — GE-Proton11-14's own Valve Wine a47c73f52fd7 base (bleeding-edge, 2026-11-20)"
    private val valve112 = "Proton 11.0-2 arm64ec (bionic) — stock Valve + fast-yield gate + FEX-unixlib loader + DirectAudio v1.3.2"
    private val valve113 = "Proton 11.0-3 arm64ec (bionic) — Valve Proton 11.0-3 base (wine 957ac7cfa4f5) + fast-yield gate + " +
        "FEX-unixlib loader ... Black Ops III ARM64EC start-up crash (DroidDeck-Proton #34)."
    private val valve104 = "Proton 10.0-4 arm64ec - Windows compatibility layer with improved gaming support + DirectAudio v1.3.2"
    private val dd128 = "DroidDeck-Proton 11.0-12.8 arm64ec (bionic) — Valve Wine fda79a585939 base (bleeding-edge, 2026-10-31, " +
        "as GE-Proton11-12) + our Android stack"
    private val cachy = "Proton-CachyOS 11.0-20261005 arm64ec (bionic) EXPERIMENTAL — CachyOS base + our full Android stack"

    private fun installed(desc: String, vararg catalogLabels: String) = LayerLine.familyOfInstalled(desc, catalogLabels.toList())

    @Test fun catalogLabels_nameTheirFamily() {
        assertEquals(Family.GE, LayerLine.familyOfLabel("GE-Proton-11.0-14-arm64ec (v17)"))
        assertEquals(Family.DROIDDECK, LayerLine.familyOfLabel("DroidDeck-Proton-11.0-12.8-arm64ec (v17)"))
        assertEquals(Family.CACHYOS, LayerLine.familyOfLabel("Proton-CachyOS-11.0-20261005-arm64ec (v14)"))
        assertEquals(Family.VALVE, LayerLine.familyOfLabel("Proton-11.0-3-arm64ec (v17)"))
        assertNull(LayerLine.familyOfLabel("proton-9.0-arm64ec"))
        assertNull(LayerLine.familyOfLabel(null))
    }

    @Test fun installedDescriptions_nameTheirFamily() {
        assertEquals(Family.GE, installed(ge119))
        assertEquals(Family.GE, installed(ge1114))
        assertEquals(Family.DROIDDECK, installed(dd128))
        assertEquals(Family.CACHYOS, installed(cachy))
        assertEquals(Family.VALVE, installed(valve112))
        assertEquals(Family.VALVE, installed(valve113))
        assertEquals(Family.VALVE, installed(valve104))
    }

    @Test fun oldGeLayer_withoutGePrefix_isStillGe() {
        assertEquals(Family.GE, installed(geOld113))
        assertEquals(Family.GE, installed(geOld113, "GE-Proton-11.0-3-arm64ec (v14)"))
    }

    @Test fun catalogRow_settlesAPlainProtonDescription() {
        assertEquals(Family.GE, installed("Proton 11.0-3 arm64ec (bionic) — Valve + fast-yield gate", "GE-Proton-11.0-3-arm64ec (v14)"))
        assertEquals(Family.VALVE, installed("Wayland desktop test build — Proton 11.0-2 arm64ec (bionic)", "Proton-11.0-2-arm64ec (v16)"))
        assertNull(installed("Wayland desktop test build — Proton 11.0-2 arm64ec (bionic)"))
        // Rows that disagree settle nothing: the description decides.
        assertEquals(Family.VALVE, installed(valve113, "GE-Proton-11.0-3-arm64ec (v17)", "Proton-11.0-3-arm64ec (v17)"))
        // A self-named description is never overridden by a row.
        assertEquals(Family.DROIDDECK, installed(dd128, "GE-Proton-11.0-12.8-arm64ec (v17)"))
    }

    @Test fun ge113_and_valve113_shareAName_butAreNotOneLine() {
        val ge = installed(geOld113, "GE-Proton-11.0-3-arm64ec (v14)")
        val valveInstalled = installed(valve113)
        val valveRow = LayerLine.familyOfLabel("Proton-11.0-3-arm64ec (v17)")
        assertFalse(LayerLine.sameLine("11.0-3-arm64ec", ge, "11.0-3-arm64ec", valveInstalled))
        assertFalse(LayerLine.sameLine("11.0-3-arm64ec", ge, "11.0-3-arm64ec", valveRow))
        assertFalse(LayerLine.sameLine("11.0-3-arm64ec", valveInstalled, "11.0-3-arm64ec", ge))
    }

    @Test fun ge119_updatesTo_ge1114() {
        assertTrue(LayerLine.sameLine("11.0-9-arm64ec", installed(ge119), "11.0-14-arm64ec", LayerLine.familyOfLabel("GE-Proton-11.0-14-arm64ec (v17)")))
        assertTrue(LayerLine.isNewer("11.0-9-arm64ec", 16, "11.0-14-arm64ec", 17))
        assertFalse(LayerLine.isNewer("11.0-14-arm64ec", 17, "11.0-9-arm64ec", 16))
        assertTrue(LayerLine.sameLine("11.0-3-arm64ec", installed(geOld113), "11.0-14-arm64ec", installed(ge1114)))
        assertTrue(LayerLine.isNewer("11.0-3-arm64ec", 14, "11.0-14-arm64ec", 17))
    }

    @Test fun geAndValve_neverCross() {
        assertFalse(LayerLine.sameLine("11.0-9-arm64ec", Family.GE, "11.0-3-arm64ec", Family.VALVE))
        assertFalse(LayerLine.sameLine("11.0-2-arm64ec", Family.VALVE, "11.0-14-arm64ec", Family.GE))
        assertFalse(LayerLine.sameLine("11.0-12.8-arm64ec", Family.DROIDDECK, "11.0-14-arm64ec", Family.GE))
    }

    @Test fun droidDeck_stillOneLineAcrossVersions() {
        assertTrue(LayerLine.sameLine("11.0-9.1-arm64ec", Family.DROIDDECK, "11.0-12.8-arm64ec", installed(dd128)))
        assertTrue(LayerLine.isNewer("11.0-9.1-arm64ec", 14, "11.0-12.8-arm64ec", 16))
        assertTrue(LayerLine.isNewer("11.0-12.8-arm64ec", 16, "11.0-12.8-arm64ec", 17))
        assertFalse(LayerLine.sameLine("11.0-9.1-arm64ec", Family.DROIDDECK, "11.0-12.8-x86_64", Family.DROIDDECK))
    }

    @Test fun valve112_updatesTo_valve113_andCachyOsMovesOn() {
        assertTrue(LayerLine.sameLine("11.0-2-arm64ec", installed(valve112), "11.0-3-arm64ec", installed(valve113)))
        assertTrue(LayerLine.isNewer("11.0-2-arm64ec", 17, "11.0-3-arm64ec", 17))
        assertTrue(LayerLine.sameLine("11.0-20260703-arm64ec", Family.CACHYOS, "11.0-20261005-arm64ec", installed(cachy)))
        assertTrue(LayerLine.isNewer("11.0-20260703-arm64ec", 14, "11.0-20261005-arm64ec", 14))
    }

    @Test fun archMismatch_isNotALine() {
        assertFalse(LayerLine.sameLine("11.0-9-arm64ec", Family.GE, "11.0-14-x86_64", Family.GE))
        assertFalse(LayerLine.sameLine("11.0-2-x86_64", Family.VALVE, "11.0-3-arm64ec", Family.VALVE))
    }

    @Test fun majorMismatch_isNotALine() {
        assertFalse(LayerLine.sameLine("10.0-34-arm64ec", Family.GE, "11.0-14-arm64ec", Family.GE))
        assertFalse(LayerLine.sameLine("11.0-9-arm64ec", Family.GE, "10.0-34-arm64ec", Family.GE))
        assertFalse(LayerLine.sameLine("10.0-4-arm64ec", installed(valve104), "11.0-2-arm64ec", installed(valve112)))
    }

    @Test fun unknownFamily_keepsTheOldExactNameRule() {
        assertTrue(LayerLine.sameLine("wine-10-x86_64", null, "wine-10-x86_64", null))
        assertFalse(LayerLine.sameLine("wine-9.0", null, "wine-10.0", null))
        // One side unknown: never a line, even under one name.
        assertFalse(LayerLine.sameLine("11.0-3-arm64ec", null, "11.0-3-arm64ec", Family.VALVE))
        assertFalse(LayerLine.sameLine("11.0-3-arm64ec", Family.GE, "11.0-3-arm64ec", null))
        assertFalse(LayerLine.sameLine(null, Family.GE, "11.0-14-arm64ec", Family.GE))
    }

    /** The live v17 catalog's Proton rows (winlator-contents 5a77857d) run through the catalog tier's filters. */
    @Test fun liveCatalog_offersTheRightRow() {
        data class Row(val label: String, val versionName: String?, val verCode: Int)
        val rows = listOf(
            Row("Proton-11.0-1-arm64ec (v15)", "11.0-1-arm64ec", 15),
            Row("Proton-11.0-2-arm64ec (v17)", "11.0-2-arm64ec", 17),
            Row("Proton-11.0-2-x86_64 (v17)", "11.0-2-x86_64", 17),
            Row("Proton-11.0-3-arm64ec (v17)", null, 17),
            Row("Proton-11.0-3-x86_64 (v17)", null, 17),
            Row("Proton-10.0-4-arm64ec (v17)", "10.0-4-arm64ec", 17),
            Row("GE-Proton-11.0-3-arm64ec (v14)", "11.0-3-arm64ec", 14),
            Row("GE-Proton-11.0-5-arm64ec (v14)", "11.0-5-arm64ec", 14),
            Row("GE-Proton-11.0-7-x86_64 (v14)", "11.0-7-x86_64", 14),
            Row("GE-Proton-11.0-7.1-arm64ec (v14)", "11.0-7.1-arm64ec", 14),
            Row("GE-Proton-11.0-14-arm64ec (v17)", "11.0-14-arm64ec", 17),
            Row("GE-Proton-11.0-14-x86_64 (v17)", "11.0-14-x86_64", 17),
            Row("DroidDeck-Proton-11.0-12.8-arm64ec (v17)", "11.0-12.8-arm64ec", 17),
            Row("GE-Proton-10.0-34-arm64ec (v17)", "10.0-34-arm64ec", 17),
            Row("Proton-CachyOS-11.0-20261005-arm64ec (v14)", "11.0-20261005-arm64ec", 14),
        )
        fun offer(name: String, code: Int, family: Family?): String? = rows
            .filter { LayerLine.sameLine(name, family, it.versionName, LayerLine.familyOfLabel(it.label)) }
            .filter { LayerLine.archOf(it.versionName) == LayerLine.archOf(name) }
            .filter { LayerLine.isNewer(name, code, it.versionName, it.verCode) }
            .maxWithOrNull(compareBy<Row, List<Int>>(LayerLine.VERSION_ORDER) { LayerLine.versionOrder(it.versionName) }.thenBy { it.verCode })
            ?.label
        assertEquals("GE-Proton-11.0-14-arm64ec (v17)", offer("11.0-9-arm64ec", 16, installed(ge119)))
        assertEquals("GE-Proton-11.0-14-x86_64 (v17)", offer("11.0-9-x86_64", 16, Family.GE))
        assertEquals("GE-Proton-11.0-14-arm64ec (v17)", offer("11.0-3-arm64ec", 14, installed(geOld113, "GE-Proton-11.0-3-arm64ec (v14)")))
        assertEquals("Proton-11.0-2-arm64ec (v17)", offer("11.0-2-arm64ec", 16, installed(valve112)))
        assertEquals("Proton-11.0-2-arm64ec (v17)", offer("11.0-1-arm64ec", 15, Family.VALVE))
        assertEquals("DroidDeck-Proton-11.0-12.8-arm64ec (v17)", offer("11.0-12.3-arm64ec", 16, Family.DROIDDECK))
        assertEquals("GE-Proton-10.0-34-arm64ec (v17)", offer("10.0-34-arm64ec", 16, Family.GE))
        assertEquals("Proton-10.0-4-arm64ec (v17)", offer("10.0-4-arm64ec", 16, installed(valve104)))
        assertEquals("Proton-CachyOS-11.0-20261005-arm64ec (v14)", offer("11.0-20260703-arm64ec", 14, Family.CACHYOS))
        assertNull(offer("11.0-14-arm64ec", 17, installed(ge1114)))
        assertNull(offer("11.0-3-arm64ec", 17, installed(valve113)))
        assertNull(offer("11.0-3-arm64ec", 14, null))
    }
}
