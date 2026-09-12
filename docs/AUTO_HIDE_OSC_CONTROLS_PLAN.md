# Auto-Hide On-Screen Controls + Smart Default Layout — Plan

**Issue:** [#333](https://github.com/The412Banner/Bannerlator/issues/333) — Auto-hide on-screen controls when a physical controller is connected.

**Status:** Planned, NOT built. Build off `main` AFTER the current in-flight session's work is merged. No native changes — app layer (Kotlin/Java) only.

**Builds on:** the 2.9.7 controller overhaul (descriptor-keyed hot-plug slots, capability filter, `ControllerAssignmentListener`, KEEP/YIELD/SHARE on-screen mode). This is the "visibility" companion to that work.

---

## Goal (plain terms)

Touch controls that just work: they show up when you need them and get out of the way when a real controller connects. Zero setup for a new user.

- New phone user: launch a game → a working touch controller appears instantly. Connect a Bluetooth/USB pad → it hides. Unplug → it comes back.
- Handheld user (AYANEO etc., controller always present): the touch pad correctly never shows.
- Power user: everything stays fully configurable; existing explicit choices are respected.

---

## The six changes

### 1. Ready-to-go touch controller out of the box (smart default layout)
Today the on-screen pad only appears if the user (a) enables it AND (b) selects a control profile — no profile means nothing shows. We add a fallback: when no user profile is assigned, fall back to the bundled **"Virtual Gamepad"** layout (`app/src/main/assets/inputcontrols/profiles/controls-3.icp`, full D-pad + A/B/X/Y + sticks → GAMEPAD_* bindings). So a fresh install has a working touch controller with no setup.

- Caveat: "Virtual Gamepad" is a *gamepad* layout — right for controller-style games. On KB/M games the user swaps to the RTS/keyboard template. It's a sensible starting default, not a lock-in.

### 2. Auto-hide when a controller takes the on-screen controls' slot (SLOT-AWARE)
On a controller taking the on-screen controls' player slot, hide the touch overlay; when no controller holds that slot anymore, restore it. Reacts only to *real* game controllers (reuses the 2.9.7 capability filter — AYANEO aux/media nodes, `uinput-fpc` fingerprint devices, etc. are ignored).

**Reporter refinement (#333, adopted):** do NOT hide on *any* controller connect. The on-screen controls ARE a player (our Players-tab model). If OSC = Player 1 and a pad is on Player 2, that's a legit 2-player setup → keep the overlay visible. Only hide when a physical controller occupies the **same slot the OSC holds**.

**🔒 LOCKED DISAMBIGUATION RULE (user decision 2026-08-09) — how "solo takeover" vs "intentional 2-player" is decided:**
The software CANNOT detect a "2-player game" or infer intent — a solo player grabbing a pad and a genuine second player joining are the identical event (a controller connected). The ONLY reliable intent signal is an **explicit user slot pin**. Therefore:
- **No explicit pin on the connecting controller (auto/default) → assume SOLO.** The connecting controller takes over the OSC's slot (YIELD it in) and the overlay hides. This is the common "I grabbed my controller" case.
- **Controller explicitly pinned (Players tab / editors) to a slot ≠ the OSC's slot → assume INTENTIONAL MULTIPLAYER.** Respect the pin; the pad stays on its own slot and the overlay stays visible.
- **Controller explicitly pinned to the OSC's slot →** hide (it's replacing the touch player, matching the reporter's "explicit Player 1 assignment" case).
- This intentionally reframes the reporter's KEEP-mode example: the 2-player-keep-visible case requires an explicit Player-2 pin rather than being inferred from KEEP auto-assignment — because an auto pad-on-P2 is indistinguishable from a solo user who happens to be in KEEP mode. Rejected alternative: tying keep/hide to the on-screen mode (KEEP=keep visible / YIELD=hide) — a solo user in KEEP mode would then never get auto-hide, which reads as broken.
- Implementation signal: an explicit pin = a `controllerSlotOverrides` entry for that device descriptor (`manualSlotOverrides` in `WinHandler`); absence = auto.

- Condition to check via `WinHandler.getPlayerSlotAssignments()`: after applying the rule above, hide when a row with `isGameController && currentSlot == <OSC's slot>` exists (a real pad now owns the touch player's slot); restore when none does.

### 3. Handhelds stay hidden (falls out for free)
A handheld's built-in pad is always connected, so with auto-hide on the touch overlay simply never shows there. No special-casing needed.

### 4. Respect the user's choices
- Touch controls deliberately turned OFF stay off — never force them on.
- If the user manually re-opens controls from the in-game drawer while a pad is connected, that sticks until the next plug event (act on transitions, not continuously).
- On disconnect, controls return only if the user's baseline had them shown.

### 5. A simple on/off setting in all three places
New toggle **"Hide on-screen controls when a controller connects"** (default: see decision below), added to:
- Per-game shortcut editor (`ShortcutsScreen.kt`, Input section, with "Use container default" to clear the override)
- Container editor (`ContainerDetailScreen.kt`, next to the On-screen mode dropdown ~:1819)
- Global default (`InputControlsScreen.kt` ~:666, via `GlobalControllerPrefs`, seeds new containers only)

Each with the existing "?" help pattern.

### 6. Resolve the SHARE conflict
The existing **SHARE** on-screen mode (touch + pad both drive one player) is contradictory with a hidden overlay. Decision: **auto-hide wins** — when auto-hide is on and a pad is connected, the overlay is hidden and SHARE is inert. Add a short note on the SHARE option rather than hard-blocking the combination.

### 7. Companion feature — "Default / Any Controller" bindings + copy between controllers (COUPLED, own sub-task)
**Why it's here:** the reporter (#333) showed that controller bindings are stored **per physical device** — a newly connected pad gets a fresh entry with **zero** bindings (their test: Controller 1 = 19 bindings, Controller 2 = 0). For games without native gamepad support (profile maps stick→WASD, A→Space, etc.), auto-hide could hide the overlay while the new controller has no working mappings → player is stuck. So this ships alongside auto-hide to keep the experience safe.

Two parts:
- **(7a) "Default / Any Controller" template.** A profile-level default binding set that any controller with no bindings of its own inherits. Two viable shapes:
  - *Seed-on-add:* when `ControlsProfile.addController(id)` first creates a new `ExternalController`, copy the default template's `controllerBindings` into it. Simple, explicit, editable afterward per device.
  - *Live fallback:* at binding-resolve time (`ExternalController.getControllerBinding(keyCode)`), if the device has no binding for that key, fall back to the default template. No copy, always in sync, but per-device edits are then "overrides." **Recommend seed-on-add** (matches the existing per-device model and the "then customize" workflow).
- **(7b) Copy bindings between controllers.** A UI action to copy one controller's `controllerBindings` list onto another. Directly answers the reporter's follow-up ("copy or export/import keyboard bindings between controllers").

**Grounding:** `ControlsProfile` holds `ArrayList<ExternalController> controllers` (each keyed by descriptor `id`, `ControlsProfile.java:37/127-141`); each `ExternalController` holds `ArrayList<ExternalControllerBinding> controllerBindings` (`ExternalController.java:31`, add/get/remove at :71-90); persisted as the `controllerBindings` JSONArray per controller (`ControlsProfile.java:422-430`). A "default" entry = a reserved sentinel id (e.g. `__default__`) in the same list, or a dedicated field on the profile.

**UI surfaces — BOTH (user decision 2026-08-09):**
- **(out-of-game, primary)** The existing **"External Controllers"** section in `InputControlsScreen.kt:495` — already lists each connected controller with its `getControllerBindingCount()` (`:502`) and opens the binding editor `ExternalControllerBindingsActivity` on tap (`:510`). Add here: a **"Default / Any Controller"** entry (edit the template) + a **"Copy bindings from…"** action per controller (pick a source controller → clone its map). This is where controller bindings already live, so it's the natural home.
- **(in-game, secondary)** Surface the same two actions in the in-game **Players sub-tab** (`XServerDrawer.kt`) — currently slot-assignment only, no binding editor. Add per-controller **"Edit bindings"** / **"Copy bindings"** entry points so a blank controller can be fixed without leaving the game. Bigger lift (in-game drawer has no binding editor today — either launch `ExternalControllerBindingsActivity` from in-game or add a compact in-drawer editor).

**Safety tie-in with auto-hide:** consider gating auto-hide's "hide" step on the incoming controller actually having usable bindings (own or inherited from the default) when the active profile is a keyboard-mapping profile — so a truly blank controller never strands the user. Decide at build.

---

## Technical grounding (verified against current tree)

| Need | Hook |
|---|---|
| Slot-aware "did a pad take the OSC's slot?" | `WinHandler.getPlayerSlotAssignments()` → `PlayerSlotInfo` rows (`isGameController`, `currentSlot`, `isOnScreen`); hide when a real-pad row now owns the OSC's slot |
| Connect/disconnect trigger | `winHandler.setControllerAssignmentListener(...)` — `XServerDisplayActivity.java:4465`, main looper, already debounced (`CONTROLLER_TOAST_DEBOUNCE_MS`); disconnect already 400ms-debounced (`PHYSICAL_DISCONNECT_DEBOUNCE_MS`) → no replug flicker |
| Hide/show overlay | `inputControlsView.setShowTouchscreenControls(bool)` (`InputControlsView.java:521`) + `showInputControls()`/`hideInputControls()` |
| Making a pad take the OSC's slot | YIELD path already exists — `handleOnScreenModeForNewPad` (`WinHandler.java:1229`) moves OSC off slot 0 so the pad takes it; auto-hide reuses this so the overlay's slot is the one vacated |
| Capability/name filter (false positives) | `ExternalController.isGameController` (`ExternalController.java:229`) — rejects `uinput-fpc`/`goodix_fp`/`uinput-` by name FIRST, then requires gamepad source + real axes/BTN_GAMEPAD |
| 3-tier config pattern to mirror | `onScreenControllerMode` (KEEP/YIELD/SHARE): `Container` get/set + extra, shortcut extra + `resolvedX()`, global `GlobalControllerPrefs`, UI in the same three screens |
| Bundled default layout | `assets/inputcontrols/profiles/controls-3.icp` ("Virtual Gamepad") |
| Per-controller bindings (feature 7) | `ControlsProfile.controllers` (`:37`, keyed by descriptor, `addController`/`getController` `:127-141`) → each `ExternalController.controllerBindings` (`ExternalController.java:31`, `:71-90`); persisted as `controllerBindings` JSONArray (`ControlsProfile.java:422-430`) |

### Implementation sketch
1. **Data model** (`Container.java`, near `ON_SCREEN_MODE_*`): `autoHideControlsOnPad` extra + getter/setter + default; shortcut override read; `GlobalControllerPrefs.get/setAutoHideControlsOnPad`; `resolvedAutoHideControlsOnPad()` in the Activity (shortcut-else-container).
2. **Default layout fallback**: where the launch resolves the profile (`simulateConfirmInputControlsDialog` ~`XServerDisplayActivity.java:4819`), if no user profile is selected, resolve/instantiate the bundled "Virtual Gamepad" template instead of hiding.
3. **Slot-aware runtime**: track a session `userWantsControlsShown` baseline; in the assignment listener (and once at launch), if `resolvedAutoHideControlsOnPad()` → when a controller takes the OSC's slot (YIELD it in first if needed), hide; restore to baseline when no real pad holds that slot. Transition-only.
4. **UI**: one switch each in the three screens with "?" help.
5. **Feature 7 (bindings)**: (7a) seed-on-add default template in `ControlsProfile.addController`; (7b) "copy bindings from…" action. Reserved `__default__` controller entry (or a profile field) for the template. Expose 7a/7b in BOTH the out-of-game External Controllers section (`InputControlsScreen.kt:495` + `ExternalControllerBindingsActivity`) AND the in-game Players sub-tab (`XServerDrawer.kt`).

### Scope
Auto-hide + smart default + slot-aware (features 1-6): ~1 focused pass, `android-app-engineer`. 1 data-model edit, ~2 Activity edits, 1 global-prefs edit, 3 UI additions. No native, no CMake.
Feature 7 (bindings default + copy): a **separate sub-task** — `ControlsProfile`/`ExternalController` core + UI in BOTH the out-of-game External Controllers section AND the in-game Players sub-tab. Ships alongside so auto-hide never strands a blank controller. (In-game surface is the bigger half — the in-game drawer has no binding editor today.)

### Device-test gates
1. Fresh install, no setup → launch → Virtual Gamepad touch controls show.
2. OSC = P1, connect pad → (auto-hide on) pad takes P1 slot, controls hide; unplug → controls return (only if baseline shown).
3. **Slot-aware / disambiguation:** OSC = P1 + pad **explicitly pinned** to P2 → controls STAY visible; OSC = P1 + pad connects with NO pin → pad takes P1, controls hide (solo assumption); pad explicitly pinned to P1 → controls hide.
4. Manual drawer re-show while pad connected → sticks.
5. Handheld (AYANEO) → controls never show; `uinput-fpc` never counts as a pad.
6. Explicitly-off controls stay off.
7. SHARE + auto-hide behaves per decision (auto-hide wins).
8. Per-shortcut override + "use container default" round-trip; global seeds new containers only.
9. **Feature 7:** new controller inherits the default/any-controller bindings (not blank); copy-bindings action clones one controller's map onto another; both actions work from BOTH the out-of-game External Controllers section AND the in-game Players sub-tab; auto-hide doesn't hide for a truly blank controller on a keyboard-mapping profile.

---

## Open decision (default value)
Recommended default for the new toggle: **ON** (auto-hide enabled), paired with the smart default layout — that's the seamless behavior #333 wants. Note this changes existing behavior: users with a layout assigned but controls currently off could start seeing the pad → call out in release notes.
