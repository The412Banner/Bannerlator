package com.winlator.star.store

import com.winlator.star.store.download.MediaImage
import com.winlator.star.store.download.MediaVideo
import com.winlator.star.store.download.StoreMedia
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prefs mirror of [StoreMediaCache]: `{at, miss, shots:[{t,f}], videos:[{k,u|id,p,n}]}` round-trips. */
class StoreMediaCacheCodecTest {

    @Test
    fun roundTripsBothVideoKinds() {
        val media = StoreMedia(
            screenshots = listOf(MediaImage("https://x/t1.jpg", "https://x/f1.jpg"), MediaImage("https://x/t2.jpg", "https://x/f2.jpg")),
            videos = listOf(
                MediaVideo.Direct("https://v/480.mp4", "https://v/poster.jpg", "Launch trailer"),
                MediaVideo.YouTube("dQw4w9WgXcQ", null, "Trailer"),
            ),
        )
        val json = StoreMediaCache.encode(media, at = 123L, miss = false)
        assertEquals(123L, json.getLong("at"))
        assertFalse(json.getBoolean("miss"))
        val back = StoreMediaCache.decode(JSONObject(json.toString()))
        assertEquals(media, back)
    }

    @Test
    fun emptyAndMissEntriesSurvive() {
        val json = StoreMediaCache.encode(StoreMedia.EMPTY, at = 1L, miss = true)
        assertTrue(json.getBoolean("miss"))
        assertTrue(StoreMediaCache.decode(json).isEmpty)
    }

    @Test
    fun unknownVideoKindsAreDropped() {
        val o = JSONObject("""{"at":1,"miss":false,"shots":[{"t":"","f":"https://x/f.jpg"}],"videos":[{"k":"z","u":"x"},{"k":"y","id":"","n":"none"}]}""")
        val m = StoreMediaCache.decode(o)
        assertEquals(1, m.screenshots.size)
        assertEquals("https://x/f.jpg", m.screenshots[0].thumb)
        assertTrue(m.videos.isEmpty())
    }
}
