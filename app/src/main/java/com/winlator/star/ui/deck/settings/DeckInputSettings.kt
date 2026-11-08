package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.winlator.star.container.Container
import com.winlator.star.ui.components.buildPlayerSlotEditorRows
import com.winlator.star.winhandler.WinHandler
import kotlinx.coroutines.CoroutineScope

// The Controls page's player slot pins and the XR controller mapping, as the classic editors offer
// them. Player slots are one JSON object ("controllerSlotOverrides", descriptor -> slot), on the
// container and per game as a shortcut extra; it is read and written only through
// WinHandler.parseSlotOverridesJson / buildSlotOverridesJson, as the classic PlayerSlotsEditor and the
// in-game Players tab do. A game with no extra follows its container's pins; changing a pin gives the
// game its own object, and Reset removes it again. The XR mapping is the container's primary
// controller and ten button keycodes (the classic XR section), container and All containers only.

internal object InputRows {

    /** The whole slot-pins JSON, never shown: the section below reads and writes it. */
    const val ID_SLOTS = "input.slotOverrides"

    private const val SLOTS_EXTRA = "controllerSlotOverrides"

    // The classic XR section's labels, in Container.XrControllerMapping order.
    private val XR_LABELS = listOf(
        L("Button A"), L("Button B"), L("Button X"), L("Button Y"),
        L("Grip"), L("Trigger"),
        L("Thumbstick up"), L("Thumbstick down"), L("Thumbstick left"), L("Thumbstick right"),
    )

    fun defs(): List<SettingDef> = listOf(
        hiddenDef(
            ID_SLOTS, "input",
            vm = VmBinding(get = { _, vm -> vm.controllerSlotOverridesJson }, set = { _, vm, v -> vm.controllerSlotOverridesJson = v }),
            // Absent or empty: the game follows the container's pins (the launch reads the extra first).
            game = GameBinding(
                keys = listOf(SLOTS_EXTRA),
                read = { e -> e.get(SLOTS_EXTRA)?.takeIf { it.isNotEmpty() } },
                write = { e, v -> e.put(SLOTS_EXTRA, v.ifEmpty { null }) },
            ),
            worlds = WINE_LINUX,
        ),
        SettingDef(
            id = "input.xr.primary", category = "input", group = L("XR controller"), level = SettingLevel.EXPERT,
            label = L("Primary XR controller"), hint = L("The hand whose controller drives the mouse in XR."), tech = L("Primary controller"),
            control = { env, _ ->
                Control.Segmented((env.vm?.primaryControllerEntries ?: emptyList()).mapIndexed { i, name -> Opt("$i", name) })
            },
            vm = VmBinding(
                get = { _, vm -> vm.selectedPrimaryController.toString() },
                set = { _, vm, v -> v.toIntOrNull()?.let { vm.selectedPrimaryController = it } },
            ),
            showInGame = false,
        ),
    ) + XR_LABELS.take(Container.XrControllerMapping.values().size).mapIndexed { i, label -> xrRow(i, label) }

    /** One XR button's key: the index into the keycode list the classic dropdown shows (XKeycode order). */
    private fun xrRow(i: Int, label: String) = SettingDef(
        id = "input.xr.map$i", category = "input", group = L("XR controller"), level = SettingLevel.EXPERT,
        label = label, tech = "Controller Mapping › ${Container.XrControllerMapping.values()[i].name}",
        control = { env, _ -> Control.Select((env.vm?.xrKeycodeNames ?: emptyList()).mapIndexed { k, name -> Opt("$k", name) }) },
        vm = VmBinding(
            get = { _, vm -> (vm.xrMappingIndices.getOrElse(i) { 0 }).toString() },
            set = { _, vm, v -> v.toIntOrNull()?.let { if (i < vm.xrMappingIndices.size) vm.xrMappingIndices[i] = it } },
        ),
        showInGame = false,
    )
}

/**
 * The player slot pins: the on-screen pad, every connected controller and every saved pin of a
 * controller that is switched off, each with Auto / Player 1-4 / Ignore (the classic
 * PlayerSlotsEditor's list). A game, a container and All containers; the Linux Steam client entry
 * too, never an Android game.
 */
