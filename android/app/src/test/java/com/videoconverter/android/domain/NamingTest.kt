package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun renameStemRejectsBlankAndPath() {
        assertEquals("holiday", sanitizeRenameStem(" holiday "))
        assertEquals("holiday", sanitizeRenameStem("holiday.mp4"))
        assertNull(sanitizeRenameStem("   "))
        assertNull(sanitizeRenameStem("../secret"))
        assertNull(sanitizeRenameStem("a/b"))
        assertEquals("clip.mp4", renamedFileName("a.mov", "/out/clip.mp4", "clip"))
        assertEquals("holiday.mp4", renamedFileName("a.mov", "content://x/clip.mp4", "holiday"))
        assertTrue(canRenameJob(JobStatus.Completed))
        assertFalse(canRenameJob(JobStatus.Running))
        assertFalse(canRenameJob(JobStatus.Failed))
    }
}
