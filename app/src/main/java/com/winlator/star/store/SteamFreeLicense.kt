package com.winlator.star.store

import com.winlator.star.store.blsteam.BlSteamEngine
import `in`.dragonbra.javasteam.enums.EResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Add to Library" for free-to-play titles — the app-side half of the engine's
 * `CMsgClientRequestFreeLicense` / `…Response` pair (**EMsg 5572 / 5573**).
 *
 * The engine takes a BATCH of appIds and answers with a status envelope; [request] is the
 * single-app convenience the store cards use. Null from the native call means "no live session"
 * and nothing else — every real outcome, refusals included, arrives as JSON.
 *
 * ## A grant is not yet a downloadable game
 * The native side blocks until the CM pushes back a license list containing the granted **package**
 * ids and reports that as `libraryUpdated`. But a license records packages, not apps: the
 * package → appIds hop is a PICS crawl driven from Kotlin. So on a grant this MUST kick
 * [SteamRepository.syncLibrary] — without it the account genuinely owns the game and it still never
 * appears as downloadable, which is the entire point of the button.
 *
 * `libraryUpdated == false` on an otherwise successful grant just means the CM's push didn't land
 * inside the engine's 8 s window. That is "granted, library still catching up", NOT a failure, and
 * the sync is kicked either way.
 *
 * ## Honest messages
 * Steam's exact EResult for a paid unowned app could not be verified, so the engine passes
 * `eresult` through raw and this file does **not** carry an EResult→copy table. A refusal is named
 * with [EResult.from] when the code is recognised and phrased generically otherwise. Transport
 * failures (`no_response` / `bad_response`) are reported as connection problems — never as a
 * licensing refusal, which would mislabel a dropped socket as "you have to buy this".
 */
object SteamFreeLicense {

    private const val TAG = StorefrontLog.LICENSE

    sealed interface Result {
        /**
         * Steam granted the license. [libraryUpdated] is false when the CM's license push hadn't
         * landed inside the engine's wait — still a success, the library is just catching up.
         * A library sync has already been kicked when this is returned.
         */
        data class Granted(val appId: Int, val libraryUpdated: Boolean) : Result

        /** The account already had it — treat exactly like [Granted] in the UI. */
        data class AlreadyOwned(val appId: Int) : Result

        /** Steam refused, or the request never completed. [message] is user-facing. */
        data class Failed(val appId: Int, val message: String) : Result
    }

    /**
     * Ask Steam to grant the free license for [appId], then (on success) kick a library sync so the
     * granted package is crawled into an actual downloadable app.
     *
     * Suspends on [Dispatchers.IO] — the native call is a blocking CM round-trip. Never throws.
     */
    suspend fun request(appId: Int): Result = withContext(Dispatchers.IO) {
        if (appId <= 0) return@withContext Result.Failed(appId, "That isn't a valid Steam app.")

        val repo = try { SteamRepository.getInstance() } catch (t: Throwable) {
            StorefrontLog.w(TAG, "app $appId: no repository — ${t.message}")
            return@withContext Result.Failed(appId, "Steam isn't ready yet. Try again in a moment.")
        }

        // Already owned? Nothing to ask for, and the library cache is the account's real license list.
        val owned = try { repo.getCachedGameRows().any { it.appId == appId } } catch (_: Throwable) { false }
        if (owned) return@withContext Result.AlreadyOwned(appId)

        val notConnected = "Not connected to Steam. Reconnect from the status pill and try again."

        val session = try { BlSteamEngine.session() } catch (_: Throwable) { null }
        if (session == null) {
            StorefrontLog.w(TAG, "app $appId: NO ENGINE SESSION — request not sent")
            return@withContext Result.Failed(appId, notConnected)
        }

        StorefrontLog.i(TAG, "app $appId: sending RequestFreeLicense (EMsg 5572)")
        val json = try {
            session.requestFreeLicense(intArrayOf(appId))
        } catch (t: Throwable) {
            StorefrontLog.w(TAG, "app $appId: nativeRequestFreeLicense THREW", t)
            null
        }
        if (json == null) {
            StorefrontLog.w(
                TAG,
                "app $appId: nativeRequestFreeLicense returned NULL — no live session " +
                    "(the request never reached the CM)",
            )
            return@withContext Result.Failed(appId, notConnected)
        }

        parse(appId, json).also { result ->
            // The package→app PICS crawl. Only a grant can add anything, and it must run even when
            // `libraryUpdated` was false — the license may simply have arrived after the wait.
            if (result is Result.Granted) {
                try {
                    repo.syncLibrary()
                    StorefrontLog.i(
                        TAG,
                        "app $appId: GRANTED (libraryUpdated=${result.libraryUpdated}) — PICS library " +
                            "sync KICKED; the app becomes downloadable once the crawl resolves the " +
                            "granted package(s)",
                    )
                } catch (t: Throwable) {
                    // The license exists but nothing will turn it into a downloadable app — loud.
                    StorefrontLog.e(
                        TAG,
                        "app $appId: GRANTED but the post-grant PICS library sync FAILED TO START " +
                            "(${t.javaClass.simpleName}: ${t.message}) — the game is licensed but will " +
                            "NOT appear until a manual Refresh",
                    )
                }
            }
        }
    }

