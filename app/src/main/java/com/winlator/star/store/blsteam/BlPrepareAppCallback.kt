package com.winlator.star.store.blsteam

/**
 * Fired by [BlSteamSession.prepareApp] when the pre-warm finishes. Called on
 * a native worker thread — marshal to your own dispatcher before touching UI.
 *
 * @param ok    true if all requested apps are cached and ready for Wine.
 * @param error empty when ok=true; otherwise a short diagnostic string.
 */
fun interface BlPrepareAppCallback {
    fun onPrepareResult(ok: Boolean, error: String)
}
