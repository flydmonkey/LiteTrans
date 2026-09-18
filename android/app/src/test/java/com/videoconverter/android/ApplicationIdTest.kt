package com.videoconverter.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ApplicationIdTest {
    @Test
    fun applicationIdMatchesIosBundleIdentifier() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals("io.github.flydmonkey.litetrans", app.packageName)
    }
}
