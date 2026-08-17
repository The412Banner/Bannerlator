package com.winlator.star.communityconfigs

import android.util.Log
import org.json.JSONObject

/**
 * One downloadable component a config asks for, kept in both raw + cleaned form so the resolver can
 * match it against what's installed and produce an honest install advisory.
 *
 * @param type   catalog bucket — "DXVK" / "VKD3D" / "Turnip" / "Proton" / "FEXCore".
 * @param wants  the raw XiaoJi name ("dxvk-2.7.1-1-async"), shown verbatim in advisories.
 * @param target cleaned version token ("2.7.1-1-async") used for minor-aware matching.
 */
data class ComponentRequest(
    val type: String,
    val wants: String,
    val target: String,
)

/**
 * Shortcut-native, reversible representation of a community config — the exact set of shortcut
 * {@code [Extra Data]} keys a translate produces and an apply consumes. Deliberately flat and
 * serialization-friendly: a future Phase 3 "export shortcut → config" is the straight reverse
 * (read {@code shortcut.getExtra(...)} into these same buckets, then serialize).
 *
 *  - [scalars] — keys written whole via {@code putExtra} (dxwrapper, emulator, audioDriver, …).
 *  - [dxwrapperConfig] — sub-keys to MERGE into the comma {@code k=v} list (version/vkd3dVersion/async).
 *  - [graphicsDriverConfig] — sub-keys to MERGE into the semicolon {@code k=v} list (the driver version).
 *  - [components] — versions to resolve against the installed catalog (drives install advisories).
 *  - [advisories] — surfaced but never written (e.g. {@code wineVersion}/Proton is container-only).
 */
data class ShortcutConfig(
    val scalars: Map<String, String>,
    val dxwrapperConfig: Map<String, String>,
    val graphicsDriverConfig: Map<String, String>,
    val components: List<ComponentRequest>,
    val advisories: Map<String, String>,
    val sourceDevice: String?,
    val sourceSoc: String?,
)

/**
 * Translates a BannerHub (XiaoJi {@code com.xj.winemu}) {@code pc_ls_*} config into a [ShortcutConfig].
 * Mirrors {@code tools/translate.py} in The412Banner/bannerlator-game-configs field-for-field so the
 * client stays in lockstep with the CI-side reference:
 *   pc_ls_DXVK → dxwrapper + dxwrapperConfig.version; pc_ls_VK3k → dxwrapperConfig.vkd3dVersion;
 *   pc_ls_GPU_DRIVER_ → graphicsDriverConfig.version; pc_ls_GRAPHICS_WRAPPER → graphicsDriver (scalar);
 *   pc_set_constant_95 → emulator + fexcoreVersion;
 *   pc_ls_AUDIO_DRIVER → audioDriver; pc_ls_boot_option → execArgs; pc_ls_environment_variable → envVars;
 *   pc_ls_update_enable_xinput → inputType. pc_ls_CONTAINER_LIST (Proton) is advisory only.
 * XiaoJi-only fields (steam_client, hub_type, base component) are dropped.
 */
object ConfigTranslator {

    private const val TAG = "CommunityConfigs"

