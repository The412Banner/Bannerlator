# Star-Compose: Jetpack Compose Migration Report & Developer Guide

**Date:** 2026-04-16  
**Last updated:** 2026-05-06 — Part G appended (post-2026-04-22 work: SDL2 controller fix, ViewModel read/seed bug, dead-code cleanup)

---

## Table of Contents

**Part A — Developer Guide (How To Do This Yourself)**
- [A1. Gradle Setup](#a1-gradle-setup)
- [A2. Order of Operations](#a2-order-of-operations)
- [A3. Fragment → Screen Pattern](#a3-fragment--screen-pattern)
- [A4. ContentDialog → AlertDialog Pattern](#a4-contentdialog--alertdialog-pattern)
- [A5. Full-Width / Full-Height Dialog](#a5-full-width--full-height-dialog)
- [A6. Async Data Loading](#a6-async-data-loading-spinners--dropdowns)
- [A7. Bridging Java Views](#a7-bridging-java-views-that-have-no-compose-equivalent)
- [A8. Icon / File Picker](#a8-icon--file-picker-replaces-startactivityforresult)
- [A9. Sharing Composables Between Screens](#a9-sharing-composables-between-screens)
- [A10. Keeping a Java Fragment Inside a Compose NavGraph](#a10-keeping-a-java-fragment-inside-a-compose-navgraph)
- [A11. Gotchas and Fixes](#a11-gotchas-and-fixes) *(18 gotchas total)*
- [A12. Theme / Color Setup](#a12-theme--color-setup)
- [A13. Screen Sealed Class + NavGraph Wiring](#a13-screen-sealed-class--navgraph-wiring)
- [A14. Launching XServerDisplayActivity](#a14-launching-xserverdisplayactivity-running-a-shortcutcontainer)
- [A15. The Engine Boundary](#a15-the-engine-boundary--what-to-never-touch)
- [A16. Post-Migration Testing Checklist](#a16-post-migration-testing-checklist)
- [A17. AppThemeState — Dynamic Theme System](#a17-appthemestate--dynamic-theme-system)
- [A18. PreloaderState — Global Loading Overlay](#a18-preloaderstate--global-loading-overlay)

**Part B — What Was Replaced and With What**
- [B1. Navigation Screens Replaced](#b1-navigation-screens-replaced)
- [B2. Dialogs Replaced](#b2-dialogs-replaced)
- [B3. Helper / Utility Classes Deleted](#b3-helper--utility-classes-deleted)
- [B4. What Remains (Intentionally)](#b4-what-remains-intentionally)
- [B5. Reusable Internal Composables](#b5-reusable-internal-composables)
- [B6. Key Compose Patterns Used](#b6-key-compose-patterns-used)
- [B7. Summary Stats](#b7-summary-stats)

**Part C — Post-Report Bug Fixes**

**Part D — Post-Migration Feedback Fixes (2026-04-17)**
- [D1. Fix Summary Table](#d1-fix-summary-table)
- [D2–D9. Per-Job Detail](#d2-job-detail-help--support)
- [D10. New Gotchas (13–18)](#d10-new-gotchas-discovered-during-feedback-fixes)

**Part E — Post-Migration Improvements (2026-04-20)**
- [E1. AdrenoTools SIGSEGV Fix](#e1-adrenotools--turnip-driver-sigsegv-fix)
- [E2. Developer Feedback Fixes](#e2-developer-feedback-fixes)
- [E3. TopAppBar Actions Architecture](#e3-topappbar-actions-architecture)
- [E4. Shortcut Grid Cover Art Layout](#e4-shortcut-grid--cover-art-layout)
- [E5. Shortcut List Tech Info Columns](#e5-shortcut-list-item--tech-info-columns)
- [E6. New Gotchas (19–22)](#e6-new-gotchas-1922)

**Part F — Post-Migration Improvements (2026-04-22)**
- [F1. Animated Splash Screen](#f1-animated-splash-screen)
- [F2. Custom Accent Color Fix](#f2-custom-accent-color--base-preset-inheritance-fix)
- [F3. AppDrawer + AppTopBar Theme Fix](#f3-appdrawer--apptopbar--materialtheme-color-fix)
- [F4. In-Game Drawer Compose Migration](#f4-in-game-side-drawer--full-compose-migration)
- [F5. Contents URL Update](#f5-contents-screen--remote-profiles-url)
- [F6. New Gotchas (23–25)](#f6-new-gotchas-2325)

**Part G — Post-2026-04-22 Status (2026-05-06)**
- [G1. Controller Support — SDL2 SoName Symlink Lost in Splash-Install Migration](#g1-controller-support--sdl2-soname-symlink-lost-in-splash-install-migration)
- [G2. Box64 Component Dropdown — Read/Seed Bug in ContainerDetailViewModel](#g2-box64-component-dropdown--readseed-bug-in-containerdetailviewmodel)
- [G3. UI Text and Branding Pass](#g3-ui-text-and-branding-pass)
- [G4. Dead Java/XML Cleanup — In-Game Dialog Migration Tail End](#g4-dead-javaxml-cleanup--in-game-dialog-migration-tail-end)
- [G5. New Gotchas (26–27)](#g5-new-gotchas-2627)
- [G6. Updated Summary Stats](#g6-updated-summary-stats)
- [G7. Still Active — Needs Migration](#g7-still-active--needs-migration)

---

## Overview

Full replacement of the XML/Java UI layer with Jetpack Compose + Material 3. The Wine engine, JNI, Box64/FEX, and container logic were left completely untouched. Every navigation screen and every dialog triggered from a Compose screen is now native Compose.

This document serves two purposes:
1. A complete record of what was replaced and with what
2. A practical guide any Winlator fork developer can follow to do the same

---

## Part A — Developer Guide (How To Do This Yourself)

### A1. Gradle Setup

These are the **exact versions used in this project** — not placeholders.

**Versions:**
| Component | Version |
|---|---|
| Android Gradle Plugin | 8.8.0 |
| Kotlin | 2.0.21 |
| Compose BOM | 2024.02.00 |
| compileSdk | 34 |
| minSdk | 26 |
| targetSdk | 28 |
| NDK | 29.0.14206865 |
| Java source/target | 17 |
| activity-compose | 1.8.2 |
| lifecycle-viewmodel-compose | 2.7.0 |
| navigation-compose | 2.7.6 |

**Note on Compose compiler (AGP 8.x + Kotlin 2.x):** Older guides tell you to set `kotlinCompilerExtensionVersion` inside `composeOptions {}`. This is the AGP 7.x approach. With Kotlin 2.0+ and AGP 8.x, use the `org.jetbrains.kotlin.plugin.compose` Gradle plugin instead — it automatically manages the compiler version and `composeOptions {}` is no longer needed.

**Top-level `build.gradle` — add the plugins:**
```groovy
buildscript {
    dependencies {
        classpath 'com.android.tools.build:gradle:8.8.0'
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21'
        classpath 'org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21'
    }
}
```

**App `build.gradle`:**
```groovy
plugins {
    id 'com.android.application'
}
apply plugin: 'kotlin-android'
apply plugin: 'org.jetbrains.kotlin.plugin.compose'  // replaces composeOptions block

android {
    compileSdk 34
    defaultConfig {
        minSdkVersion 26
        targetSdkVersion 28
    }

    buildFeatures {
        compose true
        buildConfig true   // required — AGP 8.x disables this by default
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
        freeCompilerArgs += ['-Xskip-metadata-version-check']
    }

    // Keep all NDK / externalNativeBuild config exactly as-is — Compose doesn't touch it
}

dependencies {
    // Compose BOM — pins all Compose library versions together
    implementation platform('androidx.compose:compose-bom:2024.02.00')
    implementation 'androidx.compose.ui:ui'
    implementation 'androidx.compose.ui:ui-graphics'
    implementation 'androidx.compose.material3:material3'
    implementation 'androidx.compose.material:material-icons-extended'
    implementation 'androidx.compose.ui:ui-tooling-preview'
    implementation 'androidx.activity:activity-compose:1.8.2'
    implementation 'androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0'
    implementation 'androidx.navigation:navigation-compose:2.7.6'
    debugImplementation 'androidx.compose.ui:ui-tooling'
    debugImplementation 'androidx.compose.ui:ui-test-manifest'

    // Keep all existing dependencies — appcompat, preference, material, etc.
}
```

**Important:** Do not remove `minifyEnabled`, `ndk`, `externalNativeBuild`, or any JNI config — Compose sits entirely in the UI layer and doesn't touch those.

---

### A2. Order of Operations

Convert in this order. Going outermost → inward avoids situations where you're converting a dialog that depends on a screen you haven't converted yet.

1. **MainActivity** — replace XML drawer + nav graph with Compose `NavHost` + `ModalNavigationDrawer`
2. **Simple list screens first** — Containers, Shortcuts, Contents (these have the most predictable Fragment → Screen mapping)
3. **Complex detail screens** — ContainerDetail (many tabs + sub-dialogs)
4. **Dialogs belonging to each screen** — convert these as part of the same PR as the screen that owns them
5. **Shared dialogs** — promote to `internal fun` so both screens can use them
6. **Cleanup** — delete dead Java files only after confirming CI builds clean

---

### A3. Fragment → Screen Pattern

Every `Fragment` becomes a `@Composable` function + a `ViewModel`. The ViewModel holds the data, the Composable holds the UI.

**Before (Java Fragment):**
```java
public class ContainersFragment extends Fragment {
    private ContainerManager containerManager;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.containers_fragment, container, false);
        ListView listView = view.findViewById(R.id.LVContainers);
        // populate list...
        return view;
    }
}
```

**After (Kotlin Compose):**
```kotlin
// ViewModel
class ContainersViewModel(application: Application) : AndroidViewModel(application) {
    private val _containers = MutableStateFlow<List<Container>>(emptyList())
    val containers: StateFlow<List<Container>> = _containers

    fun refresh() {
        _containers.value = ContainerManager(getApplication()).containers
    }
}

// Screen
@Composable
fun ContainersScreen(vm: ContainersViewModel = viewModel()) {
    val containers by vm.containers.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn {
        items(containers) { container ->
            ContainerItem(container)
            Divider()
        }
    }
}
```

---

### A4. ContentDialog → AlertDialog Pattern

Almost every dialog in Winlator extends `ContentDialog`. The replacement is always a Compose `AlertDialog` driven by a state variable.

**Before (Java):**
```java
ContentDialog dialog = new ContentDialog(context, R.layout.my_dialog);
dialog.setTitle("Confirm");
dialog.findViewById(R.id.BTConfirm).setOnClickListener(v -> {
    doSomething();
    dialog.dismiss();
});
dialog.show();
```

**After (Compose):**
```kotlin
var showDialog by remember { mutableStateOf(false) }

Button(onClick = { showDialog = true }) { Text("Open") }

if (showDialog) {
    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text("Confirm") },
        text = { Text("Are you sure?") },
        confirmButton = {
            TextButton(onClick = { doSomething(); showDialog = false }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) { Text("Cancel") }
        }
    )
}
```

The key insight: **the dialog is always in the composition tree, shown/hidden by a state variable**. There is no `dialog.show()` or `dialog.dismiss()` call.

---

### A5. Full-Width / Full-Height Dialog

The standard `AlertDialog` is constrained to ~280dp wide by Material. For settings dialogs that need more space (like ShortcutSettings with tabs), use `Dialog` directly with `DialogProperties`:

```kotlin
Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false)
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.92f),
        shape = RoundedCornerShape(12.dp)
    ) {
        // your content — TabRow, scrollable Column, etc.
    }
}
```

---

### A6. Async Data Loading (Spinners / Dropdowns)

Winlator spinners often load data from disk (Box64 versions, FEXCore versions, controls profiles). Do this off the main thread with `LaunchedEffect`:

```kotlin
var box64Versions by remember { mutableStateOf<List<String>>(emptyList()) }
var isArm64EC by remember { mutableStateOf(false) }

LaunchedEffect(Unit) {
    withContext(Dispatchers.IO) {
        val cm = ContentsManager(context)
        cm.syncProfiles()
        val wineInfo = WineInfo.fromIdentifier(context, cm, shortcut.container.wineVersion)
        isArm64EC = wineInfo.isArm64EC()
        val versions = ContainerDetailHelper.loadBox64VersionList(context, cm, isArm64EC)
        withContext(Dispatchers.Main) {
            box64Versions = versions
        }
    }
}

// Use in a dropdown
LabeledDropdown(
    label = "Box64 Version",
    options = box64Versions,
    selected = selectedBox64Version,
    onSelect = { selectedBox64Version = it }
)
```

---

### A7. Bridging Java Views That Have No Compose Equivalent

Some Winlator views are too complex to rewrite (e.g. `EnvVarsView`, `CPUListView`). Use `AndroidView` to embed them inside Compose:

```kotlin
val envVarsViewRef = remember { mutableStateOf<EnvVarsView?>(null) }

AndroidView(
    factory = { ctx ->
        EnvVarsView(ctx).also { ev ->
            ev.setDarkMode(true)
            ev.setEnvVars(EnvVars(shortcut.getExtra("envVars")))
            envVarsViewRef.value = ev
        }
    },
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 150.dp)
)

// Read the value back when saving:
val envVarsStr = envVarsViewRef.value?.envVars?.toString() ?: ""
```

---

### A8. Icon / File Picker (replaces startActivityForResult)

`startActivityForResult` is deprecated. In Compose use `rememberLauncherForActivityResult`:

```kotlin
val iconLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.GetContent()
) { uri ->
    uri?.let {
        val bmp = BitmapFactory.decodeStream(context.contentResolver.openInputStream(it))
        // save bmp to shortcut icon file
    }
}

Button(onClick = { iconLauncher.launch("image/*") }) {
    Text("Pick Icon")
}
```

---

### A9. Sharing Composables Between Screens

If a dialog (e.g. DxvkConfigDialog) is needed in both ContainerDetail and ShortcutSettings, define it **once** and make it `internal` (visible across the module but not public API):

```kotlin
// In ContainerDetailScreen.kt
@Composable
internal fun DxvkConfigDialog(
    config: KeyValueSet,
    onConfirm: (KeyValueSet) -> Unit,
    onDismiss: () -> Unit
) { /* ... */ }
```

Then call it from ShortcutsScreen.kt directly — no duplication needed.

---

### A10. Keeping a Java Fragment Inside a Compose NavGraph

For fragments too complex to rewrite (InputControlsFragment, SettingsFragment), use a `FragmentScreen` wrapper:

```kotlin
@Composable
fun FragmentScreen(fragmentClass: Class<out Fragment>) {
    val fragmentManager = (LocalContext.current as FragmentActivity).supportFragmentManager
    AndroidView(
        factory = { ctx ->
            // Wrap in dark theme context so the fragment matches the Compose host visually
            val themedCtx = ContextThemeWrapper(ctx, R.style.AppTheme_Dark)
            FragmentContainerView(themedCtx).apply {
                id = View.generateViewId()
            }
        },
        update = { view ->
            if (fragmentManager.findFragmentById(view.id) == null) {
                fragmentManager.beginTransaction()
                    .replace(view.id, fragmentClass.getDeclaredConstructor().newInstance())
                    .commitNow()
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// Usage in NavGraph:
composable(Screen.InputControls.route) {
    FragmentScreen(InputControlsFragment::class.java)
}
composable(Screen.Settings.route) {
    FragmentScreen(SettingsFragment::class.java)
}
```

**Why `ContextThemeWrapper`:** The base XML `AppTheme` is a Light theme. Without wrapping, `SettingsFragment` and `InputControlsFragment` render with a white background inside an otherwise dark Compose host — a jarring mismatch. `ContextThemeWrapper(ctx, R.style.AppTheme_Dark)` forces the fragment to use the dark theme, matching the Compose surface color.

**Gotcha:** `getSupportActionBar()` returns null inside fragments hosted this way. Remove any `requireActivity().supportActionBar?.title = "..."` calls from fragments that will be Compose-hosted. Manage the title in your Compose `TopAppBar` instead.

---

### A11. Gotchas and Fixes

These are the non-obvious problems encountered during this migration. Knowing them upfront will save hours.

#### Gotcha 1: AppTheme is Light — dialogs render white
Winlator's `AppTheme` is based on a Light Material theme. Any `ContentDialog` or XML dialog will have a white background. This also affects Compose dialogs if you're not careful.

**Fix:** In your Compose theme, force dark surface colors:
```kotlin
MaterialTheme(
    colorScheme = darkColorScheme(
        surface = Color(0xFF1E1E2E),
        background = Color(0xFF121212),
        // etc.
    )
) { /* app content */ }
```
Or set a transparent window background on any remaining XML dialogs:
```xml
<style name="TransparentDialog" parent="Theme.AppCompat.Dialog">
    <item name="android:windowBackground">@android:color/transparent</item>
</style>
```

#### Gotcha 2: getSupportActionBar() is null in Compose-hosted fragments
When a `Fragment` is hosted inside a Compose `AndroidView`/`FragmentContainerView`, the Activity's ActionBar is not wired up to that fragment's lifecycle the normal way.

**Fix:** Remove all `requireActivity().supportActionBar?.title = ...` calls from fragments that will be Compose-hosted. Manage the title in your Compose TopAppBar instead.

#### Gotcha 3: isDarkMode must be hardcoded true in remaining Java code
Some Java dialogs (e.g. the win components spinner backgrounds) check `isDarkMode` to pick drawable resources. Since the app is now always dark-themed via Compose, just hardcode it:

```java
// Before
spinner.setPopupBackgroundResource(isDarkMode
    ? R.drawable.content_dialog_background_dark
    : R.drawable.content_dialog_background);

// After — always dark
spinner.setPopupBackgroundResource(R.drawable.content_dialog_background_dark);
```

#### Gotcha 4: WinHandler flag constants are byte in Java, Int in Kotlin
Java defines these as `public static final byte FLAG_INPUT_TYPE_XINPUT = 1;`. Kotlin bitwise ops require `Int`.

```kotlin
// This crashes with type mismatch:
if (inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT != 0)

// Fix — coerce to Int:
if (inputType and WinHandler.FLAG_INPUT_TYPE_XINPUT.toInt() != 0)
```

#### Gotcha 5: Java getters become Kotlin properties
Java `getGraphicsDriver()` in a Java class is accessible as `.graphicsDriver` in Kotlin. Don't call `.getGraphicsDriver()` — Kotlin will warn and it reads wrong.

```kotlin
// Wrong (works but ugly):
container.getGraphicsDriver()

// Right (idiomatic Kotlin):
container.graphicsDriver
```

#### Gotcha 6: StringUtils.parseIdentifier() strips the display label
Winlator spinner entries like `"800x600 (4:3)"` have a display label. `StringUtils.parseIdentifier()` strips it to return just `"800x600"`. Always run dropdown selections through this before saving to container/shortcut data:

```kotlin
val raw = StringUtils.parseIdentifier(selectedDisplayString) // "800x600"
```

#### Gotcha 7: Screen size "Custom" needs special handling
The screen size spinner has a "Custom" entry. Detect it by comparing the raw value, then show width/height text fields:

```kotlin
var selectedScreenSize by remember { mutableStateOf(container.screenSize) }
var customWidth by remember { mutableStateOf("") }
var customHeight by remember { mutableStateOf("") }
val isCustom = selectedScreenSize == "custom"

LabeledDropdown(options = screenSizeOptions, selected = ..., onSelect = { v ->
    selectedScreenSize = StringUtils.parseIdentifier(v)
})

if (isCustom) {
    Row {
        OutlinedTextField(value = customWidth, onValueChange = { customWidth = it }, label = { Text("Width") })
        OutlinedTextField(value = customHeight, onValueChange = { customHeight = it }, label = { Text("Height") })
    }
}
```

#### Gotcha 8: colorPrimary is near-black — never use it as text color on dark backgrounds
Winlator's `colors.xml` defines `colorPrimary = #262626`. Any XML layout that uses `android:textColor="@color/colorPrimary"` will render as near-invisible dark grey text on a dark background.

**Affects:** `external_controller_list_item.xml` (controller name), `input_controls_fragment.xml` (External Controllers section header), and potentially other XML layouts that haven't been converted to Compose yet.

**Fix:** Replace with a light color like `#E6E6E6`, or use `@android:color/white`:
```xml
<!-- Before -->
android:textColor="@color/colorPrimary"

<!-- After -->
android:textColor="#E6E6E6"
```

**When doing your own migration:** grep every remaining XML layout for `@color/colorPrimary` as a text color and fix them all at once.

#### Gotcha 9: Don't delete Java "dialog" classes that are actually utility classes
Some files in `contentdialog/` look like dialogs but are actually **static utility classes** whose methods are called by the engine layer. Deleting them breaks the build silently if you're not careful.

**Check before deleting** — search all Java and Kotlin files for static method calls:
```bash
grep -rn "GraphicsDriverConfigDialog\." app/src/main/java/
grep -rn "DXVKConfigDialog\." app/src/main/java/
```

If static methods like `parseGraphicsDriverConfig()`, `setEnvVars()`, `getVersion()` show up outside the dialog file itself, keep the file. You can delete the constructor and any UI-building methods, but the statics must stay.

#### Gotcha 10: ContainerManager.reload() must be called before getContainers()
In ViewModel `init` or `refresh()`, always call `reload()` first:

```kotlin
fun refresh() {
    val mgr = ContainerManager(getApplication())
    mgr.reload()  // without this, getContainers() returns stale/empty list
    _containers.value = mgr.containers
}
```

#### Gotcha 11: ExposedDropdownMenuBox + clickable container = double-fire bug
When using `ExposedDropdownMenuBox`, the `menuAnchor()` modifier already intercepts taps and calls `onExpandedChange`. If the composable it is applied to also has its own `onClick`, both fire on the same tap — the dropdown opens and immediately closes.

**Symptom:** Drive letter dropdown (or any `CompactDropdown`) appears unresponsive. Tapping does nothing.

**Wrong:**
```kotlin
OutlinedCard(
    onClick = { expanded = !expanded },    // fires second, cancels menuAnchor
    modifier = Modifier.menuAnchor()       // fires first, sets expanded = true
) { ... }
```

**Fix:** Remove `onClick` from the card. Let `menuAnchor()` be the sole touch handler:
```kotlin
OutlinedCard(
    modifier = Modifier.menuAnchor()       // handles all tap events
) { ... }
```

#### Gotcha 12: Reflection needed to update Shortcut.file after rename
The `Shortcut.file` field is `final`. If you rename a shortcut and need to update the file reference in-memory:

```kotlin
private fun renameShortcut(shortcut: Shortcut, newName: String) {
    val oldFile = shortcut.file
    val newFile = File(oldFile.parent, "$newName.lnk")
    if (oldFile.renameTo(newFile)) {
        val field = Shortcut::class.java.getDeclaredField("file")
        field.isAccessible = true
        field.set(shortcut, newFile)
    }
}
```

---

### A12. Theme / Color Setup

This project uses a **dynamic multi-preset theme system**, not a single hardcoded color scheme. Understanding this is important — the simple approach (one static `darkColorScheme()`) works for a basic migration, but you'll want the full system for theme switching and dark mode support.

---

#### Step 1 — Color.kt (static palette constants)

These are used **only by `Theme.kt` and `ThemePreset.kt`** to build `ColorScheme` objects. Never import them directly in composables — use `MaterialTheme.colorScheme.primary` instead (see Gotcha 14).

```kotlin
package com.winlator.cmod.ui.theme
import androidx.compose.ui.graphics.Color

// Used as fallback defaults — not for direct use in composables
val Surface          = Color(0xFF2A2A2A)
val OnSurface        = Color(0xFFE0E0E0)
val OnSurfaceVariant = Color(0xFFAAAAAA)
val Divider          = Color(0xFF404040)
```

---

#### Step 2 — ThemePreset.kt (per-preset color definitions)

Each preset defines its background/surface/primary colors and provides both dark and light `ColorScheme` variants:

```kotlin
data class ThemePreset(
    val name: String,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primary: Color,
    val onSurface: Color = Color(0xFFE0E0E0),
    val onSurfaceVariant: Color = Color(0xFFAAAAAA),
) {
    fun toColorScheme(accentOverride: Color? = null) = darkColorScheme(
        primary          = accentOverride ?: primary,
        background       = background,
        surface          = surface,
        onSurface        = onSurface,
        surfaceVariant   = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        error            = Color(0xFFCF6679),
    )

    fun toLightColorScheme(accentOverride: Color? = null) = lightColorScheme(
        primary          = accentOverride ?: primary,
        onPrimary        = Color(0xFFFFFFFF),
        background       = Color(0xFFF5F5F5),
        onBackground     = Color(0xFF1A1A1A),
        surface          = Color(0xFFFFFFFF),
        onSurface        = Color(0xFF1A1A1A),
        surfaceVariant   = Color(0xFFEAEAEA),
        onSurfaceVariant = Color(0xFF555555),
        error            = Color(0xFFB00020),
    )
}

// Built-in presets — last entry is always "Custom" (user-defined HSV accent)
val themePresets = listOf(
    ThemePreset("Classic Dark", Color(0xFF1A1A1A), Color(0xFF2A2A2A), Color(0xFF333333), Color(0xFF8B6BE0)),
    ThemePreset("AMOLED",       Color(0xFF000000), Color(0xFF0D0D0D), Color(0xFF181818), Color(0xFFBB86FC)),
    ThemePreset("Ocean",        Color(0xFF0D1B2A), Color(0xFF162435), Color(0xFF1E3045), Color(0xFF0EA5E9)),
    ThemePreset("Forest",       Color(0xFF0D1A12), Color(0xFF142010), Color(0xFF1C2E1A), Color(0xFF22C55E)),
    ThemePreset("Sunset",       Color(0xFF1A0D0D), Color(0xFF251515), Color(0xFF301C1C), Color(0xFFF97316)),
    ThemePreset("Rose",         Color(0xFF1A0D14), Color(0xFF25151E), Color(0xFF301C28), Color(0xFFEC4899)),
    ThemePreset("Steel",        Color(0xFF131419), Color(0xFF1C1D25), Color(0xFF252630), Color(0xFF64748B)),
    ThemePreset("Custom",       Color(0xFF121212), Color(0xFF1E1E1E), Color(0xFF2A2A2A), Color(0xFF8B6BE0)),
)
val CUSTOM_PRESET_INDEX = themePresets.size - 1
```

---

#### Step 3 — AppThemeState.kt (global singleton)

See **[A17. AppThemeState — Dynamic Theme System](#a17-appthemestate--dynamic-theme-system)** for the full implementation. In short: this singleton holds three `StateFlow`s (`_presetIndex`, `_customAccent`, `_isDarkMode`), combines them into a `colorScheme: Flow<ColorScheme>`, and reacts to SharedPreferences changes live.

---

#### Step 4 — Theme.kt (WinlatorTheme composable)

```kotlin
@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val colorScheme by AppThemeState.colorScheme.collectAsState(
        initial = AppThemeState.currentColorSchemeSnapshot()
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
```

**Why `currentColorSchemeSnapshot()` as `initial`:** The `colorScheme` flow emits its first value asynchronously. Without an `initial`, there's a single frame where `MaterialTheme` has no color scheme — this can cause a brief flash. The snapshot reads the current state synchronously and avoids it.

---

#### Step 5 — Apply in MainActivity

```kotlin
// Call before setContent — reads saved prefs and registers SharedPreferences listener
AppThemeState.init(this)

setContent {
    WinlatorTheme {
        // your app content
    }
}
```

**Why this matters:** Without an explicit dark color scheme, Compose inherits the XML `AppTheme` which is Light. Every `Surface`, `AlertDialog`, and `Card` will render white. Defining any `darkColorScheme()` here fixes all dialogs globally. The multi-preset system is optional — even a single static `darkColorScheme()` in `WinlatorTheme` is enough to get started.

---

### A13. Screen Sealed Class + NavGraph Wiring

Every screen needs a route. Define them in a sealed class, then wire them into a `NavHost`.

**Screen.kt:**
```kotlin
sealed class Screen(val route: String) {
    object Containers    : Screen("containers")
    object Shortcuts     : Screen("shortcuts")
    object ContainerDetail : Screen("container_detail/{containerId}") {
        fun route(id: Int) = "container_detail/$id"
    }
    object Contents      : Screen("contents")
    object Saves         : Screen("saves")
    object AdrenoTools   : Screen("adrenotools")
    object InputControls : Screen("input_controls")
    object Settings      : Screen("settings")
}
```

**NavGraph.kt:**
```kotlin
@Composable
fun AppNavGraph(navController: NavHostController = rememberNavController()) {
    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet {
                // drawer items — navigate on click
                NavigationDrawerItem(
                    label = { Text("Containers") },
                    selected = false,
                    onClick = { navController.navigate(Screen.Containers.route) }
                )
                // ... other items
            }
        }
    ) {
        NavHost(navController = navController, startDestination = Screen.Containers.route) {
            composable(Screen.Containers.route)     { ContainersScreen(navController) }
            composable(Screen.Shortcuts.route)      { ShortcutsScreen() }
            composable(Screen.Contents.route)       { ContentsScreen() }
            composable(Screen.Saves.route)          { SavesScreen() }
            composable(Screen.AdrenoTools.route)    { AdrenoToolsScreen() }
            composable(Screen.InputControls.route)  { FragmentScreen(InputControlsFragment::class.java) }
            composable(Screen.Settings.route)       { FragmentScreen(SettingsFragment::class.java) }
            composable(
                route = Screen.ContainerDetail.route,
                arguments = listOf(navArgument("containerId") { type = NavType.IntType })
            ) { backStackEntry ->
                val id = backStackEntry.arguments?.getInt("containerId") ?: return@composable
                ContainerDetailScreen(containerId = id)
            }
        }
    }
}
```

**Navigating to ContainerDetail from ContainersScreen:**
```kotlin
navController.navigate(Screen.ContainerDetail.route(container.id))
```

---

### A14. Launching XServerDisplayActivity (Running a Shortcut/Container)

This is the most critical integration point — the moment Compose hands control to the Wine engine. Build the Intent with all required extras and start the Activity.

```kotlin
fun runShortcut(activity: Activity, shortcut: Shortcut) {
    val container = shortcut.container
    val intent = Intent(activity, XServerDisplayActivity::class.java).apply {
        putExtra("container_id", container.id)
        putExtra("shortcut_path", shortcut.file.path)

        // Graphics
        putExtra("graphics_driver", container.graphicsDriver)
        putExtra("graphics_driver_config", shortcut.getExtra(
            "graphicsDriverConfig", container.graphicsDriverConfig))

        // DX wrapper
        putExtra("dxwrapper", shortcut.getExtra("dxwrapper", container.dxwrapper))
        putExtra("dxwrapper_config", shortcut.getExtra("dxwrapperConfig", container.dxwrapperConfig))

        // Display
        putExtra("screen_size", shortcut.getExtra("screenSize", container.screenSize))
        putExtra("display_scale", container.displayScale)

        // Wine / Box64
        putExtra("wine_version", container.wineVersion)
        putExtra("box64_version", shortcut.getExtra("box64Version", container.box64Version))

        // Win components + env vars
        putExtra("wincomponents", shortcut.getExtra("wincomponents", container.winComponents))
        putExtra("envvars", shortcut.getExtra("envVars", container.envVars))

        // CPU
        putExtra("cpu_list", shortcut.getExtra("cpuList", container.cpuList))
        putExtra("cpu_affinity_mask", container.cpuAffinityMask)
    }
    activity.startActivity(intent)
}
```

**Calling it from a Compose screen:**
```kotlin
val activity = LocalContext.current as Activity

Button(onClick = { runShortcut(activity, shortcut) }) {
    Text("Run")
}
```

**Note:** `XServerDisplayActivity` reads all its config from Intent extras on startup. Check `XServerDisplayActivity.java` `onCreate()` to see exactly which extra keys it expects — the list above covers the common ones but your fork may have additions.

---

### A15. The Engine Boundary — What to Never Touch

The Compose migration only affects the UI layer. Everything below this line must not be renamed, restructured, or moved:

**JNI / Native — package paths map to `.so` symbol names:**
```
com.winlator.cmod.xserver.*       — X server native bridge
com.winlator.cmod.winhandler.*    — Wine message handler
com.winlator.cmod.xenvironment.*  — container environment setup
com.winlator.cmod.math.*          — native math bridge
com.winlator.cmod.widget.*        — native surface views
```

If you rename a class in any of these packages, the JNI symbol lookup in the prebuilt `.so` libraries will fail at runtime with `UnsatisfiedLinkError` and Wine will not start.

**Safe to modify UI of, but not rename/move:**
```
XServerDisplayActivity.java       — Wine session host Activity
XServerView.java                  — native surface
InputControlsFragment.java        — complex input view hierarchy
SettingsFragment.java             — PreferenceFragmentCompat
```

**Engine data classes — never rename fields (serialized to JSON/XML):**
```
container/Container.java          — container config, fields map to saved XML keys
container/Shortcut.java           — shortcut config, fields map to .lnk file keys
core/EnvVars.java                 — environment variable store
core/KeyValueSet.java             — general key=value parser
```

**Binaries — never delete or rename:**
```
assets/imagefs/                   — Wine filesystem image
app/src/main/jniLibs/             — prebuilt .so libraries (Box64, FEX, Wine JNI)
```

**Rule of thumb:** If a file has a `native` method or is in a package that a `.so` library references by name, it is off-limits for renaming. The UI layer (Fragments, Activities that only show UI, Dialogs, ViewModels) is safe to rewrite. The engine layer is not.

---

### A16. Post-Migration Testing Checklist

After building the APK, go through this list. These are the areas most likely to have subtle regressions that compile cleanly but break at runtime.

#### Container Management
- [ ] Create a new container — all tabs save correctly (General, Graphics, Audio, CPU, Advanced)
- [ ] Edit an existing container — values load correctly into all dropdowns and fields
- [ ] Screen size "Custom" — width/height fields appear, save, and reload correctly
- [ ] Delete a container — confirmation dialog appears, container is removed from list
- [ ] Container list refreshes after create/edit/delete without requiring app restart

#### Shortcut Management
- [ ] Open ShortcutSettings — all three tabs load (Win Components, Env Vars, Advanced)
- [ ] Win Components — each spinner shows correct value on open, saves on OK
- [ ] Env Vars — existing vars display in EnvVarsView, Add button opens AddEnvVarComposable, new var appears in list
- [ ] Advanced tab — Box64 version dropdown populates (async load), preset applies values
- [ ] Icon picker — selecting an image from gallery updates the shortcut icon
- [ ] Rename shortcut — new name shows in list immediately, `.lnk` file renamed on disk
- [ ] Export shortcut — file written, toast shows path
- [ ] Shortcut properties — play count and playtime display correctly, Reset clears values live without dismissing dialog
- [ ] Clone shortcut — clone appears in list under correct container
- [ ] Delete shortcut — confirmation shows, shortcut removed

#### Graphics Config Dialogs
- [ ] GraphicsDriverConfigDialog opens from ContainerDetail — values load and save
- [ ] GraphicsDriverConfigDialog opens from ShortcutSettings — same behavior
- [ ] DxvkConfigDialog — framerate, async, VKD3D feature level all save
- [ ] WineD3DConfigDialog — GPU name dropdown populates from gpu_cards.json, values save
- [ ] FPS counter config — values save to container

#### Env Vars
- [ ] Add env var with name from Presets dropdown — name auto-fills
- [ ] Add env var with custom name — saves correctly
- [ ] Duplicate name rejected (no duplicate added)
- [ ] Env vars persist after closing and reopening ShortcutSettings

#### Running a Container / Shortcut
- [ ] "Run" button on a shortcut launches XServerDisplayActivity
- [ ] Wine session starts (X server surface appears)
- [ ] Return to app after session — shortcut list is intact

#### Contents Screen
- [ ] Component list loads and displays
- [ ] Download/install flow completes for a small component
- [ ] ContentInfoDialog shows component details
- [ ] ContentUntrustedDialog appears for unsigned components

#### Saves Screen
- [ ] Save list loads for a container
- [ ] Create new save — appears in list
- [ ] Edit save name — updates in list
- [ ] Export save — file written

#### Splash / First Run
- [ ] Fresh install shows SplashScreen with progress bar
- [ ] imagefs installs without hanging
- [ ] App proceeds to ContainersScreen after install

#### Fragments (kept as Java)
- [ ] InputControls screen loads — no ActionBar crash
- [ ] Settings screen loads — no ActionBar crash
- [ ] Settings changes persist (dark mode toggle, etc.)
- [ ] Dark mode toggle in Settings immediately changes the Compose theme without restart

#### Shortcuts — New Features (Jobs 5–8)
- [ ] Sort button opens dropdown with Name A→Z, Name Z→A, Container options
- [ ] Selected sort order persists after closing and reopening the app
- [ ] Import button → container picker appears; selecting a container → file picker opens
- [ ] Picked `.desktop` file is copied to the selected container; shortcut appears in list
- [ ] `container_id` in the imported file is rewritten to match the selected container
- [ ] Grid/list toggle button switches between layouts with a crossfade
- [ ] Grid view shows 2 columns with large icon, name, and container label
- [ ] Grid/list preference persists after closing and reopening the app
- [ ] All overflow menu actions (Settings, Remove, Clone, Export, Properties) work in grid mode

#### Containers — New Features (Job 6)
- [ ] Export (3-dot menu) → toast confirms path; file exists in Downloads/Winlator/Backups/Containers/
- [ ] Import (toolbar button) → lists available backup dirs; selecting one imports and shows new container
- [ ] Imported container settings match the exported original

#### XML Layout Text Colours (common regression)
- [ ] Input Controls → "External Controllers" section header is readable (not dark on dark)
- [ ] Input Controls → connected controller name (e.g. "Microsoft X-Box 360 pad") is readable
- [ ] Grep remaining XML layouts for `@color/colorPrimary` as `textColor` — fix any found

#### Drives Tab (ContainerDetail)
- [ ] Add a drive — row appears with letter dropdown, path field, browse + remove buttons
- [ ] Drive letter dropdown opens on tap (ExposedDropdownMenuBox + menuAnchor only, no competing onClick)
- [ ] Changing letter updates the drive entry
- [ ] Browse button opens folder picker, selected path populates field
- [ ] Remove button removes the drive row

---

### A17. AppThemeState — Dynamic Theme System

`AppThemeState` is a Kotlin `object` singleton that drives the entire theme. It must be initialized once in `MainActivity.onCreate()` before `setContent {}` is called.

**Why a singleton and not a ViewModel?** The theme must be accessible from both Compose screens and non-Compose code (e.g. `AppearanceScreen` calling `setPreset()`). A ViewModel is scoped to a NavBackStackEntry — a singleton is accessible anywhere.

**Full implementation:**

```kotlin
object AppThemeState {
    private lateinit var themePrefs: SharedPreferences
    private lateinit var appPrefs: SharedPreferences
    // Hold strong reference — SharedPreferences uses WeakReference for listeners
    private var prefListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val _presetIndex  = MutableStateFlow(0)
    private val _customAccent = MutableStateFlow(Color(0xFF8B6BE0))
    private val _isDarkMode   = MutableStateFlow(true)

    val presetIndex:  StateFlow<Int>     = _presetIndex
    val customAccent: StateFlow<Color>   = _customAccent
    val isDarkMode:   StateFlow<Boolean> = _isDarkMode

    val colorScheme: Flow<ColorScheme> =
        combine(_presetIndex, _customAccent, _isDarkMode) { index, accent, dark ->
            val preset   = themePresets.getOrElse(index) { themePresets.first() }
            val override = if (index == CUSTOM_PRESET_INDEX) accent else null
            if (dark) preset.toColorScheme(accentOverride = override)
            else      preset.toLightColorScheme(accentOverride = override)
        }

    fun init(context: Context) {
        themePrefs = context.getSharedPreferences("winlator_theme", Context.MODE_PRIVATE)
        appPrefs   = PreferenceManager.getDefaultSharedPreferences(context)

        _presetIndex.value  = themePrefs.getInt("preset_index", 0).coerceIn(0, themePresets.size - 1)
        _customAccent.value = Color(themePrefs.getInt("custom_accent", Color(0xFF8B6BE0).toArgb()))
        _isDarkMode.value   = appPrefs.getBoolean("dark_mode", true)

        // React to SettingsFragment dark_mode toggle in real time — no restart needed
        prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "dark_mode") _isDarkMode.value = appPrefs.getBoolean("dark_mode", true)
        }
        appPrefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    fun setPreset(index: Int) {
        _presetIndex.value = index.coerceIn(0, themePresets.size - 1)
        themePrefs.edit().putInt("preset_index", _presetIndex.value).apply()
    }

    fun setCustomAccent(color: Color) {
        _customAccent.value = color
        _presetIndex.value  = CUSTOM_PRESET_INDEX
        themePrefs.edit()
            .putInt("custom_accent", color.toArgb())
            .putInt("preset_index", CUSTOM_PRESET_INDEX)
            .apply()
    }

    // Synchronous snapshot — used as `initial` in collectAsState() to avoid first-frame flash
    fun currentColorSchemeSnapshot(): ColorScheme {
        val index    = _presetIndex.value
        val preset   = themePresets.getOrElse(index) { themePresets.first() }
        val override = if (index == CUSTOM_PRESET_INDEX) _customAccent.value else null
        return if (_isDarkMode.value) preset.toColorScheme(accentOverride = override)
               else                   preset.toLightColorScheme(accentOverride = override)
    }
}
```

**In WinlatorTheme:**
```kotlin
@Composable
fun WinlatorTheme(content: @Composable () -> Unit) {
    val colorScheme by AppThemeState.colorScheme.collectAsState(
        initial = AppThemeState.currentColorSchemeSnapshot()
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

**In AppearanceScreen (user changes preset):**
```kotlin
AppThemeState.setPreset(selectedIndex)
AppThemeState.setCustomAccent(pickedColor)
```

Changes are immediately reflected across the entire app with no recomposition triggers needed beyond what `collectAsState()` already handles.

---

### A18. PreloaderState — Global Loading Overlay

When long-running operations need to block the UI (container creation, saves, installs), use `PreloaderState` — a global `object` that drives a Compose overlay rendered at the root of the screen tree.

**Why not a ViewModel?** The overlay must be triggerable from both Compose code (ViewModels) and legacy Java code (`PreloaderDialog.java` still calls `show()`/`hide()` in some paths). A `@JvmStatic` singleton bridges both worlds.

**PreloaderState.kt:**
```kotlin
object PreloaderState {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    @JvmStatic fun show(t: String? = null) { _text.value = t ?: "" }
    @JvmStatic fun hide()                  { _text.value = null }
    @JvmStatic fun isVisible(): Boolean    = _text.value != null
}
```

**PreloaderOverlay composable (rendered in MainActivity's Box root):**
```kotlin
@Composable
fun PreloaderOverlay() {
    val text by PreloaderState.text.collectAsState()
    if (text != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                if (text!!.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(text!!, color = Color.White)
                }
            }
        }
    }
}
```

**In MainActivity — render at the root Box so it overlays everything:**
```kotlin
setContent {
    WinlatorTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            AppShell(…)
            PreloaderOverlay()   // always on top
        }
    }
}
```

**Triggering from a Kotlin ViewModel:**
```kotlin
fun confirm(onComplete: () -> Unit) {
    if (isSaving) return
    isSaving = true
    PreloaderState.show()
    viewModelScope.launch {
        // do work…
        PreloaderState.hide()
        isSaving = false
        onComplete()
    }
}
```

**Triggering from legacy Java:**
```java
// PreloaderDialog.java — delegates to PreloaderState
PreloaderState.show("Loading…");
// … work …
PreloaderState.hide();
```

---

## Part C — Post-Report Bug Fixes

Bugs found during device testing after the initial migration was declared complete. Included here so future migrators know what to watch for.

| Commit | File | Bug | Root Cause | Fix |
|---|---|---|---|---|
| `85b1e57` | `external_controller_list_item.xml` | Controller name text invisible on dark background | `TVTitle` used `android:textColor="@color/colorPrimary"` which is `#262626` (near-black) | Changed to `#E6E6E6` |
| `85b1e57` | `ContainerDetailScreen.kt` | Drive letter dropdown unresponsive, never opened | `CompactDropdown` used `OutlinedCard(onClick = { expanded = !expanded })` + `menuAnchor()` — both handlers fired on same tap, cancelling each other | Removed `onClick` from `OutlinedCard`; `menuAnchor()` is now sole click handler |
| `6537038` | `input_controls_fragment.xml` | "External Controllers" section header invisible on dark background | Header `TextView` used `android:textColor="@color/colorPrimary"` (`#262626`) | Changed to `#E6E6E6` |

### Pattern: colorPrimary as text colour
All three visual bugs had the same root cause: `@color/colorPrimary = #262626` used as `android:textColor` in XML layouts. When the Compose dark theme is applied to the Activity, the background is dark but these XML-rendered text views inherited a near-black text colour from the legacy colour palette.

**Grep to catch them all before testing:**
```bash
grep -rn 'textColor.*colorPrimary\|colorPrimary.*textColor' app/src/main/res/layout/
```

Fix every match by replacing with `#E6E6E6` or a dedicated string resource.

---

## Part D — Post-Migration Feedback Fixes (2026-04-17)

After device testing against the original Java/XML version, 8 issues were identified and fixed. All 8 jobs are complete as of commit `546d25e`, CI run `24577265773`.

---

### D1. Fix Summary Table

| Job | Commit | Issue | Files Changed |
|---|---|---|---|
| 1 — Help & Support | `93d0326` | Tapping Help did nothing (TODO stub) | `AppDrawer.kt` |
| 2 — About dialog | `d18cae6` | About dialog showed placeholder text; `BuildConfig` unresolved | `AppDrawer.kt`, `build.gradle`, `MainActivity.kt` |
| 3 — Save overlay | `2e5f4a1`, `67844d2` | No loading state while container was being created/saved | `ContainerDetailViewModel.kt`, `ContainerDetailScreen.kt` |
| 4 — Dark mode pref | `44a4bdb` | Dark mode toggle in Settings had no effect on Compose theme | `AppThemeState.kt`, `ThemePreset.kt`, `FragmentScreen.kt` |
| 5 — Sort shortcuts | `00dc6a5` | No sort option — shortcuts appeared in filesystem order | `ShortcutsViewModel.kt`, `ShortcutsScreen.kt` |
| 6 — Import/Export container | `8477b65` | Import/export container was missing from Containers screen | `ContainersViewModel.kt`, `ContainersScreen.kt` |
| 7 — Import shortcut | `546d25e` | No way to import a `.desktop` shortcut file from storage | `ShortcutsViewModel.kt`, `ShortcutsScreen.kt` |
| 8 — Grid/list toggle | `546d25e` | Shortcuts list-only; original had grid/list toggle | `ShortcutsViewModel.kt`, `ShortcutsScreen.kt` |

Plus 4 visual polish fixes done between Job 6 and Job 7:

| Commit | Issue | Fix |
|---|---|---|
| `beee77b` | Appearance option missing from nav drawer | Added to `AppDrawer.kt` hardcoded items |
| Theme fix | Drawer accent color static, didn't follow theme | `AppDrawer.kt`: replaced `Primary` static import with `MaterialTheme.colorScheme.primary` |
| Theme fix | FAB at wrong position (center instead of bottom-right) | `ContainersScreen.kt`, `ShortcutsScreen.kt`: `Box(weight(1f))` → `Box(weight(1f).fillMaxWidth())` |
| Theme fix | Contents/AdrenoTools/Saves buttons showed old static primary color | All files: `Primary` static import → `MaterialTheme.colorScheme.primary` |

---

### D2. Job Detail: Help & Support

**Problem:** `AppDrawer.kt` had `onClick = { /* TODO: open help URL */ }` — tapping did nothing.

**Fix:** Replaced with a Compose `AlertDialog` showing the GitHub repo link with an `Intent(ACTION_VIEW)` button:

```kotlin
var showHelpDialog by remember { mutableStateOf(false) }

NavigationDrawerItem(
    label = { Text("Help & Support") },
    onClick = { showHelpDialog = true }
)

if (showHelpDialog) {
    AlertDialog(
        title = { Text("Help & Support") },
        text = { Text("Report issues at the GitHub repository.") },
        confirmButton = {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
            }) { Text("Open GitHub") }
        },
        dismissButton = { TextButton(onClick = { showHelpDialog = false }) { Text("Close") } },
        onDismissRequest = { showHelpDialog = false }
    )
}
```

---

### D3. Job Detail: About Dialog + BuildConfig Fix

**Problem 1:** The About dialog showed a placeholder version string.

**Problem 2:** `BuildConfig.VERSION_NAME` and `BuildConfig.VERSION_CODE` caused a compile error — `Unresolved reference 'BuildConfig'`. Even after adding the import, the class didn't exist.

**Root cause:** AGP disables `BuildConfig` generation by default in newer versions.

**Fix — `app/build.gradle`:**
```groovy
buildFeatures {
    compose true
    buildConfig true   // ADD THIS — AGP won't generate BuildConfig without it
}
```

**Fix — MainActivity.kt:** Add the import after enabling buildConfig:
```kotlin
import com.winlator.cmod.BuildConfig
```

**Fix — About dialog:**
```kotlin
AlertDialog(
    title = { Text("About") },
    text = {
        Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n\nBuilt on …")
    },
    …
)
```

---

### D4. Job Detail: Container Save Loading Overlay

**Problem:** Tapping the save FAB on ContainerDetail gave no feedback while the container was being created/saved asynchronously. The FAB remained enabled, allowing double-taps.

**Fix — `ContainerDetailViewModel.kt`:**
```kotlin
var isSaving by mutableStateOf(false)
    private set

fun confirm(onComplete: () -> Unit) {
    if (isSaving) return
    isSaving = true
    PreloaderState.show()
    viewModelScope.launch {
        doConfirm {
            isSaving = false
            PreloaderState.hide()
            onComplete()
        }
    }
}
```

**Fix — `ContainerDetailScreen.kt` — dim the FAB while saving:**
```kotlin
FloatingActionButton(
    onClick = { if (!viewModel.isSaving) viewModel.confirm { navController.popBackStack() } },
    containerColor = if (viewModel.isSaving)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.primary
) {
    Icon(Icons.Default.Check, contentDescription = "Save")
}
```

---

### D5. Job Detail: Dark Mode Preference Wiring

**Problem:** The dark mode toggle in SettingsFragment had no effect. `WinlatorTheme` always used `darkColorScheme()` hardcoded.

**Problem 2:** `SettingsFragment` used the light XML `AppTheme`, so the Settings screen looked completely different from the rest of the app.

**Fix — `AppThemeState.kt` — read pref and register listener:**
```kotlin
object AppThemeState {
    private val _isDarkMode = MutableStateFlow(
        PreferenceManager.getDefaultSharedPreferences(app)
            .getBoolean("dark_mode", false)
    )
    val isDarkMode: StateFlow<Boolean> = _isDarkMode

    init {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "dark_mode") {
                _isDarkMode.value = prefs.getBoolean("dark_mode", false)
            }
        }
    }

    val colorScheme: Flow<ColorScheme> =
        combine(_presetIndex, _customAccent, _isDarkMode) { index, accent, dark ->
            val preset = themePresets.getOrElse(index) { themePresets.first() }
            val override = if (index == CUSTOM_PRESET_INDEX) accent else null
            if (dark) preset.toColorScheme(accentOverride = override)
            else      preset.toLightColorScheme(accentOverride = override)
        }
}
```

**Fix — `ThemePreset.kt` — add light variant:**
```kotlin
fun toLightColorScheme(accentOverride: Color? = null): ColorScheme {
    val primary = accentOverride ?: this.primary
    return lightColorScheme(
        primary = primary,
        onPrimary = Color.White,
        surface = Color(0xFFF5F5F5),
        onSurface = Color(0xFF1C1C1C),
        background = Color(0xFFFFFFFF),
        // …
    )
}
```

**Fix — `FragmentScreen.kt` — wrap with dark context so SettingsFragment matches the app:**
```kotlin
AndroidView(
    factory = { ctx ->
        val themedCtx = ContextThemeWrapper(ctx, R.style.AppTheme_Dark)
        FragmentContainerView(themedCtx).apply { id = View.generateViewId() }
    },
    …
)
```

---

### D6. Job Detail: Sort Shortcuts List

**Problem:** No sort option existed — shortcuts always appeared in filesystem order.

**Fix — `ShortcutsViewModel.kt`:**
```kotlin
enum class ShortcutSortOrder { NAME_ASC, NAME_DESC, CONTAINER }

private val _sortOrder = MutableStateFlow(
    ShortcutSortOrder.entries[
        prefs.getInt("sort_order", ShortcutSortOrder.NAME_ASC.ordinal)
            .coerceIn(0, ShortcutSortOrder.entries.size - 1)
    ]
)
val sortOrder: StateFlow<ShortcutSortOrder> = _sortOrder

// shortcuts is now a Flow, not StateFlow
val shortcuts: Flow<List<Shortcut>> =
    combine(_shortcuts, _sortOrder) { list, order ->
        when (order) {
            ShortcutSortOrder.NAME_ASC   -> list.sortedBy { it.name.lowercase() }
            ShortcutSortOrder.NAME_DESC  -> list.sortedByDescending { it.name.lowercase() }
            ShortcutSortOrder.CONTAINER  -> list.sortedBy { (it.container?.name ?: "").lowercase() }
        }
    }

fun setSortOrder(order: ShortcutSortOrder) {
    _sortOrder.value = order
    prefs.edit().putInt("sort_order", order.ordinal).apply()
}
```

**Fix — `ShortcutsScreen.kt`:** Changed `collectAsState()` to `collectAsState(initial = emptyList())` since `shortcuts` is now a `Flow` not a `StateFlow`. Added a sort dropdown in the toolbar.

**Key lesson:** When changing a `StateFlow` to a `Flow` (e.g. via `combine()`), always pass an `initial` value to `collectAsState()` or the screen will show nothing until the first emission.

---

### D7. Job Detail: Import/Export Container

**Problem:** The old `ContainersFragment` had import/export. The Compose version had neither.

**Fix — `ContainersViewModel.kt`:** Wraps existing `ContainerManager` Java methods:
```kotlin
fun exportContainer(container: Container, onDone: (String?) -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
        val path = manager.exportContainer(container)
        withContext(Dispatchers.Main) { onDone(path) }
    }
}

fun importContainer(dir: File, onDone: () -> Unit) {
    viewModelScope.launch(Dispatchers.IO) {
        manager.importContainer(dir)
        withContext(Dispatchers.Main) {
            refresh()
            onDone()
        }
    }
}

fun availableBackups(): List<File> = manager.availableBackups() ?: emptyList()
```

**Fix — `ContainersScreen.kt`:**
- Export added to each container's 3-dot dropdown menu
- Import button added to the top bar; tapping opens an `AlertDialog` listing backup directories from `availableBackups()`

---

### D8. Job Detail: Import Shortcut

**Problem:** No way to bring a `.desktop` shortcut file from external storage into a container.

**Fix — `ShortcutsViewModel.kt`:**
```kotlin
fun importShortcut(containerIndex: Int, uri: Uri, context: Context) {
    val containers = manager.getContainers()
    if (containerIndex >= containers.size) return
    val container = containers[containerIndex]
    val destDir = container.getDesktopDir()
    if (!destDir.exists()) destDir.mkdirs()
    val fileName = DocumentFile.fromSingleUri(context, uri)?.name ?: "imported.desktop"
    val dest = File(destDir, fileName)
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
        // Rewrite container_id to match the selected container
        val lines = dest.readLines().map { line ->
            if (line.startsWith("container_id:")) "container_id:${container.id}" else line
        }
        dest.writeText(lines.joinToString("\n") + "\n")
    }
    refresh()
}
```

**Fix — `ShortcutsScreen.kt`:** The import flow has two steps — first pick a container, then pick a file. Both steps must be wired up at the top level because `rememberLauncherForActivityResult` must be called unconditionally:

```kotlin
var showImportContainerPicker by remember { mutableStateOf(false) }
var pendingImportContainerIndex by remember { mutableStateOf(-1) }

// Register unconditionally — launch only when container is selected
val importFileLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri ->
    uri ?: return@rememberLauncherForActivityResult
    if (pendingImportContainerIndex >= 0) {
        vm.importShortcut(pendingImportContainerIndex, uri, context)
        pendingImportContainerIndex = -1
    }
}
```

---

### D9. Job Detail: Grid/List Layout Toggle

**Problem:** Shortcuts were list-only. The original app had a grid/list toggle.

**Fix — `ShortcutsViewModel.kt`:**
```kotlin
private val _isGridView = MutableStateFlow(prefs.getBoolean("is_grid_view", false))
val isGridView: StateFlow<Boolean> = _isGridView

fun setGridView(grid: Boolean) {
    _isGridView.value = grid
    prefs.edit().putBoolean("is_grid_view", grid).apply()
}
```

**Fix — `ShortcutsScreen.kt`:** Toggle button in toolbar switches icon (GridView ↔ ViewList). `AnimatedContent` crossfades between layouts:

```kotlin
AnimatedContent(targetState = isGridView, label = "layout") { grid ->
    if (grid) {
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize()) {
            items(shortcuts, key = { it.file.path }) { shortcut ->
                ShortcutGridItem(shortcut, …)
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shortcuts, key = { it.file.path }) { shortcut ->
                ShortcutItem(shortcut, …)
                Divider(color = DividerColor)
            }
        }
    }
}
```

Added `ShortcutGridItem` composable — shows 64dp icon centered, name (2 lines max), container label, and 3-dot overflow menu in the top-right corner of the icon.

---

### D10. New Gotchas Discovered During Feedback Fixes

These are additions to the existing gotcha list in section A11.

#### Gotcha 13: `Box(weight(1f))` inside a `Column` needs explicit `fillMaxWidth()`

`Box` inside a `Column` with `Modifier.weight(1f)` fills vertically but defaults to **wrap-content width**. This causes two symptoms:

1. `align(Alignment.BottomEnd)` anchors to the wrapped width instead of the screen edge → FAB appears in the wrong position
2. `LazyColumn` inside the Box inherits the wrap-content width → text items get cut off

**Wrong:**
```kotlin
Column(modifier = Modifier.fillMaxSize()) {
    Box(modifier = Modifier.weight(1f)) { // wrap-content width!
        LazyColumn(modifier = Modifier.fillMaxSize()) { … }
        FloatingActionButton(modifier = Modifier.align(Alignment.BottomEnd)) { … }
    }
}
```

**Fix:**
```kotlin
Box(modifier = Modifier.weight(1f).fillMaxWidth()) { … }
```

#### Gotcha 14: Static theme color constants never update with the theme

Defining `val Primary = Color(0xFF8B6BE0)` in `Color.kt` as a top-level constant and importing it in composables seems convenient, but it never reacts to theme changes. When the user switches preset or toggles dark mode, composables reading `Primary` directly keep showing the old color.

**Wrong — anywhere in a composable:**
```kotlin
import com.winlator.cmod.ui.theme.Primary

Icon(tint = Primary) // static, never updates
```

**Fix — always use MaterialTheme inside composables:**
```kotlin
Icon(tint = MaterialTheme.colorScheme.primary) // reactive
```

The static constants (`Primary`, `Surface`, `OnSurface`, etc.) in `Color.kt` are only used by `Theme.kt` when building the `ColorScheme` object. They should never be imported directly into composable functions.

#### Gotcha 15: `buildConfig true` required in newer AGP

AGP 8.x disabled `BuildConfig` generation by default. If your project uses `BuildConfig.VERSION_NAME`, `BuildConfig.DEBUG`, or any other `BuildConfig` field, you must opt back in:

```groovy
android {
    buildFeatures {
        compose true
        buildConfig true   // without this, the class is never generated
    }
}
```

The error `Unresolved reference 'BuildConfig'` even after adding the import is a symptom of this being missing.

#### Gotcha 16: `SharedPreferences.OnSharedPreferenceChangeListener` must be held strongly

The listener registered with `registerOnSharedPreferenceChangeListener` is stored as a `WeakReference` by Android. If you register it as a lambda and don't hold a strong reference, it gets garbage-collected and stops firing:

```kotlin
// WRONG — lambda is collected immediately
prefs.registerOnSharedPreferenceChangeListener { _, key -> … }

// RIGHT — store the listener as a field
private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
    if (key == "dark_mode") _isDarkMode.value = prefs.getBoolean(key, false)
}

init {
    prefs.registerOnSharedPreferenceChangeListener(prefListener)
}
```

#### Gotcha 17: Changing `StateFlow` to `Flow` (via `combine`) breaks `collectAsState()`

When a ViewModel property is converted from `StateFlow` to `Flow` using `combine()`, it loses its `value` property and its guaranteed initial emission. `collectAsState()` without an `initial` argument will throw a compile error or emit nothing until the flow produces its first value.

```kotlin
// StateFlow — collectAsState() works without initial
val shortcuts: StateFlow<List<Shortcut>> = _shortcuts

// Flow (from combine) — MUST supply initial
val shortcuts: Flow<List<Shortcut>> = combine(_shortcuts, _sortOrder) { … }
```

```kotlin
// In the screen:
val shortcuts by vm.shortcuts.collectAsState(initial = emptyList()) // required
```

#### Gotcha 18: `rememberLauncherForActivityResult` must be called unconditionally at the top level

Activity result launchers are composable infrastructure — they must be registered at the top of the composition tree, not inside dialogs or conditional branches. If you need a launcher that is only *triggered* from inside a dialog, register it at the top of the screen composable and store the "pending state" in a `remember` variable.

```kotlin
// WRONG — inside an if block
if (showDialog) {
    val launcher = rememberLauncherForActivityResult(…) { … } // will crash
}

// RIGHT — register unconditionally at top level
var pendingContainerIndex by remember { mutableStateOf(-1) }
val launcher = rememberLauncherForActivityResult(…) { uri ->
    if (pendingContainerIndex >= 0) {
        vm.importShortcut(pendingContainerIndex, uri, context)
    }
}

// Trigger from inside dialog:
TextButton(onClick = {
    pendingContainerIndex = index
    launcher.launch("*/*")
})
```

---

## Part B — What Was Replaced and With What

### B1. Navigation Screens Replaced

| Removed (Java Fragment + XML) | Replaced With (Kotlin Compose) |
|---|---|
| `MainActivity.java` + XML drawer/nav | `MainActivity.kt` + `NavGraph.kt` + Compose NavDrawer |
| `ContainersFragment.java` + `containers_fragment.xml` | `ContainersScreen.kt` + `ContainersViewModel.kt` |
| `ShortcutsFragment.java` + `shortcuts_fragment.xml` | `ShortcutsScreen.kt` + `ShortcutsViewModel.kt` |
| `ContainerDetailFragment.java` + `container_detail_fragment.xml` | `ContainerDetailScreen.kt` + `ContainerDetailViewModel.kt` |
| `ContentsFragment.java` + `contents_fragment.xml` | `ContentsScreen.kt` + `ContentsViewModel.kt` |
| `SavesFragment.java` + `saves_fragment.xml` | `SavesScreen.kt` + `SavesViewModel.kt` |
| `AdrenotoolsFragment.java` + `adrenotools_fragment.xml` | `AdrenoToolsScreen.kt` |
| `Box86_64RCFragment.java` + `box86_64_rc_fragment.xml` | Removed entirely (dead feature) |
| — | `SplashScreen.kt` + `SplashViewModel.kt` (new, replaced `DownloadProgressDialog`) |
| — | `FileManagerScreen.kt` (new Compose file picker) |

**Kept as Java Fragments (too complex to convert, hosted via `FragmentScreen`):**
- `InputControlsFragment.java`
- `SettingsFragment.java`

---

### B2. Dialogs Replaced

#### Container Detail Dialogs

| Removed (Java + XML Layout) | Replaced With (Compose) | Location |
|---|---|---|
| `GraphicsDriverConfigDialog.java` (UI) + `graphics_driver_config_dialog.xml` | `internal fun GraphicsDriverConfigDialog()` | `ContainerDetailScreen.kt` |
| `DXVKConfigDialog.java` (UI) + `dxvk_config_dialog.xml` | `internal fun DxvkConfigDialog()` | `ContainerDetailScreen.kt` |
| `VKD3DConfigDialog.java` + `vkd3d_config_dialog.xml` | Merged into `DxvkConfigDialog()` composable | `ContainerDetailScreen.kt` |
| `WineD3DConfigDialog.java` (UI) + `wined3d_config_dialog.xml` | `internal fun WineD3DConfigDialog()` | `ContainerDetailScreen.kt` |
| `VirGLConfigDialog.java` + `virgl_config_dialog.xml` | Removed (VirGL deprecated) | — |
| `FPSCounterConfigDialog.java` + `fps_counter_config_dialog.xml` | `internal fun FpsCounterConfigDialog()` | `ContainerDetailScreen.kt` |
| `AddEnvVarDialog.java` + `add_env_var_dialog.xml` | `internal fun AddEnvVarComposable()` | `ContainerDetailScreen.kt` |
| `ExtensionPickerDialog` (inline in fragment) | `internal fun ExtensionPickerDialog()` | `ContainerDetailScreen.kt` |

#### Shortcut Dialogs

| Removed (Java + XML Layout) | Replaced With (Compose) | Location |
|---|---|---|
| `ShortcutSettingsDialog.java` + `shortcut_settings_dialog.xml` | `ShortcutSettingsDialogScreen()` — full 3-tab Compose Dialog | `ShortcutsScreen.kt` |
| `shortcut_properties_dialog.xml` + inline XML dialog in `showProperties()` | Inline Compose `AlertDialog` with `propertiesShortcut` state | `ShortcutsScreen.kt` |

**ShortcutSettingsDialog tabs (all Compose):**
- **Win Components** — `ScWinComponentsTab()` with DirectX/General sections
- **Env Vars** — `ScEnvVarsTab()` with `AndroidView(EnvVarsView)` + `AddEnvVarComposable`
- **Advanced** — `ScAdvancedTab()` with Box64, FEXCore, controls profile, `AndroidView(CPUListView)`, sharpness sliders

#### Contents / Store Dialogs

| Removed (Java + XML Layout) | Replaced With (Compose) | Location |
|---|---|---|
| `ContentInfoDialog.java` + `content_info_dialog.xml` | Inline Compose `AlertDialog` two-phase flow | `ContentsScreen.kt` |
| `ContentUntrustedDialog.java` + `content_untrusted_dialog.xml` | Inline Compose `AlertDialog` | `ContentsScreen.kt` |
| `StorageInfoDialog.java` + `container_storage_info_dialog.xml` | Inline Compose `AlertDialog` | `ContentsScreen.kt` |
| `winetricks_content_dialog.xml` | Removed (Winetricks UI not carried forward) | — |

#### Saves Dialogs

| Removed (Java + XML Layout) | Replaced With (Compose) | Location |
|---|---|---|
| `SaveEditDialog.java` + `save_edit_dialog.xml` | Inline Compose `AlertDialog` | `SavesScreen.kt` |
| `SaveSettingsDialog.java` + `save_settings_dialog.xml` | Inline Compose `AlertDialog` | `SavesScreen.kt` |
| `saves_list_item.xml` | Compose `LazyColumn` item | `SavesScreen.kt` |

#### Splash / Install Dialog

| Removed (Java + XML Layout) | Replaced With (Compose) | Location |
|---|---|---|
| `PreloaderDialog.java` + `preloader_dialog.xml` + `download_progress_dialog.xml` | Full-screen Compose overlay (`SplashScreen.kt`) | `SplashScreen.kt` |

#### Gamepad / Input Dialogs

| Removed (Java + XML Layout) | Replaced With | Notes |
|---|---|---|
| `GamepadConfiguratorDialog.java` + `dialog_gamepad_configurator.xml` | Removed | Feature not carried forward |
| `ImportGroupDialog.java` + `box86_64_rc_groups_dialog.xml` | Removed | Was part of Box86_64RC (removed screen) |
| `gyro_config_dialog.xml` | Removed | — |
| `touchpad_help_dialog.xml` | Removed | — |

---

### B3. Helper / Utility Classes Deleted

| Deleted File | Reason |
|---|---|
| `ContainerDetailHelper.java` | Was a bridge for `ShortcutSettingsDialog`; no callers once dialog was replaced |
| `TerminalActivity.java` | Dead activity, not reachable from any Compose screen |
| `RestoreActivity.java` | Dead activity |
| `WinetricksFloatingView.java` | Winetricks UI not carried forward |
| `saves/CustomFilePickerActivity.java` + `saves/FileAdapter.java` | Replaced by Compose `FileManagerScreen.kt` + system file picker |
| `saves/Save.java` + `saves/SaveManager.java` | Replaced by `SavesViewModel.kt` logic |
| `box86_64/rc/` (5 files) | Entire Box86_64RC feature removed |
| `xenvironment/components/VortekRendererComponent.java` | Dead component |
| `xenvironment/components/BionicProgramLauncherComponent.java` | Dead component |
| `xenvironment/components/GlibcProgramLauncherComponent.java` | Dead component |
| `xenvironment/components/NetworkInfoUpdateComponent.java` | Dead component |
| `core/Win32AppWorkarounds.java` | Dead utility |
| `inputcontrols/PreferenceKeys.java` | Dead utility |
| `store/LudashiLaunchBridge.java` | Dead bridge |

---

### B4. What Remains (Intentionally)

#### Java files kept — utility static methods still in use

| File | Used By | What It Provides |
|---|---|---|
| `ContentDialog.java` | `InputControlsFragment`, `SettingsFragment`, `XServerDisplayActivity`, `TaskManagerDialog` | Base dialog class + static helpers (`confirm`, `prompt`, `alert`) |
| `GraphicsDriverConfigDialog.java` | `AdrenotoolsManager`, `XServerDisplayActivity`, `ContainerDetailViewModel` | Static: `parseGraphicsDriverConfig()`, `toGraphicsDriverConfig()`, `getVersion()` |
| `DXVKConfigDialog.java` | `ContainerDetailViewModel`, `ShortcutsScreen` | Static: `parseConfig()`, `setEnvVars()`, `loadDxvkVersionList()`, `compareVersion()` |
| `WineD3DConfigDialog.java` | `ContainerDetailViewModel` | Static: `parseConfig()`, `setEnvVars()`, `loadGpuNames()` |

#### Java dialogs kept — only called from `XServerDisplayActivity` (in-game, not Compose)

| File | Trigger |
|---|---|
| `ActiveWindowsDialog.java` | In-game window list button |
| `DebugDialog.java` | In-game debug log overlay |
| `ScreenEffectDialog.java` | In-game screen effects button |
| `FSRControlFloatingDialog.java` | In-game FSR floating overlay |

#### Fragments kept — hosted via `FragmentScreen` wrapper

| File | Reason |
|---|---|
| `InputControlsFragment.java` | Complex custom view hierarchy; kept as-is |
| `SettingsFragment.java` | PreferenceFragmentCompat; kept as-is |

---

### B5. Reusable Internal Composables

Composables promoted to `internal` visibility so they can be shared across screens within the same module:

| Composable | Defined In | Used In |
|---|---|---|
| `SectionBox()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen`, `ShortcutsScreen` |
| `LabeledDropdown()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen`, `ShortcutsScreen` |
| `GraphicsDriverConfigDialog()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen`, `ShortcutsScreen` |
| `DxvkConfigDialog()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen`, `ShortcutsScreen` |
| `WineD3DConfigDialog()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen`, `ShortcutsScreen` |
| `FpsCounterConfigDialog()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen` |
| `AddEnvVarComposable()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen`, `ShortcutsScreen` |
| `ExtensionPickerDialog()` | `ContainerDetailScreen.kt` | `ContainerDetailScreen` |

---

### B6. Key Compose Patterns Used

| Pattern | Used For |
|---|---|
| `LazyColumn` | All list screens (containers, shortcuts, contents, saves) |
| `LazyVerticalGrid(GridCells.Fixed(n))` | Shortcuts grid view (Job 8) |
| `AlertDialog` | All confirmation, settings, and info dialogs |
| `Dialog(DialogProperties(usePlatformDefaultWidth = false))` | Full-screen dialogs (ShortcutSettings, SplashScreen) |
| `TabRow` + `HorizontalPager` | ContainerDetail tabs, ShortcutSettings tabs |
| `AndroidView` | `EnvVarsView`, `CPUListView` — Java views with no Compose equivalent |
| `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())` | Icon picker in ShortcutSettings; shortcut + container import |
| `LaunchedEffect + withContext(Dispatchers.IO)` | Async spinner loading (Box64 versions, FEXCore, controls profiles, MIDI) |
| `SnapshotStateList` | Win components state in ShortcutSettings |
| `collectAsState()` on `StateFlow` | All ViewModels → screen state binding |
| `collectAsState(initial = emptyList())` on `Flow` | Sorted shortcuts (combine() flow has no initial value) |
| `combine(_flowA, _flowB) { … }` | Reactive sort: `_shortcuts` + `_sortOrder` → sorted list |
| `AnimatedContent(targetState = …)` | Crossfade between list and grid layouts (shortcuts) |
| `internal fun` composables | Shared dialogs reused across multiple screens |
| `MaterialTheme.colorScheme.primary` (not static `Primary`) | All tint/color references inside composables — reacts to theme changes |
| `SharedPreferences.OnSharedPreferenceChangeListener` (held as field) | Live dark mode updates without app restart |
| `ContextThemeWrapper(ctx, R.style.AppTheme_Dark)` | Force dark theme on XML `FragmentContainerView` (Settings, InputControls) |

---

### B7. Summary Stats

| Metric | Count |
|---|---|
| Java Fragments deleted | 7 |
| Fragment XML layouts deleted | 8 |
| Java Dialog classes deleted | 11 |
| Dialog XML layouts deleted | 15 |
| Other Java/utility files deleted | 14 |
| New Kotlin Compose screens | 10 |
| New Kotlin ViewModels | 6 |
| Internal reusable composables | 8 |
| Total lines of Java/XML removed | ~5,000+ |
| Post-migration feedback fix commits | 8 jobs → 9 commits |
| New gotchas documented (Part D) | 6 (Gotchas 13–18) |

---

## Part E — Post-Migration Improvements (2026-04-20)

### E1. AdrenoTools / Turnip Driver SIGSEGV Fix

**Symptom:** GPU extension count showed `0/0` in the graphics driver config dialog instead of the expected `169/169`. The app did not crash but extensions were silently missing.

**Root cause:** The Compose migration moved `GPUInformation` JNI calls onto background coroutine threads (`Dispatchers.IO`). The AdrenoTools `hook_android_dlopen_ext` is not reentrant across threads — concurrent invocations from the main thread and an IO thread triggered a SIGSEGV race condition.

**Rule established:** ALL `GPUInformation` native methods must run on the main thread:
- `enumerateExtensions()` — called inside `LaunchedEffect(version)` with no `withContext`
- `isDriverSupported()` / `getRenderer()` — called inside `LaunchedEffect(Unit)`, outside any `withContext(Dispatchers.IO)` block
- Pure file I/O (`enumarateInstalledDrivers()`, `gpu_cards.json`) stays on `Dispatchers.IO`

**File:** `ContainerDetailScreen.kt` → `GraphicsDriverConfigDialog` composable  
**Commits:** `8035420` (partial), `e22815c` (full fix)  
**CI:** `24662739330` ✅

---

### E2. Developer Feedback Fixes

After device testing, 5 pieces of developer feedback were applied in a single pass.

#### E2 Fix Summary Table

| # | Issue | Fix | Commit |
|---|---|---|---|
| 1 | Appearance in wrong drawer section | Moved `DrawerItem(Screen.Appearance)` from Emulation to Tools section in `AppDrawer.kt` | `0879429` |
| 2 | Action buttons (import, grid toggle, sort) were inline below the list | Moved all action buttons into `AppTopBar` via `LocalTopBarActions` `CompositionLocal` | `0879429` |
| 3 | Shortcut grid used 2-column large tiles | `GridCells.Adaptive(120.dp)` for ~3 columns portrait, more in landscape | `0879429` → `a595b7a` |
| 4 | Grid items used 3-dot menu button | Replaced with `combinedClickable` long-press to open context menu | `0879429` |
| 5 | Top bar buttons disappeared when dialogs opened | `DisposableEffect.onDispose` was firing on dialog open, not just navigation. Fixed by removing `DisposableEffect` and adding `LaunchedEffect(currentRoute)` in `MainActivity` to clear actions only on route change | `afaf051` |

---

### E3. TopAppBar Actions Architecture

Screens push their own action buttons into the shared `AppTopBar` without prop drilling via a `CompositionLocal`:

**`LocalTopBarActions.kt`:**
```kotlin
val LocalTopBarActions = compositionLocalOf<MutableState<@Composable RowScope.() -> Unit>> {
    mutableStateOf({})
}
```

**In `MainActivity.kt`:**
```kotlin
val topBarActionsState = remember { topBarActionsState() }
LaunchedEffect(currentRoute) { topBarActionsState.value = {} }  // clear on navigation only
CompositionLocalProvider(LocalTopBarActions provides topBarActionsState) { ... }
```

**In each screen:**
```kotlin
val topBarActions = LocalTopBarActions.current
SideEffect {  // SideEffect not DisposableEffect — keeps captures fresh every recomposition
    topBarActions.value = { /* IconButtons here */ }
}
```

**Why `SideEffect` not `DisposableEffect`:** `DisposableEffect.onDispose` fires whenever the composable leaves composition — including when a dialog opens on the same screen. This clears the top bar buttons mid-session. `SideEffect` runs on every recomposition and has no `onDispose`, so buttons stay visible while dialogs are open.

---

### E4. Shortcut Grid — Cover Art Layout

Grid tiles were redesigned so the game cover art fills the entire tile (`ContentScale.Crop`) with a vertical gradient scrim at the bottom overlaying the game name and container name in white text.

**`ShortcutGridItem` structure:**
```kotlin
Box(modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))...) {
    Image(contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    Box(
        modifier = Modifier.align(Alignment.BottomStart)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.75f))))
            .padding(8.dp)
    ) {
        Column {
            Text(shortcut.name, color = Color.White, fontWeight = FontWeight.Bold)
            Text(containerName, fontSize = 10.sp, color = Color.White.copy(0.7f))
        }
    }
    DropdownMenu(...)  // long-press context menu
}
```

**Commit:** `a595b7a` CI: `24667712651` ✅

---

### E5. Shortcut List Item — Tech Info Columns

The list view card was extended to show per-shortcut technical settings in the right-hand space, keeping card height unchanged.

**Layout:**
```
[icon]  Game Name          1280x720 · turnip25.1.0   [▶] [⋮]
        Container-1        DXVK 2.3.1 · VKD3D 3.1
```

**Implementation:** A second `Column(horizontalAlignment = Alignment.End)` sits between the name column and action buttons. Top line: resolution + GPU driver version. Bottom line: DXVK version + VKD3D version — both parsed from the single `dxwrapperConfig` key-value string.

```kotlin
val cfgMap = dxwrapperCfg.split(",").mapNotNull {
    val parts = it.split("=", limit = 2)
    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
}.toMap()
val dxvkVersion = cfgMap["version"] ?: ""
val vkd3dVersion = cfgMap["vkd3dVersion"] ?: ""
```

**Commits:** `bdf2657`, `658013f`, `41b9f88` — CI: `24671327547` ✅

---

### E6. New Gotchas (19–22)

| # | Gotcha | Details |
|---|---|---|
| 19 | `GPUInformation` JNI must stay on main thread | `hook_android_dlopen_ext` is not reentrant. Any `GPUInformation.*` call on a background thread races with the main thread and causes SIGSEGV. Use `LaunchedEffect` with no `withContext` for these calls. |
| 20 | `DisposableEffect.onDispose` fires on dialog open | If a screen pushes state in `DisposableEffect(Unit)` and clears it in `onDispose`, dialogs opening on the same screen will trigger `onDispose` and wipe the state. Use `SideEffect` for state that should persist while the screen is visible. |
| 21 | `combinedClickable` requires `@OptIn(ExperimentalFoundationApi::class)` | Must annotate the composable function and add `import androidx.compose.foundation.ExperimentalFoundationApi`. Missing annotation causes compile error. |
| 22 | `dxwrapperConfig` stores both DXVK and VKD3D in one string | Format: `version=X,framerate=0,...,vkd3dVersion=Y,...`. Use key-value parsing (`split(",")` → `split("=")`) to extract individual versions. Do not use `GraphicsDriverConfigDialog.getVersion()` directly — it returns `version` value which includes trailing config keys. |

---

## Part F — Post-Migration Improvements (2026-04-22)

### F1. Animated Splash Screen

The splash screen was completely rebuilt as a Canvas-animated Compose full-screen experience.

**New features:**
- 18 four-point sparkles orbiting the logo, each with independent speed, size, and rotation — drawn via `Canvas { drawFourPointStar(...) }` using `withFrameMillis` for per-frame updates
- Logo pulse animation — `Modifier.scale(animateFloatAsState(1.0f → 1.07f))` on a repeating `InfiniteTransition`
- Glowing progress bar — layered fake glow (3 `drawRoundRect` passes with expanding size and decreasing alpha) + shimmer sweep via `clipRect { drawRoundRect(Brush.horizontalGradient(...)) }`
- Cycling status text — 5 messages mapped to progress ranges with animated ellipsis dots
- Smooth animated percentage counter — `animateIntAsState(progress.toInt())`
- `AnimatedVisibility(fadeIn + scaleIn)` Proceed button that appears on completion

**Key fix (CI failure):** `clipRect` used inside `DrawScope` requires an explicit import — `import androidx.compose.ui.graphics.drawscope.clipRect`. Without it the compiler cannot resolve the overload and the build fails with a type mismatch error. This import is not auto-suggested by IDE tooling.

**File:** `ui/screens/SplashScreen.kt`  
**Commits:** `d1be7af` (initial), `65712d6` (clipRect import fix)  
**CI:** `24788390969` ✅

---

### F2. Custom Accent Color — Base Preset Inheritance Fix

**Problem:** When the user selected a custom accent color from the HSV picker, all non-accent colors (background, surface, surfaceVariant) reset to the hardcoded "Custom" preset defaults — flat gray — regardless of which theme preset was active before the switch.

**Root cause:** `setCustomAccent()` set `_presetIndex` to `CUSTOM_PRESET_INDEX`. The `colorScheme` flow then used the "Custom" preset entry for *all* color values, including background and surface, instead of inheriting them from the previously active preset.

**Fix — `AppThemeState.kt`:** Added `_customBaseIndex: MutableStateFlow<Int>` which snapshots the current preset index at the moment the user first switches to Custom. The `colorScheme` flow uses this base preset for background/surface/surfaceVariant and only overrides the `primary` (accent) color.

```kotlin
private val _customBaseIndex = MutableStateFlow(0)

fun setCustomAccent(color: Color) {
    // Snapshot the current preset as the base before switching to Custom
    if (_presetIndex.value != CUSTOM_PRESET_INDEX) {
        _customBaseIndex.value = _presetIndex.value
        themePrefs.edit().putInt("custom_base_index", _customBaseIndex.value).apply()
    }
    _customAccent.value = color
    _presetIndex.value  = CUSTOM_PRESET_INDEX
    themePrefs.edit().putInt("custom_accent", color.toArgb())
               .putInt("preset_index", CUSTOM_PRESET_INDEX).apply()
}

val colorScheme: Flow<ColorScheme> =
    combine(_presetIndex, _customAccent, _isDarkMode, _customBaseIndex) { index, accent, dark, baseIdx ->
        val preset = if (index == CUSTOM_PRESET_INDEX)
            themePresets.getOrElse(baseIdx) { themePresets.first() }   // use base for bg/surface
        else
            themePresets.getOrElse(index) { themePresets.first() }
        val override = if (index == CUSTOM_PRESET_INDEX) accent else null
        if (dark) preset.toColorScheme(accentOverride = override)
        else      preset.toLightColorScheme(accentOverride = override)
    }
```

**File:** `ui/theme/AppThemeState.kt`  
**Commit:** `65712d6`

---

### F3. AppDrawer + AppTopBar — MaterialTheme Color Fix

**Problem:** After the dynamic theme system was introduced, the navigation drawer and top app bar remained gray regardless of the active theme. They were still using static color constants imported from `Color.kt` (`Surface`, `Background`, `OnSurface`, etc.) rather than reading from `MaterialTheme.colorScheme`.

**Fix:** Replaced all static constant references in both files with `MaterialTheme.colorScheme.*` equivalents.

| Static constant (removed) | Replaced with |
|---|---|
| `.background(Surface)` | `.background(MaterialTheme.colorScheme.surface)` |
| `.background(Color(0xFF1A1A1A))` | `.background(MaterialTheme.colorScheme.background)` |
| `tint = OnSurfaceVariant` | `tint = MaterialTheme.colorScheme.onSurfaceVariant` |
| `color = OnSurface` | `color = MaterialTheme.colorScheme.onSurface` |
| `containerColor = Surface` (TopAppBar) | `containerColor = MaterialTheme.colorScheme.surface` |
| `Color.White` (icon tint/text) | `MaterialTheme.colorScheme.onSurface` |

**Files:** `ui/AppDrawer.kt`, `ui/AppTopBar.kt`  
**Commit:** `e8acb58`  
**CI:** `24790554006` ✅

---

### F4. In-Game Side Drawer — Full Compose Migration

The in-game side drawer (shown while a Wine session is running, opened by swiping from the left edge) was fully migrated from `NavigationView` + XML menu + RecyclerView animation helpers to Jetpack Compose.

#### Architecture

The existing `DrawerLayout` is kept as the shell — it handles touch interception, edge-swipe detection, scrim, and open/close animation. Only the `NavigationView` content is replaced with a `ComposeView`.

```
DrawerLayout (kept)
├── FLXServerDisplay (kept — game surface)
├── ComposeView id=XServerDrawerComposeView (NEW — replaces NavigationView)
└── XRTextInput (kept — XR keyboard input)
```

#### XServerDrawerState.kt (new)

Kotlin `object` singleton bridging `XServerDisplayActivity` (Java) ↔ Compose:

```kotlin
object XServerDrawerState {
    // StateFlows — drive Compose recomposition
    val isPaused:               StateFlow<Boolean>
    val isRelativeMouseMovement:StateFlow<Boolean>
    val isMouseDisabled:        StateFlow<Boolean>
    val moveCursorToTouchpoint: StateFlow<Boolean>
    val showLogs:               StateFlow<Boolean>
    val showMagnifier:          StateFlow<Boolean>
    val cursorExpanded:         StateFlow<Boolean>

    // Callbacks set by Activity — @JvmField + Runnable for Java interop
    @JvmField var onClose:                  Runnable? = null
    @JvmField var onKeyboard:               Runnable? = null
    @JvmField var onInputControls:          Runnable? = null
    @JvmField var onScreenEffects:          Runnable? = null
    @JvmField var onGraphicEngine:          Runnable? = null
    @JvmField var onVibration:              Runnable? = null
    @JvmField var onToggleFullscreen:       Runnable? = null
    @JvmField var onPauseResume:            Runnable? = null
    @JvmField var onPipMode:                Runnable? = null
    @JvmField var onActiveWindows:          Runnable? = null
    @JvmField var onTaskManager:            Runnable? = null
    @JvmField var onMagnifier:              Runnable? = null
    @JvmField var onLogs:                   Runnable? = null
    @JvmField var onExit:                   Runnable? = null
    @JvmField var onMoveCursorToTouchpoint: Runnable? = null
    @JvmField var onRelativeMouseMovement:  Runnable? = null
    @JvmField var onDisableMouse:           Runnable? = null

    fun toggleCursorExpanded()
    fun reset()
    // + Boolean setters for Java to call
}
```

#### XServerDrawer.kt (new)

Top-level `fun setupComposeView(view: ComposeView)` callable from Java as `XServerDrawerKt.setupComposeView(composeView)`.

Menu structure — all items:
| Item | State-aware | Behavior |
|------|------------|---------|
| Keyboard | — | `onKeyboard?.run()` |
| Input Controls | — | `onInputControls?.run()` |
| Mouse & Cursor *(collapsible header)* | `cursorExpanded` | `toggleCursorExpanded()` — `AnimatedVisibility(expandVertically + fadeIn)` |
| → Move Cursor to Touchpoint | `moveCursorToTouchpoint` (checkmark) | `onMoveCursorToTouchpoint?.run()` |
| → Relative Mouse Movement | `isRelativeMouse` (checkmark) | `onRelativeMouseMovement?.run()` |
| → Disable Mouse | `isMouseDisabled` (checkmark) | `onDisableMouse?.run()` |
| Screen Effects | — | `onScreenEffects?.run()` |
| Graphic Engine | — | `onGraphicEngine?.run()` |
| Vibration | — | `onVibration?.run()` |
| Toggle Fullscreen | — | `onToggleFullscreen?.run()` |
| Pause / Resume | `isPaused` (icon flips) | `onPauseResume?.run()` |
| Picture in Picture | — | `onPipMode?.run()` |
| Active Windows | — | `onActiveWindows?.run()` |
| Task Manager | — | `onTaskManager?.run()` |
| Magnifier | hidden if `!showMagnifier` | `onMagnifier?.run()` |
| Logs | hidden if `!showLogs` | `onLogs?.run()` |
| Exit | — | `onExit?.run()` (no close — exits session) |

#### XServerDisplayActivity.java changes

Removed (~400 lines):
- `implements NavigationView.OnNavigationItemSelectedListener`
- `navigationView` field
- All nav view setup (duplicate `findViewById`, dark mode styling, menu config)
- `onNavigationItemSelected()` method (130 lines)
- All RecyclerView animation helpers: `navRecycler()`, `findNavRecycler()`, `titlesForIds()`, `rowTitle()`, `findVisibleRowsForTitles()`, `animateInGroupItems()`, `animateOutGroupItems()`, `applyGroup()`, `persistSection()`, `expandGroup()`, `collapseGroup()`, `finishCollapse()`
- Fields: `expCursor`, `navLayoutAnim`, `enableLogs` (field), `allowMagnifier`, `navigationFocused`, `PREF_EXP_CURSOR`, `ANIM_DURATION`, `SLIDE_DP`, `COLLAPSE_TRANSLATION_DP`, `CURSOR_IDS`, `dp()`

Added (~80 lines):
- `ComposeView` and `XServerDrawerKt`/`XServerDrawerState` imports
- `XServerDrawerState.INSTANCE.reset()` + all callback wiring
- `XServerDrawerKt.setupComposeView(drawerComposeView)` call

#### B4 update — in-game drawer now Compose

The following item is removed from "Java dialogs kept" in B4:
> ~~`NavigationView` + `onNavigationItemSelected` — in-game side drawer~~

The drawer is now fully Compose. The `DrawerLayout` XML shell remains.

**Files:**
- NEW: `ui/XServerDrawerState.kt`
- NEW: `ui/XServerDrawer.kt`
- MODIFIED: `res/layout/xserver_display_activity.xml` — `NavigationView` → `ComposeView`
- MODIFIED: `XServerDisplayActivity.java` — ~400 lines removed, ~80 added

**Commits:** `f79f1ef` (initial), `9289d57` (@JvmField fix), `3659f74` (merge to main)  
**CI:** `24793070513` ✅

---

### F5. Contents Screen — Remote Profiles URL

The remote profiles endpoint used by `ContentsManager.java` was updated from the original GitLab URL to the Bionic Nightly content index:

```java
// Before
public static final String REMOTE_PROFILES = "https://gitlab.com/.../content.json";

// After
public static final String REMOTE_PROFILES =
    "https://raw.githubusercontent.com/Xnick417x/Winlator-Bionic-Nightly-wcp/refs/heads/main/content.json";
```

**File:** `contents/ContentsManager.java`  
**Commit:** `e8acb58`

---

### F6. New Gotchas (23–25)

#### Gotcha 23: Kotlin `object` properties are private fields from Java's perspective

Kotlin `object` `var` properties compile to private backing fields with public getter/setter methods. Java code that tries to assign them directly (`state.onClose = ...`) gets a compile error: `onClose has private access in XServerDrawerState`.

Two options:
1. **`@JvmField`** — exposes the backing field as a public Java field. Java can assign directly. Best when the field is meant to be read/written from both Java and Kotlin.
2. **Explicit Java-callable setter** — `fun setOnClose(r: Runnable?) { onClose = r }`. More explicit but more boilerplate.

For callbacks on a bridge singleton, `@JvmField` is cleaner.

```kotlin
// Wrong — Java cannot assign this
var onClose: Runnable? = null

// Right — Java can assign directly
@JvmField var onClose: Runnable? = null
```

#### Gotcha 24: `() -> Unit` Kotlin lambdas cannot be assigned from Java void lambdas

Kotlin `(() -> Unit)?` compiles to `Function0<Unit>`. A Java lambda `() -> doSomething()` returns `void`, not `kotlin.Unit`. Java throws: `incompatible types: bad return type in lambda expression`.

**Fix:** Use `Runnable?` instead of `(() -> Unit)?` for any property that Java will set. Java `Runnable` accepts void lambdas natively. Kotlin callers invoke it with `.run()` instead of `.invoke()`.

```kotlin
// Wrong — Java cannot assign a void lambda
@JvmField var onClose: (() -> Unit)? = null

// Right — Runnable works from both Java and Kotlin
@JvmField var onClose: Runnable? = null
```

```java
// Java (works naturally):
state.onClose = () -> drawerLayout.closeDrawers();
```

```kotlin
// Kotlin (call with .run()):
state.onClose?.run()
```

#### Gotcha 25: `import androidx.compose.ui.graphics.drawscope.clipRect` is not auto-suggested

When using `clipRect { }` inside a `DrawScope` lambda (e.g. inside `Canvas { }`), the compiler needs the explicit `drawscope` package import. The IDE does not always suggest it, and the error message is a generic type mismatch rather than "unresolved reference", making it hard to diagnose.

```kotlin
// Required — add this explicitly:
import androidx.compose.ui.graphics.drawscope.clipRect

// Usage:
Canvas(modifier) {
    clipRect(right = fillWidth) {
        drawRoundRect(brush = shimmerBrush, ...)
    }
}
```

Without this import the build fails with `error: type mismatch` or `overload resolution ambiguity` pointing at the `clipRect` call site.
## Part G — Post-2026-04-22 Status (2026-05-06)

This part covers work landed between 2026-04-23 and 2026-05-06. Two non-trivial bugs surfaced after the in-game-Compose migration shipped — both rooted in the Java→Compose split — and a small wave of UI text/branding changes. Plus the actual cleanup of dead Java/XML dialogs that the in-game migration left behind.

---

### G1. Controller Support — SDL2 SoName Symlink Lost in Splash-Install Migration

After the Compose migration shipped, physical controllers (gamepads, joysticks) stopped being detected by Wine games on the Compose builds — even though they had worked correctly in the legacy Java/XML build. The on-screen controller (OSC) still worked; only physical evdev input was missing.

#### Root cause

The pre-Compose install path was:

```
MainActivity.java (legacy)
  → ImageFsInstaller.installIfNeeded()
    → installFromAssets()
        ├── extract pattern tarballs
        ├── install Wine
        ├── install drivers
        ├── createImgVersionFile(LATEST_VERSION)
        └── FileUtils.symlink("libSDL2-2.0.so",
              "<imagefs>/usr/lib/libSDL2-2.0.so.0")  ← critical line
```

When the splash screen was migrated to Compose (Part A → SplashViewModel + installFromAssetsWithCallback), the new install method was a copy-paste of the old one but **the SDL2 SoName symlink line was not brought over**. Compose-era installs left `libSDL2-2.0.so.0` missing while keeping the actual file `libSDL2-2.0.so` and the alias `libSDL2.so`.

Why this was silent for so long: Android has no `ldconfig`, so SoName resolution must be a real on-disk filename for every variant a `dlopen` call asks for. Stock Wine xinput (proton-9.x) calls `dlopen("libSDL2-2.0.so.0", ...)` to use SDL2's joystick backend. With the symlink missing the dlopen returned `NULL`, and Wine fell back to its winebus/HID stack — which on Linux uses udev/sysfs to enumerate `/dev/input/event*`. Android has no working udev. So the enumerator returned zero devices, `/dev/input/event*` never got opened, and libfakeinput's `ioctl`/`open` interposers never fired. Game saw no controller. No errors were logged because every step "succeeded" — Wine simply chose the wrong code path.

The on-screen controller worked because OSC bytes are pushed directly into the FakeInputWriter's event-file backing — that path never goes through Wine's joystick enumeration.

#### Fix

One line restored, plus a `LATEST_VERSION` bump to force re-extract for users whose imagefs was already installed on a Compose build without the symlink:

```java
// ImageFsInstaller.java
public static final byte LATEST_VERSION = 22;  // was 21

static void installFromAssetsWithCallback(...) {
    // ... existing extract + Wine + drivers + version file ...
    FileUtils.symlink(
        "libSDL2-2.0.so",
        new File(imageFs.getLibDir(), "libSDL2-2.0.so.0").getAbsolutePath()
    );
    // ...
}
```

`clearRootDir` preserves the `home` subdirectory during re-extract, so Wine prefixes, per-container state, and shortcuts all survive the upgrade.

#### Why this is a portable lesson

The general pattern: when migrating any install/setup pipeline from Java to Kotlin/Compose, **diff line-by-line against the legacy version, not just behavior-by-behavior**. SoName symlinks, font cache rebuilds, default-locale patches, registry seeds — anything done as a side effect after the main extraction is the easiest thing to lose in a copy-paste of the install method. Behavioral testing only catches it when a game gets far enough to actually need the missing piece. By that time the Compose build has shipped.

---

### G2. Box64 Component Dropdown — Read/Seed Bug in ContainerDetailViewModel

After several Compose iterations, a cosmetic-only bug appeared in the container edit dialog: changing the Box64 component (e.g. from `0.3.7` default to a contents-profile version), saving, then re-opening the container's edit dialog showed the dropdown reset to the default — even though the change was actually saved correctly and the new version was running at game launch (DXVK HUD verified).

#### Root cause

`ContainerDetailViewModel.loadContainerData()` calls `refreshWineDependent(selectedWineVersion)` to populate the Box64-version entry list. That function ends with:

```kotlin
private fun refreshWineDependent(wineVersion: String) {
    // ... compute b64Array based on isArm64EC + content profiles ...
    box64VersionEntries = b64Array
    selectedBox64Version = box64VersionEntries.firstOrNull() ?: ""  // <-- always resets
}
```

Resetting to entry 0 is **correct** when the user changes the Wine version (the Box64 entry list is different for arm64ec vs x86_64), so the function gets called from `onWineVersionChanged` with the same body. But the same function is also called once at initial load — and at initial load, the saved selection is silently overwritten by entry 0 before the dialog renders.

This is the classic Compose pitfall: a function that's correct for the change-handler context is wrong for the initial-load context. Other fields in the same ViewModel got it right by following a load-then-seed pattern (e.g. FEXCore: `loadFEXCoreVersions()` then `selectedFEXCoreVersion = c?.getFEXCoreVersion() ?: …`); Box64 was the outlier.

#### Fix

Don't change `refreshWineDependent` (its behavior is correct on Wine-version change). Instead, override the reset on initial load only:

```kotlin
// In loadContainerData(), right after refreshWineDependent(selectedWineVersion):
c?.box64Version
    ?.takeIf { it.isNotEmpty() && box64VersionEntries.contains(it) }
    ?.let { selectedBox64Version = it }
```

The `takeIf { box64VersionEntries.contains(it) }` guard means a saved value that's no longer available (uninstalled content profile) gracefully falls back to the entry-0 default rather than putting the dropdown into an invalid state.

#### Why this is a portable lesson

A function that resets state is rarely "always-correct" or "always-wrong" — the correctness depends on which call site you're looking at. When your ViewModel has `loadContainerData()` calling helper functions that themselves manipulate UI state, **trace each piece of state from disk → Container object → ViewModel field → composable**, and check that the seeding order matches the desired final state. Helper functions that mutate state are fine, but the seed-from-disk step has to come *after* them, not before.

Same fix template applies any time you have:
1. A "refresh list" function used in two contexts (init + change).
2. The function defaults a selection to "first entry" or some computed default.
3. Init-time you want the saved value, change-time you want the default.

→ Reset to default inside the helper (correct for change context), then **override at the init call site** with the saved value if available.

---

### G3. UI Text and Branding Pass

A small wave of user-facing text changes — purely cosmetic, but worth noting because they touched several Compose surfaces in parallel and risked drift:

| Surface | File | Changed |
|---|---|---|
| Splash screen | `ui/screens/SplashScreen.kt` | App title + version label |
| About dialog | `MainActivity.kt` | Title + version line (replaces `BuildConfig.VERSION_NAME`/`VERSION_CODE` format with a hard-coded user-facing string) |
| App drawer header | `ui/AppDrawer.kt` | Title |
| In-game drawer header | `ui/XServerDrawer.kt` | Title |

Build-side `versionName` / `versionCode` in `app/build.gradle` were intentionally **not** changed, so the existing CI tag and APK-naming convention (`v<versionName>-<date>-<sha>`) continues to work.

Lesson: when a fork rebrand changes user-facing strings, audit every Compose surface that displays the app name. Title text tends to live in 4–5 places (splash, app drawer header, in-game drawer header, about dialog, occasionally a top bar) and they all want updating in lockstep.

---

### G4. Dead Java/XML Cleanup — In-Game Dialog Migration Tail End

When Part F migrated the in-game drawer + dialogs/overlays to Compose, the new Compose files (`ui/dialogs/*.kt`, `ui/overlays/*.kt`) were added but the corresponding Java + XML originals were left in the tree. They ship as dead code in the APK (no functional impact, just a few hundred KB of compiled classes + layout binaries).

#### Files now safe to delete (zero references)

```
contentdialog/ActiveWindowsDialog.java
contentdialog/FSRControlFloatingDialog.java
contentdialog/ScreenEffectDialog.java
widget/MagnifierView.java
winhandler/TaskManagerDialog.java

res/layout/active_windows_dialog.xml
res/layout/active_window_item.xml
res/layout/fsr_control_dialog.xml
res/layout/magnifier_view.xml
res/layout/screen_effect_dialog.xml
res/layout/task_manager_dialog.xml
res/layout/process_info_list_item.xml
res/layout/about_dialog.xml
res/layout/debug_dialog.xml
res/layout/debug_toolbar.xml
```

#### `DebugDialog.java` — blocked on `LogView.java`

The Java `DebugDialog.setPaused(boolean)` static is still called from `widget/LogView.java` lines 230, 237, 245. Before deleting `DebugDialog.java`:

```java
// LogView.java — replace 3 call sites
// Was:
DebugDialog.setPaused(true);
// New:
XServerDialogState.INSTANCE.setLogPaused(true);
// Plus remove the import:
// import com.winlator.cmod.contentdialog.DebugDialog;
```

The new sink (`XServerDialogState.setLogPaused(Boolean)`) already exists from Part F4's drawer migration; only the Java call site needs to be retargeted.

#### Java config-helper dialogs — separate cleanup

Several `contentdialog/*Dialog.java` files have their UI fully migrated to Compose but their **static helper methods** are still called from Compose code:

| File | Static helpers in use |
|---|---|
| `ContentDialog.java` | `confirm()`, `prompt()`, `alert()` (~12 refs) |
| `DXVKConfigDialog.java` | `parseConfig()`, `setEnvVars()`, `loadDxvkVersionList()`, `compareVersion()` (~4 refs) |
| `GraphicsDriverConfigDialog.java` | `parseGraphicsDriverConfig()`, `toGraphicsDriverConfig()`, `getVersion()` (~6 refs) |
| `WineD3DConfigDialog.java` | `parseConfig()`, `setEnvVars()`, `loadGpuNames()` (~5 refs) |

These can be deleted only after the statics are extracted into Kotlin objects (e.g. `DxvkConfig` in `core/`). It's mechanical work, not a UI migration — separate task.

---

### G5. New Gotchas (26–27)

#### G26. SoName Symlinks During imagefs Install Are Easy to Lose in a Java→Kotlin Migration

When you migrate any install/setup pipeline from Java to Kotlin/Compose, the actual file copy/extract is the easy part to get right. The side-effect operations — symlinks, font cache, registry seeds, locale patches — that come *after* the main extraction are the easy parts to lose.

Defensive approach: before merging an install-path migration, do a `git diff old/InstallMethod.java new/InstallMethod.kt` line by line, **not** behavior by behavior. Functional tests only catch a missing side effect when the dependent feature actually runs (in the SDL2 case, only when a game queried physical joysticks). By the time you find it, the new install path has shipped to users.

#### G27. ViewModel Reset Helpers Need Init-Time Override Hooks

If your ViewModel has a `loadContainerData()` (or equivalent init function) that calls a "refresh / repopulate dropdown" helper, and that helper resets the selection to a default — you need to **re-seed the saved value at the init call site**, not inside the helper.

Helpers used in two contexts (initial load + change handler) cannot make a single correct decision about whether to use the default or the saved value, because that's context-dependent. The clean pattern:

```kotlin
fun loadXxx() {
    refreshDependent(currentValue)              // populates entries, resets selection
    saved?.takeIf { entries.contains(it) }      // re-seed only if saved value
        ?.let { selected = it }                 //   is still in the entries list
}

fun onChanged(newValue: String) {
    refreshDependent(newValue)                  // resets — correct here
}
```

Don't try to detect "is this initial load" inside the helper — that's a leaky abstraction. Let the caller decide.

---

### G6. Updated Summary Stats

| Metric | Part B7 (2026-04-17) | Now (2026-05-06) | Delta |
|---|---|---|---|
| Java Fragments deleted | 7 | 7 | — |
| Fragment XML layouts deleted | 8 | 8 | — |
| Java Dialog classes deleted | 11 | 11 (5 more queued; see §G4) | — |
| Dialog XML layouts deleted | 15 | 15 (10 more queued; see §G4) | — |
| New Kotlin Compose screens | 10 | 10 | — |
| **In-game Compose dialogs/overlays added** (Part F supplement) | n/a | **8** | +8 |
| **In-game Compose drawer added** (Part F4) | n/a | **1** | +1 |
| New Kotlin ViewModels | 6 | 6 | — |
| Internal reusable composables | 8 | 8 | — |
| XML layouts remaining in `res/layout/` | ~81 | **73** | −8 |
| Documented gotchas | 18 (Parts A11+D10) | **27** (adds Parts E6, F6, G5) | +9 |

---

### G7. Still Active — Needs Migration

Items below were untouched by Parts E–G and remain on the roadmap:

| File | Notes |
|---|---|
| `SettingsFragment.java` + `settings_fragment.xml` + `preference_*.xml` (8 layouts) | XML PreferenceScreen; dark-mode mismatch with Compose theme. Migrate to `ui/screens/SettingsScreen.kt` as a `LazyColumn` of preference rows. ~2h. |
| `ShortcutPickerActivity.java` | Standalone Activity; convert to Compose Dialog launched from `MainActivity` with `ActivityResultContracts`. ~1h. |
| `restore/RestoreActivity.java` | Compose screen inside the nav graph with `LaunchedEffect` for restore logic. ~1h. |
| `saves/CustomFilePickerActivity.java` | Replace with `ActivityResultContracts.OpenDocument` or a Compose `LazyColumn` browser. ~1.5h. |
| `ControlsEditorActivity.java` | Heavy custom canvas — wrap `InputControlsView` via `AndroidView`; migrate the nested config dialogs to Compose `AlertDialog`. ~4h. |
| `ExternalControllerBindingsActivity.java` | Compose `LazyColumn` with `AlertDialog` per binding row. ~2h. |
| `BigPictureActivity.java` | TV/couch-mode launcher with custom tiled background. `LazyVerticalGrid` + Compose `Canvas` for the background animation. ~2.5h. |
| Store activities (`store/*.java` — 12 files across Amazon/Epic/GOG/Steam) | Largest remaining surface; treat as a separate epic. |
| `XServerDisplayActivity.java`, `XrActivity.java` | **Engine boundary — keep as Java** (see A15). |
| `InputControlsFragment.java` | Compose dialog `ui/dialogs/InputControlsDialog.kt` exists from Part F. Verify the fragment is no longer launched anywhere; if dead, delete in the §G4 cleanup. |