    /** Decode the engine's status envelope. Anything unparseable is a generic failure, never a crash. */
    private fun parse(appId: Int, json: String): Result {
        val o = try { JSONObject(json) } catch (t: Throwable) {
            StorefrontLog.w(TAG, "app $appId: response UNPARSEABLE (${json.length} bytes): ${t.message}")
            return Result.Failed(appId, "Steam sent back a response we couldn't read. Try again.")
        }

        val status = o.optString("status", "")
        val eresult = o.optInt("eresult", 0)
        val libraryUpdated = o.optBoolean("libraryUpdated", false)
        val grantedApps = intList(o.optJSONArray("grantedAppIds"))
        val grantedPackages = intList(o.optJSONArray("grantedPackageIds"))

        // The one line that proves what the CM actually did. None of these outcomes are
        // device-verified yet, so it is logged at info even on success.
        StorefrontLog.i(
            TAG,
            "app=$appId status=$status granted=${o.optBoolean("granted", false)} eresult=$eresult " +
                "headerEresult=${o.optInt("headerEresult", 0)} grantedAppIds=$grantedApps " +
                "grantedPackageIds=$grantedPackages libraryUpdated=$libraryUpdated",
        )

        return when (status) {
            "granted" -> Result.Granted(appId, libraryUpdated)

            // A refusal. The EResult is passed through raw by design — name it when JavaSteam's
            // enum recognises the code, and stay generic when it doesn't, rather than guessing
            // which code Steam uses for "this title isn't actually free".
            "denied" -> Result.Failed(appId, deniedMessage(eresult))

            // Transport, NOT licensing. Deliberately worded as a connection problem.
            "no_response" -> Result.Failed(
                appId,
                "Steam didn't answer in time. Check your connection and try again.",
            )
            "bad_response" -> Result.Failed(
                appId,
                "Steam's reply couldn't be read. Try again in a moment.",
            )

            "invalid_request" -> Result.Failed(
                appId,
                "Steam rejected the request for this app.",
            )

            else -> Result.Failed(appId, "Couldn't add this to your library. Try again.")
        }
    }

    /**
     * User-facing copy for a `denied`. Names the EResult when [EResult.from] recognises it so a
     * support log line is meaningful, and always keeps the sentence true for an unfamiliar code.
     */
    private fun deniedMessage(eresult: Int): String {
        val name = try { EResult.from(eresult)?.name } catch (_: Throwable) { null }
        val suffix = if (name != null && name != "Invalid") " (Steam said: $name)" else ""
        return "Steam wouldn't add this to your library$suffix. Free titles can be region-locked, " +
            "age-gated, or no longer free."
    }

    private fun intList(arr: JSONArray?): List<Int> {
        arr ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optInt(it, 0).takeIf { v -> v > 0 } }
    }
}
