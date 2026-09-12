package com.winlator.star.store

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Speed tier for the native (Rust) Epic / GOG / Amazon download engines — the same tiers the
 * Steam depot downloader uses ([DownloadSpeedConfig]), but a single app-wide setting instead of
 * a per-download picker. It only changes the in-flight request CEILING the adaptive window may
 * ramp to (6 / 16 / 32 / 96); a slow link settles below it on its own. Read at download start,
 * so a change applies to the next download. The Java fallback loops ignore it.
 */
object StoreDownloadTier {

    /** Default-SharedPreferences key (Log Manager row "Store download speed"). */
    const val PREF_KEY = "store_dl_speed_tier"

    /** All tiers in ascending order, for the tap-to-cycle row. */
    @JvmStatic
    val ALL: IntArray = intArrayOf(
        DownloadSpeedConfig.TIER_SLOW,
        DownloadSpeedConfig.TIER_MEDIUM,
        DownloadSpeedConfig.TIER_FAST,
        DownloadSpeedConfig.TIER_BLAZING,
    )

    @JvmStatic
    fun get(ctx: Context): Int =
        try {
            val v = PreferenceManager.getDefaultSharedPreferences(ctx)
                .getInt(PREF_KEY, DownloadSpeedConfig.DEFAULT_TIER)
            if (v in ALL) v else DownloadSpeedConfig.DEFAULT_TIER
        } catch (_: Throwable) {
            DownloadSpeedConfig.DEFAULT_TIER
        }

    @JvmStatic
    fun set(ctx: Context, tier: Int) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putInt(PREF_KEY, tier).apply()
    }

    /** The tier's [DownloadSpeedConfig] — what the three store managers hand to the engine. */
    @JvmStatic
    fun config(ctx: Context): DownloadSpeedConfig = DownloadSpeedConfig(get(ctx))

    /** Next tier after [tier], wrapping to Slow after Blazing. */
    @JvmStatic
    fun next(tier: Int): Int {
        val i = ALL.indexOf(tier)
        return if (i < 0) DownloadSpeedConfig.DEFAULT_TIER else ALL[(i + 1) % ALL.size]
    }

    /** Human label with the ceiling it maps to, e.g. "Fast (32 in flight)". */
    @JvmStatic
    fun label(tier: Int): String {
        val window = DownloadSpeedConfig(tier).maxNetworkWindow
        val name = when (tier) {
            DownloadSpeedConfig.TIER_SLOW -> "Slow"
            DownloadSpeedConfig.TIER_MEDIUM -> "Medium"
            DownloadSpeedConfig.TIER_FAST -> "Fast"
            DownloadSpeedConfig.TIER_BLAZING -> "Blazing"
            else -> "Fast"
        }
        return "$name ($window in flight)"
    }
}
