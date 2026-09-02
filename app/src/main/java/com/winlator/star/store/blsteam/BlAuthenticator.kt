package com.winlator.star.store.blsteam

import java.util.concurrent.CompletableFuture

/**
 * Steam Guard prompt bridge for the engine's credentials auth session (the same
 * CompletableFuture shape the app's `SteamAuthManager` already resolves from the
 * sign-in dialog, so one implementation serves both engines).
 *
 * The native client invokes these via JNI on its auth thread when Steam asks for
 * a Steam Guard confirmation during BeginAuthSessionViaCredentials /
 * PollAuthSessionStatus, and BLOCKS on the returned future. A code Steam rejects
 * is re-requested with `previousCodeWasIncorrect = true`; completing a future
 * exceptionally (cancel) aborts the sign-in.
 */
interface BlAuthenticator {

    /**
     * Steam pushed a "tap to approve" prompt to the user's mobile authenticator.
     * Return a future that completes `true` if the UI should wait for the user
     * to approve out-of-band (always true for our flow — matches IAuthenticator).
     */
    fun acceptDeviceConfirmation(): CompletableFuture<Boolean>

    /**
     * Steam requires a TOTP code from the mobile authenticator.
     * Complete the returned future with the code the user enters.
     * @param previousCodeWasIncorrect true if a prior submission was rejected.
     */
    fun getDeviceCode(previousCodeWasIncorrect: Boolean): CompletableFuture<String>

    /**
     * Steam requires an email Steam Guard code.
     * Complete the returned future with the code the user enters.
     * @param email the email address Steam emailed the code to (may be null).
     * @param previousCodeWasIncorrect true if a prior submission was rejected.
     */
    fun getEmailCode(
        email: String?,
        previousCodeWasIncorrect: Boolean,
    ): CompletableFuture<String>
}
