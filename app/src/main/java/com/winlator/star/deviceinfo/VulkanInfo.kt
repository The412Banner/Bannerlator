// Ported from DroidDeck (Droid-Deck/DroidDeck app/src/main/java/com/droiddeck/launcher/gpu, GPL-3.0): device details by
// The412Banner (#295), extended by Kurt Himebauch, Danbro007, ASEbrahim, openMali, MaxsTechReview.
package com.winlator.star.deviceinfo

import android.util.Log

object VulkanInfo {
    @Volatile private var cached: Map<String, String>? = null

    @JvmStatic private external fun nativeQuery(): String

    @Synchronized
    fun query(): Map<String, String> {
        cached?.let { return it }
        val text = try {
            System.loadLibrary("deviceinfo")
            nativeQuery()
        } catch (t: Throwable) {
            Log.w("VulkanInfo", "query failed", t)
            "error=" + (t.message ?: t.javaClass.simpleName)
        }
        val map = text.lineSequence().mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) null else line.substring(0, eq) to line.substring(eq + 1)
        }.toMap(LinkedHashMap())
        cached = map
        return map
    }

    fun cachedOrNull(): Map<String, String>? = cached
}
