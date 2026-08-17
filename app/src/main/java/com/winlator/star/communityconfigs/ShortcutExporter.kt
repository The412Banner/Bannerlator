package com.winlator.star.communityconfigs

import android.content.Context
import android.os.Build
import com.winlator.star.BuildConfig
import com.winlator.star.container.Shortcut
import java.util.Random

/**
 * PHASE 3 step 1 — the thin Android adapter around the pure [ConfigExporter] core. Resolves a
 * shortcut's EFFECTIVE settings (per-game override, else container default — mirroring how
 * {@link CommunityConfigApply}'s {@code currentOrDefault} reads them), fills the device/version
 * provenance, generates a fresh {@code upload_token}, and hands everything to [ConfigExporter.export].
 *
 * Produces the artifact ONLY — no UI, no file I/O, no network. Later Phase-3 steps (share sheet,
 * {@code POST /upload}) consume [ExportResult].
 */
object ShortcutExporter {

    /**
     * The export artifact: the config [json], the BannerHub-shaped [fileName] to store/upload it under,
     * and the sanitized [game] slug the worker keys uploads by.
     */
    data class ExportResult(
        val json: String,
        val fileName: String,
        val game: String,
    )

    /**
     * Build a shareable config from [shortcut]. [context] is used only for the GPU-renderer soc probe.
     * Safe off the main thread (pure reads + string work). Never touches the filesystem or network.
     */
    fun fromShortcut(shortcut: Shortcut, context: Context): ExportResult {
        val container = shortcut.container

        // Effective settings = shortcut override, else the container default (the value the game runs
        // with). Composite k=v lists (dxwrapperConfig / graphicsDriverConfig) and the scalars with a
        // clean container getter fall back; execArgs / inputType are read as-is (shortcut-scoped).
        val effective = LinkedHashMap<String, String>()
        put(effective, "dxwrapper", orDefault(shortcut, "dxwrapper", container?.getDXWrapper()))
        put(effective, "dxwrapperConfig", orDefault(shortcut, "dxwrapperConfig", container?.getDXWrapperConfig()))
        put(effective, "graphicsDriverConfig", orDefault(shortcut, "graphicsDriverConfig", container?.getGraphicsDriverConfig()))
        put(effective, "graphicsDriver", orDefault(shortcut, "graphicsDriver", container?.getGraphicsDriver()))
        put(effective, "emulator", orDefault(shortcut, "emulator", container?.getEmulator()))
        put(effective, "fexcoreVersion", orDefault(shortcut, "fexcoreVersion", container?.getFEXCoreVersion()))
        put(effective, "audioDriver", orDefault(shortcut, "audioDriver", container?.getAudioDriver()))
        put(effective, "wineVersion", orDefault(shortcut, "wineVersion", container?.getWineVersion()))
        put(effective, "envVars", orDefault(shortcut, "envVars", container?.getEnvVars()))
        put(effective, "inputType", shortcut.getExtra("inputType"))
        put(effective, "execArgs", shortcut.getExtra("execArgs"))

        // The additive bl_ext overlay — the ~28 extra shortcut settings the pc_* format can't carry.
        // Fields with a clean container getter resolve EFFECTIVE (shortcut override, else container
        // default); the rest have no matching/clean container getter and are read shortcut-scoped only.
        // (emulator is already resolved above for the FEX gate; ConfigExporter picks it up from there.)
        put(effective, "screenSize", orDefault(shortcut, "screenSize", container?.getScreenSize()))
        put(effective, "renderer", orDefault(shortcut, "renderer", container?.getRenderer()))
        put(effective, "fullscreenMode", orDefault(shortcut, "fullscreenMode", container?.getFullscreenMode()?.toString()))
        put(effective, "frameGenEngine", orDefault(shortcut, "frameGenEngine", container?.getFrameGenEngine()))
        put(effective, "box64Version", orDefault(shortcut, "box64Version", container?.getBox64Version()))
        put(effective, "box64Preset", orDefault(shortcut, "box64Preset", container?.getBox64Preset()))
        put(effective, "fexcorePreset", orDefault(shortcut, "fexcorePreset", container?.getFEXCorePreset()))
        put(effective, "cpuList", orDefault(shortcut, "cpuList", container?.getCPUList()))
        put(effective, "wincomponents", orDefault(shortcut, "wincomponents", container?.getWinComponents()))
        put(effective, "midiSoundFont", orDefault(shortcut, "midiSoundFont", container?.getMIDISoundFont()))
        put(effective, "lc_all", orDefault(shortcut, "lc_all", container?.getLC_ALL()))
        put(effective, "reshadeLoadout", orDefault(shortcut, "reshadeLoadout", container?.getReshadeLoadout()))
        put(effective, "reshadeMode", orDefault(shortcut, "reshadeMode", container?.getReshadeMode()))
        put(effective, "reshadeParams", orDefault(shortcut, "reshadeParams", container?.getReshadeParams()))
        put(effective, "reshadeEffect", orDefault(shortcut, "reshadeEffect", container?.getReshadeEffect()))
        // No clean/matching container getter for these — read shortcut-scoped only (getExtra). e.g.
        // sfCompatMode's container form is getRendererSfCompatMode() (a boolean, not the extra's string),
        // and startupSelection's is a byte — neither cleanly matches the extra's string, so getExtra-only.
        put(effective, "renderScale", shortcut.getExtra("renderScale"))
        put(effective, "sfCompatMode", shortcut.getExtra("sfCompatMode"))
        put(effective, "fpsLimiterEnabled", shortcut.getExtra("fpsLimiterEnabled"))
        put(effective, "sharpnessEffect", shortcut.getExtra("sharpnessEffect"))
        put(effective, "sharpnessLevel", shortcut.getExtra("sharpnessLevel"))
        put(effective, "sharpnessDenoise", shortcut.getExtra("sharpnessDenoise"))
        put(effective, "startupSelection", shortcut.getExtra("startupSelection"))
        put(effective, "exclusiveXInput", shortcut.getExtra("exclusiveXInput"))
        put(effective, "disableXinput", shortcut.getExtra("disableXinput"))
        put(effective, "simTouchScreen", shortcut.getExtra("simTouchScreen"))
        put(effective, "numControllers", shortcut.getExtra("numControllers"))
        put(effective, "controlsProfile", shortcut.getExtra("controlsProfile"))
        put(effective, "autoCloseOnExit", shortcut.getExtra("autoCloseOnExit"))

        val device = Build.MANUFACTURER + " " + Build.MODEL
        val soc = DeviceIdentity.gpu(context) ?: DeviceIdentity.soc()
        // Optional account attribution (Phase 2): stamp the signed-in username/avatar into meta.uploader
        // when logged in; an anonymous export leaves both null (no uploader block), unchanged behaviour.
        val account = AccountManager.current(context)
        val meta = ConfigExporter.ExportMeta(
            appSource = "bannerlator",
            device = device,
            soc = soc,
            version = BuildConfig.VERSION_NAME,
            uploadToken = newUploadToken(),
            uploaderName = account?.username,
            uploaderAvatarUrl = account?.avatarUrl,
        )

        val json = ConfigExporter.export(effective, meta)
        val fileName = ConfigExporter.fileName(
            game = shortcut.name,
            mfr = Build.MANUFACTURER,
            model = Build.MODEL,
            soc = soc ?: "",
            epochSeconds = System.currentTimeMillis() / 1000L,
        )
        // The worker keys uploads by the sanitized game slug (same transform the file name uses).
        val game = shortcut.name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        return ExportResult(json, fileName, game)
    }

    /** Variable-length lowercase hex, non-crypto — the exact format BannerHub's exporter emits. */
    private fun newUploadToken(): String =
        java.lang.Long.toHexString(Random().nextLong() and Long.MAX_VALUE)

    /** Shortcut override, or the container default when the shortcut has none. */
    private fun orDefault(shortcut: Shortcut, key: String, default: String?): String {
        val v = shortcut.getExtra(key)
        return if (v.isNotBlank()) v else (default ?: "")
    }

    private fun put(map: MutableMap<String, String>, key: String, value: String) {
        if (value.isNotBlank()) map[key] = value
    }
}
