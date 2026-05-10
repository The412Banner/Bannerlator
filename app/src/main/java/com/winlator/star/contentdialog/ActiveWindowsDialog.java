package com.winlator.star.contentdialog;

import android.graphics.Bitmap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.winlator.star.R;
import com.winlator.star.XServerDisplayActivity;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.UnitUtils;
import com.winlator.star.renderer.GLRenderer;
import com.winlator.star.winhandler.WinHandler;
import com.winlator.star.xserver.Window;
import com.winlator.star.xserver.XLock;
import com.winlator.star.xserver.XServer;

import java.util.ArrayList;

public class ActiveWindowsDialog extends ContentDialog {
    private final XServerDisplayActivity activity;

    public ActiveWindowsDialog(XServerDisplayActivity activity) {
        super(activity, R.layout.active_windows_dialog);
        this.activity = activity;
        setCancelable(true);

        try {
            setTitle(activity.getString(R.string.active_windows));
        } catch (Exception e) {
            setTitle("Active Windows");
        }

        try {
            // Standard Winlator icon ID
            setIcon(0x7f080119); 
        } catch (Exception e) {
            // Fallback
        }

        refreshWindowList();
    }

    private void refreshWindowList() {
        XServer xServer = activity.getXServer();
        ArrayList<Window> activeWindows = new ArrayList<>();
        ArrayList<Bitmap> activeIcons = new ArrayList<>();

        try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
            findAppWindows(xServer.windowManager.rootWindow, activeWindows);
            for (Window w : activeWindows) {
                activeIcons.add(xServer.pixmapManager.getWindowIcon(w));
            }
        }

        loadWindowViews(activeWindows, activeIcons);
    }

    private void findAppWindows(Window parent, ArrayList<Window> result) {
        if (parent == null) return;
        for (Window child : parent.getChildren()) {
            if (child.attributes.isMapped()) {
                String className = child.getClassName();
                boolean isSystem = false;
                
                if (className != null) {
                    String cls = className.toLowerCase();
                    if (cls.contains("progman") || cls.contains("shell_traywnd") || cls.equals("explorer.exe")) {
                        isSystem = true;
                    }
                }

                String title = child.getName();
                boolean hasTitle = title != null && !title.isEmpty();
                boolean hasClass = className != null && !className.isEmpty();

                if (!isSystem && (hasTitle || hasClass)) {
                    if (!isDesktopWindowFallback(child)) {
                        result.add(child);
                        continue; 
                    }
                }
            }
            findAppWindows(child, result);
        }
    }

    private boolean isDesktopWindowFallback(Window window) {
        XServer xServer = activity.getXServer();
        if (window.getWidth() >= xServer.screenInfo.width && window.getHeight() >= xServer.screenInfo.height) {
            if (window.getParent() == xServer.windowManager.rootWindow) {
                String title = window.getName();
                return title == null || title.isEmpty() || title.equalsIgnoreCase("Default - Wine desktop");
            }
        }
        return false;
    }

    private void loadWindowViews(ArrayList<Window> windows, ArrayList<Bitmap> icons) {
        LinearLayout llWindowList = findViewById(R.id.llWindowList);
        TextView tvEmptyMessage = findViewById(R.id.tvEmptyMessage);

        if (llWindowList == null) return;
        llWindowList.removeAllViews();

        if (windows.isEmpty()) {
            if (tvEmptyMessage != null) tvEmptyMessage.setVisibility(View.VISIBLE);
            return;
        }

        if (tvEmptyMessage != null) tvEmptyMessage.setVisibility(View.GONE);

        GLRenderer renderer = activity.getXServer().getRenderer();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        
        int previewWidth = (int) UnitUtils.dpToPx(240.0f);
        int previewHeight = (int) UnitUtils.dpToPx(160.0f);

        LinearLayout currentRow = null;
        for (int i = 0; i < windows.size(); i++) {
            if (i % 2 == 0) {
                currentRow = new LinearLayout(getContext());
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                llWindowList.addView(currentRow);
            }

            final Window window = windows.get(i);
            final Bitmap icon = icons.get(i);
            
            View itemView = inflater.inflate(R.layout.active_window_item, currentRow, false);
            itemView.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

            ImageView ivIcon = itemView.findViewById(R.id.ivIcon);
            final ImageView ivWindow = itemView.findViewById(R.id.ivWindow);
            TextView tvName = itemView.findViewById(R.id.tvName);
            TextView tvProcess = itemView.findViewById(R.id.tvProcess);

            String className = window.getClassName();
            String title = window.getName();
            
            if (title == null || title.isEmpty()) title = className;
            if (title == null || title.isEmpty()) title = "Unnamed Window";

            tvName.setText(title);
            tvProcess.setText(className != null && !className.isEmpty() ? className : "Application");

            if (icon != null) ivIcon.setImageBitmap(icon);

            // MATCHING GLRenderer: Capture with a small delay for each item to prevent GL state flooding
            final int index = i;
            ivWindow.postDelayed(() -> {
                renderer.captureScreenshot(window, previewWidth, previewHeight, (bitmap) -> {
                    if (bitmap != null) {
                        activity.runOnUiThread(() -> {
                            ivWindow.setImageBitmap(bitmap);
                            ivWindow.setBackground(null); 
                        });
                    }
                });
            }, i * 100L); // staggered by 100ms per window

            itemView.setOnClickListener(v -> {
                WinHandler winHandler = activity.getWinHandler();
                winHandler.bringToFront(window.getClassName(), window.getHandle());
                dismiss();
            });

            currentRow.addView(itemView);

            if (i == windows.size() - 1 && i % 2 == 0) {
                View dummy = new View(getContext());
                dummy.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1.0f));
                currentRow.addView(dummy);
            }
        }
    }
}
