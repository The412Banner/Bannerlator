# UI Migration Report — star-compose
**Generated:** 2026-04-22  
**Last refreshed:** 2026-05-06 (status markers updated against `beta-4`)  
**Status:** Compose migration of main screens complete. In-game drawer + dialogs/overlays migrated 2026-04-22. Remaining work and dead-code cleanup below.

> **⚠️ STATUS NOTE (2026-05-06):** None of the files listed in §1 have actually been deleted yet — the Compose replacements were made and shipped in Beta 2, but the Java/XML originals still sit in the tree. The `DebugDialog.java` prerequisite fix in `LogView.java` is also still pending. See current statuses inline in the tables below.

---

## 1. Safe to Delete (Dead Code — No Migration Needed)

These files are fully replaced by Compose equivalents and have zero references from live code (one exception: `DebugDialog.java`, see ⚠️).

### Java files
| File | Replaced By | Status (2026-05-06) |
|---|---|---|
| `contentdialog/ActiveWindowsDialog.java` | `ui/dialogs/ActiveWindowsDialog.kt` | 🟡 Still in tree, 0 refs — safe to delete now |
| `contentdialog/DebugDialog.java` ⚠️ | `ui/dialogs/DebugDialogContent.kt` | 🔴 Still in tree, 1 ref blocking — `widget/LogView.java` fix required first (see ⚠️ below) |
| `contentdialog/FSRControlFloatingDialog.java` | `ui/overlays/FSROverlay.kt` | 🟡 Still in tree, 0 refs — safe to delete now |
| `contentdialog/ScreenEffectDialog.java` | `ui/dialogs/ScreenEffectsDialog.kt` | 🟡 Still in tree, 0 refs — safe to delete now |
| `widget/MagnifierView.java` | `ui/overlays/MagnifierOverlay.kt` | 🟡 Still in tree, 0 refs — safe to delete now |
| `winhandler/TaskManagerDialog.java` | `ui/dialogs/TaskManagerDialog.kt` | 🟡 Still in tree, 0 refs — safe to delete now |

⚠️ **Before deleting `DebugDialog.java`:** fix `widget/LogView.java` lines 230, 237, 245 —  
replace `DebugDialog.setPaused(x)` with `XServerDialogState.INSTANCE.setLogPaused(x)` and remove the import.  
**(2026-05-06 status: not done yet. `XServerDialogState.setLogPaused()` exists in `ui/XServerDialogState.kt:93`. The 3 LogView.java call sites are still calling the old API.)**

### XML layout files
All 10 XML files below remain in `app/src/main/res/layout/` as of 2026-05-06 — none have been deleted. Their inflate-points are gone, but the files themselves can be removed alongside the Java cleanup above. Doing so brings the layout count from 73 → 63.

| File | Replaced By | Status |
|---|---|---|
| `layout/active_windows_dialog.xml` | Compose `ActiveWindowsDialog.kt` | 🟡 in tree, no inflater |
| `layout/active_window_item.xml` | Compose `ActiveWindowsDialog.kt` | 🟡 in tree, no inflater |
| `layout/debug_dialog.xml` | Compose `DebugDialogContent.kt` | 🟡 in tree, no inflater |
| `layout/debug_toolbar.xml` | Compose `DebugDialogContent.kt` | 🟡 in tree, no inflater |
| `layout/fsr_control_dialog.xml` | Compose `FSROverlay.kt` | 🟡 in tree, no inflater |
| `layout/magnifier_view.xml` | Compose `MagnifierOverlay.kt` | 🟡 in tree, no inflater |
| `layout/screen_effect_dialog.xml` | Compose `ScreenEffectsDialog.kt` | 🟡 in tree, no inflater |
| `layout/task_manager_dialog.xml` | Compose `TaskManagerDialog.kt` | 🟡 in tree, no inflater |
| `layout/process_info_list_item.xml` | Compose `TaskManagerDialog.kt` | 🟡 in tree, no inflater |
| `layout/about_dialog.xml` | Compose `AboutDialog()` in `MainActivity.kt` | 🟡 in tree, no inflater |

---

## 2. Still Active — Needs Migration to Compose

> **(2026-05-06 status):** Since this report was generated, the **in-game side drawer** and **all in-game dialogs/overlays** have been migrated to Compose (commits `f79f1ef` and `e68dea0` on 2026-04-22, shipped in Beta 2). The list below was written before that work landed; entries marked `✅ DONE` reflect what's been completed since. See `COMPOSE_MIGRATION_REPORT.md` Part E for the full post-2026-04-22 status.

### Priority A — Standalone Screens (high value, self-contained)

