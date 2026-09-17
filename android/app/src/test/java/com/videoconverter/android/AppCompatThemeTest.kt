package com.videoconverter.android

import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AppCompatThemeTest {
    @Test
    @Config(sdk = [31])
    fun api31ThemeIsAppCompat() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val theme = app.resources.newTheme()
        theme.applyStyle(R.style.Theme_LightTranscode, true)
        val value = TypedValue()
        assertTrue(
            "API 31 Theme.LightTranscode must be Theme.AppCompat so AppCompatActivity can start",
            theme.resolveAttribute(androidx.appcompat.R.attr.windowActionBar, value, true),
        )
    }
}
