package com.winlator.star.inputcontrols;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.winlator.star.core.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CustomIconManager {
    private static final String CUSTOM_ICONS_DIR = "custom_icons";
    public static final short CUSTOM_ICON_ID_OFFSET = 100;
    private final File customIconsDir;
    private final Context context;

    public CustomIconManager(Context context) {
        this.context = context;
        this.customIconsDir = new File(context.getFilesDir(), CUSTOM_ICONS_DIR);
        if (!customIconsDir.exists()) customIconsDir.mkdirs();
    }

    public void addCustomIcon(Uri uri) {
        short nextId = getNextAvailableId();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(new File(customIconsDir, nextId + ".png"))) {
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private short getNextAvailableId() {
        List<Short> ids = getCustomIconIds();
        if (ids.isEmpty()) return CUSTOM_ICON_ID_OFFSET;
        return (short) (Collections.max(ids) + 1);
    }

    public List<Short> getCustomIconIds() {
        List<Short> ids = new ArrayList<>();
        File[] files = customIconsDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files != null) {
            for (File file : files) {
                try {
                    ids.add(Short.parseShort(FileUtils.getBasename(file.getName())));
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
}
