<p align="center">
<img src="https://github.com/The412Banner/Bannerlator/blob/main/logo.jpg?raw=true" width="1500" height="500" alt="Bannerlator" />
</p>

<p align="center">
<img src="https://img.shields.io/badge/release-3.1.6--pre3-ff3d8b?style=for-the-badge" alt="3.1.6-pre3" />
&nbsp;<img src="https://img.shields.io/badge/update-app--side%20%C2%B7%20no%20ImageFS%20reinstall-2dd4bf?style=for-the-badge" alt="app-side update" />
&nbsp;<img src="https://img.shields.io/badge/CPU-Force%20SSBS-22c55e?style=for-the-badge" alt="Force SSBS" />
&nbsp;<img src="https://img.shields.io/badge/layers-v14-22c55e?style=for-the-badge" alt="layers v14" />
&nbsp;<img src="https://img.shields.io/badge/Mali-PanVK%20%C2%B7%20Wayland-22c55e?style=for-the-badge" alt="Mali PanVK on Wayland" />
&nbsp;<img src="https://img.shields.io/badge/pre--release-beta-f59e0b?style=for-the-badge" alt="pre-release" />
</p>

# Bannerlator 3.1.6-pre3

Run Windows apps and games on Android — no PC and no root required.

**3.1.6-pre3 adds a CPU speed fix for Wine on ARM, new v14 layers that carry it, and Wayland settings for Mali phones.** Wine on ARM kept switching off a CPU feature called **SSBS** on game threads, which makes the processor handle memory the slower, extra-safe way. The **v14** layers carry Wine's own fix, and the new **Force SSBS** toggle — **on by default** — controls it. Mali phones get a **Mali (PanVK)** section in the Wayland driver settings and can now pick **System** as the Wayland driver. Handhelds without a Back key can pick a **Menu button**. Everything from 3.1.6-pre2 and pre1 is included.

- **Force SSBS:** on by default, per container and per game (Advanced tab). The biggest gains are expected on Snapdragon 8 Elite (Oryon) phones in CPU-heavy games.
- **v14 layers:** all ten Proton layers — including both Proton 10 layers again — in the catalog now.
- **Mali on Wayland:** PanVK settings in the driver gear, and "System" offered as the driver (experimental).
- **Menu button:** pick a controller button that opens the in-game side menu (Input Controls › Device).

<!-- update-summary: Bannerlator 3.1.6-pre3: Force SSBS CPU speed fix (on by default) with the new v14 layers (Wine MR 12262 backport, all ten layers incl. Proton 10); Mali (PanVK) Wayland settings + System driver option on Mali (experimental); Menu button for handhelds; Toy Story 2 window fix; plus everything from 3.1.6-pre2 and pre1. -->

> ✅ **Nothing to set up.** Update the app, then take the **v14** layer updates from the catalog (containers offer **Update layer → v14**). Force SSBS is already on; turn it off per game if one misbehaves.

