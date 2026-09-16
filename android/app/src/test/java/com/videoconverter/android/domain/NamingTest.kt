package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NamingTest {
    @Test
    fun uniqueName() {
        assertEquals("/out/clip.mp4", allocateOutputPath("/out", "clip", "mp4") { false })
    }

    @Test
    fun collisionUsesNumericSuffix() {
        assertEquals("/out/clip-1.mp4", allocateOutputPath("/out", "clip", "mp4") { it == "/out/clip.mp4" })
    }

    @Test
    fun collisionSkipsTakenSuffixes() {
        val taken = setOf("/out/clip.mp4", "/out/clip-1.mp4")
        assertEquals("/out/clip-2.mp4", allocateOutputPath("/out", "clip", "mp4") { it in taken })
    }

    @Test
    fun partialKeepsRealExtension() {
        assertEquals(
            "/out/[4K高清]clip_mp4-h264.partial.mp4",
            partialOutputPath("/out/[4K高清]clip_mp4-h264.mp4"),
        )
    }

    @Test
    fun ffmpegFileArgPrefixesLocalPaths() {
        assertEquals("file:/tmp/[4K]clip.mp4", ffmpegFileArg("/tmp/[4K]clip.mp4"))
        assertEquals("pipe:1", ffmpegFileArg("pipe:1"))
        assertEquals("/proc/self/fd/7", ffmpegFileArg("/proc/self/fd/7"))
    }
}
