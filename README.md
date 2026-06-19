<p align="center">
  <img src="logo.jpg" width="820" alt="Bannerlator" />
</p>

<h1 align="center">Bannerlator</h1>
<p align="center"><b>Windows applications and games on Android.</b></p>

<p align="center">
  <img src="https://img.shields.io/github/downloads/The412Banner/Bannerlator/total?style=for-the-badge&label=Downloads&color=ff2d9b" alt="Total Downloads">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-7a4cff?style=for-the-badge" alt="Platform">
  <img src="https://img.shields.io/badge/License-GPL--3.0-2d9bff?style=for-the-badge" alt="License">
</p>

<p align="center">
  <a href="https://discord.gg/n8S4G2WZQ4">Discord</a> •
  <a href="https://t.me/The412BannerGaming">Telegram</a> •
  <a href="#building">Builds</a> •
  <a href="#credits">Credits</a>
</p>

---

## 📌 Project Notice

> **Bannerlator is a personal continuation of the Winlator *Star Bionic* project ([star-emu/star](https://github.com/star-emu/star)), which was recently discontinued and archived.**
>
> **None of the original developers are involved except me ([The412Banner](https://github.com/The412Banner)).** This project would not be possible without their hard work up to this point, together with cherry-picked commits from other open-source projects across the community — all credited below.
>
> This is **my personal build.** As always, it is **free for anyone to use as they see fit, or to share.**

---

## ℹ️ Information

| | |
|---|---|
| **App label** | `Bannerlator Bionic` (standard) · `Bannerlator Bionic PuBG` (pubg) · `Bannerlator Bionic Ludashi` (ludashi) |
| **Packages** | `com.winlator.star` (standard) · `com.tencent.ig` (pubg) · `com.ludashi.benchmark` (ludashi) |
| **Version** | Bannerlator **V 1.0** — built from Star **marcescence** (`versionName 1.4-marcescene`, `versionCode 20`) |
| **Android SDK** | `compileSdk 34` · `targetSdk 28` · `minSdk 26` (Android 8.0+) |
| **Lineage** | Winlator → cmod → Bionic Nightly → Star Bionic → **marcescence** → **Bannerlator** |

---

## 🎮 Frontends Workaround

Bannerlator does not work by itself on frontends out of the box. See the [frontends workaround guide](https://github.com/star-emu/star/blob/marcescence/marcescence-frontends.md) to get it running.

---

## 🛠️ Building

This project is built via **GitHub Actions only** — local builds are not supported.

- **Action builds** — every fix is compiled and published as a downloadable workflow artifact.
- **Releases** — tagged stable builds are published as GitHub Releases.

---

## 🙏 Credits

This build stands on a long chain of prior work — its direct lineage, plus the projects whose commits and work are cherry-picked and implemented here:

| Contributor | Contribution |
|---|---|
| **brunodev85** | Original [Winlator](https://github.com/brunodev85/winlator) — Wine + Box64 + Turnip on Android. Foundation of every fork below. Also serves the `input_controls` profiles consumed by this fork: <https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/> |
| **coffincolors** | [`cmod` Winlator fork](https://github.com/coffincolors/winlator) — package `com.winlator.cmod` and the customization layer this codebase is built on. |
| **Pipetto-crypto** | [Winlator Bionic fork](https://github.com/Pipetto-crypto/winlator) (the "Bionic" half of *Star Bionic*) and the upstream [Box64 fix branch](https://github.com/Pipetto-crypto/box64). Co-credited on cmod. |
| **jacojayy** | Maintainer of the [Star](https://github.com/jacojayy/star) line. Timeline Semaphore patches in the bundled Turnip driver for newer DXVK compatibility. Official site developer and maintainer. |
| **Star / Frost dev team** | The [star-emu](https://github.com/star-emu) team behind the original *Star Bionic* and *Winlator Frost* lines this build continues from. |
| **isygold** (AGBOOLA Israel Oluwagbogo) | [Star Engine / VEGAS](https://github.com/isygold/vegas-releases) — the Adreno-optimized DXVK fork this build's `v1.3-vegas` is named for, eliminating stutter and adding real-time upscaling on mobile GPUs, plus tuned [dxvk.conf profiles](https://github.com/isygold/DXVK.CONF-FILE-SETTINGS-). |
| **vivsi** | Controller support contributions. |
| **StevenMXZ** | [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) and extensive cherry-picked work implemented in this build. This includes the **new user interface** and the **Vulkan rendering** path — both of which were **still unreleased and unfinished at the time these builds and this repo were created** — along with various other cherry-picked commits. This work is set to be released properly in his upcoming **3.1**. Also the `ludashi` package rename (Xiaomi performance-mode detection) and the `redmagic` (Genshin Impact package name for RedMagic frame-gen) variant handling. |
| **GameNative** | [GameNative](https://github.com/utkarshdalal/GameNative) by **utkarshdalal** — Proton bionic translation layers and cherry-picked commits adapted into this build. |
| **leegao** (Lee Gao) | Vulkan texture-compression work used for mobile-GPU compatibility and performance — the [BCn decompression layer](https://github.com/leegao/bcn_layer) and real-time [ASTC/ETC compute-shader encoders](https://github.com/leegao). |
| **The412Banner** | Full Jetpack Compose UI migration, in-game overlay rewrite, controller-support restore (SDL2 SoName fix + four event files), Box64 edit-dialog fix, theme system, and CI/release infrastructure. Also maintains the [Nightlies WCP Hub](https://github.com/The412Banner/Nightlies) and [Banners-Turnip](https://github.com/The412Banner/Banners-Turnip). |

### Upstream stack

The Wine/translation stack this app bundles or downloads:

| Component | Author |
|---|---|
| **Wine** | [WineHQ](https://www.winehq.org/) |
| **Box64 / Box86** | [ptitSeb](https://github.com/ptitSeb) |
| **FEXCore** | [FEX-Emu](https://github.com/FEX-Emu) |
| **DXVK** | [doitsujin / Philip Rebohle](https://github.com/doitsujin) |
| **DXVK-GPLAsync patch** | [Ph42oN](https://gitlab.com/Ph42oN) |
| **DXVK-Sarek** | [pythonlover02](https://github.com/pythonlover02) |
| **VKD3D-Proton** | [Hans-Kristian Arntzen](https://github.com/HansKristian-Work) |
| **Turnip / Mesa** | [Freedreno team @ Mesa](https://gitlab.freedesktop.org/mesa/mesa) |
| **Proton layers (bionic)** | [GameNative](https://github.com/utkarshdalal/GameNative) |

Additional credits surfaced in the **Star Bionic REVAMPED** project (`star.bionic-revamp`):

- **@The412Banner** — Converting the UI to Jetpack Compose and rewriting the controller implementation.
- **@jacojayy** — Timeline Semaphore patches in Turnip.

> If you have contributed and are not listed, open a PR — this list is intended to be complete.

---

## ⚖️ Disclaimer

Winlator and its forks are unofficial community projects. They are **not** affiliated with or endorsed by Microsoft, Wine, the Mesa project, Qualcomm, or any game publisher. Compatibility varies by device GPU, Android version, and individual game.

---

## 📄 License

Inherits the license of the upstream Winlator project (**GPL-3.0**). See [`LICENSE`](LICENSE) for the full text.
