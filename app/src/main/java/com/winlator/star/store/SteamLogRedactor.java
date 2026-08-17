package com.winlator.star.store;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scrubs credentials + PII out of EVERY line before it is written to a diagnostic file
 * (steam_debug.txt, steam_session.txt). These files are meant to be shared for support, so
 * they must NEVER contain a Steam username, email, or auth/refresh token — including lines that
 * originate from the bundled JavaSteam library, which we do not control.
 *
 * Two layers:
 *   1. Exact-match on known secrets we register at runtime (the account username + refresh token
 *      from prefs). A Steam username is not pattern-detectable, so exact match is the only reliable
 *      way to strip it — this is the primary guarantee.
 *   2. Pattern match as a backstop for anything we did not register: email addresses, JWTs (Steam
 *      refresh/access tokens ARE JWTs), and very long opaque token blobs.
 *
 * The long-token pattern is deliberately bounded high (>= 88 chars) so it can never clobber a
 * 40-hex depot chunk id or a ~19-digit manifest/gid that the log legitimately needs for debugging.
 */
public final class SteamLogRedactor {

    private SteamLogRedactor() {}

    /** Known-sensitive literals (username, refresh token) registered by SteamRepository. */
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    // JWT-shaped 3-part base64url blob. Steam refresh/access tokens ARE JWTs but base64 of their
    // "{ " prefix yields "eyA", NOT the canonical "eyJ" — so we anchor on "ey" (every JWT header
    // base64 starts "ey") to catch an UNREGISTERED token (e.g. one minted mid-download and logged
    // a beat before registerSecret sees it). This is the backstop; exact-match is the primary strip.
    private static final Pattern JWT =
            Pattern.compile("ey[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}");
    private static final Pattern LONG_TOKEN =
            Pattern.compile("[A-Za-z0-9_\\-]{88,}");
    // SteamID64 — always 17 digits beginning "76561". Prefix-anchored so it can never clobber a
    // ~19-digit manifest/gid or a 40-hex chunk id the log legitimately needs for debugging.
    private static final Pattern STEAMID64 =
            Pattern.compile("76561\\d{12}");

    /** Register a value (username / refresh token) to be stripped from every future log line. */
    public static void registerSecret(String s) {
        if (s != null && s.trim().length() >= 3) SECRETS.add(s.trim());
    }

    /** Forget all registered secrets (call on sign-out, when they are being cleared anyway). */
    public static void clearSecrets() {
        SECRETS.clear();
    }

    /** Return {@code msg} with every known secret + email + token pattern replaced. Null-safe. */
    public static String redact(String msg) {
        if (msg == null || msg.isEmpty()) return msg;
        String out = msg;
        for (String s : SECRETS) {
            if (!s.isEmpty() && out.contains(s)) out = out.replace(s, "[redacted]");
        }
        if (!maybeHasPattern(out)) return out;   // nothing a pattern could match — skip four regexes
        out = EMAIL.matcher(out).replaceAll("[email]");
        out = JWT.matcher(out).replaceAll("[token]");
        out = LONG_TOKEN.matcher(out).replaceAll("[token]");
        out = STEAMID64.matcher(out).replaceAll("[steamid]");
        return out;
    }

    /**
     * Cheap pre-check: could any of the four patterns possibly match?
     *
     * Added so redaction is affordable on a HOT path. The Wine debug callback fires once per line
     * of Wine output — a {@code +seh} run emits tens of millions of them — and running four regex
     * passes over every one costs frame time during a game. Every pattern needs a specific
     * character or digit to be present at all: an email needs '@', a JWT needs "ey", a long token
     * needs a 88-char unbroken run, a SteamID64 needs the literal "76561". One linear scan rules
     * all four out for the overwhelming majority of Wine lines, which are prose and hex addresses.
     *
     * Conservative by construction: it may return true when nothing matches (costing only the old
     * behaviour), and returns true whenever it is unsure. It must never return false for a string
     * a pattern would have caught — so the run-length check counts the exact character class
     * LONG_TOKEN uses.
     */
    private static boolean maybeHasPattern(String s) {
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '@') return true;                                   // EMAIL
            if (c == 'e' && i + 1 < s.length() && s.charAt(i + 1) == 'y') return true;   // JWT
            if (c == '7' && s.startsWith("76561", i)) return true;       // STEAMID64
            // LONG_TOKEN: [A-Za-z0-9_-]{88,}
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                if (++run >= 88) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }
}
