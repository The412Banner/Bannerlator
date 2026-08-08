package com.winlator.star.store;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.star.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-app folder picker for cloud save directory selection.
 *
 * Dropdown lets user switch between:
 *   - App Files      (getFilesDir() — Wine containers)
 *   - Internal Storage (Environment.getExternalStorageDirectory())
 *   - SD Card        (getExternalFilesDirs(null)[1] if present)
 *
 * "New Folder" button creates a subdirectory inside the current directory.
 * "Select this folder" returns the current path via setResult().
 *
 * Result extras:
 *   "path" (String) — absolute path of the selected folder
 */
public class FolderPickerActivity extends AppCompatActivity {

    private File currentDir;
    private File rootAppFiles;
    private File rootInternal;
    private File rootSdCard;   // null if no SD card

    private TextView pathTV;
    private LinearLayout listContainer;

    // Root labels and matching File objects (built in onCreate)
    private String[] rootLabels;
    private File[]   rootDirs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        rootAppFiles = getFilesDir();
        rootInternal = Environment.getExternalStorageDirectory();

        File[] extDirs = getExternalFilesDirs(null);
        rootSdCard = (extDirs != null && extDirs.length > 1 && extDirs[1] != null)
                ? extDirs[1] : null;

        // Build root label/dir arrays
        List<String> labels = new ArrayList<>();
        List<File>   dirs   = new ArrayList<>();
        labels.add(getString(R.string.folder_picker_app_files));  dirs.add(rootAppFiles);
        labels.add(getString(R.string.folder_picker_internal_storage)); dirs.add(rootInternal);
        if (rootSdCard != null) {
            labels.add(getString(R.string.folder_picker_sd_card)); dirs.add(rootSdCard);
        }
        rootLabels = labels.toArray(new String[0]);
        rootDirs   = dirs.toArray(new File[0]);

