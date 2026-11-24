package com.winlator.star.contents

import com.winlator.star.container.LayerList

/** The two types the Wine / Proton pickers list, Proton first. */
internal val LAYER_TYPES = listOf(ContentProfile.ContentType.CONTENT_TYPE_PROTON, ContentProfile.ContentType.CONTENT_TYPE_WINE)

internal fun ContentProfile.isLayer(): Boolean =
    type == ContentProfile.ContentType.CONTENT_TYPE_WINE || type == ContentProfile.ContentType.CONTENT_TYPE_PROTON

/** A catalog row (remoteUrl set) for the pickers, found again by its remoteUrl; null for other profiles. */
internal fun ContentProfile.toCatalogRow(): LayerList.CatalogRow? {
    val url = remoteUrl
    val label = verName
    if (!isLayer() || url.isNullOrEmpty() || label.isNullOrEmpty()) return null
    return LayerList.CatalogRow(url, type == ContentProfile.ContentType.CONTENT_TYPE_WINE, label, versionName, verCode, recommended)
}

/** An installed layer for the pickers, found again by its entry name; null for other profiles. */
internal fun ContentProfile.toInstalledLayer(): LayerList.Installed? {
    val name = verName
    if (!isLayer() || !remoteUrl.isNullOrEmpty() || name.isNullOrEmpty()) return null
    return LayerList.Installed(ContentsManager.getEntryName(this), type == ContentProfile.ContentType.CONTENT_TYPE_WINE, name, verCode, desc)
}

/** The process's last parsed catalog as picker rows; empty before any fetch. */
internal fun cachedLayerCatalog(): List<LayerList.CatalogRow> =
    ContentsManager.getCachedRemoteProfiles().orEmpty().mapNotNull { it.toCatalogRow() }
