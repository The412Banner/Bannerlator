package com.winlator.star.contents;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.winlator.star.container.Container;
import com.winlator.star.container.ContainerManager;
import com.winlator.star.container.Shortcut;
import com.winlator.star.core.StreamUtils;
import com.winlator.star.core.StringUtils;
import com.winlator.star.core.TarCompressorUtils;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Step 1 of the Wrapper Version Manager (issue #132) — a fixed-slot updater for our bundled
 * graphics wrappers, mirroring WinlatorMali's ManageGraphicsDriversFragment.
 *
 * There are 6 updatable slots (see {@link #SLOTS}). For each, the user may install an override
 * (a newer .tzst of the SAME name) or reset back to the bundled asset. An override for slot
 * {@code X.tzst} is stored at {@code filesDir/graphics_driver/X.tzst} and, at game launch,
 * {@code XServerDisplayActivity.extractGraphicsDriverFiles()} prefers that file over the bundled
 * asset (per extracted asset file).
 *
 * Step 2 (issue #132) adds free-form IMPORTED wrappers on top of the fixed slots: import / delete
 * arbitrary wrappers ({@link #importWrapper} / {@link #deleteImported}), enumerate them
 * ({@link #enumerateImported}), feed them into the Graphics Driver dropdown ({@link #driverEntries},
 * shared by both read sites), and cascade a delete so no Container/Shortcut is left pointing at a
 * removed wrapper. Manifest-driven per-wrapper settings remain Step 3.
 */
public class WrapperManager {

    private static final String TAG = "WrapperManager";
    /** Subdir (under filesDir) that holds user override .tzst files. Mirrors the launcher path. */
    public static final String OVERRIDE_DIR_NAME = "graphics_driver";
    /** Bundled asset directory prefix. */
    private static final String ASSET_DIR = "graphics_driver/";
    /** Wrapper archives must ship this entry; validated before an override is accepted. */
    private static final String WRAPPER_MARKER_ENTRY = "usr/lib/libvulkan_wrapper.so";
    private static final String BCN_MARKER_ENTRY     = "usr/lib/libbcn_layer.so";
    /** DX12/compat implicit layer marker. Detected + persisted for the FUTURE compat UI (sparse
     *  binding / "Use GameNative engine") which lives on feat/mali-ultimate-driver, NOT this branch.
     *  We record hasCompatLayer in the .meta so the compat gate exists the moment that UI merges in;
     *  no compat controls are built (or activated) here — see step 5 deferral in XServerDisplayActivity. */
    private static final String COMPAT_MARKER_ENTRY  = "usr/lib/libdxvk_mali_compat_layer.so";

    // ---------------------------------------------------------------------------------------------
    // Step 3 Layer 1 (issue #132): env-var auto-detect ("strings" the wrapper binaries for the
    // env-var NAMES they reference, match them to WrapperSettingsDictionary, render + emit generically).
    // ---------------------------------------------------------------------------------------------

    /** The Winlator-family env-var vocabulary. A whole token must match to be recorded (anchored). */
    private static final Pattern ENV_KEY_PATTERN =
        Pattern.compile("^(WRAPPER|ENABLE|BCN|COMPAT|MESA_VK|GALLIUM|ADRENOTOOLS)_[A-Z0-9_]+$");
    /** Binaries scanned for env-var names — whichever the archive actually contains (union). */
    private static final String[] ENV_SCAN_ENTRIES = {
        "usr/lib/libvulkan_wrapper.so",
        "usr/lib/libbcn_layer.so",
        "usr/lib/libdxvk_mali_compat_layer.so",
    };
    /** Per-.so cap on uncompressed bytes read, so a huge binary can't hang the import worker. */
    private static final long ENV_SCAN_BYTE_CAP = 64L * 1024 * 1024;

    /**
     * Env-var names ALREADY driven by the curated {@code GraphicsDriverConfigDialog} controls and the
     * hardcoded XSDA graphics-driver emission. The auto-detect "Detected settings" section EXCLUDES
     * these (so it never duplicates an existing control), and generic emission SKIPS them (so it never
     * double-emits or fights the curated BCn / present-mode / GPU-spoof logic). Shared, single source of
     * truth, read from both the dialog (Kotlin) and XSDA (Java).
     */
    public static final Set<String> HANDLED_ENV_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "MESA_VK_WSI_PRESENT_MODE", "MESA_VK_WSI_DEBUG", "GALLIUM_DRIVER",
        "WRAPPER_VK_VERSION", "WRAPPER_EXTENSION_BLACKLIST", "WRAPPER_RESOURCE_TYPE",
        "WRAPPER_EMULATE_BCN", "WRAPPER_BCN_ASTC", "WRAPPER_USE_BCN_CACHE",
        "WRAPPER_DISABLE_PRESENT_WAIT", "WRAPPER_VMEM_MAX_SIZE", "WRAPPER_MAX_IMAGE_COUNT",
        "WRAPPER_DEVICE_NAME", "WRAPPER_DEVICE_ID", "WRAPPER_VENDOR_ID",
        "ENABLE_BCN_COMPUTE", "BCN_COMPUTE_AUTO", "BCN_TRANSCODE_TO_ETC2",
        "BCN_TRANSCODE_TO_ASTC", "BCN_COMPUTE_IMAGE_VIEW", "BCN_LAYER_LOG_LEVEL",
        "BCN_PROFILE_TRANSFERS"
    )));

    /**
     * Global debug / diagnostics ignore-list (issue #132). Env-var names that are pure plumbing —
     * logging, tracing, dumping, profiling, watermarks, headless swapchains, GPU sampling — are NEVER
     * surfaced as an editable user SETTING nor emitted at launch: they don't affect gameplay and only
     * add noise (and risk). Matched as an UPPERCASE substring so vendor-prefixed variants
     * (MESA_VK_TRACE_TRIGGER, *_LOG_LEVEL, BCN_DUMP_*, *_PROFILE, *_WATERMARK, *_HEADLESS, …) are all
     * caught. Shared, single source of truth: called from the Kotlin config dialog AND the Java XSDA
     * emission loop. NOTE: the read-only "Environment variables" list still shows these — only the
     * editable settings path filters. Never throws.
     */
    private static final String[] DEBUG_ENV_MARKERS = {
        "LOG_LEVEL", "LOG_FILE", "_TRACE", "TRACE_", "_DEBUG", "DEBUG_", "DIAG", "_DUMP", "DUMP_",
        "PROFILE", "WATERMARK", "MARK_BCN", "HEADLESS", "SAMPLE_GPU", "COUNTERS"
    };

    /** True if {@code key} is debug/diagnostics plumbing (see {@link #DEBUG_ENV_MARKERS}); such keys are
     *  never shown as a setting nor emitted. Callable from Kotlin UI + Java XSDA. Never throws. */
    public static boolean isDebugEnvKey(String key) {
        if (key == null || key.isEmpty()) return false;
        String up = key.toUpperCase(Locale.ROOT);
        for (String marker : DEBUG_ENV_MARKERS) if (up.contains(marker)) return true;
        return false;
    }

    /** Curated driver/loader-namespace keys we DO expose (allow-listed back past the prefix filter). */
    private static final Set<String> DRIVER_INTERNAL_ALLOW = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "MESA_VK_VERSION_OVERRIDE", "MESA_VK_WSI_PRESENT_MODE", "GALLIUM_DRIVER"
    )));

    /** Namespaces owned by the bundled GPU driver / loader / compiler / profiler, not the wrapper author.
     *  (NIR_ = Mesa shader compiler, XDG_ = freedesktop dirs, HWCPIPE = ARM hardware-counter profiling —
     *  all confirmed present as getenv() reads in the fork binaries but never user-facing knobs.) */
    private static final String[] DRIVER_INTERNAL_PREFIXES = {
        "MESA_", "GALLIUM_", "DRI_", "ADRENOTOOLS_", "TU_", "RADV_", "ZINK_", "LIBGL_", "VK_", "EGL_",
        "NIR_", "XDG_", "HWCPIPE"
    };

    /** Standard system/process environment variables a wrapper reads to locate itself or the host — the
     *  binary calls getenv() on them, but they are the launch environment, NOT wrapper settings, so they
     *  must never be surfaced as editable knobs nor generically emitted. */
    private static final Set<String> SYSTEM_ENV_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "HOME", "PREFIX", "PATH", "TMPDIR", "TMP", "TEMP", "USER", "LOGNAME", "LANG", "LC_ALL",
        "LD_LIBRARY_PATH", "LD_PRELOAD", "DISPLAY", "WAYLAND_DISPLAY", "PWD", "SHELL", "TERM", "HOSTNAME"
    )));

    /**
     * True if {@code key} belongs to the bundled GPU driver / Vulkan-loader namespace (Mesa, Gallium,
     * adrenotools, DRI, Turnip, RADV, Zink, the loader) rather than to the wrapper author. A wrapper that
     * ships a Mesa/turnip runtime references THOUSANDS of these as internal string constants (e.g.
     * {@code MESA_VK_MAX_SCISSORS}, {@code MESA_VK_COMMAND_BUFFER_STATE_*}, {@code ADRENOTOOLS_DRIVER_PATH}),
     * many of which aren't even real env vars — so they must NOT appear as editable "Detected settings"
     * nor be generically emitted (the app already manages the adrenotools/driver plumbing itself). The few
     * we intentionally curate ({@link #DRIVER_INTERNAL_ALLOW}) are allow-listed back. Shared source of
     * truth for the Kotlin dialogs AND the Java XSDA emission loop. Never throws.
     */
    public static boolean isDriverInternalEnvKey(String key) {
        if (key == null || key.isEmpty()) return false;
        String up = key.toUpperCase(Locale.ROOT);
        if (DRIVER_INTERNAL_ALLOW.contains(up)) return false;
        if (SYSTEM_ENV_KEYS.contains(up)) return true;   // launch environment, not a wrapper knob
        for (String prefix : DRIVER_INTERNAL_PREFIXES) if (up.startsWith(prefix)) return true;
        return false;
    }

    /**
     * "strings" a wrapper's binaries for the env-var NAMES they reference, unioned across whichever of
     * the ICD / BCn / compat .so the archive contains. Bounded ({@link #ENV_SCAN_BYTE_CAP} per .so),
     * sorted, deduped, and never throws. Runs off the main thread (import worker / IO dispatcher).
     */
    public List<String> scanEnvKeys(File tzst) {
        TreeSet<String> keys = new TreeSet<>();
        if (tzst != null && tzst.isFile()) {
            for (String entry : ENV_SCAN_ENTRIES) {
                keys.addAll(TarCompressorUtils.scanEntryForTokens(
                    TarCompressorUtils.Type.ZSTD, tzst, entry, ENV_KEY_PATTERN, ENV_SCAN_BYTE_CAP));
            }
        }
        return new ArrayList<>(keys);
    }

    /**
     * The detected env-var keys for an IMPORTED wrapper, read from its {@code .meta} ({@code envKeys=}).
     * An older import whose {@code .meta} predates env scanning is backfilled once by re-scanning its
     * {@code .tzst}. Returns empty for a bundled/unknown/empty identifier. Never throws.
     */
    public List<String> detectedEnvKeys(String identifier) {
        if (identifier == null || identifier.isEmpty() || !isImported(identifier)) return new ArrayList<>();
        List<String> fromMeta = readEnvKeysFromMeta(identifier);
        if (fromMeta != null) return fromMeta;                 // line present (may be an empty list)
        List<String> scanned = scanEnvKeys(importedFileFor(identifier)); // older import -> scan + backfill
        backfillEnvKeys(identifier, scanned);
        return scanned;
    }

    /** Process-wide cache for {@link #scanBundledEnvKeys} — keyed by slot file name. Bundled assets
     *  are multi-MB zstd tars scanned on demand, so each is decompressed at most once per process. */
    private static final Map<String, List<String>> BUNDLED_ENV_KEYS_CACHE = new HashMap<>();

    /**
     * The detected env-var keys for a BUNDLED slot ({@code fileName} e.g. {@code wrapper.tzst}),
     * scanned ON DEMAND — bundled assets are intentionally NOT scanned on screen-open (each is a
     * multi-MB zstd tar; see {@link #listSlots}). Prefers the user's installed override for the slot
     * (so the list reflects what will actually be used) and otherwise copies the bundled asset
     * {@code graphics_driver/<fileName>} to a temp file, scans it, then deletes the temp. The result
     * is cached per process, so re-expanding is instant. Returns an empty list on any failure; never
     * throws. Run OFF the main thread (the UI hops onto an IO dispatcher).
     */
    public List<String> scanBundledEnvKeys(String fileName) {
        if (fileName == null || fileName.isEmpty()) return new ArrayList<>();
        synchronized (BUNDLED_ENV_KEYS_CACHE) {
            List<String> cached = BUNDLED_ENV_KEYS_CACHE.get(fileName);
            if (cached != null) return cached;
        }
        List<String> result;
        // Prefer the installed override (what actually launches) over the bundled asset.
        File override = overrideFileFor(fileName);
        if (override.isFile()) {
            result = scanEnvKeys(override);
        } else if ("leegao_bcn.tzst".equals(fileName)) {
            // The BCn layer has no standalone bundled asset — its libbcn_layer.so (which reads the
            // BCN_* env vars) ships INSIDE extra_libs.tzst. Scan just that entry so the slot surfaces
            // its real knobs instead of showing nothing.
            result = scanBundledAssetEntry("extra_libs.tzst", BCN_MARKER_ENTRY);
        } else {
            result = new ArrayList<>();
            if (!overrideDir.exists()) overrideDir.mkdirs();
            File tmp = new File(overrideDir, ".scan-" + fileName + ".tmp");
            if (tmp.exists()) tmp.delete();
            boolean copied = false;
            try (InputStream in = mContext.getAssets().open(ASSET_DIR + fileName);
                 OutputStream out = new FileOutputStream(tmp)) {
                copied = StreamUtils.copy(in, out);
            }
            catch (IOException e) {
                Log.d(TAG, "scanBundledEnvKeys: copy error for " + fileName + " — " + e.getMessage());
            }
            if (copied && tmp.isFile()) result = scanEnvKeys(tmp);
            if (tmp.exists()) tmp.delete();
        }
        synchronized (BUNDLED_ENV_KEYS_CACHE) {
            BUNDLED_ENV_KEYS_CACHE.put(fileName, result);
        }
        return result;
    }

    /** Scan ONE entry inside a bundled asset tar for env-var names — for a slot whose real binary lives
     *  inside a shared payload rather than its own asset (e.g. the leegao_bcn slot's libbcn_layer.so
     *  ships in extra_libs.tzst). Copies the asset to a temp file, scans the single entry, deletes it.
     *  Returns an empty list on any failure; never throws. */
    private List<String> scanBundledAssetEntry(String assetFile, String entry) {
        List<String> keys = new ArrayList<>();
        if (!overrideDir.exists()) overrideDir.mkdirs();
        File tmp = new File(overrideDir, ".scan-" + assetFile + ".tmp");
        if (tmp.exists()) tmp.delete();
        boolean copied = false;
        try (InputStream in = mContext.getAssets().open(ASSET_DIR + assetFile);
             OutputStream out = new FileOutputStream(tmp)) {
            copied = StreamUtils.copy(in, out);
        }
        catch (IOException e) {
            Log.d(TAG, "scanBundledAssetEntry: copy error for " + assetFile + " — " + e.getMessage());
        }
        if (copied && tmp.isFile()) {
            keys.addAll(TarCompressorUtils.scanEntryForTokens(
                TarCompressorUtils.Type.ZSTD, tmp, entry, ENV_KEY_PATTERN, ENV_SCAN_BYTE_CAP));
        }
        if (tmp.exists()) tmp.delete();
        return keys;
    }

    /** The updatable slots, in display order. Each may require a marker entry to validate an import.
     *  extra_libs is a shared-libs payload (no marker). leegao_bcn is the BCn transcode layer (its own
     *  marker) — it overlays the bcn_layer.so that otherwise ships inside extra_libs. */
    public static final Slot[] SLOTS = new Slot[] {
        new Slot("wrapper.tzst",            "Wrapper (default)",    true,  WRAPPER_MARKER_ENTRY),
        new Slot("wrapper-original.tzst",   "Wrapper (original)",   true,  WRAPPER_MARKER_ENTRY),
        new Slot("wrapper-legacy.tzst",     "Wrapper (legacy)",     true,  WRAPPER_MARKER_ENTRY),
        new Slot("wrapper-leegao.tzst",     "Wrapper (leegao)",     true,  WRAPPER_MARKER_ENTRY),
        new Slot("wrapper-gamenative.tzst", "Wrapper (GameNative)", true,  WRAPPER_MARKER_ENTRY),
        new Slot("leegao_bcn.tzst",         "BCn layer (leegao)",   false, BCN_MARKER_ENTRY),
        new Slot("extra_libs.tzst",         "Extra libraries",      false, null),
    };

    /** Static definition of a slot (file name + friendly label + display flag + required marker entry). */
    public static final class Slot {
        public final String fileName;
        public final String label;
        /** True for the wrapper-* ICD archives (affects display); false for layers/shared-libs. */
        public final boolean isWrapper;
        /** Archive entry an import must contain to be accepted, or null to skip the content check. */
        public final String markerEntry;

        Slot(String fileName, String label, boolean isWrapper, String markerEntry) {
            this.fileName = fileName;
            this.label = label;
            this.isWrapper = isWrapper;
            this.markerEntry = markerEntry;
        }
    }

    /** Runtime view of a slot: static definition + current state (override present, version/notes). */
    public static final class WrapperSlot {
        public final String fileName;
        public final String label;
        public final boolean isWrapper;
        public final boolean isOverridden;
        public final String version;
        public final String notes;

        WrapperSlot(Slot slot, boolean isOverridden, String version, String notes) {
            this.fileName = slot.fileName;
            this.label = slot.label;
            this.isWrapper = slot.isWrapper;
            this.isOverridden = isOverridden;
            this.version = version;
            this.notes = notes;
        }
    }

    private final Context mContext;
    private final File overrideDir;

    public WrapperManager(Context context) {
        this.mContext = context.getApplicationContext();
        this.overrideDir = new File(mContext.getFilesDir(), OVERRIDE_DIR_NAME);
        if (!overrideDir.exists()) overrideDir.mkdirs();
    }

    /** The on-device override file for a slot (may or may not exist). */
    public File overrideFileFor(String fileName) {
        return new File(overrideDir, fileName);
    }

    /** True when the user has installed an override for the given slot. */
    public boolean hasOverride(String fileName) {
        return overrideFileFor(fileName).isFile();
    }

    /**
     * Build the list of slots with current state. Version info is read from the user's OVERRIDE
     * file when present (a small, user-supplied archive). Bundled assets are intentionally NOT
     * scanned here: each is a multi-MB zstd tar and this runs synchronously on screen open, so
     * decompressing all 6 would jank the UI — and our bundled wrappers ship no version.txt today
     * (they'd all read "Unknown" anyway). When we start shipping version.txt in bundled wrappers,
     * move that read off the main thread. See {@link #readVersionInfoFromAsset}.
     */
    public List<WrapperSlot> listSlots() {
        ArrayList<WrapperSlot> out = new ArrayList<>();
        for (Slot slot : SLOTS) {
            // "extra_libs" is a shared-libraries payload (ReShade / Turnip driver / BCn + compat
            // layers / GL libs shared across drivers), NOT a selectable wrapper — keep it in SLOTS for
            // the override/extraction machinery, but hide it from the manager's user-facing list.
            if ("extra_libs.tzst".equals(slot.fileName)) continue;
            boolean overridden = hasOverride(slot.fileName);
            // Overridden slot: read the installed override's version.txt. Bundled slot: read the small
            // bundled_wrappers.json manifest (instant — no multi-MB tar to decompress on this thread).
            String[] info = overridden
                ? readVersionInfo(overrideFileFor(slot.fileName))
                : bundledVersionInfo(slot.fileName);
            // A catalog-installed override whose .tzst has no version.txt reads "Unknown" — fall back to
            // the recorded catalog entry name so the slot shows what it actually is (e.g. GameNative …).
            if (overridden && (info[0] == null || info[0].isEmpty() || "Unknown".equals(info[0]))) {
                String cn = slotCatalogName(slot.fileName);
                if (cn != null) info = new String[]{ cn, info.length > 1 ? info[1] : "" };
            }
            out.add(new WrapperSlot(slot, overridden, info[0], info[1]));
        }
        return out;
    }

    // --- Bundled-slot version + catalog mapping (bundled_wrappers.json) -------------------------------
    /** Bundled-wrapper version/catalog manifest, parsed once per instance ({@code null} if absent/bad). */
    private static final String BUNDLED_MANIFEST = "bundled_wrappers.json";
    private JSONObject bundledManifest;
    private boolean bundledManifestTried;

    private JSONObject bundledManifest() {
        if (bundledManifestTried) return bundledManifest;
        bundledManifestTried = true;
        try (InputStream in = mContext.getAssets().open(ASSET_DIR + BUNDLED_MANIFEST)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            StreamUtils.copy(in, bos);
            bundledManifest = new JSONObject(new String(bos.toByteArray()));
        }
        catch (Exception e) {
            bundledManifest = null; // absent / malformed -> callers fall back to "Unknown" / no mapping
        }
        return bundledManifest;
    }

    /** {version, notes} for a bundled slot from the manifest, or {"Unknown",""} when unmapped. */
    private String[] bundledVersionInfo(String fileName) {
        JSONObject m = bundledManifest();
        if (m != null) {
            JSONObject e = m.optJSONObject(fileName);
            if (e != null) return new String[]{ e.optString("version", "Unknown"), e.optString("notes", "") };
        }
        return new String[]{"Unknown", ""};
    }

    /** The canonical catalog id a bundled slot maps to (for update-available detection), or null. */
    public String bundledCatalogId(String fileName) {
        JSONObject m = bundledManifest();
        if (m == null) return null;
        JSONObject e = m.optJSONObject(fileName);
        String id = (e != null) ? e.optString("catalogId", "") : "";
        return id.isEmpty() ? null : id;
    }

    /** The catalog version the bundled slot's asset corresponds to (0 when unmapped). Compared against
     *  the live catalog entry's version to flag an available update for a bundled slot. */
    public int bundledCatalogVersion(String fileName) {
        JSONObject m = bundledManifest();
        if (m == null) return 0;
        JSONObject e = m.optJSONObject(fileName);
        return (e != null) ? e.optInt("catalogVersion", 0) : 0;
    }

    // --- Slot-override catalog provenance (slot_catalog.json) -----------------------------------------
    // A bundled-slot OVERRIDE has no .meta sidecar (only free-form imports do), so a catalog install into
    // a slot (Update -> From catalog, via installToSlot) had nowhere to record WHERE it came from. This
    // tiny prefs file at filesDir/graphics_driver/slot_catalog.json maps slotFileName -> {catalogId,
    // catalogVersion}, giving slot installs the same durable "installed from catalog X @ vN" provenance an
    // import gets from its .meta. Parsed once per instance (like bundledManifest); every method never
    // throws. Cleared for a slot when its override is reset/removed so a reverted slot stops reading as
    // catalog-installed. ---------------------------------------------------------------------------------
    private static final String SLOT_CATALOG_FILE = "slot_catalog.json";
    // slot_catalog.json is read FRESH on every access (it's a tiny one-object file) — NOT cached per
    // instance. The manager and the catalog downloader are SEPARATE WrapperManager instances that both
    // read/write this file; a per-instance cache goes stale (the manager wouldn't see a slot download the
    // downloader instance just wrote), so a slot reset would find nothing to clear via a stale cache and
    // leave the provenance — and the catalog's "Installed" checkmark — behind. Read-modify-write on disk.
    private JSONObject readSlotCatalog() {
        JSONObject obj = new JSONObject();
        File f = new File(overrideDir, SLOT_CATALOG_FILE);
        if (f.isFile()) {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            catch (IOException e) {
                Log.d(TAG, "readSlotCatalog: read error — " + e.getMessage());
            }
            if (sb.length() > 0) {
                try { obj = new JSONObject(sb.toString()); }
                catch (Exception e) { obj = new JSONObject(); } // malformed -> start clean
            }
        }
        return obj;
    }

    private void writeSlotCatalog(JSONObject obj) {
        if (!overrideDir.exists()) overrideDir.mkdirs();
        File f = new File(overrideDir, SLOT_CATALOG_FILE);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(obj.toString().getBytes());
        }
        catch (IOException e) {
            Log.d(TAG, "writeSlotCatalog: write error — " + e.getMessage());
        }
    }

    /** Record (upsert + persist) the catalog provenance of a slot OVERRIDE — called by the downloader
     *  after a successful {@link #installOverride} from the catalog. No-op for a blank slot/id. Never
     *  throws. */
    public void recordSlotCatalog(String slotFileName, String catalogId, int catalogVersion, String catalogName) {
        if (slotFileName == null || slotFileName.isEmpty() || catalogId == null || catalogId.isEmpty()) return;
        JSONObject obj = readSlotCatalog();
        try {
            JSONObject e = new JSONObject();
            e.put("catalogId", catalogId);
            e.put("catalogVersion", catalogVersion);
            // Store the display name so a catalog-installed override (whose .tzst has no version.txt)
            // shows "<catalog name>" instead of "Unknown" in the slot list (see listSlots).
            if (catalogName != null && !catalogName.isEmpty()) e.put("catalogName", catalogName);
            obj.put(slotFileName, e);
        }
        catch (Exception ex) {
            Log.d(TAG, "recordSlotCatalog: " + ex.getMessage());
            return;
        }
        writeSlotCatalog(obj);
    }

    /** Drop a slot's recorded catalog provenance (+ persist). Called from {@link #removeOverride} /
     *  {@link #resetAll} so a slot reverted to bundled stops reading as catalog-installed. Never throws. */
    public void clearSlotCatalog(String slotFileName) {
        if (slotFileName == null || slotFileName.isEmpty()) return;
        JSONObject obj = readSlotCatalog();
        if (obj.has(slotFileName)) {
            obj.remove(slotFileName);
            writeSlotCatalog(obj);
        }
    }

    /** The catalog id a slot OVERRIDE was installed from, or null when the slot isn't catalog-installed.
     *  Never throws. */
    public String slotCatalogId(String slotFileName) {
        if (slotFileName == null || slotFileName.isEmpty()) return null;
        JSONObject e = readSlotCatalog().optJSONObject(slotFileName);
        if (e == null) return null;
        String id = e.optString("catalogId", "");
        return id.isEmpty() ? null : id;
    }

    /** The catalog version a slot OVERRIDE was installed at, or 0 when unmapped. Never throws. */
    public int slotCatalogVersion(String slotFileName) {
        if (slotFileName == null || slotFileName.isEmpty()) return 0;
        JSONObject e = readSlotCatalog().optJSONObject(slotFileName);
        return (e != null) ? e.optInt("catalogVersion", 0) : 0;
    }

    /** The catalog entry NAME a slot OVERRIDE was installed from, or null. Used as the slot's version
     *  label when the override .tzst carries no version.txt. Never throws. */
    public String slotCatalogName(String slotFileName) {
        if (slotFileName == null || slotFileName.isEmpty()) return null;
        JSONObject e = readSlotCatalog().optJSONObject(slotFileName);
        if (e == null) return null;
        String n = e.optString("catalogName", "");
        return n.isEmpty() ? null : n;
    }

    /**
     * The durable "which catalog entries are installed" answer (#132): the union of every IMPORTED
     * wrapper's recorded {@code catalogId} (from its .meta) and every bundled SLOT override's recorded
     * {@code catalogId} (from slot_catalog.json). Survives dialog reopen (unlike the picker's session
     * set). Older imports / hand-picked file imports lacking provenance simply don't appear. Never throws.
     */
    public Set<String> installedCatalogIds() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Imported imp : enumerateImported()) {
            String id = catalogIdFor(imp.identifier);
            if (id != null && !id.isEmpty()) out.add(id);
        }
        for (Slot slot : SLOTS) {
            String id = slotCatalogId(slot.fileName);
            if (id != null && !id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** The on-device version installed for {@code catalogId} — from the matching import's
     *  {@code catalogVersion}, else the matching slot override's, else 0 (not installed). Lets a picker
     *  compare against the live catalog entry to flag "Update available". Never throws. */
    public int installedCatalogVersion(String catalogId) {
        if (catalogId == null || catalogId.isEmpty()) return 0;
        String impId = findImportByCatalogId(catalogId);
        if (impId != null) return catalogVersionFor(impId);
        for (Slot slot : SLOTS) {
            if (catalogId.equals(slotCatalogId(slot.fileName))) return slotCatalogVersion(slot.fileName);
        }
        return 0;
    }

    /**
     * Copy the picked file's bytes verbatim to {@code filesDir/graphics_driver/<fileName>}.
     * Validates first that it's a readable zstd tar; for wrapper-* slots additionally that it
     * contains the vulkan wrapper marker. Writes to a temp file then renames, so prior state is
     * left intact on any failure. Returns true on success.
     */
    public boolean installOverride(String fileName, Uri src) {
        Slot slot = slotFor(fileName);
        if (slot == null || src == null) return false;

        if (!overrideDir.exists()) overrideDir.mkdirs();
        File tmp = new File(overrideDir, fileName + ".tmp");
        if (tmp.exists()) tmp.delete();

        // 1. Copy bytes to a temp file.
        try (InputStream in = mContext.getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) {
                Log.d(TAG, "installOverride: could not open source " + src);
                tmp.delete();
                return false;
            }
            if (!StreamUtils.copy(in, out)) {
                Log.d(TAG, "installOverride: copy failed for " + fileName);
                tmp.delete();
                return false;
            }
        }
        catch (IOException | SecurityException e) {
            Log.d(TAG, "installOverride: copy error for " + fileName + " — " + e.getMessage());
            tmp.delete();
            return false;
        }

        // 2. Validate it's a readable zstd tar.
        if (!TarCompressorUtils.isValidArchive(TarCompressorUtils.Type.ZSTD, tmp)) {
            Log.d(TAG, "installOverride: not a readable zstd tar — " + fileName);
            tmp.delete();
            return false;
        }

        // 3. Slots with a marker entry must contain it (wrapper ICD or bcn layer).
        if (slot.markerEntry != null
                && !TarCompressorUtils.containsEntry(TarCompressorUtils.Type.ZSTD, tmp, slot.markerEntry)) {
            Log.d(TAG, "installOverride: missing " + slot.markerEntry + " — " + fileName);
            tmp.delete();
            return false;
        }

        // 4. Atomically replace.
        File dst = overrideFileFor(fileName);
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            Log.d(TAG, "installOverride: rename failed for " + fileName);
            tmp.delete();
            return false;
        }
        Log.d(TAG, "installOverride: installed override for " + fileName);
        return true;
    }

    /** Delete a single slot's override (revert to bundled). No-op if absent. Also drops any recorded
     *  catalog provenance so a reverted slot stops reading as catalog-installed (#132). */
    public void removeOverride(String fileName) {
        File f = overrideFileFor(fileName);
        if (f.exists()) {
            f.delete();
            Log.d(TAG, "removeOverride: reverted " + fileName + " to bundled");
        }
        clearSlotCatalog(fileName);
    }

    /** Delete every override among the known slots (revert all to bundled). Each {@link #removeOverride}
     *  also clears that slot's catalog provenance (#132), so a reset never leaves a stale "installed". */
    public void resetAll() {
        for (Slot slot : SLOTS) removeOverride(slot.fileName);
    }

    /**
     * Read an optional {@code version.txt} from inside a .tzst, parsing {@code version:} and
     * {@code notes:} lines. Returns {"Unknown", ""} when the file/entry is missing. Never throws.
     * Index 0 = version, index 1 = notes.
     */
    public String[] readVersionInfo(File tzst) {
        String content = TarCompressorUtils.readTextFile(TarCompressorUtils.Type.ZSTD, tzst, "version.txt");
        return parseVersionInfo(content);
    }

    private String[] readVersionInfoFromAsset(String assetPath) {
        // Stream version.txt straight out of the bundled asset (no extraction / temp copy).
        // Bundled wrappers may not ship version.txt yet -> "Unknown" (no crash).
        String content = TarCompressorUtils.readTextFile(TarCompressorUtils.Type.ZSTD, mContext, assetPath, "version.txt");
        return parseVersionInfo(content);
    }

    private static String[] parseVersionInfo(String content) {
        String version = "Unknown";
        String notes = "";
        if (content != null) {
            for (String line : content.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.regionMatches(true, 0, "version:", 0, 8)) {
                    version = trimmed.substring(8).trim();
                } else if (trimmed.regionMatches(true, 0, "notes:", 0, 6)) {
                    notes = trimmed.substring(6).trim();
                }
            }
            if (version.isEmpty()) version = "Unknown";
        }
        return new String[] {version, notes};
    }

    private static Slot slotFor(String fileName) {
        for (Slot slot : SLOTS) if (slot.fileName.equals(fileName)) return slot;
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2 (issue #132): free-form imported wrappers.
    //
    // An imported wrapper lives alongside the fixed-slot overrides in filesDir/graphics_driver/:
    //   <identifier>.tzst  — the wrapper archive (extracted at launch when a container's
    //                        graphicsDriver == identifier; see XServerDisplayActivity).
    //   <identifier>.meta  — a tiny sidecar (label / identifier / imported flag) that makes imports
    //                        enumerable and distinct from bundled-slot overrides (which have NO meta).
    // The identifier is StringUtils.parseIdentifier(displayName) so the display<->identifier round
    // trip used by the Graphics Driver dropdown holds. Reserved ids (anything colliding with a slot
    // file name) are refused so an import can never clobber a bundled-slot override.
    // ---------------------------------------------------------------------------------------------

    // ---------------------------------------------------------------------------------------------
    // Step 3 (issue #132) "Smart Wrapper Manager": capability detection.
    //
    // Instead of gating driver-config options by EXACT identifier name (isGamenative/isBcnLayer),
    // the dialog (and XSDA activation) ask WHAT A WRAPPER CONTAINS via {@link #capsFor}. For an
    // imported wrapper the caps are detected once at import time (scan the .tzst) and cached in the
    // .meta sidecar; for a bundled/known identifier the caps come from {@link #BUNDLED_CAPS} (we
    // already know what each ships). This lets an imported wrapper (e.g. "112") that actually carries
    // a BCn layer show + activate the BCn options WITHOUT a hardcoded name, while bundled wrappers
    // resolve to exactly their current gating (gamenative -> integrated BCn, bcn_layer -> BCn layer
    // settings, others -> plain ICD). Never throws.
    // ---------------------------------------------------------------------------------------------

    /** What a wrapper archive contains: an ICD (vulkan_wrapper), a BCn transcode layer, and/or the
     *  DX12/compat layer. hasCompatLayer is detected + stored for the future compat UI only (that UI
     *  is on a different branch) — nothing on THIS branch renders or activates a compat control. */
    public static final class WrapperCaps {
        public final boolean hasIcd;
        public final boolean hasBcnLayer;
        public final boolean hasCompatLayer;
        WrapperCaps(boolean hasIcd, boolean hasBcnLayer, boolean hasCompatLayer) {
            this.hasIcd = hasIcd;
            this.hasBcnLayer = hasBcnLayer;
            this.hasCompatLayer = hasCompatLayer;
        }
    }

    /** Known capabilities of the BUNDLED driver identifiers, so bundled and imported wrappers resolve
     *  through the same {@link #capsFor} path. These reproduce each bundled driver's CURRENT gating:
     *  gamenative = integrated-BCn ICD; bcn_layer = a BCn overlay layer (no own ICD); the rest = plain
     *  ICDs. compat-bcn is listed for completeness in case such a bundled driver is ever added. */
    private static WrapperCaps bundledCaps(String identifier) {
        switch (identifier) {
            case "wrapper-gamenative": return new WrapperCaps(true,  true,  false);
            case "wrapper-bcn_layer":
            case "leegao_bcn":         return new WrapperCaps(false, true,  false); // BCn overlay, no own ICD
            case "wrapper-compat-bcn": return new WrapperCaps(true,  true,  true);
            case "wrapper-original":
            case "wrapper-legacy":
            case "wrapper-leegao":
            case "wrapper":            return new WrapperCaps(true,  false, false);
            default:                   return null;
        }
    }

    /**
     * Resolve a driver identifier to its capabilities. For an IMPORTED wrapper the caps are read from
     * the .meta (backfilled by scanning the .tzst once if an older import's .meta predates the flags).
     * For a bundled/known identifier they come from {@link #bundledCaps}. An unknown non-import id
     * falls back to a plain ICD (matches "wrapper"). Never throws.
     */
    public WrapperCaps capsFor(String identifier) {
        if (identifier == null || identifier.isEmpty())
            return new WrapperCaps(false, false, false);
        if (isImported(identifier)) {
            WrapperCaps cached = readCapsFromMeta(identifier);
            if (cached != null) return cached;
            // Older import whose .meta lacks the flags -> derive once and rewrite the sidecar.
            WrapperCaps scanned = scanCaps(importedFileFor(identifier));
            backfillMeta(identifier, scanned);
            return scanned;
        }
        WrapperCaps bundled = bundledCaps(identifier);
        if (bundled != null) return bundled;
        return new WrapperCaps(true, false, false);
    }

    /**
     * Result of inspecting a picked file WITHOUT installing it (pre-import preview). {@code valid} is
     * true only for a readable zstd tar carrying the wrapper ICD marker (same bar as
     * {@link #importWrapper}); when false the caps/version are placeholders and the caller should abort
     * with the usual "not a valid wrapper" toast. Never throws.
     */
    public static final class Inspection {
        public final boolean valid;
        public final WrapperCaps caps;
        public final String version;
        public final String notes;
        /** Env-var names auto-detected from the picked file's binaries (pre-import preview). */
        public final List<String> envKeys;
        Inspection(boolean valid, WrapperCaps caps, String version, String notes, List<String> envKeys) {
            this.valid = valid;
            this.caps = caps;
            this.version = version;
            this.notes = notes;
            this.envKeys = (envKeys != null) ? envKeys : new ArrayList<>();
        }
    }

    /**
     * Copy a picked file to a scratch temp, run the SAME validation as {@link #importWrapper} (readable
     * zstd tar + wrapper ICD marker) and the SAME capability scan / version read used at import — then
     * delete the temp. Nothing is installed; this only feeds the pre-import inspection UI. Returns an
     * {@link Inspection} whose {@code valid=false} means the file should be rejected. Never throws.
     */
    public Inspection inspectUri(Uri src) {
        WrapperCaps none = new WrapperCaps(false, false, false);
        if (src == null) return new Inspection(false, none, "Unknown", "", null);
        if (!overrideDir.exists()) overrideDir.mkdirs();
        File tmp = new File(overrideDir, ".inspect.tzst.tmp");
        if (tmp.exists()) tmp.delete();
        try (InputStream in = mContext.getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null || !StreamUtils.copy(in, out)) {
                tmp.delete();
                return new Inspection(false, none, "Unknown", "", null);
            }
        }
        catch (IOException | SecurityException e) {
            Log.d(TAG, "inspectUri: copy error — " + e.getMessage());
            tmp.delete();
            return new Inspection(false, none, "Unknown", "", null);
        }
        boolean ok = TarCompressorUtils.isValidArchive(TarCompressorUtils.Type.ZSTD, tmp)
                && TarCompressorUtils.containsEntry(TarCompressorUtils.Type.ZSTD, tmp, WRAPPER_MARKER_ENTRY);
        if (!ok) {
            tmp.delete();
            return new Inspection(false, none, "Unknown", "", null);
        }
        WrapperCaps caps = scanCaps(tmp);
        String[] info = readVersionInfo(tmp);
        List<String> envKeys = scanEnvKeys(tmp);
        tmp.delete();
        return new Inspection(true, caps, info[0], info[1], envKeys);
    }

    /** Scan a wrapper .tzst for the ICD / BCn / compat marker entries (lenient membership match). */
    private WrapperCaps scanCaps(File tzst) {
        boolean icd = TarCompressorUtils.containsEntry(TarCompressorUtils.Type.ZSTD, tzst, WRAPPER_MARKER_ENTRY);
        boolean bcn = TarCompressorUtils.containsEntry(TarCompressorUtils.Type.ZSTD, tzst, BCN_MARKER_ENTRY);
        boolean compat = TarCompressorUtils.containsEntry(TarCompressorUtils.Type.ZSTD, tzst, COMPAT_MARKER_ENTRY);
        return new WrapperCaps(icd, bcn, compat);
    }

    /** Read the hasIcd/hasBcnLayer/hasCompatLayer flags from a .meta; null if any is absent (old
     *  import -> caller backfills). Never throws. */
    private WrapperCaps readCapsFromMeta(String identifier) {
        File meta = metaFileFor(identifier);
        Boolean icd = null, bcn = null, compat = null;
        try (BufferedReader r = new BufferedReader(new FileReader(meta))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals("hasIcd")) icd = v.equals("1");
                else if (k.equals("hasBcnLayer")) bcn = v.equals("1");
                else if (k.equals("hasCompatLayer")) compat = v.equals("1");
            }
        }
        catch (IOException e) {
            return null;
        }
        if (icd == null || bcn == null || compat == null) return null;
        return new WrapperCaps(icd, bcn, compat);
    }

    /** Rewrite an import's .meta to include the detected caps, preserving its label AND any already-
     *  recorded envKeys line (null = line absent -> left absent so env scanning can still backfill). */
    private void backfillMeta(String identifier, WrapperCaps caps) {
        Imported imp = readMeta(metaFileFor(identifier), identifier);
        String label = (imp != null) ? imp.label : identifier;
        writeMeta(identifier, label, caps, readEnvKeysFromMeta(identifier));
    }

    /** Rewrite an import's .meta to record the detected env keys, preserving label + caps. */
    private void backfillEnvKeys(String identifier, List<String> keys) {
        Imported imp = readMeta(metaFileFor(identifier), identifier);
        String label = (imp != null) ? imp.label : identifier;
        WrapperCaps caps = readCapsFromMeta(identifier);
        if (caps == null) caps = scanCaps(importedFileFor(identifier));
        writeMeta(identifier, label, caps, keys);
    }

    /** Read the {@code envKeys=} line from a .meta. Returns null when the line is ABSENT (older import,
     *  caller re-scans), or a (possibly empty) list when present. Never throws. */
    private List<String> readEnvKeysFromMeta(String identifier) {
        File meta = metaFileFor(identifier);
        try (BufferedReader r = new BufferedReader(new FileReader(meta))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                if (!line.substring(0, eq).trim().equals("envKeys")) continue;
                String v = line.substring(eq + 1).trim();
                ArrayList<String> out = new ArrayList<>();
                if (!v.isEmpty()) for (String s : v.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) out.add(t);
                }
                return out;
            }
        }
        catch (IOException e) {
            return null;
        }
        return null;
    }

    /** A user-imported wrapper: canonical identifier (persisted in Container.graphicsDriver) + label. */
    public static final class Imported {
        public final String identifier;
        public final String label;
        Imported(String identifier, String label) {
            this.identifier = identifier;
            this.label = label;
        }
    }

    /** The imported wrapper archive for an identifier (may or may not exist). */
    public File importedFileFor(String identifier) {
        return new File(overrideDir, identifier + ".tzst");
    }

    private File metaFileFor(String identifier) {
        return new File(overrideDir, identifier + ".meta");
    }

    /**
     * True when {@code <identifier>.tzst} would collide with one of the bundled fixed slots
     * (wrapper, wrapper-original, wrapper-legacy, wrapper-leegao, wrapper-gamenative, leegao_bcn,
     * extra_libs). Also treats empty as reserved. Imports with a reserved id are refused so they
     * cannot overwrite a slot override file.
     */
    public boolean isReservedIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) return true;
        String asFile = identifier + ".tzst";
        for (Slot slot : SLOTS) if (slot.fileName.equals(asFile)) return true;
        return false;
    }

    /** True if the identifier is a user-imported wrapper (has a .meta sidecar). Used to expose the
     *  full integrated-wrapper option set for imports (their exact capabilities aren't known, and a
     *  wrapper ignores env it doesn't read — so showing all options is safe). */
    public boolean isImported(String identifier) {
        if (identifier == null || identifier.isEmpty()) return false;
        return new File(overrideDir, identifier + ".meta").isFile();
    }

    /** Enumerate imported wrappers (each has a .meta sidecar AND a .tzst payload). Never throws. */
    public List<Imported> enumerateImported() {
        ArrayList<Imported> out = new ArrayList<>();
        File[] files = overrideDir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".meta")) continue;
            String identifier = n.substring(0, n.length() - ".meta".length());
            if (isReservedIdentifier(identifier)) continue;   // defensive: never surface a slot as an import
            if (!importedFileFor(identifier).isFile()) continue; // orphaned meta (payload gone) -> skip
            Imported imp = readMeta(f, identifier);
            if (imp != null) out.add(imp);
        }
        return out;
    }

    private Imported readMeta(File metaFile, String fallbackId) {
        String label = null;
        String identifier = fallbackId;
        try (BufferedReader r = new BufferedReader(new FileReader(metaFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if (k.equals("label")) label = v;
                else if (k.equals("identifier")) identifier = v;
            }
        }
        catch (IOException e) {
            return null;
        }
        if (label == null || label.isEmpty()) label = identifier;
        return new Imported(identifier, label);
    }

    /** Rewrite a .meta preserving any already-stored {@code hiddenKeys} line (a caps/env backfill must
     *  never drop the user's hide/show choices). */
    private boolean writeMeta(String identifier, String label, WrapperCaps caps, List<String> envKeys) {
        return writeMeta(identifier, label, caps, envKeys, hiddenKeys(identifier));
    }

    /** Rewrite a .meta preserving any already-stored catalog provenance ({@code catalogId} /
     *  {@code catalogVersion}) — a caps/env/hidden backfill must never drop where an import came from. */
    private boolean writeMeta(String identifier, String label, WrapperCaps caps, List<String> envKeys,
                              Set<String> hidden) {
        return writeMeta(identifier, label, caps, envKeys, hidden,
            catalogIdFor(identifier), catalogVersionFor(identifier));
    }

    private boolean writeMeta(String identifier, String label, WrapperCaps caps, List<String> envKeys,
                              Set<String> hidden, String catalogId, int catalogVersion) {
        File meta = metaFileFor(identifier);
        try (OutputStream out = new FileOutputStream(meta)) {
            StringBuilder content = new StringBuilder()
                .append("label=").append(label).append("\n")
                .append("identifier=").append(identifier).append("\n")
                .append("imported=true\n")
                .append("hasIcd=").append(caps.hasIcd ? "1" : "0").append("\n")
                .append("hasBcnLayer=").append(caps.hasBcnLayer ? "1" : "0").append("\n")
                .append("hasCompatLayer=").append(caps.hasCompatLayer ? "1" : "0").append("\n");
            // Omit the line entirely when unknown (null) so a caps-only backfill doesn't falsely
            // record "no env keys" before they've been scanned. An empty list writes "envKeys=".
            if (envKeys != null) content.append("envKeys=").append(TextUtils.join(",", envKeys)).append("\n");
            // hiddenKeys line only when non-empty — a missing line means "nothing hidden".
            if (hidden != null && !hidden.isEmpty())
                content.append("hiddenKeys=").append(TextUtils.join(",", hidden)).append("\n");
            // Catalog provenance (#132): written only for catalog-installed wrappers, so the manager can
            // offer an in-place update when a newer catalog version appears. Absent for file imports.
            if (catalogId != null && !catalogId.isEmpty()) {
                content.append("catalogId=").append(catalogId).append("\n");
                content.append("catalogVersion=").append(catalogVersion).append("\n");
            }
            out.write(content.toString().getBytes());
            return true;
        }
        catch (IOException e) {
            Log.d(TAG, "writeMeta failed for " + identifier + " — " + e.getMessage());
            return false;
        }
    }

    /**
     * The per-wrapper HIDDEN detected-settings set (issue #132) — env keys the user chose to hide via
     * "Edit settings", read from the {@code .meta} ({@code hiddenKeys=}). A hidden key is never rendered
     * as a setting nor emitted at launch. Returns an empty set when the line/file is absent, the id is
     * empty, or the id isn't an import. Never throws.
     */
    public Set<String> hiddenKeys(String identifier) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (identifier == null || identifier.isEmpty()) return out;
        File meta = metaFileFor(identifier);
        try (BufferedReader r = new BufferedReader(new FileReader(meta))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                if (!line.substring(0, eq).trim().equals("hiddenKeys")) continue;
                String v = line.substring(eq + 1).trim();
                if (!v.isEmpty()) for (String s : v.split(",")) {
                    String t = s.trim();
                    if (!t.isEmpty()) out.add(t);
                }
                return out;
            }
        }
        catch (IOException e) {
            return out;
        }
        return out;
    }

    /**
     * Persist the per-wrapper hidden detected-settings set, preserving the import's label / caps / env
     * keys. No-op returning false for a non-import or empty id. Never throws.
     */
    public boolean setHiddenKeys(String identifier, Set<String> hidden) {
        if (identifier == null || identifier.isEmpty() || !isImported(identifier)) return false;
        Imported imp = readMeta(metaFileFor(identifier), identifier);
        String label = (imp != null) ? imp.label : identifier;
        WrapperCaps caps = readCapsFromMeta(identifier);
        if (caps == null) caps = scanCaps(importedFileFor(identifier));
        List<String> envKeys = readEnvKeysFromMeta(identifier);   // may be null (line absent) -> stays absent
        return writeMeta(identifier, label, caps, envKeys, (hidden != null) ? hidden : new LinkedHashSet<>());
    }

    /**
     * Import a free-form wrapper with NO catalog provenance (a hand-picked file). Delegates to
     * {@link #importWrapper(Uri, String, String, int)} with {@code catalogId=null, catalogVersion=0}.
     */
    public String importWrapper(Uri src, String displayName) {
        return importWrapper(src, displayName, null, 0);
    }

    /**
     * Import a wrapper, optionally recording where it came from. Derives the identifier from
     * {@code displayName} via {@link StringUtils#parseIdentifier}; validates the source is a zstd tar
     * carrying the vulkan wrapper marker; stores {@code <identifier>.tzst} + {@code <identifier>.meta}.
     *
     * When {@code catalogId} is non-blank, the provenance ({@code catalogId} + {@code catalogVersion})
     * is written into the {@code .meta} so the Wrapper Manager can offer an in-place UPDATE when the
     * catalog later ships a newer version (issue #132). If an existing import already carries the SAME
     * {@code catalogId}, this OVERWRITES it in place (reusing its identifier + label) instead of
     * suffixing a duplicate — so re-downloading a newer version updates the wrapper and clears its
     * "update available" badge. For a hand-picked file (blank {@code catalogId}) the classic behaviour
     * holds: collisions with an existing import are resolved by suffixing the LABEL (" 2", " 3", …),
     * which keeps the display<->identifier round trip intact.
     *
     * A reserved id (bundled slot collision) or an unreadable/invalid archive returns null. Returns the
     * stored identifier.
     */
    public String importWrapper(Uri src, String displayName, String catalogId, int catalogVersion) {
        if (src == null || displayName == null) return null;
        String baseLabel = displayName.trim();
        if (baseLabel.isEmpty()) return null;

        String label = baseLabel;
        String identifier = StringUtils.parseIdentifier(label);
        if (identifier.isEmpty()) return null;
        if (isReservedIdentifier(identifier)) {
            Log.d(TAG, "importWrapper: refused reserved identifier " + identifier);
            return null;
        }

        // Catalog UPDATE-in-place: if an existing import already came from this catalog entry, overwrite
        // it (same identifier + label) rather than suffixing a duplicate. Keeps a single card per catalog
        // wrapper and lets the "update available" badge clear once the newer version lands.
        String existing = (catalogId != null && !catalogId.isEmpty()) ? findImportByCatalogId(catalogId) : null;
        if (existing != null) {
            identifier = existing;
            Imported imp = readMeta(metaFileFor(existing), existing);
            if (imp != null && imp.label != null && !imp.label.isEmpty()) label = imp.label;
        } else {
            // Suffix the label until the derived identifier is free among existing imports.
            int n = 2;
            while (importedFileFor(identifier).isFile() || metaFileFor(identifier).isFile()) {
                label = baseLabel + " " + n;
                identifier = StringUtils.parseIdentifier(label);
                n++;
                if (isReservedIdentifier(identifier)) return null; // extremely defensive
            }
        }

        if (!overrideDir.exists()) overrideDir.mkdirs();
        File tmp = new File(overrideDir, identifier + ".tzst.tmp");
        if (tmp.exists()) tmp.delete();

        // 1. Copy bytes to a temp file.
        try (InputStream in = mContext.getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null || !StreamUtils.copy(in, out)) {
                tmp.delete();
                return null;
            }
        }
        catch (IOException | SecurityException e) {
            Log.d(TAG, "importWrapper: copy error — " + e.getMessage());
            tmp.delete();
            return null;
        }

        // 2. Must be a readable zstd tar carrying the wrapper ICD marker (lenient match).
        if (!TarCompressorUtils.isValidArchive(TarCompressorUtils.Type.ZSTD, tmp)
                || !TarCompressorUtils.containsEntry(TarCompressorUtils.Type.ZSTD, tmp, WRAPPER_MARKER_ENTRY)) {
            Log.d(TAG, "importWrapper: not a valid wrapper archive");
            tmp.delete();
            return null;
        }

        // 3. Move into place + write the sidecar.
        File dst = importedFileFor(identifier);
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) {
            tmp.delete();
            return null;
        }
        // Detect capabilities once (ICD / BCn layer / compat layer) and cache them in the sidecar so
        // the driver-config dialog + XSDA activation can gate by CONTENT, not by the import's name.
        WrapperCaps caps = scanCaps(dst);
        // Layer 1 (#132): auto-detect the env-var names this wrapper's binaries reference, once at
        // import (this runs on the import worker), and persist them in the sidecar for the dialog.
        List<String> envKeys = scanEnvKeys(dst);
        // Preserve any prior hide/show choices on an in-place update; record catalog provenance (if any).
        if (!writeMeta(identifier, label, caps, envKeys, hiddenKeys(identifier), catalogId, catalogVersion)) {
            dst.delete();
            return null;
        }
        Log.d(TAG, "importWrapper: imported '" + label + "' as " + identifier
                + (catalogId != null && !catalogId.isEmpty() ? " (catalog " + catalogId + " v" + catalogVersion + ")" : ""));
        return identifier;
    }

    /** The identifier of the imported wrapper that came from catalog entry {@code catalogId}, or null
     *  when no import carries that provenance. Never throws. */
    private String findImportByCatalogId(String catalogId) {
        if (catalogId == null || catalogId.isEmpty()) return null;
        for (Imported imp : enumerateImported()) {
            if (catalogId.equals(catalogIdFor(imp.identifier))) return imp.identifier;
        }
        return null;
    }

    /** The catalog id an IMPORTED wrapper was installed from ({@code catalogId=} in its {@code .meta}),
     *  or null when absent (a hand-picked file import, or an older import predating provenance). Never
     *  throws. */
    public String catalogIdFor(String identifier) {
        if (identifier == null || identifier.isEmpty()) return null;
        File meta = metaFileFor(identifier);
        try (BufferedReader r = new BufferedReader(new FileReader(meta))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                if (!line.substring(0, eq).trim().equals("catalogId")) continue;
                String v = line.substring(eq + 1).trim();
                return v.isEmpty() ? null : v;
            }
        }
        catch (IOException e) {
            return null;
        }
        return null;
    }

    /** The catalog version an IMPORTED wrapper was installed at ({@code catalogVersion=} in its
     *  {@code .meta}), or 0 when absent/unparseable. Compared against the live catalog's version to
     *  detect an available update. Never throws. */
    public int catalogVersionFor(String identifier) {
        if (identifier == null || identifier.isEmpty()) return 0;
        File meta = metaFileFor(identifier);
        try (BufferedReader r = new BufferedReader(new FileReader(meta))) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                if (!line.substring(0, eq).trim().equals("catalogVersion")) continue;
                try {
                    return Integer.parseInt(line.substring(eq + 1).trim());
                }
                catch (NumberFormatException e) {
                    return 0;
                }
            }
        }
        catch (IOException e) {
            return 0;
        }
        return 0;
    }

    /**
     * Delete an imported wrapper AND run the reference cascade: any Container or Shortcut whose
     * {@code graphicsDriver} still points at this identifier is reset to
     * {@link Container#DEFAULT_GRAPHICS_DRIVER} so no dangling reference survives the delete. Mirrors
     * {@link AdrenotoolsManager} reloadContainers, but rewrites the graphicsDriver FIELD (not the
     * config version key). Never throws.
     */
    public void deleteImported(String identifier) {
        if (identifier == null || identifier.isEmpty()) return;
        try {
            ContainerManager cm = new ContainerManager(mContext);
            for (Container c : cm.getContainers()) {
                if (identifier.equals(c.getGraphicsDriver())) {
                    c.setGraphicsDriver(Container.DEFAULT_GRAPHICS_DRIVER);
                    c.saveData();
                }
            }
            for (Shortcut s : cm.loadShortcuts()) {
                if (identifier.equals(s.getExtra("graphicsDriver", ""))) {
                    s.putExtra("graphicsDriver", Container.DEFAULT_GRAPHICS_DRIVER);
                    s.saveData();
                }
            }
        }
        catch (Exception e) {
            Log.d(TAG, "deleteImported: cascade error — " + e.getMessage());
        }
        File tzst = importedFileFor(identifier);
        File meta = metaFileFor(identifier);
        if (tzst.exists()) tzst.delete();
        if (meta.exists()) meta.delete();
        Log.d(TAG, "deleteImported: removed " + identifier);
    }

    /**
     * The combined Graphics Driver dropdown entries = the bundled string-array entries PLUS every
     * imported wrapper's label. SHARED by both dropdown read sites (ContainerDetailViewModel and
     * ShortcutsScreen) so they can never drift — see issue #132's top-ranked risk. Order: bundled
     * first (index 0 stays the default), imports appended.
     */
    public static List<String> driverEntries(Context context, String[] bundled) {
        ArrayList<String> out = new ArrayList<>();
        if (bundled != null) for (String b : bundled) out.add(b);
        for (Imported imp : new WrapperManager(context).enumerateImported()) out.add(imp.label);
        return out;
    }

    /** Whether a bundled asset exists for the slot (defensive; the 6 are always shipped). */
    public boolean hasBundledAsset(String fileName) {
        AssetManager am = mContext.getAssets();
        try (InputStream is = am.open(ASSET_DIR + fileName)) {
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }
}
