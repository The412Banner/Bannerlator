package com.winlator.star.xenvironment;

import android.content.Context;
import android.util.Log;

import com.winlator.star.MainActivity;
import com.winlator.star.R;
import com.winlator.star.SettingsFragment;
import com.winlator.star.container.Container;
import com.winlator.star.container.ContainerManager;
import com.winlator.star.contents.AdrenotoolsManager;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.DownloadProgressDialog;
import com.winlator.star.core.FileUtils;
import com.winlator.star.core.TarCompressorUtils;
import com.winlator.star.core.WineInfo;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

public abstract class ImageFsInstaller {
    public static final byte LATEST_VERSION = 22;

    private static void resetContainerImgVersions(Context context) {
        ContainerManager manager = new ContainerManager(context);
        for (Container container : manager.getContainers()) {
            String imgVersion = container.getExtra("imgVersion");
            String wineVersion = container.getWineVersion();
            if (!imgVersion.isEmpty() && WineInfo.isMainWineVersion(wineVersion) && Short.parseShort(imgVersion) <= 5) {
                container.putExtra("wineprefixNeedsUpdate", "t");
            }

            container.putExtra("imgVersion", null);
            container.saveData();
        }
    }

    // Identity of a bundled asset as staged on disk: the app build it came from plus the asset's
    // size. Comparing SIZE ALONE (the old check) silently skips an update whenever a new build of
    // an asset happens to land on the same byte count; folding in versionCode means any release
    // restages regardless. Kept cheap on purpose — this runs on every launch, including the
    // game-launch path, so it must stay a couple of stats rather than a hash of several MB.
    private static String assetStamp(Context context, String assetPath) {
        return com.winlator.star.BuildConfig.VERSION_CODE + ":" + FileUtils.getSize(context, assetPath);
    }

    private static boolean stampMatches(File stampFile, String want) {
        try {
            return stampFile.isFile() && want.equals(FileUtils.readString(stampFile).trim());
        } catch (Exception e) {
            return false;
        }
    }

    // Stages the bundled win-fg Vulkan layer (.so + implicit-layer manifest) into imagefs so frame
    // generation works without manually copying the .so after every (re)install. Idempotent via the
    // version stamp above. The manifest's library_path is ../../../lib/libwin_fg.so, so it must sit
    // in usr/share/vulkan/implicit_layer.d/ with the .so in usr/lib/.
    //
    // win-fg is the clean-room replacement for the previous bionic-fg layer, which was removed
    // because it embedded proprietary Lossless-Scaling-derived weights. This method also deletes any
    // previously-staged bionic-fg layer so devices upgrading from an older build stop carrying it.
    public static void installWinFgLayer(Context context, ImageFs imageFs) {
        try {
            // Purge the superseded proprietary-derived bionic-fg layer from the prefix.
            new File(imageFs.getLibDir(), "libbionic_fg.so").delete();
            new File(imageFs.getLibDir(), ".bionic-fg-stamp").delete();
            new File(imageFs.getRootDir(), "usr/share/vulkan/implicit_layer.d/VkLayer_BIONIC_framegen.json").delete();

            File soDst = new File(imageFs.getLibDir(), "libwin_fg.so");
            long assetSize = FileUtils.getSize(context, "win-fg/libwin_fg.so");
            File stamp = new File(imageFs.getLibDir(), ".win-fg-stamp");
            String want = assetStamp(context, "win-fg/libwin_fg.so");
            // Both checks, because they catch different failures: the stamp catches a new build
            // that happens to be the same size as the installed one, the size catches the staged
            // file drifting on disk (replaced, truncated) while the stamp still reads current.
            if (stampMatches(stamp, want) && soDst.isFile() && soDst.length() == assetSize) return;
            if (!soDst.isFile() || soDst.length() != assetSize) {
                // Copy via a temp file and rename into place: staging runs off the main thread,
                // so a game launched during it would otherwise be able to dlopen a half-written
                // layer. rename() within the same directory is atomic.
                File soTmp = new File(soDst.getParentFile(), "libwin_fg.so.staging");
                FileUtils.copy(context, "win-fg/libwin_fg.so", soTmp);
                if (!soTmp.renameTo(soDst)) {
                    soTmp.delete();
                    Log.e("ImageFsInstaller", "Failed to swap in the staged win-fg layer");
                    return;
                }
                Log.i("ImageFsInstaller", "Staged bundled win-fg layer (" + assetSize + " bytes)");
            }
            File manifestDir = new File(imageFs.getRootDir(), "usr/share/vulkan/implicit_layer.d");
            manifestDir.mkdirs();
            File manifestDst = new File(manifestDir, "VkLayer_win_framegen.json");
            // Always refresh the manifest, like the lsfg-vk one below. It was previously written
            // only when absent, so any change to it (layer name, api version, env gating) could
            // never reach a device that already had the old copy.
            FileUtils.copy(context, "win-fg/VkLayer_win_framegen.json", manifestDst);
            FileUtils.writeString(stamp, want);
        } catch (Exception e) {
            Log.e("ImageFsInstaller", "Failed to stage win-fg layer", e);
        }
    }

