package com.winlator.star.store

import com.winlator.star.store.SteamLaunchEntries.Entry
import `in`.dragonbra.javasteam.types.KeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SteamLaunchEntriesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val publicBranch: () -> String? = { null }

    // Counter-Strike (app 10): Windows hl.exe with "-game cstrike", plus a Linux entry.
    private val cs16 = listOf(
        Entry(0, "hl.exe", "-game cstrike", "default", "windows"),
        Entry(1, "hl.sh", "-game cstrike", "default", "linux"),
    )

    @Test
    fun cs16GetsItsGameArgument() {
        val entries = SteamLaunchEntries.usable(cs16, publicBranch)
        assertEquals(1, entries.size)
        assertEquals("-game cstrike", SteamLaunchEntries.argumentsFor(entries, "hl.exe", ""))
    }

    @Test
    fun exeMatchIgnoresSlashesAndCase() {
        val entries = listOf(Entry(0, ".\\Bin\\Win64\\Game.EXE", "-config"))
        assertEquals("-config", SteamLaunchEntries.argumentsFor(entries, "bin/win64/game.exe", ""))
    }

    @Test
    fun otherExeGetsNothing() {
        assertEquals("", SteamLaunchEntries.argumentsFor(SteamLaunchEntries.usable(cs16, publicBranch), "hlds.exe", "-windowed"))
    }

    @Test
    fun userArgsThatCarryThemAddNothing() {
        val entries = SteamLaunchEntries.usable(cs16, publicBranch)
        assertEquals("", SteamLaunchEntries.argumentsFor(entries, "hl.exe", " -game  cstrike -console"))
        assertEquals("-game cstrike", SteamLaunchEntries.argumentsFor(entries, "hl.exe", "-console"))
        assertEquals("-game cstrike", SteamLaunchEntries.argumentsFor(entries, "hl.exe", "-game cstrike2"))
    }

    @Test
    fun linuxOnlyAndEmptyExeEntriesAreDropped() {
        val list = listOf(
            Entry(0, "game.sh", "", "default", "linux"),
            Entry(1, "", "-x", "default", "windows"),
            Entry(2, "game.exe", "-y", "default", ""),
            Entry(3, "game.exe", "-z", "default", "macos,windows"),
        )
        assertEquals(listOf(2, 3), SteamLaunchEntries.usable(list, publicBranch).map { it.index })
    }

    @Test
    fun defaultTypeWinsOverOptionsForTheSameExe() {
        val list = listOf(
            Entry(0, "game.exe", "-vulkan", "option1"),
            Entry(1, "game.exe", "-dx11", "default"),
        )
        assertEquals("-dx11", SteamLaunchEntries.argumentsFor(list, "game.exe", ""))
        assertEquals("-vulkan", SteamLaunchEntries.argumentsFor(listOf(list[0]), "game.exe", ""))
    }

    @Test
    fun noBetaKeyListComesBackWithoutAskingTheBranch() {
        var asked = false
        val out = SteamLaunchEntries.forBranch(cs16) { asked = true; "beta" }
        assertSame(cs16, out)
        assertFalse(asked)
    }

    // Hogwarts Legacy shape (GameNative #2427): test-branch entries listed before the public one.
    private val hogwarts = listOf(
        Entry(0, "Phoenix/Binaries/Win64/HogwartsLegacy.exe", betaKey = "development"),
        Entry(9, "HogwartsLegacy.exe", "-release", betaKey = "release"),
        Entry(13, "HogwartsLegacy.exe", "-SaveToUserDir -UserDir=\"Hogwarts Legacy\""),
    )

    @Test
    fun publicBranchKeepsOnlyUntaggedEntries() {
        val out = SteamLaunchEntries.forBranch(hogwarts, publicBranch)
        assertEquals(listOf(13), out.map { it.index })
        assertEquals("-SaveToUserDir -UserDir=\"Hogwarts Legacy\"", SteamLaunchEntries.argumentsFor(out, "HogwartsLegacy.exe", ""))
    }

    @Test
    fun installedBetaBranchGetsItsEntryFirst() {
        val out = SteamLaunchEntries.forBranch(hogwarts) { "release" }
        assertEquals(listOf(9, 13), out.map { it.index })
        assertEquals("-release", SteamLaunchEntries.argumentsFor(out, "HogwartsLegacy.exe", ""))
    }

    @Test
    fun commaSeparatedBetaKey() {
        val list = listOf(Entry(0, "a.exe", betaKey = "beta, staging"), Entry(1, "b.exe", betaKey = "other"))
        assertEquals(listOf(0), SteamLaunchEntries.forBranch(list) { "STAGING" }.map { it.index })
    }

    @Test
    fun filterThatEmptiesTheListReturnsItUnchanged() {
        val list = listOf(Entry(0, "a.exe", betaKey = "beta"))
        assertSame(list, SteamLaunchEntries.forBranch(list, publicBranch))
    }

    @Test
    fun controlCharactersAreStripped() {
        val list = listOf(Entry(0, "a.exe", "-x\n-y\u0000"))
        assertEquals("-x-y", SteamLaunchEntries.argumentsFor(list, "a.exe", ""))
    }

    @Test
    fun entryExesMoveToTheFront() {
        val root = tmp.newFolder("game")
        val a = File(root, "launcher.exe")
        val b = File(root, "bin/game.exe")
        val c = File(root, "tools/crash.exe")
        val ranked = SteamLaunchEntries.preferEntryExes(
            listOf(Entry(0, "bin\\game.exe")), { SteamLaunchEntries.relativePath(root, it) }, listOf(a, b, c),
        )
        assertEquals(listOf(b, a, c), ranked)
    }

    @Test
    fun noMatchKeepsTheOrder() {
        val root = tmp.newFolder("game2")
        val list = listOf(File(root, "a.exe"), File(root, "b.exe"))
        assertSame(list, SteamLaunchEntries.preferEntryExes(listOf(Entry(0, "c.exe")), { SteamLaunchEntries.relativePath(root, it) }, list))
    }

    @Test
    fun relativePathOnlyInsideTheInstallDir() {
        val root = tmp.newFolder("Half-Life")
        assertEquals("cstrike/x.exe", SteamLaunchEntries.relativePath(root, File(root, "cstrike/x.exe")))
        assertNull(SteamLaunchEntries.relativePath(root, File(root.parentFile, "Half-Life 2/hl2.exe")))
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val text = SteamLaunchEntries.encode(hogwarts, 1234L)
        val (t, list) = SteamLaunchEntries.decode(text)!!
        assertEquals(1234L, t)
        assertEquals(hogwarts, list)
        assertNull(SteamLaunchEntries.decode("not json"))
    }

    private fun kv(name: String, value: String? = null, vararg children: KeyValue): KeyValue {
        val n = KeyValue(name, value)
        if (children.isNotEmpty()) n.children = children.toMutableList()
        return n
    }

    @Test
    fun readsTheAppInfoLaunchBlock() {
        val root = kv("appinfo", null,
            kv("common", null, kv("name", "Counter-Strike")),
            kv("config", null,
                kv("launch", null,
                    kv("0", null, kv("executable", "hl.exe"), kv("arguments", "-game cstrike"), kv("type", "default"),
                        kv("config", null, kv("oslist", "windows"))),
                    kv("1", null, kv("executable", "hl.sh"), kv("arguments", "-game cstrike"),
                        kv("config", null, kv("oslist", "linux"), kv("betakey", "beta"))),
                ),
            ),
        )
        val list = SteamLaunchEntries.fromAppInfo(root)!!
        assertEquals(listOf(Entry(0, "hl.exe", "-game cstrike", "default", "windows"), Entry(1, "hl.sh", "-game cstrike", "", "linux", "beta")), list)
        assertNull(SteamLaunchEntries.fromAppInfo(kv("appinfo", null, kv("common", null, kv("name", "x")))))
        assertTrue(SteamLaunchEntries.usable(list, publicBranch).size == 1)
    }
}
