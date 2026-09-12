package com.winlator.star.container;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live "Check for new builds" — build order item 8, report §6b (amended addendum).
 *
 * Boundary (invariant): observation may be autonomous; mutation never is. This class
 * observes the release feed and REPORTS — it has no File imports, no write path, no
 * catalog mutation, and no adoption/migration call. A caller wiring the report into a
 * decision row is the ONLY way anything touches user data. Structural enforcement:
 * compilation against this class cannot produce a file write.
 *
 * The HTTP fetch lives in the caller (sheet via HttpUtils); this class parses the
 * GitHub releases-list JSON leniently (feed is external — a bad entry is skipped, a
 * bad feed yields feedOk=false, never a throw) and compares against the bundled
 * catalog's newest build by publishedAt (ISO dates — lexicographic == chronological;
 * NEVER semver, per §6b.2).
 */
public final class VegasLiveCheck {
    /** One parsed release entry; malformed entries are skipped, not fatal. */
    public static final class Release {
        public final String tag;
        public final String publishedAt;   // ISO date, or null when absent
        public final boolean prerelease;
        public final boolean hasConfigAsset; // any asset name ending in ".conf"

        Release(String tag, String publishedAt, boolean prerelease, boolean hasConfigAsset) {
            this.tag = tag;
            this.publishedAt = publishedAt;
            this.prerelease = prerelease;
            this.hasConfigAsset = hasConfigAsset;
        }
    }

    public static final class Report {
        public final boolean feedOk;            // root parsed as a JSON array
        public final List<Release> releases;    // in feed order (GitHub: newest first)
        public final String installedTag;
        public final String catalogNewestTag;   // bundled catalog's newestTag()
        public final String catalogNewestAt;    // its publishedAt ("" if catalog had none)
        public final boolean comparable;        // catalogNewestAt was usable for date compare
        public final boolean installedFoundLive;
        public final int newerCount;            // live releases published AFTER catalog newest
        public final int newBuildCount;         // non-prerelease subset of newerCount
        public final List<String> newerTags;    // tags of the newer releases

        Report(boolean feedOk, List<Release> releases, String installedTag,
               String catalogNewestTag, String catalogNewestAt, boolean comparable,
               boolean installedFoundLive, int newerCount, int newBuildCount,
               List<String> newerTags) {
            this.feedOk = feedOk;
            this.releases = releases;
            this.installedTag = installedTag;
            this.catalogNewestTag = catalogNewestTag;
            this.catalogNewestAt = catalogNewestAt;
            this.comparable = comparable;
            this.installedFoundLive = installedFoundLive;
            this.newerCount = newerCount;
            this.newBuildCount = newBuildCount;
            this.newerTags = newerTags;
        }
    }

    private VegasLiveCheck() {}

    /**
     * @param releasesJson    GitHub releases-list JSON (array of release objects).
     * @param installedTag    the build currently installed/selected (may be null).
     * @param catalogNewestTag catalog's newestTag() (may be null).
     * @param catalogNewestAt  catalog's publishedAt for that tag (may be null/empty).
     */
    public static Report check(String releasesJson, String installedTag,
                               String catalogNewestTag, String catalogNewestAt) {
        List<Release> releases = new ArrayList<>();
        boolean feedOk = false;
        if (releasesJson != null) {
            try {
                Object root = new Parser(releasesJson).value();
                if (root instanceof List) {
                    feedOk = true;
                    for (Object o : (List<?>) root) {
                        Release r = parseRelease(o);
                        if (r != null) releases.add(r);
                    }
                }
            } catch (IllegalArgumentException ignored) {
                feedOk = false; // malformed feed -> report failure, never crash
            }
        }
        boolean comparable = catalogNewestAt != null && !catalogNewestAt.trim().isEmpty();
        boolean installedFoundLive = installedTag != null && containsTag(releases, installedTag);
        int newerCount = 0, newBuildCount = 0;
        List<String> newerTags = new ArrayList<>();
        if (comparable) {
            for (Release r : releases) {
                if (r.publishedAt == null) continue;
                // Catalog stores date-only (YYYY-MM-DD); GitHub feeds carry full ISO
                // timestamps. Compare on the DATE part only — a release at 2026-08-05T23:59
                // is NOT newer than a catalog entry dated 2026-08-05.
                String liveDate = r.publishedAt.length() > 10 ? r.publishedAt.substring(0, 10) : r.publishedAt;
                if (liveDate.compareTo(catalogNewestAt.trim()) > 0) {
                    newerCount++;
                    if (!r.prerelease) newBuildCount++;
                    newerTags.add(r.tag);
                }
            }
        }
        return new Report(feedOk, releases, installedTag, catalogNewestTag,
                catalogNewestAt == null ? "" : catalogNewestAt, comparable,
                installedFoundLive, newerCount, newBuildCount, newerTags);
    }

    /** Tag match with leading-v tolerance ("v2.7.3-vegas" == "2.7.3-vegas"), like the catalog. */
    static boolean containsTag(List<Release> releases, String tag) {
        String want = stripV(tag);
        for (Release r : releases)
            if (stripV(r.tag).equals(want)) return true;
        return false;
    }

    private static String stripV(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.startsWith("v")) return t.substring(1);
        return t;
    }

    private static Release parseRelease(Object o) {
        if (!(o instanceof java.util.Map)) return null;
        return parseReleaseMap((java.util.Map<?, ?>) o);
    }

    /**
     * GitHub release entries parse as JSON objects. Entries missing tag_name are
     * skipped leniently; everything else missing is tolerated (feed is external).
     */
    private static Release parseReleaseMap(java.util.Map<?, ?> m) {
        Object tag = m.get("tag_name");
        if (!(tag instanceof String) || ((String) tag).isEmpty()) return null;
        Object published = m.get("published_at");
        String publishedAt = published instanceof String ? (String) published : null;
        Object pre = m.get("prerelease");
        boolean prerelease = pre instanceof Boolean && (Boolean) pre;
        boolean hasConfigAsset = false;
        Object assets = m.get("assets");
        if (assets instanceof List) {
            for (Object a : (List<?>) assets) {
                if (a instanceof java.util.Map) {
                    Object name = ((java.util.Map<?, ?>) a).get("name");
                    if (name instanceof String && ((String) name).toLowerCase(Locale.ROOT).endsWith(".conf")) {
                        hasConfigAsset = true;
                        break;
                    }
                }
            }
        }
        return new Release((String) tag, publishedAt, prerelease, hasConfigAsset);
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) { this.s = s; }

        Object value() {
            skipWs();
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private java.util.Map<String, Object> object() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            i++; skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw new IllegalArgumentException("expected string key");
                String k = string();
                skipWs();
                if (peek() != ':') throw new IllegalArgumentException("expected ':'");
                i++;
                m.put(k, value());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new IllegalArgumentException("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; skipWs();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        private Number number() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw new IllegalArgumentException("bad fraction");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try { return num.contains(".") ? (Number) Double.valueOf(num) : (Number) Long.valueOf(num); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("bad number"); }
        }

        private String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new IllegalArgumentException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw new IllegalArgumentException("bad escape");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        default: throw new IllegalArgumentException("bad escape");
                    }
                } else sb.append(c);
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, i)) throw new IllegalArgumentException("expected " + lit);
            i += lit.length();
        }

        private char peek() { skipWs(); if (i >= s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else return;
            }
        }
    }
}
