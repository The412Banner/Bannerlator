package com.winlator.star.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app data backup (app_data_backup.tar in the storage root, readable by any app with storage access)
 * without the saved store logins. Containers, game saves and settings are kept.
 */
public final class AppDataBackup {
    /** Files and folders that hold nothing but a login, relative to the data folder. */
    private static final String[] LOGIN_PATHS = {
            "shared_prefs/steam_accounts.xml",  // SteamAccountStore: saved Steam accounts and their tokens
            "shared_prefs/banner_account.xml",  // communityconfigs.AccountManager: the session
            "files/amazon/credentials.json",    // AmazonCredentialStore
            "files/xbox/account",               // XboxAccountStore
    };

    /** Token entries of preference files that also hold settings; the rest of each file is kept. */
    private static final String[][] TOKEN_KEYS = {
            {"shared_prefs/steam_prefs.xml", "refresh_token"},                    // SteamPrefs
            {"shared_prefs/bh_epic_prefs.xml", "access_token", "refresh_token"},  // EpicCredentialStore
            {"shared_prefs/bh_gog_prefs.xml", "access_token", "refresh_token"},   // GogLoginActivity, GogTokenRefresh
    };

    private AppDataBackup() {}

    public static void archive(File dataDir, File destination) {
        String base = dataDir.getAbsolutePath() + File.separator;
        Map<String, byte[]> replaced = new HashMap<>();
        Set<String> dropped = new HashSet<>();
        for (String[] spec : TOKEN_KEYS) {
            File prefs = new File(dataDir, spec[0]);
            if (!prefs.isFile()) continue;
            String kept = null;
            try {
                kept = withoutKeys(new String(Files.readAllBytes(prefs.toPath()), StandardCharsets.UTF_8), spec);
            }
            catch (IOException e) {}
            // A file whose token could not be taken out is left out whole.
            if (kept != null) replaced.put(spec[0], kept.getBytes(StandardCharsets.UTF_8));
            else dropped.add(spec[0]);
        }
        TarCompressorUtils.archive(new File[]{dataDir}, destination, file -> {
            String path = file.getAbsolutePath();
            if (path.contains("imagefs/tmp/.sysvshm")) return false;
            String relative = path.startsWith(base) ? path.substring(base.length()) : "";
            // SharedPreferences keeps a .bak copy of a file while it writes it, tokens included.
            boolean bak = relative.endsWith(".xml.bak");
            String target = bak ? relative.substring(0, relative.length() - 4) : relative;
            return !isLogin(target) && !dropped.contains(target) && !(bak && replaced.containsKey(target));
        }, file -> {
            String path = file.getAbsolutePath();
            return path.startsWith(base) ? replaced.get(path.substring(base.length())) : null;
        });
    }

    /** [relative] (to the data folder) is a login file: a store login, or a WebView's sign-in cookies. */
    static boolean isLogin(String relative) {
        for (String path : LOGIN_PATHS) {
            if (relative.equals(path) || relative.startsWith(path + "/")) return true;
        }
        if (relative.startsWith("app_webview")) {
            String name = relative.substring(relative.lastIndexOf('/') + 1);
            return name.equals("Cookies") || name.equals("Cookies-journal");
        }
        return false;
    }

    /** SharedPreferences XML without the string entries named [spec][1..]; null when one is still there. */
    static String withoutKeys(String xml, String[] spec) {
        for (int i = 1; i < spec.length; i++) {
            Matcher m = Pattern.compile("[ \\t]*<string name=\"" + Pattern.quote(spec[i]) + "\">[^<]*</string>[ \\t]*\\r?\\n?").matcher(xml);
            xml = m.replaceAll("");
            if (xml.contains("name=\"" + spec[i] + "\"")) return null;
        }
        return xml;
    }
}
