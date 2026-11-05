package com.winlator.star.core.unpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackLayersTest {
    private fun kind(name: String) = RepackLayers.classify(name).kind

    @Test fun builtinMethods() {
        for (m in listOf("lzma", "rep", "tor", "exe", "delta", "grzip", "4x4", "storing")) {
            assertEquals(m, RepackLayers.Kind.BUILTIN, kind(m))
        }
    }

    @Test fun layersBfeCannotDecode() {
        for (m in listOf("srep", "srep64", "lolz_x64", "xtool", "oodle", "ppmd", "lzp", "dict", "mm", "tta",
                "aes-256/ctr", "precomp038", "precomp042", "precomp043")) {
            assertEquals(m, RepackLayers.Kind.MISSING, kind(m))
        }
    }

    @Test fun layersBfeTries() {
        for (m in listOf("precomp", "precomp047", "precomp048", "pcf045", "pzlib", "msc")) {
            assertEquals(m, RepackLayers.Kind.EXTERNAL, kind(m))
        }
    }

    @Test fun analysisCollectsDistinctLayersAcrossBlocks() {
        val info = FreeArcInfo.Info(
            listOf(
                FreeArcInfo.Block("precomp047+rep:64mb+lzma:96mb", 3, 100),
                FreeArcInfo.Block("rep:64mb+lzma:96mb", 2, 50),
            ), 5, 1000,
        )
        val a = RepackLayers.analyze(info)
        assertEquals(listOf("precomp047", "rep", "lzma"), a.layers.map { it.name })
        assertTrue(a.needsExternal)
        assertTrue(a.missing.isEmpty())
        assertFalse(a.builtinOnly)
    }

    @Test fun encryptedHeadersAreMissing() {
        val a = RepackLayers.analyze(FreeArcInfo.Info(emptyList(), 0, 0, headersEncrypted = true))
        assertEquals(1, a.missing.size)
    }

    @Test fun arcIniHasOneShortSectionPerLayer() {
        val ini = RepackLayers.arcIni(listOf("precomp047", "pcf045", "precomp047"), "/data/app/x/lib/arm64/libbfeext.so")
        assertEquals(2, Regex("""\[External compressor:""").findAll(ini).count())
        assertTrue(ini.contains("[External compressor:pcf045]\nheader = 0\n"))
        assertTrue(ini.contains("unpackcmd = /data/app/x/lib/arm64/libbfeext.so {compressor} bfe-packed.tmp bfe-data.tmp"))
        assertFalse("sh would expand \$\$", ini.contains("$$"))
        ini.split("\n\n").forEach { assertTrue(it.length < 512) }
    }

    @Test fun helperMessagesAreExtractedWithoutQuotes() {
        val out = "Unpacking 4 bytes\nBFE-EXT: layer 'srep64' is SREP\nBFE-EXT: layer 'srep64' is SREP\n" +
            "BFE-EXT: layer 'pcf045' needs precomp 0.4.3, which is not included in BFE\nErrorlevel=512\n"
        assertEquals(
            listOf("layer srep64 is SREP", "layer pcf045 needs precomp 0.4.3, which is not included in BFE"),
            RepackLayers.helperMessages(out),
        )
    }

    @Test fun summaryNamesEveryLayer() {
        val a = RepackLayers.analyze(FreeArcInfo.Info(listOf(FreeArcInfo.Block("precomp047+srep+lzma:96mb", 1, 1)), 1, 1))
        val s = RepackLayers.summary(a)
        assertTrue(s, s.startsWith("precomp047=tries, srep=can't (SREP"))
        assertTrue(s, s.endsWith("lzma=built-in"))
    }
}
