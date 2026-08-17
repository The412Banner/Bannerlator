package com.winlator.star.core;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public abstract class TarCompressorUtils {
    public enum Type {XZ, ZSTD}

    // Interface to define the exclusion filter
    public interface ExclusionFilter {
        boolean shouldInclude(File file);
    }


    private static void addFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            tar.putArchiveEntry(tar.createArchiveEntry(file, entryName));
            try (BufferedInputStream inStream = new BufferedInputStream(new FileInputStream(file), StreamUtils.BUFFER_SIZE)) {
                StreamUtils.copy(inStream, tar);
            }
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addLinkFile(ArchiveOutputStream tar, File file, String entryName) {
        try {
            TarArchiveEntry entry = new TarArchiveEntry(entryName, TarConstants.LF_SYMLINK);
            entry.setLinkName(FileUtils.readSymlink(file));
            tar.putArchiveEntry(entry);
            tar.closeArchiveEntry();
        }
        catch (Exception e) {}
    }

    private static void addDirectory(ArchiveOutputStream tar, File folder, String basePath, ExclusionFilter filter) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (filter != null && !filter.shouldInclude(file)) {
                continue; // Skip files that should be excluded
            }
            if (FileUtils.isSymlink(file)) {
                addLinkFile(tar, file, basePath + file.getName());
            } else if (file.isDirectory()) {
                String entryName = basePath + file.getName() + "/";
                tar.putArchiveEntry(tar.createArchiveEntry(folder, entryName));
                tar.closeArchiveEntry();
                addDirectory(tar, file, entryName, filter);
            } else {
                addFile(tar, file, basePath + file.getName());
            }
        }
    }
    public static void compress(Type type, File file, File destination, int level) {
        compress(type, new File[]{file}, destination, level, null);
    }

    public static void compress(Type type, File file, File destination, int level, ExclusionFilter filter) {
        compress(type, new File[]{file}, destination, level, filter);
    }

    public static void compress(Type type, File[] files, File destination, int level, ExclusionFilter filter) {
        try (OutputStream outStream = getCompressorOutputStream(type, destination, level);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) {
                    continue; // Skip files that should be excluded
                }
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName());
                } else if (file.isDirectory()) {
                    String basePath = file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, basePath, filter);
                } else {
                    addFile(tar, file, file.getName());
                }
            }
            tar.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public static boolean extract(Type type, Context context, String assetFile, File destination) {
        return extract(type, context, assetFile, destination, null);
    }

    public static boolean extract(Type type, Context context, String assetFile, File destination, OnExtractFileListener onExtractFileListener) {
        try {
            return extract(type, context.getAssets().open(assetFile), destination, onExtractFileListener);
        }
        catch (IOException e) {
            return false;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination) {
        return extract(type, context, source, destination, null);
    }

    /** Reports how many compressed bytes have been read out of [total] (byte-accurate progress). */
    public interface OnReadProgressListener {
        void onReadProgress(long bytesRead, long total);
    }

    /**
     * Extract while reporting byte-accurate read progress. [total] is the size of the compressed
     * source (e.g. the downloaded .wcp file length); progress = bytesRead / total.
     */
    public static boolean extract(Type type, Context context, Uri source, File destination,
                                  long total, OnReadProgressListener progressListener) {
        if (source == null) return false;
        try {
            InputStream raw = source.toString().startsWith("/")
                ? new FileInputStream(source.toString())
                : context.getContentResolver().openInputStream(source);
            if (raw == null) return false;
            if (progressListener != null && total > 0) raw = new CountingInputStream(raw, total, progressListener);
            return extract(type, raw, destination, null);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    /** Wraps an InputStream and reports cumulative bytes read (throttled to whole-percent steps). */
    private static final class CountingInputStream extends java.io.FilterInputStream {
        private final long total;
        private final OnReadProgressListener listener;
        private long count = 0;
        private long lastPct = -1;
        CountingInputStream(InputStream in, long total, OnReadProgressListener listener) {
            super(in);
            this.total = total;
            this.listener = listener;
        }
        private void report() {
            long pct = count * 100 / total;
            if (pct != lastPct) {
                lastPct = pct;
                listener.onReadProgress(Math.min(count, total), total);
            }
        }
        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) { count++; report(); }
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) { count += n; report(); }
            return n;
        }
    }

    public static boolean extract(Type type, Context context, Uri source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try {
            if (source.toString().startsWith("/")) {
                return extract(type, new FileInputStream(source.toString()), destination, onExtractFileListener);
            } else {
                return extract(type, context.getContentResolver().openInputStream(source), destination, onExtractFileListener);
            }
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    public static boolean extract(Type type, File source, File destination) {
        return extract(type, source, destination, null);
    }

    public static boolean extract(Type type, File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try {
            return extract(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), destination, onExtractFileListener);
        }
        catch (FileNotFoundException e) {
            return false;
        }
    }

    private static boolean extract(Type type, InputStream source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null) return false;
        try (InputStream inStream = getCompressorInputStream(type, source);
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry)tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                File file = new File(destination, entry.getName());

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                }
                else {
                    if (entry.isSymbolicLink()) {
                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    }
                    else {
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        }
        catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Read a single small UTF-8 text entry (exact [entryName]) out of a compressed tar without
     * extracting anything. Returns null when the archive is missing/unreadable or the entry is
     * absent. Never throws — used by the wrapper manager to surface an optional version.txt.
     */
    public static String readTextFile(Type type, File source, String entryName) {
        if (source == null || !source.isFile() || entryName == null) return null;
        try {
            return readTextFile(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE), entryName);
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Asset overload: read a small UTF-8 text entry from a bundled .tzst without extracting it. */
    public static String readTextFile(Type type, Context context, String assetFile, String entryName) {
        if (context == null || assetFile == null || entryName == null) return null;
        try {
            return readTextFile(type, context.getAssets().open(assetFile), entryName);
        }
        catch (Exception e) {
            return null;
        }
    }

    /** Stream core: reads [entryName] from a raw (uncompressed) input stream and closes it. */
    private static String readTextFile(Type type, InputStream source, String entryName) {
        if (source == null || entryName == null) return null;
        try (InputStream inStream = getCompressorInputStream(type, source);
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                if (entry.isDirectory() || !tar.canReadEntryData(entry)) continue;
                if (entryName.equals(entry.getName())) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    StreamUtils.copy(tar, bos);
                    return bos.toString("UTF-8");
                }
            }
        }
        catch (Exception e) {
            return null;
        }
        return null;
    }

    /**
     * True iff [source] opens as a valid compressed tar (its first entry can be read without
     * error). Used to reject a corrupt/non-tzst file before accepting it as an override. Never throws.
     */
    public static boolean isValidArchive(Type type, File source) {
        if (source == null || !source.isFile()) return false;
        try (InputStream inStream = getCompressorInputStream(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE));
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            return tar.getNextEntry() != null;
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * True iff the compressed tar contains an entry whose name equals [entryName]. Used to validate
     * a user-supplied wrapper archive before accepting it. Never throws.
     */
    // Lenient membership check: real-world wrapper tarballs vary in layout — a leading "./",
    // a top-level directory, etc. — so we match on the normalized path OR the bare file name,
    // and skip macOS AppleDouble "._" sidecar entries. Exact-matching the full path here was
    // too brittle and rejected valid third-party wrappers (issue #132 device test).
    public static boolean containsEntry(Type type, File source, String entryName) {
        if (source == null || !source.isFile() || entryName == null) return false;
        String wantPath = entryName.replaceFirst("^\\./", "").replaceFirst("^/", "");
        String wantBase = wantPath.substring(wantPath.lastIndexOf('/') + 1);
        try (InputStream inStream = getCompressorInputStream(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE));
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                String name = entry.getName().replaceFirst("^\\./", "").replaceFirst("^/", "");
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith("._")) continue; // AppleDouble sidecar
                if (name.equals(wantPath) || name.endsWith("/" + wantPath) || base.equals(wantBase))
                    return true;
            }
        }
        catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * Stream a SINGLE tar entry (lenient name match, same rules as {@link #containsEntry}) and collect
     * every identifier token in its bytes that fully matches [tokenPattern]. This is the "strings on a
     * binary" trick used by the Smart Wrapper Manager (#132) to auto-detect the env-var NAMES a wrapper
     * .so references, with zero cooperation from the wrapper author.
     *
     * A token is a maximal run of {@code [A-Za-z0-9_]} of length &gt;= 4; [tokenPattern] is applied to
     * each WHOLE token (anchor it with {@code ^...$}). Uncompressed bytes read from the entry are capped
     * at [maxBytes] so a multi-MB .so can't hang the import worker. Never throws — returns whatever was
     * collected before an error/cap (possibly empty).
     */
    public static java.util.Set<String> scanEntryForTokens(Type type, File source, String entryName,
                                                            java.util.regex.Pattern tokenPattern, long maxBytes) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (source == null || !source.isFile() || entryName == null || tokenPattern == null) return out;
        String wantPath = entryName.replaceFirst("^\\./", "").replaceFirst("^/", "");
        String wantBase = wantPath.substring(wantPath.lastIndexOf('/') + 1);
        try (InputStream inStream = getCompressorInputStream(type, new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE));
             ArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                if (entry.isDirectory() || !tar.canReadEntryData(entry)) continue;
                String name = entry.getName().replaceFirst("^\\./", "").replaceFirst("^/", "");
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (base.startsWith("._")) continue; // AppleDouble sidecar
                if (!(name.equals(wantPath) || name.endsWith("/" + wantPath) || base.equals(wantBase))) continue;

                // Matching entry found — walk its bytes, building identifier tokens across buffer reads.
                byte[] buf = new byte[StreamUtils.BUFFER_SIZE];
                StringBuilder token = new StringBuilder();
                long scanned = 0;
                int n;
                while (scanned < maxBytes && (n = tar.read(buf)) > 0) {
                    scanned += n;
                    for (int i = 0; i < n; i++) {
                        int c = buf[i] & 0xFF;
                        boolean idChar = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                                      || (c >= '0' && c <= '9') || c == '_';
                        if (idChar) {
                            if (token.length() < 128) token.append((char) c); // env names are short; cap growth
                        } else {
                            // Only emit on a NUL terminator: a real getenv() argument is a standalone
                            // NUL-terminated C-string literal, whereas a token embedded in a larger string
                            // (e.g. "WRAPPER_TEX" inside the log format "[WRAPPER_TEX %d] bc=%d ...") is
                            // terminated by a space/%/] — a false positive we must NOT surface as a setting.
                            if (c == 0 && token.length() >= 4 && tokenPattern.matcher(token).matches())
                                out.add(token.toString());
                            token.setLength(0);
                        }
                    }
                }
                // A token still open at EOF / byte-cap was never NUL-terminated in the scanned region — do
                // not emit it (same rule as above), just drop it.
                break; // only the first matching entry
            }
        }
        catch (Exception e) {
            // Best-effort: degrade to whatever was collected. Auto-detect must never crash an import.
        }
        return out;
    }

    private static InputStream getCompressorInputStream(Type type, InputStream source) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorInputStream(source);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorInputStream(source);
        }
        return null;
    }

    private static OutputStream getCompressorOutputStream(Type type, File destination, int level) throws IOException {
        if (type == Type.XZ) {
            return new XZCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        else if (type == Type.ZSTD) {
            return new ZstdCompressorOutputStream(new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE), level);
        }
        return null;
    }

    public static void archive(File[] files, File destination, ExclusionFilter filter) {
        try (OutputStream outStream = new BufferedOutputStream(new FileOutputStream(destination), StreamUtils.BUFFER_SIZE);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(outStream)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU);
            for (File file : files) {
                if (filter != null && !filter.shouldInclude(file)) {
                    continue; // Skip files that should be excluded
                }
                if (FileUtils.isSymlink(file)) {
                    addLinkFile(tar, file, file.getName());
                } else if (file.isDirectory()) {
                    String basePath = file.getName() + "/";
                    tar.putArchiveEntry(tar.createArchiveEntry(file, basePath));
                    tar.closeArchiveEntry();
                    addDirectory(tar, file, basePath, filter);
                } else {
                    addFile(tar, file, file.getName());
                }
            }
            tar.finish();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static boolean extractTar(File source, File destination, OnExtractFileListener onExtractFileListener) {
        if (source == null || !source.isFile()) return false;
        try (InputStream inStream = new BufferedInputStream(new FileInputStream(source), StreamUtils.BUFFER_SIZE);
             TarArchiveInputStream tar = new TarArchiveInputStream(inStream)) {
            TarArchiveEntry entry;
            String topLevelDirectory = null;
            while ((entry = (TarArchiveEntry) tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;

                // Get the top-level directory name
                String entryName = entry.getName();
                if (topLevelDirectory == null) {
                    if (entry.isDirectory()) {
                        topLevelDirectory = entryName;
                        continue; // Skip creating the top-level directory
                    }
                }

                // Skip the entire tmp directory
                if (entryName.contains("/tmp/")) {
                    Log.d("RestoreOp", "Skipping tmp directory: " + entryName);
                    continue;
                }

                // Adjust the extraction path to remove the top-level directory
                String adjustedName = entryName.replaceFirst("^" + topLevelDirectory, "");
                File file = new File(destination, adjustedName);

                if (onExtractFileListener != null) {
                    file = onExtractFileListener.onExtractFile(file, entry.getSize());
                    if (file == null) continue;
                }

                if (entry.isDirectory()) {
                    if (!file.isDirectory()) file.mkdirs();
                } else {
                    if (entry.isSymbolicLink()) {
                        FileUtils.symlink(entry.getLinkName(), file.getAbsolutePath());
                    } else {
                        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(file), StreamUtils.BUFFER_SIZE)) {
                            if (!StreamUtils.copy(tar, outStream)) return false;
                        }
                    }
                }

                FileUtils.chmod(file, 0771);
            }
            return true;
        } catch (IOException e) {
            Log.e("RestoreOp", "Failed to extract tar file", e);
            return false;
        }
    }


}







