package com.winlator.star.container;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Legacy migration ("Found your config") — build order item 5, report §5.2 / §6a.5.
 *
 * The trust moment: users who downloaded a repo config, edited it, and added keys of
 * their own must see, on first open after the update, that THEIR file was found and
 * preserved — otherwise the split reads as "another config wipe". This class detects
 * the legacy state, performs the one-time adoption into the active-config world, and
 * keeps an explicit .bak-<tag> escape hatch (reversible by user action only).
 *
 * Detection (all must hold):
 *   - stored dxvkConfigFile path == a parked stock file path (the pre-split world
 *     persisted the baseline path; the split world persists the active path)
 *   - no active.conf yet (still in legacy world)
 *   - no "adopt" event in the sidecar (idempotent — never recurs)
 *   - no dismiss marker for THIS path (user already chose "start fresh" for it)
 *   - the parked file still exists (nothing to adopt otherwise)
 *
 * Decisions are recorded, never silent:
 *   - "Use it"  -> adopt(): active.conf = legacy content via VegasActiveConfig.write()
 *                 with event "adopt" (the sidecar marks sourceType baseline /
 *                 sourceBaseline = tag), plus a best-effort copy of the pre-adoption
 *                 bytes at <legacy>.bak-<sourceTag> as the user-invoked escape hatch.
 *   - "Start fresh" -> dismiss(): marker file vegas/migration.dismissed keyed to the
 *                 path; the banner never recurs for that file, nothing is written.
 *
 * Dependency-free (java.io + java.nio + VegasActiveConfig only) — harnessable
 * standalone, same discipline as VegasActiveConfig / VegasKeyCatalog.
 */
public final class VegasMigration {
    public static final String DISMISS_FILENAME = "migration.dismissed";

    public enum Plan { NONE, ADOPT }

    private VegasMigration() {}

    /**
     * Pure decision: should the "Found your config" banner be offered?
     * Returns ADOPT only when a real, undecided legacy adoption is pending.
     */
    public static Plan plan(File rootDir, String storedConfigPath, String stockFilePath) {
        if (rootDir == null || storedConfigPath == null || stockFilePath == null) return Plan.NONE;
        if (!storedConfigPath.equals(stockFilePath)) return Plan.NONE; // stored path isn't the parked stock file
        if (VegasActiveConfig.exists(rootDir)) return Plan.NONE;       // already in the split world
        for (VegasActiveConfig.Event e : VegasActiveConfig.events(rootDir))
            if (VegasActiveConfig.EVENT_ADOPT.equals(e.type)) return Plan.NONE; // already adopted — never recurs
        if (dismissedFor(rootDir, storedConfigPath)) return Plan.NONE; // user chose start-fresh for THIS file
        return new File(stockFilePath).isFile() ? Plan.ADOPT : Plan.NONE; // file gone -> nothing to adopt
    }

    /**
     * One-time adoption. Returns false WITHOUT writing anything when inputs are
     * invalid (null/empty sourceTag included — the caller must resolve provenance,
     * exactly like the seed decision row). Writes active.conf + "adopt" event first;
     * the .bak-<tag> copy and the dismiss-marker cleanup are best-effort and must
     * never roll back a recorded adoption.
     */
    public static boolean adopt(File rootDir, File legacyFile, String content, String sourceTag) {
        if (rootDir == null || legacyFile == null || content == null
                || sourceTag == null || sourceTag.trim().isEmpty()) return false;
        if (!VegasActiveConfig.write(rootDir, content, VegasActiveConfig.EVENT_ADOPT, sourceTag)) return false;
        // Escape hatch: preserve the pre-adoption bytes next to the parked file.
        File bak = new File(legacyFile.getAbsolutePath() + ".bak-" + sourceTag);
        try {
            if (legacyFile.isFile() && !bak.isFile())
                Files.copy(legacyFile.toPath(), bak.toPath());
        } catch (Exception ignored) {
            // backup is best-effort — adoption was already recorded and must stand
        }
        clearDismiss(rootDir);
        return true;
    }

    /** "Start fresh": record the choice so the banner never recurs for this file. */
    public static void dismiss(File rootDir, String storedConfigPath) {
        if (rootDir == null || storedConfigPath == null) return;
        try {
            File dir = new File(rootDir, VegasActiveConfig.DIR_NAME);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            Files.write(new File(dir, DISMISS_FILENAME).toPath(),
                    (storedConfigPath + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // marker write is best-effort; a failure just re-offers next time
        }
    }

    private static boolean dismissedFor(File rootDir, String storedConfigPath) {
        if (rootDir == null || storedConfigPath == null) return false;
        File f = new File(new File(rootDir, VegasActiveConfig.DIR_NAME), DISMISS_FILENAME);
        if (!f.isFile()) return false;
        try {
            String recorded = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            return recorded.equals(storedConfigPath);
        } catch (Exception e) {
            return false; // unreadable marker -> not dismissed -> re-offer (lenient, like the sidecar)
        }
    }

    private static void clearDismiss(File rootDir) {
        try {
            File f = new File(new File(rootDir, VegasActiveConfig.DIR_NAME), DISMISS_FILENAME);
            if (f.isFile() && !f.delete()) f.deleteOnExit();
        } catch (Exception ignored) {
        }
    }
}
