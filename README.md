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
  <a href="https://github.com/The412Banner/Bannerlator/releases/latest">
    <img src="https://img.shields.io/badge/⬇%20Download-Latest%20Release-ff2d9b?style=for-the-badge&logo=android&logoColor=white" alt="Download Latest Release">
  </a>
</p>

<p align="center">
  <a href="https://github.com/The412Banner/Bannerlator/releases/latest">Download</a> •
  <a href="https://discord.gg/n8S4G2WZQ4">Discord</a> •
  <a href="https://t.me/The412BannerGaming">Telegram</a> •
  <a href="#building">Builds</a> •
  <a href="#credits">Credits</a>
</p>

---

## 📌 Project Notice

> **Bannerlator is a personal build — made by me ([The412Banner](https://github.com/The412Banner)), for my own device, my own needs, and my own use.**
>
> It's a personal continuation of the Winlator *Star Bionic* project ([star-emu/star](https://github.com/star-emu/star)) after it was discontinued and archived. None of the original developers are involved except me; it stands on their work plus cherry-picked commits from across the community — all credited below.
>
> **This is NOT an official or general-purpose Winlator release.** It is built and tuned for *my* hardware and *my* workflow, and published **as-is** purely in case it happens to be useful to someone else.
>
> - **No guarantee it works on any other device, GPU, or Android version.**
> - **No support, and no commitment to fix anything that works for me but not for you.** If a feature works on my device, it isn't broken — for me, which is what this build is for.
> - Bug reports / feature requests for setups I don't run may simply be closed. That's not personal; this just isn't a community-support project.
>
> You're **free to use, modify, fork, or share it** (GPL-3.0). If it doesn't work on your setup, that's expected — it wasn't built for it.

---

## ℹ️ Information

| | |
|---|---|
| **App label** | `Bannerlator Bionic` (standard) · `Bannerlator Bionic PuBG` (pubg) · `Bannerlator Bionic Ludashi` (ludashi) |
| **Packages** | `com.winlator.banner` (standard) · `com.tencent.ig` (pubg) · `com.ludashi.benchmark` (ludashi) |
| **Version** | Bannerlator **V 1.9.1** — built from Star **marcescence** (`versionName 1.9.1`, `versionCode 30`) |
| **Android SDK** | `compileSdk 34` · `targetSdk 28` · `minSdk 26` (Android 8.0+) |
| **Lineage** | Winlator → cmod → Bionic Nightly → Star Bionic → **marcescence** → **Bannerlator** |

---

## 🆕 What's New in 1.9.1

> 🩹 **Hotfix release** — a small patch on top of 1.9. No new features; everything in 1.9 still applies.

- **Multi-flavor install conflict fixed.** The **PUBG** and **Ludashi** builds could fail to install alongside the **Standard** build with a *package conflict* (`INSTALL_FAILED_CONFLICTING_PROVIDER`). The content-provider (FileProvider / shared-storage) authorities were hardcoded to one shared name across all three flavors; they are now keyed per-flavor, so each variant installs side-by-side.
- **Save sharing fixed.** Sharing an exported save archive used a stale provider authority that didn't exist and would throw — it now resolves correctly on every flavor.
- **Perf HUD** now renders correctly under the **SurfaceFlinger** and **Vulkan** host renderers.

---

## ✨ Full Features

Everything Bannerlator offers, at a glance. No PC and no root required — it runs Windows apps and games directly on your Android device.

### 🍷 Windows compatibility
- **Wine** Windows compatibility layer — run native Win32/Win64 applications and games.
- **Box64 / Box86** x86 & x86-64 → ARM translation, with selectable performance presets.
- **WOWBox64** for arm64ec containers (correctly labelled per container).
- **FEXCore** as an alternative x86/x64 emulation backend.
- **arm64ec** and **x64** container support.

### 🎨 Graphics & translation layers
- **DXVK** — DirectX 8 / 9 / 10 / 11 → Vulkan (with GPLAsync and Sarek variants).
- **VKD3D-Proton** — DirectX 12 → Vulkan.
- **WineD3D / DirectDraw** OpenGL fallback paths for older titles.
- **Proton bionic** translation layers (via GameNative).
- **VEGAS** — Adreno-optimized DXVK for reduced stutter and real-time upscaling on mobile GPUs.
- **Turnip / Mesa** open-source Adreno Vulkan drivers, with Timeline Semaphore patches for newer DXVK; bundled and downloadable driver options.

### 🖥️ Renderers
- Multiple host renderers — **Vulkan**, **OpenGL**, and **VirGL**.
- > ℹ️ The **Vulkan host renderer** uses the rendering path from **[StevenMXZ](https://github.com/StevenMXZ/Winlator-Ludashi)** (Winlator-Ludashi); its `AHardwareBuffer` present path — what makes Vulkan / DXVK / VKD3D content actually display correctly — was ported from / cross-examined against **[GameNative](https://github.com/utkarshdalal/GameNative)**. See [Credits](#-credits).
- **Native Rendering+** — low-latency direct-scanout presentation on the Vulkan renderer.
- **Screen effects** on the OpenGL renderer — FXAA, SGSR, HDR, CRT, Toon, and NTSC.
- Adjustable resolution and frame-rate limit.

### 🎞️ Frame generation & pacing
- **Two selectable frame-generation engines** — pick **Off / bionic-fg / lsfg-vk** per container; the running engine is shown as a badge in the in-game drawer.
  - **bionic-fg** — powered by the **[bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg)** Vulkan layer (Lossless-Scaling lineage), bundled and ready to use out of the box.
  - **lsfg-vk** — powered by the **[lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)** Vulkan layer (Android port by [FrankBarretta](https://github.com/FrankBarretta/lsfg-vk-android)).
- > ⚠️ **lsfg-vk requires you to supply your own `Lossless.dll`.** Bannerlator bundles **no** proprietary Lossless Scaling files. You must own **[Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/)** (THS, on Steam) and import its `Lossless.dll` via **Settings → Frame Generation (lsfg-vk) → pick DLL**. The DLL is copied into app storage and serves all containers. Until you import a valid `Lossless.dll`, the **lsfg-vk** option stays greyed out; **bionic-fg** needs no DLL and works without it.
- **Live in-game controls** for whichever engine the container runs: switch between **Off / 2× / 3× / 4×** and adjust the **flow-scale** slider right from the in-game Graphics drawer, hot-reloaded with no restart.
- **FPS Limiter** — a **standalone, engine-independent** live frame cap. It paces the X11 Present extension by delaying the `IdleNotify` that frees the guest's buffer, so the game itself throttles (the in-game HUD reflects the cap and GPU/power draw drops). Works the same with frame gen **Off**, **bionic-fg**, or **lsfg-vk**, on both host renderers, all guest APIs. When **lsfg-vk** is multiplying (2×+) the limiter automatically steps aside so lsfg's own pacing governs — no double-cap. This guest-side present-pacing mechanism was ported from **[GameNative](https://github.com/utkarshdalal/GameNative)** (see [Credits](#-credits)).
- Confirmed on **both** the OpenGL and Vulkan host renderers.

### 📦 Containers
- Create and manage **multiple isolated Wine containers**.
- **Import / export** containers to move or back up setups.
- Per-container control of Wine version, graphics driver, DXVK / VKD3D version, Box64 preset, drive mappings, Z-drive selector, and environment variables.
- **Compatibility Layers download menu** — a cloud button on each component (Wine/Proton, DXVK, VKD3D, Box64/WOWBox64, FEXCore) opens a downloader to browse, install or remove versions, with **Wine/Proton tabs**, an **"in use"** marker, **install-from-file**, and **byte-accurate download + install progress bars**.

### 🕹️ Games, shortcuts & input
- **Game library** with grid or list layout, sorting, and installed/updated filters.
- Add shortcuts from external storage.
- **SteamGridDB** cover-art scraping.
- Per-game settings including display language / locale.
- **Customizable on-screen touch controls** and virtual gamepad overlays.
- **Physical controller** support (SDL2), plus touchpad / mouse emulation with adjustable cursor speed.

### 🛒 Built-in GOG store
- **Sign in with your [GOG](https://www.gog.com/) account** and browse your owned library directly in-app.
- **Download and install** your GOG games, with **cloud-save** sync and one-tap launch into a container.
- DRM-free titles only — Bannerlator does not bundle or circumvent any DRM; you install games you already own.

### 🧰 Bundled Start-menu utilities
- New containers ship with handy Windows tools in the Start menu — **Winlator File Manager (WFM)**, **AIO Graphics Test**, and **Game Controller Test**.
- **`.lnk` working-directory ("Start in") support** so shortcuts for apps that only run from their own folder launch correctly.

### 🎛️ Interface & in-game overlay
- Modern **Jetpack Compose** user interface.
- In-game overlay drawer for settings, input, and quick toggles.
- **Performance HUD** — FPS, frame time, CPU/GPU temperature, and RAM, in vertical or horizontal layout.
- **Customizable themes** — 8 presets plus an HSV colour picker, with dark mode.

### 📥 Builds & distribution
- **Three build flavors** with distinct package IDs — *standard*, *PuBG*, and *Ludashi*.
- **Optimized release builds** (not debug) for a smoother Compose UI, AOSP-testkey signed so updates install over previous installs.
- Continuous **GitHub Actions** action builds and tagged stable releases.

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
| **StevenMXZ** | [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) and extensive cherry-picked work implemented in this build. This includes the **new user interface** and the **Vulkan rendering** path — both of which were **still unreleased and unfinished at the time these builds and this repo were created** — along with various other cherry-picked commits. This work is set to be released properly in his upcoming **3.1**. The bundled **Winlator File Manager (`wfm.exe`)** — including its correct "Local Disk" drive-icon behaviour — is from StevenMXZ's **Winlator-Ludashi 3.1 hotfix** release. |
| **GameNative** | [GameNative](https://github.com/utkarshdalal/GameNative) by **utkarshdalal** — Proton bionic translation layers and cherry-picked commits adapted into this build. Its rendering pipeline was also the **reference used to fix and rewire Bannerlator's render options** — the `AHardwareBuffer` present path that makes Vulkan / DXVK / VKD3D content render correctly on both the OpenGL and Vulkan host renderers (GPUImage socket-buffer locking + EGLImage sampling, DRI3 direct-scanout, the Present extension's FLIP / COPY branches, and the Native Rendering+ direct-scanout path) was ported from and cross-examined against GameNative's implementation. The **standalone FPS limiter** is GameNative's too — its guest-side present-pacing mechanism (delaying the X11 Present `IdleNotify` to throttle the game itself, plus the rule that lsfg-vk's own pacing governs when its multiplier is ≥ 2) was ported from GameNative. |
| **xXJSONDeruloXx** | [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) — the Android/bionic Vulkan **frame-generation** layer powering Bannerlator's Frame Generation feature. Included in-tree as a git submodule with the author's permission. |
| **PancakeTAS** | [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) — the open-source Vulkan frame-generation layer (a Vulkan-layer reimplementation of Lossless Scaling's frame generation) that Bannerlator's **second, user-selectable FG engine** is built on. |
| **FrankBarretta** | [lsfg-vk-android](https://github.com/FrankBarretta/lsfg-vk-android) — the Android/bionic port of lsfg-vk (AHardwareBuffer path + `vkCmdPipelineBarrier2` shim) that runs as Bannerlator's lsfg-vk engine on the Turnip stack. The in-game live multiplier/flow-scale reload uses the `conf.toml` mtime-watch mechanism from **GameNative's** [lsfg-vk-android fork](https://github.com/GameNative). No proprietary shaders are bundled — users supply their own `Lossless.dll` ([Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) by THS) via the in-app picker. |
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
| **Frame Generation (bionic-fg)** | [xXJSONDeruloXx](https://github.com/xXJSONDeruloXx/bionic-fg) |
| **Frame Generation (lsfg-vk)** | [PancakeTAS](https://github.com/PancakeTAS/lsfg-vk) · Android port [FrankBarretta](https://github.com/FrankBarretta/lsfg-vk-android) · live-reload fork [GameNative](https://github.com/utkarshdalal/GameNative) · DLL [Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) (user-supplied) |

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