#### `SettingsFragment.java` + `settings_fragment.xml`
- **What it is:** XML PreferenceScreen for app settings (dark mode, Wine path, etc.)
- **Known issue:** Mismatches the Compose dark theme; dark mode toggle has no effect on Compose UI (partially fixed in Job 4 via listener, but the fragment itself still renders with XML AppCompat theme)
- **Migration approach:**
  1. Create `ui/screens/SettingsScreen.kt` as a Compose `LazyColumn` of preference rows
  2. Replace `PreferenceFragment` with a `NavHost` destination
  3. Wire `SharedPreferences` reads/writes directly — no PreferenceManager needed
  4. Hook dark mode toggle to `AppThemeState.setDarkMode()`
- **Effort:** ~2 hours
- **Files:** `SettingsFragment.java`, `settings_fragment.xml`, `preference_*.xml` (8 layout files)
- **(2026-05-06):** ⏳ Still pending. SettingsFragment.java is still active and wrapped via `FragmentScreen`.

#### `ShortcutPickerActivity.java`
- **What it is:** Picker shown when user wants to add a shortcut widget to the home screen
- **Migration approach:** Convert to a Compose `Dialog` or `BottomSheet` launched from `MainActivity`; use `ActivityResultContracts` to return the result
- **Effort:** ~1 hour

#### `RestoreActivity.java`
- **What it is:** Container/data restore flow
- **Migration approach:** Compose screen inside `MainActivity` nav graph with a `LaunchedEffect` for the restore logic
- **Effort:** ~1 hour

#### `saves/CustomFilePickerActivity.java` + `activity_file_picker.xml` + `saves/FileAdapter.java`
- **What it is:** Custom file browser for picking save files
- **Migration approach:** Replace with `ActivityResultContracts.OpenDocument` (system file picker) or a Compose `LazyColumn` file browser screen
- **Effort:** ~1.5 hours

### Priority B — Complex Screens (more effort, more impact)

#### `ControlsEditorActivity.java` + `controls_editor_activity.xml`
- **What it is:** The full-screen gamepad button mapper with drag-and-drop control elements, canvas drawing, element property editors
- **Why complex:** Heavy custom `Canvas` drawing via `InputControlsView.java`; multiple nested dialogs (`analog_stick_config_dialog.xml`, `control_element_settings.xml`, `extra_keys_config.xml`, `binding_field.xml`)
- **Migration approach:**
  1. Keep `InputControlsView.java` as a custom `Canvas` view, wrap it in Compose via `AndroidView`
  2. Migrate all nested dialogs to Compose `AlertDialog`/`Dialog`
  3. Wrap the activity in a Compose screen with the editor toolbar as a `TopAppBar`
- **Effort:** ~4 hours

#### `ExternalControllerBindingsActivity.java` + `external_controller_bindings_activity.xml`
- **What it is:** Maps physical gamepad buttons to in-game actions
- **Migration approach:** Compose `LazyColumn` with `AlertDialog` for each binding row; replace `RecyclerView` + `external_controller_binding_list_item.xml`
- **Effort:** ~2 hours

#### `InputControlsFragment.java` + `input_controls_fragment.xml`
- **What it is:** In-game floating panel for switching/editing input profiles
- **Migration approach:** Already partially bridged via `XServerDialogState` (`InputControlsDialog.kt`); check if this fragment is still being launched separately or fully replaced
- **Effort:** ~1 hour (verify + remove if already dead)
- **(2026-05-06):** ✅ Compose dialog `ui/dialogs/InputControlsDialog.kt` exists and is wired into the in-game drawer. Fragment.java is still in tree — verify it's no longer launched anywhere; if confirmed dead, delete in the §1 cleanup.

#### `BigPictureActivity.java` + `big_picture_activity.xml` + `bigpicture/BigPictureAdapter.java` + `bigpicture/TiledBackgroundView.java`
- **What it is:** TV/couch-mode shortcut grid with a custom tiled background animation
- **Migration approach:**
  1. `TiledBackgroundView` → Compose `Canvas` with `drawBitmap` tiling in a `LaunchedEffect` animation
  2. `BigPictureAdapter` → `LazyVerticalGrid`
  3. Wrap in a Compose fullscreen activity or nav destination
- **Effort:** ~2.5 hours

#### `XrActivity.java`
- **What it is:** XR/VR mode entry point
- **Migration approach:** Assess if XR is actively used; if so, migrate the Activity shell to Compose; XR rendering stays native
- **Effort:** ~1 hour (shell only)

### Priority C — Store Module (lowest priority, high complexity)

All 12 store activities are fully Java/XML. They form a self-contained module.

