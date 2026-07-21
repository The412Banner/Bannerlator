package com.winlator.star.inputcontrols;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Base64;
import com.winlator.star.core.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CustomIconManager {
    private static final String CUSTOM_ICONS_DIR = "custom_icons";
    public static final short CUSTOM_ICON_ID_OFFSET = 100;
    public static final short MAX_CUSTOM_ICON_ID = 255;
    private static final Object ICON_STORAGE_LOCK = new Object();
    private static final int MAX_ICON_DIMENSION = 2048;
    private static final long MAX_ICON_PIXELS = 4_194_304L;

    public static class ImportedIcon {
        public final short id;
        public final boolean created;

        ImportedIcon(short id, boolean created) {
            this.id = id;
            this.created = created;
        }
    }
    private final File customIconsDir;
    private final Context context;

    public CustomIconManager(Context context) {
        this.context = context;
        this.customIconsDir = new File(context.getFilesDir(), CUSTOM_ICONS_DIR);
        if (!customIconsDir.exists()) customIconsDir.mkdirs();
    }

    public short addCustomIcon(Uri uri) {
        synchronized (ICON_STORAGE_LOCK) {
            return addCustomIconLocked(uri);
        }
    }

    private short addCustomIconLocked(Uri uri) {
        short nextId = getNextAvailableId();
        if (nextId < 0) return -1;
        File outputFile = new File(customIconsDir, nextId + ".png");
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return -1;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (!hasValidIconBounds(bounds.outWidth, bounds.outHeight)) return -1;

            Bitmap bitmap;
            try (InputStream is = context.getContentResolver().openInputStream(uri)) {
                if (is == null) return -1;
                bitmap = BitmapFactory.decodeStream(is);
            }
            if (bitmap == null) return -1;
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    outputFile.delete();
                    return -1;
                }
            }
            finally {
                bitmap.recycle();
            }
            return nextId;
        } catch (IOException | OutOfMemoryError e) {
            outputFile.delete();
            e.printStackTrace();
        }
        return -1;
    }

    private short getNextAvailableId() {
        List<Short> ids = getCustomIconIds();
        for (int id = CUSTOM_ICON_ID_OFFSET; id <= MAX_CUSTOM_ICON_ID; id++) {
            if (!ids.contains((short) id)) return (short) id;
        }
        return -1;
    }

    public List<Short> getCustomIconIds() {
        List<Short> ids = new ArrayList<>();
        File[] files = customIconsDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files != null) {
            for (File file : files) {
                try {
                    short id = Short.parseShort(FileUtils.getBasename(file.getName()));
                    if (id >= CUSTOM_ICON_ID_OFFSET && id <= MAX_CUSTOM_ICON_ID) ids.add(id);
                } catch (NumberFormatException e) {}
            }
        }
        Collections.sort(ids);
        return ids;
    }

    public Bitmap loadIcon(short id) {
        File file = new File(customIconsDir, id + ".png");
        if (file.exists()) {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
            }
        return null;
    }

    public String encodeIcon(int id) {
        File file = new File(customIconsDir, id + ".png");
        if (!file.isFile()) return null;
        if (file.length() <= 0 || file.length() > 4 * 1024 * 1024) return null;
        try (InputStream inputStream = new java.io.FileInputStream(file)) {
            byte[] data = new byte[(int)file.length()];
            int offset = 0;
            while (offset < data.length) {
                int count = inputStream.read(data, offset, data.length - offset);
                if (count < 0) break;
                offset += count;
            }
            if (offset != data.length) return null;
            return Base64.encodeToString(data, Base64.NO_WRAP);
        }
        catch (IOException e) {
            return null;
        }
    }

    public ImportedIcon importEncodedIcon(String encodedData) {
        if (encodedData == null || encodedData.length() > 8 * 1024 * 1024) return null;
        byte[] data;
        try {
            data = Base64.decode(encodedData, Base64.DEFAULT);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        if (data.length == 0 || data.length > 4 * 1024 * 1024) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (!hasValidIconBounds(bounds.outWidth, bounds.outHeight)) return null;
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        }
        catch (OutOfMemoryError e) {
            return null;
        }
        if (bitmap == null) return null;
        bitmap.recycle();

        synchronized (ICON_STORAGE_LOCK) {
            for (short existingId : getCustomIconIds()) {
                File existingFile = new File(customIconsDir, existingId + ".png");
                if (hasSameContents(existingFile, data)) {
                    return new ImportedIcon(existingId, false);
                }
            }
            short id = getNextAvailableId();
            if (id < 0) return null;
            File outputFile = new File(customIconsDir, id + ".png");
            try (FileOutputStream outputStream = new FileOutputStream(outputFile)) {
                outputStream.write(data);
                return new ImportedIcon(id, true);
            }
            catch (IOException e) {
                outputFile.delete();
                return null;
            }
        }
    }

    private static boolean hasSameContents(File file, byte[] data) {
        if (!file.isFile() || file.length() != data.length) return false;
        try (InputStream inputStream = new java.io.FileInputStream(file)) {
            byte[] existingData = new byte[data.length];
            int offset = 0;
            while (offset < existingData.length) {
                int count = inputStream.read(existingData, offset, existingData.length - offset);
                if (count < 0) return false;
                offset += count;
            }
            return Arrays.equals(existingData, data);
        }
        catch (IOException e) {
            return false;
        }
    }

    static boolean hasValidIconBounds(int width, int height) {
        return width > 0 && height > 0
                && width <= MAX_ICON_DIMENSION && height <= MAX_ICON_DIMENSION
                && (long)width * height <= MAX_ICON_PIXELS;
    }

    public void deleteIcon(int id) {
        if (id >= CUSTOM_ICON_ID_OFFSET && id <= MAX_CUSTOM_ICON_ID) {
            new File(customIconsDir, id + ".png").delete();
        }
    }
}
