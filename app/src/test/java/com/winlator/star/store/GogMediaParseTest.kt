package com.winlator.star.store

import com.winlator.star.store.download.MediaVideo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Media tab's GOG source: `api.gog.com/products/{id}?expand=description,screenshots,videos` →
 * [GogStoreCatalog.ProductDetail.media]. Screenshots keep the catalog page's `ggvgm` strip URL and
 * add the `ggvgm_2x` viewer rendition from the template; videos are YouTube ids (other providers
 * are skipped) and get "Trailer" / "Trailer N" captions since GOG names none of them.
 */
class GogMediaParseTest {

    private val body = """
        {"id":1207658924,"title":"Gunslugs 3","release_date":"2020-01-01T00:00:00+0200",
         "description":{"lead":"Lead","full":"<p>Full</p>"},
         "images":{"background":"//images.gog-statics.com/bg.jpg","logo2x":"//images.gog-statics.com/logo.png"},
         "screenshots":[
           {"image_id":"aaa","formatter_template_url":"//images.gog-statics.com/aaa_{formatter}.png",
            "formatted_images":[
              {"formatter_name":"ggvgt","image_url":"//images.gog-statics.com/aaa_ggvgt.jpg"},
              {"formatter_name":"ggvgm","image_url":"//images.gog-statics.com/aaa_ggvgm.jpg"}]},
           {"image_id":"bbb","formatter_template_url":"https://images.gog-statics.com/bbb_{formatter}.png","formatted_images":[]},
           {"image_id":"ccc","formatted_images":[]}
         ],
         "videos":[
           {"video_id":"dQw4w9WgXcQ","thumbnail_url":"https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg","provider":"youtube"},
           {"video_id":"12345","thumbnail_url":"","provider":"vimeo"},
           {"video_id":"abcdefghijk","provider":"youtube"}
         ]}
    """.trimIndent()

    @Test
    fun screenshotsCarryStripAndViewerRenditions() {
        val d = GogStoreCatalog.parseDetail(JSONObject(body))
        // Legacy strip list (catalog page) unchanged in shape: absolute ggvgm URLs, unparsable ones skipped.
        assertEquals(
            listOf("https://images.gog-statics.com/aaa_ggvgm.jpg", "https://images.gog-statics.com/bbb_ggvgm.jpg"),
            d.screenshots,
        )
        assertEquals(2, d.media.screenshots.size)
        assertEquals("https://images.gog-statics.com/aaa_ggvgm.jpg", d.media.screenshots[0].thumb)
        assertEquals("https://images.gog-statics.com/aaa_ggvgm_2x.jpg", d.media.screenshots[0].full)
        assertEquals("https://images.gog-statics.com/bbb_ggvgm.jpg", d.media.screenshots[1].thumb)
        assertEquals("https://images.gog-statics.com/bbb_ggvgm_2x.jpg", d.media.screenshots[1].full)
        assertEquals("https://images.gog-statics.com/bg.jpg", d.background)
    }

    @Test
    fun videosAreYouTubeOnlyWithNumberedCaptions() {
        val d = GogStoreCatalog.parseDetail(JSONObject(body))
        assertEquals(2, d.media.videos.size)
        val v0 = d.media.videos[0] as MediaVideo.YouTube
        assertEquals("dQw4w9WgXcQ", v0.id)
        assertEquals("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg", v0.poster)
        assertEquals("Trailer 1", v0.title)
        val v1 = d.media.videos[1] as MediaVideo.YouTube
        assertEquals("abcdefghijk", v1.id)
        // No thumbnail_url → the standard YouTube poster.
        assertEquals("https://img.youtube.com/vi/abcdefghijk/hqdefault.jpg", v1.poster)
        assertEquals("Trailer 2", v1.title)
        assertEquals(4, d.media.count)
    }

    @Test
    fun singleVideoIsJustTrailer() {
        val one = """{"videos":[{"video_id":"zzz","provider":"youtube"}]}"""
        val d = GogStoreCatalog.parseDetail(JSONObject(one))
        assertEquals("Trailer", d.media.videos.single().title)
        assertTrue(d.media.screenshots.isEmpty())
    }

    @Test
    fun noGalleryIsEmpty() {
        val d = GogStoreCatalog.parseDetail(JSONObject("""{"description":{"lead":"x"}}"""))
        assertTrue(d.media.isEmpty)
        assertEquals("x", d.lead)
    }
}
