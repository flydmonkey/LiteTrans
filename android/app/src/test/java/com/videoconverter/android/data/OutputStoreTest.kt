package com.videoconverter.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputStoreTest {
    @Test
    fun collisionUsesNumericSuffix() {
        assertEquals(
            "clip-1.mp4",
            uniqueDisplayName("clip", "mp4", setOf("clip.mp4")),
        )
    }
}
