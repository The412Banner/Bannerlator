package com.winlator.star.store

/**
 * UI-facing model for one Steam achievement.
 *
 * Produced by [SteamAchievementStore] from the persisted [SteamDatabase.AchievementRow] plus the
 * on-disk icon cache. The DB stores only the raw CDN icon FILENAMES; this model carries both the
 * rebuilt CDN [iconUrl]/[iconGrayUrl] (color + locked/gray) and the resolved on-disk cache paths
 * ([localIconPath]/[localIconGrayPath], null when the file isn't cached yet) so the UI can render
 * from disk with a network URL fallback.
 */
data class SteamAchievement(
    val apiName: String,
    val displayName: String,
    val description: String,
    val hidden: Boolean,
    val iconUrl: String,             // CDN url, color icon
    val iconGrayUrl: String,         // CDN url, locked/gray icon
    val localIconPath: String?,      // absolute path to cached color icon file, or null
    val localIconGrayPath: String?,  // absolute path to cached gray icon file, or null
    val unlocked: Boolean,
    val unlockTimeSec: Long,         // epoch seconds; 0 if locked
)
