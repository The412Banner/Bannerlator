<img src="20260418_134945.png" width="1500" height="500" alt="star" />  
</p>

<p align="center">
  <img src="https://img.shields.io/github/downloads/jacojayy/star/total" alt="Total Downloads" width="150">
</p>

<h1 align="center"> star -
Windows applications and games on Android.</h1>

**star** is an application that lets you play PC games on Android with the best performance possible. It lets you access your Steam, Amazon, GOG and Epic Games library on the go.

- **Package:** `com.winlator.cmod`
- **Version:** `v1.2-REVAMPED` (build identifier `7.1.4x-cmod`, versionCode `20`)
- **Android SDK:** `compileSdk 34`, `targetSdk 28`, `minSdk 26` (Android 8.0+)
- **Upstream lineage:** Winlator → cmod → Bionic Nightly → Star → Star Bionic (Compose)

---

# Our official social media

👉Discord server: <a href="https://discord.gg/Q74CNHJnq2">
    <img src="https://img.shields.io/badge/Discord-Join-5865F2?logo=discord&logoColor=white" height="40">
    </a>
    
👉Telegram group: <a href="https://t.me/staremul">
    <img src="https://img.shields.io/badge/Telegram-Join-26A5E4?logo=telegram&logoColor=white" height="40">
  </a>
  
👉YouTube channel: <a href="https://youtube.com/@starwindowsemulator?si=sHoTf-auUpccqR6s">
    <img src="https://img.shields.io/badge/YouTube-Subscribe-FF0000?logo=youtube&logoColor=white" height="40">
  </a>

---

## What's in this fork

- **Full Jetpack Compose UI.** Every user-facing screen, dialog, drawer, and overlay has been ported off Java/XML to Compose + Material 3. This is the only Winlator fork to do this.
- **In-game Compose overlays.** Side drawer, settings dialogs, screen-effects panel, and task manager all run as Compose `Dialog` windows over the X server.
- **Dynamic theme system.** `AppThemeState` + `ThemePreset` allow live color/theme switching.
- **Controller support restored to Star 1.1 parity.** SDL2 SoName symlink wired into the Compose splash install path; all four controller event files pre-created at startup.
- **Box64 dropdown bug fix.** Edit-dialog now seeds the Box64 selector from the saved container value instead of resetting on dependency refresh.
- **Bionic content pattern.** Ships the larger `container_pattern_common.tzst` (bionic build, ~77 MB) for an expanded Start menu toolset.
- **Adreno-tuned drivers bundled.** Turnip 25.1.0, AdrenoTools v819, and Wrapper variants (gamenative, leegao, legacy, original).
- **Remote content registry.** Components (DXVK, VKD3D, wrappers, Box variants) are pulled from the **Bionic Nightly** registry maintained by Xnick417x.

---

# Installation

