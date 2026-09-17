package com.videoconverter.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLanguageTest {
    @Test
    fun tagsRoundTrip() {
        assertNull(AppLanguage.System.languageTag())
        assertEquals("zh-CN", AppLanguage.ZhCn.languageTag())
        assertEquals("zh-TW", AppLanguage.ZhTw.languageTag())
        assertEquals("en", AppLanguage.En.languageTag())
        assertEquals("ja", AppLanguage.Ja.languageTag())
        assertEquals("ko", AppLanguage.Ko.languageTag())
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.ZhCn, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.System, appLanguageFromLocaleTags(emptyList()))
        assertEquals(AppLanguage.System, appLanguageFromLocaleTags(listOf("")))
        assertEquals(AppLanguage.Ja, appLanguageFromLocaleTags(listOf("ja")))
        assertEquals(AppLanguage.ZhCn, appLanguageFromLocaleTags(listOf("zh-CN", "en")))
    }

    @Test
    fun unmatchedSystemFallsBackToEnglish() {
        assertEquals(AppLanguage.En, fallbackLanguageForSystemTag("fr-FR"))
        assertEquals(AppLanguage.En, fallbackLanguageForSystemTag("de"))
        assertEquals(AppLanguage.ZhCn, fallbackLanguageForSystemTag("zh-CN"))
        assertEquals(AppLanguage.ZhCn, fallbackLanguageForSystemTag("zh-SG"))
        assertEquals(AppLanguage.ZhTw, fallbackLanguageForSystemTag("zh-TW"))
        assertEquals(AppLanguage.ZhTw, fallbackLanguageForSystemTag("zh-HK"))
        assertEquals(AppLanguage.ZhTw, fallbackLanguageForSystemTag("zh-MO"))
        assertEquals(AppLanguage.En, fallbackLanguageForSystemTag("en-US"))
        assertEquals(AppLanguage.Ja, fallbackLanguageForSystemTag("ja-JP"))
        assertEquals(AppLanguage.Ko, fallbackLanguageForSystemTag("ko-KR"))
    }
}