| Activity | What it does |
|---|---|
| `store/EpicMainActivity.java` | Epic Games store home |
| `store/EpicGamesActivity.java` | Epic game library list |
| `store/EpicGameDetailActivity.java` | Epic game detail/install |
| `store/EpicLoginActivity.java` | Epic login WebView |
| `store/EpicFreeGamesActivity.java` | Epic free games list |
| `store/GogMainActivity.java` | GOG store home |
| `store/GogGamesActivity.java` | GOG library list |
| `store/GogGameDetailActivity.java` | GOG game detail |
| `store/GogLoginActivity.java` | GOG login WebView |
| `store/AmazonMainActivity.java` | Amazon Games home |
| `store/AmazonGamesActivity.java` | Amazon library list |
| `store/AmazonGameDetailActivity.java` | Amazon game detail |
| `store/AmazonLoginActivity.java` | Amazon login WebView |
| `store/FolderPickerActivity.java` | Folder picker for store downloads |

- **Migration approach:** Migrate store by store (Epic → GOG → Amazon). Each store shares a pattern: login WebView → game list → game detail. Create shared Compose templates (`StoreGameListScreen`, `StoreGameDetailScreen`) and fill per-store.
- **Effort:** ~6–8 hours total for all three stores

### Priority D — Custom Widgets (keep as AndroidView wrappers)

These are complex custom `View` subclasses used inside Compose screens via `AndroidView`. They do not need full migration — just ensure they are properly wrapped.

| Widget | Used In | Notes |
|---|---|---|
| `widget/InputControlsView.java` | `ControlsEditorActivity` | Canvas-based; keep as-is, wrap in `AndroidView` |
| `widget/EnvVarsView.java` | `ContainerDetailScreen` | Already used via `AndroidView` in Compose |
| `widget/CPUListView.java` | Container settings | Wrap in `AndroidView` |
| `widget/ImagePickerView.java` | Container settings | Wrap in `AndroidView` |
| `widget/TouchpadView.java` | In-game | Native touch input; keep as-is |
| `widget/XServerView.java` | `XServerDisplayActivity` | GLSurfaceView; keep as-is |
| `widget/LogView.java` | Static file logging only | Keep `setFilename()`/`getLogFile()` statics; display logic is dead |
| `widget/ColorPickerView.java` | Was used in AppearanceScreen | Check if still used after Compose HSV picker migration |

---

## 3. Content Dialogs Still Active (used in ContainerDetailScreen)

These Java dialogs are still launched from Compose screens via `AndroidView` or direct instantiation. Migrate after Priority A/B screens are done.

| File | Used For | Migration |
|---|---|---|
| `contentdialog/DXVKConfigDialog.java` | DXVK config in container settings | Compose `AlertDialog` with sliders/dropdowns |
| `contentdialog/WineD3DConfigDialog.java` | WineD3D config in container settings | Same pattern |
| `contentdialog/GraphicsDriverConfigDialog.java` | Driver picker in container settings | Already partially in Compose; verify |
| `contentdialog/ContentDialog.java` | Base class for above | Delete after all subclasses migrated |
| `box64/Box64EditPresetDialog.java` | Box64 preset editor | Compose `Dialog` with text fields |
| `fexcore/FEXCoreEditPresetDialog.java` | FEX preset editor | Same pattern |
| `core/DownloadProgressDialog.java` | Wine/component download progress | Compose `Dialog` with `LinearProgressIndicator` |
| `core/PreloaderDialog.java` | App loading spinner | Compose `Dialog` with `CircularProgressIndicator` (similar to Job 3 container creation overlay) |

---

## 4. Suggested Execution Order

```
Step 1 — Dead code deletion (no risk, fast)              ⏳ STILL PENDING (2026-05-06)
                                                            └─ blocked on LogView.java fix for DebugDialog
Step 2 — SettingsFragment → Compose (fixes dark mode)    ⏳ STILL PENDING
Step 3 — InputControlsFragment (verify if dead)          ⏳ STILL PENDING (Compose dialog exists, verify Java dead)
Step 4 — ShortcutPickerActivity + RestoreActivity +
         CustomFilePickerActivity                         ⏳ STILL PENDING
Step 5 — ExternalControllerBindingsActivity              ⏳ STILL PENDING
Step 6 — ControlsEditorActivity (wrap canvas in
         AndroidView, migrate dialogs)                    ⏳ STILL PENDING
Step 7 — BigPictureActivity                              ⏳ STILL PENDING
Step 8 — Content dialogs (DXVKConfig, WineD3DConfig,
         DownloadProgress, Preloader)                     ⏳ STILL PENDING
Step 9 — Store module (Epic → GOG → Amazon)              ⏳ STILL PENDING
Step 10 — XrActivity shell                               ⏳ STILL PENDING

──────────────────────────────────────────────────────────
NEW since this plan was written (not in original list):
──────────────────────────────────────────────────────────
✅ In-game side drawer → Compose            (XServerDrawer.kt, 2026-04-22)
✅ In-game dialogs/overlays → Compose       (8 files, 2026-04-22)
✅ Splash screen overhaul                   (SparkleCanvas, 2026-04-22)
✅ Controller support fully restored        (Beta 3, 2026-05-06)
✅ Box64 dropdown stale-display bug fixed  (beta-4, 2026-05-06)
```
