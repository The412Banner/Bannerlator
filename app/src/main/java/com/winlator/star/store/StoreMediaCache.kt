package com.winlator.star.store

import android.content.Context
import com.winlator.star.store.download.MediaImage
import com.winlator.star.store.download.MediaVideo
import com.winlator.star.store.download.Store
import com.winlator.star.store.download.StoreMedia
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-title cache for the Media tab, shared by every store: one entry per `"<store>:<id>"` in
 * memory and mirrored to the `store_media_cache` prefs, so a page re-open (or a rotation, which
 * recreates the detail activities) paints without a request.
 *
 * TTLs encode what the last fetch found:
 *  - non-empty media → 7 days (store galleries barely change);
 *  - a genuine "this title has none" → 6 hours;
 *  - `miss = true` (the fetch could not tell — Steam 429, transport error) → 2 minutes, so a
 *    rate-limited page shows nothing now but retries on the next open without hammering.
 *
 * Amazon does not use this (its media rides in the library cache). Storage shape:
 * `{at, miss, shots:[{t,f}], videos:[{k:"d"|"y", u|id, p, n}]}`.
 */
object StoreMediaCache {

    private const val PREFS = "store_media_cache"
    private const val TTL_HIT_MS = 7L * 24 * 60 * 60 * 1000
    private const val TTL_EMPTY_MS = 6L * 60 * 60 * 1000
    private const val TTL_MISS_MS = 2L * 60 * 1000

    private class Entry(val media: StoreMedia, val at: Long, val miss: Boolean)

    private val mem = ConcurrentHashMap<String, Entry>()

    private fun key(store: Store, id: String) = "${store.name}:$id"

    private fun ttlOf(e: Entry): Long = when {
        e.miss -> TTL_MISS_MS
        e.media.isEmpty -> TTL_EMPTY_MS
        else -> TTL_HIT_MS
    }

    /**
     * The cached media for [id], or null when nothing usable is cached (never fetched, or the
     * entry's TTL ran out) — the caller then fetches and [put]s. A fresh empty entry comes back as
     * [StoreMedia.EMPTY] so the page does NOT refetch.
     */
    fun get(ctx: Context, store: Store, id: String): StoreMedia? {
        val k = key(store, id)
        val now = System.currentTimeMillis()
        val e = mem[k] ?: loadPersisted(ctx, k)?.also { mem[k] = it } ?: return null
        if (now - e.at >= ttlOf(e)) {
            mem.remove(k)
            return null
        }
        return e.media
    }

    /** Record what the fetch found. [miss] = the fetch failed transiently (2-minute TTL). */
    fun put(ctx: Context, store: Store, id: String, media: StoreMedia, miss: Boolean = false) {
        val k = key(store, id)
        val e = Entry(media, System.currentTimeMillis(), miss)
        mem[k] = e
        runCatching {
            ctx.getSharedPreferences(PREFS, 0).edit().putString(k, encode(e.media, e.at, e.miss).toString()).apply()
        }
    }

    private fun loadPersisted(ctx: Context, k: String): Entry? = runCatching {
        val s = ctx.getSharedPreferences(PREFS, 0).getString(k, null) ?: return null
        val o = JSONObject(s)
        Entry(decode(o), o.optLong("at", 0L), o.optBoolean("miss", false))
    }.getOrNull()

    // ── codec (pure; unit-tested) ─────────────────────────────────────────────────────────────

    internal fun encode(media: StoreMedia, at: Long, miss: Boolean): JSONObject = JSONObject().apply {
        put("at", at)
        put("miss", miss)
        put("shots", JSONArray().apply {
            media.screenshots.forEach { put(JSONObject().put("t", it.thumb).put("f", it.full)) }
        })
        put("videos", JSONArray().apply {
            media.videos.forEach { v ->
                put(JSONObject().apply {
                    when (v) {
                        is MediaVideo.Direct -> { put("k", "d"); put("u", v.url) }
                        is MediaVideo.YouTube -> { put("k", "y"); put("id", v.id) }
                    }
                    put("p", v.poster ?: "")
                    put("n", v.title)
                })
            }
        })
    }

    internal fun decode(o: JSONObject): StoreMedia {
        val shots = ArrayList<MediaImage>()
        o.optJSONArray("shots")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val t = s.optString("t"); val f = s.optString("f")
                if (t.isNotBlank() || f.isNotBlank()) shots.add(MediaImage(t.ifBlank { f }, f.ifBlank { t }))
            }
        }
        val videos = ArrayList<MediaVideo>()
        o.optJSONArray("videos")?.let { a ->
            for (i in 0 until a.length()) {
                val v = a.optJSONObject(i) ?: continue
                val poster = v.optString("p").ifBlank { null }
                val name = v.optString("n")
                when (v.optString("k")) {
                    "d" -> v.optString("u").takeIf { it.isNotBlank() }?.let { videos.add(MediaVideo.Direct(it, poster, name)) }
                    "y" -> v.optString("id").takeIf { it.isNotBlank() }?.let { videos.add(MediaVideo.YouTube(it, poster, name)) }
                }
            }
        }
        return StoreMedia(shots, videos)
    }
}