    /** Component values are JSON blobs; pull the human {@code name}/{@code displayName}. */
    private fun jname(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val o = JSONObject(raw)
            o.optString("name").ifBlank { o.optString("displayName") }
        } catch (e: Exception) {
            raw
        }
    }

    // Version extractors — 1:1 with translate.py's regexes.
    private fun dxvkVer(n: String) = n.replace(Regex("^dxvk[-_ ]?v?", RegexOption.IGNORE_CASE), "").trim()
    private fun vkd3dVer(n: String) =
        n.replace(Regex("^vkd3d[-_ ]?(proton[-_ ]?)?", RegexOption.IGNORE_CASE), "").trim()
    private fun turnipVer(n: String): String {
        val m = Regex("v?(\\d+\\.\\d+\\.\\d+)").find(n)
        return if (m != null) "Mesa Turnip v${m.groupValues[1]}" else n
    }
    private fun protonVer(n: String): String {
        val m = Regex("(\\d+\\.\\d+)").find(n) ?: return n
        val arm = if (Regex("arm64x|arm64ec", RegexOption.IGNORE_CASE).containsMatchIn(n)) "-arm64ec" else ""
        return "proton-${m.groupValues[1]}$arm"
    }

    /**
     * Public Proton normalizer — reduces any Proton/Wine name to its comparable key ({@code
     * proton-<major.minor>[-arm64ec]}). Lets the apply engine tell whether a config's Proton and the
     * container's Proton are the SAME base build even when the raw names differ (e.g. the config's
     * normalized {@code proton-11.0-arm64ec} vs the container's {@code Proton-11.0-1-arm64ec-4}).
     */
    fun protonKey(name: String): String = protonVer(name)

    private fun fexVer(n: String) = n.trim()

    /** Parse a whole BannerHub config JSON object → [ShortcutConfig]. Never throws (malformed → best effort). */
    fun translate(root: JSONObject): ShortcutConfig {
        val s = root.optJSONObject("settings") ?: JSONObject()
        val meta = root.optJSONObject("meta") ?: JSONObject()
        val components = ArrayList<ComponentRequest>()

        fun comp(key: String, extract: (String) -> String, label: String): String? {
            val raw = jname(s.optString(key, ""))
            if (raw.isBlank()) return null
            val v = extract(raw)
            components.add(ComponentRequest(type = label, wants = raw, target = v))
            return v
        }

        val dxvk = comp("pc_ls_DXVK", ::dxvkVer, "DXVK")
        val vkd3d = comp("pc_ls_VK3k", ::vkd3dVer, "VKD3D")
        val turnip = comp("pc_ls_GPU_DRIVER_", ::turnipVer, "Turnip")
        val proton = comp("pc_ls_CONTAINER_LIST", ::protonVer, "Proton")
        val fex = comp("pc_set_constant_95", ::fexVer, "FEXCore")

        val isFex = !fex.isNullOrBlank() || s.toString().lowercase().contains("fex")
        val dxwrapper = when {
            !vkd3d.isNullOrBlank() -> "dxvk+vkd3d"
            !dxvk.isNullOrBlank() -> "dxvk"
            else -> "wined3d"
        }
        val asyncOn = if (!dxvk.isNullOrBlank() && dxvk.lowercase().contains("async")) "1" else "0"

        val scalars = LinkedHashMap<String, String>()
        scalars["dxwrapper"] = dxwrapper
        scalars["emulator"] = if (isFex) "fexcore" else "box64"
        if (isFex && !fex.isNullOrBlank()) scalars["fexcoreVersion"] = fex
        // graphicsDriver = the top-level wrapper selection (plain scalar string, e.g. "wrapper-bcn_layer");
        // written straight back so a Mali/BCn wrapper choice survives the round-trip.
        val wrapper = s.optString("pc_ls_GRAPHICS_WRAPPER", "").trim()
        if (wrapper.isNotEmpty()) scalars["graphicsDriver"] = wrapper
        scalars["audioDriver"] = if (s.optString("pc_ls_AUDIO_DRIVER", "1") == "1") "pulseaudio" else "alsa"
        scalars["inputType"] = if (s.optBoolean("pc_ls_update_enable_xinput", true)) "1" else "0"
        val envv = s.optString("pc_ls_environment_variable", "").trim()
        if (envv.isNotEmpty()) scalars["envVars"] = envv
        val boot = s.optString("pc_ls_boot_option", "").trim()
        if (boot.isNotEmpty()) scalars["execArgs"] = boot

        val dxw = LinkedHashMap<String, String>()
        if (!dxvk.isNullOrBlank()) dxw["version"] = dxvk
        if (!vkd3d.isNullOrBlank()) dxw["vkd3dVersion"] = vkd3d
        if (!dxvk.isNullOrBlank()) dxw["async"] = asyncOn

        val gdc = LinkedHashMap<String, String>()
        if (!turnip.isNullOrBlank()) gdc["version"] = turnip

        val advisories = LinkedHashMap<String, String>()
        if (!proton.isNullOrBlank()) advisories["wineVersion"] = proton

        // Bannerlator's additive namespaced overlay: the ~28 shortcut extras the pc_* format can't carry.
        // Present only on OUR exports — BannerHub-origin configs have no bl_ext, so this loop is a no-op for
        // them and they translate exactly as before. Each raw key/value overlays scalars (overriding any
        // value the pc_* heuristics inferred, e.g. an explicit emulator="box64" beats the fex inference).
        // The lone exception is "dxwrapperConfig": it is the FULL comma-delimited DXVK sub-key list, whose
        // every sub-key (async, vulkanVersion, version, vkd3dVersion, …) is merged into the dxw sub-map
        // (NOT scalars), overriding the heuristic async and confirming the version tokens.
        val blExt = s.optJSONObject("bl_ext")
        if (blExt != null) {
            for (key in blExt.keys()) {
                val value = blExt.optString(key, "")
                if (key == "dxwrapperConfig") {
                    for (part in value.split(",")) {
                        val eq = part.indexOf('=')
                        if (eq <= 0) continue
                        dxw[part.substring(0, eq).trim()] = part.substring(eq + 1)
                    }
                } else {
                    scalars[key] = value
                }
            }
        }

        Log.d(TAG, "Translated config: dxwrapper=$dxwrapper dxvk=$dxvk vkd3d=$vkd3d turnip=$turnip fex=$fex proton=$proton")
        return ShortcutConfig(
            scalars = scalars,
            dxwrapperConfig = dxw,
            graphicsDriverConfig = gdc,
            components = components,
            advisories = advisories,
            sourceDevice = meta.optString("device", "").ifBlank { null },
            sourceSoc = meta.optString("soc", "").ifBlank { null },
        )
    }
}
