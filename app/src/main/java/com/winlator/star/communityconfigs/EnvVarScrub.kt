package com.winlator.star.communityconfigs

/**
 * Strips credential / identity environment variables out of a community-config env-var list. A shared
 * config promises "never your store logins", yet a real-Steam (VAC) launch injects {@code WN_STEAM_TOKEN}
 * (a live JWT), {@code WN_STEAM_USERNAME} (the account email) and {@code WN_STEAM_STEAMID} — so any of
 * those that reach the env list must never leave the device, be displayed, or be written into a shortcut.
 *
 * This is an ENV-VAR-LIST FILTER, not a text redactor. The env list is the space-delimited
 * {@code NAME=VALUE} form {@link com.winlator.star.core.EnvVars} parses (split on space). [scrub] parses
 * it, DROPS WHOLE any pair that is sensitive by NAME or by VALUE SHAPE, and re-joins what survives in
 * order — a leaked {@code WN_STEAM_TOKEN} is removed name-and-value, never masked in place. Ordinary
 * tuning vars (DXVK_HUD, ZINK_*, MESA_*, WINEESYNC, TU_DEBUG, WRAPPER_*, PROTON_*, WINEDLLOVERRIDES, …)
 * pass through untouched.
 *
 * The value-shape patterns intentionally mirror {@link com.winlator.star.store.SteamLogRedactor} (JWT
 * "ey…", email, SteamID64 "76561…") for consistency — but that class scrubs free-text log LINES, whereas
 * a config carries a discrete list, so the logic lives here as a list filter rather than reusing its
 * substring-masking passes.
 *
 * Pure Kotlin (no Android types) so the pure-core {@link ConfigExporter} can call it and it stays
 * JVM-testable. Applied at all three community-config boundaries (defense in depth — already-leaked
 * configs exist in the wild): export/upload ({@link ConfigExporter#export}), the details view
 * ({@code configSummaryLines}), and apply ({@link CommunityConfigApply}).
 */
object EnvVarScrub {

    // ── Drop by NAME (case-insensitive) ─────────────────────────────────────────────────────────────
    // Explicitly named credential/identity vars. WN_STEAM_USERNAME / WN_STEAM_STEAMID carry no keyword a
    // fragment would catch, so the WN_STEAM_ prefix below is what removes them; the rest are also covered
    // by NAME_FRAGMENTS but are listed here so a later edit to the fragments can't silently un-drop them.
    private val EXACT_NAMES: Set<String> = setOf(
        "WN_STEAM_TOKEN", "WN_STEAM_USERNAME", "WN_STEAM_STEAMID",
        "SB_REFRESH_TOKEN", "STEAM_REFRESH_TOKEN",
    )

    /** Any var whose name STARTS WITH one of these is a credential/identity var and is dropped. */
    private val NAME_PREFIXES: List<String> = listOf("WN_STEAM_")

    /** Any var whose name CONTAINS one of these fragments is dropped (catches vars we didn't name). */
    private val NAME_FRAGMENTS: List<String> = listOf(
        "TOKEN", "SECRET", "PASSWORD", "PASSWD", "REFRESH", "SESSION", "CREDENTIAL", "COOKIE", "AUTH",
    )

    // ── Drop by VALUE SHAPE (safety net for vars we didn't name) ────────────────────────────────────
    /** 3-part base64url JWT ("eyJ…"); Steam refresh/access tokens are JWTs. Mirrors SteamLogRedactor. */
    private val JWT = Regex("ey[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}")
    /** An email address (a value like an account login). */
    private val EMAIL = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    /** A bare 17-digit SteamID64 (always begins "76561"). */
    private val STEAMID64 = Regex("^76561\\d{12}$")
    /** A long opaque secret — the whole value is one >=40-char base64/hex run with no spaces. */
    private val LONG_OPAQUE = Regex("^[A-Za-z0-9+/=_\\-]{40,}$")

    /**
     * Return [raw] (a space-delimited {@code NAME=VALUE} env list) with every credential/identity var
     * removed, order preserved. Null/blank in → empty string out. Tokens that aren't {@code NAME=VALUE}
     * (no '=', or a leading '=') carry no secret and are kept verbatim — matching how {@code EnvVars}
     * itself would treat them.
     */
    fun scrub(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        // Split on space exactly as EnvVars.putAll does, dropping the empties a double-space would make.
        val kept = raw.trim().split(" ").filter { it.isNotBlank() }.filter { token ->
            val eq = token.indexOf('=')
            if (eq <= 0) return@filter true          // not NAME=VALUE — no credential to drop
            !isSensitive(token.substring(0, eq), token.substring(eq + 1))
        }
        return kept.joinToString(" ")
    }

    /** True when a {@code NAME=VALUE} pair is a credential/identity var (by name, then by value shape). */
    fun isSensitive(name: String, value: String): Boolean {
        val n = name.trim().uppercase()
        if (n.isEmpty()) return false
        if (n in EXACT_NAMES) return true
        if (NAME_PREFIXES.any { n.startsWith(it) }) return true
        if (NAME_FRAGMENTS.any { n.contains(it) }) return true

        val v = value.trim()
        if (v.isEmpty()) return false
        // A JWT value, incl. the 2-dot "ey…" shape the 3-part regex would miss on an unsigned token.
        if (JWT.containsMatchIn(v)) return true
        if (v.startsWith("ey") && v.count { it == '.' } >= 2) return true
        if (EMAIL.containsMatchIn(v)) return true
        if (STEAMID64.matches(v)) return true
        if (LONG_OPAQUE.matches(v)) return true
        return false
    }
}
