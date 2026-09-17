package com.videoconverter.android.ui

import com.videoconverter.android.ui.theme.ColorSchemeChoice
import com.videoconverter.android.ui.theme.IOS_BLUE_ARGB
import com.videoconverter.android.ui.theme.SEED_ARGB
import com.videoconverter.android.ui.theme.SeedColors
import com.videoconverter.android.ui.theme.ShapeTokens
import com.videoconverter.android.ui.theme.colorSchemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TokensTest {
    @Test
    fun seedIsCaramelNotIosBlue() {
        assertEquals(0xFFC45A2A.toInt(), SEED_ARGB)
        assertEquals(0xFF007AFF.toInt(), IOS_BLUE_ARGB)
        assertNotEquals(IOS_BLUE_ARGB, SeedColors.LightPrimary)
        assertNotEquals(IOS_BLUE_ARGB, SeedColors.DarkPrimary)
        assertEquals(0xFF9B4418.toInt(), SeedColors.LightPrimary)
        assertEquals(0xFFFFB595.toInt(), SeedColors.DarkPrimary)
    }

    @Test
    fun dynamicColorOnlyOnApi31WhenEnabled() {
        assertEquals(
            ColorSchemeChoice.DynamicLight,
            colorSchemeChoice(darkTheme = false, dynamicColor = true, sdkInt = 31),
        )
        assertEquals(
            ColorSchemeChoice.DynamicDark,
            colorSchemeChoice(darkTheme = true, dynamicColor = true, sdkInt = 31),
        )
        assertEquals(
            ColorSchemeChoice.SeedLight,
            colorSchemeChoice(darkTheme = false, dynamicColor = true, sdkInt = 30),
        )
        assertEquals(
            ColorSchemeChoice.SeedDark,
            colorSchemeChoice(darkTheme = true, dynamicColor = false, sdkInt = 34),
        )
    }

    @Test
    fun shapesMatchMaterialDefaults() {
        assertEquals(4f, ShapeTokens.ExtraSmall.value)
        assertEquals(8f, ShapeTokens.Small.value)
        assertEquals(12f, ShapeTokens.Medium.value)
        assertEquals(16f, ShapeTokens.Large.value)
        assertEquals(28f, ShapeTokens.ExtraLarge.value)
        assertEquals(12f, ShapeTokens.Panel.value)
    }
}
