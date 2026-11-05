package com.winlator.star.core.unpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The `7zz l -slt` parser and tree, fed real output: every fixture under resources/slt was produced by
 * the 7-Zip 24.08 (arm64) bundled in BFE, run on a device against test archives (`<name>.slt` =
 * stdout, `.err` = stderr, `.exit` = exit code).
 */
class ArchiveListingTest {
    private val utc = ZoneOffset.UTC

    private fun res(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("slt/$name")) { "missing fixture $name" }.readText(Charsets.UTF_8)

    private fun listing(name: String, archiveName: String = name) =
        SltParser.parse(res("$name.slt").lineSequence().iterator(), archiveName, utc)

    private fun outcome(name: String): SltOutcome =
        SltOutcome.of(res("$name.exit").trim().toInt(), res("$name.err"), listing(name))

    private fun tree(name: String) = ArchiveTree.build(listing(name).entries)

    private val modified = LocalDateTime.of(2026, 10, 16, 17, 19, 10).toInstant(utc).toEpochMilli()

    @Test fun zipWithoutDirectoryEntriesGetsSynthesisedFolders() {
        val l = listing("nodirs.zip")
        assertEquals("zip", l.type)
        assertEquals("301096", l.archiveProps["Physical Size"])
        assertEquals(6, l.entries.size)
        assertTrue(outcome("nodirs.zip") is SltOutcome.Ok)

        val t = ArchiveTree.build(l.entries)
        assertEquals(listOf("Game"), t.children("").map { it.name })
        val game = t.entry("Game")!!
        assertTrue(game.isDir)
        assertFalse(game.explicit)
        assertEquals(modified, game.modified)   // a synthesised folder shows its newest child's time
        assertEquals(setOf("Data", "Spaced Dir", "émoji", "readme.txt", ".hidden"), t.children("Game").map { it.name }.toSet())
        assertEquals(listOf("Game/Data/Sub/conf.ini"), t.children("Game/Data/Sub").map { it.path })
        assertEquals("файл 日本.txt", t.children("Game/émoji").single().name)

        val big = t.entry("Game/Data/big.bin")!!
        assertEquals(300000L, big.size)
        assertEquals(300050L, big.packedSize)
        assertEquals("Deflate", big.method)
        assertEquals("35FEF7CB", big.crc)
        assertEquals(modified, big.modified)
        assertFalse(big.encrypted)

        assertEquals(6, t.fileCount)
    }

    @Test fun folderCountsAndSizes() {
        val t = tree("nodirs.zip")
        // Game, Game/Data, Game/Data/Sub, Game/Spaced Dir, Game/émoji
        assertEquals(5, t.dirCount)
        assertEquals(300000L + 4 + 2 + 2 + 6 + 2, t.totalSize)
        assertEquals(300004L, t.sizeOf(t.entry("Game/Data")!!))
        assertEquals(setOf("Game/Data/Sub", "Game/Data/Sub/conf.ini", "Game/Data/big.bin"), t.descendants("Game/Data").map { it.path }.toSet())
    }

    @Test fun extractNamesUseFolderPrefixes() {
        val t = tree("nodirs.zip")
        // 7zz matches a folder name as a path prefix even with no entry for the folder (device-verified).
        assertEquals(listOf("Game/Data"), t.extractNames(listOf(t.entry("Game/Data")!!)))
        assertEquals(listOf("Game"), t.extractNames(listOf(t.entry("Game")!!)))
        assertEquals(
            listOf("Game/readme.txt", "Game/Spaced Dir"),
            t.extractNames(listOf(t.entry("Game/readme.txt")!!, t.entry("Game/Spaced Dir")!!)),
        )
        assertFalse(t.hasRenamed)
    }

    @Test fun zipWithDirectoryEntries() {
        val t = tree("withdirs.zip")
        assertEquals(6, t.dirCount)
        val empty = t.entry("Game/EmptyDir")!!
        assertTrue(empty.isDir)
        assertTrue(empty.explicit)
        assertTrue(t.children("Game/EmptyDir").isEmpty())
        assertEquals("D drwxr-xr-x", t.entry("Game")!!.attributes)
    }

