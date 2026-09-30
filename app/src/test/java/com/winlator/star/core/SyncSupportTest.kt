package com.winlator.star.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** The Sync selector's pure half: legacy-env migration, launch env mapping, layer resolve, scanner. */
class SyncSupportTest {

    private val v9p11 = SyncCaps(esync = true, ntsync = true)   // 11.0-2 v9
    private val esyncOnly = SyncCaps(esync = true, ntsync = false) // 10.0-4 v9, every v8 layer
    private val noEsync = SyncCaps(esync = false, ntsync = false)

    private fun envOf(mode: String): Map<String, String> {
        val env = EnvVars("WINEFSYNC=1 WINEESYNC=1 WINENTSYNC=1 DXVK_HUD=fps")
        SyncSupport.applyToEnv(env, mode)
        return env.toStringArray().associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    @Test fun esync_setsOnlyEsync_andStripsFsync() {
        val e = envOf(SyncMode.ESYNC)
        assertEquals("1", e["WINEESYNC"]); assertNull(e["WINENTSYNC"]); assertNull(e["WINEFSYNC"])
        assertEquals("fps", e["DXVK_HUD"])
    }

    @Test fun ntsync_setsNtsyncWithEsyncFallback() {
        val e = envOf(SyncMode.NTSYNC)
        assertEquals("1", e["WINENTSYNC"]); assertEquals("1", e["WINEESYNC"]); assertNull(e["WINEFSYNC"])
    }

    @Test fun wineserver_turnsEsyncOff() {
        val e = envOf(SyncMode.WINESERVER)
        assertEquals("0", e["WINEESYNC"]); assertNull(e["WINENTSYNC"]); assertNull(e["WINEFSYNC"])
    }

    @Test fun legacyEnv_migration() {
        assertEquals(SyncMode.WINESERVER, SyncSupport.storedMode(null, "WINEESYNC=0 TU_DEBUG=sysmem"))
        assertEquals(SyncMode.NTSYNC, SyncSupport.storedMode("", "WINEESYNC=1 WINENTSYNC=1"))
        assertEquals(SyncMode.ESYNC, SyncSupport.storedMode("", "WINEESYNC=1"))
        assertNull(SyncSupport.storedMode("", "DXVK_HUD= WINEFSYNC=1"))
        // The extra wins over whatever the env string still says.
        assertEquals(SyncMode.ESYNC, SyncSupport.storedMode("esync", "WINEESYNC=0"))
        // fsync is never a storable choice.
        assertNull(SyncSupport.storedMode("fsync", ""))
    }

    @Test fun requested_shortcutOverridesContainer_elseFollows() {
        assertEquals(SyncMode.WINESERVER, SyncSupport.requestedMode("ntsync", "", "wineserver", ""))
        assertEquals(SyncMode.NTSYNC, SyncSupport.requestedMode("ntsync", "", "", "DXVK_HUD=fps"))
        assertEquals(SyncMode.WINESERVER, SyncSupport.requestedMode("", "WINEESYNC=0", null, null))
        assertNull(SyncSupport.requestedMode("", "", "", ""))
    }

    @Test fun resolve_fallsBackToLayerDefault() {
        assertEquals(SyncMode.NTSYNC, v9p11.resolve(SyncMode.NTSYNC))
        assertEquals(SyncMode.ESYNC, esyncOnly.resolve(SyncMode.NTSYNC))
        assertEquals(SyncMode.ESYNC, esyncOnly.resolve(null))
        assertEquals(SyncMode.WINESERVER, noEsync.resolve(SyncMode.ESYNC))
        assertEquals(SyncMode.WINESERVER, noEsync.resolve(null))
        assertEquals(SyncMode.ESYNC, v9p11.resolve(SyncMode.FSYNC))
        assertFalse(v9p11.isAvailable(SyncMode.FSYNC))
    }

    @Test fun stripSyncVars_keepsEveryOtherTokenVerbatim() {
        assertEquals("A=1 DXVK_HUD= B=x=y",
            SyncSupport.stripSyncVars("A=1 WINEESYNC=1 DXVK_HUD= WINENTSYNC=1 B=x=y WINEFSYNC=0"))
        assertEquals("WINEESYNCX=1", SyncSupport.stripSyncVars("WINEESYNCX=1"))
        assertEquals("", SyncSupport.stripSyncVars(""))
    }

    @Test fun profileCapabilities_winWhenPresent() {
        assertEquals(v9p11, SyncSupport.capsFromProfileJson(JSONObject("""{"capabilities":["esync","NTSYNC"]}""")))
        assertEquals(noEsync, SyncSupport.capsFromProfileJson(JSONObject("""{"capabilities":[]}""")))
        assertNull(SyncSupport.capsFromProfileJson(JSONObject("""{"type":"Proton"}""")))
    }

    @Test fun scanner_findsMarkersAcrossChunkBoundaries() {
        val marker = "WINENTSYNC".toByteArray()
        val esync = "esync: up and running".toByteArray()
        // Put the ntsync marker straddling the first chunk boundary (64 KB + longest marker bytes).
        val data = ByteArray(200_000)
        System.arraycopy(marker, 0, data, 64 * 1024 + esync.size - 4, marker.size)
        val found = SyncSupport.scanForMarkers(ByteArrayInputStream(data), listOf(esync, marker))
        assertArrayEquals(booleanArrayOf(false, true), found)
        System.arraycopy(esync, 0, data, data.size - esync.size, esync.size)
        assertTrue(SyncSupport.scanForMarkers(ByteArrayInputStream(data), listOf(esync, marker)).all { it })
    }
}
