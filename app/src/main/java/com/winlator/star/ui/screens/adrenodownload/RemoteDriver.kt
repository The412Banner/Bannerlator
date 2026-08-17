package com.winlator.star.ui.screens.adrenodownload

import android.content.Context

/**
 * One feed within a driver source. A source can chain several feeds; [RemoteDriverRepository.fetchEntries]
 * fetches them IN ORDER and concatenates the results (feed 1 on top, later feeds below), deduped by URL.
 */
sealed class DriverFeed {
    /** The existing format: a JSON array of `{verName, remoteUrl}` at [url]. */
    data class Json(val url: String) : DriverFeed()

    /** A GitHub repo whose releases' `.zip` assets are each a driver. */
    data class GithubReleases(val owner: String, val repo: String) : DriverFeed()
}

data class RemoteDriverSource(
    val name: String,
    val feeds: List<DriverFeed>,
    val builtIn: Boolean = false,
) {
    companion object {
        /** Convenience for the common single-JSON-feed source. */
        fun json(name: String, url: String, builtIn: Boolean = false) =
            RemoteDriverSource(name, listOf(DriverFeed.Json(url)), builtIn)
    }
}

data class RemoteDriverEntry(
    val source: String,
    val displayName: String,
    val downloadUrl: String,
)

object DriverSources {
    private const val NIGHTLIES = "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main"

    /** The bundled sources, in display order. All [builtIn]. */
    val BUILT_IN: List<RemoteDriverSource> = listOf(
        RemoteDriverSource.json("Kimchi", "$NIGHTLIES/kimchi_drivers.json", builtIn = true),
        // Lists Maxes' curated MTR json first, then every WinNative-Emu/Drivers release .zip below.
        RemoteDriverSource(
            name = "MTR / WinNative Drivers",
            feeds = listOf(
                DriverFeed.Json("$NIGHTLIES/mtr_drivers.json"),
                DriverFeed.GithubReleases("WinNative-Emu", "Drivers"),
            ),
            builtIn = true,
        ),
        RemoteDriverSource.json("Banners-Turnip", "$NIGHTLIES/banners-turnip_drivers.json", builtIn = true),
        RemoteDriverSource.json("StevenMXZ", "$NIGHTLIES/stevenmxz_drivers.json", builtIn = true),
        RemoteDriverSource.json("whitebelyash", "$NIGHTLIES/white_drivers.json", builtIn = true),
    )

    /**
     * The live source list the UI + smart installer use: built-ins whose name the user hasn't disabled
     * (in original order), followed by the user's custom sources. Backed by [DriverSourceStore].
     */
    fun allSources(context: Context): List<RemoteDriverSource> {
        val store = DriverSourceStore(context)
        val disabled = store.disabledBuiltIns()
        return BUILT_IN.filter { it.name !in disabled } + store.customSources()
    }
}
