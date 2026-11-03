package com.winlator.star.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * REFramework (praydog, MIT, github.com/praydog/REFramework) for RE Engine games, bundled in the APK
 * (assets/reframework). Our build skips x86-64 inline hooks on ARM64EC code (praydog/REFramework#1887),
 * which is what crashed it on ARM64EC Wine ("illegal instruction" in Wine's ARM64EC dinput8).
 *
 * <p>Per game, off by default ({@value #EXTRA} = "1" turns it on), only for the EXEs REFramework knows.
 * On: the bundled {@code dinput8.dll} goes next to the game EXE, {@value #MARKER} records its sha256 so
 * only a file we placed is ever replaced or removed, {@code dinput8=n,b} joins WINEDLLOVERRIDES (a user's
 * own dinput8 entry is kept), and REFramework's config is set so its menu starts closed (the in-game
 * drawer's REFramework button opens it). Off: our file is removed; a game folder's own dinput8.dll is
 * never touched. Pure Java (no Android calls), so it is unit-tested; callers log the lines it returns.
 */
public final class ReFramework {
    private ReFramework() {}

    /** Per-game extra: "1" = REFramework on for this game. */
    public static final String EXTRA = "reframework";
    public static final String ASSET_DIR = "reframework";
    public static final String DLL = "dinput8.dll";
    /** In the game folder: the sha256 of the dinput8.dll we placed. */
    public static final String MARKER = ".bannerlator-reframework";
    /** REFramework's config file name, the same for every game (REFrameworkConfig.hpp). */
    public static final String CONFIG = "re2_fw_config.txt";
    static final String KEY_REMEMBER = "REFrameworkConfig_RememberMenuState";
    static final String KEY_MENU_OPEN = "REFrameworkConfig_MenuOpen";

    /** EXE names (lowercase, no ".exe") from REFramework's own table, shared/sdk/GameIdentity.cpp. */
    private static final Set<String> STEMS = new HashSet<>(Arrays.asList(
            "re2", "re3", "re4", "re7", "re8", "re9",
            "devilmaycry5", "dmc5",
            "monsterhunterrise", "mhrise", "mhrisesunbreakdemo",
            "streetfighter6", "sf6",
            "dd2",
            "makaimura_gg_re", "makaimura",
            "onimusha2",
            "monsterhunterwilds", "mhwilds",
            "monster_hunter_stories_3_twisted_reflection",
            "monster_hunter_stories_3_twisted_reflection_trial",
            "pragmata",
            "onimushawots", "onimushawots_demo"));

    /** The EXE name of a Windows or Unix path, lowercase, without ".exe" ("" for none). */
    public static String stem(String path) {
        if (path == null) return "";
        String p = path.trim();
        int cut = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        String name = (cut >= 0 ? p.substring(cut + 1) : p).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : "";
    }

    public static boolean isSupportedExe(String path) {
        return STEMS.contains(stem(path));
    }

    // ── WINEDLLOVERRIDES ─────────────────────────────────────────────────────────────────────────

    /** {@code existing} plus {@code dinput8=n,b}, unless it already names dinput8 (then unchanged). */
    public static String overrides(String existing) {
        String e = existing == null ? "" : existing.trim();
        if (GameXInputDlls.named(e).containsKey("dinput8")) return e;
        return e.isEmpty() ? "dinput8=n,b" : e + ";dinput8=n,b";
    }

    // ── REFramework config: menu starts closed ───────────────────────────────────────────────────

    /**
     * {@code text} (REFramework's key=value config, may be empty) with RememberMenuState=true and
     * MenuOpen=false, so the menu starts closed; other lines and the line ending are kept.
     */
    public static String menuStartsClosed(String text) {
        String t = text == null ? "" : text;
        String nl = t.contains("\r\n") ? "\r\n" : (t.isEmpty() ? "\r\n" : "\n");
        StringBuilder out = new StringBuilder();
        boolean remember = false, open = false;
        for (String line : t.split("\r?\n", -1)) {
            if (line.isEmpty()) continue;
            String key = line.contains("=") ? line.substring(0, line.indexOf('=')).trim() : line.trim();
            if (key.equals(KEY_REMEMBER)) { line = KEY_REMEMBER + "=true"; remember = true; }
            else if (key.equals(KEY_MENU_OPEN)) { line = KEY_MENU_OPEN + "=false"; open = true; }
            out.append(line).append(nl);
        }
        if (!remember) out.append(KEY_REMEMBER).append("=true").append(nl);
        if (!open) out.append(KEY_MENU_OPEN).append("=false").append(nl);
        return out.toString();
    }

    /** Writes {@link #menuStartsClosed} into {@code dir}/{@value #CONFIG}; true when the file changed. */
    public static boolean applyMenuStartsClosed(File dir) throws IOException {
        File f = new File(dir, CONFIG);
        String before = f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
        String after = menuStartsClosed(before);
        if (after.equals(before)) return false;
        try (OutputStream os = new FileOutputStream(f)) { os.write(after.getBytes(StandardCharsets.UTF_8)); }
        return true;
    }

    // ── The DLL in the game folder ───────────────────────────────────────────────────────────────

    /** What {@link #install} found and did. */
    public static final class Result {
        /** Our dinput8.dll (or the game folder's own one) is in place, so the override applies. */
        public final boolean loaded;
        public final String note;
        Result(boolean loaded, String note) { this.loaded = loaded; this.note = note; }
    }

    /** dinput8.dll in {@code dir}, any letter case, or null. */
    static File findDll(File dir) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return null;
        for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase(DLL)) return f;
        return null;
    }

    static String readMarker(File dir) {
        try {
            File m = new File(dir, MARKER);
            return m.isFile() ? new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8).trim() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Puts {@code bundled} (sha256 {@code bundledSha}) into {@code dir} as dinput8.dll. A dinput8.dll that
     * is not ours (no marker, or a different sha256) is left alone and used as it is.
     */
    public static Result install(File dir, File bundled, String bundledSha) throws Exception {
        File dll = findDll(dir);
        String marker = readMarker(dir);
        if (dll != null) {
            String sha = GameXInputDlls.sha256(dll);
            if (sha.equals(bundledSha)) {
                if (!bundledSha.equals(marker)) writeMarker(dir, bundledSha);
                return new Result(true, "dinput8.dll already in place");
            }
            if (marker == null || !sha.equals(marker))
                return new Result(true, "the game folder has its own " + dll.getName() + " (not ours): left as it is");
            copy(bundled, dll);   // ours from an older app build: update it
            writeMarker(dir, bundledSha);
            return new Result(true, "dinput8.dll updated");
        }
        copy(bundled, new File(dir, DLL));
        writeMarker(dir, bundledSha);
        return new Result(true, "dinput8.dll placed");
    }

    /** Removes the dinput8.dll we placed (sha256 == marker); returns a note, or null when nothing to do. */
    public static String remove(File dir) throws Exception {
        File dll = findDll(dir);
        String marker = readMarker(dir);
        if (marker == null) return null;
        File m = new File(dir, MARKER);
        if (dll != null && GameXInputDlls.sha256(dll).equals(marker)) {
            if (!dll.delete()) return "could not remove " + dll.getName();
            m.delete();
            return "removed our dinput8.dll";
        }
        m.delete();
        return dll != null ? "the game folder's own " + dll.getName() + " left as it is" : null;
    }

    private static void writeMarker(File dir, String sha) throws IOException {
        try (OutputStream os = new FileOutputStream(new File(dir, MARKER))) {
            os.write(sha.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void copy(File from, File to) throws IOException {
        File tmp = new File(to.getParentFile(), to.getName() + ".bannerlator-tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (to.exists() && !to.delete()) { tmp.delete(); throw new IOException("cannot replace " + to); }
        if (!tmp.renameTo(to)) { tmp.delete(); throw new IOException("cannot rename to " + to); }
    }
}
