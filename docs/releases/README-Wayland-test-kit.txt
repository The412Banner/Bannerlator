Bannerlator Wayland test kit  (2026-09-16, pre-release 9: TV launch, faster Wayland, GPU spoof)
===============================================================================================

1. Bannerlator-3.1.2-wayland-pre9-<flavour>.apk   (all three flavours on the GitHub pre-release)
   versionCode 85 like 3.1.1, so you can go back to 3.1.1 or forward to the next stable.
   New since pre-release 8 (use the v16 layer below):
   - Launch a game on your TV: a "TV" tab in the game's settings (and XMB settings) appears while an
     external screen is connected; it reads the screen's modes and HDR10 support. "Launch this game
     on the TV" starts the session on that screen; the handheld shows a companion screen (Send input
     back to the TV, End the game). Touching the handheld no longer freezes the game; pulling the
     cable pauses the game and moves it back to the handheld. HUD/drawer HDR readouts follow the
     screen the game is on. Not tested yet: Home + reopen, match resolution, output-mode picker.
   - Wayland performance phase 1: layer pool of 5 with GPU-side release waits and UBWC requests,
     letterbox-only clears, CPU affinity on Wayland, "Prefer big cores" = every core >=70% of peak.
     Pocket FIT AIO copy path vs pre-release 8: Vulkan +9%, D3D12 +12%, DirectDraw +20%, D3D11 +3.5%.
   - Wayland driver settings (the gear next to "Wayland game driver"): GPU Name (spoof) - written to
     a generated DXVK config so every DXVK version gets it (proven with God of War on DXVK 2.4.1),
     the HUD shows "<card> spoof"; DXVK memory cap; present mode; OneUI / HyperOS UBWC hint.
   - Unreal Engine HDR (game settings): Off / DirectX 12 fix / DirectX 11 (experimental, bundled
     dxvk-nvapi 0.9.2). Tetris Effect: HDR10 on a TV (DX12 + DX11) and on the Fold (DX11), with HDR
     forced in its ini files (the game's own menu did not switch it on).
   - RE Engine HDR (Resident Evil): the games only offer HDR with an AMD GPU. With the v16 layer and
     GPU Name (spoof) = an AMD card (e.g. Radeon RX 6800/6800 XT / 6900 XT) + HDR output on,
     Resident Evil 3 asks "Enable HDR?" and AGS reports an HDR10 display. Not yet seen on an HDR screen.
   - AIO Graphics Test with the HDR card is built into new containers (Start menu: AIO Graphics Test (HDR)).
   - Fusion HUD pill: a long GPU name (a spoofed card) gets its own top line instead of stretching the pill.
   New since pre-release 7 (pre-release 8):
   - HDR10 on HDR screens: an "HDR output (HDR10)" setting in the container, game shortcut and
     XMB settings (shown only on screens that report HDR10; applies from the next launch; sets
     DXVK_HDR=1 and hands your screen's brightness to the layer). The game's own 10-bit HDR frames
     go straight to the display tagged HDR10 (BT.2020 PQ).
   - HDR stays on with screen effects, windowed games, a window on top and zero-copy off (one
     10-bit HDR picture on the game's display layer). The drawer's Graphics tab has a live
     "HDR output" switch: Off = the same picture tone-mapped to normal brightness.
   - Frame generation keeps HDR: LSFG Native / Win-FG Native run on the HDR picture in 16-bit and
     present through a 10-bit HDR10 swapchain (60 fps shown at 120, every frame HDR, on the Fold).
     Where the screen offers no HDR10 swapchain the frames are tone-mapped to SDR, never washed out.
   - On Android 15+ the app asks Android for the HDR boost (not proven to help yet on phones that
     do not boost on their own).
   - The HUD shows the HDR state on its own line under "<latency> . Wayland": HDR, HDR (no
     headroom), HDR off, HDR tone-mapped, HDR ready. Non-HDR sessions look as before.
   - The session log explains HDR: the screen's capability, each HDR path, the live HDR/SDR
     headroom, and the phone's heat level and brightness when the boost drops. Screenshots,
     screen recordings and a hot phone switch the boost off - that is Android.
   - Fusion HUD starts in the top-right corner; new containers get the Fusion pill at 75% with
     every metric on (existing containers keep their own HUD settings).
   - The frame generation picker works on Wayland in the container, shortcut and XMB settings.
   New since pre-release 6 (pre-release 7):
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

2. proton-11.0-2.1-arm64ec-wayland-v16.wcp   (installs as Proton-11.0-2.1-arm64ec-16)
   New in v16: Wine's own AMD AGS library (amd_ags_x64) is built and wins over the copy a game ships
   with (arm64ec). It answers from DXGI, so RE Engine games see an HDR10 display when HDR is on.
   Proven: Resident Evil 3 still runs at 60 fps with it, and with an AMD GPU spoof it asks "Enable HDR?".
   New in v13-v14: Windows' display-configuration API reports your monitor as HDR (advanced colour),
   plus the SDR white level and advanced colour state answers.
   New in v12: the eight Turnips ask for compressed (UBWC) buffers for zero-copy.
   New in v11: games see your screen's real HDR description (peak, full-screen brightness, black
   level, colours, as Android reports them) instead of DXVK's made-up 1499-nit screen. v10 built
   the description; v11 fixed Wine's virtual desktop, which handed games a monitor without it.
   Proven: 1345 nits on a Galaxy Z Fold 8 Ultra, 1207 on a ROG Phone 9 Pro, 892 on an Adreno 735.
   Nothing changes on a screen without HDR10.
   New in v9: OpenGL games draw on phones that hide their display device (they were black with
   sound on many retail Adreno 830/840 phones; the session log now says when this path is taken).
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
   switch, and the EGL fix that lets OpenGL games render. Containers on -7 to -15 show an
   "Update layer" button that moves them to -16 (registry backed up first, revertable).
   All eight load and render on an Adreno 750; the a8xx builds now run on real Adreno 830/840
   phones; 710/720/722 are still untested on real hardware.

3. No Turnip zip needed: the compositor uses whatever Android Turnip you pick under
   "Compositor driver" (any recent one from the in-app catalog). Never pick "System".

4. AIO-Graphics-Test-HDR-64bit.exe   (AIO Graphics Test with the HDR test card; new containers include it)
   Run it in your Wayland container with HDR output on, then Display Tests > HDR.
   - Top line "HDR10 ON"; the line under it says whether Windows sees your screen
     ("DXGI reports your screen (~N nits)") or DXVK's stand-in (1499/799/0.01).
   - Square corner button = true fullscreen ("fullscreen: yes (W x H at 0,0)"), so the frames can
     go straight to the display (zero-copy).
   - Tap HDR10 / SDR to compare: in HDR the 400 / 600 / 1000 / max patches step up in brightness
     and the sun glares; in SDR everything from 203 up is the same white.
   - Banding strips: the 10-bit strip should stay smooth, also with frame generation on.
   - Report: AIO Results\HDR\AIO-Graphics-Test_hdr.txt next to where it runs. Don't screen-record
     while testing HDR (it switches the HDR boost off); a photo of the screen is fine.

Setup
-----
1. Install the APK for your flavour over 3.1.1 or an earlier pre-release.
2. Contents > Proton > Install from file: the v16 wcp.
3. Container: Proton = Proton-11.0-2.1-arm64ec-16, Display backend = Wayland, Compositor driver =
   an Android Turnip, Wayland game driver = Auto (8xx owners: try the alternatives one by one),
   FEXCore = an installed version. DXVK / VKD3D / components / audio as on X11.
4. Optional experiments via Env Vars: BANNER_WAYLAND_ZERO_COPY=1 (fullscreen games only),
   BANNER_WAYLAND_UBWC=0 (if the picture is scrambled), TU_DEBUG=sysmem (Vauzi's tip for 710/720/722).
5. HDR (HDR screens only): turn on "HDR output (HDR10)" in the game's shortcut settings, switch HDR
   on in the game's own options (DXVK v3.1 is the tested version), and check with the AIO HDR card.
   Unreal Engine games: set "Unreal Engine HDR". RE Engine (Resident Evil) games: gear > GPU Name
   (spoof) = an AMD card, e.g. Radeon RX 6800/6800 XT / 6900 XT.
6. TV: plug the screen in, game settings > TV > "Launch this game on the TV", then launch the game.

Verified for HDR (Samsung Galaxy Z Fold 8 Ultra, Adreno 840, HDR10 1351 nits)
------------------------------------------------------------------------------
  God of War: 10-bit HDR frames zero-copy on the display layer, HDR/SDR headroom up to 2.3-3.2x
  while the phone was cool; drawer HDR switch off/on; CAS / FXAA / colour effects kept HDR.
  AIO HDR card: DXGI reports the screen (1345 nits); fullscreen HDR zero-copy 599 frames per 10 s;
  frame generation 2x at 120 fps, every frame through the 10-bit HDR10 swapchain, 0 tone-mapped;
  headroom 3.0-3.6x steady. 0 washed-out frames in every run.
  The screen description also reached DXGI on a ROG Phone 9 Pro (1207 nits) and an Adreno 735
  phone (892 nits).

Verified in pre-release 9 (AYANEO Pocket FIT, Adreno 750)
--------------------------------------------------------
  TV tab: launch from the Games tab onto an HDR TV (HDR gate open for the TV), companion screen on
  the handheld, touching the handheld kept the game running at 54 fps with the controller on the TV,
  End the game, cable pull -> pause -> resumes on the handheld with sound.
  GPU spoof: God of War on DXVK 2.4.1 got all six spoof settings; the HUD read "<card> spoof".
  Tetris Effect HDR10 on a TV in DX12 and DX11 (Unreal Engine HDR, ini forced); DX11 also on the Fold.
  Resident Evil 3 on v16: 60 fps; with a Radeon RX 6800 spoof the game asks "Enable HDR?" and AGS
  reports Stage 7, ColorSpace HDR10, HDR10 1.
  Phase 1, copy path vs pre-release 8 (AIO): Vulkan +9%, D3D12 +12%, DirectDraw +20%, D3D11 +3.5%.

Verified on the Wayland backend (AYANEO Pocket FIT, Adreno 750)
--------------------------------------------------------------
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
- HDR in games needs a per-engine helper: RE Engine needs an AMD GPU spoof (RE3's HDR not yet seen on
  an HDR screen); Tetris needed HDR forced in its ini files (Unreal Engine HDR doesn't write them yet).
- TV launch: Home + reopen, match resolution and the output-mode picker are untested. Android gives
  input to one screen at a time: use "Send input back to the TV" if the controller stops reaching the game.
- Not device-tested: the DXVK memory cap, present mode and UBWC hint in the gear; the Fusion pill top line.
- Unreal Engine HDR also shows on X11, where it doesn't help yet.
- The HDR boost depends on the phone: the Fold brightens HDR highlights up to ~3.6x; a ROG Phone 9 Pro
  got a correct HDR picture but no extra brightness. This build asks Android for the boost (15+);
  not proven to help yet. Screenshots, screen recordings and heat switch the boost off.
- scRGB (the other HDR format some games use) shows in normal brightness: the compositor offers HDR10.
- Measured on the Pocket FIT, Half-Life 2 uncapped, 2x60 s each: zero-copy 185 fps vs 187 fps copy path
  (no change, CPU-bound), GPU busy 79% vs 83%, GPU clock 944 vs 1000 MHz, power 16.3 W vs 16.8 W.
  Zero-copy removes the compositor's GPU work; fps gains need a GPU-bound game or a big panel.
- Real Adreno 710/720/722 hardware untested; 830/840 owners: please report which a8xx build works best.
- OpenGL is newly working, not broadly tested. Reports from OpenGL games are the most useful thing
  to send right now.
- OpenGL safe mode (on by default) avoids the vanishing-game crash in Mesa's threaded driver; it does
  not fix it.
- Drag-and-drop, image clipboard and window decorations are not on Wayland yet.
- With a window above the game, THIS panel hands the frame back to the GPU instead of composing two
  layers itself (the game still keeps its copy-free frames). Other panels may differ - please report.
- Frame generation still needs the compositor pass, so it pauses zero-copy.
- The live switch is proven on the Adreno 750 only. On a GPU where the compositor cannot import the
  game's buffers, switching off keeps the old frames on the layer until the game rebuilds.

Logs: Download/Wayland-logs/wayland-*.log (compositor) and Download/bannerlator/<game>/wine_debug.log.
