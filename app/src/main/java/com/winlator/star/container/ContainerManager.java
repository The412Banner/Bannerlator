package com.winlator.star.container;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.winlator.star.R;
import com.winlator.star.contents.ContentsManager;
import com.winlator.star.core.Callback;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.MSLink;
import com.winlator.star.core.OnExtractFileListener;
import com.winlator.star.core.TarCompressorUtils;
import com.winlator.star.core.WineInfo;
import com.winlator.star.core.WineRegistryEditor;
import com.winlator.star.xenvironment.ImageFs;

import java.util.Arrays;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.Executors;

public class ContainerManager {
    private final ArrayList<Container> containers = new ArrayList<>();
    private int maxContainerId = 0;
    private final File homeDir;
    private final Context context;

    private boolean isInitialized = false; // New flag to track initialization

    public ContainerManager(Context context) {
        this.context = context;
        File rootDir = ImageFs.find(context).getRootDir();
        homeDir = new File(rootDir, "home");
        loadContainers();
        migrateGyroPrefsToContainers();
        isInitialized = true;
    }

    // One-shot migration of the old GLOBAL gyro prefs onto every container. The gyro settings used to
    // live in SharedPreferences; they're per-container (and partly per-game) now, so without this a
    // user who had tuned them would silently get the defaults back. Runs at most once, keyed on
    // "gyro_migrated_to_container", and removes the old keys afterwards so it can't re-fire.
    // gyro_bias_* is deliberately NOT touched — the calibration bias is a property of this phone's
    // IMU and stays global (see GyroCalibrator).
    private void migrateGyroPrefsToContainers() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean("gyro_migrated_to_container", false)) return;

        boolean hasOldKeys = prefs.contains("gyro_enabled") || prefs.contains("gyro_target")
                || prefs.contains("gyro_activator") || prefs.contains("gyro_sensitivity")
                || prefs.contains("gyro_deadzone") || prefs.contains("gyro_smoothing")
                || prefs.contains("gyro_invert_x") || prefs.contains("gyro_invert_y");

        if (hasOldKeys) {
            boolean enabled = prefs.getBoolean("gyro_enabled", Container.GYRO_ENABLED_DEFAULT);
            int target = prefs.getInt("gyro_target", Container.GYRO_TARGET_DEFAULT);
            int activator = prefs.getInt("gyro_activator", Container.GYRO_ACTIVATOR_DEFAULT);
            float sensitivity = prefs.getFloat("gyro_sensitivity", Container.GYRO_SENSITIVITY_DEFAULT);
            float deadzone = prefs.getFloat("gyro_deadzone", Container.GYRO_DEADZONE_DEFAULT);
            float smoothing = prefs.getFloat("gyro_smoothing", Container.GYRO_SMOOTHING_DEFAULT);
            boolean invertX = prefs.getBoolean("gyro_invert_x", Container.GYRO_INVERT_X_DEFAULT);
            boolean invertY = prefs.getBoolean("gyro_invert_y", Container.GYRO_INVERT_Y_DEFAULT);

            for (Container container : containers) {
                container.setGyroEnabled(enabled);
                container.setGyroTarget(target);
                container.setGyroActivator(activator);
                container.setGyroSensitivity(sensitivity);
                container.setGyroDeadzone(deadzone);
                container.setGyroSmoothing(smoothing);
                container.setGyroInvertX(invertX);
                container.setGyroInvertY(invertY);
                container.saveData();
            }
            Log.i("ContainerManager", "Migrated global gyro settings onto " + containers.size() + " container(s)");
        }

        prefs.edit()
            .remove("gyro_enabled")
            .remove("gyro_target")
            .remove("gyro_activator")
            .remove("gyro_sensitivity")
            .remove("gyro_deadzone")
            .remove("gyro_smoothing")
            .remove("gyro_invert_x")
            .remove("gyro_invert_y")
            .putBoolean("gyro_migrated_to_container", true)
            .apply();
    }

    // Check if the ContainerManager is fully initialized
    public boolean isInitialized() {
        return isInitialized;
    }

    public ArrayList<Container> getContainers() {
        return containers;
    }

    // Re-scan the home dir so this instance's in-memory list picks up containers created (or removed)
    // by a *different* ContainerManager instance since construction. Each screen news up its own
    // manager, so a container created in the editor is otherwise invisible to a long-lived manager
    // (e.g. ShortcutsViewModel's) until that ViewModel is reconstructed. Cheap disk walk; call it
    // before reading getContainers() on a manager that outlives a create/delete elsewhere.
    public void reloadContainers() {
        loadContainers();
    }

    // Load containers from the home directory
    private void loadContainers() {
        containers.clear();
        maxContainerId = 0;

        try {
            File[] files = homeDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        if (file.getName().startsWith(ImageFs.USER + "-")) {
                            Container container = new Container(
                                    Integer.parseInt(file.getName().replace(ImageFs.USER + "-", "")), this
                            );

                            container.setRootDir(new File(homeDir, ImageFs.USER + "-" + container.id));
                            JSONObject data = new JSONObject(FileUtils.readString(container.getConfigFile()));
                            container.loadData(data);
                            containers.add(container);
                            maxContainerId = Math.max(maxContainerId, container.id);
                        }
                    }
                }
            }
        } catch (JSONException | NullPointerException e) {
            Log.e("ContainerManager", "Error loading containers", e);
        }
    }


    public Context getContext() {
        return context;
    }


    public void activateContainer(Container container) {
        container.setRootDir(new File(homeDir, ImageFs.USER+"-"+container.id));
        File file = new File(homeDir, ImageFs.USER);
        file.delete();
        FileUtils.symlink("./"+ImageFs.USER+"-"+container.id, file.getPath());
    }

    public void createContainerAsync(final JSONObject data, ContentsManager contentsManager, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container container = createContainer(data, contentsManager);
            handler.post(() -> callback.call(container));
        });
    }

    public void duplicateContainerAsync(Container container, Callback<Container> callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            final Container result = duplicateContainer(container);
            handler.post(() -> callback.call(result));
        });
    }

    public void removeContainerAsync(Container container, Runnable callback) {
        final Handler handler = new Handler();
        Executors.newSingleThreadExecutor().execute(() -> {
            removeContainer(container);
            handler.post(callback);
        });
    }

    private Container createContainer(JSONObject data, ContentsManager contentsManager) {
        try {
            int id = maxContainerId + 1;
            data.put("id", id);

            File containerDir = new File(homeDir, ImageFs.USER+"-"+id);
            if (containerDir.exists()) {
                // An orphan directory can be left behind for a deleted container id — e.g. a
                // shortcut import calls getDesktopDir().mkdirs() against a stale container, which
                // recreates xuser-<id>/. Such a dir has no ".container" config; clear it so
                // creation can proceed instead of silently failing on mkdirs() == false. A dir that
                // DOES have a config is a genuine id collision → bail. (issue #45)
                if (new File(containerDir, ".container").isFile()) return null;
                FileUtils.delete(containerDir);
            }
            if (!containerDir.mkdirs()) return null;

            Container container = new Container(id, this);
            container.setRootDir(containerDir);
            container.loadData(data);

            container.setWineVersion(data.getString("wineVersion"));

            if (!extractContainerPatternFile(container, container.getWineVersion(), contentsManager, containerDir, null)) {
                FileUtils.delete(containerDir);
                return null;
            }

            // "Run as administrator" toggle (default ON). Wine's wineboot leaves EnableLUA=1 for most
            // Wine versions (only some prefixPacks ship it off), which makes installers/tools that
            // query the elevation token refuse to run. When the toggle is ON we stamp EnableLUA=0 on
            // the freshly-extracted prefix so the container runs elevated (UAC off) regardless of the
            // Wine version's default; OFF leaves UAC on (EnableLUA=1). The container editor mirrors
            // this per-container in edit mode via the registry (system.reg = source of truth).
            boolean runAsAdmin = data.optBoolean("runAsAdmin", true);
            File systemRegFile = new File(containerDir, ".wine/system.reg");
            try (WineRegistryEditor registryEditor = new WineRegistryEditor(systemRegFile)) {
                registryEditor.setCreateKeyIfNotExist(true);
                registryEditor.setDwordValue("Software\\Microsoft\\Windows\\CurrentVersion\\Policies\\System", "EnableLUA", runAsAdmin ? 0 : 1);
            }

//            // Extract the selected graphics driver files
//            String driverVersion = container.getGraphicsDriverVersion();
//            if (!extractGraphicsDriverFiles(driverVersion, containerDir, null)) {
//                FileUtils.delete(containerDir);
//                return null;
//            }

            container.saveData();
            maxContainerId++;
            containers.add(container);
            return container;
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return null;
    }


    private Container duplicateContainer(Container srcContainer) {
        int id = maxContainerId + 1;

        File srcDir = srcContainer.getRootDir();
        File dstDir = new File(homeDir, ImageFs.USER + "-" + id);
        if (!dstDir.mkdirs()) return null;

        // copyContainer PRESERVES symlinks (drive letters in .wine/dosdevices) and skips
        // individual unreadable files instead of aborting the whole duplicate. It returns
        // false only when the destination root itself couldn't be created.
        int[] skipped = new int[1];
        if (!FileUtils.copyContainer(srcDir, dstDir, srcDir.getAbsolutePath(), dstDir.getAbsolutePath(),
                file -> FileUtils.chmod(file, 0771), skipped)) {
            FileUtils.delete(dstDir);
            return null;
        }
        if (skipped[0] > 0)
            Log.w("ContainerManager", "Container duplicate skipped " + skipped[0] + " unreadable file(s)");

        Container dstContainer = new Container(id, this);
        dstContainer.setRootDir(dstDir);

        // Copy the FULL source config (40+ fields) so nothing is dropped — the old
        // field-by-field block silently lost graphicsDriverConfig, renderer*, frameGen*,
        // fpsLimiter*, fexcore*, reshade*, refreshRate, inputType, controllerMapping, etc.,
        // which left the duplicate misconfigured (empty driver id -> missing meta.json ->
        // crash on launch). Load the source's .container JSON, force the NEW id (mirrors
        // createContainer's data.put("id", id) before loadData), then override only the name.
        try {
            JSONObject data = new JSONObject(FileUtils.readString(srcContainer.getConfigFile()));
            data.put("id", id);
            dstContainer.loadData(data);
        } catch (JSONException e) {
            Log.e("ContainerManager", "Failed to copy container config during duplicate", e);
            FileUtils.delete(dstDir);
            return null;
        }
        dstContainer.setName(srcContainer.getName() + " (" + context.getString(R.string._copy) + ")");
        dstContainer.saveData();

        maxContainerId++;
        containers.add(dstContainer);
        return dstContainer;
    }


    private void removeContainer(Container container) {
        if (FileUtils.delete(container.getRootDir())) containers.remove(container);
    }

    /** Desktop .lnk names written by store clients we install on the game's behalf — not games. */
    private static final java.util.Set<String> VENDOR_CLIENT_LNK = new java.util.HashSet<>(Arrays.asList(
            "EA", "EA app", "EA Desktop", "EA app Updater", "App Recovery", "EA Error Reporter", "Origin",
            "Ubisoft Connect", "Uplay"));

    public ArrayList<Shortcut> loadShortcuts() {
        ArrayList<Shortcut> shortcuts = new ArrayList<>();
        for (Container container : containers) {
            File desktopDir = container.getDesktopDir();
            ArrayList<File> files = new ArrayList<>();
            if (desktopDir.exists())
                files.addAll(Arrays.asList(desktopDir.listFiles()));
            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    if (fileName.endsWith(".lnk")) {
                        // Store-client installers (EA Desktop, Ubisoft Connect) drop their own desktop
                        // shortcuts via winemenubuilder; those are not games — never auto-import them.
                        String base = fileName.substring(0, fileName.length() - 4);
                        if (VENDOR_CLIENT_LNK.contains(base)) continue;
                        String filePath = file.getPath();
                        File desktopFile = new File(filePath.substring(0, filePath.lastIndexOf(".")) + ".desktop");
                        if (!desktopFile.exists()) {
                            MSLink.createDesktopFile(file, context);
                            Shortcut shortcut = loadShortcutOrNull(container, desktopFile);
                            if (shortcut != null) shortcuts.add(shortcut);
                        }
                    }
                    else if (fileName.endsWith(".desktop")) {
                        // winemenubuilder writes its own .desktop entries (no .lnk sibling here — the
                        // .lnk lives in C:/users/Public/Desktop) when a store client such as EA Desktop
                        // installs, and it rewrites them on every client start. Those are launchers,
                        // not games — keep them out of the Games grid.
                        if (isVendorClientDesktopEntry(file)) continue;
                        Shortcut shortcut = loadShortcutOrNull(container, file);
                        if (shortcut != null) shortcuts.add(shortcut);
                    }
                }
            }
        }

        shortcuts.sort(Comparator.comparing(a -> a.name));
        return shortcuts;
    }

    /**
     * Parses one .desktop entry, or returns null when it cannot be turned into a shortcut. One
     * unreadable or truncated file (permission denied, missing/empty `Exec=` line, half-written by an
     * external tool) used to throw out of the constructor and take the whole Games screen down at app
     * start; a bad entry is now skipped and logged so the remaining shortcuts still load.
     */
    private static Shortcut loadShortcutOrNull(Container container, File file) {
        try {
            return new Shortcut(container, file);
        }
        catch (RuntimeException e) {
            Log.w("ContainerManager", "Skipping unreadable shortcut " + file.getPath() + ": " + e);
            return null;
        }
    }

    /** Window classes of store-client executables whose winemenubuilder .desktop entries are not games. */
    private static final java.util.Set<String> VENDOR_CLIENT_WMCLASS = new java.util.HashSet<>(Arrays.asList(
            "ealauncher.exe", "eadesktop.exe", "eaapp.exe", "eabackgroundservice.exe", "eaapprecovery.exe",
            "eaerrorreporter.exe", "link2ea.exe", "origin.exe", "originwebhelperservice.exe",
            "upc.exe", "uplay.exe", "ubisoftconnect.exe", "ubisoftconnectinstaller.exe"));

    /**
     * True for a Desktop .desktop entry that a store client (EA Desktop / Origin / Ubisoft Connect) dropped
     * through winemenubuilder rather than one Bannerlator wrote for a game: matched by the vendor launcher
     * name, by the window class winemenubuilder records, or by an Exec that just re-opens the vendor .lnk.
     */
    static boolean isVendorClientDesktopEntry(File desktopFile) {
        String name = desktopFile.getName();
        String base = name.substring(0, name.length() - ".desktop".length());
        if (VENDOR_CLIENT_LNK.contains(base)) return true;
        String text;
        try {
            text = FileUtils.readString(desktopFile);
        } catch (Exception e) {
            return false;
        }
        if (text == null) return false;
        if (text.contains("storeSource=") || text.contains("steamAppId=")) return false; // ours
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.startsWith("StartupWMClass=")) {
                if (VENDOR_CLIENT_WMCLASS.contains(l.substring("StartupWMClass=".length()).trim().toLowerCase(java.util.Locale.ROOT))) return true;
            } else if (l.startsWith("Exec=")) {
                // .desktop Exec lines escape each backslash ("C:\\\\users\\\\Public\\\\Desktop\\\\EA.lnk"); collapse runs first.
                String exec = l.toLowerCase(java.util.Locale.ROOT).replaceAll("\\\\+", "\\\\");
                if (exec.contains("public\\desktop\\ea") || exec.contains("\\electronic arts\\") || exec.contains("\\ea desktop\\")
                        || exec.contains("\\origin\\origin.exe") || exec.contains("\\ubisoft game launcher\\")) return true;
            } else if (l.startsWith("Path=")) {
                String path = l.toLowerCase(java.util.Locale.ROOT);
                if (path.contains("/electronic arts/ea desktop/") || path.contains("/origin/")
                        || path.contains("/ubisoft game launcher/")) return true;
            }
        }
        return false;
    }

    public int getNextContainerId() {
        return maxContainerId + 1;
    }

    public Container getContainerById(int id) {
        for (Container container : containers) if (container.id == id) return container;
        return null;
    }

    private void extractCommonDlls(WineInfo wineInfo, String srcName, String dstName, File containerDir, OnExtractFileListener onExtractFileListener) throws JSONException {
        File srcDir = new File(wineInfo.path + "/lib/wine/" + srcName);

        File[] srcfiles = srcDir.listFiles(file -> file.isFile());

        for (File file : srcfiles) {
            String dllName = file.getName();
            if (dllName.equals("iexplore.exe") && wineInfo.isArm64EC() && srcName.equals("aarch64-windows"))
                file = new File(wineInfo.path + "/lib/wine/" + "i386-windows/iexplore.exe");
            if (dllName.equals("tabtip.exe") || dllName.equals("icu.dll"))
                continue;
            File dstFile = new File(containerDir, ".wine/drive_c/windows/" + dstName + "/" + dllName);
            if (dstFile.exists()) continue;
            if (onExtractFileListener != null ) {
                dstFile = onExtractFileListener.onExtractFile(dstFile, 0);
                if (dstFile == null) continue;
            }
            FileUtils.copy(file, dstFile);
        }
    }

    public boolean extractContainerPatternFile(Container container, String wineVersion, ContentsManager contentsManager, File containerDir, OnExtractFileListener onExtractFileListener) {
        WineInfo wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion);
        String containerPattern = wineVersion + "_container_pattern.tzst";
        boolean result = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, containerPattern, containerDir, onExtractFileListener);

        if (!result) {
            File containerPatternFile = new File(wineInfo.path + "/prefixPack.txz");
            result = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, containerPatternFile, containerDir);
        }

        if (result) {
            try {
                if (wineInfo.isArm64EC())
                    extractCommonDlls(wineInfo, "aarch64-windows", "system32", containerDir, onExtractFileListener); // arm64ec only
                else
                    extractCommonDlls(wineInfo, "x86_64-windows", "system32", containerDir, onExtractFileListener);

                extractCommonDlls(wineInfo, "i386-windows", "syswow64", containerDir, onExtractFileListener);
            }
            catch (JSONException e) {
                return false;
            }
        }
   
        return result;
    }

    public Container getContainerForShortcut(Shortcut shortcut) {
        // Search for the container by its ID
        for (Container container : containers) {
            if (container.id == shortcut.getContainerId()) {
                return container;
            }
        }
        return null;  // Return null if no matching container is found
    }

        public void importContainer(File importDir, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!importDir.exists() || !importDir.isDirectory()) {
                    Log.e("ContainerManager", "Invalid container directory for import: " + importDir.getPath());
                    return;
                }

                // Get the next container ID and set the new container name
                int newContainerId = getNextContainerId();
                String newContainerName = ImageFs.USER + "-" + newContainerId;
                File newContainerDir = new File(homeDir, newContainerName);

                if (newContainerDir.exists()) {
                    Log.e("ContainerManager", "Container directory already exists: " + newContainerDir.getPath());
                    return;
                }

                if (!newContainerDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create directory: " + newContainerDir.getPath());
                    return;
                }

                // Copy the files from the import directory to the new container directory
                if (!FileUtils.copy(importDir, newContainerDir, file -> FileUtils.chmod(file, 0771))) {
                    FileUtils.delete(newContainerDir);
                    Log.e("ContainerManager", "Failed to copy container files to: " + newContainerDir.getPath());
                    return;
                }

                // Create the new container object and save its data
                Container newContainer = new Container(newContainerId, this);
                newContainer.setRootDir(newContainerDir);
                newContainer.setName(importDir.getName());
                newContainer.saveData();
                containers.add(newContainer);
                maxContainerId++;

                Log.d("ContainerManager", "Container imported successfully to: " + newContainerDir.getPath());
                // Make sure to run the callback after successful import
                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to import container from: " + importDir.getPath(), e);
            }
        });
    }

    public void exportContainer(Container container, Runnable callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                // Create the export directory path
                File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");

                if (!exportDir.exists() && !exportDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create export directory: " + exportDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                File containerDir = container.getRootDir();
                File destinationDir = new File(exportDir, containerDir.getName());

                if (destinationDir.exists()) {
                    Log.e("ContainerManager", "Export directory already exists: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                if (!destinationDir.mkdirs()) {
                    Log.e("ContainerManager", "Failed to create directory: " + destinationDir.getPath());
                    runOnUiThread(() -> callback.run()); // Close the preloader dialog
                    return;
                }

                // copyContainer tolerates individual unreadable/locked files (skips them instead
                // of aborting the whole export — the "sometimes works sometimes not" failure) and
                // preserves symlinks. Symlink recreation no-ops gracefully on the export FS
                // (Os.symlink ErrnoException is swallowed by FileUtils.symlink). Fails only if the
                // destination root itself can't be created.
                if (!FileUtils.copyContainer(containerDir, destinationDir,
                        containerDir.getAbsolutePath(), destinationDir.getAbsolutePath(),
                        file -> FileUtils.chmod(file, 0771))) {
                    Log.e("ContainerManager", "Failed to export container files to: " + destinationDir.getPath());
                    FileUtils.delete(destinationDir); // Optional: Delete partially copied directory
                }

                Log.d("ContainerManager", "Container exported successfully to: " + destinationDir.getPath());
            } catch (Exception e) {
                Log.e("ContainerManager", "Failed to export container: " + container.getName(), e);
            } finally {
                runOnUiThread(callback); // Ensure the callback runs and preloader dialog closes
            }
        });
    }

    // Utility method to run on UI thread
    private void runOnUiThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }



}