> 📘 **Guides:** [OpenGL games now run 4 to 5 times faster](https://the412banner.github.io/Bannerlator/fast-opengl/) · [LSFG Native Made Simple](https://the412banner.github.io/Bannerlator/lsfg-native-guide.html)

# What's New — everything since 3.1.5

## 🆕 New in pre3

## ⚡ Force SSBS — a CPU speed fix for Wine on ARM

ARM processors have a feature called **SSBS**. With it on, the CPU may run memory reads ahead of earlier writes — its normal fast path. With it off, it takes a slower, extra-safe route. Every time Wine on ARM handled certain internal events (exceptions, thread suspends, its own wake-up signal) it rebuilt the thread's CPU state from a Windows structure that knows nothing about SSBS, and switched it off. Game threads ended up running the slow way almost all the time.

- **The fix lives in the v14 layers:** a backport of Wine's own fix ([MR 12262](https://gitlab.winehq.org/wine/wine/-/merge_requests/12262), Jacek Caban). Wine now keeps the SSBS bit exactly as Android set it instead of clearing it — it does not force anything.
- **Force SSBS toggle** — container settings and game shortcut settings, **Advanced** tab, next to the FEXCore settings. **On by default.** A game can say *Container default*, *On* or *Off*. Off gives Wine's old behaviour back exactly.
- **Older or third-party Protons** (Proton 9, StevenMXZ builds, layers before v14) don't carry the fix; with the toggle on the app loads a small add-on (`libssbs.so`) that switches SSBS back on after Wine clears it.
- **How much faster:** it depends on the chip and the game. The report that started this was *Fallout: New Vegas* going from about 100 to 500 fps on a Snapdragon 8 Elite (Oryon). On a Snapdragon 8 Gen 3 the difference in our tests was within noise.

> ✅ **Device-tested** on the Pocket FIT with *DiRT Showdown* on the v14 Proton 11.0-2: with the toggle on, Wine left SSBS clear **0** times across all 14 Wine processes; with it off, the old clearing came straight back. No speed measurement on an 8 Elite yet.

## 📦 New compatibility layers — versionCode 14

All **ten** layers — Proton 11.0-2, 11.0-1, GE-Proton 11.0-3 / 5 / 6 / 7 / 7.1, Proton-CachyOS 20260703 / 20261005 and, back in the set, **Proton 10.0-4 and GE-Proton 10.0-34** — are rebuilt at **versionCode 14** with the SSBS fix and its `WINE_FORCE_SSBS` switch. The five x86_64 flavours are in the same release but **unchanged** apart from the version number (an x86_64 Wine runs through box64 and never uses this ARM code). They install into a new coexisting `-14` slot; a container on an older build of the same layer is offered **Update layer → v14** with a revert snapshot. Release notes: [bionic layers v14](https://github.com/The412Banner/proton-wine/releases/tag/build-bionic-layers-20261007-v14).

## 🟣 Mali phones: PanVK settings for Wayland, and "System" as a driver

On an Arm **Mali** GPU only, the Wayland driver settings (container, game shortcut and the XMB menu) gain a **Mali (PanVK)** section for FristOneRR's PanVK driver:

- **Mali (PanVK) mode** — on by default when the driver's name contains "panvk". Turns on three adapter switches, each with its own toggle under **Advanced**: hide the extensions PanVK can't do, skip submit waits, skip the acquire signal (the two skips are for DXVK games).
- **Advanced PanVK tuning:** heap sizes (`PANVK_HEAP_MB`, `PANVK_TILER_HEAP_MB`, `PANVK_POLY_HEAP_MB`), atom stride and trace — sent only when set and in range.
- **"System" as the Wayland driver (experimental)** — no bundled Turnip runs on Mali, so the Wayland driver list now offers **System** (your phone's own Mali Vulkan driver) on Mali. With the new adapter switches some vendor drivers may be able to show the game; if it stays black, pick a PanVK driver or use X11. Picked on purpose, it is kept: the app no longer swaps in a bundled Turnip for it at launch.

Nothing changes on Adreno or any other GPU.

## 🎛️ Menu button for handhelds without a Back key

**Input Controls › Device › Menu button** — pick a controller button that opens the in-game side menu. **Set button** takes the next key press, **None** turns it off. **Off by default on every device**; **B can't be picked**, so B never opens the menu. A controller-profile binding for the same button still wins, and in a Linux Steam session Guide stays the Steam button.

## 🛠️ Fixes in pre3

- **Toy Story 2 (and anything that maximizes a 0×0 window):** the app's window-helper (`winhandler.exe`) un-maximized windows when bringing them to the front, which left Toy Story 2's *Screen Mode Select* window invisible (black screen on X11). It now brings windows forward without restoring a maximized one; existing containers get the fixed helper on their next launch.

## 🧩 From 3.1.6-pre2

## 🎮 SteamLite: Steam Input

SteamLite runs Valve's Windows Steam client inside the container, and now it can hand your controller to the game the way Steam on PC does — through **Steam Input**, Valve's controller layer, with a layout applied.

- **Where:** the SteamLite launch options of a Steam game have a **Steam Input** toggle and a layout pick. Off by default.
- **When to use it:** games that only read controllers through Steam Input, or when you want Steam's remapping. **Keep it off for games that already read a controller themselves** — with it on, such a game can see two pads at once.
- **Default layout:** a plain gamepad layout, so the pad stays a pad. The *gamepad + mouse* layout (right stick moves the mouse, buttons press keys) is there for mouse-only games.
- **Needs SteamLite v8** — the package now carries Valve's `SDL3.dll` and four controller layouts next to the client, and the agent switches Steam Input on for the game before it starts. Without the toggle the agent behaves exactly like v7, so older app builds are unaffected by the update.

> ✅ **Device-tested** on the Pocket FIT with *Left 4 Dead 2* online through SteamLite: the layout is applied, Steam confirms it, a full match played and exited cleanly.

## 🪟 Wayland: xdg-shell version 3

Every window on Wayland is created through the **xdg-shell** protocol. Bannerlator's compositor offered version 1 and only pretended to support pop-ups and their positioning; the Wayland driver in the newest Wine (the CachyOS 11.0-20261005 layer) refuses anything below version 3, so it could not create a single window — the desktop died and the game reported *exited before rendering*.

- The compositor now implements **positioners** (size, anchor, gravity, offset, the version-3 extras), real **pop-up windows** (placed against their parent, kept on screen, drawn above it, touchable) and **`reposition`**, and advertises version 3.
- Layers that ask for version 1 or 2 still get exactly that, so every existing layer behaves as before.

> ✅ **Device-tested** on the Pocket FIT: *Insane 2* on Wayland with the new CachyOS layer (≈1,400 fps on the menu, controller working) and, as a regression check, on the v13 Proton 11.0-2 layer.

## 📦 New compatibility layer — Proton-CachyOS 11.0-20261005

A new, separate **Proton-CachyOS** layer next to the 20260703 one (both stay): the Wine of CachyOS's release `cachyos-11.0-20261005-slr`, which is now **rebased on Proton-EM**, with our full Android stack ported onto it. **arm64ec and x86_64**, versionCode 13, in the in-app catalog now. Release notes: [bionic layers v13](https://github.com/The412Banner/proton-wine/releases/tag/build-bionic-layers-20261004-v13).

- **Wayland needs this build** (3.1.6-pre2 or newer) — on older app builds use X11 with this layer.
- **Controller fix** — the new upstream base made XInput skip the pads Bannerlator's *Exclusive XInput* hides from DirectInput, so the controller vanished entirely; that change is reverted in the layer.
- **x86_64 start-up fix** — the new base looks for the 64-bit Wine launcher in a folder the Android layers don't have, so nothing could start (*failed to start wineboot*); the layer keeps the working location.

> ✅ **Device-tested** on the Pocket FIT: arm64ec on X11 and Wayland, x86_64 (box64) on X11 — *Insane 2* with the controller each time.

## 🧩 From 3.1.6-pre1

## 🎮 Headless Steam: the app's own Steam session hosts the game

SteamLite runs Valve's Windows Steam client inside the container — the real thing, VAC and all, at the cost of a Steam client under translation. **Headless Steam** takes the other road: the app already holds a signed-in Steam session for your library, so a small native host (Valve's own Android Steam library, driven by the app) signs in on the account, and the game talks to it through the Steam bridge the Proton 11 layers have carried since v9. No Steam window, nothing to install in the prefix, and every Steam call the game makes runs natively instead of through translation.

- **Launch popup:** Steam shortcuts offer **Headless Steam** next to SteamLite, Goldberg and Raw; the choice is remembered per game like the others.
- **What works, device-proven:** sign-in in about a second, the game attached to the session (Steam sees you in-game), cloud saves, stats and achievements, multiplayer servers — Counter-Strike: Source was the test case, including a live match on a community server.
- **What it is not:** a VAC session. Valve's Android library contains only VAC *status* plumbing, no scanner — that only exists in the Windows and x86 Linux clients. Secure servers may still let you in, but you are not being VAC-scanned; for that, keep using SteamLite.
- **Relaunches:** a quick relaunch no longer trips over the previous session's lingering port; when the port really is busy (another app's Steam client — DroidDeck's Linux Steam uses the same one), the launch now stops with a clear card instead of starting the game without Steam.
- **Log Manager:** a **Headless Steam** toggle (same place and ? button as SteamLite) writes a per-game `headless_steam.txt` with a PASS/FAIL checklist — sign-in, bridge, session, server browser — plus the host, bridge and engine logs, redacted like the SteamLite one.

## 🔎 Find Servers works — the server browser

Valve's Android Steam library has no working in-game server browser: the moment a Source game asks for the server list it trips over an object that was never created and hangs. Inside our host it accepts the request and returns nothing. So the **v13 layers** carry their own:

- the game asks the bridge; the bridge asks the **app**, which fetches the master list through Steam's Web API with a token from your session (five thousand servers in about a second);
- the bridge **pings every server itself** (the same query protocol the PC client uses) and feeds the game its callbacks on the game's own thread;
- a small **x86-64 front** inside the layer answers the game's hottest lookups without leaving the emulator — the Source server browser re-reads server details tens of thousands of times a frame while sorting, and each read used to be a crossing into native code. With the front, the list refreshes at **316 to 376 fps**; before it, 0.

> ✅ **Tested** on the Pocket FIT with Counter-Strike: Source on Proton 11.0-2, 11.0-1 and GE 11.0-3 (v13): full lists, the three filter boxes, latency / location / anti-cheat filters, and joining a game.

## 📦 New compatibility layers — versionCode 13

All eight **Proton 11** layers (Proton 11.0-2, 11.0-1, GE-Proton 11.0-3 / 5 / 6 / 7 / 7.1, Proton-CachyOS) are rebuilt at **versionCode 13** with the server browser and its x86-64 front; the four x86_64 flavours are in the same release so the set stays together, but **they have no Steam bridge** (Headless Steam and the server browser do not apply to them) **and are unchanged** apart from the version number. The two Proton 10 layers stay at v12 — no Steam bridge there either. They install into a new coexisting `-13` slot; a container on an older build of the same layer is offered **Update layer → v13** with a revert snapshot. Release notes: [bionic layers v13](https://github.com/The412Banner/proton-wine/releases/tag/build-bionic-layers-20261004-v13).

## 🧬 Box64EC — an experimental second x86-64 translator (unofficial)

arm64ec containers gain an **Emulator (64-bit)** picker (container settings, and per game under Advanced → Box64EC) that switches the x86-64 translator between **FEXCore** (unchanged default) and **Box64EC**. The old picker is now labelled **Emulator (32-bit)**.

> ⚠️ **Box64EC components are experimental and unofficial.** They are built by The412Banner from the [box64](https://github.com/ptitSeb/box64) project's work-in-progress ARM64EC target and [airidosas252's pull request #4480](https://github.com/ptitSeb/box64/pull/4480), with our own hookup of the fast engine; they are **not** releases of the box64 project. Nothing installs by itself: the picker only appears once you install a Box64EC component from the catalog. Expect games that run slower or not at all; FEXCore remains the supported path.

## 📦 A fourth APK: AnTuTu

Every release now ships four builds of the same app under different package names — **standard** (`com.winlator.banner`), **PuBG** (`com.tencent.ig`), **Ludashi** (`com.ludashi.benchmark`) and, new, **AnTuTu** (`com.antutu.ABenchMark`). Take standard unless your device gives a specific package a performance mode; the in-app updater follows whichever one you installed.

## 🛠️ Fixes (pre1 / pre2)

- A malformed container folder (for example a leftover `xuser-` directory) crashed the app on every launch; it is skipped with a log line instead.
- Wayland HUD: a Vulkan game is no longer labelled "OpenGL"; the adapter's `icd.json` is written without escaped slashes.
- Headless Steam host: a `dlopen` failure path crashed instead of reporting the error.

## ⚠️ Carried over — still open

- **Force SSBS:** gain not yet measured on a Snapdragon 8 Elite (Oryon), where the biggest difference is expected. The v14 fix was ported by hand to each layer's Wine; device-tested on Proton 11.0-2 only.
- **Mali "System" driver on Wayland:** experimental and not yet tested on a Mali phone — it may show a black screen.
- **Menu button:** the Pocket FIT's Guide button was proven as a menu button during development; the final build has not been re-tested on a handheld.
- **SteamLite Steam Input:** tested on Left 4 Dead 2; games that *only* speak Steam Input have not been run through it yet.
- **xdg-shell v3:** pop-ups take no input grab and never take keyboard focus; the "resize to fit" placement rule is not implemented (pop-ups slide or flip back on screen instead).
- **Headless Steam:** not VAC-protected (by construction); Proton 10 layers have no Steam bridge; voice chat untested; only Counter-Strike: Source has been run through a full online session so far.
- **Server browser:** LAN tab scans the common Source ports by broadcast only; the Friends tab is empty on this path.
- **Box64EC:** experimental — no performance work yet beyond the fast-engine hookup.
- Everything listed as open in 3.1.5 is unchanged: Cutscenes / Codecs (FFmpeg arm64ec only), X11 cursor items, save-sync device checks, Fast OpenGL's 32-bit-only setups, the Wayland items, and the Linux Steam client items from 3.1.3.

<details>
<summary><b>📦 Where to get Proton 9</b> (tap to expand)</summary>

The APK does not bundle Proton 9 — Wine installs from the in-app catalog on first run. The Linux Steam client does not use these layers at all: its games run through Valve's own ARM64 Proton.

**Proton 9.0 arm64ec** — not bundled, download it if you want it:

**https://github.com/The412Banner/Nightlies/releases/download/Proton/wine/proton-9.0-arm64ec.wcp**

66.7 MB · sha256 `f8a99fed387f1b097009129dc49368c8f500927640a9d4c7a192d850b1b2068c`
Install it via **Containers → download icon → install the `.wcp`**. It also appears in the in-app catalog. *(Compiled as a wcp by **Xnick417x**.)*

</details>

---

<details>
<summary><b>🙏 Credits</b> (tap to expand)</summary>

**The SSBS fix** in the v14 layers is a backport of Wine [MR 12262](https://gitlab.winehq.org/wine/wine/-/merge_requests/12262) by **Jacek Caban** (Wine-Bug 60405); the problem was found by **Sonicadvance1** (FEX-Emu), whose `force_ssbs.diff` the earlier test builds used. **The Mali (PanVK) section** drives **FristOneRR**'s PanVK driver and Wrapper v1 changes (MIT) in the Wayland adapter. **winhandler.exe** is from Winlator by **brunodev85**; the pre3 fix is a two-byte patch of it.

**The cutscene fixes** in the v12 layers: the Media Foundation series by **NelloKudo**; the media-converter gate by **GloriousEggroll**; the original shared-texture driver (`sharedgpures.sys`) by **Valve** (Derek Lesho), rewritten here for Wine 11; the bionic prefix packs and build sysroot by **GameNative**; `winedmo` from **Wine** (Rémi Bernon), with Proton's PCM filter (© 2025 Conor McCarthy for CodeWeavers) replaced by our own. Full per-layer credits are in the [v12 layer release](https://github.com/The412Banner/proton-wine/releases/tag/build-bionic-layers-20261003-v12).

**The Codecs setting** is modelled on **Pipetto-crypto**'s winedmo backport for Proton 9 (winlator `a81fae78`): the pseudo-components in the Win Components list and the `WINE_USE_DMO` name. No code was copied; the Compose UI and plumbing are new.

**Proton-CachyOS 11.0-20261005** is the **CachyOS** team's (wine-cachyos, rebased on **Proton-EM**) on **Valve**'s Proton; our Android stack is ported on top. **SteamLite Steam Input** drives Valve's own controller layer; `SDL3.dll` and the four controller layouts in the package are **Valve**'s, unmodified. The xdg-shell v3 compositor work is ours, following the **wayland-protocols** xdg-shell specification.

**box64** is **ptitSeb**'s; the GStreamer-wrapper fix is ours and submitted upstream as [#4528](https://github.com/ptitSeb/box64/pull/4528). **FEXCore** is the FEX-Emu team's; the 2609-unix package is the catalog build with its unixlib pair.

**XInput 2 raw mouse** follows what **GameNative** and **WinNative** already do in their X servers, extended with touch and stylus raw motion. **Colour cursors** came from a Discord #bugs report of black-and-white cursors in Kingdom Come and the Gothic Remake — thank you to the reporter.

Everything credited in 3.1.4 still applies: Fast OpenGL (Mesa, Banners-Turnip, Wine's EGL route), the Wayland adapter (Pipetto-crypto's bionic Vulkan wrapper), winhandler.exe (Winlator by **brunodev85**), the Turnip fix (Mesa / Turnip by the Freedreno and Mesa teams), the Linux Steam client (Valve, DroidDeck, WinNative, Armada, Termux proot, Arch Linux ARM, FEX-Emu), ntsync (**Joshua Tam / joshuatam**, GameNative), the bundled Wayland drivers (Vauzi-17, the WinNative development team, StevenMXZ, whitebelyash), DXVK, VKD3D-Proton, Wine, Proton / GE-Proton, box64, SteamKit2 and Wayland.

</details>

---

*Entirely app-side — no ImageFS reinstall. Install over 3.1.6-pre2 or any earlier 3.1.x; everything carries over. Then take the v14 layer updates from the catalog.*
