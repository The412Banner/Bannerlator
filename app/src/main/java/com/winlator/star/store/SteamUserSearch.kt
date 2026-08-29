package com.winlator.star.store

import `in`.dragonbra.javasteam.protobufs.steamclient.SteammessagesUseraccountSteamclient
import `in`.dragonbra.javasteam.rpc.service.UserAccount
import `in`.dragonbra.javasteam.steam.handlers.steamunifiedmessages.SteamUnifiedMessages
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Community user search (by persona name) against the same public endpoint the Steam Community website's
 * "Search for users" box hits: `steamcommunity.com/search/SearchCommunityAjax`. Steam answers with a JSON
 * envelope `{ "success": 1, "search_result_count": N, "html": "…" }` whose `html` is a rendered fragment of
 * result rows; there is no structured JSON list, so the rows are scraped defensively with regex.
 *
 * Auth mirrors [SteamChatImageUploader]: a random `sessionid` CSRF value plus (when we hold one) a
 * `steamLoginSecure` cookie built from the self SteamID64 and a WEB-audience access token minted by
 * [SteamFriendsStore.webAuthToken]. The call still works unauthenticated for public results, so a null
 * token just degrades gracefully rather than failing.
 *
 * [search] is pure + blocking (network I/O) and NEVER throws — every failure path returns emptyList().
 * The caller is responsible for running it off the main thread.
 */
object SteamUserSearch {

    private const val COMMUNITY = "https://steamcommunity.com"
    private const val USER_AGENT = "Mozilla/5.0"

    /** accountid (32-bit) + this base == SteamID64. Same constant SteamFriendsStore uses. */
    private const val STEAMID64_BASE = 76561197960265728L

    /** Max rows we surface, matching Steam's default first-page cap; keeps the UI list bounded. */
    private const val MAX_RESULTS = 25

    data class Result(val steamId64: Long, val personaName: String, val avatarUrl: String?)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ── Row-scraping regexes (case-insensitive; DOT spans the escaped-HTML newlines in `html`) ──────
    private val opts = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    /** The 32-bit account id that keys each result row (present on the avatar link, sometimes the name). */
    private val miniRegex = Regex("""data-miniprofile\s*=\s*["']?(\d+)""", opts)

    /** The display-name anchor Steam tags on each result row. */
    private val personaRegex =
        Regex("""<a\b[^>]*\bclass\s*=\s*["'][^"']*searchPersonaName[^"']*["'][^>]*>(.*?)</a>""", opts)

    /** Fallback: any anchor's inner text, used only if [personaRegex] misses (markup drift). */
    private val anyAnchorRegex = Regex("""<a\b[^>]*>(.*?)</a>""", opts)

