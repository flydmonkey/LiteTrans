package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test
    fun progressFromOutTime() {
        val percent = parseProgressLine("out_time_ms=5000000", 10.0)!!
        assertEquals(50.0, percent, 0.01)
    }

    @Test
    fun outTimeUsIsAlsoMicroseconds() {
        assertEquals(25.0, parseProgressLine("out_time_us=2500000", 10.0)!!, 0.01)
    }

    @Test
    fun progressIsClampedToValidRange() {
        assertEquals(0.0, parseProgressLine("out_time_ms=-1", 10.0)!!, 0.01)
        assertEquals(100.0, parseProgressLine("out_time_ms=20000000", 10.0)!!, 0.01)
    }

    @Test
    fun invalidProgressInputReturnsNull() {
        assertNull(parseProgressLine("progress=continue", 10.0))
        assertNull(parseProgressLine("out_time_ms=invalid", 10.0))
        assertNull(parseProgressLine("out_time_ms=5000000", 0.0))
        assertNull(parseProgressLine("out_time_ms=5000000", -1.0))
    }
}
