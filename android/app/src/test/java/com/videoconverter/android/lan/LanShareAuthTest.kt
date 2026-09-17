package com.videoconverter.android.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareAuthTest {
    @Test
    fun defaultsAreOffAndEmptyToken() {
        val settings = LanShareSettings()
        assertFalse(settings.enabled)
        assertEquals("", settings.token)
    }

    @Test
    fun normalizeTrimsAndBlankBecomesEmpty() {
        assertEquals("secret", normalizeLanToken("  secret  "))
        assertEquals("", normalizeLanToken("   "))
        assertEquals("", normalizeLanToken(""))
    }

    @Test
    fun emptyTokenAllowsMissingOrAnyK() {
        assertTrue(lanTokenAllows("", null))
        assertTrue(lanTokenAllows("", "whatever"))
    }

    @Test
    fun setTokenRequiresExactMatch() {
        assertFalse(lanTokenAllows("pw", null))
        assertFalse(lanTokenAllows("pw", ""))
        assertFalse(lanTokenAllows("pw", "PW"))
        assertTrue(lanTokenAllows("pw", "pw"))
    }
}
