package com.winlator.star.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Steam USER-review scores (the percent-positive the store shows under "All Reviews") for the
 * Library tab's rating sort and card line.
 *
 * ## Why the public store API and not PICS
 * PICS' `common` block carries a review percentage for many apps, but never the review COUNT (needed
 * as the tie-break), and the Rust engine returns app info through a fixed field set that cannot be
 * extended without rebuilding native code. The store's public endpoints answer the same way whichever
 * engine is running and need no login, so both engines share one source.
 *
 * ## Two sources
 *  - PRIMARY: the batch endpoint `IStoreBrowseService/GetItems/v1` with `include_reviews`, up to
 *    [BATCH_SIZE] apps per request (about 0.7 s for 100), so even a very large library takes minutes, not
 *    hours. It is a GET with the JSON in the URL, hence the 100 cap (300 ids is an HTTP 414).
 *    Its numbers come from `summary_filtered` — what the Steam store page itself shows — and can
 *    differ slightly from the per-game `appreviews` API, which counts some off-topic review activity
 *    that `summary_filtered` leaves out.
 *  - FALLBACK: `store.steampowered.com/appreviews/<appid>`, one game per request, used for the rest
 *    of a run when the batch endpoint misbehaves in a way that is not rate limiting (an HTTP 4xx other
 *    than 429, or [MAX_INVALID_BATCHES] unusable responses in a row), so ratings still arrive.
 *
 * ## Behaviour
 *  - [backfill] walks the games that have no fresh score, strictly one request at a time, with a
 *    pause after each ([BATCH_GAP_MS] / [REQUEST_GAP_MS]), and caches every answer in
 *    [SteamReviewStore] (its own `steam_reviews.db`) so it survives restarts and works offline
 *    afterwards. A batch is written in ONE transaction. Entries older than [STALE_SEC] are refreshed.
 *  - HTTP 429 / 5xx / transport errors back off exponentially and retry the same request; after
 *    [MAX_CONSECUTIVE_FAILURES] in a row the run stops and stays quiet for [COOLDOWN_MS].
 *  - A game with fewer than [MIN_REVIEWS] reviews (Steam itself shows no rating below ten), a game
 *    with no review data, and a game Steam does not answer for are all stored as unrated (-1) with a
 *    fetch stamp, so they are not re-requested for [STALE_SEC].
 *  - Never throws to the caller (other than cancellation) and never touches the main thread: the
 *    network and DB work run on [Dispatchers.IO]; only [backfill]'s callbacks run in the caller's
 *    context.
 */
internal object SteamReviewScores {

    private const val TAG = StorefrontLog.LIBRARY

    /** A stored score older than this (30 days) is fetched again. */
    const val STALE_SEC = 30L * 24 * 60 * 60

    /** Below this many reviews Steam shows no rating, so neither do we. */
    const val MIN_REVIEWS = 10

    /**
     * The Library's rating sort only RANKS a game with at least this many reviews; everything below
     * (and every unrated game) sits in the last "not enough reviews" group however high its percent.
     * The real percent / label / total are still stored and shown on the card.
     */
    const val MIN_RANKED_REVIEWS = 500

    /** Apps per batch request. The ids travel in the URL, so this must stay at or below 100. */
    private const val BATCH_SIZE = 100

    /** Pause after every completed batch request. */
    private const val BATCH_GAP_MS = 300L

    /** Pause after every completed per-game (fallback) request: polite to the store. */
    private const val REQUEST_GAP_MS = 200L

    /** Wait before re-sending a batch whose response was unusable. */
    private const val INVALID_RETRY_MS = 1_000L

    /** Unusable batch responses in a row before switching to the per-game path. */
    private const val MAX_INVALID_BATCHES = 3

    private const val BASE_BACKOFF_MS = 2_000L
    private const val MAX_BACKOFF_MS = 30_000L
    private const val MAX_CONSECUTIVE_FAILURES = 4

    /** After giving up, [backfill] is a no-op for this long (the next library visit retries). */
    private const val COOLDOWN_MS = 2L * 60 * 1000

    /** [backfill] reloads the UI at most this often so a big library doesn't churn it. */
    private const val PROGRESS_EVERY_MS = 1_500L

    /** One Steam review summary. [percent] -1 = no rating; [desc] is Steam's label or "". */
    internal data class ReviewSummary(val percent: Int, val total: Int, val desc: String)

    private val UNRATED = ReviewSummary(-1, 0, "")

    private sealed interface FetchResult {
        data class Ok(val summary: ReviewSummary) : FetchResult

        /** This game will not get a score from a retry (bad JSON, 404, ...). Skip it. */
        data class Permanent(val reason: String) : FetchResult

        /** Rate limit / server error / network trouble: back off and try the same game again. */
        data class Retry(val reason: String) : FetchResult
    }

