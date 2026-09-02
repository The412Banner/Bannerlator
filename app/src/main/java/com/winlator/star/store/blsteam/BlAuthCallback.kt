package com.winlator.star.store.blsteam

/**
 * Fired by [BlSteamSession] when an auth session has an auth update.
 * QR login can emit a remote-approval hint before the final success or
 * failure result arrives. Invoked on a native worker thread — marshal
 * to your own dispatcher before touching UI.
 */
fun interface BlAuthCallback {
    fun onAuthResult(result: BlAuthResult)
}
