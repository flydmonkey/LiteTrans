package com.videoconverter.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {
    @Test
    fun uniqueName() {
        assertEquals("/out/clip.mp4", allocateOutputPath("/out", "clip", "mp4", exists = { false }))
        assertEquals("假期.mp4", uniqueFileName("假期", "mp4", taken = { false }))
    }

    @Test
    fun collisionAppendsTimestamp() {
        val stamp = "20260918_033012"
        assertEquals(
            "/out/clip_${stamp}.mp4",
            allocateOutputPath("/out", "clip", "mp4", { it == "/out/clip.mp4" }, { stamp }),
        )
        assertEquals(
            "假期_${stamp}.mp4",
            uniqueFileName("假期", "mp4", { it == "假期.mp4" }, { stamp }),
        )
    }

    @Test
    fun collisionSkipsTakenTimestamps() {
        val stamp = "20260918_033012"
        val taken = setOf("/out/clip.mp4", "/out/clip_${stamp}.mp4")
        assertEquals(
            "/out/clip_${stamp}_1.mp4",
            allocateOutputPath("/out", "clip", "mp4", { it in taken }, { stamp }),
        )
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
