package com.winlator.star.box64;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;

import androidx.preference.PreferenceManager;

import com.winlator.star.R;
import com.winlator.star.SettingsFragment;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.EnvVars;
import com.winlator.star.core.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;

public abstract class Box64PresetManager {
    /**
     * Pref holding user edits to BUILT-IN presets, as {@code ID|envvars} joined by commas — the
     * same escaping rules as the custom-preset list, so neither separator can appear in a value.
     * The hardcoded blocks in {@link #getEnvVars} stay the shipped originals no matter what is in
     * here, which is what lets {@link #resetPreset} always restore them.
     */
    private static String overridesKey(String prefix) {
        return prefix + "_preset_overrides";
    }

    private static EnvVars getOverride(String prefix, Context context, String id) {
        String stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(overridesKey(prefix), "");
        if (stored == null || stored.isEmpty()) return null;
        for (String entry : stored.split(",")) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length == 2 && parts[0].equals(id)) return new EnvVars(parts[1]);
        }
        return null;
    }

    /** True when a built-in preset has been edited, i.e. Reset would change something. */
    public static boolean hasOverride(String prefix, Context context, String id) {
        return id != null && !id.startsWith(Box64Preset.CUSTOM) && getOverride(prefix, context, id) != null;
    }

    private static void putOverride(String prefix, Context context, String id, EnvVars envVars) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String stored = preferences.getString(overridesKey(prefix), "");
        ArrayList<String> out = new ArrayList<>();
        if (stored != null && !stored.isEmpty()) {
            for (String entry : stored.split(",")) {
                String[] parts = entry.split("\\|", 2);
                // Drop any existing entry for this id; null envVars means "reset", so it just goes.
                if (parts.length == 2 && !parts[0].equals(id)) out.add(entry);
            }
        }
        if (envVars != null) out.add(id + "|" + envVars);
        preferences.edit().putString(overridesKey(prefix), String.join(",", out)).apply();
    }

    /** Discard a built-in preset's user edits, restoring the values this build ships. */
    public static void resetPreset(String prefix, Context context, String id) {
        if (id == null || id.startsWith(Box64Preset.CUSTOM)) return;
        putOverride(prefix, context, id, null);
    }

    public static EnvVars getEnvVars(String prefix, Context context, String id) {
        String ucPrefix = prefix.toUpperCase(Locale.ENGLISH);
        EnvVars envVars = new EnvVars();

        // A user edit to a built-in wins over the shipped values below.
        if (!id.startsWith(Box64Preset.CUSTOM)) {
            EnvVars override = getOverride(prefix, context, id);
            if (override != null) return override;
        }

        if (id.equals(Box64Preset.STABILITY)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "2");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        }
        else if (id.equals(Box64Preset.COMPATIBILITY)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
            }
        }
        else if (id.equals(Box64Preset.INTERMEDIATE)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "1");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "128");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
            }
        }
        else if (id.equals(Box64Preset.PERFORMANCE)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");

            }
        }
        else if (id.equals(Box64Preset.PERFORMANCE_MALI)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "0");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
                envVars.put("BOX64_DYNAREC_WEAKBARRIER", "1");
                envVars.put("BOX64_DYNAREC_NATIVEFLAGS", "0");

            }
        }
        else if (id.equals(Box64Preset.EXTREME)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "1");
                envVars.put("BOX64_DYNAREC_WEAKBARRIER", "1");
                envVars.put("BOX64_DYNAREC_DIRTY", "1");
                envVars.put("BOX64_DYNAREC_NATIVEFLAGS", "0");

            }
        }
        else if (id.equals(Box64Preset.EXTREME_2)) {
            // The community "Extreme" box64 preset, 32 variables — kept as a SECOND tier so the
            // original Extreme above stays byte-identical for anyone already running it.
            // Versus Extreme: FASTROUND 1->2, FORWARD 512->1024, WEAKBARRIER 1->2, DIRTY 1->0,
            // NATIVEFLAGS 0->1, plus the dynarec/CPU-feature knobs below that Extreme leaves at
            // box64's defaults.
            // NATIVEFLAGS=1 is the main speed lever. WEAKBARRIER=2 is the risky one — weaker
            // memory barriers are faster but can corrupt multi-threaded state, and it fails as
            // random instability rather than an obvious break, so Extreme remains the fallback.
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "2");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "0");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "1024");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "1");
            if (ucPrefix.equals("BOX64")) {
                // BOX64-only: box86 either lacks these or names them differently, so they stay
                // guarded exactly as Extreme does.
                envVars.put("BOX64_DYNAREC", "1");
                envVars.put("BOX64_DYNAREC_SEP", "1");
                envVars.put("BOX64_DYNAREC_WEAKBARRIER", "2");
                envVars.put("BOX64_DYNAREC_ALIGNED_ATOMICS", "0");
                envVars.put("BOX64_DYNAREC_DF", "1");
                envVars.put("BOX64_DYNAREC_DIRTY", "0");
                envVars.put("BOX64_DYNAREC_NATIVEFLAGS", "1");
                envVars.put("BOX64_DYNAREC_PAUSE", "0");
                envVars.put("BOX64_DYNAREC_NOARCH", "0");
                envVars.put("BOX64_DYNAREC_VOLATILE_METADATA", "1");
                envVars.put("BOX64_DYNACACHE", "0");
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_AES", "1");
                envVars.put("BOX64_PCLMULQDQ", "1");
                envVars.put("BOX64_SHAEXT", "1");
                envVars.put("BOX64_SSE42", "1");
                envVars.put("BOX64_SSE_FLUSHTO0", "0");
                envVars.put("BOX64_X87_NO80BITS", "0");
                envVars.put("BOX64_CPUTYPE", "0");
                envVars.put("BOX64_MAXCPU", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_UNITY", "0");
                envVars.put("BOX64_MMAP32", "1");
            }
        }
        else if (id.equals(Box64Preset.UNITY)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "3");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "2");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "0");
                envVars.put("BOX64_DYNAREC_NATIVEFLAGS", "0");

            }
        }
        else if (id.equals(Box64Preset.UNITY_MONO_BLEEDING_EDGE)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "1");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "1");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "0");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "1");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "1");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "0");
                envVars.put("BOX64_MMAP32", "0");
                envVars.put("BOX64_DYNAREC_NATIVEFLAGS", "0");

            }
        }
        else if (id.equals(Box64Preset.DENUVO)) {
            envVars.put(ucPrefix+"_DYNAREC_SAFEFLAGS", "2");
            envVars.put(ucPrefix+"_DYNAREC_FASTNAN", "0");
            envVars.put(ucPrefix+"_DYNAREC_FASTROUND", "0");
            envVars.put(ucPrefix+"_DYNAREC_X87DOUBLE", "1");
            envVars.put(ucPrefix+"_DYNAREC_BIGBLOCK", "0");
            envVars.put(ucPrefix+"_DYNAREC_STRONGMEM", "3");
            envVars.put(ucPrefix+"_DYNAREC_FORWARD", "512");
            envVars.put(ucPrefix+"_DYNAREC_CALLRET", "0");
            envVars.put(ucPrefix+"_DYNAREC_WAIT", "0");
            if (ucPrefix.equals("BOX64")) {
                envVars.put("BOX64_AVX", "0");
                envVars.put("BOX64_UNITYPLAYER", "1");
                envVars.put("BOX64_MMAP32", "0");
                envVars.put("BOX64_DYNAREC_NATIVEFLAGS", "0");

            }
        }
        else if (id.startsWith(Box64Preset.CUSTOM)) {
            for (String[] preset : customPresetsIterator(prefix, context)) {
                if (preset[0].equals(id)) {
                    envVars.putAll(preset[2]);
                    break;
                }
            }
        }

        return envVars;
    }

    public static ArrayList<Box64Preset> getPresets(String prefix, Context context) {
        ArrayList<Box64Preset> presets = new ArrayList<>();
        presets.add(new Box64Preset(Box64Preset.STABILITY, context.getString(R.string.stability)));
        presets.add(new Box64Preset(Box64Preset.COMPATIBILITY, context.getString(R.string.compatibility)));
        presets.add(new Box64Preset(Box64Preset.INTERMEDIATE, context.getString(R.string.intermediate)));
        presets.add(new Box64Preset(Box64Preset.PERFORMANCE, context.getString(R.string.performance)));
        presets.add(new Box64Preset(Box64Preset.PERFORMANCE_MALI, context.getString(R.string.performance_mali)));
        presets.add(new Box64Preset(Box64Preset.EXTREME, context.getString(R.string.extreme)));
        presets.add(new Box64Preset(Box64Preset.EXTREME_2, context.getString(R.string.extreme_2)));
        presets.add(new Box64Preset(Box64Preset.UNITY, context.getString(R.string.unity)));
        presets.add(new Box64Preset(Box64Preset.UNITY_MONO_BLEEDING_EDGE, context.getString(R.string.unity_mono_bleeding_edge)));
        presets.add(new Box64Preset(Box64Preset.DENUVO, context.getString(R.string.denuvo)));
        for (String[] preset : customPresetsIterator(prefix, context)) presets.add(new Box64Preset(preset[0], preset[1]));
        return presets;
    }

    public static Box64Preset getPreset(String prefix, Context context, String id) {
        for (Box64Preset preset : getPresets(prefix, context)) if (preset.id.equals(id)) return preset;
        return null;
    }

    private static Iterable<String[]> customPresetsIterator(String prefix, Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        final String customPresetsStr = preferences.getString(prefix+"_custom_presets", "");
        final String[] customPresets = customPresetsStr.split(",");
        final int[] index = {0};
        return () -> new Iterator<String[]>() {
            @Override
            public boolean hasNext() {
                return index[0] < customPresets.length && !customPresetsStr.isEmpty();
            }

            @Override
            public String[] next() {
                return customPresets[index[0]++].split("\\|");
            }
        };
    }

    public static int getNextPresetId(Context context, String prefix) {
        int maxId = 0;
        for (String[] preset : customPresetsIterator(prefix, context)) {
            maxId = Math.max(maxId, Integer.parseInt(preset[0].replace(Box64Preset.CUSTOM+"-", "")));
        }
        return maxId+1;
    }

    public static void editPreset(String prefix, Context context, String id, String name, EnvVars envVars) {
        // Built-in presets are editable too: their values are stored as an override rather than
        // rewritten in place, so Reset can put the shipped ones back. The name is fixed for these
        // (it comes from a string resource), so only the values are kept.
        if (id != null && !id.startsWith(Box64Preset.CUSTOM)) {
            putOverride(prefix, context, id, envVars);
            return;
        }

        String key = prefix+"_custom_presets";
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String customPresetsStr = preferences.getString(key, "");

        if (id != null) {
            String[] customPresets = customPresetsStr.split(",");
            for (int i = 0; i < customPresets.length; i++) {
                String[] preset = customPresets[i].split("\\|");
                if (preset[0].equals(id)) {
                    customPresets[i] = id+"|"+name+"|"+envVars.toString();
                    break;
                }
            }
            customPresetsStr = String.join(",", customPresets);
        }
        else {
            String preset = Box64Preset.CUSTOM+"-"+getNextPresetId(context, prefix)+"|"+name+"|"+envVars.toString();
            customPresetsStr += (!customPresetsStr.isEmpty() ? "," : "")+preset;
        }
        preferences.edit().putString(key, customPresetsStr).apply();
    }

    public static void duplicatePreset(String prefix, Context context, String id) {
        ArrayList<Box64Preset> presets = getPresets(prefix, context);
        Box64Preset originPreset = null;
        for (Box64Preset preset : presets) {
            if (preset.id.equals(id)) {
                originPreset = preset;
                break;
            }
        }
        if (originPreset == null) return;

        String newName;
        for (int i = 1;;i++) {
            newName = originPreset.name+" ("+i+")";
            boolean found = false;
            for (Box64Preset preset : presets) {
                if (preset.name.equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        editPreset(prefix, context, null, newName, getEnvVars(prefix, context, originPreset.id));
    }

    public static void removePreset(String prefix, Context context, String id) {
        String key = prefix+"_custom_presets";
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String oldCustomPresetsStr = preferences.getString(key, "");
        String newCustomPresetsStr = "";

        String[] customPresets = oldCustomPresetsStr.split(",");
        for (int i = 0; i < customPresets.length; i++) {
            String[] preset = customPresets[i].split("\\|");
            if (!preset[0].equals(id)) newCustomPresetsStr += (!newCustomPresetsStr.isEmpty() ? "," : "")+customPresets[i];
        }

        preferences.edit().putString(key, newCustomPresetsStr).apply();
    }

    public static void exportPreset(String prefix, Context context, String id) {
        File presetFile = null;
        String key = prefix + "_custom_presets";
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String[] customPresets = preferences.getString(key, "").split(",");

        for (int i = 0; i < customPresets.length; i++) {
            String[] preset = customPresets[i].split("\\|");
            if (preset[0].equals(id)) {;
                String uriPath = preferences.getString("winlator_path_uri", null);
                if (uriPath != null) {
                    Uri uri = Uri.parse(uriPath);
                    String path = FileUtils.getFilePathFromUri(context, uri);
                    presetFile = new File(path, "Presets/" + prefix + "_" + preset[1] + ".wbp");
                }
                else {
                    presetFile = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "Presets/" + prefix + "_" + preset[1] + ".wbp");
                }
                if (!presetFile.getParentFile().exists())
                    presetFile.getParentFile().mkdirs();

                try {
                    FileOutputStream fos = new FileOutputStream(presetFile);
                    PrintWriter pw = new PrintWriter(fos);
                    pw.write("ID:" + preset[0] + "\n");
                    pw.write("Name:" + preset[1] + "\n");
                    pw.write("EnvVars:" + preset[2] + "\n");
                    pw.close();
                    fos.close();
                } catch (IOException e) {
                }
                break;
            }
        }
        if (presetFile != null && presetFile.exists())
            AppUtils.showToast(context, "Preset " + presetFile.getName() + " exported successfully at " + presetFile.getParentFile().getPath());
        else
            AppUtils.showToast(context, "Failed to export preset");
    }

    public static void importPreset(String prefix, Context context, InputStream stream) {
        String key = prefix + "_custom_presets";
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String customPresetStr = preferences.getString(key, "");
        ArrayList<String> lines = new ArrayList<>();

        try {
            String[] preset = new String[3];
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            for (int i = 0; i < lines.size(); i++) {
                String[] contents = lines.get(i).split(":");
                switch (contents[0]) {
                    case "ID":
                        preset[0] = contents[1];
                        break;
                    case "Name":
                        preset[1] = contents[1];
                        break;
                    case "EnvVars":
                        preset[2] = contents[1];
                        break;
                }
            }
            customPresetStr = customPresetStr + (!customPresetStr.equals("") ? "," : "") + Box64Preset.CUSTOM+"-"+getNextPresetId(context, prefix) + "|" + preset[1] + "|" + preset[2];
        } catch (IOException e) {
        }

        preferences.edit().putString(key, customPresetStr).apply();
    }

    public static void loadSpinner(String prefix, Spinner spinner, String selectedId) {
        Context context = spinner.getContext();
        ArrayList<Box64Preset> presets = getPresets(prefix, context);

        int selectedPosition = 0;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).id.equals(selectedId)) {
                selectedPosition = i;
                break;
            }
        }

        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, presets));
        spinner.setSelection(selectedPosition);
    }

    public static String getSpinnerSelectedId(Spinner spinner) {
        SpinnerAdapter adapter = spinner.getAdapter();
        int selectedPosition = spinner.getSelectedItemPosition();
        if (adapter != null && adapter.getCount() > 0 && selectedPosition >= 0) {
            return ((Box64Preset)adapter.getItem(selectedPosition)).id;
        }
        else return Box64Preset.COMPATIBILITY;
    }
}
