package com.winlator.star.store;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clean-room client for GOG's unauthenticated <b>dependencies repository</b> (the redistributables
 * catalog: VC++ / .NET / DirectX / … runtimes GOG games need). Reconstructed from the live protocol
 * (probed read-only 2026-08-22), NOT from GameNative's GPL-3.0 GOG code.
 *
 * Protocol (all unauthenticated, no secure_link):
 * <pre>
 *   1. GET content-system.gog.com/dependencies/repository?generation=2
 *        → { "repository_manifest": "<meta url>", "build_id": "...", "generation": 2 }
 *   2. GET repository_manifest (zlib) → { "depots": [ {dependencyId, readableName,
 *        executable{path,arguments}, internal, osBitness, manifest(hash), size}, … ] }  (~68 entries)
 *   3. per-dependency depot manifest (zlib) at
 *        gog-cdn-fastly.gog.com/content-system/v2/dependencies/meta/<h0:2>/<h2:4>/<h>
 *        → same shape as a game depot manifest (items[].chunks[]).
 *   4. chunk blobs at .../dependencies/store/<cMd5 fan-out> — reassembled by the shared
 *        {@link GogDownloadManager#assembleDependencyInstaller} (same MD5-verified gen2 path).
 * </pre>
 *
 * All methods run on the calling thread — call from a background thread.
 */
public final class GogDependencyRepository {

    private static final String TAG = "BH_GOG_DEP";

    private static final String REPO_INDEX =
            "https://content-system.gog.com/dependencies/repository?generation=2";
    private static final String META_BASE =
            "https://gog-cdn-fastly.gog.com/content-system/v2/dependencies/meta/";
    static final String STORE_BASE =
            "https://gog-cdn-fastly.gog.com/content-system/v2/dependencies/store";

    private GogDependencyRepository() {}

    /** One repository redist entry (a "dependency" in GOG's terms). */
    public static final class DepEntry {
        public final String id;
        public final String readableName;
        /** {@code executable.path}, e.g. {@code __redist/MSVC2017_x64/VC_redist.x64.exe}. */
        public final String exePath;
        /** Silent-install flags shipped IN the manifest, e.g. {@code /install /quiet /norestart}. */
        public final String arguments;
        /** GOG-internal deps (ISI/SuspendLauncher/…) — never treated as user redists. */
        public final boolean internal;
        /** e.g. ["64"], ["32"], or ["32","64"]. */
        public final List<String> osBitness;
        /** depot-manifest hash for step 3. */
        public final String manifestHash;
        public final long size;

        DepEntry(String id, String readableName, String exePath, String arguments,
                 boolean internal, List<String> osBitness, String manifestHash, long size) {
            this.id = id;
            this.readableName = readableName;
            this.exePath = exePath;
            this.arguments = arguments;
            this.internal = internal;
            this.osBitness = osBitness;
            this.manifestHash = manifestHash;
            this.size = size;
        }

        /** True when this installer is an .msi (→ install_msi / msiexec), else run the .exe. */
        public boolean isMsi() {
            return exePath != null && exePath.toLowerCase().endsWith(".msi");
        }

        /** Basename of the staged installer, e.g. {@code VC_redist.x64.exe}. */
        public String fileName() {
            if (exePath == null || exePath.isEmpty()) return id + ".exe";
            String p = exePath.replace("\\", "/");
            int slash = p.lastIndexOf('/');
            return slash >= 0 ? p.substring(slash + 1) : p;
        }
    }

    // In-process cache (repository is ~40 KB; drift is handled by never pinning across sessions).
    private static Map<String, DepEntry> cachedRepo;
    private static String cachedBuildId;

    /** The repository {@code build_id} from the last {@link #fetchRepository} (for logging), or null. */
    public static String lastBuildId() { return cachedBuildId; }

    /**
     * Fetches + inflates the repository manifest into a {@code dependencyId → DepEntry} map.
     * Cached in-process for the session. Returns an empty map on failure (fail-soft).
     */
    public static synchronized Map<String, DepEntry> fetchRepository() {
        if (cachedRepo != null) return cachedRepo;
        Map<String, DepEntry> out = new LinkedHashMap<>();
        try {
            String indexJson = GogDownloadManager.httpGet(REPO_INDEX, null);
            if (indexJson == null) { Log.w(TAG, "repository index fetch null"); return out; }
            JSONObject index = new JSONObject(indexJson);
            cachedBuildId = index.optString("build_id", null);
            String manifestUrl = index.optString("repository_manifest", null);
            if (manifestUrl == null || manifestUrl.isEmpty()) return out;

            byte[] raw = GogDownloadManager.fetchBytes(manifestUrl, null);
            if (raw == null) { Log.w(TAG, "repository manifest fetch null"); return out; }
            String manifestStr = GogDownloadManager.decompressBytes(raw);
            if (manifestStr == null) return out;

            JSONArray depots = new JSONObject(manifestStr).optJSONArray("depots");
            if (depots == null) return out;
            for (int i = 0; i < depots.length(); i++) {
                JSONObject d = depots.optJSONObject(i);
                if (d == null) continue;
                String id = d.optString("dependencyId", null);
                if (id == null || id.isEmpty()) continue;
                JSONObject exe = d.optJSONObject("executable");
                String exePath = exe != null ? exe.optString("path", "") : "";
                String args = exe != null ? exe.optString("arguments", "") : "";
                List<String> bits = new ArrayList<>();
                JSONArray ob = d.optJSONArray("osBitness");
                if (ob != null) for (int b = 0; b < ob.length(); b++) bits.add(ob.optString(b));
                out.put(id, new DepEntry(
                        id,
                        d.optString("readableName", id),
                        exePath,
                        args,
                        d.optBoolean("internal", false),
                        bits,
                        d.optString("manifest", ""),
                        d.optLong("size", 0)));
            }
            cachedRepo = out;
            Log.d(TAG, "repository loaded: " + out.size() + " deps build_id=" + cachedBuildId);
        } catch (Exception e) {
            Log.w(TAG, "fetchRepository failed: " + e);
        }
        return out;
    }

    /**
     * Resolves {@code entry}'s depot manifest and assembles its installer into {@code destDir}
     * via the shared MD5-verified chunk path. Returns the assembled installer file, or null on
     * any failure (fail-soft — the caller logs and lets the game proceed).
     *
     * Skips {@code internal:true} entries (ISI etc. are handled by the P2 scriptInterpreter path).
     */
    public static File downloadRedist(DepEntry entry, File destDir, AtomicBoolean cancelled,
                                      ConcurrentLinkedQueue<String> log) {
        if (entry == null) return null;
        if (entry.internal) { log.add("skip internal dep " + entry.id); return null; }
        if (entry.manifestHash == null || entry.manifestHash.isEmpty()) {
            log.add("dep " + entry.id + " has no manifest hash");
            return null;
        }
        try {
            String metaUrl = META_BASE + GogDownloadManager.buildCdnPath(entry.manifestHash);
            byte[] raw = GogDownloadManager.fetchBytes(metaUrl, null);
            if (raw == null) { log.add("dep meta fetch null " + entry.id); return null; }
            String manifestStr = GogDownloadManager.decompressBytes(raw);
            if (manifestStr == null) { log.add("dep meta decompress fail " + entry.id); return null; }
            destDir.mkdirs();
            File installer = GogDownloadManager.assembleDependencyInstaller(
                    manifestStr, STORE_BASE, destDir, entry.exePath, cancelled, log);
            if (installer == null || !installer.isFile()) {
                log.add("dep assembly failed " + entry.id);
                return null;
            }
            return installer;
        } catch (Exception e) {
            log.add("downloadRedist exception " + entry.id + ": " + e);
            return null;
        }
    }
}
