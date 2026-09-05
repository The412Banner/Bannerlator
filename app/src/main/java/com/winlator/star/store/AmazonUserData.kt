package com.winlator.star.store

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The signed-in Amazon account for the Profile tab. The device-registration token the app holds is
 * an Amazon Games token, not a Login-with-Amazon `profile`-scoped one, so the standard
 * `api.amazon.com/user/profile` call may well be refused — everything here is fail-soft and the
 * tab renders from the credential store alone (device serial, token expiry) when it is.
 */
object AmazonUserData {

    private const val TAG = "AmazonUser"
    private const val PREFS = "bh_amazon_prefs"
    private const val KEY_CACHE = "amazon_userdata_cache"

    data class Profile(val name: String, val email: String, val userId: String, val fetchedAt: Long)

    fun cached(ctx: Context): Profile? = runCatching {
        val s = ctx.getSharedPreferences(PREFS, 0).getString(KEY_CACHE, null) ?: return null
        val o = JSONObject(s)
        Profile(o.optString("name"), o.optString("email"), o.optString("userId"), o.optLong("at"))
    }.getOrNull()

    suspend fun fetch(ctx: Context): Profile? = withContext(Dispatchers.IO) {
        val token = AmazonCredentialStore.getValidAccessToken(ctx) ?: return@withContext null
        val body = StoreNet.get("https://api.amazon.com/user/profile", bearer = token) ?: return@withContext null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        val name = o.optString("name", "")
        if (name.isBlank() && o.optString("user_id", "").isBlank()) return@withContext null
        val profile = Profile(name, o.optString("email", ""), o.optString("user_id", ""), System.currentTimeMillis())
        ctx.getSharedPreferences(PREFS, 0).edit().putString(
            KEY_CACHE,
            JSONObject().put("name", profile.name).put("email", profile.email)
                .put("userId", profile.userId).put("at", profile.fetchedAt).toString(),
        ).apply()
        Log.i(TAG, "profile resolved (name ${if (name.isBlank()) "absent" else "present"})")
        profile
    }
}
