package com.winlator.star.container

import com.winlator.star.container.LayerList.CatalogRow
import com.winlator.star.container.LayerList.Group
import com.winlator.star.container.LayerList.Installed
import com.winlator.star.container.LayerList.Status
import com.winlator.star.container.LayerList.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Wine / Proton picker lists. Catalog rows = the live contents.json Wine/Proton rows (winlator-contents 5a77857d). */
class LayerListTest {

    private fun row(wine: Boolean, label: String, versionName: String?, code: Int, rec: Boolean = false) =
        CatalogRow(label, wine, label, versionName, code, rec)

    private val catalog = listOf(
        row(false, "proton-9.0-arm64ec", null, 0),
        row(false, "proton-9.0-x86_64", null, 0),
        row(true, "wine-10-x86_64-ref", null, 0),
        row(true, "wine-8.20-x86_64-ref", null, 0),
        row(true, "wine-9.0.1-x86_64-ref", null, 0),
        row(true, "wine-9.0_ref", null, 0),
        row(true, "wine-9.10-x86_64-ref", null, 0),
        row(true, "wine-9.13-x86_64-ref", null, 0),
        row(true, "wine-9.16-x86_64-ref", null, 0),
        row(true, "wine-9.17-x86_64-ref", null, 0),
        row(true, "wine-9.18-x86_64-ref", null, 0),
        row(true, "wine-9.19-x86_64-ref", null, 0),
        row(true, "wine-9.20-x86_64-ref", null, 0),
        row(true, "wine-9.21-x86_64-ref", null, 0),
        row(true, "wine-9.22_ref", null, 0),
        row(true, "wine-9.3-x86_64-ref", null, 0),
        row(true, "wine-9.4-x86_64-ref", null, 0),
        row(true, "wine-9.5_ref", null, 0),
        row(true, "wine-9.7-x86_64-ref", null, 0),
        row(false, "proton-10.0-arm64ec", null, 0),
        row(false, "Proton-11.0-1-arm64ec (v15)", "11.0-1-arm64ec", 15),
        row(false, "Proton-11.0-2-arm64ec (v17)", "11.0-2-arm64ec", 17),
        row(false, "Proton-11.0-2-x86_64 (v17)", "11.0-2-x86_64", 17),
        row(false, "Proton-11.0-3-arm64ec (v17)", null, 17),
        row(false, "Proton-11.0-3-x86_64 (v17)", null, 17),
        row(false, "Proton-10.0-4-arm64ec (v17)", "10.0-4-arm64ec", 17),
        row(false, "GE-Proton-11.0-3-arm64ec (v14)", "11.0-3-arm64ec", 14),
        row(false, "GE-Proton-11.0-5-arm64ec (v14)", "11.0-5-arm64ec", 14),
        row(false, "GE-Proton-11.0-6-arm64ec (v14)", "11.0-6-arm64ec", 14),
        row(false, "GE-Proton-11.0-7-arm64ec (v14)", "11.0-7-arm64ec", 14),
        row(false, "GE-Proton-11.0-7-x86_64 (v14)", "11.0-7-x86_64", 14),
        row(false, "GE-Proton-11.0-7.1-arm64ec (v14)", "11.0-7.1-arm64ec", 14),
        row(false, "GE-Proton-11.0-7.1-x86_64 (v14)", "11.0-7.1-x86_64", 14),
        row(false, "GE-Proton-11.0-14-arm64ec (v17)", "11.0-14-arm64ec", 17),
        row(false, "GE-Proton-11.0-14-x86_64 (v17)", "11.0-14-x86_64", 17),
        row(false, "DroidDeck-Proton-11.0-12.8-arm64ec (v17)", "11.0-12.8-arm64ec", 17),
        row(false, "DroidDeck-Proton-11.0-12.8-x86_64 (v17)", "11.0-12.8-x86_64", 17),
        row(false, "GE-Proton-10.0-34-arm64ec (v17)", "10.0-34-arm64ec", 17),
        row(false, "Proton-CachyOS-11.0-20260703-arm64ec (v14)", "11.0-20260703-arm64ec", 14),
        row(false, "Proton-CachyOS-11.0-20260703-x86_64 (v14)", "11.0-20260703-x86_64", 14),
        row(false, "Proton-CachyOS-11.0-20261005-arm64ec (v14)", "11.0-20261005-arm64ec", 14),
        row(false, "Proton-CachyOS-11.0-20261005-x86_64 (v14)", "11.0-20261005-x86_64", 14),
    )

