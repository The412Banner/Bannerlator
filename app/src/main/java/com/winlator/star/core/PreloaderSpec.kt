package com.winlator.star.core

/**
 * Component spec for a game launch, mirrored 1:1 from the game-list card so the launch overlay
 * shows the same resolved per-shortcut settings, in the same order. Built by
 * `com.winlator.star.ui.screens.buildLaunchSpec`; rendered by the PreloaderOverlay via SpecChipRows.
 * Pure data (no Android/Compose deps) so it can live in `core` and be carried on PreloaderUi.
 */
data class PreloaderSpec(
    val meta: String = "",            // "P11 ARM · 1280x720" (container name · resolution)
    val rendererLabel: String = "",   // Vulkan / SurfaceFlinger / OpenGL
    val dxvkVersion: String = "",     // e.g. "3.0.2-1-gplasync-0"
    val frameGenLabel: String = "",   // Bionic-FG / LSFG-VK (empty when off)
    val driverLabel: String = "",     // e.g. "Mesa Turnip v26.3.0-20260715-r2"
    val vkd3dVersion: String = "",    // e.g. "3.0.1-0"
    val backendLabel: String = "",    // FEXCore / Box64
    val eosEnabled: Boolean = false,  // Epic Online Services auth injection active for this launch
)
