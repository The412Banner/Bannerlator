package com.winlator.star.store

import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-screen keep-or-delete decision for a cancelled Epic download.
 *
 * The Cancel dialog can be raised from any of three places — the game detail page, a row in any
 * Epic library view, or the cross-store Download Manager — while the download itself runs in
 * whichever Activity coroutine started it. This shared, per-app policy lets any of those UIs record
 * the user's choice (keep the partial for #3 delta-resume, or delete all files) and lets the running
 * download coroutine act on it the moment it observes the cancel — regardless of which screen the
 * user tapped Cancel on.
 *
 * Keyed by Epic appName. Default (no entry) = keep, matching the resume-safe default everywhere.
 */
object EpicCancelPolicy {
    private val deleteFlags = ConcurrentHashMap<String, Boolean>()

    /** Record whether cancelling [appName] should delete its downloaded files (true) or keep them. */
    fun setDeleteOnCancel(appName: String, delete: Boolean) {
        deleteFlags[appName] = delete
    }

    /** Read-and-clear the choice for [appName]. Returns false (keep) if none was set. */
    fun consumeDeleteOnCancel(appName: String): Boolean = deleteFlags.remove(appName) ?: false

    /** Drop any pending choice for [appName] without acting on it (e.g. a fresh download starts). */
    fun clear(appName: String) {
        deleteFlags.remove(appName)
    }
}
