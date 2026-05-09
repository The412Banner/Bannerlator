package com.winlator.cmod.ui.screens.adrenodownload

data class RemoteDriverSource(
    val name: String,
    val jsonUrl: String,
)

data class RemoteDriverEntry(
    val source: String,
    val displayName: String,
    val downloadUrl: String,
)

object DriverSources {
    private const val NIGHTLIES = "https://raw.githubusercontent.com/The412Banner/Nightlies/refs/heads/main"

    val ALL: List<RemoteDriverSource> = listOf(
        RemoteDriverSource("Kimchi", "$NIGHTLIES/kimchi_drivers.json"),
        RemoteDriverSource("Maxes MTR", "$NIGHTLIES/mtr_drivers.json"),
        RemoteDriverSource("Banners-Turnip", "$NIGHTLIES/banners-turnip_drivers.json"),
        RemoteDriverSource("StevenMXZ", "$NIGHTLIES/stevenmxz_drivers.json"),
        RemoteDriverSource("whitebelyash", "$NIGHTLIES/white_drivers.json"),
    )
}
