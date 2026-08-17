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
}
