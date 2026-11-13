package com.winlator.star.ui.deck.settings

import com.winlator.star.ui.simple.L
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.contents.ContentProfile
import com.winlator.star.ui.screens.ContentDownloadSheet
import com.winlator.star.ui.theme.AppThemeState
import kotlinx.coroutines.CoroutineScope

/**
 * The Performance page's translator downloads, which were the gears beside the classic editors' Box64 /
 * WOWBox64, FEXCore and Box64EC version pickers. A Wine game, a container and All containers; the sheet
 * opens only on the press, and a download refreshes the version lists the rows offer.
 */
internal class DeckTranslatorDownloads(
    val env: SettingsEnv,
    val source: SettingsSource,
    private val scope: CoroutineScope,
) : DeckSection {
    var sheet by mutableStateOf<ContentProfile.ContentType?>(null)

    /**
     * A row's "Get more" (SettingDef.getMore, wired by the host through SettingsEnv.getMoreOpen): "box64" opens the
     * Box64 (WOWBox64 on ARM64EC) sheet, "fex" the FEXCore one, "box64ec" the Box64EC one. False for any other
     * kind, or where this section has no download (not a Wine game / container, or no container form).
     */
    fun openGetMore(kind: String): Boolean {
        if (!applies()) return false
        sheet = when (kind) {
            "box64" -> if (env.arm64ec) ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 else ContentProfile.ContentType.CONTENT_TYPE_BOX64
            "fex" -> ContentProfile.ContentType.CONTENT_TYPE_FEXCORE
            "box64ec" -> ContentProfile.ContentType.CONTENT_TYPE_BOX64EC
            else -> return false
        }
        return true
    }

    private fun applies() = env.world == World.WINE && env.scope != EditorScope.APP && env.vm != null

    // The XMB editor has "Get more" on the Box64 / FEX / Box64EC version rows ([openGetMore]), so it lists no downloads of its
    // own; the plain Deck and the Lab style have no such link and keep the section.
    override fun extras(): Map<String, CategoryExtra> {
        if (!applies() || AppThemeState.deckXmbHome.value) return emptyMap()
        return mapOf("performance" to CategoryExtra(bottom = ::items))
    }

    private fun items(): List<PageExtra> {
        val arm64ec = env.arm64ec
        val box64 = if (arm64ec) ContentProfile.ContentType.CONTENT_TYPE_WOWBOX64 else ContentProfile.ContentType.CONTENT_TYPE_BOX64
        return actionItems("perf-downloads", L("Translator downloads"), listOfNotNull(
            DeckAction("box64", Icons.Filled.CloudDownload, if (arm64ec) L("Download WOWBox64 versions") else L("Download Box64 versions"),
                L("Installed ones appear under Box64 version.")) { sheet = box64 },
            if (arm64ec) DeckAction("fex", Icons.Filled.CloudDownload, L("Download FEXCore versions"),
                L("Installed ones appear under FEX version.")) { sheet = ContentProfile.ContentType.CONTENT_TYPE_FEXCORE } else null,
            if (arm64ec) DeckAction("box64ec", Icons.Filled.CloudDownload, L("Download Box64EC versions"),
                L("Runs 64-bit games when the 64-bit translator is Box64EC.")) { sheet = ContentProfile.ContentType.CONTENT_TYPE_BOX64EC } else null,
        ))
    }

    @Composable
    override fun Dialogs() {
        val type = sheet ?: return
        val vm = env.vm ?: return
        ContentDownloadSheet(
            contentType = type,
            onDismiss = { sheet = null },
            onContentChanged = {
                when (type) {
                    ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> vm.refreshFEXCoreVersions()
                    ContentProfile.ContentType.CONTENT_TYPE_BOX64EC -> vm.refreshBox64ECVersions()
                    else -> vm.refreshBox64Versions()
                }
            },
            inUseKey = when (type) {
                ContentProfile.ContentType.CONTENT_TYPE_FEXCORE -> vm.selectedFEXCoreVersion
                ContentProfile.ContentType.CONTENT_TYPE_BOX64EC -> vm.selectedBox64ECVersion
                else -> vm.selectedBox64Version
            },
        )
    }
}
