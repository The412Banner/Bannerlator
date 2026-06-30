package com.winlator.star.contentdialog;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import android.content.res.ColorStateList;

import androidx.annotation.NonNull;
import androidx.core.widget.ImageViewCompat;
import androidx.preference.PreferenceManager;

import com.winlator.star.R;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.Callback;
import com.winlator.star.ui.theme.AppThemeState;

import java.util.ArrayList;

public class ContentDialog extends Dialog {
    public Runnable onConfirmCallback;
    private Runnable onCancelCallback;
    private final View contentView;

    private boolean isDarkMode;

    public ContentDialog(@NonNull Context context) {
        this(context, 0);
    }

    private View inflatedLayout;

    private static int pickTheme(Context context) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getBoolean("dark_mode", false)
                ? R.style.ContentDialog_Dark
                : R.style.ContentDialog_Dark; // app is always dark; keep dark dialog always
    }

    public ContentDialog(@NonNull Context context, int layoutResId) {
        super(context, pickTheme(context));
        contentView = LayoutInflater.from(context).inflate(R.layout.content_dialog, null);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        isDarkMode = sharedPreferences.getBoolean("dark_mode", false);


        if (layoutResId > 0) {
            FrameLayout frameLayout = contentView.findViewById(R.id.FrameLayout);
            frameLayout.setVisibility(View.VISIBLE);
            View view = LayoutInflater.from(context).inflate(layoutResId, frameLayout, false);
            frameLayout.addView(view);
        }

        View confirmButton = contentView.findViewById(R.id.BTConfirm);
        confirmButton.setOnClickListener((v) -> {
            if (onConfirmCallback != null) onConfirmCallback.run();
            dismiss();
        });

        View cancelButton = contentView.findViewById(R.id.BTCancel);
        cancelButton.setOnClickListener((v) -> {
            if (onCancelCallback != null) onCancelCallback.run();
            dismiss();
        });

        setContentView(contentView);
    }

    public View getInflatedLayout() {
        return inflatedLayout;
    }

    public View getContentView() {
        return contentView;
    }

    public void setOnConfirmCallback(Runnable onConfirmCallback) {
        this.onConfirmCallback = onConfirmCallback;
    }

    public void setOnCancelCallback(Runnable onCancelCallback) {
        this.onCancelCallback = onCancelCallback;
    }

    @Override
    public void setTitle(int titleResId) {
        setTitle(getContext().getString(titleResId));
    }

    public void setIcon(int iconResId) {
        ImageView imageView = findViewById(R.id.IVIcon);
        imageView.setImageResource(iconResId);
        // XML bakes app:tint="@color/colorPrimary" at inflation; route to runtime accent.
        ImageViewCompat.setImageTintList(imageView, ColorStateList.valueOf(AppThemeState.getCurrentAccentArgb()));
        imageView.setVisibility(View.VISIBLE);
    }

    public void setTitle(String title) {
        LinearLayout titleBar = findViewById(R.id.LLTitleBar);
        TextView tvTitle = findViewById(R.id.TVTitle);
        // XML bakes textColor="@color/colorPrimary" at inflation; route to runtime accent.
        tvTitle.setTextColor(AppThemeState.getCurrentAccentArgb());

        if (title != null && !title.isEmpty()) {
            tvTitle.setText(title);
            titleBar.setVisibility(View.VISIBLE);
        }
        else {
            tvTitle.setText("");
            titleBar.setVisibility(View.GONE);
        }
    }

    public void setBottomBarText(String bottomBarText) {
        TextView tvBottomBarText = findViewById(R.id.TVBottomBarText);
        // XML bakes textColor="@color/colorPrimary" at inflation; route to runtime accent.
        tvBottomBarText.setTextColor(AppThemeState.getCurrentAccentArgb());

        if (bottomBarText != null && !bottomBarText.isEmpty()) {
            tvBottomBarText.setText(bottomBarText);
            tvBottomBarText.setVisibility(View.VISIBLE);
        }
        else {
            tvBottomBarText.setText("");
            tvBottomBarText.setVisibility(View.GONE);
        }
    }

    public void setMessage(int msgResId) {
        setMessage(getContext().getString(msgResId));
    }

    public void setMessage(String message) {
        TextView tvMessage = findViewById(R.id.TVMessage);

        if (message != null && !message.isEmpty()) {
            tvMessage.setText(message);
            tvMessage.setVisibility(View.VISIBLE);
        }
        else {
            tvMessage.setText("");
            tvMessage.setVisibility(View.GONE);
        }
    }

    public static void alert(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static void alert(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }

    public static void confirm(Context context, int msgResId, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msgResId);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void confirm(Context context, String msg, Runnable callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setMessage(msg);
        dialog.setOnConfirmCallback(callback);
        dialog.show();
    }

    public static void prompt(Context context, int titleResId, String defaultText, Callback<String> callback) {
        ContentDialog dialog = new ContentDialog(context);

        final EditText editText = dialog.findViewById(R.id.EditText);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        boolean isDarkMode = sharedPreferences.getBoolean("dark_mode", false);
        applyDarkThemeToEditText(editText, isDarkMode);

        editText.setHint(R.string.untitled);
        if (defaultText != null) editText.setText(defaultText);
        editText.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) callback.call(text);
        });

        dialog.show();
    }

    private static void applyDarkThemeToEditText(EditText editText, boolean isDarkMode) {
        if (isDarkMode) {
            editText.setTextColor(Color.WHITE); // Set text color to white for dark theme
            editText.setHintTextColor(Color.GRAY); // Set hint color to gray
            editText.setBackgroundResource(R.drawable.edit_text_dark); // Custom dark background drawable
        } else {
            editText.setTextColor(Color.BLACK); // Default text color
            editText.setHintTextColor(Color.GRAY); // Default hint color
            editText.setBackgroundResource(R.drawable.edit_text); // Custom light background drawable
        }
    }

    public static void showMultipleChoiceList(Context context, int titleResId, final String[] items, Callback<ArrayList<Integer>> callback) {
        ContentDialog dialog = new ContentDialog(context);

        final ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_multiple_choice, items));
        listView.setVisibility(View.VISIBLE);

        dialog.setTitle(titleResId);
        dialog.setOnConfirmCallback(() -> {
            ArrayList<Integer> result = new ArrayList<>();
            SparseBooleanArray checkedItemPositions = listView.getCheckedItemPositions();
            for (int i = 0; i < checkedItemPositions.size(); i++) {
                if (checkedItemPositions.valueAt(i)) result.add(checkedItemPositions.keyAt(i));
            }
            callback.call(result);
        });

        dialog.show();
    }

    public static void showSingleChoiceList(Context context, int titleResId, final String[] items, Callback<Integer> callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(View.GONE);

        final ListView listView = dialog.findViewById(R.id.ListView);
        listView.getLayoutParams().width = AppUtils.getPreferredDialogWidth(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listView.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_single_choice, items));
        listView.setVisibility(View.VISIBLE);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            callback.call(position);
            dialog.dismiss();
        });

        dialog.setTitle(titleResId);
        dialog.show();
    }
}
