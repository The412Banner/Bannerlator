package com.winlator.star.contents;

import android.content.res.AssetManager;
import android.net.Uri;

import android.content.Context;
import android.util.Log;

import com.winlator.star.SettingsFragment;
import com.winlator.star.container.Container;
import com.winlator.star.container.Shortcut;
import com.winlator.star.container.ContainerManager;
import com.winlator.star.core.DefaultVersion;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.GPUInformation;
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog;
import com.winlator.star.core.TarCompressorUtils;
import com.winlator.star.xenvironment.ImageFs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.json.JSONException;
import org.json.JSONObject;

public class AdrenotoolsManager {
    
    private File adrenotoolsContentDir;
    private Context mContext;
    
    public AdrenotoolsManager(Context context) {
        this.mContext = context;
        this.adrenotoolsContentDir = new File(mContext.getFilesDir(), "contents/adrenotools");
        if (!adrenotoolsContentDir.exists())
            adrenotoolsContentDir.mkdirs();
    }
        
    public String getLibraryName(String adrenoToolsDriverId) {
        String libraryName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            // Guard: a missing/unknown driver id has no meta.json -> readString would NPE
            // (read() returns null). This runs inside a native JNI callback, so an uncaught
            // throw = SIGABRT. Degrade to "" instead of crashing the whole app.
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            libraryName = jsonObject.getString("libraryName");
        }
        catch (Exception e) {
        }
        return libraryName;
    }

    public String getDriverName(String adrenoToolsDriverId) {
        String driverName = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverName = jsonObject.getString("name");
        }
        catch (Exception e) {
        }
        return driverName;
    }

    public String getDriverVersion(String adrenoToolsDriverId) {
        String driverVersion = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            driverVersion = jsonObject.getString("driverVersion");
        }
        catch (Exception e) {
        }
        return driverVersion;
    }

    // meta.json "vendor" ("Mesa", "Qualcomm", ...), or "" when the package doesn't declare one
    // (the bundled turnip-sdk36 meta.json carries only libraryName).
    public String getDriverVendor(String adrenoToolsDriverId) {
        String vendor = "";
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        try {
            File metaProfile = new File(driverPath, "meta.json");
            if (!metaProfile.exists()) return "";
            JSONObject jsonObject = new JSONObject(FileUtils.readString(metaProfile));
            vendor = jsonObject.optString("vendor", "");
        }
        catch (Exception e) {
        }
        return vendor;
    }

    public String getDriverPath(String adrenotoolsDriverId) {
        return adrenotoolsContentDir.getAbsolutePath() + "/" + adrenotoolsDriverId + "/";
    }

    // Migrate every container/shortcut still pointing at the driver being removed onto the default
    // wrapper. Matching is by the driver's NAME, read from its meta.json.
    //
    // Two guards, because this is the one place that rewrites configuration the user never touched:
    //  • an absent/unreadable meta.json makes getDriverName() return "", and String.contains("") is
    //    true for EVERY container and EVERY shortcut — that reset the whole library's graphics
    //    driver on a single driver delete. Nothing to match against means nothing to migrate.
    //  • a shortcut with no graphicsDriverConfig of its own INHERITS the container's. Rewriting it
    //    pinned a per-game override onto a game that never had one; the container was already
    //    migrated above, so leaving it alone is both correct and lossless.
    private void reloadContainers(String adrenoToolsDriverId) {
        String driverName = getDriverName(adrenoToolsDriverId);
        if (driverName.isEmpty()) {
            Log.w("AdrenotoolsManager", "No driver name for " + adrenoToolsDriverId + " (missing meta.json?) - skipping container migration");
            return;
        }
        String fallback = GPUInformation.isDriverSupported(DefaultVersion.WRAPPER_ADRENO, mContext)
                ? DefaultVersion.WRAPPER_ADRENO : DefaultVersion.WRAPPER;

        ContainerManager containerManager = new ContainerManager(mContext);
        for (Container container : containerManager.getContainers()) {
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(container.getGraphicsDriverConfig());
            String version = config.get("version");
            Log.d("AdrenotoolsManager", "Checking if container driver version " + version + " matches " + driverName);
            if (version == null || !version.contains(driverName)) continue;
            Log.d("AdrenotoolsManager", "Found a match for container " + container.getName());
            config.put("version", fallback);
            container.setGraphicsDriverConfig(GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
            container.saveData();
        }
        for (Shortcut shortcut : containerManager.loadShortcuts()) {
            String own = shortcut.getExtra("graphicsDriverConfig", "");
            if (own.isEmpty()) continue;   // inherits the container, which was just migrated
            HashMap<String, String> config = GraphicsDriverConfigDialog.parseGraphicsDriverConfig(own);
            String version = config.get("version");
            Log.d("AdrenotoolsManager", "Checking if shortcut driver version " + version + " matches " + driverName);
            if (version == null || !version.contains(driverName)) continue;
            Log.d("AdrenotoolsManager", "Found a match for shortcut " + shortcut.name);
            config.put("version", fallback);
            shortcut.putExtra("graphicsDriverConfig", GraphicsDriverConfigDialog.toGraphicsDriverConfig(config));
            shortcut.saveData();
        }
    }
    
    public void removeDriver(String adrenoToolsDriverId) {
        Log.d("AdrenotoolsManager", "Removing driver " + adrenoToolsDriverId);
        File driverPath = new File(adrenotoolsContentDir, adrenoToolsDriverId);
        reloadContainers(adrenoToolsDriverId);
        FileUtils.delete(driverPath);
    }

    public ArrayList<String> enumarateInstalledDrivers() {
        ArrayList<String> driversList = new ArrayList<>();
        File[] files = adrenotoolsContentDir.listFiles();
        if (files == null) return driversList;
        for (File f : files) {
            // An install's working folder (".tmp-…", and "tmp" from older builds) is never a driver.
            if (f.getName().startsWith(".") || f.getName().equals("tmp")) continue;
            boolean fromResources = isFromResources(f.getName());
            if (!fromResources && new File(f, "meta.json").exists())
                driversList.add(f.getName());
        }
        return driversList;
    }
    
    public boolean isFromResources(String adrenotoolsDriverId) {
        String driver = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        AssetManager am = mContext.getResources().getAssets();
        InputStream is = null;
        boolean isFromResources = true;
        
        try {
            is = am.open(driver);
            is.close();
        }
        catch (IOException e) {
            isFromResources = false;
        }
        
        return isFromResources;
    }
        
    public boolean extractDriverFromResources(String adrenotoolsDriverId) {
        String src = "graphics_driver/adrenotools-" + adrenotoolsDriverId + ".tzst";
        boolean hasExtracted;

        File dst = new File(adrenotoolsContentDir, adrenotoolsDriverId);
        if (dst.exists())
            return true;

        dst.mkdirs();
        Log.d("AdrenotoolsManager", "Extracting " + src + " to " + dst.getAbsolutePath());
        hasExtracted = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, mContext, src, dst);

        if (!hasExtracted)
            dst.delete();

        return hasExtracted;
    }
    
    /** The new driver's id, or "" when nothing was installed ({@link #install} says why). */
    public String installDriver(Uri driverUri) {
        return install(driverUri, false).id;
    }

    /** What {@link #install} did: the new driver id, or why nothing was installed. */
    public static final class InstallResult {
        public static final String NO_META = "no-meta";
        public static final String BAD_META = "bad-meta";
        public static final String LINUX_KIND = "linux-kind";
        public static final String WAYLAND_KIND = "wayland-kind";
        public static final String NO_LIBRARY = "no-library";
        public static final String LIBRARY_MISSING = "library-missing";
        public static final String BUILT_IN = "built-in";
        public static final String SAME_NAME = "same-name";
        public static final String IO = "io";

        /** The installed driver's id (its folder, = meta.json "name"); "" when nothing was installed. */
        public final String id;
        /** One of the codes above; null on success. */
        public final String reason;
        /** The library name, the driver name or the error text that goes with [reason]. */
        public final String detail;

        private InstallResult(String id, String reason, String detail) {
            this.id = id;
            this.reason = reason;
            this.detail = detail;
        }

        static InstallResult ok(String id) { return new InstallResult(id, null, null); }
        static InstallResult fail(String reason, String detail) { return new InstallResult("", reason, detail); }
    }

    // Installs run one at a time: two downloads finishing together used to extract into the same
    // "tmp" folder and could each move the other's files, or a mix of both, into place.
    private static final Object INSTALL_LOCK = new Object();

    /**
     * Installs a driver zip. A zip whose files all sit in one folder is taken from that folder.
     * A driver with the same name stays as it is unless [replace]; a bundled driver's name is
     * never taken over. Blocking.
     */
    public InstallResult install(Uri driverUri, boolean replace) {
        synchronized (INSTALL_LOCK) {
            File tmpDir = new File(adrenotoolsContentDir, ".tmp-" + System.nanoTime());
            try {
                if (!tmpDir.mkdirs()) return InstallResult.fail(InstallResult.IO, "cannot create " + tmpDir);
                try (InputStream is = mContext.getContentResolver().openInputStream(driverUri)) {
                    if (is == null) return InstallResult.fail(InstallResult.IO, "cannot open " + driverUri);
                    extractZip(is, tmpDir);
                }
                File root = driverRoot(tmpDir);
                InstallResult rejected = check(root);
                if (rejected != null) {
                    Log.d("AdrenotoolsManager", "not installed: " + rejected.reason + " " + rejected.detail);
                    return rejected;
                }
                String name = readMeta(root).optString("name", "").trim();
                if (name.isEmpty() || name.startsWith(".") || name.contains("/") || name.contains("..") || name.equals("tmp"))
                    return InstallResult.fail(InstallResult.BAD_META, "name \"" + name + "\"");
                if (isFromResources(name)) return InstallResult.fail(InstallResult.BUILT_IN, name);
                File dst = new File(adrenotoolsContentDir, name);
                if (dst.exists()) {
                    if (!replace) return InstallResult.fail(InstallResult.SAME_NAME, name);
                    Log.i("AdrenotoolsManager", "replacing driver " + name);
                    FileUtils.delete(dst);
                }
                if (!root.renameTo(dst)) return InstallResult.fail(InstallResult.IO, "cannot move into " + dst);
                Log.i("AdrenotoolsManager", "installed driver " + name);
                return InstallResult.ok(name);
            }
            catch (Exception e) {
                Log.w("AdrenotoolsManager", "driver install failed", e);
                return InstallResult.fail(InstallResult.IO, e.getMessage() != null ? e.getMessage() : e.toString());
            }
            finally {
                FileUtils.delete(tmpDir);
            }
        }
    }

    /** Unpacks every entry under [dir], keeping folders; an entry that points outside [dir] is refused. */
    private static void extractZip(InputStream is, File dir) throws IOException {
        String base = dir.getCanonicalPath() + File.separator;
        ZipInputStream zis = new ZipInputStream(is);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            File out = new File(dir, entry.getName());
            if (!out.getCanonicalPath().startsWith(base)) throw new IOException("bad path in zip: " + entry.getName());
            if (entry.isDirectory()) {
                out.mkdirs();
                continue;
            }
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
            Files.copy(zis, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The folder holding meta.json: the zip's top level, or its only folder when everything sits in one. */
    private static File driverRoot(File dir) {
        if (new File(dir, "meta.json").isFile()) return dir;
        File only = null;
        int count = 0;
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (k.getName().equals("__MACOSX")) continue;
                count++;
                only = k;
            }
        }
        if (count == 1 && only.isDirectory() && new File(only, "meta.json").isFile()) return only;
        return dir;
    }

    private static JSONObject readMeta(File dir) {
        try {
            return new JSONObject(FileUtils.readString(new File(dir, "meta.json")));
        }
        catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * Why this extracted zip is not an AdrenoTools driver, or null when it is one. An AdrenoTools
     * driver is one the Android Vulkan loader can be pointed at: meta.json names the library in
     * "libraryName" and that library is in the zip. Without this check ANY zip carrying a meta.json
     * installed here - including our own "-Linux" (glibc) and "-Wayland" Turnip zips, which name no
     * library on purpose. They then appeared in the Android display-driver list, where setDriverById
     * quietly sets nothing because libraryName is empty: a black screen wearing a driver's name.
     */
    private static InstallResult check(File dir) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.isFile()) return InstallResult.fail(InstallResult.NO_META, null);
        String libraryName, kind;
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(metaFile));
            libraryName = meta.optString("libraryName", "");
            kind = meta.optString("kind", "");
        }
        catch (Exception e) {
            return InstallResult.fail(InstallResult.BAD_META, e.getMessage());
        }
        if ("linux-vulkan-icd".equals(kind)) return InstallResult.fail(InstallResult.LINUX_KIND, null);
        if ("wayland-game-driver".equals(kind)) return InstallResult.fail(InstallResult.WAYLAND_KIND, null);
        if (libraryName.isEmpty()) return InstallResult.fail(InstallResult.NO_LIBRARY, null);
        if (!new File(dir, libraryName).isFile()) return InstallResult.fail(InstallResult.LIBRARY_MISSING, libraryName);
        return null;
    }

    public void setDriverById(EnvVars envVars, ImageFs imagefs, String adrenotoolsDriverId) {
        boolean isFromResources = isFromResources(adrenotoolsDriverId);

        if (isFromResources || enumarateInstalledDrivers().contains(adrenotoolsDriverId)) {
            String driverPath = getDriverPath(adrenotoolsDriverId);

            if (!getLibraryName(adrenotoolsDriverId).equals("")) {
                envVars.put("ADRENOTOOLS_DRIVER_PATH", driverPath);
                envVars.put("ADRENOTOOLS_HOOKS_PATH", imagefs.getLibDir());
                envVars.put("ADRENOTOOLS_DRIVER_NAME", getLibraryName(adrenotoolsDriverId));

                File winlatorDir = new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
                File qglConfig = new File(winlatorDir, "qgl_config.txt");
                if (qglConfig.exists())
                    envVars.put("ADRENOTOOLS_REDIRECT_DIR", winlatorDir.getAbsolutePath() + "/");
            }
        }
    }
 }
