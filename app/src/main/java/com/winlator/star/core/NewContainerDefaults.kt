package com.winlator.star.core

import android.content.Context

/**
 * User-configurable "New Container Defaults" — the seed a brand-new container is built from.
 *
 * Stored as TWO INDEPENDENT profiles, one per architecture (x86-64 and arm64ec), because the
 * emulator/box64/wowbox64/FEXCore fields are arch-coupled (refreshWineDependent swaps the
 * box64↔wowbox64 lists on isArm64EC) — a single arch-agnostic profile can't serve both. Each is a
 * JSON blob holding the same container-config `data` shape the create flow builds (see
 * ContainerDetailViewModel.doConfirm), MINUS "name", "drives" and "wineVersion" (per-container /
 * never templated). When an arch's profile is set, ContainerDetailViewModel seeds a matching new
 * container from it; when unset the built-in `Container.DEFAULT_*` constants are used, so the
 * no-profile create path is unchanged.
 *
 * Kept in a DEDICATED SharedPreferences file (not the default prefs, not Room) so it survives an
 * in-place app update and stays completely separate from config export/import and community configs.
 */
object NewContainerDefaults {

    const val ARCH_X86_64 = "x86_64"
    const val ARCH_ARM64EC = "arm64ec"

    private const val PREFS = "new_container_defaults"
    // Per-arch prefs keys: "profile_x86_64" / "profile_arm64ec".
    private fun keyFor(arch: String) = "profile_$arch"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persist the [arch] profile JSON (the stripped container-config `data` object). */
    fun save(context: Context, arch: String, json: String) {
        prefs(context).edit().putString(keyFor(arch), json).apply()
    }

    /** The saved profile JSON for [arch], or null when the user has never set one for it. */
    fun load(context: Context, arch: String): String? = prefs(context).getString(keyFor(arch), null)

    /** Forget the [arch] defaults so new containers of that arch fall back to `Container.DEFAULT_*`. */
    fun clear(context: Context, arch: String) {
        prefs(context).edit().remove(keyFor(arch)).apply()
    }

    /** Whether a profile is set for [arch] (i.e. new containers of that arch should seed from it). */
    fun exists(context: Context, arch: String): Boolean = load(context, arch) != null
}
