package com.videoconverter.android.ui

import com.videoconverter.android.ui.theme.LightTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class TokensTest {
    @Test
    fun desktopPaletteIsPinned() {
        assertEquals(0xFFECECE8.toInt(), LightTokens.Canvas)
        assertEquals(0xFF1F2428.toInt(), LightTokens.Ink)
        assertEquals(0xFF5C6460.toInt(), LightTokens.Muted)
        assertEquals(0xFFF7F7F4.toInt(), LightTokens.Card)
        assertEquals(0xFFE4E4DE.toInt(), LightTokens.Border)
        assertEquals(0xFFC45A2A.toInt(), LightTokens.Accent)
        assertEquals(0xFFE2E2DC.toInt(), LightTokens.Chip)
        assertEquals(0xFFFFF1D8.toInt(), LightTokens.Notice)
        assertEquals(0xFFFDECE6.toInt(), LightTokens.Bad)
        assertEquals(0xFFF0C9BC.toInt(), LightTokens.BadBorder)
        assertEquals(0xFFF7F7F4.toInt(), LightTokens.OnDark)
        assertEquals(0xFFC9CFCB.toInt(), LightTokens.OnDarkMuted)
    }
}
