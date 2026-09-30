package com.winlator.star.linux;

import android.content.Context;
import android.util.Log;

import com.winlator.star.contents.Downloader;
import com.winlator.star.core.FileUtils;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Downloads and unpacks the Linux runtime rootfs into {@code files/linuxfs}. It is opt-in: nothing
 * fetches it until the user asks for it, and a gamescope session refuses to start without it.
 *
 * <p>The catalog row names the build, its URL and its sha256, so an interrupted or corrupted
 * download is caught before anything is written into the app's files directory.
 */
public final class LinuxRuntimeInstaller {
    private static final String TAG = "LinuxRuntimeInstaller";

    /** Catalog row, beside the other component catalogs in winlator-contents. */
    public static final String CATALOG_URL =
            "https://raw.githubusercontent.com/The412Banner/winlator-contents/main/linuxfs.json";

    private static final String VERSION_FILE = ".version";

    public interface ProgressListener {
        /** {@code percent} is -1 while the size is unknown. */
        void onProgress(String stage, int percent);
    }

    public static final class Release {
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;

        Release(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private LinuxRuntimeInstaller() {}

    /** The build currently unpacked, or null when the runtime is not installed. */
    public static String installedVersion(Context context) {
        recoverInterruptedSwap(context);
        File marker = new File(LinuxRuntime.rootDir(context), VERSION_FILE);
        if (!marker.isFile() || !LinuxRuntime.isInstalled(context)) return null;
        String v = FileUtils.readString(marker);
        return v == null ? null : v.trim();
    }

    /** The catalog's current build, or null when it cannot be reached or read. */
    public static Release fetchRelease() {
        String body = Downloader.downloadString(CATALOG_URL);
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject json = new JSONObject(body);
            String version = json.optString("version", "");
            String url = json.optString("url", "");
            String sha256 = json.optString("sha256", "");
            if (version.isEmpty() || url.isEmpty() || sha256.isEmpty()) return null;
            return new Release(version, url, sha256, json.optLong("size", 0L));
        } catch (Exception e) {
            Log.w(TAG, "catalog: " + e);
            return null;
        }
    }

    /**
     * The guest's home directory, carried across an update rather than replaced with the tarball's
     * empty one. Steam installs itself here - the client, the signed-in account and every
     * downloaded game - so replacing the rootfs wholesale used to delete all three and leave the
     * user re-downloading Steam and signing in again, with their library gone.
     */
    private static final String USER_DATA = "root";

    /**
     * Downloads {@code release} and replaces whatever is installed with it. Returns false and
     * leaves the existing runtime alone if the download or the checksum fails; the new rootfs is
     * only moved into place once it has been unpacked whole.
     *
     * <p>{@link #USER_DATA} survives the swap: the system is replaced, what the user put in it is
     * not. An update therefore keeps Steam, the login and the installed games.
     */
    public static boolean install(Context context, Release release, ProgressListener listener) {
        File archive = new File(context.getCacheDir(), "linuxfs.tar.zst");
        installing = true;
        try {
            if (listener != null) listener.onProgress("Downloading", 0);
            // Downloader reports a 0..1 fraction, or -1 while the total size is unknown.
            boolean ok = Downloader.downloadFile(release.url, archive, true, (fraction) -> {
                if (listener != null) {
                    listener.onProgress("Downloading",
                            fraction < 0 ? -1 : Math.round(fraction * 100f));
                }
            });
            if (!ok) {
                Log.w(TAG, "download failed");
                return false;
            }

            if (listener != null) listener.onProgress("Verifying", -1);
            String actual = sha256(archive);
            if (!release.sha256.equalsIgnoreCase(actual)) {
                Log.w(TAG, "checksum mismatch: wanted " + release.sha256 + ", got " + actual);
                return false;
            }

            // Unpack beside the live rootfs and swap, so a failure here cannot leave a half
            // runtime that isInstalled() would happily launch.
            File root = LinuxRuntime.rootDir(context);
            File staging = new File(root.getParentFile(), LinuxRuntime.DIR + ".new");
            File old = new File(root.getParentFile(), LinuxRuntime.DIR + ".old");
            recoverInterruptedSwap(root, staging, old);
            FileUtils.delete(staging);
            if (!staging.mkdirs()) return false;
            if (listener != null) listener.onProgress("Extracting", -1);
            if (!extract(archive, staging, listener)) {
                FileUtils.delete(staging);
                return false;
            }
            FileUtils.writeString(new File(staging, VERSION_FILE), release.version);

            FileUtils.delete(old);
            if (root.isDirectory() && !root.renameTo(old)) {
                FileUtils.delete(staging);
                return false;
            }

            // Carry the user's home over before the new rootfs takes the name. A rename inside the
            // same filesystem, so a 30 GB library costs nothing and cannot half-copy; the tarball's
            // own empty /root is dropped first so the rename has somewhere to land. If this fails
            // the update is abandoned and the previous runtime is put back untouched - shipping a
            // working system with the user's games gone is the worse outcome.
            File keptFrom = new File(old, USER_DATA);
            if (keptFrom.isDirectory()) {
                File keptTo = new File(staging, USER_DATA);
                FileUtils.delete(keptTo);
                if (!keptFrom.renameTo(keptTo)) {
                    Log.w(TAG, "could not carry " + USER_DATA + " across the update; rolling back");
                    FileUtils.delete(staging);
                    old.renameTo(root);
                    return false;
                }
            }

            if (!staging.renameTo(root)) {
                // The user's home is inside staging by now: take it back before the old rootfs returns, or the next install's cleanup of staging deletes it.
                File keptTo = new File(staging, USER_DATA);
                if (keptTo.isDirectory() && old.isDirectory()) keptTo.renameTo(new File(old, USER_DATA));
                if (old.isDirectory()) old.renameTo(root);
                return false;
            }
            FileUtils.delete(old);
            return LinuxRuntime.isInstalled(context);
        } catch (Exception e) {
            Log.e(TAG, "install", e);
            return false;
        } finally {
            installing = false;
            archive.delete();
        }
    }

