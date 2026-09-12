package com.winlator.star.ui.theme

import androidx.compose.ui.graphics.Color

val Background = Color(0xFF000000)
val Surface = Color(0xFF000000)
val SurfaceVariant = Color(0xFF050505)
val Primary = Color(0xFF0055FF)
val PrimaryVariant = Color(0xFF0044CC)
val PrimaryDim = Color(0xFF002277)
val OnPrimary = Color(0xFFFFFFFF)
val OnSurface = Color(0xFFEEEEEE)
val OnSurfaceVariant = Color(0xFF999999)
val OnBackground = Color(0xFFFFFFFF)
val Error = Color(0xFFCF6679)
/** Destructive actions — delete buttons, selected-for-removal state. */
val DangerRed = Color(0xFFE05C4A)

// ── Store-semantic colours ──────────────────────────────────────────────────────────
// Steam's own price conventions, and the ONLY colours in the storefront that are not taken
// from the live ColorScheme. Same rationale as DangerRed: a discount badge that turns pink
// because the user picked a pink accent stops reading as "on sale", and a "Free to Play"
// line has to say free, not "accent". Chosen to stay legible on the AMOLED black ground.
/** Ink for the discount-percentage chip ("-50%"). */
val StoreDiscountInk = Color(0xFFBEEE11)
/** Fill behind [StoreDiscountInk] on the discount chip. */
val StoreDiscountBg = Color(0xFF2C4210)
/** "Free to Play" price line, and the free-title action button's ink. */
val StoreFreeGreen = Color(0xFF8FD14F)
val Divider = Color(0xFF111111)
val GlowPurple = Primary
val AccentBlue = Primary