internal class DeckInputSection(val env: SettingsEnv, val source: SettingsSource, private val scope: CoroutineScope) : DeckSection {
    private val access = DefAccess(source)
    // Bumped to list the connected controllers again (Refresh, or coming back from another screen).
    private var refresh by mutableIntStateOf(0)

    override fun extras(): Map<String, CategoryExtra> =
        if (access.def(InputRows.ID_SLOTS) == null) emptyMap()
        else mapOf("input" to CategoryExtra(bottom = this::slotItems))

    @Composable
    override fun Dialogs() {}

    override fun onReturn() { refresh++ }

    private fun slotItems(): List<PageExtra> {
        val def = access.def(InputRows.ID_SLOTS) ?: return emptyList()
        refresh
        val json = access.value(InputRows.ID_SLOTS)
        val rows = buildPlayerSlotEditorRows(json.ifEmpty { "{}" })
        val editable = source.editable(def)
        val own = source.origin(def) == Origin.SET_HERE
        val game = env.scope == EditorScope.GAME
        val info = when {
            game && !own -> L("Following the container's pins. Changing one gives this game pins of its own.")
            game -> L("This game has pins of its own.")
            own && env.scope == EditorScope.CONTAINER -> L("This container's own pins, not All containers'.")
            else -> L("Applied at launch. The in-game side menu still changes them live.")
        }
        val items = mutableListOf(PageExtra("slots-label") { GroupLabel(L("Player slot pins")) })
        items += PageExtra("slots-info") {
            GroupCard(first = true, last = false, highlight = false) {
                DeckLine(label = L("Pin controllers to players"), hint = "Two on one player share it. $info") {
                    if (own && editable) {
                        DeckSmallButton(if (game) L("Use the container's") else L("Use All containers'"), Icons.Filled.Restore) { source.reset(def) }
                    }
                    DeckSmallButton("", Icons.Filled.Refresh) { refresh++ }
                }
            }
        }
        rows.forEachIndexed { i, row ->
            items += PageExtra("slots:${row.descriptor}") {
                GroupCard(first = false, last = i == rows.lastIndex, highlight = false) {
                    val status = when {
                        row.override == WinHandler.SLOT_IGNORE -> L("Ignored")
                        row.override >= 0 -> "Player ${row.override + 1}"
                        else -> L("Auto (by connection order)")
                    }
                    val where = when {
                        row.isOnScreen -> L(" · the on-screen controls")
                        !row.connected -> L(" · not connected (saved pin)")
                        else -> ""
                    }
                    DeckLine(label = row.displayName, hint = status + where) {
                        SlotPicker(row.override, editable) { slot ->
                            val map = WinHandler.parseSlotOverridesJson(json.ifEmpty { "{}" }).toMutableMap()
                            if (slot == SLOT_AUTO) map.remove(row.descriptor) else map[row.descriptor] = slot
                            access.set(InputRows.ID_SLOTS, WinHandler.buildSlotOverridesJson(map))
                        }
                    }
                }
            }
        }
        return items
    }

    companion object {
        private const val SLOT_AUTO = -1
        private val SLOT_OPTIONS: List<Pair<String, Int>> =
            listOf("Auto" to SLOT_AUTO) + (0 until 4).map { "Player ${it + 1}" to it } + ("Ignore" to WinHandler.SLOT_IGNORE)
    }

    /** Auto / Player 1-4 / Ignore for one device. */
    @Composable
    private fun SlotPicker(current: Int, enabled: Boolean, onPick: (Int) -> Unit) {
        var open by remember { mutableStateOf(false) }
        val label = SLOT_OPTIONS.firstOrNull { it.second == current }?.first ?: L("Auto")
        Box {
            DeckSmallButton(label, Icons.Filled.UnfoldMore, enabled) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SLOT_OPTIONS.forEach { (name, slot) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = {
                            if (slot == current) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            else Spacer(Modifier.size(24.dp))
                        },
                        onClick = {
                            open = false
                            if (slot != current) onPick(slot)
                        },
                    )
                }
            }
        }
    }
}