    // Installed profile.json descriptions (shortened) as on the Pocket FIT.
    private val geOld113 = "Proton 11.0-3 arm64ec (bionic) — Valve + fast-yield gate + FEX-unixlib loader + GE-Proton11-3 game-fixes (battlenet, eac)"
    private val ge119 = "GE-Proton 11.0-9 arm64ec (bionic) — GE-Proton11-9's own Valve Wine 981e2f0b9007 base"
    private val ge1114 = "GE-Proton 11.0-14 arm64ec (bionic) — GE-Proton11-14's own Valve Wine a47c73f52fd7 base"
    private val valve112 = "Proton 11.0-2 arm64ec (bionic) — stock Valve + fast-yield gate + FEX-unixlib loader"
    private val valve113 = "Proton 11.0-3 arm64ec (bionic) — Valve Proton 11.0-3 base (wine 957ac7cfa4f5) + fast-yield gate"
    private val valve104 = "Proton 10.0-4 arm64ec - Windows compatibility layer with improved gaming support"
    private val ge1034 = "GE-Proton 10.0-34 arm64ec (bionic, Wine 10) - Valve Proton 10.0 + fast-yield gate"
    private val dd128 = "DroidDeck-Proton 11.0-12.8 arm64ec (bionic) — Valve Wine fda79a585939 base"
    private val dd123 = "DroidDeck-Proton 11.0-12.3 arm64ec (bionic) — Valve Wine fda79a585939 base"

    private fun inst(versionName: String, code: Int, desc: String?, wine: Boolean = false) =
        Installed("${if (wine) "Wine" else "Proton"}-$versionName-$code", wine, versionName, code, desc)

    /** The FIT's 12 installed Proton layers (11-24). */
    private val fit = listOf(
        inst("10.0-34-arm64ec", 16, ge1034), inst("10.0-34-arm64ec", 17, ge1034),
        inst("10.0-4-arm64ec", 16, valve104), inst("10.0-4-arm64ec", 17, valve104),
        inst("11.0-12.3-arm64ec", 16, dd123), inst("11.0-12.8-arm64ec", 16, dd128), inst("11.0-12.8-arm64ec", 17, dd128),
        inst("11.0-14-arm64ec", 17, ge1114), inst("11.0-2-arm64ec", 16, valve112), inst("11.0-2-arm64ec", 17, valve112),
        inst("11.0-3-arm64ec", 17, valve113), inst("11.0-9-arm64ec", 16, ge119),
    )

    private fun names(p: LayerList.Page, group: Group, older: Boolean = false) =
        (if (older) p.older else p.sections).firstOrNull { it.group == group }?.rows?.map { it.name } ?: emptyList()

    private fun rowNamed(p: LayerList.Page, name: String) =
        (p.sections + p.older).flatMap { it.rows }.single { it.name == name }

    @Test fun protonTab_groupsByFamily_newestFirst() {
        val p = LayerList.downloadPage(Tab.PROTON, false, catalog, emptyList())
        assertEquals(listOf(Group.VALVE, Group.GE, Group.DROIDDECK), p.sections.map { it.group })
        assertEquals(listOf("Proton 11.0-3", "Proton 11.0-2", "Proton 10.0-4"), names(p, Group.VALVE))
        assertEquals(listOf("GE-Proton 11.0-14", "GE-Proton 10.0-34"), names(p, Group.GE))
        assertEquals(listOf("DroidDeck-Proton 12.8"), names(p, Group.DROIDDECK))
        assertEquals("Proton (Valve)", Group.VALVE.title)
    }

    @Test fun olderBuilds_areTheRowsBelowTheTopVerCode() {
        val p = LayerList.downloadPage(Tab.PROTON, false, catalog, emptyList())
        assertEquals(10, p.olderCount)
        assertEquals(listOf("Proton 11.0-1"), names(p, Group.VALVE, older = true))
        assertEquals(listOf("GE-Proton 11.0-7.1", "GE-Proton 11.0-7", "GE-Proton 11.0-6", "GE-Proton 11.0-5", "GE-Proton 11.0-3"), names(p, Group.GE, older = true))
        assertEquals(listOf("CachyOS 11.0 (2026-10-05)", "CachyOS 11.0 (2026-07-03)"), names(p, Group.CACHYOS, older = true))
        assertEquals(listOf("Proton 10.0", "Proton 9.0"), names(p, Group.OTHER, older = true))
        assertTrue(p.sections.none { s -> s.rows.any { it.verCode < 17 } })
    }

