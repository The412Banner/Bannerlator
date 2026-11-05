package com.winlator.star.core.unpack

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Unpack log for the engines ported from BFE. BFE can keep an engine's full output in a file; Bannerlator
 * only writes the job start, the "[BL]" lines (engine command line, FreeArc layers) and the result to
 * logcat (tag "UnpackLog"). Raw engine output is not kept. Never throws.
 */
object FullUnpackLog {
    private const val TAG = "UnpackLog"

    fun begin(ctx: Context, archive: String, dest: String, engine: String) {
        runCatching { Log.i(TAG, "start engine=$engine archive=$archive dest=$dest") }
    }

    fun line(text: String) {
        if (text.startsWith("[BL]")) runCatching { Log.i(TAG, text) }
    }

    /** Always null: no log file is kept. */
    fun finish(failed: Boolean, summary: String? = null): File? {
        runCatching { if (failed) Log.w(TAG, "failed: ${summary ?: ""}") else Log.i(TAG, "finished: ${summary ?: ""}") }
        return null
    }
}
