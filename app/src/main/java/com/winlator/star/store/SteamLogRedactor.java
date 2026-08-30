package com.winlator.star.store;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Matcher;
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

    // ── SteamLite (genuine Valve client) log patterns ────────────────────────────────────────────
    // The base redact() above was written for OUR files + the Wine callback. The real Steam client
    // logs carry secrets in shapes it does NOT catch — see redactSteamClientLine() for the rationale.

    /** Machine-auth / other GUIDs (8-4-4-4-12 hex). */
    private static final Pattern GUID = Pattern.compile(
            "\\b[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}\\b");
    /** "Using JWT 25484942796017334" — the client logs its session token as a bare NUMBER, not a
     *  base64 JWT, so the JWT pattern above misses it. Keep the label, strip the digits. Requires
     *  &ge;8 digits so an English word after "JWT" (e.g. "JWT expired") is never touched. */
    private static final Pattern JWT_LABELLED = Pattern.compile("(?i)(\\bJWT[\\s=:]+)(\\d{8,})");
    /** key=value / key: value secrets. Bare {@code key} is deliberately EXCLUDED (so "key=english"
     *  survives); a real WebAPI key is caught by {@link #WEBAPI_KEY} instead. Value class excludes
     *  '<' '>' so a second pass over already-redacted "<redacted:…>" is a no-op (idempotent). */
    private static final Pattern SECRET_KV = Pattern.compile(
            "(?i)\\b(access[_-]?token|refresh[_-]?token|auth[_-]?token|authtoken|token|authcode|"
            + "auth[_-]?ticket|ticket|sessionid|steamloginsecure|webapikey|api[_-]?key|"
            + "machine[_-]?auth(?:[_-]?token)?|machineauth|password|passwd|pwd|secret)"
            + "(\\s*[=:]\\s*|=)([^\\s\"'<>&;,]{4,})");
    /** Steam Guard code — only when the surrounding text names it, so a random 5-char token is safe. */
    private static final Pattern GUARD_CODE = Pattern.compile(
            "(?i)((?:steam\\s*)?guard\\s*code[\\s:=]*|two[\\s-]?factor[\\s:=]*|2fa[\\s:=]*)([A-Za-z0-9]{5})");
    /** A standalone 32-hex WebAPI key. {@code \b…{32}\b} matches EXACTLY 32, so a 40-hex depot chunk
     *  id (which the base redactor deliberately preserves) is left alone. */
    private static final Pattern WEBAPI_KEY = Pattern.compile("\\b[0-9A-Fa-f]{32}\\b");
    /** A 3-part base64url JWT logged verbatim (refresh/access token). Same shape as {@link #JWT}. */
    private static final Pattern JWT_BASE64 =
            Pattern.compile("ey[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}\\.[A-Za-z0-9_\\-]{6,}");
    /** SteamID3 handle {@code [U:1:2932373]} — we MASK (keep last 4) rather than delete, so a reader
     *  can still correlate lines to one account without the value identifying it. */
    private static final Pattern STEAMID3 = Pattern.compile("\\[U:1:(\\d+)\\]");
    /** Belt-and-suspenders residual sweep: a sensitive keyword followed (any separator) by a long
     *  opaque run the rules above might have missed. &ge;12 chars so it can't eat an ordinary word. */
    private static final Pattern RESIDUAL = Pattern.compile(
            "(?i)\\b(jwt|token|ticket|sessionid|steamloginsecure|machineauth)\\b[\\s=:]*"
            + "([A-Za-z0-9+/=_\\-]{12,})");

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
     * A HARDER scrub than {@link #redact}, for the combined {@code steamlite.txt} bundle the user is
     * invited to share (see {@link com.winlator.star.core.SteamLiteLogCollector}).
     *
     * {@link #redact} handles our own files + the Wine callback: the registered account name + refresh
     * token, emails, 3-part JWTs, 88+ char blobs, and full SteamID64s. The genuine Valve client logs
     * carry secrets in shapes that pass does NOT catch — a bare-numeric "Using JWT …" token,
     * {@code token=}/{@code steamLoginSecure=}/{@code sessionid=} key-values, machine-auth GUIDs,
     * 32-hex WebAPI keys, Steam Guard codes — and it over-strips the one identifier we want to keep
     * PARTIALLY (a SteamID lets a reader correlate lines, so we mask it rather than delete it).
     *
     * Order is load-bearing: the SteamID masking runs BEFORE the base pass so the base SteamID64 rule
     * (which would blank it entirely) no longer sees a 17-digit run, and the base pass runs LAST as a
     * backstop for the account name / refresh token / email / any residual base64 token.
     *
     * KEEP (never stripped — this is what makes the log debuggable): EResult codes, CM host names +
     * IPs, ping/timings, JWT expiry DATES, appIDs, PIDs, connection-state transitions, file paths.
     */
    public static String redactSteamClientLine(String line) {
        if (line == null || line.isEmpty()) return line;
        try {
            String out = line;
            out = GUID.matcher(out).replaceAll("<redacted:guid>");
            out = JWT_LABELLED.matcher(out).replaceAll("$1<redacted:jwt>");
            out = SECRET_KV.matcher(out).replaceAll("$1$2<redacted:token>");
            out = GUARD_CODE.matcher(out).replaceAll("$1<redacted:code>");
            out = WEBAPI_KEY.matcher(out).replaceAll("<redacted:key>");
            out = JWT_BASE64.matcher(out).replaceAll("<redacted:jwt>");
            out = maskSteamId64(out);          // keep first 4 + last 4, mask the middle
            out = maskSteamId3(out);           // [U:1:####last4]
            out = redact(out);                 // base backstop: account name, token, email, residuals
            return out;
        } catch (Throwable t) {
            // A scrub that throws must NEVER emit the raw line to a file destined for sharing.
            return "[line withheld: SteamLite redaction failed]";
        }
    }

    /**
     * Post-assembly self-audit over the FINISHED {@code steamlite.txt}. Belt-and-suspenders: re-runs
     * {@link #redactSteamClientLine} on every line (idempotent — a {@code <redacted:…>} placeholder
     * can't re-trigger a rule), then a final residual sweep for a sensitive keyword trailed by a long
     * opaque run that a section header we built ourselves might have carried through un-scrubbed.
     */
    public static String auditSteamClientText(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder(text.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            sb.append(redactSteamClientLine(lines[i]));
        }
        return RESIDUAL.matcher(sb.toString()).replaceAll("$1 <redacted:token>");
    }

    /** SteamID64 (17 digits, "76561…") → first 4 + masked middle + last 4 (correlation, not identity). */
    private static String maskSteamId64(String s) {
        Matcher m = STEAMID64.matcher(s);
        if (!m.find()) return s;
        StringBuilder out = new StringBuilder();
        m.reset();
        while (m.find()) {
            String id = m.group();
            String masked = id.substring(0, 4) + repeat('#', id.length() - 8) + id.substring(id.length() - 4);
            m.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** SteamID3 handle {@code [U:1:2932373]} → {@code [U:1:###2373]} (keep last 4 of the account id). */
    private static String maskSteamId3(String s) {
        Matcher m = STEAMID3.matcher(s);
        if (!m.find()) return s;
        StringBuilder out = new StringBuilder();
        m.reset();
        while (m.find()) {
            String acct = m.group(1);
            String masked = acct.length() > 4
                    ? repeat('#', acct.length() - 4) + acct.substring(acct.length() - 4)
                    : acct;   // very short account ids carry little entropy — leave readable
            m.appendReplacement(out, Matcher.quoteReplacement("[U:1:" + masked + "]"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String repeat(char c, int n) {
        if (n <= 0) return "";
        char[] a = new char[n];
        java.util.Arrays.fill(a, c);
        return new String(a);
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
