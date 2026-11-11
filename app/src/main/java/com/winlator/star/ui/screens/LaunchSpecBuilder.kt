package com.winlator.star.ui.screens

import android.content.Context
import com.winlator.star.container.GameDetails
import com.winlator.star.container.Shortcut
import com.winlator.star.contentdialog.GraphicsDriverConfigDialog
import com.winlator.star.core.PreloaderDetails
import com.winlator.star.core.PreloaderSpec

/**
 * Resolve a shortcut's component spec (renderer, DXVK/VKD3D versions, driver, frame-gen, x86 backend
 * + the "container-name · resolution" meta line) exactly the way the game-list card does — shortcut
 * override → container default. Single source of truth shared by [ShortcutItemLayoutL] and the launch
 * overlay so the two can never drift. Public (not internal) so the Java XServerDisplayActivity can
 * call it at launch time as `SpecCardComponentsKt`-style static.
 *
 * Takes a [context] (not just Resources) because the renderer chip has to ask whether this game
 * effectively runs on Wayland, which needs the container's layer probed.
 */
fun buildLaunchSpec(shortcut: Shortcut, context: Context): PreloaderSpec {
    val container = shortcut.container
    val resolution = shortcut.getExtra("screenSize", container?.getScreenSize() ?: "")
    val driverCfg = shortcut.getExtra("graphicsDriverConfig", container?.getGraphicsDriverConfig() ?: "")
    val driverLabel = if (driverCfg.isNotEmpty()) GraphicsDriverConfigDialog.getVersion(driverCfg) else ""
    val dxwrapperCfg = shortcut.getExtra("dxwrapperConfig", container?.getDXWrapperConfig() ?: "")
    val (dxvkVersion, vkd3dVersion) = parseDxwrapperConfig(dxwrapperCfg)

    // Renderer chip: on Wayland the stored id is the (unused) X11 setting — the compositor renders.
    val wayland = com.winlator.star.core.WineWaylandSupport.runsOnWayland(context, shortcut)
    val rendererLabel = rendererLabelOf(shortcut.getExtra("renderer", container?.renderer ?: ""), wayland)
    val frameGenLabel = frameGenLabelOf(shortcut.getExtra("frameGenEngine", container?.frameGenEngine ?: "off"))
    // What runs the game's x86 code: Box64 on an x86_64 layer whatever is stored, FEXCore / WOWBox64 on arm64ec.
    val backendLabel = com.winlator.star.ui.EmulatorLabels.chipLabel(
        shortcut.getExtra("emulator", container?.emulator ?: ""), container?.wineVersion)

    // EOS badge: an Epic-store shortcut with EOS auth injection not disabled (mirrors the
    // storeSource/epicEos gate XServerDisplayActivity uses to inject the launch args).
    val eosEnabled = "epic" == shortcut.getExtra("storeSource") && "0" != shortcut.getExtra("epicEos")

    val meta = listOf(container?.name ?: "", resolution).filter { it.isNotEmpty() }.joinToString(" · ")
    return PreloaderSpec(
        meta = meta,
        rendererLabel = rendererLabel,
        dxvkVersion = dxvkVersion,
        frameGenLabel = frameGenLabel,
        driverLabel = driverLabel,
        vkd3dVersion = vkd3dVersion,
        backendLabel = backendLabel,
        eosEnabled = eosEnabled,
    )
}

/**
 * Resolve a shortcut's accumulated Game Details (genres, release year, metacritic, short description)
 * for the launch overlay's right-side panel. Reads only the shortcut's own Extra Data — nothing is
 * inherited from the container — so a game with no saved details yields an empty (hidden) panel.
 * Public so the Java XServerDisplayActivity can call it at launch time (as `LaunchSpecBuilderKt`).
 */
fun buildLaunchDetails(shortcut: Shortcut): PreloaderDetails {
    val d = GameDetails.from(shortcut)
    return PreloaderDetails(
        genres = d.genres,
        releaseYear = d.releaseYear,
        metacritic = d.metacritic,
        description = d.description,
    )
}