    // Stages the components that ride ALONGSIDE the imagefs tarball rather than inside it: both
    // frame-generation layers and the ffmpeg-8 libs. These ship as APK assets, so an app update
    // can carry a newer build of any of them — and without this they would only ever reach a
    // device on a full imagefs re-extract. Each call is individually idempotent (size check or
    // version stamp), so the steady-state cost is a few stats. Call off the main thread.
    public static void stageBundledComponents(Context context, ImageFs imageFs) {
        disableLibjpegShadowOnXiaomi(imageFs);
        installWinFgLayer(context, imageFs);
        installLsfgVkLayer(context, imageFs);
        installFFmpeg8(context, imageFs);
    }

    // XIAOMI/HYPEROS ONLY. Our imagefs ships a build-time libjpeg.so -> libjpeg.so.8 symlink.
    // Nothing resolves the bare "libjpeg.so" soname at runtime — every consumer in the imagefs
    // (libgstopengl, libgdk_pixbuf, libtiff) links against libjpeg.so.8 — but on Xiaomi the
    // symlink is actively harmful: their patched libhwui.so drags /system_ext/lib64/libjpeg-hyper.so
    // into any dlopen closure that touches libandroid.so, which BOTH frame-gen layers do for
    // AHardwareBuffer. libjpeg-hyper's own "libjpeg.so" dependency then resolves via
    // LD_LIBRARY_PATH to our symlinked copy, which does not export the jsimd_* SIMD symbols it
    // needs; the failed relocation aborts the whole dlopen, so bionic-fg AND lsfg-vk silently
    // never load ("Requested layer ... failed to load"). Moving the symlink aside lets the linker
    // fall through to Xiaomi's own /system/lib64/libjpeg.so, which does export them.
    //
    // Diagnosis and fix by @clintOnSky (PR #96). Renamed rather than deleted so the change is
    // reversible and so a support report can be answered by looking for the parked file.
    // Gated on libjpeg-hyper being present, so every non-Xiaomi device is left untouched.
    public static void disableLibjpegShadowOnXiaomi(ImageFs imageFs) {
        try {
            if (!new File("/system_ext/lib64/libjpeg-hyper.so").isFile()) return;
            File libDir = imageFs.getLibDir();
            File shadow = new File(libDir, "libjpeg.so");
            if (!shadow.exists()) return;   // already parked, or this imagefs never shipped it
            File parked = new File(libDir, "libjpeg.so.disabled");
            // An imagefs re-extract recreates the symlink, so clear any stale parked copy first
            // rather than leaving the rename to fail.
            if (parked.exists() && !parked.delete()) {
                Log.e("ImageFsInstaller", "Could not clear stale libjpeg.so.disabled");
                return;
            }
            if (shadow.renameTo(parked)) {
                Log.i("ImageFsInstaller", "Parked usr/lib/libjpeg.so as .disabled (Xiaomi libjpeg-hyper workaround)");
            } else {
                Log.e("ImageFsInstaller", "Failed to park usr/lib/libjpeg.so; frame-gen layers may not load on this device");
            }
        } catch (Exception e) {
            Log.e("ImageFsInstaller", "Failed to apply the Xiaomi libjpeg workaround", e);
        }
    }

