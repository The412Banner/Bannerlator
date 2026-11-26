// Ported from DroidDeck (Droid-Deck/DroidDeck app/src/main/java/com/droiddeck/launcher/gpu, GPL-3.0): device details by
// The412Banner (#295), extended by Kurt Himebauch, Danbro007, ASEbrahim, openMali, MaxsTechReview.
package com.winlator.star.deviceinfo

import android.os.Build

object SocNames {
    private val MODELS = mapOf(
        "SM8150" to "Snapdragon 855", "SM8250" to "Snapdragon 865", "SM8250-AC" to "Snapdragon 870",
        "SM8350" to "Snapdragon 888", "SM8450" to "Snapdragon 8 Gen 1", "SM8475" to "Snapdragon 8+ Gen 1",
        "SM8550" to "Snapdragon 8 Gen 2", "QCS8550" to "Snapdragon 8 Gen 2", "SM8650" to "Snapdragon 8 Gen 3",
        "SM8635" to "Snapdragon 8s Gen 3", "SM8735" to "Snapdragon 8s Gen 4",
        "SM8750" to "Snapdragon 8 Elite", "SM8750P" to "Snapdragon 8 Elite", "SM8850" to "Snapdragon 8 Elite Gen 5",
        "SM8975" to "Snapdragon 8 Elite Extreme Gen 6",
        "SM7325" to "Snapdragon 778G", "SM7450" to "Snapdragon 7 Gen 1", "SM7550" to "Snapdragon 7 Gen 3",
        "SM7675" to "Snapdragon 7+ Gen 3",
        "SM6375" to "Snapdragon 695",
        // Handheld parts: AYANEO Pocket FIT (SG8350P, GPU Adreno33v2 = the G3 Gen 3's "A33"), Retroid Pocket Duo (CQ7790S).
        "SG8350P" to "Snapdragon G3 Gen 3", "CQ7790S" to "Dragonwing Q-7790",
    )

    private val PLATFORMS = mapOf(
        "msmnile" to "Snapdragon 855/860", "kona" to "Snapdragon 865/870", "lahaina" to "Snapdragon 888",
        "taro" to "Snapdragon 8 Gen 1", "cape" to "Snapdragon 8+ Gen 1", "kalama" to "Snapdragon 8 Gen 2",
        "pineapple" to "Snapdragon 8 Gen 3", "sun" to "Snapdragon 8 Elite", "canoe" to "Snapdragon 8 Elite Gen 5",
    )

    /**
     * The reference top clock (MHz) of chips Samsung also ships with a faster prime core ("for Galaxy"). A Samsung phone
     * whose fastest core runs above this is named "<chip> for Galaxy"; the clock itself always comes from the phone.
     */
    private val REFERENCE_PRIME_MHZ = mapOf("SM8550" to 3190, "SM8650" to 3300, "SM8750" to 4320, "SM8850" to 4610)
    private const val GALAXY_MARGIN_MHZ = 50

    fun model(prop: (String) -> String = GpuInfo::systemProperty): String {
        val sdk = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN }.orEmpty() else ""
        return listOf(prop("ro.soc.model"), prop("ro.vendor.qti.soc_model"), sdk, prop("ro.fota.platform"))
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !Regex("(?i)^msm_\\d").containsMatchIn(it) }.orEmpty()
    }

    internal fun normalizedModel(model: String): String = model.trim().uppercase().removePrefix("QTI ").trim()

    /**
     * The chip's name: the exact model, then the model without a "-AB"-style suffix, then — only for an empty model or
     * a phone "SM" part — the board platform's family (a platform name is shared by different chips: the Retroid Pocket
     * Duo's Dragonwing Q-7790 and the Snapdragon 8 Elite are both "sun").
     */
    fun name(
        model: String,
        platform: String = GpuInfo.systemProperty("ro.board.platform"),
        maker: String = Build.MANUFACTURER.orEmpty(),
        primeKhz: Long = if (maker.equals("samsung", ignoreCase = true)) CpuCores.primeKhz() else 0,
    ): String? {
        val m = normalizedModel(model)
        val base = m.substringBefore('-')
        val known = MODELS[m] ?: MODELS[base]
        if (known != null) return known + galaxySuffix(base, maker, primeKhz)
        if (m.isNotEmpty() && !m.startsWith("SM")) return null
        return PLATFORMS[platform.lowercase()]?.let { "$it family" }
    }

    internal fun galaxySuffix(base: String, maker: String, primeKhz: Long): String {
        val reference = REFERENCE_PRIME_MHZ[base] ?: return ""
        return if (maker.equals("samsung", ignoreCase = true) && primeKhz / 1000 > reference + GALAXY_MARGIN_MHZ) " for Galaxy" else ""
    }

    fun label(model: String = model(), platform: String = GpuInfo.systemProperty("ro.board.platform")): String {
        val name = name(model, platform)
        return when {
            name != null && model.isNotEmpty() -> "$name ($model)"
            name != null -> name
            else -> model
        }
    }
}