    @Test fun sevenZipFoldersComeFromAttributes() {
        val l = listing("plain.7z")
        assertEquals("7z", l.type)
        assertEquals("+", l.archiveProps["Solid"])
        val t = ArchiveTree.build(l.entries)
        assertEquals(12, l.entries.size)
        assertTrue(t.entry("Game/Data/Sub")!!.isDir)
        assertTrue(t.entry("Game/Data/Sub")!!.explicit)
        assertFalse(t.entry("Game/readme.txt")!!.isDir)
        // 7z prints 7 fractional digits on its times.
        assertEquals(modified, t.entry("Game/readme.txt")!!.modified)
        // Files in a solid block print an empty "Packed Size =": unknown, not zero.
        assertEquals(-1L, t.entry("Game/readme.txt")!!.packedSize)
    }

    @Test fun bareFormatParsesTheSameEntries() {
        val full = listing("plain.7z").entries.map { it.path to it.isDir }
        val bare = listing("plain-ba.7z")
        assertNull(bare.type)
        assertEquals(full, bare.entries.map { it.path to it.isDir })
    }

    @Test fun tarUsesFolderAndMode() {
        val l = listing("game.tar")
        assertEquals("tar", l.type)
        val t = ArchiveTree.build(l.entries)
        assertEquals(12, l.entries.size)
        assertTrue(t.entry("Game/EmptyDir")!!.isDir)
        assertEquals("drwxr-xr-x", t.entry("Game")!!.attributes)
    }

    @Test fun compressedTarListsOneInnerTar() {
        val gz = listing("game.tar.gz")
        assertEquals("gzip", gz.type)
        val inner = gz.entries.single()
        assertEquals("game.tar", inner.path)
        assertEquals(317440L, inner.size)
        assertEquals(listOf("game.tar"), ArchiveTree.build(gz.entries).extractNames(gz.entries))
    }

    @Test fun zstdStreamHasNoPathLine() {
        val l = listing("game.wcp")
        assertEquals("zstd", l.type)
        val only = l.entries.single()
        assertEquals("game", only.path)          // 7zz names it after the archive minus ".wcp"
        assertNull(only.rawPath)
        val t = ArchiveTree.build(l.entries)
        assertTrue(t.hasRenamed)
        assertNull(t.extractNames(listOf(only)))  // a nameless stream: extract everything
        assertTrue(l.archiveProps["Method"]!!.contains("content-size-total:317440"))
    }

    @Test fun plainCompressedFile() {
        val e = listing("note.txt.gz").entries.single()
        assertEquals("note.txt", e.path)
        assertEquals(5L, e.size)
    }

    @Test fun windowsBackslashPathsBecomeFolders() {
        val t = tree("backslash.zip")
        assertEquals(setOf("Win", "top.txt"), t.children("").map { it.name }.toSet())
        val f = t.entry("Win/Dir/file1.txt")!!
        assertEquals("Win\\Dir\\file1.txt", f.rawPath)
        assertTrue(f.renamed)
        assertTrue(t.hasRenamed)
        assertFalse(t.entry("Win")!!.explicit)
        // 7zz only knows the backslash names, so a folder expands to its entries' stored names.
        assertEquals(setOf("Win\\Dir\\file1.txt", "Win\\file2.txt"), t.extractNames(listOf(t.entry("Win")!!))!!.toSet())
        assertEquals(listOf("Win\\Dir\\file1.txt"), t.extractNames(listOf(f)))
        assertEquals(listOf("top.txt"), t.extractNames(listOf(t.entry("top.txt")!!)))
    }

    @Test fun nestedArchivesAreJustFiles() {
        val t = tree("nested.zip")
        assertEquals(setOf("inner", "note.txt"), t.children("").map { it.name }.toSet())
        assertEquals(setOf("plain.7z", "game.tar.gz"), t.children("inner").map { it.name }.toSet())
    }

    @Test fun encryptedEntries() {
        for (name in listOf("zipcrypto.zip", "encfiles.7z")) {
            val t = tree(name)
            assertTrue(name, t.anyEncrypted)
            assertTrue(name, t.entry("Game/readme.txt")!!.encrypted)
            assertTrue(name, t.needsPassword(t.entry("Game")!!))
            assertTrue(name, outcome(name) is SltOutcome.Ok)
        }
        assertFalse(tree("plain.7z").needsPassword(tree("plain.7z").entry("Game")!!))
    }

    @Test fun encryptedHeadersNeedAPassword() {
        assertTrue(outcome("enchead.7z") is SltOutcome.NeedsPassword)
        val right = listing("enchead-right.7z")
        assertEquals(12, right.entries.size)
        assertTrue(right.entries.filter { !it.isDir }.all { it.encrypted })
        assertTrue(outcome("enchead-right.7z") is SltOutcome.Ok)
    }

