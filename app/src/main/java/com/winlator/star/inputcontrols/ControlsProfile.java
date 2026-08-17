package com.winlator.star.inputcontrols;

import android.content.Context;

import androidx.annotation.NonNull;

import com.winlator.star.core.FileUtils;
import com.winlator.star.widget.InputControlsView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ControlsProfile implements Comparable<ControlsProfile> {
    public static final int EDITOR_VERSION = 2;
    public static final int SCHEMA_VERSION = 2;
    public static final int MIN_EDITOR_VERSION = EDITOR_VERSION;

    public final int id;
    private String name;
    private float cursorSpeed = 1.0f;
    // Per-profile accent override for the on-screen touch controls. When customAccentEnabled is
    // false (default) the controls follow the app theme accent (AppThemeState); when true they use
    // customAccentColor. Default color = the app default blue, only consulted once the user opts in.
    private boolean customAccentEnabled = false;
    private int customAccentColor = 0xFF0055FF;
    private final ArrayList<ControlElement> elements = new ArrayList<>();
    private final ArrayList<ExternalController> controllers = new ArrayList<>();
    private final Map<String, GroupInfo> groups = new LinkedHashMap<>();
    private final List<ControlElement> immutableElements = Collections.unmodifiableList(elements);
    private final List<ExternalController> immutableControllers = Collections.unmodifiableList(controllers);
    private boolean elementsLoaded = false;
    private boolean controllersLoaded = false;
    private boolean groupsLoaded = false;
    private boolean virtualGamepad = false;
    private final ArrayList<Object> elementOrder = new ArrayList<>();
    private final Context context;
    private GamepadState gamepadState;

    public static class GroupInfo {
        private final String name;
        private boolean visible = true;

        public GroupInfo(String name, boolean visible) {
            this.name = name;
            this.visible = visible;
        }

        public String getName() {
            return name;
        }

        public boolean isVisible() {
            return visible;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
        }
    }

    public ControlsProfile(Context context, int id) {
        this.context = context;
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float getCursorSpeed() {
        return cursorSpeed;
    }

    public void setCursorSpeed(float cursorSpeed) {
        this.cursorSpeed = Float.isFinite(cursorSpeed) ? Math.max(0.1f, Math.min(5.0f, cursorSpeed)) : 1.0f;
    }

    public boolean isCustomAccentEnabled() {
        return customAccentEnabled;
    }

    public void setCustomAccentEnabled(boolean customAccentEnabled) {
        this.customAccentEnabled = customAccentEnabled;
    }

    public int getCustomAccentColor() {
        return customAccentColor;
    }

    public void setCustomAccentColor(int customAccentColor) {
        this.customAccentColor = customAccentColor;
    }

    public boolean isVirtualGamepad() {
        return virtualGamepad;
    }

    void updateVirtualGamepad() {
        virtualGamepad = false;
        for (ControlElement element : elements) {
            if (element.usesGamepadBinding()) {
                virtualGamepad = true;
                return;
            }
        }
    }

    public GamepadState getGamepadState() {
        if (gamepadState == null) gamepadState = new GamepadState();
        return gamepadState;
    }

    // #333: reserved id for the "Default / Any Controller" binding template — a bindings-only entry
    // (not tied to a real device) whose mappings newly-added controllers inherit, so a fresh controller
    // is never blank (the reporter's issue: a new pad came up with 0 bindings).
    public static final String DEFAULT_CONTROLLER_ID = "__default__";

    /** #333: the Default/Any-Controller template entry, created on demand. Editable via the bindings
     *  editor (controller_id = DEFAULT_CONTROLLER_ID) like any other controller. */
    public ExternalController getOrCreateDefaultController() {
        ExternalController c = getController(DEFAULT_CONTROLLER_ID);
        if (c == null) {
            c = new ExternalController();
            c.setId(DEFAULT_CONTROLLER_ID);
            c.setName("Default / Any Controller");
            controllers.add(c);
            controllersLoaded = true;
        }
        return c;
    }

    public ExternalController addController(String id) {
        ExternalController controller = getController(id);
        if (controller != null) { controllersLoaded = true; return controller; }
        // The Default/Any-Controller template is a bindings-only entry, not a real device lookup.
        if (DEFAULT_CONTROLLER_ID.equals(id)) return getOrCreateDefaultController();
        controller = ExternalController.getController(id);
        if (controller != null) {
            // #333: seed a brand-new controller from the Default/Any-Controller template so it inherits
            // the shared mappings instead of coming up empty.
            ExternalController template = getController(DEFAULT_CONTROLLER_ID);
            if (template != null && template.getControllerBindingCount() > 0) controller.copyBindingsFrom(template);
            controllers.add(controller);
        }
        controllersLoaded = true;
        return controller;
    }

    public void removeController(ExternalController controller) {
        if (!controllersLoaded) loadControllers();
        controllers.remove(controller);
    }

    public ExternalController getController(String id) {
        if (!controllersLoaded) loadControllers();
        for (ExternalController controller : controllers) if (controller.getId().equals(id)) return controller;
        return null;
    }

    public ExternalController getController(int deviceId) {
        if (!controllersLoaded) loadControllers();
        
        // First try direct deviceId match
        for (ExternalController controller : controllers) {
            if (controller.getDeviceId() == deviceId) return controller;
        }
        
        // If no match, try to find by descriptor
        android.view.InputDevice device = android.view.InputDevice.getDevice(deviceId);
        if (device != null) {
            String descriptor = device.getDescriptor();
            for (ExternalController controller : controllers) {
                if (controller.getId().equals(descriptor)) {
                    return controller;
                }
            }
            // #333 runtime auto-inherit: an unconfigured real controller inherits the Default/Any-
            // Controller template so it isn't blank in-game (the reporter's issue). Only when a
            // non-empty template exists, and only for real game controllers (isGameController rejects
            // uinput-fpc etc.). Created once — subsequent lookups match above. Not persisted here (the
            // seed re-applies each session; an explicit edit in the bindings editor saves it).
            if (descriptor != null && ExternalController.isGameController(device)) {
                ExternalController template = getController(DEFAULT_CONTROLLER_ID);
                if (template != null && template.getControllerBindingCount() > 0) {
                    ExternalController seeded = new ExternalController();
                    seeded.setId(descriptor);
                    seeded.setName(device.getName());
                    seeded.copyBindingsFrom(template);
                    controllers.add(seeded);
                    return seeded;
                }
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(ControlsProfile o) {
        return Integer.compare(id, o.id);
    }

    public boolean isElementsLoaded() {
        return elementsLoaded;
    }

    private void loadGroupsFromJSONObject(JSONObject profileJSONObject) throws JSONException {
        groups.clear();
        JSONArray groupsJSONArray = profileJSONObject.optJSONArray("groups");
        if (groupsJSONArray != null) {
            for (int i = 0; i < groupsJSONArray.length(); i++) {
                JSONObject groupJSONObject = groupsJSONArray.optJSONObject(i);
                if (groupJSONObject == null) continue;
                String name = groupJSONObject.optString("name", null);
                if (name == null) continue;
                name = name.trim();
                if (name.isEmpty()) continue;
                boolean visible = groupJSONObject.optBoolean("visible", true);
                groups.put(name, new GroupInfo(name, visible));
            }
        }
        groupsLoaded = true;
    }

    private void ensureGroupsLoaded() {
        if (groupsLoaded) return;

        File file = getProfileFile(context, id);
        if (!file.isFile()) {
            groupsLoaded = true;
            return;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(InputControlsManager.readStringAtomically(file));
            loadGroupsFromJSONObject(profileJSONObject);
        }
        catch (JSONException | IOException e) {
            groups.clear();
            groupsLoaded = true;
        }
    }

    private static Integer readScaledDimension(
            JSONObject elementJSONObject,
            String ratioKey,
            String legacyPixelKey,
            int referenceSize) throws JSONException {
        if (elementJSONObject.has(ratioKey)) {
            double ratio = elementJSONObject.getDouble(ratioKey);
            if (!Double.isFinite(ratio) || ratio <= 0) throw new JSONException("Invalid " + ratioKey);
            double scaled = ratio * Math.max(1, referenceSize);
            if (scaled > Integer.MAX_VALUE) throw new JSONException("Out-of-range " + ratioKey);
            return (int)Math.round(scaled);
        }
        if (elementJSONObject.has(legacyPixelKey)) return elementJSONObject.getInt(legacyPixelKey);
        return null;
    }

    public GroupInfo getGroup(String name) {
        if (name == null) return null;
        name = name.trim();
        if (name.isEmpty()) return null;
        ensureGroupsLoaded();
        return groups.get(name);
    }

    public Map<String, GroupInfo> getGroups() {
        ensureGroupsLoaded();
        return groups;
    }

    public GroupInfo addGroup(String name) {
        if (name == null) return null;
        String trimmedName = name.trim();
        if (trimmedName.isEmpty()) return null;
        ensureGroupsLoaded();
        GroupInfo group = groups.get(trimmedName);
        if (group == null) {
            group = new GroupInfo(trimmedName, true);
            groups.put(trimmedName, group);
        }
        return group;
    }

    public void setGroupVisible(String name, boolean visible) {
        GroupInfo group = getGroup(name);
        if (group != null) group.setVisible(visible);
    }

    public boolean isGroupVisible(String name) {
        GroupInfo group = getGroup(name);
        return group == null || group.isVisible();
    }

    public List<ControlElement> getGroupElements(String groupId) {
        if (groupId == null) return new ArrayList<>();
        groupId = groupId.trim();
        if (groupId.isEmpty()) return new ArrayList<>();
        ArrayList<ControlElement> groupElements = new ArrayList<>();
        for (ControlElement element : elements) {
            if (groupId.equals(element.getGroupId())) groupElements.add(element);
        }
        return groupElements;
    }

    public int getGroupElementCount(String groupId) {
        return getGroupElements(groupId).size();
    }

    public boolean save() {
        File file = getProfileFile(context, id);

        try {
            JSONObject data = file.isFile()
                    ? new JSONObject(InputControlsManager.readStringAtomically(file))
                    : new JSONObject();
            data.put("schemaVersion", SCHEMA_VERSION);
            data.put("minEditorVersion", MIN_EDITOR_VERSION);
            data.put("id", id);
            data.put("name", name);
            data.put("cursorSpeed", Float.valueOf(cursorSpeed));
            // Lightweight header fields (sit alongside cursorSpeed, before the heavy elements array)
            // so the streaming loader in InputControlsManager can read them without parsing elements.
            data.put("customAccentEnabled", customAccentEnabled);
            data.put("customAccentColor", customAccentColor);

            JSONArray groupsJSONArray = new JSONArray();
            if (!groupsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(InputControlsManager.readStringAtomically(file));
                JSONArray existingGroups = profileJSONObject.optJSONArray("groups");
                if (existingGroups != null) groupsJSONArray = existingGroups;
            }
            else {
                for (GroupInfo group : groups.values()) {
                    JSONObject groupJSONObject = new JSONObject();
                    groupJSONObject.put("name", group.getName());
                    groupJSONObject.put("visible", group.isVisible());
                    groupsJSONArray.put(groupJSONObject);
                }
            }
            data.put("groups", groupsJSONArray);

            JSONArray elementsJSONArray = new JSONArray();
            if (!elementsLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(InputControlsManager.readStringAtomically(file));
                // Preserve the on-disk elements when they were never loaded into memory,
                // but tolerate a profile that has no (or a malformed) elements array
                // otherwise the whole save() throws and is silently swallowed below,
                // discarding edits made elsewhere (e.g. controller bindings).
                JSONArray existingElements = profileJSONObject.optJSONArray("elements");
                if (existingElements != null) elementsJSONArray = existingElements;
            }
            else {
                ArrayList<ControlElement> remainingElements = new ArrayList<>(elements);
                for (Object entry : elementOrder) {
                    if (entry instanceof ControlElement) {
                        ControlElement element = (ControlElement)entry;
                        if (remainingElements.remove(element)) {
                            JSONObject serializedElement = element.toJSONObject();
                            if (serializedElement == null) return false;
                            elementsJSONArray.put(serializedElement);
                        }
                    } else {
                        elementsJSONArray.put(entry);
                    }
                }
                for (ControlElement element : remainingElements) {
                    JSONObject serializedElement = element.toJSONObject();
                    if (serializedElement == null) return false;
                    elementsJSONArray.put(serializedElement);
                }
            }
            data.put("elements", elementsJSONArray);

            JSONArray controllersJSONArray = new JSONArray();
            if (!controllersLoaded && file.isFile()) {
                JSONObject profileJSONObject = new JSONObject(InputControlsManager.readStringAtomically(file));
                if (profileJSONObject.has("controllers")) controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            }
            else {
                for (ExternalController controller : controllers) {
                    JSONObject controllerJSONObject = controller.toJSONObject();
                    if (controllerJSONObject != null) controllersJSONArray.put(controllerJSONObject);
                }
            }
            if (controllersJSONArray.length() > 0) data.put("controllers", controllersJSONArray);
            else data.remove("controllers");

            InputControlsManager.truncateProfileCombos(data);
            return InputControlsManager.writeStringAtomically(file, data.toString());
        }
        catch (JSONException | IOException e) {
            return false;
        }
    }

    public static File getProfileFile(Context context, int id) {
        return new File(InputControlsManager.getProfilesDir(context), "controls-"+id+".icp");
    }

    public void addElement(ControlElement element) {
        elements.add(element);
        elementsLoaded = true;
        updateVirtualGamepad();
    }

    public void removeElement(ControlElement element) {
        elements.remove(element);
        elementsLoaded = true;
        updateVirtualGamepad();
    }

    public List<ControlElement> getElements() {
        return immutableElements;
    }

    public boolean isTemplate() {
        return name.toLowerCase(Locale.ENGLISH).contains("template");
    }

    public ArrayList<ExternalController> loadControllers() {
        controllers.clear();
        controllersLoaded = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) {
            controllersLoaded = true;
            return controllers;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(InputControlsManager.readStringAtomically(file));
            if (!profileJSONObject.has("controllers")) {
                controllersLoaded = true;
                return controllers;
            }
            JSONArray controllersJSONArray = profileJSONObject.getJSONArray("controllers");
            for (int i = 0; i < controllersJSONArray.length(); i++) {
                // Skip a single malformed controller instead of aborting the whole load.
                try {
                    JSONObject controllerJSONObject = controllersJSONArray.getJSONObject(i);
                    String id = controllerJSONObject.getString("id");
                    ExternalController controller = new ExternalController();
                    controller.setId(id);
                    controller.setName(controllerJSONObject.getString("name"));

                    JSONArray controllerBindingsJSONArray = controllerJSONObject.getJSONArray("controllerBindings");
                    for (int j = 0; j < controllerBindingsJSONArray.length(); j++) {
                        JSONObject controllerBindingJSONObject = controllerBindingsJSONArray.getJSONObject(j);
                        ExternalControllerBinding controllerBinding = new ExternalControllerBinding();
                        controllerBinding.setKeyCode(controllerBindingJSONObject.getInt("keyCode"));
                        String serializedBindingName = controllerBindingJSONObject.getString("binding");
                        controllerBinding.setLoadedBinding(
                                Binding.fromString(serializedBindingName), serializedBindingName);
                        controller.addControllerBinding(controllerBinding);
                    }
                    controllers.add(controller);
                }
                catch (JSONException | IllegalArgumentException e) {
                    e.printStackTrace();
                }
            }
        }
        catch (JSONException | IOException e) {
            e.printStackTrace();
        }
        controllersLoaded = true;
        return controllers;
    }

    public List<ExternalController> getControllers() {
        if (!controllersLoaded) loadControllers();
        return immutableControllers;
    }

    public void loadElements(InputControlsView inputControlsView) {
        elements.clear();
        elementOrder.clear();
        elementsLoaded = false;
        virtualGamepad = false;

        File file = getProfileFile(context, id);
        if (!file.isFile()) {
            elementsLoaded = true;
            return;
        }

        try {
            JSONObject profileJSONObject = new JSONObject(InputControlsManager.readStringAtomically(file));
            loadGroupsFromJSONObject(profileJSONObject);
            JSONArray elementsJSONArray = profileJSONObject.optJSONArray("elements");
            if (elementsJSONArray == null) {
                elementsLoaded = true;
                return;
            }
            for (int i = 0; i < elementsJSONArray.length(); i++) {
                // Skip a single malformed element (unknown type/shape/range from a fork's
                // profile, missing keys, etc.) instead of aborting the whole load.
                try {
                    JSONObject elementJSONObject = elementsJSONArray.getJSONObject(i);
                    ControlElement element = new ControlElement(inputControlsView);
                    element.setType(ControlElement.Type.valueOf(elementJSONObject.getString("type")));
                    element.setShape(ControlElement.Shape.valueOf(elementJSONObject.getString("shape")));
                    element.setToggleSwitch(elementJSONObject.getBoolean("toggleSwitch"));
                    element.setX((int)(elementJSONObject.getDouble("x") * inputControlsView.getMaxWidth()));
                    element.setY((int)(elementJSONObject.getDouble("y") * inputControlsView.getMaxHeight()));
                    element.setScale((float)elementJSONObject.getDouble("scale"));
                    element.setText(elementJSONObject.getString("text"));
                    element.setIconId(elementJSONObject.getInt("iconId"));
                    element.loadCustomIconOptions(elementJSONObject);
                    if (elementJSONObject.has("range")) element.setRange(ControlElement.Range.valueOf(elementJSONObject.getString("range")));
                    if (elementJSONObject.has("orientation")) element.setOrientation((byte)elementJSONObject.getInt("orientation"));

                    // Load new fields for extended types (backward compatible)
                    if (elementJSONObject.has("deadZone")) element.setDeadZone((float)elementJSONObject.getDouble("deadZone"));
                    if (elementJSONObject.has("groupId")) {
                        element.setGroupId(elementJSONObject.optString("groupId", null));
                        if (element.getGroupId() != null && getGroup(element.getGroupId()) == null) addGroup(element.getGroupId());
                    }
                    Integer areaWidth = readScaledDimension(
                        elementJSONObject, "areaWidthRatio", "areaWidth", inputControlsView.getMaxWidth());
                    if (areaWidth != null) element.setAreaWidth(areaWidth);
                    Integer areaHeight = readScaledDimension(
                        elementJSONObject, "areaHeightRatio", "areaHeight", inputControlsView.getMaxHeight());
                    if (areaHeight != null) element.setAreaHeight(areaHeight);
                    Integer stickRadius = readScaledDimension(
                        elementJSONObject,
                        "stickRadiusRatio",
                        "stickRadius",
                        Math.min(inputControlsView.getMaxWidth(), inputControlsView.getMaxHeight()));
                    if (stickRadius != null) element.setStickRadius(stickRadius);
                    if (elementJSONObject.has("mouseSensitivity")) element.setMouseSensitivity((float)elementJSONObject.getDouble("mouseSensitivity"));
                    if (elementJSONObject.has("customAreaColor")) element.setCustomAreaColor(elementJSONObject.getInt("customAreaColor"));
                    if (elementJSONObject.has("customAreaOpacity")) element.setCustomAreaOpacity((float)elementJSONObject.getDouble("customAreaOpacity"));
                    if (elementJSONObject.has("customAreaAppearanceEnabled")) {
                        element.setCustomAreaAppearanceEnabled(elementJSONObject.getBoolean("customAreaAppearanceEnabled"));
                    }
                    if (elementJSONObject.has("gridRows")) element.setGridRows(elementJSONObject.getInt("gridRows"));
                    if (elementJSONObject.has("gridCols")) element.setGridCols(elementJSONObject.getInt("gridCols"));
                    if (elementJSONObject.has("gridSpacing")) element.setGridSpacing((float)elementJSONObject.getDouble("gridSpacing"));
                    if (elementJSONObject.has("gridMultitouchEnabled")) {
                        element.setGridMultitouchEnabled(elementJSONObject.getBoolean("gridMultitouchEnabled"));
                    }
                    if (element.getType() == ControlElement.Type.EXPANDABLE_BUTTON) {
                        element.setExpandableChildCount(elementJSONObject.optInt("expandableChildCount", 4));
                        if (elementJSONObject.has("expandableLayout")) {
                            try {
                                element.setExpandableLayout(ControlElement.ExpandableLayout.valueOf(
                                        elementJSONObject.getString("expandableLayout")));
                            }
                            catch (IllegalArgumentException ignored) {}
                        }
                        if (elementJSONObject.has("expandableDirection")) {
                            try {
                                element.setExpandableDirection(ControlElement.ExpandableDirection.valueOf(
                                        elementJSONObject.getString("expandableDirection")));
                            }
                            catch (IllegalArgumentException ignored) {}
                        }
                    }
                    if (elementJSONObject.has("gridCellShape")) {
                        try {
                            element.setGridCellShape(ControlElement.Shape.valueOf(elementJSONObject.getString("gridCellShape")));
                        }
                        catch (IllegalArgumentException e) {
                            element.setGridCellShape(ControlElement.Shape.ROUND_RECT);
                        }
                    }
                    if (element.getType() == ControlElement.Type.BUTTON_GRID) {
                        int rows = element.getGridRows() > 0 ? element.getGridRows() : 2;
                        int cols = element.getGridCols() > 0 ? element.getGridCols() : 8;
                        element.setBindingCount(rows * cols);
                        element.setBinding(Binding.NONE);
                    }

                    boolean elementUsesGamepad = false;
                    JSONArray bindingsJSONArray = elementJSONObject.optJSONArray("bindings");
                    if (bindingsJSONArray != null) {
                        int bindingLimit = element.getType() == ControlElement.Type.BUTTON_GRID
                                || element.getType() == ControlElement.Type.EXPANDABLE_BUTTON
                            ? Math.min(bindingsJSONArray.length(), element.getBindingCount())
                            : bindingsJSONArray.length();
                        for (int j = 0; j < bindingLimit; j++) {
                            Binding binding = Binding.fromString(bindingsJSONArray.optString(j, null));
                            element.setBindingAt(j, binding);
                            if (binding.isGamepad()) elementUsesGamepad = true;
                        }
                    }
                    JSONArray blockTouchscreenMouseButtonsJSONArray =
                            elementJSONObject.optJSONArray("blockTouchscreenMouseButtons");
                    if (blockTouchscreenMouseButtonsJSONArray != null) {
                        int priorityLimit = Math.min(blockTouchscreenMouseButtonsJSONArray.length(),
                                element.getBindingCount());
                        for (int j = 0; j < priorityLimit; j++) {
                            element.setBlocksTouchscreenMouseButtonsAt(j,
                                    blockTouchscreenMouseButtonsJSONArray.optBoolean(j, true));
                        }
                    }

                    // Load combos if present
                    JSONArray combosArr = elementJSONObject.optJSONArray("combos");
                    if (combosArr != null) {
                        for (int j = 0; j < combosArr.length(); j++) {
                            try {
                                JSONArray entry = combosArr.getJSONArray(j);
                                if (entry.length() < 2) continue;

                                int idx = entry.getInt(0);
                                if (idx < 0 || idx >= element.getBindingCount()) continue;

                                JSONArray keys = entry.optJSONArray(1);
                                if (keys == null || keys.length() == 0) continue;

                                ArrayList<Binding> combo = new ArrayList<>();
                                ArrayList<String> rawNames = new ArrayList<>();
                                int comboBindingLimit = Math.min(keys.length(), ControlElement.MAX_COMBO_BINDINGS);
                                for (int k = 0; k < comboBindingLimit; k++) {
                                    String rawName = keys.optString(k, null);
                                    if (rawName == null) continue;
                                    rawNames.add(rawName);
                                    Binding binding = Binding.fromString(rawName);
                                    if (binding != Binding.NONE) combo.add(binding);
                                    if (binding.isGamepad()) elementUsesGamepad = true;
                                }
                                if (!rawNames.isEmpty()) element.setLoadedCombo(
                                        idx,
                                        combo.toArray(new Binding[0]),
                                        rawNames.toArray(new String[0]));
                            }
                            catch (JSONException | IllegalArgumentException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    // Load hold key if present
                    if (elementJSONObject.has("holdKey")) {
                        try {
                            element.setHoldKey(Binding.fromString(elementJSONObject.getString("holdKey")));
                        }
                        catch (IllegalArgumentException e) {
                            element.setHoldKey(Binding.NONE);
                        }
                    }

                    if (!virtualGamepad && elementUsesGamepad) virtualGamepad = true;
                    element.setSourceJSONObject(elementJSONObject);
                    elements.add(element);
                    elementOrder.add(element);
                }
                catch (JSONException | IllegalArgumentException e) {
                    Object unknownElement = elementsJSONArray.opt(i);
                    if (unknownElement != null) elementOrder.add(unknownElement);
                    e.printStackTrace();
                }
            }
            elementsLoaded = true;
        }
        catch (JSONException | IOException e) {
            e.printStackTrace();
        }
    }
}
