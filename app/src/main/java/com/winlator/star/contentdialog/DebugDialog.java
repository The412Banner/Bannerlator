package com.winlator.star.contentdialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.winlator.star.R;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.Callback;
import com.winlator.star.core.UnitUtils;
import com.winlator.star.widget.LogView;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class DebugDialog extends ContentDialog implements Callback<String> {
    private final LogView logView;
    private static boolean paused = false;
    private BufferedWriter writer;

    public DebugDialog(@NonNull Context context) {
        super(context, R.layout.debug_dialog);
        setIcon(R.drawable.icon_debug);
        setTitle(context.getString(R.string.logs));
        logView = findViewById(R.id.LogView);
        
        logView.getLayoutParams().width = (int)UnitUtils.dpToPx(UnitUtils.pxToDp(AppUtils.getScreenWidth()) * 0.7f);

        findViewById(R.id.BTCancel).setVisibility(View.GONE);

        LinearLayout llBottomBarPanel = findViewById(R.id.LLBottomBarPanel);
        llBottomBarPanel.setVisibility(View.VISIBLE);

        View toolbarView = LayoutInflater.from(context).inflate(R.layout.debug_toolbar, llBottomBarPanel, false);
        toolbarView.findViewById(R.id.BTClear).setOnClickListener((v) -> logView.clear());
        toolbarView.findViewById(R.id.BTPause).setOnClickListener((v) -> {
            setPaused(!paused);
            ((ImageButton)v).setImageResource(getPaused() ? R.drawable.icon_play : R.drawable.icon_pause);
        });
        llBottomBarPanel.addView(toolbarView);
        try {
            writer = new BufferedWriter(new FileWriter(logView.getLogFile(context)));
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void call(final String line) {
        if (!getPaused()) logView.append(line+"\n");
        try {
            writer.write(line + "\n");
            writer.flush();
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    public static void setPaused(boolean cond) {
        paused = cond;
    }
    
    public static boolean getPaused() {
        return paused;
    }
}
