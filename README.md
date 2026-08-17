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
  <a href="#-contents">Contents</a> •
  <a href="#-ask-me-anything">Ask AI</a> •
  <a href="https://github.com/The412Banner/Bannerlator/releases/latest">Download</a> •
  <a href="https://discord.gg/n8S4G2WZQ4">Discord</a> •
  <a href="https://t.me/The412BannerGaming">Telegram</a> •
  <a href="#️-building">Builds</a> •
  <a href="#-credits">Credits</a>
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
| **Version** | Bannerlator **V 2.6** — built from Star **marcescence** (`versionName 2.6`, `versionCode 43`) |
| **Android SDK** | `compileSdk 34` · `targetSdk 28` · `minSdk 26` (Android 8.0+) |
| **Lineage** | Winlator → cmod → Bionic Nightly → Star Bionic → **marcescence** → **Bannerlator** |

---

## 📖 Contents

- [📌 Project Notice](#-project-notice)
- [ℹ️ Information](#ℹ️-information)
- [🆕 What's New in 2.5.2](#-whats-new-in-252)
- [✨ Full Features](#-full-features)
  - [🍷 Windows compatibility](#-windows-compatibility)
  - [🎨 Graphics & translation layers](#-graphics--translation-layers)
  - [🖥️ Renderers](#️-renderers)
  - [🎞️ Frame generation & pacing](#️-frame-generation--pacing)
  - [📦 Containers](#-containers)
  - [🕹️ Games, shortcuts & input](#️-games-shortcuts--input)
  - [🌐 Community Configs](#-community-configs)
  - [🛒 Built-in stores & cross-store Download Manager](#-built-in-stores--cross-store-download-manager)
  - [🔒 Security Hardening & your store accounts](#-security-hardening--your-store-accounts)
  - [🧰 Bundled Start-menu utilities](#-bundled-start-menu-utilities)
  - [🎛️ Interface & in-game overlay](#️-interface--in-game-overlay)
  - [📥 Builds & distribution](#-builds--distribution)
- [🎨 Adding your own ReShade effects](#-adding-your-own-reshade-effects)
- [🎮 Frontends Workaround](#-frontends-workaround)
- [🛠️ Building](#️-building)
- [🤖 Ask Me Anything](#-ask-me-anything)
- [🙏 Credits](#-credits)
  - [Upstream stack](#upstream-stack)
  - [Community reports & requests](#community-reports--requests)
- [⚖️ Disclaimer](#️-disclaimer)
- [📄 License](#-license)

---

## 🆕 What's New in 2.5.2

2.5.2 adds **Community Configs** — a way to browse **community-shared, per-game/per-device tuning configs** right inside the app and apply a known-good setup to your game in one tap, without hunting through Discord screenshots. It also ships **two new Proton 11 x86-64 compatibility layers** in the downloadable catalog. Like the last few releases it's an **app-side** update — **no ImageFS reinstall** — your containers, themes, custom accent and per-game settings carry over untouched; just install over 2.5.1.

**🌐 Community Configs — new.** A new **globe** button in the **Games** header opens a browsable catalog of community game-configs. Bannerlator matches your installed games to the catalog, and a **"Matches my device"** filter narrows it to configs shared from hardware like yours. Each shared config is its own card showing its **★ upvotes** and **↓ downloads** (best-rated first), the device it came from, and its date.

- **One-tap Apply.** Tap a config → **Apply to game** and Bannerlator **surgically merges** just that config's settings — DXVK / VKD3D / Turnip driver / FEX preset / renderer / resolution / launch args / environment variables — into your shortcut, **preserving everything else you've set** (BCn options, HUD, colour toggles, and the rest are untouched). It'll even apply a config to a *different* game, warning you first.
- **Smart install of what's missing.** If a config needs a **DXVK / VKD3D / FEXCore** build or a **Turnip GPU driver** you don't have, install it inline right there — an exact match installs with one confirm, or you pick from the closest versions (or browse all) — and the config **auto-applies** once it lands, with a ✓. FEX date-stamped builds are matched by their **YYMM** build tag so a dated config resolves to the right monthly build, and a component you already have is recognised instead of erroring.
- **Config detail page.** Tap **View details** for a config's provenance (source device / SoC / app / date), exactly **what it sets** in Bannerlator's own component terms, and a **preview of what would change** on your shortcut before you apply. The detail page also shows the config's **description, upvotes, downloads and comments** live — and you can **upvote** and **leave a comment** yourself.

> ℹ️ Community Configs is **read-only for your setup** — it only ever changes a shortcut when *you* tap Apply, and it never touches your containers, imagefs or existing settings.

**🍷 Two new Proton 11 x86-64 compatibility layers — new.** Two **Proton 11.0-1 (x86_64)** compatibility layers are now downloadable in-app from the **Compatibility Layers** menu (Wine/Proton tab): **`proton-11.0-1-x86_64-sdk28`** for Android 9-era devices and **`proton-11.0-1-x86_64-sdk35`** for Android 15 — pick the one matching your Android version.

<details>
<summary><b>Previously in 2.5.1</b> — the compatibility & tooling release</summary>

2.5.1 is a **compatibility-and-tooling** follow-up to 2.5: it fixes crashes and swapped colours on the **SurfaceFlinger** renderer, adds a live **FEX runtime indicator** so you can see exactly what's translating your game, makes **FEXCore unixlib (`.so`)** handling correct when you swap FEXCore versions per game, and expands the **Environment Variable presets**.

**🎨 SurfaceFlinger renderer: colour & crash fix — new.** The **SurfaceFlinger** host renderer (ASurfaceRenderer) gets a crash-and-colour-accuracy fix: the red/blue channel swap is corrected, a GPU-side format converter and proper fencing are added, and the OpenGL / Vulkan / DRI3 paths are left untouched. A new **"Correct SurfaceFlinger colours"** toggle is available **per container and per game** (shown inline under the **Renderer** picker when SurfaceFlinger is selected, on by default). Device-verified on **Adreno 750**: *DiRT 3* on **DXVK + SurfaceFlinger** renders with correct colours, no R/B swap, and no Vulkan / OpenGL regression. *(Ported from [GameNative](https://github.com/utkarshdalal/GameNative) #1620 / #1644.)*

**🔎 FEX runtime indicator — new.** The **Graphics** tab now shows a live badge, read straight from the running game: **arm64ec** vs **x86-64**, the translator (**FEXCore / wowbox64 / Box64**), and — for FEXCore — whether the native **unixlib (`.so`)** or the classic **DLL** path is active. No more guessing what's under the hood.

**🧩 FEXCore unixlib (`.so`) auto-match — new.** Users run different FEXCore versions per game or container — some DLL-only, some with the native `.so` unixlib. 2.5.1 keeps the shared `.so` **in sync with your selection on every launch**: a unixlib build gets its **matching** `.so` placed, a DLL-only build gets it **cleared**. No stale `.so` silently overriding a DLL-only choice, no mismatched `.dll` / `.so` pair — and uninstalling a unixlib FEXCore removes its `.so` too.

**🧰 18 new Environment Variable presets — new.** The **Add Environment Variable** picker gained **18 presets** across **DXVK / VKD3D / Wine / Mesa** — including `DXVK_DISABLE_TIMELINE_SEMAPHORES`, `VKD3D_SHADER_MODEL`, `DXVK_FRAME_RATE`, `VKD3D_CONFIG`, `WINEFSYNC`, `WINE_FULLSCREEN_FSR` and `MESA_VK_WSI_PRESENT_MODE` — so common tuning vars are one tap away instead of typed by hand. *(Requested by Angel.)*

<details>
<summary><b>Previously in 2.5</b> — the Mali hardening release</summary>

2.5 is the **Mali hardening** release. If you're on a **Mali** or **Xclipse** GPU, this is the big one: **BC-texture (BCn) games that used to crash or render as black / garbled textures now work**, backed by a full sign-off on real **Mali-G57** hardware — plus an **in-game logging overhaul** so reporting a problem no longer means digging through `Android/data`. It's an **app-side** update — **no ImageFS reinstall** — your containers, themes, custom accent and per-game settings carry over untouched; the new Mali driver assets are pulled into your existing containers automatically on next launch.

**🟢 Mali & Xclipse: BC-texture games now work — new.** Mali / Xclipse GPUs don't decode BC (BCn) textures in hardware, so many games crashed, ran out of VRAM, or showed black / missing textures. A new **"Wrapper + bcn_layer"** graphics driver (pick it per container under **Graphics driver**) transcodes those textures on the GPU at runtime so the games just run — **device-proven on Mali-G57 (Helio G99)**, where *MiSide* went from crashing to rendering correctly at **~34 fps** with **zero** buffer errors. It's backed by **[leegao](https://github.com/leegao)**'s **bcn_layer shader-v3** (up to ~3.5× faster BCn / ASTC transcoding), with a **BCn Layer Settings** panel to tune force-decode, ETC2 / ASTC transcode, image-view mode and debug logging. A second **"Wrapper-gamenative"** driver ships **labelled experimental** (BCn baked into the wrapper; Adreno-only in practice). *(Driven end-to-end by @kylinzang on Mali-G57 — [#70](https://github.com/The412Banner/Bannerlator/issues/70).)*

**🧾 In-game logging overhaul — new.** Reporting a bug from inside a game is finally painless: a **Copy button** in the in-game Debug Logs panel copies the whole log (including BCn transfer stats) to the clipboard; a **selectable log location** (**Settings → Logs**: App data, Download, Documents or a custom folder) lets you grab every log straight from a file manager with **no shizuku / adb**; **DXVK, DXGI and VKD3D logs are co-located** with the Wine log in one folder (they no longer hide in the game directory); and the **Wine Debug Channels** dialog now **scrolls**. *(All three ideas from @kylinzang — [#70](https://github.com/The412Banner/Bannerlator/issues/70).)*

**🔧 Fixes.** The `wrapper_DestroyBuffer: null buffer` **log spam is eliminated** (bundled Vulkan wrapper bumped to **ETC2-Milestone-2**); the in-game **Copy-logs action row is pinned on-screen** (it was pushed off the bottom in landscape); and the **release-notes pipeline is hardened** against backticks / shell metacharacters.

</details>
</details>

<details>
<summary><b>Previously in 2.4</b> — the fit & finish release</summary>

2.4 is the **fit & finish** release. Take control of **how your game fills the screen**, import files **without fighting Android's system picker**, and make your **in-game choices stick per game**. Nearly every headline feature here came straight from a GitHub report or request. It's an **app-side** update — **no ImageFS reinstall** — your containers, themes, custom accent and per-game settings carry over untouched.

**🖥️ Fullscreen aspect-ratio modes — new.** No more forced stretching on wide games. Choose exactly how the picture fits the screen, **per container *and* per game**: **Off** (windowed, letterboxed), **Fit** (fullscreen, aspect preserved), **Stretch** (fills, ignores aspect), **Fill** (fills with aspect kept, cropping the overflow — no bars, no distortion) and **Integer** (largest whole-number scale, pixel-perfect and centered). Switch live from the in-game drawer with a new **five-button selector** that applies instantly and keeps the drawer open so you can compare. Works on the OpenGL, Vulkan and SurfaceFlinger renderers. *(GitHub #71.)*

**📁 Import files with the built-in File Manager — new.** Android's system file picker (SAF) silently fails or crawls on a lot of OEM skins. Every local import now opens **Bannerlator's own File Manager** in a pick mode instead — covering **content packs (`.wcp`), control profiles (`.icp`), wallpapers, custom icons, saves, shortcuts, drivers and the big Proton / Wine / DXVK / box64 / FEXCore assets**. It starts in **Download**, **remembers your last folder**, filters to the file types you need, and now shows a **real thumbnail** for image files (jpg/png/webp…) instead of a generic icon — so picking a wallpaper is no longer done blind. Large imports show a **progress bar with percentage and ETA**. The system picker is still available as a **"Pick via system…"** option for cloud / document providers. *(GitHub #73.)*

**🎚️ In-game choices that stick — per game — new.** Three settings you tune in-game are now remembered per game and restored on relaunch: the **Scaling mode** (SGSR / FSR / FSR-Fit / Sharpen / NIS used to reset to a plain filter every launch), the **Fullscreen mode**, and your dragged **FPS overlay position** (classic *and* GameHub HUD).

**🧩 Choose your DLC + smarter Steam installs — new.** A **"Choose DLC"** sheet lets you opt out of owned DLC before downloading, with the download size updating **live** as you check and uncheck; an **"Includes DLC"** line on the detail page; and a **true-size install fix** — Steam's catalog over-reported some games' sizes, so a fully-downloaded game could be wrongly flagged "incomplete" and refuse to launch, which is now resolved by fetching the real depot-manifest sizes. The detail page also gains a **size breakdown** (on-disk footprint, download size, catalog size, free space) and **download ETA + speed** across the detail page, Download Manager and notification. *(Install-blocker reported by @Devaspe.)*

**🖼️ Container wallpaper picker — new.** Pick an image as your container's desktop wallpaper — the picker now actually appears in the container editor — and choose whether a wallpaper applies to **just this container** or **globally** to all of them. *(GitHub #66.)*

**💾 Back up & restore game saves — new.** Export and import your saves as **GameHub-compatible zips**, with per-game save discovery, a confirm checklist before anything is overwritten, and a caution prompt so you verify before replacing originals.

**🔧 Fixes.** The **magnifier follows the cursor on Vulkan** now (parity with OpenGL, fullscreen and windowed) *(GitHub #44)* and **no longer dims the whole screen** while zoomed; the Settings **"Enable File Provider" help button no longer crashes**; and **Scrape cover** is available from the list-view overflow menu, not just the grid.

<details>
<summary><b>Previously in 2.3</b> — the storefronts release</summary>

2.3 is the **storefronts** release. Bannerlator gains a full **built-in Steam store** — sign in with **username/password or QR code**, browse your owned library, and **download + install** your games through a native depot engine — plus new **Epic Games** and **Amazon Games** stores, with the existing **GOG** store folded into a new **cross-store Download Manager**. One ⬇ manager shows every active download and your whole installed library across **all four stores**, with **background downloads** that survive leaving the app and appear in your **notification shade**. For offline / emulated play there's an optional **Goldberg auto-patch** for Steam games, a **4-tier download-speed** picker, and this release **hardens store logs** so credentials and tokens are scrubbed from anything you might share. It's an **app-side** update — **no ImageFS reinstall** — your containers, themes and settings carry over untouched.

**🎮 Built-in Steam store — new.** Sign in to Steam (**username + password** *or* **QR code**), browse your owned library, and **download + install** games through a built-in **depot engine** (built on **[JavaSteam](https://github.com/Longi94/JavaSteam)**) straight into a container. Includes a **GameNative-style 4-tier download speed** (Slow / Medium / Fast / Blazing, cores × ratio), **session hardening** that recovers from Steam's ~1-hour connection-manager logoff so long installs survive, an in-header **connection / login status pill**, a **depot-download OOM fix**, and an optional per-download **"Log debug session"** toggle.

**🕹️ Goldberg auto-patch — new.** On a Steam game's detail page, apply a **[Goldberg](https://mr_goldberg.gitlab.io/goldberg_emulator/) / gbe_fork** patch for **offline / emulated** play in three tiers — **Regular**, **Experimental**, **ColdClient** — installed automatically and cleanly reverted on switch-back. *(Modifies a game's shipped files to run offline/emulated — **use at your own risk**, for games you own.)*

**⬇️ Cross-store Download Manager — new.** One unified **⬇ manager** across Steam, Epic, GOG and Amazon: see active downloads *and* your installed library in one place, with **live two-bar** download/install progress, **background downloads + shade notifications** (a foreground service keeps them running after you leave the app), **launch / verified uninstall**, a single **source-of-truth install state** across detail / card / list, and a new **"Default screen on launch"** setting.

**🛍️ Epic, GOG & Amazon stores.** **Amazon Games** and **Epic Games** stores added — sign in, browse your library, and **download / install / launch** (including Epic **free games**) — and **GOG** wired into the Download Manager. All non-Steam store pages are **restyled to Material 3** to match the app theme and the Steam layout, with live download **%** on detail pages, cover art, launch fixes, a **themed container picker**, and **themed toasts** (fixing an unreadable black-box toast on some ROMs).

**🔒 Security & your accounts.** The Steam / Epic / GOG / Amazon sign-ins are a **third-party login system, exactly like any other emulator or launcher** that logs into these stores — Bannerlator is **not affiliated with or endorsed by** Valve/Steam, Epic, GOG or Amazon, and you use them **at your own risk**. This release **redacts** credentials and identifiers from logs — signed download / manifest URLs, OAuth authorization codes, GOG `client_secret` + `refresh_token`, and account identity IDs are stripped from **logcat *and* the shareable diagnostic files** via a new `StoreLog.redactUrl` helper (Steam was already redacted). Even so, **be cautious about sharing any log or debug file publicly** — they can still contain other diagnostic detail. See the [Security Hardening](#-security-hardening--your-store-accounts) section below.

**🔓 Steam QR sign-in re-enabled.** With the new logoff-recovery path in place, **QR login is back on** — a QR session stores the same credentials as a password login and recovers the same way. If downloads or the session keep dropping after a QR sign-in, sign out and use **username + password** (the more durable path).

<details>
<summary><b>Previously in 2.2.2</b> — the in-game ReShade release</summary>

2.2.2 brought the big one: **in-game ReShade effects** — real ReShade `.fx` post-processing you configure **per game**, toggle and tune **live**, and **stack**. It also landed a batch of fixes from GitHub and Discord reports: the **FPS limiter no longer resets** between sessions, on-screen controls honour a **white** colour, **container creation** can no longer get stuck, and very light / dark custom accents stay readable. That was an **app-side** update with **no ImageFS reinstall** — existing containers **refreshed their ReShade layer automatically** on next launch.

**🎬 In-game ReShade effects.** Run real ReShade `.fx` effects (colour grading, sharpen, film grain, CRT, tonemap, LUTs…) on **DXVK / VKD3D** games, compiled **on-device** via the bundled **[vkBasalt](https://github.com/DadSchoorse/vkBasalt)** layer:
- **Per-game setup** — pick effects when editing a **container** or a **game shortcut**; your choices are saved with that game.
- **On-demand catalog** of ~100 curated MIT / CC0 effects (search, browse, download only what you want) — or **drop your own** into the `ReShade/` folder (see [Adding your own ReShade effects](#-adding-your-own-reshade-effects)).
- **Dedicated in-game ReShade tab** that auto-generates properly **typed controls** — sliders, toggles, dropdowns and colour pickers — read straight from each shader, with a **Reset-to-defaults** button. Toggle and tune effects **live**, no restart, and your changes **persist per game** across quit → relaunch.
- **Solo or stack** — run a single effect or layer several at once.
- > ⚠️ **Stacking multiple effects? Add them a few at a time.** Each ReShade effect compiles on-device and costs GPU, so **selecting too many at once can stop a game from starting** — you'll get a **flat / blank screen** instead of the game. If that happens, go back into the per-game **ReShade effect** settings and **uncheck the effects one at a time** (or the specific heavy one) until the game boots correctly, then add more gradually. *(Colour effects today; depth effects like SSAO / DOF aren't supported yet.)*

**♻️ Existing containers auto-update the ReShade layer** on next launch — no need to recreate a container or reinstall the ImageFS for ReShade to work.

**🛟 In-game drawer rail now scrolls** so every control — including **Exit** — is always reachable on short screens.

**🎛️ FPS limiter now sticks between sessions** for a game launched from a shortcut. *(GitHub #46.)*

**🎨 Fixes** — **white on-screen controls** stay white *(GitHub #46)*; very **light / dark custom accents** stay readable (glyphs pick a contrasting colour, AMOLED and presets unchanged); and **container creation** can no longer get stuck on a leftover shortcut *(GitHub #45)*.

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

</details>

</details>

</details>

---

## ✨ Full Features

Everything Bannerlator offers, at a glance. No PC and no root required — it runs Windows apps and games directly on your Android device.

### 🍷 Windows compatibility
- **Wine** Windows compatibility layer — run native Win32/Win64 applications and games.
- **Box64 / Box86** x86 & x86-64 → ARM translation, with selectable performance presets.
- **WOWBox64** for arm64ec containers (correctly labelled per container).
- **FEXCore** as an alternative x86/x64 emulation backend — with **automatic unixlib (`.so`) matching**: whichever FEXCore version you pick per game or container, the native `.so` companion is kept in sync on every launch (matched for unixlib builds, cleared for DLL-only), so there's never a stale or mismatched `.so`.
- **arm64ec** and **x64** container support.

### 🎨 Graphics & translation layers
- **DXVK** — DirectX 8 / 9 / 10 / 11 → Vulkan (with GPLAsync and Sarek variants).
- **VKD3D-Proton** — DirectX 12 → Vulkan.
- **WineD3D / DirectDraw** OpenGL fallback paths for older titles.
- **Proton bionic** translation layers (via GameNative) — including **Proton 11.0-1** in **arm64ec** and **x86-64** builds, packaged per Android SDK (**SDK 28** for Android 9-era, **SDK 35** for Android 15) and downloadable from the Compatibility Layers menu.
- **VEGAS** — Adreno-optimized DXVK for reduced stutter and real-time upscaling on mobile GPUs.
  - > 📖 **New to VEGAS?** Read the **[VEGAS DXVK FAQ](https://htmlpreview.github.io/?https://github.com/The412Banner/Bannerlator/blob/main/docs/vegas_faq.html)** — install, config, FSR, tiers, frame generation & shader-stutter troubleshooting.
  - > 🚀 **Support VEGAS Development** — low-level graphics dev & vibecoder: debugging, refactoring & improving original DXVK code for Adreno. **[❤️ Sponsor isygold →](https://github.com/sponsors/isygold)**
- **Turnip / Mesa** open-source Adreno Vulkan drivers, with Timeline Semaphore patches for newer DXVK; bundled and downloadable driver options. A **`turnip-26.1.0`** option loads Turnip as a direct system Vulkan ICD (no adrenotools hook) so it works on **Android 10 / pre-11 devices** too.
- **BCn transcoding for Mali / Xclipse** — a **"Wrapper + bcn_layer"** graphics driver ([leegao](https://github.com/leegao)'s [bcn_layer](https://github.com/leegao/bcn_layer), shader-v3) that decodes BC textures on the GPU, so BCn games run on GPUs without hardware BC support — with a **BCn Layer Settings** panel (force-decode, ETC2 / ASTC transcode, image-view mode, debug logging). An experimental **"Wrapper-gamenative"** driver (BCn baked into the wrapper, Adreno-only) is also selectable. *(Device-proven on Mali-G57.)*

### 🖥️ Renderers
- Multiple host renderers — **Vulkan**, **OpenGL**, **SurfaceFlinger**, and **VirGL**.
- **SurfaceFlinger renderer colour fix** — the SurfaceFlinger (ASurfaceRenderer) host renderer got a crash + colour-accuracy fix (red/blue channel swap corrected, GPU-side format converter, proper fencing), with a **"Correct SurfaceFlinger colours"** toggle available **per container and per game** (shown inline under the Renderer picker when SurfaceFlinger is selected, on by default). *(Ported from [GameNative](https://github.com/utkarshdalal/GameNative) #1620 / #1644.)*
- > ℹ️ The **Vulkan host renderer** uses the rendering path from **[StevenMXZ](https://github.com/StevenMXZ/Winlator-Ludashi)** (Winlator-Ludashi); its `AHardwareBuffer` present path — what makes Vulkan / DXVK / VKD3D content actually display correctly — was ported from / cross-examined against **[GameNative](https://github.com/utkarshdalal/GameNative)**. See [Credits](#-credits).
- **Native Rendering (Low-Latency Mode)** — low-latency direct-scanout presentation on **both the Vulkan *and* OpenGL renderers**, skipping the compositor blit to cut input lag (mutually exclusive with that renderer's post-processing effects / scaling, since it bypasses the compositor).
- **Spatial upscalers on *both* the Vulkan *and* OpenGL renderers** — **SGSR** (Snapdragon GSR 1.0) and **FSR / FSR-Fit** (AMD FidelityFX Super Resolution 1.0), plus **NIS** (NVIDIA Image Scaling, Vulkan), a **Sharpen** (RCAS) mode and Linear / Nearest, all switchable live in the in-game drawer. On Vulkan it engages when a game renders below display resolution; on OpenGL it renders the scene at a reduced internal resolution and reconstructs it back up. Every sharpness slider runs 0 (off) → 100 (max). Your chosen scaling mode is now **remembered per game** across relaunch.
- **Fullscreen aspect-ratio modes** — control how a game fills the screen, **per container and per game**: **Off** (windowed, letterboxed), **Fit** (fullscreen, aspect preserved), **Stretch** (fills, ignores aspect), **Fill** (fills with aspect kept, cropping the overflow — no bars, no distortion) and **Integer** (largest whole-number scale, pixel-perfect and centered). A five-button selector in the in-game drawer switches modes live without closing the drawer, on all three host renderers. Your choice is saved per game.
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
- Per-container control of Wine version, graphics driver, DXVK / VKD3D version, Box64 preset, drive mappings, Z-drive selector, and environment variables — the **Add Environment Variable** picker includes a large set of **presets** (DXVK / VKD3D / Wine / Mesa) with typed value editors, so common tuning vars are one tap away.
- **Desktop wallpaper picker** — set an image as a container's Wine desktop wallpaper from the container editor, and choose whether it applies to **just this container** or **globally** to all of them.
- **Compatibility Layers download menu** — a cloud button on each component (Wine/Proton, DXVK, VKD3D, Box64/WOWBox64, FEXCore) opens a downloader to browse, install or remove versions, with **Wine/Proton tabs**, an **"in use"** marker, **install-from-file**, and **byte-accurate download + install progress bars**.

### 🕹️ Games, shortcuts & input
- **Game library** with grid or list layout, sorting, and installed/updated filters.
- **Redesigned game cards** — primary chips (renderer · DXVK · frame-gen) over a muted driver · VKD3D · backend line, with the resolution in the subtitle; long component names no longer blank the game title.
- Add shortcuts from external storage.
- **Back up & restore game saves** as **GameHub-compatible zips**, with per-game save discovery and a confirm checklist before anything is overwritten.
- **SteamGridDB** cover-art scraping.
- Per-game settings including display language / locale.
- **Customizable on-screen touch controls** and virtual gamepad overlays, which **follow your app theme** or take a **per-game custom colour** you set in the Controls editor.
- **Physical controller** support (SDL2), plus touchpad / mouse emulation with adjustable cursor speed. The **external controller-binding screen** lists each input as a card with readable labels, and buttons you press while binding appear instantly.

### 🌐 Community Configs
Browse **community-shared, per-game / per-device tuning configs** in-app and apply a known-good setup in one tap.
- **Catalog browser** (globe button in the Games header) with search, Steam / Title filters, sort by upvotes / name / device count, and a **"Matches my device"** filter that narrows to configs shared from hardware like yours.
- **Per-config cards** showing **★ upvotes** and **↓ downloads** (best-rated first), the source device / SoC and the date, aggregated across every folder a game is known by.
- **One-tap Apply** that **surgically merges** just the config's settings — DXVK / VKD3D / Turnip driver / FEX preset / renderer / resolution / launch args / environment variables — into your shortcut, **preserving everything else you've set**. Applies to any shortcut, warning you if it doesn't match the game.
- **Smart install** of a config's missing **DXVK / VKD3D / FEXCore** build or **Turnip GPU driver**: an exact match installs with one confirm, otherwise pick from the closest versions (or browse all), and the config **auto-applies** afterward. FEX date builds match by their **YYMM** monthly tag; components you already have are recognised, not re-installed.
- **Config detail page** — provenance (source device / SoC / app / date), a plain-language list of **what the config sets** in Bannerlator's own component terms, and a **before-you-apply diff** against your shortcut, plus the config's **live description, upvotes, downloads and comments** — you can **upvote** and **comment** yourself.
- **Read-only for your setup** — nothing changes unless *you* tap Apply; your containers, imagefs and existing settings are never touched.
- **Share your own setups** — export a game's working settings and **upload** them for the community in a couple of taps. The export captures the full recipe (graphics translator + all its options, driver, renderer, resolution, launch args, env vars and the rest) plus your device / graphics chip — but **never your files, store logins, or device-specific driver tuning**. Sharing is **anonymous by default** and Bannerlator keeps its configs in **its own space**, separate from other apps' libraries. **My uploads** lets you edit a description inline or delete an upload any time.
- **Optional account (no email needed)** — you never *need* one, but a **username + password** account makes your uploads **follow you to a new device**, puts **your name and picture** on configs you share, and is recovered with a **one-time recovery key** instead of an email reset. Everything — browse, apply, share, manage, upvote, comment — works fully **anonymously** without it.
- 📖 **[Read the full plain-English guide →](docs/community-configs-guide.md)** for a friendly, non-technical walkthrough of browsing, applying, sharing, and the optional account/recovery-key system.

### 🛒 Built-in stores & cross-store Download Manager
Sign in to your existing storefronts and play from libraries **you already own** — Bannerlator does not sell, bundle or circumvent any game or DRM.
- **Steam** — sign in with **username / password or QR code**, browse your owned library, and **download + install** games through a built-in **depot engine** (built on **[JavaSteam](https://github.com/Longi94/JavaSteam)**). Includes a **4-tier download-speed** picker (Slow / Medium / Fast / Blazing), **session hardening** that recovers from Steam's ~1-hour connection-manager logoff so long installs finish, a **connection / login status pill**, and a depot-download **OOM fix**.
  - **DLC picker** — a **"Choose DLC"** sheet lets you opt out of owned DLC before downloading, with the download size updating live as you check and uncheck, plus an **"Includes DLC"** line and a **size breakdown** (footprint / download / catalog / free space) and **download ETA + speed** on the detail page. A **true-size install fix** fetches real depot-manifest sizes so fully-downloaded games are no longer wrongly flagged "incomplete."
  - **Optional Goldberg auto-patch** on a game's detail page — a **[Goldberg](https://mr_goldberg.gitlab.io/goldberg_emulator/) / gbe_fork** Steam-emulator patch for **offline / emulated** play, in **Regular / Experimental / ColdClient** tiers, installed automatically and cleanly reverted on switch-back. *(Modifies a game's shipped files — **use at your own risk**, for games you own.)*
- **Epic Games** — sign in, browse your library, and **download / install / launch** your titles (including Epic **free games**).
- **Amazon Games** — sign in, browse your library, and **download / install / launch** your titles.
- **[GOG](https://www.gog.com/)** — sign in and browse your owned library; **download and install** your **DRM-free** games with **cloud-save** sync and one-tap launch into a container.
- **⬇ Cross-store Download Manager** — one unified manager across **all four stores**: see every active download and your whole installed library in one place, with **live two-bar** download/install progress, **background downloads + notification-shade** support (a foreground service keeps them running when you leave the app), and **launch / verified uninstall** for any installed game. Install state, cover art and update-available status stay in sync across a game's detail page, its download card and the store list.
- > 🔒 These sign-ins are a **third-party login system, exactly like any other emulator/launcher** that logs into these stores — **use them at your own risk** (see [Security Hardening](#-security-hardening--your-store-accounts)).

### 🔒 Security Hardening & your store accounts
The Steam / Epic / GOG / Amazon sign-ins are a **third-party login system, exactly like any other emulator or launcher** that logs into these stores. **Bannerlator is not affiliated with, authorised by, or endorsed by Valve/Steam, Epic Games, GOG, or Amazon.**
- **Use at your own risk.** You are logging your **real store account** into a community app. That's a normal trade-off for this kind of tool — but it's your account and your call.
- **Your credentials are redacted from logs.** This release strips sensitive values out of everything the stores write, to **logcat *and* the shareable diagnostic files**, via a new `StoreLog.redactUrl` helper: **signed download / manifest URLs** (Amazon / Epic / GOG CDN links carry access tokens in the query), **OAuth authorization codes**, **GOG `client_secret` + `refresh_token`**, and **account identity IDs** (Epic account ID, GOG user ID). Steam credentials were already redacted. None of this changes how login, downloads or cloud saves work — only what gets written to a log.
- **Still be careful sharing logs.** Even with redaction, a log or debug file can contain other diagnostic detail — so only share one publicly if you're comfortable doing so.

### 🧰 Bundled Start-menu utilities
- New containers ship with handy Windows tools in the Start menu — **Winlator File Manager (WFM)**, **AIO Graphics Test**, and **Game Controller Test**.
- **`.lnk` working-directory ("Start in") support** so shortcuts for apps that only run from their own folder launch correctly.

### 🎛️ Interface & in-game overlay
- Modern **Jetpack Compose** user interface with a redesigned, icon-led navigation drawer.
- **Theme-aware everywhere** — your selected preset / accent recolours the **whole app *and* the in-game side drawer**, including dialogs, chips, sliders and overlays.
- **Customizable themes** — **16 presets** (AMOLED default, Classic Dark, Ocean, Forest, Sunset, Rose, Steel, plus Midnight Cobalt, Phosphor, Carbon & Ember, Amethyst, Crimson, Synthwave, Royal Gold, Frost and Monochrome) plus an **HSV custom-accent picker**.
- In-game overlay drawer for settings, input, and quick toggles, with a Task Manager that lists processes as cards and can launch new tasks on any renderer.
- **Built-in File Manager with Favorites** — bookmark folders and jump to them from a dedicated list, each labelled by storage source (Internal / SD card / a container's Drive C: or Z:) and full path. Image files show **real thumbnails**, and the File Manager doubles as the app's **file picker for every import** (WCP / ICP / wallpaper / drivers / assets) — reliable on OEM skins where Android's system picker fails, with the system picker still available as a secondary option.
- **Performance HUD** — FPS, frame time, CPU/GPU temperature, and RAM, in vertical or horizontal layout, with its on-screen **position saved per game**.
- **FEX runtime indicator** — a live badge in the Graphics tab shows what's actually translating the running game: **arm64ec** vs **x86-64**, the translator (**FEXCore / wowbox64 / Box64**), and — for FEXCore — whether the native **unixlib (`.so`)** or the classic **DLL** path is active. Read straight from the running process, so it reflects reality, not just the setting.

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
| **GameNative** | [GameNative](https://github.com/utkarshdalal/GameNative) by **utkarshdalal** — Proton bionic translation layers and cherry-picked commits adapted into this build. Its rendering pipeline was also the **reference used to fix and rewire Bannerlator's render options** — the `AHardwareBuffer` present path that makes Vulkan / DXVK / VKD3D content render correctly on both the OpenGL and Vulkan host renderers (GPUImage socket-buffer locking + EGLImage sampling, DRI3 direct-scanout, the Present extension's FLIP / COPY branches, and the Native Rendering+ direct-scanout path) was ported from and cross-examined against GameNative's implementation. The **standalone FPS limiter** is GameNative's too — its guest-side present-pacing mechanism (delaying the X11 Present `IdleNotify` to throttle the game itself, plus the rule that lsfg-vk's own pacing governs when its multiplier is ≥ 2) was ported from GameNative. For the **Steam store** (2.3), the **session-hardening patterns** (derived-`loggedIn` state, off-pump PICS sync, single reconnect funnel, dead-token clearing, keep-alive / watchdog) and the **`DownloadSpeedConfig` cores × ratio 4-tier download-speed model** were also ported / adapted from GameNative. |
| **xXJSONDeruloXx** | [bionic-fg](https://github.com/xXJSONDeruloXx/bionic-fg) — the Android/bionic Vulkan **frame-generation** layer powering Bannerlator's Frame Generation feature. Included in-tree as a git submodule with the author's permission. |
| **PancakeTAS** | [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) — the open-source Vulkan frame-generation layer (a Vulkan-layer reimplementation of Lossless Scaling's frame generation) that Bannerlator's **second, user-selectable FG engine** is built on. |
| **FrankBarretta** | [lsfg-vk-android](https://github.com/FrankBarretta/lsfg-vk-android) — the Android/bionic port of lsfg-vk (AHardwareBuffer path + `vkCmdPipelineBarrier2` shim) that runs as Bannerlator's lsfg-vk engine on the Turnip stack. The in-game live multiplier/flow-scale reload uses the `conf.toml` mtime-watch mechanism from **GameNative's** [lsfg-vk-android fork](https://github.com/GameNative). No proprietary shaders are bundled — users supply their own `Lossless.dll` ([Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) by THS) via the in-app picker. |
| **DadSchoorse** | [vkBasalt](https://github.com/DadSchoorse/vkBasalt) (zlib) — the Vulkan post-processing layer that embeds the ReShade FX compiler. Bannerlator's **ReShade** feature is a continuation of this work: the bundled layer is built from DadSchoorse's source, patched for live on-device toggle and slider control. The bundled / catalog `.fx` effects are MIT / CC0 shaders by the **ReShade ([crosire](https://github.com/crosire/reshade-shaders))**, **prod80 ([prod80-reshade-repository](https://github.com/prod80/prod80-reshade-repository))**, **luluco250 ([FXShaders](https://github.com/luluco250/FXShaders))** and **fubax** authors, each under their own MIT / CC0 license. |
| **leegao** (Lee Gao) | Vulkan texture-compression work used for mobile-GPU compatibility and performance — the [BCn decompression layer](https://github.com/leegao/bcn_layer) (**shader-v3**, powering 2.5's **"Wrapper + bcn_layer"** Mali driver) and real-time [ASTC/ETC compute-shader encoders](https://github.com/leegao), plus the [bionic-vulkan-wrapper](https://github.com/leegao/bionic-vulkan-wrapper) (**ETC2-Milestone-2**) bundled as the base wrapper for the Mali BCn path. |
| **JavaSteam** | [JavaSteam](https://github.com/Longi94/JavaSteam) (`in.dragonbra:javasteam`) by **Longi94** — the Steam **connection-manager client** the built-in Steam store logs in and talks to Steam with, and — via the **`javasteam-depotdownloader`** fork by **joshuatam** — the **entire depot-download engine** Bannerlator's Steam store is built on. |
| **Goldberg Steam Emu / gbe_fork** | [Goldberg Steam Emu](https://mr_goldberg.gitlab.io/goldberg_emulator/) by **Mr_Goldberg**, and **gbe_fork** by **[Detanup01](https://github.com/Detanup01/gbe_fork)** — the Steam emulator Bannerlator's **Goldberg auto-patch** installs (Regular / Experimental / ColdClient tiers) for offline / emulated play of games you own. |
| **Pluvia** | [Pluvia](https://github.com/oxters168/Pluvia) — an Android Steam client whose patterns were **referenced alongside GameNative** while building the Steam store's login / session handling. |
| **The412Banner** | Full Jetpack Compose UI migration, in-game overlay rewrite, controller-support restore (SDL2 SoName fix + four event files), Box64 edit-dialog fix, theme system, and CI/release infrastructure. **In 2.3**, building on JavaSteam / GameNative / Goldberg, the original engineering is Bannerlator's own: the **cross-store Download Manager**, the **four storefront integrations** (Steam / Epic / GOG / Amazon), the multi-week **Steam session-hardening** work, the depot **OOM fix**, the **Goldberg auto-patch** integration, the store **Material-3 restyle**, and the store-log **credential redaction** (`StoreLog.redactUrl`). **In 2.4**, the **fullscreen aspect-ratio pipeline** (Off/Fit/Stretch/Fill/Integer across all three renderers), the **in-app File-Manager import picker** replacing SAF (with image thumbnails + percent/ETA import progress), the **DLC picker**, the **true-size depot install fix**, **per-game persistence** of scaling / fullscreen / HUD position, and the **container wallpaper picker**. **In 2.5**, the **Mali / BCn hardening** — wiring leegao's bcn_layer (shader-v3) + ETC2-Milestone-2 wrapper into the **"Wrapper + bcn_layer"** and experimental **"Wrapper-gamenative"** drivers, the **BCn Layer Settings** UI, and the **in-game logging overhaul** (copy-logs button, selectable log location, co-located DXVK/VKD3D logs, scrollable debug-channels dialog). **In 2.5.1**, the **SurfaceFlinger colour + crash fix** and per-container / per-game **"Correct SurfaceFlinger colours"** toggle (ASurfaceRenderer R/B-swap fix + GPU converter, ported from GameNative #1620 / #1644), the in-game **FEX runtime indicator** (arm64ec / x86-64 · FEXCore / wowbox64 / Box64 · unixlib / DLL, read live from `/proc/<pid>/maps`), the **FEXCore unixlib (`.so`) auto-match** at launch (per-game version sync + uninstall cleanup), and **18 new Environment Variable presets** (DXVK / VKD3D / Wine / Mesa). **In 2.5.2**, the **Community Configs** system — the in-app catalog browser, per-uploaded-config cards with live upvotes / downloads / comments, the surgical config-apply engine, and the smart inline installer for DXVK / VKD3D / FEXCore + Turnip drivers — plus **two new Proton 11.0-1 x86-64 compatibility layers** (SDK 28 / SDK 35) built and published to the downloadable catalog. Also maintains the [Nightlies WCP Hub](https://github.com/The412Banner/Nightlies) and [Banners-Turnip](https://github.com/The412Banner/Banners-Turnip). |

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
| **Steam depot engine** | [JavaSteam](https://github.com/Longi94/JavaSteam) by [Longi94](https://github.com/Longi94) · depotdownloader fork [joshuatam](https://github.com/joshuatam) |
| **Steam emulator (Goldberg auto-patch)** | [Goldberg Steam Emu](https://mr_goldberg.gitlab.io/goldberg_emulator/) (Mr_Goldberg) · [gbe_fork](https://github.com/Detanup01/gbe_fork) (Detanup01) |
| **Frame Generation (bionic-fg)** | [xXJSONDeruloXx](https://github.com/xXJSONDeruloXx/bionic-fg) |
| **Frame Generation (lsfg-vk)** | [PancakeTAS](https://github.com/PancakeTAS/lsfg-vk) · Android port [FrankBarretta](https://github.com/FrankBarretta/lsfg-vk-android) · live-reload fork [GameNative](https://github.com/utkarshdalal/GameNative) · DLL [Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/) (user-supplied) |
| **Post-processing (ReShade / vkBasalt)** | [vkBasalt](https://github.com/DadSchoorse/vkBasalt) by [DadSchoorse](https://github.com/DadSchoorse) (zlib) · Winlator packaging [Pipetto-crypto](https://github.com/Pipetto-crypto/winlator) · effects by [crosire](https://github.com/crosire/reshade-shaders) · [prod80](https://github.com/prod80/prod80-reshade-repository) · [luluco250](https://github.com/luluco250/FXShaders) · fubax (MIT / CC0) |

### Community reports & requests

Much of Bannerlator's polish is driven by the people who file issues and test builds. Recent features came directly from:

- **Angel** — requested the environment-variable presets that seeded **2.5.1's** new preset set (`DXVK_DISABLE_TIMELINE_SEMAPHORES`, `VKD3D_SHADER_MODEL`).
- **[@kylinzang](https://github.com/kylinzang)** — the driving force behind **2.5's Mali / BCn support** ([#70](https://github.com/The412Banner/Bannerlator/issues/70), originally #54 / #53): the original request, the env-var spec, the in-game logging overhaul, and iterative on-device testing on Mali-G57 through a full sign-off. Also fullscreen aspect-ratio modes ([#71](https://github.com/The412Banner/Bannerlator/issues/71)) and the in-app File-Manager import picker ([#73](https://github.com/The412Banner/Bannerlator/issues/73)).
- **[@rizky2-crypto](https://github.com/rizky2-crypto)** — Mali-G610 BCn testing ([#30](https://github.com/The412Banner/Bannerlator/issues/30)).
- **[@SombraShadow](https://github.com/SombraShadow)** — the container wallpaper picker ([#66](https://github.com/The412Banner/Bannerlator/issues/66)).
- **[@abdogm](https://github.com/abdogm)** — magnifier cursor-follow & no-dim fixes ([#44](https://github.com/The412Banner/Bannerlator/issues/44)).
- **[@Devaspe](https://github.com/Devaspe)** — the Steam install-blocker report that drove the true-size depot install fix.

…and everyone in the Discord and on GitHub who tests builds and reports issues. 🙏

Additional credits surfaced in the **Star Bionic REVAMPED** project (`star.bionic-revamp`):

- **@The412Banner** — Converting the UI to Jetpack Compose and rewriting the controller implementation.
- **@jacojayy** — Timeline Semaphore patches in Turnip.

> If you have contributed and are not listed, open a PR — this list is intended to be complete.

---

## ⚖️ Disclaimer

Winlator and its forks are unofficial community projects. They are **not** affiliated with or endorsed by Microsoft, Wine, the Mesa project, Qualcomm, **Valve/Steam, Epic Games, GOG, Amazon**, or any game publisher. The built-in store sign-ins are a third-party login system for libraries **you already own** — see [Security Hardening & your store accounts](#-security-hardening--your-store-accounts), and **use them at your own risk**. Compatibility varies by device GPU, Android version, and individual game.

---

## 📄 License

**Bannerlator is licensed under [GPL-3.0](LICENSE) as a whole**, because it incorporates GPL-3.0-licensed components (notably **GameNative** and **lsfg-vk**), whose copyleft governs the combined distribution.

The upstream **Winlator → cmod → Bionic → Star → Ludashi** lineage it builds on is **MIT © 2023 BrunoSX** (permissive, and GPL-3.0-compatible). That MIT notice — and the license and copyright of *every* incorporated component (GameNative, lsfg-vk, gbe_fork/Goldberg, vkBasalt, bcn_layer, JavaSteam, bionic-fg, Wine/Box64/Mesa/DXVK, FSR/NIS/SGSR, ReShade shaders, …) — is preserved in **[`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md)**.

See [`LICENSE`](LICENSE) for the full GPL-3.0 text and [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md) for all third-party attributions.
