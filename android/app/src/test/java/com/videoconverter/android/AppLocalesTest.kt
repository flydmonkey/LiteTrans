package com.videoconverter.android

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class AppLocalesTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before
    @After
    fun resetLocales() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test
    fun emptyAppLocalesKeepsSystemContext() {
        val localized = app.withAppLocales()
        assertSame(app, localized)
        assertEquals("Download", localized.getString(R.string.lan_download))
        assertEquals("Checking the queue", localized.getString(R.string.notify_checking_queue))
    }

    @Test
    fun zhCnAppLocalesOverrideSystemEnglish() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        assertEquals("Download", app.getString(R.string.lan_download))
        val localized = app.withAppLocales()
        assertEquals("下载", localized.getString(R.string.lan_download))
        assertEquals("正在检查转码队列", localized.getString(R.string.notify_checking_queue))
    }
}
