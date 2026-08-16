package com.winlator.star.contentdialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VEGAS config-key knowledge model, data-driven (locked design, 2026-08-16).
 *
 * The classifier holds ONLY rules. All facts — released versions, vanilla DXVK
 * keys, fork-gated keys (with introduce/removal versions) — come from
 * vegas_knowledge.json (bundled asset, later download-time data per decision 9).
 *
 * Classification of a key against a selected VEGAS version:
 *  - unknown version (no stock template for it)          -> UNKNOWN
 *  - key in vanilla list (upstream-documented)           -> VANILLA (every version)
 *  - env-style key (not dxvk./vegas.-prefixed)           -> OK (always applies)
 *  - key in gated manifest: version < introducedIn       -> LATE ("needs X+")
 *  - key in gated manifest: version in [introduced, rem) -> OK
 *  - key in gated manifest: version >= removedIn         -> REMOVED (hidden)
 *  - unprovable fork/dxvk key                            -> UNLISTED ("still reads it")
 *
 * Strictness ("discipline"): the loader REJECTS the whole payload on any
 * schema violation — unknown top-level fields, duplicate names, introducedIn/
 * removedIn not in released, removedIn before introducedIn, vanilla/gated
 * overlap, malformed types. Callers fall back to the bundled last-known-good
 * payload on rejection ("strongness" is a Tier-2 wiring concern).
 */
public final class VegasKeyKnowledge {
    public enum State { OK, LATE, REMOVED, UNLISTED, VANILLA, UNKNOWN }

