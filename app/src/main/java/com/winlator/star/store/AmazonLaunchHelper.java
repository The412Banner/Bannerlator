package com.winlator.star.store;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the Wine launch command for an installed Amazon game.
 *
 * Launch flow:
 *   1. Try fuel.json → Main.Command / WorkingSubdirOverride / Args
 *   2. Fallback: ExecutableSelectionUtils scoring heuristic (Java port)
 *   3. Build A:\\path\\to\\game.exe Wine path
 *   4. Return "winhandler.exe \"A:\\path\\to\\game.exe\" [args]"
 *
 * FuelPump environment variables (caller must set these in Wine env):
 *   FUEL_DIR                           = C:\ProgramData\Amazon Games Services\Legacy
 *   AMAZON_GAMES_SDK_PATH              = C:\ProgramData\Amazon Games Services\AmazonGamesSDK
 *   AMAZON_GAMES_FUEL_ENTITLEMENT_ID   = game.entitlementId
 *   AMAZON_GAMES_FUEL_PRODUCT_SKU      = game.productSku
 *   AMAZON_GAMES_FUEL_DISPLAY_NAME     = Player
 */
public class AmazonLaunchHelper {

    private static final String TAG = "BH_AMAZON";

    public static class LaunchSpec {
        /** Full winhandler.exe command, e.g. winhandler.exe "A:\game.exe" arg1 */
        public String command;
        /** Absolute Wine working directory path (for workingDir override) */
        public String workingDir;
        /** Relative exe path within install dir (for caching in container) */
        public String exeRelativePath;
    }