    // Stages the bundled lsfg-vk Vulkan layer (.so + implicit-layer manifest) into imagefs so the
    // second frame-generation engine is available without manually copying anything. Idempotent.
    // The manifest's library_path is ../../../lib/liblsfg-vk.so, so it sits in
    // usr/share/vulkan/implicit_layer.d/ with the .so in usr/lib/. The manifest is opt-in via
    // enable_environment ENABLE_LSFG=1 — the layer ONLY loads when a container explicitly selects
    // lsfg-vk at launch, so staging it here can never brick other containers (lsfg-vk hard-exits
    // if it can't read a Lossless.dll, hence the opt-in gate).
    public static void installLsfgVkLayer(Context context, ImageFs imageFs) {
        try {
            File soDst = new File(imageFs.getLibDir(), "liblsfg-vk.so");
            long assetSize = FileUtils.getSize(context, "lsfg-vk/liblsfg-vk.so");
            File stamp = new File(imageFs.getLibDir(), ".lsfg-vk-stamp");
            String want = assetStamp(context, "lsfg-vk/liblsfg-vk.so");
            if (stampMatches(stamp, want) && soDst.isFile() && soDst.length() == assetSize) return;
            if (!soDst.isFile() || soDst.length() != assetSize) {
                File soTmp = new File(soDst.getParentFile(), "liblsfg-vk.so.staging");
                FileUtils.copy(context, "lsfg-vk/liblsfg-vk.so", soTmp);
                if (!soTmp.renameTo(soDst)) {
                    soTmp.delete();
                    Log.e("ImageFsInstaller", "Failed to swap in the staged lsfg-vk layer");
                    return;
                }
                Log.i("ImageFsInstaller", "Staged bundled lsfg-vk layer (" + assetSize + " bytes)");
            }
            File manifestDir = new File(imageFs.getRootDir(), "usr/share/vulkan/implicit_layer.d");
            manifestDir.mkdirs();
            File manifestDst = new File(manifestDir, "VkLayer_LS_frame_generation.json");
            // Always refresh the manifest (it gained enable_environment in newer builds).
            FileUtils.copy(context, "lsfg-vk/VkLayer_LS_frame_generation.json", manifestDst);
            FileUtils.writeString(stamp, want);
        } catch (Exception e) {
            Log.e("ImageFsInstaller", "Failed to stage lsfg-vk layer", e);
        }
    }

