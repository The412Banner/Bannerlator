package com.winlator.star.store

/**
 * Events emitted by SteamRepository into its SharedFlow<SteamEvent>.
 * Activities and ViewModels collect these to drive UI state.
 */
sealed class SteamEvent {

    // --- Connection ---

    /** CM WebSocket connection established. Auto-login will follow if a token is stored. */
    object Connected : SteamEvent()

    /** CM WebSocket disconnected (network drop or explicit disconnect). */
    data class Disconnected(val userInitiated: Boolean) : SteamEvent()

    // --- Authentication ---

    /** Login succeeded. */
    data class LoggedIn(val steamId64: Long, val displayName: String) : SteamEvent()

    /** Logged out (either by user action or server-side). */
    object LoggedOut : SteamEvent()

    /** Login was rejected by Steam. result is the EResult name (e.g. "InvalidPassword"). */
    data class LoginFailed(val result: String) : SteamEvent()

    /**
     * Steam Guard email code required.
     * @param emailDomain Partial email domain hint ("gmail.com" etc.), may be null.
     */
    data class SteamGuardEmailRequired(val emailDomain: String?) : SteamEvent()

    /** Steam Guard mobile authenticator (TOTP) code required. */
    object SteamGuardTwoFactorRequired : SteamEvent()

    // --- QR Login ---

    /** New QR challenge URL ready — display as QR code in UI. */
    data class QrChallengeReceived(val url: String) : SteamEvent()

    /** QR session expired; user should restart QR login flow. */
    object QrExpired : SteamEvent()

    // --- Library Sync ---

    /** PICS library sync in progress. received/total may both be 0 initially. */
    data class LibraryProgress(val received: Int, val total: Int) : SteamEvent()

    /** Library sync completed — SteamRepository.library has been updated. */
    object LibrarySynced : SteamEvent()

    // --- Downloads ---

    /** Download progress update for a game. */
    data class DownloadProgress(
        val appId: Int,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : SteamEvent()

    /** Download finished successfully. */
    data class DownloadComplete(val appId: Int) : SteamEvent()

    /** Download failed. */
    data class DownloadFailed(val appId: Int, val reason: String) : SteamEvent()
}
