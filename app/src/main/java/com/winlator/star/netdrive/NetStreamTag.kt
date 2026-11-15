package com.winlator.star.netdrive

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.io.File

/**
 * The "Loading from NAS" tag in the top-right corner of a game's screen: shown only while a streamed game waits for
 * files from its network drive (at launch, or when it opens a file it hasn't read before), with how far along it is
 * and how fast it comes ("Loading from NAS 34% · 28 MB/s"). libnetfs.so writes the progress to [NetStream.statusFile]
 * ("<done> <total> <MB/s> <file>"); this reads it four times a second. A line older than 1.5 s, or done, hides it.
 */
object NetStreamTag {
    private const val POLL_MS = 250L
    private const val STALE_MS = 1_500L

    /** Adds the (hidden) tag over the game's screen; costs one file-date check per poll when nothing streams. */
    @JvmStatic
    fun attach(activity: Activity, root: FrameLayout) {
        val status = NetStream.statusFile(activity)
        val dp = activity.resources.displayMetrics.density
        val tag = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 8 * dp
                setColor(0xB3000000.toInt())
            }
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(tag, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = (10 * dp).toInt()
            rightMargin = (10 * dp).toInt()
        })
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                update(tag, status)
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.post(tick)
    }

    private fun update(tag: TextView, status: File) {
        val modified = status.lastModified()
        if (modified == 0L || System.currentTimeMillis() - modified > STALE_MS) { hide(tag); return }
        val parts = runCatching { status.readText().trim().split(' ', limit = 4) }.getOrNull()
        val done = parts?.getOrNull(0)?.toLongOrNull() ?: -1
        val total = parts?.getOrNull(1)?.toLongOrNull() ?: -1
        val mbps = parts?.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        if (done < 0 || total <= 0 || done >= total) { hide(tag); return }
        val pct = (done * 100 / total).toInt().coerceIn(0, 99)
        tag.text = if (mbps >= 0.1) "Loading from NAS $pct% · ${"%.0f".format(mbps)} MB/s" else "Loading from NAS $pct%"
        if (tag.visibility != View.VISIBLE) {
            tag.visibility = View.VISIBLE
            tag.bringToFront()
        }
    }

    private fun hide(tag: TextView) {
        if (tag.visibility != View.GONE) tag.visibility = View.GONE
    }
}
