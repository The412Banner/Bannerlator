package com.winlator.star.contentdialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * VEGAS key catalog — the classifier's ground-truth data (design: config-discrepancy
 * report §6a/6b, "Catalog Watcher" addendum, amended 2026-08-16).
 *
 * Payload: vegas_key_catalog.json (bundled asset, later updated at build-time via
 * assistant-side maintenance — never written by the app itself).
 *
 *   {
 *     "schema": 1,
 *     "orderBy": "publishedAt",
 *     "generatedAt": "YYYY-MM-DD",
 *     "builds": [ { "tag", "publishedAt", "prerelease", "state", "assetName"?, "keys": [...], "note"? } ],
 *     "upstream": { "source", "fetchedAt", "keys": [...] }
 *   }
 *
 * Build state machine (three states, never conflated):
 *   known                — config asset existed and parsed; keys are its documented set.
 *                          Zero active lines is a LEGITIMATE state (all v2.x sheets).
 *   no-config-asset      — release exists but ships no .conf (2.7.3-stable, 1.11.1).
 *   extraction-failed    — asset existed but could not be parsed (manual review bucket).
 *
 * Ordering rule: coverage ("catalog behind build") keys off the INSTALLED TAG being
 * present in the catalog — never off version-number comparison, because release order
 * is not version-monotonic (v2.4.1-3137660 published after v2.7.3).
 *
 * Invariant (boundary from the addendum): this class is READ-ONLY. It has no method
 * that writes a user's active.conf, runs a migration, or makes an ownership decision.
 * A future watcher calling adoption/migration code is a violation, not an optimization.
 */
public final class VegasKeyCatalog {
    public enum Bucket { IN_BUILD, OTHER_BUILD, UPSTREAM, NOWHERE }
    public enum BuildState { KNOWN, NO_CONFIG_ASSET, EXTRACTION_FAILED }

    /**
     * Documented key-schema families (report §2): Sarek line uses the dxvk.vegas.*
     * namespace with per-game sections; Star Engine line uses vegas.* /
     * dxvk.enableStarProfile. Cross-line keys are not merely "unknown" — they belong
     * to a schema the installed build does not implement (§6a.6: block-with-explanation).
     */
    public enum Schema { SAREK, STAR }

    private static final String SAREK_PREFIX = "dxvk.vegas.";
    private static final String STAR_PREFIX = "vegas.";
    private static final String STAR_PROFILE_KEY = "dxvk.enableStarProfile";

