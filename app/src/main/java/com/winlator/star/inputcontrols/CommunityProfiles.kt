package com.winlator.star.inputcontrols

import com.winlator.star.core.HttpUtils
import org.json.JSONObject

/** One downloadable layout in Controls > Community profiles; [url] points at its .icp / .icpx file. */
data class CommunityProfile(
    val title: String,
    val url: String,
    val author: String? = null,
    val note: String? = null,
    val type: String? = null,
) {
    /** The profile maps a physical controller (keys / mouse from pad buttons), alone or with on-screen buttons. */
    val mapsController: Boolean get() = type == "controller" || type == "both"
}

data class CommunitySection(val id: String, val title: String, val items: List<CommunityProfile>)

/**
 * The Community profiles list. Read from index.json in the winlator-contents release `input-controls`
 * (Winlator's original layouts by brunodev85 + layouts shared in the Discord channel #bannerlator-osc-sharing).
 * Falls back to Winlator's own index.txt when index.json cannot be read.
 */
object CommunityProfiles {
    const val RELEASE_URL = "https://github.com/The412Banner/winlator-contents/releases/download/input-controls/"
    const val WINLATOR_URL = "https://raw.githubusercontent.com/brunodev85/winlator/main/input_controls/"

    @JvmStatic
    fun parseIndex(json: String): List<CommunitySection> {
        val sections = JSONObject(json).getJSONArray("sections")
        return (0 until sections.length()).mapNotNull { i ->
            val s = sections.optJSONObject(i) ?: return@mapNotNull null
            val items = s.optJSONArray("items") ?: return@mapNotNull null
            CommunitySection(
                id = s.optString("id"),
                title = s.optString("title"),
                items = (0 until items.length()).mapNotNull { j ->
                    val it = items.optJSONObject(j) ?: return@mapNotNull null
                    val file = it.optString("file").takeIf { f -> f.isNotBlank() && '/' !in f } ?: return@mapNotNull null
                    CommunityProfile(
                        title = it.optString("title").ifBlank { file },
                        url = RELEASE_URL + file,
                        author = it.optString("author").takeIf { a -> a.isNotBlank() },
                        note = it.optString("note").takeIf { n -> n.isNotBlank() },
                        type = it.optString("type").takeIf { t -> t.isNotBlank() },
                    )
                },
            )
        }.filter { it.items.isNotEmpty() }
    }

    @JvmStatic
    fun parseWinlatorList(text: String): List<CommunitySection> {
        val items = text.split("\n").map { it.trim() }.filter { it.isNotBlank() }.map { name ->
            CommunityProfile(title = name.removeSuffix(".icpx").removeSuffix(".icp"), url = WINLATOR_URL + name, author = "brunodev85")
        }
        return if (items.isEmpty()) emptyList() else listOf(CommunitySection("original", "Original", items))
    }

    /** Loads the list off the main thread; [onLoaded] gets the sections, or null when neither list could be read. */
    @JvmStatic
    fun load(onLoaded: (List<CommunitySection>?) -> Unit) {
        HttpUtils.download(RELEASE_URL + "index.json") { content ->
            val sections = content?.let { runCatching { parseIndex(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            if (sections != null) onLoaded(sections)
            else HttpUtils.download(WINLATOR_URL + "index.txt") { text ->
                onLoaded(text?.let { parseWinlatorList(it) }?.takeIf { it.isNotEmpty() })
            }
        }
    }
}
