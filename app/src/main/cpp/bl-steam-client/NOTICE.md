# bl-steam-client — provenance and license notice

`libblsteam.so` (Rust crate `bl-steam-client`, library name `blsteam`) is a
**derivative work of the `wn-steam-client` crate (`wnsteam`) from the WinNative
project** — <https://github.com/WinNative-Emu/WinNative>, directory
`app/src/main/cpp/wn-steam-client/rust`, taken at upstream commit `a2020b7`
(2026-09-01). The upstream crate is published under **GPL-3.0-or-later**;
this derivative is distributed under the same license as part of Bannerlator
(GPL-3.0). Copyright in the original code remains with the WinNative authors
and contributors.

The Kotlin facades under `app/src/main/java/com/winlator/star/store/blsteam/`
are likewise derived from WinNative's
`app/src/main/feature/stores/steam/wnsteam/` package.

## Modifications made in Bannerlator (GPL-3.0 §5(a) statement of changes)

Date of first modification: 2026-09-01.

- Crate renamed `wnsteam` → `bl-steam-client`; library output renamed
  `libwnsteam.so` → `libblsteam.so`.
- JNI exports re-targeted from `Java_com_winlator_cmod_feature_stores_steam_wnsteam_*`
  to `Java_com_winlator_star_store_blsteam_*`; the `WnAuthResult` class lookup
  now resolves `com/winlator/star/store/blsteam/BlAuthResult`.
- Identifier prefixes renamed: `Wn*` → `Bl*`, `wn_*` → `bl_*` (including the
  `wn_cm_*` C-ABI surface, now `bl_cm_*`), `WN_*` → `BL_*`
  (`WN_STATE_DIR` → `BL_STATE_DIR`, `WN_STEAM_CLIENT_VERSION_*` →
  `BL_STEAM_CLIENT_VERSION_*`), temp-file / thread-name prefixes
  `wnsteam_*` → `blsteam_*`, progress-marker magic `WNDP` → `BLDP`.
- Android log tags renamed to `BL_STEAM_*`.
- Build glue replaced: the upstream CMake `add_custom_command` cargo driver is
  not used; the crate is built by GitHub Actions (`.github/workflows/_build.yml`)
  with the `.cargo/config.toml` in this directory and packaged into
  `app/src/main/jniLibs/arm64-v8a/libblsteam.so`.
- Kotlin facades: package moved to `com.winlator.star.store.blsteam`, Timber
  logging replaced with `android.util.Log`, and the in-app `libsteamclient`
  bridge facades (`WnLibSteamClient`, `WnSteamAssetsInstaller`,
  `WnSteamBootstrap`, `WnLauncherStatusTailer`, `WnWineEnvVars`,
  `WnLibraryStore`, `AvatarFetcher`) were not ported.

No functional Steam-protocol changes were made in this revision.
