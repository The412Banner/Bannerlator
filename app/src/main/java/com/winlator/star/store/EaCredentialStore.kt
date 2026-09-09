package com.winlator.star.store

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The EA account, and its link to Steam.
 *
 * Persisted to `filesDir/ea/credentials.json`, the same shape and place
 * [AmazonCredentialStore] uses — app-private storage that other apps cannot read.
 *
 * ### What is and is not kept
 * A **refresh token**, never a password. The difference matters more here than for other stores:
 * every fresh EA sign-in is spent against EA's "too many computers" allowance, so an app that
 * replayed a stored password on each launch would quietly burn through that allowance and end up
 * locked out. Holding a refresh token means signing in once and renewing quietly.
 *
 * Encrypting this at rest under the Android keystore would be an improvement — but it is one the
 * Amazon and Epic stores need just as much, so it belongs in a change that covers all three rather
 * than in this one, where it would only look like protection while the other two sat in the clear.
 *
 * ### The Steam link
 * [linkedSteamId] records that EA has been told this Steam account belongs to this EA account. That
 * link lives on EA's servers, not here; this field is only our memory of having done it, so the UI
 * can stop asking. Clearing it locally does not unlink anything at EA.
 */
object EaCredentialStore {

    private const val TAG = "BL_EA"
    private const val DIR_NAME = "ea"
    private const val FILE_NAME = "credentials.json"

    /** Renew this long before the token actually expires, so a launch never waits on a refresh. */
    private const val RENEW_MARGIN_MS = 10 * 60 * 1000L

    data class Credentials(
        val accessToken: String = "",
        val refreshToken: String = "",
        /** Epoch millis at which [accessToken] stops being usable. */
        val expiresAt: Long = 0,
        /** The name shown in the UI, e.g. the EA persona. */
        val personaName: String = "",
        val eaUserId: String = "",
        /** Steam id we have linked to this EA account, empty when not linked. */
        val linkedSteamId: String = "",
    ) {
        val isSignedIn: Boolean get() = refreshToken.isNotEmpty()
        val isLinked: Boolean get() = linkedSteamId.isNotEmpty()

        /** True when [accessToken] is missing or close enough to expiry to be worth renewing. */
        val needsRefresh: Boolean
            get() = accessToken.isEmpty() || System.currentTimeMillis() + RENEW_MARGIN_MS >= expiresAt
    }

    private fun file(ctx: Context) = File(File(ctx.filesDir, DIR_NAME), FILE_NAME)

    fun save(ctx: Context, creds: Credentials): Boolean = try {
        val json = JSONObject()
            .put("access_token", creds.accessToken)
            .put("refresh_token", creds.refreshToken)
            .put("expires_at", creds.expiresAt)
            .put("persona_name", creds.personaName)
            .put("ea_user_id", creds.eaUserId)
            .put("linked_steam_id", creds.linkedSteamId)
        val f = file(ctx)
        f.parentFile?.mkdirs()
        // Write beside and rename, so an interrupted write cannot leave a half file that reads as a
        // signed-out account and silently sends the user back through sign-in.
        val tmp = File(f.parentFile, "${FILE_NAME}.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(f)
    } catch (e: Exception) {
        // Never log the contents: this is the one file in the app that is worth stealing.
        Log.e(TAG, "could not save EA credentials: ${e.javaClass.simpleName}")
        false
    }

    fun load(ctx: Context): Credentials = try {
        val f = file(ctx)
        if (!f.exists()) {
            Credentials()
        } else {
            val json = JSONObject(f.readText())
            Credentials(
                accessToken = json.optString("access_token", ""),
                refreshToken = json.optString("refresh_token", ""),
                expiresAt = json.optLong("expires_at", 0),
                personaName = json.optString("persona_name", ""),
                eaUserId = json.optString("ea_user_id", ""),
                linkedSteamId = json.optString("linked_steam_id", ""),
            )
        }
    } catch (e: Exception) {
        Log.e(TAG, "could not read EA credentials: ${e.javaClass.simpleName}")
        Credentials()
    }

    /** Record that this Steam account has been linked at EA. */
    fun setLinkedSteamId(ctx: Context, steamId: String): Boolean =
        save(ctx, load(ctx).copy(linkedSteamId = steamId))

    fun clear(ctx: Context) {
        if (!file(ctx).delete()) Log.i(TAG, "no EA credentials to clear")
    }
}
