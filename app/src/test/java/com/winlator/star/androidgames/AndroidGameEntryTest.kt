package com.winlator.star.androidgames

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Android-game `.desktop` shape: what is written, and what counts as one when read back. */
class AndroidGameEntryTest {

    @Test fun isAndroid_needsTagAndPackage() {
        assertTrue(AndroidGameEntry.isAndroid("android", "com.mojang.minecraftpe"))
        assertFalse(AndroidGameEntry.isAndroid("android", ""))
        assertFalse(AndroidGameEntry.isAndroid("android", null))
        assertFalse(AndroidGameEntry.isAndroid("custom", "com.mojang.minecraftpe"))
        assertFalse(AndroidGameEntry.isAndroid(null, "com.mojang.minecraftpe"))
    }

    @Test fun desktopEntry_roundTripsPackage() {
        val text = AndroidGameEntry.desktopEntry("Minecraft", "com.mojang.minecraftpe", "com.mojang.minecraftpe.MainActivity", "Minecraft")
        assertEquals("com.mojang.minecraftpe", AndroidGameEntry.packageOf(text))
        assertTrue(text.contains("\nIcon=Minecraft\n"))
        assertTrue(text.contains("\nstoreSource=android\n"))
        assertTrue(text.contains("\nandroidActivity=com.mojang.minecraftpe.MainActivity\n"))
        // Shortcut derives its path from the text after "wine "; keep that prefix.
        assertTrue(text.contains("\nExec=wine android:com.mojang.minecraftpe\n"))
    }

    @Test fun desktopEntry_withoutIconOrActivity_omitsThoseLines() {
        val text = AndroidGameEntry.desktopEntry("Dead Cells", "com.playdigious.deadcells.mobile", null, null)
        assertFalse(text.contains("Icon="))
        assertFalse(text.contains("androidActivity="))
        assertEquals("com.playdigious.deadcells.mobile", AndroidGameEntry.packageOf(text))
    }

    @Test fun packageOf_ignoresOtherShortcuts() {
        val wineGame = """
            [Desktop Entry]
            Name=FlatOut
            Exec=wine F:\\\\Games\\\\FlatOut\\\\flatout.exe
            Icon=FlatOut

            [Extra Data]
            storeSource=steam
            androidPackage=com.example.notreally
        """.trimIndent()
        assertNull(AndroidGameEntry.packageOf(wineGame))
        // The package key only counts in [Extra Data].
        assertNull(AndroidGameEntry.packageOf("[Desktop Entry]\nstoreSource=android\nandroidPackage=x.y\n"))
    }

    @Test fun retarget_repointsIconAndCover_keepsTheRest() {
        val text = AndroidGameEntry.desktopEntry("Minecraft", "com.mojang.minecraftpe", null, "Minecraft") +
            "customCoverArtPath=/data/c1/app_data/cover_arts/Minecraft.png\nuuid=abc\n"
        assertEquals("Minecraft", AndroidGameEntry.iconOf(text))
        assertEquals("/data/c1/app_data/cover_arts/Minecraft.png", AndroidGameEntry.extraOf(text, "customCoverArtPath"))

        val moved = AndroidGameEntry.retarget(text, "Minecraft (2)", "/data/home/app_data/cover_arts/Minecraft (2).png")
        assertEquals("Minecraft (2)", AndroidGameEntry.iconOf(moved))
        assertEquals("/data/home/app_data/cover_arts/Minecraft (2).png", AndroidGameEntry.extraOf(moved, "customCoverArtPath"))
        assertEquals("com.mojang.minecraftpe", AndroidGameEntry.packageOf(moved))
        assertEquals("abc", AndroidGameEntry.extraOf(moved, "uuid"))

        // Nulls leave the lines alone.
        assertEquals(text, AndroidGameEntry.retarget(text, null, null))
    }

    @Test fun safeName_stripsPathCharacters() {
        assertEquals("Asphalt_ Legends", AndroidGameEntry.safeName("Asphalt: Legends", "com.gameloft.a9"))
        assertEquals("com.gameloft.a9", AndroidGameEntry.safeName("   ", "com.gameloft.a9"))
    }
}
