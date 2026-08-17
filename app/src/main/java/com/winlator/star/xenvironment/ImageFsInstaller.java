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

    // Stages the bundled bionic-fg Vulkan layer (.so + implicit-layer manifest) into imagefs so
    // frame generation / the FPS limiter work without manually copying the .so after every
    // (re)install. Idempotent: skips the .so copy when it is already present with the same size.
    // The manifest's library_path is ../../../lib/libbionic_fg.so, so it must sit in
    // usr/share/vulkan/implicit_layer.d/ with the .so in usr/lib/.
    public static void installBionicFgLayer(Context context, ImageFs imageFs) {
        try {
            File soDst = new File(imageFs.getLibDir(), "libbionic_fg.so");
            long assetSize = FileUtils.getSize(context, "bionic-fg/libbionic_fg.so");
            if (!soDst.isFile() || soDst.length() != assetSize) {
                FileUtils.copy(context, "bionic-fg/libbionic_fg.so", soDst);
            }
            File manifestDir = new File(imageFs.getRootDir(), "usr/share/vulkan/implicit_layer.d");
            manifestDir.mkdirs();
            File manifestDst = new File(manifestDir, "VkLayer_BIONIC_framegen.json");
            if (!manifestDst.isFile()) {
                FileUtils.copy(context, "bionic-fg/VkLayer_BIONIC_framegen.json", manifestDst);
            }
        } catch (Exception e) {
            Log.e("ImageFsInstaller", "Failed to stage bionic-fg layer", e);
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
            if (!soDst.isFile() || soDst.length() != assetSize) {
                FileUtils.copy(context, "lsfg-vk/liblsfg-vk.so", soDst);
            }
            File manifestDir = new File(imageFs.getRootDir(), "usr/share/vulkan/implicit_layer.d");
            manifestDir.mkdirs();
            File manifestDst = new File(manifestDir, "VkLayer_LS_frame_generation.json");
            // Always refresh the manifest (it gained enable_environment in newer builds).
            FileUtils.copy(context, "lsfg-vk/VkLayer_LS_frame_generation.json", manifestDst);
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
                installBionicFgLayer(activity, imageFs);
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
            installBionicFgLayer(activity, imageFs);
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
                installBionicFgLayer(activity, imageFs);
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