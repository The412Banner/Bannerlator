package com.winlator.star.store

import com.winlator.star.store.download.MediaVideo
import com.winlator.star.store.download.StoreMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Media tab's Steam source: `appdetails?filters=screenshots,movies` → [StoreMedia]. Covers the
 * https rewrite of the CDN's `http://video.akamai…` movie URLs, the mp4-480 → max → webm rendition
 * order, the `"data": []` quirk (filtered-out app → EMPTY, not a failure) and the three-way
 * outcome the page relies on (media / EMPTY / null).
 */
class SteamMediaParseTest {

    private val body = """
        {"440":{"success":true,"data":{
          "screenshots":[
            {"id":0,"path_thumbnail":"https://shared.akamai.steamstatic.com/a/ss_1.600x338.jpg?t=1","path_full":"https://shared.akamai.steamstatic.com/a/ss_1.1920x1080.jpg?t=1"},
            {"id":1,"path_thumbnail":"","path_full":"http://shared.akamai.steamstatic.com/a/ss_2.1920x1080.jpg"},
            {"id":2,"path_thumbnail":"https://x/thumb_only.jpg"}
          ],
          "movies":[
            {"id":1,"name":"Meet the Heavy","thumbnail":"http://cdn.akamai.steamstatic.com/steam/apps/1/movie.jpg",
             "webm":{"480":"http://video.akamai.steamstatic.com/store_trailers/1/movie480.webm","max":"http://video.akamai.steamstatic.com/store_trailers/1/movie_max.webm"},
             "mp4":{"480":"http://video.akamai.steamstatic.com/store_trailers/1/movie480.mp4","max":"http://video.akamai.steamstatic.com/store_trailers/1/movie_max.mp4"},
             "highlight":true},
            {"id":2,"name":"   ","thumbnail":"",
             "webm":{"max":"https://video.akamai.steamstatic.com/store_trailers/2/movie_max.webm"}},
            {"id":3,"name":"No renditions"}
          ]
        }}}
    """.trimIndent()

    @Test
    fun parsesScreenshotsAndMovies() {
        val m = SteamStoreSearch.parseMedia(body, 440)!!
        assertEquals(2, m.screenshots.size)
        assertEquals("https://shared.akamai.steamstatic.com/a/ss_1.600x338.jpg?t=1", m.screenshots[0].thumb)
        assertEquals("https://shared.akamai.steamstatic.com/a/ss_1.1920x1080.jpg?t=1", m.screenshots[0].full)
        // Missing thumbnail → the full image doubles as the thumb; http → https.
        assertEquals("https://shared.akamai.steamstatic.com/a/ss_2.1920x1080.jpg", m.screenshots[1].thumb)
        assertEquals(m.screenshots[1].thumb, m.screenshots[1].full)

        assertEquals(2, m.videos.size)
        val first = m.videos[0] as MediaVideo.Direct
        assertEquals("https://video.akamai.steamstatic.com/store_trailers/1/movie480.mp4", first.url)
        assertEquals("https://cdn.akamai.steamstatic.com/steam/apps/1/movie.jpg", first.poster)
        assertEquals("Meet the Heavy", first.title)
        val second = m.videos[1] as MediaVideo.Direct
        assertEquals("https://video.akamai.steamstatic.com/store_trailers/2/movie_max.webm", second.url)
        assertNull(second.poster)
        assertEquals("Trailer 2", second.title)
        assertEquals(4, m.count)
    }

    @Test
    fun filteredOutAppIsEmptyNotFailure() {
        // Steam answers an ARRAY for `data` when the filters matched nothing.
        assertEquals(StoreMedia.EMPTY, SteamStoreSearch.parseMedia("""{"10":{"success":true,"data":[]}}""", 10))
        assertEquals(StoreMedia.EMPTY, SteamStoreSearch.parseMedia("""{"10":{"success":false}}""", 10))
        assertTrue(SteamStoreSearch.parseMedia("""{"10":{"success":true,"data":{"screenshots":[],"movies":[]}}}""", 10)!!.isEmpty)
    }

    @Test
    fun malformedOrForeignBodyIsNull() {
        assertNull(SteamStoreSearch.parseMedia("<html>429 Too Many Requests</html>", 10))
        assertNull(SteamStoreSearch.parseMedia("""{"20":{"success":true,"data":{}}}""", 10))
    }

    @Test
    fun screenshotListIsCapped() {
        val shots = (0 until 40).joinToString(",") { """{"path_thumbnail":"https://x/t$it.jpg","path_full":"https://x/f$it.jpg"}""" }
        val m = SteamStoreSearch.parseMedia("""{"10":{"success":true,"data":{"screenshots":[$shots]}}}""", 10)!!
        assertEquals(StoreMedia.MAX_SCREENSHOTS, m.screenshots.size)
        assertEquals("https://x/f0.jpg", m.screenshots.first().full)
    }
}
