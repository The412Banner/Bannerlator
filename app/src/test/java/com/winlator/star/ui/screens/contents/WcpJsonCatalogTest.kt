package com.winlator.star.ui.screens.contents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Official catalog (`ContentsManager.REMOTE_PROFILES`) and StevenMXZ share the flat
 * `{type, verName, verCode, remoteUrl}` shape. These lock the parser to a 3-entry sample of that
 * exact shape, including the Box64 / WOWBox64 pair that a substring match used to conflate.
 */
class WcpJsonCatalogTest {

    private val sample = """
        [
          {"type":"Box64","verName":"Box64-0.3.2","verCode":"0","remoteUrl":"https://example.com/Box64/Box64-0.3.2.wcp"},
          {"type":"WOWBox64","verName":"wowbox64-0.3.5","verCode":"0","remoteUrl":"https://example.com/WOWBox64/wowbox64-0.3.5.wcp"},
          {"type":"DXVK","verName":"dxvk-2.4.1","verCode":"7","remoteUrl":"https://example.com/DXVK/dxvk-2.4.1.wcp"}
        ]
    """.trimIndent()

    @Test
    fun parsesOfficialShapeFields() {
        val items = WcpJsonCatalog.parse(sample, "DXVK", "Official")
        assertEquals(1, items.size)
        val it = items[0]
        assertEquals("dxvk-2.4.1", it.versionName)
        assertEquals("DXVK  dxvk-2.4.1", it.displayName)
        assertEquals("https://example.com/DXVK/dxvk-2.4.1.wcp", it.downloadUrl)
        assertEquals("Official", it.sourceName)
    }

    @Test
    fun exactTypeWins_box64DoesNotSwallowWowbox64() {
        val box = WcpJsonCatalog.parse(sample, "Box64", "Official")
        assertEquals(listOf("Box64-0.3.2"), box.map { it.versionName })
        val wow = WcpJsonCatalog.parse(sample, "WOWBox64", "Official")
        assertEquals(listOf("wowbox64-0.3.5"), wow.map { it.versionName })
    }

    @Test
    fun typeMatchIsCaseInsensitive_andUnknownTypeIsEmpty() {
        assertEquals(1, WcpJsonCatalog.parse(sample, "box64", "Official").size)
        assertEquals(1, WcpJsonCatalog.parse(sample, "wowbox64", "Official").size)
        assertTrue(WcpJsonCatalog.parse(sample, "Proton", "Official").isEmpty())
    }

    @Test
    fun aliasFallsBackToSubstring_whenNoExactType() {
        val fex = """[{"type":"FEXCore","verName":"FEXCore-2608","verCode":"1","remoteUrl":"https://example.com/f.wcp"}]"""
        assertEquals(listOf("FEXCore-2608"), WcpJsonCatalog.parse(fex, "fex", "S").map { it.versionName })
        assertEquals(listOf("FEXCore-2608"), WcpJsonCatalog.parse(fex, "FEXCore", "S").map { it.versionName })
    }

    @Test
    fun newestLastInFileComesFirst() {
        val two = """
            [
              {"type":"DXVK","verName":"dxvk-2.3","verCode":"1","remoteUrl":"https://example.com/a.wcp"},
              {"type":"DXVK","verName":"dxvk-2.4","verCode":"2","remoteUrl":"https://example.com/b.wcp"}
            ]
        """.trimIndent()
        assertEquals(listOf("dxvk-2.4", "dxvk-2.3"), WcpJsonCatalog.parse(two, "DXVK", "S").map { it.versionName })
    }
}
