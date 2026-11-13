package com.winlator.star.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Presets are saved by name, with the old positions kept readable. These pin down that Charcoal is the default for anyone who
 * never picked one, and that every choice saved before Charcoal existed (a plain preset, and Custom with its base) still
 * resolves to the same preset.
 */
class ThemePresetTest {

    // The positions `preset_index` used before names were saved, written out again here on purpose: if the frozen list in the
    // app is ever edited, this one doesn't change with it.
    private val oldOrder = listOf(
        "Classic Dark", "AMOLED", "Ocean", "Forest", "Sunset", "Rose", "Steel",
        "Midnight Cobalt", "Phosphor", "Carbon & Ember", "Amethyst", "Crimson", "Synthwave", "Royal Gold", "Frost", "Monochrome",
        "Custom",
    )

    private val names = themePresets.map { it.name }

    @Test
    fun charcoalExistsAndIsTheDefault() {
        val charcoal = themePresets.first { it.name == "Charcoal" }
        assertEquals("Charcoal", DEFAULT_PRESET_NAME)
        assertEquals(charcoal, themePresets[DEFAULT_PRESET_INDEX])
        // Neutral grays: no colour in the background or the surfaces.
        for (c in listOf(charcoal.background, charcoal.surface, charcoal.surfaceVariant)) {
            assertEquals(c.red, c.green, 0f)
            assertEquals(c.green, c.blue, 0f)
        }
    }

    @Test
    fun nothingSavedStartsOnCharcoal() {
        assertEquals(DEFAULT_PRESET_INDEX, resolveSavedPresetIndex(names, null, null))
        assertEquals("Charcoal", themePresets[resolveSavedPresetIndex(names, null, null)].name)
    }

    @Test
    fun customStaysTheLastPreset() {
        assertEquals("Custom", themePresets.last().name)
        assertEquals(themePresets.size - 1, CUSTOM_PRESET_INDEX)
        assertEquals(1, names.count { it == "Custom" })
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun everyOldSavedPositionStillMeansTheSamePreset() {
        assertEquals(oldOrder, LEGACY_PRESET_NAMES)
        assertEquals(16, LEGACY_CUSTOM_INDEX)
        for ((position, name) in oldOrder.withIndex()) {
            val resolved = resolveSavedPresetIndex(names, null, position)
            assertEquals("old position $position", name, themePresets[resolved].name)
        }
        // Custom, saved as the last old position, is still Custom, which is the last preset today.
        assertEquals(CUSTOM_PRESET_INDEX, resolveSavedPresetIndex(names, null, LEGACY_CUSTOM_INDEX))
        // The default base of a Custom accent that never saved one was AMOLED (old position 1) and still is.
        assertEquals("AMOLED", themePresets[resolveSavedPresetIndex(names, null, 1)].name)
        // Every old name is still a preset, so nothing a user saved can have gone missing.
        assertTrue(names.containsAll(oldOrder))
    }

    @Test
    fun aSavedNameWinsOverAnOldPosition() {
        assertEquals("Ocean", themePresets[resolveSavedPresetIndex(names, "Ocean", 1)].name)
        assertEquals("Charcoal", themePresets[resolveSavedPresetIndex(names, "Charcoal", 7)].name)
        assertEquals(CUSTOM_PRESET_INDEX, resolveSavedPresetIndex(names, "Custom", 0))
    }

    @Test
    fun unknownChoicesFallBackToCharcoalInsteadOfAnotherPreset() {
        assertEquals(DEFAULT_PRESET_INDEX, resolveSavedPresetIndex(names, "No Such Preset", null))
        assertEquals(DEFAULT_PRESET_INDEX, resolveSavedPresetIndex(names, null, 99))
        assertEquals(DEFAULT_PRESET_INDEX, resolveSavedPresetIndex(names, null, -1))
        // A list without Charcoal at all falls back to its first entry.
        assertEquals(0, resolveSavedPresetIndex(listOf("A", "B"), null, null))
    }

    @Test
    fun existingPresetsKeepTheirColours() {
        // The default look of the pre-Charcoal presets is untouched: spot-check the ones with exact legacy values.
        val amoled = themePresets.first { it.name == "AMOLED" }
        assertEquals(Color(0xFF000000), amoled.background)
        assertEquals(Color(0xFF0055FF), amoled.primary)
        assertEquals(Color(0xFF002277), amoled.accentDim)
        val classic = themePresets.first { it.name == "Classic Dark" }
        assertEquals(Color(0xFF1A1A1A), classic.background)
        assertEquals(Color(0xFF2A2A2A), classic.surface)
        val ocean = themePresets.first { it.name == "Ocean" }
        assertEquals(Color(0xFF0D1B2A), ocean.background)
        assertEquals(Color(0xFF0EA5E9), ocean.primary)
    }
}
