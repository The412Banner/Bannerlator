package com.winlator.star.perf

import android.app.GameManager
import android.app.GameState
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Cross-vendor "a game is running" signal via Android's [GameManager] (API 33+).
 *
 * Unlike the Samsung Galaxy path (which sets clocks directly) or the root perf tier (which writes
 * sysfs), this simply tells the Android platform we are actively gaming. That lets each OEM's own
 * game-mode booster engage on its own — OnePlus, OPPO, Red Magic, Xiaomi, Pixel, Samsung Game
 * Booster, and so on — because the app is already declared `android:appCategory="game"`.
 *
 * It needs no special permission, no proprietary SDK, and no root. It is a no-op below Android 13
 * or when the platform has no GameManager, so it is always safe to call.
 */
object GameModeSignal {
    private const val TAG = "GameModeSignal"

    /** Signal the platform that active, uninterruptible gameplay has started. */
    @JvmStatic
    fun enterGameplay(context: Context) = setState(context, playing = true)

    /** Signal that gameplay has ended, returning the app to a neutral state. */
    @JvmStatic
    fun exitGameplay(context: Context) = setState(context, playing = false)

    private fun setState(context: Context, playing: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val gameManager = context.getSystemService(GameManager::class.java) ?: return
            val mode = if (playing) GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE else GameState.MODE_NONE
            gameManager.setGameState(GameState(false, mode))
            Log.d(TAG, "GameState set (playing=$playing)")
        } catch (e: Throwable) {
            // Never let a performance hint affect a game launch/teardown.
            Log.w(TAG, "setGameState failed: ${e.message}")
        }
    }
}
