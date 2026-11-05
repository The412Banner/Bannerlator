package com.winlator.star.widget.gpu

/** Readable text for the negative errno codes the gpuclock library returns. */
internal object KgslErrors {
    fun describe(code: Int): String {
        if (code == 0) return "no error code"
        if (code == KgslClock.RANGE_CHECK_FAILED) return "sample out of range"
        val name = runCatching { android.system.OsConstants.errnoName(-code) }.getOrNull()
        return if (name != null) "$name ($code)" else "error $code"
    }
}
