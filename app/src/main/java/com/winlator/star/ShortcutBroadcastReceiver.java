package com.winlator.star;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

public class ShortcutBroadcastReceiver extends BroadcastReceiver {

    private static final String LOG_TAG = "ShortcutBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action != null && action.equals("com.winlator.SHORTCUT_ADDED")) {
            boolean isShortcutAdded = intent.getBooleanExtra("shortcut_added", false);
            if (isShortcutAdded) {
                Log.d(LOG_TAG, "Shortcut added successfully!");
                showToast(context, R.string.shortcut_device_unsupported); // yeah. I'm at a loss here.
            } else {
                Log.d(LOG_TAG, "Shortcut addition failed.");
                showToast(context, R.string.shortcut_add_failed);

                // Attempt to add the shortcut here if it failed
                addShortcutToHomeScreen(context, intent);
            }
        } else {
            Log.d(LOG_TAG, "Unexpected broadcast action received.");
        }
    }

    private void addShortcutToHomeScreen(Context context, Intent originalIntent) {
        String shortcutName = originalIntent.getStringExtra("shortcut_name");
        Bitmap shortcutIcon = originalIntent.getParcelableExtra("shortcut_icon");
        Intent shortcutIntent = originalIntent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);

        if (shortcutName != null && shortcutIcon != null && shortcutIntent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);

                if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
                    ShortcutInfo pinShortcutInfo = new ShortcutInfo.Builder(context, shortcutName)
                            .setShortLabel(shortcutName)
                            .setIcon(Icon.createWithBitmap(shortcutIcon))
                            .setIntent(shortcutIntent)
                            .build();

                    Log.d(LOG_TAG, "Requesting pin shortcut from the BroadcastReceiver...");
                    boolean result = shortcutManager.requestPinShortcut(pinShortcutInfo, null);
                    Log.d(LOG_TAG, "Pin shortcut requested with result: " + result);

                    if (result) {
                        showToast(context, R.string.shortcut_added);
                    } else {
                        Log.e(LOG_TAG, "Failed to add shortcut from BroadcastReceiver.");
                    }
                }
            } else {
                // Fallback for older versions (API < 26)
                Intent addIntent = new Intent();
                addIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
                addIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, shortcutName);
                addIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON, shortcutIcon);
                addIntent.setAction("com.android.launcher.action.INSTALL_SHORTCUT");

                try {
                    context.sendBroadcast(addIntent);
                    Log.d(LOG_TAG, "Sent broadcast to install shortcut from BroadcastReceiver.");
                    showToast(context, R.string.shortcut_added);
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error sending broadcast for installing shortcut: " + e.getMessage(), e);
                    showToast(context, R.string.shortcut_broadcast_failed);
                }
            }
        } else {
            Log.e(LOG_TAG, "Missing shortcut data, cannot add to home screen.");
        }
    }

    private static void showToast(Context context, int textResId) {
        Toast.makeText(context, ContextCompat.getString(context, textResId), Toast.LENGTH_SHORT).show();
    }
}



