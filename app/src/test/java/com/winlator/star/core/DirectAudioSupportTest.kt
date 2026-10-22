package com.winlator.star.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DirectAudioSupportTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun layer(name: String, arm64ec: Boolean = true, pulseImports: String? = null): File {
        val dir = tmp.newFolder(name)
        if (arm64ec) {
            File(dir, "lib/wine/aarch64-unix").mkdirs()
            File(dir, "lib/wine/aarch64-windows").mkdirs()
        }
        pulseImports?.let { File(dir, "lib/wine/aarch64-unix/winepulse.so").writeText("\u007fELF..$it..") }
        return dir
    }

    @Test fun versionsCompareNumerically() {
        assertTrue(DirectAudioSupport.compareVersions("1.4.0", "1.3.2") > 0)
        assertTrue(DirectAudioSupport.compareVersions("1.3.10", "1.3.2") > 0)
        assertEquals(0, DirectAudioSupport.compareVersions("1.4", "1.4.0"))
        assertTrue(DirectAudioSupport.compareVersions("1.2.2", "1.3") < 0)
    }

    @Test fun wine11LayersGetTheClassicOrSystemThreadSet() {
        assertEquals("wine11", DirectAudioSupport.bundledSet(layer("11.0-9.1-arm64ec-14", pulseImports = "NtCreateThread")))
        assertEquals("wine11-systhread", DirectAudioSupport.bundledSet(layer("11.0-20261005-arm64ec-1", pulseImports = "PsCreateSystemThread")))
    }

    @Test fun wine10SetOnlyForTheBuildsItWasMadeFor() {
        assertEquals("wine10", DirectAudioSupport.bundledSet(layer("10.0-4-arm64ec-14")))
        assertEquals("wine10", DirectAudioSupport.bundledSet(layer("GE-Proton10.0-34-arm64ec-1")))
        assertNull(DirectAudioSupport.bundledSet(layer("10.0-arm64ec-0")))
    }

    @Test fun nonArm64ecLayersGetNothing() {
        assertNull(DirectAudioSupport.bundledSet(layer("11.0-5-x86_64-1", arm64ec = false)))
    }
}
