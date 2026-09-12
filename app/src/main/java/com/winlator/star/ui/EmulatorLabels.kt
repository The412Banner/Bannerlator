package com.winlator.star.ui

/**
 * Display labels for the "Emulator" picker (the x86 -> ARM translator).
 *
 * The picker's stored identifiers stay "fexcore" / "box64" EVERYWHERE (Container.emulator, the
 * shortcut "emulator" extra, ShortcutConfig/ConfigExporter, GuestProgramLauncherComponent's
 * `emulator.equals("fexcore")` test). Only what the user reads changes, so nothing here can move
 * a container onto a different backend.
 *
 * Two corrections, both matching GuestProgramLauncherComponent.execGuestProgram():
 *
 *  - arm64ec container: the non-FEX backend is `HODLL=wowbox64.dll`, NOT box64 — so the entry
 *    labelled "Box64" must read "WOWBox64" (the container editor's WinComponents tab already
 *    says this; the General-tab dropdowns never got the same treatment).
 *  - x86_64 container: the picker is inert — the launcher unconditionally runs `bin/box64` and
 *    never looks at the value — so the disabled field must read "Box64" rather than the stored
 *    Container.DEFAULT_EMULATOR ("FEXCore"), which lied about what was running.
 */
object EmulatorLabels {

    /** Canonical entries, as spelled in R.array.emulator_entries. */
    const val FEXCORE = "FEXCore"
    const val BOX64 = "Box64"

    /** The arm64ec spelling of the box64 entry. */
    const val WOWBOX64 = "WOWBox64"

    /** Label for one entry. Identity for FEXCore; Box64 -> WOWBox64 on arm64ec. */
    @JvmStatic
    fun display(entry: String, isArm64EC: Boolean): String =
        if (isArm64EC && entry.equals(BOX64, ignoreCase = true)) WOWBOX64 else entry

    /** Labels for a whole entry list, order preserved. */
    @JvmStatic
    fun options(entries: List<String>, isArm64EC: Boolean): List<String> =
        entries.map { display(it, isArm64EC) }

    /**
     * Map a displayed label back to its canonical entry, so what gets persisted is unchanged by
     * the relabelling. Falls through to the label itself if it isn't one we produced.
     */
    @JvmStatic
    fun fromDisplay(shown: String, entries: List<String>, isArm64EC: Boolean): String =
        entries.firstOrNull { display(it, isArm64EC) == shown } ?: shown

    /** The entry an x86_64 container actually runs, whatever happens to be stored. */
    @JvmStatic
    fun box64EntryOf(entries: List<String>): String =
        entries.firstOrNull { it.equals(BOX64, ignoreCase = true) } ?: BOX64
}