    // Stages the full ffmpeg-8 shared libs (libav*/libsw* .so.62/.60/...) into imagefs usr/lib so
    // winedmo (the GE video-rework decode path) resolves its libavcodec.so.62 / libavformat.so.62
    // deps. The stock imagefs only ships ffmpeg 7.1 (.61/.59); winedmo links the 8.x sonames.
    // Idempotent via a versioned stamp file so existing installs pick it up on next launch without
    // an imagefs re-extract. Soname symlinks are made here (the extractor doesn't carry them, same
    // as the libSDL2 symlink above).
    public static void installFFmpeg8(Context context, ImageFs imageFs) {
        try {
            File libDir = imageFs.getLibDir();
            File stamp = new File(libDir, ".ffmpeg8-8.0-full-1");
            if (stamp.isFile()) return;
            boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, context, "ffmpeg8.tzst", libDir);
            if (!ok) { Log.e("ImageFsInstaller", "ffmpeg-8 extract failed"); return; }
            symlinkLib(libDir, "libavcodec.so.62.11.100", "libavcodec.so.62");
            symlinkLib(libDir, "libavformat.so.62.3.100", "libavformat.so.62");
            symlinkLib(libDir, "libavutil.so.60.8.100",  "libavutil.so.60");
            symlinkLib(libDir, "libswscale.so.9.1.100",  "libswscale.so.9");
            symlinkLib(libDir, "libswresample.so.6.1.100", "libswresample.so.6");
            symlinkLib(libDir, "libavfilter.so.11.4.100", "libavfilter.so.11");
            symlinkLib(libDir, "libavdevice.so.62.1.100", "libavdevice.so.62");
            stamp.createNewFile();
        } catch (Exception e) {
            Log.e("ImageFsInstaller", "Failed to stage ffmpeg-8", e);
        }
    }

    private static void symlinkLib(File libDir, String target, String link) {
        new File(libDir, link).delete();
        FileUtils.symlink(target, new File(libDir, link).getAbsolutePath());
    }

    public static void installWineFromAssets(final MainActivity activity) {
        String[] versions = activity.getResources().getStringArray(R.array.wine_entries);
        File rootDir = ImageFs.find(activity).getRootDir();
        for (String version : versions) {
            File outFile = new File(rootDir, "/opt/" + version);
            outFile.mkdirs();
            TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, version + ".tar.zst", outFile);
        }
    }

    public static void installDriversFromAssets(final MainActivity activity) {
        AdrenotoolsManager adrenotoolsManager = new AdrenotoolsManager(activity);
        String[] adrenotoolsAssetDrivers = activity.getResources().getStringArray(R.array.wrapper_graphics_driver_version_entries);

        for (String driver : adrenotoolsAssetDrivers)
            adrenotoolsManager.extractDriverFromResources(driver);
    }

    public static void installFromAssets(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        final DownloadProgressDialog dialog = new DownloadProgressDialog(activity);
        dialog.show(R.string.installing_system_files);
        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final byte compressionRatio = 22;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.tar.zst") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(TarCompressorUtils.Type.ZSTD, activity, "imagefs.tar.zst", rootDir, (file, size) -> {
                if (size > 0) {
                    long totalSize = totalSizeRef.addAndGet(size);
                    final int progress = (int)(((float)totalSize / contentLength) * 100);
                    activity.runOnUiThread(() -> dialog.setProgress(progress));
                }
                return file;
            });

            if (success) {
                installWineFromAssets(activity);
                installDriversFromAssets(activity);
                imageFs.createImgVersionFile(LATEST_VERSION);
                FileUtils.symlink("libSDL2-2.0.so", new File(imageFs.getLibDir(), "libSDL2-2.0.so.0").getAbsolutePath());
                resetContainerImgVersions(activity);
                installWinFgLayer(activity, imageFs);
                installLsfgVkLayer(activity, imageFs);
                installFFmpeg8(activity, imageFs);
                // Progress is derived from an estimated content length and the post-extraction
                // steps above report nothing, so the bar otherwise stalls around 98%. Snap to
                // 100% now that everything is truly done, before the dialog closes.
                activity.runOnUiThread(() -> dialog.setProgress(100));
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();
        });
    }

    public static void installIfNeeded(final MainActivity activity) {
        ImageFs imageFs = ImageFs.find(activity);
        if (!imageFs.isValid() || imageFs.getVersion() < LATEST_VERSION) installFromAssets(activity);
        // imagefs already current -> just make sure the bundled bionic-fg layer is present
        // (e.g. upgrading from a build that didn't bundle it, without an imagefs re-extract).
        else {
            installWinFgLayer(activity, imageFs);
            installLsfgVkLayer(activity, imageFs);
            installFFmpeg8(activity, imageFs);
        }
    }

    public static void installFromAssetsWithCallback(
            final MainActivity activity,
            final IntConsumer onProgress,
            final Runnable onComplete) {
        AppUtils.keepScreenOn(activity);
        ImageFs imageFs = ImageFs.find(activity);
        File rootDir = imageFs.getRootDir();

        SettingsFragment.resetEmulatorsVersion(activity);

        Executors.newSingleThreadExecutor().execute(() -> {
            clearRootDir(rootDir);
            final byte compressionRatio = 22;
            final long contentLength = (long)(FileUtils.getSize(activity, "imagefs.tar.zst") * (100.0f / compressionRatio));
            AtomicLong totalSizeRef = new AtomicLong();

            boolean success = TarCompressorUtils.extract(
                TarCompressorUtils.Type.ZSTD, activity, "imagefs.tar.zst", rootDir,
                (file, size) -> {
                    if (size > 0) {
                        long totalSize = totalSizeRef.addAndGet(size);
                        int progress = (int)(((float) totalSize / contentLength) * 100);
                        onProgress.accept(Math.min(progress, 100));
                    }
                    return file;
                }
            );

            if (success) {
                installWineFromAssets(activity);
                installDriversFromAssets(activity);
                imageFs.createImgVersionFile(LATEST_VERSION);
                FileUtils.symlink("libSDL2-2.0.so", new File(imageFs.getLibDir(), "libSDL2-2.0.so.0").getAbsolutePath());
                resetContainerImgVersions(activity);
                installWinFgLayer(activity, imageFs);
                installLsfgVkLayer(activity, imageFs);
                installFFmpeg8(activity, imageFs);
            } else {
                AppUtils.showToast(activity, R.string.unable_to_install_system_files);
            }

            activity.runOnUiThread(onComplete);
        });
    }

    private static void clearOptDir(File optDir) {
        File[] files = optDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals("installed-wine")) continue;
                FileUtils.delete(file);
            }
        }
    }

    private static void clearRootDir(File rootDir) {
        if (rootDir.isDirectory()) {
            File[] files = rootDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        String name = file.getName();
                        if (name.equals("home")) {
                            continue;
                        }
                    }
                    FileUtils.delete(file);
                }
            }
        }
        else rootDir.mkdirs();
    }
}