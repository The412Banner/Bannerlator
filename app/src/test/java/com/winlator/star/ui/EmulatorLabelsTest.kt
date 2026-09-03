package com.winlator.star.ui

import com.winlator.star.core.StringUtils
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Emulator relabel is DISPLAY-ONLY. These lock the invariant that matters: whatever the user
 * reads, what round-trips back through StringUtils.parseIdentifier() is still "fexcore"/"box64" —
 * the identifiers Container.emulator, the shortcut "emulator" extra, ConfigExporter and
 * GuestProgramLauncherComponent all key on.
 */
class EmulatorLabelsTest {

    private val entries = listOf(EmulatorLabels.FEXCORE, EmulatorLabels.BOX64)

    @Test
    fun display_relabelsBox64OnArm64ecOnly() {
        assertEquals("WOWBox64", EmulatorLabels.display("Box64", true))
        assertEquals("Box64", EmulatorLabels.display("Box64", false))
        assertEquals("FEXCore", EmulatorLabels.display("FEXCore", true))
        assertEquals("FEXCore", EmulatorLabels.display("FEXCore", false))
    }

    @Test
    fun options_preserveOrder() {
        assertEquals(listOf("FEXCore", "WOWBox64"), EmulatorLabels.options(entries, true))
        assertEquals(listOf("FEXCore", "Box64"), EmulatorLabels.options(entries, false))
    }

    /** Without fromDisplay(), parseIdentifier("WOWBox64") would persist "wowbox64" — a new value. */
    @Test
    fun fromDisplay_roundTripsToCanonicalIdentifiers() {
        for (arm64ec in listOf(true, false)) {
            for (entry in entries) {
                val shown = EmulatorLabels.display(entry, arm64ec)
                val back = EmulatorLabels.fromDisplay(shown, entries, arm64ec)
                assertEquals(entry, back)
            }
        }
        assertEquals("box64", StringUtils.parseIdentifier(
            EmulatorLabels.fromDisplay("WOWBox64", entries, true)))
        assertEquals("fexcore", StringUtils.parseIdentifier(
            EmulatorLabels.fromDisplay("FEXCore", entries, true)))
    }

    /** The x86_64 field is forced to this — the launcher always runs bin/box64 there. */
    @Test
    fun box64EntryOf_findsTheCanonicalEntry() {
        assertEquals("Box64", EmulatorLabels.box64EntryOf(entries))
        assertEquals("Box64", EmulatorLabels.box64EntryOf(emptyList()))
        assertEquals("box64", StringUtils.parseIdentifier(EmulatorLabels.box64EntryOf(entries)))
    }
}