        currentDir = rootAppFiles;
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D0D0D);

        // ── Header ────────────────────────────────────────────────────────────
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundColor(0xFF1A1A2E);
        header.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView titleTV = new TextView(this);
        titleTV.setText(R.string.folder_picker_title);
        titleTV.setTextColor(0xFFFFFFFF);
        titleTV.setTextSize(16f);
        titleTV.setTypeface(null, Typeface.BOLD);
        header.addView(titleTV);

        // Root location spinner
        TextView locationLabel = new TextView(this);
        locationLabel.setText(R.string.folder_picker_location);
        locationLabel.setTextColor(0xFF8888AA);
        locationLabel.setTextSize(11f);
        LinearLayout.LayoutParams llLp = new LinearLayout.LayoutParams(-2, -2);
        llLp.topMargin = dp(8);
        llLp.bottomMargin = dp(2);
        header.addView(locationLabel, llLp);

        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, rootLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean firstCall = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int pos, long id) {
                if (firstCall) { firstCall = false; return; } // skip initial trigger
                currentDir = rootDirs[pos];
                refreshList();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        header.addView(spinner, new LinearLayout.LayoutParams(-1, -2));

        // Current path label
        pathTV = new TextView(this);
        pathTV.setTextColor(0xFF666688);
        pathTV.setTextSize(11f);
        pathTV.setPadding(0, dp(4), 0, 0);
        header.addView(pathTV);

        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // ── Action buttons row (Select + New Folder) ──────────────────────────
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(-1, -2);
        btnRowLp.setMargins(dp(12), dp(8), dp(12), dp(4));
        btnRow.setLayoutParams(btnRowLp);

        Button selectBtn = makeBtn(getString(R.string.folder_picker_select), 0xFF2E7D32);
        selectBtn.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra("path", currentDir.getAbsolutePath());
            setResult(RESULT_OK, result);
            finish();
        });
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        selLp.rightMargin = dp(6);
        btnRow.addView(selectBtn, selLp);

        Button newFolderBtn = makeBtn(getString(R.string.folder_picker_new), 0xFF444466);
        newFolderBtn.setOnClickListener(v -> showNewFolderDialog());
        btnRow.addView(newFolderBtn, new LinearLayout.LayoutParams(dp(90), dp(44)));

        root.addView(btnRow);

        // ── Directory list ────────────────────────────────────────────────────
        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        refreshList();
    }

    private void refreshList() {
        listContainer.removeAllViews();
        updatePathLabel();

        // ── "↑ Up" row ────────────────────────────────────────────────────────
        File parent = currentDir.getParentFile();
        if (parent != null && !isRoot(currentDir)) {
            listContainer.addView(makeDirRow(getString(R.string.folder_picker_up), parent, true));
        }

        // ── Subdirectories ────────────────────────────────────────────────────
        File[] files = currentDir.listFiles();
        if (files == null) {
            addEmptyLabel(getString(R.string.folder_picker_empty_unreadable));
            return;
        }

        List<File> dirs = new ArrayList<>();
        for (File f : files) {
            if (f.isDirectory()) dirs.add(f);
        }
        Collections.sort(dirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        if (dirs.isEmpty()) {
            addEmptyLabel(getString(R.string.folder_picker_no_subdirectories));
        } else {
            for (File dir : dirs) {
                listContainer.addView(makeDirRow("📁  " + dir.getName(), dir, false));
            }
        }
    }

    /** Returns true if currentDir is one of the root entries (no "Up" allowed). */
    private boolean isRoot(File dir) {
        for (File r : rootDirs) {
            if (r != null && r.equals(dir)) return true;
        }
        return false;
    }

    private void updatePathLabel() {
        String abs = currentDir.getAbsolutePath();
        String[] parts = abs.split("/");
        if (parts.length <= 3) {
            pathTV.setText(abs);
        } else {
            pathTV.setText("…/" + parts[parts.length - 2] + "/" + parts[parts.length - 1]);
        }
    }

    private void addEmptyLabel(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(0xFF555577);
        tv.setTextSize(13f);
        tv.setPadding(dp(4), dp(8), dp(4), dp(8));
        listContainer.addView(tv);
    }

    private void showNewFolderDialog() {
        EditText input = new EditText(this);
        input.setHint(R.string.folder_picker_folder_name_hint);
        input.setSingleLine(true);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, -2);
        inputLp.setMargins(dp(20), dp(8), dp(20), dp(8));
        input.setLayoutParams(inputLp);

        new AlertDialog.Builder(this)
            .setTitle(R.string.folder_picker_new_folder_title)
            .setView(input)
            .setPositiveButton(R.string.folder_picker_create, (d, w) -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    showThemedMessage(getString(R.string.folder_picker_enter_name));
                    return;
                }
                // Reject names with path separators
                if (name.contains("/") || name.contains("\\")) {
                    showThemedMessage(getString(R.string.folder_picker_invalid_name));
                    return;
                }
                File newDir = new File(currentDir, name);
                if (newDir.exists()) {
                    showThemedMessage(getString(R.string.folder_picker_already_exists));
                } else if (newDir.mkdir()) {
                    refreshList();
                    showThemedMessage(getString(R.string.folder_picker_created, name));
                } else {
                    showThemedMessage(getString(R.string.folder_picker_create_failed));
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Themed replacement for the old black-box Toasts. System Toasts render as an unreadable
     * black box on this ROM (targetSDK 28); this isn't a Compose screen so the shared
     * UninstallResultBar can't apply — use the dark alert-dialog style instead.
     */
    private void showThemedMessage(String msg) {
        new AlertDialog.Builder(this, com.winlator.star.R.style.StoreAlertDialogDark)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private LinearLayout makeDirRow(String label, File target, boolean isUp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(isUp ? 0xFF1E1A2E : 0xFF1A1E2E);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), 0xFF2A2A3A);
        row.setBackground(bg);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(isUp ? 0xFFAAAAAA : 0xFFDDDDFF);
        tv.setTextSize(13f);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1f));

        if (!isUp) {
            TextView arrowTV = new TextView(this);
            arrowTV.setText("›");
            arrowTV.setTextColor(0xFF555577);
            arrowTV.setTextSize(18f);
            row.addView(arrowTV, new LinearLayout.LayoutParams(-2, -2));
        }

        row.setOnClickListener(v -> {
            currentDir = target;
            refreshList();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        return row;
    }

    private Button makeBtn(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(0xFFFFFFFF);
        btn.setTextSize(13f);
        btn.setTypeface(null, Typeface.BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        return btn;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