    @Test fun notAnArchive() {
        val o = outcome("notarchive")
        assertTrue(o is SltOutcome.NotArchive)
    }

    @Test fun truncatedArchiveStillListsWithAWarning() {
        val o = outcome("trunc.zip")
        assertTrue(o is SltOutcome.Ok)
        assertTrue((o as SltOutcome.Ok).warning!!.contains("Unexpected end of archive"))
        assertEquals(2, listing("trunc.zip").entries.size)
    }

    @Test fun stderrMessagesLoseThe7zipPrefix() {
        assertEquals(
            "Cannot open encrypted archive. Wrong password?",
            SltOutcome.lastMessage("\nERROR: /sdcard/Download/x.7z : Cannot open encrypted archive. Wrong password?\n\n"),
        )
        assertEquals("Is not archive", SltOutcome.lastMessage("ERROR: /a.gz : /a.gz\nOpen ERROR: Cannot open the file as [tar] archive\n\nERRORS:\nIs not archive\n"))
    }

    @Test fun synthesisDuplicatesAndOddPaths() {
        val text = buildString {
            append("--\nPath = /x.tar\nType = tar\n\n----------\n")
            for ((p, folder) in listOf(
                "./a/b/c.txt" to "-", "a/b/c.txt" to "-", "/abs/file" to "-", "dup" to "-", "dup" to "-",
                "clash" to "-", "clash/inner.txt" to "-", "trail/" to "+", "." to "+",
            )) append("Path = $p\nFolder = $folder\nSize = 1\nModified = 2020-01-02 03:04:05\n\n")
        }
        val l = SltParser.parse(text.lineSequence().iterator(), "x.tar", utc)
        val t = ArchiveTree.build(l.entries)
        assertEquals(setOf("a", "abs", "dup", "clash", "trail"), t.children("").map { it.name }.toSet())
        assertEquals(1, t.children("a/b").size)              // "./a/b/c.txt" and "a/b/c.txt" are one entry
        assertEquals(1, t.children("").count { it.name == "dup" })
        assertTrue(t.entry("clash")!!.isDir)                  // a file and a folder of one name: the folder
        assertEquals(listOf("clash/inner.txt"), t.children("clash").map { it.path })
        assertTrue(t.entry("trail")!!.isDir)
        assertNotNull(t.entry("abs/file"))
        assertEquals("trail/", t.entry("trail")!!.rawPath)
    }

    @Test fun timeParsing() {
        assertEquals(modified, SltParser.parseTime("2026-10-16 17:19:10", utc))
        assertEquals(modified, SltParser.parseTime("2026-10-16 17:19:10.1234567", utc))
        assertEquals(0L, SltParser.parseTime("", utc))
        assertEquals(0L, SltParser.parseTime(null, utc))
        assertEquals(0L, SltParser.parseTime("garbage", utc))
    }

    @Test fun streamNames() {
        assertEquals("game", SltParser.streamName("game.wcp"))
        assertEquals("x.tar", SltParser.streamName("x.tar.zst"))
        assertEquals("blob~", SltParser.streamName("blob"))
    }

    /** 100k entries in 1000 folders, no folder entries — the size the pane must stay responsive at. */
    @Test fun hundredThousandEntries() {
        val n = 100_000
        val lines = sequence {
            yield("--"); yield("Path = /big.zip"); yield("Type = zip"); yield(""); yield("----------")
            for (i in 0 until n) {
                yield("Path = root/d%04d/f%06d.txt".format(i / 100, i))
                yield("Folder = -"); yield("Size = 0"); yield("Packed Size = 0")
                yield("Modified = 2021-01-02 03:04:06"); yield("Attributes =  -rw-r--r--"); yield("Encrypted = -")
                yield("CRC = 00000000"); yield("Method = Store"); yield("Host OS = Unix"); yield("")
            }
        }
        val started = System.nanoTime()
        val l = SltParser.parse(lines.iterator(), "big.zip", utc)
        val t = ArchiveTree.build(l.entries)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(n, t.fileCount)
        assertEquals(1001, t.dirCount)
        assertEquals(1000, t.children("root").size)
        assertEquals(100, t.children("root/d0500").size)
        println("parsed + built $n entries in $ms ms")
        assertTrue("took $ms ms", ms < 20_000)
    }
}
