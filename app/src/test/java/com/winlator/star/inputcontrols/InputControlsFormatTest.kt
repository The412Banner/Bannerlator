package com.winlator.star.inputcontrols

import com.winlator.star.widget.InputControlsView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputControlsFormatTest {
    @Test
    fun androidKeyboardBinding_isAppendedAfterGamepadBindings() {
        assertEquals(Binding.values().last(), Binding.SHOW_ANDROID_KEYBOARD)
        assertTrue(Binding.SHOW_ANDROID_KEYBOARD.ordinal > Binding.GAMEPAD_DPAD_LEFT.ordinal)
        assertEquals("ANDROID KEYBOARD", Binding.SHOW_ANDROID_KEYBOARD.toString())
    }

    @Test
    fun remapIconIds_changesOnlyMappedCustomIcons() {
        val first = JSONObject().put("iconId", 100).put("forkField", "keep")
        val second = JSONObject().put("iconId", 5)
        val root = JSONObject()
            .put("vendorExtension", true)
            .put("elements", JSONArray().put(first).put(second))

        InputControlsManager.remapIconIds(root, mapOf(100 to 128))

        assertEquals(128, first.getInt("iconId"))
        assertEquals("keep", first.getString("forkField"))
        assertEquals(5, second.getInt("iconId"))
        assertTrue(root.getBoolean("vendorExtension"))
        assertFalse(root.has("customIcons"))
    }

    @Test
    fun serialization_clearsKnownOptionalFieldsButKeepsForkFields() {
        val source = JSONObject()
            .put("groupId", "old-group")
            .put("combos", JSONArray().put(JSONArray().put(0)))
            .put("holdKey", "KEY_W")
            .put("gridCellShape", "CIRCLE")
            .put("expandableChildCount", 8)
            .put("expandableLayout", "LIST")
            .put("expandableDirection", "RIGHT")
            .put("customAreaAppearanceEnabled", true)
            .put("customAreaColor", 0xFF112233.toInt())
            .put("customAreaOpacity", 0.5)
            .put("gridSpacing", 0.5)
            .put("blockTouchscreenMouseButtons", JSONArray().put(false))
            .put("forkField", "keep")

        val copy = ControlElement.copyForSerialization(source)

        assertFalse(copy.has("groupId"))
        assertFalse(copy.has("combos"))
        assertFalse(copy.has("holdKey"))
        assertFalse(copy.has("gridCellShape"))
        assertFalse(copy.has("expandableChildCount"))
        assertFalse(copy.has("expandableLayout"))
        assertFalse(copy.has("expandableDirection"))
        assertFalse(copy.has("customAreaAppearanceEnabled"))
        assertFalse(copy.has("customAreaColor"))
        assertFalse(copy.has("customAreaOpacity"))
        assertFalse(copy.has("gridSpacing"))
        assertFalse(copy.has("blockTouchscreenMouseButtons"))
        assertEquals("keep", copy.getString("forkField"))
    }

    @Test
    fun transportFormat_acceptsLegacyAndCurrentIcpxOnly() {
        assertTrue(InputControlsManager.isSupportedTransportFormat(JSONObject()))
        assertTrue(InputControlsManager.isSupportedTransportFormat(JSONObject()
            .put("format", InputControlsManager.ICPX_FORMAT)
            .put("formatVersion", InputControlsManager.ICPX_FORMAT_VERSION)
            .put("minReaderVersion", InputControlsManager.ICPX_MIN_READER_VERSION)))
        assertFalse(InputControlsManager.isSupportedTransportFormat(JSONObject()
            .put("format", "another-fork.icpx")
            .put("formatVersion", 1)
            .put("minReaderVersion", 1)))
        assertTrue(InputControlsManager.isSupportedTransportFormat(JSONObject()
            .put("format", InputControlsManager.ICPX_FORMAT)
            .put("formatVersion", InputControlsManager.ICPX_FORMAT_VERSION + 1)
            .put("minReaderVersion", 1)))
        assertFalse(InputControlsManager.isSupportedTransportFormat(JSONObject()
            .put("format", InputControlsManager.ICPX_FORMAT)
            .put("formatVersion", InputControlsManager.ICPX_FORMAT_VERSION + 1)
            .put("minReaderVersion", InputControlsManager.ICPX_FORMAT_VERSION + 1)))
        assertFalse(InputControlsManager.isSupportedTransportFormat(JSONObject()
            .put("format", InputControlsManager.ICPX_FORMAT)
            .put("formatVersion", 1.5)
            .put("minReaderVersion", 1)))
        assertFalse(InputControlsManager.isSupportedTransportFormat(JSONObject()
            .put("format", InputControlsManager.ICPX_FORMAT)
            .put("formatVersion", "1")
            .put("minReaderVersion", 1)))
    }

    @Test
    fun transportHeader_identifiesVersionedIcpxFormat() {
        val root = JSONObject().put("name", "Portable profile")

        InputControlsManager.addTransportHeader(root)

        assertEquals(InputControlsManager.ICPX_FORMAT, root.getString("format"))
        assertEquals(InputControlsManager.ICPX_FORMAT_VERSION, root.getInt("formatVersion"))
        assertEquals(InputControlsManager.ICPX_MIN_READER_VERSION, root.getInt("minReaderVersion"))
        assertEquals("Portable profile", root.getString("name"))
    }

    @Test
    fun gamepadReset_neutralizesEveryField() {
        val state = GamepadState().apply {
            thumbLX = 1f
            thumbLY = -1f
            thumbRX = 0.5f
            thumbRY = -0.5f
            triggerL = 1f
            triggerR = 1f
            setPressed(2, true)
            dpad[0] = true
        }

        state.reset()

        assertEquals(0f, state.thumbLX)
        assertEquals(0f, state.thumbLY)
        assertEquals(0f, state.thumbRX)
        assertEquals(0f, state.thumbRY)
        assertEquals(0f, state.triggerL)
        assertEquals(0f, state.triggerR)
        assertEquals(0, state.buttons.toInt())
        assertTrue(state.dpad.none { it })
    }

    @Test
    fun trackpadSensitivity_scalesDeltaAndIsPersistedForSupportedTypes() {
        assertEquals(3f, ControlElement.scaleTrackpadDelta(3f, 1f))
        assertEquals(6f, ControlElement.scaleTrackpadDelta(3f, 2f))
        assertEquals(1.5f, ControlElement.scaleTrackpadDelta(3f, 0.5f))
        assertTrue(ControlElement.usesMouseSensitivity(ControlElement.Type.TRACKPAD))
        assertTrue(ControlElement.usesMouseSensitivity(ControlElement.Type.MOUSE_AREA))
        assertFalse(ControlElement.usesMouseSensitivity(ControlElement.Type.STICK))
    }

    @Test
    fun expandableButton_childCountIsClampedToSupportedRange() {
        assertEquals(1, ControlElement.clampExpandableChildCount(0))
        assertEquals(4, ControlElement.clampExpandableChildCount(4))
        assertEquals(10, ControlElement.clampExpandableChildCount(11))
        assertEquals(ControlElement.Type.values().last(), ControlElement.Type.EXPANDABLE_BUTTON)
        assertEquals(4, ControlElement.calculateExpandableItemsPerLane(500f, 100f, 20f, 10))
        assertEquals(1, ControlElement.calculateExpandableItemsPerLane(50f, 100f, 20f, 10))
        assertEquals(3, ControlElement.calculateExpandableItemsPerLane(1000f, 100f, 20f, 3))
    }

    @Test
    fun legacySignedIconIds_areNormalizedWithoutChangingValidIds() {
        assertEquals(128, InputControlsManager.normalizeLegacyIconId(-128))
        assertEquals(200, InputControlsManager.normalizeLegacyIconId(-56))
        assertEquals(255, InputControlsManager.normalizeLegacyIconId(-1))
        assertEquals(99, InputControlsManager.normalizeLegacyIconId(99))

        val element = ControlElement(null)
        element.setIconId(-56)
        assertEquals(200, element.iconId.toInt())
    }

    @Test
    fun nonFiniteElementValues_fallBackToSafeDefaults() {
        val element = ControlElement(null)
        element.scale = Float.NaN
        element.mouseSensitivity = Float.POSITIVE_INFINITY
        element.deadZone = Float.NEGATIVE_INFINITY
        element.customAreaOpacity = Float.NaN

        assertEquals(1f, element.scale)
        assertEquals(1f, element.mouseSensitivity)
        assertEquals(ControlElement.STICK_DEAD_ZONE, element.deadZone)
        assertEquals(0.25f, element.customAreaOpacity)
    }

    @Test
    fun importedProfileValidation_rejectsMalformedAndNonFiniteElements() {
        val validElement = JSONObject()
            .put("type", "BUTTON")
            .put("shape", "CIRCLE")
            .put("toggleSwitch", false)
            .put("x", 0.5)
            .put("y", 0.5)
            .put("scale", 1.0)
            .put("text", "A")
            .put("iconId", 0)
            .put("bindings", JSONArray().put("KEY_A"))
        val profile = JSONObject().put("name", "Test").put("elements", JSONArray().put(validElement))

        assertTrue(InputControlsManager.isValidImportedProfile(profile))
        validElement.put("blockTouchscreenMouseButtons", JSONArray().put(true))
        assertTrue(InputControlsManager.isValidImportedProfile(profile))
        validElement.put("blockTouchscreenMouseButtons", JSONArray().put("true"))
        assertFalse(InputControlsManager.isValidImportedProfile(profile))
        validElement.remove("blockTouchscreenMouseButtons")
        validElement.put("gridSpacing", 0.5)
        assertTrue(InputControlsManager.isValidImportedProfile(profile))
        validElement.put("gridSpacing", "0.5")
        assertFalse(InputControlsManager.isValidImportedProfile(profile))
        validElement.remove("gridSpacing")
        validElement.put("scale", "NaN")
        assertFalse(InputControlsManager.isValidImportedProfile(profile))
        assertFalse(InputControlsManager.isValidImportedProfile(
            JSONObject().put("name", "Test").put("elements", "not-an-array")
        ))
    }

    @Test
    fun unknownBindingNames_surviveUntilTheSlotIsEdited() {
        assertFalse(Binding.isKnownSerializedName("FORK_TURBO"))
        assertTrue(Binding.isKnownSerializedName("KEY_A"))
        assertEquals("FORK_TURBO", ControlElement.getSerializedBindingName(Binding.NONE, "FORK_TURBO"))
        assertEquals("NONE", ControlElement.getSerializedBindingName(Binding.NONE, "NONE"))
        assertEquals("KEY_B", ControlElement.getSerializedBindingName(Binding.KEY_B, "FORK_TURBO"))
    }

    @Test
    fun unknownControllerBindingNames_surviveUntilEdited() {
        val binding = ExternalControllerBinding()
        binding.setKeyCode(42)
        binding.setLoadedBinding(Binding.NONE, "FORK_TURBO")

        assertEquals("FORK_TURBO", binding.toJSONObject().getString("binding"))
        binding.setBinding(Binding.KEY_B)
        assertEquals("KEY_B", binding.toJSONObject().getString("binding"))
    }

    @Test
    fun legacyExport_removesTransportOnlyWithoutMutatingCommands() {
        val data = JSONObject()
            .put("format", InputControlsManager.ICPX_FORMAT)
            .put("formatVersion", 1)
            .put("minReaderVersion", 1)
            .put("customIcons", JSONArray().put(JSONObject().put("id", 100)))
            .put("elements", JSONArray().put(JSONObject().put("type", "BUTTON_GRID")))

        InputControlsManager.prepareLegacyExport(data)

        assertFalse(data.has("format"))
        assertFalse(data.has("formatVersion"))
        assertFalse(data.has("minReaderVersion"))
        assertFalse(data.has("customIcons"))
        assertEquals("BUTTON_GRID", data.getJSONArray("elements").getJSONObject(0).getString("type"))
    }

    @Test
    fun importedProfileValidation_rejectsUnknownTypeAndShape() {
        val element = JSONObject()
            .put("type", "BUTON")
            .put("shape", "CIRCLE")
            .put("toggleSwitch", false)
            .put("x", 0.5)
            .put("y", 0.5)
            .put("scale", 1.0)
            .put("text", "A")
            .put("iconId", 0)
            .put("bindings", JSONArray().put("KEY_A"))
        val profile = JSONObject().put("name", "Test").put("elements", JSONArray().put(element))

        assertFalse(InputControlsManager.isValidImportedProfile(profile))
        element.put("type", "BUTTON").put("shape", "SQUAREISH")
        assertFalse(InputControlsManager.isValidImportedProfile(profile))
    }

    @Test
    fun importedProfileValidation_rejectsMalformedControllersAndNonPositiveRatios() {
        val malformedController = JSONObject()
            .put("id", "pad")
            .put("name", "Pad")
            .put("controllerBindings", JSONArray().put(JSONObject()
                .put("keyCode", 1.5)
                .put("binding", "KEY_A")))
        val profile = JSONObject()
            .put("name", "Test")
            .put("controllers", JSONArray().put(malformedController))

        assertFalse(InputControlsManager.isValidImportedProfile(profile))

        val element = JSONObject()
            .put("type", "MOUSE_AREA")
            .put("shape", "RECT")
            .put("toggleSwitch", false)
            .put("x", 0.5)
            .put("y", 0.5)
            .put("scale", 1.0)
            .put("text", "")
            .put("iconId", 0)
            .put("bindings", JSONArray().put("NONE"))
            .put("areaWidthRatio", 0)
        profile.remove("controllers")
        profile.put("elements", JSONArray().put(element))
        assertFalse(InputControlsManager.isValidImportedProfile(profile))
    }

    @Test
    fun customIconBounds_rejectInvalidOrOversizedImages() {
        assertTrue(CustomIconManager.hasValidIconBounds(2048, 2048))
        assertFalse(CustomIconManager.hasValidIconBounds(0, 128))
        assertFalse(CustomIconManager.hasValidIconBounds(2049, 1))
        assertFalse(CustomIconManager.hasValidIconBounds(2000, 2100))
    }

    @Test
    fun compactBindingLabels_keepUsefulGamepadAndNumpadText() {
        assertEquals("A", ControlElement.getCompactBindingLabel(Binding.GAMEPAD_BUTTON_A))
        assertEquals("L1", ControlElement.getCompactBindingLabel(Binding.GAMEPAD_BUTTON_L1))
        assertEquals("NP1", ControlElement.getCompactBindingLabel(Binding.KEY_KP_1))
    }

    @Test
    fun comboExecution_includesMainBindingWithoutChangingStoredExtras() {
        val element = ControlElement(null)
        element.setBindingAt(0, Binding.KEY_C)
        element.setCombo(0, arrayOf(Binding.KEY_CTRL_L))

        assertEquals(listOf(Binding.KEY_CTRL_L), element.getCombo(0)!!.toList())
        assertEquals(
            listOf(Binding.KEY_C, Binding.KEY_CTRL_L),
            element.getEffectiveBindingsForSlot(0).toList(),
        )
    }

    @Test
    fun touchscreenMousePriority_defaultsOnAndSurvivesBindingArrayResize() {
        val element = ControlElement(null)
        assertTrue(element.blocksTouchscreenMouseButtonsAt(0))

        element.setBlocksTouchscreenMouseButtonsAt(0, false)
        element.setBindingCount(6)

        assertFalse(element.blocksTouchscreenMouseButtonsAt(0))
        assertTrue(element.blocksTouchscreenMouseButtonsAt(5))
    }

    @Test
    fun gridSpacing_isClampedToSupportedRange() {
        val element = ControlElement(null)
        assertEquals(0f, element.gridSpacing)

        element.gridSpacing = 2f
        assertEquals(1f, element.gridSpacing)
        element.gridSpacing = Float.NaN
        assertEquals(0f, element.gridSpacing)
    }

    @Test
    fun gamepadUsage_reflectsBindingAndComboEdits() {
        val element = ControlElement(null)
        element.setBindingAt(0, Binding.KEY_C)
        assertFalse(element.usesGamepadBinding())

        element.setCombo(0, arrayOf(Binding.GAMEPAD_BUTTON_A))
        assertTrue(element.usesGamepadBinding())

        element.setCombo(0, null)
        assertFalse(element.usesGamepadBinding())
    }

    @Test
    fun duplicateAxisOutputs_stayActiveWhileAnyMappedAxisIsActive() {
        val states = mutableMapOf<Binding, Float>()

        InputControlsView.mergeAxisBindingState(states, Binding.GAMEPAD_BUTTON_A, true, 0.8f)
        InputControlsView.mergeAxisBindingState(states, Binding.GAMEPAD_BUTTON_A, false, 0f)

        assertEquals(0.8f, states[Binding.GAMEPAD_BUTTON_A])
    }

    @Test
    fun opposingMappedDirections_areCombinedByDestinationAxis() {
        val inputs = linkedMapOf(
            Binding.GAMEPAD_LEFT_THUMB_LEFT to 0f,
            Binding.GAMEPAD_LEFT_THUMB_RIGHT to 0.8f,
            Binding.GAMEPAD_LEFT_THUMB_UP to 0.6f,
            Binding.GAMEPAD_LEFT_THUMB_DOWN to 0f,
        )
        val state = GamepadState()

        InputControlsView.applyMappedGamepadState(state, inputs)

        assertEquals(0.8f, state.thumbLX)
        assertEquals(-0.6f, state.thumbLY)

        val reversed = inputs.entries.reversed().associateTo(linkedMapOf()) { it.toPair() }
        InputControlsView.applyMappedGamepadState(state, reversed)
        assertEquals(0.8f, state.thumbLX)
        assertEquals(-0.6f, state.thumbLY)
    }

    @Test
    fun simultaneousOpposingMappedDirections_cancelOut() {
        val state = GamepadState()
        InputControlsView.applyMappedGamepadState(
            state,
            mapOf(
                Binding.GAMEPAD_RIGHT_THUMB_LEFT to 0.75f,
                Binding.GAMEPAD_RIGHT_THUMB_RIGHT to 0.75f,
            ),
        )

        assertEquals(0f, state.thumbRX)
    }

    @Test
    fun opposingMouseDirections_areCombinedIndependentlyOfMapOrder() {
        val inputs = linkedMapOf(
            Binding.MOUSE_MOVE_LEFT to 0f,
            Binding.MOUSE_MOVE_RIGHT to 0.7f,
        )

        assertEquals(
            0.7f,
            InputControlsView.getMappedDirectionalAxis(
                inputs, Binding.MOUSE_MOVE_LEFT, Binding.MOUSE_MOVE_RIGHT,
            ),
        )
        assertEquals(
            0.7f,
            InputControlsView.getMappedDirectionalAxis(
                inputs.entries.reversed().associateTo(linkedMapOf()) { it.toPair() },
                Binding.MOUSE_MOVE_LEFT,
                Binding.MOUSE_MOVE_RIGHT,
            ),
        )
    }
}
