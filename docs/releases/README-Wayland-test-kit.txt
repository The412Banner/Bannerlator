Bannerlator Wayland test kit  (2026-09-14, phase 5 = pre-release 7)
====================================================================

1. Bannerlator-3.1.2-wayland-pre7-<flavour>.apk   (all three flavours on the GitHub pre-release)
   versionCode 85 like 3.1.1, so you can go back to 3.1.1 or forward to the next stable.
   New since pre-release 6 (use the v8 layer below):
   - OpenGL safe mode: on by default for OpenGL games on Wayland (drawer switch, next launch).
     Stops OpenGL games vanishing a few seconds in (Mesa's threaded context crash).
   - Every session log starts with what the screen can do for HDR; Task Manager shows it too.
   - A window over a game no longer costs hardware composition for the rest of the session on a
     screen that rotates and scales the game (handhelds like the Pocket FIT).
   - The HUD names what really renders on Wayland: an OpenGL game says "OpenGL", not "DXVK".
     Cards read "Vulkan (Wayland)", and X11 cards now read "Vulkan (X11)" / "OpenGL (X11)".
   - Creating a container now keeps everything the create screen showed: Wayland, the Wayland
     game driver, drivers, DXVK/VKD3D versions, and env vars you deleted stay deleted.
   - A Wayland container never starts with a blank "Compositor driver": it picks a Turnip that
     works (your New Container Defaults driver first); with none installed it offers a download.
   - No more Wine Mono download prompt on a new container or after a layer update (all layers).
   New since pre-release 5 (NEEDS the v7 layer below):
   - Games that use OpenGL directly now RENDER on Wayland. They were always a black window with
     sound. Two faults: the compositor described its buffer sharing with an older protocol version
     than Mesa's OpenGL needs to find the GPU, and our driver build then steered OpenGL into a
     software renderer this layer does not contain. Both fixed; OpenGL now runs on the GPU via Zink.
   - DirectX and Vulkan games are unaffected (different path), re-measured on the v7 layer.
   New since pre-release 4 (same v6 layer, app only):
   - Screen effects KEEP the game on its own display layer. A Look, a scaling mode or a filter used
     to drop the whole session back to the compositor's copy path; the chain now draws its result
     into the game's layer instead, so the display hardware still puts it on screen.
   - A window above a fullscreen game (a launcher, Wine's Task Manager) gets its OWN layer, and the
     game keeps presenting its frames copy-free underneath. Two layers is a hard cap on purpose.
   - Input is unchanged: display layers carry no input, so touch and mouse still reach the game and
     the drawer still draws above everything.
   - Refresh-rate matching now works on Wayland: with a 60 cap the panel runs at 60 (zero-copy too),
     clearing the cap returns it to the panel maximum, a manual lock is honoured, frame generation
     still asks for cap x multiplier. Turn it on under "Match refresh rate" in the container.
   New since pre-release 3 (needs the v6 layer below):
   - The zero-copy toggle SWITCHES LIVE. Flip it in the drawer while the game runs and the game
     moves onto its own Android display layer (no copy between the game and the screen), flip it
     back and it returns to the normal path. No relaunch, no black frame, no fps change.
     The row shows what is really happening ("On: N zero-copy frames in the last 10 s" / "Off").
   - Effects, scaling and frame generation still pause zero-copy while they are on, live.
   - A new app on the old v5 layer behaves exactly as before; only app + v6 layer gives the switch.
   New since pre-release 2 (phase 3b, the in-game drawer):
   - Task Manager > Container shows "Display backend: X11/Wayland"; on Wayland the Renderer row reads
     "Vulkan (Wayland compositor)" and Graphics driver names both drivers (compositor + game).
   - Graphics tab: Native Rendering is greyed on Wayland (it is X11's direct scanout); a "Zero-copy
     presentation" toggle sits under it (saves to the shortcut/container, applies on the next launch,
     shows the live zero-copy frame count).
   - Screen effects WORK on Wayland now, live from the drawer like X11: Scaling modes (Linear,
     Nearest, SGSR, SGSR HQ, FSR, FSR Fit, Sharpen, NIS), CAS, HDR, Debanding, Looks, brightness/
     contrast/gamma/saturation, FXAA, CRT, Toon, NTSC (13-pass Vulkan chain in the compositor).
   - Frame generation WORKS on Wayland: LSFG Native and Win-FG Native, armed from the drawer as on
     X11 (the launch never auto-arms). Proven: Half-Life 2 at 30 fps shown
     at 60 / 90 / 120 fps with LSFG Native 2x / 3x / 4x.
   - Effects or frame generation on => the session uses the copy path (zero-copy pauses, the log says so).
   - HUD fps no longer reads 0.0 when zero-copy frames bypass the compositor (tester report).
   New since pre-release 1:
   - Eight Wayland game drivers built into the Proton (see 2). Auto picks by GPU; the a8xx
     alternatives are for Adreno 830/840 owners to compare.
   - Zero-copy presentation (experimental, off by default): put BANNER_WAYLAND_ZERO_COPY=1 in the
     container's or shortcut's Env Vars and a fullscreen game's own frames go straight to the display
     hardware, no compositor copy (session log shows "N zero-copy frames"). Proven with Half-Life 2.
   - Compressed (UBWC) game buffers are accepted by the compositor (default on;
     BANNER_WAYLAND_UBWC=0 forces linear if you see a scrambled picture on your GPU).
   - Fixes: the first launch after installing a Wayland Proton no longer comes up at 1024x768;
     Wine's desktop no longer closes a second into a game's startup (32-bit games under FEX).
   - Keyboard layout names now come from xkb data bundled in the Proton (no longer forced to "us").

2. proton-11.0-2.1-arm64ec-wayland-v8.wcp   (installs as Proton-11.0-2.1-arm64ec-8)
   New in v8: the XP Start menu's "Control Panel" opens Wine's Control Panel again (it did nothing
   on v7), with the proper icon; Add/Remove Programs is reachable from it. Containers on -7 show an
   "Update layer" button - it backs up the registry first and can be reverted.
   Proton 11.0-2 + winewayland + EIGHT Wayland Turnips chosen under "Wayland game driver":
     Bundled                    upstream Mesa 7cda7850, no patches        Adreno 6xx, 730, 740, 750
     Bundled a7xx               Vauzi-17 "710" v3.6 recipe                  Adreno 710, 720, 722
     Bundled a8xx               WinNative WN-Turnip 1.15 Balanced (Auto on 8xx)   Adreno 830/840
     Bundled a8xx Performance   WinNative WN-Turnip 1.15 Performance (PWR_MAX)
     Bundled a8xx gen8          Banners-Turnip gen8 recipe (own Android a8xx job)
     Bundled a8xx SMXZ          StevenMXZ Turnip Gen8 V36 recipe
     Bundled a8xx WHITE         whitebelyash Mainline Turnip v31 recipe
     Bundled a8xx upstream      pure Mesa main @ bbc7792f (2026-09-13), no patches
   Also inside: the zero-copy swapchain patch in every driver, xkeyboard-config data, and the
   Wine fix restoring the 1 s desktop-close grace, and the drivers that follow the live zero-copy
   switch, and the EGL fix that lets OpenGL games render. Remove older -1 … -6 entries (keep -7
   until your containers are updated to -8).
   All eight load and render on an Adreno 750; none has been run on real 710/720/722 or 830/840 yet.

3. No Turnip zip needed: the compositor uses whatever Android Turnip you pick under
   "Compositor driver" (any recent one from the in-app catalog). Never pick "System".

Setup
-----
1. Install the APK for your flavour over 3.1.1 or an earlier pre-release.
2. Contents > Proton > Install from file: the v8 wcp.
3. Container: Proton = Proton-11.0-2.1-arm64ec-8, Display backend = Wayland, Compositor driver =
   an Android Turnip, Wayland game driver = Auto (8xx owners: try the alternatives one by one),
   FEXCore = an installed version. DXVK / VKD3D / components / audio as on X11.
4. Optional experiments via Env Vars: BANNER_WAYLAND_ZERO_COPY=1 (fullscreen games only),
   BANNER_WAYLAND_UBWC=0 (if the picture is scrambled), TU_DEBUG=sysmem (Vauzi's tip for 710/720/722).

Verified on this build (AYANEO Pocket FIT, Adreno 750)
-----------------------------------------------------
  All eight game drivers load their own manifest and render the AIO Graphics Test on Wayland.
  Switch sweep Vulkan -> D3D12 -> D3D9 alive in one launch.
  Half-Life 2: desktop survives the launch; touch mouse-look under a pointer lock; 144 fps.
  Zero-copy on: 1239 of 1240 presented frames without a copy, picture correct, same 144 fps cap.
  First launch with a stale prefix: 1280x720, no resize.
  Notepad: paste from Android, type, copy back; soft keyboard auto-opens.
  Drawer on Wayland: backend row, zero-copy toggle, Look "Retro CRT" applied live over Half-Life 2.
  LSFG Native from the drawer on Half-Life 2 (30 fps game): 60.0 / 90.0 / 120.0 fps shown at 2x / 3x / 4x.
  Live zero-copy switch on Half-Life 2: off -> on -> off -> on mid-game, every presented frame
  zero-copy while on, ~1435 game frames per 10 s throughout, picture never black.
  Retro CRT over Half-Life 2 with the game still on its display layer: ~123 fps vs 122-126 on the
  old copy path, hardware composition kept. Wine Task Manager over a windowed game: two layers,
  game frames still copy-free underneath.
  Refresh rate: 60 cap -> panel 60 (with zero-copy on), cap off -> 144, manual 90 -> 90,
  LSFG 2x on a 30 cap -> 60.
  OpenGL on v7: a native GL game presents GPU frames through Wayland with the HUD armed and its
  own picture on screen; Wine reports the GL device as "zink Vulkan 1.4 (Turnip Adreno 750)".
  On v7, Half-Life 2 zero-copy 132-143 fps (v6: 124-142), effects chain 13 passes at 144 fps,
  Notepad typing + clipboard both ways still fine.

Known gaps
----------
- Measured on the Pocket FIT, Half-Life 2 uncapped, 2x60 s each: zero-copy 185 fps vs 187 fps copy path
  (no change, CPU-bound), GPU busy 79% vs 83%, GPU clock 944 vs 1000 MHz, power 16.3 W vs 16.8 W.
  Zero-copy removes the compositor's GPU work; fps gains need a GPU-bound game or a big panel.
- Real Adreno 710/720/722 and 830/840 hardware untested: please report which a8xx build works best.
- OpenGL is newly working, not broadly tested. Reports from OpenGL games are the most useful thing
  to send right now.
- If an OpenGL game vanishes with no error, no log and no crash dialog, put GALLIUM_THREAD=0 in its
  Env Vars. Mesa's threaded-driver helper can fault on this build, and Wine's crash handling means
  the process just disappears. The game used to find this went from dying after two frames to
  playing its full intro at a steady 30 fps.
- Drag-and-drop, image clipboard and window decorations are not on Wayland yet.
- With a window above the game, THIS panel hands the frame back to the GPU instead of composing two
  layers itself (the game still keeps its copy-free frames). Other panels may differ - please report.
- Frame generation still needs the compositor pass, so it pauses zero-copy.
- The live switch is proven on the Adreno 750 only. On a GPU where the compositor cannot import the
  game's buffers, switching off keeps the old frames on the layer until the game rebuilds.

Logs: Download/Wayland-logs/wayland-*.log (compositor) and Download/bannerlator/<game>/wine_debug.log.
