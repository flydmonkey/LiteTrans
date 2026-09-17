package com.videoconverter.android.ui.theme

import androidx.compose.ui.unit.dp

const val SEED_ARGB = 0xFFC45A2A.toInt()
const val IOS_BLUE_ARGB = 0xFF007AFF.toInt()
const val DYNAMIC_COLOR_MIN_SDK = 31

enum class ColorSchemeChoice { DynamicLight, DynamicDark, SeedLight, SeedDark }

fun colorSchemeChoice(
    darkTheme: Boolean,
    dynamicColor: Boolean,
    sdkInt: Int,
): ColorSchemeChoice = when {
    dynamicColor && sdkInt >= DYNAMIC_COLOR_MIN_SDK && darkTheme -> ColorSchemeChoice.DynamicDark
    dynamicColor && sdkInt >= DYNAMIC_COLOR_MIN_SDK -> ColorSchemeChoice.DynamicLight
    darkTheme -> ColorSchemeChoice.SeedDark
    else -> ColorSchemeChoice.SeedLight
}

object SeedColors {
    const val LightPrimary = 0xFF9B4418.toInt()
    const val LightOnPrimary = 0xFFFFFFFF.toInt()
    const val LightPrimaryContainer = 0xFFFFDBCD.toInt()
    const val LightOnPrimaryContainer = 0xFF370E00.toInt()
    const val LightSecondary = 0xFF77574C.toInt()
    const val LightOnSecondary = 0xFFFFFFFF.toInt()
    const val LightSecondaryContainer = 0xFFFFDBD0.toInt()
    const val LightOnSecondaryContainer = 0xFF2C160E.toInt()
    const val LightTertiary = 0xFF6B5E2F.toInt()
    const val LightOnTertiary = 0xFFFFFFFF.toInt()
    const val LightTertiaryContainer = 0xFFF4E2A7.toInt()
    const val LightOnTertiaryContainer = 0xFF231B00.toInt()
    const val LightError = 0xFFBA1A1A.toInt()
    const val LightOnError = 0xFFFFFFFF.toInt()
    const val LightErrorContainer = 0xFFFFDAD6.toInt()
    const val LightOnErrorContainer = 0xFF410002.toInt()
    const val LightBackground = 0xFFFFF8F6.toInt()
    const val LightOnBackground = 0xFF201A18.toInt()
    const val LightSurface = 0xFFFFF8F6.toInt()
    const val LightOnSurface = 0xFF201A18.toInt()
    const val LightSurfaceVariant = 0xFFF5DED6.toInt()
    const val LightOnSurfaceVariant = 0xFF53433E.toInt()
    const val LightOutline = 0xFF85736D.toInt()
    const val LightOutlineVariant = 0xFFD8C2BB.toInt()

    const val DarkPrimary = 0xFFFFB595.toInt()
    const val DarkOnPrimary = 0xFF5A1C00.toInt()
    const val DarkPrimaryContainer = 0xFF7C2E05.toInt()
    const val DarkOnPrimaryContainer = 0xFFFFDBCD.toInt()
    const val DarkSecondary = 0xFFE7BDB0.toInt()
    const val DarkOnSecondary = 0xFF442A22.toInt()
    const val DarkSecondaryContainer = 0xFF5D4035.toInt()
    const val DarkOnSecondaryContainer = 0xFFFFDBD0.toInt()
    const val DarkTertiary = 0xFFD7C68D.toInt()
    const val DarkOnTertiary = 0xFF3A2F05.toInt()
    const val DarkTertiaryContainer = 0xFF524619.toInt()
    const val DarkOnTertiaryContainer = 0xFFF4E2A7.toInt()
    const val DarkError = 0xFFFFB4AB.toInt()
    const val DarkOnError = 0xFF690005.toInt()
    const val DarkErrorContainer = 0xFF93000A.toInt()
    const val DarkOnErrorContainer = 0xFFFFDAD6.toInt()
    const val DarkBackground = 0xFF181210.toInt()
    const val DarkOnBackground = 0xFFECE0DC.toInt()
    const val DarkSurface = 0xFF181210.toInt()
    const val DarkOnSurface = 0xFFECE0DC.toInt()
    const val DarkSurfaceVariant = 0xFF53433E.toInt()
    const val DarkOnSurfaceVariant = 0xFFD8C2BB.toInt()
    const val DarkOutline = 0xFFA08D86.toInt()
    const val DarkOutlineVariant = 0xFF53433E.toInt()
}

object ShapeTokens {
    val ExtraSmall = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 16.dp
    val ExtraLarge = 28.dp
    val Panel = Medium
    val Chip = Small
    val Stamp = Medium
    val Glyph = Small
    val Dialog = ExtraLarge
}
