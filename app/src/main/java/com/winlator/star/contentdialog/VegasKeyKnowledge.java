package com.winlator.star.contentdialog;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * VEGAS config-key knowledge model (locked design, 2026-08-16).
 *
 * Three knowledge sources, never conflated:
 *  - vanilla DXVK options (upstream-documented, present in every build incl. forks)
 *    -> version-independent, never filtered as "features of vX"
 *  - fork keys (vegas.* app layer + fork-added dxvk.*) -> gated by the SELECTED
 *    VEGAS version (its stock config key set)
 *  - env-style [other] keys (DXVK_FRAME_RATE, ...) -> always apply
 *
 * Per-key state against a version:
 *  - OK        key is part of the version's stock key set (documented in its notes)
 *  - LATE      fork key absent from the version's stock set -> introduced in a later version
 *  - UNLISTED  dxvk.* key we cannot provenance (neither vanilla-documented nor fork manifest)
 *  - VANILLA   upstream DXVK option -> applies to every version
 *  - UNKNOWN   no stock template for the version on device (e.g. unreleased previews)
 *
 * The manifest is a static table for now; per decision 9 it should move to
 * download-time data (per-release JSON) so the app and its data never drift.
 */
public final class VegasKeyKnowledge {
    private VegasKeyKnowledge() {}

    public enum State { OK, LATE, UNLISTED, VANILLA, UNKNOWN }

    private static final Set<String> VANILLA_DXVK = new HashSet<>(Arrays.asList(
        "dxvk.numCompilerThreads", "dxvk.numAsyncThreads", "dxvk.enableGraphicsPipelineLibrary",
        "dxvk.enableStateCache", "dxvk.hud", "dxvk.async", "dxvk.maxSharedMemory", "dxvk.useRawSsbo",
        "dxvk.crossSubmissionThreads", "dxvk.fakeDeviceName", "dxvk.hideNvidiaGpu", "dxvk.shrinkNvidiaHvvHeap",
        "dxvk.forceInverseZRange", "dxvk.deferSurfaceCreation", "dxvk.customVendorId", "dxvk.customDeviceId"
    ));

    /** Fork-added dxvk.* keys (mirrors the real inline names used by DXVKConfigDialog.setEnvVars). */
    private static final Set<String> FORK_DXVK = new HashSet<>(Arrays.asList(
        "dxvk.enableStarProfile", "dxvk.tileReuse"
    ));

    public static boolean isVanilla(String key) {
        return key != null && VANILLA_DXVK.contains(key);
    }

    public static boolean isForkKey(String key) {
        return key != null && (key.startsWith("vegas.") || FORK_DXVK.contains(key));
    }

    /**
     * Classifies a config key against the selected version's stock key set.
     *
     * @param key            config key, e.g. "vegas.telemetry", "dxvk.numCompilerThreads"
     * @param versionKeySet  the SELECTED version's stock key set (the keys shipped commented in
     *                       its stock config); null when that stock template is not on device
     */
    public static State stateFor(String key, Set<String> versionKeySet) {
        if (key == null) return State.UNKNOWN;
        if (isVanilla(key)) return State.VANILLA;
        if (!key.startsWith("vegas.") && !key.startsWith("dxvk.")) return State.OK; // env-style [other]
        if (versionKeySet == null) return State.UNKNOWN;
        boolean inSet = versionKeySet.contains(key);
        if (isForkKey(key)) return inSet ? State.OK : State.LATE;
        return inSet ? State.OK : State.UNLISTED;
    }

    /**
     * The inline defaults DXVKConfigDialog.setEnvVars emits when NO config file is active
     * ("USE DEFAULTS" state): the single source of truth for the defaults info line.
     */
    public static List<String> inlineDefaults() {
        return Arrays.asList("dxvk.enableStarProfile = Auto", "vegas.enableUpscaler = Auto");
    }
}
