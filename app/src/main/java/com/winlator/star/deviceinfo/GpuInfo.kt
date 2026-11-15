// Ported from DroidDeck (Droid-Deck/DroidDeck app/src/main/java/com/droiddeck/launcher/gpu/GpuInfo.kt, GPL-3.0): device
// details by The412Banner (#295), extended by Kurt Himebauch, Danbro007, ASEbrahim, openMali, MaxsTechReview.
// Only what the device details pop-up shows: the GPU's name, family and where the model came from.
package com.winlator.star.deviceinfo

import android.os.Build
import java.io.File

data class GpuInfo(
    val name: String,
    val model: Int,
    val family: String,
    val kgslName: String = "",
    val modelSource: String = "",
    val kgslChipId: String = "",
) {
    companion object {
        fun detect(): GpuInfo {
            val adreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
            val raw = readSys("/sys/class/kgsl/kgsl-3d0/gpu_model")
            val chipId = readSys("/sys/class/kgsl/kgsl-3d0/gpu_chipid").orEmpty()
            val vulkanName = VulkanInfo.cachedOrNull()?.get("device")
            val (model, source) = if (adreno) modelFromNames(raw, { vulkanName }, { platformModel() }) else 0 to ""
            // Mali, named by the vendor's Vulkan driver ("Mali-G720-Immortalis MC12"), by kbase or by its chip.
            val mali = if (adreno || !File("/dev/mali0").exists()) null else maliName(vulkanName)
            val name = when {
                mali != null && mali.isNotEmpty() -> mali
                !adreno -> vulkanName?.takeIf { it.isNotBlank() } ?: Build.HARDWARE.ifBlank { "this GPU" }
                model > 0 -> "Adreno $model"
                else -> "Adreno"
            }
            return GpuInfo(name = name, model = model, family = familyOf(adreno, model, mali, name),
                kgslName = raw.orEmpty(), modelSource = source, kgslChipId = chipId)
        }

        internal fun threeDigits(text: String): Int = Regex("""(?i)\badreno\s*(?:\(TM\)\s*)?(\d{3})(?!\d)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        internal fun modelFromNames(kernelName: String?, vulkanName: () -> String?, fallback: () -> Int): Pair<Int, String> {
            kernelName?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "kernel" }
            vulkanName()?.let { threeDigits(it) }?.takeIf { it > 0 }?.let { return it to "vulkan" }
            return fallback().let { if (it > 0) it to "platform" else 0 to "" }
        }

        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
        )

        internal fun platformModel(
            platform: String = systemProperty("ro.board.platform"),
            qtiSocName: String = systemProperty("ro.vendor.qti.soc_name"),
            socModel: String = SocNames.model(),
        ): Int = if (SocNames.normalizedModel(socModel) == "SM8975" || SocNames.normalizedModel(qtiSocName) == "SM8975") 850
            else PLATFORMS[platform.lowercase()] ?: PLATFORMS[qtiSocName.lowercase()] ?: 0

        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (e: Exception) {
            ""
        }

        private fun maliName(vulkanName: String?): String =
            vulkanName?.takeIf { it.contains("Mali", ignoreCase = true) }
                ?: readSys("/sys/class/misc/mali0/device/gpuinfo")?.takeIf { it.contains("Mali", ignoreCase = true) }
                    ?.substringBefore(" ")
                ?: maliOfSoc(socIds())
                ?: ""

        private fun socIds(): List<String> = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null,
            systemProperty("ro.board.platform"), systemProperty("ro.soc.model"), Build.HARDWARE, Build.BOARD,
        ).filter { it.isNotBlank() }

        /** Chips whose Mali is known for certain (MediaTek Dimensity / Helio / Kompanio, Google Tensor). */
        private val MALI_SOCS = mapOf(
            "mt6993" to "Mali-G1-Ultra", "mt6991" to "Mali-G925", "mt6989" to "Mali-G720", "mt6899" to "Mali-G720", "mt6985" to "Mali-G715",
            "mt6983" to "Mali-G710", "mt6897" to "Mali-G615", "mt6878" to "Mali-G615", "mt6895" to "Mali-G610",
            "mt6896" to "Mali-G610", "mt6886" to "Mali-G610", "mt6893" to "Mali-G77", "mt6891" to "Mali-G77",
            "mt6889" to "Mali-G77", "mt6885" to "Mali-G77", "mt6877" to "Mali-G68", "mt6853" to "Mali-G57",
            "mt6833" to "Mali-G57", "mt6835" to "Mali-G57", "mt6789" to "Mali-G57", "mt6785" to "Mali-G76",
            "mt6781" to "Mali-G52", "mt6769" to "Mali-G52", "mt8192" to "Mali-G57", "mt8195" to "Mali-G57",
            "gs101" to "Mali-G78", "gs201" to "Mali-G710", "zuma" to "Mali-G715", "zumapro" to "Mali-G715",
            "s5e8835" to "Mali-G68", "s5e8825" to "Mali-G68",
        )

        internal fun maliOfSoc(ids: List<String>): String? = ids.firstNotNullOfOrNull { id ->
            MALI_SOCS[id.trim().lowercase()]?.let { "$it (by chip ${id.trim().lowercase()})" }
        }

        internal fun familyOf(adreno: Boolean, model: Int, mali: String?, name: String): String = when {
            !adreno && mali != null -> "Mali"
            !adreno && name.contains("Xclipse", ignoreCase = true) -> "Samsung Xclipse"
            !adreno && name.contains("PowerVR", ignoreCase = true) -> "PowerVR"
            !adreno -> "Not an Adreno GPU"
            model == 0 -> "Adreno"
            model >= 800 -> "Adreno 8xx"
            model in listOf(710, 720, 722) -> "Adreno 710/720/722"
            model >= 700 -> "Adreno 7xx"
            model >= 600 -> "Adreno 6xx"
            else -> "Adreno"
        }
    }
}
