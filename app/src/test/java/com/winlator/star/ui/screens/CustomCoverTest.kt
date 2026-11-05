package com.winlator.star.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of "Add your own cover": the 2:3 crop and the Icon= line rewrite. */
class CustomCoverTest {

    @Test fun cropFor_aWidePictureLosesItsSides() {
        assertEquals(CoverCrop(300, 0, 600, 900), CustomCover.cropFor(1200, 900))
    }

    @Test fun cropFor_aTallPictureLosesTopAndBottom() {
        assertEquals(CoverCrop(0, 150, 600, 900), CustomCover.cropFor(600, 1200))
    }

    @Test fun cropFor_aCoverShapedPictureIsKeptWhole() {
        assertEquals(CoverCrop(0, 0, 600, 900), CustomCover.cropFor(600, 900))
    }

    @Test fun cropFor_aSquareIsCutToTwoThirdsOfItsWidth() {
        assertEquals(CoverCrop(167, 0, 666, 1000), CustomCover.cropFor(1000, 1000))
    }

    @Test fun cropFor_nothingToCutFromAnEmptyImage() {
        assertEquals(CoverCrop(0, 0, 0, 0), CustomCover.cropFor(0, 500))
        assertEquals(CoverCrop(0, 0, 0, 0), CustomCover.cropFor(500, -1))
    }

    @Test fun cropFor_alwaysStaysInsideTheImageAndNearTwoByThree() {
        for (w in listOf(1, 2, 3, 7, 100, 359, 360, 1199, 1200)) {
            for (h in listOf(1, 2, 3, 7, 100, 540, 1199, 1200)) {
                val c = CustomCover.cropFor(w, h)
                assertTrue("$w x $h -> $c", c.width >= 1 && c.height >= 1)
                assertTrue("$w x $h -> $c", c.x >= 0 && c.y >= 0 && c.x + c.width <= w && c.y + c.height <= h)
                // Two by three, give or take the pixel the integer maths rounds away (or a 1 px sliver).
                if (c.width > 3 && c.height > 3) {
                    assertTrue("$w x $h -> $c", kotlin.math.abs(c.width * 3 - c.height * 2) <= 3)
                }
            }
        }
    }

    @Test fun withIconLine_replacesTheLineInPlace() {
        val lines = listOf("[Desktop Entry]", "Name=Game", "Icon=steam-linux", "Exec=wine x.exe", "", "[Extra Data]", "uuid=1")
        assertEquals(
            listOf("[Desktop Entry]", "Name=Game", "Icon=Game", "Exec=wine x.exe", "", "[Extra Data]", "uuid=1"),
            CustomCover.withIconLine(lines, "Game"),
        )
    }

    @Test fun withIconLine_addsTheLineUnderNameWhenThereIsNone() {
        val lines = listOf("[Desktop Entry]", "Name=Game", "Exec=wine x.exe")
        assertEquals(
            listOf("[Desktop Entry]", "Name=Game", "Icon=Game", "Exec=wine x.exe"),
            CustomCover.withIconLine(lines, "Game"),
        )
    }

    @Test fun withIconLine_fallsBackToUnderTheHeaderWithoutAName() {
        assertEquals(
            listOf("[Desktop Entry]", "Icon=Game", "Exec=wine x.exe"),
            CustomCover.withIconLine(listOf("[Desktop Entry]", "Exec=wine x.exe"), "Game"),
        )
    }

    @Test fun withIconLine_nullDropsTheLine() {
        val lines = listOf("[Desktop Entry]", "Name=Game", "Icon=Game", "Exec=wine x.exe")
        assertEquals(listOf("[Desktop Entry]", "Name=Game", "Exec=wine x.exe"), CustomCover.withIconLine(lines, null))
    }

    @Test fun withIconLine_leavesExtraDataAlone() {
        val lines = listOf("[Desktop Entry]", "Name=Game", "[Extra Data]", "Icon=notAnIconLine", "storeSource=custom")
        val out = CustomCover.withIconLine(lines, "Game")
        assertEquals(listOf("[Desktop Entry]", "Name=Game", "Icon=Game", "[Extra Data]", "Icon=notAnIconLine", "storeSource=custom"), out)
        assertEquals(lines, CustomCover.withIconLine(lines, "x").filterNot { it == "Icon=x" })
    }
}