    @Test fun oneRowPerLayer_x86SwitchListsTheX86Builds() {
        val off = LayerList.downloadPage(Tab.PROTON, false, catalog, emptyList())
        val dd = rowNamed(off, "DroidDeck-Proton 12.8")
        assertEquals("DroidDeck-Proton-11.0-12.8-arm64ec (v17)", dd.id)
        assertEquals(LayerList.ARM64EC, dd.arch)
        assertEquals(1, (off.sections + off.older).flatMap { it.rows }.count { it.name == "DroidDeck-Proton 12.8" })

        val on = LayerList.downloadPage(Tab.PROTON, true, catalog, emptyList())
        assertEquals("DroidDeck-Proton-11.0-12.8-x86_64 (v17)", rowNamed(on, "DroidDeck-Proton 12.8").id)
        // No x86_64 build: gone while the switch is on.
        assertEquals(listOf("Proton 11.0-3", "Proton 11.0-2"), names(on, Group.VALVE))
        assertEquals(listOf("GE-Proton 11.0-14"), names(on, Group.GE))
        assertEquals(listOf("GE-Proton 11.0-7.1", "GE-Proton 11.0-7"), names(on, Group.GE, older = true))
        assertEquals(5, on.olderCount)
        assertTrue((on.sections + on.older).flatMap { it.rows }.all { it.arch == LayerList.X86_64 })
    }

    @Test fun wineTab_newestFourThenOlder_shownWithTheSwitchOff() {
        for (x86 in listOf(false, true)) {
            val p = LayerList.downloadPage(Tab.WINE, x86, catalog, emptyList())
            assertEquals(listOf("Wine 10", "Wine 9.22", "Wine 9.21", "Wine 9.20"), names(p, Group.WINE))
            assertEquals(13, p.olderCount)
            assertEquals("Wine 8.20", names(p, Group.WINE, older = true).last())
            assertTrue((p.sections + p.older).flatMap { it.rows }.all { it.arch == LayerList.X86_64 && it.archTag == !x86 })
        }
        assertTrue(LayerList.downloadPage(Tab.PROTON, false, catalog, emptyList()).let { p -> (p.sections + p.older).none { it.group == Group.WINE } })
    }

    @Test fun shortNames() {
        assertEquals("GE-Proton 11.0-14", LayerList.shortName(Group.GE, "11.0-14"))
        assertEquals("Proton 11.0-2", LayerList.shortName(Group.VALVE, "11.0-2"))
        assertEquals("DroidDeck-Proton 12.8", LayerList.shortName(Group.DROIDDECK, "11.0-12.8"))
        assertEquals("CachyOS 11.0 (2026-10-05)", LayerList.shortName(Group.CACHYOS, "11.0-20261005"))
        assertEquals("Wine 9.0.1", LayerList.shortName(Group.WINE, LayerList.baseOf("wine-9.0.1-x86_64-ref", true)))
        assertEquals("9.0", LayerList.baseOf("wine-9.0_ref", true))
        assertEquals("11.0-3-arm64ec", LayerList.versionNameOf(row(false, "Proton-11.0-3-arm64ec (v17)", null, 17)))
        assertEquals("9.0-arm64ec", LayerList.versionNameOf(row(false, "proton-9.0-arm64ec", null, 0)))
        assertEquals("GE-Proton 11.0-14 · v17", LayerList.labelOf(LayerList.Row("x", Group.GE, "GE-Proton 11.0-14", 17, LayerList.ARM64EC)))
        assertEquals("Proton 11.0-2 · v17 · x86_64", LayerList.labelOf(LayerList.Row("x", Group.VALVE, "Proton 11.0-2", 17, LayerList.X86_64, archTag = true)))
        assertEquals("Wine 9.22", LayerList.labelOf(LayerList.Row("x", Group.WINE, "Wine 9.22", 0, "")))
    }

