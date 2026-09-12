package com.winlator.star.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Coverage for [DependencyDetector] using the real on-disk redist layouts seen on device
 * (DiRT 3 `_CommonRedist/vcredist/2010` + OpenAL; GTA V `D3D12-REDIST` + Rockstar setups).
 * Pure JVM — builds fake game trees and asserts the recommended component names.
 */
class DependencyDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun touch(root: File, rel: String) {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText("x")
    }

    private fun names(dir: File) = DependencyDetector.detect(dir).map { it.componentName }

    @Test
    fun dirt3_commonRedist_recommendsVcredist2010AndOpenAl_notLatest() {
        val g = tmp.newFolder("DiRT 3 Complete Edition")
        touch(g, "_CommonRedist/vcredist/2010/vcredist_x64.exe")
        touch(g, "_CommonRedist/vcredist/2010/vcredist_x86.exe")
        touch(g, "_CommonRedist/OpenAL/2.0.7.0/oalinst.exe")
        touch(g, "_CommonRedist/vcredist_x64.exe") // loose duplicate — must NOT add vcredist2022
        val n = names(g)
        assertTrue(n.contains("vcredist2010"))
        assertTrue(n.contains("oalinst"))
        assertFalse("loose installer must not add a latest VC++ when a specific year exists", n.contains("vcredist2022"))
        // install order: vcredist before oalinst
        assertTrue(n.indexOf("vcredist2010") < n.indexOf("oalinst"))
    }

    @Test
    fun gtaV_skipsDx12AndLauncherSetups() {
        val g = tmp.newFolder("Grand Theft Auto V Enhanced")
        touch(g, "D3D12-REDIST/D3D12Core.dll")
        touch(g, "Redistributables/Rockstar-Games-Launcher.exe")
        touch(g, "Redistributables/Social-Club-Setup.exe")
        assertEquals(emptyList<String>(), names(g))
    }

    @Test
    fun looseVcredist_noYear_resolvesToLatest() {
        val g = tmp.newFolder("SomeGame")
        touch(g, "vcredist_x64.exe")
        assertEquals(listOf("vcredist2022"), names(g))
    }

    @Test
    fun physxAndXact_detected() {
        val g = tmp.newFolder("RacingGame")
        touch(g, "_CommonRedist/PhysX/9.13/PhysX-9.13.0604-SystemSoftware.exe")
        touch(g, "_CommonRedist/xact/xactengine_setup.exe")
        val n = names(g)
        assertTrue(n.contains("physx"))
        assertTrue(n.contains("xact"))
        assertTrue(n.contains("xact_x64"))
    }

    @Test
    fun directXRedist_mapsToD3dx9() {
        val g = tmp.newFolder("OldGame")
        touch(g, "_CommonRedist/DirectX/DXSETUP.exe")
        assertTrue(names(g).contains("d3dx9"))
    }

    @Test
    fun dotNetFxInstaller_mapsToVersion() {
        val g = tmp.newFolder("ManagedGame")
        touch(g, "_CommonRedist/DotNet/dotNetFx45_Full_setup.exe")
        assertTrue(names(g).contains("dotnet45"))
    }

    @Test
    fun combinedRedist_orderedRuntimesFirst() {
        val g = tmp.newFolder("BigGame")
        touch(g, "_CommonRedist/vcredist/2013/vcredist_x64.exe")
        touch(g, "_CommonRedist/PhysX/PhysX_9.exe")
        touch(g, "_CommonRedist/OpenAL/oalinst.exe")
        val n = names(g)
        assertEquals(setOf("vcredist2013", "physx", "oalinst"), n.toSet())
        assertEquals("vcredist2013", n.first()) // runtime first
    }

    @Test
    fun gfwlWrapperDll_recommendsXLiveRedist() {
        val g = tmp.newFolder("DiRT 3 Complete Edition")
        touch(g, "dbxLive32.dll")           // Codemasters GFWL wrapper
        touch(g, "dirt3_game.exe")
        touch(g, "_CommonRedist/vcredist/2010/vcredist_x64.exe")
        touch(g, "_CommonRedist/OpenAL/oalinst.exe")
        val n = names(g)
        assertTrue(n.contains("XLiveRedist"))
        assertTrue(n.contains("vcredist2010"))
        assertTrue(n.contains("oalinst"))
    }

    @Test
    fun shippedRuntimeDlls_detected_versionAware() {
        val g = tmp.newFolder("SomeGame")
        touch(g, "msvcp120.dll")   // VC++ 2013
        touch(g, "d3dx9_43.dll")   // DX9 helper
        touch(g, "OpenAL32.dll")   // OpenAL
        touch(g, "PhysXLoader.dll") // PhysX
        val n = names(g)
        assertTrue(n.contains("vcredist2013"))
        assertTrue(n.contains("d3dx9"))
        assertTrue(n.contains("oalinst"))
        assertTrue(n.contains("physx"))
    }

    @Test
    fun noRedist_returnsEmpty() {
        val g = tmp.newFolder("BareGame")
        touch(g, "game.exe")
        touch(g, "data/assets.pak")
        assertEquals(emptyList<String>(), names(g))
    }
}