1. Download and install the APK from [GitHub Releases](https://github.com/jacojayy/star/releases)
2. Launch the app and wait for the installation process to finish
3. Create the container, adjust the container settings depending on your device specs and chips then create it by pressing the tick button
4. Run the container by pressing play button and you're ready for pc gaming in your device

---

# Game test on Star Emulator by EmuStar (official Star Emulator channel)

### Test on Samsung Galaxy A12, MediaTek Helio P35, 6 GB RAM (discontinued, 2 videos test only)

[![Play on Youtube](https://i.ytimg.com/vi/Dqff2JvVwPM/hqdefault.jpg?sqp=-oaymwEXCOADEI4CSFryq4qpAwkIARUAAIhCGAE=&rs=AOn4CLBtMrTSAJgDEeQfqSl8_Eho5FCNRA)](https://youtube.com/playlist?list=PLQt8o_wwflSdb_EmPvAJWQIZv1CEaOcYG&si=H8eABMErSfD5uc2c)

### Test on Honor Magic 5 Pro, Snapdragon 8 Gen 2, 12 GB RAM (active)

[![Play on Youtube](https://i.ytimg.com/vi/DY_sMG9LSEY/hqdefault.jpg?sqp=-oaymwEXCOADEI4CSFryq4qpAwkIARUAAIhCGAE=&rs=AOn4CLD7T5eppf3aSo-pDzx2HSwDr68Rtw)](https://youtube.com/playlist?list=PLQt8o_wwflSdZZOfixLLi1woRM71QPTu1&si=FXbitAf-_fK16lAg)

### Test on Redmi 13C 5G, MediaTek Dimensity 6100 Plus, 8 GB RAM (active)

[![Play on Youtube](https://i.ytimg.com/vi/XC77qiV9J

---

## Building

This project is built via **GitHub Actions only**. Local builds are not supported.

- **Any branch:** push and trigger the `Any branch compilation` workflow.
- **Releases (main only):** trigger the `Manual Release Build` workflow.

Artifacts are published as workflow artifacts; tagged stable builds are also published as GitHub Releases.

---

## Documentation in this repo

- `COMPOSE_MIGRATION_REPORT.md` — developer guide for the Java/XML → Compose migration (Parts A–G), including patterns, gotchas, and the engine boundary.
- `PROGRESS_LOG.md` — chronological record of every shipped change.
- `UI_MIGRATION_REPORT.md` — remaining UI cleanup and migration plan.

---

## Credits

This fork stands on a long chain of prior work. Credit, in lineage order:

| Contributor | Contribution |
|---|---|
| **brunodev85** | Original [Winlator](https://github.com/brunodev85/winlator) — Wine + Box64 + Turnip on Android. Foundation of every fork below. Also serves the `input_controls` profiles consumed by this fork: <https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/> |
| **coffincolors** | [`cmod` Winlator fork](https://github.com/coffincolors/winlator) — package `com.winlator.cmod` and the customization layer this codebase is built on. |
| **Pipetto-crypto** | [Winlator Bionic fork](https://github.com/Pipetto-crypto/winlator) (the "Bionic" half of *Star Bionic*) and the upstream [Box64 fix branch](https://github.com/Pipetto-crypto/box64). Co-credited on cmod. |
| **jacojayy** | Maintainer of the [Star](https://github.com/jacojayy/star) line. SDK36 patches in the bundled Turnip driver for newer DXVK compatibility. |
| **vivsi** | Controller support contributions. |
| **The412Banner** | Full Jetpack Compose UI migration, in-game overlay rewrite, controller-support restore (SDL2 SoName fix + four event files), Box64 edit-dialog fix, theme system, and CI/release infrastructure. Also maintains the [Nightlies WCP Hub](https://github.com/The412Banner/Nightlies) and [Banners-Turnip](https://github.com/The412Banner/Banners-Turnip). |

### Sibling forks (not in this fork's direct lineage, but worth knowing)

- **StevenMXZ** — [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi): Bionic-based fork with `dev-vanilla`, `ludashi` (renamed package for Xiaomi performance-mode detection), and `redmagic` (Genshin Impact package name for RedMagic frame-gen) build variants.

### Upstream stack

The Wine/translation stack this app bundles or downloads:

- **Wine** — [WineHQ](https://www.winehq.org/)
- **Box64 / Box86** — [ptitSeb](https://github.com/ptitSeb)
- **FEXCore** — [FEX-Emu](https://github.com/FEX-Emu)
- **DXVK** — [doitsujin / Philip Rebohle](https://github.com/doitsujin)
- **DXVK-GPLAsync patch** — [Ph42oN](https://gitlab.com/Ph42oN)
- **DXVK-Sarek** — [pythonlover02](https://github.com/pythonlover02)
- **VKD3D-Proton** — [Hans-Kristian Arntzen](https://github.com/HansKristian-Work)
- **Turnip / Mesa** — [Freedreno team @ Mesa](https://gitlab.freedesktop.org/mesa/mesa)
- **Proton layers (bionic)** — [GameNative](https://github.com/utkarshdalal/GameNative)

Credits surfaced in the **Star Bionic REVAMPED** release (`star.bionic-revamp`):

- **@The412Banner** — Converting the UI to Jetpack Compose and rewriting the controller implementation.
- **@jacojayy** — SDK36 patches in Turnip.

If you have contributed and are not listed, open an issue or PR — this list is intended to be complete.

---

## Disclaimer

Winlator and its forks are unofficial community projects. They are not affiliated with or endorsed by Microsoft, Wine, the Mesa project, Qualcomm, or any game publisher. Compatibility varies by device GPU, Android version, and individual game.

---

## License

Inherits the license of the upstream Winlator project (GPL-3.0). See `LICENSE` for the full text.
