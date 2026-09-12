/*
 * Epic Online Services launch-argument injection for Bannerlator.
 *
 * Credits: Java port of the EOS launch-args work by The GameNative Team
 * (https://github.com/utkarshdalal/GameNative). Based on PR #1286 / commit
 * cbea7f7 ("Feat/eos overlay utkarsh"), which introduced -EpicPortal,
 * -epicusername, -epicuserid, -epicsandboxid, -epiclocale, -epicdeploymentid
 * plus the -AUTH_LOGIN / -AUTH_PASSWORD / -AUTH_TYPE exchange-code triple.
 * Massive thanks to utkarshdalal and the GameNative contributors.
 *
 * This file ships Phase 1 (online auth handshake) and Phase 2 (ownership token
 * -epicovt, gated by Denuvo detection). Phase 3 (in-game EOS overlay for friends /
 * notifications / achievements) now lives in-tree via {@link EpicOverlayManager},
 * but is dark-launched behind {@code FeatureFlags.EPIC_OVERLAY_ENABLED} until the
 * DXVK-based wrapper it renders through lands.
 *
 * Phase 2 is a Java port of GameNative's EpicGameLauncher ownership-token flow
 * (saveOwnershipTokenToFile / -epicovt / -epicapp / -epicenv) + EpicAuthManager /
 * EpicAuthClient.getOwnershipToken. Licensed under GPL-3.0, matching the
 * GameNative-derived Epic sources.
 *
 * Reference: https://github.com/utkarshdalal/GameNative/commit/cbea7f70be46e6f4a99a7e92db13c9b96add9c1c
 * Reference: https://github.com/utkarshdalal/GameNative — service/epic/EpicGameLauncher.kt
 */