    @Test fun ge113AndValve113_shareAVersionName_butNotARow() {
        val p = LayerList.downloadPage(Tab.PROTON, false, catalog, listOf(inst("11.0-3-arm64ec", 14, geOld113)))
        val valve = rowNamed(p, "Proton 11.0-3")
        val ge = rowNamed(p, "GE-Proton 11.0-3")
        assertEquals(Group.VALVE, valve.group)
        assertEquals(Group.GE, ge.group)
        assertEquals(Status.INSTALLED, ge.status)
        assertEquals("Proton-11.0-3-arm64ec-14", ge.installedEntry)
        // The old GE layer neither installs nor updates to Valve's 11.0-3.
        assertEquals(Status.NEW, valve.status)
        assertNull(valve.installedEntry)
        assertEquals(Status.UPDATE, rowNamed(p, "GE-Proton 11.0-14").status)

        val v = LayerList.downloadPage(Tab.PROTON, false, catalog, listOf(inst("11.0-3-arm64ec", 17, valve113)))
        assertEquals(Status.INSTALLED, rowNamed(v, "Proton 11.0-3").status)
        assertEquals(Status.NEW, rowNamed(v, "GE-Proton 11.0-3").status)
        assertEquals(Status.NEW, rowNamed(v, "GE-Proton 11.0-14").status)

        // Same name AND same verCode in both families (not in today's catalog): still two rows, matched by family.
        val both = listOf(row(false, "GE-Proton-11.0-3-arm64ec (v17)", "11.0-3-arm64ec", 17), row(false, "Proton-11.0-3-arm64ec (v17)", null, 17))
        val w = LayerList.downloadPage(Tab.PROTON, false, both, listOf(inst("11.0-3-arm64ec", 17, valve113)))
        assertEquals(Status.INSTALLED, rowNamed(w, "Proton 11.0-3").status)
        assertEquals(Status.NEW, rowNamed(w, "GE-Proton 11.0-3").status)
        assertNull(rowNamed(w, "GE-Proton 11.0-3").installedEntry)
    }

    @Test fun tags_onTheFitsInstalledLayers() {
        val p = LayerList.downloadPage(Tab.PROTON, false, catalog, fit)
        assertEquals(Status.INSTALLED, rowNamed(p, "DroidDeck-Proton 12.8").status)
        assertEquals("Proton-11.0-12.8-arm64ec-17", rowNamed(p, "DroidDeck-Proton 12.8").installedEntry)
        assertEquals(Status.INSTALLED, rowNamed(p, "Proton 11.0-2").status)
        assertEquals(Status.INSTALLED, rowNamed(p, "GE-Proton 11.0-14").status)
        // An older Valve 11.x is installed (11.0-2 v16/v17): 11.0-1 v15 is not newer, so it is new.
        assertEquals(Status.NEW, rowNamed(p, "Proton 11.0-1").status)
        assertEquals(Status.NEW, rowNamed(p, "CachyOS 11.0 (2026-10-05)").status)

        // Only v16 builds: the v17 rows are updates; arm64ec installs never make an x86_64 row an update.
        val v16 = fit.filter { it.verCode == 16 }
        val q = LayerList.downloadPage(Tab.PROTON, false, catalog, v16)
        assertEquals(Status.UPDATE, rowNamed(q, "Proton 11.0-2").status)
        assertEquals(Status.UPDATE, rowNamed(q, "Proton 10.0-4").status)
        assertEquals(Status.UPDATE, rowNamed(q, "GE-Proton 11.0-14").status)
        assertEquals(Status.UPDATE, rowNamed(q, "DroidDeck-Proton 12.8").status)
        assertEquals(Status.UPDATE, rowNamed(q, "Proton 11.0-3").status)
        val x = LayerList.downloadPage(Tab.PROTON, true, catalog, v16)
        assertTrue((x.sections + x.older).flatMap { it.rows }.all { it.status == Status.NEW })
    }