    private static final Set<String> TOP_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "schema", "orderBy", "generatedAt", "builds", "upstream"));
    private static final Set<String> BUILD_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "tag", "publishedAt", "prerelease", "state", "assetName", "keys", "note"));
    private static final Set<String> UPSTREAM_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "source", "fetchedAt", "keys"));

    private final int schema;
    private final String orderBy;
    private final String generatedAt;
    private final List<Build> builds;              // catalog order = file order
    private final String upstreamSource;
    private final String upstreamFetchedAt;
    private final Set<String> upstreamKeys;

    private static final class Build {
        final String tag;
        final String publishedAt;
        final boolean prerelease;
        final BuildState state;
        final String assetName;
        final Set<String> keys;
        final String note;
        Build(String tag, String publishedAt, boolean prerelease, BuildState state,
              String assetName, Set<String> keys, String note) {
            this.tag = tag; this.publishedAt = publishedAt; this.prerelease = prerelease;
            this.state = state; this.assetName = assetName; this.keys = keys; this.note = note;
        }
    }

    public VegasKeyCatalog(String json) {
        Map<String, Object> root = parseStrictObject(json);
        validateTopLevel(root);
        this.schema = asInt(root, "schema");
        if (schema != 1) throw reject("unsupported catalog schema: " + schema);
        this.orderBy = asNonEmptyString(root, "orderBy", "orderBy");
        this.generatedAt = asNonEmptyString(root, "generatedAt", "generatedAt");

        this.builds = new ArrayList<>();
        List<Object> buildsRaw = asList(root, "builds", true);
        Set<String> seenTags = new LinkedHashSet<>();
        for (Object o : buildsRaw) {
            if (!(o instanceof Map)) throw reject("build entry must be an object");
            Map<String, Object> e = uncheckedMap(o);
            for (String f : e.keySet())
                if (!BUILD_FIELDS.contains(f)) throw reject("unknown field in build entry: " + f);
            String tag = asNonEmptyString(e, "tag", "build entry");
            if (!seenTags.add(tag)) throw reject("duplicate build tag: " + tag);
            String publishedAt = asNonEmptyString(e, "publishedAt", "build " + tag);
            boolean prerelease = asBool(e, "prerelease");
            String stateStr = asNonEmptyString(e, "state", "build " + tag);
            BuildState state;
            try {
                // JSON uses human-facing vocab ("no-config-asset"); enum uses SCREAMING_SNAKE.
                // Dash->underscore normalization only; anything else is rejected.
                state = BuildState.valueOf(stateStr.toUpperCase(Locale.ROOT).replace('-', '_'));
            }
            catch (IllegalArgumentException ex) { throw reject("build " + tag + ": bad state '" + stateStr + "'"); }
            String assetName = e.containsKey("assetName") ? asString(e, "assetName") : null;
            String note = e.containsKey("note") ? asString(e, "note") : null;
            Set<String> keys = new LinkedHashSet<>(asStringList(e, "keys", true));
            builds.add(new Build(tag, publishedAt, prerelease, state, assetName, keys, note));
        }

        Object upRaw = root.get("upstream");
        if (!(upRaw instanceof Map)) throw reject("upstream must be an object");
        Map<String, Object> up = uncheckedMap(upRaw);
        for (String f : up.keySet())
            if (!UPSTREAM_FIELDS.contains(f)) throw reject("unknown field in upstream: " + f);
        this.upstreamSource = asNonEmptyString(up, "source", "upstream");
        this.upstreamFetchedAt = asNonEmptyString(up, "fetchedAt", "upstream");
        this.upstreamKeys = new LinkedHashSet<>(asStringList(up, "keys", false));
    }

    /* ===================== public read API ===================== */

    /** Coverage rule: does the catalog know the installed build? Tag presence, never version math. */
    public boolean isCovered(String installedTag) {
        return indexOf(tagOf(installedTag)) >= 0;
    }

    /**
     * Four-bucket classifier. Resolution order is fixed: per-build set of the INSTALLED
     * tag first (sets legitimately overlap upstream, e.g. dxgi.maxFrameRate in 2.7.3 AND
     * upstream), then any other VEGAS build/line, then upstream, then nowhere.
     * An unknown installed tag yields OTHER_BUILD/NOWHERE results that must be annotated
     * "catalog behind build — unverified" by the caller (see isCovered).
     */
    public Bucket classify(String key, String installedTag) {
        if (key == null) return Bucket.NOWHERE;
        int idx = indexOf(tagOf(installedTag));
        if (idx >= 0 && builds.get(idx).keys.contains(key)) return Bucket.IN_BUILD;
        for (Build b : builds)
            if (b.keys.contains(key)) return Bucket.OTHER_BUILD;
        if (upstreamKeys.contains(key)) return Bucket.UPSTREAM;
        return Bucket.NOWHERE;
    }

    public BuildState stateOf(String installedTag) {
        int idx = indexOf(tagOf(installedTag));
        return idx >= 0 ? builds.get(idx).state : null;
    }

    public String assetNameOf(String installedTag) {
        int idx = indexOf(tagOf(installedTag));
        return idx >= 0 ? builds.get(idx).assetName : null;
    }

    public String noteOf(String installedTag) {
        int idx = indexOf(tagOf(installedTag));
        return idx >= 0 ? builds.get(idx).note : null;
    }

    public boolean hasTag(String tag) { return indexOf(tagOf(tag)) >= 0; }

    /** publishedAt (ISO date) of a known tag; "" for unknown tags. */
    public String publishedAtOf(String tag) {
        return publishedAtOf0(tag);
    }

    /** Newest known tag by publishedAt string order (ISO dates — lexicographic == chronological). */
    public String newestTag() {
        String best = null;
        for (Build b : builds)
            if (best == null || b.publishedAt.compareTo(publishedAtOf0(best)) > 0) best = b.tag;
        return best;
    }

    public String generatedAt() { return generatedAt; }
    public String upstreamSource() { return upstreamSource; }
    public String upstreamFetchedAt() { return upstreamFetchedAt; }
    public int buildCount() { return builds.size(); }
    public int upstreamKeyCount() { return upstreamKeys.size(); }

    // Live tail — tags seen via GitHub feed that are newer than bundled catalog (heals "catalog behind").
    private static final int MAX_CATALOG_TAIL = 200;
    private final Set<String> tailTags = new LinkedHashSet<>();
    public int mergeTailTags(java.util.Collection<String> tags) {
        int added = 0;
        if (tags == null) return 0;
        for (String t : tags) {
            if (t == null) continue;
            String v = t.trim();
            if (v.isEmpty()) continue;
            if (hasTag(v) || tailTags.contains(v)) continue;
            String stripped = v.startsWith("v") ? v.substring(1) : v;
            String prefixed = v.startsWith("v") ? v : "v" + v;
            if (hasTag(stripped) || hasTag(prefixed) || tailTags.contains(stripped) || tailTags.contains(prefixed)) continue;
            tailTags.add(v);
            added++;
        }
        return added;
    }
    /** Trim a tag set to MAX_CATALOG_TAIL entries, keeping the newest (last) ones. */
    public static void capToMax(Set<String> set) {
        if (set.size() > MAX_CATALOG_TAIL) {
            set.removeAll(new ArrayList<>(set).subList(0, set.size() - MAX_CATALOG_TAIL));
        }
    }
    /** Covered by bundled catalog OR by the live tail (tags the user has seen).
     *  Intentionally relaxed: tail-healed tags are treated as covered because the
     *  user has observed this live release, so its keys should not be flagged
     *  "catalog behind build — unverified". */
    public boolean isCoveredOrTail(String tag) {
        if (tag == null) return false;
        if (isCovered(tag)) return true;
        String v = tag.trim();
        if (tailTags.contains(v)) return true;
        if (v.startsWith("v") && tailTags.contains(v.substring(1))) return true;
        if (!v.startsWith("v") && tailTags.contains("v" + v)) return true;
        return false;
    }
    public java.util.Set<String> tailTags() { return new LinkedHashSet<>(tailTags); }
    /** Union of every key documented across all builds and upstream (deduped; build order then upstream). */
    public List<String> allKeys() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Build b : builds) out.addAll(b.keys);
        out.addAll(upstreamKeys);
        return new ArrayList<>(out);
    }
    public List<String> knownTags() {
        List<String> out = new ArrayList<>();
        for (Build b : builds) out.add(b.tag);
        return out;
    }

    /* ===================== schema family (§6a.6) ===================== */

    /** Family of a single key; null = schema-neutral (e.g. upstream dxgi.*, dxvk.*). */
    public Schema familyOf(String key) {
        if (key == null) return null;
        if (key.startsWith(SAREK_PREFIX)) return Schema.SAREK;
        if (key.startsWith(STAR_PREFIX) || STAR_PROFILE_KEY.equals(key)) return Schema.STAR;
        return null;
    }

    /**
     * Schema family of an installed build, derived from its DOCUMENTED key set
     * (majority of family-classified keys; a tie is ambiguous -> null). Null also for
     * unknown tags, key-less builds (no-config-asset) and schema-neutral sets.
     * Blocking only fires on an UNambiguous installed schema — never by guessing.
     */
    public Schema schemaFamilyOf(String installedTag) {
        int idx = indexOf(tagOf(installedTag));
        if (idx < 0) return null;
        int sarek = 0, star = 0;
        for (String k : builds.get(idx).keys) {
            Schema f = familyOf(k);
            if (f == Schema.SAREK) sarek++;
            else if (f == Schema.STAR) star++;
        }
        if (sarek == star) return null;                       // zero-classified keys: 0 == 0 -> null
        return sarek > star ? Schema.SAREK : Schema.STAR;
    }

    /** True when the key's family is known AND the installed build's family is the OTHER one. */
    public boolean isWrongFamily(String key, String installedTag) {
        Schema keyFam = familyOf(key);
        Schema instFam = schemaFamilyOf(installedTag);
        return keyFam != null && instFam != null && keyFam != instFam;
    }

    /* ===================== strict validation ===================== */

    private void validateTopLevel(Map<String, Object> root) {
        for (String f : root.keySet())
            if (!TOP_FIELDS.contains(f)) throw reject("unknown top-level field: " + f);
        if (root.get("schema") == null) throw reject("missing field: schema");
        if (root.get("orderBy") == null) throw reject("missing field: orderBy");
        if (root.get("generatedAt") == null) throw reject("missing field: generatedAt");
        if (root.get("builds") == null) throw reject("missing field: builds");
        if (root.get("upstream") == null) throw reject("missing field: upstream");
    }

    /* ===================== helpers ===================== */

    private int indexOf(String tag) {
        if (tag == null) return -1;
        for (int i = 0; i < builds.size(); i++)
            if (builds.get(i).tag.equals(tag)) return i;
        return -1;
    }

    private String publishedAtOf0(String tag) {
        int idx = indexOf(tag);
        return idx >= 0 ? builds.get(idx).publishedAt : "";
    }

    /** Pass-through; callers normalize variants explicitly (see mergeTailTags). */
    private static String tagOf(String tag) {
        if (tag == null) return null;
        return tag;
    }

    /* ===================== mini JSON (standalone, harness-friendly) ===================== */

    static Map<String, Object> parseStrictObject(String json) {
        MiniJson parser = new MiniJson(json);
        Object root = parser.parse();
        if (!(root instanceof Map)) throw reject("root must be an object");
        return uncheckedMap(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedMap(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Object> uncheckedList(Object o) { return (List<Object>) o; }

    private static List<Object> asList(Map<String, Object> m, String field, boolean allowEmpty) {
        Object v = m.get(field);
        if (v == null) {
            if (allowEmpty) return Arrays.asList();
            throw reject("missing array field: " + field);
        }
        if (!(v instanceof List)) throw reject("field must be an array: " + field);
        List<Object> l = uncheckedList(v);
        if (!allowEmpty && l.isEmpty()) throw reject("array must not be empty: " + field);
        return l;
    }

    private static List<String> asStringList(Map<String, Object> m, String field, boolean allowEmpty) {
        List<Object> raw = asList(m, field, allowEmpty);
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : raw) {
            String k = coerceString(o, field + " entry");
            if (!seen.add(k)) throw reject("duplicate " + field + " entry: " + k);
            out.add(k);
        }
        return out;
    }

    private static String asString(Map<String, Object> m, String field) {
        return coerceString(m.get(field), field);
    }

    private static String asNonEmptyString(Map<String, Object> m, String field, String context) {
        Object v = m.get(field);
        if (v == null) throw reject(context + ": missing field: " + field);
        String s = coerceString(v, context);
        if (s.isEmpty()) throw reject(context + ": " + field + " must not be empty");
        return s;
    }

    private static int asInt(Map<String, Object> m, String field) {
        Object v = m.get(field);
        if (!(v instanceof Number)) throw reject("field must be a number: " + field);
        return ((Number) v).intValue();
    }

    private static boolean asBool(Map<String, Object> m, String field) {
        Object v = m.get(field);
        if (v == null) return false;                       // optional, defaults false
        if (!(v instanceof Boolean)) throw reject("field must be boolean: " + field);
        return (Boolean) v;
    }

    private static String coerceString(Object o, String context) {
        if (!(o instanceof String)) throw reject(context + ": expected string, got " + typeOf(o));
        return (String) o;
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static IllegalArgumentException reject(String msg) {
        return new IllegalArgumentException("vegas_key_catalog: " + msg);
    }

    private static final class MiniJson {
        private final String s;
        private int i;

        MiniJson(String s) { this.s = s; }

        Object parse() {
            Object v = value();
            skipWs();
            if (i < s.length()) throw error("trailing characters");
            return v;
        }

        private Object value() {
            skipWs();
            if (i >= s.length()) throw error("unexpected end");
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

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw error("expected string key");
                String k = string();
                skipWs();
                if (peek() != ':') throw error("expected ':'");
                i++;
                Object v = value();
                if (m.containsKey(k)) throw error("duplicate key: " + k);
                m.put(k, v);
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw error("expected ',' or '}'");
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            skipWs();
            if (peek() == ']') { i++; return l; }
            while (true) {
                l.add(value());
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return l; }
                throw error("expected ',' or ']'");
            }
        }

        private Number number() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("bad fraction");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("bad exponent");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try {
                return num.contains(".") || num.contains("e") || num.contains("E")
                        ? (Number) Double.valueOf(num) : (Number) Integer.valueOf(num);
            } catch (NumberFormatException e) {
                throw error("bad number: " + num);
            }
        }

        private String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw error("bad escape");
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
                        case 'u':
                            if (i + 4 > s.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw error("bad escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void expect(String lit) {
            if (!s.startsWith(lit, i)) throw error("expected " + lit);
            i += lit.length();
        }

        private char peek() {
            skipWs();
            if (i >= s.length()) throw error("unexpected end");
            return s.charAt(i);
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else return;
            }
        }

        private IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("vegas_key_catalog JSON: " + msg + " at " + i);
        }
    }
}