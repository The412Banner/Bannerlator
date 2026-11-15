// Ported from DroidDeck (Droid-Deck/DroidDeck app/src/main/java/com/droiddeck/launcher/gpu, GPL-3.0): device details by
// The412Banner (#295), extended by Kurt Himebauch, Danbro007, ASEbrahim, openMali, MaxsTechReview.
package com.winlator.star.deviceinfo

import android.os.Build

object SocNames {
    private val MODELS = mapOf(
        "SM8150" to "Snapdragon 855", "SM8250" to "Snapdragon 865", "SM8250-AC" to "Snapdragon 870",
        "SM8350" to "Snapdragon 888", "SM8450" to "Snapdragon 8 Gen 1", "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8550" to "Snapdragon 8 Gen 2", "QCS8550" to "Snapdragon 8 Gen 2", "SM8650" to "Snapdragon 8 Gen 3",
        "SM8750" to "Snapdragon 8 Elite", "SM8850" to "Snapdragon 8 Elite Gen 5",
        "SM8975" to "Snapdragon 8 Elite Extreme Gen 6",
        "SM7325" to "Snapdragon 778G", "SM7450" to "Snapdragon 7 Gen 1", "SM7550" to "Snapdragon 7 Gen 3",
        "SM6375" to "Snapdragon 695",
    )

    private val PLATFORMS = mapOf(
        "msmnile" to "Snapdragon 855/860", "kona" to "Snapdragon 865/870", "lahaina" to "Snapdragon 888",
        "taro" to "Snapdragon 8 Gen 1", "cape" to "Snapdragon 8+ Gen 1", "kalama" to "Snapdragon 8 Gen 2",
        "pineapple" to "Snapdragon 8 Gen 3", "sun" to "Snapdragon 8 Elite",
    )

    fun model(prop: (String) -> String = GpuInfo::systemProperty): String {
        val sdk = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }.orEmpty() else ""
        return listOf(prop("ro.soc.model"), prop("ro.vendor.qti.soc_model"), sdk, prop("ro.fota.platform"))
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !Regex("(?i)^msm_\\d").containsMatchIn(it) }.orEmpty()
    }

    internal fun normalizedModel(model: String): String = model.trim().uppercase().removePrefix("QTI ").trim()

    fun name(model: String, platform: String = GpuInfo.systemProperty("ro.board.platform")): String? =
        MODELS[normalizedModel(model)] ?: PLATFORMS[platform.lowercase()]?.let { "$it family" }

    fun label(model: String = model(), platform: String = GpuInfo.systemProperty("ro.board.platform")): String {
        val name = name(model, platform)
        return when {
            name != null && model.isNotEmpty() -> "$name ($model)"
            name != null -> name
            else -> model
        }
    }
}
