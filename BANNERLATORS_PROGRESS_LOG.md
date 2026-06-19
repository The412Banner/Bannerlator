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

### 2026-06-18 — Splash screen rebrand (`SplashScreen.kt`)
- First-run "Installing system files" overlay was hardcoded "Bionic Star" / "V1.2" + app icon.
- **Change**: swapped `R.mipmap.ic_launcher_foreground` → repo banner logo
  (`app/src/main/res/drawable/splash_logo.jpg`, copied from root `logo.jpg`), sized
  `fillMaxWidth()` so the wide 1245×602 banner isn't squished; **removed** the title Text
  (logo already carries the "Bannerlator" wordmark); version label → **`v1.0`**. Kept
  "Installing system files", progress bar, Proceed button. Dropped unused `size` import.

## Branding / repo housekeeping (2026-06-18)
- **Repo renamed** `bannerlators` → **`Bannerlator`** (https://github.com/The412Banner/Bannerlator,
  old URL redirects). Local git remote updated; download badge + release-APK name → `Bannerlator-<tag>.apk`.
- **Logo**: replaced placeholder with neon **Bannerlator** banner (`logo.jpg`, 1245×602); old
  `logo.png` removed.
- **README**: professional rewrite (centered header, badges, quick-links nav, sectioned tables).
  Added **Project Notice** = personal continuation of the discontinued/archived Winlator
  *Star Bionic* ([star-emu/star](https://github.com/star-emu/star)); no original devs except
  The412Banner; built on their work + cherry-picked community commits; free to use/share. About
  section moved OUT of README into the GitHub repo "About" description. Discord
  (`discord.gg/n8S4G2WZQ4`) + Telegram (`t.me/The412BannerGaming`) point to The412Banner.
- **Credits expanded**: StevenMXZ (Winlator-Ludashi `ludashi`/`redmagic` variants),
  GameNative (utkarshdalal, Proton bionic layers), Star/Frost dev team (star-emu),
  leegao (BCn/ASTC/ETC Vulkan texture-compression layers), isygold (Star Engine / VEGAS
  Adreno DXVK fork — the `vegas` in `v1.3-vegas`).

## Build/run history
- 2026-06-18 ~23:21 UTC — first action build (run **27795368178**, "Any branch compilation."
  on `main`) for marcescence + orientation fix. **✅ SUCCESS** — artifact `compiled-debug`
  (~541 MB APK). Awaiting device test of the Settings screen.
- 2026-06-18 — README adopted from `The412Banner/star`, adapted for bannerlators (name, badge
  → The412Banner/bannerlators, banner → logo.png). External frontends-guide link left pointing
  at `star-emu/star` (doc not vendored here).
- 2026-06-18 — second action build (run **27797077384**, on `main`) with splash rebrand +
  branding work. **✅ SUCCESS** — artifact `compiled-debug` (~541 MB APK). Still awaiting
  device test (Settings fix + new splash).

## Notes / TODO
- Device-test the Settings screen after the action build (verify rows now stack vertically).
- Dialog layouts (`shortcut_settings_dialog.xml`, `box64_edit_preset_dialog.xml`,
  `screen_effect_dialog.xml`) also use style-only-orientation FieldSet but render in classic
  dialogs (not the Compose AndroidView host), so they're NOT affected by this regression.
- `app/build/outputs/apk/debug/app-debug.apk` is the single debug output path the workflows
  use (confirm flavor handling if multi-flavor APKs are ever needed here).
