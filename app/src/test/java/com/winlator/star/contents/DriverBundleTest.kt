package com.winlator.star.contents

import com.winlator.star.contents.DriverInstaller.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverBundleTest {
    // manifest.json of Droid-Deck/Drivers DD-Turnip-v0.7.0.zip (sha256 lines shortened).
    private val dd = """
        {
          "schemaVersion": 1,
          "package": "DD-Turnip",
          "version": "0.7.0",
          "tag": "DD-Turnip-v0.7.0",
          "mesa": {"version": "27.0.0-devel", "commit": "beaf3192c2e0a3b4fcc05362c0ca9fc198a4e236"},
          "drivers": {
            "android": {"path": "android", "library": "android/libvulkan_freedreno.so", "meta": "android/meta.json", "libc": "bionic", "sha256": "c4cf"},
            "linux": {"path": "linux", "library": "linux/libvulkan_freedreno.so", "meta": "linux/meta.json", "libc": "glibc", "sha256": "1e52"}
          }
        }
    """.trimIndent()

    @Test fun droidDeckZip() {
        val b = DriverInstaller.parseBundle(dd)!!
        assertEquals("DD-Turnip-v0.7.0", b.name)
        assertEquals(listOf(Place.ANDROID, Place.LINUX), b.parts)
        assertEquals("android", b.paths[Place.ANDROID])
        assertEquals("linux", b.paths[Place.LINUX])
    }

    @Test fun nameWithoutTag() {
        val b = DriverInstaller.parseBundle("""{"package":"DD-Turnip","version":"0.8.0","drivers":{"android":{"path":"android/"}}}""")!!
        assertEquals("DD-Turnip 0.8.0", b.name)
        assertEquals(mapOf(Place.ANDROID to "android"), b.paths)
    }

    @Test fun notABundle() {
        assertNull(DriverInstaller.parseBundle("""{"name":"Turnip","libraryName":"libvulkan_freedreno.so"}"""))
        assertNull(DriverInstaller.parseBundle("""{"drivers":{}}"""))
        assertNull(DriverInstaller.parseBundle("""{"drivers":{"windows":{"path":"win"}}}"""))
        assertNull(DriverInstaller.parseBundle("not json"))
    }

    @Test fun unsafePathsAreSkipped() {
        assertNull(DriverInstaller.parseBundle("""{"drivers":{"android":{"path":"../x"},"linux":{"path":""}}}"""))
        val b = DriverInstaller.parseBundle("""{"drivers":{"android":{"path":"a\\b"},"linux":{"path":"linux"}}}""")!!
        assertEquals(listOf(Place.LINUX), b.parts)
    }

    @Test fun androidIsTheDefault() {
        assertEquals(setOf(Place.ANDROID), DriverPartsPrompt.defaultParts(listOf(Place.ANDROID, Place.LINUX)))
        assertEquals(setOf(Place.ANDROID), DriverPartsPrompt.defaultParts(listOf(Place.LINUX, Place.ANDROID)))
        assertEquals(setOf(Place.LINUX), DriverPartsPrompt.defaultParts(listOf(Place.LINUX)))
    }
}