package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import com.winlator.star.container.Shortcut;
import com.winlator.star.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Builds the Epic Online Services launch-argument string that is appended to the
 * Wine command line for games installed via Bannerlator's Epic store integration.
 *
 * Phase 1 of EOS support: enables online auth so EOS-integrated games can
 * connect to the real Epic Online Services (multiplayer, friends, achievements)
 * against the game's own EOSSDK and the user's logged-in Epic account. This is
 * NOT an emulator — the args hand the title a real, freshly-minted exchange code.
 *
 * Per-game scoped via the shortcut's [Extra Data] block:
 *   {@code storeSource=epic}, {@code epicAppName}, {@code epicSandboxId},
 *   {@code epicCatalogId}, {@code epicEos} (1/0 toggle).
 *
 * Lookup chain:
 *   1. Read appName / sandboxId (namespace) / catalogId from the shortcut extras.
 *   2. Read displayName + accountId from {@link EpicCredentialStore}.
 *   3. Read cached deploymentId via {@link EpicSidecar#getCachedDeploymentId}.
 *   4. If the deploymentId cache is empty/stale, fire an async refresh — next launch will have it.
 *   5. Fetch a FRESH exchange code synchronously and emit the AUTH triple.
 *
 * If any required lookup fails (missing extras, no login, etc.) the method
 * silently returns "" — a no-op that leaves the launch command unchanged.
 * Pre-existing Epic installs (no shortcut extras stamped) therefore keep their
 * existing behaviour.
 */
public final class EpicLaunchArgs {

    private static final String TAG = "BH_EPIC_LAUNCH";

    private EpicLaunchArgs() {}

    /**
     * Builds a space-joined Epic launch-argument string for {@code shortcut}, or
     * "" if this is not an EOS-enabled Epic shortcut / any lookup fails.
     *
     * Runs synchronous network fetches (exchange code, ~8s timeout); callers MUST
     * invoke this off the main thread — it is called from the background launch
     * worker in XServerDisplayActivity.
     */
    public static String buildArgString(Context ctx, Shortcut shortcut) {
        if (ctx == null || shortcut == null) return "";
        try {
            String appName   = shortcut.getExtra("epicAppName", "");
            String namespace = shortcut.getExtra("epicSandboxId", "");
            String catalogId = shortcut.getExtra("epicCatalogId", "");

            // Backfill guard: pre-existing Epic installs won't have these extras
            // stamped on the shortcut. Silent no-op rather than crash the launch.
            if (appName.isEmpty() || namespace.isEmpty()) {
                Log.w(TAG, "missing epicAppName/epicSandboxId on shortcut; skipping Epic launch args");
                return "";
            }

            // Identity from EpicCredentialStore (same store the Epic UI uses).
            EpicCredentialStore.Credentials creds = EpicCredentialStore.load(ctx);
            String displayName = (creds != null && creds.displayName != null && !creds.displayName.isEmpty())
                    ? creds.displayName : "EpicUser";
            String accountId   = (creds != null && creds.accountId != null && !creds.accountId.isEmpty())
                    ? creds.accountId : "0";

            // deploymentId from cache; lazy async refresh on miss (affects NEXT launch).
            String deploymentId = EpicSidecar.getCachedDeploymentId(ctx, appName);
            if (deploymentId.isEmpty()) {
                EpicSidecar.refreshAsync(ctx, namespace, catalogId, appName);
            }

            // Locale (Feature #11): derive the 2-letter code from the container/shortcut language
            // (lc_all) → device locale → "en", instead of a hardcoded "en".
            String locale = EpicInstallTags.INSTANCE.localeCodeForCurrentContainer(ctx);
            if (locale == null || locale.isEmpty()) locale = "en";

            StringBuilder sb = new StringBuilder();
            sb.append("-EpicPortal");
            // -epicusername may contain spaces → quote it. Other IDs are ASCII-safe.
            sb.append(" -epicusername=\"").append(sanitize(displayName)).append("\"");
            sb.append(" -epicuserid=").append(sanitize(accountId));
            sb.append(" -epicsandboxid=").append(sanitize(namespace));
            sb.append(" -epiclocale=").append(sanitize(locale));
            // -epicapp / -epicenv are identity/portal args every EOS title expects — emit them on
            // EVERY Epic launch (Feature #11), not just the Denuvo ownership-token path.
            sb.append(" -epicapp=").append(sanitize(appName));
            sb.append(" -epicenv=Prod");
            if (!deploymentId.isEmpty()) {
                sb.append(" -epicdeploymentid=").append(sanitize(deploymentId));
            }

            // ── Auth mode (Feature #5: offline launch) ──────────────────────────
            // Online: fetch a FRESH exchange code (expires ~5min) and emit the AUTH triple. Without
            // it, modern EOS titles show "No exchange code was found, please launch from the Epic
            // Games Launcher". Offline: when the shortcut forces it (epicOffline=1) OR no exchange
            // code is available (no/expired token, fetch failed), skip the AUTH triple entirely and
            // emit a clean identity/portal-only arg set rather than empty/garbage AUTH values.
            boolean forceOffline = "1".equals(shortcut.getExtra("epicOffline"));
            String exchangeCode = forceOffline ? null : EpicSidecar.fetchExchangeCodeSync(ctx);
            boolean offline = forceOffline || exchangeCode == null || exchangeCode.isEmpty();
            if (!offline) {
                sb.append(" -AUTH_LOGIN=unused");
                sb.append(" -AUTH_PASSWORD=").append(sanitize(exchangeCode));
                sb.append(" -AUTH_TYPE=exchangecode");
                Log.i(TAG, "Epic launch mode ONLINE for " + appName);
            } else {
                Log.i(TAG, "Epic launch mode OFFLINE for " + appName
                        + (forceOffline ? " (forced via epicOffline)" : " (no exchange code available)"));
            }

            // ── Phase 2: Denuvo ownership token (-epicovt) ──────────────────────
            // Additive and Denuvo-gated: EOS games wrapped in Denuvo reject the
            // exchange-code auth above unless the game is also handed a signed
            // ownership-verification token proving the launching Epic account owns
            // it. Non-Denuvo EOS games take none of this path — their launch string
            // is exactly the Phase 1 output above (device-proven, unchanged).
            appendOwnershipTokenArgs(ctx, shortcut, appName, namespace, catalogId, accountId, sb);

            // ── Feature #6: AdditionalCommandLine catalog args ──────────────────
            // Append any extra launch flags Epic ships for this title (verbatim, after our args).
            // Read from cache; on a miss/stale entry fire an async refresh (affects NEXT launch) —
            // same lazy pattern as the deployment id. Control chars are stripped defensively.
            appendAdditionalCommandLine(ctx, appName, namespace, catalogId, sb);

            Log.i(TAG, "built Epic launch args for " + appName
                    + " (deploymentId=" + (deploymentId.isEmpty() ? "<none>" : deploymentId)
                    + ", mode=" + (offline ? "offline" : "online") + ")");
            return sb.toString();
        } catch (Throwable t) {
            // Defensive: never let a bug here break game launches.
            Log.w(TAG, "buildArgString failed", t);
            return "";
        }
    }

    /**
     * Feature #6: append the game's Epic-provided AdditionalCommandLine flags verbatim (after our
     * own args). Read from cache; on a miss/stale entry fire an async refresh so the NEXT launch
     * has it. Control characters are stripped (the value is Epic-provided but we don't trust it
     * blindly on the Wine command line); an empty value is skipped.
     */
    private static void appendAdditionalCommandLine(Context ctx, String appName, String namespace,
                                                    String catalogId, StringBuilder sb) {
        try {
            String cached = EpicSidecar.getCachedAdditionalCommandLine(ctx, appName);
            if (cached == null) {
                // Unset/stale — refresh in the background for next time; nothing to append now.
                EpicSidecar.refreshAdditionalCommandLineAsync(ctx, namespace, catalogId, appName);
                return;
            }
            String extra = sanitizeCommandLine(cached);
            if (!extra.isEmpty()) {
                sb.append(' ').append(extra);
                Log.i(TAG, "appended AdditionalCommandLine for " + appName + ": " + extra);
            }
        } catch (Throwable t) {
            Log.w(TAG, "appendAdditionalCommandLine failed for " + appName, t);
        }
    }

    /**
     * Strip control characters (newlines, tabs, NULs, and other C0/DEL bytes) from an Epic-provided
     * command-line fragment and trim surrounding whitespace, leaving inner spacing between flags
     * intact. Guards against a malformed/hostile catalog value corrupting the Wine command line.
     */
    private static String sanitizeCommandLine(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7F) out.append(c); // drop C0 controls + DEL
        }
        return out.toString().trim();
    }

    /**
     * Strip newlines / null bytes that could corrupt the Wine command line. Epic
     * IDs are plain ASCII; display names may contain spaces (handled by quoting
     * -epicusername) but must not contain control characters.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\n", "").replace("\r", "").replace("\0", "");
    }

    /** Directory (inside the wine prefix) that holds ownership-token .ovt files. */
    private static final String EPIC_TOKENS_SUBDIR = "drive_c/users/Public/Documents/EpicTokens";
    /** Windows-side path to the same directory (dosdevices C: → drive_c). */
    private static final String EPIC_TOKENS_WIN = "C:\\users\\Public\\Documents\\EpicTokens\\";

    /**
     * Denuvo-gated Phase 2. When (and only when) {@code shortcut} is Denuvo-protected,
     * mint a fresh signed ownership token, drop it into the container's wine prefix at
     * {@code drive_c/users/Public/Documents/EpicTokens/<appName>.ovt}, and append
     * {@code -epicovt=<winPath> -epicapp=<appName> -epicenv=Prod} to {@code sb}.
     *
     * Non-Denuvo games (or any failure: no token, unwritable prefix) leave {@code sb}
     * untouched — the Phase 1 launch string is emitted verbatim.
     *
     * Runs on the background launch worker (network fetch + a one-time binary scan on
     * first launch), so blocking here is ANR-safe.
     */
    private static void appendOwnershipTokenArgs(Context ctx, Shortcut shortcut, String appName,
                                                 String namespace, String catalogId,
                                                 String accountId, StringBuilder sb) {
        try {
            // Compute-once Denuvo flag (sync scan on first launch; cached read after).
            // A per-shortcut manual override (epicOvtForce=1) forces the ownership-token
            // path regardless of the auto-detector — for obfuscated Denuvo exes the
            // literal-"Denuvo"-string scan misses (e.g. LEGO 2K Drive).
            boolean denuvo = DenuvoDetector.scanIfNeededSync(ctx, shortcut);
            boolean forced = "1".equals(shortcut.getExtra("epicOvtForce"));
            if (!denuvo && !forced) return; // non-Denuvo EOS game, override off → Phase 1 only, unchanged.

            if (catalogId == null || catalogId.isEmpty()) {
                Log.w(TAG, "Denuvo game " + appName + " missing epicCatalogId; skipping ownership token");
                return;
            }

            // Feature #10: read the cached ownership-token hex first (25-min TTL); only mint a fresh
            // token over the network when the cache is missing/expired. Avoids burning the per-game
            // rate limit on repeat Denuvo launches. (-epicapp/-epicenv are emitted unconditionally in
            // the main builder now, so this path only appends -epicovt.)
            byte[] tokenBytes;
            String cachedHex = EpicSidecar.getCachedOwnershipTokenHex(ctx, appName);
            byte[] cachedBytes = (cachedHex != null) ? EpicSidecar.hexToBytes(cachedHex) : null;
            if (cachedBytes != null && cachedBytes.length > 0) {
                tokenBytes = cachedBytes;
                Log.i(TAG, "using cached ownership token for " + appName + " (" + tokenBytes.length + " bytes)");
            } else {
                tokenBytes = EpicSidecar.fetchOwnershipTokenSync(ctx, accountId, namespace, catalogId);
                if (tokenBytes == null || tokenBytes.length == 0) {
                    Log.w(TAG, "ownership token fetch returned nothing for " + appName + "; launching without -epicovt");
                    return;
                }
                EpicSidecar.saveOwnershipTokenHex(ctx, appName, EpicSidecar.bytesToHex(tokenBytes));
            }

            String fileName = sanitizeFilename(appName) + ".ovt";
            File tokenDir = new File(ImageFs.find(ctx).wineprefix, EPIC_TOKENS_SUBDIR);
            if (!tokenDir.exists() && !tokenDir.mkdirs()) {
                Log.w(TAG, "failed to create EpicTokens dir at " + tokenDir.getAbsolutePath());
                return;
            }
            File tokenFile = new File(tokenDir, fileName);
            // Write the token file each launch (from cache or fresh mint) — token is short-lived.
            try (FileOutputStream fos = new FileOutputStream(tokenFile, false)) {
                fos.write(tokenBytes);
            }

            String winPath = EPIC_TOKENS_WIN + fileName;
            sb.append(" -epicovt=").append(winPath);
            Log.i(TAG, "Denuvo ownership token written for " + appName + " → " + tokenFile.getAbsolutePath()
                    + " (wine: " + winPath + ")");
        } catch (Throwable t) {
            // Never let the Denuvo path break a launch — fall back to Phase 1 args.
            Log.w(TAG, "appendOwnershipTokenArgs failed for " + appName, t);
        }
    }

    /**
     * Delete this game's ownership-token .ovt from the wine prefix on session exit.
     * The token is sensitive + short-lived; the prefix lives in app-private storage,
     * but we still remove the artifact once the game has stopped. No-op when the file
     * is absent or the shortcut isn't an EOS Epic game. Best-effort; never throws.
     */
    public static void cleanupOwnershipToken(Context ctx, Shortcut shortcut) {
        if (ctx == null || shortcut == null) return;
        try {
            if (!"epic".equals(shortcut.getExtra("storeSource"))) return;
            String appName = shortcut.getExtra("epicAppName", "");
            if (appName.isEmpty()) return;
            File tokenFile = new File(new File(ImageFs.find(ctx).wineprefix, EPIC_TOKENS_SUBDIR),
                    sanitizeFilename(appName) + ".ovt");
            if (tokenFile.exists() && tokenFile.delete()) {
                Log.i(TAG, "deleted ownership token for " + appName);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cleanupOwnershipToken failed", t);
        }
    }

    /** Replace filesystem-unsafe characters so an appName is a safe .ovt basename. */
    private static String sanitizeFilename(String s) {
        if (s == null || s.isEmpty()) return "epicgame";
        String cleaned = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return cleaned.isEmpty() ? "epicgame" : cleaned;
    }
}
