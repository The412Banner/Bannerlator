package com.winlator.star.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.JsonReader;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.star.SettingsFragment;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.FileUtils;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InputControlsManager {
    static final String ICPX_FORMAT = "bannerlator.icpx";
    static final int ICPX_FORMAT_VERSION = 1;
    static final int ICPX_MIN_READER_VERSION = 1;
    private static final Object PROFILE_IMPORT_LOCK = new Object();
    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public static final class CustomIconUsage {
        private final int controlCount;
        private final ArrayList<String> profileNames;

        private CustomIconUsage(int controlCount, ArrayList<String> profileNames) {
            this.controlCount = controlCount;
            this.profileNames = profileNames;
        }

        public int getControlCount() {
            return controlCount;
        }

        public List<String> getProfileNames() {
            return Collections.unmodifiableList(profileNames);
        }
    }

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles(ignoreTemplates);
        return profiles;
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        recoverAtomicProfiles(profilesDir);
        if (FileUtils.isEmpty(profilesDir)) {
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        int newVersion = AppUtils.getVersionCode(context);
        int oldVersion = preferences.getInt("inputcontrols_app_version", 0);
        if (oldVersion == newVersion) return;
        preferences.edit().putInt("inputcontrols_app_version", newVersion).apply();

        File[] files = profilesDir.listFiles((dir, name) -> name.startsWith("controls-") && name.endsWith(".icp"));
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));
                if (originProfile == null) continue;

                File targetFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadProfile(context, file);
                    if (targetProfile == null) continue;
                    if (originProfile.id == targetProfile.id && originProfile.getName().equals(targetProfile.getName())) {
                        targetFile = file;
                        break;
                    }
                }

                if (targetFile != null) {
                    FileUtils.copy(context, assetPath, targetFile);
                }
            }
        }
        catch (IOException e) {}
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles((dir, name) -> name.startsWith("controls-") && name.endsWith(".icp"));
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                if (profile == null) continue;
                if (!(ignoreTemplates && profile.isTemplate())) profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(readStringAtomically(ControlsProfile.getProfileFile(context, source.id)));
            data.put("schemaVersion", ControlsProfile.SCHEMA_VERSION);
            data.put("minEditorVersion", ControlsProfile.MIN_EDITOR_VERSION);
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException | IOException e) {}

        ControlsProfile profile = loadProfile(context, newFile);
        profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    @Nullable
    public ControlsProfile importProfile(JSONObject data) {
        synchronized (PROFILE_IMPORT_LOCK) {
            return importProfileLocked(data);
        }
    }

    private static void recoverAtomicProfiles(File profilesDir) {
        File[] recoveryFiles = profilesDir.listFiles((dir, name) ->
                name.startsWith("controls-") && (name.endsWith(".icp.bak") || name.endsWith(".icp.new")));
        if (recoveryFiles == null) return;
        for (File recoveryFile : recoveryFiles) {
            String name = recoveryFile.getName();
            File baseFile = new File(profilesDir, name.substring(0, name.length() - 4));
            try (InputStream ignored = new AtomicFile(baseFile).openRead()) {}
            catch (IOException ignored) {
                if (name.endsWith(".new") && !baseFile.isFile()) recoveryFile.delete();
            }
        }
    }

    private ControlsProfile importProfileLocked(JSONObject sourceData) {
        if (sourceData == null) return null;
        if (!profilesLoaded) loadProfiles(false);
        CustomIconManager customIconManager = new CustomIconManager(context);
        ArrayList<Short> importedIconIds = new ArrayList<>();
        try {
            JSONObject data = new JSONObject(sourceData.toString());
            if (!isSupportedTransportFormat(data)) return null;
            Object profileName = data.opt("name");
            if (!data.has("id") || !(profileName instanceof String)
                    || ((String)profileName).trim().isEmpty()) return null;
            Integer schemaVersion = getIntegralVersion(data, "schemaVersion", 1);
            Integer minEditorVersion = getIntegralVersion(data, "minEditorVersion", 1);
            if (schemaVersion == null || minEditorVersion == null || !isValidImportedProfile(data)) return null;
            if (schemaVersion > ControlsProfile.SCHEMA_VERSION
                    || minEditorVersion > ControlsProfile.EDITOR_VERSION) return null;
            truncateProfileCombos(data);

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getName().equals(profileName)) {
                    foundIndex = i;
                    break;
                }
            }

            Map<Integer, Integer> iconIdMap = new HashMap<>();
            JSONArray embeddedIcons = data.optJSONArray("customIcons");
            if (embeddedIcons != null) {
                JSONArray elements = data.optJSONArray("elements");
                if (elements != null) {
                    for (int i = 0; i < elements.length(); i++) {
                        JSONObject element = elements.optJSONObject(i);
                        int iconId = element != null ? normalizeLegacyIconId(element.optInt("iconId", 0)) : 0;
                        if (iconId >= CustomIconManager.CUSTOM_ICON_ID_OFFSET
                                && iconId <= CustomIconManager.MAX_CUSTOM_ICON_ID) iconIdMap.put(iconId, 0);
                    }
                }
                for (int i = 0; i < embeddedIcons.length(); i++) {
                    JSONObject embeddedIcon = embeddedIcons.optJSONObject(i);
                    if (embeddedIcon == null) continue;
                    int sourceId = normalizeLegacyIconId(embeddedIcon.optInt("id", -1));
                    if (!iconIdMap.containsKey(sourceId) || iconIdMap.get(sourceId) != 0) continue;
                    CustomIconManager.ImportedIcon importedIcon = customIconManager.importEncodedIcon(
                            embeddedIcon.optString("png", null));
                    if (importedIcon != null) {
                        iconIdMap.put(sourceId, (int)importedIcon.id);
                        if (importedIcon.created) importedIconIds.add(importedIcon.id);
                    }
                }
                for (int targetId : iconIdMap.values()) {
                    if (targetId == 0) {
                        rollbackImportedIcons(customIconManager, importedIconIds);
                        return null;
                    }
                }
                data.remove("customIcons");
            }
            remapIconIds(data, iconIdMap);

            int newId = foundIndex >= 0 ? profiles.get(foundIndex).id : maxProfileId + 1;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            String previousData = foundIndex >= 0 ? readStringAtomically(newFile) : null;
            data.put("schemaVersion", ControlsProfile.SCHEMA_VERSION);
            data.put("minEditorVersion", ControlsProfile.MIN_EDITOR_VERSION);
            data.remove("format");
            data.remove("formatVersion");
            data.remove("minReaderVersion");
            data.put("id", newId);
            if (!isValidImportedProfile(data)) {
                rollbackImportedIcons(customIconManager, importedIconIds);
                return null;
            }
            if (!writeStringAtomically(newFile, data.toString())) {
                rollbackImportedIcons(customIconManager, importedIconIds);
                return null;
            }
            ControlsProfile newProfile = loadProfile(context, newFile);
            if (newProfile == null) {
                if (previousData != null) writeStringAtomically(newFile, previousData);
                else new AtomicFile(newFile).delete();
                rollbackImportedIcons(customIconManager, importedIconIds);
                return null;
            }

            if (foundIndex != -1) {
                profiles.set(foundIndex, newProfile);
            }
            else {
                maxProfileId = newId;
                profiles.add(newProfile);
            }
            return newProfile;
        }
        catch (JSONException | IOException | RuntimeException e) {
            rollbackImportedIcons(customIconManager, importedIconIds);
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        if (!profile.save()) return null;
        File destination = getExportDestination(profile, ".icpx");
        File source = ControlsProfile.getProfileFile(context, profile.id);
        try {
            JSONObject data = new JSONObject(readStringAtomically(source));
            JSONArray elements = data.optJSONArray("elements");
            Set<Integer> iconIds = new HashSet<>();
            if (elements != null) {
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.optJSONObject(i);
                    if (element == null) continue;
                    int iconId = normalizeLegacyIconId(element.optInt("iconId", 0));
                    if (iconId != element.optInt("iconId", 0)) element.put("iconId", iconId);
                    if (iconId >= CustomIconManager.CUSTOM_ICON_ID_OFFSET) iconIds.add(iconId);
                }
            }

            CustomIconManager customIconManager = new CustomIconManager(context);
            JSONArray embeddedIcons = new JSONArray();
            for (int iconId : iconIds) {
                String encodedIcon = customIconManager.encodeIcon(iconId);
                if (encodedIcon == null) return null;
                JSONObject embeddedIcon = new JSONObject();
                embeddedIcon.put("id", iconId);
                embeddedIcon.put("png", encodedIcon);
                embeddedIcons.put(embeddedIcon);
            }
            if (embeddedIcons.length() > 0) data.put("customIcons", embeddedIcons);
            else data.remove("customIcons");
            addTransportHeader(data);
            if (!writeExportFile(destination, data)) return null;
        }
        catch (JSONException | IOException e) {
            return null;
        }
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    public File exportLegacyProfile(ControlsProfile profile) {
        if (!profile.save()) return null;
        File destination = getExportDestination(profile, ".icp");
        File source = ControlsProfile.getProfileFile(context, profile.id);
        try {
            JSONObject data = prepareLegacyExport(new JSONObject(readStringAtomically(source)));
            if (!writeExportFile(destination, data)) return null;
        }
        catch (JSONException | IOException e) {
            return null;
        }
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    static JSONObject prepareLegacyExport(JSONObject data) {
        data.remove("format");
        data.remove("formatVersion");
        data.remove("minReaderVersion");
        data.remove("customIcons");
        return data;
    }

    private File getExportDestination(ControlsProfile profile, String extension) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String winlatorPath = preferences.getString("winlator_path_uri", null);
        File root = winlatorPath != null
                ? new File(FileUtils.getFilePathFromUri(context, Uri.parse(winlatorPath)))
                : new File(SettingsFragment.DEFAULT_WINLATOR_PATH);
        return new File(root, "profiles/" + getSafeProfileName(profile) + extension);
    }

    private static boolean writeExportFile(File destination, JSONObject data) throws IOException {
        File parent = destination.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return false;
        File temporaryFile = FileUtils.createTempFile(parent, destination.getName());
        try {
            if (!FileUtils.writeString(temporaryFile, data.toString())) return false;
            try {
                Files.move(temporaryFile.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException atomicMoveError) {
                Files.move(temporaryFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
        finally {
            temporaryFile.delete();
        }
    }

    static void remapIconIds(JSONObject data, Map<Integer, Integer> iconIdMap) throws JSONException {
        JSONArray elements = data.optJSONArray("elements");
        if (elements == null || iconIdMap.isEmpty()) return;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null) continue;
            int sourceIconId = normalizeLegacyIconId(element.optInt("iconId", 0));
            Integer targetIconId = iconIdMap.get(sourceIconId);
            if (targetIconId != null) element.put("iconId", targetIconId);
            else if (sourceIconId != element.optInt("iconId", 0)) element.put("iconId", sourceIconId);
        }
    }

    static boolean isSupportedTransportFormat(JSONObject data) {
        if (!data.has("format")) return true;
        if (!ICPX_FORMAT.equals(data.optString("format", ""))) return false;
        Integer formatVersion = getIntegralVersion(data, "formatVersion");
        Integer minReaderVersion = getIntegralVersion(data, "minReaderVersion");
        return formatVersion != null
                && minReaderVersion != null
                && formatVersion >= 1
                && minReaderVersion >= 1
                && minReaderVersion <= formatVersion
                && minReaderVersion <= ICPX_FORMAT_VERSION;
    }

    private static Integer getIntegralVersion(JSONObject data, String key) {
        Object value = data.opt(key);
        if (!(value instanceof Number)) return null;
        double number = ((Number)value).doubleValue();
        if (!Double.isFinite(number) || number != Math.rint(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
        return (int)number;
    }

    private static Integer getIntegralVersion(JSONObject data, String key, int defaultValue) {
        return data.has(key) ? getIntegralVersion(data, key) : defaultValue;
    }

    static int normalizeLegacyIconId(int iconId) {
        return iconId >= Byte.MIN_VALUE && iconId < 0 ? iconId & 0xFF : iconId;
    }

    static int countCustomIconReferences(JSONObject data, int iconId) {
        if (!data.has("elements")) return 0;
        JSONArray elements = data.optJSONArray("elements");
        if (elements == null) return -1;

        int references = 0;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null) return -1;
            if (!element.has("iconId")) continue;
            Integer storedIconId = getIntegralVersion(element, "iconId");
            if (storedIconId == null) return -1;
            if (normalizeLegacyIconId(storedIconId) == iconId) references++;
        }
        return references;
    }

    @Nullable
    static CustomIconUsage getCustomIconUsage(ArrayList<JSONObject> profiles, int iconId) {
        int references = 0;
        ArrayList<String> profileNames = new ArrayList<>();
        for (JSONObject profile : profiles) {
            int profileReferences = countCustomIconReferences(profile, iconId);
            if (profileReferences < 0) return null;
            if (profileReferences == 0) continue;

            Object nameValue = profile.opt("name");
            if (!(nameValue instanceof String) || ((String)nameValue).trim().isEmpty()) return null;
            references += profileReferences;
            profileNames.add(((String)nameValue).trim());
        }
        profileNames.sort(String.CASE_INSENSITIVE_ORDER);
        return new CustomIconUsage(references, profileNames);
    }

    @Nullable
    public static CustomIconUsage getCustomIconUsage(Context context, int iconId) {
        if (iconId < CustomIconManager.CUSTOM_ICON_ID_OFFSET
                || iconId > CustomIconManager.MAX_CUSTOM_ICON_ID) return null;
        File[] files = getProfilesDir(context).listFiles(
                (dir, name) -> name.startsWith("controls-") && name.endsWith(".icp"));
        if (files == null) return null;

        ArrayList<JSONObject> profiles = new ArrayList<>(files.length);
        for (File file : files) {
            try {
                profiles.add(new JSONObject(readStringAtomically(file)));
            }
            catch (IOException | JSONException e) {
                return null;
            }
        }
        return getCustomIconUsage(profiles, iconId);
    }

    public static int countCustomIconReferences(Context context, int iconId) {
        CustomIconUsage usage = getCustomIconUsage(context, iconId);
        return usage != null ? usage.getControlCount() : -1;
    }

    static void truncateProfileCombos(JSONObject data) {
        JSONArray elements = data.optJSONArray("elements");
        if (elements == null) return;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            JSONArray combos = element != null ? element.optJSONArray("combos") : null;
            if (combos == null) continue;
            for (int j = 0; j < combos.length(); j++) {
                JSONArray entry = combos.optJSONArray(j);
                JSONArray keys = entry != null ? entry.optJSONArray(1) : null;
                if (keys == null || keys.length() <= ControlElement.MAX_COMBO_BINDINGS) continue;
                JSONArray truncatedKeys = new JSONArray();
                for (int k = 0; k < ControlElement.MAX_COMBO_BINDINGS; k++) {
                    truncatedKeys.put(keys.opt(k));
                }
                try {
                    entry.put(1, truncatedKeys);
                }
                catch (JSONException ignored) {}
            }
        }
    }

    static boolean isValidImportedProfile(JSONObject data) {
        Object name = data.opt("name");
        if (!(name instanceof String) || ((String)name).trim().isEmpty()) return false;
        if (data.has("cursorSpeed") && !isFiniteNumber(data, "cursorSpeed")) return false;
        if (data.has("customAccentEnabled") && !(data.opt("customAccentEnabled") instanceof Boolean)) return false;
        if (data.has("customAccentColor") && !(data.opt("customAccentColor") instanceof Number)) return false;
        JSONArray controllers = data.optJSONArray("controllers");
        if (data.has("controllers") && controllers == null) return false;
        if (controllers != null) {
            for (int i = 0; i < controllers.length(); i++) {
                JSONObject controller = controllers.optJSONObject(i);
                if (controller == null || !isValidImportedController(controller)) return false;
            }
        }
        JSONArray elements = data.optJSONArray("elements");
        if (data.has("elements") && elements == null) return false;
        if (elements == null) return true;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null || !isValidImportedElement(element)) return false;
        }
        return true;
    }

    private static boolean isValidImportedElement(JSONObject element) {
        Object typeValue = element.opt("type");
        if (!(typeValue instanceof String)) return false;
        try {
            ControlElement.Type.valueOf((String)typeValue);
        }
        catch (IllegalArgumentException e) {
            return false;
        }

        Object shapeValue = element.opt("shape");
        if (!(shapeValue instanceof String)) return false;
        try {
            ControlElement.Shape.valueOf((String)shapeValue);
        }
        catch (IllegalArgumentException e) {
            return false;
        }
        if (!(element.opt("toggleSwitch") instanceof Boolean)
                || !(element.opt("text") instanceof String)
                || !(element.opt("iconId") instanceof Number)
                || !isFiniteNumber(element, "x")
                || !isFiniteNumber(element, "y")
                || !isFiniteNumber(element, "scale")) return false;
        JSONArray bindings = element.optJSONArray("bindings");
        if (bindings == null) return false;
        for (int i = 0; i < bindings.length(); i++) {
            if (!(bindings.opt(i) instanceof String)) return false;
        }
        JSONArray blockTouchscreenMouseButtons = element.optJSONArray("blockTouchscreenMouseButtons");
        if (element.has("blockTouchscreenMouseButtons") && blockTouchscreenMouseButtons == null) return false;
        if (blockTouchscreenMouseButtons != null) {
            for (int i = 0; i < blockTouchscreenMouseButtons.length(); i++) {
                if (!(blockTouchscreenMouseButtons.opt(i) instanceof Boolean)) return false;
            }
        }
        if (element.has("gridMultitouchEnabled")
                && !(element.opt("gridMultitouchEnabled") instanceof Boolean)) return false;
        if (element.has("customIconTintEnabled")
                && !(element.opt("customIconTintEnabled") instanceof Boolean)) return false;
        if (element.has("customIconAsButton")
                && !(element.opt("customIconAsButton") instanceof Boolean)) return false;
        String[] optionalNumbers = {"deadZone", "mouseSensitivity", "customAreaOpacity", "gridSpacing",
                "areaWidthRatio", "areaHeightRatio", "stickRadiusRatio"};
        for (String key : optionalNumbers) {
            if (element.has(key) && !isFiniteNumber(element, key)) return false;
        }
        String[] positiveRatios = {"areaWidthRatio", "areaHeightRatio", "stickRadiusRatio"};
        for (String key : positiveRatios) {
            if (element.has(key) && ((Number)element.opt(key)).doubleValue() <= 0) return false;
        }
        return true;
    }

    private static boolean isValidImportedController(JSONObject controller) {
        if (!(controller.opt("id") instanceof String)
                || ((String)controller.opt("id")).trim().isEmpty()
                || !(controller.opt("name") instanceof String)) return false;
        JSONArray bindings = controller.optJSONArray("controllerBindings");
        if (bindings == null) return false;
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject binding = bindings.optJSONObject(i);
            if (binding == null || !(binding.opt("keyCode") instanceof Number)
                    || getIntegralVersion(binding, "keyCode") == null
                    || !(binding.opt("binding") instanceof String)) return false;
        }
        return true;
    }

    private static boolean isFiniteNumber(JSONObject data, String key) {
        Object value = data.opt(key);
        return value instanceof Number && Double.isFinite(((Number)value).doubleValue());
    }

    static boolean writeStringAtomically(File file, String value) {
        AtomicFile atomicFile = new AtomicFile(file);
        FileOutputStream outputStream = null;
        try {
            outputStream = atomicFile.startWrite();
            outputStream.write(value.getBytes(StandardCharsets.UTF_8));
            atomicFile.finishWrite(outputStream);
            return true;
        }
        catch (IOException e) {
            if (outputStream != null) atomicFile.failWrite(outputStream);
            return false;
        }
    }

    static String readStringAtomically(File file) throws IOException {
        AtomicFile atomicFile = new AtomicFile(file);
        try (InputStream inputStream = atomicFile.openRead();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = inputStream.read(buffer)) != -1) outputStream.write(buffer, 0, count);
            return outputStream.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void addTransportHeader(JSONObject data) throws JSONException {
        data.put("format", ICPX_FORMAT);
        data.put("formatVersion", ICPX_FORMAT_VERSION);
        data.put("minReaderVersion", ICPX_MIN_READER_VERSION);
    }

    private static String getSafeProfileName(ControlsProfile profile) {
        return profile.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void rollbackImportedIcons(CustomIconManager manager, ArrayList<Short> importedIconIds) {
        for (short iconId : importedIconIds) manager.deleteIcon(iconId);
    }


    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new AtomicFile(file).openRead());
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            boolean customAccentEnabled = false;
            int customAccentColor = 0xFF0055FF;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float) reader.nextDouble();
                }
                else if (name.equals("customAccentEnabled")) {
                    customAccentEnabled = reader.nextBoolean();
                }
                else if (name.equals("customAccentColor")) {
                    customAccentColor = reader.nextInt();
                }
                else {
                    reader.skipValue();
                }
            }

            if (profileName == null || profileName.trim().isEmpty()) return null;
            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(Float.isNaN(cursorSpeed) ? 1.0f : cursorSpeed);
            profile.setCustomAccentEnabled(customAccentEnabled);
            profile.setCustomAccentColor(customAccentColor);
            return profile;
        }
        catch (IOException | IllegalStateException | NumberFormatException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}