    @Test fun recommended_cardAndStar_fromTheCatalogFlag() {
        assertNull(LayerList.downloadPage(Tab.PROTON, false, catalog, emptyList()).recommended)
        val flagged = catalog.map { if (it.label.startsWith("DroidDeck-Proton-11.0-12.8")) row(false, it.label, it.versionName, it.verCode, rec = true) else it }
        val p = LayerList.downloadPage(Tab.PROTON, false, flagged, emptyList())
        assertNotNull(p.recommended)
        assertEquals("DroidDeck-Proton 12.8", p.recommended!!.name)
        assertTrue(rowNamed(p, "DroidDeck-Proton 12.8").recommended)
        assertEquals(1, (p.sections + p.older).flatMap { it.rows }.count { it.recommended })
        assertEquals("DroidDeck-Proton-11.0-12.8-x86_64 (v17)", LayerList.downloadPage(Tab.PROTON, true, flagged, emptyList()).recommended!!.id)
        assertNull(LayerList.downloadPage(Tab.WINE, false, flagged, emptyList()).recommended)

        // Installed picker: the star on the recommended build only (v17, not v16 or 12.3).
        val i = LayerList.installedPage(Tab.PROTON, fit, flagged, "Proton-11.0-12.8-arm64ec-17")
        val dd = i.sections.single { it.group == Group.DROIDDECK }.rows
        assertEquals(listOf(true, false, false), dd.map { it.recommended })
        assertNull(i.recommended)
    }

    @Test fun installedPicker_groupsMarksCurrent_listsEveryBuild() {
        val p = LayerList.installedPage(Tab.PROTON, fit, catalog, "Proton-11.0-12.8-arm64ec-17")
        assertEquals(listOf(Group.VALVE, Group.GE, Group.DROIDDECK), p.sections.map { it.group })
        assertEquals(listOf("Proton 11.0-3", "Proton 11.0-2", "Proton 11.0-2", "Proton 10.0-4", "Proton 10.0-4"), names(p, Group.VALVE))
        assertEquals(listOf(17, 17, 16, 17, 16), p.sections.single { it.group == Group.VALVE }.rows.map { it.verCode })
        assertEquals(listOf("GE-Proton 11.0-14", "GE-Proton 11.0-9", "GE-Proton 10.0-34", "GE-Proton 10.0-34"), names(p, Group.GE))
        assertEquals(listOf("DroidDeck-Proton 12.8", "DroidDeck-Proton 12.8", "DroidDeck-Proton 12.3"), names(p, Group.DROIDDECK))
        val current = (p.sections).flatMap { it.rows }.filter { it.current }
        assertEquals(listOf("Proton-11.0-12.8-arm64ec-17"), current.map { it.id })
        assertEquals(0, p.olderCount)
        assertTrue(LayerList.installedPage(Tab.WINE, fit, catalog, null).isEmpty)
        assertEquals(Tab.PROTON, LayerList.tabOfEntry("Proton-11.0-12.8-arm64ec-17", fit))
        assertEquals(Tab.WINE, LayerList.tabOfEntry("Wine-wine-9.22-x86_64-0", fit))

        // The old GE 11.0-3 layer (plain "Proton" description) still goes under GE, with or without a catalog.
        val old = listOf(inst("11.0-3-arm64ec", 14, geOld113), inst("11.0-3-arm64ec", 17, valve113))
        for (cat in listOf(catalog, emptyList())) {
            val q = LayerList.installedPage(Tab.PROTON, old, cat, null)
            assertEquals(listOf("Proton-11.0-3-arm64ec-17"), q.sections.single { it.group == Group.VALVE }.rows.map { it.id })
            assertEquals(listOf("Proton-11.0-3-arm64ec-14"), q.sections.single { it.group == Group.GE }.rows.map { it.id })
        }
    }

    @Test fun installedChoices_protonGroupsThenWine_withShortLabels() {
        val layers = listOf(inst("wine-9.22-x86_64-ref", 0, null, wine = true), inst("11.0-2-x86_64", 17, valve112.replace("arm64ec", "x86_64"))) + fit.take(2)
        val c = LayerList.installedChoices(layers, catalog)
        assertEquals(
            listOf(
                "Proton-11.0-2-x86_64-17" to "Proton 11.0-2 · v17 · x86_64",
                "Proton-10.0-34-arm64ec-17" to "GE-Proton 10.0-34 · v17",
                "Proton-10.0-34-arm64ec-16" to "GE-Proton 10.0-34 · v16",
                "Wine-wine-9.22-x86_64-ref-0" to "Wine 9.22",
            ),
            c,
        )
        assertFalse(c.any { it.second.contains("arm64ec") })
    }

    @Test fun installedWine_matchesItsCatalogRow() {
        val p = LayerList.downloadPage(Tab.WINE, false, catalog, listOf(inst("wine-9.22-x86_64-ref", 0, null, wine = true)))
        assertEquals(Status.INSTALLED, rowNamed(p, "Wine 9.22").status)
        assertEquals(Status.NEW, rowNamed(p, "Wine 9.21").status)
    }
}
