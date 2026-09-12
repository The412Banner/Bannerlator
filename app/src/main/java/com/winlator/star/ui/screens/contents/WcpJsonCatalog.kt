package com.winlator.star.ui.screens.contents

import org.json.JSONArray

/**
 * Parser for the flat `contents.json` shape shared by the Official catalog
 * ([com.winlator.star.contents.ContentsManager.REMOTE_PROFILES]) and community WCP_JSON sources
 * (StevenMXZ): `[{type, verName, verCode, remoteUrl}, …]`. Pure Kotlin + org.json so it is unit-testable
 * without Android; [RemoteSourceRepository] only does the fetch and hands the text here.
 *
 * Type matching prefers the entry's own `type` field — the catalogs list `Box64` AND `WOWBox64`
 * (and `DXVK` next to `D7VK`), and a substring match on the display name filed every WOWBox64 pack
 * under Box64 too, where it would have been saved to the wrong folder and badged against the wrong
 * installed key. When nothing matches the field exactly the legacy substring match still applies so
 * aliases (`fex` → `FEXCore`) and catalogs with unusual type strings keep resolving.
 */
object WcpJsonCatalog {

    /** Parses [json] and returns the entries for [componentType], newest (last in file) first. */
    fun parse(json: String, componentType: String, sourceName: String): List<RemoteSourceRepository.RemoteItem> {
        val array = JSONArray(json)
        data class Entry(val type: String, val item: RemoteSourceRepository.RemoteItem)
        val all = ArrayList<Entry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val type = obj.getString("type")
            val verName = obj.getString("verName")
            val url = obj.getString("remoteUrl")
            // verCode is a string in the live catalog ("5") — optString reads either shape.
            val verCode = obj.optString("verCode", "").toIntOrNull()
            val profileVersionName = obj.optString("versionName", "").takeIf { it.isNotBlank() }
            all.add(Entry(type, RemoteSourceRepository.RemoteItem(
                displayName = "$type  $verName", versionName = verName, downloadUrl = url, sourceName = sourceName,
                verCode = verCode, profileVersionName = profileVersionName,
            )))
        }
        val filtered: List<Entry> = when {
            componentType == RemoteSourceRepository.GPU_DRIVER_TYPE ->
                all.filter { e -> RemoteSourceRepository.GPU_DRIVER_KEYWORDS.any { kw -> e.item.displayName.contains(kw, ignoreCase = true) } }
            else -> {
                val exact = all.filter { it.type.equals(componentType, ignoreCase = true) }
                when {
                    exact.isNotEmpty() -> exact
                    componentType.equals("fex", ignoreCase = true) -> all.filter { it.item.displayName.contains("fex", ignoreCase = true) }
                    else -> all.filter { it.item.displayName.contains(componentType, ignoreCase = true) }
                }
            }
        }
        return filtered.map { it.item }.reversed()
    }
}
