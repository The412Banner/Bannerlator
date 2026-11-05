package com.winlator.star.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [EpicDownloadManager.parseCdnUrls] on the wrapper [EpicApiClient.getManifestApiJson] produces.
 * Android's org.json escapes every "/" as "\/"; the old hand-rolled slicing kept those backslashes
 * in the CDN base URL, the cloud dir and the signed query values, so every manifest request was
 * refused (HTTP 403/404). A real JSON parse must hand back clean strings.
 */
class EpicCdnUrlParseTest {

    // Exactly the shape Android's JSONObject.toString() emits: escaped slashes, wrapper keys.
    private val androidEscapedWrapper = """
    {"manifests":[
      {"uri":"https:\/\/egdownload.fastly-edge.com\/Builds\/Org\/o-abc\/def\/default\/X.manifest",
       "queryParams":[{"name":"f_token","value":"ab\/cd+ef=="},{"name":"x","value":"a~b*c"}]},
      {"uri":"https:\/\/epicgames-download1.akamaized.net\/Builds\/Org\/o-abc\/def\/default\/X.manifest",
       "queryParams":[]},
      {"uri":"https:\/\/cloudflare.epicgamescdn.com\/Builds\/Org\/o-abc\/def\/default\/X.manifest",
       "queryParams":[{"name":"skipped","value":"1"}]}
    ],"versionId":"1.2.3"}
    """.trimIndent()

    @Test
    fun escapedSlashesAreUnescapedInBaseUrlCloudDirAndAuth() {
        val cdns = EpicDownloadManager.parseCdnUrls(androidEscapedWrapper)

        // cloudflare.epicgamescdn.com is skipped
        assertEquals(2, cdns.size)

        val fastly = cdns[0]
        assertEquals("https://egdownload.fastly-edge.com", fastly.baseUrl)
        assertEquals("/Builds/Org/o-abc/def/default", fastly.cloudDir)
        assertEquals("?f_token=ab/cd+ef==&x=a~b*c", fastly.authParams)

        val akamai = cdns[1]
        assertEquals("https://epicgames-download1.akamaized.net", akamai.baseUrl)
        assertEquals("/Builds/Org/o-abc/def/default", akamai.cloudDir)
        assertEquals("", akamai.authParams)

        for (c in cdns) {
            assertFalse(c.baseUrl, c.baseUrl.contains('\\'))
            assertFalse(c.cloudDir, c.cloudDir.contains('\\'))
            assertFalse(c.authParams, c.authParams.contains('\\'))
        }
    }

    @Test
    fun missingOrEmptyManifestsGivesNoCdns() {
        assertTrue(EpicDownloadManager.parseCdnUrls("""{"versionId":"1"}""").isEmpty())
        assertTrue(EpicDownloadManager.parseCdnUrls("""{"manifests":[],"versionId":"1"}""").isEmpty())
    }
}
