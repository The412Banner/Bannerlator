package com.winlator.star.store

import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Diagnostic logging for the Steam storefront (Store / Library / Friends / Profile).
 *
 * The storefront leans on two things that are neither documented nor device-proven — Steam's
 * undocumented `store.steampowered.com/api/` endpoints and the engine's brand-new player-profile
 * and free-license natives — so a blank tab must always leave a trail saying *which* of them gave
 * up. Everything here is failure-first: every network miss, every native returning null, and every
 * empty/error state a user can actually see gets a line.
 *
 * **One prefix, many areas.** Every tag starts with `SteamUI.` so a single on-device filter
 * captures the whole storefront in one stream:
 *
 * ```
 * adb logcat -v time | grep -F 'SteamUI.'
 * ```
 *
 * **Redaction.** Every message goes through [SteamLogRedactor.redact], which strips the registered
 * account name and refresh token plus any e-mail / JWT / long-token / full-SteamID64 pattern — this
 * log is meant to be shared. SteamIDs are deliberately logged through [sid] instead: the last five
 * digits correlate lines within a session without identifying the account, and survive redaction
 * (the redactor's SteamID64 rule only matches a full 17-digit run).
 *
 * **Cheap.** Nothing here is called per frame or inside a recomposition hot path — only on state
 * transitions, network completions and failures.
 */
internal object StorefrontLog {

    const val HOST = "SteamUI.Host"
    const val STORE = "SteamUI.Store"
    const val LIBRARY = "SteamUI.Library"
    const val FRIENDS = "SteamUI.Friends"
    const val PROFILE = "SteamUI.Profile"
    const val LICENSE = "SteamUI.License"

    fun i(tag: String, msg: String) {
        Log.i(tag, safe(msg))
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, safe(msg))
    }

    fun w(tag: String, msg: String, t: Throwable) {
        Log.w(tag, safe(msg), t)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, safe(msg))
    }

    /**
     * A SteamID64 reduced to its last five digits (`…18907`). Enough to tell two accounts apart and
     * to correlate lines about the same user; not enough to identify anyone. `none` for 0.
     */
    fun sid(steamId: Long): String =
        if (steamId == 0L) "none" else "…" + steamId.toString().takeLast(5)

    /** Describe a nullable as present/absent without ever printing its contents. */
    fun has(value: Any?): String = if (value == null) "absent" else "present"

    // appIds whose capsule art has already been reported this session. Steam's CDN 404s for a whole
    // class of titles at once (see artFailed), so an un-throttled warn per card flooded logcat hard
    // enough to EVICT the layout and license lines needed to diagnose an unrelated bug. First
    // failure per appId stays at warn — that is what identified the wrong-CDN-host bug in the first
    // place — and every repeat drops to debug.
    private val reportedArtFailures: MutableSet<Int> =
        Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /**
     * Report that every capsule-art candidate for [appId] failed. Warn once per appId per session,
     * debug thereafter.
     */
    fun artFailed(appId: Int, msg: String) {
        if (reportedArtFailures.add(appId)) Log.w(STORE, safe(msg))
        else Log.d(STORE, safe(msg))
    }

    /** A single candidate URL failed but others remain — never worth a warn. */
    fun artCandidateFailed(msg: String) {
        Log.d(STORE, safe(msg))
    }

    private fun safe(msg: String): String =
        try { SteamLogRedactor.redact(msg) } catch (_: Throwable) { msg }
}