    private static final Set<String> TOP_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "schemaVersion", "forkBuild", "generated", "released", "vanilla", "gated", "envStyle"));
    private static final Set<String> GATED_ENTRY_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "name", "introducedIn", "removedIn", "note"));

    private final String forkBuild;
    private final String generated;
    private final List<String> released;
    private final Map<String, String> vanilla;      // key -> key (set semantics, indexed)
    private final Map<String, Integer> introduced;  // key -> index into released
    private final Map<String, Integer> removed;     // key -> index into released (absent = never)
    private final Map<String, String> notes;        // key -> note
    private final List<String> envStyle;

    public VegasKeyKnowledge(String json) {
        Map<String, Object> root = parseStrictObject(json);
        validateTopLevel(root);
        this.forkBuild = asNonEmptyString(root, "forkBuild", "forkBuild");
        this.generated = asString(root, "generated");
        this.released = asStringList(root, "released", false);
        this.envStyle = asStringList(root, "envStyle", false);

        List<Object> vanillaRaw = asList(root, "vanilla", true);
        this.vanilla = new LinkedHashMap<>();
        for (Object o : vanillaRaw) {
            String k = coerceString(o, "vanilla entry");
            if (vanilla.containsKey(k)) throw reject("duplicate vanilla key: " + k);
            vanilla.put(k, k);
        }

        List<Object> gatedRaw = asList(root, "gated", true);
        this.introduced = new LinkedHashMap<>();
        this.removed = new LinkedHashMap<>();
        this.notes = new LinkedHashMap<>();
        for (Object o : gatedRaw) {
            if (!(o instanceof Map)) throw reject("gated entry must be an object");
            Map<String, Object> e = uncheckedMap(o);
            for (String f : e.keySet())
                if (!GATED_ENTRY_FIELDS.contains(f)) throw reject("unknown field in gated entry: " + f);
            String name = asNonEmptyString(e, "name", "gated entry");
            String intro = asNonEmptyString(e, "introducedIn", "gated entry " + name);
            Integer introIdx = released.indexOf(intro);
            if (introIdx < 0) throw reject("gated key " + name + ": introducedIn '" + intro + "' not in released");
            if (introduced.containsKey(name)) throw reject("duplicate gated key: " + name);
            if (vanilla.containsKey(name)) throw reject("key in both vanilla and gated: " + name);
            introduced.put(name, introIdx);
            if (e.containsKey("removedIn") && e.get("removedIn") != null) {
                String rem = asNonEmptyString(e, "removedIn", "gated entry " + name);
                Integer remIdx = released.indexOf(rem);
                if (remIdx < 0) throw reject("gated key " + name + ": removedIn '" + rem + "' not in released");
                if (remIdx < introIdx) throw reject("gated key " + name + ": removedIn before introducedIn");
                removed.put(name, remIdx);
            }
            if (e.containsKey("note") && e.get("note") != null)
                notes.put(name, asString(e, "note"));
        }
    }

    /** The fork build tag this knowledge payload was generated for (stale check helper). */
    public String forkBuild() { return forkBuild; }

    /** ISO date this payload was generated (display helper). */
    public String generated() { return generated; }

    /** Ordered released versions; index order defines version ordering. */
    public List<String> released() { return released; }

    /** Version gating info: the version that introduced a fork key, or null. */
    public String introducedFor(String key) {
        Integer idx = introduced.get(key);
        return idx == null ? null : released.get(idx);
    }

    /** Version gating info: the version that removed a fork key, or null. */
    public String removedFor(String key) {
        Integer idx = removed.get(key);
        return idx == null ? null : released.get(idx);
    }

    public boolean isVanilla(String key) { return key != null && vanilla.containsKey(key); }

    public boolean isForkKey(String key) {
        return key != null && (key.startsWith("vegas.") || introduced.containsKey(key));
    }

    /**
     * Classifies a config key against the selected VEGAS version string
     * (one of {@link #released()}). A null/unknown version yields UNKNOWN.
     */
    public State stateFor(String key, String version) {
        if (key == null) return State.UNKNOWN;
        if (vanilla.containsKey(key)) return State.VANILLA;
        // env-style [other] keys always apply — independent of the config file layer
        // (verified: DXVK_FRAME_RATE is emitted regardless; see DXVKConfigDialog.setEnvVars)
        if (!key.startsWith("vegas.") && !key.startsWith("dxvk.")) return State.OK;
        int vIdx = released.indexOf(version);
        if (vIdx < 0) return State.UNKNOWN;                      // no stock template for this version
        Integer introIdx = introduced.get(key);
        if (introIdx == null) return State.UNLISTED;             // unprovable fork/dxvk key
        Integer remIdx = removed.get(key);
        if (remIdx != null && vIdx >= remIdx) return State.REMOVED;
        return vIdx >= introIdx ? State.OK : State.LATE;
    }

    /** The inline defaults DXVKConfigDialog.setEnvVars emits when no config file is active. */
    public static List<String> inlineDefaults() {
        return Arrays.asList("dxvk.enableStarProfile = Auto", "vegas.enableUpscaler = Auto");
    }

    // ---------------------------------------------------------------- loading

    private static void validateTopLevel(Map<String, Object> root) {
        for (String f : root.keySet())
            if (!TOP_FIELDS.contains(f)) throw reject("unknown top-level field: " + f);
        Object sv = root.get("schemaVersion");
        if (!(sv instanceof Integer) || (Integer) sv != 1)
            throw reject("schemaVersion must be 1, got: " + sv);
        if (!root.containsKey("forkBuild")) throw reject("missing forkBuild");
        if (!root.containsKey("generated")) throw reject("missing generated");
        if (!root.containsKey("released")) throw reject("missing released");
    }

    private static Map<String, Object> parseStrictObject(String json) {
        MiniJson parser = new MiniJson(json);
        Object root = parser.parse();
        if (!(root instanceof Map)) throw reject("root must be an object");
        return uncheckedMap(root);
    }

    private static Map<String, Object> uncheckedMap(Object o) {
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) o;
        return m;
    }

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

    private static List<Object> uncheckedList(Object o) {
        @SuppressWarnings("unchecked")
        List<Object> l = (List<Object>) o;
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
        String v = coerceString(m.get(field), context);
        if (v.isEmpty()) throw reject(context + ": " + field + " must not be empty");
        return v;
    }

    private static String coerceString(Object o, String context) {
        if (!(o instanceof String)) throw reject(context + ": expected string, got " + typeOf(o));
        return (String) o;
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static IllegalArgumentException reject(String msg) {
        return new IllegalArgumentException("vegas_knowledge.json: " + msg);
    }

    // ------------------------------------------------------------- mini JSON

    /**
     * Minimal strict JSON parser (objects, arrays, strings, numbers, booleans,
     * null). Rejects trailing garbage and duplicate object keys. Sufficient for
     * the fixed knowledge schema; kept dependency-free for standalone javac tests.
     */
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
            i++; // {
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
            i++; // [
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
            boolean frac = false, exp = false;
            if (i < s.length() && s.charAt(i) == '.') {
                frac = true; i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("bad fraction");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                exp = true; i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw error("bad exponent");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try {
                if (!frac && !exp) return Integer.valueOf(num);
                return Double.valueOf(num);
            } catch (NumberFormatException e) {
                throw error("bad number: " + num);
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                if (i >= s.length()) throw error("unterminated string");
                char c = s.charAt(i);
                if (c == '"') { i++; return sb.toString(); }
                if (c == '\\') {
                    i++;
                    if (i >= s.length()) throw error("bad escape");
                    char e = s.charAt(i);
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u': {
                            if (i + 4 >= s.length()) throw error("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                            break;
                        }
                        default: throw error("bad escape: \\" + e);
                    }
                    i++;
                } else {
                    if (c < 0x20) throw error("control char in string");
                    sb.append(c);
                    i++;
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
                else break;
            }
        }

        private IllegalArgumentException error(String msg) {
            return reject("parse error at " + i + ": " + msg);
        }
    }
}