    /** First image src in a row = the avatar (avatarMedium/avatarIcon). Tolerates either quote style. */
    private val imgSrcRegex = Regex("""<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", opts)

    /** Strip any nested markup out of an anchor's inner text before entity-unescaping. */
    private val tagStripRegex = Regex("""<[^>]+>""")

    /**
     * Blocking network call — the caller runs it off the main thread. Returns emptyList() on ANY failure.
     * Never throws.
     */
    fun search(query: String): List<Result> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            // A null token is fine — Steam still serves public results; we just omit steamLoginSecure.
            val token = try { SteamFriendsStore.webAuthToken() } catch (_: Throwable) { null }
            val selfId = try { SteamFriendsStore.selfSteamId64() } catch (_: Throwable) { 0L }

            val sessionId = randomSessionId()
            val cookie = buildString {
                append("sessionid=").append(sessionId)
                // Only attach the login cookie when we actually have a token (and a real self id, so the
                // `{id}||{token}` pair is well-formed — a malformed one would get the whole request rejected).
                if (!token.isNullOrBlank() && selfId > STEAMID64_BASE) {
                    append("; steamLoginSecure=").append(selfId).append("||").append(token)
                }
            }

            val url = "$COMMUNITY/search/SearchCommunityAjax" +
                "?text=" + URLEncoder.encode(q, "UTF-8") +
                "&filter=users&page=1"

            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("Referer", "$COMMUNITY/search/users/")
                .header("Origin", COMMUNITY)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val body = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                resp.body?.string() ?: return emptyList()
            }

            val html = try { JSONObject(body).optString("html", "") } catch (_: Throwable) { "" }
            if (html.isBlank()) return emptyList()

            parseRows(html, selfId)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Scrape result rows out of the returned `html` fragment, preserving Steam's ranking order.
     *
     * Rows are anchored on `data-miniprofile`: each row window runs from one account id to the next
     * DISTINCT id (a single row can carry the same id twice — avatar link + name link — so consecutive
     * equal ids are collapsed into one window). Within a window we take the first persona-name anchor and
     * the first image src. Dedup by SteamID64, drop the self id, cap at [MAX_RESULTS].
     */
    private fun parseRows(html: String, selfId: Long): List<Result> {
        val minis = miniRegex.findAll(html).toList()
        if (minis.isEmpty()) return emptyList()

        val out = ArrayList<Result>(MAX_RESULTS)
        val seen = HashSet<Long>()

        var i = 0
        while (i < minis.size && out.size < MAX_RESULTS) {
            val idStr = minis[i].groupValues[1]
            val rowStart = minis[i].range.first

            // Advance past every consecutive occurrence of the SAME id, then to the next distinct id,
            // so the row window spans the whole entry regardless of how many times the id is repeated.
            var j = i + 1
            while (j < minis.size && minis[j].groupValues[1] == idStr) j++
            val rowEnd = if (j < minis.size) minis[j].range.first else html.length
            i = j

            val accountId = idStr.toLongOrNull() ?: continue
            if (accountId <= 0L) continue
            val steamId64 = accountId + STEAMID64_BASE
            if (steamId64 == selfId) continue        // never surface ourselves
            if (!seen.add(steamId64)) continue         // dedup

            val window = html.substring(rowStart, rowEnd)

            val personaName = extractPersonaName(window) ?: continue
            val avatarUrl = imgSrcRegex.find(window)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

            out.add(Result(steamId64, personaName, avatarUrl))
        }
        return out
    }

    /** Pull the display name from a row window: prefer the tagged anchor, else the first non-blank anchor. */
    private fun extractPersonaName(window: String): String? {
        val raw = personaRegex.find(window)?.groupValues?.get(1)
            ?: anyAnchorRegex.find(window)?.groupValues?.get(1)
            ?: return null
        val cleaned = unescapeHtml(tagStripRegex.replace(raw, "")).trim()
        return cleaned.takeIf { it.isNotBlank() }
    }

    /** Minimal HTML-entity unescape (the handful Steam emits in persona names). &amp; resolved LAST. */
    private fun unescapeHtml(s: String): String =
        s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    /** 12 random bytes as lowercase hex — the CSRF `sessionid` value the community cookie pairs with. */
    private val secureRandom by lazy { SecureRandom() }

    private fun randomSessionId(): String {
        val b = ByteArray(12)
        secureRandom.nextBytes(b)
        val hexChars = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (byte in b) {
            val v = byte.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}

/**
 * Steam "Quick Invite" — mints a shareable one-time friend link via the unified `UserAccount` service
 * (`CUserAccount_CreateFriendInviteToken#1`). Anyone who opens the returned link and is signed in can send
 * the local user a friend request without knowing their SteamID.
 *
 * NOTE on this JavaSteam version (io.github.joshuatam:javasteam 1.8.0.1-26): the RPC lives on the
 * [UserAccount] service — NOT `Player` — and its response
 * ([SteammessagesUseraccountSteamclient.CUserAccount_CreateFriendInviteToken_Response]) exposes only
 * `invite_token` (plus invite_limit/invite_duration/time_created/valid); there is NO server-provided
 * `invite_link`/`invite_url` field. The shareable URL is therefore built client-side as
 * `https://s.team/p/{invite_token}` (the token already contains the hyphen segment Steam's copy-link uses).
 *
 * Rides the live [SteamRepository] CM session using the exact unified-handler pattern as
 * [SteamFriendsStore]'s web-token mint. Blocking; call off the main thread. Never throws — returns null on
 * any failure (not logged in / handler missing / RPC error / empty token).
 */
object SteamQuickInvite {

    private const val INVITE_LINK_BASE = "https://s.team/p/"

    /** Blocking; returns a shareable s.team invite link, or null if unsupported/failed. Never throws. */
    fun create(): String? {
        return try {
            val repo = SteamRepository.getInstance()
            if (!repo.ensureLoggedIn(8_000L)) return null
            val client = repo.steamClient ?: return null
            val unified = client.getHandler(SteamUnifiedMessages::class.java) ?: return null
            val userAccount = unified.createService(UserAccount::class.java) ?: return null

            // Empty request: Steam applies its own defaults (a valid, shareable token). invite_limit /
            // invite_duration / invite_note are available on the builder if a bounded link is ever wanted.
            val req = SteammessagesUseraccountSteamclient
                .CUserAccount_CreateFriendInviteToken_Request.newBuilder()
                .build()

            val resp = userAccount.createFriendInviteToken(req)
                .toFuture().get(20L, TimeUnit.SECONDS) ?: return null

            val token = resp.body?.inviteToken?.takeIf { it.isNotBlank() } ?: return null
            INVITE_LINK_BASE + token
        } catch (_: Throwable) {
            null
        }
    }
}