    /**
     * Builds the launch command for an installed Amazon game.
     *
     * @param installDir  absolute path to the game install directory
     * @param gameTitle   game title (used for exe scoring)
     * @param cachedRelPath previously resolved relative exe path (may be empty)
     * @return LaunchSpec, or null if the game doesn't appear to be installed
     */
    public static LaunchSpec buildLaunchSpec(String installDir, String gameTitle,
                                              String cachedRelPath) {
        if (installDir == null || installDir.isEmpty()) {
            return null;
        }
        File dir = new File(installDir);
        if (!dir.exists()) {
            Log.e(TAG, "Install dir does not exist: " + installDir);
            return null;
        }

        // ── Step 1: Try fuel.json ──────────────────────────────────────────
        String fuelCommand    = null;
        String fuelWorkingDir = null;
        List<String> fuelArgs = new ArrayList<>();

        File fuelFile = new File(dir, "fuel.json");
        if (fuelFile.exists()) {
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new FileReader(fuelFile))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                JSONObject main = json.optJSONObject("Main");
                if (main != null) {
                    String cmd = main.optString("Command", "").trim();
                    if (!cmd.isEmpty()) fuelCommand = cmd;

                    String wd = main.optString("WorkingSubdirOverride", "").trim();
                    if (!wd.isEmpty()) fuelWorkingDir = wd;

                    JSONArray argsArr = main.optJSONArray("Args");
                    if (argsArr != null) {
                        for (int i = 0; i < argsArr.length(); i++) {
                            String a = argsArr.optString(i);
                            if (a != null && !a.isEmpty()) fuelArgs.add(a);
                        }
                    }
                }
                Log.d(TAG, "fuel.json: command=" + fuelCommand
                        + " workDir=" + fuelWorkingDir
                        + " args=" + fuelArgs.size());
            } catch (Exception e) {
                Log.w(TAG, "fuel.json parse failed (falling through to heuristic)", e);
            }
        }

        // ── Step 2: Resolve exe relative path ─────────────────────────────
        String resolvedRelPath = cachedRelPath;
        if (resolvedRelPath == null || resolvedRelPath.isEmpty()) {
            if (fuelCommand != null) {
                resolvedRelPath = fuelCommand.replace('\\', '/');
            } else {
                // Exe scoring heuristic
                File chosen = choosePrimaryExe(dir, gameTitle);
                if (chosen == null) {
                    Log.e(TAG, "No exe found in: " + installDir);
                    return null;
                }
                // relative path from installDir
                resolvedRelPath = relativePath(installDir, chosen.getAbsolutePath());
            }
        }

        // ── Step 3: Build A: drive Wine path ───────────────────────────────
        String winPath = resolvedRelPath.replace('/', '\\');
        String amazonCommand = "A:\\" + winPath;

        // ── Step 4: Working directory ──────────────────────────────────────
        String workDir;
        boolean hasFuelCmd = fuelCommand != null && !fuelCommand.isEmpty();
        boolean hasFuelWd  = fuelWorkingDir != null && !fuelWorkingDir.isEmpty();
        boolean relPathIsFuelCmd = hasFuelCmd
                && resolvedRelPath.replace('\\', '/')
                   .equals(fuelCommand.replace('\\', '/'));

        if (hasFuelWd && relPathIsFuelCmd) {
            workDir = installDir + "/" + fuelWorkingDir.replace('\\', '/');
        } else {
            int lastSlash = resolvedRelPath.lastIndexOf('/');
            String exeDir = (lastSlash > 0) ? resolvedRelPath.substring(0, lastSlash) : "";
            workDir = exeDir.isEmpty() ? installDir : (installDir + "/" + exeDir);
        }

        // ── Step 5: Build final launch command ────────────────────────────
        StringBuilder cmd = new StringBuilder("winhandler.exe \"");
        cmd.append(amazonCommand).append("\"");
        if (!fuelArgs.isEmpty()) {
            for (String arg : fuelArgs) {
                cmd.append(" ");
                if (arg.contains(" ")) {
                    cmd.append("\"").append(arg).append("\"");
                } else {
                    cmd.append(arg);
                }
            }
        }

        LaunchSpec spec = new LaunchSpec();
        spec.command         = cmd.toString();
        spec.workingDir      = workDir;
        spec.exeRelativePath = resolvedRelPath;
        Log.d(TAG, "Launch command: " + spec.command);
        Log.d(TAG, "Working dir:    " + spec.workingDir);
        return spec;
    }

    /**
     * Returns the 5 FuelPump environment variable entries as "KEY=VALUE" strings.
     * Caller injects these into the Wine/winhandler environment.
     *
     * FUEL_DIR and AMAZON_GAMES_SDK_PATH use C:\ProgramData Wine path.
     */
    public static String[] buildFuelEnv(AmazonGame game) {
        String configPath = "C:\\ProgramData";
        return new String[]{
            "FUEL_DIR=" + configPath + "\\Amazon Games Services\\Legacy",
            "AMAZON_GAMES_SDK_PATH=" + configPath + "\\Amazon Games Services\\AmazonGamesSDK",
            "AMAZON_GAMES_FUEL_ENTITLEMENT_ID=" + game.entitlementId,
            "AMAZON_GAMES_FUEL_PRODUCT_SKU=" + game.productSku,
            "AMAZON_GAMES_FUEL_DISPLAY_NAME=Player"
        };
    }

    // ── Exe scoring heuristic (Java port of ExecutableSelectionUtils.kt) ──────

    private static final Pattern UE_SHIPPING =
            Pattern.compile(".*-win(32|64)(-shipping)?\\.exe$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UE_BINARIES =
            Pattern.compile(".*/binaries/win(32|64)/.*\\.exe$", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_NAME =
            Pattern.compile("^[a-z]\\d{1,3}\\.exe$", Pattern.CASE_INSENSITIVE);

    private static final String[] NEGATIVE_KEYWORDS = {
        "crash", "handler", "viewer", "compiler", "tool",
        "setup", "unins", "eac", "launcher", "steam"
    };

    private static boolean isLikelyStub(File f) {
        String n = f.getName().toLowerCase();
        if (GENERIC_NAME.matcher(n).matches()) return true;
        if (f.length() < 1_000_000L) return true;
        for (String kw : NEGATIVE_KEYWORDS) if (n.contains(kw)) return true;
        return false;
    }

    static int scoreExe(File f, String gameNameLower) {
        int score = 50; // base
        String path = f.getAbsolutePath().replace('\\', '/').toLowerCase();
        if (UE_SHIPPING.matcher(path).matches())        score += 300;
        if (UE_BINARIES.matcher(path).find())           score += 250;
        if (!path.contains("/"))                        score += 200;
        String fn = f.getName().toLowerCase();
        String cleanGame = gameNameLower.replaceAll("[^a-z]", "");
        String cleanFile = fn.replaceAll("[^a-z]", "");
        boolean nameMatch = path.contains(gameNameLower)
                || (cleanGame.length() >= 5 && cleanFile.length() >= 5
                    && cleanGame.substring(0, 5).equals(cleanFile.substring(0, 5)));
        if (nameMatch) score += 100;
        for (String kw : NEGATIVE_KEYWORDS) if (path.contains(kw)) { score -= 150; break; }
        if (GENERIC_NAME.matcher(fn).matches()) score -= 200;
        return score;
    }

    static File choosePrimaryExe(File installDir, String gameTitle) {
        if (!installDir.isDirectory()) return null;

        // Collect all .exe files
        List<File> all = new ArrayList<>();
        collectExe(installDir, all);
        if (all.isEmpty()) return null;

        // Filter stubs; fall back to full pool if all are stubs
        List<File> pool = new ArrayList<>();
        for (File f : all) if (!isLikelyStub(f)) pool.add(f);
        if (pool.isEmpty()) pool = all;

        String lowerTitle = gameTitle.toLowerCase();
        File best = null;
        int bestScore = Integer.MIN_VALUE;
        for (File f : pool) {
            int s = scoreExe(f, lowerTitle);
            if (best == null || s > bestScore
                    || (s == bestScore && f.length() > best.length())) {
                best      = f;
                bestScore = s;
            }
        }
        return best;
    }

    static void collectExe(File dir, List<File> out) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File f : entries) {
            if (f.isDirectory()) {
                collectExe(f, out);
            } else if (f.getName().toLowerCase().endsWith(".exe")) {
                out.add(f);
            }
        }
    }

    private static String relativePath(String base, String absolute) {
        if (!base.endsWith("/")) base += "/";
        if (absolute.startsWith(base)) {
            return absolute.substring(base.length());
        }
        return absolute;
    }
}
