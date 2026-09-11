package com.winlator.star;

/**
 * Compile-time feature flags for work that is in-tree but not finished enough to ship on.
 * Flip one boolean to turn a whole feature on/off across the app.
 */
public final class FeatureFlags {
    private FeatureFlags() {}

    /**
     * TV / External-display output (Version A auto-swap onto a TV/DeX display) AND the in-game
     * "TV" tab that hosts it + the experimental wireless caster.
     *
     * OFF = the feature behaves as if it does not exist: the {@code ExternalDisplayController} is
     * never constructed or started, so connecting a TV/DeX display NEVER auto-swaps the game; the
     * wireless caster is not wired up; and the in-game TV tab is hidden. See issue #339 — DeX users
     * were getting a broken auto-swap. Re-enable once the external-display + DeX + cast paths are
     * finished and device-proven.
     */
    public static final boolean TV_OUTPUT_ENABLED = false;

    /**
     * Epic Friends Overlay (EOS Phase 3) — provision Epic's real overlay component into the wine
     * prefix + write the single {@code HKCU\Software\Epic Games\EOS} {@code OverlayPath} pointer so
     * the game's bundled EOS SDK renders friends/notifications on Shift+F3, plus the in-game
     * edge-snap {@code EpicOverlayPill} and its per-shortcut "Epic Friends Overlay" toggle.
     *
     * OFF = the feature behaves as if it does not exist: the per-shortcut toggle is hidden (no
     * shortcut can be set to {@code epicOverlay=1} through the UI), the pill is never attached, and
     * {@code provisionEpicOverlay()} routes to the STRIP branch for every Epic launch — it removes
     * any residual {@code OverlayPath} (cleaning up shortcuts that were toggled on during testing)
     * and writes/downloads nothing. Fully inert and self-cleaning while off.
     *
     * Stays OFF until the render path can actually show it: the overlay draws through the guest's
     * D3D/DXVK path and needs a wine-compat DXVK (>= PR #5257) on Wine >= 10.17 plus the CEF-under-
     * Wine bring-up. Flip to true once that wrapper lands and the overlay is device-proven to render.
     */
    public static final boolean EPIC_OVERLAY_ENABLED = false;

    /**
     * LSFG Native low-end experiments: the per-container "Capture resolution" (the frame-gen
     * chain runs below panel resolution and is blitted up; also live in the in-game drawer) and
     * "LSFG on Vulkan 1.1 drivers (compat)" (the capability probe accepts a Vulkan 1.1/1.2
     * compositor driver that offers VK_KHR_spirv_1_4 + VK_KHR_vulkan_memory_model, and the cached
     * SPIR-V 1.6 modules are lowered at load). Capture defaults to Panel and compat to off, so a
     * container that never touches them behaves exactly as before. See
     * docs/lsfg-native-lowend-perf-plan.md.
     *
     * OFF = the feature behaves as if it does not exist: both controls are hidden, the capture
     * resolution is forced to the panel and the compat switch to off whatever a container has
     * stored, so device creation, the probe verdict and the composite ring are unchanged.
     */
    public static final boolean LSFG_NATIVE_EXPERIMENTS_ENABLED = true;
}
