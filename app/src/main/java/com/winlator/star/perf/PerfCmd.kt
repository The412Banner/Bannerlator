package com.winlator.star.perf

/**
 * Pure, side-effect-free shell command / value builders for the root perf tier. Kept isolated from
 * [RootManager] so they can be unit-tested on the JVM without a device or a live su shell.
 *
 * Every write the app ever makes to a privileged sysfs node is built here, so the exact bytes that
 * hit a node — and the exact command used to read its original value back for the revert registry —
 * are covered by [com.winlator.star.perf.PerfBuildersTest].
 */
object PerfCmd {

    /** Single-quote a value for safe use inside an `sh -c` command line. */
    fun shellQuote(value: String): String {
        // Wrap in single quotes; escape any embedded single quote as '\'' (close, escaped-quote, reopen).
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /**
     * Command that writes [value] VERBATIM to [path] (no trailing newline). `printf %s` is used
     * rather than `echo` precisely because several sysfs nodes (governors, force_clk_on) reject a
     * trailing newline, and because the revert path must reproduce the captured original byte-for-byte.
     */
    fun writeCmd(path: String, value: String): String =
        "printf %s ${shellQuote(value)} > ${shellQuote(path)}"

    /** Command that reads the current contents of [path] with no added formatting. */
    fun readCmd(path: String): String = "cat ${shellQuote(path)}"

    /**
     * Normalize a value read back from sysfs for storage in the revert snapshot. Sysfs reads usually
     * carry a trailing newline; the stored original is the trimmed content and [writeCmd] re-emits it
     * without a newline, so a capture→revert round-trip is stable.
     */
    fun normalizeRead(raw: String): String = raw.trim()
}
