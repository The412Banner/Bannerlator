# Bannerlators — Progress Log

Standalone fix/build fork of `star-compose` (Winlator-based Android app, marcescence 1.4 line).
Repo: https://github.com/The412Banner/bannerlators (public). Created 2026-06-18.

## Origin / what this repo is
- Fresh, **unattached** repo: brand-new git history (single initial commit), origin points
  only at `bannerlators`. No remote/history link to `The412Banner/star-compose`.
- Source = the `star-compose` working tree at the **1.4-marcescence** line
  (gradle `versionName "7.1.4x-cmod"`, `versionCode 20` — "1.4-marcescence" is the release
  tag name, not the gradle version).
- **Self-contained**: OpenXR-SDK + adrenotools (incl. nested linkernsbypass) submodule sources
  were fetched at their pinned commits and **vendored in as plain files**. `.gitmodules`
  removed; CI no longer needs `submodules: recursive`.
- Build artifacts excluded (`.gradle`, `build/`, `.cxx`, `local.properties`, `*.iml`, `.idea`).
  Large assets (`imagefs.txz`, `proton-9.0-*.txz`) stay gitignored, same as upstream.

## CI (both manual — no accidental releases)
- **`.github/workflows/main.yml`** — "Any branch compilation." `workflow_dispatch` only.
  Builds JavaSteam JARs → `assembleDebug` → uploads APK as artifact `compiled-debug`.
  Dropped the `submodules: recursive` checkout option (vendored now).
- **`.github/workflows/release.yml`** — "Release build" `workflow_dispatch` with inputs
  `tag` (required) + `prerelease` (bool, default true). Same build, then renames the APK to
  `bannerlators-<tag>.apk` and publishes a GitHub Release via `softprops/action-gh-release@v2`
  (`permissions: contents: write`, `generate_release_notes: true`).

## Fixes applied
### 2026-06-18 — Settings screen overlapping/collapsed rows
- **Symptom** (reported via two device screenshots of 1.4-marcescence): every section card on
  the Settings screen rendered its rows on top of each other — XServer checkboxes
  ("True Mouse Control…", "Disable Xinput…") piled onto the Cursor Speed slider; Box64/FEXCore
  preset spinner + icon row + label collapsed onto one line; "Winlator/Shortcut Export Path"
  labels overlapped, etc.
- **Root cause**: each section is a `LinearLayout style="@style/FieldSet.Dark"` that gets its
  `orientation="vertical"` **only from the style**. Under 1.4's new Compose host
  (`FragmentScreen` → `AndroidView` → `FragmentContainerView` w/ `ContextThemeWrapper`), the
  style's orientation wasn't being honored, so the LinearLayout fell back to its default
  **horizontal** → children stacked at the same spot. (Width *was* honored — cards are
  full-width — so only orientation was dropped.) Inner rows were fine because they set
  `android:orientation` directly on the tag.
- **Fix**: added `android:orientation="vertical"` directly to all **11** `FieldSet.Dark`
  LinearLayout tags in `app/src/main/res/layout/settings_fragment.xml`, decoupling orientation
  from the style. Low-risk, surgical.
- The earlier suspect (commit `12b01e3` switching `FieldSet`→`FieldSet.Dark`) was a **red
  herring** — that change only swaps the background drawable (`bordered_panel`→
  `bordered_panel_dark`, a `<solid>` fill, no padding/insets) and cannot change child stacking.
- **Status**: ⏳ awaiting device confirmation (CI green ≠ working). NOT yet device-tested.

## Build/run history
- 2026-06-18 ~23:21 UTC — first action build (run **27795368178**, "Any branch compilation."
  on `main`) triggered for marcescence + orientation fix. Status: in_progress at log time.

## Notes / TODO
- Device-test the Settings screen after the action build (verify rows now stack vertically).
- Dialog layouts (`shortcut_settings_dialog.xml`, `box64_edit_preset_dialog.xml`,
  `screen_effect_dialog.xml`) also use style-only-orientation FieldSet but render in classic
  dialogs (not the Compose AndroidView host), so they're NOT affected by this regression.
- `app/build/outputs/apk/debug/app-debug.apk` is the single debug output path the workflows
  use (confirm flavor handling if multi-flavor APKs are ever needed here).
