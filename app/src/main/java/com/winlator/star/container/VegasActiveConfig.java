package com.winlator.star.container;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * VEGAS baseline/active split — the container's ACTIVE config (design: config-discrepancy
 * report §6e, friend's build order item 1 + active-provenance half of item 2).
 *
 * Layout (per container):
 *   <rootDir>/vegas/active.conf        — the container's real config. Owned by the user
 *                                        once it exists. NOTHING else writes it except
 *                                        through this class, which REQUIRES an event.
 *   <rootDir>/vegas/active.conf.json   — sidecar provenance: sourceType, sourceBaseline,
 *                                        timestamped event log (seed/adopt/switch/import).
 *
 * Invariant (structural, not runtime-guarded): write() is the only way active.conf
 * changes, and it refuses to run without a non-empty eventType+from (i.e. a decision row
 * happened upstream). Baseline files (…/VEGAS/configs/*.conf) are outside this class
 * entirely — the sheet's stock in-place write path is removed by the caller, not defended
 * here. A future code path writing active.conf directly via java.io is a regression,
 * not a shortcut.
 *
 * Dependency-free by design (java.io + java.util only, no Container reference, no org.json):
 * the class is harnessable standalone with plain javac, same discipline as VegasKeyCatalog.
 */
public final class VegasActiveConfig {
    public static final String DIR_NAME = "vegas";
    public static final String ACTIVE_FILENAME = "active.conf";
    public static final String SIDECAR_FILENAME = "active.conf.json";
    public static final int SIDECAR_SCHEMA = 1;

    // Event types (open set: strings in the sidecar, lenient on read so future
    // event types from the migration/import work don't need a schema bump).
    public static final String EVENT_SEED = "seed";
    public static final String EVENT_ADOPT = "adopt";
    public static final String EVENT_SWITCH = "switch";
    public static final String EVENT_IMPORT = "import";

    /** One event record; from = source tag or filesystem path (never null for real events). */
    public static final class Event {
        public final String type;
        public final String from;
        public final long time;
        Event(String type, String from, long time) {
            this.type = type; this.from = from; this.time = time;
        }
    }

    private VegasActiveConfig() {}

    /* ===================== paths ===================== */

    public static File activeFile(File rootDir) {
        return new File(rootDir, DIR_NAME + "/" + ACTIVE_FILENAME);
    }

    public static File sidecarFile(File rootDir) {
        return new File(rootDir, DIR_NAME + "/" + SIDECAR_FILENAME);
    }

    /* ===================== reads ===================== */

    public static boolean exists(File rootDir) {
        return rootDir != null && activeFile(rootDir).isFile();
    }

    /** Content of active.conf, or "" when absent (callers keep useDefaults semantics). */
    public static String read(File rootDir) {
        if (rootDir == null || !exists(rootDir)) return "";
        try {
            return readString(activeFile(rootDir));
        } catch (Exception e) {
            return ""; // unreadable -> treat as absent, never crash the sheet
        }
    }

    /** sourceType: "baseline" | "custom-import" | null (no sidecar / corrupt / never seeded). */
    public static String sourceType(File rootDir) {
        Map<String, Object> s = sidecar(rootDir);
        return s == null ? null : (String) s.get("sourceType");
    }

    /** sourceBaseline: release tag active.conf was seeded/adopted from, or null. */
    public static String sourceBaseline(File rootDir) {
        Map<String, Object> s = sidecar(rootDir);
        return s == null ? null : (String) s.get("sourceBaseline");
    }

    /** Event log in order (oldest first); empty list when absent/unparseable. */
    public static List<Event> events(File rootDir) {
        List<Event> out = new ArrayList<>();
        Map<String, Object> s = sidecar(rootDir);
        if (s == null) return out;
        Object raw = s.get("events");
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Object t = m.get("type"), f = m.get("from"), tm = m.get("time");
            if (!(t instanceof String) || !(f instanceof String) || !(tm instanceof Number)) continue;
            out.add(new Event((String) t, (String) f, ((Number) tm).longValue()));
        }
        return out;
    }

    /* ===================== the ONLY write path ===================== */

    /**
     * Replaces active.conf content AND appends the event in one call.
     * Refuses (returns false, writes nothing) when eventType or from is null/empty —
     * this is the structural enforcement: every change to active.conf is traceable
     * to a decision row that supplied the event. Creates directories as needed.
     */
    public static boolean write(File rootDir, String content, String eventType, String from) {
        if (rootDir == null || eventType == null || eventType.trim().isEmpty()
                || from == null || from.trim().isEmpty() || content == null) {
            return false;
        }
        try {
            File dir = activeFile(rootDir).getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
            writeString(activeFile(rootDir), content);
            appendEvent(rootDir, eventType, from);
            return true;
        } catch (Exception e) {
            return false; // never crash the sheet on IO failure
        }
    }

    /**
     * User-owned edit path: updates active.conf WITHOUT appending a lifecycle event
     * (toggles after ownership are not seed/adopt/switch/import events). Structurally
     * refuses to edit UNOWNED config — the sidecar must already exist (a write() with
     * an event happened first). This is what makes "no silent edits before adoption"
     * enforceable: a caller cannot touch active.conf before a decision row created it.
     */
    public static boolean edit(File rootDir, String content) {
        if (rootDir == null || content == null) return false;
        if (!exists(rootDir) || sidecar(rootDir) == null) return false; // unowned
        try {
            writeString(activeFile(rootDir), content);
            touchSidecar(rootDir);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Refreshes updatedAt in the sidecar without appending an event. Best-effort:
     * a failure here must not roll back the successful content write.
     */
    private static void touchSidecar(File rootDir) {
        try {
            Map<String, Object> s = sidecar(rootDir);
            if (s == null) return; // raced with deletion; content write stands
            s.put("updatedAt", System.currentTimeMillis());
            s.put("schema", SIDECAR_SCHEMA);
            writeString(sidecarFile(rootDir), render(s));
        } catch (Exception ignored) {
        }
    }

    /** Returns (did it find the event?) — paired with write(); also usable standalone. */
    private static void appendEvent(File rootDir, String type, String from) {
        Map<String, Object> s = sidecar(rootDir); // null = fresh sidecar
        if (s == null) s = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) s.get("events");
        if (events == null) events = new ArrayList<>();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("from", from);
        e.put("time", System.currentTimeMillis());
        events.add(e);
        s.put("events", events);
        // downsized sourceType/sourceBaseline derivation: baseline when from looks like a
        // release tag, custom-import when it looks like a filesystem path. Deterministic,
        // documented here, lenient by design (never guessed from FILE CONTENT).
        if ("seed".equals(type) || "adopt".equals(type) || "switch".equals(type)) {
            s.put("sourceType", "baseline");
            s.put("sourceBaseline", from);
        } else if (EVENT_IMPORT.equals(type)) {
            s.put("sourceType", "custom-import");
            s.put("sourceBaseline", null);
        }
        s.put("schema", SIDECAR_SCHEMA);
        try {
            writeString(sidecarFile(rootDir), render(s));
        } catch (Exception ignored) {
            // sidecar write failure must not roll back a successful active.conf write;
            // provenance is best-effort, the file state remains correct.
        }
    }

    /* ===================== sidecar JSON (flat schema, dependency-free) ===================== */

    private static Map<String, Object> sidecar(File rootDir) {
        File f = sidecarFile(rootDir);
        if (f == null || !f.isFile()) return null;
        String json;
        try {
            json = readString(f);
        } catch (Exception e) {
            return null; // corrupt/unreadable -> treated as no sidecar; next write replaces it
        }
        try {
            Object v = parse(json);
            if (!(v instanceof Map)) return null;
            return uncheckedMap(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String render(Map<String, Object> m) {
        return writeValue(m, new StringBuilder()).toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> uncheckedMap(Object o) { return (Map<String, Object>) o; }

    @SuppressWarnings("unchecked")
    private static List<Object> uncheckedList(Object o) { return (List<Object>) o; }

    private static StringBuilder writeValue(Object v, StringBuilder sb) {
        if (v == null) return sb.append("null");
        if (v instanceof String) return writeStringValue((String) v, sb);
        if (v instanceof Boolean || v instanceof Number) return sb.append(v);
        if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeStringValue(e.getKey(), sb).append(':');
                writeValue(e.getValue(), sb);
            }
            return sb.append('}');
        }
        if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeValue(o, sb);
            }
            return sb.append(']');
        }
        return sb.append("null");
    }

    private static StringBuilder writeStringValue(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"');
    }

    public static Object parse(String json) {
        Parser p = new Parser(json);
        Object v = p.value();
        p.skipWs();
        if (p.i < p.s.length()) throw new IllegalArgumentException("trailing characters");
        return v;
    }

    private static final class Parser {
        final String s; int i;
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
        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw new IllegalArgumentException("expected string key");
                String k = string();
                skipWs();
                if (peek() != ':') throw new IllegalArgumentException("expected ':'");
                i++;
                Object v = value();
                if (m.containsKey(k)) throw new IllegalArgumentException("duplicate key: " + k);
                m.put(k, v);
                skipWs();
                char c = peek();
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new IllegalArgumentException("expected ',' or '}'");
            }
        }
        List<Object> array() {
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
        Object number() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw new IllegalArgumentException("bad fraction");
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String num = s.substring(start, i);
            try { return num.contains(".") ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("bad number"); }
        }
        String string() {
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
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad unicode escape");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape: \\" + e);
                    }
                } else sb.append(c);
            }
        }
        void expect(String lit) {
            if (!s.startsWith(lit, i)) throw new IllegalArgumentException("expected " + lit);
            i += lit.length();
        }
        char peek() { skipWs(); if (i >= s.length()) throw new IllegalArgumentException("unexpected end"); return s.charAt(i); }
        void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else return;
            }
        }
    }

    private static String readString(File f) throws java.io.IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeString(File f, String content) throws java.io.IOException {
        java.nio.file.Files.write(f.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}