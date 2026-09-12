package com.winlator.star.store

import com.winlator.star.store.download.MediaVideo
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Media-tab parsers in [EpicStoreCatalog], on fixtures trimmed from live responses
 * (2026-09-07: content API `control`, searchStore `Control Resonant` / `Foxyball`).
 */
class EpicStoreMediaParserTest {

    // …/api/en-US/content/products/control — two pages; "home" is second on purpose.
    private val contentApiBody = """
    {"pages":[
      {"_title":"awe","_slug":"awe","data":{
        "gallery":{"galleryImages":[{"src":"https://cdn2.unrealengine.com/awe02.jpg","row":2}]},
        "carousel":{"items":[]}}},
      {"_title":"home","_slug":"home","data":{
        "gallery":{"galleryImages":[
          {"src":"https://cdn2.unrealengine.com/home1.png","row":3},
          {"src":"https://cdn2.unrealengine.com/home2.png","row":5},
          {"src":"","row":1}]},
        "carousel":{"items":[
          {"image":{"src":"https://cdn2.unrealengine.com/awards.jpg"},"video":{"loop":false}},
          {"image":{},"video":{"title":"Control World Trailer","type":"epicHosted",
            "recipes":"{\n \"en-US\": [\n {\"recipe\": \"video-webm\", \"mediaRefId\": \"webm1\"},\n {\"recipe\": \"video-hls\", \"mediaRefId\": \"hls1\"},\n {\"recipe\": \"video-fmp4\", \"mediaRefId\": \"fmp41\"}\n ]\n}"}},
          {"image":{},"video":{"type":"epicHosted",
            "recipes":"{\"de\":[{\"recipe\":\"video-hls\",\"mediaRefId\":\"hls2\"},{\"recipe\":\"video-webm\",\"mediaRefId\":\"webm2\"}]}"}}
        ]}}}
    ]}
    """.trimIndent()

    @Test
    fun productPagePrefersHomeGalleryAndCarouselRecipes() {
        val page = EpicStoreCatalog.parseProductPage(contentApiBody)
        assertEquals(listOf("https://cdn2.unrealengine.com/home1.png", "https://cdn2.unrealengine.com/home2.png"), page.screenshots.map { it.full })
        assertEquals("https://cdn2.unrealengine.com/home1.png?resize=1&w=640", page.screenshots[0].thumb)
        // Image-only carousel items are skipped; fmp4 wins over webm/hls; a non-en-US locale falls
        // back to its first usable recipe (webm — never HLS).
        assertEquals(listOf("fmp41", "webm2"), page.videos.map { it.mediaRefId })
        assertEquals("Control World Trailer", page.videos[0].title)
        assertEquals("Trailer 2", page.videos[1].title)
        assertNull(page.videos[0].videoId)
    }

    @Test
    fun productPageWithoutPagesIsEmpty() {
        assertTrue(EpicStoreCatalog.parseProductPage("""{"error":true,"message":"Page was not found"}""").isEmpty)
    }

    @Test
    fun offerKeyImagesYieldScreenshotsAndResolvableVideos() {
        val keyImages = JSONArray(
            """[
              {"type":"OfferImageWide","url":"https://cdn1.epicgames.com/wide.png"},
              {"type":"featuredMedia","url":"https://cdn1.epicgames.com/shot1.png"},
              {"type":"Screenshot","url":"https://cdn1.epicgames.com/shot2.png"},
              {"type":"heroCarouselVideo","url":"com.epicgames.video://3747b742-1d67-463c-9fba-be55eb576a33?cover=https%3A%2F%2Fcdn1.epicgames.com%2Fcover.png"},
              {"type":"heroCarouselVideo","url":"com.epicgames.video.qs://bbf84372-b877-49de-baea-987fbd93eeef?cover=x"},
              {"type":"heroCarouselVideo","url":"com.epicgames.video://not-an-id\"){evil"}
            ]""",
        )
        val page = EpicStoreCatalog.parseOfferMedia(keyImages)
        assertEquals(listOf("https://cdn1.epicgames.com/shot1.png", "https://cdn1.epicgames.com/shot2.png"), page.screenshots.map { it.full })
        assertEquals(1, page.videos.size)
        assertEquals("3747b742-1d67-463c-9fba-be55eb576a33", page.videos[0].videoId)
        assertEquals("https://cdn1.epicgames.com/cover.png", page.videos[0].poster)
    }

    @Test
    fun resolvedVideosPick480pAndDropFailedAliases() {
        val refs = listOf(
            EpicStoreCatalog.VideoRef("fmp41", null, null, "Legacy"),
            EpicStoreCatalog.VideoRef(null, "3747b742-1d67-463c-9fba-be55eb576a33", "https://poster.png", "Modern"),
            EpicStoreCatalog.VideoRef("gone", null, null, "Missing"),
        )
        val body = """
        {"data":{
          "v0":{"getMediaRef":{"outputs":[
            {"key":"high","url":"https://media-cdn.epicgames.com/a-high.fmp4","contentType":"video/mp4"},
            {"key":"low","url":"https://media-cdn.epicgames.com/a-low.fmp4","contentType":"video/mp4"},
            {"key":"audio","url":"https://media-cdn.epicgames.com/a-audio.m4a","contentType":"audio/m4a"},
            {"key":"thumbnail","url":"https://media-cdn.epicgames.com/a-thumb.png","contentType":"image/png"},
            {"key":"manifest","url":"https://media-cdn.epicgames.com/a.mpd","contentType":"application/dash+xml"}]}},
          "v1":{"fetchVideoByLocale":[
            {"recipe":"video-webm","mediaRef":{"outputs":[{"key":"medium","url":"https://media-cdn.epicgames.com/b.webm","contentType":"video/webm"}]}},
            {"recipe":"video-fmp4","mediaRef":{"outputs":[{"key":"medium","url":"https://media-cdn.epicgames.com/b-medium.fmp4","contentType":"video/mp4"}]}}]},
          "v2":{"getMediaRef":null}},
         "errors":[{"message":"MediaQuery/getMediaRef: Request failed with status code 400","path":["v2","getMediaRef"]}]}
        """.trimIndent()
        val videos = EpicStoreCatalog.parseResolvedVideos(body, refs)
        assertEquals(2, videos.size)
        val legacy = videos[0] as MediaVideo.Direct
        assertEquals("https://media-cdn.epicgames.com/a-low.fmp4", legacy.url)
        assertEquals("https://media-cdn.epicgames.com/a-thumb.png", legacy.poster)
        assertEquals("Legacy", legacy.title)
        val modern = videos[1] as MediaVideo.Direct
        assertEquals("https://media-cdn.epicgames.com/b-medium.fmp4", modern.url)
        assertEquals("https://poster.png", modern.poster)
    }
}
