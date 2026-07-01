<p align="center">
  <img src="logo.jpg" width="820" alt="Bannerlator" />
</p>

<h1 align="center">Bannerlator</h1>
<p align="center"><b>Windows applications and games on Android.</b></p>

<p align="center">
  <img src="https://img.shields.io/github/downloads/The412Banner/Bannerlator/total?style=for-the-badge&label=Downloads&color=ff2d9b" alt="Total Downloads">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-7a4cff?style=for-the-badge" alt="Platform">
  <img src="https://img.shields.io/badge/License-GPL--3.0-2d9bff?style=for-the-badge" alt="License">
  <a href="https://github.com/The412Banner/Bannerlator/issues/new?template=ask-the-ai.yml"><img src="https://img.shields.io/badge/💬%20Ask%20the%20AI-Ask%20about%20the%20app-7b2ff7?style=for-the-badge&logo=claude&logoColor=white" alt="Ask the AI"></a>
</p>

<p align="center">
  <a href="https://github.com/The412Banner/Bannerlator/releases/latest">
    <img src="https://img.shields.io/badge/⬇%20Download-Latest%20Release-ff2d9b?style=for-the-badge&logo=android&logoColor=white" alt="Download Latest Release">
  </a>
</p>

<p align="center">
  <a href="#-ask-me-anything">Ask AI</a> •
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
| **Version** | Bannerlator **V 2.2.2** — built from Star **marcescence** (`versionName 2.2.2`, `versionCode 37`) |
| **Android SDK** | `compileSdk 34` · `targetSdk 28` · `minSdk 26` (Android 8.0+) |
| **Lineage** | Winlator → cmod → Bionic Nightly → Star Bionic → **marcescence** → **Bannerlator** |

---

## 🆕 What's New in 2.2.2

2.2.2 brings the big one: **in-game ReShade effects** — real ReShade `.fx` post-processing you configure **per game**, toggle and tune **live**, and **stack**. It also lands a batch of fixes from GitHub and Discord reports: the **FPS limiter no longer resets** between sessions, on-screen controls honour a **white** colour, **container creation** can no longer get stuck, and very light / dark custom accents stay readable. This is an **app-side** update — **no ImageFS reinstall** — and existing containers **refresh their ReShade layer automatically** on next launch.