    private sealed interface BatchResult {
        /** A usable response; [byApp] has an entry for every requested id (unrated where Steam had none). */
        data class Ok(val byApp: Map<Int, ReviewSummary>) : BatchResult

        /** Rate limit / server error / network trouble: back off and re-send the same batch. */
        data class Retry(val reason: String) : BatchResult

        /** A 200 whose body was empty, unparseable or had no `store_items`. Counts toward the fallback. */
        data class Invalid(val reason: String) : BatchResult

        /** An HTTP 4xx other than 429: the endpoint refuses us, so retrying will not help. */
        data class Unsupported(val reason: String) : BatchResult
    }

    /** Per-run counters and callbacks, shared by the helper functions below. */
    private class RunState(
        val onChunk: () -> Unit,
        val onProgress: suspend () -> Unit,
    ) {
        var stored = 0
        var unreported = 0
        var lastReportMs = System.currentTimeMillis()
        var failures = 0
    }

    /** One run at a time, ever — also across a cancelled-and-restarted caller. */
    private val gate = Mutex()

    /** appIds already settled this process (scored, or skipped as unscoreable). */
    private val settled: MutableSet<Int> = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    @Volatile
    private var cooldownUntilMs = 0L

    /** True when [game] has no score yet, or one older than [STALE_SEC]. Demos are never queried:
     *  Steam files reviews under the full game, so a demo's own page has none. */
    fun needsFetch(game: SteamGame, nowSec: Long): Boolean =
        game.appId > 0 &&
            game.type != LibraryTypeFilter.TYPE_DEMO &&
            (game.reviewUpdated <= 0L || abs(nowSec - game.reviewUpdated) > STALE_SEC)

    /** The subset of [games] [backfill] would still request, in the same order. */
    fun pending(games: List<SteamGame>): List<SteamGame> {
        val now = System.currentTimeMillis() / 1000L
        return games.filter { needsFetch(it, now) && it.appId !in settled }
    }

    /**
     * Fetch and cache a score for every game in [games] that needs one, in list order (so callers
     * put what is on screen first). Suspends until done, cancelled, or it gives up; returns how many
     * scores were stored.
     *
     * [onChunk] runs (in the CALLER's context) after every saved batch — or every saved game on the
     * fallback path — so a counter can count down per chunk. It must be cheap: no reload in it.
     *
     * [onProgress] runs in the CALLER's coroutine context, debounced to [PROGRESS_EVERY_MS], plus
     * once at the end if anything was stored since the last call — the Library tab passes its quiet
     * reload so rows pick up the new values. It is not called when the run is cancelled.
     */
    suspend fun backfill(
        games: List<SteamGame>,
        onChunk: () -> Unit = {},
        onProgress: suspend () -> Unit,
    ): Int {
        if (System.currentTimeMillis() < cooldownUntilMs) return 0
        if (pending(games).isEmpty()) return 0
        // withLock queues a second caller behind the first rather than dropping it, and is itself
        // cancellable; the first run notices ITS cancellation within one request.
        return gate.withLock { runLocked(games, RunState(onChunk, onProgress)) }
    }

    private suspend fun runLocked(games: List<SteamGame>, st: RunState): Int {
        if (System.currentTimeMillis() < cooldownUntilMs) return 0
        // Re-filtered under the lock: a run that held it first may already have settled some of these.
        val todo = pending(games)
        if (todo.isEmpty()) return 0
        StorefrontLog.i(TAG, "review scores: ${todo.size} of ${games.size} game(s) need a Steam rating")

        var batchMode = true
        var invalidStreak = 0
        var i = 0
        while (i < todo.size) {
            coroutineContext.ensureActive()
            if (batchMode) {
                val chunk = ArrayList<Int>(BATCH_SIZE)
                var next = i
                while (next < todo.size && chunk.size < BATCH_SIZE) {
                    val id = todo[next].appId
                    if (id !in settled) chunk.add(id)
                    next++
                }
                if (chunk.isEmpty()) break

                when (val r = withContext(Dispatchers.IO) { fetchBatch(chunk) }) {
                    is BatchResult.Ok -> {
                        st.failures = 0
                        invalidStreak = 0
                        val saved = withContext(Dispatchers.IO) { storeBatch(chunk, r.byApp) }
                        if (saved) {
                            settled.addAll(chunk)
                            st.noteStored(chunk.size)
                        }
                        i = next
                        delay(BATCH_GAP_MS)
                    }

                    is BatchResult.Retry -> if (!st.backOff(r.reason)) break

                    is BatchResult.Invalid -> {
                        invalidStreak++
                        if (invalidStreak >= MAX_INVALID_BATCHES) {
                            StorefrontLog.w(
                                TAG,
                                "review scores: batch endpoint gave $invalidStreak unusable responses " +
                                    "(${r.reason}) — falling back to one request per game",
                            )
                            batchMode = false
                            st.failures = 0
                        } else {
                            StorefrontLog.w(TAG, "review scores: unusable batch response (${r.reason}) — retrying")
                            delay(INVALID_RETRY_MS)
                        }
                    }

                    is BatchResult.Unsupported -> {
                        StorefrontLog.w(
                            TAG,
                            "review scores: batch endpoint refused (${r.reason}) — " +
                                "falling back to one request per game",
                        )
                        batchMode = false
                        st.failures = 0
                    }
                }
            } else {
                val game = todo[i]
                if (game.appId in settled) { i++; continue }

                when (val r = withContext(Dispatchers.IO) { fetchOne(game.appId) }) {
                    is FetchResult.Ok -> {
                        st.failures = 0
                        val saved = withContext(Dispatchers.IO) { store(game.appId, r.summary) }
                        if (saved) {
                            settled.add(game.appId)
                            st.noteStored(1)
                        }
                        i++
                        delay(REQUEST_GAP_MS)
                    }

                    is FetchResult.Permanent -> {
                        StorefrontLog.w(TAG, "review scores: app ${game.appId} skipped (${r.reason})")
                        settled.add(game.appId)
                        i++
                        delay(REQUEST_GAP_MS)
                    }

                    is FetchResult.Retry -> if (!st.backOff(r.reason)) break
                }
            }
        }
        if (st.unreported > 0) st.onProgress()
        StorefrontLog.i(TAG, "review scores: stored ${st.stored} rating(s)")
        return st.stored
    }

