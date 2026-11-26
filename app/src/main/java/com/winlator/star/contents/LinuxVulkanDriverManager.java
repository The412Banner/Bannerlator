package com.winlator.star.contents;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.winlator.star.core.FileUtils;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imported LINUX Vulkan drivers: glibc Turnip ICDs ({@code Turnip-<tag>[-variant]-Linux.zip} from
 * Banners-Turnip) for the Linux runtime — the gamescope session that runs Valve's native ARM64
 * Steam client. This is the driver that DRAWS there: the client's own UI (OpenGL through the
 * runtime's Zink) and every game the client launches (D3D through Proton's DXVK/VKD3D). Putting
 * the frame on the screen stays the Android driver's job, in the app's compositor
 * ({@link AdrenotoolsManager}).
 *
 * Third sibling of {@link AdrenotoolsManager} (bionic, Android surface WSI) and
 * {@link WaylandGameDriverManager} (bionic, Wayland WSI). None of the three are interchangeable:
 * the client and its games are glibc processes and cannot load a bionic object at all, and this
 * one cannot be loaded by the app or by Wine. The check that separates them is the libc the driver
 * links against — {@code libc.so.6} here, {@code libc.so} there.
 *
 * Layout (under the app's files dir, which the session sees by its full host path):
 * <pre>
 *   files/linux_vulkan_drivers/&lt;id&gt;/
 *       libvulkan_freedreno.so   the driver
 *       icd.json                 generated Vulkan ICD manifest; library_path = the .so's ABSOLUTE path
 *       meta.json                name / driverVersion / minGlibc, our own schema
 * </pre>
 * The session gets {@code icd.json}'s path and exports it as {@code VK_DRIVER_FILES}, so nothing
 * inside the runtime is modified and the choice is reversible.
 */
public class LinuxVulkanDriverManager {
    private static final String TAG = "LinuxVulkanDriver";
    public static final String DIR_NAME = "linux_vulkan_drivers";
    public static final String LIB_NAME = "libvulkan_freedreno.so";
    public static final String ICD_NAME = "icd.json";
    public static final String META_NAME = "meta.json";

    private final Context context;
    private final File rootDir;

    public LinuxVulkanDriverManager(Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), DIR_NAME);
        if (!rootDir.exists()) rootDir.mkdirs();
    }

    public File getDriverDir(String id) {
        return new File(rootDir, id);
    }

    /** Installed = has the .so AND the generated manifest (a half-extracted dir is never offered). */
    public boolean isInstalled(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return false;
        File dir = getDriverDir(id);
        return new File(dir, LIB_NAME).isFile() && new File(dir, ICD_NAME).isFile();
    }

    /** Absolute path of the driver's ICD manifest, or null when the id isn't installed. */
    public String getIcdPath(String id) {
        return isInstalled(id) ? new File(getDriverDir(id), ICD_NAME).getAbsolutePath() : null;
    }

    public List<String> enumerateInstalledDrivers() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) if (d.isDirectory() && isInstalled(d.getName())) ids.add(d.getName());
        Collections.sort(ids);
        return ids;
    }

    private JSONObject readMeta(String id) {
        try {
            File meta = new File(getDriverDir(id), META_NAME);
            if (!meta.isFile()) return null;
            return new JSONObject(FileUtils.readString(meta));
        } catch (Exception e) {
            return null;
        }
    }

    public String getDriverName(String id) {
        JSONObject m = readMeta(id);
        String name = m != null ? m.optString("name", "") : "";
        return name.isEmpty() ? id : name;
    }

    public String getDriverVersion(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("driverVersion", "") : "";
    }

    /** The glibc the driver asks for, as its zip recorded it ("" when the zip did not say). */
    public String getMinGlibc(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("minGlibc", "") : "";
    }

    public void removeDriver(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return;
        Log.d(TAG, "removing imported Linux Vulkan driver " + id);
        FileUtils.delete(getDriverDir(id));
        // A shortcut still set to this id falls back to the runtime's own driver at launch (logged there).
    }

    /**
     * Import a {@code -Linux.zip}. Returns the new driver id.
     * @throws IllegalArgumentException with a user-facing reason when the zip is not an importable
     *         Linux driver; {@link IOException} on read/extract failures.
     */
    public String installDriver(Uri zipUri, String displayName) throws IOException {
        return installDriver(zipUri, displayName, null);
    }

    /** A driver with the zip's name is installed already ({@link #installDriver(Uri, String, Boolean)} with FALSE). */
    public static final class SameNameException extends IOException {
        public final String id;
        public final String name;

        SameNameException(String id, String name) {
            super(name + " is already installed");
            this.id = id;
            this.name = name;
        }
    }

    /** The id of an installed driver called [name], or null. */
    public String findByName(String name) {
        for (String id : enumerateInstalledDrivers()) if (getDriverName(id).equals(name)) return id;
        return null;
    }

    /**
     * {@link #installDriver(Uri, String)}, with what to do when a driver of the same name is installed:
     * null adds this one beside it (a new id), FALSE refuses with {@link SameNameException}, TRUE replaces
     * it under the same id. A zip with several libvulkan_freedreno*.so (DroidDeck's android/ + linux/)
     * gives its glibc one, with the meta.json from the same folder.
     */
    public String installDriver(Uri zipUri, String displayName, Boolean replace) throws IOException {
        File tmpDir = new File(rootDir, ".tmp-" + System.currentTimeMillis());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("cannot create " + tmpDir);
        boolean keep = false;
        try {
            String soName = null;
            String soDir = null;
            // A libvulkan_freedreno*.so that links Android's libc, kept only to say so when no glibc one comes.
            String bionicSo = null;
            JSONObject zipMeta = null;
            java.util.Map<String, JSONObject> metaByDir = new java.util.HashMap<>();
            File so = new File(tmpDir, LIB_NAME);
            try (InputStream is = context.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                if (is == null) throw new IOException("cannot open " + zipUri);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    // Flatten: only the base name matters, and it also defeats zip-slip paths.
                    String base = new File(entry.getName()).getName();
                    if (base.isEmpty()) continue;
                    String dir = entry.getName().contains("/") ? entry.getName().substring(0, entry.getName().lastIndexOf('/')) : "";
                    if (base.startsWith("libvulkan_freedreno") && base.endsWith(".so")) {
                        if (soName != null) {
                            Log.w(TAG, "zip has several libvulkan_freedreno*.so; using the first glibc one (" + soName + ")");
                            continue;
                        }
                        File part = new File(tmpDir, LIB_NAME + ".part");
                        Files.copy(zis, part.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        // The one check that actually separates this from the two bionic builds. Both sonames
                        // are in the driver's .dynstr, so a byte scan is enough and needs no ELF parsing:
                        // glibc's is versioned ("libc.so.6"), bionic's is not ("libc.so").
                        if (containsAscii(part, "libc.so.6")) {
                            if (!part.renameTo(so)) throw new IOException("cannot keep " + base);
                            soName = base;
                            soDir = dir;
                        } else {
                            if (bionicSo == null) bionicSo = base;
                            FileUtils.delete(part);
                        }
                    } else if (base.equals(META_NAME)) {
                        try {
                            zipMeta = new JSONObject(new String(readAll(zis), StandardCharsets.UTF_8));
                            metaByDir.put(dir, zipMeta);
                        } catch (Exception e) {
                            Log.w(TAG, "meta.json unreadable, ignoring: " + e.getMessage());
                        }
                    }
                    // The zip's own freedreno_icd.aarch64.json is dropped on purpose: its
                    // library_path is relative to itself, and we write an absolute one below.
                }
            }
            if (soName == null && bionicSo != null) {
                throw new IllegalArgumentException(bionicSo + " is not a glibc driver — it links Android's libc. "
                        + "The Linux runtime needs a \"-Linux\" zip; a plain or \"-Wayland\" Turnip cannot be "
                        + "loaded by the Steam client at all.");
            }
            if (soName == null) {
                throw new IllegalArgumentException("No libvulkan_freedreno*.so in this zip. An Android "
                        + "(AdrenoTools) or -Wayland Turnip zip is not a Linux runtime driver.");
            }
            if (!WaylandGameDriverManager.isAarch64Elf(so)) {
                throw new IllegalArgumentException(soName + " is not a 64-bit AArch64 ELF shared library.");
            }
            // The meta.json beside the library (a zip with several drivers has one per folder).
            if (metaByDir.containsKey(soDir)) zipMeta = metaByDir.get(soDir);
            String kind = zipMeta != null ? zipMeta.optString("kind", "") : "";
            if (!kind.isEmpty() && !"linux-vulkan-icd".equals(kind)) {
                Log.w(TAG, "meta.json says kind=" + kind + ", importing anyway (the binary is glibc)");
            }

            String name = zipMeta != null ? zipMeta.optString("name", "") : "";
            String version = zipMeta != null ? zipMeta.optString("driverVersion", "") : "";
            String minGlibc = zipMeta != null ? zipMeta.optString("minGlibc", "") : "";
            if (name.isEmpty()) {
                name = displayName != null ? displayName : "linux-driver";
                if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
            }
            String existing = replace != null ? findByName(name) : null;
            if (existing != null && !replace) throw new SameNameException(existing, name);
            String id = existing != null ? existing : uniqueId(WaylandGameDriverManager.sanitizeId(name));
            File dir = getDriverDir(id);

            // ICD manifest: absolute library_path, so the loader never depends on cwd and the guest
            // reads it at the same path the app wrote (the files dir is bound into the session).
            JSONObject icd = new JSONObject();
            icd.put("file_format_version", "1.0.0");
            JSONObject icdBody = new JSONObject();
            icdBody.put("library_path", new File(dir, LIB_NAME).getAbsolutePath());
            icdBody.put("api_version", "1.1.274");
            icd.put("ICD", icdBody);
            if (!FileUtils.writeString(new File(tmpDir, ICD_NAME), icd.toString(2).replace("\\/", "/"))) throw new IOException("cannot write icd.json");

            JSONObject meta = new JSONObject();
            meta.put("schemaVersion", 1);
            meta.put("kind", "linux-vulkan-icd");
            meta.put("name", name);
            meta.put("driverVersion", version);
            meta.put("libc", "glibc");
            meta.put("minGlibc", minGlibc);
            meta.put("sourceLibraryName", soName);
            meta.put("importedAt", System.currentTimeMillis());
            if (!FileUtils.writeString(new File(tmpDir, META_NAME), meta.toString(2))) throw new IOException("cannot write meta.json");

            if (existing != null) {
                Log.i(TAG, "replacing Linux Vulkan driver " + id);
                FileUtils.delete(dir);
            }
            if (!tmpDir.renameTo(dir)) throw new IOException("cannot move into " + dir);
            keep = true;
            Log.i(TAG, "imported Linux Vulkan driver " + id + " (" + soName + ", minGlibc=" + minGlibc + ") -> " + dir);
            return id;
        } catch (org.json.JSONException e) {
            throw new IOException("manifest write failed: " + e.getMessage());
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (getDriverDir(id).exists()) id = base + "-" + (n++);
        return id;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    static boolean containsAscii(File f, String needle) {
        byte[] nb = needle.getBytes(StandardCharsets.US_ASCII);
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int carry = 0;
            int r;
            while ((r = in.read(buf, carry, buf.length - carry)) > 0) {
                int len = carry + r;
                for (int i = 0; i + nb.length <= len; i++) {
                    int j = 0;
                    while (j < nb.length && buf[i + j] == nb[j]) j++;
                    if (j == nb.length) return true;
                }
                // Keep the last needle-1 bytes so a match across a buffer boundary is still found.
                carry = Math.min(nb.length - 1, len);
                System.arraycopy(buf, len - carry, buf, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