**🎬 In-game ReShade effects — new.** Run real ReShade `.fx` effects (colour grading, sharpen, film grain, CRT, tonemap, LUTs…) on **DXVK / VKD3D** games, compiled **on-device** via the bundled **[vkBasalt](https://github.com/DadSchoorse/vkBasalt)** layer:
- **Per-game setup** — pick effects when editing a **container** or a **game shortcut**; your choices are saved with that game.
- **On-demand catalog** of ~100 curated MIT / CC0 effects (search, browse, download only what you want) — or **drop your own** into the `ReShade/` folder (see [Adding your own ReShade effects](#-adding-your-own-reshade-effects)).
- **Dedicated in-game ReShade tab** that auto-generates properly **typed controls** — sliders, toggles, dropdowns and colour pickers — read straight from each shader, with a **Reset-to-defaults** button. Toggle and tune effects **live**, no restart, and your changes **persist per game** across quit → relaunch.
- **Solo or stack** — run a single effect or layer several at once.
- > ⚠️ **Stacking multiple effects? Add them a few at a time.** Each ReShade effect compiles on-device and costs GPU, so **selecting too many at once can stop a game from starting** — you'll get a **flat / blank screen** instead of the game. If that happens, go back into the per-game **ReShade effect** settings and **uncheck the effects one at a time** (or the specific heavy one) until the game boots correctly, then add more gradually. *(Colour effects today; depth effects like SSAO / DOF aren't supported yet.)*

**♻️ Existing containers auto-update the ReShade layer.** Updating from an earlier version? Your **existing containers pick up the new ReShade (vkBasalt) layer automatically** on next launch — no need to recreate a container or reinstall the ImageFS for ReShade to work.

**🛟 In-game drawer rail now scrolls.** On shorter screens the left icon rail could push **Exit** off the bottom; the rail is now **scrollable**, so every control — including **Exit** — is always reachable.

**🎛️ FPS limiter now sticks between sessions.** Toggling the in-game **FPS limiter** for a game launched from a shortcut is now **saved to that game** — it no longer reverts every time you close and reopen the game. *(GitHub #46.)*

**🎨 Fixes.**
- **White on-screen controls** — setting the virtual-control colour to **white** now stays white instead of falling back to blue. *(GitHub #46.)*
- **Readable light / dark accents** — a very **light** custom theme accent no longer paints buttons white-on-white (and a very **dark** one no longer goes dark-on-dark); glyphs pick a contrasting colour automatically. **AMOLED and the built-in presets are unchanged.**
- **Container creation could get stuck** — after deleting a container that still had a leftover shortcut, creating a new container could silently fail; the stale entry is now cleaned up and no longer blocks creation. *(GitHub #45.)*

<details>
<summary><b>Previously in 2.2</b> — the themeable-interface overhaul</summary>

2.2 is a **big visual overhaul**: the whole interface — and the **in-game side drawer** — now follow your chosen theme, with a redesigned drawer, **nine new colour presets**, and **per-game control colours**. It also adds **Favorites** to the File Manager and **rebuilds the controller-binding screen**, alongside a batch of readability and consistency fixes.

**🎨 A themeable interface — redesigned.** The app drawer and the in-game side drawer were rebuilt with new icons and accent-driven buttons, and — the big change — **your selected theme now recolours the entire app *and* the in-game drawer**. Previously large parts of the UI stayed blue regardless of theme; now presets and your custom accent reach the screens, dialogs, drawer, chips, sliders and overlays.
- **9 new theme presets** bring the total to **16**, plus the custom HSV accent picker: **Midnight Cobalt**, **Phosphor**, **Carbon & Ember**, **Amethyst**, **Crimson**, **Synthwave**, **Royal Gold**, **Frost** and **Monochrome** — alongside Classic Dark, **AMOLED** (still the default), Ocean, Forest, Sunset, Rose and Steel.
- **AMOLED stays the default and is unchanged**, so updating doesn't alter your look unless you choose a new theme. Your previously selected theme and custom accent are preserved.

**🕹️ Per-game on-screen control colours — new.** The on-screen touch controls follow your app theme by default, and you can now **override their colour per game**. Turn off **"Follow app theme"** in the Controls editor (in-game drawer or the out-of-game Input Controls page), pick a colour, and it's **saved with that game's profile** — so each game can keep its own control colour.

**⭐ File Manager — Favorites / bookmarked folders — new.** Pin the folders you open most and jump straight to them. A **★ button** opens a dedicated **Favorites** list, and each entry shows **exactly where it lives** — a colour-coded badge for the storage source (**Internal**, **SD card**, **Drive C:**, **Drive Z:**), the **container name** for a container drive, and the **full path** — so two games' `C:\Program Files` are never confused. Pin from a folder's **⋮ menu** or with **"Pin current folder"**, unpin with the filled ★. Favorites persist across launches; entries for a deleted container quietly drop off.

**🎮 Controller-binding screen rebuilt.** The external controller-binding screen was rebuilt in the modern UI: **labels are clearly readable under any theme**, each binding is a card matching the rest of the app, and **buttons you press while binding appear instantly**. (Builds on the 2.1.1 readability fix; bindings still persist and Ludashi-format profile import still works.)

**🧰 In-game Task Manager.** **"New Task" now works on the Vulkan / Native renderers** — the dialog used to be invisible over those surfaces — and running processes are shown as **cards**.

**🧹 Consistency & readability.** The Games and Containers lists now share one card style with consistent depth on every theme, and legacy dropdowns, spinners, dialogs and section headers follow the accent too — with a luminance floor so text never goes dark-on-dark on a dark custom accent.

</details>

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
  - > 📖 **New to VEGAS?** Read the **[VEGAS DXVK FAQ](https://htmlpreview.github.io/?https://github.com/The412Banner/Bannerlator/blob/main/docs/vegas_faq.html)** — install, config, FSR, tiers, frame generation & shader-stutter troubleshooting.
  - > 🚀 **Support VEGAS Development** — low-level graphics dev & vibecoder: debugging, refactoring & improving original DXVK code for Adreno. **[❤️ Sponsor isygold →](https://github.com/sponsors/isygold)**
- **Turnip / Mesa** open-source Adreno Vulkan drivers, with Timeline Semaphore patches for newer DXVK; bundled and downloadable driver options. A **`turnip-26.1.0`** option loads Turnip as a direct system Vulkan ICD (no adrenotools hook) so it works on **Android 10 / pre-11 devices** too.

### 🖥️ Renderers
- Multiple host renderers — **Vulkan**, **OpenGL**, and **VirGL**.
- > ℹ️ The **Vulkan host renderer** uses the rendering path from **[StevenMXZ](https://github.com/StevenMXZ/Winlator-Ludashi)** (Winlator-Ludashi); its `AHardwareBuffer` present path — what makes Vulkan / DXVK / VKD3D content actually display correctly — was ported from / cross-examined against **[GameNative](https://github.com/utkarshdalal/GameNative)**. See [Credits](#-credits).
- **Native Rendering (Low-Latency Mode)** — low-latency direct-scanout presentation on **both the Vulkan *and* OpenGL renderers**, skipping the compositor blit to cut input lag (mutually exclusive with that renderer's post-processing effects / scaling, since it bypasses the compositor).
- **Spatial upscalers on *both* the Vulkan *and* OpenGL renderers** — **SGSR** (Snapdragon GSR 1.0) and **FSR / FSR-Fit** (AMD FidelityFX Super Resolution 1.0), plus **NIS** (NVIDIA Image Scaling, Vulkan), a **Sharpen** (RCAS) mode and Linear / Nearest, all switchable live in the in-game drawer. On Vulkan it engages when a game renders below display resolution; on OpenGL it renders the scene at a reduced internal resolution and reconstructs it back up. Every sharpness slider runs 0 (off) → 100 (max).
- **Supersampling (Render scale)** — render above display resolution (1.25× / 1.5× / 2×) and downsample with a Lanczos-2 filter for DSR / OGSSAA-style anti-aliasing; set per container / per shortcut.
- **Screen effects on both the OpenGL *and* Vulkan renderers** — FXAA, Toon, CRT, NTSC, Color grading, **CAS** sharpening, and fake-HDR (the Vulkan path runs them through a new post-processing pipeline; previously they were OpenGL-only).
- **Debanding (Vulkan)** — an optional terminal dither pass that removes the visible banding from smooth gradients, skies, and dark scenes on 8-bit output, with an adjustable strength.
- **ReShade post-processing** — run real ReShade `.fx` effects (colour grading, sharpen, film grain, CRT, tonemap…) on **DXVK / VKD3D** games. Effects compile **on-device** via a bundled **[vkBasalt](https://github.com/DadSchoorse/vkBasalt)** layer; pick from an **on-demand catalog** of ~100 curated MIT/CC0 effects or drop your own into the `ReShade/` folder. A dedicated in-game **ReShade tab** auto-generates properly typed controls (sliders / toggles / dropdowns / colour pickers) from each shader, so you can **toggle and tune effects live** with a Reset-to-defaults button. Effects are configured **per game** (container or shortcut), persist across relaunch, and can be run **solo or stacked**. *(Color effects today; depth effects such as SSAO/DOF are not included yet.)*
  - > ⚠️ **Stacking multiple effects? Add them a few at a time.** Each effect compiles on-device and costs GPU — **selecting too many at once can stop a game from starting**, showing a **flat / blank screen** instead of the game. If that happens, **uncheck effects one at a time** (or the specific heavy one) in the per-game **ReShade effect** settings until it boots, then add more gradually.
- **Match refresh rate to FPS (VRR)** — the display's refresh rate can follow your frame rate: an **Auto (match FPS)** toggle or a manual **60 / 90 / 120 / 144 Hz** slider, on all three host renderers, auto-disabled on displays that don't support variable refresh.
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
- **Redesigned container cards** — a clean spec-chip layout (renderer · DXVK on top, driver · VKD3D · backend beneath) that matches the game cards.
- **Auto-close on game exit** — the session closes itself once the launched game quits (per-container "Close when game exits" toggle, on by default), so you're not left at a black Wine desktop.
- **Import / export** containers to move or back up setups.
- Per-container control of Wine version, graphics driver, DXVK / VKD3D version, Box64 preset, drive mappings, Z-drive selector, and environment variables.
- **Compatibility Layers download menu** — a cloud button on each component (Wine/Proton, DXVK, VKD3D, Box64/WOWBox64, FEXCore) opens a downloader to browse, install or remove versions, with **Wine/Proton tabs**, an **"in use"** marker, **install-from-file**, and **byte-accurate download + install progress bars**.

### 🕹️ Games, shortcuts & input
- **Game library** with grid or list layout, sorting, and installed/updated filters.
- **Redesigned game cards** — primary chips (renderer · DXVK · frame-gen) over a muted driver · VKD3D · backend line, with the resolution in the subtitle; long component names no longer blank the game title.
- Add shortcuts from external storage.
- **SteamGridDB** cover-art scraping.
- Per-game settings including display language / locale.
- **Customizable on-screen touch controls** and virtual gamepad overlays, which **follow your app theme** or take a **per-game custom colour** you set in the Controls editor.
- **Physical controller** support (SDL2), plus touchpad / mouse emulation with adjustable cursor speed. The **external controller-binding screen** lists each input as a card with readable labels, and buttons you press while binding appear instantly.

### 🛒 Built-in GOG store
- **Sign in with your [GOG](https://www.gog.com/) account** and browse your owned library directly in-app.
- **Download and install** your GOG games, with **cloud-save** sync and one-tap launch into a container.
- DRM-free titles only — Bannerlator does not bundle or circumvent any DRM; you install games you already own.

### 🧰 Bundled Start-menu utilities
- New containers ship with handy Windows tools in the Start menu — **Winlator File Manager (WFM)**, **AIO Graphics Test**, and **Game Controller Test**.
- **`.lnk` working-directory ("Start in") support** so shortcuts for apps that only run from their own folder launch correctly.

### 🎛️ Interface & in-game overlay
- Modern **Jetpack Compose** user interface with a redesigned, icon-led navigation drawer.
- **Theme-aware everywhere** — your selected preset / accent recolours the **whole app *and* the in-game side drawer**, including dialogs, chips, sliders and overlays.
- **Customizable themes** — **16 presets** (AMOLED default, Classic Dark, Ocean, Forest, Sunset, Rose, Steel, plus Midnight Cobalt, Phosphor, Carbon & Ember, Amethyst, Crimson, Synthwave, Royal Gold, Frost and Monochrome) plus an **HSV custom-accent picker**.
- In-game overlay drawer for settings, input, and quick toggles, with a Task Manager that lists processes as cards and can launch new tasks on any renderer.
- **Built-in File Manager with Favorites** — bookmark folders and jump to them from a dedicated list, each labelled by storage source (Internal / SD card / a container's Drive C: or Z:) and full path.
- **Performance HUD** — FPS, frame time, CPU/GPU temperature, and RAM, in vertical or horizontal layout.

### 📥 Builds & distribution
- **Three build flavors** with distinct package IDs — *standard*, *PuBG*, and *Ludashi*.
- **Optimized release builds** (not debug) for a smoother Compose UI, AOSP-testkey signed so updates install over previous installs.
- Continuous **GitHub Actions** action builds and tagged stable releases.

---

## 🎨 Adding your own ReShade effects

Besides the built-in download catalog, you can add **any** ReShade effect yourself by dropping it into a folder. Follow these steps exactly:

**1. Open the ReShade drop-in folder on your device** (create the `ReShade` folder if it isn't there yet):

```
Android/data/com.winlator.banner/files/ReShade/
```

> 📁 That path is for the **Standard** build. For the other builds, swap the package name: **PuBG** → `Android/data/com.tencent.ig/files/ReShade/` · **Ludashi** → `Android/data/com.ludashi.benchmark/files/ReShade/`.

**2. Make one folder per effect.** Name the folder whatever you want the effect to be **called in the menu** — for example `MySepia`.

**3. Put the effect's files inside that folder — all in the same place, next to the `.fx`:**
- the effect's **`.fx`** file (required),
- any **`.fxh`** files it `#include`s (very common — e.g. `ReShade.fxh`, `ReShadeUI.fxh`),
- any **image / texture** files the effect uses.

```
ReShade/
  MySepia/
    MySepia.fx          ← the effect (folder name match = used first)
    ReShade.fxh         ← copy in any .fxh the .fx #includes
    ReShadeUI.fxh
    noise.png           ← copy in any textures it uses
```

**4. Pick it in the app.** Open the app → edit a **container** or a **game shortcut** → **ReShade effect** picker. Your folder now appears in the list — select it.

**5. Use it in-game.** Launch a **DirectX (DXVK / VKD3D) game**, open the in-game drawer → **ReShade tab**, and turn the effect on/off and tune its sliders **live**.

> #### ⚠️ Read this if something doesn't show up or work
> - **Only colour effects work** — sharpen, colour grading, film grain, CRT, tonemap, vignette, etc. **Depth effects (SSAO, depth-of-field, MXAO) do not work yet.**
> - ReShade only affects **DirectX games running through DXVK / VKD3D** — it does nothing on OpenGL / WineD3D / older 2D titles.
> - **Effect not in the list?** Make sure it's in **its own subfolder** and that the subfolder actually contains a `.fx` file (a loose `.fx` sitting directly in `ReShade/` is ignored).
> - **Effect selected but no change in-game?** Most often a missing `#include` — open the `.fx` in a text editor, find any `#include "Something.fxh"` lines, and make sure each of those `.fxh` files is copied into the **same folder** as the `.fx`. Same for any texture files.
> - **Game won't start / flat or blank screen after enabling effects?** You likely **stacked too many effects at once**. Each one compiles on-device and costs GPU, and too many together can stop the game from launching. Go back into the per-game **ReShade effect** settings and **uncheck the effects one at a time** (or the specific heavy one) until the game boots correctly, then re-enable them gradually. Adding effects **a few at a time** avoids this.
> - **Can't even find `Android/data`?** Many stock file managers hide it on Android 11+. Use a file manager that can open `Android/data`, or copy the effect folder over from a PC via a USB cable, then drop it in.

---

## 🎮 Frontends Workaround

Bannerlator does not work by itself on frontends out of the box. See the [frontends workaround guide](https://github.com/star-emu/star/blob/marcescence/marcescence-frontends.md) to get it running.

---

## 🛠️ Building

This project is built via **GitHub Actions only** — local builds are not supported.

- **Action builds** — every fix is compiled and published as a downloadable workflow artifact.
- **Releases** — tagged stable builds are published as GitHub Releases.

---

## 🤖 Ask Me Anything

Got a question about Bannerlator? **Ask the codebase directly.** An AI reads the
actual source code and answers with the exact file names and line numbers, so you
can check it yourself. It never guesses — if the answer isn't in the code, it says so.

<p align="center">
  <a href="https://github.com/The412Banner/Bannerlator/issues/new">
    <img src="https://img.shields.io/badge/💬%20Ask%20a%20Question-Open%20an%20issue-7b2ff7?style=for-the-badge&logo=claude&logoColor=white" alt="Ask a Question">
  </a>
</p>

**It's three steps:**

1. **[Open an issue](https://github.com/The412Banner/Bannerlator/issues/new)** (you'll need a free GitHub account).
2. Type your question — be specific, and name a feature, setting, or file.
3. Submit. The AI replies in a comment on your issue, usually within **1–2 minutes**.

That's it — no form, no approval step, nothing else to do.

> ℹ️ The AI replies to **every** new issue automatically. A few per person per day
> are free; past that, it will ask you to try again later.

**Good things to ask:**

- *"How does the FPS limiter work?"*
- *"Where is the GOG store integration implemented?"*
- *"What values does the scaling mode picker accept?"*
- *"How are release builds signed and distributed?"*

*Avoid device-specific troubleshooting like "why is my game slow?" — the AI explains
what the **code** does, not how a game runs on your phone.*

<details>
<summary>Prefer the command line?</summary>

With [opencode](https://opencode.ai) installed (`npm install -g opencode-ai`), run the
same agent locally against a clone of this repo:

```
opencode run "your question" --agent ama-agent --model opencode/big-pickle
```
</details>

<details>
<summary><b>Maintainers / forks — one-time setup</b></summary>

The bot runs on the **opencode/big-pickle** model via your opencode credentials
(not a separate API key). To enable it on a fork:

1. Locally run `cat ~/.local/share/opencode/auth.json` and copy the whole JSON.
2. Add it as a repository secret named **`OPENCODE_AUTH`** under
   **Settings → Secrets and variables → Actions**.
3. Make sure the `answered` and `question` labels exist.

Every newly opened issue is answered automatically, bounded by a per-user daily
limit and a monthly cap — tune both at the top of
`.github/workflows/ama-answer.yml` (`PER_USER_PER_DAY`, `MONTHLY_CAP`;
maintainers are exempt from the daily limit). You can also force a re-run on an
older issue by adding the **`question`** label. Without the secret, the bot posts
a notice explaining what's missing.
</details>

---

## 🙏 Credits

This build stands on a long chain of prior work — its direct lineage, plus the projects whose commits and work are cherry-picked and implemented here:

| Contributor | Contribution |
|---|---|
| **brunodev85** | Original [Winlator](https://github.com/brunodev85/winlator) — Wine + Box64 + Turnip on Android. Foundation of every fork below. Also serves the `input_controls` profiles consumed by this fork: <https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/> |
| **coffincolors** | [`cmod` Winlator fork](https://github.com/coffincolors/winlator) — package `com.winlator.cmod` and the customization layer this codebase is built on. |
| **Pipetto-crypto** | [Winlator Bionic fork](https://github.com/Pipetto-crypto/winlator) (the "Bionic" half of *Star Bionic*) and the upstream [Box64 fix branch](https://github.com/Pipetto-crypto/box64). Co-credited on cmod. Also packaged **vkBasalt** into the Winlator shortcut pipeline — the integration Bannerlator's **ReShade** feature builds on. |
| **jacojayy** | Maintainer of the [Star](https://github.com/jacojayy/star) line. Timeline Semaphore patches in the bundled Turnip driver for newer DXVK compatibility. Official site developer and maintainer. |
| **Star / Frost dev team** | The [star-emu](https://github.com/star-emu) team behind the original *Star Bionic* and *Winlator Frost* lines this build continues from. |
| **isygold** (AGBOOLA Israel Oluwagbogo) | [Star Engine / VEGAS](https://github.com/isygold/vegas-releases) — the Adreno-optimized DXVK fork this build's `v1.3-vegas` is named for, eliminating stutter and adding real-time upscaling on mobile GPUs, plus tuned [dxvk.conf profiles](https://github.com/isygold/DXVK.CONF-FILE-SETTINGS-). See the **[VEGAS DXVK FAQ](https://htmlpreview.github.io/?https://github.com/The412Banner/Bannerlator/blob/main/docs/vegas_faq.html)** for help & configuration.<br>🚀 **Support VEGAS Development** — low-level graphics dev & vibecoder: debugging, refactoring & improving original DXVK code for Adreno. **[❤️ Sponsor →](https://github.com/sponsors/isygold)** |
| **vivsi** | Controller support contributions. |
| **StevenMXZ** | [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) and extensive cherry-picked work implemented in this build. This includes the **new user interface** and the **Vulkan rendering** path — both of which were **still unreleased and unfinished at the time these builds and this repo were created** — along with various other cherry-picked commits. This work is set to be released properly in his upcoming **3.1**. The bundled **Winlator File Manager (`wfm.exe`)** — including its correct "Local Disk" drive-icon behaviour — is from StevenMXZ's **Winlator-Ludashi 3.1 hotfix** release. |
| **GameNative** | [GameNative](https://github.com/utkarshdalal/GameNative) by **utkarshdalal** — Proton bionic translation layers and cherry-picked commits adapted into this build. Its rendering pipeline was also the **reference used to fix and rewire Bannerlator's render options** — the `AHardwareBuffer` present path that makes Vulkan / DXVK / VKD3D content render correctly on both the OpenGL and Vulkan host renderers (GPUImage socket-buffer locking + EGLImage sampling, DRI3 direct-scanout, the Present extension's FLIP / COPY branches, and the Native Rendering+ direct-scanout path) was ported from and cross-examined against GameNative's implementation. The **standalone FPS limiter** is GameNative's too — its guest-side present-pacing mechanism (delaying the X11 Present `IdleNotify` to throttle the game itself, plus the rule that lsfg-vk's own pacing governs when its multiplier is ≥ 2) was ported from GameNative. |
| **xXJSONDeruloXx** | [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) — the Android/bionic Vulkan **frame-generation** layer powering Bannerlator's Frame Generation feature. Included in-tree as a git submodule with the author's permission. |
| **PancakeTAS** | [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) — the open-source Vulkan frame-generation layer (a Vulkan-layer reimplementation of Lossless Scaling's frame generation) that Bannerlator's **second, user-selectable FG engine** is built on. |
| **FrankBarretta** | [lsfg-vk-android](https://github.com/FrankBarretta/lsfg-vk-android) — the Android/bionic port of lsfg-vk (AHardwareBuffer path + `vkCmdPipelineBarrier2` shim) that runs as Bannerlator's lsfg-vk engine on the Turnip stack. The in-game live multiplier/flow-scale reload uses the `conf.toml` mtime-watch mechanism from **GameNative's** [lsfg-vk-android fork](https://github.com/GameNative). No proprietary shaders are bundled — users supply their own `Lossless.dll` ([Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) by THS) via the in-app picker. |
| **DadSchoorse** | [vkBasalt](https://github.com/DadSchoorse/vkBasalt) (zlib) — the Vulkan post-processing layer that embeds the ReShade FX compiler. Bannerlator's **ReShade** feature is a continuation of this work: the bundled layer is built from DadSchoorse's source, patched for live on-device toggle and slider control. The bundled / catalog `.fx` effects are MIT / CC0 shaders by the **ReShade ([crosire](https://github.com/crosire/reshade-shaders))**, **prod80 ([prod80-reshade-repository](https://github.com/prod80/prod80-reshade-repository))**, **luluco250 ([FXShaders](https://github.com/luluco250/FXShaders))** and **fubax** authors, each under their own MIT / CC0 license. |
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
| **VEGAS** (Adreno-tuned DXVK / GPLAsync fork — `v1.3-vegas`) | [isygold](https://github.com/isygold/vegas-releases) · [FAQ](https://htmlpreview.github.io/?https://github.com/The412Banner/Bannerlator/blob/main/docs/vegas_faq.html) · [❤️ Sponsor](https://github.com/sponsors/isygold) |
| **VKD3D-Proton** | [Hans-Kristian Arntzen](https://github.com/HansKristian-Work) |
| **Turnip / Mesa** | [Freedreno team @ Mesa](https://gitlab.freedesktop.org/mesa/mesa) |
| **Proton layers (bionic)** | [GameNative](https://github.com/utkarshdalal/GameNative) |
| **Frame Generation (bionic-fg)** | [xXJSONDeruloXx](https://github.com/xXJSONDeruloXx/bionic-fg) |
| **Frame Generation (lsfg-vk)** | [PancakeTAS](https://github.com/PancakeTAS/lsfg-vk) · Android port [FrankBarretta](https://github.com/FrankBarretta/lsfg-vk-android) · live-reload fork [GameNative](https://github.com/utkarshdalal/GameNative) · DLL [Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) (user-supplied) |
| **Post-processing (ReShade / vkBasalt)** | [vkBasalt](https://github.com/DadSchoorse/vkBasalt) by [DadSchoorse](https://github.com/DadSchoorse) (zlib) · Winlator packaging [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator) · effects by [crosire](https://github.com/crosire/reshade-shaders) · [prod80](https://github.com/prod80/prod80-reshade-repository) · [luluco250](https://github.com/luluco250/FXShaders) · fubax (MIT / CC0) |

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
