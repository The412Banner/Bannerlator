package com.winlator.star.store

import com.winlator.star.store.download.MediaVideo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GetEntitlements` media parsing ([AmazonApiClient.parseEntitlement] / [AmazonApiClient.parseMedia])
 * and the library-cache round trip that feeds [AmazonLibrarySync.cachedMedia].
 */
class AmazonMediaParserTest {

    private val entitlement = JSONObject(
        """{
          "id":"ent-uuid",
          "product":{"id":"amzn1.adg.product.abc","title":"Some Game","sku":"SKU1","productType":"GAME",
            "productDetail":{"iconUrl":"https://img/icon.png","details":{
              "logoUrl":"https://img/logo.png","backgroundUrl1":"https://img/bg1.jpg",
              "developer":"Dev","publisher":"Pub",
              "screenshots":["https://img/s1.jpg","https://img/s2.jpg","https://img/s1.jpg",""],
              "videos":["https://vid/trailer.mp4",{"url":"https://vid/gameplay.mp4"}],
              "trailerImageUrl":"https://img/trailer.jpg"}}}
        }""",
    )

    @Test
    fun parsesScreenshotsVideosAndPoster() {
        val g = AmazonApiClient.parseEntitlement(entitlement)!!
        assertEquals("amzn1.adg.product.abc", g.productId)
        assertEquals(listOf("https://img/s1.jpg", "https://img/s2.jpg"), g.screenshots)
        assertEquals(listOf("https://vid/trailer.mp4", "https://vid/gameplay.mp4"), g.videos)
        assertEquals("https://img/trailer.jpg", g.trailerImageUrl)
        // Existing fields are untouched.
        assertEquals("https://img/bg1.jpg", g.heroUrl)
        assertEquals("Dev", g.developer)
    }

    @Test
    fun missingMediaFieldsLeaveEmptyLists() {
        val g = AmazonGame()
        AmazonApiClient.parseMedia(JSONObject("""{"developer":"Dev"}"""), g)
        assertTrue(g.screenshots.isEmpty())
        assertTrue(g.videos.isEmpty())
        assertEquals("", g.trailerImageUrl)
    }

    @Test
    fun cacheRowRoundTripsIntoStoreMedia() {
        val g = AmazonApiClient.parseEntitlement(entitlement)!!
        val row = JSONObject().put("productId", g.productId)
        AmazonLibrarySync.putMedia(row, g)
        val back = AmazonGame()
        AmazonLibrarySync.readMedia(JSONObject(row.toString()), back)
        assertEquals(g.screenshots, back.screenshots)
        assertEquals(g.videos, back.videos)

        val media = AmazonLibrarySync.mediaOf(row)!!
        assertEquals(2, media.screenshots.size)
        assertEquals("https://img/s1.jpg", media.screenshots[0].full)
        assertEquals(2, media.videos.size)
        val first = media.videos[0] as MediaVideo.Direct
        assertEquals("https://vid/trailer.mp4", first.url)
        assertEquals("https://img/trailer.jpg", first.poster)
        assertEquals("Trailer 1", first.title)
    }

    @Test
    fun rowWithoutMediaIsNull() {
        assertNull(AmazonLibrarySync.mediaOf(JSONObject().put("productId", "x").put("title", "old cache")))
    }

    @Test
    fun posterFallsBackToFirstScreenshot() {
        val row = JSONObject().put("screenshots", org.json.JSONArray(listOf("https://img/only.jpg")))
            .put("videos", org.json.JSONArray(listOf("https://vid/t.mp4")))
        val v = AmazonLibrarySync.mediaOf(row)!!.videos.single() as MediaVideo.Direct
        assertEquals("https://img/only.jpg", v.poster)
        assertEquals("Trailer", v.title)
    }
}
