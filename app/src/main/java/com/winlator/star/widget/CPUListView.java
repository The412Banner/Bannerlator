package com.winlator.star.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.CompoundButtonCompat;

import com.winlator.star.R;
import com.winlator.star.deviceinfo.CpuCores;
import com.winlator.star.ui.theme.AppThemeState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The core picker of the Classic container and game settings: the device's cores grouped by cluster, fastest
 * first, each group titled with its tier, core name and top clock read from the device
 * ("Prime · Cortex-X4 · 3.30 GHz"). Tapping a title switches the whole group. Checkboxes keep their "CPU<n>" tags.
 */
public class CPUListView extends LinearLayout {
    private List<String> checkedCPUList;
    private final byte numProcessors;
    private final List<CpuCores.Cluster> clusters;

    public CPUListView(Context context) {
        this(context, null);
    }

    public CPUListView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CPUListView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        List<CpuCores.Core> cores = CpuCores.INSTANCE.read();
        numProcessors = (byte)cores.size();
        clusters = CpuCores.INSTANCE.clusters(cores);
        refreshContent();
    }

    private void refreshContent() {
        removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        ColorStateList accentTint = currentAccentTint();
        int maxGroup = 4;
        for (CpuCores.Cluster c : clusters) maxGroup = Math.max(maxGroup, c.getCores().size());

        String chip = CpuCores.chipLabel();
        if (!chip.isEmpty()) {
            TextView chipView = new TextView(getContext());
            chipView.setText(chip);
            chipView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            chipView.setAlpha(0.75f);
            chipView.setPadding(dp(2), 0, 0, dp(2));
            addView(chipView);
        }

        for (CpuCores.Cluster c : clusters) {
            TextView title = new TextView(getContext());
            title.setText(CpuCores.INSTANCE.clusterTitle(c, c.getTier().getLabel()));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            title.setTextColor(accentTint);
            title.setPadding(dp(2), dp(6), 0, dp(2));
            final List<Integer> group = c.getCores();
            title.setOnClickListener(v -> toggleGroup(group));
            addView(title);

            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setWeightSum(maxGroup);
            for (int i : group) {
                View itemView = inflater.inflate(R.layout.cpu_list_item, row, false);
                String tag = "CPU"+i;
                CheckBox checkBox = itemView.findViewById(R.id.CheckBox);
                checkBox.setTag(tag);
                checkBox.setChecked(checkedCPUList == null || checkedCPUList.contains(String.valueOf(i)));
                CompoundButtonCompat.setButtonTintList(checkBox, accentTint);

                ((TextView)itemView.findViewById(R.id.TextView)).setText("Core "+i);
                row.addView(itemView);
            }
            addView(row);
        }
    }

    /** All of the group on, or all of it off when it was all on (unless that leaves no core). */
    private void toggleGroup(List<Integer> group) {
        boolean allOn = true;
        for (int i : group) {
            CheckBox cb = findViewWithTag("CPU"+i);
            if (cb != null && !cb.isChecked()) allOn = false;
        }
        if (allOn) {
            int othersOn = 0;
            for (int i = 0; i < numProcessors; i++) {
                if (group.contains(i)) continue;
                CheckBox cb = findViewWithTag("CPU"+i);
                if (cb != null && cb.isChecked()) othersOn++;
            }
            if (othersOn == 0) return;
        }
        for (int i : group) {
            CheckBox cb = findViewWithTag("CPU"+i);
            if (cb != null) cb.setChecked(!allOn);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Pull the current Compose-side accent (set via Appearance > custom color) so
     * legacy XML widgets visually agree with the rest of the UI. Defensive fallback
     * in case the theme singleton hasn't been initialized yet. */
    private static ColorStateList currentAccentTint() {
        try {
            return ColorStateList.valueOf(AppThemeState.getCurrentAccentArgb());
        } catch (Throwable ignored) {
            return ColorStateList.valueOf(0xFFBA86FC);
        }
    }

    public void setCheckedCPUList(String checkedCPUList) {
        this.checkedCPUList = new ArrayList<>(Arrays.asList(checkedCPUList.split(",")));
        refreshContent();
    }

    public void setCheckedCPUList(int from, int to) {
        if (checkedCPUList == null) checkedCPUList = new ArrayList<>();
        checkedCPUList.clear();
        for (int i = from; i < to; i++) checkedCPUList.add(String.valueOf(i));
        refreshContent();
    }

    public String getCheckedCPUListAsString() {
        String cpuList = "";

        for (int i = 0; i < numProcessors; i++) {
            CheckBox checkBox = findViewWithTag("CPU"+i);
            if (checkBox != null && checkBox.isChecked()) cpuList += (!cpuList.isEmpty() ? "," : "")+i;
        }
        return cpuList;
    }

    public boolean[] getCheckedCPUList() {
        boolean[] cpuList = new boolean[numProcessors];
        for (int i = 0; i < numProcessors; i++) {
            CheckBox checkBox = findViewWithTag("CPU"+i);
            cpuList[i] = checkBox != null && checkBox.isChecked();
        }
        return cpuList;
    }

    public byte getNumProcessors() {
        return numProcessors;
    }
}