    /**
     * True while {@link #install} runs in this process.
     * Its swap looks exactly like an interrupted one from outside, so recovery stays out of its way.
     * A process killed mid-swap starts with this false again, which is the case recovery is for.
     */
    private static volatile boolean installing;

    /**
     * Puts back what an update killed mid-swap left behind: the previous rootfs parked in {@code linuxfs.old} and possibly the user's home already moved into {@code linuxfs.new}.
     * Without this the runtime reads as not installed, and reinstalling it would clear staging, and the Steam library in it, before anything else.
     * It costs a few stats when there is nothing to recover, so it runs before every install check and session start.
     */
    public static void recoverInterruptedSwap(Context context) {
        if (installing) return;
        File root = LinuxRuntime.rootDir(context);
        recoverInterruptedSwap(root, new File(root.getParentFile(), LinuxRuntime.DIR + ".new"),
                new File(root.getParentFile(), LinuxRuntime.DIR + ".old"));
    }

    private static void recoverInterruptedSwap(File root, File staging, File old) {
        File target = root.isDirectory() ? root : old;
        File stagedHome = new File(staging, USER_DATA);
        File home = new File(target, USER_DATA);
        if (target.isDirectory() && stagedHome.isDirectory() && (!home.exists() || isEmptyDir(home))) {
            FileUtils.delete(home);
            if (stagedHome.renameTo(home)) Log.w(TAG, "recovered " + USER_DATA + " from an interrupted update");
        }
        if (!root.isDirectory() && old.isDirectory() && old.renameTo(root)) {
            Log.w(TAG, "restored the previous runtime after an interrupted update");
        }
    }

    private static boolean isEmptyDir(File dir) {
        String[] names = dir.list();
        return dir.isDirectory() && names != null && names.length == 0;
    }

    public static void uninstall(Context context) {
        FileUtils.delete(LinuxRuntime.rootDir(context));
    }

    /**
     * A whole-rootfs tar, which is not the shape the shared extractor handles: a distribution
     * rootfs is full of hard links (one binary under several names), and an entry written as an
     * empty file instead of its link target is a rootfs that boots to nothing. Symlinks, hard
     * links and the executable bit are all carried over here.
     *
     * <p>Shared with {@link LinuxSteamSeed}, which lays a package over the live rootfs with it.
     */
    static boolean extract(File archive, File destination, ProgressListener listener) {
        long entries = 0;
        try (InputStream in = new ZstdCompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive), 1 << 16));
             TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
            TarArchiveEntry entry;
            String base = destination.getCanonicalPath() + File.separator;
            while ((entry = tar.getNextTarEntry()) != null) {
                File file = new File(destination, entry.getName());
                // Refuse anything that would land outside the runtime directory.
                if (!(file.getCanonicalPath() + (entry.isDirectory() ? File.separator : "")).startsWith(base)
                        && !file.getCanonicalPath().equals(destination.getCanonicalPath())) {
                    Log.w(TAG, "skipping entry outside the rootfs: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();

                if (entry.isSymbolicLink()) {
                    file.delete();
                    FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                } else if (entry.isLink()) {
                    // A hard link to an earlier entry. Link where the filesystem allows it and
                    // fall back to a copy, which costs space but always works.
                    File target = new File(destination, entry.getLinkName());
                    file.delete();
                    try {
                        Files.createLink(file.toPath(), target.toPath());
                    } catch (IOException | UnsupportedOperationException e) {
                        if (target.isFile()) {
                            Files.copy(target.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } else if (entry.isFile()) {
                    try (OutputStream out = new FileOutputStream(file)) {
                        byte[] buffer = new byte[1 << 16];
                        int read;
                        while ((read = tar.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                    if ((entry.getMode() & 0111) != 0) file.setExecutable(true, false);
                } else {
                    continue; // device nodes and fifos: the runtime binds the real ones
                }
                if (++entries % 2000 == 0 && listener != null) {
                    listener.onProgress("Extracting", -1);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "extract", e);
            return false;
        }
    }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
