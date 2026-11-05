package com.winlator.star.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-game mods (game settings > Mods, in-game drawer > Mods): DLLs Bannerlator downloads from its own
 * GitHub releases (core.GameModCache) and places next to the game EXE at launch.
 *
 * <ul>
 * <li>REFramework (praydog, MIT): The412Banner/REFramework release arm64ec-r2, as dinput8.dll, only for the
 * RE Engine EXEs REFramework knows (core.ReFramework), 64-bit.</li>
 * <li>Special K (Kaldaien / Andon M. Coleman and contributors, SpecialKO, GPL-3.0): The412Banner/SpecialK
 * release arm64ec-r3, SpecialK64.dll or SpecialK32.dll by the EXE's machine type, as dxgi.dll / d3d11.dll /
 * d3d9.dll / d3d8.dll / opengl32.dll (picked from the EXE's imports, or chosen per game).</li>
 * </ul>
 *
 * Per game, off by default ({@link Mod#extra} = "1" turns a mod on). A marker file per mod records the
 * sha256 (and the DLL name) of the file we placed, so only our file is ever replaced or removed; a game
 * folder's own DLL of the same name is never touched. Switching Special K off also deletes its logs; its
 * .ini settings stay. Pure Java (no Android calls), so it is unit-tested.
 */
public final class GameMods {
    private GameMods() {}

    public static final int MACHINE_X64 = 0x8664;
    public static final int MACHINE_X86 = 0x14c;

    /** One downloadable build: release URL, sha256 and size. */
    public static final class Asset {
        public final String url;
        public final String sha256;
        public final long size;

        Asset(String url, String sha256, long size) {
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }

        /** The release tag in {@link #url} (".../releases/download/<tag>/file"), or "" when it has none. */
        public String tag() {
            String key = "/releases/download/";
            int i = url.indexOf(key);
            if (i < 0) return "";
            int start = i + key.length();
            int end = url.indexOf('/', start);
            return end > start ? url.substring(start, end) : "";
        }
    }

    public static final class Mod {
        public final String id;
        public final String name;
        /** Shortcut extra: "1" = on for this game. */
        public final String extra;
        /** Marker file in the game folder. */
        public final String marker;
        final Asset x64;
        final Asset x86;
        /** The DLL name in the game folder, or null when it is picked per game. */
        final String fixedDll;

        Mod(String id, String name, String extra, String marker, Asset x64, Asset x86, String fixedDll) {
            this.id = id;
            this.name = name;
            this.extra = extra;
            this.marker = marker;
            this.x64 = x64;
            this.x86 = x86;
            this.fixedDll = fixedDll;
        }
    }

    public static final Mod REFRAMEWORK = new Mod(
            "reframework", "REFramework", ReFramework.EXTRA, ReFramework.MARKER,
            new Asset("https://github.com/The412Banner/REFramework/releases/download/arm64ec-r2/dinput8.dll",
                    "636e819dd0a0dd7664a26f1c1a2b4ca268222155e92cd79679b84d36f3f18930", 23091712L),
            null, ReFramework.DLL);

    public static final Mod SPECIAL_K = new Mod(
            "specialk", "Special K", "specialk", ".bannerlator-specialk",
            new Asset("https://github.com/The412Banner/SpecialK/releases/download/arm64ec-r3/SpecialK64.dll",
                    "84354b458ffaeeb6f61ced7b5dc8cf2ec5c51098b0e1caf445661e97b1419d86", 15888896L),
            new Asset("https://github.com/The412Banner/SpecialK/releases/download/arm64ec-r3/SpecialK32.dll",
                    "bf9101fcf49120510c59acb1f1e3c89357782f47ad3b5d13af85ef99cd0c99a2", 13355520L),
            null);

    public static final List<Mod> ALL = Collections.unmodifiableList(Arrays.asList(REFRAMEWORK, SPECIAL_K));

    /** Shortcut extra: a program started with the game (a trainer, Cheat Engine), as a Windows path. */
    public static final String EXTRA_START_WITH = "startWithGame";

    /** Shortcut extra: Special K's DLL name for this game ("" or absent = picked from the EXE). */
    public static final String EXTRA_SPECIALK_DLL = "specialkDll";
    /** The names Special K can be loaded as. */
    public static final List<String> SPECIALK_DLLS = Collections.unmodifiableList(
            Arrays.asList("dxgi.dll", "d3d11.dll", "d3d9.dll", "d3d8.dll", "opengl32.dll"));

    public static Mod byId(String id) {
        for (Mod m : ALL) if (m.id.equals(id)) return m;
        return null;
    }

    /** The build of {@code mod} for an EXE of this machine type, or null when there is none. */
    public static Asset asset(Mod mod, int machine) {
        if (machine == MACHINE_X64) return mod.x64;
        if (machine == MACHINE_X86) return mod.x86;
        return null;
    }

    /** {@code mod} can be offered for this EXE (path and PE machine type). */
    public static boolean supports(Mod mod, String exePath, int machine) {
        if (asset(mod, machine) == null) return false;
        return mod != REFRAMEWORK || ReFramework.isSupportedExe(exePath);
    }

    /** The DLL name {@code mod} goes in as: fixed, the per-game {@code choice}, or picked from the EXE. */
    public static String targetDll(Mod mod, String choice, Set<String> imports, int machine) {
        if (mod.fixedDll != null) return mod.fixedDll;
        String c = choice == null ? "" : choice.trim().toLowerCase(Locale.ROOT);
        if (SPECIALK_DLLS.contains(c)) return c;
        return autoDll(imports, machine);
    }

    /**
     * Special K's name from the EXE's imports: Direct3D 9 / 8 and OpenGL games by their DLL, DXGI-era
     * games as dxgi.dll. An EXE that imports none of them loads its renderer at run time: a 32-bit one is
     * taken as Direct3D 9, a 64-bit one as DXGI.
     */
    static String autoDll(Set<String> imports, int machine) {
        Set<String> i = imports == null ? Collections.<String>emptySet() : imports;
        boolean dxgi = i.contains("dxgi.dll") || i.contains("d3d11.dll") || i.contains("d3d12.dll") || i.contains("d3d10.dll")
                || i.contains("d3d10_1.dll");
        if (!dxgi && i.contains("d3d9.dll")) return "d3d9.dll";
        if (!dxgi && i.contains("d3d8.dll")) return "d3d8.dll";
        if (!dxgi && i.contains("opengl32.dll")) return "opengl32.dll";
        return !dxgi && machine == MACHINE_X86 ? "d3d9.dll" : "dxgi.dll";
    }

    /** {@code existing} plus {@code <dll>=n,b}, unless it already names that DLL (then unchanged). */
    public static String overrides(String existing, String dll) {
        String e = existing == null ? "" : existing.trim();
        String base = dll.toLowerCase(Locale.ROOT);
        if (base.endsWith(".dll")) base = base.substring(0, base.length() - 4);
        if (GameXInputDlls.named(e).containsKey(base)) return e;
        return e.isEmpty() ? base + "=n,b" : e + ";" + base + "=n,b";
    }

    // ── The EXE: machine type and imports ────────────────────────────────────────────────────────

    /** The PE machine type of {@code exe} ({@link #MACHINE_X64}, {@link #MACHINE_X86}, ...), or 0. */
    public static int peMachine(File exe) {
        if (exe == null || !exe.isFile()) return 0;
        try (RandomAccessFile f = new RandomAccessFile(exe, "r")) {
            long pe = peHeader(f);
            if (pe < 0) return 0;
            f.seek(pe + 4);
            return u16(f);
        } catch (IOException e) {
            return 0;
        }
    }

    /** The DLL names {@code exe} imports, lowercase (empty when unreadable). */
    public static Set<String> peImports(File exe) {
        Set<String> out = new LinkedHashSet<>();
        if (exe == null || !exe.isFile()) return out;
        try (RandomAccessFile f = new RandomAccessFile(exe, "r")) {
            long pe = peHeader(f);
            if (pe < 0) return out;
            f.seek(pe + 6);
            int sections = u16(f);
            f.seek(pe + 20);
            int optSize = u16(f);
            long opt = pe + 24;
            f.seek(opt);
            int magic = u16(f);
            long dirs = opt + (magic == 0x20b ? 112 : 96);
            f.seek(dirs + 8);   // data directory 1: imports
            long importRva = u32(f);
            if (importRva == 0) return out;
            long secTable = opt + optSize;
            long[][] secs = new long[Math.min(sections, 96)][];
            for (int s = 0; s < secs.length; s++) {
                f.seek(secTable + s * 40L + 8);
                long vsize = u32(f), va = u32(f), rawSize = u32(f), raw = u32(f);
                secs[s] = new long[]{va, Math.max(vsize, rawSize), raw};
            }
            long desc = rvaToOffset(secs, importRva);
            for (int n = 0; desc > 0 && n < 512; n++, desc += 20) {
                f.seek(desc + 12);
                long nameRva = u32(f);
                if (nameRva == 0) break;
                long nameOff = rvaToOffset(secs, nameRva);
                if (nameOff <= 0) continue;
                f.seek(nameOff);
                StringBuilder sb = new StringBuilder();
                for (int c; sb.length() < 64 && (c = f.read()) > 0; ) sb.append((char) c);
                if (sb.length() > 0) out.add(sb.toString().toLowerCase(Locale.ROOT));
            }
        } catch (IOException e) {
            // unreadable: no imports
        }
        return out;
    }

    private static long peHeader(RandomAccessFile f) throws IOException {
        if (f.length() < 0x40) return -1;
        f.seek(0);
        if (f.read() != 'M' || f.read() != 'Z') return -1;
        f.seek(0x3c);
        long pe = u32(f);
        if (pe <= 0 || pe + 24 > f.length()) return -1;
        f.seek(pe);
        if (f.read() != 'P' || f.read() != 'E' || f.read() != 0 || f.read() != 0) return -1;
        return pe;
    }

    private static long rvaToOffset(long[][] secs, long rva) {
        for (long[] s : secs) if (s != null && rva >= s[0] && rva < s[0] + s[1]) return s[2] + (rva - s[0]);
        return -1;
    }

    private static int u16(RandomAccessFile f) throws IOException {
        int a = f.read(), b = f.read();
        if ((a | b) < 0) throw new IOException("eof");
        return a | (b << 8);
    }

    private static long u32(RandomAccessFile f) throws IOException {
        long a = f.read(), b = f.read(), c = f.read(), d = f.read();
        if ((a | b | c | d) < 0) throw new IOException("eof");
        return a | (b << 8) | (c << 16) | (d << 24);
    }

    // ── The DLL in the game folder ───────────────────────────────────────────────────────────────

    /** What {@link #install} found and did. */
    public static final class Result {
        /** Our DLL is in place (false: the game folder has its own file of that name, or nothing). */
        public final boolean ours;
        /** A file of that name is in the folder (ours or the game's own), so the DLL override applies. */
        public final boolean present;
        public final String note;

        Result(boolean ours, boolean present, String note) {
            this.ours = ours;
            this.present = present;
            this.note = note;
        }
    }

    /** {@code name} in {@code dir}, any letter case, or null. */
    static File find(File dir, String name) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return null;
        for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    /** The marker of {@code mod} in {@code dir}: [sha256, dll name or null], or null when there is none. */
    static String[] readMarker(File dir, Mod mod) {
        try {
            File m = new File(dir, mod.marker);
            if (!m.isFile()) return null;
            String[] parts = new String(Files.readAllBytes(m.toPath()), StandardCharsets.UTF_8).trim().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) return null;
            return new String[]{parts[0], parts.length > 1 ? parts[1] : mod.fixedDll};
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMarker(File dir, Mod mod, String sha, String dll) throws IOException {
        // REFramework's marker keeps its original one-field format, so older app builds still read it.
        String text = mod.fixedDll != null ? sha : sha + " " + dll;
        try (OutputStream os = new FileOutputStream(new File(dir, mod.marker))) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** True when {@code dir} already holds our {@code dll} with sha256 {@code sha} (no download needed). */
    public static boolean inPlace(File dir, String dll, String sha) {
        try {
            File f = find(dir, dll);
            return f != null && f.length() > 0 && GameXInputDlls.sha256(f).equals(sha);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Puts {@code source} (sha256 {@code sha}) into {@code dir} as {@code dll}. A file of that name that is not
     * ours (no marker, or a different sha256) is left alone. If the marker says we placed {@code mod} under
     * another name before, that file is removed first.
     */
    public static Result install(File dir, Mod mod, String dll, File source, String sha) throws Exception {
        String[] marker = readMarker(dir, mod);
        if (marker != null && marker[1] != null && !marker[1].equalsIgnoreCase(dll)) {
            File old = find(dir, marker[1]);
            if (old != null && GameXInputDlls.sha256(old).equals(marker[0])) old.delete();
            new File(dir, mod.marker).delete();
            marker = null;
        }
        File existing = find(dir, dll);
        if (existing != null) {
            String have = GameXInputDlls.sha256(existing);
            if (have.equals(sha)) {
                if (marker == null || !sha.equals(marker[0])) writeMarker(dir, mod, sha, dll);
                return new Result(true, true, dll + " already in place");
            }
            if (marker == null || !have.equals(marker[0]))
                return new Result(false, true, "the game folder has its own " + existing.getName() + " (not ours): left as it is");
            if (source == null) return new Result(true, true, "an older " + dll + " of ours kept (no download available)");
            copy(source, existing);   // ours from an older release: update it
            writeMarker(dir, mod, sha, dll);
            return new Result(true, true, dll + " updated");
        }
        if (source == null) return new Result(false, false, "no " + mod.name + " download available: not loaded");
        copy(source, new File(dir, dll));
        writeMarker(dir, mod, sha, dll);
        return new Result(true, true, dll + " placed");
    }

    /**
     * Removes the file we placed for {@code mod} (sha256 == marker); a note, or null when nothing to do. For
     * Special K its logs go too ({@link #removeSpecialKLogs}); its .ini settings stay. A DLL of the same name
     * that is not ours keeps its logs.
     */
    public static String remove(File dir, Mod mod) throws Exception {
        String[] marker = readMarker(dir, mod);
        if (marker == null) return null;
        File m = new File(dir, mod.marker);
        File dll = marker[1] != null ? find(dir, marker[1]) : null;
        if (dll != null && GameXInputDlls.sha256(dll).equals(marker[0])) {
            if (!dll.delete()) return "could not remove " + dll.getName();
            m.delete();
            boolean logs = mod == SPECIAL_K && removeSpecialKLogs(dir) > 0;
            return "removed our " + dll.getName() + (logs ? " and Special K's logs" : "");
        }
        m.delete();
        if (dll != null) return "the game folder's own " + dll.getName() + " left as it is";
        return mod == SPECIAL_K && removeSpecialKLogs(dir) > 0 ? "removed Special K's logs" : null;
    }

    /** Special K's log files in logs\ (SpecialKO src/core.cpp, config.cpp, crash_handler.cpp, platform code). */
    static final Set<String> SPECIALK_LOGS;
    static {
        Set<String> s = new LinkedHashSet<>();
        for (String dll : SPECIALK_DLLS) s.add(dll.substring(0, dll.length() - 4) + ".log");
        s.addAll(Arrays.asList("specialk.log", "dxgi_budget.log", "crash.log", "game_output.log", "modules.log",
                "platform.log", "steam_api.log", "eos.log", "galaxy.log", "installer.log", "textures.log",
                "file_reads.log", "file_writes.log"));
        SPECIALK_LOGS = Collections.unmodifiableSet(s);
    }

    /** Special K's crash copies: logs\crash\<%Y-%m-%d__%H'%M'%S>\ (crash_handler.cpp). */
    private static final Pattern SPECIALK_CRASH_RUN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}__\\d{2}'\\d{2}'\\d{2}");

    /**
     * Deletes Special K's files in {@code dir}\logs (its logs and its crash\ copies), then the crash and logs
     * folders when they are empty. Anything else in there is the game's and stays. Returns how many went.
     */
    static int removeSpecialKLogs(File dir) {
        File logs = findDir(dir, "logs");
        File[] entries = logs != null ? logs.listFiles() : null;
        if (entries == null) return 0;
        int n = 0;
        for (File f : entries) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (f.isFile() && SPECIALK_LOGS.contains(name)) {
                if (f.delete()) n++;
            } else if (f.isDirectory() && !Files.isSymbolicLink(f.toPath()) && name.equals("crash")) {
                File[] runs = f.listFiles();
                if (runs != null) for (File run : runs) {
                    if (run.isDirectory() && SPECIALK_CRASH_RUN.matcher(run.getName()).matches() && deleteTree(run)) n++;
                }
                if (isEmptyDir(f)) f.delete();
            }
        }
        if (isEmptyDir(logs)) logs.delete();
        return n;
    }

    private static File findDir(File dir, String name) {
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory() && !Files.isSymbolicLink(f.toPath()) && f.getName().equalsIgnoreCase(name)) return f;
        }
        return null;
    }

    private static boolean isEmptyDir(File dir) {
        String[] left = dir.list();
        return left != null && left.length == 0;
    }

    /** Deletes {@code f} and what is under it; a symbolic link is removed, never followed. */
    private static boolean deleteTree(File f) {
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        return f.delete();
    }

    static void copy(File from, File to) throws IOException {
        ReFramework.copy(from, to);
    }
}