    /** Count [n] saved scores, tell the caller's counter, and reload the UI if it is time. */
    private suspend fun RunState.noteStored(n: Int) {
        stored += n
        unreported += n
        onChunk()
        val now = System.currentTimeMillis()
        if (now - lastReportMs >= PROGRESS_EVERY_MS) {
            lastReportMs = now
            unreported = 0
            onProgress()
        }
    }

    /**
     * Record one transient failure and wait out the backoff. Returns false when it was the
     * [MAX_CONSECUTIVE_FAILURES]-th in a row: the cooldown is set and the run should stop.
     */
    private suspend fun RunState.backOff(reason: String): Boolean {
        failures++
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS
            StorefrontLog.w(
                TAG,
                "review scores: giving up after $failures consecutive failures " +
                    "($reason) — retrying on the next library visit",
            )
            return false
        }
        val wait = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (failures - 1))
        StorefrontLog.w(TAG, "review scores: $reason — backing off ${wait}ms")
        delay(wait)
        return true
    }

    // ── Network + parse: batch endpoint ───────────────────────────────────────────────────────

    private fun fetchBatch(appIds: List<Int>): BatchResult {
        val ids = JSONArray()
        appIds.forEach { ids.put(JSONObject().put("appid", it)) }
        val input = JSONObject()
            .put("ids", ids)
            .put(
                "context",
                JSONObject().put("language", "english").put("country_code", "US").put("steam_realm", 1),
            )
            .put("data_request", JSONObject().put("include_reviews", true))
        val url = "https://api.steampowered.com/IStoreBrowseService/GetItems/v1?input_json=" +
            URLEncoder.encode(input.toString(), "UTF-8")
        return when (val r = SteamStoreSearch.httpGetDetailed(url)) {
            is SteamStoreSearch.HttpOutcome.Ok -> try {
                parseBatch(r.body, appIds)?.let { BatchResult.Ok(it) }
                    ?: BatchResult.Invalid("no store_items in response")
            } catch (e: Exception) {
                BatchResult.Invalid("unparseable response: ${e.javaClass.simpleName}")
            }
            is SteamStoreSearch.HttpOutcome.HttpError ->
                if (r.code == 429 || r.code >= 500) BatchResult.Retry("HTTP ${r.code}")
                else BatchResult.Unsupported("HTTP ${r.code}")
            is SteamStoreSearch.HttpOutcome.Transport -> BatchResult.Retry("network ${r.kind}")
            is SteamStoreSearch.HttpOutcome.EmptyBody -> BatchResult.Invalid("empty response")
        }
    }

    /**
     * Parse a `GetItems` response for the requested [appIds]. Returns null when the body has no
     * `response.store_items` array or it is empty (an unusable response); throws
     * [org.json.JSONException] on malformed JSON. Every requested id gets an entry: an item with
     * `success` other than 1, no `reviews`, or no `summary_filtered` — or one Steam left out
     * entirely — is "Steam has no rating for this app" → unrated.
     *
     * The numbers are `summary_filtered`'s (review_count, percent_positive, review_score_label):
     * what the Steam store page shows. They can differ slightly from the per-game `appreviews`
     * totals, which include some review activity the store page filters out.
     */
    internal fun parseBatch(json: String, appIds: List<Int>): Map<Int, ReviewSummary>? {
        val items = JSONObject(json).optJSONObject("response")?.optJSONArray("store_items") ?: return null
        if (items.length() == 0) return null
        val out = HashMap<Int, ReviewSummary>(appIds.size * 2)
        for (k in 0 until items.length()) {
            val item = items.optJSONObject(k) ?: continue
            val id = item.optInt("appid", item.optInt("id", 0))
            if (id <= 0) continue
            out[id] = if (item.optInt("success", 0) != 1) {
                UNRATED
            } else {
                filteredSummary(item.optJSONObject("reviews")?.optJSONObject("summary_filtered"))
            }
        }
        for (id in appIds) if (id !in out) out[id] = UNRATED
        return out
    }

    private fun filteredSummary(sf: JSONObject?): ReviewSummary {
        if (sf == null) return UNRATED
        val count = sf.optInt("review_count", 0).coerceAtLeast(0)
        if (count < MIN_REVIEWS) return ReviewSummary(-1, count, "")
        val percent = sf.optInt("percent_positive", -1)
        if (percent < 0) return ReviewSummary(-1, count, "")
        return ReviewSummary(percent.coerceIn(0, 100), count, cleanLabel(sf.optString("review_score_label", "")))
    }

    /** Steam's label if it is a rating word; "" for a count ("7 user reviews") or "No user reviews". */
    private fun cleanLabel(raw: String): String {
        val s = raw.trim()
        return if (s.isEmpty() || s.first().isDigit() || s.startsWith("No ", ignoreCase = true)) "" else s
    }

    private fun storeBatch(appIds: List<Int>, byApp: Map<Int, ReviewSummary>): Boolean {
        val ctx = SteamRepository.getInstance().appContextOrNull()
        if (ctx == null) {
            StorefrontLog.w(TAG, "review scores: no app context, could not save a batch of ${appIds.size}")
            return false
        }
        val now = System.currentTimeMillis() / 1000L
        val rows = HashMap<Int, SteamReviewStore.Review>(appIds.size * 2)
        for (id in appIds) {
            val s = byApp[id] ?: UNRATED
            rows[id] = SteamReviewStore.Review(s.percent, s.total, s.desc, now)
        }
        return SteamReviewStore.putAll(ctx, rows)
    }

    // ── Network + parse: per-game fallback ────────────────────────────────────────────────────

    private fun fetchOne(appId: Int): FetchResult {
        val url = "https://store.steampowered.com/appreviews/$appId" +
            "?json=1&language=all&purchase_type=all&num_per_page=0"
        return when (val r = SteamStoreSearch.httpGetDetailed(url)) {
            is SteamStoreSearch.HttpOutcome.Ok -> try {
                FetchResult.Ok(parseSummary(r.body))
            } catch (e: Exception) {
                FetchResult.Permanent("unparseable response: ${e.javaClass.simpleName}")
            }
            is SteamStoreSearch.HttpOutcome.HttpError ->
                if (r.code == 429 || r.code >= 500) FetchResult.Retry("HTTP ${r.code}")
                else FetchResult.Permanent("HTTP ${r.code}")
            is SteamStoreSearch.HttpOutcome.Transport -> FetchResult.Retry("network ${r.kind}")
            is SteamStoreSearch.HttpOutcome.EmptyBody -> FetchResult.Permanent("empty response")
        }
    }

    /**
     * Parse an `appreviews` response. A `success` other than 1 or a missing `query_summary` is
     * "Steam has nothing for this app" → unrated. Throws [org.json.JSONException] on malformed JSON
     * (the caller turns that into a skip).
     */
    internal fun parseSummary(json: String): ReviewSummary {
        val root = JSONObject(json)
        if (root.optInt("success", 0) != 1) return UNRATED
        val q = root.optJSONObject("query_summary") ?: return UNRATED
        val positive = q.optInt("total_positive", 0).coerceAtLeast(0)
        val negative = q.optInt("total_negative", 0).coerceAtLeast(0)
        val total = positive + negative
        if (total < MIN_REVIEWS) return ReviewSummary(-1, total, "")
        val percent = (positive * 100.0 / total).roundToInt().coerceIn(0, 100)
        // Below the rating threshold Steam's label is a count ("7 user reviews" / "No user reviews"),
        // not a rating word — never show that as if it were one.
        return ReviewSummary(percent, total, cleanLabel(q.optString("review_score_desc", "")))
    }

    private fun store(appId: Int, s: ReviewSummary): Boolean {
        val ctx = SteamRepository.getInstance().appContextOrNull()
        if (ctx == null) {
            StorefrontLog.w(TAG, "review scores: no app context, could not save app $appId")
            return false
        }
        val now = System.currentTimeMillis() / 1000L
        return SteamReviewStore.putAll(ctx, mapOf(appId to SteamReviewStore.Review(s.percent, s.total, s.desc, now)))
    }
}
